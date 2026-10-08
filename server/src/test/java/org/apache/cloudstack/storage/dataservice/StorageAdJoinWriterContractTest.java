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

import java.util.function.Supplier;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.BaseStorageServiceAsyncCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.JoinStorageServiceToAdDomainCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.LeaveStorageServiceFromAdDomainCmd;
import org.apache.cloudstack.api.response.StorageIdentityDomainResponse;
import org.apache.cloudstack.context.CallContext;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

public class StorageAdJoinWriterContractTest {
    private static class Manager extends StorageServiceManagerImpl {
        BaseCmd queued;
        @Override protected <T> T executeDesiredChange(BaseCmd command, Class<T> response, Supplier<T> change) {
            queued=command;
            return response.cast(new StorageIdentityDomainResponse());
        }
    }
    @Test public void joinUsesTheSamePersistentWriterQueueAndPermissionOwnerAsOtherStorageChanges() {
        JoinStorageServiceToAdDomainCmd command=new JoinStorageServiceToAdDomainCmd();
        Assert.assertTrue(command instanceof BaseStorageServiceAsyncCmd);
        StorageService service=Mockito.mock(StorageService.class);Mockito.when(service.getStorageServiceSyncId(command)).thenReturn(17L);
        ReflectionTestUtils.setField(command,"storageServiceScope",service);
        Assert.assertEquals("StorageServiceInstance",command.getSyncObjType());Assert.assertEquals(Long.valueOf(17),command.getSyncObjId());
        com.cloud.user.AccountVO owner=Mockito.mock(com.cloud.user.AccountVO.class);com.cloud.user.UserVO user=Mockito.mock(com.cloud.user.UserVO.class);
        Mockito.when(owner.getId()).thenReturn(2L);Mockito.when(user.getId()).thenReturn(3L);
        CallContext.register(user,owner);
        try {Assert.assertEquals(2L,command.getEntityOwnerId());}finally{CallContext.unregister();}
    }
    @Test public void managerCannotMutateJoinStateOrGuestBeforeEnteringTheDesiredWriterBoundary() {
        Manager manager=new Manager();JoinStorageServiceToAdDomainCmd command=new JoinStorageServiceToAdDomainCmd();
        Assert.assertNotNull(manager.joinStorageServiceToAdDomain(command));Assert.assertSame(command,manager.queued);
        Assert.assertEquals(StorageServiceInstance.Protocol.SMB,ReflectionTestUtils.invokeMethod(manager,"operationProtocol",command));
    }
    @Test public void leaveUsesTheSameWriterBoundaryAndSmbIdentityCheckpointScope() {
        Manager manager=new Manager();LeaveStorageServiceFromAdDomainCmd command=new LeaveStorageServiceFromAdDomainCmd();
        Assert.assertTrue(command instanceof BaseStorageServiceAsyncCmd);Assert.assertNotNull(manager.leaveStorageServiceFromAdDomain(command));
        Assert.assertSame(command,manager.queued);Assert.assertEquals(StorageServiceInstance.Protocol.SMB,ReflectionTestUtils.invokeMethod(manager,"operationProtocol",command));
    }

}
