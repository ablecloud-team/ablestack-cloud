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

import java.util.Date;
import java.util.Arrays;
import java.util.Map;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.dr.adapter.DrAdapterRegistry;
import com.cloud.dr.adapter.DrReplicationEngine;
import com.cloud.dr.dao.DrCutoverSessionDao;
import com.cloud.dr.dao.DrPlanDao;
import com.cloud.dr.dao.DrPlanRuntimeDao;
import com.cloud.dr.dao.DrPlanViewCacheDao;
import com.cloud.dr.dao.DrReplicaDao;
import com.cloud.dr.dao.DrRestorePointDao;
import com.cloud.dr.dao.DrRunDao;
import com.cloud.dr.dao.DrSiteDao;
import com.cloud.dr.dao.DrSyncCycleDao;
import com.cloud.dr.dao.DrTestSessionDao;

@RunWith(MockitoJUnitRunner.class)
public class DrPlanServiceImplTest {
    @Mock
    private DrPlanDao drPlanDao;
    @Mock
    private DrPlanRuntimeDao drPlanRuntimeDao;
    @Mock
    private DrCutoverSessionDao drCutoverSessionDao;
    @Mock
    private DrSyncCycleDao drSyncCycleDao;
    @Mock
    private DrPlanViewCacheDao drPlanViewCacheDao;
    @Mock
    private DrReplicaDao drReplicaDao;
    @Mock
    private DrRestorePointDao drRestorePointDao;
    @Mock
    private DrRunDao drRunDao;
    @Mock
    private DrSiteDao drSiteDao;
    @Mock
    private DrAdapterRegistry drAdapterRegistry;
    @Mock
    private DrReplicationEngine replicationEngine;
    @Mock
    private DrPlanReadinessValidator drPlanReadinessValidator;
    @Mock
    private DrProtectionAuthorityService drProtectionAuthorityService;
    @Mock
    private DrTestSessionDao drTestSessionDao;
    @Mock
    private DrCurrentAuthorityResolver drCurrentAuthorityResolver;
    @Mock
    private DrFtctlActionCapabilityService drFtctlActionCapabilityService;

    @Mock
    private DrTestCleanupRecoveryStore testCleanupRecovery;

    @InjectMocks
    private DrPlanServiceImpl service;

    @Test
    public void databaseActionEvaluationDoesNotProbeAgentCapabilities() {
        DrPlanVO plan = new DrPlanVO("db-first-read", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        plan.setAdminState(DrConstants.ADMIN_STATE_ENABLED);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        Mockito.when(drPlanDao.findById(plan.getId())).thenReturn(plan);
        Mockito.when(drAdapterRegistry.getReplicationEngine(DrConstants.ENGINE_TYPE_FTCTL_DR,
                DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR)).thenReturn(replicationEngine);

        DrPlanActionEvaluation evaluation = service.getDatabaseActionEvaluation(plan.getId());

        Assert.assertTrue(evaluation.getEligibility().get("sync"));
        Assert.assertTrue(evaluation.getAvailability().get("sync").isEnabled());
        Mockito.verifyNoInteractions(drFtctlActionCapabilityService);
    }

    @Test
    public void deletePlanRemovesRuntimeAuthorityAndCycleCache() {
        DrPlanVO plan = new DrPlanVO("delete-cleanup", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        DrPlanVO removedPlan = new DrPlanVO("delete-cleanup", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        removedPlan.markRemoved();
        Mockito.when(drPlanDao.findById(plan.getId())).thenReturn(plan);
        Mockito.when(drPlanDao.remove(plan.getId())).thenReturn(true);
        Mockito.when(drPlanDao.findByIdIncludingRemoved(plan.getId())).thenReturn(removedPlan);

        Assert.assertTrue(service.deletePlan(plan.getId()));

        Mockito.verify(drSyncCycleDao).removeByPlanId(plan.getId());
        Mockito.verify(drPlanRuntimeDao).removeByPlanId(plan.getId());
    }

    @Test
    public void vmwarePhase1AllowsOnlySyncBeforeTargetReady() {
        DrPlanVO plan = new DrPlanVO("kvm-to-vmware", 1L, 2L, DrConstants.DIRECTION_KVM_TO_VMWARE);
        plan.setAdminState(DrConstants.ADMIN_STATE_ENABLED);
        plan.setEngineType(DrConstants.ENGINE_TYPE_VMWARE_PHASE1);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_VMWARE_PHASE1);
        Mockito.when(drPlanDao.findById(plan.getId())).thenReturn(plan);
        Mockito.when(drAdapterRegistry.getReplicationEngine(DrConstants.ENGINE_TYPE_VMWARE_PHASE1, DrConstants.ENGINE_BINDING_TYPE_VMWARE_PHASE1))
                .thenReturn(replicationEngine);

        Map<String, Boolean> eligibility = service.getActionEligibility(plan.getId());

        Assert.assertTrue(eligibility.get("sync"));
        Assert.assertFalse(eligibility.get("testFailover"));
        Assert.assertFalse(eligibility.get("failover"));
        Assert.assertFalse(eligibility.get("failback"));
        Assert.assertFalse(eligibility.get("reprotect"));
        Assert.assertFalse(eligibility.get("adoptReplica"));
        Assert.assertTrue(eligibility.get("migrationOnly"));
    }

    @Test
    public void ftctlEligibilityReflectsSupportedActionSurface() {
        DrPlanVO plan = new DrPlanVO("kvm-ftctl", 1L, 2L, "KVM_TO_KVM");
        plan.setAdminState(DrConstants.ADMIN_STATE_ENABLED);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL);
        Mockito.when(drPlanDao.findById(plan.getId())).thenReturn(plan);
        Mockito.when(drAdapterRegistry.getReplicationEngine(DrConstants.ENGINE_TYPE_FTCTL, DrConstants.ENGINE_BINDING_TYPE_FTCTL))
                .thenReturn(replicationEngine);

        Map<String, Boolean> eligibility = service.getActionEligibility(plan.getId());

        Assert.assertTrue(eligibility.get("sync"));
        Assert.assertFalse(eligibility.get("testFailover"));
        Assert.assertFalse(eligibility.get("stopTestFailover"));
        Assert.assertTrue(eligibility.get("failover"));
        Assert.assertFalse(eligibility.get("confirmFenceClear"));
        Assert.assertTrue(eligibility.get("failback"));
        Assert.assertTrue(eligibility.get("reprotect"));
        Assert.assertTrue(eligibility.get("adoptReplica"));
        Assert.assertFalse(eligibility.get("releaseProtection"));
        Assert.assertFalse(eligibility.get("cancelRun"));
    }

    @Test
    public void v2kIsMigrationOnlyAndDoesNotExposeDrActions() {
        DrPlanVO plan = new DrPlanVO("vmware-to-kvm", 1L, 2L, DrConstants.DIRECTION_VMWARE_TO_KVM);
        plan.setAdminState(DrConstants.ADMIN_STATE_ENABLED);
        plan.setEngineType(DrConstants.ENGINE_TYPE_V2K);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_V2K);
        Mockito.when(drPlanDao.findById(plan.getId())).thenReturn(plan);
        Mockito.when(drAdapterRegistry.getReplicationEngine(DrConstants.ENGINE_TYPE_V2K, DrConstants.ENGINE_BINDING_TYPE_V2K))
                .thenReturn(replicationEngine);

        Map<String, Boolean> eligibility = service.getActionEligibility(plan.getId());

        Assert.assertFalse(eligibility.get("sync"));
        Assert.assertFalse(eligibility.get("testFailover"));
        Assert.assertFalse(eligibility.get("failover"));
        Assert.assertFalse(eligibility.get("failback"));
        Assert.assertFalse(eligibility.get("reprotect"));
        Assert.assertFalse(eligibility.get("adoptReplica"));
        Assert.assertTrue(eligibility.get("migrationOnly"));
    }

    @Test
    public void pausedPlanAllowsCheckpointTestButNotPlannedCutover() {
        DrPlanVO plan = new DrPlanVO("ftctl-dr", 1L, 2L, DrConstants.DIRECTION_VMWARE_TO_KVM);
        plan.setAdminState(DrConstants.ADMIN_STATE_ENABLED);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setState(DrConstants.PLAN_STATE_PAUSED);
        plan.setTargetReadyAt(new Date());
        Mockito.when(drPlanDao.findById(plan.getId())).thenReturn(plan);
        Mockito.when(drAdapterRegistry.getReplicationEngine(DrConstants.ENGINE_TYPE_FTCTL_DR, DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR))
                .thenReturn(replicationEngine);
        Mockito.when(drRunDao.findLatestByPlanId(plan.getId())).thenReturn(controlReadyRun(plan));
        Mockito.when(drPlanReadinessValidator.validateForRelease(plan)).thenReturn(releaseReady());
        Mockito.when(drProtectionAuthorityService.getAuthority(plan.getId()))
                .thenReturn(new DrProtectionAuthoritySnapshot(new DrPlanRuntimeVO(plan.getId()), false));

        Map<String, Boolean> eligibility = service.getActionEligibility(plan.getId());

        Assert.assertTrue(eligibility.get("testFailover"));
        Assert.assertFalse(eligibility.get("failover"));
        Assert.assertTrue(eligibility.get("resumeSync"));
    }

    @Test
    public void ftctlDrEligibilityExposesContinuousDrActionsWhenTargetReady() {
        DrPlanVO plan = new DrPlanVO("ftctl-dr", 1L, 2L, DrConstants.DIRECTION_VMWARE_TO_KVM);
        plan.setAdminState(DrConstants.ADMIN_STATE_ENABLED);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setState(DrConstants.PLAN_STATE_READY);
        plan.setTargetReadyAt(new Date());
        Mockito.when(drPlanDao.findById(plan.getId())).thenReturn(plan);
        Mockito.when(drAdapterRegistry.getReplicationEngine(DrConstants.ENGINE_TYPE_FTCTL_DR, DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR))
                .thenReturn(replicationEngine);
        Mockito.when(drRunDao.findLatestByPlanId(plan.getId())).thenReturn(controlReadyRun(plan));
        Mockito.when(drPlanReadinessValidator.validateForRelease(plan)).thenReturn(releaseReady());
        Mockito.when(drProtectionAuthorityService.getAuthority(plan.getId()))
                .thenReturn(new DrProtectionAuthoritySnapshot(new DrPlanRuntimeVO(plan.getId()), true));

        Map<String, Boolean> eligibility = service.getActionEligibility(plan.getId());

        Assert.assertTrue(eligibility.get("sync"));
        Assert.assertTrue(eligibility.get("pauseSync"));
        Assert.assertFalse(eligibility.get("resumeSync"));
        Assert.assertTrue(eligibility.get("testFailover"));
        Assert.assertTrue(eligibility.get("failover"));
        Assert.assertFalse(eligibility.get("failback"));
        Assert.assertFalse(eligibility.get("reprotect"));
        Assert.assertFalse(eligibility.get("adoptReplica"));
        Assert.assertTrue(eligibility.get("releaseProtection"));
        Assert.assertFalse(eligibility.get("migrationOnly"));
    }

    @Test
    public void ftctlDrDisasterFailoverEligibilityIsProviderAgnostic() {
        assertDisasterFailoverEligibility(DrConstants.DIRECTION_VMWARE_TO_KVM);
        assertDisasterFailoverEligibility(DrConstants.DIRECTION_KVM_TO_KVM);
    }

    @Test
    public void ftctlDrEligibilityAllowsFailbackAfterReprotectKeepsTargetActive() {
        DrPlanVO plan = new DrPlanVO("ftctl-dr", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        plan.setAdminState(DrConstants.ADMIN_STATE_ENABLED);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setState(DrConstants.PLAN_STATE_READY);
        plan.setActiveSide("TARGET");
        plan.setTargetReadyAt(new Date());
        Mockito.when(drPlanDao.findById(plan.getId())).thenReturn(plan);
        Mockito.when(drAdapterRegistry.getReplicationEngine(DrConstants.ENGINE_TYPE_FTCTL_DR, DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR))
                .thenReturn(replicationEngine);
        Mockito.when(drRunDao.findLatestByPlanId(plan.getId())).thenReturn(controlReadyRun(plan));
        Mockito.when(drPlanReadinessValidator.validateForRelease(plan)).thenReturn(releaseReady());
        Mockito.when(drCurrentAuthorityResolver.resolve(plan)).thenReturn(new DrCurrentAuthorityProjection(
                "TARGET", "TARGET_PROTECTED", 113L, true, null, null, null));

        Map<String, Boolean> eligibility = service.getActionEligibility(plan.getId());

        Assert.assertTrue(eligibility.get("failback"));
        Assert.assertFalse(eligibility.get("reprotect"));
        Assert.assertTrue(eligibility.get("releaseProtection"));
    }

    @Test
    public void ftctlDrEligibilityAllowsReprotectWhenTargetIsActiveButReverseProtectionStopped() {
        DrPlanVO plan = new DrPlanVO("ftctl-dr-target-unprotected", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        plan.setAdminState(DrConstants.ADMIN_STATE_ENABLED);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setState(DrConstants.PLAN_STATE_READY);
        plan.setActiveSide("TARGET");
        plan.setTargetReadyAt(new Date());
        DrCutoverSessionVO cutover = new DrCutoverSessionVO(plan.getId(), 11L, "planned", "PROMOTED");
        cutover.setCloudPromotionState("PROMOTED");
        cutover.setEngineAckState("ACKNOWLEDGED");
        cutover.setCloudAuthorityGeneration(3L);
        DrReplicaVO replica = new DrReplicaVO(plan.getId(), plan.getTargetSiteId());
        replica.setTargetVmId(256L);
        replica.setActiveSide("TARGET");

        Mockito.when(drPlanDao.findById(plan.getId())).thenReturn(plan);
        Mockito.when(drAdapterRegistry.getReplicationEngine(DrConstants.ENGINE_TYPE_FTCTL_DR,
                DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR)).thenReturn(replicationEngine);
        Mockito.when(drRunDao.findLatestByPlanId(plan.getId())).thenReturn(controlReadyRun(plan));
        Mockito.when(drPlanReadinessValidator.validateForRelease(plan)).thenReturn(releaseReady());
        Mockito.when(drCutoverSessionDao.findLatestActiveByPlanId(plan.getId())).thenReturn(cutover);
        Mockito.when(drReplicaDao.listActiveByPlanId(plan.getId())).thenReturn(Arrays.asList(replica));
        Mockito.when(drCurrentAuthorityResolver.resolve(plan)).thenReturn(new DrCurrentAuthorityProjection(
                "TARGET", "FAILED_OVER_UNPROTECTED", 113L, true, null, null, cutover));

        Map<String, Boolean> eligibility = service.getActionEligibility(plan.getId());

        Assert.assertTrue(eligibility.get("failback"));
        Assert.assertTrue(eligibility.get("reprotect"));
        Assert.assertTrue(service.getActionAvailability(plan.getId()).get("reprotect").isApplicable());
    }

    @Test
    public void ftctlDrEligibilityBlocksSourceActionsAfterTargetPromotion() {
        DrPlanVO plan = new DrPlanVO("ftctl-dr-failed-over", 1L, 2L, DrConstants.DIRECTION_VMWARE_TO_KVM);
        plan.setAdminState(DrConstants.ADMIN_STATE_ENABLED);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setState(DrConstants.PLAN_STATE_FAILED_OVER);
        plan.setActiveSide("TARGET");
        plan.setTargetReadyAt(new Date());
        Mockito.when(drPlanDao.findById(plan.getId())).thenReturn(plan);
        Mockito.when(drAdapterRegistry.getReplicationEngine(DrConstants.ENGINE_TYPE_FTCTL_DR,
                DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR)).thenReturn(replicationEngine);
        Mockito.when(drRunDao.findLatestByPlanId(plan.getId())).thenReturn(controlReadyRun(plan));
        Mockito.when(drPlanReadinessValidator.validateForRelease(plan)).thenReturn(releaseReady());

        Map<String, Boolean> eligibility = service.getActionEligibility(plan.getId());

        Assert.assertFalse(eligibility.get("sync"));
        Assert.assertFalse(eligibility.get("pauseSync"));
        Assert.assertFalse(eligibility.get("resumeSync"));
        Assert.assertFalse(eligibility.get("testFailover"));
        Assert.assertFalse(eligibility.get("failover"));
        Assert.assertTrue(eligibility.get("failback"));
        Assert.assertFalse(eligibility.get("reprotect"));
    }

    @Test
    public void ftctlDrEligibilityAllowsReprotectOnlyWithCommittedTargetAuthority() {
        DrPlanVO plan = new DrPlanVO("ftctl-dr-failed-over", 1L, 2L, DrConstants.DIRECTION_VMWARE_TO_KVM);
        plan.setAdminState(DrConstants.ADMIN_STATE_ENABLED);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setState(DrConstants.PLAN_STATE_FAILED_OVER);
        plan.setActiveSide("TARGET");
        plan.setTargetReadyAt(new Date());
        DrCutoverSessionVO cutover = new DrCutoverSessionVO(plan.getId(), 11L, "planned", "PROMOTED");
        cutover.setCloudPromotionState("PROMOTED");
        cutover.setEngineAckState("ACKNOWLEDGED");
        cutover.setCloudAuthorityGeneration(3L);
        DrReplicaVO replica = new DrReplicaVO(plan.getId(), plan.getTargetSiteId());
        replica.setTargetVmId(256L);
        replica.setActiveSide("TARGET");

        Mockito.when(drPlanDao.findById(plan.getId())).thenReturn(plan);
        Mockito.when(drAdapterRegistry.getReplicationEngine(DrConstants.ENGINE_TYPE_FTCTL_DR,
                DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR)).thenReturn(replicationEngine);
        Mockito.when(drRunDao.findLatestByPlanId(plan.getId())).thenReturn(controlReadyRun(plan));
        Mockito.when(drPlanReadinessValidator.validateForRelease(plan)).thenReturn(releaseReady());
        Mockito.when(drCutoverSessionDao.findLatestActiveByPlanId(plan.getId())).thenReturn(cutover);
        Mockito.when(drReplicaDao.listActiveByPlanId(plan.getId())).thenReturn(Arrays.asList(replica));

        Map<String, Boolean> eligibility = service.getActionEligibility(plan.getId());

        Assert.assertTrue(eligibility.get("reprotect"));
    }

    @Test
    public void ftctlDrEligibilityAllowsTestCleanupForActiveSessionAfterRunCompletes() {
        DrPlanVO plan = new DrPlanVO("ftctl-dr-test-active", 1L, 2L, DrConstants.DIRECTION_VMWARE_TO_KVM);
        plan.setAdminState(DrConstants.ADMIN_STATE_ENABLED);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setState(DrConstants.PLAN_STATE_READY);
        DrTestSessionVO session = new DrTestSessionVO(plan.getId(), 64L, DrTestSessionState.ACTIVE);
        session.setCleanupRequired(true);
        Mockito.when(drPlanDao.findById(plan.getId())).thenReturn(plan);
        Mockito.when(drAdapterRegistry.getReplicationEngine(DrConstants.ENGINE_TYPE_FTCTL_DR, DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR))
                .thenReturn(replicationEngine);
        DrRunVO failedCleanup = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_TEST_CLEANUP);
        failedCleanup.setState(DrConstants.RUN_STATE_FAILED);
        failedCleanup.setLastStatusJson("{\"state\":\"CLEANED\"}");
        DrRunVO controlReadyRun = controlReadyRun(plan);
        Mockito.when(drRunDao.listByPlanId(plan.getId())).thenReturn(Arrays.asList(failedCleanup, controlReadyRun));
        Mockito.when(drTestSessionDao.findActiveByPlanId(plan.getId())).thenReturn(session);

        Map<String, Boolean> eligibility = service.getActionEligibility(plan.getId());

        Assert.assertTrue(eligibility.get("stopTestFailover"));
    }

    @Test
    public void ftctlDrEligibilityTreatsCleanedFailedSessionAsHistory() {
        DrPlanVO plan = new DrPlanVO("ftctl-dr-test-cleaned-failure", 1L, 2L, DrConstants.DIRECTION_VMWARE_TO_KVM);
        plan.setAdminState(DrConstants.ADMIN_STATE_ENABLED);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setState(DrConstants.PLAN_STATE_READY);
        plan.setTargetReadyAt(new Date());
        DrTestSessionVO session = new DrTestSessionVO(plan.getId(), 64L, DrTestSessionState.FAILED);
        session.setCleanupRequired(false);
        Mockito.when(drPlanDao.findById(plan.getId())).thenReturn(plan);
        Mockito.when(drAdapterRegistry.getReplicationEngine(DrConstants.ENGINE_TYPE_FTCTL_DR,
                DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR)).thenReturn(replicationEngine);
        Mockito.when(drRunDao.findLatestByPlanId(plan.getId())).thenReturn(controlReadyRun(plan));
        Mockito.when(drPlanReadinessValidator.validateForRelease(plan)).thenReturn(releaseReady());
        Mockito.when(drProtectionAuthorityService.getAuthority(plan.getId()))
                .thenReturn(new DrProtectionAuthoritySnapshot(new DrPlanRuntimeVO(plan.getId()), true));
        Mockito.when(drTestSessionDao.findActiveByPlanId(plan.getId())).thenReturn(session);

        Map<String, Boolean> eligibility = service.getActionEligibility(plan.getId());

        Assert.assertTrue(eligibility.get("testFailover"));
        Assert.assertFalse(eligibility.get("stopTestFailover"));
    }

    @Test
    public void ftctlDrEligibilityIgnoresArtifactFreeSessionWhoseRunFailed() {
        DrPlanVO plan = new DrPlanVO("ftctl-dr-test-stale-request", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        plan.setAdminState(DrConstants.ADMIN_STATE_ENABLED);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setState(DrConstants.PLAN_STATE_READY);
        plan.setTargetReadyAt(new Date());
        DrTestSessionVO session = new DrTestSessionVO(plan.getId(), 64L, DrTestSessionState.REQUESTED);
        DrRunVO failedRun = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_TEST_FAILOVER);
        failedRun.setState(DrConstants.RUN_STATE_FAILED);

        Mockito.when(drPlanDao.findById(plan.getId())).thenReturn(plan);
        Mockito.when(drAdapterRegistry.getReplicationEngine(DrConstants.ENGINE_TYPE_FTCTL_DR,
                DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR)).thenReturn(replicationEngine);
        Mockito.when(drRunDao.findById(session.getRunId())).thenReturn(failedRun);
        Mockito.when(drRunDao.findLatestByPlanId(plan.getId())).thenReturn(controlReadyRun(plan));
        Mockito.when(drPlanReadinessValidator.validateForRelease(plan)).thenReturn(releaseReady());
        Mockito.when(drProtectionAuthorityService.getAuthority(plan.getId()))
                .thenReturn(new DrProtectionAuthoritySnapshot(new DrPlanRuntimeVO(plan.getId()), true));
        Mockito.when(drTestSessionDao.findActiveByPlanId(plan.getId())).thenReturn(session);

        Map<String, Boolean> eligibility = service.getActionEligibility(plan.getId());

        Assert.assertTrue(eligibility.get("testFailover"));
        Assert.assertFalse(eligibility.get("stopTestFailover"));
    }

    @Test
    public void ftctlDrNbdQuarantineAllowsOnlySynchronizationRecovery() {
        DrPlanVO plan = new DrPlanVO("ftctl-dr-quarantine", 1L, 2L, DrConstants.DIRECTION_VMWARE_TO_KVM);
        plan.setAdminState(DrConstants.ADMIN_STATE_ENABLED);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setState(DrConstants.PLAN_STATE_READY);
        plan.setTargetReadyAt(new Date());
        DrPlanRuntimeVO runtime = new DrPlanRuntimeVO(plan.getId());
        runtime.setNbdTeardownState("QUARANTINED");
        runtime.setNbdQuarantinedDeviceCount(1);

        Mockito.when(drPlanDao.findById(plan.getId())).thenReturn(plan);
        Mockito.when(drPlanRuntimeDao.findByPlanId(plan.getId())).thenReturn(runtime);
        Mockito.when(drAdapterRegistry.getReplicationEngine(DrConstants.ENGINE_TYPE_FTCTL_DR,
                DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR)).thenReturn(replicationEngine);
        Mockito.when(drRunDao.findLatestByPlanId(plan.getId())).thenReturn(controlReadyRun(plan));

        Map<String, Boolean> eligibility = service.getActionEligibility(plan.getId());

        Assert.assertTrue(eligibility.get("recoverSync"));
        Assert.assertFalse(eligibility.get("sync"));
        Assert.assertFalse(eligibility.get("pauseSync"));
        Assert.assertFalse(eligibility.get("testFailover"));
        Assert.assertFalse(eligibility.get("failover"));
        Assert.assertFalse(eligibility.get("releaseProtection"));
    }

    @Test
    public void ftctlDrSourceOutageErrorAllowsSchedulerRecovery() {
        DrPlanVO plan = new DrPlanVO("ftctl-dr-source-outage", 1L, 2L, DrConstants.DIRECTION_VMWARE_TO_KVM);
        plan.setAdminState(DrConstants.ADMIN_STATE_ENABLED);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setState(DrConstants.PLAN_STATE_ERROR);
        DrPlanRuntimeVO runtime = new DrPlanRuntimeVO(plan.getId());
        runtime.setSchedulerDesiredState("RUNNING");
        runtime.setSchedulerPidAlive(false);
        runtime.setSchedulerHealthState("DEAD");
        runtime.setSchedulerUnitActiveState("failed");

        Mockito.when(drPlanDao.findById(plan.getId())).thenReturn(plan);
        Mockito.when(drPlanRuntimeDao.findByPlanId(plan.getId())).thenReturn(runtime);
        Mockito.when(drAdapterRegistry.getReplicationEngine(DrConstants.ENGINE_TYPE_FTCTL_DR,
                DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR)).thenReturn(replicationEngine);

        Map<String, Boolean> eligibility = service.getActionEligibility(plan.getId());

        Assert.assertTrue(eligibility.get("recoverSync"));
        Assert.assertFalse(eligibility.get("sync"));
    }

    @Test
    public void ftctlDrOperatorCanceledTransferRequiresExplicitSynchronizationRecovery() {
        DrPlanVO plan = new DrPlanVO("ftctl-dr-operator-canceled", 1L, 2L, DrConstants.DIRECTION_VMWARE_TO_KVM);
        plan.setAdminState(DrConstants.ADMIN_STATE_ENABLED);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setState(DrConstants.PLAN_STATE_READY);
        plan.setTargetReadyAt(new Date());
        DrPlanRuntimeVO runtime = new DrPlanRuntimeVO(plan.getId());
        runtime.setSchedulerDesiredState("STOPPED");
        runtime.setSchedulerRecoveryState(DrConstants.SCHEDULER_RECOVERY_REQUIRED);
        runtime.setSchedulerPidAlive(false);
        runtime.setSchedulerHealthState("STOPPED");
        runtime.setSchedulerUnitActiveState("inactive");

        Mockito.when(drPlanDao.findById(plan.getId())).thenReturn(plan);
        Mockito.when(drPlanRuntimeDao.findByPlanId(plan.getId())).thenReturn(runtime);
        Mockito.when(drAdapterRegistry.getReplicationEngine(DrConstants.ENGINE_TYPE_FTCTL_DR,
                DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR)).thenReturn(replicationEngine);

        Map<String, Boolean> eligibility = service.getActionEligibility(plan.getId());

        Assert.assertTrue(eligibility.get("recoverSync"));
        Assert.assertFalse(eligibility.get("sync"));
        Assert.assertFalse(eligibility.get("pauseSync"));
    }

    @Test
    public void ftctlDrAllowsAllFourDirectionsAtPlanValidation() {
        assertFtctlDrPlanCanBeCreated(DrConstants.DIRECTION_KVM_TO_KVM, DrConstants.HYPERVISOR_TYPE_KVM, DrConstants.HYPERVISOR_TYPE_KVM);
        assertFtctlDrPlanCanBeCreated(DrConstants.DIRECTION_KVM_TO_VMWARE, DrConstants.HYPERVISOR_TYPE_KVM, DrConstants.HYPERVISOR_TYPE_VMWARE);
        assertFtctlDrPlanCanBeCreated(DrConstants.DIRECTION_VMWARE_TO_VMWARE, DrConstants.HYPERVISOR_TYPE_VMWARE, DrConstants.HYPERVISOR_TYPE_VMWARE);
        assertFtctlDrPlanCanBeCreated(DrConstants.DIRECTION_VMWARE_TO_KVM, DrConstants.HYPERVISOR_TYPE_VMWARE, DrConstants.HYPERVISOR_TYPE_KVM);
    }

    private void assertFtctlDrPlanCanBeCreated(String direction, String sourceHypervisor, String targetHypervisor) {
        DrPlanVO plan = new DrPlanVO("ftctl-dr-" + direction, 1L, 2L, direction);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setSourceExternalRef("source-vm-" + direction);
        Mockito.when(drSiteDao.findById(1L)).thenReturn(new DrSiteVO("source", "PRIMARY", sourceHypervisor));
        Mockito.when(drSiteDao.findById(2L)).thenReturn(new DrSiteVO("target", "SECONDARY", targetHypervisor));
        Mockito.when(drPlanDao.persist(plan)).thenReturn(plan);

        DrPlanVO created = service.createPlan(plan);

        Assert.assertEquals(DrConstants.ENGINE_TYPE_FTCTL_DR, created.getEngineType());
        Assert.assertEquals(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR, created.getEngineBindingType());
        Assert.assertEquals("SOURCE", created.getActiveSide());
    }

    private void assertDisasterFailoverEligibility(String direction) {
        DrPlanVO plan = new DrPlanVO("ftctl-dr-disaster-" + direction, 1L, 2L, direction);
        plan.setAdminState(DrConstants.ADMIN_STATE_ENABLED);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setState(DrConstants.PLAN_STATE_READY);
        plan.setTargetReadyAt(new Date());
        Mockito.when(drPlanDao.findById(plan.getId())).thenReturn(plan);
        Mockito.when(drAdapterRegistry.getReplicationEngine(DrConstants.ENGINE_TYPE_FTCTL_DR,
                DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR)).thenReturn(replicationEngine);
        Mockito.when(drRunDao.findLatestByPlanId(plan.getId())).thenReturn(controlReadyRun(plan));
        Mockito.when(drProtectionAuthorityService.getAuthority(plan.getId()))
                .thenReturn(new DrProtectionAuthoritySnapshot(new DrPlanRuntimeVO(plan.getId()), false));

        Map<String, Boolean> eligibility = service.getActionEligibility(plan.getId());

        Assert.assertFalse(eligibility.get("failover"));
        Assert.assertTrue(eligibility.get("disasterFailover"));
        Assert.assertEquals(DrConstants.ACTION_REASON_CUTOVER_NOT_READY,
                service.getActionAvailability(plan.getId()).get("failover").getReasonCode());
    }

    private DrRunVO controlReadyRun(DrPlanVO plan) {
        DrRunVO run = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_SYNC);
        run.setLastStatusJson("{\"control_protocol_version\":2,\"control_generation\":7,"
                + "\"control_ack_generation\":7,\"control_state\":\"RUNNING\"}");
        return run;
    }

    private DrPlanReadiness releaseReady() {
        DrPlanReadiness readiness = new DrPlanReadiness();
        readiness.setReleaseReady(true);
        return readiness;
    }
    @Test
    public void forceDeleteUnregistersProtectedPlanAndPreservesRemoteEvidence() {
        DrPlanVO plan = new DrPlanVO("force-delete", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        plan.setState("READY");
        plan.setAdminState(DrConstants.ADMIN_STATE_ENABLED);
        DrPlanVO removed = new DrPlanVO("force-delete", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        removed.markRemoved();
        Mockito.when(drPlanDao.findById(plan.getId())).thenReturn(plan);
        Mockito.when(drPlanDao.remove(plan.getId())).thenReturn(true);
        Mockito.when(drPlanDao.findByIdIncludingRemoved(plan.getId())).thenReturn(removed);

        Assert.assertTrue(service.deletePlan(plan.getId(), true));

        Assert.assertEquals(DrConstants.ADMIN_STATE_DISABLED, plan.getAdminState());
        Mockito.verify(testCleanupRecovery).supersedePlan(plan.getId());
        Mockito.verify(drPlanRuntimeDao, Mockito.never()).removeByPlanId(Mockito.anyLong());
        Mockito.verify(drSyncCycleDao, Mockito.never()).removeByPlanId(Mockito.anyLong());
        Mockito.verifyNoInteractions(replicationEngine);
    }

    @Test
    public void forceDeleteRequiresActiveOperationToFinishOrCancel() {
        DrPlanVO plan = new DrPlanVO("active-delete", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        Mockito.when(drPlanDao.findById(plan.getId())).thenReturn(plan);
        Mockito.when(drRunDao.findActiveByPlanId(plan.getId())).thenReturn(new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_SYNC));
        try {
            service.deletePlan(plan.getId(), true);
            Assert.fail("active operation must not be orphaned");
        } catch (com.cloud.exception.InvalidParameterValueException expected) {
            Assert.assertTrue(expected.getMessage().contains(DrConstants.ERROR_ACTIVE_RUN_EXISTS));
        }
        Mockito.verify(drPlanDao, Mockito.never()).remove(Mockito.anyLong());
        Mockito.verifyNoInteractions(testCleanupRecovery);
    }

}
