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
import java.util.List;

import javax.inject.Inject;

import org.apache.commons.lang3.StringUtils;

import com.cloud.dr.dao.DrCutoverSessionDao;
import com.cloud.dr.dao.DrPlanRuntimeDao;
import com.cloud.dr.dao.DrReplicaDao;
import com.cloud.dr.dao.DrRestorePointDao;
import com.cloud.dr.dao.DrRunStepDao;
import com.cloud.dr.dao.DrSyncCycleDao;
import com.cloud.utils.component.ManagerBase;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.dao.UserVmDao;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public class DrReprotectPreflightServiceImpl extends ManagerBase implements DrReprotectPreflightService {
    private static final int STEP_ORDER_REPROTECT_PREFLIGHT = 15;
    private static final Gson GSON = new Gson();

    @Inject private DrCutoverSessionDao drCutoverSessionDao;
    @Inject private DrPlanRuntimeDao drPlanRuntimeDao;
    @Inject private DrReplicaDao drReplicaDao;
    @Inject private DrRestorePointDao drRestorePointDao;
    @Inject private DrRunStepDao drRunStepDao;
    @Inject private DrSyncCycleDao drSyncCycleDao;
    @Inject private UserVmDao userVmDao;
    @Inject private DrSourceIsolationPreflightService drSourceIsolationPreflightService;
    @Inject private DrCurrentAuthorityResolver drCurrentAuthorityResolver;

    @Override
    public DrReprotectPreflightResult validate(DrPlanVO plan, DrRunVO run) {
        DrReprotectPreflightResult result = validateAuthorityAndRuntime(plan, run);
        recordStep(run, result);
        return result;
    }

    private DrReprotectPreflightResult validateAuthorityAndRuntime(DrPlanVO plan, DrRunVO run) {
        if (plan == null || run == null) {
            return failure(DrConstants.ERROR_REPROTECT_AUTHORITY_INVALID,
                    "DR plan and Reprotect run are required");
        }
        DrCurrentAuthorityProjection currentAuthority = drCurrentAuthorityResolver != null
                ? drCurrentAuthorityResolver.resolve(plan) : null;
        if (currentAuthority == null || !currentAuthority.isConsistent()
                || !StringUtils.equalsIgnoreCase(currentAuthority.getAuthoritySide(), "TARGET")
                || !StringUtils.equalsAnyIgnoreCase(currentAuthority.getAuthorityPhase(),
                        "FAILED_OVER_UNPROTECTED", "TARGET_PROMOTED_ENGINE_PENDING")) {
            return failure(DrConstants.ERROR_REPROTECT_REQUIRES_TARGET_ACTIVE,
                    "Reprotect requires committed TARGET authority without active reverse protection");
        }

        DrCutoverSessionVO cutover = drCutoverSessionDao.findLatestActiveByPlanId(plan.getId());
        if (cutover == null
                || !StringUtils.equalsAnyIgnoreCase(cutover.getState(), "PROMOTED", "COMPLETED", "FAILED_OVER")
                || !StringUtils.equalsIgnoreCase(cutover.getCloudPromotionState(), "PROMOTED")
                || !StringUtils.equalsIgnoreCase(cutover.getEngineAckState(), "ACKNOWLEDGED")
                || cutover.getCloudAuthorityGeneration() == null || cutover.getCloudAuthorityGeneration() <= 0) {
            return failure(DrConstants.ERROR_REPROTECT_CUTOVER_NOT_COMMITTED,
                    "Committed and acknowledged target promotion was not found");
        }

        DrReplicaVO replica = servingTargetReplica(plan.getId());
        if (replica == null || replica.getTargetVmId() == null
                || !StringUtils.equalsIgnoreCase(replica.getActiveSide(), "TARGET")) {
            return failure(DrConstants.ERROR_REPROTECT_TARGET_IDENTITY_INVALID,
                    "Serving target replica identity is missing or does not own TARGET authority");
        }
        UserVmVO targetVm = userVmDao.findById(replica.getTargetVmId());
        if (targetVm == null || targetVm.getRemoved() != null) {
            return failure(DrConstants.ERROR_REPROTECT_TARGET_IDENTITY_INVALID,
                    "Serving target VM identity is missing");
        }

        DrRestorePointVO checkpoint = drRestorePointDao.findLatestTargetReadyByPlanId(plan.getId());
        if (!hasDurableCutoverCheckpoint(plan, cutover, checkpoint)) {
            return failure(DrConstants.ERROR_REPROTECT_CHECKPOINT_MISMATCH,
                    "Committed cutover checkpoint is not durable in its canonical sequence domain");
        }

        DrSourceIsolationPreflightResult transitionPreflight =
                drSourceIsolationPreflightService.validate(plan, run, DrConstants.RUN_TYPE_REPROTECT);
        if (!transitionPreflight.isReady()) {
            return failure(transitionPreflight.getErrorCode(), transitionPreflight.getMessage());
        }

        DrReprotectAuthoritySpec spec = new DrReprotectAuthoritySpec();
        spec.setPlanUuid(plan.getUuid());
        spec.setRunUuid(run.getUuid());
        spec.setExpectedActiveSide("TARGET");
        spec.setAuthorityGeneration(cutover.getCloudAuthorityGeneration());
        spec.setAuthoritySequenceFloor(resolveAuthoritySequenceFloor(plan,
                cutover.getCloudAuthorityGeneration()));
        spec.setCutoverSessionId(cutover.getUuid());
        spec.setCheckpointSequence(cutover.getCheckpointSequence());
        spec.setTargetVmId(targetVm.getId());
        spec.setTargetExternalRef(StringUtils.defaultIfBlank(replica.getTargetExternalRef(), targetVm.getUuid()));
        spec.setTargetInstanceName(targetVm.getInstanceName());
        spec.setTargetPowerState(transitionPreflight.getTargetPowerState());
        spec.setTargetMaterialized(true);
        spec.setTargetPromotionState(cutover.getCloudPromotionState());
        spec.setBootValidationState(cutover.getBootValidationState());
        spec.setSourceFenceState(cutover.getSourceFenceState());
        spec.setSourcePowerState(cutover.getSourcePowerState());
        return DrReprotectPreflightResult.success(spec);
    }

    private Long canonicalCutoverSequence(DrCutoverSessionVO cutover) {
        if (cutover == null) {
            return null;
        }
        if (StringUtils.isNotBlank(cutover.getDetailsJson())) {
            try {
                JsonObject details = GSON.fromJson(cutover.getDetailsJson(), JsonObject.class);
                JsonElement planCycle = details != null ? details.get("plan_cycle_sequence") : null;
                if (planCycle != null && !planCycle.isJsonNull()) {
                    long sequence = planCycle.getAsLong();
                    if (sequence > 0L) {
                        return sequence;
                    }
                }
            } catch (RuntimeException ignored) {
                // Older cutover rows fall back to the engine checkpoint sequence.
            }
        }
        return cutover.getCheckpointSequence();
    }

    private boolean hasDurableCutoverCheckpoint(DrPlanVO plan, DrCutoverSessionVO cutover,
            DrRestorePointVO checkpoint) {
        if (cutover == null || cutover.getCheckpointSequence() == null
                || cutover.getCheckpointSequence() <= 0L) {
            return false;
        }
        if (!StringUtils.equalsIgnoreCase(plan.getDirection(), DrConstants.DIRECTION_KVM_TO_KVM)) {
            return checkpoint != null && checkpoint.getCheckpointSequence() != null
                    && checkpoint.getCheckpointSequence().equals(cutover.getCheckpointSequence());
        }

        Long planCycleSequence = canonicalCutoverSequence(cutover);
        String cloudCycleToken = planCycleSequence != null && planCycleSequence > 0L
                ? plan.getUuid() + ":" + planCycleSequence : null;
        DrSyncCycleVO cutoverCycle = StringUtils.isNotBlank(cloudCycleToken)
                ? drSyncCycleDao.findByPlanCycleToken(plan.getId(), cloudCycleToken) : null;
        if (isDurableCycle(cutoverCycle, cloudCycleToken)) {
            return true;
        }
        cutoverCycle = planCycleSequence != null && planCycleSequence > 0L
                ? drSyncCycleDao.findByPlanSequence(plan.getId(), planCycleSequence) : null;
        if (isDurableCycle(cutoverCycle, cloudCycleToken)) {
            return true;
        }
        String engineCycleToken = plan.getUuid() + ":" + cutover.getCheckpointSequence();
        cutoverCycle = drSyncCycleDao.findByPlanCycleToken(plan.getId(), engineCycleToken);
        return isDurableCycle(cutoverCycle, engineCycleToken);
    }

    private boolean isDurableCycle(DrSyncCycleVO cycle, String expectedCycleToken) {
        return cycle != null && StringUtils.isNotBlank(expectedCycleToken)
                && StringUtils.equalsIgnoreCase(cycle.getState(), "READY")
                && StringUtils.equalsIgnoreCase(cycle.getCommitState(), "LOCAL_DURABLE")
                && cycle.getTargetDurableAt() != null
                && StringUtils.equals(cycle.getCycleToken(), expectedCycleToken);
    }

    private long resolveAuthoritySequenceFloor(DrPlanVO plan, long authorityGeneration) {
        long floor = authorityGeneration;
        DrPlanRuntimeVO runtime = drPlanRuntimeDao.findByPlanId(plan.getId());
        if (runtime != null) {
            floor = Math.max(floor, runtime.getAuthoritySequence());
        }
        DrSyncCycleVO latestCompleted = drSyncCycleDao.findLatestCompletedByPlanId(plan.getId());
        if (latestCompleted != null && latestCompleted.getAuthoritySequence() != null) {
            floor = Math.max(floor, latestCompleted.getAuthoritySequence());
        }
        return floor;
    }

    private DrReplicaVO servingTargetReplica(long planId) {
        List<DrReplicaVO> replicas = drReplicaDao.listActiveByPlanId(planId);
        if (replicas == null) {
            return null;
        }
        for (DrReplicaVO replica : replicas) {
            if (replica != null && replica.getTargetVmId() != null
                    && StringUtils.equalsIgnoreCase(replica.getActiveSide(), "TARGET")) {
                return replica;
            }
        }
        return null;
    }

    private DrReprotectPreflightResult failure(String errorCode, String message) {
        return DrReprotectPreflightResult.failure(errorCode, message);
    }

    private void recordStep(DrRunVO run, DrReprotectPreflightResult result) {
        if (run == null || drRunStepDao == null) {
            return;
        }
        Date now = new Date();
        DrRunStepVO step = drRunStepDao.findActiveByRunIdAndStepOrder(run.getId(),
                STEP_ORDER_REPROTECT_PREFLIGHT);
        if (step == null) {
            step = new DrRunStepVO(run.getId(), "reprotect-preflight",
                    STEP_ORDER_REPROTECT_PREFLIGHT);
            step.setStarted(now);
        }
        step.setState(result.isReady() ? DrConstants.STEP_STATE_SUCCEEDED : DrConstants.STEP_STATE_FAILED);
        step.setProgress(100);
        step.setCompleted(now);
        step.setDetailsJson(result.getAuthoritySpec() != null
                ? GSON.toJson(result.getAuthoritySpec()) : null);
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
