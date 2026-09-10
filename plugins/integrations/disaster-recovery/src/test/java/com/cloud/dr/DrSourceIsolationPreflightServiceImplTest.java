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

import java.util.Collections;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.FtctlDrStatusAnswer;
import com.cloud.agent.api.FtctlDrStatusCommand;
import com.cloud.dr.dao.DrCutoverSessionDao;
import com.cloud.dr.dao.DrReplicaDao;
import com.cloud.dr.dao.DrRunStepDao;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.UserVmVO;

@RunWith(MockitoJUnitRunner.class)
public class DrSourceIsolationPreflightServiceImplTest {
    @Mock private DrCutoverSessionDao drCutoverSessionDao;
    @Mock private DrReplicaDao drReplicaDao;
    @Mock private DrRunStepDao drRunStepDao;
    @Mock private UserVmDao userVmDao;
    @Mock private AgentManager agentManager;
    @Mock private DrCurrentAuthorityResolver drCurrentAuthorityResolver;
    @Mock private DrWorkerPlacementService drWorkerPlacementService;

    @InjectMocks
    private DrSourceIsolationPreflightServiceImpl service;

    private DrPlanVO plan;

    @Before
    public void setUp() {
        plan = Mockito.mock(DrPlanVO.class);
        Mockito.when(plan.getId()).thenReturn(41L);
    }

    @Test
    public void acceptsFailedOperationStateWhenCommittedTargetAuthorityIsConsistent() {
        Mockito.when(drCurrentAuthorityResolver.resolve(plan)).thenReturn(authority("TARGET", true));
        DrCutoverSessionVO cutover = Mockito.mock(DrCutoverSessionVO.class);
        Mockito.when(cutover.getCloudAuthorityGeneration()).thenReturn(10L);
        Mockito.when(cutover.getCloudPromotionState()).thenReturn("PROMOTED");
        Mockito.when(cutover.getEngineAckState()).thenReturn("ACKNOWLEDGED");
        Mockito.when(cutover.getSourceFenceState()).thenReturn("ACKNOWLEDGED");
        Mockito.when(cutover.getSourcePowerState()).thenReturn("POWERED_OFF");
        Mockito.when(drCutoverSessionDao.findLatestActiveByPlanId(41L)).thenReturn(cutover);
        Mockito.when(drReplicaDao.listActiveByPlanId(41L)).thenReturn(Collections.emptyList());

        DrSourceIsolationPreflightResult result = service.validate(
                plan, null, DrConstants.RUN_TYPE_FAILBACK);

        Assert.assertFalse(result.isReady());
        Assert.assertEquals(DrConstants.ERROR_TRANSITION_TARGET_NOT_SERVING, result.getErrorCode());
    }

    @Test
    public void rejectsInconsistentTargetAuthority() {
        Mockito.when(drCurrentAuthorityResolver.resolve(plan)).thenReturn(authority("TARGET", false));

        DrSourceIsolationPreflightResult result = service.validate(
                plan, null, DrConstants.RUN_TYPE_FAILBACK);

        Assert.assertFalse(result.isReady());
        Assert.assertEquals(DrConstants.ERROR_TRANSITION_AUTHORITY_INVALID, result.getErrorCode());
    }

    @Test
    public void acceptsHostlessServingReplicaWhenAuthorityAndCheckpointContractAreReady() {
        Mockito.when(plan.getUuid()).thenReturn("plan-uuid");
        Mockito.when(drCurrentAuthorityResolver.resolve(plan)).thenReturn(authority("TARGET", true));
        DrCutoverSessionVO cutover = Mockito.mock(DrCutoverSessionVO.class);
        Mockito.when(cutover.getCloudAuthorityGeneration()).thenReturn(10L);
        Mockito.when(cutover.getCloudPromotionState()).thenReturn("PROMOTED");
        Mockito.when(cutover.getEngineAckState()).thenReturn("ACKNOWLEDGED");
        Mockito.when(cutover.getSourceFenceState()).thenReturn("ACKNOWLEDGED");
        Mockito.when(cutover.getSourcePowerState()).thenReturn("POWERED_OFF");
        Mockito.when(drCutoverSessionDao.findLatestActiveByPlanId(41L)).thenReturn(cutover);
        DrReplicaVO replica = new DrReplicaVO(41L, 2L);
        replica.setTargetVmId(256L);
        replica.setActiveSide("TARGET");
        Mockito.when(drReplicaDao.listActiveByPlanId(41L)).thenReturn(Collections.singletonList(replica));
        UserVmVO targetVm = Mockito.mock(UserVmVO.class);
        Mockito.when(targetVm.getId()).thenReturn(256L);
        Mockito.when(userVmDao.findById(256L)).thenReturn(targetVm);
        Mockito.when(drWorkerPlacementService.resolveWorkerHostId(plan, DrWorkerRole.TARGET)).thenReturn(77L);
        Mockito.when(agentManager.easySend(Mockito.eq(77L), Mockito.any(FtctlDrStatusCommand.class)))
                .thenAnswer(invocation -> {
                    FtctlDrStatusCommand command = invocation.getArgument(1);
                    FtctlDrStatusAnswer answer = new FtctlDrStatusAnswer(command, true, "ready");
                    answer.setTransitionReady(true);
                    answer.setTransitionContractVersion(FtctlDrStatusCommand.TRANSITION_PREFLIGHT_CONTRACT_VERSION);
                    answer.setTransitionActiveSide(DrConstants.AUTHORITY_SIDE_TARGET);
                    answer.setTransitionAuthorityGeneration(10L);
                    return answer;
                });

        DrSourceIsolationPreflightResult result = service.validate(
                plan, null, DrConstants.RUN_TYPE_FAILBACK);

        Assert.assertTrue(result.isReady());
        Assert.assertEquals("NOT_REQUIRED", result.getTargetPowerState());
        Assert.assertEquals(DrFailbackPreflightStage.STATE_READY, result.getEnginePreflightState());
    }

    private DrCurrentAuthorityProjection authority(String side, boolean consistent) {
        return new DrCurrentAuthorityProjection(side, "FAILED_OVER_UNPROTECTED", 10L,
                consistent, null, null, null);
    }
}
