// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information regarding copyright
// ownership. The ASF licenses this file to you under the Apache License,
// Version 2.0 (the "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.cloud.storage.snapshot;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import com.cloud.server.ResourceTag.ResourceObjectType;
import com.cloud.storage.Snapshot;
import com.cloud.storage.SnapshotVO;
import com.cloud.storage.DataStoreRole;
import com.cloud.storage.dao.SnapshotDao;
import com.cloud.storage.dao.SnapshotDetailsDao;
import com.cloud.storage.dao.SnapshotDetailsVO;
import com.cloud.tags.ResourceTagVO;
import com.cloud.tags.dao.ResourceTagDao;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.engine.subsystem.api.storage.SnapshotStrategy;
import org.apache.cloudstack.engine.subsystem.api.storage.StorageStrategyFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.SnapshotStrategy.SnapshotOperation;
import org.apache.cloudstack.engine.subsystem.api.storage.ObjectInDataStoreStateMachine.State;
import org.apache.cloudstack.storage.datastore.db.SnapshotDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.SnapshotDataStoreVO;
import org.apache.cloudstack.annotation.dao.AnnotationDao;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.Silent.class)
public class SnapshotTagLifecycleTest {
    @Spy @InjectMocks private SnapshotManagerImpl manager = new SnapshotManagerImpl();
    @Mock private SnapshotDao _snapshotDao;
    @Mock private SnapshotDataStoreDao _snapshotStoreDao;
    @Mock private SnapshotDetailsDao snapshotDetailsDao;
    @Mock private com.cloud.storage.dao.VolumeDao _volsDao;
    @Mock private ResourceTagDao _resourceTagDao;
    @Mock private StorageStrategyFactory _storageStrategyFactory;
    @Mock private AnnotationDao annotationDao;
    private SnapshotVO snapshot;
    private SnapshotDataStoreVO ref;
    private SnapshotStrategy strategy;
    private AtomicReference<Snapshot.State> state;
    private org.mockito.MockedStatic<com.cloud.event.UsageEventUtils> usage;

    @Before public void setup() {
        usage = mockStatic(com.cloud.event.UsageEventUtils.class);
        snapshot = mock(SnapshotVO.class); state = new AtomicReference<>(Snapshot.State.BackedUp);
        when(snapshot.getId()).thenReturn(7L); when(snapshot.getVolumeId()).thenReturn(9L);
        when(snapshot.getState()).thenAnswer(i -> state.get());
        when(_snapshotDao.findByIdIncludingRemoved(7L)).thenReturn(snapshot);
        when(_snapshotDao.findById(7L)).thenReturn(snapshot);
        ref = new SnapshotDataStoreVO(); ref.setState(State.Ready); ref.setRole(DataStoreRole.Image);
        ref.setInstallPath("snapshots/2/9/own-backup");
        when(_snapshotStoreDao.listBySnapshotId(7L)).thenReturn(List.of(ref));
        when(snapshotDetailsDao.listDetailsKeyPairs(7L)).thenReturn(Map.of());
        when(_resourceTagDao.listBy(7L, ResourceObjectType.Snapshot)).thenReturn(List.of());
        doReturn(snapshot).when(manager).validateSnapshotDeletion(7L);
        doReturn(new Pair<>(List.of(ref), List.of(1L))).when(manager).getStoreRefsAndZonesForSnapshotDelete(eq(7L), nullable(Long.class));
        doNothing().when(manager).recalculateSnapshotOwnerResourceCount(snapshot);
        strategy = mock(SnapshotStrategy.class);
        when(_storageStrategyFactory.getSnapshotStrategy(eq(snapshot), nullable(Long.class), eq(SnapshotOperation.DELETE))).thenReturn(strategy);
    }
    @After public void close() { usage.close(); }
    private void terminate() { state.set(Snapshot.State.Destroyed); ref.setState(State.Destroyed); }
    private void noTagsRemoved() { verify(_resourceTagDao, never()).removeByIdAndType(anyLong(), any()); }

    @Test public void globalSuccessCleansOnlySnapshotTypeAndExactId() {
        when(strategy.deleteSnapshot(7L, null)).thenAnswer(i -> { terminate(); return true; });
        assertTrue(manager.deleteSnapshotWithLock(7L, null, null));
        verify(_resourceTagDao).removeByIdAndType(7L, ResourceObjectType.Snapshot);
        verify(_resourceTagDao).listBy(7L, ResourceObjectType.Snapshot); verifyNoMoreInteractions(_resourceTagDao);
    }
    @Test public void partialZoneSuccessRetainsLogicalResourceTags() {
        ref.setState(State.Destroyed);
        SnapshotDataStoreVO other = new SnapshotDataStoreVO(); other.setState(State.Ready);
        when(_snapshotStoreDao.listBySnapshotId(7L)).thenReturn(List.of(ref, other));
        when(strategy.deleteSnapshot(7L, 1L)).thenReturn(true);
        assertTrue(manager.deleteSnapshotWithLock(7L, 1L, null)); noTagsRemoved();
    }
    @Test public void destroyedFlagAloneCannotEraseLiveOtherZoneTags() {
        state.set(Snapshot.State.Destroyed); manager.cleanupTerminatedSnapshotTags(7L); noTagsRemoved();
    }
    @Test public void physicalFailureRetainsTags() {
        when(strategy.deleteSnapshot(7L, null)).thenReturn(false);
        assertFalse(manager.deleteSnapshotWithLock(7L, null, null)); noTagsRemoved();
    }
    @Test public void storageExceptionRetainsTags() {
        when(strategy.deleteSnapshot(7L, null)).thenThrow(new CloudRuntimeException("physical failure"));
        assertThrows(CloudRuntimeException.class, () -> manager.deleteSnapshotWithLock(7L, null, null)); noTagsRemoved();
    }
    @Test public void falseResultDoesNotCleanEvenIfDriverChangedState() {
        when(strategy.deleteSnapshot(7L, null)).thenAnswer(i -> { terminate(); return false; });
        assertFalse(manager.deleteSnapshotWithLock(7L, null, null)); noTagsRemoved();
    }
    @Test public void repeatedDestroyedDeleteCleansHistoricTagsWithoutStorageReplay() {
        terminate(); assertTrue(manager.deleteSnapshotWithLock(7L, null, null));
        assertTrue(manager.deleteSnapshotWithLock(7L, null, null));
        verify(_resourceTagDao, times(2)).removeByIdAndType(7L, ResourceObjectType.Snapshot); verifyNoInteractions(strategy);
    }
    @Test public void dependentHiddenChainAllowsLogicalTermination() {
        terminate(); ref.setState(State.Hidden); manager.cleanupTerminatedSnapshotTags(7L);
        verify(_resourceTagDao).removeByIdAndType(7L, ResourceObjectType.Snapshot);
    }
    @Test public void removedErrorWithoutStoreRefsIsTerminal() {
        when(snapshot.getRemoved()).thenReturn(new Date()); state.set(Snapshot.State.Error);
        when(_snapshotStoreDao.listBySnapshotId(7L)).thenReturn(List.of());
        manager.cleanupTerminatedSnapshotTags(7L); verify(_resourceTagDao).removeByIdAndType(7L, ResourceObjectType.Snapshot);
    }
    @Test public void unknownSnapshotNeverDeletesTags() {
        when(_snapshotDao.findByIdIncludingRemoved(7L)).thenReturn(null);
        manager.cleanupTerminatedSnapshotTags(7L); noTagsRemoved();
    }
    @Test public void failedReferenceIsNotTerminal() {
        terminate(); ref.setState(State.Failed); manager.cleanupTerminatedSnapshotTags(7L); noTagsRemoved();
    }
    @Test public void forcedPendingPhysicalTargetKeepsTags() {
        terminate(); when(snapshotDetailsDao.listDetailsKeyPairs(7L)).thenReturn(Map.of("snapshot.force-delete.pending.11", "retry target"));
        manager.cleanupTerminatedSnapshotTags(7L); noTagsRemoved();
    }
    @Test public void forcedUnmappedPhysicalTargetKeepsTagsOnNormalRetry() {
        terminate(); when(snapshotDetailsDao.findDetail(7L, "snapshot.force-delete.unmapped")).thenReturn(mock(SnapshotDetailsVO.class));
        assertTrue(manager.deleteSnapshotWithLock(7L, null, null)); noTagsRemoved();
    }
    @Test public void tagWriteFailureRemainsRetryableAfterResourceTermination() {
        terminate(); when(_resourceTagDao.listBy(7L, ResourceObjectType.Snapshot)).thenReturn((List)List.of(mock(ResourceTagVO.class)), List.of());
        assertThrows(CloudRuntimeException.class, () -> manager.deleteSnapshotWithLock(7L, null, null));
        assertTrue(manager.deleteSnapshotWithLock(7L, null, null)); verifyNoInteractions(strategy);
    }
}
