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
package com.cloud.dr.orchestrator;

import java.util.Date;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import org.apache.cloudstack.managed.context.ManagedContextRunnable;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.dr.DrConstants;
import com.cloud.dr.DrAdmissionController;
import com.cloud.dr.DrEventVO;
import com.cloud.dr.DrFailbackSessionState;
import com.cloud.dr.DrFailbackSessionVO;
import com.cloud.dr.DrPlanVO;
import com.cloud.dr.DrProjectionService;
import com.cloud.dr.DrRunStepVO;
import com.cloud.dr.DrRunVO;
import com.cloud.dr.DrTargetMaterializationService;
import com.cloud.dr.DrTestSessionState;
import com.cloud.dr.DrTestSessionVO;
import com.cloud.dr.adapter.DrAdapterRegistry;
import com.cloud.dr.adapter.DrAdapterResult;
import com.cloud.dr.adapter.DrExecutionContext;
import com.cloud.dr.adapter.DrFencingAdapter;
import com.cloud.dr.adapter.DrReplicationEngine;
import com.cloud.dr.dao.DrEventDao;
import com.cloud.dr.dao.DrFailbackSessionDao;
import com.cloud.dr.dao.DrPlanDao;
import com.cloud.dr.dao.DrRunDao;
import com.cloud.dr.dao.DrRunStepDao;
import com.cloud.dr.dao.DrTestSessionDao;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.concurrency.NamedThreadFactory;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class DrRunExecutorImpl extends ManagerBase implements DrRunExecutor {
    private static final Logger LOGGER = LogManager.getLogger(DrRunExecutorImpl.class);
    private static final String STEP_PREPARE = "prepare";
    private static final String STEP_DISPATCH = "dispatch-agent";
    private static final String STEP_AGENT_ACCEPT = "agent-accept";
    private static final String STEP_FINAL = "final";
    private static final int STEP_ORDER_PREPARE = 0;
    private static final int STEP_ORDER_DISPATCH = 10;
    private static final int STEP_ORDER_AGENT_ACCEPT = 20;
    private static final int STEP_ORDER_RUNTIME_PROJECTION = 30;
    private static final int STEP_ORDER_FINAL = 90;
    private static final int DEFAULT_RETRY_AFTER_SECONDS = 5;
    private static final int MAX_RETRY_COUNT = 12;
    private static final int ACCEPTED_PROJECTION_INTERVAL_SECONDS = 2;
    private static final int ACCEPTED_PROJECTION_MAX_ATTEMPTS = 180;

    @Inject
    private DrPlanDao drPlanDao;
    @Inject
    private DrRunDao drRunDao;
    @Inject
    private DrRunStepDao drRunStepDao;
    @Inject
    private DrEventDao drEventDao;
    @Inject
    private DrAdapterRegistry drAdapterRegistry;
    @Inject
    private DrProjectionService drProjectionService;
    @Inject
    private DrProtectionOrchestrator drProtectionOrchestrator;
    @Inject
    private DrTargetMaterializationService drTargetMaterializationService;
    @Inject
    private DrAdmissionController drAdmissionController;
    @Inject
    private DrTestSessionDao drTestSessionDao;
    @Inject
    private com.cloud.dr.DrTestCleanupRecoveryStore testCleanupRecovery;
    @Inject
    private DrFailbackSessionDao drFailbackSessionDao;

    private ExecutorService dispatchExecutor;
    private ScheduledExecutorService retryExecutor;

    @Override
    public boolean start() {
        dispatchExecutor = new ThreadPoolExecutor(4, 4, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(512), new NamedThreadFactory("DrRunDispatcher"),
                new ThreadPoolExecutor.AbortPolicy());
        retryExecutor = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("DrRunRetryScheduler"));
        resumeAcceptedProjectionWatches();
        return true;
    }

    @Override
    public boolean stop() {
        if (dispatchExecutor != null) {
            dispatchExecutor.shutdownNow();
            dispatchExecutor = null;
        }
        if (retryExecutor != null) {
            retryExecutor.shutdownNow();
            retryExecutor = null;
        }
        return true;
    }

    @Override
    public void queueRun(DrRunVO run) {
        if (run == null) {
            return;
        }
        final long runId = run.getId();
        ExecutorService executor = dispatchExecutor;
        if (executor == null) {
            executeRunInternal(runId);
            return;
        }
        try {
            executor.submit(new ManagedContextRunnable() {
                @Override
                protected void runInContext() {
                    executeRunInternal(runId);
                }
            });
        } catch (RejectedExecutionException e) {
            DrRunVO latestRun = drRunDao.findById(runId);
            if (latestRun != null) {
                retryRun(latestRun, "DR_RESOURCE_BUSY", "DR dispatcher queue is temporarily full", null,
                        DEFAULT_RETRY_AFTER_SECONDS);
            }
        }
    }

    private void executeRunInternal(long runId) {
        DrRunVO latestRun = drRunDao.findById(runId);
        if (latestRun == null || latestRun.getRemoved() != null || latestRun.getCompleted() != null) {
            return;
        }
        if (StringUtils.equals(DrConstants.RUN_STATE_CANCEL_REQUESTED, latestRun.getState())) {
            cancelRun(latestRun, "DR run was canceled before dispatch");
            return;
        }
        if (!StringUtils.equalsAny(latestRun.getState(), DrConstants.RUN_STATE_QUEUED, DrConstants.RUN_STATE_PREPARING,
                DrConstants.RUN_STATE_DISPATCHING, DrConstants.RUN_STATE_ACCEPTED, DrConstants.RUN_STATE_RETRYING)) {
            return;
        }

        DrPlanVO plan = drPlanDao.findById(latestRun.getPlanId());
        if (plan == null || plan.getRemoved() != null) {
            failRun(latestRun, DrConstants.ERROR_PLAN_NOT_FOUND, "DR plan was not found for run " + latestRun.getId(), null);
            return;
        }
        DrRunVO conflictingRun = findConflictingActiveRun(latestRun);
        if (conflictingRun != null) {
            String message = "Another DR run is active for this plan: " + conflictingRun.getUuid();
            retryRun(latestRun, DrConstants.ERROR_ACTIVE_RUN_EXISTS, message, null, DEFAULT_RETRY_AFTER_SECONDS);
            return;
        }

        if (drAdmissionController != null && !drAdmissionController.acquire(plan, latestRun)) {
            retryRun(latestRun, "DR_RESOURCE_BUSY", "DR execution capacity is temporarily unavailable", null,
                    DEFAULT_RETRY_AFTER_SECONDS);
            return;
        }
        try {
            executeAdmittedRun(plan, latestRun);
        } finally {
            if (drAdmissionController != null && !retainAdmissionLeaseUntilGroupTerminal(latestRun)) {
                drAdmissionController.release(latestRun.getId());
            }
        }
    }

    private void armTestCleanupRecovery(DrPlanVO plan, DrRunVO run) {
        if (testCleanupRecovery == null || drTestSessionDao == null
                || !DrConstants.RUN_TYPE_TEST_CLEANUP.equals(run.getRunType())) {
            return;
        }
        DrTestSessionVO session = drTestSessionDao.findActiveByPlanId(plan.getId());
        if (session != null && testCleanupRecovery.find(session.getRunId()) != null) {
            // Persist before dispatch: synchronous and asynchronous completion share this intent.
            // The recovery worker waits until artifacts are CLEANED and the Run is terminal.
            testCleanupRecovery.arm(plan.getId(), session.getRunId(), run.getId());
        }
    }

    private boolean retainAdmissionLeaseUntilGroupTerminal(DrRunVO run) {
        if (run == null || StringUtils.isBlank(run.getRequestJson())) {
            return false;
        }
        try {
            JsonObject request = JsonParser.parseString(run.getRequestJson()).getAsJsonObject();
            return request.has("groupRunUuid") && StringUtils.isNotBlank(request.get("groupRunUuid").getAsString())
                    && request.has("forceFullReseed") && request.get("forceFullReseed").getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void executeAdmittedRun(DrPlanVO plan, DrRunVO latestRun) {
        LOGGER.debug("Executing DR run {} of type {} for plan {}", latestRun.getId(), latestRun.getRunType(), latestRun.getPlanId());
        armTestCleanupRecovery(plan, latestRun);
        markRunPreparing(latestRun);
        recordEvent(plan.getId(), latestRun.getId(), DrConstants.EVENT_RUN_STARTED, DrConstants.EVENT_SEVERITY_INFO,
                "DR run started", latestRun.getRequestJson());
        recordStep(latestRun.getId(), STEP_PREPARE, STEP_ORDER_PREPARE, DrConstants.STEP_STATE_RUNNING, 5, latestRun.getRequestJson(), null, null);

        DrAdapterResult result;
        try {
            plan = prepareProtectionResources(plan, latestRun);
            cleanupCloudTestResourcesBeforeEngine(plan, latestRun);
            recordStep(latestRun.getId(), STEP_PREPARE, STEP_ORDER_PREPARE, DrConstants.STEP_STATE_SUCCEEDED, 20, latestRun.getRequestJson(), null, null);
            markRunDispatching(latestRun);
            recordStep(latestRun.getId(), STEP_DISPATCH, STEP_ORDER_DISPATCH, DrConstants.STEP_STATE_RUNNING, 30, latestRun.getRequestJson(), null, null);
            result = executeAdapter(plan, latestRun);
        } catch (RuntimeException e) {
            LOGGER.warn("DR run {} failed before agent dispatch: {}", latestRun.getId(), e.getMessage(), e);
            failRun(latestRun, classifyExecutionError(e), e.getMessage(), null);
            return;
        }
        if (result != null && result.isSuccess()) {
            if (result.isTerminal()) {
                try {
                    completeReleaseResourceDisposition(plan, latestRun);
                    completeRun(plan, latestRun, result);
                } catch (RuntimeException e) {
                    LOGGER.warn("DR run {} failed during Cloud-owned release resource disposition: {}",
                            latestRun.getId(), e.getMessage(), e);
                    failRun(latestRun, classifyExecutionError(e), e.getMessage(), result.getDetailsJson());
                }
            } else {
                acceptRun(plan, latestRun, result);
            }
            return;
        }
        String errorCode = result != null ? result.getErrorCode() : DrConstants.ERROR_ENGINE_ACTION_FAILED;
        String message = result != null ? result.getMessage() : "DR action adapter returned no result";
        String detailsJson = result != null ? result.getDetailsJson() : null;
        if (result != null && result.isRetryable()) {
            retryRun(latestRun, errorCode, message, detailsJson, result.getRetryAfterSeconds());
            return;
        }
        failRun(latestRun, errorCode, message, detailsJson);
    }

    private void completeReleaseResourceDisposition(DrPlanVO plan, DrRunVO run) {
        if (plan == null || run == null || drTargetMaterializationService == null
                || !StringUtils.equals(run.getRunType(), DrConstants.RUN_TYPE_RELEASE)) {
            return;
        }
        String disposition = DrConstants.RELEASE_DISPOSITION_RETAIN_OPERATIONAL_VM;
        if (StringUtils.isNotBlank(run.getRequestJson())) {
            JsonObject request = JsonParser.parseString(run.getRequestJson()).getAsJsonObject();
            if (request.has("resourceDisposition") && !request.get("resourceDisposition").isJsonNull()) {
                disposition = request.get("resourceDisposition").getAsString();
            }
        }
        drTargetMaterializationService.validateReleaseDisposition(plan.getId(), disposition);
        if (!drTargetMaterializationService.cleanupReleasedStandbyTarget(plan.getId(), run.getId(), disposition)) {
            throw new IllegalStateException(DrConstants.ERROR_RELEASE_TARGET_NOT_DELETABLE
                    + ": Cloud-managed standby replica cleanup did not complete");
        }
    }

    private DrRunVO findConflictingActiveRun(DrRunVO run) {
        List<DrRunVO> runs = drRunDao.listByPlanId(run.getPlanId());
        if (runs == null) {
            return null;
        }
        for (DrRunVO candidate : runs) {
            if (candidate == null || candidate.getId() == run.getId() || candidate.getRemoved() != null || candidate.getCompleted() != null) {
                continue;
            }
            if (StringUtils.equalsAny(candidate.getState(), DrConstants.RUN_STATE_QUEUED, DrConstants.RUN_STATE_PREPARING,
                    DrConstants.RUN_STATE_DISPATCHING, DrConstants.RUN_STATE_ACCEPTED, DrConstants.RUN_STATE_RUNNING,
                    DrConstants.RUN_STATE_RETRYING, DrConstants.RUN_STATE_CANCEL_REQUESTED)) {
                return candidate;
            }
        }
        return null;
    }

    private DrAdapterResult executeAdapter(DrPlanVO plan, DrRunVO run) {
        String engineType = StringUtils.defaultIfBlank(plan.getEngineType(), plan.getEngineBindingType());
        String engineBindingType = StringUtils.defaultIfBlank(plan.getEngineBindingType(), plan.getEngineType());
        DrExecutionContext context = new DrExecutionContext(plan, run);

        if (StringUtils.equalsIgnoreCase(run.getRunType(), DrConstants.RUN_TYPE_FENCE_CONFIRM)) {
            if (StringUtils.equalsIgnoreCase(engineBindingType, DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR)
                    || StringUtils.equalsIgnoreCase(engineType, DrConstants.ENGINE_TYPE_FTCTL_DR)) {
                return DrAdapterResult.failure(DrConstants.ERROR_FENCE_CLEAR_INTERNAL_ONLY,
                        "FTCTL_DR source isolation is validated internally by failback or reprotect", null);
            }
            DrFencingAdapter adapter = drAdapterRegistry.getFencingAdapter(engineType, engineBindingType);
            if (adapter == null) {
                return DrAdapterResult.failure(DrConstants.ERROR_ENGINE_UNAVAILABLE, "DR fencing adapter is unavailable", null);
            }
            return adapter.confirmFenceClear(context);
        }

        DrReplicationEngine engine = drAdapterRegistry.getReplicationEngine(engineType, engineBindingType);
        if (engine == null) {
            return DrAdapterResult.failure(DrConstants.ERROR_ENGINE_UNAVAILABLE, "DR replication engine is unavailable", null);
        }
        DrAdapterResult validation = engine.validatePlan(plan);
        if (validation != null && !validation.isSuccess()) {
            return validation;
        }
        return engine.execute(context);
    }

    private DrPlanVO prepareProtectionResources(DrPlanVO plan, DrRunVO run) {
        if (!isFtctlDrSyncRun(plan, run) || drProtectionOrchestrator == null) {
            return plan;
        }
        DrPlanVO prepared = drProtectionOrchestrator.prepareSyncRun(plan, run);
        if (prepared != null) {
            plan = prepared;
        }
        if (requiresPlanOwnedTargetPreparation(plan) && drTargetMaterializationService != null
                && !drTargetMaterializationService.prepareSyncTarget(plan.getId(), run.getId())) {
            throw new IllegalStateException("Plan owner could not prepare the DR target VM and volumes before sync");
        }
        DrPlanVO refreshed = drPlanDao.findById(plan.getId());
        if (refreshed != null) {
            plan = refreshed;
        }
        return plan;
    }

    private boolean requiresPlanOwnedTargetPreparation(DrPlanVO plan) {
        return plan != null
                && StringUtils.equalsIgnoreCase(plan.getDirection(), DrConstants.DIRECTION_KVM_TO_KVM)
                && plan.getSourceVmId() == null
                && StringUtils.isNotBlank(plan.getSourceExternalRef());
    }

    private void cleanupCloudTestResourcesBeforeEngine(DrPlanVO plan, DrRunVO run) {
        if (plan == null || run == null || drTargetMaterializationService == null
                || !StringUtils.equals(run.getRunType(), DrConstants.RUN_TYPE_TEST_CLEANUP)) {
            return;
        }
        if (!drTargetMaterializationService.cleanupTestTarget(plan.getId(), run.getId())) {
            throw new IllegalStateException("Cloud-managed DR test resources were not removed before FTCTL artifact cleanup");
        }
    }

    private boolean isFtctlDrSyncRun(DrPlanVO plan, DrRunVO run) {
        return plan != null && run != null
                && StringUtils.equalsAnyIgnoreCase(run.getRunType(),
                        DrConstants.RUN_TYPE_SYNC, DrConstants.RUN_TYPE_RECOVER_SYNC)
                && (StringUtils.equalsIgnoreCase(DrConstants.ENGINE_TYPE_FTCTL_DR, plan.getEngineType())
                        || StringUtils.equalsIgnoreCase(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR, plan.getEngineBindingType()));
    }

    private String classifyExecutionError(RuntimeException e) {
        String message = e != null ? e.getMessage() : null;
        if (StringUtils.startsWith(message, DrConstants.ERROR_TARGET_MAPPING_INVALID)) {
            return DrConstants.ERROR_TARGET_MAPPING_INVALID;
        }
        if (StringUtils.startsWith(message, DrConstants.ERROR_WORKER_BINDING_INVALID)) {
            return DrConstants.ERROR_WORKER_BINDING_INVALID;
        }
        if (StringUtils.startsWith(message, DrConstants.ERROR_RELEASE_DISPOSITION_INVALID)) {
            return DrConstants.ERROR_RELEASE_DISPOSITION_INVALID;
        }
        if (StringUtils.startsWith(message, DrConstants.ERROR_RELEASE_TARGET_AUTHORITY_ACTIVE)) {
            return DrConstants.ERROR_RELEASE_TARGET_AUTHORITY_ACTIVE;
        }
        if (StringUtils.startsWith(message, DrConstants.ERROR_RELEASE_TARGET_NOT_DELETABLE)) {
            return DrConstants.ERROR_RELEASE_TARGET_NOT_DELETABLE;
        }
        return DrConstants.ERROR_ENGINE_ACTION_FAILED;
    }

    private void markRunPreparing(DrRunVO run) {
        run.setState(DrConstants.RUN_STATE_PREPARING);
        if (run.getStarted() == null) {
            run.setStarted(new Date());
        }
        run.setCurrentStepName(STEP_PREPARE);
        run.markUpdated();
        drRunDao.update(run.getId(), run);
    }

    private void markRunDispatching(DrRunVO run) {
        run.setState(DrConstants.RUN_STATE_DISPATCHING);
        if (run.getDispatchStarted() == null) {
            run.setDispatchStarted(new Date());
        }
        run.setCurrentStepName(STEP_DISPATCH);
        run.markUpdated();
        drRunDao.update(run.getId(), run);
    }

    private void completeRun(DrPlanVO plan, DrRunVO run, DrAdapterResult result) {
        DrRunVO latestRun = drRunDao.findById(run.getId());
        if (latestRun != null && StringUtils.equals(DrConstants.RUN_STATE_CANCEL_REQUESTED, latestRun.getState())) {
            cancelRun(latestRun, "DR run completed after cancel request");
            return;
        }
        recordStep(run.getId(), STEP_DISPATCH, STEP_ORDER_DISPATCH, DrConstants.STEP_STATE_SUCCEEDED, 100, result.getDetailsJson(), null, null);
        recordStep(run.getId(), STEP_AGENT_ACCEPT, STEP_ORDER_AGENT_ACCEPT, DrConstants.STEP_STATE_SUCCEEDED, 100, result.getDetailsJson(), null, null);
        recordStep(run.getId(), STEP_FINAL, STEP_ORDER_FINAL, DrConstants.STEP_STATE_SUCCEEDED, 100, result.getDetailsJson(), null, null);
        run.setState(DrConstants.RUN_STATE_SUCCEEDED);
        run.setCompleted(new Date());
        run.setDispatchCompleted(new Date());
        run.setEngineAccepted(true);
        if (run.getAcceptedAt() == null) {
            run.setAcceptedAt(new Date());
        }
        run.setCurrentStepName("completed");
        run.setErrorCode(null);
        run.setErrorMessage(null);
        boolean releaseRun = StringUtils.equals(run.getRunType(), DrConstants.RUN_TYPE_RELEASE);
        run.setProjectionState(releaseRun ? "terminal-pending" : "terminal");
        run.setRetryable(false);
        run.setRetryAfterSeconds(null);
        run.setNextRetryAt(null);
        run.setLastStatusJson(result.getDetailsJson());
        run.markUpdated();
        drRunDao.update(run.getId(), run);
        if (releaseRun) {
            try {
                if (drProjectionService.projectTerminalActionResult(plan.getId(), run, result.getDetailsJson())) {
                    run.setProjectionState("terminal");
                    run.setProjectionChecked(new Date());
                    run.markUpdated();
                    drRunDao.update(run.getId(), run);
                }
            } catch (RuntimeException e) {
                LOGGER.warn("Immediate terminal projection failed for released DR plan {}; periodic projection will retry: {}",
                        plan.getUuid(), e.getMessage());
            }
        }
        if (drTargetMaterializationService != null
                && StringUtils.equals(run.getRunType(), DrConstants.RUN_TYPE_TEST_CLEANUP)) {
            drTargetMaterializationService.completeTestCleanup(plan.getId());
        }
        clearPlanActionError(plan.getId());
        recordEvent(plan.getId(), run.getId(), DrConstants.EVENT_RUN_SUCCEEDED, DrConstants.EVENT_SEVERITY_INFO,
                StringUtils.defaultIfBlank(result.getMessage(), "DR run succeeded"), result.getDetailsJson());
        refreshProjection(plan.getId());
    }

    private void acceptRun(DrPlanVO plan, DrRunVO run, DrAdapterResult result) {
        recordStep(run.getId(), STEP_DISPATCH, STEP_ORDER_DISPATCH, DrConstants.STEP_STATE_SUCCEEDED, 60, result.getDetailsJson(), null, null);
        recordStep(run.getId(), STEP_AGENT_ACCEPT, STEP_ORDER_AGENT_ACCEPT, DrConstants.STEP_STATE_SUCCEEDED, 70, result.getDetailsJson(), null, null);
        run.setState(DrConstants.RUN_STATE_ACCEPTED);
        run.setCurrentStepName("agent-accepted");
        run.setExternalJobRef(result.getExternalJobRef());
        run.setDispatchCompleted(new Date());
        run.setEngineAccepted(true);
        if (run.getAcceptedAt() == null) {
            run.setAcceptedAt(new Date());
        }
        run.setProjectionState("accepted");
        run.setProjectionChecked(null);
        run.setRetryable(false);
        run.setRetryAfterSeconds(null);
        run.setNextRetryAt(null);
        run.setLastStatusJson(result.getDetailsJson());
        run.setErrorCode(null);
        run.setErrorMessage(null);
        run.markUpdated();
        drRunDao.update(run.getId(), run);
        markPlanAccepted(plan, run);
        recordEvent(plan.getId(), run.getId(), DrConstants.EVENT_RUN_ACCEPTED, DrConstants.EVENT_SEVERITY_INFO,
                StringUtils.defaultIfBlank(result.getMessage(), "DR run accepted by Agent"), result.getDetailsJson());
        refreshProjection(plan.getId());
        scheduleAcceptedProjectionWatch(run, 0);
    }

    private void scheduleAcceptedProjectionWatch(final DrRunVO run, final int attempt) {
        ScheduledExecutorService executor = retryExecutor;
        if (executor == null || attempt >= ACCEPTED_PROJECTION_MAX_ATTEMPTS) {
            return;
        }
        if (!isAcceptedProjectionWatchCandidate(run)) {
            return;
        }
        final long runId = run.getId();
        executor.schedule(new ManagedContextRunnable() {
            @Override
            protected void runInContext() {
                DrRunVO watchedRun = drRunDao.findById(runId);
                if (!isAcceptedProjectionWatchCandidate(watchedRun)) {
                    return;
                }
                refreshProjection(watchedRun.getPlanId());
                DrRunVO projectedRun = drRunDao.findById(runId);
                if (isAcceptedProjectionWatchCandidate(projectedRun)) {
                    scheduleAcceptedProjectionWatch(projectedRun, attempt + 1);
                }
            }
        }, ACCEPTED_PROJECTION_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private void resumeAcceptedProjectionWatches() {
        try {
            List<DrRunVO> acceptedRuns = drRunDao.listAcceptedTestFailoverRuns();
            if (acceptedRuns == null) {
                return;
            }
            for (DrRunVO run : acceptedRuns) {
                scheduleAcceptedProjectionWatch(run, 0);
            }
        } catch (RuntimeException e) {
            LOGGER.warn("Unable to restore accepted DR test failover projection watches", e);
        }
    }

    private boolean isAcceptedProjectionWatchCandidate(DrRunVO run) {
        return run != null
                && run.getRemoved() == null
                && run.getCompleted() == null
                && StringUtils.equalsIgnoreCase(run.getRunType(), DrConstants.RUN_TYPE_TEST_FAILOVER);
    }

    private void retryRun(DrRunVO run, String errorCode, String message, String detailsJson, Integer retryAfterSeconds) {
        int retryCount = run.getRetryCount() != null ? run.getRetryCount() : 0;
        if (retryCount >= MAX_RETRY_COUNT) {
            failRun(run, DrConstants.ERROR_ENGINE_BUSY_TIMEOUT,
                    StringUtils.defaultIfBlank(message, "DR engine remained busy until retry budget was exhausted"), detailsJson);
            return;
        }
        int delay = normalizeRetryAfterSeconds(retryAfterSeconds);
        Date now = new Date();
        Date nextRetryAt = new Date(now.getTime() + TimeUnit.SECONDS.toMillis(delay));
        closeOpenSteps(run, DrConstants.STEP_STATE_RETRYING, errorCode, message);
        recordStep(run.getId(), "retry-wait", STEP_ORDER_FINAL, DrConstants.STEP_STATE_RETRYING, 95, detailsJson, errorCode, message);
        run.setState(DrConstants.RUN_STATE_RETRYING);
        run.setCurrentStepName("retry-wait");
        run.setErrorCode(errorCode);
        run.setErrorMessage(message);
        run.setProjectionState("retrying");
        run.setProjectionChecked(now);
        run.setRetryable(true);
        run.setRetryCount(retryCount + 1);
        run.setRetryAfterSeconds(delay);
        run.setNextRetryAt(nextRetryAt);
        run.setLastStatusJson(detailsJson);
        if (run.getDispatchStarted() != null && run.getDispatchCompleted() == null) {
            run.setDispatchCompleted(now);
        }
        run.markUpdated();
        drRunDao.update(run.getId(), run);
        markPlanRetryable(run.getPlanId(), errorCode, message, run.isEngineAccepted());
        recordEvent(run.getPlanId(), run.getId(), DrConstants.EVENT_RUN_QUEUED, DrConstants.EVENT_SEVERITY_WARN,
                StringUtils.defaultIfBlank(message, "DR run is waiting for a retryable engine condition"), detailsJson);
        scheduleRetry(run.getId(), delay);
    }

    private int normalizeRetryAfterSeconds(Integer retryAfterSeconds) {
        if (retryAfterSeconds == null || retryAfterSeconds <= 0) {
            return DEFAULT_RETRY_AFTER_SECONDS;
        }
        return Math.min(Math.max(retryAfterSeconds, 1), 60);
    }

    private void scheduleRetry(final long runId, final int retryAfterSeconds) {
        ScheduledExecutorService executor = retryExecutor;
        if (executor == null) {
            return;
        }
        executor.schedule(new ManagedContextRunnable() {
            @Override
            protected void runInContext() {
                DrRunVO retryRun = drRunDao.findById(runId);
                if (retryRun == null || retryRun.getCompleted() != null || retryRun.getRemoved() != null
                        || !StringUtils.equals(retryRun.getState(), DrConstants.RUN_STATE_RETRYING)) {
                    return;
                }
                retryRun.setState(DrConstants.RUN_STATE_QUEUED);
                retryRun.setCurrentStepName("retry-queued");
                retryRun.setRetryable(false);
                retryRun.markUpdated();
                drRunDao.update(retryRun.getId(), retryRun);
                queueRun(retryRun);
            }
        }, retryAfterSeconds, TimeUnit.SECONDS);
    }

    private void failRun(DrRunVO run, String errorCode, String message, String detailsJson) {
        closeOpenSteps(run, DrConstants.STEP_STATE_FAILED, errorCode, message);
        recordStep(run.getId(), STEP_FINAL, STEP_ORDER_FINAL, DrConstants.STEP_STATE_FAILED, 100, detailsJson, errorCode, message);
        run.setState(DrConstants.RUN_STATE_FAILED);
        run.setCompleted(new Date());
        if (run.getDispatchStarted() != null && run.getDispatchCompleted() == null) {
            run.setDispatchCompleted(new Date());
        }
        run.setCurrentStepName("failed");
        run.setErrorCode(errorCode);
        run.setErrorMessage(message);
        run.setProjectionState("failed");
        run.setProjectionChecked(new Date());
        run.setRetryable(false);
        run.setRetryAfterSeconds(null);
        run.setNextRetryAt(null);
        run.setLastStatusJson(detailsJson);
        run.markUpdated();
        drRunDao.update(run.getId(), run);
        closeArtifactFreeFailedTestSession(run, errorCode, message);
        closeArtifactFreeFailedFailbackSession(run, errorCode, message);
        if (isPlanTerminalFailure(run)) {
            markPlanFailed(run.getPlanId(), errorCode, message);
        } else {
            markPlanActionFailed(run.getPlanId(), errorCode, message);
        }
        recordEvent(run.getPlanId(), run.getId(), DrConstants.EVENT_RUN_FAILED, DrConstants.EVENT_SEVERITY_ERROR,
                message, detailsJson);
    }

    private void cancelRun(DrRunVO run, String message) {
        closeOpenSteps(run, DrConstants.STEP_STATE_CANCELED, null, message);
        recordStep(run.getId(), STEP_FINAL, STEP_ORDER_FINAL, DrConstants.STEP_STATE_CANCELED, 100, null, null, message);
        run.setState(DrConstants.RUN_STATE_CANCELED);
        run.setCompleted(new Date());
        if (run.getDispatchStarted() != null && run.getDispatchCompleted() == null) {
            run.setDispatchCompleted(new Date());
        }
        run.setCurrentStepName("canceled");
        run.setRetryable(false);
        run.setRetryAfterSeconds(null);
        run.setNextRetryAt(null);
        run.markUpdated();
        drRunDao.update(run.getId(), run);
        closeArtifactFreeFailedTestSession(run, null, message);
        closeArtifactFreeFailedFailbackSession(run, null, message);
        recordEvent(run.getPlanId(), run.getId(), DrConstants.EVENT_RUN_CANCELED, DrConstants.EVENT_SEVERITY_WARN,
                StringUtils.defaultIfBlank(message, "DR run canceled"), null);
    }

    private void closeArtifactFreeFailedTestSession(DrRunVO run, String errorCode, String message) {
        if (!StringUtils.equals(run.getRunType(), DrConstants.RUN_TYPE_TEST_FAILOVER) || drTestSessionDao == null) {
            return;
        }
        DrTestSessionVO session = drTestSessionDao.findActiveByRunId(run.getId());
        if (!DrTestSessionState.isTerminalRunFailureWithoutArtifacts(session, run)) {
            return;
        }
        session.setState(DrTestSessionState.FAILED);
        session.setErrorCode(errorCode);
        session.setErrorMessage(message);
        session.setCleanupRequired(false);
        session.setRemoved(new Date());
        session.markUpdated();
        drTestSessionDao.update(session.getId(), session);
    }

    private void closeArtifactFreeFailedFailbackSession(DrRunVO run, String errorCode, String message) {
        if (!StringUtils.equals(run.getRunType(), DrConstants.RUN_TYPE_FAILBACK)
                || drFailbackSessionDao == null) {
            return;
        }
        DrFailbackSessionVO session = drFailbackSessionDao.findActiveByRunId(run.getId());
        if (!DrFailbackSessionState.isTerminalRunFailureWithoutEngineArtifacts(session, run)) {
            return;
        }
        session.setState(StringUtils.equals(run.getState(), DrConstants.RUN_STATE_CANCELED)
                ? "ABORTED" : "FAILED");
        session.setAcceptanceState("REJECTED");
        session.setFailurePhase("PRE_DISPATCH");
        session.setFailedComponent("cloud-dr-run-executor");
        session.setErrorCode(errorCode);
        session.setErrorMessage(message);
        session.setCompletedAt(run.getCompleted());
        session.setRemoved(new Date());
        session.markUpdated();
        drFailbackSessionDao.update(session.getId(), session);
    }

    private void recordStep(long runId, String stepName, int stepOrder, String state, Integer progress, String detailsJson,
            String errorCode, String errorMessage) {
        DrRunStepVO step = drRunStepDao.findActiveByRunIdAndStepOrder(runId, stepOrder);
        if (step == null) {
            step = new DrRunStepVO(runId, stepName, stepOrder);
        }
        step.setState(state);
        step.setProgress(progress);
        step.setDetailsJson(detailsJson);
        step.setErrorCode(errorCode);
        step.setErrorMessage(errorMessage);
        if (StringUtils.equals(DrConstants.STEP_STATE_RUNNING, state) && step.getStarted() == null) {
            step.setStarted(new Date());
        }
        if (StringUtils.equalsAny(state, DrConstants.STEP_STATE_SUCCEEDED, DrConstants.STEP_STATE_FAILED,
                DrConstants.STEP_STATE_CANCELED, DrConstants.STEP_STATE_SKIPPED)) {
            step.setCompleted(new Date());
        }
        step.markUpdated();
        if (step.getId() > 0) {
            drRunStepDao.update(step.getId(), step);
        } else {
            drRunStepDao.persist(step);
        }
    }

    private void closeOpenSteps(DrRunVO run, String terminalState, String errorCode, String message) {
        if (drRunStepDao == null || run == null) {
            return;
        }
        for (DrRunStepVO step : drRunStepDao.listActiveByRunId(run.getId())) {
            if (step.getCompleted() != null) {
                continue;
            }
            step.setState(terminalState);
            step.setProgress(100);
            step.setErrorCode(errorCode);
            step.setErrorMessage(message);
            step.setCompleted(new Date());
            step.markUpdated();
            drRunStepDao.update(step.getId(), step);
        }
    }

    private void markPlanAccepted(DrPlanVO plan, DrRunVO run) {
        if (plan == null || run == null || !StringUtils.equals(run.getRunType(), DrConstants.RUN_TYPE_SYNC)) {
            return;
        }
        DrPlanVO latestPlan = drPlanDao.findById(plan.getId());
        if (latestPlan == null || latestPlan.getRemoved() != null) {
            return;
        }
        latestPlan.setState(DrConstants.PLAN_STATE_SYNCING);
        latestPlan.setLastErrorCode(null);
        latestPlan.setLastErrorMessage(null);
        latestPlan.markUpdated();
        drPlanDao.update(latestPlan.getId(), latestPlan);
    }

    private void markPlanFailed(long planId, String errorCode, String message) {
        DrPlanVO plan = drPlanDao.findById(planId);
        if (plan == null || plan.getRemoved() != null) {
            return;
        }
        plan.setState(DrConstants.PLAN_STATE_ERROR);
        plan.setLastErrorCode(errorCode);
        plan.setLastErrorMessage(message);
        plan.markUpdated();
        drPlanDao.update(plan.getId(), plan);
    }

    private boolean isPlanTerminalFailure(DrRunVO run) {
        return run != null && !StringUtils.equalsAny(run.getRunType(),
                DrConstants.RUN_TYPE_RECOVER_SYNC,
                DrConstants.RUN_TYPE_PAUSE_SYNC,
                DrConstants.RUN_TYPE_RESUME_SYNC,
                DrConstants.RUN_TYPE_TEST_FAILOVER,
                DrConstants.RUN_TYPE_TEST_CLEANUP,
                DrConstants.RUN_TYPE_RELEASE);
    }

    private void markPlanActionFailed(long planId, String errorCode, String message) {
        DrPlanVO plan = drPlanDao.findById(planId);
        if (plan == null || plan.getRemoved() != null) {
            return;
        }
        plan.setLastErrorCode(errorCode);
        plan.setLastErrorMessage(message);
        plan.markUpdated();
        drPlanDao.update(plan.getId(), plan);
    }

    private void clearPlanActionError(long planId) {
        DrPlanVO plan = drPlanDao.findById(planId);
        if (plan == null || plan.getRemoved() != null
                || StringUtils.equals(plan.getState(), DrConstants.PLAN_STATE_ERROR)) {
            return;
        }
        plan.setLastErrorCode(null);
        plan.setLastErrorMessage(null);
        plan.markUpdated();
        drPlanDao.update(plan.getId(), plan);
    }

    private void markPlanRetryable(long planId, String errorCode, String message, boolean engineAccepted) {
        DrPlanVO plan = drPlanDao.findById(planId);
        if (plan == null || plan.getRemoved() != null) {
            return;
        }
        if (!engineAccepted && StringUtils.equals(plan.getState(), DrConstants.PLAN_STATE_SYNCING)) {
            plan.setState(plan.getTargetReadyAt() != null ? DrConstants.PLAN_STATE_READY : DrConstants.PLAN_STATE_ENABLED);
        }
        plan.setLastErrorCode(errorCode);
        plan.setLastErrorMessage(message);
        plan.markUpdated();
        drPlanDao.update(plan.getId(), plan);
    }

    private void recordEvent(Long planId, Long runId, String eventType, String severity, String message, String detailsJson) {
        DrEventVO event = new DrEventVO(eventType, severity, DrConstants.EVENT_SOURCE_CLOUD);
        event.setPlanId(planId);
        event.setRunId(runId);
        event.setMessage(message);
        event.setDetailsJson(detailsJson);
        drEventDao.persist(event);
    }

    private void refreshProjection(long planId) {
        try {
            drProjectionService.refreshPlanProjection(planId, true);
        } catch (RuntimeException e) {
            LOGGER.debug("Ignoring best-effort DR projection refresh failure for plan {}: {}", planId, e.getMessage());
        }
    }
}
