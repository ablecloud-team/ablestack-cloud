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
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

/** Durable cleanup proof survives removal of the larger per-job directory. */
final class LibvirtAblestackBackupJobFiles {
    private LibvirtAblestackBackupJobFiles() { }

    static boolean completed(Path fence, String id) throws IOException {
        if (!Files.isRegularFile(fence, LinkOption.NOFOLLOW_LINKS)) { return false; }
        String contents = Files.readString(fence).trim();
        if (!contents.startsWith("{")) { return false; }
        JsonObject receipt = new Gson().fromJson(contents, JsonObject.class);
        return receipt != null && receipt.has("version") && receipt.get("version").getAsInt() == 1
                && receipt.has("jobId") && id.equals(receipt.get("jobId").getAsString())
                && receipt.has("jobCleanup") && "CONFIRMED".equals(receipt.get("jobCleanup").getAsString());
    }

    /** Called after worker/source termination, before deleting any job files. */
    static void confirmCompleted(Path directory, String id, JsonObject plan, Path fence) throws IOException {
        confirmCleanup(directory, id, plan, fence, false);
    }

    /** The controller acknowledges deletion; the Host also verifies staging is absent. */
    static void confirmDeleted(Path directory, String id, JsonObject plan, Path fence) throws IOException {
        confirmCleanup(directory, id, plan, fence, true);
    }

    private static void confirmCleanup(Path directory, String id, JsonObject plan, Path fence, boolean backupDeleted) throws IOException {
        if (!Files.isRegularFile(fence, LinkOption.NOFOLLOW_LINKS)
                || !id.equals(plan.getAsJsonObject("manifest").get("backupUuid").getAsString())
                || Files.exists(Path.of(plan.get("stageRoot").getAsString(), ".volume-reservations", id + ".json"))) {
            throw new IOException("Backup staging reservation release is unconfirmed");
        }
        if (backupDeleted) {
            if (!Files.notExists(Path.of(plan.get("backupPath").getAsString()), LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Deleted backup staging directory removal is unconfirmed");
            }
        } else {
            Path file = directory.resolve("volume-finalization.json");
            JsonObject result = Files.isRegularFile(file) ? new Gson().fromJson(Files.readString(file), JsonObject.class) : null;
            if (result == null || !result.has("version") || result.get("version").getAsInt() != 1
                    || !result.has("backupUuid") || !id.equals(result.get("backupUuid").getAsString())
                    || !result.has("state") || !"COMPLETED".equals(result.get("state").getAsString())
                    || !result.has("stagingCleanupState") || !"COMPLETED".equals(result.get("stagingCleanupState").getAsString())
                    || !result.has("stagingPath") || !result.get("stagingPath").equals(plan.get("backupPath"))) {
                throw new IOException("Complete backup staging directory removal is unconfirmed");
            }
            if (plan.has("parentCheckpointName") && !plan.get("parentCheckpointName").isJsonNull()
                    && !plan.get("parentCheckpointName").getAsString().isBlank()
                    && !LibvirtAblestackAsyncBackupRunner.sourceCleanupCompleted(directory, id, plan)) {
                throw new IOException("Previous source checkpoint cleanup is unconfirmed");
            }
        }
        // Keep the existing admission fence as the minimal receipt. It also makes
        // partial directory removal and a lost cleanup response safe to retry.
        LibvirtAblestackVolumeRestoreHelper.atomic(fence, new Gson().toJson(Map.of(
                "version", 1, "jobId", id, "jobCleanup", "CONFIRMED", "checkedAt", System.currentTimeMillis())));
        try (FileChannel channel = FileChannel.open(fence.getParent(), StandardOpenOption.READ)) { channel.force(true); }
    }

    /** Archive diagnostics only after physical cleanup; active/retryable plans stay in jobs/. */
    static void archiveFailure(Path directory, Path properties, Path history, String id) throws IOException {
        Files.createDirectories(history, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path archive = history.resolve(id + ".zip");
        if (!Files.exists(archive)) {
            Path temporary = Files.createTempFile(history, id + "-", ".tmp",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            try {
                try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(temporary))) {
                    if (Files.isRegularFile(properties, LinkOption.NOFOLLOW_LINKS)) { add(output, properties, "job.properties"); }
                    if (Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                        try (var files = Files.list(directory)) {
                            for (Path file : files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                                    && (path.getFileName().toString().endsWith(".log")
                                            || java.util.Set.of("events.jsonl", "exit-code").contains(path.getFileName().toString())))
                                    .collect(java.util.stream.Collectors.toList())) {
                                add(output, file, file.getFileName().toString());
                            }
                        }
                    }
                }
                try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
                Files.move(temporary, archive, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                try (FileChannel channel = FileChannel.open(history, StandardOpenOption.READ)) { channel.force(true); }
            } finally { Files.deleteIfExists(temporary); }
        }
    }

    static void expireFailureHistory(Path history) throws IOException {
        if (!Files.isDirectory(history)) { return; }
        Instant cutoff = Instant.now().minusMillis(TimeUnit.DAYS.toMillis(7));
        try (var files = Files.list(history)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".zip")
                    && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).collect(java.util.stream.Collectors.toList())) {
                if (Files.getLastModifiedTime(file).toInstant().isBefore(cutoff)) { Files.delete(file); }
            }
        }
    }

    private static void add(ZipOutputStream output, Path file, String name) throws IOException {
        output.putNextEntry(new ZipEntry(name));
        Files.copy(file, output);
        output.closeEntry();
    }
}
