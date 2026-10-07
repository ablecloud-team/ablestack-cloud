// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package org.apache.cloudstack.storage.dataservice;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import com.cloud.storage.DataStoreRole;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.UserVmManager;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.UserVmDao;
import org.apache.cloudstack.engine.orchestration.service.VolumeOrchestrationService;
import org.apache.cloudstack.engine.subsystem.api.storage.TemplateDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.TemplateInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeService;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeService.VolumeApiResult;

/** Only ROOT bindings change. The caller persists the transaction and owns quiesce/boot/verification. */
public final class StorageServiceRootVolumeSwap {
    public interface Atomic {
        <T> T execute(Supplier<T> action);
    }
    private final VolumeDao volumes;
    private final UserVmDao vms;
    private final VolumeOrchestrationService orchestration;
    private final VolumeDataFactory volumeFactory;
    private final TemplateDataFactory templateFactory;
    private final VolumeService volumeService;
    private final Atomic atomic;

    public StorageServiceRootVolumeSwap(VolumeDao volumes, UserVmDao vms, VolumeOrchestrationService orchestration,
            VolumeDataFactory volumeFactory, TemplateDataFactory templateFactory, VolumeService volumeService) {
        this(volumes, vms, orchestration, volumeFactory, templateFactory, volumeService, new Atomic() {
            public <T> T execute(Supplier<T> action) { return Transaction.execute((com.cloud.utils.db.TransactionCallback<T>) status -> action.get()); }
        });
    }
    StorageServiceRootVolumeSwap(VolumeDao volumes, UserVmDao vms, VolumeOrchestrationService orchestration,
            VolumeDataFactory volumeFactory, TemplateDataFactory templateFactory, VolumeService volumeService, Atomic atomic) {
        this.volumes=volumes;this.vms=vms;this.orchestration=orchestration;
        this.volumeFactory=volumeFactory;this.templateFactory=templateFactory;this.volumeService=volumeService;this.atomic=atomic;
    }

    private UserVmVO vm(long id) {
        UserVmVO vm=vms.findById(id);
        if (vm==null || !UserVmManager.SHAREDFSVM.equals(vm.getUserVmType())) {
            throw new CloudRuntimeException("ROOT swap is restricted to an existing SharedFS SystemVM");
        }
        if (vm.getState()!=VirtualMachine.State.Running && vm.getState()!=VirtualMachine.State.Stopped) {
            throw new CloudRuntimeException("SharedFS SystemVM is in a transitional state");
        }
        return vm;
    }
    private VolumeVO root(long id, UserVmVO vm) {
        VolumeVO root=volumes.findById(id);
        if (root==null || root.getVolumeType()!=Volume.Type.ROOT || root.getRemoved()!=null
                || root.getAccountId()!=vm.getAccountId() || root.getDataCenterId()!=vm.getDataCenterId()) {
            throw new CloudRuntimeException("ROOT volume scope changed");
        }
        return root;
    }
    private void activeRoot(UserVmVO vm, VolumeVO expected) {
        List<VolumeVO> roots=volumes.findByInstanceAndType(vm.getId(),Volume.Type.ROOT);
        if (roots.size()!=1 || roots.get(0).getId()!=expected.getId() || !Objects.equals(expected.getInstanceId(),vm.getId())) {
            throw new CloudRuntimeException("Expected current ROOT binding changed");
        }
    }

    /** Allocate once without publishing a second active ROOT; do not change the running VM template. */
    public VolumeVO allocate(long vmId,long previousRootId,VMTemplateVO target,String operationUuid) {
        UUID operation=UUID.fromString(operationUuid);
        UserVmVO vm=vm(vmId);VolumeVO previous=root(previousRootId,vm);activeRoot(vm,previous);
        if (target==null || previous.getPoolId()==null || previous.getDeviceId()==null
                || previous.getState()!=Volume.State.Ready) throw new CloudRuntimeException("Previous ROOT is not ready for staging");
        String uuid=UUID.nameUUIDFromBytes(("SharedFSRoot:"+vmId+":"+operation).getBytes(StandardCharsets.UTF_8)).toString();
        return atomic.execute(() -> {
            VolumeVO existing=volumes.findByUuid(uuid);
            if (existing!=null) {
                root(existing.getId(),vm);
                if (existing.getInstanceId()!=null || !Objects.equals(existing.getTemplateId(),target.getId())) {
                    throw new CloudRuntimeException("Staged ROOT provenance changed");
                }
                return existing;
            }
            Volume allocated=orchestration.allocateDuplicateVolume(previous,null,target.getId());
            VolumeVO staged=volumes.findById(allocated.getId());
            staged.setUuid(uuid);staged.setInstanceId(null);staged.setDeviceId(previous.getDeviceId());
            staged.setSize(Math.max(previous.getSize(),target.getSize()==null?0:target.getSize()));
            // ROOT retained for rollback is never implicitly recreatable or garbage-collected as a VM scratch disk.
            staged.setRecreatable(false);
            if (!volumes.update(staged.getId(),staged)) throw new CloudRuntimeException("Unable to persist detached staged ROOT");
            return staged;
        });
    }

    /** Uses the explicit target template; the generic create path would select the running VM's old template. */
    public VolumeVO prepare(long vmId,long previousRootId,long stagedRootId,long targetTemplateId) {
        UserVmVO vm=vm(vmId);VolumeVO previous=root(previousRootId,vm);activeRoot(vm,previous);
        VolumeVO staged=root(stagedRootId,vm);
        if (staged.getInstanceId()!=null || !Objects.equals(staged.getTemplateId(),targetTemplateId)) {
            throw new CloudRuntimeException("Staged ROOT binding changed before preparation");
        }
        if (staged.getState()==Volume.State.Ready) return staged;
        if (staged.getState()!=Volume.State.Allocated) throw new CloudRuntimeException("Staged ROOT creation requires reconciliation");
        TemplateInfo template=templateFactory.getTemplate(targetTemplateId,DataStoreRole.Image,vm.getDataCenterId());
        if (template==null) throw new CloudRuntimeException("Target template is not available in this zone");
        VolumeInfo volume=volumeFactory.getVolume(stagedRootId);volume.setDestinationHostId(vm.getHostId());
        try {
            VolumeApiResult result=volumeService.createVolumeFromTemplateAsync(volume,previous.getPoolId(),template).get(15,TimeUnit.MINUTES);
            if (result==null || result.isFailed()) throw new CloudRuntimeException("Staged ROOT creation failed or requires reconciliation");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();throw new CloudRuntimeException("Staged ROOT preparation interrupted",interrupted);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException unavailable) {
            throw new CloudRuntimeException("Staged ROOT preparation requires reconciliation",unavailable);
        }
        VolumeVO ready=root(stagedRootId,vm);
        if (ready.getState()!=Volume.State.Ready || ready.getInstanceId()!=null
                || !Objects.equals(ready.getPoolId(),previous.getPoolId())) throw new CloudRuntimeException("Staged ROOT was not verified on the previous primary storage");
        return ready;
    }

    /** Reused in both directions. Old ROOT is detached and retained; no destroy/expunge is called. */
    public void swap(long vmId,long expectedRootId,long replacementRootId,long expectedTemplateId,
            long replacementTemplateId,long replacementGuestOsId) {
        atomic.execute(() -> {
            UserVmVO vm=vm(vmId);
            if (vm.getState()!=VirtualMachine.State.Stopped || vm.getTemplateId()!=expectedTemplateId) {
                throw new CloudRuntimeException("ROOT swap requires the expected stopped VM");
            }
            VolumeVO old=root(expectedRootId,vm);VolumeVO replacement=root(replacementRootId,vm);activeRoot(vm,old);
            if (replacement.getState()!=Volume.State.Ready || replacement.getInstanceId()!=null
                    || !Objects.equals(replacement.getTemplateId(),replacementTemplateId)
                    || !Objects.equals(replacement.getPoolId(),old.getPoolId()) || old.getDeviceId()==null) {
                throw new CloudRuntimeException("Replacement ROOT scope or readiness changed");
            }
            old.setRecreatable(false);
            if (!volumes.update(old.getId(),old)) throw new CloudRuntimeException("Unable to protect previous ROOT");
            volumes.detachVolume(old.getId());
            volumes.attachVolume(replacement.getId(),vmId,old.getDeviceId());
            vm.setTemplateId(replacementTemplateId);vm.setGuestOSId(replacementGuestOsId);
            if (!vms.update(vmId,vm)) throw new CloudRuntimeException("Unable to commit ROOT and template binding");
            return null;
        });
    }
}
