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
package com.cloud.user;

import com.cloud.exception.PermissionDeniedException;
import com.cloud.user.dao.AccountDao;
import java.lang.reflect.Field;
import org.junit.Test;
import org.mockito.Mockito;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class LocalMachineAccountCleanupTest {
    private AccountManagerImpl manager(AccountVO account, Account caller) throws Exception {
        AccountManagerImpl manager = Mockito.spy(new AccountManagerImpl());
        AccountDao dao = Mockito.mock(AccountDao.class);
        Field field = AccountManagerImpl.class.getDeclaredField("_accountDao");
        field.setAccessible(true); field.set(manager, dao);
        Mockito.when(dao.remove(account.getId())).thenReturn(true);
        Mockito.doReturn(true).when(manager).cleanupAccount(account, User.UID_SYSTEM, caller);
        // External IAM is unavailable; local deletion must still perform normal internal cleanup.
        Mockito.doThrow(new IllegalStateException("External IAM unavailable")).when(manager).deleteKeycloakUser(account);
        return manager;
    }
    @Test public void verifiedLocalIdentityUsesStandardCleanupWithoutExternalIam() throws Exception {
        AccountVO account = Mockito.mock(AccountVO.class);
        Mockito.when(account.getId()).thenReturn(23L);
        Mockito.when(account.getType()).thenReturn(Account.Type.NORMAL);
        Account caller = Mockito.mock(Account.class);
        Mockito.when(caller.getId()).thenReturn(Account.ACCOUNT_ID_SYSTEM);
        AccountManagerImpl manager = manager(account, caller);
        assertTrue(manager.deleteLocalMachineAccount(account, User.UID_SYSTEM, caller));
        Mockito.verify(manager).cleanupAccount(account, User.UID_SYSTEM, caller);
        Mockito.verify(manager, Mockito.never()).deleteKeycloakUser(Mockito.any());
        Mockito.verify(manager, Mockito.never()).deleteGlueUser(Mockito.anyString());
        Mockito.verify(manager, Mockito.never()).deleteWallUser(Mockito.anyString());
    }
    @Test public void ordinaryAccountDeletionKeepsExternalIamContract() throws Exception {
        AccountVO account = Mockito.mock(AccountVO.class);
        Mockito.when(account.getId()).thenReturn(23L);
        Account caller = Mockito.mock(Account.class);
        AccountManagerImpl manager = manager(account, caller);
        org.junit.Assert.assertFalse(manager.deleteAccount(account, User.UID_SYSTEM, caller));
        Mockito.verify(manager).deleteKeycloakUser(account);
        Mockito.verify(manager, Mockito.never()).cleanupAccount(Mockito.any(), Mockito.anyLong(), Mockito.any());
    }
    @Test public void internalProjectAccountDeletionDoesNotRequireAnExternalIamUser() throws Exception {
        AccountVO account = Mockito.mock(AccountVO.class);
        Mockito.when(account.getId()).thenReturn(22L);
        Mockito.when(account.getType()).thenReturn(Account.Type.PROJECT);
        Account caller = Mockito.mock(Account.class);
        AccountManagerImpl manager = manager(account, caller);
        assertTrue(manager.deleteAccount(account, User.UID_SYSTEM, caller));
        Mockito.verify(manager).cleanupAccount(account, User.UID_SYSTEM, caller);
        Mockito.verify(manager, Mockito.never()).deleteKeycloakUser(Mockito.any());
        Mockito.verify(manager, Mockito.never()).deleteGlueUser(Mockito.anyString());
        Mockito.verify(manager, Mockito.never()).deleteWallUser(Mockito.anyString());
    }
    @Test public void nonSystemCallerCannotSelectLocalCleanup() {
        AccountManagerImpl manager = new AccountManagerImpl();
        Account caller = Mockito.mock(Account.class);
        Mockito.when(caller.getId()).thenReturn(2L);
        try { manager.deleteLocalMachineAccount(Mockito.mock(AccountVO.class), User.UID_SYSTEM, caller); fail("SYSTEM required"); }
        catch (PermissionDeniedException expected) { }
    }
}
