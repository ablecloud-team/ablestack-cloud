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

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.function.Supplier;

import com.cloud.agent.api.Command;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.apache.cloudstack.backup.AblestackBackupFrameworkUtils;
import org.apache.cloudstack.backup.BackupAnswer;
import org.apache.cloudstack.backup.ThirdPartyBackupStart;
import org.apache.logging.log4j.Logger;

/** Stable start receipts survive deletion of the removable Host job directory. */
final class LibvirtAblestackBackupStartHelper {
    private static final Path ROOT = Path.of(AblestackBackupFrameworkUtils.BACKUP_START_ROOT);
    private static final Object[] LOCAL_LOCKS = java.util.stream.IntStream.range(0, 256)
            .mapToObj(index -> new Object()).toArray();

    private LibvirtAblestackBackupStartHelper() { }

    private static Object localLock(String jobId) {
        return LOCAL_LOCKS[Math.floorMod(jobId.hashCode(), LOCAL_LOCKS.length)];
    }

    private static void validate(ThirdPartyBackupStart.Plan plan) {
        if (plan == null || plan.version != ThirdPartyBackupStart.VERSION || plan.jobId == null
                || !plan.jobId.matches("[A-Za-z0-9-]+") || plan.manifest == null
                || !plan.jobId.equals(plan.manifest.getBackupUuid()) || plan.hostId <= 0
                || plan.backupPath == null || !Path.of(plan.backupPath).isAbsolute()) {
            throw new CloudRuntimeException("Invalid volume backup start plan");
        }
        plan.manifest.validate(false);
        if (plan.manifest.getCurrentArtifacts().stream().anyMatch(artifact ->
                !Path.of(artifact.path).getParent().equals(Path.of(plan.backupPath)))) {
            throw new CloudRuntimeException("Backup start destination differs from its artifacts");
        }
    }

    private static ThirdPartyBackupStart.Receipt read(ThirdPartyBackupStart.Plan plan) throws IOException {
        Path file = ROOT.resolve(plan.jobId + ".json");
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) { return null; }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) { throw new IOException("Backup start receipt is not a regular file"); }
        ThirdPartyBackupStart.Receipt receipt = new Gson().fromJson(Files.readString(file), ThirdPartyBackupStart.Receipt.class);
        if (receipt == null || receipt.version != ThirdPartyBackupStart.VERSION || receipt.plan == null
                || !new Gson().toJsonTree(plan).equals(new Gson().toJsonTree(receipt.plan))
                || !Set.of("PREPARED", "DISPATCHED", "STARTED", "START_FAILED").contains(receipt.state)) {
            throw new CloudRuntimeException("Backup start receipt differs from the exact dispatch plan");
        }
        return receipt;
    }

    private static void write(ThirdPartyBackupStart.Receipt receipt) throws IOException {
        receipt.checkedAt = System.currentTimeMillis();
        LibvirtAblestackVolumeRestoreHelper.atomic(ROOT.resolve(receipt.plan.jobId + ".json"), new Gson().toJson(receipt));
        try (FileChannel directory = FileChannel.open(ROOT, StandardOpenOption.READ)) { directory.force(true); }
    }

    static ThirdPartyBackupStart.Receipt recorded(String jobId) throws IOException {
        if (jobId == null || !jobId.matches("[A-Za-z0-9-]+")) { return null; }
        Path file = ROOT.resolve(jobId + ".json");
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) { return null; }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) { throw new IOException("Backup start receipt is not a regular file"); }
        ThirdPartyBackupStart.Receipt receipt = new Gson().fromJson(Files.readString(file), ThirdPartyBackupStart.Receipt.class);
        validate(receipt == null ? null : receipt.plan);
        if (!jobId.equals(receipt.plan.jobId)) { throw new CloudRuntimeException("Backup start receipt belongs to another job"); }
        return read(receipt.plan);
    }

    static boolean unstartedFenced(String jobId) throws IOException {
        ThirdPartyBackupStart.Receipt receipt = recorded(jobId);
        if (receipt == null || !"START_FAILED".equals(receipt.state)
                || !Files.isRegularFile(Path.of(AblestackBackupFrameworkUtils.STAGING_ADMISSION_CLOSED_ROOT, jobId + ".closed"))) { return false; }
        Path job = Path.of(AblestackBackupFrameworkUtils.ASYNC_BACKUP_JOB_ROOT, jobId);
        for (String file : Set.of("volume-source-start.json", "volume-engine.json", "staging-admission-plan.json",
                "staging-admission-request.json", "volume-request.json", "volume-transfer-complete.json")) {
            if (Files.exists(job.resolve(file), LinkOption.NOFOLLOW_LINKS)) {
                throw new CloudRuntimeException("Host IO records conflict with an unstarted backup fence");
            }
        }
        return Files.isRegularFile(job.resolve("volume-cleanup.json"));
    }

    static ThirdPartyBackupStart.Receipt control(ThirdPartyBackupStart.Plan plan, String action, Logger logger) throws IOException {
        validate(plan);
        // Serialize local threads before opening the lock file. POSIX record locks
        // can be released by closing another descriptor for this file in the same process.
        synchronized (localLock(plan.jobId)) {
            Files.createDirectories(ROOT);
            // FileLock and Python lockf use the same POSIX record lock, unlike Python flock.
            try (FileChannel channel = FileChannel.open(ROOT.resolve(plan.jobId + ".lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE); FileLock lock = channel.tryLock()) {
                if (lock == null) { throw new CloudRuntimeException("Backup launch is being reconciled; retry shortly"); }
                ThirdPartyBackupStart.Receipt receipt = read(plan);
                if (receipt == null) {
                    if (!"BACKUP_START_PREPARE".equals(action)) { throw new CloudRuntimeException("Backup start preparation is unconfirmed"); }
                    Path job = Path.of(AblestackBackupFrameworkUtils.ASYNC_BACKUP_JOB_ROOT, plan.jobId);
                    if (Files.exists(job.resolve("volume-plan.json"))
                            || !"UNKNOWN".equals(LibvirtAblestackAsyncBackupRunner.getJobState(plan.jobId, logger))) {
                        throw new CloudRuntimeException("Existing Host artifacts lack a matching backup start receipt");
                    }
                    receipt = new ThirdPartyBackupStart.Receipt();
                    receipt.plan = plan;
                    receipt.state = "PREPARED";
                    write(receipt);
                }
                if ("BACKUP_START_ABORT".equals(action) && !"STARTED".equals(receipt.state)) {
                    Path job = Path.of(AblestackBackupFrameworkUtils.ASYNC_BACKUP_JOB_ROOT, plan.jobId);
                    for (String file : Set.of("volume-source-start.json", "volume-engine.json", "staging-admission-plan.json",
                            "staging-admission-request.json", "volume-request.json", "volume-transfer-complete.json")) {
                        if (Files.exists(job.resolve(file), LinkOption.NOFOLLOW_LINKS)) {
                            throw new CloudRuntimeException("Host IO records conflict with an unstarted backup receipt");
                        }
                    }
                    // This write fences delayed Agent commands and Python workers before any source or staging IO.
                    receipt.state = "START_FAILED";
                    receipt.reason = "Backup engine did not initialize; this attempt is permanently blocked from starting";
                    write(receipt);
                    Path closed = Path.of(AblestackBackupFrameworkUtils.STAGING_ADMISSION_CLOSED_ROOT);
                    Files.createDirectories(closed);
                    LibvirtAblestackVolumeRestoreHelper.atomic(closed.resolve(plan.jobId + ".closed"), receipt.reason + "\n");
                    try (FileChannel directory = FileChannel.open(closed, StandardOpenOption.READ)) { directory.force(true); }
                    Files.createDirectories(job);
                    LibvirtAblestackVolumeRestoreHelper.atomic(job.resolve("volume-cleanup.json"), "{\"state\":\"COMPLETED\"}");
                    LibvirtAblestackAsyncBackupRunner.markBackupStartFailed(plan, receipt.reason, logger);
                }
                return receipt;
            }
        }
    }

    static BackupAnswer launch(Command command, Logger logger, String trace, String provider, String jobId, String vmName,
            String backupPath, String backupType, String planJson, Supplier<String[]> commandSupplier) {
        try {
            ThirdPartyBackupStart.Plan plan = new Gson().fromJson(planJson, ThirdPartyBackupStart.Plan.class);
            validate(plan);
            if (!plan.jobId.equals(jobId) || !plan.backupPath.equals(backupPath) || !plan.manifest.getVmName().equals(vmName)
                    || !plan.manifest.getBackupType().equalsIgnoreCase(backupType)
                    || !plan.manifest.getProvider().equals("ablestack-" + provider.toLowerCase(java.util.Locale.ROOT))) {
                throw new CloudRuntimeException("Backup command differs from its prepared start plan");
            }
            synchronized (localLock(plan.jobId)) {
                Files.createDirectories(ROOT);
                try (FileChannel channel = FileChannel.open(ROOT.resolve(jobId + ".lock"),
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE); FileLock lock = channel.tryLock()) {
                    if (lock == null) { throw new CloudRuntimeException("Backup start control is busy; dispatch is unconfirmed"); }
                    ThirdPartyBackupStart.Receipt receipt = read(plan);
                    if (receipt == null) { throw new CloudRuntimeException("Backup start has not been prepared"); }
                    if ("START_FAILED".equals(receipt.state)) { return new BackupAnswer(command, false, receipt.reason); }
                    if (!"PREPARED".equals(receipt.state)) { return new BackupAnswer(command, true, "The same backup start was already dispatched"); }
                    String[] scriptCommand = commandSupplier.get();
                    Path file = Path.of(AblestackBackupFrameworkUtils.ASYNC_BACKUP_JOB_ROOT, jobId, "volume-plan.json");
                    if (scriptCommand == null || scriptCommand.length != 4 || !"python3".equals(scriptCommand[0])
                            || !"--plan-file".equals(scriptCommand[2]) || !file.equals(Path.of(scriptCommand[3]))) {
                        throw new CloudRuntimeException("Prepared volume backup requires the fenced Python engine");
                    }
                    JsonObject sourcePlan = new Gson().fromJson(Files.readString(file), JsonObject.class);
                    if (sourcePlan == null || !new Gson().toJsonTree(plan.manifest).equals(sourcePlan.get("manifest"))) {
                        throw new CloudRuntimeException("Prepared source manifest differs from the backup start plan");
                    }
                    sourcePlan.add("backupStartPlan", new Gson().toJsonTree(plan));
                    LibvirtAblestackVolumeRestoreHelper.atomic(file, new Gson().toJson(sourcePlan));
                    receipt.state = "DISPATCHED";
                    write(receipt);
                    return LibvirtAblestackAsyncBackupRunner.startDetached(command, logger, trace, provider, jobId,
                            vmName, backupPath, backupType, scriptCommand, false);
                }
            }
        } catch (IOException | RuntimeException e) {
            logger.warn("Volume backup start is unconfirmed for job [{}]: {}", jobId, e.getMessage(), e);
            return new BackupAnswer(command, false, e.getMessage());
        }
    }
}
