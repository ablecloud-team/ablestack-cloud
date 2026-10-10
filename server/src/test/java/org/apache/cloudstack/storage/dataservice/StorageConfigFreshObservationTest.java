// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.cloudstack.storage.dataservice;
import org.junit.Test;
import org.junit.Assert;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;

public class StorageConfigFreshObservationTest {
    @Test public void backupCollectorsUseFixedCacheFreeInventoryAndHealthProbes() {
        StorageServiceManagerImpl manager=new StorageServiceManagerImpl();StorageServiceGuestCommandDispatcher guest=Mockito.mock(StorageServiceGuestCommandDispatcher.class);
        VMInstanceDao vms=Mockito.mock(VMInstanceDao.class);ReflectionTestUtils.setField(manager,"guestCommandDispatcher",guest);ReflectionTestUtils.setField(manager,"vmInstanceDao",vms);
        StorageServiceInstanceVO instance=Mockito.mock(StorageServiceInstanceVO.class);Mockito.when(instance.getVmId()).thenReturn(48L);
        VMInstanceVO vm=Mockito.mock(VMInstanceVO.class);Mockito.when(vm.getState()).thenReturn(VirtualMachine.State.Running);Mockito.when(vms.findById(48L)).thenReturn(vm);
        Mockito.when(guest.dispatch(Mockito.any())).thenReturn(new StorageServiceGuestCommandResult(true,"ok","{\"success\":true}"));
        manager.observeConfigurationRuntime(instance,"inventory");manager.observeConfigurationRuntime(instance,"health");
        org.mockito.ArgumentCaptor<StorageServiceGuestCommand> commands=org.mockito.ArgumentCaptor.forClass(StorageServiceGuestCommand.class);Mockito.verify(guest,Mockito.times(2)).dispatch(commands.capture());
        Assert.assertEquals("operation observe",commands.getAllValues().get(0).getOperation());Assert.assertEquals("operation verify",commands.getAllValues().get(1).getOperation());
    }
}
