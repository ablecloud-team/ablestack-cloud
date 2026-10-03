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
package org.apache.cloudstack.storage.vmsnapshot;

import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import org.springframework.test.util.ReflectionTestUtils;
import org.apache.cloudstack.storage.datastore.db.SnapshotDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.SnapshotDataStoreVO;
import com.cloud.storage.DataStoreRole;
import com.cloud.storage.Snapshot;
import com.cloud.storage.SnapshotVO;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.SnapshotDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.snapshot.VMSnapshotDetailsVO;
import com.cloud.vm.snapshot.VMSnapshotVO;
import com.cloud.vm.snapshot.dao.VMSnapshotDetailsDao;

public class KvmFileSnapshotRecoveryTest {
    private static final String ARTIFACT = "00000000-0000-4000-8000-000000000003";
    private KvmFileBasedStorageVmSnapshotStrategy strategy;
    private VMSnapshotVO target;
    private SnapshotDataStoreVO ref;
    private SnapshotVO volumeSnapshot;

    @Before public void setup() {
        strategy = new KvmFileBasedStorageVmSnapshotStrategy(); target = mock(VMSnapshotVO.class);
        when(target.getId()).thenReturn(1L);
        strategy.vmSnapshotHelper = mock(VMSnapshotHelper.class);
        strategy.vmSnapshotDetailsDao = mock(VMSnapshotDetailsDao.class);
        strategy.snapshotDataStoreDao = mock(SnapshotDataStoreDao.class);
        strategy.volumeDao = mock(VolumeDao.class);
        SnapshotDao snapshots = mock(SnapshotDao.class); ReflectionTestUtils.setField(strategy, "snapshotDao", snapshots);
        ref = mock(SnapshotDataStoreVO.class);
        when(ref.getId()).thenReturn(10L); when(ref.getSnapshotId()).thenReturn(2L);
        when(ref.getVolumeId()).thenReturn(3L); when(ref.getDataStoreId()).thenReturn(4L); when(ref.getInstallPath()).thenReturn(ARTIFACT);
        volumeSnapshot = mock(SnapshotVO.class); when(volumeSnapshot.getState()).thenReturn(Snapshot.State.Error);
        when(snapshots.findById(2L)).thenReturn(volumeSnapshot);
        when(strategy.vmSnapshotHelper.getVolumeSnapshotsAssociatedWithKvmDiskOnlyVmSnapshot(1L)).thenReturn(List.of());
        when(strategy.vmSnapshotDetailsDao.listDetails(1L)).thenReturn(List.of(new VMSnapshotDetailsVO(1L, "snapshot.creation.artifact.3", "2", false)));
        when(strategy.snapshotDataStoreDao.findOneBySnapshotAndDatastoreRole(2L, DataStoreRole.Primary)).thenReturn(ref);
        SearchBuilder<SnapshotDataStoreVO> builder = mock(SearchBuilder.class);
        when(builder.entity()).thenReturn(mock(SnapshotDataStoreVO.class)); when(builder.create()).thenReturn(mock(SearchCriteria.class));
        when(strategy.snapshotDataStoreDao.createSearchBuilder()).thenReturn(builder);
        when(strategy.snapshotDataStoreDao.search(any(SearchCriteria.class), isNull())).thenReturn(List.of());
        when(strategy.snapshotDataStoreDao.listByStoreId(4L, DataStoreRole.Primary)).thenReturn(List.of(ref));
        when(strategy.volumeDao.findNonDestroyedVolumesByPoolId(4L)).thenReturn(List.of());
    }
    @Test public void failedCreationUsesHiddenOriginalReferencesBeforeSuccessAssociationExists() {
        assertEquals(Map.of(3L, ARTIFACT), strategy.recoveryArtifacts(target));
    }
    @Test(expected = CloudRuntimeException.class) public void missingOriginalReferenceIsNotAssumedAbsent() {
        when(strategy.snapshotDataStoreDao.findOneBySnapshotAndDatastoreRole(2L, DataStoreRole.Primary)).thenReturn(null);
        strategy.recoveryArtifacts(target);
    }
    @Test(expected = CloudRuntimeException.class) public void completedVolumeSnapshotIsNeverTreatedAsFailedCreation() {
        when(volumeSnapshot.getState()).thenReturn(Snapshot.State.BackedUp); strategy.recoveryArtifacts(target);
    }
    @Test(expected = CloudRuntimeException.class) public void anotherVolumeBackingReferenceBlocksDeletion() {
        VolumeVO volume = mock(VolumeVO.class); when(volume.getChainInfo()).thenReturn("{\"backing\":\"" + ARTIFACT + "\"}");
        when(strategy.volumeDao.findNonDestroyedVolumesByPoolId(4L)).thenReturn(List.of(volume)); strategy.recoveryArtifacts(target);
    }
    @Test(expected = CloudRuntimeException.class) public void anotherSnapshotReferenceBlocksDeletion() {
        SnapshotDataStoreVO other = mock(SnapshotDataStoreVO.class); when(other.getId()).thenReturn(11L); when(other.getInstallPath()).thenReturn(ARTIFACT);
        when(strategy.snapshotDataStoreDao.listByStoreId(4L, DataStoreRole.Primary)).thenReturn(List.of(ref, other)); strategy.recoveryArtifacts(target);
    }
    @Test(expected = CloudRuntimeException.class) public void dependentStorageChildBlocksDeletion() {
        when(strategy.snapshotDataStoreDao.search(any(SearchCriteria.class), isNull())).thenReturn(List.of(mock(SnapshotDataStoreVO.class)));
        strategy.recoveryArtifacts(target);
    }
}
