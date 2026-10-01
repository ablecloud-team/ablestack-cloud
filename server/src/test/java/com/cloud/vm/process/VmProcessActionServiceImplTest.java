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
}
