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

import java.nio.file.Files;
import java.io.File;
import java.util.List;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.cloud.agent.api.CleanupConvertedInstanceDisksCommand;
import com.cloud.agent.api.to.NfsTO;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.hypervisor.kvm.storage.KVMPhysicalDisk;
import com.cloud.hypervisor.kvm.storage.KVMStoragePool;
import com.cloud.hypervisor.kvm.storage.KVMStoragePoolManager;
import com.cloud.storage.Storage;
import com.cloud.utils.exception.CloudRuntimeException;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

public class LibvirtCleanupConvertedInstanceDisksCommandWrapperTest {
    private static final String PREFIX = "be749de2-963e-4d60-8e2c-8e4c424aaab8";
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private final LibvirtCleanupConvertedInstanceDisksCommandWrapper wrapper = new LibvirtCleanupConvertedInstanceDisksCommandWrapper();
    private final LibvirtComputingResource resource = mock(LibvirtComputingResource.class);
    private final KVMStoragePoolManager manager = mock(KVMStoragePoolManager.class);
    private final KVMStoragePool pool = mock(KVMStoragePool.class);
    private final NfsTO store = mock(NfsTO.class);

    @Before
    public void setUp() {
        when(resource.getStoragePoolMgr()).thenReturn(manager);
        when(store.getUrl()).thenReturn("nfs://fixture/conversion");
        when(manager.getStoragePoolByURI("nfs://fixture/conversion")).thenReturn(pool);
        when(pool.getLocalPath()).thenReturn(folder.getRoot().getAbsolutePath());
    }

    private KVMPhysicalDisk disk(String name) {
        KVMPhysicalDisk disk = mock(KVMPhysicalDisk.class);
        when(disk.getName()).thenReturn(name);
        return disk;
    }

    @Test
    public void deletesOnlyOwnedOutputAndRetryIsIdempotent() throws Exception {
        File xml = folder.newFile(PREFIX + ".xml");
        Files.writeString(xml.toPath(), "<domain><devices><disk type='file' device='disk'><source file='/parent/unrelated'/></disk></devices></domain>");
        doReturn(List.of(disk(PREFIX + "-sda"), disk("parent-image"), disk(PREFIX + "other-sda")),
                List.of(disk("parent-image"))).when(pool).listPhysicalDisks();
        when(pool.deletePhysicalDisk(PREFIX + "-sda", Storage.ImageFormat.QCOW2)).thenReturn(true);
        CleanupConvertedInstanceDisksCommand command = new CleanupConvertedInstanceDisksCommand(store, PREFIX);
        assertTrue(wrapper.execute(command, resource).getResult());
        assertFalse(xml.exists());
        assertTrue(wrapper.execute(command, resource).getResult());
        verify(pool, times(1)).deletePhysicalDisk(PREFIX + "-sda", Storage.ImageFormat.QCOW2);
        verify(pool, never()).deletePhysicalDisk(eq("parent-image"), any());
        verify(pool, never()).getPhysicalDisk(anyString());
    }

    @Test
    public void rejectsUnsafePrefixBeforeResolvingStorage() {
        for (String prefix : new String[]{null, "", "../", "*", PREFIX + "/.."}) {
            assertFalse(wrapper.execute(new CleanupConvertedInstanceDisksCommand(store, prefix), resource).getResult());
        }
        verifyNoInteractions(pool);
        verify(manager, never()).getStoragePoolByURI(anyString());
    }

    @Test
    public void failureIsReportedAndXmlRemainsForRetry() throws Exception {
        File xml = folder.newFile(PREFIX + ".xml");
        doReturn(List.of(disk(PREFIX + "-sda"))).when(pool).listPhysicalDisks();
        when(pool.deletePhysicalDisk(anyString(), any())).thenReturn(false);
        assertFalse(wrapper.execute(new CleanupConvertedInstanceDisksCommand(store, PREFIX), resource).getResult());
        assertTrue(xml.exists());
    }

    @Test
    public void importCleanupValidatesEntireDiskSetBeforeDeleting() {
        try {
            wrapper.cleanupDisksAndDomainFromTemporaryLocation(List.of(disk(PREFIX + "-sda"), disk("parent-image")), pool, PREFIX, false);
            fail("unowned image must be rejected");
        } catch (CloudRuntimeException expected) {
            verify(pool, never()).deletePhysicalDisk(anyString(), any());
        }
    }

    @Test
    public void unavailablePoolReturnsFailureAnswer() {
        when(manager.getStoragePoolByURI(anyString())).thenThrow(new CloudRuntimeException("unavailable"));
        assertFalse(wrapper.execute(new CleanupConvertedInstanceDisksCommand(store, PREFIX), resource).getResult());
    }
}
