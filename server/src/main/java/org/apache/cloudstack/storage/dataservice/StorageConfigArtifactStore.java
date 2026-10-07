// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.cloudstack.storage.dataservice;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import com.cloud.utils.exception.CloudRuntimeException;

/** Protected management-local configuration artifacts. Names never come from archive entries. */
public final class StorageConfigArtifactStore {
    private final Path root;
    public StorageConfigArtifactStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            if (Files.isSymbolicLink(this.root) || !this.root.toRealPath().equals(this.root)
                    || !Files.getOwner(this.root).getName().equals(System.getProperty("user.name"))
                    || !Files.getPosixFilePermissions(this.root).equals(PosixFilePermissions.fromString("rwx------"))) {
                throw new CloudRuntimeException("Configuration artifact directory ownership or mode is unsafe");
            }
        } catch (IOException error) { throw new CloudRuntimeException("Configuration artifact directory is unavailable", error); }
    }
    private Path path(String uuid) {
        if (uuid == null || !uuid.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw new CloudRuntimeException("Invalid configuration artifact identity");
        }
        return root.resolve(uuid + ".zip");
    }
    public void write(String uuid, byte[] bytes) {
        if (bytes == null || bytes.length > StorageConfigArchive.MAX_ARCHIVE_BYTES) throw new CloudRuntimeException("Configuration artifact size exceeds limit");
        Path target = path(uuid);Path temporary = null;
        try {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new CloudRuntimeException("Configuration artifact is immutable");
            temporary = Files.createTempFile(root, ".configuration-", ".part", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            try (java.nio.channels.FileChannel file = java.nio.channels.FileChannel.open(temporary, java.nio.file.StandardOpenOption.WRITE)) {
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) file.write(buffer);
                file.force(true);
            }
            Files.createLink(target, temporary);
            Files.delete(temporary);temporary = null;
            try (java.nio.channels.FileChannel directory = java.nio.channels.FileChannel.open(root, java.nio.file.StandardOpenOption.READ)) { directory.force(true); }
        } catch (IOException error) { throw new CloudRuntimeException("Unable to persist configuration artifact", error); }
        finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
        }
    }
    public byte[] read(String uuid, String expectedSha256) {
        Path target = path(uuid);
        try {
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.size(target) > StorageConfigArchive.MAX_ARCHIVE_BYTES
                    || !Files.getOwner(target).getName().equals(System.getProperty("user.name"))
                    || !Files.getPosixFilePermissions(target).equals(PosixFilePermissions.fromString("rw-------"))) {
                throw new CloudRuntimeException("Configuration artifact identity or protection changed");
            }
            byte[] bytes = Files.readAllBytes(target);
            if (!StorageConfigArchive.sha256(bytes).equals(expectedSha256)) throw new CloudRuntimeException("Configuration artifact checksum changed");
            return bytes;
        } catch (IOException error) { throw new CloudRuntimeException("Configuration artifact is unavailable", error); }
    }
    public void remove(String uuid) {
        try { Files.deleteIfExists(path(uuid)); }
        catch (IOException error) { throw new CloudRuntimeException("Unable to remove expired configuration artifact", error); }
    }
    public static String chunkIdentity(String uuid, int index) {
        if (index < 0 || index >= 64 || uuid == null || !uuid.matches("[0-9a-fA-F-]{36}")) throw new CloudRuntimeException("Invalid configuration chunk identity");
        return java.util.UUID.nameUUIDFromBytes(("configuration-chunk:" + uuid + ":" + index).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

}
