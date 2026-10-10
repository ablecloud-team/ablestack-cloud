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
import java.util.Objects;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.VMTemplateVO;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.UserVmManager;
import com.cloud.vm.VirtualMachine;
import com.cloud.exception.InvalidParameterValueException;
import com.google.gson.JsonObject;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceTemplateUpgradeDao;

/** A plan freezes source bindings and declared compatibility without allocating or touching a ROOT. */
public final class StorageTemplateUpgradePlanner {
    private final StorageServiceTemplateUpgradeDao upgrades;
    public StorageTemplateUpgradePlanner(StorageServiceTemplateUpgradeDao upgrades) { this.upgrades=upgrades; }
    public StorageServiceTemplateUpgradeVO plan(StorageServiceInstanceVO instance,long sharedFsId,UserVmVO vm,
            List<VolumeVO> roots,VMTemplateVO target,JsonObject compatibility,long revision,String request,long userId) {
        if (vm==null || instance.getVmId()==null || vm.getId()!=instance.getVmId()
                || !UserVmManager.SHAREDFSVM.equals(vm.getUserVmType())) throw new InvalidParameterValueException("SharedFS VM scope is unavailable");
        if (vm.getState()!=VirtualMachine.State.Running && vm.getState()!=VirtualMachine.State.Stopped) throw new InvalidParameterValueException("SharedFS VM is transitional");
        if (request==null || !request.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")) throw new InvalidParameterValueException("Template upgrade idempotency key is invalid");
        StorageServiceTemplateUpgradeVO previous=upgrades.findByRequest(instance.getId(),request);
        if (previous!=null) {
            if (target==null || previous.getTargetTemplateId()!=target.getId() || previous.getSharedFilesystemId()!=sharedFsId) {
                throw new InvalidParameterValueException("Template upgrade request was reused with another target");
            }
            return previous;
        }
        if (roots.size()!=1) throw new InvalidParameterValueException("Exactly one current ROOT is required");
        VolumeVO root=roots.get(0);
        if (root.getVolumeType()!=Volume.Type.ROOT || root.getState()!=Volume.State.Ready || root.getDeviceId()==null
                || !Objects.equals(root.getInstanceId(),vm.getId()) || root.getAccountId()!=vm.getAccountId()
                || root.getDataCenterId()!=vm.getDataCenterId() || root.getPoolId()==null) {
            throw new InvalidParameterValueException("Current ROOT identity or readiness is not verified");
        }
        if (target==null || compatibility==null || !compatibility.has("compatible") || !compatibility.get("compatible").getAsBoolean()) {
            throw new InvalidParameterValueException("Selected template is incompatible");
        }
        StorageServiceTemplateUpgradeVO row=new StorageServiceTemplateUpgradeVO();
        row.setInstanceId(instance.getId());row.setSharedFilesystemId(sharedFsId);
        row.setSourceTemplateId(vm.getTemplateId());row.setTargetTemplateId(target.getId());row.setPreviousRootVolumeId(root.getId());
        row.setPreviousGuestOsId(vm.getGuestOSId());row.setRootDeviceId(root.getDeviceId());row.setPreviousVmState(vm.getState().name());
        row.setRevision(revision);row.setRequestKey(request);row.setCreatedBy(userId);row.setPreflightJson(compatibility.toString());
        return upgrades.persist(row);
    }
    public static void approve(StorageServiceTemplateUpgradeVO row,long currentRevision,String serviceName,String confirmation,Boolean maintenanceWindow) {
        if (!java.util.Set.of("PLANNED","RUNNING","RECOVERY_REQUIRED").contains(row.getState())) throw new InvalidParameterValueException("Template upgrade cannot execute in its current state");
        if (row.getRevision()!=currentRevision) throw new InvalidParameterValueException("Desired configuration changed; create a new upgrade plan");
        if (!Boolean.TRUE.equals(maintenanceWindow) || !Objects.equals(serviceName,confirmation)) {
            throw new InvalidParameterValueException("Template ROOT maintenance requires explicit interruption approval and exact service name");
        }
    }
}
