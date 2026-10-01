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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.VmProcessAction;
import com.cloud.agent.api.VmProcessActionAnswer;
import com.cloud.agent.api.VmProcessActionCommand;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.google.gson.GsonBuilder;
import org.mockito.ArgumentCaptor;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.UserVmDao;

import org.apache.cloudstack.context.CallContext;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

public class VmProcessActionServiceImplTest {
    private final UserVmDao dao = mock(UserVmDao.class);
    private final AccountManager accounts = mock(AccountManager.class);
    private final AgentManager agents = mock(AgentManager.class);
    private final VmProcessOperationStore store = mock(VmProcessOperationStore.class);
    private final UserVmVO vm = mock(UserVmVO.class);
    private final CallContext ctx = mock(CallContext.class);
    private boolean enabled = true;
    private final VmProcessActionServiceImpl service =
            new VmProcessActionServiceImpl() {
                @Override
                boolean enabled() {
                    return enabled;
                }

                @Override
                void audit(long vm, Map<String, Object> result, String phase) {}
            };
    private final String id = "11111111-1111-4111-8111-111111111111",
            snapshot = "22222222-2222-4222-8222-222222222222";

    @Before
    public void setup() {
        ReflectionTestUtils.setField(service, "vmDao", dao);
        ReflectionTestUtils.setField(service, "accountManager", accounts);
        ReflectionTestUtils.setField(service, "agentManager", agents);
        ReflectionTestUtils.setField(service, "store", store);
        when(dao.findById(1L)).thenReturn(vm);
        when(vm.getType()).thenReturn(VirtualMachine.Type.User);
        when(ctx.getCallingAccount()).thenReturn(mock(Account.class));
        when(ctx.getCallingAccountId()).thenReturn(9L);
    }

    private MockedStatic<CallContext> context() {
        MockedStatic<CallContext> c = mockStatic(CallContext.class);
        c.when(CallContext::current).thenReturn(ctx);
        return c;
    }

    @Test
    public void disabledNeverReservesOrSends() {
        try (MockedStatic<CallContext> c = context()) {
            enabled = false;
            assertThrows(
                    InvalidParameterValueException.class,
                    () -> service.execute(1, id, snapshot, 3, "process.kill", null));
            verifyNoInteractions(store, agents);
        }
    }

    @Test
    public void crossTenantNeverReadsJournal() {
        try (MockedStatic<CallContext> c = context()) {
            doThrow(new PermissionDeniedException("denied"))
                    .when(accounts)
                    .checkAccess(any(), any(), eq(true), eq(vm));
            assertThrows(PermissionDeniedException.class, () -> service.get(1, id));
            verifyNoInteractions(store, agents);
        }
    }

    @Test
    public void injectedServiceNeverReserves() {
        try (MockedStatic<CallContext> c = context()) {
            assertThrows(
                    InvalidParameterValueException.class,
                    () ->
                            service.execute(
                                    1, id, snapshot, 3, "service.restart", "x';touch /tmp/x"));
            verifyNoInteractions(store, agents);
        }
    }

    @Test
    public void duplicateUnknownNeverResends() throws Exception {
        try (MockedStatic<CallContext> c = context()) {
            VmProcessOperationStore.Record r = new VmProcessOperationStore.Record();
            r.vm = 1;
            r.fingerprint =
                    VmProcessActionServiceImpl.fingerprint(1, snapshot, 3, "process.kill", null);
            r.result =
                    "{\"authority\":{\"vmUuid\":\""
                            + id
                            + "\"},\"state\":\"UNKNOWN\",\"operationId\":\""
                            + id
                            + "\"}";
            when(store.find(9, id)).thenReturn(r);
            assertEquals(
                    "UNKNOWN",
                    service.execute(1, id, snapshot, 3, "process.kill", null)
                            .getProcessState()
                            .get("state"));
            verifyNoInteractions(agents);
            verify(store, never()).reserve(any());
        }
    }

    @Test
    public void requestConflictNeverResends() throws Exception {
        try (MockedStatic<CallContext> c = context()) {
            VmProcessOperationStore.Record r = new VmProcessOperationStore.Record();
            r.vm = 1;
            r.fingerprint = "other";
            when(store.find(9, id)).thenReturn(r);
            assertThrows(
                    InvalidParameterValueException.class,
                    () -> service.execute(1, id, snapshot, 3, "process.kill", null));
            verifyNoInteractions(agents);
        }
    }

    @Test
    public void actionPermissionsAreSeparateAndDefaultAdminOnly() {
        for (Class<?> c :
                List.of(
                        org.apache.cloudstack.api.command.user.vm.TerminateVirtualMachineProcessCmd
                                .class,
                        org.apache.cloudstack.api.command.user.vm.KillVirtualMachineProcessCmd
                                .class,
                        org.apache.cloudstack.api.command.user.vm.RestartVirtualMachineServiceCmd
                                .class))
            assertArrayEquals(
                    new org.apache.cloudstack.acl.RoleType[] {
                        org.apache.cloudstack.acl.RoleType.Admin
                    },
                    c.getAnnotation(org.apache.cloudstack.api.APICommand.class).authorized());
    }

    @Test
    public void revokedReadAccessCannotReturnCachedJournal() throws Exception {
        try (MockedStatic<CallContext> c = context()) {
            VmProcessOperationStore.Record r = new VmProcessOperationStore.Record();
            r.vm = 1;
            r.state = "SUCCEEDED";
            r.result = "{\"authority\":{\"vmUuid\":\"v\"},\"state\":\"SUCCEEDED\"}";
            when(store.get(1, id)).thenReturn(r);
            doNothing()
                    .doThrow(new PermissionDeniedException("revoked"))
                    .when(accounts)
                    .checkAccess(any(), any(), eq(true), eq(vm));
            assertThrows(PermissionDeniedException.class, () -> service.get(1, id));
            verifyNoInteractions(agents);
        }
    }

    private VmProcessOperationStore.Record pending() throws Exception {
        VmProcessOperationStore.Record r = new VmProcessOperationStore.Record();
        r.vm = 1;
        r.host = 4;
        r.generation = 7;
        r.operation = id;
        r.request = snapshot;
        r.state = "UNKNOWN";
        Map<String, Object> request = new java.util.LinkedHashMap<>();
        request.put("schemaVersion", "1.0");
        request.put("requestId", snapshot);
        request.put("operationId", id);
        request.put("authority", Map.of("vmUuid", id, "bootId", "boot-1"));
        request.put("identity", Map.of("pid", "3", "startTicks", "100"));
        request.put("action", "process.kill");
        request.put("service", null);
        r.json = json(request);
        r.result = json(VmProcessAction.unknown(request));
        when(store.get(1, id)).thenReturn(r);
        when(vm.getUuid()).thenReturn(id);
        when(vm.getState()).thenReturn(VirtualMachine.State.Running);
        when(vm.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(vm.getHostId()).thenReturn(4L);
        when(vm.getUpdated()).thenReturn(7L);
        return r;
    }

    private String json(Object value) {
        return new GsonBuilder().serializeNulls().create().toJson(value);
    }

    private Map<String, Object> completed(VmProcessOperationStore.Record r) throws Exception {
        Map<String, Object> result = VmProcessAction.parse(r.result);
        result.put("state", "SUCCEEDED");
        result.put("effect", "VERIFIED");
        result.put("postcondition", "TARGET_EXITED");
        result.put("completedAt", java.time.Instant.now().toString());
        result.put("error", null);
        return result;
    }

    @Test
    public void invalidUuidIsAParameterErrorBeforeJournalAccess() {
        try (MockedStatic<CallContext> c = context()) {
            for (String invalid : new String[] {null, "bad", "1-1-1-1-1", " " + id}) {
                assertThrows(InvalidParameterValueException.class, () -> service.get(1, invalid));
            }
            verifyNoInteractions(store, agents);
        }
    }

    @Test
    public void durableUnknownAfterRestartOnlyQueriesAndReconciles() throws Exception {
        try (MockedStatic<CallContext> c = context()) {
            VmProcessOperationStore.Record r = pending();
            when(agents.send(eq(4L), any(VmProcessActionCommand.class)))
                    .thenReturn(new VmProcessActionAnswer(null, json(completed(r))));
            assertEquals("SUCCEEDED", service.get(1, id).getProcessState().get("state"));
            ArgumentCaptor<VmProcessActionCommand> command =
                    ArgumentCaptor.forClass(VmProcessActionCommand.class);
            verify(agents).send(eq(4L), command.capture());
            assertTrue(command.getValue().isQuery());
            assertEquals("operation.get", VmProcessAction.parse(command.getValue().getRequestJson()).get("operation"));
            verify(store).finish(eq(r), eq("SUCCEEDED"), any());
            verify(store, never()).reserve(any());
        }
    }

    @Test
    public void lostQueryResponseKeepsUnknownWithoutNewDispatch() throws Exception {
        try (MockedStatic<CallContext> c = context()) {
            pending();
            when(agents.send(eq(4L), any(VmProcessActionCommand.class)))
                    .thenThrow(new RuntimeException("connection lost"));
            assertEquals("UNKNOWN", service.get(1, id).getProcessState().get("state"));
            verify(store, never()).finish(any(), any(), any());
            verify(store, never()).reserve(any());
        }
    }

    @Test
    public void migrationDoesNotQueryOldHostJournal() throws Exception {
        try (MockedStatic<CallContext> c = context()) {
            pending();
            when(vm.getHostId()).thenReturn(5L);
            assertEquals("UNKNOWN", service.get(1, id).getProcessState().get("state"));
            verifyNoInteractions(agents);
        }
    }

    @Test
    public void rebootGenerationDoesNotQueryOldJournal() throws Exception {
        try (MockedStatic<CallContext> c = context()) {
            pending();
            when(vm.getUpdated()).thenReturn(8L);
            assertEquals("UNKNOWN", service.get(1, id).getProcessState().get("state"));
            verifyNoInteractions(agents);
        }
    }

    @Test
    public void placementChangeDuringQueryCannotReleaseReservation() throws Exception {
        try (MockedStatic<CallContext> c = context()) {
            VmProcessOperationStore.Record r = pending();
            when(agents.send(eq(4L), any(VmProcessActionCommand.class))).thenAnswer(invocation -> {
                when(vm.getHostId()).thenReturn(5L);
                return new VmProcessActionAnswer(null, json(completed(r)));
            });
            assertEquals("UNKNOWN", service.get(1, id).getProcessState().get("state"));
            verify(store, never()).finish(any(), any(), any());
        }
    }

    @Test
    public void mismatchedGuestIdentityCannotReleaseReservation() throws Exception {
        try (MockedStatic<CallContext> c = context()) {
            VmProcessOperationStore.Record r = pending();
            Map<String, Object> result = completed(r);
            result.put("identity", Map.of("pid", "3", "startTicks", "101"));
            when(agents.send(eq(4L), any(VmProcessActionCommand.class)))
                    .thenReturn(new VmProcessActionAnswer(null, json(result)));
            assertEquals("UNKNOWN", service.get(1, id).getProcessState().get("state"));
            verify(store, never()).finish(any(), any(), any());
        }
    }

    @Test
    public void revokedAccessDuringQueryCannotReturnOrFinish() throws Exception {
        try (MockedStatic<CallContext> c = context()) {
            VmProcessOperationStore.Record r = pending();
            when(agents.send(eq(4L), any(VmProcessActionCommand.class))).thenAnswer(invocation -> {
                doThrow(new PermissionDeniedException("revoked")).when(accounts)
                        .checkAccess(any(), any(), eq(true), eq(vm));
                return new VmProcessActionAnswer(null, json(completed(r)));
            });
            assertThrows(PermissionDeniedException.class, () -> service.get(1, id));
            verify(store, never()).finish(any(), any(), any());
        }
    }

    @Test
    public void globalDisablePreventsReadingPendingOperation() throws Exception {
        try (MockedStatic<CallContext> c = context()) {
            enabled = false;
            assertThrows(InvalidParameterValueException.class, () -> service.get(1, id));
            verifyNoInteractions(store, agents);
        }
    }
}
