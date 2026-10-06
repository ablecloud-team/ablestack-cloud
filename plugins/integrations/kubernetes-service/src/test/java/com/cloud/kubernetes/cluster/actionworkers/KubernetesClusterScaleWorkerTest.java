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
import com.cloud.kubernetes.cluster.KubernetesClusterVmMapVO;
import com.cloud.kubernetes.cluster.KubernetesClusterManagerImpl;
import com.cloud.kubernetes.cluster.dao.KubernetesClusterVmMapDao;
import com.cloud.offering.ServiceOffering;
import com.cloud.network.dao.LoadBalancerDao;
import com.cloud.network.dao.LoadBalancerVMMapDao;
import com.cloud.network.dao.LoadBalancerVMMapVO;
import com.cloud.network.dao.LoadBalancerVO;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.utils.Pair;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.dao.UserVmDao;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static com.cloud.kubernetes.cluster.KubernetesServiceHelper.KubernetesClusterNodeType.CONTROL;
import static com.cloud.kubernetes.cluster.KubernetesServiceHelper.KubernetesClusterNodeType.DEFAULT;

@RunWith(MockitoJUnitRunner.class)
public class KubernetesClusterScaleWorkerTest {

    @Mock
    private KubernetesCluster kubernetesCluster;
    @Mock
    private KubernetesClusterManagerImpl clusterManager;
    @Mock
    private ServiceOfferingDao serviceOfferingDao;
    @Mock
    private KubernetesClusterVmMapDao kubernetesClusterVmMapDao;
    @Mock
    private UserVmDao userVmDao;

    @Mock
    private LoadBalancerDao loadBalancerDao;
    @Mock
    private LoadBalancerVMMapDao loadBalancerVMMapDao;

    private KubernetesClusterScaleWorker worker;

    private static final Long defaultOfferingId = 1L;

    @Before
    public void setUp() {
        worker = new KubernetesClusterScaleWorker(kubernetesCluster, clusterManager);
        worker.serviceOfferingDao = serviceOfferingDao;
        worker.kubernetesClusterVmMapDao = kubernetesClusterVmMapDao;
        worker.userVmDao = userVmDao;
        worker.loadBalancerDao = loadBalancerDao;
        worker.loadBalancerVMMapDao = loadBalancerVMMapDao;
    }

    @Test
    public void testCalculateNewClusterCountAndCapacityAllNodesScaleSize() {
        long controlNodes = 3L;
        long etcdNodes = 2L;
        Mockito.when(kubernetesCluster.getControlNodeCount()).thenReturn(controlNodes);
        Mockito.when(kubernetesCluster.getEtcdNodeCount()).thenReturn(etcdNodes);

        ServiceOffering newOffering = Mockito.mock(ServiceOffering.class);
        int newCores = 4;
        int newMemory = 4096;
        Mockito.when(newOffering.getCpu()).thenReturn(newCores);
        Mockito.when(newOffering.getRamSize()).thenReturn(newMemory);

        long newWorkerSize = 4L;
        Pair<Long, Long> newClusterCapacity = worker.calculateNewClusterCountAndCapacity(newWorkerSize, DEFAULT, newOffering);

        long expectedCores = (newCores * newWorkerSize) + (newCores * controlNodes) + (newCores * etcdNodes);
        long expectedMemory = (newMemory * newWorkerSize) + (newMemory * controlNodes) + (newMemory * etcdNodes);
        Assert.assertEquals(expectedCores, newClusterCapacity.first().longValue());
        Assert.assertEquals(expectedMemory, newClusterCapacity.second().longValue());
    }

    @Test
    public void testCalculateNewClusterCountAndCapacityNodeTypeScaleControlOffering() {
        long controlNodes = 2L;
        long kubernetesClusterId = 10L;
        Mockito.when(kubernetesCluster.getId()).thenReturn(kubernetesClusterId);
        Mockito.when(kubernetesCluster.getControlNodeCount()).thenReturn(controlNodes);

        ServiceOfferingVO existingOffering = Mockito.mock(ServiceOfferingVO.class);
        int existingCores = 2;
        int existingMemory = 2048;
        Mockito.when(existingOffering.getCpu()).thenReturn(existingCores);
        Mockito.when(existingOffering.getRamSize()).thenReturn(existingMemory);
        int remainingClusterCpu = 8;
        int remainingClusterMemory = 12288;
        Mockito.when(kubernetesCluster.getCores()).thenReturn(remainingClusterCpu + (controlNodes * existingCores));
        Mockito.when(kubernetesCluster.getMemory()).thenReturn(remainingClusterMemory + (controlNodes * existingMemory));

        Mockito.when(serviceOfferingDao.findById(1L)).thenReturn(existingOffering);

        ServiceOfferingVO newOffering = Mockito.mock(ServiceOfferingVO.class);
        int newCores = 4;
        int newMemory = 2048;
        Mockito.when(newOffering.getCpu()).thenReturn(newCores);
        Mockito.when(newOffering.getRamSize()).thenReturn(newMemory);

        KubernetesClusterVmMapVO controlNodeVM1 = Mockito.mock(KubernetesClusterVmMapVO.class);
        Mockito.when(controlNodeVM1.getVmId()).thenReturn(10L);
        UserVmVO userVmVO = Mockito.mock(UserVmVO.class);
        Mockito.when(userVmVO.getServiceOfferingId()).thenReturn(defaultOfferingId);
        Mockito.when(userVmDao.findById(10L)).thenReturn(userVmVO);
        Mockito.when(kubernetesClusterVmMapDao.listByClusterIdAndVmType(kubernetesClusterId, CONTROL)).thenReturn(List.of(controlNodeVM1));
        Pair<Long, Long> newClusterCapacity = worker.calculateNewClusterCountAndCapacity(null, CONTROL, newOffering);

        long expectedCores = remainingClusterCpu + (controlNodes * newCores);
        long expectedMemory = remainingClusterMemory + (controlNodes * newMemory);
        Assert.assertEquals(expectedCores, newClusterCapacity.first().longValue());
        Assert.assertEquals(expectedMemory, newClusterCapacity.second().longValue());
    }


    @Test
    public void testGetWorkerNodesToRemoveForDownsize_singleRemoval() {
        KubernetesCluster kubernetesCluster = Mockito.mock(KubernetesCluster.class);
        KubernetesClusterManagerImpl clusterManager = Mockito.mock(KubernetesClusterManagerImpl.class);
        KubernetesClusterScaleWorker worker = new KubernetesClusterScaleWorker(kubernetesCluster, new java.util.HashMap<>(), 2L, null, false, null, null, clusterManager);
        KubernetesClusterScaleWorker spyWorker = Mockito.spy(worker);

        KubernetesClusterVmMapVO vm1 = Mockito.mock(KubernetesClusterVmMapVO.class);
        Mockito.when(vm1.isExternalNode()).thenReturn(false);
        Mockito.when(vm1.isControlNode()).thenReturn(false);
        Mockito.when(vm1.isEtcdNode()).thenReturn(false);

        KubernetesClusterVmMapVO vm2 = Mockito.mock(KubernetesClusterVmMapVO.class);
        Mockito.when(vm2.isExternalNode()).thenReturn(false);
        Mockito.when(vm2.isControlNode()).thenReturn(false);
        Mockito.when(vm2.isEtcdNode()).thenReturn(false);

        KubernetesClusterVmMapVO vm3 = Mockito.mock(KubernetesClusterVmMapVO.class);
        Mockito.when(vm3.isExternalNode()).thenReturn(false);
        Mockito.when(vm3.isControlNode()).thenReturn(false);
        Mockito.when(vm3.isEtcdNode()).thenReturn(false);

        Mockito.doReturn(Arrays.asList(vm1, vm2, vm3)).when(spyWorker).getKubernetesClusterVMMaps();

        List<KubernetesClusterVmMapVO> toRemove = spyWorker.getWorkerNodesToRemove();

        Assert.assertEquals(1, toRemove.size());
        Assert.assertSame(vm3, toRemove.get(0));
    }

    @Test
    public void testGetWorkerNodesToRemoveForDownsize_noRemoval() {
        KubernetesCluster kubernetesCluster = Mockito.mock(KubernetesCluster.class);

        KubernetesClusterScaleWorker worker = new KubernetesClusterScaleWorker(kubernetesCluster, new java.util.HashMap<>(), 3L, null, false, null, null, clusterManager);
        KubernetesClusterScaleWorker spyWorker = Mockito.spy(worker);

        KubernetesClusterVmMapVO vm1 = Mockito.mock(KubernetesClusterVmMapVO.class);
        Mockito.when(vm1.isExternalNode()).thenReturn(false);
        Mockito.when(vm1.isControlNode()).thenReturn(false);
        Mockito.when(vm1.isEtcdNode()).thenReturn(false);

        KubernetesClusterVmMapVO vm2 = Mockito.mock(KubernetesClusterVmMapVO.class);
        Mockito.when(vm2.isExternalNode()).thenReturn(false);
        Mockito.when(vm2.isControlNode()).thenReturn(false);
        Mockito.when(vm2.isEtcdNode()).thenReturn(false);

        KubernetesClusterVmMapVO vm3 = Mockito.mock(KubernetesClusterVmMapVO.class);
        Mockito.when(vm3.isExternalNode()).thenReturn(false);
        Mockito.when(vm3.isControlNode()).thenReturn(false);
        Mockito.when(vm3.isEtcdNode()).thenReturn(false);

        Mockito.doReturn(Arrays.asList(vm1, vm2, vm3)).when(spyWorker).getKubernetesClusterVMMaps();

        List<KubernetesClusterVmMapVO> toRemove = spyWorker.getWorkerNodesToRemove();

        Assert.assertTrue(toRemove.isEmpty());
    }
    private KubernetesClusterScaleWorker removalWorker() {
        KubernetesClusterScaleWorker spy = Mockito.spy(worker);
        Mockito.doReturn(new File("unused-test-key")).when(spy).getManagementServerSshPublicKeyFile();
        return spy;
    }

    @Test
    public void testBlockedDrainDoesNotDeleteNodeAndUncordons() throws Exception {
        KubernetesClusterScaleWorker spy = removalWorker();
        UserVmVO vm = Mockito.mock(UserVmVO.class);
        Mockito.when(vm.getHostName()).thenReturn("worker-3");
        Mockito.doReturn(new Pair<>(false, "Cannot evict pod: PodDisruptionBudget would be violated"))
                .when(spy).executeNodeRemovalCommand(Mockito.eq("endpoint"), Mockito.eq(2222), Mockito.any(File.class),
                        Mockito.eq(KubernetesClusterScaleWorker.buildNodeDrainCommand("worker-3")), Mockito.eq(60000));
        Mockito.doReturn(new Pair<>(true, "node uncordoned"))
                .when(spy).executeNodeRemovalCommand(Mockito.eq("endpoint"), Mockito.eq(2222), Mockito.any(File.class),
                        Mockito.eq(KubernetesClusterScaleWorker.buildNodeUncordonCommand("worker-3")), Mockito.eq(30000));
        Assert.assertFalse(spy.removeKubernetesClusterNode("endpoint", 2222, vm, 1, 0));
        Mockito.verify(spy, Mockito.never()).executeNodeRemovalCommand(Mockito.anyString(), Mockito.anyInt(), Mockito.any(File.class),
                Mockito.eq(KubernetesClusterScaleWorker.buildNodeDeleteCommand("worker-3")), Mockito.anyInt());
        Mockito.verify(spy).executeNodeRemovalCommand(Mockito.eq("endpoint"), Mockito.eq(2222), Mockito.any(File.class),
                Mockito.eq(KubernetesClusterScaleWorker.buildNodeUncordonCommand("worker-3")), Mockito.eq(30000));
    }

    @Test
    public void testSuccessfulDrainDeletesNodeWithoutUncordon() throws Exception {
        KubernetesClusterScaleWorker spy = removalWorker();
        UserVmVO vm = Mockito.mock(UserVmVO.class);
        Mockito.when(vm.getHostName()).thenReturn("worker-3");
        Mockito.doReturn(new Pair<>(true, "ok")).when(spy).executeNodeRemovalCommand(Mockito.anyString(), Mockito.anyInt(),
                Mockito.any(File.class), Mockito.anyString(), Mockito.anyInt());
        Assert.assertTrue(spy.removeKubernetesClusterNode("endpoint", 2222, vm, 1, 0));
        Mockito.verify(spy).executeNodeRemovalCommand(Mockito.eq("endpoint"), Mockito.eq(2222), Mockito.any(File.class),
                Mockito.eq(KubernetesClusterScaleWorker.buildNodeDeleteCommand("worker-3")), Mockito.eq(30000));
        Mockito.verify(spy, Mockito.never()).executeNodeRemovalCommand(Mockito.anyString(), Mockito.anyInt(), Mockito.any(File.class),
                Mockito.eq(KubernetesClusterScaleWorker.buildNodeUncordonCommand("worker-3")), Mockito.anyInt());
    }

    @Test
    public void testTransportFailureAndUncordonFailurePreserveNode() throws Exception {
        KubernetesClusterScaleWorker spy = removalWorker();
        UserVmVO vm = Mockito.mock(UserVmVO.class);
        Mockito.when(vm.getHostName()).thenReturn("worker-3");
        Mockito.doThrow(new java.io.IOException("connection unavailable"))
                .when(spy).executeNodeRemovalCommand(Mockito.anyString(), Mockito.anyInt(), Mockito.any(File.class), Mockito.anyString(), Mockito.anyInt());
        Assert.assertFalse(spy.removeKubernetesClusterNode("endpoint", 2222, vm, 1, 0));
        Mockito.verify(spy, Mockito.never()).executeNodeRemovalCommand(Mockito.anyString(), Mockito.anyInt(), Mockito.any(File.class),
                Mockito.eq(KubernetesClusterScaleWorker.buildNodeDeleteCommand("worker-3")), Mockito.anyInt());
        Mockito.verify(spy).executeNodeRemovalCommand(Mockito.eq("endpoint"), Mockito.eq(2222), Mockito.any(File.class),
                Mockito.eq(KubernetesClusterScaleWorker.buildNodeUncordonCommand("worker-3")), Mockito.eq(30000));
    }

    private KubernetesNodeLoadBalancerDrain.Snapshot prepareNativeLbRemoval(KubernetesClusterScaleWorker spy, String previous) throws Exception {
        String before = KubernetesNodeLoadBalancerDrainTest.node(KubernetesNodeLoadBalancerDrainTest.NODE_UID, previous);
        String current = KubernetesNodeLoadBalancerDrainTest.node(KubernetesNodeLoadBalancerDrainTest.NODE_UID,
                KubernetesNodeLoadBalancerDrain.EXCLUSION_MARKER);
        KubernetesNodeLoadBalancerDrain.Snapshot snapshot = KubernetesNodeLoadBalancerDrain.parseSnapshot("worker-3", before,
                KubernetesNodeLoadBalancerDrainTest.services());
        Mockito.doReturn(new Pair<>(true, before), new Pair<>(true, current)).when(spy).executeNodeRemovalCommand(
                Mockito.eq("endpoint"), Mockito.eq(2222), Mockito.any(File.class),
                Mockito.eq(KubernetesNodeLoadBalancerDrain.nodeReadCommand("worker-3")), Mockito.eq(30000));
        Mockito.doReturn(new Pair<>(true, KubernetesNodeLoadBalancerDrainTest.services())).when(spy).executeNodeRemovalCommand(
                Mockito.eq("endpoint"), Mockito.eq(2222), Mockito.any(File.class),
                Mockito.eq(KubernetesNodeLoadBalancerDrain.serviceReadCommand()), Mockito.eq(30000));
        Mockito.doReturn(new Pair<>(true, "patched")).when(spy).executeNodeRemovalCommand(
                Mockito.eq("endpoint"), Mockito.eq(2222), Mockito.any(File.class),
                Mockito.eq(KubernetesNodeLoadBalancerDrain.labelPatchCommand("worker-3", snapshot, true)), Mockito.eq(30000));
        return snapshot;
    }

    private UserVmVO workerVm() {
        UserVmVO vm = Mockito.mock(UserVmVO.class);
        Mockito.when(vm.getHostName()).thenReturn("worker-3");
        return vm;
    }

    private void stubRestore(KubernetesClusterScaleWorker spy, KubernetesNodeLoadBalancerDrain.Snapshot snapshot) throws Exception {
        Mockito.doReturn(new Pair<>(true, "restored")).when(spy).executeNodeRemovalCommand(
                Mockito.eq("endpoint"), Mockito.eq(2222), Mockito.any(File.class),
                Mockito.eq(KubernetesNodeLoadBalancerDrain.labelPatchCommand("worker-3", snapshot, false)), Mockito.eq(30000));
    }

    @Test
    public void testNativeLbExclusionPrecedesDrainAndNodeDeletion() throws Exception {
        KubernetesClusterScaleWorker spy = removalWorker();
        UserVmVO vm = workerVm();
        KubernetesNodeLoadBalancerDrain.Snapshot snapshot = prepareNativeLbRemoval(spy, null);
        Mockito.doReturn(true).when(spy).waitForNodeLoadBalancerExclusion(Mockito.anyLong(), Mockito.eq(snapshot.serviceRulePrefixes));
        Mockito.doReturn(true).when(spy).removeKubernetesClusterNode("endpoint", 2222, vm, 1, 0);
        Assert.assertTrue(spy.removeKubernetesClusterWorkerNode("endpoint", 2222, vm, 1, 0));
        org.mockito.InOrder order = Mockito.inOrder(spy);
        order.verify(spy).executeNodeRemovalCommand(Mockito.eq("endpoint"), Mockito.eq(2222), Mockito.any(File.class),
                Mockito.eq(KubernetesNodeLoadBalancerDrain.labelPatchCommand("worker-3", snapshot, true)), Mockito.eq(30000));
        order.verify(spy).waitForNodeLoadBalancerExclusion(Mockito.anyLong(), Mockito.eq(snapshot.serviceRulePrefixes));
        order.verify(spy).removeKubernetesClusterNode("endpoint", 2222, vm, 1, 0);
        Mockito.verify(spy, Mockito.never()).executeNodeRemovalCommand(Mockito.anyString(), Mockito.anyInt(), Mockito.any(File.class),
                Mockito.eq(KubernetesNodeLoadBalancerDrain.labelPatchCommand("worker-3", snapshot, false)), Mockito.anyInt());
    }

    @Test
    public void testBackendExclusionTimeoutRestoresLabelWithoutDraining() throws Exception {
        KubernetesClusterScaleWorker spy = removalWorker();
        UserVmVO vm = workerVm();
        KubernetesNodeLoadBalancerDrain.Snapshot snapshot = prepareNativeLbRemoval(spy, "");
        Mockito.doReturn(false).when(spy).waitForNodeLoadBalancerExclusion(Mockito.anyLong(), Mockito.anySet());
        stubRestore(spy, snapshot);
        Assert.assertFalse(spy.removeKubernetesClusterWorkerNode("endpoint", 2222, vm, 1, 0));
        Mockito.verify(spy, Mockito.never()).removeKubernetesClusterNode(Mockito.anyString(), Mockito.anyInt(), Mockito.any(), Mockito.anyInt(), Mockito.anyInt());
        Mockito.verify(spy).executeNodeRemovalCommand(Mockito.eq("endpoint"), Mockito.eq(2222), Mockito.any(File.class),
                Mockito.eq(KubernetesNodeLoadBalancerDrain.labelPatchCommand("worker-3", snapshot, false)), Mockito.eq(30000));
    }

    @Test
    public void testPdbDrainRejectionRestoresTheOriginalExclusionLabel() throws Exception {
        KubernetesClusterScaleWorker spy = removalWorker();
        UserVmVO vm = workerVm();
        KubernetesNodeLoadBalancerDrain.Snapshot snapshot = prepareNativeLbRemoval(spy, "previous-owner");
        Mockito.doReturn(true).when(spy).waitForNodeLoadBalancerExclusion(Mockito.anyLong(), Mockito.anySet());
        Mockito.doReturn(false).when(spy).removeKubernetesClusterNode("endpoint", 2222, vm, 1, 0);
        stubRestore(spy, snapshot);
        Assert.assertFalse(spy.removeKubernetesClusterWorkerNode("endpoint", 2222, vm, 1, 0));
        Mockito.verify(spy).executeNodeRemovalCommand(Mockito.eq("endpoint"), Mockito.eq(2222), Mockito.any(File.class),
                Mockito.eq(KubernetesNodeLoadBalancerDrain.labelPatchCommand("worker-3", snapshot, false)), Mockito.eq(30000));
    }

    @Test
    public void testNativeOwnershipReadFailureCannotStartRemoval() throws Exception {
        KubernetesClusterScaleWorker spy = removalWorker();
        UserVmVO vm = workerVm();
        Mockito.doReturn(new Pair<>(false, "unavailable")).when(spy).executeNodeRemovalCommand(
                Mockito.anyString(), Mockito.anyInt(), Mockito.any(File.class), Mockito.anyString(), Mockito.anyInt());
        Assert.assertFalse(spy.removeKubernetesClusterWorkerNode("endpoint", 2222, vm, 1, 0));
        Mockito.verify(spy, Mockito.never()).removeKubernetesClusterNode(Mockito.anyString(), Mockito.anyInt(), Mockito.any(), Mockito.anyInt(), Mockito.anyInt());
    }

    @Test
    public void testReplacementNodeAfterExclusionDoesNotGetDrained() throws Exception {
        KubernetesClusterScaleWorker spy = removalWorker();
        UserVmVO vm = workerVm();
        KubernetesNodeLoadBalancerDrain.Snapshot snapshot = prepareNativeLbRemoval(spy, null);
        Mockito.doReturn(new Pair<>(true, KubernetesNodeLoadBalancerDrainTest.node(KubernetesNodeLoadBalancerDrainTest.NODE_UID, null)),
                new Pair<>(true, KubernetesNodeLoadBalancerDrainTest.node(KubernetesNodeLoadBalancerDrainTest.SERVICE_UID,
                        KubernetesNodeLoadBalancerDrain.EXCLUSION_MARKER)))
                .when(spy).executeNodeRemovalCommand(Mockito.eq("endpoint"), Mockito.eq(2222), Mockito.any(File.class),
                        Mockito.eq(KubernetesNodeLoadBalancerDrain.nodeReadCommand("worker-3")), Mockito.eq(30000));
        Mockito.doReturn(true).when(spy).waitForNodeLoadBalancerExclusion(Mockito.anyLong(), Mockito.anySet());
        stubRestore(spy, snapshot);
        Assert.assertFalse(spy.removeKubernetesClusterWorkerNode("endpoint", 2222, vm, 1, 0));
        Mockito.verify(spy, Mockito.never()).removeKubernetesClusterNode(Mockito.anyString(), Mockito.anyInt(), Mockito.any(), Mockito.anyInt(), Mockito.anyInt());
    }

    @Test
    public void testBackendPollingHasADeadline() throws Exception {
        KubernetesClusterScaleWorker spy = Mockito.spy(worker);
        Mockito.doReturn(0L).when(spy).nodeLoadBalancerDrainTimeoutMillis();
        Mockito.doReturn(true).when(spy).hasNativeServiceLoadBalancerBackends(3L, Set.of("native-service"));
        Assert.assertFalse(spy.waitForNodeLoadBalancerExclusion(3L, Set.of("native-service")));
        Mockito.verify(spy).hasNativeServiceLoadBalancerBackends(3L, Set.of("native-service"));
    }

    @Test
    public void testReconciliationCanRemoveBackendBeforeTheDeadline() throws Exception {
        KubernetesClusterScaleWorker spy = Mockito.spy(worker);
        Mockito.doReturn(true, false).when(spy).hasNativeServiceLoadBalancerBackends(3L, Set.of("native-service"));
        Assert.assertTrue(spy.waitForNodeLoadBalancerExclusion(3L, Set.of("native-service")));
        Mockito.verify(spy, Mockito.times(2)).hasNativeServiceLoadBalancerBackends(3L, Set.of("native-service"));
    }

    @Test
    public void testOnlyNativeServiceRulesOnTheClusterNetworkBlockRemoval() {
        LoadBalancerVMMapVO mapping = Mockito.mock(LoadBalancerVMMapVO.class);
        Mockito.when(mapping.getLoadBalancerId()).thenReturn(42L);
        Mockito.when(loadBalancerVMMapDao.listByInstanceId(3L)).thenReturn(List.of(mapping));
        LoadBalancerVO rule = Mockito.mock(LoadBalancerVO.class);
        Mockito.when(loadBalancerDao.findById(42L)).thenReturn(rule);
        Mockito.when(kubernetesCluster.getNetworkId()).thenReturn(100L);
        Mockito.when(rule.getNetworkId()).thenReturn(100L);
        Mockito.when(rule.getName()).thenReturn(KubernetesNodeLoadBalancerDrainTest.PREFIX + "-tcp-18087");
        Assert.assertTrue(worker.hasNativeServiceLoadBalancerBackends(3L, Set.of(KubernetesNodeLoadBalancerDrainTest.PREFIX)));
        Mockito.when(rule.getName()).thenReturn("unrelated-manual-rule");
        Assert.assertFalse(worker.hasNativeServiceLoadBalancerBackends(3L, Set.of(KubernetesNodeLoadBalancerDrainTest.PREFIX)));
        Mockito.when(rule.getNetworkId()).thenReturn(200L);
        Assert.assertFalse(worker.hasNativeServiceLoadBalancerBackends(3L, Set.of(KubernetesNodeLoadBalancerDrainTest.PREFIX)));
    }

    @Test
    public void testUnacknowledgedLabelPatchRestoresStateWithoutDraining() throws Exception {
        KubernetesClusterScaleWorker spy = removalWorker();
        UserVmVO vm = workerVm();
        KubernetesNodeLoadBalancerDrain.Snapshot snapshot = prepareNativeLbRemoval(spy, null);
        Mockito.doReturn(new Pair<>(false, "transport unavailable")).when(spy).executeNodeRemovalCommand(
                Mockito.eq("endpoint"), Mockito.eq(2222), Mockito.any(File.class),
                Mockito.eq(KubernetesNodeLoadBalancerDrain.labelPatchCommand("worker-3", snapshot, true)), Mockito.eq(30000));
        stubRestore(spy, snapshot);
        Assert.assertFalse(spy.removeKubernetesClusterWorkerNode("endpoint", 2222, vm, 1, 0));
        Mockito.verify(spy, Mockito.never()).removeKubernetesClusterNode(Mockito.anyString(), Mockito.anyInt(), Mockito.any(), Mockito.anyInt(), Mockito.anyInt());
        Mockito.verify(spy, Mockito.never()).waitForNodeLoadBalancerExclusion(Mockito.anyLong(), Mockito.anySet());
    }

    @Test(expected = com.cloud.utils.exception.CloudRuntimeException.class)
    public void testUnknownLoadBalancerOwnershipDoesNotMeanNoBackends() {
        LoadBalancerVMMapVO mapping = Mockito.mock(LoadBalancerVMMapVO.class);
        Mockito.when(mapping.getLoadBalancerId()).thenReturn(42L);
        Mockito.when(loadBalancerVMMapDao.listByInstanceId(3L)).thenReturn(List.of(mapping));
        worker.hasNativeServiceLoadBalancerBackends(3L, Set.of(KubernetesNodeLoadBalancerDrainTest.PREFIX));
    }

}
