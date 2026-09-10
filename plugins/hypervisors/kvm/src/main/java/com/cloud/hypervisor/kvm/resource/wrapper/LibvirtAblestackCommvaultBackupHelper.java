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
import org.apache.cloudstack.backup.AblestackCommvaultTakeBackupCommand;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;
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
import java.util.concurrent.TimeUnit;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

class LibvirtAblestackCommvaultBackupHelper {
    protected Logger LOGGER = LogManager.getLogger(LibvirtAblestackCommvaultBackupHelper.class);
    static final Integer EXIT_CLEANUP_FAILED = 20;
    private static final int BACKUP_JOB_POLL_INTERVAL_MS = 10000;
    private static final DateTimeFormatter SCRIPT_LOG_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss>");
    private static final String STAGING_IN_PROGRESS_MARKER = ".staging.inprogress";
    private static final String STAGING_COMPLETE_MARKER = ".staging.complete";
    private static final String BACKUP_TRACE = "[ABLESTACK_COMMVAULT_BACKUP_TRACE]";

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

    LibvirtAblestackCommvaultBackupHelper(LibvirtComputingResource resource) {
        this.resource = resource;
    }

    Pair<Integer, String> executeBackup(AblestackCommvaultTakeBackupCommand command) {
        List<String> diskPaths = resolveDiskPaths(command.getVolumePools(), command.getVolumePaths());
        BackupExecutionMode executionMode = determineExecutionMode(command.getVmName(), command.getVolumePools());
        LOGGER.debug("Commvault backup execution mode=[{}], vm=[{}], backupType=[{}], diskPaths=[{}]",
                executionMode, command.getVmName(), command.getBackupType(), diskPaths);
        if (BackupExecutionMode.STOPPED.equals(executionMode)) {
            return executeStoppedVmBackup(command, diskPaths);
        }

        List<String[]> commands = new ArrayList<>();
        ensureParentCheckpointMaterialized(command);
        String[] scriptCommand = buildBackupScriptCommand(command, diskPaths, executionMode);
        LOGGER.debug("Executing Commvault backup script command=[{}]", String.join(" ", scriptCommand));
        commands.add(scriptCommand);
        final int commandWaitSeconds = command.getWait();
        final long resourceTimeoutMillis = resource.getCmdsTimeout();
        final long effectiveTimeoutMillis = commandWaitSeconds > 0 ? TimeUnit.SECONDS.toMillis(commandWaitSeconds) : resourceTimeoutMillis;
        LOGGER.info(
                "Executing running VM Commvault backup for vm=[{}], commandWaitSeconds=[{}], "
                        + "resourceCmdsTimeoutMillis=[{}], effectiveTimeoutMillis=[{}]",
                command.getVmName(),
                commandWaitSeconds,
                resourceTimeoutMillis,
                effectiveTimeoutMillis
        );
        Pair<Integer, String> result = Script.executePipedCommands(commands, effectiveTimeoutMillis);
        if (result.first() != 0 && BackupExecutionMode.RUNNING.equals(executionMode)) {
            resumeVmIfPaused(command.getVmName());
        }
        return result;
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

    private String[] buildBackupScriptCommand(AblestackCommvaultTakeBackupCommand command, List<String> diskPaths, BackupExecutionMode executionMode) {
        return new String[] {
                resource.getAbleCvtBackupPath(),
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
                "-d", diskPaths.isEmpty() ? "" : String.join(",", diskPaths),
                "--bandwidth-limit-mbps", String.valueOf(command.getBandwidthLimitMbps())
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

    private Pair<Integer, String> executeStoppedVmBackup(AblestackCommvaultTakeBackupCommand command, List<String> diskPaths) {
        String dummyVmName = String.format("DUMMY-VM-%s", command.getCheckpointName().replace('.', '-'));
        Path dest = Path.of(command.getBackupPath());
        Connect conn = null;
        long startedAt = System.currentTimeMillis();
        try {
            LOGGER.info("{} phase=[STOPPED_BACKUP_START], vm=[{}], dummyVm=[{}], backupType=[{}], checkpoint=[{}]",
                    BACKUP_TRACE, command.getVmName(), dummyVmName, command.getBackupType(), command.getCheckpointName());
            validateStoppedBackupDiskPaths(diskPaths);
            if (isIncremental(command)) {
                resource.validateLibvirtAndQemuVersionForIncrementalSnapshots();
            }
            Files.createDirectories(dest.resolve("checkpoints"));
            markStagingInProgress(dest, command);

            conn = LibvirtConnection.getConnection();
            String dummyVmXml = buildDummyVmXml(dummyVmName, diskPaths);
            resource.startVM(conn, dummyVmName, dummyVmXml, Domain.CreateFlags.PAUSED);
            LOGGER.info("{} phase=[DUMMY_CREATED], vm=[{}], dummyVm=[{}], backupType=[{}], checkpoint=[{}]",
                    BACKUP_TRACE, command.getVmName(), dummyVmName, command.getBackupType(), command.getCheckpointName());

            ensureParentCheckpointMaterialized(command);
            if (isIncremental(command) && command.getParentCheckpointPath() != null && !command.getParentCheckpointPath().isEmpty()) {
                redefineCheckpointIfNeeded(dummyVmName, Path.of(command.getParentCheckpointPath()));
            }

            List<String> diskLabels = getDiskLabels(conn, dummyVmName);
            Path backupXml = writeBackupXml(dest, command, diskLabels);
            Path checkpointXml = writeCheckpointXml(dest, command, diskLabels);

            String backupBeginCommand = String.format("virsh -c qemu:///system backup-begin --domain %s --backupxml %s --checkpointxml %s",
                    shellQuote(dummyVmName), shellQuote(backupXml.toString()), shellQuote(checkpointXml.toString()));
            LOGGER.debug("Starting stopped VM Commvault backup-begin command=[{}]", backupBeginCommand);
            Pair<Integer, String> backupBeginResult = runCommandWithOutput(backupBeginCommand);
            if (backupBeginResult.first() != 0) {
                String failureDetails = formatScriptStyleLog(String.format(
                        "Failed to start stopped VM Commvault backup for dummy domain [%s]: %s",
                        dummyVmName, sanitizeCommandOutput(backupBeginResult.second())));
                LOGGER.error("{} phase=[BACKUP_BEGIN_FAILED], vm=[{}], dummyVm=[{}], backupType=[{}], checkpoint=[{}], reason=[{}]",
                        BACKUP_TRACE, command.getVmName(), dummyVmName, command.getBackupType(), command.getCheckpointName(), failureDetails);
                LOGGER.error(failureDetails);
                throw new IOException(failureDetails);
            }
            LOGGER.info("{} phase=[BACKUP_JOB_STARTED], vm=[{}], dummyVm=[{}], backupType=[{}], checkpoint=[{}], elapsedMs=[{}]",
                    BACKUP_TRACE, command.getVmName(), dummyVmName, command.getBackupType(), command.getCheckpointName(),
                    System.currentTimeMillis() - startedAt);

            try {
                final long effectiveTimeoutMillis = command.getWait() > 0 ? TimeUnit.SECONDS.toMillis(command.getWait()) : resource.getCmdsTimeout();
                waitForBackup(dummyVmName, effectiveTimeoutMillis);
                LOGGER.info("{} phase=[BACKUP_JOB_COMPLETED], vm=[{}], dummyVm=[{}], backupType=[{}], checkpoint=[{}], elapsedMs=[{}]",
                        BACKUP_TRACE, command.getVmName(), dummyVmName, command.getBackupType(), command.getCheckpointName(),
                        System.currentTimeMillis() - startedAt);
            } catch (IOException e) {
                cancelBackupJob(dummyVmName);
                throw e;
            }

            dumpCheckpointXml(dummyVmName, command.getCheckpointName(), dest);
            LOGGER.info("{} phase=[CHECKPOINT_DUMPED], vm=[{}], dummyVm=[{}], backupType=[{}], checkpoint=[{}]",
                    BACKUP_TRACE, command.getVmName(), dummyVmName, command.getBackupType(), command.getCheckpointName());
            Files.deleteIfExists(backupXml);
            Files.deleteIfExists(checkpointXml);
            Script.runSimpleBashScriptForExitValue("sync", resource.getCmdsTimeout(), false);
            markStagingComplete(dest, command);
            LOGGER.info("{} phase=[STOPPED_BACKUP_DONE], vm=[{}], dummyVm=[{}], backupType=[{}], checkpoint=[{}], elapsedMs=[{}]",
                    BACKUP_TRACE, command.getVmName(), dummyVmName, command.getBackupType(), command.getCheckpointName(),
                    System.currentTimeMillis() - startedAt);
            return new Pair<>(0, "success");
        } catch (Exception e) {
            LOGGER.error("{} phase=[STOPPED_BACKUP_FAILED], vm=[{}], dummyVm=[{}], backupType=[{}], checkpoint=[{}], elapsedMs=[{}], reason=[{}]",
                    BACKUP_TRACE, command.getVmName(), dummyVmName, command.getBackupType(), command.getCheckpointName(),
                    System.currentTimeMillis() - startedAt, e.getMessage(), e);
            if (!cleanupStoppedBackupPath(dest)) {
                return new Pair<>(EXIT_CLEANUP_FAILED, String.format("Backup cleanup failed after stopped VM Commvault backup failure: %s", e.getMessage()));
            }
            return new Pair<>(1, e.getMessage());
        } finally {
            cleanupDummyVm(dummyVmName);
            LOGGER.info("{} phase=[DUMMY_CLEANED], vm=[{}], dummyVm=[{}], backupType=[{}], checkpoint=[{}], elapsedMs=[{}]",
                    BACKUP_TRACE, command.getVmName(), dummyVmName, command.getBackupType(), command.getCheckpointName(),
                    System.currentTimeMillis() - startedAt);
        }
    }

    private void markStagingInProgress(Path dest, AblestackCommvaultTakeBackupCommand command) throws IOException {
        Files.deleteIfExists(dest.resolve(STAGING_COMPLETE_MARKER));
        Files.writeString(dest.resolve(STAGING_IN_PROGRESS_MARKER),
                String.format("vm=%s%ncheckpoint=%s%n", command.getVmName(), command.getCheckpointName()),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private void markStagingComplete(Path dest, AblestackCommvaultTakeBackupCommand command) throws IOException {
        Path completeMarker = dest.resolve(STAGING_COMPLETE_MARKER);
        Path tmpMarker = dest.resolve(STAGING_COMPLETE_MARKER + ".tmp");
        Files.writeString(tmpMarker,
                String.format("vm=%s%ncheckpoint=%s%n", command.getVmName(), command.getCheckpointName()),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.move(tmpMarker, completeMarker, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.deleteIfExists(dest.resolve(STAGING_IN_PROGRESS_MARKER));
    }

    private boolean cleanupStoppedBackupPath(Path dest) {
        if (dest == null || !Files.exists(dest)) {
            return true;
        }
        try (var stream = Files.walk(dest)) {
            List<Path> paths = stream.sorted(Comparator.reverseOrder()).collect(Collectors.toList());
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
            return true;
        } catch (IOException e) {
            LOGGER.warn("Failed to cleanup stopped VM Commvault backup path [{}]: {}", dest, e.getMessage(), e);
            return false;
        }
    }

    private void ensureParentCheckpointMaterialized(AblestackCommvaultTakeBackupCommand command) {
        if (!isIncremental(command)) {
            return;
        }
        try {
            if (command.getParentCheckpointXmlChain() != null) {
                for (var entry : command.getParentCheckpointXmlChain().entrySet()) {
                    materializeCheckpointXml(entry.getKey(), entry.getValue());
                }
            }
            materializeCheckpointXml(command.getParentCheckpointPath(), command.getParentCheckpointXml());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to materialize parent checkpoint XML chain for VM " + command.getVmName(), e);
        }
    }

    private void materializeCheckpointXml(String checkpointPathValue, String checkpointXml) throws IOException {
        if (StringUtils.isBlank(checkpointPathValue) || StringUtils.isBlank(checkpointXml)) {
            return;
        }
        Path checkpointPath = Path.of(checkpointPathValue);
        if (Files.exists(checkpointPath)) {
            return;
        }
        Path parentDir = checkpointPath.getParent();
        if (parentDir != null) {
            Files.createDirectories(parentDir);
        }
        Files.writeString(checkpointPath, checkpointXml, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        LOGGER.debug("Materialized parent checkpoint XML at [{}]", checkpointPath);
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

    private Path writeBackupXml(Path dest, AblestackCommvaultTakeBackupCommand command, List<String> diskLabels) throws IOException {
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

    private Path writeCheckpointXml(Path dest, AblestackCommvaultTakeBackupCommand command, List<String> diskLabels) throws IOException {
        StringBuilder xml = new StringBuilder("<domaincheckpoint><name>").append(command.getCheckpointName()).append("</name><disks>");
        for (String diskLabel : diskLabels) {
            xml.append("<disk name='").append(diskLabel).append("' checkpoint='bitmap'/>");
        }
        xml.append("</disks></domaincheckpoint>");
        Path checkpointXml = dest.resolve("checkpoint.xml");
        Files.writeString(checkpointXml, xml.toString(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        return checkpointXml;
    }

    private void waitForBackup(String vmName, long timeoutMillis) throws IOException {
        long remainingMillis = timeoutMillis;
        while (remainingMillis > 0) {
            String result = checkBackupJob(vmName);
            if (result != null && result.contains("Completed") && result.contains("Backup")) {
                return;
            }
            if (result != null && result.contains("Failed")) {
                throw new IOException("Virsh backup job failed for dummy VM " + vmName);
            }
            long sleepMillis = Math.min(BACKUP_JOB_POLL_INTERVAL_MS, remainingMillis);
            try {
                Thread.sleep(sleepMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
            remainingMillis -= sleepMillis;
        }
        throw new IOException("Timed out waiting for backup job of dummy VM " + vmName + " after "+ timeoutMillis + " milliseconds"
        );
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

    private void resumeVmIfPaused(String vmName) {
        if (vmName == null || vmName.isBlank()) {
            return;
        }
        String state = Script.runSimpleBashScriptWithFullResult(
                String.format("virsh -c qemu:///system domstate %s 2>/dev/null", shellQuote(vmName)), 10000);
        if (state != null && "paused".equalsIgnoreCase(state.trim())) {
            LOGGER.warn("{} phase=[RESUME_PAUSED_VM], vm=[{}]", BACKUP_TRACE, vmName);
            Script.runSimpleBashScriptForExitValue(String.format(
                    "virsh -c qemu:///system resume %s > /dev/null 2>&1", shellQuote(vmName)));
        }
    }

    private boolean isIncremental(AblestackCommvaultTakeBackupCommand command) {
        return "INCREMENTAL".equalsIgnoreCase(command.getBackupType());
    }

    private String getBackupFileByIndex(AblestackCommvaultTakeBackupCommand command, int index, String fallback) {
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
