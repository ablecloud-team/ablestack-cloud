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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;

import org.junit.Test;
import org.mockito.MockedStatic;

import com.cloud.agent.api.GetVolumeStatsAnswer;
import com.cloud.agent.api.GetVolumeStatsCommand;
import com.cloud.agent.api.VolumeStatsEntry;
import com.cloud.hypervisor.kvm.resource.KvmVolumeStats;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.hypervisor.kvm.storage.KVMPhysicalDisk;
import com.cloud.hypervisor.kvm.storage.KVMStoragePool;
import com.cloud.hypervisor.kvm.storage.KVMStoragePoolManager;
import com.cloud.storage.Storage.StoragePoolType;

public class LibvirtGetVolumeStatsCommandWrapperTest {
    @Test
    public void sharedMountStatsNeverFetchTheLibvirtPoolAndKeepPartialResults() {
        LibvirtComputingResource resource = mock(LibvirtComputingResource.class);
        KVMStoragePoolManager manager = mock(KVMStoragePoolManager.class);
        when(resource.getStoragePoolMgr()).thenReturn(manager);
        when(manager.getRegisteredStoragePoolPath(StoragePoolType.SharedMountPoint, "pool")).thenReturn("/gfs");
        GetVolumeStatsCommand command = new GetVolumeStatsCommand(StoragePoolType.SharedMountPoint, "pool", List.of("busy", "ready"));
        HashMap<String, VolumeStatsEntry> samples = new HashMap<>();
        samples.put("ready", new VolumeStatsEntry("ready", 4096, 8192));
        try (MockedStatic<KvmVolumeStats> collector = mockStatic(KvmVolumeStats.class)) {
            collector.when(() -> KvmVolumeStats.collect("/gfs", command.getVolumeUuids())).thenReturn(samples);
            GetVolumeStatsAnswer answer = (GetVolumeStatsAnswer) new LibvirtGetVolumeStatsCommandWrapper().execute(command, resource);
            assertSame(samples, answer.getVolumeStats());
            verify(manager, never()).getStoragePool(StoragePoolType.SharedMountPoint, "pool");
        }
    }

    @Test
    public void unregisteredSharedMountPoolDefersWithoutDiscoveringOrCreatingIt() {
        LibvirtComputingResource resource = mock(LibvirtComputingResource.class);
        KVMStoragePoolManager manager = mock(KVMStoragePoolManager.class);
        when(resource.getStoragePoolMgr()).thenReturn(manager);
        GetVolumeStatsCommand command = new GetVolumeStatsCommand(StoragePoolType.SharedMountPoint, "missing", List.of("volume"));
        GetVolumeStatsAnswer answer = (GetVolumeStatsAnswer) new LibvirtGetVolumeStatsCommandWrapper().execute(command, resource);
        assertNull(answer.getVolumeStats());
        verify(manager, never()).getStoragePool(StoragePoolType.SharedMountPoint, "missing");
    }

    @Test
    public void otherPoolTypesKeepTheirExistingStatisticsPath() throws Exception {
        LibvirtComputingResource resource = mock(LibvirtComputingResource.class);
        KVMStoragePoolManager manager = mock(KVMStoragePoolManager.class);
        KVMStoragePool pool = mock(KVMStoragePool.class);
        KVMPhysicalDisk disk = mock(KVMPhysicalDisk.class);
        when(resource.getStoragePoolMgr()).thenReturn(manager);
        when(manager.getStoragePool(StoragePoolType.RBD, "pool")).thenReturn(pool);
        when(pool.getPhysicalDisk("volume")).thenReturn(disk);
        when(disk.getSize()).thenReturn(4096L);
        when(disk.getVirtualSize()).thenReturn(8192L);
        GetVolumeStatsCommand command = new GetVolumeStatsCommand(StoragePoolType.RBD, "pool", List.of("volume"));
        GetVolumeStatsAnswer answer = (GetVolumeStatsAnswer) new LibvirtGetVolumeStatsCommandWrapper().execute(command, resource);
        assertEquals(4096, answer.getVolumeStats().get("volume").getPhysicalSize());
        verify(manager, never()).getRegisteredStoragePoolPath(StoragePoolType.RBD, "pool");
    }
}
