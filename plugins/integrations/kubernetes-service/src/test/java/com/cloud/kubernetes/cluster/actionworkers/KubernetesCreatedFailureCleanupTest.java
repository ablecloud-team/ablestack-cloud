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
import com.cloud.kubernetes.cluster.KubernetesCluster;
import com.cloud.kubernetes.cluster.KubernetesClusterVO;
import com.cloud.kubernetes.cluster.KubernetesClusterManagerImpl;
import com.cloud.kubernetes.cluster.KubernetesClusterVmMapVO;
import com.cloud.kubernetes.cluster.dao.KubernetesClusterDao;
import com.cloud.kubernetes.cluster.dao.KubernetesClusterDetailsDao;
import com.cloud.kubernetes.cluster.dao.KubernetesClusterVmMapDao;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.VMInstanceVO;
import org.apache.cloudstack.framework.jobs.AsyncJob;
import org.apache.cloudstack.framework.jobs.dao.AsyncJobDao;
import org.apache.cloudstack.framework.jobs.impl.AsyncJobVO;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

public class KubernetesCreatedFailureCleanupTest {
    private KubernetesCluster cluster;
    private KubernetesClusterManagerImpl manager;
    private KubernetesClusterDestroyWorker worker;
    private KubernetesClusterVmMapDao maps;
    private KubernetesClusterDetailsDao details;
    private AsyncJobVO failed;
    @Before public void setup() {
        cluster = Mockito.mock(KubernetesCluster.class);
        Mockito.when(cluster.getId()).thenReturn(3L);
        Mockito.when(cluster.getState()).thenReturn(KubernetesCluster.State.Created);
        Mockito.when(cluster.getName()).thenReturn("test-cluster");
        Mockito.when(cluster.getNetworkId()).thenReturn(7L);
        Mockito.when(cluster.getEtcdNodeCount()).thenReturn(0L);
        manager = Mockito.mock(KubernetesClusterManagerImpl.class);
        worker = Mockito.spy(new KubernetesClusterDestroyWorker(cluster, manager));
        maps = Mockito.mock(KubernetesClusterVmMapDao.class);
        details = Mockito.mock(KubernetesClusterDetailsDao.class);
        worker.kubernetesClusterVmMapDao = maps;
        worker.kubernetesClusterDetailsDao = details;
        worker.kubernetesClusterDao = Mockito.mock(KubernetesClusterDao.class);
        worker.vmInstanceDao = Mockito.mock(VMInstanceDao.class);
        worker.asyncJobDao = Mockito.mock(AsyncJobDao.class);
        SearchBuilder<AsyncJobVO> builder = Mockito.mock(SearchBuilder.class);
        Mockito.when(builder.entity()).thenReturn(Mockito.mock(AsyncJobVO.class));
        Mockito.when(builder.create()).thenReturn(Mockito.mock(SearchCriteria.class));
        Mockito.when(worker.asyncJobDao.createSearchBuilder()).thenReturn(builder);
        failed = Mockito.mock(AsyncJobVO.class);
        Mockito.when(failed.getId()).thenReturn(5L);
        Mockito.when(failed.getUuid()).thenReturn("failed-create-job");
        Mockito.when(failed.getStatus()).thenReturn(AsyncJob.Status.FAILED);
        Mockito.when(worker.asyncJobDao.searchIncludingRemoved(Mockito.any(), Mockito.isNull(), Mockito.isNull(), Mockito.eq(false))).thenReturn(Collections.singletonList(failed));
        Mockito.doReturn(true).when(worker).stateTransitTo(Mockito.eq(3L), Mockito.any());
    }
    @Test public void softRemovedFailedCreationWithNoNodesReconcilesUsingRealJobReceipt() {
        KubernetesClusterVO reconciled = Mockito.mock(KubernetesClusterVO.class);
        Mockito.when(reconciled.getState()).thenReturn(KubernetesCluster.State.Error);
        Mockito.when(worker.kubernetesClusterDao.findById(3L)).thenReturn(reconciled);
        assertTrue(worker.reconcileFailedCreationBeforeDelete());
        Mockito.verify(worker.asyncJobDao).searchIncludingRemoved(Mockito.any(), Mockito.isNull(), Mockito.isNull(), Mockito.eq(false));
        Mockito.verify(details).addDetail(3L, "lifecycle.creation.failed.job", "failed-create-job", false);
        Mockito.verify(details).addDetail(3L, "lifecycle.provisioning.phase", "Preflight", false);
        Mockito.verify(worker).stateTransitTo(3L, KubernetesCluster.Event.StartRequested);
        Mockito.verify(worker).stateTransitTo(3L, KubernetesCluster.Event.CreateFailed);
    }
    private void rejectsWithoutCleanupMarker() {
        try { worker.reconcileFailedCreationBeforeDelete(); fail("unverified creation must be preserved"); }
        catch (CloudRuntimeException expected) {
            Mockito.verify(details, Mockito.never()).addDetail(Mockito.anyLong(), Mockito.anyString(), Mockito.anyString(), Mockito.anyBoolean());
            Mockito.verify(worker, Mockito.never()).stateTransitTo(Mockito.anyLong(), Mockito.any());
        }
    }
    @Test public void activeCreationEvenWithOlderFailureIsPreserved() {
        AsyncJobVO pending = Mockito.mock(AsyncJobVO.class);
        Mockito.when(pending.getId()).thenReturn(4L);
        Mockito.when(pending.getStatus()).thenReturn(AsyncJob.Status.IN_PROGRESS);
        Mockito.when(worker.asyncJobDao.searchIncludingRemoved(Mockito.any(), Mockito.isNull(), Mockito.isNull(), Mockito.eq(false))).thenReturn(Arrays.asList(failed, pending));
        rejectsWithoutCleanupMarker();
    }
    @Test public void noCreationEvidenceIsPreserved() {
        Mockito.when(worker.asyncJobDao.searchIncludingRemoved(Mockito.any(), Mockito.isNull(), Mockito.isNull(), Mockito.eq(false))).thenReturn(Collections.emptyList());
        rejectsWithoutCleanupMarker();
    }
    @Test public void successfulCreationIsNotReclassifiedAsFailed() {
        Mockito.when(failed.getStatus()).thenReturn(AsyncJob.Status.SUCCEEDED); rejectsWithoutCleanupMarker();
    }
    @Test public void provisionedNodesAreNeverReclassifiedAsPreflight() {
        Mockito.when(maps.listByClusterId(3L)).thenReturn(Collections.singletonList(Mockito.mock(KubernetesClusterVmMapVO.class)));
        assertFalse(worker.reconcileFailedCreationBeforeDelete());
        Mockito.verifyNoInteractions(worker.asyncJobDao);
        Mockito.verifyNoInteractions(details);
    }
    private String serializedFailure(String message) {
        org.apache.cloudstack.api.response.ExceptionResponse response = new org.apache.cloudstack.api.response.ExceptionResponse();
        response.setErrorText(message);
        return org.apache.cloudstack.framework.jobs.impl.JobSerializerHelper.toSerializedString(response);
    }
    @Test public void legacyFirstControlFailureWithoutAnyNodesCanBeCleaned() {
        Mockito.when(cluster.getState()).thenReturn(KubernetesCluster.State.Error);
        Mockito.when(failed.getResult()).thenReturn(serializedFailure("Provisioning the control VM failed in the Kubernetes cluster : test-cluster"));
        assertTrue(worker.reconcileFailedCreationBeforeDelete());
        Mockito.verify(worker, Mockito.never()).stateTransitTo(Mockito.anyLong(), Mockito.any());
        Mockito.verify(details).addDetail(3L, "lifecycle.creation.failed.job", "failed-create-job", false);
    }
    @Test public void laterFailureIsNotTreatedAsUnprovisioned() {
        Mockito.when(cluster.getState()).thenReturn(KubernetesCluster.State.Error);
        Mockito.when(failed.getResult()).thenReturn(serializedFailure("CSI deployment failed"));
        assertFalse(worker.reconcileFailedCreationBeforeDelete());
        Mockito.verifyNoInteractions(details);
    }
    @Test public void untrackedExistingNodeVmBlocksUnprovisionedCleanup() {
        Mockito.when(worker.vmInstanceDao.listNonRemovedVmsByTypeAndNetwork(Mockito.eq(7L), Mockito.any()))
                .thenReturn(Collections.singletonList(Mockito.mock(VMInstanceVO.class)));
        rejectsWithoutCleanupMarker();
    }
    @Test public void realCredentialPreparationFailureUsesNormalCreationFailureStates() {
        KubernetesClusterStartWorker start = Mockito.spy(new KubernetesClusterStartWorker(cluster, manager));
        start.kubernetesClusterVmMapDao = maps; start.kubernetesClusterDetailsDao = details;
        Mockito.doReturn(true).when(start).stateTransitTo(Mockito.eq(3L), Mockito.any());
        start.recordCreationCredentialFailure();
        Mockito.verify(details).addDetail(3L, "lifecycle.provisioning.phase", "Preflight", false);
        Mockito.verify(start).stateTransitTo(3L, KubernetesCluster.Event.StartRequested);
        Mockito.verify(start).stateTransitTo(3L, KubernetesCluster.Event.CreateFailed);
        Mockito.verify(maps, Mockito.never()).removeByClusterId(Mockito.anyLong());
    }
}
