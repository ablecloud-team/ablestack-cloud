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

import java.util.Arrays;
import java.util.Collections;
import java.lang.reflect.Field;
import com.cloud.kubernetes.cluster.KubernetesCluster;
import com.cloud.kubernetes.cluster.KubernetesClusterDetailsVO;
import com.cloud.kubernetes.cluster.KubernetesClusterManagerImpl;
import com.cloud.kubernetes.cluster.KubernetesClusterVmMapVO;
import com.cloud.kubernetes.cluster.dao.KubernetesClusterDetailsDao;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.dao.UserVmDao;
import org.apache.cloudstack.framework.jobs.AsyncJob;
import org.apache.cloudstack.framework.jobs.dao.AsyncJobDao;
import org.apache.cloudstack.framework.jobs.impl.AsyncJobVO;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class KubernetesFailedBootstrapCleanupTest {
    private KubernetesCluster cluster;
    private KubernetesClusterDestroyWorker worker;
    private AsyncJobVO job;
    private KubernetesClusterVmMapVO node;
    private UserVmVO vm;
    @Before public void setup() throws Exception {
        cluster = Mockito.mock(KubernetesCluster.class);
        Mockito.when(cluster.getId()).thenReturn(3L);
        Mockito.when(cluster.getAccountId()).thenReturn(9L);
        Mockito.when(cluster.getClusterType()).thenReturn(KubernetesCluster.ClusterType.CloudManaged);
        Mockito.when(cluster.getState()).thenReturn(KubernetesCluster.State.Alert);
        worker = Mockito.spy(new KubernetesClusterDestroyWorker(cluster, Mockito.mock(KubernetesClusterManagerImpl.class)));
        worker.kubernetesClusterDetailsDao = Mockito.mock(KubernetesClusterDetailsDao.class);
        KubernetesClusterDetailsVO phase = Mockito.mock(KubernetesClusterDetailsVO.class);
        Mockito.when(phase.getValue()).thenReturn("Bootstrap");
        Mockito.when(worker.kubernetesClusterDetailsDao.findDetail(3L, "lifecycle.provisioning.phase")).thenReturn(phase);
        worker.asyncJobDao = Mockito.mock(AsyncJobDao.class);
        SearchBuilder<AsyncJobVO> builder = Mockito.mock(SearchBuilder.class);
        Mockito.when(builder.entity()).thenReturn(Mockito.mock(AsyncJobVO.class));
        Mockito.when(builder.create()).thenReturn(Mockito.mock(SearchCriteria.class));
        Mockito.when(worker.asyncJobDao.createSearchBuilder()).thenReturn(builder);
        job = Mockito.mock(AsyncJobVO.class);
        Mockito.when(job.getId()).thenReturn(7L);
        Mockito.when(job.getStatus()).thenReturn(AsyncJob.Status.FAILED);
        Mockito.when(job.getUuid()).thenReturn("failed-job");
        Mockito.when(worker.asyncJobDao.searchIncludingRemoved(Mockito.any(), Mockito.isNull(), Mockito.isNull(), Mockito.eq(false))).thenReturn(Collections.singletonList(job));
        node = Mockito.mock(KubernetesClusterVmMapVO.class);
        Mockito.when(node.getVmId()).thenReturn(11L);
        Field maps = KubernetesClusterDestroyWorker.class.getDeclaredField("clusterVMs");
        maps.setAccessible(true); maps.set(worker, Collections.singletonList(node));
        worker.userVmDao = Mockito.mock(UserVmDao.class);
        vm = Mockito.mock(UserVmVO.class);
        Mockito.when(vm.getAccountId()).thenReturn(9L);
        Mockito.when(worker.userVmDao.findById(11L)).thenReturn(vm);
        Mockito.doReturn(Collections.emptyList()).when(worker).recordOwnedNetworkResources(true);
        Mockito.doNothing().when(worker).stopNodesForOfflineOwnedCleanup();
        Mockito.doNothing().when(worker).cleanupOwnedNetworkResources(true);
        Mockito.doNothing().when(worker).cleanupNativeAclResources();
    }
    private void rejectsBeforeStop() throws Exception {
        try { worker.prepareFailedBootstrapCleanup(); fail("unverified bootstrap must be preserved"); }
        catch (CloudRuntimeException expected) {
            Mockito.verify(worker, Mockito.never()).stopNodesForOfflineOwnedCleanup();
            Mockito.verify(worker, Mockito.never()).cleanupOwnedNetworkResources(Mockito.anyBoolean());
        }
    }
    @Test public void failedPreApiBootstrapValidatesThenStopsBeforeCleanup() throws Exception {
        assertTrue(worker.prepareFailedBootstrapCleanup());
        InOrder order = Mockito.inOrder(worker);
        order.verify(worker).recordOwnedNetworkResources(true);
        order.verify(worker).stopNodesForOfflineOwnedCleanup();
        order.verify(worker).cleanupNativeAclResources();
        order.verify(worker).cleanupOwnedNetworkResources(true);
        Mockito.verify(worker.kubernetesClusterDetailsDao).addDetail(3L, "cleanup.bootstrap.failed.job", "failed-job", false);
        Mockito.verify(worker, Mockito.never()).executeServiceCleanup(Mockito.anyString());
    }
    @Test public void initializedApiNeverUsesFailedBootstrapFallback() throws Exception {
        Mockito.when(cluster.getEndpoint()).thenReturn("https://192.0.2.1:6443/");
        assertFalse(worker.prepareFailedBootstrapCleanup());
        Mockito.verifyNoInteractions(worker.asyncJobDao, worker.userVmDao);
    }
    @Test public void runningClusterNeverUsesFailedBootstrapFallback() throws Exception {
        Mockito.when(cluster.getState()).thenReturn(KubernetesCluster.State.Running);
        assertFalse(worker.prepareFailedBootstrapCleanup());
        Mockito.verifyNoInteractions(worker.asyncJobDao);
    }
    @Test public void establishedProviderNeverUsesFailedBootstrapFallback() throws Exception {
        Mockito.doReturn(true).when(worker).ownershipCleanupEnabled();
        assertFalse(worker.prepareFailedBootstrapCleanup());
        Mockito.verifyNoInteractions(worker.asyncJobDao);
    }
    @Test public void externalManagedClusterNeverUsesFallback() throws Exception {
        Mockito.when(cluster.getClusterType()).thenReturn(KubernetesCluster.ClusterType.ExternalManaged);
        assertFalse(worker.prepareFailedBootstrapCleanup());
        Mockito.verifyNoInteractions(worker.asyncJobDao);
    }
    @Test public void activeCreateEvenWithOlderFailureBlocksCleanup() throws Exception {
        AsyncJobVO active = Mockito.mock(AsyncJobVO.class);
        Mockito.when(active.getStatus()).thenReturn(AsyncJob.Status.IN_PROGRESS);
        Mockito.when(active.getId()).thenReturn(6L);
        Mockito.when(worker.asyncJobDao.searchIncludingRemoved(Mockito.any(), Mockito.isNull(), Mockito.isNull(), Mockito.eq(false))).thenReturn(Arrays.asList(job, active));
        rejectsBeforeStop();
    }
    @Test public void successfulCreateCannotAuthorizeFallback() throws Exception {
        Mockito.when(job.getStatus()).thenReturn(AsyncJob.Status.SUCCEEDED); rejectsBeforeStop();
    }
    @Test public void foreignNodeBlocksBeforeEveryVmMutation() throws Exception {
        Mockito.when(vm.getAccountId()).thenReturn(10L); rejectsBeforeStop();
    }
    @Test public void externalNodeBlocksBeforeEveryVmMutation() throws Exception {
        Mockito.when(node.isExternalNode()).thenReturn(true); rejectsBeforeStop();
    }
    @Test public void unknownErrorDiagnosticsNeverExposeCredentialOrUrl() {
        assertEquals("RuntimeException", KubernetesClusterDestroyWorker.cleanupFailureReason(new RuntimeException("https://secret.invalid/?apikey=private")));
        assertEquals("Public IP cleanup allocation changed", KubernetesClusterDestroyWorker.cleanupFailureReason(new CloudRuntimeException("Public IP cleanup allocation changed: 11111111-1111-4111-8111-111111111111")));
    }
}
