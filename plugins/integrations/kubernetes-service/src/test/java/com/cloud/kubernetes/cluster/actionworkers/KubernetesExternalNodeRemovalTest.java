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

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.cloud.kubernetes.cluster.KubernetesCluster;
import com.cloud.kubernetes.cluster.KubernetesClusterDetailsVO;
import com.cloud.kubernetes.cluster.KubernetesClusterManagerImpl;
import com.cloud.kubernetes.cluster.dao.KubernetesClusterDetailsDao;
import com.cloud.network.IpAddress;
import com.cloud.network.Network;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.dao.UserVmDao;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class KubernetesExternalNodeRemovalTest {
    private final KubernetesCluster cluster = Mockito.mock(KubernetesCluster.class);
    private final UserVmDao vms = Mockito.mock(UserVmDao.class);
    private final ServiceOfferingDao offerings = Mockito.mock(ServiceOfferingDao.class);
    private final KubernetesClusterDetailsDao details = Mockito.mock(KubernetesClusterDetailsDao.class);
    private final Map<String, String> receipts = new HashMap<>();
    private final Network network = Mockito.mock(Network.class);
    private final IpAddress address = Mockito.mock(IpAddress.class);
    private TestWorker worker;

    private class TestWorker extends KubernetesClusterRemoveWorker {
        long failingNode;
        boolean ruleSuccess = true;
        int nativeCalls;
        int ruleCalls;
        List<Long> removed = new ArrayList<>();
        long cpu;
        long ram;
        TestWorker() { super(cluster, Mockito.mock(KubernetesClusterManagerImpl.class)); }
        @Override protected void prepareNodeRemovalRules(UserVmVO vm, Network net, IpAddress ip) { }
        @Override protected void removeNodeVmFromCluster(Long id, String name, String ip) {
            nativeCalls++;
            if (id == failingNode) { throw new CloudRuntimeException("drain failed"); }
        }
        @Override protected boolean removeNodePortForwardingRules(Long id, Network net, UserVmVO vm) {
            ruleCalls++;
            return ruleSuccess;
        }
        @Override protected void recordNodeRemovalEvent(String description, long vmId) { }
        @Override protected void updateKubernetesCluster(long id, List<Long> ids, long memory, long cores) {
            removed = new ArrayList<>(ids); ram = memory; cpu = cores;
        }
    }

    @Before public void setUp() throws Exception {
        Mockito.when(address.getAddress()).thenReturn(new com.cloud.utils.net.Ip("127.0.0.1"));
        Mockito.when(cluster.getId()).thenReturn(11L);
        Mockito.when(cluster.getUuid()).thenReturn("cluster");
        Mockito.when(details.findDetail(Mockito.eq(11L), Mockito.anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(1);
            return receipts.containsKey(key) ? new KubernetesClusterDetailsVO(11L, key, receipts.get(key), false) : null;
        });
        Mockito.doAnswer(inv -> { receipts.put(inv.getArgument(1), inv.getArgument(2)); return null; })
                .when(details).addDetail(Mockito.eq(11L), Mockito.anyString(), Mockito.anyString(), Mockito.eq(false));
        Mockito.doAnswer(inv -> { receipts.remove(inv.getArgument(1)); return null; })
                .when(details).removeDetail(Mockito.eq(11L), Mockito.anyString());
        worker = new TestWorker(); worker.userVmDao = vms; worker.serviceOfferingDao = offerings; worker.kubernetesClusterDetailsDao = details;
        Field timeout = KubernetesClusterRemoveWorker.class.getDeclaredField("removeNodeTimeoutTime");
        timeout.setAccessible(true); timeout.setLong(worker, Long.MAX_VALUE);
    }

    private void node(long id, int cpu, int ram) {
        UserVmVO vm = Mockito.mock(UserVmVO.class); ServiceOfferingVO offering = Mockito.mock(ServiceOfferingVO.class);
        Mockito.when(vm.getId()).thenReturn(id); Mockito.when(vm.getUuid()).thenReturn("vm" + id);
        Mockito.when(vm.getHostName()).thenReturn("worker" + id); Mockito.when(vm.getServiceOfferingId()).thenReturn(id + 100);
        Mockito.when(vms.findById(id)).thenReturn(vm); Mockito.when(offerings.findById(id, id + 100)).thenReturn(offering);
        Mockito.when(offering.getCpu()).thenReturn(cpu); Mockito.when(offering.getRamSize()).thenReturn(ram);
    }

    @Test public void drainResetOrDeleteFailureRetainsMappingAndDoesNotRevokeRules() {
        node(1L, 4, 8192); worker.failingNode = 1L;
        assertFalse(worker.removeNodesFromCluster(List.of(1L), network, address));
        assertTrue(worker.removed.isEmpty()); assertEquals(0, worker.ruleCalls); assertTrue(receipts.isEmpty());
    }

    @Test public void cleanupFailureKeepsDurableNativeCompletionAndRetryDoesNotRepeatReset() {
        node(1L, 4, 8192); worker.ruleSuccess = false;
        assertFalse(worker.removeNodesFromCluster(List.of(1L), network, address));
        assertTrue(worker.removed.isEmpty()); assertTrue(receipts.containsKey("external.remove.native.vm1"));
        worker.ruleSuccess = true;
        assertTrue(worker.removeNodesFromCluster(List.of(1L), network, address));
        assertEquals(1, worker.nativeCalls); assertEquals(List.of(1L), worker.removed);
        assertEquals(4, worker.cpu); assertEquals(8192, worker.ram); assertTrue(receipts.isEmpty());
    }

    @Test public void partialSuccessAccountsOnlyTheSuccessfulWorker() {
        node(1L, 4, 8192); node(2L, 6, 12288); worker.failingNode = 1L;
        assertFalse(worker.removeNodesFromCluster(List.of(1L, 2L), network, address));
        assertEquals(List.of(2L), worker.removed); assertEquals(6, worker.cpu); assertEquals(12288, worker.ram);
    }

    @Test public void missingVmFailsWithoutChangingTotals() {
        assertFalse(worker.removeNodesFromCluster(List.of(99L), network, address));
        assertTrue(worker.removed.isEmpty()); assertEquals(0, worker.nativeCalls);
    }

    @Test public void failedRemoteCommandDoesNotExposeItsOutput() throws Exception {
        KubernetesClusterRemoveWorker failed = new KubernetesClusterRemoveWorker(cluster, Mockito.mock(KubernetesClusterManagerImpl.class)) {
            @Override protected Pair<Boolean, String> executeNodeRemoval(String ip, int port, String command) {
                return new Pair<>(false, "secret=do-not-publish");
            }
        };
        for (String stage : List.of("DRAIN", "RESET", "NODE_DELETE")) {
            try { failed.requireNodeRemovalCommand("127.0.0.1", 22, "unused", stage); fail("must fail"); }
            catch (CloudRuntimeException e) { assertEquals("External Kubernetes node removal failed at " + stage, e.getMessage()); }
        }
    }
}
