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

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.inject.Inject;
import org.apache.cloudstack.acl.apikeypair.ApiKeyPair;
import org.apache.cloudstack.api.response.NetBackupHostConfigurationResponse;
import org.apache.cloudstack.backup.dao.BackupDetailsDao;
import org.apache.cloudstack.backup.dao.BackupOfferingDao;
import org.apache.cloudstack.backup.dao.BackupScheduleDao;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.framework.config.ConfigDepot;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.commons.lang3.StringUtils;
import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.event.ActionEvent;
import com.cloud.event.EventTypes;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.user.AccountService;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.dao.VMInstanceDao;
import com.google.gson.Gson;

/** Control-plane setup only. Host installation has a durable receipt and never rewrites shared settings. */
public class NetBackupHostConfigurationServiceImpl implements NetBackupHostConfigurationService {
    @Inject private HostDao hostDao;
    @Inject private DataCenterDao dataCenterDao;
    @Inject private ClusterDao clusterDao;
    @Inject private AgentManager agentManager;
    @Inject private AccountService accountService;
    @Inject private ConfigDepot configDepot;
    @Inject private VMInstanceDao vmInstanceDao;
    @Inject private BackupOfferingDao backupOfferingDao;
    @Inject private BackupScheduleDao backupScheduleDao;
    @Inject private BackupDetailsDao backupDetailsDao;

    private HostVO host(Long id) {
        if (!accountService.isRootAdmin(CallContext.current().getCallingAccount().getId())) {
            throw new CloudRuntimeException("Only root administrators can configure NetBackup Hosts");
        }
        HostVO host = id == null ? null : hostDao.findById(id);
        if (host == null || host.getRemoved() != null || host.getType() != Host.Type.Routing
                || host.getHypervisorType() != HypervisorType.KVM) {
            throw new CloudRuntimeException("Select a registered KVM routing Host");
        }
        return host;
    }

    private String zoneValue(String name, HostVO host) {
        ConfigKey<?> key = configDepot.get(name);
        return key == null ? "" : StringUtils.defaultString((String) key.valueIn(host.getDataCenterId()));
    }

    private String source(String name, ConfigKey.Scope scope, Long id) {
        return configDepot.getConfigStringValue(name, scope, id) == null ? ConfigKey.Scope.Global.name() : scope.name();
    }

    private ApiKeyPair credentials() {
        return accountService.getLatestUserKeyPair(CallContext.current().getCallingUserId());
    }

    private void common(NetBackupHostConfigurationResponse value, HostVO host) {
        value.hostid = host.getUuid();
        value.hostname = host.getName();
        value.hoststate = host.getStatus().toString();
        value.netbackupurl = zoneValue("backup.plugin.netbackup.url", host);
        String netbackupKey = zoneValue("backup.plugin.netbackup.apikey", host);
        value.credentialsconfigured = StringUtils.isNotBlank(netbackupKey)
                && !netbackupKey.equals(configDepot.get("backup.plugin.netbackup.apikey").defaultValue());
        ApiKeyPair key = credentials();
        value.moldcredentialsavailable = key != null && StringUtils.isNoneBlank(key.getApiKey(), key.getSecretKey());
        value.frameworkglobalenabled = BackupManager.BackupFrameworkEnabled.value();
        value.frameworkzoneenabled = BackupManager.BackupFrameworkEnabled.valueIn(host.getDataCenterId());
        value.frameworkenabled = value.frameworkglobalenabled && value.frameworkzoneenabled;
        value.settingsource = new java.util.HashMap<>();
        for (String name : java.util.List.of(BackupManager.BackupFrameworkEnabled.key(), BackupManager.BackupProviderPlugin.key(),
                "backup.plugin.netbackup.url", "backup.plugin.netbackup.apikey")) {
            value.settingsource.put(name, source(name, ConfigKey.Scope.Zone, host.getDataCenterId()));
        }
        value.settingsource.put(BackupManager.KvmIncrementalBackup.key(), source(BackupManager.KvmIncrementalBackup.key(), ConfigKey.Scope.Cluster, host.getClusterId()));
        value.providerenabled = BackupProviderNameUtils.isConfigured(zoneValue(BackupManager.BackupProviderPlugin.key(), host),
                BackupProviderNameUtils.ABLESTACK_NETBACKUP);
        value.stagingenabled = BackupManager.ThirdPartyStagingEnable.value();
        value.vmschedulebackup = BackupManager.NetBackupVmScheduleEnabled.value();
        value.incrementalbackup = BackupManager.KvmIncrementalBackup.valueIn(host.getClusterId());
        value.backupchainsize = BackupManager.KvmBackupChainSize.value();
        String stagingRoot = BackupManager.ThirdPartyStagingRootPath.value();
        value.stagingpath = StringUtils.isBlank(stagingRoot) ? null : StringUtils.removeEnd(stagingRoot, "/") + "/ablestack-netbackup";
        String expectedBootstrap = value.stagingpath == null || StringUtils.isBlank(value.policyname) ? null : value.stagingpath + "/bootstrap/" + value.policyname;
        if (!java.util.Objects.equals(expectedBootstrap, value.bootstrappath)) { value.bootstrappresent = false; }
        value.bootstrappath = expectedBootstrap;
        value.policypathmatched = expectedBootstrap != null && value.checkedat != null && value.policyselections != null
                && value.policyselections.equals(java.util.List.of(expectedBootstrap));
        BackupOffering registered = backupOfferingDao.findByExternalId("netbackup", host.getDataCenterId());
        value.backupofferingid = registered != null && BackupProviderNameUtils.isNetBackupFamily(registered.getProvider()) ? registered.getUuid() : null;
        value.linkready = expectedBootstrap != null && value.frameworkenabled && value.providerenabled && value.stagingenabled && value.configured
                && value.hooksready && value.timeractive && value.policypathmatched && value.bootstrappresent
                && value.backupofferingid != null && expectedBootstrap.equals(value.configuredbootstrappath)
                && value.netbackupurl.equals(value.inspectednetbackupurl)
                && !Set.of("FAILED", "UNCONFIRMED", "RUNNING", "UNREACHABLE").contains(StringUtils.defaultString(value.state));
        value.virtualmachine = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        var vms = new ArrayList<>(vmInstanceDao.listByHostId(host.getId()));
        vms.addAll(vmInstanceDao.listByLastHostIdAndStates(host.getId(), VirtualMachine.State.Stopped));
        for (VMInstanceVO vm : vms) {
            if (vm.getRemoved() != null || vm.getType() != VirtualMachine.Type.User
                    || !(vm.getState() == VirtualMachine.State.Running || vm.getState() == VirtualMachine.State.Stopped) || !seen.add(vm.getId()) || vm.getBackupOfferingId() == null) { continue; }
            BackupOffering offering = backupOfferingDao.findById(vm.getBackupOfferingId());
            if (offering == null || !BackupProviderNameUtils.isNetBackupFamily(offering.getProvider())) { continue; }
            if (StringUtils.isNotBlank(value.policyname) && !"netbackup".equalsIgnoreCase(offering.getExternalId())
                    && !value.policyname.equalsIgnoreCase(offering.getExternalId()) && !value.policyname.equalsIgnoreCase(offering.getName())) { continue; }
            var target = new NetBackupHostConfigurationResponse.Target();
            target.id = vm.getUuid();
            target.name = vm.getName();
            target.instancename = vm.getInstanceName();
            target.state = vm.getState().toString();
            target.hasschedule = !backupScheduleDao.listByVM(vm.getId()).isEmpty();
            value.virtualmachine.add(target);
        }
    }

    @Override public NetBackupHostConfigurationResponse inspect(Long hostId) {
        HostVO host = host(hostId);
        NetBackupHostConfigurationResponse value = new NetBackupHostConfigurationResponse();
        value.state = "UNREACHABLE";
        common(value, host);
        if (!value.providerenabled) {
            value.state = "PROVIDER_DISABLED";
            value.message = "Enable ablestack-netbackup in this Zone's effective backup provider setting before preparing this Host";
            return value;
        }
        if (host.getStatus() == Status.Up) {
            try { value = send(host, new NetBackupHostConfigurationCommand("STATUS", null)); }
            catch (CloudRuntimeException error) { value.message = error.getMessage(); }
        } else { value.message = "Connect the KVM Host before inspecting its configuration"; }
        common(value, host);
        return value;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_HOST_BACKUP_CONFIGURE, eventDescription = "configuring NetBackup Host", async = true)
    public NetBackupHostConfigurationResponse configure(Long hostId, String action, String policyName, String moldUrl) {
        HostVO host = host(hostId);
        if (!Set.of("CHECK", "APPLY").contains(StringUtils.defaultString(action))) {
            throw new CloudRuntimeException("Choose CHECK or APPLY");
        }
        if (!NetBackupHostConfigurationService.isAvailable(host.getType(), host.getHypervisorType(),
                zoneValue(BackupManager.BackupProviderPlugin.key(), host))) {
            throw new CloudRuntimeException("NetBackup Host configuration requires ablestack-netbackup in this Zone's effective backup provider setting");
        }
        if (host.getStatus() != Status.Up) { throw new CloudRuntimeException("The KVM Host must be connected"); }
        if (policyName == null || policyName.length() > 128 || !policyName.matches("[A-Za-z0-9_+][A-Za-z0-9._+\\-]*") || policyName.endsWith(".")) {
            throw new CloudRuntimeException("Specify a valid existing NetBackup policy name (maximum 128 characters)");
        }
        validateUrl(moldUrl);
        NetBackupHostConfigurationResponse settings = new NetBackupHostConfigurationResponse();
        common(settings, host);
        if (!settings.frameworkenabled || !settings.providerenabled || !settings.stagingenabled) {
            throw new CloudRuntimeException("Enable the backup framework, Zone NetBackup provider and common staging first; restart Management if required");
        }
        if (StringUtils.isBlank(settings.netbackupurl) || !settings.credentialsconfigured) {
            throw new CloudRuntimeException("Configure the NetBackup URL and API key in Global settings or this Zone's overrides first");
        }
        validateUrl(settings.netbackupurl);
        ApiKeyPair key = credentials();
        if (!settings.moldcredentialsavailable) {
            throw new CloudRuntimeException("Register an API key pair for this administrator before preparing Host integration");
        }
        GlobalLock lock = GlobalLock.getInternLock(ThirdPartyBackupStagingService.CONFIGURATION_LOCK);
        try {
            if (!lock.lock(5)) { throw new CloudRuntimeException("Staging settings are being changed; retry shortly"); }
            try {
                if ("APPLY".equals(action)) { requireIdle(host); }
                Map<String, Object> payload = new java.util.HashMap<>();
                payload.put("action", action);
                payload.put("operationid", UUID.randomUUID().toString());
                payload.put("hostid", host.getUuid());
                payload.put("zoneid", dataCenterDao.findById(host.getDataCenterId()).getUuid());
                payload.put("clusterid", clusterDao.findById(host.getClusterId()).getUuid());
                payload.put("policyname", policyName);
                payload.put("moldurl", moldUrl);
                payload.put("adminapikey", key.getApiKey());
                payload.put("adminsecretkey", key.getSecretKey());
                payload.put("netbackupurl", settings.netbackupurl);
                payload.put("netbackupapikey", zoneValue("backup.plugin.netbackup.apikey", host));
                payload.put("vmschedulebackup", settings.vmschedulebackup);
                payload.put("incrementalbackup", settings.incrementalbackup);
                NetBackupHostConfigurationResponse value = send(host, new NetBackupHostConfigurationCommand(action, new Gson().toJson(payload)));
                common(value, host);
                return value;
            } finally { lock.unlock(); }
        } finally { lock.releaseRef(); }
    }

    private void requireIdle(HostVO host) {
        for (String name : java.util.List.of(ThirdPartyBackupAdmission.BACKUP_KEY, ThirdPartyBackupAdmission.RESTORE_KEY)) {
            for (var detail : backupDetailsDao.findDetails(name)) {
                ThirdPartyBackupAdmission.Entry entry = new Gson().fromJson(detail.getValue(), ThirdPartyBackupAdmission.Entry.class);
                if (entry == null || (entry.hostId == host.getId() && !"RELEASED".equals(entry.state))) {
                    throw new CloudRuntimeException("Finish queued, active and unconfirmed staging work on this Host before configuration");
                }
            }
        }
    }

    private NetBackupHostConfigurationResponse send(HostVO host, NetBackupHostConfigurationCommand command) {
        try {
            Answer answer = agentManager.send(host.getId(), command);
            if (answer == null || !answer.getResult()) {
                throw new CloudRuntimeException(answer == null ? "Host configuration response is unconfirmed; refresh its status" : answer.getDetails());
            }
            NetBackupHostConfigurationResponse value = new Gson().fromJson(answer.getDetails(), NetBackupHostConfigurationResponse.class);
            if (value == null || StringUtils.isBlank(value.state)) { throw new CloudRuntimeException("Invalid Host configuration receipt"); }
            value.setObjectName("netbackuphostconfiguration");
            return value;
        } catch (com.cloud.exception.AgentUnavailableException | com.cloud.exception.OperationTimedoutException error) {
            throw new CloudRuntimeException("Host configuration response is unconfirmed; refresh its status", error);
        }
    }

    static void validateUrl(String raw) {
        try {
            URI value = URI.create(raw);
            if (!("http".equalsIgnoreCase(value.getScheme()) || "https".equalsIgnoreCase(value.getScheme()))
                    || StringUtils.isBlank(value.getHost()) || value.getRawUserInfo() != null || value.getRawQuery() != null || value.getRawFragment() != null
                    || raw.chars().anyMatch(character -> character < 32 || character == 127)) { throw new IllegalArgumentException(); }
        } catch (Exception error) { throw new CloudRuntimeException("Use an HTTP(S) API endpoint without credentials, query parameters or fragments"); }
    }
}
