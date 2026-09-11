// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.hypervisor.kvm.resource.wrapper;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Map;

import org.apache.cloudstack.backup.BackupAnswer;
import org.apache.cloudstack.backup.RestoreBackupCommand;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;
import org.junit.Assert;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.mockito.MockedConstruction;
import org.apache.cloudstack.utils.qemu.QemuImg;
import org.apache.cloudstack.utils.qemu.QemuImgFile;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.agent.api.Answer;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.storage.Storage;
import com.cloud.utils.Pair;
import com.cloud.utils.script.Script;
import com.cloud.vm.VirtualMachine;

@RunWith(MockitoJUnitRunner.class)
public class LibvirtRestoreBackupCommandWrapperTest {

    private LibvirtRestoreBackupCommandWrapper wrapper;
    private LibvirtComputingResource libvirtComputingResource;
    private RestoreBackupCommand command;
    private MockedConstruction<QemuImg> qemuImages;

    @Before
    public void setUp() {
        qemuImages = Mockito.mockConstruction(QemuImg.class, (mock, context) ->
                when(mock.info(any(QemuImgFile.class))).thenReturn(Map.of()));
        wrapper = new LibvirtRestoreBackupCommandWrapper();
        libvirtComputingResource = Mockito.mock(LibvirtComputingResource.class);
        command = Mockito.mock(RestoreBackupCommand.class);
    }

    @After
    public void tearDown() {
        qemuImages.close();
    }

    @Test
    public void testExecuteWithVmExistsNull() throws Exception {
        when(command.getVmName()).thenReturn("test-vm");
        when(command.getBackupPath()).thenReturn("backup/path");
        when(command.getBackupRepoAddress()).thenReturn("192.168.1.100:/backup");
        when(command.getBackupRepoType()).thenReturn("nfs");
        when(command.getMountOptions()).thenReturn("rw");
        when(command.isVmExists()).thenReturn(null);
        when(command.getDiskType()).thenReturn("root");
        when(command.getRestoreVolumeSizes()).thenReturn(Arrays.asList(1024L));
        when(command.getWait()).thenReturn(60);
        PrimaryDataStoreTO primaryDataStore = Mockito.mock(PrimaryDataStoreTO.class);
        when(primaryDataStore.getPoolType()).thenReturn(Storage.StoragePoolType.NetworkFilesystem);
        when(command.getRestoreVolumePools()).thenReturn(Arrays.asList(primaryDataStore));
        when(command.getVolumePaths()).thenReturn(Arrays.asList("/var/lib/libvirt/images/volume-123"));
        when(command.getBackupFiles()).thenReturn(Arrays.asList("volume-123"));
        when(command.getVmState()).thenReturn(VirtualMachine.State.Running);
        when(command.getMountTimeout()).thenReturn(30);

        try (MockedStatic<Files> filesMock = mockStatic(Files.class)) {
            Path tempPath = Mockito.mock(Path.class);
            when(tempPath.toString()).thenReturn("/tmp/csbackup.abc123");
            filesMock.when(() -> Files.createTempDirectory(anyString())).thenReturn(tempPath);

            try (MockedStatic<Script> scriptMock = mockStatic(Script.class)) {
                scriptMock.when(() -> Script.getExecutableAbsolutePath(anyString()))
                        .thenAnswer(invocation -> invocation.getArgument(0));
                scriptMock.when(() -> Script.executeCommand(any(String[].class)))
                        .thenReturn(null);
                scriptMock.when(() -> Script.executeCommandForExitValue(any(String[].class)))
                        .thenReturn(0);
                scriptMock.when(() -> Script.executeCommandForExitValue(any(String[].class)))
                        .thenReturn(0); // Other commands success
                scriptMock.when(() -> Script.executePipedCommands(anyList(), anyLong()))
                        .thenReturn(new Pair<>(0, "vda"));

                filesMock.when(() -> Files.deleteIfExists(any(Path.class))).thenReturn(true);

                Answer result = wrapper.execute(command, libvirtComputingResource);

                Assert.assertNotNull(result);
                Assert.assertTrue(result instanceof BackupAnswer);
                BackupAnswer backupAnswer = (BackupAnswer) result;
                Assert.assertTrue(backupAnswer.getResult());
                Assert.assertEquals("volume-123", backupAnswer.getDetails());
            }
        }
    }

    @Test
    public void testExecuteWithVmExistsTrue() throws Exception {
        when(command.getVmName()).thenReturn("test-vm");
        when(command.getBackupPath()).thenReturn("backup/path");
        when(command.getBackupRepoAddress()).thenReturn("192.168.1.100:/backup");
        when(command.getBackupRepoType()).thenReturn("nfs");
        when(command.getMountOptions()).thenReturn("rw");
        when(command.isVmExists()).thenReturn(true);
        when(command.getDiskType()).thenReturn("root");
        PrimaryDataStoreTO primaryDataStore = Mockito.mock(PrimaryDataStoreTO.class);
        when(primaryDataStore.getPoolType()).thenReturn(Storage.StoragePoolType.NetworkFilesystem);
        when(command.getRestoreVolumePools()).thenReturn(Arrays.asList(primaryDataStore));
        when(command.getVolumePaths()).thenReturn(Arrays.asList("/var/lib/libvirt/images/volume-123"));
        when(command.getBackupVolumesUUIDs()).thenReturn(Arrays.asList("volume-123"));
        when(command.getBackupFiles()).thenReturn(Arrays.asList("volume-123"));
        when(command.getMountTimeout()).thenReturn(30);

        try (MockedStatic<Files> filesMock = mockStatic(Files.class)) {
            Path tempPath = Mockito.mock(Path.class);
            when(tempPath.toString()).thenReturn("/tmp/csbackup.abc123");
            filesMock.when(() -> Files.createTempDirectory(anyString())).thenReturn(tempPath);

            try (MockedStatic<Script> scriptMock = mockStatic(Script.class)) {
                scriptMock.when(() -> Script.executeCommand(any(String[].class)))
                        .thenReturn(null);
                scriptMock.when(() -> Script.executeCommandForExitValue(any(String[].class)))
                        .thenReturn(0);
                scriptMock.when(() -> Script.executeCommandForExitValue(any(String[].class)))
                        .thenReturn(0); // Other commands success

                filesMock.when(() -> Files.deleteIfExists(any(Path.class))).thenReturn(true);

                Answer result = wrapper.execute(command, libvirtComputingResource);

                Assert.assertNotNull(result);
                Assert.assertTrue(result instanceof BackupAnswer);
                BackupAnswer backupAnswer = (BackupAnswer) result;
                Assert.assertTrue(backupAnswer.getResult());
            }
        }
    }

    @Test
    public void testExecuteWithVmExistsFalse() throws Exception {
        when(command.getVmName()).thenReturn("test-vm");
        when(command.getBackupPath()).thenReturn("backup/path");
        when(command.getBackupRepoAddress()).thenReturn("192.168.1.100:/backup");
        when(command.getBackupRepoType()).thenReturn("nfs");
        when(command.getMountOptions()).thenReturn("rw");
        when(command.isVmExists()).thenReturn(false);
        when(command.getDiskType()).thenReturn("root");
        PrimaryDataStoreTO primaryDataStore = Mockito.mock(PrimaryDataStoreTO.class);
        when(primaryDataStore.getPoolType()).thenReturn(Storage.StoragePoolType.NetworkFilesystem);
        when(command.getRestoreVolumePools()).thenReturn(Arrays.asList(primaryDataStore));
        when(command.getVolumePaths()).thenReturn(Arrays.asList("/var/lib/libvirt/images/volume-123"));
        when(command.getBackupFiles()).thenReturn(Arrays.asList("volume-123"));
        when(command.getMountTimeout()).thenReturn(30);

        try (MockedStatic<Files> filesMock = mockStatic(Files.class)) {
            Path tempPath = Mockito.mock(Path.class);
            when(tempPath.toString()).thenReturn("/tmp/csbackup.abc123");
            filesMock.when(() -> Files.createTempDirectory(anyString())).thenReturn(tempPath);

            try (MockedStatic<Script> scriptMock = mockStatic(Script.class)) {
                scriptMock.when(() -> Script.executeCommand(any(String[].class)))
                        .thenReturn(null);
                scriptMock.when(() -> Script.executeCommandForExitValue(any(String[].class)))
                        .thenReturn(0);
                scriptMock.when(() -> Script.executeCommandForExitValue(any(String[].class)))
                        .thenReturn(0); // Other commands success

                filesMock.when(() -> Files.deleteIfExists(any(Path.class))).thenReturn(true);

                Answer result = wrapper.execute(command, libvirtComputingResource);

                Assert.assertNotNull(result);
                Assert.assertTrue(result instanceof BackupAnswer);
                BackupAnswer backupAnswer = (BackupAnswer) result;
                Assert.assertTrue(backupAnswer.getResult());
            }
        }
    }

    @Test
    public void testExecuteWithCifsMountType() throws Exception {
        when(command.getVmName()).thenReturn("test-vm");
        when(command.getBackupPath()).thenReturn("backup/path");
        when(command.getBackupRepoAddress()).thenReturn("//192.168.1.100/backup");
        when(command.getBackupRepoType()).thenReturn("cifs");
        when(command.getMountOptions()).thenReturn("username=user,password=pass");
        when(command.isVmExists()).thenReturn(null);
        when(command.getDiskType()).thenReturn("root");
        when(command.getRestoreVolumeSizes()).thenReturn(Arrays.asList(1024L));
        when(command.getWait()).thenReturn(60);
        PrimaryDataStoreTO primaryDataStore = Mockito.mock(PrimaryDataStoreTO.class);
        when(primaryDataStore.getPoolType()).thenReturn(Storage.StoragePoolType.NetworkFilesystem);
        when(command.getRestoreVolumePools()).thenReturn(Arrays.asList(primaryDataStore));
        when(command.getVolumePaths()).thenReturn(Arrays.asList("/var/lib/libvirt/images/volume-123"));
        when(command.getBackupFiles()).thenReturn(Arrays.asList("volume-123"));
        when(command.getVmState()).thenReturn(VirtualMachine.State.Running);
        when(command.getMountTimeout()).thenReturn(30);

        try (MockedStatic<Files> filesMock = mockStatic(Files.class)) {
            Path tempPath = Mockito.mock(Path.class);
            when(tempPath.toString()).thenReturn("/tmp/csbackup.abc123");
            filesMock.when(() -> Files.createTempDirectory(anyString())).thenReturn(tempPath);

            try (MockedStatic<Script> scriptMock = mockStatic(Script.class)) {
                scriptMock.when(() -> Script.getExecutableAbsolutePath(Mockito.anyString()))
                        .thenAnswer(invocation -> invocation.getArgument(0));
                scriptMock.when(() ->
                                Script.executePipedCommands(anyList(), anyLong()))
                        .thenReturn(new Pair<>(0, "vda")); // Current device

                filesMock.when(() -> Files.deleteIfExists(any(Path.class))).thenReturn(true);

                Answer result = wrapper.execute(command, libvirtComputingResource);

                Assert.assertNotNull(result);
                Assert.assertTrue(result instanceof BackupAnswer);
                BackupAnswer backupAnswer = (BackupAnswer) result;
                Assert.assertTrue(backupAnswer.getResult());
            }
        }
    }

    @Test
    public void testExecuteWithMountFailure() throws Exception {
        lenient().when(command.getVmName()).thenReturn("test-vm");
        lenient().when(command.getBackupPath()).thenReturn("backup/path");
        lenient().when(command.getBackupRepoAddress()).thenReturn("192.168.1.100:/backup");
        lenient().when(command.getBackupRepoType()).thenReturn("nfs");
        lenient().when(command.getMountOptions()).thenReturn("rw");
        lenient().when(command.isVmExists()).thenReturn(null);
        lenient().when(command.getDiskType()).thenReturn("root");
        lenient().when(command.getRestoreVolumeSizes()).thenReturn(Arrays.asList(1024L));
        lenient().when(command.getWait()).thenReturn(60);
        PrimaryDataStoreTO primaryDataStore = Mockito.mock(PrimaryDataStoreTO.class);
        lenient().when(primaryDataStore.getPoolType()).thenReturn(Storage.StoragePoolType.NetworkFilesystem);
        when(command.getRestoreVolumePools()).thenReturn(Arrays.asList(primaryDataStore));
        lenient().when(command.getVolumePaths()).thenReturn(Arrays.asList("/var/lib/libvirt/images/volume-123"));
        when(command.getBackupFiles()).thenReturn(Arrays.asList("volume-123"));
        lenient().when(command.getVmState()).thenReturn(VirtualMachine.State.Running);
        lenient().when(command.getMountTimeout()).thenReturn(30);

        try (MockedStatic<Files> filesMock = mockStatic(Files.class)) {
            Path tempPath = Mockito.mock(Path.class);
            when(tempPath.toString()).thenReturn("/tmp/csbackup.abc123");
            filesMock.when(() -> Files.createTempDirectory(anyString())).thenReturn(tempPath);

            try (MockedStatic<Script> scriptMock = mockStatic(Script.class)) {
                String mountCommand = "sudo mount -t nfs 192.168.1.100:/backup /tmp/csbackup.abc123 -o rw";
                scriptMock.when(() -> Script.runSimpleBashScriptForExitValue(mountCommand, 30000, false))
                        .thenReturn(1); // Mount failure

                Answer result = wrapper.execute(command, libvirtComputingResource);

                Assert.assertNotNull(result);
                Assert.assertTrue(result instanceof BackupAnswer);
                BackupAnswer backupAnswer = (BackupAnswer) result;
                Assert.assertFalse(backupAnswer.getResult());
                Assert.assertTrue(backupAnswer.getDetails().contains("Failed to mount the backup repository"));
                scriptMock.verify(() -> Script.runSimpleBashScriptForExitValue(mountCommand, 30000, false));
                filesMock.verify(() -> Files.deleteIfExists(Paths.get("/tmp/csbackup.abc123")));
            }
        }
    }

    @Test
    public void testExecuteWithBackupFileNotFound() throws Exception {
        when(command.getVmName()).thenReturn("test-vm");
        when(command.getBackupPath()).thenReturn("backup/path");
        when(command.getBackupRepoAddress()).thenReturn("192.168.1.100:/backup");
        when(command.getBackupRepoType()).thenReturn("nfs");
        when(command.getMountOptions()).thenReturn("rw");
        when(command.isVmExists()).thenReturn(null);
        when(command.getDiskType()).thenReturn("root");
        when(command.getRestoreVolumeSizes()).thenReturn(Arrays.asList(1024L));
        when(command.getWait()).thenReturn(60);
        PrimaryDataStoreTO primaryDataStore = Mockito.mock(PrimaryDataStoreTO.class);
        when(primaryDataStore.getPoolType()).thenReturn(Storage.StoragePoolType.NetworkFilesystem);
        when(command.getRestoreVolumePools()).thenReturn(Arrays.asList(primaryDataStore));
        when(command.getVolumePaths()).thenReturn(Arrays.asList("/var/lib/libvirt/images/volume-123"));
        when(command.getBackupFiles()).thenReturn(Arrays.asList("volume-123"));
        when(command.getVmState()).thenReturn(VirtualMachine.State.Running);
        when(command.getMountTimeout()).thenReturn(30);

        try (MockedStatic<Files> filesMock = mockStatic(Files.class)) {
            Path tempPath = Mockito.mock(Path.class);
            when(tempPath.toString()).thenReturn("/tmp/csbackup.abc123");
            filesMock.when(() -> Files.createTempDirectory(anyString())).thenReturn(tempPath);

            try (MockedStatic<Script> scriptMock = mockStatic(Script.class)) {
                scriptMock.when(() -> Script.executeCommand(any(String[].class)))
                        .thenReturn(null);
                scriptMock.when(() -> Script.executeCommandForExitValue(any(String[].class)))
                        .thenAnswer(invocation -> {
                            String command = Arrays.toString(invocation.getArguments());
                            if (command.contains("-f")) {
                                return 1; // File not found
                            }
                            return 0; // Other commands success
                        });

                filesMock.when(() -> Files.deleteIfExists(any(Path.class))).thenReturn(true);

                Answer result = wrapper.execute(command, libvirtComputingResource);

                Assert.assertNotNull(result);
                Assert.assertTrue(result instanceof BackupAnswer);
                BackupAnswer backupAnswer = (BackupAnswer) result;
                Assert.assertFalse(backupAnswer.getResult());
                Assert.assertTrue(backupAnswer.getDetails().contains("Backup file for the volume [volume-123] does not exist"));
            }
        }
    }

    @Test
    public void testExecuteWithCorruptBackupFile() throws Exception {
        when(command.getVmName()).thenReturn("test-vm");
        when(command.getBackupPath()).thenReturn("backup/path");
        when(command.getBackupRepoAddress()).thenReturn("192.168.1.100:/backup");
        when(command.getBackupRepoType()).thenReturn("nfs");
        when(command.getMountOptions()).thenReturn("rw");
        when(command.isVmExists()).thenReturn(null);
        when(command.getDiskType()).thenReturn("root");
        when(command.getRestoreVolumeSizes()).thenReturn(Arrays.asList(1024L));
        when(command.getWait()).thenReturn(60);
        PrimaryDataStoreTO primaryDataStore = Mockito.mock(PrimaryDataStoreTO.class);
        when(primaryDataStore.getPoolType()).thenReturn(Storage.StoragePoolType.NetworkFilesystem);
        when(command.getRestoreVolumePools()).thenReturn(Arrays.asList(primaryDataStore));
        when(command.getVolumePaths()).thenReturn(Arrays.asList("/var/lib/libvirt/images/volume-123"));
        when(command.getBackupFiles()).thenReturn(Arrays.asList("volume-123"));
        when(command.getVmState()).thenReturn(VirtualMachine.State.Running);
        when(command.getMountTimeout()).thenReturn(30);

        try (MockedStatic<Files> filesMock = mockStatic(Files.class)) {
            Path tempPath = Mockito.mock(Path.class);
            when(tempPath.toString()).thenReturn("/tmp/csbackup.abc123");
            filesMock.when(() -> Files.createTempDirectory(anyString())).thenReturn(tempPath);

            try (MockedStatic<Script> scriptMock = mockStatic(Script.class)) {
                scriptMock.when(() -> Script.executeCommand(any(String[].class)))
                        .thenReturn(null);
                scriptMock.when(() -> Script.executeCommandForExitValue(any(String[].class)))
                        .thenAnswer(invocation -> {
                            String command = Arrays.toString(invocation.getArguments());
                            if (command.contains("-f")) {
                                return 0; // File exists
                            } else if (command.contains("check")) {
                                return 1; // Corrupt file
                            }
                            return 0; // Other commands success
                        });

                filesMock.when(() -> Files.deleteIfExists(any(Path.class))).thenReturn(true);

                Answer result = wrapper.execute(command, libvirtComputingResource);

                Assert.assertNotNull(result);
                Assert.assertTrue(result instanceof BackupAnswer);
                BackupAnswer backupAnswer = (BackupAnswer) result;
                Assert.assertFalse(backupAnswer.getResult());
                Assert.assertTrue(backupAnswer.getDetails().contains("Backup qcow2 file for the volume [volume-123] is corrupt"));
            }
        }
    }

    @Test
    public void testExecuteWithRsyncFailure() throws Exception {
        when(command.getVmName()).thenReturn("test-vm");
        when(command.getBackupPath()).thenReturn("backup/path");
        when(command.getBackupRepoAddress()).thenReturn("192.168.1.100:/backup");
        when(command.getBackupRepoType()).thenReturn("nfs");
        when(command.getMountOptions()).thenReturn("rw");
        when(command.isVmExists()).thenReturn(null);
        when(command.getDiskType()).thenReturn("root");
        when(command.getRestoreVolumeSizes()).thenReturn(Arrays.asList(1024L));
        when(command.getWait()).thenReturn(60);
        PrimaryDataStoreTO primaryDataStore = Mockito.mock(PrimaryDataStoreTO.class);
        when(primaryDataStore.getPoolType()).thenReturn(Storage.StoragePoolType.NetworkFilesystem);
        when(command.getRestoreVolumePools()).thenReturn(Arrays.asList(primaryDataStore));
        when(command.getVolumePaths()).thenReturn(Arrays.asList("/var/lib/libvirt/images/volume-123"));
        when(command.getBackupFiles()).thenReturn(Arrays.asList("volume-123"));
        when(command.getVmState()).thenReturn(VirtualMachine.State.Running);
        when(command.getMountTimeout()).thenReturn(30);

        try (MockedStatic<Files> filesMock = mockStatic(Files.class)) {
            Path tempPath = Mockito.mock(Path.class);
            when(tempPath.toString()).thenReturn("/tmp/csbackup.abc123");
            filesMock.when(() -> Files.createTempDirectory(anyString())).thenReturn(tempPath);

            try (MockedStatic<Script> scriptMock = mockStatic(Script.class)) {
                scriptMock.when(() -> Script.getExecutableAbsolutePath(anyString()))
                        .thenAnswer(invocation -> invocation.getArgument(0));
                scriptMock.when(() -> Script.executeCommand(any(String[].class)))
                        .thenReturn(null);
                scriptMock.when(() -> Script.executeCommandForExitValue(anyLong(), any(String[].class)))
                        .thenAnswer(invocation -> {
                            if (Arrays.stream(invocation.getArguments()).map(String::valueOf).anyMatch("rsync"::equals)) {
                                return 1; // Rsync failure
                            }
                            return 0;
                        });
                scriptMock.when(() -> Script.executeCommandForExitValue(any(String[].class)))
                        .thenAnswer(invocation -> {
                            String command = Arrays.toString(invocation.getArguments());
                            if (command.contains("-f")) {
                                return 0; // File exists
                            } else if (command.contains("check")) {
                                return 0; // File is valid
                            } else if (command.contains("qemu-img info") && command.contains("backing-filename")) {
                                return 1; // No backing chain — exercise the rsync path (full backups)
                            }
                            return 0; // Other commands success
                        });

                filesMock.when(() -> Files.deleteIfExists(any(Path.class))).thenReturn(true);

                Answer result = wrapper.execute(command, libvirtComputingResource);

                Assert.assertNotNull(result);
                Assert.assertTrue(result instanceof BackupAnswer);
                BackupAnswer backupAnswer = (BackupAnswer) result;
                Assert.assertFalse(backupAnswer.getResult());
                Assert.assertTrue(backupAnswer.getDetails().contains("Unable to restore contents from the backup volume [volume-123]"));
            }
        }
    }

    @Test
    public void testExecuteWithAttachVolumeFailure() throws Exception {
        when(command.getVmName()).thenReturn("test-vm");
        when(command.getBackupPath()).thenReturn("backup/path");
        when(command.getBackupRepoAddress()).thenReturn("192.168.1.100:/backup");
        when(command.getBackupRepoType()).thenReturn("nfs");
        when(command.getMountOptions()).thenReturn("rw");
        when(command.isVmExists()).thenReturn(null);
        when(command.getDiskType()).thenReturn("root");
        when(command.getRestoreVolumeSizes()).thenReturn(Arrays.asList(1024L));
        when(command.getWait()).thenReturn(60);
        PrimaryDataStoreTO primaryDataStore = Mockito.mock(PrimaryDataStoreTO.class);
        when(primaryDataStore.getPoolType()).thenReturn(Storage.StoragePoolType.NetworkFilesystem);
        when(command.getRestoreVolumePools()).thenReturn(Arrays.asList(primaryDataStore));
        when(command.getVolumePaths()).thenReturn(Arrays.asList("/var/lib/libvirt/images/volume-123"));
        when(command.getBackupFiles()).thenReturn(Arrays.asList("volume-123"));
        when(command.getVmState()).thenReturn(VirtualMachine.State.Running);
        when(command.getMountTimeout()).thenReturn(30);

        try (MockedStatic<Files> filesMock = mockStatic(Files.class)) {
            Path tempPath = Mockito.mock(Path.class);
            when(tempPath.toString()).thenReturn("/tmp/csbackup.abc123");
            filesMock.when(() -> Files.createTempDirectory(anyString())).thenReturn(tempPath);

            try (MockedStatic<Script> scriptMock = mockStatic(Script.class)) {
                scriptMock.when(() -> Script.getExecutableAbsolutePath(anyString()))
                        .thenAnswer(invocation -> invocation.getArgument(0));
                scriptMock.when(() -> Script.executeCommand(any(String[].class)))
                        .thenReturn(null);
                scriptMock.when(() -> Script.executeCommandForExitValue(any(String[].class)))
                        .thenAnswer(invocation -> {
                            if (Arrays.stream(invocation.getArguments()).map(String::valueOf).anyMatch("attach-disk"::equals)) {
                                return 1; // Attach failure
                            }
                            return 0;
                        });
                scriptMock.when(() -> Script.executePipedCommands(anyList(), anyLong()))
                        .thenReturn(new Pair<>(0, "vda"));

                filesMock.when(() -> Files.deleteIfExists(any(Path.class))).thenReturn(true);

                Answer result = wrapper.execute(command, libvirtComputingResource);

                Assert.assertNotNull(result);
                Assert.assertTrue(result instanceof BackupAnswer);
                BackupAnswer backupAnswer = (BackupAnswer) result;
                Assert.assertFalse(backupAnswer.getResult());
                Assert.assertTrue(backupAnswer.getDetails().contains("Failed to attach volume to VM: test-vm"));
            }
        }
    }

    @Test
    public void testExecuteWithTempDirectoryCreationFailure() throws Exception {
        lenient().when(command.getVmName()).thenReturn("test-vm");
        lenient().when(command.getBackupPath()).thenReturn("backup/path");
        lenient().when(command.getBackupRepoAddress()).thenReturn("192.168.1.100:/backup");
        lenient().when(command.getBackupRepoType()).thenReturn("nfs");
        lenient().when(command.getMountOptions()).thenReturn("rw");
        lenient().when(command.isVmExists()).thenReturn(null);
        lenient().when(command.getDiskType()).thenReturn("root");
        lenient().when(command.getRestoreVolumeSizes()).thenReturn(Arrays.asList(1024L));
        lenient().when(command.getWait()).thenReturn(60);
        PrimaryDataStoreTO primaryDataStore = Mockito.mock(PrimaryDataStoreTO.class);
        lenient().when(primaryDataStore.getPoolType()).thenReturn(Storage.StoragePoolType.NetworkFilesystem);
        when(command.getRestoreVolumePools()).thenReturn(Arrays.asList(primaryDataStore));
        lenient().when(command.getVolumePaths()).thenReturn(Arrays.asList("/var/lib/libvirt/images/volume-123"));
        when(command.getBackupFiles()).thenReturn(Arrays.asList("volume-123"));
        lenient().when(command.getVmState()).thenReturn(VirtualMachine.State.Running);
        lenient().when(command.getMountTimeout()).thenReturn(30);

        try (MockedStatic<Files> filesMock = mockStatic(Files.class)) {
            filesMock.when(() -> Files.createTempDirectory(anyString()))
                    .thenThrow(new IOException("Failed to create temp directory"));

            Answer result = wrapper.execute(command, libvirtComputingResource);

            Assert.assertNotNull(result);
            Assert.assertTrue(result instanceof BackupAnswer);
            BackupAnswer backupAnswer = (BackupAnswer) result;
            Assert.assertFalse(backupAnswer.getResult());
            Assert.assertTrue(backupAnswer.getDetails().contains("Failed to create the tmp mount directory for restore"));
        }
    }

    @Test
    public void testExecuteWithMultipleVolumes() throws Exception {
        when(command.getVmName()).thenReturn("test-vm");
        when(command.getBackupPath()).thenReturn("backup/path");
        when(command.getBackupRepoAddress()).thenReturn("192.168.1.100:/backup");
        when(command.getBackupRepoType()).thenReturn("nfs");
        when(command.getMountOptions()).thenReturn("rw");
        when(command.isVmExists()).thenReturn(true);
        when(command.getDiskType()).thenReturn("root");
        PrimaryDataStoreTO primaryDataStore1 = Mockito.mock(PrimaryDataStoreTO.class);
        PrimaryDataStoreTO primaryDataStore2 = Mockito.mock(PrimaryDataStoreTO.class);
        when(primaryDataStore1.getPoolType()).thenReturn(Storage.StoragePoolType.NetworkFilesystem);
        when(primaryDataStore2.getPoolType()).thenReturn(Storage.StoragePoolType.NetworkFilesystem);
        when(command.getRestoreVolumePools()).thenReturn(Arrays.asList(
                primaryDataStore1,
                primaryDataStore2
        ));
        when(command.getVolumePaths()).thenReturn(Arrays.asList(
                "/var/lib/libvirt/images/volume-123",
                "/var/lib/libvirt/images/volume-456"
        ));
        when(command.getBackupVolumesUUIDs()).thenReturn(Arrays.asList("volume-123", "volume-456"));
        when(command.getBackupFiles()).thenReturn(Arrays.asList("volume-123", "volume-456"));
        when(command.getMountTimeout()).thenReturn(30);

        try (MockedStatic<Files> filesMock = mockStatic(Files.class)) {
            Path tempPath = Mockito.mock(Path.class);
            when(tempPath.toString()).thenReturn("/tmp/csbackup.abc123");
            filesMock.when(() -> Files.createTempDirectory(anyString())).thenReturn(tempPath);

            try (MockedStatic<Script> scriptMock = mockStatic(Script.class)) {
                scriptMock.when(() -> Script.executeCommandForExitValue(any(String[].class)))
                        .thenReturn(0); // All commands success
                scriptMock.when(() -> Script.executeCommand(any(String[].class)))
                        .thenReturn(null);
                scriptMock.when(() -> Script.executeCommandForExitValue(any(String[].class)))
                        .thenReturn(0); // All commands success

                filesMock.when(() -> Files.deleteIfExists(any(Path.class))).thenReturn(true);

                Answer result = wrapper.execute(command, libvirtComputingResource);

                Assert.assertNotNull(result);
                Assert.assertTrue(result instanceof BackupAnswer);
                BackupAnswer backupAnswer = (BackupAnswer) result;
                Assert.assertTrue(backupAnswer.getResult());
            }
        }
    }
    @Test
    public void mountPreservesArgumentsTimeoutAndCifsOptions() throws Exception {
        java.lang.reflect.Method mount = LibvirtRestoreBackupCommandWrapper.class.getDeclaredMethod(
                "mountBackupDirectory", String.class, String.class, String.class, Integer.class);
        mount.setAccessible(true);
        String address = "//fixture/share with spaces";
        String options = "username=fixture,password=fixture value";
        try (MockedStatic<Script> script = mockStatic(Script.class)) {
            script.when(() -> Script.getExecutableAbsolutePath("mount")).thenReturn("/usr/bin/mount");
            script.when(() -> Script.executeCommandForExitValue(anyLong(), any(String[].class))).thenAnswer(call -> {
                Assert.assertEquals(37000L, (long) call.getArgument(0));
                Object[] args = call.getArguments();
                Assert.assertEquals("sudo", args[1]);
                Assert.assertEquals("/usr/bin/mount", args[2]);
                Assert.assertEquals(address, args[5]);
                Assert.assertEquals(options + ",nobrl", args[8]);
                return 0;
            });
            String directory = (String) mount.invoke(wrapper, address, "cifs", options, 37000);
            Assert.assertTrue(Files.isDirectory(Path.of(directory)));
            Files.delete(Path.of(directory));
        }
    }

    @Test
    public void linstorAndFileAttachmentPreserveDiskFormatAndCache() throws Exception {
        java.lang.reflect.Method attach = LibvirtRestoreBackupCommandWrapper.class.getDeclaredMethod(
                "attachVolumeToVm", com.cloud.hypervisor.kvm.storage.KVMStoragePoolManager.class,
                String.class, PrimaryDataStoreTO.class, String.class, String.class);
        attach.setAccessible(true);
        PrimaryDataStoreTO pool = Mockito.mock(PrimaryDataStoreTO.class);
        java.util.List<String> captured = new java.util.ArrayList<>();
        try (MockedStatic<Script> script = mockStatic(Script.class)) {
            script.when(() -> Script.getExecutableAbsolutePath(anyString())).thenAnswer(call -> call.getArgument(0));
            script.when(() -> Script.executePipedCommands(anyList(), anyLong())).thenReturn(new Pair<>(0, "vda"));
            script.when(() -> Script.executeCommandForExitValue(any(String[].class))).thenAnswer(call -> {
                captured.clear();
                for (Object argument : call.getArguments()) {
                    captured.add((String) argument);
                }
                return 0;
            });
            for (Storage.StoragePoolType type : Arrays.asList(Storage.StoragePoolType.Linstor, Storage.StoragePoolType.NetworkFilesystem)) {
                when(pool.getPoolType()).thenReturn(type);
                Assert.assertEquals(true, attach.invoke(wrapper, null, "vm with spaces", pool, "/volume path", "writeback"));
                Assert.assertEquals("vm with spaces", captured.get(2));
                Assert.assertEquals("/volume path", captured.get(3));
                Assert.assertEquals("vdb", captured.get(4));
                Assert.assertEquals("writeback", captured.get(captured.size() - 1));
                Assert.assertEquals(type != Storage.StoragePoolType.Linstor, captured.contains("qcow2"));
            }
        }
    }

    @Test
    public void rbdAttachmentUsesReadableXmlAndCleansItUp() throws Exception {
        java.lang.reflect.Method attach = LibvirtRestoreBackupCommandWrapper.class.getDeclaredMethod(
                "attachVolumeToVm", com.cloud.hypervisor.kvm.storage.KVMStoragePoolManager.class,
                String.class, PrimaryDataStoreTO.class, String.class, String.class);
        attach.setAccessible(true);
        PrimaryDataStoreTO pool = Mockito.mock(PrimaryDataStoreTO.class);
        when(pool.getPoolType()).thenReturn(Storage.StoragePoolType.RBD);
        when(pool.getHost()).thenReturn("ceph.example");
        when(pool.getUuid()).thenReturn("pool-id");
        com.cloud.hypervisor.kvm.storage.KVMStoragePoolManager manager = Mockito.mock(com.cloud.hypervisor.kvm.storage.KVMStoragePoolManager.class);
        java.util.List<Path> files = new java.util.ArrayList<>();
        try (MockedStatic<Script> script = mockStatic(Script.class)) {
            script.when(() -> Script.getExecutableAbsolutePath(anyString())).thenAnswer(call -> call.getArgument(0));
            script.when(() -> Script.executePipedCommands(anyList(), anyLong())).thenReturn(new Pair<>(0, "vda"));
            script.when(() -> Script.executeCommandForExitValue(any(String[].class))).thenAnswer(call -> {
                Assert.assertEquals("attach-device", call.getArgument(1));
                Path xml = Path.of((String) call.getArgument(3));
                files.add(xml);
                org.w3c.dom.Document document = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.toFile());
                Assert.assertEquals("raw", document.getElementsByTagName("driver").item(0).getAttributes().getNamedItem("type").getNodeValue());
                Assert.assertEquals("writeback", document.getElementsByTagName("driver").item(0).getAttributes().getNamedItem("cache").getNodeValue());
                return 0;
            });
            Assert.assertEquals(true, attach.invoke(wrapper, manager, "fixture-vm", pool, "pool/volume", "writeback"));
            Assert.assertEquals(1, files.size());
            Assert.assertFalse(Files.exists(files.get(0)));
        }
    }
}
