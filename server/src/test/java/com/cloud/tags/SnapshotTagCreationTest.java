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
package com.cloud.tags;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.Date;
import java.util.List;
import java.util.Map;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.server.ResourceManagerUtil;
import com.cloud.server.ResourceTag;
import com.cloud.server.ResourceTag.ResourceObjectType;
import com.cloud.storage.Snapshot;
import com.cloud.storage.SnapshotVO;
import com.cloud.tags.dao.ResourceTagDao;
import com.cloud.user.Account;
import com.cloud.user.User;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallbackNoReturn;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.context.CallContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.Silent.class)
public class SnapshotTagCreationTest {
    @Spy @InjectMocks private TaggedResourceManagerImpl manager = new TaggedResourceManagerImpl();
    @Mock private ResourceManagerUtil resourceManagerUtil;
    @Mock private ResourceTagDao _resourceTagDao;
    @Mock private EntityManager _entityMgr;
    private MockedStatic<GlobalLock> global;
    private MockedStatic<Transaction> transactions;
    private GlobalLock lock;
    private SnapshotVO snapshot;
    @Before public void setup() {
        Account account = mock(Account.class); when(account.getId()).thenReturn(2L);
        CallContext.register(mock(User.class), account);
        global = mockStatic(GlobalLock.class); lock = mock(GlobalLock.class);
        global.when(() -> GlobalLock.getInternLock("snapshot-delete-7")).thenReturn(lock); when(lock.lock(30)).thenReturn(true);
        transactions = mockStatic(Transaction.class);
        transactions.when(() -> Transaction.execute(any(TransactionCallbackNoReturn.class))).thenAnswer(i -> {
            ((TransactionCallbackNoReturn)i.getArgument(0)).doInTransactionWithoutResult(null); return null;
        });
        snapshot = mock(SnapshotVO.class); when(snapshot.getAccountId()).thenReturn(2L); when(snapshot.getDomainId()).thenReturn(1L);
        when(snapshot.getState()).thenReturn(Snapshot.State.BackedUp);
        when(_entityMgr.findById(SnapshotVO.class, 7L)).thenReturn(snapshot);
        when(resourceManagerUtil.getResourceId("uuid", ResourceObjectType.Snapshot)).thenReturn(7L);
        when(resourceManagerUtil.getUuid("uuid", ResourceObjectType.Snapshot)).thenReturn("uuid");
        when(_resourceTagDao.persist(any(ResourceTagVO.class))).thenAnswer(i -> i.getArgument(0));
    }
    @After public void close() { transactions.close(); global.close(); CallContext.unregister(); }
    private List<ResourceTag> create() { return manager.createTags(List.of("uuid"), ResourceObjectType.Snapshot, Map.of("key", "value"), null); }
    private void released() { verify(lock).unlock(); verify(lock).releaseRef(); }
    @Test public void liveSnapshotTagCreationUsesSharedDeleteLock() {
        assertEquals(1, create().size());
        org.mockito.InOrder order = inOrder(lock, _resourceTagDao);
        order.verify(lock).lock(30); order.verify(_resourceTagDao).persist(any(ResourceTagVO.class)); order.verify(lock).unlock(); released();
    }
    @Test public void destroyedSnapshotCannotRecreateTags() {
        when(snapshot.getState()).thenReturn(Snapshot.State.Destroyed);
        assertThrows(InvalidParameterValueException.class, this::create); verifyNoInteractions(_resourceTagDao); released();
    }
    @Test public void removedSnapshotCannotRecreateTags() {
        when(snapshot.getRemoved()).thenReturn(new Date()); assertThrows(InvalidParameterValueException.class, this::create); released();
    }
    @Test public void missingSnapshotCannotRecreateTags() {
        // Exercise the target check independently of account metadata resolution.
        when(_entityMgr.findById(SnapshotVO.class, 7L)).thenReturn(null);
        assertThrows(InvalidParameterValueException.class, () -> manager.validateSnapshotTagTarget(7L, ResourceObjectType.Snapshot));
    }
    @Test public void partiallyFailedDestroyingResourceCanStillHaveTags() {
        when(snapshot.getState()).thenReturn(Snapshot.State.Destroying); assertEquals(1, create().size()); released();
    }
    @Test public void aclIsCheckedBeforeTerminalStateIsDisclosed() {
        when(snapshot.getState()).thenReturn(Snapshot.State.Destroyed);
        doThrow(new PermissionDeniedException("denied")).when(resourceManagerUtil).checkResourceAccessible(eq(2L), eq(1L), anyString());
        assertThrows(PermissionDeniedException.class, this::create); verifyNoInteractions(_resourceTagDao); released();
    }
    @Test public void timeoutReleasesReferenceWithoutUnlockOrWrite() {
        when(lock.lock(30)).thenReturn(false); assertThrows(CloudRuntimeException.class, this::create);
        verify(lock).releaseRef(); verify(lock, never()).unlock(); verifyNoInteractions(_resourceTagDao);
    }
    @Test public void lockExceptionReleasesReference() {
        when(lock.lock(30)).thenThrow(new CloudRuntimeException("lock failure")); assertThrows(CloudRuntimeException.class, this::create);
        verify(lock).releaseRef(); verify(lock, never()).unlock();
    }
    @Test public void databaseFailureReleasesLock() {
        when(_resourceTagDao.persist(any(ResourceTagVO.class))).thenThrow(new CloudRuntimeException("db failure"));
        assertThrows(CloudRuntimeException.class, this::create); released();
    }
    @Test public void multipleSnapshotLocksAreUniqueAndNumericallyOrdered() {
        GlobalLock first = mock(GlobalLock.class), second = mock(GlobalLock.class);
        global.when(() -> GlobalLock.getInternLock("snapshot-delete-1")).thenReturn(first);
        global.when(() -> GlobalLock.getInternLock("snapshot-delete-2")).thenReturn(second);
        when(first.lock(30)).thenReturn(true); when(second.lock(30)).thenReturn(true);
        when(resourceManagerUtil.getResourceId("a", ResourceObjectType.Snapshot)).thenReturn(2L);
        when(resourceManagerUtil.getResourceId("b", ResourceObjectType.Snapshot)).thenReturn(1L);
        doReturn(List.of()).when(manager).createTagsWithSnapshotLocksHeld(anyList(), any(), anyMap(), isNull());
        manager.createTags(List.of("a", "b", "a"), ResourceObjectType.Snapshot, Map.of("key", "value"), null);
        org.mockito.InOrder order = inOrder(first, second);
        order.verify(first).lock(30); order.verify(second).lock(30); order.verify(second).unlock(); order.verify(second).releaseRef();
        order.verify(first).unlock(); order.verify(first).releaseRef();
    }
    @Test public void laterLockFailureReleasesPreviouslyAcquiredLock() {
        GlobalLock second = mock(GlobalLock.class); global.when(() -> GlobalLock.getInternLock("snapshot-delete-8")).thenReturn(second);
        when(resourceManagerUtil.getResourceId("other", ResourceObjectType.Snapshot)).thenReturn(8L);
        assertThrows(CloudRuntimeException.class, () -> manager.createTags(List.of("uuid", "other"), ResourceObjectType.Snapshot, Map.of("k", "v"), null));
        released(); verify(second).releaseRef(); verify(second, never()).unlock();
    }
    @Test public void otherResourceTypesKeepExistingPathWithoutSnapshotLock() {
        doReturn(List.of()).when(manager).createTagsWithSnapshotLocksHeld(anyList(), any(), anyMap(), isNull());
        manager.createTags(List.of("volume"), ResourceObjectType.Volume, Map.of("k", "v"), null); global.verifyNoInteractions();
        manager.validateSnapshotTagTarget(7L, ResourceObjectType.Volume); verifyNoInteractions(_entityMgr);
    }
}
