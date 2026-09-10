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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.inject.Inject;

import org.apache.commons.lang3.StringUtils;

import com.cloud.dr.adapter.DrAdapterRegistry;
import com.cloud.dr.dao.DrPlanDao;
import com.cloud.dr.dao.DrCutoverSessionDao;
import com.cloud.dr.dao.DrPlanViewCacheDao;
import com.cloud.dr.dao.DrPlanRuntimeDao;
import com.cloud.dr.dao.DrReplicaDao;
import com.cloud.dr.dao.DrRestorePointDao;
import com.cloud.dr.dao.DrRunDao;
import com.cloud.dr.dao.DrSiteDao;
import com.cloud.dr.dao.DrSyncCycleDao;
import com.cloud.dr.dao.DrTestSessionDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.utils.Pair;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class DrPlanServiceImpl extends ManagerBase implements DrPlanService {
    @Inject private DrTestCleanupRecoveryStore testCleanupRecovery;

    private static final DrPlanActionAvailabilityEvaluator ACTION_AVAILABILITY_EVALUATOR =
            new DrPlanActionAvailabilityEvaluator();
    @Inject
    private DrPlanDao drPlanDao;
    @Inject
    private DrPlanViewCacheDao drPlanViewCacheDao;
    @Inject
    private DrPlanRuntimeDao drPlanRuntimeDao;
    @Inject
    private DrSyncCycleDao drSyncCycleDao;
    @Inject
    private DrRunDao drRunDao;
    @Inject
    private DrReplicaDao drReplicaDao;
    @Inject
    private DrRestorePointDao drRestorePointDao;
    @Inject
    private DrSiteDao drSiteDao;
    @Inject
    private DrAdapterRegistry drAdapterRegistry;
    @Inject
    private DrPlanReadinessValidator drPlanReadinessValidator;
    @Inject
    private DrProtectionAuthorityService drProtectionAuthorityService;
    @Inject
    private DrTestSessionDao drTestSessionDao;
    @Inject
    private DrCutoverSessionDao drCutoverSessionDao;
    @Inject
    private DrFtctlActionCapabilityService drFtctlActionCapabilityService;
    @Inject
    private DrCurrentAuthorityResolver drCurrentAuthorityResolver;

    @Override
    public DrPlanVO createPlan(DrPlanVO plan) {
        normalizePlanEngine(plan);
        plan.setSourceWorkerHostId(null);
        plan.setTargetWorkerHostId(null);
        plan.setCoordinatorWorkerHostId(null);
        validatePlan(plan);
        DrSiteVO[] sites = ensureSitesExist(plan);
        validatePlanTopology(plan, sites[0], sites[1]);
        ensureNoDuplicatePlan(plan);

        if (StringUtils.isBlank(plan.getAdminState())) {
            plan.setAdminState(DrConstants.ADMIN_STATE_ENABLED);
        }
        if (StringUtils.isBlank(plan.getState())) {
            plan.setState(DrConstants.PLAN_STATE_NEW);
        }
        if (StringUtils.isBlank(plan.getActiveSide())) {
            plan.setActiveSide("SOURCE");
        }
        return drPlanDao.persist(plan);
    }

    @Override
    public DrPlanVO updatePlan(long planId, DrPlanVO update) {
        DrPlanVO plan = requirePlan(planId);
        if (update == null) {
            return plan;
        }
        if (drRunDao.findActiveByPlanId(planId) != null) {
            throw new InvalidParameterValueException(DrConstants.ERROR_ACTIVE_RUN_EXISTS + ": active run exists for plan " + planId);
        }
        normalizePlanEngine(update);
        if (hasRuntimeResources(planId, plan) && hasDisruptiveUpdate(plan, update)) {
            throw new InvalidParameterValueException(DrConstants.ERROR_RUNTIME_RESOURCE_EXISTS
                    + ": release DR runtime resources before changing source, engine, worker, or mapping fields for plan " + planId);
        }
        if (StringUtils.isNotBlank(update.getName())) {
            plan.setName(update.getName());
        }
        if (update.getDescription() != null) {
            plan.setDescription(update.getDescription());
        }
        if (update.getSourceVmId() != null && !update.getSourceVmId().equals(plan.getSourceVmId())) {
            DrPlanVO existing = drPlanDao.findActiveBySourceVmId(update.getSourceVmId());
            if (existing != null && existing.getId() != planId) {
                throw new InvalidParameterValueException(DrConstants.ERROR_DUPLICATE_PLAN + ": source VM already has an active DR plan");
            }
            plan.setSourceVmId(update.getSourceVmId());
        }
        if (update.getSourceExternalRef() != null) {
            DrPlanVO existing = drPlanDao.findActiveBySourceSiteAndExternalRef(plan.getSourceSiteId(), update.getSourceExternalRef());
            if (StringUtils.isNotBlank(update.getSourceExternalRef()) && existing != null && existing.getId() != planId) {
                throw new InvalidParameterValueException(DrConstants.ERROR_DUPLICATE_PLAN + ": source workload already has an active DR plan");
            }
            plan.setSourceExternalRef(update.getSourceExternalRef());
        }
        if (StringUtils.isNotBlank(update.getEngineType())) {
            plan.setEngineType(update.getEngineType());
        }
        if (StringUtils.isNotBlank(update.getEngineBindingType())) {
            plan.setEngineBindingType(update.getEngineBindingType());
        }
        if (update.getEngineBindingId() != null && !update.getEngineBindingId().equals(plan.getEngineBindingId())) {
            DrPlanVO existing = drPlanDao.findActiveByEngineBinding(plan.getEngineBindingType(), update.getEngineBindingId());
            if (existing != null && existing.getId() != planId) {
                throw new InvalidParameterValueException(DrConstants.ERROR_DUPLICATE_PLAN + ": engine binding already has an active DR plan");
            }
            plan.setEngineBindingId(update.getEngineBindingId());
        }
        if (update.getRpoSeconds() != null) {
            plan.setRpoSeconds(update.getRpoSeconds());
        }
        if (update.getRtoSeconds() != null) {
            plan.setRtoSeconds(update.getRtoSeconds());
        }
        if (update.getScheduleJson() != null) {
            plan.setScheduleJson(update.getScheduleJson());
        }
        if (update.getPolicyJson() != null) {
            plan.setPolicyJson(update.getPolicyJson());
        }
        if (update.getMappingJson() != null) {
            plan.setMappingJson(update.getMappingJson());
        }
        if (update.getQuiescePolicyJson() != null) {
            plan.setQuiescePolicyJson(update.getQuiescePolicyJson());
        }
        normalizePlanEngine(plan);
        validatePlan(plan);
        DrSiteVO[] sites = ensureSitesExist(plan);
        validatePlanTopology(plan, sites[0], sites[1]);
        plan.markUpdated();
        drPlanDao.update(planId, plan);
        return drPlanDao.findById(planId);
    }

    @Override
    public DrPlanVO getPlan(long planId) {
        return requirePlan(planId);
    }

    @Override
    public List<DrPlanVO> listPlans() {
        return drPlanDao.listActive();
    }

    @Override
    public Pair<List<DrPlanVO>, Integer> searchPlans(DrPlanSearchCriteria criteria) {
        if (criteria == null) {
            return drPlanDao.searchActive(null, null, null, null, null, null, null, 0L, 20L);
        }
        return drPlanDao.searchActive(criteria.getId(), criteria.getKeyword(), criteria.getState(),
                criteria.getSourceSiteId(), criteria.getTargetSiteId(), criteria.getDirection(), criteria.getEngineType(),
                criteria.getStartIndex(), criteria.getPageSize());
    }

    @Override
    public DrPlanVO enablePlan(long planId) {
        DrPlanVO plan = requirePlan(planId);
        plan.setAdminState(DrConstants.ADMIN_STATE_ENABLED);
        plan.markUpdated();
        drPlanDao.update(planId, plan);
        return drPlanDao.findById(planId);
    }

    @Override
    public DrPlanVO disablePlan(long planId) {
        DrPlanVO plan = requirePlan(planId);
        plan.setAdminState(DrConstants.ADMIN_STATE_DISABLED);
        plan.markUpdated();
        drPlanDao.update(planId, plan);
        return drPlanDao.findById(planId);
    }

    @Override
    public boolean deletePlan(long planId) {
        DrPlanVO plan = requirePlan(planId);
        if (drRunDao.findActiveByPlanId(planId) != null) {
            throw new InvalidParameterValueException(DrConstants.ERROR_ACTIVE_RUN_EXISTS + ": active run exists for plan " + planId);
        }
        if (hasRuntimeResources(planId, plan) || isProtectedPlanState(plan)) {
            throw new InvalidParameterValueException(DrConstants.ERROR_RUNTIME_RESOURCE_EXISTS
                    + ": release DR protection and cleanup runtime resources before deleting plan " + planId);
        }
        DrPlanViewCacheVO cache = drPlanViewCacheDao != null ? drPlanViewCacheDao.findByPlanId(planId) : null;
        if (cache != null && !drPlanViewCacheDao.remove(cache.getId())) {
            throw new CloudRuntimeException("Failed to delete DR protection view cache for plan " + planId);
        }
        if (drSyncCycleDao != null) {
            drSyncCycleDao.removeByPlanId(planId);
        }
        if (drPlanRuntimeDao != null) {
            drPlanRuntimeDao.removeByPlanId(planId);
        }
        if (!drPlanDao.remove(planId)) {
            throw new CloudRuntimeException("Failed to delete DR plan " + planId);
        }
        DrPlanVO removedPlan = drPlanDao.findByIdIncludingRemoved(planId);
        if (removedPlan == null || removedPlan.getRemoved() == null) {
            throw new CloudRuntimeException("DR plan soft delete was not persisted " + planId);
        }
        return true;
    }

    @Override
    public Map<String, Boolean> getActionEligibility(long planId) {
        return getActionEvaluation(planId).getEligibility();
    }

    @Override
    public Map<String, DrActionAvailability> getActionAvailability(long planId) {
        return getActionEvaluation(planId).getAvailability();
    }

    @Override
    public DrPlanActionEvaluation getActionEvaluation(long planId) {
        return getActionEvaluation(planId, true);
    }

    @Override
    public DrPlanActionEvaluation getDatabaseActionEvaluation(long planId) {
        return getActionEvaluation(planId, false);
    }

    private DrPlanActionEvaluation getActionEvaluation(long planId, boolean probeCapabilities) {
        DrPlanVO plan = requirePlan(planId);
        boolean enabled = StringUtils.equals(DrConstants.ADMIN_STATE_ENABLED, plan.getAdminState());
        boolean activeRun = drRunDao.findActiveByPlanId(planId) != null;
        boolean runtimeResources = hasRuntimeResources(planId, plan);
        boolean hasEngine = drAdapterRegistry.getReplicationEngine(plan.getEngineType(), plan.getEngineBindingType()) != null;

        boolean vmwarePhase1 = isVmwarePhase1Plan(plan);
        boolean v2kPlan = isV2kPlan(plan);
        boolean legacyFtctlPlan = isLegacyFtctlPlan(plan);
        boolean ftctlDrPlan = isFtctlDrPlan(plan);
        DrPlanReadiness targetReadiness = drPlanReadinessValidator != null ? drPlanReadinessValidator.validateTargetReadiness(plan) : null;
        boolean targetReady = targetReadiness != null ? targetReadiness.isTargetMaterialized() : plan.getTargetReadyAt() != null;
        boolean failedOver = StringUtils.equals(DrConstants.PLAN_STATE_FAILED_OVER, plan.getState());
        boolean lifecycleTransition = StringUtils.equals(DrConstants.PLAN_STATE_COMMIT_VERIFYING, plan.getState());
        boolean targetActive = StringUtils.equalsIgnoreCase("TARGET", plan.getActiveSide());
        boolean sourceAuthority = !failedOver && !targetActive;
        boolean syncPausable = StringUtils.equalsAny(plan.getState(), DrConstants.PLAN_STATE_SYNCING, DrConstants.PLAN_STATE_READY);
        boolean syncPaused = StringUtils.equals(DrConstants.PLAN_STATE_PAUSED, plan.getState());
        DrTestSessionVO activeTestSession = drTestSessionDao != null ? drTestSessionDao.findActiveByPlanId(planId) : null;
        DrRunVO activeTestRun = activeTestSession != null ? drRunDao.findById(activeTestSession.getRunId()) : null;
        boolean staleTestSession = DrTestSessionState.isTerminalRunFailureWithoutArtifacts(activeTestSession, activeTestRun);
        boolean testRunning = !staleTestSession
                && (StringUtils.equals(DrConstants.PLAN_STATE_TESTING, plan.getState())
                        || DrTestSessionState.blocksNewTest(activeTestSession));
        DrPlanReadiness executionReadiness = drPlanReadinessValidator != null ? drPlanReadinessValidator.validateForExecution(plan) : null;
        DrPlanReadiness releaseReadiness = drPlanReadinessValidator != null ? drPlanReadinessValidator.validateForRelease(plan) : null;
        boolean ftctlDrExecutionReady = !ftctlDrPlan || executionReadiness == null || executionReadiness.isExecutionReady();
        boolean ftctlDrReleaseReady = releaseReadiness != null && releaseReadiness.isReleaseReady();
        boolean ftctlDrControlReady = !ftctlDrPlan || isFtctlDrControlReady(planId);
        DrPlanRuntimeVO planRuntime = ftctlDrPlan && drPlanRuntimeDao != null
                ? drPlanRuntimeDao.findByPlanId(planId) : null;
        boolean nbdRecoveryRequired = planRuntime != null
                && (StringUtils.equalsIgnoreCase(planRuntime.getNbdTeardownState(), "QUARANTINED")
                        || planRuntime.getNbdQuarantinedDeviceCount() > 0);
        boolean recoverSyncRequired = nbdRecoveryRequired
                || isRecoverSyncRequired(plan, planRuntime, sourceAuthority);
        boolean runtimeReconciliationRequired = planRuntime != null
                && (StringUtils.equalsAnyIgnoreCase(planRuntime.getReconciliationState(),
                        "RECONCILING", "DEAD_CONFIRMING")
                        || planRuntime.getOwnedProcessCount() > 0
                        && !StringUtils.equalsIgnoreCase(planRuntime.getReconciliationState(), "TERMINAL"));
        DrProtectionAuthoritySnapshot authority = ftctlDrPlan && drProtectionAuthorityService != null
                ? drProtectionAuthorityService.getAuthority(planId) : null;
        boolean normalCutoverReady = !ftctlDrPlan || (authority != null && authority.isNormalCutoverReady());
        boolean committedTargetAuthority = !ftctlDrPlan || hasCommittedTargetAuthority(plan);
        DrCurrentAuthorityProjection currentAuthority = ftctlDrPlan && drCurrentAuthorityResolver != null
                ? drCurrentAuthorityResolver.resolve(plan) : null;
        boolean targetProtected = currentAuthority != null
                && StringUtils.equalsIgnoreCase(currentAuthority.getAuthorityPhase(), "TARGET_PROTECTED");
        boolean targetNeedsReprotect = ftctlDrPlan && targetActive && committedTargetAuthority
                && !targetProtected;
        DrFtctlActionCapabilitySnapshot capabilitySnapshot = probeCapabilities && ftctlDrPlan
                && drFtctlActionCapabilityService != null
                ? drFtctlActionCapabilityService.evaluate(plan) : null;

        Map<String, Boolean> eligibility = new LinkedHashMap<String, Boolean>();
        eligibility.put("update", !activeRun);
        eligibility.put("delete", !activeRun && !runtimeResources && !isProtectedPlanState(plan));
        eligibility.put("sync", enabled && !activeRun && hasEngine && sourceAuthority && !v2kPlan
                && !recoverSyncRequired
                && (legacyFtctlPlan || vmwarePhase1 || (ftctlDrPlan && ftctlDrExecutionReady)));
        eligibility.put("recoverSync", enabled && !activeRun && hasEngine && ftctlDrPlan && recoverSyncRequired);
        eligibility.put("pauseSync", enabled && !activeRun && hasEngine && sourceAuthority
                && ftctlDrPlan && ftctlDrControlReady && syncPausable);
        eligibility.put("resumeSync", enabled && !activeRun && hasEngine && sourceAuthority
                && ftctlDrPlan && ftctlDrControlReady && syncPaused);
        eligibility.put("testFailover", (testCleanupRecovery == null || !testCleanupRecovery.pending(plan.getId())) && enabled && !activeRun && hasEngine && ftctlDrPlan && ftctlDrControlReady
                && sourceAuthority && targetReady);
        eligibility.put("stopTestFailover", enabled && !activeRun && hasEngine && ftctlDrPlan && ftctlDrControlReady && testRunning);
        eligibility.put("failover", enabled && !activeRun && hasEngine
                && sourceAuthority
                && (legacyFtctlPlan || (ftctlDrPlan && ftctlDrControlReady && targetReady && normalCutoverReady)));
        eligibility.put("disasterFailover", enabled && !activeRun && hasEngine
                && sourceAuthority && ftctlDrPlan && ftctlDrControlReady && targetReady);
        eligibility.put("confirmFenceClear", enabled && !activeRun && hasEngine && legacyFtctlPlan && failedOver);
        eligibility.put("failback", enabled && !activeRun && hasEngine
                && (legacyFtctlPlan || (ftctlDrPlan && ftctlDrControlReady && (failedOver || targetActive))));
        eligibility.put("reprotect", enabled && !activeRun && hasEngine
                && (legacyFtctlPlan || (ftctlDrPlan && ftctlDrControlReady && targetNeedsReprotect
                        && targetActive && committedTargetAuthority && !testRunning)));
        eligibility.put("adoptReplica", enabled && !activeRun && hasEngine && legacyFtctlPlan);
        eligibility.put("releaseProtection", enabled && !activeRun && hasEngine && ftctlDrPlan && ftctlDrControlReady && ftctlDrReleaseReady);
        eligibility.put("migrationOnly", vmwarePhase1 || v2kPlan);
        eligibility.put("cancelRun", activeRun);
        if (capabilitySnapshot != null) {
            for (String action : new String[] {"sync", "recoverSync", "pauseSync", "resumeSync",
                    "testFailover", "stopTestFailover", "failover", "failback", "reprotect",
                    "releaseProtection"}) {
                if (capabilitySnapshot.getBlockingReason(action) != null) {
                    eligibility.put(action, false);
                    if ("failover".equals(action)) {
                        eligibility.put("disasterFailover", false);
                    }
                }
            }
        }
        if (lifecycleTransition) {
            eligibility.replaceAll((action, allowed) -> false);
            eligibility.put("cancelRun", activeRun);
        }
        if (nbdRecoveryRequired) {
            eligibility.replaceAll((action, allowed) -> false);
            eligibility.put("recoverSync", enabled && !activeRun && hasEngine && ftctlDrPlan);
            eligibility.put("cancelRun", activeRun);
        }
        if (runtimeReconciliationRequired) {
            eligibility.replaceAll((action, allowed) -> false);
            eligibility.put("cancelRun", activeRun);
        }
        DrPlanActionAvailabilityContext context = new DrPlanActionAvailabilityContext();
        context.planEnabled = enabled;
        context.activeRun = activeRun;
        context.runtimeResources = runtimeResources;
        context.protectedPlanState = isProtectedPlanState(plan);
        context.hasEngine = hasEngine;
        context.sourceAuthority = sourceAuthority;
        context.targetAuthority = failedOver || targetActive;
        context.v2kPlan = v2kPlan;
        context.legacyFtctlPlan = legacyFtctlPlan;
        context.ftctlDrPlan = ftctlDrPlan;
        context.syncPausable = syncPausable;
        context.syncPaused = syncPaused;
        context.recoverSyncRequired = recoverSyncRequired;
        context.testRunning = testRunning;
        context.targetReady = targetReady;
        context.normalCutoverReady = normalCutoverReady;
        context.failedOver = failedOver || targetNeedsReprotect;
        context.committedTargetAuthority = committedTargetAuthority;
        context.ftctlControlReady = ftctlDrControlReady;
        context.ftctlReleaseReady = ftctlDrReleaseReady;
        context.lifecycleTransition = lifecycleTransition;
        context.nbdRecoveryRequired = nbdRecoveryRequired;
        context.runtimeReconciliationRequired = runtimeReconciliationRequired;
        if (capabilitySnapshot != null) {
            Map<String, String> capabilityReasons = new LinkedHashMap<String, String>();
            Map<String, Map<String, String>> capabilityArgs = new LinkedHashMap<String, Map<String, String>>();
            for (String action : eligibility.keySet()) {
                String reason = capabilitySnapshot.getBlockingReason(action);
                if (reason != null) {
                    capabilityReasons.put(action, reason);
                    capabilityArgs.put(action, capabilitySnapshot.getReasonArgs(action));
                }
            }
            context.capabilityBlockingReasons = capabilityReasons;
            context.capabilityReasonArgs = capabilityArgs;
        }
        return new DrPlanActionEvaluation(eligibility,
                ACTION_AVAILABILITY_EVALUATOR.evaluate(eligibility, context));
    }

    private boolean hasCommittedTargetAuthority(DrPlanVO plan) {
        if (plan == null || drCutoverSessionDao == null || drReplicaDao == null) {
            return false;
        }
        DrCutoverSessionVO session = drCutoverSessionDao.findLatestActiveByPlanId(plan.getId());
        if (session == null
                || !StringUtils.equalsAnyIgnoreCase(session.getState(), "PROMOTED", "COMPLETED", "FAILED_OVER")
                || !StringUtils.equalsIgnoreCase(session.getCloudPromotionState(), "PROMOTED")
                || !StringUtils.equalsIgnoreCase(session.getEngineAckState(), "ACKNOWLEDGED")
                || session.getCloudAuthorityGeneration() == null || session.getCloudAuthorityGeneration() <= 0) {
            return false;
        }
        List<DrReplicaVO> replicas = drReplicaDao.listActiveByPlanId(plan.getId());
        if (replicas == null) {
            return false;
        }
        for (DrReplicaVO replica : replicas) {
            if (replica != null && replica.getTargetVmId() != null
                    && StringUtils.equalsIgnoreCase(replica.getActiveSide(), "TARGET")) {
                return true;
            }
        }
        return false;
    }

    private boolean isRecoverSyncRequired(DrPlanVO plan, DrPlanRuntimeVO runtime, boolean sourceAuthority) {
        if (plan == null || runtime == null || !sourceAuthority
                || !StringUtils.equalsAnyIgnoreCase(plan.getState(), DrConstants.PLAN_STATE_READY,
                        DrConstants.PLAN_STATE_SYNCING, DrConstants.PLAN_STATE_ERROR, "DEGRADED")) {
            return false;
        }
        if (StringUtils.equalsAnyIgnoreCase(runtime.getSchedulerRecoveryState(),
                DrConstants.SCHEDULER_RECOVERY_PENDING, DrConstants.SCHEDULER_RECOVERY_RECOVERING)) {
            return false;
        }
        if (StringUtils.equalsIgnoreCase(runtime.getSchedulerRecoveryState(),
                DrConstants.SCHEDULER_RECOVERY_REQUIRED)) {
            return true;
        }
        String desiredState = StringUtils.defaultIfBlank(runtime.getSchedulerDesiredState(), "RUNNING");
        if (!StringUtils.equalsIgnoreCase(desiredState, "RUNNING")) {
            return false;
        }
        return !runtime.isSchedulerPidAlive()
                || StringUtils.equalsAnyIgnoreCase(runtime.getSchedulerHealthState(),
                        "DEAD", "HEARTBEAT_STALE", "RECOVERY_FAILED", "OWNER_MISMATCH")
                || StringUtils.equalsAnyIgnoreCase(runtime.getSchedulerUnitActiveState(), "failed", "inactive");
    }

    private boolean isFtctlDrControlReady(long planId) {
        List<DrRunVO> runs = drRunDao.listByPlanId(planId);
        if (runs == null || runs.isEmpty()) {
            runs = new java.util.ArrayList<DrRunVO>();
            runs.add(drRunDao.findLatestByPlanId(planId));
        }
        for (DrRunVO run : runs) {
            if (isFtctlDrControlStatusReady(run)) {
                return true;
            }
        }
        return false;
    }

    private boolean isFtctlDrControlStatusReady(DrRunVO run) {
        if (run == null || StringUtils.isBlank(run.getLastStatusJson())) {
            return false;
        }
        try {
            JsonObject runtime = JsonParser.parseString(run.getLastStatusJson()).getAsJsonObject();
            if (!runtime.has("control_protocol_version") || runtime.get("control_protocol_version").getAsInt() < 2
                    || !runtime.has("control_generation") || !runtime.has("control_ack_generation")) {
                return false;
            }
            long generation = runtime.get("control_generation").getAsLong();
            long acknowledged = runtime.get("control_ack_generation").getAsLong();
            String controlState = runtime.has("control_state") ? runtime.get("control_state").getAsString() : null;
            return acknowledged >= generation && !StringUtils.equalsIgnoreCase(controlState, "ERROR");
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean isLegacyFtctlPlan(DrPlanVO plan) {
        return StringUtils.equalsIgnoreCase(DrConstants.ENGINE_TYPE_FTCTL, plan.getEngineType())
                || StringUtils.equalsIgnoreCase(DrConstants.ENGINE_BINDING_TYPE_FTCTL, plan.getEngineBindingType());
    }

    private boolean isFtctlDrPlan(DrPlanVO plan) {
        return StringUtils.equalsIgnoreCase(DrConstants.ENGINE_TYPE_FTCTL_DR, plan.getEngineType())
                || StringUtils.equalsIgnoreCase(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR, plan.getEngineBindingType());
    }

    private boolean isVmwarePhase1Plan(DrPlanVO plan) {
        return StringUtils.equalsIgnoreCase(DrConstants.ENGINE_TYPE_VMWARE_PHASE1, plan.getEngineType())
                || StringUtils.equalsIgnoreCase(DrConstants.ENGINE_BINDING_TYPE_VMWARE_PHASE1, plan.getEngineBindingType());
    }

    private boolean isV2kPlan(DrPlanVO plan) {
        return StringUtils.equalsIgnoreCase(DrConstants.ENGINE_TYPE_V2K, plan.getEngineType())
                || StringUtils.equalsIgnoreCase(DrConstants.ENGINE_BINDING_TYPE_V2K, plan.getEngineBindingType());
    }

    private boolean hasRuntimeResources(long planId, DrPlanVO plan) {
        if (plan != null && plan.getTargetReadyAt() != null) {
            return true;
        }
        return (drReplicaDao != null && !drReplicaDao.listActiveByPlanId(planId).isEmpty())
                || (drRestorePointDao != null && !drRestorePointDao.listActiveByPlanId(planId).isEmpty());
    }

    private boolean isProtectedPlanState(DrPlanVO plan) {
        return plan != null && StringUtils.equalsAny(plan.getState(),
                DrConstants.PLAN_STATE_SYNCING,
                DrConstants.PLAN_STATE_READY,
                DrConstants.PLAN_STATE_TESTING,
                DrConstants.PLAN_STATE_PAUSED,
                DrConstants.PLAN_STATE_FAILED_OVER,
                DrConstants.PLAN_STATE_COMMIT_VERIFYING);
    }

    private boolean hasDisruptiveUpdate(DrPlanVO plan, DrPlanVO update) {
        return (update.getSourceVmId() != null && !Objects.equals(update.getSourceVmId(), plan.getSourceVmId()))
                || (update.getSourceExternalRef() != null && !StringUtils.equals(update.getSourceExternalRef(), plan.getSourceExternalRef()))
                || (StringUtils.isNotBlank(update.getEngineType()) && !StringUtils.equalsIgnoreCase(update.getEngineType(), plan.getEngineType()))
                || (StringUtils.isNotBlank(update.getEngineBindingType()) && !StringUtils.equalsIgnoreCase(update.getEngineBindingType(), plan.getEngineBindingType()))
                || (update.getEngineBindingId() != null && !Objects.equals(update.getEngineBindingId(), plan.getEngineBindingId()))
                || (update.getMappingJson() != null && !StringUtils.equals(update.getMappingJson(), plan.getMappingJson()));
    }

    private DrPlanVO requirePlan(long planId) {
        DrPlanVO plan = drPlanDao.findById(planId);
        if (plan == null || plan.getRemoved() != null) {
            throw new InvalidParameterValueException(DrConstants.ERROR_PLAN_NOT_FOUND + ": " + planId);
        }
        return plan;
    }

    private void validatePlan(DrPlanVO plan) {
        if (plan == null) {
            throw new InvalidParameterValueException("DR plan is required");
        }
        if (StringUtils.isBlank(plan.getName())) {
            throw new InvalidParameterValueException("DR plan name is required");
        }
        if (StringUtils.isBlank(plan.getDirection())) {
            throw new InvalidParameterValueException("DR plan direction is required");
        }
        if (plan.getSourceVmId() == null && StringUtils.isBlank(plan.getSourceExternalRef())) {
            throw new InvalidParameterValueException("DR plan source workload is required");
        }
        validatePositiveSeconds(plan.getRpoSeconds(), "RPO");
        validatePositiveSeconds(plan.getRtoSeconds(), "RTO");
        validateJson(plan.getScheduleJson(), "schedule JSON");
        validateJson(plan.getPolicyJson(), "policy JSON");
        validateJson(plan.getMappingJson(), "mapping JSON");
        validateJson(plan.getQuiescePolicyJson(), "quiesce policy JSON");
    }

    private DrSiteVO[] ensureSitesExist(DrPlanVO plan) {
        DrSiteVO sourceSite = drSiteDao.findById(plan.getSourceSiteId());
        if (sourceSite == null || sourceSite.getRemoved() != null) {
            throw new InvalidParameterValueException(DrConstants.ERROR_SITE_NOT_FOUND + ": source site " + plan.getSourceSiteId());
        }
        DrSiteVO targetSite = drSiteDao.findById(plan.getTargetSiteId());
        if (targetSite == null || targetSite.getRemoved() != null) {
            throw new InvalidParameterValueException(DrConstants.ERROR_SITE_NOT_FOUND + ": target site " + plan.getTargetSiteId());
        }
        return new DrSiteVO[] {sourceSite, targetSite};
    }

    private void validatePlanTopology(DrPlanVO plan, DrSiteVO sourceSite, DrSiteVO targetSite) {
        String direction = StringUtils.upperCase(plan.getDirection());
        String engineType = StringUtils.upperCase(StringUtils.defaultIfBlank(plan.getEngineType(), plan.getEngineBindingType()));
        if (!StringUtils.equalsAny(direction, DrConstants.DIRECTION_KVM_TO_KVM, DrConstants.DIRECTION_KVM_TO_VMWARE,
                DrConstants.DIRECTION_VMWARE_TO_VMWARE, DrConstants.DIRECTION_VMWARE_TO_KVM)) {
            throw new InvalidParameterValueException("Unsupported DR plan direction: " + plan.getDirection());
        }

        validateSiteHypervisor(direction, sourceSite, targetSite);
        if (StringUtils.equals(engineType, DrConstants.ENGINE_TYPE_FTCTL)
                && !StringUtils.equals(direction, DrConstants.DIRECTION_KVM_TO_KVM)) {
            throw new InvalidParameterValueException("FTCTL DR plans support only " + DrConstants.DIRECTION_KVM_TO_KVM);
        }
        if (StringUtils.equals(engineType, DrConstants.ENGINE_TYPE_FTCTL_DR)) {
            return;
        }
        if (StringUtils.equals(engineType, DrConstants.ENGINE_TYPE_VMWARE_PHASE1)
                && !StringUtils.equals(direction, DrConstants.DIRECTION_KVM_TO_VMWARE)) {
            throw new InvalidParameterValueException("VMware Phase 1 DR plans support only " + DrConstants.DIRECTION_KVM_TO_VMWARE);
        }
        if (StringUtils.equals(engineType, DrConstants.ENGINE_TYPE_V2K)
                && !StringUtils.equals(direction, DrConstants.DIRECTION_VMWARE_TO_KVM)) {
            throw new InvalidParameterValueException("V2K DR plans support only " + DrConstants.DIRECTION_VMWARE_TO_KVM);
        }
    }

    private void normalizePlanEngine(DrPlanVO plan) {
        if (plan == null) {
            return;
        }
        String engineType = StringUtils.upperCase(StringUtils.trimToEmpty(plan.getEngineType()));
        String engineBindingType = StringUtils.upperCase(StringUtils.trimToEmpty(plan.getEngineBindingType()));
        if (StringUtils.isBlank(engineType) && StringUtils.isBlank(engineBindingType)) {
            return;
        }
        if (StringUtils.isBlank(engineType)) {
            engineType = engineBindingType;
        }
        if (StringUtils.isBlank(engineBindingType)) {
            engineBindingType = engineType;
        }
        if (StringUtils.equalsAny(engineType, DrConstants.ENGINE_TYPE_FTCTL_DR, DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR)
                || StringUtils.equalsAny(engineBindingType, DrConstants.ENGINE_TYPE_FTCTL_DR, DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR)) {
            plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
            plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
            return;
        }
        if (StringUtils.equalsAny(engineType, DrConstants.ENGINE_TYPE_FTCTL, DrConstants.ENGINE_BINDING_TYPE_FTCTL)
                || StringUtils.equalsAny(engineBindingType, DrConstants.ENGINE_TYPE_FTCTL, DrConstants.ENGINE_BINDING_TYPE_FTCTL)) {
            plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL);
            plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL);
            return;
        }
        plan.setEngineType(engineType);
        plan.setEngineBindingType(engineBindingType);
    }

    private void validateSiteHypervisor(String direction, DrSiteVO sourceSite, DrSiteVO targetSite) {
        if (StringUtils.startsWith(direction, "KVM") && !StringUtils.equalsIgnoreCase(DrConstants.HYPERVISOR_TYPE_KVM, sourceSite.getHypervisorType())) {
            throw new InvalidParameterValueException("Source site must use KVM for " + direction + " plans");
        }
        if (StringUtils.startsWith(direction, "VMWARE") && !StringUtils.equalsIgnoreCase(DrConstants.HYPERVISOR_TYPE_VMWARE, sourceSite.getHypervisorType())) {
            throw new InvalidParameterValueException("Source site must use VMware for " + direction + " plans");
        }
        if (StringUtils.endsWith(direction, "KVM") && !StringUtils.equalsIgnoreCase(DrConstants.HYPERVISOR_TYPE_KVM, targetSite.getHypervisorType())) {
            throw new InvalidParameterValueException("Target site must use KVM for " + direction + " plans");
        }
        if (StringUtils.endsWith(direction, "VMWARE") && !StringUtils.equalsIgnoreCase(DrConstants.HYPERVISOR_TYPE_VMWARE, targetSite.getHypervisorType())) {
            throw new InvalidParameterValueException("Target site must use VMware for " + direction + " plans");
        }
    }

    private void ensureNoDuplicatePlan(DrPlanVO plan) {
        if (plan.getSourceVmId() != null && drPlanDao.findActiveBySourceVmId(plan.getSourceVmId()) != null) {
            throw new InvalidParameterValueException(DrConstants.ERROR_DUPLICATE_PLAN + ": source VM already has an active DR plan");
        }
        if (StringUtils.isNotBlank(plan.getSourceExternalRef())
                && drPlanDao.findActiveBySourceSiteAndExternalRef(plan.getSourceSiteId(), plan.getSourceExternalRef()) != null) {
            throw new InvalidParameterValueException(DrConstants.ERROR_DUPLICATE_PLAN + ": source workload already has an active DR plan");
        }
        if (StringUtils.isNotBlank(plan.getEngineBindingType()) && plan.getEngineBindingId() != null
                && drPlanDao.findActiveByEngineBinding(plan.getEngineBindingType(), plan.getEngineBindingId()) != null) {
            throw new InvalidParameterValueException(DrConstants.ERROR_DUPLICATE_PLAN + ": engine binding already has an active DR plan");
        }
    }

    private void validatePositiveSeconds(Integer seconds, String label) {
        if (seconds != null && seconds <= 0) {
            throw new InvalidParameterValueException(label + " must be greater than 0 seconds");
        }
    }

    private void validateJson(String value, String label) {
        if (StringUtils.isBlank(value)) {
            return;
        }
        try {
            JsonParser.parseString(value);
        } catch (RuntimeException e) {
            throw new InvalidParameterValueException("Invalid DR plan " + label);
        }
    }
}
