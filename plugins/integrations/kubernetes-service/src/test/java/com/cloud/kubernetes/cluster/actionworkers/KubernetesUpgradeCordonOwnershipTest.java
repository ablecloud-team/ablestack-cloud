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

import java.util.HashMap;
import java.util.Map;
import java.util.List;
import com.cloud.kubernetes.cluster.KubernetesCluster;
import com.cloud.kubernetes.cluster.KubernetesClusterDetailsVO;
import com.cloud.kubernetes.cluster.KubernetesClusterManagerImpl;
import com.cloud.kubernetes.cluster.dao.KubernetesClusterDetailsDao;
import com.cloud.kubernetes.version.KubernetesSupportedVersion;
import com.cloud.uservm.UserVm;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;

public class KubernetesUpgradeCordonOwnershipTest {
    private final KubernetesCluster cluster = Mockito.mock(KubernetesCluster.class);
    private final KubernetesSupportedVersion target = Mockito.mock(KubernetesSupportedVersion.class);
    private final UserVm vm = Mockito.mock(UserVm.class);
    private final KubernetesClusterDetailsDao details = Mockito.mock(KubernetesClusterDetailsDao.class);
    private final Map<String, String> receipts = new HashMap<>();
    private TestWorker worker;
    private static final String UUID = "00000000-0000-0000-0000-000000000061";
    private class TestWorker extends KubernetesClusterUpgradeWorker {
        boolean querySuccess = true;
        String observed = "false";
        int queries;
        int uncordons;
        boolean uncordonSuccess = true;
        TestWorker() { super(cluster, target, Mockito.mock(KubernetesClusterManagerImpl.class), new String[0]); }
        @Override protected Pair<Boolean, String> executeUpgradeNodeCordonQuery(String host) { queries++; return new Pair<>(querySuccess, observed); }
        @Override protected boolean uncordonUpgradeNode(UserVm node) { uncordons++; return uncordonSuccess; }
    }
    @Before public void setUp() {
        Mockito.when(cluster.getId()).thenReturn(1L); Mockito.when(cluster.getKubernetesVersionId()).thenReturn(70L);
        Mockito.when(target.getId()).thenReturn(71L); Mockito.when(vm.getUuid()).thenReturn(UUID); Mockito.when(vm.getHostName()).thenReturn("worker1");
        Mockito.when(details.findDetail(Mockito.eq(1L), Mockito.anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(1);
            return receipts.containsKey(key) ? new KubernetesClusterDetailsVO(1L, key, receipts.get(key), false) : null;
        });
        Mockito.doAnswer(inv -> { receipts.put(inv.getArgument(1), inv.getArgument(2)); return null; })
                .when(details).addDetail(Mockito.eq(1L), Mockito.anyString(), Mockito.anyString(), Mockito.eq(false));
        Mockito.doAnswer(inv -> { receipts.remove(inv.getArgument(1)); return null; }).when(details).removeDetail(Mockito.eq(1L), Mockito.anyString());
        worker = new TestWorker(); worker.kubernetesClusterDetailsDao = details; worker.clusterVMs = List.of(vm);
    }
    @Test public void originallySchedulableNodeIsUncordonedAndItsFirstValueSurvivesRetry() {
        assertFalse(worker.captureUpgradeNodeCordon(vm)); worker.observed = "true";
        assertFalse(worker.captureUpgradeNodeCordon(vm)); assertEquals(1, worker.queries);
        assertTrue(worker.restoreUpgradeNodeCordon(vm, false)); assertEquals(1, worker.uncordons);
        assertEquals("70|71|" + UUID + "|false", receipts.get("upgrade.cordon." + UUID));
    }
    @Test public void operatorOrUnverifiedLegacyCordonIsPreserved() {
        worker.observed = "true"; assertTrue(worker.captureUpgradeNodeCordon(vm));
        assertTrue(worker.restoreUpgradeNodeCordon(vm, true)); assertEquals(0, worker.uncordons);
    }
    @Test public void absentUnschedulableFieldMeansSchedulableOnlyAfterSuccessfulLookup() {
        worker.observed = ""; assertFalse(worker.captureUpgradeNodeCordon(vm)); assertEquals(1, receipts.size());
    }
    @Test(expected = CloudRuntimeException.class) public void apiFailureCannotBeSavedAsSchedulable() {
        worker.querySuccess = false; try { worker.captureUpgradeNodeCordon(vm); } finally { assertTrue(receipts.isEmpty()); }
    }
    @Test(expected = CloudRuntimeException.class) public void unexpectedOutputFailsBeforeDrain() {
        worker.observed = "unexpected output"; try { worker.captureUpgradeNodeCordon(vm); } finally { assertTrue(receipts.isEmpty()); }
    }
    @Test(expected = CloudRuntimeException.class) public void differentArtifactReceiptCannotBeReused() {
        receipts.put("upgrade.cordon." + UUID, "70|72|" + UUID + "|false"); worker.captureUpgradeNodeCordon(vm);
    }
    @Test public void successfulFullUpgradeClearsOnlyMatchingReceipts() {
        worker.captureUpgradeNodeCordon(vm); receipts.put("unrelated.detail", "keep");
        Mockito.when(cluster.getKubernetesVersionId()).thenReturn(71L);
        worker.clearCompletedCordonReceipts(); assertEquals(Map.of("unrelated.detail", "keep"), receipts);
    }
    @Test public void failedUncordonRetainsRecoveryReceipt() {
        worker.captureUpgradeNodeCordon(vm); worker.uncordonSuccess = false;
        assertFalse(worker.restoreUpgradeNodeCordon(vm, false)); assertEquals(1, receipts.size());
    }
    @Test(expected = CloudRuntimeException.class) public void changedReceiptIsNotClearedAtCompletion() {
        receipts.put("upgrade.cordon." + UUID, "70|72|" + UUID + "|true");
        try { worker.clearCompletedCordonReceipts(); } finally { assertEquals(1, receipts.size()); }
    }
}
