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
import org.junit.Test;
import org.mockito.Mockito;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

public class KubernetesRuntimeComponentRecoveryTest {
    private KubernetesClusterStartWorker worker(boolean csi) {
        KubernetesCluster cluster = Mockito.mock(KubernetesCluster.class);
        Mockito.when(cluster.isCsiEnabled()).thenReturn(csi);
        KubernetesClusterStartWorker worker = Mockito.spy(new KubernetesClusterStartWorker(cluster, new KubernetesClusterManagerImpl()));
        worker.setKeys(new String[]{"fixture-api", "fixture-secret"});
        Mockito.doNothing().when(worker).retrieveScriptFiles();
        Mockito.doNothing().when(worker).copyScripts(Mockito.any(), Mockito.anyInt());
        Mockito.doReturn(true).when(worker).createCloudStackSecret(Mockito.any());
        Mockito.doReturn(true).when(worker).deployProvider();
        Mockito.doReturn(true).when(worker).deployCsiDriver();
        return worker;
    }

    @Test public void restoresMissingCsiBeforeReportingReady() {
        KubernetesClusterStartWorker worker = worker(true);
        assertTrue(worker.reconcileRuntimeComponents());
        org.mockito.InOrder order = Mockito.inOrder(worker);
        order.verify(worker).retrieveScriptFiles();
        order.verify(worker).copyScripts(Mockito.any(), Mockito.anyInt());
        order.verify(worker).createCloudStackSecret(Mockito.any());
        order.verify(worker).deployProvider();
        order.verify(worker).deployCsiDriver();
    }
    @Test public void missingKeyFailsWithoutRemoteMutation() {
        KubernetesClusterStartWorker worker = worker(true);worker.setKeys(null);
        assertFalse(worker.reconcileRuntimeComponents());
        Mockito.verify(worker,Mockito.never()).retrieveScriptFiles();
    }
    @Test public void emptyKeyFailsWithoutRemoteMutation() {
        KubernetesClusterStartWorker worker = worker(true);worker.setKeys(new String[]{"", "fixture"});
        assertFalse(worker.reconcileRuntimeComponents());Mockito.verify(worker,Mockito.never()).retrieveScriptFiles();
    }
    @Test public void secretFailureCannotStartControllers() {
        KubernetesClusterStartWorker worker=worker(true);Mockito.doReturn(false).when(worker).createCloudStackSecret(Mockito.any());
        assertFalse(worker.reconcileRuntimeComponents());Mockito.verify(worker,Mockito.never()).deployProvider();Mockito.verify(worker,Mockito.never()).deployCsiDriver();
    }
    @Test public void providerFailureCannotReportReady() {
        KubernetesClusterStartWorker worker=worker(true);Mockito.doReturn(false).when(worker).deployProvider();
        assertFalse(worker.reconcileRuntimeComponents());Mockito.verify(worker,Mockito.never()).deployCsiDriver();
    }
    @Test public void csiFailureCannotReportReady() {
        KubernetesClusterStartWorker worker=worker(true);Mockito.doReturn(false).when(worker).deployCsiDriver();
        assertFalse(worker.reconcileRuntimeComponents());
    }
    @Test public void deploymentExceptionCannotBypassTheFailureGate() {
        KubernetesClusterStartWorker worker=worker(true);
        Mockito.doThrow(new com.cloud.utils.exception.CloudRuntimeException("fixture failure")).when(worker).deployCsiDriver();
        assertFalse(worker.reconcileRuntimeComponents());
    }
    @Test public void disabledCsiRemainsDisabled() {
        KubernetesClusterStartWorker worker=worker(false);assertTrue(worker.reconcileRuntimeComponents());Mockito.verify(worker,Mockito.never()).deployCsiDriver();
    }
}
