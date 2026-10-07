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
package com.cloud.network.security;

import java.util.Collections;
import java.util.Date;

import com.cloud.user.Account;
import com.cloud.user.AccountVO;
import com.cloud.user.UserVO;
import com.cloud.user.dao.AccountDao;
import com.cloud.user.dao.UserDao;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class RemovedAccountDefaultSecurityGroupTest {
    private final SecurityGroupManagerImpl manager = new SecurityGroupManagerImpl();
    private final Account caller = mock(Account.class);
    private final AccountVO owner = mock(AccountVO.class);
    private final SecurityGroupVO group = mock(SecurityGroupVO.class);

    @Before
    public void setup() {
        manager._accountDao = mock(AccountDao.class);
        manager._userDao = mock(UserDao.class);
        when(caller.getType()).thenReturn(Account.Type.ADMIN);
        when(group.getAccountId()).thenReturn(26L);
        when(manager._accountDao.findByIdIncludingRemoved(26L)).thenReturn(owner);
        when(owner.getId()).thenReturn(26L);
        when(owner.getRemoved()).thenReturn(new Date());
        when(owner.getType()).thenReturn(Account.Type.NORMAL);
        when(manager._userDao.listByAccount(26L)).thenReturn(Collections.emptyList());
    }

    @Test
    public void rootAdminCanCleanRemovedEmptyNormalAccount() {
        assertTrue(manager.canDeleteRemovedAccountDefaultGroup(caller, group));
    }

    @Test
    public void activeAccountRemainsReserved() {
        when(owner.getRemoved()).thenReturn(null);
        assertFalse(manager.canDeleteRemovedAccountDefaultGroup(caller, group));
    }

    @Test
    public void defaultAccountRemainsReserved() {
        when(owner.isDefault()).thenReturn(true);
        assertFalse(manager.canDeleteRemovedAccountDefaultGroup(caller, group));
    }

    @Test
    public void missingOwnerFailsClosed() {
        when(manager._accountDao.findByIdIncludingRemoved(26L)).thenReturn(null);
        assertFalse(manager.canDeleteRemovedAccountDefaultGroup(caller, group));
    }

    @Test
    public void projectAndMachineAccountsAreExcluded() {
        when(owner.getType()).thenReturn(Account.Type.PROJECT);
        assertFalse(manager.canDeleteRemovedAccountDefaultGroup(caller, group));
    }

    @Test
    public void activeOrphanUserBlocksDefaultGroupCleanup() {
        when(manager._userDao.listByAccount(26L)).thenReturn(Collections.singletonList(mock(UserVO.class)));
        assertFalse(manager.canDeleteRemovedAccountDefaultGroup(caller, group));
    }

    @Test
    public void domainAdminAndNormalCallerCannotUseRecovery() {
        when(caller.getType()).thenReturn(Account.Type.DOMAIN_ADMIN);
        assertFalse(manager.canDeleteRemovedAccountDefaultGroup(caller, group));
        when(caller.getType()).thenReturn(Account.Type.NORMAL);
        assertFalse(manager.canDeleteRemovedAccountDefaultGroup(caller, group));
    }
}
