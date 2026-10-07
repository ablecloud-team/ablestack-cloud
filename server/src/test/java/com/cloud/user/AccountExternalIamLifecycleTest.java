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

import java.lang.reflect.Field;
import java.util.Collections;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.user.dao.AccountDao;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

public class AccountExternalIamLifecycleTest {
    private AccountManagerImpl manager;
    private AccountDao accounts;
    private AccountDetailsDao details;
    private AccountVO account;
    private Account caller;

    @Before public void prepare() throws Exception {
        manager = Mockito.spy(new AccountManagerImpl());
        accounts = Mockito.mock(AccountDao.class);
        details = Mockito.mock(AccountDetailsDao.class);
        for (String name : new String[]{"_accountDao", "_accountDetailsDao"}) {
            Field field = AccountManagerImpl.class.getDeclaredField(name); field.setAccessible(true);
            field.set(manager, name.equals("_accountDao") ? accounts : details);
        }
        account = Mockito.mock(AccountVO.class);
        Mockito.when(account.getId()).thenReturn(2000L);
        Mockito.when(account.getType()).thenReturn(Account.Type.NORMAL);
        Mockito.when(account.getAccountName()).thenReturn("local-user");
        caller = Mockito.mock(Account.class);
        Mockito.when(accounts.remove(2000L)).thenReturn(true);
        Mockito.doReturn(true).when(manager).cleanupAccount(account, 2L, caller);
        Mockito.doNothing().when(manager).deleteKeycloakUser(account);
        Mockito.doNothing().when(manager).deleteGlueUser("local-user");
        Mockito.doNothing().when(manager).deleteWallUser("local-user");
    }

    @Test public void serverRecordedLocalOnlyAccountDeletesWithoutExternalIam() throws Exception {
        Mockito.when(details.findDetail(2000L, AccountManagerImpl.EXTERNAL_IAM_ORIGIN)).thenReturn(
                new AccountDetailVO(2000L, AccountManagerImpl.EXTERNAL_IAM_ORIGIN, AccountManagerImpl.LOCAL_ONLY_ORIGIN));
        Mockito.doThrow(new IllegalStateException("IAM unavailable")).when(manager).deleteKeycloakUser(account);
        Assert.assertTrue(manager.deleteAccount(account, 2L, caller));
        Mockito.verify(manager, Mockito.never()).deleteKeycloakUser(account);
        Mockito.verify(manager).cleanupAccount(account, 2L, caller);
    }

    @Test public void legacyAndExternallyProvisionedAccountsRetainExternalCleanupBeforeLocalRemoval() throws Exception {
        for (String origin : new String[]{null, AccountManagerImpl.EXTERNAL_ORIGIN, "unknown"}) {
            Mockito.when(details.findDetail(2000L, AccountManagerImpl.EXTERNAL_IAM_ORIGIN)).thenReturn(origin == null ? null :
                    new AccountDetailVO(2000L, AccountManagerImpl.EXTERNAL_IAM_ORIGIN, origin));
            Assert.assertTrue(manager.deleteAccount(account, 2L, caller));
        }
        InOrder order = Mockito.inOrder(manager, accounts);
        order.verify(manager).deleteKeycloakUser(account);
        order.verify(manager).deleteGlueUser("local-user");
        order.verify(manager).deleteWallUser("local-user");
        order.verify(accounts).remove(2000L);
        order.verify(manager).cleanupAccount(account, 2L, caller);
    }

    @Test public void externalFailureNeverHidesAccountOrStartsLocalCleanup() throws Exception {
        for (int failingStep = 0; failingStep < 3; failingStep++) {
            prepare();
            if (failingStep == 0) { Mockito.doThrow(new IllegalStateException()).when(manager).deleteKeycloakUser(account); }
            if (failingStep == 1) { Mockito.doThrow(new IllegalStateException()).when(manager).deleteGlueUser("local-user"); }
            if (failingStep == 2) { Mockito.doThrow(new IllegalStateException()).when(manager).deleteWallUser("local-user"); }
            Assert.assertFalse(manager.deleteAccount(account, 2L, caller));
            Mockito.verify(accounts, Mockito.never()).remove(Mockito.anyLong());
            Mockito.verify(manager, Mockito.never()).cleanupAccount(Mockito.any(), Mockito.anyLong(), Mockito.any());
        }
    }

    @Test public void serverWritesEnableOriginAsOneDetailWithoutReplacingExistingDetails() {
        manager.recordExternalIamOrigin(2000L, false);
        manager.recordExternalIamOrigin(2001L, true);
        ArgumentCaptor<AccountDetailVO> value = ArgumentCaptor.forClass(AccountDetailVO.class);
        Mockito.verify(details, Mockito.times(2)).persist(value.capture());
        Assert.assertEquals(AccountManagerImpl.LOCAL_ONLY_ORIGIN, value.getAllValues().get(0).getValue());
        Assert.assertEquals(AccountManagerImpl.EXTERNAL_ORIGIN, value.getAllValues().get(1).getValue());
        Mockito.verify(details, Mockito.never()).persist(Mockito.anyLong(), Mockito.anyMap());
    }

    @Test public void callerCannotSpoofOrOverwriteProvisionOrigin() {
        manager.validateCallerAccountDetails(Collections.singletonMap("user.note", "normal detail"));
        for (String key : new String[]{AccountManagerImpl.EXTERNAL_IAM_ORIGIN, "mold.external-iam.future"}) {
            try { manager.validateCallerAccountDetails(Collections.singletonMap(key, "local-only-v1")); Assert.fail("server-owned namespace"); }
            catch (InvalidParameterValueException expected) { }
        }
    }
}
