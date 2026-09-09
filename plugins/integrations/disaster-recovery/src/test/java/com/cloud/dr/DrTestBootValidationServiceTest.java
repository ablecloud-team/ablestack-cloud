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
import org.junit.Test;
import org.junit.Before;
import org.junit.Assert;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.contains;
import org.apache.commons.lang3.reflect.FieldUtils;
import com.cloud.dr.dao.DrTestSessionDao;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.agent.AgentManager;
import com.cloud.agent.api.CheckVmGuestAgentCommand;
import com.cloud.agent.api.CheckVmGuestAgentAnswer;

public class DrTestBootValidationServiceTest {
    private DrTestBootValidationService service;
    private DrTestBootValidationStore store;
    private DrTestSessionDao sessions;
    private UserVmDao vms;
    private AgentManager agent;
    private DrTestSessionVO session;
    private UserVmVO vm;
    @Before public void setup() throws Exception {
        service = new DrTestBootValidationService();
        store = mock(DrTestBootValidationStore.class); sessions = mock(DrTestSessionDao.class);
        vms = mock(UserVmDao.class); agent = mock(AgentManager.class);
        FieldUtils.writeField(service,"store",store,true); FieldUtils.writeField(service,"sessionDao",sessions,true);
        FieldUtils.writeField(service,"vmDao",vms,true); FieldUtils.writeField(service,"agentManager",agent,true);
        session = new DrTestSessionVO(1,2,"CLOUD_VM_VALIDATING"); session.setTargetVmId(3L); session.setValidationMode("QGA_REQUIRED");
        vm=mock(UserVmVO.class); when(vm.getId()).thenReturn(3L); when(vm.getHostId()).thenReturn(4L);
        when(vm.getUuid()).thenReturn("vm-uuid"); when(vm.getInstanceName()).thenReturn("i-2-3-VM");
        when(vm.getState()).thenReturn(VirtualMachine.State.Running);
        when(store.claim(eq(1L),anyString())).thenReturn(1); when(store.remaining(1L)).thenReturn(30);
        when(sessions.findById(1L)).thenReturn(session); when(vms.findById(3L)).thenReturn(vm);
    }
    @Test public void powerStateCannotSatisfyQga() {
        session.setBootValidationState("POWER_STATE_VALIDATED"); Assert.assertFalse(service.satisfied(session));
    }
    @Test public void qgaRequiresDurableEvidence() {
        session.setBootValidationState("QGA_VALIDATED"); when(store.state(0L)).thenReturn("MISSING");
        Assert.assertFalse(service.satisfied(session)); when(store.state(0L)).thenReturn("QGA_VALIDATED");
        Assert.assertTrue(service.satisfied(session));
    }
    @Test public void powerOnlyDoesNotProbe() {
        session.setValidationMode("POWER_STATE_ONLY"); session.setBootValidationState("POWER_STATE_VALIDATED");
        Assert.assertTrue(service.satisfied(session)); verifyNoInteractions(agent,store);
    }
    @Test public void liveResponseValidates() {
        when(agent.easySend(eq(4L),any())).thenAnswer(i -> new CheckVmGuestAgentAnswer(i.getArgument(1),"RESPONDED",null));
        service.probeOnce(1L); verify(store).finish(eq(1L),anyString(),eq("QGA_VALIDATED"),contains("vm-uuid"));
    }
    @Test public void runningWithoutQgaRetries() {
        service.probeOnce(1L); verify(store).finish(eq(1L),anyString(),eq("PENDING"),anyString());
    }
    @Test public void expiredNeverProbes() {
        when(store.remaining(1L)).thenReturn(0); service.probeOnce(1L);
        verifyNoInteractions(agent); verify(store).finish(eq(1L),anyString(),eq("QGA_TIMEOUT"),anyString());
    }
    @Test public void leaseRejectsDuplicate() {
        when(store.claim(eq(1L),anyString())).thenReturn(0); service.probeOnce(1L); verifyNoInteractions(agent);
    }
    @Test public void changedHostDiscardsResponse() {
        UserVmVO moved=mock(UserVmVO.class); when(moved.getState()).thenReturn(VirtualMachine.State.Running);
        when(moved.getHostId()).thenReturn(5L); when(vms.findById(3L)).thenReturn(vm,moved);
        when(agent.easySend(eq(4L),any())).thenAnswer(i -> new CheckVmGuestAgentAnswer(i.getArgument(1),"RESPONDED",null));
        service.probeOnce(1L); verify(store).finish(eq(1L),anyString(),eq("PENDING"),anyString());
    }
    @Test public void wrongTokenDiscardsResponse() {
        when(agent.easySend(eq(4L),any())).thenReturn(new CheckVmGuestAgentAnswer(
            new CheckVmGuestAgentCommand("vm-uuid","i-2-3-VM","wrong",5),"RESPONDED",null));
        service.probeOnce(1L); verify(store).finish(eq(1L),anyString(),eq("PENDING"),anyString());
    }
    @Test public void stoppedVmFails() {
        when(vm.getState()).thenReturn(VirtualMachine.State.Stopped); service.probeOnce(1L);
        verifyNoInteractions(agent); verify(store).finish(eq(1L),anyString(),eq("QGA_FAILED"),anyString());
    }
    @Test public void completionResumesWithoutRuntimeProjection() throws Exception {
        DrTargetMaterializationService target = mock(DrTargetMaterializationService.class);
        javax.inject.Provider<DrTargetMaterializationService> provider = () -> target;
        FieldUtils.writeField(service, "materializer", provider, true);
        service.wakeCompletion(1L);
        verify(target).enqueueTestMaterialization(eq(1L), eq(2L), org.mockito.ArgumentMatchers.isNull());
        verifyNoInteractions(agent);
    }
    @Test public void cleanupSuppressesCompletionCallback() throws Exception {
        DrTargetMaterializationService target = mock(DrTargetMaterializationService.class);
        javax.inject.Provider<DrTargetMaterializationService> provider = () -> target;
        FieldUtils.writeField(service, "materializer", provider, true);
        session.setCleanupRunId(9L);
        service.wakeCompletion(1L);
        verifyNoInteractions(target, agent);
    }
}
