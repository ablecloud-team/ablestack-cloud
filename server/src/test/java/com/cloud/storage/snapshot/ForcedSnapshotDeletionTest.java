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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import com.cloud.event.UsageEventUtils;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.storage.DataStoreRole;
import com.cloud.storage.Snapshot;
import com.cloud.storage.SnapshotVO;
import com.cloud.storage.dao.SnapshotDao;
import com.cloud.storage.dao.SnapshotDetailsDao;
import com.cloud.storage.dao.SnapshotDetailsVO;
import com.cloud.storage.dao.SnapshotZoneDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.storage.dao.VolumeDetailsDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.ResourceLimitService;
import com.cloud.user.User;
import com.cloud.utils.db.SearchCriteria;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.engine.subsystem.api.storage.SnapshotDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.SnapshotInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.SnapshotService;
import org.apache.cloudstack.storage.datastore.db.SnapshotDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.SnapshotDataStoreVO;
import org.apache.cloudstack.api.response.SnapshotDeletionResponse;
import org.apache.cloudstack.engine.subsystem.api.storage.ObjectInDataStoreStateMachine.State;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.DataCenterVO;
import org.apache.cloudstack.annotation.dao.AnnotationDao;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

@RunWith(MockitoJUnitRunner.class)
public class ForcedSnapshotDeletionTest {
    @InjectMocks private SnapshotManagerImpl manager = new SnapshotManagerImpl();
    @Mock private SnapshotDao _snapshotDao;
    @Mock private SnapshotDataStoreDao _snapshotStoreDao;
    @Mock private SnapshotDetailsDao snapshotDetailsDao;
    @Mock private SnapshotDataFactory snapshotFactory;
    @Mock private SnapshotService snapshotSrv;
    @Mock private AccountManager _accountMgr;
    @Mock private ResourceLimitService _resourceLimitMgr;
    @Mock private com.cloud.user.dao.AccountDao _accountDao;
    @Mock private VolumeDetailsDao allocationDetailsDao;
    @Mock private VolumeDao _volsDao;
    @Mock private DataStoreManager dataStoreMgr;
    @Mock private SnapshotZoneDao snapshotZoneDao;
    @Mock private DataCenterDao dataCenterDao;
    @Mock private AnnotationDao annotationDao;
    @Mock private org.apache.cloudstack.engine.subsystem.api.storage.EndPointSelector _epSelector;
    private SnapshotVO snapshot;
    private SnapshotDataStoreVO ref;
    private SnapshotInfo info;
    private AtomicReference<Snapshot.State> global;
    private List<SnapshotDataStoreVO> refs;
    private Map<String, String> audit;
    private SearchCriteria<SnapshotDataStoreVO> criteria;
    private MockedStatic<UsageEventUtils> usage;

    @Before public void setup() {
        com.cloud.user.AccountVO retainedOwner = org.mockito.Mockito.mock(com.cloud.user.AccountVO.class);
        org.mockito.Mockito.lenient().when(retainedOwner.getState()).thenReturn(Account.State.ENABLED);
        org.mockito.Mockito.lenient().when(_accountDao.findByIdIncludingRemoved(org.mockito.Mockito.anyLong())).thenReturn(retainedOwner);

        usage = mockStatic(UsageEventUtils.class);
        Account account = mock(Account.class); lenient().when(account.getId()).thenReturn(2L);
        CallContext.register(mock(User.class), account);
        lenient().when(_accountMgr.isRootAdmin(2L)).thenReturn(true);
        snapshot = mock(SnapshotVO.class);
        lenient().when(snapshot.getId()).thenReturn(7L);
        lenient().when(snapshot.getUuid()).thenReturn("snapshot-uuid");
        lenient().when(snapshot.getName()).thenReturn("disposable");
        lenient().when(snapshot.getVolumeId()).thenReturn(9L);
        lenient().when(snapshot.getAccountId()).thenReturn(2L);
        lenient().when(snapshot.getDomainId()).thenReturn(1L);
        lenient().when(snapshot.getDataCenterId()).thenReturn(1L);
        global = new AtomicReference<>(Snapshot.State.BackedUp);
        lenient().when(snapshot.getState()).thenAnswer(call -> global.get());
        lenient().doAnswer(call -> { global.set(call.getArgument(0)); return null; }).when(snapshot).setState(any());
        when(_snapshotDao.findById(7L)).thenReturn(snapshot);
        lenient().when(_snapshotDao.update(7L, snapshot)).thenReturn(true);
        ref = reference(11L, 1L);
        refs = new ArrayList<>(List.of(ref));
        lenient().when(_snapshotStoreDao.listBySnapshotId(7L)).thenAnswer(call -> refs);
        lenient().when(_snapshotStoreDao.findBySnapshotIdWithNonDestroyedState(7L)).thenAnswer(call -> {
            List<SnapshotDataStoreVO> active = new ArrayList<>();
            for (SnapshotDataStoreVO item : refs) { if (item.getState() != State.Destroyed) active.add(item); }
            return active;
        });
        lenient().when(_snapshotStoreDao.update(anyLong(), any(SnapshotDataStoreVO.class))).thenReturn(true);
        audit = new TreeMap<>();
        lenient().when(snapshotDetailsDao.listDetailsKeyPairs(7L)).thenAnswer(call -> new TreeMap<>(audit));
        lenient().when(snapshotDetailsDao.findDetail(eq(7L), anyString())).thenAnswer(call -> {
            String key = call.getArgument(1); String value = audit.get(key);
            return value == null ? null : new SnapshotDetailsVO(7L, key, value, false);
        });
        lenient().doAnswer(call -> { audit.put(call.getArgument(1), call.getArgument(2)); return null; }).when(snapshotDetailsDao).addDetail(eq(7L), anyString(), anyString(), eq(false));
        lenient().doAnswer(call -> { audit.remove(call.getArgument(1)); return null; }).when(snapshotDetailsDao).removeDetail(eq(7L), anyString());
        criteria = mock(SearchCriteria.class);
        lenient().when(_snapshotStoreDao.createSearchCriteria()).thenReturn(criteria);
        info = mock(SnapshotInfo.class);
        lenient().when(snapshotFactory.getSnapshotForStoreRef(7L, 11L)).thenReturn(info);
        lenient().when(dataStoreMgr.getStoreZoneId(1L, DataStoreRole.Primary)).thenReturn(1L);
    }
    @After public void teardown() { usage.close(); CallContext.unregister(); }
    private SnapshotDataStoreVO reference(long id, long store) {
        SnapshotDataStoreVO item = new SnapshotDataStoreVO(); ReflectionTestUtils.setField(item, "id", id);
        item.setSnapshotId(7L); item.setVolumeId(9L); item.setDataStoreId(store); item.setRole(DataStoreRole.Primary);
        item.setState(State.Ready); item.setDisplay(true); item.setInstallPath("rbd/source/native"); return item;
    }
    private SnapshotDeletionResponse run(Long zone) {
        String revision = manager.forcedSnapshotScopeHash(snapshot, zone, manager.forcedSnapshotScope(7L, zone));
        return manager.forceDeleteSnapshotWithLock(7L, zone, revision);
    }
    @Test public void identifiedPhysicalCleanupTerminatesOnlyInspectedMetadata() {
        doReturn(true).when(snapshotSrv).deleteSnapshot(info);
        SnapshotDeletionResponse result = run(null);
        assertTrue(result.isPhysicalCleanupComplete()); assertEquals(0, result.getPendingCleanup());
        assertEquals(State.Destroyed, ref.getState()); assertFalse(ref.isDisplay());
        assertEquals(Snapshot.State.Destroyed, global.get()); assertTrue(audit.isEmpty());
        verify(snapshotFactory).getSnapshotForStoreRef(7L, 11L);
    }
    @Test public void historicDestroyedReferenceWithoutPendingMarkerIsPhysicallyRechecked() {
        ref.setState(State.Destroyed); ref.setDisplay(false); global.set(Snapshot.State.Destroyed);
        doReturn(true).when(snapshotSrv).deleteSnapshot(info);
        assertEquals(1, manager.forcedSnapshotScope(7L, null).size());
        SnapshotDeletionResponse result = run(null);
        assertTrue(result.isPhysicalCleanupComplete()); assertTrue(audit.isEmpty());
        verify(snapshotSrv).deleteSnapshot(info); usage.verifyNoInteractions();
        org.mockito.Mockito.verify(_snapshotStoreDao, org.mockito.Mockito.never()).update(anyLong(), any(SnapshotDataStoreVO.class));
        org.mockito.Mockito.verify(_snapshotDao, org.mockito.Mockito.never()).update(anyLong(), any(SnapshotVO.class));
    }
    private org.apache.cloudstack.engine.subsystem.api.storage.EndPoint nfsEndpoint() {
        org.apache.cloudstack.engine.subsystem.api.storage.DataStore store = mock(org.apache.cloudstack.engine.subsystem.api.storage.DataStore.class);
        lenient().when(store.getTO()).thenReturn(mock(com.cloud.agent.api.to.NfsTO.class));
        when(dataStoreMgr.getDataStore(1L, DataStoreRole.Image)).thenReturn(store);
        org.apache.cloudstack.engine.subsystem.api.storage.EndPoint endpoint = mock(org.apache.cloudstack.engine.subsystem.api.storage.EndPoint.class);
        lenient().when(_epSelector.select(store)).thenReturn(endpoint);
        return endpoint;
    }
    @Test public void missingReferenceCanRetryItsRetainedNfsAddressWithoutRecreatingMetadata() {
        refs.clear(); global.set(Snapshot.State.Destroyed);
        audit.put("snapshot.force-delete.pending.11", "{\"referenceid\":11,\"snapshotuuid\":\"snapshot-uuid\",\"storeid\":1,\"role\":\"Image\",\"path\":\"snapshots/2/9/backup\",\"volumeid\":9}");
        org.apache.cloudstack.engine.subsystem.api.storage.EndPoint endpoint = nfsEndpoint();
        when(endpoint.sendMessage(any(org.apache.cloudstack.storage.command.DeleteCommand.class))).thenReturn(new com.cloud.agent.api.Answer(null, true, null));
        assertTrue(run(null).isPhysicalCleanupComplete()); assertTrue(audit.isEmpty());
        org.mockito.ArgumentCaptor<org.apache.cloudstack.storage.command.DeleteCommand> command = org.mockito.ArgumentCaptor.forClass(org.apache.cloudstack.storage.command.DeleteCommand.class);
        verify(endpoint).sendMessage(command.capture()); assertEquals("snapshots/2/9/backup", command.getValue().getData().getPath());
        verifyNoInteractions(snapshotSrv); usage.verifyNoInteractions();
        org.mockito.Mockito.verify(_snapshotStoreDao, org.mockito.Mockito.never()).persist(any(SnapshotDataStoreVO.class));
    }
    @Test public void nullAgentReplyRetainsMissingReferenceAddressForRetry() {
        refs.clear(); global.set(Snapshot.State.Destroyed);
        audit.put("snapshot.force-delete.pending.11", "{\"referenceid\":11,\"snapshotuuid\":\"snapshot-uuid\",\"storeid\":1,\"role\":\"Image\",\"path\":\"snapshots/2/9/backup\",\"volumeid\":9}");
        nfsEndpoint(); assertFalse(run(null).isPhysicalCleanupComplete()); assertEquals(1, audit.size());
    }
    @Test public void terminalNfsReferenceUsesPhysicalRecoveryWithoutStateMachineReplay() {
        ref.setRole(DataStoreRole.Image); ref.setInstallPath("snapshots/2/9/backup"); ref.setState(State.Destroyed); ref.setDisplay(false); global.set(Snapshot.State.Destroyed);
        org.apache.cloudstack.engine.subsystem.api.storage.EndPoint endpoint = nfsEndpoint();
        when(endpoint.sendMessage(any(org.apache.cloudstack.storage.command.DeleteCommand.class))).thenReturn(new com.cloud.agent.api.Answer(null, true, null));
        assertTrue(run(null).isPhysicalCleanupComplete()); verifyNoInteractions(snapshotSrv); usage.verifyNoInteractions();
    }
    @Test public void missingPathAllowsExplicitDatabaseCleanupAndRetainsRetryAddress() {
        ref.setInstallPath(null);
        SnapshotDeletionResponse result = run(null);
        assertFalse(result.isPhysicalCleanupComplete()); assertEquals(1, result.getPendingCleanup());
        assertEquals(State.Destroyed, ref.getState()); assertEquals(Snapshot.State.Destroyed, global.get());
        verifyNoInteractions(snapshotSrv);
        assertTrue(audit.get("snapshot.force-delete.pending.11").contains("MISSING_TARGET_METADATA"));
    }
    @Test public void missingStoreThenRestoredMappingCanRetryDestroyedReference() {
        when(snapshotFactory.getSnapshotForStoreRef(7L, 11L)).thenReturn(null);
        assertFalse(run(null).isPhysicalCleanupComplete());
        assertEquals(State.Destroyed, ref.getState()); assertEquals(1, audit.size());
        usage.clearInvocations();
        when(snapshotFactory.getSnapshotForStoreRef(7L, 11L)).thenReturn(info);
        doReturn(true).when(snapshotSrv).deleteSnapshot(info);
        assertTrue(run(null).isPhysicalCleanupComplete());
        assertTrue(audit.isEmpty()); usage.verifyNoInteractions();
    }
    @Test public void physicalStorageFailureIsDatabaseOnlyUntilRetrySucceeds() {
        when(snapshotSrv.deleteSnapshot(info)).thenThrow(new IllegalStateException("busy"));
        assertFalse(run(null).isPhysicalCleanupComplete());
        assertTrue(audit.get("snapshot.force-delete.pending.11").contains("rbd/source/native"));
        doReturn(true).when(snapshotSrv).deleteSnapshot(info);
        assertTrue(run(null).isPhysicalCleanupComplete()); assertTrue(audit.isEmpty());
    }
    @Test public void sharedImageDirectoryIsNeverSentToStorageDelete() {
        ref.setRole(DataStoreRole.Image); ref.setInstallPath("snapshots/2/9");
        assertFalse(run(null).isPhysicalCleanupComplete());
        verifyNoInteractions(snapshotSrv);
    }
    @Test public void ordinaryDiagnosisRevisionCannotAuthorizeForcedCleanup() {
        String normal = manager.snapshotDeletionScopeHash(snapshot, null, refs);
        assertThrows(InvalidParameterValueException.class, () -> manager.forceDeleteSnapshotWithLock(7L, null, normal));
        verifyNoInteractions(snapshotSrv); assertEquals(State.Ready, ref.getState());
    }
    @Test public void forceWithoutFreshDiagnosisIsRejected() {
        assertThrows(InvalidParameterValueException.class, () -> manager.forceDeleteSnapshot(7L, null, null));
        verifyNoInteractions(snapshotSrv);
    }
    @Test public void forceIsUnavailableToNormalOwners() {
        when(_accountMgr.isRootAdmin(2L)).thenReturn(false);
        assertThrows(PermissionDeniedException.class, () -> manager.previewSnapshotDeletion(7L, null, true));
        assertThrows(PermissionDeniedException.class, () -> manager.forceDeleteSnapshot(7L, null, "revision"));
        verifyNoInteractions(snapshotSrv);
    }
    @Test public void unknownZoneCannotExpandAScopedForceRequest() {
        DataCenterVO zone = mock(DataCenterVO.class); when(dataCenterDao.findById(1L)).thenReturn(zone);
        when(dataStoreMgr.getStoreZoneId(1L, DataStoreRole.Primary)).thenReturn(null);
        assertThrows(InvalidParameterValueException.class, () -> run(1L));
        assertEquals(State.Ready, ref.getState()); verifyNoInteractions(snapshotSrv);
    }
    @Test public void anotherZonesLiveReferenceAndGlobalSnapshotRemain() {
        DataCenterVO zone = mock(DataCenterVO.class); when(dataCenterDao.findById(1L)).thenReturn(zone);
        when(zone.getUuid()).thenReturn("zone-one");
        SnapshotDataStoreVO other = reference(12L, 2L); refs.add(other);
        when(dataStoreMgr.getStoreZoneId(2L, DataStoreRole.Primary)).thenReturn(2L);
        doReturn(true).when(snapshotSrv).deleteSnapshot(info);
        assertTrue(run(1L).isPhysicalCleanupComplete());
        assertEquals(State.Ready, other.getState()); assertTrue(other.isDisplay());
        assertEquals(Snapshot.State.BackedUp, global.get());
    }
    @Test public void knownChildBlocksEvenDatabaseOnlyCleanupWithMissingMetadata() {
        ref.setInstallPath(null);
        when(_snapshotStoreDao.search(criteria, null)).thenReturn(List.of(reference(12L, 1L)));
        assertThrows(InvalidParameterValueException.class, () -> run(null));
        assertEquals(State.Ready, ref.getState()); assertTrue(audit.isEmpty());
    }
    @Test public void activeBackupCannotBeOverriddenByForce() {
        global.set(Snapshot.State.BackingUp);
        assertThrows(InvalidParameterValueException.class, () -> run(null));
        verifyNoInteractions(snapshotSrv);
    }
    @Test public void unmappedGlobalMetadataCanBeTerminatedWithoutClaimingPhysicalDeletion() {
        refs.clear();
        SnapshotDeletionResponse result = run(null);
        assertEquals(Snapshot.State.Destroyed, global.get()); assertFalse(result.isPhysicalCleanupComplete());
        assertTrue(audit.containsKey("snapshot.force-delete.unmapped")); verifyNoInteractions(snapshotSrv);
        usage.verify(() -> UsageEventUtils.publishUsageEvent("SNAPSHOT.DELETE", 2L, 1L, 7L, "disposable", null, null, 0L,
                snapshot.getClass().getName(), "snapshot-uuid"));
        usage.clearInvocations();
        assertFalse(run(null).isPhysicalCleanupComplete());
        usage.verifyNoInteractions();
    }
    @Test public void removedOwnerDoesNotTurnCompletedPhysicalRecoveryIntoRecountFailure() {
        com.cloud.user.AccountVO removed = mock(com.cloud.user.AccountVO.class);
        when(removed.getState()).thenReturn(Account.State.REMOVED);
        when(_accountDao.findByIdIncludingRemoved(2L)).thenReturn(removed);
        doReturn(true).when(snapshotSrv).deleteSnapshot(info);
        SnapshotDeletionResponse result = run(null);
        assertTrue(result.isPhysicalCleanupComplete()); assertEquals(0, result.getPendingCleanup());
        assertEquals(State.Destroyed, ref.getState()); assertEquals(Snapshot.State.Destroyed, global.get());
        org.mockito.Mockito.verifyNoInteractions(_resourceLimitMgr);
    }
    @Test public void missingOwnerStillAllowsExplicitPhysicalRecovery() {
        when(_accountDao.findByIdIncludingRemoved(2L)).thenReturn(null);
        doReturn(true).when(snapshotSrv).deleteSnapshot(info);
        assertTrue(run(null).isPhysicalCleanupComplete());
        org.mockito.Mockito.verifyNoInteractions(_resourceLimitMgr);
    }

}
