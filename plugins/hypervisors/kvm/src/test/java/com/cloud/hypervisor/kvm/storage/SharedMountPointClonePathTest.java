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
package com.cloud.hypervisor.kvm.storage;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.libvirt.StoragePool;

import com.cloud.storage.Storage.StoragePoolType;
import com.cloud.utils.exception.CloudRuntimeException;

public class SharedMountPointClonePathTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void rejectsParentDirectoryWithoutFallingBackToBasename() throws IOException {
        File root = temporaryFolder.newFolder("pool");
        StorageAdaptor adaptor = mock(StorageAdaptor.class);
        LibvirtStoragePool pool = pool(root, adaptor);

        CloudRuntimeException failure = assertThrows(CloudRuntimeException.class, () -> pool.getPhysicalDisk("../outside"));

        assertTrue(failure.getMessage().contains("outside the storage pool"));
        verifyNoInteractions(adaptor);
    }

    @Test
    public void rejectsOverlaySymlinkOutsideStoragePool() throws IOException {
        File root = temporaryFolder.newFolder("pool");
        File outside = temporaryFolder.newFile("outside");
        File overlays = new File(root, "clone/overlay");
        Files.createDirectories(overlays.toPath());
        Files.createSymbolicLink(new File(overlays, "disk").toPath(), outside.toPath());
        StorageAdaptor adaptor = mock(StorageAdaptor.class);
        LibvirtStoragePool pool = pool(root, adaptor);

        CloudRuntimeException failure = assertThrows(CloudRuntimeException.class, () -> pool.getPhysicalDisk("clone/overlay/disk"));

        assertTrue(failure.getMessage().contains("outside the storage pool"));
        verifyNoInteractions(adaptor);
    }

    private LibvirtStoragePool pool(File root, StorageAdaptor adaptor) {
        LibvirtStoragePool pool = new LibvirtStoragePool("pool", "pool", StoragePoolType.SharedMountPoint, adaptor, mock(StoragePool.class));
        pool.setLocalPath(root.getAbsolutePath());
        return pool;
    }
}
