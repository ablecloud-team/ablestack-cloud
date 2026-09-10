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
package com.cloud.dr.adapter.ftctl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TimeZone;
import java.util.UUID;

import javax.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.FtctlDrActionAnswer;
import com.cloud.agent.api.FtctlDrActionCommand;
import com.cloud.agent.api.FtctlDrCycleSnapshot;
import com.cloud.agent.api.FtctlDrStatusAnswer;
import com.cloud.agent.api.FtctlDrStatusCommand;
import com.cloud.dr.DrConstants;
import com.cloud.dr.DrCutoverCommitEnvelope;
import com.cloud.dr.DrCutoverDiskVO;
import com.cloud.dr.DrCutoverSessionVO;
import com.cloud.dr.DrFailbackLifecycleService;
import com.cloud.dr.DrFailbackSessionVO;
import com.cloud.dr.DrEventVO;
import com.cloud.dr.DrPlanVO;
import com.cloud.dr.DrPlanOwnedTransportService;
import com.cloud.dr.DrPlanRuntimeVO;
import com.cloud.dr.DrReplicaDiskVO;
import com.cloud.dr.DrReplicaVO;
import com.cloud.dr.DrResolvedSiteCredential;
import com.cloud.dr.DrRestorePointVO;
import com.cloud.dr.DrRunStepVO;
import com.cloud.dr.DrRunVO;
import com.cloud.dr.DrSyncCycleVO;
import com.cloud.dr.DrSyncWorkflowProgress;
import com.cloud.dr.DrTestSessionVO;
import com.cloud.dr.DrTargetMaterializationService;
import com.cloud.dr.DrTargetResourceOwnershipService;
import com.cloud.dr.DrTargetPowerOnResult;
import com.cloud.dr.DrTestSessionState;
import com.cloud.dr.DrSiteCredentialService;
import com.cloud.dr.DrSiteVO;
import com.cloud.dr.DrWorkerPlacementService;
import com.cloud.dr.DrWorkerRole;
import com.cloud.dr.adapter.DrAdapterResult;
import com.cloud.dr.adapter.DrProjectionAdapter;
import com.cloud.dr.dao.DrEventDao;
import com.cloud.dr.dao.DrCutoverDiskDao;
import com.cloud.dr.dao.DrCutoverSessionDao;
import com.cloud.dr.dao.DrFailbackSessionDao;
import com.cloud.dr.dao.DrPlanDao;
import com.cloud.dr.dao.DrPlanRuntimeDao;
import com.cloud.dr.dao.DrReplicaDiskDao;
import com.cloud.dr.dao.DrReplicaDao;
import com.cloud.dr.dao.DrRestorePointDao;
import com.cloud.dr.dao.DrRunDao;
import com.cloud.dr.dao.DrRunStepDao;
import com.cloud.dr.dao.DrSiteDao;
import com.cloud.dr.dao.DrSyncCycleDao;
import com.cloud.dr.dao.DrTestSessionDao;
import com.cloud.dr.inventory.DrSourceVmHardware;
import com.cloud.dr.inventory.DrVmwareInventoryClient;
import com.cloud.utils.DateUtil;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.UserVmDao;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class FtctlDrRuntimeProjectionAdapter extends ManagerBase implements DrProjectionAdapter {
    @Inject private com.cloud.dr.DrTestCleanupRecoveryStore testCleanupRecovery;

    private static final Logger LOGGER = LogManager.getLogger(FtctlDrRuntimeProjectionAdapter.class);
    private static final int CYCLE_EVIDENCE_MAX_RETRIES = 3;
    private static final Gson GSON = new Gson();
    private static final int RUNTIME_CREATION_GRACE_SECONDS = 120;
    private static final int WORKER_START_GRACE_SECONDS = 60;
    private static final int STEP_ORDER_RUNTIME_PROJECTION = 30;
    private static final int STEP_ORDER_SOURCE_ISOLATION = 35;
    private static final int STEP_ORDER_TARGET_POWER_ON = 40;
    private static final int STEP_ORDER_BOOT_VALIDATION = 50;
    private static final int STEP_ORDER_CLOUD_PROMOTION = 60;
    private static final int STEP_ORDER_ENGINE_ACK = 70;
    private static final int STEP_ORDER_FINAL = 90;
    private static final int STATUS_REFRESH_WAIT_SECONDS = 5;
    private static final int DEFAULT_CHECKPOINT_RETENTION = 24;
    private static final int SCHEDULER_HEARTBEAT_STALE_SECONDS = 90;
    private static final int SUPERSEDED_CYCLE_RECONCILE_LIMIT = 100;

    @Inject
    private AgentManager agentManager;
    @Inject
    private DrRemoteAgentClient drRemoteAgentClient;
    @Inject
    private DrWorkerPlacementService drWorkerPlacementService;
    @Inject
    private DrPlanOwnedTransportService drPlanOwnedTransportService;
    @Inject
    private DrPlanDao drPlanDao;
    @Inject
    private DrEventDao drEventDao;
    @Inject
    private DrRestorePointDao drRestorePointDao;
    @Inject
    private DrReplicaDao drReplicaDao;
    @Inject
    private DrReplicaDiskDao drReplicaDiskDao;
    @Inject
    private DrRunDao drRunDao;
    @Inject
    private DrRunStepDao drRunStepDao;
    @Inject
    private DrTargetMaterializationService drTargetMaterializationService;
    @Inject
    private DrTargetResourceOwnershipService drTargetResourceOwnershipService;
    @Inject
    private DrSiteDao drSiteDao;
    @Inject
    private DrSiteCredentialService drSiteCredentialService;
    @Inject
    private DrVmwareInventoryClient drVmwareInventoryClient;
    @Inject
    private DrCutoverSessionDao drCutoverSessionDao;
    @Inject
    private DrFailbackSessionDao drFailbackSessionDao;
    @Inject
    private DrFailbackLifecycleService drFailbackLifecycleService;
    @Inject
    private DrCutoverDiskDao drCutoverDiskDao;
    @Inject
    private DrPlanRuntimeDao drPlanRuntimeDao;
    @Inject
    private DrSyncCycleDao drSyncCycleDao;
    @Inject
    private DrTestSessionDao drTestSessionDao;
    @Inject
    private UserVmDao userVmDao;
    @Inject
    private VolumeDao volumeDao;

    @Override
    public String getEngineType() {
        return DrConstants.ENGINE_TYPE_FTCTL_DR;
    }

    @Override
    public String getEngineBindingType() {
        return DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR;
    }

    @Override
    public DrAdapterResult projectTerminalActionResult(DrPlanVO plan, DrRunVO run, String detailsJson) {
        if (run == null || !StringUtils.equalsIgnoreCase(run.getRunType(), DrConstants.RUN_TYPE_RELEASE)) {
            return DrAdapterResult.success("FTCTL_DR terminal action does not require release convergence", detailsJson);
        }
        JsonObject details = parseObject(detailsJson);
        JsonObject agentAnswer = objectValue(details, "agentAnswer");
        JsonObject runtime = objectValue(agentAnswer, "status").deepCopy();
        copyTerminalProperty(agentAnswer, runtime, "state", "state");
        copyTerminalProperty(agentAnswer, runtime, "step", "step");
        copyTerminalProperty(agentAnswer, runtime, "planUuid", "plan_uuid");
        copyTerminalProperty(agentAnswer, runtime, "runUuid", "run_uuid");
        if (!isReleasedRuntime(null, runtime)) {
            return DrAdapterResult.failure(DrConstants.ERROR_PROJECTION_UNAVAILABLE,
                    "FTCTL_DR release answer does not contain RELEASED / release-completed terminal evidence",
                    detailsJson);
        }
        if (!runtime.has("active_side")) {
            runtime.addProperty("active_side", plan.getActiveSide());
        }
        if (!runtime.has("protection_state")) {
            runtime.addProperty("protection_state", "UNPROTECTED");
        }
        if (!runtime.has("scheduler_state")) {
            runtime.addProperty("scheduler_state", "STOPPED");
        }
        cleanupReleasedProjection(plan, null, runtime);
        return DrAdapterResult.success("FTCTL_DR release terminal projection committed", GSON.toJson(runtime));
    }

    @Override
    public DrAdapterResult refreshPlanProjection(DrPlanVO plan) {
        DrRunVO projectionRun = resolveRefreshProjectionRun(plan);
        if (isInitialRuntimePending(plan, projectionRun)) {
            JsonObject details = buildDetails(plan, null, null);
            details.addProperty("runtimeState", "INITIAL_SYNC_PENDING");
            details.addProperty("projectionSkipped", true);
            return DrAdapterResult.success("FTCTL_DR runtime is pending the initial synchronization",
                    GSON.toJson(details));
        }
        Long hostId = resolveCoordinatorHostId(plan);
        if (hostId == null) {
            String message = "FTCTL_DR projection requires a coordinator, source, or target worker host";
            return DrAdapterResult.failure(DrConstants.ERROR_TARGET_MAPPING_INVALID, message, GSON.toJson(buildDetails(plan, null, null)));
        }

        FtctlDrStatusCommand authorityCommand = new FtctlDrStatusCommand(plan.getUuid(), null,
                FtctlDrStatusCommand.StatusScope.PLAN_AUTHORITY);
        authorityCommand.setWait(STATUS_REFRESH_WAIT_SECONDS);
        Answer answer;
        try {
            answer = sendStatusCommand(plan, projectionRun, authorityCommand, hostId);
        } catch (RuntimeException e) {
            return DrAdapterResult.retryable(DrConstants.ERROR_ENGINE_UNAVAILABLE,
                    "Remote FTCTL_DR authority status is temporarily unavailable: " + e.getMessage(),
                    GSON.toJson(buildDetails(plan, hostId, null)), STATUS_REFRESH_WAIT_SECONDS);
        }
        if (!(answer instanceof FtctlDrStatusAnswer)) {
            String message = answer != null ? answer.getDetails() : "Agent returned no FTCTL_DR status answer";
            return DrAdapterResult.failure(DrConstants.ERROR_ENGINE_UNAVAILABLE, message, GSON.toJson(buildDetails(plan, hostId, null)));
        }

        FtctlDrStatusAnswer authorityStatus = (FtctlDrStatusAnswer) answer;
        JsonObject authorityDetails = buildDetails(plan, hostId, authorityStatus);
        JsonObject authorityRuntime = parseObject(authorityStatus.getStatusJson());
        if (!isCorrelatedRuntime(plan, null, authorityStatus)) {
            authorityDetails.addProperty("projectionIgnored", true);
            authorityDetails.addProperty("reason", "STALE_PLAN_AUTHORITY_IGNORED");
            persistRunProjectionEvent(plan, projectionRun, DrConstants.EVENT_PROJECTION_REFRESH,
                    DrConstants.EVENT_SEVERITY_WARN,
                    "Ignored FTCTL_DR authority status for a different plan", GSON.toJson(authorityDetails));
            return DrAdapterResult.success("Ignored stale FTCTL_DR authority status", GSON.toJson(authorityDetails));
        }
        DrCutoverSessionVO reconciledTargetAuthority = reconcileAcknowledgedTargetAuthority(
                plan, authorityStatus, authorityRuntime);
        if (projectionRun == null && isRemoteKvmToKvmPlan(plan)
                && (reconciledTargetAuthority != null || findCommittedTargetAuthority(plan) != null)
                && !isTargetProtectedRuntime(authorityStatus, authorityRuntime)) {
            preserveFailedOverTargetAuthority(plan);
            authorityDetails.addProperty("committedTargetAuthorityPreserved", true);
            return DrAdapterResult.success("Committed target authority retained while no transition is active",
                    GSON.toJson(authorityDetails));
        }
        if (!authorityStatus.getResult() && isStatusBoundaryFailure(authorityStatus)) {
            return handleStatusBoundaryFailure(plan, projectionRun, authorityStatus, authorityDetails,
                    "FTCTL_DR authority status failed validation; last-good projection was retained");
        }
        DrRunVO canceledFailoverRun = resolveCanceledFailoverReconciliationRun(plan, projectionRun);
        if (reconcileCanceledFailoverPreparation(plan, canceledFailoverRun, authorityRuntime)) {
            authorityDetails.addProperty("canceledFailoverCompensated", true);
            return DrAdapterResult.success("Canceled failover preparation was compensated before status projection",
                    GSON.toJson(authorityDetails));
        }
        if (isReleasedRuntime(authorityStatus, authorityRuntime)) {
            cleanupReleasedProjection(plan, authorityStatus, authorityRuntime);
            reconcileAcceptedRunFromStatus(plan, authorityStatus, authorityRuntime);
            authorityDetails.addProperty("releaseTerminalCommitted", true);
            return DrAdapterResult.success("FTCTL_DR release terminal projection committed",
                    GSON.toJson(authorityDetails));
        }
        FtctlDrCycleSnapshot latestCompletedCycle = latestCompletedCycle(authorityStatus);
        if (!isCoherentCycleSnapshot(plan, authorityStatus, latestCompletedCycle)) {
            markProjectionIntegrityFailure(plan, latestCompletedCycle, "DR_STATUS_CYCLE_SNAPSHOT_INCOHERENT");
            return DrAdapterResult.retryable("DR_STATUS_CYCLE_SNAPSHOT_INCOHERENT",
                    "FTCTL_DR completed cycle snapshot was not coherent; last-good projection was retained",
                    GSON.toJson(authorityDetails), STATUS_REFRESH_WAIT_SECONDS);
        }
        DrRunVO protectionProducerRun = resolveProtectionProducerRun(plan, authorityStatus, authorityRuntime);
        projectProtectionAuthority(plan, protectionProducerRun, authorityStatus, authorityRuntime);
        upsertRestorePointFromStatus(plan, protectionProducerRun, authorityStatus, authorityRuntime);
        reconcileDurableTargetMaterialization(plan, protectionProducerRun,
                authorityStatus, authorityRuntime);

        FtctlDrStatusAnswer status = authorityStatus;
        if (projectionRun != null) {
            FtctlDrStatusCommand operationCommand = new FtctlDrStatusCommand(plan.getUuid(), projectionRun.getUuid(),
                    FtctlDrStatusCommand.StatusScope.OPERATION);
            operationCommand.setWait(STATUS_REFRESH_WAIT_SECONDS);
            Answer operationAnswer;
            try {
                operationAnswer = sendStatusCommand(plan, projectionRun, operationCommand, hostId);
            } catch (RuntimeException e) {
                return DrAdapterResult.retryable(DrConstants.ERROR_ENGINE_UNAVAILABLE,
                        "Remote FTCTL_DR operation status is temporarily unavailable: " + e.getMessage(),
                        GSON.toJson(authorityDetails), STATUS_REFRESH_WAIT_SECONDS);
            }
            if (!(operationAnswer instanceof FtctlDrStatusAnswer)) {
                String message = operationAnswer != null ? operationAnswer.getDetails()
                        : "Agent returned no FTCTL_DR operation status answer";
                return DrAdapterResult.retryable(DrConstants.ERROR_ENGINE_UNAVAILABLE, message,
                        GSON.toJson(authorityDetails), STATUS_REFRESH_WAIT_SECONDS);
            }
            status = (FtctlDrStatusAnswer) operationAnswer;
        }

        JsonObject details = buildDetails(plan, hostId, status);
        details.add("authority", authorityDetails);
        if (!isCorrelatedRuntime(plan, projectionRun, status)) {
            details.addProperty("projectionIgnored", true);
            details.addProperty("reason", "STALE_OPERATION_IGNORED");
            persistRunProjectionEvent(plan, projectionRun, DrConstants.EVENT_PROJECTION_REFRESH,
                    DrConstants.EVENT_SEVERITY_WARN,
                    "Ignored FTCTL_DR status for a different plan/run", GSON.toJson(details));
            return DrAdapterResult.success("Ignored stale FTCTL_DR runtime status", GSON.toJson(details));
        }
        JsonObject runtimeStatus = parseObject(status.getStatusJson());
        if (projectionRun != null && isNotFoundStatus(status, runtimeStatus)) {
            if (deferRuntimeNotFound(plan, projectionRun, status, runtimeStatus)) {
                return DrAdapterResult.success("FTCTL_DR operation runtime is pending creation",
                        GSON.toJson(details));
            }
            failRunFromProjection(plan, projectionRun, status, runtimeStatus);
            return DrAdapterResult.failure(DrConstants.ERROR_RUNTIME_NOT_CREATED,
                    "FTCTL_DR operation runtime was not created within the allowed grace period",
                    GSON.toJson(details));
        }
        if (!status.getResult() && isStatusBoundaryFailure(status)) {
            return handleStatusBoundaryFailure(plan, projectionRun, status, details,
                    "FTCTL_DR status failed validation; last-good projection was retained");
        }
        reconcileCloudManagedTestTarget(plan, projectionRun, status, runtimeStatus);
        if (!hardwareContractMatches(plan, authorityRuntime)) {
            String message = "FTCTL_DR source hardware fingerprint differs from the persisted DR Plan";
            markPlanProjectionFailed(plan, DrConstants.ERROR_SOURCE_HARDWARE_CHANGED, message);
            return DrAdapterResult.failure(DrConstants.ERROR_SOURCE_HARDWARE_CHANGED, message, GSON.toJson(details));
        }
        if (!status.getResult()) {
            JsonObject runtime = parseObject(status.getStatusJson());
            if (isFailbackLifecyclePending(projectionRun, status, runtime)) {
                drFailbackLifecycleService.reconcile(plan, projectionRun, runtime);
                markFailbackLifecyclePending(projectionRun, status, runtime);
                return DrAdapterResult.success("FTCTL_DR Failback lifecycle acknowledgement is pending",
                        GSON.toJson(details));
            }
            if (isStatusTimeout(status, runtime)) {
                markProjectionStale(plan, status);
                return DrAdapterResult.retryable(DrConstants.ERROR_PROJECTION_STALE,
                        statusMessage(status, "FTCTL_DR status refresh timed out"),
                        GSON.toJson(details), STATUS_REFRESH_WAIT_SECONDS);
            }
            if (deferRuntimeNotFound(plan, projectionRun, status, runtime)) {
                return DrAdapterResult.success("FTCTL_DR runtime is not created yet; projection will retry", GSON.toJson(details));
            }
            reconcileAcceptedRunFromStatus(plan, status, runtime);
            return DrAdapterResult.failure(StringUtils.defaultIfBlank(status.getErrorCode(), DrConstants.ERROR_ENGINE_ACTION_FAILED),
                    projectionFailureMessage(status.getErrorCode(), status, runtime), GSON.toJson(details));
        }

        projectLiveTransferOverlay(plan, projectionRun, status);
        updatePlanFromStatus(plan, projectionRun, protectionProducerRun, status);
        return DrAdapterResult.success("FTCTL_DR runtime projection refreshed", GSON.toJson(details));
    }

    private Long resolveCoordinatorHostId(DrPlanVO plan) {
        DrWorkerRole role = StringUtils.startsWithIgnoreCase(plan != null ? plan.getDirection() : null, "VMWARE_")
                ? DrWorkerRole.VDDK_DATA_PLANE : DrWorkerRole.COORDINATOR;
        return drWorkerPlacementService != null
                ? drWorkerPlacementService.resolveWorkerHostId(plan, role) : null;
    }

    private boolean isInitialRuntimePending(DrPlanVO plan, DrRunVO projectionRun) {
        if (plan == null || projectionRun != null
                || !StringUtils.equalsIgnoreCase(plan.getState(), DrConstants.PLAN_STATE_NEW)) {
            return false;
        }
        if (drRunDao != null && drRunDao.findLatestByPlanId(plan.getId()) != null) {
            return false;
        }
        DrPlanRuntimeVO runtime = drPlanRuntimeDao != null ? drPlanRuntimeDao.findByPlanId(plan.getId()) : null;
        return runtime == null;
    }

    private Answer sendStatusCommand(DrPlanVO plan, DrRunVO run, FtctlDrStatusCommand command, Long localHostId) {
        DrRunVO routingRun = command != null
                && command.getStatusScope() == FtctlDrStatusCommand.StatusScope.PLAN_AUTHORITY ? null : run;
        if (pollsRemoteSource(plan, routingRun)) {
            return drRemoteAgentClient.execute(plan, "STATUS", command,
                    null, FtctlDrStatusAnswer.class);
        }
        return agentManager.easySend(localHostId, command);
    }

    private boolean pollsRemoteSource(DrPlanVO plan, DrRunVO run) {
        if (drRemoteAgentClient == null || !drRemoteAgentClient.isRemoteKvmSource(plan)
                || !StringUtils.equalsIgnoreCase(plan.getActiveSide(), "SOURCE")) {
            return false;
        }
        if (run == null) {
            return true;
        }
        if (DrFailoverExecutionPolicy.isDisaster(run)) {
            return false;
        }
        return StringUtils.equalsAnyIgnoreCase(run.getRunType(), DrConstants.RUN_TYPE_SYNC,
                DrConstants.RUN_TYPE_RECOVER_SYNC, DrConstants.RUN_TYPE_PAUSE_SYNC,
                DrConstants.RUN_TYPE_RESUME_SYNC, DrConstants.RUN_TYPE_FAILOVER,
                DrConstants.RUN_TYPE_RELEASE);
    }

    private String firstNonBlank(String first, String second) {
        return StringUtils.isNotBlank(first) ? first : second;
    }

    private void projectProtectionAuthority(DrPlanVO plan, DrRunVO protectionProducerRun, FtctlDrStatusAnswer status,
            JsonObject runtime) {
        Long generation = status.getRuntimeGeneration() != null ? status.getRuntimeGeneration()
                : longValue(runtime, "runtime_generation");
        Long leaseEpoch = status.getSchedulerLeaseEpoch() != null ? status.getSchedulerLeaseEpoch()
                : longValue(runtime, "scheduler_lease_epoch");
        Long authoritySequence = status.getAuthoritySequence() != null ? status.getAuthoritySequence()
                : longValue(runtime, "authority_sequence");
        Long sequence = status.getCurrentCheckpointSequence() != null ? status.getCurrentCheckpointSequence()
                : longValue(runtime, "current_checkpoint_sequence");
        leaseEpoch = leaseEpoch != null ? leaseEpoch : 0L;
        authoritySequence = authoritySequence != null ? authoritySequence
                : (generation != null ? generation : (sequence != null ? sequence : 0L));
        generation = authoritySequence;

        DrCutoverSessionVO committedTargetSession = findCommittedTargetAuthority(plan);
        boolean committedTargetAuthority = committedTargetSession != null;
        boolean targetProtectedRuntime = isTargetProtectedRuntime(status, runtime);
        DrPlanRuntimeVO authority = drPlanRuntimeDao.findByPlanId(plan.getId());
        if (committedTargetAuthority) {
            if (authority != null) {
                authoritySequence = Math.max(authoritySequence, authority.getAuthoritySequence());
            }
            DrSyncCycleVO latestCompleted = drSyncCycleDao.findLatestCompletedByPlanId(plan.getId());
            if (latestCompleted != null && latestCompleted.getAuthoritySequence() != null) {
                authoritySequence = Math.max(authoritySequence, latestCompleted.getAuthoritySequence());
            }
            generation = authoritySequence;
        }
        boolean sourceAuthorityHandoff = isCompletedFailbackSourceAuthorityHandoff(plan, authority, status, runtime);
        if (authority != null && !committedTargetAuthority && !sourceAuthorityHandoff) {
            boolean remoteKvmSourceAuthority = drRemoteAgentClient != null
                    && drRemoteAgentClient.isRemoteKvmSource(plan)
                    && StringUtils.equalsIgnoreCase(plan.getActiveSide(), DrConstants.AUTHORITY_SIDE_SOURCE);
            if (remoteKvmSourceAuthority
                    ? isStaleRemoteSourceAuthority(authority, leaseEpoch, authoritySequence)
                    : leaseEpoch < authority.getSchedulerLeaseEpoch()
                            || leaseEpoch == authority.getSchedulerLeaseEpoch()
                            && authoritySequence < authority.getAuthoritySequence()) {
                return;
            }
        }
        if (authority == null) {
            authority = new DrPlanRuntimeVO(plan.getId());
        }

        Date now = new Date();
        Date sourceAt = firstDate(parseDate(status.getLatestCompletedSourceCheckpointAt()),
                parseDate(status.getLastSourceCheckpointAt()),
                parseDate(stringValue(runtime, "latest_completed_source_checkpoint_at")),
                parseDate(stringValue(runtime, "last_source_checkpoint_at")));
        Date durableAt = firstDate(parseDate(status.getLatestCompletedTargetDurableAt()),
                parseDate(status.getLastTargetDurableAt()),
                parseDate(stringValue(runtime, "latest_completed_target_durable_at")),
                parseDate(stringValue(runtime, "last_target_durable_at")));
        String schedulerState = stringValue(runtime, "scheduler_state");
        String schedulerDesiredState = StringUtils.defaultIfBlank(status.getSchedulerDesiredState(),
                stringValue(runtime, "scheduler_desired_state"));
        String schedulerServiceUnit = StringUtils.defaultIfBlank(status.getSchedulerServiceUnit(),
                stringValue(runtime, "scheduler_service_unit"));
        String schedulerUnitActiveState = StringUtils.defaultIfBlank(status.getSchedulerUnitActiveState(),
                stringValue(runtime, "scheduler_unit_active_state"));
        String schedulerUnitSubState = StringUtils.defaultIfBlank(status.getSchedulerUnitSubState(),
                stringValue(runtime, "scheduler_unit_sub_state"));
        Long schedulerUnitMainPid = status.getSchedulerUnitMainPid() != null ? status.getSchedulerUnitMainPid()
                : longValue(runtime, "scheduler_unit_main_pid");
        String schedulerCgroup = StringUtils.defaultIfBlank(status.getSchedulerCgroup(),
                stringValue(runtime, "scheduler_cgroup"));
        String schedulerRecoveryState = StringUtils.defaultIfBlank(status.getSchedulerRecoveryState(),
                StringUtils.defaultIfBlank(stringValue(runtime, "scheduler_recovery_state"), DrConstants.SCHEDULER_RECOVERY_NONE));
        String schedulerRecoveryTrigger = StringUtils.defaultIfBlank(status.getSchedulerRecoveryTrigger(),
                stringValue(runtime, "scheduler_recovery_trigger"));
        Date schedulerRecoveredAt = firstDate(parseDate(status.getSchedulerRecoveredAt()),
                parseDate(stringValue(runtime, "scheduler_recovered_at")));
        String schedulerSessionUuid = StringUtils.defaultIfBlank(status.getSchedulerSessionUuid(),
                stringValue(runtime, "scheduler_session_uuid"));
        String schedulerHealth = StringUtils.defaultIfBlank(status.getSchedulerHealth(),
                stringValue(runtime, "scheduler_health"));
        String replicationActivity = StringUtils.defaultIfBlank(status.getReplicationActivity(),
                StringUtils.defaultIfBlank(stringValue(runtime, "replication_activity"), "IDLE"));
        String activeWorkerRunUuid = schemaSafeRuntimeRunUuid(StringUtils.defaultIfBlank(
                status.getActiveWorkerRunUuid(), stringValue(runtime, "active_worker_run_uuid")));
        Long activeWorkerPid = status.getActiveWorkerPid() != null ? status.getActiveWorkerPid()
                : longValue(runtime, "active_worker_pid");
        Long activeWorkerStartTicks = status.getActiveWorkerStartTicks() != null ? status.getActiveWorkerStartTicks()
                : longValue(runtime, "active_worker_start_ticks");
        Date workerHeartbeatAt = firstDate(parseDate(status.getWorkerHeartbeatAt()),
                parseDate(stringValue(runtime, "worker_heartbeat_at")));
        String controlRequestRunUuid = schemaSafeControlRequestRunUuid(StringUtils.defaultIfBlank(
                status.getControlRequestRunUuid(), stringValue(runtime, "control_request_run_uuid")));
        Boolean ownerMatched = status.getOwnerMatched() != null ? status.getOwnerMatched()
                : booleanValue(runtime, "owner_matched");
        String workerState = StringUtils.defaultIfBlank(status.getWorkerState(), stringValue(runtime, "worker_state"));
        String workerIdentityState = StringUtils.defaultIfBlank(status.getWorkerIdentityState(),
                stringValue(runtime, "worker_identity_state"));
        String workerLivenessState = StringUtils.defaultIfBlank(status.getWorkerLivenessState(),
                stringValue(runtime, "worker_liveness_state"));
        String workerLaunchNonce = StringUtils.defaultIfBlank(status.getWorkerLaunchNonce(),
                stringValue(runtime, "worker_launch_nonce"));
        Long workerGeneration = status.getWorkerGeneration() != null ? status.getWorkerGeneration()
                : longValue(runtime, "worker_generation");
        String transferActivityState = StringUtils.defaultIfBlank(status.getTransferActivityState(),
                stringValue(runtime, "transfer_activity_state"));
        Long transferPayloadBytes = status.getTransferPayloadBytes() != null ? status.getTransferPayloadBytes()
                : longValue(runtime, "transfer_payload_bytes");
        Integer transferProgressSchemaVersion = status.getTransferProgressSchemaVersion() != null ? status.getTransferProgressSchemaVersion()
                : integerValue(runtime, "transfer_progress_schema_version");
        Long transferCycleSequence = status.getTransferCycleSequence() != null ? status.getTransferCycleSequence()
                : longValue(runtime, "transfer_cycle_sequence");
        Long transferSampleSequence = status.getTransferSampleSequence() != null ? status.getTransferSampleSequence()
                : longValue(runtime, "transfer_sample_sequence");
        String transferPhase = StringUtils.defaultIfBlank(status.getTransferPhase(), stringValue(runtime, "transfer_phase"));
        String transferMode = StringUtils.defaultIfBlank(status.getTransferMode(), stringValue(runtime, "transfer_mode"));
        Long transferBytesTotal = status.getTransferBytesTotal() != null ? status.getTransferBytesTotal() : longValue(runtime, "transfer_bytes_total");
        Long transferBytesProcessed = status.getTransferBytesProcessed() != null ? status.getTransferBytesProcessed() : longValue(runtime, "transfer_bytes_processed");
        Long transferSourceReadBytes = status.getTransferSourceReadBytes() != null ? status.getTransferSourceReadBytes() : longValue(runtime, "transfer_source_read_bytes");
        Long transferTargetWrittenBytes = status.getTransferTargetWrittenBytes() != null ? status.getTransferTargetWrittenBytes() : longValue(runtime, "transfer_target_written_bytes");
        Long transferVerifiedBytes = status.getTransferVerifiedBytes() != null ? status.getTransferVerifiedBytes() : longValue(runtime, "transfer_verified_bytes");
        Double transferPercent = status.getTransferPercent() != null ? status.getTransferPercent() : doubleValue(runtime, "transfer_percent");
        Long transferThroughputBps = status.getTransferThroughputBps() != null ? status.getTransferThroughputBps() : longValue(runtime, "transfer_throughput_bps");
        Long transferEtaSeconds = status.getTransferEtaSeconds() != null ? status.getTransferEtaSeconds() : longValue(runtime, "transfer_eta_seconds");
        Integer transferCurrentDiskIndex = status.getTransferCurrentDiskIndex() != null ? status.getTransferCurrentDiskIndex() : integerValue(runtime, "transfer_current_disk_index");
        Integer transferDiskCount = status.getTransferDiskCount() != null ? status.getTransferDiskCount() : integerValue(runtime, "transfer_disk_count");
        Boolean transferProgressEstimated = status.getTransferProgressEstimated() != null ? status.getTransferProgressEstimated() : booleanValue(runtime, "transfer_progress_estimated");
        Long transferProgressSampleEpochMs = status.getTransferProgressSampleEpochMs() != null ? status.getTransferProgressSampleEpochMs() : longValue(runtime, "transfer_progress_sample_epoch_ms");
        Boolean transferProgressStale = status.getTransferProgressStale() != null ? status.getTransferProgressStale() : booleanValue(runtime, "transfer_progress_stale");
        Integer ownedProcessCount = status.getOwnedProcessCount() != null ? status.getOwnedProcessCount()
                : integerValue(runtime, "owned_process_count");
        Boolean runtimeEndpointsDrained = status.getRuntimeEndpointsDrained() != null
                ? status.getRuntimeEndpointsDrained() : booleanValue(runtime, "runtime_endpoints_drained");
        Boolean terminalAuthoritative = status.getTerminalAuthoritative() != null
                ? status.getTerminalAuthoritative() : booleanValue(runtime, "terminal_authoritative");
        String terminalSource = StringUtils.defaultIfBlank(status.getTerminalSource(),
                stringValue(runtime, "terminal_source"));
        Integer terminalVersion = status.getTerminalVersion() != null ? status.getTerminalVersion()
                : integerValue(runtime, "terminal_version");
        Boolean reconciliationRequired = status.getReconciliationRequired() != null
                ? status.getReconciliationRequired() : booleanValue(runtime, "reconciliation_required");
        String cycleState = StringUtils.defaultIfBlank(status.getCurrentCheckpointState(),
                StringUtils.defaultIfBlank(status.getCycleState(), stringValue(runtime, "cycle_state")));
        String cycleMode = StringUtils.defaultIfBlank(status.getCurrentCheckpointCycleType(),
                stringValue(runtime, "current_checkpoint_cycle_type"));
        String requestedMode = StringUtils.defaultIfBlank(status.getCurrentCheckpointRequestedMode(), cycleMode);
        String effectiveMode = StringUtils.defaultIfBlank(status.getCurrentCheckpointEffectiveMode(), requestedMode);
        String modeDecisionCode = StringUtils.defaultIfBlank(status.getCurrentCheckpointModeDecisionCode(),
                stringValue(runtime, "current_checkpoint_mode_decision_code"));
        String baselineState = StringUtils.defaultIfBlank(status.getBaselineState(), stringValue(runtime, "baseline_state"));
        String reseedReason = StringUtils.defaultIfBlank(status.getReseedReason(), stringValue(runtime, "reseed_reason"));
        Boolean pidAlive = status.getSchedulerPidAlive() != null ? status.getSchedulerPidAlive()
                : booleanValue(runtime, "scheduler_pid_alive");
        String errorCode = StringUtils.defaultIfBlank(status.getErrorCode(), stringValue(runtime, "error_code"));
        String errorMessage = StringUtils.defaultIfBlank(status.getErrorMessage(), stringValue(runtime, "error_message"));
        JsonObject cbtStatus = objectValue(runtime, "cbt_status");
        if (StringUtils.equalsIgnoreCase(stringValue(cbtStatus, "lifecycleState"), "ERROR")) {
            errorCode = StringUtils.defaultIfBlank(errorCode, stringValue(cbtStatus, "error_code"));
            errorMessage = StringUtils.defaultIfBlank(errorMessage, stringValue(cbtStatus, "message"));
        }
        String nbdTeardownState = StringUtils.defaultIfBlank(status.getNbdTeardownState(),
                stringValue(runtime, "nbd_teardown_state"));
        Integer nbdQuarantinedDeviceCount = status.getNbdQuarantinedDeviceCount() != null
                ? status.getNbdQuarantinedDeviceCount() : integerValue(runtime, "nbd_quarantined_device_count");
        String nbdTeardownErrorCode = StringUtils.defaultIfBlank(status.getNbdTeardownErrorCode(),
                stringValue(runtime, "nbd_teardown_error_code"));
        String nbdTeardownErrorMessage = StringUtils.defaultIfBlank(status.getNbdTeardownErrorMessage(),
                stringValue(runtime, "nbd_teardown_error_message"));
        boolean nbdQuarantined = StringUtils.equalsIgnoreCase(nbdTeardownState, "QUARANTINED")
                || nbdQuarantinedDeviceCount != null && nbdQuarantinedDeviceCount > 0;
        if (committedTargetAuthority && !targetProtectedRuntime) {
            schedulerState = "STOPPED";
            schedulerDesiredState = "STOPPED";
            schedulerHealth = "SUPPRESSED";
            schedulerRecoveryState = DrConstants.SCHEDULER_RECOVERY_SUPPRESSED;
            replicationActivity = "STOPPED";
            pidAlive = false;
            ownerMatched = false;
            activeWorkerRunUuid = null;
            activeWorkerPid = null;
            activeWorkerStartTicks = null;
            workerHeartbeatAt = null;
            ownedProcessCount = 0;
            reconciliationRequired = false;
            workerState = "IDLE";
            workerIdentityState = "IDLE";
            workerLivenessState = "STOPPED";
            transferActivityState = "IDLE";
            errorCode = null;
            errorMessage = null;
        }
        Integer committedRpoSeconds = committedTargetRpoSeconds(plan, committedTargetSession, sourceAt);
        long rpoAge = committedTargetAuthority && committedRpoSeconds != null
                ? Math.max(0L, committedRpoSeconds)
                : durableAt != null ? Math.max(0L, (now.getTime() - durableAt.getTime()) / 1000L) : Long.MAX_VALUE;
        long rpoLimit = plan.getRpoSeconds() != null ? Math.max(1, plan.getRpoSeconds()) : 300L;
        String evaluatedFreshnessState = classifyRpoFreshness(committedTargetAuthority, durableAt, rpoAge, rpoLimit);
        boolean overdue = StringUtils.equals(evaluatedFreshnessState, "OVERDUE");
        String engineProtectionState = StringUtils.defaultIfBlank(status.getProtectionState(),
                stringValue(runtime, "protection_state"));
        boolean sourceRecoveryWaiting = (Boolean.TRUE.equals(booleanValue(runtime, "retryable"))
                && StringUtils.equalsIgnoreCase(errorCode, "DR_SOURCE_SITE_UNAVAILABLE"))
                || StringUtils.equalsIgnoreCase(schedulerHealth, "WAITING_SOURCE")
                || StringUtils.equalsIgnoreCase(cycleState, "WAITING_SOURCE");
        boolean runtimeFailed = !committedTargetAuthority
                && !sourceRecoveryWaiting
                && (StringUtils.equalsAnyIgnoreCase(engineProtectionState, "ERROR", "FAILED")
                || StringUtils.equalsAnyIgnoreCase(cycleState, "ERROR", "FAILED")
                || StringUtils.equalsAnyIgnoreCase(schedulerHealth, "DEAD", "OWNER_MISMATCH", "DUPLICATE_WORKER"));
        boolean reseeding = isFullSeedMode(cycleMode)
                && !StringUtils.equalsAnyIgnoreCase(cycleState, "COMPLETED", "READY");
        long heartbeatAge = workerHeartbeatAt != null
                ? Math.max(0L, (now.getTime() - workerHeartbeatAt.getTime()) / 1000L) : Long.MAX_VALUE;
        boolean sessionMatched = StringUtils.equals(plan.getUuid(), schedulerSessionUuid);
        boolean schedulerHealthy = Boolean.TRUE.equals(pidAlive) && Boolean.TRUE.equals(ownerMatched)
                && sessionMatched && heartbeatAge <= SCHEDULER_HEARTBEAT_STALE_SECONDS
                && StringUtils.equalsAnyIgnoreCase(schedulerHealth, "HEALTHY")
                && StringUtils.equalsAnyIgnoreCase(schedulerState, "RUNNING", "STARTED", "COMPLETED");
        String projectionRuntimeState = StringUtils.upperCase(StringUtils.defaultIfBlank(status.getState(),
                stringValue(runtime, "state")), Locale.ROOT);
        boolean targetMaterialized = Boolean.TRUE.equals(status.getTargetMaterialized())
                || Boolean.TRUE.equals(booleanValue(runtime, "target_materialized"))
                || isSyncTargetReady(plan, status, runtime, projectionRuntimeState);

        String protectionState;
        String freshnessState = evaluatedFreshnessState;
        if (nbdQuarantined) {
            protectionState = "DEGRADED";
            freshnessState = "RECOVERY_REQUIRED";
            errorCode = StringUtils.defaultIfBlank(nbdTeardownErrorCode, "DR_NBD_RECOVERY_REQUIRED");
            errorMessage = StringUtils.defaultIfBlank(nbdTeardownErrorMessage,
                    "NBD teardown did not reach a stable detached state");
        } else if (committedTargetAuthority && targetProtectedRuntime) {
            protectionState = DrConstants.PLAN_STATE_READY;
            freshnessState = "WITHIN_RPO";
        } else if (committedTargetAuthority) {
            protectionState = "FAILED_OVER_UNPROTECTED";
            freshnessState = "WITHIN_RPO";
        } else if (sourceRecoveryWaiting) {
            protectionState = "DEGRADED";
            freshnessState = "SOURCE_UNAVAILABLE";
        } else if (runtimeFailed) {
            protectionState = DrConstants.PLAN_STATE_ERROR;
        } else if (reseeding) {
            protectionState = "RESEEDING";
        } else if (StringUtils.equalsAnyIgnoreCase(engineProtectionState, "PAUSED")
                || StringUtils.equalsIgnoreCase(schedulerState, "PAUSED")) {
            protectionState = DrConstants.PLAN_STATE_PAUSED;
        } else if ((StringUtils.equalsIgnoreCase(plan.getActiveSide(), "TARGET")
                || StringUtils.equalsAnyIgnoreCase(stringValue(runtime, "active_side"), "TARGET")
                || StringUtils.equalsAnyIgnoreCase(stringValue(runtime, "state"), "FAILED_OVER", "PROMOTED"))
                && !schedulerHealthy) {
            protectionState = "FAILED_OVER_UNPROTECTED";
        } else if (!schedulerHealthy || overdue) {
            protectionState = "DEGRADED";
        } else if (targetMaterialized && durableAt != null) {
            protectionState = DrConstants.PLAN_STATE_READY;
        } else {
            protectionState = DrConstants.PLAN_STATE_SYNCING;
        }

        String producerRunUuid = schemaSafeRuntimeRunUuid(resolveProtectionProducerRunUuid(status, runtime));
        authority.setEngineRunUuid(producerRunUuid);
        authority.setRuntimeGeneration(generation);
        authority.setSchedulerState(schedulerState);
        authority.setSchedulerDesiredState(StringUtils.defaultIfBlank(schedulerDesiredState,
                committedTargetAuthority && !targetProtectedRuntime ? "STOPPED" : "RUNNING"));
        authority.setSchedulerServiceUnit(schedulerServiceUnit);
        authority.setSchedulerUnitActiveState(schedulerUnitActiveState);
        authority.setSchedulerUnitSubState(schedulerUnitSubState);
        authority.setSchedulerUnitMainPid(schedulerUnitMainPid);
        authority.setSchedulerCgroup(schedulerCgroup);
        if (StringUtils.equalsIgnoreCase(schedulerRecoveryState, DrConstants.SCHEDULER_RECOVERY_RECOVERING)
                && !StringUtils.equalsIgnoreCase(authority.getSchedulerRecoveryState(), DrConstants.SCHEDULER_RECOVERY_RECOVERING)) {
            authority.setSchedulerRecoveryAttempts(authority.getSchedulerRecoveryAttempts() + 1);
        }
        authority.setSchedulerRecoveryState(schedulerRecoveryState);
        authority.setSchedulerRecoveryTrigger(schedulerRecoveryTrigger);
        authority.setSchedulerRecoveryErrorCode(StringUtils.equalsIgnoreCase(schedulerRecoveryState,
                DrConstants.SCHEDULER_RECOVERY_FAILED) ? errorCode : null);
        authority.setSchedulerRecoveryErrorMessage(StringUtils.equalsIgnoreCase(schedulerRecoveryState,
                DrConstants.SCHEDULER_RECOVERY_FAILED) ? errorMessage : null);
        authority.setSchedulerRecoveredAt(schedulerRecoveredAt);
        authority.setSchedulerPidAlive(Boolean.TRUE.equals(pidAlive));
        authority.setSchedulerSessionUuid(schedulerSessionUuid);
        authority.setSchedulerLeaseEpoch(leaseEpoch);
        authority.setAuthoritySequence(authoritySequence);
        authority.setPlanCycleSequence(status.getPlanCycleSequence() != null ? status.getPlanCycleSequence()
                : longValue(runtime, "plan_cycle_sequence"));
        authority.setSchedulerHealthState(schedulerHealth);
        authority.setReplicationActivityState(replicationActivity);
        authority.setActiveWorkerRunUuid(activeWorkerRunUuid);
        authority.setActiveWorkerPid(activeWorkerPid);
        authority.setActiveWorkerStartTicks(activeWorkerStartTicks);
        authority.setWorkerHeartbeatAt(workerHeartbeatAt);
        authority.setControlRequestRunUuid(controlRequestRunUuid);
        authority.setOwnerMatched(Boolean.TRUE.equals(ownerMatched));
        authority.setWorkerState(workerState);
        authority.setWorkerIdentityState(workerIdentityState);
        authority.setWorkerLivenessState(workerLivenessState);
        authority.setWorkerLaunchNonce(workerLaunchNonce);
        authority.setWorkerGeneration(workerGeneration);
        FtctlDrCycleSnapshot latestCompletedSnapshot = latestCompletedCycle(status);
        Long latestCompletedSequence = latestCompletedSequence(status);
        boolean latestCompletedSummary = StringUtils.equalsIgnoreCase(replicationActivity, "IDLE")
                && latestCompletedSnapshot != null
                && isCoherentCycleSnapshot(plan, status, latestCompletedSnapshot)
                && latestCompletedSequence != null
                && latestCompletedSequence.equals(latestCompletedSnapshot.getSequence());
        boolean validTransferSnapshot = transferProgressSchemaVersion != null && transferProgressSchemaVersion >= 2
                && transferBytesTotal != null && transferBytesTotal > 0;
        boolean retainedTransferSnapshot = authority.getTransferProgressSchemaVersion() != null
                && authority.getTransferProgressSchemaVersion() >= 2
                && authority.getTransferBytesTotal() != null && authority.getTransferBytesTotal() > 0;
        if (latestCompletedSummary) {
            projectLatestCompletedTransferSummary(authority, latestCompletedSnapshot);
        } else if (validTransferSnapshot || !retainedTransferSnapshot) {
            authority.setTransferActivityState(transferActivityState);
            authority.setTransferPayloadBytes(transferPayloadBytes);
            authority.setTransferProgressSchemaVersion(transferProgressSchemaVersion);
            authority.setTransferCycleSequence(transferCycleSequence);
            authority.setTransferSampleSequence(transferSampleSequence);
            authority.setTransferPhase(transferPhase);
            authority.setTransferMode(transferMode);
            authority.setTransferBytesTotal(transferBytesTotal);
            authority.setTransferBytesProcessed(transferBytesProcessed);
            authority.setTransferSourceReadBytes(transferSourceReadBytes);
            authority.setTransferTargetWrittenBytes(transferTargetWrittenBytes);
            authority.setTransferVerifiedBytes(transferVerifiedBytes);
            authority.setTransferPercent(transferPercent);
            authority.setTransferThroughputBps(transferThroughputBps);
            authority.setTransferEtaSeconds(transferEtaSeconds);
            authority.setTransferCurrentDiskIndex(transferCurrentDiskIndex);
            authority.setTransferDiskCount(transferDiskCount);
            authority.setTransferProgressEstimated(Boolean.TRUE.equals(transferProgressEstimated));
            authority.setTransferProgressSampledAt(transferProgressSampleEpochMs != null && transferProgressSampleEpochMs > 0
                    ? new Date(transferProgressSampleEpochMs) : null);
            authority.setTransferProgressStale(Boolean.TRUE.equals(transferProgressStale));
        }
        authority.setOwnedProcessCount(ownedProcessCount != null ? Math.max(0, ownedProcessCount) : 0);
        authority.setRuntimeEndpointsDrained(Boolean.TRUE.equals(runtimeEndpointsDrained));
        authority.setTerminalSource(terminalSource);
        authority.setTerminalVersion(terminalVersion);
        authority.setTerminalAuthoritative(Boolean.TRUE.equals(terminalAuthoritative));
        boolean liveOperation = StringUtils.equalsAnyIgnoreCase(workerLivenessState, "ALIVE", "MATCHED")
                || StringUtils.equalsAnyIgnoreCase(transferActivityState, "COPYING", "VERIFYING")
                || ownedProcessCount != null && ownedProcessCount > 0;
        String observedRunUuid = StringUtils.defaultIfBlank(status.getRunUuid(), activeWorkerRunUuid);
        if (Boolean.TRUE.equals(terminalAuthoritative)) {
            authority.setReconciliationState("TERMINAL");
            authority.setReconciliationRunUuid(observedRunUuid);
            authority.setReconciliationChecks(0);
        } else if (liveOperation) {
            authority.setReconciliationState("LIVE");
            authority.setReconciliationRunUuid(observedRunUuid);
            authority.setReconciliationChecks(0);
        } else if (Boolean.TRUE.equals(reconciliationRequired)
                || StringUtils.equalsAnyIgnoreCase(workerIdentityState, "CONFLICT", "MISMATCH", "UNVERIFIED")
                || StringUtils.equalsIgnoreCase(workerLivenessState, "DEAD_CONFIRMED")) {
            boolean sameObservation = StringUtils.equals(authority.getReconciliationRunUuid(), observedRunUuid);
            authority.setReconciliationState(StringUtils.equalsIgnoreCase(workerLivenessState, "DEAD_CONFIRMED")
                    ? "DEAD_CONFIRMING" : "RECONCILING");
            authority.setReconciliationRunUuid(observedRunUuid);
            authority.setReconciliationChecks(sameObservation ? authority.getReconciliationChecks() + 1 : 1);
        } else {
            authority.setReconciliationState("NONE");
            authority.setReconciliationRunUuid(null);
            authority.setReconciliationChecks(0);
        }
        authority.setCurrentCycleSequence(sequence);
        authority.setCurrentCycleState(cycleState);
        authority.setCurrentCycleMode(cycleMode);
        authority.setBaselineState(baselineState);
        authority.setReseedReason(reseedReason);
        authority.setLastModeDecisionCode(StringUtils.defaultIfBlank(status.getLatestCompletedModeDecisionCode(), modeDecisionCode));
        authority.setConsecutiveAutomaticReseedCount(status.getConsecutiveAutomaticReseedCount() != null
                ? status.getConsecutiveAutomaticReseedCount() : 0);
        Long latestCompletedCycleSequence = status.getLatestCompletedCycleSequence() != null
                ? status.getLatestCompletedCycleSequence() : status.getLatestCompletedCheckpointSequence();
        if (latestCompletedCycleSequence != null) {
            authority.setLatestCompletedCycleSequence(latestCompletedCycleSequence);
            authority.setProjectionIntegritySequence(latestCompletedCycleSequence);
        }
        if (status.getLatestCompletedIncrementalVerified() != null) {
            authority.setLatestCompletedIncrementalVerified(status.getLatestCompletedIncrementalVerified());
        }
        authority.setProjectionIntegrityState("CONSISTENT");
        authority.setProjectionIntegrityCode(null);
        authority.setNbdTeardownState(nbdTeardownState);
        authority.setNbdQuarantinedDeviceCount(nbdQuarantinedDeviceCount != null
                ? nbdQuarantinedDeviceCount : 0);
        authority.setNbdTeardownErrorCode(nbdTeardownErrorCode);
        authority.setNbdTeardownErrorMessage(nbdTeardownErrorMessage);
        authority.setProtectionState(protectionState);
        authority.setFreshnessState(freshnessState);
        authority.setSchedulerNextRunAt(parseDate(status.getSchedulerNextRunAt()));
        authority.setSchedulerExecutionBudgetSeconds(status.getSchedulerExecutionBudgetSeconds());
        authority.setSchedulerCycleWallDurationSeconds(status.getSchedulerCycleWallDurationSeconds());
        authority.setLastStatusAt(now);
        authority.setLastSourceCheckpointAt(sourceAt);
        authority.setLastTargetDurableAt(durableAt);
        authority.setRpoAgeSeconds(committedTargetAuthority || durableAt != null ? rpoAge : null);
        authority.setRpoOverdue(overdue);
        authority.setErrorCode(errorCode);
        authority.setErrorMessage(errorMessage);
        authority.setStatusJson(compactRuntimeStatusJson(status.getStatusJson()));
        Long canonicalCompletedSequence = projectSyncCyclesAtomically(plan, protectionProducerRun, status,
                producerRunUuid, sequence, requestedMode, effectiveMode, cycleState, baselineState, reseedReason,
                sourceAt, errorCode, errorMessage);
        if (canonicalCompletedSequence != null) {
            authority.setLatestCompletedCycleSequence(canonicalCompletedSequence);
            authority.setProjectionIntegritySequence(canonicalCompletedSequence);
            if (latestCompletedSummary) {
                authority.setTransferCycleSequence(canonicalCompletedSequence);
                authority.setTransferSampleSequence(canonicalCompletedSequence);
            }
        }
        authority.markUpdated();
        if (authority.getId() == 0) {
            drPlanRuntimeDao.persist(authority);
        } else {
            drPlanRuntimeDao.update(authority.getId(), authority);
        }
        if (committedTargetSession != null) {
            upsertCutoverDisks(plan, committedTargetSession, runtime);
        }
    }

    boolean isStaleRemoteSourceAuthority(DrPlanRuntimeVO authority, long leaseEpoch, long authoritySequence) {
        if (authoritySequence != authority.getAuthoritySequence()) {
            return authoritySequence < authority.getAuthoritySequence();
        }
        return leaseEpoch < authority.getSchedulerLeaseEpoch();
    }

    static String schemaSafeControlRequestRunUuid(String value) {
        return StringUtils.length(value) <= 40 ? value : null;
    }

    static String schemaSafeRuntimeRunUuid(String value) {
        if (StringUtils.isBlank(value) || StringUtils.length(value) <= 40) {
            return value;
        }
        return UUID.nameUUIDFromBytes(("ftctl-runtime-run:" + value).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private Long projectSyncCyclesAtomically(DrPlanVO plan, DrRunVO protectionProducerRun, FtctlDrStatusAnswer status,
            String producerRunUuid, Long sequence, String requestedMode, String effectiveMode, String cycleState,
            String baselineState, String reseedReason, Date sourceAt, String errorCode, String errorMessage) {
        Long completedSequence = latestCompletedSequence(status);
        if (StringUtils.isBlank(producerRunUuid) || sequence == null && completedSequence == null) {
            return null;
        }
        return Transaction.execute(new TransactionCallback<Long>() {
            @Override
            public Long doInTransaction(TransactionStatus transactionStatus) {
                if (drPlanDao.acquireInLockTable(plan.getId(), 10) == null) {
                    return null;
                }
                Long canonicalCompletedSequence = null;
                try {
                    if (sequence != null) {
                        projectCurrentSyncCycle(plan, protectionProducerRun, status, sequence, requestedMode, effectiveMode,
                                cycleState, baselineState, reseedReason, sourceAt, errorCode, errorMessage);
                    }
                    if (completedSequence != null) {
                        DrSyncCycleVO completedCycle = projectLatestCompletedSyncCycle(plan, protectionProducerRun, status,
                                completedSequence, baselineState);
                        if (completedCycle != null) {
                            canonicalCompletedSequence = completedCycle.getSequence();
                            terminalizeSupersededSyncCycles(plan, completedCycle);
                        }
                    }
                    return canonicalCompletedSequence;
                } finally {
                    drPlanDao.releaseFromLockTable(plan.getId());
                }
            }
        });
    }

    void projectLatestCompletedTransferSummary(DrPlanRuntimeVO authority, FtctlDrCycleSnapshot snapshot) {
        if (authority == null || snapshot == null || snapshot.getSequence() == null) {
            return;
        }
        authority.setTransferActivityState("IDLE");
        authority.setTransferProgressSchemaVersion(2);
        authority.setTransferCycleSequence(snapshot.getSequence());
        authority.setTransferSampleSequence(snapshot.getSequence());
        authority.setTransferPhase("COMPLETED");
        authority.setTransferMode(snapshot.getEffectiveMode());
        authority.setTransferBytesTotal(snapshot.getVirtualBytes());
        authority.setTransferBytesProcessed(snapshot.getTransferPayloadBytes());
        authority.setTransferSourceReadBytes(snapshot.getSourceReadBytes());
        authority.setTransferTargetWrittenBytes(snapshot.getTargetWrittenBytes());
        authority.setTransferVerifiedBytes(snapshot.getTargetWrittenBytes());
        authority.setTransferPayloadBytes(snapshot.getTransferPayloadBytes());
        authority.setTransferPercent(100D);
        authority.setTransferThroughputBps(snapshot.getThroughputBps());
        authority.setTransferEtaSeconds(0L);
        authority.setTransferCurrentDiskIndex(null);
        authority.setTransferDiskCount(null);
        authority.setTransferProgressEstimated(Boolean.TRUE.equals(snapshot.getMetricsEstimated()));
        authority.setTransferProgressSampledAt(parseDate(snapshot.getTargetDurableAt()));
        authority.setTransferProgressStale(false);
    }

    void terminalizeSupersededSyncCycles(DrPlanVO plan, DrSyncCycleVO completedCycle) {
        if (plan == null || completedCycle == null || completedCycle.getCompleted() == null) {
            return;
        }
        List<DrSyncCycleVO> incompleteCycles = drSyncCycleDao.listIncompleteAtOrBeforeSequence(plan.getId(),
                completedCycle.getSequence(), SUPERSEDED_CYCLE_RECONCILE_LIMIT);
        if (incompleteCycles == null || incompleteCycles.isEmpty()) {
            return;
        }
        for (DrSyncCycleVO cycle : incompleteCycles) {
            if (isPinnedByNonTerminalRun(plan.getId(), cycle.getSequence())) {
                continue;
            }
            boolean reverseCheckpoint = StringUtils.equalsAnyIgnoreCase(cycle.getState(),
                    "FAILBACK_DATA_READY", "REVERSE_DATA_READY");
            drSyncCycleDao.terminalize(cycle.getId(), reverseCheckpoint ? "CONSUMED" : "SUPERSEDED",
                    reverseCheckpoint ? "CONSUMED_BY_DURABLE_CYCLE" : "SUPERSEDED_BY_DURABLE_CYCLE",
                    completedCycle.getCompleted());
        }
    }

    boolean isPinnedByNonTerminalRun(long planId, long sequence) {
        List<DrRunVO> runs = drRunDao.listByPlanId(planId);
        if (runs == null || runs.isEmpty()) {
            return false;
        }
        return runs.stream().anyMatch(run -> run != null && run.getCompleted() == null
                && run.getAcceptedCycleSequence() != null && run.getAcceptedCycleSequence() == sequence);
    }

    void projectLiveTransferOverlay(DrPlanVO plan, DrRunVO projectionRun, FtctlDrStatusAnswer status) {
        if (plan == null || projectionRun == null || status == null
                || status.getTransferProgressSchemaVersion() == null
                || status.getTransferProgressSchemaVersion() < 2
                || status.getTransferBytesTotal() == null
                || status.getTransferBytesTotal() <= 0
                || !StringUtils.equals(status.getRunUuid(), projectionRun.getUuid())) {
            return;
        }
        DrPlanRuntimeVO authority = drPlanRuntimeDao.findByPlanId(plan.getId());
        if (authority == null) {
            return;
        }
        String ownerRunUuid = authority.getControlRequestRunUuid();
        if (StringUtils.isNotBlank(ownerRunUuid) && !StringUtils.equals(ownerRunUuid, projectionRun.getUuid())) {
            return;
        }
        long candidateCycle = status.getTransferCycleSequence() != null ? status.getTransferCycleSequence() : 0L;
        long authorityCycle = authority.getPlanCycleSequence() != null ? authority.getPlanCycleSequence()
                : (authority.getCurrentCycleSequence() != null ? authority.getCurrentCycleSequence() : 0L);
        if (candidateCycle > 0 && authorityCycle > 0 && candidateCycle != authorityCycle) {
            return;
        }
        long currentCycle = authority.getTransferCycleSequence() != null ? authority.getTransferCycleSequence() : 0L;
        long candidateSample = status.getTransferSampleSequence() != null ? status.getTransferSampleSequence() : 0L;
        long currentSample = authority.getTransferSampleSequence() != null ? authority.getTransferSampleSequence() : 0L;
        if (candidateCycle < currentCycle || candidateCycle == currentCycle && candidateSample < currentSample) {
            return;
        }

        authority.setTransferActivityState(status.getTransferActivityState());
        authority.setTransferPayloadBytes(status.getTransferPayloadBytes());
        authority.setTransferProgressSchemaVersion(status.getTransferProgressSchemaVersion());
        authority.setTransferCycleSequence(status.getTransferCycleSequence());
        authority.setTransferSampleSequence(status.getTransferSampleSequence());
        authority.setTransferPhase(status.getTransferPhase());
        authority.setTransferMode(status.getTransferMode());
        authority.setTransferBytesTotal(status.getTransferBytesTotal());
        authority.setTransferBytesProcessed(status.getTransferBytesProcessed());
        authority.setTransferSourceReadBytes(status.getTransferSourceReadBytes());
        authority.setTransferTargetWrittenBytes(status.getTransferTargetWrittenBytes());
        authority.setTransferVerifiedBytes(status.getTransferVerifiedBytes());
        authority.setTransferPercent(status.getTransferPercent());
        authority.setTransferThroughputBps(status.getTransferThroughputBps());
        authority.setTransferEtaSeconds(status.getTransferEtaSeconds());
        authority.setTransferCurrentDiskIndex(status.getTransferCurrentDiskIndex());
        authority.setTransferDiskCount(status.getTransferDiskCount());
        authority.setTransferProgressEstimated(Boolean.TRUE.equals(status.getTransferProgressEstimated()));
        authority.setTransferProgressSampledAt(status.getTransferProgressSampleEpochMs() != null
                && status.getTransferProgressSampleEpochMs() > 0
                ? new Date(status.getTransferProgressSampleEpochMs()) : null);
        authority.setTransferProgressStale(Boolean.TRUE.equals(status.getTransferProgressStale()));
        authority.markUpdated();
        drPlanRuntimeDao.update(authority.getId(), authority);
    }

    private DrCutoverSessionVO findCommittedTargetAuthority(DrPlanVO plan) {
        if (plan == null || !StringUtils.equalsIgnoreCase(plan.getActiveSide(), "TARGET")) {
            return null;
        }
        DrCutoverSessionVO session = drCutoverSessionDao.findCurrentAuthorityByPlanId(plan.getId());
        return session != null
                && StringUtils.equalsIgnoreCase(session.getCloudPromotionState(), "PROMOTED")
                && StringUtils.equalsIgnoreCase(session.getEngineAckState(), "ACKNOWLEDGED")
                && StringUtils.equalsIgnoreCase(session.getState(), DrConstants.PLAN_STATE_FAILED_OVER)
                ? session : null;
    }

    private DrCutoverSessionVO reconcileAcknowledgedTargetAuthority(DrPlanVO plan,
            FtctlDrStatusAnswer status, JsonObject runtime) {
        if (plan == null || runtime == null || drCutoverSessionDao == null
                || !StringUtils.equalsIgnoreCase(stringValue(runtime, "active_side"),
                        DrConstants.AUTHORITY_SIDE_TARGET)
                || !StringUtils.equalsIgnoreCase(stringValue(runtime, "target_promotion_state"), "PROMOTED")
                || !StringUtils.equalsIgnoreCase(stringValue(runtime, "engine_ack_state"), "ACKNOWLEDGED")) {
            return null;
        }
        String cloudSessionUuid = stringValue(runtime, "cloud_cutover_session_id");
        Long authorityGeneration = longValue(runtime, "cloud_authority_generation");
        DrCutoverSessionVO candidate = drCutoverSessionDao.findLatestActiveByPlanId(plan.getId());
        if (!matchesEngineAuthorityTuple(candidate, cloudSessionUuid, authorityGeneration)) {
            return null;
        }
        DrCutoverSessionVO reconciled = reconcileCutoverSessionFromEngine(candidate,
                cloudSessionUuid, authorityGeneration);
        if (reconciled == null) {
            return null;
        }
        Date committedAt = reconciled.getEngineAckAt() != null ? reconciled.getEngineAckAt() : new Date();
        plan.setState(DrConstants.PLAN_STATE_FAILED_OVER);
        plan.setActiveSide(DrConstants.AUTHORITY_SIDE_TARGET);
        plan.setLastErrorCode(null);
        plan.setLastErrorMessage(null);
        plan.markUpdated();
        drPlanDao.update(plan.getId(), plan);
        updateReplicaRuntimeProjection(plan, status, runtime, DrConstants.REPLICA_STATE_FAILED_OVER,
                DrConstants.AUTHORITY_SIDE_TARGET, "POWERED_ON");
        applyFailedOverRuntime(plan, status, authorityGeneration, committedAt);
        DrRunVO run = drRunDao != null ? drRunDao.findById(reconciled.getRunId()) : null;
        if (run != null && !StringUtils.equalsIgnoreCase(run.getState(), DrConstants.RUN_STATE_SUCCEEDED)
                && run.getCompleted() == null) {
            completeRunFromProjection(plan, run, status);
        }
        return reconciled;
    }

    private DrCutoverSessionVO reconcileCutoverSessionFromEngine(DrCutoverSessionVO session,
            String cloudSessionUuid, Long authorityGeneration) {
        if (session.getId() == 0) {
            if (!matchesEngineAuthorityTuple(session, cloudSessionUuid, authorityGeneration)) {
                return null;
            }
            return applyEngineAuthorityAcknowledgement(session, new Date());
        }
        final long sessionId = session.getId();
        return Transaction.execute(new TransactionCallback<DrCutoverSessionVO>() {
            @Override
            public DrCutoverSessionVO doInTransaction(TransactionStatus transactionStatus) {
                DrCutoverSessionVO current = drCutoverSessionDao.lockRow(sessionId, true);
                if (!matchesEngineAuthorityTuple(current, cloudSessionUuid, authorityGeneration)) {
                    return null;
                }
                return applyEngineAuthorityAcknowledgement(current, new Date());
            }
        });
    }

    private boolean matchesEngineAuthorityTuple(DrCutoverSessionVO session,
            String cloudSessionUuid, Long authorityGeneration) {
        return session != null && StringUtils.isNotBlank(cloudSessionUuid) && authorityGeneration != null
                && StringUtils.equals(session.getUuid(), cloudSessionUuid)
                && authorityGeneration.equals(session.getCloudAuthorityGeneration());
    }

    private DrCutoverSessionVO applyEngineAuthorityAcknowledgement(DrCutoverSessionVO session, Date acknowledgedAt) {
        if (isAcknowledgedTargetCutover(session)) {
            return session;
        }
        session.setEngineAckState("ACKNOWLEDGED");
        session.setEngineAckAt(acknowledgedAt);
        session.setCommitState("ACKNOWLEDGED");
        session.setCloudPromotionState("PROMOTED");
        session.setState(DrConstants.PLAN_STATE_FAILED_OVER);
        session.setCompletedAt(acknowledgedAt);
        session.setErrorCode(null);
        session.setErrorMessage(null);
        session.markUpdated();
        drCutoverSessionDao.update(session.getId(), session);
        return session;
    }

    boolean isCompletedFailbackSourceAuthorityHandoff(DrPlanVO plan, DrPlanRuntimeVO authority,
            FtctlDrStatusAnswer status, JsonObject runtime) {
        if (plan == null || authority == null || status == null || drRemoteAgentClient == null
                || !StringUtils.equalsIgnoreCase(plan.getActiveSide(), DrConstants.AUTHORITY_SIDE_SOURCE)
                || !drRemoteAgentClient.isRemoteKvmSource(plan)
                || !(StringUtils.equalsIgnoreCase(authority.getProtectionState(), "FAILED_OVER_UNPROTECTED")
                        || StringUtils.equalsIgnoreCase(authority.getSchedulerHealthState(), "SUPPRESSED")
                        || StringUtils.equalsIgnoreCase(authority.getSchedulerRecoveryState(),
                                DrConstants.SCHEDULER_RECOVERY_SUPPRESSED))) {
            return false;
        }
        DrFailbackSessionVO session = drFailbackSessionDao != null
                ? drFailbackSessionDao.findLatestActiveByPlanId(plan.getId()) : null;
        if (session == null || !StringUtils.equalsIgnoreCase(session.getState(), "COMPLETED")
                || !StringUtils.equalsIgnoreCase(session.getEngineAckState(), "ACKNOWLEDGED")) {
            return false;
        }
        Long requiredSequence = session.getRequiredPostFailbackCheckpointSequence();
        Long completedSequence = status.getLatestCompletedCheckpointSequence() != null
                ? status.getLatestCompletedCheckpointSequence()
                : longValue(runtime, "latest_completed_checkpoint_sequence");
        boolean checkpointContinuous = requiredSequence == null
                || completedSequence != null && completedSequence >= requiredSequence;
        String schedulerState = stringValue(runtime, "scheduler_state");
        String schedulerHealth = StringUtils.defaultIfBlank(status.getSchedulerHealth(),
                stringValue(runtime, "scheduler_health"));
        Boolean schedulerPidAlive = status.getSchedulerPidAlive() != null ? status.getSchedulerPidAlive()
                : booleanValue(runtime, "scheduler_pid_alive");
        Boolean ownerMatched = status.getOwnerMatched() != null ? status.getOwnerMatched()
                : booleanValue(runtime, "owner_matched");
        String schedulerSessionUuid = StringUtils.defaultIfBlank(status.getSchedulerSessionUuid(),
                stringValue(runtime, "scheduler_session_uuid"));
        return checkpointContinuous
                && StringUtils.equalsIgnoreCase(schedulerSessionUuid, plan.getUuid())
                && StringUtils.equalsAnyIgnoreCase(schedulerState, "RUNNING", "STARTED")
                && StringUtils.equalsIgnoreCase(schedulerHealth, "HEALTHY")
                && Boolean.TRUE.equals(schedulerPidAlive)
                && Boolean.TRUE.equals(ownerMatched);
    }

    private Integer committedTargetRpoSeconds(DrPlanVO plan, DrCutoverSessionVO session, Date sourceCheckpointAt) {
        if (session == null) {
            return plan != null ? plan.getTargetReadyRpoSeconds() : null;
        }
        Date sourceAt = sourceCheckpointAt != null ? sourceCheckpointAt : plan.getLastSourceCheckpointAt();
        Date committedAt = session.getCompletedAt() != null ? session.getCompletedAt() : session.getEngineAckAt();
        if (sourceAt == null || committedAt == null) {
            return plan.getTargetReadyRpoSeconds();
        }
        long seconds = Math.max(0L, (committedAt.getTime() - sourceAt.getTime()) / 1000L);
        return seconds > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) seconds;
    }

    void projectCurrentSyncCycle(DrPlanVO plan, DrRunVO projectionRun, FtctlDrStatusAnswer status,
            long sequence, String requestedMode, String effectiveMode, String cycleState, String baselineState,
            String reseedReason, Date sourceAt, String errorCode, String errorMessage) {
        String engineRunUuid = schemaSafeRuntimeRunUuid(
                resolveProtectionProducerRunUuid(status, parseObject(status.getStatusJson())));
        String cycleToken = plan.getUuid() + ":" + sequence;
        DrSyncCycleVO cycle = resolveCycleForProjection(plan, status, engineRunUuid, sequence, cycleToken);
        if (cycle.getStarted() == null) {
            cycle.setStarted(new Date());
        }
        if (cycle.getCompleted() != null) {
            bindAcceptedCycleIfEligible(projectionRun, cycle);
            return;
        }
        if (cycle.getRunId() == null) {
            cycle.setRunId(projectionRun != null ? projectionRun.getId() : null);
        }
        cycle.setSchedulerSessionUuid(status.getSchedulerSessionUuid());
        cycle.setSchedulerLeaseEpoch(status.getSchedulerLeaseEpoch());
        cycle.setAuthoritySequence(status.getAuthoritySequence());
        cycle.setRequestedMode(requestedMode);
        cycle.setCycleToken(cycleToken);
        cycle.setEffectiveMode(effectiveMode);
        cycle.setState(StringUtils.defaultIfBlank(cycleState, status.getState()));
        cycle.setCommitState(status.getDataCommitState());
        cycle.setBaselineState(baselineState);
        cycle.setReseedReason(reseedReason);
        cycle.setAutomaticReseed(status.getCurrentCheckpointAutomaticReseed());
        cycle.setModeDecisionCode(status.getCurrentCheckpointModeDecisionCode());
        cycle.setInvalidBaselineDiskCount(status.getCurrentCheckpointInvalidBaselineDiskCount());
        cycle.setNbdTeardownState(status.getNbdTeardownState());
        cycle.setNbdQuarantinedDeviceCount(status.getNbdQuarantinedDeviceCount() != null
                ? status.getNbdQuarantinedDeviceCount() : 0);
        cycle.setNbdTeardownErrorCode(status.getNbdTeardownErrorCode());
        cycle.setNbdTeardownErrorMessage(status.getNbdTeardownErrorMessage());
        cycle.setSourceCheckpointAt(sourceAt);
        cycle.setErrorCode(errorCode);
        cycle.setErrorMessage(errorMessage);
        persistSyncCycle(cycle);
        bindAcceptedCycleIfEligible(projectionRun, cycle);
    }

    DrSyncCycleVO projectLatestCompletedSyncCycle(DrPlanVO plan, DrRunVO projectionRun, FtctlDrStatusAnswer status,
            long sequence, String baselineState) {
        FtctlDrCycleSnapshot snapshot = latestCompletedCycle(status);
        if (!isCoherentCycleSnapshot(plan, status, snapshot) || snapshot == null) {
            return null;
        }
        sequence = snapshot.getSequence();
        String engineRunUuid = schemaSafeRuntimeRunUuid(
                resolveProtectionProducerRunUuid(status, parseObject(status.getStatusJson())));
        String cycleToken = StringUtils.defaultIfBlank(snapshot.getCycleToken(), plan.getUuid() + ":" + sequence);
        DrSyncCycleVO cycle = resolveCycleForProjection(plan, status, engineRunUuid, sequence, cycleToken);
        if (cycle.getStarted() == null) {
            cycle.setStarted(parseDate(status.getLatestCompletedSourceCheckpointAt()));
        }
        if (cycle.getCompleted() != null && StringUtils.equals(cycle.getCycleToken(), cycleToken)) {
            bindAcceptedCycleIfEligible(projectionRun, cycle);
            return cycle;
        }
        Date sourceAt = parseDate(status.getLatestCompletedSourceCheckpointAt());
        Date durableAt = parseDate(status.getLatestCompletedTargetDurableAt());
        if (cycle.getRunId() == null) {
            cycle.setRunId(projectionRun != null ? projectionRun.getId() : null);
        }
        cycle.setSchedulerSessionUuid(status.getSchedulerSessionUuid());
        cycle.setSchedulerLeaseEpoch(status.getSchedulerLeaseEpoch());
        cycle.setAuthoritySequence(status.getAuthoritySequence());
        cycle.setCycleToken(cycleToken);
        cycle.setRequestedMode(StringUtils.defaultIfBlank(snapshot.getRequestedMode(),
                status.getLatestCompletedCheckpointCycleType()));
        cycle.setEffectiveMode(snapshot.getEffectiveMode());
        cycle.setState(StringUtils.defaultIfBlank(snapshot.getState(), "READY"));
        cycle.setCommitState("LOCAL_DURABLE");
        cycle.setBaselineGeneration(snapshot.getBaselineGeneration());
        cycle.setBaselineState(StringUtils.defaultIfBlank(baselineState, "LOCAL_DURABLE"));
        cycle.setReseedReason(status.getLatestCompletedReseedReason());
        cycle.setAutomaticReseed(status.getLatestCompletedAutomaticReseed());
        cycle.setModeDecisionCode(status.getLatestCompletedModeDecisionCode());
        cycle.setInvalidBaselineDiskCount(status.getLatestCompletedInvalidBaselineDiskCount());
        cycle.setIncrementalVerified(snapshot.getIncrementalVerified());
        cycle.setMetricsEstimated(snapshot.getMetricsEstimated());
        cycle.setVirtualBytes(snapshot.getVirtualBytes());
        cycle.setChangedBytes(snapshot.getChangedBytes());
        cycle.setSourceReadBytes(snapshot.getSourceReadBytes());
        cycle.setTargetWrittenBytes(snapshot.getTargetWrittenBytes());
        cycle.setTransferPayloadBytes(snapshot.getTransferPayloadBytes());
        cycle.setChangedExtentCount(snapshot.getChangedExtentCount());
        cycle.setDurationMs(snapshot.getDurationMs());
        cycle.setThroughputBps(snapshot.getThroughputBps());
        cycle.setNbdTeardownState(snapshot.getNbdTeardownState());
        cycle.setNbdTeardownStartedAt(epochDate(snapshot.getNbdTeardownStartedAtEpochMs()));
        cycle.setNbdTeardownCompletedAt(epochDate(snapshot.getNbdTeardownCompletedAtEpochMs()));
        cycle.setNbdTeardownDurationMs(snapshot.getNbdTeardownDurationMs());
        cycle.setNbdSourceDeviceCount(snapshot.getNbdSourceDeviceCount());
        cycle.setNbdTargetDeviceCount(snapshot.getNbdTargetDeviceCount());
        cycle.setNbdQuarantinedDeviceCount(snapshot.getNbdQuarantinedDeviceCount() != null
                ? snapshot.getNbdQuarantinedDeviceCount() : 0);
        cycle.setNbdTeardownErrorCode(snapshot.getNbdTeardownErrorCode());
        cycle.setNbdTeardownErrorMessage(snapshot.getNbdTeardownErrorMessage());
        cycle.setSourceCheckpointAt(sourceAt);
        cycle.setTargetDurableAt(durableAt);
        cycle.setCompleted(durableAt != null ? durableAt : new Date());
        cycle.setErrorCode(null);
        cycle.setErrorMessage(null);
        persistSyncCycle(cycle);
        bindAcceptedCycleIfEligible(projectionRun, cycle);
        return cycle;
    }

    DrSyncCycleVO resolveCycleForProjection(DrPlanVO plan, FtctlDrStatusAnswer status, String engineRunUuid,
            long engineSequence, String cycleToken) {
        JsonObject runtime = parseObject(status.getStatusJson());
        String schedulerSessionUuid = StringUtils.defaultIfBlank(status.getSchedulerSessionUuid(),
                stringValue(runtime, "scheduler_session_uuid"));
        Long schedulerLeaseEpoch = status.getSchedulerLeaseEpoch() != null ? status.getSchedulerLeaseEpoch()
                : longValue(runtime, "scheduler_lease_epoch");
        if (StringUtils.isNotBlank(schedulerSessionUuid) && schedulerLeaseEpoch != null
                && StringUtils.isNotBlank(cycleToken)) {
            DrSyncCycleVO exact = drSyncCycleDao.findByPlanSchedulerCycle(plan.getId(), schedulerSessionUuid,
                    schedulerLeaseEpoch, cycleToken);
            if (exact != null) {
                return exact;
            }
        }
        DrSyncCycleVO sequenceCycle = drSyncCycleDao.findByPlanSequence(plan.getId(), engineSequence);
        boolean legacySequenceOnlyIdentity = sequenceCycle != null
                && StringUtils.isBlank(sequenceCycle.getSchedulerSessionUuid())
                && sequenceCycle.getSchedulerLeaseEpoch() == null
                && StringUtils.isBlank(sequenceCycle.getCycleToken());
        if (legacySequenceOnlyIdentity || sameSchedulerCycle(sequenceCycle,
                schedulerSessionUuid, schedulerLeaseEpoch, cycleToken)) {
            return sequenceCycle;
        }
        DrSyncCycleVO latest = drSyncCycleDao.findLatestByPlanId(plan.getId());
        if (sequenceCycle == null && (latest == null || latest.getSequence() < engineSequence)) {
            return newProjectionCycle(plan.getId(), engineRunUuid, engineSequence, cycleToken);
        }
        long canonicalSequence = engineSequence;
        if (latest != null && latest.getSequence() >= canonicalSequence && latest.getSequence() < Long.MAX_VALUE) {
            canonicalSequence = latest.getSequence() + 1L;
        }
        Long authoritySequence = status.getAuthoritySequence() != null ? status.getAuthoritySequence()
                : longValue(runtime, "authority_sequence");
        if (authoritySequence != null) {
            canonicalSequence = Math.max(canonicalSequence, authoritySequence);
        }
        return newProjectionCycle(plan.getId(), engineRunUuid, canonicalSequence, cycleToken);
    }

    private DrSyncCycleVO newProjectionCycle(long planId, String engineRunUuid, long canonicalSequence,
            String cycleToken) {
        DrSyncCycleVO cycle = new DrSyncCycleVO(planId, engineRunUuid, canonicalSequence);
        cycle.setCycleToken(cycleToken);
        return cycle;
    }

    boolean sameSchedulerCycle(DrSyncCycleVO cycle, String schedulerSessionUuid, Long schedulerLeaseEpoch,
            String cycleToken) {
        return cycle != null
                && StringUtils.equals(cycle.getSchedulerSessionUuid(), schedulerSessionUuid)
                && Objects.equals(cycle.getSchedulerLeaseEpoch(), schedulerLeaseEpoch)
                && StringUtils.equals(cycle.getCycleToken(), cycleToken);
    }

    void bindAcceptedCycleIfEligible(DrRunVO run, DrSyncCycleVO cycle) {
        if (run == null || cycle == null || run.getAcceptedCycleSequence() != null
                || !isFullReseedRun(run)
                || !isFullSeedMode(cycle.getRequestedMode())) {
            return;
        }
        boolean runOwned = cycle.getRunId() != null && cycle.getRunId() == run.getId();
        if (!runOwned && !StringUtils.equals(run.getUuid(), cycle.getEngineRunUuid())) {
            return;
        }
        String cycleToken = cycle.getCycleToken();
        if (StringUtils.isBlank(cycleToken)) {
            return;
        }
        run.setAcceptedCycleSequence(cycle.getSequence());
        run.setAcceptedCycleToken(cycleToken);
        run.markUpdated();
        drRunDao.update(run.getId(), run);
    }

    private boolean isFullReseedRun(DrRunVO run) {
        if (run == null || !StringUtils.equalsIgnoreCase(run.getRunType(), DrConstants.RUN_TYPE_SYNC)) {
            return false;
        }
        return StringUtils.equalsIgnoreCase(stringValue(parseObject(run.getRequestJson()), "mode"), "FULL_RESEED");
    }

    private DrSyncCycleVO resolveAcceptedCycle(DrRunVO run) {
        if (run == null) {
            return null;
        }
        DrSyncCycleVO cycle = run.getAcceptedCycleSequence() != null
                ? drSyncCycleDao.findByPlanSequence(run.getPlanId(), run.getAcceptedCycleSequence())
                : drSyncCycleDao.findLatestCompletedByRunIdAndRequestedMode(run.getId(), "FULL_RESEED");
        if (cycle == null && run.getAcceptedCycleSequence() == null) {
            cycle = drSyncCycleDao.findLatestCompletedByRunIdAndRequestedMode(run.getId(), "FULL_SEED");
        }
        if (cycle != null && run.getAcceptedCycleSequence() == null) {
            bindAcceptedCycleIfEligible(run, cycle);
        }
        return cycle;
    }

    boolean isAcceptedFullReseedCycleSatisfied(DrRunVO run, FtctlDrStatusAnswer status, JsonObject runtime) {
        if (!isFullReseedRun(run) || status == null) {
            return false;
        }
        String controlRequestRunUuid = StringUtils.defaultIfBlank(status.getControlRequestRunUuid(),
                stringValue(runtime, "control_request_run_uuid"));
        boolean terminalAuthoritative = Boolean.TRUE.equals(status.getTerminalAuthoritative())
                || Boolean.TRUE.equals(booleanValue(runtime, "terminal_authoritative"))
                || StringUtils.equalsIgnoreCase(StringUtils.defaultIfBlank(status.getTerminalSource(),
                        stringValue(runtime, "terminal_source")), "ENGINE_TERMINAL");
        DrSyncCycleVO cycle = resolveAcceptedCycle(run);
        boolean runOwnedDurableCycle = isAcceptedRunOwnedDurableFullSeedCycle(run, cycle);
        boolean controlRequestMatches = StringUtils.equals(run.getUuid(), controlRequestRunUuid);
        if ((!controlRequestMatches && !runOwnedDurableCycle)
                || (!terminalAuthoritative && !runOwnedDurableCycle)) {
            return false;
        }
        return runOwnedDurableCycle || cycle != null && cycle.getCompleted() != null
                && isFullSeedMode(cycle.getRequestedMode())
                && StringUtils.equalsAnyIgnoreCase(cycle.getState(), "READY", "COMPLETED", "TARGET_READY")
                && StringUtils.equalsAnyIgnoreCase(cycle.getCommitState(), "LOCAL_DURABLE", "COMMITTED", "DURABLE")
                && run.getAcceptedCycleSequence() != null
                && run.getAcceptedCycleSequence() == cycle.getSequence()
                && StringUtils.equals(run.getAcceptedCycleToken(), cycle.getCycleToken());
    }

    private boolean isAcceptedRunOwnedDurableFullSeedCycle(DrRunVO run, DrSyncCycleVO cycle) {
        return run != null && cycle != null && cycle.getRunId() != null && cycle.getRunId() == run.getId()
                && cycle.getCompleted() != null
                && isFullSeedMode(cycle.getRequestedMode())
                && StringUtils.equalsAnyIgnoreCase(cycle.getState(), "READY", "COMPLETED", "TARGET_READY")
                && StringUtils.equalsAnyIgnoreCase(cycle.getCommitState(), "LOCAL_DURABLE", "COMMITTED", "DURABLE")
                && run.getAcceptedCycleSequence() != null
                && run.getAcceptedCycleSequence() == cycle.getSequence()
                && StringUtils.equals(run.getAcceptedCycleToken(), cycle.getCycleToken());
    }

    private void persistSyncCycle(DrSyncCycleVO cycle) {
        cycle.markUpdated();
        if (cycle.getId() == 0) {
            drSyncCycleDao.persist(cycle);
        } else {
            drSyncCycleDao.update(cycle.getId(), cycle);
        }
    }

    private FtctlDrCycleSnapshot latestCompletedCycle(FtctlDrStatusAnswer status) {
        FtctlDrCycleSnapshot snapshot = status.getLatestCompletedCycle();
        Long completedSequence = latestCompletedSequence(status);
        if (snapshot != null || completedSequence == null) {
            return snapshot;
        }
        snapshot = new FtctlDrCycleSnapshot();
        snapshot.setPlanUuid(status.getPlanUuid());
        snapshot.setRunUuid(resolveProtectionProducerRunUuid(status, parseObject(status.getStatusJson())));
        snapshot.setSequence(completedSequence);
        snapshot.setCycleToken(status.getLatestCompletedCycleToken());
        snapshot.setState(status.getLatestCompletedCheckpointState());
        snapshot.setRequestedMode(status.getLatestCompletedRequestedMode());
        snapshot.setEffectiveMode(status.getLatestCompletedEffectiveMode());
        snapshot.setBaselineGeneration(status.getLatestCompletedBaselineGeneration());
        snapshot.setIncrementalVerified(status.getLatestCompletedIncrementalVerified());
        snapshot.setMetricsEstimated(status.getLatestCompletedMetricsEstimated());
        snapshot.setVirtualBytes(status.getLatestCompletedVirtualBytes());
        snapshot.setChangedBytes(status.getLatestCompletedChangedBytes());
        snapshot.setSourceReadBytes(status.getLatestCompletedSourceReadBytes());
        snapshot.setTargetWrittenBytes(status.getLatestCompletedTargetWrittenBytes());
        snapshot.setTransferPayloadBytes(status.getLatestCompletedTransferPayloadBytes());
        snapshot.setChangedExtentCount(status.getLatestCompletedChangedExtentCount());
        snapshot.setDurationMs(status.getLatestCompletedDurationMs());
        snapshot.setThroughputBps(status.getLatestCompletedThroughputBps());
        snapshot.setNbdTeardownState(status.getLatestCompletedNbdTeardownState());
        snapshot.setNbdTeardownStartedAtEpochMs(status.getLatestCompletedNbdTeardownStartedAtEpochMs());
        snapshot.setNbdTeardownCompletedAtEpochMs(status.getLatestCompletedNbdTeardownCompletedAtEpochMs());
        snapshot.setNbdTeardownDurationMs(status.getLatestCompletedNbdTeardownDurationMs());
        snapshot.setNbdSourceDeviceCount(status.getLatestCompletedNbdSourceDeviceCount());
        snapshot.setNbdTargetDeviceCount(status.getLatestCompletedNbdTargetDeviceCount());
        snapshot.setNbdQuarantinedDeviceCount(status.getLatestCompletedNbdQuarantinedDeviceCount());
        snapshot.setNbdTeardownErrorCode(status.getLatestCompletedNbdTeardownErrorCode());
        snapshot.setNbdTeardownErrorMessage(status.getLatestCompletedNbdTeardownErrorMessage());
        snapshot.setSourceCheckpointAt(status.getLatestCompletedSourceCheckpointAt());
        snapshot.setTargetDurableAt(status.getLatestCompletedTargetDurableAt());
        return snapshot;
    }

    private Long latestCompletedSequence(FtctlDrStatusAnswer status) {
        if (status == null) {
            return null;
        }
        return status.getLatestCompletedCycleSequence() != null
                ? status.getLatestCompletedCycleSequence()
                : status.getLatestCompletedCheckpointSequence();
    }

    private boolean isCoherentCycleSnapshot(DrPlanVO plan, FtctlDrStatusAnswer status,
            FtctlDrCycleSnapshot snapshot) {
        if (snapshot == null) {
            return true;
        }
        String producerRunUuid = resolveProtectionProducerRunUuid(status, parseObject(status.getStatusJson()));
        if (snapshot.getSequence() == null || !StringUtils.equals(plan.getUuid(), snapshot.getPlanUuid())
                || !StringUtils.equals(producerRunUuid, snapshot.getRunUuid())) {
            return false;
        }
        String expectedToken = snapshot.getPlanUuid() + ":" + snapshot.getSequence();
        if (StringUtils.isBlank(snapshot.getCycleToken())) {
            snapshot.setCycleToken(expectedToken);
        } else if (!StringUtils.equals(expectedToken, snapshot.getCycleToken())) {
            return false;
        }
        if (snapshot.getBaselineGeneration() != null
                && !snapshot.getBaselineGeneration().equals(snapshot.getSequence())) {
            return false;
        }
        if (StringUtils.equalsIgnoreCase(snapshot.getEffectiveMode(), "CBT_INCREMENTAL")
                && Boolean.TRUE.equals(snapshot.getIncrementalVerified())
                && !StringUtils.equalsIgnoreCase(snapshot.getNbdTeardownState(), "DRAINED")) {
            return false;
        }
        if (StringUtils.equalsIgnoreCase(snapshot.getNbdTeardownState(), "QUARANTINED")
                && (snapshot.getNbdQuarantinedDeviceCount() == null
                        || snapshot.getNbdQuarantinedDeviceCount() <= 0
                        || StringUtils.isBlank(snapshot.getNbdTeardownErrorCode()))) {
            return false;
        }
        Long changed = snapshot.getChangedBytes();
        Long written = snapshot.getTargetWrittenBytes();
        if (changed != null && written != null && changed == 0L && written == 0L) {
            return StringUtils.isBlank(snapshot.getEffectiveMode())
                    || StringUtils.equalsIgnoreCase(snapshot.getEffectiveMode(), "NO_CHANGE");
        }
        if (changed != null && written != null && (changed > 0L || written > 0L)) {
            return !StringUtils.equalsIgnoreCase(snapshot.getEffectiveMode(), "NO_CHANGE");
        }
        return true;
    }

    private Date epochDate(Long epochMs) {
        return epochMs != null && epochMs > 0L ? new Date(epochMs) : null;
    }

    private void markProjectionIntegrityFailure(DrPlanVO plan, FtctlDrCycleSnapshot snapshot, String errorCode) {
        DrPlanRuntimeVO runtime = drPlanRuntimeDao.findByPlanId(plan.getId());
        if (runtime == null) {
            runtime = new DrPlanRuntimeVO(plan.getId());
        }
        runtime.setProjectionIntegrityState("INCONSISTENT");
        runtime.setProjectionIntegrityCode(errorCode);
        runtime.setProjectionIntegritySequence(snapshot != null ? snapshot.getSequence() : null);
        runtime.markUpdated();
        if (runtime.getId() == 0) {
            drPlanRuntimeDao.persist(runtime);
        } else {
            drPlanRuntimeDao.update(runtime.getId(), runtime);
        }
    }


    private void updatePlanFromStatus(DrPlanVO plan, DrRunVO projectionRun, DrRunVO protectionProducerRun,
            FtctlDrStatusAnswer status) {
        boolean changed = false;
        JsonObject runtime = parseObject(status.getStatusJson());
        DrCutoverSessionVO cutoverSession = upsertCutoverSession(plan, projectionRun, status, runtime);
        DrFailbackSessionVO failbackSession = drFailbackLifecycleService.reconcile(plan, projectionRun, runtime);
        if (failbackSession == null && drFailbackSessionDao != null) {
            failbackSession = drFailbackSessionDao.findLatestActiveByPlanId(plan.getId());
        }
        if (preserveCommittedSourceAuthorityDuringFailback(plan, status, runtime, failbackSession)) {
            reconcileAcceptedRunFromStatus(plan, status, runtime);
            return;
        }
        DrRunVO terminalReconciliationRun = resolveCanceledFailoverReconciliationRun(plan, projectionRun);
        if (reconcileCanceledFailoverPreparation(plan, terminalReconciliationRun, runtime)) {
            return;
        }
        if (isReleasedRuntime(status, runtime)) {
            applyReleasedResourceDisposition(plan, projectionRun);
            cleanupReleasedProjection(plan, status, runtime);
            reconcileAcceptedRunFromStatus(plan, status, runtime);
            return;
        }
        if (isFiniteOperationRun(projectionRun)) {
            reconcileAcceptedRunFromStatus(plan, status, runtime);
            return;
        }
        if (projectionRun != null
                && StringUtils.equalsIgnoreCase(projectionRun.getRunType(), DrConstants.RUN_TYPE_REPROTECT)) {
            // Reprotect failures must close their accepted Run before preserving the
            // committed TARGET authority. Successful reprotects continue through the
            // regular protection-state projection below.
            reconcileAcceptedRunFromStatus(plan, status, runtime);
        }
        if (preserveTerminalMaterializationFailure(plan, status, runtime)) {
            return;
        }
        if (preserveCommittedTargetAuthorityAfterReprotectFailure(plan, status, runtime)) {
            return;
        }
        String runtimeProtectionState = StringUtils.defaultIfBlank(status.getProtectionState(),
                stringValue(runtime, "protection_state"));
        String planState = toPlanState(StringUtils.defaultIfBlank(runtimeProtectionState, status.getState()));
        String projectionRuntimeState = StringUtils.upperCase(StringUtils.defaultIfBlank(status.getState(),
                stringValue(runtime, "state")), Locale.ROOT);
        if (StringUtils.equals(planState, DrConstants.PLAN_STATE_SYNCING)
                && isSyncTargetReady(plan, status, runtime, projectionRuntimeState)) {
            planState = DrConstants.PLAN_STATE_READY;
        }
        if (isCutoverReadyRuntime(plan, status, runtime)) {
            try {
                ensurePlannedSourceIsolation(plan, projectionRun, cutoverSession, runtime);
            } catch (RuntimeException e) {
                LOGGER.warn("Unable to isolate source before DR cutover for plan {}",
                        plan.getUuid(), e);
                markSourceIsolationWaiting(plan, projectionRun, e);
                plan.setState(DrConstants.PLAN_STATE_COMMIT_VERIFYING);
                plan.setActiveSide(DrConstants.AUTHORITY_SIDE_SOURCE);
                plan.setLastErrorCode(sourceIsolationErrorCode(e));
                plan.setLastErrorMessage(e.getMessage());
                plan.markUpdated();
                drPlanDao.update(plan.getId(), plan);
                return;
            }
            try {
                stopPlanOwnedTargetExportForPromotion(plan, projectionRun, cutoverSession);
                DrTargetPowerOnResult powerOnResult = drTargetMaterializationService.ensureTargetPoweredOn(plan.getId());
                if (!commitCloudOwnedCutover(plan, projectionRun, cutoverSession, status, runtime, powerOnResult)) {
                    return;
                }
                runtime.addProperty("state", "FAILED_OVER");
                runtime.addProperty("active_side", "TARGET");
                runtime.addProperty("target_power_state", "POWERED_ON");
                runtime.addProperty("target_promotion_state", "PROMOTED");
                planState = DrConstants.PLAN_STATE_FAILED_OVER;
            } catch (RuntimeException e) {
                LOGGER.warn("Unable to complete Cloud-owned DR cutover projection for plan {}",
                        plan.getUuid(), e);
                plan.setLastErrorCode("DR_TARGET_POWER_ON_FAILED");
                plan.setLastErrorMessage(e.getMessage());
                plan.markUpdated();
                drPlanDao.update(plan.getId(), plan);
                return;
            }
        }
        boolean targetReferencePresent = hasTargetReferenceForDirection(plan);
        boolean durableCheckpointPresent = hasDurableCheckpoint(status, runtime);
        Date projectedDurableAt = firstDate(parseDate(status.getLatestCompletedTargetDurableAt()),
                parseDate(status.getLastTargetDurableAt()), plan.getLastTargetDurableAt());
        if (StringUtils.equals(planState, DrConstants.PLAN_STATE_READY) && projectedDurableAt != null
                && plan.getRpoSeconds() != null) {
            long ageSeconds = Math.max(0L, (System.currentTimeMillis() - projectedDurableAt.getTime()) / 1000L);
            long graceSeconds = Math.min(30L, Math.max(5L, plan.getRpoSeconds() / 10L));
            if (ageSeconds > plan.getRpoSeconds() + graceSeconds) {
                planState = DrConstants.HEALTH_DEGRADED;
            }
        }
        if (StringUtils.equals(planState, DrConstants.PLAN_STATE_READY)
                && (!targetReferencePresent || !durableCheckpointPresent)) {
            planState = DrConstants.PLAN_STATE_SYNCING;
        }
        if (StringUtils.isNotBlank(planState) && !StringUtils.equals(planState, plan.getState())) {
            plan.setState(planState);
            changed = true;
        }
        Date sourceCheckpointAt = parseDate(status.getLastSourceCheckpointAt());
        if (sourceCheckpointAt != null) {
            plan.setLastSourceCheckpointAt(sourceCheckpointAt);
            changed = true;
        }
        Date targetDurableAt = parseDate(status.getLastTargetDurableAt());
        if (targetDurableAt != null) {
            plan.setLastTargetDurableAt(targetDurableAt);
            if (targetReferencePresent && durableCheckpointPresent) {
                plan.setTargetReadyAt(targetDurableAt);
            } else {
                plan.setTargetReadyAt(null);
            }
            changed = true;
        }
        DrCutoverSessionVO committedTargetSession = findCommittedTargetAuthority(plan);
        if (committedTargetSession != null) {
            Integer frozenRpoSeconds = committedTargetRpoSeconds(plan, committedTargetSession,
                    plan.getLastSourceCheckpointAt());
            if (frozenRpoSeconds != null && !frozenRpoSeconds.equals(plan.getTargetReadyRpoSeconds())) {
                plan.setTargetReadyRpoSeconds(frozenRpoSeconds);
                changed = true;
            }
        } else if (status.getTargetReadyRpoSeconds() != null) {
            plan.setTargetReadyRpoSeconds(targetReferencePresent ? status.getTargetReadyRpoSeconds() : null);
            changed = true;
        }
        if (StringUtils.isNotBlank(status.getErrorCode())) {
            plan.setLastErrorCode(status.getErrorCode());
            plan.setLastErrorMessage(projectionFailureMessage(status.getErrorCode(), status, runtime));
            changed = true;
        } else if (isHealthyRuntimeProgress(status, runtime) && StringUtils.isNotBlank(plan.getLastErrorCode())) {
            plan.setLastErrorCode(null);
            plan.setLastErrorMessage(null);
            changed = true;
        }
        if (isFailbackRestoredRuntime(planState, runtime, failbackSession)) {
            if (!StringUtils.equals(plan.getState(), DrConstants.PLAN_STATE_READY)) {
                plan.setState(DrConstants.PLAN_STATE_READY);
                changed = true;
            }
            if (!StringUtils.equals(plan.getActiveSide(), "SOURCE")) {
                plan.setActiveSide("SOURCE");
                changed = true;
            }
            updateReplicaRuntimeProjection(plan, status, runtime, DrConstants.REPLICA_STATE_READY,
                    "SOURCE", StringUtils.defaultIfBlank(stringValue(runtime, "target_power_state"),
                            DrConstants.REPLICA_POWER_STATE_POWERED_OFF));
        } else if (isReprotectedRuntime(planState, runtime)) {
            if (!StringUtils.equals(plan.getState(), planState)) {
                plan.setState(planState);
                changed = true;
            }
            if (!StringUtils.equals(plan.getActiveSide(), "TARGET")) {
                plan.setActiveSide("TARGET");
                changed = true;
            }
            updateReplicaRuntimeProjection(plan, status, runtime, DrConstants.REPLICA_STATE_READY,
                    "TARGET", StringUtils.defaultIfBlank(stringValue(runtime, "target_power_state"), "POWER_ON_DELEGATED"));
        } else if (isFailedOverRuntime(planState, runtime)) {
            if (!StringUtils.equals(plan.getState(), DrConstants.PLAN_STATE_FAILED_OVER)) {
                plan.setState(DrConstants.PLAN_STATE_FAILED_OVER);
                changed = true;
            }
            if (!StringUtils.equals(plan.getActiveSide(), "TARGET")) {
                plan.setActiveSide("TARGET");
                changed = true;
            }
            updateReplicaRuntimeProjection(plan, status, runtime, DrConstants.REPLICA_STATE_FAILED_OVER,
                    "TARGET", StringUtils.defaultIfBlank(stringValue(runtime, "target_power_state"), "POWER_ON_DELEGATED"));
        }
        if (changed) {
            plan.markUpdated();
            drPlanDao.update(plan.getId(), plan);
        }
        reconcileAcceptedRunFromStatus(plan, status, runtime);
    }

    private boolean preserveCommittedSourceAuthorityDuringFailback(DrPlanVO plan,
            FtctlDrStatusAnswer status, JsonObject runtime, DrFailbackSessionVO session) {
        if (plan == null || session == null
                || !StringUtils.equalsIgnoreCase(session.getState(), "PROTECTION_RESUMING")
                || !StringUtils.equalsIgnoreCase(session.getCommitOutcome(), "ACKNOWLEDGED")
                || !StringUtils.equalsIgnoreCase(session.getEngineAckState(), "ACKNOWLEDGED")
                || !StringUtils.equalsIgnoreCase(session.getTargetPowerState(), "POWERED_OFF")
                || !StringUtils.equalsIgnoreCase(session.getSourcePowerState(), "POWERED_ON")) {
            return false;
        }
        boolean changed = false;
        if (!StringUtils.equals(plan.getState(), DrConstants.PLAN_STATE_SYNCING)) {
            plan.setState(DrConstants.PLAN_STATE_SYNCING);
            changed = true;
        }
        if (!StringUtils.equalsIgnoreCase(plan.getActiveSide(), DrConstants.AUTHORITY_SIDE_SOURCE)) {
            plan.setActiveSide(DrConstants.AUTHORITY_SIDE_SOURCE);
            changed = true;
        }
        if (StringUtils.isNotBlank(plan.getLastErrorCode()) || StringUtils.isNotBlank(plan.getLastErrorMessage())) {
            plan.setLastErrorCode(null);
            plan.setLastErrorMessage(null);
            changed = true;
        }
        if (changed) {
            plan.markUpdated();
            drPlanDao.update(plan.getId(), plan);
        }
        updateReplicaRuntimeProjection(plan, status, runtime, DrConstants.REPLICA_STATE_READY,
                DrConstants.AUTHORITY_SIDE_SOURCE, DrConstants.REPLICA_POWER_STATE_POWERED_OFF);
        return true;
    }

    private void ensurePlannedSourceIsolation(DrPlanVO plan, DrRunVO run,
            DrCutoverSessionVO session, JsonObject runtime) {
        if (plan == null || session == null
                || !StringUtils.equalsIgnoreCase(stringValue(runtime, "failover_mode"), "planned")) {
            return;
        }
        if (StringUtils.equalsAnyIgnoreCase(session.getSourceFenceState(), "ACKNOWLEDGED", "VERIFIED")
                && StringUtils.equalsIgnoreCase(session.getSourcePowerState(), "POWERED_OFF")) {
            return;
        }
        if (StringUtils.isBlank(plan.getSourceExternalRef())) {
            throw new IllegalStateException("Planned failover source VM reference is missing");
        }
        String sourcePowerState;
        if (isRemoteKvmToKvmPlan(plan)) {
            FtctlDrActionAnswer pauseAnswer = drRemoteAgentClient.transitionSourceScheduler(plan,
                    FtctlDrActionCommand.Action.PAUSE_SYNC, run.getUuid());
            if (pauseAnswer == null || !pauseAnswer.getResult()) {
                throw new IllegalStateException(StringUtils.defaultIfBlank(
                        pauseAnswer != null ? pauseAnswer.getDetails() : null,
                        "Remote KVM source scheduler did not quiesce"));
            }
            try {
                sourcePowerState = drRemoteAgentClient.ensureSourceVmPowerState(plan, false);
            } catch (RuntimeException e) {
                resumeRemoteKvmSourceAfterIsolationFailure(plan, run);
                throw e;
            }
        } else {
            DrSiteVO sourceSite = drSiteDao.findById(plan.getSourceSiteId());
            if (sourceSite == null
                    || (!StringUtils.equalsIgnoreCase(sourceSite.getHypervisorType(), DrConstants.HYPERVISOR_TYPE_VMWARE)
                            && !StringUtils.equalsIgnoreCase(sourceSite.getSiteType(), "VMWARE_DIRECT"))) {
                throw new IllegalStateException("Planned VMware failover requires a registered VMware source site");
            }
            try (DrResolvedSiteCredential credential = drSiteCredentialService.resolveCredential(sourceSite)) {
                sourcePowerState = drVmwareInventoryClient.ensureVirtualMachinePowerState(
                        credential, plan.getSourceExternalRef(), false);
            }
        }
        if (!StringUtils.equalsIgnoreCase(sourcePowerState, "POWERED_OFF")) {
            throw new IllegalStateException("Source VM did not reach POWERED_OFF before target promotion");
        }
        session.setSourceFenceState("VERIFIED");
        session.setSourcePowerState("POWERED_OFF");
        session.markUpdated();
        drCutoverSessionDao.update(session.getId(), session);
        runtime.addProperty("source_fence_state", "VERIFIED");
        runtime.addProperty("source_power_state", "POWERED_OFF");
    }

    private void resumeRemoteKvmSourceAfterIsolationFailure(DrPlanVO plan, DrRunVO run) {
        try {
            drRemoteAgentClient.transitionSourceScheduler(plan,
                    FtctlDrActionCommand.Action.RESUME_SYNC, run.getUuid());
        } catch (RuntimeException resumeError) {
            LOGGER.error("Unable to resume remote KVM source scheduler after failed isolation for plan {}",
                    plan.getUuid(), resumeError);
        }
    }

    private boolean isCutoverReadyRuntime(DrPlanVO plan, FtctlDrStatusAnswer status, JsonObject runtime) {
        String state = StringUtils.upperCase(StringUtils.defaultIfBlank(status.getState(), stringValue(runtime, "state")), Locale.ROOT);
        String guestPreparationState = StringUtils.defaultIfBlank(status.getGuestPreparationState(),
                StringUtils.defaultIfBlank(stringValue(runtime, "guest_prep_state"),
                        stringValue(runtime, "guest_preparation_state")));
        String manifestSchema = StringUtils.defaultIfBlank(status.getManifestSchemaVersion(),
                stringValue(runtime, "manifest_schema_version"));
        String manifestSha256 = StringUtils.defaultIfBlank(status.getManifestSha256(),
                stringValue(runtime, "manifest_sha256"));
        Long manifestCheckpoint = status.getGuestPreparationCheckpointSequence() != null
                ? status.getGuestPreparationCheckpointSequence() : longValue(runtime, "guestprep_checkpoint_sequence");
        Long cutoverCheckpoint = longValue(runtime, "failover_restore_point_sequence");
        if (cutoverCheckpoint == null) {
            cutoverCheckpoint = longValue(runtime, "checkpoint_sequence");
        }
        if (cutoverCheckpoint == null) {
            cutoverCheckpoint = status.getLatestCompletedCheckpointSequence() != null
                    ? status.getLatestCompletedCheckpointSequence()
                    : longValue(runtime, "latest_completed_checkpoint_sequence");
        }
        Integer targetDiskCount = firstInteger(status.getTargetDiskCount(), integerValue(runtime, "target_disk_count"));
        boolean vmwareCutoverReady = StringUtils.equalsIgnoreCase(plan.getDirection(), "VMWARE_TO_KVM")
                && StringUtils.equals(state, "CUTOVER_READY")
                && StringUtils.equalsIgnoreCase(guestPreparationState, "READY")
                && StringUtils.equals(manifestSchema, "FTCTL_GUESTPREP_MANIFEST_V2")
                && StringUtils.length(manifestSha256) == 64
                && manifestSha256.matches("[0-9a-fA-F]{64}")
                && manifestCheckpoint != null
                && manifestCheckpoint.equals(cutoverCheckpoint)
                && targetDiskCount != null && targetDiskCount > 0;
        if (vmwareCutoverReady) {
            return true;
        }
        Long checkpointSequence = longValue(runtime, "failover_restore_point_sequence");
        boolean sourceRuntimeQuiesceReady = !requiresPlannedFileRuntimeQuiesce(plan, runtime)
                || ((StringUtils.equalsIgnoreCase(stringValue(runtime, "source_runtime_quiesce_state"), "PAUSED")
                            && StringUtils.equalsIgnoreCase(stringValue(runtime, "source_runtime_quiesce_mode"), "QMP_STOP"))
                        || (StringUtils.equalsIgnoreCase(stringValue(runtime, "source_runtime_quiesce_state"), "OFFLINE")
                            && StringUtils.equalsIgnoreCase(stringValue(runtime, "source_runtime_quiesce_mode"),
                                    "SOURCE_ALREADY_STOPPED")
                            && StringUtils.equalsIgnoreCase(stringValue(runtime, "source_power_state"), "POWERED_OFF")))
                && StringUtils.length(stringValue(runtime, "cutover_source_disk_map_sha256")) == 64
                && stringValue(runtime, "cutover_source_disk_map_sha256").matches("[0-9a-fA-F]{64}");
        return isRemoteKvmToKvmPlan(plan)
                && StringUtils.equals(state, "CUTOVER_READY")
                && checkpointSequence != null && checkpointSequence > 0L
                && StringUtils.length(manifestSha256) == 64
                && manifestSha256.matches("[0-9a-fA-F]{64}")
                && StringUtils.isNotBlank(stringValue(runtime, "target_external_ref"))
                && sourceRuntimeQuiesceReady;
    }

    private boolean requiresPlannedFileRuntimeQuiesce(DrPlanVO plan, JsonObject runtime) {
        return isSharedMountPointFilePlan(plan)
                && StringUtils.equalsIgnoreCase(stringValue(runtime, "failover_mode"), "planned");
    }

    private boolean isSharedMountPointFilePlan(DrPlanVO plan) {
        if (!isRemoteKvmToKvmPlan(plan)) {
            return false;
        }
        JsonObject mapping = parseObject(plan.getMappingJson());
        JsonObject target = objectValue(mapping, "target");
        if (StringUtils.equalsIgnoreCase(stringValue(target, "storagePoolType"), "SharedMountPoint")
                || StringUtils.equalsIgnoreCase(stringValue(target, "poolType"), "SharedMountPoint")) {
            return true;
        }
        JsonElement disksElement = mapping.get("disks");
        if (disksElement == null || !disksElement.isJsonArray()) {
            return false;
        }
        JsonArray disks = disksElement.getAsJsonArray();
        for (JsonElement element : disks) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject diskTarget = objectValue(element.getAsJsonObject(), "target");
            if (StringUtils.equalsIgnoreCase(stringValue(diskTarget, "storagePoolType"), "SharedMountPoint")
                    || StringUtils.equalsIgnoreCase(stringValue(diskTarget, "poolType"), "SharedMountPoint")) {
                return true;
            }
        }
        return false;
    }

    private boolean isRemoteKvmToKvmPlan(DrPlanVO plan) {
        return plan != null && drRemoteAgentClient != null
                && drRemoteAgentClient.isRemoteKvmSource(plan)
                && StringUtils.equalsIgnoreCase(plan.getDirection(), DrConstants.DIRECTION_KVM_TO_KVM);
    }

    private void stopPlanOwnedTargetExportForPromotion(DrPlanVO plan, DrRunVO run,
            DrCutoverSessionVO session) {
        if (!isRemoteKvmToKvmPlan(plan)) {
            return;
        }
        if (session != null && StringUtils.equalsAnyIgnoreCase(session.getCloudPromotionState(),
                "POWER_ON_VALIDATED", "PROMOTED")) {
            return;
        }
        drPlanOwnedTransportService.stopForwardTargetExport(plan, run, null,
                session != null ? session.getCheckpointSequence() : null);
    }

    private void restorePlanOwnedTargetExportAfterAbort(DrPlanVO plan, DrRunVO run) {
        if (!isRemoteKvmToKvmPlan(plan)) {
            return;
        }
        drPlanOwnedTransportService.startForwardTargetExport(plan, run, null);
    }

    private DrCutoverSessionVO upsertCutoverSession(DrPlanVO plan, DrRunVO run, FtctlDrStatusAnswer status, JsonObject runtime) {
        if (run == null || !StringUtils.equalsIgnoreCase(run.getRunType(), DrConstants.RUN_TYPE_FAILOVER)) {
            return null;
        }
        DrCutoverSessionVO session = drCutoverSessionDao.findActiveByRunId(run.getId());
        if (session == null) {
            session = new DrCutoverSessionVO(plan.getId(), run.getId(), DrFailoverExecutionPolicy.mode(run),
                    StringUtils.defaultIfBlank(status.getState(), "PREPARING"));
            session = drCutoverSessionDao.persist(session);
        }
        if (!StringUtils.equalsIgnoreCase(session.getCloudPromotionState(), "PROMOTED")) {
            session.setState(StringUtils.defaultIfBlank(status.getState(), session.getState()));
        }
        Long checkpointSequence = isRemoteKvmToKvmPlan(plan)
                ? longValue(runtime, "failover_restore_point_sequence") : null;
        if (checkpointSequence == null) {
            checkpointSequence = status.getGuestPreparationCheckpointSequence();
        }
        if (checkpointSequence == null) {
            checkpointSequence = longValue(runtime, "guestprep_checkpoint_sequence");
        }
        if (checkpointSequence == null) {
            checkpointSequence = status.getLatestCompletedCheckpointSequence();
        }
        if (checkpointSequence == null) {
            checkpointSequence = longValue(runtime, "failover_restore_point_sequence");
        }
        session.setCheckpointSequence(checkpointSequence);
        session.setGuestOsFamily(status.getGuestFamily());
        session.setGuestPreparationState(status.getGuestPreparationState());
        session.setVirtioState(StringUtils.equalsIgnoreCase(status.getGuestPreparationState(), "READY") ? "READY" : status.getGuestPreparationState());
        session.setSecureBootState(stringValue(runtime, "secure_boot_state"));
        session.setDomainName(status.getTestDomainName());
        session.setBootValidationState(status.getTestDomainState());
        session.setSourceFenceState(stringValue(runtime, "source_fence_state"));
        session.setSourcePowerState(stringValue(runtime, "source_power_state"));
        session.setManifestSchemaVersion(StringUtils.defaultIfBlank(status.getManifestSchemaVersion(),
                stringValue(runtime, "manifest_schema_version")));
        session.setManifestSha256(StringUtils.defaultIfBlank(status.getManifestSha256(),
                stringValue(runtime, "manifest_sha256")));
        session.setTargetDiskCount(firstInteger(status.getTargetDiskCount(), integerValue(runtime, "target_disk_count")));
        session.setSchedulerRecoveryState(stringValue(runtime, "scheduler_recovery_state"));
        session.setCleanupRequired(StringUtils.equalsAnyIgnoreCase(status.getState(), "TESTING", "TEST_RUNNING", "ERROR"));
        session.setDetailsJson(compactRuntimeStatusJson(status.getStatusJson()));
        if (isAcknowledgedTargetCutover(session)) {
            session.setErrorCode(null);
            session.setErrorMessage(null);
        } else {
            session.setErrorCode(status.getErrorCode());
            session.setErrorMessage(StringUtils.isNotBlank(status.getErrorCode())
                    ? projectionFailureMessage(status.getErrorCode(), status, runtime) : null);
        }
        session.markUpdated();
        drCutoverSessionDao.update(session.getId(), session);
        upsertCutoverDisks(plan, session, runtime);
        return session;
    }

    // Replication checkpoint counters are not authority generations (#977).
    static long cutoverGeneration(Long issued, long floor, String sessionId, String observedSession, boolean rejectedStale) {
        if (issued != null && (!rejectedStale || StringUtils.equals(sessionId, observedSession))) {
            return issued;
        }
        return Math.addExact(Math.max(issued != null ? issued : 0L, floor), 1L);
    }

    private boolean commitCloudOwnedCutover(DrPlanVO plan, DrRunVO run, DrCutoverSessionVO session,
            FtctlDrStatusAnswer status, JsonObject runtime, DrTargetPowerOnResult powerOnResult) {
        if (run == null || session == null || powerOnResult == null || !powerOnResult.isReady()) {
            throw new IllegalStateException("Cloud target promotion evidence is incomplete");
        }
        String compactStatusJson = compactRuntimeStatusJson(status.getStatusJson());
        recordRunStep(run, "target-power-on", STEP_ORDER_TARGET_POWER_ON, DrConstants.STEP_STATE_SUCCEEDED,
                100, compactStatusJson, null, null);
        recordRunStep(run, "boot-validation", STEP_ORDER_BOOT_VALIDATION, DrConstants.STEP_STATE_SUCCEEDED,
                100, compactStatusJson, null, null);

        Long previousGeneration = session.getCloudAuthorityGeneration();
        long floor = resolveAuthoritySequenceFloor(plan, longValue(runtime, "cloud_authority_generation"),
                drPlanRuntimeDao != null ? drPlanRuntimeDao.findByPlanId(plan.getId()) : null);
        floor = Math.max(floor, session.getCheckpointSequence() != null ? session.getCheckpointSequence() : run.getId());
        boolean rejectedStale = StringUtils.contains(plan.getLastErrorMessage(), "DR_CUTOVER_GENERATION_STALE");
        long generation = cutoverGeneration(previousGeneration, floor, session.getEngineSessionId(),
                stringValue(runtime, "cloud_cutover_session_id"), rejectedStale);
        if (previousGeneration != null && previousGeneration != generation) {
            session.setCommitAttemptId(null);
        }
        String engineSessionId = StringUtils.defaultIfBlank(session.getEngineSessionId(),
                stringValue(runtime, "failover_session_id"));
        String sourceFenceState = StringUtils.defaultIfBlank(session.getSourceFenceState(),
                stringValue(runtime, "source_fence_state"));
        String sourcePowerState = StringUtils.defaultIfBlank(session.getSourcePowerState(),
                stringValue(runtime, "source_power_state"));
        session = prepareCutoverCommitSession(plan, run, session, powerOnResult, generation,
                engineSessionId, sourceFenceState, sourcePowerState);
        final String commitAttemptId = session.getCommitAttemptId();

        plan.setState(DrConstants.PLAN_STATE_COMMIT_VERIFYING);
        plan.setLastErrorCode(null);
        plan.setLastErrorMessage(null);
        plan.markUpdated();
        drPlanDao.update(plan.getId(), plan);
        runtime.addProperty("state", "CUTOVER_READY");
        runtime.addProperty("target_power_state", powerOnResult.getPowerState());
        runtime.addProperty("target_promotion_state", "AWAITING_ENGINE_ACK");
        runtime.addProperty("boot_validation_state", powerOnResult.getBootValidationState());
        runtime.addProperty("cloud_authority_generation", generation);
        runtime.addProperty("cutover_commit_state", "PREPARED");
        recordRunStep(run, "cloud-promotion", STEP_ORDER_CLOUD_PROMOTION, DrConstants.STEP_STATE_RUNNING,
                90, GSON.toJson(runtime), null, null);

        Answer answer = sendCutoverCommit(plan, run, session, powerOnResult, runtime, generation);
        if (answer == null || !answer.getResult()) {
            String message = answer != null ? answer.getDetails() : "Agent returned no cutover commit acknowledgement";
            DrCutoverSessionVO current = recordCutoverCommitFailure(session, commitAttemptId, message);
            if (current == null) {
                LOGGER.info("Ignored stale cutover commit failure for Plan {} Run {} attempt {}",
                        plan.getUuid(), run.getUuid(), commitAttemptId);
                return false;
            }
            if (isAcknowledgedTargetCutover(current)) {
                session = current;
            } else {
                session = current;
                plan.setState(DrConstants.PLAN_STATE_COMMIT_VERIFYING);
                plan.setLastErrorCode("DR_CUTOVER_COMMIT_FAILED");
                plan.setLastErrorMessage(message);
                plan.markUpdated();
                drPlanDao.update(plan.getId(), plan);
                recordRunStep(run, "engine-state-reconciliation", STEP_ORDER_ENGINE_ACK, DrConstants.STEP_STATE_RUNNING,
                        95, GSON.toJson(runtime), "DR_CUTOVER_COMMIT_FAILED", message);
                return false;
            }
        }

        session = acknowledgeCutoverCommit(session, commitAttemptId);
        if (session == null) {
            LOGGER.info("Ignored stale cutover commit acknowledgement for Plan {} Run {} attempt {}",
                    plan.getUuid(), run.getUuid(), commitAttemptId);
            return false;
        }
        Date now = session.getEngineAckAt() != null ? session.getEngineAckAt() : new Date();
        plan.setState(DrConstants.PLAN_STATE_FAILED_OVER);
        plan.setActiveSide("TARGET");
        plan.setLastErrorCode(null);
        plan.setLastErrorMessage(null);
        plan.markUpdated();
        drPlanDao.update(plan.getId(), plan);
        runtime.addProperty("state", "FAILED_OVER");
        runtime.addProperty("active_side", "TARGET");
        runtime.addProperty("target_promotion_state", "PROMOTED");
        runtime.addProperty("cutover_commit_state", "ACKNOWLEDGED");
        runtime.addProperty("engine_ack_state", "ACKNOWLEDGED");
        runtime.addProperty("engine_ack_at", DateUtil.getDateDisplayString(TimeZone.getTimeZone("GMT"), now));
        updateReplicaRuntimeProjection(plan, status, runtime, DrConstants.REPLICA_STATE_FAILED_OVER,
                "TARGET", powerOnResult.getPowerState());
        recordRunStep(run, "cloud-promotion", STEP_ORDER_CLOUD_PROMOTION, DrConstants.STEP_STATE_SUCCEEDED,
                100, GSON.toJson(runtime), null, null);
        applyFailedOverRuntime(plan, status, generation, now);
        upsertCutoverDisks(plan, session, runtime);
        completeRunFromProjection(plan, run, status);
        return true;
    }

    private DrCutoverSessionVO prepareCutoverCommitSession(DrPlanVO plan, DrRunVO run,
            DrCutoverSessionVO session, DrTargetPowerOnResult powerOnResult, long generation,
            String engineSessionId, String sourceFenceState, String sourcePowerState) {
        if (session.getId() == 0) {
            prepareCutoverCommitSessionFields(plan, run, session, powerOnResult, generation,
                    engineSessionId, sourceFenceState, sourcePowerState);
            drCutoverSessionDao.update(session.getId(), session);
            return session;
        }
        final long sessionId = session.getId();
        return Transaction.execute(new TransactionCallback<DrCutoverSessionVO>() {
            @Override
            public DrCutoverSessionVO doInTransaction(TransactionStatus transactionStatus) {
                DrCutoverSessionVO current = drCutoverSessionDao.lockRow(sessionId, true);
                if (current == null) {
                    throw new CloudRuntimeException("Cutover session disappeared before authority commit");
                }
                if (!isAcknowledgedTargetCutover(current)) {
                    prepareCutoverCommitSessionFields(plan, run, current, powerOnResult, generation,
                            engineSessionId, sourceFenceState, sourcePowerState);
                    drCutoverSessionDao.update(current.getId(), current);
                }
                return current;
            }
        });
    }

    private void prepareCutoverCommitSessionFields(DrPlanVO plan, DrRunVO run, DrCutoverSessionVO session,
            DrTargetPowerOnResult powerOnResult, long generation, String engineSessionId,
            String sourceFenceState, String sourcePowerState) {
        if (session.getCloudAuthorityGeneration() != null && session.getCloudAuthorityGeneration() != generation) {
            session.setCommitAttemptId(null);
        }
        session.setCloudAuthorityGeneration(generation);
        session.setCommitContractVersion(DrCutoverCommitEnvelope.CONTRACT_VERSION);
        session.setEngineSessionId(engineSessionId);
        if (StringUtils.isBlank(session.getCommitAttemptId())) {
            session.setCommitAttemptId(UUID.randomUUID().toString());
        }
        session.setCloudPromotionState("POWER_ON_VALIDATED");
        session.setTargetPowerState(powerOnResult.getPowerState());
        session.setTargetPowerOnAt(powerOnResult.getPowerOnAt());
        session.setBootValidationState(powerOnResult.getBootValidationState());
        session.setBootValidatedAt(powerOnResult.getBootValidatedAt());
        session.setSourceFenceState(sourceFenceState);
        session.setSourcePowerState(sourcePowerState);
        session.setCommitEnvelopeSha256(DrCutoverCommitEnvelope.sha256(plan, run, session,
                engineSessionId, powerOnResult.getTargetVmId(), powerOnResult.getTargetVmUuid(),
                powerOnResult.getPowerState(), powerOnResult.getBootValidationState(),
                sourceFenceState, sourcePowerState));
        session.setCommitState("PREPARED");
        session.setEngineAckState("PENDING");
        session.setState("ENGINE_COMMIT_PENDING");
        session.setErrorCode(null);
        session.setErrorMessage(null);
        session.markUpdated();
    }

    private DrCutoverSessionVO recordCutoverCommitFailure(DrCutoverSessionVO session,
            String expectedAttemptId, String message) {
        if (session.getId() == 0) {
            return applyCutoverCommitFailure(session, expectedAttemptId, message);
        }
        final long sessionId = session.getId();
        return Transaction.execute(new TransactionCallback<DrCutoverSessionVO>() {
            @Override
            public DrCutoverSessionVO doInTransaction(TransactionStatus transactionStatus) {
                DrCutoverSessionVO current = drCutoverSessionDao.lockRow(sessionId, true);
                return applyCutoverCommitFailure(current, expectedAttemptId, message);
            }
        });
    }

    private DrCutoverSessionVO applyCutoverCommitFailure(DrCutoverSessionVO current,
            String expectedAttemptId, String message) {
        if (current == null || isAcknowledgedTargetCutover(current)) {
            return current;
        }
        if (!StringUtils.equals(current.getCommitAttemptId(), expectedAttemptId)) {
            return null;
        }
        current.setEngineAckState("RETRY_REQUIRED");
        current.setCommitState("UNKNOWN");
        current.setErrorCode("DR_CUTOVER_COMMIT_FAILED");
        current.setErrorMessage(message);
        current.markUpdated();
        drCutoverSessionDao.update(current.getId(), current);
        return current;
    }

    private DrCutoverSessionVO acknowledgeCutoverCommit(DrCutoverSessionVO session, String expectedAttemptId) {
        if (session.getId() == 0) {
            return applyCutoverCommitAcknowledgement(session, expectedAttemptId, new Date());
        }
        final long sessionId = session.getId();
        return Transaction.execute(new TransactionCallback<DrCutoverSessionVO>() {
            @Override
            public DrCutoverSessionVO doInTransaction(TransactionStatus transactionStatus) {
                DrCutoverSessionVO current = drCutoverSessionDao.lockRow(sessionId, true);
                return applyCutoverCommitAcknowledgement(current, expectedAttemptId, new Date());
            }
        });
    }

    private DrCutoverSessionVO applyCutoverCommitAcknowledgement(DrCutoverSessionVO current,
            String expectedAttemptId, Date acknowledgedAt) {
        if (current == null || isAcknowledgedTargetCutover(current)) {
            return current;
        }
        if (!StringUtils.equals(current.getCommitAttemptId(), expectedAttemptId)) {
            return null;
        }
        current.setEngineAckState("ACKNOWLEDGED");
        current.setEngineAckAt(acknowledgedAt);
        current.setCommitState("ACKNOWLEDGED");
        current.setCloudPromotionState("PROMOTED");
        current.setState(DrConstants.PLAN_STATE_FAILED_OVER);
        current.setCompletedAt(acknowledgedAt);
        current.setErrorCode(null);
        current.setErrorMessage(null);
        current.markUpdated();
        drCutoverSessionDao.update(current.getId(), current);
        return current;
    }

    private boolean isAcknowledgedTargetCutover(DrCutoverSessionVO session) {
        return session != null
                && StringUtils.equalsIgnoreCase(session.getEngineAckState(), "ACKNOWLEDGED")
                && StringUtils.equalsIgnoreCase(session.getCloudPromotionState(), "PROMOTED")
                && StringUtils.equalsIgnoreCase(session.getState(), DrConstants.PLAN_STATE_FAILED_OVER);
    }

    private void applyFailedOverRuntime(DrPlanVO plan, FtctlDrStatusAnswer status, long authorityGeneration,
            Date committedAt) {
        DrPlanRuntimeVO authority = drPlanRuntimeDao.findByPlanId(plan.getId());
        if (authority == null) {
            authority = new DrPlanRuntimeVO(plan.getId());
        }
        authority.setProtectionState("FAILED_OVER_UNPROTECTED");
        authority.setFreshnessState("WITHIN_RPO");
        authority.setSchedulerState("STOPPED");
        authority.setSchedulerDesiredState("STOPPED");
        authority.setSchedulerHealthState("SUPPRESSED");
        authority.setSchedulerRecoveryState(DrConstants.SCHEDULER_RECOVERY_SUPPRESSED);
        authority.setReplicationActivityState("STOPPED");
        authority.setSchedulerPidAlive(false);
        authority.setOwnerMatched(false);
        authority.setActiveWorkerRunUuid(null);
        authority.setActiveWorkerPid(null);
        authority.setActiveWorkerStartTicks(null);
        authority.setWorkerHeartbeatAt(null);
        authority.setErrorCode(null);
        authority.setErrorMessage(null);
        long authoritySequenceFloor = resolveAuthoritySequenceFloor(plan, authorityGeneration, authority);
        authority.setAuthoritySequence(authoritySequenceFloor);
        authority.setRuntimeGeneration(authoritySequenceFloor);
        authority.setLastStatusAt(committedAt);
        if (plan.getTargetReadyRpoSeconds() != null) {
            authority.setRpoAgeSeconds(Math.max(0L, plan.getTargetReadyRpoSeconds().longValue()));
        }
        authority.setRpoOverdue(false);
        authority.setStatusJson(compactRuntimeStatusJson(status.getStatusJson()));
        authority.markUpdated();
        if (authority.getId() == 0) {
            drPlanRuntimeDao.persist(authority);
        } else {
            drPlanRuntimeDao.update(authority.getId(), authority);
        }
    }

    private Answer sendCutoverCommit(DrPlanVO plan, DrRunVO run, DrCutoverSessionVO session,
            DrTargetPowerOnResult powerOnResult, JsonObject runtime, long generation) {
        Long hostId = resolveCoordinatorHostId(plan);
        if (hostId == null) {
            return null;
        }
        FtctlDrActionCommand command = buildCutoverCommitCommand(plan, run, session, powerOnResult, generation,
                "coordinator");
        if (command == null) {
            return null;
        }
        if (isRemoteKvmToKvmPlan(plan) && !DrFailoverExecutionPolicy.isDisaster(run)) {
            Answer sourceAnswer = drRemoteAgentClient.execute(plan, "ACTION", command,
                    null, FtctlDrActionAnswer.class);
            if (sourceAnswer == null || !sourceAnswer.getResult()) {
                return sourceAnswer;
            }
            FtctlDrActionCommand targetCommand = buildCutoverCommitCommand(plan, run, session,
                    powerOnResult, generation, "target");
            Long targetHostId = drWorkerPlacementService.resolveWorkerHostId(plan, run, DrWorkerRole.TARGET);
            return targetHostId != null
                    ? agentManager.easySend(targetHostId, targetCommand)
                    : new Answer(targetCommand, false, "DR_CUTOVER_TARGET_HOST_MISSING: target worker is not configured");
        }
        if (isRemoteKvmToKvmPlan(plan) && DrFailoverExecutionPolicy.isDisaster(run)) {
            FtctlDrActionCommand targetCommand = buildCutoverCommitCommand(plan, run, session,
                    powerOnResult, generation, "target");
            Long targetHostId = drWorkerPlacementService.resolveWorkerHostId(plan, run, DrWorkerRole.TARGET);
            return targetHostId != null
                    ? agentManager.easySend(targetHostId, targetCommand)
                    : new Answer(targetCommand, false, "DR_CUTOVER_TARGET_HOST_MISSING: target worker is not configured");
        }
        return agentManager.easySend(hostId, command);
    }

    private FtctlDrActionCommand buildCutoverCommitCommand(DrPlanVO plan, DrRunVO run,
            DrCutoverSessionVO session, DrTargetPowerOnResult powerOnResult, long generation,
            String role) {
        FtctlDrActionCommand command = new FtctlDrActionCommand(FtctlDrActionCommand.Action.CUTOVER_COMMIT,
                plan.getUuid(), run.getUuid());
        command.setRole(role);
        command.setWaitForCompletion(true);
        command.setWait(30);
        command.setCutoverCommitContractVersion(session.getCommitContractVersion());
        command.setCutoverEngineSessionId(session.getEngineSessionId());
        command.setCutoverCloudSessionId(session.getUuid());
        command.setCutoverCheckpointSequence(session.getCheckpointSequence());
        command.setCutoverManifestSha256(session.getManifestSha256());
        command.setCutoverAuthorityGeneration(generation);
        command.setAuthoritySequenceFloor(resolveAuthoritySequenceFloor(plan, generation,
                drPlanRuntimeDao.findByPlanId(plan.getId())));
        command.setCutoverCommitAttemptId(session.getCommitAttemptId());
        command.setCutoverCommitEnvelopeSha256(session.getCommitEnvelopeSha256());
        command.setCutoverTargetVmId(powerOnResult.getTargetVmId());
        command.setCutoverTargetExternalRef(powerOnResult.getTargetVmUuid());
        command.setCutoverTargetPowerState(powerOnResult.getPowerState());
        command.setCutoverBootValidationState(powerOnResult.getBootValidationState());
        command.setCutoverSourceFenceState(session.getSourceFenceState());
        command.setCutoverSourcePowerState(session.getSourcePowerState());
        if (StringUtils.isAnyBlank(command.getCutoverEngineSessionId(), command.getCutoverCloudSessionId(),
                command.getCutoverManifestSha256(), command.getCutoverCommitAttemptId(),
                command.getCutoverCommitEnvelopeSha256(), command.getCutoverTargetExternalRef(),
                command.getCutoverSourceFenceState(), command.getCutoverSourcePowerState())) {
            return null;
        }
        return command;
    }

    private void reconcileCloudManagedTestTarget(DrPlanVO plan, DrRunVO run, FtctlDrStatusAnswer status, JsonObject runtime) {
        if (run == null || !StringUtils.equalsIgnoreCase(run.getRunType(), DrConstants.RUN_TYPE_TEST_FAILOVER)) {
            return;
        }
        // A healthy non-owner host can return a status-boundary failure for this Run.
        // That routing observation is not authoritative evidence about the Cloud test session.
        if (!status.getResult() && isStatusBoundaryFailure(status)) {
            return;
        }
        String runtimeState = isRuntimeError(status, runtime) ? DrTestSessionState.FAILED
                : StringUtils.upperCase(StringUtils.defaultIfBlank(status.getState(), stringValue(runtime, "state")), Locale.ROOT);
        DrTestSessionVO session = drTestSessionDao != null ? drTestSessionDao.findActiveByRunId(run.getId()) : null;
        boolean restoredSoftClosedSession = false;
        if (session == null && hasAuthoritativeTestArtifacts(run, status, runtime, runtimeState)) {
            DrTestSessionVO softClosedSession = drTestSessionDao.findByRunIdIncludingRemoved(run.getId());
            if (DrTestSessionState.canRestoreSoftClosedPreMaterializationFailure(softClosedSession)) {
                session = softClosedSession;
                restoredSoftClosedSession = true;
            }
        }
        boolean materializationPending = false;
        if (session != null) {
            boolean recoverablePreMaterializationFailure =
                    StringUtils.equalsAny(runtimeState, "TEST_ARTIFACTS_READY", "ARTIFACTS_READY")
                    && Boolean.TRUE.equals(booleanValue(runtime, "run_exists"))
                    && (restoredSoftClosedSession
                    || DrTestSessionState.canRecoverArtifactFreePreMaterializationFailure(session));
            String projectedState = recoverablePreMaterializationFailure
                    ? DrTestSessionState.ARTIFACTS_READY
                    : DrTestSessionState.projectEngineState(session.getState(), runtimeState);
            if (StringUtils.equals(projectedState, DrTestSessionState.FAILED)) {
                boolean terminalCleanupProof = hasTerminalTestCleanupProof(status, runtime);
                session.setState(projectedState);
                session.setErrorCode(StringUtils.defaultIfBlank(status.getErrorCode(), stringValue(runtime, "error_code")));
                session.setErrorMessage(StringUtils.defaultIfBlank(status.getErrorMessage(), stringValue(runtime, "error_message")));
                session.setCleanupRequired(!terminalCleanupProof);
                if (DrTestSessionState.canSoftCloseFailedSession(session, terminalCleanupProof)) {
                    session.setRemoved(new Date());
                }
            } else if (!StringUtils.equals(session.getState(), projectedState)) {
                session.setState(projectedState);
                session.setCleanupRequired(!hasTerminalTestCleanupProof(status, runtime));
            }
            if (recoverablePreMaterializationFailure) {
                session.setErrorCode(null);
                session.setErrorMessage(null);
            }
            Long checkpointSequence = status.getCurrentCheckpointSequence();
            if (checkpointSequence == null) {
                checkpointSequence = longValue(runtime, "test_restore_point_sequence");
            }
            if (checkpointSequence == null) {
                checkpointSequence = longValue(runtime, "current_checkpoint_sequence");
            }
            session.setCheckpointSequence(checkpointSequence);
            session.setRestorePointRef(StringUtils.defaultIfBlank(stringValue(runtime, "test_restore_point_ref"),
                    status.getLatestCompletedCheckpointRef()));
            session.setDetailsJson(compactRuntimeStatusJson(status.getStatusJson()));
            session.markUpdated();
            if (restoredSoftClosedSession) {
                session.setRemoved(null);
                if (!drTestSessionDao.restoreSoftClosedForMaterialization(session)) {
                    return;
                }
                DrTestSessionVO restoredSession = drTestSessionDao.findActiveByRunId(run.getId());
                if (restoredSession == null) {
                    return;
                }
                session = restoredSession;
            } else if (!StringUtils.equals(session.getState(), DrTestSessionState.CLOUD_VM_VALIDATING)) {
                drTestSessionDao.update(session.getId(), session);
            }
            materializationPending = DrTestSessionState.isMaterializationPending(session.getState());
        }
        if (materializationPending && StringUtils.equalsAny(runtimeState, "TEST_ARTIFACTS_READY", "ARTIFACTS_READY")) {
            drTargetMaterializationService.enqueueTestMaterialization(plan.getId(), run.getId(), status.getStatusJson());
        }
    }

    private boolean hasAuthoritativeTestArtifacts(DrRunVO run, FtctlDrStatusAnswer status, JsonObject runtime,
            String runtimeState) {
        if (run == null || run.getCompleted() != null || !isProjectableRunState(run) || !status.getResult()
                || !StringUtils.equalsAny(runtimeState, "TEST_ARTIFACTS_READY", "ARTIFACTS_READY")
                || !Boolean.TRUE.equals(booleanValue(runtime, "run_exists"))) {
            return false;
        }
        String workerState = StringUtils.defaultIfBlank(status.getWorkerState(), stringValue(runtime, "worker_state"));
        String artifactsState = StringUtils.defaultIfBlank(status.getTestArtifactsState(),
                stringValue(runtime, "test_artifacts_state"));
        Integer artifactCount = status.getTestArtifactCount() != null ? status.getTestArtifactCount()
                : integerValue(runtime, "test_artifact_count");
        return StringUtils.equalsIgnoreCase(workerState, "SUCCEEDED")
                && StringUtils.equalsIgnoreCase(artifactsState, "CREATED")
                && artifactCount != null && artifactCount > 0;
    }

    boolean hasTerminalTestCleanupProof(FtctlDrStatusAnswer status, JsonObject runtime) {
        String sessionState = StringUtils.defaultIfBlank(status.getTestSessionState(),
                stringValue(runtime, "test_session_state"));
        String artifactsState = StringUtils.defaultIfBlank(status.getTestArtifactsState(),
                stringValue(runtime, "test_artifacts_state"));
        String cleanupState = StringUtils.defaultIfBlank(status.getTestCleanupState(),
                stringValue(runtime, "test_cleanup_state"));
        String leaseState = StringUtils.defaultIfBlank(status.getCheckpointLeaseState(),
                stringValue(runtime, "checkpoint_lease_state"));
        return StringUtils.equalsIgnoreCase(sessionState, "CLEANED")
                && StringUtils.equalsIgnoreCase(artifactsState, "CLEANED")
                && StringUtils.equalsIgnoreCase(cleanupState, "CLEANED")
                && StringUtils.equalsIgnoreCase(leaseState, "RELEASED")
                && !Boolean.TRUE.equals(status.getCleanupRequired());
    }

    private boolean isFiniteOperationRun(DrRunVO run) {
        return run != null && StringUtils.equalsAnyIgnoreCase(run.getRunType(),
                DrConstants.RUN_TYPE_TEST_FAILOVER, DrConstants.RUN_TYPE_TEST_CLEANUP);
    }

    private void upsertCutoverDisks(DrPlanVO plan, DrCutoverSessionVO session, JsonObject runtime) {
        List<DrReplicaDiskVO> replicaDisks = new ArrayList<>();
        List<DrReplicaVO> replicas = drReplicaDao.listActiveByPlanId(plan.getId());
        if (replicas != null) {
            for (DrReplicaVO replica : replicas) {
                List<DrReplicaDiskVO> disks = drReplicaDiskDao.listActiveByReplicaId(replica.getId());
                if (disks != null) {
                    replicaDisks.addAll(disks);
                }
            }
        }
        replicaDisks.sort(Comparator.comparingLong(DrReplicaDiskVO::getId));
        if (!replicaDisks.isEmpty()) {
            List<DrCutoverDiskVO> existing = drCutoverDiskDao.listActiveBySessionId(session.getId());
            for (int index = 0; index < replicaDisks.size(); index++) {
                DrReplicaDiskVO replicaDisk = replicaDisks.get(index);
                DrCutoverDiskVO disk = findCutoverDisk(existing, index);
                boolean create = disk == null;
                if (create) {
                    disk = new DrCutoverDiskVO(session.getId(), index);
                }
                String targetRef = replicaDisk.getTargetDiskRef();
                String format = replicaDisk.getFormat();
                VolumeVO targetVolume = replicaDisk.getTargetVolumeId() != null
                        ? volumeDao.findById(replicaDisk.getTargetVolumeId()) : null;
                disk.setProvider(StringUtils.containsIgnoreCase(targetRef, "rbd")
                        || StringUtils.containsIgnoreCase(format, "rbd")
                        || targetVolume != null && StringUtils.containsIgnoreCase(targetVolume.getChainInfo(), "rbd")
                        ? "RBD" : "QCOW2");
                disk.setCheckpointRef(replicaDisk.getSourceDiskRef());
                disk.setWritableRef(targetRef);
                disk.setState("PROMOTED");
                disk.setTargetVolumeId(replicaDisk.getTargetVolumeId());
                disk.setTargetVolumeUuid(targetVolume != null ? targetVolume.getUuid() : null);
                disk.setCheckpointSequence(session.getCheckpointSequence());
                disk.setManifestSha256(session.getManifestSha256());
                disk.setDetailsJson(replicaDisk.getDetailsJson());
                disk.markUpdated();
                if (create) {
                    drCutoverDiskDao.persist(disk);
                } else {
                    drCutoverDiskDao.update(disk.getId(), disk);
                }
            }
            return;
        }
        JsonObject testSession = objectValue(runtime, "test_session");
        JsonObject artifacts = objectValue(testSession, "testArtifacts");
        JsonElement recordsElement = artifacts.get("records");
        if (recordsElement == null || !recordsElement.isJsonArray()) {
            return;
        }
        List<DrCutoverDiskVO> existing = drCutoverDiskDao.listActiveBySessionId(session.getId());
        for (int index = 0; index < recordsElement.getAsJsonArray().size(); index++) {
            JsonElement element = recordsElement.getAsJsonArray().get(index);
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject record = element.getAsJsonObject();
            DrCutoverDiskVO disk = findCutoverDisk(existing, index);
            String type = stringValue(record, "type");
            boolean create = disk == null;
            if (create) {
                disk = new DrCutoverDiskVO(session.getId(), index);
            }
            disk.setProvider(StringUtils.startsWithIgnoreCase(type, "rbd") ? "RBD" : "QCOW2");
            disk.setCheckpointRef(StringUtils.defaultIfBlank(stringValue(record, "backing"), "unknown"));
            disk.setWritableRef(StringUtils.defaultIfBlank(stringValue(record, "path"), stringValue(record, "clone")));
            disk.setRollbackRef(stringValue(record, "snapshot"));
            disk.setState(StringUtils.defaultIfBlank(stringValue(record, "state"), "CREATED"));
            disk.setCheckpointSequence(session.getCheckpointSequence());
            disk.setManifestSha256(session.getManifestSha256());
            disk.setDetailsJson(GSON.toJson(record));
            disk.markUpdated();
            if (create) {
                drCutoverDiskDao.persist(disk);
            } else {
                drCutoverDiskDao.update(disk.getId(), disk);
            }
        }
    }

    private DrCutoverDiskVO findCutoverDisk(List<DrCutoverDiskVO> disks, int diskIndex) {
        for (DrCutoverDiskVO disk : disks) {
            if (disk.getDiskIndex() == diskIndex) {
                return disk;
            }
        }
        return null;
    }

    private boolean isReleasedRuntime(FtctlDrStatusAnswer status, JsonObject runtime) {
        String statusState = status != null ? status.getState() : null;
        String statusStep = status != null ? status.getStep() : null;
        String runtimeState = StringUtils.upperCase(StringUtils.defaultIfBlank(statusState, stringValue(runtime, "state")), Locale.ROOT);
        String step = StringUtils.defaultIfBlank(statusStep, stringValue(runtime, "step"));
        return StringUtils.equals(runtimeState, "RELEASED")
                || StringUtils.equalsIgnoreCase(step, "release-completed");
    }

    private void cleanupReleasedProjection(DrPlanVO plan, FtctlDrStatusAnswer status, JsonObject runtime) {
        if (plan == null) {
            return;
        }
        JsonObject releasedRuntime = normalizeReleasedRuntime(plan, runtime);
        String statusJson = status != null ? status.getStatusJson() : null;
        if (StringUtils.isNotBlank(statusJson)) {
            releasedRuntime = normalizeReleasedRuntime(plan, parseObject(statusJson));
        }
        String runtimeJson = compactRuntimeStatusJson(GSON.toJson(releasedRuntime));
        JsonObject terminalRuntime = releasedRuntime;
        Transaction.execute(new TransactionCallback<Void>() {
            @Override
            public Void doInTransaction(TransactionStatus transactionStatus) {
                cleanupReleasedProjectionTransaction(plan, terminalRuntime, runtimeJson);
                return null;
            }
        });
    }

    private void applyReleasedResourceDisposition(DrPlanVO plan, DrRunVO run) {
        if (plan == null || run == null || drTargetMaterializationService == null
                || !StringUtils.equalsIgnoreCase(run.getRunType(), DrConstants.RUN_TYPE_RELEASE)) {
            return;
        }
        String disposition = DrConstants.RELEASE_DISPOSITION_RETAIN_OPERATIONAL_VM;
        JsonObject request = parseObject(run.getRequestJson());
        if (request.has("resourceDisposition") && !request.get("resourceDisposition").isJsonNull()) {
            disposition = request.get("resourceDisposition").getAsString();
        }
        drTargetMaterializationService.validateReleaseDisposition(plan.getId(), disposition);
        if (!drTargetMaterializationService.cleanupReleasedStandbyTarget(plan.getId(), run.getId(), disposition)) {
            throw new CloudRuntimeException(DrConstants.ERROR_RELEASE_TARGET_NOT_DELETABLE
                    + ": Cloud-managed standby replica cleanup did not complete");
        }
    }

    private JsonObject normalizeReleasedRuntime(DrPlanVO plan, JsonObject runtime) {
        JsonObject released = runtime != null ? runtime.deepCopy() : new JsonObject();
        String activeSide = StringUtils.upperCase(
                StringUtils.defaultIfBlank(stringValue(released, "active_side"), plan.getActiveSide()),
                Locale.ROOT);
        if (!StringUtils.equalsAny(activeSide,
                DrConstants.AUTHORITY_SIDE_SOURCE, DrConstants.AUTHORITY_SIDE_TARGET)) {
            activeSide = DrConstants.AUTHORITY_SIDE_SOURCE;
        }
        released.addProperty("state", "RELEASED");
        released.addProperty("step", "release-completed");
        released.addProperty("active_side", activeSide);
        released.addProperty("protection_state", "UNPROTECTED");
        released.addProperty("scheduler_state", "STOPPED");
        released.addProperty("scheduler_desired_state", "STOPPED");
        released.addProperty("control_state", "STOPPED");
        released.addProperty("cycle_state", "IDLE");
        released.addProperty("replication_activity_state", "IDLE");
        released.addProperty("transfer_activity_state", "IDLE");
        released.addProperty("worker_state", "STOPPED");
        released.addProperty("profile_exists", false);
        released.addProperty("scheduler_pid_alive", false);
        released.addProperty("owned_process_count", 0);
        released.remove("active_worker_run_uuid");
        released.remove("active_worker_pid");
        released.remove("active_worker_start_ticks");
        released.remove("worker_heartbeat_at");
        released.remove("scheduler_next_run_at");
        released.remove("error_code");
        released.remove("error_message");
        return released;
    }

    private void cleanupReleasedProjectionTransaction(DrPlanVO plan, JsonObject runtime, String runtimeJson) {
        if (drTargetResourceOwnershipService != null) {
            drTargetResourceOwnershipService.releasePlanClaims(plan.getId());
        }
        removeActiveReplicas(plan, runtimeJson);
        removeActiveRestorePoints(plan);

        String releasedAuthority = StringUtils.upperCase(
                StringUtils.defaultIfBlank(stringValue(runtime, "active_side"), plan.getActiveSide()),
                Locale.ROOT);
        if (!StringUtils.equalsAny(releasedAuthority,
                DrConstants.AUTHORITY_SIDE_SOURCE, DrConstants.AUTHORITY_SIDE_TARGET)) {
            releasedAuthority = DrConstants.AUTHORITY_SIDE_SOURCE;
        }
        plan.setState(DrConstants.PLAN_STATE_UNPROTECTED);
        plan.setAdminState(DrConstants.ADMIN_STATE_DISABLED);
        plan.setActiveSide(releasedAuthority);
        plan.setTargetReadyAt(null);
        plan.setTargetReadyRpoSeconds(null);
        plan.setLastTargetDurableAt(null);
        plan.setLastErrorCode(null);
        plan.setLastErrorMessage(null);
        plan.markUpdated();
        drPlanDao.update(plan.getId(), plan);

        DrPlanRuntimeVO planRuntime = drPlanRuntimeDao != null
                ? drPlanRuntimeDao.findByPlanId(plan.getId()) : null;
        if (planRuntime != null) {
            planRuntime.setSchedulerState("STOPPED");
            planRuntime.setSchedulerDesiredState("STOPPED");
            planRuntime.setSchedulerUnitActiveState("inactive");
            planRuntime.setSchedulerUnitSubState("dead");
            planRuntime.setSchedulerUnitMainPid(null);
            planRuntime.setSchedulerCgroup(null);
            planRuntime.setSchedulerRecoveryState("NONE");
            planRuntime.setSchedulerRecoveryTrigger(null);
            planRuntime.setSchedulerRecoveryAttempts(0);
            planRuntime.setSchedulerRecoveryErrorCode(null);
            planRuntime.setSchedulerRecoveryErrorMessage(null);
            planRuntime.setSchedulerPidAlive(false);
            planRuntime.setSchedulerSessionUuid(null);
            planRuntime.setSchedulerHealthState("STOPPED");
            planRuntime.setActiveWorkerRunUuid(null);
            planRuntime.setActiveWorkerPid(null);
            planRuntime.setActiveWorkerStartTicks(null);
            planRuntime.setWorkerHeartbeatAt(null);
            planRuntime.setControlRequestRunUuid(null);
            planRuntime.setOwnerMatched(false);
            planRuntime.setWorkerState("STOPPED");
            planRuntime.setWorkerIdentityState(null);
            planRuntime.setWorkerLivenessState("STOPPED");
            planRuntime.setWorkerLaunchNonce(null);
            planRuntime.setWorkerGeneration(null);
            planRuntime.setTransferActivityState("IDLE");
            planRuntime.setTransferProgressSchemaVersion(null);
            planRuntime.setTransferCycleSequence(null);
            planRuntime.setTransferSampleSequence(null);
            planRuntime.setTransferPhase(null);
            planRuntime.setTransferMode(null);
            planRuntime.setTransferBytesTotal(null);
            planRuntime.setTransferBytesProcessed(null);
            planRuntime.setTransferSourceReadBytes(null);
            planRuntime.setTransferTargetWrittenBytes(null);
            planRuntime.setTransferVerifiedBytes(null);
            planRuntime.setTransferPercent(null);
            planRuntime.setTransferThroughputBps(null);
            planRuntime.setTransferEtaSeconds(null);
            planRuntime.setTransferCurrentDiskIndex(null);
            planRuntime.setTransferDiskCount(null);
            planRuntime.setTransferProgressEstimated(null);
            planRuntime.setTransferProgressSampledAt(null);
            planRuntime.setTransferProgressStale(null);
            planRuntime.setOwnedProcessCount(0);
            planRuntime.setRuntimeEndpointsDrained(true);
            planRuntime.setCurrentCycleState("IDLE");
            planRuntime.setCurrentCycleSequence(null);
            planRuntime.setCurrentCycleMode(null);
            planRuntime.setProtectionState("UNPROTECTED");
            planRuntime.setReplicationActivityState("IDLE");
            planRuntime.setFreshnessState("UNKNOWN");
            planRuntime.setSchedulerNextRunAt(null);
            planRuntime.setSchedulerExecutionBudgetSeconds(null);
            planRuntime.setSchedulerCycleWallDurationSeconds(null);
            planRuntime.setRpoAgeSeconds(null);
            planRuntime.setRpoOverdue(false);
            planRuntime.setErrorCode(null);
            planRuntime.setErrorMessage(null);
            planRuntime.setStatusJson(runtimeJson);
            planRuntime.setLastStatusAt(new Date());
            planRuntime.markUpdated();
            drPlanRuntimeDao.update(planRuntime.getId(), planRuntime);
        }
    }

    private void copyTerminalProperty(JsonObject source, JsonObject target, String sourceName, String targetName) {
        if (source == null || target == null || !source.has(sourceName) || source.get(sourceName).isJsonNull()) {
            return;
        }
        target.add(targetName, source.get(sourceName).deepCopy());
    }

    private void removeActiveReplicas(DrPlanVO plan, String detailsJson) {
        if (drReplicaDao == null || drReplicaDiskDao == null) {
            return;
        }
        List<DrReplicaVO> replicas = drReplicaDao.listActiveByPlanId(plan.getId());
        if (replicas == null) {
            return;
        }
        for (DrReplicaVO replica : replicas) {
            if (replica == null || replica.getRemoved() != null) {
                continue;
            }
            List<DrReplicaDiskVO> disks = drReplicaDiskDao.listActiveByReplicaId(replica.getId());
            if (disks != null) {
                for (DrReplicaDiskVO disk : disks) {
                    if (disk == null || disk.getRemoved() != null) {
                        continue;
                    }
                    disk.setDetailsJson(detailsJson);
                    disk.markUpdated();
                    drReplicaDiskDao.update(disk.getId(), disk);
                    drReplicaDiskDao.remove(disk.getId());
                }
            }
            replica.setRuntimeStateJson(detailsJson);
            replica.markUpdated();
            drReplicaDao.update(replica.getId(), replica);
            drReplicaDao.remove(replica.getId());
        }
    }

    private void removeActiveRestorePoints(DrPlanVO plan) {
        if (drRestorePointDao == null) {
            return;
        }
        List<DrRestorePointVO> restorePoints = drRestorePointDao.listActiveByPlanId(plan.getId());
        if (restorePoints == null) {
            return;
        }
        for (DrRestorePointVO restorePoint : restorePoints) {
            if (restorePoint == null || restorePoint.getRemoved() != null) {
                continue;
            }
            drRestorePointDao.remove(restorePoint.getId());
        }
    }

    private boolean preserveTerminalMaterializationFailure(DrPlanVO plan, FtctlDrStatusAnswer status, JsonObject runtime) {
        if (plan == null || drRunDao == null) {
            return false;
        }
        DrRunVO latestRun = drRunDao.findLatestByPlanId(plan.getId());
        if (latestRun == null || latestRun.getCompleted() == null
                || !StringUtils.equals(latestRun.getState(), DrConstants.RUN_STATE_FAILED)
                || !StringUtils.equalsAny(latestRun.getErrorCode(),
                        DrConstants.ERROR_TARGET_VM_MATERIALIZE_FAILED,
                        DrConstants.ERROR_TARGET_OWNERSHIP_CONFLICT)) {
            return false;
        }
        boolean changed = false;
        if (!StringUtils.equals(plan.getState(), DrConstants.PLAN_STATE_ERROR)) {
            plan.setState(DrConstants.PLAN_STATE_ERROR);
            changed = true;
        }
        if (!StringUtils.equals(plan.getLastErrorCode(), latestRun.getErrorCode())) {
            plan.setLastErrorCode(latestRun.getErrorCode());
            changed = true;
        }
        if (!StringUtils.equals(plan.getLastErrorMessage(), latestRun.getErrorMessage())) {
            plan.setLastErrorMessage(latestRun.getErrorMessage());
            changed = true;
        }
        if (changed) {
            plan.markUpdated();
            drPlanDao.update(plan.getId(), plan);
        }
        return true;
    }

    private boolean isCorrelatedRuntime(DrPlanVO plan, DrRunVO run, FtctlDrStatusAnswer status) {
        if (plan == null || status == null || !StringUtils.equals(plan.getUuid(), status.getPlanUuid())) {
            return false;
        }
        if (run == null || StringUtils.isBlank(status.getRunUuid())) {
            return true;
        }
        return StringUtils.equals(run.getUuid(), status.getRunUuid());
    }

    private boolean hardwareContractMatches(DrPlanVO plan, JsonObject runtime) {
        JsonObject mapping = parseObject(plan != null ? plan.getMappingJson() : null);
        JsonObject hardware = firstObject(objectValue(mapping, "source"), "hardware", "sourceHardware");
        JsonObject bootEvidence = objectValue(runtime, "source_boot_hardware");
        if (!bootEvidence.entrySet().isEmpty()) {
            if (!StringUtils.equals("1", stringValue(runtime, "source_boot_hardware_version"))) {
                return false;
            }
            return com.cloud.dr.DrHardwareCompatibilityPolicy.bootSnapshot(hardware).equals(
                    com.cloud.dr.DrHardwareCompatibilityPolicy.bootSnapshot(bootEvidence));
        }
        String expected = stringValue(hardware, "fingerprint");
        String actual = stringValue(runtime, "source_hardware_fingerprint");
        if (StringUtils.isBlank(expected) || StringUtils.isBlank(actual) || StringUtils.equals(expected, actual)) {
            return true;
        }
        if (!hardware.entrySet().isEmpty()
                && StringUtils.equals(DrSourceVmHardware.stableFingerprint(hardware), actual)) {
            return true;
        }
        return false;
    }

    private boolean canRecoverTerminalMaterializationFailure(DrPlanVO plan, FtctlDrStatusAnswer status, JsonObject runtime) {
        if (!isHealthyRuntimeProgress(status, runtime)) {
            return false;
        }
        String runtimeState = StringUtils.upperCase(StringUtils.defaultIfBlank(status.getState(), stringValue(runtime, "state")), Locale.ROOT);
        boolean targetReadyRuntime = StringUtils.equalsAny(runtimeState, "READY", "TARGET_READY")
                || Boolean.TRUE.equals(status != null ? status.getTargetMaterialized() : null)
                || Boolean.TRUE.equals(booleanValue(runtime, "target_materialized"));
        if (!targetReadyRuntime || !hasDurableCheckpoint(status, runtime)) {
            return false;
        }
        if (isExplicitFalse(status != null ? status.getTargetVmPresent() : null, booleanValue(runtime, "target_vm_present"))
                || isExplicitFalse(status != null ? status.getTargetStoragePresent() : null, booleanValue(runtime, "target_storage_present"))
                || isExplicitFalse(status != null ? status.getTargetNetworkPresent() : null, booleanValue(runtime, "target_network_present"))
                || isExplicitFalse(status != null ? status.getRestorePointPresent() : null, booleanValue(runtime, "restore_point_present"))) {
            return false;
        }
        return hasTargetReferenceForDirection(plan);
    }

    private void recoverTerminalMaterializationFailure(DrPlanVO plan, DrRunVO latestRun, FtctlDrStatusAnswer status, JsonObject runtime) {
        String compactStatusJson = compactRuntimeStatusJson(status != null ? status.getStatusJson() : GSON.toJson(runtime));
        recordRunProjectionStep(latestRun, DrConstants.STEP_STATE_SUCCEEDED, 100, compactStatusJson, null, null);
        latestRun.setState(DrConstants.RUN_STATE_SUCCEEDED);
        latestRun.setCompleted(new Date());
        latestRun.setCurrentStepName("runtime-projection");
        latestRun.setProjectionState("recovered");
        latestRun.setProjectionChecked(new Date());
        latestRun.setRetryable(false);
        latestRun.setRetryAfterSeconds(null);
        latestRun.setNextRetryAt(null);
        latestRun.setLastStatusJson(compactStatusJson);
        latestRun.setErrorCode(null);
        latestRun.setErrorMessage(null);
        latestRun.markUpdated();
        drRunDao.update(latestRun.getId(), latestRun);

        plan.setState(DrConstants.PLAN_STATE_READY);
        plan.setLastErrorCode(null);
        plan.setLastErrorMessage(null);
        plan.markUpdated();
        drPlanDao.update(plan.getId(), plan);
        updateReplicaRuntimeProjection(plan, status, runtime, DrConstants.REPLICA_STATE_READY, "SOURCE",
                StringUtils.defaultIfBlank(stringValue(runtime, "target_power_state"), DrConstants.REPLICA_POWER_STATE_POWERED_OFF));
        markReplicaDisksReady(plan, compactStatusJson);
        persistRunProjectionEvent(plan, latestRun, DrConstants.EVENT_TARGET_MATERIALIZATION_RECOVERED, DrConstants.EVENT_SEVERITY_INFO,
                "FTCTL_DR target materialization failure was recovered from converged Cloud and runtime state", compactStatusJson);
    }

    private void markReplicaDisksReady(DrPlanVO plan, String detailsJson) {
        if (plan == null || drReplicaDao == null || drReplicaDiskDao == null) {
            return;
        }
        List<DrReplicaVO> replicas = drReplicaDao.listActiveByPlanId(plan.getId());
        if (replicas == null) {
            return;
        }
        for (DrReplicaVO replica : replicas) {
            if (replica == null || replica.getRemoved() != null) {
                continue;
            }
            List<DrReplicaDiskVO> disks = drReplicaDiskDao.listActiveByReplicaId(replica.getId());
            if (disks == null) {
                continue;
            }
            for (DrReplicaDiskVO disk : disks) {
                if (disk == null || disk.getRemoved() != null) {
                    continue;
                }
                disk.setState(DrConstants.REPLICA_STATE_READY);
                disk.setDetailsJson(detailsJson);
                disk.markUpdated();
                drReplicaDiskDao.update(disk.getId(), disk);
            }
        }
    }

    private boolean isHealthyRuntimeProgress(FtctlDrStatusAnswer status, JsonObject runtime) {
        String runtimeState = StringUtils.upperCase(StringUtils.defaultIfBlank(status.getState(), stringValue(runtime, "state")), Locale.ROOT);
        String workerState = StringUtils.upperCase(stringValue(runtime, "worker_state"), Locale.ROOT);
        String errorCode = StringUtils.defaultIfBlank(status.getErrorCode(), stringValue(runtime, "error_code"));
        return status.getResult()
                && StringUtils.isBlank(errorCode)
                && !StringUtils.equals(workerState, "FAILED")
                && StringUtils.equalsAny(runtimeState, "RUNNING", "SYNCING", "SEEDING", "READY", "TARGET_READY");
    }

    private void updateReplicaRuntimeProjection(DrPlanVO plan, FtctlDrStatusAnswer status, JsonObject runtime,
            String replicaState, String activeSide, String powerState) {
        List<DrReplicaVO> replicas = drReplicaDao.listActiveByPlanId(plan.getId());
        if (replicas == null || replicas.isEmpty()) {
            return;
        }
        String runtimeJson = compactRuntimeStatusJson(StringUtils.defaultIfBlank(status.getStatusJson(), GSON.toJson(runtime)));
        for (DrReplicaVO replica : replicas) {
            replica.setState(replicaState);
            replica.setActiveSide(activeSide);
            replica.setPowerState(powerState);
            replica.setRuntimeStateJson(runtimeJson);
            replica.markUpdated();
            drReplicaDao.update(replica.getId(), replica);
        }
    }

    private boolean isFailedOverRuntime(String planState, JsonObject runtime) {
        return StringUtils.equals(planState, DrConstants.PLAN_STATE_FAILED_OVER)
                || StringUtils.equalsIgnoreCase(stringValue(runtime, "state"), "FAILED_OVER")
                || StringUtils.equalsIgnoreCase(stringValue(runtime, "state"), "PROMOTED");
    }

    private boolean isFailbackRestoredRuntime(String planState, JsonObject runtime, DrFailbackSessionVO session) {
        return StringUtils.equals(planState, DrConstants.PLAN_STATE_READY)
                && StringUtils.equalsIgnoreCase(stringValue(runtime, "active_side"), "SOURCE")
                && session != null
                && StringUtils.equalsIgnoreCase(session.getState(), "COMPLETED")
                && StringUtils.equalsIgnoreCase(session.getEngineAckState(), "ACKNOWLEDGED")
                && session.getPostFailbackCheckpointSequence() != null;
    }

    private boolean isReprotectedRuntime(String planState, JsonObject runtime) {
        return StringUtils.equalsAny(planState, DrConstants.PLAN_STATE_READY, DrConstants.HEALTH_DEGRADED)
                && StringUtils.equalsIgnoreCase(stringValue(runtime, "active_side"), "TARGET")
                && (StringUtils.isNotBlank(stringValue(runtime, "reprotect_session_id"))
                || StringUtils.isNotBlank(stringValue(runtime, "reprotect_completed_at")));
    }

    private boolean isDurableReprotectedRuntime(FtctlDrStatusAnswer status, JsonObject runtime) {
        if (status == null || runtime == null || !status.getResult()) {
            return false;
        }
        String errorCode = StringUtils.defaultIfBlank(status.getErrorCode(), stringValue(runtime, "error_code"));
        Long checkpointSequence = longValue(runtime, "checkpoint_sequence");
        if (checkpointSequence == null) {
            checkpointSequence = status.getLatestCompletedCheckpointSequence();
        }
        if (checkpointSequence == null) {
            checkpointSequence = longValue(runtime, "latest_completed_checkpoint_sequence");
        }
        String baselineState = StringUtils.defaultIfBlank(status.getBaselineState(),
                stringValue(runtime, "baseline_state"));
        return StringUtils.isBlank(errorCode)
                && StringUtils.equalsIgnoreCase(stringValue(runtime, "action"), "dr-reprotect")
                && StringUtils.equalsIgnoreCase(StringUtils.defaultIfBlank(status.getState(),
                        stringValue(runtime, "state")), DrConstants.PLAN_STATE_READY)
                && StringUtils.equalsIgnoreCase(StringUtils.defaultIfBlank(status.getStep(),
                        stringValue(runtime, "step")), "reprotect-ready")
                && StringUtils.equalsIgnoreCase(StringUtils.defaultIfBlank(status.getProtectionState(),
                        stringValue(runtime, "protection_state")), DrConstants.PLAN_STATE_READY)
                && StringUtils.equalsIgnoreCase(stringValue(runtime, "active_side"), "TARGET")
                && StringUtils.equalsAnyIgnoreCase(baselineState, "LOCAL_DURABLE", "DURABLE", "COMMITTED")
                && StringUtils.isNotBlank(stringValue(runtime, "reprotect_session_id"))
                && StringUtils.isNotBlank(stringValue(runtime, "reprotect_restore_point_ref"))
                && checkpointSequence != null && checkpointSequence > 0L;
    }

    private boolean isTargetProtectedRuntime(FtctlDrStatusAnswer status, JsonObject runtime) {
        if (isDurableReprotectedRuntime(status, runtime)) {
            return true;
        }
        if (status == null || runtime == null || !status.getResult()) {
            return false;
        }
        String errorCode = StringUtils.defaultIfBlank(status.getErrorCode(), stringValue(runtime, "error_code"));
        String schedulerState = stringValue(runtime, "scheduler_state");
        String schedulerHealth = StringUtils.defaultIfBlank(status.getSchedulerHealth(),
                stringValue(runtime, "scheduler_health"));
        String protectionState = StringUtils.defaultIfBlank(status.getProtectionState(),
                stringValue(runtime, "protection_state"));
        Boolean pidAlive = status.getSchedulerPidAlive() != null ? status.getSchedulerPidAlive()
                : booleanValue(runtime, "scheduler_pid_alive");
        Boolean ownerMatched = status.getOwnerMatched() != null ? status.getOwnerMatched()
                : booleanValue(runtime, "owner_matched");
        Long checkpointSequence = status.getLatestCompletedCheckpointSequence() != null
                ? status.getLatestCompletedCheckpointSequence()
                : longValue(runtime, "latest_completed_checkpoint_sequence");
        String baselineState = StringUtils.defaultIfBlank(status.getBaselineState(),
                stringValue(runtime, "baseline_state"));
        return StringUtils.isBlank(errorCode)
                && StringUtils.equalsIgnoreCase(stringValue(runtime, "active_side"), "TARGET")
                && StringUtils.equalsIgnoreCase(protectionState, DrConstants.PLAN_STATE_READY)
                && StringUtils.equalsIgnoreCase(schedulerState, "RUNNING")
                && StringUtils.equalsIgnoreCase(schedulerHealth, "HEALTHY")
                && Boolean.TRUE.equals(pidAlive)
                && Boolean.TRUE.equals(ownerMatched)
                && StringUtils.equalsAnyIgnoreCase(baselineState, "LOCAL_DURABLE", "DURABLE", "COMMITTED")
                && checkpointSequence != null && checkpointSequence > 0L;
    }

    private void reconcileAcceptedRunFromStatus(DrPlanVO plan, FtctlDrStatusAnswer status, JsonObject runtime) {
        DrRunVO run = resolveProjectionRun(plan);
        if (run == null) {
            return;
        }
        boolean lateFullSeedRecovered = reopenFailedFullReseedRunIfDurablyRecovered(plan, run, status, runtime);
        if (!lateFullSeedRecovered && !isProjectableRunState(run)) {
            // A failed failback run remains immutable history. Its later rollback
            // acknowledgement still owns the current plan authority projection.
            if (isRolledBackFailback(plan, run, runtime)) {
                preserveFailedOverTargetAuthority(plan);
            }
            return;
        }
        if (StringUtils.equalsIgnoreCase(run.getRunType(), DrConstants.RUN_TYPE_FAILBACK)
                && !runtimeBelongsToRun(runtime, run)) {
            return;
        }
        if (run.getCompleted() != null) {
            // A concurrent terminal projection may be followed by a stale Agent
            // acceptance write. Keep the Run projectable and repair the mixed
            // ACCEPTED + completed state from canonical runtime evidence.
            run.setCompleted(null);
        }
        bindAcceptedCycleFromControlRequest(plan, run, status, runtime);
        bindAcceptedCycleFromLateTerminalCheckpoint(plan, run, status, runtime);
        if (StringUtils.equalsIgnoreCase(run.getRunType(), DrConstants.RUN_TYPE_TEST_FAILOVER)
                && drTargetMaterializationService.isTestTargetActive(run.getId())) {
            completeRunFromProjection(plan, run, status);
            return;
        }
        if (isFailbackLifecyclePending(run, status, runtime)) {
            markFailbackLifecyclePending(run, status, runtime);
            return;
        }
        if (StringUtils.equalsIgnoreCase(run.getRunType(), DrConstants.RUN_TYPE_FAILBACK)) {
            DrFailbackSessionVO failbackSession = drFailbackSessionDao.findActiveByRunId(run.getId());
            if (failbackSession != null && StringUtils.equalsAnyIgnoreCase(failbackSession.getState(),
                    "COMMIT_VERIFYING", "PROTECTION_RESUMING", "ROLLBACK_FENCING",
                    "ROLLBACK_POWER_RESTORING")) {
                return;
            }
        }
        if (deferForRuntimeReconciliation(plan, run, status, runtime)) {
            return;
        }
        if (isRetryableWorkerCondition(status, runtime)) {
            failRetryableRunFromProjection(plan, run, status, runtime);
            return;
        }
        if (isWorkerFailed(runtime)) {
            failRunFromProjection(plan, run, status, runtime);
            return;
        }
        if (isWorkerStartStalled(run, runtime)) {
            failRetryableRunFromProjection(plan, run, status, runtime);
            return;
        }
        if (isRuntimeError(status, runtime)) {
            failRunFromProjection(plan, run, status, runtime);
            return;
        }
        if (isRunSatisfiedByRuntime(plan, run, status, runtime)) {
            if (StringUtils.equalsIgnoreCase(run.getRunType(), DrConstants.RUN_TYPE_TEST_CLEANUP)) {
                DrTestSessionVO cleanedSession = drTestSessionDao.findActiveByPlanId(plan.getId());
                if (testCleanupRecovery != null && cleanedSession != null
                        && testCleanupRecovery.find(cleanedSession.getRunId()) != null) {
                    testCleanupRecovery.arm(plan.getId(), cleanedSession.getRunId(), run.getId());
                }
                drTargetMaterializationService.completeTestCleanup(plan.getId());
            }
            completeRunFromProjection(plan, run, status);
            return;
        }
        if (StringUtils.equalsIgnoreCase(run.getRunType(), DrConstants.RUN_TYPE_SYNC)) {
            markSyncTargetPending(plan, run, status, runtime);
        }
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

    private boolean reconcileCanceledFailoverPreparation(DrPlanVO plan, DrRunVO run, JsonObject runtime) {
        DrCutoverSessionVO session = run != null ? drCutoverSessionDao.findActiveByRunId(run.getId()) : null;
        boolean compensationPending = session != null
                && StringUtils.equalsAnyIgnoreCase(session.getState(), "CUTOVER_READY", "ABORTING", "ABORT_FAILED");
        String runtimeActiveSide = stringValue(runtime, "active_side");
        boolean sourceAuthorityOwned = StringUtils.equalsIgnoreCase(runtimeActiveSide,
                DrConstants.AUTHORITY_SIDE_SOURCE)
                || (compensationPending && StringUtils.isBlank(runtimeActiveSide)
                        && plan != null && StringUtils.equalsIgnoreCase(plan.getActiveSide(),
                                DrConstants.AUTHORITY_SIDE_SOURCE));
        if (plan == null || run == null || runtime == null
                || !StringUtils.equalsIgnoreCase(run.getRunType(), DrConstants.RUN_TYPE_FAILOVER)
                || !StringUtils.equalsIgnoreCase(run.getState(), DrConstants.RUN_STATE_CANCELED)
                || (!StringUtils.equalsAnyIgnoreCase(stringValue(runtime, "state"), "CUTOVER_READY", "READY")
                        && !compensationPending)
                || !sourceAuthorityOwned) {
            return false;
        }
        abortFailedFailoverPreparation(plan, run, runtime,
                "DR_FAILOVER_CANCELED",
                "Canceled failover preparation was reconciled before target promotion");
        // Cancellation is terminal operator intent. Even when compensation is
        // retryable, the canceled Run must never fall through into promotion.
        return true;
    }

    private DrRunVO resolveCanceledFailoverReconciliationRun(DrPlanVO plan, DrRunVO projectionRun) {
        if (projectionRun != null
                && StringUtils.equalsIgnoreCase(projectionRun.getRunType(), DrConstants.RUN_TYPE_FAILOVER)
                && StringUtils.equalsIgnoreCase(projectionRun.getState(), DrConstants.RUN_STATE_CANCELED)) {
            return projectionRun;
        }
        if (plan != null && drCutoverSessionDao != null) {
            DrCutoverSessionVO activeSession = drCutoverSessionDao.findLatestActiveByPlanId(plan.getId());
            DrRunVO sessionRun = activeSession != null && drRunDao != null
                    ? drRunDao.findById(activeSession.getRunId()) : null;
            if (sessionRun != null
                    && StringUtils.equalsIgnoreCase(sessionRun.getRunType(), DrConstants.RUN_TYPE_FAILOVER)
                    && StringUtils.equalsIgnoreCase(sessionRun.getState(), DrConstants.RUN_STATE_CANCELED)) {
                return sessionRun;
            }
        }
        if (plan == null || drRunDao == null
                || !StringUtils.equalsIgnoreCase(plan.getActiveSide(), DrConstants.AUTHORITY_SIDE_TARGET)
                || !StringUtils.equalsAnyIgnoreCase(plan.getState(), DrConstants.PLAN_STATE_FAILED_OVER,
                        DrConstants.PLAN_STATE_COMMIT_VERIFYING, DrConstants.PLAN_STATE_READY)) {
            return projectionRun;
        }
        List<DrRunVO> runs = drRunDao.listByPlanId(plan.getId());
        if (runs == null || runs.isEmpty()) {
            return null;
        }
        return runs.stream()
                .filter(run -> StringUtils.equalsIgnoreCase(run.getRunType(), DrConstants.RUN_TYPE_FAILOVER))
                .findFirst()
                .filter(run -> StringUtils.equalsIgnoreCase(run.getState(), DrConstants.RUN_STATE_CANCELED))
                .orElse(null);
    }

    void bindAcceptedCycleFromControlRequest(DrPlanVO plan, DrRunVO run,
            FtctlDrStatusAnswer status, JsonObject runtime) {
        if (plan == null || run == null || status == null || run.getAcceptedCycleSequence() != null
                || !isFullReseedRun(run)) {
            return;
        }
        String controlRequestRunUuid = StringUtils.defaultIfBlank(status.getControlRequestRunUuid(),
                stringValue(runtime, "control_request_run_uuid"));
        if (!StringUtils.equals(run.getUuid(), controlRequestRunUuid)) {
            return;
        }
        Long sequence = status.getTransferCycleSequence() != null ? status.getTransferCycleSequence()
                : longValue(runtime, "transfer_cycle_sequence");
        String mode = StringUtils.defaultIfBlank(status.getTransferMode(), stringValue(runtime, "transfer_mode"));
        if (sequence == null || !isFullSeedMode(mode)) {
            return;
        }
        String expectedToken = plan.getUuid() + ":" + sequence;
        DrSyncCycleVO cycle = resolveCycleForProjection(plan, status,
                schemaSafeRuntimeRunUuid(controlRequestRunUuid), sequence, expectedToken);
        if (cycle.getCompleted() == null || !isFullSeedMode(cycle.getRequestedMode())
                || StringUtils.isBlank(cycle.getCycleToken())) {
            return;
        }
        run.setAcceptedCycleSequence(cycle.getSequence());
        run.setAcceptedCycleToken(cycle.getCycleToken());
        run.markUpdated();
        drRunDao.update(run.getId(), run);
    }

    void bindAcceptedCycleFromLateTerminalCheckpoint(DrPlanVO plan, DrRunVO run,
            FtctlDrStatusAnswer status, JsonObject runtime) {
        if (plan == null || run == null || status == null || runtime == null
                || run.getAcceptedCycleSequence() != null || !isFullReseedRun(run)) {
            return;
        }
        String controlRequestRunUuid = StringUtils.defaultIfBlank(status.getControlRequestRunUuid(),
                stringValue(runtime, "control_request_run_uuid"));
        String terminalSource = StringUtils.defaultIfBlank(status.getTerminalSource(),
                stringValue(runtime, "terminal_source"));
        boolean terminalAuthoritative = Boolean.TRUE.equals(status.getTerminalAuthoritative())
                || Boolean.TRUE.equals(booleanValue(runtime, "terminal_authoritative"))
                || StringUtils.equalsIgnoreCase(terminalSource, "ENGINE_TERMINAL");
        String workerState = StringUtils.defaultIfBlank(status.getWorkerState(), stringValue(runtime, "worker_state"));
        Integer workerExitCode = status.getWorkerExitCode() != null ? status.getWorkerExitCode()
                : integerValue(runtime, "worker_exit_code");
        boolean terminalPublished = StringUtils.equalsAnyIgnoreCase(workerState, "TERMINAL_PUBLISHED", "SUCCEEDED")
                && Integer.valueOf(0).equals(workerExitCode);
        String runtimeState = StringUtils.defaultIfBlank(status.getState(), stringValue(runtime, "state"));
        String runtimeStep = StringUtils.defaultIfBlank(status.getStep(), stringValue(runtime, "step"));
        Long sequence = longValue(runtime, "checkpoint_sequence");
        if (!StringUtils.equals(run.getUuid(), controlRequestRunUuid)
                || (!terminalAuthoritative && !terminalPublished)
                || !StringUtils.equalsIgnoreCase(runtimeState, DrConstants.PLAN_STATE_READY)
                || !StringUtils.equalsIgnoreCase(runtimeStep, "full-resync-completed")
                || sequence == null) {
            return;
        }
        String artifactMarker = run.getUuid() + "-cycle-" + sequence + "-";
        String manifestPath = stringValue(runtime, "manifest_path");
        String checkpointPath = stringValue(runtime, "checkpoint_path");
        if (!StringUtils.contains(manifestPath, artifactMarker)
                || !StringUtils.contains(checkpointPath, artifactMarker)) {
            return;
        }
        String expectedToken = plan.getUuid() + ":" + sequence;
        DrSyncCycleVO cycle = resolveCycleForProjection(plan, status,
                schemaSafeRuntimeRunUuid(controlRequestRunUuid), sequence, expectedToken);
        if (cycle.getCompleted() == null) {
            Date sourceAt = firstDate(parseDate(status.getLatestCompletedSourceCheckpointAt()),
                    parseDate(stringValue(runtime, "latest_completed_source_checkpoint_at")),
                    plan.getLastSourceCheckpointAt(), run.getStarted());
            Date durableAt = firstDate(parseDate(status.getLatestCompletedTargetDurableAt()),
                    parseDate(stringValue(runtime, "latest_completed_target_durable_at")),
                    plan.getLastTargetDurableAt());
            if (durableAt == null || run.getStarted() != null && durableAt.before(run.getStarted())) {
                return;
            }
            cycle.setRunId(run.getId());
            cycle.setSchedulerSessionUuid(StringUtils.defaultIfBlank(status.getSchedulerSessionUuid(),
                    stringValue(runtime, "scheduler_session_uuid")));
            cycle.setSchedulerLeaseEpoch(status.getSchedulerLeaseEpoch() != null ? status.getSchedulerLeaseEpoch()
                    : longValue(runtime, "scheduler_lease_epoch"));
            cycle.setAuthoritySequence(status.getAuthoritySequence() != null ? status.getAuthoritySequence()
                    : longValue(runtime, "authority_sequence"));
            cycle.setCycleToken(expectedToken);
            cycle.setRequestedMode("FULL_SEED");
            cycle.setEffectiveMode("FULL_SEED");
            cycle.setState("READY");
            cycle.setCommitState("LOCAL_DURABLE");
            cycle.setBaselineGeneration(sequence);
            cycle.setBaselineState("LOCAL_DURABLE");
            cycle.setReseedReason("LEGACY_SEQUENCE_COLLISION_RECOVERY");
            cycle.setAutomaticReseed(false);
            cycle.setModeDecisionCode("FULL_RESEED_CONTROL_TERMINAL");
            cycle.setIncrementalVerified(false);
            cycle.setNbdTeardownState(StringUtils.defaultIfBlank(status.getNbdTeardownState(),
                    stringValue(runtime, "nbd_teardown_state")));
            cycle.setSourceCheckpointAt(sourceAt);
            cycle.setTargetDurableAt(durableAt);
            cycle.setStarted(sourceAt != null ? sourceAt : run.getStarted());
            cycle.setCompleted(durableAt);
            cycle.setErrorCode(null);
            cycle.setErrorMessage(null);
            persistSyncCycle(cycle);
        }
        if (cycle == null || cycle.getCompleted() == null
                || !isFullSeedMode(cycle.getRequestedMode())
                || !StringUtils.equalsAnyIgnoreCase(cycle.getState(), "READY", "COMPLETED", "TARGET_READY")
                || !StringUtils.equalsAnyIgnoreCase(cycle.getCommitState(), "LOCAL_DURABLE", "COMMITTED", "DURABLE")
                || StringUtils.isBlank(cycle.getCycleToken())) {
            return;
        }
        run.setAcceptedCycleSequence(cycle.getSequence());
        run.setAcceptedCycleToken(cycle.getCycleToken());
        run.markUpdated();
        drRunDao.update(run.getId(), run);
    }

    private boolean deferForRuntimeReconciliation(DrPlanVO plan, DrRunVO run,
            FtctlDrStatusAnswer status, JsonObject runtime) {
        boolean terminalAuthoritative = Boolean.TRUE.equals(status.getTerminalAuthoritative())
                || Boolean.TRUE.equals(booleanValue(runtime, "terminal_authoritative"))
                || StringUtils.equalsIgnoreCase(StringUtils.defaultIfBlank(status.getTerminalSource(),
                        stringValue(runtime, "terminal_source")), "ENGINE_TERMINAL");
        if (terminalAuthoritative) {
            return false;
        }
        String workerLiveness = StringUtils.defaultIfBlank(status.getWorkerLivenessState(),
                stringValue(runtime, "worker_liveness_state"));
        String workerIdentity = StringUtils.defaultIfBlank(status.getWorkerIdentityState(),
                stringValue(runtime, "worker_identity_state"));
        String transferActivity = StringUtils.defaultIfBlank(status.getTransferActivityState(),
                stringValue(runtime, "transfer_activity_state"));
        Integer ownedProcesses = status.getOwnedProcessCount() != null ? status.getOwnedProcessCount()
                : integerValue(runtime, "owned_process_count");
        boolean live = StringUtils.equalsAnyIgnoreCase(workerLiveness, "ALIVE", "MATCHED")
                || StringUtils.equalsAnyIgnoreCase(transferActivity, "COPYING", "VERIFYING")
                || ownedProcesses != null && ownedProcesses > 0;
        boolean reconciliationRequired = Boolean.TRUE.equals(status.getReconciliationRequired())
                || Boolean.TRUE.equals(booleanValue(runtime, "reconciliation_required"))
                || StringUtils.equalsAnyIgnoreCase(workerIdentity, "CONFLICT", "MISMATCH", "UNVERIFIED");
        boolean deadConfirmed = StringUtils.equalsIgnoreCase(workerLiveness, "DEAD_CONFIRMED");
        boolean endpointsDrained = Boolean.TRUE.equals(status.getRuntimeEndpointsDrained())
                || Boolean.TRUE.equals(booleanValue(runtime, "runtime_endpoints_drained"));
        DrPlanRuntimeVO authority = drPlanRuntimeDao.findByPlanId(plan.getId());
        int confirmationCount = authority != null ? authority.getReconciliationChecks() : 0;
        if (!live && deadConfirmed && endpointsDrained && confirmationCount >= 2) {
            return false;
        }
        if (!live && !reconciliationRequired && !deadConfirmed) {
            return false;
        }
        String compactStatusJson = compactRuntimeStatusJson(status.getStatusJson());
        run.setState(DrConstants.RUN_STATE_RUNNING);
        run.setCurrentStepName(live ? "runtime-transfer" : "runtime-reconciliation");
        run.setProjectionState(live ? "running" : "reconciling");
        run.setProjectionChecked(new Date());
        run.setRetryable(true);
        run.setRetryAfterSeconds(2);
        run.setNextRetryAt(new Date(System.currentTimeMillis() + 2000L));
        run.setLastStatusJson(compactStatusJson);
        run.setErrorCode(null);
        run.setErrorMessage(null);
        run.markUpdated();
        drRunDao.update(run.getId(), run);
        return true;
    }

    private boolean isProjectableRunState(DrRunVO run) {
        return StringUtils.equalsAny(run.getState(),
                DrConstants.RUN_STATE_ACCEPTED, DrConstants.RUN_STATE_RUNNING, DrConstants.RUN_STATE_RETRYING);
    }

    private boolean isFullSeedMode(String mode) {
        String normalized = StringUtils.upperCase(StringUtils.trimToEmpty(mode), Locale.ROOT)
                .replace('-', '_');
        return StringUtils.equalsAny(normalized, "FULL_SEED", "FULL_RESEED");
    }

    private DrRunVO resolveProjectionRun(DrPlanVO plan) {
        if (plan == null || drRunDao == null) {
            return null;
        }
        DrRunVO activeRun = drRunDao.findActiveByPlanId(plan.getId());
        if (activeRun != null) {
            return activeRun;
        }
        return drRunDao.findLatestByPlanId(plan.getId());
    }

    DrRunVO resolveRefreshProjectionRun(DrPlanVO plan) {
        if (plan == null || drRunDao == null) {
            return null;
        }
        DrRunVO activeRun = drRunDao.findActiveByPlanId(plan.getId());
        if (activeRun != null) {
            return activeRun;
        }
        DrRunVO latestRun = drRunDao.findLatestByPlanId(plan.getId());
        if (latestRun != null && isFailedFullReseedRunWithNewerDurableCycle(plan, latestRun)) {
            return latestRun;
        }
        return latestRun != null && drFailbackLifecycleService != null
                && drFailbackLifecycleService.requiresCancellationCompensation(latestRun)
                ? latestRun : null;
    }

    boolean reopenFailedFullReseedRunIfDurablyRecovered(DrPlanVO plan, DrRunVO run,
            FtctlDrStatusAnswer status, JsonObject runtime) {
        if (plan == null || run == null || status == null || runtime == null
                || !isFailedFullReseedRunWithNewerDurableCycle(plan, run)
                || !status.getResult()) {
            return false;
        }
        String controlRun = StringUtils.defaultIfBlank(status.getControlRequestRunUuid(),
                stringValue(runtime, "control_request_run_uuid"));
        String workerState = StringUtils.defaultIfBlank(status.getWorkerState(),
                stringValue(runtime, "worker_state"));
        Integer workerExitCode = status.getWorkerExitCode() != null ? status.getWorkerExitCode()
                : integerValue(runtime, "worker_exit_code");
        boolean terminalAuthoritative = Boolean.TRUE.equals(status.getTerminalAuthoritative())
                || Boolean.TRUE.equals(booleanValue(runtime, "terminal_authoritative"));
        String runtimeState = StringUtils.defaultIfBlank(status.getState(), stringValue(runtime, "state"));
        String runtimeStep = StringUtils.defaultIfBlank(status.getStep(), stringValue(runtime, "step"));
        if (!StringUtils.equals(run.getUuid(), controlRun)
                || !terminalAuthoritative
                || !StringUtils.equalsAnyIgnoreCase(workerState, "TERMINAL_PUBLISHED", "SUCCEEDED")
                || !Integer.valueOf(0).equals(workerExitCode)
                || !StringUtils.equalsIgnoreCase(runtimeState, DrConstants.PLAN_STATE_READY)
                || !StringUtils.equalsIgnoreCase(runtimeStep, "full-resync-completed")) {
            return false;
        }
        DrSyncCycleVO recoveredCycle = findNewerDurableFullSeedCycle(plan, run);
        if (recoveredCycle == null) {
            return false;
        }
        run.setAcceptedCycleSequence(recoveredCycle.getSequence());
        run.setAcceptedCycleToken(recoveredCycle.getCycleToken());
        run.setState(DrConstants.RUN_STATE_ACCEPTED);
        run.setCompleted(null);
        run.setProjectionState("target-materializing");
        run.setProjectionChecked(new Date());
        run.setErrorCode(null);
        run.setErrorMessage(null);
        run.setRetryable(false);
        run.setRetryAfterSeconds(null);
        run.setNextRetryAt(null);
        run.setTerminalSource(null);
        run.setTerminalVersion(null);
        run.setTerminalAuthoritative(false);
        run.markUpdated();
        drRunDao.update(run.getId(), run);
        return true;
    }

    private boolean isFailedFullReseedRunWithNewerDurableCycle(DrPlanVO plan, DrRunVO run) {
        return plan != null && run != null
                && StringUtils.equalsIgnoreCase(run.getState(), DrConstants.RUN_STATE_FAILED)
                && run.getCompleted() != null
                && isFullReseedRun(run)
                && findNewerDurableFullSeedCycle(plan, run) != null;
    }

    private DrSyncCycleVO findNewerDurableFullSeedCycle(DrPlanVO plan, DrRunVO run) {
        if (plan == null || run == null || drSyncCycleDao == null || plan.getId() != run.getPlanId()) {
            return null;
        }
        DrSyncCycleVO fullReseed = drSyncCycleDao.findLatestCompletedByRunIdAndRequestedMode(
                run.getId(), "FULL_RESEED");
        DrSyncCycleVO fullSeed = drSyncCycleDao.findLatestCompletedByRunIdAndRequestedMode(
                run.getId(), "FULL_SEED");
        DrSyncCycleVO candidate = fullReseed == null ? fullSeed : fullSeed == null
                || fullReseed.getSequence() >= fullSeed.getSequence() ? fullReseed : fullSeed;
        long acceptedSequence = run.getAcceptedCycleSequence() != null ? run.getAcceptedCycleSequence() : 0L;
        return candidate != null
                && candidate.getSequence() > acceptedSequence
                && candidate.getCompleted() != null
                && candidate.getRunId() != null && candidate.getRunId() == run.getId()
                && StringUtils.equalsAnyIgnoreCase(candidate.getState(), "READY", "COMPLETED", "TARGET_READY")
                && StringUtils.equalsAnyIgnoreCase(candidate.getCommitState(), "LOCAL_DURABLE", "COMMITTED", "DURABLE")
                && StringUtils.equals(candidate.getCycleToken(), plan.getUuid() + ":" + candidate.getCheckpointSequence())
                ? candidate : null;
    }

    private DrRunVO resolveProtectionProducerRun(DrPlanVO plan, FtctlDrStatusAnswer status, JsonObject runtime) {
        if (plan == null || drRunDao == null) {
            return null;
        }
        String producerRunUuid = resolveProtectionProducerRunUuid(status, runtime);
        DrRunVO producerRun = drRunDao.findByUuid(producerRunUuid);
        if (producerRun != null && producerRun.getPlanId() == plan.getId()
                && StringUtils.equalsAnyIgnoreCase(producerRun.getRunType(),
                        DrConstants.RUN_TYPE_SYNC, DrConstants.RUN_TYPE_REPROTECT)) {
            return producerRun;
        }
        return drRunDao.findLatestProtectionProducerByPlanId(plan.getId());
    }

    private String resolveProtectionProducerRunUuid(FtctlDrStatusAnswer status, JsonObject runtime) {
        if (status == null) {
            return null;
        }
        String producerRunUuid = StringUtils.defaultIfBlank(status.getLatestCompletedProducerRunUuid(),
                StringUtils.defaultIfBlank(stringValue(runtime, "latest_completed_producer_run_uuid"),
                        StringUtils.defaultIfBlank(status.getActiveWorkerRunUuid(),
                                stringValue(runtime, "active_worker_run_uuid"))));
        if (StringUtils.isBlank(producerRunUuid)
                && !StringUtils.equalsIgnoreCase(status.getStatusScope(), "PLAN_AUTHORITY")) {
            producerRunUuid = status.getRunUuid();
        }
        return producerRunUuid;
    }

    private boolean isRetryableWorkerCondition(FtctlDrStatusAnswer status, JsonObject runtime) {
        String errorCode = StringUtils.defaultIfBlank(status.getErrorCode(), stringValue(runtime, "error_code"));
        String result = StringUtils.defaultIfBlank(status.getFtctlResult(), stringValue(runtime, "result"));
        String workerState = StringUtils.upperCase(stringValue(runtime, "worker_state"), Locale.ROOT);
        Boolean retryable = booleanValue(runtime, "retryable");
        return StringUtils.equalsIgnoreCase(errorCode, DrConstants.ERROR_ENGINE_BUSY_RETRYABLE)
                || (Boolean.TRUE.equals(retryable)
                && (StringUtils.equalsAny(workerState, "RETRYING", "WAITING")
                || StringUtils.equalsIgnoreCase(result, "locked")));
    }

    private boolean isFailbackLifecyclePending(DrRunVO run, FtctlDrStatusAnswer status, JsonObject runtime) {
        if (run == null || !StringUtils.equalsIgnoreCase(run.getRunType(), DrConstants.RUN_TYPE_FAILBACK)) {
            return false;
        }
        String errorCode = StringUtils.defaultIfBlank(status.getErrorCode(), stringValue(runtime, "error_code"));
        String phase = StringUtils.upperCase(stringValue(runtime, "failback_phase"), Locale.ROOT);
        String outcome = StringUtils.upperCase(stringValue(runtime, "failback_commit_outcome"), Locale.ROOT);
        return StringUtils.equalsIgnoreCase(errorCode, DrConstants.ERROR_FAILBACK_COMMIT_ACK_PENDING)
                || StringUtils.equals(phase, "COMMIT_VERIFYING")
                        && StringUtils.equalsAny(outcome, "", "UNKNOWN", "PENDING")
                || StringUtils.equals(phase, "PROTECTION_RESUMING")
                        && StringUtils.equals(outcome, "ACKNOWLEDGED");
    }

    private void markFailbackLifecyclePending(DrRunVO run, FtctlDrStatusAnswer status, JsonObject runtime) {
        String phase = StringUtils.upperCase(stringValue(runtime, "failback_phase"), Locale.ROOT);
        String step = StringUtils.equals(phase, "PROTECTION_RESUMING")
                ? "protection-resuming" : "commit-verifying";
        Integer retryAfter = integerValue(runtime, "retry_after_sec");
        if (retryAfter == null || retryAfter <= 0) {
            retryAfter = STATUS_REFRESH_WAIT_SECONDS;
        }
        int progress = status.getProgress() != null ? Math.max(status.getProgress(), 90) : 90;
        Date now = new Date();
        String compactStatusJson = compactRuntimeStatusJson(status.getStatusJson());
        recordRunProjectionStep(run, DrConstants.STEP_STATE_RUNNING, progress,
                compactStatusJson, null, "Failback authority acknowledgement is being verified");
        run.setState(DrConstants.RUN_STATE_RUNNING);
        run.setCompleted(null);
        run.setCurrentStepName(step);
        run.setProjectionState("lifecycle-pending");
        run.setProjectionChecked(now);
        run.setRetryable(true);
        run.setRetryAfterSeconds(retryAfter);
        run.setNextRetryAt(new Date(now.getTime() + retryAfter * 1000L));
        run.setLastStatusJson(compactStatusJson);
        run.setErrorCode(null);
        run.setErrorMessage(null);
        run.setTerminalSource(null);
        run.setTerminalVersion(null);
        run.setTerminalAuthoritative(false);
        run.markUpdated();
        drRunDao.update(run.getId(), run);
    }

    private boolean isWorkerFailed(JsonObject runtime) {
        if (Boolean.TRUE.equals(booleanValue(runtime, "terminal_publication_pending"))
                || StringUtils.equalsIgnoreCase(stringValue(runtime, "worker_state"), "TERMINAL_PENDING")) {
            return false;
        }
        String workerState = StringUtils.upperCase(stringValue(runtime, "worker_state"), Locale.ROOT);
        String errorCode = stringValue(runtime, "error_code");
        return StringUtils.equals(workerState, "FAILED")
                && !StringUtils.equalsIgnoreCase(errorCode, DrConstants.ERROR_ENGINE_BUSY_RETRYABLE);
    }

    private boolean isWorkerStartStalled(DrRunVO run, JsonObject runtime) {
        String workerState = StringUtils.upperCase(stringValue(runtime, "worker_state"), Locale.ROOT);
        if (!StringUtils.equalsAny(workerState, "STARTING", "STARTED")) {
            return false;
        }
        Date base = firstDate(parseDate(stringValue(runtime, "worker_updated_at")), parseDate(stringValue(runtime, "updated_at")),
                run.getAcceptedAt(), run.getDispatchCompleted(), run.getStarted(), run.getCreated());
        if (base == null) {
            return false;
        }
        long ageSeconds = (System.currentTimeMillis() - base.getTime()) / 1000L;
        return ageSeconds >= WORKER_START_GRACE_SECONDS;
    }

    private boolean isRuntimeError(FtctlDrStatusAnswer status, JsonObject runtime) {
        if (Boolean.TRUE.equals(booleanValue(runtime, "terminal_publication_pending"))) {
            return false;
        }
        if (isStatusTimeout(status, runtime)) {
            return false;
        }
        String runtimeState = StringUtils.upperCase(StringUtils.defaultIfBlank(status.getState(), stringValue(runtime, "state")), Locale.ROOT);
        String errorCode = StringUtils.defaultIfBlank(status.getErrorCode(), stringValue(runtime, "error_code"));
        if (StringUtils.equalsIgnoreCase(errorCode, DrConstants.ERROR_PROJECTION_STALE)
                || StringUtils.equalsIgnoreCase(errorCode, DrConstants.ERROR_ENGINE_BUSY_RETRYABLE)
                || StringUtils.equalsIgnoreCase(errorCode, DrConstants.ERROR_FAILBACK_COMMIT_ACK_PENDING)) {
            return false;
        }
        return !status.getResult() || StringUtils.equalsAny(runtimeState, "ERROR", "FAILED")
                || (StringUtils.isNotBlank(errorCode) && !StringUtils.equalsIgnoreCase(errorCode, "not_found"));
    }

    private boolean isRunSatisfiedByRuntime(DrPlanVO plan, DrRunVO run, FtctlDrStatusAnswer status, JsonObject runtime) {
        String runType = StringUtils.upperCase(run.getRunType(), Locale.ROOT);
        String runtimeState = StringUtils.upperCase(StringUtils.defaultIfBlank(status.getState(), stringValue(runtime, "state")), Locale.ROOT);
        String activeSide = StringUtils.upperCase(StringUtils.defaultIfBlank(plan.getActiveSide(), stringValue(runtime, "active_side")), Locale.ROOT);
        if (StringUtils.equals(runType, DrConstants.RUN_TYPE_SYNC)) {
            if (isFullReseedRun(run) && !isAcceptedFullReseedCycleSatisfied(run, status, runtime)) {
                return false;
            }
            return isSyncTargetReady(plan, status, runtime, runtimeState);
        }
        if (StringUtils.equals(runType, DrConstants.RUN_TYPE_TEST_FAILOVER)) {
            return drTargetMaterializationService.isTestTargetActive(run.getId());
        }
        if (StringUtils.equals(runType, DrConstants.RUN_TYPE_FAILOVER)) {
            DrCutoverSessionVO session = drCutoverSessionDao.findActiveByRunId(run.getId());
            return session != null
                    && StringUtils.equals(plan.getState(), DrConstants.PLAN_STATE_FAILED_OVER)
                    && StringUtils.equalsIgnoreCase(plan.getActiveSide(), "TARGET")
                    && StringUtils.equalsIgnoreCase(session.getCloudPromotionState(), "PROMOTED")
                    && StringUtils.equalsIgnoreCase(session.getTargetPowerState(), "POWERED_ON")
                    && StringUtils.equalsIgnoreCase(session.getEngineAckState(), "ACKNOWLEDGED")
                    && StringUtils.equalsIgnoreCase(session.getState(), DrConstants.PLAN_STATE_FAILED_OVER)
                    && session.getCompletedAt() != null;
        }
        if (StringUtils.equals(runType, DrConstants.RUN_TYPE_FAILBACK)) {
            DrFailbackSessionVO session = drFailbackSessionDao.findActiveByRunId(run.getId());
            return session != null
                    && StringUtils.equals(plan.getState(), DrConstants.PLAN_STATE_READY)
                    && StringUtils.equals(activeSide, "SOURCE")
                    && StringUtils.equalsIgnoreCase(session.getState(), "COMPLETED")
                    && StringUtils.equalsIgnoreCase(session.getTargetPowerState(), "POWERED_OFF")
                    && StringUtils.equalsIgnoreCase(session.getSourcePowerState(), "POWERED_ON")
                    && StringUtils.equalsIgnoreCase(session.getBootValidationState(), "POWER_STATE_VALIDATED")
                    && StringUtils.equalsIgnoreCase(session.getEngineAckState(), "ACKNOWLEDGED")
                    && session.getPostFailbackCheckpointSequence() != null;
        }
        if (StringUtils.equals(runType, DrConstants.RUN_TYPE_REPROTECT)) {
            String controlRequestRunUuid = StringUtils.defaultIfBlank(status.getControlRequestRunUuid(),
                    stringValue(runtime, "control_request_run_uuid"));
            String terminalSource = StringUtils.defaultIfBlank(status.getTerminalSource(),
                    stringValue(runtime, "terminal_source"));
            boolean terminalAuthoritative = Boolean.TRUE.equals(status.getTerminalAuthoritative())
                    || Boolean.TRUE.equals(booleanValue(runtime, "terminal_authoritative"))
                    || StringUtils.equalsIgnoreCase(terminalSource, "ENGINE_TERMINAL");
            boolean endpointsDrained = Boolean.TRUE.equals(status.getRuntimeEndpointsDrained())
                    || Boolean.TRUE.equals(booleanValue(runtime, "runtime_endpoints_drained"));
            String runtimeStep = StringUtils.defaultIfBlank(status.getStep(), stringValue(runtime, "step"));
            String baselineState = StringUtils.defaultIfBlank(stringValue(runtime, "baseline_state"),
                    stringValue(runtime, "baseline_file_state"));
            return StringUtils.equals(run.getUuid(), controlRequestRunUuid)
                    && terminalAuthoritative && endpointsDrained
                    && StringUtils.equals(runtimeState, DrConstants.PLAN_STATE_READY)
                    && StringUtils.equalsIgnoreCase(runtimeStep, "reprotect-ready")
                    && StringUtils.equals(activeSide, "TARGET")
                    && StringUtils.equalsAnyIgnoreCase(baselineState, "LOCAL_DURABLE", "DURABLE", "COMMITTED")
                    && StringUtils.isNotBlank(stringValue(runtime, "reprotect_session_id"))
                    && StringUtils.isNotBlank(stringValue(runtime, "reprotect_completed_at"))
                    && StringUtils.isNotBlank(stringValue(runtime, "reprotect_manifest_path"))
                    && StringUtils.isNotBlank(stringValue(runtime, "reprotect_checkpoint_path"));
        }
        if (StringUtils.equals(runType, DrConstants.RUN_TYPE_TEST_CLEANUP)) {
            return drTargetMaterializationService.isTestTargetCleaned(plan.getId())
                    && runtimeBelongsToRun(runtime, run)
                    && StringUtils.equalsIgnoreCase(stringValue(runtime, "test_cleanup_state"), "CLEANED")
                    && StringUtils.equalsAny(runtimeState, "READY", "PAUSED");
        }
        return StringUtils.equalsAny(runType, DrConstants.RUN_TYPE_PAUSE_SYNC,
                DrConstants.RUN_TYPE_RESUME_SYNC, DrConstants.RUN_TYPE_RELEASE)
                && StringUtils.equalsAny(runtimeState, "READY", "PAUSED", "RELEASED");
    }

    private void completeRunFromProjection(DrPlanVO plan, DrRunVO run, FtctlDrStatusAnswer status) {
        String compactStatusJson = compactRuntimeStatusJson(status.getStatusJson());
        recordRunProjectionStep(run, DrConstants.STEP_STATE_SUCCEEDED, 100, compactStatusJson, null, null);
        if (StringUtils.equalsIgnoreCase(run.getRunType(), DrConstants.RUN_TYPE_TEST_FAILOVER)) {
            recordRunStep(run, "target-materialization", 40, DrConstants.STEP_STATE_SUCCEEDED, 100, compactStatusJson, null, null);
            recordRunStep(run, "boot-validation", 50, DrConstants.STEP_STATE_SUCCEEDED, 100, compactStatusJson, null, null);
            recordRunStep(run, "test-failover-active", 60, DrConstants.STEP_STATE_SUCCEEDED, 100, compactStatusJson, null, null);
        } else if (StringUtils.equalsIgnoreCase(run.getRunType(), DrConstants.RUN_TYPE_FAILOVER)) {
            recordRunStep(run, "source-isolation", STEP_ORDER_SOURCE_ISOLATION, DrConstants.STEP_STATE_SUCCEEDED, 100, compactStatusJson, null, null);
            recordRunStep(run, "target-power-on", STEP_ORDER_TARGET_POWER_ON, DrConstants.STEP_STATE_SUCCEEDED, 100, compactStatusJson, null, null);
            recordRunStep(run, "boot-validation", STEP_ORDER_BOOT_VALIDATION, DrConstants.STEP_STATE_SUCCEEDED, 100, compactStatusJson, null, null);
            recordRunStep(run, "cloud-promotion", STEP_ORDER_CLOUD_PROMOTION, DrConstants.STEP_STATE_SUCCEEDED, 100, compactStatusJson, null, null);
            recordRunStep(run, "engine-state-reconciliation", STEP_ORDER_ENGINE_ACK, DrConstants.STEP_STATE_SUCCEEDED, 100, compactStatusJson, null, null);
            recordRunStep(run, "completed", STEP_ORDER_FINAL, DrConstants.STEP_STATE_SUCCEEDED, 100, compactStatusJson, null, null);
        }
        run.setState(DrConstants.RUN_STATE_SUCCEEDED);
        run.setCompleted(new Date());
        run.setCurrentStepName(StringUtils.equalsIgnoreCase(run.getRunType(), DrConstants.RUN_TYPE_TEST_FAILOVER)
                ? "test-failover-active" : StringUtils.equalsIgnoreCase(run.getRunType(), DrConstants.RUN_TYPE_FAILOVER)
                        ? "completed" : "runtime-projection");
        run.setEngineAccepted(true);
        if (run.getAcceptedAt() == null) {
            run.setAcceptedAt(new Date());
        }
        run.setProjectionState("succeeded");
        run.setProjectionChecked(new Date());
        run.setRetryable(false);
        run.setRetryAfterSeconds(null);
        run.setNextRetryAt(null);
        run.setLastStatusJson(compactStatusJson);
        run.setErrorCode(null);
        run.setErrorMessage(null);
        DrSyncCycleVO acceptedCycle = resolveAcceptedCycle(run);
        boolean canonicalCycleTerminal = isAcceptedRunOwnedDurableFullSeedCycle(run, acceptedCycle);
        run.setTerminalSource(StringUtils.defaultIfBlank(status.getTerminalSource(),
                canonicalCycleTerminal ? "CYCLE_DURABLE" : "ENGINE_TERMINAL"));
        run.setTerminalVersion(status.getTerminalVersion());
        run.setTerminalAuthoritative(Boolean.TRUE.equals(status.getTerminalAuthoritative())
                || StringUtils.equalsIgnoreCase(status.getTerminalSource(), "ENGINE_TERMINAL")
                || canonicalCycleTerminal);
        run.markUpdated();
        drRunDao.update(run.getId(), run);
        persistRunProjectionEvent(plan, run, DrConstants.EVENT_RUN_SUCCEEDED, DrConstants.EVENT_SEVERITY_INFO,
                "FTCTL_DR runtime accepted state completed", compactStatusJson);
        if (StringUtils.equalsIgnoreCase(run.getRunType(), DrConstants.RUN_TYPE_TEST_FAILOVER)) {
            persistRunProjectionEvent(plan, run, DrConstants.EVENT_TEST_VM_ACTIVE, DrConstants.EVENT_SEVERITY_INFO,
                    "Cloud-managed DR test VM is active", compactStatusJson);
        }
    }

    void markSourceIsolationWaiting(DrPlanVO plan, DrRunVO projectionRun, RuntimeException failure) {
        if (plan == null || drRunDao == null) {
            return;
        }
        DrRunVO run = projectionRun != null ? projectionRun : drRunDao.findActiveByPlanId(plan.getId());
        if (run == null || !StringUtils.equalsIgnoreCase(run.getRunType(), DrConstants.RUN_TYPE_FAILOVER)
                || !isProjectableRunState(run)) {
            return;
        }
        String errorCode = sourceIsolationErrorCode(failure);
        String message = StringUtils.defaultIfBlank(failure != null ? failure.getMessage() : null,
                "The source VM has not reached POWERED_OFF");
        Date now = new Date();
        recordRunStep(run, "source-isolation", STEP_ORDER_SOURCE_ISOLATION, DrConstants.STEP_STATE_RUNNING,
                70, run.getLastStatusJson(), errorCode, message);
        run.setCurrentStepName("source-isolation-wait");
        run.setProjectionState("source-isolation-wait");
        run.setProjectionChecked(now);
        run.setRetryable(true);
        run.setRetryAfterSeconds(STATUS_REFRESH_WAIT_SECONDS);
        run.setNextRetryAt(new Date(now.getTime() + STATUS_REFRESH_WAIT_SECONDS * 1000L));
        run.setErrorCode(errorCode);
        run.setErrorMessage(message);
        run.markUpdated();
        drRunDao.update(run.getId(), run);
    }

    private String sourceIsolationErrorCode(RuntimeException failure) {
        String message = failure != null ? failure.getMessage() : null;
        return StringUtils.containsIgnoreCase(message, "clone flatten")
                ? DrConstants.ERROR_SOURCE_CLONE_FLATTEN_ACTIVE
                : DrConstants.ERROR_SOURCE_ISOLATION_NOT_READY;
    }

    private void failRunFromProjection(DrPlanVO plan, DrRunVO run, FtctlDrStatusAnswer status, JsonObject runtime) {
        String errorCode = StringUtils.defaultIfBlank(status.getErrorCode(),
                StringUtils.defaultIfBlank(stringValue(runtime, "error_code"), DrConstants.ERROR_ENGINE_ACTION_FAILED));
        if (StringUtils.equalsIgnoreCase(errorCode, "not_found")) {
            errorCode = DrConstants.ERROR_RUNTIME_NOT_CREATED;
        } else if (StringUtils.equalsIgnoreCase(errorCode, DrConstants.ERROR_ENGINE_ACTION_FAILED)
                && StringUtils.equalsIgnoreCase(stringValue(runtime, "worker_state"), "FAILED")) {
            errorCode = DrConstants.ERROR_ENGINE_WORKER_FAILED;
        }
        String message = projectionFailureMessage(errorCode, status, runtime);
        String compactStatusJson = compactRuntimeStatusJson(status.getStatusJson());
        recordRunProjectionStep(run, DrConstants.STEP_STATE_FAILED, 100, compactStatusJson, errorCode, message);
        run.setState(DrConstants.RUN_STATE_FAILED);
        run.setCompleted(new Date());
        run.setCurrentStepName("runtime-projection");
        run.setProjectionState("failed");
        run.setProjectionChecked(new Date());
        run.setRetryable(false);
        run.setRetryAfterSeconds(null);
        run.setNextRetryAt(null);
        run.setLastStatusJson(compactStatusJson);
        run.setErrorCode(errorCode);
        run.setErrorMessage(message);
        run.setTerminalSource(StringUtils.defaultIfBlank(status.getTerminalSource(),
                stringValue(runtime, "terminal_source")));
        run.setTerminalVersion(status.getTerminalVersion() != null ? status.getTerminalVersion()
                : integerValue(runtime, "terminal_version"));
        run.setTerminalAuthoritative(Boolean.TRUE.equals(status.getTerminalAuthoritative())
                || Boolean.TRUE.equals(booleanValue(runtime, "terminal_authoritative"))
                || StringUtils.equalsIgnoreCase(run.getTerminalSource(), "ENGINE_TERMINAL"));
        run.markUpdated();
        drRunDao.update(run.getId(), run);
        boolean finiteOperationFailed = isFiniteOperationRun(run);
        boolean failoverPreparationAborted = !finiteOperationFailed
                && abortFailedFailoverPreparation(plan, run, runtime, errorCode, message);
        if (finiteOperationFailed) {
            LOGGER.info("DR finite operation {} failed without changing protection state for Plan {}",
                    run.getRunType(), plan.getUuid());
        } else if (failoverPreparationAborted
                || isRolledBackFailback(plan, run, runtime)
                || isReprotectOperationOnlyFailure(plan, run, errorCode)) {
            if (!failoverPreparationAborted) {
                preserveFailedOverTargetAuthority(plan);
            }
        } else {
            markPlanProjectionFailed(plan, errorCode, message);
            markReplicaProjectionFailed(plan, status, runtime, errorCode, message);
        }
        persistRunProjectionEvent(plan, run, DrConstants.EVENT_RUN_FAILED, DrConstants.EVENT_SEVERITY_ERROR,
                message, compactStatusJson);
    }

    private boolean abortFailedFailoverPreparation(DrPlanVO plan, DrRunVO run, JsonObject runtime,
            String errorCode, String message) {
        if (plan == null || run == null
                || !StringUtils.equalsIgnoreCase(run.getRunType(), DrConstants.RUN_TYPE_FAILOVER)
                || StringUtils.equalsIgnoreCase(stringValue(runtime, "active_side"), "TARGET")
                || (StringUtils.equalsIgnoreCase(stringValue(runtime, "target_power_state"), "POWERED_ON")
                        && !StringUtils.equalsIgnoreCase(run.getState(), DrConstants.RUN_STATE_CANCELED))) {
            return false;
        }
        DrCutoverSessionVO session = drCutoverSessionDao.findActiveByRunId(run.getId());
        if (session == null) {
            session = new DrCutoverSessionVO(plan.getId(), run.getId(), run.getRunType(), "ABORTING");
            session = drCutoverSessionDao.persist(session);
        }
        session.setState("ABORTING");
        session.setCleanupRequired(true);
        session.setErrorCode(errorCode);
        session.setErrorMessage(message);
        session.setDetailsJson(compactRuntimeStatusJson(GSON.toJson(runtime)));
        session.markUpdated();
        drCutoverSessionDao.update(session.getId(), session);

        try {
            drTargetMaterializationService.ensureTargetPoweredOff(plan.getId());
        } catch (RuntimeException e) {
            session.setState("ABORT_FAILED");
            session.setCleanupRequired(true);
            session.setErrorCode("DR_FAILOVER_ABORT_UNSAFE");
            session.setErrorMessage(StringUtils.defaultIfBlank(e.getMessage(),
                    "Cloud target VM could not be stopped before source authority restoration"));
            session.markUpdated();
            drCutoverSessionDao.update(session.getId(), session);
            return false;
        }

        boolean disasterFailover = DrFailoverExecutionPolicy.isDisaster(run);
        Long hostId = disasterFailover && drWorkerPlacementService != null
                ? drWorkerPlacementService.resolveWorkerHostId(plan, run, DrWorkerRole.TARGET)
                : resolveCoordinatorHostId(plan);
        if (hostId == null) {
            session.setState("ABORT_FAILED");
            session.setErrorCode("DR_FAILOVER_ABORT_HOST_UNRESOLVED");
            session.setErrorMessage("FTCTL_DR coordinator host could not be resolved for failover preparation abort");
            session.markUpdated();
            drCutoverSessionDao.update(session.getId(), session);
            return false;
        }
        FtctlDrActionCommand command = new FtctlDrActionCommand(FtctlDrActionCommand.Action.FAILOVER_ABORT,
                plan.getUuid(), run.getUuid());
        command.setWaitForCompletion(true);
        String engineSessionId = stringValue(runtime, "failover_session_id");
        if (StringUtils.isNotBlank(engineSessionId)) {
            command.setContextParam("cutoverSessionId", engineSessionId);
        }
        Answer answer = isRemoteKvmToKvmPlan(plan) && !disasterFailover
                ? drRemoteAgentClient.execute(plan, "ACTION", command,
                        null, FtctlDrActionAnswer.class)
                : agentManager.easySend(hostId, command);
        if (answer == null || !answer.getResult()) {
            String abortMessage = answer != null ? answer.getDetails()
                    : "Agent returned no failover preparation abort acknowledgement";
            session.setState("ABORT_FAILED");
            session.setCleanupRequired(true);
            session.setErrorCode("DR_FAILOVER_ABORT_FAILED");
            session.setErrorMessage(abortMessage);
            session.markUpdated();
            drCutoverSessionDao.update(session.getId(), session);
            return false;
        }
        if (!cloudTargetsStopped(plan)) {
            session.setState("ABORT_FAILED");
            session.setCleanupRequired(true);
            session.setErrorCode("DR_FAILOVER_ABORT_CLOUD_STATE_CHANGED");
            session.setErrorMessage("Cloud target VM power state changed while failover preparation was being aborted");
            session.markUpdated();
            drCutoverSessionDao.update(session.getId(), session);
            return false;
        }

        if (isRemoteKvmToKvmPlan(plan) && !disasterFailover) {
            try {
                restorePlanOwnedTargetExportAfterAbort(plan, run);
                String sourcePowerState = drRemoteAgentClient.ensureSourceVmPowerState(plan, true);
                if (!StringUtils.equalsIgnoreCase(sourcePowerState, "POWERED_ON")) {
                    throw new CloudRuntimeException("Remote KVM source VM did not reach POWERED_ON after failover abort");
                }
                FtctlDrActionAnswer resumeAnswer = drRemoteAgentClient.transitionSourceScheduler(plan,
                        FtctlDrActionCommand.Action.RESUME_SYNC, run.getUuid());
                if (resumeAnswer == null || !resumeAnswer.getResult()) {
                    throw new CloudRuntimeException(StringUtils.defaultIfBlank(
                            resumeAnswer != null ? resumeAnswer.getDetails() : null,
                            "Remote source scheduler did not resume after failover abort"));
                }
            } catch (RuntimeException e) {
                session.setState("ABORT_FAILED");
                session.setCleanupRequired(true);
                session.setErrorCode("DR_FAILOVER_ABORT_RECOVERY_FAILED");
                session.setErrorMessage(e.getMessage());
                session.markUpdated();
                drCutoverSessionDao.update(session.getId(), session);
                return false;
            }
        }

        Date now = new Date();
        session.setState("ABORTED");
        session.setCleanupRequired(false);
        session.setCloudPromotionState("NOT_STARTED");
        session.setTargetPowerState("POWERED_OFF");
        session.setEngineAckState("ABORTED");
        session.setEngineAckAt(now);
        session.setCompletedAt(now);
        session.setErrorCode(errorCode);
        session.setErrorMessage(message);
        session.markUpdated();
        drCutoverSessionDao.update(session.getId(), session);
        restoreSourceAuthorityAfterFailoverAbort(plan);
        persistRunProjectionEvent(plan, run, "DR_FAILOVER_PREPARATION_ABORTED", DrConstants.EVENT_SEVERITY_WARN,
                "Failover preparation failed before target promotion; FTCTL resumed source protection", session.getDetailsJson());
        return true;
    }

    private boolean cloudTargetsStopped(DrPlanVO plan) {
        if (plan == null || drReplicaDao == null || userVmDao == null) {
            return false;
        }
        List<DrReplicaVO> replicas = drReplicaDao.listActiveByPlanId(plan.getId());
        if (replicas == null) {
            return true;
        }
        for (DrReplicaVO replica : replicas) {
            if (replica == null || replica.getRemoved() != null || replica.getTargetVmId() == null) {
                continue;
            }
            UserVmVO targetVm = userVmDao.findById(replica.getTargetVmId());
            if (targetVm != null && targetVm.getRemoved() == null
                    && targetVm.getState() != VirtualMachine.State.Stopped) {
                return false;
            }
        }
        return true;
    }

    private void restoreSourceAuthorityAfterFailoverAbort(DrPlanVO plan) {
        plan.setState(DrConstants.PLAN_STATE_READY);
        plan.setActiveSide("SOURCE");
        plan.setLastErrorCode(null);
        plan.setLastErrorMessage(null);
        plan.markUpdated();
        drPlanDao.update(plan.getId(), plan);
        DrPlanRuntimeVO planRuntime = drPlanRuntimeDao.findByPlanId(plan.getId());
        if (planRuntime == null) {
            planRuntime = new DrPlanRuntimeVO(plan.getId());
        }
        planRuntime.setProtectionState(DrConstants.PLAN_STATE_READY);
        planRuntime.setErrorCode(null);
        planRuntime.setErrorMessage(null);
        planRuntime.setLastStatusAt(new Date());
        planRuntime.markUpdated();
        if (planRuntime.getId() == 0) {
            drPlanRuntimeDao.persist(planRuntime);
        } else {
            drPlanRuntimeDao.update(planRuntime.getId(), planRuntime);
        }
        for (DrReplicaVO replica : drReplicaDao.listActiveByPlanId(plan.getId())) {
            if (replica == null || replica.getRemoved() != null) {
                continue;
            }
            replica.setState(DrConstants.REPLICA_STATE_READY);
            replica.setActiveSide("SOURCE");
            replica.setPowerState("POWERED_OFF");
            replica.markUpdated();
            drReplicaDao.update(replica.getId(), replica);
        }
    }

    private boolean isReprotectOperationOnlyFailure(DrPlanVO plan, DrRunVO run, String errorCode) {
        if (plan == null || run == null
                || !StringUtils.equalsIgnoreCase(run.getRunType(), DrConstants.RUN_TYPE_REPROTECT)
                || !StringUtils.equalsIgnoreCase(plan.getActiveSide(), "TARGET")) {
            return false;
        }
        return StringUtils.startsWithIgnoreCase(errorCode, "DR_REPROTECT_")
                || StringUtils.equalsAnyIgnoreCase(errorCode,
                        "DR_REPROTECT_REVERSE_SYNC_FAILED",
                        "DR_UNSUPPORTED_DIRECTION",
                        DrConstants.ERROR_VMWARE_MOVER_UNAVAILABLE,
                        DrConstants.ERROR_VMWARE_MOVER_FAILED);
    }

    private boolean isRolledBackFailback(DrPlanVO plan, DrRunVO run, JsonObject runtime) {
        return plan != null && run != null && runtime != null
                && StringUtils.equalsIgnoreCase(run.getRunType(), DrConstants.RUN_TYPE_FAILBACK)
                && StringUtils.equalsIgnoreCase(plan.getActiveSide(), "TARGET")
                && StringUtils.equalsIgnoreCase(stringValue(runtime, "active_side"), "TARGET")
                && StringUtils.equalsIgnoreCase(stringValue(runtime, "failback_commit_outcome"), "ROLLED_BACK")
                && StringUtils.equalsIgnoreCase(stringValue(runtime, "rollback_state"), "COMPLETED")
                && StringUtils.equalsIgnoreCase(stringValue(runtime, "source_power_state"), "POWERED_OFF")
                && StringUtils.equalsIgnoreCase(stringValue(runtime, "target_power_state"), "POWERED_ON");
    }

    private void preserveFailedOverTargetAuthority(DrPlanVO plan) {
        DrPlanRuntimeVO planRuntime = drPlanRuntimeDao != null
                ? drPlanRuntimeDao.findByPlanId(plan.getId()) : null;
        if (isPersistedTargetProtectedRuntime(planRuntime)) {
            plan.setState(DrConstants.PLAN_STATE_READY);
            plan.setActiveSide(DrConstants.AUTHORITY_SIDE_TARGET);
            plan.setLastErrorCode(null);
            plan.setLastErrorMessage(null);
            plan.markUpdated();
            drPlanDao.update(plan.getId(), plan);
            preserveServingTargetReplica(plan);
            return;
        }
        plan.setState(DrConstants.PLAN_STATE_FAILED_OVER);
        plan.setActiveSide("TARGET");
        plan.setLastErrorCode(null);
        plan.setLastErrorMessage(null);
        plan.markUpdated();
        drPlanDao.update(plan.getId(), plan);
        if (drPlanRuntimeDao == null) {
            return;
        }
        if (planRuntime == null) {
            planRuntime = new DrPlanRuntimeVO(plan.getId());
        }
        long authoritySequenceFloor = resolveAuthoritySequenceFloor(plan,
                planRuntime.getRuntimeGeneration(), planRuntime);
        planRuntime.setProtectionState("FAILED_OVER_UNPROTECTED");
        planRuntime.setFreshnessState("WITHIN_RPO");
        planRuntime.setSchedulerState("STOPPED");
        planRuntime.setSchedulerDesiredState("STOPPED");
        planRuntime.setSchedulerHealthState("SUPPRESSED");
        planRuntime.setSchedulerRecoveryState(DrConstants.SCHEDULER_RECOVERY_SUPPRESSED);
        planRuntime.setReplicationActivityState("STOPPED");
        planRuntime.setSchedulerPidAlive(false);
        planRuntime.setOwnerMatched(false);
        planRuntime.setActiveWorkerRunUuid(null);
        planRuntime.setActiveWorkerPid(null);
        planRuntime.setActiveWorkerStartTicks(null);
        planRuntime.setWorkerHeartbeatAt(null);
        planRuntime.setOwnedProcessCount(0);
        planRuntime.setReconciliationState("NONE");
        planRuntime.setReconciliationRunUuid(null);
        planRuntime.setReconciliationChecks(0);
        planRuntime.setWorkerState("IDLE");
        planRuntime.setWorkerIdentityState("IDLE");
        planRuntime.setWorkerLivenessState("STOPPED");
        planRuntime.setTransferActivityState("IDLE");
        planRuntime.setErrorCode(null);
        planRuntime.setErrorMessage(null);
        planRuntime.setAuthoritySequence(authoritySequenceFloor);
        planRuntime.setRuntimeGeneration(authoritySequenceFloor);
        planRuntime.setRpoOverdue(false);
        planRuntime.setLastStatusAt(new Date());
        planRuntime.markUpdated();
        if (planRuntime.getId() == 0) {
            drPlanRuntimeDao.persist(planRuntime);
        } else {
            drPlanRuntimeDao.update(planRuntime.getId(), planRuntime);
        }
        preserveServingTargetReplica(plan);
        ensureCommittedTargetAuthorityProjection(plan);
    }

    private boolean isPersistedTargetProtectedRuntime(DrPlanRuntimeVO runtime) {
        return runtime != null
                && StringUtils.equalsIgnoreCase(runtime.getProtectionState(), DrConstants.PLAN_STATE_READY)
                && StringUtils.equalsIgnoreCase(runtime.getSchedulerState(), "RUNNING")
                && StringUtils.equalsIgnoreCase(runtime.getSchedulerHealthState(), "HEALTHY")
                && runtime.isSchedulerPidAlive()
                && runtime.isOwnerMatched()
                && StringUtils.equalsAnyIgnoreCase(runtime.getBaselineState(),
                        "LOCAL_DURABLE", "DURABLE", "COMMITTED")
                && runtime.getLatestCompletedCycleSequence() != null
                && runtime.getLatestCompletedCycleSequence() > 0L
                && StringUtils.isBlank(runtime.getErrorCode());
    }

    private long resolveAuthoritySequenceFloor(DrPlanVO plan, Long requestedGeneration,
            DrPlanRuntimeVO authority) {
        long floor = requestedGeneration != null ? requestedGeneration : 0L;
        if (authority != null) {
            floor = Math.max(floor, authority.getAuthoritySequence());
            floor = Math.max(floor, authority.getRuntimeGeneration());
        }
        if (plan != null && drSyncCycleDao != null) {
            DrSyncCycleVO latestCompleted = drSyncCycleDao.findLatestCompletedByPlanId(plan.getId());
            if (latestCompleted != null && latestCompleted.getAuthoritySequence() != null) {
                floor = Math.max(floor, latestCompleted.getAuthoritySequence());
            }
        }
        return floor;
    }

    private void ensureCommittedTargetAuthorityProjection(DrPlanVO plan) {
        Long targetHostId = drWorkerPlacementService != null
                ? drWorkerPlacementService.resolveWorkerHostId(plan, DrWorkerRole.TARGET) : null;
        if (!isRemoteKvmToKvmPlan(plan) || targetHostId == null
                || drCutoverSessionDao == null || drRunDao == null) {
            return;
        }
        DrCutoverSessionVO session = findCommittedTargetAuthority(plan);
        if (session == null || session.getRunId() <= 0 || session.getCloudAuthorityGeneration() == null) {
            return;
        }
        FtctlDrStatusCommand probe = new FtctlDrStatusCommand(plan.getUuid(), null,
                FtctlDrStatusCommand.StatusScope.TRANSITION_PREFLIGHT);
        probe.setTransitionOperation("failback");
        probe.setExpectedAuthoritySide(DrConstants.AUTHORITY_SIDE_TARGET);
        probe.setExpectedAuthorityGeneration(session.getCloudAuthorityGeneration());
        Answer probeAnswer = agentManager.easySend(targetHostId, probe);
        FtctlDrStatusAnswer typedProbe = probeAnswer instanceof FtctlDrStatusAnswer
                ? (FtctlDrStatusAnswer) probeAnswer : null;
        if (typedProbe != null && probeAnswer.getResult()
                && Boolean.TRUE.equals(typedProbe.getTransitionReady())
                && StringUtils.equalsIgnoreCase(typedProbe.getTransitionActiveSide(),
                        DrConstants.AUTHORITY_SIDE_TARGET)
                && session.getCloudAuthorityGeneration().equals(
                        typedProbe.getTransitionAuthorityGeneration())) {
            return;
        }
        DrRunVO run = drRunDao.findById(session.getRunId());
        if (run == null) {
            LOGGER.warn("Unable to repair target FTCTL authority for Plan {}: cutover Run {} is missing",
                    plan.getUuid(), session.getRunId());
            return;
        }
        try {
            DrTargetPowerOnResult powerOnResult = drTargetMaterializationService.ensureTargetPoweredOn(plan.getId());
            if (powerOnResult == null || !powerOnResult.isReady()) {
                return;
            }
            FtctlDrActionCommand command = buildCutoverCommitCommand(plan, run, session, powerOnResult,
                    session.getCloudAuthorityGeneration(), "target");
            Answer repair = command != null
                    ? agentManager.easySend(targetHostId, command) : null;
            if (repair == null || !repair.getResult()) {
                LOGGER.warn("Target FTCTL authority repair is pending for Plan {}: {}", plan.getUuid(),
                        repair != null ? repair.getDetails() : "no Agent answer");
            }
        } catch (RuntimeException e) {
            LOGGER.warn("Unable to repair target FTCTL authority projection for Plan {}", plan.getUuid(), e);
        }
    }

    private boolean preserveCommittedTargetAuthorityAfterReprotectFailure(DrPlanVO plan,
            FtctlDrStatusAnswer status, JsonObject runtime) {
        String errorCode = StringUtils.defaultIfBlank(status != null ? status.getErrorCode() : null,
                stringValue(runtime, "error_code"));
        String action = stringValue(runtime, "action");
        String runtimeState = StringUtils.defaultIfBlank(status != null ? status.getState() : null,
                stringValue(runtime, "state"));
        boolean reprotectFailure = status == null || !status.getResult()
                || StringUtils.equalsAnyIgnoreCase(runtimeState, "ERROR", "FAILED")
                || StringUtils.isNotBlank(errorCode);
        if (plan == null || !StringUtils.equalsIgnoreCase(plan.getActiveSide(), "TARGET")
                || !(StringUtils.equalsIgnoreCase(action, "dr-reprotect")
                        || StringUtils.startsWithIgnoreCase(errorCode, "DR_REPROTECT_"))
                || !reprotectFailure) {
            return false;
        }
        DrCutoverSessionVO cutover = drCutoverSessionDao != null
                ? drCutoverSessionDao.findLatestActiveByPlanId(plan.getId()) : null;
        if (cutover == null
                || !StringUtils.equalsAnyIgnoreCase(cutover.getState(), "PROMOTED", "COMPLETED", "FAILED_OVER")
                || !StringUtils.equalsIgnoreCase(cutover.getCloudPromotionState(), "PROMOTED")
                || !StringUtils.equalsIgnoreCase(cutover.getTargetPowerState(), "POWERED_ON")
                || !StringUtils.equalsIgnoreCase(cutover.getEngineAckState(), "ACKNOWLEDGED")
                || cutover.getCloudAuthorityGeneration() == null
                || cutover.getCloudAuthorityGeneration() <= 0) {
            return false;
        }
        preserveFailedOverTargetAuthority(plan);
        return true;
    }

    private void preserveServingTargetReplica(DrPlanVO plan) {
        if (plan == null || drReplicaDao == null) {
            return;
        }
        List<DrReplicaVO> replicas = drReplicaDao.listActiveByPlanId(plan.getId());
        if (replicas == null) {
            return;
        }
        for (DrReplicaVO replica : replicas) {
            if (replica == null || replica.getRemoved() != null
                    || replica.getTargetVmId() == null
                    || !StringUtils.equalsIgnoreCase(replica.getActiveSide(), "TARGET")) {
                continue;
            }
            replica.setState(DrConstants.REPLICA_STATE_READY);
            replica.setPowerState("POWERED_ON");
            replica.setActiveSide("TARGET");
            replica.markUpdated();
            drReplicaDao.update(replica.getId(), replica);
        }
    }

    private String projectionFailureMessage(String errorCode, FtctlDrStatusAnswer status, JsonObject runtime) {
        if (isMeaningfulErrorMessage(status.getErrorMessage())) {
            return status.getErrorMessage();
        }
        String runtimeMessage = stringValue(runtime, "error_message");
        if (isMeaningfulErrorMessage(runtimeMessage)) {
            return runtimeMessage;
        }
        if (StringUtils.equalsIgnoreCase(errorCode, DrConstants.ERROR_CUTOVER_MANIFEST_INVALID)) {
            return "The cutover manifest failed schema or cross-field validation";
        }
        if (StringUtils.equalsIgnoreCase(errorCode, DrConstants.ERROR_GUEST_OS_UNRESOLVED)) {
            return "The source guest operating system could not be resolved for cutover preparation";
        }
        if (StringUtils.equalsIgnoreCase(errorCode, DrConstants.ERROR_TARGET_DISK_MAP_MISSING)) {
            return "The cutover manifest is missing a target disk binding";
        }
        if (StringUtils.equalsIgnoreCase(errorCode, DrConstants.ERROR_TARGET_DISK_LOCATOR_INVALID)) {
            return "A target disk provider locator is invalid or is only a display reference";
        }
        if (StringUtils.equalsIgnoreCase(errorCode, DrConstants.ERROR_TARGET_DISK_NOT_DURABLE)) {
            return "A target disk provider object is absent or not readable";
        }
        if (StringUtils.equalsAnyIgnoreCase(errorCode,
                DrConstants.ERROR_FORWARD_TARGET_MAP_MISSING,
                DrConstants.ERROR_FORWARD_TARGET_MAP_GENERATOR_UNAVAILABLE)) {
            return "The forward replication target map is unavailable and was not accepted for data transfer";
        }
        if (StringUtils.equalsAnyIgnoreCase(errorCode,
                DrConstants.ERROR_FORWARD_TARGET_MAP_INVALID,
                DrConstants.ERROR_FORWARD_TARGET_MAP_CARDINALITY_MISMATCH)) {
            return "The forward replication target map does not match the protected source disks";
        }
        if (StringUtils.equalsIgnoreCase(errorCode, DrConstants.ERROR_FORWARD_TARGET_LOCATOR_INVALID)) {
            return "A forward replication target locator could not be canonicalized to a provider URI";
        }
        if (StringUtils.equalsIgnoreCase(errorCode, DrConstants.ERROR_GUEST_PREP_RUNTIME_UNAVAILABLE)) {
            return "The guest preparation runtime or required ISO is unavailable";
        }
        if (StringUtils.equalsIgnoreCase(errorCode, DrConstants.ERROR_GUEST_PREP_SESSION_MISSING)) {
            return "The selected test session is missing or unreadable";
        }
        if (StringUtils.equalsIgnoreCase(errorCode, DrConstants.ERROR_GUEST_PREP_MANIFEST_TOOL_MISSING)) {
            return "The guest preparation manifest tool is not installed on the selected worker";
        }
        if (StringUtils.equalsIgnoreCase(errorCode, DrConstants.ERROR_GUEST_PREP_V2K_RUNTIME_MISSING)) {
            return "The required v2k guest preparation runtime is not installed on the selected worker";
        }
        if (StringUtils.equalsIgnoreCase(errorCode, DrConstants.ERROR_GUEST_PREP_PROFILE_INVALID)) {
            return "The test session does not contain a valid guest preparation profile";
        }
        if (StringUtils.equalsIgnoreCase(errorCode, DrConstants.ERROR_GUEST_PREP_WINPE_ISO_MISSING)) {
            return "The Windows guest preparation WinPE ISO is missing or unreadable";
        }
        if (StringUtils.equalsIgnoreCase(errorCode, DrConstants.ERROR_GUEST_PREP_VIRTIO_ISO_MISSING)) {
            return "The Windows virtio driver ISO is missing or unreadable";
        }
        if (StringUtils.equalsIgnoreCase(errorCode, DrConstants.ERROR_GUEST_PREPARATION_FAILED)) {
            return "Guest preparation failed after cutover manifest validation";
        }
        if (StringUtils.equalsIgnoreCase(errorCode,
                DrConstants.ERROR_TEST_CHECKPOINT_GUEST_FS_INCONSISTENT)) {
            return "The complete checkpoint disk set could not be validated as a boot-consistent guest";
        }
        if (StringUtils.equalsIgnoreCase(errorCode, DrConstants.ERROR_VMWARE_MOVER_UNAVAILABLE)) {
            return "VMware data mover is not available on the selected FTCTL worker";
        }
        if (StringUtils.equalsIgnoreCase(errorCode, DrConstants.ERROR_VMWARE_MOVER_FAILED)) {
            return "VMware data mover failed while copying source disk data";
        }
        if (StringUtils.equalsIgnoreCase(errorCode, DrConstants.ERROR_VMWARE_MOVER_SOURCE_GRAPH_INVALID)) {
            return "VMware data mover could not open the VDDK NBD source graph";
        }
        if (StringUtils.equalsIgnoreCase(errorCode, DrConstants.ERROR_VMWARE_NBDKIT_FAILED)) {
            return "VMware VDDK nbdkit session failed before data transfer";
        }
        if (StringUtils.equalsIgnoreCase(errorCode, DrConstants.ERROR_VMWARE_VDDK_CONNECT_INVALID)) {
            return "VMware VDDK rejected the source connection parameters";
        }
        if (StringUtils.equalsIgnoreCase(errorCode, DrConstants.ERROR_VMWARE_VDDK_THUMBPRINT_UNRESOLVED)) {
            return "VMware VDDK requires a vCenter certificate thumbprint for source-open";
        }
        if (StringUtils.equalsIgnoreCase(errorCode, DrConstants.ERROR_VMWARE_VDDK_EXPORT_UNAVAILABLE)) {
            return "VMware VDDK NBD export is unavailable for the selected source disk";
        }
        if (StringUtils.equalsIgnoreCase(errorCode, DrConstants.ERROR_VMWARE_VDDK_SOURCE_LOCKED)) {
            return "VMware source disk is locked and requires snapshot-backed source-open";
        }
        if (StringUtils.equalsIgnoreCase(errorCode, DrConstants.ERROR_VMWARE_VDDK_OPEN_DENIED)) {
            return "VMware VDDK cannot open the selected source disk because access is denied";
        }
        if (StringUtils.equalsIgnoreCase(errorCode, DrConstants.ERROR_VMWARE_SNAPSHOT_REF_UNRESOLVED)) {
            return "VMware source snapshot was created, but FTCTL_DR could not resolve its MoRef. The source snapshot is marked for cleanup before retry.";
        }
        if (StringUtils.equalsIgnoreCase(errorCode, DrConstants.ERROR_CBT_METRICS_INVALID)) {
            return "Disk data was copied, but FTCTL could not validate the cycle metrics; no checkpoint was committed";
        }
        if (StringUtils.equalsIgnoreCase(errorCode, DrConstants.ERROR_CBT_LOCAL_COMMIT_FAILED)) {
            return "Disk data was copied, but FTCTL could not commit the local cycle metadata; no checkpoint was published";
        }
        String agentMessage = boundedAgentMessage(status.getDetails());
        if (StringUtils.isNotBlank(agentMessage)) {
            return agentMessage;
        }
        return StringUtils.isNotBlank(errorCode)
                ? "FTCTL_DR runtime reported failure: " + errorCode
                : "FTCTL_DR runtime reported failure";
    }

    private boolean isMeaningfulErrorMessage(String message) {
        return StringUtils.isNotBlank(message)
                && !StringUtils.equalsAnyIgnoreCase(StringUtils.trim(message), "OK", "SUCCESS", "ACCEPTED", "DELEGATED");
    }

    private String statusMessage(FtctlDrStatusAnswer status, String defaultMessage) {
        if (status == null) {
            return defaultMessage;
        }
        if (StringUtils.isNotBlank(status.getErrorMessage())) {
            return status.getErrorMessage();
        }
        return StringUtils.defaultIfBlank(boundedAgentMessage(status.getDetails()), defaultMessage);
    }

    private String boundedAgentMessage(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        String message = value.trim();
        if (message.length() > 512 || message.contains("\n") || message.contains("\r")
                || message.startsWith("{") || message.startsWith("[")
                || !isMeaningfulErrorMessage(message)) {
            return null;
        }
        return message;
    }

    private void failRetryableRunFromProjection(DrPlanVO plan, DrRunVO run, FtctlDrStatusAnswer status, JsonObject runtime) {
        String errorCode = StringUtils.defaultIfBlank(status.getErrorCode(),
                StringUtils.defaultIfBlank(stringValue(runtime, "error_code"), DrConstants.ERROR_ENGINE_WORKER_STALLED));
        if (StringUtils.equalsIgnoreCase(errorCode, "not_found")) {
            errorCode = DrConstants.ERROR_RUNTIME_NOT_CREATED;
        }
        if (isWorkerStartStalled(run, runtime) && !StringUtils.equalsIgnoreCase(errorCode, DrConstants.ERROR_ENGINE_BUSY_RETRYABLE)) {
            errorCode = DrConstants.ERROR_ENGINE_WORKER_STALLED;
        }
        String message = retryableProjectionMessage(status, runtime, errorCode);
        Integer retryAfter = integerValue(runtime, "retry_after_sec");
        if (retryAfter == null || retryAfter <= 0) {
            retryAfter = STATUS_REFRESH_WAIT_SECONDS;
        }
        Date now = new Date();
        String compactStatusJson = compactRuntimeStatusJson(status.getStatusJson());
        recordRunProjectionStep(run, DrConstants.STEP_STATE_FAILED, 100, compactStatusJson, errorCode, message);
        run.setState(DrConstants.RUN_STATE_FAILED);
        run.setCompleted(now);
        run.setCurrentStepName("runtime-worker");
        run.setProjectionState("retryable-failed");
        run.setProjectionChecked(now);
        run.setRetryable(true);
        run.setRetryAfterSeconds(retryAfter);
        run.setNextRetryAt(new Date(now.getTime() + retryAfter * 1000L));
        run.setLastStatusJson(compactStatusJson);
        run.setErrorCode(errorCode);
        run.setErrorMessage(message);
        run.markUpdated();
        drRunDao.update(run.getId(), run);
        markPlanProjectionFailed(plan, errorCode, message);
        persistRunProjectionEvent(plan, run, DrConstants.EVENT_RUN_FAILED, DrConstants.EVENT_SEVERITY_WARN,
                message, compactStatusJson);
    }

    private String retryableProjectionMessage(FtctlDrStatusAnswer status, JsonObject runtime, String errorCode) {
        String message = status.getErrorMessage();
        if (StringUtils.isNotBlank(message)) {
            return message;
        }
        String holderCommand = stringValue(runtime, "holder_command");
        if (StringUtils.equalsIgnoreCase(errorCode, DrConstants.ERROR_ENGINE_BUSY_RETRYABLE)) {
            return StringUtils.isNotBlank(holderCommand)
                    ? "FTCTL_DR worker could not start because another engine command is holding the lock: " + holderCommand
                    : "FTCTL_DR worker could not start because the engine lock is busy";
        }
        if (StringUtils.equalsIgnoreCase(errorCode, DrConstants.ERROR_ENGINE_WORKER_STALLED)) {
            return "FTCTL_DR asynchronous worker did not advance after it was accepted";
        }
        return "FTCTL_DR asynchronous worker ended before the target was materialized";
    }

    private void markSyncTargetPending(DrPlanVO plan, DrRunVO run, FtctlDrStatusAnswer status, JsonObject runtime) {
        if (run == null || run.getCompleted() != null) {
            return;
        }
        boolean targetReferencePresent = hasTargetReferenceForDirection(plan);
        boolean durablePresent = hasDurableCheckpoint(status, runtime);
        String message = targetReferencePresent
                ? "FTCTL_DR sync is still materializing target restore point"
                : "FTCTL_DR sync has not materialized the target VM reference yet";
        Double transferPercent = status.getTransferPercent() != null
                ? status.getTransferPercent() : doubleValue(runtime, "transfer_percent");
        Long transferBytesProcessed = status.getTransferBytesProcessed() != null
                ? status.getTransferBytesProcessed() : longValue(runtime, "transfer_bytes_processed");
        Long transferBytesTotal = status.getTransferBytesTotal() != null
                ? status.getTransferBytesTotal() : longValue(runtime, "transfer_bytes_total");
        int progress = DrSyncWorkflowProgress.resolve(status.getProgress(), transferPercent,
                transferBytesProcessed, transferBytesTotal, durablePresent && !targetReferencePresent);
        String compactStatusJson = compactRuntimeStatusJson(status.getStatusJson());
        recordRunProjectionStep(run, DrConstants.STEP_STATE_RUNNING, progress, compactStatusJson, null, message);
        run.setProjectionState(durablePresent && !targetReferencePresent ? "target-materializing" : "syncing");
        run.setProjectionChecked(new Date());
        run.setCurrentStepName(durablePresent && !targetReferencePresent ? "target-materializing" : "runtime-projection");
        run.setLastStatusJson(compactStatusJson);
        run.setErrorCode(null);
        run.setErrorMessage(null);
        run.markUpdated();
        drRunDao.update(run.getId(), run);
        if (durablePresent && !targetReferencePresent && drTargetMaterializationService != null) {
            drTargetMaterializationService.enqueueMaterialization(plan.getId(), run.getId(), compactStatusJson);
        }
    }

    void reconcileDurableTargetMaterialization(DrPlanVO plan, DrRunVO correlationRun,
            FtctlDrStatusAnswer status, JsonObject runtime) {
        if (plan == null || correlationRun == null || drTargetMaterializationService == null
                || drReplicaDao == null
                || !StringUtils.equalsIgnoreCase(plan.getDirection(), DrConstants.DIRECTION_KVM_TO_KVM)
                || !StringUtils.equalsAnyIgnoreCase(correlationRun.getRunType(),
                        DrConstants.RUN_TYPE_SYNC, DrConstants.RUN_TYPE_RECOVER_SYNC)
                || !StringUtils.equalsIgnoreCase(StringUtils.defaultIfBlank(plan.getActiveSide(),
                        DrConstants.AUTHORITY_SIDE_SOURCE), DrConstants.AUTHORITY_SIDE_SOURCE)
                || StringUtils.equalsIgnoreCase(plan.getState(), DrConstants.PLAN_STATE_FAILED_OVER)
                || !hasDurableCheckpoint(status, runtime)) {
            return;
        }
        List<DrReplicaVO> replicas = drReplicaDao.listActiveByPlanId(plan.getId());
        if (replicas == null || replicas.isEmpty()) {
            return;
        }
        boolean reconciliationRequired = false;
        for (DrReplicaVO replica : replicas) {
            if (replica == null || replica.getRemoved() != null || replica.getTargetVmId() == null) {
                continue;
            }
            if (!StringUtils.equalsIgnoreCase(replica.getState(), DrConstants.REPLICA_STATE_READY)
                    || StringUtils.isBlank(replica.getMaterializationDigest())) {
                reconciliationRequired = true;
                break;
            }
        }
        if (!reconciliationRequired) {
            return;
        }
        String compactStatusJson = compactRuntimeStatusJson(StringUtils.defaultIfBlank(
                status != null ? status.getStatusJson() : null, GSON.toJson(runtime)));
        drTargetMaterializationService.enqueueDurableReconciliation(plan.getId(), correlationRun.getId(), compactStatusJson);
    }

    private void recordRunProjectionStep(DrRunVO run, String state, Integer progress, String detailsJson,
            String errorCode, String errorMessage) {
        recordRunStep(run, "runtime-projection", STEP_ORDER_RUNTIME_PROJECTION, state, progress, detailsJson, errorCode, errorMessage);
    }

    private void recordRunStep(DrRunVO run, String name, int order, String state, Integer progress, String detailsJson,
            String errorCode, String errorMessage) {
        if (drRunStepDao == null) {
            return;
        }
        DrRunStepVO step = drRunStepDao.findActiveByRunIdAndStepOrder(run.getId(), order);
        if (step == null) {
            step = new DrRunStepVO(run.getId(), name, order);
        }
        step.setState(state);
        Integer previousProgress = step.getProgress();
        step.setProgress(previousProgress != null && progress != null
                ? Math.max(previousProgress, progress) : progress);
        step.setDetailsJson(compactRuntimeStatusJson(detailsJson));
        step.setErrorCode(errorCode);
        step.setErrorMessage(errorMessage);
        if (step.getStarted() == null) {
            step.setStarted(new Date());
        }
        if (StringUtils.equalsAny(state, DrConstants.STEP_STATE_SUCCEEDED, DrConstants.STEP_STATE_FAILED, DrConstants.STEP_STATE_CANCELED)) {
            step.setCompleted(new Date());
        }
        step.markUpdated();
        if (step.getId() > 0) {
            drRunStepDao.update(step.getId(), step);
        } else {
            drRunStepDao.persist(step);
        }
    }

    private boolean deferRuntimeNotFound(DrPlanVO plan, DrRunVO projectionRun,
            FtctlDrStatusAnswer status, JsonObject runtime) {
        if (!isNotFoundStatus(status, runtime)) {
            return false;
        }
        // The caller already resolved and correlated the active operation Run.
        // Re-querying here creates a non-repeatable-read window in which a
        // concurrent projector can temporarily hide the Run and bypass the
        // runtime creation grace period.
        DrRunVO run = projectionRun;
        if (run == null || run.getCompleted() != null) {
            return false;
        }
        if (isWithinRuntimeCreationGrace(run)) {
            markRunProjectionPending(plan, run, status);
            return true;
        }
        return false;
    }

    private boolean isNotFoundStatus(FtctlDrStatusAnswer status, JsonObject runtime) {
        String errorCode = StringUtils.defaultIfBlank(status.getErrorCode(), stringValue(runtime, "error_code"));
        String result = StringUtils.defaultIfBlank(status.getFtctlResult(), stringValue(runtime, "result"));
        return StringUtils.equalsIgnoreCase(errorCode, "not_found")
                || StringUtils.equalsAnyIgnoreCase(result, "not_found", "run_not_found")
                || Boolean.FALSE.equals(booleanValue(runtime, "run_exists"))
                        && StringUtils.equalsIgnoreCase(stringValue(runtime, "status_scope"), "OPERATION");
    }

    private boolean isStatusTimeout(FtctlDrStatusAnswer status, JsonObject runtime) {
        String errorCode = StringUtils.defaultIfBlank(status.getErrorCode(), stringValue(runtime, "error_code"));
        String result = StringUtils.defaultIfBlank(status.getFtctlResult(), stringValue(runtime, "result"));
        return StringUtils.equalsIgnoreCase(errorCode, DrConstants.ERROR_STATUS_TIMEOUT)
                || StringUtils.equalsIgnoreCase(result, "timeout");
    }

    private boolean isStatusBoundaryFailure(FtctlDrStatusAnswer status) {
        if (status == null || StringUtils.isBlank(status.getErrorCode())) {
            return false;
        }
        return StringUtils.equalsAnyIgnoreCase(status.getErrorCode(),
                "DR_STATUS_INVALID_JSON",
                "DR_STATUS_JSON_INVALID",
                "DR_STATUS_IDENTITY_MISMATCH",
                "DR_STATUS_PAYLOAD_TOO_LARGE",
                "DR_STATUS_TYPE_MISMATCH",
                "DR_STATUS_CYCLE_EVIDENCE_INCOMPLETE",
                "DR_STATUS_CYCLE_SNAPSHOT_INCOHERENT");
    }

    private boolean isWithinRuntimeCreationGrace(DrRunVO run) {
        Date base = run.getAcceptedAt() != null ? run.getAcceptedAt()
                : (run.getDispatchStarted() != null ? run.getDispatchStarted()
                : (run.getStarted() != null ? run.getStarted() : run.getCreated()));
        if (base == null) {
            return true;
        }
        long ageSeconds = (System.currentTimeMillis() - base.getTime()) / 1000L;
        return ageSeconds < RUNTIME_CREATION_GRACE_SECONDS;
    }

    private void markRunProjectionPending(DrPlanVO plan, DrRunVO run, FtctlDrStatusAnswer status) {
        String compactStatusJson = compactRuntimeStatusJson(status.getStatusJson());
        recordRunProjectionStep(run, DrConstants.STEP_STATE_RUNNING, 75, compactStatusJson,
                DrConstants.ERROR_RUNTIME_STARTING, "FTCTL_DR runtime has not created status yet");
        run.setProjectionState(DrConstants.ERROR_RUNTIME_STARTING);
        run.setProjectionChecked(new Date());
        run.setCurrentStepName("runtime-projection");
        run.setLastStatusJson(compactStatusJson);
        run.setErrorCode(null);
        run.setErrorMessage(null);
        run.markUpdated();
        drRunDao.update(run.getId(), run);
        persistRunProjectionEvent(plan, run, DrConstants.EVENT_PROJECTION_REFRESH, DrConstants.EVENT_SEVERITY_INFO,
                "FTCTL_DR runtime is not created yet; projection will retry", compactStatusJson);
    }

    private void markProjectionStale(DrPlanVO plan, FtctlDrStatusAnswer status) {
        DrRunVO run = drRunDao != null ? drRunDao.findActiveByPlanId(plan.getId()) : null;
        if (run == null || run.getCompleted() != null) {
            return;
        }
        String message = statusMessage(status, "FTCTL_DR status refresh timed out");
        String compactStatusJson = compactRuntimeStatusJson(status.getStatusJson());
        recordRunProjectionStep(run, DrConstants.STEP_STATE_RUNNING, 75, compactStatusJson,
                DrConstants.ERROR_PROJECTION_STALE, message);
        run.setProjectionState(DrConstants.ERROR_PROJECTION_STALE);
        run.setProjectionChecked(new Date());
        run.setCurrentStepName("runtime-status-stale");
        run.setLastStatusJson(compactStatusJson);
        run.setErrorCode(DrConstants.ERROR_PROJECTION_STALE);
        run.setErrorMessage(message);
        run.markUpdated();
        drRunDao.update(run.getId(), run);
        persistRunProjectionEvent(plan, run, DrConstants.EVENT_PROJECTION_REFRESH, DrConstants.EVENT_SEVERITY_WARN,
                message, compactStatusJson);
    }

    private DrAdapterResult handleStatusBoundaryFailure(DrPlanVO plan, DrRunVO projectionRun,
            FtctlDrStatusAnswer status, JsonObject details, String defaultMessage) {
        String errorCode = status.getErrorCode();
        String message = statusMessage(status, defaultMessage);
        if (projectionRun != null
                && projectionRun.getCompleted() == null
                && StringUtils.equalsIgnoreCase(projectionRun.getRunType(), DrConstants.RUN_TYPE_FAILOVER)
                && StringUtils.equalsIgnoreCase(errorCode, "DR_STATUS_CYCLE_EVIDENCE_INCOMPLETE")) {
            int retryCount = projectionRun.getRetryCount() != null ? projectionRun.getRetryCount() + 1 : 1;
            projectionRun.setRetryCount(retryCount);
            if (retryCount >= CYCLE_EVIDENCE_MAX_RETRIES) {
                failRunFromProjection(plan, projectionRun, status, parseObject(status.getStatusJson()));
                return DrAdapterResult.failure(errorCode,
                        "FTCTL_DR completed-cycle evidence remained incomplete after bounded retries; "
                                + "failover preparation was aborted",
                        GSON.toJson(details));
            }
            projectionRun.setRetryable(true);
            projectionRun.setRetryAfterSeconds(STATUS_REFRESH_WAIT_SECONDS);
            projectionRun.setNextRetryAt(new Date(System.currentTimeMillis() + STATUS_REFRESH_WAIT_SECONDS * 1000L));
        }
        markProjectionStale(plan, status);
        return DrAdapterResult.retryable(errorCode, message, GSON.toJson(details), STATUS_REFRESH_WAIT_SECONDS);
    }

    private void markPlanProjectionFailed(DrPlanVO plan, String errorCode, String message) {
        DrPlanVO latestPlan = drPlanDao.findById(plan.getId());
        if (latestPlan == null || latestPlan.getRemoved() != null) {
            return;
        }
        latestPlan.setState(DrConstants.PLAN_STATE_ERROR);
        latestPlan.setLastErrorCode(StringUtils.defaultIfBlank(errorCode, DrConstants.ERROR_RUNTIME_NOT_CREATED));
        latestPlan.setLastErrorMessage(message);
        latestPlan.markUpdated();
        drPlanDao.update(latestPlan.getId(), latestPlan);
    }

    private void markReplicaProjectionFailed(DrPlanVO plan, FtctlDrStatusAnswer status, JsonObject runtime,
            String errorCode, String message) {
        if (plan == null || drReplicaDao == null) {
            return;
        }
        String runtimeJson = compactRuntimeStatusJson(StringUtils.defaultIfBlank(status != null ? status.getStatusJson() : null, GSON.toJson(runtime)));
        List<DrReplicaVO> replicas = drReplicaDao.listActiveByPlanId(plan.getId());
        if (replicas == null || replicas.isEmpty()) {
            return;
        }
        for (DrReplicaVO replica : replicas) {
            if (replica == null || replica.getRemoved() != null) {
                continue;
            }
            replica.setState(DrConstants.REPLICA_STATE_ERROR);
            replica.setRuntimeStateJson(runtimeJson);
            replica.markUpdated();
            drReplicaDao.update(replica.getId(), replica);
            markReplicaDisksFailed(replica, runtimeJson, errorCode, message);
        }
    }

    private void markReplicaDisksFailed(DrReplicaVO replica, String runtimeJson, String errorCode, String message) {
        if (replica == null || drReplicaDiskDao == null) {
            return;
        }
        List<DrReplicaDiskVO> disks = drReplicaDiskDao.listActiveByReplicaId(replica.getId());
        if (disks == null || disks.isEmpty()) {
            return;
        }
        JsonObject details = parseObject(runtimeJson);
        details.addProperty("errorCode", errorCode);
        details.addProperty("errorMessage", message);
        String detailsJson = GSON.toJson(details);
        for (DrReplicaDiskVO disk : disks) {
            if (disk == null || disk.getRemoved() != null) {
                continue;
            }
            disk.setState(DrConstants.REPLICA_STATE_ERROR);
            disk.setDetailsJson(detailsJson);
            disk.markUpdated();
            drReplicaDiskDao.update(disk.getId(), disk);
        }
    }

    private void upsertRestorePointFromStatus(DrPlanVO plan, DrRunVO protectionProducerRun,
            FtctlDrStatusAnswer status, JsonObject runtime) {
        FtctlDrCycleSnapshot completedCycle = latestCompletedCycle(status);
        if (!isCoherentCycleSnapshot(plan, status, completedCycle)) {
            markProjectionIntegrityFailure(plan, completedCycle, "DR_STATUS_CYCLE_SNAPSHOT_INCOHERENT");
            return;
        }
        Long completedSequence = status.getLatestCompletedCheckpointSequence();
        if (completedCycle != null) {
            completedSequence = completedCycle.getSequence();
        }
        if (completedSequence == null) {
            completedSequence = parseLong(stringValue(runtime, "latest_completed_checkpoint_sequence"));
        }
        String completedRef = StringUtils.defaultIfBlank(status.getLatestCompletedCheckpointRef(),
                stringValue(runtime, "latest_completed_checkpoint_ref"));
        String completedState = StringUtils.defaultIfBlank(status.getLatestCompletedCheckpointState(),
                stringValue(runtime, "latest_completed_checkpoint_state"));
        if (completedSequence == null || StringUtils.isBlank(completedRef)
                || (!StringUtils.equalsAnyIgnoreCase(completedState, "READY", "COMPLETED", "TARGET_READY"))) {
            return;
        }

        Date targetDurableAt = parseDate(status.getLatestCompletedTargetDurableAt());
        if (targetDurableAt == null) {
            targetDurableAt = parseDate(stringValue(runtime, "latest_completed_target_durable_at"));
        }
        if (targetDurableAt == null) {
            return;
        }

        String checkpointSequence = String.valueOf(completedSequence);
        String sourceSnapshotRef = completedRef;
        byte[] checkpointRefHash = sha256(sourceSnapshotRef);
        if (drPlanDao.acquireInLockTable(plan.getId(), 10) == null) {
            return;
        }
        try {
            DrRestorePointVO restorePoint = drRestorePointDao.findByPlanIdAndCheckpointRefHash(plan.getId(), checkpointRefHash);
            if (restorePoint == null) {
                restorePoint = drRestorePointDao.findByPlanIdAndSourceSnapshotRef(plan.getId(), sourceSnapshotRef);
            }
            if (restorePoint == null) {
                restorePoint = new DrRestorePointVO(plan.getId(), "FTCTL_DR_CHECKPOINT");
                restorePoint.setSourceSnapshotRef(sourceSnapshotRef);
                restorePoint.setCheckpointRefHash(checkpointRefHash);
                restorePoint.setConsistencyLevel("CRASH_CONSISTENT");
            } else if (restorePoint.getTargetReadyAt() != null
                    && restorePoint.getBaselineGeneration() != null
                    && restorePoint.getBaselineGeneration().equals(completedSequence)
                    && StringUtils.equals(restorePoint.getCycleToken(), completedCycle != null
                            ? completedCycle.getCycleToken() : status.getLatestCompletedCycleToken())) {
                return;
            }

            Date sourceCheckpointAt = parseDate(status.getLatestCompletedSourceCheckpointAt());
            if (sourceCheckpointAt == null) {
                sourceCheckpointAt = parseDate(stringValue(runtime, "latest_completed_source_checkpoint_at"));
            }
            restorePoint.setRunId(protectionProducerRun != null ? protectionProducerRun.getId() : null);
            restorePoint.setCheckpointSequence(parseLong(checkpointSequence));
            restorePoint.setCheckpointCycleType(StringUtils.defaultIfBlank(status.getLatestCompletedCheckpointCycleType(),
                    stringValue(runtime, "latest_completed_checkpoint_cycle_type")));
            restorePoint.setSourceCreated(sourceCheckpointAt);
            restorePoint.setTargetReadyAt(targetDurableAt);
            restorePoint.setTargetReadyRpoSeconds(firstInteger(status.getLatestCompletedTargetReadyRpoSeconds(),
                    integerValue(runtime, "latest_completed_target_ready_rpo_seconds")));
            restorePoint.setEffectiveMode(StringUtils.defaultIfBlank(completedCycle != null
                            ? completedCycle.getEffectiveMode() : status.getLatestCompletedEffectiveMode(),
                    stringValue(runtime, "latest_completed_effective_mode")));
            restorePoint.setRequestedMode(StringUtils.defaultIfBlank(completedCycle != null
                            ? completedCycle.getRequestedMode() : status.getLatestCompletedRequestedMode(),
                    stringValue(runtime, "latest_completed_requested_mode")));
            restorePoint.setAutomaticReseed(status.getLatestCompletedAutomaticReseed() != null
                    ? status.getLatestCompletedAutomaticReseed()
                    : booleanValue(runtime, "latest_completed_automatic_reseed"));
            restorePoint.setModeDecisionCode(StringUtils.defaultIfBlank(status.getLatestCompletedModeDecisionCode(),
                    stringValue(runtime, "latest_completed_mode_decision_code")));
            restorePoint.setReseedReason(StringUtils.defaultIfBlank(status.getLatestCompletedReseedReason(),
                    stringValue(runtime, "latest_completed_reseed_reason")));
            restorePoint.setInvalidBaselineDiskCount(status.getLatestCompletedInvalidBaselineDiskCount() != null
                    ? status.getLatestCompletedInvalidBaselineDiskCount()
                    : integerValue(runtime, "latest_completed_invalid_baseline_disk_count"));
            restorePoint.setIncrementalVerified(status.getLatestCompletedIncrementalVerified() != null
                    ? status.getLatestCompletedIncrementalVerified()
                    : booleanValue(runtime, "latest_completed_incremental_verified"));
            restorePoint.setMetricsEstimated(status.getLatestCompletedMetricsEstimated() != null
                    ? status.getLatestCompletedMetricsEstimated()
                    : booleanValue(runtime, "latest_completed_metrics_estimated"));
            restorePoint.setVirtualBytes(status.getLatestCompletedVirtualBytes() != null
                    ? status.getLatestCompletedVirtualBytes() : longValue(runtime, "latest_completed_virtual_bytes"));
            restorePoint.setChangedBytes(status.getLatestCompletedChangedBytes() != null
                    ? status.getLatestCompletedChangedBytes() : longValue(runtime, "latest_completed_changed_bytes"));
            restorePoint.setSourceReadBytes(status.getLatestCompletedSourceReadBytes() != null
                    ? status.getLatestCompletedSourceReadBytes() : longValue(runtime, "latest_completed_source_read_bytes"));
            restorePoint.setTargetWrittenBytes(status.getLatestCompletedTargetWrittenBytes() != null
                    ? status.getLatestCompletedTargetWrittenBytes() : longValue(runtime, "latest_completed_target_written_bytes"));
            restorePoint.setTransferPayloadBytes(status.getLatestCompletedTransferPayloadBytes() != null
                    ? status.getLatestCompletedTransferPayloadBytes() : longValue(runtime, "latest_completed_transfer_payload_bytes"));
            restorePoint.setChangedExtentCount(status.getLatestCompletedChangedExtentCount() != null
                    ? status.getLatestCompletedChangedExtentCount() : longValue(runtime, "latest_completed_changed_extent_count"));
            restorePoint.setDurationMs(status.getLatestCompletedDurationMs() != null
                    ? status.getLatestCompletedDurationMs() : longValue(runtime, "latest_completed_duration_ms"));
            restorePoint.setThroughputBps(status.getLatestCompletedThroughputBps() != null
                    ? status.getLatestCompletedThroughputBps() : longValue(runtime, "latest_completed_throughput_bps"));
            restorePoint.setBaselineGeneration(completedCycle != null && completedCycle.getBaselineGeneration() != null
                    ? completedCycle.getBaselineGeneration() : longValue(runtime, "latest_completed_baseline_generation"));
            restorePoint.setCycleToken(StringUtils.defaultIfBlank(completedCycle != null
                            ? completedCycle.getCycleToken() : status.getLatestCompletedCycleToken(),
                    stringValue(runtime, "latest_completed_cycle_token")));
            restorePoint.setState("READY");
            restorePoint.markUpdated();
            if (restorePoint.getId() > 0) {
                drRestorePointDao.update(restorePoint.getId(), restorePoint);
            } else {
                drRestorePointDao.persist(restorePoint);
            }
            enforceCheckpointRetention(plan);
        } finally {
            drPlanDao.releaseFromLockTable(plan.getId());
        }
    }

    private void enforceCheckpointRetention(DrPlanVO plan) {
        int retention = DEFAULT_CHECKPOINT_RETENTION;
        JsonObject schedule = parseObject(plan.getScheduleJson());
        Integer configured = firstInteger(integerValue(schedule, "retentionCount"), integerValue(schedule, "retention_count"));
        if (configured != null && configured > 0) {
            retention = Math.min(configured, 1000);
        }
        List<DrRestorePointVO> checkpoints = drRestorePointDao.listActiveByPlanId(plan.getId());
        for (int index = retention; index < checkpoints.size(); index++) {
            DrRestorePointVO expired = checkpoints.get(index);
            expired.markRemoved();
            drRestorePointDao.update(expired.getId(), expired);
        }
    }

    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private Long parseLong(String value) {
        try {
            return StringUtils.isBlank(value) ? null : Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean isSyncTargetReady(DrPlanVO plan, FtctlDrStatusAnswer status, JsonObject runtime, String runtimeState) {
        String cycleState = StringUtils.upperCase(stringValue(runtime, "cycle_state"), Locale.ROOT);
        String checkpointState = StringUtils.upperCase(stringValue(runtime, "current_checkpoint_state"), Locale.ROOT);
        boolean completedContinuousCycle = StringUtils.equals(runtimeState, "SYNCING")
                && StringUtils.equalsAny(cycleState, "IDLE", "COMPLETED")
                && StringUtils.equalsAny(checkpointState, "COMPLETED", "READY", "TARGET_READY");
        if (!StringUtils.equalsAny(runtimeState, "READY", "TARGET_READY") && !completedContinuousCycle) {
            return false;
        }
        if (!hasDurableCheckpoint(status, runtime)) {
            return false;
        }
        boolean cloudManagedKvmTarget = StringUtils.endsWithIgnoreCase(plan.getDirection(), "_KVM")
                && hasTargetReferenceForDirection(plan);
        if ((!cloudManagedKvmTarget
                && (isExplicitFalse(status != null ? status.getTargetVmPresent() : null,
                        booleanValue(runtime, "target_vm_present"))
                || isExplicitFalse(status != null ? status.getTargetNetworkPresent() : null,
                        booleanValue(runtime, "target_network_present"))))
                || isExplicitFalse(status != null ? status.getTargetStoragePresent() : null,
                        booleanValue(runtime, "target_storage_present"))
                || isExplicitFalse(status != null ? status.getRestorePointPresent() : null,
                        booleanValue(runtime, "restore_point_present"))) {
            return false;
        }
        if (!hasTargetReferenceForDirection(plan)) {
            return false;
        }
        return drRestorePointDao != null && drRestorePointDao.findLatestTargetReadyByPlanId(plan.getId()) != null;
    }

    private boolean hasDurableCheckpoint(FtctlDrStatusAnswer status, JsonObject runtime) {
        return parseDate(status != null ? status.getLastTargetDurableAt() : null) != null
                || parseDate(stringValue(runtime, "last_target_durable_at")) != null
                || parseDate(status != null ? status.getLatestCompletedTargetDurableAt() : null) != null
                || parseDate(stringValue(runtime, "latest_completed_target_durable_at")) != null;
    }

    private boolean hasTargetReferenceForDirection(DrPlanVO plan) {
        if (plan == null || drReplicaDao == null) {
            return false;
        }
        List<DrReplicaVO> replicas = drReplicaDao.listActiveByPlanId(plan.getId());
        if (replicas == null || replicas.isEmpty()) {
            return false;
        }
        boolean targetAbleStack = StringUtils.endsWithIgnoreCase(plan.getDirection(), "_KVM");
        for (DrReplicaVO replica : replicas) {
            if (replica == null || replica.getRemoved() != null) {
                continue;
            }
            if (targetAbleStack && replica.getTargetVmId() != null) {
                return true;
            }
            if (!targetAbleStack && (StringUtils.isNotBlank(replica.getTargetExternalRef()) || replica.getTargetVmId() != null)) {
                return true;
            }
        }
        return false;
    }

    private String toPlanState(String runtimeState) {
        String normalized = StringUtils.upperCase(runtimeState, Locale.ROOT);
        if (StringUtils.equalsAny(normalized, "RUNNING", "SYNCING", "SEEDING")) {
            return DrConstants.PLAN_STATE_SYNCING;
        }
        if (StringUtils.equalsAny(normalized, "TARGET_READY", "READY")) {
            return DrConstants.PLAN_STATE_READY;
        }
        if (StringUtils.equalsAny(normalized, "DEGRADED", "RPO_EXCEEDED", "STALE")) {
            return DrConstants.HEALTH_DEGRADED;
        }
        if (StringUtils.equalsAny(normalized, "PAUSED")) {
            return DrConstants.PLAN_STATE_PAUSED;
        }
        if (StringUtils.equalsAny(normalized, "TESTING", "TEST_RUNNING")) {
            return DrConstants.PLAN_STATE_TESTING;
        }
        if (StringUtils.equalsAny(normalized, "FAILED_OVER", "PROMOTED")) {
            return DrConstants.PLAN_STATE_FAILED_OVER;
        }
        if (StringUtils.equalsAny(normalized, "ERROR", "FAILED")) {
            return DrConstants.PLAN_STATE_ERROR;
        }
        return null;
    }

    private Date parseDate(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        try {
            return DateUtil.parseTZDateString(value);
        } catch (ParseException e) {
            return DateUtil.parseDateString(TimeZone.getTimeZone("GMT"), value);
        }
    }

    String classifyRpoFreshness(boolean committedTargetAuthority, Date durableAt, long rpoAge, long rpoLimit) {
        if (committedTargetAuthority) {
            return "WITHIN_RPO";
        }
        long normalizedLimit = Math.max(1L, rpoLimit);
        if (durableAt == null || rpoAge > normalizedLimit) {
            return "OVERDUE";
        }
        return rpoAge >= Math.max(1L, (normalizedLimit * 80L) / 100L)
                ? "RPO_DUE_SOON" : "WITHIN_RPO";
    }

    private JsonObject parseObject(String json) {
        if (StringUtils.isBlank(json)) {
            return new JsonObject();
        }
        try {
            JsonElement parsed = JsonParser.parseString(json);
            return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException e) {
            return new JsonObject();
        }
    }

    private String compactRuntimeStatusJson(String statusJson) {
        if (StringUtils.isBlank(statusJson)) {
            return null;
        }
        JsonObject runtime = parseObject(statusJson);
        if (runtime.entrySet().isEmpty()) {
            return statusJson;
        }
        JsonObject compact = new JsonObject();
        copyJsonProperty(runtime, compact, "command");
        copyJsonProperty(runtime, compact, "result");
        copyJsonProperty(runtime, compact, "plan_uuid");
        copyJsonProperty(runtime, compact, "run_uuid");
        copyJsonProperty(runtime, compact, "action");
        copyJsonProperty(runtime, compact, "state");
        copyJsonProperty(runtime, compact, "step");
        copyJsonProperty(runtime, compact, "progress");
        copyJsonProperty(runtime, compact, "external_job_ref");
        copyJsonProperty(runtime, compact, "runtime_exists");
        copyJsonProperty(runtime, compact, "profile_exists");
        copyJsonProperty(runtime, compact, "run_exists");
        copyJsonProperty(runtime, compact, "last_source_checkpoint_at");
        copyJsonProperty(runtime, compact, "last_target_durable_at");
        copyJsonProperty(runtime, compact, "target_ready_rpo_seconds");
        copyJsonProperty(runtime, compact, "target_materialized");
        copyJsonProperty(runtime, compact, "target_vm_present");
        copyJsonProperty(runtime, compact, "target_storage_present");
        copyJsonProperty(runtime, compact, "target_network_present");
        copyJsonProperty(runtime, compact, "restore_point_present");
        copyJsonProperty(runtime, compact, "target_vm_id");
        copyJsonProperty(runtime, compact, "target_external_ref");
        copyJsonProperty(runtime, compact, "source_firmware");
        copyJsonProperty(runtime, compact, "source_secure_boot");
        copyJsonProperty(runtime, compact, "source_boot_hardware");
        copyJsonProperty(runtime, compact, "source_boot_hardware_version");
        copyJsonProperty(runtime, compact, "source_hardware_fingerprint");
        copyJsonProperty(runtime, compact, "source_hardware_fingerprint_version");
        copyJsonProperty(runtime, compact, "source_runtime_quiesce_state");
        copyJsonProperty(runtime, compact, "source_runtime_quiesce_mode");
        copyJsonProperty(runtime, compact, "source_runtime_quiesce_owner_run");
        copyJsonProperty(runtime, compact, "source_runtime_quiesced_at");
        copyJsonProperty(runtime, compact, "source_runtime_quiesce_released_at");
        copyJsonProperty(runtime, compact, "cutover_source_disk_map_path");
        copyJsonProperty(runtime, compact, "cutover_source_disk_map_sha256");
        copyJsonProperty(runtime, compact, "target_boot_type");
        copyJsonProperty(runtime, compact, "target_boot_mode");
        copyJsonProperty(runtime, compact, "target_io_policy");
        copyJsonProperty(runtime, compact, "target_iothreads");
        copyJsonProperty(runtime, compact, "error_code");
        copyJsonProperty(runtime, compact, "error_message");
        copyJsonProperty(runtime, compact, "driver_exit_code");
        copyJsonProperty(runtime, compact, "updated_at");
        copyJsonProperty(runtime, compact, "driver");
        copyJsonProperty(runtime, compact, "driver_state");
        copyJsonProperty(runtime, compact, "disk_map_path");
        copyJsonProperty(runtime, compact, "source_disk_map_path");
        copyJsonProperty(runtime, compact, "target_disk_map_path");
        copyJsonProperty(runtime, compact, "disk_map_role");
        copyJsonProperty(runtime, compact, "target_disk_count");
        copyJsonProperty(runtime, compact, "target_disk_invalid_count");
        copyJsonProperty(runtime, compact, "manifest_schema_version");
        copyJsonProperty(runtime, compact, "manifest_sha256");
        copyJsonProperty(runtime, compact, "guestprep_checkpoint_sequence");
        copyJsonProperty(runtime, compact, "manifest_path");
        copyJsonProperty(runtime, compact, "checkpoint_path");
        copyJsonProperty(runtime, compact, "cbt_status_path");
        copyJsonProperty(runtime, compact, "source_open_status_path");
        copyJsonProperty(runtime, compact, "source_snapshot_status_path");
        compact.add("cbt_status", compactStatusObject(runtime, "cbt_status"));
        compact.add("source_open", compactStatusObject(runtime, "source_open"));
        compact.add("source_snapshot", compactStatusObject(runtime, "source_snapshot"));
        copyJsonProperty(runtime, compact, "scheduler_state");
        copyJsonProperty(runtime, compact, "scheduler_desired_state");
        copyJsonProperty(runtime, compact, "release_state");
        copyJsonProperty(runtime, compact, "profile_removed");
        copyJsonProperty(runtime, compact, "runtime_removed");
        copyJsonProperty(runtime, compact, "vm_mutated");
        copyJsonProperty(runtime, compact, "storage_mutated");
        copyJsonProperty(runtime, compact, "network_mutated");
        copyJsonProperty(runtime, compact, "released_at");
        copyJsonProperty(runtime, compact, "scheduler_pid_alive");
        copyJsonProperty(runtime, compact, "runtime_generation");
        copyJsonProperty(runtime, compact, "scheduler_session_uuid");
        copyJsonProperty(runtime, compact, "scheduler_lease_epoch");
        copyJsonProperty(runtime, compact, "authority_sequence");
        copyJsonProperty(runtime, compact, "plan_cycle_sequence");
        copyJsonProperty(runtime, compact, "scheduler_health");
        copyJsonProperty(runtime, compact, "replication_activity");
        copyJsonProperty(runtime, compact, "protection_state");
        copyJsonProperty(runtime, compact, "active_worker_run_uuid");
        copyJsonProperty(runtime, compact, "active_worker_pid");
        copyJsonProperty(runtime, compact, "active_worker_start_ticks");
        copyJsonProperty(runtime, compact, "worker_heartbeat_at");
        copyJsonProperty(runtime, compact, "control_request_run_uuid");
        copyJsonProperty(runtime, compact, "owner_matched");
        copyJsonProperty(runtime, compact, "baseline_state");
        copyJsonProperty(runtime, compact, "reseed_reason");
        copyJsonProperty(runtime, compact, "control_protocol_version");
        copyJsonProperty(runtime, compact, "control_generation");
        copyJsonProperty(runtime, compact, "control_ack_generation");
        copyJsonProperty(runtime, compact, "control_state");
        copyJsonProperty(runtime, compact, "cycle_state");
        copyJsonProperty(runtime, compact, "transition_state");
        copyJsonProperty(runtime, compact, "transition_action");
        copyJsonProperty(runtime, compact, "transition_quiesced_at");
        copyJsonProperty(runtime, compact, "checkpoint_lease_state");
        copyJsonProperty(runtime, compact, "test_checkpoint_sequence");
        copyJsonProperty(runtime, compact, "test_checkpoint_seal_state");
        copyJsonProperty(runtime, compact, "test_checkpoint_integrity_state");
        copyJsonProperty(runtime, compact, "test_checkpoint_path");
        copyJsonProperty(runtime, compact, "worker_pid");
        copyJsonProperty(runtime, compact, "worker_state");
        copyJsonProperty(runtime, compact, "worker_started_at");
        copyJsonProperty(runtime, compact, "worker_updated_at");
        copyJsonProperty(runtime, compact, "worker_exit_code");
        copyJsonProperty(runtime, compact, "transfer_activity_state");
        copyJsonProperty(runtime, compact, "transfer_payload_bytes");
        copyJsonProperty(runtime, compact, "transfer_progress_schema_version");
        copyJsonProperty(runtime, compact, "transfer_cycle_sequence");
        copyJsonProperty(runtime, compact, "transfer_sample_sequence");
        copyJsonProperty(runtime, compact, "transfer_phase");
        copyJsonProperty(runtime, compact, "transfer_mode");
        copyJsonProperty(runtime, compact, "transfer_bytes_total");
        copyJsonProperty(runtime, compact, "transfer_bytes_processed");
        copyJsonProperty(runtime, compact, "transfer_source_read_bytes");
        copyJsonProperty(runtime, compact, "transfer_target_written_bytes");
        copyJsonProperty(runtime, compact, "transfer_verified_bytes");
        copyJsonProperty(runtime, compact, "transfer_percent");
        copyJsonProperty(runtime, compact, "transfer_throughput_bps");
        copyJsonProperty(runtime, compact, "transfer_eta_seconds");
        copyJsonProperty(runtime, compact, "transfer_current_disk_index");
        copyJsonProperty(runtime, compact, "transfer_disk_count");
        copyJsonProperty(runtime, compact, "transfer_progress_estimated");
        copyJsonProperty(runtime, compact, "transfer_progress_sample_epoch_ms");
        copyJsonProperty(runtime, compact, "transfer_progress_stale");
        copyJsonProperty(runtime, compact, "retryable");
        copyJsonProperty(runtime, compact, "retry_after_sec");
        copyJsonProperty(runtime, compact, "lock_file");
        copyJsonProperty(runtime, compact, "holder_pid");
        copyJsonProperty(runtime, compact, "holder_command");
        copyJsonProperty(runtime, compact, "holder_age_sec");
        copyJsonProperty(runtime, compact, "checkpoint_sequence");
        copyJsonProperty(runtime, compact, "active_side");
        copyJsonProperty(runtime, compact, "failover_mode");
        copyJsonProperty(runtime, compact, "failover_session_id");
        copyJsonProperty(runtime, compact, "failover_restore_point_ref");
        copyJsonProperty(runtime, compact, "source_fence_state");
        copyJsonProperty(runtime, compact, "scheduler_recovery_state");
        copyJsonProperty(runtime, compact, "target_power_state");
        copyJsonProperty(runtime, compact, "target_promotion_state");
        copyJsonProperty(runtime, compact, "boot_validation_state");
        copyJsonProperty(runtime, compact, "cloud_cutover_session_id");
        copyJsonProperty(runtime, compact, "cloud_authority_generation");
        copyJsonProperty(runtime, compact, "engine_ack_state");
        copyJsonProperty(runtime, compact, "engine_ack_at");
        copyJsonProperty(runtime, compact, "source_power_state");
        copyJsonProperty(runtime, compact, "source_promotion_state");
        copyJsonProperty(runtime, compact, "failback_session_id");
        copyJsonProperty(runtime, compact, "failback_phase");
        copyJsonProperty(runtime, compact, "cloud_lifecycle_state");
        copyJsonProperty(runtime, compact, "failback_commit_outcome");
        copyJsonProperty(runtime, compact, "failback_commit_phase");
        copyJsonProperty(runtime, compact, "rollback_state");
        copyJsonProperty(runtime, compact, "rollback_generation");
        copyJsonProperty(runtime, compact, "failback_restore_point_ref");
        copyJsonProperty(runtime, compact, "reprotect_session_id");
        copyJsonProperty(runtime, compact, "reprotect_restore_point_ref");
        copyJsonProperty(runtime, compact, "reverse_direction");
        copyJsonProperty(runtime, compact, "replication_direction");
        copyJsonProperty(runtime, compact, "provider_pair");
        copyJsonProperty(runtime, compact, "route_contract_version");
        copyJsonProperty(runtime, compact, "rto_actual_seconds");
        copyJsonProperty(runtime, compact, "events_offset");
        return GSON.toJson(compact);
    }

    private JsonObject compactStatusObject(JsonObject runtime, String key) {
        JsonObject source = objectValue(runtime, key);
        JsonObject compact = new JsonObject();
        copyJsonProperty(source, compact, "checked");
        copyJsonProperty(source, compact, "ready");
        copyJsonProperty(source, compact, "enabled");
        copyJsonProperty(source, compact, "schemaVersion");
        copyJsonProperty(source, compact, "lifecycleState");
        copyJsonProperty(source, compact, "vmConfigSignal");
        copyJsonProperty(source, compact, "created");
        copyJsonProperty(source, compact, "cleanupRequired");
        copyJsonProperty(source, compact, "error_code");
        copyJsonProperty(source, compact, "message");
        copyJsonProperty(source, compact, "vmRef");
        copyJsonProperty(source, compact, "snapshotName");
        copyJsonProperty(source, compact, "snapshotRefPresent");
        copyJsonProperty(source, compact, "snapshotRef");
        copyJsonProperty(source, compact, "sourceVmdkPathPresent");
        copyJsonProperty(source, compact, "cbtDiskId");
        copyJsonProperty(source, compact, "sourceDiskRef");
        copyJsonProperty(source, compact, "govcBin");
        copyJsonProperty(source, compact, "resolveMethod");
        copyJsonProperty(source, compact, "checkedAtEpochMs");
        if (!compact.has("cbtDiskId") && source.has("disks") && source.get("disks").isJsonArray()
                && source.getAsJsonArray("disks").size() > 0 && source.getAsJsonArray("disks").get(0).isJsonObject()) {
            copyJsonProperty(source.getAsJsonArray("disks").get(0).getAsJsonObject(), compact, "cbtDiskId");
        }
        return compact;
    }

    private JsonObject objectValue(JsonObject object, String name) {
        if (object == null || !object.has(name) || object.get(name).isJsonNull()) {
            return new JsonObject();
        }
        JsonElement element = object.get(name);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    private JsonObject firstObject(JsonObject object, String... names) {
        if (object == null || names == null) {
            return new JsonObject();
        }
        for (String name : names) {
            JsonObject value = objectValue(object, name);
            if (!value.entrySet().isEmpty()) {
                return value;
            }
        }
        return new JsonObject();
    }

    private void copyJsonProperty(JsonObject source, JsonObject target, String name) {
        if (source == null || target == null || !source.has(name) || source.get(name).isJsonNull()) {
            return;
        }
        JsonElement element = source.get(name);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return;
        }
        target.add(name, JsonParser.parseString(GSON.toJson(element)));
    }

    private String stringValue(JsonObject object, String name) {
        if (object == null || !object.has(name) || object.get(name).isJsonNull()) {
            return null;
        }
        return object.get(name).getAsString();
    }

    private Integer integerValue(JsonObject object, String name) {
        if (object == null || !object.has(name) || object.get(name).isJsonNull()) {
            return null;
        }
        try {
            return object.get(name).getAsInt();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Long longValue(JsonObject object, String name) {
        if (object == null || !object.has(name) || object.get(name).isJsonNull()) {
            return null;
        }
        try {
            return object.get(name).getAsLong();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Double doubleValue(JsonObject object, String name) {
        if (object == null || !object.has(name) || object.get(name).isJsonNull()) {
            return null;
        }
        try {
            return object.get(name).getAsDouble();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Boolean booleanValue(JsonObject object, String name) {
        if (object == null || !object.has(name) || object.get(name).isJsonNull()) {
            return null;
        }
        try {
            return object.get(name).getAsBoolean();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private boolean isExplicitFalse(Boolean first, Boolean second) {
        return Boolean.FALSE.equals(first) || Boolean.FALSE.equals(second);
    }

    private Integer firstInteger(Integer first, Integer fallback) {
        return first != null ? first : fallback;
    }

    private Date firstDate(Date... values) {
        if (values == null) {
            return null;
        }
        for (Date value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private JsonObject buildDetails(DrPlanVO plan, Long hostId, FtctlDrStatusAnswer status) {
        JsonObject details = new JsonObject();
        details.addProperty("engineType", getEngineType());
        details.addProperty("engineBindingType", getEngineBindingType());
        details.addProperty("planId", plan.getId());
        details.addProperty("planUuid", plan.getUuid());
        details.addProperty("agentHostId", hostId);
        if (status != null) {
            JsonObject runtime = parseObject(status.getStatusJson());
            details.addProperty("result", status.getFtctlResult());
            details.addProperty("state", status.getState());
            details.addProperty("step", status.getStep());
            details.addProperty("progress", status.getProgress());
            details.addProperty("lastSourceCheckpointAt", status.getLastSourceCheckpointAt());
            details.addProperty("lastTargetDurableAt", status.getLastTargetDurableAt());
            details.addProperty("targetReadyRpoSeconds", status.getTargetReadyRpoSeconds());
            details.addProperty("targetMaterialized", booleanValue(runtime, "target_materialized"));
            details.addProperty("targetVmPresent", booleanValue(runtime, "target_vm_present"));
            details.addProperty("targetStoragePresent", booleanValue(runtime, "target_storage_present"));
            details.addProperty("targetNetworkPresent", booleanValue(runtime, "target_network_present"));
            details.addProperty("restorePointPresent", booleanValue(runtime, "restore_point_present"));
            details.addProperty("eventsOffset", status.getEventsOffset());
            details.addProperty("errorCode", status.getErrorCode());
            details.addProperty("errorMessage", status.getErrorMessage());
            details.addProperty("failedComponent", status.getFailedComponent());
            details.addProperty("dataCommitState", status.getDataCommitState());
            details.addProperty("dataCopied", status.getDataCopied());
            details.addProperty("metadataCommitted", status.getMetadataCommitted());
            details.addProperty("targetDurable", status.getTargetDurable());
            details.addProperty("cycleRetryMode", status.getCycleRetryMode());
            details.addProperty("exitCode", status.getExitCode());
            details.addProperty("sourceDiskMapPath", StringUtils.defaultIfBlank(status.getSourceDiskMapPath(), stringValue(runtime, "source_disk_map_path")));
            details.addProperty("targetDiskMapPath", StringUtils.defaultIfBlank(status.getTargetDiskMapPath(), stringValue(runtime, "target_disk_map_path")));
            details.addProperty("diskMapRole", StringUtils.defaultIfBlank(status.getDiskMapRole(), stringValue(runtime, "disk_map_role")));
            details.addProperty("targetDiskCount", firstInteger(status.getTargetDiskCount(), integerValue(runtime, "target_disk_count")));
            details.addProperty("targetDiskInvalidCount", firstInteger(status.getTargetDiskInvalidCount(), integerValue(runtime, "target_disk_invalid_count")));
            details.addProperty("workerState", stringValue(runtime, "worker_state"));
            details.addProperty("workerPid", integerValue(runtime, "worker_pid"));
            details.addProperty("workerStartedAt", stringValue(runtime, "worker_started_at"));
            details.addProperty("workerUpdatedAt", stringValue(runtime, "worker_updated_at"));
            details.addProperty("workerExitCode", integerValue(runtime, "worker_exit_code"));
            details.addProperty("terminalSource", StringUtils.defaultIfBlank(status.getTerminalSource(),
                    stringValue(runtime, "terminal_source")));
            details.addProperty("terminalVersion", firstInteger(status.getTerminalVersion(),
                    integerValue(runtime, "terminal_version")));
            details.addProperty("terminalPublicationPending", status.getTerminalPublicationPending() != null
                    ? status.getTerminalPublicationPending() : booleanValue(runtime, "terminal_publication_pending"));
            details.addProperty("terminalPublicationPendingSince",
                    StringUtils.defaultIfBlank(status.getTerminalPublicationPendingSince(),
                            stringValue(runtime, "terminal_publication_pending_since")));
            details.addProperty("failurePhase", StringUtils.defaultIfBlank(status.getFailurePhase(),
                    stringValue(runtime, "failure_phase")));
            details.addProperty("controlProtocolVersion", firstInteger(status.getControlProtocolVersion(), integerValue(runtime, "control_protocol_version")));
            details.addProperty("controlGeneration", status.getControlGeneration() != null ? status.getControlGeneration() : parseLong(stringValue(runtime, "control_generation")));
            details.addProperty("controlAckGeneration", status.getControlAckGeneration() != null ? status.getControlAckGeneration() : parseLong(stringValue(runtime, "control_ack_generation")));
            details.addProperty("controlState", StringUtils.defaultIfBlank(status.getControlState(), stringValue(runtime, "control_state")));
            details.addProperty("cycleState", StringUtils.defaultIfBlank(status.getCycleState(), stringValue(runtime, "cycle_state")));
            details.addProperty("transitionState", StringUtils.defaultIfBlank(status.getTransitionState(), stringValue(runtime, "transition_state")));
            details.addProperty("checkpointLeaseState", StringUtils.defaultIfBlank(status.getCheckpointLeaseState(), stringValue(runtime, "checkpoint_lease_state")));
            details.add("sourceSnapshot", compactStatusObject(runtime, "source_snapshot"));
            details.addProperty("retryable", booleanValue(runtime, "retryable"));
            details.addProperty("retryAfterSeconds", integerValue(runtime, "retry_after_sec"));
            details.addProperty("lockFile", stringValue(runtime, "lock_file"));
            details.addProperty("holderPid", integerValue(runtime, "holder_pid"));
            details.addProperty("holderCommand", stringValue(runtime, "holder_command"));
            details.addProperty("holderAgeSeconds", integerValue(runtime, "holder_age_sec"));
            details.addProperty("activeSide", stringValue(runtime, "active_side"));
            details.addProperty("failoverMode", stringValue(runtime, "failover_mode"));
            details.addProperty("failoverSessionId", stringValue(runtime, "failover_session_id"));
            details.addProperty("failoverRestorePointRef", stringValue(runtime, "failover_restore_point_ref"));
            details.addProperty("targetPowerState", stringValue(runtime, "target_power_state"));
            details.addProperty("targetPromotionState", stringValue(runtime, "target_promotion_state"));
            details.addProperty("sourcePowerState", stringValue(runtime, "source_power_state"));
            details.addProperty("sourcePromotionState", stringValue(runtime, "source_promotion_state"));
            details.addProperty("failbackSessionId", stringValue(runtime, "failback_session_id"));
            details.addProperty("failbackRestorePointRef", stringValue(runtime, "failback_restore_point_ref"));
            details.addProperty("failbackCommitOutcome", stringValue(runtime, "failback_commit_outcome"));
            details.addProperty("failbackCommitPhase", stringValue(runtime, "failback_commit_phase"));
            details.addProperty("rollbackState", stringValue(runtime, "rollback_state"));
            details.addProperty("rollbackGeneration", longValue(runtime, "rollback_generation"));
            details.addProperty("reprotectSessionId", stringValue(runtime, "reprotect_session_id"));
            details.addProperty("reprotectRestorePointRef", stringValue(runtime, "reprotect_restore_point_ref"));
            details.addProperty("reverseDirection", stringValue(runtime, "reverse_direction"));
            details.addProperty("replicationDirection", StringUtils.defaultIfBlank(status.getReplicationDirection(),
                    stringValue(runtime, "replication_direction")));
            details.addProperty("providerPair", StringUtils.defaultIfBlank(status.getProviderPair(),
                    stringValue(runtime, "provider_pair")));
            details.addProperty("routeContractVersion", status.getRouteContractVersion() != null
                    ? status.getRouteContractVersion() : integerValue(runtime, "route_contract_version"));
            details.addProperty("rtoActualSeconds", integerValue(runtime, "rto_actual_seconds"));
        }
        return details;
    }

    private void persistRunProjectionEvent(DrPlanVO plan, DrRunVO run, String eventType, String severity,
            String message, String detailsJson) {
        DrEventVO event = new DrEventVO(eventType, severity, DrConstants.EVENT_SOURCE_FTCTL_DR);
        event.setPlanId(plan.getId());
        event.setRunId(run != null ? run.getId() : null);
        event.setMessage(message);
        event.setDetailsJson(compactRuntimeStatusJson(detailsJson));
        drEventDao.persist(event);
    }
}
