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
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.apache.cloudstack.storage.to.VolumeExport;
import com.cloud.storage.Storage;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Bounded read-only materialization, independently compared before atomic publication. */
final class VerifiedVolumeExport {
    interface Runner { String run(List<String> command, long timeout); }
    private final Runner runner;
    VerifiedVolumeExport(Runner runner) { this.runner = runner; }

    VolumeObjectTO export(VolumeObjectTO source, String poolUuid, String input, Path directory, long timeout) throws IOException {
        if (source.requiresEncryption() || source.getEncryptFormat() != null || source.getSize() == null || source.getSize() <= 0 ||
                !List.of(Storage.ImageFormat.RAW, Storage.ImageFormat.QCOW2).contains(source.getFormat())) {
            throw new CloudRuntimeException("VOLUME_EXPORT_UNSUPPORTED: unencrypted RAW/QCOW2 required");
        }
        long deadline = System.nanoTime() + Math.max(1, Math.min(timeout > 0 ? timeout : 3600000, 14400000)) * 1000000L;
        String format = source.getFormat().toString().toLowerCase(Locale.ROOT);
        JsonObject before = json(run(deadline, "qemu-img", "info", "--output=json", "-f", format, input));
        if (before.get("virtual-size").getAsLong() != source.getSize()) {
            throw new CloudRuntimeException("VOLUME_EXPORT_SOURCE_SIZE_CHANGED");
        }
        JsonObject measure = json(run(deadline, "qemu-img", "measure", "--output=json", "-f", format, "-O", "qcow2", input));
        long required = Math.addExact(measure.get("required").getAsLong(), 64L * 1024 * 1024);
        if (Files.getFileStore(directory).getUsableSpace() < required) {
            throw new CloudRuntimeException("VOLUME_EXPORT_CAPACITY_INSUFFICIENT");
        }
        Path stage = Files.createTempDirectory(directory, ".volume-export-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path temporary = stage.resolve("image.qcow2");
        Path published = directory.resolve(UUID.randomUUID() + VolumeExport.SUFFIX);
        boolean success = false;
        try {
            run(deadline, "qemu-img", "convert", "-f", format, "-O", "qcow2", "-o", "preallocation=off", input, temporary.toString());
            JsonObject actual = json(run(deadline, "qemu-img", "info", "--output=json", temporary.toString()));
            if (!"qcow2".equals(actual.get("format").getAsString()) || actual.get("virtual-size").getAsLong() != source.getSize() ||
                    actual.has("backing-filename") || (actual.has("encrypted") && actual.get("encrypted").getAsBoolean())) {
                throw new CloudRuntimeException("VOLUME_EXPORT_FORMAT_OR_SIZE_INVALID");
            }
            run(deadline, "qemu-img", "check", "-f", "qcow2", temporary.toString());
            run(deadline, "qemu-img", "compare", "-f", format, "-F", "qcow2", input, temporary.toString());
            JsonObject after = json(run(deadline, "qemu-img", "info", "--output=json", "-f", format, input));
            if (!before.equals(after)) { throw new CloudRuntimeException("VOLUME_EXPORT_SOURCE_CHANGED"); }
            Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-r--r--"));
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            Files.move(temporary, published, StandardCopyOption.ATOMIC_MOVE);
            VolumeObjectTO result = new VolumeObjectTO();
            result.setSize(source.getSize()); result.setFormat(Storage.ImageFormat.QCOW2);
            result.setPath(published.getFileName().toString());
            result.setExportVerification(VolumeExport.receipt(source, poolUuid, Files.size(published)));
            success = true;
            return result;
        } finally {
            Files.deleteIfExists(temporary);
            Files.deleteIfExists(stage);
            if (!success) { Files.deleteIfExists(published); }
        }
    }
    private String run(long deadline, String... command) {
        long remaining = (deadline - System.nanoTime()) / 1000000L;
        if (remaining <= 0 || Thread.currentThread().isInterrupted()) { throw new CloudRuntimeException("VOLUME_EXPORT_INTERRUPTED_OR_TIMEOUT"); }
        return runner.run(List.of(command), remaining);
    }
    private static JsonObject json(String value) { return new JsonParser().parse(value).getAsJsonObject(); }
}
