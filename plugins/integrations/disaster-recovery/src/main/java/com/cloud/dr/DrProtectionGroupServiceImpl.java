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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import org.apache.cloudstack.managed.context.ManagedContextRunnable;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.dr.dao.DrGroupRunDao;
import com.cloud.dr.dao.DrPlanDao;
import com.cloud.dr.dao.DrRunDao;
import com.cloud.dr.dao.DrSyncCycleDao;
import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.FtctlDrStatusAnswer;
import com.cloud.agent.api.FtctlDrStatusCommand;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.concurrency.NamedThreadFactory;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.db.TransactionStatus;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class DrProtectionGroupServiceImpl extends ManagerBase implements DrProtectionGroupService {
    private static final Logger LOGGER = LogManager.getLogger(DrProtectionGroupServiceImpl.class);
    private static final int DEFAULT_MAX_PARALLEL = 2;
    private static final int MAX_PARALLEL = 16;
    private static final long RUN_TIMEOUT_MILLIS = TimeUnit.HOURS.toMillis(24);
    private static final long RESULT_FINALIZING_GRACE_MILLIS = TimeUnit.SECONDS.toMillis(30);

    @Inject private DrPlanDao drPlanDao;
    @Inject private DrRunDao drRunDao;
    @Inject private DrRunService drRunService;
    @Inject private DrPlanService drPlanService;
    @Inject private DrProjectionService drProjectionService;
    @Inject private DrGroupRunDao drGroupRunDao;
    @Inject private DrSyncCycleDao drSyncCycleDao;
    @Inject private DrAdmissionController drAdmissionController;
    @Inject private AgentManager agentManager;
    @Inject private DrWorkerPlacementService drWorkerPlacementService;

    private ExecutorService groupExecutor;

    @Override public boolean start() {
        groupExecutor = Executors.newFixedThreadPool(2, new NamedThreadFactory("DrProtectionGroup"));
        for (DrGroupRunVO run : drGroupRunDao.listRecoverable()) {
            queue(run.getId(), null);
        }
        return true;
    }

    @Override public boolean stop() {
        if (groupExecutor != null) {
            groupExecutor.shutdownNow();
            groupExecutor = null;
        }
        return true;
    }

    @Override
    public String configureGroup(List<Long> planIds, String groupName, Integer maxParallel, Boolean quiesceRequired) {
        List<DrPlanVO> plans = loadAndValidatePlans(planIds);
        validateCompatiblePlans(plans);
        String groupUuid = UUID.randomUUID().toString();
        int parallel = normalizeParallel(maxParallel);
        for (int index = 0; index < plans.size(); index++) {
            DrPlanVO plan = plans.get(index);
            plan.setProtectionGroupUuid(groupUuid);
            plan.setProtectionGroupName(StringUtils.defaultIfBlank(groupName, "DR protection group"));
            plan.setProtectionGroupOrder(index);
            plan.setProtectionGroupMaxParallel(parallel);
            plan.setProtectionGroupQuiesceRequired(Boolean.TRUE.equals(quiesceRequired));
            plan.markUpdated();
            drPlanDao.update(plan.getId(), plan);
        }
        return groupUuid;
    }

    @Override
    public DrGroupRunVO startGroupRun(List<Long> planIds, String action, Integer maxParallel, Boolean quiesceRequired,
            boolean fullReseed, Long requestedByUserId) {
        List<DrPlanVO> plans = loadAndValidatePlans(planIds);
        validateCompatiblePlans(plans);
        String runType = normalizeAction(action);
        boolean requiresQuiesce = Boolean.TRUE.equals(quiesceRequired)
                || plans.stream().anyMatch(plan -> Boolean.TRUE.equals(plan.getProtectionGroupQuiesceRequired()));
        DrProtectionGroupPreflight preflight = evaluatePreflight(plans, runType, requiresQuiesce);
        String groupUuid = commonGroupUuid(plans);
        if (groupUuid == null) {
            groupUuid = configureGroup(planIds, "Ad-hoc DR group", maxParallel, quiesceRequired);
            plans = loadAndValidatePlans(planIds);
        }
        JsonArray planIdsJson = new JsonArray();
        plans.forEach(plan -> planIdsJson.add(plan.getId()));
        DrGroupRunVO groupRun = new DrGroupRunVO(groupUuid,
                StringUtils.defaultIfBlank(plans.get(0).getProtectionGroupName(), "DR protection group"), runType,
                planIdsJson.toString(), normalizeParallel(maxParallel != null ? maxParallel : plans.get(0).getProtectionGroupMaxParallel()),
                requiresQuiesce, plans.size());
        groupRun = drGroupRunDao.persist(groupRun);
        if (!preflight.isReady()) {
            finalizeBlockedGroup(groupRun, preflight);
            return drGroupRunDao.findById(groupRun.getId());
        }
        queue(groupRun.getId(), requestedByUserId);
        return groupRun;
    }

    @Override
    public DrProtectionGroupPreflight previewGroupRun(List<Long> planIds, String action, Boolean quiesceRequired) {
        List<DrPlanVO> plans = loadAndValidatePlans(planIds);
        validateCompatiblePlans(plans);
        return evaluatePreflight(plans, normalizeAction(action), Boolean.TRUE.equals(quiesceRequired));
    }

    @Override public List<DrGroupRunVO> listGroupRuns(String groupUuid) {
        return drGroupRunDao.listByGroupUuid(groupUuid);
    }

    private void queue(final long groupRunId, final Long requestedByUserId) {
        if (groupExecutor == null) {
            execute(groupRunId, requestedByUserId);
            return;
        }
        groupExecutor.submit(new ManagedContextRunnable() {
            @Override protected void runInContext() {
                execute(groupRunId, requestedByUserId);
            }
        });
    }

    private void execute(long groupRunId, Long requestedByUserId) {
        DrGroupRunVO groupRun = drGroupRunDao.findById(groupRunId);
        if (groupRun == null || groupRun.getCompleted() != null) {
            return;
        }
        List<DrPlanVO> plans = loadAndValidatePlans(parsePlanIds(groupRun.getPlanIdsJson()));
        plans.sort(Comparator.comparingInt(plan -> plan.getProtectionGroupOrder() != null ? plan.getProtectionGroupOrder() : Integer.MAX_VALUE));
        if (StringUtils.equals(groupRun.getAction(), DrConstants.RUN_TYPE_FAILBACK)) {
            plans.sort(Comparator.comparingInt((DrPlanVO plan) -> plan.getProtectionGroupOrder() != null
                    ? plan.getProtectionGroupOrder() : Integer.MAX_VALUE).reversed());
        }
        if (requiresExecutionPreflight(groupRun)) {
            DrProtectionGroupPreflight preflight = evaluatePreflight(plans, groupRun.getAction(), groupRun.isQuiesceRequired());
            if (!preflight.isReady()) {
                finalizeBlockedGroup(groupRun, preflight);
                return;
            }
        }
        groupRun.setState("RUNNING");
        drGroupRunDao.update(groupRun.getId(), groupRun);
        JsonArray progress = new JsonArray();
        int succeeded = 0;
        int failed = 0;
        for (int offset = 0; offset < plans.size() && failed == 0; offset += groupRun.getMaxParallel()) {
            List<DrRunVO> batch = new ArrayList<>();
            int end = Math.min(plans.size(), offset + groupRun.getMaxParallel());
            for (int index = offset; index < end; index++) {
                DrPlanVO plan = plans.get(index);
                JsonObject request = new JsonObject();
                request.addProperty("groupRunUuid", groupRun.getUuid());
                request.addProperty("groupUuid", groupRun.getGroupUuid());
                request.addProperty("groupOrder", plan.getProtectionGroupOrder());
                request.addProperty("quiesceRequired", groupRun.isQuiesceRequired());
                request.addProperty("scheduleJitterSeconds", Math.min(60, index * 2));
                if (StringUtils.equals(groupRun.getAction(), DrConstants.RUN_TYPE_SYNC)) {
                    request.addProperty("mode", "FULL_RESEED");
                    request.addProperty("forceFullReseed", true);
                    request.addProperty("forceImmediateCycle", true);
                }
                DrRunVO child = findGroupChildRun(groupRun, plan);
                if (child == null) {
                    child = drRunService.startRun(plan.getId(), groupRun.getAction(),
                            groupRun.getUuid() + ":" + plan.getId(), requestedByUserId, null, request.toString());
                }
                batch.add(child);
                progress.add(progressEntry(plan, child, child.getState(), null));
            }
            updateProgress(groupRun, progress, succeeded, failed);
            long deadline = System.currentTimeMillis() + RUN_TIMEOUT_MILLIS;
            boolean batchRunning = !batch.isEmpty();
            while (batchRunning && System.currentTimeMillis() < deadline) {
                batchRunning = false;
                succeeded = 0;
                failed = 0;
                progress = new JsonArray();
                for (DrPlanVO plan : plans) {
                    DrRunVO child = findGroupChildRun(groupRun, plan);
                    if (child == null) {
                        progress.add(progressEntry(plan, null, "PENDING", null));
                        continue;
                    }
                    child = reconcileGroupChildTerminal(plan, groupRun, child);
                    boolean terminalSuccess = isGroupChildTerminalSuccess(child);
                    String progressState = terminalSuccess ? DrConstants.RUN_STATE_SUCCEEDED
                            : isFullReseedChild(child) && StringUtils.equals(child.getState(), DrConstants.RUN_STATE_SUCCEEDED)
                                    ? "RESULT_FINALIZING" : child.getState();
                    progress.add(progressEntry(plan, child, progressState, child.getErrorMessage()));
                    if (terminalSuccess) {
                        succeeded++;
                    } else if (StringUtils.equalsAny(child.getState(), DrConstants.RUN_STATE_FAILED, DrConstants.RUN_STATE_CANCELED)) {
                        failed++;
                    } else {
                        batchRunning = true;
                    }
                }
                updateProgress(groupRun, progress, succeeded, failed);
                if (batchRunning) {
                    sleep(5);
                }
            }
            if (batchRunning) {
                failed++;
            }
        }
        completeGroupRun(groupRun, plans, succeeded, failed);
    }

    boolean requiresExecutionPreflight(DrGroupRunVO groupRun) {
        return groupRun == null || !StringUtils.equalsIgnoreCase(groupRun.getState(), "RUNNING");
    }

    void completeGroupRun(DrGroupRunVO groupRun, List<DrPlanVO> plans, int succeeded, int failed) {
        final int succeededCount = succeeded;
        final int failedCount = failed;
        Transaction.execute(new TransactionCallback<Void>() {
            @Override
            public Void doInTransaction(TransactionStatus status) {
                if (failedCount == 0 && succeededCount == plans.size()) {
                    for (DrPlanVO plan : plans) {
                        DrSyncCycleVO completedCycle = drSyncCycleDao.findLatestCompletedByPlanId(plan.getId());
                        if (completedCycle == null || completedCycle.getCompleted() == null) {
                            continue;
                        }
                        for (DrSyncCycleVO alias : drSyncCycleDao.listIncompleteAtOrBeforeSequence(plan.getId(),
                                completedCycle.getSequence(), 100)) {
                            drSyncCycleDao.terminalize(alias.getId(), "SUPERSEDED", "SUPERSEDED_BY_GROUP_DURABLE_CYCLE",
                                    completedCycle.getCompleted());
                        }
                    }
                }
                groupRun.setSucceededCount(succeededCount);
                groupRun.setFailedCount(failedCount);
                groupRun.setState(failedCount == 0 && succeededCount == plans.size() ? "SUCCEEDED" : "FAILED");
                groupRun.setCompleted(new Date());
                drGroupRunDao.update(groupRun.getId(), groupRun);
                return null;
            }
        });
    }

    private DrRunVO findGroupChildRun(DrGroupRunVO groupRun, DrPlanVO plan) {
        for (DrRunVO run : drRunDao.listByPlanId(plan.getId())) {
            if (run != null && StringUtils.equals(run.getIdempotencyKey(), groupRun.getUuid() + ":" + plan.getId())) {
                return run;
            }
        }
        return null;
    }

    DrRunVO reconcileGroupChildTerminal(DrPlanVO plan, DrGroupRunVO groupRun, DrRunVO child) {
        if (child == null || StringUtils.equalsAny(child.getState(), DrConstants.RUN_STATE_FAILED,
                DrConstants.RUN_STATE_CANCELED)) {
            return child;
        }
        if (!isFullReseedChild(child)) {
            return child;
        }
        DrRunVO converged = convergeGroupChildTerminal(groupRun, child);
        if (isGroupChildTerminalSuccess(converged)) {
            return converged;
        }
        if (drAdmissionController != null) {
            drAdmissionController.renew(child.getId());
        }
        // A completed child waits for its accepted Cycle to become durable in the
        // projection DB. Do not block the group monitor on a remote status call.
        if (!StringUtils.equals(child.getState(), DrConstants.RUN_STATE_SUCCEEDED)) {
            try {
                drProjectionService.refreshPlanProjection(plan.getId(), true);
            } catch (RuntimeException e) {
                LOGGER.debug("Deferred DR protection group child terminal reconciliation for group [{}], plan [{}], run [{}]",
                        groupRun != null ? groupRun.getUuid() : null, plan.getUuid(), child.getUuid(), e);
            }
        }
        DrRunVO refreshed = findGroupChildRun(groupRun, plan);
        return convergeGroupChildTerminal(groupRun, refreshed != null ? refreshed : converged);
    }

    DrRunVO convergeGroupChildTerminal(DrGroupRunVO groupRun, DrRunVO child) {
        if (groupRun == null || child == null
                || !StringUtils.equals(child.getState(), DrConstants.RUN_STATE_SUCCEEDED)) {
            return child;
        }
        return Transaction.execute(new TransactionCallback<DrRunVO>() {
            @Override
            public DrRunVO doInTransaction(TransactionStatus status) {
                DrRunVO current = drRunDao.findById(child.getId());
                DrSyncCycleVO durableCycle = current != null && isFullReseedChild(current)
                        ? acceptedDurableCycle(current) : null;
                if (current != null && isFullReseedChild(current) && !current.isTerminalAuthoritative()) {
                    if (durableCycle != null) {
                        current.setTerminalSource("CYCLE_DURABLE");
                        current.setTerminalAuthoritative(true);
                        current.setProjectionState("succeeded");
                        current.setProjectionChecked(new Date());
                        current.markUpdated();
                        drRunDao.update(current.getId(), current);
                    }
                }
                if (!isGroupChildTerminalSuccess(current)) {
                    return current != null ? current : child;
                }
                if (drAdmissionController != null) {
                    drAdmissionController.release(current.getId());
                }
                DrGroupRunVO currentGroup = drGroupRunDao.findById(groupRun.getId());
                if (currentGroup != null && currentGroup.getCompleted() == null) {
                    int succeeded = 0;
                    int failed = 0;
                    for (Long planId : parsePlanIds(currentGroup.getPlanIdsJson())) {
                        DrPlanVO member = drPlanDao.findById(planId);
                        DrRunVO memberRun = member != null ? findGroupChildRun(currentGroup, member) : null;
                        if (isGroupChildTerminalSuccess(memberRun)) {
                            succeeded++;
                        } else if (memberRun != null && StringUtils.equalsAny(memberRun.getState(),
                                DrConstants.RUN_STATE_FAILED, DrConstants.RUN_STATE_CANCELED)) {
                            failed++;
                        }
                    }
                    currentGroup.setSucceededCount(succeeded);
                    currentGroup.setFailedCount(failed);
                    drGroupRunDao.update(currentGroup.getId(), currentGroup);
                }
                return current;
            }
        });
    }

    private boolean isFullReseedChild(DrRunVO run) {
        if (run == null || !StringUtils.equalsIgnoreCase(run.getRunType(), DrConstants.RUN_TYPE_SYNC)
                || StringUtils.isBlank(run.getRequestJson())) {
            return false;
        }
        try {
            JsonElement parsed = JsonParser.parseString(run.getRequestJson());
            return parsed.isJsonObject() && parsed.getAsJsonObject().has("mode")
                    && StringUtils.equalsIgnoreCase(parsed.getAsJsonObject().get("mode").getAsString(), "FULL_RESEED");
        } catch (RuntimeException e) {
            return false;
        }
    }

    boolean isGroupChildTerminalSuccess(DrRunVO run) {
        if (run == null || !StringUtils.equals(run.getState(), DrConstants.RUN_STATE_SUCCEEDED)) {
            return false;
        }
        return !isFullReseedChild(run) || run.isTerminalAuthoritative() && acceptedDurableCycle(run) != null;
    }

    private DrSyncCycleVO acceptedDurableCycle(DrRunVO run) {
        if (run == null || !StringUtils.equalsIgnoreCase(run.getRunType(), DrConstants.RUN_TYPE_SYNC)) {
            return null;
        }
        DrSyncCycleVO cycle = run.getAcceptedCycleSequence() != null
                ? drSyncCycleDao.findByPlanSequence(run.getPlanId(), run.getAcceptedCycleSequence())
                : drSyncCycleDao.findLatestCompletedByRunIdAndRequestedMode(run.getId(), "FULL_SEED");
        if (cycle == null && run.getAcceptedCycleSequence() == null) {
            cycle = drSyncCycleDao.findLatestCompletedByRunIdAndRequestedMode(run.getId(), "FULL_RESEED");
        }
        boolean tokenMatches = cycle != null && (StringUtils.isBlank(run.getAcceptedCycleToken())
                || StringUtils.equals(run.getAcceptedCycleToken(), cycle.getCycleToken()));
        return cycle != null && tokenMatches && cycle.getCompleted() != null
                && isFullSeedCycleMode(cycle.getRequestedMode())
                && StringUtils.equalsAnyIgnoreCase(cycle.getState(), "READY", "COMPLETED", "TARGET_READY")
                && StringUtils.equalsAnyIgnoreCase(cycle.getCommitState(), "LOCAL_DURABLE", "COMMITTED", "DURABLE")
                ? cycle : null;
    }

    private boolean isFullSeedCycleMode(String mode) {
        String normalized = StringUtils.upperCase(StringUtils.trimToEmpty(mode), Locale.ROOT).replace('-', '_');
        return StringUtils.equalsAny(normalized, "FULL_SEED", "FULL_RESEED");
    }

    private void updateProgress(DrGroupRunVO run, JsonArray progress, int succeeded, int failed) {
        JsonObject summary = new JsonObject();
        int dataTransferCompleted = 0;
        int resultFinalizing = 0;
        int consistencyWarnings = 0;
        for (JsonElement element : progress) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            if (entry.has("dataTransferCompleted") && entry.get("dataTransferCompleted").getAsBoolean()) {
                dataTransferCompleted++;
            }
            String terminalizationState = entry.has("terminalizationState")
                    ? entry.get("terminalizationState").getAsString() : null;
            if (StringUtils.equals(terminalizationState, "RESULT_FINALIZING")) {
                resultFinalizing++;
            } else if (StringUtils.equals(terminalizationState, "CONSISTENCY_WARNING")) {
                consistencyWarnings++;
            }
        }
        summary.addProperty("total", run.getTotalCount());
        summary.addProperty("succeeded", succeeded);
        summary.addProperty("failed", failed);
        summary.addProperty("dataTransferCompletedCount", dataTransferCompleted);
        summary.addProperty("resultFinalizingCount", resultFinalizing);
        summary.addProperty("consistencyWarningCount", consistencyWarnings);
        summary.addProperty("resultVerificationState", consistencyWarnings > 0
                ? "CONSISTENCY_WARNING" : resultFinalizing > 0 ? "RESULT_FINALIZING" : "CONSISTENT");
        summary.add("plans", progress);
        run.setProgressJson(summary.toString());
        run.setSucceededCount(succeeded);
        run.setFailedCount(failed);
        drGroupRunDao.update(run.getId(), run);
    }

    private JsonObject progressEntry(DrPlanVO plan, DrRunVO run, String state, String error) {
        DrPlanVO currentPlan = drPlanDao.findById(plan.getId());
        if (currentPlan != null && currentPlan.getRemoved() == null) {
            plan = currentPlan;
        }
        JsonObject entry = new JsonObject();
        entry.addProperty("planId", plan.getUuid());
        entry.addProperty("planName", plan.getName());
        entry.addProperty("order", plan.getProtectionGroupOrder());
        entry.addProperty("runId", run != null ? run.getUuid() : null);
        entry.addProperty("state", state);
        entry.addProperty("error", error);
        entry.addProperty("initialSyncState", state);
        String continuousState = continuousProtectionState(plan);
        entry.addProperty("continuousProtectionState", continuousState);
        entry.addProperty("targetRpoSeconds", plan.getRpoSeconds());
        entry.addProperty("currentRpoSeconds", plan.getTargetReadyRpoSeconds());
        entry.addProperty("resourceWaiting", StringUtils.equalsAnyIgnoreCase(plan.getLastErrorCode(),
                "DR_RESOURCE_BUSY", "DR_NBD_CAPACITY_INVALID", "DR_TARGET_EXPORT_UNAVAILABLE"));
        DrSyncCycleVO acceptedCycle = run != null ? acceptedDurableCycle(run) : null;
        if (run != null && isFullReseedChild(run)
                && StringUtils.equals(run.getState(), DrConstants.RUN_STATE_SUCCEEDED)
                && !isGroupChildTerminalSuccess(run)) {
            entry.addProperty("terminalizationState", "RESULT_FINALIZING");
        }
        if (acceptedCycle != null && !StringUtils.equalsAny(state, DrConstants.RUN_STATE_SUCCEEDED,
                DrConstants.RUN_STATE_FAILED, DrConstants.RUN_STATE_CANCELED)) {
            long ageMillis = Math.max(0L, System.currentTimeMillis() - acceptedCycle.getCompleted().getTime());
            entry.addProperty("dataTransferCompleted", true);
            entry.addProperty("terminalizationState", ageMillis <= RESULT_FINALIZING_GRACE_MILLIS
                    ? "RESULT_FINALIZING" : "CONSISTENCY_WARNING");
            entry.addProperty("terminalizationAgeSeconds", ageMillis / 1000L);
            entry.addProperty("acceptedCycleSequence", acceptedCycle.getSequence());
        }
        return entry;
    }

    private DrProtectionGroupPreflight evaluatePreflight(List<DrPlanVO> plans, String action,
            boolean quiesceRequired) {
        List<DrProtectionGroupPlanPreflight> results = new ArrayList<>();
        String key = actionKey(action);
        for (DrPlanVO plan : plans) {
            DrActionAvailability availability = drPlanService.getActionAvailability(plan.getId()).get(key);
            boolean eligible = availability != null && availability.isEnabled();
            String reasonCode = availability != null ? availability.getReasonCode() : "DR_ACTION_AVAILABILITY_MISSING";
            Map<String, String> reasonArgs = availability != null && availability.getReasonArgs() != null
                    ? new LinkedHashMap<>(availability.getReasonArgs()) : new LinkedHashMap<>();
            if (eligible && quiesceRequired && StringUtils.isBlank(plan.getQuiescePolicyJson())) {
                eligible = false;
                reasonCode = "DR_GROUP_QUIESCE_POLICY_REQUIRED";
            }
            if (eligible && StringUtils.equals(action, DrConstants.RUN_TYPE_SYNC)
                    && StringUtils.equals(plan.getDirection(), DrConstants.DIRECTION_VMWARE_TO_KVM)) {
                JsonObject capacity = readNbdCapacity(plan);
                boolean configured = booleanValue(capacity, "configured");
                boolean ready = booleanValue(capacity, "ready");
                addCapacityReasonArgs(reasonArgs, capacity);
                if (!configured) {
                    eligible = false;
                    reasonCode = "DR_NBD_CAPACITY_INVALID";
                } else if (!ready) {
                    eligible = false;
                    reasonCode = "DR_RESOURCE_BUSY";
                }
            }
            results.add(new DrProtectionGroupPlanPreflight(plan, eligible, reasonCode, reasonArgs));
        }
        return new DrProtectionGroupPreflight(action, results);
    }

    private JsonObject readNbdCapacity(DrPlanVO plan) {
        Long hostId = drWorkerPlacementService != null
                ? drWorkerPlacementService.resolveWorkerHostId(plan, DrWorkerRole.COORDINATOR) : null;
        if (hostId == null) {
            return unavailableCapacity("DR_NBD_CAPACITY_HOST_UNRESOLVED");
        }
        FtctlDrStatusCommand command = new FtctlDrStatusCommand(plan.getUuid(), null,
                FtctlDrStatusCommand.StatusScope.PLAN_AUTHORITY);
        Answer answer = agentManager.easySend(hostId, command);
        if (!(answer instanceof FtctlDrStatusAnswer) || !answer.getResult()) {
            return unavailableCapacity("DR_NBD_CAPACITY_STATUS_UNAVAILABLE");
        }
        try {
            JsonElement parsed = JsonParser.parseString(((FtctlDrStatusAnswer) answer).getStatusJson());
            JsonObject runtime = parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
            JsonElement capacity = runtime.get("nbd_capacity");
            return capacity != null && capacity.isJsonObject()
                    ? capacity.getAsJsonObject() : unavailableCapacity("DR_NBD_CAPACITY_STATUS_MISSING");
        } catch (RuntimeException e) {
            return unavailableCapacity("DR_NBD_CAPACITY_STATUS_INVALID");
        }
    }

    private JsonObject unavailableCapacity(String errorCode) {
        JsonObject value = new JsonObject();
        value.addProperty("configured", false);
        value.addProperty("ready", false);
        value.addProperty("errorCode", errorCode);
        return value;
    }

    private boolean booleanValue(JsonObject value, String key) {
        return value != null && value.has(key) && !value.get(key).isJsonNull() && value.get(key).getAsBoolean();
    }

    private void addCapacityReasonArgs(Map<String, String> target, JsonObject capacity) {
        for (String key : new String[] {"deviceStart", "deviceEnd", "moduleMaxDevices", "expectedDeviceCount",
                "presentDeviceCount", "freeDeviceCount", "quarantinedDeviceCount", "errorCode"}) {
            if (capacity.has(key) && !capacity.get(key).isJsonNull()) {
                target.put(key, capacity.get(key).getAsString());
            }
        }
    }

    private String continuousProtectionState(DrPlanVO plan) {
        if (StringUtils.equalsAnyIgnoreCase(plan.getLastErrorCode(), "DR_RESOURCE_BUSY", "DR_NBD_CAPACITY_INVALID",
                "DR_TARGET_EXPORT_UNAVAILABLE")) {
            return "WAITING_RESOURCE";
        }
        Integer actual = plan.getTargetReadyRpoSeconds();
        Integer target = plan.getRpoSeconds();
        if (actual != null && target != null && actual > target) {
            return "DEGRADED";
        }
        return StringUtils.equalsAnyIgnoreCase(plan.getState(), DrConstants.PLAN_STATE_READY, DrConstants.HEALTH_DEGRADED)
                ? StringUtils.upperCase(plan.getState(), Locale.ROOT) : "PENDING";
    }

    private void finalizeBlockedGroup(DrGroupRunVO groupRun, DrProtectionGroupPreflight preflight) {
        JsonArray progress = new JsonArray();
        int blocked = 0;
        for (DrProtectionGroupPlanPreflight planResult : preflight.getPlans()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("planId", planResult.getPlanUuid());
            entry.addProperty("planName", planResult.getPlanName());
            entry.addProperty("state", planResult.isEligible() ? "SKIPPED" : "BLOCKED");
            entry.addProperty("reasonCode", planResult.isEligible()
                    ? "DR_GROUP_ATOMIC_PREFLIGHT_FAILED" : planResult.getReasonCode());
            JsonObject reasonArgs = new JsonObject();
            planResult.getReasonArgs().forEach(reasonArgs::addProperty);
            entry.add("reasonArgs", reasonArgs);
            entry.addProperty("error", planResult.isEligible()
                    ? "Group preflight failed for another plan" : planResult.getReasonCode());
            progress.add(entry);
            if (!planResult.isEligible()) {
                blocked++;
            }
        }
        groupRun.setState("FAILED");
        groupRun.setCompleted(new Date());
        updateProgress(groupRun, progress, 0, blocked);
    }

    private List<DrPlanVO> loadAndValidatePlans(List<Long> planIds) {
        if (planIds == null || planIds.isEmpty()) {
            throw new InvalidParameterValueException("At least one DR plan is required");
        }
        List<DrPlanVO> plans = new ArrayList<>();
        for (Long planId : planIds) {
            DrPlanVO plan = planId != null ? drPlanDao.findById(planId) : null;
            if (plan == null || plan.getRemoved() != null) {
                throw new InvalidParameterValueException("DR plan was not found: " + planId);
            }
            plans.add(plan);
        }
        return plans;
    }

    private void validateCompatiblePlans(List<DrPlanVO> plans) {
        DrPlanVO first = plans.get(0);
        for (DrPlanVO plan : plans) {
            if (plan.getSourceSiteId() != first.getSourceSiteId() || plan.getTargetSiteId() != first.getTargetSiteId()
                    || !StringUtils.equals(plan.getDirection(), first.getDirection())) {
                throw new InvalidParameterValueException("DR protection group plans must share source, target, and direction");
            }
        }
    }

    private String commonGroupUuid(List<DrPlanVO> plans) {
        String groupUuid = plans.get(0).getProtectionGroupUuid();
        if (StringUtils.isBlank(groupUuid)) {
            return null;
        }
        return plans.stream().allMatch(plan -> StringUtils.equals(groupUuid, plan.getProtectionGroupUuid())) ? groupUuid : null;
    }

    private List<Long> parsePlanIds(String json) {
        List<Long> result = new ArrayList<>();
        for (com.google.gson.JsonElement item : com.google.gson.JsonParser.parseString(json).getAsJsonArray()) {
            result.add(item.getAsLong());
        }
        return result;
    }

    private int normalizeParallel(Integer value) {
        return Math.min(MAX_PARALLEL, Math.max(1, value != null ? value : DEFAULT_MAX_PARALLEL));
    }

    private String normalizeAction(String action) {
        String value = StringUtils.upperCase(StringUtils.trim(action), Locale.ROOT);
        if (!StringUtils.equalsAny(value, DrConstants.RUN_TYPE_SYNC, DrConstants.RUN_TYPE_TEST_FAILOVER,
                DrConstants.RUN_TYPE_TEST_CLEANUP, DrConstants.RUN_TYPE_FAILOVER, DrConstants.RUN_TYPE_FAILBACK,
                DrConstants.RUN_TYPE_REPROTECT, DrConstants.RUN_TYPE_PAUSE_SYNC, DrConstants.RUN_TYPE_RESUME_SYNC)) {
            throw new InvalidParameterValueException("Unsupported DR protection group action: " + action);
        }
        return value;
    }

    private String actionKey(String action) {
        if (StringUtils.equals(action, DrConstants.RUN_TYPE_TEST_FAILOVER)) return "testFailover";
        if (StringUtils.equals(action, DrConstants.RUN_TYPE_TEST_CLEANUP)) return "stopTestFailover";
        if (StringUtils.equals(action, DrConstants.RUN_TYPE_PAUSE_SYNC)) return "pauseSync";
        if (StringUtils.equals(action, DrConstants.RUN_TYPE_RESUME_SYNC)) return "resumeSync";
        return StringUtils.lowerCase(action, Locale.ROOT);
    }

    private void sleep(int seconds) {
        try {
            Thread.sleep(TimeUnit.SECONDS.toMillis(seconds));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("DR protection group execution was interrupted", e);
        }
    }
}
