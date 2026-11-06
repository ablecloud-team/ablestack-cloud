// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.vm;

import java.util.Map;
import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.CheckVmPcieTopologyCommand;
import com.cloud.agent.api.StartAnswer;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.vm.dao.VMInstanceDetailsDao;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class VirtualMachineManagerQ35TopologyTest {
    private final VirtualMachineManagerImpl manager = new VirtualMachineManagerImpl();
    private final VMInstanceDetailsDao details = Mockito.mock(VMInstanceDetailsDao.class);
    private final AgentManager agents = Mockito.mock(AgentManager.class);
    private final VirtualMachine vm = Mockito.mock(VirtualMachine.class);

    @Before public void setup() {
        ReflectionTestUtils.setField(manager, "vmInstanceDetailsDao", details);
        ReflectionTestUtils.setField(manager, "_agentMgr", agents);
        when(vm.getId()).thenReturn(218L);
        when(vm.getType()).thenReturn(VirtualMachine.Type.User);
        when(vm.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(vm.getState()).thenReturn(VirtualMachine.State.Stopped);
        when(vm.getInstanceName()).thenReturn("i-2-test-VM");
        when(vm.getUuid()).thenReturn("vm-uuid");
        when(vm.getLastHostId()).thenReturn(14L);
    }

    @Test public void priorHostSuppliesThePolicyBeforeAStartOnAnotherHost() throws Exception {
        Answer reply = Mockito.mock(Answer.class);
        when(reply.getResult()).thenReturn(true);
        when(reply.getDetails()).thenReturn("reserved32");
        when(agents.send(eq(14L), any(CheckVmPcieTopologyCommand.class))).thenReturn(reply);
        manager.preserveKvmPcieTopology(vm);
        ArgumentCaptor<CheckVmPcieTopologyCommand> query = ArgumentCaptor.forClass(CheckVmPcieTopologyCommand.class);
        verify(agents).send(eq(14L), query.capture());
        assertEquals("vm-uuid", query.getValue().getVmUuid());
        assertEquals("i-2-test-VM", query.getValue().getVmName());
        verify(details).addDetail(218L, VmDetailConstants.KVM_Q35_PCIE_TOPOLOGY, "reserved32", false);
    }
    @Test public void anExistingPolicyDoesNotDependOnLocalHistory() throws Exception {
        when(details.findDetail(218L, VmDetailConstants.KVM_Q35_PCIE_TOPOLOGY)).thenReturn(Mockito.mock(VMInstanceDetailVO.class));
        manager.preserveKvmPcieTopology(vm);
        verify(agents, never()).send(eq(14L), any(CheckVmPcieTopologyCommand.class));
    }
    @Test public void unknownHistoryDoesNotPersistAGuessedLegacyPolicy() throws Exception {
        manager.preserveKvmPcieTopology(vm);
        verify(details, never()).addDetail(eq(218L), eq(VmDetailConstants.KVM_Q35_PCIE_TOPOLOGY), any(), eq(false));
    }
    @Test public void runningVmCanBeCapturedBeforeBackupWithoutAStopOrRestart() throws Exception {
        when(vm.getState()).thenReturn(VirtualMachine.State.Running);
        when(vm.getHostId()).thenReturn(20L);
        Answer reply = Mockito.mock(Answer.class);
        when(reply.getResult()).thenReturn(true); when(reply.getDetails()).thenReturn("reserved32");
        when(agents.send(eq(20L), any(CheckVmPcieTopologyCommand.class))).thenReturn(reply);
        manager.preserveKvmPcieTopology(vm);
        verify(agents).send(eq(20L), any(CheckVmPcieTopologyCommand.class));
        verify(details).addDetail(218L, VmDetailConstants.KVM_Q35_PCIE_TOPOLOGY, "reserved32", false);
    }
    @Test public void successfulDestinationDiscoveryIsSavedForFutureHosts() {
        StartAnswer answer = answer("vm-uuid", "legacy", true);
        manager.saveKvmPcieTopology(vm, answer);
        verify(details).addDetail(218L, VmDetailConstants.KVM_Q35_PCIE_TOPOLOGY, "legacy", false);
    }
    @Test public void failedOrMismatchedStartDoesNotChangeTheVmPolicy() {
        manager.saveKvmPcieTopology(vm, answer("vm-uuid", "reserved32", false));
        manager.saveKvmPcieTopology(vm, answer("another-vm", "reserved32", true));
        verify(details, never()).addDetail(eq(218L), eq(VmDetailConstants.KVM_Q35_PCIE_TOPOLOGY), any(), eq(false));
    }
    @Test public void otherHypervisorsDoNotQueryKvmHistory() throws Exception {
        when(vm.getHypervisorType()).thenReturn(HypervisorType.VMware);
        manager.preserveKvmPcieTopology(vm);
        verify(agents, never()).send(eq(14L), any(CheckVmPcieTopologyCommand.class));
    }

    private StartAnswer answer(String uuid, String policy, boolean success) {
        StartAnswer answer = Mockito.mock(StartAnswer.class);
        VirtualMachineTO returned = Mockito.mock(VirtualMachineTO.class);
        when(answer.getResult()).thenReturn(success);
        when(answer.getVirtualMachine()).thenReturn(returned);
        when(returned.getUuid()).thenReturn(uuid);
        when(returned.getDetails()).thenReturn(Map.of(VmDetailConstants.KVM_Q35_PCIE_TOPOLOGY, policy));
        return answer;
    }
}

