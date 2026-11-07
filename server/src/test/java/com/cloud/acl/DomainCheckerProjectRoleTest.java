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

import java.util.Arrays;

import org.apache.cloudstack.acl.ProjectRolePermissionVO;
import org.apache.cloudstack.acl.ProjectRoleService;
import org.apache.cloudstack.acl.RolePermissionEntity;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.exception.PermissionDeniedException;
import com.cloud.projects.Project;
import com.cloud.projects.ProjectAccount;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class DomainCheckerProjectRoleTest {
    @Test
    public void memberReadAndWriteFollowTheSameInternalPermissionLookup() {
        DomainChecker checker = new DomainChecker();
        ProjectRoleService service = Mockito.mock(ProjectRoleService.class);
        Project project = Mockito.mock(Project.class);
        ProjectAccount member = Mockito.mock(ProjectAccount.class);
        ReflectionTestUtils.setField(checker, "projectRoleService", service);
        Mockito.when(project.getId()).thenReturn(10L);
        Mockito.when(member.getProjectRoleId()).thenReturn(3L);
        Mockito.when(service.findCurrentUserProjectRolePermissions(10L)).thenReturn(Arrays.asList(
                new ProjectRolePermissionVO(10L, 3L, "updateNetwork", RolePermissionEntity.Permission.DENY, "deny"),
                new ProjectRolePermissionVO(10L, 3L, "list*", RolePermissionEntity.Permission.ALLOW, "allow")));
        assertTrue(ReflectionTestUtils.invokeMethod(checker, "isPermitted", project, member, "listNetworks"));
        assertThrows(PermissionDeniedException.class,
                () -> ReflectionTestUtils.invokeMethod(checker, "isPermitted", project, member, "updateNetwork"));
        Mockito.verify(service, Mockito.never()).findProjectRole(Mockito.anyLong(), Mockito.anyLong());
        Mockito.verify(service, Mockito.never()).findAllProjectRolePermissions(Mockito.anyLong(), Mockito.anyLong());
    }

    @Test
    public void membershipWithoutCustomRoleKeepsBaseline() {
        DomainChecker checker = new DomainChecker();
        ProjectAccount member = Mockito.mock(ProjectAccount.class);
        Mockito.when(member.getProjectRoleId()).thenReturn(null);
        assertTrue(ReflectionTestUtils.invokeMethod(checker, "isPermitted", Mockito.mock(Project.class), member, "listNetworks"));
    }
}
