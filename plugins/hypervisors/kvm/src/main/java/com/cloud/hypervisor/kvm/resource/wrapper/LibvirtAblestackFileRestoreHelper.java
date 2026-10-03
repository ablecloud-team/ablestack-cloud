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

import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.script.Script;
import com.cloud.storage.Storage;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;
import org.apache.cloudstack.utils.qemu.QemuImg;
import org.apache.cloudstack.utils.qemu.QemuImgException;
import org.apache.cloudstack.utils.qemu.QemuImgFile;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Logger;
import org.libvirt.LibvirtException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class LibvirtAblestackFileRestoreHelper {
    private static final String QEMU_IMG_HAS_BACKING_COMMAND = "qemu-img info --output=json %s 2>/dev/null | grep -q '\"backing-filename\"'";
    private static final long RESTORE_PRIMARY_SPACE_BUFFER_BYTES = 10L * 1024L * 1024L * 1024L;

    private LibvirtAblestackFileRestoreHelper() {
    }

    static boolean replaceFileVolumeWithBackup(final String trace, final Logger logger, final String volumePath,
            final List<String> backupPaths, final int timeout, final String temporaryFilePrefix) {
        if (backupPaths == null || backupPaths.isEmpty()) {
            return false;
        }
        final String leafBackupPath = getRestorableFileBackupPath(backupPaths);
        if (backupPaths.size() > 1) {
            logger.info("{} phase=[QCOW2_CHAIN_LEAF_SELECTED], target=[{}], leaf=[{}], chainFiles=[{}]",
                    trace, volumePath, leafBackupPath, backupPaths);
        }
        return replaceFileVolumeWithBackup(trace, logger, volumePath, leafBackupPath, timeout, temporaryFilePrefix);
    }

    static boolean replaceFileVolumeWithBackup(final String trace, final Logger logger, final String volumePath,
            final String backupPath, final int timeout, final String temporaryFilePrefix) {
        QemuImgFile srcBackupFile = null;
        Path temporaryVolumePath = null;
        Path movedAsideTarget = null;
        try {
            srcBackupFile = new QemuImgFile(backupPath, getBackupFileFormat(backupPath));
            final QemuImg.PhysicalDiskFormat targetFormat = getFileVolumeFormat(logger, volumePath);
            validatePrimaryStorageSpaceForFileRestore(trace, logger, backupPath, volumePath);
            movedAsideTarget = moveExistingFileVolumeAside(trace, logger, volumePath);
            temporaryVolumePath = createTemporaryVolumePath(volumePath, temporaryFilePrefix, targetFormat);
            Files.deleteIfExists(temporaryVolumePath);
            final QemuImgFile temporaryVolumeFile = new QemuImgFile(temporaryVolumePath.toString(), targetFormat);
            logger.info("{} phase=[TEMP_TARGET_CREATED], source=[{}], target=[{}], temporaryTarget=[{}], sourceFormat=[{}], targetFormat=[{}]",
                    trace, srcBackupFile.getFileName(), volumePath, temporaryVolumeFile.getFileName(), srcBackupFile.getFormat(), targetFormat);
            restoreFileVolumeData(trace, logger, backupPath, temporaryVolumeFile.getFileName(), srcBackupFile.getFormat(), targetFormat, timeout);
            Files.move(temporaryVolumePath, Paths.get(volumePath), StandardCopyOption.REPLACE_EXISTING);
            logger.info("{} phase=[TEMP_TARGET_PROMOTED], target=[{}], temporaryTarget=[{}]",
                    trace, volumePath, temporaryVolumePath);
            deleteMovedAsideFileVolume(trace, logger, movedAsideTarget);
            return true;
        } catch (final QemuImgException | LibvirtException | IOException e) {
            final String srcFilename = srcBackupFile != null ? srcBackupFile.getFileName() : null;
            logger.error("{} phase=[FILE_RESTORE_FAILED], source=[{}], target=[{}], error=[{}]",
                    trace, srcFilename, volumePath, e.getMessage());
            restoreMovedAsideFileVolume(trace, logger, volumePath, movedAsideTarget);
            return false;
        } finally {
            deleteTemporaryVolume(trace, logger, temporaryVolumePath);
        }
    }

    static String getRestorableFileBackupPath(final List<String> backupPaths) {
        for (int index = backupPaths.size() - 1; index >= 0; index--) {
            final String backupPath = backupPaths.get(index);
            if (StringUtils.isNotBlank(backupPath) && Files.exists(Paths.get(backupPath))) {
                return backupPath;
            }
        }
        return backupPaths.get(backupPaths.size() - 1);
    }

    static long estimateRequiredBytesForFileRestore(final String backupPath) throws QemuImgException, LibvirtException {
        try {
            final QemuImg qemu = new QemuImg(0);
            final Map<String, String> info = qemu.info(new QemuImgFile(backupPath, getBackupFileFormat(backupPath)));
            final String virtualSize = info.get(QemuImg.VIRTUAL_SIZE);
            if (StringUtils.isNotBlank(virtualSize)) {
                return Long.parseLong(virtualSize);
            }
        } catch (final NumberFormatException e) {
            return estimateFileSize(backupPath);
        }
        return estimateFileSize(backupPath);
    }

    static void validatePrimaryStorageSpaceForFileRestorePlan(final String trace, final Logger logger, final String providerName,
            final List<String> volumePaths, final List<List<String>> backupPathsByVolume, final List<PrimaryDataStoreTO> restoreVolumePools) {
        final Map<Path, Long> persistentGrowthBytesByDirectory = new HashMap<>();
        final Map<Path, Long> peakRestoreBytesByDirectory = new HashMap<>();
        final Map<Path, Integer> volumeCountByDirectory = new HashMap<>();
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
                final long backupRequiredBytes = estimateRequiredBytesForFileRestore(getRestorableFileBackupPath(backupPaths));
                final Path targetPath = Paths.get(volumePath);
                final long persistentGrowthBeforeVolume = persistentGrowthBytesByDirectory.getOrDefault(targetDirectory, 0L);
                peakRestoreBytesByDirectory.merge(targetDirectory, persistentGrowthBeforeVolume + backupRequiredBytes, Math::max);
                volumeCountByDirectory.merge(targetDirectory, 1, Integer::sum);
                if (Files.exists(targetPath)) {
                    final long existingBytes = estimateRequiredBytesForFileRestore(volumePath);
                    persistentGrowthBytesByDirectory.merge(targetDirectory, Math.max(backupRequiredBytes - existingBytes, 0L), Long::sum);
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
            final long peakRestoreBytes = peakRestoreBytesByDirectory.getOrDefault(targetDirectory, 0L);
            final long requiredBytes = Math.max(persistentGrowthBytes, peakRestoreBytes);
            final long bufferBytes = Math.max(RESTORE_PRIMARY_SPACE_BUFFER_BYTES, requiredBytes / 5L);
            final long minimumAvailableBytes = requiredBytes + bufferBytes;
            final long availableBytes;
            try {
                availableBytes = Files.getFileStore(targetDirectory).getUsableSpace();
            } catch (final IOException e) {
                throw new CloudRuntimeException(String.format("Failed to query primary storage space under [%s]: %s", targetDirectory, e.getMessage()), e);
            }
            logger.info("{} phase=[PRIMARY_SPACE_PLAN_CHECK], targetDirectory=[{}], persistentGrowthBytes=[{}], transientBytes=[{}], requiredBytes=[{}], bufferBytes=[{}], minimumAvailableBytes=[{}], availableBytes=[{}], volumeCount=[{}]",
                    trace, targetDirectory, persistentGrowthBytes, peakRestoreBytes, requiredBytes, bufferBytes, minimumAvailableBytes, availableBytes,
                    volumeCountByDirectory.getOrDefault(targetDirectory, 0));
            if (availableBytes < minimumAvailableBytes) {
                throw new CloudRuntimeException(String.format(
                        "Insufficient primary storage space for %s restore under [%s]. Required at least [%d] bytes including buffer for the restore plan, but only [%d] bytes are available.",
                        providerName, targetDirectory, minimumAvailableBytes, availableBytes));
            }
        }
    }

    static long estimateRequiredBytesForVolumeRestore(final String volumePath, final List<String> backupPaths) {
        try {
            if (Files.exists(Paths.get(volumePath))) {
                return estimateRequiredBytesForFileRestore(volumePath);
            }
            return estimateRequiredBytesForFileRestore(getRestorableFileBackupPath(backupPaths));
        } catch (final QemuImgException | LibvirtException e) {
            throw new CloudRuntimeException(String.format("Failed to estimate primary storage requirement for target [%s]: %s",
                    volumePath, e.getMessage()), e);
        }
    }

    private static void validateResolvedChainPaths(final List<String> resolvedPaths, final String volumePath) {
        if (resolvedPaths == null || resolvedPaths.isEmpty()) {
            throw new CloudRuntimeException(String.format("No resolved backup chain paths found for volume [%s]", volumePath));
        }
    }

    private static long estimateFileSize(final String backupPath) throws QemuImgException {
        try {
            return Files.size(Paths.get(backupPath));
        } catch (final IOException e) {
            throw new QemuImgException(String.format("Failed to estimate restore size for backup [%s]: %s", backupPath, e.getMessage()));
        }
    }

    private static void restoreFileVolumeData(final String trace, final Logger logger, final String backupPath, final String volumePath,
            final QemuImg.PhysicalDiskFormat backupFormat, final QemuImg.PhysicalDiskFormat volumeFormat, final int timeout)
            throws QemuImgException, LibvirtException {
        if (backupFormat == QemuImg.PhysicalDiskFormat.QCOW2 && volumeFormat == QemuImg.PhysicalDiskFormat.QCOW2 && !hasBackingChain(backupPath)) {
            rsyncQcow2BackupFile(trace, logger, backupPath, volumePath, timeout);
            return;
        }
        convertFileVolumeWithQemuImg(trace, logger, backupPath, volumePath, backupFormat, volumeFormat, timeout);
    }

    private static boolean hasBackingChain(final String qcow2Path) {
        return runCommandWithOutput(String.format(QEMU_IMG_HAS_BACKING_COMMAND, quote(qcow2Path)), 0).first() == 0;
    }

    private static void rsyncQcow2BackupFile(final String trace, final Logger logger, final String backupPath,
            final String volumePath, final int timeout) throws QemuImgException {
        final String rsyncCommand = String.format("rsync -az %s %s", quote(backupPath), quote(volumePath));
        final Pair<Integer, String> result = runCommandWithOutput(rsyncCommand, timeout * 1000);
        final String output = formatTraceOutput(result.second());
        if (result.first() == 0) {
            logger.info("{} phase=[RSYNC], source=[{}], target=[{}], command=[rsync-qcow2]",
                    trace, backupPath, volumePath);
            return;
        }
        logger.warn("{} phase=[RSYNC], source=[{}], target=[{}], command=[rsync-qcow2], exitCode=[{}], output=[{}]",
                trace, backupPath, volumePath, result.first(), output);
        throw new QemuImgException(String.format("rsync qcow2 backup failed with exitCode [%s], output [%s]", result.first(), output));
    }

    private static void convertFileVolumeWithQemuImg(final String trace, final Logger logger, final String backupPath,
            final String volumePath, final QemuImg.PhysicalDiskFormat backupFormat, final QemuImg.PhysicalDiskFormat volumeFormat,
            final int timeout) throws QemuImgException {
        final String convertCommand = String.format("qemu-img convert -p -S 0 -f %s -O %s %s %s",
                backupFormat.toString().toLowerCase(Locale.ROOT), volumeFormat.toString().toLowerCase(Locale.ROOT),
                quote(backupPath), quote(volumePath));
        final Pair<Integer, String> result = runCommandWithOutput(convertCommand, timeout * 1000);
        final String output = formatTraceOutput(result.second());
        if (result.first() == 0) {
            logger.info("{} phase=[CONVERT], source=[{}], target=[{}], command=[qemu-img-convert-nosparse]",
                    trace, backupPath, volumePath);
            return;
        }
        logger.warn("{} phase=[CONVERT], source=[{}], target=[{}], command=[qemu-img-convert-nosparse], exitCode=[{}], output=[{}]",
                trace, backupPath, volumePath, result.first(), output);
        throw new QemuImgException(String.format("qemu-img convert failed with exitCode [%s], output [%s]", result.first(), output));
    }

    private static void validatePrimaryStorageSpaceForFileRestore(final String trace, final Logger logger,
            final String backupPath, final String volumePath) throws IOException, QemuImgException, LibvirtException {
        final Path targetDirectory = getTargetDirectory(volumePath);
        final long requiredBytes = estimateRequiredBytesForFileRestore(backupPath);
        final long bufferBytes = Math.max(RESTORE_PRIMARY_SPACE_BUFFER_BYTES, requiredBytes / 5L);
        final long minimumAvailableBytes = requiredBytes + bufferBytes;
        final long availableBytes = Files.getFileStore(targetDirectory).getUsableSpace();
        logger.info("{} phase=[PRIMARY_SPACE_CHECK], source=[{}], target=[{}], targetDirectory=[{}], requiredBytes=[{}], bufferBytes=[{}], minimumAvailableBytes=[{}], availableBytes=[{}]",
                trace, backupPath, volumePath, targetDirectory, requiredBytes, bufferBytes, minimumAvailableBytes, availableBytes);
        if (availableBytes < minimumAvailableBytes) {
            throw new CloudRuntimeException(String.format(
                    "Insufficient primary storage space for restore target [%s]. Required at least [%d] bytes including buffer, but only [%d] bytes are available under [%s].",
                    volumePath, minimumAvailableBytes, availableBytes, targetDirectory));
        }
    }

    private static Path getTargetDirectory(final String volumePath) {
        final Path targetPath = Paths.get(volumePath).toAbsolutePath();
        final Path targetDirectory = targetPath.getParent();
        return targetDirectory != null ? targetDirectory : Paths.get(".").toAbsolutePath();
    }

    private static QemuImg.PhysicalDiskFormat getBackupFileFormat(final String backupPath) {
        if (backupPath.endsWith(".raw")) {
            return QemuImg.PhysicalDiskFormat.RAW;
        }
        return QemuImg.PhysicalDiskFormat.QCOW2;
    }

    private static QemuImg.PhysicalDiskFormat getFileVolumeFormat(final Logger logger, final String volumePath) {
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

    private static Path createTemporaryVolumePath(final String volumePath, final String prefix,
            final QemuImg.PhysicalDiskFormat targetFormat) throws IOException {
        final Path targetPath = Paths.get(volumePath).toAbsolutePath();
        final Path targetDirectory = targetPath.getParent();
        final String suffix = "." + targetFormat.toString().toLowerCase(Locale.ROOT);
        return targetDirectory != null ? Files.createTempFile(targetDirectory, prefix, suffix) : Files.createTempFile(prefix, suffix);
    }

    private static Path moveExistingFileVolumeAside(final String trace, final Logger logger, final String volumePath) throws IOException {
        final Path targetPath = Paths.get(volumePath);
        if (!Files.exists(targetPath)) {
            return null;
        }

        final Path movedAsidePath = targetPath.resolveSibling(targetPath.getFileName() + ".csrestore." + System.currentTimeMillis() + ".bak");
        Files.move(targetPath, movedAsidePath);
        logger.info("{} phase=[TARGET_MOVED_ASIDE], target=[{}], movedAside=[{}]",
                trace, volumePath, movedAsidePath);
        return movedAsidePath;
    }

    private static void deleteMovedAsideFileVolume(final String trace, final Logger logger, final Path movedAsideTarget) {
        if (movedAsideTarget == null) {
            return;
        }
        try {
            Files.deleteIfExists(movedAsideTarget);
            logger.info("{} phase=[TARGET_MOVED_ASIDE_DELETED], movedAside=[{}]",
                    trace, movedAsideTarget);
        } catch (final IOException e) {
            logger.warn("{} phase=[TARGET_MOVED_ASIDE_DELETE_FAILED], movedAside=[{}], error=[{}]",
                    trace, movedAsideTarget, e.getMessage());
        }
    }

    private static void restoreMovedAsideFileVolume(final String trace, final Logger logger, final String volumePath,
            final Path movedAsideTarget) {
        if (movedAsideTarget == null || !Files.exists(movedAsideTarget)) {
            return;
        }

        final Path targetPath = Paths.get(volumePath);
        try {
            Files.deleteIfExists(targetPath);
            Files.move(movedAsideTarget, targetPath);
            logger.info("{} phase=[TARGET_MOVED_ASIDE_RESTORED], target=[{}], movedAside=[{}]",
                    trace, volumePath, movedAsideTarget);
        } catch (final IOException e) {
            logger.error("{} phase=[TARGET_MOVED_ASIDE_RESTORE_FAILED], target=[{}], movedAside=[{}], error=[{}]",
                    trace, volumePath, movedAsideTarget, e.getMessage());
        }
    }

    private static void deleteTemporaryVolume(final String trace, final Logger logger, final Path temporaryVolumePath) {
        if (temporaryVolumePath == null) {
            return;
        }
        try {
            Files.deleteIfExists(temporaryVolumePath);
        } catch (final IOException e) {
            logger.warn("{} phase=[TEMP_TARGET_DELETE_FAILED], temporaryTarget=[{}], error=[{}]",
                    trace, temporaryVolumePath, e.getMessage());
        }
    }

    private static Pair<Integer, String> runCommandWithOutput(final String command, final int timeout) {
        final String wrappedCommand = String.format("set +e; %s 2>&1; rc=$?; echo __CMD_EXIT__=$rc", command);
        final String output = Script.runSimpleBashScriptWithFullResult(wrappedCommand, timeout);
        if (output == null) {
            return new Pair<>(-1, "");
        }

        final List<String> lines = new ArrayList<>(Arrays.asList(output.split("\n")));
        int exitCode = -1;
        if (!lines.isEmpty()) {
            final String lastLine = lines.get(lines.size() - 1).trim();
            if (lastLine.startsWith("__CMD_EXIT__=")) {
                exitCode = Integer.parseInt(lastLine.substring("__CMD_EXIT__=".length()));
                lines.remove(lines.size() - 1);
            }
        }
        return new Pair<>(exitCode, String.join("\n", lines));
    }

    private static String quote(final String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static String formatTraceOutput(final String output) {
        if (StringUtils.isBlank(output)) {
            return "";
        }
        return output.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
