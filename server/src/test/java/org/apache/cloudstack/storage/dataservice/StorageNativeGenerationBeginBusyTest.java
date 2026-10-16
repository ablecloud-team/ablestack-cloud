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
package org.apache.cloudstack.storage.dataservice;

import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

public class StorageNativeGenerationBeginBusyTest {
    private static final String INSTANCE = "8f9fd466-5f61-4893-abac-d4b4dec8f214";
    private static final String OPERATION = "3f21823d-0bf0-4e84-9202-30b736882357";

    private static StorageServiceGuestCommandResult busy() {
        return new StorageServiceGuestCommandResult(false, "exit code 75", "{\"success\":false,\"errorCode\":\"STORAGE_WRITER_BUSY\"}");
    }

    private static StorageServiceGuestCommandResult success() {
        return new StorageServiceGuestCommandResult(true, "", "{\"success\":true,\"generationSupported\":true,\"phase\":\"PREPARED\"}");
    }

    private static JsonObject idle() {
        JsonObject value = new JsonObject();
        value.addProperty("success", true);value.addProperty("status", "WRITER_IDLE");
        value.addProperty("writerIdle", true);value.addProperty("writerLockSupported", true);
        value.addProperty("bootChildActive", false);value.addProperty("bootRecoveryRequired", false);
        return value;
    }

    private static JsonObject settled() {
        JsonObject value = new JsonObject();
        value.addProperty("success", true);value.addProperty("generationSupported", true);
        value.addProperty("bootId", "33333333-3333-4333-8333-333333333333");
        value.add("pendingOperationUuid", JsonNull.INSTANCE);
        value.add("generation",sourceGeneration());value.addProperty("runtimeRevision",8);
        value.addProperty("generationStatus","IN_SYNC");value.addProperty("configurationSha256","a".repeat(64));
        JsonObject desired=new JsonObject();for(String key:StorageRenderedDesiredState.PATHS)desired.add(key,JsonNull.INSTANCE);
        value.add("configurationDesiredState",desired);return value;
    }

    private static JsonObject sourceGeneration() {
        JsonObject value = new JsonObject();
        value.addProperty("instanceUuid", INSTANCE);value.addProperty("operationUuid", "11111111-1111-4111-8111-111111111111");
        value.addProperty("revision", 8L);value.addProperty("configurationSha256", "a".repeat(64));value.addProperty("verifiedAt", 100.25);
        return value;
    }

    private static JsonObject pendingSource() {
        JsonObject value = settled();value.addProperty("pendingOperationUuid", OPERATION);
        value.addProperty("generationStatus", "PENDING");value.add("generation", sourceGeneration());
        value.addProperty("configurationSha256", "b".repeat(64));return value;
    }

    private static JsonObject committedTarget(boolean pending) {
        JsonObject value = pendingSource();JsonObject target = sourceGeneration();
        target.addProperty("operationUuid", OPERATION);target.addProperty("revision", 9L);
        target.addProperty("configurationSha256", "b".repeat(64));value.add("generation", target);
        if (!pending) { value.add("pendingOperationUuid", JsonNull.INSTANCE);value.addProperty("generationStatus", "IN_SYNC"); }
        return value;
    }

    private static class Fixture extends StorageServiceManagerImpl {
        final StorageServiceInstanceVO instance = Mockito.mock(StorageServiceInstanceVO.class);
        final StorageServiceOperationVO operation = Mockito.mock(StorageServiceOperationVO.class);
        final java.util.List<StorageServiceGuestCommand> sent = new java.util.ArrayList<>();
        final java.util.Deque<StorageServiceGuestCommandResult> replies = new java.util.ArrayDeque<>();
        JsonObject idleReply = idle(), statusReply = settled(),initialStatusReply;
        String action;
        long initialReadNanos;
        int initialStatusReads;
        RuntimeException initialReadFailure;
        long now = 1_000_000L, dispatchNanos, readNanos;
        int idleReads, statusReads, cancellationChecks;
        Runnable afterDispatch, afterIdle, afterStatus;
        RuntimeException transportFailure, readFailure, cancellationFailure;
        StorageServiceOperationVO expectedCancellationOwner = operation;

        Fixture() throws Exception {
            Mockito.when(instance.getId()).thenReturn(72L);
            Mockito.when(instance.getUuid()).thenReturn(INSTANCE);
            Mockito.when(instance.getVmId()).thenReturn(72L);
            Mockito.when(operation.getId()).thenReturn(1706L);
            Mockito.when(operation.getInstanceId()).thenReturn(72L);
            Mockito.when(operation.getUuid()).thenReturn(OPERATION);
            Mockito.when(operation.getRevision()).thenReturn(9L);
            Mockito.when(operation.getPhase()).thenReturn("PREPARED");
            Mockito.when(operation.getState()).thenReturn("RUNNING");
            JsonObject checkpoint = new JsonObject();checkpoint.add("previous", sourceGeneration());
            JsonObject snapshot = new JsonObject();snapshot.add("nativeGeneration", checkpoint);
            Mockito.when(operation.getPreviousSnapshotJson()).thenReturn(snapshot.toString());
            owner(operation);
            java.lang.reflect.Field f = StorageServiceManagerImpl.class.getDeclaredField("guestCommandDispatcher");
            f.setAccessible(true);
            f.set(this, (StorageServiceGuestCommandDispatcher) command -> {
                sent.add(command);now += dispatchNanos;
                if (afterDispatch != null) afterDispatch.run();
                if (transportFailure != null) throw transportFailure;
                return replies.isEmpty() ? busy() : replies.removeFirst();
            });
        }

        @SuppressWarnings("unchecked")
        void owner(StorageServiceOperationVO value) throws Exception {
            java.lang.reflect.Field f = StorageServiceManagerImpl.class.getDeclaredField("storageWriterOperation");
            f.setAccessible(true);((ThreadLocal<StorageServiceOperationVO>) f.get(this)).set(value);
        }

        @Override protected long rollbackNativeWriterNowNanos() { return now; }
        @Override protected void pauseRollbackNativeWriter(long millis) { now += java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(millis); }
        @Override protected JsonObject rootGuest(StorageServiceInstanceVO scope, String action, JsonObject payload, int timeout) {
            Assert.assertSame(instance, scope);Assert.assertEquals(0, payload.size());
            Assert.assertTrue(timeout >= 1 && timeout <= 30);
            if("begin".equals(this.action) && "operation generation status".equals(action) && initialStatusReads==0) {
                initialStatusReads++;now+=initialReadNanos;
                if(initialReadFailure!=null)throw initialReadFailure;
                return initialStatusReply==null?statusReply.deepCopy():initialStatusReply.deepCopy();
            }
            now += readNanos;
            if (readFailure != null) throw readFailure;
            if ("operation writer-idle".equals(action)) {
                idleReads++;if (afterIdle != null) afterIdle.run();return idleReply;
            }
            Assert.assertEquals("operation generation status", action);
            statusReads++;if (afterStatus != null) afterStatus.run();return statusReply;
        }
        @Override protected void requireNoOperationCancellation(StorageServiceOperationVO value) {
            Assert.assertSame(expectedCancellationOwner, value);cancellationChecks++;
            if (cancellationFailure != null) throw cancellationFailure;
        }
        JsonObject run(String action) { this.action=action;return nativeConfigurationGeneration(instance, operation, action); }
        long beginCount() { return sent.stream().filter(x -> "operation generation begin".equals(x.getOperation())).count(); }
    }

    @Test public void successfulTransportCannotTurnWeakNativeFlagsIntoGenerationSuccess()throws Exception {
        for(String action:java.util.List.of("begin","verify","commit","finish"))for(String field:java.util.List.of("success","generationSupported")) {
            for(com.google.gson.JsonElement weak:java.util.List.of(new com.google.gson.JsonPrimitive("true"),new com.google.gson.JsonPrimitive(1),JsonNull.INSTANCE)) {
                Fixture f=new Fixture();JsonObject malformed=new JsonObject();malformed.addProperty("success",true);
                malformed.addProperty("generationSupported",true);malformed.add(field,weak);
                f.replies.add(new StorageServiceGuestCommandResult(true,"",malformed.toString()));
                Assert.assertThrows(CloudRuntimeException.class,()->f.run(action));Assert.assertEquals(1,f.sent.size());
            }
        }
    }

    @Test public void beginPinsFreshSourceBeforeFirstDispatchEvenWithoutDurableNativeCheckpoint()throws Exception {
        Fixture f=new Fixture();Mockito.when(f.operation.getPreviousSnapshotJson()).thenReturn("{}");
        f.replies.add(busy());f.replies.add(success());f.run("begin");
        Assert.assertEquals(2,f.beginCount());Assert.assertEquals(1,f.initialStatusReads);Assert.assertEquals(1,f.statusReads);
        Fixture initial=new Fixture();Mockito.when(initial.operation.getPreviousSnapshotJson()).thenReturn("{}");
        JsonObject empty=settled();empty.add("generation",new JsonObject());empty.addProperty("runtimeRevision",0);
        empty.addProperty("generationStatus","UNVERIFIED");initial.statusReply=empty;
        initial.replies.add(busy());initial.replies.add(success());initial.run("begin");Assert.assertEquals(2,initial.beginCount());
    }
    @Test public void beginCannotRetryChangedUnknownOrMalformedSourceAfterItsFirstClosedBusy()throws Exception {
        for(int variant=0;variant<7;variant++) {
            Fixture f=new Fixture();Mockito.when(f.operation.getPreviousSnapshotJson()).thenReturn("{}");
            f.initialStatusReply=settled();f.replies.add(busy());f.replies.add(success());
            if(variant==0)f.statusReply.remove("generation");
            if(variant==1)f.statusReply.getAsJsonObject("generation").addProperty("operationUuid","22222222-2222-4222-8222-222222222222");
            if(variant==2)f.statusReply.addProperty("configurationSha256","b".repeat(64));
            if(variant==3)f.statusReply.addProperty("generationStatus","UNVERIFIED");
            if(variant==4)f.statusReply.addProperty("runtimeRevision","8");
            if(variant==5)f.statusReply.getAsJsonObject("configurationDesiredState").remove("network-endpoints.json");
            if(variant==6)f.statusReply.addProperty("bootId","22222222-2222-4222-8222-222222222222");
            Assert.assertThrows(CloudRuntimeException.class,()->f.run("begin"));Assert.assertEquals(1,f.beginCount());
        }
        Fixture unknown=new Fixture();unknown.initialReadFailure=new CloudRuntimeException("Unknown read");
        unknown.replies.add(busy());unknown.replies.add(success());
        Assert.assertThrows(CloudRuntimeException.class,()->unknown.run("begin"));Assert.assertEquals(1,unknown.beginCount());
        Fixture ownPending=new Fixture();ownPending.initialStatusReply=pendingSource();ownPending.replies.add(success());
        ownPending.run("begin");Assert.assertEquals(1,ownPending.beginCount());
    }
    @Test public void beginPreReadConsumesSameThirtySecondBudgetAndCanPreventInitialDispatch()throws Exception {
        Fixture slow=new Fixture();slow.initialReadNanos=java.util.concurrent.TimeUnit.SECONDS.toNanos(31);
        slow.replies.add(success());Assert.assertThrows(CloudRuntimeException.class,()->slow.run("begin"));Assert.assertEquals(0,slow.beginCount());
        Fixture bounded=new Fixture();bounded.initialReadNanos=java.util.concurrent.TimeUnit.SECONDS.toNanos(12);
        bounded.replies.add(success());bounded.run("begin");Assert.assertEquals(18,bounded.sent.get(0).getTimeoutSeconds());
    }

    @Test public void exactBusyRetriesSameBeginOnlyAfterFreshIdleAndNoPendingWithOneDeadline() throws Exception {
        Fixture f = new Fixture();f.replies.add(busy());f.replies.add(success());
        f.dispatchNanos = java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        Assert.assertEquals("PREPARED", f.run("begin").get("phase").getAsString());
        Assert.assertEquals(2, f.beginCount());Assert.assertEquals(1, f.idleReads);
        Assert.assertEquals(1, f.statusReads);Assert.assertEquals(1, f.cancellationChecks);
        Assert.assertEquals(f.sent.get(0).getPayload(), f.sent.get(1).getPayload());
        JsonObject request = new com.google.gson.JsonParser().parse(f.sent.get(0).getPayload()).getAsJsonObject();
        Assert.assertEquals(java.util.Set.of("instanceUuid", "operationUuid", "revision"), request.keySet());
        Assert.assertEquals(INSTANCE, request.get("instanceUuid").getAsString());
        Assert.assertEquals(OPERATION, request.get("operationUuid").getAsString());
        Assert.assertEquals(9L, request.get("revision").getAsLong());
        for (StorageServiceGuestCommand c : f.sent) {
            Assert.assertEquals(72L, c.getVmId());Assert.assertTrue(c.getMaskedFields().isEmpty());
        }
        Assert.assertEquals(30, f.sent.get(0).getTimeoutSeconds());
        Assert.assertTrue(f.sent.get(1).getTimeoutSeconds() < 30);
    }

    @Test public void repeatedBusyAndExpiredOverallDeadlineCannotResendForever() throws Exception {
        Fixture count = new Fixture();
        Assert.assertThrows(CloudRuntimeException.class, () -> count.run("begin"));
        Assert.assertEquals(4, count.beginCount());Assert.assertEquals(3, count.idleReads);
        Fixture elapsed = new Fixture();elapsed.dispatchNanos = java.util.concurrent.TimeUnit.SECONDS.toNanos(31);
        Assert.assertThrows(CloudRuntimeException.class, () -> elapsed.run("begin"));
        Assert.assertEquals(1, elapsed.beginCount());Assert.assertEquals(0, elapsed.idleReads);
        Fixture lateRead = new Fixture();lateRead.readNanos = java.util.concurrent.TimeUnit.SECONDS.toNanos(16);
        Assert.assertThrows(CloudRuntimeException.class, () -> lateRead.run("begin"));
        Assert.assertEquals(1, lateRead.beginCount());
    }

    @Test public void nonClosedFailuresAndUnknownTransportNeverRetry() throws Exception {
        for (String raw : new String[]{"", "{}", "{\"success\":false}",
                "{\"success\":\"false\",\"errorCode\":\"STORAGE_WRITER_BUSY\"}",
                "{\"success\":false,\"errorCode\":\"OTHER\"}",
                "{\"success\":false,\"errorCode\":\"STORAGE_WRITER_BUSY\",\"pendingOperationUuid\":null}",
                "{\"success\":true,\"success\":false,\"errorCode\":\"STORAGE_WRITER_BUSY\"}",
                "{\"success\":false,\"errorCode\":\"STORAGE_WRITER_BUSY\"}{}",
                "{\"success\":false,\"errorCode\":\"STORAGE_WRITER_BUSY\"/*comment*/}", "not-json"}) {
            Fixture f = new Fixture();f.replies.add(new StorageServiceGuestCommandResult(false, "STORAGE_WRITER_BUSY exit code 75", raw));
            Assert.assertThrows(RuntimeException.class, () -> f.run("begin"));
            Assert.assertEquals(1, f.beginCount());Assert.assertEquals(0, f.idleReads);
        }
        Fixture unknown = new Fixture();unknown.transportFailure = new IllegalStateException("unknown");
        Assert.assertThrows(IllegalStateException.class, () -> unknown.run("begin"));
        Assert.assertEquals(1, unknown.beginCount());Assert.assertEquals(0, unknown.idleReads);
        Fixture wrongTransport = new Fixture();wrongTransport.replies.add(new StorageServiceGuestCommandResult(true, "", busy().getResultJson()));
        Assert.assertThrows(CloudRuntimeException.class, () -> wrongTransport.run("begin"));
        Assert.assertEquals(1, wrongTransport.beginCount());Assert.assertEquals(0, wrongTransport.idleReads);
    }

    @Test public void rollbackStatusAndOrdinarySuccessRemainSingleDispatch() throws Exception {
        for (String action : new String[]{"rollback", "status"}) {
            Fixture f = new Fixture();
            Assert.assertThrows(CloudRuntimeException.class, () -> f.run(action));
            Assert.assertEquals(1, f.sent.size());Assert.assertEquals(0, f.idleReads);
        }
        Fixture f = new Fixture();f.replies.add(success());f.run("begin");
        Assert.assertEquals(1, f.beginCount());Assert.assertEquals(0, f.idleReads);
    }

    @Test public void anyOwnForeignOrMalformedPendingObservationCannotDuplicateBegin() throws Exception {
        for (int mode = 0; mode < 6; mode++) {
            Fixture f = new Fixture();f.replies.add(busy());f.replies.add(success());
            if (mode == 0) f.statusReply.addProperty("pendingOperationUuid", OPERATION);
            if (mode == 1) f.statusReply.addProperty("pendingOperationUuid", "foreign");
            if (mode == 2) f.statusReply.remove("pendingOperationUuid");
            if (mode == 3) f.statusReply.addProperty("pendingOperationUuid", false);
            if (mode == 4) f.statusReply.addProperty("success", "true");
            if (mode == 5) f.statusReply.addProperty("generationSupported", false);
            Assert.assertThrows(CloudRuntimeException.class, () -> f.run("begin"));
            Assert.assertEquals(1, f.beginCount());Assert.assertEquals(1, f.statusReads);
        }
    }

    @Test public void scopeMutationCancellationInterruptionAndUnknownIdleCannotResend() throws Exception {
        for (int mode = 0; mode < 9; mode++) {
            final int which = mode;Fixture f = new Fixture();
            f.afterDispatch = () -> {
                if (which == 0) Mockito.when(f.instance.getVmId()).thenReturn(73L);
                if (which == 1) Mockito.when(f.instance.getUuid()).thenReturn("foreign");
                if (which == 2) Mockito.when(f.operation.getUuid()).thenReturn("foreign");
                if (which == 3) Mockito.when(f.operation.getRevision()).thenReturn(10L);
                if (which == 4) Mockito.when(f.operation.getInstanceId()).thenReturn(73L);
                if (which == 5) Mockito.when(f.operation.getPhase()).thenReturn("COMPLETE");
                if (which == 6) Mockito.when(f.operation.getState()).thenReturn("COMPLETE");
                if (which == 7) Mockito.when(f.operation.getPreviousSnapshotJson()).thenReturn("{}");
                if (which == 8) {try { f.owner(null); } catch (Exception e) { throw new AssertionError(e); }}
            };
            Assert.assertThrows(CloudRuntimeException.class, () -> f.run("begin"));
            Assert.assertEquals(1, f.beginCount());Assert.assertEquals(0, f.idleReads);
        }
        Fixture changed = new Fixture();
        changed.afterStatus = () -> Mockito.when(changed.operation.getRevision()).thenReturn(10L);
        Assert.assertThrows(CloudRuntimeException.class, () -> changed.run("begin"));Assert.assertEquals(1, changed.beginCount());
        Fixture cancelled = new Fixture();cancelled.cancellationFailure = new CloudRuntimeException("cancelled");
        Assert.assertThrows(CloudRuntimeException.class, () -> cancelled.run("begin"));Assert.assertEquals(1, cancelled.beginCount());
        Fixture idleUnknown = new Fixture();idleUnknown.idleReply.addProperty("bootRecoveryRequired", true);
        Assert.assertThrows(CloudRuntimeException.class, () -> idleUnknown.run("begin"));Assert.assertEquals(1, idleUnknown.beginCount());
        Fixture interrupted = new Fixture();interrupted.afterDispatch = () -> Thread.currentThread().interrupt();
        try { Assert.assertThrows(CloudRuntimeException.class, () -> interrupted.run("begin"));Assert.assertEquals(1, interrupted.beginCount()); }
        finally { Thread.interrupted(); }
    }
    @Test public void verifyAndCommitRetryOnlySameOwnPendingAndFrozenSourceAfterClosedBusy() throws Exception {
        for (String action : new String[]{"verify", "commit"}) {
            Fixture f = new Fixture();f.statusReply = pendingSource();f.replies.add(busy());f.replies.add(success());
            f.dispatchNanos = java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
            Assert.assertTrue(f.run(action).get("success").getAsBoolean());
            Assert.assertEquals(2, f.sent.size());Assert.assertEquals(1, f.idleReads);Assert.assertEquals(1, f.statusReads);
            Assert.assertEquals(1, f.cancellationChecks);Assert.assertEquals("operation generation " + action, f.sent.get(0).getOperation());
            Assert.assertEquals(f.sent.get(0).getPayload(), f.sent.get(1).getPayload());
            Assert.assertEquals(72L, f.sent.get(1).getVmId());Assert.assertTrue(f.sent.get(1).getTimeoutSeconds() < 30);
        }
    }

    private static Fixture ownCompletedFinish() throws Exception {
        Fixture f = new Fixture();Mockito.when(f.operation.getState()).thenReturn("COMPLETE");
        Mockito.when(f.operation.getPhase()).thenReturn("COMPLETE");f.statusReply = committedTarget(true);
        JsonObject witness = new JsonObject();witness.addProperty("_targetNativeBootId", f.statusReply.get("bootId").getAsString());
        Mockito.when(f.operation.getResultJson()).thenReturn(witness.toString());return f;
    }

    @Test public void ownCompletedWriterFinishesOnlyItsExactTargetAfterClosedBusy() throws Exception {
        for (boolean pending : new boolean[]{true, false}) {
            Fixture f = ownCompletedFinish();f.statusReply = committedTarget(pending);f.replies.add(busy());f.replies.add(success());
            f.dispatchNanos = java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
            Assert.assertTrue(f.run("finish").get("success").getAsBoolean());Assert.assertEquals(2, f.sent.size());
            Assert.assertEquals(1, f.idleReads);Assert.assertEquals(1, f.statusReads);Assert.assertEquals(1, f.cancellationChecks);
            Assert.assertEquals("operation generation finish", f.sent.get(1).getOperation());
            Assert.assertEquals(f.sent.get(0).getPayload(), f.sent.get(1).getPayload());Assert.assertEquals(72L, f.sent.get(1).getVmId());
            Assert.assertTrue(f.sent.get(1).getTimeoutSeconds() < 30);
        }
    }

    @Test public void ownCompletedFinishCannotRetryOtherActionsTerminalStatesPhasesOrOwners() throws Exception {
        for (String action : new String[]{"begin", "verify", "commit", "rollback"}) {
            Fixture f = ownCompletedFinish();
            Assert.assertThrows(CloudRuntimeException.class, () -> f.run(action));Assert.assertEquals(1, f.sent.size());
        }
        for (int mode = 0; mode < 5; mode++) {
            Fixture f = ownCompletedFinish();
            if (mode == 0) Mockito.when(f.operation.getState()).thenReturn("ROLLED_BACK");
            if (mode == 1) Mockito.when(f.operation.getState()).thenReturn("CANCELLED");
            if (mode == 2) Mockito.when(f.operation.getPhase()).thenReturn("VERIFYING");
            if (mode == 3) f.owner(null);
            if (mode == 4) { StorageServiceOperationVO other = Mockito.mock(StorageServiceOperationVO.class);
                Mockito.when(other.getInstanceId()).thenReturn(72L);Mockito.when(other.getRevision()).thenReturn(9L);f.owner(other); }
            Assert.assertThrows(CloudRuntimeException.class, () -> f.run("finish"));Assert.assertEquals(1, f.sent.size());
        }
    }

    @Test public void ownCompletedFinishRejectsChangedOwnerOrImmutableScopeBeforeResend() throws Exception {
        for (int mode = 0; mode < 6; mode++) {
            Fixture f = ownCompletedFinish();final int change = mode;
            f.afterStatus = () -> {
                if (change == 0) Mockito.when(f.operation.getPhase()).thenReturn("PREPARED");
                if (change == 1) Mockito.when(f.operation.getState()).thenReturn("RECOVERY_REQUIRED");
                if (change == 2) Mockito.when(f.operation.getPreviousSnapshotJson()).thenReturn("{}");
                if (change == 3) Mockito.when(f.operation.getRevision()).thenReturn(10L);
                if (change == 4) Mockito.when(f.instance.getVmId()).thenReturn(73L);
                if (change == 5) { try { f.owner(null); } catch (Exception failure) { throw new AssertionError(failure); } }
            };
            Assert.assertThrows(CloudRuntimeException.class, () -> f.run("finish"));Assert.assertEquals(1, f.sent.size());
        }
    }

    @Test public void ownCompletedFinishRequiresExactCommittedTargetBootAndCancellation() throws Exception {
        for (int mode = 0; mode < 5; mode++) {
            Fixture f = ownCompletedFinish();
            if (mode == 0) f.statusReply.getAsJsonObject("generation").addProperty("configurationSha256", "c".repeat(64));
            if (mode == 1) f.statusReply.addProperty("pendingOperationUuid", "11111111-1111-4111-8111-111111111111");
            if (mode == 2) f.statusReply.addProperty("bootId", "44444444-4444-4444-8444-444444444444");
            if (mode == 3) f.statusReply.getAsJsonObject("generation").addProperty("revision", 8L);
            if (mode == 4) f.cancellationFailure = new CloudRuntimeException("cancelled");
            Assert.assertThrows(CloudRuntimeException.class, () -> f.run("finish"));Assert.assertEquals(1, f.sent.size());
        }
    }

    @Test public void ownCompletedFinishRetainsFourAttemptDeadlineAndUnknownOutcomeBounds() throws Exception {
        Fixture repeated = ownCompletedFinish();
        Assert.assertThrows(CloudRuntimeException.class, () -> repeated.run("finish"));Assert.assertEquals(4, repeated.sent.size());
        Fixture expired = ownCompletedFinish();expired.dispatchNanos = java.util.concurrent.TimeUnit.SECONDS.toNanos(31);
        Assert.assertThrows(CloudRuntimeException.class, () -> expired.run("finish"));Assert.assertEquals(1, expired.sent.size());
        Fixture unknown = ownCompletedFinish();unknown.transportFailure = new IllegalStateException("unknown");
        Assert.assertThrows(IllegalStateException.class, () -> unknown.run("finish"));Assert.assertEquals(1, unknown.sent.size());
        Fixture unclosed = ownCompletedFinish();
        unclosed.replies.add(new StorageServiceGuestCommandResult(false, "exit code 75", "{\"success\":false}"));
        Assert.assertThrows(CloudRuntimeException.class, () -> unclosed.run("finish"));Assert.assertEquals(1, unclosed.sent.size());
    }

    @Test public void finishRetriesOnlyOwnCommittedTargetIncludingExactAbsentPendingIdempotency() throws Exception {
        for (boolean pending : new boolean[]{true, false}) {
            Fixture f = new Fixture();f.statusReply = committedTarget(pending);f.replies.add(busy());f.replies.add(success());
            Assert.assertTrue(f.run("finish").get("success").getAsBoolean());
            Assert.assertEquals(2, f.sent.size());Assert.assertEquals(1, f.cancellationChecks);
            Assert.assertEquals(f.sent.get(0).getPayload(), f.sent.get(1).getPayload());
        }
        Fixture changed = new Fixture();changed.statusReply = committedTarget(true);
        changed.afterStatus = () -> { if (changed.statusReads > 1) changed.statusReply.getAsJsonObject("generation").addProperty("verifiedAt", 101.0); };
        Assert.assertThrows(CloudRuntimeException.class, () -> changed.run("finish"));
        Assert.assertEquals(2, changed.sent.size());
    }

    @Test public void lifecycleRejectsForeignAbsentMalformedPendingSourceAndAlreadyCommittedVerify() throws Exception {
        for (String action : new String[]{"verify", "commit"}) {
            for (int mode = 0; mode < 8; mode++) {
                Fixture f = new Fixture();f.statusReply = pendingSource();
                if (mode == 0) f.statusReply.add("pendingOperationUuid", JsonNull.INSTANCE);
                if (mode == 1) f.statusReply.addProperty("pendingOperationUuid", "foreign");
                if (mode == 2) f.statusReply.addProperty("pendingOperationUuid", false);
                if (mode == 3) f.statusReply.remove("generation");
                if (mode == 4) f.statusReply.getAsJsonObject("generation").addProperty("revision", 7L);
                if (mode == 5) f.statusReply.getAsJsonObject("generation").addProperty("configurationSha256", "c".repeat(64));
                if (mode == 6) f.statusReply = committedTarget(true);
                if (mode == 7) Mockito.when(f.operation.getPreviousSnapshotJson()).thenReturn("{}");
                Assert.assertThrows(CloudRuntimeException.class, () -> f.run(action));Assert.assertEquals(1, f.sent.size());
            }
        }
    }

    @Test public void finishRejectsForeignTargetDriftAndUnverifiedAbsentPending() throws Exception {
        for (int mode = 0; mode < 7; mode++) {
            Fixture f = new Fixture();f.statusReply = committedTarget(false);
            if (mode == 0) f.statusReply.getAsJsonObject("generation").addProperty("operationUuid", "foreign");
            if (mode == 1) f.statusReply.getAsJsonObject("generation").addProperty("revision", 8L);
            if (mode == 2) f.statusReply.addProperty("generationStatus", "UNVERIFIED");
            if (mode == 3) f.statusReply.addProperty("configurationSha256", "c".repeat(64));
            if (mode == 4) f.statusReply.addProperty("pendingOperationUuid", "foreign");
            if (mode == 5) f.statusReply.remove("pendingOperationUuid");
            if (mode == 6) f.statusReply.getAsJsonObject("generation").addProperty("instanceUuid", "foreign");
            Assert.assertThrows(CloudRuntimeException.class, () -> f.run("finish"));Assert.assertEquals(1, f.sent.size());
        }
    }

    @Test public void lifecycleBoundsAttemptsAndTimeAndNeverRetriesUnknownEffects() throws Exception {
        for (String action : new String[]{"verify", "commit", "finish"}) {
            Fixture repeated = new Fixture();repeated.statusReply = action.equals("finish") ? committedTarget(true) : pendingSource();
            Assert.assertThrows(CloudRuntimeException.class, () -> repeated.run(action));Assert.assertEquals(4, repeated.sent.size());
            Fixture expired = new Fixture();expired.statusReply = repeated.statusReply;expired.dispatchNanos = java.util.concurrent.TimeUnit.SECONDS.toNanos(31);
            Assert.assertThrows(CloudRuntimeException.class, () -> expired.run(action));Assert.assertEquals(1, expired.sent.size());Assert.assertEquals(0, expired.idleReads);
            Fixture unknown = new Fixture();unknown.transportFailure = new IllegalStateException("unknown");
            Assert.assertThrows(IllegalStateException.class, () -> unknown.run(action));Assert.assertEquals(1, unknown.sent.size());
            Fixture raw = new Fixture();raw.replies.add(new StorageServiceGuestCommandResult(false, "exit code 75", "{\"success\":false}"));
            Assert.assertThrows(CloudRuntimeException.class, () -> raw.run(action));Assert.assertEquals(1, raw.sent.size());Assert.assertEquals(0, raw.idleReads);
        }
    }

    @Test public void lifecycleCancellationOwnerMutationAndLateStatusPreventAnotherDispatch() throws Exception {
        for (String action : new String[]{"verify", "commit", "finish"}) {
            Fixture cancelled = new Fixture();cancelled.statusReply = action.equals("finish") ? committedTarget(true) : pendingSource();
            cancelled.cancellationFailure = new CloudRuntimeException("cancelled");
            Assert.assertThrows(CloudRuntimeException.class, () -> cancelled.run(action));Assert.assertEquals(1, cancelled.sent.size());
            Fixture changed = new Fixture();changed.statusReply = cancelled.statusReply;
            changed.afterStatus = () -> Mockito.when(changed.instance.getVmId()).thenReturn(73L);
            Assert.assertThrows(CloudRuntimeException.class, () -> changed.run(action));Assert.assertEquals(1, changed.sent.size());
            Fixture late = new Fixture();late.statusReply = cancelled.statusReply;late.readNanos = java.util.concurrent.TimeUnit.SECONDS.toNanos(16);
            Assert.assertThrows(CloudRuntimeException.class, () -> late.run(action));Assert.assertEquals(1, late.sent.size());
        }
    }

    @Test public void initialSourceRetryNeedsItsDurableEmptyCheckpointNotAnUnboundMissingSource() throws Exception {
        for (String action : new String[]{"verify", "commit"}) {
            Fixture f = new Fixture();JsonObject checkpoint = new JsonObject();checkpoint.add("previous", new JsonObject());
            JsonObject snapshot = new JsonObject();snapshot.add("nativeGeneration", checkpoint);
            Mockito.when(f.operation.getPreviousSnapshotJson()).thenReturn(snapshot.toString());
            f.statusReply = pendingSource();f.statusReply.add("generation", new JsonObject());f.replies.add(busy());f.replies.add(success());
            Assert.assertTrue(f.run(action).get("success").getAsBoolean());Assert.assertEquals(2, f.sent.size());
        }
    }

    private static StorageServiceOperationVO activeParent(Fixture f, long revision) throws Exception {
        StorageServiceOperationVO parent = Mockito.mock(StorageServiceOperationVO.class);
        Mockito.when(parent.getId()).thenReturn(1710L);Mockito.when(parent.getUuid()).thenReturn("22222222-2222-4222-8222-222222222222");
        Mockito.when(parent.getInstanceId()).thenReturn(72L);Mockito.when(parent.getRevision()).thenReturn(revision);
        Mockito.when(parent.getState()).thenReturn("RUNNING");Mockito.when(parent.getPhase()).thenReturn("PREPARED");
        Mockito.when(parent.getPreviousSnapshotJson()).thenReturn("{}");
        Mockito.when(f.operation.getState()).thenReturn("COMPLETE");Mockito.when(f.operation.getPhase()).thenReturn("COMPLETE");
        f.owner(parent);f.expectedCancellationOwner = parent;return parent;
    }

    @Test public void newWriterMayFinishOnlyItsExactOlderCompletedNativeTargetAndChecksParentCancellation() throws Exception {
        Fixture f = new Fixture();activeParent(f, 10L);f.statusReply = committedTarget(true);
        f.replies.add(busy());f.replies.add(success());Assert.assertTrue(f.run("finish").get("success").getAsBoolean());
        Assert.assertEquals(2, f.sent.size());Assert.assertEquals(1, f.cancellationChecks);
        Assert.assertTrue(f.sent.get(1).getPayload().contains(OPERATION));
        Fixture cancelled = new Fixture();activeParent(cancelled, 10L);cancelled.statusReply = committedTarget(true);
        cancelled.cancellationFailure = new CloudRuntimeException("parent cancelled");
        Assert.assertThrows(CloudRuntimeException.class, () -> cancelled.run("finish"));Assert.assertEquals(1, cancelled.sent.size());
    }

    @Test public void delegatedFinishNeverBorrowsForeignLowerOrMutatedParentOrDifferentCurrent() throws Exception {
        for (int mode = 0; mode < 6; mode++) {
            Fixture f = new Fixture();StorageServiceOperationVO parent = activeParent(f, mode == 0 ? 9L : 10L);
            f.statusReply = committedTarget(true);
            if (mode == 1) Mockito.when(parent.getInstanceId()).thenReturn(73L);
            if (mode == 2) Mockito.when(parent.getState()).thenReturn("COMPLETE");
            if (mode == 3) f.afterStatus = () -> Mockito.when(parent.getUuid()).thenReturn("foreign");
            if (mode == 4) f.afterStatus = () -> Mockito.when(parent.getPhase()).thenReturn("COMPLETE");
            if (mode == 5) f.statusReply.getAsJsonObject("generation").addProperty("operationUuid", "foreign");
            Assert.assertThrows(CloudRuntimeException.class, () -> f.run("finish"));Assert.assertEquals(1, f.sent.size());
        }
    }

    @Test public void lifecycleRejectsRebootMalformedBootAndChangedDurableTargetBoot() throws Exception {
        for (String action : new String[]{"begin", "verify", "commit", "finish"}) {
            Fixture changed = new Fixture();changed.statusReply = action.equals("finish") ? committedTarget(true) : action.equals("begin") ? settled() : pendingSource();
            changed.afterStatus = () -> { if (changed.statusReads > 1) changed.statusReply.addProperty("bootId", "44444444-4444-4444-8444-444444444444"); };
            Assert.assertThrows(CloudRuntimeException.class, () -> changed.run(action));Assert.assertEquals(2, changed.sent.size());
            Fixture witnessed = new Fixture();witnessed.statusReply = action.equals("finish") ? committedTarget(true) : action.equals("begin") ? settled() : pendingSource();
            JsonObject witness = new JsonObject();witness.addProperty("_targetNativeBootId", "44444444-4444-4444-8444-444444444444");
            Mockito.when(witnessed.operation.getResultJson()).thenReturn(witness.toString());
            Assert.assertThrows(CloudRuntimeException.class, () -> witnessed.run(action));Assert.assertEquals(1, witnessed.sent.size());
            Fixture missing = new Fixture();missing.statusReply = witnessed.statusReply.deepCopy();missing.statusReply.remove("bootId");
            Assert.assertThrows(CloudRuntimeException.class, () -> missing.run(action));Assert.assertEquals(1, missing.sent.size());
        }
    }

    @Test public void lifecycleNeverTreatsOverflowOrNoncanonicalSourcePointerAsNativeAuthority() throws Exception {
        Fixture overflow = new Fixture();overflow.statusReply = committedTarget(true);
        overflow.statusReply.getAsJsonObject("generation").addProperty("revision", new java.math.BigInteger("18446744073709551625"));
        Assert.assertThrows(CloudRuntimeException.class, () -> overflow.run("finish"));Assert.assertEquals(1, overflow.sent.size());
        Fixture malformed = new Fixture();JsonObject source = sourceGeneration();source.addProperty("operationUuid", "-".repeat(36));
        JsonObject checkpoint = new JsonObject();checkpoint.add("previous", source);JsonObject snapshot = new JsonObject();snapshot.add("nativeGeneration", checkpoint);
        Mockito.when(malformed.operation.getPreviousSnapshotJson()).thenReturn(snapshot.toString());
        malformed.statusReply = pendingSource();malformed.statusReply.add("generation", source);
        Assert.assertThrows(CloudRuntimeException.class, () -> malformed.run("commit"));Assert.assertEquals(1, malformed.sent.size());
    }

}
