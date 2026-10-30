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
package org.apache.cloudstack.backup;

import java.util.List;

import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.agent.AgentManager;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.Storage;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.snapshot.dao.VMSnapshotDao;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class SharedVolumeBackupGuardTest {
    @Mock private VMInstanceDao vmDao;
    @Mock private VMSnapshotDao snapshotDao;
    @Mock private VolumeDao volumeDao;
    @Mock private ServiceOfferingDao serviceOfferingDao;
    @Mock private DiskOfferingDao diskOfferingDao;
    @Mock private PrimaryDataStoreDao poolDao;
    @Mock private AgentManager agentManager;
    @Mock private AblestackSharedRawBackupService sharedRaw;
    @InjectMocks private BackupSnapshotGuard guard;
    private VMInstanceVO vm;

    @Before
    public void setup() {
        vm = new VMInstanceVO();
        vm.setServiceOfferingId(100L);
        vm.setHypervisorType(HypervisorType.KVM);
        vm.setState(VirtualMachine.State.Stopped);
        when(vmDao.findById(1L)).thenReturn(vm);
    }

    private void computeOffering(Long diskOfferingId, boolean shareable) {
        ServiceOfferingVO compute = mock(ServiceOfferingVO.class);
        when(compute.getDiskOfferingId()).thenReturn(diskOfferingId);
        when(serviceOfferingDao.findByIdIncludingRemoved(100L)).thenReturn(compute);
        if (diskOfferingId != null) diskOffering(diskOfferingId, shareable);
    }

    private void diskOffering(long id, boolean shareable) {
        DiskOfferingVO offering = mock(DiskOfferingVO.class);
        when(offering.getShareable()).thenReturn(shareable);
        when(diskOfferingDao.findByIdIncludingRemoved(id)).thenReturn(offering);
    }

    private VolumeVO volume(Volume.Type type, Storage.ImageFormat format, long offeringId) {
        VolumeVO volume = new VolumeVO(type, "test-volume", 1L, 1L, 1L,
                offeringId, null, 4096L, null, null, null);
        volume.setFormat(format);
        volume.setPoolId(10L);
        return volume;
    }

    private void assertSharedVolumeBlocked() {
        CloudRuntimeException failure = assertThrows(CloudRuntimeException.class, () -> guard.checkBackup(1L));
        assertTrue(failure.getMessage(), failure.getMessage().startsWith("BACKUP_SHARED_VOLUME_UNSUPPORTED:"));
        verifyNoInteractions(poolDao, agentManager, sharedRaw);
    }

    @Test
    public void computeSharingBlocksStoppedAndRunningVmsWithoutInspectingAttachedWriters() {
        computeOffering(200L, true);
        assertSharedVolumeBlocked();
        vm.setState(VirtualMachine.State.Running);
        assertSharedVolumeBlocked();
        verifyNoInteractions(volumeDao);
    }

    @Test
    public void rawAndQcow2DataDisksAreBlockedRegardlessOfStorageBackend() {
        computeOffering(200L, false);
        diskOffering(300L, true);
        for (Storage.ImageFormat format : List.of(Storage.ImageFormat.RAW, Storage.ImageFormat.QCOW2)) {
            when(volumeDao.findByInstance(1L)).thenReturn(List.of(volume(Volume.Type.DATADISK, format, 300L)));
            assertSharedVolumeBlocked();
        }
    }

    @Test
    public void actualRootOfferingIsCheckedEvenWhenTheCurrentComputeOfferingIsNotShared() {
        computeOffering(200L, false);
        diskOffering(300L, true);
        when(volumeDao.findByInstance(1L)).thenReturn(List.of(volume(Volume.Type.ROOT, Storage.ImageFormat.RAW, 300L)));
        assertSharedVolumeBlocked();
    }

    @Test
    public void sharedDataDiskRejectsTheWholeVmIncludingAnOrdinaryRootDisk() {
        computeOffering(200L, false);
        diskOffering(300L, true);
        when(volumeDao.findByInstance(1L)).thenReturn(List.of(
                volume(Volume.Type.ROOT, Storage.ImageFormat.QCOW2, 200L),
                volume(Volume.Type.DATADISK, Storage.ImageFormat.RAW, 300L)));
        assertSharedVolumeBlocked();
    }

    @Test
    public void computeOnlyOfferingStillChecksTheAttachedDiskOffering() {
        computeOffering(null, false);
        diskOffering(300L, true);
        when(volumeDao.findByInstance(1L)).thenReturn(List.of(volume(Volume.Type.ROOT, Storage.ImageFormat.QCOW2, 300L)));
        assertSharedVolumeBlocked();
    }

    @Test
    public void removedOfferingDefinitionsCannotBypassTheSharingCheck() {
        computeOffering(200L, true);
        assertSharedVolumeBlocked();
        verify(serviceOfferingDao).findByIdIncludingRemoved(100L);
        verify(diskOfferingDao).findByIdIncludingRemoved(200L);
        verify(diskOfferingDao, never()).findById(200L);
    }

    @Test
    public void sharingRestrictionAlsoAppliesOutsideTheKvmOfflineRawPath() {
        vm.setHypervisorType(HypervisorType.VMware);
        computeOffering(200L, true);
        assertSharedVolumeBlocked();
        verifyNoInteractions(snapshotDao);
    }

    @Test
    public void ordinaryRawOnSharedStorageKeepsItsExistingOfflineChecks() {
        computeOffering(200L, false);
        when(volumeDao.findByInstance(1L)).thenReturn(List.of(volume(Volume.Type.ROOT, Storage.ImageFormat.RAW, 200L)));
        StoragePoolVO pool = mock(StoragePoolVO.class);
        when(pool.getPoolType()).thenReturn(Storage.StoragePoolType.SharedMountPoint);
        when(poolDao.findById(10L)).thenReturn(pool);
        guard.checkBackup(1L);
        guard.checkBackupSource(1L, "ablestack-nas");
        verify(sharedRaw).checkSources(1L);
        verifyNoInteractions(agentManager);
    }

    @Test
    public void ordinaryRootAndDataDisksRemainEligible() {
        computeOffering(200L, false);
        diskOffering(300L, false);
        when(volumeDao.findByInstance(1L)).thenReturn(List.of(
                volume(Volume.Type.ROOT, Storage.ImageFormat.QCOW2, 200L),
                volume(Volume.Type.DATADISK, Storage.ImageFormat.RAW, 300L)));
        guard.checkBackup(1L);
        verifyNoInteractions(poolDao, agentManager, sharedRaw);
    }

    @Test
    public void missingComputeDefinitionDoesNotAllowAnUnverifiedBackup() {
        CloudRuntimeException failure = assertThrows(CloudRuntimeException.class, () -> guard.checkBackup(1L));
        assertTrue(failure.getMessage().startsWith("BACKUP_SOURCE_UNKNOWN:"));
        verifyNoInteractions(volumeDao, diskOfferingDao, poolDao, agentManager, sharedRaw);
    }

    @Test
    public void missingDiskDefinitionDoesNotAllowAnUnverifiedBackup() {
        computeOffering(null, false);
        when(volumeDao.findByInstance(1L)).thenReturn(List.of(volume(Volume.Type.DATADISK, Storage.ImageFormat.RAW, 300L)));
        CloudRuntimeException failure = assertThrows(CloudRuntimeException.class, () -> guard.checkBackup(1L));
        assertTrue(failure.getMessage().startsWith("BACKUP_SOURCE_UNKNOWN:"));
        verifyNoInteractions(poolDao, agentManager, sharedRaw);
    }
}
