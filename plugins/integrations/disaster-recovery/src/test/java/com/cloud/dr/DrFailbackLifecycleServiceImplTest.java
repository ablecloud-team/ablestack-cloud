// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information.
// The ASF licenses this file to you under the Apache License, Version 2.0.
package com.cloud.dr;

import java.util.Date;

import javax.inject.Provider;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.agent.api.FtctlDrActionAnswer;
import com.cloud.agent.api.FtctlDrActionCommand;
import com.cloud.agent.api.FtctlDrStatusAnswer;
import com.cloud.agent.api.FtctlDrStatusCommand;
import com.cloud.dr.adapter.ftctl.DrRemoteAgentClient;
import com.cloud.dr.adapter.ftctl.FtctlDrUnifiedActionAdapter;
import com.cloud.dr.dao.DrCutoverSessionDao;
import com.cloud.dr.dao.DrEventDao;
import com.cloud.dr.dao.DrFailbackSessionDao;
import com.cloud.dr.dao.DrPlanDao;
import com.cloud.dr.dao.DrReplicaDao;
import com.cloud.dr.dao.DrRunDao;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonObject;

@RunWith(MockitoJUnitRunner.class)
public class DrFailbackLifecycleServiceImplTest {
    @Mock private DrFailbackSessionDao drFailbackSessionDao;
    @Mock private DrPlanDao drPlanDao;
    @Mock private DrRunDao drRunDao;
    @Mock private DrReplicaDao drReplicaDao;
    @Mock private DrEventDao drEventDao;
    @Mock private DrCutoverSessionDao drCutoverSessionDao;
    @Mock private DrRemoteAgentClient drRemoteAgentClient;
    @Mock private DrPlanOwnedTransportService drPlanOwnedTransportService;
    @Mock private Provider<FtctlDrUnifiedActionAdapter> ftctlDrUnifiedActionAdapterProvider;
    @Mock private FtctlDrUnifiedActionAdapter ftctlDrUnifiedActionAdapter;

    @Spy
    @InjectMocks
    private DrFailbackLifecycleServiceImpl service;

    private DrPlanVO plan;
    private DrRunVO run;
    private DrFailbackSessionVO session;

    @Before
    public void setUp() {
        plan = new DrPlanVO("failback-plan", 1L, 2L, DrConstants.DIRECTION_VMWARE_TO_KVM);
        plan.setState(DrConstants.PLAN_STATE_COMMIT_VERIFYING);
        plan.setActiveSide("SOURCE");
        run = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_FAILBACK);
        session = new DrFailbackSessionVO(plan.getId(), run.getId(), "session-a", "COMMIT_VERIFYING");
        session.setCheckpointSequence(7L);
        session.setCommitOutcome("UNKNOWN");
        session.setEngineAckState("UNKNOWN");

        Mockito.when(drFailbackSessionDao.findActiveByRunId(run.getId())).thenReturn(session);
        Mockito.when(drReplicaDao.listActiveByPlanId(plan.getId())).thenReturn(java.util.Collections.emptyList());
    }

    @Test public void liveResumeWorkerIsObservedWithoutRecreatingTransport() {
        DrPlanVO plan = new DrPlanVO("resume", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        plan.setActiveSide("SOURCE");
        DrRunVO run = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_FAILBACK);
        String worker = java.util.UUID.nameUUIDFromBytes((plan.getUuid()+":"+run.getUuid()+":RESUME_SYNC")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        JsonObject runtime = new JsonObject();
        runtime.addProperty("active_worker_run_uuid",worker); runtime.addProperty("scheduler_pid_alive",true);
        runtime.addProperty("control_state","RUNNING"); runtime.addProperty("scheduler_state","RUNNING");
        runtime.addProperty("scheduler_health","HEALTHY");
        Assert.assertTrue(service.ownedResumeWorkerActive(plan,run,runtime));
        runtime.addProperty("control_state","PAUSED"); Assert.assertFalse(service.ownedResumeWorkerActive(plan,run,runtime));
        runtime.addProperty("control_state","RUNNING"); runtime.addProperty("scheduler_pid_alive",false);
        Assert.assertFalse(service.ownedResumeWorkerActive(plan,run,runtime));
        runtime.addProperty("scheduler_pid_alive",true); runtime.addProperty("active_worker_run_uuid","stale");
        Assert.assertFalse(service.ownedResumeWorkerActive(plan,run,runtime));
        runtime.addProperty("active_worker_run_uuid",worker); runtime.addProperty("active_side","TARGET");
        Assert.assertFalse(service.ownedResumeWorkerActive(plan,run,runtime));
    }

    @Test
    public void acknowledgedRuntimeConvergesWithoutRollback() {
        Mockito.doReturn(true).when(service).cloudPowerStatesMatch(plan);
        JsonObject runtime = runtime("ACKNOWLEDGED");
        runtime.addProperty("control_generation", 12);
        runtime.addProperty("control_ack_generation", 12);
        runtime.addProperty("scheduler_state", "RUNNING");

        DrFailbackSessionVO result = service.reconcile(plan, run, runtime);

        Assert.assertSame(session, result);
        Assert.assertEquals("PROTECTION_RESUMING", session.getState());
        Assert.assertEquals("ACKNOWLEDGED", session.getCommitOutcome());
        Assert.assertEquals("ACKNOWLEDGED", session.getEngineAckState());
        Assert.assertEquals(Long.valueOf(12), session.getSchedulerGeneration());
        Assert.assertEquals(Long.valueOf(12), session.getSchedulerAckGeneration());
        Assert.assertEquals(Long.valueOf(7), session.getResumeBaselineCheckpointSequence());
        Assert.assertEquals(Long.valueOf(8), session.getRequiredPostFailbackCheckpointSequence());
        Assert.assertNotNull(session.getProtectionResumeRequestedAt());
        Assert.assertEquals(DrConstants.PLAN_STATE_SYNCING, plan.getState());
        Assert.assertEquals("SOURCE", plan.getActiveSide());
        Mockito.verify(drEventDao).persist(Mockito.argThat(event ->
                "FAILBACK_AUTHORITY_COMMITTED".equals(event.getEventType())));
    }

    @Test
    public void unknownRuntimeRemainsCommitVerifying() {
        JsonObject runtime = runtime("UNKNOWN");
        runtime.addProperty("error_code", "DR_FAILBACK_COMMIT_ACK_TIMEOUT");

        DrFailbackSessionVO result = service.reconcile(plan, run, runtime);

        Assert.assertSame(session, result);
        Assert.assertEquals("COMMIT_VERIFYING", session.getState());
        Assert.assertEquals("UNKNOWN", session.getCommitOutcome());
        Assert.assertEquals(DrConstants.PLAN_STATE_COMMIT_VERIFYING, plan.getState());
        Mockito.verify(drEventDao, Mockito.never()).persist(Mockito.any());
    }

    @Test
    public void terminalRunReconcilerClosesArtifactFreeRequestedSession() {
        run.setState(DrConstants.RUN_STATE_FAILED);
        run.setCompleted(new Date());
        run.setErrorCode(DrConstants.ERROR_ENGINE_ACTION_FAILED);
        run.setErrorMessage("Mold API returned HTTP 401");
        session.setState("REQUESTED");
        session.setAcceptanceState("SUBMITTED");
        session.setEngineAckState("PENDING");
        session.setCommitOutcome("PENDING");
        session.setRollbackState("NONE");
        session.setCheckpointSequence(null);

        DrFailbackSessionVO result = service.reconcile(plan, run, new JsonObject());

        Assert.assertSame(session, result);
        Assert.assertEquals("FAILED", session.getState());
        Assert.assertEquals("REJECTED", session.getAcceptanceState());
        Assert.assertEquals("PRE_DISPATCH", session.getFailurePhase());
        Assert.assertNotNull(session.getCompletedAt());
        Assert.assertNotNull(session.getRemoved());
        Mockito.verify(drFailbackSessionDao).update(session.getId(), session);
        Mockito.verifyNoInteractions(drRemoteAgentClient);
    }

    @Test
    public void protectionResumeRequiresDurableNextCheckpointAndSessionAck() {
        plan.setActiveSide("SOURCE");
        session.setState("PROTECTION_RESUMING");
        session.setCommitOutcome("ACKNOWLEDGED");
        session.setEngineAckState("ACKNOWLEDGED");
        session.setTargetPowerState("POWERED_OFF");
        session.setSourcePowerState("POWERED_ON");
        session.setResumeBaselineCheckpointSequence(7L);
        session.setRequiredPostFailbackCheckpointSequence(8L);
        JsonObject runtime = runtime("UNKNOWN");
        runtime.addProperty("scheduler_state", "RUNNING");
        runtime.addProperty("scheduler_health", "HEALTHY");
        runtime.addProperty("latest_completed_checkpoint_sequence", 7L);
        runtime.addProperty("plan_cycle_sequence", 99L);

        Assert.assertFalse(service.protectionResumed(plan, session, runtime));

        runtime.addProperty("latest_completed_checkpoint_sequence", 8L);
        Assert.assertTrue(service.protectionResumed(plan, session, runtime));

        session.setEngineAckState("UNKNOWN");
        Assert.assertFalse(service.protectionResumed(plan, session, runtime));
    }

    @Test
    public void remoteKvmProtectionResumeAcceptsBlankRuntimeSideAfterDurableCheckpoint() {
        plan = new DrPlanVO("failback-plan", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        plan.setSourceExternalRef("remote-source-vm");
        plan.setActiveSide("SOURCE");
        session.setState("PROTECTION_RESUMING");
        session.setCommitOutcome("ACKNOWLEDGED");
        session.setEngineAckState("ACKNOWLEDGED");
        session.setTargetPowerState("POWERED_OFF");
        session.setSourcePowerState("POWERED_ON");
        session.setRequiredPostFailbackCheckpointSequence(8L);
        JsonObject runtime = runtime("UNKNOWN");
        runtime.remove("active_side");
        runtime.addProperty("scheduler_state", "RUNNING");
        runtime.addProperty("scheduler_health", "HEALTHY");
        runtime.addProperty("latest_completed_checkpoint_sequence", 8L);
        Mockito.when(drRemoteAgentClient.isRemoteKvmSource(plan)).thenReturn(true);

        Assert.assertTrue(service.protectionResumed(plan, session, runtime));
    }

    @Test
    public void remoteKvmProtectionResumeRejectsExplicitConflictingRuntimeSide() {
        plan = new DrPlanVO("failback-plan", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        plan.setSourceExternalRef("remote-source-vm");
        plan.setActiveSide("SOURCE");
        session.setState("PROTECTION_RESUMING");
        session.setCommitOutcome("ACKNOWLEDGED");
        session.setEngineAckState("ACKNOWLEDGED");
        session.setTargetPowerState("POWERED_OFF");
        session.setSourcePowerState("POWERED_ON");
        session.setRequiredPostFailbackCheckpointSequence(8L);
        JsonObject runtime = runtime("UNKNOWN");
        runtime.addProperty("active_side", "TARGET");
        runtime.addProperty("scheduler_state", "RUNNING");
        runtime.addProperty("scheduler_health", "HEALTHY");
        runtime.addProperty("latest_completed_checkpoint_sequence", 8L);
        Assert.assertFalse(service.protectionResumed(plan, session, runtime));
    }

    @Test
    public void remoteKvmFailbackDrainsOriginalSiteExportBeforeSourceStart() {
        plan = new DrPlanVO("failback-plan", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        plan.setSourceExternalRef("remote-source-vm");
        plan.setActiveSide("TARGET");

        service.stopReversePlanOwnedExport(plan, run);

        Mockito.verify(drPlanOwnedTransportService).stopReverseTargetExport(plan, run);
    }

    @Test
    public void remoteKvmFailbackRestoresForwardExportBeforeAuthorityCommit() {
        service.restoreForwardPlanOwnedExport(plan, run);

        Mockito.verify(drPlanOwnedTransportService).startForwardTargetExport(plan, run, null);
    }

    @Test
    public void protectionResumeReentryRestoresForwardExportForRemoteKvmPlan() {
        plan = new DrPlanVO("failback-plan", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        plan.setSourceExternalRef("remote-source-vm");
        Mockito.when(drPlanOwnedTransportService.supports(plan)).thenReturn(true);

        service.ensureForwardPlanOwnedExportForProtectionResume(plan, run);

        Mockito.verify(drPlanOwnedTransportService).startForwardTargetExport(plan, run, null);
    }

    @Test
    public void protectionResumeReentryDoesNotTouchVmwareTransport() {
        Mockito.when(drPlanOwnedTransportService.supports(plan)).thenReturn(false);

        service.ensureForwardPlanOwnedExportForProtectionResume(plan, run);

        Mockito.verify(drPlanOwnedTransportService, Mockito.never())
                .startForwardTargetExport(Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    public void protectionResumeReentryResumesRemoteSourceScheduler() {
        plan = new DrPlanVO("failback-plan", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        plan.setSourceExternalRef("remote-source-vm");
        Mockito.when(drRemoteAgentClient.isRemoteKvmSource(plan)).thenReturn(true);
        Mockito.when(ftctlDrUnifiedActionAdapterProvider.get()).thenReturn(ftctlDrUnifiedActionAdapter);
        FtctlDrActionCommand command = new FtctlDrActionCommand(
                FtctlDrActionCommand.Action.RESUME_SYNC, plan.getUuid(), run.getUuid());
        session.setResumeBaselineCheckpointSequence(9L);
        session.setRequiredPostFailbackCheckpointSequence(10L);
        Mockito.when(ftctlDrUnifiedActionAdapter.resumeRemoteSourceProtection(plan, run, 9L, 10L))
                .thenReturn(new FtctlDrActionAnswer(command, true, "resumed"));

        service.ensureRemoteSourceSchedulerResumedForProtectionResume(plan, run, session);

        Mockito.verify(ftctlDrUnifiedActionAdapter).resumeRemoteSourceProtection(plan, run, 9L, 10L);
    }

    @Test(expected = CloudRuntimeException.class)
    public void protectionResumeReentryRejectsRemoteSourceResumeFailure() {
        plan = new DrPlanVO("failback-plan", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        plan.setSourceExternalRef("remote-source-vm");
        Mockito.when(drRemoteAgentClient.isRemoteKvmSource(plan)).thenReturn(true);
        Mockito.when(ftctlDrUnifiedActionAdapterProvider.get()).thenReturn(ftctlDrUnifiedActionAdapter);
        FtctlDrActionCommand command = new FtctlDrActionCommand(
                FtctlDrActionCommand.Action.RESUME_SYNC, plan.getUuid(), run.getUuid());
        session.setResumeBaselineCheckpointSequence(9L);
        session.setRequiredPostFailbackCheckpointSequence(10L);
        Mockito.when(ftctlDrUnifiedActionAdapter.resumeRemoteSourceProtection(plan, run, 9L, 10L))
                .thenReturn(new FtctlDrActionAnswer(command, false, "resume failed"));

        service.ensureRemoteSourceSchedulerResumedForProtectionResume(plan, run, session);
    }

    @Test
    public void protectionResumeReadsAuthorityFromRemoteSourceWorker() {
        plan = new DrPlanVO("failback-plan", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        plan.setSourceExternalRef("remote-source-vm");
        Mockito.when(drRemoteAgentClient.isRemoteKvmSource(plan)).thenReturn(true);
        FtctlDrStatusCommand command = new FtctlDrStatusCommand(plan.getUuid(), null,
                FtctlDrStatusCommand.StatusScope.PLAN_AUTHORITY);
        FtctlDrStatusAnswer answer = new FtctlDrStatusAnswer(command, true, "ok", plan.getUuid(), null,
                "ok", "READY", "durable", 100, null, null, 0, null, null, null, null, null,
                null, null, 0, "ok", "{\"scheduler_state\":\"RUNNING\",\"latest_completed_checkpoint_sequence\":11}");
        Mockito.when(drRemoteAgentClient.fetchSourceStatus(plan, null,
                FtctlDrStatusCommand.StatusScope.PLAN_AUTHORITY)).thenReturn(answer);

        JsonObject runtime = service.fetchStatusRuntime(plan, run,
                FtctlDrStatusCommand.StatusScope.PLAN_AUTHORITY);

        Assert.assertEquals("RUNNING", runtime.get("scheduler_state").getAsString());
        Assert.assertEquals(11L, runtime.get("latest_completed_checkpoint_sequence").getAsLong());
    }

    @Test
    public void rollbackStopsForwardExportBeforeTargetRecovery() {
        service.stopForwardPlanOwnedExportForTargetRecovery(plan, run);

        Mockito.verify(drPlanOwnedTransportService).stopForwardTargetExport(plan, run, null, null);
    }

    @Test
    public void durableCommitDispatchRequiresCompletePersistedEnvelope() {
        Assert.assertFalse(service.hasDurableCommitDispatch(session));

        session.setCommitContractVersion(DrFailbackCommitEnvelope.CONTRACT_VERSION);
        session.setCommitAttemptId("attempt-a");
        session.setCommitEnvelopeSha256(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        session.setCommitDispatchState("DISPATCHED");

        Assert.assertTrue(service.hasDurableCommitDispatch(session));
    }

    @Test
    public void rollbackFailedSessionRemainsEligibleForCleanupReconciliation() {
        Assert.assertFalse(service.isTerminal("ROLLBACK_FAILED"));
        Assert.assertTrue(service.isTerminal("COMPLETED"));
        Assert.assertTrue(service.isTerminal("FAILED"));
        Assert.assertTrue(service.isTerminal("ABORTED"));
    }

    @Test
    public void canceledFailbackWithLiveSessionRequiresAuthorityCompensation() {
        run.setState(DrConstants.RUN_STATE_CANCELED);
        session.setState("COMMIT_VERIFYING");
        Assert.assertTrue(service.isCanceledFailbackPendingCompensation(run, session));

        session.setState("ABORTED");
        Assert.assertFalse(service.isCanceledFailbackPendingCompensation(run, session));
    }

    @Test
    public void abortedSessionWithNonterminalRunRequiresAtomicFailureConvergence() {
        session.setState("ABORTED");
        run.setState(DrConstants.RUN_STATE_RUNNING);
        Assert.assertTrue(service.requiresRolledBackFailureConvergence(session, run));

        run.setState(DrConstants.RUN_STATE_FAILED);
        Assert.assertFalse(service.requiresRolledBackFailureConvergence(session, run));
        session.setState("FAILED");
        run.setState(DrConstants.RUN_STATE_RUNNING);
        Assert.assertTrue(service.requiresRolledBackFailureConvergence(session, run));
    }

    @Test
    public void failbackRuntimeOwnedByOlderRunIsIgnored() {
        JsonObject runtime = runtime("ACKNOWLEDGED");
        runtime.addProperty("run_uuid", "older-failback-run");
        runtime.addProperty("control_request_run_uuid", "older-failback-run");
        runtime.addProperty("failback_session_id", plan.getUuid() + ":older-failback-run");

        DrFailbackSessionVO result = service.reconcile(plan, run, runtime);

        Assert.assertSame(session, result);
        Assert.assertEquals("COMMIT_VERIFYING", session.getState());
        Mockito.verify(drFailbackSessionDao, Mockito.never()).update(
                Mockito.eq(session.getId()), Mockito.any(DrFailbackSessionVO.class));
        Mockito.verify(drEventDao, Mockito.never()).persist(Mockito.any());
    }

    @Test
    public void failbackBootValidationUsesExplicitPowerStatePolicy() {
        plan.setPolicyJson("{\"failbackBootValidationMode\":\"POWER_STATE_ONLY\","
                + "\"testBootValidationMode\":\"QGA_REQUIRED\"}");

        Assert.assertEquals("POWER_STATE_ONLY", service.resolveFailbackBootValidationMode(plan));
    }

    @Test
    public void failbackBootValidationUsesExistingPlanPolicyForCompatibility() {
        plan.setPolicyJson("{\"testBootValidationMode\":\"POWER_STATE_ONLY\"}");

        Assert.assertEquals("POWER_STATE_ONLY", service.resolveFailbackBootValidationMode(plan));
    }

    @Test
    public void windowsFailbackRequiresGuestHeartbeatDespitePowerOnlyTestPolicy() {
        plan.setPolicyJson("{\"testBootValidationMode\":\"POWER_STATE_ONLY\"}");
        plan.setMappingJson("{\"source\":{\"hardware\":{"
                + "\"guestId\":\"windows2019srvNext_64Guest\"}}}");

        Assert.assertTrue(service.isWindowsSource(plan));
        Assert.assertEquals("GUEST_HEARTBEAT_REQUIRED", service.resolveFailbackBootValidationMode(plan));
    }

    @Test
    public void linuxFailbackKeepsExplicitPowerStateCompatibility() {
        plan.setPolicyJson("{\"failbackBootValidationMode\":\"POWER_STATE_ONLY\"}");
        plan.setMappingJson("{\"source\":{\"hardware\":{\"guestId\":\"rhel9_64Guest\"}}}");

        Assert.assertFalse(service.isWindowsSource(plan));
        Assert.assertEquals("POWER_STATE_ONLY", service.resolveFailbackBootValidationMode(plan));
    }

    @Test
    public void failbackBootValidationKeepsGuestHeartbeatAsSafeDefault() {
        plan.setPolicyJson("{\"testBootValidationMode\":\"QGA_REQUIRED\"}");

        Assert.assertEquals("GUEST_HEARTBEAT_REQUIRED", service.resolveFailbackBootValidationMode(plan));
    }

    @Test
    public void reverseWorkerFailureKeepsTargetAuthorityWhileCleanupIsPending() {
        plan.setState(DrConstants.PLAN_STATE_FAILED_OVER);
        plan.setActiveSide("TARGET");
        session.setState("REVERSE_SYNCING");
        JsonObject runtime = runtime("PENDING");
        runtime.addProperty("state", "ERROR");
        runtime.addProperty("worker_state", "FAILED");
        runtime.addProperty("worker_pid_alive", false);
        runtime.addProperty("failure_phase", "REVERSE_TRANSFER");
        runtime.addProperty("failed_component", "kvm-vmware-mover");
        runtime.addProperty("driver_exit_code", 83);
        runtime.addProperty("baseline_file_state", "MISSING_EXPECTED");
        runtime.addProperty("error_code", "DR_REVERSE_BASELINE_REQUIRED");
        runtime.addProperty("error_message", "Reverse baseline is required for incremental transfer");

        DrFailbackSessionVO result = service.reconcile(plan, run, runtime);

        Assert.assertSame(session, result);
        Assert.assertEquals("ROLLBACK_FAILED", session.getState());
        Assert.assertEquals("FAILED", session.getRollbackState());
        Assert.assertEquals("REVERSE_TRANSFER", session.getFailurePhase());
        Assert.assertEquals("kvm-vmware-mover", session.getFailedComponent());
        Assert.assertEquals(Integer.valueOf(83), session.getDriverExitCode());
        Assert.assertEquals(Boolean.FALSE, session.getWorkerPidAlive());
        Assert.assertEquals("TARGET", plan.getActiveSide());
        Assert.assertEquals(DrConstants.PLAN_STATE_FAILED_OVER, plan.getState());
        Assert.assertTrue(session.getErrorMessage().contains("engine cleanup is pending"));
        Mockito.verify(drEventDao).persist(Mockito.argThat(event ->
                "FAILBACK_REVERSE_SYNC_FAILED".equals(event.getEventType())));
    }

    @Test
    public void terminalPublicationGraceDoesNotSynthesizeFailbackFailure() {
        plan.setState(DrConstants.PLAN_STATE_FAILED_OVER);
        plan.setActiveSide("TARGET");
        session.setState("REVERSE_SYNCING");
        JsonObject runtime = runtime("PENDING");
        runtime.addProperty("state", "RUNNING");
        runtime.addProperty("worker_state", "TERMINAL_PENDING");
        runtime.addProperty("worker_pid_alive", false);
        runtime.addProperty("terminal_publication_pending", true);
        runtime.addProperty("terminal_publication_pending_since", "2026-08-05T01:02:03+0900");

        DrFailbackSessionVO result = service.reconcile(plan, run, runtime);

        Assert.assertSame(session, result);
        Assert.assertEquals("REVERSE_SYNCING", session.getState());
        Assert.assertEquals("TARGET", plan.getActiveSide());
        Assert.assertEquals(DrConstants.PLAN_STATE_FAILED_OVER, plan.getState());
        Mockito.verify(drEventDao, Mockito.never()).persist(Mockito.any());
    }

    @Test
    public void terminalRuntimeSnapshotOverridesStaleProtectionResumingState() {
        JsonObject runtime = runtime("ACKNOWLEDGED");
        runtime.addProperty("step", "protection-resuming");
        runtime.addProperty("failback_phase", "PROTECTION_RESUMING");
        runtime.addProperty("cloud_lifecycle_state", "COMMITTED");
        runtime.addProperty("worker_state", "SUCCEEDED");
        runtime.addProperty("transfer_activity_state", "COPYING");
        runtime.addProperty("latest_completed_checkpoint_sequence", 8L);
        runtime.addProperty("latest_completed_transfer_payload_bytes", 4096L);
        runtime.addProperty("failure_phase", "REVERSE_SYNC");
        runtime.addProperty("failed_component", "ftctl");

        JsonObject terminal = service.terminalRuntimeSnapshot(runtime);

        Assert.assertEquals("READY", terminal.get("state").getAsString());
        Assert.assertEquals("target-checkpoint-ready", terminal.get("step").getAsString());
        Assert.assertEquals("COMPLETED", terminal.get("failback_phase").getAsString());
        Assert.assertEquals("COMPLETED", terminal.get("cloud_lifecycle_state").getAsString());
        Assert.assertEquals("IDLE", terminal.get("transfer_activity_state").getAsString());
        Assert.assertEquals("CLOUD_LIFECYCLE", terminal.get("terminal_source").getAsString());
        Assert.assertTrue(terminal.get("terminal_authoritative").getAsBoolean());
        Assert.assertEquals(8L, terminal.get("latest_completed_checkpoint_sequence").getAsLong());
        Assert.assertEquals(4096L, terminal.get("latest_completed_transfer_payload_bytes").getAsLong());
        Assert.assertFalse(terminal.has("failure_phase"));
        Assert.assertFalse(terminal.has("failed_component"));
    }

    @Test
    public void cloudLifecycleTerminalIsIdempotent() {
        run.setTerminalSource("ENGINE_TERMINAL");
        run.setTerminalVersion(1);
        run.setTerminalAuthoritative(true);

        service.applyCloudLifecycleTerminal(run);

        Assert.assertEquals("CLOUD_LIFECYCLE", run.getTerminalSource());
        Assert.assertEquals(Integer.valueOf(2), run.getTerminalVersion());
        Assert.assertTrue(run.isTerminalAuthoritative());
        service.applyCloudLifecycleTerminal(run);
        Assert.assertEquals(Integer.valueOf(2), run.getTerminalVersion());
    }

    @Test
    public void completedSessionRejectsLateFailureEvidenceAndClearsPersistedMetadata() {
        session.setState("COMPLETED");
        session.setFailurePhase("REVERSE_TRANSFER");
        session.setFailedComponent("ftctl");
        DrFailbackSessionVO verified = new DrFailbackSessionVO(
                plan.getId(), run.getId(), "session-a", "COMPLETED");
        Mockito.when(drFailbackSessionDao.findById(session.getId())).thenReturn(verified);
        JsonObject lateRuntime = runtime("ACKNOWLEDGED");
        lateRuntime.addProperty("failure_phase", "LATE_READY_SAMPLE");
        lateRuntime.addProperty("failed_component", "ftctl");

        DrFailbackSessionVO result = service.reconcile(plan, run, lateRuntime);

        Assert.assertSame(session, result);
        Assert.assertNull(result.getFailurePhase());
        Assert.assertNull(result.getFailedComponent());
        Assert.assertNull(result.getErrorCode());
        Assert.assertNull(result.getErrorMessage());
        Mockito.verify(drFailbackSessionDao).clearFailureMetadata(session.getId());
        Mockito.verify(drFailbackSessionDao, Mockito.never()).update(
                Mockito.eq(session.getId()), Mockito.any(DrFailbackSessionVO.class));
        Mockito.verify(drEventDao, Mockito.never()).persist(Mockito.any());
    }

    @Test(expected = CloudRuntimeException.class)
    public void completedSessionRejectsUnclearedFailureMetadataAfterDaoUpdate() {
        session.setState("COMPLETED");
        session.setFailedComponent("ftctl");
        DrFailbackSessionVO stillStale = new DrFailbackSessionVO(
                plan.getId(), run.getId(), "session-a", "COMPLETED");
        stillStale.setFailedComponent("ftctl");
        Mockito.when(drFailbackSessionDao.findById(session.getId())).thenReturn(stillStale);

        service.reconcile(plan, run, runtime("ACKNOWLEDGED"));
    }

    @Test
    public void lifecycleRejectsRunNotFoundPayloadMixedWithOlderPlanOwner() {
        JsonObject mixedRuntime = new JsonObject();
        mixedRuntime.addProperty("result", "run_not_found");
        mixedRuntime.addProperty("run_uuid", run.getUuid());
        mixedRuntime.addProperty("control_request_run_uuid", "older-run");
        mixedRuntime.addProperty("failback_session_id", "plan:older-run");

        Assert.assertFalse(service.runtimeBelongsToRun(mixedRuntime, run));

        mixedRuntime.addProperty("control_request_run_uuid", run.getUuid());
        mixedRuntime.addProperty("failback_session_id", "plan:" + run.getUuid());
        Assert.assertTrue(service.runtimeBelongsToRun(mixedRuntime, run));
    }

    private JsonObject runtime(String outcome) {
        JsonObject runtime = new JsonObject();
        runtime.addProperty("state", "SYNCING");
        runtime.addProperty("failback_session_id", "session-a");
        runtime.addProperty("failback_commit_outcome", outcome);
        runtime.addProperty("active_side", "SOURCE");
        runtime.addProperty("target_power_state", "POWERED_OFF");
        runtime.addProperty("source_power_state", "POWERED_ON");
        return runtime;
    }
}
