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
import java.net.URISyntaxException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import org.apache.cloudstack.backup.ablestackveeam.AblestackVeeamClient;
import org.apache.cloudstack.backup.ablestackveeam.AblestackVeeamRestClient;
import org.apache.cloudstack.backup.ablestackveeam.AblestackVeeamSshClient;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import com.cloud.hypervisor.Hypervisor;
import com.cloud.utils.Pair;
import com.cloud.utils.component.AdapterBase;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VirtualMachine;

import static org.apache.cloudstack.backup.BackupManager.BackupFrameworkEnabled;

/**
 * Ablestack Veeam backup provider for KVM: seeds from Veeam restore points, then incremental NAS backups.
 */
public class AblestackVeeamBackupProvider extends AdapterBase implements BackupProvider, Configurable {

    public static final String PROVIDER_NAME = "ablestack-veeam";
    public static final String DETAIL_VEEAM_RESTORE_POINT_ID = "ablestack.veeam.restore.point.id";
    public static final String DETAIL_VEEAM_VM_NAME = "ablestack.veeam.vm.name";

    public ConfigKey<String> AblestackVeeamUrl = new ConfigKey<>("Advanced", String.class,
            "backup.plugin.ablestack-veeam.url", "https://localhost:9398/api/",
            "The Ablestack Veeam B&R REST API URL.", true, ConfigKey.Scope.Zone, BackupFrameworkEnabled.key());

    public ConfigKey<Integer> AblestackVeeamVersion = new ConfigKey<>("Advanced", Integer.class,
            "backup.plugin.ablestack-veeam.version", "0",
            "Veeam server major version (0 = auto-detect via PowerShell).", true, ConfigKey.Scope.Zone, BackupFrameworkEnabled.key());

    private ConfigKey<String> AblestackVeeamUsername = new ConfigKey<>("Advanced", String.class,
            "backup.plugin.ablestack-veeam.username", "administrator",
            "Veeam B&R username for Ablestack Veeam plugin.", true, ConfigKey.Scope.Zone, BackupFrameworkEnabled.key());

    private ConfigKey<String> AblestackVeeamPassword = new ConfigKey<>("Secure", String.class,
            "backup.plugin.ablestack-veeam.password", "",
            "Veeam B&R password for Ablestack Veeam plugin.", true, ConfigKey.Scope.Zone, BackupFrameworkEnabled.key());

    private ConfigKey<Boolean> AblestackVeeamValidateSsl = new ConfigKey<>("Advanced", Boolean.class,
            "backup.plugin.ablestack-veeam.validate.ssl", "false",
            "Validate SSL when connecting to Veeam REST API.", true, ConfigKey.Scope.Zone, BackupFrameworkEnabled.key());

    private ConfigKey<Integer> AblestackVeeamApiTimeout = new ConfigKey<>("Advanced", Integer.class,
            "backup.plugin.ablestack-veeam.request.timeout", "300",
            "Veeam API request timeout in seconds.", true, ConfigKey.Scope.Zone, BackupFrameworkEnabled.key());

    private ConfigKey<Integer> AblestackVeeamRestoreTimeout = new ConfigKey<>("Advanced", Integer.class,
            "backup.plugin.ablestack-veeam.restore.timeout", "3600",
            "Veeam export/restore operation timeout in seconds.", true, ConfigKey.Scope.Zone, BackupFrameworkEnabled.key());

    private ConfigKey<Integer> AblestackVeeamTaskPollInterval = new ConfigKey<>("Advanced", Integer.class,
            "backup.plugin.ablestack-veeam.task.poll.interval", "5",
            "Veeam task poll interval in seconds.", true, ConfigKey.Scope.Zone, BackupFrameworkEnabled.key());

    private ConfigKey<Integer> AblestackVeeamTaskPollMaxRetry = new ConfigKey<>("Advanced", Integer.class,
            "backup.plugin.ablestack-veeam.task.poll.max.retry", "240",
            "Max retries when polling Veeam tasks.", true, ConfigKey.Scope.Zone, BackupFrameworkEnabled.key());

    public ConfigKey<String> AblestackVeeamStagingPath = new ConfigKey<>("Advanced", String.class,
            "backup.plugin.ablestack-veeam.staging.path", "/var/ablestack-veeam-staging",
            "Shared staging directory on the Veeam server (must be visible to KVM hosts) for disk export before NAS seed import.",
            true, ConfigKey.Scope.Zone, BackupFrameworkEnabled.key());

    public ConfigKey<Boolean> AblestackVeeamUseRestApi = new ConfigKey<>("Advanced", Boolean.class,
            "backup.plugin.ablestack-veeam.use.rest.api", "false",
            "Use the Veeam Backup & Replication REST API (port 9419) for restore point discovery instead of "
                    + "PowerShell-over-SSH. Disk export for seed/restore still uses PowerShell (no REST equivalent).",
            true, ConfigKey.Scope.Zone, BackupFrameworkEnabled.key());

    public ConfigKey<String> AblestackVeeamRestUrl = new ConfigKey<>("Advanced", String.class,
            "backup.plugin.ablestack-veeam.rest.url", "https://localhost:9419/api/",
            "Veeam Backup & Replication REST API base URL (port 9419), used when use.rest.api is true.",
            true, ConfigKey.Scope.Zone, BackupFrameworkEnabled.key());

    public ConfigKey<String> AblestackVeeamRestApiVersion = new ConfigKey<>("Advanced", String.class,
            "backup.plugin.ablestack-veeam.rest.api.version", "1.3-rev1",
            "Veeam Backup & Replication REST API version header (e.g. 1.3-rev1 for v13.0.1).",
            true, ConfigKey.Scope.Zone, BackupFrameworkEnabled.key());

    @Inject
    private AblestackNasBackupProvider nasBackupProvider;

    @Inject
    private BackupManager backupManager;

    protected AblestackVeeamClient getClient(final Long zoneId) {
        try {
            return new AblestackVeeamClient(
                    AblestackVeeamUrl.valueIn(zoneId),
                    AblestackVeeamVersion.valueIn(zoneId),
                    AblestackVeeamUsername.valueIn(zoneId),
                    AblestackVeeamPassword.valueIn(zoneId),
                    AblestackVeeamValidateSsl.valueIn(zoneId),
                    AblestackVeeamApiTimeout.valueIn(zoneId),
                    AblestackVeeamRestoreTimeout.valueIn(zoneId),
                    AblestackVeeamTaskPollInterval.valueIn(zoneId),
                    AblestackVeeamTaskPollMaxRetry.valueIn(zoneId));
        } catch (URISyntaxException e) {
            throw new CloudRuntimeException("Failed to parse Ablestack Veeam API URL: " + e.getMessage());
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            throw new CloudRuntimeException("Failed to build Ablestack Veeam client: " + e.getMessage());
        }
    }

    protected AblestackVeeamRestClient getRestClient(final Long zoneId) {
        try {
            return new AblestackVeeamRestClient(
                    AblestackVeeamRestUrl.valueIn(zoneId),
                    AblestackVeeamRestApiVersion.valueIn(zoneId),
                    AblestackVeeamUsername.valueIn(zoneId),
                    AblestackVeeamPassword.valueIn(zoneId),
                    AblestackVeeamValidateSsl.valueIn(zoneId),
                    AblestackVeeamApiTimeout.valueIn(zoneId));
        } catch (URISyntaxException e) {
            throw new CloudRuntimeException("Failed to parse Ablestack Veeam REST API URL: " + e.getMessage());
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            throw new CloudRuntimeException("Failed to build Ablestack Veeam REST client: " + e.getMessage());
        }
    }

    /**
     * SSH/PowerShell client for disk export. Intentionally does NOT use Enterprise Manager
     * (9398) so that REST-API (9419) deployments do not depend on EM at all. The Veeam SSH
     * host is derived from the REST URL (falling back to the EM URL); credentials reuse the
     * configured Veeam username/password (same as the EM/SSH credentials).
     */
    protected AblestackVeeamSshClient getSshClient(final Long zoneId) {
        String host = extractHost(AblestackVeeamRestUrl.valueIn(zoneId));
        if (StringUtils.isBlank(host)) {
            host = extractHost(AblestackVeeamUrl.valueIn(zoneId));
        }
        return new AblestackVeeamSshClient(host, AblestackVeeamUsername.valueIn(zoneId), AblestackVeeamPassword.valueIn(zoneId));
    }

    private String extractHost(final String url) {
        if (StringUtils.isBlank(url)) {
            return null;
        }
        try {
            return new URI(url).getHost();
        } catch (URISyntaxException e) {
            throw new CloudRuntimeException("Failed to parse Veeam URL for SSH host: " + e.getMessage());
        }
    }

    @Override
    public String getName() {
        return PROVIDER_NAME;
    }

    @Override
    public String getDescription() {
        return "Ablestack Veeam + NAS KVM Backup Plugin";
    }

    @Override
    public List<BackupOffering> listBackupOfferings(Long zoneId) {
        return nasBackupProvider.listBackupOfferings(zoneId);
    }

    @Override
    public boolean isValidProviderOffering(Long zoneId, String uuid) {
        return nasBackupProvider.isValidProviderOffering(zoneId, uuid);
    }

    @Override
    public boolean assignVMToBackupOffering(VirtualMachine vm, BackupOffering backupOffering) {
        if (!Hypervisor.HypervisorType.KVM.equals(vm.getHypervisorType())) {
            throw new CloudRuntimeException("Ablestack Veeam backup provider supports KVM instances only");
        }
        return true;
    }

    @Override
    public boolean removeVMFromBackupOffering(VirtualMachine vm) {
        return true;
    }

    @Override
    public boolean willDeleteBackupsOnOfferingRemoval() {
        return false;
    }

    @Override
    public Pair<Boolean, Backup> takeBackup(VirtualMachine vm, Boolean quiesceVM) {
        if (!nasBackupProvider.hasBackedUpSeed(vm)) {
            final String restorePointId = resolveRestorePointId(vm);
            final String stagingSubDir = String.format("%s/%s", vm.getInstanceName(), System.currentTimeMillis());
            final String stagingPath = String.format("%s/%s", AblestackVeeamStagingPath.valueIn(vm.getDataCenterId()), stagingSubDir);
            logger.info("No NAS seed found for VM [{}], exporting Veeam restore point [{}] to [{}]",
                    vm.getInstanceName(), restorePointId, stagingPath);
            final List<String> stagingDisks = getSshClient(vm.getDataCenterId())
                    .exportRestorePointDisksToStaging(restorePointId, stagingPath);
            final Pair<Boolean, Backup> seedResult = nasBackupProvider.importVeeamBackupSeed(
                    vm, stagingDisks, restorePointId, "vmdk", true);
            if (!seedResult.first()) {
                return seedResult;
            }
            tagBackupAsVeeamSourced(seedResult.second(), restorePointId, vm);
            return seedResult;
        }
        final Pair<Boolean, Backup> result = nasBackupProvider.takeBackup(vm, quiesceVM);
        if (result.second() != null) {
            tagBackupAsVeeamSourced(result.second(), getVmRestorePointDetail(vm), vm);
        }
        return result;
    }

    @Override
    public Pair<Boolean, Backup> takeBackup(VirtualMachine vm, Boolean quiesceVM, Long backupScheduleId) {
        return takeBackup(vm, quiesceVM);
    }

    @Override
    public Pair<Boolean, Backup> importAblestackVeeamBackupSeed(VirtualMachine vm, String veeamRestorePointId,
            List<String> stagingDiskPaths, String sourceFormat, Boolean bootstrapCheckpoint) {
        List<String> staging = stagingDiskPaths;
        if (CollectionUtils.isEmpty(staging)) {
            if (StringUtils.isBlank(veeamRestorePointId)) {
                throw new CloudRuntimeException("Either veeam restore point id or staging disk paths are required");
            }
            final String stagingSubDir = String.format("%s/%s-import", vm.getInstanceName(), System.currentTimeMillis());
            final String stagingPath = String.format("%s/%s", AblestackVeeamStagingPath.valueIn(vm.getDataCenterId()), stagingSubDir);
            staging = getSshClient(vm.getDataCenterId()).exportRestorePointDisksToStaging(veeamRestorePointId, stagingPath);
        }
        final Pair<Boolean, Backup> result = nasBackupProvider.importVeeamBackupSeed(
                vm, staging, veeamRestorePointId, sourceFormat, bootstrapCheckpoint);
        if (result.second() != null) {
            tagBackupAsVeeamSourced(result.second(), veeamRestorePointId, vm);
        }
        return result;
    }

    private void tagBackupAsVeeamSourced(Backup backup, String restorePointId, VirtualMachine vm) {
        if (backup == null) {
            return;
        }
        nasBackupProvider.updateBackupDetail(backup, AblestackNasBackupProvider.DETAIL_BACKUP_SOURCE, PROVIDER_NAME);
        if (StringUtils.isNotBlank(restorePointId)) {
            nasBackupProvider.updateBackupDetail(backup, DETAIL_VEEAM_RESTORE_POINT_ID, restorePointId);
        }
        nasBackupProvider.updateBackupDetail(backup, DETAIL_VEEAM_VM_NAME, vm.getInstanceName());
    }

    private String resolveRestorePointId(VirtualMachine vm) {
        final String fromVm = getVmRestorePointDetail(vm);
        if (StringUtils.isNotBlank(fromVm)) {
            return fromVm;
        }
        final List<Backup.RestorePoint> points = listRestorePoints(vm);
        if (CollectionUtils.isEmpty(points)) {
            throw new CloudRuntimeException(String.format(
                    "No Veeam restore point found for VM [%s]. Set detail [%s] or import a seed via API.",
                    vm.getInstanceName(), DETAIL_VEEAM_RESTORE_POINT_ID));
        }
        return points.get(0).getId();
    }

    private String getVmRestorePointDetail(VirtualMachine vm) {
        Map<String, String> details = backupManager.getBackupDetailsFromVM(vm);
        return details != null ? details.get(DETAIL_VEEAM_RESTORE_POINT_ID) : null;
    }

    @Override
    public boolean deleteBackup(Backup backup, boolean forced) {
        return nasBackupProvider.deleteBackup(backup, forced);
    }

    @Override
    public Pair<Boolean, String> restoreBackupToVM(VirtualMachine vm, Backup backup, String hostIp, String dataStoreUuid) {
        try {
            final Pair<Boolean, String> local = nasBackupProvider.restoreBackupToVM(vm, backup, hostIp, dataStoreUuid);
            if (local != null && Boolean.TRUE.equals(local.first())) {
                return local;
            }
            logger.warn("Local NAS restore did not succeed for backup [{}]; attempting Veeam chain fallback.", backup.getUuid());
        } catch (Exception e) {
            logger.warn(String.format("Local NAS restore failed for backup [%s]; attempting Veeam chain fallback: %s",
                    backup.getUuid(), e.getMessage()));
        }
        final Backup seed = rebuildBackupFromVeeam(vm, backup);
        return nasBackupProvider.restoreBackupToVM(vm, seed, hostIp, dataStoreUuid);
    }

    @Override
    public Pair<Boolean, String> restoreBackupToVM(Long backupId, String vmName) {
        // This entry point only carries ids; the Veeam fallback needs the full VM
        // and Backup objects (restore-point detail, zone, staging path). The
        // object-based overloads above provide the Veeam chain fallback.
        return nasBackupProvider.restoreBackupToVM(backupId, vmName);
    }

    @Override
    public boolean restoreVMFromBackup(VirtualMachine vm, Backup backup) {
        try {
            if (nasBackupProvider.restoreVMFromBackup(vm, backup)) {
                return true;
            }
            logger.warn("Local NAS restore did not succeed for backup [{}]; attempting Veeam chain fallback.", backup.getUuid());
        } catch (Exception e) {
            logger.warn(String.format("Local NAS restore failed for backup [%s]; attempting Veeam chain fallback: %s",
                    backup.getUuid(), e.getMessage()));
        }
        final Backup seed = rebuildBackupFromVeeam(vm, backup);
        return nasBackupProvider.restoreVMFromBackup(vm, seed);
    }

    /**
     * Veeam chain restore fallback used when the local qcow2 chain cannot satisfy a
     * restore (e.g. the chain is incomplete because the authoritative data lives in
     * Veeam). We re-export the exact Veeam restore point recorded on the backup,
     * re-import it as a fresh NAS seed (a self-contained full point that does not
     * depend on the local chain) and let the caller restore from that seed.
     *
     * <p>The happy path (local restore succeeds) never reaches here, so this adds no
     * regression risk to working local restores.</p>
     */
    private Backup rebuildBackupFromVeeam(VirtualMachine vm, Backup backup) {
        final String restorePointId = getVeeamRestorePointId(backup);
        if (StringUtils.isBlank(restorePointId)) {
            throw new CloudRuntimeException(String.format(
                    "Local restore failed for backup [%s] and no Veeam restore point id is recorded; cannot fall back to Veeam.",
                    backup.getUuid()));
        }
        logger.info("Rebuilding backup [{}] from Veeam restore point [{}] for restore.", backup.getUuid(), restorePointId);
        final String stagingSubDir = String.format("restore-%s/%s", vm.getInstanceName(), System.currentTimeMillis());
        final String stagingPath = String.format("%s/%s", AblestackVeeamStagingPath.valueIn(vm.getDataCenterId()), stagingSubDir);
        final List<String> stagingDisks = getSshClient(vm.getDataCenterId())
                .exportRestorePointDisksToStaging(restorePointId, stagingPath);
        final Pair<Boolean, Backup> seed = nasBackupProvider.importVeeamBackupSeed(
                vm, stagingDisks, restorePointId, "vmdk", true);
        if (seed == null || !Boolean.TRUE.equals(seed.first()) || seed.second() == null) {
            throw new CloudRuntimeException(String.format(
                    "Failed to rebuild backup [%s] from Veeam restore point [%s].", backup.getUuid(), restorePointId));
        }
        return seed.second();
    }

    private String getVeeamRestorePointId(Backup backup) {
        final Map<String, String> details = backup != null ? backup.getDetails() : null;
        if (details == null) {
            return null;
        }
        final String fromProvider = details.get(DETAIL_VEEAM_RESTORE_POINT_ID);
        if (StringUtils.isNotBlank(fromProvider)) {
            return fromProvider;
        }
        return details.get(AblestackNasBackupProvider.DETAIL_VEEAM_RESTORE_POINT_ID);
    }

    @Override
    public Pair<Boolean, String> restoreBackedUpVolume(Backup backup, Backup.VolumeInfo backupVolumeInfo, String hostIp,
            String dataStoreUuid, Pair<String, VirtualMachine.State> vmNameAndState) {
        return nasBackupProvider.restoreBackedUpVolume(backup, backupVolumeInfo, hostIp, dataStoreUuid, vmNameAndState);
    }

    @Override
    public void syncBackupMetrics(Long zoneId) {
        nasBackupProvider.syncBackupMetrics(zoneId);
    }

    @Override
    public List<Backup.RestorePoint> listRestorePoints(VirtualMachine vm) {
        final String veeamVmName = getVeeamSourceVmName(vm);
        if (Boolean.TRUE.equals(AblestackVeeamUseRestApi.valueIn(vm.getDataCenterId()))) {
            return getRestClient(vm.getDataCenterId()).listRestorePointsForVm(veeamVmName);
        }
        final AblestackVeeamClient client = getClient(vm.getDataCenterId());
        client.syncBackupRepository();
        return client.listRestorePointsForVmDisplayName(veeamVmName);
    }

    private String getVeeamSourceVmName(VirtualMachine vm) {
        Map<String, String> details = backupManager.getBackupDetailsFromVM(vm);
        if (details != null && StringUtils.isNotBlank(details.get(DETAIL_VEEAM_VM_NAME))) {
            return details.get(DETAIL_VEEAM_VM_NAME);
        }
        return vm.getInstanceName();
    }

    @Override
    public Backup createNewBackupEntryForRestorePoint(Backup.RestorePoint restorePoint, VirtualMachine vm) {
        throw new CloudRuntimeException("Use importVeeamNasBackupSeed API to register a Veeam restore point as NAS seed");
    }

    @Override
    public boolean supportsInstanceFromBackup() {
        return nasBackupProvider.supportsInstanceFromBackup();
    }

    @Override
    public Pair<Long, Long> getBackupStorageStats(Long zoneId) {
        return nasBackupProvider.getBackupStorageStats(zoneId);
    }

    @Override
    public void syncBackupStorageStats(Long zoneId) {
        nasBackupProvider.syncBackupStorageStats(zoneId);
    }

    @Override
    public void syncBackups(VirtualMachine vm) {
        nasBackupProvider.syncBackups(vm);
    }

    /**
     * Backups for this provider are NAS-managed (delegated to nasBackupProvider).
     * Veeam restore points returned by {@link #listRestorePoints(VirtualMachine)} are
     * a seed source only, not the authoritative backup list. The generic out-of-band
     * sync reconciles DB backups against listRestorePoints() and DELETES any DB backup
     * that has no matching Veeam restore point, which would wrongly wipe NAS/agent backups
     * (e.g. when Veeam Enterprise Manager reports no restore points for the VM display name).
     * Opt out so those backups are not destroyed; NAS sync is handled via syncBackups(vm).
     */
    @Override
    public boolean supportsOutOfBandBackupSync() {
        return false;
    }

    @Override
    public boolean checkBackupAgent(Long zoneId) {
        return true;
    }

    @Override
    public boolean installBackupAgent(Long zoneId) {
        return true;
    }

    @Override
    public boolean importBackupPlan(Long zoneId, String retentionPeriod, String externalId) {
        return true;
    }

    @Override
    public boolean updateBackupPlan(Long zoneId, String retentionPeriod, String externalId) {
        return true;
    }

    @Override
    public Boolean crossZoneInstanceCreationEnabled(BackupOffering backupOffering) {
        return nasBackupProvider.crossZoneInstanceCreationEnabled(backupOffering);
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey[]{
                AblestackVeeamUrl,
                AblestackVeeamVersion,
                AblestackVeeamUsername,
                AblestackVeeamPassword,
                AblestackVeeamValidateSsl,
                AblestackVeeamApiTimeout,
                AblestackVeeamRestoreTimeout,
                AblestackVeeamTaskPollInterval,
                AblestackVeeamTaskPollMaxRetry,
                AblestackVeeamStagingPath,
                AblestackVeeamUseRestApi,
                AblestackVeeamRestUrl,
                AblestackVeeamRestApiVersion
        };
    }

    @Override
    public String getConfigComponentName() {
        return BackupService.class.getSimpleName();
    }

    @Override
    public boolean supportsVolumeLevelChainState() {
        return nasBackupProvider.supportsVolumeLevelChainState();
    }

    @Override
    public boolean supportsRestorePlan() {
        return nasBackupProvider.supportsRestorePlan();
    }

    @Override
    public boolean supportsRestoreChainValidation() {
        return nasBackupProvider.supportsRestoreChainValidation();
    }
}
