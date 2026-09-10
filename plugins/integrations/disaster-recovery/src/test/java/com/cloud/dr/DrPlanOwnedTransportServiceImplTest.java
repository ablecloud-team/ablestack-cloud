// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information.
package com.cloud.dr;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.FtctlDrActionAnswer;
import com.cloud.agent.api.FtctlDrActionCommand;
import com.cloud.dr.adapter.ftctl.DrRemoteAgentClient;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.google.gson.JsonArray;

@RunWith(MockitoJUnitRunner.class)
public class DrPlanOwnedTransportServiceImplTest {
    @Mock private AgentManager agentManager;
    @Mock private DrExportOwnershipStore exportOwnershipStore;
    @Mock private HostDao hostDao;
    @Mock private DrRemoteAgentClient drRemoteAgentClient;
    @Mock private DrWorkerPlacementService drWorkerPlacementService;

    @InjectMocks
    private DrPlanOwnedTransportServiceImpl service;

    private DrPlanVO plan;
    private DrRunVO run;
    private HostVO targetHost;

    @Before
    public void setUp() {
        Mockito.lenient().when(exportOwnershipStore.nextGeneration(Mockito.anyLong())).thenReturn(2L);
        Mockito.lenient().when(exportOwnershipStore.rememberHosts(Mockito.anyLong(), Mockito.anySet()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        Mockito.lenient().when(hostDao.listAllHostsByZoneAndHypervisorType(Mockito.anyLong(), Mockito.any()))
                .thenAnswer(invocation -> java.util.Collections.singletonList(targetHost));
        plan = new DrPlanVO("rbd-plan", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        plan.setSourceExternalRef("remote-source-vm");
        plan.setTargetWorkerHostId(22L);
        run = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_FAILBACK);
        targetHost = Mockito.mock(HostVO.class);
        Mockito.when(targetHost.getId()).thenReturn(22L);
        Mockito.when(targetHost.getUuid()).thenReturn("target-worker-uuid");
        Mockito.when(hostDao.findById(22L)).thenReturn(targetHost);
        Mockito.when(drRemoteAgentClient.isRemoteKvmSource(plan)).thenReturn(true);
        Mockito.lenient().when(drWorkerPlacementService.resolveWorkerHostId(
                Mockito.any(DrPlanVO.class), Mockito.eq(DrWorkerRole.TARGET))).thenReturn(22L);
    }

    @Test
    public void forwardExportUsesTargetWorkerAndReturnsEndpoints() {
        FtctlDrActionAnswer answer = answer("{\"result\":\"ok\",\"exports\":[{\"device\":\"sda\",\"port\":11833}]}");
        FtctlDrActionAnswer stopped = answer("{\"result\":\"ok\"}");
        Mockito.when(agentManager.easySend(Mockito.eq(22L), Mockito.any(FtctlDrActionCommand.class)))
                .thenReturn(stopped, answer);

        JsonArray exports = service.startForwardTargetExport(plan, run, null);

        Assert.assertEquals(1, exports.size());
        ArgumentCaptor<FtctlDrActionCommand> command = ArgumentCaptor.forClass(FtctlDrActionCommand.class);
        Mockito.verify(agentManager, Mockito.times(2)).easySend(Mockito.eq(22L), command.capture());
        Assert.assertEquals(FtctlDrActionCommand.Action.TARGET_EXPORT_START, command.getValue().getAction());
        Assert.assertEquals("target", command.getValue().getRole());
        Assert.assertEquals("target-worker-uuid", command.getValue().getTargetWorkerUuid());
    }

    @Test
    public void reverseExportUsesOriginalSiteWorkerAndAuxiliaryRole() {
        FtctlDrActionAnswer answer = answer(
                "{\"result\":\"ok\",\"exports\":[{\"device\":\"sda\",\"port\":11834}]}");
        Mockito.when(drRemoteAgentClient.execute(Mockito.eq(plan), Mockito.eq("ACTION"),
                Mockito.any(FtctlDrActionCommand.class), Mockito.isNull(),
                Mockito.eq(FtctlDrActionAnswer.class)))
                .thenReturn(answer);

        JsonArray exports = service.startReverseTargetExport(plan, run, "{\"request\":{}}");

        Assert.assertEquals(1, exports.size());
        Mockito.verify(drRemoteAgentClient).execute(Mockito.eq(plan), Mockito.eq("ACTION"),
                Mockito.argThat((FtctlDrActionCommand command) -> "reverse-target".equals(command.getRole())
                        && command.getProfileJson().contains("reverseTargetExport")),
                Mockito.isNull(), Mockito.eq(FtctlDrActionAnswer.class));
    }

    @Test
    public void reverseExportRejectsPartialMultiDiskContract() {
        String profileJson = "{\"request\":{},\"mapping\":{\"disks\":["
                + "{\"device\":\"disk-0\"},{\"device\":\"disk-1\"}]}}";
        FtctlDrActionAnswer answer = answer(
                "{\"result\":\"ok\",\"exports\":[{\"device\":\"disk-1\",\"port\":11834}]}");
        Mockito.when(drRemoteAgentClient.execute(Mockito.eq(plan), Mockito.eq("ACTION"),
                Mockito.any(FtctlDrActionCommand.class), Mockito.isNull(),
                Mockito.eq(FtctlDrActionAnswer.class)))
                .thenReturn(answer);

        try {
            service.startReverseTargetExport(plan, run, profileJson);
            Assert.fail("A partial multi-disk export contract must be rejected");
        } catch (com.cloud.utils.exception.CloudRuntimeException expected) {
            Assert.assertTrue(expected.getMessage().contains("incomplete export set"));
        }
    }

    @Test
    public void testFailoverDrainDoesNotRequestReverseCutoverBaseline() {
        run = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_TEST_FAILOVER);
        FtctlDrActionAnswer actionAnswer = answer("{\"result\":\"ok\"}");
        Mockito.when(agentManager.easySend(Mockito.eq(22L), Mockito.any(FtctlDrActionCommand.class)))
                .thenReturn(actionAnswer);

        service.stopForwardTargetExport(plan, run, "{\"request\":{\"actionIntent\":\"TEST_FAILOVER\"}}", 253L);

        ArgumentCaptor<FtctlDrActionCommand> command = ArgumentCaptor.forClass(FtctlDrActionCommand.class);
        Mockito.verify(agentManager).easySend(Mockito.eq(22L), command.capture());
        Assert.assertNull(command.getValue().getCutoverCheckpointSequence());
    }

    @Test
    public void failoverDrainPreservesReverseCutoverBaselineSequence() {
        run = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_FAILOVER);
        FtctlDrActionAnswer actionAnswer = answer("{\"result\":\"ok\"}");
        Mockito.when(agentManager.easySend(Mockito.eq(22L), Mockito.any(FtctlDrActionCommand.class)))
                .thenReturn(actionAnswer);

        service.stopForwardTargetExport(plan, run, null, 253L);

        ArgumentCaptor<FtctlDrActionCommand> command = ArgumentCaptor.forClass(FtctlDrActionCommand.class);
        Mockito.verify(agentManager, Mockito.times(2)).easySend(Mockito.eq(22L), command.capture());
        Assert.assertEquals(Long.valueOf(253L), command.getValue().getCutoverCheckpointSequence());
    }

    @Test
    public void unsupportedRouteDoesNotDispatchTransport() {
        plan = new DrPlanVO("vmware-plan", 1L, 2L, DrConstants.DIRECTION_VMWARE_TO_KVM);

        Assert.assertEquals(0, service.startForwardTargetExport(plan, run, null).size());
        Mockito.verifyNoInteractions(agentManager);
    }

    @Test
    public void unavailableHistoricalWorkerPreventsNewWriter() {
        Mockito.when(exportOwnershipStore.rememberHosts(Mockito.anyLong(), Mockito.anySet()))
                .thenReturn(new java.util.HashSet<>(java.util.Arrays.asList(21L, 22L)));
        try {
            service.startForwardTargetExport(plan, run, null);
            Assert.fail("Missing historical worker must require fencing");
        } catch (com.cloud.utils.exception.CloudRuntimeException expected) {
            Assert.assertTrue(expected.getMessage().contains("DR_EXPORT_OWNERSHIP_PENDING"));
        }
        Mockito.verifyNoInteractions(agentManager);
    }

    @Test
    public void oldEngineOkIsNotRevocationProof() {
        FtctlDrActionAnswer old = Mockito.mock(FtctlDrActionAnswer.class);
        Mockito.when(old.getResult()).thenReturn(true);
        Mockito.when(old.getStatusJson()).thenReturn("{\"result\":\"ok\"}");
        Mockito.when(agentManager.easySend(Mockito.eq(22L), Mockito.any())).thenReturn(old);
        try {
            service.startForwardTargetExport(plan, run, null);
            Assert.fail("Protocol-less response must block grant");
        } catch (com.cloud.utils.exception.CloudRuntimeException expected) {
            Assert.assertTrue(expected.getMessage().contains("DR_EXPORT_OWNERSHIP_PENDING"));
        }
        Mockito.verify(agentManager).easySend(Mockito.eq(22L), Mockito.argThat(
                (FtctlDrActionCommand command) -> command.getAction() == FtctlDrActionCommand.Action.TARGET_EXPORT_STOP));
    }

    @Test
    public void everyHistoricalWriterStopsBeforeSelectedWriterStarts() {
        HostVO old = Mockito.mock(HostVO.class);
        Mockito.when(old.getId()).thenReturn(21L);
        Mockito.when(old.getUuid()).thenReturn("old-worker");
        Mockito.when(hostDao.findById(21L)).thenReturn(old);
        Mockito.when(hostDao.listAllHostsByZoneAndHypervisorType(Mockito.anyLong(), Mockito.any()))
                .thenReturn(java.util.Arrays.asList(old, targetHost));
        FtctlDrActionAnswer stopped = answer("{\"result\":\"ok\"}");
        FtctlDrActionAnswer started = answer("{\"result\":\"ok\",\"exports\":[{\"device\":\"sda\"}]}");
        Mockito.when(agentManager.easySend(Mockito.eq(21L), Mockito.any())).thenReturn(stopped);
        Mockito.when(agentManager.easySend(Mockito.eq(22L), Mockito.any())).thenReturn(stopped, started);
        service.startForwardTargetExport(plan, run, null);
        org.mockito.InOrder order = Mockito.inOrder(agentManager);
        order.verify(agentManager).easySend(Mockito.eq(21L), Mockito.argThat(
                (FtctlDrActionCommand command) -> command.getAction() == FtctlDrActionCommand.Action.TARGET_EXPORT_STOP));
        order.verify(agentManager).easySend(Mockito.eq(22L), Mockito.argThat(
                (FtctlDrActionCommand command) -> command.getAction() == FtctlDrActionCommand.Action.TARGET_EXPORT_STOP));
        order.verify(agentManager).easySend(Mockito.eq(22L), Mockito.argThat(
                (FtctlDrActionCommand command) -> command.getAction() == FtctlDrActionCommand.Action.TARGET_EXPORT_START
                        && command.getProfileJson().contains("\"exportGeneration\":3")));
    }

    private FtctlDrActionAnswer answer(String statusJson) {
        FtctlDrActionAnswer answer = Mockito.mock(FtctlDrActionAnswer.class);
        Mockito.when(answer.getResult()).thenReturn(true);
        com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(statusJson).getAsJsonObject();
        json.addProperty("ownershipProtocol", 1);
        json.addProperty("exportGeneration", json.has("exports") ? 3 : 2);
        Mockito.lenient().when(answer.getStatusJson()).thenReturn(json.toString());
        return answer;
    }
}
