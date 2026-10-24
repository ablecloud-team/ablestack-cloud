// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with this
// work for additional information regarding copyright ownership. The ASF
// licenses this file to You under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0 .
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
// WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
// License for the specific language governing permissions and limitations.
package org.apache.cloudstack.storage.template;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;
import com.cloud.agent.api.storage.CreateEntityDownloadURLCommand;
import com.cloud.agent.api.storage.CreateEntityDownloadURLAnswer;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.storage.Storage.ImageFormat;
import org.apache.cloudstack.storage.to.SnapshotObjectTO;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SnapshotDownloadTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private Path root;
    private Path exports;
    private File source;
    private UploadManagerImpl manager;
    private CreateEntityDownloadURLCommand command;

    @Before public void setup() throws Exception {
        root = folder.newFolder("storage").toPath();
        exports = folder.newFolder("exports").toPath();
        Path store = Files.createDirectory(root.resolve("store"));
        source = store.resolve("misleading.raw").toFile();
        try (RandomAccessFile f = new RandomAccessFile(source, "rw")) {
            f.setLength(4096); f.writeInt(0x514649fb); f.writeInt(2);
            f.seek(20); f.writeInt(16); f.writeLong(1073741824L);
        }
        Files.setPosixFilePermissions(source.toPath(), PosixFilePermissions.fromString("rw-r--r--"));
        manager = new UploadManagerImpl() {
            @Override protected Path snapshotStorageRoot() { return root; }
            @Override protected Path snapshotExtractRoot() { return exports; }
            @Override protected boolean checkAndStartApache() { return true; }
        };
        SnapshotObjectTO snapshot = new SnapshotObjectTO(); snapshot.setSize(1073741824L); snapshot.setHypervisorType(HypervisorType.KVM);
        command = new CreateEntityDownloadURLCommand("store", "misleading.raw", "snapshot.qcow2", UUID.randomUUID().toString(), snapshot);
    }
    @Test public void probeReturnsActualMetadataWithoutExport() throws Exception {
        command.setInspectOnly(true);
        CreateEntityDownloadURLAnswer answer = manager.handleCreateEntityURLCommand(command);
        assertTrue(answer.getResult()); assertEquals(ImageFormat.QCOW2, answer.getFormat());
        assertEquals(Long.valueOf(1073741824L), answer.getVirtualSize()); assertEquals(Long.valueOf(4096), answer.getFileSize());
        try (java.util.stream.Stream<Path> files = Files.list(exports)) { assertEquals(0, files.count()); }
    }
    @Test public void linkPointsToUnchangedBackup() throws Exception {
        assertTrue(manager.handleCreateEntityURLCommand(command).getResult());
        Path link = exports.resolve(command.getFilepathInExtractURL()).resolve("snapshot.qcow2");
        assertTrue(Files.isSymbolicLink(link)); assertEquals(source.toPath(), Files.readSymbolicLink(link)); assertEquals(4096, source.length());
    }
    @Test public void wrongExtensionFailsWithoutLeavingDirectory() throws Exception {
        command.setFilenameInExtractURL("snapshot.raw"); assertFalse(manager.handleCreateEntityURLCommand(command).getResult());
        assertFalse(Files.exists(exports.resolve(command.getFilepathInExtractURL())));
    }
    @Test public void rawUsesIndependentLogicalSize() throws Exception {
        try (RandomAccessFile f = new RandomAccessFile(source, "rw")) { f.setLength(1073741824L); f.writeLong(0); }
        command.setFilenameInExtractURL("snapshot.raw");
        CreateEntityDownloadURLAnswer answer = manager.handleCreateEntityURLCommand(command);
        assertTrue(answer.getResult()); assertEquals(ImageFormat.RAW, answer.getFormat()); assertEquals(answer.getFileSize(), answer.getVirtualSize());
    }
    @Test public void missingBackupFails() throws Exception { Files.delete(source.toPath()); assertFalse(manager.handleCreateEntityURLCommand(command).getResult()); }
    @Test public void truncatedBackupFails() throws Exception {
        try (RandomAccessFile f = new RandomAccessFile(source, "rw")) { f.setLength(16); }
        assertFalse(manager.handleCreateEntityURLCommand(command).getResult());
    }
    @Test public void pathTraversalFails() { command.setInstallPath("../../outside"); assertFalse(manager.handleCreateEntityURLCommand(command).getResult()); }
    @Test public void symlinkOutsideStoreFails() throws Exception {
        Path outside = folder.newFile("outside").toPath(); Files.createSymbolicLink(root.resolve("store/escape"), outside);
        command.setInstallPath("escape"); assertFalse(manager.handleCreateEntityURLCommand(command).getResult());
    }
    @Test public void malformedExportTokenFails() { command.setFilepathInExtractURL("../outside"); assertFalse(manager.handleCreateEntityURLCommand(command).getResult()); }
    @Test public void unreadableByApacheRemovesAttemptLinkOnly() throws Exception {
        Files.setPosixFilePermissions(source.toPath(), PosixFilePermissions.fromString("rw-------"));
        assertFalse(manager.handleCreateEntityURLCommand(command).getResult()); assertTrue(source.isFile());
        assertFalse(Files.exists(exports.resolve(command.getFilepathInExtractURL())));
    }
    @Test public void existingExportIsNeverOverwrittenOrDeleted() throws Exception {
        Path dir = Files.createDirectory(exports.resolve(command.getFilepathInExtractURL())); Path keep = Files.writeString(dir.resolve("keep"), "keep");
        assertFalse(manager.handleCreateEntityURLCommand(command).getResult()); assertTrue(Files.exists(keep));
    }
    private com.cloud.agent.api.storage.DeleteEntityDownloadURLCommand deletion() {
        return new com.cloud.agent.api.storage.DeleteEntityDownloadURLCommand("misleading.raw", com.cloud.storage.Upload.Type.SNAPSHOT,
                "http://ssvm/userdata/" + command.getFilepathInExtractURL() + "/snapshot.qcow2", "store");
    }
    @Test public void revocationIsIdempotentAndPreservesOriginal() throws Exception {
        assertTrue(manager.handleCreateEntityURLCommand(command).getResult());
        assertTrue(manager.handleDeleteEntityDownloadURLCommand(deletion()).getResult());
        assertTrue(manager.handleDeleteEntityDownloadURLCommand(deletion()).getResult());
        assertTrue(source.isFile()); assertFalse(Files.exists(exports.resolve(command.getFilepathInExtractURL())));
    }
    @Test public void revocationRemovesOnlyConvertedTemporaryFile() throws Exception {
        Path temporary = Files.writeString(source.toPath().resolveSibling(source.getName() + "_temp"), "temporary");
        assertTrue(manager.handleDeleteEntityDownloadURLCommand(deletion()).getResult());
        assertFalse(Files.exists(temporary)); assertTrue(source.isFile());
    }
    @Test public void revocationRetainsUnrelatedDirectoryEntries() throws Exception {
        assertTrue(manager.handleCreateEntityURLCommand(command).getResult());
        Path keep = Files.writeString(exports.resolve(command.getFilepathInExtractURL()).resolve("keep"), "keep");
        assertTrue(manager.handleDeleteEntityDownloadURLCommand(deletion()).getResult()); assertTrue(Files.exists(keep));
    }
    @Test public void revocationRefusesUnexpectedRegularExportFile() throws Exception {
        Path dir = Files.createDirectory(exports.resolve(command.getFilepathInExtractURL())); Path keep = Files.writeString(dir.resolve("snapshot.qcow2"), "keep");
        assertFalse(manager.handleDeleteEntityDownloadURLCommand(deletion()).getResult()); assertTrue(Files.exists(keep));
    }

}
