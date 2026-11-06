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
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import com.cloud.agent.api.Answer;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.Gson;
import org.apache.cloudstack.backup.AblestackNasTakeBackupCommand;
import org.apache.cloudstack.backup.AblestackVolumeStagingCommand;
import org.apache.cloudstack.backup.ThirdPartyBackupStart;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;

public class LibvirtNasSourceGuardTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private AblestackNasTakeBackupCommand command;
    private ThirdPartyBackupStart.Plan plan;

    @Before public void setup() {
        command = new AblestackNasTakeBackupCommand("vm", "vm/point");
        command.setBackupJobId("backup-1");
        command.setBackupType("FULL");
        command.setCheckpointName("point");
        command.setBackupRepoType("NFS");
        command.setBackupRepoAddress("nas:/backup");
        command.setMountOptions("password=private-repository-secret");
        command.setBackupFiles(List.of("root.qcow2"));
        command.setVolumePaths(List.of("root"));
        command.setVolumePools(List.of());
        command.setWait(300);
        command.setWaitForCompletion(false);
        command.setStoppedSourceProtectionRequired(true);
        plan = new ThirdPartyBackupStart.Plan();
        plan.jobId = "backup-1";
        plan.hostId = 4;
        plan.backupPath = "vm/point";
        plan.protectStoppedSource = true;
        plan.nasSource = new ThirdPartyBackupStart.NasSource();
        plan.nasSource.vmName = "vm";
        plan.nasSource.engine = "QCOW2";
        plan.nasSource.checkpointName = "point";
        plan.nasSource.backupType = "FULL";
        plan.nasSource.volumeCount = 1;
        plan.nasSource.requestDigest = command.sourceRequestDigest();
        command.setSourceStartPlan(new Gson().toJson(plan));
    }

    @Test public void commandDigestSurvivesAgentAndDetachedWorkerSerialization() {
        String digest = command.sourceRequestDigest();
        var copy = new Gson().fromJson(new Gson().toJson(command), AblestackNasTakeBackupCommand.class);
        copy.setWaitForCompletion(true);
        assertEquals(digest, copy.sourceRequestDigest());
        assertTrue(LibvirtAblestackBackupStartHelper.nasPlan(copy).validNasSource());
        assertFalse(new Gson().toJson(plan).contains("private-repository-secret"));
        copy.setBackupRepoAddress("different:/backup");
        assertThrows(CloudRuntimeException.class, () -> LibvirtAblestackBackupStartHelper.nasPlan(copy));
    }

    @Test public void privateRuntimePlanCannotBeReplacedWithDifferentSources() throws Exception {
        Path file = temporary.newFolder().toPath().resolve("nas-source-plan.json");
        LibvirtAblestackNasSourceHelper.prepare(file, command, List.of("/primary/root.qcow2"), null);
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(file));
        LibvirtAblestackNasSourceHelper.prepare(file, command, List.of("/primary/root.qcow2"), null);
        assertThrows(CloudRuntimeException.class,
                () -> LibvirtAblestackNasSourceHelper.prepare(file, command, List.of("/primary/different.qcow2"), null));
    }

    @Test public void nasQcow2UsesTheSamePersistentVmStartFence() throws Exception {
        Path root = temporary.newFolder().toPath();
        LibvirtAblestackStoppedBackupGuard.prepare(root, plan);
        assertThrows(CloudRuntimeException.class, () -> LibvirtAblestackStoppedBackupGuard.lock(root, "vm", true));
        LibvirtAblestackStoppedBackupGuard.abort(root, plan);
        try (var lease = LibvirtAblestackStoppedBackupGuard.lock(root, "vm", true)) { assertTrue(plan.validNasSource()); }
    }

    @Test public void nasRbdSnapshotCompletionPermitsStartDuringExport() throws Exception {
        plan.nasSource.engine = "RBD_DIFF";
        Path root = temporary.newFolder().toPath();
        LibvirtAblestackStoppedBackupGuard.prepare(root, plan);
        var proof = LibvirtAblestackStoppedBackupGuard.status(root, plan);
        proof.phase = "SNAPSHOTS_COMPLETE";
        proof.released = true;
        Files.writeString(root.resolve("vm/backup-1.json"), new Gson().toJson(proof));
        try (var lease = LibvirtAblestackStoppedBackupGuard.lock(root, "vm", true)) { assertTrue(plan.sourceIsRbd()); }
    }

    @Test public void cleanupRpcRejectsAnActiveWorkerBeforeTouchingSourceOwnership() {
        try (var starts = Mockito.mockStatic(LibvirtAblestackBackupStartHelper.class);
                var workers = Mockito.mockStatic(LibvirtAblestackAsyncBackupRunner.class);
                var guards = Mockito.mockStatic(LibvirtAblestackStoppedBackupGuard.class)) {
            var receipt = new ThirdPartyBackupStart.Receipt();
            receipt.state = "STARTED";
            starts.when(() -> LibvirtAblestackBackupStartHelper.control(any(), eq("BACKUP_START_STATUS"), any())).thenReturn(receipt);
            workers.when(() -> LibvirtAblestackAsyncBackupRunner.getJobState(anyString(), any())).thenReturn("RUNNING");
            Answer answer = new LibvirtAblestackVolumeStagingCommandWrapper().execute(new AblestackVolumeStagingCommand(
                    "backup-1", "NAS_SOURCE_CLEANUP", -1, new Gson().toJson(plan)), mock(LibvirtComputingResource.class));
            assertFalse(answer.getResult());
            guards.verifyNoInteractions();
        }
    }

    @Test public void completedReleasedSourceCanBeCheckedAfterRuntimeFilesWereRetired() throws Exception {
        try (var starts = Mockito.mockStatic(LibvirtAblestackBackupStartHelper.class);
                var workers = Mockito.mockStatic(LibvirtAblestackAsyncBackupRunner.class);
                var guards = Mockito.mockStatic(LibvirtAblestackStoppedBackupGuard.class)) {
            var receipt = new ThirdPartyBackupStart.Receipt();
            receipt.state = "STARTED";
            starts.when(() -> LibvirtAblestackBackupStartHelper.control(any(), eq("BACKUP_START_STATUS"), any())).thenReturn(receipt);
            workers.when(() -> LibvirtAblestackAsyncBackupRunner.getJobState(anyString(), any())).thenReturn("COMPLETED");
            var proof = new ThirdPartyBackupStart.SourceProof();
            proof.plan = plan;
            proof.released = true;
            proof.phase = "SOURCE_CLEANED";
            guards.when(() -> LibvirtAblestackStoppedBackupGuard.status(any())).thenReturn(proof);
            Answer answer = new LibvirtAblestackVolumeStagingCommandWrapper().execute(new AblestackVolumeStagingCommand(
                    "backup-1", "NAS_SOURCE_CLEANUP", -1, new Gson().toJson(plan)), mock(LibvirtComputingResource.class));
            assertTrue(answer.getResult());
        }
    }
}
