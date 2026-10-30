// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with this
// work for additional information regarding copyright ownership. The ASF
// licenses this file to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0 .
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
// WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
// License for the specific language governing permissions and limitations.
package org.apache.cloudstack.storage.vmsnapshot;

import java.util.List;
import com.cloud.storage.Storage;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VirtualMachine;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class StoppedRbdSnapshotTest {
    @Test public void onlyStoppedReadyRawRbdDisksUseStorageOnlySnapshot() {
        StorageVMSnapshotStrategy strategy = new StorageVMSnapshotStrategy();
        VolumeDao volumes = mock(VolumeDao.class); PrimaryDataStoreDao pools = mock(PrimaryDataStoreDao.class);
        ReflectionTestUtils.setField(strategy,"volumeDao",volumes); ReflectionTestUtils.setField(strategy,"storagePool",pools);
        UserVmVO vm = mock(UserVmVO.class); when(vm.getId()).thenReturn(1L); when(vm.getState()).thenReturn(VirtualMachine.State.Stopped);
        VolumeVO volume = mock(VolumeVO.class); when(volume.getPoolId()).thenReturn(2L); when(volume.getState()).thenReturn(Volume.State.Ready); when(volume.getFormat()).thenReturn(Storage.ImageFormat.RAW);
        StoragePoolVO pool = mock(StoragePoolVO.class); when(pool.getPoolType()).thenReturn(Storage.StoragePoolType.RBD); when(pools.findById(2L)).thenReturn(pool);
        when(volumes.findByInstance(1L)).thenReturn(List.of(volume));
        assertTrue(strategy.isStoppedRbdVm(vm));
        when(pool.getPoolType()).thenReturn(Storage.StoragePoolType.SharedMountPoint); assertFalse(strategy.isStoppedRbdVm(vm));
        when(pool.getPoolType()).thenReturn(Storage.StoragePoolType.RBD); when(volume.getState()).thenReturn(Volume.State.Creating); assertFalse(strategy.isStoppedRbdVm(vm));
        when(volume.getState()).thenReturn(Volume.State.Ready); when(vm.getState()).thenReturn(VirtualMachine.State.Running); assertFalse(strategy.isStoppedRbdVm(vm));
        when(vm.getState()).thenReturn(VirtualMachine.State.Stopped); when(volumes.findByInstance(1L)).thenReturn(List.of()); assertFalse(strategy.isStoppedRbdVm(vm));
    }
}
