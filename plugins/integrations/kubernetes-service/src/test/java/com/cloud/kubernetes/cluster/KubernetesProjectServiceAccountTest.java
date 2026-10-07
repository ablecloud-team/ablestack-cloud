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
package com.cloud.kubernetes.cluster;

import com.cloud.projects.Project;
import com.cloud.projects.ProjectAccount;
import com.cloud.projects.ProjectManager;
import com.cloud.projects.ProjectAccountVO;
import com.cloud.projects.dao.ProjectAccountDao;
import java.util.Collections;
import com.cloud.user.Account;
import com.cloud.user.AccountService;
import com.cloud.user.AccountManager;
import com.cloud.user.User;
import com.cloud.user.UserAccount;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.db.EntityManager;
import java.lang.reflect.Field;
import org.apache.cloudstack.acl.Role;
import org.apache.cloudstack.context.CallContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

public class KubernetesProjectServiceAccountTest {
    private KubernetesClusterManagerImpl manager;
    private Project project;
    private CallContext caller;
    private EntityManager previousEntityManager;
    private final String name = "kubeadmin-project-fixture";

    @Before public void setup() throws Exception {
        Field field = CallContext.class.getDeclaredField("s_entityMgr");
        field.setAccessible(true);
        previousEntityManager = (EntityManager) field.get(null);
        EntityManager entities = Mockito.mock(EntityManager.class);
        Mockito.when(entities.findById(User.class, User.UID_SYSTEM)).thenReturn(Mockito.mock(User.class));
        Mockito.when(entities.findById(Account.class, Account.ACCOUNT_ID_SYSTEM)).thenReturn(Mockito.mock(Account.class));
        CallContext.init(entities);
        caller = CallContext.register(Mockito.mock(User.class), Mockito.mock(Account.class));
        manager = Mockito.spy(new KubernetesClusterManagerImpl());
        manager.accountService = Mockito.mock(AccountService.class);
        manager.projectManager = Mockito.mock(ProjectManager.class);
        manager.projectAccountDao = Mockito.mock(ProjectAccountDao.class);
        project = Mockito.mock(Project.class);
        Mockito.when(project.getDomainId()).thenReturn(9L);
        Role role = Mockito.mock(Role.class);
        Mockito.when(role.getId()).thenReturn(10L);
        Mockito.doReturn(role).when(manager).getProjectKubernetesAccountRole();
        // Model an unavailable external user system: requesting external provisioning must fail.
        Mockito.when(manager.accountService.createUserAccount(Mockito.anyString(), Mockito.anyString(),
                Mockito.anyString(), Mockito.anyString(), Mockito.isNull(), Mockito.isNull(), Mockito.anyString(),
                Mockito.eq(Account.Type.NORMAL), Mockito.eq(10L), Mockito.eq(9L), Mockito.isNull(), Mockito.isNull(),
                Mockito.isNull(), Mockito.isNull(), Mockito.eq(User.Source.NATIVE), Mockito.eq(true)))
                .thenThrow(new CloudRuntimeException("External user service unavailable"));
    }
    @After public void finish() { CallContext.unregister(); CallContext.init(previousEntityManager); }

    @Test public void localMachineAccountBindsOnlyToRequestedProject() {
        UserAccount user = Mockito.mock(UserAccount.class);
        Mockito.when(user.getAccountId()).thenReturn(11L);
        Mockito.when(manager.accountService.createUserAccount(Mockito.startsWith("mold-cks-project-"), Mockito.anyString(),
                Mockito.anyString(), Mockito.anyString(), Mockito.isNull(), Mockito.isNull(), Mockito.eq(name),
                Mockito.eq(Account.Type.NORMAL), Mockito.eq(10L), Mockito.eq(9L), Mockito.isNull(), Mockito.isNull(),
                Mockito.isNull(), Mockito.isNull(), Mockito.eq(User.Source.NATIVE), Mockito.eq(false))).thenReturn(user);
        Account local = Mockito.mock(Account.class);
        Mockito.when(manager.accountService.getAccount(11L)).thenReturn(local);
        assertSame(local, manager.createProjectKubernetesAccount(project, name));
        Mockito.verify(manager.projectManager).assignAccountToProject(project, 11L, ProjectAccount.Role.Regular, null, null);
        Mockito.verifyNoMoreInteractions(manager.projectManager);
        assertSame(caller, CallContext.current());
    }

    private Account existingAccount(Long userId) {
        Account account = Mockito.mock(Account.class);
        Mockito.when(account.getId()).thenReturn(11L);
        Mockito.when(account.getDomainId()).thenReturn(9L);
        Mockito.when(account.getRoleId()).thenReturn(10L);
        Mockito.when(account.getType()).thenReturn(Account.Type.NORMAL);
        Mockito.when(project.getId()).thenReturn(7L);
        ProjectAccountVO member = Mockito.mock(ProjectAccountVO.class);
        Mockito.when(member.getAccountRole()).thenReturn(ProjectAccount.Role.Regular);
        Mockito.when(member.getUserId()).thenReturn(userId);
        Mockito.when(manager.projectAccountDao.listBy(7L, 11L, null)).thenReturn(Collections.singletonList(member));
        return account;
    }
    @Test public void existingBootstrapUserMembershipAllowsDedicatedAccountRuntimeUsers() {
        Account account = existingAccount(12L);
        manager.ensureProjectKubernetesAccountMembership(project, account);
        Mockito.verify(manager.projectManager).assignAccountToProject(project, 11L, ProjectAccount.Role.Regular, null, null);
        Mockito.verifyNoInteractions(manager.accountService);
        assertSame(caller, CallContext.current());
    }
    @Test public void existingAccountLevelMembershipIsIdempotent() {
        Account account = existingAccount(null);
        manager.ensureProjectKubernetesAccountMembership(project, account);
        Mockito.verifyNoInteractions(manager.projectManager);
        assertSame(caller, CallContext.current());
    }
    @Test public void sameNameWithoutVerifiedProjectMembershipCannotGainAccess() {
        Account account = existingAccount(12L);
        Mockito.when(manager.projectAccountDao.listBy(7L, 11L, null)).thenReturn(Collections.emptyList());
        try { manager.ensureProjectKubernetesAccountMembership(project, account); fail("membership must be verified"); }
        catch (CloudRuntimeException expected) { assertSame(caller, CallContext.current()); }
        Mockito.verifyNoInteractions(manager.projectManager);
    }
    @Test public void wrongDomainCannotGainProjectMembership() {
        Account account = existingAccount(12L);
        Mockito.when(account.getDomainId()).thenReturn(8L);
        try { manager.ensureProjectKubernetesAccountMembership(project, account); fail("domain must match"); }
        catch (CloudRuntimeException expected) { assertSame(caller, CallContext.current()); }
        Mockito.verifyNoInteractions(manager.projectManager);
    }

    @Test public void lastClusterDeletionRetainsProjectMachineIdentityWithoutExternalIamCalls() {
        manager.accountManager = Mockito.mock(AccountManager.class);
        manager.kubernetesClusterDao = Mockito.mock(com.cloud.kubernetes.cluster.dao.KubernetesClusterDao.class);
        KubernetesCluster cluster = Mockito.mock(KubernetesCluster.class);
        Mockito.when(cluster.getAccountId()).thenReturn(22L);
        Account projectOwner = Mockito.mock(Account.class);
        Mockito.when(projectOwner.getType()).thenReturn(Account.Type.PROJECT);
        Mockito.when(projectOwner.getAccountId()).thenReturn(22L);
        Mockito.when(manager.accountService.getAccount(22L)).thenReturn(projectOwner);
        Mockito.when(manager.kubernetesClusterDao.countNotForGCByAccount(22L)).thenReturn(0);
        manager.deleteProjectKubernetesAccountIfNeeded(cluster);
        Mockito.verifyNoInteractions(manager.accountManager, manager.accountService, manager.projectManager);
        assertSame(caller, CallContext.current());
    }

    @Test public void localAccountFailureRestoresCallerWithoutMembership() {
        Mockito.when(manager.accountService.createUserAccount(Mockito.startsWith("mold-cks-project-"), Mockito.anyString(),
                Mockito.anyString(), Mockito.anyString(), Mockito.isNull(), Mockito.isNull(), Mockito.eq(name),
                Mockito.eq(Account.Type.NORMAL), Mockito.eq(10L), Mockito.eq(9L), Mockito.isNull(), Mockito.isNull(),
                Mockito.isNull(), Mockito.isNull(), Mockito.eq(User.Source.NATIVE), Mockito.eq(false)))
                .thenThrow(new CloudRuntimeException("Local account creation failed"));
        try { manager.createProjectKubernetesAccount(project, name); fail("local failure must propagate"); }
        catch (CloudRuntimeException expected) { assertSame(caller, CallContext.current()); }
        Mockito.verifyNoInteractions(manager.projectManager);
    }
}
