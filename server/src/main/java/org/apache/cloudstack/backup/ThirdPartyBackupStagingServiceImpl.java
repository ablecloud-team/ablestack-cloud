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

import java.math.BigInteger;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import javax.inject.Inject;

import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.backup.dao.BackupDao;
import org.apache.cloudstack.backup.dao.BackupDetailsDao;
import org.apache.commons.lang3.StringUtils;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.resource.ResourceState;
import com.cloud.storage.Storage.StoragePoolType;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.Gson;

/** Activation and runtime validation share the same host-side staging preparation command. */
public class ThirdPartyBackupStagingServiceImpl extends ManagerBase implements ThirdPartyBackupStagingService {
    private static final int VALIDATION_TIMEOUT_SECONDS = 15;
    private static final long VALIDATION_RETRY_DELAY_MS = 30_000L;
    private final Map<String, ValidationFailure> cleanupValidationFailures = new ConcurrentHashMap<>();
    private final Map<String, ValidationFailure> backupStartFailures = new ConcurrentHashMap<>();
    private final Map<String, ValidationFailure> sourceCheckFailures = new ConcurrentHashMap<>();
    private Clock validationClock = Clock.systemUTC();

    private static class ValidationFailure {
        final long retryAt;
        final String reason;

        ValidationFailure(long retryAt, String reason) {
            this.retryAt = retryAt;
            this.reason = reason;
        }
    }

    @Inject
    private ConfigurationDao configurationDao;
    @Inject
    private HostDao hostDao;
    @Inject
    private VolumeDao volumeDao;
    @Inject
    private AgentManager agentManager;
    @Inject
    private BackupDao backupDao;
    @Inject
    private BackupDetailsDao backupDetailsDao;

    @Override
    public <T> T updateConfiguration(String name, String value, Supplier<T> saveConfiguration) {
        GlobalLock lock = GlobalLock.getInternLock(CONFIGURATION_LOCK);
        boolean locked = false;
        try {
            locked = lock.lock(10);
            if (!locked) {
                throw new CloudRuntimeException("Another staging configuration update is in progress. Retry after it completes.");
            }
            validateSetting(name, value);
            if (BackupManager.ThirdPartyStagingEnable.key().equals(name)) {
                if (Boolean.parseBoolean(StringUtils.trimToEmpty(value))) {
                    validateActivation();
                    preserveRecordedConfigurations(false);
                }
            } else if (!isAdmissionSetting(name) && isEnabled() && !StringUtils.equals(readValue(name), value)) {
                throw new InvalidParameterValueException(
                        "Disable backup.thirdparty.staging.enable before changing staging settings, then enable it again to validate the new configuration.");
            }
            if (isLocationSetting(name) && !StringUtils.equals(readValue(name), value)) {
                preserveRecordedConfigurations(true);
            } else if (BackupManager.ThirdPartyStagingEnable.key().equals(name) && isEnabled()
                    && !Boolean.parseBoolean(StringUtils.trimToEmpty(value))) {
                // Capture legacy backups while the old configuration is still complete.
                preserveRecordedConfigurations(false);
            }
            return saveConfiguration.get();
        } finally {
            if (locked) {
                lock.unlock();
            }
            lock.releaseRef();
        }
    }

    private boolean isEnabled() {
        return Boolean.parseBoolean(readValue(BackupManager.ThirdPartyStagingEnable));
    }

    @Override
    public void requireEnabled() {
        if (!isEnabled()) {
            throw new CloudRuntimeException("ABLESTACK third-party staging is disabled. Configure staging storage and enable backup.thirdparty.staging.enable first.");
        }
    }

    private void validateActivation() {
        Snapshot configuration = loadConfiguration();
        long largestVolumeBytes = volumeDao.findLargestUserVmVolumeSize();
        long requiredBytes = Math.addExact(largestVolumeBytes, capacityBuffer(largestVolumeBytes, configuration.bufferPercent));
        List<HostVO> hosts = findActivationHosts();
        if (hosts.isEmpty()) {
            throw new InvalidParameterValueException(
                    "No eligible KVM hosts were found in zones with an enabled ABLESTACK third-party backup provider.");
        }

        List<String> failures = new ArrayList<>();
        for (HostVO host : hosts) {
            try {
                prepareHost(host, configuration, configuration.rootPath, requiredBytes);
            } catch (RuntimeException e) {
                failures.add(String.format("Host [%s] (zone [%s]): %s", host.getName(), host.getDataCenterId(), e.getMessage()));
            }
        }
        if (!failures.isEmpty()) {
            throw new InvalidParameterValueException(
                    "Staging activation failed; the setting was not changed. " + String.join("; ", failures));
        }
        logger.info("Third-party staging activation validation succeeded: hosts=[{}], largestVolumeBytes=[{}], bufferPercent=[{}], requiredBytes=[{}], root=[{}]",
                hosts.size(), largestVolumeBytes, configuration.bufferPercent, requiredBytes, configuration.rootPath);
    }

    private List<HostVO> findActivationHosts() {
        List<HostVO> hosts = new ArrayList<>();
        for (HostVO host : hostDao.listByType(Host.Type.Routing)) {
            if (host.getRemoved() != null || host.getHypervisorType() != HypervisorType.KVM
                    || host.getResourceState() != ResourceState.Enabled
                    || !Boolean.TRUE.equals(BackupManager.BackupFrameworkEnabled.valueIn(host.getDataCenterId()))) {
                continue;
            }
            String providers = BackupManager.BackupProviderPlugin.valueIn(host.getDataCenterId());
            if (Arrays.stream(StringUtils.defaultString(providers).split(","))
                    .map(String::trim).anyMatch(ThirdPartyBackupStagingService::isStagingProvider)) {
                hosts.add(host);
            }
        }
        return hosts;
    }

    @Override
    public String getStageRootPath(String providerName) {
        if (!ThirdPartyBackupStagingService.isStagingProvider(providerName)) {
            throw new IllegalArgumentException("Unsupported third-party staging provider: " + providerName);
        }
        return Path.of(loadConfiguration().rootPath).resolve(providerName.toLowerCase(Locale.ROOT)).toString();
    }

    @Override
    public Snapshot captureConfiguration() {
        return loadConfiguration();
    }

    @Override
    public long getAvailableBytes(Host host, String path) {
        requireEnabled();
        return preparePath(host, path);
    }

    @Override
    public long prepareBackupStart(Host host, String path) {
        requireEnabled();
        Snapshot configuration = loadConfiguration();
        Path requested = absolutePath("staging backup path", path);
        if (!requested.startsWith(Path.of(configuration.rootPath))) {
            throw new CloudRuntimeException("Backup path differs from the active staging configuration");
        }
        String key = (host == null ? "missing" : host.getId()) + ":" + new Gson().toJson(configuration) + ":" + requested;
        long now = validationClock.millis();
        backupStartFailures.entrySet().removeIf(entry -> entry.getValue().retryAt <= now);
        ValidationFailure previous = backupStartFailures.get(key);
        if (previous != null) {
            throw new CloudRuntimeException("BACKUP_START_DEFERRED: Staging remains unavailable; retry after 30 seconds. " + previous.reason);
        }
        try {
            return prepareHost(host, configuration, requested.toString(), 0,
                    Math.min(configuration.timeoutSeconds, VALIDATION_TIMEOUT_SECONDS));
        } catch (RuntimeException e) {
            backupStartFailures.put(key, new ValidationFailure(
                    validationClock.millis() + VALIDATION_RETRY_DELAY_MS, e.getMessage()));
            throw new CloudRuntimeException("BACKUP_START_DEFERRED: Staging is unavailable; no backup point was created. " + e.getMessage(), e);
        }
    }

    @Override
    public void validateBackupSources(Host host, List<String> paths, List<StoragePoolType> poolTypes) {
        if (host == null || paths == null || paths.isEmpty() || poolTypes == null || paths.size() != poolTypes.size()
                || paths.stream().anyMatch(StringUtils::isBlank) || poolTypes.stream().anyMatch(type -> type == null)) {
            throw new CloudRuntimeException("BACKUP_START_DEFERRED: Source storage information is unavailable; no backup point was created.");
        }
        List<String> filePaths = new ArrayList<>();
        for (int index = 0; index < paths.size(); index++) {
            if (poolTypes.get(index) != StoragePoolType.RBD) {
                filePaths.add(paths.get(index));
            }
        }
        if (filePaths.isEmpty()) {
            return;
        }
        // Avoid libvirt storage refresh: its native I/O can stall on withdrawn GFS2.
        String key = host.getId() + ":" + new Gson().toJson(filePaths);
        long now = validationClock.millis();
        sourceCheckFailures.entrySet().removeIf(entry -> entry.getValue().retryAt <= now);
        ValidationFailure previous = sourceCheckFailures.get(key);
        if (previous != null) {
            throw new CloudRuntimeException("BACKUP_START_DEFERRED: Source storage will be checked again after 30 seconds. " + previous.reason);
        }
        AblestackBackupSourceCheckCommand command = new AblestackBackupSourceCheckCommand(filePaths, VALIDATION_TIMEOUT_SECONDS);
        try {
            Answer answer = agentManager.send(host.getId(), command);
            if (answer == null || !answer.getResult()) {
                throw new CloudRuntimeException(answer == null ? "No source storage check answer received."
                        : StringUtils.defaultIfBlank(answer.getDetails(), "Source storage check failed."));
            }
        } catch (AgentUnavailableException | OperationTimedoutException | CloudRuntimeException e) {
            sourceCheckFailures.put(key, new ValidationFailure(validationClock.millis() + VALIDATION_RETRY_DELAY_MS, e.getMessage()));
            throw new CloudRuntimeException("BACKUP_START_DEFERRED: Source storage is unavailable; no backup point was created. " + e.getMessage(), e);
        }
    }

    @Override
    public void prepareCleanup(Host host, String path) {
        prepareCleanup(host, path, loadConfiguration());
    }

    @Override
    public void prepareCleanup(Host host, String path, Snapshot configuration) {
        validateSnapshot(configuration);
        Path requested = absolutePath("recorded staging cleanup path", path);
        if (!requested.startsWith(Path.of(configuration.rootPath))) {
            throw new CloudRuntimeException("Cleanup path differs from the recorded staging configuration");
        }
        // A missing shared mount affects every retained point on this worker. Do not
        // repeat a full mount timeout for each deletion while holding its VM lock.
        String key = (host == null ? "missing" : host.getId()) + ":" + new Gson().toJson(configuration) + ":" + requested;
        long now = validationClock.millis();
        cleanupValidationFailures.entrySet().removeIf(entry -> entry.getValue().retryAt <= now);
        ValidationFailure previous = cleanupValidationFailures.get(key);
        if (previous != null) {
            throw new CloudRuntimeException("Staging cleanup validation will retry shortly: " + previous.reason);
        }
        try {
            prepareHost(host, configuration, requested.toString(), 0,
                    Math.min(configuration.timeoutSeconds, VALIDATION_TIMEOUT_SECONDS));
        } catch (RuntimeException e) {
            cleanupValidationFailures.put(key, new ValidationFailure(
                    validationClock.millis() + VALIDATION_RETRY_DELAY_MS, e.getMessage()));
            throw e;
        }
    }

    private long preparePath(Host host, String path) {
        Snapshot configuration = loadConfiguration();
        Path requestedPath = absolutePath("staging operation path", path);
        if (!requestedPath.startsWith(Path.of(configuration.rootPath))) {
            throw new CloudRuntimeException("The staging operation path must be inside backup.thirdparty.staging.root.path: " + path
                    + ". Backups recorded with an earlier staging path require restore destination migration before they can use this configuration.");
        }
        return prepareHost(host, configuration, requestedPath.toString(), 0);
    }

    @Override
    public long getCapacityBufferBytes(long requiredBytes) {
        return capacityBuffer(requiredBytes, getCapacityBufferPercent());
    }

    @Override
    public int getCapacityBufferPercent() {
        return Integer.parseInt(readValue(BackupManager.ThirdPartyStagingCapacityBufferPercent));
    }

    private long capacityBuffer(long bytes, int percent) {
        if (bytes < 0 || percent < 0 || percent > 100) {
            throw new InvalidParameterValueException("Staging capacity must be non-negative and the capacity buffer must be between 0 and 100 percent.");
        }
        return BigInteger.valueOf(bytes).multiply(BigInteger.valueOf(percent))
                .add(BigInteger.valueOf(99)).divide(BigInteger.valueOf(100)).longValueExact();
    }

    private long prepareHost(Host host, Snapshot configuration, String path, long requiredBytes) {
        return prepareHost(host, configuration, path, requiredBytes, configuration.timeoutSeconds);
    }

    private long prepareHost(Host host, Snapshot configuration, String path, long requiredBytes, int timeoutSeconds) {
        if (host == null || host.getStatus() != Status.Up || host.getHypervisorType() != HypervisorType.KVM
                || host.getResourceState() != ResourceState.Enabled) {
            throw new CloudRuntimeException("The staging host must be an enabled, connected KVM host.");
        }
        AblestackThirdPartyStagingCommand command = new AblestackThirdPartyStagingCommand(
                configuration.type, configuration.rootPath, configuration.mountPath,
                configuration.nfsSource, configuration.mountOptions, path, timeoutSeconds, requiredBytes);
        try {
            Answer answer = agentManager.send(host.getId(), command);
            if (answer == null || !answer.getResult()) {
                throw new CloudRuntimeException(answer == null ? "No staging validation answer received." : answer.getDetails());
            }
            long availableBytes = Long.parseLong(StringUtils.trimToEmpty(answer.getDetails()));
            if (availableBytes < requiredBytes || availableBytes < 0) {
                throw new CloudRuntimeException(String.format(
                        "Insufficient staging space: required [%d] bytes, available [%d] bytes.", requiredBytes, availableBytes));
            }
            return availableBytes;
        } catch (AgentUnavailableException | OperationTimedoutException e) {
            throw new CloudRuntimeException("Unable to validate staging on host [" + host.getName() + "]: " + e.getMessage(), e);
        } catch (NumberFormatException e) {
            throw new CloudRuntimeException("Invalid staging capacity response from host [" + host.getName() + "].", e);
        }
    }

    private Snapshot loadConfiguration() {
        Snapshot configuration = new Snapshot();
        configuration.type = readValue(BackupManager.ThirdPartyStagingStorageType).trim().toUpperCase(Locale.ROOT);
        validateSetting(BackupManager.ThirdPartyStagingStorageType.key(), configuration.type);
        if (configuration.type.isEmpty()) {
            throw new InvalidParameterValueException("backup.thirdparty.staging.storage.type must be configured.");
        }
        Path mountPath = absolutePath(BackupManager.ThirdPartyStagingMountPath.key(), readValue(BackupManager.ThirdPartyStagingMountPath));
        Path rootPath = absolutePath(BackupManager.ThirdPartyStagingRootPath.key(), readValue(BackupManager.ThirdPartyStagingRootPath));
        if (mountPath.getParent() == null || !rootPath.startsWith(mountPath)) {
            throw new InvalidParameterValueException("The staging mount must be a separate mount point, and root.path must be within mount.path.");
        }
        configuration.mountPath = mountPath.toString();
        configuration.rootPath = rootPath.toString();
        configuration.nfsSource = readValue(BackupManager.ThirdPartyStagingNfsSource).trim();
        configuration.mountOptions = readValue(BackupManager.ThirdPartyStagingMountOptions).trim();
        configuration.timeoutSeconds = Integer.parseInt(readValue(BackupManager.ThirdPartyStagingMountTimeout));
        configuration.bufferPercent = Integer.parseInt(readValue(BackupManager.ThirdPartyStagingCapacityBufferPercent));
        validateSetting(BackupManager.ThirdPartyStagingMountTimeout.key(), String.valueOf(configuration.timeoutSeconds));
        validateSetting(BackupManager.ThirdPartyStagingCapacityBufferPercent.key(), String.valueOf(configuration.bufferPercent));
        validateSetting(BackupManager.ThirdPartyStagingMountOptions.key(), configuration.mountOptions);
        if ("NFS".equals(configuration.type) && !configuration.nfsSource.matches("[^\\s]+:/.*")) {
            throw new InvalidParameterValueException("backup.thirdparty.staging.nfs.source must use server:/export format.");
        }
        return configuration;
    }

    private void validateSnapshot(Snapshot configuration) {
        if (configuration == null || configuration.version != 1) {
            throw new CloudRuntimeException("Recorded staging configuration is missing or unsupported");
        }
        validateSetting(BackupManager.ThirdPartyStagingStorageType.key(), configuration.type);
        Path root = absolutePath("recorded staging root", configuration.rootPath);
        Path mount = absolutePath("recorded staging mount", configuration.mountPath);
        if (!List.of("GFS2", "NFS", "LOCAL").contains(configuration.type) || mount.getParent() == null
                || !root.startsWith(mount) || !root.toString().equals(configuration.rootPath)
                || !mount.toString().equals(configuration.mountPath)
                || ("NFS".equals(configuration.type) && (configuration.nfsSource == null
                        || !configuration.nfsSource.matches("[^\\s]+:/.*")))) {
            throw new CloudRuntimeException("Recorded staging mount identity is invalid");
        }
        validateSetting(BackupManager.ThirdPartyStagingMountOptions.key(), configuration.mountOptions);
        validateSetting(BackupManager.ThirdPartyStagingMountTimeout.key(), String.valueOf(configuration.timeoutSeconds));
        validateSetting(BackupManager.ThirdPartyStagingCapacityBufferPercent.key(), String.valueOf(configuration.bufferPercent));
    }

    private boolean isLocationSetting(String name) {
        return BackupManager.ThirdPartyStagingStorageType.key().equals(name)
                || BackupManager.ThirdPartyStagingRootPath.key().equals(name)
                || BackupManager.ThirdPartyStagingMountPath.key().equals(name)
                || BackupManager.ThirdPartyStagingNfsSource.key().equals(name)
                || BackupManager.ThirdPartyStagingMountOptions.key().equals(name);
    }

    private void preserveRecordedConfigurations(boolean requireQuiescent) {
        Snapshot current = null;
        List<BackupVO> legacy = new ArrayList<>();
        for (BackupDetailVO detail : backupDetailsDao.findDetails(ThirdPartyBackupManifest.MODE_KEY,
                ThirdPartyBackupManifest.VOLUME_MODE, null)) {
            BackupVO backup = backupDao.findById(detail.getResourceId());
            if (backup == null) { continue; }
            backupDao.loadDetails(backup);
            if (requireQuiescent) { requireQuiescent(backup); }
            if (StringUtils.isNotBlank(backup.getDetail(SNAPSHOT_KEY))) {
                validateSnapshot(new Gson().fromJson(backup.getDetail(SNAPSHOT_KEY), Snapshot.class));
                continue;
            }
            ThirdPartyBackupManifest manifest = ThirdPartyBackupManifest.fromJson(backup.getDetail(ThirdPartyBackupManifest.DETAIL_KEY));
            Path recorded = Path.of(manifest.getCurrentArtifacts().get(0).path).getParent().getParent().getParent();
            String configuredRoot = readValue(BackupManager.ThirdPartyStagingRootPath);
            if (StringUtils.isBlank(configuredRoot) || !recorded.equals(Path.of(configuredRoot, manifest.getProvider()))) {
                logger.warn("Legacy backup [{}] has no staging snapshot for [{}]. Its original mount settings must be restored before cleanup; no configuration was inferred.",
                        backup.getUuid(), recorded);
                continue;
            }
            if (current == null) {
                try { current = loadConfiguration(); }
                catch (InvalidParameterValueException e) {
                    // Permit completing/correcting a disabled, partially edited configuration.
                    logger.warn("Legacy staging snapshot for backup [{}] is deferred until valid mount settings are restored: {}", backup.getUuid(), e.getMessage());
                    continue;
                }
            }
            legacy.add(backup);
        }
        if (current != null) {
            String json = new Gson().toJson(current);
            for (BackupVO backup : legacy) { backupDetailsDao.addDetail(backup.getId(), SNAPSHOT_KEY, json, false); }
        }
    }

    private void requireQuiescent(BackupVO backup) {
        boolean busy = backup.getStatus() == Backup.Status.BackingUp || backup.getStatus() == Backup.Status.Restoring
                || "true".equals(backup.getDetail(ThirdPartyExternalRestore.PENDING_KEY))
                || ThirdPartyBackupVolumeService.needsBackupCleanup(backup)
                || StringUtils.isNotBlank(backup.getDetail(ThirdPartyBackupManifest.DELETE_PROGRESS_KEY))
                || (StringUtils.isNotBlank(backup.getDetail(ThirdPartyBackupRestore.PLAN_KEY))
                        && !"COMPLETED".equals(backup.getDetail(ThirdPartyBackupRestore.CLEANUP_STATE_KEY)))
                || List.of("WAITING", "RUNNING").contains(StringUtils.defaultString(
                        backup.getDetail(ThirdPartyBackupManifest.SOURCE_CLEANUP_STATE_KEY)));
        for (String key : List.of(ThirdPartyBackupAdmission.BACKUP_KEY, ThirdPartyBackupAdmission.RESTORE_KEY)) {
            String json = backup.getDetail(key);
            if (StringUtils.isNotBlank(json)) {
                ThirdPartyBackupAdmission.Entry entry = new Gson().fromJson(json, ThirdPartyBackupAdmission.Entry.class);
                busy |= entry == null || !"RELEASED".equals(entry.state);
            }
        }
        if (busy) {
            throw new CloudRuntimeException("Staging location cannot change while backup [" + backup.getUuid()
                    + "] has queued, active or unconfirmed cleanup work. Finish or cancel it using the original configuration first.");
        }
    }

    private Path absolutePath(String name, String value) {
        try {
            if (StringUtils.isBlank(value) || !Path.of(value).isAbsolute() || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
                throw new InvalidParameterValueException(name + " must be an absolute filesystem path.");
            }
            return Path.of(value).normalize();
        } catch (InvalidPathException e) {
            throw new InvalidParameterValueException(name + " contains an invalid filesystem path.");
        }
    }

    private boolean isAdmissionSetting(String name) {
        return BackupManager.ThirdPartyStagingConcurrentHost.key().equals(name)
                || BackupManager.ThirdPartyStagingConcurrentCluster.key().equals(name)
                || BackupManager.ThirdPartyStagingConcurrentTotal.key().equals(name)
                || BackupManager.ThirdPartyStagingQueueTimeout.key().equals(name);
    }

    private void validateSetting(String name, String value) {
        String normalized = StringUtils.trimToEmpty(value);
        try {
            if (BackupManager.ThirdPartyStagingEnable.key().equals(name)) {
                if (!"true".equalsIgnoreCase(normalized) && !"false".equalsIgnoreCase(normalized)) {
                    throw new InvalidParameterValueException("backup.thirdparty.staging.enable must be true or false.");
                }
            } else if (BackupManager.ThirdPartyStagingStorageType.key().equals(name)) {
                if (!normalized.isEmpty() && !Arrays.asList("GFS2", "NFS", "LOCAL")
                        .contains(normalized.toUpperCase(Locale.ROOT))) {
                    throw new InvalidParameterValueException("Staging storage.type must be GFS2, NFS, or LOCAL.");
                }
            } else if (isAdmissionSetting(name)) {
                if (Integer.parseInt(normalized) <= 0) {
                    throw new InvalidParameterValueException("Staging concurrency limits and queue timeout must be positive integers.");
                }
            } else if (BackupManager.ThirdPartyStagingCapacityBufferPercent.key().equals(name)) {
                int percent = Integer.parseInt(normalized);
                if (percent < 0 || percent > 100) {
                    throw new InvalidParameterValueException("Staging capacity.buffer.percent must be between 0 and 100.");
                }
            } else if (BackupManager.ThirdPartyStagingMountTimeout.key().equals(name)) {
                int seconds = Integer.parseInt(normalized);
                if (seconds <= 0 || seconds > Integer.MAX_VALUE - 30) {
                    throw new InvalidParameterValueException("Staging mount.timeout must be a positive number of seconds.");
                }
            } else if (BackupManager.ThirdPartyStagingMountOptions.key().equals(name)
                    && (normalized.indexOf('\n') >= 0 || normalized.indexOf('\r') >= 0
                            || Arrays.stream(normalized.split(",")).map(String::trim).anyMatch(option -> option.matches("(?i)(secret|password)=.*")))) {
                throw new InvalidParameterValueException("Use host credential files instead of secrets in staging mount.options.");
            }
        } catch (NumberFormatException e) {
            throw new InvalidParameterValueException(name + " must be an integer.");
        }
    }

    private String readValue(ConfigKey<?> key) {
        String value = configurationDao.getValue(key.key());
        return value == null ? StringUtils.defaultString(key.defaultValue()) : value;
    }

    private String readValue(String name) {
        return configurationDao.getValue(name);
    }

}
