/*
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
*/

// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information.
package com.cloud.storage.snapshot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import com.cloud.configuration.Resource.ResourceType;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.storage.DataStoreRole;
import com.cloud.storage.Snapshot;
import com.cloud.storage.SnapshotVO;
import com.cloud.storage.Storage;
import com.cloud.storage.dao.SnapshotDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.storage.dao.VolumeDetailsDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.ResourceLimitService;
import com.cloud.user.User;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.SnapshotDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.SnapshotInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.SnapshotService;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.SnapshotDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.MockitoJUnitRunner;
import org.apache.cloudstack.engine.subsystem.api.storage.ObjectInDataStoreStateMachine.State;

@RunWith(MockitoJUnitRunner.class)
public class SnapshotArchiveTest {
    @InjectMocks private SnapshotManagerImpl manager = new SnapshotManagerImpl();
    @Mock private SnapshotDao _snapshotDao;
    @Mock private SnapshotDataStoreDao _snapshotStoreDao;
    @Mock private SnapshotDataFactory snapshotFactory;
    @Mock private SnapshotService snapshotSrv;
    @Mock private AccountManager _accountMgr;
    @Mock private ResourceLimitService _resourceLimitMgr;
    @Mock private VolumeDetailsDao allocationDetailsDao;
    @Mock private com.cloud.storage.dao.SnapshotDetailsDao snapshotDetailsDao;
    @Mock private VolumeDao _volsDao;
    @Mock private PrimaryDataStoreDao _storagePoolDao;
    private SnapshotVO snapshot;
    private SnapshotInfo primary, secondary;
    private StoragePoolVO pool;
    private AtomicReference<List<SnapshotInfo>> copies;

    @Before public void setup() {
        CallContext.register(mock(User.class), mock(Account.class));
        snapshot = mock(SnapshotVO.class);
        lenient().when(snapshot.getId()).thenReturn(7L);
        lenient().when(snapshot.getUuid()).thenReturn("00000000-0000-0000-0000-000000000007");
        lenient().when(snapshot.getName()).thenReturn("archive fixture");
        lenient().when(snapshot.getState()).thenReturn(Snapshot.State.BackedUp);
        lenient().when(snapshot.getVolumeId()).thenReturn(9L);
        lenient().when(snapshot.getAccountId()).thenReturn(2L);
        lenient().when(snapshot.getDomainId()).thenReturn(1L);
        lenient().when(snapshot.getLocationType()).thenReturn(Snapshot.LocationType.PRIMARY);
        when(_snapshotDao.findById(7L)).thenReturn(snapshot);
        primary = copy(DataStoreRole.Primary, 1L, 10L);
        secondary = copy(DataStoreRole.Image, 2L, 11L);
        copies = new AtomicReference<>(List.of(primary, secondary));
        lenient().when(snapshotFactory.getSnapshots(7L, null)).thenAnswer(call -> copies.get());
        pool = mock(StoragePoolVO.class);
        lenient().when(_storagePoolDao.findById(1L)).thenReturn(pool);
        lenient().when(pool.getPoolType()).thenReturn(Storage.StoragePoolType.RBD);
    }
    @After public void teardown() { CallContext.unregister(); }
    private SnapshotInfo copy(DataStoreRole role, long storeId, long ref) {
        SnapshotInfo info = mock(SnapshotInfo.class);
        DataStore store = mock(DataStore.class);
        lenient().when(store.getRole()).thenReturn(role);
        lenient().when(store.getId()).thenReturn(storeId);
        lenient().when(info.getId()).thenReturn(7L);
        lenient().when(info.getDataStore()).thenReturn(store);
        lenient().when(info.getDataStoreRefId()).thenReturn(ref);
        lenient().when(info.getStatus()).thenReturn(State.Ready);
        return info;
    }
    private void verifiedDelete() {
        when(snapshotSrv.verifySnapshotBackup(secondary)).thenReturn(true);
        when(snapshotSrv.deleteSnapshot(primary)).thenAnswer(call -> { copies.set(List.of(secondary)); return true; });
    }
    @Test public void existingSecondaryIsVerifiedBeforePrimaryDeletionWithoutAnotherBackup() {
        verifiedDelete();
        assertSame(snapshot, manager.archiveSnapshotWithLock(7L, null));
        InOrder order = inOrder(snapshotSrv);
        order.verify(snapshotSrv).verifySnapshotBackup(secondary);
        order.verify(snapshotSrv).deleteSnapshot(primary);
        verifyNoMoreInteractions(snapshotSrv);
        verify(snapshot).setLocationType(Snapshot.LocationType.SECONDARY);
        verify(_resourceLimitMgr).recalculateResourceCount(2L, 1L, ResourceType.snapshot.getOrdinal());
        verify(_resourceLimitMgr).recalculateResourceCount(2L, 1L, ResourceType.secondary_storage.getOrdinal());
        verifyNoMoreInteractions(_resourceLimitMgr);
    }
    @Test public void secondaryOnlyRepeatedArchiveDoesNotChangeRecordsOrCounters() {
        copies.set(List.of(secondary));
        when(snapshot.getLocationType()).thenReturn(Snapshot.LocationType.SECONDARY);
        when(snapshotSrv.verifySnapshotBackup(secondary)).thenReturn(true);
        manager.archiveSnapshotWithLock(7L, null); manager.archiveSnapshotWithLock(7L, null);
        verify(snapshotSrv, times(2)).verifySnapshotBackup(secondary);
        verifyNoMoreInteractions(snapshotSrv);
        verify(_snapshotDao, never()).update(anyLong(), any());
        verifyNoInteractions(_resourceLimitMgr);
        assertEquals("ALREADY_ARCHIVED", manager.previewSnapshotArchive(7L).getReason());
    }
    @Test public void unverifiedExistingBackupNeverRemovesPrimary() {
        assertThrows(CloudRuntimeException.class, () -> manager.archiveSnapshotWithLock(7L, null));
        verify(snapshotSrv).verifySnapshotBackup(secondary);
        verifyNoMoreInteractions(snapshotSrv);
        verifyNoInteractions(_resourceLimitMgr);
    }
    @Test public void managedPrimaryOnlyArchivesAfterBackupAndVerification() {
        copies.set(List.of(primary));
        when(pool.isManaged()).thenReturn(true);
        lenient().when(pool.getPoolType()).thenReturn(Storage.StoragePoolType.IscsiLUN);
        assertEquals("BACKUP_REQUIRED", manager.previewSnapshotArchive(7L).getReason());
        when(snapshotSrv.backupSnapshotForArchive(primary)).thenAnswer(call -> { copies.set(List.of(primary, secondary)); return secondary; });
        verifiedDelete();
        manager.archiveSnapshotWithLock(7L, null);
        InOrder order = inOrder(snapshotSrv);
        order.verify(snapshotSrv).backupSnapshotForArchive(primary);
        order.verify(snapshotSrv).verifySnapshotBackup(secondary);
        order.verify(snapshotSrv).deleteSnapshot(primary);
    }
    @Test public void rbdPrimaryOnlyIsSupported() {
        copies.set(List.of(primary));
        assertTrue(manager.previewSnapshotArchive(7L).isEligible());
    }
    @Test public void unmanagedFilePrimaryOnlyIsRejectedBeforeAnyStorageChange() {
        copies.set(List.of(primary));
        when(pool.getPoolType()).thenReturn(Storage.StoragePoolType.SharedMountPoint);
        assertEquals("UNSUPPORTED_PRIMARY", manager.previewSnapshotArchive(7L).getReason());
        assertThrows(InvalidParameterValueException.class, () -> manager.archiveSnapshotWithLock(7L, null));
        verifyNoInteractions(snapshotSrv, _resourceLimitMgr);
    }
    @Test public void incompleteBackupCannotRemovePrimary() {
        copies.set(List.of(primary));
        when(snapshotSrv.backupSnapshotForArchive(primary)).thenReturn(null);
        assertThrows(CloudRuntimeException.class, () -> manager.archiveSnapshotWithLock(7L, null));
        verify(snapshotSrv).backupSnapshotForArchive(primary);
        verifyNoMoreInteractions(snapshotSrv);
        verify(snapshot, never()).setLocationType(any());
    }
    @Test public void failedPrimaryCleanupPreservesBackupAndRetryDoesNotCreateCopy() {
        when(snapshotSrv.verifySnapshotBackup(secondary)).thenReturn(true);
        when(snapshotSrv.deleteSnapshot(primary)).thenReturn(false);
        assertThrows(CloudRuntimeException.class, () -> manager.archiveSnapshotWithLock(7L, null));
        verify(snapshot, never()).setLocationType(any());
        when(primary.getStatus()).thenReturn(State.Destroying);
        assertEquals("RETRY_PRIMARY_CLEANUP", manager.previewSnapshotArchive(7L).getReason());
        when(snapshotSrv.deleteSnapshot(primary)).thenAnswer(call -> { copies.set(List.of(secondary)); return true; });
        manager.archiveSnapshotWithLock(7L, null);
        verify(snapshotSrv, never()).backupSnapshotForArchive(any());
    }
    @Test public void duplicateSecondaryReferencesAreRejected() {
        copies.set(List.of(primary, secondary, copy(DataStoreRole.Image, 2L, 12L)));
        assertEquals("AMBIGUOUS_REFERENCES", manager.previewSnapshotArchive(7L).getReason());
        assertThrows(InvalidParameterValueException.class, () -> manager.archiveSnapshotWithLock(7L, null));
        verifyNoInteractions(snapshotSrv);
    }
    @Test public void allocatedBackupIsAResumePlan() {
        when(secondary.getStatus()).thenReturn(State.Allocated);
        assertEquals("RETRY_BACKUP", manager.previewSnapshotArchive(7L).getReason());
    }
    @Test public void activeCopyOrSnapshotStateRejectsArchive() {
        when(secondary.getStatus()).thenReturn(State.Creating);
        assertEquals("SECONDARY_NOT_STABLE", manager.previewSnapshotArchive(7L).getReason());
        when(snapshot.getState()).thenReturn(Snapshot.State.BackingUp);
        assertEquals("SNAPSHOT_NOT_STABLE", manager.previewSnapshotArchive(7L).getReason());
    }
    @Test public void dependentPrimaryIsPreserved() {
        when(primary.getChild()).thenReturn(mock(SnapshotInfo.class));
        assertEquals("PRIMARY_IN_USE", manager.previewSnapshotArchive(7L).getReason());
        assertThrows(InvalidParameterValueException.class, () -> manager.archiveSnapshotWithLock(7L, null));
        verifyNoInteractions(snapshotSrv);
    }
    @Test public void changedScopeRejectsBeforeAnyStorageChange() {
        assertThrows(InvalidParameterValueException.class, () -> manager.archiveSnapshotWithLock(7L, "stale"));
        verifyNoInteractions(snapshotSrv, _resourceLimitMgr);
    }
    @Test public void ownerAccessIsCheckedBeforeReadingCopiesOrReturningDiagnosis() {
        doThrow(new PermissionDeniedException("denied")).when(_accountMgr).checkAccess(any(Account.class), isNull(), eq(true), eq(snapshot));
        assertThrows(PermissionDeniedException.class, () -> manager.previewSnapshotArchive(7L));
        assertThrows(PermissionDeniedException.class, () -> manager.archiveSnapshot(7L));
        verifyNoInteractions(snapshotFactory, snapshotSrv);
    }
    @Test public void archiveSharesDeletionLockAndAlwaysReleasesItOnFailure() {
        GlobalLock lock = mock(GlobalLock.class);
        when(lock.lock(30)).thenReturn(true);
        try (MockedStatic<GlobalLock> locks = mockStatic(GlobalLock.class)) {
            locks.when(() -> GlobalLock.getInternLock("snapshot-delete-7")).thenReturn(lock);
            assertThrows(CloudRuntimeException.class, () -> manager.archiveSnapshot(7L));
            verify(lock).unlock(); verify(lock).releaseRef();
        }
    }
}
