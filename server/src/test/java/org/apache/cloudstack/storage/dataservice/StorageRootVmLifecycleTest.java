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
import org.junit.Test;
import org.junit.Assert;
import org.mockito.Mockito;
import com.cloud.vm.VirtualMachineManager;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.UserVmManager;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.utils.exception.CloudRuntimeException;

public class StorageRootVmLifecycleTest {
    private UserVmVO vm(VirtualMachine.State state,String type) {
        UserVmVO vm=Mockito.mock(UserVmVO.class);Mockito.when(vm.getState()).thenReturn(state);
        Mockito.when(vm.getUuid()).thenReturn("same-vm");Mockito.when(vm.getUserVmType()).thenReturn(type);return vm;
    }
    @Test public void stopRequiresTheSameSharedFsVmToReachStoppedBeforeRootSwap() throws Exception {
        UserVmDao vms=Mockito.mock(UserVmDao.class);VirtualMachineManager manager=Mockito.mock(VirtualMachineManager.class);
        UserVmVO running=vm(VirtualMachine.State.Running,UserVmManager.SHAREDFSVM),stopped=vm(VirtualMachine.State.Stopped,UserVmManager.SHAREDFSVM);
        Mockito.when(vms.findById(7L)).thenReturn(running,stopped);
        new StorageRootVmLifecycle(manager,vms,Mockito.mock(StorageServiceGuestCommandDispatcher.class)).stop(7);
        Mockito.verify(manager).stop("same-vm");
    }
    @Test public void failedStopCannotAuthorizeADataOrRootBindingChange() throws Exception {
        UserVmDao vms=Mockito.mock(UserVmDao.class);VirtualMachineManager manager=Mockito.mock(VirtualMachineManager.class);
        UserVmVO running=vm(VirtualMachine.State.Running,UserVmManager.SHAREDFSVM);Mockito.when(vms.findById(7L)).thenReturn(running);
        Assert.assertThrows(CloudRuntimeException.class,()->new StorageRootVmLifecycle(manager,vms,Mockito.mock(StorageServiceGuestCommandDispatcher.class)).stop(7));
        Mockito.verify(manager).stop("same-vm");
    }
    @Test public void ordinaryUserVmCannotEnterThisMaintenanceLifecycle() {
        UserVmDao vms=Mockito.mock(UserVmDao.class);VirtualMachineManager manager=Mockito.mock(VirtualMachineManager.class);
        UserVmVO ordinary=vm(VirtualMachine.State.Running,"USER");Mockito.when(vms.findById(7L)).thenReturn(ordinary);
        Assert.assertThrows(CloudRuntimeException.class,()->new StorageRootVmLifecycle(manager,vms,Mockito.mock(StorageServiceGuestCommandDispatcher.class)).stop(7));
        Mockito.verifyNoInteractions(manager);
    }
    @Test public void newRootBootUsesTheExistingVmAndRequiresProtectedIdentityTransport() {
        UserVmDao vms=Mockito.mock(UserVmDao.class);VirtualMachineManager manager=Mockito.mock(VirtualMachineManager.class);
        UserVmVO stopped=vm(VirtualMachine.State.Stopped,UserVmManager.SHAREDFSVM),running=vm(VirtualMachine.State.Running,UserVmManager.SHAREDFSVM);
        Mockito.when(vms.findById(7L)).thenReturn(stopped,running);
        StorageServiceGuestCommandDispatcher guest=Mockito.mock(StorageServiceGuestCommandDispatcher.class);
        Mockito.when(guest.dispatch(Mockito.any())).thenReturn(new StorageServiceGuestCommandResult(true,"ready","{\"success\":true,\"localIdentity\":true,\"protectedStdinTransport\":true}"));
        StorageServiceInstanceVO instance=Mockito.mock(StorageServiceInstanceVO.class);Mockito.when(instance.getVmId()).thenReturn(7L);Mockito.when(instance.getUuid()).thenReturn("instance");
        new StorageRootVmLifecycle(manager,vms,guest).startAndAwaitCapabilities(instance,"operation");
        Mockito.verify(manager).start(Mockito.eq("same-vm"),Mockito.anyMap());
        org.mockito.ArgumentCaptor<StorageServiceGuestCommand> command=org.mockito.ArgumentCaptor.forClass(StorageServiceGuestCommand.class);
        Mockito.verify(guest).dispatch(command.capture());Assert.assertEquals("identity capsule capabilities",command.getValue().getOperation());
    }
}
