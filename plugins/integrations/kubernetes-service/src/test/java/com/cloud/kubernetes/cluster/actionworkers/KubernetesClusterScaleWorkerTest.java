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
import com.cloud.kubernetes.cluster.KubernetesClusterVO;
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
import static com.cloud.kubernetes.cluster.KubernetesServiceHelper.KubernetesClusterNodeType.WORKER;

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

    private void verifyAutoscalingOnlyRequest(boolean wasEnabled, boolean enable, Long oldMin, Long oldMax,
                                              Long newMin, Long newMax, boolean success) {
        Mockito.when(kubernetesCluster.getState()).thenReturn(KubernetesCluster.State.Running);
        Mockito.when(kubernetesCluster.getNodeCount()).thenReturn(2L);
        Mockito.when(kubernetesCluster.getAutoscalingEnabled()).thenReturn(wasEnabled);
        Mockito.lenient().when(kubernetesCluster.getMinSize()).thenReturn(oldMin);
        Mockito.lenient().when(kubernetesCluster.getMaxSize()).thenReturn(oldMax);
        KubernetesClusterVmMapVO workerMap = Mockito.mock(KubernetesClusterVmMapVO.class);
        Mockito.when(workerMap.getVmId()).thenReturn(61L);
        Mockito.when(kubernetesClusterVmMapDao.listByClusterIdAndVmType(0L, WORKER)).thenReturn(List.of(workerMap));
        UserVmVO existingWorker = Mockito.mock(UserVmVO.class);
        Mockito.when(existingWorker.getServiceOfferingId()).thenReturn(6L);
        Mockito.when(userVmDao.findById(61L)).thenReturn(existingWorker);
        ServiceOfferingVO existing = Mockito.mock(ServiceOfferingVO.class);
        Mockito.when(serviceOfferingDao.findById(6L)).thenReturn(existing);
        KubernetesClusterScaleWorker autoscaleWorker = Mockito.spy(new KubernetesClusterScaleWorker(kubernetesCluster,
                new java.util.HashMap<>(), null, null, enable, newMin, newMax, clusterManager));
        autoscaleWorker.serviceOfferingDao = serviceOfferingDao;
        autoscaleWorker.kubernetesClusterVmMapDao = kubernetesClusterVmMapDao;
        autoscaleWorker.userVmDao = userVmDao;
        Mockito.doNothing().when(autoscaleWorker).init();
        Mockito.doReturn(success).when(autoscaleWorker).autoscaleCluster(enable, newMin, newMax);
        Mockito.doReturn(true).when(autoscaleWorker).stateTransitTo(Mockito.anyLong(), Mockito.any());
        Assert.assertEquals(success, autoscaleWorker.scaleCluster());
        Mockito.verify(autoscaleWorker).autoscaleCluster(enable, newMin, newMax);
        Mockito.verify(userVmDao).findById(61L);
        Mockito.verifyNoMoreInteractions(userVmDao);
        Mockito.verify(autoscaleWorker).stateTransitTo(0L, success ? KubernetesCluster.Event.OperationSucceeded : KubernetesCluster.Event.OperationFailed);
        Mockito.verify(kubernetesClusterVmMapDao).listByClusterIdAndVmType(0L, WORKER);
        Mockito.verifyNoMoreInteractions(kubernetesClusterVmMapDao);
    }

    @Test public void offeringFreeAutoscalerEnableStillRunsControllerConfiguration() {
        verifyAutoscalingOnlyRequest(false, true, null, null, 2L, 3L, true);
    }

    @Test public void offeringFreeAutoscalerDisableStillRunsControllerConfiguration() {
        verifyAutoscalingOnlyRequest(true, false, 2L, 3L, null, null, true);
    }

    @Test public void offeringFreeAutoscalerLimitsUpdateStillRunsControllerConfiguration() {
        verifyAutoscalingOnlyRequest(true, true, 2L, 3L, 2L, 4L, true);
    }

    @Test public void offeringFreeAutoscalerFailureIsReturnedToCaller() {
        verifyAutoscalingOnlyRequest(false, true, null, null, 2L, 3L, false);
    }

    private void actualSizedMappings(int... cpuValues) {
        java.util.ArrayList<KubernetesClusterVmMapVO> mappings = new java.util.ArrayList<>();
        for (int i = 0; i < cpuValues.length; i++) {
            long id = 100L + i;
            KubernetesClusterVmMapVO mapping = Mockito.mock(KubernetesClusterVmMapVO.class);
            Mockito.when(mapping.getVmId()).thenReturn(id);
            UserVmVO vm = Mockito.mock(UserVmVO.class);
            Mockito.when(vm.getServiceOfferingId()).thenReturn(id);
            Mockito.when(userVmDao.findById(id)).thenReturn(vm);
            ServiceOfferingVO actualOffering = offering(cpuValues[i], cpuValues[i] * 2048);
            Mockito.when(serviceOfferingDao.findById(id)).thenReturn(actualOffering);
            mappings.add(mapping);
        }
        Mockito.when(kubernetesClusterVmMapDao.listByClusterId(0L)).thenReturn(mappings);
    }

    @Test public void sizeOnlyUpdateUsesActualVmTotalsWithoutChangingRoleOfferingIds() {
        actualSizedMappings(4, 6, 6, 6);
        Mockito.when(kubernetesCluster.getControlNodeCount()).thenReturn(1L);
        Mockito.when(kubernetesCluster.getEtcdNodeCount()).thenReturn(0L);
        Mockito.when(kubernetesCluster.getAutoscalingEnabled()).thenReturn(true);
        Mockito.when(kubernetesCluster.getMinSize()).thenReturn(2L);
        Mockito.when(kubernetesCluster.getMaxSize()).thenReturn(3L);
        Mockito.when(kubernetesClusterVmMapDao.listByClusterIdAndVmType(0L, WORKER))
                .thenReturn(List.of(new KubernetesClusterVmMapVO(0L, 101L, false)));
        KubernetesClusterScaleWorker spy = Mockito.spy(worker);
        KubernetesClusterVO updated = Mockito.mock(KubernetesClusterVO.class);
        Mockito.doReturn(updated).when(spy).updateKubernetesClusterEntry(22L, 45056L, 3L, null,
                true, 2L, 3L, WORKER, false, false);
        Assert.assertSame(updated, spy.updateKubernetesClusterEntryForNodeType(3L, WORKER, null, false, false));
        Mockito.verify(spy).updateKubernetesClusterEntry(22L, 45056L, 3L, null, true, 2L, 3L, WORKER, false, false);
    }

    @Test public void sizeOnlyExpansionRebuildsActualCapacityWithLargerWorkers() {
        actualSizedMappings(4, 6, 6, 6);
        Pair<Long, Long> totals = worker.calculateActualMappedCapacity(4L);
        Assert.assertEquals(Long.valueOf(22), totals.first());
        Assert.assertEquals(Long.valueOf(45056), totals.second());
    }

    @Test public void sizeOnlyReductionRebuildsCapacityWithHeterogeneousWorkers() {
        actualSizedMappings(4, 6, 4);
        Pair<Long, Long> totals = worker.calculateActualMappedCapacity(3L);
        Assert.assertEquals(Long.valueOf(14), totals.first());
        Assert.assertEquals(Long.valueOf(28672), totals.second());
    }

    @Test public void mappedCapacityDoesNotReplaceRequestedSizeBeforeMutation() {
        KubernetesClusterVmMapVO mapping = Mockito.mock(KubernetesClusterVmMapVO.class);
        Mockito.when(kubernetesClusterVmMapDao.listByClusterId(0L)).thenReturn(List.of(mapping));
        Assert.assertNull(worker.calculateActualMappedCapacity(3L));
        Mockito.verifyNoInteractions(userVmDao, serviceOfferingDao);
    }

    @Test(expected = com.cloud.utils.exception.CloudRuntimeException.class)
    public void mappedCapacityRefusesMissingVmBeforeWritingTotals() {
        KubernetesClusterVmMapVO mapping = Mockito.mock(KubernetesClusterVmMapVO.class);
        Mockito.when(mapping.getVmId()).thenReturn(100L);
        Mockito.when(kubernetesClusterVmMapDao.listByClusterId(0L)).thenReturn(List.of(mapping));
        worker.calculateActualMappedCapacity(1L);
    }

    @Test public void unprovisionedClusterRetainsConfiguredRoleOfferingIds() {
        Mockito.when(kubernetesCluster.getControlNodeServiceOfferingId()).thenReturn(4L);
        Mockito.when(kubernetesCluster.getWorkerNodeServiceOfferingId()).thenReturn(6L);
        Mockito.when(kubernetesCluster.getEtcdNodeServiceOfferingId()).thenReturn(8L);
        Assert.assertEquals(Long.valueOf(4), worker.getExistingOfferingIdForNodeType(CONTROL, kubernetesCluster));
        Assert.assertEquals(Long.valueOf(6), worker.getExistingOfferingIdForNodeType(WORKER, kubernetesCluster));
        Assert.assertEquals(Long.valueOf(8), worker.getExistingOfferingIdForNodeType(com.cloud.kubernetes.cluster.KubernetesServiceHelper.KubernetesClusterNodeType.ETCD, kubernetesCluster));
        Mockito.verifyNoInteractions(userVmDao, serviceOfferingDao);
    }

    private void actualRoleMappings() {
        Mockito.when(kubernetesCluster.getId()).thenReturn(31L);
        Mockito.when(kubernetesCluster.getTotalNodeCount()).thenReturn(3L);
        KubernetesClusterVmMapVO control = Mockito.mock(KubernetesClusterVmMapVO.class);
        Mockito.when(control.isControlNode()).thenReturn(true);
        Mockito.lenient().when(control.getVmId()).thenReturn(11L);
        KubernetesClusterVmMapVO worker1 = Mockito.mock(KubernetesClusterVmMapVO.class);
        KubernetesClusterVmMapVO worker2 = Mockito.mock(KubernetesClusterVmMapVO.class);
        Mockito.lenient().when(worker1.getVmId()).thenReturn(12L);
        Mockito.lenient().when(worker2.getVmId()).thenReturn(13L);
        Mockito.when(kubernetesClusterVmMapDao.listByClusterId(31L)).thenReturn(List.of(control, worker1, worker2));
    }

    private ServiceOfferingVO offering(int cpu, int memory) {
        ServiceOfferingVO result = Mockito.mock(ServiceOfferingVO.class);
        Mockito.when(result.getCpu()).thenReturn(cpu);
        Mockito.when(result.getRamSize()).thenReturn(memory);
        return result;
    }

    @Test public void roleOfferingTotalsRebuildAfterVmOfferingAlreadyChanged() {
        actualRoleMappings();
        Mockito.when(kubernetesCluster.getNodeCount()).thenReturn(2L);
        UserVmVO controlVm = Mockito.mock(UserVmVO.class);
        Mockito.when(controlVm.getServiceOfferingId()).thenReturn(1L);
        Mockito.when(userVmDao.findById(11L)).thenReturn(controlVm);
        ServiceOfferingVO control = offering(4, 8192);
        Mockito.when(serviceOfferingDao.findById(1L)).thenReturn(control);
        Pair<Long, Long> total = worker.calculateNewClusterCountAndCapacity(null, WORKER, offering(6, 12288));
        Assert.assertEquals(16L, total.first().longValue());
        Assert.assertEquals(32768L, total.second().longValue());
        Mockito.verify(kubernetesCluster, Mockito.never()).getCores();
        Mockito.verify(kubernetesCluster, Mockito.never()).getMemory();
    }

    @Test public void otherRoleActualVmOfferingsCanDiffer() {
        actualRoleMappings();
        Mockito.when(kubernetesCluster.getControlNodeCount()).thenReturn(1L);
        for (long id : new long[]{12L, 13L}) {
            UserVmVO vm = Mockito.mock(UserVmVO.class);
            Mockito.when(vm.getServiceOfferingId()).thenReturn(id);
            Mockito.when(userVmDao.findById(id)).thenReturn(vm);
            ServiceOfferingVO so = offering(id == 12L ? 6 : 8, id == 12L ? 12288 : 16384);
            Mockito.when(serviceOfferingDao.findById(id)).thenReturn(so);
        }
        Pair<Long, Long> total = worker.calculateNewClusterCountAndCapacity(null, CONTROL, offering(4, 8192));
        Assert.assertEquals(18L, total.first().longValue());
        Assert.assertEquals(36864L, total.second().longValue());
    }

    @Test(expected = com.cloud.utils.exception.CloudRuntimeException.class)
    public void missingRemainingNodeCannotWriteAnIncorrectCapacity() {
        actualRoleMappings();
        Mockito.when(kubernetesCluster.getNodeCount()).thenReturn(2L);
        worker.calculateNewClusterCountAndCapacity(null, WORKER, offering(6, 12288));
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
