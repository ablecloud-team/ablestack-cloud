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
import java.util.List;
import java.util.concurrent.TimeUnit;
import com.cloud.storage.Volume;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.Gson;
import org.apache.cloudstack.backup.Backup;
import org.apache.cloudstack.backup.ThirdPartyBackupManifest;
import org.apache.cloudstack.backup.ThirdPartyBackupStart;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class LibvirtStoppedBackupGuardTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private Path root;
    private ThirdPartyBackupStart.Plan plan;

    @Before public void setup() throws Exception {
        root = temporary.newFolder().toPath();
        plan = new ThirdPartyBackupStart.Plan();
        plan.jobId = "backup-1";
        plan.hostId = 4;
        plan.backupPath = "/staging/ablestack-netbackup/vm/point";
        plan.protectStoppedSource = true;
        plan.manifest = ThirdPartyBackupManifest.create("ablestack-netbackup", plan.jobId, "vm", "point", "FULL",
                "host-4", plan.backupPath, "QCOW2",
                List.of(new Backup.VolumeInfo("volume-1", "root", Volume.Type.ROOT, 1073741824L, 0L, null, null, null)), null, null);
    }

    @Test public void prepareBlocksStartUntilAnUnstartedAttemptIsFenced() throws Exception {
        LibvirtAblestackStoppedBackupGuard.prepare(root, plan);
        LibvirtAblestackStoppedBackupGuard.prepare(root, plan);
        assertFalse(LibvirtAblestackStoppedBackupGuard.status(root, plan).released);
        assertThrows(CloudRuntimeException.class, () -> LibvirtAblestackStoppedBackupGuard.lock(root, "vm", true));
        LibvirtAblestackStoppedBackupGuard.abort(root, plan);
        assertTrue(LibvirtAblestackStoppedBackupGuard.status(root, plan).released);
        try (var lease = LibvirtAblestackStoppedBackupGuard.lock(root, "vm", true)) {
            assertTrue(Files.isRegularFile(root.resolve("vm/start.lock")));
        }
        assertThrows(CloudRuntimeException.class, () -> LibvirtAblestackStoppedBackupGuard.prepare(root, plan));
    }

    @Test public void qcow2CannotBeReleasedWithRbdSnapshotPhase() throws Exception {
        LibvirtAblestackStoppedBackupGuard.prepare(root, plan);
        var proof = LibvirtAblestackStoppedBackupGuard.status(root, plan);
        proof.released = true;
        proof.phase = "SNAPSHOTS_COMPLETE";
        Files.writeString(root.resolve("vm/backup-1.json"), new Gson().toJson(proof));
        assertThrows(CloudRuntimeException.class, () -> LibvirtAblestackStoppedBackupGuard.lock(root, "vm", true));
        proof.phase = "SOURCE_CLEANED";
        Files.writeString(root.resolve("vm/backup-1.json"), new Gson().toJson(proof));
        try (var lease = LibvirtAblestackStoppedBackupGuard.lock(root, "vm", true)) { assertTrue(proof.released); }
    }

    @Test public void unknownOrDifferentOwnershipFailsClosed() throws Exception {
        LibvirtAblestackStoppedBackupGuard.prepare(root, plan);
        plan.hostId = 99;
        assertThrows(CloudRuntimeException.class, () -> LibvirtAblestackStoppedBackupGuard.status(root, plan));
        Files.writeString(root.resolve("vm/backup-1.json"), "{}");
        assertThrows(CloudRuntimeException.class, () -> LibvirtAblestackStoppedBackupGuard.lock(root, "vm", true));
    }

    @Test public void javaStartLockExcludesPythonSourcePreparation() throws Exception {
        Process probe = null;
        try (var lease = LibvirtAblestackStoppedBackupGuard.lock(root, "vm", false)) {
            probe = new ProcessBuilder("python3", "-c", "import fcntl,sys\nf=open(sys.argv[1],'a')\n"
                    + "try: fcntl.lockf(f,fcntl.LOCK_EX|fcntl.LOCK_NB)\nexcept BlockingIOError: sys.exit(42)",
                    root.resolve("vm/start.lock").toString()).start();
            assertTrue(probe.waitFor(10, TimeUnit.SECONDS));
            assertEquals(42, probe.exitValue());
        } finally { if (probe != null) { probe.destroyForcibly(); } }
        LibvirtAblestackStoppedBackupGuard.prepare(root, plan);
    }

    @Test public void startAlreadyInFlightPreventsAgentPreparation() throws Exception {
        var workers = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            try (var lease = LibvirtAblestackStoppedBackupGuard.lock(root, "vm", true)) {
                workers.submit(() -> {
                    assertThrows(CloudRuntimeException.class, () -> LibvirtAblestackStoppedBackupGuard.prepare(root, plan));
                }).get(2, TimeUnit.SECONDS);
                assertFalse(Files.exists(root.resolve("vm/backup-1.json")));
            }
            LibvirtAblestackStoppedBackupGuard.prepare(root, plan);
            assertFalse(LibvirtAblestackStoppedBackupGuard.status(root, plan).released);
        } finally { workers.shutdownNow(); }
    }

    @Test public void legacyRawSourceFenceDefersToTheDedicatedSharedRawGuard() throws Exception {
        var offline = new org.apache.cloudstack.backup.AblestackSharedRawBackup.Plan();
        offline.jobId = plan.jobId;
        offline.operation = "BACKUP";
        plan.manifest.setOfflineGuard(offline);
        Path root = temporary.newFolder().toPath();
        LibvirtAblestackStoppedBackupGuard.prepare(root, plan);
        try (var lease = LibvirtAblestackStoppedBackupGuard.lock(root, "vm", true)) {
            assertTrue(Files.isRegularFile(root.resolve("vm/backup-1.json")));
        }
    }
}
