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
import com.cloud.kubernetes.cluster.KubernetesClusterVmMapVO;
import com.cloud.kubernetes.cluster.dao.KubernetesClusterVmMapDao;
import com.cloud.kubernetes.version.KubernetesSupportedVersion;
import com.cloud.uservm.UserVm;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.logging.log4j.Level;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@RunWith(MockitoJUnitRunner.class)
public class KubernetesClusterUpgradeWorkerTest {

    @Mock
    private KubernetesCluster kubernetesCluster;
    @Mock
    private KubernetesSupportedVersion kubernetesSupportedVersion;
    @Mock
    private KubernetesClusterManagerImpl clusterManager;
    @Mock
    private KubernetesClusterVmMapDao kubernetesClusterVmMapDao;

    private KubernetesClusterUpgradeWorker worker;

    @Before
    public void setUp() {
        String[] keys = {};
        worker = new KubernetesClusterUpgradeWorker(kubernetesCluster, kubernetesSupportedVersion, clusterManager, keys);
        worker.kubernetesClusterVmMapDao = kubernetesClusterVmMapDao;
    }

    @Test public void failureStageOnlyExtractsBoundedMarkers() {
        Assert.assertEquals(" (stage API_RECOVERY, exit 1)", KubernetesClusterUpgradeWorker.safeUpgradeFailureStage(
                "private command output\nMOLD_UPGRADE_FAILED stage=API_RECOVERY exit=1\n"));
    }
    @Test public void malformedFailureMarkerCannotExposeRawOutput() {
        Assert.assertEquals("", KubernetesClusterUpgradeWorker.safeUpgradeFailureStage(
                "MOLD_UPGRADE_FAILED stage=https://private/path?password=private exit=1\n"));
        Assert.assertEquals("", KubernetesClusterUpgradeWorker.safeUpgradeFailureStage(null));
        Assert.assertEquals("", KubernetesClusterUpgradeWorker.safeUpgradeFailureStage("MOLD_UPGRADE_FAILED stage=NOT_A_PHASE exit=1"));
        Assert.assertEquals("", KubernetesClusterUpgradeWorker.safeUpgradeFailureStage("MOLD_UPGRADE_FAILED stage=API_RECOVERY exit=999"));
    }
    @Test public void finalFailureStageSupersedesEarlierRecoveredFailure() {
        Assert.assertEquals(" (stage ISO_UNMOUNT, exit 32)", KubernetesClusterUpgradeWorker.safeUpgradeFailureStage(
                "MOLD_UPGRADE_FAILED stage=KUBEADM exit=1\nMOLD_UPGRADE_FAILED stage=ISO_UNMOUNT exit=32\n"));
    }

    @Test
    public void testFilterOutManualUpgradeNodesFromClusterUpgrade() {
        long controlNodeId = 1L;
        long workerNode1Id = 2L;
        long workerNode2Id = 3L;
        UserVm controlNode = Mockito.mock(UserVm.class);
        Mockito.when(controlNode.getId()).thenReturn(controlNodeId);
        UserVm workerNode1 = Mockito.mock(UserVm.class);
        Mockito.when(workerNode1.getId()).thenReturn(workerNode1Id);
        UserVm workerNode2 = Mockito.mock(UserVm.class);
        Mockito.when(workerNode2.getId()).thenReturn(workerNode2Id);
        KubernetesClusterVmMapVO controlNodeMap = Mockito.mock(KubernetesClusterVmMapVO.class);
        KubernetesClusterVmMapVO workerNode1Map = Mockito.mock(KubernetesClusterVmMapVO.class);
        KubernetesClusterVmMapVO workerNode2Map = Mockito.mock(KubernetesClusterVmMapVO.class);
        Mockito.when(workerNode2Map.isManualUpgrade()).thenReturn(true);
        Mockito.when(kubernetesClusterVmMapDao.getClusterMapFromVmId(controlNodeId)).thenReturn(controlNodeMap);
        Mockito.when(kubernetesClusterVmMapDao.getClusterMapFromVmId(workerNode1Id)).thenReturn(workerNode1Map);
        Mockito.when(kubernetesClusterVmMapDao.getClusterMapFromVmId(workerNode2Id)).thenReturn(workerNode2Map);
        worker.clusterVMs = Arrays.asList(controlNode, workerNode1, workerNode2);
        worker.filterOutManualUpgradeNodesFromClusterUpgrade();
        Assert.assertEquals(2, worker.clusterVMs.size());
        List<Long> ids = worker.clusterVMs.stream().map(UserVm::getId).collect(Collectors.toList());
        Assert.assertTrue(ids.contains(controlNodeId) && ids.contains(workerNode1Id));
        Assert.assertFalse(ids.contains(workerNode2Id));
    }
    @Test
    public void testControllerUpgradePreservesEnabledAutoscalerIdentityAndBounds() {
        Mockito.when(kubernetesCluster.getAutoscalingEnabled()).thenReturn(true);
        Mockito.when(kubernetesCluster.getUuid()).thenReturn("cluster-123");
        Mockito.when(kubernetesCluster.getMinSize()).thenReturn(2L);
        Mockito.when(kubernetesCluster.getMaxSize()).thenReturn(5L);
        String command = worker.getControllerUpgradeCommand();
        Assert.assertTrue(command.contains("cloud-controller-manager --timeout=120s"));
        Assert.assertTrue(command.contains("&& sudo /opt/bin/autoscale-kube-cluster -i cluster-123 -e -M 5 -m 2"));
        Assert.assertTrue(command.endsWith("cluster-autoscaler --timeout=120s"));
    }

    @Test
    public void testControllerUpgradeDoesNotEnableDisabledAutoscaler() {
        String command = worker.getControllerUpgradeCommand();
        Assert.assertTrue(command.contains("cloud-controller-manager --timeout=120s"));
        Assert.assertFalse(command.contains("autoscale"));
    }

    @Test(expected = CloudRuntimeException.class)
    public void testControllerUpgradeRejectsInvalidBounds() {
        Mockito.when(kubernetesCluster.getAutoscalingEnabled()).thenReturn(true);
        Mockito.when(kubernetesCluster.getUuid()).thenReturn("cluster-123");
        Mockito.when(kubernetesCluster.getMinSize()).thenReturn(4L);
        Mockito.when(kubernetesCluster.getMaxSize()).thenReturn(2L);
        worker.getControllerUpgradeCommand();
    }

    @Test(expected = CloudRuntimeException.class)
    public void testControllerUpgradeRejectsMissingBounds() {
        Mockito.when(kubernetesCluster.getAutoscalingEnabled()).thenReturn(true);
        Mockito.when(kubernetesCluster.getUuid()).thenReturn("cluster-123");
        worker.getControllerUpgradeCommand();
    }

    @Test(expected = CloudRuntimeException.class)
    public void testControllerUpgradeRejectsUnsafeClusterIdentity() {
        Mockito.when(kubernetesCluster.getAutoscalingEnabled()).thenReturn(true);
        Mockito.when(kubernetesCluster.getUuid()).thenReturn("cluster; exit 0");
        Mockito.when(kubernetesCluster.getMinSize()).thenReturn(2L);
        Mockito.when(kubernetesCluster.getMaxSize()).thenReturn(3L);
        worker.getControllerUpgradeCommand();
    }

    @Test
    public void testControllerUpgradeWaitsForSuccessfulRemoteResult() throws Exception {
        KubernetesClusterUpgradeWorker spy = Mockito.spy(worker);
        Mockito.doReturn(new Pair<>(true, "ready")).when(spy).executeControllerUpgradeCommand(Mockito.anyString());
        spy.upgradeKubernetesControllers();
        Mockito.verify(spy).executeControllerUpgradeCommand(Mockito.contains("--timeout=120s"));
    }

    @Test
    public void testControllerUpgradeRemoteFailureFailsOperationAndDetachesIso() throws Exception {
        assertControllerUpgradeFailure(false);
    }

    @Test
    public void testControllerUpgradeSshTimeoutFailsOperationAndDetachesIso() throws Exception {
        assertControllerUpgradeFailure(true);
    }

    private void assertControllerUpgradeFailure(boolean sshTimeout) throws Exception {
        KubernetesClusterUpgradeWorker spy = Mockito.spy(worker);
        if (sshTimeout) {
            Mockito.doThrow(new java.io.IOException("timeout")).when(spy).executeControllerUpgradeCommand(Mockito.anyString());
        } else {
            Mockito.doReturn(new Pair<>(false, "rollout timeout")).when(spy).executeControllerUpgradeCommand(Mockito.anyString());
        }
        Mockito.doThrow(new CloudRuntimeException("controller failure")).when(spy).logTransitStateDetachIsoAndThrow(
                Mockito.eq(Level.ERROR), Mockito.anyString(), Mockito.eq(kubernetesCluster), Mockito.eq(worker.clusterVMs),
                Mockito.eq(KubernetesCluster.Event.OperationFailed), Mockito.isNull());
        try {
            spy.upgradeKubernetesControllers();
            Assert.fail("A failed controller rollout must not succeed");
        } catch (CloudRuntimeException expected) {
            Mockito.verify(spy).logTransitStateDetachIsoAndThrow(Mockito.eq(Level.ERROR), Mockito.anyString(),
                    Mockito.eq(kubernetesCluster), Mockito.eq(worker.clusterVMs),
                    Mockito.eq(KubernetesCluster.Event.OperationFailed), Mockito.isNull());
        }
    }

    @org.junit.Test public void manifestApplyErrorExposesOnlyWhitelistedCategory() {
        Assert.assertEquals(" (stage CNI_APPLY, exit 1, reason API_TRANSIENT)", KubernetesClusterUpgradeWorker.safeUpgradeFailureStage(
                "private-output\nMOLD_UPGRADE_APPLY_FAILURE reason=API_TRANSIENT\nMOLD_UPGRADE_FAILED stage=CNI_APPLY exit=1\n"));
        Assert.assertEquals(" (stage CNI_APPLY, exit 1)", KubernetesClusterUpgradeWorker.safeUpgradeFailureStage(
                "MOLD_UPGRADE_APPLY_FAILURE reason=raw-private-error\nMOLD_UPGRADE_FAILED stage=CNI_APPLY exit=1\n"));
    }

    @Test public void targetImagePreparationIncludesOriginalManualNodeOrderingWithoutDrain() throws Exception {
        KubernetesClusterUpgradeWorker checked = Mockito.spy(worker);
        checked.kubernetesClusterDetailsDao = Mockito.mock(com.cloud.kubernetes.cluster.dao.KubernetesClusterDetailsDao.class);
        UserVm control = Mockito.mock(UserVm.class); UserVm manual = Mockito.mock(UserVm.class); UserVm workerNode = Mockito.mock(UserVm.class);
        Mockito.when(control.getUuid()).thenReturn("control");Mockito.when(manual.getUuid()).thenReturn("manual");Mockito.when(workerNode.getUuid()).thenReturn("worker");
        checked.imagePreparationNodes = Arrays.asList(control, manual, workerNode);
        checked.clusterVMs = Arrays.asList(control, workerNode);
        Mockito.doReturn(new Pair<>(true, "MOLD_UPGRADE_IMAGES_PRELOADED")).when(checked).runInstallScriptOnVM(Mockito.any(), Mockito.anyInt(), Mockito.eq(true));
        checked.preloadUpgradeImages();
        org.mockito.InOrder order = Mockito.inOrder(checked);
        order.verify(checked).runInstallScriptOnVM(control, 0, true);
        order.verify(checked).runInstallScriptOnVM(manual, 1, true);
        order.verify(checked).runInstallScriptOnVM(workerNode, 2, true);
        Mockito.verify(checked, Mockito.never()).captureUpgradeNodeCordon(Mockito.any());
    }
    @Test public void targetImagePreparationFailureStopsBeforeNextNodeAndDrain() throws Exception {
        KubernetesClusterUpgradeWorker checked = Mockito.spy(worker);
        UserVm first = Mockito.mock(UserVm.class);UserVm second = Mockito.mock(UserVm.class);
        checked.imagePreparationNodes = Arrays.asList(first, second);
        Mockito.doReturn(new Pair<>(false, "private failure")).when(checked).runInstallScriptOnVM(first, 0, true);
        Mockito.doThrow(new CloudRuntimeException("expected image preparation failure" )).when(checked)
                .logTransitStateDetachIsoAndThrow(Mockito.any(), Mockito.anyString(), Mockito.any(), Mockito.anyList(), Mockito.any(), Mockito.isNull());
        try { checked.preloadUpgradeImages();Assert.fail("failed image preparation must stop upgrade"); }
        catch (CloudRuntimeException expected) { }
        Mockito.verify(checked, Mockito.never()).runInstallScriptOnVM(second, 1, true);
        Mockito.verify(checked, Mockito.never()).captureUpgradeNodeCordon(Mockito.any());
    }

}
