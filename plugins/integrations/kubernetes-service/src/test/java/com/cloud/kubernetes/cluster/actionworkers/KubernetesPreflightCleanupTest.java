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
import com.cloud.kubernetes.cluster.KubernetesClusterDetailsVO;
import com.cloud.kubernetes.cluster.KubernetesClusterManagerImpl;
import com.cloud.kubernetes.cluster.KubernetesClusterVmMapVO;
import com.cloud.kubernetes.cluster.dao.KubernetesClusterDetailsDao;
import com.cloud.kubernetes.cluster.dao.KubernetesClusterVmMapDao;
import com.cloud.exception.ManagementServerException;
import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.dao.NetworkDao;
import org.junit.Test;
import org.mockito.Mockito;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class KubernetesPreflightCleanupTest {
    private final KubernetesCluster cluster = Mockito.mock(KubernetesCluster.class);
    private KubernetesClusterManagerImpl manager() {
        Mockito.when(cluster.getId()).thenReturn(7L);
        Mockito.when(cluster.getNetworkId()).thenReturn(8L);
        KubernetesClusterManagerImpl manager = new KubernetesClusterManagerImpl();
        manager.kubernetesClusterDetailsDao = Mockito.mock(KubernetesClusterDetailsDao.class);
        manager.kubernetesClusterVmMapDao = Mockito.mock(KubernetesClusterVmMapDao.class);
        return manager;
    }
    private KubernetesClusterDestroyWorker destroyer(KubernetesCluster.State state, String phase) {
        KubernetesClusterManagerImpl manager = manager();
        Mockito.when(cluster.getState()).thenReturn(state);
        if (phase != null) {
            Mockito.when(manager.kubernetesClusterDetailsDao.findDetail(7L, "lifecycle.provisioning.phase"))
                    .thenReturn(new KubernetesClusterDetailsVO(7L, "lifecycle.provisioning.phase", phase, false));
        }
        Mockito.when(manager.kubernetesClusterVmMapDao.listByClusterId(7L)).thenReturn(Collections.emptyList());
        return Mockito.spy(new KubernetesClusterDestroyWorker(cluster, manager));
    }
    @Test public void failedNetworkBooleanCannotBecomeAStartedNetwork() throws Exception {
        KubernetesClusterStartWorker worker = Mockito.spy(new KubernetesClusterStartWorker(cluster, manager()));
        worker.networkDao = Mockito.mock(NetworkDao.class);
        worker.networkMgr = Mockito.mock(NetworkOrchestrationService.class);
        NetworkVO network = Mockito.mock(NetworkVO.class);
        Mockito.when(network.getId()).thenReturn(8L);
        Mockito.when(worker.networkDao.findById(8L)).thenReturn(network);
        try { worker.startKubernetesClusterNetwork(null); fail("false implementation must fail creation"); }
        catch (ManagementServerException expected) {
            assertTrue(expected.getMessage().contains("network implementation did not complete"));
        }
    }
    @Test public void explicitNeverProvisionedFailureDoesNotContactNonexistentNodes() {
        KubernetesClusterDestroyWorker worker = destroyer(KubernetesCluster.State.Error, "Preflight");
        assertTrue(worker.isUnprovisionedFailure());
        worker.prepareNodeRemoval();
        Mockito.verify(worker, Mockito.never()).requireCleanupNetworkAccess();
        Mockito.verify(worker, Mockito.never()).prepareCsiCleanupBeforeNodeRemoval();
        Mockito.verify(worker, Mockito.never()).prepareServiceCleanupBeforeNodeRemoval();
        Mockito.verify(worker.kubernetesClusterDetailsDao).addDetail(7L, "cleanup.nodes.prepared", "v1", false);
    }
    @Test public void removalRetryPreservesTheExplicitPreflightProof() {
        assertTrue(destroyer(KubernetesCluster.State.Destroying, "Preflight").isUnprovisionedFailure());
    }
    @Test public void missingProofOrNodeStageCannotBypassDataCleanup() {
        assertFalse(destroyer(KubernetesCluster.State.Error, null).isUnprovisionedFailure());
        assertFalse(destroyer(KubernetesCluster.State.Error, "Nodes").isUnprovisionedFailure());
        assertFalse(destroyer(KubernetesCluster.State.Running, "Preflight").isUnprovisionedFailure());
    }
    @Test public void anyProvisionedNodeRejectsThePreflightShortcut() {
        KubernetesClusterDestroyWorker worker = destroyer(KubernetesCluster.State.Error, "Preflight");
        Mockito.when(worker.kubernetesClusterVmMapDao.listByClusterId(7L))
                .thenReturn(Collections.singletonList(Mockito.mock(KubernetesClusterVmMapVO.class)));
        assertFalse(worker.isUnprovisionedFailure());
    }
    @Test public void verifiedNodeProvisioningFailureSkipsUnreachableCsiButMissingReceiptDoesNot() {
        KubernetesClusterDestroyWorker worker=destroyer(KubernetesCluster.State.Error,"NodeProvisioningFailed");
        assertFalse(worker.isUnprovisionedFailure());
        Mockito.when(worker.kubernetesClusterDetailsDao.findDetail(7L,"lifecycle.creation.failed.job"))
                .thenReturn(new KubernetesClusterDetailsVO(7L,"lifecycle.creation.failed.job","actual-failed-create-job",false));
        assertTrue(worker.isUnprovisionedFailure());
        worker.prepareNodeRemoval();
        Mockito.verify(worker,Mockito.never()).prepareCsiCleanupBeforeNodeRemoval();
        Mockito.verify(worker,Mockito.never()).prepareServiceCleanupBeforeNodeRemoval();
    }
}
