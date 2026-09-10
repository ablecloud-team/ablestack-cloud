// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file distributed with this work.
package com.cloud.dr;

import java.util.Arrays;
import java.util.Date;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.dr.dao.DrSiteDao;
import com.cloud.dr.dao.DrSiteHealthCheckDao;
import com.cloud.utils.Pair;
import com.cloud.utils.db.Filter;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class DrSchedulerRecoverySchedulerTest {
    @Mock private DrSiteDao drSiteDao;
    @Mock private DrSiteHealthCheckDao drSiteHealthCheckDao;
    @Mock private com.cloud.dr.dao.DrPlanDao drPlanDao;
    @Mock private com.cloud.dr.dao.DrPlanRuntimeDao drPlanRuntimeDao;
    @Mock private com.cloud.dr.dao.DrRunDao drRunDao;
    @Mock private DrPlanService drPlanService;
    @Mock private DrRunService drRunService;
    @InjectMocks private DrSchedulerRecoveryScheduler scheduler;

    @Test
    public void acceptsLatestConsecutiveHealthyChecksOutsideFreshnessWindow() {
        DrPlanVO plan = new DrPlanVO("plan", 11L, 12L, DrConstants.DIRECTION_VMWARE_TO_KVM);
        DrSiteVO site = connectedSite(11L);
        when(drSiteDao.findById(11L)).thenReturn(site);
        when(drSiteHealthCheckDao.searchBySite(eq(11L), isNull(), isNull(), isNull(), isNull(), any(Filter.class)))
                .thenReturn(new Pair<>(Arrays.asList(connectedCheck(11L), connectedCheck(11L), connectedCheck(11L)), 3));

        Assert.assertTrue(ReflectionTestUtils.invokeMethod(scheduler, "isSourceSiteStable", plan));
        verify(drSiteHealthCheckDao).searchBySite(eq(11L), isNull(), isNull(), isNull(), isNull(), any(Filter.class));
    }

    @Test
    public void rejectsDisconnectedCheckInsideLatestConsecutiveWindow() {
        DrPlanVO plan = new DrPlanVO("plan", 11L, 12L, DrConstants.DIRECTION_VMWARE_TO_KVM);
        when(drSiteDao.findById(11L)).thenReturn(connectedSite(11L));
        DrSiteHealthCheckVO disconnected = new DrSiteHealthCheckVO(11L, "SCHEDULED", "DISCONNECTED");
        disconnected.setCheckedAt(new Date(System.currentTimeMillis() - 600_000L));
        when(drSiteHealthCheckDao.searchBySite(eq(11L), isNull(), isNull(), isNull(), isNull(), any(Filter.class)))
                .thenReturn(new Pair<>(Arrays.asList(connectedCheck(11L), connectedCheck(11L), disconnected), 3));

        Assert.assertFalse(ReflectionTestUtils.invokeMethod(scheduler, "isSourceSiteStable", plan));
    }

    @Test
    public void rejectsAutomaticRetryForCbtEpochRecoveryOwnedByFtctl() {
        DrPlanRuntimeVO runtime = new DrPlanRuntimeVO(42L);
        runtime.setSchedulerRecoveryState(DrConstants.SCHEDULER_RECOVERY_FAILED);
        runtime.setErrorCode("DR_CBT_RESEED_REQUIRED");

        Assert.assertFalse(ReflectionTestUtils.invokeMethod(scheduler, "isAutomaticRetryAllowed", runtime, null));
    }

    @Test
    public void rejectsAutomaticRetryWhileBaselineRecoveryIsRunning() {
        DrPlanRuntimeVO runtime = new DrPlanRuntimeVO(42L);
        runtime.setSchedulerHealthState("RECOVERING_BASELINE");
        runtime.setReplicationActivityState("RESEEDING");

        Assert.assertFalse(ReflectionTestUtils.invokeMethod(scheduler, "isAutomaticRetryAllowed", runtime, null));
    }

    @Test
    public void rejectsAutomaticRetryForOperatorCanceledTransfer() {
        DrPlanRuntimeVO runtime = new DrPlanRuntimeVO(42L);
        runtime.setSchedulerRecoveryState("REQUIRED");
        runtime.setReseedReason("OPERATOR_CANCELED_TRANSFER");

        Assert.assertFalse(ReflectionTestUtils.invokeMethod(scheduler, "isAutomaticRetryAllowed", runtime, null));
    }

    @Test
    public void rejectsAutomaticRetryWhenCanceledSyncIsNewerThanRuntimeProjection() {
        DrPlanRuntimeVO staleRuntime = new DrPlanRuntimeVO(42L);
        staleRuntime.setSchedulerRecoveryState("REQUIRED");
        DrRunVO canceledSync = new DrRunVO(42L, DrConstants.RUN_TYPE_SYNC);
        canceledSync.setState(DrConstants.RUN_STATE_CANCELED);

        Assert.assertFalse(ReflectionTestUtils.invokeMethod(
                scheduler, "isAutomaticRetryAllowed", staleRuntime, canceledSync));
    }

    @Test
    public void allowsSourceTransportRecoveryAfterSiteBecomesStable() {
        DrPlanRuntimeVO runtime = new DrPlanRuntimeVO(42L);
        runtime.setSchedulerRecoveryState(DrConstants.SCHEDULER_RECOVERY_FAILED);
        runtime.setErrorCode("DR_SOURCE_SITE_UNAVAILABLE");

        Assert.assertTrue(ReflectionTestUtils.invokeMethod(scheduler, "isAutomaticRetryAllowed", runtime, null));
    }

    @Test
    public void allowsTargetExportRecoveryAfterTargetAgentRestarts() {
        DrPlanRuntimeVO runtime = new DrPlanRuntimeVO(42L);
        runtime.setSchedulerRecoveryState(DrConstants.SCHEDULER_RECOVERY_FAILED);
        runtime.setErrorCode("DR_TARGET_EXPORT_UNAVAILABLE");

        Assert.assertTrue(ReflectionTestUtils.invokeMethod(scheduler, "isAutomaticRetryAllowed", runtime, null));
    }

    @Test
    public void allowsQcow2RuntimeRelocationRecovery() {
        DrPlanRuntimeVO runtime = new DrPlanRuntimeVO(42L);
        runtime.setSchedulerRecoveryState(DrConstants.SCHEDULER_RECOVERY_FAILED);
        runtime.setErrorCode("DR_QCOW2_SOURCE_RUNTIME_UNAVAILABLE");

        Assert.assertTrue(ReflectionTestUtils.invokeMethod(scheduler, "isAutomaticRetryAllowed", runtime, null));
    }

    @Test
    public void completedAttemptGetsNewKeyButSameObservationIsIdempotent() {
        DrPlanVO plan = new DrPlanVO("plan", 11L, 12L, DrConstants.DIRECTION_KVM_TO_KVM);
        DrPlanRuntimeVO runtime = new DrPlanRuntimeVO(42L);
        DrRunVO first = new DrRunVO(42L, "RECOVER_SYNC");
        ReflectionTestUtils.setField(first, "id", 100L);
        String key = DrSchedulerRecoveryScheduler.recoveryKey(plan, runtime, first);
        Assert.assertEquals(key, DrSchedulerRecoveryScheduler.recoveryKey(plan, runtime, first));
        DrRunVO next = new DrRunVO(42L, "RECOVER_SYNC");
        ReflectionTestUtils.setField(next, "id", 101L);
        Assert.assertNotEquals(key, DrSchedulerRecoveryScheduler.recoveryKey(plan, runtime, next));
    }

    @Test
    public void retryBackoffSurvivesControllerRestartAndRejectsActiveAttempt() {
        DrRunVO run = new DrRunVO(42L, "RECOVER_SYNC");
        Assert.assertFalse(DrSchedulerRecoveryScheduler.retryDelayElapsed(run, 100_000L));
        run.setCompleted(new Date(10_000L));
        Assert.assertFalse(DrSchedulerRecoveryScheduler.retryDelayElapsed(run, 69_999L));
        Assert.assertTrue(DrSchedulerRecoveryScheduler.retryDelayElapsed(run, 70_000L));
    }

    @Test
    public void latestOperatorIntentOverridesStaleRecoveryProjection() {
        DrPlanRuntimeVO runtime = new DrPlanRuntimeVO(42L);
        runtime.setSchedulerRecoveryState("REQUIRED");
        for (String type : Arrays.asList("PAUSE_SYNC", "RELEASE", "FAILOVER", "FAILBACK", "REPROTECT", "TEST_FAILOVER")) {
            DrRunVO run = new DrRunVO(42L, type);
            run.setState("FAILED");
            run.setCompleted(new Date());
            Assert.assertFalse(type, ReflectionTestUtils.invokeMethod(scheduler, "isAutomaticRetryAllowed", runtime, run));
        }
    }

    @Test
    public void completedCleanupDoesNotPreventLaterMaintenanceRecovery() {
        DrRunVO run = new DrRunVO(42L, "TEST_CLEANUP");
        run.setState("SUCCEEDED");
        run.setCompleted(new Date());
        Assert.assertTrue(ReflectionTestUtils.invokeMethod(scheduler, "isAutomaticRetryAllowed", new DrPlanRuntimeVO(42L), run));
    }

    @Test
    public void pausedRuntimeRemainsPaused() {
        DrPlanRuntimeVO runtime = new DrPlanRuntimeVO(42L);
        runtime.setSchedulerDesiredState("PAUSED");
        Assert.assertFalse(ReflectionTestUtils.invokeMethod(scheduler, "isAutomaticRetryAllowed", runtime, null));
    }

    @Test
    public void pendingOwnershipIsRetriedAfterWorkerReturns() {
        DrPlanRuntimeVO runtime = new DrPlanRuntimeVO(42L);
        runtime.setSchedulerRecoveryState("FAILED");
        runtime.setSchedulerRecoveryErrorCode("DR_EXPORT_OWNERSHIP_PENDING");
        Assert.assertTrue(ReflectionTestUtils.invokeMethod(scheduler, "isAutomaticRetryAllowed", runtime, null));
    }

    @Test
    public void controllerCreatesAnotherAttemptAfterFailedRecoveryInSameAuthority() {
        DrPlanVO plan = new DrPlanVO("plan", 11L, 12L, DrConstants.DIRECTION_KVM_TO_KVM);
        ReflectionTestUtils.setField(plan, "id", 42L);
        plan.setState("ERROR");
        plan.setAdminState("ENABLED");
        plan.setActiveSide("SOURCE");
        DrPlanRuntimeVO runtime = new DrPlanRuntimeVO(42L);
        runtime.setErrorCode("DR_TARGET_EXPORT_UNAVAILABLE");
        runtime.setSchedulerRecoveryState("FAILED");
        DrRunVO previous = new DrRunVO(42L, "RECOVER_SYNC");
        ReflectionTestUtils.setField(previous, "id", 100L);
        previous.setState("FAILED");
        previous.setCompleted(new Date(System.currentTimeMillis() - 120_000L));
        when(drPlanDao.listActive()).thenReturn(Arrays.asList(plan));
        when(drPlanDao.findById(42L)).thenReturn(plan);
        when(drPlanRuntimeDao.findByPlanId(42L)).thenReturn(runtime);
        when(drRunDao.findLatestByPlanId(42L)).thenReturn(previous);
        when(drPlanService.getActionEligibility(42L)).thenReturn(java.util.Collections.singletonMap("recoverSync", true));
        when(drSiteDao.findById(11L)).thenReturn(connectedSite(11L));
        when(drSiteHealthCheckDao.searchBySite(eq(11L), isNull(), isNull(), isNull(), isNull(), any(Filter.class)))
                .thenReturn(new Pair<>(Arrays.asList(connectedCheck(11L), connectedCheck(11L), connectedCheck(11L)), 3));
        ReflectionTestUtils.invokeMethod(scheduler, "recoverEligiblePlans");
        verify(drRunService).startRun(eq(42L), eq("RECOVER_SYNC"),
                eq(DrSchedulerRecoveryScheduler.recoveryKey(plan, runtime, previous)), isNull(), isNull(), any(String.class));
        ReflectionTestUtils.setField(previous, "id", 101L);
        ReflectionTestUtils.invokeMethod(scheduler, "recoverEligiblePlans");
        verify(drRunService).startRun(eq(42L), eq("RECOVER_SYNC"),
                eq(DrSchedulerRecoveryScheduler.recoveryKey(plan, runtime, previous)), isNull(), isNull(), any(String.class));
    }

    @Test
    public void unavailableAgentAndDispatchTimeoutRemainAutomaticallyRetryable() {
        for (String code : Arrays.asList(DrConstants.ERROR_AGENT_UNAVAILABLE,
                DrConstants.ERROR_AGENT_DISPATCH_TIMEOUT, DrConstants.ERROR_ENGINE_UNAVAILABLE)) {
            DrPlanRuntimeVO runtime = new DrPlanRuntimeVO(42L);
            runtime.setSchedulerRecoveryState("FAILED");
            runtime.setSchedulerRecoveryErrorCode(code);
            Assert.assertTrue(code, ReflectionTestUtils.invokeMethod(scheduler, "isAutomaticRetryAllowed", runtime, null));
        }
    }
    private DrSiteVO connectedSite(long id) {
        DrSiteVO site = new DrSiteVO("source", "VMWARE_DIRECT", "VMWARE");
        ReflectionTestUtils.setField(site, "id", id);
        site.setHealthState(DrConstants.HEALTH_CONNECTED);
        site.setLastChecked(new Date());
        return site;
    }

    private DrSiteHealthCheckVO connectedCheck(long siteId) {
        DrSiteHealthCheckVO check = new DrSiteHealthCheckVO(siteId, "SCHEDULED", DrConstants.HEALTH_CONNECTED);
        check.setCheckedAt(new Date(System.currentTimeMillis() - 600_000L));
        return check;
    }
}
