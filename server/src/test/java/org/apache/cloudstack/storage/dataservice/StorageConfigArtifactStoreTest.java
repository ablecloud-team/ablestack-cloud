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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;
import org.junit.Assert;
import org.junit.Test;
import com.cloud.utils.exception.CloudRuntimeException;

public class StorageConfigArtifactStoreTest {
    private Path root() throws Exception {
        return Files.createTempDirectory("config-artifact-test-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    }
    @Test public void artifactsAreImmutablePrivateAndChecksumVerified() throws Exception {
        Path root = root();
        try {
            StorageConfigArtifactStore store = new StorageConfigArtifactStore(root);
            String uuid = UUID.randomUUID().toString();byte[] data = "synthetic configuration".getBytes(StandardCharsets.UTF_8);
            store.write(uuid, data);
            Assert.assertArrayEquals(data, store.read(uuid, StorageConfigArchive.sha256(data)));
            Assert.assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(root.resolve(uuid + ".zip")));
            Assert.assertThrows(CloudRuntimeException.class, () -> store.write(uuid, "other".getBytes(StandardCharsets.UTF_8)));
            Assert.assertArrayEquals(data, store.read(uuid, StorageConfigArchive.sha256(data)));
            Assert.assertThrows(CloudRuntimeException.class, () -> store.read(uuid, "wrong"));
            store.remove(uuid);Assert.assertFalse(Files.exists(root.resolve(uuid + ".zip")));
        } finally {
            try (java.util.stream.Stream<Path> files = Files.list(root)) { files.forEach(path -> { try { Files.delete(path); } catch (Exception error) { throw new RuntimeException(error); } }); }
            Files.delete(root);
        }
    }
    @Test public void unsafeNamesAndPublicDirectoriesCannotAccessArtifacts() throws Exception {
        Path root = root();
        try {
            StorageConfigArtifactStore store = new StorageConfigArtifactStore(root);
            Assert.assertThrows(CloudRuntimeException.class, () -> store.write("../escape", new byte[] {1}));
            Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxrwxrwx"));
            Assert.assertThrows(CloudRuntimeException.class, () -> new StorageConfigArtifactStore(root));
        } finally { Files.delete(root); }
    }
    @Test public void symlinkCannotReplaceArtifactFileOrProtectedRoot() throws Exception {
        Path root = root();Path outside = Files.createTempFile("config-outside-", ".txt");
        try {
            StorageConfigArtifactStore store = new StorageConfigArtifactStore(root);String uuid = UUID.randomUUID().toString();
            Files.createSymbolicLink(root.resolve(uuid + ".zip"), outside);
            Assert.assertThrows(CloudRuntimeException.class, () -> store.read(uuid, StorageConfigArchive.sha256(new byte[0])));
            Assert.assertThrows(CloudRuntimeException.class, () -> new StorageConfigArtifactStore(root.resolve(uuid + ".zip")));
            Files.delete(root.resolve(uuid + ".zip"));
        } finally { Files.delete(root);Files.delete(outside); }
    }
}
