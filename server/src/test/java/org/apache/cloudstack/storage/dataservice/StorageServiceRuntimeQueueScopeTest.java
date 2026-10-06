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

import com.cloud.user.Account;
import com.cloud.user.User;
import com.cloud.user.AccountManager;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.api.command.admin.storage.dataservice.*;
import org.apache.cloudstack.api.command.user.storage.dataservice.BaseStorageServiceAsyncCmd;
import org.apache.cloudstack.storage.dataservice.dao.*;
import org.apache.cloudstack.storage.sharedfs.SharedFSVO;
import org.apache.cloudstack.storage.sharedfs.dao.SharedFSDao;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.Assert;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.*;

public class StorageServiceRuntimeQueueScopeTest {
    private StorageServiceManagerImpl manager;
    private StorageServiceRuntimeUpgradeDao upgrades;
    @Before public void prepare() {
        User user=mock(User.class); Account account=mock(Account.class);
        when(user.getId()).thenReturn(1L); when(account.getId()).thenReturn(2L);
        CallContext.register(user,account);
        manager=new StorageServiceManagerImpl();
        StorageServiceInstanceDao instances=mock(StorageServiceInstanceDao.class);
        SharedFSDao shared=mock(SharedFSDao.class);
        upgrades=mock(StorageServiceRuntimeUpgradeDao.class);
        ReflectionTestUtils.setField(manager,"storageServiceInstanceDao",instances);
        ReflectionTestUtils.setField(manager,"sharedFSDao",shared);
        ReflectionTestUtils.setField(manager,"storageRuntimeUpgradeDao",upgrades);
        ReflectionTestUtils.setField(manager,"storageAccountManager",mock(AccountManager.class));
        StorageServiceInstanceVO instance=mock(StorageServiceInstanceVO.class);
        when(instance.getId()).thenReturn(7L);
        when(instances.findById(7L)).thenReturn(instance);
        when(instances.findByVmId(41L)).thenReturn(instance);
        SharedFSVO fs=mock(SharedFSVO.class);when(fs.getVmId()).thenReturn(41L);
        when(shared.findById(3L)).thenReturn(fs);
        StorageServiceRuntimeUpgradeVO upgrade=mock(StorageServiceRuntimeUpgradeVO.class);
        when(upgrade.getInstanceId()).thenReturn(7L);when(upgrades.findById(11L)).thenReturn(upgrade);
    }
    @After public void clear(){CallContext.unregister();}
    private void assertScope(BaseStorageServiceAsyncCmd cmd) {
        ReflectionTestUtils.setField(cmd,"storageServiceScope",manager);
        Assert.assertEquals("StorageServiceInstance",cmd.getSyncObjType());
        Assert.assertEquals(Long.valueOf(7),cmd.getSyncObjId());
    }
    @Test public void preflightActivateRollbackShareTheSamePersistentWriterQueue() {
        PreflightStorageServiceRuntimeUpgradeCmd preflight=new PreflightStorageServiceRuntimeUpgradeCmd();
        ReflectionTestUtils.setField(preflight,"sharedFileSystemId",3L);assertScope(preflight);
        UpgradeStorageServiceRuntimeCmd activate=new UpgradeStorageServiceRuntimeCmd();
        ReflectionTestUtils.setField(activate,"upgradeId",11L);assertScope(activate);
        RollbackStorageServiceRuntimeUpgradeCmd rollback=new RollbackStorageServiceRuntimeUpgradeCmd();
        ReflectionTestUtils.setField(rollback,"upgradeId",11L);assertScope(rollback);
    }
    @Test public void unavailableRuntimeTransactionIsRejectedBeforeQueueDispatch() {
        UpgradeStorageServiceRuntimeCmd cmd=new UpgradeStorageServiceRuntimeCmd();
        ReflectionTestUtils.setField(cmd,"upgradeId",99L);
        ReflectionTestUtils.setField(cmd,"storageServiceScope",manager);
        Assert.assertThrows(com.cloud.exception.InvalidParameterValueException.class,cmd::getSyncObjId);
    }
}
