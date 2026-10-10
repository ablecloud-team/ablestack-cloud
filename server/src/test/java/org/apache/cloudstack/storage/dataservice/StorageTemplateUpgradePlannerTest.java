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
import org.junit.Test;
import org.junit.Assert;
import org.mockito.Mockito;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.VMTemplateVO;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.UserVmManager;
import com.cloud.vm.VirtualMachine;
import com.cloud.exception.InvalidParameterValueException;
import com.google.gson.JsonObject;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceTemplateUpgradeDao;

public class StorageTemplateUpgradePlannerTest {
    @Test public void compatiblePlanRecordsTheExistingRootWithoutAllocatingAnotherVolume() {
        StorageServiceTemplateUpgradeDao dao=Mockito.mock(StorageServiceTemplateUpgradeDao.class);
        Mockito.when(dao.persist(Mockito.any())).thenAnswer(call->call.getArgument(0));
        StorageServiceInstanceVO instance=Mockito.mock(StorageServiceInstanceVO.class);Mockito.when(instance.getVmId()).thenReturn(7L);Mockito.when(instance.getId()).thenReturn(6L);
        UserVmVO vm=Mockito.mock(UserVmVO.class);Mockito.when(vm.getId()).thenReturn(7L);Mockito.when(vm.getUserVmType()).thenReturn(UserVmManager.SHAREDFSVM);
        Mockito.when(vm.getState()).thenReturn(VirtualMachine.State.Running);Mockito.when(vm.getTemplateId()).thenReturn(41L);
        VolumeVO root=Mockito.mock(VolumeVO.class);Mockito.when(root.getId()).thenReturn(10L);Mockito.when(root.getInstanceId()).thenReturn(7L);
        Mockito.when(root.getVolumeType()).thenReturn(Volume.Type.ROOT);Mockito.when(root.getState()).thenReturn(Volume.State.Ready);
        Mockito.when(root.getDeviceId()).thenReturn(0L);Mockito.when(root.getPoolId()).thenReturn(30L);
        VMTemplateVO target=Mockito.mock(VMTemplateVO.class);Mockito.when(target.getId()).thenReturn(99L);
        JsonObject compatibility=new JsonObject();compatibility.addProperty("compatible",true);
        StorageServiceTemplateUpgradeVO row=new StorageTemplateUpgradePlanner(dao).plan(instance,12,vm,List.of(root),target,compatibility,9,"planned",1);
        Assert.assertEquals("PLANNED",row.getState());Assert.assertEquals(10,row.getPreviousRootVolumeId());Assert.assertNull(row.getTargetRootVolumeId());
        Assert.assertEquals("Running",row.getPreviousVmState());Assert.assertEquals(41,row.getSourceTemplateId());
        Mockito.verify(vm,Mockito.never()).setTemplateId(Mockito.any());Mockito.verify(root,Mockito.never()).setInstanceId(Mockito.any());
    }
    @Test public void changedRevisionAndMissingExplicitMaintenanceApprovalAreBlocked() {
        StorageServiceTemplateUpgradeVO row=new StorageServiceTemplateUpgradeVO();row.setRevision(9);
        Assert.assertThrows(InvalidParameterValueException.class,()->StorageTemplateUpgradePlanner.approve(row,10,"service","service",true));
        Assert.assertThrows(InvalidParameterValueException.class,()->StorageTemplateUpgradePlanner.approve(row,9,"service","service",false));
        Assert.assertThrows(InvalidParameterValueException.class,()->StorageTemplateUpgradePlanner.approve(row,9,"service","another",true));
        StorageTemplateUpgradePlanner.approve(row,9,"service","service",true);
    }
    @Test public void aCompletedTransactionCannotBeReplayedAsAnotherCutover() {
        StorageServiceTemplateUpgradeVO row=new StorageServiceTemplateUpgradeVO();row.setState("COMPLETE");row.setRevision(9);
        Assert.assertThrows(InvalidParameterValueException.class,()->StorageTemplateUpgradePlanner.approve(row,9,"service","service",true));
    }
}
