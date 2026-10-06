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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import com.cloud.kubernetes.cluster.dao.KubernetesClusterDetailsDao;
import com.cloud.kubernetes.cluster.dao.KubernetesClusterVmMapDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountService;
import com.cloud.user.User;
import com.cloud.user.UserAccount;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.acl.ApiKeyPairPermissionVO;
import org.apache.cloudstack.acl.RolePermissionEntity;
import org.apache.cloudstack.acl.apikeypair.ApiKeyPair;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.context.CallContext;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import static org.junit.Assert.fail;

public class KubernetesControllerKeyRotationTest {
    private static final String CID = "11111111-1111-4111-8111-111111111111";
    private KubernetesClusterManagerImpl manager;
    private KubernetesClusterVO cluster;
    private ApiKeyPair oldKey;
    private VMInstanceVO vm;
    @Before public void setup() {
        CallContext.register(Mockito.mock(User.class), Mockito.mock(Account.class));
        manager = Mockito.spy(new KubernetesClusterManagerImpl());
        manager.accountManager = Mockito.mock(AccountManager.class);
        manager.accountService = Mockito.mock(AccountService.class);
        manager.kubernetesClusterDetailsDao = Mockito.mock(KubernetesClusterDetailsDao.class);
        manager.kubernetesClusterVmMapDao = Mockito.mock(KubernetesClusterVmMapDao.class);
        manager.vmInstanceDao = Mockito.mock(VMInstanceDao.class);
        cluster = Mockito.mock(KubernetesClusterVO.class);
        Mockito.when(cluster.getId()).thenReturn(7L);
        Mockito.when(cluster.getUuid()).thenReturn(CID);
        Mockito.when(cluster.getAccountId()).thenReturn(8L);
        Mockito.when(cluster.getClusterType()).thenReturn(KubernetesCluster.ClusterType.CloudManaged);
        Mockito.when(cluster.getState()).thenReturn(KubernetesCluster.State.Stopped);
        Mockito.when(cluster.isCsiEnabled()).thenReturn(true);
        Account owner = Mockito.mock(Account.class);
        Mockito.when(owner.getAccountId()).thenReturn(8L);
        Mockito.when(owner.getDomainId()).thenReturn(9L);
        Mockito.when(owner.getType()).thenReturn(Account.Type.NORMAL);
        Mockito.when(manager.accountService.getAccount(8L)).thenReturn(owner);
        UserAccount user = Mockito.mock(UserAccount.class);
        Mockito.when(user.getAccountId()).thenReturn(8L);
        Mockito.when(user.getId()).thenReturn(10L);
        Mockito.when(manager.accountService.getActiveUserAccount("mold-cks-" + CID, 9L)).thenReturn(user);
        KubernetesClusterDetailsVO detail = Mockito.mock(KubernetesClusterDetailsVO.class);
        Mockito.when(detail.getValue()).thenReturn("11");
        Mockito.when(manager.kubernetesClusterDetailsDao.findDetail(7L, KubernetesRuntimeKeyProfile.KEY_DETAIL)).thenReturn(detail);
        oldKey = Mockito.mock(ApiKeyPair.class);
        Mockito.when(oldKey.getId()).thenReturn(11L);
        Mockito.when(oldKey.getUserId()).thenReturn(10L);
        Mockito.when(oldKey.getAccountId()).thenReturn(8L);
        Mockito.when(oldKey.getDomainId()).thenReturn(9L);
        Mockito.when(oldKey.getName()).thenReturn(KubernetesRuntimeKeyProfile.name(CID, true));
        Mockito.when(manager.accountService.getKeyPairById(11L)).thenReturn(oldKey);
        Mockito.doReturn(permissions(true)).when(manager.accountService).getAllExplicitKeyPairPermissions(11L);
        KubernetesClusterVmMapVO map = Mockito.mock(KubernetesClusterVmMapVO.class);
        Mockito.when(map.getVmId()).thenReturn(12L);
        Mockito.when(manager.kubernetesClusterVmMapDao.listByClusterId(7L)).thenReturn(Collections.singletonList(map));
        vm = Mockito.mock(VMInstanceVO.class);
        Mockito.when(vm.getState()).thenReturn(VirtualMachine.State.Stopped);
        Mockito.when(manager.vmInstanceDao.findById(12L)).thenReturn(vm);
    }
    @After public void finish() { CallContext.unregister(); }
    private List<ApiKeyPairPermissionVO> permissions(boolean csi) {
        List<ApiKeyPairPermissionVO> result = new ArrayList<>();
        for (Map<String, Object> r : KubernetesRuntimeKeyProfile.request(10L, CID, csi).getRules()) {
            ApiKeyPairPermissionVO p = new ApiKeyPairPermissionVO(11L, r.get(ApiConstants.RULE).toString(),
                    (RolePermissionEntity.Permission) r.get(ApiConstants.PERMISSION), "fixture");
            p.setSortOrder(result.size()); result.add(p);
        }
        return result;
    }
    private void rejects() {
        try { manager.rotateStoppedClusterControllerKey(cluster); fail("rotation must fail before credential mutation"); }
        catch (CloudRuntimeException expected) {
            Mockito.verify(manager, Mockito.never()).createClusterServiceKey(Mockito.anyLong(), Mockito.any());
            Mockito.verify(manager.accountService, Mockito.never()).deleteApiKey(Mockito.any(ApiKeyPair.class));
            Mockito.verify(manager.kubernetesClusterDetailsDao, Mockito.never()).addDetail(Mockito.anyLong(), Mockito.anyString(), Mockito.anyString(), Mockito.anyBoolean());
        }
    }
    @Test public void runningClusterRejected() { Mockito.when(cluster.getState()).thenReturn(KubernetesCluster.State.Running); rejects(); }
    @Test public void externallyManagedClusterRejected() { Mockito.when(cluster.getClusterType()).thenReturn(KubernetesCluster.ClusterType.ExternalManaged); rejects(); }
    @Test public void stoppedStateWithRunningVmRejected() { Mockito.when(vm.getState()).thenReturn(VirtualMachine.State.Running); rejects(); }
    @Test public void legacySharedKeyRejected() { Mockito.when(manager.kubernetesClusterDetailsDao.findDetail(7L, KubernetesRuntimeKeyProfile.KEY_DETAIL)).thenReturn(null); rejects(); }
    @Test public void differentOwnerRejected() { Mockito.when(oldKey.getAccountId()).thenReturn(99L); rejects(); }
    @Test public void differentProfileRejected() { Mockito.when(oldKey.getName()).thenReturn(KubernetesRuntimeKeyProfile.name(CID, false)); rejects(); }
    @Test public void reducedPermissionsAreNotWidened() { Mockito.doReturn(permissions(false)).when(manager.accountService).getAllExplicitKeyPairPermissions(11L); rejects(); }
    @Test public void unavailableKeyRejected() { Mockito.when(manager.accountService.getKeyPairById(11L)).thenReturn(null); rejects(); }
    private MockedStatic<Transaction> transaction() {
        MockedStatic<Transaction> tx = Mockito.mockStatic(Transaction.class);
        tx.when(() -> Transaction.execute(Mockito.any(TransactionCallback.class))).thenAnswer(i -> ((TransactionCallback<?>) i.getArgument(0)).doInTransaction(null));
        return tx;
    }
    @Test public void replacementCreationFailurePreservesOldKeyAndPointer() {
        Mockito.doThrow(new CloudRuntimeException("key creation failed")).when(manager).createClusterServiceKey(10L, cluster);
        try (MockedStatic<Transaction> tx = transaction()) {
            try { manager.rotateStoppedClusterControllerKey(cluster); fail("creation failure must propagate"); }
            catch (CloudRuntimeException expected) {
                Mockito.verify(manager.accountService, Mockito.never()).deleteApiKey(Mockito.any(ApiKeyPair.class));
                Mockito.verify(manager.kubernetesClusterDetailsDao, Mockito.never()).addDetail(Mockito.anyLong(), Mockito.anyString(), Mockito.anyString(), Mockito.anyBoolean());
            }
        }
    }
    @Test public void scopedReplacementPointerAndRevocationShareOneTransaction() {
        ApiKeyPair replacement = Mockito.mock(ApiKeyPair.class);
        Mockito.when(replacement.getId()).thenReturn(13L);
        Mockito.doReturn(replacement).when(manager).createClusterServiceKey(10L, cluster);
        try (MockedStatic<Transaction> tx = transaction()) {
            manager.rotateStoppedClusterControllerKey(cluster);
            InOrder order = Mockito.inOrder(manager, manager.kubernetesClusterDetailsDao, manager.accountService);
            order.verify(manager).createClusterServiceKey(10L, cluster);
            order.verify(manager.kubernetesClusterDetailsDao).addDetail(7L, KubernetesRuntimeKeyProfile.KEY_DETAIL, "13", false);
            order.verify(manager.accountService).deleteApiKey(oldKey);
        }
    }
}
