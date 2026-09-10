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
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.dr.dao.DrCutoverSessionDao;
import com.cloud.dr.dao.DrPlanRuntimeDao;
import com.cloud.dr.dao.DrReplicaDao;
import com.cloud.dr.dao.DrRestorePointDao;
import com.cloud.dr.dao.DrRunStepDao;
import com.cloud.dr.dao.DrSyncCycleDao;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.dao.UserVmDao;

@RunWith(MockitoJUnitRunner.class)
public class DrReprotectPreflightServiceImplTest {
    @Mock private DrCutoverSessionDao drCutoverSessionDao;
    @Mock private DrPlanRuntimeDao drPlanRuntimeDao;
    @Mock private DrReplicaDao drReplicaDao;
    @Mock private DrRestorePointDao drRestorePointDao;
    @Mock private DrRunStepDao drRunStepDao;
    @Mock private DrSyncCycleDao drSyncCycleDao;
    @Mock private UserVmDao userVmDao;
    @Mock private DrSourceIsolationPreflightService drSourceIsolationPreflightService;
    @Mock private DrCurrentAuthorityResolver drCurrentAuthorityResolver;

    @InjectMocks
    private DrReprotectPreflightServiceImpl service;

    @Test
    public void validatesCommittedTargetAuthorityWithoutPowerStateGate() {
        Fixture fixture = fixture();
        Mockito.when(drSourceIsolationPreflightService.validate(fixture.plan, fixture.run,
                DrConstants.RUN_TYPE_REPROTECT))
                .thenReturn(DrSourceIsolationPreflightResult.success(
                        DrConstants.RUN_TYPE_REPROTECT, 3L, "ACKNOWLEDGED",
                        "POWERED_OFF", "POWERED_ON", "{\"ready\":true}"));

        DrReprotectPreflightResult result = service.validate(fixture.plan, fixture.run);

        Assert.assertTrue(result.isReady());
        Assert.assertEquals("TARGET", result.getAuthoritySpec().getExpectedActiveSide());
        Assert.assertEquals(3L, result.getAuthoritySpec().getAuthorityGeneration());
        Assert.assertEquals(153L, result.getAuthoritySpec().getAuthoritySequenceFloor());
        Assert.assertEquals(17L, result.getAuthoritySpec().getCheckpointSequence());
        Assert.assertEquals(256L, result.getAuthoritySpec().getTargetVmId());
        Assert.assertEquals("POWERED_ON", result.getAuthoritySpec().getTargetPowerState());
        Mockito.verify(drRunStepDao).persist(Mockito.argThat(step ->
                DrConstants.STEP_STATE_SUCCEEDED.equals(step.getState())
                        && step.getDetailsJson().contains("\"targetInstanceName\":\"i-2-256-VM\"")));
    }

    @Test
    public void acceptsTargetWithoutHostAssignmentWhenCheckpointAuthorityIsDurable() {
        Fixture fixture = fixture();
        UserVmVO stoppedTarget = Mockito.mock(UserVmVO.class);
        Mockito.when(stoppedTarget.getId()).thenReturn(256L);
        Mockito.when(stoppedTarget.getInstanceName()).thenReturn("i-2-256-VM");
        Mockito.when(userVmDao.findById(256L)).thenReturn(stoppedTarget);
        Mockito.when(drSourceIsolationPreflightService.validate(fixture.plan, fixture.run,
                DrConstants.RUN_TYPE_REPROTECT))
                .thenReturn(DrSourceIsolationPreflightResult.success(
                        DrConstants.RUN_TYPE_REPROTECT, 3L, "ACKNOWLEDGED",
                        "POWERED_OFF", "NOT_REQUIRED", "{\"ready\":true}"));

        DrReprotectPreflightResult result = service.validate(fixture.plan, fixture.run);

        Assert.assertTrue(result.isReady());
        Assert.assertEquals("NOT_REQUIRED", result.getAuthoritySpec().getTargetPowerState());
        Mockito.verify(drRunStepDao).persist(Mockito.argThat(step ->
                DrConstants.STEP_STATE_SUCCEEDED.equals(step.getState())));
    }

    @Test
    public void validatesRemoteKvmCutoverUsingCanonicalCloudCycleSequence() {
        Fixture fixture = fixture(DrConstants.DIRECTION_KVM_TO_KVM);
        fixture.cutover.setCheckpointSequence(112L);
        fixture.cutover.setDetailsJson("{\"checkpoint_sequence\":112,\"plan_cycle_sequence\":179}");
        durableCutoverCycle(fixture, 179L);
        allowReprotect(fixture);

        DrReprotectPreflightResult result = service.validate(fixture.plan, fixture.run);

        Assert.assertTrue(result.isReady());
        Assert.assertEquals(112L, result.getAuthoritySpec().getCheckpointSequence());
    }

    @Test
    public void validatesTargetAuthorityProjectionWhenRawPlanStateIsReady() {
        Fixture fixture = fixture(DrConstants.DIRECTION_KVM_TO_KVM);
        fixture.plan.setState(DrConstants.PLAN_STATE_READY);
        durableCutoverCycle(fixture, 17L);
        allowReprotect(fixture);

        DrReprotectPreflightResult result = service.validate(fixture.plan, fixture.run);

        Assert.assertTrue(result.isReady());
        Assert.assertEquals("TARGET", result.getAuthoritySpec().getExpectedActiveSide());
    }

    @Test
    public void acceptsCanonicalCutoverWhenReverseSchedulerUsesIndependentSequenceDomain() {
        Fixture fixture = fixture(DrConstants.DIRECTION_KVM_TO_KVM);
        fixture.cutover.setCheckpointSequence(112L);
        fixture.cutover.setDetailsJson("{\"checkpoint_sequence\":112,\"plan_cycle_sequence\":179}");
        fixture.checkpoint.setCheckpointSequence(122L);
        durableCutoverCycle(fixture, 179L);
        allowReprotect(fixture);

        DrReprotectPreflightResult result = service.validate(fixture.plan, fixture.run);

        Assert.assertTrue(result.isReady());
        Assert.assertEquals(112L, result.getAuthoritySpec().getCheckpointSequence());
    }

    @Test
    public void acceptsCanonicalCycleTokenWhenDatabaseSequenceHasAdvancedIndependently() {
        Fixture fixture = fixture(DrConstants.DIRECTION_KVM_TO_KVM);
        fixture.cutover.setCheckpointSequence(5L);
        fixture.cutover.setDetailsJson("{\"checkpoint_sequence\":5,\"plan_cycle_sequence\":14}");
        DrSyncCycleVO cycle = new DrSyncCycleVO(fixture.plan.getId(), "cutover-run", 28L);
        cycle.setCycleToken(fixture.plan.getUuid() + ":14");
        cycle.setState("READY");
        cycle.setCommitState("LOCAL_DURABLE");
        cycle.setTargetDurableAt(new java.util.Date());
        Mockito.when(drSyncCycleDao.findByPlanCycleToken(fixture.plan.getId(),
                fixture.plan.getUuid() + ":14")).thenReturn(cycle);
        allowReprotect(fixture);

        DrReprotectPreflightResult result = service.validate(fixture.plan, fixture.run);

        Assert.assertTrue(result.isReady());
        Assert.assertEquals(5L, result.getAuthoritySpec().getCheckpointSequence());
        Mockito.verify(drSyncCycleDao, Mockito.never()).findByPlanSequence(fixture.plan.getId(), 14L);
    }

    @Test
    public void acceptsDurableCycleByEngineCheckpointTokenWhenCloudSequenceDiffers() {
        Fixture fixture = fixture(DrConstants.DIRECTION_KVM_TO_KVM);
        fixture.cutover.setCheckpointSequence(546L);
        fixture.cutover.setDetailsJson("{\"checkpoint_sequence\":112,\"plan_cycle_sequence\":546}");
        DrSyncCycleVO cycle = new DrSyncCycleVO(fixture.plan.getId(), "cutover-run", 1163L);
        cycle.setCycleToken(fixture.plan.getUuid() + ":546");
        cycle.setState("READY");
        cycle.setCommitState("LOCAL_DURABLE");
        cycle.setTargetDurableAt(new java.util.Date());
        Mockito.when(drSyncCycleDao.findByPlanCycleToken(fixture.plan.getId(),
                fixture.plan.getUuid() + ":546")).thenReturn(cycle);
        allowReprotect(fixture);

        DrReprotectPreflightResult result = service.validate(fixture.plan, fixture.run);

        Assert.assertTrue(result.isReady());
        Assert.assertEquals(546L, result.getAuthoritySpec().getCheckpointSequence());
    }

    @Test
    public void rejectsMissingCanonicalDurableCutoverCycle() {
        Fixture fixture = fixture(DrConstants.DIRECTION_KVM_TO_KVM);
        fixture.cutover.setCheckpointSequence(112L);
        fixture.cutover.setDetailsJson("{\"checkpoint_sequence\":112,\"plan_cycle_sequence\":179}");

        DrReprotectPreflightResult result = service.validate(fixture.plan, fixture.run);

        Assert.assertFalse(result.isReady());
        Assert.assertEquals(DrConstants.ERROR_REPROTECT_CHECKPOINT_MISMATCH, result.getErrorCode());
    }

    @Test
    public void rejectsCanonicalCycleWithoutDurableCommit() {
        Fixture fixture = fixture(DrConstants.DIRECTION_KVM_TO_KVM);
        fixture.cutover.setCheckpointSequence(112L);
        fixture.cutover.setDetailsJson("{\"checkpoint_sequence\":112,\"plan_cycle_sequence\":179}");
        DrSyncCycleVO cutoverCycle = durableCutoverCycle(fixture, 179L);
        cutoverCycle.setCommitState("COMMITTING");

        DrReprotectPreflightResult result = service.validate(fixture.plan, fixture.run);

        Assert.assertFalse(result.isReady());
        Assert.assertEquals(DrConstants.ERROR_REPROTECT_CHECKPOINT_MISMATCH, result.getErrorCode());
    }

    private DrSyncCycleVO durableCutoverCycle(Fixture fixture, long sequence) {
        DrSyncCycleVO cycle = new DrSyncCycleVO(fixture.plan.getId(), "cutover-run", sequence);
        cycle.setCycleToken(fixture.plan.getUuid() + ":" + sequence);
        cycle.setState("READY");
        cycle.setCommitState("LOCAL_DURABLE");
        cycle.setTargetDurableAt(new java.util.Date());
        Mockito.when(drSyncCycleDao.findByPlanSequence(fixture.plan.getId(), sequence)).thenReturn(cycle);
        return cycle;
    }

    private void allowReprotect(Fixture fixture) {
        Mockito.when(drSourceIsolationPreflightService.validate(fixture.plan, fixture.run,
                DrConstants.RUN_TYPE_REPROTECT))
                .thenReturn(DrSourceIsolationPreflightResult.success(
                        DrConstants.RUN_TYPE_REPROTECT, 3L, "ACKNOWLEDGED",
                        "POWERED_OFF", "POWERED_ON", "{\"ready\":true}"));
    }

    private Fixture fixture() {
        return fixture(DrConstants.DIRECTION_VMWARE_TO_KVM);
    }

    private Fixture fixture(String direction) {
        DrPlanVO plan = new DrPlanVO("reprotect-plan", 1L, 2L, direction);
        plan.setState(DrConstants.PLAN_STATE_FAILED_OVER);
        plan.setActiveSide("TARGET");
        DrRunVO run = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_REPROTECT);
        DrCutoverSessionVO cutover = new DrCutoverSessionVO(plan.getId(), 11L, "planned", "PROMOTED");
        cutover.setCheckpointSequence(17L);
        cutover.setCloudPromotionState("PROMOTED");
        cutover.setEngineAckState("ACKNOWLEDGED");
        cutover.setCloudAuthorityGeneration(3L);
        DrReplicaVO replica = new DrReplicaVO(plan.getId(), plan.getTargetSiteId());
        replica.setTargetVmId(256L);
        replica.setTargetExternalRef("target-vm-uuid");
        replica.setActiveSide("TARGET");
        DrRestorePointVO checkpoint = new DrRestorePointVO(plan.getId(), "FTCTL_DR_CHECKPOINT");
        checkpoint.setCheckpointSequence(17L);
        UserVmVO targetVm = Mockito.mock(UserVmVO.class);
        Mockito.when(targetVm.getId()).thenReturn(256L);
        Mockito.when(targetVm.getUuid()).thenReturn("target-vm-uuid");
        Mockito.when(targetVm.getInstanceName()).thenReturn("i-2-256-VM");

        Mockito.when(drCutoverSessionDao.findLatestActiveByPlanId(plan.getId())).thenReturn(cutover);
        Mockito.when(drReplicaDao.listActiveByPlanId(plan.getId())).thenReturn(Collections.singletonList(replica));
        Mockito.when(drRestorePointDao.findLatestTargetReadyByPlanId(plan.getId())).thenReturn(checkpoint);
        DrPlanRuntimeVO runtime = new DrPlanRuntimeVO(plan.getId());
        runtime.setAuthoritySequence(41L);
        DrSyncCycleVO latestCompleted = new DrSyncCycleVO(plan.getId(), "cycle-run", 61L);
        latestCompleted.setAuthoritySequence(153L);
        Mockito.when(drPlanRuntimeDao.findByPlanId(plan.getId())).thenReturn(runtime);
        Mockito.when(drSyncCycleDao.findLatestCompletedByPlanId(plan.getId())).thenReturn(latestCompleted);
        Mockito.when(userVmDao.findById(256L)).thenReturn(targetVm);
        Mockito.when(drCurrentAuthorityResolver.resolve(plan)).thenReturn(new DrCurrentAuthorityProjection(
                "TARGET", "FAILED_OVER_UNPROTECTED", 3L, true, null, null, cutover));
        return new Fixture(plan, run, cutover, checkpoint);
    }

    private static class Fixture {
        private final DrPlanVO plan;
        private final DrRunVO run;
        private final DrCutoverSessionVO cutover;
        private final DrRestorePointVO checkpoint;

        Fixture(DrPlanVO plan, DrRunVO run, DrCutoverSessionVO cutover,
                DrRestorePointVO checkpoint) {
            this.plan = plan;
            this.run = run;
            this.cutover = cutover;
            this.checkpoint = checkpoint;
        }
    }
}
