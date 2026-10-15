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
            org.mockito.Mockito.when(instance.getId()).thenReturn(9L);
            org.mockito.Mockito.when(instance.getUuid()).thenReturn("3480bb2c-99ee-42f2-91cf-715ded5dd35f");
            org.springframework.test.util.ReflectionTestUtils.setField(this,"storageOperationControlDao",
                    org.mockito.Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationControlDao.class));
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

        @Override public Long getStorageServiceSyncId(org.apache.cloudstack.api.BaseCmd command) {return 9L;}
        @Override protected StorageServiceInstanceVO requireInstance(Long id) {Assert.assertEquals(Long.valueOf(9),id);return instance;}

        void actualRuntimeGuard() throws Exception {
            org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageNfsExportCmd command =
                    new org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageNfsExportCmd();
            java.util.concurrent.atomic.AtomicReference<DesiredStateChange.Runtime> captured = new java.util.concurrent.atomic.AtomicReference<>();
            try (org.mockito.MockedStatic<StorageServiceRequestFingerprint> fingerprint = org.mockito.Mockito.mockStatic(StorageServiceRequestFingerprint.class);
                    org.mockito.MockedConstruction<DesiredStateChange> engines = org.mockito.Mockito.mockConstruction(DesiredStateChange.class,
                            (engine, construction) -> org.mockito.Mockito.when(engine.execute(org.mockito.ArgumentMatchers.eq(9L),
                                    org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.nullable(String.class),
                                    org.mockito.ArgumentMatchers.nullable(Long.class), org.mockito.ArgumentMatchers.eq("formatter-gate-test-fingerprint"),
                                    org.mockito.ArgumentMatchers.eq(String.class), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                                    .thenAnswer(call -> {captured.set(call.getArgument(7));return "runtime-captured";}))) {
                fingerprint.when(() -> StorageServiceRequestFingerprint.of(command)).thenReturn("formatter-gate-test-fingerprint");
                Assert.assertEquals("runtime-captured", executeDesiredChange(command, String.class,
                        () -> {throw new AssertionError("Runtime guard fixture must not execute a mutation");}));
                Assert.assertEquals(1,engines.constructed().size());Assert.assertNotNull(captured.get());
            }
            captured.get().ensureRollbackSafe();
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


    private static final class RecoveryNetworkFixture extends StorageServiceManagerImpl {
        final StorageServiceInstanceVO instance = org.mockito.Mockito.mock(StorageServiceInstanceVO.class);
        final StorageServiceOperationVO owner = org.mockito.Mockito.mock(StorageServiceOperationVO.class);
        final java.util.Deque<StorageServiceGuestCommandResult> replies = new java.util.ArrayDeque<>();
        final java.util.Deque<JsonObject> idleReplies = new java.util.ArrayDeque<>();
        final java.util.List<StorageServiceGuestCommand> calls = new java.util.ArrayList<>();
        final JsonObject source = new JsonObject(), request = new JsonObject();
        final com.google.gson.JsonArray observedBindings = new com.google.gson.JsonArray();
        long now = 1_000_000, dispatchNanos;
        int probes, formatterChecks, identityChecks;boolean interruptBindings;
        RuntimeException commandError, idleError, formatterError, identityError;
        Runnable afterDispatch, afterIdle;
        RecoveryNetworkFixture() throws Exception {
            org.springframework.test.util.ReflectionTestUtils.setField(this,"storageOperationControlDao",
                    org.mockito.Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationControlDao.class));
            org.mockito.Mockito.when(instance.getId()).thenReturn(9L);
            org.mockito.Mockito.when(instance.getUuid()).thenReturn("3480bb2c-99ee-42f2-91cf-715ded5dd35f");
            org.mockito.Mockito.when(instance.getVmId()).thenReturn(54L);
            org.mockito.Mockito.when(owner.getId()).thenReturn(43L);
            org.mockito.Mockito.when(owner.getInstanceId()).thenReturn(9L);
            org.mockito.Mockito.when(owner.getUuid()).thenReturn("143ac9d5-2180-4881-8a5c-e1f44084903e");
            org.mockito.Mockito.when(owner.getRevision()).thenReturn(101L);
            org.mockito.Mockito.when(owner.getPhase()).thenReturn("ROLLING_BACK");
            org.mockito.Mockito.when(owner.getState()).thenReturn("RUNNING");
            JsonObject desired = new JsonObject();
            for (String path : new java.util.TreeSet<>(StorageRenderedDesiredState.PATHS)) desired.add(path, com.google.gson.JsonNull.INSTANCE);
            JsonObject network = new JsonObject();network.add("endpoints", new com.google.gson.JsonArray());desired.add("network-endpoints.json", network);
            String sha = StorageConfigArchive.sha256(desired.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            JsonObject generation = new JsonObject();generation.addProperty("instanceUuid", instance.getUuid());
            generation.addProperty("operationUuid", "a5e722d8-5e1d-4930-8761-0b3d6902992a");generation.addProperty("revision",100L);
            generation.addProperty("configurationSha256",sha);generation.addProperty("verifiedAt",1.0);
            source.addProperty("success",true);source.addProperty("generationSupported",true);source.addProperty("frozen",true);
            source.add("generation", generation);source.addProperty("configurationSha256",sha);source.add("configurationDesiredState",desired);
            JsonObject nativeGeneration = new JsonObject();nativeGeneration.add("previous",generation.deepCopy());
            JsonObject snapshot = new JsonObject();snapshot.add("nativeGeneration",nativeGeneration);snapshot.add("nativeDesiredState",desired.deepCopy());
            org.mockito.Mockito.when(owner.getPreviousSnapshotJson()).thenReturn(snapshot.toString());
            request.addProperty("instanceUuid",instance.getUuid());request.addProperty("operationUuid",owner.getUuid());
            request.addProperty("revision",101L);request.add("expectedBindings",new com.google.gson.JsonArray());
            setLocal("storageWriterOperation",owner);setLocal("configurationRecoverySource",source);
            java.lang.reflect.Field field = StorageServiceManagerImpl.class.getDeclaredField("guestCommandDispatcher");field.setAccessible(true);
            field.set(this,(StorageServiceGuestCommandDispatcher) command -> {
                if ("operation writer-idle".equals(command.getOperation())) {
                    probes++;
                    if (idleError!=null) throw idleError;
                    JsonObject idle = idleReplies.isEmpty()?nativeIdle(true):idleReplies.removeFirst();
                    if (afterIdle!=null)afterIdle.run();
                    return new StorageServiceGuestCommandResult(true,"",idle.toString());
                }
                calls.add(command);now+=dispatchNanos;
                if(afterDispatch!=null)afterDispatch.run();
                if(commandError!=null)throw commandError;
                return replies.isEmpty()?busy():replies.removeFirst();
            });
        }
        @SuppressWarnings("unchecked") void setLocal(String field,Object value) throws Exception {
            java.lang.reflect.Field local=StorageServiceManagerImpl.class.getDeclaredField(field);local.setAccessible(true);
            ((ThreadLocal<Object>)local.get(this)).set(value);
        }
        @Override protected long rollbackNativeWriterNowNanos(){return now;}
        @Override protected void pauseRollbackNativeWriter(long milliseconds){now+=java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(milliseconds);}
        @Override protected void requireNoPendingVolumeFormatter(StorageServiceInstanceVO value){formatterChecks++;if(formatterError!=null)throw formatterError;}
        @Override protected void requireIdentityRollbackSafe(StorageServiceInstanceVO value,StorageServiceOperationVO operation){identityChecks++;if(identityError!=null)throw identityError;}
        @Override protected com.google.gson.JsonArray rootNetworkBindings(StorageServiceInstanceVO value,JsonObject cache){
            Assert.assertSame(instance,value);if(interruptBindings)Thread.currentThread().interrupt();return observedBindings.deepCopy();
        }
        JsonObject invoke(){return invokeWithTimeout(60);}
        JsonObject invokeWithTimeout(int timeout){return rootGuest(instance,"network endpoints reconcile",request,timeout);}
        void successAfterBusy(){replies.add(busy());replies.add(new StorageServiceGuestCommandResult(true,"","{\"success\":true}"));}
    }

    @Test public void frozenRecoveryNetworkBusyRetriesActualRootGuestAfterStrictIdle() throws Exception {
        RecoveryNetworkFixture value=new RecoveryNetworkFixture();value.successAfterBusy();
        value.idleReplies.add(nativeIdle(false));value.idleReplies.add(nativeIdle(true));
        value.dispatchNanos=java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        Assert.assertTrue(value.invoke().get("success").getAsBoolean());
        Assert.assertEquals(2,value.calls.size());Assert.assertEquals(2,value.probes);
        Assert.assertEquals(1,value.formatterChecks);Assert.assertEquals(1,value.identityChecks);
        for(StorageServiceGuestCommand call:value.calls){
            Assert.assertEquals("network endpoints reconcile",call.getOperation());Assert.assertEquals(54L,call.getVmId());
            Assert.assertEquals(value.request.toString(),call.getPayload());Assert.assertTrue(call.getMaskedFields().isEmpty());
        }
        Assert.assertEquals(60,value.calls.get(0).getTimeoutSeconds());
        Assert.assertTrue(value.calls.get(1).getTimeoutSeconds()<60);
    }
    @Test public void exactInterruptedRecoveryNetworkCanRetryButDoesNotGainForwardAuthority() throws Exception {
        RecoveryNetworkFixture value=new RecoveryNetworkFixture();value.successAfterBusy();
        org.mockito.Mockito.when(value.owner.getPhase()).thenReturn("RECOVERING_INTERRUPTED_WRITER");
        org.mockito.Mockito.when(value.owner.getState()).thenReturn("RECOVERY_REQUIRED");
        Assert.assertTrue(value.invoke().get("success").getAsBoolean());Assert.assertEquals(2,value.calls.size());
        Assert.assertEquals(1,value.formatterChecks);Assert.assertEquals(1,value.identityChecks);
    }
    @Test public void networkMissingForeignOrForwardSourceCannotGainRetry() throws Exception {
        for(int mode=0;mode<12;mode++){
            RecoveryNetworkFixture value=new RecoveryNetworkFixture();
            if(mode==0)value.setLocal("configurationRecoverySource",null);
            if(mode==1)value.setLocal("storageWriterOperation",null);
            if(mode==2)org.mockito.Mockito.when(value.owner.getPhase()).thenReturn("APPLYING");
            if(mode==3)org.mockito.Mockito.when(value.owner.getState()).thenReturn("COMPLETE");
            if(mode==4)value.request.addProperty("operationUuid","22222222-2222-4222-8222-222222222222");
            if(mode==5)value.request.addProperty("instanceUuid","22222222-2222-4222-8222-222222222222");
            if(mode==6)value.request.addProperty("revision",102L);
            if(mode==7)org.mockito.Mockito.when(value.owner.getPreviousSnapshotJson()).thenReturn("{}");
            if(mode==8)value.source.getAsJsonObject("generation").addProperty("revision",101L);
            if(mode==9)value.source.addProperty("configurationSha256","b".repeat(64));
            if(mode==10)value.request.addProperty("extra",true);
            if(mode==11)value.source.getAsJsonObject("generation").addProperty("instanceUuid","22222222-2222-4222-8222-222222222222");
            Assert.assertThrows(CloudRuntimeException.class,value::invoke);
            Assert.assertEquals(1,value.calls.size());Assert.assertEquals(0,value.probes);
        }
    }
    @Test public void networkBusyWithExtraFieldsUnknownTrueOrMissingWireNeverRetries() throws Exception {
        for(String raw:new String[]{null,"{}","{\"success\":false,\"errorCode\":\"OTHER\"}",
                "{\"success\":true,\"errorCode\":\"STORAGE_WRITER_BUSY\"}","{\"success\":false,\"errorCode\":\"STORAGE_WRITER_BUSY\",\"extra\":1}",
                "{\"success\":\"false\",\"errorCode\":\"STORAGE_WRITER_BUSY\"}",
                "{\"success\":false,\"success\":false,\"errorCode\":\"STORAGE_WRITER_BUSY\"}"}){
            RecoveryNetworkFixture value=new RecoveryNetworkFixture();value.replies.add(new StorageServiceGuestCommandResult(false,"masked exit75",raw));
            Assert.assertThrows(CloudRuntimeException.class,value::invoke);Assert.assertEquals(1,value.calls.size());Assert.assertEquals(0,value.probes);
        }
    }
    @Test public void networkActualTransportErrorDoesNotRetry() throws Exception {
        RecoveryNetworkFixture value=new RecoveryNetworkFixture();value.commandError=new CloudRuntimeException("I/O unavailable");
        Assert.assertSame(value.commandError,Assert.assertThrows(CloudRuntimeException.class,value::invoke));
        Assert.assertEquals(1,value.calls.size());Assert.assertEquals(0,value.probes);
    }
    @Test public void networkBusyCannotResendAfterBootUnknownOrFailedIdle() throws Exception {
        java.util.List<JsonObject> bad=new java.util.ArrayList<>();JsonObject boot=nativeIdle(false);boot.addProperty("bootChildActive",true);bad.add(boot);
        JsonObject recovery=nativeIdle(false);recovery.addProperty("bootRecoveryRequired",true);bad.add(recovery);
        JsonObject unknown=nativeIdle(true);unknown.addProperty("status","UNKNOWN");bad.add(unknown);
        JsonObject malformed=nativeIdle(true);malformed.remove("writerLockSupported");bad.add(malformed);
        for(JsonObject reply:bad){
            RecoveryNetworkFixture value=new RecoveryNetworkFixture();value.idleReplies.add(reply);
            Assert.assertThrows(CloudRuntimeException.class,value::invoke);Assert.assertEquals(1,value.calls.size());Assert.assertEquals(1,value.probes);
        }
    }
    @Test public void networkBusyStillHonorsFormatterAndIdentityFailure() throws Exception {
        for(int mode=0;mode<2;mode++){
            RecoveryNetworkFixture value=new RecoveryNetworkFixture();
            if(mode==0)value.formatterError=new CloudRuntimeException("Formatter is active");else value.identityError=new CloudRuntimeException("Identity is foreign");
            Assert.assertThrows(CloudRuntimeException.class,value::invoke);Assert.assertEquals(1,value.calls.size());
            Assert.assertEquals(1,value.formatterChecks);Assert.assertEquals(mode==0?0:1,value.identityChecks);
        }
    }
    @Test public void networkSourceOwnerOrFrozenMutationAfterBusyCannotResend() throws Exception {
        for(int mode=0;mode<9;mode++){
            RecoveryNetworkFixture value=new RecoveryNetworkFixture();final int selected=mode;
            value.afterIdle=()->{
                if(selected==0)value.source.addProperty("configurationSha256","b".repeat(64));
                if(selected==1)value.source.getAsJsonObject("configurationDesiredState").add("network-endpoints.json",com.google.gson.JsonNull.INSTANCE);
                if(selected==2)org.mockito.Mockito.when(value.owner.getUuid()).thenReturn("22222222-2222-4222-8222-222222222222");
                if(selected==3)org.mockito.Mockito.when(value.owner.getRevision()).thenReturn(102L);
                if(selected==4)org.mockito.Mockito.when(value.owner.getPreviousSnapshotJson()).thenReturn("{}");
                if(selected==5)org.mockito.Mockito.when(value.instance.getVmId()).thenReturn(55L);
                if(selected==6)org.mockito.Mockito.when(value.owner.getState()).thenReturn("COMPLETE");
                if(selected==7){try{value.setLocal("configurationRecoverySource",null);}catch(Exception e){throw new AssertionError(e);}}
                if(selected==8)value.observedBindings.add(new JsonObject());
            };
            Assert.assertThrows(CloudRuntimeException.class,value::invoke);Assert.assertEquals(1,value.calls.size());Assert.assertEquals(1,value.probes);
        }
    }
    @Test public void networkPermanentBusyAndInterruptionNeverExtendBudgetOrResendUnsafely() throws Exception {
        RecoveryNetworkFixture value=new RecoveryNetworkFixture();value.idleReplies.add(nativeIdle(false));
        value.idleReplies.add(nativeIdle(false));
        value.idleError=new CloudRuntimeException("Read transport unavailable");
        Assert.assertThrows(CloudRuntimeException.class,value::invoke);Assert.assertEquals(1,value.calls.size());
        RecoveryNetworkFixture interrupted=new RecoveryNetworkFixture();interrupted.afterIdle=()->Thread.currentThread().interrupt();
        try{Assert.assertThrows(CloudRuntimeException.class,interrupted::invoke);Assert.assertTrue(Thread.currentThread().isInterrupted());Assert.assertEquals(1,interrupted.calls.size());}
        finally{Thread.interrupted();}
    }


    @Test public void networkRepeatedPrebodyBusyIsBoundedAndNeverResetsOriginalTimeout() throws Exception {
        RecoveryNetworkFixture value=new RecoveryNetworkFixture();value.dispatchNanos=java.util.concurrent.TimeUnit.SECONDS.toNanos(4);
        Assert.assertThrows(CloudRuntimeException.class,value::invoke);
        Assert.assertTrue(value.calls.size()>1&&value.calls.size()<=5);
        int previous=61;
        for(StorageServiceGuestCommand command:value.calls){Assert.assertTrue(command.getTimeoutSeconds()<previous);previous=command.getTimeoutSeconds();}
        RecoveryNetworkFixture shortBudget=new RecoveryNetworkFixture();shortBudget.dispatchNanos=java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        Assert.assertThrows(CloudRuntimeException.class,()->shortBudget.invokeWithTimeout(3));Assert.assertEquals(1,shortBudget.calls.size());
    }
    @Test public void networkFreshIdleReturnedAfterDeadlineCannotAuthorizeResend() throws Exception {
        RecoveryNetworkFixture value=new RecoveryNetworkFixture();value.afterIdle=()->value.now+=java.util.concurrent.TimeUnit.SECONDS.toNanos(61);
        Assert.assertThrows(CloudRuntimeException.class,value::invoke);Assert.assertEquals(1,value.calls.size());Assert.assertEquals(1,value.probes);
    }

    @Test public void networkInterruptionDuringFreshNicBindingCheckCannotDispatchAgain() throws Exception {
        RecoveryNetworkFixture value=new RecoveryNetworkFixture();value.afterIdle=()->value.interruptBindings=true;
        try{
            Assert.assertThrows(CloudRuntimeException.class,value::invoke);Assert.assertTrue(Thread.currentThread().isInterrupted());
            Assert.assertEquals(1,value.calls.size());
        }finally{Thread.interrupted();}
    }

    @Test public void networkOversizedSourceAndRequestRevisionsCannotWrapIntoRetryAuthority() throws Exception {
        for(boolean sourceRevision:new boolean[]{true,false}){
            RecoveryNetworkFixture value=new RecoveryNetworkFixture();value.successAfterBusy();
            if(sourceRevision){
                value.source.getAsJsonObject("generation").addProperty("revision",new java.math.BigInteger("18446744073709551716"));
                JsonObject snapshot=new com.google.gson.JsonParser().parse(value.owner.getPreviousSnapshotJson()).getAsJsonObject();
                snapshot.getAsJsonObject("nativeGeneration").add("previous",value.source.get("generation").deepCopy());
                org.mockito.Mockito.when(value.owner.getPreviousSnapshotJson()).thenReturn(snapshot.toString());
            }else value.request.addProperty("revision",new java.math.BigInteger("18446744073709551717"));
            Assert.assertThrows(CloudRuntimeException.class,value::invoke);Assert.assertEquals(1,value.calls.size());Assert.assertEquals(0,value.probes);
        }
    }
}
