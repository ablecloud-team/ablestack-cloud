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
import java.util.ArrayList;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;
import org.apache.cloudstack.context.CallContext;
import com.cloud.agent.AgentManager;
import com.cloud.agent.api.GetVmProcessSnapshotCommand;
import com.cloud.agent.api.GetVmProcessSnapshotAnswer;
import com.cloud.agent.api.VmProcessSnapshot;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.InvalidParameterValueException;
public class VmProcessSnapshotServiceImplTest {
    private final UserVmDao dao = mock(UserVmDao.class);
    private final AccountManager accounts = mock(AccountManager.class);
    private final AgentManager agents = mock(AgentManager.class);
    private final HostDao hosts = mock(HostDao.class);
    private final UserVmVO vm = mock(UserVmVO.class);
    private final CallContext context = mock(CallContext.class);
    private boolean enabled = true;
    private long clock = 100000000000L;
    private final VmProcessSnapshotServiceImpl service = new VmProcessSnapshotServiceImpl() {
        @Override boolean enabled() { return enabled; }
        @Override long now() { return clock; }
    };
    private void setup() throws Exception {
        ReflectionTestUtils.setField(service, "vmDao", dao); ReflectionTestUtils.setField(service, "accountManager", accounts);
        ReflectionTestUtils.setField(service, "agentManager", agents); ReflectionTestUtils.setField(service, "hostDao", hosts);
        when(dao.findById(15L)).thenReturn(vm); when(context.getCallingAccount()).thenReturn(mock(Account.class));
        when(vm.getType()).thenReturn(VirtualMachine.Type.User); when(vm.getState()).thenReturn(VirtualMachine.State.Running);
        when(vm.getHypervisorType()).thenReturn(HypervisorType.KVM); when(vm.getUuid()).thenReturn("11111111-1111-4111-8111-111111111111");
        when(vm.getInstanceName()).thenReturn("i-2-15-VM"); when(vm.getHostId()).thenReturn(1L); when(vm.getUpdated()).thenReturn(42L);
        HostVO host = mock(HostVO.class); when(host.getUuid()).thenReturn("22222222-2222-4222-8222-222222222222"); when(hosts.findById(1L)).thenReturn(host);
        when(agents.send(eq(1L), any(GetVmProcessSnapshotCommand.class))).thenAnswer(call -> {
            GetVmProcessSnapshotCommand c = call.getArgument(1);
            return new GetVmProcessSnapshotAnswer(c, new com.google.gson.GsonBuilder().serializeNulls().create().toJson(snapshot(c)));
        });
    }
    static Map<String, Object> snapshot(GetVmProcessSnapshotCommand c) {
        Map<String, Object> result = new LinkedHashMap<>(VmProcessSnapshot.request(c));
        result.remove("operation"); result.remove("operationId"); result.remove("budgetMs"); result.put("kind", "snapshot");
        result.put("snapshotId", "33333333-3333-4333-8333-333333333333"); result.put("bootId", "windows:133000000000000000");
        result.put("observedAt", "2026-09-27T00:00:00Z"); result.put("expiresAt", "2026-09-27T00:00:10Z");
        result.put("status", "OK"); result.put("truncated", false); result.put("totalKnown", 3);
        List<Object> rows = new ArrayList<>();
        for (int i = 3; i > 0; i--) {
            Map<String, Object> row = new LinkedHashMap<>(); row.put("identity", Map.of("vmUuid", c.getVmUuid(), "bootId", result.get("bootId"), "pid", i, "startTicks", "133000000000000001"));
            row.put("ppid", null); row.put("name", "test" + i); row.put("owner", null); row.put("state", "running");
            row.put("memoryBytes", i * 1024); row.put("cpuPercent", null); row.put("services", List.of()); row.put("allowedActions", List.of()); rows.add(row);
        }
        result.put("processes", rows); return result;
    }
    private MockedStatic<CallContext> context() { MockedStatic<CallContext> scope = mockStatic(CallContext.class); scope.when(CallContext::current).thenReturn(context); return scope; }
    @Test public void asyncResponseRoundTripRetainsContractNullsAndTickStrings() throws Exception {
        setup(); try (MockedStatic<CallContext> scope = context()) {
            org.apache.cloudstack.api.response.VmProcessSnapshotResponse response = service.refresh(15L);
            com.google.gson.Gson gson = com.cloud.api.ApiResponseGsonHelper.getBuilder().create();
            String encoded = gson.toJson(response);
            org.apache.cloudstack.api.response.VmProcessSnapshotResponse restored = gson.fromJson(encoded, org.apache.cloudstack.api.response.VmProcessSnapshotResponse.class);
            assertEquals(response.getProcessState().get("snapshotId"), restored.getProcessState().get("snapshotId"));
            assertEquals(List.of(), restored.getProcessState().get("processes"));
            String id = (String) response.getProcessState().get("snapshotId");
            String page = gson.toJson(service.list(15L, id, null, "pid", false, 1, 10));
            org.junit.Assert.assertTrue(page.contains("133000000000000001"));
            org.junit.Assert.assertTrue(page.contains("\"cpuPercent\":null"));
        }
    }
    @Test public void globalOptInDefaultsToFalse() {
        assertEquals("false", VmProcessCapabilityServiceImpl.MANAGEMENT_ENABLED.defaultValue());
        assertEquals(false, new VmProcessCapabilityServiceImpl().enabled(vm));
    }
    @Test public void disabledAndDeniedNeverDispatch() throws Exception {
        setup(); try (MockedStatic<CallContext> scope = context()) {
            enabled = false; assertThrows(InvalidParameterValueException.class, () -> service.refresh(15L));
            enabled = true; doThrow(new PermissionDeniedException("denied")).when(accounts).checkAccess(any(), any(), eq(true), eq(vm));
            assertThrows(PermissionDeniedException.class, () -> service.refresh(15L)); verifyNoInteractions(agents);
        }
    }
    @Test public void pagesAreStableAndCoalescedAndTicksRemainStrings() throws Exception {
        setup(); try (MockedStatic<CallContext> scope = context()) {
            Map<String, Object> first = service.refresh(15L).getProcessState(); String id = (String) first.get("snapshotId");
            assertEquals(Map.of("vmUuid", vm.getUuid()), first.get("authority")); assertEquals(List.of(), first.get("processes")); service.refresh(15L);
            Map<String, Object> page = service.list(15L, id, null, "pid", false, 2, 1).getProcessState();
            Map<String, Object> identity = VmProcessSnapshot.map(VmProcessSnapshot.map(((List<?>) page.get("processes")).get(0)).get("identity"));
            assertEquals(2, ((Number) identity.get("pid")).intValue()); assertEquals("133000000000000001", identity.get("startTicks"));
            verify(agents, times(1)).send(eq(1L), any(GetVmProcessSnapshotCommand.class));
            assertThrows(InvalidParameterValueException.class, () -> service.list(15L, id, null, "pid", false, 1, 201));
            clock += 11000000000L; assertEquals("failure", service.list(15L, id, null, "pid", false, 1, 10).getProcessState().get("kind"));
        }
    }
    @Test public void movedVmAndRevokedAccessNeverExposeCachedRows() throws Exception {
        setup(); try (MockedStatic<CallContext> scope = context()) {
            String id = (String) service.refresh(15L).getProcessState().get("snapshotId");
            when(vm.getUpdated()).thenReturn(43L);
            assertEquals("failure", service.list(15L, id, null, "pid", false, 1, 10).getProcessState().get("kind"));
            doThrow(new PermissionDeniedException("revoked")).when(accounts).checkAccess(any(), any(), eq(true), eq(vm));
            assertThrows(PermissionDeniedException.class, () -> service.list(15L, id, null, "pid", false, 1, 10));
        }
    }
    @Test public void disableWhileAgentRunsDiscardsResults() throws Exception {
        setup(); when(agents.send(eq(1L), any(GetVmProcessSnapshotCommand.class))).thenAnswer(call -> { enabled = false; return null; });
        try (MockedStatic<CallContext> scope = context()) { assertThrows(InvalidParameterValueException.class, () -> service.refresh(15L)); }
    }
    @Test public void placementChangeDuringCollectionDiscardsResult() throws Exception {
        setup(); when(agents.send(eq(1L), any(GetVmProcessSnapshotCommand.class))).thenAnswer(call -> { when(vm.getUpdated()).thenReturn(43L); return null; });
        try (MockedStatic<CallContext> scope = context()) {
            assertEquals("STALE_AUTHORITY", VmProcessSnapshot.map(service.refresh(15L).getProcessState().get("error")).get("code"));
        }
    }
    @Test public void parserRejectsSecretsDuplicateKeysAndIdentityPrecisionLoss() throws Exception {
        GetVmProcessSnapshotCommand c = new GetVmProcessSnapshotCommand("vm", "11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222", "42", UUID.randomUUID().toString());
        Map<String, Object> good = snapshot(c); com.google.gson.Gson gson = new com.google.gson.GsonBuilder().serializeNulls().create();
        VmProcessSnapshot.decode(gson.toJson(good), c);
        assertThrows(java.io.IOException.class, () -> VmProcessSnapshot.decode(gson.toJson(good).replaceFirst("\\{", "{\"kind\":\"snapshot\","), c));
        Map<String, Object> row = VmProcessSnapshot.map(((List<?>) good.get("processes")).get(0));
        Map<String, Object> identity = new LinkedHashMap<>(VmProcessSnapshot.map(row.get("identity")));
        row.put("identity", identity); identity.put("startTicks", 133000000000000001L);
        assertThrows(java.io.IOException.class, () -> VmProcessSnapshot.decode(gson.toJson(good), c));
        identity.put("startTicks", "133000000000000001");
        good.put("status", "PARTIAL"); good.put("truncated", true); good.put("totalKnown", null);
        VmProcessSnapshot.decode(gson.toJson(good), c);
        row.put("commandLine", "secret");
        assertThrows(java.io.IOException.class, () -> VmProcessSnapshot.decode(gson.toJson(good), c));
    }
    private static long firstPid(org.apache.cloudstack.api.response.VmProcessSnapshotResponse response) {
        Map<String, Object> row = VmProcessSnapshot.map(((List<?>) response.getProcessState().get("processes")).get(0));
        return ((Number) VmProcessSnapshot.map(row.get("identity")).get("pid")).longValue();
    }
    @Test public void cpuNumbersSurviveDecoderAndSortWithNullLast() throws Exception {
        GetVmProcessSnapshotCommand c = new GetVmProcessSnapshotCommand("vm", "11111111-1111-4111-8111-111111111111",
                "22222222-2222-4222-8222-222222222222", "42", UUID.randomUUID().toString());
        Map<String, Object> result = snapshot(c);
        List<?> rows = (List<?>) result.get("processes");
        VmProcessSnapshot.map(rows.get(0)).put("cpuPercent", null);
        VmProcessSnapshot.map(rows.get(1)).put("cpuPercent", new BigDecimal("126.8"));
        VmProcessSnapshot.map(rows.get(2)).put("cpuPercent", BigDecimal.ZERO);
        com.google.gson.Gson gson = new com.google.gson.GsonBuilder().serializeNulls().create();
        Map<String, Object> decoded = VmProcessSnapshot.decode(gson.toJson(result), c);
        assertEquals(new BigDecimal("126.8"), VmProcessSnapshot.map(((List<?>) decoded.get("processes")).get(1)).get("cpuPercent"));
        assertEquals(1L, firstPid(VmProcessSnapshotServiceImpl.page(decoded, null, "cpuPercent", false, 1, 1)));
        assertEquals(2L, firstPid(VmProcessSnapshotServiceImpl.page(decoded, null, "cpuPercent", false, 2, 1)));
        assertEquals(3L, firstPid(VmProcessSnapshotServiceImpl.page(decoded, null, "cpuPercent", false, 3, 1)));
        assertEquals(2L, firstPid(VmProcessSnapshotServiceImpl.page(decoded, null, "cpuPercent", true, 1, 1)));
        assertEquals(1L, firstPid(VmProcessSnapshotServiceImpl.page(decoded, null, "cpuPercent", true, 2, 1)));
        assertEquals(3L, firstPid(VmProcessSnapshotServiceImpl.page(decoded, null, "cpuPercent", true, 3, 1)));
        VmProcessSnapshot.map(rows.get(1)).put("cpuPercent", 0);
        assertEquals(1L, firstPid(VmProcessSnapshotServiceImpl.page(result, null, "cpuPercent", false, 1, 1)));
        assertEquals(2L, firstPid(VmProcessSnapshotServiceImpl.page(result, null, "cpuPercent", false, 2, 1)));
    }
    @Test public void cpuDecoderRejectsInvalidNumbersWithoutWeakeningActions() throws Exception {
        GetVmProcessSnapshotCommand c = new GetVmProcessSnapshotCommand("vm", "11111111-1111-4111-8111-111111111111",
                "22222222-2222-4222-8222-222222222222", "42", UUID.randomUUID().toString());
        Map<String, Object> result = snapshot(c);
        Map<String, Object> row = VmProcessSnapshot.map(((List<?>) result.get("processes")).get(0));
        com.google.gson.Gson gson = new com.google.gson.GsonBuilder().serializeNulls().create();
        for (Object cpu : List.of(-0.1, true, "1.25")) {
            row.put("cpuPercent", cpu);
            assertThrows(java.io.IOException.class, () -> VmProcessSnapshot.decode(gson.toJson(result), c));
        }
        for (Object cpu : List.of(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            row.put("cpuPercent", cpu);
            assertThrows(IllegalArgumentException.class, () -> VmProcessSnapshot.validate(result, c));
        }
        row.put("cpuPercent", 250.5);
        row.put("allowedActions", List.of("process.kill"));
        assertThrows(java.io.IOException.class, () -> VmProcessSnapshot.decode(gson.toJson(result), c));
    }

}
