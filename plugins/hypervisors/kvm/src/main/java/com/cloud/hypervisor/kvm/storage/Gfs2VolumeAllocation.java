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
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserDefinedFileAttributeView;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.apache.cloudstack.utils.qemu.QemuImg.PhysicalDiskFormat;
import com.cloud.storage.Storage;
import com.cloud.storage.VolumeAllocationPolicy;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Creation-time GFS2 allocation. File blocks are an observation, never a persistent reservation. */
public class Gfs2VolumeAllocation {
    private static final int FALLOC_RETRIES = 2;
    static final String RECEIPT = "cloudstack.allocation.v1";
    interface Runner { String run(List<String> arguments, long timeout); }
    private final Runner runner;

    public Gfs2VolumeAllocation() {
        this(Gfs2AllocationCommandRunner::run);
    }
    Gfs2VolumeAllocation(Runner runner) { this.runner = runner; }

    /** Keep the Cloud RAW file format through the older default disk-definition path. */
    public static void configureFileDisk(com.cloud.hypervisor.kvm.resource.LibvirtVMDef.DiskDef disk,
            KVMPhysicalDisk physical, com.cloud.agent.api.to.DataTO data) {
        if (prepareRawPhysicalFile(physical, data)) {
            if (disk == null) { throw new CloudRuntimeException("GFS2_RAW_FILE_REQUIRED: disk definition missing"); }
            configurePhysicalFileDisk(disk, physical);
        }
    }

    /** Attach commands may carry only the actual format; never infer an allocation policy from it. */
    public static boolean prepareRawPhysicalFile(KVMPhysicalDisk physical, com.cloud.agent.api.to.DataTO data) {
        if (!(data instanceof VolumeObjectTO) || !(data.getDataStore() instanceof org.apache.cloudstack.storage.to.PrimaryDataStoreTO)) { return false; }
        VolumeObjectTO volume = (VolumeObjectTO) data;
        org.apache.cloudstack.storage.to.PrimaryDataStoreTO store = (org.apache.cloudstack.storage.to.PrimaryDataStoreTO) data.getDataStore();
        Map<String, String> intent = VolumeAllocationPolicy.validateStored(volume.getAllocation());
        if (store.getPoolType() != Storage.StoragePoolType.SharedMountPoint ||
                (!"raw".equals(intent.get(VolumeAllocationPolicy.FORMAT)) && volume.getFormat() != Storage.ImageFormat.RAW)) { return false; }
        if (volume.getFormat() != Storage.ImageFormat.RAW || (!intent.isEmpty() && !"raw".equals(intent.get(VolumeAllocationPolicy.FORMAT))) ||
                physical == null || physical.getPool() == null || !store.getUuid().equals(physical.getPool().getUuid()) || physical.getPath() == null ||
                !Path.of(physical.getPath()).isAbsolute() || !Files.isRegularFile(Path.of(physical.getPath()), LinkOption.NOFOLLOW_LINKS) ||
                Files.isSymbolicLink(Path.of(physical.getPath()))) {
            throw new CloudRuntimeException("GFS2_RAW_FILE_REQUIRED: RAW must retain its actual file-backed format and pool identity");
        }
        physical.setFormat(PhysicalDiskFormat.RAW);
        return true;
    }

    /** RAW files on SharedMountPoint are files, including hot attach; RBD/LVM remain block/network. */
    public static void configurePhysicalFileDisk(com.cloud.hypervisor.kvm.resource.LibvirtVMDef.DiskDef disk, KVMPhysicalDisk physical) {
        if (disk == null || physical == null || physical.getPool() == null || physical.getFormat() != PhysicalDiskFormat.RAW ||
                physical.getPool().getType() != Storage.StoragePoolType.SharedMountPoint || physical.getPath() == null ||
                !Files.isRegularFile(Path.of(physical.getPath()), LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(Path.of(physical.getPath()))) { return; }
        com.cloud.hypervisor.kvm.resource.LibvirtVMDef.DiskDef.DiskCacheMode cache = disk.getCacheMode();
        disk.defFileBasedDisk(physical.getPath(), disk.getDiskLabel(), disk.getBusType(),
                com.cloud.hypervisor.kvm.resource.LibvirtVMDef.DiskDef.DiskFmtType.RAW);
        disk.setCacheMode(cache);
    }

    protected Path root(KVMStoragePool pool) throws IOException {
        if (pool == null || pool.getType() != Storage.StoragePoolType.SharedMountPoint || pool.getLocalPath() == null) {
            throw new CloudRuntimeException("GFS2_ALLOCATION_BACKEND_UNSUPPORTED: SharedMountPoint required");
        }
        Path configured = Path.of(pool.getLocalPath());
        if (!configured.isAbsolute() || Files.isSymbolicLink(configured) || !Files.isDirectory(configured)) {
            throw new CloudRuntimeException("GFS2_ALLOCATION_BACKEND_UNSUPPORTED: absolute mounted directory required");
        }
        Path real = configured.toRealPath();
        if (!"gfs2".equalsIgnoreCase(Files.getFileStore(real).type()) ||
                !Files.getFileStore(real).supportsFileAttributeView(UserDefinedFileAttributeView.class)) {
            throw new CloudRuntimeException("GFS2_ALLOCATION_BACKEND_UNSUPPORTED: mounted GFS2 with user xattrs required");
        }
        return real;
    }

    static Map<String, String> methods(String format, String operation) {
        if (operation == null || !List.of("create", "copy").contains(operation)) { return Map.of(); }
        if ("qcow2".equals(format)) {
            return Map.of("metadata", "qemu-preallocation-metadata", "falloc", "qemu-preallocation-falloc", "full", "qemu-preallocation-full");
        }
        return "raw".equals(format) ? Map.of("off", "qemu-preallocation-off", "falloc", "qemu-preallocation-falloc", "full", "qemu-preallocation-full") : Map.of();
    }

    /** Read-only mounted-filesystem and installed-tool probe. Execution must still verify allocation. */
    public Map<String, String> capabilities(KVMStoragePool pool, String format, String operation) {
        Map<String, String> supported = methods(format, operation);
        if (supported.isEmpty()) { return supported; }
        try {
            root(pool);
            Context context = new Context(30_000);
            String help = context.run("qemu-img", "convert", "--help");
            String compare = context.run("qemu-img", "compare", "--help");
            String options = context.run("qemu-img", "create", "-f", format, "-o", "help");
            // RAW preallocation is a file-protocol option; do not infer its absence from raw format help.
            if (!help.contains("-n") || !help.contains("-S") || !compare.contains("-F") ||
                    ("qcow2".equals(format) && !options.contains("preallocation"))) { return Map.of(); }
            context.run("qemu-img", "measure", "--output=json", "-O", format, "--size", "1048576");
            return supported;
        } catch (IOException e) { throw new CloudRuntimeException("GFS2_ALLOCATION_CAPABILITY_UNAVAILABLE", e); }
    }

    public VolumeObjectTO create(VolumeObjectTO volume, KVMStoragePool pool, KVMPhysicalDisk source, long timeout) {
        Map<String, String> intent = VolumeAllocationPolicy.requireCreationIntent(volume.getAllocation());
        String format = intent.get(VolumeAllocationPolicy.FORMAT), policy = intent.get(VolumeAllocationPolicy.POLICY);
        if (intent.isEmpty() || !methods(format, "create").containsKey(policy) || volume.getSize() == null || volume.getSize() <= 0 ||
                volume.requiresEncryption() || volume.getEncryptFormat() != null || volume.getMigrationOptions() != null ||
                volume.getUuid() == null || !volume.getUuid().matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) {
            throw new CloudRuntimeException("GFS2_ALLOCATION_REQUEST_UNSUPPORTED: unencrypted qualified format/policy creation or template copy only");
        }
        try { return execute(volume, pool, source, intent, root(pool), new Context(timeout)); }
        catch (IOException e) { throw new CloudRuntimeException("GFS2_ALLOCATION_IO_FAILED: " + e.getMessage(), e); }
    }

    private VolumeObjectTO execute(VolumeObjectTO volume, KVMStoragePool pool, KVMPhysicalDisk source,
            Map<String, String> intent, Path directory, Context context) throws IOException {
        String format = intent.get(VolumeAllocationPolicy.FORMAT), policy = intent.get(VolumeAllocationPolicy.POLICY);
        String operation = source == null ? "create" : "copy";
        Path original = null;
        BasicFileAttributes sourceBefore = null;
        String sourceFormat = "raw";
        if (source != null) {
            if (source.getFormat() != PhysicalDiskFormat.RAW && source.getFormat() != PhysicalDiskFormat.QCOW2) {
                throw new CloudRuntimeException("GFS2_ALLOCATION_SOURCE_UNSUPPORTED: RAW/QCOW2 template required");
            }
            original = Path.of(source.getPath());
            if (!original.isAbsolute() || !Files.isRegularFile(original, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(original)) {
                throw new CloudRuntimeException("GFS2_ALLOCATION_SOURCE_UNSUPPORTED: regular local template required");
            }
            original = original.toRealPath();
            sourceFormat = source.getFormat().toString().toLowerCase(Locale.ROOT);
            JsonObject info = info(context, original, sourceFormat);
            if (info.get("virtual-size").getAsLong() > volume.getSize()) {
                throw new CloudRuntimeException("GFS2_ALLOCATION_TARGET_TOO_SMALL");
            }
            requireStandalone(info, sourceFormat);
            sourceBefore = Files.readAttributes(original, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        }
        String identity = pool.getUuid() + "/" + volume.getUuid() + "/" + volume.getVolumeId() + "/" + volume.getAccountId() + "/" +
                volume.getVolumeType() + "/" + volume.getSize() + "/" + intent.get(VolumeAllocationPolicy.VERSION) + "/" + format + "/" + policy + "/" + operation + "/" +
                (source == null ? "zero" : original + "/" + sourceFormat + "/" + sourceBefore.size() + "/" + sourceBefore.lastModifiedTime());
        String fingerprint = digest(identity);
        Path target = directory.resolve(volume.getUuid());
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            requireReceipt(target, fingerprint);
            long used = verify(context, target, original, sourceFormat, format, policy, volume.getSize());
            requireSourceUnchanged(original, sourceBefore);
            return result(volume, intent, operation, used);
        }
        JsonObject measured = json(context.run("qemu-img", "measure", "--output=json", "-O", format, "-o", "preallocation=" + policy,
                "--size", volume.getSize().toString()));
        long needed = measured.get((source != null || List.of("falloc", "full").contains(policy)) ? "fully-allocated" : "required").getAsLong();
        int attempts = "falloc".equals(policy) ? FALLOC_RETRIES + 1 : 1;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            requireSourceUnchanged(original, sourceBefore);
            if (needed < 0 || Math.min(pool.getAvailable(), available(directory)) < needed) {
                throw new CloudRuntimeException("GFS2_ALLOCATION_INSUFFICIENT_CAPACITY: image and format overhead exceed usable filesystem space; not a reservation");
            }
            Path work = Files.createTempDirectory(directory, volume.getUuid() + ".allocation-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            Path stage = work.resolve("image");
            RuntimeException pending = null;
            try {
                context.run("qemu-img", "create", "-f", format, "-o", "preallocation=" + policy, stage.toString(), volume.getSize().toString());
                if (source != null) {
                    // Preserve fully allocated targets. Falloc copy may write zero data; only Full promises eager initialization.
                    context.run("qemu-img", "convert", "-n", "-S", List.of("falloc", "full").contains(policy) ? "0" : "4096",
                            "-f", sourceFormat, "-O", format, original.toString(), stage.toString());
                }
                long used;
                try {
                    used = verify(context, stage, original, sourceFormat, format, policy, volume.getSize(), work);
                } catch (Gfs2AllocationFailureEvidence.ContentMismatch failure) {
                    String evidence = Gfs2AllocationFailureEvidence.preserve(work, directory, fingerprint,
                            volume.getUuid(), format, policy, volume.getSize(), sourceFormat, original, failure);
                    org.apache.logging.log4j.LogManager.getLogger(Gfs2VolumeAllocation.class).warn("GFS2 allocation content mismatch: volume={}, format={}, policy={}, attempt={}/{}, {}",
                            volume.getUuid(), format, policy, attempt, attempts, evidence);
                    requireSourceUnchanged(original, sourceBefore);
                    if (attempt < attempts) {
                        // The finally block removes only this attempt's remaining files before the next fresh stage.
                        // The same Context retains the original deadline. Never repair or publish a failed inode.
                        continue;
                    }
                    throw new CloudRuntimeException("GFS2_ALLOCATION_CONTENT_MISMATCH: image was not published after " + attempt +
                            " attempt(s); " + evidence, failure);
                }
                requireSourceUnchanged(original, sourceBefore);
                attributes(stage).write(RECEIPT, ByteBuffer.wrap(fingerprint.getBytes(StandardCharsets.UTF_8)));
                try (FileChannel image = FileChannel.open(stage, StandardOpenOption.WRITE)) { image.force(true); }
                // link(2) publishes the verified inode atomically and fails EEXIST across hosts; never overwrite.
                Files.createLink(target, stage);
                try (FileChannel parent = FileChannel.open(directory, StandardOpenOption.READ)) { parent.force(true); }
                return result(volume, intent, operation, used);
            } catch (RuntimeException failure) { pending = failure; throw failure; }
            finally {
                try {
                    try (var remaining = Files.list(work)) {
                        for (Path owned : remaining.collect(java.util.stream.Collectors.toList())) { Files.delete(owned); }
                    }
                    Files.delete(work);
                } catch (IOException cleanup) {
                    CloudRuntimeException failure = new CloudRuntimeException("GFS2_ALLOCATION_CLEANUP_REQUIRED: owned temporary directory " + work, cleanup);
                    if (pending != null) { failure.addSuppressed(pending); }
                    throw failure;
                }
            }
        }
        throw new IllegalStateException("Allocation attempts exhausted without a result");
    }

    protected long available(Path directory) throws IOException { return Files.getFileStore(directory).getUsableSpace(); }

    private long verify(Context context, Path image, Path original, String sourceFormat, String format, String policy, long expected) throws IOException {
        return verify(context, image, original, sourceFormat, format, policy, expected, null);
    }

    private long verify(Context context, Path image, Path original, String sourceFormat, String format, String policy, long expected,
            Path evidenceDirectory) throws IOException {
        if (!Files.isRegularFile(image, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(image)) {
            throw new CloudRuntimeException("GFS2_ALLOCATION_VERIFICATION_FAILED: regular owned image required");
        }
        JsonObject info = info(context, image, format);
        requireStandalone(info, format);
        String[] stat = context.run("stat", "--format=%b:%s", "--", image.toString()).trim().split(":");
        long used = Math.multiplyExact(Long.parseLong(stat[0]), 512L), length = Long.parseLong(stat[1]);
        if (info.get("virtual-size").getAsLong() != expected || length != Files.size(image) || used < 0 ||
                !info.has("actual-size") || info.get("actual-size").getAsLong() != used ||
                (List.of("falloc", "full").contains(policy) && (used < expected || length < expected))) {
            throw new CloudRuntimeException("GFS2_ALLOCATION_VERIFICATION_FAILED: format, size or preallocated file blocks do not match");
        }
        if (original == null) {
            Path zero = evidenceDirectory == null
                    ? Files.createTempFile("cloudstack-allocation-zero-", ".raw", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                    : Files.createTempFile(evidenceDirectory, "zero-", ".raw", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            boolean retainReference = false;
            try {
                try (RandomAccessFile file = new RandomAccessFile(zero.toFile(), "rw")) { file.setLength(expected); }
                compare(context, "raw", format, zero, image);
            } catch (Gfs2AllocationFailureEvidence.ContentMismatch failure) {
                retainReference = evidenceDirectory != null;
                throw failure;
            } finally { if (!retainReference) { Files.deleteIfExists(zero); } }
        } else {
            // Without -s, a larger destination tail must read as zero as well.
            compare(context, sourceFormat, format, original, image);
        }
        return used;
    }

    private void compare(Context context, String sourceFormat, String format, Path original, Path image) {
        try {
            context.run("qemu-img", "compare", "-f", sourceFormat, "-F", format, original.toString(), image.toString());
        } catch (Gfs2AllocationCommandRunner.CommandFailure failure) {
            // qemu-img compare: 1 means content mismatch; 2+ means command/open/read errors.
            if (failure.exitCode() != 1) { throw failure; }
            throw new Gfs2AllocationFailureEvidence.ContentMismatch(failure);
        }
    }

    private static void requireStandalone(JsonObject info, String format) {
        if (!format.equals(info.get("format").getAsString()) || info.has("backing-filename") || info.has("full-backing-filename") ||
                (info.has("encrypted") && info.get("encrypted").getAsBoolean()) || info.toString().contains("\"data-file\"")) {
            throw new CloudRuntimeException("GFS2_ALLOCATION_VERIFICATION_FAILED: unexpected format, backing, external data or encryption");
        }
    }
    private static void requireSourceUnchanged(Path original, BasicFileAttributes before) throws IOException {
        if (original == null) { return; }
        BasicFileAttributes after = Files.readAttributes(original, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!after.isRegularFile() || after.size() != before.size() || !after.lastModifiedTime().equals(before.lastModifiedTime()) ||
                !java.util.Objects.equals(after.fileKey(), before.fileKey())) {
            throw new CloudRuntimeException("GFS2_ALLOCATION_SOURCE_CHANGED: template must remain immutable during copy");
        }
    }
    private static UserDefinedFileAttributeView attributes(Path path) {
        UserDefinedFileAttributeView view = Files.getFileAttributeView(path, UserDefinedFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) { throw new CloudRuntimeException("GFS2_ALLOCATION_RECEIPT_UNAVAILABLE"); }
        return view;
    }
    private static void requireReceipt(Path path, String fingerprint) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new CloudRuntimeException("GFS2_ALLOCATION_TARGET_EXISTS: regular verified image required");
        }
        UserDefinedFileAttributeView view = attributes(path);
        if (!view.list().contains(RECEIPT) || view.size(RECEIPT) != 64) {
            throw new CloudRuntimeException("GFS2_ALLOCATION_TARGET_EXISTS: existing image has no owned request receipt");
        }
        ByteBuffer receipt = ByteBuffer.allocate(64); view.read(RECEIPT, receipt); receipt.flip();
        if (!fingerprint.equals(StandardCharsets.UTF_8.decode(receipt).toString())) {
            throw new CloudRuntimeException("GFS2_ALLOCATION_TARGET_EXISTS: image belongs to a different request");
        }
    }
    private VolumeObjectTO result(VolumeObjectTO request, Map<String, String> intent, String operation, long used) {
        VolumeObjectTO result = new VolumeObjectTO(); result.setPath(request.getUuid()); result.setSize(request.getSize());
        result.setFormat(Storage.ImageFormat.valueOf(intent.get(VolumeAllocationPolicy.FORMAT).toUpperCase(Locale.ROOT)));
        result.setDataStore(request.getDataStore());
        result.setAllocation(VolumeAllocationPolicy.evidence(intent, "GFS2", operation, "qemu-preallocation-" + intent.get(VolumeAllocationPolicy.POLICY),
                used, Instant.now(), "stat-st-blocks-times-512"));
        return result;
    }
    private static JsonObject info(Context context, Path image, String format) {
        return json(context.run("qemu-img", "info", "--output=json", "-f", format, image.toString()));
    }
    private static JsonObject json(String value) { return new JsonParser().parse(value).getAsJsonObject(); }
    private static String digest(String value) {
        try {
            StringBuilder hex = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))) { hex.append(String.format("%02x", b & 0xff)); }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private final class Context {
        private final long deadline;
        Context(long timeout) { deadline = System.nanoTime() + Math.max(1, Math.min(timeout > 0 ? timeout : 3_600_000, 3_600_000)) * 1_000_000; }
        String run(String... arguments) {
            long remaining = (deadline - System.nanoTime()) / 1_000_000;
            if (remaining <= 0) { throw new CloudRuntimeException("GFS2_ALLOCATION_TIMEOUT"); }
            return runner.run(List.of(arguments), remaining);
        }
    }
}
