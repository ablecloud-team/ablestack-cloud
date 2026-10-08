// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.cloudstack.storage.dataservice;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.Before;
import org.junit.After;
import org.junit.Assert;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.cloud.user.User;
import com.cloud.user.Account;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao;

public class DesiredStateChangeTest {
    private StorageServiceOperationDao operations;
    private StorageServiceDesiredSnapshot snapshots;
    private DesiredStateChange.Runtime runtime;
    private DesiredStateChange engine;
    private AtomicReference<StorageServiceOperationVO> saved;

    @Before
    public void prepare() {
        User user = mock(User.class); Account account = mock(Account.class);
        when(user.getId()).thenReturn(1L); when(account.getId()).thenReturn(2L);
        CallContext.register(user, account);
        operations = mock(StorageServiceOperationDao.class);
        snapshots = mock(StorageServiceDesiredSnapshot.class);
        runtime = mock(DesiredStateChange.Runtime.class);
        saved = new AtomicReference<>();
        when(operations.listByInstance(7L)).thenReturn(Collections.emptyList());
        when(snapshots.capture(7L)).thenReturn("previous", "current");
        when(operations.persist(any())).thenAnswer(call -> {
            StorageServiceOperationVO operation = call.getArgument(0); saved.set(operation); return operation;
        });
        engine = new DesiredStateChange(operations, snapshots, id -> new DesiredStateChange.WriterLock() {
            public boolean lock(int seconds) { return true; }
            public void unlock() { }
            public void releaseRef() { }
        });
    }

    @After public void clearContext() { CallContext.unregister(); }

    @Test public void verifiesBeforePromotingLastKnownGoodAndReturnsIdempotentResult() {
        Assert.assertEquals("done", engine.execute(7L, "update", "request", 0L, String.class, () -> "done", runtime));
        Assert.assertEquals("COMPLETE", saved.get().getState());
        Assert.assertEquals("current", saved.get().getSnapshotJson());
        Assert.assertEquals("previous", saved.get().getPreviousSnapshotJson());
        verify(runtime).verify();
        when(operations.findByRequest(7L, "update:request")).thenReturn(saved.get());
        Assert.assertEquals("done", engine.execute(7L, "update", "request", 0L, String.class, () -> { throw new AssertionError("duplicate mutation"); }, runtime));
    }

    @Test public void failedApplyRestoresDatabaseAndRuntimeBeforeReturningRolledBack() {
        Assert.assertThrows(CloudRuntimeException.class, () -> engine.execute(7L, "update", "request", 0L, String.class,
                () -> { throw new CloudRuntimeException("apply failed"); }, runtime));
        verify(snapshots).restore(7L, "previous");
        verify(runtime).applyPrevious(); verify(runtime).verify();
        Assert.assertEquals("ROLLED_BACK", saved.get().getState());
        Assert.assertNull(saved.get().getSnapshotJson());
    }

    @Test public void failedRollbackRequiresRecoveryAndNeverPromotesLastKnownGood() {
        doThrow(new CloudRuntimeException("runtime restore failed")).when(runtime).applyPrevious();
        Assert.assertThrows(CloudRuntimeException.class, () -> engine.execute(7L, "update", "request", 0L, String.class,
                () -> { throw new CloudRuntimeException("apply failed"); }, runtime));
        Assert.assertEquals("RECOVERY_REQUIRED", saved.get().getState());
        Assert.assertNull(saved.get().getSnapshotJson());
    }

    @Test public void rejectedInputWithUnchangedDesiredStateDoesNotRestartHealthyServices() {
        when(snapshots.capture(7L)).thenReturn("previous");
        Assert.assertThrows(CloudRuntimeException.class,()->engine.execute(7L,"network","invalid",0L,String.class,
                ()->{ throw new com.cloud.exception.InvalidParameterValueException("old runtime capability"); },runtime));
        Assert.assertEquals("BLOCKED",saved.get().getState());verify(runtime,never()).applyPrevious();verify(snapshots,never()).restore(anyLong(),anyString());
    }
    @Test public void resourcePreflightFailureNeverMutatesOrRestoresResources() {
        doThrow(new CloudRuntimeException("resource pressure")).when(runtime).preflight();
        Assert.assertThrows(CloudRuntimeException.class, () -> engine.execute(7L, "update", "request", 0L, String.class,
                () -> { throw new AssertionError("unexpected mutation"); }, runtime));
        Assert.assertEquals("BLOCKED", saved.get().getState());
        verify(snapshots, never()).restore(anyLong(), anyString());
    }
    @Test public void failedRestorePointPromotionRollsBackInsteadOfMarkingChangeComplete() {
        org.mockito.Mockito.doThrow(new CloudRuntimeException("restore point storage unavailable"))
                .when(runtime).promoteVerifiedConfiguration(org.mockito.ArgumentMatchers.any());
        Assert.assertThrows(CloudRuntimeException.class, () -> engine.execute(7L, "restore", "point-failure", 0L, String.class, () -> "new", runtime));
        Assert.assertEquals("ROLLED_BACK", saved.get().getState());
        verify(snapshots).restore(7L, "previous");
        verify(runtime).applyPrevious();
    }
    @Test public void successfulPromotionOccursOnlyAfterRuntimeVerificationAndSnapshotCapture() {
        Assert.assertEquals("done", engine.execute(7L, "update", "verified-point", 0L, String.class, () -> "done", runtime));
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(runtime, snapshots);
        order.verify(runtime).started(org.mockito.ArgumentMatchers.any());
        order.verify(runtime).preflight();
        order.verify(snapshots).capture(7L);
        order.verify(runtime).verify();
        order.verify(runtime).verifyNativeGeneration(any());
        order.verify(snapshots).capture(7L);
        order.verify(runtime).promoteVerifiedConfiguration(org.mockito.ArgumentMatchers.any());
    }

    @Test public void failedNativeCheckpointNeverMutatesOrRestartsHealthyServices() {
        doThrow(new CloudRuntimeException("native identity checkpoint unavailable")).when(runtime).prepareNativeCheckpoint(any());
        Assert.assertThrows(CloudRuntimeException.class, () -> engine.execute(7L, "smb", "checkpoint-failure", 0L, String.class,
                () -> { throw new AssertionError("credential mutation was reached"); }, runtime));
        Assert.assertEquals("BLOCKED", saved.get().getState());
        Assert.assertEquals("previous", saved.get().getPreviousSnapshotJson());
        verify(runtime, never()).applyPrevious();verify(runtime, never()).verify();verify(snapshots, never()).restore(anyLong(), anyString());
    }
    @Test public void checkpointIsProtectedAfterDesiredSnapshotAndBeforeAnyChange() {
        java.util.List<String> order = new java.util.ArrayList<>();
        org.mockito.Mockito.doAnswer(call -> { Assert.assertEquals("previous", saved.get().getPreviousSnapshotJson());order.add("checkpoint");return null; })
                .when(runtime).prepareNativeCheckpoint(any());
        engine.execute(7L, "smb", "checkpoint-order", 0L, String.class, () -> { order.add("mutation");return "done"; }, runtime);
        Assert.assertEquals(java.util.List.of("checkpoint", "mutation"), order);
    }

    @Test public void operationResultIsAvailableAtTheAtomicPromotionBoundary() {
        org.mockito.Mockito.doAnswer(call -> {
            StorageServiceOperationVO operation = call.getArgument(0);
            Assert.assertEquals("\"done\"", operation.getResultJson());
            operation.setState("COMPLETE");operation.setPhase("COMPLETE");operation.setProgress(100);
            return null;
        }).when(runtime).promoteVerifiedConfiguration(any());
        Assert.assertEquals("done", engine.execute(7L, "atomic", "commit", 0L, String.class, () -> "done", runtime));
        Assert.assertEquals("COMPLETE", saved.get().getState());
        Assert.assertEquals(100, saved.get().getProgress());
    }

    @Test public void failedAtomicCompletionNeverLeavesTheInMemoryOperationComplete() {
        org.mockito.Mockito.doAnswer(call -> {
            StorageServiceOperationVO operation = call.getArgument(0);
            operation.setState("COMPLETE");
            throw new CloudRuntimeException("operation commit failure");
        }).when(runtime).promoteVerifiedConfiguration(any());
        Assert.assertThrows(CloudRuntimeException.class, () -> engine.execute(7L, "atomic", "commit-failure", 0L, String.class, () -> "new", runtime));
        Assert.assertEquals("ROLLED_BACK", saved.get().getState());
        verify(snapshots).restore(7L, "previous");
        verify(runtime).applyPrevious();
    }

    @Test public void unresolvedWriterBlocksANewChangeBeforeCreatingAnOperation() {
        StorageServiceOperationVO active = new StorageServiceOperationVO();active.setState("RUNNING");active.setInstanceId(7L);
        when(operations.listByInstance(7L)).thenReturn(java.util.List.of(active));
        Assert.assertThrows(CloudRuntimeException.class, () -> engine.execute(7L, "new", "blocked-by-orphan", null, String.class,
                () -> { throw new AssertionError("Unexpected mutation"); }, runtime));
        verify(operations, never()).persist(any());verify(runtime, never()).preflight();
    }

    @Test public void failedRecoveryWithoutALaterCommitBlocksNewMutations() {
        StorageServiceOperationVO recovery = new StorageServiceOperationVO();recovery.setState("RECOVERY_REQUIRED");recovery.setRevision(5);
        StorageServiceOperationVO committed = new StorageServiceOperationVO();committed.setState("COMPLETE");committed.setRevision(4);
        when(operations.listByInstance(7L)).thenReturn(java.util.List.of(recovery, committed));
        Assert.assertThrows(CloudRuntimeException.class, () -> engine.execute(7L, "new", "failed-recovery", 4L, String.class,
                () -> { throw new AssertionError("Unexpected mutation"); }, runtime));
        verify(operations, never()).persist(any());
    }
    @Test public void historicalFailureCannotBlockChangesAfterTheSameRevisionWasVerified() {
        StorageServiceOperationVO recovery = new StorageServiceOperationVO();recovery.setState("RECOVERY_REQUIRED");recovery.setRevision(5);
        StorageServiceOperationVO committed = new StorageServiceOperationVO();committed.setState("COMPLETE");committed.setRevision(5);
        when(operations.listByInstance(7L)).thenReturn(java.util.List.of(recovery, committed));
        Assert.assertEquals("done", engine.execute(7L, "new", "later-verified", 5L, String.class, () -> "done", runtime));
        Assert.assertEquals(6, saved.get().getRevision());
    }

    @Test public void failedNativeGenerationCommitRestoresDesiredAndNativeBeforeCompletingRollback() {
        doThrow(new CloudRuntimeException("native generation changed")).when(runtime).verifyNativeGeneration(any());
        Assert.assertThrows(CloudRuntimeException.class, () -> engine.execute(7L, "smb", "generation", 0L, String.class, () -> "new", runtime));
        Assert.assertEquals("ROLLED_BACK", saved.get().getState());
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(runtime, snapshots);
        order.verify(runtime).verifyNativeGeneration(any());
        order.verify(snapshots).restore(7L, "previous");
        order.verify(runtime).applyPrevious();
        order.verify(runtime).verify();
        order.verify(runtime).rollbackNativeGeneration(any());
        verify(runtime, never()).promoteVerifiedConfiguration(any());
    }
    @Test public void rejectedInputReleasesNativeCheckpointWithoutRestartingProtocols() {
        when(snapshots.capture(7L)).thenReturn("previous");
        Assert.assertThrows(CloudRuntimeException.class, () -> engine.execute(7L, "invalid", "checkpoint-abort", 0L, String.class,
                () -> { throw new com.cloud.exception.InvalidParameterValueException("invalid"); }, runtime));
        verify(runtime).abortNativeCheckpoint(any());
        verify(runtime, never()).applyPrevious();
        Assert.assertEquals("BLOCKED", saved.get().getState());
    }
    @Test public void uncertainNativeCheckpointAbortRetainsRecoveryRequiredInsteadOfAllowingAnotherWriter() {
        doThrow(new CloudRuntimeException("native unreachable")).when(runtime).abortNativeCheckpoint(any());
        doThrow(new CloudRuntimeException("prepare disconnected")).when(runtime).prepareNativeCheckpoint(any());
        Assert.assertThrows(CloudRuntimeException.class, () -> engine.execute(7L, "prepare", "abort-failed", 0L, String.class,
                () -> { throw new AssertionError("Unexpected mutation"); }, runtime));
        Assert.assertEquals("RECOVERY_REQUIRED", saved.get().getState());
    }

    @Test public void exactRequestFingerprintReplaysOriginalResponseWithoutMutation() {
        Assert.assertEquals(Boolean.TRUE,engine.execute(7L,"password-reset","bound",0L,"intent-A",Boolean.class,()->true,runtime));
        when(operations.findByRequest(7L,"password-reset:bound")).thenReturn(saved.get());
        Assert.assertEquals(Boolean.TRUE,engine.execute(7L,"password-reset","bound",0L,"intent-A",Boolean.class,()->{throw new AssertionError("duplicate mutation");},runtime));
        verify(runtime).prepareNativeCheckpoint(any());
    }
    @Test public void reusedKeyForAnotherTargetOrParameterIntentIsRejectedBeforeMutation() {
        engine.execute(7L,"password-reset","bound",0L,"intent-XFS",Boolean.class,()->true,runtime);
        when(operations.findByRequest(7L,"password-reset:bound")).thenReturn(saved.get());
        Assert.assertThrows(CloudRuntimeException.class,()->engine.execute(7L,"password-reset","bound",0L,"intent-EXT4",Boolean.class,()->{throw new AssertionError("foreign mutation");},runtime));
        verify(runtime).prepareNativeCheckpoint(any());
    }
    @Test public void historicalUnboundKeyCannotAssertCurrentBodyIdentity() {
        StorageServiceOperationVO old=new StorageServiceOperationVO();old.setState("COMPLETE");old.setResultJson("true");when(operations.findByRequest(7L,"reset:legacy")).thenReturn(old);
        Assert.assertThrows(CloudRuntimeException.class,()->engine.execute(7L,"reset","legacy",0L,"new-intent",Boolean.class,()->{throw new AssertionError("unbound replay");},runtime));
        verify(runtime,never()).prepareNativeCheckpoint(any());
    }

    @Test public void anotherActorCannotReplayAnIdenticalTargetAndFingerprint() {
        engine.execute(7L,"reset","bound-actor",0L,"same-intent",Boolean.class,()->true,runtime);
        when(operations.findByRequest(7L,"reset:bound-actor")).thenReturn(saved.get());
        User other=mock(User.class);Account account=mock(Account.class);when(other.getId()).thenReturn(42L);when(account.getId()).thenReturn(2L);CallContext.unregister();CallContext.register(other,account);
        Assert.assertThrows(CloudRuntimeException.class,()->engine.execute(7L,"reset","bound-actor",0L,"same-intent",Boolean.class,()->{throw new AssertionError("foreign replay");},runtime));
        verify(runtime).prepareNativeCheckpoint(any());
    }

}
