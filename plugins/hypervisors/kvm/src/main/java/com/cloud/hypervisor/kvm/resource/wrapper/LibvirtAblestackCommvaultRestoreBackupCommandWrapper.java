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
import org.apache.cloudstack.backup.AblestackCommvaultRestoreBackupCommand;
import org.apache.cloudstack.backup.BackupRestorePlan;
import org.apache.cloudstack.backup.BackupRestoreStage;
import org.apache.cloudstack.backup.BackupVolumeChainState;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;
import org.apache.cloudstack.utils.qemu.QemuImg;
import org.apache.cloudstack.utils.qemu.QemuImgException;
import org.apache.cloudstack.utils.qemu.QemuImgFile;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.libvirt.LibvirtException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@ResourceWrapper(handles = AblestackCommvaultRestoreBackupCommand.class)
public class LibvirtAblestackCommvaultRestoreBackupCommandWrapper extends CommandWrapper<AblestackCommvaultRestoreBackupCommand, Answer, LibvirtComputingResource> {
    private static final String FILE_PATH_PLACEHOLDER = "%s/%s";
    private static final String ATTACH_QCOW2_DISK_COMMAND = " virsh attach-disk %s %s %s --driver qemu --subdriver qcow2 --cache none";
    private static final String ATTACH_RBD_DISK_XML_COMMAND = " virsh attach-device %s /dev/stdin <<EOF%sEOF";
    private static final String CURRENT_DEVICE = "virsh domblklist --domain %s --details | awk '($3 ~ /^(vd|sd|hd)[a-z]+$/) {print $3} "
            + "($4 ~ /^(vd|sd|hd)[a-z]+$/) {print $4}' | sort -V | tail -n 1";
    private static final String MKDIR_P = "mkdir -p %s";
    private static final String RSYNC_DIR_FROM_REMOTE = "rsync -az -e \"ssh -o StrictHostKeyChecking=no\" %s:%s/ %s/";
    private static final String QEMU_IMG_HAS_BACKING_COMMAND = "qemu-img info --output=json %s 2>/dev/null | grep -q '\"backing-filename\"'";
    private static final String COMMAND_EXIT_MARKER = "__CS_COMMAND_EXIT__=";
    private static final String RESTORE_TRACE = AblestackBackupFrameworkUtils.buildTracePrefix("commvault", AblestackBackupFrameworkUtils.OPERATION_RESTORE);
    private static final long RESTORE_PRIMARY_SPACE_BUFFER_BYTES = 10L * 1024L * 1024L * 1024L;

    @Override
    public Answer execute(AblestackCommvaultRestoreBackupCommand command, LibvirtComputingResource serverResource) {
        if (!command.isWaitForCompletion()) {
            return LibvirtAblestackAsyncBackupRunner.startDetachedRestore(command, logger, RESTORE_TRACE, "commvault",
                    command.getRestoreJobId(), command.getVmName(), command.getBackupPath());
        }
        String vmName = command.getVmName();
        String backupPath = command.getBackupPath();
        Boolean vmExists = command.isVmExists();
        String diskType = command.getDiskType();
        List<String> backedVolumeUUIDs = command.getBackupVolumesUUIDs();
        List<String> backupFiles = command.getBackupFiles();
        List<String> backupFileChains = command.getBackupFileChains();
        List<BackupVolumeChainState> volumeChainStates = command.getVolumeChainStates();
        List<PrimaryDataStoreTO> restoreVolumePools = command.getRestoreVolumePools();
        List<String> restoreVolumePaths = command.getRestoreVolumePaths();
        String restoreVolumeUuid = command.getRestoreVolumeUUID();
        int timeout = command.getTimeout();
        String cacheMode = command.getCacheMode();
        String hostName = command.getHostName();
        List<String> backupSourceHosts = command.getBackupSourceHosts();
        BackupRestorePlan restorePlan = command.getRestorePlan();
        KVMStoragePoolManager storagePoolMgr = serverResource.getStoragePoolMgr();

        logger.info("{} phase=[ENTER], restoreJobId=[{}], jobLog=[{}], vm=[{}], backupPath=[{}], vmExists=[{}], "
                        + "restorePlan=[{}], restoreVolumePaths=[{}], backupFiles=[{}], backupFileChains=[{}]",
                RESTORE_TRACE, command.getRestoreJobId(), AblestackBackupFrameworkUtils.getAsyncRestoreJobLogPath(command.getRestoreJobId()),
                vmName, backupPath, vmExists, restorePlan, restoreVolumePaths, backupFiles, backupFileChains);
        LibvirtAblestackAsyncBackupRunner.markRestoreJobRunning(logger, "commvault", command.getRestoreJobId(), vmName, backupPath,
                "Commvault restore command started");
        String newVolumeId = null;
        try {
            LibvirtAblestackAsyncBackupRunner.markRestoreJobStep(logger, "commvault", command.getRestoreJobId(), vmName, backupPath,
                    "VALIDATE_CHAIN", "Validating restore chain");
            validateChainStatePlan(volumeChainStates, restorePlan);
            LibvirtAblestackAsyncBackupRunner.markRestoreJobStep(logger, "commvault", command.getRestoreJobId(), vmName, backupPath,
                    "PREPARE_SOURCE", "Preparing restore source");
            if (AblestackBackupFrameworkUtils.hasRestoreStage(restorePlan, BackupRestoreStage.PREPARE_SOURCE) && hostName != null) {
                fetchBackupFile(hostName, backupPath, timeout);
            }
            if (AblestackBackupFrameworkUtils.hasRestoreStage(restorePlan, BackupRestoreStage.PREPARE_SOURCE) && backupSourceHosts != null && !backupSourceHosts.isEmpty()) {
                LinkedHashSet<String> sourceHosts = new LinkedHashSet<>(backupSourceHosts);
                for (String sourceHost : sourceHosts) {
                    if (StringUtils.isBlank(sourceHost) || Objects.equals(sourceHost, hostName)) {
                        continue;
                    }
                    fetchBackupFile(sourceHost, backupPath, timeout);
                }
            }
            LibvirtAblestackAsyncBackupRunner.markRestoreJobStep(logger, "commvault", command.getRestoreJobId(), vmName, backupPath,
                    "RESTORE_DATA", "Restoring backup data");
            if (Objects.isNull(vmExists)) {
                PrimaryDataStoreTO volumePool = restoreVolumePools.get(0);
                String volumePath = restoreVolumePaths.get(0);
                int lastIndex = volumePath.lastIndexOf("/");
                newVolumeId = volumePath.substring(lastIndex + 1);
                restoreVolume(storagePoolMgr, backupPath, volumePool, volumePath, diskType, restoreVolumeUuid, backupFiles, backupFileChains, volumeChainStates,
                        new Pair<>(vmName, command.getVmState()), timeout, cacheMode, restorePlan);
            } else if (Boolean.TRUE.equals(vmExists)) {
                restoreVolumesOfExistingVM(storagePoolMgr, restoreVolumePools, restoreVolumePaths, backedVolumeUUIDs, backupPath, backupFiles, backupFileChains,
                        volumeChainStates, timeout, restorePlan);
            } else {
                restoreVolumesOfDestroyedVMs(storagePoolMgr, restoreVolumePools, restoreVolumePaths, vmName, backupPath, backupFiles, backupFileChains,
                        volumeChainStates, timeout, restorePlan);
            }
        } catch (CloudRuntimeException e) {
            String errorMessage = e.getMessage() != null ? e.getMessage() : "";
            LibvirtAblestackAsyncBackupRunner.markRestoreJobFailed(logger, "commvault", command.getRestoreJobId(), vmName, backupPath, errorMessage);
            return new BackupAnswer(command, false, errorMessage);
        }

        logger.info("{} phase=[DONE], restoreJobId=[{}], vm=[{}], backupPath=[{}], vmExists=[{}], newVolumeId=[{}]",
                RESTORE_TRACE, command.getRestoreJobId(), vmName, backupPath, vmExists, newVolumeId);
        LibvirtAblestackAsyncBackupRunner.markRestoreJobCompleted(logger, "commvault", command.getRestoreJobId(), vmName, backupPath,
                StringUtils.defaultIfBlank(newVolumeId, "Commvault restore command completed"));
        return new BackupAnswer(command, true, newVolumeId);
    }

    private void restoreVolumesOfExistingVM(KVMStoragePoolManager storagePoolMgr, List<PrimaryDataStoreTO> restoreVolumePools, List<String> restoreVolumePaths, List<String> backedVolumesUUIDs,
                                            String backupPath, List<String> backupFiles, List<String> backupFileChains,
                                            List<BackupVolumeChainState> volumeChainStates, int timeout, BackupRestorePlan restorePlan) {
        String diskType = "root";
        try {
            List<List<String>> localBackupPathsByVolume = getLocalBackupPathsForVolumes(backupPath, backupFiles, backupFileChains, volumeChainStates,
                    restoreVolumePaths, backedVolumesUUIDs);
            validatePrimaryStorageSpaceForFileRestorePlan(restoreVolumePaths, localBackupPathsByVolume, restoreVolumePools);
            for (int idx = 0; idx < restoreVolumePaths.size(); idx++) {
                PrimaryDataStoreTO restoreVolumePool = restoreVolumePools.get(idx);
                String restoreVolumePath = restoreVolumePaths.get(idx);
                String backupVolumeUuid = backedVolumesUUIDs.get(idx);
                List<String> localBackupPaths = localBackupPathsByVolume.get(idx);
                validateResolvedChainPaths(localBackupPaths, restoreVolumePath);
                diskType = "datadisk";
                if (!replaceVolumeWithBackup(storagePoolMgr, restoreVolumePool, restoreVolumePath, localBackupPaths, timeout, backupPath, idx)) {
                    throw new CloudRuntimeException(String.format("Unable to restore contents from the backup volume [%s].", backupVolumeUuid));
                }
            }
        } finally {
            cleanupBackupDirectory(backupPath, restorePlan);
        }
    }

    private void restoreVolumesOfDestroyedVMs(KVMStoragePoolManager storagePoolMgr, List<PrimaryDataStoreTO> volumePools, List<String> volumePaths, String vmName, String backupPath,
                                              List<String> backupFiles, List<String> backupFileChains,
                                              List<BackupVolumeChainState> volumeChainStates, int timeout, BackupRestorePlan restorePlan) {
        String diskType = "root";
        try {
            List<List<String>> localBackupPathsByVolume = getLocalBackupPathsForVolumes(backupPath, backupFiles, backupFileChains, volumeChainStates,
                    volumePaths, null);
            validatePrimaryStorageSpaceForFileRestorePlan(volumePaths, localBackupPathsByVolume, volumePools);
            for (int i = 0; i < volumePaths.size(); i++) {
                PrimaryDataStoreTO volumePool = volumePools.get(i);
                String volumePath = volumePaths.get(i);
                String volumeUuid = volumePath.substring(volumePath.lastIndexOf(File.separator) + 1);
                List<String> localBackupPaths = localBackupPathsByVolume.get(i);
                validateResolvedChainPaths(localBackupPaths, volumePath);
                diskType = "datadisk";
                if (!replaceVolumeWithBackup(storagePoolMgr, volumePool, volumePath, localBackupPaths, timeout, backupPath, i)) {
                    throw new CloudRuntimeException(String.format("Unable to restore contents from the backup volume [%s].", volumeUuid));
                }
            }
        } finally {
            cleanupBackupDirectory(backupPath, restorePlan);
        }
    }

    private void restoreVolume(KVMStoragePoolManager storagePoolMgr, String backupPath, PrimaryDataStoreTO volumePool, String volumePath, String diskType, String volumeUUID,
                               List<String> backupFiles, List<String> backupFileChains, List<BackupVolumeChainState> volumeChainStates,
                               Pair<String, VirtualMachine.State> vmNameAndState, int timeout, String cacheMode, BackupRestorePlan restorePlan) {
        try {
            List<String> localBackupPaths = getLocalBackupPaths(backupPath, backupFiles, backupFileChains, volumeChainStates, 0, getLegacyBackupFileName(diskType, volumeUUID));
            validateResolvedChainPaths(localBackupPaths, volumePath);
            validatePrimaryStorageSpaceForFileRestorePlan(List.of(volumePath), List.of(localBackupPaths), List.of(volumePool));
            if (!replaceVolumeWithBackup(storagePoolMgr, volumePool, volumePath, localBackupPaths, timeout, backupPath, 0, true)) {
                throw new CloudRuntimeException(String.format("Unable to restore contents from the backup volume [%s].", volumeUUID));
            }
            if (AblestackBackupFrameworkUtils.hasRestoreStage(restorePlan, BackupRestoreStage.ATTACH_VOLUME)
                    && VirtualMachine.State.Running.equals(vmNameAndState.second())) {
                if (!attachVolumeToVm(storagePoolMgr, vmNameAndState.first(), volumePool, volumePath, cacheMode)) {
                    throw new CloudRuntimeException(String.format("Failed to attach volume to VM: %s", vmNameAndState.first()));
                }
            }
        } finally {
            cleanupBackupDirectory(backupPath, restorePlan);
        }
    }

    private List<List<String>> getLocalBackupPathsForVolumes(String backupPath, List<String> backupFiles, List<String> backupFileChains,
                                                            List<BackupVolumeChainState> volumeChainStates, List<String> volumePaths,
                                                            List<String> backedVolumeUUIDs) {
        List<List<String>> localBackupPathsByVolume = new ArrayList<>();
        String diskType = "root";
        for (int idx = 0; idx < volumePaths.size(); idx++) {
            String volumeUuid = backedVolumeUUIDs != null ? backedVolumeUUIDs.get(idx)
                    : volumePaths.get(idx).substring(volumePaths.get(idx).lastIndexOf(File.separator) + 1);
            localBackupPathsByVolume.add(getLocalBackupPaths(backupPath, backupFiles, backupFileChains, volumeChainStates, idx,
                    getLegacyBackupFileName(diskType, volumeUuid)));
            diskType = "datadisk";
        }
        return localBackupPathsByVolume;
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

    private void cleanupBackupDirectory(String backupPath, BackupRestorePlan restorePlan) {
        if (AblestackBackupFrameworkUtils.hasRestoreStage(restorePlan, BackupRestoreStage.CLEANUP_SOURCE)) {
            deleteBackupDirectory(backupPath);
        }
    }

    private void deleteBackupDirectory(String backupDirectory) {
        try {
            FileUtils.deleteDirectory(new File(backupDirectory));
        } catch (IOException e) {
            logger.error(String.format("Failed to delete backup directory: %s", backupDirectory), e);
            throw new CloudRuntimeException("Failed to delete the backup directory");
        }
    }

    private List<String> getLocalBackupPaths(String backupPath, List<String> backupFiles, List<String> backupFileChains,
                                             List<BackupVolumeChainState> volumeChainStates, int index, String legacyBackupFileName) {
        LinkedHashSet<String> localPaths = new LinkedHashSet<>();
        boolean resolvedFromVolumeChainStates = false;
        if (volumeChainStates != null && volumeChainStates.size() > index) {
            for (String chainPath : volumeChainStates.get(index).getChainFiles()) {
                if (StringUtils.isBlank(chainPath)) {
                    continue;
                }
                localPaths.add(resolveBackupPath(backupPath, chainPath));
                resolvedFromVolumeChainStates = true;
            }
        }
        if (!resolvedFromVolumeChainStates && backupFileChains != null && backupFileChains.size() > index && StringUtils.isNotBlank(backupFileChains.get(index))) {
            for (String chainPath : backupFileChains.get(index).split(";")) {
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

    private String resolveBackupPath(String backupPath, String chainPath) {
        if (chainPath.startsWith("/")) {
            return chainPath;
        }
        if (chainPath.contains("/")) {
            return String.format(FILE_PATH_PLACEHOLDER, backupPath, chainPath);
        }
        return String.format(FILE_PATH_PLACEHOLDER, backupPath, chainPath);
    }

    private String getLegacyBackupFileName(String diskType, String volumeUuid) {
        return String.format("%s.%s.qcow2", diskType.toLowerCase(Locale.ROOT), volumeUuid);
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
        return LibvirtAblestackFileRestoreHelper.getRestorableFileBackupPath(backupPaths);
    }

    private boolean replaceFileVolumeWithBackup(String volumePath, String backupPath, int timeout) {
        return LibvirtAblestackFileRestoreHelper.replaceFileVolumeWithBackup(RESTORE_TRACE, logger, volumePath, backupPath, timeout,
                "cs-commvault-restore-volume-");
    }

    private Path createTemporaryVolumePath(String volumePath, String prefix, QemuImg.PhysicalDiskFormat targetFormat) throws IOException {
        Path targetPath = Paths.get(volumePath).toAbsolutePath();
        Path targetDirectory = targetPath.getParent();
        String suffix = "." + targetFormat.toString().toLowerCase(Locale.ROOT);
        return targetDirectory != null ? Files.createTempFile(targetDirectory, prefix, suffix) : Files.createTempFile(prefix, suffix);
    }

    private void validatePrimaryStorageSpaceForFileRestorePlan(List<String> volumePaths, List<List<String>> backupPathsByVolume,
                                                               List<PrimaryDataStoreTO> restoreVolumePools) {
        LibvirtAblestackFileRestoreHelper.validatePrimaryStorageSpaceForFileRestorePlan(RESTORE_TRACE, logger, "Commvault",
                volumePaths, backupPathsByVolume, restoreVolumePools);
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
                    "Insufficient primary storage space for Commvault restore target [%s]. Required at least [%d] bytes including buffer, but only [%d] bytes are available under [%s].",
                    volumePath, minimumAvailableBytes, availableBytes, targetDirectory));
        }
    }

    private Path getTargetDirectory(String volumePath) {
        Path targetPath = Paths.get(volumePath).toAbsolutePath();
        Path targetDirectory = targetPath.getParent();
        return targetDirectory != null ? targetDirectory : Paths.get(".").toAbsolutePath();
    }

    private long estimateRequiredBytesForVolumeRestore(String volumePath, List<String> backupPaths) {
        return LibvirtAblestackFileRestoreHelper.estimateRequiredBytesForVolumeRestore(volumePath, backupPaths);
    }

    private long estimateRequiredBytesForFileRestore(String backupPath) throws QemuImgException, LibvirtException {
        return LibvirtAblestackFileRestoreHelper.estimateRequiredBytesForFileRestore(backupPath);
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
            logger.info("{} phase=[TARGET_MOVED_ASIDE_DELETED], movedAside=[{}]",
                    RESTORE_TRACE, movedAsideTarget);
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

    private boolean replaceFileVolumeWithBackup(String volumePath, List<String> backupPaths, int timeout) {
        return LibvirtAblestackFileRestoreHelper.replaceFileVolumeWithBackup(RESTORE_TRACE, logger, volumePath, backupPaths, timeout,
                "cs-commvault-restore-volume-");
    }

    private void restoreFileVolumeData(String backupPath, String volumePath, QemuImg.PhysicalDiskFormat backupFormat,
                                       QemuImg.PhysicalDiskFormat volumeFormat, int timeout) throws QemuImgException, LibvirtException {
        if (backupFormat == QemuImg.PhysicalDiskFormat.QCOW2 && volumeFormat == QemuImg.PhysicalDiskFormat.QCOW2 && !hasBackingChain(backupPath)) {
            rsyncQcow2BackupFile(backupPath, volumePath, timeout);
            return;
        }
        convertFileVolumeWithQemuImg(backupPath, volumePath, backupFormat, volumeFormat, timeout);
    }

    private boolean hasBackingChain(String qcow2Path) {
        return Script.runSimpleBashScriptForExitValue(String.format(QEMU_IMG_HAS_BACKING_COMMAND, quote(qcow2Path)), 0, false) == 0;
    }

    private void rsyncQcow2BackupFile(String backupPath, String volumePath, int timeout) throws QemuImgException {
        String rsyncCommand = String.format("rsync -az %s %s", quote(backupPath), quote(volumePath));
        CommandExecutionResult result = executeBashCommandWithResult(rsyncCommand, timeout, "Rsync standalone QCOW2 backup to file volume");
        if (result.exitCode != 0) {
            logger.warn("{} phase=[RSYNC], source=[{}], target=[{}], command=[rsync-qcow2], exitCode=[{}], output=[{}]",
                    RESTORE_TRACE, backupPath, volumePath, result.exitCode, result.output);
            throw new QemuImgException(String.format("rsync qcow2 backup failed with exitCode [%s], output [%s]", result.exitCode, result.output));
        }
        logger.info("{} phase=[RSYNC], source=[{}], target=[{}], command=[rsync-qcow2]",
                RESTORE_TRACE, backupPath, volumePath);
    }

    private void convertFileVolumeWithQemuImg(String backupPath, String volumePath, QemuImg.PhysicalDiskFormat backupFormat,
                                              QemuImg.PhysicalDiskFormat volumeFormat, int timeout) throws QemuImgException, LibvirtException {
        String convertCommand = String.format("qemu-img convert -p -S 0 -f %s -O %s %s %s",
                backupFormat.toString().toLowerCase(Locale.ROOT), volumeFormat.toString().toLowerCase(Locale.ROOT),
                quote(backupPath), quote(volumePath));
        CommandExecutionResult result = executeBashCommandWithResult(convertCommand, timeout, "Convert backup to file volume without sparse detection");
        if (result.exitCode != 0) {
            logger.warn("{} phase=[CONVERT], source=[{}], target=[{}], command=[qemu-img-convert-nosparse], exitCode=[{}], output=[{}]",
                    RESTORE_TRACE, backupPath, volumePath, result.exitCode, result.output);
            throw new QemuImgException(String.format("qemu-img convert failed with exitCode [%s], output [%s]", result.exitCode, result.output));
        }
        logger.info("{} phase=[CONVERT], source=[{}], target=[{}], command=[qemu-img-convert-nosparse]",
                RESTORE_TRACE, backupPath, volumePath);
    }

    private boolean convertTemporaryRbdToFileVolume(String volumePath, int timeout, RbdImageSpec sourceImage, String tempImage) {
        QemuImgFile srcBackupFile = null;
        QemuImgFile destVolumeFile = null;
        try {
            srcBackupFile = new QemuImgFile(sourceImage.buildQemuUri(tempImage), QemuImg.PhysicalDiskFormat.RAW);
            destVolumeFile = new QemuImgFile(volumePath, getFileVolumeFormat(volumePath));
            String convertCommand = String.format("qemu-img convert -p -S 0 -f %s -O %s %s %s",
                    srcBackupFile.getFormat().toString().toLowerCase(Locale.ROOT), destVolumeFile.getFormat().toString().toLowerCase(Locale.ROOT),
                    quote(srcBackupFile.getFileName()), quote(destVolumeFile.getFileName()));
            CommandExecutionResult result = executeBashCommandWithResult(convertCommand, timeout, "Convert temporary RBD backup to file volume without sparse detection");
            if (result.exitCode != 0) {
                logger.warn("{} phase=[CONVERT_TEMP_RBD_TO_FILE], source=[{}], target=[{}], command=[qemu-img-convert-nosparse], exitCode=[{}], output=[{}]",
                        RESTORE_TRACE, srcBackupFile.getFileName(), destVolumeFile.getFileName(), result.exitCode, result.output);
                return false;
            }
            return true;
        } catch (RuntimeException e) {
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
            java.util.Map<String, String> info = qemu.info(new QemuImgFile(volumePath));
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
        CommandExecutionResult importResult = executeBashCommandWithResult(importCommand, timeout, "Import raw backup to temporary RBD");
        if (importResult.exitCode != 0) {
            logger.error("Failed to import base RBD backup {} into temporary image {}. Exit code: {}, output: {}", backupPaths.get(0), tempImage,
                    importResult.exitCode, importResult.output);
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
                CommandExecutionResult importDiffResult = executeBashCommandWithResult(importDiffCommand, timeout, "Import RBD diff to temporary image");
                if (importDiffResult.exitCode != 0) {
                    logger.error("Failed to import RBD diff {} into temporary image {}. Exit code: {}, output: {}", backupPath, tempImage,
                            importDiffResult.exitCode, importDiffResult.output);
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
        CommandExecutionResult createSnapshotResult = executeBashCommandWithResult(createSnapshotCommand, timeout, "Create RBD snapshot on temporary image");
        if (createSnapshotResult.exitCode != 0) {
            logger.error("Failed to create RBD snapshot {} on image {}. Exit code: {}, output: {}", snapshotName, image,
                    createSnapshotResult.exitCode, createSnapshotResult.output);
            return false;
        }
        return true;
    }

    private CommandExecutionResult executeBashCommandWithResult(String command, int timeoutInSeconds, String description) {
        logger.debug("{} command: {}", description, command);
        String wrappedCommand = String.format("set -o pipefail; { %s; } 2>&1; rc=$?; echo \"%s${rc}\"", command, COMMAND_EXIT_MARKER);
        String output = Script.runSimpleBashScriptWithFullResult(wrappedCommand, timeoutInSeconds);
        if (StringUtils.isBlank(output)) {
            return new CommandExecutionResult(-1, "");
        }
        int markerIndex = output.lastIndexOf(COMMAND_EXIT_MARKER);
        if (markerIndex < 0) {
            logger.warn("{} command output did not include an exit marker. Output: {}", description, output);
            return new CommandExecutionResult(-1, output.trim());
        }
        String commandOutput = output.substring(0, markerIndex).trim();
        String exitCodeString = output.substring(markerIndex + COMMAND_EXIT_MARKER.length()).trim();
        int exitCode;
        try {
            exitCode = Integer.parseInt(exitCodeString);
        } catch (NumberFormatException e) {
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

        private CommandExecutionResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
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
            java.util.Map<String, String> metadata = Files.readAllLines(metadataPath).stream()
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
        String currentDevice = Script.runSimpleBashScript(String.format(CURRENT_DEVICE, vmName));
        if (StringUtils.isBlank(currentDevice)) {
            throw new CloudRuntimeException(String.format("Unable to determine next disk target for VM [%s].", vmName));
        }
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

    private void fetchBackupFile(String hostName, String backupPath, int timeout) {
        int timeoutMillis = timeout * 1000;
        int mkdirExit = Script.runSimpleBashScriptForExitValue(String.format(MKDIR_P, backupPath));
        if (mkdirExit != 0) {
            throw new CloudRuntimeException(String.format("Failed to create local backup directory: %s", backupPath));
        }

        String cmd = String.format(RSYNC_DIR_FROM_REMOTE, hostName, backupPath, backupPath);
        logger.debug("Fetching commvault backup directory from remote host. cmd={}", cmd);

        int exit = Script.runSimpleBashScriptForExitValue(cmd, timeoutMillis, false);
        if (exit != 0) {
            throw new CloudRuntimeException(String.format(
                    "Failed to fetch backup directory from remote host [%s]. remotePath=[%s], localPath=[%s]",
                    hostName, backupPath, backupPath));
        }
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
            return String.format("%s-csrestore-%s", image, org.apache.commons.lang3.RandomStringUtils.randomAlphanumeric(8).toLowerCase(Locale.ROOT));
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
}
