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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import javax.inject.Inject;

import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
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
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.exception.CloudRuntimeException;

/** Activation and runtime validation share the same host-side staging preparation command. */
public class ThirdPartyBackupStagingServiceImpl extends ManagerBase implements ThirdPartyBackupStagingService {
    @Inject
    private ConfigurationDao configurationDao;
    @Inject
    private HostDao hostDao;
    @Inject
    private VolumeDao volumeDao;
    @Inject
    private AgentManager agentManager;

    @Override
    public <T> T updateConfiguration(String name, String value, Supplier<T> saveConfiguration) {
        GlobalLock lock = GlobalLock.getInternLock("backup.thirdparty.staging.configuration");
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
                }
            } else if (isEnabled() && !StringUtils.equals(readValue(name), value)) {
                throw new InvalidParameterValueException(
                        "Disable backup.thirdparty.staging.enable before changing staging settings, then enable it again to validate the new configuration.");
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
        StagingConfiguration configuration = loadConfiguration();
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
    public long getAvailableBytes(Host host, String path) {
        requireEnabled();
        StagingConfiguration configuration = loadConfiguration();
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

    private long prepareHost(Host host, StagingConfiguration configuration, String path, long requiredBytes) {
        if (host == null || host.getStatus() != Status.Up || host.getHypervisorType() != HypervisorType.KVM
                || host.getResourceState() != ResourceState.Enabled) {
            throw new CloudRuntimeException("The staging host must be an enabled, connected KVM host.");
        }
        AblestackThirdPartyStagingCommand command = new AblestackThirdPartyStagingCommand(
                configuration.type, configuration.rootPath, configuration.mountPath,
                configuration.nfsSource, configuration.mountOptions, path, configuration.timeoutSeconds, requiredBytes);
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

    private StagingConfiguration loadConfiguration() {
        StagingConfiguration configuration = new StagingConfiguration();
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

    private static final class StagingConfiguration {
        private String type;
        private String rootPath;
        private String mountPath;
        private String nfsSource;
        private String mountOptions;
        private int timeoutSeconds;
        private int bufferPercent;
    }
}
