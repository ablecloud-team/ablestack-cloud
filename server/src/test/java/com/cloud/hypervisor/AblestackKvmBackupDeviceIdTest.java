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
package com.cloud.hypervisor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.cloud.event.UsageEventUtils;
import com.cloud.hypervisor.dao.HypervisorCapabilitiesDao;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.dao.VMInstanceDao;
import org.apache.cloudstack.backup.Backup;
import org.apache.cloudstack.backup.BackupProvider;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** Exercise the actual post-restore import, including a volume that is already attached. */
public class AblestackKvmBackupDeviceIdTest {
    private final KVMGuru guru = new KVMGuru();
    private final VMInstanceDao instances = mock(VMInstanceDao.class);
    private final VolumeDao volumes = mock(VolumeDao.class);
    private final HypervisorCapabilitiesDao capabilities = mock(HypervisorCapabilitiesDao.class);
    private final VMInstanceVO vm = mock(VMInstanceVO.class);
    private final VolumeVO root = mock(VolumeVO.class);
    private final VolumeVO data = mock(VolumeVO.class);
    private final Backup backup = mock(Backup.class);
    private final BackupProvider provider = mock(BackupProvider.class);
    private final AtomicReference<Long> device = new AtomicReference<>(1L);
    private MockedStatic<UsageEventUtils> usage;

    @Before public void setup() {
        usage = mockStatic(UsageEventUtils.class);
        ReflectionTestUtils.setField(guru, "_instanceDao", instances);
        ReflectionTestUtils.setField(guru, "_volumeDao", volumes);
        ReflectionTestUtils.setField(guru, "_hypervisorCapabilitiesDao", capabilities);
        when(instances.findVMByInstanceNameIncludingRemoved("i-2-318-VM")).thenReturn(vm);
        when(vm.getId()).thenReturn(318L);
        when(vm.getBackupVolumeList()).thenReturn(List.of(
                new Backup.VolumeInfo("root", "root.qcow2", Volume.Type.ROOT, 100L << 30, 0L, null, null, null),
                new Backup.VolumeInfo("data", "data.qcow2", Volume.Type.DATADISK, 100L << 30, 1L, null, null, null)));
        when(root.getId()).thenReturn(11L);
        when(root.getUuid()).thenReturn("root");
        when(root.getInstanceId()).thenReturn(318L);
        when(root.getDeviceId()).thenReturn(0L);
        when(data.getId()).thenReturn(12L);
        when(data.getUuid()).thenReturn("data");
        when(data.getInstanceId()).thenReturn(318L);
        when(data.getDeviceId()).thenAnswer(call -> device.get());
        when(volumes.findByUuidIncludingRemoved("root")).thenReturn(root);
        when(volumes.findByUuidIncludingRemoved("data")).thenReturn(data);
        when(volumes.findByInstance(318L)).thenAnswer(call -> new ArrayList<>(List.of(root, data)));
        when(capabilities.getMaxDataVolumesLimit(Hypervisor.HypervisorType.KVM, "default")).thenReturn(16);
        doAnswer(call -> {
            if (call.<Long>getArgument(0) == 12L) { device.set(call.getArgument(2)); }
            return null;
        }).when(volumes).attachVolume(anyLong(), anyLong(), anyLong());
        when(provider.getName()).thenReturn("ablestack-netbackup");
    }

    @After public void teardown() { usage.close(); }

    private void finishRestore() {
        assertSame(vm, guru.importVirtualMachineFromBackup(1L, 1L, 1L, 1L, "i-2-318-VM", backup, provider));
    }

    @Test public void repeatedVolumeStagingRestoresKeepTheOriginalDataSlotForAllThreeProviders() {
        for (String name : List.of("ablestack-netbackup", "ablestack-veeam", "ablestack-commvault")) {
            when(provider.getName()).thenReturn(name);
            device.set(1L);
            finishRestore();
            assertEquals(name, Long.valueOf(1L), device.get());
            finishRestore();
            assertEquals(name, Long.valueOf(1L), device.get());
        }
    }

    @Test public void attachedDataDisksKeepTheirConfiguredNonconsecutiveSlot() {
        device.set(4L);
        finishRestore();
        assertEquals(Long.valueOf(4L), device.get());
    }

    @Test public void nativeCloudStackProvidersKeepTheirExistingImportBehavior() {
        for (String name : List.of("netbackup", "veeam", "commvault", "networker")) {
            when(provider.getName()).thenReturn(name);
            device.set(1L);
            finishRestore();
            assertEquals(name, Long.valueOf(2L), device.get());
        }
    }

    @Test public void legacyAblestackNasRestoresAlsoKeepTheirExistingAttachment() {
        when(provider.getName()).thenReturn("ablestack-nas");
        finishRestore();
        assertEquals(Long.valueOf(1L), device.get());
    }

    @Test public void detachedVolumesStillReceiveAnAvailableSlot() {
        when(data.getInstanceId()).thenReturn(null);
        device.set(null);
        when(volumes.findByInstance(318L)).thenReturn(List.of(root));
        finishRestore();
        assertEquals(Long.valueOf(1L), device.get());
    }
}
