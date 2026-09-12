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

package com.cloud.hypervisor.kvm.resource.wrapper;

import com.cloud.hypervisor.kvm.storage.KVMPhysicalDisk;
import com.cloud.hypervisor.kvm.storage.KVMStoragePool;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.script.Script;
import org.apache.cloudstack.utils.qemu.QemuImg;
import org.apache.cloudstack.utils.qemu.QemuImgException;
import org.apache.cloudstack.utils.qemu.QemuImgFile;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.libvirt.LibvirtException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class LibvirtAblestackRbdRestoreHelper {
    private static final Logger LOGGER = LogManager.getLogger(LibvirtAblestackRbdRestoreHelper.class);
    private static final String COMMAND_EXIT_MARKER = "__CS_COMMAND_EXIT__=";
    private static final long RESTORE_PRIMARY_SPACE_BUFFER_BYTES = 10L * 1024L * 1024L * 1024L;
    private static final String RBD_RESTORE_TEMP_SUFFIX = "-csrestore-";
    private static final String RBD_RESTORE_ORIGINAL_SUFFIX = "-csrestore-original-";

    private LibvirtAblestackRbdRestoreHelper() {
    }

    static boolean restoreRbdBackup(final String tracePrefix, final KVMStoragePool storagePool, final String volumePath,
            final List<String> backupPaths, final int timeoutSeconds, final boolean createTargetVolume) {
        if (backupPaths == null || backupPaths.isEmpty()) {
            return false;
        }

        validateRbdStorageSpace(tracePrefix, storagePool, backupPaths, timeoutSeconds);

        final String temporaryVolumePath = buildTemporaryRbdImageName(volumePath);
        boolean temporaryImageCreated = false;
        try {
            if (!restoreRbdBackupToImage(tracePrefix, storagePool, temporaryVolumePath, backupPaths, timeoutSeconds, true)) {
                LOGGER.error("{} phase=[RBD_TEMP_RESTORE_FAILED], targetVolume=[{}], temporaryVolume=[{}]",
                        tracePrefix, volumePath, temporaryVolumePath);
                return false;
            }
            temporaryImageCreated = true;
            return promoteTemporaryRbdImage(tracePrefix, storagePool, volumePath, temporaryVolumePath, timeoutSeconds, createTargetVolume);
        } finally {
            if (temporaryImageCreated && rbdImageExists(storagePool, temporaryVolumePath, timeoutSeconds)) {
                LOGGER.warn("{} phase=[RBD_TEMP_CLEANUP], temporaryVolume=[{}]", tracePrefix, temporaryVolumePath);
                deleteRbdImageIfPresent(tracePrefix, storagePool, temporaryVolumePath, timeoutSeconds);
            }
        }
    }

    /**
     * Restores an RBD backup chain (raw + rbdiff) into a temporary RBD image, then converts that image to a file volume.
     * Used when the restore target is a non-RBD primary storage path.
     */
    static boolean restoreRbdBackupChainToFileVolume(final String tracePrefix, final String volumePath, final List<String> backupPaths,
            final int timeoutSeconds, final String backupRootPath, final int backupIndex) {
        if (StringUtils.isBlank(backupRootPath)) {
            throw new CloudRuntimeException("Unable to locate backup root path for incremental RBD restore");
        }
        if (backupPaths == null || backupPaths.isEmpty()) {
            return false;
        }

        final RbdSourceImage sourceImage = getRbdSourceImageFromMetadata(backupRootPath, backupIndex);
        validateRbdStorageSpace(tracePrefix, sourceImage, backupPaths, timeoutSeconds);

        final String temporaryVolumePath = sourceImage.buildTempImageSpec();
        try {
            if (!importBackupChainToTemporaryRbd(tracePrefix, sourceImage, temporaryVolumePath, backupPaths, timeoutSeconds)) {
                LOGGER.error("{} phase=[RBD_FILE_TEMP_RESTORE_FAILED], targetVolume=[{}], temporaryVolume=[{}]",
                        tracePrefix, volumePath, temporaryVolumePath);
                return false;
            }
            return convertTemporaryRbdToFileVolume(tracePrefix, sourceImage, temporaryVolumePath, volumePath, timeoutSeconds);
        } finally {
            LOGGER.warn("{} phase=[RBD_FILE_TEMP_CLEANUP], temporaryVolume=[{}]", tracePrefix, temporaryVolumePath);
            deleteSourceRbdImageIfPresent(tracePrefix, sourceImage, temporaryVolumePath, timeoutSeconds);
        }
    }

    private static boolean restoreRbdBackupToImage(final String tracePrefix, final KVMStoragePool storagePool, final String volumePath,
            final List<String> backupPaths, final int timeoutSeconds, final boolean createTargetVolume) {
        if (backupPaths.stream().anyMatch(path -> path.endsWith(".rbdiff"))) {
            return restoreIncrementalRbdBackupChain(tracePrefix, storagePool, volumePath, backupPaths, timeoutSeconds, createTargetVolume);
        }

        final String backupPath = getRestorableFileBackupPath(backupPaths);
        if (getBackupFileFormat(backupPath) == QemuImg.PhysicalDiskFormat.RAW) {
            return importRawBackupToRbd(storagePool, volumePath, backupPath, timeoutSeconds, createTargetVolume);
        }

        QemuImg qemu;
        try {
            qemu = new QemuImg(timeoutSeconds * 1000, true, false);
            if (!createTargetVolume) {
                final KVMPhysicalDisk rbdDisk = storagePool.getPhysicalDisk(volumePath);
                LOGGER.debug("Restoring RBD volume: {}", rbdDisk);
                qemu.setSkipTargetVolumeCreation(true);
            }
        } catch (final LibvirtException ex) {
            throw new CloudRuntimeException("Failed to create qemu-img command to restore RBD volume with backup", ex);
        }

        QemuImgFile srcBackupFile = null;
        QemuImgFile destVolumeFile = null;
        try {
            srcBackupFile = new QemuImgFile(backupPath, getBackupFileFormat(backupPath));
            destVolumeFile = new QemuImgFile(KVMPhysicalDisk.RBDStringBuilder(storagePool, volumePath), QemuImg.PhysicalDiskFormat.RAW);
            LOGGER.debug("{} phase=[RBD_CONVERT_BEGIN], source=[{}], targetVolume=[{}]", tracePrefix, backupPath, volumePath);
            qemu.convert(srcBackupFile, destVolumeFile);
            LOGGER.debug("{} phase=[RBD_CONVERT_DONE], source=[{}], targetVolume=[{}]", tracePrefix, backupPath, volumePath);
            return true;
        } catch (final QemuImgException | LibvirtException e) {
            final String srcFilename = srcBackupFile != null ? srcBackupFile.getFileName() : null;
            final String destFilename = destVolumeFile != null ? destVolumeFile.getFileName() : null;
            LOGGER.error("Failed to convert backup {} to volume {}, the error was: {}", srcFilename, destFilename, e.getMessage());
            return false;
        }
    }

    private static boolean restoreIncrementalRbdBackupChain(final String tracePrefix, final KVMStoragePool storagePool, final String volumePath,
            final List<String> backupPaths, final int timeoutSeconds, final boolean createTargetVolume) {
        if (backupPaths.isEmpty() || !backupPaths.get(0).endsWith(".raw")) {
            throw new CloudRuntimeException("Incremental RBD backup chain is missing the base full backup");
        }
        if (!restoreRbdBackupToImage(tracePrefix, storagePool, volumePath, List.of(backupPaths.get(0)), timeoutSeconds, createTargetVolume)) {
            return false;
        }

        final List<String> restoreSnapshots = new ArrayList<>();
        try {
            final Map<String, String> baseMetadata = readRbdBackupMetadata(backupPaths.get(0));
            final String baseCheckpoint = baseMetadata.get("checkpoint_name");
            if (StringUtils.isNotBlank(baseCheckpoint)) {
                if (!ensureRbdSnapshotExists(storagePool, volumePath, baseCheckpoint, timeoutSeconds)) {
                    return false;
                }
                restoreSnapshots.add(baseCheckpoint);
            }

            for (int index = 1; index < backupPaths.size(); index++) {
                final String backupPath = backupPaths.get(index);
                if (!backupPath.endsWith(".rbdiff")) {
                    continue;
                }
                final Map<String, String> metadata = readRbdBackupMetadata(backupPath);
                final String parentCheckpoint = metadata.get("parent_checkpoint_name");
                final String checkpoint = metadata.get("checkpoint_name");
                if (StringUtils.isBlank(parentCheckpoint) || StringUtils.isBlank(checkpoint)) {
                    throw new CloudRuntimeException(String.format("RBD incremental backup metadata is incomplete for %s", backupPath));
                }
                if (!rbdSnapshotExists(storagePool, volumePath, parentCheckpoint, timeoutSeconds)) {
                    throw new CloudRuntimeException(String.format("Required parent snapshot %s is missing on volume %s", parentCheckpoint, volumePath));
                }
                final CommandExecutionResult importDiffResult = executeBashCommandWithResult(
                        buildRbdCommand(storagePool, "import-diff", backupPath, volumePath), timeoutSeconds, "Import RBD diff to temporary volume");
                if (importDiffResult.exitCode != 0) {
                    LOGGER.error("Failed to import RBD diff {} into volume {}. Exit code: {}, output: {}", backupPath, volumePath,
                            importDiffResult.exitCode, importDiffResult.output);
                    return false;
                }
                if (!ensureRbdSnapshotExists(storagePool, volumePath, checkpoint, timeoutSeconds)) {
                    return false;
                }
                restoreSnapshots.add(checkpoint);
            }
            return true;
        } finally {
            cleanupRbdRestoreSnapshots(storagePool, volumePath, restoreSnapshots, timeoutSeconds);
        }
    }

    private static boolean importRawBackupToRbd(final KVMStoragePool storagePool, final String volumePath, final String backupPath,
            final int timeoutSeconds, final boolean createTargetVolume) {
        if (!createTargetVolume && !deleteRbdImageIfPresent(storagePool, volumePath, timeoutSeconds)) {
            LOGGER.error("Failed to delete existing RBD volume {} before raw import", volumePath);
            return false;
        }

        final CommandExecutionResult importResult = executeBashCommandWithResult(
                buildRbdCommand(storagePool, "import", backupPath, volumePath), timeoutSeconds, "Import raw backup to RBD");
        if (importResult.exitCode != 0) {
            LOGGER.error("Failed to import raw backup {} into volume {}. Exit code: {}, output: {}", backupPath, volumePath,
                    importResult.exitCode, importResult.output);
            return false;
        }
        return true;
    }

    private static boolean promoteTemporaryRbdImage(final String tracePrefix, final KVMStoragePool storagePool, final String targetVolumePath,
            final String temporaryVolumePath, final int timeoutSeconds, final boolean createTargetVolume) {
        final String originalVolumePath = buildOriginalRbdImageName(targetVolumePath);
        boolean originalMovedAside = false;
        try {
            if (rbdImageExists(storagePool, targetVolumePath, timeoutSeconds)) {
                if (createTargetVolume) {
                    LOGGER.warn("{} phase=[RBD_TARGET_EXISTS_FOR_NEW_RESTORE], targetVolume=[{}], temporaryVolume=[{}]",
                            tracePrefix, targetVolumePath, temporaryVolumePath);
                    return false;
                }
                if (!renameRbdImage(storagePool, targetVolumePath, originalVolumePath, timeoutSeconds)) {
                    LOGGER.error("{} phase=[RBD_ORIGINAL_RENAME_FAILED], targetVolume=[{}], originalVolume=[{}]",
                            tracePrefix, targetVolumePath, originalVolumePath);
                    return false;
                }
                originalMovedAside = true;
            } else if (!createTargetVolume) {
                LOGGER.warn("{} phase=[RBD_ORIGINAL_MISSING_BEFORE_PROMOTE], targetVolume=[{}]", tracePrefix, targetVolumePath);
            }

            if (!renameRbdImage(storagePool, temporaryVolumePath, targetVolumePath, timeoutSeconds)) {
                LOGGER.error("{} phase=[RBD_TEMP_PROMOTE_FAILED], targetVolume=[{}], temporaryVolume=[{}]",
                        tracePrefix, targetVolumePath, temporaryVolumePath);
                if (originalMovedAside) {
                    rollbackOriginalRbdImage(tracePrefix, storagePool, targetVolumePath, originalVolumePath, timeoutSeconds);
                }
                return false;
            }

            if (originalMovedAside && !deleteRbdImageIfPresent(tracePrefix, storagePool, originalVolumePath, timeoutSeconds)) {
                LOGGER.warn("{} phase=[RBD_ORIGINAL_CLEANUP_FAILED], originalVolume=[{}]", tracePrefix, originalVolumePath);
            }
            LOGGER.info("{} phase=[RBD_TEMP_PROMOTED], targetVolume=[{}], temporaryVolume=[{}], originalVolume=[{}]",
                    tracePrefix, targetVolumePath, temporaryVolumePath, originalMovedAside ? originalVolumePath : null);
            return true;
        } catch (final CloudRuntimeException e) {
            if (originalMovedAside) {
                rollbackOriginalRbdImage(tracePrefix, storagePool, targetVolumePath, originalVolumePath, timeoutSeconds);
            }
            throw e;
        }
    }

    private static void rollbackOriginalRbdImage(final String tracePrefix, final KVMStoragePool storagePool, final String targetVolumePath,
            final String originalVolumePath, final int timeoutSeconds) {
        if (!rbdImageExists(storagePool, originalVolumePath, timeoutSeconds)) {
            LOGGER.error("{} phase=[RBD_ORIGINAL_ROLLBACK_SKIPPED], targetVolume=[{}], originalVolume=[{}]",
                    tracePrefix, targetVolumePath, originalVolumePath);
            return;
        }
        if (rbdImageExists(storagePool, targetVolumePath, timeoutSeconds)) {
            deleteRbdImageIfPresent(tracePrefix, storagePool, targetVolumePath, timeoutSeconds);
        }
        if (!renameRbdImage(storagePool, originalVolumePath, targetVolumePath, timeoutSeconds)) {
            LOGGER.error("{} phase=[RBD_ORIGINAL_ROLLBACK_FAILED], targetVolume=[{}], originalVolume=[{}]",
                    tracePrefix, targetVolumePath, originalVolumePath);
        }
    }

    private static void validateRbdStorageSpace(final String tracePrefix, final KVMStoragePool storagePool, final List<String> backupPaths,
            final int timeoutSeconds) {
        final long requiredBytes = estimateRequiredBytesForRbdRestore(backupPaths);
        final long bufferBytes = Math.max(RESTORE_PRIMARY_SPACE_BUFFER_BYTES, requiredBytes / 5L);
        final long minimumAvailableBytes = requiredBytes + bufferBytes;
        final Long availableBytes = getCephPoolAvailableBytes(storagePool, timeoutSeconds);
        if (availableBytes == null) {
            LOGGER.warn("{} phase=[RBD_SPACE_CHECK_SKIPPED], pool=[{}], requiredBytes=[{}], bufferBytes=[{}]",
                    tracePrefix, storagePool.getSourceDir(), requiredBytes, bufferBytes);
            return;
        }
        LOGGER.info("{} phase=[RBD_SPACE_CHECK], pool=[{}], requiredBytes=[{}], bufferBytes=[{}], minimumAvailableBytes=[{}], availableBytes=[{}]",
                tracePrefix, storagePool.getSourceDir(), requiredBytes, bufferBytes, minimumAvailableBytes, availableBytes);
        if (availableBytes < minimumAvailableBytes) {
            throw new CloudRuntimeException(String.format(
                    "Insufficient Ceph RBD pool space for restore on pool [%s]. Required at least [%d] bytes including buffer, but only [%d] bytes are available.",
                    storagePool.getSourceDir(), minimumAvailableBytes, availableBytes));
        }
    }

    private static void validateRbdStorageSpace(final String tracePrefix, final RbdSourceImage sourceImage, final List<String> backupPaths,
            final int timeoutSeconds) {
        final long requiredBytes = estimateRequiredBytesForRbdRestore(backupPaths);
        final long bufferBytes = Math.max(RESTORE_PRIMARY_SPACE_BUFFER_BYTES, requiredBytes / 5L);
        final long minimumAvailableBytes = requiredBytes + bufferBytes;
        final Long availableBytes = getCephPoolAvailableBytes(sourceImage, timeoutSeconds);
        if (availableBytes == null) {
            LOGGER.warn("{} phase=[RBD_SPACE_CHECK_SKIPPED], pool=[{}], requiredBytes=[{}], bufferBytes=[{}]",
                    tracePrefix, sourceImage.getPoolName(), requiredBytes, bufferBytes);
            return;
        }
        LOGGER.info("{} phase=[RBD_SPACE_CHECK], pool=[{}], requiredBytes=[{}], bufferBytes=[{}], minimumAvailableBytes=[{}], availableBytes=[{}]",
                tracePrefix, sourceImage.getPoolName(), requiredBytes, bufferBytes, minimumAvailableBytes, availableBytes);
        if (availableBytes < minimumAvailableBytes) {
            throw new CloudRuntimeException(String.format(
                    "Insufficient Ceph RBD pool space for restore on pool [%s]. Required at least [%d] bytes including buffer, but only [%d] bytes are available.",
                    sourceImage.getPoolName(), minimumAvailableBytes, availableBytes));
        }
    }

    private static long estimateRequiredBytesForRbdRestore(final List<String> backupPaths) {
        final String sizeSource = backupPaths.stream().anyMatch(path -> path.endsWith(".rbdiff")) && backupPaths.get(0).endsWith(".raw")
                ? backupPaths.get(0) : getRestorableFileBackupPath(backupPaths);
        try {
            final QemuImg qemu = new QemuImg(0);
            final Map<String, String> info = qemu.info(new QemuImgFile(sizeSource, getBackupFileFormat(sizeSource)));
            final String virtualSize = info.get(QemuImg.VIRTUAL_SIZE);
            if (StringUtils.isNotBlank(virtualSize)) {
                return Long.parseLong(virtualSize);
            }
        } catch (final NumberFormatException | QemuImgException | LibvirtException e) {
            LOGGER.warn("Failed to parse virtual size for RBD restore backup [{}]. Falling back to file size.", sizeSource, e);
        }
        try {
            return Files.size(Paths.get(sizeSource));
        } catch (final IOException e) {
            throw new CloudRuntimeException(String.format("Failed to estimate RBD restore size for backup [%s]: %s", sizeSource, e.getMessage()), e);
        }
    }

    private static Long getCephPoolAvailableBytes(final KVMStoragePool storagePool, final int timeoutSeconds) {
        return getCephPoolAvailableBytes(buildCephCommand(storagePool, "df", "detail", "--format", "json"),
                storagePool.getSourceDir(), timeoutSeconds);
    }

    private static Long getCephPoolAvailableBytes(final RbdSourceImage sourceImage, final int timeoutSeconds) {
        return getCephPoolAvailableBytes(sourceImage.buildCephCommand("df", "detail", "--format", "json"),
                sourceImage.getPoolName(), timeoutSeconds);
    }

    private static Long getCephPoolAvailableBytes(final String cephDfCommand, final String pool, final int timeoutSeconds) {
        final String python = "import json,sys; "
                + "pool=sys.argv[1]; data=json.load(sys.stdin); "
                + "pools=data.get('pools', []); "
                + "matches=[p for p in pools if p.get('name') == pool]; "
                + "stats=(matches[0].get('stats', {}) if matches else (data.get('stats', {}) if not pool else {})); "
                + "value=stats.get('max_avail') or stats.get('available') or stats.get('total_avail_bytes'); "
                + "print(value if value is not None else '')";
        final String command = cephDfCommand + " | python3 -c " + quote(python) + " " + quote(StringUtils.defaultString(pool));
        final CommandExecutionResult result = executeBashCommandWithResult(command, timeoutSeconds, "Query Ceph pool available bytes");
        if (result.exitCode != 0 || StringUtils.isBlank(result.output)) {
            return null;
        }
        final String lastLine = Arrays.stream(result.output.split("\n"))
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .reduce((first, second) -> second)
                .orElse("");
        try {
            return Long.parseLong(lastLine);
        } catch (final NumberFormatException e) {
            LOGGER.warn("Failed to parse Ceph pool available bytes from output [{}]", result.output, e);
            return null;
        }
    }

    private static String getRestorableFileBackupPath(final List<String> backupPaths) {
        for (int index = backupPaths.size() - 1; index >= 0; index--) {
            final String backupPath = backupPaths.get(index);
            if (StringUtils.isNotBlank(backupPath) && Files.exists(Paths.get(backupPath))) {
                return backupPath;
            }
        }
        return backupPaths.get(backupPaths.size() - 1);
    }

    private static QemuImg.PhysicalDiskFormat getBackupFileFormat(final String backupPath) {
        if (backupPath.endsWith(".raw")) {
            return QemuImg.PhysicalDiskFormat.RAW;
        }
        return QemuImg.PhysicalDiskFormat.QCOW2;
    }

    private static Map<String, String> readRbdBackupMetadata(final String backupPath) {
        final java.nio.file.Path metadataPath = Paths.get(backupPath).getParent().resolve("rbd-backup.meta");
        if (!Files.exists(metadataPath)) {
            throw new CloudRuntimeException(String.format("RBD backup metadata file not found: %s", metadataPath));
        }
        try {
            return Files.readAllLines(metadataPath).stream()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && line.contains("="))
                    .map(line -> line.split("=", 2))
                    .collect(java.util.stream.Collectors.toMap(parts -> parts[0], parts -> parts[1], (left, right) -> right));
        } catch (final IOException e) {
            throw new CloudRuntimeException(String.format("Failed to read RBD backup metadata: %s", metadataPath), e);
        }
    }

    private static boolean ensureRbdSnapshotExists(final KVMStoragePool storagePool, final String volumePath, final String snapshotName,
            final int timeoutSeconds) {
        if (rbdSnapshotExists(storagePool, volumePath, snapshotName, timeoutSeconds)) {
            return true;
        }
        final CommandExecutionResult result = executeBashCommandWithResult(
                buildRbdCommand(storagePool, "snap", "create", volumePath + "@" + snapshotName), timeoutSeconds, "Create RBD snapshot");
        if (result.exitCode != 0) {
            LOGGER.error("Failed to create RBD snapshot {} on volume {}. Exit code: {}, output: {}", snapshotName, volumePath, result.exitCode, result.output);
            return false;
        }
        return true;
    }

    private static boolean rbdSnapshotExists(final KVMStoragePool storagePool, final String volumePath, final String snapshotName,
            final int timeoutSeconds) {
        final String existsCommand = buildRbdCommand(storagePool, "snap", "ls", volumePath)
                + " | awk 'NR>1 {print $2}' | grep -Fx " + quote(snapshotName);
        return Script.runSimpleBashScriptForExitValue(existsCommand, timeoutSeconds * 1000, false) == 0;
    }

    private static void cleanupRbdRestoreSnapshots(final KVMStoragePool storagePool, final String volumePath, final List<String> snapshotNames,
            final int timeoutSeconds) {
        for (int index = snapshotNames.size() - 1; index >= 0; index--) {
            final String snapshotName = snapshotNames.get(index);
            Script.runSimpleBashScriptForExitValue(buildRbdCommand(storagePool, "snap", "rm", volumePath + "@" + snapshotName),
                    timeoutSeconds * 1000, false);
        }
    }

    private static boolean renameRbdImage(final KVMStoragePool storagePool, final String sourceImage, final String targetImage,
            final int timeoutSeconds) {
        return executeBashCommandWithResult(buildRbdCommand(storagePool, "rename", sourceImage, targetImage), timeoutSeconds, "Rename RBD image").exitCode == 0;
    }

    private static boolean rbdImageExists(final KVMStoragePool storagePool, final String volumePath, final int timeoutSeconds) {
        return Script.runSimpleBashScriptForExitValue(buildRbdCommand(storagePool, "info", volumePath), timeoutSeconds * 1000, false) == 0;
    }

    private static boolean deleteRbdImageIfPresent(final KVMStoragePool storagePool, final String volumePath, final int timeoutSeconds) {
        return deleteRbdImageIfPresent(null, storagePool, volumePath, timeoutSeconds);
    }

    private static boolean deleteRbdImageIfPresent(final String tracePrefix, final KVMStoragePool storagePool, final String volumePath, final int timeoutSeconds) {
        if (!rbdImageExists(storagePool, volumePath, timeoutSeconds)) {
            return true;
        }
        if (rbdImageHasSnapshots(storagePool, volumePath, timeoutSeconds) && !purgeRbdImageSnapshots(tracePrefix, storagePool, volumePath, timeoutSeconds)) {
            return false;
        }
        CommandExecutionResult result = executeBashCommandWithResult(buildRbdCommand(storagePool, "rm", volumePath), timeoutSeconds, "Remove RBD image");
        if (result.exitCode == 0) {
            return true;
        }
        if (StringUtils.isNotBlank(tracePrefix)) {
            LOGGER.warn("{} phase=[RBD_IMAGE_DELETE_RETRY_WITH_SNAPSHOT_PURGE], image=[{}], exitCode=[{}], output=[{}]",
                    tracePrefix, volumePath, result.exitCode, result.output);
        }
        if (!purgeRbdImageSnapshots(tracePrefix, storagePool, volumePath, timeoutSeconds)) {
            return false;
        }
        result = executeBashCommandWithResult(buildRbdCommand(storagePool, "rm", volumePath), timeoutSeconds, "Remove RBD image after snapshot purge");
        if (result.exitCode != 0 && StringUtils.isNotBlank(tracePrefix)) {
            LOGGER.warn("{} phase=[RBD_IMAGE_DELETE_AFTER_PURGE_FAILED], image=[{}], exitCode=[{}], output=[{}]",
                    tracePrefix, volumePath, result.exitCode, result.output);
        }
        return result.exitCode == 0;
    }

    private static boolean rbdImageHasSnapshots(final KVMStoragePool storagePool, final String volumePath, final int timeoutSeconds) {
        final String command = buildRbdCommand(storagePool, "snap", "ls", volumePath) + " | awk 'NR>1 {found=1} END {exit found ? 0 : 1}'";
        return Script.runSimpleBashScriptForExitValue(command, timeoutSeconds * 1000, false) == 0;
    }

    private static boolean purgeRbdImageSnapshots(final String tracePrefix, final KVMStoragePool storagePool, final String volumePath,
            final int timeoutSeconds) {
        if (StringUtils.isNotBlank(tracePrefix)) {
            LOGGER.info("{} phase=[RBD_IMAGE_SNAPSHOT_PURGE], image=[{}]", tracePrefix, volumePath);
        }
        final CommandExecutionResult purgeResult = executeBashCommandWithResult(buildRbdCommand(storagePool, "snap", "purge", volumePath),
                timeoutSeconds, "Purge RBD image snapshots before remove");
        if (purgeResult.exitCode != 0) {
            if (StringUtils.isNotBlank(tracePrefix)) {
                LOGGER.warn("{} phase=[RBD_IMAGE_SNAPSHOT_PURGE_FAILED], image=[{}], exitCode=[{}], output=[{}]",
                        tracePrefix, volumePath, purgeResult.exitCode, purgeResult.output);
            }
            return false;
        }
        return true;
    }

    private static String buildTemporaryRbdImageName(final String volumePath) {
        return String.format("%s%s%s", volumePath, RBD_RESTORE_TEMP_SUFFIX, RandomStringUtils.randomAlphanumeric(8).toLowerCase(Locale.ROOT));
    }

    private static String buildOriginalRbdImageName(final String volumePath) {
        return String.format("%s%s%s", volumePath, RBD_RESTORE_ORIGINAL_SUFFIX, System.currentTimeMillis());
    }

    private static String buildRbdCommand(final KVMStoragePool storagePool, final String action, final String... args) {
        return buildCephToolCommand("rbd", storagePool, action, args);
    }

    private static String buildCephCommand(final KVMStoragePool storagePool, final String action, final String... args) {
        return buildCephToolCommand("ceph", storagePool, action, args);
    }

    private static String buildCephToolCommand(final String tool, final KVMStoragePool storagePool, final String action, final String... args) {
        final StringBuilder command = new StringBuilder(tool);
        if (StringUtils.isNotBlank(storagePool.getSourceHost())) {
            command.append(" -m ").append(quote(formatRbdMonHosts(storagePool.getSourceHost(), storagePool.getSourcePort())));
        }
        if (StringUtils.isNotBlank(storagePool.getAuthUserName())) {
            command.append(" --id ").append(quote(storagePool.getAuthUserName()));
        }
        if (StringUtils.isNotBlank(storagePool.getAuthSecret())) {
            command.append(" --key ").append(quote(storagePool.getAuthSecret()));
        }
        command.append(" ").append(action);
        for (final String arg : args) {
            command.append(" ").append(quote(arg));
        }
        return command.toString();
    }

    private static String formatRbdMonHosts(final String hosts, final int port) {
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

    private static CommandExecutionResult executeBashCommandWithResult(final String command, final int timeoutSeconds, final String description) {
        LOGGER.debug("{} command: {}", description, command);
        final String wrappedCommand = String.format("set -o pipefail; { %s; } 2>&1; rc=$?; echo \"%s${rc}\"", command, COMMAND_EXIT_MARKER);
        final String output = Script.runSimpleBashScriptWithFullResult(wrappedCommand, timeoutSeconds * 1000);
        if (StringUtils.isBlank(output)) {
            return new CommandExecutionResult(-1, "");
        }
        final int markerIndex = output.lastIndexOf(COMMAND_EXIT_MARKER);
        if (markerIndex < 0) {
            LOGGER.warn("{} command output did not include an exit marker. Output: {}", description, output);
            return new CommandExecutionResult(-1, output.trim());
        }
        final String commandOutput = output.substring(0, markerIndex).trim();
        final String exitCodeString = output.substring(markerIndex + COMMAND_EXIT_MARKER.length()).trim();
        int exitCode;
        try {
            exitCode = Integer.parseInt(exitCodeString);
        } catch (final NumberFormatException e) {
            LOGGER.warn("{} command exit marker was not a valid integer. Output: {}", description, output, e);
            exitCode = -1;
        }
        return new CommandExecutionResult(exitCode, commandOutput);
    }

    private static String quote(final String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static boolean importBackupChainToTemporaryRbd(final String tracePrefix, final RbdSourceImage sourceImage,
            final String temporaryVolumePath, final List<String> backupPaths, final int timeoutSeconds) {
        if (backupPaths.isEmpty() || !backupPaths.get(0).endsWith(".raw")) {
            throw new CloudRuntimeException("Incremental RBD backup chain is missing the base full backup");
        }
        final CommandExecutionResult importResult = executeBashCommandWithResult(
                sourceImage.buildRbdCommand("import", backupPaths.get(0), temporaryVolumePath),
                timeoutSeconds, "Import raw backup to temporary RBD");
        if (importResult.exitCode != 0) {
            LOGGER.error("{} phase=[RBD_FILE_BASE_IMPORT_FAILED], source=[{}], temporaryVolume=[{}], exitCode=[{}], output=[{}]",
                    tracePrefix, backupPaths.get(0), temporaryVolumePath, importResult.exitCode, importResult.output);
            return false;
        }

        final List<String> restoreSnapshots = new ArrayList<>();
        try {
            final Map<String, String> baseMetadata = readRbdBackupMetadata(backupPaths.get(0));
            final String baseCheckpoint = baseMetadata.get("checkpoint_name");
            if (StringUtils.isNotBlank(baseCheckpoint)) {
                if (!ensureSourceRbdSnapshotExists(sourceImage, temporaryVolumePath, baseCheckpoint, timeoutSeconds)) {
                    return false;
                }
                restoreSnapshots.add(baseCheckpoint);
            }
            for (int index = 1; index < backupPaths.size(); index++) {
                final String backupPath = backupPaths.get(index);
                if (!backupPath.endsWith(".rbdiff")) {
                    continue;
                }
                final Map<String, String> metadata = readRbdBackupMetadata(backupPath);
                final String parentCheckpoint = metadata.get("parent_checkpoint_name");
                final String checkpoint = metadata.get("checkpoint_name");
                if (StringUtils.isBlank(parentCheckpoint) || StringUtils.isBlank(checkpoint)) {
                    throw new CloudRuntimeException(String.format("RBD incremental backup metadata is incomplete for %s", backupPath));
                }
                if (!sourceRbdSnapshotExists(sourceImage, temporaryVolumePath, parentCheckpoint, timeoutSeconds)) {
                    throw new CloudRuntimeException(String.format("Required parent snapshot %s is missing on temporary image %s",
                            parentCheckpoint, temporaryVolumePath));
                }
                final CommandExecutionResult importDiffResult = executeBashCommandWithResult(
                        sourceImage.buildRbdCommand("import-diff", backupPath, temporaryVolumePath),
                        timeoutSeconds, "Import RBD diff to temporary image");
                if (importDiffResult.exitCode != 0) {
                    LOGGER.error("{} phase=[RBD_FILE_DIFF_IMPORT_FAILED], source=[{}], temporaryVolume=[{}], exitCode=[{}], output=[{}]",
                            tracePrefix, backupPath, temporaryVolumePath, importDiffResult.exitCode, importDiffResult.output);
                    return false;
                }
                if (!ensureSourceRbdSnapshotExists(sourceImage, temporaryVolumePath, checkpoint, timeoutSeconds)) {
                    return false;
                }
                restoreSnapshots.add(checkpoint);
            }
            return true;
        } finally {
            cleanupSourceRbdRestoreSnapshots(sourceImage, temporaryVolumePath, restoreSnapshots, timeoutSeconds);
        }
    }

    private static boolean convertTemporaryRbdToFileVolume(final String tracePrefix, final RbdSourceImage sourceImage,
            final String temporaryVolumePath, final String volumePath, final int timeoutSeconds) {
        QemuImgFile srcBackupFile = null;
        QemuImgFile destVolumeFile = null;
        try {
            final QemuImg qemu = new QemuImg(timeoutSeconds * 1000, true, false);
            srcBackupFile = new QemuImgFile(sourceImage.buildQemuUri(temporaryVolumePath), QemuImg.PhysicalDiskFormat.RAW);
            destVolumeFile = new QemuImgFile(volumePath, detectFileVolumeFormat(volumePath));
            LOGGER.debug("{} phase=[RBD_FILE_CONVERT_BEGIN], source=[{}], targetVolume=[{}]",
                    tracePrefix, srcBackupFile.getFileName(), volumePath);
            qemu.convert(srcBackupFile, destVolumeFile);
            LOGGER.debug("{} phase=[RBD_FILE_CONVERT_DONE], source=[{}], targetVolume=[{}]",
                    tracePrefix, srcBackupFile.getFileName(), volumePath);
            return true;
        } catch (final QemuImgException | LibvirtException e) {
            final String srcFilename = srcBackupFile != null ? srcBackupFile.getFileName() : temporaryVolumePath;
            final String destFilename = destVolumeFile != null ? destVolumeFile.getFileName() : volumePath;
            LOGGER.error("Failed to convert temporary RBD {} to volume {}, the error was: {}", srcFilename, destFilename, e.getMessage());
            return false;
        }
    }

    private static QemuImg.PhysicalDiskFormat detectFileVolumeFormat(final String volumePath) {
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
            LOGGER.warn("Failed to detect file volume format for path {}. Falling back to qcow2.", volumePath, e);
        }
        return QemuImg.PhysicalDiskFormat.QCOW2;
    }

    private static RbdSourceImage getRbdSourceImageFromMetadata(final String backupRootPath, final int backupIndex) {
        final java.nio.file.Path metadataPath = Paths.get(backupRootPath, "rbd-backup.meta");
        if (!Files.exists(metadataPath)) {
            throw new CloudRuntimeException(String.format("RBD backup metadata file not found: %s", metadataPath));
        }
        try {
            final Map<String, String> metadata = Files.readAllLines(metadataPath).stream()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && line.contains("="))
                    .map(line -> line.split("=", 2))
                    .collect(java.util.stream.Collectors.toMap(parts -> parts[0], parts -> parts[1], (left, right) -> right));
            final String diskPaths = metadata.get("disk_paths");
            if (StringUtils.isBlank(diskPaths)) {
                throw new CloudRuntimeException("RBD backup metadata does not contain disk_paths");
            }
            final List<String> values = Arrays.asList(diskPaths.split(","));
            if (backupIndex >= values.size()) {
                throw new CloudRuntimeException(String.format("RBD backup metadata does not contain disk path for index %d", backupIndex));
            }
            return RbdSourceImage.fromUri(values.get(backupIndex));
        } catch (final IOException e) {
            throw new CloudRuntimeException(String.format("Failed to read RBD backup metadata: %s", metadataPath), e);
        }
    }

    private static boolean ensureSourceRbdSnapshotExists(final RbdSourceImage sourceImage, final String image, final String snapshotName,
            final int timeoutSeconds) {
        if (sourceRbdSnapshotExists(sourceImage, image, snapshotName, timeoutSeconds)) {
            return true;
        }
        final CommandExecutionResult result = executeBashCommandWithResult(
                sourceImage.buildRbdCommand("snap", "create", image + "@" + snapshotName),
                timeoutSeconds, "Create RBD snapshot on temporary image");
        if (result.exitCode != 0) {
            LOGGER.error("Failed to create RBD snapshot {} on image {}. Exit code: {}, output: {}",
                    snapshotName, image, result.exitCode, result.output);
            return false;
        }
        return true;
    }

    private static boolean sourceRbdSnapshotExists(final RbdSourceImage sourceImage, final String image, final String snapshotName,
            final int timeoutSeconds) {
        final String existsCommand = sourceImage.buildRbdCommand("snap", "ls", image)
                + " | awk 'NR>1 {print $2}' | grep -Fx " + quote(snapshotName);
        return Script.runSimpleBashScriptForExitValue(existsCommand, timeoutSeconds * 1000, false) == 0;
    }

    private static void cleanupSourceRbdRestoreSnapshots(final RbdSourceImage sourceImage, final String image,
            final List<String> snapshotNames, final int timeoutSeconds) {
        for (int index = snapshotNames.size() - 1; index >= 0; index--) {
            final String snapshotName = snapshotNames.get(index);
            Script.runSimpleBashScriptForExitValue(sourceImage.buildRbdCommand("snap", "rm", image + "@" + snapshotName),
                    timeoutSeconds * 1000, false);
        }
    }

    private static boolean deleteSourceRbdImageIfPresent(final String tracePrefix, final RbdSourceImage sourceImage,
            final String volumePath, final int timeoutSeconds) {
        if (!sourceRbdImageExists(sourceImage, volumePath, timeoutSeconds)) {
            return true;
        }
        if (sourceRbdImageHasSnapshots(sourceImage, volumePath, timeoutSeconds)
                && !purgeSourceRbdImageSnapshots(tracePrefix, sourceImage, volumePath, timeoutSeconds)) {
            return false;
        }
        CommandExecutionResult result = executeBashCommandWithResult(sourceImage.buildRbdCommand("rm", volumePath),
                timeoutSeconds, "Remove temporary RBD image");
        if (result.exitCode == 0) {
            return true;
        }
        LOGGER.warn("{} phase=[RBD_FILE_TEMP_DELETE_RETRY_WITH_SNAPSHOT_PURGE], image=[{}], exitCode=[{}], output=[{}]",
                tracePrefix, volumePath, result.exitCode, result.output);
        if (!purgeSourceRbdImageSnapshots(tracePrefix, sourceImage, volumePath, timeoutSeconds)) {
            return false;
        }
        result = executeBashCommandWithResult(sourceImage.buildRbdCommand("rm", volumePath),
                timeoutSeconds, "Remove temporary RBD image after snapshot purge");
        if (result.exitCode != 0) {
            LOGGER.warn("{} phase=[RBD_FILE_TEMP_DELETE_AFTER_PURGE_FAILED], image=[{}], exitCode=[{}], output=[{}]",
                    tracePrefix, volumePath, result.exitCode, result.output);
        }
        return result.exitCode == 0;
    }

    private static boolean sourceRbdImageExists(final RbdSourceImage sourceImage, final String volumePath, final int timeoutSeconds) {
        return Script.runSimpleBashScriptForExitValue(sourceImage.buildRbdCommand("info", volumePath), timeoutSeconds * 1000, false) == 0;
    }

    private static boolean sourceRbdImageHasSnapshots(final RbdSourceImage sourceImage, final String volumePath, final int timeoutSeconds) {
        final String command = sourceImage.buildRbdCommand("snap", "ls", volumePath) + " | awk 'NR>1 {found=1} END {exit found ? 0 : 1}'";
        return Script.runSimpleBashScriptForExitValue(command, timeoutSeconds * 1000, false) == 0;
    }

    private static boolean purgeSourceRbdImageSnapshots(final String tracePrefix, final RbdSourceImage sourceImage,
            final String volumePath, final int timeoutSeconds) {
        LOGGER.info("{} phase=[RBD_FILE_TEMP_SNAPSHOT_PURGE], image=[{}]", tracePrefix, volumePath);
        final CommandExecutionResult purgeResult = executeBashCommandWithResult(sourceImage.buildRbdCommand("snap", "purge", volumePath),
                timeoutSeconds, "Purge temporary RBD image snapshots before remove");
        if (purgeResult.exitCode != 0) {
            LOGGER.warn("{} phase=[RBD_FILE_TEMP_SNAPSHOT_PURGE_FAILED], image=[{}], exitCode=[{}], output=[{}]",
                    tracePrefix, volumePath, purgeResult.exitCode, purgeResult.output);
            return false;
        }
        return true;
    }

    private static final class RbdSourceImage {
        private final String imageSpec;
        private final String monHosts;
        private final String port;
        private final String user;
        private final String key;

        private RbdSourceImage(final String imageSpec, final String monHosts, final String port, final String user, final String key) {
            this.imageSpec = imageSpec;
            this.monHosts = monHosts;
            this.port = port;
            this.user = user;
            this.key = key;
        }

        private static RbdSourceImage fromUri(final String uri) {
            if (StringUtils.isBlank(uri)) {
                throw new CloudRuntimeException("RBD disk path is blank");
            }
            String imageSpec = null;
            String monHosts = null;
            String port = null;
            String user = null;
            String key = null;

            if (uri.startsWith("rbd/")) {
                imageSpec = uri;
            } else if (uri.startsWith("rbd:")) {
                final String withoutScheme = uri.substring("rbd:".length());
                final int colonIndex = withoutScheme.indexOf(':');
                imageSpec = colonIndex >= 0 ? withoutScheme.substring(0, colonIndex) : withoutScheme;
                final String options = colonIndex >= 0 ? withoutScheme.substring(colonIndex + 1) : "";
                for (final String option : options.split(":")) {
                    if (option.startsWith("mon_host=")) {
                        monHosts = option.substring("mon_host=".length()).replace("\\;", ",").replace("\\:", ":");
                    } else if (option.startsWith("port=")) {
                        port = option.substring("port=".length());
                    } else if (option.startsWith("id=")) {
                        user = option.substring("id=".length());
                    } else if (option.startsWith("key=")) {
                        key = option.substring("key=".length());
                    }
                }
            } else {
                imageSpec = uri;
            }

            if (StringUtils.isBlank(imageSpec)) {
                throw new CloudRuntimeException(String.format("Unable to parse RBD disk path: %s", uri));
            }
            return new RbdSourceImage(imageSpec, monHosts, port, user, key);
        }

        private String getPoolName() {
            final int slashIndex = imageSpec.indexOf('/');
            return slashIndex > 0 ? imageSpec.substring(0, slashIndex) : imageSpec;
        }

        private String buildTempImageSpec() {
            return String.format("%s-csrestore-%s", imageSpec, RandomStringUtils.randomAlphanumeric(8).toLowerCase(Locale.ROOT));
        }

        private String buildQemuUri(final String targetImage) {
            final StringBuilder uri = new StringBuilder("rbd:").append(targetImage);
            if (StringUtils.isNotBlank(monHosts)) {
                uri.append(":mon_host=").append(monHosts.replace(",", "\\;"));
            }
            if (StringUtils.isNotBlank(port)) {
                uri.append(":port=").append(port);
            }
            if (StringUtils.isNotBlank(user)) {
                uri.append(":id=").append(user);
            }
            if (StringUtils.isNotBlank(key)) {
                uri.append(":key=").append(key);
            }
            return uri.toString();
        }

        private String buildRbdCommand(final String... tokens) {
            return buildToolCommand("rbd", tokens);
        }

        private String buildCephCommand(final String... tokens) {
            return buildToolCommand("ceph", tokens);
        }

        private String buildToolCommand(final String tool, final String... tokens) {
            final StringBuilder command = new StringBuilder(tool);
            final String monArg = formatMonArg();
            if (StringUtils.isNotBlank(monArg)) {
                command.append(" -m ").append(quote(monArg));
            }
            if (StringUtils.isNotBlank(user)) {
                command.append(" --id ").append(quote(user));
            }
            if (StringUtils.isNotBlank(key)) {
                command.append(" --key ").append(quote(key));
            }
            for (final String token : tokens) {
                command.append(" ").append(quote(token));
            }
            return command.toString();
        }

        private String formatMonArg() {
            if (StringUtils.isBlank(monHosts)) {
                return null;
            }
            if (StringUtils.isBlank(port)) {
                return monHosts;
            }
            final String[] hostValues = monHosts.split(",");
            final List<String> formattedHosts = new ArrayList<>();
            for (final String host : hostValues) {
                final String normalizedHost = host.replace("[", "").replace("]", "").trim();
                if (StringUtils.isBlank(normalizedHost)) {
                    continue;
                }
                if (normalizedHost.contains(":")) {
                    formattedHosts.add(normalizedHost);
                } else {
                    formattedHosts.add(normalizedHost + ":" + port);
                }
            }
            return String.join(",", formattedHosts);
        }
    }

    private static final class CommandExecutionResult {
        private final int exitCode;
        private final String output;

        private CommandExecutionResult(final int exitCode, final String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }
}
