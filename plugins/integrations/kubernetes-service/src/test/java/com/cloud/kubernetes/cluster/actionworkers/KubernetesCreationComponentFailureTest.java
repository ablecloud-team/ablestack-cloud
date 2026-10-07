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

import com.cloud.kubernetes.cluster.KubernetesCluster;
import com.cloud.kubernetes.cluster.KubernetesClusterManagerImpl;
import com.cloud.utils.exception.CloudRuntimeException;
import org.junit.Test;
import org.mockito.Mockito;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class KubernetesCreationComponentFailureTest {
    private KubernetesClusterStartWorker worker() {
        KubernetesCluster c = Mockito.mock(KubernetesCluster.class);
        Mockito.when(c.getId()).thenReturn(8L);
        KubernetesClusterStartWorker w = Mockito.spy(new KubernetesClusterStartWorker(c, Mockito.mock(KubernetesClusterManagerImpl.class)));
        Mockito.doReturn(true).when(w).stateTransitTo(Mockito.eq(8L), Mockito.any());
        return w;
    }
    @Test public void providerExceptionRecordsFailureAndKeepsRawDiagnosticOutOfApiMessage() {
        KubernetesClusterStartWorker w = worker();
        try { w.initializeCreationComponent("Provider", () -> { throw new CloudRuntimeException("url?secret=private"); }); fail("provider failure must propagate"); }
        catch (CloudRuntimeException expected) { assertEquals("Failed to initialize Kubernetes Provider; nodes and cleanup receipts are preserved", expected.getMessage()); }
        Mockito.verify(w).stateTransitTo(8L, KubernetesCluster.Event.CreateFailed);
    }
    @Test public void csiExceptionRecordsFailedCreationBeforeReturn() {
        KubernetesClusterStartWorker w = worker();
        try { w.initializeCreationComponent("CSI", () -> { throw new CloudRuntimeException("rollout failed"); }); fail("CSI failure must propagate"); }
        catch (CloudRuntimeException expected) { }
        Mockito.verify(w).stateTransitTo(8L, KubernetesCluster.Event.CreateFailed);
    }
    @Test public void successfulOrFalseComponentReturnKeepsExistingCallerStateHandling() {
        KubernetesClusterStartWorker w = worker();
        assertTrue(w.initializeCreationComponent("Provider", () -> true));
        assertFalse(w.initializeCreationComponent("CSI", () -> false));
        Mockito.verify(w, Mockito.never()).stateTransitTo(Mockito.anyLong(), Mockito.any());
    }
    private void storedState(KubernetesClusterStartWorker w, KubernetesCluster.State state) {
        w.kubernetesClusterDao = Mockito.mock(com.cloud.kubernetes.cluster.dao.KubernetesClusterDao.class);
        com.cloud.kubernetes.cluster.KubernetesClusterVO current = Mockito.mock(com.cloud.kubernetes.cluster.KubernetesClusterVO.class);
        Mockito.when(current.getState()).thenReturn(state);
        Mockito.when(w.kubernetesClusterDao.findById(8L)).thenReturn(current);
    }
    @Test public void unexpectedAllocationFailureUsesPersistedStartingState() {
        KubernetesClusterStartWorker w = worker(); storedState(w, KubernetesCluster.State.Starting);
        w.recordCreationOperationFailure();
        Mockito.verify(w).stateTransitTo(8L, KubernetesCluster.Event.CreateFailed);
    }
    @Test public void earlierFailureOrSuccessfulStateIsNeverOverwritten() {
        KubernetesClusterStartWorker w = worker(); storedState(w, KubernetesCluster.State.Error);
        w.recordCreationOperationFailure(); storedState(w, KubernetesCluster.State.Running);
        w.recordCreationOperationFailure();
        Mockito.verify(w, Mockito.never()).stateTransitTo(Mockito.anyLong(), Mockito.any());
    }
    @Test public void preInitFailureWithoutAnyNodesRecordsPreflightAndFailure() {
        KubernetesClusterStartWorker w = worker(); storedState(w, KubernetesCluster.State.Created);
        w.kubernetesClusterVmMapDao = Mockito.mock(com.cloud.kubernetes.cluster.dao.KubernetesClusterVmMapDao.class);
        w.kubernetesClusterDetailsDao = Mockito.mock(com.cloud.kubernetes.cluster.dao.KubernetesClusterDetailsDao.class);
        w.recordCreationOperationFailure();
        Mockito.verify(w.kubernetesClusterDetailsDao).addDetail(8L, "lifecycle.provisioning.phase", "Preflight", false);
        Mockito.verify(w).stateTransitTo(8L, KubernetesCluster.Event.StartRequested);
        Mockito.verify(w).stateTransitTo(8L, KubernetesCluster.Event.CreateFailed);
    }
}
