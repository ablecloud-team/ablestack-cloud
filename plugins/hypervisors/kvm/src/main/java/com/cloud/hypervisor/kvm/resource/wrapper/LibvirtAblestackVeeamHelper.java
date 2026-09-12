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

import com.cloud.hypervisor.kvm.resource.LibvirtConnection;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.hypervisor.kvm.storage.KVMPhysicalDisk;
import com.cloud.hypervisor.kvm.storage.KVMStoragePool;
import com.cloud.hypervisor.kvm.storage.KVMStoragePoolManager;
import com.cloud.storage.Storage;
import com.cloud.utils.Pair;
import com.cloud.utils.script.Script;
import org.apache.cloudstack.backup.AblestackVeeamImportSeedCommand;
import org.apache.cloudstack.backup.AblestackVeeamTakeBackupCommand;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;
import org.apache.commons.collections.CollectionUtils;
import org.apache.cloudstack.utils.security.ParserUtils;
import org.libvirt.Connect;
import org.libvirt.Domain;
import org.libvirt.DomainInfo.DomainState;
import org.libvirt.LibvirtException;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import org.apache.commons.lang3.StringUtils;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

class LibvirtAblestackVeeamHelper {
    protected Logger LOGGER = LogManager.getLogger(LibvirtAblestackVeeamHelper.class);
    static final Integer EXIT_CLEANUP_FAILED = 20;
    private static final String BACKUP_TRACE = "[ABLESTACK_VEEAM_BACKUP_TRACE]";
    private static final int BACKUP_JOB_POLL_INTERVAL_MS = 10000;
    private static final DateTimeFormatter SCRIPT_LOG_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss>");

    enum BackupExecutionMode {
        RUNNING("backup-running"),
        STOPPED("backup-stopped"),
        RBD("backup-rbd");

        private final String scriptOperation;

        BackupExecutionMode(String scriptOperation) {
            this.scriptOperation = scriptOperation;
        }

        String getScriptOperation() {
            return scriptOperation;
        }
    }

    private final LibvirtComputingResource resource;

    LibvirtAblestackVeeamHelper(LibvirtComputingResource resource) {
        this.resource = resource;
    }

    Pair<Integer, String> executeImportSeed(AblestackVeeamImportSeedCommand command, List<String> diskPaths) {
        if (CollectionUtils.isEmpty(command.getStagingDiskPaths())) {
            return new Pair<>(1, "Staging disk paths are required for Veeam seed import");
        }
        String[] scriptCommand = buildImportSeedScriptCommand(command, diskPaths);
        List<String[]> commands = new ArrayList<>();
        commands.add(scriptCommand);
        final int timeout = command.getWait() > 0 ? command.getWait() * 1000 : resource.getCmdsTimeout();
        return Script.executePipedCommands(commands, timeout);
    }

    long parseBackupSize(String output, List<String> diskPaths) {
        if (output == null || output.isBlank()) {
            return 0L;
        }
        List<Long> parsedSizes = Arrays.stream(output.trim().split("\n"))
                .map(String::trim)
                .filter(value -> value != null && !value.isEmpty() && value.chars().allMatch(Character::isDigit))
                .map(Long::parseLong)
                .collect(Collectors.toList());
        if (parsedSizes.isEmpty()) {
            LOGGER.warn("Unable to parse Veeam backup size from output=[{}]", output);
            return 0L;
        }
        if (CollectionUtils.isEmpty(diskPaths)) {
            return parsedSizes.get(parsedSizes.size() - 1);
        }
        return parsedSizes.stream().mapToLong(Long::longValue).sum();
    }

    Pair<Integer, String> executeBackup(AblestackVeeamTakeBackupCommand command) {
        List<String> diskPaths = resolveDiskPaths(command.getVolumePools(), command.getVolumePaths());
        BackupExecutionMode executionMode = determineExecutionMode(command.getVmName(), command.getVolumePools());
        LOGGER.info("{} phase=[HELPER_ENTER], mode=[{}], vm=[{}], backupType=[{}], diskPaths=[{}]",
                BACKUP_TRACE, executionMode, command.getVmName(), command.getBackupType(), diskPaths);
        if (BackupExecutionMode.STOPPED.equals(executionMode)) {
            // qcow2 incremental needs live dirty-bitmaps on the real domain (backup-running).
            // Never fall into the dummy-VM STOPPED path for INCREMENTAL — that path does not
            // preserve the live bitmap chain and must not interfere with a Running guest.
            if (isIncremental(command)) {
                String msg = String.format(
                        "Veeam qcow2 INCREMENTAL requires VM [%s] to be Running in libvirt (backup-running). "
                                + "Start the VM and retry; STOPPED/dummy backup is FULL-only.",
                        command.getVmName());
                LOGGER.error(msg);
                return new Pair<>(1, msg);
            }
            return executeStoppedVmBackup(command, diskPaths);
        }

        List<String[]> commands = new ArrayList<>();
        Path parentCheckpointWorkspace = null;
        try {
            parentCheckpointWorkspace = ensureParentCheckpointMaterialized(command);
            String[] scriptCommand = buildBackupScriptCommand(command, diskPaths, executionMode);
            LOGGER.debug("Executing Veeam script command=[{}]", String.join(" ", scriptCommand));
            commands.add(scriptCommand);
            final int timeout = command.getWait() > 0 ? command.getWait() * 1000 : resource.getCmdsTimeout();
            return Script.executePipedCommands(commands, timeout);
        } finally {
            cleanupParentCheckpointWorkspace(parentCheckpointWorkspace);
        }
    }

    long calculateBackupSize(AblestackVeeamTakeBackupCommand command) {
        final Path backupPath = Path.of(command.getBackupPath());
        final List<String> backupFiles = command.getBackupFiles();

        if (backupFiles != null && !backupFiles.isEmpty()) {
            long totalSize = 0L;
            for (final String backupFile : backupFiles) {
                if (StringUtils.isBlank(backupFile)) {
                    continue;
                }
                final Path backupFilePath = backupPath.resolve(backupFile);
                try {
                    if (Files.isRegularFile(backupFilePath)) {
                        totalSize += Files.size(backupFilePath);
                    }
                } catch (IOException e) {
                    LOGGER.warn("Failed to read Veeam backup file size for [{}]", backupFilePath, e);
                }
            }
            return totalSize;
        }

        try (var walk = Files.walk(backupPath)) {
            return walk.filter(Files::isRegularFile).mapToLong(path -> {
                try {
                    return Files.size(path);
                } catch (IOException e) {
                    LOGGER.warn("Failed to read Veeam backup file size for [{}]", path, e);
                    return 0L;
                }
            }).sum();
        } catch (IOException e) {
            LOGGER.warn("Failed to calculate Veeam backup size under [{}]", backupPath, e);
            return 0L;
        }
    }

    List<String> resolveDiskPaths(List<PrimaryDataStoreTO> volumePools, List<String> volumePaths) {
        List<String> diskPaths = new ArrayList<>();
        if (volumePaths == null) {
            return diskPaths;
        }

        KVMStoragePoolManager storagePoolMgr = resource.getStoragePoolMgr();
        for (int idx = 0; idx < volumePaths.size(); idx++) {
            PrimaryDataStoreTO volumePool = volumePools.get(idx);
            String volumePath = volumePaths.get(idx);
            if (volumePool.getPoolType() != Storage.StoragePoolType.RBD) {
                diskPaths.add(volumePath);
                continue;
            }

            KVMStoragePool volumeStoragePool = storagePoolMgr.getStoragePool(volumePool.getPoolType(), volumePool.getUuid());
            diskPaths.add(KVMPhysicalDisk.RBDStringBuilder(volumeStoragePool, volumePath));
        }
        return diskPaths;
    }

    private String[] buildImportSeedScriptCommand(AblestackVeeamImportSeedCommand command, List<String> diskPaths) {
        return new String[] {
                resource.getAbleVeeamPath(),
                "-o", "import-seed",
                "-v", command.getVmName(),
                "-p", command.getBackupPath(),
                "-c", Objects.nonNull(command.getCheckpointName()) ? command.getCheckpointName() : "",
                "-f", CollectionUtils.isEmpty(command.getBackupFiles()) ? "" : String.join(",", command.getBackupFiles()),
                "-d", diskPaths.isEmpty() ? "" : String.join(",", diskPaths),
                "--staging-disks", String.join(",", command.getStagingDiskPaths()),
                "--source-format", Objects.nonNull(command.getSourceFormat()) ? command.getSourceFormat() : "vmdk",
                "--veeam-restore-point", Objects.nonNull(command.getVeeamRestorePointId()) ? command.getVeeamRestorePointId() : "",
                "--bootstrap-checkpoint", command.getBootstrapCheckpoint() != null && command.getBootstrapCheckpoint() ? "true" : "false"
        };
    }

    private String[] buildBackupScriptCommand(AblestackVeeamTakeBackupCommand command, List<String> diskPaths, BackupExecutionMode executionMode) {
        return new String[] {
                resource.getAbleVeeamPath(),
                "-o", executionMode.getScriptOperation(),
                "-v", command.getVmName(),
                "-p", command.getBackupPath(),
                "-b", Objects.nonNull(command.getBackupType()) ? command.getBackupType() : "",
                "-c", Objects.nonNull(command.getCheckpointName()) ? command.getCheckpointName() : "",
                "-r", Objects.nonNull(command.getParentBackupPath()) ? command.getParentBackupPath() : "",
                "-i", Objects.nonNull(command.getParentCheckpointName()) ? command.getParentCheckpointName() : "",
                "-j", Objects.nonNull(command.getParentCheckpointPath()) ? command.getParentCheckpointPath() : "",
                "-f", command.getBackupFiles() == null || command.getBackupFiles().isEmpty() ? "" : String.join(",", command.getBackupFiles()),
                "-q", command.getQuiesce() != null && command.getQuiesce() ? "true" : "false",
                "-d", diskPaths.isEmpty() ? "" : String.join(",", diskPaths)
        };
    }

    private BackupExecutionMode determineExecutionMode(String vmName, List<PrimaryDataStoreTO> volumePools) {
        if (volumePools != null && volumePools.stream().anyMatch(pool -> pool != null && pool.getPoolType() == Storage.StoragePoolType.RBD)) {
            return BackupExecutionMode.RBD;
        }
        return isVmRunning(vmName) ? BackupExecutionMode.RUNNING : BackupExecutionMode.STOPPED;
    }

    private boolean isVmRunning(String vmName) {
        try {
            Connect conn = LibvirtConnection.getConnectionByVmName(vmName);
            Domain domain = resource.getDomain(conn, vmName);
            return domain != null && DomainState.VIR_DOMAIN_RUNNING.equals(domain.getInfo().state);
        } catch (LibvirtException e) {
            return false;
        }
    }

    private Pair<Integer, String> executeStoppedVmBackup(AblestackVeeamTakeBackupCommand command, List<String> diskPaths) {
        String dummyVmName = String.format("DUMMY-VM-%s", command.getCheckpointName().replace('.', '-'));
        Path dest = Path.of(command.getBackupPath());
        Path parentCheckpointWorkspace = null;
        Connect conn = null;
        try {
            LOGGER.info("Starting stopped VM Veeam backup for vm=[{}], dummyVm=[{}], backupType=[{}]",
                    command.getVmName(), dummyVmName, command.getBackupType());
            validateStoppedBackupDiskPaths(diskPaths);
            if (isIncremental(command)) {
                resource.validateLibvirtAndQemuVersionForIncrementalSnapshots();
            }
            Files.createDirectories(dest.resolve("checkpoints"));

            conn = LibvirtConnection.getConnection();
            String dummyVmXml = buildDummyVmXml(dummyVmName, diskPaths);
            resource.startVM(conn, dummyVmName, dummyVmXml, Domain.CreateFlags.PAUSED);

            parentCheckpointWorkspace = ensureParentCheckpointMaterialized(command);
            if (isIncremental(command) && command.getParentCheckpointPath() != null && !command.getParentCheckpointPath().isEmpty()) {
                redefineCheckpointIfNeeded(dummyVmName, Path.of(command.getParentCheckpointPath()));
            }

            List<String> diskLabels = getDiskLabels(conn, dummyVmName);
            Path backupXml = writeBackupXml(dest, command, diskLabels);
            Path checkpointXml = writeCheckpointXml(dest, command, diskLabels);

            String backupBeginCommand = String.format("virsh -c qemu:///system backup-begin --domain %s --backupxml %s --checkpointxml %s",
                    shellQuote(dummyVmName), shellQuote(backupXml.toString()), shellQuote(checkpointXml.toString()));
            LOGGER.debug("Starting stopped VM Veeam backup-begin command=[{}]", backupBeginCommand);
            Pair<Integer, String> backupBeginResult = runCommandWithOutput(backupBeginCommand);
            if (backupBeginResult.first() != 0) {
                String failureDetails = formatScriptStyleLog(String.format(
                        "Failed to start stopped VM Veeam backup for dummy domain [%s]: %s",
                        dummyVmName, sanitizeCommandOutput(backupBeginResult.second())));
                LOGGER.error(failureDetails);
                return new Pair<>(backupBeginResult.first(), failureDetails);
            }

            try {
                waitForBackup(dummyVmName);
            } catch (IOException e) {
                cancelBackupJob(dummyVmName);
                throw e;
            }

            dumpCheckpointXml(dummyVmName, command.getCheckpointName(), dest);
            Files.deleteIfExists(backupXml);
            Files.deleteIfExists(checkpointXml);
            Script.runSimpleBashScriptForExitValue("sync", resource.getCmdsTimeout(), false);
            LOGGER.info("Completed stopped VM Veeam backup for vm=[{}], dummyVm=[{}]", command.getVmName(), dummyVmName);
            return new Pair<>(0, "success");
        } catch (Exception e) {
            LOGGER.error("Stopped VM Veeam backup failed for vm=[{}], dummyVm=[{}] due to: {}",
                    command.getVmName(), dummyVmName, e.getMessage(), e);
            return new Pair<>(1, e.getMessage());
        } finally {
            cleanupParentCheckpointWorkspace(parentCheckpointWorkspace);
            cleanupDummyVm(dummyVmName);
        }
    }

    private Path ensureParentCheckpointMaterialized(AblestackVeeamTakeBackupCommand command) {
        if (!isIncremental(command)) {
            return null;
        }
        try {
            Path workspace = Path.of(command.getBackupPath()).resolve("checkpoints").resolve("parent-chain");
            Files.createDirectories(workspace);
            if (command.getParentCheckpointXmlChain() != null) {
                for (var entry : command.getParentCheckpointXmlChain().entrySet()) {
                    materializeCheckpointXml(workspace, entry.getKey(), entry.getValue());
                }
            }
            Path parentCheckpointPath = materializeCheckpointXml(workspace, command.getParentCheckpointPath(), command.getParentCheckpointXml());
            if (parentCheckpointPath != null) {
                command.setParentCheckpointPath(parentCheckpointPath.toString());
            }
            return workspace;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to materialize parent checkpoint XML chain for VM " + command.getVmName(), e);
        }
    }

    private Path materializeCheckpointXml(Path workspace, String checkpointPathValue, String checkpointXml) throws IOException {
        if (StringUtils.isBlank(checkpointPathValue) || StringUtils.isBlank(checkpointXml)) {
            return null;
        }
        Path sourceCheckpointPath = Path.of(checkpointPathValue);
        Path checkpointPath = workspace.resolve(sourceCheckpointPath.getFileName().toString());
        if (Files.exists(checkpointPath)) {
            return checkpointPath;
        }
        Files.writeString(checkpointPath, checkpointXml, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        LOGGER.debug("Materialized parent checkpoint XML at [{}] from source [{}]", checkpointPath, checkpointPathValue);
        return checkpointPath;
    }

    private void cleanupParentCheckpointWorkspace(Path workspace) {
        if (workspace == null || !Files.exists(workspace)) {
            return;
        }
        try (var walk = Files.walk(workspace)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    LOGGER.warn("Failed to delete temporary parent checkpoint materialization path [{}]", path, e);
                }
            });
        } catch (IOException e) {
            LOGGER.warn("Failed to clean temporary parent checkpoint workspace [{}]", workspace, e);
        }
    }

    private String buildDummyVmXml(String vmName, List<String> diskPaths) {
        String arch = resource.getGuestCpuArch() != null ? resource.getGuestCpuArch() : "x86_64";
        String machine = resource.isGuestAarch64() ? LibvirtComputingResource.VIRT : LibvirtComputingResource.PC;
        String emulator = resource.getHypervisorPath();
        StringBuilder xml = new StringBuilder();
        xml.append("<domain type='qemu'>")
                .append("<name>").append(vmName).append("</name>")
                .append("<memory unit='MiB'>256</memory>")
                .append("<currentMemory unit='MiB'>256</currentMemory>")
                .append("<vcpu placement='static'>1</vcpu>")
                .append("<os><type arch='").append(arch).append("' machine='").append(machine).append("'>hvm</type><boot dev='hd'/></os>")
                .append("<devices><emulator>").append(emulator).append("</emulator>");
        for (int i = 0; i < diskPaths.size(); i++) {
            char letter = (char) ('a' + i);
            String diskPath = diskPaths.get(i);
            xml.append("<disk type='file' device='disk'>")
                    .append("<driver name='qemu' type='qcow2' cache='none'/>")
                    .append("<source file='").append(diskPath).append("'/>")
                    .append("<target dev='vd").append(letter).append("' bus='virtio'/></disk>");
        }
        xml.append("<console type='pty'/><graphics type='vnc' port='-1'/></devices></domain>");
        return xml.toString();
    }

    private void validateStoppedBackupDiskPaths(List<String> diskPaths) {
        if (diskPaths.stream().anyMatch(path -> path != null && path.startsWith("rbd:"))) {
            throw new IllegalArgumentException("Stopped VM dummy backup flow supports only file-backed disks. RBD backups must use the dedicated RBD backup path.");
        }
    }

    private Pair<Integer, String> runCommandWithOutput(String command) {
        String wrappedCommand = String.format("set +e; %s 2>&1; rc=$?; echo __CMD_EXIT__=$rc", command);
        String output = Script.runSimpleBashScriptWithFullResult(wrappedCommand, resource.getCmdsTimeout());
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

    private String sanitizeCommandOutput(String output) {
        if (output == null || output.isBlank()) {
            return "no detailed error returned";
        }
        return output.replace('\n', ' ').trim();
    }

    private String formatScriptStyleLog(String message) {
        return LocalDateTime.now().format(SCRIPT_LOG_TIME_FORMATTER) + " " + message;
    }

    private void redefineCheckpointIfNeeded(String vmName, Path checkpointPath) throws IOException {
        redefineCheckpointChainIfNeeded(vmName, checkpointPath, new HashSet<>());
    }

    private void redefineCheckpointChainIfNeeded(String vmName, Path checkpointPath, Set<String> visitedCheckpointNames) throws IOException {
        if (!Files.exists(checkpointPath)) {
            return;
        }
        String checkpointName = checkpointPath.getFileName().toString().replace(".xml", "");
        if (!visitedCheckpointNames.add(checkpointName)) {
            return;
        }

        String parentCheckpointName = getParentCheckpointName(checkpointPath);
        if (parentCheckpointName != null) {
            Path parentCheckpointPath = findCheckpointPath(getCheckpointSearchRoot(checkpointPath), parentCheckpointName);
            if (parentCheckpointPath == null) {
                throw new IOException(formatScriptStyleLog(String.format(
                        "Missing parent checkpoint XML for checkpoint [%s] referenced by [%s] under search root [%s]",
                        parentCheckpointName, checkpointPath, getCheckpointSearchRoot(checkpointPath))));
            }
            redefineCheckpointChainIfNeeded(vmName, parentCheckpointPath, visitedCheckpointNames);
        }

        int infoExit = Script.runSimpleBashScriptForExitValue(String.format(
                "virsh -c qemu:///system checkpoint-info --domain %s --checkpointname %s > /dev/null 2>&1",
                shellQuote(vmName), shellQuote(checkpointName)));
        if (infoExit == 0) {
            return;
        }
        String redefineCommand = String.format(
                "virsh -c qemu:///system checkpoint-create --domain %s --xmlfile %s --redefine > /dev/null 2>&1",
                shellQuote(vmName), shellQuote(checkpointPath.toString()));
        Pair<Integer, String> redefineResult = runCommandWithOutput(redefineCommand);
        if (redefineResult.first() != 0) {
            String failureDetails = formatScriptStyleLog(String.format(
                    "Failed to redefine checkpoint [%s] on domain [%s] using [%s]: %s",
                    checkpointName, vmName, checkpointPath, sanitizeCommandOutput(redefineResult.second())));
            LOGGER.error(failureDetails);
            throw new IOException(failureDetails);
        }
    }

    private String getParentCheckpointName(Path checkpointPath) throws IOException {
        try {
            Document checkpointDocument = ParserUtils.getSaferDocumentBuilderFactory().newDocumentBuilder().parse(checkpointPath.toFile());
            XPath xpath = XPathFactory.newInstance().newXPath();
            String parentName = (String) xpath.compile("/domaincheckpoint/parent/name/text()")
                    .evaluate(checkpointDocument, XPathConstants.STRING);
            if (parentName == null || parentName.isBlank()) {
                return null;
            }
            return parentName.trim();
        } catch (XPathExpressionException | SAXException | RuntimeException | javax.xml.parsers.ParserConfigurationException e) {
            throw new IOException("Failed to parse checkpoint XML " + checkpointPath, e);
        }
    }

    private Path getCheckpointSearchRoot(Path checkpointPath) {
        Path checkpointsDir = checkpointPath.getParent();
        Path backupDir = checkpointsDir != null ? checkpointsDir.getParent() : null;
        Path vmBackupRoot = backupDir != null ? backupDir.getParent() : null;
        return vmBackupRoot != null ? vmBackupRoot : checkpointPath.getParent();
    }

    private Path findCheckpointPath(Path searchRoot, String checkpointName) throws IOException {
        if (searchRoot == null || checkpointName == null || checkpointName.isBlank() || !Files.exists(searchRoot)) {
            return null;
        }

        try (var stream = Files.find(searchRoot, 5,
                (path, attrs) -> attrs.isRegularFile() && (checkpointName + ".xml").equals(path.getFileName().toString()))) {
            return stream.findFirst().orElse(null);
        }
    }

    private List<String> getDiskLabels(Connect conn, String vmName) {
        return resource.getDisks(conn, vmName).stream()
                .map(d -> d.getDiskLabel())
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private Path writeBackupXml(Path dest, AblestackVeeamTakeBackupCommand command, List<String> diskLabels) throws IOException {
        StringBuilder xml = new StringBuilder("<domainbackup mode='push'>");
        if (isIncremental(command) && command.getParentCheckpointName() != null && !command.getParentCheckpointName().isEmpty()) {
            xml.append("<incremental>").append(command.getParentCheckpointName()).append("</incremental>");
        }
        xml.append("<disks>");
        for (int i = 0; i < diskLabels.size(); i++) {
            String backupFile = getBackupFileByIndex(command, i, getLegacyBackupFileName(diskLabels, i));
            xml.append("<disk name='").append(diskLabels.get(i)).append("' backup='yes' type='file'>")
                    .append("<driver type='qcow2'/><target file='").append(dest.resolve(backupFile)).append("'/></disk>");
        }
        xml.append("</disks></domainbackup>");
        Path backupXml = dest.resolve("backup.xml");
        Files.writeString(backupXml, xml.toString(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        return backupXml;
    }

    private Path writeCheckpointXml(Path dest, AblestackVeeamTakeBackupCommand command, List<String> diskLabels) throws IOException {
        StringBuilder xml = new StringBuilder("<domaincheckpoint><name>").append(command.getCheckpointName()).append("</name><disks>");
        for (String diskLabel : diskLabels) {
            xml.append("<disk name='").append(diskLabel).append("' checkpoint='bitmap'/>");
        }
        xml.append("</disks></domaincheckpoint>");
        Path checkpointXml = dest.resolve("checkpoint.xml");
        Files.writeString(checkpointXml, xml.toString(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        return checkpointXml;
    }

    private void waitForBackup(String vmName) throws IOException {
        int timeout = resource.getCmdsTimeout();
        while (timeout > 0) {
            String result = checkBackupJob(vmName);
            if (result != null && result.contains("Completed") && result.contains("Backup")) {
                return;
            }
            if (result != null && result.contains("Failed")) {
                throw new IOException("Virsh backup job failed for dummy VM " + vmName);
            }
            timeout -= BACKUP_JOB_POLL_INTERVAL_MS;
            try {
                Thread.sleep(BACKUP_JOB_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
        }
        throw new IOException("Timed out waiting for backup job of dummy VM " + vmName);
    }

    private void cancelBackupJob(String vmName) {
        Script.runSimpleBashScriptForExitValue(String.format("virsh -c qemu:///system domjobabort --domain %s > /dev/null 2>&1", shellQuote(vmName)));
    }

    private String checkBackupJob(String vmName) {
        return Script.runSimpleBashScriptWithFullResult(
                String.format("virsh -c qemu:///system domjobinfo %s --completed --keep-completed", shellQuote(vmName)), 10);
    }

    private String getLegacyBackupFileName(List<String> diskLabels, int index) {
        String diskType = index == 0 ? "root" : "datadisk";
        String suffix = index < diskLabels.size() && diskLabels.get(index) != null ? diskLabels.get(index) : String.valueOf(index);
        return String.format("%s.%s.qcow2", diskType, suffix);
    }

    private void dumpCheckpointXml(String vmName, String checkpointName, Path dest) {
        Path checkpointDest = dest.resolve("checkpoints").resolve(checkpointName + ".xml");
        Script.runSimpleBashScriptForExitValue(String.format(
                "virsh -c qemu:///system checkpoint-dumpxml --domain %s --checkpointname %s --no-domain > %s 2>/dev/null",
                shellQuote(vmName), shellQuote(checkpointName), shellQuote(checkpointDest.toString())));
    }

    private void cleanupDummyVm(String dummyVmName) {
        Script.runSimpleBashScriptForExitValue(String.format("virsh -c qemu:///system destroy %s > /dev/null 2>&1 || true", shellQuote(dummyVmName)));
        Script.runSimpleBashScriptForExitValue(String.format(
                "virsh -c qemu:///system undefine %s --nvram > /dev/null 2>&1 || virsh -c qemu:///system undefine %s > /dev/null 2>&1 || true",
                shellQuote(dummyVmName), shellQuote(dummyVmName)));
    }

    private boolean isIncremental(AblestackVeeamTakeBackupCommand command) {
        return "INCREMENTAL".equalsIgnoreCase(command.getBackupType());
    }

    private String getBackupFileByIndex(AblestackVeeamTakeBackupCommand command, int index, String fallback) {
        List<String> backupFiles = command.getBackupFiles();
        if (backupFiles == null || index >= backupFiles.size()) {
            return fallback;
        }
        return backupFiles.get(index);
    }

    private String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}
