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
import com.google.gson.JsonObject;
import org.junit.Assert;
import org.junit.Test;
import com.cloud.utils.exception.CloudRuntimeException;
public class StorageFormatterLifecycleGateTest {
    private static JsonObject state(String phase,boolean active){JsonObject result=new JsonObject();result.addProperty("success",true);result.addProperty("status",phase);result.addProperty("formatterActive",active);JsonObject journal=new JsonObject();journal.addProperty("formatStarted",true);journal.addProperty("phase",phase);journal.addProperty("filesystemUuid","partial-header-uuid");result.add("operation",journal);return result;}
    @Test public void rawIdleCannotApproveOrphanOrPartialFormatterWithAHeaderUuid(){Assert.assertThrows(CloudRuntimeException.class,()->StorageFormatterLifecycleGate.requireIdle(state("TIMED_OUT_PENDING_RECONCILE",false)));Assert.assertThrows(CloudRuntimeException.class,()->StorageFormatterLifecycleGate.requireIdle(state("FORMATTING",false)));Assert.assertThrows(CloudRuntimeException.class,()->StorageFormatterLifecycleGate.requireIdle(state("COMPLETE",true)));}
    @Test public void knownInactiveCompleteJournalPermitsExistingMountLifecycleButUnknownDoesNot(){StorageFormatterLifecycleGate.requireIdle(state("COMPLETE",false));JsonObject unknown=state("COMPLETE",false);unknown.remove("formatterActive");Assert.assertThrows(CloudRuntimeException.class,()->StorageFormatterLifecycleGate.requireIdle(unknown));JsonObject blocked=state("COMPLETE",false);blocked.addProperty("terminationPending",true);Assert.assertThrows(CloudRuntimeException.class,()->StorageFormatterLifecycleGate.requireIdle(blocked));}
    @Test public void explicitActiveOrTerminatingFormatterOverridesNotStartedButLegacyAbsenceRemainsReadable() {
        JsonObject value=new JsonObject();value.addProperty("success",true);value.addProperty("status","NOT_STARTED");
        StorageFormatterLifecycleGate.requireIdle(value);
        value.addProperty("formatterActive",true);
        Assert.assertThrows(CloudRuntimeException.class,()->StorageFormatterLifecycleGate.requireIdle(value));
        value.remove("formatterActive");value.addProperty("terminationPending",true);
        Assert.assertThrows(CloudRuntimeException.class,()->StorageFormatterLifecycleGate.requireIdle(value));
    }


    private static JsonObject nativeIdle(boolean idle) {
        JsonObject value = new JsonObject();value.addProperty("success", true);
        value.addProperty("status", idle ? "WRITER_IDLE" : "WRITER_BUSY");value.addProperty("writerIdle", idle);
        value.addProperty("writerLockSupported", true);value.addProperty("bootChildActive", false);value.addProperty("bootRecoveryRequired", false);
        return value;
    }

    private static class RollbackFixture extends StorageServiceManagerImpl {
        final java.util.Deque<JsonObject> replies = new java.util.ArrayDeque<>();
        final StorageServiceInstanceVO instance = org.mockito.Mockito.mock(StorageServiceInstanceVO.class);
        long now = 1_000_000;int probes;int pauses;int formatterChecks;int identityChecks;
        boolean late;boolean interruptWait;boolean interruptReply;RuntimeException transportError;RuntimeException identityError;
        JsonObject formatter = new JsonObject();

        RollbackFixture(JsonObject... observations) throws Exception {
            replies.addAll(java.util.Arrays.asList(observations));
            org.mockito.Mockito.when(instance.getVmId()).thenReturn(54L);
            com.cloud.storage.dao.VolumeDao dao = org.mockito.Mockito.mock(com.cloud.storage.dao.VolumeDao.class);
            com.cloud.storage.VolumeVO volume = org.mockito.Mockito.mock(com.cloud.storage.VolumeVO.class);
            org.mockito.Mockito.when(volume.getUuid()).thenReturn("abcdef22-2222-4222-8222-222222222222");
            org.mockito.Mockito.when(dao.findByInstanceAndType(54L, com.cloud.storage.Volume.Type.DATADISK))
                    .thenReturn(java.util.Collections.singletonList(volume));
            java.lang.reflect.Field field = StorageServiceManagerImpl.class.getDeclaredField("volumeDao");
            field.setAccessible(true);field.set(this, dao);
            formatter.addProperty("success", true);formatter.addProperty("status", "NOT_STARTED");formatter.addProperty("formatterActive", false);
        }

        @Override protected JsonObject rootGuest(StorageServiceInstanceVO scope, String command, JsonObject request, int timeout) {
            Assert.assertSame(instance, scope);
            if ("operation writer-idle".equals(command)) {
                probes++;Assert.assertTrue(timeout >= 1 && timeout <= 15);
                if (transportError != null) throw transportError;
                if (interruptReply) Thread.currentThread().interrupt();
                if (late) now += java.util.concurrent.TimeUnit.SECONDS.toNanos(16);
                return replies.isEmpty() ? nativeIdle(false) : replies.removeFirst();
            }
            Assert.assertEquals("volume operation status", command);formatterChecks++;
            Assert.assertEquals("abcdef22-2222-4222-8222-222222222222", request.get("volumeUuid").getAsString());
            return formatter;
        }

        // The test clock avoids real sleeps; the production implementation uses System.nanoTime.
        protected long rollbackNativeWriterNowNanos() { return now; }
        protected void pauseRollbackNativeWriter(long milliseconds) {
            pauses++;
            if (interruptWait) {
                Thread.currentThread().interrupt();
                throw new CloudRuntimeException("Native writer rollback wait was interrupted; rollback is deferred");
            }
            now += java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(milliseconds);
        }

        @Override protected void requireIdentityRollbackSafe(StorageServiceInstanceVO scope, StorageServiceOperationVO operation) {
            identityChecks++;if (identityError != null) throw identityError;
            super.requireIdentityRollbackSafe(scope, operation);
        }

        void actualRuntimeGuard() throws Exception {
            Class<?> selected = null;
            for (int i = 1; i <= 32; i++) {
                try {
                    Class<?> candidate = Class.forName(StorageServiceManagerImpl.class.getName() + "$" + i, false,
                            StorageServiceManagerImpl.class.getClassLoader());
                    if (DesiredStateChange.Runtime.class.isAssignableFrom(candidate)) {
                        Assert.assertNull("Exactly one actual desired-change runtime", selected);selected = candidate;
                    }
                } catch (ClassNotFoundException absent) { /* Bounded compiler-generated class inventory. */ }
            }
            Assert.assertNotNull(selected);
            java.lang.reflect.Constructor<?> constructor = selected.getDeclaredConstructors()[0];constructor.setAccessible(true);
            DesiredStateChange.Runtime runtime = (DesiredStateChange.Runtime) constructor.newInstance(this, instance, null,
                    StorageServiceInstance.Protocol.NFS, 9L, false);
            runtime.ensureRollbackSafe();
        }
    }

    @Test public void finiteNativeWriterBusyWaitsForIdleThenRunsOriginalSafetyProofs() throws Exception {
        RollbackFixture value = new RollbackFixture(nativeIdle(false), nativeIdle(false), nativeIdle(true));
        value.actualRuntimeGuard();
        Assert.assertEquals(3, value.probes);Assert.assertEquals(2, value.pauses);
        Assert.assertEquals(1, value.formatterChecks);Assert.assertEquals(1, value.identityChecks);
    }

    @Test public void alreadyIdleDoesNotPauseAndKeepsFormatterAndIdentityGuards() throws Exception {
        RollbackFixture value = new RollbackFixture(nativeIdle(true));value.actualRuntimeGuard();
        Assert.assertEquals(1, value.probes);Assert.assertEquals(0, value.pauses);
        Assert.assertEquals(1, value.formatterChecks);Assert.assertEquals(1, value.identityChecks);
    }

    @Test public void permanentBusyEndsAtOneBudgetWithoutClaimingAFormatterOrRunningRollbackProofs() throws Exception {
        RollbackFixture value = new RollbackFixture();
        CloudRuntimeException error = Assert.assertThrows(CloudRuntimeException.class, value::actualRuntimeGuard);
        Assert.assertTrue(error.getMessage().contains("writer"));Assert.assertFalse(error.getMessage().contains("formatter"));
        Assert.assertTrue(value.probes > 1 && value.probes <= 151);
        Assert.assertTrue(value.now <= java.util.concurrent.TimeUnit.SECONDS.toNanos(15) + 1_000_000);
        Assert.assertEquals(0, value.formatterChecks);Assert.assertEquals(0, value.identityChecks);
    }

    @Test public void malformedUnknownOrUnsupportedIdleProofIsNeverReobservedOrTrusted() throws Exception {
        java.util.List<JsonObject> observations = new java.util.ArrayList<>();
        for (String field : new String[]{"success", "writerLockSupported", "bootChildActive", "bootRecoveryRequired", "writerIdle"}) {
            JsonObject missing = nativeIdle(true);missing.remove(field);observations.add(missing);
            JsonObject string = nativeIdle(true);string.addProperty(field, "true");observations.add(string);
        }
        JsonObject mismatch = nativeIdle(true);mismatch.addProperty("writerIdle", false);observations.add(mismatch);
        JsonObject unsupported = nativeIdle(true);unsupported.addProperty("writerLockSupported", false);observations.add(unsupported);
        JsonObject failed = nativeIdle(true);failed.addProperty("success", false);observations.add(failed);
        JsonObject unknown = nativeIdle(true);unknown.addProperty("status", "UNKNOWN");observations.add(unknown);
        JsonObject extra = nativeIdle(true);extra.addProperty("credentialPrivateKey", "PRIVATE_SENTINEL");observations.add(extra);
        for (JsonObject proof : observations) {
            RollbackFixture value = new RollbackFixture(proof);
            CloudRuntimeException error = Assert.assertThrows(CloudRuntimeException.class, value::actualRuntimeGuard);
            Assert.assertFalse(error.getMessage().contains("PRIVATE_SENTINEL"));
            Assert.assertEquals(1, value.probes);Assert.assertEquals(0, value.pauses);
            Assert.assertEquals(0, value.formatterChecks);Assert.assertEquals(0, value.identityChecks);
        }
    }

    @Test public void anyBootChildOrRecoveryRequiresImmediateForwardRecovery() throws Exception {
        for (String field : new String[]{"bootChildActive", "bootRecoveryRequired"}) {
            JsonObject proof = nativeIdle(false);proof.addProperty(field, true);
            RollbackFixture value = new RollbackFixture(proof);
            CloudRuntimeException error = Assert.assertThrows(CloudRuntimeException.class, value::actualRuntimeGuard);
            Assert.assertTrue(error.getMessage().contains("BOOT"));
            Assert.assertEquals(1, value.probes);Assert.assertEquals(0, value.pauses);
            Assert.assertEquals(0, value.formatterChecks);Assert.assertEquals(0, value.identityChecks);
        }
    }

    @Test public void transportFailureIsNotAWriterBusyRetry() throws Exception {
        RollbackFixture value = new RollbackFixture();value.transportError = new CloudRuntimeException("Transport unavailable");
        Assert.assertSame(value.transportError, Assert.assertThrows(CloudRuntimeException.class, value::actualRuntimeGuard));
        Assert.assertEquals(1, value.probes);Assert.assertEquals(0, value.pauses);Assert.assertEquals(0, value.formatterChecks);
    }

    @Test public void actualActiveOrIncompleteFormatterStillBlocksAfterFinalIdle() throws Exception {
        for (String phase : new String[]{"FORMATTING", "TIMED_OUT_PENDING_RECONCILE"}) {
            RollbackFixture value = new RollbackFixture(nativeIdle(false), nativeIdle(true));
            value.formatter = state(phase, "FORMATTING".equals(phase));
            Assert.assertThrows(CloudRuntimeException.class, value::actualRuntimeGuard);
            Assert.assertEquals(2, value.probes);Assert.assertEquals(1, value.formatterChecks);Assert.assertEquals(0, value.identityChecks);
        }
    }

    @Test public void originalIdentitySafetyFailureRemainsBlockingAfterIdle() throws Exception {
        RollbackFixture value = new RollbackFixture(nativeIdle(true));value.identityError = new CloudRuntimeException("Owned identity requires recovery");
        Assert.assertSame(value.identityError, Assert.assertThrows(CloudRuntimeException.class, value::actualRuntimeGuard));
        Assert.assertEquals(1, value.formatterChecks);Assert.assertEquals(1, value.identityChecks);
    }

    @Test public void interruptionDoesNotRetryOrConsumeAnySafetyAuthority() throws Exception {
        RollbackFixture value = new RollbackFixture();value.interruptWait = true;
        try {
            CloudRuntimeException error = Assert.assertThrows(CloudRuntimeException.class, value::actualRuntimeGuard);
            Assert.assertTrue(error.getMessage().contains("interrupted"));Assert.assertTrue(Thread.currentThread().isInterrupted());
            Assert.assertEquals(1, value.probes);Assert.assertEquals(0, value.formatterChecks);
        } finally {Thread.interrupted();}
    }

    @Test public void idleReplyAfterTheOriginalBudgetCannotAuthorizeRollback() throws Exception {
        RollbackFixture value = new RollbackFixture(nativeIdle(true));value.late = true;
        Assert.assertThrows(CloudRuntimeException.class, value::actualRuntimeGuard);
        Assert.assertEquals(1, value.probes);Assert.assertEquals(0, value.formatterChecks);Assert.assertEquals(0, value.identityChecks);
    }

    @Test public void interruptionDuringIdleRpcCannotAuthorizeRollback() throws Exception {
        RollbackFixture value = new RollbackFixture(nativeIdle(true));value.interruptReply = true;
        try {
            Assert.assertThrows(CloudRuntimeException.class, value::actualRuntimeGuard);
            Assert.assertTrue(Thread.currentThread().isInterrupted());
            Assert.assertEquals(1, value.probes);Assert.assertEquals(0, value.formatterChecks);Assert.assertEquals(0, value.identityChecks);
        } finally {Thread.interrupted();}
    }

    private static StorageServiceGuestCommandResult busy() {
        return new StorageServiceGuestCommandResult(false, "masked exit 75", "{\"success\":false,\"errorCode\":\"STORAGE_WRITER_BUSY\"}");
    }

    private static final class PlainFixture extends RollbackFixture {
        final java.util.List<StorageServiceGuestCommand> dispatched = new java.util.ArrayList<>();
        final java.util.Deque<StorageServiceGuestCommandResult> commandReplies = new java.util.ArrayDeque<>();
        final StorageServiceOperationVO operation = org.mockito.Mockito.mock(StorageServiceOperationVO.class);
        final java.util.Set<String> masks = new java.util.HashSet<>(java.util.Arrays.asList("password"));
        Runnable afterDispatch;Runnable afterIdle;long dispatchNanos;
        RuntimeException commandError;RuntimeException controlError;int controlChecks;int clockReads;int advanceAtRead;long advanceNanos;

        PlainFixture(JsonObject... idleReplies) throws Exception {
            super(idleReplies);
            org.mockito.Mockito.when(instance.getId()).thenReturn(9L);
            org.mockito.Mockito.when(operation.getId()).thenReturn(42L);
            org.mockito.Mockito.when(operation.getInstanceId()).thenReturn(9L);
            org.mockito.Mockito.when(operation.getUuid()).thenReturn("11111111-1111-4111-8111-111111111111");
            org.mockito.Mockito.when(operation.getRevision()).thenReturn(73L);
            org.mockito.Mockito.when(operation.getPhase()).thenReturn("APPLYING");
            org.mockito.Mockito.when(operation.getState()).thenReturn("RUNNING");
            setOwned(operation);
            java.lang.reflect.Field field = StorageServiceManagerImpl.class.getDeclaredField("guestCommandDispatcher");
            field.setAccessible(true);field.set(this, (StorageServiceGuestCommandDispatcher) command -> {
                dispatched.add(command);now += dispatchNanos;
                if (afterDispatch != null) afterDispatch.run();
                if (commandError != null) throw commandError;
                return commandReplies.isEmpty() ? busy() : commandReplies.removeFirst();
            });
        }

        @SuppressWarnings("unchecked")
        void setOwned(StorageServiceOperationVO value) throws Exception {
            java.lang.reflect.Field field = StorageServiceManagerImpl.class.getDeclaredField("storageWriterOperation");
            field.setAccessible(true);((ThreadLocal<StorageServiceOperationVO>) field.get(this)).set(value);
        }

        @Override protected JsonObject rootGuest(StorageServiceInstanceVO scope, String command, JsonObject payload, int timeout) {
            JsonObject result = super.rootGuest(scope, command, payload, timeout);
            if ("operation writer-idle".equals(command) && afterIdle != null) afterIdle.run();
            return result;
        }

        @Override protected long rollbackNativeWriterNowNanos() {
            clockReads++;
            if (clockReads == advanceAtRead) now += advanceNanos;
            return now;
        }

        @Override protected void requireNoOperationCancellation(StorageServiceOperationVO owner) {
            Assert.assertSame(operation, owner);controlChecks++;
            if (controlError != null) throw controlError;
        }

        StorageServiceGuestCommandResult apply(String command, int timeout) {
            return dispatchPlainProtocolApply(instance, new StorageServiceGuestCommand(54L, command,
                    "{\"testOnly\":true}", timeout, masks));
        }
    }

    @Test public void fourPlainBusyCommandsRetryOnlyAfterFreshIdleAndKeepImmutableWireScope() throws Exception {
        for (String command : new String[]{"nfs export apply", "smb share apply", "iscsi target apply", "nvmeof subsystem apply"}) {
            PlainFixture value = new PlainFixture(nativeIdle(false), nativeIdle(true));
            StorageServiceGuestCommandResult success = new StorageServiceGuestCommandResult(true, "", "{}");
            value.commandReplies.add(busy());value.commandReplies.add(success);
            value.dispatchNanos = java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
            value.afterDispatch = () -> value.masks.add("late-mask");
            Assert.assertSame(success, value.apply(command, 120));
            Assert.assertEquals(2, value.dispatched.size());Assert.assertEquals(2, value.probes);
            Assert.assertEquals(0, value.formatterChecks);Assert.assertEquals(0, value.identityChecks);Assert.assertEquals(1, value.controlChecks);
            for (StorageServiceGuestCommand sent : value.dispatched) {
                Assert.assertEquals(54L, sent.getVmId());Assert.assertEquals(command, sent.getOperation());
                Assert.assertEquals("{\"testOnly\":true}", sent.getPayload());
                Assert.assertEquals(java.util.Collections.singleton("password"), sent.getMaskedFields());
            }
            Assert.assertEquals(120, value.dispatched.get(0).getTimeoutSeconds());
            Assert.assertTrue(value.dispatched.get(1).getTimeoutSeconds() < 120);
        }
    }

    @Test public void rollbackAndInterruptedRecoveryBusyRetriesRetainOriginalSafetyGuards() throws Exception {
        for (String phase : new String[]{"ROLLING_BACK", "RECOVERING_INTERRUPTED_WRITER"}) {
            PlainFixture value = new PlainFixture(nativeIdle(true));
            org.mockito.Mockito.when(value.operation.getPhase()).thenReturn(phase);
            StorageServiceGuestCommandResult success = new StorageServiceGuestCommandResult(true, "", "{}");
            value.commandReplies.add(busy());value.commandReplies.add(success);
            Assert.assertSame(success, value.apply("smb share apply", 120));
            Assert.assertEquals(1, value.formatterChecks);Assert.assertEquals(1, value.identityChecks);Assert.assertEquals(0, value.controlChecks);
        }
    }

    @Test public void malformedOrAbsentBusyJsonAndMaskedExit75NeverRetry() throws Exception {
        for (String raw : new String[]{null, "", "{}", "{\"success\":false}", "{\"success\":\"false\",\"errorCode\":\"STORAGE_WRITER_BUSY\"}",
                "{\"success\":false,\"errorCode\":\"OTHER\"}", "{\"success\":false,\"errorCode\":\"STORAGE_WRITER_BUSY\",\"extra\":1}",
                "{\"success\":true,\"success\":false,\"errorCode\":\"STORAGE_WRITER_BUSY\"}",
                "{\"success\":false,\"errorCode\":\"STORAGE_WRITER_BUSY\"}{}",
                "{\"success\":false,\"errorCode\":\"STORAGE_WRITER_BUSY\"/*comment*/}", " ".repeat(1025)}) {
            PlainFixture value = new PlainFixture();StorageServiceGuestCommandResult failure = new StorageServiceGuestCommandResult(false, "masked exit 75", raw);
            value.commandReplies.add(failure);Assert.assertSame(failure, value.apply("smb share apply", 120));
            Assert.assertEquals(1, value.dispatched.size());Assert.assertEquals(0, value.probes);
        }
        PlainFixture value = new PlainFixture();StorageServiceGuestCommandResult success = new StorageServiceGuestCommandResult(true, "", busy().getResultJson());
        value.commandReplies.add(success);Assert.assertSame(success, value.apply("smb share apply", 120));
        Assert.assertEquals(1, value.dispatched.size());Assert.assertEquals(0, value.probes);
    }

    @Test public void protectedCapsuleRenderedAndIdentityCommandsAreNeverRetried() throws Exception {
        for (String command : new String[]{"identity capsule import", "smb identity local-source-status", "operation generation render-activate", "nfs export apply extra"}) {
            PlainFixture value = new PlainFixture();StorageServiceGuestCommandResult failure = busy();value.commandReplies.add(failure);
            Assert.assertSame(failure, value.apply(command, 120));Assert.assertEquals(1, value.dispatched.size());Assert.assertEquals(0, value.probes);
        }
    }

    @Test public void bootUnknownMalformedOrFailedIdleAfterBusyCannotResend() throws Exception {
        java.util.List<JsonObject> replies = new java.util.ArrayList<>();
        JsonObject boot = nativeIdle(false);boot.addProperty("bootChildActive", true);replies.add(boot);
        JsonObject recovery = nativeIdle(false);recovery.addProperty("bootRecoveryRequired", true);replies.add(recovery);
        JsonObject malformed = nativeIdle(true);malformed.remove("writerIdle");replies.add(malformed);
        JsonObject unknown = nativeIdle(true);unknown.addProperty("status", "UNKNOWN");replies.add(unknown);
        JsonObject failed = nativeIdle(true);failed.addProperty("success", false);replies.add(failed);
        for (JsonObject reply : replies) {
            PlainFixture value = new PlainFixture(reply);
            Assert.assertThrows(CloudRuntimeException.class, () -> value.apply("nfs export apply", 120));
            Assert.assertEquals(1, value.dispatched.size());Assert.assertEquals(1, value.probes);
        }
    }

    @Test public void lostOrMutatedOwnedScopePreventsSecondHandlerCall() throws Exception {
        for (int mode = 0; mode < 10; mode++) {
            PlainFixture value = new PlainFixture(nativeIdle(true));final int selected = mode;
            value.afterIdle = () -> {
                if (selected == 0) org.mockito.Mockito.when(value.operation.getRevision()).thenReturn(74L);
                if (selected == 1) org.mockito.Mockito.when(value.operation.getUuid()).thenReturn("22222222-2222-4222-8222-222222222222");
                if (selected == 2) org.mockito.Mockito.when(value.operation.getInstanceId()).thenReturn(10L);
                if (selected == 3) org.mockito.Mockito.when(value.instance.getVmId()).thenReturn(55L);
                if (selected == 4) org.mockito.Mockito.when(value.operation.getPhase()).thenReturn("COMPLETE");
                if (selected == 5) org.mockito.Mockito.when(value.operation.getState()).thenReturn("COMPLETE");
                if (selected == 6) {try {value.setOwned(null);} catch (Exception failure) {throw new AssertionError(failure);}}
                if (selected == 7) org.mockito.Mockito.when(value.operation.getPreviousSnapshotJson()).thenReturn("{\"changed\":true}");
                if (selected == 8) org.mockito.Mockito.when(value.operation.getId()).thenReturn(43L);
                if (selected == 9) org.mockito.Mockito.when(value.instance.getId()).thenReturn(10L);
            };
            Assert.assertThrows(CloudRuntimeException.class, () -> value.apply("nfs export apply", 120));
            Assert.assertEquals(1, value.dispatched.size());
        }
    }

    @Test public void missingOrIneligibleOwnedWriterDoesNotGainRetryAuthority() throws Exception {
        for (int mode = 0; mode < 4; mode++) {
            PlainFixture value = new PlainFixture();
            if (mode == 0) value.setOwned(null);
            if (mode == 1) org.mockito.Mockito.when(value.operation.getPhase()).thenReturn("COMPLETE");
            if (mode == 2) org.mockito.Mockito.when(value.operation.getInstanceId()).thenReturn(10L);
            if (mode == 3) org.mockito.Mockito.when(value.operation.getPhase()).thenReturn(null);
            StorageServiceGuestCommandResult failure = busy();value.commandReplies.add(failure);
            Assert.assertSame(failure, value.apply("nfs export apply", 120));
            Assert.assertEquals(1, value.dispatched.size());Assert.assertEquals(0, value.probes);
        }
    }

    @Test public void formatterOrIdentityFailureInRecoveryStillPreventsResend() throws Exception {
        for (int mode = 0; mode < 2; mode++) {
            PlainFixture value = new PlainFixture(nativeIdle(true));
            org.mockito.Mockito.when(value.operation.getPhase()).thenReturn("ROLLING_BACK");
            if (mode == 0) value.formatter = state("FORMATTING", true);
            if (mode == 1) value.identityError = new CloudRuntimeException("Identity requires recovery");
            Assert.assertThrows(CloudRuntimeException.class, () -> value.apply("smb share apply", 120));
            Assert.assertEquals(1, value.dispatched.size());
        }
    }

    @Test public void permanentBusyCannotExtendFifteenSecondWindowOrOriginalCommandDeadline() throws Exception {
        for (int timeout : new int[]{3, 120}) {
            PlainFixture value = new PlainFixture();
            Assert.assertThrows(CloudRuntimeException.class, () -> value.apply("nfs export apply", timeout));
            Assert.assertEquals(1, value.dispatched.size());Assert.assertTrue(value.probes > 1);
            Assert.assertTrue(value.now <= java.util.concurrent.TimeUnit.SECONDS.toNanos(Math.min(15, timeout)) + 1_000_000);
        }
    }

    @Test public void repeatedBusyAfterIdleIsBoundedWithoutResettingCommandBudget() throws Exception {
        PlainFixture value = new PlainFixture(nativeIdle(true), nativeIdle(true), nativeIdle(true));
        value.dispatchNanos = java.util.concurrent.TimeUnit.SECONDS.toNanos(4);
        Assert.assertThrows(CloudRuntimeException.class, () -> value.apply("nfs export apply", 10));
        Assert.assertEquals(3, value.dispatched.size());
        Assert.assertEquals(10, value.dispatched.get(0).getTimeoutSeconds());
        Assert.assertEquals(6, value.dispatched.get(1).getTimeoutSeconds());
        Assert.assertEquals(2, value.dispatched.get(2).getTimeoutSeconds());
    }

    @Test public void nonBusyFailureOrTransportExceptionAfterFirstAttemptIsNeverRetried() throws Exception {
        PlainFixture value = new PlainFixture();value.commandError = new CloudRuntimeException("Transport failure");
        Assert.assertSame(value.commandError, Assert.assertThrows(CloudRuntimeException.class, () -> value.apply("nfs export apply", 120)));
        Assert.assertEquals(1, value.dispatched.size());Assert.assertEquals(0, value.probes);
        PlainFixture second = new PlainFixture(nativeIdle(true));
        StorageServiceGuestCommandResult failure = new StorageServiceGuestCommandResult(false, "handler failed", "{\"success\":false,\"errorCode\":\"HANDLER_FAILED\"}");
        second.commandReplies.add(busy());second.commandReplies.add(failure);
        Assert.assertSame(failure, second.apply("nfs export apply", 120));
        Assert.assertEquals(2, second.dispatched.size());Assert.assertEquals(1, second.probes);
    }

    @Test public void interruptDuringFreshIdleAfterBusyPreventsSecondDispatch() throws Exception {
        PlainFixture value = new PlainFixture(nativeIdle(true));value.interruptReply = true;
        try {
            Assert.assertThrows(CloudRuntimeException.class, () -> value.apply("nfs export apply", 120));
            Assert.assertTrue(Thread.currentThread().isInterrupted());Assert.assertEquals(1, value.dispatched.size());
        } finally {Thread.interrupted();}
    }

    @Test public void everyBusyResponseNeedsANewBootFreeIdleProofBeforeAnotherDispatch() throws Exception {
        JsonObject boot = nativeIdle(false);boot.addProperty("bootRecoveryRequired", true);
        PlainFixture value = new PlainFixture(nativeIdle(true), boot);
        Assert.assertThrows(CloudRuntimeException.class, () -> value.apply("smb share apply", 120));
        Assert.assertEquals(2, value.dispatched.size());Assert.assertEquals(2, value.probes);Assert.assertEquals(1, value.controlChecks);
    }

    @Test public void forwardCancellationOrLostHeartbeatBeforeRetryDoesNotDispatchAgain() throws Exception {
        PlainFixture value = new PlainFixture(nativeIdle(true));
        value.controlError = new CloudRuntimeException("Existing operation control is unavailable");
        Assert.assertSame(value.controlError, Assert.assertThrows(CloudRuntimeException.class, () -> value.apply("nfs export apply", 120)));
        Assert.assertEquals(1, value.dispatched.size());Assert.assertEquals(1, value.controlChecks);
    }

    @Test public void reversedClosedBusyFieldsAreAcceptedButNonBooleanOrDuplicateCodeIsNot() throws Exception {
        PlainFixture value = new PlainFixture(nativeIdle(true));
        value.commandReplies.add(new StorageServiceGuestCommandResult(false, "", "{\"errorCode\":\"STORAGE_WRITER_BUSY\",\"success\":false}"));
        StorageServiceGuestCommandResult success = new StorageServiceGuestCommandResult(true, "", "{}");value.commandReplies.add(success);
        Assert.assertSame(success, value.apply("nfs export apply", 120));Assert.assertEquals(2, value.dispatched.size());
        for (String raw : new String[]{"{\"success\":0,\"errorCode\":\"STORAGE_WRITER_BUSY\"}",
                "{\"success\":false,\"errorCode\":\"OTHER\",\"errorCode\":\"STORAGE_WRITER_BUSY\"}"}) {
            PlainFixture rejected = new PlainFixture();StorageServiceGuestCommandResult failed = new StorageServiceGuestCommandResult(false, "", raw);
            rejected.commandReplies.add(failed);Assert.assertSame(failed, rejected.apply("nfs export apply", 120));
            Assert.assertEquals(1, rejected.dispatched.size());Assert.assertEquals(0, rejected.probes);
        }
    }

    @Test public void completedSafetyObservationCannotResetTheBusyOrCommandBudget() throws Exception {
        PlainFixture value = new PlainFixture(nativeIdle(true));value.late = true;
        Assert.assertThrows(CloudRuntimeException.class, () -> value.apply("smb share apply", 120));
        Assert.assertEquals(1, value.dispatched.size());Assert.assertEquals(0, value.controlChecks);
    }

    @Test public void terminalOrMissingOwnerStateCannotResendDespiteAnOldAllowedPhase() throws Exception {
        for (String phase : new String[]{"APPLYING", "ROLLING_BACK", "RECOVERING_INTERRUPTED_WRITER"}) {
            for (String terminal : new String[]{"COMPLETE", "ROLLED_BACK", "BLOCKED", null}) {
                PlainFixture value = new PlainFixture(nativeIdle(true));
                org.mockito.Mockito.when(value.operation.getPhase()).thenReturn(phase);
                org.mockito.Mockito.when(value.operation.getState()).thenReturn(terminal);
                StorageServiceGuestCommandResult failure = busy();value.commandReplies.add(failure);
                Assert.assertSame(failure, value.apply("nfs export apply", 120));
                Assert.assertEquals(1, value.dispatched.size());Assert.assertEquals(0, value.probes);
            }
        }
        for (String phase : new String[]{"APPLYING", "ROLLING_BACK"}) {
            PlainFixture value = new PlainFixture();org.mockito.Mockito.when(value.operation.getPhase()).thenReturn(phase);
            org.mockito.Mockito.when(value.operation.getState()).thenReturn("RECOVERY_REQUIRED");
            StorageServiceGuestCommandResult failure = busy();value.commandReplies.add(failure);
            Assert.assertSame(failure, value.apply("nfs export apply", 120));
            Assert.assertEquals(1, value.dispatched.size());Assert.assertEquals(0, value.probes);
        }
    }

    @Test public void originalRecoveryRequiredStateCanStillUseItsOwnedInterruptedRecovery() throws Exception {
        PlainFixture value = new PlainFixture(nativeIdle(true));
        org.mockito.Mockito.when(value.operation.getPhase()).thenReturn("RECOVERING_INTERRUPTED_WRITER");
        org.mockito.Mockito.when(value.operation.getState()).thenReturn("RECOVERY_REQUIRED");
        StorageServiceGuestCommandResult success = new StorageServiceGuestCommandResult(true, "", "{}");
        value.commandReplies.add(busy());value.commandReplies.add(success);
        Assert.assertSame(success, value.apply("smb share apply", 120));
        Assert.assertEquals(2, value.dispatched.size());Assert.assertEquals(1, value.identityChecks);Assert.assertEquals(0, value.controlChecks);
    }

    @Test public void deadlineIsRecheckedImmediatelyBeforeEachFirstOrRepeatedDispatch() throws Exception {
        PlainFixture first = new PlainFixture();first.advanceAtRead = 2;
        first.advanceNanos = java.util.concurrent.TimeUnit.SECONDS.toNanos(121);
        Assert.assertThrows(CloudRuntimeException.class, () -> first.apply("nfs export apply", 120));
        Assert.assertEquals(0, first.dispatched.size());
        PlainFixture second = new PlainFixture(nativeIdle(true));second.advanceAtRead = 6;
        second.advanceNanos = java.util.concurrent.TimeUnit.SECONDS.toNanos(16);
        Assert.assertThrows(CloudRuntimeException.class, () -> second.apply("nfs export apply", 120));
        Assert.assertEquals(1, second.dispatched.size());Assert.assertEquals(1, second.probes);Assert.assertEquals(1, second.controlChecks);
    }

}
