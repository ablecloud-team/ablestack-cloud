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
package com.cloud.hypervisor.kvm.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import com.cloud.agent.api.ImportVolumeAllocationCommand;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.storage.Storage;
import com.cloud.storage.VolumeAllocationImport;
import com.cloud.storage.VolumeAllocationPolicy;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.apache.cloudstack.storage.volume.VolumeOnStorageTO;
import org.apache.cloudstack.utils.qemu.QemuImg.PhysicalDiskFormat;

/** Import-time evidence. Adoption never asserts which historical create command ran. */
public class Gfs2VolumeImportAllocation extends Gfs2VolumeAllocation {
    protected Gfs2VolumeAllocation copyAdapter() { return new Gfs2VolumeAllocation(); }
    protected String run(List<String> args, long timeout) { return Gfs2AllocationCommandRunner.run(args, timeout); }

    public Map<String, String> importCapabilities(KVMStoragePool pool, String format) {
        if (copyAdapter().capabilities(pool, format, "copy").isEmpty()) return Map.of();
        return VolumeAllocationImport.methods(format);
    }

    public static final class Result {
        public final VolumeOnStorageTO volume;
        public final Map<String, String> allocation;
        Result(VolumeOnStorageTO volume, Map<String, String> allocation) { this.volume = volume; this.allocation = allocation; }
    }

    public Result execute(ImportVolumeAllocationCommand command, KVMStoragePool pool) {
        if (command.getContractVersion() != 1 || command.getPool() == null || pool == null ||
                !pool.getUuid().equals(command.getPool().getUuid()) ||
                !List.of("IMPORT", "VERIFY").contains(command.getAction()) || command.getSize() <= 0) {
            throw new CloudRuntimeException("ALLOCATION_IMPORT_UNSUPPORTED: invalid import scope");
        }
        Map<String, String> intent = VolumeAllocationPolicy.validateStored(command.getAllocation());
        String policy = intent.get(VolumeAllocationPolicy.POLICY), format = intent.get(VolumeAllocationPolicy.FORMAT);
        VolumeAllocationImport.expected(intent, policy, format, 1, command.getRequestId());
        if (VolumeAllocationImport.requiresCopy(policy) && !command.isAllowCopy()) {
            throw new CloudRuntimeException("ALLOCATION_EVIDENCE_REQUIRED: metadata/full require explicitly approved offline allocation copy");
        }
        long timeout = Math.max(1, command.getWait()) * 1000L;
        try {
            Path directory = root(pool);
            Path source = resolve(directory, command.getSourcePath());
            BasicFileAttributes sourceBefore = attributes(source);
            JsonObject sourceInfo = inspect(source, format, command.getSize(), timeout);
            String strategy = VolumeAllocationImport.requiresCopy(policy) ? "copy" : "adopt";
            Path target = source;
            Map<String, String> evidence;
            if ("copy".equals(strategy)) {
                // Reuse the qualified owned-stage/copy/compare/atomic-publish adapter.
                // Its durable request receipt makes a lost-answer retry reverify the same inode.
                VolumeObjectTO request = new VolumeObjectTO();
                request.setUuid(command.getRequestId()); request.setVolumeId(0L); request.setAccountId(command.getAccountId());
                request.setSize(command.getSize()); request.setAllocation(intent);
                request.setFormat(Storage.ImageFormat.valueOf(format.toUpperCase(Locale.ROOT)));
                KVMPhysicalDisk original = new KVMPhysicalDisk(source.toString(), source.getFileName().toString(), pool);
                original.setFormat(PhysicalDiskFormat.valueOf(format.toUpperCase(Locale.ROOT))); original.setVirtualSize(command.getSize());
                VolumeObjectTO copied = "VERIFY".equals(command.getAction()) ? copyAdapter().verifyCopy(request, pool, original, timeout)
                        : copyAdapter().create(request, pool, original, timeout);
                target = resolve(directory, copied.getPath());
                evidence = new LinkedHashMap<>(copied.getAllocation());
            } else {
                long used = allocated(source, sourceInfo, command.getSize(), policy, timeout);
                evidence = VolumeAllocationPolicy.evidence(intent, "GFS2", "import", "import-adopt-" + policy,
                        used, Instant.now(), "stat-st-blocks-times-512");
            }
            BasicFileAttributes targetBefore = attributes(target);
            JsonObject finalInfo = inspect(target, format, command.getSize(), timeout);
            long used = allocated(target, finalInfo, command.getSize(), policy, timeout);
            String relative = directory.relativize(target).toString();
            String identity = identity(pool, directory, target, command.getSize(), timeout);
            if ("VERIFY".equals(command.getAction()) &&
                    (!relative.equals(command.getFinalPath()) || !identity.equals(command.getAllocation().get(VolumeAllocationImport.IDENTITY)) ||
                     !strategy.equals(command.getAllocation().get(VolumeAllocationImport.STRATEGY)))) {
                throw new CloudRuntimeException("ALLOCATION_POLICY_MISMATCH: final imported image identity changed");
            }
            unchanged(source, sourceBefore); unchanged(target, targetBefore);
            evidence.put(VolumeAllocationImport.REQUEST_ID, command.getRequestId());
            evidence.put(VolumeAllocationImport.POOL, pool.getUuid());
            evidence.put(VolumeAllocationImport.SOURCE_PATH, directory.relativize(source).toString());
            evidence.put(VolumeAllocationImport.SOURCE_IDENTITY, identity(pool, directory, source, command.getSize(), timeout));
            evidence.put(VolumeAllocationImport.FINAL_PATH, relative);
            evidence.put(VolumeAllocationImport.IDENTITY, identity);
            evidence.put(VolumeAllocationImport.STRATEGY, strategy);
            evidence.put(VolumeAllocationImport.VERIFIED_AT, Instant.now().toString());
            evidence.put(VolumeAllocationPolicy.OBSERVED_BYTES, Long.toString(used));
            evidence.put(VolumeAllocationPolicy.OBSERVED_AT, Instant.now().toString());
            evidence.put(VolumeAllocationPolicy.OBSERVED_SOURCE, "stat-st-blocks-times-512");
            VolumeAllocationImport.validateEvidence(intent, evidence, command.getRequestId(), pool.getUuid(), relative, format, command.isAllowCopy());
            VolumeOnStorageTO volume = new VolumeOnStorageTO(Hypervisor.HypervisorType.KVM, relative,
                    target.getFileName().toString(), target.toString(), format, used, command.getSize());
            return new Result(volume, evidence);
        } catch (IOException e) {
            throw new CloudRuntimeException("ALLOCATION_IMPORT_IO_FAILED: " + e.getMessage(), e);
        }
    }

    private Path resolve(Path directory, String value) throws IOException {
        if (value == null || value.isBlank()) throw new CloudRuntimeException("ALLOCATION_IMPORT_PATH_REQUIRED");
        Path configured = Path.of(value), candidate = configured.isAbsolute() ? configured : directory.resolve(configured);
        if (Files.isSymbolicLink(candidate) || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new CloudRuntimeException("ALLOCATION_IMPORT_PATH_INVALID: regular, unlinked image required");
        }
        Path real = candidate.toRealPath();
        if (!real.startsWith(directory) || real.equals(directory)) throw new CloudRuntimeException("ALLOCATION_IMPORT_PATH_OUTSIDE_POOL");
        return real;
    }

    private JsonObject inspect(Path image, String format, long size, long timeout) {
        JsonObject info = new JsonParser().parse(run(List.of("qemu-img", "info", "--output=json", image.toString()), timeout)).getAsJsonObject();
        if (!format.equals(info.get("format").getAsString()) || info.get("virtual-size").getAsLong() != size ||
                info.has("backing-filename") || info.has("full-backing-filename") || info.toString().contains("\"data-file\"") ||
                (info.has("encrypted") && info.get("encrypted").getAsBoolean())) {
            throw new CloudRuntimeException("ALLOCATION_IMPORT_IMAGE_MISMATCH: standalone format and exact virtual size required");
        }
        return info;
    }

    private long allocated(Path image, JsonObject info, long size, String policy, long timeout) {
        String[] stat = run(List.of("stat", "--format=%b:%s", "--", image.toString()), timeout).trim().split(":");
        long used = Math.multiplyExact(Long.parseLong(stat[0]), 512L), length = Long.parseLong(stat[1]);
        if (used < 0 || !info.has("actual-size") || used != info.get("actual-size").getAsLong() ||
                (List.of("falloc", "full").contains(policy) && (used < size || length < size))) {
            throw new CloudRuntimeException("ALLOCATION_EVIDENCE_REQUIRED: requested allocation is not present on final image");
        }
        return used;
    }

    private String identity(KVMStoragePool pool, Path directory, Path image, long size, long timeout) throws IOException {
        // Pool + GFS inode, not a host-local device number or a worker placement binding.
        String inode = run(List.of("stat", "--format=%i", "--", image.toString()), timeout).trim();
        BasicFileAttributes attributes = attributes(image);
        String value = pool.getUuid() + "/" + directory.relativize(image) + "/" + inode + "/" + size + "/" +
                attributes.size() + "/" + attributes.lastModifiedTime().toInstant();
        try {
            StringBuilder result = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))) result.append(String.format("%02x", b & 255));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static BasicFileAttributes attributes(Path path) throws IOException { return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); }
    private static void unchanged(Path path, BasicFileAttributes before) throws IOException {
        BasicFileAttributes after = attributes(path);
        if (!after.isRegularFile() || before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime()) ||
                !java.util.Objects.equals(before.fileKey(), after.fileKey())) throw new CloudRuntimeException("ALLOCATION_IMPORT_IMAGE_CHANGED");
    }
}
