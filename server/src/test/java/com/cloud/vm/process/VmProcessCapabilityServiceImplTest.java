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
package com.cloud.vm.process;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import java.util.Map;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;
import org.apache.cloudstack.context.CallContext;
import com.cloud.agent.AgentManager;
import com.cloud.agent.api.GetVmProcessCapabilitiesCommand;
import com.cloud.agent.api.GetVmProcessCapabilitiesAnswer;
import com.cloud.agent.api.VmProcessCapability;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.exception.PermissionDeniedException;

public class VmProcessCapabilityServiceImplTest {
    private final UserVmDao dao = mock(UserVmDao.class);
    private final AccountManager accounts = mock(AccountManager.class);
    private final AgentManager agents = mock(AgentManager.class);
    private final HostDao hosts = mock(HostDao.class);
    private final UserVmVO vm = mock(UserVmVO.class);
    private final CallContext context = mock(CallContext.class);
    private final VmProcessCapabilityServiceImpl service = new VmProcessCapabilityServiceImpl() {
        @Override boolean enabled(UserVmVO value) { return true; }
    };
    private void setup() {
        ReflectionTestUtils.setField(service, "vmDao", dao);
        ReflectionTestUtils.setField(service, "accountManager", accounts);
        ReflectionTestUtils.setField(service, "agentManager", agents);
        ReflectionTestUtils.setField(service, "hostDao", hosts);
        when(dao.findById(15L)).thenReturn(vm);
        when(context.getCallingAccount()).thenReturn(mock(Account.class));
        when(vm.getType()).thenReturn(VirtualMachine.Type.User);
        when(vm.getState()).thenReturn(VirtualMachine.State.Running);
        when(vm.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(vm.getUuid()).thenReturn("11111111-1111-4111-8111-111111111111");
        when(vm.getInstanceName()).thenReturn("i-2-15-VM"); when(vm.getHostId()).thenReturn(1L);
        when(vm.getUpdated()).thenReturn(42L);
        HostVO host = mock(HostVO.class); when(host.getUuid()).thenReturn("22222222-2222-4222-8222-222222222222");
        when(hosts.findById(1L)).thenReturn(host);
    }
    @Test public void currentAuthorityIsCheckedAndHiddenFromCaller() throws Exception {
        setup();
        when(agents.send(eq(1L), any(GetVmProcessCapabilitiesCommand.class))).thenAnswer(call -> {
            GetVmProcessCapabilitiesCommand command = call.getArgument(1);
            assertEquals("42", command.getGeneration());
            return new GetVmProcessCapabilitiesAnswer(command, VmProcessCapability.empty(command));
        });
        try (MockedStatic<CallContext> mock = mockStatic(CallContext.class)) {
            mock.when(CallContext::current).thenReturn(context);
            Map<String, Object> result = service.getCapabilities(15L).getProcessState();
            assertEquals(Map.of("vmUuid", vm.getUuid()), result.get("authority"));
            verify(accounts, times(2)).checkAccess(any(), any(), eq(true), eq(vm));
        }
    }
    @Test public void responseKeepsContractNullsAndOmitsInternalAuthority() {
        GetVmProcessCapabilitiesCommand command = new GetVmProcessCapabilitiesCommand("test",
                "11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222", "42", "request");
        String json = com.cloud.api.ApiResponseGsonHelper.getBuilder().create().toJson(
                VmProcessCapabilityServiceImpl.response(VmProcessCapability.empty(command)));
        org.junit.Assert.assertTrue(json.contains("\"guestAdapterVersion\":null"));
        org.junit.Assert.assertFalse(json.contains("hostUuid"));
        org.junit.Assert.assertFalse(json.contains("placementGeneration"));
    }
    @Test public void placementGenerationChangeDiscardsAgentResult() throws Exception {
        setup();
        when(agents.send(eq(1L), any(GetVmProcessCapabilitiesCommand.class))).thenAnswer(call -> {
            when(vm.getUpdated()).thenReturn(43L);
            GetVmProcessCapabilitiesCommand command = call.getArgument(1);
            return new GetVmProcessCapabilitiesAnswer(command, VmProcessCapability.fail(VmProcessCapability.empty(command), "TOOLS_REQUIRED", "fixture"));
        });
        try (MockedStatic<CallContext> mock = mockStatic(CallContext.class)) {
            mock.when(CallContext::current).thenReturn(context);
            assertEquals("CHECK_FAILED", service.getCapabilities(15L).getProcessState().get("readiness"));
        }
    }
    @Test public void hostMigrationDiscardsEvenSuccessfulAgentReadiness() throws Exception {
        setup();
        when(agents.send(eq(1L), any(GetVmProcessCapabilitiesCommand.class))).thenAnswer(call -> {
            when(vm.getHostId()).thenReturn(2L);
            GetVmProcessCapabilitiesCommand command = call.getArgument(1);
            return new GetVmProcessCapabilitiesAnswer(command, VmProcessCapability.empty(command));
        });
        try (MockedStatic<CallContext> mock = mockStatic(CallContext.class)) {
            mock.when(CallContext::current).thenReturn(context);
            assertEquals("CHECK_FAILED", service.getCapabilities(15L).getProcessState().get("readiness"));
        }
    }
    @Test public void inconsistentAgentReadyIsNotPublished() throws Exception {
        setup();
        when(agents.send(eq(1L), any(GetVmProcessCapabilitiesCommand.class))).thenAnswer(call -> {
            GetVmProcessCapabilitiesCommand command = call.getArgument(1);
            Map<String, Object> invalid = VmProcessCapability.empty(command);
            invalid.put("readiness", "READY"); invalid.put("error", null);
            return new GetVmProcessCapabilitiesAnswer(command, invalid);
        });
        try (MockedStatic<CallContext> mock = mockStatic(CallContext.class)) {
            mock.when(CallContext::current).thenReturn(context);
            assertEquals("CHECK_FAILED", service.getCapabilities(15L).getProcessState().get("readiness"));
        }
    }
    @Test public void deniedTenantNeverContactsAgent() throws Exception {
        setup(); doThrow(new PermissionDeniedException("denied")).when(accounts).checkAccess(any(), any(), eq(true), eq(vm));
        try (MockedStatic<CallContext> mock = mockStatic(CallContext.class)) {
            mock.when(CallContext::current).thenReturn(context);
            assertThrows(PermissionDeniedException.class, () -> service.getCapabilities(15L));
            verifyNoInteractions(agents);
        }
    }
    @Test public void readyAgentWireNullIsRestoredForPublicContract() throws Exception {
        setup();
        when(agents.send(eq(1L), any(GetVmProcessCapabilitiesCommand.class))).thenAnswer(call -> {
            GetVmProcessCapabilitiesCommand command = call.getArgument(1);
            Map<String, Object> ready = VmProcessCapability.empty(command);
            ready.put("readiness", "READY"); ready.remove("error");
            ready.put("os", Map.of("family", "linux", "id", "ubuntu", "version", "24.04", "arch", "x86_64"));
            Map<String, String> rpcs = new java.util.LinkedHashMap<>();
            VmProcessCapability.RPCS.forEach(rpc -> rpcs.put(rpc, "ENABLED")); ready.put("rpcs", rpcs);
            ready.put("qgaVersion", "9.1"); ready.put("hostToolsVersion", "0.10.0"); ready.put("guestAdapterVersion", "approved-read-bundle");
            ready.put("supportedSchemaVersions", java.util.List.of("1.0")); ready.put("allowedActions", java.util.List.of("process.list"));
            return new GetVmProcessCapabilitiesAnswer(command, ready);
        });
        try (MockedStatic<CallContext> mock = mockStatic(CallContext.class)) {
            mock.when(CallContext::current).thenReturn(context);
            org.apache.cloudstack.api.response.VmProcessCapabilityResponse result = service.getCapabilities(15L);
            assertEquals("READY", result.getProcessState().get("readiness"));
            String json = com.cloud.api.ApiResponseGsonHelper.getBuilder().create().toJson(result);
            org.junit.Assert.assertTrue(json.contains("\"error\":null"));
            org.junit.Assert.assertFalse(json.contains("hostUuid"));
        }
    }
    @Test public void stoppedAndNonKvmNeverContactAgent() throws Exception {
        setup();
        try (MockedStatic<CallContext> mock = mockStatic(CallContext.class)) {
            mock.when(CallContext::current).thenReturn(context);
            when(vm.getState()).thenReturn(VirtualMachine.State.Stopped);
            assertEquals("VM_NOT_RUNNING", service.getCapabilities(15L).getProcessState().get("readiness"));
            when(vm.getHypervisorType()).thenReturn(HypervisorType.VMware);
            assertEquals("UNSUPPORTED_HYPERVISOR", service.getCapabilities(15L).getProcessState().get("readiness"));
            verifyNoInteractions(agents);
        }
    }
}
