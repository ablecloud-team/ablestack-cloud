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
package com.cloud.storage;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Collections;

import org.apache.cloudstack.engine.orchestration.service.VolumeOrchestrationService;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.dc.ClusterVO;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.HostPodVO;
import com.cloud.org.Grouping;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.HostPodDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.fsm.NoTransitionException;
import com.cloud.vm.DiskProfile;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.VirtualMachineManager;

/** Attachment placement must work before the VM's first start without inventing a pod. */
@RunWith(MockitoJUnitRunner.class)
public class VolumeAttachStoragePlacementTest {
    @InjectMocks private VolumeApiServiceImpl service = new VolumeApiServiceImpl();
    @Mock private DataCenterDao zoneDao;
    @Mock private ClusterDao clusterDao;
    @Mock private HostPodDao podDao;
    @Mock private DiskOfferingDao offeringDao;
    @Mock private PrimaryDataStoreDao primaryStoreDao;
    @Mock private VirtualMachineManager vmManager;
    @Mock private VolumeOrchestrationService volumeManager;
    @Mock private UserVmVO vm;
    @Mock private VolumeInfo volume;
    @Mock private DiskOfferingVO offering;
    @Mock private StoragePoolVO pool;
    @Mock private DataCenterVO zone;

    @Before
    public void setup() {
        lenient().when(vm.getDataCenterId()).thenReturn(1L);
        lenient().when(vm.getPodIdToDeployIn()).thenReturn(null);
        lenient().when(vm.getState()).thenReturn(State.Stopped);
        lenient().when(vm.getHypervisorType()).thenReturn(HypervisorType.KVM);
        lenient().when(vmManager.findClusterAndHostIdForVm(vm, false)).thenReturn(new Pair<>(null, null));
        lenient().when(zoneDao.findById(1L)).thenReturn(zone);
        lenient().when(volume.getId()).thenReturn(11L);
        lenient().when(volume.getState()).thenReturn(Volume.State.Allocated);
        lenient().when(volume.getVolumeType()).thenReturn(Volume.Type.DATADISK);
        lenient().when(volume.getName()).thenReturn("new-data");
        lenient().when(volume.getSize()).thenReturn(1073741824L);
        lenient().when(volume.getDiskOfferingId()).thenReturn(7L);
        lenient().when(volume.getTemplateId()).thenReturn(8L);
        lenient().when(offeringDao.findById(7L)).thenReturn(offering);
        lenient().when(offering.getTagsArray()).thenReturn(new String[]{"required-tag"});
        lenient().when(offering.isUseLocalStorage()).thenReturn(true);
        lenient().when(offering.isRecreatable()).thenReturn(true);
    }

    private void zonePool() {
        when(volumeManager.findStoragePool(any(DiskProfile.class), eq(zone), isNull(), isNull(), isNull(), eq(vm),
                eq(Collections.emptySet()))).thenReturn(pool);
    }

    @Test
    public void allocatedBeforeFirstStartUsesZoneAndUnchangedDiskConstraints() {
        zonePool();
        assertSame(pool, service.getPoolForAllocatedOrUploadedVolumeForAttach(volume, vm));
        ArgumentCaptor<DiskProfile> disk = ArgumentCaptor.forClass(DiskProfile.class);
        verify(volumeManager).findStoragePool(disk.capture(), eq(zone), isNull(), isNull(), isNull(), eq(vm), eq(Collections.emptySet()));
        assertEquals(11L, disk.getValue().getVolumeId());
        assertEquals(1073741824L, disk.getValue().getSize());
        assertArrayEquals(new String[]{"required-tag"}, disk.getValue().getTags());
        assertTrue(disk.getValue().useLocalStorage());
        assertTrue(disk.getValue().isRecreatable());
        assertEquals(Long.valueOf(8L), disk.getValue().getTemplateId());
        assertEquals(HypervisorType.KVM, disk.getValue().getHypervisorType());
        verifyNoInteractions(podDao, clusterDao);
    }

    @Test
    public void suitableLookupAlsoAcceptsAbsentPlacement() {
        zonePool();
        assertSame(pool, service.getSuitablePoolForAllocatedOrUploadedVolumeForAttach(volume, vm));
        verifyNoInteractions(podDao);
    }

    @Test
    public void knownClusterOverridesAbsentVmPodAndPreservesHost() {
        when(vmManager.findClusterAndHostIdForVm(vm, false)).thenReturn(new Pair<>(3L, 4L));
        ClusterVO cluster = mock(ClusterVO.class);
        HostPodVO pod = mock(HostPodVO.class);
        when(clusterDao.findById(3L)).thenReturn(cluster);
        when(cluster.getPodId()).thenReturn(9L);
        when(podDao.findById(9L)).thenReturn(pod);
        when(volumeManager.findStoragePool(any(DiskProfile.class), eq(zone), eq(pod), eq(3L), eq(4L), eq(vm),
                eq(Collections.emptySet()))).thenReturn(pool);
        assertSame(pool, service.getPoolForAllocatedOrUploadedVolumeForAttach(volume, vm));
        verify(podDao).findById(9L);
        verify(podDao, never()).findById(null);
    }

    @Test
    public void knownVmPodWithoutClusterIsRetained() {
        when(vm.getPodIdToDeployIn()).thenReturn(2L);
        HostPodVO pod = mock(HostPodVO.class);
        when(podDao.findById(2L)).thenReturn(pod);
        when(volumeManager.findStoragePool(any(DiskProfile.class), eq(zone), eq(pod), isNull(), isNull(), eq(vm),
                eq(Collections.emptySet()))).thenReturn(pool);
        assertSame(pool, service.getPoolForAllocatedOrUploadedVolumeForAttach(volume, vm));
        verifyNoInteractions(clusterDao);
    }

    @Test
    public void hostConstraintIsNotDiscardedWhenPodAndClusterAreAbsent() {
        when(vmManager.findClusterAndHostIdForVm(vm, false)).thenReturn(new Pair<>(null, 4L));
        when(volumeManager.findStoragePool(any(DiskProfile.class), eq(zone), isNull(), isNull(), eq(4L), eq(vm),
                eq(Collections.emptySet()))).thenReturn(pool);
        assertSame(pool, service.getPoolForAllocatedOrUploadedVolumeForAttach(volume, vm));
        verifyNoInteractions(podDao);
    }

    @Test
    public void staleKnownClusterFailsInsteadOfBroadeningPlacement() {
        when(vmManager.findClusterAndHostIdForVm(vm, false)).thenReturn(new Pair<>(3L, 4L));
        CloudRuntimeException error = assertThrows(CloudRuntimeException.class,
                () -> service.getPoolForAllocatedOrUploadedVolumeForAttach(volume, vm));
        assertTrue(error.getMessage().contains("cluster [3]"));
        verifyNoInteractions(volumeManager, podDao);
    }

    @Test
    public void staleKnownPodFailsInsteadOfBroadeningPlacement() {
        when(vm.getPodIdToDeployIn()).thenReturn(2L);
        CloudRuntimeException error = assertThrows(CloudRuntimeException.class,
                () -> service.getPoolForAllocatedOrUploadedVolumeForAttach(volume, vm));
        assertTrue(error.getMessage().contains("pod [2]"));
        verifyNoInteractions(volumeManager);
    }

    @Test
    public void noEligiblePoolHasClearErrorAndCannotCreateOrAttach() {
        CloudRuntimeException error = assertThrows(CloudRuntimeException.class,
                () -> service.createVolumeOnPrimaryForAttachIfNeeded(volume, vm, null));
        assertTrue(error.getMessage().contains("Failed to find a primary storage for volume in state: Allocated"));
        verifyNoInteractions(primaryStoreDao);
    }

    @Test
    public void suitableLookupKeepsNullForNoEligiblePool() {
        assertSame(null, service.getSuitablePoolForAllocatedOrUploadedVolumeForAttach(volume, vm));
    }

    @Test
    public void allocatedDataIsMaterializedEvenWhenRootHasNeverBeenCreated() throws Exception {
        zonePool();
        VolumeVO root = mock(VolumeVO.class);
        when(root.getState()).thenReturn(Volume.State.Allocated);
        VolumeInfo ready = mock(VolumeInfo.class);
        when(volumeManager.createVolumeOnPrimaryStorage(vm, volume, HypervisorType.KVM, pool, null, null)).thenReturn(ready);
        assertSame(ready, service.createVolumeOnPrimaryForAttachIfNeeded(volume, vm, root));
        verifyNoInteractions(primaryStoreDao);
        verify(root, never()).getPoolId();
    }

    @Test
    public void uploadedDataUsesEligiblePoolWithoutVmPlacement() throws Exception {
        when(volume.getState()).thenReturn(Volume.State.Uploaded);
        zonePool();
        VolumeInfo ready = mock(VolumeInfo.class);
        when(volumeManager.createVolumeOnPrimaryStorage(vm, volume, HypervisorType.KVM, pool, null, null)).thenReturn(ready);
        assertSame(ready, service.createVolumeOnPrimaryForAttachIfNeeded(volume, vm, null));
    }

    @Test
    public void readyDataDoesNotRepeatStorageSelectionOrCreation() {
        when(volume.getState()).thenReturn(Volume.State.Ready);
        assertSame(volume, service.createVolumeOnPrimaryForAttachIfNeeded(volume, vm, null));
        verifyNoInteractions(vmManager, volumeManager, podDao, clusterDao);
    }

    @Test
    public void initializedRootKeepsExistingPool() throws Exception {
        VolumeVO root = mock(VolumeVO.class);
        when(root.getState()).thenReturn(Volume.State.Ready);
        when(root.getPoolId()).thenReturn(10L);
        when(primaryStoreDao.findById(10L)).thenReturn(pool);
        VolumeInfo ready = mock(VolumeInfo.class);
        when(volumeManager.createVolumeOnPrimaryStorage(vm, volume, HypervisorType.KVM, pool, null, null)).thenReturn(ready);
        assertSame(ready, service.createVolumeOnPrimaryForAttachIfNeeded(volume, vm, root));
        verifyNoInteractions(vmManager, podDao);
    }

    @Test
    public void uploadedPowerFlexRejectionIsPreserved() {
        when(volume.getState()).thenReturn(Volume.State.Uploaded);
        zonePool();
        when(pool.getPoolType()).thenReturn(Storage.StoragePoolType.PowerFlex);
        assertThrows(InvalidParameterValueException.class, () -> service.createVolumeOnPrimaryForAttachIfNeeded(volume, vm, null));
    }

    @Test
    public void storageCreationFailureRemainsVisible() throws Exception {
        zonePool();
        when(volumeManager.createVolumeOnPrimaryStorage(vm, volume, HypervisorType.KVM, pool, null, null))
                .thenThrow(new NoTransitionException("allocation failed"));
        CloudRuntimeException error = assertThrows(CloudRuntimeException.class,
                () -> service.createVolumeOnPrimaryForAttachIfNeeded(volume, vm, null));
        assertTrue(error.getMessage().contains("Failed to create volume on primary storage"));
        assertTrue(error.getCause() instanceof NoTransitionException);
    }

    private HostPodVO pod(long zoneId, Grouping.AllocationState state) {
        HostPodVO pod = mock(HostPodVO.class);
        when(pod.getDataCenterId()).thenReturn(zoneId);
        lenient().when(pod.getAllocationState()).thenReturn(state);
        return pod;
    }

    @Test
    public void clusterStorageUsesRealEnabledPodsWhenZoneWideLookupIsEmpty() {
        HostPodVO first = pod(1L, Grouping.AllocationState.Enabled);
        HostPodVO second = pod(1L, Grouping.AllocationState.Enabled);
        when(podDao.listByDataCenterId(1L)).thenReturn(java.util.Arrays.asList(first, second));
        when(volumeManager.findStoragePool(any(DiskProfile.class), eq(zone), eq(second), isNull(), isNull(), eq(vm),
                eq(Collections.emptySet()))).thenReturn(pool);
        assertSame(pool, service.getPoolForAllocatedOrUploadedVolumeForAttach(volume, vm));
        ArgumentCaptor<DiskProfile> disk = ArgumentCaptor.forClass(DiskProfile.class);
        verify(volumeManager).findStoragePool(disk.capture(), eq(zone), isNull(), isNull(), isNull(), eq(vm), eq(Collections.emptySet()));
        verify(volumeManager).findStoragePool(eq(disk.getValue()), eq(zone), eq(first), isNull(), isNull(), eq(vm), eq(Collections.emptySet()));
        verify(volumeManager).findStoragePool(eq(disk.getValue()), eq(zone), eq(second), isNull(), isNull(), eq(vm), eq(Collections.emptySet()));
        verify(podDao, never()).findById(null);
    }

    @Test
    public void fallbackSkipsDisabledAndForeignPods() {
        HostPodVO disabled = pod(1L, Grouping.AllocationState.Disabled);
        HostPodVO foreign = pod(9L, Grouping.AllocationState.Enabled);
        when(podDao.listByDataCenterId(1L)).thenReturn(java.util.Arrays.asList(disabled, foreign));
        assertSame(null, service.getSuitablePoolForAllocatedOrUploadedVolumeForAttach(volume, vm));
        verify(volumeManager, never()).findStoragePool(any(), any(), eq(disabled), any(), any(), any(), any());
        verify(volumeManager, never()).findStoragePool(any(), any(), eq(foreign), any(), any(), any(), any());
    }

    @Test
    public void exhaustedRealPodsStillFailWithoutCreatingVolume() {
        HostPodVO pod = pod(1L, Grouping.AllocationState.Enabled);
        when(podDao.listByDataCenterId(1L)).thenReturn(Collections.singletonList(pod));
        assertThrows(CloudRuntimeException.class, () -> service.createVolumeOnPrimaryForAttachIfNeeded(volume, vm, null));
        verify(volumeManager).findStoragePool(any(), eq(zone), eq(pod), isNull(), isNull(), eq(vm), eq(Collections.emptySet()));
        verifyNoInteractions(primaryStoreDao);
    }

    @Test
    public void failedKnownPodDoesNotSearchOtherPods() {
        when(vm.getPodIdToDeployIn()).thenReturn(2L);
        HostPodVO pod = mock(HostPodVO.class);
        when(podDao.findById(2L)).thenReturn(pod);
        assertThrows(CloudRuntimeException.class, () -> service.getPoolForAllocatedOrUploadedVolumeForAttach(volume, vm));
        verify(podDao, never()).listByDataCenterId(1L);
    }

    @Test
    public void failedHostConstraintDoesNotBroadenToOtherPods() {
        when(vmManager.findClusterAndHostIdForVm(vm, false)).thenReturn(new Pair<>(null, 4L));
        assertThrows(CloudRuntimeException.class, () -> service.getPoolForAllocatedOrUploadedVolumeForAttach(volume, vm));
        verifyNoInteractions(podDao);
    }
}
