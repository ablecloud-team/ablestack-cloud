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

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

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
import com.cloud.agent.api.FtctlDrActionAnswer;
import com.cloud.agent.api.FtctlDrActionCommand;
import com.cloud.agent.api.FtctlDrCapabilitiesAnswer;
import com.cloud.agent.api.FtctlDrCapabilitiesCommand;
import com.cloud.dr.DrConstants;
import com.cloud.dr.DrFailbackPreflightResult;
import com.cloud.dr.DrFailbackPreflightService;
import com.cloud.dr.DrPlanVO;
import com.cloud.dr.DrPlanRuntimeVO;
import com.cloud.dr.DrPlanOwnedTransportService;
import com.cloud.dr.DrReprotectAuthoritySpec;
import com.cloud.dr.DrReprotectPreflightResult;
import com.cloud.dr.DrReprotectPreflightService;
import com.cloud.dr.DrRestorePointVO;
import com.cloud.dr.DrRunVO;
import com.cloud.dr.DrSyncCycleVO;
import com.cloud.dr.DrWorkerPlacementService;
import com.cloud.dr.DrWorkerRole;
import com.cloud.dr.adapter.DrAdapterResult;
import com.cloud.dr.adapter.DrExecutionContext;
import com.cloud.dr.dao.DrRestorePointDao;
import com.cloud.dr.dao.DrPlanDao;
import com.cloud.dr.dao.DrPlanRuntimeDao;
import com.cloud.dr.dao.DrSyncCycleDao;
import com.cloud.dr.inventory.DrSourceHardwareInventoryService;
import com.cloud.dr.inventory.DrSourceVmHardware;
import com.cloud.host.dao.HostDao;
import com.cloud.host.HostVO;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

@RunWith(MockitoJUnitRunner.class)
public class FtctlDrUnifiedActionAdapterTest {

    @Test
    public void pausedReplicationWinsOverRunningSchedulerProcess() {
        com.cloud.dr.DrPlanVO plan = new com.cloud.dr.DrPlanVO("paused", 1L, 2L, "KVM_TO_KVM");
        com.cloud.dr.DrPlanRuntimeVO runtime = new com.cloud.dr.DrPlanRuntimeVO(plan.getId());
        runtime.setSchedulerDesiredState("RUNNING");
        runtime.setReplicationActivityState("PAUSED");
        Assert.assertEquals("PAUSED", org.springframework.test.util.ReflectionTestUtils.invokeMethod(adapter,
                "preTestReplicationIntent", plan, runtime));
        runtime.setReplicationActivityState("IDLE");
        Assert.assertEquals("RUNNING", org.springframework.test.util.ReflectionTestUtils.invokeMethod(adapter,
                "preTestReplicationIntent", plan, runtime));
    }

    @Test
    public void vmwareTestCapturesPausedIntentBeforeLocalRuntimeQuiesce() {
        DrPlanVO plan = ftctlDrPlan();
        plan.setState("PAUSED");
        DrRunVO run = run(DrConstants.RUN_TYPE_TEST_FAILOVER, "{}");
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(adapter,
                "captureTestReplicationIntent", new DrExecutionContext(plan, run));
        Mockito.verify(testCleanupRecovery).capture(plan.getId(), run.getId(), "PAUSED");
    }

    @Test
    public void vmwareTestCapturesRunningIntentForDurableCleanupRecovery() {
        DrPlanVO plan = ftctlDrPlan();
        plan.setState("READY");
        DrRunVO run = run(DrConstants.RUN_TYPE_TEST_FAILOVER, "{}");
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(adapter,
                "captureTestReplicationIntent", new DrExecutionContext(plan, run));
        Mockito.verify(testCleanupRecovery).capture(plan.getId(), run.getId(), "RUNNING");
    }

    @Test
    public void vmwareCleanupDelegatesSourceRestoreToCloud() {
        DrPlanVO plan = ftctlDrPlan();
        DrRunVO run = run(DrConstants.RUN_TYPE_TEST_CLEANUP, "{}");
        FtctlDrActionCommand command = org.springframework.test.util.ReflectionTestUtils.invokeMethod(adapter,
                "buildActionCommand", new DrExecutionContext(plan, run),
                FtctlDrActionCommand.Action.TEST_ARTIFACT_CLEANUP);
        Assert.assertTrue(JsonParser.parseString(command.getRequestJson()).getAsJsonObject()
                .get("sourceSchedulerRestoreManagedByCloud").getAsBoolean());
        Assert.assertTrue(JsonParser.parseString(command.getProfileJson()).getAsJsonObject()
                .getAsJsonObject("request").get("sourceSchedulerRestoreManagedByCloud").getAsBoolean());
        Mockito.verify(drRemoteAgentClient).isRemoteKvmSource(plan);
        Mockito.verifyNoMoreInteractions(drRemoteAgentClient);
    }

    @Test
    public void automaticVmwareThumbprintsAreRefreshableButOperatorPinsAreNot() {
        Assert.assertTrue(FtctlDrUnifiedActionAdapter.shouldRefreshAutoThumbprint("backend-auto"));
        Assert.assertTrue(FtctlDrUnifiedActionAdapter.shouldRefreshAutoThumbprint("backend-auto-refreshed"));
        Assert.assertTrue(FtctlDrUnifiedActionAdapter.shouldRefreshAutoThumbprint("backend-auto-fallback"));
        Assert.assertFalse(FtctlDrUnifiedActionAdapter.shouldRefreshAutoThumbprint("runtime"));
        Assert.assertFalse(FtctlDrUnifiedActionAdapter.shouldRefreshAutoThumbprint(null));
    }

    @Mock
    private com.cloud.dr.DrTestCleanupRecoveryStore testCleanupRecovery;
    @Mock
    private AgentManager agentManager;
    @Mock
    private HostDao hostDao;
    @Mock
    private DrRestorePointDao drRestorePointDao;
    @Mock
    private DrPlanRuntimeDao drPlanRuntimeDao;
    @Mock
    private DrSyncCycleDao drSyncCycleDao;
    @Mock
    private DrReprotectPreflightService drReprotectPreflightService;
    @Mock
    private DrFailbackPreflightService drFailbackPreflightService;
    @Mock
    private DrRemoteAgentClient drRemoteAgentClient;
    @Mock
    private DrPlanOwnedTransportService drPlanOwnedTransportService;
    @Mock
    private DrPlanDao drPlanDao;
    @Mock
    private DrSourceHardwareInventoryService drSourceHardwareInventoryService;
    @Mock
    private DrWorkerPlacementService drWorkerPlacementService;

    @InjectMocks
    private FtctlDrUnifiedActionAdapter adapter;

    @Before
    public void resolveRemoteSourceWorkerThroughSharedResolver() {
        Mockito.lenient().when(drRemoteAgentClient.sourceWorkerUuid(Mockito.any(DrPlanVO.class)))
                .thenReturn("source-host-uuid");
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
    public void syncDispatchesToCoordinatorWorkerAndReturnsAcceptedRun() throws Exception {
        DrPlanVO plan = ftctlDrPlan();
        DrRunVO run = run(DrConstants.RUN_TYPE_SYNC,
                "{\"mode\":\"FULL_RESEED\",\"forceImmediateCycle\":true,\"remoteMoldSecretKey\":\"top-secret\",\"dryRun\":false}");
        ArgumentCaptor<FtctlDrActionCommand> commandCaptor = ArgumentCaptor.forClass(FtctlDrActionCommand.class);
        mockCapabilities();
        Mockito.when(agentManager.send(Mockito.eq(103L), commandCaptor.capture())).thenAnswer(invocation -> {
            FtctlDrActionCommand command = invocation.getArgument(1);
            return new FtctlDrActionAnswer(command, true, "accepted", FtctlDrActionCommand.Action.SYNC,
                    plan.getUuid(), run.getUuid(), "accepted", true, "SYNCING", "dispatch",
                    1, "ftctl-job-1", 0L, null, 0, "{\"result\":\"accepted\"}",
                    "{\"remoteMoldSecretKey\":\"top-secret\",\"state\":\"SYNCING\"}");
        });

        DrAdapterResult result = adapter.execute(new DrExecutionContext(plan, run));

        Assert.assertTrue(result.isSuccess());
        Assert.assertFalse(result.isTerminal());
        Assert.assertEquals("ftctl-job-1", result.getExternalJobRef());
        Mockito.verify(agentManager).send(Mockito.eq(103L), Mockito.any(FtctlDrActionCommand.class));

        FtctlDrActionCommand command = commandCaptor.getValue();
        Assert.assertEquals(FtctlDrActionCommand.Action.SYNC, command.getAction());
        Assert.assertEquals(DrConstants.RUN_TYPE_SYNC, command.getRunType());
        Assert.assertEquals(DrConstants.DIRECTION_VMWARE_TO_KVM, command.getDirection());
        Assert.assertEquals("coordinator", command.getRole());
        Assert.assertEquals("101", command.getSourceWorkerUuid());
        Assert.assertEquals("102", command.getTargetWorkerUuid());
        Assert.assertEquals("103", command.getCoordinatorWorkerUuid());
        Assert.assertFalse(command.isWaitForCompletion());
        Assert.assertEquals("FULL_RESEED", command.getMode());
        Assert.assertTrue(command.isForceImmediateCycle());
        Assert.assertNull(command.getAuthoritySequenceFloor());
        Assert.assertTrue(command.getProfileJson().contains("\"engine\":\"FTCTL_DR\""));
        Assert.assertFalse(command.getProfileJson().contains("top-secret"));
        Assert.assertFalse(command.getRequestJson().contains("top-secret"));
        Assert.assertEquals("REDACTED", command.getContext().get("remoteMoldSecretKey"));
        Assert.assertFalse(result.getDetailsJson().contains("top-secret"));
    }

    @Test
    public void actionCarriesLatestDurableAuthoritySequenceFloor() throws Exception {
        DrPlanVO plan = ftctlDrPlan();
        DrRunVO run = run(DrConstants.RUN_TYPE_SYNC, "{\"mode\":\"INCREMENTAL\"}");
        DrPlanRuntimeVO runtime = new DrPlanRuntimeVO(plan.getId());
        runtime.setAuthoritySequence(41L);
        DrSyncCycleVO completed = new DrSyncCycleVO(plan.getId(), "cycle-run", 61L);
        completed.setAuthoritySequence(153L);
        Mockito.when(drPlanRuntimeDao.findByPlanId(plan.getId())).thenReturn(runtime);
        Mockito.when(drSyncCycleDao.findLatestCompletedByPlanId(plan.getId())).thenReturn(completed);
        mockCapabilities();
        ArgumentCaptor<FtctlDrActionCommand> commandCaptor = ArgumentCaptor.forClass(FtctlDrActionCommand.class);
        Mockito.when(agentManager.send(Mockito.eq(103L), commandCaptor.capture())).thenAnswer(invocation -> {
            FtctlDrActionCommand command = invocation.getArgument(1);
            return new FtctlDrActionAnswer(command, true, "accepted", FtctlDrActionCommand.Action.SYNC,
                    plan.getUuid(), run.getUuid(), "accepted", true, "SYNCING", "dispatch",
                    1, "ftctl-job-floor", 0L, null, 0, "{\"result\":\"accepted\"}",
                    "{\"state\":\"SYNCING\"}");
        });

        Assert.assertTrue(adapter.execute(new DrExecutionContext(plan, run)).isSuccess());
        Assert.assertEquals(Long.valueOf(153L), commandCaptor.getValue().getAuthoritySequenceFloor());
    }

    @Test
    public void syncRejectsSemanticErrorEvenWhenAgentTransportSucceeded() throws Exception {
        DrPlanVO plan = ftctlDrPlan();
        DrRunVO run = run(DrConstants.RUN_TYPE_SYNC, "{\"mode\":\"INCREMENTAL\"}");
        mockCapabilities();
        Mockito.when(agentManager.send(Mockito.eq(103L), Mockito.any(FtctlDrActionCommand.class))).thenAnswer(invocation -> {
            FtctlDrActionCommand command = invocation.getArgument(1);
            return new FtctlDrActionAnswer(command, true, "status query completed",
                    FtctlDrActionCommand.Action.SYNC, plan.getUuid(), run.getUuid(), "ok", false,
                    "ERROR", "scheduler-recovery-failed", 100, null, 0L,
                    "DR_RECOVERY_FAILED", 0, "{\"result\":\"ok\"}",
                    "{\"result\":\"ok\",\"accepted\":false,\"state\":\"ERROR\","
                            + "\"error_code\":\"DR_RECOVERY_FAILED\"}");
        });

        DrAdapterResult result = adapter.execute(new DrExecutionContext(plan, run));

        Assert.assertFalse(result.isSuccess());
        Assert.assertTrue(result.isTerminal());
        Assert.assertEquals("DR_RECOVERY_FAILED", result.getErrorCode());
    }

    @Test
    public void crossSiteKvmSyncDispatchesToRemoteSourceAndPreparesTargetWorker() throws Exception {
        DrPlanVO plan = new DrPlanVO("cross-site-kvm-plan", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setSourceExternalRef("source-vm-uuid");
        plan.setActiveSide("SOURCE");
        plan.setTargetWorkerHostId(102L);
        plan.setCoordinatorWorkerHostId(103L);
        plan.setMappingJson("{\"source\":{\"hardware\":{\"sourceHostUuid\":\"source-host-uuid\","
                + "\"instanceName\":\"i-2-332-VM\"}},\"target\":{\"storagePoolType\":\"RBD\"},"
                + "\"disks\":[{\"device\":\"sda\",\"sourcePath\":\"rbd:rbd/source-image\","
                + "\"targetPath\":\"rbd:rbd/target-image\",\"targetStorageRef\":\"target-pool-uuid\","
                + "\"target\":{\"storageRef\":\"target-pool-uuid\",\"path\":\"target-image\",\"format\":\"raw\"}}]}");
        DrRunVO run = run(DrConstants.RUN_TYPE_SYNC, "{\"mode\":\"FULL_RESEED\",\"forceImmediateCycle\":true}");
        HostVO targetHost = Mockito.mock(HostVO.class);
        Mockito.when(targetHost.getUuid()).thenReturn("target-host-uuid");
        Mockito.when(targetHost.getPrivateIpAddress()).thenReturn("10.10.32.2");
        Mockito.when(hostDao.findById(102L)).thenReturn(targetHost);
        Mockito.when(drRemoteAgentClient.isRemoteKvmSource(plan)).thenReturn(true);
        Mockito.when(drSourceHardwareInventoryService.resolve(plan)).thenReturn(sourceHardware());
        Mockito.when(drPlanOwnedTransportService.supports(plan)).thenReturn(true);
        Mockito.when(drPlanOwnedTransportService.startForwardTargetExport(
                Mockito.eq(plan), Mockito.eq(run), Mockito.anyString()))
                .thenReturn(exports("10.10.32.2", 12032, "dr-export-sda"));
        Mockito.when(drRemoteAgentClient.execute(Mockito.eq(plan), Mockito.eq("CAPABILITIES"),
                Mockito.isA(FtctlDrCapabilitiesCommand.class), Mockito.isNull(),
                Mockito.eq(FtctlDrCapabilitiesAnswer.class))).thenAnswer(invocation -> {
                    FtctlDrCapabilitiesAnswer answer = new FtctlDrCapabilitiesAnswer(invocation.getArgument(2), true, "ok");
                    answer.setSupportedFeatures(java.util.Arrays.asList(
                            "control-protocol-v2", "dr-site-agent-rbd-transport-v1"));
                    return answer;
                });
        Mockito.when(drRemoteAgentClient.execute(Mockito.eq(plan), Mockito.eq("ACTION"),
                Mockito.isA(FtctlDrActionCommand.class), Mockito.isNull(),
                Mockito.eq(FtctlDrActionAnswer.class))).thenAnswer(invocation -> {
                    FtctlDrActionCommand command = invocation.getArgument(2);
                    return new FtctlDrActionAnswer(command, true, "accepted", FtctlDrActionCommand.Action.SYNC,
                            plan.getUuid(), run.getUuid(), "accepted", true, "SYNCING", "dispatch",
                            1, "remote-ftctl-job", 0L, null, 0, "{\"result\":\"accepted\"}",
                            "{\"state\":\"SYNCING\"}");
                });

        DrAdapterResult result = adapter.execute(new DrExecutionContext(plan, run));

        Assert.assertTrue(result.isSuccess());
        Assert.assertFalse(result.isTerminal());
        Mockito.verify(drPlanOwnedTransportService).startForwardTargetExport(
                Mockito.eq(plan), Mockito.eq(run), Mockito.anyString());
        Mockito.verify(agentManager, Mockito.never()).send(Mockito.anyLong(), Mockito.isA(FtctlDrActionCommand.class));
        ArgumentCaptor<FtctlDrActionCommand> actionCaptor = ArgumentCaptor.forClass(FtctlDrActionCommand.class);
        Mockito.verify(drRemoteAgentClient).execute(Mockito.eq(plan), Mockito.eq("ACTION"), actionCaptor.capture(),
                Mockito.isNull(), Mockito.eq(FtctlDrActionAnswer.class));
        FtctlDrActionCommand command = actionCaptor.getValue();
        Assert.assertNull(command.getSourceWorkerUuid());
        Assert.assertTrue(command.getProfileJson().contains("\"mode\":\"site-agent-nbd\""));
        Assert.assertTrue(command.getProfileJson().contains("\"targetHostAddress\":\"10.10.32.2\""));
        Assert.assertTrue(command.getProfileJson().contains("\"name\":\"dr-export-sda\""));
        Assert.assertTrue(command.getRequestJson().contains("\"schedulerTransitionScope\":\"REMOTE_SOURCE\""));
        Assert.assertTrue(command.getProfileJson().contains("\"UEFI\":\"LEGACY\""));
        Assert.assertTrue(command.getProfileJson().contains("\"tpmversion\":\"NONE\""));
        Mockito.verify(drPlanDao, Mockito.never()).update(Mockito.anyLong(), Mockito.any(DrPlanVO.class));
        Assert.assertFalse(command.getProfileJson().contains("sshUser"));
        Assert.assertFalse(command.getProfileJson().contains("moldSecretKey"));
    }

    @Test
    public void crossSiteKvmRecoveryCarriesLatestDurableBaselineToRelocatedSourceWorker() throws Exception {
        DrPlanVO plan = new DrPlanVO("cross-site-kvm-recovery", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setSourceExternalRef("source-vm-uuid");
        plan.setActiveSide("SOURCE");
        plan.setTargetWorkerHostId(102L);
        plan.setCoordinatorWorkerHostId(103L);
        plan.setMappingJson("{\"source\":{\"hardware\":{\"sourceHostUuid\":\"old-host-uuid\","
                + "\"instanceName\":\"i-2-100-VM\"}},\"target\":{\"storagePoolType\":\"SharedMountPoint\"},"
                + "\"disks\":[{\"device\":\"sda\",\"sourcePath\":\"/mnt/glue-gfs/source-image\","
                + "\"targetPath\":\"/mnt/glue-gfs/target-image\",\"targetStorageRef\":\"target-pool-uuid\","
                + "\"target\":{\"storageRef\":\"target-pool-uuid\",\"path\":\"target-image\",\"format\":\"qcow2\"}}]}");
        DrRunVO run = run(DrConstants.RUN_TYPE_RECOVER_SYNC, "{\"trigger\":\"AUTOMATIC\"}");
        DrRestorePointVO checkpoint = checkpoint(plan, "ftctl:" + plan.getUuid() + ":old-run:259");
        checkpoint.setCheckpointSequence(259L);
        checkpoint.setSourceCreated(new Date(1788507962000L));
        checkpoint.setTargetReadyAt(new Date(1788507965000L));
        Mockito.when(drRestorePointDao.findLatestTargetReadyByPlanId(plan.getId())).thenReturn(checkpoint);
        Mockito.when(drRemoteAgentClient.isRemoteKvmSource(plan)).thenReturn(true);
        Mockito.when(drSourceHardwareInventoryService.resolve(plan)).thenReturn(sourceHardware());
        Mockito.when(drPlanOwnedTransportService.supports(plan)).thenReturn(true);
        Mockito.when(drPlanOwnedTransportService.startForwardTargetExport(
                Mockito.eq(plan), Mockito.eq(run), Mockito.anyString()))
                .thenReturn(exports("10.10.31.3", 10850, "dr-export-sda"));
        Mockito.when(drRemoteAgentClient.execute(Mockito.eq(plan), Mockito.eq("CAPABILITIES"),
                Mockito.isA(FtctlDrCapabilitiesCommand.class), Mockito.isNull(),
                Mockito.eq(FtctlDrCapabilitiesAnswer.class))).thenAnswer(invocation -> {
                    FtctlDrCapabilitiesAnswer answer = new FtctlDrCapabilitiesAnswer(invocation.getArgument(2), true, "ok");
                    answer.setSupportedFeatures(java.util.Arrays.asList(
                            "control-protocol-v2", "dr-site-agent-rbd-transport-v1"));
                    return answer;
                });
        ArgumentCaptor<FtctlDrActionCommand> actionCaptor = ArgumentCaptor.forClass(FtctlDrActionCommand.class);
        Mockito.when(drRemoteAgentClient.execute(Mockito.eq(plan), Mockito.eq("ACTION"), actionCaptor.capture(),
                Mockito.isNull(), Mockito.eq(FtctlDrActionAnswer.class))).thenAnswer(invocation -> {
                    FtctlDrActionCommand command = invocation.getArgument(2);
                    return new FtctlDrActionAnswer(command, true, "accepted", FtctlDrActionCommand.Action.RECOVER_SYNC,
                            plan.getUuid(), run.getUuid(), "accepted", true, "SYNCING", "scheduler-recovery-accepted",
                            1, run.getUuid(), 0L, null, 0, "{\"result\":\"accepted\"}",
                            "{\"state\":\"SYNCING\"}");
                });

        DrAdapterResult result = adapter.execute(new DrExecutionContext(plan, run));

        Assert.assertTrue(result.isSuccess());
        FtctlDrActionCommand command = actionCaptor.getValue();
        Assert.assertEquals(Long.valueOf(259L), command.getResumeBaselineCheckpointSequence());
        Assert.assertEquals(Long.valueOf(260L), command.getMinimumCompletedCheckpointSequence());
        Assert.assertTrue(command.getProfileJson().contains("\"checkpointSequence\":259"));
        Assert.assertTrue(command.getProfileJson().contains("\"resumeBaselineCheckpointSequence\":259"));
        Assert.assertTrue(command.getProfileJson().contains("\"minimumCompletedCheckpointSequence\":260"));
        Assert.assertTrue(command.getProfileJson().contains("\"checkpointTargetReadyAt\""));
    }

    @Test
    public void crossSiteKvmFailoverIsRejectedBeforeDispatchWhileSourceCloneFlattenIsActive() throws Exception {
        DrPlanVO plan = new DrPlanVO("cross-site-kvm-plan", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setSourceExternalRef("source-vm-uuid");
        plan.setActiveSide("SOURCE");
        plan.setTargetWorkerHostId(102L);
        plan.setCoordinatorWorkerHostId(103L);
        plan.setMappingJson("{\"source\":{\"hardware\":{\"sourceHostUuid\":\"source-host-uuid\","
                + "\"instanceName\":\"i-2-51-VM\"}},\"target\":{\"storagePoolType\":\"SharedMountPoint\"},"
                + "\"disks\":[{\"device\":\"sda\",\"targetStorageRef\":\"target-pool-uuid\"}]}");
        DrRunVO run = run(DrConstants.RUN_TYPE_FAILOVER, "{}");
        DrSourceVmHardware hardware = sourceHardware();
        hardware.setOperationBlockerCode(DrConstants.ERROR_SOURCE_CLONE_FLATTEN_ACTIVE);
        hardware.setOperationBlockerMessage("Source VM SharedMountPoint clone flatten is running");
        Mockito.when(drSourceHardwareInventoryService.resolve(plan)).thenReturn(hardware);

        DrAdapterResult result = adapter.execute(new DrExecutionContext(plan, run));

        Assert.assertFalse(result.isSuccess());
        Assert.assertEquals(DrConstants.ERROR_SOURCE_CLONE_FLATTEN_ACTIVE, result.getErrorCode());
        Mockito.verifyNoInteractions(agentManager, drRemoteAgentClient);
    }

    @Test
    public void crossSiteKvmResumeDispatchesWithReconciledTargetExports() throws Exception {
        DrPlanVO plan = new DrPlanVO("cross-site-kvm-resume", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setSourceExternalRef("source-vm-uuid");
        plan.setActiveSide("SOURCE");
        plan.setTargetWorkerHostId(102L);
        plan.setCoordinatorWorkerHostId(103L);
        plan.setMappingJson("{\"source\":{\"hardware\":{\"sourceHostUuid\":\"source-host-uuid\","
                + "\"instanceName\":\"i-2-332-VM\"}},\"target\":{\"storagePoolType\":\"RBD\"},"
                + "\"disks\":[{\"device\":\"sda\",\"sourcePath\":\"rbd:rbd/source-image\","
                + "\"targetPath\":\"rbd:rbd/target-image\",\"targetStorageRef\":\"target-pool-uuid\","
                + "\"target\":{\"storageRef\":\"target-pool-uuid\",\"path\":\"target-image\",\"format\":\"raw\"}}]}");
        DrRunVO run = run(DrConstants.RUN_TYPE_RESUME_SYNC, "{}");
        HostVO targetHost = Mockito.mock(HostVO.class);
        Mockito.when(targetHost.getUuid()).thenReturn("target-host-uuid");
        Mockito.when(targetHost.getPrivateIpAddress()).thenReturn("10.10.32.2");
        Mockito.when(hostDao.findById(102L)).thenReturn(targetHost);
        Mockito.when(drRemoteAgentClient.isRemoteKvmSource(plan)).thenReturn(true);
        Mockito.when(drPlanOwnedTransportService.supports(plan)).thenReturn(true);
        Mockito.when(drPlanOwnedTransportService.startForwardTargetExport(
                Mockito.eq(plan), Mockito.eq(run), Mockito.anyString()))
                .thenReturn(exports("10.10.32.2", 12032, "dr-resume-sda"));
        Mockito.when(drRemoteAgentClient.execute(Mockito.eq(plan), Mockito.eq("CAPABILITIES"),
                Mockito.isA(FtctlDrCapabilitiesCommand.class), Mockito.isNull(),
                Mockito.eq(FtctlDrCapabilitiesAnswer.class))).thenAnswer(invocation -> {
                    FtctlDrCapabilitiesAnswer answer = new FtctlDrCapabilitiesAnswer(invocation.getArgument(2), true, "ok");
                    answer.setSupportedFeatures(java.util.Arrays.asList(
                            "control-protocol-v2", "dr-site-agent-rbd-transport-v1"));
                    return answer;
                });
        ArgumentCaptor<FtctlDrActionCommand> actionCaptor = ArgumentCaptor.forClass(FtctlDrActionCommand.class);
        Mockito.when(drRemoteAgentClient.execute(Mockito.eq(plan), Mockito.eq("ACTION"), actionCaptor.capture(),
                Mockito.isNull(), Mockito.eq(FtctlDrActionAnswer.class))).thenAnswer(invocation -> {
                    FtctlDrActionCommand command = invocation.getArgument(2);
                    return new FtctlDrActionAnswer(command, true, "resumed", FtctlDrActionCommand.Action.RESUME_SYNC,
                            plan.getUuid(), run.getUuid(), "accepted", true, "READY", "resume",
                            1, run.getUuid(), 0L, null, 0, "{\"result\":\"accepted\"}",
                            "{\"state\":\"READY\"}");
                });

        DrAdapterResult result = adapter.execute(new DrExecutionContext(plan, run));

        Assert.assertTrue(result.isSuccess());
        Mockito.verify(drPlanOwnedTransportService).startForwardTargetExport(
                Mockito.eq(plan), Mockito.eq(run), Mockito.anyString());
        Assert.assertEquals(FtctlDrActionCommand.Action.RESUME_SYNC, actionCaptor.getValue().getAction());
        Assert.assertTrue(actionCaptor.getValue().getProfileJson().contains("\"mode\":\"site-agent-nbd\""));
        Assert.assertTrue(actionCaptor.getValue().getProfileJson().contains("dr-resume-sda"));
    }

    @Test
    public void remoteKvmFailbackResumeRehydratesForwardProfileBeforeSourceDispatch() {
        DrPlanVO plan = new DrPlanVO("cross-site-kvm-resume", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setSourceExternalRef("source-vm-uuid");
        plan.setActiveSide("SOURCE");
        plan.setTargetWorkerHostId(102L);
        plan.setCoordinatorWorkerHostId(103L);
        plan.setMappingJson("{\"source\":{\"hardware\":{\"sourceHostUuid\":\"source-host-uuid\","
                + "\"instanceName\":\"i-2-332-VM\"}},\"target\":{\"storagePoolType\":\"RBD\"},"
                + "\"disks\":[{\"device\":\"sda\",\"sourcePath\":\"rbd:rbd/source-image\","
                + "\"targetPath\":\"rbd:rbd/target-image\",\"targetStorageRef\":\"target-pool-uuid\","
                + "\"target\":{\"storageRef\":\"target-pool-uuid\",\"path\":\"target-image\",\"format\":\"raw\"}}]}");
        DrRunVO run = run(DrConstants.RUN_TYPE_FAILBACK, "{}");
        HostVO targetHost = Mockito.mock(HostVO.class);
        Mockito.when(targetHost.getUuid()).thenReturn("target-host-uuid");
        Mockito.when(targetHost.getPrivateIpAddress()).thenReturn("10.10.32.3");
        Mockito.when(hostDao.findById(102L)).thenReturn(targetHost);
        Mockito.when(drRemoteAgentClient.isRemoteKvmSource(plan)).thenReturn(true);
        Mockito.when(drPlanOwnedTransportService.supports(plan)).thenReturn(true);
        Mockito.when(drPlanOwnedTransportService.startForwardTargetExport(
                Mockito.eq(plan), Mockito.eq(run), Mockito.anyString()))
                .thenReturn(exports("10.10.32.3", 12033, "dr-forward-sda"));
        DrPlanRuntimeVO runtime = new DrPlanRuntimeVO(plan.getId());
        runtime.setAuthoritySequence(676L);
        Mockito.when(drPlanRuntimeDao.findByPlanId(plan.getId())).thenReturn(runtime);
        ArgumentCaptor<String> profileCaptor = ArgumentCaptor.forClass(String.class);
        FtctlDrActionCommand answerCommand = new FtctlDrActionCommand(
                FtctlDrActionCommand.Action.RESUME_SYNC, plan.getUuid(), run.getUuid());
        Mockito.when(drRemoteAgentClient.transitionSourceScheduler(Mockito.eq(plan),
                Mockito.eq(FtctlDrActionCommand.Action.RESUME_SYNC), Mockito.eq(run.getUuid()),
                profileCaptor.capture(), Mockito.eq(9L), Mockito.eq(10L), Mockito.eq(676L)))
                .thenReturn(new FtctlDrActionAnswer(answerCommand, true, "resumed"));

        FtctlDrActionAnswer answer = adapter.resumeRemoteSourceProtection(plan, run, 9L, 10L);

        Assert.assertTrue(answer.getResult());
        JsonObject profile = JsonParser.parseString(profileCaptor.getValue()).getAsJsonObject();
        Assert.assertEquals("REMOTE_SOURCE",
                profile.getAsJsonObject("request").get("schedulerTransitionScope").getAsString());
        Assert.assertTrue(profile.getAsJsonObject("request").get("forceImmediateCycle").getAsBoolean());
        Assert.assertEquals(9L,
                profile.getAsJsonObject("request").get("resumeBaselineCheckpointSequence").getAsLong());
        Assert.assertEquals(10L,
                profile.getAsJsonObject("request").get("minimumCompletedCheckpointSequence").getAsLong());
        Assert.assertEquals("site-agent-nbd",
                profile.getAsJsonObject("transport").get("mode").getAsString());
        Assert.assertEquals("nbd://10.10.32.3:12033/dr-forward-sda",
                profile.getAsJsonObject("transport").getAsJsonArray("exports")
                        .get(0).getAsJsonObject().get("uri").getAsString());
        org.mockito.InOrder ordered = Mockito.inOrder(drPlanOwnedTransportService, drRemoteAgentClient);
        ordered.verify(drPlanOwnedTransportService)
                .startForwardTargetExport(Mockito.eq(plan), Mockito.eq(run), Mockito.anyString());
        ordered.verify(drRemoteAgentClient)
                .transitionSourceScheduler(Mockito.eq(plan), Mockito.eq(FtctlDrActionCommand.Action.RESUME_SYNC),
                        Mockito.eq(run.getUuid()), Mockito.anyString(), Mockito.eq(9L), Mockito.eq(10L),
                        Mockito.eq(676L));
    }

    @Test
    public void crossSiteKvmFailoverKeepsTargetExportAndDispatchesFinalDeltaToRemoteSource() throws Exception {
        DrPlanVO plan = new DrPlanVO("cross-site-kvm-failover", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setSourceExternalRef("source-vm-uuid");
        plan.setActiveSide("SOURCE");
        plan.setTargetWorkerHostId(102L);
        plan.setCoordinatorWorkerHostId(103L);
        plan.setMappingJson("{\"source\":{\"hardware\":{\"sourceHostUuid\":\"source-host-uuid\","
                + "\"instanceName\":\"i-2-332-VM\"}},\"target\":{\"storagePoolType\":\"RBD\"},"
                + "\"disks\":[{\"device\":\"sda\",\"sourcePath\":\"rbd:rbd/source-image\","
                + "\"targetPath\":\"rbd:rbd/target-image\",\"targetStorageRef\":\"target-pool-uuid\","
                + "\"target\":{\"storageRef\":\"target-pool-uuid\",\"path\":\"target-image\",\"format\":\"raw\"}}]}");
        DrRunVO run = run(DrConstants.RUN_TYPE_FAILOVER, "{\"mode\":\"planned\",\"finalSync\":true}");
        DrRestorePointVO checkpoint = checkpoint(plan, "ftctl:" + plan.getUuid() + ":source-run:12");
        Mockito.when(drRestorePointDao.findLatestTargetReadyByPlanId(plan.getId())).thenReturn(checkpoint);
        HostVO targetHost = Mockito.mock(HostVO.class);
        Mockito.when(targetHost.getUuid()).thenReturn("target-host-uuid");
        Mockito.when(targetHost.getPrivateIpAddress()).thenReturn("10.10.32.2");
        Mockito.when(hostDao.findById(102L)).thenReturn(targetHost);
        Mockito.when(drRemoteAgentClient.isRemoteKvmSource(plan)).thenReturn(true);
        Mockito.when(drSourceHardwareInventoryService.resolve(plan)).thenReturn(sourceHardware());
        Mockito.when(drPlanOwnedTransportService.supports(plan)).thenReturn(true);
        Mockito.when(drPlanOwnedTransportService.startForwardTargetExport(
                Mockito.eq(plan), Mockito.eq(run), Mockito.anyString()))
                .thenReturn(exports("10.10.32.2", 12032, "dr-export-sda"));
        Mockito.when(drRemoteAgentClient.transitionSourceScheduler(Mockito.eq(plan),
                Mockito.eq(FtctlDrActionCommand.Action.PAUSE_SYNC), Mockito.eq(run.getUuid()),
                Mockito.anyString())).thenAnswer(invocation -> new FtctlDrActionAnswer(
                        new FtctlDrActionCommand(FtctlDrActionCommand.Action.PAUSE_SYNC,
                                plan.getUuid(), run.getUuid()), true, "paused"));
        Mockito.when(drRemoteAgentClient.ensureSourceVmPowerState(plan, false)).thenReturn("POWERED_OFF");
        Mockito.when(drRemoteAgentClient.execute(Mockito.eq(plan), Mockito.eq("CAPABILITIES"),
                Mockito.isA(FtctlDrCapabilitiesCommand.class), Mockito.isNull(),
                Mockito.eq(FtctlDrCapabilitiesAnswer.class))).thenAnswer(invocation -> {
                    FtctlDrCapabilitiesAnswer answer = new FtctlDrCapabilitiesAnswer(invocation.getArgument(2), true, "ok");
                    answer.setSupportedFeatures(java.util.Arrays.asList(
                            "control-protocol-v2", "dr-site-agent-rbd-transport-v1"));
                    return answer;
                });
        ArgumentCaptor<FtctlDrActionCommand> sourceCommandCaptor = ArgumentCaptor.forClass(FtctlDrActionCommand.class);
        Mockito.when(drRemoteAgentClient.execute(Mockito.eq(plan), Mockito.eq("ACTION"), sourceCommandCaptor.capture(),
                Mockito.isNull(), Mockito.eq(FtctlDrActionAnswer.class))).thenAnswer(invocation -> {
                    FtctlDrActionCommand command = invocation.getArgument(2);
                    return new FtctlDrActionAnswer(command, true, "accepted", FtctlDrActionCommand.Action.FAILOVER,
                            plan.getUuid(), run.getUuid(), "accepted", true, "RUNNING", "final-delta",
                            1, run.getUuid(), 0L, null, 0, "{\"result\":\"accepted\"}", "{\"state\":\"RUNNING\"}");
                });

        DrAdapterResult result = adapter.execute(new DrExecutionContext(plan, run));

        Assert.assertTrue(result.isSuccess());
        Mockito.verify(drPlanOwnedTransportService).startForwardTargetExport(
                Mockito.eq(plan), Mockito.eq(run), Mockito.anyString());
        Assert.assertEquals(FtctlDrActionCommand.Action.FAILOVER, sourceCommandCaptor.getValue().getAction());
        Assert.assertTrue(sourceCommandCaptor.getValue().getProfileJson().contains("\"mode\":\"site-agent-nbd\""));
        Mockito.verify(agentManager, Mockito.never()).send(Mockito.anyLong(), Mockito.isA(FtctlDrActionCommand.class));
        org.mockito.InOrder order = Mockito.inOrder(drPlanOwnedTransportService, drRemoteAgentClient);
        order.verify(drPlanOwnedTransportService).startForwardTargetExport(
                Mockito.eq(plan), Mockito.eq(run), Mockito.anyString());
        order.verify(drRemoteAgentClient).transitionSourceScheduler(Mockito.eq(plan),
                Mockito.eq(FtctlDrActionCommand.Action.PAUSE_SYNC), Mockito.eq(run.getUuid()),
                Mockito.anyString());
        order.verify(drRemoteAgentClient).ensureSourceVmPowerState(plan, false);
        order.verify(drRemoteAgentClient).execute(Mockito.eq(plan), Mockito.eq("ACTION"),
                Mockito.isA(FtctlDrActionCommand.class), Mockito.isNull(),
                Mockito.eq(FtctlDrActionAnswer.class));
    }

    @Test
    public void crossSiteKvmDisasterFailoverUsesDurableTargetWithoutSourceAccess() throws Exception {
        DrPlanVO plan = crossSiteKvmPlan();
        String immutableMapping = plan.getMappingJson();
        DrRunVO run = run(DrConstants.RUN_TYPE_FAILOVER,
                "{\"mode\":\"disaster\",\"finalSync\":false,"
                        + "\"sourceIsolationAcknowledged\":true,\"sourceIsolationReason\":\"site unavailable\"}");
        DrRestorePointVO checkpoint = checkpoint(plan, "ftctl:" + plan.getUuid() + ":source-run:22");
        Mockito.when(drRestorePointDao.findLatestTargetReadyByPlanId(plan.getId())).thenReturn(checkpoint);
        Mockito.when(drRemoteAgentClient.isRemoteKvmSource(plan)).thenReturn(true);
        Mockito.when(drPlanOwnedTransportService.supports(plan)).thenReturn(true);
        Mockito.when(agentManager.send(Mockito.eq(103L), Mockito.isA(FtctlDrCapabilitiesCommand.class)))
                .thenAnswer(invocation -> {
                    FtctlDrCapabilitiesAnswer answer = new FtctlDrCapabilitiesAnswer(invocation.getArgument(1), true, "ok");
                    answer.setSupportedFeatures(java.util.Arrays.asList(
                            "control-protocol-v2", "dr-target-disaster-promote-v1"));
                    return answer;
                });
        ArgumentCaptor<FtctlDrActionCommand> commandCaptor = ArgumentCaptor.forClass(FtctlDrActionCommand.class);
        Mockito.when(agentManager.send(Mockito.eq(103L), commandCaptor.capture())).thenAnswer(invocation -> {
            FtctlDrActionCommand command = invocation.getArgument(1);
            return new FtctlDrActionAnswer(command, true, "accepted", FtctlDrActionCommand.Action.FAILOVER,
                    plan.getUuid(), run.getUuid(), "accepted", true, "RUNNING", "target-disaster",
                    1, run.getUuid(), 0L, null, 0, "{\"result\":\"accepted\"}", "{\"state\":\"RUNNING\"}");
        });

        DrAdapterResult result = adapter.execute(new DrExecutionContext(plan, run));

        Assert.assertTrue(result.isSuccess());
        Assert.assertEquals(immutableMapping, plan.getMappingJson());
        Assert.assertNull(commandCaptor.getValue().getSourceWorkerUuid());
        Assert.assertTrue(commandCaptor.getValue().getRequestJson()
                .contains("\"schedulerTransitionScope\":\"TARGET_DISASTER\""));
        Mockito.verify(drPlanOwnedTransportService).stopForwardTargetExport(
                Mockito.eq(plan), Mockito.eq(run), Mockito.anyString(), Mockito.eq(2L));
        Mockito.verify(drPlanOwnedTransportService, Mockito.never()).startForwardTargetExport(
                Mockito.any(DrPlanVO.class), Mockito.any(DrRunVO.class), Mockito.anyString());
        Mockito.verifyNoInteractions(drSourceHardwareInventoryService);
        Mockito.verify(drRemoteAgentClient, Mockito.never()).sourceWorkerUuid(plan);
        Mockito.verify(drRemoteAgentClient, Mockito.never()).execute(Mockito.eq(plan), Mockito.anyString(),
                Mockito.any(), Mockito.anyString(), Mockito.any());
    }

    @Test
    public void sharedMountPointPlannedFailoverDelegatesQmpQuiesceBeforeSourcePowerOff() throws Exception {
        DrPlanVO plan = new DrPlanVO("cross-site-file-failover", 1L, 2L,
                DrConstants.DIRECTION_KVM_TO_KVM);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setSourceExternalRef("source-vm-uuid");
        plan.setActiveSide(DrConstants.AUTHORITY_SIDE_SOURCE);
        plan.setTargetWorkerHostId(102L);
        plan.setCoordinatorWorkerHostId(103L);
        plan.setMappingJson("{\"source\":{\"hardware\":{\"sourceHostUuid\":\"source-host-uuid\","
                + "\"instanceName\":\"i-2-51-VM\"}},\"target\":{\"storagePoolType\":\"SharedMountPoint\"},"
                + "\"disks\":[{\"device\":\"sda\",\"sourcePath\":\"/mnt/glue-gfs/source.qcow2\","
                + "\"sourceType\":\"file\",\"sourceFormat\":\"qcow2\","
                + "\"targetPath\":\"/mnt/glue-gfs/target.qcow2\",\"targetType\":\"file\","
                + "\"targetFormat\":\"qcow2\",\"targetStorageRef\":\"target-pool-uuid\","
                + "\"target\":{\"storageRef\":\"target-pool-uuid\","
                + "\"storagePoolType\":\"SharedMountPoint\",\"path\":\"target.qcow2\"}}]}");
        DrRunVO run = run(DrConstants.RUN_TYPE_FAILOVER, "{\"mode\":\"planned\",\"finalSync\":true}");
        Mockito.when(drRestorePointDao.findLatestTargetReadyByPlanId(plan.getId()))
                .thenReturn(checkpoint(plan, "ftctl:" + plan.getUuid() + ":source-run:12"));
        HostVO targetHost = Mockito.mock(HostVO.class);
        Mockito.when(targetHost.getUuid()).thenReturn("target-host-uuid");
        Mockito.when(targetHost.getPrivateIpAddress()).thenReturn("10.10.31.2");
        Mockito.when(hostDao.findById(102L)).thenReturn(targetHost);
        Mockito.when(drRemoteAgentClient.isRemoteKvmSource(plan)).thenReturn(true);
        Mockito.when(drSourceHardwareInventoryService.resolve(plan)).thenReturn(sourceHardware());
        Mockito.when(drPlanOwnedTransportService.supports(plan)).thenReturn(true);
        Mockito.when(drPlanOwnedTransportService.startForwardTargetExport(
                Mockito.eq(plan), Mockito.eq(run), Mockito.anyString()))
                .thenReturn(exports("10.10.31.2", 12031, "dr-export-sda"));
        Mockito.when(drRemoteAgentClient.getSourceVmPowerState(plan)).thenReturn("POWERED_ON");
        Mockito.when(drRemoteAgentClient.transitionSourceScheduler(Mockito.eq(plan),
                Mockito.eq(FtctlDrActionCommand.Action.PAUSE_SYNC), Mockito.eq(run.getUuid()),
                Mockito.anyString())).thenAnswer(invocation -> new FtctlDrActionAnswer(
                        new FtctlDrActionCommand(FtctlDrActionCommand.Action.PAUSE_SYNC,
                                plan.getUuid(), run.getUuid()), true, "paused"));
        Mockito.when(drRemoteAgentClient.execute(Mockito.eq(plan), Mockito.eq("CAPABILITIES"),
                Mockito.isA(FtctlDrCapabilitiesCommand.class), Mockito.isNull(),
                Mockito.eq(FtctlDrCapabilitiesAnswer.class))).thenAnswer(invocation -> {
                    FtctlDrCapabilitiesAnswer answer = new FtctlDrCapabilitiesAnswer(
                            invocation.getArgument(2), true, "ok");
                    answer.setSupportedFeatures(java.util.Arrays.asList(
                            "control-protocol-v2", "dr-site-agent-rbd-transport-v1",
                            "file-checkpoint-invariance-v1",
                            "dr-file-planned-failover-runtime-quiesce-v2"));
                    return answer;
                });
        ArgumentCaptor<FtctlDrActionCommand> commandCaptor = ArgumentCaptor.forClass(FtctlDrActionCommand.class);
        Mockito.when(drRemoteAgentClient.execute(Mockito.eq(plan), Mockito.eq("ACTION"),
                commandCaptor.capture(), Mockito.isNull(),
                Mockito.eq(FtctlDrActionAnswer.class))).thenAnswer(invocation -> {
                    FtctlDrActionCommand command = invocation.getArgument(2);
                    return new FtctlDrActionAnswer(command, true, "accepted",
                            FtctlDrActionCommand.Action.FAILOVER, plan.getUuid(), run.getUuid(),
                            "accepted", true, "RUNNING", "source-runtime-quiesce", 1,
                            run.getUuid(), 0L, null, 0, "{\"result\":\"accepted\"}",
                            "{\"state\":\"RUNNING\"}");
                });

        DrAdapterResult result = adapter.execute(new DrExecutionContext(plan, run));

        Assert.assertTrue(result.isSuccess());
        FtctlDrActionCommand command = commandCaptor.getValue();
        JsonObject request = JsonParser.parseString(command.getRequestJson()).getAsJsonObject();
        Assert.assertTrue(request.get("sourceRuntimeQuiesceRequired").getAsBoolean());
        Assert.assertEquals("QMP_STOP", request.get("sourceRuntimeQuiesceMode").getAsString());
        Assert.assertEquals("POWERED_ON", request.get("sourceRuntimeObservedPowerState").getAsString());
        Assert.assertEquals(run.getUuid(), request.get("cutoverRunUuid").getAsString());
        JsonObject profileRequest = JsonParser.parseString(command.getProfileJson()).getAsJsonObject()
                .getAsJsonObject("request");
        Assert.assertTrue(profileRequest.get("sourceRuntimeQuiesceRequired").getAsBoolean());
        Assert.assertEquals("QMP_STOP", profileRequest.get("sourceRuntimeQuiesceMode").getAsString());
        Assert.assertEquals("POWERED_ON", profileRequest.get("sourceRuntimeObservedPowerState").getAsString());
        Assert.assertEquals(run.getUuid(), profileRequest.get("cutoverRunUuid").getAsString());
        Mockito.verify(drRemoteAgentClient, Mockito.never()).ensureSourceVmPowerState(plan, false);
        org.mockito.InOrder order = Mockito.inOrder(drPlanOwnedTransportService, drRemoteAgentClient);
        order.verify(drPlanOwnedTransportService).startForwardTargetExport(
                Mockito.eq(plan), Mockito.eq(run), Mockito.anyString());
        order.verify(drRemoteAgentClient).transitionSourceScheduler(Mockito.eq(plan),
                Mockito.eq(FtctlDrActionCommand.Action.PAUSE_SYNC), Mockito.eq(run.getUuid()),
                Mockito.anyString());
        order.verify(drRemoteAgentClient).execute(Mockito.eq(plan), Mockito.eq("ACTION"),
                Mockito.isA(FtctlDrActionCommand.class), Mockito.isNull(),
                Mockito.eq(FtctlDrActionAnswer.class));
    }

    @Test
    public void sharedMountPointPlannedFailoverUsesOfflineQuiesceOnlyForObservedStoppedSource() {
        DrPlanVO plan = new DrPlanVO("cross-site-file-offline", 1L, 2L,
                DrConstants.DIRECTION_KVM_TO_KVM);
        plan.setSourceExternalRef("source-vm-uuid");
        plan.setActiveSide(DrConstants.AUTHORITY_SIDE_SOURCE);
        plan.setMappingJson("{\"target\":{\"storagePoolType\":\"SharedMountPoint\"},"
                + "\"disks\":[{\"target\":{\"storagePoolType\":\"SharedMountPoint\"}}]}");
        DrRunVO run = run(DrConstants.RUN_TYPE_FAILOVER, "{\"mode\":\"planned\",\"finalSync\":true}");
        FtctlDrActionCommand command = new FtctlDrActionCommand(
                FtctlDrActionCommand.Action.FAILOVER, plan.getUuid(), run.getUuid());
        command.setRequestJson("{}");
        command.setProfileJson("{\"request\":{}}");
        Mockito.when(drPlanOwnedTransportService.supports(plan)).thenReturn(true);
        Mockito.when(drRemoteAgentClient.getSourceVmPowerState(plan)).thenReturn("POWERED_OFF");

        ReflectionTestUtils.invokeMethod(adapter, "applyPlannedFileSourceQuiesceContract",
                new DrExecutionContext(plan, run), FtctlDrActionCommand.Action.FAILOVER, command);

        JsonObject request = JsonParser.parseString(command.getRequestJson()).getAsJsonObject();
        JsonObject profileRequest = JsonParser.parseString(command.getProfileJson()).getAsJsonObject()
                .getAsJsonObject("request");
        Assert.assertEquals("SOURCE_ALREADY_STOPPED",
                request.get("sourceRuntimeQuiesceMode").getAsString());
        Assert.assertEquals("POWERED_OFF",
                request.get("sourceRuntimeObservedPowerState").getAsString());
        Assert.assertEquals("SOURCE_ALREADY_STOPPED",
                profileRequest.get("sourceRuntimeQuiesceMode").getAsString());
        Assert.assertEquals("POWERED_OFF",
                profileRequest.get("sourceRuntimeObservedPowerState").getAsString());
        Mockito.verify(drRemoteAgentClient, Mockito.never()).ensureSourceVmPowerState(plan, false);
    }

    @Test
    public void plannedCrossSiteKvmFailoverRestoresSourceWhenPowerOffFails() throws Exception {
        DrPlanVO plan = crossSiteKvmPlan();
        DrRunVO run = run(DrConstants.RUN_TYPE_FAILOVER, "{\"mode\":\"planned\",\"finalSync\":true}");
        Mockito.when(drRestorePointDao.findLatestTargetReadyByPlanId(plan.getId()))
                .thenReturn(checkpoint(plan, "ftctl:" + plan.getUuid() + ":source-run:12"));
        Mockito.when(drRemoteAgentClient.isRemoteKvmSource(plan)).thenReturn(true);
        Mockito.when(drSourceHardwareInventoryService.resolve(plan)).thenReturn(sourceHardware());
        Mockito.when(drPlanOwnedTransportService.supports(plan)).thenReturn(true);
        HostVO targetHost = Mockito.mock(HostVO.class);
        Mockito.when(targetHost.getUuid()).thenReturn("target-host-uuid");
        Mockito.when(targetHost.getPrivateIpAddress()).thenReturn("10.10.32.2");
        Mockito.when(hostDao.findById(102L)).thenReturn(targetHost);
        Mockito.when(drPlanOwnedTransportService.startForwardTargetExport(
                Mockito.eq(plan), Mockito.eq(run), Mockito.anyString()))
                .thenReturn(exports("10.10.32.2", 12032, "dr-export-sda"));
        mockRemoteKvmCapabilities(plan);
        Mockito.when(drRemoteAgentClient.transitionSourceScheduler(Mockito.eq(plan),
                Mockito.eq(FtctlDrActionCommand.Action.PAUSE_SYNC), Mockito.eq(run.getUuid()),
                Mockito.anyString())).thenAnswer(invocation -> new FtctlDrActionAnswer(
                        new FtctlDrActionCommand(FtctlDrActionCommand.Action.PAUSE_SYNC,
                                plan.getUuid(), run.getUuid()), true, "paused"));
        Mockito.when(drRemoteAgentClient.ensureSourceVmPowerState(plan, false))
                .thenThrow(new CloudRuntimeException("source stop rejected"));
        Mockito.when(drRemoteAgentClient.ensureSourceVmPowerState(plan, true)).thenReturn("POWERED_ON");
        Mockito.when(drRemoteAgentClient.transitionSourceScheduler(Mockito.eq(plan),
                Mockito.eq(FtctlDrActionCommand.Action.RESUME_SYNC), Mockito.eq(run.getUuid()),
                Mockito.anyString(), Mockito.isNull(), Mockito.isNull(), Mockito.isNull()))
                .thenAnswer(invocation -> new FtctlDrActionAnswer(
                        new FtctlDrActionCommand(FtctlDrActionCommand.Action.RESUME_SYNC,
                                plan.getUuid(), run.getUuid()), true, "resumed"));

        DrAdapterResult result = adapter.execute(new DrExecutionContext(plan, run));

        Assert.assertFalse(result.isSuccess());
        Assert.assertEquals(DrConstants.ERROR_SOURCE_ISOLATION_NOT_READY, result.getErrorCode());
        Mockito.verify(drRemoteAgentClient).ensureSourceVmPowerState(plan, true);
        Mockito.verify(drRemoteAgentClient, Mockito.never()).execute(Mockito.eq(plan), Mockito.eq("ACTION"),
                Mockito.isA(FtctlDrActionCommand.class), Mockito.anyString(),
                Mockito.eq(FtctlDrActionAnswer.class));
    }

    @Test
    public void crossSiteKvmFailbackPreparesReverseExportOnOriginalSite() throws Exception {
        DrPlanVO plan = new DrPlanVO("cross-site-kvm-failback", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setSourceExternalRef("source-vm-uuid");
        plan.setActiveSide("TARGET");
        plan.setTargetWorkerHostId(102L);
        plan.setCoordinatorWorkerHostId(103L);
        plan.setMappingJson("{\"source\":{\"hardware\":{\"sourceHostUuid\":\"source-host-uuid\","
                + "\"instanceName\":\"i-2-332-VM\"}},\"target\":{\"storagePoolType\":\"RBD\",\"storagePath\":\"rbd\"},"
                + "\"disks\":[{\"device\":\"sda\",\"sourcePath\":\"rbd:rbd/source-image\","
                + "\"targetPath\":\"target-image\",\"targetStorageRef\":\"target-pool-uuid\","
                + "\"targetStoragePath\":\"rbd\",\"targetStorageType\":\"RBD\","
                + "\"target\":{\"storageRef\":\"target-pool-uuid\",\"storagePath\":\"rbd\","
                + "\"storagePoolType\":\"RBD\",\"path\":\"target-image\",\"format\":\"raw\"}}]}");
        DrRunVO run = run(DrConstants.RUN_TYPE_FAILBACK, "{}");
        Mockito.when(drRemoteAgentClient.isRemoteKvmSource(plan)).thenReturn(true);
        Mockito.when(drPlanOwnedTransportService.supports(plan)).thenReturn(true);
        Mockito.when(drPlanOwnedTransportService.startReverseTargetExport(
                Mockito.eq(plan), Mockito.eq(run), Mockito.anyString()))
                .thenReturn(exports("10.10.22.1", 12022, "dr-reverse-sda"));
        Mockito.when(drFailbackPreflightService.validate(plan, run))
                .thenReturn(DrFailbackPreflightResult.success(null, null, null));
        Mockito.when(agentManager.send(Mockito.eq(103L), Mockito.isA(FtctlDrCapabilitiesCommand.class)))
                .thenAnswer(invocation -> {
                    FtctlDrCapabilitiesAnswer answer = new FtctlDrCapabilitiesAnswer(invocation.getArgument(1), true, "ok");
                    answer.setReprotectAuthorityContractVersions(java.util.Collections.singletonList(
                            DrReprotectAuthoritySpec.CONTRACT_VERSION));
                    answer.setSupportedFeatures(java.util.Arrays.asList("control-protocol-v2",
                            "dr-transition-preflight-v2", "dr-reverse-site-agent-rbd-transport-v1",
                            "dr-remote-source-failback-commit-v1"));
                    return answer;
                });
        ArgumentCaptor<FtctlDrActionCommand> failbackCommand = ArgumentCaptor.forClass(FtctlDrActionCommand.class);
        Mockito.when(agentManager.send(Mockito.eq(103L), failbackCommand.capture())).thenAnswer(invocation -> {
            FtctlDrActionCommand command = invocation.getArgument(1);
            return new FtctlDrActionAnswer(command, true, "accepted", FtctlDrActionCommand.Action.FAILBACK,
                    plan.getUuid(), run.getUuid(), "accepted", true, "FAILBACK_SYNCING", "reverse-transfer",
                    1, run.getUuid(), 0L, null, 0, "{\"result\":\"accepted\"}",
                    "{\"state\":\"FAILBACK_SYNCING\"}");
        });

        DrAdapterResult result = adapter.execute(new DrExecutionContext(plan, run));

        Assert.assertTrue(result.isSuccess());
        Mockito.verify(drPlanOwnedTransportService).startReverseTargetExport(
                Mockito.eq(plan), Mockito.eq(run), Mockito.anyString());
        Assert.assertEquals(FtctlDrActionCommand.Action.FAILBACK, failbackCommand.getValue().getAction());
        Assert.assertTrue(failbackCommand.getValue().getProfileJson().contains("\"mode\":\"site-agent-nbd\""));
        Assert.assertTrue(failbackCommand.getValue().getProfileJson().contains("dr-reverse-sda"));
        Mockito.verify(agentManager, Mockito.never()).easySend(Mockito.eq(102L), Mockito.any());
    }

    @Test
    public void crossSiteKvmReprotectPreparesReverseExportOnOriginalSite() throws Exception {
        DrPlanVO plan = new DrPlanVO("cross-site-kvm-reprotect", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setSourceExternalRef("source-vm-uuid");
        plan.setState(DrConstants.PLAN_STATE_FAILED_OVER);
        plan.setActiveSide("TARGET");
        plan.setTargetWorkerHostId(102L);
        plan.setCoordinatorWorkerHostId(103L);
        plan.setMappingJson("{\"source\":{\"hardware\":{\"sourceHostUuid\":\"source-host-uuid\","
                + "\"instanceName\":\"i-2-332-VM\"}},\"target\":{\"storagePoolType\":\"RBD\",\"storagePath\":\"rbd\"},"
                + "\"disks\":[{\"device\":\"sda\",\"sourcePath\":\"rbd:rbd/source-image\","
                + "\"targetPath\":\"target-image\",\"targetStorageRef\":\"target-pool-uuid\","
                + "\"targetStoragePath\":\"rbd\",\"targetStorageType\":\"RBD\"}]}");
        DrRunVO run = run(DrConstants.RUN_TYPE_REPROTECT, "{}");
        DrReprotectAuthoritySpec spec = new DrReprotectAuthoritySpec();
        spec.setPlanUuid(plan.getUuid());
        spec.setRunUuid(run.getUuid());
        spec.setExpectedActiveSide("TARGET");
        spec.setAuthorityGeneration(3L);
        spec.setCutoverSessionId("cutover-1");
        spec.setCheckpointSequence(17L);
        spec.setTargetVmId(287L);
        spec.setTargetExternalRef("target-vm-uuid");
        spec.setTargetInstanceName("i-2-287-VM");
        spec.setTargetPowerState("POWERED_ON");
        spec.setTargetMaterialized(true);
        spec.setTargetPromotionState("PROMOTED");
        Mockito.when(drRemoteAgentClient.isRemoteKvmSource(plan)).thenReturn(true);
        Mockito.when(drPlanOwnedTransportService.supports(plan)).thenReturn(true);
        Mockito.when(drPlanOwnedTransportService.startReverseTargetExport(
                Mockito.eq(plan), Mockito.eq(run), Mockito.anyString()))
                .thenReturn(exports("10.10.22.1", 12022, "dr-reprotect-sda"));
        Mockito.when(drReprotectPreflightService.validate(plan, run))
                .thenReturn(DrReprotectPreflightResult.success(spec));
        Mockito.when(agentManager.send(Mockito.eq(103L), Mockito.isA(FtctlDrCapabilitiesCommand.class)))
                .thenAnswer(invocation -> {
                    FtctlDrCapabilitiesAnswer answer = new FtctlDrCapabilitiesAnswer(invocation.getArgument(1), true, "ok");
                    answer.setReprotectAuthorityContractVersions(java.util.Collections.singletonList(
                            DrReprotectAuthoritySpec.CONTRACT_VERSION));
                    answer.setSupportedFeatures(java.util.Arrays.asList("control-protocol-v2",
                            "dr-transition-preflight-v2", "dr-reverse-site-agent-rbd-transport-v1"));
                    return answer;
                });
        ArgumentCaptor<FtctlDrActionCommand> commandCaptor = ArgumentCaptor.forClass(FtctlDrActionCommand.class);
        Mockito.when(agentManager.send(Mockito.eq(103L), commandCaptor.capture())).thenAnswer(invocation -> {
            FtctlDrActionCommand command = invocation.getArgument(1);
            return new FtctlDrActionAnswer(command, true, "accepted", FtctlDrActionCommand.Action.REPROTECT,
                    plan.getUuid(), run.getUuid(), "accepted", true, "REPROTECTING", "reverse-transfer",
                    1, run.getUuid(), 0L, null, 0, "{\"result\":\"accepted\"}",
                    "{\"state\":\"REPROTECTING\"}");
        });

        DrAdapterResult result = adapter.execute(new DrExecutionContext(plan, run));

        Assert.assertTrue(result.isSuccess());
        Mockito.verify(drPlanOwnedTransportService).startReverseTargetExport(
                Mockito.eq(plan), Mockito.eq(run), Mockito.anyString());
        Assert.assertEquals(FtctlDrActionCommand.Action.REPROTECT, commandCaptor.getValue().getAction());
        Assert.assertTrue(commandCaptor.getValue().getProfileJson().contains("\"mode\":\"site-agent-nbd\""));
        Assert.assertTrue(commandCaptor.getValue().getProfileJson().contains("dr-reprotect-sda"));
    }

    private JsonArray exports(String host, int port, String name) {
        return JsonParser.parseString("[{\"device\":\"sda\",\"host\":\"" + host
                + "\",\"port\":" + port + ",\"name\":\"" + name
                + "\",\"uri\":\"nbd://" + host + ":" + port + "/" + name + "\"}]")
                .getAsJsonArray();
    }

    @Test
    public void testFailoverDispatchesRestorePointReferenceToFtctlProfile() throws Exception {
        DrPlanVO plan = ftctlDrPlan();
        DrRunVO run = run(DrConstants.RUN_TYPE_TEST_FAILOVER,
                "{\"restorePointId\":9,\"restorePointRef\":\"ftctl:" + plan.getUuid() + ":2\"}");
        ArgumentCaptor<FtctlDrActionCommand> commandCaptor = ArgumentCaptor.forClass(FtctlDrActionCommand.class);
        DrRestorePointVO checkpoint = checkpoint(plan, "ftctl:" + plan.getUuid() + ":run-sync:2");
        Mockito.when(drRestorePointDao.findLatestTargetReadyByPlanId(plan.getId())).thenReturn(checkpoint);
        mockCapabilities();
        Mockito.when(agentManager.send(Mockito.eq(103L), commandCaptor.capture())).thenAnswer(invocation -> {
            FtctlDrActionCommand command = invocation.getArgument(1);
            return new FtctlDrActionAnswer(command, true, "accepted", FtctlDrActionCommand.Action.TEST_PREPARE,
                    plan.getUuid(), run.getUuid(), "accepted", true, "TESTING", "test-session-ready",
                    100, run.getUuid(), 0L, null, 0, "{\"result\":\"accepted\"}",
                    "{\"state\":\"TESTING\",\"test_restore_point_ref\":\"ftctl:" + plan.getUuid() + ":2\"}");
        });

        DrAdapterResult result = adapter.execute(new DrExecutionContext(plan, run));

        Assert.assertTrue(result.isSuccess());
        Assert.assertFalse(result.isTerminal());
        FtctlDrActionCommand command = commandCaptor.getValue();
        Assert.assertEquals(FtctlDrActionCommand.Action.TEST_PREPARE, command.getAction());
        Assert.assertNull(command.getRestorePointId());
        Assert.assertEquals(checkpoint.getSourceSnapshotRef(), command.getCheckpointRef());
        Assert.assertTrue(command.getProfileJson().contains("\"restorePointRef\":\"" + checkpoint.getSourceSnapshotRef() + "\""));
        Assert.assertTrue(command.getRequestJson().contains("\"restorePointRef\":\"" + checkpoint.getSourceSnapshotRef() + "\""));
        Assert.assertTrue(command.getRequestJson().contains("\"checkpointContractVersion\":1"));
        Assert.assertTrue(command.getRequestJson().contains("\"checkpointSequence\":2"));
        Assert.assertTrue(command.getArtifactSpecJson().contains("\"checkpointContractVersion\":1"));
        Assert.assertFalse(command.getRequestJson().contains("restorePointId"));
        Assert.assertEquals("3", command.getArtifactContractVersion());
        Assert.assertTrue(command.getArtifactSpecJson().contains("\"canonicalLocator\":\"rbd:rbd/Rocky10-1-dr-disk-0\""));
    }

    @Test
    public void testFailoverResolvesSharedMountPointRelativeVolumePath() throws Exception {
        DrPlanVO plan = ftctlDrPlan();
        plan.setMappingJson("{\"target\":{\"storagePoolType\":\"SharedMountPoint\",\"storagePath\":\"/mnt/glue-gfs\"},"
                + "\"disks\":[{\"device\":\"sda\",\"target\":{\"volumeId\":221,"
                + "\"path\":\"rocky9-vm-dr-disk-0\",\"storagePoolType\":\"SharedMountPoint\","
                + "\"storagePath\":\"/mnt/glue-gfs\",\"format\":\"qcow2\"}}]}");
        DrRunVO run = run(DrConstants.RUN_TYPE_TEST_FAILOVER,
                "{\"restorePointRef\":\"ftctl:" + plan.getUuid() + ":2\"}");
        DrRestorePointVO checkpoint = checkpoint(plan, "ftctl:" + plan.getUuid() + ":run-sync:2");
        Mockito.when(drRestorePointDao.findLatestTargetReadyByPlanId(plan.getId())).thenReturn(checkpoint);
        ArgumentCaptor<FtctlDrActionCommand> commandCaptor = ArgumentCaptor.forClass(FtctlDrActionCommand.class);
        mockCapabilities();
        Mockito.when(agentManager.easySend(Mockito.eq(103L), Mockito.argThat(command ->
                command instanceof FtctlDrActionCommand
                        && ((FtctlDrActionCommand) command).getAction() == FtctlDrActionCommand.Action.PAUSE_SYNC)))
                .thenAnswer(invocation -> new FtctlDrActionAnswer(invocation.getArgument(1), true, "paused"));
        Mockito.when(agentManager.send(Mockito.eq(103L), commandCaptor.capture())).thenAnswer(invocation -> {
            FtctlDrActionCommand command = invocation.getArgument(1);
            return new FtctlDrActionAnswer(command, true, "accepted", FtctlDrActionCommand.Action.TEST_PREPARE,
                    plan.getUuid(), run.getUuid(), "accepted", true, "TESTING", "test-session-ready",
                    100, run.getUuid(), 0L, null, 0, "{\"result\":\"accepted\"}", "{\"state\":\"TESTING\"}");
        });

        DrAdapterResult result = adapter.execute(new DrExecutionContext(plan, run));

        Assert.assertTrue(result.isSuccess());
        FtctlDrActionCommand action = commandCaptor.getValue();
        Assert.assertTrue(action.getArtifactSpecJson()
                .contains("\"canonicalLocator\":\"file:/mnt/glue-gfs/rocky9-vm-dr-disk-0\""));
        Assert.assertTrue(action.getArtifactSpecJson().contains("\"checkpointImmutableRequired\":true"));
        Assert.assertTrue(action.getRequestJson().contains("\"checkpointWriterState\":\"DRAINED\""));
        org.mockito.InOrder order = Mockito.inOrder(agentManager);
        order.verify(agentManager).easySend(Mockito.eq(103L), Mockito.isA(FtctlDrActionCommand.class));
        order.verify(agentManager).send(Mockito.eq(103L), Mockito.isA(FtctlDrActionCommand.class));
    }

    @Test
    public void vmwareSharedMountPointTestCleanupDoesNotResumeBeforeProjection() throws Exception {
        DrPlanVO plan = ftctlDrPlan();
        plan.setMappingJson("{\"target\":{\"storagePoolType\":\"SharedMountPoint\",\"storagePath\":\"/mnt/glue-gfs\"},"
                + "\"disks\":[{\"device\":\"sda\",\"target\":{\"path\":\"windows-dr-disk-0\","
                + "\"storagePoolType\":\"SharedMountPoint\",\"storagePath\":\"/mnt/glue-gfs\",\"format\":\"qcow2\"}}]}");
        DrRunVO run = run(DrConstants.RUN_TYPE_TEST_CLEANUP, "{}");
        mockCapabilities();
        Mockito.when(agentManager.send(Mockito.eq(103L), Mockito.isA(FtctlDrActionCommand.class)))
                .thenAnswer(invocation -> new FtctlDrActionAnswer(invocation.getArgument(1), true, "cleaned",
                        FtctlDrActionCommand.Action.TEST_ARTIFACT_CLEANUP, plan.getUuid(), run.getUuid(),
                        "success", true, "READY", "test-cleanup-completed", 100, run.getUuid(), 0L,
                        null, 0, "{\"result\":\"success\"}", "{\"state\":\"READY\"}"));


        DrAdapterResult result = adapter.execute(new DrExecutionContext(plan, run));

        Assert.assertTrue(result.isSuccess());
        Mockito.verify(agentManager, Mockito.never()).easySend(Mockito.eq(103L), Mockito.argThat(command ->
                command instanceof FtctlDrActionCommand
                        && ((FtctlDrActionCommand) command).getAction() == FtctlDrActionCommand.Action.RESUME_SYNC));
        Mockito.verify(drRemoteAgentClient, Mockito.never()).transitionSourceScheduler(
                Mockito.eq(plan), Mockito.any(FtctlDrActionCommand.Action.class),
                Mockito.eq(run.getUuid()), Mockito.anyString());
    }

    @Test
    public void remoteSharedMountPointTestFailoverDrainsWriterBeforeAgentMaterialization() throws Exception {
        DrPlanVO plan = new DrPlanVO("remote-file-test", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setSourceExternalRef("source-vm-uuid");
        plan.setActiveSide("SOURCE");
        plan.setTargetWorkerHostId(102L);
        plan.setCoordinatorWorkerHostId(103L);
        plan.setMappingJson("{\"source\":{\"hardware\":{\"sourceHostUuid\":\"source-host-uuid\","
                + "\"instanceName\":\"i-2-13-VM\"}},\"target\":{\"storagePoolType\":\"SharedMountPoint\","
                + "\"storagePath\":\"/mnt/glue-gfs\"},\"disks\":[{\"device\":\"sda\","
                + "\"targetStorageRef\":\"target-pool-uuid\",\"target\":{\"storageRef\":\"target-pool-uuid\","
                + "\"path\":\"rocky9-vm-dr-disk-0\",\"storagePoolType\":\"SharedMountPoint\","
                + "\"storagePath\":\"/mnt/glue-gfs\",\"format\":\"qcow2\"}}]}");
        DrRunVO run = run(DrConstants.RUN_TYPE_TEST_FAILOVER, "{}");
        DrRestorePointVO checkpoint = checkpoint(plan, "ftctl:" + plan.getUuid() + ":run-sync:2");
        Mockito.when(drRestorePointDao.findLatestTargetReadyByPlanId(plan.getId())).thenReturn(checkpoint);
        Mockito.when(drRemoteAgentClient.isRemoteKvmSource(plan)).thenReturn(true);
        Mockito.when(drSourceHardwareInventoryService.resolve(plan)).thenReturn(sourceHardware());
        Mockito.when(drPlanOwnedTransportService.supports(plan)).thenReturn(true);
        mockCapabilities();
        Mockito.when(drRemoteAgentClient.transitionSourceScheduler(Mockito.eq(plan),
                Mockito.eq(FtctlDrActionCommand.Action.PAUSE_SYNC), Mockito.eq(run.getUuid()),
                Mockito.anyString())).thenAnswer(invocation -> {
                    FtctlDrActionCommand command = new FtctlDrActionCommand(
                            FtctlDrActionCommand.Action.PAUSE_SYNC, plan.getUuid(), run.getUuid());
                    return new FtctlDrActionAnswer(command, true, "paused");
                });
        ArgumentCaptor<FtctlDrActionCommand> actionCaptor = ArgumentCaptor.forClass(FtctlDrActionCommand.class);
        Mockito.when(agentManager.send(Mockito.eq(103L), actionCaptor.capture())).thenAnswer(invocation -> {
            FtctlDrActionCommand command = invocation.getArgument(1);
            return new FtctlDrActionAnswer(command, true, "accepted", FtctlDrActionCommand.Action.TEST_PREPARE,
                    plan.getUuid(), run.getUuid(), "accepted", true, "TESTING", "test-artifact-prepare-accepted",
                    70, run.getUuid(), 0L, null, 0, "{\"result\":\"accepted\"}",
                    "{\"state\":\"TESTING\"}");
        });

        DrAdapterResult result = adapter.execute(new DrExecutionContext(plan, run));

        Assert.assertTrue(result.isSuccess());
        FtctlDrActionCommand action = actionCaptor.getValue();
        Assert.assertTrue(action.getRequestJson().contains("\"checkpointWriterState\":\"DRAINED\""));
        Assert.assertTrue(action.getRequestJson().contains("\"checkpointImmutableRequired\":true"));
        Assert.assertTrue(action.getArtifactSpecJson().contains("\"checkpointImmutableRequired\":true"));
        Assert.assertTrue(action.getProfileJson().contains("\"UEFI\":\"LEGACY\""));
        org.mockito.InOrder order = Mockito.inOrder(drRemoteAgentClient, drPlanOwnedTransportService, agentManager);
        order.verify(drRemoteAgentClient).transitionSourceScheduler(Mockito.eq(plan),
                Mockito.eq(FtctlDrActionCommand.Action.PAUSE_SYNC), Mockito.eq(run.getUuid()), Mockito.anyString());
        order.verify(drPlanOwnedTransportService).stopForwardTargetExport(Mockito.eq(plan), Mockito.eq(run),
                Mockito.anyString(), Mockito.eq(2L));
        order.verify(agentManager).send(Mockito.eq(103L), Mockito.isA(FtctlDrActionCommand.class));
    }

    @Test
    public void sourceIndependentTestDoesNotContactSourceAndRequiresExistingSeal() throws Exception {
        DrPlanVO plan = new DrPlanVO("remote-file-test", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setSourceExternalRef("source-vm-uuid");
        plan.setActiveSide("SOURCE");
        plan.setTargetWorkerHostId(102L);
        plan.setCoordinatorWorkerHostId(103L);
        plan.setMappingJson("{\"source\":{\"hardware\":{\"sourceHostUuid\":\"source-host-uuid\","
                + "\"instanceName\":\"i-2-13-VM\"}},\"target\":{\"storagePoolType\":\"SharedMountPoint\","
                + "\"storagePath\":\"/mnt/glue-gfs\"},\"disks\":[{\"device\":\"sda\","
                + "\"targetStorageRef\":\"target-pool-uuid\",\"target\":{\"storageRef\":\"target-pool-uuid\","
                + "\"path\":\"rocky9-vm-dr-disk-0\",\"storagePoolType\":\"SharedMountPoint\","
                + "\"storagePath\":\"/mnt/glue-gfs\",\"format\":\"qcow2\"}}]}");
        DrRunVO run = run(DrConstants.RUN_TYPE_TEST_FAILOVER, "{\"sourceIndependent\":true}");
        DrRestorePointVO checkpoint = checkpoint(plan, "ftctl:" + plan.getUuid() + ":run-sync:2");
        Mockito.when(drRestorePointDao.findLatestTargetReadyByPlanId(plan.getId())).thenReturn(checkpoint);
        Mockito.when(drRemoteAgentClient.isRemoteKvmSource(plan)).thenReturn(true);
        Mockito.when(drPlanOwnedTransportService.supports(plan)).thenReturn(true);
        mockCapabilities();
        ArgumentCaptor<FtctlDrActionCommand> actionCaptor = ArgumentCaptor.forClass(FtctlDrActionCommand.class);
        Mockito.when(agentManager.send(Mockito.eq(103L), actionCaptor.capture())).thenAnswer(invocation -> {
            FtctlDrActionCommand command = invocation.getArgument(1);
            return new FtctlDrActionAnswer(command, true, "accepted", FtctlDrActionCommand.Action.TEST_PREPARE,
                    plan.getUuid(), run.getUuid(), "accepted", true, "TESTING", "test-artifact-prepare-accepted",
                    70, run.getUuid(), 0L, null, 0, "{\"result\":\"accepted\"}",
                    "{\"state\":\"TESTING\"}");
        });

        DrAdapterResult result = adapter.execute(new DrExecutionContext(plan, run));

        Assert.assertTrue(result.isSuccess());
        FtctlDrActionCommand action = actionCaptor.getValue();
        Assert.assertTrue(action.getRequestJson().contains("\"checkpointWriterState\":\"DRAINED\""));
        Assert.assertTrue(action.getRequestJson().contains("\"checkpointImmutableRequired\":true"));
        Assert.assertTrue(action.getArtifactSpecJson().contains("\"checkpointImmutableRequired\":true"));
        Assert.assertTrue(action.getRequestJson().contains("\"checkpointExistingSealRequired\":true"));
        Mockito.verify(drSourceHardwareInventoryService, Mockito.never()).resolve(Mockito.any());
        Mockito.verify(drRemoteAgentClient, Mockito.never()).transitionSourceScheduler(Mockito.any(), Mockito.any(), Mockito.anyString(), Mockito.anyString());
        Mockito.verify(drPlanOwnedTransportService).stopForwardTargetExport(Mockito.eq(plan), Mockito.eq(run), Mockito.anyString(), Mockito.eq(2L));
    }

    @Test
    public void remoteRbdTestFailoverDrainsAllWritersBeforeCloningDiskSet() throws Exception {
        DrPlanVO plan = new DrPlanVO("remote-rbd-test", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setSourceExternalRef("source-vm-uuid");
        plan.setActiveSide("SOURCE");
        plan.setTargetWorkerHostId(102L);
        plan.setCoordinatorWorkerHostId(103L);
        plan.setMappingJson("{\"source\":{\"hardware\":{\"sourceHostUuid\":\"source-host-uuid\","
                + "\"instanceName\":\"i-2-13-VM\"}},\"target\":{\"storagePoolType\":\"RBD\","
                + "\"storagePath\":\"rbd\"},\"disks\":[{\"device\":\"sda\","
                + "\"targetStorageRef\":\"target-pool-uuid\",\"target\":{"
                + "\"storageRef\":\"target-pool-uuid\",\"path\":\"target-root\","
                + "\"storagePoolType\":\"RBD\",\"storagePath\":\"rbd\",\"format\":\"raw\"}},"
                + "{\"device\":\"sdb\",\"targetStorageRef\":\"target-pool-uuid\",\"target\":{"
                + "\"storageRef\":\"target-pool-uuid\",\"path\":\"target-data\","
                + "\"storagePoolType\":\"RBD\",\"storagePath\":\"rbd\",\"format\":\"raw\"}}]}");
        DrRunVO run = run(DrConstants.RUN_TYPE_TEST_FAILOVER, "{}");
        Mockito.when(drRestorePointDao.findLatestTargetReadyByPlanId(plan.getId()))
                .thenReturn(checkpoint(plan, "ftctl:" + plan.getUuid() + ":run-sync:2"));
        Mockito.when(drRemoteAgentClient.isRemoteKvmSource(plan)).thenReturn(true);
        Mockito.when(drSourceHardwareInventoryService.resolve(plan)).thenReturn(sourceHardware());
        Mockito.when(drPlanOwnedTransportService.supports(plan)).thenReturn(true);
        mockCapabilities();
        Mockito.when(drRemoteAgentClient.transitionSourceScheduler(Mockito.eq(plan),
                Mockito.eq(FtctlDrActionCommand.Action.PAUSE_SYNC), Mockito.eq(run.getUuid()),
                Mockito.anyString())).thenAnswer(invocation -> {
                    FtctlDrActionCommand command = new FtctlDrActionCommand(
                            FtctlDrActionCommand.Action.PAUSE_SYNC, plan.getUuid(), run.getUuid());
                    return new FtctlDrActionAnswer(command, true, "paused");
                });
        ArgumentCaptor<FtctlDrActionCommand> actionCaptor = ArgumentCaptor.forClass(FtctlDrActionCommand.class);
        Mockito.when(agentManager.send(Mockito.eq(103L), actionCaptor.capture())).thenAnswer(invocation -> {
            FtctlDrActionCommand command = invocation.getArgument(1);
            return new FtctlDrActionAnswer(command, true, "accepted", FtctlDrActionCommand.Action.TEST_PREPARE,
                    plan.getUuid(), run.getUuid(), "accepted", true, "TESTING", "test-artifact-prepare-accepted",
                    70, run.getUuid(), 0L, null, 0, "{\"result\":\"accepted\"}",
                    "{\"state\":\"TESTING\"}");
        });

        DrAdapterResult result = adapter.execute(new DrExecutionContext(plan, run));

        Assert.assertTrue(result.isSuccess());
        FtctlDrActionCommand action = actionCaptor.getValue();
        Assert.assertTrue(action.getRequestJson().contains("\"checkpointWriterState\":\"DRAINED\""));
        Assert.assertTrue(action.getRequestJson().contains("\"checkpointImmutableRequired\":false"));
        Assert.assertTrue(action.getArtifactSpecJson().contains("\"canonicalLocator\":\"rbd:rbd/target-root\""));
        Assert.assertTrue(action.getArtifactSpecJson().contains("\"canonicalLocator\":\"rbd:rbd/target-data\""));
        org.mockito.InOrder order = Mockito.inOrder(drRemoteAgentClient, drPlanOwnedTransportService, agentManager);
        order.verify(drRemoteAgentClient).transitionSourceScheduler(Mockito.eq(plan),
                Mockito.eq(FtctlDrActionCommand.Action.PAUSE_SYNC), Mockito.eq(run.getUuid()), Mockito.anyString());
        order.verify(drPlanOwnedTransportService).stopForwardTargetExport(Mockito.eq(plan), Mockito.eq(run),
                Mockito.anyString(), Mockito.eq(2L));
        order.verify(agentManager).send(Mockito.eq(103L), Mockito.isA(FtctlDrActionCommand.class));
    }

    @Test
    public void testFailoverRejectsSharedMountPointTraversal() throws Exception {
        DrPlanVO plan = ftctlDrPlan();
        plan.setMappingJson("{\"target\":{\"storagePoolType\":\"SharedMountPoint\",\"storagePath\":\"/mnt/glue-gfs\"},"
                + "\"disks\":[{\"device\":\"sda\",\"target\":{\"path\":\"../outside\","
                + "\"storagePoolType\":\"SharedMountPoint\",\"storagePath\":\"/mnt/glue-gfs\","
                + "\"format\":\"qcow2\"}}]}");
        DrRunVO run = run(DrConstants.RUN_TYPE_TEST_FAILOVER,
                "{\"restorePointRef\":\"ftctl:" + plan.getUuid() + ":2\"}");
        Mockito.when(drRestorePointDao.findLatestTargetReadyByPlanId(plan.getId()))
                .thenReturn(checkpoint(plan, "ftctl:" + plan.getUuid() + ":run-sync:2"));
        mockCapabilities();
        DrAdapterResult result = adapter.execute(new DrExecutionContext(plan, run));

        Assert.assertFalse(result.isSuccess());
        Assert.assertEquals("DR_TEST_ARTIFACT_SPEC_INVALID", result.getErrorCode());
        Mockito.verify(agentManager, Mockito.never()).send(Mockito.eq(103L), Mockito.isA(FtctlDrActionCommand.class));
        Mockito.verify(agentManager, Mockito.never()).easySend(
                Mockito.eq(103L), Mockito.isA(FtctlDrActionCommand.class));
    }

    @Test
    public void testFailoverAcceptsSuccessfulAgentContractDespiteLegacySyntheticTimeoutCode() throws Exception {
        DrPlanVO plan = ftctlDrPlan();
        DrRunVO run = run(DrConstants.RUN_TYPE_TEST_FAILOVER,
                "{\"restorePointRef\":\"ftctl:" + plan.getUuid() + ":2\"}");
        DrRestorePointVO checkpoint = checkpoint(plan, "ftctl:" + plan.getUuid() + ":run-sync:2");
        Mockito.when(drRestorePointDao.findLatestTargetReadyByPlanId(plan.getId())).thenReturn(checkpoint);
        mockCapabilities();
        Mockito.when(agentManager.send(Mockito.eq(103L), Mockito.any(FtctlDrActionCommand.class))).thenAnswer(invocation -> {
            FtctlDrActionCommand command = invocation.getArgument(1);
            return new FtctlDrActionAnswer(command, true, "accepted", FtctlDrActionCommand.Action.TEST_PREPARE,
                    plan.getUuid(), run.getUuid(), "accepted", true, "TESTING", "test-artifact-prepare-accepted",
                    70, run.getUuid(), 0L, "DR_AGENT_ACCEPT_TIMEOUT", 0,
                    "{\"result\":\"accepted\",\"testBootTimeoutSeconds\":180}",
                    "{\"result\":\"accepted\",\"accepted\":true,\"state\":\"TESTING\","
                            + "\"boot_timeout_seconds\":180,\"error_code\":\"\"}");
        });

        DrAdapterResult result = adapter.execute(new DrExecutionContext(plan, run));

        Assert.assertTrue(result.isSuccess());
        Assert.assertFalse(result.isTerminal());
        Assert.assertEquals(run.getUuid(), result.getExternalJobRef());
    }

    @Test
    public void failoverDispatchesModeRestorePointAndFinalSyncToFtctlProfile() throws Exception {
        DrPlanVO plan = ftctlDrPlan();
        DrRunVO run = run(DrConstants.RUN_TYPE_FAILOVER,
                "{\"mode\":\"planned\",\"restorePointId\":12,\"restorePointRef\":\"ftctl:" + plan.getUuid() + ":3\",\"finalSync\":true}");
        ArgumentCaptor<FtctlDrActionCommand> commandCaptor = ArgumentCaptor.forClass(FtctlDrActionCommand.class);
        DrRestorePointVO checkpoint = checkpoint(plan, "ftctl:" + plan.getUuid() + ":run-sync:3");
        Mockito.when(drRestorePointDao.findLatestTargetReadyByPlanId(plan.getId())).thenReturn(checkpoint);
        mockCapabilities();
        Mockito.when(agentManager.send(Mockito.eq(103L), commandCaptor.capture())).thenAnswer(invocation -> {
            FtctlDrActionCommand command = invocation.getArgument(1);
            return new FtctlDrActionAnswer(command, true, "accepted", FtctlDrActionCommand.Action.FAILOVER,
                    plan.getUuid(), run.getUuid(), "accepted", true, "RUNNING", "failover-worker-started",
                    15, run.getUuid(), 0L, null, 0, "{\"result\":\"accepted\"}",
                    "{\"state\":\"RUNNING\",\"failover_restore_point_ref\":\"ftctl:" + plan.getUuid() + ":3\"}");
        });

        DrAdapterResult result = adapter.execute(new DrExecutionContext(plan, run));

        Assert.assertTrue(result.isSuccess());
        Assert.assertFalse(result.isTerminal());
        FtctlDrActionCommand command = commandCaptor.getValue();
        Assert.assertEquals(FtctlDrActionCommand.Action.FAILOVER, command.getAction());
        Assert.assertEquals("planned", command.getMode());
        Assert.assertNull(command.getRestorePointId());
        Assert.assertEquals(checkpoint.getSourceSnapshotRef(), command.getCheckpointRef());
        Assert.assertFalse(command.isWaitForCompletion());
        Assert.assertTrue(command.getProfileJson().contains("\"restorePointRef\":\"" + checkpoint.getSourceSnapshotRef() + "\""));
        Assert.assertTrue(command.getProfileJson().contains("\"finalSync\":true"));
        Assert.assertTrue(command.getRequestJson().contains("\"restorePointRef\":\"" + checkpoint.getSourceSnapshotRef() + "\""));
        Assert.assertTrue(command.getRequestJson().contains("\"finalSync\":true"));
    }

    @Test
    public void reprotectDispatchesImmutableTargetAuthorityContract() throws Exception {
        DrPlanVO plan = ftctlDrPlan();
        plan.setState(DrConstants.PLAN_STATE_FAILED_OVER);
        plan.setActiveSide("TARGET");
        DrRunVO run = run(DrConstants.RUN_TYPE_REPROTECT, "{}");
        DrReprotectAuthoritySpec spec = new DrReprotectAuthoritySpec();
        spec.setPlanUuid(plan.getUuid());
        spec.setRunUuid(run.getUuid());
        spec.setExpectedActiveSide("TARGET");
        spec.setAuthorityGeneration(3L);
        spec.setCutoverSessionId("cutover-1");
        spec.setCheckpointSequence(17L);
        spec.setTargetVmId(256L);
        spec.setTargetExternalRef("target-vm-uuid");
        spec.setTargetInstanceName("i-2-256-VM");
        spec.setTargetPowerState("POWERED_ON");
        spec.setTargetMaterialized(true);
        spec.setTargetPromotionState("PROMOTED");
        Mockito.when(drReprotectPreflightService.validate(plan, run))
                .thenReturn(DrReprotectPreflightResult.success(spec));
        ArgumentCaptor<FtctlDrActionCommand> commandCaptor = ArgumentCaptor.forClass(FtctlDrActionCommand.class);
        mockCapabilities();
        Mockito.when(agentManager.send(Mockito.eq(103L), commandCaptor.capture())).thenAnswer(invocation -> {
            FtctlDrActionCommand command = invocation.getArgument(1);
            return new FtctlDrActionAnswer(command, true, "accepted", FtctlDrActionCommand.Action.REPROTECT,
                    plan.getUuid(), run.getUuid(), "accepted", true, "REPROTECTING", "worker-started",
                    10, run.getUuid(), 0L, null, 0, "{\"result\":\"accepted\"}",
                    "{\"state\":\"REPROTECTING\"}");
        });

        DrAdapterResult result = adapter.execute(new DrExecutionContext(plan, run));

        Assert.assertTrue(result.isSuccess());
        FtctlDrActionCommand command = commandCaptor.getValue();
        Assert.assertEquals(FtctlDrActionCommand.Action.REPROTECT, command.getAction());
        Assert.assertEquals(DrReprotectAuthoritySpec.CONTRACT_VERSION, command.getAuthorityContractVersion());
        Assert.assertTrue(command.getAuthoritySpecJson().contains("\"expectedActiveSide\":\"TARGET\""));
        Assert.assertTrue(command.getAuthoritySpecJson().contains("\"authorityGeneration\":3"));
        Assert.assertTrue(command.getAuthoritySpecJson().contains("\"targetVmId\":256"));
    }

    @Test
    public void reprotectStopsBeforeDispatchWhenRuntimeDoesNotAdvertiseWriterContract() throws Exception {
        DrPlanVO plan = ftctlDrPlan();
        plan.setState(DrConstants.PLAN_STATE_FAILED_OVER);
        plan.setActiveSide("TARGET");
        DrRunVO run = run(DrConstants.RUN_TYPE_REPROTECT, "{}");
        Mockito.when(agentManager.send(Mockito.eq(103L), Mockito.isA(FtctlDrCapabilitiesCommand.class)))
                .thenAnswer(invocation -> {
                    FtctlDrCapabilitiesAnswer answer = new FtctlDrCapabilitiesAnswer(
                            invocation.getArgument(1), true, "ok");
                    answer.setSupportedFeatures(java.util.Arrays.asList(
                            "control-protocol-v2", "dr-transition-preflight-v2"));
                    answer.setReprotectAuthorityContractVersions(
                            java.util.Collections.singletonList("2026-07-23"));
                    return answer;
                });

        DrAdapterResult result = adapter.execute(new DrExecutionContext(plan, run));

        Assert.assertFalse(result.isSuccess());
        Assert.assertEquals(DrConstants.ERROR_AGENT_CAPABILITY_MISMATCH, result.getErrorCode());
        Assert.assertTrue(result.getMessage().contains("does not support"));
        Mockito.verify(agentManager, Mockito.never()).send(
                Mockito.eq(103L), Mockito.isA(FtctlDrActionCommand.class));
    }

    @Test
    public void failbackStopsBeforeAgentDispatchWhenSitePreflightFails() throws Exception {
        DrPlanVO plan = ftctlDrPlan();
        plan.setState(DrConstants.PLAN_STATE_FAILED_OVER);
        plan.setActiveSide("TARGET");
        DrRunVO run = run(DrConstants.RUN_TYPE_FAILBACK, "{\"force\":true}");
        Mockito.when(drFailbackPreflightService.validate(plan, run)).thenReturn(
                DrFailbackPreflightResult.failure(DrConstants.ERROR_FAILBACK_CREDENTIAL_NOT_READY,
                        "destination credential missing", null, null, "CONFIGURED", "MISSING", null));
        mockCapabilities();

        DrAdapterResult result = adapter.execute(new DrExecutionContext(plan, run));

        Assert.assertFalse(result.isSuccess());
        Assert.assertEquals(DrConstants.ERROR_FAILBACK_CREDENTIAL_NOT_READY, result.getErrorCode());
        Mockito.verify(agentManager, Mockito.never()).send(Mockito.eq(103L), Mockito.isA(FtctlDrActionCommand.class));
    }

    @Test
    public void unavailableAutomaticWorkerFailsBeforeAgentDispatch() throws Exception {
        DrPlanVO plan = ftctlDrPlan();
        plan.setSourceWorkerHostId(null);
        plan.setTargetWorkerHostId(null);
        plan.setCoordinatorWorkerHostId(null);
        DrRunVO run = run(DrConstants.RUN_TYPE_SYNC, "{\"mode\":\"FULL_RESEED\"}");
        Mockito.reset(drWorkerPlacementService);

        DrAdapterResult result = adapter.execute(new DrExecutionContext(plan, run));

        Assert.assertFalse(result.isSuccess());
        Assert.assertEquals(DrConstants.ERROR_TARGET_MAPPING_INVALID, result.getErrorCode());
        Mockito.verifyNoInteractions(agentManager);
    }

    private DrPlanVO ftctlDrPlan() {
        DrPlanVO plan = new DrPlanVO("ftctl-dr-plan", 1L, 2L, DrConstants.DIRECTION_VMWARE_TO_KVM);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setSourceVmId(101L);
        plan.setSourceExternalRef("vmware-vm-01");
        plan.setActiveSide("SOURCE");
        plan.setRpoSeconds(30);
        plan.setRtoSeconds(300);
        plan.setSourceWorkerHostId(101L);
        plan.setTargetWorkerHostId(102L);
        plan.setCoordinatorWorkerHostId(103L);
        plan.setPolicyJson("{\"compression\":true}");
        plan.setMappingJson("{\"diskPolicy\":\"same-order\",\"target\":{\"storagePoolType\":\"RBD\",\"storagePath\":\"rbd\"},"
                + "\"disks\":[{\"device\":\"sda\",\"capacityBytes\":\"107374182400\",\"target\":{"
                + "\"volumeId\":252,\"path\":\"Rocky10-1-dr-disk-0\",\"storagePoolType\":\"RBD\",\"storagePath\":\"rbd\",\"format\":\"raw\"}}]}");
        return plan;
    }

    private DrPlanVO crossSiteKvmPlan() {
        DrPlanVO plan = new DrPlanVO("cross-site-kvm-failover", 1L, 2L,
                DrConstants.DIRECTION_KVM_TO_KVM);
        plan.setEngineType(DrConstants.ENGINE_TYPE_FTCTL_DR);
        plan.setEngineBindingType(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR);
        plan.setSourceExternalRef("source-vm-uuid");
        plan.setActiveSide("SOURCE");
        plan.setTargetWorkerHostId(102L);
        plan.setCoordinatorWorkerHostId(103L);
        plan.setMappingJson("{\"source\":{\"hardware\":{\"sourceHostUuid\":\"source-host-uuid\","
                + "\"instanceName\":\"i-2-332-VM\"}},\"target\":{\"storagePoolType\":\"RBD\"},"
                + "\"disks\":[{\"device\":\"sda\",\"sourcePath\":\"rbd:rbd/source-image\","
                + "\"targetPath\":\"rbd:rbd/target-image\",\"targetStorageRef\":\"target-pool-uuid\","
                + "\"target\":{\"storageRef\":\"target-pool-uuid\",\"path\":\"target-image\","
                + "\"format\":\"raw\"}}]}");
        return plan;
    }

    private void mockRemoteKvmCapabilities(DrPlanVO plan) {
        Mockito.when(drRemoteAgentClient.execute(Mockito.eq(plan), Mockito.eq("CAPABILITIES"),
                Mockito.isA(FtctlDrCapabilitiesCommand.class), Mockito.isNull(),
                Mockito.eq(FtctlDrCapabilitiesAnswer.class))).thenAnswer(invocation -> {
                    FtctlDrCapabilitiesAnswer answer = new FtctlDrCapabilitiesAnswer(
                            invocation.getArgument(2), true, "ok");
                    answer.setSupportedFeatures(java.util.Arrays.asList(
                            "control-protocol-v2", "dr-site-agent-rbd-transport-v1",
                            "dr-file-planned-failover-runtime-quiesce-v2"));
                    return answer;
                });
    }

    private DrSourceVmHardware sourceHardware() {
        DrSourceVmHardware hardware = new DrSourceVmHardware();
        hardware.setSourceVmRef("source-vm-uuid");
        hardware.setSourceHostUuid("source-host-uuid");
        hardware.setInstanceName("i-2-51-VM");
        hardware.setFirmware("UEFI");
        hardware.setUefiMode("LEGACY");
        hardware.setSecureBootEnabled(Boolean.FALSE);
        Map<String, String> details = new HashMap<String, String>();
        details.put("UEFI", "LEGACY");
        details.put("tpmversion", "NONE");
        details.put("io.policy", "io_uring");
        details.put("iothreads", "true");
        hardware.setVmDetails(details);
        hardware.setInventorySource("MOLD_API");
        hardware.seal();
        return hardware;
    }

    private DrRunVO run(String runType, String requestJson) {
        DrRunVO run = new DrRunVO(0L, runType);
        run.setState(DrConstants.RUN_STATE_QUEUED);
        run.setRequestJson(requestJson);
        return run;
    }

    private DrRestorePointVO checkpoint(DrPlanVO plan, String ref) {
        DrRestorePointVO checkpoint = new DrRestorePointVO(plan.getId(), "FTCTL_DR_CHECKPOINT");
        checkpoint.setSourceSnapshotRef(ref);
        checkpoint.setState("READY");
        checkpoint.setCheckpointSequence(2L);
        checkpoint.setCheckpointCycleType("CBT_INCREMENTAL");
        checkpoint.setCycleToken(plan.getUuid() + ":2");
        checkpoint.setEffectiveMode("CBT_INCREMENTAL");
        checkpoint.setIncrementalVerified(Boolean.TRUE);
        return checkpoint;
    }

    private void mockCapabilities() throws Exception {
        Mockito.when(agentManager.send(Mockito.eq(103L), Mockito.isA(FtctlDrCapabilitiesCommand.class))).thenAnswer(invocation -> {
            FtctlDrCapabilitiesAnswer answer = new FtctlDrCapabilitiesAnswer(invocation.getArgument(1), true, "ok");
            answer.setReprotectAuthorityContractVersions(java.util.Collections.singletonList(
                    DrReprotectAuthoritySpec.CONTRACT_VERSION));
            answer.setSupportedFeatures(java.util.Arrays.asList("control-protocol-v2", "guest-preparation-v2",
                    "test-artifact-lifecycle-v2", "test-domain-lifecycle-v1", "file-checkpoint-invariance-v1", "cutover-ready-v1",
                    "cutover-manifest-v2", "cutover-preflight-v1", "dr-source-independent-test-v1"));
            return answer;
        });
    }

    @Test
    public void newerPauseBlocksRestoreBeforeExportPreparation() throws Exception {
        DrPlanVO plan=ftctlDrPlan(); DrRunVO run=run(DrConstants.RUN_TYPE_TEST_CLEANUP,"{}");
        Mockito.when(drPlanDao.findById(plan.getId())).thenReturn(plan);
        com.cloud.dr.dao.DrRunDao dao=Mockito.mock(com.cloud.dr.dao.DrRunDao.class);
        org.springframework.test.util.ReflectionTestUtils.setField(adapter,"cleanupRecoveryRunDao",dao);
        DrRunVO pause=run(DrConstants.RUN_TYPE_PAUSE_SYNC,"{}");
        org.springframework.test.util.ReflectionTestUtils.setField(pause,"id",run.getId()+1);
        Mockito.when(dao.findLatestByPlanId(plan.getId())).thenReturn(pause);
        Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,
                () -> adapter.restoreTestCheckpointProtection(plan,run));
        Mockito.verifyNoInteractions(drPlanOwnedTransportService);
    }

    @Test
    public void changedTargetAuthorityBlocksRestoreBeforeExportPreparation() {
        DrPlanVO plan=ftctlDrPlan(); DrRunVO run=run(DrConstants.RUN_TYPE_TEST_CLEANUP,"{}");
        Mockito.when(drPlanDao.findById(plan.getId())).thenReturn(plan);
        plan.setActiveSide("TARGET");
        Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,
                () -> adapter.restoreTestCheckpointProtection(plan,run));
        Mockito.verifyNoInteractions(drPlanOwnedTransportService);
    }

    @Test
    public void restoreCreatesExportBeforeResumingAndInjectsFreshEndpoints() throws Exception {
        DrPlanVO plan=ftctlDrPlan(); org.apache.commons.lang3.reflect.FieldUtils.writeField(plan,"direction",DrConstants.DIRECTION_KVM_TO_KVM,true);
        plan.setSourceExternalRef("source-vm-uuid"); plan.setSourceVmId(null);
        DrRunVO run=run(DrConstants.RUN_TYPE_TEST_CLEANUP,"{}");
        Mockito.when(drPlanDao.findById(plan.getId())).thenReturn(plan);
        Mockito.when(drRemoteAgentClient.isRemoteKvmSource(plan)).thenReturn(true);
        Mockito.when(drRemoteAgentClient.fetchSourceStatus(Mockito.eq(plan), Mockito.anyString(), Mockito.any()))
                .thenAnswer(invocation -> new com.cloud.agent.api.FtctlDrStatusAnswer(
                        new com.cloud.agent.api.FtctlDrStatusCommand(), true, "available"));
        Mockito.when(drPlanOwnedTransportService.supports(plan)).thenReturn(true);
        com.google.gson.JsonArray exports=new com.google.gson.JsonArray();
        com.google.gson.JsonObject endpoint=new com.google.gson.JsonObject(); endpoint.addProperty("host","fresh-worker"); exports.add(endpoint);
        Mockito.when(drPlanOwnedTransportService.restoreForwardTargetExport(Mockito.eq(plan),Mockito.eq(run),Mockito.anyString())).thenReturn(exports);
        Mockito.when(drRemoteAgentClient.transitionSourceScheduler(Mockito.eq(plan),Mockito.eq(FtctlDrActionCommand.Action.RESUME_SYNC),
                Mockito.eq(run.getUuid()),Mockito.contains("fresh-worker"))).thenAnswer(invocation ->
                new FtctlDrActionAnswer(new FtctlDrActionCommand(FtctlDrActionCommand.Action.RESUME_SYNC,plan.getUuid(),run.getUuid()),true,"resumed"));
        adapter.restoreTestCheckpointProtection(plan,run);
        org.mockito.InOrder order=Mockito.inOrder(drPlanOwnedTransportService,drRemoteAgentClient);
        order.verify(drPlanOwnedTransportService).restoreForwardTargetExport(Mockito.eq(plan),Mockito.eq(run),Mockito.anyString());
        order.verify(drRemoteAgentClient).transitionSourceScheduler(Mockito.eq(plan),Mockito.eq(FtctlDrActionCommand.Action.RESUME_SYNC),
                Mockito.eq(run.getUuid()),Mockito.contains("fresh-worker"));
    }

    @Test
    public void unavailableExportMustNotResumeSource() {
        DrPlanVO plan=ftctlDrPlan(); DrRunVO run=run(DrConstants.RUN_TYPE_TEST_CLEANUP,"{}");
        Mockito.when(drPlanDao.findById(plan.getId())).thenReturn(plan);
        Mockito.when(drRemoteAgentClient.fetchSourceStatus(Mockito.eq(plan), Mockito.anyString(), Mockito.any()))
                .thenAnswer(invocation -> new com.cloud.agent.api.FtctlDrStatusAnswer(
                        new com.cloud.agent.api.FtctlDrStatusCommand(), true, "available"));
        Mockito.when(drPlanOwnedTransportService.supports(plan)).thenReturn(true);
        Mockito.when(drPlanOwnedTransportService.restoreForwardTargetExport(Mockito.eq(plan),Mockito.eq(run),Mockito.anyString()))
                .thenThrow(new com.cloud.utils.exception.CloudRuntimeException("export unavailable"));
        try { adapter.restoreTestCheckpointProtection(plan,run); Assert.fail("Export failure must propagate"); }
        catch(com.cloud.utils.exception.CloudRuntimeException expected) { Assert.assertEquals("export unavailable",expected.getMessage()); }
        Mockito.verify(drRemoteAgentClient,Mockito.never()).transitionSourceScheduler(Mockito.any(),Mockito.any(),Mockito.anyString(),Mockito.anyString());
    }
    @Test
    public void sourceUnavailableRestoreDoesNotRotateTargetExports() {
        DrPlanVO plan = ftctlDrPlan();
        Mockito.when(drPlanOwnedTransportService.supports(plan)).thenReturn(true);
        DrRunVO run = run(DrConstants.RUN_TYPE_TEST_CLEANUP, "{}");
        Mockito.when(drPlanDao.findById(plan.getId())).thenReturn(plan);
        try {
            adapter.restoreTestCheckpointProtection(plan, run);
            Assert.fail("Unavailable source must remain pending");
        } catch (com.cloud.utils.exception.CloudRuntimeException expected) {
            Assert.assertTrue(expected.getMessage().contains("remains pending"));
        }
        Mockito.verify(drPlanOwnedTransportService, Mockito.never()).restoreForwardTargetExport(Mockito.any(), Mockito.any(), Mockito.anyString());
    }

}
