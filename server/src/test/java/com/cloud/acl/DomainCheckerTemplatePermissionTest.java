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
package com.cloud.acl;

import org.apache.cloudstack.acl.SecurityChecker.AccessType;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cloud.exception.PermissionDeniedException;
import com.cloud.projects.ProjectManager;
import com.cloud.storage.LaunchPermissionVO;
import com.cloud.storage.dao.LaunchPermissionDao;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.user.Account;
import com.cloud.user.AccountService;
import com.cloud.user.AccountVO;
import com.cloud.user.dao.AccountDao;

public class DomainCheckerTemplatePermissionTest {
    private DomainChecker checker;
    private Account caller;
    private AccountVO owner;
    private VirtualMachineTemplate template;

    @Before
    public void setup() {
        checker = new DomainChecker();
        checker._accountService = mock(AccountService.class);
        checker._accountDao = mock(AccountDao.class);
        checker._projectMgr = mock(ProjectManager.class);
        checker._launchPermissionDao = mock(LaunchPermissionDao.class);
        caller = mock(Account.class);
        owner = mock(AccountVO.class);
        template = mock(VirtualMachineTemplate.class);
        when(caller.getId()).thenReturn(1L);
        when(caller.getType()).thenReturn(Account.Type.NORMAL);
        when(owner.getId()).thenReturn(2L);
        when(owner.getType()).thenReturn(Account.Type.NORMAL);
        when(template.getId()).thenReturn(3L);
        when(template.getAccountId()).thenReturn(2L);
        when(checker._accountDao.findById(2L)).thenReturn(owner);
        when(checker._launchPermissionDao.findByTemplateAndAccount(3L, 1L)).thenReturn(mock(LaunchPermissionVO.class));
    }

    @Test
    public void sharedImageRemainsUsable() {
        assertTrue(checker.checkAccess(caller, template, AccessType.UseEntry));
    }

    @Test(expected = PermissionDeniedException.class)
    public void launchPermissionDoesNotGrantModification() {
        checker.checkAccess(caller, template, AccessType.OperateEntry);
    }

    @Test
    public void rootAdminCanModifyPrivateImage() {
        when(checker._accountService.isRootAdmin(1L)).thenReturn(true);
        assertTrue(checker.checkAccess(caller, template, AccessType.OperateEntry));
    }

    @Test
    public void ownerCanModifyPrivateImage() {
        when(owner.getId()).thenReturn(1L);
        assertTrue(checker.checkAccess(caller, template, AccessType.OperateEntry));
    }

    @Test
    public void projectMemberCanModifyProjectImage() {
        when(owner.getType()).thenReturn(Account.Type.PROJECT);
        when(checker._projectMgr.canAccessProjectAccount(caller, 2L)).thenReturn(true);
        assertTrue(checker.checkAccess(caller, template, AccessType.OperateEntry));
    }

    @Test(expected = PermissionDeniedException.class)
    public void projectNonmemberCannotModifyEvenWithLaunchPermission() {
        when(owner.getType()).thenReturn(Account.Type.PROJECT);
        checker.checkAccess(caller, template, AccessType.OperateEntry);
    }

    @Test(expected = PermissionDeniedException.class)
    public void domainAdminCannotModifyAnotherOwnerPrivateImage() {
        when(caller.getType()).thenReturn(Account.Type.DOMAIN_ADMIN);
        checker.checkAccess(caller, template, AccessType.OperateEntry);
    }

    @Test(expected = PermissionDeniedException.class)
    public void publicImageOwnerPolicyIsPreserved() {
        when(template.isPublicTemplate()).thenReturn(true);
        checker.checkAccess(caller, template, AccessType.OperateEntry);
    }
}
