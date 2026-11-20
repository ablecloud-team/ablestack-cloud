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
package org.apache.cloudstack.backup;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.apache.cloudstack.framework.config.ConfigDepot;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.host.HostVO;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonParser;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class NetBackupHookTimeoutServiceTest {
    private NetBackupHookTimeoutService service;
    private NetBackupScheduleModeGuard guard;
    private AgentManager agents;
    private final AtomicReference<String> global = new AtomicReference<>("86400");
    private final List<String> calls = new ArrayList<>();
    private String fail;
    private boolean rollbackFails;
    private List<HostVO> hosts;

    @Before public void setup() throws Exception {
        service = new NetBackupHookTimeoutService();
        guard = mock(NetBackupScheduleModeGuard.class);
        agents = mock(AgentManager.class);
        ConfigDepot depot = mock(ConfigDepot.class);
        ConfigurationDao configuration = mock(ConfigurationDao.class);
        when(configuration.getValue(BackupManager.NetBackupHookTimeout.key())).thenAnswer(unused -> global.get());
        hosts = List.of(host(1), host(2));
        when(guard.withIdleHosts(any())).thenAnswer(call -> ((Function<List<HostVO>, ?>) call.getArgument(0)).apply(hosts));
        for (String name : List.of("backup.plugin.netbackup.url", "backup.plugin.netbackup.apikey")) {
            ConfigKey<?> key = mock(ConfigKey.class);
            doReturn("synthetic").when(key).valueInScope(ConfigKey.Scope.Zone, 1L);
            doReturn(key).when(depot).get(name);
        }
        for (Object[] dependency : new Object[][] {{"guard", guard}, {"agents", agents}, {"configDepot", depot}, {"configDao", configuration}}) {
            ReflectionTestUtils.setField(service, (String) dependency[0], dependency[1]);
        }
        when(agents.send(anyLong(), any(NetBackupHostConfigurationCommand.class))).thenAnswer(call -> {
            var command = (NetBackupHostConfigurationCommand) call.getArgument(1);
            var payload = JsonParser.parseString(command.getPayload()).getAsJsonObject();
            assertEquals(86400, payload.get("previous").getAsInt());
            String action = command.getAction().substring("HOOK_TIMEOUT_".length());
            String request = call.getArgument(0) + ":" + action;
            calls.add(request);
            if (request.equals(fail) || (rollbackFails && "ROLLBACK".equals(action))) {
                return new Answer(command, false, "Lost Host response");
            }
            String state = Map.of("RECOVER", "RECOVERED", "CHECK", "CHECKED", "APPLY", "APPLIED", "COMMIT", "COMMITTED")
                    .getOrDefault(action, "ROLLED_BACK");
            return new Answer(command, true, "{\"state\":\"" + state + "\",\"fingerprint\":\"hash\"}");
        });
    }

    private HostVO host(long id) {
        HostVO host = mock(HostVO.class);
        when(host.getId()).thenReturn(id);
        when(host.getDataCenterId()).thenReturn(1L);
        when(host.getName()).thenReturn("host-" + id);
        when(host.getPrivateIpAddress()).thenReturn("192.0.2." + id);
        return host;
    }
    private String save() { calls.add("SAVE"); global.set("3600"); return "saved"; }
    private void denied(String code, Runnable action) {
        assertTrue(assertThrows(CloudRuntimeException.class, action::run).getMessage().startsWith("NETBACKUP_HOOK_TIMEOUT_" + code + ":"));
    }

    @Test public void checksEveryHostBeforeApplyingAndSavesOnlyAfterEveryAcknowledgment() {
        assertEquals("saved", service.update("3600", this::save));
        assertEquals(List.of("1:RECOVER", "1:CHECK", "2:RECOVER", "2:CHECK", "1:APPLY", "2:APPLY", "SAVE", "1:COMMIT", "2:COMMIT"), calls);
    }
    @Test public void initialSetupWithoutManagedHostsStillAllowsSaving() {
        hosts = List.of();
        assertEquals("saved", service.update("3600", this::save));
        verifyNoInteractions(agents);
    }
    @Test public void checkFailureNeverWritesAnyHostOrGlobal() {
        fail = "2:CHECK";
        denied("SYNC_FAILED", () -> service.update("3600", this::save));
        assertEquals("86400", global.get());
        assertTrue(calls.stream().noneMatch(s -> s.endsWith(":APPLY") || s.equals("SAVE")));
    }
    @Test public void lostApplyResponseRollsBackTheUncertainHostAndPriorHosts() {
        fail = "2:APPLY";
        denied("SYNC_FAILED", () -> service.update("3600", this::save));
        assertEquals("86400", global.get());
        assertEquals(List.of("2:ROLLBACK", "1:ROLLBACK"), calls.subList(calls.size() - 2, calls.size()));
    }
    @Test public void failedRollbackRequiresReconciliationAndKeepsGlobal() {
        fail = "2:APPLY"; rollbackFails = true;
        denied("RECOVERY_PENDING", () -> service.update("3600", this::save));
        assertEquals("86400", global.get());
    }
    @Test public void rejectedDbSaveRestoresHostsButAnAlreadyCommittedSaveDoesNotUndoThem() {
        denied("SYNC_FAILED", () -> service.update("3600", () -> { throw new IllegalStateException("DB rejected"); }));
        assertTrue(calls.contains("1:ROLLBACK"));
        calls.clear();
        denied("SAVE_UNCONFIRMED", () -> service.update("3600", () -> { global.set("3600"); throw new IllegalStateException("Response lost after commit"); }));
        assertTrue(calls.contains("1:COMMIT"));
        assertTrue(calls.stream().noneMatch(s -> s.endsWith(":ROLLBACK")));
    }
    @Test public void sameValueRepairFailureIsNotMisreportedAsASuccessfulDbCommit() {
        fail = "1:APPLY";
        denied("SYNC_FAILED", () -> service.update("86400", () -> "unchanged"));
    }
    @Test public void commitReceiptLossDoesNotUndoConfirmedFilesBehindTheSavedGlobal() {
        fail = "1:COMMIT";
        assertEquals("saved", service.update("3600", this::save));
        assertEquals("3600", global.get());
        assertTrue(calls.contains("2:COMMIT"));
    }
    @Test public void unsafeOrInvalidChangesNeverReachHosts() {
        for (String value : List.of("0", "-1", "invalid", "2147483648")) {
            denied("INVALID_VALUE", () -> service.update(value, this::save));
        }
        doThrow(new CloudRuntimeException("Work pending")).when(guard).withIdleHosts(any());
        denied("UNSAFE", () -> service.update("3600", this::save));
        verifyNoInteractions(agents);
    }
}
