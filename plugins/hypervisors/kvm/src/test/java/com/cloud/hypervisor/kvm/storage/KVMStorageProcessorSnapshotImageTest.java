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
package com.cloud.hypervisor.kvm.storage;

import java.io.File;
import java.io.RandomAccessFile;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.to.NfsTO;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.storage.Storage.StoragePoolType;
import org.apache.cloudstack.storage.command.CopyCommand;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;
import org.apache.cloudstack.storage.to.SnapshotObjectTO;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.apache.cloudstack.utils.qemu.QemuImg.PhysicalDiskFormat;

public class KVMStorageProcessorSnapshotImageTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private KVMStoragePoolManager pools;
    private KVMStorageProcessor processor;
    private KVMStoragePool secondary;
    private SnapshotObjectTO snapshot;
    private VolumeObjectTO source;
    private VolumeObjectTO target;
    private File backup;
    private KVMPhysicalDisk disk;
    @Before public void setup() throws Exception {
        pools = mock(KVMStoragePoolManager.class); processor = new KVMStorageProcessor(pools, mock(LibvirtComputingResource.class));
        secondary = mock(KVMStoragePool.class);
        when(pools.getStoragePoolByURI(anyString())).thenReturn(secondary);
        backup = folder.newFile();
        disk = new KVMPhysicalDisk(backup.getAbsolutePath(), "snapshot", secondary);
        when(secondary.getPhysicalDisk("snapshot")).thenReturn(disk);
        KVMStoragePool primary = mock(KVMStoragePool.class);
        when(pools.getStoragePool(StoragePoolType.SharedMountPoint, "primary")).thenReturn(primary);
        when(pools.copyPhysicalDisk(any(KVMPhysicalDisk.class), anyString(), any(KVMStoragePool.class), anyInt())).thenAnswer(i -> i.getArgument(0));
        source = new VolumeObjectTO(); source.setFormat(ImageFormat.QCOW2);
        snapshot = new SnapshotObjectTO(); snapshot.setPath("snapshots/2/1/snapshot"); snapshot.setVolume(source); snapshot.setSize(1073741824L);
        NfsTO nfs = mock(NfsTO.class); when(nfs.getUrl()).thenReturn("nfs://server/secondary"); snapshot.setDataStore(nfs);
        PrimaryDataStoreTO store = mock(PrimaryDataStoreTO.class); when(store.getPoolType()).thenReturn(StoragePoolType.SharedMountPoint); when(store.getUuid()).thenReturn("primary");
        target = new VolumeObjectTO(); target.setDataStore(store); target.setUuid("target");
    }
    private Answer copy() { return processor.createVolumeFromSnapshot(new CopyCommand(snapshot, target, 60, false)); }
    @Test public void rawBackupDoesNotInheritGfsQcowFormat() throws Exception {
        try (RandomAccessFile f = new RandomAccessFile(backup, "rw")) { f.setLength(1073741824L); }
        assertTrue(copy().getResult()); assertEquals(PhysicalDiskFormat.RAW, disk.getFormat()); assertEquals(1073741824L, disk.getVirtualSize());
    }
    @Test public void qcowBackupDoesNotInheritRbdRawFormat() throws Exception {
        source.setFormat(ImageFormat.RAW);
        try (RandomAccessFile f = new RandomAccessFile(backup, "rw")) { f.setLength(4096); f.writeInt(0x514649fb); f.writeInt(3); f.seek(20); f.writeInt(16); f.writeLong(1073741824L); }
        assertTrue(copy().getResult()); assertEquals(PhysicalDiskFormat.QCOW2, disk.getFormat()); assertEquals(1073741824L, disk.getVirtualSize()); assertEquals(4096L, disk.getSize());
    }
    @Test public void invalidBackupFailsBeforeStorageMutation() throws Exception {
        try (RandomAccessFile f = new RandomAccessFile(backup, "rw")) { f.writeBytes("bad backup"); }
        assertFalse(copy().getResult()); verify(pools, never()).connectPhysicalDisk(any(), anyString(), anyString(), any());
        verify(pools, never()).copyPhysicalDisk(any(), anyString(), any(), anyInt()); verify(secondary).delete();
    }
}
