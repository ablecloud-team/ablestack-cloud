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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;
import org.apache.cloudstack.acl.apikeypair.ApiKeyPair;
import org.apache.cloudstack.backup.dao.BackupDetailsDao;
import org.apache.cloudstack.backup.dao.BackupOfferingDao;
import org.apache.cloudstack.backup.dao.BackupScheduleDao;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.framework.config.impl.ConfigDepotImpl;
import org.apache.cloudstack.framework.config.ConfigKey;
import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.ClusterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.user.Account;
import com.cloud.user.AccountService;
import com.cloud.user.User;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.VirtualMachine;
import com.google.gson.JsonParser;

public class NetBackupHostConfigurationServiceImplTest {
    private NetBackupHostConfigurationServiceImpl service;
    private ConfigDepotImpl previous;
    private ConfigDepotImpl depot;
    private AccountService accounts;
    private AgentManager agents;
    private BackupDetailsDao details;
    private HostVO host;
    private Object previousFrameworkValue;
    private Object previousIncrementalValue;

    @Before public void setUp() throws Exception {
        previous = (ConfigDepotImpl) ReflectionTestUtils.getField(BackupManager.NetBackupVmScheduleEnabled, "s_depot");
        previousFrameworkValue = ReflectionTestUtils.getField(BackupManager.BackupFrameworkEnabled, "_value");
        previousIncrementalValue = ReflectionTestUtils.getField(BackupManager.KvmIncrementalBackup, "_value");
        ReflectionTestUtils.setField(BackupManager.BackupFrameworkEnabled, "_value", null);
        ReflectionTestUtils.setField(BackupManager.KvmIncrementalBackup, "_value", null);
        depot = mock(ConfigDepotImpl.class);
        ConfigKey.init(depot);
        doReturn(BackupManager.BackupProviderPlugin).when(depot).get(BackupManager.BackupProviderPlugin.key());
        doReturn(new ConfigKey<>("Advanced", String.class, "backup.plugin.netbackup.url", "https://localhost:1556/netbackup", "URL", true,
                ConfigKey.Scope.Zone)).when(depot).get("backup.plugin.netbackup.url");
        doReturn(new ConfigKey<>("Advanced", String.class, "backup.plugin.netbackup.apikey", "apikey", "API key", true,
                ConfigKey.Scope.Zone)).when(depot).get("backup.plugin.netbackup.apikey");
        when(depot.getConfigStringValue(anyString(), any(), any())).thenAnswer(call -> {
            switch ((String) call.getArgument(0)) {
                case "backup.framework.enabled": return "true";
                case "backup.framework.provider.plugin": return "ablestack-netbackup";
                case "backup.thirdparty.staging.enable": return "true";
                case "backup.thirdparty.staging.root.path": return "/stage";
                case "backup.plugin.netbackup.vm.schedule.enabled": return "true";
                case "kvm.incremental.backup": return "false";
                case "kvm.backup.chain.size": return "8";
                case "backup.plugin.netbackup.url": return "https://netbackup.test/netbackup";
                case "backup.plugin.netbackup.apikey": return "netbackup-private-key";
                default: return "";
            }
        });
        User user = mock(User.class); when(user.getId()).thenReturn(2L);
        Account account = mock(Account.class); when(account.getId()).thenReturn(2L);
        CallContext.register(user, account);
        service = new NetBackupHostConfigurationServiceImpl();
        host = mock(HostVO.class);
        when(host.getId()).thenReturn(14L); when(host.getUuid()).thenReturn("host-uuid"); when(host.getName()).thenReturn("host-test");
        when(host.getType()).thenReturn(Host.Type.Routing); when(host.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(host.getStatus()).thenReturn(Status.Up); when(host.getDataCenterId()).thenReturn(1L); when(host.getClusterId()).thenReturn(5L);
        HostDao hosts = mock(HostDao.class); when(hosts.findById(14L)).thenReturn(host);
        accounts = mock(AccountService.class); when(accounts.isRootAdmin(2L)).thenReturn(true);
        ApiKeyPair pair = mock(ApiKeyPair.class); when(pair.getApiKey()).thenReturn("mold-private-api-key"); when(pair.getSecretKey()).thenReturn("mold-private-secret");
        when(accounts.getLatestUserKeyPair(2L)).thenReturn(pair);
        agents = mock(AgentManager.class);
        when(agents.send(eq(14L), any(NetBackupHostConfigurationCommand.class))).thenReturn(new Answer(null, true, "{\"state\":\"CHECKED\"}"));
        details = mock(BackupDetailsDao.class); when(details.findDetails(anyString())).thenReturn(List.of());
        VMInstanceDao vms = mock(VMInstanceDao.class); when(vms.listByHostId(14L)).thenReturn(List.of());
        when(vms.listByLastHostIdAndStates(eq(14L), any(VirtualMachine.State.class))).thenReturn(List.of());
        DataCenterDao zones = mock(DataCenterDao.class); DataCenterVO zone = mock(DataCenterVO.class);
        when(zone.getUuid()).thenReturn("zone-uuid"); when(zones.findById(1L)).thenReturn(zone);
        ClusterDao clusters = mock(ClusterDao.class); ClusterVO cluster = mock(ClusterVO.class);
        when(cluster.getUuid()).thenReturn("cluster-uuid"); when(clusters.findById(5L)).thenReturn(cluster);
        BackupOfferingDao offerings = mock(BackupOfferingDao.class);
        BackupOfferingVO offering = mock(BackupOfferingVO.class);
        when(offering.getProvider()).thenReturn("ablestack-netbackup"); when(offering.getUuid()).thenReturn("offering-uuid");
        when(offerings.findByExternalId("netbackup", 1L)).thenReturn(offering);
        for (Object[] dependency : new Object[][] {{"hostDao", hosts}, {"accountService", accounts}, {"agentManager", agents},
                {"configDepot", depot}, {"backupDetailsDao", details}, {"vmInstanceDao", vms}, {"dataCenterDao", zones}, {"clusterDao", clusters},
                {"backupOfferingDao", offerings}, {"backupScheduleDao", mock(BackupScheduleDao.class)}}) {
            ReflectionTestUtils.setField(service, (String) dependency[0], dependency[1]);
        }
    }
    @After public void tearDown() {
        CallContext.unregister();
        ConfigKey.init(previous);
        ReflectionTestUtils.setField(BackupManager.BackupFrameworkEnabled, "_value", previousFrameworkValue);
        ReflectionTestUtils.setField(BackupManager.KvmIncrementalBackup, "_value", previousIncrementalValue);
    }

    @Test public void absentZoneAndClusterOverridesInheritGlobalDefaultsForInspectionAndHostPayload() throws Exception {
        when(depot.getConfigStringValue(anyString(), eq(ConfigKey.Scope.Zone), eq(1L))).thenReturn(null);
        when(depot.getConfigStringValue(eq("kvm.incremental.backup"), eq(ConfigKey.Scope.Cluster), eq(5L))).thenReturn(null);
        when(depot.getConfigStringValue(eq("kvm.incremental.backup"), eq(ConfigKey.Scope.Global), eq(null))).thenReturn(null);
        var result = service.inspect(14L);
        assertTrue(result.frameworkenabled);
        assertTrue(result.frameworkglobalenabled);
        assertTrue(result.frameworkzoneenabled);
        assertTrue(result.providerenabled);
        assertTrue(result.credentialsconfigured);
        assertTrue(result.incrementalbackup);
        assertEquals("https://netbackup.test/netbackup", result.netbackupurl);
        assertEquals(8, result.backupchainsize);
        assertTrue(result.settingsource.values().stream().allMatch("Global"::equals));
        assertFalse(new com.google.gson.Gson().toJson(result).contains("netbackup-private-key"));
        GlobalLock lock = mock(GlobalLock.class); when(lock.lock(5)).thenReturn(true);
        try (MockedStatic<GlobalLock> locks = mockStatic(GlobalLock.class)) {
            locks.when(() -> GlobalLock.getInternLock(ThirdPartyBackupStagingService.CONFIGURATION_LOCK)).thenReturn(lock);
            service.configure(14L, "CHECK", "seocho+01", "http://mold.test/client/api");
        }
        var captor = org.mockito.ArgumentCaptor.forClass(NetBackupHostConfigurationCommand.class);
        verify(agents, times(2)).send(eq(14L), captor.capture());
        var payload = JsonParser.parseString(captor.getValue().getPayload()).getAsJsonObject();
        assertEquals("https://netbackup.test/netbackup", payload.get("netbackupurl").getAsString());
        assertEquals("netbackup-private-key", payload.get("netbackupapikey").getAsString());
        assertTrue(payload.get("incrementalbackup").getAsBoolean());
    }

    @Test public void zoneAndClusterOverridesTakePrecedenceAndExplicitEmptyValuesDoNotInherit() throws Exception {
        when(depot.getConfigStringValue(eq("backup.plugin.netbackup.url"), eq(ConfigKey.Scope.Zone), eq(1L))).thenReturn("https://zone.test/netbackup");
        when(depot.getConfigStringValue(eq("kvm.incremental.backup"), eq(ConfigKey.Scope.Cluster), eq(5L))).thenReturn("true");
        var result = service.inspect(14L);
        assertEquals("https://zone.test/netbackup", result.netbackupurl);
        assertEquals("Zone", result.settingsource.get("backup.plugin.netbackup.url"));
        assertEquals("Cluster", result.settingsource.get("kvm.incremental.backup"));
        assertTrue(result.incrementalbackup);
        when(depot.getConfigStringValue(eq("backup.plugin.netbackup.url"), eq(ConfigKey.Scope.Zone), eq(1L))).thenReturn("");
        when(depot.getConfigStringValue(eq("backup.plugin.netbackup.apikey"), eq(ConfigKey.Scope.Zone), eq(1L))).thenReturn("");
        result = service.inspect(14L);
        assertEquals("", result.netbackupurl);
        assertFalse(result.credentialsconfigured);
        assertEquals("Zone", result.settingsource.get("backup.plugin.netbackup.apikey"));
        assertThrows(CloudRuntimeException.class, () -> service.configure(14L, "CHECK", "seocho+01", "http://mold.test/client/api"));
    }

    @Test public void frameworkRequiresGlobalMasterAndAllowsAZoneToDisableInheritedBackup() {
        when(depot.getConfigStringValue(eq("backup.framework.enabled"), eq(ConfigKey.Scope.Zone), eq(1L))).thenReturn("false");
        var result = service.inspect(14L);
        assertTrue(result.frameworkglobalenabled);
        assertFalse(result.frameworkzoneenabled);
        assertFalse(result.frameworkenabled);
        when(depot.getConfigStringValue(eq("backup.framework.enabled"), eq(ConfigKey.Scope.Global), eq(null))).thenReturn("false");
        ReflectionTestUtils.setField(BackupManager.BackupFrameworkEnabled, "_value", null);
        when(depot.getConfigStringValue(eq("backup.framework.enabled"), eq(ConfigKey.Scope.Zone), eq(1L))).thenReturn("true");
        result = service.inspect(14L);
        assertFalse(result.frameworkglobalenabled);
        assertTrue(result.frameworkzoneenabled);
        assertFalse(result.frameworkenabled);
    }

    @Test public void factoryDefaultsDoNotReportCredentialsOrStagingAsConfigured() {
        when(depot.getConfigStringValue(anyString(), eq(ConfigKey.Scope.Zone), eq(1L))).thenReturn(null);
        when(depot.getConfigStringValue(eq("backup.framework.enabled"), eq(ConfigKey.Scope.Global), eq(null))).thenReturn("false");
        when(depot.getConfigStringValue(eq("backup.framework.provider.plugin"), eq(ConfigKey.Scope.Global), eq(null))).thenReturn(null);
        when(depot.getConfigStringValue(eq("backup.plugin.netbackup.url"), eq(ConfigKey.Scope.Global), eq(null))).thenReturn(null);
        when(depot.getConfigStringValue(eq("backup.plugin.netbackup.apikey"), eq(ConfigKey.Scope.Global), eq(null))).thenReturn(null);
        when(depot.getConfigStringValue(eq("backup.thirdparty.staging.enable"), eq(ConfigKey.Scope.Global), eq(null))).thenReturn("false");
        when(depot.getConfigStringValue(eq("backup.thirdparty.staging.root.path"), eq(ConfigKey.Scope.Global), eq(null))).thenReturn("");
        var result = service.inspect(14L);
        assertFalse(result.frameworkenabled);
        assertFalse(result.providerenabled);
        assertFalse(result.credentialsconfigured);
        assertFalse(result.stagingenabled);
        assertNull(result.stagingpath);
        assertNull(result.bootstrappath);
        assertFalse(result.linkready);
        assertEquals("https://localhost:1556/netbackup", result.netbackupurl);
        verifyNoInteractions(agents);
    }

    @Test public void inspectionPreservesTrueDefaultAndRequiresBothPathsAndComponentsForReady() throws Exception {
        when(agents.send(eq(14L), any(NetBackupHostConfigurationCommand.class))).thenReturn(new Answer(null, true,
            "{\"state\":\"COMPLETED\",\"configured\":true,\"hooksready\":true,\"timeractive\":true,\"policypathmatched\":true,\"checkedat\":1,\"inspectednetbackupurl\":\"https://netbackup.test/netbackup\",\"policyselections\":[\"/stage/ablestack-netbackup/bootstrap/seocho+01\"],\"configuredbootstrappath\":\"/stage/ablestack-netbackup/bootstrap/seocho+01\",\"bootstrappresent\":true,\"policyname\":\"seocho+01\",\"bootstrappath\":\"/stage/ablestack-netbackup/bootstrap/seocho+01\"}"));
        var result = service.inspect(14L);
        assertTrue(result.vmschedulebackup);
        assertTrue(result.linkready);
        assertTrue(result.virtualmachine.isEmpty());
        when(depot.getConfigStringValue(eq("backup.thirdparty.staging.root.path"), any(), any())).thenReturn("/changed");
        assertFalse(service.inspect(14L).linkready);
    }

    @Test public void applyWithEmptyBackupInventoryPassesExistingCredentialsPrivatelyToHost() throws Exception {
        GlobalLock lock = mock(GlobalLock.class); when(lock.lock(5)).thenReturn(true);
        try (MockedStatic<GlobalLock> locks = mockStatic(GlobalLock.class)) {
            locks.when(() -> GlobalLock.getInternLock(ThirdPartyBackupStagingService.CONFIGURATION_LOCK)).thenReturn(lock);
            service.configure(14L, "APPLY", "seocho+01", "http://mold.test/client/api");
        }
        var captor = org.mockito.ArgumentCaptor.forClass(NetBackupHostConfigurationCommand.class);
        verify(agents).send(eq(14L), captor.capture());
        var payload = JsonParser.parseString(captor.getValue().getPayload()).getAsJsonObject();
        assertEquals("host-uuid", payload.get("hostid").getAsString());
        assertEquals("zone-uuid", payload.get("zoneid").getAsString());
        assertEquals("cluster-uuid", payload.get("clusterid").getAsString());
        assertTrue(payload.get("vmschedulebackup").getAsBoolean());
        assertFalse(payload.get("incrementalbackup").getAsBoolean());
        assertEquals("mold-private-secret", payload.get("adminsecretkey").getAsString());
        verify(accounts, never()).createApiKeyAndSecretKey(anyLong());
    }

    @Test public void administratorWithoutExistingKeysCannotStartSetup() throws Exception {
        when(accounts.getLatestUserKeyPair(2L)).thenReturn(null);
        assertThrows(CloudRuntimeException.class, () -> service.configure(14L, "CHECK", "seocho+01", "http://mold.test/client/api"));
        verifyNoInteractions(agents);
    }

    @Test public void nonRootAdministratorAndDisconnectedHostCannotStartSetup() throws Exception {
        when(accounts.isRootAdmin(2L)).thenReturn(false);
        assertThrows(CloudRuntimeException.class, () -> service.inspect(14L));
        when(accounts.isRootAdmin(2L)).thenReturn(true);
        when(host.getStatus()).thenReturn(Status.Disconnected);
        assertThrows(CloudRuntimeException.class, () -> service.configure(14L, "CHECK", "seocho+01", "http://mold.test/client/api"));
        verifyNoInteractions(agents);
    }

    @Test public void unknownAgentResponseDoesNotReportSuccess() throws Exception {
        when(agents.send(eq(14L), any(NetBackupHostConfigurationCommand.class))).thenReturn(null);
        assertEquals("UNREACHABLE", service.inspect(14L).state);
        assertFalse(service.inspect(14L).linkready);
    }

    @Test public void providerGatingRejectsOtherProvidersAndAcceptsAnExplicitProviderList() throws Exception {
        when(depot.getConfigStringValue(eq(BackupManager.BackupProviderPlugin.key()), any(), any())).thenReturn("ablestack-nas, ablestack-veeam");
        assertEquals("PROVIDER_DISABLED", service.inspect(14L).state);
        assertFalse(service.inspect(14L).providerenabled);
        assertThrows(CloudRuntimeException.class, () -> service.configure(14L, "APPLY", "seocho+01", "http://mold.test/client/api"));
        verifyNoInteractions(agents);
        when(depot.getConfigStringValue(eq(BackupManager.BackupProviderPlugin.key()), any(), any())).thenReturn("ablestack-nas, ablestack-netbackup");
        assertTrue(service.inspect(14L).providerenabled);
    }

    @Test public void endpointRejectsEmbeddedSecretsAndPolicyNamesCannotContainShellSyntax() {
        for (String endpoint : List.of("file:///etc/passwd", "http://user:password@mold.test/client/api", "http://mold.test/client/api?secret=key", "http://mold.test/client/api#fragment")) {
            assertThrows(CloudRuntimeException.class, () -> NetBackupHostConfigurationServiceImpl.validateUrl(endpoint));
        }
        assertThrows(CloudRuntimeException.class, () -> service.configure(14L, "APPLY", "policy;bad", "http://mold.test/client/api"));
        assertThrows(CloudRuntimeException.class, () -> service.configure(14L, "DELETE", "policy", "http://mold.test/client/api"));
    }
}
