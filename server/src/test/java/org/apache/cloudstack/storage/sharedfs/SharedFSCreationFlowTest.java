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
package org.apache.cloudstack.storage.sharedfs;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import com.cloud.dc.DataCenter;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.VolumeApiService;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.AccountManager;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.UserVmManager;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.utils.db.*;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.dataservice.StorageService;
import org.apache.cloudstack.storage.dataservice.StorageFileShareVO;
import org.apache.cloudstack.storage.dataservice.StorageBlockTargetVO;
import org.apache.cloudstack.storage.dataservice.dao.*;
import org.junit.*;
import org.mockito.*;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class SharedFSCreationFlowTest {
    @Mock AccountManager accountMgr;
    @Mock VolumeDao volumeDao;
    @Mock VolumeApiService volumeApiService;
    @Mock com.cloud.dc.dao.DataCenterDao dataCenterDao;
    @Mock org.apache.cloudstack.storage.sharedfs.dao.SharedFSDao sharedFSDao;
    @Mock UserVmDao userVmDao;
    @Mock PrimaryDataStoreDao storagePoolDao;
    @Mock StorageFileShareDao storageFileShareDao;
    @Mock StorageBlockTargetDao storageBlockTargetDao;
    @Mock StorageServiceInstanceDao storageServiceInstanceDao;
    @Mock StorageServiceProtocolDao storageServiceProtocolDao;
    @Mock StorageAccessRuleDao storageAccessRuleDao;
    @Mock StorageService storageService;
    @Spy @InjectMocks SharedFSServiceImpl service;
    SharedFSVO fs;
    SharedFSLifeCycle life;
    VolumeVO data;
    SharedFSCreationContext initial;
    AtomicReference<String> journal;
    AtomicReference<Long> attached = new AtomicReference<>();
    AtomicReference<Long> vmBinding = new AtomicReference<>();
    AtomicReference<SharedFS.State> state = new AtomicReference<>(SharedFS.State.Allocated);
    MockedStatic<GlobalLock> locks;
    MockedStatic<org.apache.cloudstack.framework.jobs.AsyncJobExecutionContext> jobs;
    AutoCloseable mocks;
    @Before public void setUp() throws Exception {
        mocks=MockitoAnnotations.openMocks(this);
        jobs=mockStatic(org.apache.cloudstack.framework.jobs.AsyncJobExecutionContext.class);
        GlobalLock lock=mock(GlobalLock.class);when(lock.lock(anyInt())).thenReturn(true);
        locks=mockStatic(GlobalLock.class);locks.when(()->GlobalLock.getInternLock(anyString())).thenReturn(lock);
        initial=new SharedFSCreationContext();initial.existing=true;initial.volumeId=10L;initial.networkId=20L;
        initial.expectedFilesystem="AUTO";initial.importMode="MOUNT_EXISTING";initial.planVm("sf-fixture");
        initial.originalPoolId=30L;initial.originalPath="source-path";initial.originalSize=21474836480L;initial.originalDiskOfferingId=40L;
        journal=new AtomicReference<>(initial.json());fs=mock(SharedFSVO.class);
        when(fs.getId()).thenReturn(1L);when(fs.getUuid()).thenReturn("sf-fixture");when(fs.getAccountId()).thenReturn(2L);
        when(fs.getDataCenterId()).thenReturn(3L);when(fs.getServiceOfferingId()).thenReturn(4L);when(fs.getFsProviderName()).thenReturn("SHAREDFSVM");
        when(fs.getCreationContext()).thenAnswer(x->journal.get());doAnswer(x->{journal.set(x.getArgument(0));return null;}).when(fs).setCreationContext(anyString());
        when(fs.getVmId()).thenAnswer(x->vmBinding.get());doAnswer(x->{vmBinding.set(x.getArgument(0));return null;}).when(fs).setVmId(any());
        when(fs.getVolumeId()).thenReturn(10L);when(fs.getState()).thenAnswer(x->state.get());
        when(sharedFSDao.findById(1L)).thenReturn(fs);
        SharedFSProvider provider=mock(SharedFSProvider.class);life=mock(SharedFSLifeCycle.class);
        when(provider.getSharedFSLifeCycle()).thenReturn(life);doReturn(provider).when(service).getSharedFSProvider(anyString());
        com.cloud.dc.DataCenterVO zone=mock(com.cloud.dc.DataCenterVO.class);
        when(zone.getAllocationState()).thenReturn(com.cloud.org.Grouping.AllocationState.Enabled);
        when(zone.getNetworkType()).thenReturn(DataCenter.NetworkType.Advanced);
        when(dataCenterDao.findById(3L)).thenReturn(zone);
        when(life.checkCreationPrerequisites(any(),eq(4L),anyMap())).thenReturn(Map.of());
        when(life.allocateInitialSharedFSVm(eq(fs),eq(20L),anyMap(),eq(initial.plannedVmUuid))).thenReturn(7L);
        UserVmVO vm=mock(UserVmVO.class);when(vm.getUuid()).thenReturn(initial.plannedVmUuid);
        when(vm.getAccountId()).thenReturn(2L);when(vm.getUserVmType()).thenReturn(UserVmManager.SHAREDFSVM);
        when(vm.getState()).thenReturn(VirtualMachine.State.Running);when(userVmDao.findById(7L)).thenReturn(vm);
        data=mock(VolumeVO.class);when(data.getId()).thenReturn(10L);when(data.getAccountId()).thenReturn(2L);
        when(data.getDataCenterId()).thenReturn(3L);when(data.getVolumeType()).thenReturn(Volume.Type.DATADISK);
        when(data.getState()).thenReturn(Volume.State.Ready);when(data.getPoolId()).thenReturn(30L);
        when(data.getPath()).thenReturn("source-path");when(data.getSize()).thenReturn(21474836480L);when(data.getDiskOfferingId()).thenReturn(40L);
        when(data.getInstanceId()).thenAnswer(x->attached.get());when(volumeDao.findById(10L)).thenReturn(data);
        when(sharedFSDao.listByBackingVolume(10L)).thenReturn(List.of(fs));
        org.apache.cloudstack.storage.datastore.db.StoragePoolVO pool=mock(org.apache.cloudstack.storage.datastore.db.StoragePoolVO.class);
        when(pool.getStatus()).thenReturn(com.cloud.storage.StoragePoolStatus.Up);
        when(pool.getDataCenterId()).thenReturn(3L);
        when(pool.getScope()).thenReturn(com.cloud.storage.ScopeType.ZONE);
        when(storagePoolDao.findById(30L)).thenReturn(pool);
        when(storageFileShareDao.createSearchCriteria()).thenReturn(mock(SearchCriteria.class));when(storageFileShareDao.search(any(),isNull())).thenReturn(List.of());
        when(storageBlockTargetDao.createSearchCriteria()).thenReturn(mock(SearchCriteria.class));when(storageBlockTargetDao.search(any(),isNull())).thenReturn(List.of());
        doAnswer(x->{attached.set(7L);return null;}).when(volumeApiService).attachVolumeToVM(eq(7L),eq(10L),isNull(),eq(true));
        doAnswer(x->{attached.set(null);return null;}).when(volumeApiService).detachVolumeViaDestroyVM(7L,10L);
        when(storageService.prepareInitialImportedBackingVolume(eq(fs),anyString())).thenAnswer(x->inspection(x.getArgument(1)));
        doNothing().when(service).syncSharedFSToStorageService(any());
        doAnswer(x->{SharedFS.Event e=x.getArgument(1);if(e==SharedFS.Event.OperationSucceeded)state.set(SharedFS.State.Ready);
            if(e==SharedFS.Event.OperationFailed)state.set(SharedFS.State.Error);if(e==SharedFS.Event.CreationResumeRequested)state.set(SharedFS.State.Allocated);return true;})
            .when(service).stateTransitTo(eq(fs),any());
    }
    String inspection(String mode) {
        return "{\"volumeUuid\":\"data-fixture\",\"filesystem\":\"ext4\",\"filesystemUuid\":\"fs-uuid\",\"bootId\":\"boot\",\"volumeMountPath\":\"/srv/volume\",\"persistentMount\":"+mode.equals("MOUNT_EXISTING")+"}";
    }
    @After public void tearDown() throws Exception { locks.close();jobs.close();mocks.close(); }
    @Test public void existingUsesOneVmAndOneAttachThenReplayDoesNotAllocate() throws Exception {
        service.continueCreation(1L,"CONTINUE");assertEquals(SharedFS.State.Ready,state.get());
        assertEquals("READY",SharedFSCreationContext.read(journal.get()).phase);
        service.continueCreation(1L,"CONTINUE");
        verify(life,times(1)).allocateInitialSharedFSVm(any(),any(),anyMap(),anyString());
        verify(volumeApiService,times(1)).attachVolumeToVM(7L,10L,null,true);
        verify(fs).setFsType(SharedFS.FileSystemType.EXT4);
    }
    @Test public void inspectOnlyPausesThenMountContinuesSameVmAndData() throws Exception {
        initial.importMode="INSPECT_ONLY";journal.set(initial.json());
        service.continueCreation(1L,"CONTINUE");assertEquals(SharedFS.State.Allocated,state.get());
        assertEquals("INSPECTED",SharedFSCreationContext.read(journal.get()).phase);
        verify(storageService,never()).prepareInitialImportedBackingVolume(fs,"MOUNT_EXISTING");
        service.continueCreation(1L,"MOUNT_EXISTING");assertEquals(SharedFS.State.Ready,state.get());
        verify(life,times(1)).allocateInitialSharedFSVm(any(),any(),anyMap(),anyString());
        verify(volumeApiService,times(1)).attachVolumeToVM(7L,10L,null,true);
    }
    @Test public void lostAttachResponseIsObservedWithoutSecondAttach() throws Exception {
        doAnswer(x->{attached.set(7L);throw new CloudRuntimeException("simulated lost attach response");})
            .when(volumeApiService).attachVolumeToVM(7L,10L,null,true);
        try {service.continueCreation(1L,"CONTINUE");fail();} catch(CloudRuntimeException expected) { }
        assertEquals("OBSERVATION_PENDING",SharedFSCreationContext.read(journal.get()).phase);
        service.continueCreation(1L,"CONTINUE");assertEquals(SharedFS.State.Ready,state.get());
        verify(volumeApiService,times(1)).attachVolumeToVM(7L,10L,null,true);
        verify(life,times(1)).allocateInitialSharedFSVm(any(),any(),anyMap(),anyString());
    }
    @Test public void filesystemMismatchCompensatesOnlyOwnedVmAndAttachment() throws Exception {
        initial.expectedFilesystem="XFS";journal.set(initial.json());
        try {service.continueCreation(1L,"CONTINUE");fail();} catch(com.cloud.exception.InvalidParameterValueException expected) { }
        assertNull(attached.get());assertEquals("CANCELLED",SharedFSCreationContext.read(journal.get()).phase);
        verify(storageService).releaseInitialImportedBackingVolume(fs);
        verify(life).cleanupImportedSharedFSVm(fs);
        verify(life,never()).deleteSharedFS(any());
        verify(volumeApiService).attachVolumeToVM(7L,10L,null,true);
        verify(volumeApiService).detachVolumeViaDestroyVM(7L,10L);
        verifyNoMoreInteractions(volumeApiService);
    }

    @Test public void unallocatedCorruptReservationCanBeCancelledWithoutResourceDeletion() throws Exception {
        journal.set("{\"version\":1,");
        service.continueCreation(1L, "CANCEL_PREPARATION");
        assertTrue(SharedFSCreationContext.read(journal.get()).cancelled);
        assertEquals("CANCELLED", SharedFSCreationContext.read(journal.get()).phase);
        verify(fs).setVolumeId(null);
        verifyNoInteractions(volumeApiService, storageService);
        verify(life, never()).cleanupImportedSharedFSVm(any());
        verify(life, never()).deleteSharedFS(any());
    }

    @Test public void corruptReservationWithAnUnboundPlannedVmIsProtected() throws Exception {
        journal.set("{\"version\":1,");
        when(userVmDao.findByUuid(anyString())).thenReturn(mock(UserVmVO.class));
        try { service.continueCreation(1L, "CANCEL_PREPARATION");fail("Planned VM must block cancellation"); }
        catch (com.cloud.exception.InvalidParameterValueException expected) { }
        verify(fs, never()).setVolumeId(null);
        verifyNoInteractions(volumeApiService, storageService);
    }

    @Test public void corruptReservationWithAttachedDataIsProtected() throws Exception {
        journal.set("{\"version\":1,");attached.set(99L);
        try { service.continueCreation(1L, "CANCEL_PREPARATION");fail("Attached DATA must block cancellation"); }
        catch (com.cloud.exception.InvalidParameterValueException expected) { }
        verify(fs, never()).setVolumeId(null);
        verifyNoInteractions(volumeApiService, storageService);
    }

    @Test public void corruptJournalCannotContinueAllocation() throws Exception {
        journal.set("{\"version\":1,");
        try { service.continueCreation(1L, "CONTINUE");fail("Corrupt journal must not allocate"); }
        catch (com.cloud.exception.InvalidParameterValueException expected) {
            assertTrue(expected.getMessage().contains(SharedFSCreationContext.INVALID_JOURNAL));
        }
        verify(life, never()).allocateInitialSharedFSVm(any(),any(),anyMap(),anyString());
        verifyNoInteractions(volumeApiService, storageService);
    }

    void preparedNewFixture(String status) {
        initial.existing=false;initial.prepareNewFileBacking=true;initial.expectedFilesystem="XFS";
        initial.ownedVolumeIds=new java.util.ArrayList<>(List.of(9L,10L));
        initial.preparation=com.google.gson.JsonParser.parseString("{\"operationId\":\"33333333-2222-4333-8444-555555555555\",\"status\":\""+status+"\",\"phase\":\"QUEUED\"}").getAsJsonObject();
        journal.set(initial.json());vmBinding.set(7L);attached.set(7L);
        when(data.getUuid()).thenReturn("11111111-2222-4333-8444-555555555555");
    }
    @Test public void runningNewPreparationDoesNotRepeatVmAllocationOrNetworkSetup() throws Exception {
        preparedNewFixture("RUNNING");
        when(storageService.operateInitialBackingPreparation(fs,"status")).thenReturn("{\"status\":\"RUNNING\",\"phase\":\"FORMATTING\",\"formatCount\":1}");
        service.continueCreationStep(1L,"OBSERVE_PREPARATION");
        assertEquals(SharedFS.State.Allocated,state.get());
        assertEquals("PREPARING",SharedFSCreationContext.read(journal.get()).phase);
        verify(life,never()).allocateInitialSharedFSVm(any(),any(),anyMap(),anyString());
        verify(life,never()).checkCreationPrerequisites(any(),any(),anyMap());
        verifyNoInteractions(volumeApiService);
    }
    @Test public void preparedNewResumeValidatesMountAndBecomesReadyWithoutReallocation() throws Exception {
        preparedNewFixture("OBSERVATION_PENDING");
        when(storageService.operateInitialBackingPreparation(fs,"resume")).thenReturn("{\"status\":\"PREPARED\",\"phase\":\"PREPARED\",\"filesystemUuid\":\"fs-uuid\",\"observedFilesystem\":\"xfs\",\"mountPath\":\"/srv/ablestack-storage/volumes/11111111-2222-4333-8444-555555555555\"}");
        service.continueCreationStep(1L,"CONTINUE");
        assertEquals(SharedFS.State.Ready,state.get());assertEquals("READY",SharedFSCreationContext.read(journal.get()).phase);
        verify(life,never()).allocateInitialSharedFSVm(any(),any(),anyMap(),anyString());
        verifyNoInteractions(volumeApiService);
    }
    @Test public void pendingNewPreparationIsRetainedWithoutCleanupOrReady() throws Exception {
        preparedNewFixture("RUNNING");
        when(storageService.operateInitialBackingPreparation(fs,"status")).thenReturn("{\"status\":\"OBSERVATION_PENDING\",\"errorCode\":\"TRANSPORT_UNCERTAIN\"}");
        service.continueCreationStep(1L,"OBSERVE_PREPARATION");
        assertEquals(SharedFS.State.Allocated,state.get());
        assertEquals("OBSERVATION_PENDING",SharedFSCreationContext.read(journal.get()).phase);
        verify(life,never()).deleteSharedFS(any());verify(life,never()).cleanupImportedSharedFSVm(any());
        verifyNoInteractions(volumeApiService);
    }
    @Test public void activeOrUncertainNewPreparationCannotBeCancelled() throws Exception {
        preparedNewFixture("OBSERVATION_PENDING");
        try {service.continueCreationStep(1L,"CANCEL_PREPARATION");fail();}
        catch(com.cloud.exception.InvalidParameterValueException expected) { }
        verifyNoInteractions(volumeApiService,storageService);
        verify(life,never()).deleteSharedFS(any());
    }
    @Test public void preparedReplyWithForeignMountCannotBecomeReady() throws Exception {
        preparedNewFixture("OBSERVATION_PENDING");
        when(storageService.operateInitialBackingPreparation(fs,"resume")).thenReturn("{\"status\":\"PREPARED\",\"filesystemUuid\":\"fs-uuid\",\"observedFilesystem\":\"xfs\",\"mountPath\":\"/foreign\"}");
        try {service.continueCreationStep(1L,"CONTINUE");fail();}
        catch(com.cloud.exception.InvalidParameterValueException expected) { }
        assertEquals(SharedFS.State.Allocated,state.get());verifyNoInteractions(volumeApiService);
        verify(life,never()).deleteSharedFS(any());
    }
    @Test public void staticBootstrapPendingRetainsNewVmAndOwnedDataThenReplaysSameVm() throws Exception {
        initial.existing=false;initial.prepareNewFileBacking=true;initial.expectedFilesystem="XFS";
        initial.diskOfferingId=40L;initial.storageId=30L;initial.ownedVolumeIds=new ArrayList<>(List.of(9L,10L));
        journal.set(initial.json());vmBinding.set(7L);attached.set(7L);
        VolumeVO root=mock(VolumeVO.class);when(root.getId()).thenReturn(9L);
        when(root.getVolumeType()).thenReturn(Volume.Type.ROOT);when(root.getName()).thenReturn("sharedfs-ROOT");
        when(data.getDeviceId()).thenReturn(1L);when(data.getName()).thenReturn("sharedfs-DATA");
        when(volumeDao.findByInstance(7L)).thenReturn(List.of(root,data));
        doThrow(new SharedFSServiceImpl.SharedFsBootstrapPendingTimeoutException("still initializing"))
                .doNothing().when(service).configureStaticNetwork(fs);
        doReturn(true).when(service).prepareInitialNewData(eq(fs),any(),eq("CONTINUE"));
        try {service.continueCreationStep(1L,"CONTINUE");fail();}
        catch(SharedFSServiceImpl.SharedFsBootstrapPendingTimeoutException expected) { }
        SharedFSCreationContext pending=SharedFSCreationContext.read(journal.get());
        assertEquals("OBSERVATION_PENDING",pending.phase);assertFalse(pending.cancelled);
        assertEquals(initial.plannedVmUuid,pending.plannedVmUuid);assertEquals(List.of(9L,10L),pending.ownedVolumeIds);
        assertEquals(Long.valueOf(7L),vmBinding.get());assertEquals(SharedFS.State.Error,state.get());
        verify(life,never()).deleteSharedFS(any());verify(life,never()).cleanupImportedSharedFSVm(any());
        verify(service,never()).prepareInitialNewData(any(),any(),anyString());
        service.continueCreationStep(1L,"CONTINUE");
        assertEquals(SharedFS.State.Ready,state.get());assertEquals(Long.valueOf(7L),vmBinding.get());
        verify(life,never()).allocateInitialSharedFSVm(any(),any(),anyMap(),anyString());
        verify(volumeApiService,never()).attachVolumeToVM(any(),any(),any(),anyBoolean());
        verify(service).prepareInitialNewData(eq(fs),any(),eq("CONTINUE"));
    }

}
