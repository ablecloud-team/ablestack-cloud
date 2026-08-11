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

import com.amazonaws.util.CollectionUtils;
import com.cloud.hypervisor.kvm.resource.LibvirtConnection;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.hypervisor.kvm.storage.KVMPhysicalDisk;
import com.cloud.hypervisor.kvm.storage.KVMStoragePool;
import com.cloud.hypervisor.kvm.storage.KVMStoragePoolManager;
import com.cloud.storage.Storage;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.script.Script;
import org.apache.cloudstack.backup.AblestackNasImportVeeamSeedCommand;
import org.apache.cloudstack.backup.AblestackNasTakeBackupCommand;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;
import org.apache.cloudstack.utils.security.ParserUtils;
import org.libvirt.Connect;
import org.libvirt.Domain;
import org.libvirt.DomainInfo.DomainState;
import org.libvirt.LibvirtException;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
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

class LibvirtAblestackNasBackupHelper {
    protected Logger LOGGER = LogManager.getLogger(LibvirtAblestackNasBackupHelper.class);
    static final Integer EXIT_CLEANUP_FAILED = 20;
    private static final int BACKUP_JOB_POLL_INTERVAL_MS = 10000;
    private static final int UNMOUNT_TIMEOUT_SECONDS = 60;
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

    LibvirtAblestackNasBackupHelper(LibvirtComputingResource resource) {
        this.resource = resource;
    }

    Pair<Integer, String> executeImportVeeamSeed(AblestackNasImportVeeamSeedCommand command, List<String> diskPaths) {
        if (CollectionUtils.isNullOrEmpty(command.getStagingDiskPaths())) {
            return new Pair<>(1, "Staging disk paths are required for Veeam seed import");
        }
        String[] scriptCommand = buildImportVeeamSeedScriptCommand(command, diskPaths);
        List<String[]> commands = new ArrayList<>();
        commands.add(scriptCommand);
        return Script.executePipedCommands(commands, resource.getCmdsTimeout());
    }

    Pair<Integer, String> executeBackup(AblestackNasTakeBackupCommand command) {
        LOGGER.info("LibvirtNasBackupHelper executeBackup entered for vm=[{}], backupPath=[{}], backupType=[{}]",
                command.getVmName(), command.getBackupPath(), command.getBackupType());
        List<String> diskPaths = resolveDiskPaths(command.getVolumePools(), command.getVolumePaths());
        BackupExecutionMode executionMode = determineExecutionMode(command.getVmName(), command.getVolumePools());
        LOGGER.debug("NAS backup execution mode=[{}], vm=[{}], backupType=[{}], diskPaths=[{}]",
                executionMode, command.getVmName(), command.getBackupType(), diskPaths);
        if (BackupExecutionMode.STOPPED.equals(executionMode)) {
            return executeStoppedVmBackup(command, diskPaths);
        }
        List<String[]> commands = new ArrayList<>();
        String[] scriptCommand = buildBackupScriptCommand(command, diskPaths, executionMode);
        LOGGER.debug("Executing NAS backup script command=[{}]", String.join(" ", scriptCommand));
        commands.add(scriptCommand);
        return Script.executePipedCommands(commands, resource.getCmdsTimeout());
    }

    List<String> resolveDiskPaths(List<PrimaryDataStoreTO> volumePools, List<String> volumePaths) {
        List<String> diskPaths = new ArrayList<>();
        if (Objects.isNull(volumePaths)) {
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

    long parseBackupSize(String output, List<String> diskPaths) {
        if (output == null || output.isBlank()) {
            return 0L;
        }

        List<Long> parsedSizes = Arrays.stream(output.trim().split("\n"))
                .map(String::trim)
                .filter(this::isWholeNumber)
                .map(Long::parseLong)
                .collect(Collectors.toList());

        if (parsedSizes.isEmpty()) {
            LOGGER.warn("Unable to parse NAS backup size from output=[{}]", output);
            return 0L;
        }

        if (CollectionUtils.isNullOrEmpty(diskPaths)) {
            return parsedSizes.get(parsedSizes.size() - 1);
        }

        return parsedSizes.stream().mapToLong(Long::longValue).sum();
    }

    private boolean isWholeNumber(String value) {
        return value != null && !value.isEmpty() && value.chars().allMatch(Character::isDigit);
    }

    private String[] buildImportVeeamSeedScriptCommand(AblestackNasImportVeeamSeedCommand command, List<String> diskPaths) {
        return new String[] {
                resource.getAbleNasBackupPath(),
                "-o", "import-veeam-seed",
                "-v", command.getVmName(),
                "-t", command.getBackupRepoType(),
                "-s", command.getBackupRepoAddress(),
                "-m", Objects.nonNull(command.getMountOptions()) ? command.getMountOptions() : "",
                "-p", command.getBackupPath(),
                "-c", Objects.nonNull(command.getCheckpointName()) ? command.getCheckpointName() : "",
                "-f", CollectionUtils.isNullOrEmpty(command.getBackupFiles()) ? "" : String.join(",", command.getBackupFiles()),
                "-d", diskPaths.isEmpty() ? "" : String.join(",", diskPaths),
                "--staging-disks", String.join(",", command.getStagingDiskPaths()),
                "--source-format", Objects.nonNull(command.getSourceFormat()) ? command.getSourceFormat() : "vmdk",
                "--veeam-restore-point", Objects.nonNull(command.getVeeamRestorePointId()) ? command.getVeeamRestorePointId() : "",
                "--bootstrap-checkpoint", command.getBootstrapCheckpoint() != null && command.getBootstrapCheckpoint() ? "true" : "false"
        };
    }

    private String[] buildBackupScriptCommand(AblestackNasTakeBackupCommand command, List<String> diskPaths, BackupExecutionMode executionMode) {
        return new String[] {
                resource.getAbleNasBackupPath(),
                "-o", executionMode.getScriptOperation(),
                "-v", command.getVmName(),
                "-t", command.getBackupRepoType(),
                "-s", command.getBackupRepoAddress(),
                "-m", Objects.nonNull(command.getMountOptions()) ? command.getMountOptions() : "",
                "-p", command.getBackupPath(),
                "-b", Objects.nonNull(command.getBackupType()) ? command.getBackupType() : "",
                "-c", Objects.nonNull(command.getCheckpointName()) ? command.getCheckpointName() : "",
                "-r", Objects.nonNull(command.getParentBackupPath()) ? command.getParentBackupPath() : "",
                "-i", Objects.nonNull(command.getParentCheckpointName()) ? command.getParentCheckpointName() : "",
                "-j", Objects.nonNull(command.getParentCheckpointPath()) ? command.getParentCheckpointPath() : "",
                "-q", command.getQuiesce() != null && command.getQuiesce() ? "true" : "false",
                "-f", CollectionUtils.isNullOrEmpty(command.getBackupFiles()) ? "" : String.join(",", command.getBackupFiles()),
                "-d", diskPaths.isEmpty() ? "" : String.join(",", diskPaths)
        };
    }

    private BackupExecutionMode determineExecutionMode(String vmName, List<PrimaryDataStoreTO> volumePools) {
        if (hasRbdVolumes(volumePools)) {
            return BackupExecutionMode.RBD;
        }
        return isVmRunning(vmName) ? BackupExecutionMode.RUNNING : BackupExecutionMode.STOPPED;
    }

    private boolean hasRbdVolumes(List<PrimaryDataStoreTO> volumePools) {
        if (CollectionUtils.isNullOrEmpty(volumePools)) {
            return false;
        }
        return volumePools.stream().anyMatch(pool -> pool != null && pool.getPoolType() == Storage.StoragePoolType.RBD);
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

    private Pair<Integer, String> executeStoppedVmBackup(AblestackNasTakeBackupCommand command, List<String> diskPaths) {
        Path mountPoint = null;
        Path dest = null;
        String dummyVmName = String.format("DUMMY-VM-%s", command.getCheckpointName().replace('.', '-'));
        Connect conn = null;
        try {
            LOGGER.info("Starting stopped VM NAS backup for vm=[{}], dummyVm=[{}], backupType=[{}]",
                    command.getVmName(), dummyVmName, command.getBackupType());
            validateStoppedBackupDiskPaths(diskPaths);
            if (isIncremental(command)) {
                resource.validateLibvirtAndQemuVersionForIncrementalSnapshots();
            }
            mountPoint = mountRepository(command);
            dest = mountPoint.resolve(command.getBackupPath());
            Files.createDirectories(dest.resolve("checkpoints"));

            conn = LibvirtConnection.getConnection();
            String dummyVmXml = buildDummyVmXml(dummyVmName, diskPaths, conn);
            resource.startVM(conn, dummyVmName, dummyVmXml, Domain.CreateFlags.PAUSED);

            if (isIncremental(command) && command.getParentCheckpointPath() != null && !command.getParentCheckpointPath().isEmpty()) {
                redefineCheckpointIfNeeded(dummyVmName, mountPoint.resolve(command.getParentCheckpointPath()));
            }

            List<String> diskLabels = getDiskLabels(conn, dummyVmName);
            Path backupXml = writeBackupXml(dest, command, diskLabels);
            Path checkpointXml = writeCheckpointXml(dest, command, diskLabels);

            String backupBeginCommand = String.format("virsh -c qemu:///system backup-begin --domain %s --backupxml %s --checkpointxml %s",
                    shellQuote(dummyVmName), shellQuote(backupXml.toString()), shellQuote(checkpointXml.toString()));
            LOGGER.debug("Starting stopped VM NAS backup-begin command=[{}]", backupBeginCommand);
            Pair<Integer, String> backupBeginResult = runCommandWithOutput(backupBeginCommand);
            if (backupBeginResult.first() != 0) {
                String failureDetails = formatScriptStyleLog(String.format(
                        "Failed to start stopped VM NAS backup for dummy domain [%s]: %s",
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

            if (isIncremental(command) && command.getParentBackupPath() != null && !command.getParentBackupPath().isEmpty()) {
                rebaseIncrementalChain(dest, command, diskPaths);
            }

            dumpCheckpointXml(dummyVmName, command.getCheckpointName(), dest);

            Files.deleteIfExists(backupXml);
            Files.deleteIfExists(checkpointXml);
            runCommand(String.format("sync"));
            String output = listTopLevelFileSizes(dest);
            LOGGER.info("Completed stopped VM NAS backup for vm=[{}], dummyVm=[{}]", command.getVmName(), dummyVmName);
            return new Pair<>(0, output);
        } catch (Exception e) {
            LOGGER.error("Stopped VM NAS backup failed for vm=[{}], dummyVm=[{}] due to: {}",
                    command.getVmName(), dummyVmName, e.getMessage(), e);
            if (!cleanupStoppedBackup(command, dest, mountPoint)) {
                mountPoint = null;
                return new Pair<>(EXIT_CLEANUP_FAILED, String.format("Backup cleanup failed after stopped VM NAS backup failure: %s", e.getMessage()));
            }
            mountPoint = null;
            return new Pair<>(1, e.getMessage());
        } finally {
            cleanupDummyVm(dummyVmName);
            unmountRepository(command, mountPoint);
        }
    }

    private Path mountRepository(AblestackNasTakeBackupCommand command) throws IOException {
        Path mountPoint = Files.createTempDirectory("csbackup.");
        final String mount;
        try {
            mount = LibvirtBackupRepositoryMountHelper.buildMountCommand(
                    command.getBackupRepoAddress(),
                    command.getBackupRepoType(),
                    command.getMountOptions(),
                    mountPoint.toString());
        } catch (CloudRuntimeException e) {
            throw new IOException(e.getMessage(), e);
        }
        if (Script.runSimpleBashScriptForExitValue(mount, resource.getCmdsTimeout(), false) != 0) {
            throw new IOException("Failed to mount backup repository");
        }
        return mountPoint;
    }

    private boolean cleanupStoppedBackup(AblestackNasTakeBackupCommand command, Path dest, Path mountPoint) {
        boolean success = true;
        if (dest != null) {
            try (var stream = Files.walk(dest)) {
                List<Path> paths = stream.sorted(Comparator.reverseOrder()).collect(Collectors.toList());
                for (Path path : paths) {
                    Files.deleteIfExists(path);
                }
            } catch (IOException e) {
                LOGGER.warn("Failed to cleanup stopped VM NAS backup path [{}]: {}", dest, e.getMessage(), e);
                success = false;
            }
        }
        return unmountRepository(command, mountPoint) && success;
    }

    private boolean unmountRepository(AblestackNasTakeBackupCommand command, Path mountPoint) {
        if (mountPoint == null) {
            return true;
        }
        boolean success = true;
        int unmountExit = Script.runSimpleBashScriptForExitValue(String.format("timeout %d umount %s",
                UNMOUNT_TIMEOUT_SECONDS, shellQuote(mountPoint.toString())));
        if (unmountExit != 0) {
            LOGGER.warn("Failed to unmount stopped VM NAS backup repository [{}] or operation timed out", mountPoint);
            success = false;
        }
        try {
            Files.deleteIfExists(mountPoint);
        } catch (IOException e) {
            LOGGER.warn("Failed to remove stopped VM NAS backup mount point [{}]: {}", mountPoint, e.getMessage(), e);
            success = false;
        }
        return success;
    }

    private String buildDummyVmXml(String vmName, List<String> diskPaths, Connect conn) throws LibvirtException {
        String arch = resource.getGuestCpuArch() != null ? resource.getGuestCpuArch() : "x86_64";
        String machine = resource.isGuestAarch64() ? LibvirtComputingResource.VIRT : LibvirtComputingResource.PC;
        String emulator = resource.getHypervisorPath();
        StringBuilder xml = new StringBuilder();
        xml.append("<domain type='qemu'>")
                .append("<name>").append(vmName).append("</name>")
                .append("<memory unit='MiB'>256</memory>")
                .append("<currentMemory unit='MiB'>256</currentMemory>")
                .append("<vcpu>1</vcpu>")
                .append("<os><type arch='").append(arch).append("' machine='").append(machine)
                .append("'>hvm</type><boot dev='hd'/></os>")
                .append("<devices><emulator>").append(emulator).append("</emulator>");
        for (int i = 0; i < diskPaths.size(); i++) {
            char letter = (char) ('a' + i);
            xml.append("<disk type='file' device='disk'>")
                    .append("<driver name='qemu' type='qcow2' cache='none'/>")
                    .append("<source file='").append(diskPaths.get(i)).append("'/>")
                    .append("<target dev='vd").append(letter).append("' bus='virtio'/>")
                    .append("</disk>");
        }
        xml.append("<graphics type='vnc' port='-1'/>")
                .append("</devices></domain>");
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
                .map(disk -> disk.getDiskLabel())
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private Path writeBackupXml(Path dest, AblestackNasTakeBackupCommand command, List<String> diskLabels) throws IOException {
        StringBuilder xml = new StringBuilder("<domainbackup mode='push'>");
        if (isIncremental(command) && command.getParentCheckpointName() != null && !command.getParentCheckpointName().isEmpty()) {
            xml.append("<incremental>").append(command.getParentCheckpointName()).append("</incremental>");
        }
        xml.append("<disks>");
        for (int i = 0; i < diskLabels.size(); i++) {
            String backupFile = getBackupFileByIndex(command, i, String.format("disk-%d.qcow2", i));
            xml.append("<disk name='").append(diskLabels.get(i)).append("' backup='yes' type='file'>")
                    .append("<target file='").append(dest.resolve(backupFile)).append("' />")
                    .append("<driver type='qcow2'/></disk>");
        }
        xml.append("</disks></domainbackup>");
        Path backupXml = dest.resolve("backup.xml");
        Files.writeString(backupXml, xml.toString(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        return backupXml;
    }

    private Path writeCheckpointXml(Path dest, AblestackNasTakeBackupCommand command, List<String> diskLabels) throws IOException {
        StringBuilder xml = new StringBuilder("<domaincheckpoint><name>")
                .append(command.getCheckpointName())
                .append("</name><disks>");
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

    private void rebaseIncrementalChain(Path dest, AblestackNasTakeBackupCommand command, List<String> diskPaths) throws IOException {
        for (int i = 0; i < diskPaths.size(); i++) {
            String backupFile = getBackupFileByIndex(command, i, String.format("disk-%d.qcow2", i));
            Path output = dest.resolve(backupFile);
            String parent = "../" + Path.of(command.getParentBackupPath()).getFileName() + "/" + backupFile;
            int exit = Script.runSimpleBashScriptForExitValue(String.format(
                    "qemu-img rebase -u -F qcow2 -b %s %s",
                    shellQuote(parent), shellQuote(output.toString())), resource.getCmdsTimeout(), false);
            if (exit != 0) {
                throw new IOException("qemu-img rebase failed for " + output + " with parent " + parent);
            }
        }
    }

    private void dumpCheckpointXml(String vmName, String checkpointName, Path dest) {
        Path checkpointDest = dest.resolve("checkpoints").resolve(checkpointName + ".xml");
        Script.runSimpleBashScriptForExitValue(String.format(
                "virsh -c qemu:///system checkpoint-dumpxml --domain %s --checkpointname %s --no-domain > %s 2>/dev/null",
                shellQuote(vmName), shellQuote(checkpointName), shellQuote(checkpointDest.toString())));
    }

    private String listTopLevelFileSizes(Path dest) throws IOException {
        try (var stream = Files.list(dest)) {
            return stream.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .map(path -> {
                        try {
                            return String.valueOf(Files.size(path));
                        } catch (IOException e) {
                            return "0";
                        }
                    })
                    .collect(Collectors.joining("\n"));
        }
    }

    private void cleanupDummyVm(String dummyVmName) {
        runCommand(String.format("virsh -c qemu:///system destroy %s > /dev/null 2>&1 || true", shellQuote(dummyVmName)));
        runCommand(String.format("virsh -c qemu:///system undefine %s --nvram > /dev/null 2>&1 || virsh -c qemu:///system undefine %s > /dev/null 2>&1 || true",
                shellQuote(dummyVmName), shellQuote(dummyVmName)));
    }

    private void runCommand(String command) {
        Script.runSimpleBashScriptForExitValue(command, resource.getCmdsTimeout(), false);
    }

    private boolean isIncremental(AblestackNasTakeBackupCommand command) {
        return "INCREMENTAL".equalsIgnoreCase(command.getBackupType());
    }

    private String getBackupFileByIndex(AblestackNasTakeBackupCommand command, int index, String fallback) {
        List<String> backupFiles = command.getBackupFiles();
        if (CollectionUtils.isNullOrEmpty(backupFiles) || index >= backupFiles.size()) {
            return fallback;
        }
        return backupFiles.get(index);
    }

    private String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}
