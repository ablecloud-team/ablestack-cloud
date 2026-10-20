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

import com.cloud.vm.UserVmService;
import org.apache.cloudstack.api.BaseAsyncCmd;
import org.apache.cloudstack.api.command.user.vm.StopVMCmd;
import org.apache.cloudstack.api.command.user.vm.RebootVMCmd;
import org.apache.cloudstack.api.command.user.vm.DestroyVMCmd;
import org.apache.cloudstack.api.command.user.vm.RestoreVMCmd;
import org.apache.cloudstack.api.command.admin.vm.MigrateVMCmd;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class StorageVmWriterQueueTest {
    @Test public void actualGenericVmCommandsJoinTheirSharedFsInstanceWriterQueue() {
        UserVmService service=mock(UserVmService.class);when(service.getStorageServiceSyncIdForVm(7L)).thenReturn(8L);
        BaseAsyncCmd[] commands={new StopVMCmd(),new RebootVMCmd(),new DestroyVMCmd(),new RestoreVMCmd(),new MigrateVMCmd()};
        String[] idFields={"id","id","id","vmId","virtualMachineId"};
        for (int i=0;i<commands.length;i++) {
            ReflectionTestUtils.setField(commands[i],idFields[i],7L);ReflectionTestUtils.setField(commands[i],"_userVmService",service);
            Assert.assertEquals(commands[i].getClass().getSimpleName(),"StorageServiceInstance",commands[i].getSyncObjType());Assert.assertEquals(Long.valueOf(8L),commands[i].getSyncObjId());
        }
    }
    @Test public void ordinaryVmKeepsItsPreviousNullOrMigrationHostQueue() {
        UserVmService service=mock(UserVmService.class);when(service.getStorageServiceSyncIdForVm(7L)).thenReturn(null);
        StopVMCmd stop=new StopVMCmd();ReflectionTestUtils.setField(stop,"id",7L);ReflectionTestUtils.setField(stop,"_userVmService",service);Assert.assertNull(stop.getSyncObjType());Assert.assertNull(stop.getSyncObjId());
        MigrateVMCmd migrate=new MigrateVMCmd();ReflectionTestUtils.setField(migrate,"virtualMachineId",7L);ReflectionTestUtils.setField(migrate,"hostId",99L);ReflectionTestUtils.setField(migrate,"_userVmService",service);Assert.assertEquals(BaseAsyncCmd.migrationSyncObject,migrate.getSyncObjType());Assert.assertEquals(Long.valueOf(99L),migrate.getSyncObjId());
    }
}
