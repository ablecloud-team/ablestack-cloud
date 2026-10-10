/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.cloud.hypervisor.kvm.storage;

import java.util.Map;
import com.cloud.storage.Storage.StoragePoolType;
import com.cloud.storage.Volume;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.apache.cloudstack.utils.qemu.QemuImg.PhysicalDiskFormat;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

public class KVMStorageProcessorRbdRetryTest {
    private static final String UUID = "caf4e79d-b529-44ea-9c4f-1a3f71135a6f";
    private static final long SIZE = 10L * 1024 * 1024 * 1024;
    private KVMStoragePool pool;
    private VolumeObjectTO request;
    private KVMStorageProcessor processor;
    private KVMPhysicalDisk existing;
    private CloudRuntimeException failure;

    @Before
    public void setup() {
        pool = Mockito.mock(KVMStoragePool.class);
        request = Mockito.mock(VolumeObjectTO.class);
        processor = new KVMStorageProcessor(null, null);
        Mockito.when(pool.getType()).thenReturn(StoragePoolType.RBD);
        Mockito.when(pool.getUuid()).thenReturn("expected-pool");
        Mockito.when(pool.getSourceDir()).thenReturn("rbd");
        Mockito.when(request.getUuid()).thenReturn(UUID);
        Mockito.when(request.getUsableSize()).thenReturn(null);
        Mockito.when(request.getVolumeType()).thenReturn(Volume.Type.DATADISK);
        Mockito.when(request.getVolumeId()).thenReturn(57L);
        Mockito.when(request.getDeviceId()).thenReturn(1L);
        Mockito.when(request.getVmName()).thenReturn("i-2-59-VM");
        existing = new KVMPhysicalDisk("rbd/" + UUID, UUID, pool);
        existing.setFormat(PhysicalDiskFormat.RAW);
        existing.setVirtualSize(SIZE);
        Mockito.when(pool.getPhysicalDisk(UUID)).thenReturn(existing);
        failure = new CloudRuntimeException("storage volume exists already");
        Mockito.when(pool.createPhysicalDisk(UUID, PhysicalDiskFormat.RAW, null, SIZE, null, null)).thenThrow(failure);
    }

    @Test
    public void recoversExactDataImageWithoutAnotherCreateOrDelete() {
        Assert.assertSame(existing, processor.createBlankVolume(request, pool, PhysicalDiskFormat.RAW, SIZE));
        Mockito.verify(pool, Mockito.times(1)).createPhysicalDisk(UUID, PhysicalDiskFormat.RAW, null, SIZE, null, null);
        Mockito.verify(pool, Mockito.never()).deletePhysicalDisk(Mockito.anyString(), Mockito.any());
    }

    @Test
    public void missingImageRetainsTheOriginalFailure() {
        Mockito.when(pool.getPhysicalDisk(UUID)).thenThrow(new CloudRuntimeException("not found"));
        Assert.assertSame(failure, Assert.assertThrows(CloudRuntimeException.class,
                () -> processor.createBlankVolume(request, pool, PhysicalDiskFormat.RAW, SIZE)));
    }

    @Test
    public void wrongCapacityIsNeverAdoptedOrResized() {
        existing.setVirtualSize(SIZE / 2);
        Assert.assertThrows(CloudRuntimeException.class, () -> processor.createBlankVolume(request, pool, PhysicalDiskFormat.RAW, SIZE));
    }

    @Test
    public void wrongNameAndPoolAreNeverAdopted() {
        Mockito.when(pool.getPhysicalDisk(UUID)).thenReturn(new KVMPhysicalDisk("rbd/other", "other", pool));
        Assert.assertThrows(CloudRuntimeException.class, () -> processor.createBlankVolume(request, pool, PhysicalDiskFormat.RAW, SIZE));
        KVMStoragePool other = Mockito.mock(KVMStoragePool.class);
        Mockito.when(other.getUuid()).thenReturn("other-pool");
        KVMPhysicalDisk disk = new KVMPhysicalDisk("rbd/" + UUID, UUID, other);
        disk.setFormat(PhysicalDiskFormat.RAW); disk.setVirtualSize(SIZE);
        Mockito.when(pool.getPhysicalDisk(UUID)).thenReturn(disk);
        Assert.assertThrows(CloudRuntimeException.class, () -> processor.createBlankVolume(request, pool, PhysicalDiskFormat.RAW, SIZE));
    }

    @Test
    public void rootImagesAreNeverRecoveredByTheBlankDataPath() {
        Mockito.when(request.getVolumeType()).thenReturn(Volume.Type.ROOT);
        Assert.assertSame(failure, Assert.assertThrows(CloudRuntimeException.class,
                () -> processor.createBlankVolume(request, pool, PhysicalDiskFormat.RAW, SIZE)));
        Mockito.verify(pool, Mockito.never()).getPhysicalDisk(Mockito.anyString());
    }

    @Test
    public void encryptedAndDetachedRequestsAreNeverRecovered() {
        Mockito.when(request.requiresEncryption()).thenReturn(true);
        Assert.assertThrows(CloudRuntimeException.class, () -> processor.createBlankVolume(request, pool, PhysicalDiskFormat.RAW, SIZE));
        Mockito.when(request.requiresEncryption()).thenReturn(false);
        Mockito.when(request.getDeviceId()).thenReturn(null);
        Assert.assertThrows(CloudRuntimeException.class, () -> processor.createBlankVolume(request, pool, PhysicalDiskFormat.RAW, SIZE));
        Mockito.verify(pool, Mockito.never()).getPhysicalDisk(Mockito.anyString());
    }

    @Test
    public void qemuManagedRbdDataPoolIsNotRecovered() {
        Mockito.when(pool.getDetails()).thenReturn(Map.of(KVMPhysicalDisk.RBD_DEFAULT_DATA_POOL, "data-pool"));
        Assert.assertThrows(CloudRuntimeException.class, () -> processor.createBlankVolume(request, pool, PhysicalDiskFormat.RAW, SIZE));
        Mockito.verify(pool, Mockito.never()).getPhysicalDisk(Mockito.anyString());
    }

    @Test
    public void otherStorageAndMismatchingPhysicalFormatsAreNotRecovered() {
        Mockito.when(pool.getType()).thenReturn(StoragePoolType.SharedMountPoint);
        Assert.assertThrows(CloudRuntimeException.class, () -> processor.createBlankVolume(request, pool, PhysicalDiskFormat.RAW, SIZE));
        Mockito.verify(pool, Mockito.never()).getPhysicalDisk(Mockito.anyString());
        Mockito.when(pool.getType()).thenReturn(StoragePoolType.RBD);
        existing.setFormat(PhysicalDiskFormat.QCOW2);
        Assert.assertThrows(CloudRuntimeException.class, () -> processor.createBlankVolume(request, pool, PhysicalDiskFormat.RAW, SIZE));
    }
    @Test
    public void successfulNewCreateDoesNotInspectOrReuseAnotherDisk() {
        Mockito.doReturn(existing).when(pool).createPhysicalDisk(UUID, PhysicalDiskFormat.RAW, null, SIZE, null, null);
        Assert.assertSame(existing, processor.createBlankVolume(request, pool, PhysicalDiskFormat.RAW, SIZE));
        Mockito.verify(pool, Mockito.never()).getPhysicalDisk(Mockito.anyString());
    }

    @Test
    public void wrongPhysicalPathIsNeverRecovered() {
        KVMPhysicalDisk disk = new KVMPhysicalDisk("rbd/other-image", UUID, pool);
        disk.setFormat(PhysicalDiskFormat.RAW); disk.setVirtualSize(SIZE);
        Mockito.when(pool.getPhysicalDisk(UUID)).thenReturn(disk);
        Assert.assertThrows(CloudRuntimeException.class, () -> processor.createBlankVolume(request, pool, PhysicalDiskFormat.RAW, SIZE));
    }
}
