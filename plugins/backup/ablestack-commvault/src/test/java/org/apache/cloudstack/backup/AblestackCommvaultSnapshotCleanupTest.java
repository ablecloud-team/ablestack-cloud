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

package org.apache.cloudstack.backup;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;

import com.cloud.storage.Snapshot;
import com.cloud.storage.SnapshotVO;
import com.cloud.storage.Storage;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.SnapshotDao;
import com.cloud.storage.dao.SnapshotDetailsDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.snapshot.dao.VMSnapshotDao;
import com.google.gson.Gson;

public class AblestackCommvaultSnapshotCleanupTest {
    private final AblestackCommvaultBackupProvider provider = new AblestackCommvaultBackupProvider();
    private final SnapshotDao snapshots = mock(SnapshotDao.class);
    private final SnapshotDetailsDao details = mock(SnapshotDetailsDao.class);
    private final VolumeDao volumes = mock(VolumeDao.class);
    private final PrimaryDataStoreDao pools = mock(PrimaryDataStoreDao.class);
    private final VirtualMachine vm = mock(VirtualMachine.class);
    private final VolumeVO volume = mock(VolumeVO.class);
    private final SnapshotVO snapshot = mock(SnapshotVO.class);
    private final StoragePoolVO pool = mock(StoragePoolVO.class);

    @Before
    public void setup() {
        ReflectionTestUtils.setField(provider, "snapshotDao", snapshots);
        ReflectionTestUtils.setField(provider, "snapshotDetailsDao", details);
        ReflectionTestUtils.setField(provider, "volumeDao", volumes);
        ReflectionTestUtils.setField(provider, "primaryDataStoreDao", pools);
        ReflectionTestUtils.setField(provider, "vmSnapshotDao", mock(VMSnapshotDao.class));
        when(vm.getId()).thenReturn(10L);
        when(volume.getId()).thenReturn(9L);
        when(volume.getUuid()).thenReturn("volume");
        when(volume.getPoolId()).thenReturn(20L);
        when(volumes.findByInstance(10L)).thenReturn(List.of(volume));
        when(pools.findById(20L)).thenReturn(pool);
        when(pool.getPoolType()).thenReturn(Storage.StoragePoolType.RBD);
        when(snapshot.getId()).thenReturn(7L);
        when(snapshot.getUuid()).thenReturn("snapshot");
        when(snapshot.getVolumeId()).thenReturn(9L);
        when(snapshot.getState()).thenReturn(Snapshot.State.Destroyed);
        when(snapshots.listByVolumeIdIncludingRemoved(9L)).thenReturn(List.of(snapshot));
    }

    private void validate() {
        ReflectionTestUtils.invokeMethod(provider, "validateCommvaultRestoreSnapshotCompatibility", vm);
    }

    private void pending(String role) {
        String receipt = new Gson().toJson(Map.of("referenceid", 13L, "snapshotuuid", "snapshot", "volumeid", 9L,
                "storeid", 20L, "role", role));
        when(details.listDetailsKeyPairs(7L)).thenReturn(Map.of("snapshot.force-delete.pending.13", receipt));
    }

    @Test
    public void destroyedPrimarySnapshotBlocksUntilCleanupCompletes() {
        pending("Primary");
        assertTrue(assertThrows(CloudRuntimeException.class, this::validate).getMessage().startsWith("SNAPSHOT_CLEANUP_PENDING:"));
        when(details.listDetailsKeyPairs(7L)).thenReturn(Map.of());
        validate();
    }

    @Test
    public void secondaryOnlyCleanupAllowsRestoreOfTheRbdSource() {
        pending("Image");
        validate();
        verify(snapshots).listByVolumeIdIncludingRemoved(9L);
    }

    @Test
    public void activeNativeSnapshotStillBlocksRestore() {
        when(snapshot.getState()).thenReturn(Snapshot.State.BackedUp);
        assertTrue(assertThrows(CloudRuntimeException.class, this::validate).getMessage().contains("RBD volume snapshots exist"));
        verifyNoInteractions(details);
    }

    @Test
    public void gfsDestinationKeepsItsExistingRestorePath() {
        when(pool.getPoolType()).thenReturn(Storage.StoragePoolType.SharedMountPoint);
        validate();
        verifyNoInteractions(snapshots, details);
    }

    @Test
    public void newUnpersistedRbdDestinationDoesNotInspectTheOriginalVolume() {
        when(volume.getId()).thenReturn(0L);
        validate();
        verifyNoInteractions(snapshots, details);
    }
}
