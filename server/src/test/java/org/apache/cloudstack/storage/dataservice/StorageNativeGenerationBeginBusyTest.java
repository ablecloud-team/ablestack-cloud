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
        value.add("pendingOperationUuid", JsonNull.INSTANCE);
        return value;
    }

    private static class Fixture extends StorageServiceManagerImpl {
        final StorageServiceInstanceVO instance = Mockito.mock(StorageServiceInstanceVO.class);
        final StorageServiceOperationVO operation = Mockito.mock(StorageServiceOperationVO.class);
        final java.util.List<StorageServiceGuestCommand> sent = new java.util.ArrayList<>();
        final java.util.Deque<StorageServiceGuestCommandResult> replies = new java.util.ArrayDeque<>();
        JsonObject idleReply = idle(), statusReply = settled();
        long now = 1_000_000L, dispatchNanos, readNanos;
        int idleReads, statusReads, cancellationChecks;
        Runnable afterDispatch, afterIdle, afterStatus;
        RuntimeException transportFailure, readFailure, cancellationFailure;

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
            Mockito.when(operation.getPreviousSnapshotJson()).thenReturn("{\"frozenSource\":true}");
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
            now += readNanos;
            if (readFailure != null) throw readFailure;
            if ("operation writer-idle".equals(action)) {
                idleReads++;if (afterIdle != null) afterIdle.run();return idleReply;
            }
            Assert.assertEquals("operation generation status", action);
            statusReads++;if (afterStatus != null) afterStatus.run();return statusReply;
        }
        @Override protected void requireNoOperationCancellation(StorageServiceOperationVO value) {
            Assert.assertSame(operation, value);cancellationChecks++;
            if (cancellationFailure != null) throw cancellationFailure;
        }
        JsonObject run(String action) { return nativeConfigurationGeneration(instance, operation, action); }
        long beginCount() { return sent.stream().filter(x -> "operation generation begin".equals(x.getOperation())).count(); }
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

    @Test public void verifyCommitRollbackAndOrdinarySuccessRemainSingleDispatch() throws Exception {
        for (String action : new String[]{"verify", "commit", "rollback", "finish", "status"}) {
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
}
