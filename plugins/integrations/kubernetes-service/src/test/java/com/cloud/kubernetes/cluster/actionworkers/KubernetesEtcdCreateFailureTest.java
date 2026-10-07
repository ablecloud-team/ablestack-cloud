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

import com.cloud.exception.ResourceUnavailableException;
import com.cloud.kubernetes.cluster.KubernetesCluster;
import com.cloud.kubernetes.cluster.KubernetesClusterManagerImpl;
import com.cloud.network.Network;
import com.cloud.uservm.UserVm;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

public class KubernetesEtcdCreateFailureTest {
    private static class Worker extends KubernetesClusterStartWorker {
        private final boolean unavailable;
        private KubernetesCluster.Event event;
        private final CloudRuntimeException failure = new CloudRuntimeException("custom root disk size required");

        Worker(boolean unavailable) {
            super(Mockito.mock(KubernetesCluster.class), new KubernetesClusterManagerImpl());
            this.unavailable = unavailable;
        }

        @Override
        protected Pair<List<UserVm>, List<Network.IpAddresses>> provisionEtcdCluster(Network network, Long domainId, Long accountId)
                throws ResourceUnavailableException {
            if (unavailable) {
                throw new ResourceUnavailableException("etcd placement unavailable", UserVm.class, 1L);
            }
            throw failure;
        }

        @Override
        protected boolean stateTransitTo(long clusterId, KubernetesCluster.Event stateEvent) {
            event = stateEvent;
            return true;
        }
    }

    @Test
    public void runtimeProvisioningFailureDoesNotLeaveClusterStarting() {
        Worker worker = new Worker(false);
        try {
            worker.provisionEtcdClusterOnCreate(null, 1L, 1L);
            fail("etcd provisioning failure must fail the create job");
        } catch (CloudRuntimeException expected) {
            assertSame(worker.failure, expected.getCause());
        }
        assertEquals(KubernetesCluster.Event.CreateFailed, worker.event);
    }

    @Test
    public void unavailableEtcdResourceDoesNotLeaveClusterStarting() {
        Worker worker = new Worker(true);
        try {
            worker.provisionEtcdClusterOnCreate(null, 1L, 1L);
            fail("unavailable etcd resource must fail the create job");
        } catch (CloudRuntimeException expected) {
            assertEquals(ResourceUnavailableException.class, expected.getCause().getClass());
        }
        assertEquals(KubernetesCluster.Event.CreateFailed, worker.event);
    }
}
