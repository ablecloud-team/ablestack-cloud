// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

package com.cloud.hypervisor.kvm.resource.wrapper;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.storage.Storage;
import com.cloud.utils.script.Script;
import com.google.gson.Gson;
import org.apache.cloudstack.backup.AblestackSharedRawBackup;
import org.apache.cloudstack.backup.BackupVolumeAllocation;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;

/** GFS2 record locks interoperate with Python fcntl.lockf, including across cluster Hosts. */
final class LibvirtAblestackSharedRawGuard {
    private static final Gson JSON = new Gson();
    private LibvirtAblestackSharedRawGuard() { }

    static Path resolve(LibvirtComputingResource resource, PrimaryDataStoreTO pool, String name) throws Exception {
        if (pool == null || pool.getPoolType() != Storage.StoragePoolType.SharedMountPoint || name == null)
            throw new IllegalArgumentException("SHARED_RAW_UNKNOWN: a file-backed shared pool is required");
        return resolve(resource, pool.getUuid(), name);
    }

    private static Path resolve(LibvirtComputingResource resource, String poolUuid, String name) throws Exception {
        var storage = resource.getStoragePoolMgr().getStoragePool(Storage.StoragePoolType.SharedMountPoint, poolUuid);
        Path root = Path.of(storage.getLocalPath()).toRealPath();
        Path path = Path.of(name);
        if (!path.isAbsolute()) path = root.resolve(path);
        if (!path.getParent().toRealPath().equals(root) || Files.isSymbolicLink(path)
                || !path.getFileName().toString().matches("[A-Za-z0-9][A-Za-z0-9_.-]*"))
            throw new IllegalArgumentException("SHARED_RAW_UNKNOWN: disk identity escapes its pool or is non-canonical");
        return root.resolve(path.getFileName());
    }

    static List<String> paths(LibvirtComputingResource resource, AblestackSharedRawBackup.Plan plan) throws Exception {
        if (plan == null || plan.version != 1 || plan.disks == null || plan.disks.isEmpty()
                || plan.jobId == null || !plan.jobId.matches("[A-Za-z0-9_.-]+")) throw new IllegalArgumentException("Invalid offline disk ownership");
        List<String> result = new ArrayList<>();
        for (var disk : plan.disks) {
            result.add(resolve(resource, disk.poolUuid, disk.path).toString());
        }
        return result;
    }

    static void control(LibvirtComputingResource resource, String action, AblestackSharedRawBackup.Plan plan) throws Exception {
        List<String> sources = paths(resource, plan);
        Path helper = Path.of(resource.getAbleCvtBackupPath()).getParent().resolve("ablestack_shared_raw.py");
        if (!Files.isRegularFile(helper)) throw new IllegalArgumentException("Offline RAW helper is not installed");
        Path directory = Path.of("/var/lib/ablestack/backup/shared-raw");
        Files.createDirectories(directory);
        Path file = Files.createTempFile(directory, plan.jobId + "-", ".json", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            Files.writeString(file, JSON.toJson(Map.of("plan", plan, "paths", sources)), StandardCharsets.UTF_8);
            var answer = Script.executePipedCommands(List.<String[]>of(new String[]{"python3", helper.toString(), "--action", action, "--plan-file", file.toString()}), TimeUnit.SECONDS.toMillis(120));
            if (answer.first() != 0) throw new IllegalStateException("Offline RAW " + action + " was not confirmed: " + answer.second());
        } finally { Files.deleteIfExists(file); }
    }

    static final class Lease implements AutoCloseable {
        private final List<FileLock> locks = new ArrayList<>();
        private final List<FileChannel> files = new ArrayList<>();
        @Override public void close() {
            java.util.Collections.reverse(locks); java.util.Collections.reverse(files);
            for (FileLock lock : locks) try { lock.release(); } catch (Exception ignored) { }
            for (FileChannel file : files) try { file.close(); } catch (Exception ignored) { }
        }
    }

    static Lease acquire(List<String> paths, String expectedJob) throws Exception {
        Lease lease = new Lease();
        try {
            for (String source : new TreeSet<>(paths)) {
                Path path = Path.of(source);
                Path directory = path.getParent().toRealPath().resolve(".ablestack-offline-raw");
                if (Files.isSymbolicLink(path) || Files.isSymbolicLink(directory)) throw new IllegalStateException("Non-canonical RAW guard path");
                Files.createDirectories(directory);
                StringBuilder hash = new StringBuilder();
                for (byte value : MessageDigest.getInstance("SHA-256").digest(path.getFileName().toString().getBytes(StandardCharsets.UTF_8)))
                    hash.append(String.format("%02x", value & 255));
                FileChannel file = FileChannel.open(directory.resolve(hash + ".lock"), StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                lease.files.add(file);
                FileLock lock = file.tryLock();
                if (lock == null) throw new IllegalStateException("SHARED_RAW_BUSY: an offline file operation owns this disk");
                lease.locks.add(lock);
                Path marker = directory.resolve(hash + ".json");
                if (Files.isSymbolicLink(marker)) throw new IllegalStateException("Invalid shared RAW guard marker");
                AblestackSharedRawBackup.Plan owner = Files.exists(marker) ? JSON.fromJson(Files.readString(marker), AblestackSharedRawBackup.Plan.class) : null;
                if (expectedJob == null ? owner != null : owner == null || owner.version != 1 || !expectedJob.equals(owner.jobId) || !"RESTORE".equals(owner.operation))
                    throw new IllegalStateException("SHARED_RAW_PROTECTED: disk ownership does not permit this operation");
            }
            return lease;
        } catch (Exception failure) { lease.close(); throw failure; }
    }

    static Lease restore(LibvirtComputingResource resource, String job, boolean wait, List<PrimaryDataStoreTO> pools,
            List<String> paths, Map<String, BackupVolumeAllocation> policies) throws Exception {
        if (!wait || policies == null || policies.values().stream().noneMatch(policy -> "GFS2".equals(policy.backend) && "raw".equals(policy.format))) return new Lease();
        List<String> sources = new ArrayList<>();
        for (int i = 0; i < paths.size(); i++) sources.add(resolve(resource, pools.get(i), paths.get(i)).toString());
        return acquire(sources, job);
    }
}
