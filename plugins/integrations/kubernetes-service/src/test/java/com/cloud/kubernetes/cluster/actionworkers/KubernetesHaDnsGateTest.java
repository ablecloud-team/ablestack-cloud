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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

public class KubernetesHaDnsGateTest {
    private static class Worker extends KubernetesClusterStartWorker {
        private int calls;
        private boolean ready;

        Worker(KubernetesCluster cluster) {
            super(cluster, new KubernetesClusterManagerImpl());
        }

        @Override
        protected boolean executeDnsRebalance() {
            calls++;
            return ready;
        }
    }

    @Test
    public void singleControlDeploymentSkipsHaRollout() {
        KubernetesCluster cluster = Mockito.mock(KubernetesCluster.class);
        Mockito.when(cluster.getControlNodeCount()).thenReturn(1L);
        Worker worker = new Worker(cluster);
        assertTrue(worker.rebalanceHaDns());
        assertEquals(0, worker.calls);
    }

    @Test
    public void haDeploymentCannotSucceedUntilDnsGatePasses() {
        KubernetesCluster cluster = Mockito.mock(KubernetesCluster.class);
        Mockito.when(cluster.getControlNodeCount()).thenReturn(3L);
        Worker worker = new Worker(cluster);
        assertFalse(worker.rebalanceHaDns());
        worker.ready = true;
        assertTrue(worker.rebalanceHaDns());
        assertEquals(2, worker.calls);
    }
}
