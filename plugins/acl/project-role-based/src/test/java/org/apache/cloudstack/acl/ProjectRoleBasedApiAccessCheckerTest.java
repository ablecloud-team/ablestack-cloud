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

import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.UnavailableCommandException;
import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.projects.Project;
import com.cloud.projects.ProjectAccount;
import com.cloud.projects.ProjectAccountVO;
import com.cloud.projects.ProjectVO;
import com.cloud.projects.dao.ProjectAccountDao;
import com.cloud.user.User;
import com.cloud.user.UserVO;
import com.cloud.user.Account;
import com.cloud.user.AccountService;

import org.apache.cloudstack.context.CallContext;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import junit.framework.TestCase;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class ProjectRoleBasedApiAccessCheckerTest extends TestCase {
    @Mock
    ProjectAccountDao projectAccountDaoMock;

    @Mock
    RoleService roleServiceMock;

    @Mock
    AccountService accountServiceMock;

    @Mock
    DomainDao domainDaoMock;

    @Mock
    ProjectRoleService projectRoleServiceMock;

    @Mock
    ProjectAccountVO projectAccountVOMock;

    @Mock
    CallContext callContextMock;

    @InjectMocks
    ProjectRoleBasedApiAccessChecker projectRoleBasedApiAccessCheckerSpy = Mockito.spy(ProjectRoleBasedApiAccessChecker.class);

    List<String> apiNames = new ArrayList<>(Arrays.asList("apiName"));

    MockedStatic<CallContext> callContextMocked;

    @Before
    public void setup() {
        callContextMocked = Mockito.mockStatic(CallContext.class);
        callContextMocked.when(CallContext::current).thenReturn(callContextMock);
        Mockito.doReturn(true).when(roleServiceMock).isEnabled();
    }

    @Override
    @After
    public void tearDown() throws Exception {
        callContextMocked.close();
    }

    public Project getTestProject() {
        return new ProjectVO("Teste", "Teste", 1L, 1L);
    }

    private User getTestUser() {
        return new UserVO(12L, "some user", "password", "firstName", "lastName",
                "email@gmail.com", "GMT", "uuid", User.Source.UNKNOWN);
    }

    @Test
    public void getApisAllowedToUserTestRoleServiceIsDisabledShouldReturnUnchangedApiList() {
        Mockito.doReturn(false).when(roleServiceMock).isEnabled();

        List<String> apisReceived = projectRoleBasedApiAccessCheckerSpy.getApisAllowedToUser(null, getTestUser(), apiNames);
        Assert.assertEquals(1, apisReceived.size());
    }

    @Test
    public void getApisAllowedToUserTestProjectIsNullShouldReturnUnchangedApiList() {

        Mockito.doReturn(null).when(callContextMock).getProject();

        List<String> apisReceived = projectRoleBasedApiAccessCheckerSpy.getApisAllowedToUser(null, getTestUser(), apiNames);
        Assert.assertEquals(1, apisReceived.size());
    }

    @Test (expected = PermissionDeniedException.class)
    public void getApisAllowedToUserTestProjectAccountIsNullThrowPermissionDeniedException() {

        Mockito.when(callContextMock.getProject()).thenReturn(getTestProject());
        Mockito.when(projectAccountDaoMock.findByProjectIdAccountId(Mockito.anyLong(), Mockito.anyLong())).thenReturn(null);
        Mockito.when(projectAccountDaoMock.findByProjectIdUserId(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong())).thenReturn(null);

        projectRoleBasedApiAccessCheckerSpy.getApisAllowedToUser(null, getTestUser(), apiNames);
    }

    @Test
    public void getApisAllowedToUserTestProjectAccountHasAdminRoleReturnsUnchangedApiList() {

        Mockito.doReturn(getTestProject()).when(callContextMock).getProject();
        Mockito.doReturn(projectAccountVOMock).when(projectAccountDaoMock).findByProjectIdUserId(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong());
        Mockito.doReturn(ProjectAccount.Role.Admin).when(projectAccountVOMock).getAccountRole();

        List<String> apisReceived = projectRoleBasedApiAccessCheckerSpy.getApisAllowedToUser(null, getTestUser(), apiNames);
        Assert.assertEquals(1, apisReceived.size());
    }

    @Test
    public void getApisAllowedToUserTestProjectAccountNotPermittedForTheApiListShouldReturnEmptyList() {

        Mockito.doReturn(getTestProject()).when(callContextMock).getProject();
        Mockito.doReturn(projectAccountVOMock).when(projectAccountDaoMock).findByProjectIdUserId(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong());
        Mockito.doReturn(ProjectAccount.Role.Regular).when(projectAccountVOMock).getAccountRole();
        Mockito.when(projectAccountVOMock.getProjectRoleId()).thenReturn(1L);
        Mockito.when(projectRoleServiceMock.findCurrentUserProjectRolePermissions(Mockito.anyLong())).thenReturn(
                Arrays.asList(new ProjectRolePermissionVO(1L, 1L, "*", RolePermissionEntity.Permission.DENY, "deny")));


        List<String> apisReceived = projectRoleBasedApiAccessCheckerSpy.getApisAllowedToUser(null, getTestUser(), apiNames);
        Assert.assertTrue(apisReceived.isEmpty());
    }

    @Test
    public void getApisAllowedToUserTestProjectAccountPermittedForTheApiListShouldReturnTheSameList() {

        Mockito.doReturn(getTestProject()).when(callContextMock).getProject();
        Mockito.doReturn(projectAccountVOMock).when(projectAccountDaoMock).findByProjectIdUserId(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong());
        Mockito.doReturn(ProjectAccount.Role.Regular).when(projectAccountVOMock).getAccountRole();
        Mockito.when(projectAccountVOMock.getProjectRoleId()).thenReturn(1L);
        Mockito.when(projectRoleServiceMock.findCurrentUserProjectRolePermissions(Mockito.anyLong())).thenReturn(
                Arrays.asList(new ProjectRolePermissionVO(1L, 1L, "*", RolePermissionEntity.Permission.ALLOW, "allow")));


        List<String> apisReceived = projectRoleBasedApiAccessCheckerSpy.getApisAllowedToUser(null, getTestUser(), apiNames);
        Assert.assertEquals(1, apisReceived.size());
    }

    @Test
    public void rootAdministratorDiscoveryAndExecutionDoNotRequireMembership() {
        User user = getTestUser();
        Mockito.when(callContextMock.getProject()).thenReturn(getTestProject());
        Mockito.when(accountServiceMock.isRootAdmin(user.getAccountId())).thenReturn(true);
        Assert.assertSame(apiNames, projectRoleBasedApiAccessCheckerSpy.getApisAllowedToUser(null, user, apiNames));
        Assert.assertTrue(projectRoleBasedApiAccessCheckerSpy.checkAccess(user, "createNetwork", null));
        Mockito.verifyNoInteractions(projectAccountDaoMock, domainDaoMock);
    }

    @Test
    public void domainAdministratorDiscoveryAndExecutionValidateTheProjectDomain() {
        User user = getTestUser();
        Project project = getTestProject();
        DomainVO domain = Mockito.mock(DomainVO.class);
        Account account = Mockito.mock(Account.class);
        Mockito.when(callContextMock.getProject()).thenReturn(project);
        Mockito.when(accountServiceMock.isDomainAdmin(user.getAccountId())).thenReturn(true);
        Mockito.when(accountServiceMock.getAccount(user.getAccountId())).thenReturn(account);
        Mockito.when(domainDaoMock.findById(project.getDomainId())).thenReturn(domain);
        Assert.assertSame(apiNames, projectRoleBasedApiAccessCheckerSpy.getApisAllowedToUser(null, user, apiNames));
        Assert.assertTrue(projectRoleBasedApiAccessCheckerSpy.checkAccess(user, "addNicToVirtualMachine", null));
        Mockito.verify(accountServiceMock, Mockito.times(2)).checkAccess(account, domain);
        Mockito.verifyNoInteractions(projectAccountDaoMock);
    }

    @Test
    public void domainAdministratorOutsideHierarchyIsDeniedInBothPaths() {
        User user = getTestUser();
        DomainVO domain = Mockito.mock(DomainVO.class);
        Account account = Mockito.mock(Account.class);
        Mockito.when(callContextMock.getProject()).thenReturn(getTestProject());
        Mockito.when(accountServiceMock.isDomainAdmin(user.getAccountId())).thenReturn(true);
        Mockito.when(accountServiceMock.getAccount(user.getAccountId())).thenReturn(account);
        Mockito.when(domainDaoMock.findById(Mockito.anyLong())).thenReturn(domain);
        Mockito.doThrow(new PermissionDeniedException("outside domain")).when(accountServiceMock).checkAccess(account, domain);
        Assert.assertThrows(PermissionDeniedException.class,
                () -> projectRoleBasedApiAccessCheckerSpy.getApisAllowedToUser(null, user, apiNames));
        Assert.assertThrows(PermissionDeniedException.class,
                () -> projectRoleBasedApiAccessCheckerSpy.checkAccess(user, "updateNetwork", null));
        Mockito.verifyNoInteractions(projectAccountDaoMock);
    }

    @Test
    public void missingProjectDomainFailsClosedForDomainAdministrator() {
        User user = getTestUser();
        Mockito.when(callContextMock.getProject()).thenReturn(getTestProject());
        Mockito.when(accountServiceMock.isDomainAdmin(user.getAccountId())).thenReturn(true);
        Assert.assertThrows(PermissionDeniedException.class,
                () -> projectRoleBasedApiAccessCheckerSpy.getApisAllowedToUser(null, user, apiNames));
        Assert.assertThrows(PermissionDeniedException.class,
                () -> projectRoleBasedApiAccessCheckerSpy.checkAccess(user, "createNetwork", null));
        Mockito.verifyNoInteractions(projectAccountDaoMock);
    }

    @Test
    public void ordinaryNonMemberStillCannotDiscoverOrExecuteProjectApis() {
        Mockito.when(callContextMock.getProject()).thenReturn(getTestProject());
        User user = getTestUser();
        Assert.assertThrows(PermissionDeniedException.class,
                () -> projectRoleBasedApiAccessCheckerSpy.getApisAllowedToUser(null, user, apiNames));
        Assert.assertThrows(UnavailableCommandException.class,
                () -> projectRoleBasedApiAccessCheckerSpy.checkAccess(user, "createNetwork", null));
    }

    @Test
    public void userSpecificRestrictionTakesPrecedenceOverAccountMembership() {
        Mockito.when(callContextMock.getProject()).thenReturn(getTestProject());
        Mockito.when(projectAccountDaoMock.findByProjectIdUserId(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong())).thenReturn(projectAccountVOMock);
        Mockito.when(projectAccountVOMock.getAccountRole()).thenReturn(ProjectAccount.Role.Regular);
        Mockito.when(projectAccountVOMock.getProjectRoleId()).thenReturn(1L);
        Mockito.when(projectRoleServiceMock.findCurrentUserProjectRolePermissions(Mockito.anyLong())).thenReturn(
                Arrays.asList(new ProjectRolePermissionVO(1L, 1L, "*", RolePermissionEntity.Permission.DENY, "deny")));
        Assert.assertTrue(projectRoleBasedApiAccessCheckerSpy.getApisAllowedToUser(null, getTestUser(), apiNames).isEmpty());
        Assert.assertThrows(PermissionDeniedException.class,
                () -> projectRoleBasedApiAccessCheckerSpy.checkAccess(getTestUser(), "createNetwork", null));
        Mockito.verify(projectAccountDaoMock, Mockito.never()).findByProjectIdAccountId(Mockito.anyLong(), Mockito.anyLong());
    }

    private void configuredMember(List<ProjectRolePermission> permissions) {
        Mockito.when(callContextMock.getProject()).thenReturn(getTestProject());
        Mockito.when(projectAccountDaoMock.findByProjectIdUserId(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong())).thenReturn(projectAccountVOMock);
        Mockito.when(projectAccountVOMock.getAccountRole()).thenReturn(ProjectAccount.Role.Regular);
        Mockito.when(projectAccountVOMock.getProjectRoleId()).thenReturn(1L);
        Mockito.when(projectRoleServiceMock.findCurrentUserProjectRolePermissions(Mockito.anyLong())).thenReturn(permissions);
    }

    private ProjectRolePermission permission(String rule, RolePermissionEntity.Permission permission) {
        return new ProjectRolePermissionVO(1L, 1L, rule, permission, "test rule");
    }

    @Test
    public void discoveryReadsOrderedPermissionsOnceAndMatchesExecution() {
        configuredMember(Arrays.asList(permission("updateNetwork", RolePermissionEntity.Permission.DENY),
                permission("addNicToVirtualMachine", RolePermissionEntity.Permission.DENY),
                permission("list*", RolePermissionEntity.Permission.ALLOW)));
        List<String> result = projectRoleBasedApiAccessCheckerSpy.getApisAllowedToUser(null, getTestUser(),
                new ArrayList<>(Arrays.asList("listApis", "listNetworks", "listVirtualMachines", "updateNetwork", "addNicToVirtualMachine")));
        Assert.assertEquals(Arrays.asList("listApis", "listNetworks", "listVirtualMachines"), result);
        Mockito.verify(projectRoleServiceMock).findCurrentUserProjectRolePermissions(Mockito.anyLong());
        Assert.assertTrue(projectRoleBasedApiAccessCheckerSpy.checkAccess(getTestUser(), "listNetworks", null));
        Assert.assertThrows(PermissionDeniedException.class,
                () -> projectRoleBasedApiAccessCheckerSpy.checkAccess(getTestUser(), "updateNetwork", null));
        Mockito.verify(projectRoleServiceMock, Mockito.never()).findProjectRole(Mockito.anyLong(), Mockito.anyLong());
        Mockito.verify(projectRoleServiceMock, Mockito.never()).findAllProjectRolePermissions(Mockito.anyLong(), Mockito.anyLong());
    }

    @Test
    public void exactRulesPrecedeLaterWildcardsAndAreCaseInsensitive() {
        configuredMember(Arrays.asList(permission("listNetworks", RolePermissionEntity.Permission.ALLOW),
                permission("LIST*", RolePermissionEntity.Permission.DENY)));
        Assert.assertTrue(projectRoleBasedApiAccessCheckerSpy.checkAccess(getTestUser(), "LISTNETWORKS", null));
        Assert.assertThrows(PermissionDeniedException.class,
                () -> projectRoleBasedApiAccessCheckerSpy.checkAccess(getTestUser(), "listApis", null));
    }

    @Test
    public void earlierWildcardDenyWinsOverLaterExactAllow() {
        configuredMember(Arrays.asList(permission("list*", RolePermissionEntity.Permission.DENY),
                permission("listNetworks", RolePermissionEntity.Permission.ALLOW)));
        Assert.assertThrows(PermissionDeniedException.class,
                () -> projectRoleBasedApiAccessCheckerSpy.checkAccess(getTestUser(), "listNetworks", null));
    }

    @Test
    public void changedPermissionsApplyToTheNextDiscoveryAndExecution() {
        configuredMember(Arrays.asList(permission("listNetworks", RolePermissionEntity.Permission.ALLOW)));
        Assert.assertTrue(projectRoleBasedApiAccessCheckerSpy.checkAccess(getTestUser(), "listNetworks", null));
        Mockito.when(projectRoleServiceMock.findCurrentUserProjectRolePermissions(Mockito.anyLong()))
                .thenReturn(Arrays.asList(permission("listNetworks", RolePermissionEntity.Permission.DENY)));
        Assert.assertTrue(projectRoleBasedApiAccessCheckerSpy.getApisAllowedToUser(null, getTestUser(), new ArrayList<>(Arrays.asList("listNetworks"))).isEmpty());
        Assert.assertThrows(PermissionDeniedException.class,
                () -> projectRoleBasedApiAccessCheckerSpy.checkAccess(getTestUser(), "listNetworks", null));
    }

    @Test
    public void missingAssignedRoleFailsClosedInDiscoveryAndExecution() {
        configuredMember(new ArrayList<>());
        Mockito.when(projectRoleServiceMock.findCurrentUserProjectRolePermissions(Mockito.anyLong()))
                .thenThrow(new PermissionDeniedException("assigned role unavailable"));
        Assert.assertThrows(PermissionDeniedException.class,
                () -> projectRoleBasedApiAccessCheckerSpy.getApisAllowedToUser(null, getTestUser(), new ArrayList<>(Arrays.asList("listNetworks"))));
        Assert.assertThrows(PermissionDeniedException.class,
                () -> projectRoleBasedApiAccessCheckerSpy.checkAccess(getTestUser(), "listNetworks", null));
    }
}
