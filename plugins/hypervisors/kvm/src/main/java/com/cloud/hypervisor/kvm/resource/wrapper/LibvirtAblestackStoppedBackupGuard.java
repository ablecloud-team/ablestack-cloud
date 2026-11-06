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
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.cloudstack.backup.AblestackBackupFrameworkUtils;
import org.apache.cloudstack.backup.ThirdPartyBackupStart;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.common.collect.MapMaker;
import com.google.gson.Gson;

/** The Agent and Python engine share a per-VM POSIX lock and durable source ownership. */
final class LibvirtAblestackStoppedBackupGuard {
    private static final Path ROOT = Path.of(AblestackBackupFrameworkUtils.BACKUP_START_ROOT, "source-guards");
    private static final ConcurrentMap<String, ReentrantLock> LOCKS = new MapMaker().weakValues().makeMap();
    interface Lease extends AutoCloseable { @Override void close(); }

    private LibvirtAblestackStoppedBackupGuard() { }

    static Lease start(String vm) { return lock(ROOT, vm, true); }

    static Lease lock(Path root, String vm, boolean starting) {
        if (vm == null || !vm.matches("[A-Za-z0-9_.-]+") || Set.of(".", "..").contains(vm)) {
            throw new CloudRuntimeException("Stopped backup protection requires a valid VM identity");
        }
        Path directory = root.resolve(vm);
        ReentrantLock local = LOCKS.computeIfAbsent(directory.toString(), key -> new ReentrantLock());
        if (local.isHeldByCurrentThread() || !local.tryLock()) {
            throw new CloudRuntimeException("VM start or backup source preparation is in progress; retry shortly");
        }
        FileChannel channel = null;
        FileLock fence = null;
        try {
            Files.createDirectories(directory);
            channel = FileChannel.open(directory.resolve("start.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            fence = channel.tryLock();
            if (fence == null) { throw new CloudRuntimeException("VM start or backup source preparation is in progress; retry shortly"); }
            if (starting) {
                try (var files = Files.list(directory)) {
                    for (Path file : (Iterable<Path>) files.filter(path -> path.getFileName().toString().endsWith(".json"))::iterator) {
                        ThirdPartyBackupStart.SourceProof proof = new Gson().fromJson(Files.readString(file), ThirdPartyBackupStart.SourceProof.class);
                        if (proof == null || proof.plan == null || (proof.plan.manifest == null && !proof.plan.validNasSource())
                                || !vm.equals(proof.plan.sourceVmName())) {
                            throw new CloudRuntimeException("VM backup source identity is unconfirmed");
                        }
                        if (proof.plan.manifest != null && proof.plan.manifest.getOfflineGuard() != null) {
                            // Shared RAW has its own DB ownership and cluster file lock on every StartCommand.
                            proof.plan.manifest.validate(false);
                            continue;
                        }
                        if (!released(proof)) {
                            throw new CloudRuntimeException("VM start is blocked until backup source preparation or dummy cleanup is confirmed");
                        }
                    }
                }
            }
            FileLock held = fence;
            FileChannel opened = channel;
            return () -> {
                try { held.release(); opened.close(); }
                catch (IOException e) { throw new CloudRuntimeException("Unable to release VM start protection", e); }
                finally { local.unlock(); }
            };
        } catch (IOException | RuntimeException e) {
            try { if (fence != null) { fence.release(); } }
            catch (IOException ignored) { }
            try { if (channel != null) { channel.close(); } }
            catch (IOException ignored) { }
            local.unlock();
            throw new CloudRuntimeException("VM backup source protection is unconfirmed", e);
        }
    }

    private static boolean released(ThirdPartyBackupStart.SourceProof proof) {
        boolean rbd = proof.plan.sourceIsRbd();
        return proof.version == ThirdPartyBackupStart.VERSION && proof.released
                && ("SOURCE_CLEANED".equals(proof.phase) || "UNSTARTED_FENCED".equals(proof.phase)
                        || (rbd && "SNAPSHOTS_COMPLETE".equals(proof.phase)));
    }

    private static Path record(Path root, ThirdPartyBackupStart.Plan plan) {
        if (plan == null || !plan.protectStoppedSource || plan.jobId == null || !plan.jobId.matches("[A-Za-z0-9-]+")
                || (plan.nasSource != null ? !plan.validNasSource() : plan.manifest == null || !plan.jobId.equals(plan.manifest.getBackupUuid()))) {
            throw new CloudRuntimeException("Invalid stopped backup source plan");
        }
        return root.resolve(plan.sourceVmName()).resolve(plan.jobId + ".json");
    }

    static void prepare(ThirdPartyBackupStart.Plan plan) throws IOException { prepare(ROOT, plan); }

    static void prepare(Path root, ThirdPartyBackupStart.Plan plan) throws IOException {
        if (!plan.protectStoppedSource) { return; }
        try (Lease lease = lock(root, plan.sourceVmName(), false)) {
            Path file = record(root, plan);
            if (Files.exists(file)) {
                ThirdPartyBackupStart.SourceProof proof = read(root, plan);
                if (proof.released) { throw new CloudRuntimeException("Released backup source cannot be prepared again"); }
                return;
            }
            ThirdPartyBackupStart.SourceProof proof = new ThirdPartyBackupStart.SourceProof();
            proof.plan = plan;
            proof.phase = "PROTECTED";
            write(file, proof);
        }
    }

    private static ThirdPartyBackupStart.SourceProof read(Path root, ThirdPartyBackupStart.Plan plan) throws IOException {
        ThirdPartyBackupStart.SourceProof proof = new Gson().fromJson(Files.readString(record(root, plan)), ThirdPartyBackupStart.SourceProof.class);
        if (proof == null || proof.version != ThirdPartyBackupStart.VERSION || proof.plan == null
                || !new Gson().toJsonTree(plan).equals(new Gson().toJsonTree(proof.plan))
                || (proof.released ? !released(proof) : !"PROTECTED".equals(proof.phase))) {
            throw new CloudRuntimeException("Stopped backup source ownership differs from the dispatch plan");
        }
        return proof;
    }

    static ThirdPartyBackupStart.SourceProof status(ThirdPartyBackupStart.Plan plan) throws IOException {
        return status(ROOT, plan);
    }

    static ThirdPartyBackupStart.SourceProof status(Path root, ThirdPartyBackupStart.Plan plan) throws IOException {
        try (Lease lease = lock(root, plan.sourceVmName(), false)) { return read(root, plan); }
    }

    static void abort(ThirdPartyBackupStart.Plan plan) throws IOException {
        abort(ROOT, plan);
    }

    static void abort(Path root, ThirdPartyBackupStart.Plan plan) throws IOException {
        if (!plan.protectStoppedSource) { return; }
        try (Lease lease = lock(root, plan.sourceVmName(), false)) {
            ThirdPartyBackupStart.SourceProof proof = read(root, plan);
            if (proof.released) { return; }
            proof.released = true;
            proof.phase = "UNSTARTED_FENCED";
            write(record(root, plan), proof);
        }
    }

    private static void write(Path file, ThirdPartyBackupStart.SourceProof proof) throws IOException {
        LibvirtAblestackVolumeRestoreHelper.atomic(file, new Gson().toJson(proof));
        try (FileChannel directory = FileChannel.open(file.getParent(), StandardOpenOption.READ)) { directory.force(true); }
    }
}
