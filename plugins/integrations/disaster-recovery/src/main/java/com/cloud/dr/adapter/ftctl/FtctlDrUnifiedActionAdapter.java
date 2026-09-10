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

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.FtctlDrActionAnswer;
import com.cloud.agent.api.FtctlDrActionCommand;
import com.cloud.agent.api.FtctlDrCapabilitiesAnswer;
import com.cloud.agent.api.FtctlDrCapabilitiesCommand;
import com.cloud.agent.api.FtctlDrStatusAnswer;
import com.cloud.agent.api.FtctlDrStatusCommand;
import com.cloud.agent.api.FtctlDrReversePreflightAnswer;
import com.cloud.agent.api.FtctlDrReversePreflightCommand;
import com.cloud.dr.DrConstants;
import com.cloud.dr.DrFailbackPreflightResult;
import com.cloud.dr.DrFailbackPreflightService;
import com.cloud.dr.DrPlanVO;
import com.cloud.dr.DrPlanRuntimeVO;
import com.cloud.dr.DrPlanReadinessValidator;
import com.cloud.dr.DrPlanOwnedTransportService;
import com.cloud.dr.DrReprotectAuthoritySpec;
import com.cloud.dr.DrReprotectPreflightResult;
import com.cloud.dr.DrReprotectPreflightService;
import com.cloud.dr.DrRestorePointVO;
import com.cloud.dr.DrReplicaVO;
import com.cloud.dr.DrResolvedSiteCredential;
import com.cloud.dr.DrRunVO;
import com.cloud.dr.DrSyncCycleVO;
import com.cloud.dr.DrWorkerPlacementService;
import com.cloud.dr.DrWorkerRole;
import com.cloud.dr.DrSiteCredentialService;
import com.cloud.dr.DrSiteVO;
import com.cloud.dr.adapter.DrAdapterResult;
import com.cloud.dr.adapter.DrExecutionContext;
import com.cloud.dr.adapter.DrReplicationEngine;
import com.cloud.dr.dao.DrSiteDao;
import com.cloud.dr.dao.DrRestorePointDao;
import com.cloud.dr.dao.DrReplicaDao;
import com.cloud.dr.dao.DrPlanRuntimeDao;
import com.cloud.dr.dao.DrSyncCycleDao;
import com.cloud.dr.health.DrSiteProbeSupport;
import com.cloud.dr.inventory.DrSourceHardwareInventoryService;
import com.cloud.dr.inventory.DrSourceVmHardware;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.host.DetailVO;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.host.dao.HostDetailsDao;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class FtctlDrUnifiedActionAdapter extends ManagerBase implements DrReplicationEngine {
    @Inject private com.cloud.dr.DrTestCleanupRecoveryStore testCleanupRecovery;
    @Inject private com.cloud.dr.dao.DrRunDao cleanupRecoveryRunDao;

    private static final Logger LOGGER = LogManager.getLogger(FtctlDrUnifiedActionAdapter.class);
    private static final Gson GSON = new Gson();
    private static final int AGENT_ACCEPT_TIMEOUT_SECONDS = 30;
    private static final int VCENTER_THUMBPRINT_TIMEOUT_MS = 10000;
    private static final String TEST_ARTIFACT_CONTRACT_VERSION = "3";

    @Inject
    private AgentManager agentManager;
    @Inject
    private HostDao hostDao;
    @Inject
    private HostDetailsDao hostDetailsDao;
    @Inject
    private DrSiteDao drSiteDao;
    @Inject
    private DrSiteCredentialService drSiteCredentialService;
    @Inject
    private DrRestorePointDao drRestorePointDao;
    @Inject
    private DrReplicaDao drReplicaDao;
    @Inject
    private DrPlanRuntimeDao drPlanRuntimeDao;
    @Inject
    private DrSyncCycleDao drSyncCycleDao;
    @Inject
    private UserVmDao userVmDao;
    @Inject
    private DrReprotectPreflightService drReprotectPreflightService;
    @Inject
    private DrFailbackPreflightService drFailbackPreflightService;
    @Inject
    private DrRemoteAgentClient drRemoteAgentClient;
    @Inject
    private DrPlanOwnedTransportService drPlanOwnedTransportService;
    @Inject
    private DrSourceHardwareInventoryService drSourceHardwareInventoryService;
    @Inject
    private DrWorkerPlacementService drWorkerPlacementService;

    @Override
    public String getEngineType() {
        return DrConstants.ENGINE_TYPE_FTCTL_DR;
    }

    @Override
    public String getEngineBindingType() {
        return DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR;
    }

    @Override
    public DrAdapterResult validatePlan(DrPlanVO plan) {
        if (plan == null) {
            return DrAdapterResult.failure(DrConstants.ERROR_PLAN_NOT_FOUND, "FTCTL_DR plan is required", null);
        }
        String direction = StringUtils.upperCase(plan.getDirection(), Locale.ROOT);
        if (!StringUtils.equalsAny(direction, DrConstants.DIRECTION_KVM_TO_KVM, DrConstants.DIRECTION_KVM_TO_VMWARE,
                DrConstants.DIRECTION_VMWARE_TO_VMWARE, DrConstants.DIRECTION_VMWARE_TO_KVM)) {
            return DrAdapterResult.failure(DrConstants.ERROR_ENGINE_UNSUPPORTED,
                    "FTCTL_DR does not support direction " + plan.getDirection(), GSON.toJson(buildValidationDetails(plan)));
        }
        DrAdapterResult credentialResult = validateVmwareCredentials(plan, direction);
        if (credentialResult != null) {
            return credentialResult;
        }
        DrAdapterResult mappingResult = validateKvmTargetMapping(plan, direction);
        if (mappingResult != null) {
            return mappingResult;
        }
        return DrAdapterResult.success("FTCTL_DR plan contract is valid", GSON.toJson(buildValidationDetails(plan)));
    }

    private DrAdapterResult validateKvmTargetMapping(DrPlanVO plan, String direction) {
        if (!StringUtils.endsWithIgnoreCase(direction, "_KVM")) {
            return null;
        }
        JsonObject mapping = parseObject(plan.getMappingJson());
        JsonObject target = objectAt(mapping, "target");
        JsonArray disks = firstArray(mapping, "disks", "diskMappings", "volumes", "volumeMappings");
        JsonArray missing = new JsonArray();
        if (StringUtils.isBlank(firstString(mapping, "targetStorageRef", "targetDatastoreRef"))
                && StringUtils.isBlank(firstString(target, "storageRef", "storagePoolId", "targetStorageRef"))
                && !diskMappingsProvideTargetStorage(disks)) {
            missing.add("TARGET_STORAGE_REQUIRED");
        }
        if (StringUtils.isBlank(firstString(mapping, "targetComputeRef", "serviceOfferingId"))
                && StringUtils.isBlank(firstString(target, "serviceOfferingId", "serviceOfferingRef", "computeOfferingId"))) {
            missing.add("TARGET_SERVICE_OFFERING_REQUIRED");
        }
        if (StringUtils.isBlank(firstString(mapping, "targetNetworkRef", "networkRef"))
                && firstArray(target, "networks", "networkRefs", "networkMappings").size() == 0) {
            missing.add("TARGET_NETWORK_REQUIRED");
        }
        if (disks.size() == 0) {
            missing.add("DISK_MAPPING_REQUIRED");
        }
        for (int i = 0; i < disks.size(); i++) {
            JsonElement element = disks.get(i);
            if (element == null || !element.isJsonObject()) {
                missing.add("DISK_MAPPING_REQUIRED:" + i);
                continue;
            }
            JsonObject disk = element.getAsJsonObject();
            JsonObject diskTarget = objectAt(disk, "target");
            if (StringUtils.isBlank(firstString(disk, "targetStorageRef", "storageRef"))
                    && StringUtils.isBlank(firstString(diskTarget, "storageRef", "storagePoolId", "targetStorageRef"))) {
                missing.add("TARGET_STORAGE_REQUIRED:" + i);
            }
            if (StringUtils.isBlank(firstString(disk, "targetDiskOfferingId", "diskOfferingId"))
                    && StringUtils.isBlank(firstString(diskTarget, "diskOfferingId", "diskOfferingRef", "offeringId"))) {
                missing.add("TARGET_DISK_OFFERING_REQUIRED:" + i);
            }
        }
        if (missing.size() == 0) {
            return null;
        }
        JsonObject details = buildValidationDetails(plan);
        details.add("blockingReasons", missing);
        return DrAdapterResult.failure(DrConstants.ERROR_TARGET_MAPPING_INVALID,
                "FTCTL_DR target KVM mapping is incomplete: " + missing, GSON.toJson(details));
    }

    private boolean diskMappingsProvideTargetStorage(JsonArray disks) {
        if (disks == null || disks.size() == 0) {
            return false;
        }
        for (int i = 0; i < disks.size(); i++) {
            JsonElement element = disks.get(i);
            if (element == null || !element.isJsonObject()) {
                return false;
            }
            JsonObject disk = element.getAsJsonObject();
            JsonObject target = objectAt(disk, "target");
            if (StringUtils.isBlank(firstNonBlank(firstString(disk, "targetStorageRef", "storageRef"),
                    firstString(target, "storageRef", "storagePoolId", "targetStorageRef")))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public DrAdapterResult execute(DrExecutionContext context) {
        FtctlDrActionCommand.Action action = resolveAction(context.getRun());
        boolean testCheckpointBarrier = action == FtctlDrActionCommand.Action.TEST_PREPARE;
        boolean testCheckpointBarrierAcquired = false;
        boolean plannedRemoteKvmIsolationRequired = requiresPlannedRemoteKvmIsolation(context, action);
        boolean plannedRemoteKvmIsolated = false;
        if (action == null) {
            String message = "DR run type " + context.getRun().getRunType() + " is not supported by FTCTL_DR";
            return DrAdapterResult.failure(DrConstants.ERROR_ACTION_UNSUPPORTED, message, GSON.toJson(buildExecutionDetails(context, null, null)));
        }

        Long coordinatorHostId = resolveCoordinatorHostId(context.getPlan(), context.getRun());
        if (coordinatorHostId == null || coordinatorHostId.longValue() <= 0L) {
            String message = "FTCTL_DR requires a coordinator, source, or target worker host before dispatch";
            return DrAdapterResult.failure(DrConstants.ERROR_TARGET_MAPPING_INVALID, message, GSON.toJson(buildExecutionDetails(context, action, null)));
        }

        DrSourceVmHardware sourceHardware = resolveSourceHardwareSnapshot(context, action);
        DrAdapterResult sourceHardwareResult = validateSourceHardwareSnapshot(context, action, sourceHardware);
        if (sourceHardwareResult != null) {
            return sourceHardwareResult;
        }

        DrAdapterResult isolationValidation = validateFailoverIsolation(context, action);
        if (isolationValidation != null) {
            return isolationValidation;
        }

        DrAdapterResult capabilityResult = validateCapabilities(context, action, coordinatorHostId);
        if (capabilityResult != null) {
            return capabilityResult;
        }

        DrAdapterResult checkpointValidation = validateLatestCheckpoint(context, action);
        if (checkpointValidation != null) {
            return checkpointValidation;
        }
        if (action == FtctlDrActionCommand.Action.FAILBACK) {
            DrFailbackPreflightResult failbackPreflight = drFailbackPreflightService.validate(
                    context.getPlan(), context.getRun());
            if (!failbackPreflight.isReady()) {
                return DrAdapterResult.failure(failbackPreflight.getErrorCode(), failbackPreflight.getMessage(),
                        GSON.toJson(buildExecutionDetails(context, action, coordinatorHostId)));
            }
        }
        DrReprotectPreflightResult reprotectPreflight = null;
        if (action == FtctlDrActionCommand.Action.REPROTECT) {
            reprotectPreflight = drReprotectPreflightService.validate(context.getPlan(), context.getRun());
            if (!reprotectPreflight.isReady()) {
                return DrAdapterResult.failure(reprotectPreflight.getErrorCode(), reprotectPreflight.getMessage(),
                        GSON.toJson(buildExecutionDetails(context, action, coordinatorHostId)));
            }
        }
        FtctlDrActionCommand command;
        try {
            command = buildActionCommand(context, action, sourceHardware);
            applyPlannedFileSourceQuiesceContract(context, action, command);
            preparePlanOwnedTransport(context, action, command);
            testCheckpointBarrierAcquired = testCheckpointBarrier;
            if (plannedRemoteKvmIsolationRequired) {
                DrAdapterResult isolationFailure = preparePlannedRemoteKvmIsolation(context, command);
                if (isolationFailure != null) {
                    return isolationFailure;
                }
                plannedRemoteKvmIsolated = true;
            }
            if (reprotectPreflight != null) {
                command.setAuthorityContractVersion(DrReprotectAuthoritySpec.CONTRACT_VERSION);
                command.setAuthoritySpecJson(GSON.toJson(reprotectPreflight.getAuthoritySpec()));
            }
        } catch (IllegalArgumentException e) {
            compensateTestCheckpointBarrier(context, testCheckpointBarrierAcquired);
            return DrAdapterResult.failure("DR_TEST_ARTIFACT_SPEC_INVALID", e.getMessage(),
                    GSON.toJson(buildExecutionDetails(context, action, coordinatorHostId)));
        } catch (CloudRuntimeException e) {
            compensateTestCheckpointBarrier(context, testCheckpointBarrierAcquired);
            return DrAdapterResult.retryable(DrConstants.ERROR_ENGINE_UNAVAILABLE,
                    "Unable to prepare remote DR transport: " + e.getMessage(),
                    GSON.toJson(buildExecutionDetails(context, action, coordinatorHostId)), 10);
        }
        if (action == FtctlDrActionCommand.Action.FAILBACK
                && StringUtils.equalsIgnoreCase(context.getPlan().getDirection(), DrConstants.DIRECTION_VMWARE_TO_KVM)) {
            DrAdapterResult reversePreflight = validateReversePreflight(context, coordinatorHostId, command);
            if (reversePreflight != null) {
                return reversePreflight;
            }
        }
        try {
            Answer answer = sendActionCommand(context, action, coordinatorHostId, command);
            DrAdapterResult result = toAdapterResult(context, action, coordinatorHostId, answer);
            if (testCheckpointBarrierAcquired && !result.isSuccess()) {
                compensateTestCheckpointBarrier(context, true);
            } else if (plannedRemoteKvmIsolated && !result.isSuccess()) {
                compensatePlannedRemoteKvmIsolation(context);
            }
            // Cleanup acceptance is not artifact completion. The terminal projection
            // durably schedules protection restoration after all test writers are gone.
            return result;
        } catch (OperationTimedoutException e) {
            LOGGER.warn("Unable to dispatch FTCTL_DR run {} to host {}: {}", context.getRun().getId(), coordinatorHostId, e.getMessage());
            DrAdapterResult acceptedFromStatus = probeAcceptedStatus(context, action, coordinatorHostId);
            if (acceptedFromStatus != null) {
                return acceptedFromStatus;
            }
            compensateTestCheckpointBarrier(context, testCheckpointBarrierAcquired);
            if (plannedRemoteKvmIsolated) {
                compensatePlannedRemoteKvmIsolation(context);
            }
            return DrAdapterResult.failure(DrConstants.ERROR_AGENT_DISPATCH_TIMEOUT,
                    "Unable to dispatch FTCTL_DR run to Agent: " + e.getMessage(), GSON.toJson(buildExecutionDetails(context, action, coordinatorHostId)));
        } catch (AgentUnavailableException e) {
            LOGGER.warn("FTCTL_DR coordinator Agent is unavailable for run {} on host {}: {}", context.getRun().getId(), coordinatorHostId, e.getMessage());
            compensateTestCheckpointBarrier(context, testCheckpointBarrierAcquired);
            if (plannedRemoteKvmIsolated) {
                compensatePlannedRemoteKvmIsolation(context);
            }
            return DrAdapterResult.failure(DrConstants.ERROR_AGENT_UNAVAILABLE,
                    "FTCTL_DR coordinator Agent is unavailable: " + e.getMessage(), GSON.toJson(buildExecutionDetails(context, action, coordinatorHostId)));
        } catch (CloudRuntimeException e) {
            LOGGER.warn("FTCTL_DR remote site dispatch failed for run {}: {}", context.getRun().getId(), e.getMessage());
            compensateTestCheckpointBarrier(context, testCheckpointBarrierAcquired);
            if (plannedRemoteKvmIsolated) {
                compensatePlannedRemoteKvmIsolation(context);
            }
            return DrAdapterResult.retryable(DrConstants.ERROR_ENGINE_UNAVAILABLE,
                    "FTCTL_DR remote site dispatch failed: " + e.getMessage(),
                    GSON.toJson(buildExecutionDetails(context, action, coordinatorHostId)), 10);
        }
    }

    private boolean requiresPlannedRemoteKvmIsolation(DrExecutionContext context,
            FtctlDrActionCommand.Action action) {
        if (context == null || action != FtctlDrActionCommand.Action.FAILOVER
                || !isRemoteKvmToKvmPlan(context.getPlan())
                || !StringUtils.equalsIgnoreCase(context.getPlan().getActiveSide(), "SOURCE")) {
            return false;
        }
        return StringUtils.equalsIgnoreCase(requestString(requestJson(context.getRun()), "mode"), "planned");
    }

    private DrAdapterResult preparePlannedRemoteKvmIsolation(DrExecutionContext context,
            FtctlDrActionCommand command) {
        DrPlanVO plan = context.getPlan();
        DrRunVO run = context.getRun();
        try {
            FtctlDrActionAnswer pause = drRemoteAgentClient.transitionSourceScheduler(plan,
                    FtctlDrActionCommand.Action.PAUSE_SYNC, run.getUuid(), command.getProfileJson());
            if (pause == null || !pause.getResult()) {
                throw new CloudRuntimeException(StringUtils.defaultIfBlank(
                        pause != null ? pause.getDetails() : null,
                        "Remote KVM source scheduler did not acknowledge the final-delta barrier"));
            }
            if (isSharedMountPointFilePlan(plan)) {
                return null;
            }
            String powerState = drRemoteAgentClient.ensureSourceVmPowerState(plan, false);
            if (!StringUtils.equalsIgnoreCase(powerState, "POWERED_OFF")) {
                throw new CloudRuntimeException("Remote KVM source VM did not reach POWERED_OFF before final delta");
            }
            return null;
        } catch (RuntimeException e) {
            compensatePlannedRemoteKvmIsolation(context);
            JsonObject details = buildExecutionDetails(context, FtctlDrActionCommand.Action.FAILOVER,
                    resolveCoordinatorHostId(plan));
            details.addProperty("plannedSourceIsolation", "ROLLED_BACK");
            details.addProperty("sourcePowerRequired", isSharedMountPointFilePlan(plan)
                    ? "QMP_PAUSED_THEN_POWERED_OFF" : "POWERED_OFF");
            return DrAdapterResult.failure(DrConstants.ERROR_SOURCE_ISOLATION_NOT_READY,
                    "Unable to establish an immutable planned failover checkpoint: " + e.getMessage(),
                    GSON.toJson(details));
        }
    }

    private void applyPlannedFileSourceQuiesceContract(DrExecutionContext context,
            FtctlDrActionCommand.Action action, FtctlDrActionCommand command) {
        if (!requiresPlannedRemoteKvmIsolation(context, action)
                || !isSharedMountPointFilePlan(context.getPlan())) {
            return;
        }
        String observedPowerState = drRemoteAgentClient.getSourceVmPowerState(context.getPlan());
        String quiesceMode = StringUtils.equalsIgnoreCase(observedPowerState, "POWERED_OFF")
                ? "SOURCE_ALREADY_STOPPED" : "QMP_STOP";
        JsonObject request = parseObject(command.getRequestJson());
        request.addProperty("sourceRuntimeQuiesceRequired", true);
        request.addProperty("sourceRuntimeQuiesceMode", quiesceMode);
        request.addProperty("sourceRuntimeObservedPowerState", observedPowerState);
        request.addProperty("sourceRuntimeObservedAtEpochMs", System.currentTimeMillis());
        request.addProperty("cutoverRunUuid", context.getRun().getUuid());
        request.addProperty("sourcePowerOffAfterCheckpoint", true);
        command.setRequestJson(GSON.toJson(request));

        JsonObject profile = parseObject(command.getProfileJson());
        JsonObject profileRequest = profile.has("request") && profile.get("request").isJsonObject()
                ? profile.getAsJsonObject("request") : new JsonObject();
        if (!profile.has("request") || !profile.get("request").isJsonObject()) {
            profile.add("request", profileRequest);
        }
        profileRequest.addProperty("sourceRuntimeQuiesceRequired", true);
        profileRequest.addProperty("sourceRuntimeQuiesceMode", quiesceMode);
        profileRequest.addProperty("sourceRuntimeObservedPowerState", observedPowerState);
        profileRequest.addProperty("sourceRuntimeObservedAtEpochMs",
                request.get("sourceRuntimeObservedAtEpochMs").getAsLong());
        profileRequest.addProperty("cutoverRunUuid", context.getRun().getUuid());
        profileRequest.addProperty("sourcePowerOffAfterCheckpoint", true);
        command.setProfileJson(GSON.toJson(profile));
    }

    private void compensatePlannedRemoteKvmIsolation(DrExecutionContext context) {
        DrPlanVO plan = context.getPlan();
        DrRunVO run = context.getRun();
        try {
            drRemoteAgentClient.ensureSourceVmPowerState(plan, true);
        } catch (RuntimeException powerFailure) {
            LOGGER.error("Unable to restore remote KVM source VM power after planned Failover failed for Plan {}: {}",
                    plan.getUuid(), powerFailure.getMessage(), powerFailure);
        }
        try {
            resumeRemoteSourceProtection(plan, run);
        } catch (RuntimeException resumeFailure) {
            LOGGER.error("Unable to resume remote KVM source protection after planned Failover failed for Plan {}: {}",
                    plan.getUuid(), resumeFailure.getMessage(), resumeFailure);
        }
    }

    private boolean sourceIndependentTest(DrRunVO run) {
        return run != null && "TEST_FAILOVER".equalsIgnoreCase(run.getRunType())
                && requestBoolean(requestJson(run), "sourceIndependent", false);
    }

    private boolean targetOnlyRecovery(DrRunVO run) {
        return sourceIndependentTest(run) || run != null && ("TEST_CLEANUP".equalsIgnoreCase(run.getRunType())
                || "FAILOVER".equalsIgnoreCase(run.getRunType()) && DrFailoverExecutionPolicy.isDisaster(run));
    }

    private DrSourceVmHardware resolveSourceHardwareSnapshot(DrExecutionContext context,
            FtctlDrActionCommand.Action action) {
        DrPlanVO plan = context != null ? context.getPlan() : null;
        if (context != null && sourceIndependentTest(context.getRun())) return null;
        if (plan == null || !StringUtils.equalsIgnoreCase(plan.getDirection(), DrConstants.DIRECTION_KVM_TO_KVM)
                || !StringUtils.equalsIgnoreCase(plan.getActiveSide(), "SOURCE")
                || (action == FtctlDrActionCommand.Action.FAILOVER
                        && DrFailoverExecutionPolicy.isDisaster(context.getRun()))
                || (action != FtctlDrActionCommand.Action.SYNC
                        && action != FtctlDrActionCommand.Action.RECOVER_SYNC
                        && action != FtctlDrActionCommand.Action.TEST_PREPARE
                        && action != FtctlDrActionCommand.Action.FAILOVER)) {
            return null;
        }
        return drSourceHardwareInventoryService.resolve(plan);
    }

    private DrAdapterResult validateSourceHardwareSnapshot(DrExecutionContext context,
            FtctlDrActionCommand.Action action, DrSourceVmHardware hardware) {
        DrPlanVO plan = context != null ? context.getPlan() : null;
        if (context != null && sourceIndependentTest(context.getRun())) return null;
        if (plan == null || hardware == null && (action == FtctlDrActionCommand.Action.FAILOVER
                && DrFailoverExecutionPolicy.isDisaster(context.getRun()))) {
            return null;
        }
        if (hardware == null && !(StringUtils.equalsIgnoreCase(plan.getDirection(), DrConstants.DIRECTION_KVM_TO_KVM)
                && StringUtils.equalsIgnoreCase(plan.getActiveSide(), "SOURCE")
                && (action == FtctlDrActionCommand.Action.SYNC
                        || action == FtctlDrActionCommand.Action.RECOVER_SYNC
                        || action == FtctlDrActionCommand.Action.TEST_PREPARE
                        || action == FtctlDrActionCommand.Action.FAILOVER))) {
            return null;
        }
        if (hardware == null || !hardware.isComplete()) {
            String message = StringUtils.defaultIfBlank(hardware != null ? hardware.getMessage() : null,
                    "ABLESTACK source VM details could not be read before DR action dispatch");
            return DrAdapterResult.failure(DrPlanReadinessValidator.REASON_SOURCE_HARDWARE_INVENTORY_REQUIRED,
                    message, GSON.toJson(buildExecutionDetails(context, action, resolveCoordinatorHostId(plan))));
        }
        if (hardware.hasOperationBlocker()) {
            return DrAdapterResult.failure(hardware.getOperationBlockerCode(),
                    StringUtils.defaultIfBlank(hardware.getOperationBlockerMessage(),
                            "ABLESTACK source VM has an active operation that blocks DR cutover"),
                    GSON.toJson(buildExecutionDetails(context, action, resolveCoordinatorHostId(plan))));
        }
        return null;
    }

    private DrAdapterResult validateReversePreflight(DrExecutionContext context, Long coordinatorHostId,
            FtctlDrActionCommand actionCommand) {
        Answer rawAnswer = sendReversePreflight(context.getPlan(), coordinatorHostId,
                actionCommand.getProfileJson());
        if (!(rawAnswer instanceof FtctlDrReversePreflightAnswer)) {
            return DrAdapterResult.failure("DR_REVERSE_PREFLIGHT_UNAVAILABLE",
                    "FTCTL reverse preflight did not return a typed answer",
                    GSON.toJson(buildExecutionDetails(context, FtctlDrActionCommand.Action.FAILBACK, coordinatorHostId)));
        }
        FtctlDrReversePreflightAnswer answer = (FtctlDrReversePreflightAnswer) rawAnswer;
        if (!answer.getResult() || !Boolean.TRUE.equals(answer.getReady())) {
            String errorCode = StringUtils.defaultIfBlank(answer.getErrorCode(), "DR_REVERSE_PREFLIGHT_FAILED");
            return DrAdapterResult.failure(errorCode,
                    StringUtils.defaultIfBlank(answer.getDetails(), "FTCTL reverse preflight failed"),
                    StringUtils.defaultIfBlank(answer.getStatusJson(),
                            GSON.toJson(buildExecutionDetails(context, FtctlDrActionCommand.Action.FAILBACK, coordinatorHostId))));
        }
        return null;
    }

    public FtctlDrReversePreflightAnswer probeReversePreflight(DrPlanVO plan, DrRunVO run) {
        if (plan == null) {
            return null;
        }
        Long coordinatorHostId = resolveCoordinatorHostId(plan);
        if (coordinatorHostId == null) {
            return null;
        }
        DrRunVO effectiveRun = run != null ? run : new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_FAILBACK);
        FtctlDrActionCommand actionCommand = buildActionCommand(
                new DrExecutionContext(plan, effectiveRun), FtctlDrActionCommand.Action.FAILBACK);
        Answer answer = sendReversePreflight(plan, coordinatorHostId, actionCommand.getProfileJson());
        return answer instanceof FtctlDrReversePreflightAnswer
                ? (FtctlDrReversePreflightAnswer) answer : null;
    }

    private Answer sendReversePreflight(DrPlanVO plan, Long coordinatorHostId, String profileJson) {
        FtctlDrReversePreflightCommand command = new FtctlDrReversePreflightCommand(
                plan.getUuid(), profileJson, "FAILBACK_FINAL", "AUTO");
        return agentManager.easySend(coordinatorHostId, command);
    }

    private Answer sendActionCommand(DrExecutionContext context, FtctlDrActionCommand.Action action,
            Long localHostId, FtctlDrActionCommand command) throws AgentUnavailableException, OperationTimedoutException {
        if (dispatchesOnRemoteSource(context.getPlan(), context.getRun(), action)) {
            return drRemoteAgentClient.execute(context.getPlan(), "ACTION", command,
                    null, FtctlDrActionAnswer.class);
        }
        return agentManager.send(localHostId, command);
    }

    private void preparePlanOwnedTransport(DrExecutionContext context, FtctlDrActionCommand.Action action,
            FtctlDrActionCommand sourceCommand) {
        DrPlanVO plan = context.getPlan();
        if (action == FtctlDrActionCommand.Action.TEST_PREPARE) {
            captureTestReplicationIntent(context);
        }
        if (action == FtctlDrActionCommand.Action.TEST_PREPARE && (requiresTestCheckpointBarrier(plan) || sourceIndependentTest(context.getRun()))) {
            prepareTestCheckpointBarrier(context, sourceCommand);
            return;
        }
        if (action == FtctlDrActionCommand.Action.RELEASE) {
            drPlanOwnedTransportService.stopForwardTargetExport(plan, context.getRun(),
                    sourceCommand.getProfileJson(), null);
            return;
        }
        if (action == FtctlDrActionCommand.Action.FAILOVER
                && DrFailoverExecutionPolicy.isDisaster(context.getRun())) {
            DrRestorePointVO checkpoint = drRestorePointDao.findLatestTargetReadyByPlanId(plan.getId());
            drPlanOwnedTransportService.stopForwardTargetExport(plan, context.getRun(),
                    sourceCommand.getProfileJson(), null);
            if (isImmutableCheckpoint(checkpoint)) {
                restoreDurableCheckpoint(context, checkpoint);
            }
            drPlanOwnedTransportService.stopForwardTargetExport(plan, context.getRun(),
                    sourceCommand.getProfileJson(), checkpoint != null ? checkpoint.getCheckpointSequence() : null);
            return;
        }
        if (isRemoteKvmToKvmPlan(plan)
                && (action == FtctlDrActionCommand.Action.FAILBACK
                        || action == FtctlDrActionCommand.Action.REPROTECT)
                && StringUtils.equalsIgnoreCase(plan.getActiveSide(), "TARGET")) {
            prepareReversePlanOwnedTransport(context, sourceCommand);
            return;
        }
        if (!dispatchesOnRemoteSource(plan, context.getRun(), action)
                || (action != FtctlDrActionCommand.Action.SYNC
                        && action != FtctlDrActionCommand.Action.RECOVER_SYNC
                        && action != FtctlDrActionCommand.Action.FAILOVER
                        && action != FtctlDrActionCommand.Action.RESUME_SYNC)) {
            return;
        }
        injectPlanOwnedExports(sourceCommand, drPlanOwnedTransportService.startForwardTargetExport(
                plan, context.getRun(), sourceCommand.getProfileJson()));
    }

    private void prepareTestCheckpointBarrier(DrExecutionContext context,
            FtctlDrActionCommand command) {
        DrPlanVO plan = context.getPlan();
        boolean immutableFileCheckpoint = isSharedMountPointFilePlan(plan);
        boolean independent = sourceIndependentTest(context.getRun());
        if (!independent) {
            FtctlDrActionAnswer pause = transitionTestCheckpointScheduler(context,
                    FtctlDrActionCommand.Action.PAUSE_SYNC, command.getProfileJson());
            if (pause == null || !pause.getResult()) {
                throw new CloudRuntimeException("Protection scheduler did not acknowledge the immutable checkpoint barrier");
            }
        }
        try {
            DrRestorePointVO checkpoint = drRestorePointDao.findLatestTargetReadyByPlanId(plan.getId());
            Long checkpointSequence = checkpoint != null ? checkpoint.getCheckpointSequence() : null;
            if (checkpointSequence == null || checkpointSequence <= 0L) {
                throw new CloudRuntimeException("A positive durable checkpoint sequence is required for Test Failover");
            }
            JsonObject request = parseObject(command.getRequestJson());
            addControllerCheckpointEvidence(request, plan, checkpoint);
            drPlanOwnedTransportService.stopForwardTargetExport(plan, context.getRun(),
                    command.getProfileJson(), checkpointSequence);
            request.addProperty("checkpointWriterState", "DRAINED");
            request.addProperty("checkpointExistingSealRequired", independent || isImmutableCheckpoint(checkpoint));
            request.addProperty("checkpointImmutableRequired", immutableFileCheckpoint);
            command.setRequestJson(GSON.toJson(request));
            JsonObject profile = parseObject(command.getProfileJson());
            JsonObject profileRequest = objectAt(profile, "request");
            addControllerCheckpointEvidence(profileRequest, plan, checkpoint);
            profileRequest.addProperty("checkpointWriterState", "DRAINED");
            profileRequest.addProperty("checkpointExistingSealRequired", independent || isImmutableCheckpoint(checkpoint));
            profileRequest.addProperty("checkpointImmutableRequired", immutableFileCheckpoint);
            command.setProfileJson(GSON.toJson(profile));
            command.setCheckpointRef(checkpoint.getSourceSnapshotRef());
            command.setArtifactSpecJson(buildTestArtifactSpec(plan, context.getRun(), checkpoint));
        } catch (RuntimeException e) {
            compensateTestCheckpointBarrier(context, true);
            throw e;
        }
    }

    private void compensateTestCheckpointBarrier(DrExecutionContext context, boolean required) {
        if (!required) {
            return;
        }
        if (sourceIndependentTest(context.getRun())) {
            if (testCleanupRecovery != null) testCleanupRecovery.arm(context.getPlan().getId(), context.getRun().getId(), context.getRun().getId());
            return;
        }
        try {
            resumeTestCheckpointProtection(context);
        } catch (RuntimeException compensationFailure) {
            if (testCleanupRecovery != null) {
                testCleanupRecovery.arm(context.getPlan().getId(), context.getRun().getId(), context.getRun().getId());
            }
            LOGGER.error("Unable to restore FILE DR protection after Test Failover preparation failed for Plan {}: {}",
                    context.getPlan().getUuid(), compensationFailure.getMessage(), compensationFailure);
        }
    }

    private void captureTestReplicationIntent(DrExecutionContext context) {
        if (testCleanupRecovery != null) {
            DrPlanVO plan = context.getPlan();
            DrPlanRuntimeVO previous = drPlanRuntimeDao.findByPlanId(plan.getId());
            testCleanupRecovery.capture(plan.getId(), context.getRun().getId(),
                    preTestReplicationIntent(plan, previous));
        }
    }

    private String preTestReplicationIntent(DrPlanVO plan, DrPlanRuntimeVO runtime) {
        // The scheduler process stays RUNNING while its replication loop is paused.
        // Operator pause/protection state therefore takes precedence over process intent.
        if (StringUtils.equalsIgnoreCase(plan.getState(), "PAUSED")
                || runtime != null && (StringUtils.equalsIgnoreCase(runtime.getProtectionState(), "PAUSED")
                || StringUtils.equalsIgnoreCase(runtime.getReplicationActivityState(), "PAUSED"))) {
            return "PAUSED";
        }
        String desired = runtime != null ? runtime.getSchedulerDesiredState() : null;
        return StringUtils.equalsIgnoreCase(StringUtils.defaultIfBlank(desired, "RUNNING"), "RUNNING")
                ? "RUNNING" : "PAUSED";
    }

    private void resumeTestCheckpointProtection(DrExecutionContext context) {
        com.cloud.dr.DrTestCleanupRecoveryStore.Intent intent = testCleanupRecovery != null
                ? testCleanupRecovery.find(context.getRun().getId()) : null;
        if (intent != null && !"RUNNING".equals(intent.desiredState)) return;
        restoreTestCheckpointProtection(context.getPlan(), context.getRun());
    }

    public void restoreTestCheckpointProtection(DrPlanVO plan, DrRunVO run) {
        DrExecutionContext context = new DrExecutionContext(plan, run);
        // Probe only in the asynchronous recovery worker, never in artifact cleanup.
        // A disconnected source must not cause a new export generation every retry.
        if (isRemoteKvmToKvmPlan(plan)) {
            FtctlDrStatusAnswer status = drRemoteAgentClient.fetchSourceStatus(plan, plan.getUuid(),
                    FtctlDrStatusCommand.StatusScope.PLAN_AUTHORITY);
            if (status == null || !status.getResult()) {
                throw new CloudRuntimeException("Source scheduler is unavailable; protection restore remains pending");
            }
        }
        String profile = buildProfileJson(plan, run, redactJson(requestJson(run)).getAsJsonObject(), null, true);
        if (drPlanOwnedTransportService.supports(plan)) {
            profile = withPlanOwnedExports(profile,
                    drPlanOwnedTransportService.startForwardTargetExport(plan, run, profile));
        }
        // Recheck after the bounded target RPC: a newer operator intent wins.
        DrRunVO latest = cleanupRecoveryRunDao != null ? cleanupRecoveryRunDao.findLatestByPlanId(plan.getId()) : null;
        if (latest != null && latest.getId() > run.getId()
                && StringUtils.equalsAnyIgnoreCase(latest.getRunType(), "PAUSE_SYNC", "RELEASE", "FAILOVER")) {
            throw new CloudRuntimeException("Newer operator intent supersedes test protection restore");
        }
        FtctlDrActionAnswer resume = transitionTestCheckpointScheduler(context,
                FtctlDrActionCommand.Action.RESUME_SYNC, profile);
        if (resume == null || !resume.getResult()) {
            throw new CloudRuntimeException("Protection scheduler did not acknowledge Test Failover cleanup resume");
        }
    }

    private FtctlDrActionAnswer transitionTestCheckpointScheduler(DrExecutionContext context,
            FtctlDrActionCommand.Action action, String profileJson) {
        DrPlanVO plan = context.getPlan();
        DrRunVO run = context.getRun();
        if (isRemoteKvmToKvmPlan(plan)) {
            return drRemoteAgentClient.transitionSourceScheduler(plan, action, run.getUuid(), profileJson);
        }
        Long coordinatorHostId = resolveCoordinatorHostId(plan);
        if (coordinatorHostId == null || coordinatorHostId <= 0L) {
            throw new CloudRuntimeException("A local protection scheduler host is required for checkpoint transition");
        }
        String runType = action == FtctlDrActionCommand.Action.PAUSE_SYNC
                ? DrConstants.RUN_TYPE_PAUSE_SYNC : DrConstants.RUN_TYPE_RESUME_SYNC;
        FtctlDrActionCommand transition = new FtctlDrActionCommand(action, plan.getUuid(),
                DrRemoteAgentClient.sourceTransitionRunUuid(plan.getUuid(), run.getUuid(), action));
        transition.setActionName(action.name());
        transition.setCliCommand(action.getCliCommand());
        transition.setRunType(runType);
        transition.setActionIntent(runType);
        transition.setDirection(plan.getDirection());
        transition.setRole("coordinator");
        transition.setSourceWorkerUuid(resolveHostUuid(resolveWorkerHostId(plan, run, DrWorkerRole.SOURCE)));
        transition.setTargetWorkerUuid(resolveHostUuid(resolveWorkerHostId(plan, run, DrWorkerRole.TARGET)));
        transition.setCoordinatorWorkerUuid(resolveHostUuid(coordinatorHostId));
        transition.setProfileJson(profileJson);
        transition.setWaitForCompletion(true);
        transition.setWait(45);
        Answer answer = agentManager.easySend(coordinatorHostId, transition);
        if (!(answer instanceof FtctlDrActionAnswer)) {
            throw new CloudRuntimeException("Local protection scheduler transition did not return an FTCTL response");
        }
        return (FtctlDrActionAnswer) answer;
    }

    private boolean requiresTestCheckpointBarrier(DrPlanVO plan) {
        return isSharedMountPointFilePlan(plan) || isRemoteKvmToKvmPlan(plan);
    }

    private boolean isSharedMountPointFilePlan(DrPlanVO plan) {
        JsonObject mapping = parseObject(plan.getMappingJson());
        JsonObject target = objectAt(mapping, "target");
        if (StringUtils.equalsIgnoreCase(firstString(target, "storagePoolType", "poolType"), "SharedMountPoint")) {
            return true;
        }
        JsonArray disks = firstArray(mapping, "disks", "diskMappings", "volumes", "volumeMappings");
        for (JsonElement element : disks) {
            if (element != null && element.isJsonObject()) {
                JsonObject diskTarget = objectAt(element.getAsJsonObject(), "target");
                if (StringUtils.equalsIgnoreCase(firstString(diskTarget, "storagePoolType", "poolType"),
                        "SharedMountPoint")) {
                    return true;
                }
            }
        }
        return false;
    }

    private void prepareReversePlanOwnedTransport(DrExecutionContext context,
            FtctlDrActionCommand failbackCommand) {
        injectPlanOwnedExports(failbackCommand, drPlanOwnedTransportService.startReverseTargetExport(
                context.getPlan(), context.getRun(), failbackCommand.getProfileJson()));
    }

    private void injectPlanOwnedExports(FtctlDrActionCommand command, JsonArray exports) {
        command.setProfileJson(withPlanOwnedExports(command.getProfileJson(), exports));
    }

    private String withPlanOwnedExports(String profileJson, JsonArray exports) {
        JsonObject profile = parseObject(profileJson);
        JsonObject transport = objectAt(profile, "transport");
        transport.addProperty("mode", "site-agent-nbd");
        transport.remove("secondaryUri");
        transport.remove("sshUser");
        transport.remove("sshPort");
        transport.remove("sshKeyFile");
        transport.add("exports", exports);
        return GSON.toJson(profile);
    }

    public FtctlDrActionAnswer resumeRemoteSourceProtection(DrPlanVO plan, DrRunVO run) {
        return resumeRemoteSourceProtection(plan, run, null, null);
    }

    public FtctlDrActionAnswer resumeRemoteSourceProtection(DrPlanVO plan, DrRunVO run,
            Long resumeBaselineCheckpointSequence, Long minimumCompletedCheckpointSequence) {
        if (!isRemoteKvmToKvmPlan(plan)) {
            throw new CloudRuntimeException("Remote KVM_TO_KVM Plan is required for forward protection resume");
        }
        JsonObject request = redactJson(requestJson(run)).getAsJsonObject();
        request.addProperty("schedulerTransitionScope", "REMOTE_SOURCE");
        request.addProperty("forceImmediateCycle", true);
        if (resumeBaselineCheckpointSequence != null) {
            request.addProperty("resumeBaselineCheckpointSequence", resumeBaselineCheckpointSequence);
        }
        if (minimumCompletedCheckpointSequence != null) {
            request.addProperty("minimumCompletedCheckpointSequence", minimumCompletedCheckpointSequence);
        }
        String profileJson = buildProfileJson(plan, run, request);
        JsonArray exports = drPlanOwnedTransportService.startForwardTargetExport(plan, run, profileJson);
        return drRemoteAgentClient.transitionSourceScheduler(plan,
                FtctlDrActionCommand.Action.RESUME_SYNC, run.getUuid(),
                withPlanOwnedExports(profileJson, exports),
                resumeBaselineCheckpointSequence, minimumCompletedCheckpointSequence,
                resolveAuthoritySequenceFloor(plan));
    }

    private boolean isRemoteKvmToKvmPlan(DrPlanVO plan) {
        return drPlanOwnedTransportService != null && drPlanOwnedTransportService.supports(plan);
    }

    private boolean dispatchesOnRemoteSource(DrPlanVO plan, DrRunVO run,
            FtctlDrActionCommand.Action action) {
        return DrFailoverExecutionPolicy.usesRemoteSource(plan, run,
                action != null ? action.name() : null,
                drRemoteAgentClient != null && drRemoteAgentClient.isRemoteKvmSource(plan));
    }

    private FtctlDrActionCommand buildActionCommand(DrExecutionContext context, FtctlDrActionCommand.Action action) {
        return buildActionCommand(context, action, null);
    }

    private FtctlDrActionCommand buildActionCommand(DrExecutionContext context,
            FtctlDrActionCommand.Action action, DrSourceVmHardware sourceHardware) {
        DrPlanVO plan = context.getPlan();
        DrRunVO run = context.getRun();
        JsonObject request = requestJson(run);
        JsonObject redactedRequest = redactJson(request).getAsJsonObject();
        if (action == FtctlDrActionCommand.Action.TEST_PREPARE
                || action == FtctlDrActionCommand.Action.TEST_ARTIFACT_CLEANUP) {
            redactedRequest.addProperty("sourceSchedulerRestoreManagedByCloud", true);
        }
        DrRestorePointVO latestCheckpoint = usesLatestCheckpointEvidence(action)
                ? drRestorePointDao.findLatestTargetReadyByPlanId(plan.getId()) : null;
        redactedRequest.remove("restorePointId");
        String transitionScope = DrFailoverExecutionPolicy.schedulerTransitionScope(plan, run,
                isRemoteKvmToKvmPlan(plan));
        if (action == FtctlDrActionCommand.Action.TEST_ARTIFACT_CLEANUP && requiresTestCheckpointBarrier(plan)) {
            transitionScope = "REMOTE_SOURCE"; // Cloud owns post-cleanup restore, including local FILE sources.
        }
        if (StringUtils.isNotBlank(transitionScope)) {
            redactedRequest.addProperty("schedulerTransitionScope", transitionScope);
        }
        if (latestCheckpoint != null) {
            addControllerCheckpointEvidence(redactedRequest, plan, latestCheckpoint);
        }
        Long recoveryBaselineSequence = action == FtctlDrActionCommand.Action.RECOVER_SYNC
                && latestCheckpoint != null ? latestCheckpoint.getCheckpointSequence() : null;
        Long recoveryMinimumSequence = recoveryBaselineSequence != null
                ? recoveryBaselineSequence + 1L : null;
        if (recoveryBaselineSequence != null) {
            redactedRequest.addProperty("resumeBaselineCheckpointSequence", recoveryBaselineSequence);
            redactedRequest.addProperty("minimumCompletedCheckpointSequence", recoveryMinimumSequence);
        }
        FtctlDrActionCommand command = new FtctlDrActionCommand(action, plan.getUuid(), run.getUuid());
        command.setActionName(action.name());
        command.setCliCommand(action.getCliCommand());
        command.setRunType(run.getRunType());
        command.setActionIntent(requestString(request, "actionIntent"));
        command.setDirection(plan.getDirection());
        command.setRole("coordinator");
        command.setSourceWorkerUuid(targetOnlyRecovery(run) || dispatchesOnRemoteSource(plan, run, action)
                || action == FtctlDrActionCommand.Action.FAILOVER && DrFailoverExecutionPolicy.isDisaster(run) ? null
                : resolveHostUuid(resolveWorkerHostId(plan, run, DrWorkerRole.SOURCE)));
        command.setTargetWorkerUuid(resolveHostUuid(resolveWorkerHostId(plan, run, DrWorkerRole.TARGET)));
        command.setCoordinatorWorkerUuid(resolveHostUuid(resolveCoordinatorHostId(plan, run)));
        command.setProfileJson(buildProfileJson(plan, run, redactedRequest, sourceHardware));
        command.setRequestJson(GSON.toJson(redactedRequest));
        if (action == FtctlDrActionCommand.Action.TEST_PREPARE) {
            command.setArtifactContractVersion(TEST_ARTIFACT_CONTRACT_VERSION);
            command.setArtifactSpecJson(buildTestArtifactSpec(plan, run, latestCheckpoint));
        }
        command.setMode(requestString(request, "mode"));
        command.setForceImmediateCycle(requestBoolean(request, "forceImmediateCycle", false));
        command.setAuthoritySequenceFloor(resolveAuthoritySequenceFloor(plan));
        command.setResumeBaselineCheckpointSequence(recoveryBaselineSequence);
        command.setMinimumCompletedCheckpointSequence(recoveryMinimumSequence);
        command.setCheckpointRef(latestCheckpoint != null ? latestCheckpoint.getSourceSnapshotRef() : null);
        command.setForce(requestBoolean(request, "force", false));
        command.setDryRun(requestBoolean(request, "dryRun", false));
        command.setWaitForCompletion(false);
        command.setWait(AGENT_ACCEPT_TIMEOUT_SECONDS);
        command.setContext(toStringMap(redactedRequest));
        if (StringUtils.isNotBlank(plan.getSourceExternalRef())) {
            command.setContextParam("sourceVmUuid", plan.getSourceExternalRef());
        }
        return command;
    }

    private Long resolveAuthoritySequenceFloor(DrPlanVO plan) {
        long floor = 0L;
        DrPlanRuntimeVO runtime = drPlanRuntimeDao.findByPlanId(plan.getId());
        if (runtime != null) {
            floor = Math.max(floor, runtime.getAuthoritySequence());
        }
        DrSyncCycleVO latestCompleted = drSyncCycleDao.findLatestCompletedByPlanId(plan.getId());
        if (latestCompleted != null && latestCompleted.getAuthoritySequence() != null) {
            floor = Math.max(floor, latestCompleted.getAuthoritySequence());
        }
        return floor > 0L ? floor : null;
    }

    private boolean isImmutableCheckpoint(DrRestorePointVO checkpoint) {
        return checkpoint != null && "FTCTL_DR_IMMUTABLE_CHECKPOINT".equals(checkpoint.getRestorePointType());
    }

    private void restoreDurableCheckpoint(DrExecutionContext context, DrRestorePointVO checkpoint) {
        DrPlanVO plan = context.getPlan();
        JsonObject spec = parseObject(buildTestArtifactSpec(plan, context.getRun(), checkpoint));
        JsonObject request = new JsonObject();
        String ref = checkpoint.getSourceSnapshotRef();
        String prefix = "ftctl:" + plan.getUuid() + ":";
        if (!ref.startsWith(prefix) || ref.lastIndexOf(':') <= prefix.length()) {
            throw new CloudRuntimeException("DR_CHECKPOINT_IDENTITY_INVALID: unexpected checkpoint reference");
        }
        request.addProperty("planUuid", plan.getUuid());
        request.addProperty("producerRunUuid", ref.substring(prefix.length(), ref.lastIndexOf(':')));
        request.addProperty("checkpointSequence", checkpoint.getCheckpointSequence());
        request.addProperty("checkpointRef", ref);
        JsonArray disks = new JsonArray();
        for (JsonElement element : spec.getAsJsonArray("disks")) {
            JsonObject disk = element.getAsJsonObject();
            JsonObject target = new JsonObject();
            target.addProperty("device", firstString(disk, "device"));
            target.addProperty("provider", firstString(disk, "provider"));
            String locator = firstString(disk, "canonicalLocator");
            target.addProperty("canonicalLocator", locator);
            if ("FILE".equals(firstString(disk, "provider"))) {
                target.addProperty("storageRoot", locator.substring(5, locator.lastIndexOf('/')));
            }
            disks.add(target);
        }
        request.add("disks", disks);
        Long hostId = drWorkerPlacementService.resolveWorkerHostId(plan, DrWorkerRole.TARGET);
        if (hostId == null) {
            throw new CloudRuntimeException("DR_CHECKPOINT_TARGET_UNAVAILABLE");
        }
        FtctlDrActionCommand restore = new FtctlDrActionCommand(
                FtctlDrActionCommand.Action.CHECKPOINT_RESTORE, plan.getUuid(), context.getRun().getUuid());
        restore.setArtifactSpecJson(GSON.toJson(request));
        restore.setWaitForCompletion(true);
        restore.setWait(300);
        Answer answer = agentManager.easySend(hostId, restore);
        if (!(answer instanceof FtctlDrActionAnswer) || !answer.getResult()
                || !"RESTORED".equals(firstString(parseObject(((FtctlDrActionAnswer) answer).getStatusJson()), "restoreState"))) {
            throw new CloudRuntimeException("DR_CHECKPOINT_RESTORE_FAILED: "
                    + (answer != null ? answer.getDetails() : "target Agent unavailable"));
        }
    }

    private String buildTestArtifactSpec(DrPlanVO plan, DrRunVO run, DrRestorePointVO checkpoint) {
        JsonObject mapping = parseObject(plan.getMappingJson());
        JsonObject mappingTarget = objectAt(mapping, "target");
        JsonArray mappedDisks = firstArray(mapping, "disks", "diskMappings", "volumes", "volumeMappings");
        JsonArray artifactDisks = new JsonArray();
        for (int index = 0; index < mappedDisks.size(); index++) {
            JsonElement element = mappedDisks.get(index);
            if (element == null || !element.isJsonObject()) {
                throw new IllegalArgumentException("DR_TEST_ARTIFACT_SPEC_INVALID: disk mapping " + index + " is not an object");
            }
            JsonObject disk = element.getAsJsonObject();
            JsonObject target = objectAt(disk, "target");
            String storageType = firstNonBlank(firstString(target, "storagePoolType", "type", "targetType"),
                    firstString(mappingTarget, "storagePoolType", "type", "targetType"));
            String storagePath = firstNonBlank(firstString(target, "storagePath", "krbdPath"),
                    firstString(mappingTarget, "storagePath", "krbdPath"));
            String volumeRef = firstNonBlank(firstString(target, "path", "diskRef", "volumePath", "volumeUuid"),
                    firstString(disk, "targetRef", "targetDiskRef", "targetPath"));
            String provider = isRbdStorage(storageType, storagePath, volumeRef) ? "RBD" : "FILE";
            String canonicalLocator = canonicalArtifactLocator(provider, storageType, storagePath, volumeRef);
            JsonObject artifactDisk = new JsonObject();
            artifactDisk.addProperty("diskIndex", index);
            artifactDisk.addProperty("device", firstNonBlank(firstString(disk, "device", "label"), "disk" + index));
            artifactDisk.addProperty("provider", provider);
            artifactDisk.addProperty("canonicalLocator", canonicalLocator);
            artifactDisk.addProperty("format", StringUtils.defaultIfBlank(firstString(target, "format"),
                    StringUtils.equals(provider, "RBD") ? "raw" : "qcow2"));
            addLongIfPresent(artifactDisk, "targetVolumeId", firstLong(target, "volumeId", "targetVolumeId"));
            String sizeBytes = firstNonBlank(firstString(target, "capacityBytes", "sizeBytes"),
                    firstString(disk, "capacityBytes", "sizeBytes"));
            if (StringUtils.isNotBlank(sizeBytes)) {
                artifactDisk.addProperty("sizeBytes", sizeBytes);
            }
            artifactDisks.add(artifactDisk);
        }
        if (artifactDisks.size() == 0) {
            throw new IllegalArgumentException("DR_TEST_ARTIFACT_SPEC_INVALID: no mapped target disks");
        }
        JsonObject spec = new JsonObject();
        spec.addProperty("contractVersion", TEST_ARTIFACT_CONTRACT_VERSION);
        spec.addProperty("planUuid", plan.getUuid());
        spec.addProperty("runUuid", run.getUuid());
        spec.addProperty("checkpointImmutableRequired", isSharedMountPointFilePlan(plan));
        if (checkpoint != null) {
            addControllerCheckpointEvidence(spec, plan, checkpoint);
        }
        spec.add("disks", artifactDisks);
        return GSON.toJson(spec);
    }

    private void addControllerCheckpointEvidence(JsonObject target, DrPlanVO plan, DrRestorePointVO checkpoint) {
        target.addProperty("checkpointContractVersion", 1);
        target.addProperty("checkpointPlanUuid", plan.getUuid());
        target.addProperty("checkpointRef", checkpoint.getSourceSnapshotRef());
        target.addProperty("restorePointRef", checkpoint.getSourceSnapshotRef());
        if (checkpoint.getCheckpointSequence() != null) {
            target.addProperty("checkpointSequence", checkpoint.getCheckpointSequence());
        }
        target.addProperty("checkpointState", checkpoint.getState());
        target.addProperty("checkpointCycleType", checkpoint.getCheckpointCycleType());
        target.addProperty("checkpointCycleToken", checkpoint.getCycleToken());
        target.addProperty("checkpointEffectiveMode", checkpoint.getEffectiveMode());
        if (checkpoint.getSourceCreated() != null) {
            target.addProperty("checkpointSourceCreatedAt", checkpoint.getSourceCreated().toInstant().toString());
        }
        if (checkpoint.getTargetReadyAt() != null) {
            target.addProperty("checkpointTargetReadyAt", checkpoint.getTargetReadyAt().toInstant().toString());
        }
        if (checkpoint.getTargetReadyRpoSeconds() != null) {
            target.addProperty("checkpointTargetReadyRpoSeconds", checkpoint.getTargetReadyRpoSeconds());
        }
        if (checkpoint.getIncrementalVerified() != null) {
            target.addProperty("checkpointIncrementalVerified", checkpoint.getIncrementalVerified());
        }
    }

    private boolean isRbdStorage(String storageType, String storagePath, String volumeRef) {
        return StringUtils.containsIgnoreCase(storageType, "RBD")
                || StringUtils.startsWithIgnoreCase(storagePath, "rbd")
                || StringUtils.startsWithIgnoreCase(volumeRef, "rbd:")
                || StringUtils.startsWithIgnoreCase(volumeRef, "/dev/rbd/");
    }

    private String canonicalArtifactLocator(String provider, String storageType, String storagePath, String volumeRef) {
        String ref = StringUtils.trimToNull(volumeRef);
        if (StringUtils.equals(provider, "RBD")) {
            if (ref != null && StringUtils.startsWithIgnoreCase(ref, "rbd:")) {
                return "rbd:" + StringUtils.removeStartIgnoreCase(ref, "rbd:");
            }
            if (ref != null && StringUtils.startsWith(ref, "/dev/rbd/")) {
                return "rbd:" + StringUtils.removeStart(ref, "/dev/rbd/");
            }
            String pool = StringUtils.trimToNull(storagePath);
            if (pool != null && StringUtils.startsWithIgnoreCase(pool, "rbd:")) {
                pool = StringUtils.removeStartIgnoreCase(pool, "rbd:");
            }
            if (pool != null && StringUtils.startsWith(pool, "/dev/rbd/")) {
                pool = StringUtils.removeStart(pool, "/dev/rbd/");
            }
            if (ref == null || StringUtils.isBlank(pool)) {
                throw new IllegalArgumentException("DR_TEST_ARTIFACT_LOCATOR_INVALID: RBD pool and image are required");
            }
            return StringUtils.contains(ref, "/") ? "rbd:" + ref : "rbd:" + StringUtils.removeEnd(pool, "/") + "/" + ref;
        }
        if (StringUtils.equalsIgnoreCase(storageType, "SharedMountPoint")) {
            return "file:" + canonicalSharedMountPath(storagePath, ref);
        }
        if (StringUtils.isBlank(ref) || !StringUtils.startsWith(ref, "/")) {
            throw new IllegalArgumentException("DR_TEST_ARTIFACT_LOCATOR_INVALID: file-backed disk requires an absolute path");
        }
        return "file:" + ref;
    }

    private String canonicalSharedMountPath(String storagePath, String volumeRef) {
        String rootValue = StringUtils.trimToNull(storagePath);
        String refValue = StringUtils.trimToNull(volumeRef);
        if (rootValue == null || refValue == null) {
            throw new IllegalArgumentException("DR_TEST_ARTIFACT_LOCATOR_INVALID: SharedMountPoint root and volume path are required");
        }
        try {
            Path root = Paths.get(rootValue).normalize();
            if (!root.isAbsolute()) {
                throw new IllegalArgumentException("DR_TEST_ARTIFACT_LOCATOR_INVALID: SharedMountPoint root must be absolute");
            }
            Path ref = Paths.get(refValue);
            Path candidate = (ref.isAbsolute() ? ref : root.resolve(ref)).normalize();
            if (!candidate.isAbsolute() || candidate.equals(root) || !candidate.startsWith(root)) {
                throw new IllegalArgumentException("DR_TEST_ARTIFACT_LOCATOR_INVALID: SharedMountPoint volume path escapes the storage root");
            }
            return candidate.toString();
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("DR_TEST_ARTIFACT_LOCATOR_INVALID: SharedMountPoint volume path is invalid", e);
        }
    }

    private void addLongIfPresent(JsonObject object, String key, Long value) {
        if (value != null) {
            object.addProperty(key, value);
        }
    }

    private Long firstLong(JsonObject object, String... keys) {
        String value = firstString(object, keys);
        if (StringUtils.isBlank(value)) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private DrAdapterResult validateLatestCheckpoint(DrExecutionContext context, FtctlDrActionCommand.Action action) {
        if (!requiresLatestCheckpoint(action)) {
            return null;
        }
        DrRestorePointVO latest = drRestorePointDao.findLatestTargetReadyByPlanId(context.getPlan().getId());
        if (latest != null && StringUtils.isNotBlank(latest.getSourceSnapshotRef())) {
            return null;
        }
        return DrAdapterResult.failure(DrConstants.ERROR_TARGET_NOT_READY,
                "The latest synchronized target checkpoint is not ready",
                GSON.toJson(buildExecutionDetails(context, action, resolveCoordinatorHostId(context.getPlan(), context.getRun()))));
    }

    private boolean requiresLatestCheckpoint(FtctlDrActionCommand.Action action) {
        return action == FtctlDrActionCommand.Action.TEST_PREPARE || action == FtctlDrActionCommand.Action.FAILOVER;
    }

    private boolean usesLatestCheckpointEvidence(FtctlDrActionCommand.Action action) {
        return requiresLatestCheckpoint(action) || action == FtctlDrActionCommand.Action.RECOVER_SYNC;
    }

    private DrAdapterResult validateFailoverIsolation(DrExecutionContext context, FtctlDrActionCommand.Action action) {
        if (action != FtctlDrActionCommand.Action.FAILOVER) {
            return null;
        }
        JsonObject request = requestJson(context.getRun());
        if (!StringUtils.equalsIgnoreCase(requestString(request, "mode"), "disaster")) {
            return null;
        }
        boolean acknowledged = requestBoolean(request, "sourceIsolationAcknowledged", false);
        String reason = requestString(request, "sourceIsolationReason");
        if (acknowledged && StringUtils.isNotBlank(reason)) {
            return null;
        }
        JsonObject details = buildExecutionDetails(context, action, resolveCoordinatorHostId(context.getPlan(), context.getRun()));
        details.addProperty("sourceIsolationAcknowledged", acknowledged);
        return DrAdapterResult.failure(DrConstants.ERROR_SOURCE_ISOLATION_UNCONFIRMED,
                "Disaster failover requires source isolation acknowledgement and a reason", GSON.toJson(details));
    }

    private DrAdapterResult validateCapabilities(DrExecutionContext context, FtctlDrActionCommand.Action action, long hostId) {
        FtctlDrCapabilitiesCommand command = new FtctlDrCapabilitiesCommand(context.getPlan().getUuid(), context.getRun().getUuid());
        List<String> requiredActions = new ArrayList<String>();
        requiredActions.add(action.name());
        command.setRequiredActions(requiredActions);
        List<String> requiredCliCommands = new ArrayList<String>();
        requiredCliCommands.add(action.getCliCommand());
        requiredCliCommands.add("dr-status");
        command.setRequiredCliCommands(requiredCliCommands);
        List<String> requiredFeatures = new ArrayList<String>();
        if (sourceIndependentTest(context.getRun())) requiredFeatures.add("dr-source-independent-test-v1");
        if (action == FtctlDrActionCommand.Action.TEST_PREPARE
                || action == FtctlDrActionCommand.Action.TEST_ARTIFACT_CLEANUP) {
            requiredFeatures.add("dr-cloud-test-recovery-v1");
        }
        if (action == FtctlDrActionCommand.Action.FAILBACK
                || action == FtctlDrActionCommand.Action.REPROTECT) {
            requiredFeatures.add("dr-transition-preflight-v2");
        }
        if (isRemoteKvmToKvmPlan(context.getPlan())
                && action == FtctlDrActionCommand.Action.FAILBACK) {
            requiredFeatures.add("dr-reverse-site-agent-rbd-transport-v1");
            requiredFeatures.add("dr-remote-source-failback-commit-v1");
        }
        if (action == FtctlDrActionCommand.Action.RELEASE) {
            requiredFeatures.add("dr-release-tombstone-v1");
        }
        if (action == FtctlDrActionCommand.Action.TEST_PREPARE
                && isSharedMountPointFilePlan(context.getPlan())) {
            requiredFeatures.add("file-checkpoint-invariance-v1");
        }
        if (action == FtctlDrActionCommand.Action.FAILOVER
                && isSharedMountPointFilePlan(context.getPlan())
                && StringUtils.equalsIgnoreCase(requestString(requestJson(context.getRun()), "mode"), "planned")) {
            requiredFeatures.add("dr-file-planned-failover-runtime-quiesce-v2");
        }
        if (action == FtctlDrActionCommand.Action.FAILOVER
                && isRemoteKvmToKvmPlan(context.getPlan())
                && DrFailoverExecutionPolicy.isDisaster(context.getRun())) {
            requiredFeatures.add("dr-target-disaster-promote-v1");
        }
        if (dispatchesOnRemoteSource(context.getPlan(), context.getRun(), action)
                && (action == FtctlDrActionCommand.Action.SYNC
                        || action == FtctlDrActionCommand.Action.RECOVER_SYNC
                        || action == FtctlDrActionCommand.Action.FAILOVER)) {
            requiredFeatures.add("dr-site-agent-rbd-transport-v1");
        }
        command.setRequiredFeatures(requiredFeatures);
        try {
            Answer answer = dispatchesOnRemoteSource(context.getPlan(), context.getRun(), action)
                    ? drRemoteAgentClient.execute(context.getPlan(), "CAPABILITIES", command,
                            null, FtctlDrCapabilitiesAnswer.class)
                    : agentManager.send(hostId, command);
            JsonObject details = buildExecutionDetails(context, action, hostId);
            details.add("capabilityCheck", GSON.toJsonTree(redactedCapabilities(answer)));
            if (!(answer instanceof FtctlDrCapabilitiesAnswer)) {
                String message = answer != null ? answer.getDetails() : "Agent returned no FTCTL_DR capability answer";
                details.addProperty("answerType", answer != null ? answer.getClass().getName() : null);
                return DrAdapterResult.failure(DrConstants.ERROR_AGENT_CAPABILITY_MISMATCH, message, GSON.toJson(details));
            }
            FtctlDrCapabilitiesAnswer capabilities = (FtctlDrCapabilitiesAnswer) answer;
            if (!capabilities.getResult()) {
                String message = StringUtils.defaultIfBlank(capabilities.getDetails(), "FTCTL_DR Agent capability mismatch");
                return DrAdapterResult.failure(DrConstants.ERROR_AGENT_CAPABILITY_MISMATCH, message, GSON.toJson(details));
            }
            if (requiresControlProtocol(action) && !supportsFeature(capabilities.getSupportedFeatures(), "control-protocol-v2")) {
                return DrAdapterResult.failure(DrConstants.ERROR_CONTROL_PROTOCOL_UNSUPPORTED,
                        "FTCTL_DR control protocol v2 is required for coordinated DR actions", GSON.toJson(details));
            }
            if (action == FtctlDrActionCommand.Action.REPROTECT
                    && !supportsFeature(capabilities.getReprotectAuthorityContractVersions(),
                            DrReprotectAuthoritySpec.CONTRACT_VERSION)) {
                details.addProperty("requiredReprotectAuthorityContractVersion",
                        DrReprotectAuthoritySpec.CONTRACT_VERSION);
                details.add("supportedReprotectAuthorityContractVersions",
                        GSON.toJsonTree(capabilities.getReprotectAuthorityContractVersions()));
                return DrAdapterResult.failure(DrConstants.ERROR_AGENT_CAPABILITY_MISMATCH,
                        "FTCTL_DR host does not support the Reprotect authority contract produced by Cloud",
                        GSON.toJson(details));
            }
            if (requiresVmwareGuestPreparation(context, action)) {
                String missingFeature = action == FtctlDrActionCommand.Action.TEST_PREPARE
                        ? firstMissingFeature(capabilities.getSupportedFeatures(),
                                "guest-preparation-v2", "test-artifact-lifecycle-v2")
                        : firstMissingFeature(capabilities.getSupportedFeatures(),
                                "guest-preparation-v2", "cutover-ready-v1", "cutover-manifest-v2", "cutover-preflight-v1");
                if (missingFeature != null) {
                    details.addProperty("missingFeature", missingFeature);
                    return DrAdapterResult.failure(DrConstants.ERROR_AGENT_CAPABILITY_MISMATCH,
                            "FTCTL_DR host does not provide required guest preparation capability: " + missingFeature,
                            GSON.toJson(details));
                }
            }
            return null;
        } catch (OperationTimedoutException e) {
            LOGGER.warn("FTCTL_DR capability check timed out for run {} on host {}: {}", context.getRun().getId(), hostId, e.getMessage());
            return DrAdapterResult.failure(DrConstants.ERROR_AGENT_CAPABILITY_MISMATCH,
                    "FTCTL_DR capability check timed out: " + e.getMessage(), GSON.toJson(buildExecutionDetails(context, action, hostId)));
        } catch (AgentUnavailableException e) {
            LOGGER.warn("FTCTL_DR capability check failed because Agent is unavailable for run {} on host {}: {}",
                    context.getRun().getId(), hostId, e.getMessage());
            return DrAdapterResult.failure(DrConstants.ERROR_AGENT_UNAVAILABLE,
                    "FTCTL_DR coordinator Agent is unavailable: " + e.getMessage(), GSON.toJson(buildExecutionDetails(context, action, hostId)));
        } catch (CloudRuntimeException e) {
            LOGGER.warn("FTCTL_DR remote capability check failed for run {}: {}", context.getRun().getId(), e.getMessage());
            return DrAdapterResult.retryable(DrConstants.ERROR_ENGINE_UNAVAILABLE,
                    "FTCTL_DR remote capability check failed: " + e.getMessage(),
                    GSON.toJson(buildExecutionDetails(context, action, hostId)), 10);
        }
    }

    private boolean requiresControlProtocol(FtctlDrActionCommand.Action action) {
        return action != null && action != FtctlDrActionCommand.Action.TARGET_MATERIALIZED;
    }

    private boolean supportsFeature(List<String> features, String requiredFeature) {
        if (features == null) {
            return false;
        }
        for (String feature : features) {
            if (StringUtils.equalsIgnoreCase(feature, requiredFeature)) {
                return true;
            }
        }
        return false;
    }

    private boolean requiresVmwareGuestPreparation(DrExecutionContext context, FtctlDrActionCommand.Action action) {
        return context != null && context.getPlan() != null
                && StringUtils.equalsIgnoreCase(context.getPlan().getDirection(), "VMWARE_TO_KVM")
                && (action == FtctlDrActionCommand.Action.TEST_PREPARE || action == FtctlDrActionCommand.Action.FAILOVER);
    }

    private String firstMissingFeature(List<String> features, String... requiredFeatures) {
        for (String requiredFeature : requiredFeatures) {
            if (!supportsFeature(features, requiredFeature)) {
                return requiredFeature;
            }
        }
        return null;
    }

    private DrAdapterResult probeAcceptedStatus(DrExecutionContext context, FtctlDrActionCommand.Action action, long hostId) {
        try {
            FtctlDrStatusCommand statusCommand = new FtctlDrStatusCommand(context.getPlan().getUuid(), context.getRun().getUuid());
            statusCommand.setWait(10);
            Answer answer = dispatchesOnRemoteSource(context.getPlan(), context.getRun(), action)
                    ? drRemoteAgentClient.execute(context.getPlan(), "STATUS", statusCommand,
                            null, FtctlDrStatusAnswer.class)
                    : agentManager.easySend(hostId, statusCommand);
            if (!(answer instanceof FtctlDrStatusAnswer)) {
                return null;
            }
            FtctlDrStatusAnswer status = (FtctlDrStatusAnswer) answer;
            if (!isAcceptedStatus(status)) {
                return null;
            }
            JsonObject details = buildExecutionDetails(context, action, hostId);
            details.add("statusProbe", GSON.toJsonTree(redactedStatus(status)));
            String externalJobRef = StringUtils.defaultIfBlank(stringValue(parseObject(status.getStatusJson()), "external_job_ref"), context.getRun().getUuid());
            return DrAdapterResult.accepted("FTCTL_DR action " + action + " accepted by Agent after timeout status probe",
                    GSON.toJson(details), externalJobRef);
        } catch (RuntimeException e) {
            LOGGER.debug("Ignoring FTCTL_DR status probe failure for run {} after dispatch timeout: {}",
                    context.getRun().getId(), e.getMessage());
            return null;
        }
    }

    private DrAdapterResult toAdapterResult(DrExecutionContext context, FtctlDrActionCommand.Action action, long hostId, Answer answer) {
        JsonObject details = buildExecutionDetails(context, action, hostId);
        if (!(answer instanceof FtctlDrActionAnswer)) {
            String message = answer != null ? answer.getDetails() : "Agent returned no FTCTL_DR answer";
            details.addProperty("answerType", answer != null ? answer.getClass().getName() : null);
            return DrAdapterResult.failure(DrConstants.ERROR_ENGINE_ACTION_FAILED, message, GSON.toJson(details));
        }

        FtctlDrActionAnswer actionAnswer = (FtctlDrActionAnswer) answer;
        details.add("agentAnswer", GSON.toJsonTree(redactedAnswer(actionAnswer)));
        if (!actionAnswer.getResult() || hasSemanticFailure(actionAnswer)) {
            JsonObject lockPayload = retryableLockPayload(actionAnswer);
            if (lockPayload != null) {
                details.add("retryableLock", lockPayload);
                Integer retryAfterSeconds = integerValue(lockPayload, "retry_after_sec");
                String holderCommand = stringValue(lockPayload, "holder_command");
                String message = "FTCTL_DR engine is busy";
                if (StringUtils.isNotBlank(holderCommand)) {
                    message += " while " + holderCommand + " is holding the lock";
                }
                return DrAdapterResult.retryable(DrConstants.ERROR_ENGINE_BUSY_RETRYABLE, message, GSON.toJson(details), retryAfterSeconds);
            }
            String errorCode = StringUtils.defaultIfBlank(actionAnswer.getErrorCode(),
                    StringUtils.defaultIfBlank(stringValue(parseObject(actionAnswer.getStatusJson()), "error_code"),
                            DrConstants.ERROR_ENGINE_ACTION_FAILED));
            String message = StringUtils.defaultIfBlank(actionAnswer.getDetails(), "FTCTL_DR Agent command failed");
            return DrAdapterResult.failure(errorCode, message, GSON.toJson(details));
        }

        String result = StringUtils.lowerCase(StringUtils.defaultString(actionAnswer.getFtctlResult()), Locale.ROOT);
        boolean accepted = Boolean.TRUE.equals(actionAnswer.getAccepted())
                || StringUtils.equalsAny(result, "accepted", "ok", "success", "delegated", "warn")
                || Integer.valueOf(0).equals(actionAnswer.getExitCode());
        if (!accepted) {
            String message = "FTCTL_DR Agent command did not return an accepted result";
            return DrAdapterResult.failure(DrConstants.ERROR_ENGINE_ACTION_FAILED, message, GSON.toJson(details));
        }

        String externalJobRef = StringUtils.defaultIfBlank(actionAnswer.getExternalJobRef(), context.getRun().getUuid());
        if (isTerminalControlAction(context.getRun())) {
            return DrAdapterResult.success("FTCTL_DR control action " + action + " accepted by Agent", GSON.toJson(details));
        }
        return DrAdapterResult.accepted("FTCTL_DR action " + action + " accepted by Agent", GSON.toJson(details), externalJobRef);
    }

    private boolean isAcceptedStatus(FtctlDrStatusAnswer status) {
        if (status == null) {
            return false;
        }
        JsonObject runtime = parseObject(status.getStatusJson());
        String result = StringUtils.lowerCase(StringUtils.defaultIfBlank(status.getFtctlResult(), stringValue(runtime, "result")), Locale.ROOT);
        String state = StringUtils.upperCase(StringUtils.defaultIfBlank(status.getState(), stringValue(runtime, "state")), Locale.ROOT);
        String errorCode = stringValue(runtime, "error_code");
        return status.getResult()
                && !isExplicitlyFalse(runtime, "accepted")
                && StringUtils.isBlank(errorCode)
                && !StringUtils.equalsAny(state, "ERROR", "FAILED", "REJECTED", "CANCELED", "CANCELLED")
                && (booleanValue(runtime, "accepted")
                || StringUtils.equalsAny(result, "accepted", "ok", "success", "delegated", "warn")
                || StringUtils.equalsAny(state, "SYNCING", "RUNNING", "READY", "TARGET_READY", "PAUSED", "TESTING",
                        "TEST_ARTIFACTS_READY", "ARTIFACTS_READY"));
    }

    private boolean hasSemanticFailure(FtctlDrActionAnswer answer) {
        JsonObject runtime = parseObject(answer.getStatusJson());
        String state = StringUtils.upperCase(StringUtils.defaultIfBlank(answer.getState(), stringValue(runtime, "state")), Locale.ROOT);
        String payloadErrorCode = stringValue(runtime, "error_code");
        String answerErrorCode = answer.getErrorCode();
        if (isAcceptedAgentContract(answer, runtime, state, payloadErrorCode)
                && StringUtils.equalsIgnoreCase(answerErrorCode, "DR_AGENT_ACCEPT_TIMEOUT")) {
            answerErrorCode = null;
        }
        String errorCode = StringUtils.defaultIfBlank(answerErrorCode, payloadErrorCode);
        boolean explicitlyRejected = Boolean.FALSE.equals(answer.getAccepted())
                || isExplicitlyFalse(runtime, "accepted");
        return explicitlyRejected
                || StringUtils.isNotBlank(errorCode)
                || StringUtils.equalsAny(state, "ERROR", "FAILED", "REJECTED", "CANCELED", "CANCELLED");
    }

    private boolean isAcceptedAgentContract(FtctlDrActionAnswer answer, JsonObject runtime, String state,
            String payloadErrorCode) {
        String result = StringUtils.lowerCase(StringUtils.defaultIfBlank(answer.getFtctlResult(),
                stringValue(runtime, "result")), Locale.ROOT);
        return answer.getResult()
                && Integer.valueOf(0).equals(answer.getExitCode())
                && !Boolean.FALSE.equals(answer.getAccepted())
                && !isExplicitlyFalse(runtime, "accepted")
                && StringUtils.isBlank(payloadErrorCode)
                && !StringUtils.equalsAny(state, "ERROR", "FAILED", "REJECTED", "CANCELED", "CANCELLED")
                && (Boolean.TRUE.equals(answer.getAccepted())
                        || booleanValue(runtime, "accepted")
                        || StringUtils.equalsAny(result, "accepted", "ok", "success", "delegated", "warn"));
    }

    private FtctlDrActionCommand.Action resolveAction(DrRunVO run) {
        String runType = StringUtils.upperCase(run.getRunType(), Locale.ROOT);
        if (StringUtils.equals(runType, DrConstants.RUN_TYPE_SYNC)) {
            return FtctlDrActionCommand.Action.SYNC;
        }
        if (StringUtils.equals(runType, DrConstants.RUN_TYPE_RECOVER_SYNC)) {
            return FtctlDrActionCommand.Action.RECOVER_SYNC;
        }
        if (StringUtils.equals(runType, DrConstants.RUN_TYPE_PAUSE_SYNC)) {
            return FtctlDrActionCommand.Action.PAUSE_SYNC;
        }
        if (StringUtils.equals(runType, DrConstants.RUN_TYPE_RESUME_SYNC)) {
            return FtctlDrActionCommand.Action.RESUME_SYNC;
        }
        if (StringUtils.equals(runType, DrConstants.RUN_TYPE_TEST_FAILOVER)) {
            return FtctlDrActionCommand.Action.TEST_PREPARE;
        }
        if (StringUtils.equals(runType, DrConstants.RUN_TYPE_TEST_CLEANUP)) {
            return FtctlDrActionCommand.Action.TEST_ARTIFACT_CLEANUP;
        }
        if (StringUtils.equals(runType, DrConstants.RUN_TYPE_FAILOVER)) {
            return FtctlDrActionCommand.Action.FAILOVER;
        }
        if (StringUtils.equals(runType, DrConstants.RUN_TYPE_FAILBACK)) {
            return FtctlDrActionCommand.Action.FAILBACK;
        }
        if (StringUtils.equals(runType, DrConstants.RUN_TYPE_REPROTECT)) {
            return FtctlDrActionCommand.Action.REPROTECT;
        }
        if (StringUtils.equals(runType, DrConstants.RUN_TYPE_RELEASE)) {
            return FtctlDrActionCommand.Action.RELEASE;
        }
        return null;
    }

    private boolean isTerminalControlAction(DrRunVO run) {
        return StringUtils.equalsAny(StringUtils.upperCase(run.getRunType(), Locale.ROOT),
                DrConstants.RUN_TYPE_RECOVER_SYNC, DrConstants.RUN_TYPE_PAUSE_SYNC, DrConstants.RUN_TYPE_RESUME_SYNC,
                DrConstants.RUN_TYPE_TEST_CLEANUP, DrConstants.RUN_TYPE_RELEASE);
    }

    private Long resolveCoordinatorHostId(DrPlanVO plan) {
        return resolveCoordinatorHostId(plan, null);
    }

    private Long resolveCoordinatorHostId(DrPlanVO plan, DrRunVO run) {
        DrWorkerRole role = !targetOnlyRecovery(run) && StringUtils.startsWithIgnoreCase(plan != null ? plan.getDirection() : null, "VMWARE_")
                ? DrWorkerRole.VDDK_DATA_PLANE : DrWorkerRole.COORDINATOR;
        return resolveWorkerHostId(plan, run, role);
    }

    private Long resolveWorkerHostId(DrPlanVO plan, DrRunVO run, DrWorkerRole role) {
        return drWorkerPlacementService != null
                ? drWorkerPlacementService.resolveWorkerHostId(plan, run, role) : null;
    }

    private String resolveHostUuid(Long hostId) {
        if (hostId == null) {
            return null;
        }
        HostVO host = hostDao != null ? hostDao.findById(hostId) : null;
        return host != null && StringUtils.isNotBlank(host.getUuid()) ? host.getUuid() : String.valueOf(hostId);
    }

    private String buildProfileJson(DrPlanVO plan, DrRunVO run, JsonObject request) {
        return buildProfileJson(plan, run, request, null);
    }

    private String buildProfileJson(DrPlanVO plan, DrRunVO run, JsonObject request,
            DrSourceVmHardware sourceHardware) {
        return buildProfileJson(plan, run, request, sourceHardware, false);
    }

    private String buildProfileJson(DrPlanVO plan, DrRunVO run, JsonObject request,
            DrSourceVmHardware sourceHardware, boolean restoringSource) {
        JsonObject profile = new JsonObject();
        profile.addProperty("version", 1);
        profile.addProperty("engine", DrConstants.ENGINE_TYPE_FTCTL_DR);
        profile.addProperty("planUuid", plan.getUuid());
        profile.addProperty("runUuid", run.getUuid());
        // The scheduler session belongs to the Plan, while runUuid identifies
        // the finite API operation that requested a transition.
        profile.addProperty("schedulerSessionUuid", plan.getUuid());
        profile.addProperty("direction", plan.getDirection());
        profile.addProperty("activeSide", plan.getActiveSide());
        profile.addProperty("rpoTargetSeconds", plan.getRpoSeconds());
        profile.addProperty("rtoTargetSeconds", plan.getRtoSeconds());
        DrSiteVO sourceSite = drSiteDao != null ? drSiteDao.findById(plan.getSourceSiteId()) : null;
        DrSiteVO targetSite = drSiteDao != null ? drSiteDao.findById(plan.getTargetSiteId()) : null;
        JsonObject mapping = parseObject(plan.getMappingJson());
        if (sourceHardware != null) {
            JsonObject source = objectAt(mapping, "source");
            source.add("hardware", sourceHardware.toJsonObject());
            mapping.add("source", source);
        }
        profile.add("source", buildSourceEndpoint(plan, sourceSite, mapping));
        profile.add("target", buildTargetEndpoint(plan, targetSite, mapping));
        profile.add("credentials", buildCredentials(plan, !restoringSource && targetOnlyRecovery(run) ? null : sourceSite, targetSite));
        profile.add("workers", buildWorkers(plan, restoringSource ? null : run));
        profile.add("transport", buildTransport(plan));
        profile.add("policy", parseObject(plan.getPolicyJson()));
        profile.add("mapping", mapping);
        JsonObject schedule = parseObject(plan.getScheduleJson());
        if (request.has("scheduleJitterSeconds") && request.get("scheduleJitterSeconds").isJsonPrimitive()) {
            schedule.addProperty("jitterSeconds", Math.max(0, request.get("scheduleJitterSeconds").getAsInt()));
        }
        profile.add("schedule", schedule);
        profile.add("quiescePolicy", parseObject(plan.getQuiescePolicyJson()));
        if ("ABLESTACK".equalsIgnoreCase(firstString(profile.getAsJsonObject("target"), "provider"))) {
            request.addProperty("durableCheckpointProtocol", 1);
        }
        profile.add("request", request);
        return GSON.toJson(profile);
    }

    private JsonObject buildTargetEndpoint(DrPlanVO plan, DrSiteVO targetSite, JsonObject mapping) {
        JsonObject endpoint = buildEndpoint(plan.getDirection(), false, targetSite, null, null);
        JsonObject targetMapping = objectAt(mapping, "target");
        for (Map.Entry<String, JsonElement> entry : targetMapping.entrySet()) {
            if (!endpoint.has(entry.getKey()) && entry.getValue() != null && !entry.getValue().isJsonNull()) {
                endpoint.add(entry.getKey(), entry.getValue().deepCopy());
            }
        }
        if (drReplicaDao != null && userVmDao != null) {
            List<DrReplicaVO> replicas = drReplicaDao.listActiveByPlanId(plan.getId());
            if (replicas != null) {
                for (DrReplicaVO replica : replicas) {
                    if (replica == null || replica.getTargetVmId() == null) {
                        continue;
                    }
                    UserVmVO vm = userVmDao.findById(replica.getTargetVmId());
                    if (vm != null && vm.getRemoved() == null) {
                        endpoint.addProperty("vmId", vm.getId());
                        endpoint.addProperty("vmUuid", vm.getUuid());
                        endpoint.addProperty("instanceName", vm.getInstanceName());
                        endpoint.addProperty("vmName", vm.getDisplayName());
                        endpoint.addProperty("hostId", vm.getHostId());
                        break;
                    }
                }
            }
        }
        return endpoint;
    }

    private JsonObject buildSourceEndpoint(DrPlanVO plan, DrSiteVO sourceSite, JsonObject mapping) {
        JsonObject endpoint = buildEndpoint(plan.getDirection(), true, sourceSite,
                plan.getSourceVmId(), plan.getSourceExternalRef());
        JsonObject sourceMapping = objectAt(mapping, "source");
        for (Map.Entry<String, JsonElement> entry : sourceMapping.entrySet()) {
            if (!endpoint.has(entry.getKey()) && entry.getValue() != null && !entry.getValue().isJsonNull()) {
                endpoint.add(entry.getKey(), entry.getValue().deepCopy());
            }
        }
        JsonObject hardware = objectAt(sourceMapping, "hardware");
        addIfNotBlank(endpoint, "hostUuid", firstString(hardware, "sourceHostUuid", "hostUuid"));
        addIfNotBlank(endpoint, "hostName", firstString(hardware, "sourceHostName", "hostName"));
        addIfNotBlank(endpoint, "instanceName", firstString(hardware, "instanceName"));
        return endpoint;
    }

    private JsonObject buildEndpoint(String direction, boolean source, DrSiteVO site, Long vmId, String externalRef) {
        JsonObject endpoint = new JsonObject();
        boolean vmware = source ? StringUtils.startsWith(direction, "VMWARE_") : StringUtils.endsWith(direction, "_VMWARE");
        endpoint.addProperty("provider", vmware ? "VMWARE" : "ABLESTACK");
        endpoint.addProperty("driver", vmware ? (source ? "VMWARE_CBT" : "VMWARE_VDDK") : (source ? "KVM_QMP" : "ABLESTACK"));
        if (source && vmware) {
            JsonObject cbtPolicy = new JsonObject();
            cbtPolicy.addProperty("required", true);
            cbtPolicy.addProperty("autoEnable", true);
            cbtPolicy.addProperty("failIfPreExistingSnapshots", false);
            endpoint.add("cbtPolicy", cbtPolicy);
        }
        if (site != null) {
            endpoint.addProperty("siteId", site.getId());
            endpoint.addProperty("siteUuid", site.getUuid());
            endpoint.addProperty("siteName", site.getName());
            endpoint.addProperty("siteType", site.getSiteType());
            endpoint.addProperty("hypervisorType", site.getHypervisorType());
            endpoint.addProperty("endpoint", site.getEndpoint());
        }
        if (vmId != null) {
            endpoint.addProperty("vmId", vmId);
        }
        if (StringUtils.isNotBlank(externalRef)) {
            endpoint.addProperty("externalRef", externalRef);
        }
        return endpoint;
    }

    private JsonObject buildCredentials(DrPlanVO plan, DrSiteVO sourceSite, DrSiteVO targetSite) {
        JsonObject credentials = new JsonObject();
        addCredential(credentials, "source", sourceSite, plan, true);
        addCredential(credentials, "target", targetSite, plan, false);
        return credentials;
    }

    private void addCredential(JsonObject credentials, String key, DrSiteVO site, DrPlanVO plan, boolean source) {
        if (drSiteCredentialService == null || site == null) {
            return;
        }
        if (StringUtils.equalsIgnoreCase(plan.getDirection(), DrConstants.DIRECTION_KVM_TO_KVM)) {
            return;
        }
        DrResolvedSiteCredential credential = null;
        try {
            credential = drSiteCredentialService.resolveCredential(site);
            if (credential != null && credential.hasSecrets()) {
                JsonObject runtime = credential.toRuntimeJson();
                if (source && isVmwareSourcePlan(plan)) {
                    enrichVmwareSourceCredential(runtime, plan);
                }
                credentials.add(key, runtime);
            }
        } finally {
            if (credential != null) {
                credential.close();
            }
        }
    }

    private boolean isVmwareSourcePlan(DrPlanVO plan) {
        return plan != null && StringUtils.startsWithIgnoreCase(plan.getDirection(), "VMWARE_");
    }

    private void enrichVmwareSourceCredential(JsonObject credential, DrPlanVO plan) {
        Long hostId = resolveVmwareDataPlaneHostId(plan);
        if (hostId == null || credential == null) {
            return;
        }
        String libDir = hostDetailValue(hostId, Host.HOST_VDDK_LIB_DIR);
        if (StringUtils.isNotBlank(libDir)) {
            credential.addProperty("vddkLibdir", libDir);
        }
        String version = hostDetailValue(hostId, Host.HOST_VDDK_VERSION);
        if (StringUtils.isNotBlank(version)) {
            credential.addProperty("vddkVersion", version);
        }
        credential.addProperty("dataPlaneHostId", hostId);
        credential.addProperty("dataPlaneHostUuid", resolveHostUuid(hostId));
        enrichVmwareSourceThumbprint(credential);
    }

    private void enrichVmwareSourceThumbprint(JsonObject credential) {
        if (credential == null || booleanValue(credential, "tlsVerify")) {
            return;
        }
        String thumbprint = firstString(credential, "thumbprint", "tlsThumbprint");
        String thumbprintSource = firstString(credential, "thumbprintSource");
        if (StringUtils.isNotBlank(thumbprint) && !shouldRefreshAutoThumbprint(thumbprintSource)) {
            credential.addProperty("thumbprint", thumbprint);
            if (StringUtils.isBlank(firstString(credential, "thumbprintSource"))) {
                credential.addProperty("thumbprintSource", "runtime");
            }
            return;
        }
        String endpoint = firstString(credential, "endpoint");
        if (StringUtils.isBlank(endpoint)) {
            credential.addProperty("thumbprintPresent", false);
            credential.addProperty("thumbprintSource", "missing-endpoint");
            return;
        }
        try {
            thumbprint = DrSiteProbeSupport.fetchSha1Thumbprint(endpoint, VCENTER_THUMBPRINT_TIMEOUT_MS);
            if (StringUtils.isNotBlank(thumbprint)) {
                credential.addProperty("thumbprint", thumbprint);
                credential.addProperty("thumbprintPresent", true);
                credential.addProperty("thumbprintSource", "backend-auto");
                return;
            }
        } catch (Exception e) {
            LOGGER.warn("Unable to resolve vCenter thumbprint for DR source endpoint {}: {}", endpoint, e.getMessage());
        }
        if (StringUtils.isNotBlank(thumbprint)) {
            credential.addProperty("thumbprint", thumbprint);
            credential.addProperty("thumbprintPresent", true);
            credential.addProperty("thumbprintSource", "backend-auto-fallback");
            return;
        }
        credential.addProperty("thumbprintPresent", false);
        credential.addProperty("thumbprintSource", "backend-unresolved");
    }

    static boolean shouldRefreshAutoThumbprint(String thumbprintSource) {
        return StringUtils.equalsIgnoreCase(StringUtils.trim(thumbprintSource), "backend-auto")
                || StringUtils.equalsIgnoreCase(StringUtils.trim(thumbprintSource), "backend-auto-refreshed")
                || StringUtils.equalsIgnoreCase(StringUtils.trim(thumbprintSource), "backend-auto-fallback");
    }

    private Long resolveVmwareDataPlaneHostId(DrPlanVO plan) {
        return resolveWorkerHostId(plan, null, DrWorkerRole.VDDK_DATA_PLANE);
    }

    private String hostDetailValue(Long hostId, String name) {
        if (hostId == null || StringUtils.isBlank(name) || hostDetailsDao == null) {
            return null;
        }
        DetailVO detail = hostDetailsDao.findDetail(hostId, name);
        return detail != null ? StringUtils.trimToNull(detail.getValue()) : null;
    }

    private JsonObject buildWorkers(DrPlanVO plan, DrRunVO run) {
        JsonObject workers = new JsonObject();
        workers.addProperty("coordinator", resolveHostUuid(resolveWorkerHostId(plan, run, DrWorkerRole.COORDINATOR)));
        workers.addProperty("source", targetOnlyRecovery(run) || drRemoteAgentClient != null && drRemoteAgentClient.isRemoteKvmSource(plan)
                ? null : resolveHostUuid(resolveWorkerHostId(plan, run, DrWorkerRole.SOURCE)));
        workers.addProperty("target", resolveHostUuid(resolveWorkerHostId(plan, run, DrWorkerRole.TARGET)));
        return workers;
    }

    private JsonObject buildTransport(DrPlanVO plan) {
        JsonObject transport = new JsonObject();
        if (drRemoteAgentClient == null || !drRemoteAgentClient.isRemoteKvmSource(plan)) {
            transport.addProperty("mode", "local");
            return transport;
        }
        Long targetHostId = resolveWorkerHostId(plan, null, DrWorkerRole.TARGET);
        HostVO targetHost = targetHostId != null ? hostDao.findById(targetHostId) : null;
        JsonObject mapping = parseObject(plan.getMappingJson());
        JsonObject source = objectAt(mapping, "source");
        JsonObject hardware = objectAt(source, "hardware");
        String instanceName = firstNonBlank(firstString(hardware, "instanceName"),
                firstString(source, "instanceName"));
        transport.addProperty("mode", "site-agent-nbd");
        if (targetHost != null) {
            addIfNotBlank(transport, "targetHostUuid", targetHost.getUuid());
            addIfNotBlank(transport, "targetHostAddress", targetHost.getPrivateIpAddress());
            addIfNotBlank(transport, "remoteNbdExportAddress", targetHost.getPrivateIpAddress());
        }
        transport.addProperty("controlMode", "site-agent");
        transport.addProperty("targetStorageScope", "secondary-local");
        return transport;
    }

    private void addIfNotBlank(JsonObject object, String key, String value) {
        if (StringUtils.isNotBlank(value)) {
            object.addProperty(key, value);
        }
    }

    private DrAdapterResult validateVmwareCredentials(DrPlanVO plan, String direction) {
        if (drSiteDao == null || drSiteCredentialService == null) {
            return null;
        }
        if (StringUtils.startsWith(direction, "VMWARE_")) {
            DrSiteVO sourceSite = drSiteDao.findById(plan.getSourceSiteId());
            if (!drSiteCredentialService.hasUsableCredential(sourceSite)) {
                return DrAdapterResult.failure(DrConstants.ERROR_CREDENTIAL_INVALID,
                        "Source VMware DR site requires stored credentials", GSON.toJson(buildValidationDetails(plan)));
            }
        }
        if (StringUtils.endsWith(direction, "_VMWARE")) {
            DrSiteVO targetSite = drSiteDao.findById(plan.getTargetSiteId());
            if (!drSiteCredentialService.hasUsableCredential(targetSite)) {
                return DrAdapterResult.failure(DrConstants.ERROR_CREDENTIAL_INVALID,
                        "Target VMware DR site requires stored credentials", GSON.toJson(buildValidationDetails(plan)));
            }
        }
        return null;
    }

    private JsonObject buildValidationDetails(DrPlanVO plan) {
        JsonObject details = new JsonObject();
        details.addProperty("engineType", getEngineType());
        details.addProperty("engineBindingType", getEngineBindingType());
        if (plan != null) {
            details.addProperty("planId", plan.getId());
            details.addProperty("direction", plan.getDirection());
            details.addProperty("coordinatorWorkerHostId", plan.getCoordinatorWorkerHostId());
            details.addProperty("sourceWorkerHostId", plan.getSourceWorkerHostId());
            details.addProperty("targetWorkerHostId", plan.getTargetWorkerHostId());
        }
        details.addProperty("runtimeDispatchReady", true);
        details.addProperty("statusPollingReady", true);
        return details;
    }

    private JsonObject buildExecutionDetails(DrExecutionContext context, FtctlDrActionCommand.Action action, Long hostId) {
        JsonObject details = new JsonObject();
        details.addProperty("engineType", getEngineType());
        details.addProperty("engineBindingType", getEngineBindingType());
        details.addProperty("runType", context.getRun() != null ? context.getRun().getRunType() : null);
        details.addProperty("action", action != null ? action.name() : null);
        details.addProperty("agentHostId", hostId);
        details.addProperty("agentAcceptTimeoutSeconds", AGENT_ACCEPT_TIMEOUT_SECONDS);
        if (context.getPlan() != null) {
            details.addProperty("planId", context.getPlan().getId());
            details.addProperty("planUuid", context.getPlan().getUuid());
            details.addProperty("direction", context.getPlan().getDirection());
        }
        if (context.getRun() != null) {
            details.addProperty("runId", context.getRun().getId());
            details.addProperty("runUuid", context.getRun().getUuid());
            details.add("request", redactJson(requestJson(context.getRun())));
        }
        return details;
    }

    private JsonObject redactedAnswer(FtctlDrActionAnswer answer) {
        JsonObject object = new JsonObject();
        object.addProperty("action", answer.getAction() != null ? answer.getAction().name() : null);
        object.addProperty("planUuid", answer.getPlanUuid());
        object.addProperty("runUuid", answer.getRunUuid());
        object.addProperty("result", answer.getFtctlResult());
        object.addProperty("accepted", answer.getAccepted());
        object.addProperty("state", answer.getState());
        object.addProperty("step", answer.getStep());
        object.addProperty("progress", answer.getProgress());
        object.addProperty("externalJobRef", answer.getExternalJobRef());
        object.addProperty("eventsOffset", answer.getEventsOffset());
        object.addProperty("errorCode", answer.getErrorCode());
        object.addProperty("exitCode", answer.getExitCode());
        object.add("status", redactJson(parseElement(answer.getStatusJson())));
        return object;
    }

    private JsonObject redactedCapabilities(Answer answer) {
        JsonObject object = new JsonObject();
        if (!(answer instanceof FtctlDrCapabilitiesAnswer)) {
            object.addProperty("result", answer != null && answer.getResult());
            object.addProperty("details", answer != null ? answer.getDetails() : null);
            object.addProperty("answerType", answer != null ? answer.getClass().getName() : null);
            return object;
        }
        FtctlDrCapabilitiesAnswer capabilities = (FtctlDrCapabilitiesAnswer) answer;
        object.addProperty("result", capabilities.getResult());
        object.addProperty("details", capabilities.getDetails());
        object.addProperty("planUuid", capabilities.getPlanUuid());
        object.addProperty("runUuid", capabilities.getRunUuid());
        object.addProperty("ftctlVersion", capabilities.getFtctlVersion());
        object.addProperty("runtimeSchemaVersion", capabilities.getRuntimeSchemaVersion());
        object.addProperty("actionContractVersion", capabilities.getActionContractVersion());
        object.addProperty("actionCommandCodeSource", capabilities.getActionCommandCodeSource());
        object.addProperty("wrapperCodeSource", capabilities.getWrapperCodeSource());
        object.add("supportedActions", GSON.toJsonTree(capabilities.getSupportedActions()));
        object.add("supportedCliCommands", GSON.toJsonTree(capabilities.getSupportedCliCommands()));
        object.add("supportedFeatures", GSON.toJsonTree(capabilities.getSupportedFeatures()));
        object.add("missingActions", GSON.toJsonTree(capabilities.getMissingActions()));
        object.add("missingCliCommands", GSON.toJsonTree(capabilities.getMissingCliCommands()));
        return object;
    }

    private JsonObject redactedStatus(FtctlDrStatusAnswer status) {
        JsonObject object = new JsonObject();
        object.addProperty("planUuid", status.getPlanUuid());
        object.addProperty("runUuid", status.getRunUuid());
        object.addProperty("result", status.getFtctlResult());
        object.addProperty("state", status.getState());
        object.addProperty("step", status.getStep());
        object.addProperty("progress", status.getProgress());
        object.addProperty("eventsOffset", status.getEventsOffset());
        object.addProperty("errorCode", status.getErrorCode());
        object.addProperty("exitCode", status.getExitCode());
        object.add("status", redactJson(parseElement(status.getStatusJson())));
        return object;
    }

    private JsonObject requestJson(DrRunVO run) {
        if (run == null || StringUtils.isBlank(run.getRequestJson())) {
            return new JsonObject();
        }
        JsonElement parsed = parseElement(run.getRequestJson());
        return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
    }

    private JsonObject parseObject(String json) {
        JsonElement element = parseElement(json);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    private JsonObject objectAt(JsonObject object, String key) {
        JsonElement value = object != null ? object.get(key) : null;
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }

    private JsonArray firstArray(JsonObject object, String... keys) {
        for (String key : keys) {
            JsonElement value = object != null ? object.get(key) : null;
            if (value != null && value.isJsonArray()) {
                return value.getAsJsonArray();
            }
        }
        return new JsonArray();
    }

    private String firstString(JsonObject object, String... keys) {
        for (String key : keys) {
            JsonElement value = object != null ? object.get(key) : null;
            if (value != null && value.isJsonPrimitive()) {
                String result = StringUtils.trimToNull(value.getAsString());
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    private String firstNonBlank(String first, String second) {
        return StringUtils.isNotBlank(first) ? first : second;
    }

    private String stringValue(JsonObject object, String key) {
        JsonElement value = object != null ? object.get(key) : null;
        return value != null && !value.isJsonNull() && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    private boolean booleanValue(JsonObject object, String key) {
        JsonElement value = object != null ? object.get(key) : null;
        return value != null && !value.isJsonNull() && value.isJsonPrimitive() && value.getAsBoolean();
    }

    private boolean isExplicitlyFalse(JsonObject object, String key) {
        return object != null && object.has(key) && !object.get(key).isJsonNull()
                && !booleanValue(object, key);
    }

    private Integer integerValue(JsonObject object, String key) {
        JsonElement value = object != null ? object.get(key) : null;
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            return null;
        }
        try {
            return value.getAsInt();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private JsonObject retryableLockPayload(FtctlDrActionAnswer answer) {
        JsonObject payload = firstNonEmptyObject(answer.getStatusJson(), answer.getOutput(), answer.getDetails());
        if (payload.entrySet().isEmpty()) {
            return null;
        }
        String result = StringUtils.lowerCase(stringValue(payload, "result"), Locale.ROOT);
        String command = StringUtils.lowerCase(stringValue(payload, "command"), Locale.ROOT);
        boolean retryable = booleanValue(payload, "retryable");
        boolean locked = StringUtils.equals(result, "locked") || StringUtils.contains(command, "lock")
                || StringUtils.equalsIgnoreCase(stringValue(payload, "error_code"), "locked");
        return retryable && locked ? payload : null;
    }

    private JsonObject firstNonEmptyObject(String... values) {
        for (String value : values) {
            JsonObject object = parseObject(value);
            if (object != null && !object.entrySet().isEmpty()) {
                return object;
            }
        }
        return new JsonObject();
    }

    private JsonElement parseElement(String json) {
        if (StringUtils.isBlank(json)) {
            return null;
        }
        try {
            return JsonParser.parseString(json);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private JsonElement redactJson(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return new JsonObject();
        }
        if (element.isJsonObject()) {
            JsonObject redacted = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                if (isSecretKey(entry.getKey())) {
                    redacted.addProperty(entry.getKey(), "REDACTED");
                } else {
                    redacted.add(entry.getKey(), redactJson(entry.getValue()));
                }
            }
            return redacted;
        }
        if (element.isJsonArray()) {
            JsonArray redacted = new JsonArray();
            for (JsonElement item : element.getAsJsonArray()) {
                redacted.add(redactJson(item));
            }
            return redacted;
        }
        return element.deepCopy();
    }

    private boolean isSecretKey(String key) {
        String lower = StringUtils.lowerCase(key, Locale.ROOT);
        return StringUtils.contains(lower, "password")
                || StringUtils.contains(lower, "secret")
                || StringUtils.contains(lower, "token")
                || StringUtils.contains(lower, "apikey")
                || StringUtils.contains(lower, "api_key")
                || StringUtils.contains(lower, "credential");
    }

    private Map<String, String> toStringMap(JsonObject object) {
        Map<String, String> values = new java.util.HashMap<String, String>();
        if (object == null) {
            return values;
        }
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            values.put(entry.getKey(), entry.getValue().isJsonPrimitive() ? entry.getValue().getAsString() : entry.getValue().toString());
        }
        return values;
    }

    private String requestString(JsonObject request, String key) {
        JsonElement value = request.get(key);
        return value != null && !value.isJsonNull() ? value.getAsString() : null;
    }

    private Long requestLong(JsonObject request, String key) {
        JsonElement value = request.get(key);
        return value != null && !value.isJsonNull() ? value.getAsLong() : null;
    }

    private boolean requestBoolean(JsonObject request, String key, boolean defaultValue) {
        JsonElement value = request.get(key);
        return value != null && !value.isJsonNull() ? value.getAsBoolean() : defaultValue;
    }
}
