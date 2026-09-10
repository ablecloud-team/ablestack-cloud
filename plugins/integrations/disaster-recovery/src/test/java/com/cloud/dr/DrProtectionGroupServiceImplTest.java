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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.dr.dao.DrGroupRunDao;
import com.cloud.dr.dao.DrPlanDao;
import com.cloud.dr.dao.DrRunDao;
import com.cloud.dr.dao.DrSyncCycleDao;
import com.cloud.agent.AgentManager;
import com.cloud.agent.api.FtctlDrStatusAnswer;
import com.cloud.agent.api.FtctlDrStatusCommand;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

@RunWith(MockitoJUnitRunner.class)
public class DrProtectionGroupServiceImplTest {
    @Mock private DrPlanDao drPlanDao;
    @Mock private DrRunDao drRunDao;
    @Mock private DrRunService drRunService;
    @Mock private DrPlanService drPlanService;
    @Mock private DrProjectionService drProjectionService;
    @Mock private DrGroupRunDao drGroupRunDao;
    @Mock private DrSyncCycleDao drSyncCycleDao;
    @Mock private DrAdmissionController drAdmissionController;
    @Mock private AgentManager agentManager;
    @Mock private DrWorkerPlacementService drWorkerPlacementService;

    @InjectMocks private DrProtectionGroupServiceImpl service;

    private DrPlanVO disabledPlan;
    private DrPlanVO readyPlan;

    @Before
    public void setUp() {
        disabledPlan = plan(39L, "ubuntu-plan", DrConstants.PLAN_STATE_UNPROTECTED,
                DrConstants.ADMIN_STATE_DISABLED);
        readyPlan = plan(37L, "rocky-plan", DrConstants.PLAN_STATE_READY,
                DrConstants.ADMIN_STATE_ENABLED);
        when(drPlanDao.findById(39L)).thenReturn(disabledPlan);
        when(drPlanDao.findById(37L)).thenReturn(readyPlan);
        when(drPlanService.getActionAvailability(39L)).thenReturn(availability(false,
                "DR_ACTION_PLAN_DISABLED"));
        when(drPlanService.getActionAvailability(37L)).thenReturn(availability(true, null));
        when(agentManager.easySend(anyLong(), any(FtctlDrStatusCommand.class)))
                .thenAnswer(invocation -> nbdStatus(invocation.getArgument(1), true, true, 16));
        when(drWorkerPlacementService.resolveWorkerHostId(any(DrPlanVO.class),
                org.mockito.ArgumentMatchers.eq(DrWorkerRole.COORDINATOR))).thenReturn(103L);
    }

    @Test
    public void previewExplainsEveryPlanAndBlocksMixedEligibilityWithoutMutation() {
        DrProtectionGroupPreflight result = service.previewGroupRun(Arrays.asList(39L, 37L),
                DrConstants.RUN_TYPE_SYNC, false);

        Assert.assertFalse(result.isReady());
        Assert.assertEquals(2, result.getPlans().size());
        Assert.assertFalse(result.getPlans().get(0).isEligible());
        Assert.assertEquals("DR_ACTION_PLAN_DISABLED", result.getPlans().get(0).getReasonCode());
        Assert.assertTrue(result.getPlans().get(1).isEligible());
        verify(drPlanDao, never()).update(anyLong(), any(DrPlanVO.class));
        verify(drGroupRunDao, never()).persist(any(DrGroupRunVO.class));
        verify(drRunService, never()).startRun(anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    public void startPersistsTerminalBlockedAndSkippedEvidenceWithoutDispatchingChildren() {
        String groupUuid = "group-1";
        assignGroup(disabledPlan, groupUuid, 0);
        assignGroup(readyPlan, groupUuid, 1);
        AtomicReference<DrGroupRunVO> persisted = new AtomicReference<>();
        when(drGroupRunDao.persist(any(DrGroupRunVO.class))).thenAnswer(invocation -> {
            DrGroupRunVO run = invocation.getArgument(0);
            ReflectionTestUtils.setField(run, "id", 101L);
            persisted.set(run);
            return run;
        });
        when(drGroupRunDao.findById(101L)).thenAnswer(invocation -> persisted.get());

        DrGroupRunVO result = service.startGroupRun(Arrays.asList(39L, 37L),
                DrConstants.RUN_TYPE_SYNC, 2, false, true, 2L);

        Assert.assertEquals("FAILED", result.getState());
        Assert.assertNotNull(result.getCompleted());
        Assert.assertEquals(1, result.getFailedCount());
        Assert.assertTrue(result.getProgressJson().contains("\"state\":\"BLOCKED\""));
        Assert.assertTrue(result.getProgressJson().contains("DR_ACTION_PLAN_DISABLED"));
        Assert.assertTrue(result.getProgressJson().contains("\"state\":\"SKIPPED\""));
        Assert.assertTrue(result.getProgressJson().contains("DR_GROUP_ATOMIC_PREFLIGHT_FAILED"));
        verify(drRunService, never()).startRun(anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    public void previewBlocksGroupWhenReservedNbdRangeIsNotInstalled() {
        when(agentManager.easySend(anyLong(), any(FtctlDrStatusCommand.class)))
                .thenAnswer(invocation -> nbdStatus(invocation.getArgument(1), false, false, 0));

        DrProtectionGroupPreflight result = service.previewGroupRun(Collections.singletonList(37L),
                DrConstants.RUN_TYPE_SYNC, false);

        Assert.assertFalse(result.isReady());
        Assert.assertEquals("DR_NBD_CAPACITY_INVALID", result.getPlans().get(0).getReasonCode());
        Assert.assertEquals("16", result.getPlans().get(0).getReasonArgs().get("deviceStart"));
        Assert.assertEquals("0", result.getPlans().get(0).getReasonArgs().get("presentDeviceCount"));
    }

    @Test
    public void previewBlocksGroupWhenReservedNbdRangeIsTemporarilyBusy() {
        when(agentManager.easySend(anyLong(), any(FtctlDrStatusCommand.class)))
                .thenAnswer(invocation -> nbdStatus(invocation.getArgument(1), true, false, 0));

        DrProtectionGroupPreflight result = service.previewGroupRun(Collections.singletonList(37L),
                DrConstants.RUN_TYPE_SYNC, false);

        Assert.assertFalse(result.isReady());
        Assert.assertEquals("DR_RESOURCE_BUSY", result.getPlans().get(0).getReasonCode());
    }

    @Test
    public void targetExportOutageIsReportedAsContinuousProtectionResourceWait() {
        readyPlan.setLastErrorCode("DR_TARGET_EXPORT_UNAVAILABLE");

        String state = ReflectionTestUtils.invokeMethod(service, "continuousProtectionState", readyPlan);

        Assert.assertEquals("WAITING_RESOURCE", state);
    }

    @Test
    public void successfulGroupCompletionTerminalizesCycleAliasesInSameTransaction() {
        DrGroupRunVO groupRun = new DrGroupRunVO("group-1", "group one", DrConstants.RUN_TYPE_SYNC,
                "[37]", 1, false, 1);
        ReflectionTestUtils.setField(groupRun, "id", 201L);
        DrSyncCycleVO completed = new DrSyncCycleVO(readyPlan.getId(), "canonical-run", 42L);
        ReflectionTestUtils.setField(completed, "id", 301L);
        completed.setState("READY");
        completed.setCompleted(new Date(1000L));
        DrSyncCycleVO alias = new DrSyncCycleVO(readyPlan.getId(), "scheduler-run", 42L);
        ReflectionTestUtils.setField(alias, "id", 302L);

        when(drSyncCycleDao.findLatestCompletedByPlanId(readyPlan.getId())).thenReturn(completed);
        when(drSyncCycleDao.listIncompleteAtOrBeforeSequence(readyPlan.getId(), 42L, 100))
                .thenReturn(Collections.singletonList(alias));

        service.completeGroupRun(groupRun, Collections.singletonList(readyPlan), 1, 0);

        verify(drSyncCycleDao).terminalize(302L, "SUPERSEDED", "SUPERSEDED_BY_GROUP_DURABLE_CYCLE",
                completed.getCompleted());
        verify(drGroupRunDao).update(201L, groupRun);
        Assert.assertEquals("SUCCEEDED", groupRun.getState());
        Assert.assertEquals(1, groupRun.getSucceededCount());
        Assert.assertEquals(0, groupRun.getFailedCount());
        Assert.assertNotNull(groupRun.getCompleted());
    }

    @Test
    public void groupMonitorKeepsNonAuthoritativeSucceededChildFinalizing() {
        DrGroupRunVO groupRun = new DrGroupRunVO("group-1", "group one", DrConstants.RUN_TYPE_SYNC,
                "[37]", 1, false, 1);
        DrRunVO running = new DrRunVO(readyPlan.getId(), DrConstants.RUN_TYPE_SYNC);
        ReflectionTestUtils.setField(running, "id", 401L);
        running.setIdempotencyKey(groupRun.getUuid() + ":" + readyPlan.getId());
        running.setState(DrConstants.RUN_STATE_RUNNING);
        running.setRequestJson("{\"mode\":\"FULL_RESEED\",\"forceFullReseed\":true}");
        DrRunVO succeeded = new DrRunVO(readyPlan.getId(), DrConstants.RUN_TYPE_SYNC);
        ReflectionTestUtils.setField(succeeded, "id", 401L);
        succeeded.setIdempotencyKey(running.getIdempotencyKey());
        succeeded.setState(DrConstants.RUN_STATE_SUCCEEDED);
        succeeded.setRequestJson(running.getRequestJson());

        when(drRunDao.listByPlanId(readyPlan.getId())).thenReturn(Collections.singletonList(succeeded));

        DrRunVO result = service.reconcileGroupChildTerminal(readyPlan, groupRun, running);

        verify(drProjectionService).refreshPlanProjection(readyPlan.getId(), true);
        Assert.assertEquals(DrConstants.RUN_STATE_SUCCEEDED, result.getState());
        Assert.assertFalse(service.isGroupChildTerminalSuccess(result));
        verify(drAdmissionController).renew(running.getId());
    }

    @Test
    public void fullReseedChildRequiresMatchingAuthoritativeDurableCycle() {
        DrRunVO child = new DrRunVO(readyPlan.getId(), DrConstants.RUN_TYPE_SYNC);
        child.setState(DrConstants.RUN_STATE_SUCCEEDED);
        child.setRequestJson("{\"mode\":\"FULL_RESEED\",\"forceFullReseed\":true}");
        child.setAcceptedCycleSequence(42L);
        child.setAcceptedCycleToken("cycle-42");
        child.setTerminalAuthoritative(true);
        DrSyncCycleVO cycle = new DrSyncCycleVO(readyPlan.getId(), "engine-run", 42L);
        cycle.setCycleToken("different-cycle");
        cycle.setRequestedMode("FULL_SEED");
        cycle.setState("READY");
        cycle.setCommitState("LOCAL_DURABLE");
        cycle.setCompleted(new Date());
        when(drSyncCycleDao.findByPlanSequence(readyPlan.getId(), 42L)).thenReturn(cycle);

        Assert.assertFalse(service.isGroupChildTerminalSuccess(child));

        cycle.setCycleToken("cycle-42");
        Assert.assertTrue(service.isGroupChildTerminalSuccess(child));
    }

    @Test
    public void progressReportsResultFinalizingUntilFullReseedTerminalIsAuthoritative() {
        DrRunVO child = new DrRunVO(readyPlan.getId(), DrConstants.RUN_TYPE_SYNC);
        child.setState(DrConstants.RUN_STATE_SUCCEEDED);
        child.setRequestJson("{\"mode\":\"FULL_RESEED\",\"forceFullReseed\":true}");

        JsonObject progress = ReflectionTestUtils.invokeMethod(service, "progressEntry", readyPlan, child,
                "RESULT_FINALIZING", null);

        Assert.assertEquals("RESULT_FINALIZING", progress.get("state").getAsString());
        Assert.assertEquals("RESULT_FINALIZING", progress.get("terminalizationState").getAsString());
    }

    @Test
    public void restartedRunningGroupContinuesWithoutRepeatingAdmissionPreflight() {
        DrGroupRunVO running = new DrGroupRunVO("group-1", "group one", DrConstants.RUN_TYPE_SYNC,
                "[37]", 1, false, 1);
        running.setState("RUNNING");
        DrGroupRunVO queued = new DrGroupRunVO("group-1", "group one", DrConstants.RUN_TYPE_SYNC,
                "[37]", 1, false, 1);

        Assert.assertFalse(service.requiresExecutionPreflight(running));
        Assert.assertTrue(service.requiresExecutionPreflight(queued));
    }

    @Test
    public void terminalChildReleasesLeaseAndUpdatesGroupCountInOneConvergenceTransaction() {
        DrGroupRunVO groupRun = new DrGroupRunVO("group-1", "group one", DrConstants.RUN_TYPE_SYNC,
                "[37]", 1, false, 1);
        ReflectionTestUtils.setField(groupRun, "id", 501L);
        DrRunVO child = new DrRunVO(readyPlan.getId(), DrConstants.RUN_TYPE_SYNC);
        ReflectionTestUtils.setField(child, "id", 502L);
        child.setIdempotencyKey(groupRun.getUuid() + ":" + readyPlan.getId());
        child.setState(DrConstants.RUN_STATE_SUCCEEDED);
        child.setRequestJson("{\"mode\":\"FULL_RESEED\",\"forceFullReseed\":true}");
        child.setAcceptedCycleSequence(42L);
        child.setAcceptedCycleToken(readyPlan.getUuid() + ":42");
        child.setTerminalAuthoritative(true);
        DrSyncCycleVO cycle = new DrSyncCycleVO(readyPlan.getId(), child.getUuid(), 42L);
        cycle.setCycleToken(readyPlan.getUuid() + ":42");
        cycle.setRequestedMode("FULL_SEED");
        cycle.setState("READY");
        cycle.setCommitState("LOCAL_DURABLE");
        cycle.setCompleted(new Date());

        when(drRunDao.findById(child.getId())).thenReturn(child);
        when(drSyncCycleDao.findByPlanSequence(readyPlan.getId(), 42L)).thenReturn(cycle);
        when(drGroupRunDao.findById(groupRun.getId())).thenReturn(groupRun);
        when(drRunDao.listByPlanId(readyPlan.getId())).thenReturn(Collections.singletonList(child));

        DrRunVO result = service.convergeGroupChildTerminal(groupRun, child);

        Assert.assertEquals(DrConstants.RUN_STATE_SUCCEEDED, result.getState());
        Assert.assertEquals(1, groupRun.getSucceededCount());
        verify(drAdmissionController).release(child.getId());
        verify(drGroupRunDao).update(groupRun.getId(), groupRun);
    }

    @Test
    public void durableAcceptedCyclePromotesSucceededChildBeforeGroupCompletion() {
        DrGroupRunVO groupRun = new DrGroupRunVO("group-1", "group one", DrConstants.RUN_TYPE_SYNC,
                "[37]", 1, false, 1);
        ReflectionTestUtils.setField(groupRun, "id", 551L);
        DrRunVO child = new DrRunVO(readyPlan.getId(), DrConstants.RUN_TYPE_SYNC);
        ReflectionTestUtils.setField(child, "id", 552L);
        child.setIdempotencyKey(groupRun.getUuid() + ":" + readyPlan.getId());
        child.setState(DrConstants.RUN_STATE_SUCCEEDED);
        child.setRequestJson("{\"mode\":\"FULL_RESEED\",\"forceFullReseed\":true}");
        child.setAcceptedCycleSequence(43L);
        child.setAcceptedCycleToken("cycle-43");
        DrSyncCycleVO cycle = new DrSyncCycleVO(readyPlan.getId(), child.getUuid(), 43L);
        cycle.setCycleToken("cycle-43");
        cycle.setRequestedMode("FULL_SEED");
        cycle.setState("READY");
        cycle.setCommitState("LOCAL_DURABLE");
        cycle.setCompleted(new Date());

        when(drRunDao.findById(child.getId())).thenReturn(child);
        when(drSyncCycleDao.findByPlanSequence(readyPlan.getId(), 43L)).thenReturn(cycle);
        when(drGroupRunDao.findById(groupRun.getId())).thenReturn(groupRun);
        when(drRunDao.listByPlanId(readyPlan.getId())).thenReturn(Collections.singletonList(child));

        DrRunVO result = service.convergeGroupChildTerminal(groupRun, child);

        Assert.assertTrue(result.isTerminalAuthoritative());
        Assert.assertEquals("CYCLE_DURABLE", result.getTerminalSource());
        Assert.assertEquals(1, groupRun.getSucceededCount());
        verify(drRunDao).update(child.getId(), child);
        verify(drAdmissionController).release(child.getId());
        verify(drGroupRunDao).update(groupRun.getId(), groupRun);
    }

    @Test
    public void durableSucceededChildConvergesFromDbWithoutRemoteProjectionRefresh() {
        DrGroupRunVO groupRun = new DrGroupRunVO("group-1", "group one", DrConstants.RUN_TYPE_SYNC,
                "[37]", 1, false, 1);
        ReflectionTestUtils.setField(groupRun, "id", 561L);
        DrRunVO child = new DrRunVO(readyPlan.getId(), DrConstants.RUN_TYPE_SYNC);
        ReflectionTestUtils.setField(child, "id", 562L);
        child.setIdempotencyKey(groupRun.getUuid() + ":" + readyPlan.getId());
        child.setState(DrConstants.RUN_STATE_SUCCEEDED);
        child.setRequestJson("{\"mode\":\"FULL_RESEED\",\"forceFullReseed\":true}");
        child.setAcceptedCycleSequence(44L);
        child.setAcceptedCycleToken("cycle-44");
        DrSyncCycleVO cycle = new DrSyncCycleVO(readyPlan.getId(), child.getUuid(), 44L);
        cycle.setCycleToken("cycle-44");
        cycle.setRequestedMode("FULL_RESEED");
        cycle.setState("READY");
        cycle.setCommitState("LOCAL_DURABLE");
        cycle.setCompleted(new Date());

        when(drRunDao.findById(child.getId())).thenReturn(child);
        when(drSyncCycleDao.findByPlanSequence(readyPlan.getId(), 44L)).thenReturn(cycle);
        when(drGroupRunDao.findById(groupRun.getId())).thenReturn(groupRun);
        when(drRunDao.listByPlanId(readyPlan.getId())).thenReturn(Collections.singletonList(child));

        DrRunVO result = service.reconcileGroupChildTerminal(readyPlan, groupRun, child);

        Assert.assertTrue(result.isTerminalAuthoritative());
        Assert.assertEquals("CYCLE_DURABLE", result.getTerminalSource());
        verify(drProjectionService, never()).refreshPlanProjection(anyLong(),
                org.mockito.ArgumentMatchers.anyBoolean());
        verify(drAdmissionController).release(child.getId());
    }

    @Test
    public void groupProgressAggregatesTerminalConsistencyStates() {
        DrGroupRunVO groupRun = new DrGroupRunVO("group-1", "group one", DrConstants.RUN_TYPE_SYNC,
                "[37,39]", 2, false, 2);
        ReflectionTestUtils.setField(groupRun, "id", 601L);
        JsonArray progress = new JsonArray();
        JsonObject finalizing = new JsonObject();
        finalizing.addProperty("dataTransferCompleted", true);
        finalizing.addProperty("terminalizationState", "RESULT_FINALIZING");
        progress.add(finalizing);
        JsonObject warning = new JsonObject();
        warning.addProperty("dataTransferCompleted", true);
        warning.addProperty("terminalizationState", "CONSISTENCY_WARNING");
        progress.add(warning);

        ReflectionTestUtils.invokeMethod(service, "updateProgress", groupRun, progress, 0, 0);

        Assert.assertTrue(groupRun.getProgressJson().contains("\"dataTransferCompletedCount\":2"));
        Assert.assertTrue(groupRun.getProgressJson().contains("\"resultFinalizingCount\":1"));
        Assert.assertTrue(groupRun.getProgressJson().contains("\"consistencyWarningCount\":1"));
        Assert.assertTrue(groupRun.getProgressJson().contains("\"resultVerificationState\":\"CONSISTENCY_WARNING\""));
        verify(drGroupRunDao).update(groupRun.getId(), groupRun);
    }

    private DrPlanVO plan(long id, String name, String state, String adminState) {
        DrPlanVO plan = new DrPlanVO(name, 1L, 2L, DrConstants.DIRECTION_VMWARE_TO_KVM);
        ReflectionTestUtils.setField(plan, "id", id);
        plan.setState(state);
        plan.setAdminState(adminState);
        plan.setCoordinatorWorkerHostId(1L);
        plan.setRpoSeconds(300);
        return plan;
    }

    private void assignGroup(DrPlanVO plan, String groupUuid, int order) {
        plan.setProtectionGroupUuid(groupUuid);
        plan.setProtectionGroupName("mixed group");
        plan.setProtectionGroupOrder(order);
        plan.setProtectionGroupMaxParallel(2);
        plan.setProtectionGroupQuiesceRequired(false);
    }

    private Map<String, DrActionAvailability> availability(boolean enabled, String reasonCode) {
        Map<String, DrActionAvailability> result = new LinkedHashMap<>();
        result.put("sync", new DrActionAvailability(true, enabled, reasonCode, Collections.emptyMap()));
        return result;
    }

    private FtctlDrStatusAnswer nbdStatus(FtctlDrStatusCommand command, boolean configured, boolean ready,
            int freeDeviceCount) {
        String json = String.format("{\"nbd_capacity\":{\"configured\":%s,\"ready\":%s,"
                        + "\"deviceStart\":16,\"deviceEnd\":31,\"moduleMaxDevices\":32,"
                        + "\"expectedDeviceCount\":16,\"presentDeviceCount\":%d,"
                        + "\"freeDeviceCount\":%d,\"quarantinedDeviceCount\":0}}",
                configured, ready, configured ? 16 : 0, freeDeviceCount);
        return new FtctlDrStatusAnswer(command, true, "ok", command.getPlanUuid(), null,
                "ok", "READY", "idle", 100, null, null, 0, 0L, null, 0, "", json);
    }
}
