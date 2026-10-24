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
package com.cloud.storage.snapshot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.cloud.agent.api.storage.CreateEntityDownloadURLAnswer;
import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.domain.dao.DomainDao;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.org.Grouping;
import com.cloud.storage.DataStoreRole;
import com.cloud.storage.Snapshot;
import com.cloud.storage.SnapshotVO;
import com.cloud.storage.dao.SnapshotDao;
import com.cloud.storage.dao.SnapshotDetailsDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountVO;
import com.cloud.user.User;
import com.cloud.user.dao.AccountDao;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.exception.InvalidParameterValueException;
import org.apache.cloudstack.api.command.user.snapshot.CopySnapshotCmd;
import org.apache.cloudstack.api.response.SnapshotCopyTargetsResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.engine.subsystem.api.storage.EndPoint;
import org.apache.cloudstack.engine.subsystem.api.storage.EndPointSelector;
import org.apache.cloudstack.engine.subsystem.api.storage.ObjectInDataStoreStateMachine;
import org.apache.cloudstack.engine.subsystem.api.storage.SnapshotDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.ZoneScope;
import org.apache.cloudstack.storage.datastore.db.SnapshotDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.SnapshotDataStoreVO;
import org.apache.cloudstack.storage.image.datastore.ImageStoreEntity;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.Silent.class)
public class SnapshotTransferTest {
    @Spy @InjectMocks private SnapshotManagerImpl manager = new SnapshotManagerImpl();
    @Mock private SnapshotDao _snapshotDao;
    @Mock private com.cloud.storage.dao.SnapshotZoneDao snapshotZoneDao;
    @Mock private com.cloud.user.ResourceLimitService _resourceLimitMgr;
    @Mock private SnapshotDataStoreDao _snapshotStoreDao;
    @Mock private SnapshotDetailsDao snapshotDetailsDao;
    @Mock private AccountManager _accountMgr;
    @Mock private AccountDao _accountDao;
    @Mock private DomainDao _domainDao;
    @Mock private DataCenterDao dataCenterDao;
    @Mock private DataStoreManager dataStoreMgr;
    @Mock private EndPointSelector _epSelector;
    @Mock private HostDao hostDao;
    @Mock private SnapshotDataFactory snapshotFactory;
    private SnapshotVO snapshot;
    private Account caller;
    private ImageStoreEntity source, target;
    private DataCenterVO sourceZone, targetZone;
    private List<DataCenterVO> zones;
    private List<SnapshotDataStoreVO> refs;
    private Map<Long, ImageStoreEntity> stores;
    private Map<Long, Long> storeZones;
    private Map<Long, List<DataStore>> byZone;
    private Set<Long> readOnly = new HashSet<>();

    @Before public void setup() throws Exception {
        caller = mock(Account.class); when(caller.getId()).thenReturn(2L); when(caller.getDomainId()).thenReturn(1L);
        CallContext.register(mock(User.class), caller);
        when(_accountMgr.isRootAdmin(2L)).thenReturn(true);
        snapshot = mock(SnapshotVO.class);
        when(snapshot.getId()).thenReturn(7L); when(snapshot.getUuid()).thenReturn("snapshot");
        when(snapshot.getName()).thenReturn("snapshot"); when(snapshot.getState()).thenReturn(Snapshot.State.BackedUp);
        when(snapshot.getAccountId()).thenReturn(2L); when(snapshot.getDomainId()).thenReturn(1L);
        when(snapshot.getDataCenterId()).thenReturn(1L); when(snapshot.getVolumeId()).thenReturn(9L);
        when(snapshot.getSize()).thenReturn(1073741824L); when(snapshot.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(_snapshotDao.findById(7L)).thenReturn(snapshot);
        sourceZone = zone(1L, "source"); targetZone = zone(2L, "target"); zones = new ArrayList<>(List.of(sourceZone, targetZone));
        when(dataCenterDao.listAllZones()).thenAnswer(i -> zones);
        when(dataCenterDao.findById(anyLong())).thenAnswer(i -> zones.stream().filter(z -> z.getId() == (Long)i.getArgument(0)).findFirst().orElse(null));
        stores = new HashMap<>(); storeZones = new HashMap<>(); byZone = new HashMap<>();
        source = store(10L, 1L, "source-secondary"); target = store(20L, 2L, "target-secondary");
        refs = new ArrayList<>(List.of(reference(1L, 10L, ObjectInDataStoreStateMachine.State.Ready)));
        when(_snapshotStoreDao.findBySnapshotIdWithNonDestroyedState(7L)).thenAnswer(i -> refs);
        when(_snapshotStoreDao.listReadyBySnapshot(7L, DataStoreRole.Image)).thenAnswer(i -> refs.stream().filter(r -> r.getState() == ObjectInDataStoreStateMachine.State.Ready && r.getRole() == DataStoreRole.Image).collect(java.util.stream.Collectors.toList()));
        when(_snapshotStoreDao.findByStoreSnapshot(eq(DataStoreRole.Image), anyLong(), eq(7L))).thenAnswer(i -> refs.stream().filter(r -> r.getDataStoreId() == (Long)i.getArgument(1)).findFirst().orElse(null));
        when(dataStoreMgr.getDataStore(anyLong(), eq(DataStoreRole.Image))).thenAnswer(i -> stores.get((Long)i.getArgument(0)));
        when(dataStoreMgr.getStoreZoneId(anyLong(), eq(DataStoreRole.Image))).thenAnswer(i -> storeZones.get((Long)i.getArgument(0)));
        when(dataStoreMgr.getImageStoresByScope(any())).thenAnswer(i -> byZone.getOrDefault(((ZoneScope)i.getArgument(0)).getScopeId(), List.of()));
        when(dataStoreMgr.getImageStoresByScopeExcludingReadOnly(any())).thenAnswer(i -> byZone.getOrDefault(((ZoneScope)i.getArgument(0)).getScopeId(), List.of()).stream().filter(store -> !readOnly.contains(store.getId())).collect(java.util.stream.Collectors.toList()));
        when(dataStoreMgr.getImageStoreWithFreeCapacity(anyList())).thenAnswer(i -> ((List<DataStore>)i.getArgument(0)).get(0));
        AccountVO owner = mock(AccountVO.class); when(_accountDao.findById(2L)).thenReturn(owner);
        CreateEntityDownloadURLAnswer metadata = mock(CreateEntityDownloadURLAnswer.class);
        when(metadata.getVirtualSize()).thenReturn(1073741824L); when(metadata.getFileSize()).thenReturn(2490368L);
        doReturn(metadata).when(manager).inspectSnapshotDownload(any(), any());
        when(_snapshotStoreDao.update(anyLong(), any(SnapshotDataStoreVO.class))).thenReturn(true);
    }
    @After public void close() { CallContext.unregister(); }

    private DataCenterVO zone(long id, String name) {
        DataCenterVO z = mock(DataCenterVO.class);
        when(z.getId()).thenReturn(id); when(z.getUuid()).thenReturn("zone-" + id); when(z.getName()).thenReturn(name);
        when(z.getDomainId()).thenReturn(null); when(z.getAllocationState()).thenReturn(Grouping.AllocationState.Enabled); when(z.getType()).thenReturn(DataCenter.Type.Core);
        return z;
    }
    private ImageStoreEntity store(long id, long zone, String name) {
        ImageStoreEntity s = mock(ImageStoreEntity.class);
        when(s.getMountPoint()).thenReturn("mount-" + id); when(s.getId()).thenReturn(id); when(s.getUuid()).thenReturn("store-" + id); when(s.getName()).thenReturn(name); when(s.getRole()).thenReturn(DataStoreRole.Image);
        com.cloud.agent.api.to.NfsTO to = mock(com.cloud.agent.api.to.NfsTO.class); when(to.getUrl()).thenReturn("nfs://server/" + name); when(s.getTO()).thenReturn(to);
        EndPoint ep = mock(EndPoint.class); when(ep.getId()).thenReturn(id + 100);
        HostVO host = mock(HostVO.class); when(host.getStatus()).thenReturn(Status.Up); when(hostDao.findById(id + 100)).thenReturn(host);
        when(_epSelector.findAllEndpointsForScope(s)).thenReturn(List.of(ep));
        stores.put(id, s); storeZones.put(id, zone); byZone.computeIfAbsent(zone, ignored -> new ArrayList<>()).add(s);
        return s;
    }
    private SnapshotDataStoreVO reference(long id, long store, ObjectInDataStoreStateMachine.State state) {
        SnapshotDataStoreVO r = mock(SnapshotDataStoreVO.class);
        when(r.getId()).thenReturn(id); when(r.getSnapshotId()).thenReturn(7L); when(r.getDataStoreId()).thenReturn(store);
        when(r.getRole()).thenReturn(DataStoreRole.Image); when(r.getState()).thenReturn(state);
        return r;
    }
    private CopySnapshotCmd command(Long... ids) {
        CopySnapshotCmd cmd = mock(CopySnapshotCmd.class); when(cmd.getId()).thenReturn(7L); when(cmd.getSourceZoneId()).thenReturn(1L); when(cmd.getDestinationZoneIds()).thenReturn(Arrays.asList(ids));
        return cmd;
    }
    private SnapshotCopyTargetsResponse preview() { return manager.previewSnapshotCopy(7L, 1L); }
    private Map<String, Object> destination() { return preview().getDestinations().get(0); }

    @Test public void readOnlyPlanPinsSourceAndDestinationWithoutUpdatingReferences() {
        SnapshotCopyTargetsResponse plan = preview(); assertTrue(plan.isEligible()); assertEquals(64, plan.getScopeHash().length());
        assertEquals("store-20", plan.getDestinations().get(0).get("storeid")); assertEquals(true, plan.getDestinations().get(0).get("eligible"));
        verify(_snapshotStoreDao, never()).update(anyLong(), any()); verify(_snapshotDao, never()).update(anyLong(), any());
        assertEquals(plan.getScopeHash(), preview().getScopeHash());
    }
    @Test public void primaryOnlyIsNotAReadySecondarySource() {
        refs.clear(); assertEquals("NO_READY_SECONDARY", preview().getReason()); assertFalse(preview().isEligible());
    }
    @Test public void unsupportedProtocolIsExplained() {
        when(((com.cloud.agent.api.to.NfsTO)source.getTO()).getUrl()).thenReturn("cifs://server/source");
        assertEquals("UNSUPPORTED_IMAGE_STORE", preview().getReason());
    }
    @Test public void sourceEndpointMustBeUp() {
        when(_epSelector.findAllEndpointsForScope(source)).thenReturn(List.of()); assertEquals("SOURCE_SECONDARY_UNAVAILABLE", preview().getReason());
    }
    @Test public void noImageStoreIsDifferentFromReadOnlyAndCapacity() {
        byZone.put(2L, List.of()); assertEquals("NO_IMAGE_STORE", destination().get("reason"));
        byZone.put(2L, List.of(target)); readOnly.add(20L); assertEquals("READ_ONLY_IMAGE_STORE", destination().get("reason"));
        readOnly.clear(); doReturn(null).when(dataStoreMgr).getImageStoreWithFreeCapacity(anyList()); assertEquals("NO_CAPACITY", destination().get("reason"));
    }
    @Test public void disabledAndEdgeTargetsAreNotSelectable() {
        when(targetZone.getAllocationState()).thenReturn(Grouping.AllocationState.Disabled); assertEquals("ZONE_DISABLED", destination().get("reason"));
        when(targetZone.getAllocationState()).thenReturn(Grouping.AllocationState.Enabled); when(targetZone.getType()).thenReturn(DataCenter.Type.Edge); assertEquals("EDGE_ZONE", destination().get("reason"));
    }
    @Test public void destinationNeedsARealUpEndpoint() {
        when(_epSelector.findAllEndpointsForScope(target)).thenReturn(List.of()); assertEquals("DESTINATION_SECONDARY_UNAVAILABLE", destination().get("reason"));
    }
    @Test public void activeCopyIsNotSubmittedAgain() {
        refs.add(reference(2L, 20L, ObjectInDataStoreStateMachine.State.Creating)); assertEquals("COPY_IN_PROGRESS", destination().get("reason")); assertEquals(false, destination().get("eligible"));
    }
    @Test public void failedReferenceRetriesItsOriginalStore() {
        refs.add(reference(2L, 20L, ObjectInDataStoreStateMachine.State.Failed)); ImageStoreEntity another = store(21L, 2L, "another");
        assertEquals("RETRY", destination().get("reason")); assertEquals("store-20", destination().get("storeid"));
    }
    @Test public void existingReadyDestinationIsReportedAndNotRecreated() throws Exception {
        refs.add(reference(2L, 20L, ObjectInDataStoreStateMachine.State.Ready));
        assertEquals("ALREADY_PRESENT", destination().get("reason")); assertEquals(false, destination().get("eligible"));
        assertSame(snapshot, manager.copySecondarySnapshotWithLock(command(2L)));
        verify(manager, never()).transferSnapshotToDestination(any(), any(), any(), any());
    }
    @Test public void revisionChangeRejectsBeforePhysicalProbeOrTransfer() {
        String hash = preview().getScopeHash(); when(targetZone.getAllocationState()).thenReturn(Grouping.AllocationState.Disabled);
        CopySnapshotCmd cmd = command(2L); when(cmd.getExpectedScopeHash()).thenReturn(hash);
        assertThrows(InvalidParameterValueException.class, () -> manager.copySecondarySnapshotWithLock(cmd));
        verify(manager, never()).inspectSnapshotDownload(any(), any());
    }
    @Test public void selectionAndOwnerVisibilityAreCheckedOnTheServer() {
        when(_accountMgr.isRootAdmin(2L)).thenReturn(false); when(targetZone.getDomainId()).thenReturn(98L);
        SnapshotCopyTargetsResponse plan = preview(); assertTrue(plan.getDestinations().isEmpty());
        String json = new com.google.gson.Gson().toJson(plan); assertFalse(json.contains("source-secondary")); assertFalse(json.contains("target-secondary"));
    }
    @Test public void successfulVerificationPersistsPhysicalAndVirtualSizes() {
        SnapshotDataStoreVO ref = reference(2L, 20L, ObjectInDataStoreStateMachine.State.Ready); refs.add(ref);
        manager.verifySnapshotCopy(snapshot, target);
        verify(ref).setSize(1073741824L); verify(ref).setPhysicalSize(2490368L); verify(ref).setErrorString("");
    }
    @Test public void failedVerificationDoesNotLeaveAnUnretryableReadyCopy() {
        SnapshotDataStoreVO ref = reference(2L, 20L, ObjectInDataStoreStateMachine.State.Ready); refs.add(ref);
        doThrow(new CloudRuntimeException("invalid image")).when(manager).inspectSnapshotDownload(any(), any());
        assertThrows(CloudRuntimeException.class, () -> manager.verifySnapshotCopy(snapshot, target));
        verify(ref).setState(ObjectInDataStoreStateMachine.State.Failed);
        verify(ref).setDownloadState(com.cloud.storage.VMTemplateStorageResourceAssoc.Status.DOWNLOAD_ERROR);
    }
    @Test public void retryOfAnAlreadyCountedZoneDoesNotChargeSecondaryStorageAgain() {
        when(snapshotZoneDao.findByZoneSnapshot(2L, 7L)).thenReturn(mock(com.cloud.storage.SnapshotZoneVO.class));
        manager.recordSnapshotCopy(caller, refs.get(0), 2L, 2490368L);
        verify(snapshotZoneDao).addSnapshotToZone(7L, 2L);
        verify(_resourceLimitMgr, never()).incrementResourceCount(anyLong(), any(), anyLong());
    }
    @Test public void newZoneCopyIsCountedOnce() {
        when(caller.getId()).thenReturn(Account.ACCOUNT_ID_SYSTEM); when(refs.get(0).getSize()).thenReturn(1073741824L);
        manager.recordSnapshotCopy(caller, refs.get(0), 2L, 2490368L);
        verify(_resourceLimitMgr).incrementResourceCount(Account.ACCOUNT_ID_SYSTEM, com.cloud.configuration.Resource.ResourceType.secondary_storage, 2490368L);
    }
    @Test public void failedPhysicalVerificationCannotPublishASuccessfulZoneOrUsage() {
        when(_snapshotDao.findByIdIncludingRemoved(7L)).thenReturn(snapshot);
        doThrow(new CloudRuntimeException("invalid image")).when(manager).verifySnapshotCopy(snapshot, target);
        assertThrows(CloudRuntimeException.class, () -> manager.completeSecondarySnapshotCopy(refs.get(0), target, caller, 2L, true));
        verify(manager, never()).recordSnapshotCopy(any(), any(), anyLong(), anyLong());
        verify(snapshotZoneDao, never()).addSnapshotToZone(anyLong(), anyLong());
    }
    @Test public void physicalVerificationPrecedesSuccessfulZoneAccounting() {
        when(_snapshotDao.findByIdIncludingRemoved(7L)).thenReturn(snapshot);
        when(refs.get(0).getPhysicalSize()).thenReturn(2490368L);
        when(_snapshotStoreDao.findByStoreSnapshot(DataStoreRole.Image, 20L, 7L)).thenReturn(refs.get(0));
        org.mockito.Mockito.doNothing().when(manager).verifySnapshotCopy(snapshot, target);
        org.mockito.Mockito.doNothing().when(manager).recordSnapshotCopy(caller, refs.get(0), 2L, 2490368L);
        manager.completeSecondarySnapshotCopy(refs.get(0), target, caller, 2L, true);
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(manager);
        order.verify(manager).verifySnapshotCopy(snapshot, target);
        order.verify(manager).recordSnapshotCopy(caller, refs.get(0), 2L, 2490368L);
    }
    @Test public void anyFailedZoneFailsTheJobWhileCompletedCopyCanBeRetriedIdempotently() throws Exception {
        DataCenterVO third = zone(3L, "failed-zone"); zones.add(third); ImageStoreEntity thirdStore = store(30L, 3L, "failed-store");
        doAnswer(i -> ((SnapshotCopyDestination)i.getArgument(2)).zone.getId() == 2L).when(manager).transferSnapshotToDestination(any(), any(), any(), any());
        CloudRuntimeException error = assertThrows(CloudRuntimeException.class, () -> manager.copySecondarySnapshotWithLock(command(2L, 3L)));
        assertTrue(error.getMessage().contains("Completed destinations: target")); assertTrue(error.getMessage().contains("failed-zone"));
        refs.add(reference(2L, 20L, ObjectInDataStoreStateMachine.State.Ready)); refs.add(reference(3L, 30L, ObjectInDataStoreStateMachine.State.Failed));
        doReturn(true).when(manager).transferSnapshotToDestination(any(), any(), any(), any());
        assertSame(snapshot, manager.copySecondarySnapshotWithLock(command(2L, 3L)));
        ArgumentCaptor<SnapshotCopyDestination> destinations = ArgumentCaptor.forClass(SnapshotCopyDestination.class);
        verify(manager, times(3)).transferSnapshotToDestination(eq(snapshot), eq(source), destinations.capture(), any());
        assertEquals(List.of(2L, 3L, 3L), destinations.getAllValues().stream().map(d -> d.zone.getId()).collect(java.util.stream.Collectors.toList()));
    }
}
