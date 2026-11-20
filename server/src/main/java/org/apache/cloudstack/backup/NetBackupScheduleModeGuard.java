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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import javax.inject.Inject;
import javax.inject.Provider;

import org.apache.cloudstack.backup.dao.BackupDao;
import org.apache.cloudstack.backup.dao.BackupDetailsDao;
import org.apache.cloudstack.backup.dao.BackupOfferingDao;
import org.apache.cloudstack.api.command.user.backup.CreateNetBackupCmd;
import org.apache.cloudstack.framework.jobs.dao.AsyncJobDao;
import org.apache.commons.lang3.StringUtils;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.Gson;

/** Serialize NetBackup dispatch and global mode changes; history and schedules are never rewritten. */
public class NetBackupScheduleModeGuard extends ManagerBase {
    private static final String LOCK = "backup.netbackup.schedule.mode";
    private static final Set<Backup.Status> ACTIVE = Set.of(Backup.Status.Allocated, Backup.Status.Queued,
            Backup.Status.BackingUp, Backup.Status.ReadyForImageTransfer, Backup.Status.FinalizingImageTransfer, Backup.Status.Restoring);
    private static final Set<String> TERMINAL = Set.of("COMPLETED", "FAILED", "CANCELED", "CANCELLED", "INTERRUPTED");
    @Inject private BackupDao backupDao;
    @Inject private BackupOfferingDao offeringDao;
    @Inject private BackupDetailsDao detailsDao;
    @Inject private HostDao hostDao;
    @Inject private AgentManager agentManager;
    @Inject private Provider<BackupManager> backupManager;
    @Inject private AsyncJobDao asyncJobDao;
    @Inject private Provider<ThirdPartyBackupVolumeService> volumeService;

    public static boolean isSetting(String name) { return BackupManager.NetBackupVmScheduleEnabled.key().equals(name); }

    private static CloudRuntimeException blocked(String code, String detail) {
        return new CloudRuntimeException("NETBACKUP_VM_SCHEDULE_MODE_" + code + ": " + detail);
    }

    public Lease acquireExecution() { return acquire(LOCK); }

    private Lease acquire(String name) {
        GlobalLock lock = GlobalLock.getInternLock(name);
        if (!lock.lock(5)) {
            lock.releaseRef();
            throw blocked("BUSY", "Retry after the current operation finishes");
        }
        return new Lease(lock);
    }

    public <T> T update(String value, Supplier<T> save) {
        if (!("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value))) {
            throw blocked("INVALID_VALUE", "Use true or false");
        }
        return withIdleHosts(() -> BackupManager.NetBackupVmScheduleEnabled.value() != Boolean.parseBoolean(value),
                false, unused -> save.get());
    }

    public <T> T withIdleHosts(Function<List<HostVO>, T> operation) {
        return withIdleHosts(() -> true, true, operation);
    }

    private <T> T withIdleHosts(BooleanSupplier validate, boolean hookTimeoutChange, Function<List<HostVO>, T> operation) {
        try (Lease mode = acquireExecution(); Lease configuration = acquire(ThirdPartyBackupStagingService.CONFIGURATION_LOCK);
                ThirdPartyBackupVolumeService.Lease placement = hookTimeoutChange ? volumeService.get().acquireRestorePlacement() : null) {
            if (!validate.getAsBoolean()) { return operation.apply(List.of()); }
            // The async job exists before dispatch validation and remains pending until
            // the provider has persisted its backup intent. Do not serialize slow Host
            // preparation across every NetBackup Host just to protect a mode change.
            if (asyncJobDao.countPendingJobs(null, CreateNetBackupCmd.class.getName()) > 0) {
                throw blocked("BACKUP_BUSY", "NetBackup backup submission");
            }
            Map<Long, BackupOfferingVO> offerings = new HashMap<>();
            Set<Long> zones = new HashSet<>();
            for (BackupOfferingVO offering : offeringDao.listAllIncludingRemoved()) {
                offerings.put(offering.getId(), offering);
                if (BackupProviderNameUtils.isNetBackupFamily(offering.getProvider())) { zones.add(offering.getZoneId()); }
            }
            checkOperations(offerings);
            List<HostVO> configuredHosts = new ArrayList<>();
            Map<Long, List<String>> policies = new HashMap<>();
            for (HostVO host : hostDao.listByType(Host.Type.Routing)) {
                if (host.getRemoved() != null || host.getHypervisorType() != HypervisorType.KVM) { continue; }
                if (!zones.contains(host.getDataCenterId()) && !BackupProviderNameUtils.isConfigured(
                        BackupManager.BackupProviderPlugin.valueIn(host.getDataCenterId()), BackupProviderNameUtils.ABLESTACK_NETBACKUP)) { continue; }
                if (host.getStatus() != Status.Up) { throw blocked("UNCONFIRMED", host.getName()); }
                HostState status = inspect(host);
                if (status.state == null || Set.of("RUNNING", "UNCONFIRMED", "UNREACHABLE").contains(status.state)) {
                    throw blocked("HOST_BUSY", host.getName());
                }
                if (!Boolean.TRUE.equals(status.policyinventorycomplete) || status.configuredpolicies == null
                        || (status.configured && status.configuredpolicies.isEmpty())
                        || status.configuredpolicies.stream().anyMatch(StringUtils::isBlank)) {
                    throw blocked("UNCONFIRMED", host.getName());
                }
                if (!hookTimeoutChange && status.hooktimeoutpending) {
                    throw blocked("UNCONFIRMED", host.getName() + "; resave backup.plugin.netbackup.hook.timeout to reconcile Host settings");
                }
                if (hookTimeoutChange) {
                    if (!status.configured || !status.clientinstalled) { continue; }
                    if (!Set.of("COMPLETED", "CHECKED", "POLICY_PATH_MISMATCH").contains(status.state)) {
                        throw blocked("HOST_BUSY", host.getName());
                    }
                    configuredHosts.add(host);
                }
                policies.computeIfAbsent(host.getDataCenterId(), unused -> new ArrayList<>()).addAll(status.configuredpolicies);
            }
            for (var entry : policies.entrySet()) {
                if (!entry.getValue().isEmpty()) {
                    BackupProvider provider = backupManager.get().getBackupProvider(BackupProviderNameUtils.ABLESTACK_NETBACKUP);
                    if (provider == null) { throw blocked("UNCONFIRMED", "NetBackup provider"); }
                    provider.validateVmScheduleModeChange(entry.getKey(), entry.getValue());
                }
            }
            return operation.apply(configuredHosts);
        }
    }

    private HostState inspect(HostVO host) {
        try {
            NetBackupHostConfigurationCommand command = new NetBackupHostConfigurationCommand("STATUS", null);
            command.setWait(30);
            Answer answer = agentManager.send(host.getId(), command);
            if (answer == null || !answer.getResult()) { throw new IllegalStateException("Host response unavailable"); }
            HostState state = new Gson().fromJson(answer.getDetails(), HostState.class);
            if (state == null) { throw new IllegalStateException("Host response missing"); }
            return state;
        } catch (Exception error) { throw blocked("UNCONFIRMED", host.getName()); }
    }

    private void checkOperations(Map<Long, BackupOfferingVO> offerings) {
        for (BackupVO backup : backupDao.listAllIncludingRemoved()) {
            BackupOfferingVO offering = offerings.get(backup.getBackupOfferingId());
            if (offering == null || !BackupProviderNameUtils.isNetBackupFamily(offering.getProvider())) { continue; }
            backupDao.loadDetails(backup);
            // Legacy deletion did not always settle the historical status. Keep checking
            // its cleanup/restore markers and admissions, but do not treat that status as
            // live work. Current volume/start ownership must still be checked normally.
            boolean removedLegacyHistory = backup.getRemoved() != null
                    && StringUtils.isBlank(backup.getDetail(ThirdPartyBackupManifest.MODE_KEY))
                    && StringUtils.isBlank(backup.getDetail(ThirdPartyBackupStart.DETAIL_KEY));
            if (!removedLegacyHistory && backup.getStatus() == null) { throw blocked("UNCONFIRMED", backup.getUuid()); }
            if (!removedLegacyHistory && ACTIVE.contains(backup.getStatus())) { throw blocked("BACKUP_BUSY", backup.getUuid()); }
            String restore = backup.getDetail(AblestackBackupFrameworkUtils.RESTORE_JOB_STATE_DETAIL);
            if ((StringUtils.isNotBlank(restore) && !TERMINAL.contains(restore))
                    || (StringUtils.isBlank(restore) && StringUtils.isNotBlank(backup.getDetail(AblestackBackupFrameworkUtils.RESTORE_JOB_ID_DETAIL)))
                    || StringUtils.isNotBlank(backup.getDetail(AblestackBackupFrameworkUtils.RESTORE_JOB_CLEANUP_ID_DETAIL))
                    || StringUtils.isNotBlank(backup.getDetail(AblestackRestoreInstanceCleanup.PENDING_KEY))
                    || (Boolean.parseBoolean(backup.getDetail(AblestackBackupFrameworkUtils.BACKUP_CANCEL_REQUESTED_DETAIL))
                        && !"COMPLETED".equals(backup.getDetail(AblestackBackupFrameworkUtils.BACKUP_CLEANUP_STATE_DETAIL)))
                    || BackupDeletionService.isPending(backup)
                    || StringUtils.isNotBlank(backup.getDetail(ThirdPartyBackupManifest.DELETE_PROGRESS_KEY))) {
                throw blocked("CLEANUP_PENDING", backup.getUuid());
            }
            for (String name : List.of(ThirdPartyBackupManifest.CLEANUP_STATE_KEY, ThirdPartyBackupManifest.SOURCE_CLEANUP_STATE_KEY,
                    ThirdPartyBackupManifest.JOB_CLEANUP_STATE_KEY, ThirdPartyBackupManifest.FINALIZATION_STATE_KEY,
                    ThirdPartyBackupRestore.CLEANUP_STATE_KEY, AblestackBackupFrameworkUtils.BACKUP_CLEANUP_STATE_DETAIL)) {
                String state = backup.getDetail(name);
                if (StringUtils.isNotBlank(state) && !Set.of("COMPLETED", "NOT_REQUIRED", "NONE").contains(state)) {
                    throw blocked("CLEANUP_PENDING", backup.getUuid());
                }
            }
        }
        for (String name : List.of(ThirdPartyBackupAdmission.BACKUP_KEY, ThirdPartyBackupAdmission.RESTORE_KEY)) {
            for (BackupDetailVO detail : detailsDao.findDetails(name)) {
                BackupVO backup = backupDao.findByIdIncludingRemoved(detail.getResourceId());
                BackupOfferingVO offering = backup == null ? null : offerings.get(backup.getBackupOfferingId());
                if (offering != null && !BackupProviderNameUtils.isNetBackupFamily(offering.getProvider())) { continue; }
                ThirdPartyBackupAdmission.Entry entry;
                try { entry = new Gson().fromJson(detail.getValue(), ThirdPartyBackupAdmission.Entry.class); }
                catch (RuntimeException error) { throw blocked("UNCONFIRMED", "Staging admission"); }
                if (entry == null || !"RELEASED".equals(entry.state)) { throw blocked("BACKUP_BUSY", "Staging admission"); }
            }
        }
    }

    private static class HostState {
        String state;
        boolean configured;
        boolean clientinstalled;
        boolean hooktimeoutpending;
        Boolean policyinventorycomplete;
        List<String> configuredpolicies;
    }

    public static final class Lease implements AutoCloseable {
        private final GlobalLock lock;
        private Lease(GlobalLock lock) { this.lock = lock; }
        @Override public void close() { try { lock.unlock(); } finally { lock.releaseRef(); } }
    }
}
