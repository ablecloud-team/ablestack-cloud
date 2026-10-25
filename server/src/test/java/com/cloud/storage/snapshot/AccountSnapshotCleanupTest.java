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
package com.cloud.storage.snapshot;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import java.util.List;
import java.util.Map;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.DeleteSnapshotsDirCommand;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.SnapshotVO;
import com.cloud.storage.DataStoreRole;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.storage.dao.SnapshotDao;
import com.cloud.storage.dao.SnapshotDetailsDao;
import com.cloud.user.Account;
import com.cloud.user.ResourceLimitService;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.engine.subsystem.api.storage.EndPoint;
import org.apache.cloudstack.engine.subsystem.api.storage.EndPointSelector;
import org.apache.cloudstack.engine.subsystem.api.storage.ObjectInDataStoreStateMachine;
import org.apache.cloudstack.storage.datastore.db.SnapshotDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.SnapshotDataStoreVO;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.InjectMocks;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class AccountSnapshotCleanupTest {
    @Spy @InjectMocks private SnapshotManagerImpl manager = new SnapshotManagerImpl();
    @Mock private VolumeDao _volsDao;
    @Mock private SnapshotDao _snapshotDao;
    @Mock private SnapshotDataStoreDao _snapshotStoreDao;
    @Mock private SnapshotDetailsDao snapshotDetailsDao;
    @Mock private DataStoreManager dataStoreMgr;
    @Mock private EndPointSelector _epSelector;
    @Mock private ResourceLimitService _resourceLimitMgr;
    private Account account;
    private DataStore origin, remote;
    private EndPoint endpoint;
    private SnapshotDataStoreVO reference;
    @Before public void setup() {
        account = mock(Account.class); when(account.getId()).thenReturn(5L); when(account.getDomainId()).thenReturn(1L);
        VolumeVO volume = mock(VolumeVO.class); when(volume.getId()).thenReturn(10L); when(volume.getDataCenterId()).thenReturn(1L);
        when(_volsDao.findIncludingRemovedByAccount(5L)).thenReturn(List.of(volume));
        SnapshotVO snapshot = mock(SnapshotVO.class); when(snapshot.getId()).thenReturn(7L);
        when(_snapshotDao.listByVolumeIdIncludingRemoved(10L)).thenReturn(List.of(snapshot));
        origin = mock(DataStore.class); when(origin.getId()).thenReturn(1L);
        remote = mock(DataStore.class); when(remote.getId()).thenReturn(2L);
        when(dataStoreMgr.getImageStoresByScope(any())).thenReturn(List.of(origin));
        reference = new SnapshotDataStoreVO(); reference.setRole(DataStoreRole.Image); reference.setDataStoreId(2L);
        reference.setState(ObjectInDataStoreStateMachine.State.Destroyed);
        when(_snapshotStoreDao.listBySnapshotId(7L)).thenReturn(List.of(reference));
        when(dataStoreMgr.getDataStore(2L, DataStoreRole.Image)).thenReturn(remote);
        when(snapshotDetailsDao.listDetailsKeyPairs(7L)).thenReturn(Map.of());
        endpoint = mock(EndPoint.class); lenient().when(_epSelector.select(any(DataStore.class))).thenReturn(endpoint);
        lenient().when(endpoint.sendMessage(any(DeleteSnapshotsDirCommand.class))).thenReturn(new Answer(null, true, null));
        lenient().doReturn(true).when(manager).deleteSnapshot(7L, null);
    }
    @Test public void accountCleanupUsesIndividualDeletionAndSweepsEveryCopyStoreWithoutDirectDecrements() {
        assertTrue(manager.deleteSnapshotDirsForAccount(account));
        verify(manager).deleteSnapshot(7L, null);
        verify(_epSelector).select(origin); verify(_epSelector).select(remote);
        verify(_resourceLimitMgr, never()).decrementResourceCount(anyLong(), any(), anyLong());
        verify(_resourceLimitMgr).recalculateResourceCount(5L, 1L, 3);
        verify(_resourceLimitMgr).recalculateResourceCount(5L, 1L, 11);
    }
    @Test public void partialFailureDoesNotSweepAndCanRetryNormally() {
        doReturn(false, true).when(manager).deleteSnapshot(7L, null);
        assertFalse(manager.deleteSnapshotDirsForAccount(account)); verifyNoInteractions(_epSelector);
        assertTrue(manager.deleteSnapshotDirsForAccount(account)); verify(manager, times(2)).deleteSnapshot(7L, null);
    }
    @Test public void hiddenDependencyBlocksDirectorySweep() {
        reference.setState(ObjectInDataStoreStateMachine.State.Hidden);
        assertFalse(manager.deleteSnapshotDirsForAccount(account)); verifyNoInteractions(_epSelector);
    }
    @Test public void pendingPhysicalCleanupBlocksDirectorySweep() {
        when(snapshotDetailsDao.listDetailsKeyPairs(7L)).thenReturn(Map.of("snapshot.force-delete.pending.8", "address"));
        assertFalse(manager.deleteSnapshotDirsForAccount(account)); verifyNoInteractions(_epSelector);
    }
    @Test public void endpointFailureIsNotReportedAsSuccessfulAccountCleanup() {
        when(endpoint.sendMessage(any(DeleteSnapshotsDirCommand.class))).thenReturn(new Answer(null, false, "Store unavailable"));
        assertFalse(manager.deleteSnapshotDirsForAccount(account));
    }
    @Test public void removedAccountFinishesWithoutPublicRecountOfHiddenAccount() {
        when(account.getState()).thenReturn(Account.State.REMOVED);
        assertTrue(manager.deleteSnapshotDirsForAccount(account));
        verify(manager).deleteSnapshot(7L, null);
        verify(_epSelector).select(origin); verify(_epSelector).select(remote);
        verifyNoInteractions(_resourceLimitMgr);
    }
    @Test public void removedAccountStillRetainsUnresolvedPhysicalCleanup() {
        when(account.getState()).thenReturn(Account.State.REMOVED);
        reference.setState(ObjectInDataStoreStateMachine.State.Hidden);
        assertFalse(manager.deleteSnapshotDirsForAccount(account));
        verifyNoInteractions(_epSelector, _resourceLimitMgr);
    }

}
