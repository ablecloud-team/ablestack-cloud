// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.kubernetes.cluster;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.function.Supplier;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.kubernetes.cluster.dao.KubernetesClusterDao;
import com.cloud.kubernetes.version.KubernetesVersionReferences;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountService;
import org.apache.cloudstack.config.ApiServiceConfiguration;
import com.cloud.user.User;
import com.cloud.user.UserDataVO;
import com.cloud.user.dao.UserDataDao;
import com.cloud.utils.Pair;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import org.apache.cloudstack.acl.SecurityChecker;
import org.apache.cloudstack.api.command.user.kubernetes.cluster.CreateKubernetesClusterCmd;
import org.apache.cloudstack.api.command.user.kubernetes.cluster.ListKubernetesClustersCmd;
import org.apache.cloudstack.api.response.KubernetesClusterResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.framework.config.ConfigKey;
import com.cloud.user.CniConfigurationReferences;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

public class KubernetesCniReferencesTest {
    private KubernetesClusterManagerImpl manager;
    private Account caller;
    private Account owner;
    private UserDataVO cni;
    private Object oldDefault;
    private Field configDefault;

    @Before public void setup() throws Exception {
        manager = spy(new KubernetesClusterManagerImpl());
        manager.userDataDao = mock(UserDataDao.class); manager.accountManager = mock(AccountManager.class);
        manager.kubernetesClusterDao = mock(KubernetesClusterDao.class);
        caller = mock(Account.class); owner = mock(Account.class); cni = mock(UserDataVO.class);
        when(cni.isForCks()).thenReturn(true); when(cni.getAccountId()).thenReturn(11L); when(cni.getDomainId()).thenReturn(22L);
        when(owner.getAccountId()).thenReturn(11L); when(owner.getDomainId()).thenReturn(22L);
        when(manager.userDataDao.findById(17L)).thenReturn(cni);
        CallContext.register(mock(User.class), caller);
        configDefault = ConfigKey.class.getDeclaredField("_defaultValue"); configDefault.setAccessible(true);
        oldDefault = configDefault.get(KubernetesClusterService.KubernetesServiceEnabled); configDefault.set(KubernetesClusterService.KubernetesServiceEnabled, "true");
    }
    @After public void cleanup() throws Exception { CallContext.unregister(); configDefault.set(KubernetesClusterService.KubernetesServiceEnabled, oldDefault); }

    @Test public void optionalCniDoesNotResolveAnything() { manager.validateCniForCluster(null, owner); verifyNoInteractions(manager.userDataDao, manager.accountManager); }
    @Test public void removedCniCannotPublishReference() { when(manager.userDataDao.findById(17L)).thenReturn(null); assertThrows(InvalidParameterValueException.class, () -> manager.validateCniForCluster(17L, owner)); }
    @Test public void ordinaryUserDataCannotBecomeACniReference() { when(cni.isForCks()).thenReturn(false); assertThrows(InvalidParameterValueException.class, () -> manager.validateCniForCluster(17L, owner)); verify(manager.accountManager).checkAccess(caller, SecurityChecker.AccessType.UseEntry, false, cni); }
    @Test public void callerAclRunsBeforeTypeDisclosure() { when(cni.isForCks()).thenReturn(false); doThrow(new PermissionDeniedException("denied")).when(manager.accountManager).checkAccess(caller, SecurityChecker.AccessType.UseEntry, false, cni); assertThrows(PermissionDeniedException.class, () -> manager.validateCniForCluster(17L, owner)); verify(cni, never()).isForCks(); }
    @Test public void differentAccountOrProjectCannotBorrowOwnersCni() { when(owner.getAccountId()).thenReturn(12L); assertThrows(InvalidParameterValueException.class, () -> manager.validateCniForCluster(17L, owner)); }
    @Test public void matchingAccountDomainCanPublishCniReference() { manager.validateCniForCluster(17L, owner); verify(manager.accountManager).checkAccess(caller, SecurityChecker.AccessType.UseEntry, false, cni); }

    @Test public void creationAcquiresCniInsideVersionLockBeforePublishing() {
        CreateKubernetesClusterCmd cmd = mock(CreateKubernetesClusterCmd.class); when(cmd.getCniConfigId()).thenReturn(17L); when(cmd.getKubernetesVersionId()).thenReturn(13L);
        KubernetesCluster cluster = mock(KubernetesCluster.class);
        try (MockedStatic<KubernetesVersionReferences> versions = mockStatic(KubernetesVersionReferences.class); MockedStatic<CniConfigurationReferences> cnis = mockStatic(CniConfigurationReferences.class)) {
            versions.when(() -> KubernetesVersionReferences.withLock(eq(13L), any())).thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
            cnis.when(() -> CniConfigurationReferences.withLock(eq(17L), any())).thenReturn(cluster);
            assertSame(cluster, manager.createManagedKubernetesCluster(cmd));
            cnis.verify(() -> CniConfigurationReferences.withLock(eq(17L), any()));
        }
    }

    @Test public void actualCreationRejectsRemovedCniBeforeInfrastructureSideEffects() {
        manager.accountService = mock(AccountService.class);
        when(manager.accountService.getActiveAccountById(11L)).thenReturn(owner);
        when(manager.userDataDao.findById(17L)).thenReturn(null);
        CreateKubernetesClusterCmd cmd = mock(CreateKubernetesClusterCmd.class);
        when(cmd.getCniConfigId()).thenReturn(17L); when(cmd.getKubernetesVersionId()).thenReturn(13L); when(cmd.getEntityOwnerId()).thenReturn(11L);
        try (MockedStatic<KubernetesVersionReferences> versions = mockStatic(KubernetesVersionReferences.class);
             MockedStatic<CniConfigurationReferences> cnis = mockStatic(CniConfigurationReferences.class);
             MockedStatic<ApiServiceConfiguration> endpoints = mockStatic(ApiServiceConfiguration.class)) {
            versions.when(() -> KubernetesVersionReferences.withLock(eq(13L), any())).thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
            cnis.when(() -> CniConfigurationReferences.withLock(eq(17L), any())).thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
            assertThrows(InvalidParameterValueException.class, () -> manager.createManagedKubernetesCluster(cmd));
            verify(manager.userDataDao).findById(17L);
            verifyNoInteractions(manager.kubernetesClusterDao);
        }
    }

    @SuppressWarnings("unchecked")
    @Test public void usageFilterRetainsClusterAclPaginationAndCountQuery() {
        ListKubernetesClustersCmd cmd = mock(ListKubernetesClustersCmd.class); when(cmd.getCniConfigId()).thenReturn(17L); when(cmd.getStartIndex()).thenReturn(4L); when(cmd.getPageSizeVal()).thenReturn(2L);
        SearchBuilder<KubernetesClusterVO> sb = mock(SearchBuilder.class); SearchCriteria<KubernetesClusterVO> sc = mock(SearchCriteria.class); KubernetesClusterVO row = mock(KubernetesClusterVO.class);
        when(manager.kubernetesClusterDao.createSearchBuilder()).thenReturn(sb); when(sb.entity()).thenReturn(row); when(sb.create()).thenReturn(sc);
        when(manager.kubernetesClusterDao.searchAndCount(eq(sc), any(Filter.class))).thenReturn(new Pair<>(Collections.emptyList(), 5));
        manager.listKubernetesClusters(cmd);
        verify(sc).setParameters("cniId", 17L);
        verify(manager.accountManager).buildACLSearchBuilder(eq(sb), any(), anyBoolean(), any(), any());
        verify(manager.accountManager).buildACLSearchCriteria(eq(sc), any(), anyBoolean(), any(), any());
        ArgumentCaptor<Filter> filter = ArgumentCaptor.forClass(Filter.class); verify(manager.kubernetesClusterDao).searchAndCount(eq(sc), filter.capture());
        assertEquals(Long.valueOf(4), filter.getValue().getOffset()); assertEquals(Long.valueOf(2), filter.getValue().getLimit());
    }
    @Test public void forbiddenCniFilterCannotQueryClusters() {
        ListKubernetesClustersCmd cmd = mock(ListKubernetesClustersCmd.class); when(cmd.getCniConfigId()).thenReturn(17L);
        doThrow(new PermissionDeniedException("denied")).when(manager.accountManager).checkAccess(caller, SecurityChecker.AccessType.UseEntry, false, cni);
        assertThrows(PermissionDeniedException.class, () -> manager.listKubernetesClusters(cmd)); verifyNoInteractions(manager.kubernetesClusterDao);
    }
}
