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
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonObject;

/** Keep the first small failed image per pool for diagnosis; never certify or repair its contents. */
final class Gfs2AllocationFailureEvidence {
    static final String DIRECTORY = ".cloudstack-allocation-failure-v1";
    static final long MAX_IMAGE_BYTES = 2L * 1024 * 1024 * 1024;
    private Gfs2AllocationFailureEvidence() { }

    static final class ContentMismatch extends CloudRuntimeException {
        ContentMismatch(Throwable cause) { super("GFS2_ALLOCATION_CONTENT_MISMATCH", cause); }
    }

    static String preserve(Path work, Path pool, String fingerprint, String volumeUuid, String format,
            String policy, long size, String sourceFormat, Path source, ContentMismatch failure) {
        Path evidence = pool.resolve(DIRECTORY);
        boolean claimed = false;
        try {
            Path image = work.resolve("image");
            if (!Files.isRegularFile(image, LinkOption.NOFOLLOW_LINKS) || Files.size(image) > MAX_IMAGE_BYTES) {
                return "evidence=skipped-image-limit (2 GiB); no existing evidence changed";
            }
            List<Path> files;
            try (var children = Files.list(work)) { files = children.toList(); }
            for (Path file : files) {
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) ||
                        (!file.equals(image) && !file.getFileName().toString().matches("zero-.*\\.raw"))) {
                    return "evidence=skipped-unexpected-stage-entry";
                }
            }
            // mkdir is the cross-host exclusive claim. Never replace any pre-existing file/directory/symlink.
            Files.createDirectory(evidence, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            claimed = true;
            JsonObject manifest = new JsonObject();
            manifest.addProperty("version", 1);
            manifest.addProperty("status", "unverified-content-mismatch");
            manifest.addProperty("capturedAt", Instant.now().toString());
            manifest.addProperty("requestFingerprint", fingerprint);
            manifest.addProperty("volumeUuid", volumeUuid);
            manifest.addProperty("format", format);
            manifest.addProperty("policy", policy);
            manifest.addProperty("expectedVirtualBytes", size);
            manifest.addProperty("operation", source == null ? "create" : "copy");
            manifest.addProperty("referenceFormat", source == null ? "raw" : sourceFormat);
            if (source != null) { manifest.addProperty("referencePath", source.toString()); }
            String cause = String.valueOf(failure.getCause());
            manifest.addProperty("failure", cause.substring(0, Math.min(cause.length(), 4096)));
            Path record = evidence.resolve("manifest.json");
            Files.writeString(record, manifest.toString(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            Files.setPosixFilePermissions(record, PosixFilePermissions.fromString("rw-------"));
            for (Path file : files) {
                Path retained = evidence.resolve(file.getFileName());
                Files.move(file, retained);
                Files.setPosixFilePermissions(retained, PosixFilePermissions.fromString("rw-------"));
                try (FileChannel channel = FileChannel.open(retained, StandardOpenOption.WRITE)) { channel.force(true); }
            }
            try (FileChannel channel = FileChannel.open(record, StandardOpenOption.WRITE)) { channel.force(true); }
            try (FileChannel channel = FileChannel.open(evidence, StandardOpenOption.READ)) { channel.force(true); }
            try (FileChannel channel = FileChannel.open(pool, StandardOpenOption.READ)) { channel.force(true); }
            return "evidence=retained:" + evidence + "; root-only diagnosis and explicit cleanup required";
        } catch (FileAlreadyExistsException occupied) {
            return "evidence=existing-slot-preserved:" + evidence;
        } catch (IOException | RuntimeException capture) {
            failure.addSuppressed(capture);
            return "evidence=capture-incomplete:" + evidence + "; slotClaimed=" + claimed;
        }
    }
}
