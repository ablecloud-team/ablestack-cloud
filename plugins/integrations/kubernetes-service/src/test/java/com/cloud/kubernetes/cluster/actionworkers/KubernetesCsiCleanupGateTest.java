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

import java.util.Collections;
import com.cloud.kubernetes.cluster.KubernetesCluster;
import com.cloud.kubernetes.cluster.KubernetesClusterManagerImpl;
import com.cloud.kubernetes.cluster.dao.KubernetesClusterDetailsDao;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.UserVmService;
import org.junit.Test;
import org.mockito.Mockito;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class KubernetesCsiCleanupGateTest {
    private static final String UID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    private static final String HANDLE = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";

    private KubernetesClusterDestroyWorker worker(boolean csi) {
        KubernetesCluster cluster = Mockito.mock(KubernetesCluster.class);
        Mockito.when(cluster.getId()).thenReturn(7L);
        Mockito.when(cluster.isCsiEnabled()).thenReturn(csi);
        KubernetesClusterManagerImpl manager = new KubernetesClusterManagerImpl();
        manager.kubernetesClusterDetailsDao = Mockito.mock(KubernetesClusterDetailsDao.class);
        KubernetesClusterDestroyWorker worker = Mockito.spy(new KubernetesClusterDestroyWorker(cluster, manager));
        worker.userVmService = Mockito.mock(UserVmService.class);
        worker.volumeDao = Mockito.mock(VolumeDao.class);
        return worker;
    }

    @Test
    public void csiFailurePreservesEveryNodeAndReportsTheBlockedPhase() {
        KubernetesClusterDestroyWorker worker = worker(true);
        Mockito.doReturn(false).when(worker).deletePVsWithReclaimPolicyDelete();
        try {
            worker.prepareCsiCleanupBeforeNodeRemoval();
            fail("CSI cleanup failure must block node removal");
        } catch (CloudRuntimeException expected) {
            Mockito.verify(worker.kubernetesClusterDetailsDao).addDetail(7L, "cleanup.status", "Blocked", true);
            Mockito.verify(worker.kubernetesClusterDetailsDao).addDetail(7L, "cleanup.phase", "MoldCsiDeleteVolumes", true);
            Mockito.verifyNoInteractions(worker.userVmService);
        }
    }

    @Test
    public void nonCsiClusterDoesNotRunCsiDeletion() {
        KubernetesClusterDestroyWorker worker = worker(false);
        worker.prepareCsiCleanupBeforeNodeRemoval();
        Mockito.verify(worker, Mockito.never()).deletePVsWithReclaimPolicyDelete();
    }

    @Test
    public void pendingReceiptIsRecordedBeforeDeletionAndRemainsBlockedForConfirmation() {
        KubernetesClusterDestroyWorker worker = worker(true);
        String output = "MOLD_PV_CLEANUP_RECEIPT {\"completed\":false,\"volumes\":[{\"uid\":\"" + UID + "\",\"handle\":\"" + HANDLE + "\"}]}";
        assertTrue(worker.recordCsiCleanupReceipt(output, false));
        Mockito.verify(worker.kubernetesClusterDetailsDao).addDetail(7L, "cleanup.pv." + UID, HANDLE, false);
        assertFalse(worker.confirmDeletedCsiBackingVolumes(output));
        Mockito.verifyNoInteractions(worker.volumeDao);
    }

    @Test
    public void existingBackingVolumeCannotBeReportedAsDeleted() {
        KubernetesClusterDestroyWorker worker = worker(true);
        Mockito.when(worker.kubernetesClusterDetailsDao.listDetailsKeyPairs(7L)).thenReturn(Collections.singletonMap("cleanup.pv." + UID, HANDLE));
        VolumeVO volume = Mockito.mock(VolumeVO.class);
        Mockito.when(volume.getState()).thenReturn(Volume.State.Ready);
        Mockito.when(worker.volumeDao.findByUuid(HANDLE)).thenReturn(volume);
        String output = "MOLD_PV_CLEANUP_RECEIPT {\"completed\":true,\"volumes\":[]}";
        assertFalse(worker.confirmDeletedCsiBackingVolumes(output));
        Mockito.verify(worker.kubernetesClusterDetailsDao).addDetail(7L, "cleanup.remaining", HANDLE, true);
        Mockito.when(volume.getState()).thenReturn(Volume.State.Expunged);
        assertTrue(worker.confirmDeletedCsiBackingVolumes(output));
    }

    @Test
    public void missingReceiptFailsClosed() {
        assertFalse(worker(true).confirmDeletedCsiBackingVolumes("legacy script completed"));
    }
}
