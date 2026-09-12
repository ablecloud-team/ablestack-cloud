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

import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.dr.DrConstants;
import com.cloud.dr.DrEventVO;
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
import com.cloud.dr.adapter.DrReplicationEngine;
import com.cloud.dr.dao.DrEventDao;
import com.cloud.dr.dao.DrFailbackSessionDao;
import com.cloud.dr.dao.DrPlanDao;
import com.cloud.dr.dao.DrRunDao;
import com.cloud.dr.dao.DrRunStepDao;
import com.cloud.dr.dao.DrTestSessionDao;

@RunWith(MockitoJUnitRunner.class)
public class DrRunExecutorImplTest {
    @Mock private com.cloud.dr.DrTestCleanupRecoveryStore testCleanupRecovery;
    @Mock
    private DrPlanDao drPlanDao;
    @Mock
    private DrRunDao drRunDao;
    @Mock
    private DrRunStepDao drRunStepDao;
    @Mock
    private DrEventDao drEventDao;
    @Mock
    private DrAdapterRegistry drAdapterRegistry;
    @Mock
    private DrProjectionService drProjectionService;
    @Mock
    private DrProtectionOrchestrator drProtectionOrchestrator;
    @Mock
    private DrReplicationEngine replicationEngine;
    @Mock
    private DrTargetMaterializationService drTargetMaterializationService;
    @Mock
    private DrTestSessionDao drTestSessionDao;
    @Mock
    private DrFailbackSessionDao drFailbackSessionDao;

    @InjectMocks
    private DrRunExecutorImpl executor;

    @Test
    public void cleanupPersistsRecoveryBeforeEitherCompletionPath() {
        DrPlanVO plan = ftctlDrPlan();
        DrRunVO run = run(DrConstants.RUN_TYPE_TEST_CLEANUP);
        DrTestSessionVO session = new DrTestSessionVO(plan.getId(), 123L, DrTestSessionState.REQUESTED);
        Mockito.when(drTestSessionDao.findActiveByPlanId(plan.getId())).thenReturn(session);
        Mockito.when(testCleanupRecovery.find(123L)).thenReturn(new com.cloud.dr.DrTestCleanupRecoveryStore.Intent());
        ReflectionTestUtils.invokeMethod(executor, "armTestCleanupRecovery", plan, run);
        Mockito.verify(testCleanupRecovery).arm(plan.getId(), 123L, run.getId());
    }

    @Test
    public void successfulKvmFtctlRunRecordsTerminalStepAndRefreshesProjection() {
        DrPlanVO plan = ftctlPlan();
        DrRunVO run = run(DrConstants.RUN_TYPE_SYNC);
        Mockito.when(drRunDao.findById(run.getId())).thenReturn(run);
        Mockito.when(drPlanDao.findById(plan.getId())).thenReturn(plan);
        Mockito.when(drAdapterRegistry.getReplicationEngine(DrConstants.ENGINE_TYPE_FTCTL, DrConstants.ENGINE_BINDING_TYPE_FTCTL))
                .thenReturn(replicationEngine);
        Mockito.when(replicationEngine.validatePlan(plan)).thenReturn(DrAdapterResult.success("valid", null));
        Mockito.when(replicationEngine.execute(Mockito.any(DrExecutionContext.class)))
                .thenReturn(DrAdapterResult.success("done", "{\"ok\":true}"));

        executor.queueRun(run);

        Assert.assertEquals(DrConstants.RUN_STATE_SUCCEEDED, run.getState());
        Assert.assertEquals("completed", run.getCurrentStepName());
        Assert.assertNotNull(run.getStarted());
        Assert.assertNotNull(run.getCompleted());

        ArgumentCaptor<DrRunStepVO> stepCaptor = ArgumentCaptor.forClass(DrRunStepVO.class);
        Mockito.verify(drRunStepDao, Mockito.times(6)).persist(stepCaptor.capture());
        List<DrRunStepVO> steps = stepCaptor.getAllValues();
        Assert.assertEquals(DrConstants.STEP_STATE_RUNNING, steps.get(0).getState());
        Assert.assertEquals(Integer.valueOf(5), steps.get(0).getProgress());
        Assert.assertEquals(DrConstants.STEP_STATE_SUCCEEDED, steps.get(1).getState());
        Assert.assertEquals(Integer.valueOf(20), steps.get(1).getProgress());
        Assert.assertEquals(DrConstants.STEP_STATE_RUNNING, steps.get(2).getState());
        Assert.assertEquals(Integer.valueOf(30), steps.get(2).getProgress());
        Assert.assertEquals(DrConstants.STEP_STATE_SUCCEEDED, steps.get(3).getState());
        Assert.assertEquals(Integer.valueOf(100), steps.get(3).getProgress());
        Assert.assertEquals(DrConstants.STEP_STATE_SUCCEEDED, steps.get(4).getState());
        Assert.assertEquals(Integer.valueOf(100), steps.get(4).getProgress());
        Assert.assertEquals(DrConstants.STEP_STATE_SUCCEEDED, steps.get(5).getState());
        Assert.assertEquals(Integer.valueOf(100), steps.get(5).getProgress());

        ArgumentCaptor<DrEventVO> eventCaptor = ArgumentCaptor.forClass(DrEventVO.class);
        Mockito.verify(drEventDao, Mockito.times(2)).persist(eventCaptor.capture());
        Assert.assertEquals(DrConstants.EVENT_RUN_STARTED, eventCaptor.getAllValues().get(0).getEventType());
        Assert.assertEquals(DrConstants.EVENT_RUN_SUCCEEDED, eventCaptor.getAllValues().get(1).getEventType());
        Mockito.verify(drProjectionService).refreshPlanProjection(plan.getId(), true);
    }

    @Test
    public void acceptedFtctlDrRunRemainsAcceptedAndRefreshesProjection() {
        DrPlanVO plan = ftctlDrPlan();
        DrRunVO run = run(DrConstants.RUN_TYPE_SYNC);
        Mockito.when(drRunDao.findById(run.getId())).thenReturn(run);
        Mockito.when(drPlanDao.findById(plan.getId())).thenReturn(plan);
        Mockito.when(drProtectionOrchestrator.prepareSyncRun(plan, run)).thenReturn(plan);
        Mockito.when(drAdapterRegistry.getReplicationEngine(DrConstants.ENGINE_TYPE_FTCTL_DR, DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR))
                .thenReturn(replicationEngine);
        Mockito.when(replicationEngine.validatePlan(plan)).thenReturn(DrAdapterResult.success("valid", null));
        Mockito.when(replicationEngine.execute(Mockito.any(DrExecutionContext.class)))
                .thenReturn(DrAdapterResult.accepted("accepted by agent", "{\"accepted\":true}", "ftctl-job-1"));

        executor.queueRun(run);

        Assert.assertEquals(DrConstants.RUN_STATE_ACCEPTED, run.getState());
        Assert.assertEquals("agent-accepted", run.getCurrentStepName());
        Assert.assertEquals("ftctl-job-1", run.getExternalJobRef());
        Assert.assertNotNull(run.getStarted());
        Assert.assertNull(run.getCompleted());
        Assert.assertNull(run.getErrorCode());

        ArgumentCaptor<DrRunStepVO> stepCaptor = ArgumentCaptor.forClass(DrRunStepVO.class);
        Mockito.verify(drRunStepDao, Mockito.times(5)).persist(stepCaptor.capture());
        List<DrRunStepVO> steps = stepCaptor.getAllValues();
        Assert.assertEquals(DrConstants.STEP_STATE_RUNNING, steps.get(0).getState());
        Assert.assertEquals(DrConstants.STEP_STATE_SUCCEEDED, steps.get(1).getState());
        Assert.assertEquals(DrConstants.STEP_STATE_RUNNING, steps.get(2).getState());
        Assert.assertEquals(DrConstants.STEP_STATE_SUCCEEDED, steps.get(3).getState());
        Assert.assertEquals(DrConstants.STEP_STATE_SUCCEEDED, steps.get(4).getState());

        ArgumentCaptor<DrEventVO> eventCaptor = ArgumentCaptor.forClass(DrEventVO.class);
        Mockito.verify(drEventDao, Mockito.times(2)).persist(eventCaptor.capture());
        Assert.assertEquals(DrConstants.EVENT_RUN_STARTED, eventCaptor.getAllValues().get(0).getEventType());
        Assert.assertEquals(DrConstants.EVENT_RUN_ACCEPTED, eventCaptor.getAllValues().get(1).getEventType());
        Mockito.verify(drProtectionOrchestrator).prepareSyncRun(plan, run);
        Mockito.verify(drProjectionService).refreshPlanProjection(plan.getId(), true);
    }

    @Test
    public void acceptedTestFailoverSchedulesBoundedBackgroundProjection() {
        DrPlanVO plan = ftctlDrPlan();
        DrRunVO run = run(DrConstants.RUN_TYPE_TEST_FAILOVER);
        ScheduledExecutorService projectionScheduler = Mockito.mock(ScheduledExecutorService.class);
        ReflectionTestUtils.setField(executor, "retryExecutor", projectionScheduler);
        Mockito.when(drRunDao.findById(run.getId())).thenReturn(run);
        Mockito.when(drPlanDao.findById(plan.getId())).thenReturn(plan);
        Mockito.when(drAdapterRegistry.getReplicationEngine(DrConstants.ENGINE_TYPE_FTCTL_DR,
                DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR)).thenReturn(replicationEngine);
        Mockito.when(replicationEngine.validatePlan(plan)).thenReturn(DrAdapterResult.success("valid", null));
        Mockito.when(replicationEngine.execute(Mockito.any(DrExecutionContext.class)))
                .thenReturn(DrAdapterResult.accepted("accepted by agent", "{\"accepted\":true}", "ftctl-test-job"));

        executor.queueRun(run);

        Assert.assertEquals(DrConstants.RUN_STATE_ACCEPTED, run.getState());
        Mockito.verify(projectionScheduler).schedule(Mockito.any(Runnable.class), Mockito.eq(2L),
                Mockito.eq(TimeUnit.SECONDS));
        Mockito.verify(drRunDao, Mockito.times(1)).findById(run.getId());
    }

    @Test
    public void startupRecoveryResumesAcceptedTestFailoverProjectionWatch() {
        DrRunVO run = run(DrConstants.RUN_TYPE_TEST_FAILOVER);
        run.setState(DrConstants.RUN_STATE_ACCEPTED);
        ScheduledExecutorService projectionScheduler = Mockito.mock(ScheduledExecutorService.class);
        ReflectionTestUtils.setField(executor, "retryExecutor", projectionScheduler);
        Mockito.when(drRunDao.listAcceptedTestFailoverRuns()).thenReturn(List.of(run));

        ReflectionTestUtils.invokeMethod(executor, "resumeAcceptedProjectionWatches");

        Mockito.verify(projectionScheduler).schedule(Mockito.any(Runnable.class), Mockito.eq(2L),
                Mockito.eq(TimeUnit.SECONDS));
    }

    @Test
    public void remoteKvmSyncMaterializesPlanOwnedTargetBeforePreparingFtctlProfile() {
        DrPlanVO plan = remoteKvmFtctlDrPlan();
        DrPlanVO refreshed = remoteKvmFtctlDrPlan();
        DrRunVO run = run(DrConstants.RUN_TYPE_SYNC);
        Mockito.when(drRunDao.findById(run.getId())).thenReturn(run);
        Mockito.when(drPlanDao.findById(plan.getId())).thenReturn(plan, refreshed);
        Mockito.when(drTargetMaterializationService.prepareSyncTarget(plan.getId(), run.getId())).thenReturn(true);
        Mockito.when(drProtectionOrchestrator.prepareSyncRun(plan, run)).thenReturn(plan);
        Mockito.when(drAdapterRegistry.getReplicationEngine(DrConstants.ENGINE_TYPE_FTCTL_DR,
                DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR)).thenReturn(replicationEngine);
        Mockito.when(replicationEngine.validatePlan(refreshed)).thenReturn(DrAdapterResult.success("valid", null));
        Mockito.when(replicationEngine.execute(Mockito.any(DrExecutionContext.class)))
                .thenReturn(DrAdapterResult.accepted("accepted", "{}", "remote-job"));

        executor.queueRun(run);

        org.mockito.InOrder preparationOrder = Mockito.inOrder(drProtectionOrchestrator, drTargetMaterializationService);
        preparationOrder.verify(drProtectionOrchestrator).prepareSyncRun(plan, run);
        preparationOrder.verify(drTargetMaterializationService).prepareSyncTarget(plan.getId(), run.getId());
        Mockito.verify(replicationEngine).validatePlan(refreshed);
        Mockito.verify(replicationEngine).execute(Mockito.argThat(context -> context.getPlan() == refreshed));
        Assert.assertEquals(DrConstants.RUN_STATE_ACCEPTED, run.getState());
    }

    @Test
    public void missingKvmFtctlAdapterFailsRunWithoutCallingProjectionRefresh() {
        DrPlanVO plan = ftctlPlan();
        DrRunVO run = run(DrConstants.RUN_TYPE_FAILOVER);
        Mockito.when(drRunDao.findById(run.getId())).thenReturn(run);
        Mockito.when(drPlanDao.findById(plan.getId())).thenReturn(plan);
        Mockito.when(drAdapterRegistry.getReplicationEngine(DrConstants.ENGINE_TYPE_FTCTL, DrConstants.ENGINE_BINDING_TYPE_FTCTL))
                .thenReturn(null);

        executor.queueRun(run);

        Assert.assertEquals(DrConstants.RUN_STATE_FAILED, run.getState());
        Assert.assertEquals(DrConstants.ERROR_ENGINE_UNAVAILABLE, run.getErrorCode());
        Mockito.verify(drProjectionService, Mockito.never()).refreshPlanProjection(Mockito.anyLong(), Mockito.anyBoolean());
    }

    @Test
    public void failedTestFailoverPreservesReadyPlanState() {
        DrPlanVO plan = ftctlDrPlan();
        plan.setState(DrConstants.PLAN_STATE_READY);
        DrRunVO run = run(DrConstants.RUN_TYPE_TEST_FAILOVER);
        Mockito.when(drRunDao.findById(run.getId())).thenReturn(run);
        Mockito.when(drPlanDao.findById(plan.getId())).thenReturn(plan);
        Mockito.when(drAdapterRegistry.getReplicationEngine(DrConstants.ENGINE_TYPE_FTCTL_DR, DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR))
                .thenReturn(replicationEngine);
        Mockito.when(replicationEngine.validatePlan(plan)).thenReturn(DrAdapterResult.success("valid", null));
        Mockito.when(replicationEngine.execute(Mockito.any(DrExecutionContext.class)))
                .thenReturn(DrAdapterResult.failure("DR_SYNC_QUIESCE_TIMEOUT", "scheduler did not acknowledge pause", null));

        executor.queueRun(run);

        Assert.assertEquals(DrConstants.RUN_STATE_FAILED, run.getState());
        Assert.assertEquals(DrConstants.PLAN_STATE_READY, plan.getState());
        Assert.assertEquals("DR_SYNC_QUIESCE_TIMEOUT", plan.getLastErrorCode());
        Mockito.verify(drPlanDao).update(plan.getId(), plan);
    }

    @Test
    public void failedTestFailoverClosesArtifactFreeRequestedSession() {
        DrPlanVO plan = ftctlDrPlan();
        plan.setState(DrConstants.PLAN_STATE_READY);
        DrRunVO run = run(DrConstants.RUN_TYPE_TEST_FAILOVER);
        DrTestSessionVO session = new DrTestSessionVO(plan.getId(), run.getId(), DrTestSessionState.REQUESTED);
        Mockito.when(drRunDao.findById(run.getId())).thenReturn(run);
        Mockito.when(drPlanDao.findById(plan.getId())).thenReturn(plan);
        Mockito.when(drTestSessionDao.findActiveByRunId(run.getId())).thenReturn(session);
        Mockito.when(drAdapterRegistry.getReplicationEngine(DrConstants.ENGINE_TYPE_FTCTL_DR,
                DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR)).thenReturn(replicationEngine);
        Mockito.when(replicationEngine.validatePlan(plan)).thenReturn(DrAdapterResult.success("valid", null));
        Mockito.when(replicationEngine.execute(Mockito.any(DrExecutionContext.class)))
                .thenReturn(DrAdapterResult.failure("DR_TEST_ARTIFACT_SPEC_INVALID", "absolute path required", null));

        executor.queueRun(run);

        Assert.assertEquals(DrTestSessionState.FAILED, session.getState());
        Assert.assertEquals("DR_TEST_ARTIFACT_SPEC_INVALID", session.getErrorCode());
        Assert.assertNotNull(session.getRemoved());
        Mockito.verify(drTestSessionDao).update(session.getId(), session);
    }

    @Test
    public void failedFailbackClosesArtifactFreeRequestedSession() {
        DrPlanVO plan = ftctlDrPlan();
        plan.setState(DrConstants.PLAN_STATE_FAILED_OVER);
        plan.setActiveSide("TARGET");
        DrRunVO run = run(DrConstants.RUN_TYPE_FAILBACK);
        DrFailbackSessionVO session = new DrFailbackSessionVO(plan.getId(), run.getId(),
                plan.getUuid() + ":" + run.getUuid(), "REQUESTED");
        session.setAcceptanceState("SUBMITTED");
        session.setEngineAckState("PENDING");
        session.setCommitOutcome("PENDING");
        session.setRollbackState("NONE");
        Mockito.when(drRunDao.findById(run.getId())).thenReturn(run);
        Mockito.when(drPlanDao.findById(plan.getId())).thenReturn(plan);
        Mockito.when(drFailbackSessionDao.findActiveByRunId(run.getId())).thenReturn(session);
        Mockito.when(drAdapterRegistry.getReplicationEngine(DrConstants.ENGINE_TYPE_FTCTL_DR,
                DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR)).thenReturn(replicationEngine);
        Mockito.when(replicationEngine.validatePlan(plan)).thenReturn(DrAdapterResult.success("valid", null));
        Mockito.when(replicationEngine.execute(Mockito.any(DrExecutionContext.class)))
                .thenThrow(new IllegalStateException("Mold API returned HTTP 401"));

        executor.queueRun(run);

        Assert.assertEquals(DrConstants.RUN_STATE_FAILED, run.getState());
        Assert.assertEquals("FAILED", session.getState());
        Assert.assertEquals("REJECTED", session.getAcceptanceState());
        Assert.assertEquals("PRE_DISPATCH", session.getFailurePhase());
        Assert.assertNotNull(session.getCompletedAt());
        Assert.assertNotNull(session.getRemoved());
        Mockito.verify(drFailbackSessionDao).update(session.getId(), session);
    }

    @Test
    public void failedFailbackPreservesSessionAfterEngineAcceptance() {
        DrPlanVO plan = ftctlDrPlan();
        DrRunVO run = run(DrConstants.RUN_TYPE_FAILBACK);
        DrFailbackSessionVO session = new DrFailbackSessionVO(plan.getId(), run.getId(),
                plan.getUuid() + ":" + run.getUuid(), "ENGINE_ACCEPTED");
        session.setAcceptanceState("ACCEPTED");
        session.setEngineAckState("ACKNOWLEDGED");
        Mockito.when(drRunDao.findById(run.getId())).thenReturn(run);
        Mockito.when(drPlanDao.findById(plan.getId())).thenReturn(plan);
        Mockito.when(drFailbackSessionDao.findActiveByRunId(run.getId())).thenReturn(session);
        Mockito.when(drAdapterRegistry.getReplicationEngine(DrConstants.ENGINE_TYPE_FTCTL_DR,
                DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR)).thenReturn(replicationEngine);
        Mockito.when(replicationEngine.validatePlan(plan)).thenReturn(DrAdapterResult.success("valid", null));
        Mockito.when(replicationEngine.execute(Mockito.any(DrExecutionContext.class)))
                .thenReturn(DrAdapterResult.failure("DR_ENGINE_ACTION_FAILED", "engine rejected", null));

        executor.queueRun(run);

        Assert.assertEquals("ENGINE_ACCEPTED", session.getState());
        Assert.assertNull(session.getRemoved());
        Mockito.verify(drFailbackSessionDao, Mockito.never()).update(session.getId(), session);
    }

    @Test
    public void terminalTestCleanupClosesCloudTestSession() {
        DrPlanVO plan = ftctlDrPlan();
        plan.setLastErrorCode(DrConstants.ERROR_ENGINE_ACTION_FAILED);
        plan.setLastErrorMessage("previous cleanup attempt failed");
        DrRunVO run = run(DrConstants.RUN_TYPE_TEST_CLEANUP);
        Mockito.when(drRunDao.findById(run.getId())).thenReturn(run);
        Mockito.when(drPlanDao.findById(plan.getId())).thenReturn(plan);
        Mockito.when(drTargetMaterializationService.cleanupTestTarget(plan.getId(), run.getId())).thenReturn(true);
        Mockito.when(drAdapterRegistry.getReplicationEngine(DrConstants.ENGINE_TYPE_FTCTL_DR, DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR))
                .thenReturn(replicationEngine);
        Mockito.when(replicationEngine.validatePlan(plan)).thenReturn(DrAdapterResult.success("valid", null));
        Mockito.when(replicationEngine.execute(Mockito.any(DrExecutionContext.class)))
                .thenReturn(DrAdapterResult.success("cleaned", "{\"state\":\"CLEANED\"}"));

        executor.queueRun(run);

        Assert.assertEquals(DrConstants.RUN_STATE_SUCCEEDED, run.getState());
        Assert.assertNull(plan.getLastErrorCode());
        Assert.assertNull(plan.getLastErrorMessage());
        Mockito.verify(drTargetMaterializationService).cleanupTestTarget(plan.getId(), run.getId());
        Mockito.verify(drTargetMaterializationService).completeTestCleanup(plan.getId());
        Mockito.verify(drPlanDao).update(plan.getId(), plan);
    }

    @Test
    public void terminalReleaseProjectsAgentEvidenceBeforeBestEffortPolling() {
        DrPlanVO plan = ftctlDrPlan();
        plan.setState(DrConstants.PLAN_STATE_READY);
        plan.setAdminState(DrConstants.ADMIN_STATE_ENABLED);
        DrRunVO run = run(DrConstants.RUN_TYPE_RELEASE);
        String detailsJson = "{\"agentAnswer\":{\"state\":\"RELEASED\",\"step\":\"release-completed\"}}";
        Mockito.when(drRunDao.findById(run.getId())).thenReturn(run);
        Mockito.when(drPlanDao.findById(plan.getId())).thenReturn(plan);
        Mockito.when(drAdapterRegistry.getReplicationEngine(DrConstants.ENGINE_TYPE_FTCTL_DR,
                DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR)).thenReturn(replicationEngine);
        Mockito.when(replicationEngine.validatePlan(plan)).thenReturn(DrAdapterResult.success("valid", null));
        Mockito.when(replicationEngine.execute(Mockito.any(DrExecutionContext.class)))
                .thenReturn(DrAdapterResult.success("released", detailsJson));
        Mockito.when(drTargetMaterializationService.cleanupReleasedStandbyTarget(plan.getId(), run.getId(),
                DrConstants.RELEASE_DISPOSITION_RETAIN_OPERATIONAL_VM)).thenReturn(true);
        Mockito.when(drProjectionService.projectTerminalActionResult(plan.getId(), run, detailsJson)).thenReturn(true);

        executor.queueRun(run);

        Assert.assertEquals(DrConstants.RUN_STATE_SUCCEEDED, run.getState());
        Assert.assertEquals("terminal", run.getProjectionState());
        Assert.assertNotNull(run.getProjectionChecked());
        Mockito.verify(drProjectionService).projectTerminalActionResult(plan.getId(), run, detailsJson);
        Mockito.verify(drTargetMaterializationService).validateReleaseDisposition(plan.getId(),
                DrConstants.RELEASE_DISPOSITION_RETAIN_OPERATIONAL_VM);
        Mockito.verify(drTargetMaterializationService).cleanupReleasedStandbyTarget(plan.getId(), run.getId(),
                DrConstants.RELEASE_DISPOSITION_RETAIN_OPERATIONAL_VM);
        Mockito.verify(drProjectionService).refreshPlanProjection(plan.getId(), true);
    }

    private DrPlanVO ftctlPlan() {
        DrPlanVO plan = new DrPlanVO("kvm-ftctl-plan", 1L, 2L, "KVM_TO_KVM");
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL);
        plan.setSourceVmId(101L);
        return plan;
    }

    private DrPlanVO ftctlDrPlan() {
        DrPlanVO plan = new DrPlanVO("ftctl-dr-plan", 1L, 2L, DrConstants.DIRECTION_KVM_TO_VMWARE);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setSourceVmId(101L);
        plan.setSourceWorkerHostId(101L);
        plan.setTargetWorkerHostId(102L);
        plan.setCoordinatorWorkerHostId(103L);
        return plan;
    }

    private DrPlanVO remoteKvmFtctlDrPlan() {
        DrPlanVO plan = new DrPlanVO("remote-kvm-ftctl-dr-plan", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setSourceExternalRef("remote-source-vm");
        plan.setTargetWorkerHostId(102L);
        plan.setCoordinatorWorkerHostId(103L);
        return plan;
    }

    private DrRunVO run(String runType) {
        DrRunVO run = new DrRunVO(0L, runType);
        run.setState(DrConstants.RUN_STATE_QUEUED);
        run.setRequestJson("{\"sourceStorage\":\"rbd\",\"targetStorage\":\"qcow2\"}");
        return run;
    }
}
