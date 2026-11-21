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
package com.cloud.projects;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.cloudstack.acl.ControlledEntity;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.framework.messagebus.MessageBus;
import org.apache.cloudstack.reservation.dao.ReservationDao;
import org.apache.cloudstack.webhook.WebhookHelper;
import org.apache.commons.collections.CollectionUtils;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;

import com.cloud.configuration.Resource.ResourceType;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.projects.ProjectAccount.Role;
import com.cloud.projects.dao.ProjectAccountDao;
import com.cloud.projects.dao.ProjectDao;
import com.cloud.projects.dao.ProjectInvitationDao;
import com.cloud.resourcelimit.CheckedReservation;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountVO;
import com.cloud.user.ResourceLimitService;
import com.cloud.user.User;
import com.cloud.user.UserVO;
import com.cloud.user.dao.AccountDao;
import com.cloud.user.dao.UserDao;
import com.cloud.utils.component.ComponentContext;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;


@RunWith(MockitoJUnitRunner.class)
public class ProjectManagerImplTest {

    @Spy
    @InjectMocks
    ProjectManagerImpl projectManager;

    @Mock
    ProjectDao projectDao;

    @Mock
    ProjectInvitationDao projectInvitationDao;

    @Mock
    ProjectAccountDao projectAccountDao;

    @Mock
    AccountDao accountDao;

    @Mock
    AccountManager accountManager;

    @Mock
    UserDao userDao;

    @Mock
    MessageBus messageBus;

    @Mock
    ResourceLimitService resourceLimitMgr;

    @Mock
    ReservationDao reservationDao;

    List<ProjectVO> updateProjects;

    @Before
    public void setUp() throws Exception {
        updateProjects = new ArrayList<>();
        Mockito.when(projectDao.update(Mockito.anyLong(), Mockito.any(ProjectVO.class))).thenAnswer((Answer<Boolean>) invocation -> {
            ProjectVO project = (ProjectVO)invocation.getArguments()[1];
            updateProjects.add(project);
            return true;
        });
    }

    @Test
    public void testCreateProjectChecksLimitOfSelectedOwnerInSelectedDomain() throws ResourceAllocationException {
        Account caller = Mockito.mock(Account.class);
        AccountVO owner = Mockito.mock(AccountVO.class);
        CallContext context = Mockito.mock(CallContext.class);
        try (MockedStatic<CallContext> callContext = Mockito.mockStatic(CallContext.class)) {
            callContext.when(CallContext::current).thenReturn(context);
            Mockito.when(context.getCallingAccount()).thenReturn(caller);
            Mockito.when(owner.getId()).thenReturn(42L);
            Mockito.when(owner.getAccountName()).thenReturn("target");
            Mockito.when(owner.getDomainId()).thenReturn(18L);
            Mockito.when(owner.getState()).thenReturn(Account.State.ENABLED);
            Mockito.when(accountDao.findById(42L)).thenReturn(owner);
            Mockito.when(accountManager.finalizeOwner(caller, "target", 18L, null)).thenReturn(owner);
            Mockito.doThrow(new ResourceAllocationException("test quota", ResourceType.project))
                    .when(resourceLimitMgr).checkResourceLimit(owner, ResourceType.project);

            Assert.assertThrows(ResourceAllocationException.class,
                    () -> projectManager.createProject("example", "example", null, null, null, 42L));

            Mockito.verify(projectDao).findByNameAndDomain("example", 18L);
            Mockito.verify(resourceLimitMgr).checkResourceLimit(owner, ResourceType.project);
            Mockito.verifyNoMoreInteractions(resourceLimitMgr);
        }
    }

    @Test
    public void testResolveProjectOwnerRejectsConflictingDomainBeforeCheckingQuotas() {
        AccountVO owner = Mockito.mock(AccountVO.class);
        CallContext context = Mockito.mock(CallContext.class);
        try (MockedStatic<CallContext> callContext = Mockito.mockStatic(CallContext.class)) {
            callContext.when(CallContext::current).thenReturn(context);
            Mockito.when(owner.getDomainId()).thenReturn(18L);
            Mockito.when(owner.getState()).thenReturn(Account.State.ENABLED);
            Mockito.when(accountDao.findById(42L)).thenReturn(owner);

            Assert.assertThrows(InvalidParameterValueException.class,
                    () -> projectManager.resolveProjectOwner(null, 19L, null, 42L));

            Mockito.verifyNoInteractions(accountManager, resourceLimitMgr);
        }
    }

    @Test
    public void testResolveProjectOwnerChecksDelegationThroughAccountAuthorization() {
        Account caller = Mockito.mock(Account.class);
        AccountVO owner = Mockito.mock(AccountVO.class);
        CallContext context = Mockito.mock(CallContext.class);
        try (MockedStatic<CallContext> callContext = Mockito.mockStatic(CallContext.class)) {
            callContext.when(CallContext::current).thenReturn(context);
            Mockito.when(context.getCallingAccount()).thenReturn(caller);
            Mockito.when(owner.getDomainId()).thenReturn(18L);
            Mockito.when(owner.getAccountName()).thenReturn("target");
            Mockito.when(owner.getState()).thenReturn(Account.State.ENABLED);
            Mockito.when(accountDao.findById(42L)).thenReturn(owner);
            Mockito.when(accountManager.finalizeOwner(caller, "target", 18L, null))
                    .thenThrow(new PermissionDeniedException("test scope denial"));

            Assert.assertThrows(PermissionDeniedException.class,
                    () -> projectManager.resolveProjectOwner(null, null, null, 42L));
        }
    }

    @Test
    public void testResolveProjectOwnerRejectsUserFromAnotherAccount() {
        Account caller = Mockito.mock(Account.class);
        Account owner = Mockito.mock(Account.class);
        UserVO user = Mockito.mock(UserVO.class);
        CallContext context = Mockito.mock(CallContext.class);
        try (MockedStatic<CallContext> callContext = Mockito.mockStatic(CallContext.class)) {
            callContext.when(CallContext::current).thenReturn(context);
            Mockito.when(context.getCallingAccount()).thenReturn(caller);
            Mockito.when(accountManager.finalizeOwner(caller, "target", 18L, null)).thenReturn(owner);
            Mockito.when(owner.getId()).thenReturn(42L);
            Mockito.when(owner.getState()).thenReturn(Account.State.ENABLED);
            Mockito.when(userDao.findById(17L)).thenReturn(user);
            Mockito.when(user.getState()).thenReturn(Account.State.ENABLED);
            Mockito.when(user.getAccountId()).thenReturn(43L);

            Assert.assertThrows(InvalidParameterValueException.class,
                    () -> projectManager.resolveProjectOwner("target", 18L, 17L, null));
        }
    }

    @Test
    public void testDeleteProjectRemovesAccountAndUserScopedMemberships() {
        long projectId = 100L;
        ProjectVO project = Mockito.mock(ProjectVO.class);
        AccountVO projectAccount = Mockito.mock(AccountVO.class);
        AccountVO system = Mockito.mock(AccountVO.class);
        ProjectAccountVO accountMembership = Mockito.mock(ProjectAccountVO.class);
        ProjectAccountVO userMembership = Mockito.mock(ProjectAccountVO.class);
        Mockito.when(project.getId()).thenReturn(projectId);
        Mockito.when(project.getProjectAccountId()).thenReturn(90L);
        Mockito.when(accountDao.findById(90L)).thenReturn(projectAccount);
        Mockito.when(accountDao.findById(Account.ACCOUNT_ID_SYSTEM)).thenReturn(system);
        Mockito.when(accountManager.deleteAccount(projectAccount, User.UID_SYSTEM, system)).thenReturn(true);
        Mockito.when(projectAccountDao.listByProjectId(projectId)).thenReturn(List.of(accountMembership, userMembership));
        Mockito.when(accountMembership.getId()).thenReturn(11L);
        Mockito.when(userMembership.getId()).thenReturn(12L);
        Mockito.when(projectAccountDao.remove(11L)).thenReturn(true);
        Mockito.when(projectAccountDao.remove(12L)).thenReturn(true);
        Mockito.when(projectDao.remove(projectId)).thenReturn(true);

        try (MockedStatic<Transaction> transaction = Mockito.mockStatic(Transaction.class)) {
            transaction.when(() -> Transaction.execute(Mockito.any(TransactionCallback.class)))
                    .thenAnswer(call -> ((TransactionCallback<?>) call.getArgument(0)).doInTransaction(null));

            Assert.assertTrue(projectManager.deleteProject(system, User.UID_SYSTEM, project));
        }

        Mockito.verify(projectAccountDao).remove(11L);
        Mockito.verify(projectAccountDao).remove(12L);
    }

    @Test
    public void testUserScopedAdministratorDoesNotAuthorizeSibling() {
        Account caller = Mockito.mock(Account.class);
        User user = Mockito.mock(User.class);
        ProjectVO project = Mockito.mock(ProjectVO.class);
        CallContext context = Mockito.mock(CallContext.class);
        Mockito.when(caller.getId()).thenReturn(42L);
        Mockito.when(user.getId()).thenReturn(77L);
        Mockito.when(project.getId()).thenReturn(100L);
        Mockito.when(project.getProjectAccountId()).thenReturn(90L);
        try (MockedStatic<CallContext> callContext = Mockito.mockStatic(CallContext.class)) {
            callContext.when(CallContext::current).thenReturn(context);
            Mockito.when(context.getCallingUser()).thenReturn(user);
            Mockito.when(context.getProject()).thenReturn(project);

            Assert.assertFalse(projectManager.canModifyProjectAccount(caller, 90L));
        }
        Mockito.verify(projectAccountDao).findByProjectIdUserId(100L, 42L, 77L);
        Mockito.verify(projectAccountDao).findByProjectIdAccountId(100L, 42L);
    }

    @Test
    public void testLastUserAdministratorCannotBeRemoved() {
        Account caller = Mockito.mock(Account.class);
        CallContext context = Mockito.mock(CallContext.class);
        UserVO user = Mockito.mock(UserVO.class);
        ProjectVO project = Mockito.mock(ProjectVO.class);
        ProjectAccountVO owner = Mockito.mock(ProjectAccountVO.class);
        Mockito.when(projectDao.lockRow(100L, true)).thenReturn(project);
        Mockito.when(userDao.findById(77L)).thenReturn(user);
        Mockito.when(user.getAccountId()).thenReturn(42L);
        Mockito.when(projectAccountDao.findByProjectIdUserId(100L, 42L, 77L)).thenReturn(owner);
        Mockito.when(projectAccountDao.getProjectOwners(100L)).thenReturn(List.of(owner));
        Mockito.when(owner.getAccountRole()).thenReturn(Role.Admin);

        try (MockedStatic<CallContext> callContext = Mockito.mockStatic(CallContext.class);
             MockedStatic<Transaction> transaction = Mockito.mockStatic(Transaction.class)) {
            callContext.when(CallContext::current).thenReturn(context);
            Mockito.when(context.getCallingAccount()).thenReturn(caller);
            transaction.when(() -> Transaction.execute(Mockito.any(TransactionCallback.class)))
                    .thenAnswer(call -> ((TransactionCallback<?>) call.getArgument(0)).doInTransaction(null));

            InvalidParameterValueException error = Assert.assertThrows(InvalidParameterValueException.class,
                    () -> projectManager.deleteUserFromProject(100L, 77L));
            Assert.assertTrue(error.getMessage().contains("last project administrator"));
        }
        Mockito.verify(projectAccountDao, Mockito.never()).remove(Mockito.anyLong());
        Mockito.verifyNoInteractions(resourceLimitMgr);
    }

    private void runUpdateProjectNameAndDisplayTextTest(boolean nonNullName, boolean nonNullDisplayText) {
        ProjectVO projectVO = new ProjectVO();
        String newName = nonNullName ? "NewName" : null;
        String newDisplayText = nonNullDisplayText ? "NewDisplayText" : null;
        projectManager.updateProjectNameAndDisplayText(projectVO, newName, newDisplayText);
        if (!nonNullName && !nonNullDisplayText) {
            Assert.assertTrue(updateProjects.isEmpty());
        } else {
            Assert.assertFalse(updateProjects.isEmpty());
            Assert.assertEquals(1, updateProjects.size());
            ProjectVO updatedProject = updateProjects.get(0);
            Assert.assertNotNull(updatedProject);
            if (nonNullName) {
                Assert.assertEquals(newName, updatedProject.getName());
            }
            if (nonNullDisplayText) {
                Assert.assertEquals(newDisplayText, updatedProject.getDisplayText());
            }
        }
    }

    @Test
    public void testUpdateProjectNameAndDisplayTextNoUpdate() {
        runUpdateProjectNameAndDisplayTextTest(false, false);
    }

    @Test
    public void testUpdateProjectNameAndDisplayTextUpdateName() {
        runUpdateProjectNameAndDisplayTextTest(true, false);
    }

    @Test
    public void testUpdateProjectNameAndDisplayTextUpdateDisplayText() {
        runUpdateProjectNameAndDisplayTextTest(false, true);
    }

    @Test
    public void testUpdateProjectNameAndDisplayTextUpdateNameDisplayText() {
        runUpdateProjectNameAndDisplayTextTest(true, true);
    }

    @Test
    public void testDeleteWebhooksForAccount() {
        try (MockedStatic<ComponentContext> mockedComponentContext = Mockito.mockStatic(ComponentContext.class)) {
            WebhookHelper webhookHelper = Mockito.mock(WebhookHelper.class);
            List<ControlledEntity> webhooks = List.of(Mockito.mock(ControlledEntity.class),
                    Mockito.mock(ControlledEntity.class));
            Mockito.doReturn(webhooks).when(webhookHelper).listWebhooksByAccount(Mockito.anyLong());
            mockedComponentContext.when(() -> ComponentContext.getDelegateComponentOfType(WebhookHelper.class))
                    .thenReturn(webhookHelper);
            Project project = Mockito.mock(Project.class);
            Mockito.when(project.getProjectAccountId()).thenReturn(1L);
            List<? extends ControlledEntity> result = projectManager.listWebhooksForProject(project);
            Assert.assertEquals(2, result.size());
        }
    }

    @Test
    public void testDeleteWebhooksForAccountNoBean() {
        try (MockedStatic<ComponentContext> mockedComponentContext = Mockito.mockStatic(ComponentContext.class)) {
            mockedComponentContext.when(() -> ComponentContext.getDelegateComponentOfType(WebhookHelper.class))
                    .thenThrow(NoSuchBeanDefinitionException.class);
            List<? extends ControlledEntity> result =
                    projectManager.listWebhooksForProject(Mockito.mock(Project.class));
            Assert.assertTrue(CollectionUtils.isEmpty(result));
        }
    }

    @Test
    public void cleanupProjectsForUserTestNoAssociationsReturnsFalse() {
        Project project = Mockito.mock(Project.class);
        Mockito.when(project.getId()).thenReturn(100L);
        User user = mockUser(1L, 10L);
        Mockito.when(projectAccountDao.listBy(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong()))
                .thenReturn(Collections.emptyList());

        boolean result = projectManager.cleanupProjectsForUser(project, user);

        Assert.assertFalse(result);
        Mockito.verify(projectInvitationDao).removeBy(100L, 10L, 1L);
        Mockito.verify(projectAccountDao, Mockito.never()).remove(Mockito.anyLong());
        Mockito.verify(resourceLimitMgr, Mockito.never()).decrementResourceCount(Mockito.anyLong(), Mockito.any(ResourceType.class));
    }

    @Test
    public void cleanupProjectsForUserTestRemovesAdminAndRegularAssociations() {
        Project project = Mockito.mock(Project.class);
        Mockito.when(project.getId()).thenReturn(100L);
        User user = mockUser(1L, 10L);
        ProjectAccountVO admin = mockProjectAccount(1L, Role.Admin);
        ProjectAccountVO regular = mockProjectAccount(2L, Role.Regular);
        Mockito.when(projectAccountDao.listBy(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong()))
                .thenReturn(List.of(admin, regular));

        boolean result = projectManager.cleanupProjectsForUser(project, user);

        Assert.assertTrue(result);
        Mockito.verify(projectInvitationDao).removeBy(100L, 10L, 1L);
        Mockito.verify(projectAccountDao).remove(1L);
        Mockito.verify(projectAccountDao).remove(2L);
        Mockito.verify(resourceLimitMgr).decrementResourceCount(10L, ResourceType.project);
    }

    @Test
    public void cleanupProjectsForUserTestNullProject() {
        User user = mockUser(1L, 10L);
        ProjectAccountVO admin = mockProjectAccount(1L, Role.Admin);
        Mockito.when(projectAccountDao.listBy(Mockito.isNull(), Mockito.eq(10L), Mockito.eq(1L)))
                .thenReturn(List.of(admin));

        boolean result = projectManager.cleanupProjectsForUser(null, user);

        Assert.assertTrue(result);
        Mockito.verify(projectInvitationDao).removeBy(Mockito.isNull(), Mockito.eq(10L), Mockito.eq(1L));
        Mockito.verify(projectAccountDao).remove(1L);
        Mockito.verify(resourceLimitMgr).decrementResourceCount(10L, ResourceType.project);
    }

    @Test
    public void moveProjectAssociationsToUserTestNoProjectAccounts() throws ResourceAllocationException {
        User oldUser = mockUser(1L, 10L);
        User newUser = mockUser(2L, 20L);
        Mockito.when(projectAccountDao.listBy(Mockito.isNull(), Mockito.eq(10L), Mockito.eq(1L)))
                .thenReturn(Collections.emptyList());

        projectManager.moveProjectAssociationsToUser(oldUser, newUser);

        Mockito.verify(projectInvitationDao).move(oldUser, newUser);
        Mockito.verify(projectAccountDao, Mockito.never()).move(Mockito.any(), Mockito.any());
        Mockito.verifyNoInteractions(accountDao);
        Mockito.verifyNoInteractions(resourceLimitMgr);
    }

    @Test
    public void moveProjectAssociationsToUserTestSameAccount() throws ResourceAllocationException {
        User oldUser = mockUser(1L, 10L);
        User newUser = mockUser(2L, 10L);
        ProjectAccountVO regular = mockProjectAccount(1L, Role.Regular);
        Mockito.when(projectAccountDao.listBy(Mockito.isNull(), Mockito.eq(10L), Mockito.eq(1L)))
                .thenReturn(List.of(regular));
        AccountVO oldAccount = mockAccount(10L);
        AccountVO newAccount = mockAccount(10L);
        Mockito.when(accountDao.findById(10L)).thenReturn(oldAccount).thenReturn(newAccount);

        try (MockedConstruction<CheckedReservation> ignored = Mockito.mockConstruction(CheckedReservation.class)) {
            projectManager.moveProjectAssociationsToUser(oldUser, newUser);
        }

        Mockito.verify(projectInvitationDao).move(oldUser, newUser);
        Mockito.verify(projectAccountDao).move(oldUser, newUser);
        Mockito.verify(resourceLimitMgr, Mockito.never())
                .incrementResourceCount(Mockito.anyLong(), Mockito.any(ResourceType.class), Mockito.anyLong());
        Mockito.verify(resourceLimitMgr, Mockito.never())
                .decrementResourceCount(Mockito.anyLong(), Mockito.any(ResourceType.class), Mockito.anyLong());
    }

    @Test
    public void moveProjectAssociationsToUserTestDifferentAccountsWithAdminRole() throws ResourceAllocationException {
        User oldUser = mockUser(1L, 10L);
        User newUser = mockUser(2L, 20L);
        ProjectAccountVO admin = mockProjectAccount(1L, Role.Admin);
        Mockito.when(projectAccountDao.listBy(Mockito.isNull(), Mockito.eq(10L), Mockito.eq(1L)))
                .thenReturn(List.of(admin));
        AccountVO oldAccount = mockAccount(10L);
        AccountVO newAccount = mockAccount(20L);
        Mockito.when(accountDao.findById(10L)).thenReturn(oldAccount);
        Mockito.when(accountDao.findById(20L)).thenReturn(newAccount);

        try (MockedConstruction<CheckedReservation> ignored = Mockito.mockConstruction(CheckedReservation.class)) {
            projectManager.moveProjectAssociationsToUser(oldUser, newUser);
        }

        Mockito.verify(projectInvitationDao).move(oldUser, newUser);
        Mockito.verify(projectAccountDao).move(oldUser, newUser);
        Mockito.verify(resourceLimitMgr).incrementResourceCount(20L, ResourceType.project, 1L);
        Mockito.verify(resourceLimitMgr).decrementResourceCount(10L, ResourceType.project, 1L);
    }

    @Test
    public void moveProjectAssociationsToUserTestDifferentAccountsWithoutAdminRole() throws ResourceAllocationException {
        User oldUser = mockUser(1L, 10L);
        User newUser = mockUser(2L, 20L);
        ProjectAccountVO regular = mockProjectAccount(1L, Role.Regular);
        Mockito.when(projectAccountDao.listBy(Mockito.isNull(), Mockito.eq(10L), Mockito.eq(1L)))
                .thenReturn(List.of(regular));
        AccountVO oldAccount = mockAccount(10L);
        AccountVO newAccount = mockAccount(20L);
        Mockito.when(accountDao.findById(10L)).thenReturn(oldAccount);
        Mockito.when(accountDao.findById(20L)).thenReturn(newAccount);

        try (MockedConstruction<CheckedReservation> ignored = Mockito.mockConstruction(CheckedReservation.class)) {
            projectManager.moveProjectAssociationsToUser(oldUser, newUser);
        }

        Mockito.verify(projectInvitationDao).move(oldUser, newUser);
        Mockito.verify(projectAccountDao).move(oldUser, newUser);
        Mockito.verify(resourceLimitMgr, Mockito.never())
                .incrementResourceCount(Mockito.anyLong(), Mockito.any(ResourceType.class), Mockito.anyLong());
        Mockito.verify(resourceLimitMgr, Mockito.never())
                .decrementResourceCount(Mockito.anyLong(), Mockito.any(ResourceType.class), Mockito.anyLong());
    }

    private User mockUser(long id, long accountId) {
        User user = Mockito.mock(User.class);
        Mockito.when(user.getId()).thenReturn(id);
        Mockito.when(user.getAccountId()).thenReturn(accountId);
        return user;
    }

    private AccountVO mockAccount(long id) {
        AccountVO account = Mockito.mock(AccountVO.class);
        Mockito.when(account.getId()).thenReturn(id);
        return account;
    }

    private ProjectAccountVO mockProjectAccount(long id, Role role) {
        ProjectAccountVO projectAccount = Mockito.mock(ProjectAccountVO.class);
        Mockito.when(projectAccount.getId()).thenReturn(id);
        Mockito.when(projectAccount.getAccountRole()).thenReturn(role);
        return projectAccount;
    }
}
