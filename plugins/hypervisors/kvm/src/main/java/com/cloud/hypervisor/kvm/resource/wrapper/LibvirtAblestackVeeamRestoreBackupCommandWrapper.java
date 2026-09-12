//
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
//

package com.cloud.hypervisor.kvm.resource.wrapper;

import com.cloud.agent.api.Answer;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.hypervisor.kvm.storage.KVMStoragePool;
import com.cloud.hypervisor.kvm.storage.KVMStoragePoolManager;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;
import com.cloud.storage.Storage;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.script.Script;
import com.cloud.vm.VirtualMachine;
import org.apache.cloudstack.backup.AblestackBackupFrameworkUtils;
import org.apache.cloudstack.backup.AblestackVeeamRestoreBackupCommand;
import org.apache.cloudstack.backup.BackupAnswer;
import org.apache.cloudstack.backup.BackupRestorePlan;
import org.apache.cloudstack.backup.BackupRestoreStage;
import org.apache.cloudstack.backup.BackupVolumeChainState;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;
import org.apache.cloudstack.utils.qemu.QemuImg;
import org.apache.cloudstack.utils.qemu.QemuImgException;
import org.apache.cloudstack.utils.qemu.QemuImgFile;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.libvirt.LibvirtException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@ResourceWrapper(handles = AblestackVeeamRestoreBackupCommand.class)
public class LibvirtAblestackVeeamRestoreBackupCommandWrapper extends CommandWrapper<AblestackVeeamRestoreBackupCommand, Answer, LibvirtComputingResource> {
    private static final String FILE_PATH_PLACEHOLDER = "%s/%s";
    private static final String COMMAND_EXIT_MARKER = "__CS_COMMAND_EXIT__=";
    private static final String ATTACH_QCOW2_DISK_COMMAND = " virsh attach-disk %s %s %s --driver qemu --subdriver qcow2 --cache none";
    private static final String ATTACH_RBD_DISK_XML_COMMAND = " virsh attach-device %s /dev/stdin <<EOF%sEOF";
    private static final String CURRENT_DEVICE = "virsh domblklist --domain %s | tail -n 3 | head -n 1 | awk '{print $1}'";
    private static final String QEMU_IMG_HAS_BACKING_COMMAND = "qemu-img info --output=json %s 2>/dev/null | grep -q '\"backing-filename\"'";
    private static final String RESTORE_TRACE = "[ABLESTACK_VEEAM_RESTORE_TRACE]";
    private static final long RESTORE_PRIMARY_SPACE_BUFFER_BYTES = 10L * 1024L * 1024L * 1024L;

    @Override
    public Answer execute(final AblestackVeeamRestoreBackupCommand command, final LibvirtComputingResource serverResource) {
        final String backupPath = command.getBackupPath();
        final Boolean vmExists = command.isVmExists();
        final String diskType = command.getDiskType();
        final List<String> backedVolumeUUIDs = command.getBackupVolumesUUIDs();
        final List<String> backupFiles = command.getBackupFiles();
        final List<String> backupFileChains = command.getBackupFileChains();
        final List<BackupVolumeChainState> volumeChainStates = command.getVolumeChainStates();
        final List<PrimaryDataStoreTO> restoreVolumePools = command.getRestoreVolumePools();
        final List<String> restoreVolumePaths = command.getRestoreVolumePaths();
        final String restoreVolumeUuid = command.getRestoreVolumeUUID();
        final int timeout = command.getTimeout();
        final BackupRestorePlan restorePlan = command.getRestorePlan();
        final String cacheMode = command.getCacheMode();
        final KVMStoragePoolManager storagePoolMgr = serverResource.getStoragePoolMgr();
        String newVolumeId = null;

        try {
            if (Objects.isNull(vmExists)) {
                final PrimaryDataStoreTO restoreVolumePool = restoreVolumePools.get(0);
                final String restoreVolumePath = restoreVolumePaths.get(0);
                final int lastIndex = restoreVolumePath.lastIndexOf("/");
                newVolumeId = restoreVolumePath.substring(lastIndex + 1);
                restoreVolume(storagePoolMgr, backupPath, restoreVolumePool, restoreVolumePath, diskType, restoreVolumeUuid,
                        backupFiles, backupFileChains, volumeChainStates, command.getVmName(), command.getVmState(), timeout, cacheMode, restorePlan);
            } else if (Boolean.TRUE.equals(vmExists)) {
                restoreVolumesOfExistingVM(storagePoolMgr, restoreVolumePools, restoreVolumePaths, backedVolumeUUIDs,
                        backupPath, backupFiles, backupFileChains, volumeChainStates, timeout, restorePlan, command.getCheckpointName());
            } else {
                restoreVolumesOfDestroyedVMs(storagePoolMgr, restoreVolumePools, restoreVolumePaths, backedVolumeUUIDs,
                        backupPath, backupFiles, backupFileChains, volumeChainStates, timeout, restorePlan, command.getCheckpointName());
            }
        } catch (final CloudRuntimeException e) {
            final String errorMessage = e.getMessage() != null ? e.getMessage() : "";
            return new BackupAnswer(command, false, errorMessage);
        }

        return new BackupAnswer(command, true, newVolumeId);
    }

    private void restoreVolumesOfExistingVM(final KVMStoragePoolManager storagePoolMgr, final List<PrimaryDataStoreTO> restoreVolumePools,
            final List<String> restoreVolumePaths, final List<String> backedVolumesUUIDs, final String backupPath, final List<String> backupFiles,
            final List<String> backupFileChains, final List<BackupVolumeChainState> volumeChainStates, final int timeout,
            final BackupRestorePlan restorePlan, final String checkpointName) {
        try {
            validateChainStatePlan(volumeChainStates, restorePlan);
            if (tryRestoreVolumesWithRbdSnapRollback(storagePoolMgr, restoreVolumePools, restoreVolumePaths, checkpointName, timeout)) {
                return;
            }
            final List<List<String>> localBackupPathsByVolume = getLocalBackupPathsForVolumes(backupPath, backupFiles, backupFileChains, volumeChainStates,
                    restoreVolumePaths, backedVolumesUUIDs);
            validatePrimaryStorageSpaceForFileRestorePlan(restoreVolumePaths, localBackupPathsByVolume, restoreVolumePools);
            for (int idx = 0; idx < restoreVolumePaths.size(); idx++) {
                final PrimaryDataStoreTO restoreVolumePool = restoreVolumePools.get(idx);
                final String restoreVolumePath = restoreVolumePaths.get(idx);
                final String backupVolumeUuid = backedVolumesUUIDs.get(idx);
                final List<String> localBackupPaths = localBackupPathsByVolume.get(idx);
                logger.info("Resolved Veeam local backup paths for existing VM volume [{}], target [{}]: {}",
                        backupVolumeUuid, restoreVolumePath, localBackupPaths);
                validateResolvedChainPaths(localBackupPaths, restoreVolumePath);
                if (!replaceVolumeWithBackup(storagePoolMgr, restoreVolumePool, restoreVolumePath, localBackupPaths, timeout, backupPath, idx)) {
                    throw new CloudRuntimeException(String.format("Unable to restore contents from the backup volume [%s].", backupVolumeUuid));
                }
            }
        } finally {
            cleanupBackupDirectory(backupPath, restorePlan);
        }
    }

    private void restoreVolumesOfDestroyedVMs(final KVMStoragePoolManager storagePoolMgr, final List<PrimaryDataStoreTO> restoreVolumePools,
            final List<String> restoreVolumePaths, final List<String> backedVolumesUUIDs, final String backupPath, final List<String> backupFiles,
            final List<String> backupFileChains, final List<BackupVolumeChainState> volumeChainStates, final int timeout,
            final BackupRestorePlan restorePlan, final String checkpointName) {
        // Same disk rewrite as existing-VM restore; createVMFromBackup / destroyed-VM restore land here when vmExists=false.
        restoreVolumesOfExistingVM(storagePoolMgr, restoreVolumePools, restoreVolumePaths, backedVolumesUUIDs,
                backupPath, backupFiles, backupFileChains, volumeChainStates, timeout, restorePlan, checkpointName);
    }

    private void validateChainStatePlan(final List<BackupVolumeChainState> volumeChainStates, final BackupRestorePlan restorePlan) {
        if (AblestackBackupFrameworkUtils.hasRestoreStage(restorePlan, BackupRestoreStage.VALIDATE_CHAIN)
                && volumeChainStates != null && !volumeChainStates.isEmpty()) {
            try {
                AblestackBackupFrameworkUtils.validateVolumeChainStates(volumeChainStates);
            } catch (final IllegalArgumentException e) {
                throw new CloudRuntimeException(e.getMessage(), e);
            }
        }
    }

    private void validateResolvedChainPaths(final List<String> resolvedPaths, final String volumePath) {
        if (resolvedPaths == null || resolvedPaths.isEmpty()) {
            throw new CloudRuntimeException(String.format("No resolved backup chain paths found for volume [%s]", volumePath));
        }
        for (final String path : resolvedPaths) {
            if (StringUtils.isBlank(path) || !Files.isRegularFile(Paths.get(path))) {
                throw new CloudRuntimeException(String.format(
                        "Backup chain file [%s] is missing for volume [%s]. Refusing to restore so the live RBD/qcow2 image is not deleted.",
                        path, volumePath));
            }
        }
    }

    private void cleanupBackupDirectory(final String backupPath, final BackupRestorePlan restorePlan) {
        if (AblestackBackupFrameworkUtils.hasRestoreStage(restorePlan, BackupRestoreStage.CLEANUP_SOURCE)) {
            deleteBackupDirectory(backupPath);
        }
    }

    private void restoreVolume(final KVMStoragePoolManager storagePoolMgr, final String backupPath, final PrimaryDataStoreTO volumePool,
            final String volumePath, final String diskType, final String volumeUUID, final List<String> backupFiles,
            final List<String> backupFileChains, final List<BackupVolumeChainState> volumeChainStates, final String vmName,
            final VirtualMachine.State vmState, final int timeout, final String cacheMode, final BackupRestorePlan restorePlan) {
        try {
            final List<String> localBackupPaths = getLocalBackupPaths(backupPath, backupFiles, backupFileChains, volumeChainStates, 0,
                    getLegacyBackupFileName(diskType, volumeUUID));
            logger.info("Resolved Veeam local backup paths for restored volume [{}], target [{}]: {}",
                    volumeUUID, volumePath, localBackupPaths);
            validateResolvedChainPaths(localBackupPaths, volumePath);
            validatePrimaryStorageSpaceForFileRestorePlan(List.of(volumePath), List.of(localBackupPaths), List.of(volumePool));
            if (!replaceVolumeWithBackup(storagePoolMgr, volumePool, volumePath, localBackupPaths, timeout, backupPath, 0, true)) {
                throw new CloudRuntimeException(String.format("Unable to restore contents from the backup volume [%s].", volumeUUID));
            }
            if (AblestackBackupFrameworkUtils.hasRestoreStage(restorePlan, BackupRestoreStage.ATTACH_VOLUME)
                    && VirtualMachine.State.Running.equals(vmState)) {
                if (!attachVolumeToVm(storagePoolMgr, vmName, volumePool, volumePath, cacheMode)) {
                    throw new CloudRuntimeException(String.format("Failed to attach volume to VM: %s", vmName));
                }
            }
        } finally {
            cleanupBackupDirectory(backupPath, restorePlan);
        }
    }

    private boolean tryRestoreVolumesWithRbdSnapRollback(final KVMStoragePoolManager storagePoolMgr,
            final List<PrimaryDataStoreTO> restoreVolumePools, final List<String> restoreVolumePaths, final String checkpointName,
            final int timeout) {
        if (StringUtils.isBlank(checkpointName) || CollectionUtils.isEmpty(restoreVolumePools) || CollectionUtils.isEmpty(restoreVolumePaths)
                || restoreVolumePools.size() != restoreVolumePaths.size()) {
            return false;
        }
        for (final PrimaryDataStoreTO pool : restoreVolumePools) {
            if (pool == null || pool.getPoolType() != Storage.StoragePoolType.RBD) {
                return false;
            }
        }
        for (int idx = 0; idx < restoreVolumePaths.size(); idx++) {
            final PrimaryDataStoreTO volumePool = restoreVolumePools.get(idx);
            final KVMStoragePool volumeStoragePool = storagePoolMgr.getStoragePool(volumePool.getPoolType(), volumePool.getUuid());
            final String normalizedVolumePath = normalizeRbdVolumePath(restoreVolumePaths.get(idx), volumeStoragePool);
            if (!rbdSnapshotExists(volumeStoragePool, normalizedVolumePath, checkpointName, timeout)) {
                logger.info("{} phase=[RBD_SNAP_ROLLBACK_SKIP], reason=[checkpoint_missing], volume=[{}], checkpoint=[{}]",
                        RESTORE_TRACE, normalizedVolumePath, checkpointName);
                return false;
            }
        }
        for (int idx = 0; idx < restoreVolumePaths.size(); idx++) {
            final PrimaryDataStoreTO volumePool = restoreVolumePools.get(idx);
            final KVMStoragePool volumeStoragePool = storagePoolMgr.getStoragePool(volumePool.getPoolType(), volumePool.getUuid());
            final String normalizedVolumePath = normalizeRbdVolumePath(restoreVolumePaths.get(idx), volumeStoragePool);
            if (!rollbackRbdVolumeToCheckpoint(volumeStoragePool, normalizedVolumePath, checkpointName, timeout)) {
                throw new CloudRuntimeException(String.format(
                        "Failed to rollback RBD volume [%s] to checkpoint snapshot [%s]", normalizedVolumePath, checkpointName));
            }
        }
        logger.info("{} phase=[RBD_SNAP_ROLLBACK_DONE], checkpoint=[{}], volumeCount=[{}]",
                RESTORE_TRACE, checkpointName, restoreVolumePaths.size());
        return true;
    }

    private boolean rollbackRbdVolumeToCheckpoint(final KVMStoragePool volumeStoragePool, final String volumePath,
            final String checkpointName, final int timeout) {
        logger.info("{} phase=[RBD_SNAP_ROLLBACK_BEGIN], volume=[{}], checkpoint=[{}]",
                RESTORE_TRACE, volumePath, checkpointName);
        final String rollbackCommand = buildRbdSnapshotCommand(volumeStoragePool, "snap rollback", volumePath + "@" + checkpointName);
        final CommandExecutionResult rollbackResult = executeBashCommandWithResult(rollbackCommand, timeout, "Rollback RBD volume to checkpoint");
        if (rollbackResult.exitCode != 0) {
            logger.error("{} phase=[RBD_SNAP_ROLLBACK_FAILED], volume=[{}], checkpoint=[{}], exit=[{}], output=[{}]",
                    RESTORE_TRACE, volumePath, checkpointName, rollbackResult.exitCode, rollbackResult.output);
            return false;
        }
        return true;
    }

    private void deleteBackupDirectory(final String backupDirectory) {
        try {
            FileUtils.deleteDirectory(new File(backupDirectory));
        } catch (final IOException e) {
            logger.error(String.format("Failed to delete backup directory: %s", backupDirectory), e);
            throw new CloudRuntimeException("Failed to delete the backup directory");
        }
    }

    private List<List<String>> getLocalBackupPathsForVolumes(final String backupPath, final List<String> backupFiles,
            final List<String> backupFileChains, final List<BackupVolumeChainState> volumeChainStates,
            final List<String> volumePaths, final List<String> backedVolumeUUIDs) {
        final List<List<String>> localBackupPathsByVolume = new ArrayList<>();
        String diskType = "root";
        for (int idx = 0; idx < volumePaths.size(); idx++) {
            final String volumeUuid = backedVolumeUUIDs != null ? backedVolumeUUIDs.get(idx)
                    : volumePaths.get(idx).substring(volumePaths.get(idx).lastIndexOf(File.separator) + 1);
            localBackupPathsByVolume.add(getLocalBackupPaths(backupPath, backupFiles, backupFileChains, volumeChainStates, idx,
                    getLegacyBackupFileName(diskType, volumeUuid)));
            diskType = "datadisk";
        }
        return localBackupPathsByVolume;
    }

    private List<String> getLocalBackupPaths(final String backupPath, final List<String> backupFiles, final List<String> backupFileChains,
            final List<BackupVolumeChainState> volumeChainStates, final int index, final String legacyBackupFileName) {
        final LinkedHashSet<String> localPaths = new LinkedHashSet<>();
        boolean resolvedFromVolumeChainStates = false;
        if (volumeChainStates != null && volumeChainStates.size() > index) {
            for (final String chainPath : volumeChainStates.get(index).getChainFiles()) {
                if (StringUtils.isBlank(chainPath)) {
                    continue;
                }
                localPaths.add(resolveBackupPath(backupPath, chainPath));
                resolvedFromVolumeChainStates = true;
            }
        }
        if (!resolvedFromVolumeChainStates && backupFileChains != null && backupFileChains.size() > index && StringUtils.isNotBlank(backupFileChains.get(index))) {
            for (final String chainPath : backupFileChains.get(index).split(";")) {
                if (StringUtils.isBlank(chainPath)) {
                    continue;
                }
                localPaths.add(resolveBackupPath(backupPath, chainPath));
            }
        }
        if (localPaths.isEmpty() && backupFiles != null && backupFiles.size() > index && StringUtils.isNotBlank(backupFiles.get(index))) {
            localPaths.add(resolveBackupPath(backupPath, backupFiles.get(index)));
        }
        if (localPaths.isEmpty()) {
            localPaths.add(String.format(FILE_PATH_PLACEHOLDER, backupPath, legacyBackupFileName));
        }
        return new ArrayList<>(localPaths);
    }

    private String resolveBackupPath(final String backupPath, final String chainPath) {
        if (chainPath.startsWith("/")) {
            return chainPath;
        }
        if (chainPath.contains("/")) {
            return String.format(FILE_PATH_PLACEHOLDER, backupPath, chainPath);
        }
        return String.format(FILE_PATH_PLACEHOLDER, backupPath, chainPath);
    }

    private String getLegacyBackupFileName(final String diskType, final String volumeUuid) {
        return String.format("%s.%s.qcow2", diskType.toLowerCase(Locale.ROOT), volumeUuid);
    }

    private boolean replaceVolumeWithBackup(final KVMStoragePoolManager storagePoolMgr, final PrimaryDataStoreTO volumePool,
            final String volumePath, final List<String> backupPaths, final int timeout, final String backupRootPath, final int backupIndex) {
        return replaceVolumeWithBackup(storagePoolMgr, volumePool, volumePath, backupPaths, timeout, backupRootPath, backupIndex, false);
    }

    private boolean replaceVolumeWithBackup(final KVMStoragePoolManager storagePoolMgr, final PrimaryDataStoreTO volumePool,
            final String volumePath, final List<String> backupPaths, final int timeout, final String backupRootPath, final int backupIndex,
            final boolean createTargetVolume) {
        if (backupPaths == null || backupPaths.isEmpty()) {
            return false;
        }
        logger.info("{} phase=[RESTORE_VOLUME_BEGIN], poolType=[{}], targetVolume=[{}], backupPaths=[{}], backupIndex=[{}], createTargetVolume=[{}]",
                RESTORE_TRACE, volumePool.getPoolType(), volumePath, backupPaths, backupIndex, createTargetVolume);
        if (volumePool.getPoolType() != Storage.StoragePoolType.RBD) {
            if (backupPaths.stream().anyMatch(path -> path.endsWith(".rbdiff"))) {
                return restoreIncrementalRbdBackupChainToFileVolume(volumePath, backupPaths, timeout, backupRootPath, backupIndex);
            }
            return replaceFileVolumeWithBackup(volumePath, backupPaths, timeout);
        }

        return replaceRbdVolumeWithBackup(storagePoolMgr, volumePool, volumePath, backupPaths, timeout, createTargetVolume);
    }

    private boolean restoreIncrementalRbdBackupChainToFileVolume(final String volumePath, final List<String> backupPaths, final int timeout,
            final String backupRootPath, final int backupIndex) {
        return LibvirtAblestackRbdRestoreHelper.restoreRbdBackupChainToFileVolume(RESTORE_TRACE, volumePath, backupPaths, timeout, backupRootPath, backupIndex);
    }

    private String getLastExistingBackupPath(final List<String> backupPaths) {
        for (int i = backupPaths.size() - 1; i >= 0; i--) {
            final String backupPath = backupPaths.get(i);
            if (StringUtils.isNotBlank(backupPath) && Files.exists(Paths.get(backupPath))) {
                return backupPath;
            }
        }
        return backupPaths.get(backupPaths.size() - 1);
    }

    private boolean replaceFileVolumeWithBackup(final String volumePath, final String backupPath, final int timeout) {
        QemuImgFile srcBackupFile = null;
        Path temporaryVolumePath = null;
        Path movedAsideTarget = null;
        try {
            srcBackupFile = new QemuImgFile(backupPath, getBackupFileFormat(backupPath));
            final QemuImg.PhysicalDiskFormat targetFormat = getFileVolumeFormat(volumePath);
            validatePrimaryStorageSpaceForFileRestore(backupPath, volumePath);
            movedAsideTarget = moveExistingFileVolumeAside(volumePath);
            temporaryVolumePath = createTemporaryVolumePath(volumePath, "cs-veeam-restore-volume-", targetFormat);
            Files.deleteIfExists(temporaryVolumePath);
            final QemuImgFile temporaryVolumeFile = new QemuImgFile(temporaryVolumePath.toString(), targetFormat);
            logger.info("{} phase=[TEMP_TARGET_CREATED], source=[{}], target=[{}], temporaryTarget=[{}], sourceFormat=[{}], targetFormat=[{}]",
                    RESTORE_TRACE, srcBackupFile.getFileName(), volumePath, temporaryVolumeFile.getFileName(), srcBackupFile.getFormat(), targetFormat);
            restoreFileVolumeData(backupPath, temporaryVolumeFile.getFileName(), srcBackupFile.getFormat(), targetFormat, timeout);
            Files.move(temporaryVolumePath, Paths.get(volumePath), StandardCopyOption.REPLACE_EXISTING);
            logger.info("{} phase=[TEMP_TARGET_PROMOTED], target=[{}], temporaryTarget=[{}]",
                    RESTORE_TRACE, volumePath, temporaryVolumePath);
            deleteMovedAsideFileVolume(movedAsideTarget);
            return true;
        } catch (final QemuImgException | LibvirtException | IOException e) {
            final String srcFilename = srcBackupFile != null ? srcBackupFile.getFileName() : null;
            logger.error("{} phase=[FILE_RESTORE_FAILED], source=[{}], target=[{}], error=[{}]",
                    RESTORE_TRACE, srcFilename, volumePath, e.getMessage());
            restoreMovedAsideFileVolume(volumePath, movedAsideTarget);
            return false;
        } finally {
            if (temporaryVolumePath != null) {
                try {
                    Files.deleteIfExists(temporaryVolumePath);
                } catch (final IOException e) {
                    logger.warn("{} phase=[TEMP_TARGET_DELETE_FAILED], temporaryTarget=[{}], error=[{}]",
                            RESTORE_TRACE, temporaryVolumePath, e.getMessage());
                }
            }
        }
    }

    private Path createTemporaryVolumePath(final String volumePath, final String prefix, final QemuImg.PhysicalDiskFormat targetFormat) throws IOException {
        final Path targetPath = Paths.get(volumePath).toAbsolutePath();
        final Path targetDirectory = targetPath.getParent();
        final String suffix = "." + targetFormat.toString().toLowerCase(Locale.ROOT);
        return targetDirectory != null ? Files.createTempFile(targetDirectory, prefix, suffix) : Files.createTempFile(prefix, suffix);
    }

    private void validatePrimaryStorageSpaceForFileRestorePlan(final List<String> volumePaths, final List<List<String>> backupPathsByVolume,
            final List<PrimaryDataStoreTO> restoreVolumePools) {
        final Map<Path, Long> persistentGrowthBytesByDirectory = new HashMap<>();
        final Map<Path, Long> transientBytesByDirectory = new HashMap<>();
        for (int idx = 0; idx < volumePaths.size(); idx++) {
            final PrimaryDataStoreTO restoreVolumePool = restoreVolumePools.get(idx);
            if (restoreVolumePool.getPoolType() == Storage.StoragePoolType.RBD) {
                continue;
            }
            final String volumePath = volumePaths.get(idx);
            final List<String> backupPaths = backupPathsByVolume.get(idx);
            validateResolvedChainPaths(backupPaths, volumePath);
            final Path targetDirectory = getTargetDirectory(volumePath);
            try {
                final long backupRequiredBytes = estimateRequiredBytesForFileRestore(getLastExistingBackupPath(backupPaths));
                final Path targetPath = Paths.get(volumePath);
                if (Files.exists(targetPath)) {
                    final long existingBytes = estimateRequiredBytesForFileRestore(volumePath);
                    persistentGrowthBytesByDirectory.merge(targetDirectory, Math.max(backupRequiredBytes - existingBytes, 0L), Long::sum);
                    transientBytesByDirectory.merge(targetDirectory, backupRequiredBytes, Long::max);
                } else {
                    persistentGrowthBytesByDirectory.merge(targetDirectory, backupRequiredBytes, Long::sum);
                }
            } catch (final QemuImgException | LibvirtException e) {
                throw new CloudRuntimeException(String.format("Failed to estimate primary storage requirement for target [%s]: %s",
                        volumePath, e.getMessage()), e);
            }
        }

        for (final Map.Entry<Path, Long> entry : persistentGrowthBytesByDirectory.entrySet()) {
            final Path targetDirectory = entry.getKey();
            final long persistentGrowthBytes = entry.getValue();
            final long transientBytes = transientBytesByDirectory.getOrDefault(targetDirectory, 0L);
            final long requiredBytes = persistentGrowthBytes + transientBytes;
            final long bufferBytes = Math.max(RESTORE_PRIMARY_SPACE_BUFFER_BYTES, requiredBytes / 5L);
            final long minimumAvailableBytes = requiredBytes + bufferBytes;
            final long availableBytes;
            try {
                availableBytes = Files.getFileStore(targetDirectory).getUsableSpace();
            } catch (final IOException e) {
                throw new CloudRuntimeException(String.format("Failed to query primary storage space under [%s]: %s", targetDirectory, e.getMessage()), e);
            }
            logger.info("{} phase=[PRIMARY_SPACE_PLAN_CHECK], targetDirectory=[{}], persistentGrowthBytes=[{}], transientBytes=[{}], requiredBytes=[{}], bufferBytes=[{}], minimumAvailableBytes=[{}], availableBytes=[{}], volumeCount=[{}]",
                    RESTORE_TRACE, targetDirectory, persistentGrowthBytes, transientBytes, requiredBytes, bufferBytes, minimumAvailableBytes, availableBytes, volumePaths.size());
            if (availableBytes < minimumAvailableBytes) {
                throw new CloudRuntimeException(String.format(
                        "Insufficient primary storage space for Veeam restore under [%s]. Required at least [%d] bytes including buffer for the restore plan, but only [%d] bytes are available.",
                        targetDirectory, minimumAvailableBytes, availableBytes));
            }
        }
        for (final Map.Entry<Path, Long> entry : transientBytesByDirectory.entrySet()) {
            final Path targetDirectory = entry.getKey();
            if (persistentGrowthBytesByDirectory.containsKey(targetDirectory)) {
                continue;
            }
            final long transientBytes = entry.getValue();
            final long bufferBytes = Math.max(RESTORE_PRIMARY_SPACE_BUFFER_BYTES, transientBytes / 5L);
            final long minimumAvailableBytes = transientBytes + bufferBytes;
            final long availableBytes;
            try {
                availableBytes = Files.getFileStore(targetDirectory).getUsableSpace();
            } catch (final IOException e) {
                throw new CloudRuntimeException(String.format("Failed to query primary storage space under [%s]: %s", targetDirectory, e.getMessage()), e);
            }
            logger.info("{} phase=[PRIMARY_SPACE_PLAN_CHECK], targetDirectory=[{}], persistentGrowthBytes=[0], transientBytes=[{}], requiredBytes=[{}], bufferBytes=[{}], minimumAvailableBytes=[{}], availableBytes=[{}], volumeCount=[{}]",
                    RESTORE_TRACE, targetDirectory, transientBytes, transientBytes, bufferBytes, minimumAvailableBytes, availableBytes, volumePaths.size());
            if (availableBytes < minimumAvailableBytes) {
                throw new CloudRuntimeException(String.format(
                        "Insufficient primary storage space for Veeam restore under [%s]. Required at least [%d] bytes including buffer for the restore plan, but only [%d] bytes are available.",
                        targetDirectory, minimumAvailableBytes, availableBytes));
            }
        }
    }

    private void validatePrimaryStorageSpaceForFileRestore(final String backupPath, final String volumePath) throws IOException, QemuImgException, LibvirtException {
        final Path targetDirectory = getTargetDirectory(volumePath);
        final long requiredBytes = estimateRequiredBytesForFileRestore(backupPath);
        final long bufferBytes = Math.max(RESTORE_PRIMARY_SPACE_BUFFER_BYTES, requiredBytes / 5L);
        final long minimumAvailableBytes = requiredBytes + bufferBytes;
        final long availableBytes = Files.getFileStore(targetDirectory).getUsableSpace();
        logger.info("{} phase=[PRIMARY_SPACE_CHECK], source=[{}], target=[{}], targetDirectory=[{}], requiredBytes=[{}], bufferBytes=[{}], minimumAvailableBytes=[{}], availableBytes=[{}]",
                RESTORE_TRACE, backupPath, volumePath, targetDirectory, requiredBytes, bufferBytes, minimumAvailableBytes, availableBytes);
        if (availableBytes < minimumAvailableBytes) {
            throw new CloudRuntimeException(String.format(
                    "Insufficient primary storage space for Veeam restore target [%s]. Required at least [%d] bytes including buffer, but only [%d] bytes are available under [%s].",
                    volumePath, minimumAvailableBytes, availableBytes, targetDirectory));
        }
    }

    private Path getTargetDirectory(final String volumePath) {
        final Path targetPath = Paths.get(volumePath).toAbsolutePath();
        final Path targetDirectory = targetPath.getParent();
        return targetDirectory != null ? targetDirectory : Paths.get(".").toAbsolutePath();
    }

    private long estimateRequiredBytesForFileRestore(final String backupPath) throws QemuImgException, LibvirtException {
        try {
            final QemuImg qemu = new QemuImg(0);
            final Map<String, String> info = qemu.info(new QemuImgFile(backupPath, getBackupFileFormat(backupPath)));
            final String virtualSize = info.get(QemuImg.VIRTUAL_SIZE);
            if (StringUtils.isNotBlank(virtualSize)) {
                return Long.parseLong(virtualSize);
            }
        } catch (final NumberFormatException e) {
            logger.warn("Failed to parse virtual size for backup [{}]. Falling back to file size.", backupPath, e);
        }
        try {
            return Files.size(Paths.get(backupPath));
        } catch (final IOException e) {
            throw new QemuImgException(String.format("Failed to estimate restore size for backup [%s]: %s", backupPath, e.getMessage()));
        }
    }

    private Path moveExistingFileVolumeAside(final String volumePath) throws IOException {
        final Path targetPath = Paths.get(volumePath);
        if (!Files.exists(targetPath)) {
            return null;
        }

        final Path movedAsidePath = targetPath.resolveSibling(targetPath.getFileName() + ".csrestore." + System.currentTimeMillis() + ".bak");
        Files.move(targetPath, movedAsidePath);
        logger.info("{} phase=[TARGET_MOVED_ASIDE], target=[{}], movedAside=[{}]",
                RESTORE_TRACE, volumePath, movedAsidePath);
        return movedAsidePath;
    }

    private void deleteMovedAsideFileVolume(final Path movedAsideTarget) {
        if (movedAsideTarget == null) {
            return;
        }
        try {
            Files.deleteIfExists(movedAsideTarget);
            logger.info("{} phase=[TARGET_MOVED_ASIDE_DELETED], movedAside=[{}]",
                    RESTORE_TRACE, movedAsideTarget);
        } catch (final IOException e) {
            logger.warn("{} phase=[TARGET_MOVED_ASIDE_DELETE_FAILED], movedAside=[{}], error=[{}]",
                    RESTORE_TRACE, movedAsideTarget, e.getMessage());
        }
    }

    private void restoreMovedAsideFileVolume(final String volumePath, final Path movedAsideTarget) {
        if (movedAsideTarget == null || !Files.exists(movedAsideTarget)) {
            return;
        }

        final Path targetPath = Paths.get(volumePath);
        try {
            Files.deleteIfExists(targetPath);
            Files.move(movedAsideTarget, targetPath);
            logger.info("{} phase=[TARGET_MOVED_ASIDE_RESTORED], target=[{}], movedAside=[{}]",
                    RESTORE_TRACE, volumePath, movedAsideTarget);
        } catch (final IOException e) {
            logger.error("{} phase=[TARGET_MOVED_ASIDE_RESTORE_FAILED], target=[{}], movedAside=[{}], error=[{}]",
                    RESTORE_TRACE, volumePath, movedAsideTarget, e.getMessage());
        }
    }

    private boolean hasBackingChain(final String qcow2Path) {
        return Script.runSimpleBashScriptForExitValue(String.format(QEMU_IMG_HAS_BACKING_COMMAND, quote(qcow2Path)), 0, false) == 0;
    }

    private void restoreFileVolumeData(final String backupPath, final String volumePath, final QemuImg.PhysicalDiskFormat backupFormat,
            final QemuImg.PhysicalDiskFormat volumeFormat, final int timeout) throws QemuImgException, LibvirtException {
        if (backupFormat == QemuImg.PhysicalDiskFormat.QCOW2 && volumeFormat == QemuImg.PhysicalDiskFormat.QCOW2 && !hasBackingChain(backupPath)) {
            rsyncQcow2BackupFile(backupPath, volumePath, timeout);
            return;
        }
        convertFileVolumeWithQemuImg(backupPath, volumePath, backupFormat, volumeFormat, timeout);
    }

    private void rsyncQcow2BackupFile(final String backupPath, final String volumePath, final int timeout) throws QemuImgException {
        final String rsyncCommand = String.format("rsync -az %s %s", quote(backupPath), quote(volumePath));
        final CommandExecutionResult result = executeBashCommandWithResult(rsyncCommand, timeout, "Rsync standalone QCOW2 backup to file volume");
        if (result.exitCode != 0) {
            logger.warn("{} phase=[RSYNC], source=[{}], target=[{}], command=[rsync-qcow2], exitCode=[{}], output=[{}]",
                    RESTORE_TRACE, backupPath, volumePath, result.exitCode, result.output);
            throw new QemuImgException(String.format("rsync qcow2 backup failed with exitCode [%s], output [%s]", result.exitCode, result.output));
        }
        logger.info("{} phase=[RSYNC], source=[{}], target=[{}], command=[rsync-qcow2]",
                RESTORE_TRACE, backupPath, volumePath);
    }

    private void convertFileVolumeWithQemuImg(final String backupPath, final String volumePath, final QemuImg.PhysicalDiskFormat backupFormat,
            final QemuImg.PhysicalDiskFormat volumeFormat, final int timeout) throws QemuImgException, LibvirtException {
        final QemuImg qemu = new QemuImg(timeout * 1000, true, false);
        qemu.convert(new QemuImgFile(backupPath, backupFormat), new QemuImgFile(volumePath, volumeFormat));
        logger.info("{} phase=[CONVERT], source=[{}], target=[{}], command=[qemu-img-convert]",
                RESTORE_TRACE, backupPath, volumePath);
    }

    private boolean replaceFileVolumeWithBackup(final String volumePath, final List<String> backupPaths, final int timeout) {
        if (backupPaths == null || backupPaths.isEmpty()) {
            return false;
        }
        if (backupPaths.size() == 1) {
            return replaceFileVolumeWithBackup(volumePath, getLastExistingBackupPath(backupPaths), timeout);
        }

        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("cs-veeam-qcow2-chain-");
            Path latestChainFile = null;
            Path previousChainFile = null;
            for (int index = 0; index < backupPaths.size(); index++) {
                final String backupPath = backupPaths.get(index);
                if (StringUtils.isBlank(backupPath)) {
                    continue;
                }
                final Path source = Paths.get(backupPath);
                if (!Files.exists(source)) {
                    throw new CloudRuntimeException(String.format("Missing QCOW2 backup chain file [%s] for restore", backupPath));
                }
                final Path copiedChainFile = tempDir.resolve(String.format("%03d-%s", index, source.getFileName()));
                Files.copy(source, copiedChainFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                if (previousChainFile != null) {
                    rebaseBackupChainFile(copiedChainFile, previousChainFile, timeout);
                }
                previousChainFile = copiedChainFile;
                latestChainFile = copiedChainFile;
            }
            if (latestChainFile == null) {
                throw new CloudRuntimeException(String.format("No QCOW2 backup chain files were prepared for restore to volume [%s]", volumePath));
            }
            return replaceFileVolumeWithBackup(volumePath, latestChainFile.toString(), timeout);
        } catch (final IOException e) {
            logger.error("Failed to reconstruct QCOW2 backup chain {} for volume {}: {}", backupPaths, volumePath, e.getMessage(), e);
            return false;
        } finally {
            if (tempDir != null) {
                try {
                    FileUtils.deleteDirectory(tempDir.toFile());
                } catch (final IOException e) {
                    logger.warn("Failed to delete temporary QCOW2 restore chain directory {}", tempDir, e);
                }
            }
        }
    }

    private void rebaseBackupChainFile(final Path child, final Path parent, final int timeout) throws IOException {
        final String command = String.format("qemu-img rebase -u -F qcow2 -b %s %s", quote(parent.toString()), quote(child.toString()));
        final CommandExecutionResult result = executeBashCommandWithResult(command, timeout, "Rebase QCOW2 restore chain");
        if (result.exitCode != 0) {
            throw new IOException(String.format("qemu-img rebase failed for %s with parent %s: %s", child, parent, result.output));
        }
    }

    private QemuImg.PhysicalDiskFormat getBackupFileFormat(final String backupPath) {
        if (backupPath.endsWith(".raw")) {
            return QemuImg.PhysicalDiskFormat.RAW;
        }
        return QemuImg.PhysicalDiskFormat.QCOW2;
    }

    private QemuImg.PhysicalDiskFormat getFileVolumeFormat(final String volumePath) {
        if (!Files.exists(Paths.get(volumePath))) {
            return QemuImg.PhysicalDiskFormat.QCOW2;
        }
        try {
            final QemuImg qemu = new QemuImg(0);
            final Map<String, String> info = qemu.info(new QemuImgFile(volumePath));
            final String format = info.get("file_format");
            if (StringUtils.isNotBlank(format)) {
                return QemuImg.PhysicalDiskFormat.valueOf(format.toUpperCase(Locale.ROOT));
            }
        } catch (final QemuImgException | LibvirtException | IllegalArgumentException e) {
            logger.warn("Failed to detect file volume format for path {}. Falling back to qcow2.", volumePath, e);
        }
        return QemuImg.PhysicalDiskFormat.QCOW2;
    }

    private boolean replaceRbdVolumeWithBackup(final KVMStoragePoolManager storagePoolMgr, final PrimaryDataStoreTO volumePool,
            final String volumePath, final List<String> backupPaths, final int timeout, final boolean createTargetVolume) {
        final KVMStoragePool volumeStoragePool = storagePoolMgr.getStoragePool(volumePool.getPoolType(), volumePool.getUuid());
        final String normalizedVolumePath = normalizeRbdVolumePath(volumePath, volumeStoragePool);
        return LibvirtAblestackRbdRestoreHelper.restoreRbdBackup(RESTORE_TRACE, volumeStoragePool, normalizedVolumePath,
                backupPaths, timeout, createTargetVolume);
    }

    private String normalizeRbdVolumePath(final String volumePath, final KVMStoragePool storagePool) {
        if (StringUtils.isBlank(volumePath)) {
            return volumePath;
        }
        String normalized = volumePath;
        final String poolPath = storagePool.getSourceDir();
        if (StringUtils.isNotBlank(poolPath)) {
            final String poolPrefix = poolPath + "/";
            if (normalized.startsWith(poolPrefix)) {
                normalized = normalized.substring(poolPrefix.length());
            }
        }
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(normalized.lastIndexOf('/') + 1);
        }
        return normalized;
    }

    private CommandExecutionResult executeBashCommandWithResult(final String command, final int timeoutInSeconds, final String description) {
        logger.debug("{} command: {}", description, command);
        final String wrappedCommand = String.format("set -o pipefail; { %s; } 2>&1; rc=$?; echo \"%s${rc}\"", command, COMMAND_EXIT_MARKER);
        final String output = Script.runSimpleBashScriptWithFullResult(wrappedCommand, timeoutInSeconds);
        if (StringUtils.isBlank(output)) {
            return new CommandExecutionResult(-1, "");
        }
        final int markerIndex = output.lastIndexOf(COMMAND_EXIT_MARKER);
        if (markerIndex < 0) {
            logger.warn("{} command output did not include an exit marker. Output: {}", description, output);
            return new CommandExecutionResult(-1, output.trim());
        }
        final String commandOutput = output.substring(0, markerIndex).trim();
        final String exitCodeString = output.substring(markerIndex + COMMAND_EXIT_MARKER.length()).trim();
        int exitCode;
        try {
            exitCode = Integer.parseInt(exitCodeString);
        } catch (final NumberFormatException e) {
            logger.warn("{} command exit marker was not a valid integer. Output: {}", description, output, e);
            exitCode = -1;
        }
        if (exitCode == 0) {
            logger.debug("{} command completed successfully. Output: {}", description, commandOutput);
        } else {
            logger.error("{} command failed with exit code {}. Output: {}", description, exitCode, commandOutput);
        }
        return new CommandExecutionResult(exitCode, commandOutput);
    }

    private static final class CommandExecutionResult {
        private final int exitCode;
        private final String output;

        private CommandExecutionResult(final int exitCode, final String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    private boolean rbdSnapshotExists(final KVMStoragePool storagePool, final String volumePath, final String snapshotName, final int timeout) {
        final String existsCommand = buildRbdSnapshotCommand(storagePool, "snap ls", volumePath) + " | awk 'NR>1 {print $2}' | grep -Fx " + quote(snapshotName);
        return Script.runSimpleBashScriptForExitValue(existsCommand, timeout * 1000, false) == 0;
    }

    private String buildRbdSnapshotCommand(final KVMStoragePool storagePool, final String action, final String target) {
        final StringBuilder command = new StringBuilder("rbd");
        if (StringUtils.isNotBlank(storagePool.getSourceHost())) {
            command.append(" -m ").append(formatRbdMonHosts(storagePool.getSourceHost(), storagePool.getSourcePort()));
        }
        if (StringUtils.isNotBlank(storagePool.getAuthUserName())) {
            command.append(" --id ").append(storagePool.getAuthUserName());
        }
        if (StringUtils.isNotBlank(storagePool.getAuthSecret())) {
            command.append(" --key ").append(storagePool.getAuthSecret());
        }
        command.append(" ").append(action).append(" ").append(target);
        return command.toString();
    }

    private String formatRbdMonHosts(final String hosts, final int port) {
        final String[] hostValues = hosts.split(",");
        final List<String> formattedHosts = new ArrayList<>();
        for (final String host : hostValues) {
            final String normalizedHost = host.replace("[", "").replace("]", "").trim();
            if (StringUtils.isBlank(normalizedHost)) {
                continue;
            }
            formattedHosts.add(port > 0 ? normalizedHost + ":" + port : normalizedHost);
        }
        return String.join(",", formattedHosts);
    }

    private String quote(final String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private boolean attachVolumeToVm(final KVMStoragePoolManager storagePoolMgr, final String vmName, final PrimaryDataStoreTO volumePool,
            final String volumePath, String cacheMode) {
        final String deviceToAttachDiskTo = getDeviceToAttachDisk(vmName);
        final int exitValue;
        if (volumePool.getPoolType() != Storage.StoragePoolType.RBD) {
            exitValue = Script.runSimpleBashScriptForExitValue(String.format(ATTACH_QCOW2_DISK_COMMAND, vmName, volumePath, deviceToAttachDiskTo));
        } else {
            final String xmlForRbdDisk = getXmlForRbdDisk(storagePoolMgr, volumePool, volumePath, deviceToAttachDiskTo, cacheMode);
            logger.debug("RBD disk xml to attach: {}", xmlForRbdDisk);
            exitValue = Script.runSimpleBashScriptForExitValue(String.format(ATTACH_RBD_DISK_XML_COMMAND, vmName, xmlForRbdDisk));
        }
        return exitValue == 0;
    }

    private String getDeviceToAttachDisk(final String vmName) {
        final String currentDevice = Script.runSimpleBashScript(String.format(CURRENT_DEVICE, vmName));
        if (StringUtils.isBlank(currentDevice)) {
            throw new CloudRuntimeException(String.format("Unable to determine next disk target for VM [%s].", vmName));
        }
        final char lastChar = currentDevice.charAt(currentDevice.length() - 1);
        final char incrementedChar = (char) (lastChar + 1);
        return currentDevice.substring(0, currentDevice.length() - 1) + incrementedChar;
    }

    private String getXmlForRbdDisk(final KVMStoragePoolManager storagePoolMgr, final PrimaryDataStoreTO volumePool,
            final String volumePath, final String deviceToAttachDiskTo, String cacheMode) {
        final StringBuilder diskBuilder = new StringBuilder();
        diskBuilder.append("\n<disk ");
        diskBuilder.append(" device='disk'");
        diskBuilder.append(" type='network'");
        diskBuilder.append(">\n");

        diskBuilder.append("<driver name='qemu' type='raw'");
        if (StringUtils.isBlank(cacheMode)) {
            cacheMode = "none";
        }
        diskBuilder.append(" cache='").append(cacheMode).append("'/> \n");

        diskBuilder.append("<source ");
        diskBuilder.append(" protocol='rbd'");
        diskBuilder.append(" name='").append(volumePath).append("'");
        diskBuilder.append(">\n");
        if (StringUtils.isNotBlank(volumePool.getHost())) {
            diskBuilder.append("<host name='").append(volumePool.getHost());
            if (volumePool.getPort() != 0) {
                diskBuilder.append("' port='");
                diskBuilder.append(volumePool.getPort());
            }
            diskBuilder.append("'/>\n");
        }
        diskBuilder.append("</source>\n");
        String authUserName = null;
        final KVMStoragePool primaryPool = storagePoolMgr.getStoragePool(volumePool.getPoolType(), volumePool.getUuid());
        if (primaryPool != null) {
            authUserName = primaryPool.getAuthUserName();
        }
        if (StringUtils.isNotBlank(authUserName)) {
            diskBuilder.append("<auth username='").append(authUserName).append("'>\n");
            diskBuilder.append("<secret type='ceph' uuid='").append(volumePool.getUuid()).append("'/>\n");
            diskBuilder.append("</auth>\n");
        }
        diskBuilder.append("<target dev='").append(deviceToAttachDiskTo).append("'");
        diskBuilder.append(" bus='virtio'");
        diskBuilder.append("/>\n");
        diskBuilder.append("</disk>\n");
        return diskBuilder.toString();
    }

}
