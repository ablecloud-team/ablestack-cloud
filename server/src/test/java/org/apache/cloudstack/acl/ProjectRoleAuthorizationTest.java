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

package org.apache.cloudstack.acl;

import java.util.Arrays;
import java.util.List;

import org.apache.cloudstack.acl.dao.ProjectRoleDao;
import org.apache.cloudstack.acl.dao.ProjectRolePermissionsDao;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.exception.PermissionDeniedException;
import com.cloud.projects.ProjectAccountVO;
import com.cloud.projects.ProjectVO;
import com.cloud.projects.dao.ProjectAccountDao;
import com.cloud.projects.dao.ProjectDao;
import com.cloud.user.AccountService;
import com.cloud.user.User;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class ProjectRoleAuthorizationTest {
    @Spy
    @InjectMocks
    private ProjectRoleManagerImpl manager;
    @Mock
    private ProjectAccountDao projAccDao;
    @Mock
    private ProjectRoleDao projRoleDao;
    @Mock
    private ProjectRolePermissionsDao projRolePermissionsDao;
    @Mock
    private ProjectDao projectDao;
    @Mock
    private AccountService accountService;
    @Mock
    private ProjectAccountVO member;
    @Mock
    private User user;

    @Before
    public void setup() {
        when(projectDao.findById(10L)).thenReturn(Mockito.mock(ProjectVO.class));
        Mockito.doReturn(true).when(manager).isEnabled();
        Mockito.doReturn(user).when(manager).getCurrentUser();
        when(user.getId()).thenReturn(9L);
        when(user.getAccountId()).thenReturn(7L);
    }

    private void assignedRole(long roleId) {
        when(member.getProjectRoleId()).thenReturn(roleId);
        ProjectRoleVO role = Mockito.mock(ProjectRoleVO.class);
        when(role.getProjectId()).thenReturn(10L);
        when(projRoleDao.findById(roleId)).thenReturn(role);
    }

    @Test
    public void regularMemberReadsOnlyAssignedOrderedPermissionsWithoutManagementPrivilege() {
        when(projAccDao.findByProjectIdUserId(10L, 7L, 9L)).thenReturn(member);
        assignedRole(3L);
        List<ProjectRolePermissionVO> rules = Arrays.asList(
                new ProjectRolePermissionVO(10L, 3L, "updateNetwork", RolePermissionEntity.Permission.DENY, "deny"),
                new ProjectRolePermissionVO(10L, 3L, "list*", RolePermissionEntity.Permission.ALLOW, "allow"));
        when(projRolePermissionsDao.findAllByRoleIdSorted(3L, 10L)).thenReturn(rules);
        assertEquals(rules, manager.findCurrentUserProjectRolePermissions(10L));
        Mockito.verifyNoInteractions(accountService);
        verify(projAccDao, never()).findByProjectIdAccountId(anyLong(), anyLong());
    }

    @Test
    public void accountMembershipIsUsedWhenNoUserMembershipExists() {
        when(projAccDao.findByProjectIdAccountId(10L, 7L)).thenReturn(member);
        assignedRole(3L);
        assertTrue(manager.findCurrentUserProjectRolePermissions(10L).isEmpty());
        verify(projRolePermissionsDao).findAllByRoleIdSorted(3L, 10L);
    }

    @Test
    public void userMembershipPrecedesAccountMembership() {
        when(projAccDao.findByProjectIdUserId(10L, 7L, 9L)).thenReturn(member);
        assignedRole(4L);
        manager.findCurrentUserProjectRolePermissions(10L);
        verify(projRolePermissionsDao).findAllByRoleIdSorted(4L, 10L);
        verify(projAccDao, never()).findByProjectIdAccountId(anyLong(), anyLong());
    }

    @Test
    public void memberWithoutCustomRoleKeepsBaselinePermissions() {
        when(projAccDao.findByProjectIdUserId(10L, 7L, 9L)).thenReturn(member);
        when(member.getProjectRoleId()).thenReturn(null);
        assertTrue(manager.findCurrentUserProjectRolePermissions(10L).isEmpty());
        Mockito.verifyNoInteractions(projRoleDao, projRolePermissionsDao);
    }

    @Test
    public void nonMemberCannotReadInternalPermissions() {
        assertThrows(PermissionDeniedException.class, () -> manager.findCurrentUserProjectRolePermissions(10L));
        Mockito.verifyNoInteractions(projRoleDao, projRolePermissionsDao);
    }

    @Test
    public void roleFromAnotherProjectFailsClosed() {
        when(projAccDao.findByProjectIdUserId(10L, 7L, 9L)).thenReturn(member);
        when(member.getProjectRoleId()).thenReturn(3L);
        ProjectRoleVO role = Mockito.mock(ProjectRoleVO.class);
        when(role.getProjectId()).thenReturn(11L);
        when(projRoleDao.findById(3L)).thenReturn(role);
        assertThrows(PermissionDeniedException.class, () -> manager.findCurrentUserProjectRolePermissions(10L));
        Mockito.verifyNoInteractions(projRolePermissionsDao);
    }

    @Test
    public void removedAssignedRoleFailsClosed() {
        when(projAccDao.findByProjectIdUserId(10L, 7L, 9L)).thenReturn(member);
        when(member.getProjectRoleId()).thenReturn(3L);
        assertThrows(PermissionDeniedException.class, () -> manager.findCurrentUserProjectRolePermissions(10L));
        Mockito.verifyNoInteractions(projRolePermissionsDao);
    }

    @Test
    public void roleAssignmentChangeIsReadOnNextRequest() {
        when(projAccDao.findByProjectIdUserId(10L, 7L, 9L)).thenReturn(member);
        assignedRole(3L);
        manager.findCurrentUserProjectRolePermissions(10L);
        assignedRole(4L);
        manager.findCurrentUserProjectRolePermissions(10L);
        verify(projRolePermissionsDao).findAllByRoleIdSorted(3L, 10L);
        verify(projRolePermissionsDao).findAllByRoleIdSorted(4L, 10L);
    }
}
