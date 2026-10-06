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
    private final ThirdPartyBackupRestore.Plan plan;
    private final Path job;
    private final Logger logger;
    private final String provider;
    private final String trace;
    private final long deadline;
    private int sequence;
    private boolean transferUncertain;

    private LibvirtAblestackVolumeRestoreHelper(ThirdPartyBackupRestore.Plan plan, Logger logger) {
        this.plan = plan;
        this.logger = logger;
        provider = plan.manifest.getProvider().substring("ablestack-".length());
        trace = AblestackBackupFrameworkUtils.buildTracePrefix(provider, AblestackBackupFrameworkUtils.OPERATION_RESTORE);
        job = Path.of(AblestackBackupFrameworkUtils.ASYNC_BACKUP_JOB_ROOT, plan.jobId);
        deadline = plan.timeout <= 0 ? Long.MAX_VALUE : System.nanoTime() + TimeUnit.SECONDS.toNanos(plan.timeout);
    }

    static void restore(LibvirtComputingResource resource, Logger logger, ThirdPartyBackupRestore.Plan plan,
            String vmName, List<PrimaryDataStoreTO> pools, List<String> targets) {
        new LibvirtAblestackVolumeRestoreHelper(plan, logger).execute(resource, vmName, pools, targets);
    }

    private void execute(LibvirtComputingResource resource, String vmName, List<PrimaryDataStoreTO> pools, List<String> targets) {
        plan.manifest.validate(true);
        if (!plan.jobId.matches("[A-Za-z0-9_.-]+") || !plan.manifest.getVmName().equals(vmName)
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
        for (int i = 0; i < volumes.size(); i++) {
            if (volumes.get(i).chain.size() > 1 && "RBD".equals(volumes.get(i).engine)
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
            admission.put("primaryScratchBytes", validatePrimaryCapacity(resource.getStoragePoolMgr(), pools, targets, volumes, root));
            atomic(capacityPlan, new Gson().toJson(admission));
            capacity(capacityScript, capacityPlan, "reserve");
            reserved = true;
            Files.createDirectories(destination.resolve("payload"));
            Files.createDirectories(destination.resolve("metadata"));
            LibvirtAblestackAsyncBackupRunner.markRestoreJobRunning(logger, provider, plan.jobId, vmName, plan.destination,
                    "Volume restore started");
            LibvirtAblestackRestoreTransaction.restorePrepared(logger, trace, vmName, resource.getStoragePoolMgr(), pools, targets,
                    plan.timeout, () -> validatePrimaryCapacity(resource.getStoragePoolMgr(), pools, targets, volumes, root),
                    (index, prepared) -> {
                        if (index == 0) {
                            try { restoreMetadata(); } catch (IOException e) { throw new CloudRuntimeException("Unable to read restored metadata", e); }
                        }
                        prepareVolume(resource.getStoragePoolMgr(), volumes.get(index), pools.get(index), targets.get(index), prepared, index);
                        return true;
                    });
            completed = true;
        } catch (Exception e) {
            throw e instanceof CloudRuntimeException ? (CloudRuntimeException) e : new CloudRuntimeException("Volume restore failed", e);
        } finally {
            // An external timeout can leave a writer active. Keep its directory and reservation for reconciliation.
            if (reserved && !transferUncertain) {
                try {
                    org.apache.commons.io.FileUtils.deleteDirectory(destination.toFile());
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
                required.merge(key, volumes.get(i).provisionedBytes, Math::addExact);
                available.put(key, free);
            }
        } catch (IOException e) { throw new CloudRuntimeException("Cannot read restore filesystem capacity", e); }
        long sharedScratch = 0;
        for (Map.Entry<String, Long> entry : required.entrySet()) {
            long bytes = Math.addExact(entry.getValue(), Math.max(10L * 1024L * 1024L * 1024L, entry.getValue() / 5));
            if (entry.getKey().equals(stageStore)) { sharedScratch = bytes; }
            if (available.get(entry.getKey()) < bytes) {
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
                if (chain == 0) {
                    if (file.toString().endsWith(".rbdiff")) { throw new CloudRuntimeException("Restore chain is missing its Full artifact"); }
                    QemuImg.PhysicalDiskFormat sourceFormat = file.toString().endsWith(".raw")
                            ? QemuImg.PhysicalDiskFormat.RAW : QemuImg.PhysicalDiskFormat.QCOW2;
                    QemuImg qemu = new QemuImg(plan.timeout);
                    qemu.convert(new QemuImgFile(file.toString(), sourceFormat), new QemuImgFile(preparedUri, targetFormat));
                    if ("RBD".equals(volume.engine) && volume.chain.size() > 1) {
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
            }
            if (checkpoint != null) { LibvirtAblestackRbdRestoreHelper.removePreparedCheckpoint(pool, prepared, checkpoint, plan.timeout); }
            if (pool == null) {
                try (FileChannel channel = FileChannel.open(Path.of(prepared), StandardOpenOption.WRITE)) { channel.force(true); }
            }
        } catch (Exception e) { throw new CloudRuntimeException("Unable to prepare restored volume " + volume.uuid, e); }
    }

    private Path fetch(ThirdPartyBackupManifest.Artifact artifact, boolean metadata, int volume, int chain) throws IOException {
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
        atomic(job.resolve("volume-restore-request.json"), new Gson().toJson(request));
        while (!Files.isRegularFile(acknowledgment)) {
            Path failure = job.resolve("volume-restore-failure");
            if (Files.isRegularFile(failure)) { throw new CloudRuntimeException(Files.readString(failure)); }
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
        LibvirtAblestackAsyncBackupRunner.markRestoreJobStep(logger, provider, plan.jobId, plan.manifest.getVmName(), plan.destination, step, detail);
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
