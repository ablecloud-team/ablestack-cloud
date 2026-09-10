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

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.FtctlDrCancelAnswer;
import com.cloud.agent.api.FtctlDrCancelCommand;
import com.cloud.dr.adapter.ftctl.DrRemoteAgentClient;
import com.cloud.dr.dao.DrPlanDao;
import com.cloud.dr.dao.DrRunDao;
import com.cloud.dr.dao.DrRunStepDao;
import com.cloud.dr.orchestrator.DrOrchestrator;

@RunWith(MockitoJUnitRunner.class)
public class DrRunServiceImplTest {
    @Mock private DrRunDao drRunDao;
    @Mock private DrRunStepDao drRunStepDao;
    @Mock private DrOrchestrator drOrchestrator;
    @Mock private DrPlanDao drPlanDao;
    @Mock private AgentManager agentManager;
    @Mock private DrFailbackLifecycleService drFailbackLifecycleService;
    @Mock private DrProjectionService drProjectionService;
    @Mock private DrRemoteAgentClient drRemoteAgentClient;
    @Mock private DrWorkerPlacementService drWorkerPlacementService;
    @InjectMocks private DrRunServiceImpl service;

    @org.junit.Before
    public void selectCoordinatorAutomatically() {
        Mockito.lenient().when(drWorkerPlacementService.resolveWorkerHostId(
                Mockito.any(DrPlanVO.class), Mockito.nullable(DrRunVO.class),
                Mockito.eq(DrWorkerRole.COORDINATOR))).thenReturn(22L);
    }

    @Test
    public void activeRunCancellationIsRequeuedForTerminalization() {
        DrRunVO active = new DrRunVO(10L, DrConstants.RUN_TYPE_TEST_FAILOVER);
        active.setState(DrConstants.RUN_STATE_RUNNING);
        Mockito.when(drRunDao.findById(active.getId())).thenReturn(active);

        DrRunVO result = service.cancelRun(active.getId());

        Assert.assertEquals(DrConstants.RUN_STATE_CANCEL_REQUESTED, result.getState());
        Mockito.verify(drOrchestrator).executeRun(active.getId());
    }

    @Test
    public void queuedRunCancellationDoesNotRequireExecutorRedispatch() {
        DrRunVO queued = new DrRunVO(10L, DrConstants.RUN_TYPE_TEST_FAILOVER);
        queued.setState(DrConstants.RUN_STATE_QUEUED);
        Mockito.when(drRunDao.findById(queued.getId())).thenReturn(queued);

        DrRunVO result = service.cancelRun(queued.getId());

        Assert.assertEquals(DrConstants.RUN_STATE_CANCELED, result.getState());
        Assert.assertNotNull(result.getCompleted());
        Mockito.verify(drOrchestrator, Mockito.never()).executeRun(queued.getId());
    }

    @Test
    public void activeFtctlRunIsTerminalizedOnlyAfterEngineAcceptsCancellation() {
        DrRunVO active = new DrRunVO(10L, DrConstants.RUN_TYPE_SYNC);
        active.setState(DrConstants.RUN_STATE_RUNNING);
        DrPlanVO plan = Mockito.mock(DrPlanVO.class);
        Mockito.when(plan.getUuid()).thenReturn("plan-uuid");
        Mockito.when(plan.getEngineType()).thenReturn(DrConstants.ENGINE_TYPE_FTCTL_DR);
        Mockito.when(drRunDao.findById(active.getId())).thenReturn(active);
        Mockito.when(drPlanDao.findById(10L)).thenReturn(plan);
        Mockito.when(agentManager.easySend(Mockito.eq(22L), Mockito.any(FtctlDrCancelCommand.class)))
                .thenAnswer(invocation -> {
                    FtctlDrCancelCommand command = invocation.getArgument(1);
                    return new FtctlDrCancelAnswer(command, true, "accepted", "plan-uuid", active.getUuid(),
                            "canceled", true, null, 0,
                            "{\"state\":\"CANCELED\",\"terminal_authoritative\":true,"
                                    + "\"runtime_endpoints_drained\":true,\"transfer_activity_state\":\"CANCELED\"}");
                });

        DrRunVO result = service.cancelRun(active.getId());

        Assert.assertEquals(DrConstants.RUN_STATE_CANCEL_REQUESTED, result.getState());
        Mockito.verify(drOrchestrator).executeRun(active.getId());
    }

    @Test
    public void activeFailbackIsTerminalizedOnlyAfterTargetAuthorityIsRestored() {
        DrRunVO active = new DrRunVO(10L, DrConstants.RUN_TYPE_FAILBACK);
        active.setState(DrConstants.RUN_STATE_RUNNING);
        DrPlanVO plan = Mockito.mock(DrPlanVO.class);
        Mockito.when(plan.getUuid()).thenReturn("plan-uuid");
        Mockito.when(plan.getEngineType()).thenReturn(DrConstants.ENGINE_TYPE_FTCTL_DR);
        Mockito.when(drRunDao.findById(active.getId())).thenReturn(active);
        Mockito.when(drPlanDao.findById(10L)).thenReturn(plan);
        Mockito.when(drFailbackLifecycleService.cancelAndRestoreTargetAuthority(plan, active)).thenReturn(true);
        Mockito.when(agentManager.easySend(Mockito.eq(22L), Mockito.any(FtctlDrCancelCommand.class)))
                .thenAnswer(invocation -> {
                    FtctlDrCancelCommand command = invocation.getArgument(1);
                    return new FtctlDrCancelAnswer(command, true, "accepted", "plan-uuid", active.getUuid(),
                            "canceled", true, null, 0,
                            "{\"state\":\"CANCELED\",\"terminal_authoritative\":true,"
                                    + "\"runtime_endpoints_drained\":true,\"transfer_activity_state\":\"CANCELED\"}");
                });

        service.cancelRun(active.getId());

        Mockito.verify(drFailbackLifecycleService).cancelAndRestoreTargetAuthority(plan, active);
        Mockito.verify(drOrchestrator).executeRun(active.getId());
    }

    @Test
    public void acceptedFtctlCancellationWithoutTerminalEvidenceRemainsRequested() {
        DrRunVO active = new DrRunVO(10L, DrConstants.RUN_TYPE_SYNC);
        active.setState(DrConstants.RUN_STATE_RUNNING);
        DrPlanVO plan = Mockito.mock(DrPlanVO.class);
        Mockito.when(plan.getUuid()).thenReturn("plan-uuid");
        Mockito.when(plan.getEngineType()).thenReturn(DrConstants.ENGINE_TYPE_FTCTL_DR);
        Mockito.when(drRunDao.findById(active.getId())).thenReturn(active);
        Mockito.when(drPlanDao.findById(10L)).thenReturn(plan);
        Mockito.when(agentManager.easySend(Mockito.eq(22L), Mockito.any(FtctlDrCancelCommand.class)))
                .thenAnswer(invocation -> {
                    FtctlDrCancelCommand command = invocation.getArgument(1);
                    return new FtctlDrCancelAnswer(command, true, "accepted", "plan-uuid", active.getUuid(),
                            "canceled", true, null, 0, "{\"state\":\"CANCEL_REQUESTED\"}");
                });

        DrRunVO result = service.cancelRun(active.getId());

        Assert.assertEquals(DrConstants.RUN_STATE_CANCEL_REQUESTED, result.getState());
        Mockito.verify(drOrchestrator, Mockito.never()).executeRun(active.getId());
    }

    @Test
    public void rejectedFtctlCancellationRemainsRequestedAndIsNotTerminalized() {
        DrRunVO active = new DrRunVO(10L, DrConstants.RUN_TYPE_SYNC);
        active.setState(DrConstants.RUN_STATE_RUNNING);
        DrPlanVO plan = Mockito.mock(DrPlanVO.class);
        Mockito.when(plan.getUuid()).thenReturn("plan-uuid");
        Mockito.when(plan.getEngineType()).thenReturn(DrConstants.ENGINE_TYPE_FTCTL_DR);
        Mockito.when(drRunDao.findById(active.getId())).thenReturn(active);
        Mockito.when(drPlanDao.findById(10L)).thenReturn(plan);
        Mockito.when(agentManager.easySend(Mockito.eq(22L), Mockito.any(FtctlDrCancelCommand.class)))
                .thenAnswer(invocation -> {
                    FtctlDrCancelCommand command = invocation.getArgument(1);
                    return new FtctlDrCancelAnswer(command, false, "rejected", "plan-uuid", active.getUuid(),
                            "rejected", false, "DR_CANCEL_REJECTED", 1, "{}");
                });

        DrRunVO result = service.cancelRun(active.getId());

        Assert.assertEquals(DrConstants.RUN_STATE_CANCEL_REQUESTED, result.getState());
        Mockito.verify(drOrchestrator, Mockito.never()).executeRun(active.getId());
    }

    @Test
    public void remoteKvmSourceOwnedRunCancellationUsesRemoteSourceAgent() {
        DrRunVO active = new DrRunVO(10L, DrConstants.RUN_TYPE_SYNC);
        active.setState(DrConstants.RUN_STATE_RUNNING);
        DrPlanVO plan = Mockito.mock(DrPlanVO.class);
        Mockito.when(plan.getUuid()).thenReturn("plan-uuid");
        Mockito.when(plan.getEngineType()).thenReturn(DrConstants.ENGINE_TYPE_FTCTL_DR);
        Mockito.when(plan.getActiveSide()).thenReturn("SOURCE");
        Mockito.when(drRunDao.findById(active.getId())).thenReturn(active);
        Mockito.when(drPlanDao.findById(10L)).thenReturn(plan);
        Mockito.when(drRemoteAgentClient.isRemoteKvmSource(plan)).thenReturn(true);
        Mockito.when(drRemoteAgentClient.cancelSourceRun(plan, active.getUuid()))
                .thenReturn(new FtctlDrCancelAnswer(new FtctlDrCancelCommand("plan-uuid", active.getUuid()),
                        true, "accepted", "plan-uuid", active.getUuid(), "canceled", true, null, 0,
                        "{\"state\":\"CANCELED\",\"terminal_authoritative\":true,"
                                + "\"runtime_endpoints_drained\":true,\"transfer_activity_state\":\"CANCELED\"}"));

        service.cancelRun(active.getId());

        Mockito.verify(drRemoteAgentClient).cancelSourceRun(plan, active.getUuid());
        Mockito.verify(agentManager, Mockito.never()).easySend(Mockito.anyLong(), Mockito.any(FtctlDrCancelCommand.class));
        Mockito.verify(drOrchestrator).executeRun(active.getId());
    }

    @Test
    public void lateCancellationProjectsExistingAuthoritativeTerminal() {
        DrRunVO active = new DrRunVO(10L, DrConstants.RUN_TYPE_SYNC);
        active.setState(DrConstants.RUN_STATE_RUNNING);
        DrPlanVO plan = Mockito.mock(DrPlanVO.class);
        Mockito.when(plan.getUuid()).thenReturn("plan-uuid");
        Mockito.when(plan.getEngineType()).thenReturn(DrConstants.ENGINE_TYPE_FTCTL_DR);
        Mockito.when(drRunDao.findById(active.getId())).thenReturn(active);
        Mockito.when(drPlanDao.findById(10L)).thenReturn(plan);
        Mockito.when(agentManager.easySend(Mockito.eq(22L), Mockito.any(FtctlDrCancelCommand.class)))
                .thenAnswer(invocation -> {
                    FtctlDrCancelCommand command = invocation.getArgument(1);
                    return new FtctlDrCancelAnswer(command, true, "already terminal", "plan-uuid", active.getUuid(),
                            "already_terminal", true, null, 0,
                            "{\"result\":\"already_terminal\",\"state\":\"READY\","
                                    + "\"terminal_authoritative\":true,\"runtime_endpoints_drained\":true,"
                                    + "\"transfer_activity_state\":\"IDLE\"}");
                });
        Mockito.doAnswer(invocation -> {
            active.setState(DrConstants.RUN_STATE_SUCCEEDED);
            active.setCompleted(new java.util.Date());
            return plan;
        }).when(drProjectionService).refreshPlanProjection(10L, true);

        DrRunVO result = service.cancelRun(active.getId());

        Assert.assertEquals(DrConstants.RUN_STATE_SUCCEEDED, result.getState());
        Assert.assertNotNull(result.getCompleted());
        Mockito.verify(drProjectionService).refreshPlanProjection(10L, true);
        Mockito.verify(drOrchestrator, Mockito.never()).executeRun(active.getId());
    }
}
