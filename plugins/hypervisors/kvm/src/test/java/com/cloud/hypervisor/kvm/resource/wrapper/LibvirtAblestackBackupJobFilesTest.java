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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class LibvirtAblestackBackupJobFilesTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private Path directory;
    private Path fence;
    private JsonObject plan;

    @Before
    public void setup() throws Exception {
        directory = folder.newFolder("job-1").toPath();
        fence = folder.getRoot().toPath().resolve("job-1.closed");
        Files.writeString(fence, "Backup staging completed\n");
        plan = new Gson().fromJson("{\"rbd\":true,\"manifest\":{\"backupUuid\":\"job-1\"},\"backupPath\":\"/staging/job-1\","
                + "\"checkpointName\":\"current\"}", JsonObject.class);
        plan.addProperty("stageRoot", folder.getRoot().toString());
        Files.writeString(directory.resolve("volume-finalization.json"), "{\"version\":1,\"backupUuid\":\"job-1\","
                + "\"state\":\"COMPLETED\",\"stagingCleanupState\":\"COMPLETED\",\"stagingPath\":\"/staging/job-1\"}");
    }

    @Test
    public void minimalFenceSurvivesRemovalAndConfirmsOnlyTheSameJob() throws Exception {
        assertFalse(LibvirtAblestackBackupJobFiles.completed(fence, "job-1"));
        LibvirtAblestackBackupJobFiles.confirmCompleted(directory, "job-1", plan, fence);
        Files.delete(directory.resolve("volume-finalization.json"));
        Files.delete(directory);
        assertTrue(LibvirtAblestackBackupJobFiles.completed(fence, "job-1"));
        assertFalse(LibvirtAblestackBackupJobFiles.completed(fence, "other-job"));
    }

    @Test
    public void reservationOrMissingFenceBlocksConfirmation() throws Exception {
        Path reservations = Files.createDirectories(folder.getRoot().toPath().resolve(".volume-reservations"));
        Files.writeString(reservations.resolve("job-1.json"), "{}");
        assertThrows(java.io.IOException.class, () -> LibvirtAblestackBackupJobFiles.confirmCompleted(directory, "job-1", plan, fence));
        assertFalse(LibvirtAblestackBackupJobFiles.completed(fence, "job-1"));
        Files.delete(reservations.resolve("job-1.json"));
        Files.delete(fence);
        assertThrows(java.io.IOException.class, () -> LibvirtAblestackBackupJobFiles.confirmCompleted(directory, "job-1", plan, fence));
    }

    @Test
    public void incompleteOrDifferentFinalizationCannotAuthorizeRemoval() throws Exception {
        Path file = directory.resolve("volume-finalization.json");
        String original = Files.readString(file);
        for (String result : new String[]{original.replace("job-1", "job-2"), original.replace("COMPLETED", "WAITING"),
                original.replace("stagingCleanupState", "unconfirmedState"), original.replace("version\":1", "version\":2")}) {
            Files.writeString(file, result);
            assertThrows(java.io.IOException.class, () -> LibvirtAblestackBackupJobFiles.confirmCompleted(directory, "job-1", plan, fence));
            assertFalse(LibvirtAblestackBackupJobFiles.completed(fence, "job-1"));
        }
    }

    @Test
    public void deletedBackupDoesNotNeedRetainedRecoveryPointFinalizationOrParentRetirement() throws Exception {
        plan.addProperty("backupPath", folder.getRoot().toPath().resolve("deleted-staging").toString());
        plan.addProperty("parentCheckpointName", "parent");
        Files.delete(directory.resolve("volume-finalization.json"));
        assertThrows(java.io.IOException.class, () -> LibvirtAblestackBackupJobFiles.confirmCompleted(directory, "job-1", plan, fence));
        LibvirtAblestackBackupJobFiles.confirmDeleted(directory, "job-1", plan, fence);
        assertTrue(LibvirtAblestackBackupJobFiles.completed(fence, "job-1"));
        Files.delete(directory);
        assertTrue(LibvirtAblestackBackupJobFiles.completed(fence, "job-1"));
    }

    @Test
    public void deletionCannotConfirmWhileStagingOrItsSymlinkStillExists() throws Exception {
        Path staging = folder.newFolder("staging").toPath();
        plan.addProperty("backupPath", staging.toString());
        assertThrows(java.io.IOException.class, () -> LibvirtAblestackBackupJobFiles.confirmDeleted(directory, "job-1", plan, fence));
        Files.delete(staging);
        Files.createSymbolicLink(staging, folder.getRoot().toPath().resolve("missing-target"));
        assertThrows(java.io.IOException.class, () -> LibvirtAblestackBackupJobFiles.confirmDeleted(directory, "job-1", plan, fence));
        assertFalse(LibvirtAblestackBackupJobFiles.completed(fence, "job-1"));
    }

    @Test
    public void deletionStillRequiresMatchingOwnershipReservationReleaseAndAdmissionClosure() throws Exception {
        plan.addProperty("backupPath", folder.getRoot().toPath().resolve("deleted-staging").toString());
        assertThrows(java.io.IOException.class, () -> LibvirtAblestackBackupJobFiles.confirmDeleted(directory, "other-job", plan, fence));
        Path reservations = Files.createDirectories(folder.getRoot().toPath().resolve(".volume-reservations"));
        Path reservation = Files.writeString(reservations.resolve("job-1.json"), "{}");
        assertThrows(java.io.IOException.class, () -> LibvirtAblestackBackupJobFiles.confirmDeleted(directory, "job-1", plan, fence));
        Files.delete(reservation);
        Files.delete(fence);
        assertThrows(java.io.IOException.class, () -> LibvirtAblestackBackupJobFiles.confirmDeleted(directory, "job-1", plan, fence));
    }

    @Test
    public void parentCleanupMustBeConfirmedBeforeItsInputsCanBeRemoved() throws Exception {
        plan.addProperty("parentCheckpointName", "parent");
        assertThrows(java.io.IOException.class, () -> LibvirtAblestackBackupJobFiles.confirmCompleted(directory, "job-1", plan, fence));
        Files.writeString(directory.resolve("source-cleanup.json"), "{\"version\":1,\"backupUuid\":\"job-1\",\"state\":\"COMPLETED\","
                + "\"checkpointName\":\"current\",\"parentCheckpointName\":\"parent\",\"errors\":[]}");
        LibvirtAblestackBackupJobFiles.confirmCompleted(directory, "job-1", plan, fence);
        assertTrue(LibvirtAblestackBackupJobFiles.completed(fence, "job-1"));
    }

    @Test
    public void qcow2CleanupNeedsItsDiskSpecificReceipt() throws Exception {
        plan.addProperty("rbd", false);
        plan.addProperty("parentCheckpointName", "parent");
        plan.add("diskPaths", new Gson().toJsonTree(new String[]{"/primary/disk"}));
        Path file = directory.resolve("source-cleanup.json");
        Files.writeString(file, "{\"version\":1,\"backupUuid\":\"job-1\",\"state\":\"COMPLETED\"}");
        assertThrows(java.io.IOException.class, () -> LibvirtAblestackBackupJobFiles.confirmCompleted(directory, "job-1", plan, fence));
        Files.writeString(file, "{\"version\":2,\"backupUuid\":\"job-1\",\"state\":\"COMPLETED\",\"engine\":\"QCOW2\","
                + "\"checkpointName\":\"current\",\"parentCheckpointName\":\"parent\",\"diskPaths\":[\"/primary/disk\"],\"errors\":[]}");
        LibvirtAblestackBackupJobFiles.confirmCompleted(directory, "job-1", plan, fence);
        assertTrue(LibvirtAblestackBackupJobFiles.completed(fence, "job-1"));
    }

    @Test
    public void retryKeepsTheOriginalCompressedFailureLogs() throws Exception {
        Path properties = folder.getRoot().toPath().resolve("job-1.properties");
        Files.writeString(properties, "state=FAILED\n");
        Files.writeString(directory.resolve("job.log"), "original failure");
        Files.writeString(directory.resolve("volume-plan.json"), "sensitive plan");
        Path history = folder.getRoot().toPath().resolve("failed-jobs");
        LibvirtAblestackBackupJobFiles.archiveFailure(directory, properties, history, "job-1");
        Files.writeString(directory.resolve("job.log"), "partial retry");
        LibvirtAblestackBackupJobFiles.archiveFailure(directory, properties, history, "job-1");
        try (ZipFile zip = new ZipFile(history.resolve("job-1.zip").toFile())) {
            assertEquals("original failure", new String(zip.getInputStream(zip.getEntry("job.log")).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            assertTrue(zip.getEntry("job.properties") != null);
            assertTrue(zip.getEntry("volume-plan.json") == null);
        }
        try (var files = Files.list(history)) { assertEquals(1, files.count()); }
    }

    @Test
    public void onlyExpiredFailureArchivesAreRemoved() throws Exception {
        Path history = folder.newFolder("history").toPath();
        Path old = Files.writeString(history.resolve("old.zip"), "old");
        Path recent = Files.writeString(history.resolve("recent.zip"), "recent");
        Path other = Files.writeString(history.resolve("unrelated.txt"), "keep");
        Files.setLastModifiedTime(old, FileTime.from(Instant.now().minusMillis(TimeUnit.DAYS.toMillis(8))));
        Files.setLastModifiedTime(other, Files.getLastModifiedTime(old));
        LibvirtAblestackBackupJobFiles.expireFailureHistory(history);
        assertFalse(Files.exists(old));
        assertTrue(Files.exists(recent));
        assertTrue(Files.exists(other));
    }
}
