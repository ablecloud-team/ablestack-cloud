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
import org.junit.*;
import org.mockito.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.api.command.admin.storage.dataservice.*;
import com.cloud.user.*;
import org.apache.cloudstack.storage.sharedfs.*;
import org.apache.cloudstack.storage.sharedfs.dao.SharedFSDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceInstanceDao;
public class StorageTemplateUpgradeCommandContractTest {
    @Test public void allRootAsyncCommandsUseTheValidCallingAccountOwner(){
        try (MockedStatic<CallContext> scoped=Mockito.mockStatic(CallContext.class)) {
            CallContext context=Mockito.mock(CallContext.class);Account account=Mockito.mock(Account.class);scoped.when(CallContext::current).thenReturn(context);Mockito.when(context.getCallingAccount()).thenReturn(account);Mockito.when(account.getId()).thenReturn(42L);
            for (BaseStorageTemplateUpgradeAsyncCmd command:new BaseStorageTemplateUpgradeAsyncCmd[]{new UpgradeStorageServiceSystemVmTemplateCmd(),new RollbackStorageServiceSystemVmTemplateUpgradeCmd(),new FinalizeStorageServiceSystemVmTemplateUpgradeCmd()}) Assert.assertEquals(42,command.getEntityOwnerId());
        }
    }
    @Test public void rootMaintenanceAndOrdinaryLifecycleResolveTheSameInstanceQueue(){
        try (MockedStatic<CallContext> scoped=Mockito.mockStatic(CallContext.class)) {
            CallContext context=Mockito.mock(CallContext.class);Account account=Mockito.mock(Account.class);scoped.when(CallContext::current).thenReturn(context);Mockito.when(context.getCallingAccount()).thenReturn(account);
            StorageServiceManagerImpl manager=new StorageServiceManagerImpl();SharedFSDao shared=Mockito.mock(SharedFSDao.class);StorageServiceInstanceDao instances=Mockito.mock(StorageServiceInstanceDao.class);SharedFSVO fs=Mockito.mock(SharedFSVO.class);StorageServiceInstanceVO instance=Mockito.mock(StorageServiceInstanceVO.class);
            Mockito.when(shared.findById(12L)).thenReturn(fs);Mockito.when(fs.getVmId()).thenReturn(7L);Mockito.when(instances.findByVmId(7L)).thenReturn(instance);Mockito.when(instance.getId()).thenReturn(6L);Mockito.when(instances.findById(6L)).thenReturn(instance);
            ReflectionTestUtils.setField(manager,"sharedFSDao",shared);ReflectionTestUtils.setField(manager,"storageServiceInstanceDao",instances);ReflectionTestUtils.setField(manager,"storageAccountManager",Mockito.mock(AccountManager.class));
            UpgradeStorageServiceSystemVmTemplateCmd root=new UpgradeStorageServiceSystemVmTemplateCmd();ReflectionTestUtils.setField(root,"sharedFileSystemId",12L);
            org.apache.cloudstack.api.command.user.storage.sharedfs.StopSharedFSCmd stop=new org.apache.cloudstack.api.command.user.storage.sharedfs.StopSharedFSCmd();ReflectionTestUtils.setField(stop,"id",12L);
            Assert.assertEquals(Long.valueOf(6),manager.getStorageServiceSyncId(root));Assert.assertEquals(Long.valueOf(6),manager.getStorageServiceSyncId(stop));Assert.assertEquals("StorageServiceInstance",root.getSyncObjType());Assert.assertEquals("StorageServiceInstance",stop.getSyncObjType());
        }
    }
}
