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

import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.cloud.hypervisor.kvm.storage.KVMPhysicalDisk;
import com.cloud.hypervisor.kvm.storage.KVMStoragePool;
import com.cloud.hypervisor.kvm.storage.KVMStoragePoolManager;
import com.cloud.storage.Storage;
import com.cloud.utils.script.Script;
import com.google.gson.Gson;
import org.apache.cloudstack.backup.ThirdPartyBackupManifest;
import org.apache.cloudstack.backup.ThirdPartyBackupRestore;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;
import org.apache.cloudstack.utils.qemu.QemuImg;
import org.apache.cloudstack.utils.qemu.QemuImgFile;
import org.apache.logging.log4j.Logger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;

public class LibvirtAblestackRbdVolumeRestoreTest {
    private static final String POOL = "restore-pool";
    private static final String PREPARED = "target-csrestore-prepared-transaction";
    private static final long VOLUME_BYTES = 1024L * 1024 * 1024;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void commvaultRawFullUsesTheNasImportRoutineInTheSelectedPool() throws Exception {
        assertStreamingFull("ablestack-commvault", true);
    }

    @Test
    public void netbackupRawFullUsesTheNasImportRoutineInTheSelectedPool() throws Exception {
        assertStreamingFull("ablestack-netbackup", true);
    }

    @Test
    public void veeamRawFullUsesTheNasImportRoutineInTheSelectedPool() throws Exception {
        assertStreamingFull("ablestack-veeam", true);
    }

    @Test
    public void rawFullStillConvertsToAFileForGfsDestinations() throws Exception {
        assertStreamingFull("ablestack-commvault", false);
    }

    private void assertStreamingFull(String provider, boolean rbd) throws Exception {
        Path job = folder.newFolder("job").toPath();
        Path destination = folder.newFolder("staging").toPath();
        Path payload = Files.createDirectories(destination.resolve("payload")).resolve("root.raw");
        Files.writeString(payload, "full");
        ThirdPartyBackupManifest.Artifact artifact = new ThirdPartyBackupManifest.Artifact();
        artifact.path = "/backup/root.raw";
        artifact.size = Files.size(payload);
        ThirdPartyBackupManifest.Volume volume = new ThirdPartyBackupManifest.Volume();
        volume.uuid = "source-volume";
        volume.engine = "RBD";
        volume.provisionedBytes = VOLUME_BYTES;
        volume.chain = List.of(artifact);
        ThirdPartyBackupRestore.Plan plan = new ThirdPartyBackupRestore.Plan();
        plan.jobId = "restore-full-test";
        plan.timeout = 60;
        plan.destination = destination.toString();
        plan.volumeUuids = List.of(volume.uuid);
        plan.manifest = Mockito.mock(ThirdPartyBackupManifest.class);
        Mockito.when(plan.manifest.getProvider()).thenReturn(provider);
        ThirdPartyBackupRestore.Request request = new ThirdPartyBackupRestore.Request();
        request.jobId = plan.jobId;
        request.destination = payload.toString();
        request.artifact = artifact;
        Files.writeString(job.resolve("volume-restore-ack-0.json"), new Gson().toJson(request));
        Constructor<LibvirtAblestackVolumeRestoreHelper> constructor = LibvirtAblestackVolumeRestoreHelper.class
                .getDeclaredConstructor(ThirdPartyBackupRestore.Plan.class, Logger.class);
        constructor.setAccessible(true);
        LibvirtAblestackVolumeRestoreHelper helper = constructor.newInstance(plan, Mockito.mock(Logger.class));
        ReflectionTestUtils.setField(helper, "job", job);
        ReflectionTestUtils.setField(helper, "artifactCount", 1);
        KVMStoragePool pool = pool();
        KVMStoragePoolManager manager = Mockito.mock(KVMStoragePoolManager.class);
        PrimaryDataStoreTO target = new Gson().fromJson("{}", PrimaryDataStoreTO.class);
        target.setUuid("target-pool");
        target.setPoolType(rbd ? Storage.StoragePoolType.RBD : Storage.StoragePoolType.SharedMountPoint);
        Mockito.when(manager.getStoragePool(Storage.StoragePoolType.RBD, "target-pool")).thenReturn(pool);
        String prepared = rbd ? PREPARED : folder.getRoot().toPath().resolve("prepared.qcow2").toString();
        String original = rbd ? POOL + "/target" : folder.getRoot().toPath().resolve("target.qcow2").toString();
        List<String> commands = new ArrayList<>();
        try (MockedStatic<KVMPhysicalDisk> disks = Mockito.mockStatic(KVMPhysicalDisk.class);
                MockedStatic<Script> scripts = Mockito.mockStatic(Script.class);
                MockedStatic<LibvirtAblestackAsyncBackupRunner> runner = Mockito.mockStatic(LibvirtAblestackAsyncBackupRunner.class);
                MockedStatic<LibvirtAblestackFileRestoreHelper> files = Mockito.mockStatic(LibvirtAblestackFileRestoreHelper.class);
                MockedConstruction<QemuImg> qemu = Mockito.mockConstruction(QemuImg.class, (mock, context) -> {
                    Mockito.when(mock.info(any(QemuImgFile.class))).thenReturn(Map.of(QemuImg.VIRTUAL_SIZE, String.valueOf(VOLUME_BYTES)));
                    Mockito.doAnswer(call -> {
                        if (!rbd) { Files.writeString(Path.of(((QemuImgFile) call.getArgument(1)).getFileName()), "prepared"); }
                        return null;
                    }).when(mock).convert(any(QemuImgFile.class), any(QemuImgFile.class));
                })) {
            disks.when(() -> KVMPhysicalDisk.RBDStringBuilder(eq(pool), anyString()))
                    .thenAnswer(call -> "rbd:" + call.getArgument(1));
            files.when(() -> LibvirtAblestackFileRestoreHelper.getFileVolumeFormat(any(), eq(original)))
                    .thenReturn(QemuImg.PhysicalDiskFormat.QCOW2);
            files.when(() -> LibvirtAblestackFileRestoreHelper.getRestorableFileBackupPath(any()))
                    .thenAnswer(call -> ((List<String>) call.getArgument(0)).get(0));
            scripts.when(() -> Script.runSimpleBashScriptWithFullResult(anyString(), anyInt())).thenAnswer(call -> {
                commands.add(call.getArgument(0));
                return "__CS_COMMAND_EXIT__=0";
            });

            ReflectionTestUtils.invokeMethod(helper, "prepareVolume", manager, volume, target, original, prepared, 0);

            assertFalse(Files.exists(payload));
            ArgumentCaptor<QemuImgFile> information = ArgumentCaptor.forClass(QemuImgFile.class);
            QemuImg reader = qemu.constructed().get(qemu.constructed().size() - 1);
            Mockito.verify(reader).info(information.capture());
            assertEquals(rbd ? "rbd:" + POOL + "/" + PREPARED : prepared, information.getValue().getFileName());
            if (rbd) {
                assertTrue(commands.stream().anyMatch(command -> command.contains(" --pool '" + POOL + "' import '")
                        && command.contains(payload.toString()) && command.contains("'" + PREPARED + "'")));
                for (QemuImg command : qemu.constructed()) {
                    Mockito.verify(command, never()).convert(any(QemuImgFile.class), any(QemuImgFile.class));
                }
            } else {
                assertTrue(commands.isEmpty());
                ArgumentCaptor<QemuImgFile> converted = ArgumentCaptor.forClass(QemuImgFile.class);
                Mockito.verify(qemu.constructed().get(0)).convert(any(QemuImgFile.class), converted.capture());
                assertEquals(QemuImg.PhysicalDiskFormat.QCOW2, converted.getValue().getFormat());
            }
        }
    }

    @Test
    public void qcow2ConversionQualifiesAnUnqualifiedRbdTarget() throws Exception {
        assertQcow2Conversion(PREPARED);
    }

    @Test
    public void qcow2ConversionDoesNotDuplicateAnExistingPoolPrefix() throws Exception {
        assertQcow2Conversion(POOL + "/" + PREPARED);
    }

    private void assertQcow2Conversion(String target) throws Exception {
        KVMStoragePool pool = pool();
        try (MockedStatic<KVMPhysicalDisk> disks = Mockito.mockStatic(KVMPhysicalDisk.class);
                MockedConstruction<QemuImg> qemu = Mockito.mockConstruction(QemuImg.class)) {
            disks.when(() -> KVMPhysicalDisk.RBDStringBuilder(eq(pool), anyString()))
                    .thenAnswer(call -> "rbd:" + call.getArgument(1));

            assertTrue(LibvirtAblestackRbdRestoreHelper.restoreRbdBackupToImage("test", pool, target,
                    List.of("/backup/root.qcow2"), 60, true));

            ArgumentCaptor<QemuImgFile> converted = ArgumentCaptor.forClass(QemuImgFile.class);
            Mockito.verify(qemu.constructed().get(0)).convert(any(QemuImgFile.class), converted.capture());
            assertEquals("rbd:" + POOL + "/" + PREPARED, converted.getValue().getFileName());
        }
    }

    @Test
    public void imageExistenceRenameAndCleanupUseTheSelectedPool() {
        KVMStoragePool pool = pool();
        List<String> commands = new ArrayList<>();
        try (MockedStatic<Script> scripts = Mockito.mockStatic(Script.class)) {
            scripts.when(() -> Script.runSimpleBashScriptForExitValue(anyString(), anyInt(), eq(false))).thenAnswer(call -> {
                String command = call.getArgument(0);
                commands.add(command);
                return command.contains(" snap ") ? 1 : 0;
            });
            scripts.when(() -> Script.runSimpleBashScriptWithFullResult(anyString(), anyInt())).thenAnswer(call -> {
                commands.add(call.getArgument(0));
                return "__CS_COMMAND_EXIT__=0";
            });

            assertTrue(LibvirtAblestackRbdRestoreHelper.rbdImageExists(pool, PREPARED, 60));
            assertTrue(LibvirtAblestackRbdRestoreHelper.renameRbdImage(pool, PREPARED, "target", 60));
            assertTrue(LibvirtAblestackRbdRestoreHelper.deleteRbdImageIfPresent(pool, PREPARED, 60));

            assertTrue(commands.stream().allMatch(command -> command.contains(" --pool '" + POOL + "' ")));
            assertTrue(commands.stream().anyMatch(command -> command.contains(" rename '" + PREPARED + "' 'target'")));
            assertTrue(commands.stream().anyMatch(command -> command.contains(" rm '" + PREPARED + "'")));
        }
    }

    private KVMStoragePool pool() {
        KVMStoragePool pool = Mockito.mock(KVMStoragePool.class);
        Mockito.when(pool.getSourceDir()).thenReturn(POOL);
        Mockito.when(pool.getSourceHost()).thenReturn("monitor1,monitor2");
        return pool;
    }
}
