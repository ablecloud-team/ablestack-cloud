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

package com.cloud.kubernetes.cluster.actionworkers;

import java.util.Collections;
import com.cloud.kubernetes.cluster.KubernetesCluster;
import com.cloud.kubernetes.cluster.KubernetesClusterManagerImpl;
import com.cloud.kubernetes.cluster.dao.KubernetesClusterDetailsDao;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.vpc.NetworkACLItemVO;
import com.cloud.network.vpc.NetworkACLService;
import com.cloud.network.vpc.NetworkACLItemDao;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.UserVmService;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import static org.junit.Assert.fail;

public class KubernetesServiceCleanupGateTest {
    private static final String NETWORK = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    private static final String ACL = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
    private KubernetesClusterDestroyWorker worker() {
        KubernetesCluster cluster = Mockito.mock(KubernetesCluster.class);
        Mockito.when(cluster.getId()).thenReturn(7L);
        Mockito.when(cluster.getNetworkId()).thenReturn(8L);
        KubernetesClusterManagerImpl manager = new KubernetesClusterManagerImpl();
        manager.kubernetesClusterDetailsDao = Mockito.mock(KubernetesClusterDetailsDao.class);
        KubernetesClusterDestroyWorker w = Mockito.spy(new KubernetesClusterDestroyWorker(cluster, manager));
        w.userVmService = Mockito.mock(UserVmService.class);
        w.networkDao = Mockito.mock(NetworkDao.class);
        w.networkACLItemDao = Mockito.mock(NetworkACLItemDao.class);
        w.networkACLService = Mockito.mock(NetworkACLService.class);
        NetworkVO network = Mockito.mock(NetworkVO.class);
        Mockito.when(network.getUuid()).thenReturn(NETWORK);
        Mockito.when(network.getNetworkACLId()).thenReturn(9L);
        Mockito.when(w.networkDao.findById(8L)).thenReturn(network);
        Mockito.doReturn(network).when(w).requireCleanupNetworkAccess();
        Mockito.when(w.kubernetesClusterDetailsDao.listDetailsKeyPairs(7L)).thenReturn(
            Collections.singletonMap("cleanup.native.acl." + ACL, "10|" + ACL + "|" + NETWORK));
        return w;
    }
    @Test
    public void nativeAclReceiptDeletesOnlyItsExactUuidAndList() throws Exception {
        KubernetesClusterDestroyWorker w = worker();
        NetworkACLItemVO acl = Mockito.mock(NetworkACLItemVO.class);
        Mockito.when(acl.getId()).thenReturn(10L);
        Mockito.when(acl.getUuid()).thenReturn(ACL);
        Mockito.when(acl.getAclId()).thenReturn(9L);
        Mockito.when(w.networkACLItemDao.findByUuid(ACL)).thenReturn(acl);
        Mockito.when(w.networkACLService.revokeNetworkACLItem(10L)).thenReturn(true);
        w.cleanupNativeAclResources();
        Mockito.verify(w.networkACLService).revokeNetworkACLItem(10L);
        Mockito.when(acl.getAclId()).thenReturn(11L);
        Mockito.clearInvocations(w.networkACLService);
        try { w.cleanupNativeAclResources(); fail("foreign ACL list must be preserved"); }
        catch (CloudRuntimeException expected) { Mockito.verifyNoInteractions(w.networkACLService); }
    }
    @Test
    public void alreadyDeletedNativeAclIsAnIdempotentRetry() throws Exception {
        KubernetesClusterDestroyWorker w = worker();
        w.cleanupNativeAclResources();
        Mockito.verifyNoInteractions(w.networkACLService);
    }
    @Test
    public void legacyControllerFailureBlocksBeforeEveryVmMutation() {
        KubernetesClusterDestroyWorker w = worker();
        Mockito.doReturn(false).when(w).executeServiceCleanup("request");
        Mockito.doReturn(false).when(w).ownershipCleanupEnabled();
        try { w.prepareServiceCleanupBeforeNodeRemoval(); fail("missing legacy ownership must block deletion"); }
        catch (CloudRuntimeException expected) {
            Mockito.verify(w.kubernetesClusterDetailsDao).addDetail(7L, "cleanup.status", "Blocked", true);
            Mockito.verifyNoInteractions(w.userVmService, w.networkACLService);
        }
    }
    @Test
    public void offlineOwnedCleanupStopsControllersBeforeRuleCleanup() throws Exception {
        KubernetesClusterDestroyWorker w = worker();
        Mockito.doReturn(false).when(w).executeServiceCleanup("request");
        Mockito.doReturn(true).when(w).ownershipCleanupEnabled();
        Mockito.doReturn(Collections.emptyList()).when(w).recordOwnedNetworkResources(false);
        Mockito.doNothing().when(w).stopNodesForOfflineOwnedCleanup();
        Mockito.doNothing().when(w).cleanupOwnedNetworkResources(Mockito.anyBoolean());
        Mockito.doNothing().when(w).cleanupNativeAclResources();
        w.prepareServiceCleanupBeforeNodeRemoval();
        InOrder order = Mockito.inOrder(w);
        order.verify(w).recordOwnedNetworkResources(false);
        order.verify(w).stopNodesForOfflineOwnedCleanup();
        order.verify(w).cleanupOwnedNetworkResources(false);
        Mockito.verify(w, Mockito.never()).executeServiceCleanup("finalize");
    }
    @Test
    public void onlineFinalizerRemovalOccursAfterMoldCleanupAndFailurePreservesNodes() throws Exception {
        KubernetesClusterDestroyWorker w = worker();
        Mockito.doReturn(true).when(w).executeServiceCleanup("request");
        Mockito.doReturn(false).when(w).executeServiceCleanup("finalize");
        Mockito.doReturn(true).when(w).ownershipCleanupEnabled();
        Mockito.doReturn(Collections.emptyList()).when(w).recordOwnedNetworkResources(false);
        Mockito.doNothing().when(w).cleanupOwnedNetworkResources(false);
        try { w.prepareServiceCleanupBeforeNodeRemoval(); fail("finalization failure must preserve nodes"); }
        catch (CloudRuntimeException expected) {
            InOrder order = Mockito.inOrder(w);
            order.verify(w).cleanupOwnedNetworkResources(false);
            order.verify(w).executeServiceCleanup("finalize");
            Mockito.verify(w, Mockito.never()).cleanupOwnedNetworkResources(true);
            Mockito.verifyNoInteractions(w.userVmService, w.networkACLService);
        }
    }
    @Test
    public void revokedAccessBlocksBeforeKubernetesDeletionOrControllerStop() {
        KubernetesClusterDestroyWorker w = worker();
        Mockito.doThrow(new CloudRuntimeException("access revoked")).when(w).requireCleanupNetworkAccess();
        try { w.prepareServiceCleanupBeforeNodeRemoval(); fail("revoked access must block cleanup"); }
        catch (CloudRuntimeException expected) {
            Mockito.verify(w, Mockito.never()).executeServiceCleanup(Mockito.anyString());
            Mockito.verifyNoInteractions(w.userVmService, w.networkACLService);
        }
    }

}
