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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.apache.cloudstack.utils.qemu.QemuImg.PhysicalDiskFormat;
import com.cloud.storage.Storage;
import com.cloud.storage.VolumeAllocationPolicy;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.script.OutputInterpreter;
import com.cloud.utils.script.Script;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** RBD RAW creation-time allocation, never a persistent reservation or a COW clone. */
public class RbdVolumeAllocation {
    static final String RECEIPT = "cloudstack.allocation.v1";
    private static final long DEFAULT_TIMEOUT = 3_600_000;

    interface Runner {
        String run(List<String> arguments, long timeout);
    }

    private final Runner runner;

    public RbdVolumeAllocation() {
        this((arguments, timeout) -> {
            Script script = new Script(arguments.get(0), timeout);
            script.setAvoidLoggingCommand(true);
            for (int i = 1; i < arguments.size(); i++) {
                script.add(arguments.get(i));
            }
            OutputInterpreter.AllLinesParser output = new OutputInterpreter.AllLinesParser();
            String error = script.execute(output);
            if (error != null) {
                throw new CloudRuntimeException("RBD_ALLOCATION_COMMAND_FAILED: " + error);
            }
            return output.getLines() == null ? "" : output.getLines();
        });
    }

    RbdVolumeAllocation(Runner runner) {
        this.runner = runner;
    }

    /** Non-mutating tool and pool authentication probe. Each operation must still verify its result. */
    public Map<String, String> capabilities(KVMStoragePool pool, String format, String operation) {
        if (pool.getType() != Storage.StoragePoolType.RBD || !"raw".equals(format) ||
                !List.of("create", "copy").contains(operation)) {
            return Map.of();
        }
        try (Context context = new Context(pool, 30_000)) {
            String createHelp = context.run("rbd", "help", "create");
            String duHelp = context.run("rbd", "help", "du");
            String qemuHelp = context.run("qemu-img", "convert", "--help");
            String compareHelp = context.run("qemu-img", "compare", "--help");
            if (!createHelp.contains("--thick-provision") || !duHelp.contains("--exact") ||
                    !qemuHelp.contains("-S") || !qemuHelp.contains("-n") || !compareHelp.contains("-F")) {
                return Map.of();
            }
            context.rbd("pool", "stats", "--pool", pool.getSourceDir(), "--format", "json");
            try {
                context.poolAvailable(pool.getSourceDir());
                return Map.of("off", "rbd-thin", "full", "create".equals(operation) ? "rbd-thick-create" : "rbd-thick-convert-n-S0");
            } catch (RuntimeException unavailable) {
                // Thin does not need a Full capacity claim. Never infer usable space from raw cluster bytes.
                return Map.of("off", "rbd-thin");
            }
        } catch (IOException e) {
            throw new CloudRuntimeException("RBD_ALLOCATION_CAPABILITY_UNAVAILABLE", e);
        }
    }

    /** A temporary image is verified before an atomic, non-overwriting rename publishes the volume. */
    public VolumeObjectTO create(VolumeObjectTO volume, KVMStoragePool pool, KVMPhysicalDisk source, long timeout) {
        Map<String, String> intent = VolumeAllocationPolicy.requireCreationIntent(volume.getAllocation());
        if (intent.isEmpty() || !"raw".equals(intent.get(VolumeAllocationPolicy.FORMAT)) ||
                !List.of("off", "full").contains(intent.get(VolumeAllocationPolicy.POLICY)) || pool.getType() != Storage.StoragePoolType.RBD || volume.getSize() == null || volume.getSize() <= 0 ||
                volume.requiresEncryption() || volume.getEncryptFormat() != null || volume.getMigrationOptions() != null ||
                volume.getUuid() == null || !volume.getUuid().matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) {
            throw new CloudRuntimeException("RBD_ALLOCATION_REQUEST_UNSUPPORTED: unencrypted RAW creation or materialized template copy only");
        }
        synchronized ((pool.getUuid() + "/" + volume.getUuid()).intern()) {
            try (Context context = new Context(pool, timeout)) {
                return execute(volume, pool, source, intent, context);
            } catch (IOException e) {
                throw new CloudRuntimeException("RBD_ALLOCATION_CONTEXT_FAILED", e);
            }
        }
    }

    private VolumeObjectTO execute(VolumeObjectTO volume, KVMStoragePool pool, KVMPhysicalDisk source,
            Map<String, String> intent, Context context) throws IOException {
        boolean full = "full".equals(intent.get(VolumeAllocationPolicy.POLICY));
        String operation = source == null ? "create" : "copy";
        String method = full ? (source == null ? "rbd-thick-create" : "rbd-thick-convert-n-S0") : "rbd-thin";
        String sourceName = source == null ? "zero" : source.getPath();
        String fingerprint = digest(pool.getUuid() + "/" + volume.getUuid() + "/" + volume.getVolumeId() + "/" +
                volume.getAccountId() + "/" + volume.getVolumeType() + "/" + volume.getSize() + "/" +
                intent.get(VolumeAllocationPolicy.VERSION) + "/" + intent.get(VolumeAllocationPolicy.FORMAT) + "/" +
                intent.get(VolumeAllocationPolicy.POLICY) + "/" + operation + "/" + sourceName);
        String finalImage = pool.getSourceDir() + "/" + volume.getUuid();
        String sourceUri;
        String sourceFormat;
        if (source == null) {
            Path zero = context.directory.resolve("zero.raw");
            try (RandomAccessFile file = new RandomAccessFile(zero.toFile(), "rw")) {
                file.setLength(volume.getSize());
            }
            sourceUri = zero.toString();
            sourceFormat = "raw";
        } else {
            if (source.getFormat() != PhysicalDiskFormat.RAW &&
                    source.getFormat() != PhysicalDiskFormat.QCOW2) {
                throw new CloudRuntimeException("RBD_ALLOCATION_SOURCE_UNSUPPORTED");
            }
            if (source.getPool().getType() == Storage.StoragePoolType.RBD) {
                if (!pool.getUuid().equals(source.getPool().getUuid()) || source.getFormat() != PhysicalDiskFormat.RAW) {
                    throw new CloudRuntimeException("RBD_ALLOCATION_SOURCE_UNSUPPORTED: cross-pool or non-RAW RBD template");
                }
                sourceUri = context.uri(source.getPath());
            } else {
                if (!Path.of(source.getPath()).isAbsolute() || !Files.isRegularFile(Path.of(source.getPath()))) {
                    throw new CloudRuntimeException("RBD_ALLOCATION_SOURCE_UNSUPPORTED: regular local template file required");
                }
                sourceUri = source.getPath();
            }
            sourceFormat = source.getFormat().toString().toLowerCase(java.util.Locale.ROOT);
            JsonObject info = json(context.run("qemu-img", "info", "--output=json", "-f", sourceFormat, sourceUri));
            if (info.get("virtual-size").getAsLong() > volume.getSize()) {
                throw new CloudRuntimeException("RBD_ALLOCATION_TARGET_TOO_SMALL");
            }
        }

        // Only a receipt from this exact immutable request permits a lost-answer retry.
        List<String> images = new com.google.gson.Gson().fromJson(context.rbd("ls", "--format", "json", pool.getSourceDir()),
                new com.google.gson.reflect.TypeToken<List<String>>() { }.getType());
        if (images.contains(volume.getUuid())) {
            String receipt;
            try {
                receipt = context.rbd("image-meta", "get", finalImage, RECEIPT).trim();
            } catch (RuntimeException missingReceipt) {
                throw new CloudRuntimeException("RBD_ALLOCATION_TARGET_EXISTS: existing image has no verified request receipt", missingReceipt);
            }
            if (!fingerprint.equals(receipt)) {
                throw new CloudRuntimeException("RBD_ALLOCATION_TARGET_EXISTS: existing image is not owned by this request");
            }
            long used = verify(context, finalImage, volume.getSize(), full, sourceUri, sourceFormat);
            return result(volume, intent, operation, method, used);
        }
        if (full && Math.min(pool.getAvailable(), context.poolAvailable(pool.getSourceDir())) < volume.getSize()) {
            throw new CloudRuntimeException("RBD_ALLOCATION_INSUFFICIENT_CAPACITY: Ceph pool max_avail cannot cover the Full virtual size; this check is not a reservation");
        }
        // This unpredictable stage name belongs exclusively to this invocation, even on create timeout.
        String stage = pool.getSourceDir() + "/" + volume.getUuid() + ".allocation-" + UUID.randomUUID();
        boolean published = false;
        try {
            List<String> create = new ArrayList<>(List.of("create", "--size", volume.getSize() + "B", "--image-format", "2",
                    "--image-feature", "layering,exclusive-lock,object-map,fast-diff,deep-flatten", "--no-progress"));
            if (full) {
                create.add("--thick-provision");
            }
            create.add(stage);
            context.rbd(create.toArray(new String[0]));
            if (source != null) {
                context.run("qemu-img", "convert", "-n", "-S", full ? "0" : "4096", "-f", sourceFormat,
                        "-O", "raw", sourceUri, context.uri(stage));
            }
            long used = verify(context, stage, volume.getSize(), full, sourceUri, sourceFormat);
            context.rbd("image-meta", "set", stage, RECEIPT, fingerprint);
            context.rbd("rename", stage, finalImage);
            published = true;
            return result(volume, intent, operation, method, used);
        } catch (RuntimeException failure) {
            if (!published) {
                try {
                    // Cleanup has its own bounded timeout, including when the work budget has expired.
                    context.removeStage(stage);
                } catch (RuntimeException cleanupFailure) {
                    throw new CloudRuntimeException("RBD_ALLOCATION_FAILED: owned temporary image requires cleanup: " + stage, failure);
                }
            }
            throw failure;
        }
    }

    private long verify(Context context, String image, long expected, boolean full, String source, String format) {
        JsonObject info = json(context.rbd("info", image, "--format", "json"));
        if (info.get("size").getAsLong() != expected || info.has("parent")) {
            throw new CloudRuntimeException("RBD_ALLOCATION_VERIFICATION_FAILED: size mismatch or COW parent");
        }
        // Without -s, compare also verifies that any larger target tail reads as zero.
        context.run("qemu-img", "compare", "-f", format, "-F", "raw", source, context.uri(image));
        JsonObject du = json(context.rbd("du", "--exact", image, "--format", "json"));
        if (!du.has("images") || du.getAsJsonArray("images").size() != 1) {
            throw new CloudRuntimeException("RBD_ALLOCATION_VERIFICATION_FAILED: image-specific usage is missing");
        }
        JsonObject usage = du.getAsJsonArray("images").get(0).getAsJsonObject();
        long used = usage.get("used_size").getAsLong();
        if (usage.get("provisioned_size").getAsLong() != expected || used < 0 || (full && used < expected)) {
            throw new CloudRuntimeException("RBD_ALLOCATION_VERIFICATION_FAILED: requested Full is not fully allocated");
        }
        return used;
    }

    private VolumeObjectTO result(VolumeObjectTO request, Map<String, String> intent, String operation, String method, long used) {
        VolumeObjectTO result = new VolumeObjectTO();
        result.setPath(request.getUuid());
        result.setSize(request.getSize());
        result.setFormat(Storage.ImageFormat.RAW);
        result.setDataStore(request.getDataStore());
        result.setAllocation(VolumeAllocationPolicy.evidence(intent, "RBD", operation, method, used, Instant.now(), "rbd-du-exact-image-used-size"));
        return result;
    }

    private static JsonObject json(String value) {
        return new JsonParser().parse(value).getAsJsonObject();
    }

    private static String digest(String value) {
        try {
            StringBuilder hex = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))) {
                hex.append(String.format("%02x", b & 0xff));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private final class Context implements AutoCloseable {
        private final Path directory;
        private final Path config;
        private final String user;
        private final long deadline;

        Context(KVMStoragePool pool, long timeout) throws IOException {
            Map<String, String> details = pool.getDetails();
            if (!pool.getSourceDir().matches("[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}") || pool.getAuthUserName() == null ||
                    !pool.getAuthUserName().matches("[A-Za-z0-9_.-]+") || pool.getAuthSecret() == null ||
                    !pool.getAuthSecret().matches("[A-Za-z0-9+/=]+") || pool.getSourceHost() == null ||
                    !pool.getSourceHost().matches("[A-Za-z0-9.,:;\\[\\]-]+") ||
                    (details != null && details.get(KVMPhysicalDisk.RBD_DEFAULT_DATA_POOL) != null)) {
                throw new CloudRuntimeException("RBD_ALLOCATION_POOL_UNSUPPORTED: authenticated replicated RAW pool configuration required");
            }
            user = pool.getAuthUserName();
            directory = Files.createTempDirectory("cloudstack-rbd-allocation-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            config = directory.resolve("ceph.conf");
            String monitors = pool.getSourceHost().replace(';', ',');
            if (pool.getSourcePort() > 0) {
                monitors = java.util.Arrays.stream(monitors.split(",")).map(host -> host + ":" + pool.getSourcePort()).collect(java.util.stream.Collectors.joining(","));
            }
            Files.writeString(config, "[global]\nmon_host = " + monitors + "\nclient_mount_timeout = 30\n[client." + user + "]\nkey = " + pool.getAuthSecret() + "\n",
                    StandardCharsets.UTF_8);
            Files.setPosixFilePermissions(config, PosixFilePermissions.fromString("rw-------"));
            deadline = System.nanoTime() + Math.min(timeout > 0 ? timeout : DEFAULT_TIMEOUT, 14_400_000) * 1_000_000L;
        }

        String run(String... arguments) {
            long remaining = (deadline - System.nanoTime()) / 1_000_000L;
            if (remaining <= 0) {
                throw new CloudRuntimeException("RBD_ALLOCATION_TIMEOUT");
            }
            return runner.run(List.of(arguments), remaining);
        }

        String rbd(String... arguments) {
            List<String> command = new ArrayList<>(List.of("rbd", "--conf", config.toString(), "--id", user));
            command.addAll(List.of(arguments));
            return run(command.toArray(new String[0]));
        }

        long poolAvailable(String pool) {
            try {
                JsonObject df = json(run("ceph", "--conf", config.toString(), "--id", user, "df", "--format", "json"));
                for (com.google.gson.JsonElement entry : df.getAsJsonArray("pools")) {
                    JsonObject row = entry.getAsJsonObject();
                    if (pool.equals(row.get("name").getAsString())) {
                        long available = row.getAsJsonObject("stats").get("max_avail").getAsLong();
                        if (available >= 0) {
                            return available;
                        }
                    }
                }
            } catch (RuntimeException unavailable) {
                throw new CloudRuntimeException("RBD_ALLOCATION_CAPACITY_UNVERIFIED: authenticated Ceph pool max_avail is required for Full", unavailable);
            }
            throw new CloudRuntimeException("RBD_ALLOCATION_CAPACITY_UNVERIFIED: target pool max_avail is missing");
        }

        String uri(String image) {
            if (!image.matches("[A-Za-z0-9_][A-Za-z0-9_.-]*/[A-Za-z0-9_.@-]+")) {
                throw new CloudRuntimeException("RBD_ALLOCATION_IMAGE_IDENTITY_INVALID");
            }
            return "rbd:" + image + ":id=" + user + ":conf=" + config;
        }

        void removeStage(String image) {
            try {
                runner.run(List.of("rbd", "--conf", config.toString(), "--id", user, "rm", image, "--no-progress"), 30_000);
            } catch (RuntimeException cleanup) {
                String pool = image.substring(0, image.indexOf('/'));
                String name = image.substring(image.indexOf('/') + 1);
                String listed = runner.run(List.of("rbd", "--conf", config.toString(), "--id", user, "ls", "--pool", pool, "--format", "json"), 30_000);
                List<String> remaining = new com.google.gson.Gson().fromJson(listed, new com.google.gson.reflect.TypeToken<List<String>>() { }.getType());
                if (remaining.contains(name)) {
                    throw cleanup;
                }
            }
        }

        @Override public void close() throws IOException {
            Files.deleteIfExists(directory.resolve("zero.raw"));
            Files.deleteIfExists(config);
            Files.deleteIfExists(directory);
        }
    }
}
