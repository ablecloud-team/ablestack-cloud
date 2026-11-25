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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;
import org.apache.cloudstack.utils.qemu.QemuImg;
import org.apache.cloudstack.backup.ThirdPartyBackupManifest;
import org.apache.cloudstack.backup.ThirdPartyBackupRestore;
import org.apache.logging.log4j.Logger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.hypervisor.kvm.storage.KVMStoragePoolManager;
import com.google.gson.Gson;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class LibvirtAblestackVolumeRestoreTimeoutTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private LibvirtAblestackVolumeRestoreHelper helper(String provider, Path job, Path destination) throws Exception {
        ThirdPartyBackupRestore.Plan plan = new ThirdPartyBackupRestore.Plan();
        plan.jobId = "restore-timeout-test";
        plan.timeout = 1;
        plan.destination = destination.toString();
        plan.volumeUuids = List.of("source-volume");
        plan.manifest = Mockito.mock(ThirdPartyBackupManifest.class);
        Mockito.when(plan.manifest.getProvider()).thenReturn(provider);
        var constructor = LibvirtAblestackVolumeRestoreHelper.class.getDeclaredConstructor(ThirdPartyBackupRestore.Plan.class, Logger.class);
        constructor.setAccessible(true);
        var helper = constructor.newInstance(plan, Mockito.mock(Logger.class));
        ReflectionTestUtils.setField(helper, "job", job);
        ReflectionTestUtils.setField(helper, "artifactCount", 2);
        return helper;
    }

    private Path fetch(LibvirtAblestackVolumeRestoreHelper helper) {
        ThirdPartyBackupManifest.Artifact artifact = new ThirdPartyBackupManifest.Artifact();
        artifact.path = "/backup/disk.raw";
        return ReflectionTestUtils.invokeMethod(helper, "fetch", artifact, false, 0, 0);
    }

    private CompletableFuture<Void> acknowledge(Path job, int sequence) {
        return CompletableFuture.runAsync(() -> {
            try {
                Path request = job.resolve("volume-restore-request.json");
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!Files.exists(request) && System.nanoTime() < deadline) { Thread.sleep(5); }
                String json = Files.readString(request);
                assertEquals(sequence, new Gson().fromJson(json, ThirdPartyBackupRestore.Request.class).sequence);
                Thread.sleep(50);
                Files.writeString(job.resolve("volume-restore-ack-" + sequence + ".json"), json);
            } catch (Exception error) { throw new RuntimeException(error); }
        });
    }

    @Test public void earlierDownloadsAndImportsDoNotConsumeTheNextArtifactsBudget() throws Exception {
        for (String provider : List.of("ablestack-netbackup", "ablestack-veeam", "ablestack-commvault")) {
            Path job = folder.newFolder().toPath();
            Path destination = folder.newFolder().toPath();
            var helper = helper(provider, job, destination);
            try (var runner = Mockito.mockStatic(LibvirtAblestackAsyncBackupRunner.class)) {
                var first = acknowledge(job, 0);
                assertEquals(destination.resolve("payload/disk.raw"), fetch(helper));
                first.get(5, TimeUnit.SECONDS);
                // Simulate time spent importing the preceding artifact into primary storage.
                Thread.sleep(1100);
                var second = acknowledge(job, 1);
                assertEquals(destination.resolve("payload/disk.raw"), fetch(helper));
                second.get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test public void eachArtifactStillTimesOutAndRetainsItsUnconfirmedTransfer() throws Exception {
        Path job = folder.newFolder().toPath();
        var helper = helper("ablestack-netbackup", job, folder.newFolder().toPath());
        try (var runner = Mockito.mockStatic(LibvirtAblestackAsyncBackupRunner.class)) {
            assertTrue(assertThrows(CloudRuntimeException.class, () -> fetch(helper)).getMessage().contains("Timed out"));
            assertEquals(Boolean.TRUE, ReflectionTestUtils.getField(helper, "transferUncertain"));
            assertTrue(Files.exists(job.resolve("volume-restore-request.json")));
        }
    }

    @Test public void volumePreparationRetainsTheTimeoutReasonForTheVmRestoreResult() throws Exception {
        Path job = folder.newFolder().toPath();
        var helper = helper("ablestack-netbackup", job, folder.newFolder().toPath());
        var volume = new ThirdPartyBackupManifest.Volume();
        volume.uuid = "source-volume";
        var artifact = new ThirdPartyBackupManifest.Artifact();
        artifact.path = "/backup/disk.qcow2";
        volume.chain = List.of(artifact);
        try (var runner = Mockito.mockStatic(LibvirtAblestackAsyncBackupRunner.class);
                var files = Mockito.mockStatic(LibvirtAblestackFileRestoreHelper.class)) {
            files.when(() -> LibvirtAblestackFileRestoreHelper.getFileVolumeFormat(Mockito.any(), Mockito.anyString()))
                    .thenReturn(QemuImg.PhysicalDiskFormat.QCOW2);
            CloudRuntimeException failure = assertThrows(CloudRuntimeException.class, () -> ReflectionTestUtils.invokeMethod(helper,
                    "prepareVolume", Mockito.mock(KVMStoragePoolManager.class), volume, Mockito.mock(PrimaryDataStoreTO.class),
                    "/primary/original", "/primary/prepared", 0));
            assertTrue(failure.getMessage().contains("source-volume"));
            assertTrue(failure.getMessage().contains("Timed out waiting for external volume restore"));
            assertEquals(Boolean.TRUE, ReflectionTestUtils.getField(helper, "transferUncertain"));
            assertTrue(Files.exists(job.resolve("volume-restore-request.json")));
        }
    }

    @Test public void cancellationStillStopsAWaitingArtifact() throws Exception {
        Path job = folder.newFolder().toPath();
        var helper = helper("ablestack-netbackup", job, folder.newFolder().toPath());
        Files.writeString(job.resolve("volume-restore-cancel"), "Cancellation requested");
        assertTrue(assertThrows(CloudRuntimeException.class, () -> fetch(helper)).getMessage().contains("Cancellation requested"));
    }
}
