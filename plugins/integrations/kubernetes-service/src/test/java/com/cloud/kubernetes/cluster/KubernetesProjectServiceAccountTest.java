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
import com.cloud.user.Account;
import com.cloud.user.AccountService;
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
        Mockito.when(user.getId()).thenReturn(12L);
        Mockito.when(manager.accountService.createUserAccount(Mockito.eq(name), Mockito.anyString(),
                Mockito.anyString(), Mockito.anyString(), Mockito.isNull(), Mockito.isNull(), Mockito.eq(name),
                Mockito.eq(Account.Type.NORMAL), Mockito.eq(10L), Mockito.eq(9L), Mockito.isNull(), Mockito.isNull(),
                Mockito.isNull(), Mockito.isNull(), Mockito.eq(User.Source.NATIVE), Mockito.eq(false))).thenReturn(user);
        Account local = Mockito.mock(Account.class);
        Mockito.when(manager.accountService.getAccount(11L)).thenReturn(local);
        assertSame(local, manager.createProjectKubernetesAccount(project, name));
        Mockito.verify(manager.projectManager).assignAccountToProject(project, 11L, ProjectAccount.Role.Regular, 12L, null);
        Mockito.verifyNoMoreInteractions(manager.projectManager);
        assertSame(caller, CallContext.current());
    }

    @Test public void localAccountFailureRestoresCallerWithoutMembership() {
        Mockito.when(manager.accountService.createUserAccount(Mockito.eq(name), Mockito.anyString(),
                Mockito.anyString(), Mockito.anyString(), Mockito.isNull(), Mockito.isNull(), Mockito.eq(name),
                Mockito.eq(Account.Type.NORMAL), Mockito.eq(10L), Mockito.eq(9L), Mockito.isNull(), Mockito.isNull(),
                Mockito.isNull(), Mockito.isNull(), Mockito.eq(User.Source.NATIVE), Mockito.eq(false)))
                .thenThrow(new CloudRuntimeException("Local account creation failed"));
        try { manager.createProjectKubernetesAccount(project, name); fail("local failure must propagate"); }
        catch (CloudRuntimeException expected) { assertSame(caller, CallContext.current()); }
        Mockito.verifyNoInteractions(manager.projectManager);
    }
}
