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

import java.util.List;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.utils.Ternary;
import org.apache.cloudstack.acl.APIAclChecker;
import org.apache.cloudstack.acl.APIChecker;
import org.apache.cloudstack.acl.ApiKeyPairManagerImpl;
import org.apache.cloudstack.acl.apikeypair.ApiKeyPair;
import org.apache.cloudstack.acl.apikeypair.ApiKeyPairPermission;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

public class AccountManagerSchedulePermissionTest {
    @Test
    public void checksUserAndKeyPermissionsWithoutChargingRateLimitAgain() {
        AccountManagerImpl manager = Mockito.spy(new AccountManagerImpl());
        User user = Mockito.mock(User.class);
        Account account = Mockito.mock(Account.class);
        Mockito.when(user.getId()).thenReturn(7L);
        Mockito.when(user.getAccountId()).thenReturn(9L);
        Mockito.when(account.getRoleId()).thenReturn(23L);
        Mockito.doReturn(account).when(manager).getAccount(9L);
        APIAclChecker acl = Mockito.mock(APIAclChecker.class);
        APIChecker rate = Mockito.mock(APIChecker.class);
        Mockito.when(acl.isEnabled()).thenReturn(true);
        Mockito.when(rate.isEnabled()).thenReturn(true);
        ReflectionTestUtils.setField(manager, "apiAccessCheckers", List.of(acl, rate));
        ApiKeyPair key = Mockito.mock(ApiKeyPair.class);
        Mockito.when(key.getId()).thenReturn(11L);
        Mockito.doReturn(new Ternary<>(user, account, key)).when(manager).findUserByApiKey("fixture-key");
        ApiKeyPairManagerImpl keyManager = Mockito.mock(ApiKeyPairManagerImpl.class);
        ApiKeyPairPermission permission = Mockito.mock(ApiKeyPairPermission.class);
        Mockito.when(keyManager.findAllPermissionsByKeyPairId(11L, 23L)).thenReturn(List.of(permission));
        ReflectionTestUtils.setField(manager, "keyPairManager", keyManager);
        Mockito.doThrow(new PermissionDeniedException("project or key denies VM schedule")).when(acl)
                .checkAccess(user, "createVMSchedule", key, permission);
        try {
            manager.checkApiAccessForUser(user, "createVMSchedule", "fixture-key");
            Assert.fail("User/project/key permission denial must propagate");
        } catch (PermissionDeniedException expected) {
            Mockito.verify(acl).checkAccess(user, "createVMSchedule", key, permission);
            Mockito.verify(rate, Mockito.never()).checkAccess(Mockito.any(User.class), Mockito.anyString(), Mockito.any(), Mockito.<ApiKeyPairPermission[]>any());
            Mockito.verify(rate, Mockito.never()).checkAccess(Mockito.any(Account.class), Mockito.anyString(), Mockito.any(), Mockito.<ApiKeyPairPermission[]>any());
        }
    }

    @Test(expected = PermissionDeniedException.class)
    public void unknownKeyFailsClosedBeforeAcl() {
        AccountManagerImpl manager = Mockito.spy(new AccountManagerImpl());
        User user = Mockito.mock(User.class);
        Mockito.doReturn(null).when(manager).findUserByApiKey("unknown-fixture-key");
        Mockito.doReturn(Mockito.mock(Account.class)).when(manager).getAccount(0L);
        manager.checkApiAccessForUser(user, "createVMSchedule", "unknown-fixture-key");
    }
}
