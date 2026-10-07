// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

package com.cloud.hypervisor.kvm.resource.wrapper;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.hypervisor.kvm.storage.KVMPhysicalDisk;
import com.cloud.hypervisor.kvm.storage.KVMStoragePool;
import com.cloud.hypervisor.kvm.storage.KVMStoragePoolManager;
import com.cloud.storage.Storage;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.script.Script;
import com.google.gson.Gson;
import org.apache.cloudstack.backup.AblestackBackupFrameworkUtils;
import org.apache.cloudstack.backup.ThirdPartyBackupManifest;
import org.apache.cloudstack.backup.ThirdPartyBackupRestore;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;
import org.apache.cloudstack.utils.qemu.QemuImg;
import org.apache.cloudstack.utils.qemu.QemuImgFile;
import org.apache.logging.log4j.Logger;

/** Download/apply/unlink one artifact at a time; use the same journalled VM switch as legacy restores. */
final class LibvirtAblestackVolumeRestoreHelper {
    private static final Object[] START_LOCKS = java.util.stream.IntStream.range(0, 256).mapToObj(index -> new Object()).toArray();

    private static Object localStartLock(String jobId) { return START_LOCKS[Math.floorMod(jobId.hashCode(), START_LOCKS.length)]; }

    private final ThirdPartyBackupRestore.Plan plan;
    private final Path job;
    private final Logger logger;
    private final String provider;
    private final String trace;
    private long deadline;
    private int sequence;
    private boolean transferUncertain;
    private String targetVmName;
    private int artifactCount;

    private LibvirtAblestackVolumeRestoreHelper(ThirdPartyBackupRestore.Plan plan, Logger logger) {
        this.plan = plan;
        this.logger = logger;
        provider = plan.manifest.getProvider().substring("ablestack-".length());
        trace = AblestackBackupFrameworkUtils.buildTracePrefix(provider, AblestackBackupFrameworkUtils.OPERATION_RESTORE);
        job = Path.of(AblestackBackupFrameworkUtils.ASYNC_BACKUP_JOB_ROOT, plan.jobId);
        deadline = plan.timeout <= 0 ? Long.MAX_VALUE : System.nanoTime() + TimeUnit.SECONDS.toNanos(plan.timeout);
    }

    private static Path startDirectory() {
        return Path.of(AblestackBackupFrameworkUtils.ASYNC_BACKUP_JOB_ROOT).getParent().resolve("restore-start");
    }

    private static Path startFile(String jobId) { return startDirectory().resolve(jobId + ".json"); }

    private static void validateStartPlan(ThirdPartyBackupRestore.Plan plan) {
        if (plan == null || plan.jobId == null || !plan.jobId.matches("[A-Za-z0-9_.-]+")
                || plan.jobId.equals(".") || plan.jobId.equals("..") || plan.manifest == null
                || plan.startProtocolVersion != ThirdPartyBackupRestore.START_PROTOCOL_VERSION
                || plan.targetVmName == null || !plan.targetVmName.matches("[A-Za-z0-9_.-]+")) {
            throw new CloudRuntimeException("Invalid restore start protocol or plan");
        }
        plan.manifest.validate(true);
        Path destination = Path.of(plan.stageRoot).resolve(plan.manifest.getProvider()).resolve("restore").resolve(plan.jobId);
        if (!destination.isAbsolute() || !destination.toString().equals(plan.destination)) {
            throw new CloudRuntimeException("Restore start destination differs from its plan");
        }
    }

    private static ThirdPartyBackupRestore.StartReceipt readStart(ThirdPartyBackupRestore.Plan plan) throws IOException {
        Path file = startFile(plan.jobId);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) { return null; }
        ThirdPartyBackupRestore.StartReceipt receipt = new Gson().fromJson(Files.readString(file), ThirdPartyBackupRestore.StartReceipt.class);
        if (receipt == null || receipt.version != ThirdPartyBackupRestore.START_PROTOCOL_VERSION || receipt.plan == null
                || !new Gson().toJson(plan).equals(new Gson().toJson(receipt.plan))
                || !java.util.Set.of("PREPARED", "INITIALIZED", "START_FAILED").contains(receipt.state)) {
            throw new CloudRuntimeException("Restore start receipt differs from the exact recorded plan");
        }
        return receipt;
    }

    private static void writeStart(ThirdPartyBackupRestore.StartReceipt receipt) throws IOException {
        receipt.checkedAt = System.currentTimeMillis();
        atomic(startFile(receipt.plan.jobId), new Gson().toJson(receipt));
        try (FileChannel directory = FileChannel.open(startDirectory(), StandardOpenOption.READ)) { directory.force(true); }
    }

    /** Both the initializer and start cancellation lock this stable file, outside removable job records. */
    static ThirdPartyBackupRestore.StartReceipt controlStart(ThirdPartyBackupRestore.Plan plan, boolean abort) throws IOException {
        validateStartPlan(plan);
        // Closing another same-file descriptor in this JVM can release its POSIX record lock.
        // Serialize local threads before either control or initialization opens the descriptor.
        synchronized (localStartLock(plan.jobId)) {
            Files.createDirectories(startDirectory());
            try (FileChannel channel = FileChannel.open(startDirectory().resolve(plan.jobId + ".lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE); java.nio.channels.FileLock lock = channel.tryLock()) {
                if (lock == null) { throw new CloudRuntimeException("Restore initialization is being inspected; retry shortly"); }
                ThirdPartyBackupRestore.StartReceipt receipt = readStart(plan);
                Path job = Path.of(AblestackBackupFrameworkUtils.ASYNC_BACKUP_JOB_ROOT, plan.jobId);
                Path hostPlan = job.resolve("volume-restore-plan.json");
                if (Files.exists(hostPlan, LinkOption.NOFOLLOW_LINKS)) {
                    if (!new Gson().toJson(plan).equals(new Gson().toJson(new Gson().fromJson(Files.readString(hostPlan), ThirdPartyBackupRestore.Plan.class)))) {
                        throw new CloudRuntimeException("Host restore plan differs from its start receipt");
                    }
                    if (receipt != null && "START_FAILED".equals(receipt.state)) {
                        throw new CloudRuntimeException("Initialized Host restore conflicts with a start failure receipt");
                    }
                    if (receipt == null) { receipt = new ThirdPartyBackupRestore.StartReceipt(); receipt.plan = plan; }
                    receipt.state = "INITIALIZED";
                    writeStart(receipt);
                    return receipt;
                }
                if (receipt == null) {
                    if (abort) { throw new CloudRuntimeException("Host start preparation is unconfirmed; no absence-based release is allowed"); }
                    receipt = new ThirdPartyBackupRestore.StartReceipt();
                    receipt.plan = plan;
                    receipt.state = "PREPARED";
                    writeStart(receipt);
                }
                if (abort && "PREPARED".equals(receipt.state)) {
                    if (Files.exists(job.resolve("restore-capacity-plan.json")) || Files.exists(job.resolve("volume-restore-request.json"))
                            || Files.exists(job.resolve("staging-admission-plan.json"))
                            || Files.exists(Path.of(plan.destination), LinkOption.NOFOLLOW_LINKS)
                            || Files.exists(Path.of(plan.stageRoot, ".volume-reservations", plan.jobId + ".json"))) {
                        throw new CloudRuntimeException("Restore has Host artifacts or capacity records; normal reconciliation is required");
                    }
                    receipt.state = "START_FAILED";
                    receipt.reason = "Restore engine did not initialize; this attempt is permanently blocked from starting";
                    writeStart(receipt);
                }
                return receipt;
            }
        }
    }

    static String startFailure(String jobId) throws IOException {
        if (jobId == null || !jobId.matches("[A-Za-z0-9_.-]+") || jobId.equals(".") || jobId.equals("..")) { return null; }
        Path file = startFile(jobId);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) { return null; }
        ThirdPartyBackupRestore.StartReceipt receipt = new Gson().fromJson(Files.readString(file), ThirdPartyBackupRestore.StartReceipt.class);
        if (receipt == null || receipt.plan == null || !jobId.equals(receipt.plan.jobId)
                || receipt.version != ThirdPartyBackupRestore.START_PROTOCOL_VERSION) {
            throw new CloudRuntimeException("Invalid persisted restore start receipt");
        }
        return "START_FAILED".equals(receipt.state) ? receipt.reason : null;
    }

    static void restore(LibvirtComputingResource resource, Logger logger, ThirdPartyBackupRestore.Plan plan,
            String vmName, List<PrimaryDataStoreTO> pools, List<String> targets) {
        if (plan.jobId == null || !plan.jobId.matches("[A-Za-z0-9_.-]+") || plan.jobId.equals(".") || plan.jobId.equals("..")) {
            throw new CloudRuntimeException("Invalid restore job ID");
        }
        if (plan.targetVmName != null && !plan.targetVmName.equals(vmName)) {
            throw new CloudRuntimeException("Restore target differs from the recorded plan");
        }
        LibvirtAblestackVolumeRestoreHelper helper = new LibvirtAblestackVolumeRestoreHelper(plan, logger);
        try {
            Files.createDirectories(helper.job);
            try (FileChannel channel = FileChannel.open(helper.job.resolve("volume-restore-engine.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE); java.nio.channels.FileLock lock = channel.tryLock()) {
                if (lock == null) { throw new CloudRuntimeException("Volume restore engine is already active"); }
                if (Files.exists(helper.job.resolve("volume-restore-plan.json"))) {
                    throw new CloudRuntimeException("Volume restore job was already initialized; use reconciliation instead of rerunning it");
                }
                plan.targetVmName = vmName;
                if (plan.startProtocolVersion > 0) {
                    validateStartPlan(plan);
                    synchronized (localStartLock(plan.jobId)) {
                        Files.createDirectories(startDirectory());
                        try (FileChannel start = FileChannel.open(startDirectory().resolve(plan.jobId + ".lock"),
                                StandardOpenOption.CREATE, StandardOpenOption.WRITE); java.nio.channels.FileLock startLock = start.tryLock()) {
                            if (startLock == null) { throw new CloudRuntimeException("Restore start control is active; dispatch will not be repeated"); }
                            ThirdPartyBackupRestore.StartReceipt receipt = readStart(plan);
                            if (receipt == null || !"PREPARED".equals(receipt.state)) {
                                throw new CloudRuntimeException("Restore was not prepared or has already started or been blocked");
                            }
                            // No capacity reservation or data mutation precedes this durable initialization proof.
                            atomic(helper.job.resolve("volume-restore-plan.json"), new Gson().toJson(plan));
                            receipt.state = "INITIALIZED";
                            writeStart(receipt);
                        }
                    }
                } else {
                    atomic(helper.job.resolve("volume-restore-plan.json"), new Gson().toJson(plan));
                }
                LibvirtAblestackRestoreOutcome.initialize(plan);
                try { helper.execute(resource, vmName, pools, targets); }
                catch (RuntimeException failure) {
                    try { LibvirtAblestackRestoreOutcome.recordFailure(plan, failure.getMessage()); }
                    catch (Exception auditFailure) { logger.warn("Restore failure result could not be saved for job [{}]", plan.jobId, auditFailure); }
                    throw failure;
                }
            }
        } catch (IOException e) { throw new CloudRuntimeException("Unable to persist volume restore initialization", e); }
    }

    /** Controller calls this only after the exact external writer has terminated. */
    static void cleanup(LibvirtComputingResource resource, Logger logger, ThirdPartyBackupRestore.Plan expected) throws IOException {
        if (expected.jobId == null || !expected.jobId.matches("[A-Za-z0-9_.-]+") || expected.jobId.equals(".") || expected.jobId.equals("..")) {
            throw new CloudRuntimeException("Invalid restore cleanup ID");
        }
        Path job = Path.of(AblestackBackupFrameworkUtils.ASYNC_BACKUP_JOB_ROOT, expected.jobId);
        try (FileChannel channel = FileChannel.open(job.resolve("volume-restore-engine.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE); java.nio.channels.FileLock lock = channel.tryLock()) {
            if (lock == null) { throw new CloudRuntimeException("Volume restore engine has not terminated"); }
            Path planFile = job.resolve("volume-restore-plan.json");
            if (!Files.isRegularFile(planFile)) { throw new CloudRuntimeException("Host restore plan is unavailable; cleanup requires reconciliation"); }
            ThirdPartyBackupRestore.Plan plan = new Gson().fromJson(Files.readString(planFile), ThirdPartyBackupRestore.Plan.class);
            Path root = Path.of(plan.stageRoot).toAbsolutePath().normalize();
            Path destination = Path.of(plan.destination).toAbsolutePath().normalize();
            if (!expected.jobId.equals(plan.jobId) || !expected.destination.equals(plan.destination)
                    || !expected.stageRoot.equals(plan.stageRoot) || !expected.manifest.getBackupUuid().equals(plan.manifest.getBackupUuid())
                    || (expected.targetVmName != null && !expected.targetVmName.equals(plan.targetVmName))
                    || plan.targetVmName == null || !plan.targetVmName.matches("[A-Za-z0-9_.-]+")
                    || !destination.equals(root.resolve(plan.manifest.getProvider()).resolve("restore").resolve(plan.jobId))
                    || Files.isSymbolicLink(destination)) { throw new CloudRuntimeException("Invalid restore cleanup destination or target VM"); }
            Path capacityPlan = job.resolve("restore-capacity-plan.json");
            if (Files.exists(capacityPlan)) {
                com.google.gson.JsonObject admission = new Gson().fromJson(Files.readString(capacityPlan), com.google.gson.JsonObject.class);
                Path identity = root.resolve(".volume-reservations").resolve("filesystem.id");
                boolean sameFilesystem = admission.has("stageFilesystemId")
                        ? Files.isRegularFile(identity) && Files.readString(identity).trim().equals(admission.get("stageFilesystemId").getAsString())
                        : admission.has("stageDevice") && admission.get("stageDevice").getAsLong()
                                == ((Number) Files.getAttribute(root, "unix:dev")).longValue();
                if (!sameFilesystem) {
                    throw new CloudRuntimeException("Staging filesystem identity is unconfirmed; restore the original mount before cleanup");
                }
            }
            // An Agent crash may leave its converter child running even though the Java engine lock was released.
            try (var processes = ProcessHandle.allProcesses()) {
                if (processes.anyMatch(process -> java.util.Arrays.stream(process.info().arguments().orElse(new String[0]))
                        .anyMatch(arg -> arg.startsWith(destination.toString() + "/")))) {
                    throw new CloudRuntimeException("A Host process still owns this restore payload");
                }
            }
            if (plan.primaryCapacityVersion == 1 && Files.isRegularFile(job.resolve("restore-primary-capacity.json"))) {
                // Rollback/commit cleanup must use the same physical stores, even after remount or Agent restart.
                LibvirtAblestackPrimaryRestoreCapacity.query(resource, job);
            }
            LibvirtAblestackRestoreTransaction.recoverForVm(logger, resource.getStoragePoolMgr(), plan.targetVmName, plan.timeout);
            if (plan.vmResultVersion == 1) {
                LibvirtAblestackRestoreOutcome.recordFailure(plan, "Restore engine stopped before a VM volume transaction began");
                var result = LibvirtAblestackRestoreOutcome.read(plan.jobId);
                if (result != null) { result.validate(plan); }
                if (result == null || (result.transactionId != null
                        && (!java.util.Set.of("COMMITTED", "ROLLED_BACK").contains(result.outcome)
                            || !"COMPLETED".equals(result.primaryCleanupState)))) {
                    throw new CloudRuntimeException("VM volume transaction outcome or primary cleanup is unconfirmed; reservations are retained");
                }
            }
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                if (!destination.toRealPath().startsWith(root.toRealPath())) { throw new CloudRuntimeException("Restore cleanup resolves outside staging"); }
                Path owner = destination.resolve(".restore-owner");
                if (!Files.isRegularFile(owner, LinkOption.NOFOLLOW_LINKS) || !Files.readString(owner).trim().equals(plan.jobId)) {
                    throw new CloudRuntimeException("Restore staging directory ownership is unconfirmed; existing data is protected");
                }
                LibvirtAblestackStagingCleanup.delete(plan.manifest.getProvider(), destination,
                        root.resolve(plan.manifest.getProvider()));
            }
            if (Files.exists(capacityPlan)) {
                Path script = Path.of(resource.getAbleCvtBackupPath()).getParent().resolve("thirdparty_restore_capacity.py");
                new LibvirtAblestackVolumeRestoreHelper(plan, logger).capacity(script, capacityPlan, "release");
            }
            if ("ablestack-netbackup".equals(plan.manifest.getProvider())) {
                Path control = Path.of("/usr/openv/netbackup/logs/user_ops/ablestack", plan.jobId);
                if (Files.isSymbolicLink(control)) { throw new CloudRuntimeException("NetBackup restore control directory is a symlink"); }
                if (Files.isDirectory(control)) { org.apache.commons.io.FileUtils.deleteDirectory(control.toFile()); }
            }
            atomic(job.resolve("volume-restore-cleanup.json"), "{\"state\":\"COMPLETED\"}");
            LibvirtAblestackAsyncBackupRunner.recoverFailedRestoreTransaction(plan.jobId, resource.getStoragePoolMgr(), logger);
        }
    }

    private void execute(LibvirtComputingResource resource, String vmName, List<PrimaryDataStoreTO> pools, List<String> targets) {
        checkCancellation();
        plan.manifest.validate(true);
        targetVmName = vmName;
        if (plan.jobId == null || !plan.jobId.matches("[A-Za-z0-9_.-]+") || plan.volumeUuids.isEmpty()
                || new java.util.HashSet<>(plan.volumeUuids).size() != plan.volumeUuids.size()
                || (plan.targetVolumeBytes != null && plan.targetVolumeBytes.size() != plan.volumeUuids.size())
                || targets.size() != plan.volumeUuids.size() || pools.size() != targets.size()) {
            throw new CloudRuntimeException("Invalid streaming restore plan");
        }
        Path root = Path.of(plan.stageRoot).toAbsolutePath().normalize();
        Path destination = Path.of(plan.destination).toAbsolutePath().normalize();
        if (!destination.startsWith(root.resolve(plan.manifest.getProvider()).resolve("restore"))
                || !destination.getFileName().toString().equals(plan.jobId)) {
            throw new CloudRuntimeException("Invalid streaming restore destination");
        }
        List<ThirdPartyBackupManifest.Volume> volumes = new ArrayList<>();
        for (String uuid : plan.volumeUuids) {
            volumes.add(plan.manifest.getVolumes().stream().filter(v -> uuid.equals(v.uuid)).findFirst().orElseThrow());
        }
        artifactCount = 1 + volumes.stream().mapToInt(volume -> volume.chain.size()).sum();
        for (int i = 0; i < volumes.size(); i++) {
            if (targetBytes(i, volumes.get(i)) < volumes.get(i).provisionedBytes) {
                throw new CloudRuntimeException("Restore destination is smaller than the backed up volume");
            }
            if (volumes.get(i).chain.size() > 1 && volumes.get(i).engine.startsWith("RBD")
                    && pools.get(i).getPoolType() != Storage.StoragePoolType.RBD) {
                throw new CloudRuntimeException("Streaming RBD diff restore requires an RBD primary destination");
            }
        }
        Path capacityScript = Path.of(resource.getAbleCvtBackupPath()).getParent().resolve("thirdparty_restore_capacity.py");
        Path capacityPlan = job.resolve("restore-capacity-plan.json");
        boolean reserved = false;
        boolean completed = false;
        try {
            Files.createDirectories(job);
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                throw new CloudRuntimeException("Restore staging destination already exists");
            }
            Map<String, Object> admission = new Gson().fromJson(new Gson().toJson(plan), Map.class);
            admission.put("stageDevice", Files.getAttribute(root, "unix:dev"));
            if (plan.primaryCapacityVersion == 1) {
                List<Long> sizes = new ArrayList<>();
                for (int i = 0; i < volumes.size(); i++) { sizes.add(targetBytes(i, volumes.get(i))); }
                var primary = LibvirtAblestackPrimaryRestoreCapacity.prepare(resource, job, plan, pools, targets, sizes);
                admission.put("primaryCapacityVersion", primary.version);
                admission.put("stagingStorageKey", primary.stagingStorageKey);
                admission.put("primaryClaims", primary.primaryClaims);
                admission.put("primaryScratchBytes", primary.primaryClaims.stream()
                        .filter(claim -> primary.stagingStorageKey.equals(claim.storageKey)).mapToLong(claim -> claim.requiredBytes).sum());
            } else {
                admission.put("primaryScratchBytes", validatePrimaryCapacity(resource.getStoragePoolMgr(), pools, targets, volumes, root));
            }
            atomic(capacityPlan, new Gson().toJson(admission));
            checkCancellation();
            capacity(capacityScript, capacityPlan, "prepare");
            LibvirtAblestackAsyncBackupRunner.markRestoreJobRunning(logger, provider, plan.jobId, vmName, plan.destination,
                    "Waiting for staging admission");
            step("WAITING", "Waiting for staging and primary storage capacity and an execution slot");
            progress("WAITING", 0, 0);
            long queueDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(plan.queueTimeout <= 0 ? 3600 : plan.queueTimeout);
            while (!Files.isRegularFile(job.resolve("staging-admission-granted"))) {
                checkCancellation();
                if (System.nanoTime() >= queueDeadline) {
                    capacity(capacityScript, capacityPlan, "cancel");
                }
                Thread.sleep(1000);
            }
            reserved = true;
            checkCancellation();
            Files.deleteIfExists(job.resolve("staging-admission-request.json"));
            deadline = plan.timeout <= 0 ? Long.MAX_VALUE : System.nanoTime() + TimeUnit.SECONDS.toNanos(plan.timeout);
            Files.createDirectories(destination.resolve("payload"));
            Files.createDirectories(destination.resolve("metadata"));
            atomic(destination.resolve(".restore-owner"), plan.jobId);
            if (!destination.toRealPath().startsWith(root.toRealPath())) {
                throw new CloudRuntimeException("Restore staging destination resolves outside the configured filesystem");
            }
            LibvirtAblestackAsyncBackupRunner.markRestoreJobRunning(logger, provider, plan.jobId, vmName, plan.destination,
                    "Volume restore started");
            if (plan.primaryCapacityVersion == 1) { LibvirtAblestackPrimaryRestoreCapacity.query(resource, job); }
            checkCancellation();
            LibvirtAblestackRestoreTransaction.restorePrepared(logger, trace, vmName, resource.getStoragePoolMgr(), pools, targets,
                    plan.timeout, () -> {
                        if (plan.primaryCapacityVersion == 1) { LibvirtAblestackPrimaryRestoreCapacity.validate(resource, job); }
                        else { validatePrimaryCapacity(resource.getStoragePoolMgr(), pools, targets, volumes, root); }
                    },
                    (index, prepared) -> {
                        if (plan.primaryCapacityVersion == 1) {
                            try { LibvirtAblestackPrimaryRestoreCapacity.query(resource, job); }
                            catch (IOException e) { throw new CloudRuntimeException("Restore primary storage identity cannot be rechecked", e); }
                        }
                        if (index == 0) {
                            try { restoreMetadata(); } catch (IOException e) { throw new CloudRuntimeException("Unable to read restored metadata", e); }
                        }
                        prepareVolume(resource.getStoragePoolMgr(), volumes.get(index), pools.get(index), targets.get(index), prepared, index);
                        if (index == volumes.size() - 1) {
                            checkCancellation();
                            step("SWITCH_VOLUMES", "Every restored volume is prepared; switching VM volumes");
                            try { progress("SWITCH_VOLUMES", volumes.size(), 85); }
                            catch (IOException e) { throw new CloudRuntimeException("Unable to save restore progress", e); }
                        }
                        return true;
                    }, plan);
            completed = true;
            try { progress("CLEANUP_SOURCE", plan.volumeUuids.size(), 95); }
            catch (IOException e) { logger.warn("VM volumes are committed; final progress could not be saved for job [{}]", plan.jobId, e); }
        } catch (Exception e) {
            throw e instanceof CloudRuntimeException ? (CloudRuntimeException) e : new CloudRuntimeException("Volume restore failed", e);
        } finally {
            // An external timeout can leave a writer active. Keep its directory and reservation for reconciliation.
            if (reserved && !transferUncertain) {
                try {
                    if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                        Path owner = destination.resolve(".restore-owner");
                        if (!Files.isRegularFile(owner, LinkOption.NOFOLLOW_LINKS) || !Files.readString(owner).trim().equals(plan.jobId)) {
                            throw new IOException("Restore staging directory ownership is unconfirmed; cleanup is retained");
                        }
                    }
                    LibvirtAblestackStagingCleanup.delete(plan.manifest.getProvider(), destination,
                            root.resolve(plan.manifest.getProvider()));
                    capacity(capacityScript, capacityPlan, "release");
                } catch (Exception e) { logger.warn("Restore staging cleanup is pending for job [{}]", plan.jobId, e); }
            }
            logger.info("{} phase=[VOLUME_RESTORE_DONE], job=[{}], committed=[{}], externalTransferUncertain=[{}]",
                    trace, plan.jobId, completed, transferUncertain);
        }
    }

    private long validatePrimaryCapacity(KVMStoragePoolManager manager, List<PrimaryDataStoreTO> pools, List<String> targets,
            List<ThirdPartyBackupManifest.Volume> volumes, Path stageRoot) {
        Map<String, Long> required = new HashMap<>();
        Map<String, Long> available = new HashMap<>();
        String stageStore;
        try {
            stageStore = "fs:" + Files.getFileStore(stageRoot).toString();
            for (int i = 0; i < targets.size(); i++) {
                String key;
                Long free;
                if (pools.get(i).getPoolType() == Storage.StoragePoolType.RBD) {
                    key = "rbd:" + pools.get(i).getUuid();
                    KVMStoragePool pool = manager.getStoragePool(pools.get(i).getPoolType(), pools.get(i).getUuid());
                    free = LibvirtAblestackRbdRestoreHelper.getCephPoolAvailableBytes(pool, plan.timeout);
                } else {
                    var store = Files.getFileStore(Path.of(targets.get(i)).toAbsolutePath().getParent());
                    key = "fs:" + store.toString();
                    free = store.getUsableSpace();
                }
                if (free == null) { throw new CloudRuntimeException("Cannot read restore primary capacity"); }
                required.merge(key, targetBytes(i, volumes.get(i)), Math::addExact);
                available.put(key, free);
            }
        } catch (IOException e) { throw new CloudRuntimeException("Cannot read restore filesystem capacity", e); }
        long sharedScratch = 0;
        for (Map.Entry<String, Long> entry : required.entrySet()) {
            long bytes = Math.addExact(entry.getValue(), Math.max(10L * 1024L * 1024L * 1024L, entry.getValue() / 5));
            if (entry.getKey().equals(stageStore)) { sharedScratch = bytes; }
            if (!entry.getKey().equals(stageStore) && available.get(entry.getKey()) < bytes) {
                throw new CloudRuntimeException("Insufficient primary capacity to retain original volumes: " + entry.getKey());
            }
        }
        return sharedScratch;
    }

    private void restoreMetadata() throws IOException {
        Path directory = fetch(plan.manifest.getMetadata(), true, -1, -1);
        Path file = directory.resolve(ThirdPartyBackupManifest.FILE_NAME);
        ThirdPartyBackupManifest restored = ThirdPartyBackupManifest.fromJson(Files.readString(file));
        if (!restored.getBackupUuid().equals(plan.manifest.getBackupUuid())
                || !restored.getProvider().equals(plan.manifest.getProvider())
                || !restored.getVmName().equals(plan.manifest.getVmName())
                || !new Gson().toJson(restored.getVolumes()).equals(new Gson().toJson(plan.manifest.getVolumes()))) {
            throw new CloudRuntimeException("Restored metadata does not match the selected volume catalog");
        }
    }

    private void prepareVolume(KVMStoragePoolManager manager, ThirdPartyBackupManifest.Volume volume, PrimaryDataStoreTO to,
            String original, String prepared, int index) {
        try {
            KVMStoragePool pool = to.getPoolType() == Storage.StoragePoolType.RBD ? manager.getStoragePool(to.getPoolType(), to.getUuid()) : null;
            String preparedUri = pool == null ? prepared : KVMPhysicalDisk.RBDStringBuilder(pool, prepared);
            QemuImg.PhysicalDiskFormat targetFormat = pool == null
                    ? LibvirtAblestackFileRestoreHelper.getFileVolumeFormat(logger, original) : QemuImg.PhysicalDiskFormat.RAW;
            String checkpoint = null;
            for (int chain = 0; chain < volume.chain.size(); chain++) {
                ThirdPartyBackupManifest.Artifact artifact = volume.chain.get(chain);
                Path file = fetch(artifact, false, index, chain);
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) != artifact.size) {
                    throw new CloudRuntimeException("Downloaded restore artifact is missing or has an unexpected size: " + file);
                }
                step("PREPARE_VOLUME", "Applying volume " + (index + 1) + "/" + plan.volumeUuids.size() + ", artifact " + (chain + 1));
                progress("PREPARE_VOLUME", index + 1, 15 + 65 * Math.max(0, sequence - 1) / artifactCount);
                if (chain == 0) {
                    if (file.toString().endsWith(".rbdiff")) { throw new CloudRuntimeException("Restore chain is missing its Full artifact"); }
                    QemuImg.PhysicalDiskFormat sourceFormat = file.toString().endsWith(".raw")
                            ? QemuImg.PhysicalDiskFormat.RAW : QemuImg.PhysicalDiskFormat.QCOW2;
                    QemuImg qemu = new QemuImg(TimeUnit.SECONDS.toMillis(plan.timeout));
                    qemu.convert(new QemuImgFile(file.toString(), sourceFormat), new QemuImgFile(preparedUri, targetFormat));
                    if (volume.engine.startsWith("RBD") && volume.chain.size() > 1) {
                        LibvirtAblestackRbdRestoreHelper.createPreparedCheckpoint(pool, prepared, artifact.checkpointName, plan.timeout);
                        checkpoint = artifact.checkpointName;
                    }
                } else if (file.toString().endsWith(".rbdiff")) {
                    if (!java.util.Objects.equals(checkpoint, artifact.parentCheckpointName)) {
                        throw new CloudRuntimeException("RBD incremental restore chain has a checkpoint gap");
                    }
                    LibvirtAblestackRbdRestoreHelper.applyStagedDiff(pool, prepared, file.toString(), artifact.parentCheckpointName,
                            artifact.checkpointName, plan.timeout);
                    checkpoint = artifact.checkpointName;
                } else {
                    // The downloaded overlay belongs exclusively to this restore job; no second staging copy is needed.
                    run("qemu-img rebase -u -f qcow2 -F " + targetFormat.toString().toLowerCase(java.util.Locale.ROOT)
                            + " -b " + quote(preparedUri) + " " + quote(file.toString()));
                    run("qemu-img commit -f qcow2 " + quote(file.toString()));
                }
                Files.delete(file);
                progress("PREPARE_VOLUME", index + 1, 15 + 65 * sequence / artifactCount);
            }
            if (checkpoint != null) { LibvirtAblestackRbdRestoreHelper.removePreparedCheckpoint(pool, prepared, checkpoint, plan.timeout); }
            Map<String, String> preparedInfo = new QemuImg(TimeUnit.SECONDS.toMillis(plan.timeout))
                    .info(new QemuImgFile(preparedUri, targetFormat));
            if (Long.parseLong(preparedInfo.get(QemuImg.VIRTUAL_SIZE)) != volume.provisionedBytes) {
                throw new CloudRuntimeException("Prepared restore volume size differs from the manifest");
            }
            long targetBytes = targetBytes(index, volume);
            if (targetBytes > volume.provisionedBytes) {
                run("qemu-img resize -f " + targetFormat.toString().toLowerCase(java.util.Locale.ROOT) + " "
                        + quote(preparedUri) + " " + targetBytes);
                Map<String, String> resizedInfo = new QemuImg(TimeUnit.SECONDS.toMillis(plan.timeout))
                        .info(new QemuImgFile(preparedUri, targetFormat));
                if (Long.parseLong(resizedInfo.get(QemuImg.VIRTUAL_SIZE)) != targetBytes) {
                    throw new CloudRuntimeException("Prepared restore volume does not retain the current provisioned capacity");
                }
            }
            if (pool == null) {
                try (FileChannel channel = FileChannel.open(Path.of(prepared), StandardOpenOption.WRITE)) { channel.force(true); }
            }
        } catch (Exception e) { throw new CloudRuntimeException("Unable to prepare restored volume " + volume.uuid, e); }
    }

    private long targetBytes(int index, ThirdPartyBackupManifest.Volume volume) {
        return plan.targetVolumeBytes == null ? volume.provisionedBytes : plan.targetVolumeBytes.get(index);
    }

    private Path fetch(ThirdPartyBackupManifest.Artifact artifact, boolean metadata, int volume, int chain) throws IOException {
        checkCancellation();
        ThirdPartyBackupRestore.Request request = new ThirdPartyBackupRestore.Request();
        request.jobId = plan.jobId;
        request.sequence = sequence++;
        request.volumeIndex = volume;
        request.chainIndex = chain;
        request.metadata = metadata;
        request.artifact = artifact;
        Path destination = Path.of(plan.destination, metadata ? "metadata" : "payload").resolve(Path.of(artifact.path).getFileName());
        request.destination = destination.toString();
        Path acknowledgment = job.resolve("volume-restore-ack-" + request.sequence + ".json");
        transferUncertain = true;
        step(provider.toUpperCase(java.util.Locale.ROOT) + "_RESTORE", metadata ? "Restoring backup metadata" : "Fetching volume " + (volume + 1));
        progress(provider.toUpperCase(java.util.Locale.ROOT) + "_RESTORE", metadata ? 1 : volume + 1,
                15 + 65 * request.sequence / artifactCount);
        atomic(job.resolve("volume-restore-request.json"), new Gson().toJson(request));
        while (!Files.isRegularFile(acknowledgment)) {
            checkCancellation();
            Path failure = job.resolve("volume-restore-failure");
            if (Files.isRegularFile(failure)) {
                Map<?, ?> result = new Gson().fromJson(Files.readString(failure), Map.class);
                transferUncertain = !Boolean.TRUE.equals(result.get("terminal"));
                throw new CloudRuntimeException(String.valueOf(result.get("message")));
            }
            if (System.nanoTime() >= deadline) { throw new CloudRuntimeException("Timed out waiting for external volume restore"); }
            try { Thread.sleep(1000); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CloudRuntimeException("Volume restore interrupted", e);
            }
        }
        if (!new Gson().toJson(request).equals(Files.readString(acknowledgment))) {
            throw new CloudRuntimeException("Restore transfer acknowledgment does not match its request");
        }
        transferUncertain = false;
        Files.deleteIfExists(job.resolve("volume-restore-request.json"));
        return destination;
    }

    private void step(String step, String detail) {
        LibvirtAblestackAsyncBackupRunner.markRestoreJobStep(logger, provider, plan.jobId, targetVmName, plan.destination, step, detail);
    }

    private void checkCancellation() {
        for (String file : List.of("volume-restore-cancel", "staging-admission-cancel")) {
            Path marker = job.resolve(file);
            if (Files.isRegularFile(marker)) {
                try { throw new CloudRuntimeException(Files.readString(marker)); }
                catch (IOException e) { throw new CloudRuntimeException("Restore cancellation marker could not be read", e); }
            }
        }
    }

    private void progress(String step, int volume, int percent) throws IOException {
        java.util.Properties state = new java.util.Properties();
        state.setProperty("step", step);
        state.setProperty("volumeIndex", String.valueOf(volume));
        state.setProperty("volumeCount", String.valueOf(plan.volumeUuids.size()));
        state.setProperty("progress", String.valueOf(percent));
        java.io.StringWriter text = new java.io.StringWriter();
        state.store(text, null);
        atomic(job.resolve("volume-progress.properties"), text.toString());
    }

    private void capacity(Path script, Path file, String action) {
        if (!Files.isRegularFile(script)) { throw new CloudRuntimeException("Restore capacity helper is not installed on the Host"); }
        run("python3 " + quote(script.toString()) + " --plan-file " + quote(file.toString()) + " --action " + action);
    }

    private void run(String command) {
        if (Script.runSimpleBashScriptForExitValue(command, plan.timeout * 1000, false) != 0) {
            throw new CloudRuntimeException("Volume restore preparation command failed");
        }
    }

    static void atomic(Path file, String json) throws IOException {
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, json);
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
        Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String quote(String value) { return "'" + value.replace("'", "'\"'\"'") + "'"; }
}
