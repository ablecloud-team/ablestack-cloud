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

import java.util.List;
import java.util.function.Supplier;
import org.junit.Before;
import org.junit.Test;
import org.junit.Assert;
import org.mockito.Mockito;
import com.cloud.storage.DataStoreRole;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;
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
import org.apache.cloudstack.framework.async.AsyncCallFuture;

public class StorageServiceRootVolumeSwapTest {
    private VolumeDao volumes;
    private UserVmDao vms;
    private UserVmVO vm;
    private VolumeVO previous;
    private VolumeVO staged;
    private VMTemplateVO target;
    private VolumeOrchestrationService orchestration;
    private VolumeDataFactory factory;
    private TemplateDataFactory templates;
    private VolumeService service;
    private StorageServiceRootVolumeSwap swap;

    @Before public void setup() {
        volumes=Mockito.mock(VolumeDao.class);vms=Mockito.mock(UserVmDao.class);
        orchestration=Mockito.mock(VolumeOrchestrationService.class);
        factory=Mockito.mock(VolumeDataFactory.class);templates=Mockito.mock(TemplateDataFactory.class);service=Mockito.mock(VolumeService.class);
        vm=Mockito.mock(UserVmVO.class);Mockito.when(vm.getId()).thenReturn(7L);Mockito.when(vm.getAccountId()).thenReturn(2L);
        Mockito.when(vm.getDataCenterId()).thenReturn(5L);Mockito.when(vm.getHostId()).thenReturn(13L);
        Mockito.when(vm.getUserVmType()).thenReturn(UserVmManager.SHAREDFSVM);Mockito.when(vm.getTemplateId()).thenReturn(41L);
        Mockito.when(vm.getState()).thenReturn(VirtualMachine.State.Running);Mockito.when(vms.findById(7L)).thenReturn(vm);
        Mockito.when(vms.update(Mockito.eq(7L),Mockito.any())).thenReturn(true);
        previous=root(10L,41L,7L);staged=root(20L,99L,null);
        Mockito.when(volumes.findById(10L)).thenReturn(previous);Mockito.when(volumes.findById(20L)).thenReturn(staged);
        Mockito.when(volumes.findByInstanceAndType(7L,Volume.Type.ROOT)).thenReturn(List.of(previous));
        Mockito.when(volumes.update(Mockito.anyLong(),Mockito.any())).thenReturn(true);
        target=Mockito.mock(VMTemplateVO.class);Mockito.when(target.getId()).thenReturn(99L);
        Mockito.when(target.getSize()).thenReturn(6L*1024*1024*1024);
        swap=new StorageServiceRootVolumeSwap(volumes,vms,orchestration,factory,templates,service,new StorageServiceRootVolumeSwap.Atomic(){
            public <T> T execute(Supplier<T> action){return action.get();}
        });
    }
    private VolumeVO root(long id,long template,Long attached) {
        VolumeVO root=Mockito.mock(VolumeVO.class);
        Mockito.when(root.getId()).thenReturn(id);Mockito.when(root.getVolumeType()).thenReturn(Volume.Type.ROOT);
        Mockito.when(root.getTemplateId()).thenReturn(template);Mockito.when(root.getInstanceId()).thenReturn(attached);
        Mockito.when(root.getAccountId()).thenReturn(2L);Mockito.when(root.getDataCenterId()).thenReturn(5L);
        Mockito.when(root.getState()).thenReturn(Volume.State.Ready);Mockito.when(root.getPoolId()).thenReturn(30L);
        Mockito.when(root.getDeviceId()).thenReturn(0L);Mockito.when(root.getSize()).thenReturn(5L*1024*1024*1024);
        return root;
    }
    @Test public void runningStageDetachesDuplicateWithinItsTransactionAndKeepsCurrentTemplate() {
        Mockito.when(orchestration.allocateDuplicateVolume(previous,null,99L)).thenReturn(staged);
        Assert.assertEquals(staged,swap.allocate(7,10,target,"441a5cbd-1690-4d85-b29f-ad9a843738c5"));
        Mockito.verify(staged).setInstanceId(null);Mockito.verify(staged).setSize(6L*1024*1024*1024);
        Mockito.verify(vm,Mockito.never()).setTemplateId(Mockito.any());
        Mockito.verify(volumes,Mockito.never()).detachVolume(10L);
        Mockito.verifyNoInteractions(service);
    }
    @Test public void allocatedPreparationUsesTargetTemplateAndNeverGenericVmOldTemplate() {
        Mockito.when(staged.getState()).thenReturn(Volume.State.Allocated,Volume.State.Ready);
        VolumeInfo info=Mockito.mock(VolumeInfo.class);Mockito.when(factory.getVolume(20L)).thenReturn(info);
        TemplateInfo template=Mockito.mock(TemplateInfo.class);Mockito.when(templates.getTemplate(99L,DataStoreRole.Image,5L)).thenReturn(template);
        AsyncCallFuture<VolumeApiResult> future=new AsyncCallFuture<>();
        future.complete(new VolumeApiResult(info));
        Mockito.when(service.createVolumeFromTemplateAsync(info,30L,template)).thenReturn(future);
        Assert.assertEquals(staged,swap.prepare(7,10,20,99));
        Mockito.verify(templates,Mockito.never()).getTemplate(41L,DataStoreRole.Image,5L);
        Mockito.verify(info).setDestinationHostId(13L);Mockito.verify(vm,Mockito.never()).setTemplateId(Mockito.any());
    }
    @Test public void swapPreservesPreviousRootAndTheOriginalDeviceNumber() {
        Mockito.when(vm.getState()).thenReturn(VirtualMachine.State.Stopped);
        swap.swap(7,10,20,41,99,88);
        Mockito.verify(volumes).detachVolume(10L);Mockito.verify(volumes).attachVolume(20L,7L,0L);
        Mockito.verify(previous).setRecreatable(false);Mockito.verify(vm).setTemplateId(99L);Mockito.verify(vm).setGuestOSId(88L);
        Mockito.verify(volumes,Mockito.never()).remove(Mockito.anyLong());Mockito.verifyNoInteractions(service,orchestration);
    }
    @Test public void runningSwapAndForeignDataDiskAreRejectedBeforeAnyMutation() {
        Assert.assertThrows(CloudRuntimeException.class,()->swap.swap(7,10,20,41,99,88));
        Mockito.when(vm.getState()).thenReturn(VirtualMachine.State.Stopped);
        Mockito.when(staged.getVolumeType()).thenReturn(Volume.Type.DATADISK);
        Assert.assertThrows(CloudRuntimeException.class,()->swap.swap(7,10,20,41,99,88));
        Mockito.verify(volumes,Mockito.never()).detachVolume(Mockito.anyLong());
        Mockito.verify(volumes,Mockito.never()).attachVolume(Mockito.anyLong(),Mockito.anyLong(),Mockito.anyLong());
    }
    @Test public void changedTemplateOrUnexpectedSecondRootCannotBeSwapped() {
        Mockito.when(vm.getState()).thenReturn(VirtualMachine.State.Stopped);
        Mockito.when(vm.getTemplateId()).thenReturn(42L);
        Assert.assertThrows(CloudRuntimeException.class,()->swap.swap(7,10,20,41,99,88));
        Mockito.when(vm.getTemplateId()).thenReturn(41L);
        Mockito.when(volumes.findByInstanceAndType(7L,Volume.Type.ROOT)).thenReturn(List.of(previous,staged));
        Assert.assertThrows(CloudRuntimeException.class,()->swap.swap(7,10,20,41,99,88));
        Mockito.verify(volumes,Mockito.never()).detachVolume(Mockito.anyLong());
    }
}
