// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information.
package com.cloud.ftctl;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Before;
import org.junit.Test;
import org.junit.Assert;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import com.cloud.agent.AgentManager;
import com.cloud.agent.api.FtctlDrActionAnswer;
import com.cloud.agent.api.FtctlDrActionCommand;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

@RunWith(MockitoJUnitRunner.class)
public class FtctlDrReverseExportCoordinatorTest {
    @Mock AgentManager agentManager;
    @Mock HostDao hostDao;
    @Mock UserVmDao userVmDao;
    @Mock FtctlDrReverseExportStore store;
    @InjectMocks FtctlDrReverseExportCoordinator coordinator;
    FtctlDrReverseExportStore.Journal saved;
    HostVO first, second;
    UserVmVO vm;
    Gson gson = new Gson();
    @Before public void setup() throws Exception {
        vm = Mockito.mock(UserVmVO.class);
        Mockito.lenient().when(vm.getState()).thenReturn(VirtualMachine.State.Stopped);
        Mockito.lenient().when(vm.getDataCenterId()).thenReturn(1L);
        Mockito.lenient().when(userVmDao.findByUuid("vm")).thenReturn(vm);
        first = host(1); second = host(2);
        Mockito.lenient().when(hostDao.listAllHostsByZoneAndHypervisorType(Mockito.anyLong(), Mockito.any())).thenReturn(Arrays.asList(first, second));
        Mockito.lenient().when(hostDao.listAllHostsUpByZoneAndHypervisor(Mockito.anyLong(), Mockito.any())).thenReturn(Arrays.asList(first, second));
        Mockito.lenient().when(store.load("plan")).thenAnswer(i -> saved == null ? null : gson.fromJson(gson.toJson(saved), FtctlDrReverseExportStore.Journal.class));
        Mockito.lenient().doAnswer(i -> { saved=gson.fromJson(gson.toJson((Object)i.getArgument(1)), FtctlDrReverseExportStore.Journal.class); return null; }).when(store).save(Mockito.eq("plan"), Mockito.any());
        Mockito.lenient().when(agentManager.send(Mockito.anyLong(), Mockito.any(FtctlDrActionCommand.class))).thenAnswer(i -> ack(i.getArgument(1)));
    }
    HostVO host(long id) {
        HostVO h=Mockito.mock(HostVO.class);
        Mockito.lenient().when(h.getId()).thenReturn(id);
        Mockito.lenient().when(h.getUuid()).thenReturn("host"+id);
        Mockito.lenient().when(h.getStatus()).thenReturn(Status.Up);
        Mockito.lenient().when(h.getPrivateIpAddress()).thenReturn("10.0.0."+id);
        Mockito.lenient().when(hostDao.findById(id)).thenReturn(h);return h;
    }
    FtctlDrActionCommand command(long generation, boolean start) {
        FtctlDrActionCommand c=new FtctlDrActionCommand(start ? FtctlDrActionCommand.Action.TARGET_EXPORT_START : FtctlDrActionCommand.Action.TARGET_EXPORT_STOP,"plan","run"+generation);
        c.setRole("reverse-target"); c.setContextParam("sourceVmUuid","vm");
        c.setProfileJson("{\"request\":{\"exportAuthorityScope\":\"plan\",\"exportDirection\":\"REVERSE\",\"exportGeneration\":"+generation+"}}");return c;
    }
    FtctlDrActionAnswer ack(FtctlDrActionCommand c) {
        JsonObject status=gson.fromJson(c.getProfileJson(),JsonObject.class).getAsJsonObject("request").deepCopy();
        status.addProperty("ownershipProtocol",2);
        status.addProperty("state", c.getAction()==FtctlDrActionCommand.Action.TARGET_EXPORT_STOP ? "STOPPED" : "READY");
        return new FtctlDrActionAnswer(c,true,null,c.getAction(),"plan",c.getRunUuid(),"ok",true,"READY",null,100,null,null,null,0,null,status.toString());
    }
    @Test public void recordsGrantBeforeSendingAndRevokesLegacyBeforeStart() throws Exception {
        Mockito.when(agentManager.send(Mockito.anyLong(),Mockito.any(FtctlDrActionCommand.class))).thenAnswer(i -> {
            FtctlDrActionCommand c=i.getArgument(1);
            if(c.getAction()==FtctlDrActionCommand.Action.TARGET_EXPORT_START) {
                Assert.assertEquals("GRANTED",saved.hosts.get(1L)); Assert.assertEquals("REVOKED",saved.hosts.get(2L));
            }
            return ack(c);
        });
        coordinator.executeLocked(command(3,true)); Assert.assertEquals(Long.valueOf(1),saved.selectedHost);
    }
    @Test public void revokedNeverOwnerDownDoesNotBlockLaterGrant() throws Exception {
        coordinator.executeLocked(command(3,true)); Mockito.clearInvocations(agentManager);
        Mockito.lenient().when(hostDao.findById(2L)).thenReturn(null);
        coordinator.executeLocked(command(5,true));
        Mockito.verify(agentManager,Mockito.never()).send(Mockito.eq(2L),Mockito.any(FtctlDrActionCommand.class));
    }
    @Test public void uncertainGrantRetriesSameWorker() throws Exception {
        Mockito.when(agentManager.send(Mockito.eq(1L),Mockito.any(FtctlDrActionCommand.class))).thenAnswer(i -> {
            FtctlDrActionCommand c=i.getArgument(1);
            return c.getAction()==FtctlDrActionCommand.Action.TARGET_EXPORT_START ? null : ack(c);
        });
        Assert.assertThrows(CloudRuntimeException.class,()->coordinator.executeLocked(command(3,true)));
        Assert.assertEquals("GRANTED",saved.hosts.get(1L));
        Mockito.lenient().when(hostDao.listAllHostsUpByZoneAndHypervisor(Mockito.anyLong(),Mockito.any())).thenReturn(Collections.singletonList(second));
        Mockito.when(agentManager.send(Mockito.eq(1L),Mockito.any(FtctlDrActionCommand.class))).thenAnswer(i->ack(i.getArgument(1)));
        coordinator.executeLocked(command(3,true)); Assert.assertEquals(Long.valueOf(1),saved.selectedHost);
    }
    @Test public void oldGrantDownBlocksNewStartUntilReturn() throws Exception {
        coordinator.executeLocked(command(3,true));
        Mockito.when(agentManager.send(Mockito.eq(1L),Mockito.any(FtctlDrActionCommand.class))).thenReturn(null);
        Assert.assertThrows(CloudRuntimeException.class,()->coordinator.executeLocked(command(5,true)));
        Assert.assertEquals("GRANTED",saved.hosts.get(1L));
        Mockito.when(agentManager.send(Mockito.eq(1L),Mockito.any(FtctlDrActionCommand.class))).thenAnswer(i->ack(i.getArgument(1)));
        Mockito.lenient().when(hostDao.listAllHostsUpByZoneAndHypervisor(Mockito.anyLong(),Mockito.any())).thenReturn(Collections.singletonList(second));
        coordinator.executeLocked(command(5,true)); Assert.assertEquals(Long.valueOf(2),saved.selectedHost);
    }
    @Test public void stopDoesNotRequireNewWorker() {
        coordinator.executeLocked(command(3,true)); Mockito.clearInvocations(hostDao);
        coordinator.executeLocked(command(4,false));
        Mockito.verify(hostDao,Mockito.never()).listAllHostsUpByZoneAndHypervisor(Mockito.anyLong(),Mockito.any());
        Assert.assertTrue(saved.hosts.values().stream().allMatch("REVOKED"::equals));
    }
    @Test public void staleRequestRejectedBeforeAgent() {
        coordinator.executeLocked(command(5,true)); Mockito.clearInvocations(agentManager);
        Assert.assertThrows(CloudRuntimeException.class,()->coordinator.executeLocked(command(4,false))); Mockito.verifyNoInteractions(agentManager);
    }
    @Test public void conflictingRunRejected() {
        coordinator.executeLocked(command(3,true)); FtctlDrActionCommand c=gson.fromJson(gson.toJson(command(3,true)).replace("run3", "other"), FtctlDrActionCommand.class);
        Assert.assertThrows(CloudRuntimeException.class,()->coordinator.executeLocked(c));
    }
    @Test public void runningOriginalVmNeverOpened() {
        Mockito.when(vm.getState()).thenReturn(VirtualMachine.State.Running);
        Assert.assertThrows(CloudRuntimeException.class,()->coordinator.executeLocked(command(3,true))); Mockito.verifyNoInteractions(agentManager);
    }
    @Test public void scopeMismatchNeverDispatched() {
        FtctlDrActionCommand c=command(3,true); c.setProfileJson(c.getProfileJson().replace("\"plan\"","\"other\""));
        Assert.assertThrows(CloudRuntimeException.class,()->coordinator.executeLocked(c)); Mockito.verifyNoInteractions(agentManager);
    }
    @Test public void legacyUnreachableHostMustBeReconciledOnce() throws Exception {
        Mockito.when(agentManager.send(Mockito.eq(2L),Mockito.any(FtctlDrActionCommand.class))).thenReturn(null);
        Assert.assertThrows(CloudRuntimeException.class,()->coordinator.executeLocked(command(3,true)));
        Assert.assertEquals("REVOKED",saved.hosts.get(1L)); Assert.assertEquals("UNKNOWN",saved.hosts.get(2L));
    }
}