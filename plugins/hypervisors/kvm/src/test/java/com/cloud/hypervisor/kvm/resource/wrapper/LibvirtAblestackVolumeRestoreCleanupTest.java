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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

import com.cloud.utils.exception.CloudRuntimeException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.never;

public class LibvirtAblestackVolumeRestoreCleanupTest {
    private static final String PROVIDER = "ablestack-commvault";
    private static final String JOB_ID = "restore-before-admission";
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();
    private Path providerRoot;
    private Path destination;

    @Before
    public void prepare() throws IOException {
        providerRoot = folder.getRoot().toPath().resolve(PROVIDER);
        destination = providerRoot.resolve("restore").resolve(JOB_ID);
        Files.createDirectories(destination);
    }

    private void cleanup() throws IOException {
        LibvirtAblestackVolumeRestoreHelper.cleanupStagingDirectory(PROVIDER, JOB_ID, destination, providerRoot);
    }

    @Test
    public void removesOnlyTheEmptyDirectoryLeftByOldMountPreparation() throws Exception {
        Path other = destination.resolveSibling("another-restore");
        Files.createDirectories(other);
        Files.writeString(other.resolve("payload"), "retained");
        try (MockedStatic<LibvirtAblestackStagingCleanup> validation = Mockito.mockStatic(LibvirtAblestackStagingCleanup.class)) {
            cleanup();
            assertFalse(Files.exists(destination));
            assertTrue(Files.isDirectory(destination.getParent()));
            assertEquals("retained", Files.readString(other.resolve("payload")));
            validation.verify(() -> LibvirtAblestackStagingCleanup.validate(PROVIDER, destination, providerRoot));
            validation.verify(() -> LibvirtAblestackStagingCleanup.delete(PROVIDER, destination, providerRoot), never());
            cleanup();
        }
    }

    @Test
    public void neverRecursivelyDeletesAnUnownedPayload() throws Exception {
        Path payload = destination.resolve("payload");
        Files.createDirectories(payload);
        Files.writeString(payload.resolve("volume.qcow2"), "unconfirmed data");
        try (MockedStatic<LibvirtAblestackStagingCleanup> validation = Mockito.mockStatic(LibvirtAblestackStagingCleanup.class)) {
            assertThrows(CloudRuntimeException.class, this::cleanup);
            assertEquals("unconfirmed data", Files.readString(payload.resolve("volume.qcow2")));
            validation.verify(() -> LibvirtAblestackStagingCleanup.delete(PROVIDER, destination, providerRoot), never());
        }
    }

    @Test
    public void retainsFilesThatArriveAfterMountValidation() throws Exception {
        Path payload = destination.resolve("late-payload");
        try (MockedStatic<LibvirtAblestackStagingCleanup> validation = Mockito.mockStatic(LibvirtAblestackStagingCleanup.class)) {
            validation.when(() -> LibvirtAblestackStagingCleanup.validate(PROVIDER, destination, providerRoot)).thenAnswer(invocation -> {
                Files.writeString(payload, "concurrent data");
                return null;
            });
            assertThrows(CloudRuntimeException.class, this::cleanup);
            assertEquals("concurrent data", Files.readString(payload));
        }
    }

    @Test
    public void retainsDirectoriesOwnedByAnotherJob() throws Exception {
        Path owner = destination.resolve(".restore-owner");
        Files.writeString(owner, "another-restore");
        try (MockedStatic<LibvirtAblestackStagingCleanup> validation = Mockito.mockStatic(LibvirtAblestackStagingCleanup.class)) {
            assertThrows(CloudRuntimeException.class, this::cleanup);
            assertEquals("another-restore", Files.readString(owner));
            validation.verify(() -> LibvirtAblestackStagingCleanup.delete(PROVIDER, destination, providerRoot), never());
        }
    }

    @Test
    public void ownedPayloadUsesTheExistingValidatedCleanup() throws Exception {
        Files.writeString(destination.resolve(".restore-owner"), JOB_ID);
        Files.writeString(destination.resolve("payload"), "owned data");
        try (MockedStatic<LibvirtAblestackStagingCleanup> validation = Mockito.mockStatic(LibvirtAblestackStagingCleanup.class)) {
            validation.when(() -> LibvirtAblestackStagingCleanup.delete(PROVIDER, destination, providerRoot)).thenCallRealMethod();
            cleanup();
            assertFalse(Files.exists(destination));
        }
    }

    @Test
    public void mountValidationStillBlocksAnEmptyDirectory() throws Exception {
        try (MockedStatic<LibvirtAblestackStagingCleanup> validation = Mockito.mockStatic(LibvirtAblestackStagingCleanup.class)) {
            validation.when(() -> LibvirtAblestackStagingCleanup.validate(PROVIDER, destination, providerRoot))
                    .thenThrow(new IOException("Destination is a mount point"));
            assertEquals("Destination is a mount point", assertThrows(IOException.class, this::cleanup).getMessage());
            assertTrue(Files.isDirectory(destination));
        }
    }

    @Test
    public void symbolicLinkCannotBeTreatedAsAnEmptyDirectory() throws Exception {
        Path realDirectory = folder.newFolder("unrelated").toPath();
        Files.delete(destination);
        Files.createSymbolicLink(destination, realDirectory);
        assertThrows(IOException.class, this::cleanup);
        assertTrue(Files.isSymbolicLink(destination));
        assertTrue(Files.isDirectory(realDirectory));
    }

    @Test
    public void ownerMarkerCannotBeASymbolicLink() throws Exception {
        Path marker = folder.getRoot().toPath().resolve("unrelated-owner");
        Files.writeString(marker, JOB_ID);
        Files.createSymbolicLink(destination.resolve(".restore-owner"), marker);
        try (MockedStatic<LibvirtAblestackStagingCleanup> validation = Mockito.mockStatic(LibvirtAblestackStagingCleanup.class)) {
            assertThrows(CloudRuntimeException.class, this::cleanup);
            assertTrue(Files.exists(destination.resolve(".restore-owner"), LinkOption.NOFOLLOW_LINKS));
            assertEquals(JOB_ID, Files.readString(marker));
        }
    }

    @Test
    public void cleanupRejectsAnotherJobPath() throws Exception {
        assertThrows(CloudRuntimeException.class, () -> LibvirtAblestackVolumeRestoreHelper.cleanupStagingDirectory(
                PROVIDER, "another-restore", destination, providerRoot));
        assertTrue(Files.isDirectory(destination));
    }
}
