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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.cloud.agent.api.Answer;
import com.cloud.storage.Storage;
import com.cloud.storage.Volume;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.script.Script;
import com.google.gson.Gson;
import org.apache.cloudstack.backup.AblestackCommvaultRestoreBackupCommand;
import org.apache.cloudstack.backup.AblestackNasRestoreBackupCommand;
import org.apache.cloudstack.backup.AblestackNetBackupRestoreBackupCommand;
import org.apache.cloudstack.backup.AblestackRestoreBackupCommand;
import org.apache.cloudstack.backup.AblestackVeeamRestoreBackupCommand;
import org.apache.cloudstack.backup.BackupAnswer;
import org.apache.cloudstack.utils.qemu.QemuImg;
import org.apache.cloudstack.utils.qemu.QemuImgFile;
import org.apache.logging.log4j.LogManager;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;

public class LibvirtAblestackRestoreProvisioningTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private MockedStatic<Script> scripts(List<String> commands, boolean createOutput, boolean fail) {
        MockedStatic<Script> scripts = Mockito.mockStatic(Script.class);
        scripts.when(() -> Script.runSimpleBashScriptWithFullResult(anyString(), anyInt())).thenAnswer(call -> {
            String command = call.getArgument(0);
            commands.add(command);
            if (createOutput && !fail && command.contains("qemu-img convert")) {
                Matcher output = Pattern.compile("'([^']+)' 2>&1;").matcher(command);
                assertTrue(command, output.find());
                Files.writeString(Path.of(output.group(1)), "restored-content");
            }
            return "\n__CMD_EXIT__=" + (fail ? "1" : "0");
        });
        return scripts;
    }

    private void convert(String target, QemuImg.PhysicalDiskFormat format) throws Exception {
        LibvirtAblestackFileRestoreHelper.convertFileVolumeWithQemuImg("test", LogManager.getLogger(), "/backup/full.qcow2",
                target, QemuImg.PhysicalDiskFormat.QCOW2, format, 30);
    }

    @Test
    public void thinRootAndSparseDataUseTheirOwnPolicies() throws Exception {
        List<String> commands = new ArrayList<>();
        List<LibvirtAblestackRestoreProgress.Update> updates = new ArrayList<>();
        try (var scope = LibvirtAblestackRestoreProvisioning.begin(Map.of("/primary/root", Storage.ProvisioningType.THIN,
                "/primary/data", Storage.ProvisioningType.SPARSE)); var scripts = scripts(commands, false, false);
                var progress = LibvirtAblestackRestoreProgress.begin(updates::add)) {
            LibvirtAblestackRestoreProgress.volume(1, 2, "/backup/root.qcow2");
            convert("/primary/root", QemuImg.PhysicalDiskFormat.QCOW2);
            LibvirtAblestackRestoreProgress.volume(2, 2, "/backup/datadisk.qcow2");
            convert("/primary/data", QemuImg.PhysicalDiskFormat.QCOW2);
        }
        assertTrue(commands.get(0), commands.get(0).contains("-S 4k -f qcow2 -O qcow2 -o preallocation=off"));
        assertTrue(commands.get(1), commands.get(1).contains("-S 4k -f qcow2 -O qcow2 -o preallocation=metadata"));
        assertEquals("ROOT · Restoring volume data", updates.get(1).details);
        assertEquals("DATA · Restoring volume data", updates.get(3).details);
    }

    @Test
    public void replacementFileKeepsSparsePolicyAndPromotesOnlyAfterConversion() throws Exception {
        Path target = folder.getRoot().toPath().resolve("data.qcow2");
        Path backup = folder.getRoot().toPath().resolve("backup.qcow2");
        Files.writeString(target, "original-content");
        Files.writeString(backup, "backup-content");
        List<String> commands = new ArrayList<>();
        try (var scope = LibvirtAblestackRestoreProvisioning.begin(Map.of(target.toString(), Storage.ProvisioningType.SPARSE));
                var scripts = scripts(commands, true, false);
                MockedConstruction<QemuImg> qemu = info();
                var capacity = Mockito.mockStatic(LibvirtAblestackRestoreCapacity.class)) {
            assertTrue(LibvirtAblestackFileRestoreHelper.replaceFileVolumeWithBackup("test", LogManager.getLogger(), target.toString(),
                    backup.toString(), 30, "restoring-"));
        }
        assertEquals("restored-content", Files.readString(target));
        assertEquals("backup-content", Files.readString(backup));
        assertTrue(commands.get(0), commands.get(0).contains("preallocation=metadata"));
        assertTrue(commands.get(0), commands.get(0).contains("restoring-"));
    }

    private MockedConstruction<QemuImg> info() {
        return Mockito.mockConstruction(QemuImg.class, (mock, context) -> Mockito.when(mock.info(any(QemuImgFile.class)))
                .thenReturn(Map.of(QemuImg.VIRTUAL_SIZE, "1048576", "file_format", "qcow2")));
    }

    @Test
    public void conversionFailureRetainsTheOriginalVolume() throws Exception {
        Path target = folder.getRoot().toPath().resolve("data.qcow2");
        Files.writeString(target, "original-content");
        List<String> commands = new ArrayList<>();
        try (var scope = LibvirtAblestackRestoreProvisioning.begin(Map.of(target.toString(), Storage.ProvisioningType.SPARSE));
                var scripts = scripts(commands, false, true); MockedConstruction<QemuImg> qemu = info();
                var capacity = Mockito.mockStatic(LibvirtAblestackRestoreCapacity.class)) {
            assertFalse(LibvirtAblestackFileRestoreHelper.replaceFileVolumeWithBackup("test", LogManager.getLogger(), target.toString(),
                    "/backup/full.qcow2", 30, "restoring-"));
        }
        assertEquals("original-content", Files.readString(target));
    }

    @Test
    public void fullAndIncrementalPreparationKeepsTheTargetPolicyAndSourceArtifacts() throws Exception {
        Path root = folder.getRoot().toPath();
        Path target = root.resolve("data.qcow2");
        Path prepared = root.resolve("prepared.qcow2");
        Path full = root.resolve("full.qcow2");
        Path diff = root.resolve("diff.qcow2");
        Files.writeString(full, "full-artifact");
        Files.writeString(diff, "diff-artifact");
        List<String> commands = new ArrayList<>();
        try (var scope = LibvirtAblestackRestoreProvisioning.begin(Map.of(target.toString(), Storage.ProvisioningType.SPARSE));
                var scripts = scripts(commands, true, false)) {
            assertTrue(LibvirtAblestackFileRestoreHelper.prepareFileVolume("test", LogManager.getLogger(), target.toString(), prepared.toString(),
                    List.of(full.toString(), diff.toString()), 30));
        }
        assertTrue(commands.get(0), commands.get(0).contains("preallocation=metadata"));
        assertTrue(commands.stream().anyMatch(command -> command.contains("qemu-img commit -f qcow2")));
        assertEquals("full-artifact", Files.readString(full));
        assertEquals("diff-artifact", Files.readString(diff));
    }

    @Test
    public void nestedTemporaryAliasesDoNotLeakIntoTheNextVolumeOrJob() {
        try (var scope = LibvirtAblestackRestoreProvisioning.begin(Map.of("/primary/data", Storage.ProvisioningType.SPARSE))) {
            try (var prepared = LibvirtAblestackRestoreProvisioning.temporary("/primary/data", "/primary/prepared")) {
                try (var temporary = LibvirtAblestackRestoreProvisioning.temporary("/primary/prepared", "/primary/temporary")) {
                    assertEquals(Storage.ProvisioningType.SPARSE, LibvirtAblestackRestoreProvisioning.type("/primary/temporary"));
                }
                assertThrows(CloudRuntimeException.class, () -> LibvirtAblestackRestoreProvisioning.type("/primary/temporary"));
            }
            assertThrows(CloudRuntimeException.class, () -> LibvirtAblestackRestoreProvisioning.type("/primary/prepared"));
        }
        assertNull(LibvirtAblestackRestoreProvisioning.type("/primary/data"));
    }

    @Test
    public void missingNewPolicyFailsBeforeMovingTheOriginal() throws Exception {
        Path target = folder.getRoot().toPath().resolve("data.qcow2");
        Files.writeString(target, "original-content");
        List<String> commands = new ArrayList<>();
        try (var scope = LibvirtAblestackRestoreProvisioning.begin(Map.of()); var scripts = scripts(commands, false, false);
                MockedConstruction<QemuImg> qemu = info()) {
            assertThrows(CloudRuntimeException.class, () -> LibvirtAblestackFileRestoreHelper.replaceFileVolumeWithBackup("test", LogManager.getLogger(),
                    target.toString(), "/backup/full.qcow2", 30, "restoring-"));
        }
        assertEquals("original-content", Files.readString(target));
        assertTrue(commands.isEmpty());
    }

    @Test
    public void sparseRawDoesNotReceiveUnsupportedMetadataPreallocation() throws Exception {
        List<String> commands = new ArrayList<>();
        try (var scope = LibvirtAblestackRestoreProvisioning.begin(Map.of("/primary/data", Storage.ProvisioningType.SPARSE));
                var scripts = scripts(commands, false, false)) {
            convert("/primary/data", QemuImg.PhysicalDiskFormat.RAW);
        }
        assertTrue(commands.get(0), commands.get(0).contains("-S 4k -f qcow2 -O raw -o preallocation=off"));
    }

    @Test
    public void fatVolumesRetainFullAllocation() throws Exception {
        List<String> commands = new ArrayList<>();
        try (var scope = LibvirtAblestackRestoreProvisioning.begin(Map.of("/primary/data", Storage.ProvisioningType.FAT));
                var scripts = scripts(commands, false, false)) {
            convert("/primary/data", QemuImg.PhysicalDiskFormat.QCOW2);
        }
        assertTrue(commands.get(0), commands.get(0).contains("-S 0 -f qcow2 -O qcow2 -o preallocation=full"));
    }

    @Test
    public void legacySavedCommandsKeepTheirPreviousSparseConversion() throws Exception {
        List<String> commands = new ArrayList<>();
        try (var scripts = scripts(commands, false, false)) { convert("/primary/data", QemuImg.PhysicalDiskFormat.QCOW2); }
        assertTrue(commands.get(0).contains("-S 4k"));
        assertFalse(commands.get(0).contains("preallocation="));
    }

    @Test
    public void concurrentRestoresKeepTheirPoliciesSeparate() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch entered = new CountDownLatch(2);
        try {
            List<java.util.concurrent.Future<Storage.ProvisioningType>> results = new ArrayList<>();
            for (Storage.ProvisioningType type : List.of(Storage.ProvisioningType.THIN, Storage.ProvisioningType.SPARSE)) {
                results.add(executor.submit(() -> {
                    try (var scope = LibvirtAblestackRestoreProvisioning.begin(Map.of("/primary/data", type))) {
                        entered.countDown();
                        assertTrue(entered.await(5, TimeUnit.SECONDS));
                        return LibvirtAblestackRestoreProvisioning.type("/primary/data");
                    } finally { assertNull(LibvirtAblestackRestoreProvisioning.type("/primary/data")); }
                }));
            }
            assertEquals(Storage.ProvisioningType.THIN, results.get(0).get(10, TimeUnit.SECONDS));
            assertEquals(Storage.ProvisioningType.SPARSE, results.get(1).get(10, TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); }
    }

    @Test
    public void allFourWrappersInstallAndCloseTheCommandPolicyScope() {
        Volume volume = Mockito.mock(Volume.class);
        Mockito.when(volume.getProvisioningType()).thenReturn(Storage.ProvisioningType.SPARSE);
        try (var runner = Mockito.mockStatic(LibvirtAblestackAsyncBackupRunner.class)) {
            runner.when(() -> LibvirtAblestackAsyncBackupRunner.startDetachedRestore(any(), any(), anyString(), anyString(), any(), any(), any()))
                    .thenAnswer(call -> {
                        assertEquals(Storage.ProvisioningType.SPARSE, LibvirtAblestackRestoreProvisioning.type("/primary/data"));
                        return new BackupAnswer(call.getArgument(0), true, "recorded");
                    });
            Gson gson = new Gson();
            for (AblestackRestoreBackupCommand command : List.of(gson.fromJson("{}", AblestackNasRestoreBackupCommand.class),
                    gson.fromJson("{}", AblestackCommvaultRestoreBackupCommand.class), gson.fromJson("{}", AblestackNetBackupRestoreBackupCommand.class),
                    new AblestackVeeamRestoreBackupCommand())) {
                command.setRestoreVolumeProvisioningTypes(List.of(volume), List.of("/primary/data"));
                assertTrue(dispatch(command).getResult());
                assertNull(LibvirtAblestackRestoreProvisioning.type("/primary/data"));
            }
        }
    }

    private Answer dispatch(AblestackRestoreBackupCommand command) {
        if (command instanceof AblestackNasRestoreBackupCommand) {
            var restore = (AblestackNasRestoreBackupCommand) command;
            restore.setWaitForCompletion(false);
            return new LibvirtAblestackNasRestoreBackupCommandWrapper().execute(restore, null);
        } else if (command instanceof AblestackCommvaultRestoreBackupCommand) {
            var restore = (AblestackCommvaultRestoreBackupCommand) command;
            restore.setWaitForCompletion(false);
            return new LibvirtAblestackCommvaultRestoreBackupCommandWrapper().execute(restore, null);
        } else if (command instanceof AblestackNetBackupRestoreBackupCommand) {
            var restore = (AblestackNetBackupRestoreBackupCommand) command;
            restore.setWaitForCompletion(false);
            return new LibvirtAblestackNetBackupRestoreBackupCommandWrapper().execute(restore, null);
        }
        var restore = (AblestackVeeamRestoreBackupCommand) command;
        restore.setWaitForCompletion(false);
        return new LibvirtAblestackVeeamRestoreBackupCommandWrapper().execute(restore, null);
    }
}
