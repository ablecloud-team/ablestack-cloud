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
package com.cloud.vm.snapshot;

import java.util.List;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.vm.UserVmVO;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.storage.Storage;
import com.cloud.storage.StoragePool;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.snapshot.dao.VMSnapshotDao;
import com.cloud.vm.snapshot.dao.VMSnapshotDetailsDao;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.InvalidParameterValueException;

public class VMSnapshotForceDeleteTest {
    private VMSnapshotManagerImpl manager;
    private Account caller;
    private VMSnapshotVO target;
    @Before public void setup() {
        manager = new VMSnapshotManagerImpl(); caller = mock(Account.class); target = mock(VMSnapshotVO.class);
        manager._accountMgr = mock(AccountManager.class);
        manager._vmSnapshotDao = mock(VMSnapshotDao.class);
        manager._vmSnapshotDetailsDao = mock(VMSnapshotDetailsDao.class);
        manager._volumeDao = mock(VolumeDao.class);
        manager._userVMDao = mock(UserVmDao.class);
        manager._storagePoolDao = mock(PrimaryDataStoreDao.class);
        when(caller.getId()).thenReturn(1L); when(manager._accountMgr.isRootAdmin(1L)).thenReturn(true);
        when(target.getId()).thenReturn(2L); when(target.getVmId()).thenReturn(3L); when(target.getUuid()).thenReturn("snapshot");
        when(target.getState()).thenReturn(VMSnapshot.State.Error); when(target.getType()).thenReturn(VMSnapshot.Type.DiskAndMemory);
        when(target.getCurrent()).thenReturn(null); when(manager._vmSnapshotDao.listByParent(2L)).thenReturn(List.of());
        UserVmVO vm = mock(UserVmVO.class); when(vm.getHypervisorType()).thenReturn(Hypervisor.HypervisorType.KVM);
        when(manager._userVMDao.findById(3L)).thenReturn(vm);
        VolumeVO volume = mock(VolumeVO.class); when(volume.getPoolId()).thenReturn(4L); when(volume.getFormat()).thenReturn(ImageFormat.QCOW2);
        when(manager._volumeDao.findByInstance(3L)).thenReturn(List.of(volume));
        StoragePool pool = mock(StoragePool.class); when(pool.getPoolType()).thenReturn(Storage.StoragePoolType.SharedMountPoint);
        // PrimaryDataStoreDao is typed to StoragePoolVO.
        org.apache.cloudstack.storage.datastore.db.StoragePoolVO stored = mock(org.apache.cloudstack.storage.datastore.db.StoragePoolVO.class);
        when(stored.getPoolType()).thenReturn(Storage.StoragePoolType.SharedMountPoint); when(stored.isManaged()).thenReturn(false);
        when(manager._storagePoolDao.findById(4L)).thenReturn(stored);
    }
    @Test public void creationErrorWithNullCurrentCanBeRecovered() { manager.validateForcedSnapshotDeletion(caller, target); }
    @Test(expected = InvalidParameterValueException.class) public void previouslyCompletedSnapshotErrorCannotUseCreationRecovery() {
        when(target.getCurrent()).thenReturn(false); manager.validateForcedSnapshotDeletion(caller, target);
    }
    @Test(expected = PermissionDeniedException.class) public void ordinaryAccountCannotForceDelete() {
        when(manager._accountMgr.isRootAdmin(1L)).thenReturn(false); manager.validateForcedSnapshotDeletion(caller, target);
    }
    @Test(expected = InvalidParameterValueException.class) public void readyCannotBeDeletedByForce() {
        when(target.getState()).thenReturn(VMSnapshot.State.Ready); manager.validateForcedSnapshotDeletion(caller, target);
    }
    @Test(expected = InvalidParameterValueException.class) public void currentErrorCannotBeRemoved() {
        when(target.getCurrent()).thenReturn(true); manager.validateForcedSnapshotDeletion(caller, target);
    }
    @Test(expected = InvalidParameterValueException.class) public void dependentChildrenBlockForce() {
        when(manager._vmSnapshotDao.listByParent(2L)).thenReturn(List.of(mock(VMSnapshotVO.class))); manager.validateForcedSnapshotDeletion(caller, target);
    }
    @Test(expected = InvalidParameterValueException.class) public void unrecognizedExpungingIsNotRecoveryRetry() {
        when(target.getState()).thenReturn(VMSnapshot.State.Expunging); manager.validateForcedSnapshotDeletion(caller, target);
    }
    @Test public void serializedVmWorkPreservesForceAndOldConstructorDefaultsFalse() {
        VmWorkDeleteVMSnapshot work = new VmWorkDeleteVMSnapshot(1, 1, 3, "handler", 2L, true);
        VmWorkDeleteVMSnapshot decoded = new com.google.gson.Gson().fromJson(new com.google.gson.Gson().toJson(work), VmWorkDeleteVMSnapshot.class);
        assertTrue(decoded.isForce()); assertFalse(new VmWorkDeleteVMSnapshot(1, 1, 3, "handler", 2L).isForce());
    }
}
