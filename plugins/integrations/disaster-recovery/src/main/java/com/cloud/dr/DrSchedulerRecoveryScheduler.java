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

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.naming.ConfigurationException;

import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.cloudstack.managed.context.ManagedContextRunnable;
import org.apache.commons.lang3.StringUtils;

import com.cloud.dr.cluster.DisasterRecoveryClusterService;
import com.cloud.dr.dao.DrPlanDao;
import com.cloud.dr.dao.DrPlanRuntimeDao;
import com.cloud.dr.dao.DrRunDao;
import com.cloud.dr.dao.DrSiteDao;
import com.cloud.dr.dao.DrSiteHealthCheckDao;
import com.cloud.utils.Pair;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.concurrency.NamedThreadFactory;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.GlobalLock;
import com.google.gson.JsonObject;

public class DrSchedulerRecoveryScheduler extends ManagerBase implements Configurable {
    private static final int GLOBAL_LOCK_TIMEOUT_SECONDS = 1;
    private static final long INITIAL_DELAY_SECONDS = 20L;

    public static final ConfigKey<Boolean> DrSchedulerRecoveryEnabled = new ConfigKey<>("Advanced", Boolean.class,
            "dr.scheduler.recovery.enabled", "true",
            "Enable automatic recovery of eligible FTCTL_DR Plan schedulers.", false);
    public static final ConfigKey<Integer> DrSchedulerRecoveryInterval = new ConfigKey<>("Advanced", Integer.class,
            "dr.scheduler.recovery.interval", "30", "DR scheduler recovery evaluation interval in seconds.", false);
    public static final ConfigKey<Integer> DrSchedulerRecoveryBatchSize = new ConfigKey<>("Advanced", Integer.class,
            "dr.scheduler.recovery.batch.size", "25", "Maximum DR scheduler recoveries evaluated per tick.", false);
    public static final ConfigKey<Integer> DrSchedulerRecoverySourceHealthyChecks = new ConfigKey<>("Advanced", Integer.class,
            "dr.scheduler.recovery.source.healthy.checks", "3",
            "Consecutive healthy source-site checks required before automatic scheduler recovery.", false);
    public static final ConfigKey<Integer> DrSchedulerRecoverySourceHealthMaxAge = new ConfigKey<>("Advanced", Integer.class,
            "dr.scheduler.recovery.source.health.max.age", "180",
            "Maximum age in seconds of source-site health evidence used for automatic scheduler recovery.", false);

    @Inject private DrPlanDao drPlanDao;
    @Inject private DrPlanRuntimeDao drPlanRuntimeDao;
    @Inject private DrRunDao drRunDao;
    @Inject private DrSiteDao drSiteDao;
    @Inject private DrSiteHealthCheckDao drSiteHealthCheckDao;
    @Inject private DrPlanService drPlanService;
    @Inject private DrRunService drRunService;
    private ScheduledExecutorService executor;

    @Override
    public boolean configure(String name, Map<String, Object> params) throws ConfigurationException {
        super.configure(name, params);
        executor = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("DrSchedulerRecovery"));
        return true;
    }

    @Override
    public boolean start() {
        int interval = Math.max(10, DrSchedulerRecoveryInterval.value());
        executor.scheduleWithFixedDelay(new RecoveryTask(), INITIAL_DELAY_SECONDS, interval, TimeUnit.SECONDS);
        logger.info(String.format("Started DR scheduler recovery controller with interval %s seconds (enabled=%s)",
                interval, DrSchedulerRecoveryEnabled.value()));
        return true;
    }

    @Override
    public boolean stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
        return true;
    }

    @Override
    public String getConfigComponentName() {
        return DrSchedulerRecoveryScheduler.class.getSimpleName();
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey<?>[] {DrSchedulerRecoveryEnabled, DrSchedulerRecoveryInterval,
                DrSchedulerRecoveryBatchSize, DrSchedulerRecoverySourceHealthyChecks,
                DrSchedulerRecoverySourceHealthMaxAge};
    }

    private final class RecoveryTask extends ManagedContextRunnable {
        @Override
        protected void runInContext() {
            if (!Boolean.TRUE.equals(DisasterRecoveryClusterService.DisasterRecoveryServiceEnabled.value())
                    || !Boolean.TRUE.equals(DrSchedulerRecoveryEnabled.value())) {
                return;
            }
            GlobalLock lock = GlobalLock.getInternLock("DrSchedulerRecoveryScheduler");
            try {
                if (lock.lock(GLOBAL_LOCK_TIMEOUT_SECONDS)) {
                    try {
                        recoverEligiblePlans();
                    } finally {
                        lock.unlock();
                    }
                }
            } catch (RuntimeException e) {
                logger.warn("Failed to run DR scheduler recovery controller", e);
            } finally {
                lock.releaseRef();
            }
        }
    }

    private void recoverEligiblePlans() {
        List<DrPlanVO> plans = drPlanDao.listActive();
        int remaining = Math.max(1, DrSchedulerRecoveryBatchSize.value());
        for (DrPlanVO plan : plans) {
            if (remaining <= 0) {
                break;
            }
            try {
                if (!hasReplicationIntent(plan) || !isSourceSiteStable(plan)) {
                    continue;
                }
                Map<String, Boolean> eligibility = drPlanService.getActionEligibility(plan.getId());
                if (!Boolean.TRUE.equals(eligibility.get("recoverSync"))) {
                    continue;
                }
                DrPlanRuntimeVO runtime = drPlanRuntimeDao.findByPlanId(plan.getId());
                DrRunVO latestRun = drRunDao.findLatestByPlanId(plan.getId());
                if (!isAutomaticRetryAllowed(runtime, latestRun)
                        || !retryDelayElapsed(latestRun, System.currentTimeMillis())) {
                    continue;
                }
                // Re-read after readiness RPCs; never resume an intent superseded by an operator.
                DrRunVO current = drRunDao.findLatestByPlanId(plan.getId());
                if ((current == null ? 0L : current.getId()) != (latestRun == null ? 0L : latestRun.getId())) {
                    continue;
                }
                if (!hasReplicationIntent(drPlanDao.findById(plan.getId()))) {
                    continue;
                }
                JsonObject request = new JsonObject();
                request.addProperty("trigger", "AUTO_CONTROLLER");
                request.addProperty("forceFullReseed", false);
                drRunService.startRun(plan.getId(), DrConstants.RUN_TYPE_RECOVER_SYNC,
                        recoveryKey(plan, runtime, latestRun),
                        null, null, request.toString());
                remaining--;
            } catch (RuntimeException e) {
                logger.warn(String.format("Failed to recover DR scheduler for plan %s", plan.getId()), e);
            }
        }
    }

    static String recoveryKey(DrPlanVO plan, DrPlanRuntimeVO runtime, DrRunVO latestRun) {
        // A terminal attempt must not consume every future recovery in this authority epoch.
        return String.format("scheduler-recovery:%s:%s:%s", plan.getUuid(),
                runtime != null ? runtime.getAuthoritySequence() : 0L,
                latestRun != null ? latestRun.getId() : 0L);
    }

    static boolean retryDelayElapsed(DrRunVO latestRun, long now) {
        return latestRun == null
                || !StringUtils.equalsIgnoreCase(latestRun.getRunType(), DrConstants.RUN_TYPE_RECOVER_SYNC)
                || latestRun.getCompleted() != null && now - latestRun.getCompleted().getTime() >= 60_000L;
    }

    private boolean hasReplicationIntent(DrPlanVO plan) {
        return plan != null && plan.getRemoved() == null
                && !StringUtils.equalsIgnoreCase(plan.getAdminState(), "DISABLED")
                && !StringUtils.equalsIgnoreCase(plan.getActiveSide(), "TARGET")
                && StringUtils.equalsAnyIgnoreCase(plan.getState(), "READY", "SYNCING", "ERROR", "DEGRADED");
    }

    private boolean isAutomaticRetryAllowed(DrPlanRuntimeVO runtime, DrRunVO latestRun) {
        if (latestRun != null && (latestRun.getCompleted() == null
                || StringUtils.equalsAnyIgnoreCase(latestRun.getRunType(), "PAUSE_SYNC", "RELEASE")
                || !StringUtils.equalsIgnoreCase(latestRun.getState(), "SUCCEEDED")
                    && StringUtils.equalsAnyIgnoreCase(latestRun.getRunType(),
                        "FAILOVER", "FAILBACK", "REPROTECT", "TEST_FAILOVER", "TEST_CLEANUP"))) {
            return false;
        }
        if (runtime != null && StringUtils.equalsAnyIgnoreCase(runtime.getSchedulerDesiredState(), "PAUSED", "STOPPED")) {
            return false;
        }
        if (latestRun != null
                && StringUtils.equalsIgnoreCase(latestRun.getRunType(), DrConstants.RUN_TYPE_SYNC)
                && StringUtils.equalsIgnoreCase(latestRun.getState(), DrConstants.RUN_STATE_CANCELED)) {
            return false;
        }
        if (runtime == null) {
            return true;
        }
        if (StringUtils.equalsIgnoreCase(runtime.getSchedulerRecoveryState(), "REQUIRED")
                && StringUtils.equalsIgnoreCase(runtime.getReseedReason(), "OPERATOR_CANCELED_TRANSFER")) {
            return false;
        }
        String errorCode = StringUtils.upperCase(StringUtils.defaultString(runtime.getErrorCode()));
        String recoveryErrorCode = StringUtils.upperCase(
                StringUtils.defaultString(runtime.getSchedulerRecoveryErrorCode()));
        String schedulerHealth = StringUtils.upperCase(
                StringUtils.defaultString(runtime.getSchedulerHealthState()));
        String replicationActivity = StringUtils.upperCase(
                StringUtils.defaultString(runtime.getReplicationActivityState()));
        if (StringUtils.equalsAny(schedulerHealth, "RECOVERING_BASELINE")
                || StringUtils.equalsAny(replicationActivity, "RESEEDING")) {
            return false;
        }
        if (StringUtils.startsWith(errorCode, "DR_CBT_")
                || StringUtils.startsWith(recoveryErrorCode, "DR_CBT_")) {
            return false;
        }
        if (StringUtils.equalsIgnoreCase(runtime.getSchedulerRecoveryState(), DrConstants.SCHEDULER_RECOVERY_FAILED)) {
            return StringUtils.equalsAny(errorCode, "DR_SOURCE_SITE_UNAVAILABLE", "DR_VMWARE_VDDK_CONNECT_INVALID",
                    "DR_TARGET_EXPORT_UNAVAILABLE", "DR_EXPORT_OWNERSHIP_PENDING", "DR_QCOW2_SOURCE_RUNTIME_UNAVAILABLE",
                    "DR_QCOW2_OFFLINE_SOURCE_BUSY", DrConstants.ERROR_AGENT_UNAVAILABLE,
                    DrConstants.ERROR_AGENT_DISPATCH_TIMEOUT, DrConstants.ERROR_ENGINE_UNAVAILABLE)
                    || StringUtils.equalsAny(recoveryErrorCode,
                            "DR_SOURCE_SITE_UNAVAILABLE", "DR_VMWARE_VDDK_CONNECT_INVALID",
                            "DR_TARGET_EXPORT_UNAVAILABLE", "DR_EXPORT_OWNERSHIP_PENDING", "DR_QCOW2_SOURCE_RUNTIME_UNAVAILABLE",
                            "DR_QCOW2_OFFLINE_SOURCE_BUSY", DrConstants.ERROR_AGENT_UNAVAILABLE,
                            DrConstants.ERROR_AGENT_DISPATCH_TIMEOUT, DrConstants.ERROR_ENGINE_UNAVAILABLE);
        }
        return true;
    }

    private boolean isSourceSiteStable(DrPlanVO plan) {
        DrSiteVO sourceSite = drSiteDao.findById(plan.getSourceSiteId());
        if (sourceSite == null || sourceSite.getRemoved() != null
                || !StringUtils.equalsIgnoreCase(sourceSite.getHealthState(), DrConstants.HEALTH_CONNECTED)
                || sourceSite.getLastChecked() == null) {
            return false;
        }
        long maxAgeMillis = Math.max(30, DrSchedulerRecoverySourceHealthMaxAge.value()) * 1000L;
        if (System.currentTimeMillis() - sourceSite.getLastChecked().getTime() > maxAgeMillis) {
            return false;
        }
        int requiredChecks = Math.max(1, DrSchedulerRecoverySourceHealthyChecks.value());
        Filter filter = new Filter(DrSiteHealthCheckVO.class, "checkedAt", false, 0L, (long) requiredChecks);
        Pair<List<DrSiteHealthCheckVO>, Integer> result = drSiteHealthCheckDao.searchBySite(
                sourceSite.getId(), null, null, null, null, filter);
        List<DrSiteHealthCheckVO> checks = result != null ? result.first() : null;
        if (checks == null || checks.size() < requiredChecks) {
            return false;
        }
        for (DrSiteHealthCheckVO check : checks) {
            if (check == null || check.getCheckedAt() == null
                    || !StringUtils.equalsIgnoreCase(check.getHealthState(), DrConstants.HEALTH_CONNECTED)) {
                return false;
            }
        }
        return true;
    }
}
