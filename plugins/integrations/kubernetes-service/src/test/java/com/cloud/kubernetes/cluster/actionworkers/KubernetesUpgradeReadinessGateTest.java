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

import java.util.List;
import java.util.Collections;

import com.cloud.kubernetes.cluster.KubernetesCluster;
import com.cloud.kubernetes.cluster.KubernetesClusterManagerImpl;
import com.cloud.uservm.UserVm;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.logging.log4j.Level;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class KubernetesUpgradeReadinessGateTest {
    private static class Worker extends KubernetesClusterUpgradeWorker {
        private Pair<Boolean, String> response = new Pair<>(false, "");
        private KubernetesCluster.Event failureEvent;
        private int calls;

        Worker(KubernetesCluster cluster) {
            super(cluster, null, new KubernetesClusterManagerImpl(), null);
            UserVm vm = Mockito.mock(UserVm.class);
            Mockito.when(vm.getHostName()).thenReturn("worker-1");
            clusterVMs = Collections.singletonList(vm);
        }

        @Override
        protected String readResourceFile(String resource) {
            return "print('gate script')";
        }

        @Override
        protected Pair<Boolean, String> executeWorkloadGateCommand(String command) {
            calls++;
            return response;
        }

        @Override
        protected void logTransitStateDetachIsoAndThrow(Level level, String message, KubernetesCluster cluster,
                List<UserVm> vms, KubernetesCluster.Event event, Exception cause) {
            failureEvent = event;
            throw new CloudRuntimeException(message);
        }
    }

    private Worker worker() {
        KubernetesCluster cluster = Mockito.mock(KubernetesCluster.class);
        Mockito.when(cluster.getUuid()).thenReturn("11111111-2222-4333-8444-555555555555");
        return new Worker(cluster);
    }

    @Test
    public void successfulSshWithoutReadinessMarkerCannotContinue() {
        Worker worker = worker();
        worker.response = new Pair<>(true, "kubectl returned an unrelated response");
        try {
            worker.ensureUpgradeWorkloadsReady(false);
            fail("missing readiness receipt must pause upgrade");
        } catch (CloudRuntimeException expected) {
            assertEquals(KubernetesCluster.Event.OperationFailed, worker.failureEvent);
        }
        assertEquals(1, worker.calls);
    }

    @Test
    public void preflightAndRecoveryRequireTheirOwnMarkers() {
        Worker worker = worker();
        worker.response = new Pair<>(true, "UPGRADE_WORKLOAD_BASELINE_READY");
        worker.ensureUpgradeWorkloadsReady(true);
        worker.response = new Pair<>(true, "UPGRADE_WORKLOADS_AND_ENDPOINTS_READY");
        worker.ensureUpgradeWorkloadsReady(false);
        assertEquals(2, worker.calls);
    }

    @Test
    public void commandsUseBoundedAndEncodedNodeReceipts() throws Exception {
        Worker worker = worker();
        String capture = worker.getWorkloadGateCommand(true);
        assertTrue(capture.contains("--capture --nodes-base64 "));
        assertTrue(capture.contains("--timeout 120"));
        assertFalse(capture.contains("worker-1"));
        assertFalse(worker.getWorkloadGateCommand(false).contains("--capture"));
    }

    @Test
    public void unsafeReceiptPathIsRejectedBeforeSsh() {
        KubernetesCluster cluster = Mockito.mock(KubernetesCluster.class);
        Mockito.when(cluster.getUuid()).thenReturn("../../other-cluster");
        Worker worker = new Worker(cluster);
        try {
            worker.ensureUpgradeWorkloadsReady(true);
            fail("invalid receipt path must fail before SSH");
        } catch (CloudRuntimeException expected) {
            assertEquals(KubernetesCluster.Event.OperationFailed, worker.failureEvent);
        }
        assertEquals(0, worker.calls);
    }
}
