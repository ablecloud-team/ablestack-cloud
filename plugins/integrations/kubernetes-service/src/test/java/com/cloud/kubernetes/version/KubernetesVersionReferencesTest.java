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

package com.cloud.kubernetes.version;

import com.cloud.kubernetes.cluster.KubernetesCluster;
import com.cloud.kubernetes.cluster.KubernetesClusterDetailsVO;
import com.cloud.kubernetes.cluster.dao.KubernetesClusterDetailsDao;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallbackNoReturn;
import com.cloud.utils.exception.CloudRuntimeException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import static org.junit.Assert.fail;

public class KubernetesVersionReferencesTest {
    private KubernetesCluster cluster;
    private KubernetesClusterDetailsDao details;
    private MockedStatic<Transaction> transactions;
    @Before public void setup() {
        cluster = Mockito.mock(KubernetesCluster.class);
        Mockito.when(cluster.getId()).thenReturn(3L);
        Mockito.when(cluster.getKubernetesVersionId()).thenReturn(1L);
        details = Mockito.mock(KubernetesClusterDetailsDao.class);
        transactions = Mockito.mockStatic(Transaction.class);
        transactions.when(() -> Transaction.execute(Mockito.any(TransactionCallbackNoReturn.class))).thenAnswer(i -> {
            ((TransactionCallbackNoReturn) i.getArgument(0)).doInTransactionWithoutResult(null); return null;
        });
    }
    @After public void finish() { transactions.close(); }
    private void detail(String name, String value) {
        KubernetesClusterDetailsVO d = Mockito.mock(KubernetesClusterDetailsVO.class);
        Mockito.when(d.getValue()).thenReturn(value); Mockito.when(details.findDetail(3L, name)).thenReturn(d);
    }
    @Test public void initialPinRecordsSourceAndExactTarget() {
        KubernetesVersionReferences.pin(cluster, 2L, details);
        Mockito.verify(details).addDetail(3L, KubernetesVersionReferences.SOURCE, "1", false);
        Mockito.verify(details).addDetail(3L, KubernetesVersionReferences.TARGET, "2", false);
    }
    @Test public void sameArtifactRetryAccepted() {
        detail(KubernetesVersionReferences.SOURCE, "1"); detail(KubernetesVersionReferences.TARGET, "2");
        KubernetesVersionReferences.pin(cluster, 2L, details);
        Mockito.verify(details).addDetail(3L, KubernetesVersionReferences.TARGET, "2", false);
    }
    @Test public void differentTargetCannotOverwritePartialUpgrade() {
        detail(KubernetesVersionReferences.SOURCE, "1"); detail(KubernetesVersionReferences.TARGET, "7");
        try { KubernetesVersionReferences.pin(cluster, 2L, details); fail("different target must be rejected"); }
        catch (CloudRuntimeException expected) { Mockito.verify(details, Mockito.never()).addDetail(Mockito.anyLong(), Mockito.anyString(), Mockito.anyString(), Mockito.anyBoolean()); }
    }
    @Test public void incompleteReferencePairIsNotOverwritten() {
        detail(KubernetesVersionReferences.TARGET, "2");
        try { KubernetesVersionReferences.pin(cluster, 2L, details); fail("incomplete pins must be preserved"); }
        catch (CloudRuntimeException expected) { Mockito.verify(details, Mockito.never()).addDetail(Mockito.anyLong(), Mockito.anyString(), Mockito.anyString(), Mockito.anyBoolean()); }
    }
    @Test public void changedCompletionTargetPreservesRecoveryPins() {
        detail(KubernetesVersionReferences.TARGET, "7");
        try { KubernetesVersionReferences.clear(3L, 2L, details); fail("changed target must be preserved"); }
        catch (CloudRuntimeException expected) { Mockito.verify(details, Mockito.never()).removeDetail(Mockito.anyLong(), Mockito.anyString()); }
    }
    @Test public void successfulCompletionReleasesRecoveryPins() {
        detail(KubernetesVersionReferences.TARGET, "2"); KubernetesVersionReferences.clear(3L, 2L, details);
        Mockito.verify(details).removeDetail(3L, KubernetesVersionReferences.SOURCE);
        Mockito.verify(details).removeDetail(3L, KubernetesVersionReferences.TARGET);
    }
    @Test public void disabledArtifactRejectedBeforeConsumption() {
        KubernetesSupportedVersion v = Mockito.mock(KubernetesSupportedVersion.class);
        Mockito.when(v.getState()).thenReturn(KubernetesSupportedVersion.State.Disabled);
        try { KubernetesVersionReferences.requireEnabled(v); fail("disabled version must be rejected"); }
        catch (CloudRuntimeException expected) { Mockito.verifyNoInteractions(details); }
    }
    @Test public void referenceLockFailureDoesNotRunConsumer() {
        GlobalLock lock = Mockito.mock(GlobalLock.class);
        try (MockedStatic<GlobalLock> locks = Mockito.mockStatic(GlobalLock.class)) {
            locks.when(() -> GlobalLock.getInternLock("KubernetesVersion.Reference.2")).thenReturn(lock);
            try { KubernetesVersionReferences.withLock(2L, () -> { fail("consumer must not run without lock"); return null; }); fail("lock failure must propagate"); }
            catch (CloudRuntimeException expected) { Mockito.verify(lock).releaseRef(); Mockito.verify(lock, Mockito.never()).unlock(); }
        }
    }
    @Test public void throwingConsumerStillReleasesReferenceLock() {
        GlobalLock lock = Mockito.mock(GlobalLock.class); Mockito.when(lock.lock(10)).thenReturn(true);
        try (MockedStatic<GlobalLock> locks = Mockito.mockStatic(GlobalLock.class)) {
            locks.when(() -> GlobalLock.getInternLock("KubernetesVersion.Reference.2")).thenReturn(lock);
            try { KubernetesVersionReferences.withLock(2L, () -> { throw new CloudRuntimeException("failed consumer"); }); fail("consumer failure must propagate"); }
            catch (CloudRuntimeException expected) { Mockito.verify(lock).unlock(); Mockito.verify(lock).releaseRef(); }
        }
    }
}
