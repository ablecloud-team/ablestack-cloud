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

package org.apache.cloudstack.backup;

import java.util.List;
import javax.inject.Provider;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.impl.ConfigDepotImpl;
import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDetailsDao;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.Gson;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class ThirdPartyBackupStagingConfigurationSyncTest {
    private final Gson gson = new Gson();
    private ThirdPartyBackupStagingConfigurationSync service;
    private AgentManager agents;
    private HostDetailsDao details;
    private BackupProvider provider;
    private HostVO host;
    private ThirdPartyBackupStagingService.Snapshot config;
    private ConfigDepotImpl original;
    private boolean changed;
    private boolean failApply;
    private String responseHost;
    private boolean explicit;

    @Before public void setup() throws Exception {
        service = new ThirdPartyBackupStagingConfigurationSync();
        agents = mock(AgentManager.class);
        details = mock(HostDetailsDao.class);
        provider = mock(BackupProvider.class);
        BackupManager manager = mock(BackupManager.class);
        Provider<BackupManager> lazy = () -> manager;
        when(manager.getBackupProvider(anyString())).thenReturn(provider);
        ReflectionTestUtils.setField(service, "agentManager", agents);
        ReflectionTestUtils.setField(service, "hostDetailsDao", details);
        ReflectionTestUtils.setField(service, "backupManager", lazy);
        host = host(1L, "host-four");
        config = new ThirdPartyBackupStagingService.Snapshot();
        config.rootPath = "/new-stage";
        config.type = "LOCAL";
        original = (ConfigDepotImpl) ReflectionTestUtils.getField(BackupManager.BackupProviderPlugin, "s_depot");
        ConfigDepotImpl depot = mock(ConfigDepotImpl.class);
        ConfigKey.init(depot);
        when(depot.getConfigStringValue(eq(BackupManager.BackupProviderPlugin.key()), eq(ConfigKey.Scope.Zone), anyLong())).thenReturn("ablestack-netbackup");
        when(agents.send(anyLong(), any(AblestackBackupStagingSyncCommand.class))).thenAnswer(call -> {
            var request = gson.fromJson(((AblestackBackupStagingSyncCommand) call.getArgument(1)).getRequestJson(), ThirdPartyBackupStagingSync.Request.class);
            if (failApply && "APPLY".equals(request.action)) { return new Answer(null, false, "Host write failed"); }
            var plan = new ThirdPartyBackupStagingSync.Plan();
            plan.hostName = responseHost == null ? request.hostName : responseHost;
            plan.rootPath = request.configuration.rootPath;
            plan.fingerprint = "a".repeat(64);
            plan.changed = changed && "PLAN".equals(request.action);
            var binding = new ThirdPartyBackupStagingSync.Binding();
            binding.provider = "ablestack-netbackup";
            binding.name = "custom-policy";
            binding.previousPath = "/old-stage/ablestack-netbackup/bootstrap/custom-policy";
            binding.path = "/new-stage/ablestack-netbackup/bootstrap/custom-policy";
            binding.managed = !explicit;
            plan.bindings.add(binding);
            return new Answer(null, true, gson.toJson(plan));
        });
    }

    @After public void restoreConfig() { ConfigKey.init(original); }

    private HostVO host(long id, String name) {
        HostVO value = mock(HostVO.class);
        when(value.getId()).thenReturn(id);
        when(value.getName()).thenReturn(name);
        when(value.getDataCenterId()).thenReturn(1L);
        return value;
    }

    @Test public void matchingPathsDoNotApplyOrVerifyHostFilesOrWriteExternalPolicies() throws Exception {
        Runnable idleGuard = mock(Runnable.class);
        service.synchronize(config, List.of(host), true, idleGuard);
        verifyNoInteractions(idleGuard);
        verify(provider).synchronizeStagingPath(eq(host), any(), eq("CHECK"));
        verify(provider, never()).synchronizeStagingPath(any(), any(), eq("APPLY"));
        ArgumentCaptor<AblestackBackupStagingSyncCommand> calls = ArgumentCaptor.forClass(AblestackBackupStagingSyncCommand.class);
        verify(agents).send(eq(1L), calls.capture());
        assertEquals("PLAN", gson.fromJson(calls.getValue().getRequestJson(), ThirdPartyBackupStagingSync.Request.class).action);
    }

    @Test public void changedHostPathsAreAppliedAndVerifiedBeforeCompletion() throws Exception {
        changed = true;
        service.synchronize(config, List.of(host), true);
        verify(agents, times(3)).send(eq(1L), any(AblestackBackupStagingSyncCommand.class));
        verify(provider).synchronizeStagingPath(eq(host), any(), eq("VERIFY"));
        verify(details, atLeastOnce()).persist(eq(1L), argThat(values -> values.get(ThirdPartyBackupStagingConfigurationSync.STATUS_KEY).contains("SYNCHRONIZED")));
    }

    @Test public void onlyExternalDifferenceDoesNotRewriteHostFiles() throws Exception {
        when(provider.synchronizeStagingPath(eq(host), any(), eq("CHECK"))).thenReturn(true);
        service.synchronize(config, List.of(host), true);
        ArgumentCaptor<AblestackBackupStagingSyncCommand> calls = ArgumentCaptor.forClass(AblestackBackupStagingSyncCommand.class);
        verify(agents, times(2)).send(eq(1L), calls.capture());
        assertEquals(List.of("PLAN", "VERIFY"), calls.getAllValues().stream()
                .map(command -> gson.fromJson(command.getRequestJson(), ThirdPartyBackupStagingSync.Request.class).action)
                .collect(java.util.stream.Collectors.toList()));
    }

    @Test public void hostFailureLeavesDurableFailureAndDoesNotClaimCompletion() {
        changed = failApply = true;
        assertThrows(CloudRuntimeException.class, () -> service.synchronize(config, List.of(host), true));
        verify(details, atLeastOnce()).persist(eq(1L), argThat(values -> values.get(ThirdPartyBackupStagingConfigurationSync.STATUS_KEY).contains("FAILED")));
        verify(provider, never()).synchronizeStagingPath(any(), any(), eq("VERIFY"));
    }

    @Test public void preflightFailureOnAnotherHostPreventsEveryExternalWrite() {
        HostVO second = host(2L, "host-five");
        changed = true;
        when(provider.synchronizeStagingPath(eq(second), any(), eq("CHECK"))).thenThrow(new CloudRuntimeException("Wrong policy client"));
        assertThrows(CloudRuntimeException.class, () -> service.synchronize(config, List.of(host, second), true));
        verify(provider, never()).synchronizeStagingPath(any(), any(), eq("APPLY"));
    }

    @Test public void enabledStagingCannotBeMutatedToRepairDrift() {
        changed = true;
        assertThrows(CloudRuntimeException.class, () -> service.synchronize(config, List.of(host), false));
        verify(provider, never()).synchronizeStagingPath(any(), any(), eq("APPLY"));
    }

    @Test public void explicitPathsDoNotContactTheExternalPolicy() {
        explicit = true;
        service.synchronize(config, List.of(host), true);
        verifyNoInteractions(provider);
    }

    @Test public void aResponseForAnotherHostCannotAuthorizeSynchronization() {
        responseHost = "wrong-host";
        assertThrows(CloudRuntimeException.class, () -> service.synchronize(config, List.of(host), true));
        verifyNoInteractions(provider);
    }

    @Test public void activeBackupGuardRunsOnlyBeforeActualPathChanges() {
        changed = true;
        Runnable idleGuard = mock(Runnable.class);
        doThrow(new CloudRuntimeException("A backup is active")).when(idleGuard).run();
        assertThrows(CloudRuntimeException.class, () -> service.synchronize(config, List.of(host), true, idleGuard));
        verify(idleGuard).run();
        verify(provider, never()).synchronizeStagingPath(any(), any(), eq("APPLY"));
    }
}
