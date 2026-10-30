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
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.apache.cloudstack.backup.AblestackCommvaultTakeBackupCommand;
import org.apache.cloudstack.backup.Backup;
import org.apache.cloudstack.backup.ThirdPartyBackupManifest;
import org.apache.cloudstack.backup.ThirdPartyBackupStart;
import org.apache.cloudstack.backup.ThirdPartyBackupRestore;
import com.cloud.storage.Volume;
import org.apache.logging.log4j.LogManager;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class LibvirtAblestackJobHistoryTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private Path helper() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path script = current.resolve("scripts/vm/hypervisor/kvm/thirdparty_job_history.py");
            if (Files.isRegularFile(script)) { return script; }
            current = current.getParent();
        }
        throw new AssertionError("History helper source is missing");
    }

    @Test
    public void javaReadsTheSameDurableReceiptAsPythonAndCannotRestartIt() throws Exception {
        Path root = folder.getRoot().toPath();
        Files.createDirectories(root.resolve("backup-start"));
        Files.createDirectories(root.resolve("staging-admission-closed"));
        ThirdPartyBackupStart.Plan plan = new ThirdPartyBackupStart.Plan();
        plan.jobId = "backup-1";
        plan.hostId = 4L;
        plan.backupPath = "/staging/ablestack-commvault/vm/2026.10.16.12.00.00";
        plan.manifest = ThirdPartyBackupManifest.create("ablestack-commvault", plan.jobId, "vm", "2026.10.16.12.00.00", "FULL",
                "host-4", plan.backupPath, "QCOW2", Collections.singletonList(new Backup.VolumeInfo("volume-1", "root.qcow2",
                        Volume.Type.ROOT, 1073741824L, 0L, null, null, null)), null, null);
        ThirdPartyBackupStart.Receipt start = new ThirdPartyBackupStart.Receipt();
        start.plan = plan;
        start.state = "STARTED";
        start.checkedAt = 1;
        Gson gson = new Gson();
        Files.writeString(root.resolve("backup-start/backup-1.json"), gson.toJson(start));
        Files.writeString(root.resolve("backup-start/backup-1.lock"), "");
        Files.writeString(root.resolve("staging-admission-closed/backup-1.closed"), "Staging cleanup completed");
        try (var channel = java.nio.channels.FileChannel.open(root.resolve("backup-start/backup-1.lock"),
                java.nio.file.StandardOpenOption.WRITE); var lock = channel.lock()) {
            assertThrows(java.io.IOException.class, () -> LibvirtAblestackJobHistory.execute(root, plan.jobId, "compact", helper()));
            assertTrue(Files.exists(root.resolve("backup-start/backup-1.json")));
            assertFalse(Files.exists(root.resolve("job-history.sqlite")));
        }
        JsonObject compacted = LibvirtAblestackJobHistory.execute(root, plan.jobId, "compact", helper());
        assertEquals(compacted, LibvirtAblestackJobHistory.execute(root, plan.jobId, "read", helper()));
        assertFalse(Files.exists(root.resolve("backup-start/backup-1.json")));
        assertFalse(Files.exists(root.resolve("backup-start/backup-1.lock")));
        AtomicBoolean launched = new AtomicBoolean();
        try (MockedStatic<LibvirtAblestackJobHistory> index = Mockito.mockStatic(LibvirtAblestackJobHistory.class)) {
            index.when(() -> LibvirtAblestackJobHistory.closed(plan.jobId)).thenReturn(true);
            index.when(() -> LibvirtAblestackJobHistory.read(plan.jobId)).thenReturn(compacted);
            ThirdPartyBackupStart.Receipt recorded = LibvirtAblestackBackupStartHelper.control(plan, "BACKUP_START_PREPARE", LogManager.getLogger());
            assertEquals("STARTED", recorded.state);
            assertEquals(gson.toJson(plan), gson.toJson(recorded.plan));
            assertEquals("STARTED", LibvirtAblestackBackupStartHelper.recorded(plan.jobId).state);
            var answer = LibvirtAblestackBackupStartHelper.launch(new AblestackCommvaultTakeBackupCommand("vm", plan.backupPath),
                    LogManager.getLogger(), "trace", "commvault", plan.jobId, "vm", plan.backupPath, "FULL", gson.toJson(plan), () -> {
                        launched.set(true);
                        return null;
                    });
            assertFalse(answer.getResult());
            assertFalse(launched.get());
            plan.backupPath = "/other";
            assertThrows(CloudRuntimeException.class,
                    () -> LibvirtAblestackBackupStartHelper.control(plan, "BACKUP_START_PREPARE", LogManager.getLogger()));
        }
    }

    @Test
    public void damagedIndexFailsClosedWithoutRemovingLegacyReceipts() throws Exception {
        Path root = folder.getRoot().toPath();
        Files.createDirectories(root.resolve("staging-admission-closed"));
        Files.writeString(root.resolve("staging-admission-closed/backup-1.closed"), "closed");
        Files.writeString(root.resolve("job-history.sqlite"), "broken database");
        assertThrows(java.io.IOException.class, () -> LibvirtAblestackJobHistory.execute(root, "backup-1", "compact", helper()));
        assertTrue(Files.exists(root.resolve("staging-admission-closed/backup-1.closed")));
    }

    private ThirdPartyBackupRestore.Plan restoreFixture(String outcome, String cleanup) throws Exception {
        Path root = folder.getRoot().toPath();
        ThirdPartyBackupRestore.Plan plan = new ThirdPartyBackupRestore.Plan();
        plan.jobId = "restore-backup-1-attempt-1";
        plan.hostId = 4;
        plan.hostName = "host-4";
        plan.targetVmName = "vm-1";
        plan.stageRoot = root.resolve("stage").toString();
        plan.destination = Path.of(plan.stageRoot, "ablestack-commvault", "restore", plan.jobId).toString();
        plan.startProtocolVersion = 1;
        plan.vmResultVersion = 1;
        plan.volumeUuids = Collections.singletonList("volume-1");
        plan.targetVolumeBytes = Collections.singletonList(1073741824L);
        plan.manifest = ThirdPartyBackupManifest.create("ablestack-commvault", "backup-1", "vm-1", "2026.10.16.12.00.00", "FULL",
                "host-4", root.resolve("backup").toString(), "QCOW2", Collections.singletonList(new Backup.VolumeInfo("volume-1", "root.qcow2",
                        Volume.Type.ROOT, 1073741824L, 0L, null, null, null)), null, null);
        ThirdPartyBackupManifest.Artifact metadata = new ThirdPartyBackupManifest.Artifact();
        metadata.backupUuid = "backup-1";
        metadata.path = root.resolve("backup").toString();
        metadata.sourceHost = "host-4";
        plan.manifest.setMetadata(metadata);
        plan.manifest.getOwnedArtifacts().forEach(artifact -> {
            artifact.completed = true;
            artifact.jobId = artifact.externalId = "external-job";
        });
        plan.manifest.setComplete(true);
        plan.manifest.validate(true);
        ThirdPartyBackupRestore.StartReceipt start = new ThirdPartyBackupRestore.StartReceipt();
        start.plan = plan;
        start.state = "INITIALIZED";
        start.checkedAt = 1;
        ThirdPartyBackupRestore.VmResult result = new ThirdPartyBackupRestore.VmResult();
        result.jobId = plan.jobId;
        result.sourceBackupUuid = "backup-1";
        result.targetVmName = plan.targetVmName;
        result.transactionId = "transaction-1";
        result.outcome = outcome;
        result.primaryCleanupState = cleanup;
        result.revision = 2;
        result.updatedAt = 2;
        for (String directory : java.util.List.of("restore-start", "restore-outcomes", "staging-admission-closed")) {
            Files.createDirectories(root.resolve(directory));
        }
        Files.writeString(root.resolve("restore-start/" + plan.jobId + ".json"), new Gson().toJson(start));
        Files.writeString(root.resolve("restore-outcomes/" + plan.jobId + ".json"), new Gson().toJson(result));
        Files.writeString(root.resolve("staging-admission-closed/" + plan.jobId + ".closed"), "Restore staging cleanup completed");
        return plan;
    }

    @Test
    public void deletedBackupRestoreDetailsBecomeOneSmallReplayFenceAndRetryIsIdempotent() throws Exception {
        Path root = folder.getRoot().toPath();
        ThirdPartyBackupRestore.Plan plan = restoreFixture("COMMITTED", "COMPLETED");
        JsonObject retired = LibvirtAblestackJobHistory.execute(root, plan.jobId, "compact-restore", helper());
        assertEquals("backup-1", retired.get("sourceBackupUuid").getAsString());
        assertEquals("RESTORE", retired.get("kind").getAsString());
        assertFalse(retired.has("plan"));
        assertFalse(retired.has("startReceipt"));
        assertFalse(retired.has("outcome"));
        for (String directory : java.util.List.of("restore-start", "restore-outcomes")) {
            assertFalse(Files.exists(root.resolve(directory).resolve(plan.jobId + ".json")));
            assertFalse(Files.exists(root.resolve(directory).resolve(plan.jobId + ".lock")));
        }
        assertFalse(Files.exists(root.resolve("staging-admission-closed/" + plan.jobId + ".closed")));
        assertEquals(retired, LibvirtAblestackJobHistory.execute(root, plan.jobId, "compact-restore", helper()));
        assertNull(LibvirtAblestackJobHistory.execute(root, "unseen-attempt", "read", helper()));
        try (MockedStatic<LibvirtAblestackJobHistory> index = Mockito.mockStatic(LibvirtAblestackJobHistory.class)) {
            index.when(() -> LibvirtAblestackJobHistory.retiredRestore(plan.jobId)).thenReturn(retired);
            assertThrows(CloudRuntimeException.class, () -> LibvirtAblestackVolumeRestoreHelper.controlStart(plan, false));
            assertThrows(CloudRuntimeException.class, () -> LibvirtAblestackVolumeRestoreHelper.restore(null, LogManager.getLogger(), plan,
                    plan.targetVmName, Collections.emptyList(), Collections.emptyList()));
            assertThrows(CloudRuntimeException.class, () -> LibvirtAblestackRestoreOutcome.initialize(plan));
            assertEquals(retired.get("closedReason").getAsString(), LibvirtAblestackVolumeRestoreHelper.startFailure(plan.jobId));
        }
    }

    @Test
    public void unfinishedPrimaryCleanupRetainsRestoreDetails() throws Exception {
        ThirdPartyBackupRestore.Plan plan = restoreFixture("COMMITTED", "WAITING");
        Path root = folder.getRoot().toPath();
        assertThrows(java.io.IOException.class, () -> LibvirtAblestackJobHistory.execute(root, plan.jobId, "compact-restore", helper()));
        assertTrue(Files.exists(root.resolve("restore-start/" + plan.jobId + ".json")));
        assertTrue(Files.exists(root.resolve("restore-outcomes/" + plan.jobId + ".json")));
        assertFalse(Files.exists(root.resolve("job-history.sqlite")));
    }

    @Test
    public void liveExecutionAndHeldOutcomeLockBothPreventRestoreRecordDeletion() throws Exception {
        ThirdPartyBackupRestore.Plan plan = restoreFixture("ROLLED_BACK", "COMPLETED");
        Path root = folder.getRoot().toPath();
        Path job = root.resolve("jobs").resolve(plan.jobId);
        Files.createDirectories(job);
        assertThrows(java.io.IOException.class, () -> LibvirtAblestackJobHistory.execute(root, plan.jobId, "compact-restore", helper()));
        Files.delete(job);
        Path lockFile = root.resolve("restore-outcomes/" + plan.jobId + ".lock");
        try (var channel = java.nio.channels.FileChannel.open(lockFile, java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.WRITE); var lock = channel.lock()) {
            assertThrows(java.io.IOException.class, () -> LibvirtAblestackJobHistory.execute(root, plan.jobId, "compact-restore", helper()));
        }
        assertTrue(Files.exists(root.resolve("restore-outcomes/" + plan.jobId + ".json")));
    }

    @Test
    public void ordinaryBackupCompactionDoesNotDiscardRestoreOwnership() throws Exception {
        ThirdPartyBackupRestore.Plan plan = restoreFixture("COMMITTED", "COMPLETED");
        Path root = folder.getRoot().toPath();
        assertNull(LibvirtAblestackJobHistory.execute(root, plan.jobId, "compact", helper()));
        assertTrue(Files.exists(root.resolve("restore-start/" + plan.jobId + ".json")));
        assertTrue(Files.exists(root.resolve("staging-admission-closed/" + plan.jobId + ".closed")));
    }

    @Test
    public void restoreRetirementUpgradesAnOlderAdmissionOnlyIndex() throws Exception {
        ThirdPartyBackupRestore.Plan plan = restoreFixture("COMMITTED", "COMPLETED");
        Path root = folder.getRoot().toPath();
        Path start = root.resolve("restore-start/" + plan.jobId + ".json");
        Path outcome = root.resolve("restore-outcomes/" + plan.jobId + ".json");
        String receipt = Files.readString(start);
        String result = Files.readString(outcome);
        Files.delete(start);
        Files.delete(outcome);
        LibvirtAblestackJobHistory.execute(root, plan.jobId, "compact", helper());
        Files.writeString(start, receipt);
        Files.writeString(outcome, result);
        assertEquals("RESTORE", LibvirtAblestackJobHistory.execute(root, plan.jobId, "compact-restore", helper()).get("kind").getAsString());
    }

    @Test
    public void corruptDatabaseNeverRemovesRestoreReceipts() throws Exception {
        ThirdPartyBackupRestore.Plan plan = restoreFixture("COMMITTED", "COMPLETED");
        Path root = folder.getRoot().toPath();
        Files.writeString(root.resolve("job-history.sqlite"), "not a database");
        assertThrows(java.io.IOException.class, () -> LibvirtAblestackJobHistory.execute(root, plan.jobId, "compact-restore", helper()));
        assertTrue(Files.exists(root.resolve("restore-start/" + plan.jobId + ".json")));
        assertTrue(Files.exists(root.resolve("restore-outcomes/" + plan.jobId + ".json")));
    }

    @Test
    public void unstartedFailureCanRetireWithoutInventingATransactionOutcome() throws Exception {
        ThirdPartyBackupRestore.Plan plan = restoreFixture("COMMITTED", "COMPLETED");
        Path root = folder.getRoot().toPath();
        Path start = root.resolve("restore-start/" + plan.jobId + ".json");
        ThirdPartyBackupRestore.StartReceipt receipt = new Gson().fromJson(Files.readString(start), ThirdPartyBackupRestore.StartReceipt.class);
        receipt.state = "START_FAILED";
        Files.writeString(start, new Gson().toJson(receipt));
        Files.delete(root.resolve("restore-outcomes/" + plan.jobId + ".json"));
        Files.delete(root.resolve("staging-admission-closed/" + plan.jobId + ".closed"));
        assertEquals("RESTORE", LibvirtAblestackJobHistory.execute(root, plan.jobId, "compact-restore", helper()).get("kind").getAsString());
    }

    @Test
    public void leftoverStagingPreventsRestoreRecordDeletion() throws Exception {
        ThirdPartyBackupRestore.Plan plan = restoreFixture("COMMITTED", "COMPLETED");
        Path root = folder.getRoot().toPath();
        Files.createDirectories(Path.of(plan.destination));
        assertThrows(java.io.IOException.class, () -> LibvirtAblestackJobHistory.execute(root, plan.jobId, "compact-restore", helper()));
        assertTrue(Files.exists(root.resolve("restore-start/" + plan.jobId + ".json")));
    }
}
