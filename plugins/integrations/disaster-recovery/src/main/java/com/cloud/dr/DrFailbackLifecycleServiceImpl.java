// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information.
package com.cloud.dr;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Provider;

import org.apache.commons.lang3.StringUtils;
import org.apache.cloudstack.managed.context.ManagedContextRunnable;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.FtctlDrActionAnswer;
import com.cloud.agent.api.FtctlDrActionCommand;
import com.cloud.agent.api.FtctlDrStatusAnswer;
import com.cloud.agent.api.FtctlDrStatusCommand;
import com.cloud.dr.adapter.ftctl.DrRemoteAgentClient;
import com.cloud.dr.adapter.ftctl.FtctlDrUnifiedActionAdapter;
import com.cloud.dr.dao.DrEventDao;
import com.cloud.dr.dao.DrCutoverSessionDao;
import com.cloud.dr.dao.DrFailbackSessionDao;
import com.cloud.dr.dao.DrPlanDao;
import com.cloud.dr.dao.DrPlanViewCacheDao;
import com.cloud.dr.dao.DrReplicaDao;
import com.cloud.dr.dao.DrRunDao;
import com.cloud.dr.dao.DrRunStepDao;
import com.cloud.dr.dao.DrSiteDao;
import com.cloud.dr.inventory.DrMoldInventoryClient;
import com.cloud.dr.inventory.DrVmwareInventoryClient;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.concurrency.NamedThreadFactory;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.UserVmManager;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachineProfile;
import com.cloud.vm.dao.UserVmDao;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class DrFailbackLifecycleServiceImpl extends ManagerBase implements DrFailbackLifecycleService {
    private static final Gson GSON = new Gson();
    private static final String DATA_READY = "DATA_READY";
    private static final String DATA_EVIDENCE_PENDING = "DATA_EVIDENCE_PENDING";
    private static final String COMMIT_VERIFYING = "COMMIT_VERIFYING";
    private static final String PROTECTION_RESUMING = "PROTECTION_RESUMING";
    private static final String COMPLETED = "COMPLETED";
    private static final String ACKNOWLEDGED = "ACKNOWLEDGED";
    private static final String UNKNOWN = "UNKNOWN";
    private static final String REJECTED = "REJECTED";
    private static final String ROLLED_BACK = "ROLLED_BACK";
    private static final long RECONCILE_INTERVAL_SECONDS = 5L;
    private static final int RECONCILE_BATCH_SIZE = 25;
    private static final int GLOBAL_LOCK_TIMEOUT_SECONDS = 1;
    private static final long DATA_EVIDENCE_PUBLICATION_GRACE_MILLIS = 20000L;

    @Inject private DrFailbackSessionDao drFailbackSessionDao;
    @Inject private DrPlanDao drPlanDao;
    @Inject private DrRunDao drRunDao;
    @Inject private DrReplicaDao drReplicaDao;
    @Inject private DrPlanViewCacheDao drPlanViewCacheDao;
    @Inject private DrSiteDao drSiteDao;
    @Inject private DrSiteCredentialService drSiteCredentialService;
    @Inject private DrMoldInventoryClient drMoldInventoryClient;
    @Inject private DrVmwareInventoryClient drVmwareInventoryClient;
    @Inject private UserVmDao userVmDao;
    @Inject private UserVmManager userVmManager;
    @Inject private AgentManager agentManager;
    @Inject private DrEventDao drEventDao;
    @Inject private DrCutoverSessionDao drCutoverSessionDao;
    @Inject private DrRunStepDao drRunStepDao;
    @Inject private DrSourceIsolationPreflightService drSourceIsolationPreflightService;
    @Inject private DrFailbackDataGateService drFailbackDataGateService;
    @Inject private DrRemoteAgentClient drRemoteAgentClient;
    @Inject private DrPlanOwnedTransportService drPlanOwnedTransportService;
    @Inject private Provider<FtctlDrUnifiedActionAdapter> ftctlDrUnifiedActionAdapterProvider;
    @Inject private DrWorkerPlacementService drWorkerPlacementService;

    private final Set<Long> inFlightRuns = ConcurrentHashMap.newKeySet();
    private ExecutorService executor;
    private ScheduledExecutorService reconciler;

    @Override
    public boolean start() {
        executor = Executors.newSingleThreadExecutor(new NamedThreadFactory("DrFailbackLifecycle"));
        reconciler = Executors.newSingleThreadScheduledExecutor(
                new NamedThreadFactory("DrFailbackLifecycleReconciler"));
        reconciler.scheduleWithFixedDelay(new ReconcileTask(), RECONCILE_INTERVAL_SECONDS,
                RECONCILE_INTERVAL_SECONDS, TimeUnit.SECONDS);
        return true;
    }

    @Override
    public boolean stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        if (reconciler != null) {
            reconciler.shutdownNow();
            reconciler = null;
        }
        return true;
    }

    private final class ReconcileTask extends ManagedContextRunnable {
        @Override
        protected void runInContext() {
            GlobalLock lock = GlobalLock.getInternLock("DrFailbackLifecycleReconciler");
            try {
                if (lock.lock(GLOBAL_LOCK_TIMEOUT_SECONDS)) {
                    try {
                        reconcilePendingSessions();
                    } finally {
                        lock.unlock();
                    }
                }
            } catch (RuntimeException e) {
                logger.warn("Failed to reconcile pending DR failback lifecycles", e);
            } finally {
                lock.releaseRef();
            }
        }
    }

    private void reconcilePendingSessions() {
        Date probeBefore = new Date(System.currentTimeMillis() - RECONCILE_INTERVAL_SECONDS * 1000L);
        List<DrFailbackSessionVO> sessions =
                drFailbackSessionDao.listReconcileCandidates(probeBefore, RECONCILE_BATCH_SIZE);
        for (DrFailbackSessionVO session : sessions) {
            submitLifecycle(session.getRunId());
        }
    }

    @Override
    public DrFailbackSessionVO reconcile(DrPlanVO plan, DrRunVO run, JsonObject runtime) {
        if (plan == null || run == null || !StringUtils.equalsIgnoreCase(run.getRunType(), DrConstants.RUN_TYPE_FAILBACK)) {
            return null;
        }
        String engineSessionId = stringValue(runtime, "failback_session_id");
        String runtimeState = upper(stringValue(runtime, "state"));
        DrFailbackSessionVO session = drFailbackSessionDao.findActiveByRunId(run.getId());
        if (DrFailbackSessionState.isTerminalRunFailureWithoutEngineArtifacts(session, run)) {
            terminalizeArtifactFreeFailure(session, run);
            return session;
        }
        if (isCanceledFailbackPendingCompensation(run, session)) {
            cancelAndRestoreTargetAuthority(plan, run);
            return drFailbackSessionDao.findActiveByRunId(run.getId());
        }
        if (requiresRolledBackFailureConvergence(session, run)) {
            convergePreAuthorityFailure(plan, run, session,
                    defaultValue(session.getFailurePhase(), "CLOUD_LIFECYCLE"),
                    defaultValue(session.getFailedComponent(), "ftctl-failback-abort"),
                    defaultValue(session.getErrorCode(), "DR_FAILBACK_LIFECYCLE_GATE_FAILED"),
                    defaultValue(session.getErrorMessage(), "Failback was rolled back to TARGET authority"),
                    runtime);
            return drFailbackSessionDao.findActiveByRunId(run.getId());
        }
        if (!runtimeBelongsToRun(runtime, run)) {
            return session;
        }
        if (session != null && isTerminal(session.getState())) {
            return convergeSuccessfulTerminalFailureMetadata(session);
        }
        if (session == null) {
            String resolvedSessionId = StringUtils.defaultIfBlank(engineSessionId,
                    plan.getUuid() + ":" + run.getUuid());
            session = new DrFailbackSessionVO(plan.getId(), run.getId(), resolvedSessionId,
                    initialRuntimeState(runtime, runtimeState));
            session.setAcceptanceState(booleanValue(runtime, "accepted") == Boolean.FALSE ? "REJECTED" : "ACCEPTED");
            session.setCheckpointSequence(longValue(runtime, "failback_restore_point_sequence"));
            initializeResumeCheckpointContract(session);
            session.setTargetPowerState(defaultValue(stringValue(runtime, "target_power_state"), "POWERED_ON"));
            session.setSourcePowerState(defaultValue(stringValue(runtime, "source_power_state"), "POWERED_OFF"));
            session.setEngineAckState(defaultValue(stringValue(runtime, "engine_ack_state"), "PENDING"));
            session.setCommitOutcome(defaultValue(stringValue(runtime, "failback_commit_outcome"), "PENDING"));
            session.setSchedulerGeneration(longValue(runtime, "control_generation"));
            session.setSchedulerAckGeneration(longValue(runtime, "control_ack_generation"));
            session.setSchedulerState(stringValue(runtime, "scheduler_state"));
            session.setRollbackState(defaultValue(stringValue(runtime, "rollback_state"), "NONE"));
            session.setRollbackGeneration(longValue(runtime, "rollback_generation"));
            updateRuntimeEvidence(session, runtime);
            updateDataEvidence(plan, session, runtime);
            refreshCommitPrerequisites(plan, run, session, runtime);
            session.setDetailsJson(GSON.toJson(runtime));
            if (StringUtils.equals(session.getState(), DATA_READY)) {
                session.setDataReadyAt(new Date());
            }
            session = drFailbackSessionDao.persist(session);
            if (StringUtils.equals(session.getState(), DATA_READY)) {
                recordEvent(plan, run, "FAILBACK_DATA_READY", "Reverse replication is durable; Cloud lifecycle commit is queued");
            }
        } else if (session != null) {
            if (StringUtils.isNotBlank(engineSessionId)
                    && !StringUtils.equals(engineSessionId, session.getEngineSessionId())) {
                session.setEngineSessionId(engineSessionId);
            }
            session.setDetailsJson(GSON.toJson(runtime));
            updateRuntimeEvidence(session, runtime);
            updateDataEvidence(plan, session, runtime);
            refreshCommitPrerequisites(plan, run, session, runtime);
            if (StringUtils.equals(runtimeState, "FAILBACK_DATA_READY")
                    && StringUtils.equalsAnyIgnoreCase(session.getState(), "REVERSE_SYNCING", DATA_READY)) {
                session.setState(DATA_READY);
                if (session.getDataReadyAt() == null) {
                    session.setDataReadyAt(new Date());
                }
            }
            session.markUpdated();
            drFailbackSessionDao.update(session.getId(), session);
        }
        if (session == null) {
            return null;
        }

        if (isTerminalPublicationPending(runtime)) {
            return session;
        }
        if (isEarlyRuntimeFailure(runtime, runtimeState) && !isTerminal(session.getState())) {
            String errorCode = defaultValue(stringValue(runtime, "error_code"), "DR_FAILBACK_WORKER_EXITED");
            String errorMessage = defaultValue(stringValue(runtime, "error_message"),
                    "Failback worker terminated before reverse data became durable");
            failBeforeAuthorityTransition(plan, run, session,
                    defaultValue(stringValue(runtime, "failure_phase"), "REVERSE_TRANSFER"),
                    defaultValue(stringValue(runtime, "failed_component"), "ftctl-failback-worker"),
                    errorCode, errorMessage);
            recordEvent(plan, run, "FAILBACK_REVERSE_SYNC_FAILED", errorMessage);
            return drFailbackSessionDao.findActiveByRunId(run.getId());
        }

        String commitOutcome = upper(defaultValue(stringValue(runtime, "failback_commit_outcome"),
                session.getCommitOutcome()));
        if (StringUtils.equals(commitOutcome, ACKNOWLEDGED)
                && !StringUtils.equals(session.getState(), PROTECTION_RESUMING)
                && !isTerminal(session.getState())) {
            if (cloudPowerStatesMatch(plan)) {
                acknowledgeCommit(plan, run, session, runtime);
            } else {
                markCommitVerifying(plan, session, null);
                submitLifecycle(run.getId());
            }
        } else if (StringUtils.equals(commitOutcome, ROLLED_BACK) && !isTerminal(session.getState())) {
            convergePreAuthorityFailure(plan, run, session,
                    defaultValue(session.getFailurePhase(), "CLOUD_LIFECYCLE"),
                    defaultValue(session.getFailedComponent(), "ftctl-failback-abort"),
                    defaultValue(session.getErrorCode(), "DR_FAILBACK_LIFECYCLE_GATE_FAILED"),
                    defaultValue(session.getErrorMessage(), "Failback was rolled back to TARGET authority"),
                    runtime);
        } else if (StringUtils.equalsAny(session.getState(), DATA_READY, DATA_EVIDENCE_PENDING)
                || StringUtils.equals(session.getState(), COMMIT_VERIFYING)) {
            submitLifecycle(run.getId());
        } else if (StringUtils.equals(session.getState(), PROTECTION_RESUMING)) {
            if (protectionResumed(plan, session, runtime)) {
                completeLifecycle(plan, run, session, runtime);
            } else {
                submitLifecycle(run.getId());
            }
        }
        return drFailbackSessionDao.findActiveByRunId(run.getId());
    }

    private void submitLifecycle(long runId) {
        if (executor == null || !inFlightRuns.add(runId)) {
            return;
        }
        try {
            executor.submit(() -> {
                try {
                    executeLifecycle(runId);
                } finally {
                    inFlightRuns.remove(runId);
                }
            });
        } catch (RejectedExecutionException e) {
            inFlightRuns.remove(runId);
        }
    }

    private void executeLifecycle(long runId) {
        DrRunVO run = drRunDao.findById(runId);
        DrFailbackSessionVO session = drFailbackSessionDao.findActiveByRunId(runId);
        if (DrFailbackSessionState.isTerminalRunFailureWithoutEngineArtifacts(session, run)) {
            terminalizeArtifactFreeFailure(session, run);
            return;
        }
        if (isCanceledFailbackPendingCompensation(run, session)) {
            DrPlanVO plan = drPlanDao.findById(run.getPlanId());
            if (plan != null) {
                cancelAndRestoreTargetAuthority(plan, run);
            }
            return;
        }
        if (run != null && requiresRolledBackFailureConvergence(session, run)) {
            DrPlanVO plan = drPlanDao.findById(run.getPlanId());
            if (plan != null) {
                convergePreAuthorityFailure(plan, run, session,
                        defaultValue(session.getFailurePhase(), "CLOUD_LIFECYCLE"),
                        defaultValue(session.getFailedComponent(), "ftctl-failback-abort"),
                        defaultValue(session.getErrorCode(), "DR_FAILBACK_LIFECYCLE_GATE_FAILED"),
                        defaultValue(session.getErrorMessage(), "Failback was rolled back to TARGET authority"),
                        parseObject(session.getDetailsJson()));
            }
            return;
        }
        if (run == null || session == null || isTerminal(session.getState())) {
            return;
        }
        DrPlanVO plan = drPlanDao.findById(run.getPlanId());
        if (plan == null) {
            failSession(session, "DR_FAILBACK_PLAN_REMOVED", "DR plan was removed during failback");
            return;
        }
        session.setLastProbeAt(new Date());
        session.markUpdated();
        drFailbackSessionDao.update(session.getId(), session);
        if (StringUtils.equals(session.getState(), COMMIT_VERIFYING)) {
            verifyCommitOutcome(plan, run, session);
            return;
        }
        if (StringUtils.equals(session.getState(), PROTECTION_RESUMING)) {
            JsonObject authorityRuntime = fetchStatusRuntime(plan, run,
                    FtctlDrStatusCommand.StatusScope.PLAN_AUTHORITY);
            if (!ownedResumeWorkerActive(plan, run, authorityRuntime)) {
                ensureRemoteSourceSchedulerResumedForProtectionResume(plan, run, session);
                authorityRuntime = fetchStatusRuntime(plan, run, FtctlDrStatusCommand.StatusScope.PLAN_AUTHORITY);
            }
            if (protectionResumed(plan, session, authorityRuntime)) {
                completeLifecycle(plan, run, session, authorityRuntime);
            }
            return;
        }
        if (StringUtils.equalsAny(session.getState(), "ABORTING", "ROLLBACK_FAILED")) {
            failBeforeAuthorityTransition(plan, run, session,
                    defaultValue(session.getFailurePhase(), "CLOUD_LIFECYCLE"),
                    defaultValue(session.getFailedComponent(), "cloud-failback-gate"),
                    defaultValue(session.getErrorCode(), "DR_FAILBACK_LIFECYCLE_GATE_FAILED"),
                    defaultValue(session.getErrorMessage(), "Failback lifecycle validation failed"));
            return;
        }
        if (!StringUtils.equalsAny(session.getState(), DATA_READY, DATA_EVIDENCE_PENDING)) {
            return;
        }
        JsonObject evidenceRuntime = fetchStatusRuntime(plan, run,
                FtctlDrStatusCommand.StatusScope.OPERATION);
        String evidenceRunUuid = stringValue(evidenceRuntime, "reverse_evidence_run_uuid");
        if (StringUtils.isNotBlank(evidenceRunUuid) && !StringUtils.equals(evidenceRunUuid, run.getUuid())) {
            failBeforeAuthorityTransition(plan, run, session, "CLOUD_LIFECYCLE_DATA_GATE",
                    "cloud-failback-data-gate", "DR_FAILBACK_DATA_EVIDENCE_INCONSISTENT",
                    "Durable reverse-data evidence belongs to a different Run");
            return;
        }
        updateDataEvidence(plan, session, evidenceRuntime);
        session.setDetailsJson(GSON.toJson(evidenceRuntime));
        session.markUpdated();
        drFailbackSessionDao.update(session.getId(), session);
        DrFailbackDataGateResult dataGate = drFailbackDataGateService.validate(plan, run, session);
        if (!dataGate.isReady()) {
            if (StringUtils.equals(dataGate.getErrorCode(), "DR_FAILBACK_DATA_EVIDENCE_INCOMPLETE")
                    && withinEvidencePublicationGrace(session)) {
                boolean firstPending = !StringUtils.equals(session.getState(), DATA_EVIDENCE_PENDING);
                session.setState(DATA_EVIDENCE_PENDING);
                session.markUpdated();
                drFailbackSessionDao.update(session.getId(), session);
                if (firstPending) {
                    recordEvent(plan, run, "FAILBACK_DATA_EVIDENCE_PENDING",
                            "Waiting for FTCTL to publish durable reverse-data evidence");
                }
                return;
            }
            failBeforeAuthorityTransition(plan, run, session, "CLOUD_LIFECYCLE_DATA_GATE",
                    "cloud-failback-data-gate", dataGate.getErrorCode(), dataGate.getMessage());
            recordEvent(plan, run, "FAILBACK_DATA_GATE_BLOCKED", dataGate.getMessage());
            return;
        }
        if (StringUtils.equals(session.getState(), DATA_EVIDENCE_PENDING)) {
            session.setState(DATA_READY);
            session.markUpdated();
            drFailbackSessionDao.update(session.getId(), session);
            recordEvent(plan, run, "FAILBACK_DATA_EVIDENCE_READY",
                    "Durable reverse-data evidence publication completed");
        }
        DrSourceIsolationPreflightResult transitionPreflight =
                drSourceIsolationPreflightService.validate(plan, run, DrConstants.RUN_TYPE_FAILBACK);
        if (!transitionPreflight.isReady()) {
            failBeforeAuthorityTransition(plan, run, session, "CLOUD_SOURCE_ISOLATION_GATE",
                    "cloud-source-isolation-preflight", transitionPreflight.getErrorCode(),
                    transitionPreflight.getMessage());
            recordEvent(plan, run, "FAILBACK_SOURCE_ISOLATION_PREFLIGHT_FAILED",
                    transitionPreflight.getMessage());
            return;
        }
        try {
            refreshCommitPrerequisites(plan, run, session, evidenceRuntime);
            prepareCommitDispatch(plan, run, session);
        } catch (CloudRuntimeException e) {
            failBeforeAuthorityTransition(plan, run, session, "CLOUD_COMMIT_CONTRACT_GATE",
                    "cloud-failback-commit-contract", "DR_FAILBACK_COMMIT_CONTRACT_INVALID", e.getMessage());
            recordEvent(plan, run, "FAILBACK_COMMIT_CONTRACT_BLOCKED", e.getMessage());
            return;
        }
        boolean targetStopped = false;
        boolean sourceStarted = false;
        boolean commitAttempted = false;
        try {
            updateSession(session, "TARGET_STOPPING", null);
            String targetState = ensureTargetPowerState(plan, false);
            targetStopped = true;
            session.setTargetPowerState(targetState);
            session.setTargetStoppedAt(new Date());
            updateSession(session, "TARGET_STOPPED", null);

            stopReversePlanOwnedExport(plan, run);

            updateSession(session, "FORWARD_TRANSPORT_PREPARING", null);
            restoreForwardPlanOwnedExport(plan, run);
            updateSession(session, "FORWARD_TRANSPORT_READY", null);

            updateSession(session, "SOURCE_STARTING", null);
            String sourceState = ensureSourcePowerState(plan, true);
            sourceStarted = true;
            session.setSourcePowerState(sourceState);
            session.setSourcePoweredOnAt(new Date());
            updateSession(session, "SOURCE_BOOT_VALIDATING", null);
            if (!StringUtils.equals(sourceState, "POWERED_ON")) {
                throw new CloudRuntimeException("Source VM did not reach POWERED_ON");
            }
            session.setBootValidationState(validateSourceBootState(plan));
            session.setBootValidatedAt(new Date());
            session.setCommitOutcome("PENDING");
            session.setCommitRequestedAt(new Date());
            initializeResumeCheckpointContract(session);
            session.setCommitEnvelopeSha256(DrFailbackCommitEnvelope.sha256(plan, run, session,
                    session.getTargetPowerState(), session.getSourcePowerState(), session.getBootValidationState()));
            session.setCommitDispatchState("DISPATCHING");
            updateSession(session, "AUTHORITY_COMMITTING", null);

            commitAttempted = true;
            Answer answer = sendEngineCommand(plan, run, session, FtctlDrActionCommand.Action.FAILBACK_COMMIT);
            session.setCommitDispatchState("DISPATCHED");
            session.setCommitDispatchedAt(new Date());
            session.markUpdated();
            drFailbackSessionDao.update(session.getId(), session);
            String outcome = commitOutcome(answer);
            if (StringUtils.equals(outcome, ACKNOWLEDGED)) {
                if (!cloudPowerStatesMatch(plan)) {
                    markCommitVerifying(plan, session, answer);
                    return;
                }
                acknowledgeCommit(plan, run, session, answerPayload(answer));
                return;
            }
            if (StringUtils.equals(outcome, UNKNOWN)) {
                markCommitVerifying(plan, session, answer);
                return;
            }
            throw new CloudRuntimeException(answer != null ? answer.getDetails()
                    : "Agent returned no failback commit acknowledgement");
        } catch (Exception e) {
            if (commitAttempted && isUncertainFailure(e.getMessage())) {
                markCommitVerifying(plan, session, null);
                return;
            }
            boolean rolledBack = rollbackFailback(plan, run, session, targetStopped, sourceStarted);
            if (rolledBack) {
                session.setErrorCode("DR_FAILBACK_LIFECYCLE_REJECTED");
                session.setErrorMessage(StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()));
                session.markUpdated();
                drFailbackSessionDao.update(session.getId(), session);
            } else {
                session.setErrorCode("DR_FAILBACK_ROLLBACK_FAILED");
                session.setErrorMessage(StringUtils.defaultIfBlank(session.getErrorMessage(), e.getMessage()));
                session.markUpdated();
                drFailbackSessionDao.update(session.getId(), session);
            }
            recordEvent(plan, run, "FAILBACK_LIFECYCLE_FAILED", session.getErrorMessage());
        }
    }

    private void updateDataEvidence(DrPlanVO plan, DrFailbackSessionVO session, JsonObject runtime) {
        if (runtime == null || (!runtime.has("replication_direction") && !runtime.has("reverse_direction")
                && !runtime.has("provider_pair") && !runtime.has("baseline_generation")
                && !runtime.has("baseline_state") && !runtime.has("tracker_state")
                && !runtime.has("writer_state") && !runtime.has("target_written")
                && !runtime.has("write_verified") && !runtime.has("reverse_guest_compatibility_state")
                && !runtime.has("guest_compatibility_state"))) {
            return;
        }
        DrFailbackRouteContract runtimeRoute = DrFailbackRouteContract.normalize(
                stringValue(runtime, "replication_direction"), stringValue(runtime, "provider_pair"),
                stringValue(runtime, "reverse_direction"));
        if (runtimeRoute.hasDirection()) {
            session.setReplicationDirection(runtimeRoute.getReplicationDirection());
        } else if (StringUtils.isBlank(session.getReplicationDirection())) {
            session.setReplicationDirection(DrFailbackRouteContract.forPlan(plan).getReplicationDirection());
        }
        if (runtimeRoute.hasProviderPair()) {
            session.setProviderPair(runtimeRoute.getProviderPair());
        } else if (StringUtils.isBlank(session.getProviderPair())) {
            session.setProviderPair(DrFailbackRouteContract.forPlan(plan).getProviderPair());
        }
        session.setBaselineGeneration(firstNonNull(longValue(runtime, "baseline_generation"),
                session.getBaselineGeneration()));
        session.setBaselineState(defaultValue(stringValue(runtime, "baseline_state"), session.getBaselineState()));
        session.setTrackerState(defaultValue(stringValue(runtime, "tracker_state"), session.getTrackerState()));
        session.setWriterState(defaultValue(stringValue(runtime, "writer_state"), session.getWriterState()));
        Boolean targetWritten = booleanValue(runtime, "target_written");
        if (targetWritten != null) {
            session.setTargetWritten(targetWritten);
        }
        Boolean writeVerified = booleanValue(runtime, "write_verified");
        if (writeVerified != null) {
            session.setWriteVerified(writeVerified);
        }
        session.setGuestCompatibilityState(defaultValue(stringValue(runtime, "reverse_guest_compatibility_state"),
                defaultValue(stringValue(runtime, "guest_compatibility_state"),
                        session.getGuestCompatibilityState())));
    }

    private boolean withinEvidencePublicationGrace(DrFailbackSessionVO session) {
        Date startedAt = session.getDataReadyAt();
        if (startedAt == null) {
            startedAt = new Date();
            session.setDataReadyAt(startedAt);
        }
        return System.currentTimeMillis() - startedAt.getTime() < DATA_EVIDENCE_PUBLICATION_GRACE_MILLIS;
    }

    private String ensureTargetPowerState(DrPlanVO plan, boolean poweredOn) throws Exception {
        DrReplicaVO replica = firstReplica(plan.getId());
        if (replica == null) {
            throw new CloudRuntimeException("DR target replica was not found");
        }
        UserVmVO localVm = replica.getTargetVmId() != null ? userVmDao.findById(replica.getTargetVmId()) : null;
        if (localVm != null && localVm.getRemoved() == null) {
            return ensureLocalPowerState(localVm, null, poweredOn);
        }
        DrSiteVO site = drSiteDao.findById(plan.getTargetSiteId());
        return ensureRemotePowerState(site, replica.getTargetExternalRef(), poweredOn);
    }

    private String ensureSourcePowerState(DrPlanVO plan, boolean poweredOn) throws Exception {
        UserVmVO localVm = plan.getSourceVmId() != null ? userVmDao.findById(plan.getSourceVmId()) : null;
        if (localVm != null && localVm.getRemoved() == null) {
            return ensureLocalPowerState(localVm, null, poweredOn);
        }
        DrSiteVO site = drSiteDao.findById(plan.getSourceSiteId());
        return ensureRemotePowerState(site, plan.getSourceExternalRef(), poweredOn);
    }

    private String validateSourceBootState(DrPlanVO plan) {
        if (StringUtils.equals(resolveFailbackBootValidationMode(plan), "POWER_STATE_ONLY")) {
            return "POWER_STATE_VALIDATED";
        }
        DrSiteVO sourceSite = drSiteDao.findById(plan.getSourceSiteId());
        if (sourceSite != null && (StringUtils.equalsAnyIgnoreCase(sourceSite.getHypervisorType(), "VMWARE", "VMware")
                || StringUtils.equalsIgnoreCase(sourceSite.getSiteType(), "VMWARE_DIRECT"))) {
            try (DrResolvedSiteCredential credential = drSiteCredentialService.resolveCredential(sourceSite)) {
                return drVmwareInventoryClient.validateVirtualMachineGuestBoot(credential, plan.getSourceExternalRef());
            }
        }
        return "POWER_STATE_VALIDATED";
    }

    String resolveFailbackBootValidationMode(DrPlanVO plan) {
        if (isWindowsSource(plan)) {
            return "GUEST_HEARTBEAT_REQUIRED";
        }
        JsonObject policy = plan != null ? parseObject(plan.getPolicyJson()) : null;
        String mode = policy != null ? stringValue(policy, "failbackBootValidationMode") : null;
        if (StringUtils.isBlank(mode) && policy != null) {
            mode = stringValue(policy, "testBootValidationMode");
        }
        return StringUtils.equalsIgnoreCase(mode, "POWER_STATE_ONLY")
                ? "POWER_STATE_ONLY" : "GUEST_HEARTBEAT_REQUIRED";
    }

    boolean isWindowsSource(DrPlanVO plan) {
        JsonObject mapping = plan != null ? parseObject(plan.getMappingJson()) : null;
        JsonObject source = objectValue(mapping, "source");
        JsonObject hardware = objectValue(source, "hardware");
        JsonObject vm = objectValue(source, "vm");
        String guestId = defaultValue(stringValue(hardware, "guestId"), stringValue(vm, "guestId"));
        return StringUtils.containsIgnoreCase(guestId, "windows");
    }

    private String ensureLocalPowerState(UserVmVO vm, Long hostId, boolean poweredOn)
            throws ConcurrentOperationException, InsufficientCapacityException,
            ResourceAllocationException, ResourceUnavailableException {
        UserVmVO current = userVmDao.findById(vm.getId());
        if (poweredOn && current.getState() != VirtualMachine.State.Running) {
            userVmManager.startVirtualMachine(current.getId(), hostId,
                    new HashMap<VirtualMachineProfile.Param, Object>(), null);
        } else if (!poweredOn && current.getState() != VirtualMachine.State.Stopped) {
            userVmManager.stopVirtualMachine(current.getId(), true);
        }
        current = userVmDao.findById(vm.getId());
        String state = current != null && current.getState() == VirtualMachine.State.Running
                ? "POWERED_ON" : current != null && current.getState() == VirtualMachine.State.Stopped
                ? "POWERED_OFF" : "UNKNOWN";
        String expected = poweredOn ? "POWERED_ON" : "POWERED_OFF";
        if (!StringUtils.equals(state, expected)) {
            throw new CloudRuntimeException("Cloud VM did not reach " + expected + ": " + vm.getUuid());
        }
        return state;
    }

    private String ensureRemotePowerState(DrSiteVO site, String vmRef, boolean poweredOn) {
        if (site == null || StringUtils.isBlank(vmRef)) {
            throw new CloudRuntimeException("Remote DR site or VM reference is missing");
        }
        try (DrResolvedSiteCredential credential = drSiteCredentialService.resolveCredential(site)) {
            if (StringUtils.equalsAnyIgnoreCase(site.getHypervisorType(), "VMWARE", "VMware")
                    || StringUtils.equalsIgnoreCase(site.getSiteType(), "VMWARE_DIRECT")) {
                return drVmwareInventoryClient.ensureVirtualMachinePowerState(credential, vmRef, poweredOn);
            }
            return drMoldInventoryClient.ensureVirtualMachinePowerState(credential, vmRef, poweredOn);
        }
    }

    protected boolean cloudPowerStatesMatch(DrPlanVO plan) {
        try {
            return StringUtils.equals(readSourcePowerState(plan), "POWERED_ON")
                    && StringUtils.equals(readTargetPowerState(plan), "POWERED_OFF");
        } catch (RuntimeException e) {
            logger.warn(String.format("Could not verify Cloud failback authority for DR plan %s",
                    plan != null ? plan.getId() : null), e);
            return false;
        }
    }

    private String readTargetPowerState(DrPlanVO plan) {
        DrReplicaVO replica = firstReplica(plan.getId());
        if (replica == null) {
            throw new CloudRuntimeException("DR target replica was not found");
        }
        UserVmVO localVm = replica.getTargetVmId() != null ? userVmDao.findById(replica.getTargetVmId()) : null;
        if (localVm != null && localVm.getRemoved() == null) {
            return localPowerState(localVm);
        }
        DrSiteVO site = drSiteDao.findById(plan.getTargetSiteId());
        return readRemotePowerState(site, replica.getTargetExternalRef());
    }

    private String readSourcePowerState(DrPlanVO plan) {
        UserVmVO localVm = plan.getSourceVmId() != null ? userVmDao.findById(plan.getSourceVmId()) : null;
        if (localVm != null && localVm.getRemoved() == null) {
            return localPowerState(localVm);
        }
        DrSiteVO site = drSiteDao.findById(plan.getSourceSiteId());
        return readRemotePowerState(site, plan.getSourceExternalRef());
    }

    private String localPowerState(UserVmVO vm) {
        UserVmVO current = userVmDao.findById(vm.getId());
        if (current == null || current.getRemoved() != null) {
            return "UNKNOWN";
        }
        if (current.getState() == VirtualMachine.State.Running) {
            return "POWERED_ON";
        }
        if (current.getState() == VirtualMachine.State.Stopped) {
            return "POWERED_OFF";
        }
        return "UNKNOWN";
    }

    private String readRemotePowerState(DrSiteVO site, String vmRef) {
        if (site == null || StringUtils.isBlank(vmRef)) {
            return "UNKNOWN";
        }
        try (DrResolvedSiteCredential credential = drSiteCredentialService.resolveCredential(site)) {
            if (StringUtils.equalsAnyIgnoreCase(site.getHypervisorType(), "VMWARE", "VMware")
                    || StringUtils.equalsIgnoreCase(site.getSiteType(), "VMWARE_DIRECT")) {
                return drVmwareInventoryClient.getVirtualMachinePowerState(credential, vmRef);
            }
            return drMoldInventoryClient.getVirtualMachinePowerState(credential, vmRef);
        }
    }

    private Answer sendEngineCommand(DrPlanVO plan, DrRunVO run, DrFailbackSessionVO session,
            FtctlDrActionCommand.Action action) {
        return sendEngineCommand(plan, run, session, action, null);
    }

    private Answer sendEngineCommand(DrPlanVO plan, DrRunVO run, DrFailbackSessionVO session,
            FtctlDrActionCommand.Action action, String rollbackPhase) {
        Long hostId = drWorkerPlacementService != null
                ? drWorkerPlacementService.resolveWorkerHostId(plan, run, DrWorkerRole.COORDINATOR) : null;
        if (hostId == null) {
            throw new CloudRuntimeException("DR coordinator host is not configured");
        }
        FtctlDrActionCommand command = new FtctlDrActionCommand(action, plan.getUuid(), run.getUuid());
        command.setRunType(DrConstants.RUN_TYPE_FAILBACK);
        command.setActionIntent(DrConstants.RUN_TYPE_FAILBACK);
        command.setRole("coordinator");
        command.setWaitForCompletion(true);
        command.setFailbackCommitContractVersion(session.getCommitContractVersion());
        command.setFailbackSessionId(session.getEngineSessionId());
        command.setFailbackCheckpointSequence(session.getCheckpointSequence());
        command.setFailbackAuthorityGeneration(session.getAuthorityGeneration());
        command.setFailbackBaselineGeneration(session.getBaselineGeneration());
        command.setFailbackEvidenceRunUuid(run.getUuid());
        command.setFailbackCommitAttemptId(session.getCommitAttemptId());
        command.setFailbackCommitEnvelopeSha256(session.getCommitEnvelopeSha256());
        command.setFailbackTargetPowerState(session.getTargetPowerState());
        command.setFailbackSourcePowerState(session.getSourcePowerState());
        command.setFailbackBootValidationState(session.getBootValidationState());
        command.setResumeBaselineCheckpointSequence(session.getResumeBaselineCheckpointSequence());
        command.setMinimumCompletedCheckpointSequence(session.getRequiredPostFailbackCheckpointSequence());
        command.setForceImmediateCycle(action == FtctlDrActionCommand.Action.FAILBACK_COMMIT);
        if (StringUtils.isNotBlank(rollbackPhase)) {
            command.setContextParam("rollbackPhase", rollbackPhase);
        }
        return agentManager.easySend(hostId, command);
    }

    void stopReversePlanOwnedExport(DrPlanVO plan, DrRunVO run) {
        if (drPlanOwnedTransportService != null) {
            drPlanOwnedTransportService.stopReverseTargetExport(plan, run);
        }
    }

    void restoreForwardPlanOwnedExport(DrPlanVO plan, DrRunVO run) {
        if (drPlanOwnedTransportService != null) {
            drPlanOwnedTransportService.startForwardTargetExport(plan, run, null);
        }
    }

    void ensureForwardPlanOwnedExportForProtectionResume(DrPlanVO plan, DrRunVO run) {
        if (drPlanOwnedTransportService != null && drPlanOwnedTransportService.supports(plan)) {
            drPlanOwnedTransportService.startForwardTargetExport(plan, run, null);
        }
    }

    void ensureRemoteSourceSchedulerResumedForProtectionResume(DrPlanVO plan, DrRunVO run,
            DrFailbackSessionVO session) {
        if (drRemoteAgentClient == null || !drRemoteAgentClient.isRemoteKvmSource(plan)) {
            return;
        }
        FtctlDrUnifiedActionAdapter adapter = ftctlDrUnifiedActionAdapterProvider != null
                ? ftctlDrUnifiedActionAdapterProvider.get() : null;
        if (adapter == null) {
            throw new CloudRuntimeException("FTCTL adapter is unavailable for remote source protection resume");
        }
        FtctlDrActionAnswer answer = adapter.resumeRemoteSourceProtection(plan, run,
                session.getResumeBaselineCheckpointSequence(),
                session.getRequiredPostFailbackCheckpointSequence());
        if (answer == null || !answer.getResult()) {
            throw new CloudRuntimeException(StringUtils.defaultIfBlank(
                    answer != null ? answer.getDetails() : null,
                    "Original-site Agent did not resume forward protection"));
        }
    }

    JsonObject fetchStatusRuntime(DrPlanVO plan, DrRunVO run,
            FtctlDrStatusCommand.StatusScope scope) {
        if (scope == FtctlDrStatusCommand.StatusScope.PLAN_AUTHORITY
                && drRemoteAgentClient != null && drRemoteAgentClient.isRemoteKvmSource(plan)) {
            FtctlDrStatusAnswer remoteAnswer = drRemoteAgentClient.fetchSourceStatus(plan, null, scope);
            JsonObject remotePayload = remoteAnswer != null
                    ? parseObject(remoteAnswer.getStatusJson()) : null;
            return remotePayload != null ? remotePayload : new JsonObject();
        }
        Long hostId = drWorkerPlacementService != null
                ? drWorkerPlacementService.resolveWorkerHostId(plan, run, DrWorkerRole.COORDINATOR) : null;
        if (hostId == null) {
            return new JsonObject();
        }
        FtctlDrStatusCommand command = new FtctlDrStatusCommand(plan.getUuid(),
                scope == FtctlDrStatusCommand.StatusScope.OPERATION ? run.getUuid() : null, scope);
        command.setWait(5);
        Answer answer = agentManager.easySend(hostId, command);
        if (!(answer instanceof FtctlDrStatusAnswer)) {
            return new JsonObject();
        }
        JsonObject payload = parseObject(((FtctlDrStatusAnswer) answer).getStatusJson());
        return payload != null ? payload : new JsonObject();
    }

    private boolean rollbackFailback(DrPlanVO plan, DrRunVO run, DrFailbackSessionVO session,
            boolean targetStopped, boolean sourceStarted) {
        try {
            session.setRollbackState("FENCING");
            session.setRollbackRequestedAt(new Date());
            updateSession(session, "ROLLBACK_FENCING", null);
            Answer fenceAnswer = sendEngineCommand(plan, run, session,
                    FtctlDrActionCommand.Action.FAILBACK_ABORT, "prepare");
            if (fenceAnswer == null || !fenceAnswer.getResult()) {
                throw new CloudRuntimeException(fenceAnswer != null ? fenceAnswer.getDetails()
                        : "Agent returned no rollback fence acknowledgement");
            }
            JsonObject fencePayload = answerPayload(fenceAnswer);
            session.setRollbackState("FENCED");
            session.setRollbackGeneration(longValue(fencePayload, "rollback_generation"));
            session.setSchedulerGeneration(longValue(fencePayload, "control_generation"));
            session.setSchedulerAckGeneration(longValue(fencePayload, "control_ack_generation"));
            session.setSchedulerState(defaultValue(stringValue(fencePayload, "scheduler_state"), "STOPPED"));
            updateSession(session, "ROLLBACK_POWER_RESTORING", null);

            if (sourceStarted) {
                session.setSourcePowerState(ensureSourcePowerState(plan, false));
            }
            stopForwardPlanOwnedExportForTargetRecovery(plan, run);
            if (targetStopped) {
                session.setTargetPowerState(ensureTargetPowerState(plan, true));
            }
            Answer rollbackAnswer = sendEngineCommand(plan, run, session,
                    FtctlDrActionCommand.Action.FAILBACK_ABORT, "commit");
            if (rollbackAnswer == null || !rollbackAnswer.getResult()) {
                throw new CloudRuntimeException(rollbackAnswer != null ? rollbackAnswer.getDetails()
                        : "Agent returned no rollback commit acknowledgement");
            }
            String rollbackCode = defaultValue(session.getErrorCode(), "DR_FAILBACK_COMMIT_ROLLED_BACK");
            String rollbackMessage = defaultValue(session.getErrorMessage(),
                    "Failback commit was not durably acknowledged; target authority was restored");
            convergePreAuthorityFailure(plan, run, session,
                    defaultValue(session.getFailurePhase(), "AUTHORITY_COMMIT"),
                    defaultValue(session.getFailedComponent(), "ftctl-failback-commit"),
                    rollbackCode, rollbackMessage, answerPayload(rollbackAnswer));
            recordEvent(plan, run, "FAILBACK_ROLLED_BACK", rollbackMessage);
            return true;
        } catch (Exception e) {
            session.setState("ROLLBACK_FAILED");
            session.setRollbackState("FAILED");
            session.setErrorMessage("Failback failed and rollback was incomplete: " + e.getMessage());
            session.markUpdated();
            drFailbackSessionDao.update(session.getId(), session);
            preserveUncertainAuthority(plan);
            return false;
        }
    }

    boolean isCanceledFailbackPendingCompensation(DrRunVO run, DrFailbackSessionVO session) {
        return run != null && session != null
                && StringUtils.equalsIgnoreCase(run.getRunType(), DrConstants.RUN_TYPE_FAILBACK)
                && StringUtils.equalsAnyIgnoreCase(run.getState(),
                        DrConstants.RUN_STATE_CANCEL_REQUESTED, DrConstants.RUN_STATE_CANCELED)
                && !isTerminal(session.getState());
    }

    @Override
    public boolean requiresCancellationCompensation(DrRunVO run) {
        return run != null && isCanceledFailbackPendingCompensation(run,
                drFailbackSessionDao.findActiveByRunId(run.getId()));
    }

    @Override
    public boolean cancelAndRestoreTargetAuthority(DrPlanVO plan, DrRunVO run) {
        if (plan == null || run == null
                || !StringUtils.equalsIgnoreCase(run.getRunType(), DrConstants.RUN_TYPE_FAILBACK)) {
            return true;
        }
        DrFailbackSessionVO session = drFailbackSessionDao.findActiveByRunId(run.getId());
        if (session == null || isTerminal(session.getState())) {
            return true;
        }
        if (StringUtils.equalsIgnoreCase(session.getCommitOutcome(), ACKNOWLEDGED)) {
            return false;
        }
        try {
            session.setRollbackState("FENCING");
            if (session.getRollbackRequestedAt() == null) {
                session.setRollbackRequestedAt(new Date());
            }
            updateSession(session, "ROLLBACK_FENCING", null);
            Answer prepare = sendEngineCommand(plan, run, session,
                    FtctlDrActionCommand.Action.FAILBACK_ABORT, "prepare");
            if (prepare == null || !prepare.getResult()) {
                throw new CloudRuntimeException(prepare != null ? prepare.getDetails()
                        : "Agent returned no canceled Failback fence acknowledgement");
            }
            session.setRollbackState("FENCED");
            updateSession(session, "ROLLBACK_POWER_RESTORING", null);
            session.setSourcePowerState(ensureSourcePowerState(plan, false));
            stopForwardPlanOwnedExportForTargetRecovery(plan, run);
            session.setTargetPowerState(ensureTargetPowerState(plan, true));
            Answer commit = sendEngineCommand(plan, run, session,
                    FtctlDrActionCommand.Action.FAILBACK_ABORT, "commit");
            if (commit == null || !commit.getResult()) {
                throw new CloudRuntimeException(commit != null ? commit.getDetails()
                        : "Agent returned no canceled Failback rollback acknowledgement");
            }
            convergeCanceledFailback(plan, run, session, answerPayload(commit));
            recordEvent(plan, run, "FAILBACK_CANCELED_ROLLED_BACK",
                    "Canceled Failback restored TARGET authority and serving VM power");
            return true;
        } catch (Exception e) {
            session.setState("ROLLBACK_FAILED");
            session.setRollbackState("FAILED");
            session.setErrorCode("DR_FAILBACK_CANCEL_ROLLBACK_PENDING");
            session.setErrorMessage("Canceled Failback compensation is pending: " + e.getMessage());
            session.markUpdated();
            drFailbackSessionDao.update(session.getId(), session);
            plan.setState(DrConstants.PLAN_STATE_COMMIT_VERIFYING);
            plan.setActiveSide(DrConstants.AUTHORITY_SIDE_TARGET);
            plan.setLastErrorCode("DR_FAILBACK_CANCEL_ROLLBACK_PENDING");
            plan.setLastErrorMessage(session.getErrorMessage());
            plan.markUpdated();
            drPlanDao.update(plan.getId(), plan);
            logger.warn("Canceled Failback compensation remains pending for plan {} run {}",
                    plan.getUuid(), run.getUuid(), e);
            return false;
        }
    }

    void stopForwardPlanOwnedExportForTargetRecovery(DrPlanVO plan, DrRunVO run) {
        if (drPlanOwnedTransportService != null) {
            drPlanOwnedTransportService.stopForwardTargetExport(plan, run, null, null);
        }
    }

    private void convergeCanceledFailback(DrPlanVO plan, DrRunVO run,
            DrFailbackSessionVO session, JsonObject abortRuntime) {
        final long planId = plan.getId();
        final long sessionId = session.getId();
        final String runtimeJson = GSON.toJson(abortRuntime != null ? abortRuntime : new JsonObject());
        Transaction.execute(new TransactionCallback<Void>() {
            @Override
            public Void doInTransaction(TransactionStatus status) {
                Date now = new Date();
                DrFailbackSessionVO currentSession = drFailbackSessionDao.findById(sessionId);
                DrPlanVO currentPlan = drPlanDao.findById(planId);
                if (currentSession == null || currentPlan == null) {
                    throw new CloudRuntimeException("Canceled Failback convergence records are incomplete");
                }
                currentSession.setState("ABORTED");
                currentSession.setCommitOutcome(ROLLED_BACK);
                currentSession.setEngineAckState("ABORTED");
                currentSession.setRollbackState("COMPLETED");
                currentSession.setRollbackVerifiedAt(now);
                currentSession.setCompletedAt(now);
                currentSession.setSourcePowerState("POWERED_OFF");
                currentSession.setTargetPowerState("POWERED_ON");
                currentSession.setFailurePhase(null);
                currentSession.setFailedComponent(null);
                currentSession.setErrorCode(null);
                currentSession.setErrorMessage(null);
                currentSession.markUpdated();
                drFailbackSessionDao.update(currentSession.getId(), currentSession);

                currentPlan.setState(DrConstants.PLAN_STATE_FAILED_OVER);
                currentPlan.setActiveSide(DrConstants.AUTHORITY_SIDE_TARGET);
                currentPlan.setLastErrorCode(null);
                currentPlan.setLastErrorMessage(null);
                currentPlan.markUpdated();
                drPlanDao.update(currentPlan.getId(), currentPlan);

                DrReplicaVO replica = firstReplica(planId);
                if (replica != null) {
                    replica.setState(DrConstants.REPLICA_STATE_FAILED_OVER);
                    replica.setPowerState("POWERED_ON");
                    replica.setActiveSide(DrConstants.AUTHORITY_SIDE_TARGET);
                    replica.setRuntimeStateJson(runtimeJson);
                    replica.markUpdated();
                    drReplicaDao.update(replica.getId(), replica);
                }
                DrPlanViewCacheVO cache = drPlanViewCacheDao.findByPlanId(planId);
                if (cache != null) {
                    drPlanViewCacheDao.remove(cache.getId());
                }
                return null;
            }
        });
    }

    private void verifyCommitOutcome(DrPlanVO plan, DrRunVO run, DrFailbackSessionVO session) {
        if (!hasDurableCommitDispatch(session)) {
            recordEvent(plan, run, "FAILBACK_COMMIT_NOT_DISPATCHED",
                    "Failback commit has no durable dispatch envelope; rolling back to target authority");
            rollbackFailback(plan, run, session, true, true);
            return;
        }
        session.setLastProbeAt(new Date());
        session.setCommitProbeCount((session.getCommitProbeCount() == null ? 0 : session.getCommitProbeCount()) + 1);
        Answer answer = sendEngineCommand(plan, run, session, FtctlDrActionCommand.Action.FAILBACK_COMMIT_STATUS);
        JsonObject payload = answerPayload(answer);
        String outcome = upper(stringValue(payload, "failback_commit_outcome"));
        if (StringUtils.equals(outcome, ACKNOWLEDGED)) {
            if (!cloudPowerStatesMatch(plan)) {
                session.setErrorCode("DR_FAILBACK_POWER_STATE_UNVERIFIED");
                session.setErrorMessage("Engine acknowledged failback, but source/target power states are not yet authoritative");
                session.markUpdated();
                drFailbackSessionDao.update(session.getId(), session);
                return;
            }
            acknowledgeCommit(plan, run, session, payload);
            return;
        }
        if (StringUtils.equals(outcome, REJECTED)) {
            rollbackFailback(plan, run, session, true, true);
            return;
        }
        session.setCommitOutcome(UNKNOWN);
        session.setEngineAckState(UNKNOWN);
        session.setState(COMMIT_VERIFYING);
        session.markUpdated();
        drFailbackSessionDao.update(session.getId(), session);
    }

    boolean hasDurableCommitDispatch(DrFailbackSessionVO session) {
        return StringUtils.equals(DrFailbackCommitEnvelope.CONTRACT_VERSION, session.getCommitContractVersion())
                && StringUtils.isNotBlank(session.getCommitAttemptId())
                && StringUtils.isNotBlank(session.getCommitEnvelopeSha256())
                && StringUtils.equalsAnyIgnoreCase(session.getCommitDispatchState(),
                        "DISPATCHING", "DISPATCHED", "OUTCOME_UNKNOWN");
    }

    private void acknowledgeCommit(DrPlanVO plan, DrRunVO run, DrFailbackSessionVO session, JsonObject payload) {
        Date now = new Date();
        initializeResumeCheckpointContract(session);
        session.setCommitOutcome(ACKNOWLEDGED);
        session.setCommitVerifiedAt(now);
        session.setEngineAckState(ACKNOWLEDGED);
        session.setEngineAckAt(now);
        session.setSchedulerGeneration(longValue(payload, "control_generation"));
        session.setSchedulerAckGeneration(longValue(payload, "control_ack_generation"));
        session.setSchedulerState(defaultValue(stringValue(payload, "scheduler_state"), "RUNNING"));
        if (session.getProtectionResumeRequestedAt() == null) {
            session.setProtectionResumeRequestedAt(now);
        }
        updateSession(session, PROTECTION_RESUMING, null);
        projectCommittedAuthority(plan);
        recordEvent(plan, run, "FAILBACK_AUTHORITY_COMMITTED",
                "Source VM is running, target VM is stopped, and FTCTL acknowledged source authority");
    }

    private void markCommitVerifying(DrPlanVO plan, DrFailbackSessionVO session, Answer answer) {
        JsonObject payload = answerPayload(answer);
        session.setState(COMMIT_VERIFYING);
        session.setCommitOutcome(UNKNOWN);
        session.setEngineAckState(UNKNOWN);
        session.setSchedulerGeneration(longValue(payload, "control_generation"));
        session.setSchedulerAckGeneration(longValue(payload, "control_ack_generation"));
        session.setSchedulerState(stringValue(payload, "scheduler_state"));
        session.setLastProbeAt(new Date());
        session.setErrorCode("DR_FAILBACK_COMMIT_OUTCOME_UNKNOWN");
        session.setErrorMessage("Failback commit acknowledgement is being verified");
        session.markUpdated();
        drFailbackSessionDao.update(session.getId(), session);

        plan.setState(DrConstants.PLAN_STATE_COMMIT_VERIFYING);
        plan.setActiveSide("TARGET");
        plan.setLastErrorCode(null);
        plan.setLastErrorMessage(null);
        plan.markUpdated();
        drPlanDao.update(plan.getId(), plan);
    }

    private String commitOutcome(Answer answer) {
        JsonObject payload = answerPayload(answer);
        String outcome = upper(stringValue(payload, "failback_commit_outcome"));
        if (StringUtils.equalsAny(outcome, ACKNOWLEDGED, REJECTED, UNKNOWN, ROLLED_BACK)) {
            return outcome;
        }
        if (answer != null && answer.getResult()) {
            return ACKNOWLEDGED;
        }
        String details = answer != null ? answer.getDetails() : null;
        return isUncertainFailure(details) ? UNKNOWN : REJECTED;
    }

    private JsonObject answerPayload(Answer answer) {
        if (answer instanceof FtctlDrActionAnswer) {
            FtctlDrActionAnswer actionAnswer = (FtctlDrActionAnswer) answer;
            JsonObject payload = parseObject(actionAnswer.getStatusJson());
            if (payload != null) {
                return payload;
            }
            payload = parseObject(actionAnswer.getOutput());
            if (payload != null) {
                return payload;
            }
        }
        JsonObject payload = parseObject(answer != null ? answer.getDetails() : null);
        return payload != null ? payload : new JsonObject();
    }

    private JsonObject parseObject(String json) {
        if (StringUtils.isBlank(json)) {
            return null;
        }
        try {
            JsonElement parsed = JsonParser.parseString(json);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private boolean isUncertainFailure(String message) {
        String normalized = StringUtils.lowerCase(StringUtils.defaultString(message), Locale.ROOT);
        return StringUtils.contains(normalized, "timeout")
                || StringUtils.contains(normalized, "timed out")
                || StringUtils.contains(normalized, "stream closed")
                || StringUtils.contains(normalized, "ack")
                || StringUtils.contains(normalized, "outcome requires status verification");
    }

    private void updateRuntimeEvidence(DrFailbackSessionVO session, JsonObject runtime) {
        Boolean accepted = booleanValue(runtime, "accepted");
        if (accepted != null) {
            session.setAcceptanceState(accepted ? "ACCEPTED" : "REJECTED");
        }
        session.setFailurePhase(defaultValue(stringValue(runtime, "failure_phase"), session.getFailurePhase()));
        session.setFailedComponent(defaultValue(stringValue(runtime, "failed_component"), session.getFailedComponent()));
        Long driverExitCode = longValue(runtime, "driver_exit_code");
        if (driverExitCode != null) {
            session.setDriverExitCode(driverExitCode.intValue());
        }
        session.setBaselineFileState(defaultValue(stringValue(runtime, "baseline_file_state"),
                session.getBaselineFileState()));
        session.setOperationIntent(defaultValue(stringValue(runtime, "operation_intent"), session.getOperationIntent()));
        session.setRequestedMode(defaultValue(stringValue(runtime, "requested_mode"), session.getRequestedMode()));
        session.setEffectiveMode(defaultValue(stringValue(runtime, "effective_mode"), session.getEffectiveMode()));
        session.setModeDecisionCode(defaultValue(stringValue(runtime, "mode_decision_code"), session.getModeDecisionCode()));
        Boolean initialSeedRequired = booleanValue(runtime, "initial_seed_required");
        if (initialSeedRequired != null) {
            session.setInitialSeedRequired(initialSeedRequired);
        }
        session.setSourceDiskProbeState(defaultValue(stringValue(runtime, "source_disk_probe_state"),
                session.getSourceDiskProbeState()));
        Long sourceDiskCount = longValue(runtime, "source_disk_count");
        if (sourceDiskCount != null) {
            session.setSourceDiskCount(sourceDiskCount.intValue());
        }
        session.setTargetWriterProbeState(defaultValue(stringValue(runtime, "target_writer_probe_state"),
                session.getTargetWriterProbeState()));
        session.setEstimatedVirtualBytes(firstNonNull(longValue(runtime, "estimated_virtual_bytes"),
                session.getEstimatedVirtualBytes()));
        Boolean workerPidAlive = booleanValue(runtime, "worker_pid_alive");
        if (workerPidAlive != null) {
            session.setWorkerPidAlive(workerPidAlive);
        }
        String projectedState = projectedEarlyRuntimeState(runtime);
        if (StringUtils.isNotBlank(projectedState)
                && StringUtils.equalsAnyIgnoreCase(session.getState(), "REQUESTED", "DISPATCHED",
                "ENGINE_ACCEPTED", "REVERSE_PREFLIGHT", "REVERSE_SYNCING", DATA_READY)) {
            session.setState(projectedState);
        }
        String outcome = stringValue(runtime, "failback_commit_outcome");
        if (StringUtils.isNotBlank(outcome)) {
            session.setCommitOutcome(upper(outcome));
        }
        session.setSchedulerGeneration(firstNonNull(longValue(runtime, "control_generation"),
                session.getSchedulerGeneration()));
        session.setSchedulerAckGeneration(firstNonNull(longValue(runtime, "control_ack_generation"),
                session.getSchedulerAckGeneration()));
        session.setSchedulerState(defaultValue(stringValue(runtime, "scheduler_state"),
                session.getSchedulerState()));
        session.setRollbackState(defaultValue(stringValue(runtime, "rollback_state"),
                session.getRollbackState()));
        session.setRollbackGeneration(firstNonNull(longValue(runtime, "rollback_generation"),
                session.getRollbackGeneration()));
    }

    private String initialRuntimeState(JsonObject runtime, String runtimeState) {
        if (StringUtils.equals(runtimeState, "FAILBACK_DATA_READY")) {
            return DATA_READY;
        }
        return defaultValue(projectedEarlyRuntimeState(runtime), "REQUESTED");
    }

    private String projectedEarlyRuntimeState(JsonObject runtime) {
        String phase = upper(stringValue(runtime, "failback_phase"));
        String step = upper(stringValue(runtime, "step"));
        if (StringUtils.equals(phase, DATA_READY)) {
            return DATA_READY;
        }
        if (StringUtils.contains(step, "PREFLIGHT")) {
            return "REVERSE_PREFLIGHT";
        }
        if (StringUtils.equalsAny(phase, "REQUESTED", "REVERSE_SYNCING")) {
            return StringUtils.equals(phase, "REQUESTED") ? "ENGINE_ACCEPTED" : phase;
        }
        if (StringUtils.contains(step, "REVERSE") || StringUtils.contains(step, "WORKER")) {
            return "REVERSE_SYNCING";
        }
        return null;
    }

    private boolean isEarlyRuntimeFailure(JsonObject runtime, String runtimeState) {
        if (isTerminalPublicationPending(runtime)) {
            return false;
        }
        String workerState = upper(stringValue(runtime, "worker_state"));
        Boolean workerPidAlive = booleanValue(runtime, "worker_pid_alive");
        return StringUtils.equals(runtimeState, "ERROR")
                || StringUtils.equals(workerState, "FAILED")
                || (StringUtils.equals(workerState, "RUNNING") && Boolean.FALSE.equals(workerPidAlive));
    }

    private boolean isTerminalPublicationPending(JsonObject runtime) {
        return Boolean.TRUE.equals(booleanValue(runtime, "terminal_publication_pending"))
                || StringUtils.equalsIgnoreCase(stringValue(runtime, "worker_state"), "TERMINAL_PENDING");
    }

    private void completeLifecycle(DrPlanVO plan, DrRunVO run, DrFailbackSessionVO session, JsonObject runtime) {
        final long planId = plan.getId();
        final long runId = run.getId();
        final long sessionId = session.getId();
        final Long checkpointSequence = longValue(runtime, "latest_completed_checkpoint_sequence");
        final String runtimeJson = GSON.toJson(terminalRuntimeSnapshot(runtime));
        Transaction.execute(new TransactionCallback<Void>() {
            @Override
            public Void doInTransaction(TransactionStatus status) {
                Date now = new Date();
                DrFailbackSessionVO currentSession = drFailbackSessionDao.findById(sessionId);
                DrRunVO currentRun = drRunDao.findById(runId);
                DrPlanVO currentPlan = drPlanDao.findById(planId);
                if (currentSession == null || currentRun == null || currentPlan == null) {
                    throw new CloudRuntimeException("Failback terminal convergence records are incomplete");
                }
                currentSession.setState(COMPLETED);
                currentSession.setPostFailbackCheckpointSequence(checkpointSequence);
                currentSession.setProtectionResumeVerifiedAt(now);
                currentSession.setCompletedAt(now);
                currentSession.setDetailsJson(runtimeJson);
                currentSession.setFailurePhase(null);
                currentSession.setFailedComponent(null);
                currentSession.setErrorCode(null);
                currentSession.setErrorMessage(null);
                currentSession.markUpdated();
                drFailbackSessionDao.update(currentSession.getId(), currentSession);
                clearAndVerifySuccessfulTerminalFailureMetadata(currentSession);

                currentRun.setState(DrConstants.RUN_STATE_SUCCEEDED);
                currentRun.setCompleted(now);
                currentRun.setCurrentStepName("completed");
                currentRun.setProjectionState("succeeded");
                currentRun.setProjectionChecked(now);
                currentRun.setRetryable(false);
                currentRun.setRetryAfterSeconds(null);
                currentRun.setNextRetryAt(null);
                currentRun.setLastStatusJson(runtimeJson);
                currentRun.setErrorCode(null);
                currentRun.setErrorMessage(null);
                applyCloudLifecycleTerminal(currentRun);
                currentRun.markUpdated();
                drRunDao.update(currentRun.getId(), currentRun);

                currentPlan.setState(DrConstants.PLAN_STATE_READY);
                currentPlan.setActiveSide("SOURCE");
                currentPlan.setLastErrorCode(null);
                currentPlan.setLastErrorMessage(null);
                currentPlan.markUpdated();
                drPlanDao.update(currentPlan.getId(), currentPlan);

                DrReplicaVO replica = firstReplica(planId);
                if (replica != null) {
                    replica.setState(DrConstants.REPLICA_STATE_READY);
                    replica.setPowerState(DrConstants.REPLICA_POWER_STATE_POWERED_OFF);
                    replica.setActiveSide("SOURCE");
                    replica.setRuntimeStateJson(runtimeJson);
                    replica.markUpdated();
                    drReplicaDao.update(replica.getId(), replica);
                }

                DrCutoverSessionVO cutover = drCutoverSessionDao.findCurrentAuthorityByPlanId(planId);
                if (cutover != null) {
                    cutover.setState(DrConstants.CUTOVER_STATE_FAILED_BACK);
                    cutover.setAuthorityEndedAt(now);
                    cutover.setAuthorityEndedByRunId(runId);
                    cutover.setCleanupRequired(false);
                    cutover.setErrorCode(null);
                    cutover.setErrorMessage(null);
                    cutover.markUpdated();
                    drCutoverSessionDao.update(cutover.getId(), cutover);
                }

                completeFailbackStep(runId, "runtime-projection", 30, now, runtimeJson);
                completeFailbackStep(runId, "data-ready", 100,
                        firstNonNull(currentSession.getDataReadyAt(), currentSession.getCreated()), runtimeJson);
                completeFailbackStep(runId, "target-stop", 110,
                        firstNonNull(currentSession.getTargetStoppedAt(), now), runtimeJson);
                completeFailbackStep(runId, "source-start", 120,
                        firstNonNull(currentSession.getSourcePoweredOnAt(), now), runtimeJson);
                completeFailbackStep(runId, "source-boot-validation", 130,
                        firstNonNull(currentSession.getBootValidatedAt(), now), runtimeJson);
                completeFailbackStep(runId, "authority-commit", 140,
                        firstNonNull(currentSession.getCommitVerifiedAt(), currentSession.getEngineAckAt()), runtimeJson);
                completeFailbackStep(runId, "scheduler-resume", 150, now, runtimeJson);
                completeFailbackStep(runId, "post-checkpoint", 160, now, runtimeJson);
                completeFailbackStep(runId, "completed", 170, now, runtimeJson);

                DrPlanViewCacheVO cache = drPlanViewCacheDao.findByPlanId(planId);
                if (cache != null) {
                    drPlanViewCacheDao.remove(cache.getId());
                }
                return null;
            }
        });
        recordEvent(plan, run, "FAILBACK_COMPLETED",
                "Original-direction protection resumed and produced a durable checkpoint");
    }

    void applyCloudLifecycleTerminal(DrRunVO run) {
        if (!StringUtils.equals(run.getTerminalSource(), "CLOUD_LIFECYCLE")) {
            run.setTerminalVersion(run.getTerminalVersion() == null ? 1 : run.getTerminalVersion() + 1);
        } else if (run.getTerminalVersion() == null) {
            run.setTerminalVersion(1);
        }
        run.setTerminalSource("CLOUD_LIFECYCLE");
        run.setTerminalAuthoritative(true);
    }

    private void failBeforeAuthorityTransition(DrPlanVO plan, DrRunVO run, DrFailbackSessionVO session,
            String failurePhase, String failedComponent, String errorCode, String message) {
        session.setState("ABORTING");
        session.setFailurePhase(failurePhase);
        session.setFailedComponent(failedComponent);
        session.setErrorCode(errorCode);
        session.setErrorMessage(message);
        session.setRollbackState("FENCING");
        if (session.getRollbackRequestedAt() == null) {
            session.setRollbackRequestedAt(new Date());
        }
        session.markUpdated();
        drFailbackSessionDao.update(session.getId(), session);

        try {
            Answer prepare = sendEngineCommand(plan, run, session,
                    FtctlDrActionCommand.Action.FAILBACK_ABORT, "prepare");
            if (prepare == null || !prepare.getResult()) {
                throw new CloudRuntimeException(prepare != null ? prepare.getDetails()
                        : "Agent returned no failback abort prepare acknowledgement");
            }
            Answer commit = sendEngineCommand(plan, run, session,
                    FtctlDrActionCommand.Action.FAILBACK_ABORT, "commit");
            if (commit == null || !commit.getResult()) {
                throw new CloudRuntimeException(commit != null ? commit.getDetails()
                        : "Agent returned no failback abort commit acknowledgement");
            }
            convergePreAuthorityFailure(plan, run, session, failurePhase, failedComponent, errorCode, message,
                    answerPayload(commit));
        } catch (Exception e) {
            session.setState("ROLLBACK_FAILED");
            session.setRollbackState("FAILED");
            session.setErrorCode(errorCode);
            session.setErrorMessage(message + "; engine cleanup is pending: " + e.getMessage());
            session.markUpdated();
            drFailbackSessionDao.update(session.getId(), session);
            plan.setState(DrConstants.PLAN_STATE_FAILED_OVER);
            plan.setActiveSide(DrConstants.AUTHORITY_SIDE_TARGET);
            plan.setLastErrorCode(errorCode);
            plan.setLastErrorMessage(session.getErrorMessage());
            plan.markUpdated();
            drPlanDao.update(plan.getId(), plan);
            logger.warn("Failback pre-authority failure cleanup remains pending for plan {} run {}: {}",
                    plan.getUuid(), run.getUuid(), e.getMessage());
        }
    }

    private void convergePreAuthorityFailure(DrPlanVO plan, DrRunVO run, DrFailbackSessionVO session,
            String failurePhase, String failedComponent, String errorCode, String message, JsonObject abortRuntime) {
        final long planId = plan.getId();
        final long runId = run.getId();
        final long sessionId = session.getId();
        final String runtimeJson = GSON.toJson(abortRuntime != null ? abortRuntime : new JsonObject());
        Transaction.execute(new TransactionCallback<Void>() {
            @Override
            public Void doInTransaction(TransactionStatus status) {
                Date now = new Date();
                DrFailbackSessionVO currentSession = drFailbackSessionDao.findById(sessionId);
                DrRunVO currentRun = drRunDao.findById(runId);
                DrPlanVO currentPlan = drPlanDao.findById(planId);
                if (currentSession == null || currentRun == null || currentPlan == null) {
                    throw new CloudRuntimeException("Failback failure convergence records are incomplete");
                }
                currentSession.setState("FAILED");
                currentSession.setFailurePhase(failurePhase);
                currentSession.setFailedComponent(failedComponent);
                currentSession.setRollbackState("COMPLETED");
                currentSession.setRollbackVerifiedAt(now);
                currentSession.setCompletedAt(now);
                currentSession.setEngineAckState("ABORTED");
                currentSession.setErrorCode(errorCode);
                currentSession.setErrorMessage(message);
                currentSession.markUpdated();
                drFailbackSessionDao.update(currentSession.getId(), currentSession);

                currentRun.setState(DrConstants.RUN_STATE_FAILED);
                currentRun.setCompleted(now);
                currentRun.setCurrentStepName("cloud-lifecycle-gate");
                currentRun.setProjectionState("failed");
                currentRun.setProjectionChecked(now);
                currentRun.setRetryable(false);
                currentRun.setRetryAfterSeconds(null);
                currentRun.setNextRetryAt(null);
                currentRun.setLastStatusJson(runtimeJson);
                currentRun.setErrorCode(errorCode);
                currentRun.setErrorMessage(message);
                currentRun.setTerminalSource("CLOUD_LIFECYCLE");
                currentRun.setTerminalVersion(currentRun.getTerminalVersion() == null
                        ? 1 : currentRun.getTerminalVersion() + 1);
                currentRun.setTerminalAuthoritative(true);
                currentRun.markUpdated();
                drRunDao.update(currentRun.getId(), currentRun);

                currentPlan.setState(DrConstants.PLAN_STATE_FAILED_OVER);
                currentPlan.setActiveSide(DrConstants.AUTHORITY_SIDE_TARGET);
                currentPlan.setLastErrorCode(errorCode);
                currentPlan.setLastErrorMessage(message);
                currentPlan.markUpdated();
                drPlanDao.update(currentPlan.getId(), currentPlan);

                DrReplicaVO replica = firstReplica(planId);
                if (replica != null) {
                    replica.setState(DrConstants.REPLICA_STATE_FAILED_OVER);
                    replica.setPowerState("POWERED_ON");
                    replica.setActiveSide(DrConstants.AUTHORITY_SIDE_TARGET);
                    replica.setRuntimeStateJson(runtimeJson);
                    replica.markUpdated();
                    drReplicaDao.update(replica.getId(), replica);
                }
                failFailbackStep(runId, "cloud-lifecycle-gate", 105, now, runtimeJson,
                        errorCode, message);
                DrPlanViewCacheVO cache = drPlanViewCacheDao.findByPlanId(planId);
                if (cache != null) {
                    drPlanViewCacheDao.remove(cache.getId());
                }
                return null;
            }
        });
    }

    private void terminalizeArtifactFreeFailure(DrFailbackSessionVO session, DrRunVO run) {
        session.setState(StringUtils.equals(run.getState(), DrConstants.RUN_STATE_CANCELED)
                ? "ABORTED" : "FAILED");
        session.setAcceptanceState("REJECTED");
        session.setFailurePhase("PRE_DISPATCH");
        session.setFailedComponent("cloud-dr-run-executor");
        session.setErrorCode(run.getErrorCode());
        session.setErrorMessage(run.getErrorMessage());
        session.setCompletedAt(run.getCompleted());
        session.setRemoved(new Date());
        session.markUpdated();
        drFailbackSessionDao.update(session.getId(), session);
    }

    private DrFailbackSessionVO convergeSuccessfulTerminalFailureMetadata(DrFailbackSessionVO session) {
        if (session == null || !StringUtils.equals(session.getState(), COMPLETED)
                || !hasFailureMetadata(session)) {
            return session;
        }
        clearAndVerifySuccessfulTerminalFailureMetadata(session);
        return session;
    }

    private void clearAndVerifySuccessfulTerminalFailureMetadata(DrFailbackSessionVO session) {
        drFailbackSessionDao.clearFailureMetadata(session.getId());
        DrFailbackSessionVO verified = drFailbackSessionDao.findById(session.getId());
        if (verified == null || hasFailureMetadata(verified)) {
            throw new CloudRuntimeException("Successful Failback failure metadata did not converge");
        }
        session.setFailurePhase(null);
        session.setFailedComponent(null);
        session.setErrorCode(null);
        session.setErrorMessage(null);
    }

    private boolean hasFailureMetadata(DrFailbackSessionVO session) {
        return StringUtils.isNotBlank(session.getFailurePhase())
                || StringUtils.isNotBlank(session.getFailedComponent())
                || StringUtils.isNotBlank(session.getErrorCode())
                || StringUtils.isNotBlank(session.getErrorMessage());
    }

    JsonObject terminalRuntimeSnapshot(JsonObject runtime) {
        JsonObject terminal = runtime == null ? new JsonObject() : runtime.deepCopy();
        terminal.addProperty("state", "READY");
        terminal.addProperty("step", "target-checkpoint-ready");
        terminal.addProperty("progress", 100);
        terminal.addProperty("failback_phase", COMPLETED);
        terminal.addProperty("cloud_lifecycle_state", COMPLETED);
        terminal.addProperty("active_side", "SOURCE");
        terminal.addProperty("scheduler_state", "RUNNING");
        terminal.addProperty("scheduler_health", "HEALTHY");
        terminal.addProperty("immediate_cycle_pending", false);
        terminal.addProperty("transfer_activity_state", "IDLE");
        terminal.addProperty("worker_state", "TERMINAL_PUBLISHED");
        terminal.addProperty("worker_pid_alive", false);
        terminal.addProperty("terminal_publication_pending", false);
        terminal.addProperty("terminal_source", "CLOUD_LIFECYCLE");
        terminal.addProperty("terminal_version", 1);
        terminal.addProperty("terminal_authoritative", true);
        terminal.addProperty("retryable", false);
        terminal.addProperty("error_code", "");
        terminal.addProperty("error_message", "");
        terminal.remove("failure_phase");
        terminal.remove("failed_component");
        return terminal;
    }

    private void failFailbackStep(long runId, String stepName, int stepOrder, Date completedAt,
            String detailsJson, String errorCode, String message) {
        DrRunStepVO step = drRunStepDao.findActiveByRunIdAndStepName(runId, stepName);
        if (step == null) {
            step = new DrRunStepVO(runId, stepName, stepOrder);
            step.setStarted(completedAt);
            step.setCompleted(completedAt);
            step.setState(DrConstants.STEP_STATE_FAILED);
            step.setProgress(100);
            step.setDetailsJson(detailsJson);
            step.setErrorCode(errorCode);
            step.setErrorMessage(message);
            drRunStepDao.persist(step);
            return;
        }
        step.setCompleted(completedAt);
        step.setState(DrConstants.STEP_STATE_FAILED);
        step.setProgress(100);
        step.setDetailsJson(detailsJson);
        step.setErrorCode(errorCode);
        step.setErrorMessage(message);
        step.markUpdated();
        drRunStepDao.update(step.getId(), step);
    }

    private void completeFailbackStep(long runId, String stepName, int stepOrder, Date completedAt,
            String detailsJson) {
        Date completed = completedAt != null ? completedAt : new Date();
        DrRunStepVO step = drRunStepDao.findActiveByRunIdAndStepName(runId, stepName);
        if (step == null) {
            step = new DrRunStepVO(runId, stepName, stepOrder);
            step.setStarted(completed);
            step.setCompleted(completed);
            step.setState(DrConstants.STEP_STATE_SUCCEEDED);
            step.setProgress(100);
            step.setDetailsJson(detailsJson);
            drRunStepDao.persist(step);
            return;
        }
        step.setState(DrConstants.STEP_STATE_SUCCEEDED);
        step.setProgress(100);
        if (step.getStarted() == null) {
            step.setStarted(completed);
        }
        step.setCompleted(completed);
        step.setDetailsJson(detailsJson);
        step.setErrorCode(null);
        step.setErrorMessage(null);
        step.markUpdated();
        drRunStepDao.update(step.getId(), step);
    }

    // #976: observing an in-flight resume must not revoke its export every projection tick.
    boolean ownedResumeWorkerActive(DrPlanVO plan, DrRunVO run, JsonObject runtime) {
        if (plan == null || run == null || runtime == null
                || !StringUtils.equalsIgnoreCase(plan.getActiveSide(), "SOURCE")) { return false; }
        String expected = UUID.nameUUIDFromBytes((plan.getUuid() + ":" + run.getUuid() + ":RESUME_SYNC")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        String side = stringValue(runtime, "active_side");
        return (StringUtils.isBlank(side) || "SOURCE".equalsIgnoreCase(side))
                && expected.equals(stringValue(runtime, "active_worker_run_uuid"))
                && Boolean.TRUE.equals(booleanValue(runtime, "scheduler_pid_alive"))
                && "RUNNING".equalsIgnoreCase(stringValue(runtime, "control_state"))
                && StringUtils.equalsAnyIgnoreCase(stringValue(runtime, "scheduler_state"), "RUNNING", "ACTIVE")
                && "HEALTHY".equalsIgnoreCase(stringValue(runtime, "scheduler_health"));
    }

    boolean protectionResumed(DrPlanVO plan, DrFailbackSessionVO session, JsonObject runtime) {
        Long latest = longValue(runtime, "latest_completed_checkpoint_sequence");
        Long required = session.getRequiredPostFailbackCheckpointSequence();
        if (required == null && session.getCheckpointSequence() != null) {
            required = session.getCheckpointSequence() + 1L;
        }
        String runtimeActiveSide = stringValue(runtime, "active_side");
        boolean remoteSourceAuthority = StringUtils.isBlank(runtimeActiveSide)
                && drRemoteAgentClient != null && drRemoteAgentClient.isRemoteKvmSource(plan);
        return StringUtils.equalsIgnoreCase(plan.getActiveSide(), "SOURCE")
                && (StringUtils.equalsIgnoreCase(runtimeActiveSide, "SOURCE") || remoteSourceAuthority)
                && StringUtils.equalsAnyIgnoreCase(stringValue(runtime, "scheduler_state"), "RUNNING", "ACTIVE")
                && StringUtils.equalsAnyIgnoreCase(
                        defaultValue(stringValue(runtime, "scheduler_health"), "HEALTHY"),
                        "HEALTHY", "RUNNING")
                && StringUtils.equalsIgnoreCase(session.getEngineAckState(), ACKNOWLEDGED)
                && StringUtils.equalsIgnoreCase(session.getCommitOutcome(), ACKNOWLEDGED)
                && StringUtils.equalsIgnoreCase(session.getTargetPowerState(), "POWERED_OFF")
                && StringUtils.equalsIgnoreCase(session.getSourcePowerState(), "POWERED_ON")
                && latest != null && required != null && latest >= required;
    }

    private void initializeResumeCheckpointContract(DrFailbackSessionVO session) {
        Long checkpoint = session.getCheckpointSequence();
        if (checkpoint == null) {
            return;
        }
        if (session.getResumeBaselineCheckpointSequence() == null) {
            session.setResumeBaselineCheckpointSequence(checkpoint);
        }
        if (session.getRequiredPostFailbackCheckpointSequence() == null) {
            session.setRequiredPostFailbackCheckpointSequence(checkpoint + 1L);
        }
    }

    private void projectCommittedAuthority(DrPlanVO plan) {
        plan.setState(DrConstants.PLAN_STATE_SYNCING);
        plan.setActiveSide("SOURCE");
        plan.setLastErrorCode(null);
        plan.setLastErrorMessage(null);
        plan.markUpdated();
        drPlanDao.update(plan.getId(), plan);
        DrReplicaVO replica = firstReplica(plan.getId());
        if (replica != null) {
            replica.setState(DrConstants.REPLICA_STATE_READY);
            replica.setPowerState(DrConstants.REPLICA_POWER_STATE_POWERED_OFF);
            replica.setActiveSide("SOURCE");
            replica.markUpdated();
            drReplicaDao.update(replica.getId(), replica);
        }
    }

    private void preserveTargetAuthority(DrPlanVO plan) {
        plan.setState(DrConstants.PLAN_STATE_FAILED_OVER);
        plan.setActiveSide("TARGET");
        plan.markUpdated();
        drPlanDao.update(plan.getId(), plan);
        DrReplicaVO replica = firstReplica(plan.getId());
        if (replica != null) {
            replica.setState(DrConstants.REPLICA_STATE_FAILED_OVER);
            replica.setPowerState("POWERED_ON");
            replica.setActiveSide("TARGET");
            replica.markUpdated();
            drReplicaDao.update(replica.getId(), replica);
        }
    }

    private void preserveUncertainAuthority(DrPlanVO plan) {
        plan.setState(DrConstants.PLAN_STATE_COMMIT_VERIFYING);
        plan.setActiveSide("SOURCE");
        plan.setLastErrorCode("DR_FAILBACK_COMMIT_UNCERTAIN");
        plan.setLastErrorMessage("Failback authority could not be rolled back safely");
        plan.markUpdated();
        drPlanDao.update(plan.getId(), plan);
    }

    private DrReplicaVO firstReplica(long planId) {
        List<DrReplicaVO> replicas = drReplicaDao.listActiveByPlanId(planId);
        return replicas == null || replicas.isEmpty() ? null : replicas.get(0);
    }

    private void updateSession(DrFailbackSessionVO session, String state, String errorCode) {
        session.setState(state);
        session.setErrorCode(errorCode);
        if (errorCode == null) {
            session.setErrorMessage(null);
        }
        session.markUpdated();
        drFailbackSessionDao.update(session.getId(), session);
    }

    private void failSession(DrFailbackSessionVO session, String errorCode, String message) {
        session.setState("FAILED");
        session.setErrorCode(errorCode);
        session.setErrorMessage(message);
        session.markUpdated();
        drFailbackSessionDao.update(session.getId(), session);
    }

    private void recordEvent(DrPlanVO plan, DrRunVO run, String eventType, String message) {
        DrEventVO event = new DrEventVO(eventType,
                StringUtils.endsWith(eventType, "FAILED") ? DrConstants.EVENT_SEVERITY_ERROR : DrConstants.EVENT_SEVERITY_INFO,
                DrConstants.EVENT_SOURCE_CLOUD);
        event.setPlanId(plan.getId());
        event.setRunId(run.getId());
        event.setMessage(message);
        drEventDao.persist(event);
    }

    boolean isTerminal(String state) {
        return StringUtils.equalsAnyIgnoreCase(state, COMPLETED, "FAILED", "ABORTED");
    }

    boolean requiresRolledBackFailureConvergence(DrFailbackSessionVO session, DrRunVO run) {
        if (session == null || run == null) {
            return false;
        }
        boolean runTerminal = StringUtils.equalsAnyIgnoreCase(run.getState(),
                DrConstants.RUN_STATE_SUCCEEDED, DrConstants.RUN_STATE_FAILED, DrConstants.RUN_STATE_CANCELED);
        boolean sessionTerminal = StringUtils.equalsAnyIgnoreCase(session.getState(), "COMPLETED", "FAILED", "ABORTED");
        if (sessionTerminal && !runTerminal) {
            return !StringUtils.equalsIgnoreCase(session.getState(), "COMPLETED");
        }
        return runTerminal && run.getCompleted() != null && run.isTerminalAuthoritative()
                && StringUtils.equalsAnyIgnoreCase(session.getState(), "ABORTING", "ROLLBACK_FAILED", "ABORTED");
    }

    boolean runtimeBelongsToRun(JsonObject runtime, DrRunVO run) {
        if (runtime == null || run == null || StringUtils.isBlank(run.getUuid())) {
            return true;
        }
        String runUuid = run.getUuid();
        String runtimeRun = stringValue(runtime, "run_uuid");
        String controlRun = stringValue(runtime, "control_request_run_uuid");
        String sessionId = stringValue(runtime, "failback_session_id");
        boolean hasTypedSessionIdentity = StringUtils.contains(sessionId, ":");
        boolean hasIdentity = StringUtils.isNotBlank(runtimeRun) || StringUtils.isNotBlank(controlRun)
                || hasTypedSessionIdentity;
        if (!hasIdentity) {
            return true;
        }
        if (StringUtils.isNotBlank(runtimeRun) && !StringUtils.equals(runtimeRun, runUuid)) {
            return false;
        }
        if (StringUtils.isNotBlank(controlRun) && !StringUtils.equals(controlRun, runUuid)) {
            return false;
        }
        return !hasTypedSessionIdentity || StringUtils.endsWith(sessionId, ":" + runUuid);
    }

    private void refreshCommitPrerequisites(DrPlanVO plan, DrRunVO run, DrFailbackSessionVO session,
            JsonObject runtime) {
        Long checkpoint = firstNonNull(longValue(runtime, "failback_restore_point_sequence"),
                longValue(runtime, "reverse_evidence_checkpoint_sequence"),
                longValue(runtime, "baseline_generation"), session.getCheckpointSequence(),
                session.getBaselineGeneration());
        if (checkpoint != null) {
            if (session.getCheckpointSequence() != null && !session.getCheckpointSequence().equals(checkpoint)) {
                throw new CloudRuntimeException("Durable reverse checkpoint changed during failback");
            }
            session.setCheckpointSequence(checkpoint);
        }
        DrCutoverSessionVO authority = drCutoverSessionDao.findCommittedTargetAuthorityByPlanId(plan.getId());
        Long generation = authority != null ? authority.getCloudAuthorityGeneration() : null;
        if (generation != null) {
            if (session.getAuthorityGeneration() != null && !session.getAuthorityGeneration().equals(generation)) {
                throw new CloudRuntimeException("Committed target authority generation changed during failback");
            }
            session.setAuthorityGeneration(generation);
        }
        initializeResumeCheckpointContract(session);
    }

    private void prepareCommitDispatch(DrPlanVO plan, DrRunVO run, DrFailbackSessionVO session) {
        if (!StringUtils.equalsIgnoreCase(plan.getActiveSide(), DrConstants.AUTHORITY_SIDE_TARGET)) {
            throw new CloudRuntimeException("Failback commit requires committed TARGET authority");
        }
        if (StringUtils.isBlank(session.getEngineSessionId()) || session.getCheckpointSequence() == null
                || session.getAuthorityGeneration() == null || session.getBaselineGeneration() == null) {
            throw new CloudRuntimeException("Failback session, durable checkpoint, baseline, and authority generation are required");
        }
        if (StringUtils.isBlank(session.getCommitAttemptId())) {
            session.setCommitAttemptId(UUID.randomUUID().toString());
        }
        session.setCommitContractVersion(DrFailbackCommitEnvelope.CONTRACT_VERSION);
        session.setCommitDispatchState("PREPARED");
        session.setCommitProbeCount(0);
        session.setCommitProbeDeadlineAt(new Date(System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(5)));
        session.markUpdated();
        drFailbackSessionDao.update(session.getId(), session);
    }

    private Long longValue(JsonObject object, String key) {
        JsonElement value = object != null ? object.get(key) : null;
        if (value == null || value.isJsonNull()) {
            return null;
        }
        try {
            return value.getAsLong();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String stringValue(JsonObject object, String key) {
        JsonElement value = object != null ? object.get(key) : null;
        return value != null && !value.isJsonNull() ? StringUtils.trimToNull(value.getAsString()) : null;
    }

    private JsonObject objectValue(JsonObject object, String key) {
        JsonElement value = object != null ? object.get(key) : null;
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private Boolean booleanValue(JsonObject object, String key) {
        JsonElement value = object != null ? object.get(key) : null;
        if (value == null || value.isJsonNull()) {
            return null;
        }
        try {
            return value.getAsBoolean();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String upper(String value) {
        return StringUtils.upperCase(StringUtils.defaultString(value), Locale.ROOT);
    }

    private String defaultValue(String value, String defaultValue) {
        return StringUtils.defaultIfBlank(value, defaultValue);
    }

    @SafeVarargs
    private final <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
