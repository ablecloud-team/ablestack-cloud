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

package com.cloud.dr;

import java.util.Date;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.inject.Inject;

import org.apache.commons.lang3.StringUtils;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.FtctlDrStatusAnswer;
import com.cloud.agent.api.FtctlDrStatusCommand;
import com.cloud.dr.dao.DrCutoverSessionDao;
import com.cloud.dr.dao.DrReplicaDao;
import com.cloud.dr.dao.DrRunStepDao;
import com.cloud.utils.component.ManagerBase;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.dao.UserVmDao;
import com.google.gson.Gson;

public class DrSourceIsolationPreflightServiceImpl extends ManagerBase
        implements DrSourceIsolationPreflightService {
    private static final int STEP_ORDER = 12;
    private static final Gson GSON = new Gson();

    @Inject private DrCutoverSessionDao drCutoverSessionDao;
    @Inject private DrReplicaDao drReplicaDao;
    @Inject private DrRunStepDao drRunStepDao;
    @Inject private UserVmDao userVmDao;
    @Inject private AgentManager agentManager;
    @Inject private DrCurrentAuthorityResolver drCurrentAuthorityResolver;
    @Inject private DrWorkerPlacementService drWorkerPlacementService;

    @Override
    public DrSourceIsolationPreflightResult validate(DrPlanVO plan, DrRunVO run, String operation) {
        DrSourceIsolationPreflightResult result = validateInternal(plan, operation);
        recordStep(run, result);
        return result;
    }

    private DrSourceIsolationPreflightResult validateInternal(DrPlanVO plan, String operation) {
        List<DrFailbackPreflightStage> stages = new ArrayList<DrFailbackPreflightStage>();
        String normalizedOperation = StringUtils.upperCase(operation, Locale.ROOT);
        if (plan == null || !StringUtils.equalsAny(normalizedOperation,
                DrConstants.RUN_TYPE_FAILBACK, DrConstants.RUN_TYPE_REPROTECT)) {
            return blocked(stages, "AUTHORITY", DrConstants.ERROR_TRANSITION_PREFLIGHT_INVALID,
                    "A valid FTCTL_DR failback or reprotect preflight is required",
                    normalizedOperation, null, null, null, null, null, null, null, null, null);
        }
        DrCurrentAuthorityProjection authority = drCurrentAuthorityResolver.resolve(plan);
        if (authority == null || !authority.isConsistent()
                || !StringUtils.equalsIgnoreCase(authority.getAuthoritySide(),
                        DrConstants.AUTHORITY_SIDE_TARGET)) {
            return blocked(stages, "AUTHORITY", DrConstants.ERROR_TRANSITION_AUTHORITY_INVALID,
                    "Transition requires committed TARGET authority", normalizedOperation,
                    null, null, null, null, null, null, null, null, null);
        }

        DrCutoverSessionVO cutover = drCutoverSessionDao.findLatestActiveByPlanId(plan.getId());
        if (cutover == null || cutover.getCloudAuthorityGeneration() == null
                || cutover.getCloudAuthorityGeneration() <= 0
                || !StringUtils.equalsIgnoreCase(cutover.getCloudPromotionState(), "PROMOTED")
                || !StringUtils.equalsIgnoreCase(cutover.getEngineAckState(), "ACKNOWLEDGED")) {
            return blocked(stages, "AUTHORITY", DrConstants.ERROR_TRANSITION_AUTHORITY_INVALID,
                    "Committed cutover authority evidence is incomplete", normalizedOperation,
                    cutover != null ? cutover.getCloudAuthorityGeneration() : null,
                    cutover != null ? cutover.getSourceFenceState() : null,
                    cutover != null ? cutover.getSourcePowerState() : null, null, null,
                    null, null, null, null);
        }
        stages.add(DrFailbackPreflightStage.ready("AUTHORITY", "CLOUD_DB",
                "Committed TARGET authority is consistent"));

        String sourceFenceState = cutover.getSourceFenceState();
        String sourcePowerState = cutover.getSourcePowerState();
        boolean sourcePowerOff = StringUtils.equalsIgnoreCase(sourcePowerState, "POWERED_OFF");
        boolean sourceFenced = StringUtils.equalsAnyIgnoreCase(sourceFenceState,
                "ACKNOWLEDGED", "CONFIRMED", "FENCED", "ISOLATED", "BLOCKED");
        if (!sourcePowerOff && !sourceFenced) {
            return blocked(stages, "SOURCE_RUNTIME", DrConstants.ERROR_SOURCE_ISOLATION_NOT_READY,
                    "Source site is not proven powered off or isolated", normalizedOperation,
                    cutover.getCloudAuthorityGeneration(), sourceFenceState, sourcePowerState,
                    null, null, null, null, null, null);
        }
        stages.add(DrFailbackPreflightStage.ready("SOURCE_RUNTIME", "CUTOVER_OR_FENCE",
                sourcePowerOff ? "Source VM is powered off" : "Source isolation is acknowledged"));

        DrReplicaVO replica = servingTargetReplica(plan.getId());
        UserVmVO targetVm = replica != null && replica.getTargetVmId() != null
                ? userVmDao.findById(replica.getTargetVmId()) : null;
        if (targetVm == null || targetVm.getRemoved() != null) {
            return blocked(stages, "TARGET_RUNTIME", DrConstants.ERROR_TRANSITION_TARGET_NOT_SERVING,
                    "Serving target VM identity is missing", normalizedOperation,
                    cutover.getCloudAuthorityGeneration(), sourceFenceState, sourcePowerState,
                    null, null, targetVm != null ? targetVm.getState().toString() : null,
                    "NOT_FOUND", targetVm != null ? targetVm.getId() : null,
                    targetVm != null ? targetVm.getHostId() : null);
        }
        String targetDbState = targetVm.getState() != null ? targetVm.getState().toString() : null;
        String targetRuntimeState = "NOT_REQUIRED";
        stages.add(DrFailbackPreflightStage.ready("TARGET_RUNTIME", "CHECKPOINT_AUTHORITY",
                "Serving target replica identity is available; VM power state is not an action prerequisite"));

        Long coordinatorHostId = drWorkerPlacementService != null
                ? drWorkerPlacementService.resolveWorkerHostId(plan, DrWorkerRole.TARGET) : null;
        if (coordinatorHostId == null) {
            return blocked(stages, "FTCTL_TRANSITION", DrConstants.ERROR_TRANSITION_ENGINE_PREFLIGHT_FAILED,
                    "FTCTL_DR coordinator host is not configured", normalizedOperation,
                    cutover.getCloudAuthorityGeneration(), sourceFenceState, sourcePowerState,
                    targetRuntimeState, null, targetDbState, targetRuntimeState,
                    targetVm.getId(), targetVm.getHostId());
        }
        FtctlDrStatusCommand command = new FtctlDrStatusCommand(plan.getUuid(), null,
                FtctlDrStatusCommand.StatusScope.TRANSITION_PREFLIGHT);
        command.setTransitionOperation(normalizedOperation.toLowerCase(Locale.ROOT));
        command.setExpectedAuthoritySide(DrConstants.AUTHORITY_SIDE_TARGET);
        command.setExpectedAuthorityGeneration(cutover.getCloudAuthorityGeneration());
        Answer engineProbe = agentManager.easySend(coordinatorHostId, command);
        FtctlDrStatusAnswer engineAnswer = engineProbe instanceof FtctlDrStatusAnswer
                ? (FtctlDrStatusAnswer) engineProbe : null;
        String evidenceJson = engineAnswer != null ? engineAnswer.getStatusJson() : null;
        if (engineAnswer == null
                || !FtctlDrStatusCommand.TRANSITION_PREFLIGHT_CONTRACT_VERSION.equals(
                        engineAnswer.getTransitionContractVersion())) {
            return blocked(stages, "FTCTL_TRANSITION", DrConstants.ERROR_AGENT_TRANSITION_PREFLIGHT_CONTRACT_MISMATCH,
                    engineProbe != null ? engineProbe.getDetails()
                            : "FTCTL_DR transition preflight returned no typed answer",
                    normalizedOperation, cutover.getCloudAuthorityGeneration(), sourceFenceState,
                    sourcePowerState, targetRuntimeState, evidenceJson, targetDbState,
                    targetRuntimeState, targetVm.getId(), targetVm.getHostId());
        }
        if (!engineProbe.getResult() || !Boolean.TRUE.equals(engineAnswer.getTransitionReady())
                || !StringUtils.equalsIgnoreCase(engineAnswer.getTransitionActiveSide(),
                        DrConstants.AUTHORITY_SIDE_TARGET)
                || !cutover.getCloudAuthorityGeneration().equals(
                        engineAnswer.getTransitionAuthorityGeneration())) {
            return blocked(stages, "FTCTL_TRANSITION", DrConstants.ERROR_TRANSITION_ENGINE_PREFLIGHT_FAILED,
                    engineProbe != null ? engineProbe.getDetails()
                            : "FTCTL_DR transition preflight returned no answer",
                    normalizedOperation, cutover.getCloudAuthorityGeneration(), sourceFenceState,
                    sourcePowerState, targetRuntimeState, evidenceJson, targetDbState,
                    targetRuntimeState, targetVm.getId(), targetVm.getHostId());
        }
        stages.add(DrFailbackPreflightStage.ready("FTCTL_TRANSITION", "FTCTL",
                "FTCTL transition contract is ready"));
        return DrSourceIsolationPreflightResult.of(true, null, null, normalizedOperation,
                cutover.getCloudAuthorityGeneration(), sourceFenceState, sourcePowerState,
                targetRuntimeState, evidenceJson, stages, null,
                DrFailbackPreflightStage.STATE_READY, "CONSISTENT", targetDbState,
                targetRuntimeState, targetVm.getId(), targetVm.getHostId());
    }

    private DrSourceIsolationPreflightResult blocked(List<DrFailbackPreflightStage> stages,
            String failureStage, String errorCode, String message, String operation,
            Long generation, String fenceState, String sourcePowerState, String targetPowerState,
            String engineEvidenceJson, String targetDbState, String targetAgentState,
            Long targetVmId, Long targetHostId) {
        return blocked(stages, failureStage, errorCode, message, operation, generation,
                fenceState, sourcePowerState, targetPowerState, engineEvidenceJson,
                targetDbState, targetAgentState, targetVmId, targetHostId, null);
    }

    private DrSourceIsolationPreflightResult blocked(List<DrFailbackPreflightStage> stages,
            String failureStage, String errorCode, String message, String operation,
            Long generation, String fenceState, String sourcePowerState, String targetPowerState,
            String engineEvidenceJson, String targetDbState, String targetAgentState,
            Long targetVmId, Long targetHostId, String runtimeDriftState) {
        stages.add(DrFailbackPreflightStage.blocked(failureStage, errorCode, message,
                StringUtils.equals(failureStage, "TARGET_RUNTIME") ? "MOLD_AGENT" : "CLOUD"));
        appendNotRunStages(stages, failureStage);
        return DrSourceIsolationPreflightResult.of(false, errorCode, message, operation,
                generation, fenceState, sourcePowerState, targetPowerState, engineEvidenceJson,
                stages, failureStage,
                StringUtils.equals(failureStage, "FTCTL_TRANSITION")
                        ? DrFailbackPreflightStage.STATE_BLOCKED : DrFailbackPreflightStage.STATE_NOT_RUN,
                runtimeDriftState, targetDbState, targetAgentState, targetVmId, targetHostId);
    }

    private void appendNotRunStages(List<DrFailbackPreflightStage> stages, String failureStage) {
        String[] order = { "AUTHORITY", "SOURCE_RUNTIME", "TARGET_RUNTIME", "FTCTL_TRANSITION" };
        boolean append = false;
        for (String code : order) {
            if (append) {
                stages.add(DrFailbackPreflightStage.notRun(code, "Skipped because an earlier safety stage is blocked"));
            }
            if (StringUtils.equals(code, failureStage)) {
                append = true;
            }
        }
    }

    private DrReplicaVO servingTargetReplica(long planId) {
        List<DrReplicaVO> replicas = drReplicaDao.listActiveByPlanId(planId);
        if (replicas == null) {
            return null;
        }
        for (DrReplicaVO replica : replicas) {
            if (replica != null && replica.getTargetVmId() != null
                    && StringUtils.equalsIgnoreCase(replica.getActiveSide(),
                            DrConstants.AUTHORITY_SIDE_TARGET)) {
                return replica;
            }
        }
        return null;
    }

    private Long firstNonNull(Long... values) {
        for (Long value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private DrSourceIsolationPreflightResult failure(String errorCode, String message,
            String operation, Long generation, String fenceState, String sourcePowerState,
            String targetPowerState, String engineEvidenceJson) {
        return DrSourceIsolationPreflightResult.failure(errorCode, message, operation, generation,
                fenceState, sourcePowerState, targetPowerState, engineEvidenceJson);
    }

    private void recordStep(DrRunVO run, DrSourceIsolationPreflightResult result) {
        if (run == null || drRunStepDao == null) {
            return;
        }
        Date now = new Date();
        DrRunStepVO step = drRunStepDao.findActiveByRunIdAndStepOrder(run.getId(), STEP_ORDER);
        if (step == null) {
            step = new DrRunStepVO(run.getId(), "source-isolation-preflight", STEP_ORDER);
            step.setStarted(now);
        }
        step.setState(result.isReady() ? DrConstants.STEP_STATE_SUCCEEDED
                : DrConstants.STEP_STATE_FAILED);
        step.setProgress(100);
        step.setCompleted(now);
        step.setDetailsJson(GSON.toJson(result));
        step.setErrorCode(result.getErrorCode());
        step.setErrorMessage(result.getMessage());
        step.markUpdated();
        if (step.getId() == 0) {
            drRunStepDao.persist(step);
        } else {
            drRunStepDao.update(step.getId(), step);
        }
    }
}
