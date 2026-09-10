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
package com.cloud.dr.adapter.ftctl;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.FtctlDrActionAnswer;
import com.cloud.agent.api.FtctlDrActionCommand;
import com.cloud.agent.api.FtctlDrCycleSnapshot;
import com.cloud.agent.api.FtctlDrStatusAnswer;
import com.cloud.agent.api.FtctlDrStatusCommand;
import com.cloud.dr.DrConstants;
import com.cloud.dr.DrCutoverSessionVO;
import com.cloud.dr.DrEventVO;
import com.cloud.dr.DrFailbackLifecycleService;
import com.cloud.dr.DrFailbackSessionVO;
import com.cloud.dr.DrPlanVO;
import com.cloud.dr.DrPlanOwnedTransportService;
import com.cloud.dr.DrPlanRuntimeVO;
import com.cloud.dr.DrReplicaDiskVO;
import com.cloud.dr.DrReplicaVO;
import com.cloud.dr.DrResolvedSiteCredential;
import com.cloud.dr.DrRestorePointVO;
import com.cloud.dr.DrRunStepVO;
import com.cloud.dr.DrRunVO;
import com.cloud.dr.DrSyncCycleVO;
import com.cloud.dr.DrTargetMaterializationService;
import com.cloud.dr.DrTargetResourceOwnershipService;
import com.cloud.dr.DrTargetPowerOnResult;
import com.cloud.dr.DrSiteCredentialService;
import com.cloud.dr.DrSiteVO;
import com.cloud.dr.DrTestSessionVO;
import com.cloud.dr.DrTestSessionState;
import com.cloud.dr.DrWorkerPlacementService;
import com.cloud.dr.DrWorkerRole;
import com.cloud.dr.adapter.DrAdapterResult;
import com.cloud.dr.dao.DrCutoverDiskDao;
import com.cloud.dr.dao.DrCutoverSessionDao;
import com.cloud.dr.dao.DrEventDao;
import com.cloud.dr.dao.DrFailbackSessionDao;
import com.cloud.dr.dao.DrPlanDao;
import com.cloud.dr.dao.DrPlanRuntimeDao;
import com.cloud.dr.dao.DrReplicaDiskDao;
import com.cloud.dr.dao.DrReplicaDao;
import com.cloud.dr.dao.DrRestorePointDao;
import com.cloud.dr.dao.DrRunDao;
import com.cloud.dr.dao.DrRunStepDao;
import com.cloud.dr.dao.DrSiteDao;
import com.cloud.dr.dao.DrSyncCycleDao;
import com.cloud.dr.dao.DrTestSessionDao;
import com.cloud.dr.inventory.DrVmwareInventoryClient;
import com.cloud.dr.inventory.DrSourceVmHardware;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.UserVmDao;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

@RunWith(MockitoJUnitRunner.class)
public class FtctlDrRuntimeProjectionAdapterTest {
    @Mock
    private com.cloud.dr.DrTestCleanupRecoveryStore testCleanupRecovery;

    @Test
    public void failedTestRestoresIntentOnlyAfterActualCleanupOfLatestRun() {
        DrPlanVO plan = new DrPlanVO("failed-test", 1L, 2L, DrConstants.DIRECTION_VMWARE_TO_KVM);
        DrRunVO run = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_TEST_FAILOVER);
        run.setState(DrConstants.RUN_STATE_FAILED);
        DrTestSessionVO session = new DrTestSessionVO(plan.getId(), run.getId(), "FAILED");
        session.setCleanupRequired(false);
        FtctlDrStatusCommand command = new FtctlDrStatusCommand(plan.getUuid(), run.getUuid());
        FtctlDrStatusAnswer status = new FtctlDrStatusAnswer(command, true, "ok");
        status.setTestSessionState("CLEANED");
        status.setTestArtifactsState("CLEANED");
        status.setTestCleanupState("CLEANED");
        status.setCheckpointLeaseState("RELEASED");
        status.setCleanupRequired(false);
        JsonObject runtime = new JsonObject();
        runtime.addProperty("run_uuid", run.getUuid());
        Mockito.when(drRunDao.findLatestByPlanId(plan.getId())).thenReturn(run);
        Mockito.when(drTestSessionDao.findByRunIdIncludingRemoved(run.getId())).thenReturn(session);
        adapter.restoreAfterFailedTestCleanup(plan, run, status, runtime);
        Mockito.verify(testCleanupRecovery).arm(plan.getId(), run.getId(), run.getId());
        Mockito.clearInvocations(testCleanupRecovery);

        session.setCleanupRequired(true);
        adapter.restoreAfterFailedTestCleanup(plan, run, status, runtime);
        session.setCleanupRequired(false);
        session.setTargetVmId(99L);
        adapter.restoreAfterFailedTestCleanup(plan, run, status, runtime);
        session.setTargetVmId(null);
        status.setCleanupRequired(true);
        adapter.restoreAfterFailedTestCleanup(plan, run, status, runtime);
        status.setCleanupRequired(false);
        runtime.addProperty("run_uuid", "other-run");
        adapter.restoreAfterFailedTestCleanup(plan, run, status, runtime);
        Mockito.verifyNoInteractions(testCleanupRecovery);
    }


    @Test
    public void bootProfileEvidenceAllowsPerformanceDriftButRejectsFirmwareDrift() {
        DrPlanVO plan = new DrPlanVO("boot-contract", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        plan.setMappingJson("{\"source\":{\"hardware\":{\"firmware\":\"uefi\",\"cpuCount\":8,"
                + "\"vmDetails\":{\"UEFI\":\"LEGACY\",\"iothreads\":\"true\"}}}}");
        JsonObject runtime = JsonParser.parseString("{\"source_hardware_fingerprint\":\"old-snapshot\","
                + "\"source_boot_hardware\":{\"firmware\":\"UEFI\",\"vmDetails\":{\"UEFI\":\"legacy\"}}}").getAsJsonObject();
        runtime.addProperty("source_boot_hardware_version", 1);
        Assert.assertTrue((Boolean) ReflectionTestUtils.invokeMethod(adapter, "hardwareContractMatches", plan, runtime));
        runtime.getAsJsonObject("source_boot_hardware").addProperty("firmware", "bios");
        Assert.assertFalse((Boolean) ReflectionTestUtils.invokeMethod(adapter, "hardwareContractMatches", plan, runtime));
    }

    @Test
    public void versionTwoHardwareContractIgnoresLegacyPlacementFingerprint() {
        DrPlanVO plan = new DrPlanVO("hardware-placement", 1L, 2L,
                DrConstants.DIRECTION_KVM_TO_KVM);
        JsonObject hardware = JsonParser.parseString("{\"sourceVmRef\":\"vm-1\","
                + "\"sourceHostUuid\":\"old-host\",\"sourceHostName\":\"old-name\","
                + "\"instanceName\":\"i-2-100-VM\",\"firmware\":\"EFI\","
                + "\"UEFI\":\"LEGACY\",\"secureBoot\":false,\"cpuCount\":2,"
                + "\"memoryMiB\":4096,\"fingerprint\":\"sha256:legacy-placement-hash\"}")
                .getAsJsonObject();
        JsonObject mapping = new JsonObject();
        JsonObject source = new JsonObject();
        source.add("hardware", hardware);
        mapping.add("source", source);
        plan.setMappingJson(mapping.toString());
        JsonObject runtime = new JsonObject();
        runtime.addProperty("source_hardware_fingerprint_version",
                DrSourceVmHardware.FINGERPRINT_CONTRACT_VERSION);
        runtime.addProperty("source_hardware_fingerprint", DrSourceVmHardware.stableFingerprint(hardware));

        Boolean matches = ReflectionTestUtils.invokeMethod(adapter, "hardwareContractMatches", plan, runtime);

        Assert.assertTrue(Boolean.TRUE.equals(matches));
    }

    @Test
    public void stableHardwareFingerprintIgnoresPlacementWhenLegacyPlanHasNoVersion() {
        DrPlanVO plan = new DrPlanVO("legacy-hardware-placement", 1L, 2L,
                DrConstants.DIRECTION_KVM_TO_KVM);
        JsonObject hardware = JsonParser.parseString("{\"sourceVmRef\":\"vm-1\","
                + "\"sourceHostUuid\":\"old-host\",\"sourceHostName\":\"old-name\","
                + "\"instanceName\":\"i-2-100-VM\",\"firmware\":\"EFI\","
                + "\"UEFI\":\"LEGACY\",\"secureBoot\":false,\"cpuCount\":2,"
                + "\"memoryMiB\":4096,\"fingerprint\":\"sha256:legacy-placement-hash\"}")
                .getAsJsonObject();
        JsonObject mapping = new JsonObject();
        JsonObject source = new JsonObject();
        source.add("hardware", hardware);
        mapping.add("source", source);
        plan.setMappingJson(mapping.toString());
        JsonObject runtime = new JsonObject();
        runtime.addProperty("source_hardware_fingerprint", DrSourceVmHardware.stableFingerprint(hardware));

        Boolean matches = ReflectionTestUtils.invokeMethod(adapter, "hardwareContractMatches", plan, runtime);

        Assert.assertTrue(Boolean.TRUE.equals(matches));
    }

    @Test
    public void testProjectionFailureMessagePreservesSpecificGuestPreparationBlocker() {
        FtctlDrStatusAnswer status = Mockito.mock(FtctlDrStatusAnswer.class);
        Mockito.when(status.getErrorMessage()).thenReturn(null);

        String message = ReflectionTestUtils.invokeMethod(adapter, "projectionFailureMessage",
                DrConstants.ERROR_GUEST_PREP_V2K_RUNTIME_MISSING, status, new com.google.gson.JsonObject());

        Assert.assertEquals("The required v2k guest preparation runtime is not installed on the selected worker", message);
    }

    @Test
    public void sharedMountPointCutoverRequiresRunOwnedQmpQuiesceEvidence() {
        DrPlanVO plan = new DrPlanVO("shared-file-cutover", 1L, 2L,
                DrConstants.DIRECTION_KVM_TO_KVM);
        plan.setMappingJson("{\"target\":{\"storagePoolType\":\"SharedMountPoint\"},"
                + "\"disks\":[{\"target\":{\"storagePoolType\":\"SharedMountPoint\"}}]}");
        Mockito.when(drRemoteAgentClient.isRemoteKvmSource(plan)).thenReturn(true);
        FtctlDrStatusAnswer status = Mockito.mock(FtctlDrStatusAnswer.class);
        Mockito.when(status.getState()).thenReturn("CUTOVER_READY");
        JsonObject runtime = JsonParser.parseString("{\"state\":\"CUTOVER_READY\","
                + "\"failover_mode\":\"planned\",\"failover_restore_point_sequence\":12,"
                + "\"manifest_sha256\":\"" + String.join("", Collections.nCopies(64, "a")) + "\","
                + "\"target_external_ref\":\"target-vm-uuid\"}").getAsJsonObject();

        Boolean readyWithoutQuiesce = ReflectionTestUtils.invokeMethod(adapter,
                "isCutoverReadyRuntime", plan, status, runtime);

        Assert.assertFalse(Boolean.TRUE.equals(readyWithoutQuiesce));
        runtime.addProperty("source_runtime_quiesce_state", "PAUSED");
        runtime.addProperty("source_runtime_quiesce_mode", "QMP_STOP");
        runtime.addProperty("cutover_source_disk_map_sha256",
                String.join("", Collections.nCopies(64, "b")));
        Boolean readyWithQuiesce = ReflectionTestUtils.invokeMethod(adapter,
                "isCutoverReadyRuntime", plan, status, runtime);
        Assert.assertTrue(Boolean.TRUE.equals(readyWithQuiesce));

        runtime.addProperty("source_runtime_quiesce_state", "OFFLINE");
        runtime.addProperty("source_runtime_quiesce_mode", "SOURCE_ALREADY_STOPPED");
        runtime.addProperty("source_power_state", "POWERED_OFF");
        Boolean readyWithStoppedSource = ReflectionTestUtils.invokeMethod(adapter,
                "isCutoverReadyRuntime", plan, status, runtime);
        Assert.assertTrue(Boolean.TRUE.equals(readyWithStoppedSource));

        runtime.addProperty("source_power_state", "UNKNOWN");
        Boolean rejectedWithoutStoppedEvidence = ReflectionTestUtils.invokeMethod(adapter,
                "isCutoverReadyRuntime", plan, status, runtime);
        Assert.assertFalse(Boolean.TRUE.equals(rejectedWithoutStoppedEvidence));
    }

    @Test
    public void remoteKvmCutoverUsesOperationCheckpointInsteadOfStaleSchedulerCheckpoint() {
        DrPlanVO plan = new DrPlanVO("remote-kvm-cutover-sequence", 1L, 2L,
                DrConstants.DIRECTION_KVM_TO_KVM);
        DrRunVO run = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_FAILOVER);
        DrCutoverSessionVO session = new DrCutoverSessionVO(plan.getId(), run.getId(),
                run.getRunType(), "CUTOVER_READY");
        JsonObject runtime = JsonParser.parseString("{\"state\":\"CUTOVER_READY\","
                + "\"latest_completed_checkpoint_sequence\":109,"
                + "\"failover_restore_point_sequence\":110}").getAsJsonObject();
        FtctlDrStatusAnswer status = Mockito.mock(FtctlDrStatusAnswer.class);
        Mockito.when(status.getState()).thenReturn("CUTOVER_READY");
        Mockito.when(drRemoteAgentClient.isRemoteKvmSource(plan)).thenReturn(true);
        Mockito.when(drCutoverSessionDao.findActiveByRunId(run.getId())).thenReturn(session);

        DrCutoverSessionVO projected = ReflectionTestUtils.invokeMethod(adapter,
                "upsertCutoverSession", plan, run, status, runtime);

        Assert.assertNotNull(projected);
        Assert.assertEquals(Long.valueOf(110L), projected.getCheckpointSequence());
    }

    @Test
    public void vmwareCutoverUsesFinalOperationCheckpointInsteadOfStaleSchedulerCheckpoint() {
        DrPlanVO plan = new DrPlanVO("vmware-file-cutover-sequence", 1L, 2L,
                "VMWARE_TO_KVM");
        FtctlDrStatusAnswer status = Mockito.mock(FtctlDrStatusAnswer.class);
        Mockito.when(status.getState()).thenReturn("CUTOVER_READY");
        Mockito.when(status.getGuestPreparationState()).thenReturn("READY");
        Mockito.when(status.getManifestSchemaVersion()).thenReturn("FTCTL_GUESTPREP_MANIFEST_V2");
        Mockito.when(status.getManifestSha256()).thenReturn(String.join("", Collections.nCopies(64, "a")));
        Mockito.when(status.getGuestPreparationCheckpointSequence()).thenReturn(37L);
        Mockito.when(status.getTargetDiskCount()).thenReturn(1);
        JsonObject runtime = JsonParser.parseString("{\"state\":\"CUTOVER_READY\","
                + "\"checkpoint_sequence\":37,\"failover_restore_point_sequence\":37,"
                + "\"latest_completed_checkpoint_sequence\":36}")
                .getAsJsonObject();

        Boolean ready = ReflectionTestUtils.invokeMethod(adapter,
                "isCutoverReadyRuntime", plan, status, runtime);

        Assert.assertTrue(Boolean.TRUE.equals(ready));
    }

    @Test
    public void disasterFailoverStatusNeverPollsRemoteSource() {
        DrPlanVO plan = new DrPlanVO("target-disaster-status", 1L, 2L,
                DrConstants.DIRECTION_KVM_TO_KVM);
        plan.setActiveSide(DrConstants.AUTHORITY_SIDE_SOURCE);
        DrRunVO run = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_FAILOVER);
        run.setRequestJson("{\"mode\":\"disaster\",\"finalSync\":false}");
        Mockito.when(drRemoteAgentClient.isRemoteKvmSource(plan)).thenReturn(true);

        Boolean pollsRemote = ReflectionTestUtils.invokeMethod(adapter, "pollsRemoteSource", plan, run);

        Assert.assertFalse(Boolean.TRUE.equals(pollsRemote));
    }

    @Test
    public void targetRecoveryAuthorityStatusNeverCallsRemoteSource() {
        DrPlanVO plan = new DrPlanVO("offline-test-status", 1L, 2L,
                DrConstants.DIRECTION_KVM_TO_KVM);
        plan.setActiveSide(DrConstants.AUTHORITY_SIDE_SOURCE);
        Mockito.when(drRemoteAgentClient.isRemoteKvmSource(plan)).thenReturn(true);
        for (String type : new String[] {DrConstants.RUN_TYPE_TEST_FAILOVER,
                DrConstants.RUN_TYPE_TEST_CLEANUP, DrConstants.RUN_TYPE_FAILOVER}) {
            DrRunVO run = new DrRunVO(plan.getId(), type);
            run.setRequestJson("{\"sourceIndependent\":true,\"mode\":\"disaster\",\"finalSync\":false}");
            FtctlDrStatusCommand command = new FtctlDrStatusCommand(plan.getUuid(), null,
                    FtctlDrStatusCommand.StatusScope.PLAN_AUTHORITY);
            ReflectionTestUtils.invokeMethod(adapter, "sendStatusCommand", plan, run, command, 7L);
            Mockito.verify(agentManager, Mockito.atLeastOnce()).easySend(7L, command);
        }
        Mockito.verify(drRemoteAgentClient, Mockito.never()).execute(Mockito.any(), Mockito.anyString(),
                Mockito.any(), Mockito.any(), Mockito.eq(FtctlDrStatusAnswer.class));
    }

    @Test
    public void plannedFailoverStatusRetainsRemoteSourceOwnership() {
        DrPlanVO plan = new DrPlanVO("planned-status", 1L, 2L,
                DrConstants.DIRECTION_KVM_TO_KVM);
        plan.setActiveSide(DrConstants.AUTHORITY_SIDE_SOURCE);
        DrRunVO run = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_FAILOVER);
        run.setRequestJson("{\"mode\":\"planned\",\"finalSync\":true}");
        Mockito.when(drRemoteAgentClient.isRemoteKvmSource(plan)).thenReturn(true);

        Boolean pollsRemote = ReflectionTestUtils.invokeMethod(adapter, "pollsRemoteSource", plan, run);

        Assert.assertTrue(Boolean.TRUE.equals(pollsRemote));
    }

    @Test
    public void powerOnValidatedCutoverRetryDoesNotDrainRunningTargetAgain() {
        DrPlanVO plan = new DrPlanVO("remote-kvm-cutover-retry", 1L, 2L,
                DrConstants.DIRECTION_KVM_TO_KVM);
        DrRunVO run = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_FAILOVER);
        DrCutoverSessionVO session = new DrCutoverSessionVO(plan.getId(), run.getId(),
                run.getRunType(), "ENGINE_COMMIT_PENDING");
        session.setCheckpointSequence(110L);
        session.setCloudPromotionState("POWER_ON_VALIDATED");
        Mockito.when(drRemoteAgentClient.isRemoteKvmSource(plan)).thenReturn(true);

        ReflectionTestUtils.invokeMethod(adapter, "stopPlanOwnedTargetExportForPromotion",
                plan, run, session);

        Mockito.verifyNoInteractions(drPlanOwnedTransportService);
    }

    @Test
    public void schemaSafeControlRequestRunUuidRejectsLegacySuffixedIdentity() {
        String uuid = "d64fdb24-9fb3-49ad-82de-2556db63698b";

        Assert.assertEquals(uuid, FtctlDrRuntimeProjectionAdapter.schemaSafeControlRequestRunUuid(uuid));
        Assert.assertNull(FtctlDrRuntimeProjectionAdapter.schemaSafeControlRequestRunUuid(
                uuid + "-source-resume"));
    }

    @Test
    public void schemaSafeRuntimeRunUuidNormalizesLegacySuffixedIdentityDeterministically() {
        String uuid = "d64fdb24-9fb3-49ad-82de-2556db63698b";
        String legacy = uuid + "-source-resume";

        Assert.assertEquals(uuid, FtctlDrRuntimeProjectionAdapter.schemaSafeRuntimeRunUuid(uuid));
        String normalized = FtctlDrRuntimeProjectionAdapter.schemaSafeRuntimeRunUuid(legacy);
        Assert.assertEquals(36, normalized.length());
        Assert.assertEquals(normalized, FtctlDrRuntimeProjectionAdapter.schemaSafeRuntimeRunUuid(legacy));
        Assert.assertNotEquals(normalized,
                FtctlDrRuntimeProjectionAdapter.schemaSafeRuntimeRunUuid(uuid + "-source-pause"));
    }

    @Mock
    private AgentManager agentManager;
    @Mock
    private DrPlanDao drPlanDao;
    @Mock
    private DrEventDao drEventDao;
    @Mock
    private DrRestorePointDao drRestorePointDao;
    @Mock
    private DrReplicaDao drReplicaDao;
    @Mock
    private DrReplicaDiskDao drReplicaDiskDao;
    @Mock
    private DrRunDao drRunDao;
    @Mock
    private DrRunStepDao drRunStepDao;
    @Mock
    private DrTargetMaterializationService drTargetMaterializationService;
    @Mock
    private DrTargetResourceOwnershipService drTargetResourceOwnershipService;
    @Mock
    private DrSiteDao drSiteDao;
    @Mock
    private DrSiteCredentialService drSiteCredentialService;
    @Mock
    private DrVmwareInventoryClient drVmwareInventoryClient;
    @Mock
    private DrCutoverSessionDao drCutoverSessionDao;
    @Mock
    private DrFailbackSessionDao drFailbackSessionDao;
    @Mock
    private DrFailbackLifecycleService drFailbackLifecycleService;
    @Mock
    private DrCutoverDiskDao drCutoverDiskDao;
    @Mock
    private DrPlanRuntimeDao drPlanRuntimeDao;
    @Mock
    private DrSyncCycleDao drSyncCycleDao;
    @Mock
    private DrTestSessionDao drTestSessionDao;
    @Mock
    private UserVmDao userVmDao;
    @Mock
    private VolumeDao volumeDao;
    @Mock
    private DrRemoteAgentClient drRemoteAgentClient;
    @Mock
    private DrPlanOwnedTransportService drPlanOwnedTransportService;
    @Mock
    private DrWorkerPlacementService drWorkerPlacementService;

    @InjectMocks
    private FtctlDrRuntimeProjectionAdapter adapter;

    @Before
    public void allowCycleProjectionLock() {
        Mockito.lenient().when(drPlanDao.acquireInLockTable(Mockito.anyLong(), Mockito.anyInt()))
                .thenReturn(new DrPlanVO("projection-lock", 1L, 2L, DrConstants.DIRECTION_VMWARE_TO_KVM));
        Mockito.lenient().when(drWorkerPlacementService.resolveWorkerHostId(Mockito.any(DrPlanVO.class),
                Mockito.nullable(DrRunVO.class), Mockito.any(DrWorkerRole.class)))
                .thenAnswer(invocation -> workerFor(invocation.getArgument(0), invocation.getArgument(2)));
        Mockito.lenient().when(drWorkerPlacementService.resolveWorkerHostId(Mockito.any(DrPlanVO.class),
                Mockito.any(DrWorkerRole.class)))
                .thenAnswer(invocation -> workerFor(invocation.getArgument(0), invocation.getArgument(1)));
    }

    private Long workerFor(DrPlanVO plan, DrWorkerRole role) {
        if (role == DrWorkerRole.SOURCE) {
            return plan.getSourceWorkerHostId() != null ? plan.getSourceWorkerHostId() : 101L;
        }
        if (role == DrWorkerRole.TARGET) {
            return plan.getTargetWorkerHostId() != null ? plan.getTargetWorkerHostId() : 102L;
        }
        return plan.getCoordinatorWorkerHostId() != null ? plan.getCoordinatorWorkerHostId() : 103L;
    }

    @Test
    public void pristineNewPlanDoesNotRequireAWorkerForRuntimeProjection() {
        DrPlanVO plan = new DrPlanVO("new-plan", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        plan.setState(DrConstants.PLAN_STATE_NEW);

        DrAdapterResult result = adapter.refreshPlanProjection(plan);

        Assert.assertTrue(result.isSuccess());
        Assert.assertTrue(result.getDetailsJson().contains("INITIAL_SYNC_PENDING"));
        Mockito.verifyNoInteractions(agentManager);
    }

    @Test
    public void activeNewPlanStillRequiresRuntimeWorkerPlacement() {
        DrPlanVO plan = new DrPlanVO("active-new-plan", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        plan.setState(DrConstants.PLAN_STATE_NEW);
        DrRunVO run = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_SYNC);
        Mockito.when(drRunDao.findActiveByPlanId(plan.getId())).thenReturn(run);
        Mockito.when(drWorkerPlacementService.resolveWorkerHostId(plan, DrWorkerRole.COORDINATOR))
                .thenReturn(null);

        DrAdapterResult result = adapter.refreshPlanProjection(plan);

        Assert.assertFalse(result.isSuccess());
        Assert.assertEquals(DrConstants.ERROR_TARGET_MAPPING_INVALID, result.getErrorCode());
    }

    @Test
    public void newPlanWithTerminalHistoryStillRequiresRuntimeWorkerPlacement() {
        DrPlanVO plan = new DrPlanVO("terminal-new-plan", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        plan.setState(DrConstants.PLAN_STATE_NEW);
        DrRunVO run = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_SYNC);
        run.setState(DrConstants.RUN_STATE_FAILED);
        Mockito.when(drRunDao.findLatestByPlanId(plan.getId())).thenReturn(run);
        Mockito.when(drWorkerPlacementService.resolveWorkerHostId(plan, DrWorkerRole.COORDINATOR))
                .thenReturn(null);

        DrAdapterResult result = adapter.refreshPlanProjection(plan);

        Assert.assertFalse(result.isSuccess());
        Assert.assertEquals(DrConstants.ERROR_TARGET_MAPPING_INVALID, result.getErrorCode());
    }

    @Test
    public void terminalTestCleanupProofRequiresArtifactsAndOwnedLeaseRelease() {
        FtctlDrStatusCommand command = new FtctlDrStatusCommand("plan-1", "run-1");
        FtctlDrStatusAnswer status = new FtctlDrStatusAnswer(command, true, "ok");
        status.setTestSessionState("CLEANED");
        status.setTestArtifactsState("CLEANED");
        status.setTestCleanupState("CLEANED");
        status.setCheckpointLeaseState("RELEASED");
        status.setCleanupRequired(false);

        Assert.assertTrue(adapter.hasTerminalTestCleanupProof(status, new JsonObject()));

        status.setCheckpointLeaseState("LEASED");
        Assert.assertFalse(adapter.hasTerminalTestCleanupProof(status, new JsonObject()));
    }

    @Test
    public void liveTransferOverlayPersistsNewerCorrelatedOperationSample() {
        DrPlanVO plan = new DrPlanVO("plan-live-progress", 1L, 2L, DrConstants.DIRECTION_VMWARE_TO_KVM);
        DrRunVO run = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_SYNC);
        DrPlanRuntimeVO authority = new DrPlanRuntimeVO(plan.getId());
        authority.setControlRequestRunUuid(run.getUuid());
        authority.setPlanCycleSequence(8L);
        authority.setTransferProgressSchemaVersion(2);
        authority.setTransferCycleSequence(8L);
        authority.setTransferSampleSequence(2L);
        authority.setTransferBytesTotal(4096L);
        authority.setTransferBytesProcessed(512L);
        Mockito.when(drPlanRuntimeDao.findByPlanId(plan.getId())).thenReturn(authority);

        FtctlDrStatusCommand command = new FtctlDrStatusCommand(plan.getUuid(), run.getUuid(),
                FtctlDrStatusCommand.StatusScope.OPERATION);
        FtctlDrStatusAnswer status = new FtctlDrStatusAnswer(command, true, "ok", plan.getUuid(), run.getUuid(),
                "ok", "SYNCING", "full-reseed-transfer", 40, null, null, null, null, null, 0, "", "{}");
        status.setTransferProgressSchemaVersion(2);
        status.setTransferCycleSequence(8L);
        status.setTransferSampleSequence(3L);
        status.setTransferActivityState("COPYING");
        status.setTransferPhase("TRANSFER");
        status.setTransferMode("FULL_RESEED");
        status.setTransferBytesTotal(4096L);
        status.setTransferBytesProcessed(1024L);
        status.setTransferPayloadBytes(1024L);
        status.setTransferPercent(25D);
        status.setTransferProgressSampleEpochMs(1_786_000_000_000L);

        adapter.projectLiveTransferOverlay(plan, run, status);

        Assert.assertEquals(Long.valueOf(3L), authority.getTransferSampleSequence());
        Assert.assertEquals(Long.valueOf(1024L), authority.getTransferBytesProcessed());
        Assert.assertEquals(Double.valueOf(25D), authority.getTransferPercent());
        Mockito.verify(drPlanRuntimeDao).update(authority.getId(), authority);
    }

    @Test
    public void liveTransferOverlayRejectsOlderSample() {
        DrPlanVO plan = new DrPlanVO("plan-live-progress", 1L, 2L, DrConstants.DIRECTION_VMWARE_TO_KVM);
        DrRunVO run = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_SYNC);
        DrPlanRuntimeVO authority = new DrPlanRuntimeVO(plan.getId());
        authority.setControlRequestRunUuid(run.getUuid());
        authority.setPlanCycleSequence(8L);
        authority.setTransferProgressSchemaVersion(2);
        authority.setTransferCycleSequence(8L);
        authority.setTransferSampleSequence(4L);
        authority.setTransferBytesTotal(4096L);
        authority.setTransferBytesProcessed(2048L);
        Mockito.when(drPlanRuntimeDao.findByPlanId(plan.getId())).thenReturn(authority);

        FtctlDrStatusCommand command = new FtctlDrStatusCommand(plan.getUuid(), run.getUuid(),
                FtctlDrStatusCommand.StatusScope.OPERATION);
        FtctlDrStatusAnswer status = new FtctlDrStatusAnswer(command, true, "ok", plan.getUuid(), run.getUuid(),
                "ok", "SYNCING", "full-reseed-transfer", 40, null, null, null, null, null, 0, "", "{}");
        status.setTransferProgressSchemaVersion(2);
        status.setTransferCycleSequence(8L);
        status.setTransferSampleSequence(3L);
        status.setTransferBytesTotal(4096L);
        status.setTransferBytesProcessed(1024L);

        adapter.projectLiveTransferOverlay(plan, run, status);

        Assert.assertEquals(Long.valueOf(4L), authority.getTransferSampleSequence());
        Assert.assertEquals(Long.valueOf(2048L), authority.getTransferBytesProcessed());
        Mockito.verify(drPlanRuntimeDao, Mockito.never()).update(Mockito.anyLong(), Mockito.any());
    }

    @Test
    public void idleRuntimeSummaryConvergesToLatestCompletedCycle() {
        DrPlanRuntimeVO authority = new DrPlanRuntimeVO(43L);
        authority.setTransferProgressSchemaVersion(2);
        authority.setTransferCycleSequence(352L);
        authority.setTransferMode("FULL_RESEED");
        authority.setTransferBytesTotal(107374182400L);
        authority.setTransferBytesProcessed(107374182400L);
        FtctlDrCycleSnapshot snapshot = new FtctlDrCycleSnapshot();
        snapshot.setSequence(528L);
        snapshot.setEffectiveMode("CBT_INCREMENTAL");
        snapshot.setVirtualBytes(107374182400L);
        snapshot.setChangedBytes(79691776L);
        snapshot.setSourceReadBytes(79691776L);
        snapshot.setTargetWrittenBytes(79691776L);
        snapshot.setTransferPayloadBytes(79691776L);
        snapshot.setThroughputBps(10485760L);
        snapshot.setMetricsEstimated(false);
        snapshot.setTargetDurableAt("2026-08-12T05:20:00Z");

        adapter.projectLatestCompletedTransferSummary(authority, snapshot);

        Assert.assertEquals("IDLE", authority.getTransferActivityState());
        Assert.assertEquals(Long.valueOf(528L), authority.getTransferCycleSequence());
        Assert.assertEquals("CBT_INCREMENTAL", authority.getTransferMode());
        Assert.assertEquals(Long.valueOf(79691776L), authority.getTransferBytesProcessed());
        Assert.assertEquals(Double.valueOf(100D), authority.getTransferPercent());
        Assert.assertFalse(authority.getTransferProgressStale());
    }

    @Test
    public void rpoFreshnessUsesDeadlineWithoutImplicitGrace() {
        Date durableAt = new Date();

        Assert.assertEquals("WITHIN_RPO", adapter.classifyRpoFreshness(false, durableAt, 239L, 300L));
        Assert.assertEquals("RPO_DUE_SOON", adapter.classifyRpoFreshness(false, durableAt, 240L, 300L));
        Assert.assertEquals("RPO_DUE_SOON", adapter.classifyRpoFreshness(false, durableAt, 300L, 300L));
        Assert.assertEquals("OVERDUE", adapter.classifyRpoFreshness(false, durableAt, 301L, 300L));
        Assert.assertEquals("OVERDUE", adapter.classifyRpoFreshness(false, null, 0L, 300L));
        Assert.assertEquals("WITHIN_RPO", adapter.classifyRpoFreshness(true, null, 900L, 300L));
    }

    @Test
    public void laterDurableCycleConsumesReverseCheckpointAndSupersedesOrphanCycle() {
        DrPlanVO plan = new DrPlanVO("plan-terminal-cycles", 1L, 2L, DrConstants.DIRECTION_VMWARE_TO_KVM);
        DrSyncCycleVO orphan = new DrSyncCycleVO(plan.getId(), "sync-run", 523L);
        orphan.setState("SYNCING");
        DrSyncCycleVO reverse = new DrSyncCycleVO(plan.getId(), "failback-run", 527L);
        reverse.setState("FAILBACK_DATA_READY");
        DrSyncCycleVO completed = new DrSyncCycleVO(plan.getId(), "sync-run", 528L);
        completed.setState("READY");
        completed.setCompleted(new Date());
        Mockito.when(drSyncCycleDao.listIncompleteAtOrBeforeSequence(plan.getId(), 528L, 100))
                .thenReturn(Arrays.asList(orphan, reverse));

        adapter.terminalizeSupersededSyncCycles(plan, completed);

        Mockito.verify(drSyncCycleDao).terminalize(orphan.getId(), "SUPERSEDED",
                "SUPERSEDED_BY_DURABLE_CYCLE", completed.getCompleted());
        Mockito.verify(drSyncCycleDao).terminalize(reverse.getId(), "CONSUMED",
                "CONSUMED_BY_DURABLE_CYCLE", completed.getCompleted());
    }

    @Test
    public void repeatedTerminalProjectionIsIdempotentAfterRestart() {
        DrPlanVO plan = new DrPlanVO("plan-terminal-restart", 1L, 2L, DrConstants.DIRECTION_VMWARE_TO_KVM);
        DrSyncCycleVO completed = new DrSyncCycleVO(plan.getId(), "sync-run", 528L);
        completed.setCompleted(new Date());
        Mockito.when(drSyncCycleDao.listIncompleteAtOrBeforeSequence(plan.getId(), 528L, 100))
                .thenReturn(Collections.emptyList());

        adapter.terminalizeSupersededSyncCycles(plan, completed);
        adapter.terminalizeSupersededSyncCycles(plan, completed);

        Mockito.verify(drSyncCycleDao, Mockito.times(2))
                .listIncompleteAtOrBeforeSequence(plan.getId(), 528L, 100);
        Mockito.verify(drSyncCycleDao, Mockito.never()).terminalize(Mockito.anyLong(), Mockito.anyString(),
                Mockito.anyString(), Mockito.any(Date.class));
    }

    @Test
    public void durableCycleImmediatelySupersedesSameSequenceAlias() {
        DrPlanVO plan = new DrPlanVO("plan-same-sequence-alias", 1L, 2L,
                DrConstants.DIRECTION_VMWARE_TO_KVM);
        DrSyncCycleVO alias = new DrSyncCycleVO(plan.getId(), "scheduler-run", 528L);
        ReflectionTestUtils.setField(alias, "id", 527L);
        alias.setState("TRANSFERRING");
        DrSyncCycleVO completed = new DrSyncCycleVO(plan.getId(), "operation-run", 528L);
        completed.setCompleted(new Date());
        Mockito.when(drSyncCycleDao.listIncompleteAtOrBeforeSequence(plan.getId(), 528L, 100))
                .thenReturn(Collections.singletonList(alias));

        adapter.terminalizeSupersededSyncCycles(plan, completed);

        Mockito.verify(drSyncCycleDao).terminalize(alias.getId(), "SUPERSEDED",
                "SUPERSEDED_BY_DURABLE_CYCLE", completed.getCompleted());
    }

    @Test
    public void acceptedCycleOfNonTerminalRunIsNotSupersededByNextIncremental() {
        DrPlanVO plan = new DrPlanVO("plan-pinned-cycle", 1L, 2L, DrConstants.DIRECTION_VMWARE_TO_KVM);
        ReflectionTestUtils.setField(plan, "id", 77L);
        DrSyncCycleVO accepted = new DrSyncCycleVO(plan.getId(), "operation-run", 42L);
        ReflectionTestUtils.setField(accepted, "id", 420L);
        accepted.setState("TRANSFERRING");
        DrSyncCycleVO nextCompleted = new DrSyncCycleVO(plan.getId(), "scheduler-run", 43L);
        nextCompleted.setCompleted(new Date());
        DrRunVO child = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_SYNC);
        child.setAcceptedCycleSequence(42L);

        Mockito.when(drSyncCycleDao.listIncompleteAtOrBeforeSequence(plan.getId(), 43L, 100))
                .thenReturn(Collections.singletonList(accepted));
        Mockito.when(drRunDao.listByPlanId(plan.getId())).thenReturn(Collections.singletonList(child));

        adapter.terminalizeSupersededSyncCycles(plan, nextCompleted);

        Mockito.verify(drSyncCycleDao, Mockito.never()).terminalize(Mockito.eq(accepted.getId()),
                Mockito.anyString(), Mockito.anyString(), Mockito.any(Date.class));
    }

    @Test
    public void schedulerAndOperationRunUuidsConvergeOnCanonicalPlanSequence() {
        DrPlanVO plan = new DrPlanVO("canonical-cycle-plan", 1L, 2L, DrConstants.DIRECTION_VMWARE_TO_KVM);
        ReflectionTestUtils.setField(plan, "id", 77L);
        DrRunVO run = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_SYNC);
        ReflectionTestUtils.setField(run, "id", 88L);
        DrSyncCycleVO canonical = new DrSyncCycleVO(plan.getId(), "scheduler-run", 19L);
        ReflectionTestUtils.setField(canonical, "id", 99L);
        Mockito.when(drSyncCycleDao.findByPlanSequence(plan.getId(), 19L)).thenReturn(canonical);

        FtctlDrStatusAnswer schedulerStatus = new FtctlDrStatusAnswer(
                new FtctlDrStatusCommand(plan.getUuid(), null), true, "ok");
        schedulerStatus.setActiveWorkerRunUuid("scheduler-run");
        adapter.projectCurrentSyncCycle(plan, run, schedulerStatus, 19L, "FULL_RESEED", "FULL_RESEED",
                "TRANSFERRING", "PENDING", null, new Date(), null, null);

        FtctlDrStatusAnswer operationStatus = new FtctlDrStatusAnswer(
                new FtctlDrStatusCommand(plan.getUuid(), "operation-run"), true, "ok");
        operationStatus.setLatestCompletedProducerRunUuid("operation-run");
        operationStatus.setLatestCompletedCheckpointSequence(19L);
        operationStatus.setLatestCompletedSourceCheckpointAt("2026-08-14T01:00:00Z");
        operationStatus.setLatestCompletedTargetDurableAt("2026-08-14T01:00:01Z");
        FtctlDrCycleSnapshot snapshot = new FtctlDrCycleSnapshot();
        snapshot.setPlanUuid(plan.getUuid());
        snapshot.setRunUuid("operation-run");
        snapshot.setSequence(19L);
        snapshot.setCycleToken(plan.getUuid() + ":19");
        snapshot.setState("READY");
        snapshot.setEffectiveMode("NO_CHANGE");
        snapshot.setBaselineGeneration(19L);
        snapshot.setChangedBytes(0L);
        snapshot.setTargetWrittenBytes(0L);
        operationStatus.setLatestCompletedCycle(snapshot);

        DrSyncCycleVO completed = adapter.projectLatestCompletedSyncCycle(plan, run, operationStatus, 19L,
                "LOCAL_DURABLE");

        Assert.assertSame(canonical, completed);
        Assert.assertEquals("scheduler-run", completed.getEngineRunUuid());
        Assert.assertEquals("READY", completed.getState());
        Assert.assertNotNull(completed.getCompleted());
        Mockito.verify(drSyncCycleDao, Mockito.never()).persist(Mockito.any(DrSyncCycleVO.class));
        Mockito.verify(drSyncCycleDao, Mockito.times(2)).update(Mockito.eq(canonical.getId()), Mockito.same(canonical));
    }

    @Test
    public void latestCycleAliasBuildsDurableSnapshotWithoutLegacyCheckpointAlias() {
        FtctlDrStatusAnswer status = new FtctlDrStatusAnswer(
                new FtctlDrStatusCommand("snapshot-cleanup-plan", "scheduler-run"), true, "ok");
        status.setLatestCompletedCycleSequence(57L);
        status.setLatestCompletedCycleToken("snapshot-cleanup-plan:57");
        status.setLatestCompletedCheckpointState("READY");
        status.setLatestCompletedRequestedMode("FULL_RESEED");
        status.setLatestCompletedEffectiveMode("FULL_RESEED");
        status.setLatestCompletedBaselineGeneration(57L);
        status.setLatestCompletedTargetDurableAt("2026-08-21T08:00:00Z");

        FtctlDrCycleSnapshot snapshot = ReflectionTestUtils.invokeMethod(adapter, "latestCompletedCycle", status);

        Assert.assertNotNull(snapshot);
        Assert.assertEquals(Long.valueOf(57L), snapshot.getSequence());
        Assert.assertEquals("snapshot-cleanup-plan:57", snapshot.getCycleToken());
        Assert.assertEquals("FULL_RESEED", snapshot.getEffectiveMode());
        Assert.assertEquals(Long.valueOf(57L), snapshot.getBaselineGeneration());
    }

    @Test
    public void releaseTombstoneConvergesPlanToDisabledUnprotectedWithoutChangingAuthority() {
        DrPlanVO plan = new DrPlanVO("release-plan", 1L, 2L, DrConstants.DIRECTION_VMWARE_TO_KVM);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setCoordinatorWorkerHostId(103L);
        plan.setState(DrConstants.PLAN_STATE_FAILED_OVER);
        plan.setAdminState(DrConstants.ADMIN_STATE_ENABLED);
        plan.setActiveSide(DrConstants.AUTHORITY_SIDE_TARGET);
        DrPlanRuntimeVO planRuntime = new DrPlanRuntimeVO(plan.getId());
        planRuntime.setSchedulerState("RUNNING");
        planRuntime.setSchedulerDesiredState("RUNNING");
        planRuntime.setSchedulerUnitActiveState("active");
        planRuntime.setSchedulerUnitSubState("running");
        planRuntime.setSchedulerUnitMainPid(1234L);
        planRuntime.setSchedulerPidAlive(true);
        planRuntime.setWorkerState("RUNNING");
        planRuntime.setTransferActivityState("TRANSFERRING");
        planRuntime.setTransferPercent(75D);
        planRuntime.setStatusJson("{\"state\":\"READY\",\"control_state\":\"RUNNING\"," +
                "\"scheduler_pid_alive\":true,\"active_worker_pid\":1234}");
        String statusJson = "{\"command\":\"dr-status\",\"result\":\"ok\","
                + "\"plan_uuid\":\"release-plan\",\"state\":\"RELEASED\","
                + "\"step\":\"release-completed\",\"active_side\":\"TARGET\","
                + "\"protection_state\":\"UNPROTECTED\",\"scheduler_state\":\"STOPPED\"}";
        Mockito.when(agentManager.easySend(Mockito.eq(103L), Mockito.any(FtctlDrStatusCommand.class)))
                .thenAnswer(invocation -> {
                    FtctlDrStatusCommand command = invocation.getArgument(1);
                    FtctlDrStatusAnswer answer = new FtctlDrStatusAnswer(command, true, "ok",
                            plan.getUuid(), null, "ok", "RELEASED", "release-completed", 100,
                            null, null, null, null, null, 0, null, statusJson);
                    answer.setStatusScope("PLAN_AUTHORITY");
                    answer.setProtectionState("UNPROTECTED");
                    return answer;
                });
        Mockito.when(drPlanRuntimeDao.findByPlanId(plan.getId())).thenReturn(planRuntime);
        Mockito.when(drReplicaDao.listActiveByPlanId(plan.getId())).thenReturn(Collections.emptyList());
        Mockito.when(drRestorePointDao.listActiveByPlanId(plan.getId())).thenReturn(Collections.emptyList());

        DrAdapterResult result = adapter.refreshPlanProjection(plan);

        Assert.assertTrue(result.isSuccess());
        Assert.assertEquals(DrConstants.PLAN_STATE_UNPROTECTED, plan.getState());
        Assert.assertEquals(DrConstants.ADMIN_STATE_DISABLED, plan.getAdminState());
        Assert.assertEquals(DrConstants.AUTHORITY_SIDE_TARGET, plan.getActiveSide());
        Assert.assertEquals("STOPPED", planRuntime.getSchedulerState());
        Assert.assertEquals("UNPROTECTED", planRuntime.getProtectionState());
        Assert.assertEquals("UNKNOWN", planRuntime.getFreshnessState());
        Assert.assertEquals("NONE", planRuntime.getSchedulerRecoveryState());
        Assert.assertEquals("inactive", planRuntime.getSchedulerUnitActiveState());
        Assert.assertEquals("dead", planRuntime.getSchedulerUnitSubState());
        Assert.assertNull(planRuntime.getSchedulerUnitMainPid());
        Assert.assertFalse(planRuntime.isSchedulerPidAlive());
        Assert.assertEquals("STOPPED", planRuntime.getWorkerState());
        Assert.assertEquals("IDLE", planRuntime.getTransferActivityState());
        Assert.assertNull(planRuntime.getTransferPercent());
        JsonObject releasedRuntime = JsonParser.parseString(planRuntime.getStatusJson()).getAsJsonObject();
        Assert.assertEquals("RELEASED", releasedRuntime.get("state").getAsString());
        Assert.assertEquals("STOPPED", releasedRuntime.get("control_state").getAsString());
        Assert.assertFalse(releasedRuntime.get("scheduler_pid_alive").getAsBoolean());
        Assert.assertFalse(releasedRuntime.has("active_worker_pid"));
        Mockito.verify(drPlanDao).update(plan.getId(), plan);
        Mockito.verify(drTargetResourceOwnershipService).releasePlanClaims(plan.getId());
        Mockito.verify(drSyncCycleDao, Mockito.never()).findLatestCompletedByPlanId(plan.getId());
        Mockito.verify(drFailbackLifecycleService, Mockito.never())
                .reconcile(Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    public void releaseActionAnswerConvergesImmediatelyWithoutStatusPolling() {
        DrPlanVO plan = new DrPlanVO("release-answer-plan", 1L, 2L, DrConstants.DIRECTION_VMWARE_TO_KVM);
        plan.setState(DrConstants.PLAN_STATE_READY);
        plan.setAdminState(DrConstants.ADMIN_STATE_ENABLED);
        plan.setActiveSide(DrConstants.AUTHORITY_SIDE_SOURCE);
        DrRunVO run = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_RELEASE);
        DrPlanRuntimeVO planRuntime = new DrPlanRuntimeVO(plan.getId());
        planRuntime.setStatusJson("{\"state\":\"READY\",\"control_state\":\"RUNNING\"}");
        String detailsJson = "{\"agentAnswer\":{\"action\":\"RELEASE\","
                + "\"planUuid\":\"release-answer-plan\",\"runUuid\":\"release-run\","
                + "\"state\":\"RELEASED\",\"step\":\"release-completed\","
                + "\"status\":{\"active_side\":\"SOURCE\",\"protection_state\":\"UNPROTECTED\","
                + "\"scheduler_state\":\"STOPPED\",\"profile_removed\":true}}}";
        Mockito.when(drPlanRuntimeDao.findByPlanId(plan.getId())).thenReturn(planRuntime);
        Mockito.when(drReplicaDao.listActiveByPlanId(plan.getId())).thenReturn(Collections.emptyList());
        Mockito.when(drRestorePointDao.listActiveByPlanId(plan.getId())).thenReturn(Collections.emptyList());

        DrAdapterResult result = adapter.projectTerminalActionResult(plan, run, detailsJson);

        Assert.assertTrue(result.isSuccess());
        Assert.assertEquals(DrConstants.PLAN_STATE_UNPROTECTED, plan.getState());
        Assert.assertEquals(DrConstants.ADMIN_STATE_DISABLED, plan.getAdminState());
        Assert.assertEquals(DrConstants.AUTHORITY_SIDE_SOURCE, plan.getActiveSide());
        Assert.assertEquals("STOPPED", planRuntime.getSchedulerState());
        Assert.assertEquals("UNPROTECTED", planRuntime.getProtectionState());
        Assert.assertEquals("UNKNOWN", planRuntime.getFreshnessState());
        Assert.assertEquals("NONE", planRuntime.getSchedulerRecoveryState());
        JsonObject releasedRuntime = JsonParser.parseString(planRuntime.getStatusJson()).getAsJsonObject();
        Assert.assertEquals("RELEASED", releasedRuntime.get("state").getAsString());
        Assert.assertEquals("STOPPED", releasedRuntime.get("control_state").getAsString());
        Assert.assertFalse(releasedRuntime.get("profile_exists").getAsBoolean());
        Mockito.verify(agentManager, Mockito.never()).easySend(Mockito.anyLong(), Mockito.any());
        Mockito.verify(drTargetResourceOwnershipService).releasePlanClaims(plan.getId());
    }

    @Test
    public void acceptedReleaseAppliesPersistedDispositionBeforeProjectionCleanup() {
        DrPlanVO plan = new DrPlanVO("release-delete-plan", 1L, 2L, DrConstants.DIRECTION_VMWARE_TO_KVM);
        DrRunVO run = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_RELEASE);
        run.setRequestJson("{\"resourceDisposition\":\"DELETE_STANDBY_REPLICA\"}");
        Mockito.when(drTargetMaterializationService.cleanupReleasedStandbyTarget(plan.getId(), run.getId(),
                DrConstants.RELEASE_DISPOSITION_DELETE_STANDBY_REPLICA)).thenReturn(true);

        ReflectionTestUtils.invokeMethod(adapter, "applyReleasedResourceDisposition", plan, run);

        Mockito.verify(drTargetMaterializationService).validateReleaseDisposition(plan.getId(),
                DrConstants.RELEASE_DISPOSITION_DELETE_STANDBY_REPLICA);
        Mockito.verify(drTargetMaterializationService).cleanupReleasedStandbyTarget(plan.getId(), run.getId(),
                DrConstants.RELEASE_DISPOSITION_DELETE_STANDBY_REPLICA);
        Mockito.verifyNoInteractions(drTargetResourceOwnershipService);
    }

    @Test
    public void ownershipConflictIsPreservedAsTerminalMaterializationFailure() {
        DrPlanVO plan = new DrPlanVO("ownership-conflict", 1L, 2L, DrConstants.DIRECTION_VMWARE_TO_KVM);
        plan.setState(DrConstants.PLAN_STATE_SYNCING);
        DrRunVO run = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_SYNC);
        run.setState(DrConstants.RUN_STATE_FAILED);
        run.setErrorCode(DrConstants.ERROR_TARGET_OWNERSHIP_CONFLICT);
        run.setErrorMessage("owned by a released plan");
        run.setCompleted(new Date());
        Mockito.when(drRunDao.findLatestByPlanId(plan.getId())).thenReturn(run);

        boolean preserved = ReflectionTestUtils.invokeMethod(adapter,
                "preserveTerminalMaterializationFailure", plan, null, new JsonObject());

        Assert.assertTrue(preserved);
        Assert.assertEquals(DrConstants.PLAN_STATE_ERROR, plan.getState());
        Assert.assertEquals(DrConstants.ERROR_TARGET_OWNERSHIP_CONFLICT, plan.getLastErrorCode());
        Mockito.verify(drPlanDao).update(plan.getId(), plan);
    }

    @Test
    public void releaseActionAnswerWithoutTerminalEvidenceIsRejected() {
        DrPlanVO plan = new DrPlanVO("release-answer-invalid", 1L, 2L, DrConstants.DIRECTION_VMWARE_TO_KVM);
        DrRunVO run = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_RELEASE);

        DrAdapterResult result = adapter.projectTerminalActionResult(plan, run,
                "{\"agentAnswer\":{\"state\":\"READY\",\"step\":\"idle\"}}");

        Assert.assertFalse(result.isSuccess());
        Assert.assertEquals(DrConstants.ERROR_PROJECTION_UNAVAILABLE, result.getErrorCode());
        Mockito.verifyNoInteractions(drPlanDao, drPlanRuntimeDao, drReplicaDao, drRestorePointDao);
    }

    @Test
    public void refreshPlanProjectionCompletesTestFailoverWithoutDowngradingActiveSession() {
        DrPlanVO plan = new DrPlanVO("ftctl-dr-plan", 1L, 2L, DrConstants.DIRECTION_VMWARE_TO_KVM);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setCoordinatorWorkerHostId(103L);
        DrRunVO run = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_TEST_FAILOVER);
        run.setState(DrConstants.RUN_STATE_ACCEPTED);
        DrTestSessionVO session = new DrTestSessionVO(plan.getId(), run.getId(), "ACTIVE");

        String statusJson = "{\"state\":\"TEST_ARTIFACTS_READY\",\"worker_state\":\"SUCCEEDED\"," +
                "\"transition_state\":\"TEST_ACTIVE\",\"progress\":100}";
        Mockito.when(agentManager.easySend(Mockito.eq(103L), Mockito.any(FtctlDrStatusCommand.class))).thenAnswer(invocation -> {
            FtctlDrStatusCommand command = invocation.getArgument(1);
            return new FtctlDrStatusAnswer(command, true, "ok", plan.getUuid(), run.getUuid(),
                    "ok", "TEST_ARTIFACTS_READY", "test-artifacts-ready", 100,
                    null, null, null, null, null, 0, "", statusJson);
        });
        Mockito.when(drRunDao.findActiveByPlanId(plan.getId())).thenReturn(run);
        Mockito.when(drTestSessionDao.findActiveByRunId(run.getId())).thenReturn(session);
        Mockito.when(drTargetMaterializationService.isTestTargetActive(run.getId())).thenReturn(true);

        DrAdapterResult result = adapter.refreshPlanProjection(plan);

        Assert.assertTrue(result.isSuccess());
        Assert.assertEquals("ACTIVE", session.getState());
        Assert.assertEquals(DrConstants.RUN_STATE_SUCCEEDED, run.getState());
        Assert.assertEquals("test-failover-active", run.getCurrentStepName());
        Assert.assertNotNull(run.getCompleted());
        Mockito.verify(drTargetMaterializationService, Mockito.never())
                .enqueueTestMaterialization(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyString());
    }

    @Test
    public void failedWorkerProjectsRequestedTestSessionToFailedDespiteStaleSyncingState() {
        DrPlanVO plan = new DrPlanVO("plan-test-failure", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        DrRunVO run = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_TEST_FAILOVER);
        DrTestSessionVO session = new DrTestSessionVO(plan.getId(), run.getId(), "REQUESTED");
        ReflectionTestUtils.setField(run, "id", 314L);
        ReflectionTestUtils.setField(session, "id", 19L);
        Mockito.when(drTestSessionDao.findActiveByRunId(run.getId())).thenReturn(session);

        FtctlDrStatusCommand command = new FtctlDrStatusCommand(plan.getUuid(), run.getUuid());
        FtctlDrStatusAnswer status = new FtctlDrStatusAnswer(command, true, "ok", plan.getUuid(), run.getUuid(),
                "ok", "SYNCING", "test-session-restore-point-missing", 100,
                null, null, null, null, "DR_RESTORE_POINT_NOT_FOUND", 44,
                "restore point was not found",
                "{\"state\":\"SYNCING\",\"worker_state\":\"FAILED\"," +
                        "\"error_code\":\"DR_RESTORE_POINT_NOT_FOUND\"}");
        JsonObject runtime = JsonParser.parseString(status.getStatusJson()).getAsJsonObject();

        ReflectionTestUtils.invokeMethod(adapter, "reconcileCloudManagedTestTarget", plan, run, status, runtime);

        Assert.assertEquals(DrTestSessionState.FAILED, session.getState());
        Assert.assertEquals("DR_RESTORE_POINT_NOT_FOUND", session.getErrorCode());
        Assert.assertTrue(session.isCleanupRequired());
        Mockito.verify(drTestSessionDao).update(session.getId(), session);
    }

    @Test
    public void nonOwningHostIdentityMismatchDoesNotMutateTestSession() {
        DrPlanVO plan = new DrPlanVO("test-session-host-fanout", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        DrRunVO run = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_TEST_FAILOVER);
        DrTestSessionVO session = new DrTestSessionVO(plan.getId(), run.getId(), DrTestSessionState.ARTIFACTS_READY);
        session.setCleanupRequired(true);
        ReflectionTestUtils.setField(run, "id", 400L);
        ReflectionTestUtils.setField(session, "id", 30L);

        FtctlDrStatusCommand command = new FtctlDrStatusCommand(plan.getUuid(), run.getUuid());
        String statusJson = "{\"result\":\"error\",\"state\":\"UNKNOWN\","
                + "\"error_code\":\"DR_STATUS_IDENTITY_MISMATCH\"}";
        FtctlDrStatusAnswer status = new FtctlDrStatusAnswer(command, false,
                "FTCTL_DR status identity did not match the requested plan/run", plan.getUuid(), run.getUuid(),
                "error", "UNKNOWN", "status-validation", 0, null, null, null, null,
                "DR_STATUS_IDENTITY_MISMATCH", 2,
                "FTCTL_DR status identity did not match the requested plan/run", statusJson);
        JsonObject runtime = JsonParser.parseString(statusJson).getAsJsonObject();

        ReflectionTestUtils.invokeMethod(adapter, "reconcileCloudManagedTestTarget", plan, run, status, runtime);

        Assert.assertEquals(DrTestSessionState.ARTIFACTS_READY, session.getState());
        Assert.assertTrue(session.isCleanupRequired());
        Mockito.verifyNoInteractions(drTestSessionDao, drTargetMaterializationService);
    }

    @Test
    public void remoteTestFailoverDefersRunNotFoundUsingResolvedRunWithoutSecondDaoRead() {
        DrPlanVO plan = new DrPlanVO("remote-test-pending", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setState(DrConstants.PLAN_STATE_READY);
        plan.setActiveSide("SOURCE");
        plan.setCoordinatorWorkerHostId(103L);
        DrRunVO run = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_TEST_FAILOVER);
        run.setState(DrConstants.RUN_STATE_ACCEPTED);
        DrTestSessionVO session = new DrTestSessionVO(plan.getId(), run.getId(), DrTestSessionState.REQUESTED);
        ReflectionTestUtils.setField(run, "id", 401L);
        ReflectionTestUtils.setField(session, "id", 31L);

        Mockito.when(drRunDao.findActiveByPlanId(plan.getId())).thenReturn(run, null);
        Mockito.when(drRemoteAgentClient.isRemoteKvmSource(plan)).thenReturn(true);
        Mockito.when(drRemoteAgentClient.execute(Mockito.eq(plan), Mockito.eq("STATUS"),
                Mockito.any(FtctlDrStatusCommand.class), Mockito.isNull(), Mockito.eq(FtctlDrStatusAnswer.class)))
                .thenAnswer(invocation -> {
                    FtctlDrStatusCommand command = invocation.getArgument(2);
                    FtctlDrStatusAnswer answer = new FtctlDrStatusAnswer(command, true, "ok", plan.getUuid(), null,
                            "ok", "READY", "source-authority", 100, null, null, null,
                            null, null, 0, "", "{\"scheduler_state\":\"RUNNING\"}");
                    answer.setStatusScope(FtctlDrStatusCommand.StatusScope.PLAN_AUTHORITY.name());
                    return answer;
                });
        Mockito.when(agentManager.easySend(Mockito.eq(103L), Mockito.any(FtctlDrStatusCommand.class)))
                .thenAnswer(invocation -> {
                    FtctlDrStatusCommand command = invocation.getArgument(1);
                    String statusJson = "{\"result\":\"run_not_found\",\"status_scope\":\"OPERATION\","
                            + "\"run_uuid\":\"" + run.getUuid() + "\",\"run_exists\":false,"
                            + "\"state\":\"QUEUED\",\"step\":\"run-pending\","
                            + "\"error_code\":\"not_found\",\"terminal_authoritative\":false}";
                    FtctlDrStatusAnswer answer = new FtctlDrStatusAnswer(command, true, "ok", plan.getUuid(),
                            run.getUuid(), "run_not_found", "QUEUED", "run-pending", 0, null, null, null,
                            null, "not_found", 0, "", statusJson);
                    answer.setStatusScope(FtctlDrStatusCommand.StatusScope.OPERATION.name());
                    return answer;
                });

        DrAdapterResult result = adapter.refreshPlanProjection(plan);

        Assert.assertTrue(result.isSuccess());
        Assert.assertEquals(DrTestSessionState.REQUESTED, session.getState());
        Assert.assertEquals(DrConstants.ERROR_RUNTIME_STARTING, run.getProjectionState());
        Assert.assertNull(run.getErrorCode());
        Mockito.verify(drRunDao, Mockito.times(1)).findActiveByPlanId(plan.getId());
        Mockito.verify(drTestSessionDao, Mockito.never()).update(Mockito.eq(session.getId()),
                Mockito.any(DrTestSessionVO.class));
        Mockito.verify(drTargetMaterializationService, Mockito.never())
                .enqueueTestMaterialization(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyString());
    }

    @Test
    public void currentArtifactsRecoverArtifactFreeFailureFromPreDispatchRace() {
        DrPlanVO plan = new DrPlanVO("recover-test-pending", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        DrRunVO run = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_TEST_FAILOVER);
        DrTestSessionVO session = new DrTestSessionVO(plan.getId(), run.getId(), DrTestSessionState.FAILED);
        session.setCleanupRequired(false);
        session.setErrorCode("DR_REPLICATION_CYCLE_FAILED");
        session.setErrorMessage("stale Plan error");
        ReflectionTestUtils.setField(run, "id", 402L);
        ReflectionTestUtils.setField(session, "id", 32L);
        Mockito.when(drTestSessionDao.findActiveByRunId(run.getId())).thenReturn(session);

        String statusJson = "{\"state\":\"TEST_ARTIFACTS_READY\",\"run_exists\":true,"
                + "\"worker_state\":\"SUCCEEDED\",\"test_artifacts_state\":\"CREATED\"}";
        FtctlDrStatusCommand command = new FtctlDrStatusCommand(plan.getUuid(), run.getUuid());
        FtctlDrStatusAnswer status = new FtctlDrStatusAnswer(command, true, "ok", plan.getUuid(), run.getUuid(),
                "ok", "TEST_ARTIFACTS_READY", "test-artifacts-ready", 100,
                null, null, null, null, null, 0, "", statusJson);
        JsonObject runtime = JsonParser.parseString(statusJson).getAsJsonObject();

        ReflectionTestUtils.invokeMethod(adapter, "reconcileCloudManagedTestTarget", plan, run, status, runtime);

        Assert.assertEquals(DrTestSessionState.ARTIFACTS_READY, session.getState());
        Assert.assertTrue(session.isCleanupRequired());
        Assert.assertNull(session.getErrorCode());
        Assert.assertNull(session.getErrorMessage());
        Mockito.verify(drTestSessionDao).update(session.getId(), session);
        Mockito.verify(drTargetMaterializationService)
                .enqueueTestMaterialization(plan.getId(), run.getId(), statusJson);
    }

    @Test
    public void currentArtifactsRestoreSoftClosedSessionFromPreDispatchRace() {
        DrPlanVO plan = new DrPlanVO("restore-soft-closed-test", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        DrRunVO run = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_TEST_FAILOVER);
        run.setState(DrConstants.RUN_STATE_RUNNING);
        DrTestSessionVO session = new DrTestSessionVO(plan.getId(), run.getId(), DrTestSessionState.FAILED);
        session.setCleanupRequired(false);
        session.setErrorCode("DR_REPLICATION_CYCLE_FAILED");
        session.setErrorMessage("stale Plan error");
        session.setRemoved(new Date());
        ReflectionTestUtils.setField(run, "id", 403L);
        ReflectionTestUtils.setField(session, "id", 33L);
        Mockito.when(drTestSessionDao.findActiveByRunId(run.getId())).thenReturn(null, session);
        Mockito.when(drTestSessionDao.findByRunIdIncludingRemoved(run.getId())).thenReturn(session);
        Mockito.when(drTestSessionDao.restoreSoftClosedForMaterialization(session)).thenReturn(true);

        String statusJson = "{\"state\":\"TEST_ARTIFACTS_READY\",\"run_exists\":true,"
                + "\"worker_state\":\"SUCCEEDED\",\"test_artifacts_state\":\"CREATED\","
                + "\"test_artifact_count\":2}";
        FtctlDrStatusCommand command = new FtctlDrStatusCommand(plan.getUuid(), run.getUuid());
        FtctlDrStatusAnswer status = new FtctlDrStatusAnswer(command, true, "ok", plan.getUuid(), run.getUuid(),
                "ok", "TEST_ARTIFACTS_READY", "test-artifacts-ready", 100,
                null, null, null, null, null, 0, "", statusJson);
        status.setWorkerState("SUCCEEDED");
        status.setTestArtifactsState("CREATED");
        status.setTestArtifactCount(2);
        JsonObject runtime = JsonParser.parseString(statusJson).getAsJsonObject();

        ReflectionTestUtils.invokeMethod(adapter, "reconcileCloudManagedTestTarget", plan, run, status, runtime);

        Assert.assertNull(session.getRemoved());
        Assert.assertEquals(DrTestSessionState.ARTIFACTS_READY, session.getState());
        Assert.assertTrue(session.isCleanupRequired());
        Assert.assertNull(session.getErrorCode());
        Assert.assertNull(session.getErrorMessage());
        Mockito.verify(drTestSessionDao).restoreSoftClosedForMaterialization(session);
        Mockito.verify(drTestSessionDao, Mockito.never()).update(session.getId(), session);
        Mockito.verify(drTargetMaterializationService)
                .enqueueTestMaterialization(plan.getId(), run.getId(), statusJson);
    }

    @Test
    public void failedSoftClosedSessionRestoreDoesNotEnqueueMaterialization() {
        DrPlanVO plan = new DrPlanVO("failed-restore-soft-closed-test", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        DrRunVO run = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_TEST_FAILOVER);
        run.setState(DrConstants.RUN_STATE_RUNNING);
        DrTestSessionVO session = new DrTestSessionVO(plan.getId(), run.getId(), DrTestSessionState.ARTIFACTS_READY);
        session.setCleanupRequired(true);
        session.setRemoved(new Date());
        ReflectionTestUtils.setField(run, "id", 405L);
        ReflectionTestUtils.setField(session, "id", 35L);
        Mockito.when(drTestSessionDao.findActiveByRunId(run.getId())).thenReturn(null);
        Mockito.when(drTestSessionDao.findByRunIdIncludingRemoved(run.getId())).thenReturn(session);
        Mockito.when(drTestSessionDao.restoreSoftClosedForMaterialization(session)).thenReturn(false);

        String statusJson = "{\"state\":\"TEST_ARTIFACTS_READY\",\"run_exists\":true,"
                + "\"worker_state\":\"SUCCEEDED\",\"test_artifacts_state\":\"CREATED\","
                + "\"test_artifact_count\":2}";
        FtctlDrStatusCommand command = new FtctlDrStatusCommand(plan.getUuid(), run.getUuid());
        FtctlDrStatusAnswer status = new FtctlDrStatusAnswer(command, true, "ok", plan.getUuid(), run.getUuid(),
                "ok", "TEST_ARTIFACTS_READY", "test-artifacts-ready", 100,
                null, null, null, null, null, 0, "", statusJson);
        status.setWorkerState("SUCCEEDED");
        status.setTestArtifactsState("CREATED");
        status.setTestArtifactCount(2);

        ReflectionTestUtils.invokeMethod(adapter, "reconcileCloudManagedTestTarget", plan, run, status,
                JsonParser.parseString(statusJson).getAsJsonObject());

        Mockito.verify(drTargetMaterializationService, Mockito.never())
                .enqueueTestMaterialization(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyString());
    }

    @Test
    public void terminalRunDoesNotRestoreSoftClosedTestSession() {
        DrPlanVO plan = new DrPlanVO("terminal-soft-closed-test", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        DrRunVO run = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_TEST_FAILOVER);
        run.setState(DrConstants.RUN_STATE_FAILED);
        run.setCompleted(new Date());
        DrTestSessionVO session = new DrTestSessionVO(plan.getId(), run.getId(), DrTestSessionState.FAILED);
        session.setRemoved(new Date());
        ReflectionTestUtils.setField(run, "id", 404L);
        ReflectionTestUtils.setField(session, "id", 34L);
        Mockito.when(drTestSessionDao.findActiveByRunId(run.getId())).thenReturn(null);

        String statusJson = "{\"state\":\"TEST_ARTIFACTS_READY\",\"run_exists\":true,"
                + "\"worker_state\":\"SUCCEEDED\",\"test_artifacts_state\":\"CREATED\","
                + "\"test_artifact_count\":2}";
        FtctlDrStatusCommand command = new FtctlDrStatusCommand(plan.getUuid(), run.getUuid());
        FtctlDrStatusAnswer status = new FtctlDrStatusAnswer(command, true, "ok", plan.getUuid(), run.getUuid(),
                "ok", "TEST_ARTIFACTS_READY", "test-artifacts-ready", 100,
                null, null, null, null, null, 0, "", statusJson);
        JsonObject runtime = JsonParser.parseString(statusJson).getAsJsonObject();

        ReflectionTestUtils.invokeMethod(adapter, "reconcileCloudManagedTestTarget", plan, run, status, runtime);

        Assert.assertNotNull(session.getRemoved());
        Mockito.verify(drTestSessionDao, Mockito.never()).findByRunIdIncludingRemoved(run.getId());
        Mockito.verify(drTargetMaterializationService, Mockito.never())
                .enqueueTestMaterialization(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyString());
    }

    @Test
    public void failedTestManifestPreservesExactLocatorErrorInRunAndSession() {
        DrPlanVO plan = new DrPlanVO("plan-test-locator-failure", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        DrRunVO run = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_TEST_FAILOVER);
        DrTestSessionVO session = new DrTestSessionVO(plan.getId(), run.getId(), "REQUESTED");
        ReflectionTestUtils.setField(run, "id", 315L);
        ReflectionTestUtils.setField(session, "id", 20L);
        Mockito.when(drTestSessionDao.findActiveByRunId(run.getId())).thenReturn(session);

        String message = "unsupported test artifact type: qcow2-copy";
        FtctlDrStatusCommand command = new FtctlDrStatusCommand(plan.getUuid(), run.getUuid());
        FtctlDrStatusAnswer status = new FtctlDrStatusAnswer(command, true, "ok", plan.getUuid(), run.getUuid(),
                "ok", "ERROR", "test-guest-preparation-failed", 100,
                null, null, null, null, "DR_TARGET_DISK_LOCATOR_INVALID", 63, message,
                "{\"state\":\"ERROR\",\"worker_state\":\"FAILED\"," +
                        "\"error_code\":\"DR_TARGET_DISK_LOCATOR_INVALID\"," +
                        "\"error_message\":\"unsupported test artifact type: qcow2-copy\"}");
        JsonObject runtime = JsonParser.parseString(status.getStatusJson()).getAsJsonObject();

        ReflectionTestUtils.invokeMethod(adapter, "reconcileCloudManagedTestTarget", plan, run, status, runtime);
        ReflectionTestUtils.invokeMethod(adapter, "failRunFromProjection", plan, run, status, runtime);

        Assert.assertEquals(DrConstants.RUN_STATE_FAILED, run.getState());
        Assert.assertEquals("DR_TARGET_DISK_LOCATOR_INVALID", run.getErrorCode());
        Assert.assertEquals(message, run.getErrorMessage());
        Assert.assertEquals(DrTestSessionState.FAILED, session.getState());
        Assert.assertEquals("DR_TARGET_DISK_LOCATOR_INVALID", session.getErrorCode());
        Assert.assertEquals(message, session.getErrorMessage());
    }

    @Test
    public void terminalCheckpointSetFailureClosesSessionWithoutChangingProtectionState() {
        DrPlanVO plan = new DrPlanVO("plan-checkpoint-set-failure", 1L, 2L,
                DrConstants.DIRECTION_KVM_TO_KVM);
        plan.setState(DrConstants.PLAN_STATE_READY);
        DrRunVO run = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_TEST_FAILOVER);
        DrTestSessionVO session = new DrTestSessionVO(plan.getId(), run.getId(), "REQUESTED");
        ReflectionTestUtils.setField(run, "id", 316L);
        ReflectionTestUtils.setField(session, "id", 21L);
        Mockito.when(drTestSessionDao.findActiveByRunId(run.getId())).thenReturn(session);

        String message = "required local mount /DATA was not resolved from the checkpoint disk set";
        FtctlDrStatusCommand command = new FtctlDrStatusCommand(plan.getUuid(), run.getUuid());
        FtctlDrStatusAnswer status = new FtctlDrStatusAnswer(command, true, "ok", plan.getUuid(), run.getUuid(),
                "error", "ERROR", "test-materialization-failed", 100,
                null, null, null, null, DrConstants.ERROR_TEST_CHECKPOINT_GUEST_FS_INCONSISTENT,
                0, message, "{}");
        status.setTestSessionState("CLEANED");
        status.setTestArtifactsState("CLEANED");
        status.setTestCleanupState("CLEANED");
        status.setCheckpointLeaseState("RELEASED");
        status.setCleanupRequired(false);
        JsonObject runtime = JsonParser.parseString("{\"state\":\"ERROR\",\"worker_state\":\"FAILED\","
                + "\"terminal_source\":\"ENGINE_TERMINAL\",\"terminal_authoritative\":true,"
                + "\"error_code\":\"DR_TEST_CHECKPOINT_GUEST_FS_INCONSISTENT\","
                + "\"test_session_state\":\"CLEANED\",\"test_artifacts_state\":\"CLEANED\","
                + "\"test_cleanup_state\":\"CLEANED\",\"cleanup_required\":false,"
                + "\"checkpoint_lease_state\":\"RELEASED\"}").getAsJsonObject();

        ReflectionTestUtils.invokeMethod(adapter, "reconcileCloudManagedTestTarget", plan, run, status, runtime);
        ReflectionTestUtils.invokeMethod(adapter, "failRunFromProjection", plan, run, status, runtime);

        Assert.assertEquals(DrConstants.RUN_STATE_FAILED, run.getState());
        Assert.assertEquals(DrConstants.ERROR_TEST_CHECKPOINT_GUEST_FS_INCONSISTENT, run.getErrorCode());
        Assert.assertEquals("The complete checkpoint disk set could not be validated as a boot-consistent guest",
                run.getErrorMessage());
        Assert.assertEquals(DrTestSessionState.FAILED, session.getState());
        Assert.assertFalse(session.isCleanupRequired());
        Assert.assertNotNull(session.getRemoved());
        Assert.assertEquals(DrConstants.PLAN_STATE_READY, plan.getState());
        Mockito.verify(drPlanDao, Mockito.never()).update(Mockito.eq(plan.getId()), Mockito.any(DrPlanVO.class));
    }

    @Test
    public void refreshPlanProjectionSeparatesAuthorityAndOperationWithoutErasingLastGoodVerification() {
        DrPlanVO plan = new DrPlanVO("ftctl-dr-plan", 1L, 2L, DrConstants.DIRECTION_VMWARE_TO_KVM);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setCoordinatorWorkerHostId(103L);
        DrRunVO run = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_TEST_FAILOVER);
        run.setState(DrConstants.RUN_STATE_ACCEPTED);
        DrPlanRuntimeVO authority = new DrPlanRuntimeVO(plan.getId());
        authority.setLatestCompletedCycleSequence(42L);
        authority.setLatestCompletedIncrementalVerified(true);

        Mockito.when(drRunDao.findActiveByPlanId(plan.getId())).thenReturn(run);
        Mockito.when(drPlanRuntimeDao.findByPlanId(plan.getId())).thenReturn(authority);
        Mockito.when(drTargetMaterializationService.isTestTargetActive(run.getId())).thenReturn(true);
        Mockito.when(agentManager.easySend(Mockito.eq(103L), Mockito.any(FtctlDrStatusCommand.class))).thenAnswer(invocation -> {
            FtctlDrStatusCommand command = invocation.getArgument(1);
            if (command.getStatusScope() == FtctlDrStatusCommand.StatusScope.PLAN_AUTHORITY) {
                FtctlDrStatusAnswer answer = new FtctlDrStatusAnswer(command, true, "ok", plan.getUuid(), null,
                        "ok", "READY", "checkpoint-ready", 100, null, null, null,
                        null, null, 0, "", "{\"scheduler_state\":\"RUNNING\"}");
                answer.setStatusScope("PLAN_AUTHORITY");
                return answer;
            }
            FtctlDrStatusAnswer answer = new FtctlDrStatusAnswer(command, true, "ok", plan.getUuid(), run.getUuid(),
                    "ok", "TEST_ARTIFACTS_READY", "test-artifacts-ready", 100, null, null, null,
                    null, null, 0, "", "{\"state\":\"TEST_ARTIFACTS_READY\"}");
            answer.setStatusScope("OPERATION");
            return answer;
        });

        DrAdapterResult result = adapter.refreshPlanProjection(plan);

        Assert.assertTrue(result.isSuccess());
        Assert.assertEquals(Boolean.TRUE, authority.getLatestCompletedIncrementalVerified());
        ArgumentCaptor<FtctlDrStatusCommand> commandCaptor = ArgumentCaptor.forClass(FtctlDrStatusCommand.class);
        Mockito.verify(agentManager, Mockito.times(2)).easySend(Mockito.eq(103L), commandCaptor.capture());
        Assert.assertEquals(FtctlDrStatusCommand.StatusScope.PLAN_AUTHORITY,
                commandCaptor.getAllValues().get(0).getStatusScope());
        Assert.assertEquals(FtctlDrStatusCommand.StatusScope.OPERATION,
                commandCaptor.getAllValues().get(1).getStatusScope());
    }

    @Test
    public void remoteKvmTestCleanupProjectsTargetWithoutSourceAuthority() {
        DrPlanVO plan = new DrPlanVO("remote-kvm-test-cleanup", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setCoordinatorWorkerHostId(103L);
        plan.setActiveSide("SOURCE");
        Mockito.when(drWorkerPlacementService.resolveWorkerHostId(plan, DrWorkerRole.TARGET)).thenReturn(103L);
        DrRunVO run = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_TEST_CLEANUP);
        run.setState(DrConstants.RUN_STATE_ACCEPTED);

        Mockito.when(drRunDao.findActiveByPlanId(plan.getId())).thenReturn(run);
        Mockito.when(drRemoteAgentClient.isRemoteKvmSource(plan)).thenReturn(true);
        Mockito.when(agentManager.easySend(Mockito.eq(103L), Mockito.any(FtctlDrStatusCommand.class)))
                .thenAnswer(invocation -> {
                    FtctlDrStatusCommand command = invocation.getArgument(1);
                    FtctlDrStatusAnswer answer = new FtctlDrStatusAnswer(command, true, "ok", plan.getUuid(),
                            run.getUuid(), "ok", "CLEANED", "target-operation", 100, null, null, null,
                            null, null, 0, "", "{\"state\":\"CLEANED\",\"worker_state\":\"SUCCEEDED\"}");
                    answer.setStatusScope(command.getStatusScope().name());
                    return answer;
                });

        DrAdapterResult result = adapter.refreshPlanProjection(plan);

        Assert.assertTrue(result.isSuccess());
        Mockito.verify(agentManager, Mockito.times(2))
                .easySend(Mockito.eq(103L), Mockito.any(FtctlDrStatusCommand.class));
        Mockito.verify(drRemoteAgentClient, Mockito.never()).execute(Mockito.eq(plan), Mockito.eq("STATUS"),
                Mockito.argThat(command -> command instanceof FtctlDrStatusCommand
                        && ((FtctlDrStatusCommand) command).getStatusScope()
                                == FtctlDrStatusCommand.StatusScope.PLAN_AUTHORITY),
                Mockito.isNull(),
                Mockito.eq(FtctlDrStatusAnswer.class));
    }

    @Test
    public void refreshPlanProjectionRetainsLastGoodDataForMixedCompletedCycleGeneration() {
        DrPlanVO plan = new DrPlanVO("ftctl-dr-plan", 1L, 2L, DrConstants.DIRECTION_VMWARE_TO_KVM);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setCoordinatorWorkerHostId(103L);

        Mockito.when(agentManager.easySend(Mockito.eq(103L), Mockito.any(FtctlDrStatusCommand.class))).thenAnswer(invocation -> {
            FtctlDrStatusCommand command = invocation.getArgument(1);
            FtctlDrStatusAnswer answer = new FtctlDrStatusAnswer(command, true, "ok", plan.getUuid(), null,
                    "ok", "READY", "scheduler-completed", 100, null, null, null,
                    null, null, 0, "", "{}");
            answer.setLatestCompletedCheckpointSequence(7L);
            answer.setLatestCompletedCheckpointState("READY");
            answer.setLatestCompletedCycleToken(plan.getUuid() + ":7");
            answer.setLatestCompletedBaselineGeneration(8L);
            answer.setLatestCompletedChangedBytes(0L);
            answer.setLatestCompletedTargetWrittenBytes(0L);
            answer.setLatestCompletedEffectiveMode("NO_CHANGE");
            return answer;
        });

        DrAdapterResult result = adapter.refreshPlanProjection(plan);

        Assert.assertFalse(result.isSuccess());
        Assert.assertTrue(result.isRetryable());
        Assert.assertEquals("DR_STATUS_CYCLE_SNAPSHOT_INCOHERENT", result.getErrorCode());
        ArgumentCaptor<DrPlanRuntimeVO> runtime = ArgumentCaptor.forClass(DrPlanRuntimeVO.class);
        Mockito.verify(drPlanRuntimeDao).persist(runtime.capture());
        Assert.assertEquals("INCONSISTENT", runtime.getValue().getProjectionIntegrityState());
        Assert.assertEquals(Long.valueOf(7L), runtime.getValue().getProjectionIntegritySequence());
        Mockito.verify(drSyncCycleDao, Mockito.never()).persist(Mockito.any());
        Mockito.verify(drRestorePointDao, Mockito.never()).persist(Mockito.any());
    }

    @Test
    public void refreshPlanProjectionPersistsFtctlCheckpointRestorePoint() {
        DrPlanVO plan = new DrPlanVO("ftctl-dr-plan", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setState(DrConstants.PLAN_STATE_SYNCING);
        plan.setCoordinatorWorkerHostId(103L);
        DrReplicaVO replica = new DrReplicaVO(plan.getId(), plan.getTargetSiteId());
        replica.setTargetVmId(9L);

        Mockito.when(agentManager.easySend(Mockito.eq(103L), Mockito.any(FtctlDrStatusCommand.class))).thenAnswer(invocation -> {
            FtctlDrStatusCommand command = invocation.getArgument(1);
            FtctlDrStatusAnswer answer = new FtctlDrStatusAnswer(command, true, "ok", plan.getUuid(), null,
                    "ok", "READY", "scheduler-completed", 100,
                    "2026-07-01T01:05:00Z", "2026-07-01T01:05:02Z", 2,
                    9L, null, 0, "", "{\"current_checkpoint_sequence\":3,\"current_checkpoint_state\":\"TRANSFERRING\","
                            + "\"latest_completed_checkpoint_sequence\":2,\"latest_completed_checkpoint_ref\":\"ftctl:"
                            + plan.getUuid() + ":2\",\"latest_completed_checkpoint_state\":\"READY\","
                            + "\"latest_completed_target_durable_at\":\"2026-07-01T01:05:02Z\"}");
            answer.setCurrentCheckpointSequence(3L);
            answer.setCurrentCheckpointState("TRANSFERRING");
            answer.setLatestCompletedCheckpointSequence(2L);
            answer.setLatestCompletedCheckpointRef("ftctl:" + plan.getUuid() + ":2");
            answer.setLatestCompletedCheckpointState("READY");
            answer.setLatestCompletedProducerRunUuid("sync-producer-run");
            answer.setLatestCompletedSourceCheckpointAt("2026-07-01T01:05:00Z");
            answer.setLatestCompletedTargetDurableAt("2026-07-01T01:05:02Z");
            answer.setLatestCompletedTargetReadyRpoSeconds(2);
            answer.setLatestCompletedEffectiveMode("CBT_INCREMENTAL");
            answer.setLatestCompletedIncrementalVerified(true);
            answer.setLatestCompletedMetricsEstimated(false);
            answer.setLatestCompletedVirtualBytes(107374182400L);
            answer.setLatestCompletedChangedBytes(131072L);
            answer.setLatestCompletedSourceReadBytes(131072L);
            answer.setLatestCompletedTargetWrittenBytes(131072L);
            answer.setLatestCompletedTransferPayloadBytes(131072L);
            answer.setLatestCompletedChangedExtentCount(2L);
            answer.setLatestCompletedDurationMs(25L);
            answer.setLatestCompletedThroughputBps(5242880L);
            answer.setLatestCompletedNbdTeardownState("DRAINED");
            answer.setLatestCompletedNbdTeardownStartedAtEpochMs(1782867901000L);
            answer.setLatestCompletedNbdTeardownCompletedAtEpochMs(1782867902000L);
            answer.setLatestCompletedNbdTeardownDurationMs(1000L);
            answer.setLatestCompletedNbdSourceDeviceCount(1);
            answer.setLatestCompletedNbdTargetDeviceCount(1);
            answer.setLatestCompletedNbdQuarantinedDeviceCount(0);
            answer.setLatestCompletedBaselineGeneration(2L);
            answer.setLatestCompletedCycleToken(plan.getUuid() + ":2");
            return answer;
        });
        Mockito.when(drRestorePointDao.findByPlanIdAndSourceSnapshotRef(plan.getId(), "ftctl:" + plan.getUuid() + ":2")).thenReturn(null);
        Mockito.when(drPlanDao.acquireInLockTable(plan.getId(), 10)).thenReturn(plan);
        Mockito.when(drReplicaDao.listActiveByPlanId(plan.getId())).thenReturn(Collections.singletonList(replica));

        DrAdapterResult result = adapter.refreshPlanProjection(plan);

        Assert.assertTrue(result.isSuccess());
        Assert.assertEquals(DrConstants.PLAN_STATE_READY, plan.getState());
        Assert.assertNotNull(plan.getTargetReadyAt());
        Assert.assertEquals(Integer.valueOf(2), plan.getTargetReadyRpoSeconds());
        Mockito.verify(drPlanDao, Mockito.atLeastOnce()).update(Mockito.eq(plan.getId()), Mockito.same(plan));

        ArgumentCaptor<DrRestorePointVO> restorePointCaptor = ArgumentCaptor.forClass(DrRestorePointVO.class);
        Mockito.verify(drRestorePointDao).persist(restorePointCaptor.capture());
        DrRestorePointVO restorePoint = restorePointCaptor.getValue();
        Assert.assertEquals(plan.getId(), restorePoint.getPlanId());
        Assert.assertEquals("ftctl:" + plan.getUuid() + ":2", restorePoint.getSourceSnapshotRef());
        Assert.assertEquals("FTCTL_DR_CHECKPOINT", restorePoint.getRestorePointType());
        Assert.assertEquals("READY", restorePoint.getState());
        Assert.assertNotNull(restorePoint.getTargetReadyAt());
        Assert.assertEquals("CBT_INCREMENTAL", restorePoint.getEffectiveMode());
        Assert.assertEquals(Boolean.TRUE, restorePoint.getIncrementalVerified());
        Assert.assertEquals(Boolean.FALSE, restorePoint.getMetricsEstimated());
        Assert.assertEquals(Long.valueOf(131072L), restorePoint.getChangedBytes());
        Assert.assertEquals(Long.valueOf(131072L), restorePoint.getTransferPayloadBytes());
        Assert.assertEquals(Long.valueOf(2L), restorePoint.getChangedExtentCount());
        Assert.assertEquals(plan.getUuid() + ":2", restorePoint.getCycleToken());
        ArgumentCaptor<DrSyncCycleVO> cycleCaptor = ArgumentCaptor.forClass(DrSyncCycleVO.class);
        Mockito.verify(drSyncCycleDao, Mockito.atLeastOnce()).persist(cycleCaptor.capture());
        DrSyncCycleVO completedCycle = cycleCaptor.getAllValues().stream()
                .filter(cycle -> cycle.getSequence() == 2L)
                .findFirst().orElseThrow(AssertionError::new);
        Assert.assertEquals("DRAINED", completedCycle.getNbdTeardownState());
        Assert.assertEquals(Integer.valueOf(1), completedCycle.getNbdSourceDeviceCount());
        Assert.assertEquals(Integer.valueOf(1), completedCycle.getNbdTargetDeviceCount());
        Assert.assertEquals(0, completedCycle.getNbdQuarantinedDeviceCount());

        Mockito.verify(drEventDao, Mockito.never()).persist(Mockito.argThat(event ->
                event != null && DrConstants.EVENT_PROJECTION_REFRESH.equals(event.getEventType())));
    }

    @Test
    public void refreshPlanProjectionRejectsIncrementalCheckpointWithoutNbdDrainEvidence() {
        DrPlanVO plan = new DrPlanVO("ftctl-dr-plan", 1L, 2L, DrConstants.DIRECTION_VMWARE_TO_KVM);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setCoordinatorWorkerHostId(103L);

        Mockito.when(agentManager.easySend(Mockito.eq(103L), Mockito.any(FtctlDrStatusCommand.class)))
                .thenAnswer(invocation -> {
                    FtctlDrStatusCommand command = invocation.getArgument(1);
                    FtctlDrStatusAnswer answer = new FtctlDrStatusAnswer(command, true, "ok", plan.getUuid(), null,
                            "ok", "READY", "scheduler-completed", 100, null, null, null,
                            null, null, 0, "", "{}");
                    answer.setLatestCompletedCheckpointSequence(3L);
                    answer.setLatestCompletedCheckpointState("READY");
                    answer.setLatestCompletedCycleToken(plan.getUuid() + ":3");
                    answer.setLatestCompletedBaselineGeneration(3L);
                    answer.setLatestCompletedEffectiveMode("CBT_INCREMENTAL");
                    answer.setLatestCompletedIncrementalVerified(true);
                    answer.setLatestCompletedChangedBytes(4096L);
                    answer.setLatestCompletedTargetWrittenBytes(4096L);
                    return answer;
                });

        DrAdapterResult result = adapter.refreshPlanProjection(plan);

        Assert.assertFalse(result.isSuccess());
        Assert.assertEquals("DR_STATUS_CYCLE_SNAPSHOT_INCOHERENT", result.getErrorCode());
        Mockito.verify(drSyncCycleDao, Mockito.never()).persist(Mockito.any());
    }

    @Test
    public void refreshPlanProjectionMarksPlanAndReplicaFailedOver() {
        DrPlanVO plan = new DrPlanVO("ftctl-dr-plan", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setState(DrConstants.PLAN_STATE_READY);
        plan.setActiveSide("SOURCE");
        plan.setCoordinatorWorkerHostId(103L);
        DrReplicaVO replica = new DrReplicaVO(plan.getId(), plan.getTargetSiteId());
        replica.setState(DrConstants.REPLICA_STATE_READY);
        replica.setActiveSide("SOURCE");
        replica.setPowerState(DrConstants.REPLICA_POWER_STATE_POWERED_OFF);

        String statusJson = "{\"state\":\"FAILED_OVER\",\"active_side\":\"TARGET\",\"failover_session_id\":\""
                + plan.getUuid() + ":run-failover\",\"failover_mode\":\"planned\",\"failover_restore_point_ref\":\"ftctl:"
                + plan.getUuid() + ":3\",\"checkpoint_sequence\":3,\"target_power_state\":\"POWER_ON_DELEGATED\","
                + "\"target_promotion_state\":\"PROMOTED\",\"rto_actual_seconds\":4}";
        Mockito.when(agentManager.easySend(Mockito.eq(103L), Mockito.any(FtctlDrStatusCommand.class))).thenAnswer(invocation -> {
            FtctlDrStatusCommand command = invocation.getArgument(1);
            return new FtctlDrStatusAnswer(command, true, "ok", plan.getUuid(), null,
                    "ok", "FAILED_OVER", "active-side-switch", 100,
                    "2026-07-01T01:10:00Z", "2026-07-01T01:10:03Z", 3,
                    10L, null, 0, "", statusJson);
        });
        Mockito.when(drReplicaDao.listActiveByPlanId(plan.getId())).thenReturn(Collections.singletonList(replica));
        DrAdapterResult result = adapter.refreshPlanProjection(plan);

        Assert.assertTrue(result.isSuccess());
        Assert.assertEquals(DrConstants.PLAN_STATE_FAILED_OVER, plan.getState());
        Assert.assertEquals("TARGET", plan.getActiveSide());
        Assert.assertEquals(DrConstants.REPLICA_STATE_FAILED_OVER, replica.getState());
        Assert.assertEquals("TARGET", replica.getActiveSide());
        Assert.assertEquals("POWER_ON_DELEGATED", replica.getPowerState());
        Assert.assertTrue(replica.getRuntimeStateJson().contains("\"failover_session_id\""));
        Assert.assertTrue(result.getDetailsJson().contains("\"failoverMode\":\"planned\""));

        Mockito.verify(drPlanDao).update(Mockito.eq(plan.getId()), Mockito.same(plan));
        Mockito.verify(drReplicaDao).update(Mockito.eq(replica.getId()), Mockito.same(replica));
        Mockito.verify(drEventDao, Mockito.never()).persist(Mockito.any(DrEventVO.class));
    }

    @Test
    public void refreshPlanProjectionMarksPlanAndReplicaReadyAfterFailback() {
        DrPlanVO plan = new DrPlanVO("ftctl-dr-plan", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setState(DrConstants.PLAN_STATE_FAILED_OVER);
        plan.setActiveSide("TARGET");
        plan.setCoordinatorWorkerHostId(103L);
        DrReplicaVO replica = new DrReplicaVO(plan.getId(), plan.getTargetSiteId());
        replica.setState(DrConstants.REPLICA_STATE_FAILED_OVER);
        replica.setActiveSide("TARGET");
        replica.setPowerState("POWER_ON_DELEGATED");
        replica.setTargetVmId(9L);

        String statusJson = "{\"state\":\"READY\",\"active_side\":\"SOURCE\",\"failback_session_id\":\""
                + plan.getUuid() + ":run-failback\",\"failback_restore_point_ref\":\"ftctl:"
                + plan.getUuid() + ":4\",\"checkpoint_sequence\":4,\"source_power_state\":\"POWER_ON_DELEGATED\","
                + "\"source_promotion_state\":\"PROMOTED\",\"reverse_direction\":\"KVM_TO_KVM\","
                + "\"rto_actual_seconds\":7}";
        Mockito.when(agentManager.easySend(Mockito.eq(103L), Mockito.any(FtctlDrStatusCommand.class))).thenAnswer(invocation -> {
            FtctlDrStatusCommand command = invocation.getArgument(1);
            return new FtctlDrStatusAnswer(command, true, "ok", plan.getUuid(), null,
                    "ok", "READY", "active-side-restore", 100,
                    "2026-07-01T01:20:00Z", "2026-07-01T01:20:02Z", 2,
                    11L, null, 0, "", statusJson);
        });
        DrFailbackSessionVO failbackSession = new DrFailbackSessionVO(
                plan.getId(), 0L, plan.getUuid() + ":run-failback", "COMPLETED");
        failbackSession.setCheckpointSequence(4L);
        failbackSession.setPostFailbackCheckpointSequence(5L);
        failbackSession.setTargetPowerState("POWERED_OFF");
        failbackSession.setSourcePowerState("POWERED_ON");
        failbackSession.setBootValidationState("POWER_STATE_VALIDATED");
        failbackSession.setEngineAckState("ACKNOWLEDGED");
        Mockito.when(drFailbackLifecycleService.reconcile(
                Mockito.eq(plan), Mockito.isNull(), Mockito.any(JsonObject.class)))
                .thenReturn(failbackSession);
        Mockito.when(drReplicaDao.listActiveByPlanId(plan.getId())).thenReturn(Collections.singletonList(replica));

        DrAdapterResult result = adapter.refreshPlanProjection(plan);

        Assert.assertTrue(result.isSuccess());
        Assert.assertEquals(DrConstants.PLAN_STATE_READY, plan.getState());
        Assert.assertEquals("SOURCE", plan.getActiveSide());
        Assert.assertEquals(DrConstants.REPLICA_STATE_READY, replica.getState());
        Assert.assertEquals("SOURCE", replica.getActiveSide());
        Assert.assertEquals(DrConstants.REPLICA_POWER_STATE_POWERED_OFF, replica.getPowerState());
        Assert.assertTrue(replica.getRuntimeStateJson().contains("\"failback_session_id\""));
        Assert.assertTrue(result.getDetailsJson().contains("\"failbackSessionId\""));

        Mockito.verify(drPlanDao).update(Mockito.eq(plan.getId()), Mockito.same(plan));
        Mockito.verify(drReplicaDao).update(Mockito.eq(replica.getId()), Mockito.same(replica));
        Mockito.verify(drEventDao, Mockito.never()).persist(Mockito.any(DrEventVO.class));
    }

    @Test
    public void refreshPlanProjectionKeepsTargetActiveAfterReprotect() {
        DrPlanVO plan = new DrPlanVO("ftctl-dr-plan", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setState(DrConstants.PLAN_STATE_FAILED_OVER);
        plan.setActiveSide("TARGET");
        plan.setCoordinatorWorkerHostId(103L);
        DrReplicaVO replica = new DrReplicaVO(plan.getId(), plan.getTargetSiteId());
        replica.setState(DrConstants.REPLICA_STATE_FAILED_OVER);
        replica.setActiveSide("TARGET");
        replica.setPowerState("POWER_ON_DELEGATED");
        replica.setTargetVmId(9L);

        String statusJson = "{\"state\":\"READY\",\"active_side\":\"TARGET\",\"reprotect_session_id\":\""
                + plan.getUuid() + ":run-reprotect\",\"reprotect_restore_point_ref\":\"ftctl:"
                + plan.getUuid() + ":6\",\"checkpoint_sequence\":6,\"target_power_state\":\"POWER_ON_DELEGATED\","
                + "\"target_promotion_state\":\"PROMOTED\",\"reverse_direction\":\"KVM_TO_KVM\","
                + "\"rto_actual_seconds\":5}";
        Mockito.when(agentManager.easySend(Mockito.eq(103L), Mockito.any(FtctlDrStatusCommand.class))).thenAnswer(invocation -> {
            FtctlDrStatusCommand command = invocation.getArgument(1);
            return new FtctlDrStatusAnswer(command, true, "ok", plan.getUuid(), null,
                    "ok", "READY", "reprotect-ready", 100,
                    "2026-07-01T02:20:00Z", "2026-07-01T02:20:03Z", 3,
                    12L, null, 0, "", statusJson);
        });
        Mockito.when(drReplicaDao.listActiveByPlanId(plan.getId())).thenReturn(Collections.singletonList(replica));

        DrAdapterResult result = adapter.refreshPlanProjection(plan);

        Assert.assertTrue(result.isSuccess());
        Assert.assertEquals(DrConstants.PLAN_STATE_READY, plan.getState());
        Assert.assertEquals("TARGET", plan.getActiveSide());
        Assert.assertEquals(DrConstants.REPLICA_STATE_READY, replica.getState());
        Assert.assertEquals("TARGET", replica.getActiveSide());
        Assert.assertEquals("POWER_ON_DELEGATED", replica.getPowerState());
        Assert.assertTrue(replica.getRuntimeStateJson().contains("\"reprotect_session_id\""));
        Assert.assertTrue(result.getDetailsJson().contains("\"reprotectSessionId\""));

        Mockito.verify(drPlanDao, Mockito.atLeastOnce()).update(Mockito.eq(plan.getId()), Mockito.same(plan));
        Mockito.verify(drReplicaDao).update(Mockito.eq(replica.getId()), Mockito.same(replica));
        Mockito.verify(drEventDao, Mockito.never()).persist(Mockito.any(DrEventVO.class));
    }

    @Test
    public void completedRemoteKvmReprotectIsNotRegressedByCommittedTargetAuthority() {
        DrPlanVO plan = new DrPlanVO("remote-kvm-reprotected", 1L, 2L,
                DrConstants.DIRECTION_KVM_TO_KVM);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setState(DrConstants.PLAN_STATE_FAILED_OVER);
        plan.setActiveSide("TARGET");
        plan.setCoordinatorWorkerHostId(103L);

        DrCutoverSessionVO cutover = new DrCutoverSessionVO(plan.getId(), 41L,
                DrConstants.RUN_TYPE_FAILOVER, DrConstants.PLAN_STATE_FAILED_OVER);
        cutover.setCloudPromotionState("PROMOTED");
        cutover.setEngineAckState("ACKNOWLEDGED");
        cutover.setCloudAuthorityGeneration(112L);
        DrPlanRuntimeVO authority = new DrPlanRuntimeVO(plan.getId());
        authority.setProtectionState("FAILED_OVER_UNPROTECTED");
        authority.setAuthoritySequence(389L);
        DrReplicaVO replica = new DrReplicaVO(plan.getId(), plan.getTargetSiteId());
        replica.setState(DrConstants.REPLICA_STATE_FAILED_OVER);
        replica.setActiveSide("TARGET");
        replica.setPowerState("POWERED_ON");
        replica.setTargetVmId(165L);

        String sessionId = plan.getUuid() + ":run-reprotect";
        String statusJson = "{\"action\":\"dr-reprotect\",\"state\":\"READY\","
                + "\"step\":\"reprotect-ready\",\"protection_state\":\"READY\","
                + "\"active_side\":\"TARGET\",\"baseline_state\":\"LOCAL_DURABLE\","
                + "\"checkpoint_sequence\":113,\"reprotect_session_id\":\"" + sessionId + "\","
                + "\"reprotect_restore_point_ref\":\"ftctl:" + plan.getUuid() + ":113\","
                + "\"target_power_state\":\"POWERED_ON\",\"target_promotion_state\":\"PROMOTED\"}";
        Mockito.when(agentManager.easySend(Mockito.eq(103L), Mockito.any(FtctlDrStatusCommand.class)))
                .thenAnswer(invocation -> {
                    FtctlDrStatusCommand command = invocation.getArgument(1);
                    FtctlDrStatusAnswer answer = new FtctlDrStatusAnswer(command, true, "ok", plan.getUuid(),
                            null, "ok", "READY", "reprotect-ready", 100,
                            "2026-08-30T14:48:58Z", "2026-08-30T14:54:17Z", 4,
                            389L, null, 0, "", statusJson);
                    answer.setProtectionState("READY");
                    answer.setBaselineState("LOCAL_DURABLE");
                    answer.setLatestCompletedCheckpointSequence(113L);
                    return answer;
                });
        Mockito.when(drRemoteAgentClient.isRemoteKvmSource(plan)).thenReturn(true);
        Mockito.when(drCutoverSessionDao.findCurrentAuthorityByPlanId(plan.getId())).thenReturn(cutover);
        Mockito.when(drPlanRuntimeDao.findByPlanId(plan.getId())).thenReturn(authority);
        Mockito.when(drReplicaDao.listActiveByPlanId(plan.getId())).thenReturn(Collections.singletonList(replica));

        DrAdapterResult result = adapter.refreshPlanProjection(plan);

        Assert.assertTrue(result.isSuccess());
        Assert.assertEquals(DrConstants.PLAN_STATE_READY, plan.getState());
        Assert.assertEquals("TARGET", plan.getActiveSide());
        Assert.assertEquals(DrConstants.PLAN_STATE_READY, authority.getProtectionState());
        Assert.assertEquals(DrConstants.REPLICA_STATE_READY, replica.getState());
        Assert.assertEquals("TARGET", replica.getActiveSide());
        Mockito.verify(drPlanDao, Mockito.atLeastOnce()).update(Mockito.eq(plan.getId()), Mockito.same(plan));
        Mockito.verify(drReplicaDao).update(Mockito.eq(replica.getId()), Mockito.same(replica));
    }

    @Test
    public void healthyReverseSchedulerKeepsCommittedTargetProtectedAfterNextCycle() {
        DrPlanVO plan = new DrPlanVO("remote-kvm-reverse-live", 1L, 2L,
                DrConstants.DIRECTION_KVM_TO_KVM);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setState(DrConstants.PLAN_STATE_READY);
        plan.setActiveSide("TARGET");
        plan.setCoordinatorWorkerHostId(103L);

        DrCutoverSessionVO cutover = new DrCutoverSessionVO(plan.getId(), 41L,
                DrConstants.RUN_TYPE_FAILOVER, DrConstants.PLAN_STATE_FAILED_OVER);
        cutover.setCloudPromotionState("PROMOTED");
        cutover.setEngineAckState("ACKNOWLEDGED");
        DrPlanRuntimeVO authority = new DrPlanRuntimeVO(plan.getId());
        authority.setProtectionState("FAILED_OVER_UNPROTECTED");
        authority.setSchedulerState("STOPPED");
        authority.setSchedulerHealthState("SUPPRESSED");
        authority.setSchedulerPidAlive(false);
        authority.setOwnerMatched(false);
        authority.setAuthoritySequence(113L);
        DrReplicaVO replica = new DrReplicaVO(plan.getId(), plan.getTargetSiteId());
        replica.setState(DrConstants.REPLICA_STATE_READY);
        replica.setActiveSide("TARGET");
        replica.setPowerState("POWERED_ON");
        replica.setTargetVmId(165L);

        String statusJson = "{\"action\":\"dr-scheduler-run\",\"state\":\"READY\","
                + "\"step\":\"target-checkpoint-ready\",\"protection_state\":\"READY\","
                + "\"active_side\":\"TARGET\",\"scheduler_state\":\"RUNNING\","
                + "\"scheduler_health\":\"HEALTHY\",\"scheduler_pid_alive\":true,"
                + "\"owner_matched\":true,\"baseline_state\":\"LOCAL_DURABLE\","
                + "\"latest_completed_checkpoint_sequence\":114}";
        Mockito.when(agentManager.easySend(Mockito.eq(103L), Mockito.any(FtctlDrStatusCommand.class)))
                .thenAnswer(invocation -> {
                    FtctlDrStatusCommand command = invocation.getArgument(1);
                    FtctlDrStatusAnswer answer = new FtctlDrStatusAnswer(command, true, "ok", plan.getUuid(),
                            null, "ok", "READY", "target-checkpoint-ready", 100,
                            "2026-08-31T00:10:00Z", "2026-08-31T00:10:03Z", 3,
                            114L, null, 0, "", statusJson);
                    answer.setProtectionState("READY");
                    answer.setSchedulerHealth("HEALTHY");
                    answer.setSchedulerPidAlive(true);
                    answer.setOwnerMatched(true);
                    answer.setBaselineState("LOCAL_DURABLE");
                    answer.setLatestCompletedCheckpointSequence(114L);
                    return answer;
                });
        Mockito.when(drRemoteAgentClient.isRemoteKvmSource(plan)).thenReturn(true);
        Mockito.when(drCutoverSessionDao.findCurrentAuthorityByPlanId(plan.getId())).thenReturn(cutover);
        Mockito.when(drPlanRuntimeDao.findByPlanId(plan.getId())).thenReturn(authority);
        Mockito.when(drReplicaDao.listActiveByPlanId(plan.getId())).thenReturn(Collections.singletonList(replica));

        DrAdapterResult result = adapter.refreshPlanProjection(plan);

        Assert.assertTrue(result.isSuccess());
        Assert.assertEquals(DrConstants.PLAN_STATE_READY, plan.getState());
        Assert.assertEquals(DrConstants.PLAN_STATE_READY, authority.getProtectionState());
        Assert.assertEquals("RUNNING", authority.getSchedulerState());
        Assert.assertEquals("HEALTHY", authority.getSchedulerHealthState());
        Assert.assertTrue(authority.isSchedulerPidAlive());
        Assert.assertTrue(authority.isOwnerMatched());
        Assert.assertEquals("TARGET", plan.getActiveSide());
    }

    @Test
    public void authoritativeReprotectCompletesRunWhileRpoHealthRemainsDegraded() {
        DrPlanVO plan = new DrPlanVO("ftctl-dr-reprotect-overdue", 1L, 2L,
                DrConstants.DIRECTION_VMWARE_TO_KVM);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setState(DrConstants.PLAN_STATE_FAILED_OVER);
        plan.setActiveSide("TARGET");
        plan.setCoordinatorWorkerHostId(103L);
        plan.setRpoSeconds(300);

        DrRunVO run = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_REPROTECT);
        run.setState(DrConstants.RUN_STATE_ACCEPTED);
        DrReplicaVO replica = new DrReplicaVO(plan.getId(), plan.getTargetSiteId());
        replica.setState(DrConstants.REPLICA_STATE_FAILED_OVER);
        replica.setActiveSide("TARGET");
        replica.setPowerState("POWER_ON_DELEGATED");
        replica.setTargetVmId(9L);
        DrPlanRuntimeVO authority = new DrPlanRuntimeVO(plan.getId());

        String statusJson = "{\"action\":\"dr-reprotect\",\"state\":\"READY\",\"step\":\"reprotect-ready\","
                + "\"protection_state\":\"READY\",\"active_side\":\"TARGET\","
                + "\"control_request_run_uuid\":\"" + run.getUuid() + "\","
                + "\"terminal_source\":\"ENGINE_TERMINAL\",\"terminal_authoritative\":true,"
                + "\"runtime_endpoints_drained\":true,\"worker_state\":\"TERMINAL_PUBLISHED\","
                + "\"baseline_state\":\"LOCAL_DURABLE\",\"reprotect_session_id\":\""
                + plan.getUuid() + ":" + run.getUuid() + "\","
                + "\"reprotect_completed_at\":\"2026-08-23T13:17:22+09:00\","
                + "\"reprotect_manifest_path\":\"/run/reprotect/manifest.json\","
                + "\"reprotect_checkpoint_path\":\"/run/reprotect/checkpoint.json\","
                + "\"latest_completed_checkpoint_sequence\":2460,"
                + "\"latest_completed_checkpoint_state\":\"READY\"}";
        Mockito.when(agentManager.easySend(Mockito.eq(103L), Mockito.any(FtctlDrStatusCommand.class)))
                .thenAnswer(invocation -> {
                    FtctlDrStatusCommand command = invocation.getArgument(1);
                    FtctlDrStatusAnswer answer = new FtctlDrStatusAnswer(command, true, "ok", plan.getUuid(),
                            run.getUuid(), "ok", "READY", "reprotect-ready", 100,
                            "2026-08-23T04:17:21Z", "2026-08-23T04:17:21Z", 986,
                            4850L, null, 0, "", statusJson);
                    answer.setProtectionState("READY");
                    answer.setControlRequestRunUuid(run.getUuid());
                    answer.setTerminalSource("ENGINE_TERMINAL");
                    answer.setTerminalAuthoritative(true);
                    answer.setRuntimeEndpointsDrained(true);
                    answer.setLatestCompletedCheckpointSequence(2460L);
                    answer.setLatestCompletedCheckpointState("READY");
                    return answer;
                });
        Mockito.when(drRunDao.findActiveByPlanId(plan.getId())).thenReturn(run);
        Mockito.when(drPlanRuntimeDao.findByPlanId(plan.getId())).thenReturn(authority);
        Mockito.when(drReplicaDao.listActiveByPlanId(plan.getId())).thenReturn(Collections.singletonList(replica));

        DrAdapterResult result = adapter.refreshPlanProjection(plan);

        Assert.assertTrue(result.isSuccess());
        Assert.assertEquals(DrConstants.RUN_STATE_SUCCEEDED, run.getState());
        Assert.assertTrue(run.isTerminalAuthoritative());
        Assert.assertNotNull(run.getCompleted());
        Assert.assertEquals(DrConstants.HEALTH_DEGRADED, plan.getState());
        Assert.assertEquals("TARGET", plan.getActiveSide());
        Assert.assertEquals(DrConstants.REPLICA_STATE_READY, replica.getState());
        Assert.assertEquals("TARGET", replica.getActiveSide());
        Mockito.verify(drRunDao, Mockito.atLeastOnce()).update(Mockito.eq(run.getId()), Mockito.same(run));
    }

    @Test
    public void refreshPlanProjectionCompletesFailoverOnlyAfterCloudPromotionAndEngineAck() {
        DrPlanVO plan = new DrPlanVO("ftctl-dr-plan", 1L, 2L, DrConstants.DIRECTION_VMWARE_TO_KVM);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setState(DrConstants.PLAN_STATE_READY);
        plan.setActiveSide("SOURCE");
        plan.setCoordinatorWorkerHostId(103L);
        plan.setSourceExternalRef("vm-123");
        DrRunVO run = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_FAILOVER);
        run.setState(DrConstants.RUN_STATE_ACCEPTED);
        DrCutoverSessionVO session = new DrCutoverSessionVO(plan.getId(), run.getId(), run.getRunType(), "CUTOVER_READY");
        Date now = new Date();

        String manifestSha256 = String.join("", Collections.nCopies(64, "a"));
        String statusJson = "{\"state\":\"CUTOVER_READY\",\"active_side\":\"SOURCE\",\"failover_session_id\":\""
                + plan.getUuid() + ":" + run.getUuid() + "\",\"failover_restore_point_ref\":\"ftctl:"
                + plan.getUuid() + ":7\",\"checkpoint_sequence\":7,\"guest_prep_state\":\"READY\","
                + "\"guestprep_checkpoint_sequence\":7,\"manifest_schema_version\":\"FTCTL_GUESTPREP_MANIFEST_V2\","
                + "\"manifest_sha256\":\"" + manifestSha256 + "\",\"target_disk_count\":1,"
                + "\"failover_mode\":\"planned\",\"source_fence_state\":\"REQUESTED\","
                + "\"source_power_state\":\"UNKNOWN\"}";
        Mockito.when(agentManager.easySend(Mockito.eq(103L), Mockito.any(FtctlDrStatusCommand.class))).thenAnswer(invocation -> {
            FtctlDrStatusCommand command = invocation.getArgument(1);
            FtctlDrStatusAnswer answer = new FtctlDrStatusAnswer(command, true, "ok", plan.getUuid(), run.getUuid(),
                    "ok", "CUTOVER_READY", "cutover-ready", 95,
                    "2026-07-01T03:10:00Z", "2026-07-01T03:10:02Z", 2,
                    13L, null, 0, "", statusJson);
            answer.setGuestPreparationState("READY");
            answer.setManifestSchemaVersion("FTCTL_GUESTPREP_MANIFEST_V2");
            answer.setManifestSha256(manifestSha256);
            answer.setGuestPreparationCheckpointSequence(7L);
            answer.setLatestCompletedCheckpointSequence(7L);
            answer.setTargetDiskCount(1);
            return answer;
        });
        Mockito.when(agentManager.easySend(Mockito.eq(103L), Mockito.any(FtctlDrActionCommand.class)))
                .thenAnswer(invocation -> new Answer(invocation.getArgument(1), true, "acknowledged"));
        Mockito.when(drRunDao.findActiveByPlanId(plan.getId())).thenReturn(run);
        Mockito.when(drCutoverSessionDao.findActiveByRunId(run.getId())).thenReturn(session);
        DrSiteVO sourceSite = new DrSiteVO("VMware source", "VMWARE_DIRECT", "VMWARE");
        DrResolvedSiteCredential sourceCredential = Mockito.mock(DrResolvedSiteCredential.class);
        Mockito.when(drSiteDao.findById(plan.getSourceSiteId())).thenReturn(sourceSite);
        Mockito.when(drSiteCredentialService.resolveCredential(sourceSite)).thenReturn(sourceCredential);
        Mockito.when(drVmwareInventoryClient.ensureVirtualMachinePowerState(
                sourceCredential, plan.getSourceExternalRef(), false)).thenReturn("POWERED_OFF");
        Mockito.when(drTargetMaterializationService.ensureTargetPoweredOn(plan.getId()))
                .thenReturn(new DrTargetPowerOnResult(91L, "target-uuid", "POWERED_ON",
                        "POWER_STATE_VALIDATED", now, now, false));

        DrAdapterResult result = adapter.refreshPlanProjection(plan);

        Assert.assertTrue(result.isSuccess());
        Assert.assertEquals(DrConstants.PLAN_STATE_FAILED_OVER, plan.getState());
        Assert.assertEquals("TARGET", plan.getActiveSide());
        Assert.assertEquals("PROMOTED", session.getCloudPromotionState());
        Assert.assertEquals("POWERED_ON", session.getTargetPowerState());
        Assert.assertEquals("ACKNOWLEDGED", session.getEngineAckState());
        Assert.assertNotNull(session.getCompletedAt());
        Assert.assertEquals(DrConstants.RUN_STATE_SUCCEEDED, run.getState());
        Assert.assertEquals("completed", run.getCurrentStepName());
        Assert.assertNull(run.getErrorCode());
        Assert.assertNotNull(run.getCompleted());

        ArgumentCaptor<FtctlDrActionCommand> commandCaptor = ArgumentCaptor.forClass(FtctlDrActionCommand.class);
        Mockito.verify(agentManager).easySend(Mockito.eq(103L), commandCaptor.capture());
        FtctlDrActionCommand commit = commandCaptor.getValue();
        Assert.assertEquals("DR_CUTOVER_COMMIT_V2", commit.getCutoverCommitContractVersion());
        Assert.assertEquals(plan.getUuid() + ":" + run.getUuid(), commit.getCutoverEngineSessionId());
        Assert.assertEquals(session.getUuid(), commit.getCutoverCloudSessionId());
        Assert.assertEquals(Long.valueOf(7L), commit.getCutoverCheckpointSequence());
        Assert.assertEquals(Long.valueOf(91L), commit.getCutoverTargetVmId());
        Assert.assertEquals("target-uuid", commit.getCutoverTargetExternalRef());
        Assert.assertEquals("VERIFIED", commit.getCutoverSourceFenceState());
        Assert.assertEquals("POWERED_OFF", commit.getCutoverSourcePowerState());
        Assert.assertEquals(64, commit.getCutoverCommitEnvelopeSha256().length());

        ArgumentCaptor<DrRunStepVO> stepCaptor = ArgumentCaptor.forClass(DrRunStepVO.class);
        Mockito.verify(drRunStepDao, Mockito.atLeast(5)).persist(stepCaptor.capture());
        Assert.assertTrue(stepCaptor.getAllValues().stream()
                .anyMatch(step -> "engine-state-reconciliation".equals(step.getStepName())
                        && DrConstants.STEP_STATE_SUCCEEDED.equals(step.getState())));
        Assert.assertTrue(stepCaptor.getAllValues().stream()
                .anyMatch(step -> "completed".equals(step.getStepName())
                        && Integer.valueOf(100).equals(step.getProgress())));

        Mockito.verify(drRunDao).update(Mockito.eq(run.getId()), Mockito.same(run));
        Mockito.verify(drEventDao, Mockito.atLeastOnce()).persist(Mockito.any(DrEventVO.class));
        org.mockito.InOrder cutoverOrder = Mockito.inOrder(drVmwareInventoryClient,
                drTargetMaterializationService, agentManager);
        cutoverOrder.verify(drVmwareInventoryClient).ensureVirtualMachinePowerState(
                sourceCredential, plan.getSourceExternalRef(), false);
        cutoverOrder.verify(drTargetMaterializationService).ensureTargetPoweredOn(plan.getId());
        cutoverOrder.verify(agentManager).easySend(Mockito.eq(103L), Mockito.any(FtctlDrActionCommand.class));
    }

    @Test
    public void remoteKvmFailoverQuiescesAndPowersOffSourceBeforeTargetCommit() {
        DrPlanVO plan = new DrPlanVO("remote-kvm-cutover", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setState(DrConstants.PLAN_STATE_READY);
        plan.setActiveSide(DrConstants.AUTHORITY_SIDE_SOURCE);
        plan.setCoordinatorWorkerHostId(103L);
        plan.setTargetWorkerHostId(102L);
        plan.setSourceExternalRef("source-vm-uuid");
        plan.setMappingJson("{\"source\":{\"hardware\":{\"sourceHostUuid\":\"source-worker-uuid\"}}}");
        DrRunVO run = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_FAILOVER);
        run.setState(DrConstants.RUN_STATE_ACCEPTED);
        DrCutoverSessionVO session = new DrCutoverSessionVO(plan.getId(), run.getId(),
                run.getRunType(), "CUTOVER_READY");
        String manifestSha256 = String.join("", Collections.nCopies(64, "c"));
        String engineSessionId = plan.getUuid() + ":" + run.getUuid();
        String statusJson = "{\"state\":\"CUTOVER_READY\",\"active_side\":\"SOURCE\","
                + "\"failover_session_id\":\"" + engineSessionId + "\","
                + "\"failover_restore_point_sequence\":12,\"manifest_sha256\":\""
                + manifestSha256 + "\",\"target_vm_id\":283,"
                + "\"target_external_ref\":\"target-vm-uuid\",\"failover_mode\":\"planned\"}";

        Mockito.when(drRemoteAgentClient.isRemoteKvmSource(plan)).thenReturn(true);
        Mockito.when(drRemoteAgentClient.transitionSourceScheduler(Mockito.eq(plan),
                Mockito.eq(FtctlDrActionCommand.Action.PAUSE_SYNC), Mockito.eq(run.getUuid())))
                .thenAnswer(invocation -> {
                    FtctlDrActionCommand command = new FtctlDrActionCommand(
                            FtctlDrActionCommand.Action.PAUSE_SYNC, plan.getUuid(), run.getUuid());
                    return new FtctlDrActionAnswer(command, true, "paused");
                });
        Mockito.when(drRemoteAgentClient.ensureSourceVmPowerState(plan, false)).thenReturn("POWERED_OFF");
        Mockito.when(drRemoteAgentClient.execute(Mockito.eq(plan), Mockito.eq("STATUS"),
                Mockito.any(FtctlDrStatusCommand.class), Mockito.isNull(),
                Mockito.eq(FtctlDrStatusAnswer.class)))
                .thenAnswer(invocation -> {
                    FtctlDrStatusCommand command = invocation.getArgument(2);
                    return new FtctlDrStatusAnswer(command, true, "ok", plan.getUuid(), run.getUuid(),
                            "ok", "CUTOVER_READY", "cutover-ready", 100,
                            "2026-08-24T00:04:57Z", "2026-08-24T00:05:00Z", 3,
                            12L, null, 0, "", statusJson);
                });
        Mockito.when(agentManager.easySend(Mockito.eq(102L), Mockito.any(FtctlDrActionCommand.class)))
                .thenAnswer(invocation -> new Answer(invocation.getArgument(1), true, "target commit acknowledged"));
        Mockito.when(drRemoteAgentClient.execute(Mockito.eq(plan), Mockito.eq("ACTION"),
                Mockito.any(FtctlDrActionCommand.class), Mockito.isNull(),
                Mockito.eq(FtctlDrActionAnswer.class)))
                .thenAnswer(invocation -> new FtctlDrActionAnswer(invocation.getArgument(2), true, "acknowledged"));
        Mockito.when(drRunDao.findActiveByPlanId(plan.getId())).thenReturn(run);
        Mockito.when(drCutoverSessionDao.findActiveByRunId(run.getId())).thenReturn(session);
        Mockito.when(drTargetMaterializationService.ensureTargetPoweredOn(plan.getId()))
                .thenReturn(new DrTargetPowerOnResult(283L, "target-vm-uuid", "POWERED_ON",
                        "POWER_STATE_VALIDATED", new Date(), new Date(), false));

        DrAdapterResult result = adapter.refreshPlanProjection(plan);

        Assert.assertTrue(result.getErrorCode() + ": " + result.getMessage(), result.isSuccess());
        Assert.assertEquals(DrConstants.PLAN_STATE_FAILED_OVER, plan.getState());
        Assert.assertEquals(DrConstants.AUTHORITY_SIDE_TARGET, plan.getActiveSide());
        Assert.assertEquals(Long.valueOf(12L), session.getCheckpointSequence());
        Assert.assertEquals("VERIFIED", session.getSourceFenceState());
        Assert.assertEquals("POWERED_OFF", session.getSourcePowerState());
        Assert.assertEquals("ACKNOWLEDGED", session.getEngineAckState());
        Mockito.verify(drRemoteAgentClient).transitionSourceScheduler(plan,
                FtctlDrActionCommand.Action.PAUSE_SYNC, run.getUuid());

        org.mockito.InOrder cutoverOrder = Mockito.inOrder(drRemoteAgentClient,
                drPlanOwnedTransportService, drTargetMaterializationService, agentManager);
        cutoverOrder.verify(drRemoteAgentClient).transitionSourceScheduler(plan,
                FtctlDrActionCommand.Action.PAUSE_SYNC, run.getUuid());
        cutoverOrder.verify(drRemoteAgentClient).ensureSourceVmPowerState(plan, false);
        cutoverOrder.verify(drPlanOwnedTransportService).stopForwardTargetExport(
                plan, run, null, 12L);
        cutoverOrder.verify(drTargetMaterializationService).ensureTargetPoweredOn(plan.getId());
        ArgumentCaptor<FtctlDrActionCommand> sourceCommandCaptor = ArgumentCaptor.forClass(FtctlDrActionCommand.class);
        cutoverOrder.verify(drRemoteAgentClient).execute(Mockito.eq(plan), Mockito.eq("ACTION"),
                sourceCommandCaptor.capture(),
                Mockito.isNull(), Mockito.eq(FtctlDrActionAnswer.class));
        ArgumentCaptor<FtctlDrActionCommand> targetCommandCaptor = ArgumentCaptor.forClass(FtctlDrActionCommand.class);
        cutoverOrder.verify(agentManager).easySend(Mockito.eq(102L), Mockito.argThat(command ->
                command instanceof FtctlDrActionCommand
                        && ((FtctlDrActionCommand) command).getAction() == FtctlDrActionCommand.Action.CUTOVER_COMMIT
                        && "target".equals(((FtctlDrActionCommand) command).getRole())));
        Mockito.verify(agentManager).easySend(Mockito.eq(102L), targetCommandCaptor.capture());
        Assert.assertEquals(FtctlDrActionCommand.Action.CUTOVER_COMMIT,
                sourceCommandCaptor.getValue().getAction());
        Assert.assertEquals("coordinator", sourceCommandCaptor.getValue().getRole());
        Assert.assertEquals(FtctlDrActionCommand.Action.CUTOVER_COMMIT, targetCommandCaptor.getValue().getAction());
        Assert.assertEquals("target", targetCommandCaptor.getValue().getRole());
        Assert.assertEquals(sourceCommandCaptor.getValue().getAuthoritySequenceFloor(),
                targetCommandCaptor.getValue().getAuthoritySequenceFloor());
        Assert.assertTrue(targetCommandCaptor.getValue().getAuthoritySequenceFloor() >= 12L);
        Mockito.verify(agentManager, Mockito.never()).easySend(Mockito.eq(103L), Mockito.isA(FtctlDrActionCommand.class));
    }

    @Test
    public void sourceCloneFlattenDependencyIsProjectedAsActionableFailoverWait() {
        DrPlanVO plan = new DrPlanVO("remote-kvm-cutover", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        DrRunVO run = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_FAILOVER);
        run.setState(DrConstants.RUN_STATE_RUNNING);
        Mockito.when(drRunDao.findActiveByPlanId(plan.getId())).thenReturn(run);

        adapter.markSourceIsolationWaiting(plan, null,
                new RuntimeException("Unable to stop VM while SharedMountPoint clone flatten is running."));

        Assert.assertEquals(DrConstants.RUN_STATE_RUNNING, run.getState());
        Assert.assertEquals("source-isolation-wait", run.getCurrentStepName());
        Assert.assertEquals(DrConstants.ERROR_SOURCE_CLONE_FLATTEN_ACTIVE, run.getErrorCode());
        Assert.assertTrue(run.isRetryable());
        Assert.assertNull(run.getCompleted());
        ArgumentCaptor<DrRunStepVO> stepCaptor = ArgumentCaptor.forClass(DrRunStepVO.class);
        Mockito.verify(drRunStepDao).persist(stepCaptor.capture());
        Assert.assertEquals("source-isolation", stepCaptor.getValue().getStepName());
        Assert.assertEquals(DrConstants.STEP_STATE_RUNNING, stepCaptor.getValue().getState());
        Assert.assertEquals(DrConstants.ERROR_SOURCE_CLONE_FLATTEN_ACTIVE, stepCaptor.getValue().getErrorCode());
        Mockito.verify(drRunDao).update(Mockito.eq(run.getId()), Mockito.same(run));
    }

    @Test
    public void refreshPlanProjectionKeepsSourceAuthorityWhenCutoverCommitFails() {
        DrPlanVO plan = new DrPlanVO("ftctl-dr-commit-failure", 1L, 2L,
                DrConstants.DIRECTION_VMWARE_TO_KVM);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setState(DrConstants.PLAN_STATE_READY);
        plan.setActiveSide(DrConstants.AUTHORITY_SIDE_SOURCE);
        plan.setCoordinatorWorkerHostId(103L);
        DrRunVO run = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_FAILOVER);
        run.setState(DrConstants.RUN_STATE_ACCEPTED);
        DrCutoverSessionVO session = new DrCutoverSessionVO(plan.getId(), run.getId(),
                run.getRunType(), "CUTOVER_READY");
        String manifestSha256 = String.join("", Collections.nCopies(64, "b"));
        String statusJson = "{\"state\":\"READY\",\"active_side\":\"SOURCE\","
                + "\"failover_session_id\":\"" + plan.getUuid() + ":" + run.getUuid() + "\","
                + "\"checkpoint_sequence\":8,\"guest_prep_state\":\"READY\","
                + "\"guestprep_checkpoint_sequence\":8,"
                + "\"manifest_schema_version\":\"FTCTL_GUESTPREP_MANIFEST_V2\","
                + "\"manifest_sha256\":\"" + manifestSha256 + "\",\"target_disk_count\":1,"
                + "\"source_fence_state\":\"VERIFIED\",\"source_power_state\":\"POWERED_OFF\"}";
        Mockito.when(agentManager.easySend(Mockito.eq(103L), Mockito.any(FtctlDrStatusCommand.class)))
                .thenAnswer(invocation -> {
                    FtctlDrStatusCommand command = invocation.getArgument(1);
                    FtctlDrStatusAnswer answer = new FtctlDrStatusAnswer(command, true, "ok",
                            plan.getUuid(), run.getUuid(), "ok", "CUTOVER_READY", "cutover-ready", 95,
                            "2026-07-01T03:10:00Z", "2026-07-01T03:10:02Z", 2,
                            13L, null, 0, "", statusJson);
                    answer.setGuestPreparationState("READY");
                    answer.setManifestSchemaVersion("FTCTL_GUESTPREP_MANIFEST_V2");
                    answer.setManifestSha256(manifestSha256);
                    answer.setGuestPreparationCheckpointSequence(8L);
                    answer.setLatestCompletedCheckpointSequence(8L);
                    answer.setTargetDiskCount(1);
                    return answer;
                });
        Mockito.when(agentManager.easySend(Mockito.eq(103L), Mockito.any(FtctlDrActionCommand.class)))
                .thenAnswer(invocation -> new Answer(invocation.getArgument(1), false,
                        "DR_CUTOVER_POWER_STATE_INVALID"));
        Mockito.when(drRunDao.findActiveByPlanId(plan.getId())).thenReturn(run);
        Mockito.when(drCutoverSessionDao.findActiveByRunId(run.getId())).thenReturn(session);
        Mockito.when(drTargetMaterializationService.ensureTargetPoweredOn(plan.getId()))
                .thenReturn(new DrTargetPowerOnResult(92L, "target-uuid", "POWERED_ON",
                        "POWER_STATE_VALIDATED", new Date(), new Date(), false));

        DrAdapterResult result = adapter.refreshPlanProjection(plan);

        Assert.assertTrue(result.isSuccess());
        Assert.assertEquals(DrConstants.PLAN_STATE_COMMIT_VERIFYING, plan.getState());
        Assert.assertEquals(DrConstants.AUTHORITY_SIDE_SOURCE, plan.getActiveSide());
        Assert.assertEquals("RETRY_REQUIRED", session.getEngineAckState());
        Assert.assertEquals("DR_CUTOVER_COMMIT_FAILED", session.getErrorCode());
        Assert.assertNotEquals(DrConstants.RUN_STATE_SUCCEEDED, run.getState());
    }

    @Test
    public void cutoverCommitPrepareReusesThePersistedAttempt() {
        DrPlanVO plan = new DrPlanVO("cutover-attempt-plan", 1L, 2L,
                DrConstants.DIRECTION_KVM_TO_KVM);
        DrRunVO run = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_FAILOVER);
        DrCutoverSessionVO session = new DrCutoverSessionVO(plan.getId(), run.getId(),
                run.getRunType(), "CUTOVER_READY");
        DrTargetPowerOnResult powerOn = new DrTargetPowerOnResult(92L, "target-uuid", "POWERED_ON",
                "POWER_STATE_VALIDATED", new Date(), new Date(), false);

        DrCutoverSessionVO first = ReflectionTestUtils.invokeMethod(adapter,
                "prepareCutoverCommitSession", plan, run, session, powerOn, 546L,
                plan.getUuid() + ":" + run.getUuid(), "ACKNOWLEDGED", "POWERED_OFF");
        String attemptId = first.getCommitAttemptId();
        String envelopeSha = first.getCommitEnvelopeSha256();

        first.setEngineAckState("RETRY_REQUIRED");
        DrCutoverSessionVO retry = ReflectionTestUtils.invokeMethod(adapter,
                "prepareCutoverCommitSession", plan, run, first, powerOn, 546L,
                plan.getUuid() + ":" + run.getUuid(), "ACKNOWLEDGED", "POWERED_OFF");

        Assert.assertNotNull(attemptId);
        Assert.assertEquals(attemptId, retry.getCommitAttemptId());
        Assert.assertEquals(envelopeSha, retry.getCommitEnvelopeSha256());
    }

    @Test
    public void lateCutoverCommitFailureCannotRegressAcknowledgedAuthority() {
        DrCutoverSessionVO session = new DrCutoverSessionVO(2L, 71L,
                DrConstants.RUN_TYPE_FAILOVER, DrConstants.PLAN_STATE_FAILED_OVER);
        session.setCommitAttemptId("attempt-accepted");
        session.setCommitState("ACKNOWLEDGED");
        session.setEngineAckState("ACKNOWLEDGED");
        session.setCloudPromotionState("PROMOTED");

        DrCutoverSessionVO result = ReflectionTestUtils.invokeMethod(adapter,
                "recordCutoverCommitFailure", session, "attempt-accepted",
                "DR_CUTOVER_COMMIT_CONFLICT");

        Assert.assertSame(session, result);
        Assert.assertEquals("ACKNOWLEDGED", session.getEngineAckState());
        Assert.assertEquals("ACKNOWLEDGED", session.getCommitState());
        Assert.assertEquals("PROMOTED", session.getCloudPromotionState());
        Assert.assertNull(session.getErrorCode());
        Assert.assertNull(session.getErrorMessage());
    }

    @Test
    public void lateTargetRuntimeCannotRegressCommittedSourceAuthorityDuringFailbackResume() {
        DrPlanVO plan = new DrPlanVO("failback-source-authority", 1L, 2L,
                DrConstants.DIRECTION_KVM_TO_KVM);
        ReflectionTestUtils.setField(plan, "id", 44L);
        plan.setState(DrConstants.PLAN_STATE_FAILED_OVER);
        plan.setActiveSide(DrConstants.AUTHORITY_SIDE_TARGET);
        plan.setLastErrorCode("STALE_TARGET_RUNTIME");
        plan.setLastErrorMessage("late target sample");
        DrFailbackSessionVO session = new DrFailbackSessionVO(plan.getId(), 71L,
                "failback-session", "PROTECTION_RESUMING");
        session.setCommitOutcome("ACKNOWLEDGED");
        session.setEngineAckState("ACKNOWLEDGED");
        session.setTargetPowerState("POWERED_OFF");
        session.setSourcePowerState("POWERED_ON");
        FtctlDrStatusAnswer status = Mockito.mock(FtctlDrStatusAnswer.class);
        Mockito.when(status.getStatusJson()).thenReturn("{\"state\":\"FAILED_OVER\","
                + "\"active_side\":\"TARGET\"}");
        JsonObject runtime = JsonParser.parseString(status.getStatusJson()).getAsJsonObject();
        Mockito.when(drReplicaDao.listActiveByPlanId(plan.getId())).thenReturn(Collections.emptyList());

        Boolean preserved = ReflectionTestUtils.invokeMethod(adapter,
                "preserveCommittedSourceAuthorityDuringFailback", plan, status, runtime, session);

        Assert.assertTrue(Boolean.TRUE.equals(preserved));
        Assert.assertEquals(DrConstants.PLAN_STATE_SYNCING, plan.getState());
        Assert.assertEquals(DrConstants.AUTHORITY_SIDE_SOURCE, plan.getActiveSide());
        Assert.assertNull(plan.getLastErrorCode());
        Assert.assertNull(plan.getLastErrorMessage());
        Mockito.verify(drPlanDao).update(plan.getId(), plan);
    }

    @Test
    public void matchingEngineAuthorityRepairsRetryRequiredCutoverForFailbackReadiness() {
        DrPlanVO plan = new DrPlanVO("engine-authority-repair", 1L, 2L,
                DrConstants.DIRECTION_KVM_TO_KVM);
        ReflectionTestUtils.setField(plan, "id", 2L);
        plan.setState(DrConstants.PLAN_STATE_ERROR);
        plan.setActiveSide(DrConstants.AUTHORITY_SIDE_TARGET);
        plan.setLastErrorCode("DR_CUTOVER_COMMIT_FAILED");
        plan.setLastErrorMessage("stale conflict");
        DrCutoverSessionVO session = new DrCutoverSessionVO(plan.getId(), 71L,
                DrConstants.RUN_TYPE_FAILOVER, "ENGINE_COMMIT_PENDING");
        session.setCloudAuthorityGeneration(546L);
        session.setCloudPromotionState("POWER_ON_VALIDATED");
        session.setEngineAckState("RETRY_REQUIRED");
        session.setCommitState("UNKNOWN");
        session.setErrorCode("DR_CUTOVER_COMMIT_FAILED");
        session.setErrorMessage("stale conflict");
        DrRunVO run = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_FAILOVER);
        run.setState(DrConstants.RUN_STATE_SUCCEEDED);
        run.setCompleted(new Date());
        DrPlanRuntimeVO planRuntime = new DrPlanRuntimeVO(plan.getId());
        planRuntime.setProtectionState(DrConstants.PLAN_STATE_ERROR);

        JsonObject runtime = new JsonObject();
        runtime.addProperty("state", DrConstants.PLAN_STATE_FAILED_OVER);
        runtime.addProperty("active_side", DrConstants.AUTHORITY_SIDE_TARGET);
        runtime.addProperty("target_promotion_state", "PROMOTED");
        runtime.addProperty("engine_ack_state", "ACKNOWLEDGED");
        runtime.addProperty("cloud_cutover_session_id", session.getUuid());
        runtime.addProperty("cloud_authority_generation", 546L);
        FtctlDrStatusCommand command = new FtctlDrStatusCommand(plan.getUuid(), null,
                FtctlDrStatusCommand.StatusScope.PLAN_AUTHORITY);
        FtctlDrStatusAnswer status = new FtctlDrStatusAnswer(command, true, "ok", plan.getUuid(), null,
                "ok", DrConstants.PLAN_STATE_FAILED_OVER, "failed-over", 100,
                null, null, null, 546L, null, 0, "", runtime.toString());

        Mockito.when(drCutoverSessionDao.findLatestActiveByPlanId(plan.getId())).thenReturn(session);
        Mockito.when(drRunDao.findById(session.getRunId())).thenReturn(run);
        Mockito.when(drPlanRuntimeDao.findByPlanId(plan.getId())).thenReturn(planRuntime);
        Mockito.when(drReplicaDao.listActiveByPlanId(plan.getId())).thenReturn(Collections.emptyList());

        DrCutoverSessionVO repaired = ReflectionTestUtils.invokeMethod(adapter,
                "reconcileAcknowledgedTargetAuthority", plan, status, runtime);

        Assert.assertSame(session, repaired);
        Assert.assertEquals(DrConstants.PLAN_STATE_FAILED_OVER, plan.getState());
        Assert.assertEquals(DrConstants.AUTHORITY_SIDE_TARGET, plan.getActiveSide());
        Assert.assertNull(plan.getLastErrorCode());
        Assert.assertEquals(DrConstants.PLAN_STATE_FAILED_OVER, session.getState());
        Assert.assertEquals("PROMOTED", session.getCloudPromotionState());
        Assert.assertEquals("ACKNOWLEDGED", session.getEngineAckState());
        Assert.assertEquals("ACKNOWLEDGED", session.getCommitState());
        Assert.assertNull(session.getErrorCode());
        Assert.assertEquals("FAILED_OVER_UNPROTECTED", planRuntime.getProtectionState());
        Assert.assertEquals("STOPPED", planRuntime.getSchedulerState());
    }

    @Test
    public void mismatchedEngineAuthorityCannotRepairAnotherCutoverSession() {
        DrPlanVO plan = new DrPlanVO("engine-authority-mismatch", 1L, 2L,
                DrConstants.DIRECTION_KVM_TO_KVM);
        ReflectionTestUtils.setField(plan, "id", 3L);
        plan.setState(DrConstants.PLAN_STATE_ERROR);
        plan.setActiveSide(DrConstants.AUTHORITY_SIDE_TARGET);
        DrCutoverSessionVO session = new DrCutoverSessionVO(plan.getId(), 72L,
                DrConstants.RUN_TYPE_FAILOVER, "ENGINE_COMMIT_PENDING");
        session.setCloudAuthorityGeneration(546L);
        session.setEngineAckState("RETRY_REQUIRED");
        JsonObject runtime = new JsonObject();
        runtime.addProperty("active_side", DrConstants.AUTHORITY_SIDE_TARGET);
        runtime.addProperty("target_promotion_state", "PROMOTED");
        runtime.addProperty("engine_ack_state", "ACKNOWLEDGED");
        runtime.addProperty("cloud_cutover_session_id", "another-session");
        runtime.addProperty("cloud_authority_generation", 546L);
        FtctlDrStatusCommand command = new FtctlDrStatusCommand(plan.getUuid(), null,
                FtctlDrStatusCommand.StatusScope.PLAN_AUTHORITY);
        FtctlDrStatusAnswer status = new FtctlDrStatusAnswer(command, true, "ok", plan.getUuid(), null,
                "ok", DrConstants.PLAN_STATE_FAILED_OVER, "failed-over", 100,
                null, null, null, 546L, null, 0, "", runtime.toString());
        Mockito.when(drCutoverSessionDao.findLatestActiveByPlanId(plan.getId())).thenReturn(session);

        DrCutoverSessionVO repaired = ReflectionTestUtils.invokeMethod(adapter,
                "reconcileAcknowledgedTargetAuthority", plan, status, runtime);

        Assert.assertNull(repaired);
        Assert.assertEquals(DrConstants.PLAN_STATE_ERROR, plan.getState());
        Assert.assertEquals("RETRY_REQUIRED", session.getEngineAckState());
        Mockito.verify(drPlanDao, Mockito.never()).update(Mockito.eq(plan.getId()), Mockito.any(DrPlanVO.class));
    }

    @Test
    public void refreshPlanProjectionFailsAcceptedRunWhenStatusRefreshFails() {
        DrPlanVO plan = new DrPlanVO("ftctl-dr-plan", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setState(DrConstants.PLAN_STATE_SYNCING);
        plan.setCoordinatorWorkerHostId(103L);
        DrRunVO run = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_SYNC);
        run.setState(DrConstants.RUN_STATE_ACCEPTED);

        Mockito.when(agentManager.easySend(Mockito.eq(103L), Mockito.any(FtctlDrStatusCommand.class))).thenAnswer(invocation -> {
            FtctlDrStatusCommand command = invocation.getArgument(1);
            return new FtctlDrStatusAnswer(command, false, "FTCTL_DR status refresh failed", plan.getUuid(), null,
                    "error", "ERROR", "status-refresh", 0,
                    null, null, null, 14L, DrConstants.ERROR_ENGINE_ACTION_FAILED, 2,
                    "status failed", "{\"state\":\"ERROR\",\"error_code\":\"DR_ENGINE_ACTION_FAILED\"}");
        });
        Mockito.when(drRunDao.findActiveByPlanId(plan.getId())).thenReturn(run);

        DrAdapterResult result = adapter.refreshPlanProjection(plan);

        Assert.assertFalse(result.isSuccess());
        Assert.assertEquals(DrConstants.RUN_STATE_FAILED, run.getState());
        Assert.assertEquals("runtime-projection", run.getCurrentStepName());
        Assert.assertEquals(DrConstants.ERROR_ENGINE_ACTION_FAILED, run.getErrorCode());
        Assert.assertEquals("FTCTL_DR status refresh failed", run.getErrorMessage());
        Assert.assertNotNull(run.getCompleted());

        ArgumentCaptor<DrRunStepVO> stepCaptor = ArgumentCaptor.forClass(DrRunStepVO.class);
        Mockito.verify(drRunStepDao).persist(stepCaptor.capture());
        DrRunStepVO step = stepCaptor.getValue();
        Assert.assertEquals(DrConstants.STEP_STATE_FAILED, step.getState());
        Assert.assertEquals(DrConstants.ERROR_ENGINE_ACTION_FAILED, step.getErrorCode());

        Mockito.verify(drRunDao).update(Mockito.eq(run.getId()), Mockito.same(run));
        Mockito.verify(drEventDao).persist(Mockito.any(DrEventVO.class));
    }

    @Test
    public void failbackCommitAckPendingRemainsRunningEvenWithDataTerminal() {
        DrPlanVO plan = new DrPlanVO("ftctl-dr-failback-pending", 1L, 2L,
                DrConstants.DIRECTION_VMWARE_TO_KVM);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setState(DrConstants.PLAN_STATE_COMMIT_VERIFYING);
        plan.setActiveSide("SOURCE");
        plan.setCoordinatorWorkerHostId(103L);
        DrRunVO run = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_FAILBACK);
        run.setState(DrConstants.RUN_STATE_ACCEPTED);

        Mockito.when(agentManager.easySend(Mockito.eq(103L), Mockito.any(FtctlDrStatusCommand.class)))
                .thenAnswer(invocation -> {
                    FtctlDrStatusCommand command = invocation.getArgument(1);
                    String runUuid = command.getStatusScope() == FtctlDrStatusCommand.StatusScope.OPERATION
                            ? run.getUuid() : null;
                    String statusJson = "{\"state\":\"SYNCING\",\"step\":\"commit-verifying\","
                            + "\"failback_phase\":\"COMMIT_VERIFYING\","
                            + "\"failback_commit_outcome\":\"UNKNOWN\",\"engine_ack_state\":\"UNKNOWN\","
                            + "\"terminal_source\":\"ENGINE_TERMINAL\",\"terminal_authoritative\":true,"
                            + "\"error_code\":\"DR_FAILBACK_COMMIT_ACK_PENDING\",\"retryable\":true,"
                            + "\"retry_after_sec\":2}";
                    return new FtctlDrStatusAnswer(command, true, "accepted", plan.getUuid(), runUuid,
                            "unknown", "SYNCING", "commit-verifying", 90,
                            null, null, null, 21L, DrConstants.ERROR_FAILBACK_COMMIT_ACK_PENDING,
                            2, "Failback commit acknowledgement is pending verification", statusJson);
                });
        Mockito.when(drRunDao.findActiveByPlanId(plan.getId())).thenReturn(run);

        DrAdapterResult result = adapter.refreshPlanProjection(plan);

        Assert.assertTrue(result.isSuccess());
        Assert.assertEquals(DrConstants.RUN_STATE_RUNNING, run.getState());
        Assert.assertEquals("commit-verifying", run.getCurrentStepName());
        Assert.assertEquals("lifecycle-pending", run.getProjectionState());
        Assert.assertNull(run.getCompleted());
        Assert.assertNull(run.getErrorCode());
        Assert.assertNull(run.getErrorMessage());
        Assert.assertTrue(run.isRetryable());
        Assert.assertFalse(run.isTerminalAuthoritative());
        Mockito.verify(drRunDao).update(Mockito.eq(run.getId()), Mockito.same(run));
        Mockito.verify(drEventDao, Mockito.never()).persist(Mockito.argThat(event ->
                DrConstants.EVENT_RUN_FAILED.equals(event.getEventType())));
    }

    @Test
    public void failoverIncompleteCycleEvidenceAbortsAfterBoundedRetriesAndRestoresSourceAuthority() {
        DrPlanVO plan = new DrPlanVO("ftctl-dr-plan", 1L, 2L, DrConstants.DIRECTION_VMWARE_TO_KVM);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setState(DrConstants.PLAN_STATE_SYNCING);
        plan.setActiveSide("SOURCE");
        plan.setCoordinatorWorkerHostId(103L);
        DrRunVO run = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_FAILOVER);
        run.setState(DrConstants.RUN_STATE_ACCEPTED);
        run.setRetryCount(2);
        DrCutoverSessionVO session = new DrCutoverSessionVO(
                plan.getId(), run.getId(), run.getRunType(), "CUTOVER_READY");
        DrPlanRuntimeVO runtime = new DrPlanRuntimeVO(plan.getId());
        DrReplicaVO replica = new DrReplicaVO(plan.getId(), plan.getTargetSiteId());
        replica.setState(DrConstants.REPLICA_STATE_READY);
        replica.setActiveSide("SOURCE");
        replica.setPowerState(DrConstants.REPLICA_POWER_STATE_POWERED_OFF);

        String statusJson = "{\"state\":\"CUTOVER_READY\",\"active_side\":\"SOURCE\","
                + "\"target_power_state\":\"POWERED_OFF\",\"guest_prep_state\":\"READY\","
                + "\"manifest_schema_version\":\"FTCTL_GUESTPREP_MANIFEST_V2\","
                + "\"manifest_sha256\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\","
                + "\"guestprep_checkpoint_sequence\":15,\"latest_completed_checkpoint_sequence\":15,"
                + "\"target_disk_count\":1,\"failover_session_id\":\""
                + plan.getUuid() + ":" + run.getUuid() + "\"}";
        Mockito.when(agentManager.easySend(Mockito.eq(103L), Mockito.any(FtctlDrStatusCommand.class)))
                .thenAnswer(invocation -> {
                    FtctlDrStatusCommand command = invocation.getArgument(1);
                    FtctlDrStatusAnswer answer = new FtctlDrStatusAnswer(command, false,
                            "FTCTL_DR latest completed cycle evidence is incomplete", plan.getUuid(), run.getUuid(),
                            "error", "CUTOVER_READY", "cutover-ready", 100,
                            null, null, null, null,
                            "DR_STATUS_CYCLE_EVIDENCE_INCOMPLETE", 65,
                            "incomplete", statusJson);
                    answer.setCycleEvidenceState("INCOMPLETE");
                    answer.setRetryable(true);
                    answer.setRetryAfterSeconds(5);
                    return answer;
                });
        Mockito.when(agentManager.easySend(Mockito.eq(103L), Mockito.any(FtctlDrActionCommand.class)))
                .thenAnswer(invocation -> new Answer(invocation.getArgument(1), true, "aborted"));
        Mockito.when(drRunDao.findActiveByPlanId(plan.getId())).thenReturn(run);
        Mockito.when(drCutoverSessionDao.findActiveByRunId(run.getId())).thenReturn(session);
        Mockito.when(drPlanRuntimeDao.findByPlanId(plan.getId())).thenReturn(runtime);
        Mockito.when(drReplicaDao.listActiveByPlanId(plan.getId()))
                .thenReturn(Collections.singletonList(replica));

        DrAdapterResult result = adapter.refreshPlanProjection(plan);

        Assert.assertFalse(result.isSuccess());
        Assert.assertEquals("DR_STATUS_CYCLE_EVIDENCE_INCOMPLETE", result.getErrorCode());
        Assert.assertEquals(DrConstants.RUN_STATE_FAILED, run.getState());
        Assert.assertEquals(Integer.valueOf(3), run.getRetryCount());
        Assert.assertEquals("ABORTED", session.getState());
        Assert.assertFalse(session.isCleanupRequired());
        Assert.assertEquals("ABORTED", session.getEngineAckState());
        Assert.assertEquals(DrConstants.PLAN_STATE_READY, plan.getState());
        Assert.assertEquals("SOURCE", plan.getActiveSide());
        Assert.assertEquals(DrConstants.REPLICA_STATE_READY, replica.getState());
        Assert.assertEquals("SOURCE", replica.getActiveSide());
        Assert.assertEquals(DrConstants.REPLICA_POWER_STATE_POWERED_OFF, replica.getPowerState());

        ArgumentCaptor<FtctlDrActionCommand> action = ArgumentCaptor.forClass(FtctlDrActionCommand.class);
        Mockito.verify(agentManager).easySend(Mockito.eq(103L), action.capture());
        Assert.assertEquals(FtctlDrActionCommand.Action.FAILOVER_ABORT, action.getValue().getAction());
        Mockito.verify(drTargetMaterializationService, Mockito.never()).ensureTargetPoweredOn(plan.getId());
    }

    @Test
    public void canceledFailoverCutoverReadyRuntimeAbortsAndRestoresSourceAuthority() {
        DrPlanVO plan = new DrPlanVO("ftctl-dr-canceled-failover", 1L, 2L,
                DrConstants.DIRECTION_VMWARE_TO_KVM);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setState(DrConstants.PLAN_STATE_FAILED_OVER);
        plan.setActiveSide("TARGET");
        plan.setCoordinatorWorkerHostId(103L);
        DrRunVO run = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_FAILOVER);
        run.setState(DrConstants.RUN_STATE_CANCELED);
        run.setCompleted(new Date());
        DrCutoverSessionVO session = new DrCutoverSessionVO(
                plan.getId(), run.getId(), run.getRunType(), "CUTOVER_READY");
        DrPlanRuntimeVO planRuntime = new DrPlanRuntimeVO(plan.getId());
        DrReplicaVO replica = new DrReplicaVO(plan.getId(), plan.getTargetSiteId());
        replica.setState(DrConstants.REPLICA_STATE_READY);
        replica.setActiveSide("SOURCE");
        replica.setPowerState(DrConstants.REPLICA_POWER_STATE_POWERED_OFF);

        String statusJson = "{\"state\":\"CUTOVER_READY\",\"active_side\":\"SOURCE\","
                + "\"target_power_state\":\"POWERED_OFF\",\"failover_session_id\":\""
                + plan.getUuid() + ":" + run.getUuid() + "\"}";
        Mockito.when(agentManager.easySend(Mockito.eq(103L), Mockito.any(FtctlDrStatusCommand.class)))
                .thenAnswer(invocation -> {
                    FtctlDrStatusCommand command = invocation.getArgument(1);
                    return new FtctlDrStatusAnswer(command, true, "ok", plan.getUuid(), run.getUuid(),
                            "ok", "CUTOVER_READY", "cutover-ready", 100,
                            null, null, null, null, null, 0, null, statusJson);
                });
        Mockito.when(agentManager.easySend(Mockito.eq(103L), Mockito.any(FtctlDrActionCommand.class)))
                .thenAnswer(invocation -> new Answer(invocation.getArgument(1), true, "aborted"));
        Mockito.when(drRunDao.findActiveByPlanId(plan.getId())).thenReturn(null);
        Mockito.when(drRunDao.listByPlanId(plan.getId())).thenReturn(Collections.singletonList(run));
        Mockito.when(drCutoverSessionDao.findActiveByRunId(run.getId())).thenReturn(session);
        Mockito.when(drPlanRuntimeDao.findByPlanId(plan.getId())).thenReturn(planRuntime);
        Mockito.when(drReplicaDao.listActiveByPlanId(plan.getId()))
                .thenReturn(Collections.singletonList(replica));

        DrAdapterResult result = adapter.refreshPlanProjection(plan);

        Assert.assertTrue(result.isSuccess());
        Assert.assertEquals("ABORTED", session.getState());
        Assert.assertEquals(DrConstants.PLAN_STATE_READY, plan.getState());
        Assert.assertEquals("SOURCE", plan.getActiveSide());
        Assert.assertEquals(DrConstants.REPLICA_STATE_READY, replica.getState());
        Assert.assertEquals("SOURCE", replica.getActiveSide());
        ArgumentCaptor<FtctlDrActionCommand> action = ArgumentCaptor.forClass(FtctlDrActionCommand.class);
        Mockito.verify(agentManager).easySend(Mockito.eq(103L), action.capture());
        Assert.assertEquals(FtctlDrActionCommand.Action.FAILOVER_ABORT, action.getValue().getAction());
    }

    @Test
    public void canceledFailoverReconciliationAcceptsReadyTargetProjectionAndIgnoresActiveSchedulerRun() {
        DrPlanVO plan = new DrPlanVO("ftctl-dr-canceled-ready-target", 1L, 2L,
                DrConstants.DIRECTION_VMWARE_TO_KVM);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setState(DrConstants.PLAN_STATE_READY);
        plan.setActiveSide("TARGET");
        plan.setCoordinatorWorkerHostId(103L);
        DrRunVO schedulerRun = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_SYNC);
        schedulerRun.setState(DrConstants.RUN_STATE_ACCEPTED);
        DrRunVO canceledFailover = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_FAILOVER);
        canceledFailover.setState(DrConstants.RUN_STATE_CANCELED);
        canceledFailover.setCompleted(new Date());
        DrCutoverSessionVO session = new DrCutoverSessionVO(
                plan.getId(), canceledFailover.getId(), canceledFailover.getRunType(), "ABORT_FAILED");
        DrPlanRuntimeVO planRuntime = new DrPlanRuntimeVO(plan.getId());
        DrReplicaVO replica = new DrReplicaVO(plan.getId(), plan.getTargetSiteId());
        replica.setState(DrConstants.REPLICA_STATE_READY);
        replica.setActiveSide("SOURCE");
        replica.setPowerState(DrConstants.REPLICA_POWER_STATE_POWERED_OFF);
        String statusJson = "{\"state\":\"READY\",\"active_side\":\"SOURCE\","
                + "\"target_power_state\":\"POWERED_OFF\",\"failover_session_id\":\""
                + plan.getUuid() + ":" + canceledFailover.getUuid() + "\"}";
        Mockito.when(agentManager.easySend(Mockito.eq(103L), Mockito.any(FtctlDrStatusCommand.class)))
                .thenAnswer(invocation -> {
                    FtctlDrStatusCommand command = invocation.getArgument(1);
                    return new FtctlDrStatusAnswer(command, true, "ok", plan.getUuid(), schedulerRun.getUuid(),
                            "ok", "READY", "target-checkpoint-ready", 100,
                            null, null, null, null, null, 0, null, statusJson);
                });
        Mockito.when(agentManager.easySend(Mockito.eq(103L), Mockito.any(FtctlDrActionCommand.class)))
                .thenAnswer(invocation -> new Answer(invocation.getArgument(1), true, "aborted"));
        Mockito.when(drRunDao.findActiveByPlanId(plan.getId())).thenReturn(schedulerRun);
        Mockito.when(drRunDao.listByPlanId(plan.getId())).thenReturn(Collections.singletonList(canceledFailover));
        Mockito.when(drCutoverSessionDao.findActiveByRunId(canceledFailover.getId())).thenReturn(session);
        Mockito.when(drPlanRuntimeDao.findByPlanId(plan.getId())).thenReturn(planRuntime);
        Mockito.when(drReplicaDao.listActiveByPlanId(plan.getId()))
                .thenReturn(Collections.singletonList(replica));

        DrAdapterResult result = adapter.refreshPlanProjection(plan);

        Assert.assertTrue(result.isSuccess());
        Assert.assertEquals("ABORTED", session.getState());
        Assert.assertEquals(DrConstants.PLAN_STATE_READY, plan.getState());
        Assert.assertEquals("SOURCE", plan.getActiveSide());
        Mockito.verify(drTargetMaterializationService, Mockito.never()).ensureTargetPoweredOn(plan.getId());
    }

    @Test
    public void canceledRemoteKvmFailoverCompensatesBeforeFailedAuthorityProjection() {
        DrPlanVO plan = new DrPlanVO("remote-kvm-canceled-cutover", 1L, 2L,
                DrConstants.DIRECTION_KVM_TO_KVM);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setState(DrConstants.PLAN_STATE_ERROR);
        plan.setActiveSide(DrConstants.AUTHORITY_SIDE_SOURCE);
        plan.setCoordinatorWorkerHostId(103L);
        plan.setTargetWorkerHostId(102L);
        plan.setSourceExternalRef("source-vm-uuid");
        plan.setMappingJson("{\"source\":{\"hardware\":{\"sourceHostUuid\":\"source-worker-uuid\"}}}");
        DrRunVO run = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_FAILOVER);
        run.setState(DrConstants.RUN_STATE_CANCELED);
        run.setCompleted(new Date());
        DrCutoverSessionVO session = new DrCutoverSessionVO(plan.getId(), run.getId(),
                run.getRunType(), "ABORT_FAILED");
        DrPlanRuntimeVO planRuntime = new DrPlanRuntimeVO(plan.getId());
        DrReplicaVO replica = new DrReplicaVO(plan.getId(), plan.getTargetSiteId());
        replica.setTargetVmId(283L);
        replica.setState(DrConstants.REPLICA_STATE_READY);
        replica.setActiveSide(DrConstants.AUTHORITY_SIDE_SOURCE);
        replica.setPowerState("POWERED_ON");
        UserVmVO targetVm = Mockito.mock(UserVmVO.class);
        Mockito.when(targetVm.getState()).thenReturn(VirtualMachine.State.Stopped);

        String statusJson = "{\"state\":\"ERROR\",\"active_side\":\"\","
                + "\"target_power_state\":\"POWERED_ON\",\"failover_session_id\":\""
                + plan.getUuid() + ":" + run.getUuid() + "\"}";
        Mockito.when(drRemoteAgentClient.isRemoteKvmSource(plan)).thenReturn(true);
        Mockito.when(drRemoteAgentClient.execute(Mockito.eq(plan), Mockito.eq("STATUS"),
                Mockito.any(FtctlDrStatusCommand.class), Mockito.isNull(),
                Mockito.eq(FtctlDrStatusAnswer.class)))
                .thenAnswer(invocation -> {
                    FtctlDrStatusCommand command = invocation.getArgument(2);
                    return new FtctlDrStatusAnswer(command, false, "replication cycle failed",
                            plan.getUuid(), run.getUuid(), "error", "ERROR", "replication-cycle-failed", 100,
                            null, null, null, null, "DR_REPLICATION_CYCLE_FAILED", 0, null, statusJson);
                });
        Mockito.when(drRemoteAgentClient.execute(Mockito.eq(plan), Mockito.eq("ACTION"),
                Mockito.any(FtctlDrActionCommand.class), Mockito.isNull(),
                Mockito.eq(FtctlDrActionAnswer.class)))
                .thenAnswer(invocation -> new FtctlDrActionAnswer(invocation.getArgument(2), true, "aborted"));
        Mockito.when(drRemoteAgentClient.ensureSourceVmPowerState(plan, true)).thenReturn("POWERED_ON");
        Mockito.when(drRemoteAgentClient.transitionSourceScheduler(plan,
                FtctlDrActionCommand.Action.RESUME_SYNC, run.getUuid()))
                .thenAnswer(invocation -> new FtctlDrActionAnswer(
                        new FtctlDrActionCommand(FtctlDrActionCommand.Action.RESUME_SYNC,
                                plan.getUuid(), run.getUuid()), true, "resumed"));
        Mockito.when(drRunDao.findActiveByPlanId(plan.getId())).thenReturn(null);
        Mockito.when(drRunDao.findById(run.getId())).thenReturn(run);
        Mockito.when(drCutoverSessionDao.findLatestActiveByPlanId(plan.getId())).thenReturn(session);
        Mockito.when(drCutoverSessionDao.findActiveByRunId(run.getId())).thenReturn(session);
        Mockito.when(drPlanRuntimeDao.findByPlanId(plan.getId())).thenReturn(planRuntime);
        Mockito.when(drReplicaDao.listActiveByPlanId(plan.getId())).thenReturn(Collections.singletonList(replica));
        Mockito.when(userVmDao.findById(283L)).thenReturn(targetVm);

        DrAdapterResult result = adapter.refreshPlanProjection(plan);

        Assert.assertTrue(result.getErrorCode() + ": " + result.getMessage(), result.isSuccess());
        Assert.assertEquals(session.getErrorCode() + ": " + session.getErrorMessage(),
                "ABORTED", session.getState());
        Assert.assertFalse(session.isCleanupRequired());
        Assert.assertEquals(DrConstants.PLAN_STATE_READY, plan.getState());
        Assert.assertEquals(DrConstants.AUTHORITY_SIDE_SOURCE, plan.getActiveSide());
        Mockito.verify(drTargetMaterializationService, Mockito.never()).ensureTargetPoweredOn(plan.getId());

        org.mockito.InOrder compensationOrder = Mockito.inOrder(drTargetMaterializationService,
                drRemoteAgentClient, drPlanOwnedTransportService);
        compensationOrder.verify(drTargetMaterializationService).ensureTargetPoweredOff(plan.getId());
        compensationOrder.verify(drRemoteAgentClient).execute(Mockito.eq(plan), Mockito.eq("ACTION"),
                Mockito.argThat(command -> command instanceof FtctlDrActionCommand
                        && ((FtctlDrActionCommand) command).getAction() == FtctlDrActionCommand.Action.FAILOVER_ABORT),
                Mockito.isNull(), Mockito.eq(FtctlDrActionAnswer.class));
        compensationOrder.verify(drPlanOwnedTransportService).startForwardTargetExport(plan, run, null);
        compensationOrder.verify(drRemoteAgentClient).ensureSourceVmPowerState(plan, true);
        compensationOrder.verify(drRemoteAgentClient).transitionSourceScheduler(plan,
                FtctlDrActionCommand.Action.RESUME_SYNC, run.getUuid());
    }

    @Test
    public void failoverAbortRefusesRunningCloudTarget() {
        DrPlanVO plan = new DrPlanVO("ftctl-dr-plan", 1L, 2L, DrConstants.DIRECTION_VMWARE_TO_KVM);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setState(DrConstants.PLAN_STATE_SYNCING);
        plan.setActiveSide("SOURCE");
        plan.setCoordinatorWorkerHostId(103L);
        DrRunVO run = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_FAILOVER);
        run.setState(DrConstants.RUN_STATE_ACCEPTED);
        run.setRetryCount(2);
        DrCutoverSessionVO session = new DrCutoverSessionVO(
                plan.getId(), run.getId(), run.getRunType(), "CUTOVER_READY");
        DrReplicaVO replica = new DrReplicaVO(plan.getId(), plan.getTargetSiteId());
        replica.setTargetVmId(256L);
        replica.setState(DrConstants.REPLICA_STATE_READY);
        replica.setActiveSide("SOURCE");
        replica.setPowerState(DrConstants.REPLICA_POWER_STATE_POWERED_OFF);
        UserVmVO targetVm = Mockito.mock(UserVmVO.class);

        String statusJson = "{\"state\":\"CUTOVER_READY\",\"active_side\":\"SOURCE\","
                + "\"target_power_state\":\"POWERED_OFF\",\"failover_session_id\":\""
                + plan.getUuid() + ":" + run.getUuid() + "\"}";
        Mockito.when(agentManager.easySend(Mockito.eq(103L), Mockito.any(FtctlDrStatusCommand.class)))
                .thenAnswer(invocation -> {
                    FtctlDrStatusCommand command = invocation.getArgument(1);
                    FtctlDrStatusAnswer answer = new FtctlDrStatusAnswer(command, false,
                            "FTCTL_DR latest completed cycle evidence is incomplete", plan.getUuid(), run.getUuid(),
                            "error", "CUTOVER_READY", "cutover-ready", 100,
                            null, null, null, null,
                            "DR_STATUS_CYCLE_EVIDENCE_INCOMPLETE", 65,
                            "incomplete", statusJson);
                    answer.setCycleEvidenceState("INCOMPLETE");
                    answer.setRetryable(true);
                    answer.setRetryAfterSeconds(5);
                    return answer;
                });
        Mockito.when(drRunDao.findActiveByPlanId(plan.getId())).thenReturn(run);
        Mockito.when(drCutoverSessionDao.findActiveByRunId(run.getId())).thenReturn(session);
        Mockito.when(drReplicaDao.listActiveByPlanId(plan.getId()))
                .thenReturn(Collections.singletonList(replica));
        Mockito.doThrow(new com.cloud.utils.exception.CloudRuntimeException("target stop failed"))
                .when(drTargetMaterializationService).ensureTargetPoweredOff(plan.getId());

        DrAdapterResult result = adapter.refreshPlanProjection(plan);

        Assert.assertFalse(result.isSuccess());
        Assert.assertEquals("ABORT_FAILED", session.getState());
        Assert.assertTrue(session.isCleanupRequired());
        Assert.assertEquals("DR_FAILOVER_ABORT_UNSAFE", session.getErrorCode());
        Mockito.verify(agentManager, Mockito.never())
                .easySend(Mockito.eq(103L), Mockito.any(FtctlDrActionCommand.class));
    }

    @Test
    public void refreshPlanProjectionFailsAcceptedRunWhenRuntimePayloadReportsWorkerFailure() {
        DrPlanVO plan = new DrPlanVO("ftctl-dr-plan", 1L, 2L, DrConstants.DIRECTION_VMWARE_TO_KVM);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setState(DrConstants.PLAN_STATE_SYNCING);
        plan.setCoordinatorWorkerHostId(103L);
        DrRunVO run = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_SYNC);
        run.setState(DrConstants.RUN_STATE_ACCEPTED);
        DrReplicaVO replica = new DrReplicaVO(plan.getId(), plan.getTargetSiteId());
        replica.setState(DrConstants.REPLICA_STATE_SKELETON_READY);

        String statusJson = "{\"state\":\"ERROR\",\"step\":\"ablestack-target-prepare-failed\","
                + "\"worker_state\":\"FAILED\",\"worker_exit_code\":32,"
                + "\"target_materialized\":false,\"target_vm_present\":false,"
                + "\"error_code\":\"" + DrConstants.ERROR_TARGET_DISK_TYPE_INVALID + "\","
                + "\"error_message\":\"ABLESTACK target preparation failed before target VM materialization\"}";
        Mockito.when(agentManager.easySend(Mockito.eq(103L), Mockito.any(FtctlDrStatusCommand.class))).thenAnswer(invocation -> {
            FtctlDrStatusCommand command = invocation.getArgument(1);
            return new FtctlDrStatusAnswer(command, true, "ok", plan.getUuid(), run.getUuid(),
                    "ok", "ERROR", "ablestack-target-prepare-failed", 100,
                    null, null, null, 15L, null, 0, "", statusJson);
        });
        Mockito.when(drRunDao.findActiveByPlanId(plan.getId())).thenReturn(run);
        Mockito.when(drReplicaDao.listActiveByPlanId(plan.getId())).thenReturn(Collections.singletonList(replica));
        Mockito.when(drPlanDao.findById(plan.getId())).thenReturn(plan);

        DrAdapterResult result = adapter.refreshPlanProjection(plan);

        Assert.assertTrue(result.isSuccess());
        Assert.assertEquals(DrConstants.RUN_STATE_FAILED, run.getState());
        Assert.assertEquals("runtime-projection", run.getCurrentStepName());
        Assert.assertEquals(DrConstants.ERROR_TARGET_DISK_TYPE_INVALID, run.getErrorCode());
        Assert.assertEquals("ABLESTACK target preparation failed before target VM materialization", run.getErrorMessage());
        Assert.assertNotNull(run.getCompleted());
        Assert.assertEquals(DrConstants.PLAN_STATE_ERROR, plan.getState());
        Assert.assertEquals(DrConstants.ERROR_TARGET_DISK_TYPE_INVALID, plan.getLastErrorCode());
        Assert.assertEquals(DrConstants.REPLICA_STATE_ERROR, replica.getState());

        ArgumentCaptor<DrRunStepVO> stepCaptor = ArgumentCaptor.forClass(DrRunStepVO.class);
        Mockito.verify(drRunStepDao).persist(stepCaptor.capture());
        DrRunStepVO step = stepCaptor.getValue();
        Assert.assertEquals(DrConstants.STEP_STATE_FAILED, step.getState());
        Assert.assertEquals(DrConstants.ERROR_TARGET_DISK_TYPE_INVALID, step.getErrorCode());
        Assert.assertTrue(step.getDetailsJson().contains("\"worker_state\":\"FAILED\""));

        Mockito.verify(drRunDao).update(Mockito.eq(run.getId()), Mockito.same(run));
        Mockito.verify(drPlanDao, Mockito.atLeastOnce()).update(Mockito.eq(plan.getId()), Mockito.same(plan));
        Mockito.verify(drReplicaDao).update(Mockito.eq(replica.getId()), Mockito.same(replica));
        Mockito.verify(drEventDao, Mockito.atLeastOnce()).persist(Mockito.any(DrEventVO.class));
    }

    @Test
    public void reprotectFailurePreservesCommittedTargetAuthority() {
        DrPlanVO plan = new DrPlanVO("ftctl-dr-plan", 1L, 2L, DrConstants.DIRECTION_VMWARE_TO_KVM);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setState(DrConstants.PLAN_STATE_FAILED_OVER);
        plan.setActiveSide("TARGET");
        plan.setCoordinatorWorkerHostId(103L);
        DrRunVO run = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_REPROTECT);
        run.setState(DrConstants.RUN_STATE_ACCEPTED);
        DrReplicaVO replica = new DrReplicaVO(plan.getId(), plan.getTargetSiteId());
        replica.setState(DrConstants.REPLICA_STATE_READY);
        replica.setActiveSide("TARGET");
        DrPlanRuntimeVO runtime = new DrPlanRuntimeVO(plan.getId());
        DrCutoverSessionVO cutover = new DrCutoverSessionVO(plan.getId(), 94L,
                DrConstants.RUN_TYPE_FAILOVER, "PROMOTED");
        cutover.setCloudPromotionState("PROMOTED");
        cutover.setTargetPowerState("POWERED_ON");
        cutover.setEngineAckState("ACKNOWLEDGED");
        cutover.setCloudAuthorityGeneration(1L);
        cutover.setState(DrConstants.PLAN_STATE_FAILED_OVER);

        String statusJson = "{\"state\":\"ERROR\",\"step\":\"reprotect-preflight\","
                + "\"worker_state\":\"FAILED\",\"worker_exit_code\":20,"
                + "\"error_code\":\"" + DrConstants.ERROR_REPROTECT_TARGET_RUNTIME_NOT_RUNNING + "\","
                + "\"error_message\":\"Target VM is not powered on according to its host Agent\"}";
        Mockito.when(agentManager.easySend(Mockito.eq(103L), Mockito.any(FtctlDrStatusCommand.class))).thenAnswer(invocation -> {
            FtctlDrStatusCommand command = invocation.getArgument(1);
            return new FtctlDrStatusAnswer(command, true, "ok", plan.getUuid(), run.getUuid(),
                    "ok", "ERROR", "reprotect-preflight", 100,
                    null, null, null, 15L, null, 0, "", statusJson);
        });
        Mockito.when(drRunDao.findActiveByPlanId(plan.getId())).thenReturn(run);
        Mockito.when(drPlanRuntimeDao.findByPlanId(plan.getId())).thenReturn(runtime);
        Mockito.when(drCutoverSessionDao.findLatestActiveByPlanId(plan.getId())).thenReturn(cutover);
        Mockito.when(drReplicaDao.listActiveByPlanId(plan.getId())).thenReturn(Collections.singletonList(replica));

        DrAdapterResult result = adapter.refreshPlanProjection(plan);

        Assert.assertTrue(result.isSuccess());
        Assert.assertEquals(DrConstants.RUN_STATE_FAILED, run.getState());
        Assert.assertEquals(DrConstants.ERROR_REPROTECT_TARGET_RUNTIME_NOT_RUNNING, run.getErrorCode());
        Assert.assertEquals(DrConstants.PLAN_STATE_FAILED_OVER, plan.getState());
        Assert.assertEquals("TARGET", plan.getActiveSide());
        Assert.assertNull(plan.getLastErrorCode());
        Assert.assertEquals(DrConstants.REPLICA_STATE_READY, replica.getState());
        Assert.assertEquals("FAILED_OVER_UNPROTECTED", runtime.getProtectionState());
        Mockito.verify(drReplicaDao, Mockito.never()).update(Mockito.eq(replica.getId()), Mockito.same(replica));
    }

    @Test
    public void rolledBackFailbackPreservesServingTargetAuthority() {
        DrPlanVO plan = new DrPlanVO("ftctl-dr-plan", 1L, 2L, DrConstants.DIRECTION_VMWARE_TO_KVM);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setState(DrConstants.PLAN_STATE_FAILED_OVER);
        plan.setActiveSide("TARGET");
        plan.setCoordinatorWorkerHostId(103L);
        plan.setLastErrorCode("DR_FAILBACK_PROTECTION_RESUME_FAILED");
        plan.setLastErrorMessage("stale failback failure");
        DrRunVO run = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_FAILBACK);
        run.setState(DrConstants.RUN_STATE_ACCEPTED);
        DrReplicaVO replica = new DrReplicaVO(plan.getId(), plan.getTargetSiteId());
        replica.setTargetVmId(256L);
        replica.setState(DrConstants.REPLICA_STATE_ERROR);
        replica.setPowerState("POWERED_ON");
        replica.setActiveSide("TARGET");
        DrPlanRuntimeVO runtime = new DrPlanRuntimeVO(plan.getId());

        String statusJson = "{\"action\":\"dr-failback-abort\",\"state\":\"FAILED_OVER\","
                + "\"active_side\":\"TARGET\",\"source_power_state\":\"POWERED_OFF\","
                + "\"target_power_state\":\"POWERED_ON\",\"failback_commit_outcome\":\"ROLLED_BACK\","
                + "\"rollback_state\":\"COMPLETED\",\"scheduler_state\":\"STOPPED\","
                + "\"error_code\":\"DR_FAILBACK_PROTECTION_RESUME_FAILED\"}";
        Mockito.when(agentManager.easySend(Mockito.eq(103L), Mockito.any(FtctlDrStatusCommand.class)))
                .thenAnswer(invocation -> {
                    FtctlDrStatusCommand command = invocation.getArgument(1);
                    return new FtctlDrStatusAnswer(command, true, "ok", plan.getUuid(), run.getUuid(),
                            "ok", "ERROR", "failback-aborted", 100,
                            null, null, null, 19L,
                            "DR_FAILBACK_PROTECTION_RESUME_FAILED", 0, "", statusJson);
                });
        Mockito.when(drRunDao.findActiveByPlanId(plan.getId())).thenReturn(run);
        Mockito.when(drPlanRuntimeDao.findByPlanId(plan.getId())).thenReturn(runtime);
        Mockito.when(drReplicaDao.listActiveByPlanId(plan.getId())).thenReturn(Collections.singletonList(replica));

        DrAdapterResult result = adapter.refreshPlanProjection(plan);

        Assert.assertTrue(result.isSuccess());
        Assert.assertEquals(DrConstants.RUN_STATE_FAILED, run.getState());
        Assert.assertEquals(DrConstants.PLAN_STATE_FAILED_OVER, plan.getState());
        Assert.assertEquals("TARGET", plan.getActiveSide());
        Assert.assertNull(plan.getLastErrorCode());
        Assert.assertNull(plan.getLastErrorMessage());
        Assert.assertEquals("FAILED_OVER_UNPROTECTED", runtime.getProtectionState());
        Assert.assertEquals(DrConstants.REPLICA_STATE_READY, replica.getState());
        Assert.assertEquals("POWERED_ON", replica.getPowerState());
        Assert.assertEquals("TARGET", replica.getActiveSide());
        Mockito.verify(drPlanDao, Mockito.atLeastOnce()).update(Mockito.eq(plan.getId()), Mockito.same(plan));
        Mockito.verify(drReplicaDao, Mockito.atLeastOnce()).update(Mockito.eq(replica.getId()), Mockito.same(replica));
    }

    @Test
    public void completedFailedFailbackRollbackClearsCurrentPlanError() {
        DrPlanVO plan = new DrPlanVO("ftctl-dr-plan", 1L, 2L, DrConstants.DIRECTION_VMWARE_TO_KVM);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setState(DrConstants.PLAN_STATE_FAILED_OVER);
        plan.setActiveSide("TARGET");
        plan.setCoordinatorWorkerHostId(103L);
        plan.setLastErrorCode("DR_REVERSE_SNAPSHOT_OR_NBD_FAILED");
        plan.setLastErrorMessage("stale reverse transfer failure");
        DrRunVO run = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_FAILBACK);
        run.setState(DrConstants.RUN_STATE_FAILED);
        run.setCompleted(new Date());
        DrReplicaVO replica = new DrReplicaVO(plan.getId(), plan.getTargetSiteId());
        replica.setTargetVmId(256L);
        replica.setState(DrConstants.REPLICA_STATE_ERROR);
        replica.setPowerState("POWERED_ON");
        replica.setActiveSide("TARGET");
        DrPlanRuntimeVO runtime = new DrPlanRuntimeVO(plan.getId());

        String statusJson = "{\"action\":\"dr-failback-abort\",\"state\":\"FAILED_OVER\","
                + "\"active_side\":\"TARGET\",\"source_power_state\":\"POWERED_OFF\","
                + "\"target_power_state\":\"POWERED_ON\",\"failback_commit_outcome\":\"ROLLED_BACK\","
                + "\"rollback_state\":\"COMPLETED\",\"scheduler_state\":\"STOPPED\",\"error_code\":\"\"}";
        Mockito.when(agentManager.easySend(Mockito.eq(103L), Mockito.any(FtctlDrStatusCommand.class)))
                .thenAnswer(invocation -> {
                    FtctlDrStatusCommand command = invocation.getArgument(1);
                    return new FtctlDrStatusAnswer(command, true, "ok", plan.getUuid(), run.getUuid(),
                            "ok", "FAILED_OVER", "failback-aborted", 100,
                            null, null, null, 19L, null, 0, "", statusJson);
                });
        Mockito.when(drRunDao.findActiveByPlanId(plan.getId())).thenReturn(null);
        Mockito.when(drRunDao.findLatestByPlanId(plan.getId())).thenReturn(run);
        Mockito.when(drPlanRuntimeDao.findByPlanId(plan.getId())).thenReturn(runtime);
        Mockito.when(drReplicaDao.listActiveByPlanId(plan.getId())).thenReturn(Collections.singletonList(replica));

        DrAdapterResult result = adapter.refreshPlanProjection(plan);

        Assert.assertTrue(result.isSuccess());
        Assert.assertEquals(DrConstants.RUN_STATE_FAILED, run.getState());
        Assert.assertNotNull(run.getCompleted());
        Assert.assertEquals(DrConstants.PLAN_STATE_FAILED_OVER, plan.getState());
        Assert.assertEquals("TARGET", plan.getActiveSide());
        Assert.assertNull(plan.getLastErrorCode());
        Assert.assertNull(plan.getLastErrorMessage());
        Assert.assertEquals("FAILED_OVER_UNPROTECTED", runtime.getProtectionState());
        Assert.assertEquals(DrConstants.REPLICA_STATE_READY, replica.getState());
    }

    @Test
    public void canceledFailbackPendingCompensationRemainsProjectableWithoutActiveRun() {
        DrPlanVO plan = new DrPlanVO("ftctl-dr-plan", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        DrRunVO canceledFailback = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_FAILBACK);
        canceledFailback.setState(DrConstants.RUN_STATE_CANCELED);
        canceledFailback.setCompleted(new Date());
        Mockito.when(drRunDao.findActiveByPlanId(plan.getId())).thenReturn(null);
        Mockito.when(drRunDao.findLatestByPlanId(plan.getId())).thenReturn(canceledFailback);
        Mockito.when(drFailbackLifecycleService.requiresCancellationCompensation(canceledFailback))
                .thenReturn(true);

        Assert.assertSame(canceledFailback, adapter.resolveRefreshProjectionRun(plan));
    }

    @Test
    public void terminalCanceledFailbackDoesNotReplaceMissingActiveRun() {
        DrPlanVO plan = new DrPlanVO("ftctl-dr-plan", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        DrRunVO canceledFailback = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_FAILBACK);
        canceledFailback.setState(DrConstants.RUN_STATE_CANCELED);
        canceledFailback.setCompleted(new Date());
        Mockito.when(drRunDao.findActiveByPlanId(plan.getId())).thenReturn(null);
        Mockito.when(drRunDao.findLatestByPlanId(plan.getId())).thenReturn(canceledFailback);
        Mockito.when(drFailbackLifecycleService.requiresCancellationCompensation(canceledFailback))
                .thenReturn(false);

        Assert.assertNull(adapter.resolveRefreshProjectionRun(plan));
    }

    @Test
    public void failedFullReseedWithNewerOwnedDurableCycleRemainsProjectable() {
        DrPlanVO plan = new DrPlanVO("late-full-seed", 1L, 2L, DrConstants.DIRECTION_VMWARE_TO_KVM);
        DrRunVO run = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_SYNC);
        run.setRequestJson("{\"mode\":\"FULL_RESEED\"}");
        run.setState(DrConstants.RUN_STATE_FAILED);
        run.setCompleted(new Date());
        run.setAcceptedCycleSequence(1L);
        run.setAcceptedCycleToken(plan.getUuid() + ":1");
        DrSyncCycleVO recovered = durableFullSeedCycle(plan, run, 2L);
        Mockito.when(drRunDao.findActiveByPlanId(plan.getId())).thenReturn(null);
        Mockito.when(drRunDao.findLatestByPlanId(plan.getId())).thenReturn(run);
        Mockito.when(drSyncCycleDao.findLatestCompletedByRunIdAndRequestedMode(run.getId(), "FULL_RESEED"))
                .thenReturn(recovered);
        Mockito.when(drSyncCycleDao.findLatestCompletedByRunIdAndRequestedMode(run.getId(), "FULL_SEED"))
                .thenReturn(null);

        Assert.assertSame(run, adapter.resolveRefreshProjectionRun(plan));
    }

    @Test
    public void correctedTerminalReopensFailedFullReseedAndRebindsDurableCycle() {
        DrPlanVO plan = new DrPlanVO("late-full-seed", 1L, 2L, DrConstants.DIRECTION_VMWARE_TO_KVM);
        DrRunVO run = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_SYNC);
        run.setRequestJson("{\"mode\":\"FULL_RESEED\"}");
        run.setState(DrConstants.RUN_STATE_FAILED);
        run.setCompleted(new Date());
        run.setAcceptedCycleSequence(1L);
        run.setAcceptedCycleToken(plan.getUuid() + ":1");
        run.setErrorCode("DR_CBT_QUERY_FAILED");
        DrSyncCycleVO recovered = durableFullSeedCycle(plan, run, 2L);
        Mockito.when(drSyncCycleDao.findLatestCompletedByRunIdAndRequestedMode(run.getId(), "FULL_RESEED"))
                .thenReturn(recovered);
        Mockito.when(drSyncCycleDao.findLatestCompletedByRunIdAndRequestedMode(run.getId(), "FULL_SEED"))
                .thenReturn(null);
        FtctlDrStatusAnswer status = Mockito.mock(FtctlDrStatusAnswer.class);
        Mockito.when(status.getResult()).thenReturn(true);
        Mockito.when(status.getControlRequestRunUuid()).thenReturn(run.getUuid());
        Mockito.when(status.getWorkerState()).thenReturn("TERMINAL_PUBLISHED");
        Mockito.when(status.getWorkerExitCode()).thenReturn(0);
        Mockito.when(status.getTerminalAuthoritative()).thenReturn(true);
        Mockito.when(status.getState()).thenReturn("READY");
        Mockito.when(status.getStep()).thenReturn("full-resync-completed");

        Assert.assertTrue(adapter.reopenFailedFullReseedRunIfDurablyRecovered(
                plan, run, status, new JsonObject()));
        Assert.assertEquals(DrConstants.RUN_STATE_ACCEPTED, run.getState());
        Assert.assertNull(run.getCompleted());
        Assert.assertEquals(Long.valueOf(2L), run.getAcceptedCycleSequence());
        Assert.assertEquals(plan.getUuid() + ":2", run.getAcceptedCycleToken());
        Assert.assertNull(run.getErrorCode());
        Mockito.verify(drRunDao).update(run.getId(), run);
    }

    private DrSyncCycleVO durableFullSeedCycle(DrPlanVO plan, DrRunVO run, long sequence) {
        DrSyncCycleVO cycle = new DrSyncCycleVO(plan.getId(), run.getUuid(), sequence);
        cycle.setRunId(run.getId());
        cycle.setCycleToken(plan.getUuid() + ":" + sequence);
        cycle.setRequestedMode("FULL_RESEED");
        cycle.setEffectiveMode("FULL_RESEED");
        cycle.setState("READY");
        cycle.setCommitState("LOCAL_DURABLE");
        cycle.setCompleted(new Date());
        return cycle;
    }

    @Test
    public void periodicAuthorityProjectionDoesNotReapplyTerminalReprotectError() {
        DrPlanVO plan = new DrPlanVO("ftctl-dr-plan", 1L, 2L, DrConstants.DIRECTION_VMWARE_TO_KVM);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setState(DrConstants.PLAN_STATE_FAILED_OVER);
        plan.setActiveSide("TARGET");
        plan.setCoordinatorWorkerHostId(103L);
        Date committedAt = new Date(1_000_000L);
        plan.setLastSourceCheckpointAt(new Date(committedAt.getTime() - 209_000L));
        plan.setTargetReadyRpoSeconds(6500);
        DrReplicaVO replica = new DrReplicaVO(plan.getId(), plan.getTargetSiteId());
        replica.setTargetVmId(256L);
        replica.setState(DrConstants.REPLICA_STATE_READY);
        replica.setPowerState("POWERED_ON");
        replica.setActiveSide("TARGET");
        DrPlanRuntimeVO runtime = new DrPlanRuntimeVO(plan.getId());
        runtime.setSchedulerState("STOPPED");
        runtime.setSchedulerDesiredState("RUNNING");
        runtime.setSchedulerHealthState("STOPPED");
        runtime.setSchedulerRecoveryState("SUCCEEDED");
        runtime.setReplicationActivityState("STOPPED");
        runtime.setSchedulerPidAlive(false);
        runtime.setOwnerMatched(true);
        runtime.setSchedulerLeaseEpoch(99L);
        runtime.setAuthoritySequence(999L);
        DrCutoverSessionVO cutover = new DrCutoverSessionVO(plan.getId(), 94L,
                DrConstants.RUN_TYPE_FAILOVER, "PROMOTED");
        cutover.setCloudPromotionState("PROMOTED");
        cutover.setTargetPowerState("POWERED_ON");
        cutover.setEngineAckState("ACKNOWLEDGED");
        cutover.setCloudAuthorityGeneration(1L);
        cutover.setState(DrConstants.PLAN_STATE_FAILED_OVER);
        cutover.setCompletedAt(committedAt);

        String statusJson = "{\"action\":\"dr-reprotect\",\"state\":\"ERROR\","
                + "\"active_side\":\"\",\"worker_state\":\"FAILED\","
                + "\"error_code\":\"" + DrConstants.ERROR_REPROTECT_TARGET_RUNTIME_NOT_RUNNING + "\"}";
        Mockito.when(agentManager.easySend(Mockito.eq(103L), Mockito.any(FtctlDrStatusCommand.class)))
                .thenAnswer(invocation -> {
                    FtctlDrStatusCommand command = invocation.getArgument(1);
                    return new FtctlDrStatusAnswer(command, true, "ok", plan.getUuid(), null,
                            "ok", "ERROR", "reprotect-not-eligible", 100,
                            null, null, null, 15L,
                            DrConstants.ERROR_REPROTECT_TARGET_RUNTIME_NOT_RUNNING, 0, "", statusJson);
                });
        Mockito.when(drPlanRuntimeDao.findByPlanId(plan.getId())).thenReturn(runtime);
        Mockito.when(drCutoverSessionDao.findCurrentAuthorityByPlanId(plan.getId())).thenReturn(cutover);
        Mockito.when(drCutoverSessionDao.findLatestActiveByPlanId(plan.getId())).thenReturn(cutover);
        Mockito.when(drReplicaDao.listActiveByPlanId(plan.getId())).thenReturn(Collections.singletonList(replica));

        DrAdapterResult result = adapter.refreshPlanProjection(plan);

        Assert.assertTrue(result.isSuccess());
        Assert.assertEquals(DrConstants.PLAN_STATE_FAILED_OVER, plan.getState());
        Assert.assertEquals("TARGET", plan.getActiveSide());
        Assert.assertNull(plan.getLastErrorCode());
        Assert.assertEquals(DrConstants.REPLICA_STATE_READY, replica.getState());
        Assert.assertEquals("POWERED_ON", replica.getPowerState());
        Assert.assertEquals("FAILED_OVER_UNPROTECTED", runtime.getProtectionState());
        Assert.assertEquals("STOPPED", runtime.getSchedulerDesiredState());
        Assert.assertEquals("SUPPRESSED", runtime.getSchedulerHealthState());
        Assert.assertEquals(DrConstants.SCHEDULER_RECOVERY_SUPPRESSED, runtime.getSchedulerRecoveryState());
        Assert.assertEquals("STOPPED", runtime.getReplicationActivityState());
        Assert.assertFalse(runtime.isSchedulerPidAlive());
        Assert.assertFalse(runtime.isOwnerMatched());
        Assert.assertEquals(Long.valueOf(209L), runtime.getRpoAgeSeconds());
        Mockito.verify(drPlanDao).update(Mockito.eq(plan.getId()), Mockito.same(plan));
        Mockito.verify(drReplicaDao).update(Mockito.eq(replica.getId()), Mockito.same(replica));
    }

    @Test
    public void remoteKvmCommittedTargetAuthorityIgnoresIdleTargetSchedulerFailure() {
        DrPlanVO plan = new DrPlanVO("remote-kvm-plan", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setState(DrConstants.HEALTH_DEGRADED);
        plan.setActiveSide("TARGET");
        plan.setCoordinatorWorkerHostId(103L);
        plan.setTargetWorkerHostId(103L);
        plan.setLastErrorCode("DR_REPLICATION_CYCLE_FAILED");
        plan.setLastErrorMessage("stale target scheduler failure");
        DrPlanRuntimeVO runtime = new DrPlanRuntimeVO(plan.getId());
        runtime.setProtectionState("ERROR");
        runtime.setSchedulerState("ERROR");
        runtime.setSchedulerDesiredState("RUNNING");
        runtime.setSchedulerHealthState("DEAD");
        runtime.setReplicationActivityState("FAILED");
        runtime.setOwnedProcessCount(1);
        runtime.setReconciliationState("LIVE");
        runtime.setReconciliationRunUuid("stale-worker-run");
        runtime.setReconciliationChecks(2);
        runtime.setWorkerState("RUNNING");
        runtime.setWorkerIdentityState("MATCHED");
        runtime.setWorkerLivenessState("ALIVE");
        runtime.setTransferActivityState("COPYING");
        runtime.setErrorCode("DR_REPLICATION_CYCLE_FAILED");
        runtime.setErrorMessage("stale target scheduler failure");
        runtime.setAuthoritySequence(41L);
        DrReplicaVO replica = new DrReplicaVO(plan.getId(), plan.getTargetSiteId());
        replica.setTargetVmId(283L);
        replica.setState(DrConstants.REPLICA_STATE_READY);
        replica.setPowerState("POWERED_ON");
        replica.setActiveSide("TARGET");
        DrCutoverSessionVO cutover = new DrCutoverSessionVO(plan.getId(), 322L,
                DrConstants.RUN_TYPE_FAILOVER, DrConstants.PLAN_STATE_FAILED_OVER);
        cutover.setCloudPromotionState("PROMOTED");
        cutover.setTargetPowerState("POWERED_ON");
        cutover.setEngineAckState("ACKNOWLEDGED");
        cutover.setCheckpointSequence(12L);
        cutover.setCloudAuthorityGeneration(146L);
        cutover.setCommitContractVersion("DR_CUTOVER_COMMIT_V2");
        cutover.setEngineSessionId(plan.getUuid() + ":failover-run");
        cutover.setManifestSha256(String.join("", Collections.nCopies(64, "d")));
        cutover.setCommitAttemptId("target-repair-attempt");
        cutover.setCommitEnvelopeSha256(String.join("", Collections.nCopies(64, "e")));
        cutover.setSourceFenceState("VERIFIED");
        cutover.setSourcePowerState("POWERED_OFF");
        cutover.setCompletedAt(new Date());
        DrRunVO cutoverRun = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_FAILOVER);
        DrSyncCycleVO latestCompleted = new DrSyncCycleVO(plan.getId(), "source-cycle", 61L);
        latestCompleted.setAuthoritySequence(153L);

        String staleStatus = "{\"state\":\"ERROR\",\"step\":\"replication-cycle-failed\","
                + "\"scheduler_state\":\"ERROR\",\"owned_process_count\":1,"
                + "\"worker_state\":\"RUNNING\",\"worker_identity_state\":\"MATCHED\","
                + "\"worker_liveness_state\":\"ALIVE\",\"reconciliation_required\":true,"
                + "\"runtime_endpoints_drained\":false,"
                + "\"error_code\":\"DR_REPLICATION_CYCLE_FAILED\"}";
        Mockito.when(agentManager.easySend(Mockito.eq(103L), Mockito.any(FtctlDrStatusCommand.class)))
                .thenAnswer(invocation -> {
                    FtctlDrStatusCommand command = invocation.getArgument(1);
                    return new FtctlDrStatusAnswer(command, true, "ok", plan.getUuid(), null,
                            "ok", "ERROR", "replication-cycle-failed", 100,
                            null, null, null, 12L, "DR_REPLICATION_CYCLE_FAILED", 0, "", staleStatus);
                });
        Mockito.when(drRunDao.findActiveByPlanId(plan.getId())).thenReturn(null);
        Mockito.when(drRunDao.findById(cutover.getRunId())).thenReturn(cutoverRun);
        Mockito.when(drRemoteAgentClient.isRemoteKvmSource(plan)).thenReturn(true);
        Mockito.when(drCutoverSessionDao.findCurrentAuthorityByPlanId(plan.getId())).thenReturn(cutover);
        Mockito.when(drPlanRuntimeDao.findByPlanId(plan.getId())).thenReturn(runtime);
        Mockito.when(drSyncCycleDao.findLatestCompletedByPlanId(plan.getId())).thenReturn(latestCompleted);
        Mockito.when(drReplicaDao.listActiveByPlanId(plan.getId())).thenReturn(Collections.singletonList(replica));
        Mockito.when(drTargetMaterializationService.ensureTargetPoweredOn(plan.getId()))
                .thenReturn(new DrTargetPowerOnResult(283L, "target-vm-uuid", "POWERED_ON",
                        "POWER_STATE_VALIDATED", new Date(), new Date(), false));
        Mockito.when(agentManager.easySend(Mockito.eq(103L), Mockito.any(FtctlDrActionCommand.class)))
                .thenAnswer(invocation -> new Answer(invocation.getArgument(1), true, "target authority repaired"));

        DrAdapterResult result = adapter.refreshPlanProjection(plan);

        Assert.assertTrue(result.isSuccess());
        Assert.assertEquals(DrConstants.PLAN_STATE_FAILED_OVER, plan.getState());
        Assert.assertEquals("TARGET", plan.getActiveSide());
        Assert.assertNull(plan.getLastErrorCode());
        Assert.assertEquals("FAILED_OVER_UNPROTECTED", runtime.getProtectionState());
        Assert.assertEquals("STOPPED", runtime.getSchedulerState());
        Assert.assertEquals("SUPPRESSED", runtime.getSchedulerHealthState());
        Assert.assertEquals("STOPPED", runtime.getReplicationActivityState());
        Assert.assertEquals(0, runtime.getOwnedProcessCount());
        Assert.assertEquals("NONE", runtime.getReconciliationState());
        Assert.assertEquals("IDLE", runtime.getWorkerState());
        Assert.assertEquals("STOPPED", runtime.getWorkerLivenessState());
        Assert.assertNull(runtime.getErrorCode());
        Assert.assertEquals(153L, runtime.getAuthoritySequence());
        Assert.assertFalse(runtime.isRpoOverdue());
        Mockito.verify(drPlanDao).update(Mockito.eq(plan.getId()), Mockito.same(plan));
        Mockito.verify(agentManager).easySend(Mockito.eq(103L), Mockito.argThat(command ->
                command instanceof FtctlDrActionCommand
                        && ((FtctlDrActionCommand) command).getAction() == FtctlDrActionCommand.Action.CUTOVER_COMMIT
                        && "target".equals(((FtctlDrActionCommand) command).getRole())));
    }

    @Test
    public void committedTargetProjectionFreezesPlanRpoAtCutover() {
        DrPlanVO plan = new DrPlanVO("ftctl-dr-plan", 1L, 2L, DrConstants.DIRECTION_VMWARE_TO_KVM);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setState(DrConstants.PLAN_STATE_FAILED_OVER);
        plan.setActiveSide("TARGET");
        plan.setCoordinatorWorkerHostId(103L);
        Date committedAt = new Date(1_000_000L);
        plan.setLastSourceCheckpointAt(new Date(committedAt.getTime() - 209_000L));
        plan.setTargetReadyRpoSeconds(6500);
        DrPlanRuntimeVO runtime = new DrPlanRuntimeVO(plan.getId());
        DrReplicaVO replica = new DrReplicaVO(plan.getId(), plan.getTargetSiteId());
        replica.setTargetVmId(256L);
        replica.setState(DrConstants.REPLICA_STATE_READY);
        replica.setPowerState("POWERED_ON");
        replica.setActiveSide("TARGET");
        DrReplicaDiskVO replicaDisk = new DrReplicaDiskVO(replica.getId(), "disk-0");
        replicaDisk.setSourceDiskRef("checkpoint-1192");
        replicaDisk.setTargetDiskRef("target-volume");
        replicaDisk.setFormat("raw");
        replicaDisk.setTargetVolumeId(485L);
        VolumeVO targetVolume = Mockito.mock(VolumeVO.class);
        DrCutoverSessionVO cutover = new DrCutoverSessionVO(plan.getId(), 94L,
                DrConstants.RUN_TYPE_FAILOVER, DrConstants.PLAN_STATE_FAILED_OVER);
        cutover.setCheckpointSequence(1192L);
        cutover.setManifestSha256("1110e8073620358b1fc2944dd773f8b69a3af16758fbc32a47dc58d413c81c23");
        cutover.setCloudPromotionState("PROMOTED");
        cutover.setTargetPowerState("POWERED_ON");
        cutover.setEngineAckState("ACKNOWLEDGED");
        cutover.setCloudAuthorityGeneration(1L);
        cutover.setCompletedAt(committedAt);

        String statusJson = "{\"state\":\"FAILED_OVER\",\"active_side\":\"TARGET\","
                + "\"scheduler_state\":\"STOPPED\",\"scheduler_pid_alive\":false}";
        Mockito.when(agentManager.easySend(Mockito.eq(103L), Mockito.any(FtctlDrStatusCommand.class)))
                .thenAnswer(invocation -> {
                    FtctlDrStatusCommand command = invocation.getArgument(1);
                    return new FtctlDrStatusAnswer(command, true, "ok", plan.getUuid(), null,
                            "ok", "FAILED_OVER", "cloud-promotion-committed", 100,
                            null, null, 6500, 15L, null, 0, "", statusJson);
                });
        Mockito.when(drPlanRuntimeDao.findByPlanId(plan.getId())).thenReturn(runtime);
        Mockito.when(drCutoverSessionDao.findCurrentAuthorityByPlanId(plan.getId())).thenReturn(cutover);
        Mockito.when(drReplicaDao.listActiveByPlanId(plan.getId())).thenReturn(Collections.singletonList(replica));
        Mockito.when(drReplicaDiskDao.listActiveByReplicaId(replica.getId()))
                .thenReturn(Collections.singletonList(replicaDisk));
        Mockito.when(drCutoverDiskDao.listActiveBySessionId(cutover.getId())).thenReturn(Collections.emptyList());
        Mockito.when(volumeDao.findById(485L)).thenReturn(targetVolume);
        Mockito.when(targetVolume.getUuid()).thenReturn("target-volume-uuid");
        Mockito.when(targetVolume.getChainInfo()).thenReturn("{\"targetType\":\"rbd\",\"storagePoolType\":\"RBD\"}");

        DrAdapterResult result = adapter.refreshPlanProjection(plan);

        Assert.assertTrue(result.isSuccess());
        Assert.assertEquals(Integer.valueOf(209), plan.getTargetReadyRpoSeconds());
        Assert.assertEquals(Long.valueOf(209L), runtime.getRpoAgeSeconds());
        Assert.assertFalse(runtime.isRpoOverdue());
        Mockito.verify(drCutoverDiskDao).persist(Mockito.argThat(disk ->
                disk.getDiskIndex() == 0
                        && "RBD".equals(disk.getProvider())
                        && Long.valueOf(485L).equals(disk.getTargetVolumeId())
                        && "target-volume-uuid".equals(disk.getTargetVolumeUuid())
                        && Long.valueOf(1192L).equals(disk.getCheckpointSequence())
                        && cutover.getManifestSha256().equals(disk.getManifestSha256())));
    }

    @Test
    public void refreshPlanProjectionUsesOperatorReadableMessageForVmwareMoverFailure() {
        DrPlanVO plan = new DrPlanVO("ftctl-dr-plan", 1L, 2L, DrConstants.DIRECTION_VMWARE_TO_KVM);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setState(DrConstants.PLAN_STATE_SYNCING);
        plan.setCoordinatorWorkerHostId(103L);
        DrRunVO run = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_SYNC);
        run.setState(DrConstants.RUN_STATE_ACCEPTED);
        DrReplicaVO replica = new DrReplicaVO(plan.getId(), plan.getTargetSiteId());
        replica.setState(DrConstants.REPLICA_STATE_SKELETON_READY);

        String statusJson = "{\"state\":\"ERROR\",\"step\":\"replication-cycle-failed\","
                + "\"worker_state\":\"FAILED\",\"worker_exit_code\":68,"
                + "\"error_code\":\"" + DrConstants.ERROR_VMWARE_MOVER_FAILED + "\"}";
        Mockito.when(agentManager.easySend(Mockito.eq(103L), Mockito.any(FtctlDrStatusCommand.class))).thenAnswer(invocation -> {
            FtctlDrStatusCommand command = invocation.getArgument(1);
            return new FtctlDrStatusAnswer(command, true, "100% | qemu-img convert failed", plan.getUuid(), run.getUuid(),
                    "ok", "ERROR", "replication-cycle-failed", 100,
                    null, null, null, 15L, DrConstants.ERROR_VMWARE_MOVER_FAILED, 0,
                    "100% | qemu-img convert failed", statusJson);
        });
        Mockito.when(drRunDao.findActiveByPlanId(plan.getId())).thenReturn(run);
        Mockito.when(drPlanDao.findById(plan.getId())).thenReturn(plan);
        Mockito.when(drReplicaDao.listActiveByPlanId(plan.getId())).thenReturn(Collections.singletonList(replica));

        DrAdapterResult result = adapter.refreshPlanProjection(plan);

        Assert.assertTrue(result.isSuccess());
        Assert.assertEquals(DrConstants.RUN_STATE_FAILED, run.getState());
        Assert.assertEquals(DrConstants.ERROR_VMWARE_MOVER_FAILED, run.getErrorCode());
        Assert.assertEquals("VMware data mover failed while copying source disk data", run.getErrorMessage());
        Assert.assertEquals(DrConstants.PLAN_STATE_ERROR, plan.getState());
        Assert.assertEquals(DrConstants.ERROR_VMWARE_MOVER_FAILED, plan.getLastErrorCode());
        Mockito.verify(drReplicaDao).update(Mockito.eq(replica.getId()), Mockito.same(replica));
        Mockito.verify(drEventDao, Mockito.atLeastOnce()).persist(Mockito.any(DrEventVO.class));
    }

    @Test
    public void refreshPlanProjectionPreservesCycleCommitFailureAsTypedError() {
        DrPlanVO plan = new DrPlanVO("ftctl-dr-plan", 1L, 2L, DrConstants.DIRECTION_VMWARE_TO_KVM);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setState(DrConstants.PLAN_STATE_SYNCING);
        plan.setCoordinatorWorkerHostId(103L);
        DrRunVO run = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_SYNC);
        run.setState(DrConstants.RUN_STATE_ACCEPTED);
        DrReplicaVO replica = new DrReplicaVO(plan.getId(), plan.getTargetSiteId());
        replica.setState(DrConstants.REPLICA_STATE_SKELETON_READY);

        String errorMessage = "Disk data copied, but cycle metadata validation failed";
        String statusJson = "{\"state\":\"ERROR\",\"step\":\"replication-cycle-failed\","
                + "\"worker_state\":\"FAILED\",\"worker_exit_code\":87,"
                + "\"error_code\":\"" + DrConstants.ERROR_CBT_METRICS_INVALID + "\","
                + "\"error_message\":\"" + errorMessage + "\","
                + "\"failed_component\":\"vmware-mover\","
                + "\"data_commit_state\":\"DATA_COPIED_METADATA_FAILED\","
                + "\"data_copied\":true,\"metadata_committed\":false,\"target_durable\":false,"
                + "\"cycle_retry_mode\":\"RESEED_REQUIRED\"}";
        Mockito.when(agentManager.easySend(Mockito.eq(103L), Mockito.any(FtctlDrStatusCommand.class))).thenAnswer(invocation -> {
            FtctlDrStatusCommand command = invocation.getArgument(1);
            FtctlDrStatusAnswer answer = new FtctlDrStatusAnswer(command, true, errorMessage, plan.getUuid(), run.getUuid(),
                    "ok", "ERROR", "replication-cycle-failed", 100,
                    null, null, null, 15L, DrConstants.ERROR_CBT_METRICS_INVALID, 0,
                    errorMessage, statusJson);
            answer.setErrorMessage(errorMessage);
            answer.setFailedComponent("vmware-mover");
            answer.setDataCommitState("DATA_COPIED_METADATA_FAILED");
            answer.setDataCopied(Boolean.TRUE);
            answer.setMetadataCommitted(Boolean.FALSE);
            answer.setTargetDurable(Boolean.FALSE);
            answer.setCycleRetryMode("RESEED_REQUIRED");
            return answer;
        });
        Mockito.when(drRunDao.findActiveByPlanId(plan.getId())).thenReturn(run);
        Mockito.when(drPlanDao.findById(plan.getId())).thenReturn(plan);
        Mockito.when(drReplicaDao.listActiveByPlanId(plan.getId())).thenReturn(Collections.singletonList(replica));

        DrAdapterResult result = adapter.refreshPlanProjection(plan);

        Assert.assertTrue(result.isSuccess());
        Assert.assertEquals(DrConstants.RUN_STATE_FAILED, run.getState());
        Assert.assertEquals(DrConstants.ERROR_CBT_METRICS_INVALID, run.getErrorCode());
        Assert.assertEquals(errorMessage, run.getErrorMessage());
        Assert.assertFalse(run.getErrorMessage().contains("{\"state\""));
        Assert.assertEquals(DrConstants.PLAN_STATE_ERROR, plan.getState());
        Assert.assertEquals(DrConstants.ERROR_CBT_METRICS_INVALID, plan.getLastErrorCode());
        Assert.assertEquals(errorMessage, plan.getLastErrorMessage());
    }

    @Test
    public void refreshPlanProjectionKeepsInitialSeedPendingRunHealthyWhenTargetVmIsAbsent() {
        DrPlanVO plan = new DrPlanVO("ftctl-dr-plan", 1L, 2L, DrConstants.DIRECTION_VMWARE_TO_KVM);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setState(DrConstants.PLAN_STATE_SYNCING);
        plan.setCoordinatorWorkerHostId(103L);
        plan.setLastErrorCode(DrConstants.ERROR_TARGET_VM_NOT_FOUND);
        plan.setLastErrorMessage("stale target VM missing projection");
        DrRunVO run = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_SYNC);
        run.setState(DrConstants.RUN_STATE_ACCEPTED);
        run.setErrorCode(DrConstants.ERROR_TARGET_VM_NOT_FOUND);
        run.setErrorMessage("stale target VM missing projection");
        DrReplicaVO replica = new DrReplicaVO(plan.getId(), plan.getTargetSiteId());
        replica.setState(DrConstants.REPLICA_STATE_SKELETON_READY);

        String statusJson = "{\"state\":\"SYNCING\",\"step\":\"full-seed-transfer\",\"progress\":40,"
                + "\"worker_state\":\"RUNNING\",\"target_materialized\":false,"
                + "\"target_vm_present\":false,\"target_storage_present\":true,"
                + "\"target_network_present\":false,\"restore_point_present\":false,"
                + "\"source_open\":{\"ready\":true},\"source_snapshot\":{\"ready\":true},"
                + "\"cbt_status\":{\"enabled\":true}}";
        Mockito.when(agentManager.easySend(Mockito.eq(103L), Mockito.any(FtctlDrStatusCommand.class))).thenAnswer(invocation -> {
            FtctlDrStatusCommand command = invocation.getArgument(1);
            return new FtctlDrStatusAnswer(command, true, "ok", plan.getUuid(), run.getUuid(),
                    "ok", "SYNCING", "full-seed-transfer", 40,
                    null, null, null, null, null, 0, "", statusJson);
        });
        Mockito.when(drRunDao.findActiveByPlanId(plan.getId())).thenReturn(run);
        Mockito.when(drReplicaDao.listActiveByPlanId(plan.getId())).thenReturn(Collections.singletonList(replica));

        DrAdapterResult result = adapter.refreshPlanProjection(plan);

        Assert.assertTrue(result.isSuccess());
        Assert.assertEquals(DrConstants.RUN_STATE_ACCEPTED, run.getState());
        Assert.assertEquals("syncing", run.getProjectionState());
        Assert.assertEquals("runtime-projection", run.getCurrentStepName());
        Assert.assertNull(run.getErrorCode());
        Assert.assertNull(run.getErrorMessage());
        Assert.assertNull(run.getCompleted());
        Assert.assertNull(plan.getLastErrorCode());
        Assert.assertNull(plan.getLastErrorMessage());

        ArgumentCaptor<DrRunStepVO> stepCaptor = ArgumentCaptor.forClass(DrRunStepVO.class);
        Mockito.verify(drRunStepDao).persist(stepCaptor.capture());
        DrRunStepVO step = stepCaptor.getValue();
        Assert.assertEquals(DrConstants.STEP_STATE_RUNNING, step.getState());
        Assert.assertEquals(Integer.valueOf(70), step.getProgress());
        Assert.assertNull(step.getErrorCode());
        Assert.assertEquals("FTCTL_DR sync has not materialized the target VM reference yet", step.getErrorMessage());

        Mockito.verify(drRunDao).update(Mockito.eq(run.getId()), Mockito.same(run));
        Mockito.verify(drPlanDao).update(Mockito.eq(plan.getId()), Mockito.same(plan));
        Mockito.verify(drEventDao, Mockito.never()).persist(Mockito.any(DrEventVO.class));
    }

    @Test
    public void refreshPlanProjectionUsesCloudAuthorityForKvmTargetVmAndNetwork() {
        DrPlanVO plan = new DrPlanVO("ftctl-dr-plan", 1L, 2L, DrConstants.DIRECTION_VMWARE_TO_KVM);
        ReflectionTestUtils.setField(plan, "id", 51L);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setState(DrConstants.PLAN_STATE_SYNCING);
        plan.setCoordinatorWorkerHostId(103L);
        DrRunVO run = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_SYNC);
        run.setState(DrConstants.RUN_STATE_ACCEPTED);
        run.setCompleted(new Date());
        DrReplicaVO replica = new DrReplicaVO(plan.getId(), plan.getTargetSiteId());
        replica.setTargetVmId(9L);
        DrRestorePointVO restorePoint = new DrRestorePointVO(plan.getId(), "FTCTL_DR_CHECKPOINT");
        restorePoint.setState("READY");

        String now = java.time.Instant.now().toString();
        String statusJson = "{\"state\":\"SYNCING\",\"step\":\"target-checkpoint-ready\",\"progress\":100,"
                + "\"cycle_state\":\"IDLE\",\"current_checkpoint_state\":\"COMPLETED\","
                + "\"scheduler_state\":\"RUNNING\",\"scheduler_health\":\"HEALTHY\","
                + "\"scheduler_pid_alive\":true,\"owner_matched\":true,"
                + "\"scheduler_session_uuid\":\"" + plan.getUuid() + "\","
                + "\"worker_heartbeat_at\":\"" + now + "\",\"target_materialized\":false,"
                + "\"target_vm_present\":false,\"target_storage_present\":true,"
                + "\"target_network_present\":false,\"restore_point_present\":true,"
                + "\"last_target_durable_at\":\"" + now + "\"}";
        Mockito.when(agentManager.easySend(Mockito.eq(103L), Mockito.any(FtctlDrStatusCommand.class))).thenAnswer(invocation -> {
            FtctlDrStatusCommand command = invocation.getArgument(1);
            return new FtctlDrStatusAnswer(command, true, "ok", plan.getUuid(), run.getUuid(),
                    "ok", "SYNCING", "target-checkpoint-ready", 100,
                    now, now, 2,
                    9L, null, 0, "", statusJson);
        });
        Mockito.when(drRunDao.findActiveByPlanId(plan.getId())).thenReturn(run);
        Mockito.when(drReplicaDao.listActiveByPlanId(plan.getId())).thenReturn(Collections.singletonList(replica));
        Mockito.when(drRestorePointDao.findLatestTargetReadyByPlanId(plan.getId())).thenReturn(restorePoint);

        DrAdapterResult result = adapter.refreshPlanProjection(plan);

        Assert.assertTrue(result.isSuccess());
        Assert.assertEquals(DrConstants.RUN_STATE_SUCCEEDED, run.getState());
        Assert.assertEquals("succeeded", run.getProjectionState());
        Assert.assertNotNull(run.getCompleted());
        Assert.assertEquals(DrConstants.PLAN_STATE_READY, plan.getState());
    }

    @Test
    public void durableKvmCycleReconcilesPrecreatedReplicaAfterInitiatingRunCompleted() {
        DrPlanVO plan = new DrPlanVO("kvm-owner-plan", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        plan.setActiveSide(DrConstants.AUTHORITY_SIDE_SOURCE);
        DrRunVO completedRun = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_RECOVER_SYNC);
        completedRun.setState(DrConstants.RUN_STATE_SUCCEEDED);
        completedRun.setCompleted(new Date());
        DrReplicaVO replica = new DrReplicaVO(plan.getId(), plan.getTargetSiteId());
        replica.setTargetVmId(91L);
        replica.setState(DrConstants.REPLICA_STATE_SKELETON_READY);
        replica.setOwnershipState("VALID");
        Mockito.when(drReplicaDao.listActiveByPlanId(plan.getId())).thenReturn(Collections.singletonList(replica));

        FtctlDrStatusCommand command = new FtctlDrStatusCommand(plan.getUuid(), null,
                FtctlDrStatusCommand.StatusScope.PLAN_AUTHORITY);
        FtctlDrStatusAnswer status = new FtctlDrStatusAnswer(command, true, "ok", plan.getUuid(), completedRun.getUuid(),
                "ok", "READY", "target-checkpoint-ready", 100,
                "2026-08-24T09:51:42Z", "2026-08-24T09:51:44Z", 2,
                91L, null, 0, "", "{}");
        JsonObject runtime = JsonParser.parseString("{\"last_target_durable_at\":\"2026-08-24T09:51:44Z\"," +
                "\"latest_completed_commit_state\":\"LOCAL_DURABLE\"}").getAsJsonObject();

        adapter.reconcileDurableTargetMaterialization(plan, completedRun, status, runtime);

        Mockito.verify(drTargetMaterializationService).enqueueDurableReconciliation(
                Mockito.eq(plan.getId()), Mockito.eq(completedRun.getId()), Mockito.anyString());
    }

    @Test
    public void durableKvmCycleDoesNotLetFailbackRunOwnTargetReconciliation() {
        DrPlanVO plan = new DrPlanVO("kvm-failback-plan", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        plan.setActiveSide(DrConstants.AUTHORITY_SIDE_TARGET);
        plan.setState(DrConstants.PLAN_STATE_FAILED_OVER);
        DrRunVO failbackRun = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_FAILBACK);
        DrReplicaVO replica = new DrReplicaVO(plan.getId(), plan.getTargetSiteId());
        replica.setTargetVmId(91L);
        replica.setState(DrConstants.REPLICA_STATE_SKELETON_READY);
        FtctlDrStatusCommand command = new FtctlDrStatusCommand(plan.getUuid(), failbackRun.getUuid(),
                FtctlDrStatusCommand.StatusScope.PLAN_AUTHORITY);
        FtctlDrStatusAnswer status = new FtctlDrStatusAnswer(command, true, "ok", plan.getUuid(), failbackRun.getUuid(),
                "ok", "FAILED_OVER_UNPROTECTED", "target-active", 100,
                "2026-08-24T09:51:42Z", "2026-08-24T09:51:44Z", 2,
                91L, null, 0, "", "{}");
        JsonObject runtime = JsonParser.parseString("{\"last_target_durable_at\":\"2026-08-24T09:51:44Z\"," +
                "\"latest_completed_commit_state\":\"LOCAL_DURABLE\"}").getAsJsonObject();

        adapter.reconcileDurableTargetMaterialization(plan, failbackRun, status, runtime);

        Mockito.verifyNoInteractions(drTargetMaterializationService);
    }

    @Test
    public void failbackProjectionRejectsRuntimeOwnedByOlderRun() {
        DrRunVO run = new DrRunVO(51L, DrConstants.RUN_TYPE_FAILBACK);
        JsonObject runtime = JsonParser.parseString("{\"run_uuid\":\"older-run\","
                + "\"control_request_run_uuid\":\"older-run\","
                + "\"failback_session_id\":\"plan:older-run\"}").getAsJsonObject();

        Assert.assertFalse(adapter.runtimeBelongsToRun(runtime, run));

        runtime.addProperty("control_request_run_uuid", run.getUuid());
        Assert.assertFalse(adapter.runtimeBelongsToRun(runtime, run));

        runtime.addProperty("run_uuid", run.getUuid());
        runtime.addProperty("failback_session_id", "plan:" + run.getUuid());
        Assert.assertTrue(adapter.runtimeBelongsToRun(runtime, run));
    }

    @Test
    public void failbackProjectionRejectsRunNotFoundPayloadMixedWithOlderPlanOwner() {
        DrRunVO run = new DrRunVO(51L, DrConstants.RUN_TYPE_FAILBACK);
        JsonObject runtime = JsonParser.parseString("{\"result\":\"run_not_found\","
                + "\"run_uuid\":\"" + run.getUuid() + "\","
                + "\"control_request_run_uuid\":\"older-run\","
                + "\"failback_session_id\":\"plan:older-run\"}").getAsJsonObject();

        Assert.assertFalse(adapter.runtimeBelongsToRun(runtime, run));
    }

    @Test
    public void refreshPlanProjectionAcceptsLowerSequenceFromNewSchedulerLeaseEpoch() {
        DrPlanVO plan = new DrPlanVO("ftctl-dr-plan", 1L, 2L, DrConstants.DIRECTION_VMWARE_TO_KVM);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setState(DrConstants.PLAN_STATE_SYNCING);
        plan.setCoordinatorWorkerHostId(103L);
        DrRunVO run = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_SYNC);
        run.setState(DrConstants.RUN_STATE_ACCEPTED);
        DrPlanRuntimeVO authority = new DrPlanRuntimeVO(plan.getId());
        authority.setEngineRunUuid("previous-run");
        authority.setRuntimeGeneration(24L);
        authority.setSchedulerSessionUuid(plan.getUuid());
        authority.setSchedulerLeaseEpoch(2L);
        authority.setAuthoritySequence(24L);

        String statusJson = "{\"state\":\"SYNCING\",\"runtime_generation\":6,"
                + "\"scheduler_state\":\"RUNNING\",\"scheduler_pid_alive\":true,"
                + "\"scheduler_session_uuid\":\"" + plan.getUuid() + "\","
                + "\"scheduler_lease_epoch\":3,\"authority_sequence\":6,"
                + "\"scheduler_health\":\"HEALTHY\",\"owner_matched\":true,"
                + "\"active_worker_run_uuid\":\"" + run.getUuid() + "\","
                + "\"cycle_state\":\"RUNNING\"}";
        Mockito.when(agentManager.easySend(Mockito.eq(103L), Mockito.any(FtctlDrStatusCommand.class))).thenAnswer(invocation -> {
            FtctlDrStatusCommand command = invocation.getArgument(1);
            FtctlDrStatusAnswer answer = new FtctlDrStatusAnswer(command, true, "ok", plan.getUuid(), run.getUuid(),
                    "ok", "SYNCING", "incremental-transfer", 40,
                    null, null, null, null, null, 0, "", statusJson);
            answer.setSchedulerSessionUuid(plan.getUuid());
            answer.setSchedulerLeaseEpoch(3L);
            answer.setAuthoritySequence(6L);
            answer.setSchedulerHealth("HEALTHY");
            answer.setSchedulerPidAlive(true);
            answer.setOwnerMatched(true);
            answer.setActiveWorkerRunUuid(run.getUuid());
            return answer;
        });
        Mockito.when(drRunDao.findActiveByPlanId(plan.getId())).thenReturn(run);
        Mockito.when(drPlanRuntimeDao.findByPlanId(plan.getId())).thenReturn(authority);

        DrAdapterResult result = adapter.refreshPlanProjection(plan);

        Assert.assertTrue(result.isSuccess());
        Assert.assertEquals(run.getUuid(), authority.getEngineRunUuid());
        Assert.assertEquals(6L, authority.getRuntimeGeneration());
        Assert.assertEquals(3L, authority.getSchedulerLeaseEpoch());
        Assert.assertEquals(6L, authority.getAuthoritySequence());
        Assert.assertTrue(authority.isSchedulerPidAlive());
    }

    @Test
    public void completedRemoteFailbackAcceptsLowerTupleFromRestoredSourceAuthorityOnce() {
        DrPlanVO plan = new DrPlanVO("remote-kvm-plan", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        ReflectionTestUtils.setField(plan, "id", 51L);
        plan.setActiveSide(DrConstants.AUTHORITY_SIDE_SOURCE);
        plan.setRpoSeconds(300);

        DrPlanRuntimeVO authority = new DrPlanRuntimeVO(plan.getId());
        ReflectionTestUtils.setField(authority, "id", 26L);
        authority.setSchedulerLeaseEpoch(2L);
        authority.setAuthoritySequence(41L);
        authority.setSchedulerState("STOPPED");
        authority.setSchedulerHealthState("SUPPRESSED");
        authority.setSchedulerRecoveryState(DrConstants.SCHEDULER_RECOVERY_SUPPRESSED);
        authority.setProtectionState("FAILED_OVER_UNPROTECTED");

        DrFailbackSessionVO session = new DrFailbackSessionVO(plan.getId(), 355L,
                plan.getUuid() + ":failback-run", "COMPLETED");
        session.setEngineAckState("ACKNOWLEDGED");
        session.setRequiredPostFailbackCheckpointSequence(10L);
        session.setPostFailbackCheckpointSequence(10L);

        String now = java.time.Instant.now().toString();
        JsonObject runtime = JsonParser.parseString("{\"state\":\"READY\","
                + "\"scheduler_state\":\"RUNNING\",\"scheduler_health\":\"HEALTHY\","
                + "\"scheduler_pid_alive\":true,\"owner_matched\":true,"
                + "\"scheduler_session_uuid\":\"" + plan.getUuid() + "\","
                + "\"scheduler_lease_epoch\":1,\"authority_sequence\":35,"
                + "\"latest_completed_checkpoint_sequence\":17,"
                + "\"latest_completed_target_durable_at\":\"" + now + "\","
                + "\"worker_heartbeat_at\":\"" + now + "\","
                + "\"target_materialized\":true,\"replication_activity\":\"IDLE\"}")
                .getAsJsonObject();
        FtctlDrStatusCommand command = new FtctlDrStatusCommand(plan.getUuid(), null,
                FtctlDrStatusCommand.StatusScope.PLAN_AUTHORITY);
        FtctlDrStatusAnswer status = new FtctlDrStatusAnswer(command, true, "ok");
        status.setSchedulerLeaseEpoch(1L);
        status.setAuthoritySequence(35L);
        status.setSchedulerSessionUuid(plan.getUuid());
        status.setSchedulerHealth("HEALTHY");
        status.setSchedulerPidAlive(true);
        status.setOwnerMatched(true);
        status.setLatestCompletedCheckpointSequence(17L);
        status.setLatestCompletedTargetDurableAt(now);

        Mockito.when(drRemoteAgentClient.isRemoteKvmSource(plan)).thenReturn(true);
        Mockito.when(drFailbackSessionDao.findLatestActiveByPlanId(plan.getId())).thenReturn(session);
        Mockito.when(drPlanRuntimeDao.findByPlanId(plan.getId())).thenReturn(authority);

        ReflectionTestUtils.invokeMethod(adapter, "projectProtectionAuthority", plan, null, status, runtime);

        Assert.assertEquals(1L, authority.getSchedulerLeaseEpoch());
        Assert.assertEquals(35L, authority.getAuthoritySequence());
        Assert.assertEquals("RUNNING", authority.getSchedulerState());
        Assert.assertEquals("HEALTHY", authority.getSchedulerHealthState());
        Assert.assertEquals(DrConstants.PLAN_STATE_READY, authority.getProtectionState());
        Mockito.verify(drPlanRuntimeDao).update(authority.getId(), authority);
    }

    @Test
    public void remoteKvmSourceAcceptsHigherGlobalAuthorityAfterWorkerRelocation() {
        DrPlanVO plan = new DrPlanVO("remote-kvm-plan", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        ReflectionTestUtils.setField(plan, "id", 51L);
        plan.setActiveSide(DrConstants.AUTHORITY_SIDE_SOURCE);
        plan.setRpoSeconds(300);

        DrPlanRuntimeVO authority = new DrPlanRuntimeVO(plan.getId());
        ReflectionTestUtils.setField(authority, "id", 26L);
        authority.setSchedulerLeaseEpoch(4L);
        authority.setAuthoritySequence(724L);
        authority.setRuntimeGeneration(724L);
        authority.setSchedulerState("ERROR");
        authority.setSchedulerHealthState("DEAD");
        authority.setProtectionState(DrConstants.PLAN_STATE_ERROR);

        String now = java.time.Instant.now().toString();
        JsonObject runtime = JsonParser.parseString("{\"state\":\"READY\","
                + "\"scheduler_state\":\"RUNNING\",\"scheduler_health\":\"HEALTHY\","
                + "\"scheduler_pid_alive\":true,\"owner_matched\":true,"
                + "\"scheduler_session_uuid\":\"" + plan.getUuid() + "\","
                + "\"scheduler_lease_epoch\":1,\"authority_sequence\":750,"
                + "\"latest_completed_checkpoint_sequence\":12,"
                + "\"latest_completed_target_durable_at\":\"" + now + "\","
                + "\"worker_heartbeat_at\":\"" + now + "\","
                + "\"target_materialized\":true,\"replication_activity\":\"IDLE\"}")
                .getAsJsonObject();
        FtctlDrStatusCommand command = new FtctlDrStatusCommand(plan.getUuid(), null,
                FtctlDrStatusCommand.StatusScope.PLAN_AUTHORITY);
        FtctlDrStatusAnswer status = new FtctlDrStatusAnswer(command, true, "ok");
        status.setSchedulerLeaseEpoch(1L);
        status.setAuthoritySequence(750L);
        status.setSchedulerSessionUuid(plan.getUuid());
        status.setSchedulerHealth("HEALTHY");
        status.setSchedulerPidAlive(true);
        status.setOwnerMatched(true);
        status.setLatestCompletedCheckpointSequence(12L);
        status.setLatestCompletedTargetDurableAt(now);

        Mockito.when(drRemoteAgentClient.isRemoteKvmSource(plan)).thenReturn(true);
        Mockito.when(drPlanRuntimeDao.findByPlanId(plan.getId())).thenReturn(authority);

        ReflectionTestUtils.invokeMethod(adapter, "projectProtectionAuthority", plan, null, status, runtime);

        Assert.assertEquals(1L, authority.getSchedulerLeaseEpoch());
        Assert.assertEquals(750L, authority.getAuthoritySequence());
        Assert.assertEquals(750L, authority.getRuntimeGeneration());
        Assert.assertEquals("RUNNING", authority.getSchedulerState());
        Assert.assertEquals("HEALTHY", authority.getSchedulerHealthState());
        Assert.assertEquals(DrConstants.PLAN_STATE_READY, authority.getProtectionState());
        Mockito.verify(drPlanRuntimeDao).update(authority.getId(), authority);
    }

    @Test
    public void remoteKvmSourceRejectsLowerGlobalAuthorityFromStaleWorker() {
        DrPlanRuntimeVO authority = new DrPlanRuntimeVO(51L);
        authority.setSchedulerLeaseEpoch(1L);
        authority.setAuthoritySequence(750L);

        Assert.assertTrue(adapter.isStaleRemoteSourceAuthority(authority, 9L, 724L));
        Assert.assertFalse(adapter.isStaleRemoteSourceAuthority(authority, 1L, 751L));
        Assert.assertTrue(adapter.isStaleRemoteSourceAuthority(authority, 0L, 750L));
    }

    @Test
    public void refreshPlanProjectionPreservesVmwareMoverSourceGraphFailure() {
        DrPlanVO plan = new DrPlanVO("ftctl-dr-plan", 1L, 2L, DrConstants.DIRECTION_VMWARE_TO_KVM);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setState(DrConstants.PLAN_STATE_SYNCING);
        plan.setCoordinatorWorkerHostId(103L);
        DrRunVO run = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_SYNC);
        run.setState(DrConstants.RUN_STATE_ACCEPTED);
        DrReplicaVO replica = new DrReplicaVO(plan.getId(), plan.getTargetSiteId());
        replica.setState(DrConstants.REPLICA_STATE_SKELETON_READY);

        String statusJson = "{\"state\":\"ERROR\",\"step\":\"replication-cycle-failed\","
                + "\"worker_state\":\"FAILED\",\"worker_exit_code\":72,"
                + "\"error_code\":\"" + DrConstants.ERROR_VMWARE_MOVER_SOURCE_GRAPH_INVALID + "\"}";
        Mockito.when(agentManager.easySend(Mockito.eq(103L), Mockito.any(FtctlDrStatusCommand.class))).thenAnswer(invocation -> {
            FtctlDrStatusCommand command = invocation.getArgument(1);
            return new FtctlDrStatusAnswer(command, true, "100% | qemu-img source graph invalid", plan.getUuid(), run.getUuid(),
                    "ok", "ERROR", "replication-cycle-failed", 100,
                    null, null, null, 15L, DrConstants.ERROR_VMWARE_MOVER_SOURCE_GRAPH_INVALID, 0,
                    "100% | qemu-img source graph invalid", statusJson);
        });
        Mockito.when(drRunDao.findActiveByPlanId(plan.getId())).thenReturn(run);
        Mockito.when(drPlanDao.findById(plan.getId())).thenReturn(plan);
        Mockito.when(drReplicaDao.listActiveByPlanId(plan.getId())).thenReturn(Collections.singletonList(replica));

        DrAdapterResult result = adapter.refreshPlanProjection(plan);

        Assert.assertTrue(result.isSuccess());
        Assert.assertEquals(DrConstants.RUN_STATE_FAILED, run.getState());
        Assert.assertEquals(DrConstants.ERROR_VMWARE_MOVER_SOURCE_GRAPH_INVALID, run.getErrorCode());
        Assert.assertEquals("VMware data mover could not open the VDDK NBD source graph", run.getErrorMessage());
        Assert.assertEquals(DrConstants.PLAN_STATE_ERROR, plan.getState());
        Assert.assertEquals(DrConstants.ERROR_VMWARE_MOVER_SOURCE_GRAPH_INVALID, plan.getLastErrorCode());
        Mockito.verify(drReplicaDao).update(Mockito.eq(replica.getId()), Mockito.same(replica));
        Mockito.verify(drEventDao, Mockito.atLeastOnce()).persist(Mockito.any(DrEventVO.class));
    }

    @Test
    public void refreshPlanProjectionUsesSyncProducerWhilePollingCompletedTestCleanup() {
        DrPlanVO plan = new DrPlanVO("ftctl-dr-plan", 1L, 2L, DrConstants.DIRECTION_VMWARE_TO_KVM);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setCoordinatorWorkerHostId(103L);
        plan.setRpoSeconds(300);
        DrRunVO cleanupRun = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_TEST_CLEANUP);
        cleanupRun.setState(DrConstants.RUN_STATE_SUCCEEDED);
        DrRunVO syncRun = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_SYNC);
        DrSyncCycleVO previousCanonicalCycle = new DrSyncCycleVO(plan.getId(), "previous-producer", 723L);
        previousCanonicalCycle.setCompleted(new Date());

        String statusJson = "{\"state\":\"READY\",\"scheduler_state\":\"RUNNING\","
                + "\"scheduler_session_uuid\":\"" + plan.getUuid() + "\","
                + "\"scheduler_lease_epoch\":4,\"authority_sequence\":292,"
                + "\"plan_cycle_sequence\":146,\"scheduler_health\":\"HEALTHY\","
                + "\"scheduler_pid_alive\":true,\"owner_matched\":true,"
                + "\"active_worker_run_uuid\":\"" + syncRun.getUuid() + "\","
                + "\"latest_completed_producer_run_uuid\":\"" + syncRun.getUuid() + "\"}";
        Mockito.when(agentManager.easySend(Mockito.eq(103L), Mockito.any(FtctlDrStatusCommand.class))).thenAnswer(invocation -> {
            FtctlDrStatusCommand command = invocation.getArgument(1);
            FtctlDrStatusAnswer answer = new FtctlDrStatusAnswer(command, true, "ok", plan.getUuid(), cleanupRun.getUuid(),
                    "ok", "READY", "checkpoint-ready", 100,
                    null, null, null, null, null, 0, "", statusJson);
            answer.setSchedulerSessionUuid(plan.getUuid());
            answer.setSchedulerLeaseEpoch(4L);
            answer.setAuthoritySequence(292L);
            answer.setPlanCycleSequence(146L);
            answer.setSchedulerHealth("HEALTHY");
            answer.setSchedulerPidAlive(true);
            answer.setOwnerMatched(true);
            answer.setActiveWorkerRunUuid(syncRun.getUuid());
            answer.setLatestCompletedProducerRunUuid(syncRun.getUuid());
            answer.setLatestCompletedCheckpointSequence(145L);
            answer.setLatestCompletedCheckpointRef("ftctl:" + plan.getUuid() + ":" + syncRun.getUuid() + ":145");
            answer.setLatestCompletedCheckpointState("READY");
            answer.setLatestCompletedSourceCheckpointAt("2026-07-21T07:16:57Z");
            answer.setLatestCompletedTargetDurableAt("2026-07-21T07:16:59Z");
            answer.setLatestCompletedBaselineGeneration(145L);
            answer.setLatestCompletedCycleToken(plan.getUuid() + ":145");
            answer.setLatestCompletedEffectiveMode("CBT_INCREMENTAL");
            answer.setLatestCompletedChangedBytes(4096L);
            answer.setLatestCompletedTargetWrittenBytes(4096L);
            return answer;
        });
        Mockito.when(drRunDao.findActiveByPlanId(plan.getId())).thenReturn(null);
        Mockito.when(drRunDao.findLatestByPlanId(plan.getId())).thenReturn(cleanupRun);
        Mockito.when(drRunDao.findByUuid(syncRun.getUuid())).thenReturn(syncRun);
        Mockito.when(drPlanDao.acquireInLockTable(plan.getId(), 10)).thenReturn(plan);
        Mockito.when(drSyncCycleDao.findLatestByPlanId(plan.getId())).thenReturn(previousCanonicalCycle);

        DrAdapterResult result = adapter.refreshPlanProjection(plan);

        Assert.assertTrue(result.isSuccess());
        ArgumentCaptor<DrPlanRuntimeVO> authorityCaptor = ArgumentCaptor.forClass(DrPlanRuntimeVO.class);
        Mockito.verify(drPlanRuntimeDao).persist(authorityCaptor.capture());
        Assert.assertEquals(syncRun.getUuid(), authorityCaptor.getValue().getEngineRunUuid());
        Assert.assertEquals(292L, authorityCaptor.getValue().getAuthoritySequence());
        Assert.assertEquals(Long.valueOf(724L), authorityCaptor.getValue().getLatestCompletedCycleSequence());
        Assert.assertEquals(Long.valueOf(724L), authorityCaptor.getValue().getTransferCycleSequence());
        ArgumentCaptor<DrRestorePointVO> restorePointCaptor = ArgumentCaptor.forClass(DrRestorePointVO.class);
        Mockito.verify(drRestorePointDao).persist(restorePointCaptor.capture());
        Assert.assertTrue(restorePointCaptor.getValue().getSourceSnapshotRef().contains(syncRun.getUuid()));
    }

    @Test
    public void acceptedFullReseedCycleCompletesAfterSchedulerAdvancesToNextIncrementalProducer() {
        DrPlanVO plan = new DrPlanVO("plan-41", 1L, 2L, DrConstants.DIRECTION_VMWARE_TO_KVM);
        ReflectionTestUtils.setField(plan, "id", 41L);
        DrRunVO run = new DrRunVO(41L, DrConstants.RUN_TYPE_SYNC);
        ReflectionTestUtils.setField(run, "id", 188L);
        run.setRequestJson("{\"mode\":\"FULL_RESEED\",\"forceFullReseed\":true}");

        DrSyncCycleVO acceptedCycle = new DrSyncCycleVO(41L, "scheduler-full-seed", 1140L);
        acceptedCycle.setRunId(147L);
        acceptedCycle.setCycleToken(plan.getUuid() + ":1140");
        acceptedCycle.setRequestedMode("FULL_SEED");
        acceptedCycle.setEffectiveMode("FULL_SEED");
        acceptedCycle.setState("READY");
        acceptedCycle.setCommitState("LOCAL_DURABLE");
        acceptedCycle.setCompleted(new Date());
        Mockito.when(drSyncCycleDao.findByPlanSequence(41L, 1140L)).thenReturn(acceptedCycle);

        FtctlDrStatusCommand command = new FtctlDrStatusCommand("plan-41", run.getUuid(),
                FtctlDrStatusCommand.StatusScope.OPERATION);
        FtctlDrStatusAnswer status = new FtctlDrStatusAnswer(command, true, "ok");
        status.setControlRequestRunUuid(run.getUuid());
        status.setTerminalAuthoritative(true);
        status.setTerminalSource("ENGINE_TERMINAL");
        status.setTransferCycleSequence(1140L);
        status.setTransferMode("FULL_RESEED");
        status.setLatestCompletedProducerRunUuid("scheduler-next-cycle");
        status.setLatestCompletedRequestedMode("CBT_INCREMENTAL");
        JsonObject runtime = new JsonObject();
        runtime.addProperty("control_request_run_uuid", run.getUuid());
        runtime.addProperty("terminal_authoritative", true);
        runtime.addProperty("transfer_cycle_sequence", 1140L);
        runtime.addProperty("transfer_mode", "FULL_RESEED");
        runtime.addProperty("latest_completed_producer_run_uuid", "scheduler-next-cycle");

        adapter.bindAcceptedCycleFromControlRequest(plan, run, status, runtime);

        Assert.assertEquals(Long.valueOf(1140L), run.getAcceptedCycleSequence());
        Assert.assertEquals(plan.getUuid() + ":1140", run.getAcceptedCycleToken());
        Mockito.verify(drRunDao).update(run.getId(), run);
        Assert.assertTrue(adapter.isAcceptedFullReseedCycleSatisfied(run, status, runtime));
    }

    @Test
    public void acceptedFullReseedCycleCompletesFromOwnedDurableCycleWithoutTerminalJournal() {
        DrPlanVO plan = new DrPlanVO("plan-42", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        ReflectionTestUtils.setField(plan, "id", 42L);
        DrRunVO run = new DrRunVO(42L, DrConstants.RUN_TYPE_SYNC);
        ReflectionTestUtils.setField(run, "id", 189L);
        run.setRequestJson("{\"mode\":\"FULL_RESEED\",\"forceFullReseed\":true}");

        DrSyncCycleVO acceptedCycle = new DrSyncCycleVO(42L, "scheduler-full-seed", 1141L);
        acceptedCycle.setRunId(run.getId());
        acceptedCycle.setCycleToken(plan.getUuid() + ":1141");
        acceptedCycle.setRequestedMode("FULL_SEED");
        acceptedCycle.setEffectiveMode("FULL_SEED");
        acceptedCycle.setState("READY");
        acceptedCycle.setCommitState("LOCAL_DURABLE");
        acceptedCycle.setCompleted(new Date());
        Mockito.when(drSyncCycleDao.findByPlanSequence(42L, 1141L)).thenReturn(acceptedCycle);

        FtctlDrStatusCommand command = new FtctlDrStatusCommand("plan-42", run.getUuid(),
                FtctlDrStatusCommand.StatusScope.OPERATION);
        FtctlDrStatusAnswer status = new FtctlDrStatusAnswer(command, true, "ok");
        status.setControlRequestRunUuid(run.getUuid());
        status.setTransferCycleSequence(1141L);
        status.setTransferMode("FULL_RESEED");
        JsonObject runtime = new JsonObject();
        runtime.addProperty("control_request_run_uuid", run.getUuid());
        runtime.addProperty("transfer_cycle_sequence", 1141L);
        runtime.addProperty("transfer_mode", "FULL_RESEED");

        adapter.bindAcceptedCycleFromControlRequest(plan, run, status, runtime);

        Assert.assertEquals(Long.valueOf(1141L), run.getAcceptedCycleSequence());
        Assert.assertEquals(plan.getUuid() + ":1141", run.getAcceptedCycleToken());
        Assert.assertTrue(adapter.isAcceptedFullReseedCycleSatisfied(run, status, runtime));
    }

    @Test
    public void acceptedFullReseedCycleCompletesAfterCurrentControlAdvancesToIncrementalScheduler() {
        DrPlanVO plan = new DrPlanVO("plan-42-next", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        ReflectionTestUtils.setField(plan, "id", 142L);
        DrRunVO run = new DrRunVO(142L, DrConstants.RUN_TYPE_SYNC);
        ReflectionTestUtils.setField(run, "id", 289L);
        run.setRequestJson("{\"mode\":\"FULL_RESEED\",\"forceFullReseed\":true}");
        run.setAcceptedCycleSequence(1208L);
        run.setAcceptedCycleToken(plan.getUuid() + ":311");

        DrSyncCycleVO acceptedCycle = new DrSyncCycleVO(plan.getId(), run.getUuid(), 1208L);
        acceptedCycle.setRunId(run.getId());
        acceptedCycle.setCycleToken(plan.getUuid() + ":311");
        acceptedCycle.setRequestedMode("FULL_SEED");
        acceptedCycle.setEffectiveMode("FULL_SEED");
        acceptedCycle.setState("READY");
        acceptedCycle.setCommitState("LOCAL_DURABLE");
        acceptedCycle.setCompleted(new Date());
        Mockito.when(drSyncCycleDao.findByPlanSequence(plan.getId(), 1208L)).thenReturn(acceptedCycle);

        FtctlDrStatusCommand command = new FtctlDrStatusCommand(plan.getUuid(), run.getUuid(),
                FtctlDrStatusCommand.StatusScope.OPERATION);
        FtctlDrStatusAnswer status = new FtctlDrStatusAnswer(command, true, "ok");
        status.setControlRequestRunUuid("scheduler-next-incremental");
        JsonObject runtime = new JsonObject();
        runtime.addProperty("control_request_run_uuid", "scheduler-next-incremental");

        Assert.assertTrue(adapter.isAcceptedFullReseedCycleSatisfied(run, status, runtime));
    }

    @Test
    public void lateCancelBindsAuthoritativeFullSeedCheckpointAfterSchedulerProducerAdvances() {
        DrPlanVO plan = new DrPlanVO("plan-43", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        ReflectionTestUtils.setField(plan, "id", 43L);
        DrRunVO run = new DrRunVO(43L, DrConstants.RUN_TYPE_SYNC);
        ReflectionTestUtils.setField(run, "id", 190L);
        run.setRequestJson("{\"mode\":\"FULL_RESEED\",\"forceImmediateCycle\":true}");

        DrSyncCycleVO acceptedCycle = new DrSyncCycleVO(43L, "scheduler-full-seed", 75L);
        acceptedCycle.setRunId(189L);
        acceptedCycle.setCycleToken(plan.getUuid() + ":75");
        acceptedCycle.setRequestedMode("FULL_SEED");
        acceptedCycle.setEffectiveMode("FULL_SEED");
        acceptedCycle.setState("READY");
        acceptedCycle.setCommitState("LOCAL_DURABLE");
        acceptedCycle.setCompleted(new Date());
        Mockito.when(drSyncCycleDao.findByPlanSequence(43L, 75L)).thenReturn(acceptedCycle);

        FtctlDrStatusCommand command = new FtctlDrStatusCommand("plan-43", run.getUuid(),
                FtctlDrStatusCommand.StatusScope.OPERATION);
        FtctlDrStatusAnswer status = new FtctlDrStatusAnswer(command, true, "ok");
        status.setControlRequestRunUuid(run.getUuid());
        status.setTerminalAuthoritative(true);
        status.setTerminalSource("ENGINE_TERMINAL");
        status.setWorkerState("TERMINAL_PUBLISHED");
        status.setWorkerExitCode(0);
        JsonObject runtime = new JsonObject();
        runtime.addProperty("control_request_run_uuid", run.getUuid());
        runtime.addProperty("terminal_authoritative", true);
        runtime.addProperty("state", "READY");
        runtime.addProperty("step", "full-resync-completed");
        runtime.addProperty("checkpoint_sequence", 75L);
        runtime.addProperty("manifest_path", "/runtime/manifests/" + run.getUuid() + "-cycle-75-manifest.json");
        runtime.addProperty("checkpoint_path", "/runtime/checkpoints/" + run.getUuid() + "-cycle-75-checkpoint.json");

        adapter.bindAcceptedCycleFromLateTerminalCheckpoint(plan, run, status, runtime);

        Assert.assertEquals(Long.valueOf(75L), run.getAcceptedCycleSequence());
        Assert.assertEquals(plan.getUuid() + ":75", run.getAcceptedCycleToken());
        Mockito.verify(drRunDao).update(run.getId(), run);
        Assert.assertTrue(adapter.isAcceptedFullReseedCycleSatisfied(run, status, runtime));
    }

    @Test
    public void lateCancelRejectsCheckpointArtifactsOwnedByAnotherControlRun() {
        DrPlanVO plan = new DrPlanVO("plan-44", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        ReflectionTestUtils.setField(plan, "id", 44L);
        DrRunVO run = new DrRunVO(44L, DrConstants.RUN_TYPE_SYNC);
        ReflectionTestUtils.setField(run, "id", 191L);
        run.setRequestJson("{\"mode\":\"FULL_RESEED\"}");
        FtctlDrStatusCommand command = new FtctlDrStatusCommand("plan-44", run.getUuid(),
                FtctlDrStatusCommand.StatusScope.OPERATION);
        FtctlDrStatusAnswer status = new FtctlDrStatusAnswer(command, true, "ok");
        status.setControlRequestRunUuid(run.getUuid());
        status.setTerminalAuthoritative(true);
        JsonObject runtime = new JsonObject();
        runtime.addProperty("control_request_run_uuid", run.getUuid());
        runtime.addProperty("terminal_authoritative", true);
        runtime.addProperty("state", "READY");
        runtime.addProperty("step", "full-resync-completed");
        runtime.addProperty("checkpoint_sequence", 75L);
        runtime.addProperty("manifest_path", "/runtime/manifests/another-run-cycle-75-manifest.json");
        runtime.addProperty("checkpoint_path", "/runtime/checkpoints/another-run-cycle-75-checkpoint.json");

        adapter.bindAcceptedCycleFromLateTerminalCheckpoint(plan, run, status, runtime);

        Assert.assertNull(run.getAcceptedCycleSequence());
        Mockito.verify(drRunDao, Mockito.never()).update(run.getId(), run);
    }

    @Test
    public void lateFullReseedTerminalRecoversCanonicalCycleWhenSchedulerSequenceWasReused() {
        DrPlanVO plan = new DrPlanVO("plan-sequence-collision", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        ReflectionTestUtils.setField(plan, "id", 51L);
        Date started = new Date(System.currentTimeMillis() - 120_000L);
        Date durable = new Date(System.currentTimeMillis() - 30_000L);
        plan.setLastSourceCheckpointAt(new Date(started.getTime() + 10_000L));
        plan.setLastTargetDurableAt(durable);

        DrRunVO run = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_SYNC);
        ReflectionTestUtils.setField(run, "id", 379L);
        run.setRequestJson("{\"mode\":\"FULL_RESEED\",\"forceImmediateCycle\":true}");
        run.setStarted(started);

        DrSyncCycleVO oldLeaseCycle = new DrSyncCycleVO(plan.getId(), "old-scheduler", 311L);
        oldLeaseCycle.setSchedulerSessionUuid(plan.getUuid());
        oldLeaseCycle.setSchedulerLeaseEpoch(36L);
        oldLeaseCycle.setCycleToken(plan.getUuid() + ":311");
        oldLeaseCycle.setRequestedMode("CBT_INCREMENTAL");
        oldLeaseCycle.setState("READY");
        oldLeaseCycle.setCommitState("LOCAL_DURABLE");
        oldLeaseCycle.setCompleted(new Date(started.getTime() - 60_000L));
        DrSyncCycleVO latestHistoricalCycle = new DrSyncCycleVO(plan.getId(), "old-scheduler", 336L);
        latestHistoricalCycle.setCompleted(new Date(started.getTime() - 30_000L));

        Mockito.when(drSyncCycleDao.findByPlanSchedulerCycle(plan.getId(), plan.getUuid(), 37L,
                plan.getUuid() + ":311")).thenReturn(null);
        Mockito.when(drSyncCycleDao.findByPlanSequence(plan.getId(), 311L)).thenReturn(oldLeaseCycle);
        Mockito.when(drSyncCycleDao.findLatestByPlanId(plan.getId())).thenReturn(latestHistoricalCycle);

        FtctlDrStatusAnswer status = new FtctlDrStatusAnswer(
                new FtctlDrStatusCommand(plan.getUuid(), run.getUuid(), FtctlDrStatusCommand.StatusScope.OPERATION),
                true, "ok");
        status.setControlRequestRunUuid(run.getUuid());
        status.setTerminalAuthoritative(true);
        status.setTerminalSource("ENGINE_TERMINAL");
        status.setWorkerState("TERMINAL_PUBLISHED");
        status.setWorkerExitCode(0);
        status.setSchedulerSessionUuid(plan.getUuid());
        status.setSchedulerLeaseEpoch(37L);
        status.setAuthoritySequence(1189L);
        JsonObject runtime = new JsonObject();
        runtime.addProperty("control_request_run_uuid", run.getUuid());
        runtime.addProperty("terminal_authoritative", true);
        runtime.addProperty("state", "READY");
        runtime.addProperty("step", "full-resync-completed");
        runtime.addProperty("checkpoint_sequence", 311L);
        runtime.addProperty("scheduler_session_uuid", plan.getUuid());
        runtime.addProperty("scheduler_lease_epoch", 37L);
        runtime.addProperty("authority_sequence", 1189L);
        runtime.addProperty("manifest_path", "/runtime/manifests/" + run.getUuid()
                + "-cycle-311-manifest.json");
        runtime.addProperty("checkpoint_path", "/runtime/checkpoints/" + run.getUuid()
                + "-cycle-311-checkpoint.json");

        adapter.bindAcceptedCycleFromLateTerminalCheckpoint(plan, run, status, runtime);

        ArgumentCaptor<DrSyncCycleVO> cycleCaptor = ArgumentCaptor.forClass(DrSyncCycleVO.class);
        Mockito.verify(drSyncCycleDao).persist(cycleCaptor.capture());
        DrSyncCycleVO recovered = cycleCaptor.getValue();
        Assert.assertEquals(1189L, recovered.getSequence());
        Assert.assertEquals(Long.valueOf(311L), recovered.getBaselineGeneration());
        Assert.assertEquals(Long.valueOf(37L), recovered.getSchedulerLeaseEpoch());
        Assert.assertEquals(Long.valueOf(run.getId()), recovered.getRunId());
        Assert.assertEquals("FULL_SEED", recovered.getRequestedMode());
        Assert.assertEquals("LOCAL_DURABLE", recovered.getCommitState());
        Assert.assertEquals(Long.valueOf(1189L), run.getAcceptedCycleSequence());
        Assert.assertEquals(plan.getUuid() + ":311", run.getAcceptedCycleToken());
        Mockito.when(drSyncCycleDao.findByPlanSequence(plan.getId(), 1189L)).thenReturn(recovered);
        Assert.assertTrue(adapter.isAcceptedFullReseedCycleSatisfied(run, status, runtime));
    }

    @Test
    public void newCycleUsesMonotonicCanonicalSequenceWhenHistoricalFloorIsAhead() {
        DrPlanVO plan = new DrPlanVO("plan-canonical-floor", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        ReflectionTestUtils.setField(plan, "id", 52L);
        DrSyncCycleVO latest = new DrSyncCycleVO(plan.getId(), "historical-run", 685L);
        latest.setCycleToken(plan.getUuid() + ":244");
        latest.setCompleted(new Date());

        FtctlDrStatusAnswer status = new FtctlDrStatusAnswer(
                new FtctlDrStatusCommand(plan.getUuid(), null, FtctlDrStatusCommand.StatusScope.PLAN_AUTHORITY),
                true, "ok");
        status.setSchedulerSessionUuid(plan.getUuid());
        status.setSchedulerLeaseEpoch(195L);
        status.setAuthoritySequence(686L);

        Mockito.when(drSyncCycleDao.findByPlanSchedulerCycle(plan.getId(), plan.getUuid(), 195L,
                plan.getUuid() + ":246")).thenReturn(null);
        Mockito.when(drSyncCycleDao.findByPlanSequence(plan.getId(), 246L)).thenReturn(null);
        Mockito.when(drSyncCycleDao.findLatestByPlanId(plan.getId())).thenReturn(latest);

        DrSyncCycleVO cycle = adapter.resolveCycleForProjection(plan, status, "current-run", 246L,
                plan.getUuid() + ":246");

        Assert.assertEquals(686L, cycle.getSequence());
        Assert.assertEquals(246L, cycle.getCheckpointSequence());
    }

    @Test
    public void cleanupRequiresMatchingRunAndCleanedArtifactsBeforeRestoration() {
        DrPlanVO plan=new DrPlanVO("cleanup-proof",1L,2L,DrConstants.DIRECTION_KVM_TO_KVM);
        DrRunVO run=new DrRunVO(plan.getId(),DrConstants.RUN_TYPE_TEST_CLEANUP);
        FtctlDrStatusAnswer status=new FtctlDrStatusAnswer(new FtctlDrStatusCommand(plan.getUuid(),run.getUuid()),true,"ok");
        JsonObject runtime=new JsonObject();runtime.addProperty("state","READY");runtime.addProperty("run_uuid",run.getUuid());
        Mockito.when(drTargetMaterializationService.isTestTargetCleaned(plan.getId())).thenReturn(true);
        Assert.assertFalse((Boolean) ReflectionTestUtils.invokeMethod(adapter,"isRunSatisfiedByRuntime",plan,run,status,runtime));
        runtime.addProperty("test_cleanup_state","CLEANED"); runtime.addProperty("run_uuid","another-run");
        Assert.assertFalse((Boolean) ReflectionTestUtils.invokeMethod(adapter,"isRunSatisfiedByRuntime",plan,run,status,runtime));
        runtime.addProperty("run_uuid",run.getUuid());
        Assert.assertTrue((Boolean) ReflectionTestUtils.invokeMethod(adapter,"isRunSatisfiedByRuntime",plan,run,status,runtime));
    }
    @Test
    public void authorityGenerationDoesNotUseResetCheckpointSequence() {
        Assert.assertEquals(374L, FtctlDrRuntimeProjectionAdapter.cutoverGeneration(null, 373L, "new", "old", false));
        Assert.assertEquals(374L, FtctlDrRuntimeProjectionAdapter.cutoverGeneration(374L, 500L, "new", "old", false));
        Assert.assertEquals(374L, FtctlDrRuntimeProjectionAdapter.cutoverGeneration(64L, 373L, "new", "old", true));
        Assert.assertEquals(64L, FtctlDrRuntimeProjectionAdapter.cutoverGeneration(64L, 373L, "same", "same", true));
    }

}
