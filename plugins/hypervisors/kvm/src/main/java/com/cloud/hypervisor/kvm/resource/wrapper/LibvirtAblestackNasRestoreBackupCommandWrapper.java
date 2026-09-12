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
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.script.Script;
import com.cloud.vm.VirtualMachine;
import org.apache.cloudstack.backup.AblestackBackupFrameworkUtils;
import org.apache.cloudstack.backup.BackupAnswer;
import org.apache.cloudstack.backup.AblestackNasRestoreBackupCommand;
import org.apache.cloudstack.backup.BackupRestorePlan;
import org.apache.cloudstack.backup.BackupRestoreStage;
import org.apache.cloudstack.backup.BackupVolumeChainState;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;
import org.apache.cloudstack.utils.qemu.QemuImg;
import org.apache.cloudstack.utils.qemu.QemuImgException;
import org.apache.cloudstack.utils.qemu.QemuImgFile;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.libvirt.LibvirtException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@ResourceWrapper(handles = AblestackNasRestoreBackupCommand.class)
public class LibvirtAblestackNasRestoreBackupCommandWrapper extends CommandWrapper<AblestackNasRestoreBackupCommand, Answer, LibvirtComputingResource> {
    private static final String RESTORE_TRACE = "[ABLESTACK_NAS_RESTORE_TRACE]";
    private static final String BACKUP_TEMP_FILE_PREFIX = "csbackup";
    private static final String MOUNT_COMMAND = "sudo mount -t %s %s %s";
    private static final String UMOUNT_COMMAND = "sudo umount %s";
    private static final String FILE_PATH_PLACEHOLDER = "%s/%s";
    private static final String ATTACH_QCOW2_DISK_COMMAND = " virsh attach-disk %s %s %s --driver qemu --subdriver qcow2 --cache none";
    private static final String ATTACH_RBD_DISK_XML_COMMAND = " virsh attach-device %s /dev/stdin <<EOF%sEOF";
    private static final String CURRRENT_DEVICE = "virsh domblklist --domain %s | tail -n 3 | head -n 1 | awk '{print $1}'";
    private static final String QEMU_IMG_HAS_BACKING_COMMAND = "qemu-img info --output=json %s 2>/dev/null | grep -q '\"backing-filename\"'";
    private static final long RESTORE_PRIMARY_SPACE_BUFFER_BYTES = 10L * 1024L * 1024L * 1024L;
    @Override
    public Answer execute(AblestackNasRestoreBackupCommand command, LibvirtComputingResource serverResource) {
        String vmName = command.getVmName();
        String backupPath = command.getBackupPath();
        String backupRepoAddress = command.getBackupRepoAddress();
        String backupRepoType = command.getBackupRepoType();
        String mountOptions = command.getMountOptions();
        Boolean vmExists = command.isVmExists();
        List<PrimaryDataStoreTO> restoreVolumePools = command.getRestoreVolumePools();
        List<String> restoreVolumePaths = command.getRestoreVolumePaths();
        Integer mountTimeout = command.getMountTimeout() * 1000;
        int timeout = command.getWait() > 0 ? command.getWait() : command.getMountTimeout();
        String cacheMode = command.getCacheMode();
        KVMStoragePoolManager storagePoolMgr = serverResource.getStoragePoolMgr();
        List<String> volumePaths = command.getVolumePaths();
        List<String> backupFiles = command.getBackupFiles();
        List<String> backupFileChains = command.getBackupFileChains();
        List<BackupVolumeChainState> volumeChainStates = command.getVolumeChainStates();
        BackupRestorePlan restorePlan = command.getRestorePlan();

        logger.info("{} phase=[ENTER], vm=[{}], backupPath=[{}], repoType=[{}], repoAddress=[{}], vmExists=[{}], restorePlan=[{}], volumePaths=[{}], restoreVolumePaths=[{}], backupFiles=[{}], backupFileChains=[{}]",
                RESTORE_TRACE, vmName, backupPath, backupRepoType, backupRepoAddress, vmExists, restorePlan, volumePaths, restoreVolumePaths, backupFiles, backupFileChains);
        String newVolumeId = null;
        try {
            validateChainStatePlan(volumeChainStates, restorePlan);
            String mountDirectory = AblestackBackupFrameworkUtils.hasRestoreStage(restorePlan, BackupRestoreStage.PREPARE_SOURCE)
                    ? mountBackupDirectory(backupRepoAddress, backupRepoType, mountOptions, mountTimeout) : null;
            if (Objects.isNull(vmExists)) {
                String volumePath = volumePaths.get(0);
                String backupFile = backupFiles.get(0);
                BackupVolumeChainState volumeChainState = volumeChainStates != null && !volumeChainStates.isEmpty() ? volumeChainStates.get(0) : null;
                String backupFileChain = volumeChainState != null ? String.join(";", volumeChainState.getChainFiles()) :
                        (backupFileChains != null && !backupFileChains.isEmpty() ? backupFileChains.get(0) : null);
                validateResolvedChainPaths(getMountedBackupPaths(mountDirectory, backupPath, backupFile, backupFileChain), volumePath);
                int lastIndex = volumePath.lastIndexOf("/");
                newVolumeId = volumePath.substring(lastIndex + 1);
                restoreVolume(backupPath, backupRepoType, backupRepoAddress, volumePath, backupFile, backupFileChain,
                        new Pair<>(vmName, command.getVmState()), mountOptions, mountTimeout, timeout, storagePoolMgr, restoreVolumePools.get(0), cacheMode, restorePlan);
            } else if (Boolean.TRUE.equals(vmExists)) {
                restoreVolumesOfExistingVM(restoreVolumePaths, backupPath, backupFiles, backupFileChains, volumeChainStates, mountDirectory, timeout, storagePoolMgr,
                        restoreVolumePools, restorePlan);
            } else {
                restoreVolumesOfDestroyedVMs(restoreVolumePaths, backupPath, backupFiles, backupFileChains, volumeChainStates, backupRepoAddress, backupRepoType, mountOptions,
                        mountTimeout, storagePoolMgr, restoreVolumePools, timeout, restorePlan);
            }
        } catch (CloudRuntimeException e) {
            String errorMessage = e.getMessage() != null ? e.getMessage() : "";
            return new BackupAnswer(command, false, errorMessage);
        }

        logger.info("{} phase=[DONE], vm=[{}], backupPath=[{}], vmExists=[{}], newVolumeId=[{}]",
                RESTORE_TRACE, vmName, backupPath, vmExists, newVolumeId);
        return new BackupAnswer(command, true, newVolumeId);
    }

    private void restoreVolumesOfExistingVM(List<String> volumePaths, String backupPath, List<String> backupFiles, List<String> backupFileChains,
                                            List<BackupVolumeChainState> volumeChainStates,
                                            String mountDirectory, Integer timeout, KVMStoragePoolManager storagePoolMgr, List<PrimaryDataStoreTO> restoreVolumePools,
                                            BackupRestorePlan restorePlan) {
        try {
            List<List<String>> mountedBackupPathsByVolume = getMountedBackupPathsForVolumes(mountDirectory, backupPath, backupFiles, backupFileChains, volumeChainStates, volumePaths);
            validatePrimaryStorageSpaceForFileRestorePlan(volumePaths, mountedBackupPathsByVolume, restoreVolumePools);
            for (int idx = 0; idx < volumePaths.size(); idx++) {
                String volumePath = volumePaths.get(idx);
                List<String> mountedBackupPaths = mountedBackupPathsByVolume.get(idx);
                validateResolvedChainPaths(mountedBackupPaths, volumePath);
                PrimaryDataStoreTO restoreVolumePool = restoreVolumePools.get(idx);
                if (!replaceVolumeWithBackup(storagePoolMgr, restoreVolumePool, volumePath, mountedBackupPaths, timeout,
                        String.format(FILE_PATH_PLACEHOLDER, mountDirectory, backupPath), idx)) {
                    throw new CloudRuntimeException(String.format("Unable to restore backup from volume [%s].", volumePath));
                }
            }
        } finally {
            cleanupMountedBackupDirectory(mountDirectory, restorePlan);
        }
    }

    private void restoreVolumesOfDestroyedVMs(List<String> volumePaths, String backupPath, List<String> backupFiles, List<String> backupFileChains,
                                              List<BackupVolumeChainState> volumeChainStates,
                                              String backupRepoAddress, String backupRepoType, String mountOptions, Integer mountTimeout, KVMStoragePoolManager storagePoolMgr,
                                              List<PrimaryDataStoreTO> restoreVolumePools, Integer timeout, BackupRestorePlan restorePlan) {
        String mountDirectory = AblestackBackupFrameworkUtils.hasRestoreStage(restorePlan, BackupRestoreStage.PREPARE_SOURCE)
                ? mountBackupDirectory(backupRepoAddress, backupRepoType, mountOptions, mountTimeout) : null;
        try {
            List<List<String>> mountedBackupPathsByVolume = getMountedBackupPathsForVolumes(mountDirectory, backupPath, backupFiles, backupFileChains, volumeChainStates, volumePaths);
            validatePrimaryStorageSpaceForFileRestorePlan(volumePaths, mountedBackupPathsByVolume, restoreVolumePools);
            for (int idx = 0; idx < volumePaths.size(); idx++) {
                String volumePath = volumePaths.get(idx);
                List<String> mountedBackupPaths = mountedBackupPathsByVolume.get(idx);
                validateResolvedChainPaths(mountedBackupPaths, volumePath);
                PrimaryDataStoreTO restoreVolumePool = restoreVolumePools.get(idx);
                if (!replaceVolumeWithBackup(storagePoolMgr, restoreVolumePool, volumePath, mountedBackupPaths, timeout,
                        String.format(FILE_PATH_PLACEHOLDER, mountDirectory, backupPath), idx)) {
                    throw new CloudRuntimeException(String.format("Unable to restore backup from volume [%s].", volumePath));
                }
            }
        } finally {
            cleanupMountedBackupDirectory(mountDirectory, restorePlan);
        }
    }

    private void restoreVolume(String backupPath, String backupRepoType, String backupRepoAddress, String volumePath, String backupFile, String backupFileChain,
                               Pair<String, VirtualMachine.State> vmNameAndState, String mountOptions, Integer mountTimeout, Integer timeout,
                               KVMStoragePoolManager storagePoolMgr, PrimaryDataStoreTO restoreVolumePool, String cacheMode, BackupRestorePlan restorePlan) {
        String mountDirectory = AblestackBackupFrameworkUtils.hasRestoreStage(restorePlan, BackupRestoreStage.PREPARE_SOURCE)
                ? mountBackupDirectory(backupRepoAddress, backupRepoType, mountOptions, mountTimeout) : null;
        try {
            List<String> mountedBackupPaths = getMountedBackupPaths(mountDirectory, backupPath, backupFile, backupFileChain);
            validateResolvedChainPaths(mountedBackupPaths, volumePath);
            validatePrimaryStorageSpaceForFileRestorePlan(List.of(volumePath), List.of(mountedBackupPaths), List.of(restoreVolumePool));
            if (!replaceVolumeWithBackup(storagePoolMgr, restoreVolumePool, volumePath, mountedBackupPaths, timeout,
                    String.format(FILE_PATH_PLACEHOLDER, mountDirectory, backupPath), 0, true)) {
                throw new CloudRuntimeException(String.format("Unable to restore backup from volume [%s].", volumePath));
            }
            if (AblestackBackupFrameworkUtils.hasRestoreStage(restorePlan, BackupRestoreStage.ATTACH_VOLUME)
                    && VirtualMachine.State.Running.equals(vmNameAndState.second())) {
                if (!attachVolumeToVm(storagePoolMgr, vmNameAndState.first(), restoreVolumePool, volumePath, cacheMode)) {
                    throw new CloudRuntimeException(String.format("Failed to attach volume to VM: %s", vmNameAndState.first()));
                }
            }
        } finally {
            cleanupMountedBackupDirectory(mountDirectory, restorePlan);
        }
    }

    private List<List<String>> getMountedBackupPathsForVolumes(String mountDirectory, String backupPath, List<String> backupFiles,
                                                               List<String> backupFileChains, List<BackupVolumeChainState> volumeChainStates,
                                                               List<String> volumePaths) {
        List<List<String>> mountedBackupPathsByVolume = new ArrayList<>();
        for (int idx = 0; idx < volumePaths.size(); idx++) {
            String backupFile = backupFiles.get(idx);
            BackupVolumeChainState volumeChainState = volumeChainStates != null && volumeChainStates.size() > idx ? volumeChainStates.get(idx) : null;
            String backupFileChain = volumeChainState != null ? String.join(";", volumeChainState.getChainFiles()) :
                    (backupFileChains != null && backupFileChains.size() > idx ? backupFileChains.get(idx) : null);
            mountedBackupPathsByVolume.add(getMountedBackupPaths(mountDirectory, backupPath, backupFile, backupFileChain));
        }
        return mountedBackupPathsByVolume;
    }

    private void validateChainStatePlan(List<BackupVolumeChainState> volumeChainStates, BackupRestorePlan restorePlan) {
        if (AblestackBackupFrameworkUtils.hasRestoreStage(restorePlan, BackupRestoreStage.VALIDATE_CHAIN) && volumeChainStates != null && !volumeChainStates.isEmpty()) {
            try {
                AblestackBackupFrameworkUtils.validateVolumeChainStates(volumeChainStates);
            } catch (IllegalArgumentException e) {
                throw new CloudRuntimeException(e.getMessage(), e);
            }
        }
    }

    private void validateResolvedChainPaths(List<String> resolvedPaths, String volumePath) {
        if (resolvedPaths == null || resolvedPaths.isEmpty()) {
            throw new CloudRuntimeException(String.format("No resolved backup chain paths found for volume [%s]", volumePath));
        }
    }

    private void cleanupMountedBackupDirectory(String mountDirectory, BackupRestorePlan restorePlan) {
        if (StringUtils.isBlank(mountDirectory)) {
            return;
        }
        if (AblestackBackupFrameworkUtils.hasRestoreStage(restorePlan, BackupRestoreStage.CLEANUP_SOURCE)) {
            unmountBackupDirectory(mountDirectory);
            deleteTemporaryDirectory(mountDirectory);
        }
    }


    private String mountBackupDirectory(String backupRepoAddress, String backupRepoType, String mountOptions, Integer mountTimeout) {
        String randomChars = RandomStringUtils.random(5, true, false);
        String mountDirectory = String.format("%s.%s",BACKUP_TEMP_FILE_PREFIX , randomChars);

        try {
            mountDirectory = Files.createTempDirectory(mountDirectory).toString();
        } catch (IOException e) {
            logger.error(String.format("Failed to create the tmp mount directory {} for restore", mountDirectory), e);
            throw new CloudRuntimeException("Failed to create the tmp mount directory for restore on the KVM host");
        }

        final String mount = LibvirtBackupRepositoryMountHelper.buildMountCommand(
                backupRepoAddress, backupRepoType, mountOptions, mountDirectory);

        int exitValue = Script.runSimpleBashScriptForExitValue(mount, mountTimeout, false);
        if (exitValue != 0) {
            logger.error(String.format("Failed to mount repository {} of type {} to the directory {}", backupRepoAddress, backupRepoType, mountDirectory));
            throw new CloudRuntimeException("Failed to mount the backup repository on the KVM host");
        }
        return mountDirectory;
    }

    private void unmountBackupDirectory(String backupDirectory) {
        String umountCmd = String.format(UMOUNT_COMMAND, backupDirectory);
        int exitValue = Script.runSimpleBashScriptForExitValue(umountCmd);
        if (exitValue != 0) {
            logger.error(String.format("Failed to unmount backup directory {}", backupDirectory));
            throw new CloudRuntimeException("Failed to unmount the backup directory");
        }
    }

    private void deleteTemporaryDirectory(String backupDirectory) {
        try {
            Files.deleteIfExists(Paths.get(backupDirectory));
        } catch (IOException e) {
            logger.error(String.format("Failed to delete backup directory: %s", backupDirectory), e);
            throw new CloudRuntimeException("Failed to delete the backup directory");
        }
    }

    private List<String> getMountedBackupPaths(String mountDirectory, String backupPath, String backupFile, String backupFileChain) {
        LinkedHashSet<String> mountedPaths = new LinkedHashSet<>();
        if (StringUtils.isNotBlank(backupFileChain)) {
            for (String chainPath : backupFileChain.split(";")) {
                if (StringUtils.isBlank(chainPath)) {
                    continue;
                }
                String normalizedPath = chainPath.startsWith("/") ? chainPath.substring(1) : chainPath;
                if (!normalizedPath.contains("/") && StringUtils.isNotBlank(backupPath)) {
                    mountedPaths.add(String.format(FILE_PATH_PLACEHOLDER, String.format(FILE_PATH_PLACEHOLDER, mountDirectory, backupPath), normalizedPath));
                } else {
                    mountedPaths.add(String.format(FILE_PATH_PLACEHOLDER, mountDirectory, normalizedPath));
                }
            }
        }
        if (mountedPaths.isEmpty() && StringUtils.isNotBlank(backupFile)) {
            mountedPaths.add(String.format(FILE_PATH_PLACEHOLDER, String.format(FILE_PATH_PLACEHOLDER, mountDirectory, backupPath), backupFile));
        }
        return new ArrayList<>(mountedPaths);
    }

    private boolean replaceVolumeWithBackup(KVMStoragePoolManager storagePoolMgr, PrimaryDataStoreTO volumePool, String volumePath, List<String> backupPaths, int timeout,
                                            String backupRootPath, int backupIndex) {
        return replaceVolumeWithBackup(storagePoolMgr, volumePool, volumePath, backupPaths, timeout, backupRootPath, backupIndex, false);
    }

    private boolean replaceVolumeWithBackup(KVMStoragePoolManager storagePoolMgr, PrimaryDataStoreTO volumePool, String volumePath, List<String> backupPaths, int timeout,
                                            String backupRootPath, int backupIndex, boolean createTargetVolume) {
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

    private boolean restoreIncrementalRbdBackupChainToFileVolume(String volumePath, List<String> backupPaths, int timeout, String backupRootPath, int backupIndex) {
        if (StringUtils.isBlank(backupRootPath)) {
            throw new CloudRuntimeException("Unable to locate backup root path for incremental RBD restore");
        }
        RbdImageSpec sourceImage = getRbdImageSpecFromMetadata(backupRootPath, backupIndex);
        String tempImage = sourceImage.buildTempImageSpec();
        try {
            if (!importBackupChainToTemporaryRbd(backupPaths, timeout, sourceImage, tempImage)) {
                return false;
            }
            return convertTemporaryRbdToFileVolume(volumePath, timeout, sourceImage, tempImage);
        } finally {
            removeTemporaryRbdImage(sourceImage, tempImage, timeout);
        }
    }

    private String getRestorableFileBackupPath(List<String> backupPaths) {
        for (int index = backupPaths.size() - 1; index >= 0; index--) {
            String backupPath = backupPaths.get(index);
            if (StringUtils.isNotBlank(backupPath) && Files.exists(Paths.get(backupPath))) {
                return backupPath;
            }
        }
        return backupPaths.get(backupPaths.size() - 1);
    }

    private boolean replaceFileVolumeWithBackup(String volumePath, List<String> backupPaths, int timeout) {
        if (backupPaths == null || backupPaths.isEmpty()) {
            return false;
        }
        if (backupPaths.size() == 1) {
            return replaceFileVolumeWithBackup(volumePath, getRestorableFileBackupPath(backupPaths), timeout);
        }

        String leafBackupPath = getRestorableFileBackupPath(backupPaths);
        logger.info("{} phase=[QCOW2_CHAIN_LEAF_SELECTED], target=[{}], leaf=[{}], chainFiles=[{}]",
                RESTORE_TRACE, volumePath, leafBackupPath, backupPaths);
        return replaceFileVolumeWithBackup(volumePath, leafBackupPath, timeout);
    }

    private boolean replaceFileVolumeWithBackup(String volumePath, String backupPath, int timeout) {
        QemuImgFile srcBackupFile = null;
        QemuImgFile destVolumeFile = null;
        Path temporaryVolumePath = null;
        Path movedAsideTarget = null;
        try {
            srcBackupFile = new QemuImgFile(backupPath, getBackupFileFormat(backupPath));
            QemuImg.PhysicalDiskFormat targetFormat = getFileVolumeFormat(volumePath);
            validatePrimaryStorageSpaceForFileRestore(backupPath, volumePath);
            destVolumeFile = new QemuImgFile(volumePath, targetFormat);
            logger.info("Restoring NAS backup file [{}] to file volume [{}] without target-is-zero optimization.", backupPath, volumePath);
            movedAsideTarget = moveExistingFileVolumeAside(volumePath);
            temporaryVolumePath = createTemporaryVolumePath(volumePath, "cs-nas-restore-volume-", targetFormat);
            Files.deleteIfExists(temporaryVolumePath);
            logger.info("{} phase=[TEMP_TARGET_CREATED], source=[{}], target=[{}], temporaryTarget=[{}], sourceFormat=[{}], targetFormat=[{}]",
                    RESTORE_TRACE, backupPath, volumePath, temporaryVolumePath, srcBackupFile.getFormat(), targetFormat);
            restoreFileVolumeData(backupPath, temporaryVolumePath.toString(), srcBackupFile.getFormat(), destVolumeFile.getFormat(), timeout);
            Files.move(temporaryVolumePath, Paths.get(volumePath), StandardCopyOption.REPLACE_EXISTING);
            logger.info("{} phase=[TEMP_TARGET_PROMOTED], target=[{}], temporaryTarget=[{}]",
                    RESTORE_TRACE, volumePath, temporaryVolumePath);
            deleteMovedAsideFileVolume(movedAsideTarget);
            return true;
        } catch (QemuImgException | LibvirtException | IOException e) {
            String srcFilename = srcBackupFile != null ? srcBackupFile.getFileName() : null;
            String destFilename = destVolumeFile != null ? destVolumeFile.getFileName() : null;
            logger.error("Failed to convert backup {} to volume {}, the error was: {}", srcFilename, destFilename, e.getMessage());
            restoreMovedAsideFileVolume(volumePath, movedAsideTarget);
            return false;
        } finally {
            if (temporaryVolumePath != null) {
                try {
                    Files.deleteIfExists(temporaryVolumePath);
                } catch (IOException e) {
                    logger.warn("{} phase=[TEMP_TARGET_DELETE_FAILED], temporaryTarget=[{}], error=[{}]",
                            RESTORE_TRACE, temporaryVolumePath, e.getMessage());
                }
            }
        }
    }

    private Path createTemporaryVolumePath(String volumePath, String prefix, QemuImg.PhysicalDiskFormat targetFormat) throws IOException {
        Path targetPath = Paths.get(volumePath).toAbsolutePath();
        Path targetDirectory = targetPath.getParent();
        String suffix = "." + targetFormat.toString().toLowerCase(Locale.ROOT);
        return targetDirectory != null ? Files.createTempFile(targetDirectory, prefix, suffix) : Files.createTempFile(prefix, suffix);
    }

    private void validatePrimaryStorageSpaceForFileRestorePlan(List<String> volumePaths, List<List<String>> backupPathsByVolume,
                                                               List<PrimaryDataStoreTO> restoreVolumePools) {
        Map<Path, Long> persistentGrowthBytesByDirectory = new HashMap<>();
        Map<Path, Long> peakRestoreBytesByDirectory = new HashMap<>();
        Map<Path, Integer> volumeCountByDirectory = new HashMap<>();
        for (int idx = 0; idx < volumePaths.size(); idx++) {
            PrimaryDataStoreTO restoreVolumePool = restoreVolumePools.get(idx);
            if (restoreVolumePool.getPoolType() == Storage.StoragePoolType.RBD) {
                continue;
            }
            String volumePath = volumePaths.get(idx);
            List<String> backupPaths = backupPathsByVolume.get(idx);
            validateResolvedChainPaths(backupPaths, volumePath);
            Path targetDirectory = getTargetDirectory(volumePath);
            try {
                long backupRequiredBytes = estimateRequiredBytesForFileRestore(getRestorableFileBackupPath(backupPaths));
                Path targetPath = Paths.get(volumePath);
                long persistentGrowthBeforeVolume = persistentGrowthBytesByDirectory.getOrDefault(targetDirectory, 0L);
                peakRestoreBytesByDirectory.merge(targetDirectory, persistentGrowthBeforeVolume + backupRequiredBytes, Math::max);
                volumeCountByDirectory.merge(targetDirectory, 1, Integer::sum);
                if (Files.exists(targetPath)) {
                    long existingBytes = estimateRequiredBytesForFileRestore(volumePath);
                    persistentGrowthBytesByDirectory.merge(targetDirectory, Math.max(backupRequiredBytes - existingBytes, 0L), Long::sum);
                } else {
                    persistentGrowthBytesByDirectory.merge(targetDirectory, backupRequiredBytes, Long::sum);
                }
            } catch (QemuImgException | LibvirtException e) {
                throw new CloudRuntimeException(String.format("Failed to estimate primary storage requirement for target [%s]: %s",
                        volumePath, e.getMessage()), e);
            }
        }

        for (Map.Entry<Path, Long> entry : persistentGrowthBytesByDirectory.entrySet()) {
            Path targetDirectory = entry.getKey();
            long persistentGrowthBytes = entry.getValue();
            long peakRestoreBytes = peakRestoreBytesByDirectory.getOrDefault(targetDirectory, 0L);
            long requiredBytes = Math.max(persistentGrowthBytes, peakRestoreBytes);
            long bufferBytes = Math.max(RESTORE_PRIMARY_SPACE_BUFFER_BYTES, requiredBytes / 5L);
            long minimumAvailableBytes = requiredBytes + bufferBytes;
            long availableBytes;
            try {
                availableBytes = Files.getFileStore(targetDirectory).getUsableSpace();
            } catch (IOException e) {
                throw new CloudRuntimeException(String.format("Failed to query primary storage space under [%s]: %s", targetDirectory, e.getMessage()), e);
            }
            logger.info("{} phase=[PRIMARY_SPACE_PLAN_CHECK], targetDirectory=[{}], persistentGrowthBytes=[{}], transientBytes=[{}], requiredBytes=[{}], bufferBytes=[{}], minimumAvailableBytes=[{}], availableBytes=[{}], volumeCount=[{}]",
                    RESTORE_TRACE, targetDirectory, persistentGrowthBytes, peakRestoreBytes, requiredBytes, bufferBytes, minimumAvailableBytes, availableBytes,
                    volumeCountByDirectory.getOrDefault(targetDirectory, 0));
            if (availableBytes < minimumAvailableBytes) {
                throw new CloudRuntimeException(String.format(
                        "Insufficient primary storage space for NAS restore under [%s]. Required at least [%d] bytes including buffer for the restore plan, but only [%d] bytes are available.",
                        targetDirectory, minimumAvailableBytes, availableBytes));
            }
        }
    }

    private void validatePrimaryStorageSpaceForFileRestore(String backupPath, String volumePath) throws IOException, QemuImgException, LibvirtException {
        Path targetDirectory = getTargetDirectory(volumePath);
        long requiredBytes = estimateRequiredBytesForFileRestore(backupPath);
        long bufferBytes = Math.max(RESTORE_PRIMARY_SPACE_BUFFER_BYTES, requiredBytes / 5L);
        long minimumAvailableBytes = requiredBytes + bufferBytes;
        long availableBytes = Files.getFileStore(targetDirectory).getUsableSpace();
        logger.info("{} phase=[PRIMARY_SPACE_CHECK], source=[{}], target=[{}], targetDirectory=[{}], requiredBytes=[{}], bufferBytes=[{}], minimumAvailableBytes=[{}], availableBytes=[{}]",
                RESTORE_TRACE, backupPath, volumePath, targetDirectory, requiredBytes, bufferBytes, minimumAvailableBytes, availableBytes);
        if (availableBytes < minimumAvailableBytes) {
            throw new CloudRuntimeException(String.format(
                    "Insufficient primary storage space for NAS restore target [%s]. Required at least [%d] bytes including buffer, but only [%d] bytes are available under [%s].",
                    volumePath, minimumAvailableBytes, availableBytes, targetDirectory));
        }
    }

    private Path getTargetDirectory(String volumePath) {
        Path targetPath = Paths.get(volumePath).toAbsolutePath();
        Path targetDirectory = targetPath.getParent();
        return targetDirectory != null ? targetDirectory : Paths.get(".").toAbsolutePath();
    }

    private long estimateRequiredBytesForVolumeRestore(String volumePath, List<String> backupPaths) {
        try {
            if (Files.exists(Paths.get(volumePath))) {
                return estimateRequiredBytesForFileRestore(volumePath);
            }
            return estimateRequiredBytesForFileRestore(getRestorableFileBackupPath(backupPaths));
        } catch (QemuImgException | LibvirtException e) {
            throw new CloudRuntimeException(String.format("Failed to estimate primary storage requirement for target [%s]: %s",
                    volumePath, e.getMessage()), e);
        }
    }

    private long estimateRequiredBytesForFileRestore(String backupPath) throws QemuImgException, LibvirtException {
        try {
            QemuImg qemu = new QemuImg(0);
            Map<String, String> info = qemu.info(new QemuImgFile(backupPath, getBackupFileFormat(backupPath)));
            String virtualSize = info.get(QemuImg.VIRTUAL_SIZE);
            if (StringUtils.isNotBlank(virtualSize)) {
                return Long.parseLong(virtualSize);
            }
        } catch (NumberFormatException e) {
            logger.warn("Failed to parse virtual size for backup [{}]. Falling back to file size.", backupPath, e);
        }
        try {
            return Files.size(Paths.get(backupPath));
        } catch (IOException e) {
            throw new QemuImgException(String.format("Failed to estimate restore size for backup [%s]: %s", backupPath, e.getMessage()));
        }
    }

    private boolean convertTemporaryRbdToFileVolume(String volumePath, int timeout, RbdImageSpec sourceImage, String tempImage) {
        QemuImgFile srcBackupFile = null;
        QemuImgFile destVolumeFile = null;
        try {
            QemuImg qemu = new QemuImg(timeout * 1000, true, false);
            srcBackupFile = new QemuImgFile(sourceImage.buildQemuUri(tempImage), QemuImg.PhysicalDiskFormat.RAW);
            destVolumeFile = new QemuImgFile(volumePath, getFileVolumeFormat(volumePath));
            qemu.convert(srcBackupFile, destVolumeFile);
            return true;
        } catch (QemuImgException | LibvirtException e) {
            String srcFilename = srcBackupFile != null ? srcBackupFile.getFileName() : tempImage;
            String destFilename = destVolumeFile != null ? destVolumeFile.getFileName() : volumePath;
            logger.error("Failed to convert temporary RBD {} to volume {}, the error was: {}", srcFilename, destFilename, e.getMessage());
            return false;
        }
    }

    private QemuImg.PhysicalDiskFormat getBackupFileFormat(String backupPath) {
        if (backupPath.endsWith(".raw")) {
            return QemuImg.PhysicalDiskFormat.RAW;
        }
        return QemuImg.PhysicalDiskFormat.QCOW2;
    }

    private QemuImg.PhysicalDiskFormat getFileVolumeFormat(String volumePath) {
        if (!Files.exists(Paths.get(volumePath))) {
            return QemuImg.PhysicalDiskFormat.QCOW2;
        }
        try {
            QemuImg qemu = new QemuImg(0);
            Map<String, String> info = qemu.info(new QemuImgFile(volumePath));
            String format = info.get("file_format");
            if (StringUtils.isNotBlank(format)) {
                return QemuImg.PhysicalDiskFormat.valueOf(format.toUpperCase(Locale.ROOT));
            }
        } catch (QemuImgException | LibvirtException | IllegalArgumentException e) {
            logger.warn("Failed to detect file volume format for path {}. Falling back to qcow2.", volumePath, e);
        }
        return QemuImg.PhysicalDiskFormat.QCOW2;
    }

    private boolean replaceRbdVolumeWithBackup(KVMStoragePoolManager storagePoolMgr, PrimaryDataStoreTO volumePool, String volumePath, List<String> backupPaths, int timeout, boolean createTargetVolume) {
        KVMStoragePool volumeStoragePool = storagePoolMgr.getStoragePool(volumePool.getPoolType(), volumePool.getUuid());
        String normalizedVolumePath = normalizeRbdVolumePath(volumePath, volumeStoragePool);

        return LibvirtAblestackRbdRestoreHelper.restoreRbdBackup(RESTORE_TRACE, volumeStoragePool, normalizedVolumePath,
                backupPaths, timeout, createTargetVolume);
    }

    private Path moveExistingFileVolumeAside(String volumePath) throws IOException {
        Path targetPath = Paths.get(volumePath);
        if (!Files.exists(targetPath)) {
            return null;
        }

        Path movedAsidePath = targetPath.resolveSibling(targetPath.getFileName() + ".csrestore." + System.currentTimeMillis() + ".bak");
        Files.move(targetPath, movedAsidePath);
        logger.info("{} phase=[TARGET_MOVED_ASIDE], target=[{}], movedAside=[{}]",
                RESTORE_TRACE, volumePath, movedAsidePath);
        return movedAsidePath;
    }

    private void deleteMovedAsideFileVolume(Path movedAsideTarget) {
        if (movedAsideTarget == null) {
            return;
        }
        try {
            Files.deleteIfExists(movedAsideTarget);
            logger.info("{} phase=[TARGET_MOVED_ASIDE_DELETED], movedAside=[{}]", RESTORE_TRACE, movedAsideTarget);
        } catch (IOException e) {
            logger.warn("{} phase=[TARGET_MOVED_ASIDE_DELETE_FAILED], movedAside=[{}], error=[{}]",
                    RESTORE_TRACE, movedAsideTarget, e.getMessage());
        }
    }

    private void restoreMovedAsideFileVolume(String volumePath, Path movedAsideTarget) {
        if (movedAsideTarget == null || !Files.exists(movedAsideTarget)) {
            return;
        }
        Path targetPath = Paths.get(volumePath);
        try {
            Files.deleteIfExists(targetPath);
            Files.move(movedAsideTarget, targetPath);
            logger.info("{} phase=[TARGET_MOVED_ASIDE_RESTORED], target=[{}], movedAside=[{}]",
                    RESTORE_TRACE, volumePath, movedAsideTarget);
        } catch (IOException e) {
            logger.error("{} phase=[TARGET_MOVED_ASIDE_RESTORE_FAILED], target=[{}], movedAside=[{}], error=[{}]",
                    RESTORE_TRACE, volumePath, movedAsideTarget, e.getMessage());
        }
    }

    private void restoreFileVolumeData(String backupPath, String volumePath, QemuImg.PhysicalDiskFormat backupFormat,
                                       QemuImg.PhysicalDiskFormat volumeFormat, int timeout) throws QemuImgException {
        if (backupFormat == QemuImg.PhysicalDiskFormat.QCOW2 && volumeFormat == QemuImg.PhysicalDiskFormat.QCOW2) {
            if (hasBackingChain(backupPath)) {
                convertFileVolumeWithQemuImg(backupPath, volumePath, backupFormat, volumeFormat, timeout);
            } else {
                rsyncQcow2BackupFile(backupPath, volumePath, timeout);
            }
            return;
        }
        convertFileVolumeWithQemuImg(backupPath, volumePath, backupFormat, volumeFormat, timeout);
    }

    private boolean hasBackingChain(String qcow2Path) {
        return runCommandWithOutput(String.format(QEMU_IMG_HAS_BACKING_COMMAND, quote(qcow2Path)), 0).first() == 0;
    }

    private void rsyncQcow2BackupFile(String backupPath, String volumePath, int timeout) throws QemuImgException {
        String rsyncCommand = String.format("rsync -az %s %s", quote(backupPath), quote(volumePath));
        Pair<Integer, String> result = runCommandWithOutput(rsyncCommand, timeout * 1000);
        String output = formatTraceOutput(result.second());
        if (result.first() == 0) {
            logger.info("{} phase=[RSYNC], source=[{}], target=[{}], command=[rsync-qcow2]",
                    RESTORE_TRACE, backupPath, volumePath);
            return;
        }
        logger.warn("{} phase=[RSYNC], source=[{}], target=[{}], command=[rsync-qcow2], exitCode=[{}], output=[{}]",
                RESTORE_TRACE, backupPath, volumePath, result.first(), output);
        throw new QemuImgException(String.format("rsync qcow2 backup failed with exitCode [%s], output [%s]", result.first(), output));
    }

    private void convertFileVolumeWithQemuImg(String backupPath, String volumePath, QemuImg.PhysicalDiskFormat backupFormat,
                                              QemuImg.PhysicalDiskFormat volumeFormat, int timeout) throws QemuImgException {
        String convertCommand = String.format("qemu-img convert -p -S 0 -f %s -O %s %s %s",
                backupFormat.toString().toLowerCase(Locale.ROOT), volumeFormat.toString().toLowerCase(Locale.ROOT),
                quote(backupPath), quote(volumePath));
        Pair<Integer, String> result = runCommandWithOutput(convertCommand, timeout * 1000);
        String output = formatTraceOutput(result.second());
        if (result.first() == 0) {
            logger.info("{} phase=[CONVERT], source=[{}], target=[{}], command=[qemu-img-convert-nosparse]",
                    RESTORE_TRACE, backupPath, volumePath);
            return;
        }
        logger.warn("{} phase=[CONVERT], source=[{}], target=[{}], command=[qemu-img-convert-nosparse], exitCode=[{}], output=[{}]",
                RESTORE_TRACE, backupPath, volumePath, result.first(), output);
        throw new QemuImgException(String.format("qemu-img convert failed with exitCode [%s], output [%s]", result.first(), output));
    }

    private Pair<Integer, String> runCommandWithOutput(String command, int timeout) {
        String wrappedCommand = String.format("set +e; %s 2>&1; rc=$?; echo __CMD_EXIT__=$rc", command);
        String output = Script.runSimpleBashScriptWithFullResult(wrappedCommand, timeout);
        if (output == null) {
            return new Pair<>(-1, "");
        }

        List<String> lines = new ArrayList<>(Arrays.asList(output.split("\n")));
        int exitCode = -1;
        if (!lines.isEmpty()) {
            String lastLine = lines.get(lines.size() - 1).trim();
            if (lastLine.startsWith("__CMD_EXIT__=")) {
                exitCode = Integer.parseInt(lastLine.substring("__CMD_EXIT__=".length()));
                lines.remove(lines.size() - 1);
            }
        }
        return new Pair<>(exitCode, String.join("\n", lines).trim());
    }

    private String formatTraceOutput(String output) {
        if (output == null || output.isBlank()) {
            return "";
        }
        return output.replace("\r", "\\r").replace("\n", "\\n").trim();
    }

    private String normalizeRbdVolumePath(String volumePath, KVMStoragePool storagePool) {
        if (StringUtils.isBlank(volumePath)) {
            return volumePath;
        }
        String normalized = volumePath;
        String poolPath = storagePool.getSourceDir();
        if (StringUtils.isNotBlank(poolPath)) {
            String poolPrefix = poolPath + "/";
            if (normalized.startsWith(poolPrefix)) {
                normalized = normalized.substring(poolPrefix.length());
            }
        }
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(normalized.lastIndexOf('/') + 1);
        }
        return normalized;
    }

    private boolean importBackupChainToTemporaryRbd(List<String> backupPaths, int timeout, RbdImageSpec sourceImage, String tempImage) {
        if (backupPaths.isEmpty() || !backupPaths.get(0).endsWith(".raw")) {
            throw new CloudRuntimeException("Incremental RBD backup chain is missing the base full backup");
        }
        String importCommand = sourceImage.buildRbdCommand("import", quote(backupPaths.get(0)), quote(tempImage));
        if (Script.runSimpleBashScriptForExitValue(importCommand, timeout * 1000, false) != 0) {
            logger.error("Failed to import base RBD backup {} into temporary image {}", backupPaths.get(0), tempImage);
            return false;
        }

        List<String> restoreSnapshots = new ArrayList<>();
        try {
            Map<String, String> baseMetadata = readRbdBackupMetadata(backupPaths.get(0));
            String baseCheckpoint = baseMetadata.get("checkpoint_name");
            if (StringUtils.isNotBlank(baseCheckpoint)) {
                if (!ensureRbdSnapshotExists(sourceImage, tempImage, baseCheckpoint, timeout)) {
                    return false;
                }
                restoreSnapshots.add(baseCheckpoint);
            }

            for (int index = 1; index < backupPaths.size(); index++) {
                String backupPath = backupPaths.get(index);
                if (!backupPath.endsWith(".rbdiff")) {
                    continue;
                }
                Map<String, String> metadata = readRbdBackupMetadata(backupPath);
                String parentCheckpoint = metadata.get("parent_checkpoint_name");
                String checkpoint = metadata.get("checkpoint_name");
                if (StringUtils.isBlank(parentCheckpoint) || StringUtils.isBlank(checkpoint)) {
                    throw new CloudRuntimeException(String.format("RBD incremental backup metadata is incomplete for %s", backupPath));
                }
                if (!rbdSnapshotExists(sourceImage, tempImage, parentCheckpoint, timeout)) {
                    throw new CloudRuntimeException(String.format("Required parent snapshot %s is missing on temporary image %s", parentCheckpoint, tempImage));
                }
                String importDiffCommand = sourceImage.buildRbdCommand("import-diff", quote(backupPath), quote(tempImage));
                if (Script.runSimpleBashScriptForExitValue(importDiffCommand, timeout * 1000, false) != 0) {
                    logger.error("Failed to import RBD diff {} into temporary image {}", backupPath, tempImage);
                    return false;
                }
                if (!ensureRbdSnapshotExists(sourceImage, tempImage, checkpoint, timeout)) {
                    return false;
                }
                restoreSnapshots.add(checkpoint);
            }
            return true;
        } finally {
            cleanupRbdRestoreSnapshots(sourceImage, tempImage, restoreSnapshots, timeout);
        }
    }

    private Map<String, String> readRbdBackupMetadata(String backupPath) {
        java.nio.file.Path metadataPath = Paths.get(backupPath).getParent().resolve("rbd-backup.meta");
        if (!Files.exists(metadataPath)) {
            throw new CloudRuntimeException(String.format("RBD backup metadata file not found: %s", metadataPath));
        }
        try {
            return Files.readAllLines(metadataPath).stream()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && line.contains("="))
                    .map(line -> line.split("=", 2))
                    .collect(java.util.stream.Collectors.toMap(parts -> parts[0], parts -> parts[1], (left, right) -> right));
        } catch (IOException e) {
            throw new CloudRuntimeException(String.format("Failed to read RBD backup metadata: %s", metadataPath), e);
        }
    }

    private boolean ensureRbdSnapshotExists(RbdImageSpec imageSpec, String image, String snapshotName, int timeout) {
        if (rbdSnapshotExists(imageSpec, image, snapshotName, timeout)) {
            return true;
        }
        String createSnapshotCommand = imageSpec.buildRbdCommand("snap", "create", quote(image + "@" + snapshotName));
        if (Script.runSimpleBashScriptForExitValue(createSnapshotCommand, timeout * 1000, false) != 0) {
            logger.error("Failed to create RBD snapshot {} on image {}", snapshotName, image);
            return false;
        }
        return true;
    }

    private boolean rbdSnapshotExists(RbdImageSpec imageSpec, String image, String snapshotName, int timeout) {
        String existsCommand = imageSpec.buildRbdCommand("snap", "ls", quote(image)) + " | awk 'NR>1 {print $2}' | grep -Fx " + quote(snapshotName);
        return Script.runSimpleBashScriptForExitValue(existsCommand, timeout * 1000, false) == 0;
    }

    private void cleanupRbdRestoreSnapshots(RbdImageSpec imageSpec, String image, List<String> snapshotNames, int timeout) {
        for (int index = snapshotNames.size() - 1; index >= 0; index--) {
            String snapshotName = snapshotNames.get(index);
            String removeSnapshotCommand = imageSpec.buildRbdCommand("snap", "rm", quote(image + "@" + snapshotName));
            Script.runSimpleBashScriptForExitValue(removeSnapshotCommand, timeout * 1000, false);
        }
    }

    private void removeTemporaryRbdImage(RbdImageSpec sourceImage, String tempImage, int timeout) {
        String removeCommand = sourceImage.buildRbdCommand("rm", quote(tempImage));
        Script.runSimpleBashScriptForExitValue(removeCommand, timeout * 1000, false);
    }

    private RbdImageSpec getRbdImageSpecFromMetadata(String backupRootPath, int backupIndex) {
        java.nio.file.Path metadataPath = Paths.get(backupRootPath, "rbd-backup.meta");
        if (!Files.exists(metadataPath)) {
            throw new CloudRuntimeException(String.format("RBD backup metadata file not found: %s", metadataPath));
        }
        try {
            Map<String, String> metadata = Files.readAllLines(metadataPath).stream()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && line.contains("="))
                    .map(line -> line.split("=", 2))
                    .collect(java.util.stream.Collectors.toMap(parts -> parts[0], parts -> parts[1], (left, right) -> right));
            String diskPaths = metadata.get("disk_paths");
            if (StringUtils.isBlank(diskPaths)) {
                throw new CloudRuntimeException("RBD backup metadata does not contain disk_paths");
            }
            List<String> values = Arrays.asList(diskPaths.split(","));
            if (backupIndex >= values.size()) {
                throw new CloudRuntimeException(String.format("RBD backup metadata does not contain disk path for index %d", backupIndex));
            }
            return RbdImageSpec.fromUri(values.get(backupIndex));
        } catch (IOException e) {
            throw new CloudRuntimeException(String.format("Failed to read RBD backup metadata: %s", metadataPath), e);
        }
    }

    private String quote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static final class RbdImageSpec {
        private final String image;
        private final String monHost;
        private final String user;
        private final String key;

        private RbdImageSpec(String image, String monHost, String user, String key) {
            this.image = image;
            this.monHost = monHost;
            this.user = user;
            this.key = key;
        }

        private static RbdImageSpec fromUri(String uri) {
            String image = null;
            String monHost = null;
            String user = null;
            String key = null;
            if (uri.startsWith("rbd:")) {
                String payload = uri.substring("rbd:".length());
                image = payload.contains(":") ? payload.substring(0, payload.indexOf(':')) : payload;
                monHost = extract(uri, ":mon_host=([^:]*)");
                if (monHost != null) {
                    monHost = monHost.replace("\\;", ",").replace("\\:", ":");
                }
                user = extract(uri, ":id=([^:]*)");
                key = extract(uri, ":key=([^:]*)");
            } else if (uri.startsWith("rbd/")) {
                image = uri;
            }
            if (StringUtils.isBlank(image)) {
                throw new CloudRuntimeException(String.format("Unable to parse RBD disk path: %s", uri));
            }
            return new RbdImageSpec(image, monHost, user, key);
        }

        private static String extract(String value, String regex) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(regex).matcher(value);
            return matcher.find() ? matcher.group(1) : null;
        }

        private String buildTempImageSpec() {
            return String.format("%s-csrestore-%s", image, RandomStringUtils.randomAlphanumeric(8).toLowerCase(Locale.ROOT));
        }

        private String buildRbdCommand(String action, String source, String target) {
            StringBuilder command = new StringBuilder("rbd");
            if (StringUtils.isNotBlank(monHost)) {
                command.append(" -m ").append(quoteArg(monHost));
            }
            if (StringUtils.isNotBlank(user)) {
                command.append(" --id ").append(quoteArg(user));
            }
            if (StringUtils.isNotBlank(key)) {
                command.append(" --key ").append(quoteArg(key));
            }
            command.append(" ").append(action);
            if (StringUtils.isNotBlank(source)) {
                command.append(" ").append(source);
            }
            if (StringUtils.isNotBlank(target)) {
                command.append(" ").append(target);
            }
            return command.toString();
        }

        private String buildRbdCommand(String action, String target) {
            return buildRbdCommand(action, null, target);
        }

        private String buildQemuUri(String imageSpec) {
            StringBuilder uri = new StringBuilder("rbd:").append(imageSpec);
            if (StringUtils.isNotBlank(monHost)) {
                uri.append(":mon_host=").append(monHost.replace(",", "\\;"));
            }
            if (StringUtils.isNotBlank(user)) {
                uri.append(":id=").append(user);
            }
            if (StringUtils.isNotBlank(key)) {
                uri.append(":key=").append(key);
            }
            return uri.toString();
        }

        private String quoteArg(String value) {
            return "'" + value.replace("'", "'\"'\"'") + "'";
        }
    }

    private boolean attachVolumeToVm(KVMStoragePoolManager storagePoolMgr, String vmName, PrimaryDataStoreTO volumePool, String volumePath, String cacheMode) {
        String deviceToAttachDiskTo = getDeviceToAttachDisk(vmName);
        int exitValue;
        if (volumePool.getPoolType() != Storage.StoragePoolType.RBD) {
            exitValue = Script.runSimpleBashScriptForExitValue(String.format(ATTACH_QCOW2_DISK_COMMAND, vmName, volumePath, deviceToAttachDiskTo));
        } else {
            String xmlForRbdDisk = getXmlForRbdDisk(storagePoolMgr, volumePool, volumePath, deviceToAttachDiskTo, cacheMode);
            logger.debug("RBD disk xml to attach: {}", xmlForRbdDisk);
            exitValue = Script.runSimpleBashScriptForExitValue(String.format(ATTACH_RBD_DISK_XML_COMMAND, vmName, xmlForRbdDisk));
        }
        return exitValue == 0;
    }

    private String getDeviceToAttachDisk(String vmName) {
        String currentDevice = Script.runSimpleBashScript(String.format(CURRRENT_DEVICE, vmName));
        char lastChar = currentDevice.charAt(currentDevice.length() - 1);
        char incrementedChar = (char) (lastChar + 1);
        return currentDevice.substring(0, currentDevice.length() - 1) + incrementedChar;
    }

    private String getXmlForRbdDisk(KVMStoragePoolManager storagePoolMgr, PrimaryDataStoreTO volumePool, String volumePath, String deviceToAttachDiskTo, String cacheMode) {
        StringBuilder diskBuilder = new StringBuilder();
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
        diskBuilder.append(" name='" + volumePath + "'");
        diskBuilder.append(">\n");
        for (String sourceHost : volumePool.getHost().split(",")) {
            diskBuilder.append("<host name='");
            diskBuilder.append(sourceHost.replace("[", "").replace("]", ""));
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
            diskBuilder.append("<auth username='" + authUserName + "'>\n");
            diskBuilder.append("<secret type='ceph' uuid='" + volumePool.getUuid() + "'/>\n");
            diskBuilder.append("</auth>\n");
        }
        diskBuilder.append("<target dev='" + deviceToAttachDiskTo + "'");
        diskBuilder.append(" bus='virtio'");
        diskBuilder.append("/>\n");
        diskBuilder.append("</disk>\n");
        return diskBuilder.toString();
    }
}
