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

import static org.junit.Assert.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import org.apache.cloudstack.backup.AblestackSharedRawBackup;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import com.google.gson.Gson;

public class LibvirtAblestackSharedRawGuardTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private Path marker(Path source) throws Exception {
        var directory = source.getParent().resolve(".ablestack-offline-raw"); Files.createDirectories(directory);
        StringBuilder hash = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(source.getFileName().toString().getBytes(StandardCharsets.UTF_8)))
            hash.append(String.format("%02x", value & 255));
        return directory.resolve(hash + ".json");
    }
    private void owner(Path source, String job, String operation) throws Exception {
        var plan = new AblestackSharedRawBackup.Plan(); plan.jobId = job; plan.operation = operation;
        Files.writeString(marker(source), new Gson().toJson(plan));
    }
    @Test public void ordinaryStartAndAttachAreAllowedUntilOfflineOwnershipExists() throws Exception {
        Path source = temp.newFile("disk").toPath();
        try (var free = LibvirtAblestackSharedRawGuard.acquire(List.of(source.toString()), null)) { }
        owner(source, "backup", "BACKUP");
        assertThrows(IllegalStateException.class, () -> LibvirtAblestackSharedRawGuard.acquire(List.of(source.toString()), null));
    }
    @Test public void onlyTheOwningRestoreCanWriteAndItsLockPreventsConcurrentRelease() throws Exception {
        Path source = temp.newFile("disk").toPath(); owner(source, "restore", "RESTORE");
        assertThrows(IllegalStateException.class, () -> LibvirtAblestackSharedRawGuard.acquire(List.of(source.toString()), "other"));
        try (var writing = LibvirtAblestackSharedRawGuard.acquire(List.of(source.toString()), "restore")) {
            assertThrows(Exception.class, () -> LibvirtAblestackSharedRawGuard.acquire(List.of(source.toString()), "restore"));
        }
        try (var released = LibvirtAblestackSharedRawGuard.acquire(List.of(source.toString()), "restore")) { }
    }
    @Test public void aDelayedRestoreCannotStartAfterItsMarkerWasRemoved() throws Exception {
        Path source = temp.newFile("disk").toPath(); owner(source, "restore", "RESTORE"); Files.delete(marker(source));
        assertThrows(IllegalStateException.class, () -> LibvirtAblestackSharedRawGuard.acquire(List.of(source.toString()), "restore"));
    }
    @Test public void guardDirectorySymlinkIsRejected() throws Exception {
        Path source = temp.newFile("disk").toPath();
        Files.createSymbolicLink(source.getParent().resolve(".ablestack-offline-raw"), temp.newFolder("outside").toPath());
        assertThrows(IllegalStateException.class, () -> LibvirtAblestackSharedRawGuard.acquire(List.of(source.toString()), null));
    }
}
