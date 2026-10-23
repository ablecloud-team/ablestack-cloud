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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.apache.cloudstack.backup.AblestackBackupFrameworkUtils;
import org.apache.cloudstack.backup.BackupVolumeAllocation;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import com.cloud.hypervisor.kvm.storage.Gfs2VolumeAllocation;
import com.cloud.hypervisor.kvm.storage.RbdVolumeAllocation;
import com.cloud.hypervisor.kvm.storage.KVMStoragePool;
import com.cloud.hypervisor.kvm.storage.KVMStoragePoolManager;
import com.cloud.hypervisor.kvm.storage.KVMPhysicalDisk;
import com.cloud.storage.Storage;
import com.cloud.storage.VolumeAllocationPolicy;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.Gson;

/** Reuse qualified copy adapters after reconstructing the entire Full/incremental chain. */
final class LibvirtAblestackRestoreAllocation {
    private static final Gson JSON = new Gson();
    private static final Path ROOT = Path.of(AblestackBackupFrameworkUtils.ASYNC_BACKUP_JOB_ROOT).getParent().resolve("allocation-results");
    private LibvirtAblestackRestoreAllocation() { }

    static KVMStoragePool pool(KVMStoragePoolManager manager, BackupVolumeAllocation policy) {
        policy.validate();
        KVMStoragePool pool = manager.getStoragePool("RBD".equals(policy.backend) ? Storage.StoragePoolType.RBD
                : Storage.StoragePoolType.SharedMountPoint, policy.poolUuid);
        if (pool == null || !policy.poolUuid.equals(pool.getUuid())) throw new CloudRuntimeException("Restore allocation pool is unavailable");
        return pool;
    }

    static void check(KVMStoragePoolManager manager, Map<String, BackupVolumeAllocation> policies) throws IOException {
        if (policies == null) return;
        Map<String, Map<String, String>> verified = new LinkedHashMap<>();
        for (var entry : policies.entrySet()) {
            BackupVolumeAllocation policy = entry.getValue();
            KVMStoragePool pool = pool(manager, policy);
            boolean rbd = "RBD".equals(policy.backend);
            if (!rbd && !Path.of(entry.getKey()).toAbsolutePath().getParent().toRealPath().equals(Path.of(pool.getLocalPath()).toRealPath())) {
                throw new CloudRuntimeException("Restore allocation destination is outside its primary pool");
            }
            String capabilityKey = policy.backend + ":" + policy.poolUuid + ":" + policy.format;
            Map<String, String> methods = verified.computeIfAbsent(capabilityKey, key -> rbd
                    ? new RbdVolumeAllocation().capabilities(pool, policy.format, "copy")
                    : new Gfs2VolumeAllocation().capabilities(pool, policy.format, "copy"));
            VolumeAllocationPolicy.validateCapability(policy.backend, policy.format, "copy", methods);
            if (!methods.containsKey(policy.intent.get(VolumeAllocationPolicy.POLICY))) throw new CloudRuntimeException("Requested restore allocation is unavailable");
        }
    }

    static Map<String, String> materialize(KVMStoragePoolManager manager, BackupVolumeAllocation policy,
            String sourcePath, String destinationUuid, int timeout) {
        KVMStoragePool pool = pool(manager, policy);
        boolean rbd = "RBD".equals(policy.backend);
        // Transactions use image names; the allocation adapter requires the authenticated pool/image identity.
        String allocationSource = rbd && !sourcePath.startsWith(pool.getSourceDir() + "/")
                ? pool.getSourceDir() + "/" + sourcePath : sourcePath;
        KVMPhysicalDisk source = new KVMPhysicalDisk(allocationSource, allocationSource, pool);
        source.setFormat(rbd ? org.apache.cloudstack.utils.qemu.QemuImg.PhysicalDiskFormat.RAW
                : LibvirtAblestackFileRestoreHelper.getFileVolumeFormat(org.apache.logging.log4j.LogManager.getLogger(LibvirtAblestackRestoreAllocation.class), sourcePath));
        source.setVirtualSize(policy.bytes);
        VolumeObjectTO request = new VolumeObjectTO();
        request.setUuid(destinationUuid); request.setVolumeId(policy.volumeId); request.setAccountId(policy.accountId);
        request.setSize(policy.bytes); request.setFormat(Storage.ImageFormat.valueOf(policy.format.toUpperCase(java.util.Locale.ROOT)));
        request.setAllocation(policy.intent);
        long timeoutMillis = TimeUnit.SECONDS.toMillis(timeout > 0 ? timeout : 43200);
        return (rbd ? new RbdVolumeAllocation().create(request, pool, source, timeoutMillis)
                : new Gfs2VolumeAllocation().create(request, pool, source, timeoutMillis)).getAllocation();
    }

    static String allocatedPath(KVMStoragePoolManager manager, BackupVolumeAllocation policy, String uuid) {
        KVMStoragePool pool = pool(manager, policy);
        return "RBD".equals(policy.backend) ? uuid : Path.of(pool.getLocalPath()).resolve(uuid).toString();
    }

    static void publish(Properties state) throws IOException {
        String jobId = state.getProperty("allocation.job");
        if (jobId == null || !"COMMITTED".equals(state.getProperty("phase"))) return;
        BackupVolumeAllocation.Result result = new BackupVolumeAllocation.Result();
        result.jobId = jobId;
        int count = Integer.parseInt(state.getProperty("count"));
        for (int i = 0; i < count; i++) {
            String request = state.getProperty(i + ".allocation.request");
            if (request == null) continue;
            String path = state.getProperty(i + ".allocation.path");
            String evidence = state.getProperty(i + ".allocation.evidence");
            if (evidence == null || !Boolean.parseBoolean(state.getProperty(i + ".switched"))) throw new IOException("Allocation was not verified and committed");
            result.policies.put(path, JSON.fromJson(request, BackupVolumeAllocation.class));
            result.evidence.put(path, JSON.fromJson(evidence, new com.google.gson.reflect.TypeToken<Map<String, String>>() { }.getType()));
        }
        Files.createDirectories(resultDirectory());
        Path receipt = receipt(jobId);
        if (Files.exists(receipt) && !Files.readString(receipt).equals(JSON.toJson(result))) throw new IOException("Committed allocation receipt changed");
        LibvirtAblestackVolumeRestoreHelper.atomic(receipt, JSON.toJson(result));
        try (var directory = java.nio.channels.FileChannel.open(resultDirectory(), java.nio.file.StandardOpenOption.READ)) { directory.force(true); }
    }

    static Path resultDirectory() { return ROOT; }

    private static Path receipt(String jobId) {
        if (jobId == null || !jobId.matches("[A-Za-z0-9_.-]+") || jobId.equals(".") || jobId.equals("..")) throw new CloudRuntimeException("Invalid allocation job ID");
        return resultDirectory().resolve(jobId + ".json");
    }
    static BackupVolumeAllocation.Result read(String jobId) throws IOException {
        Path receipt = receipt(jobId);
        if (!Files.isRegularFile(receipt, java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw new IOException("Committed allocation receipt is missing");
        return JSON.fromJson(Files.readString(receipt), BackupVolumeAllocation.Result.class);
    }
}
