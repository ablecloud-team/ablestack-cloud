// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with this
// work for additional information regarding copyright ownership. The ASF
// licenses this file to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0 .
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
// WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
// License for the specific language governing permissions and limitations.
package com.cloud.vm;

import java.util.List;
import com.cloud.storage.*;
import com.cloud.storage.dao.*;
import com.cloud.vm.snapshot.*;
import com.cloud.vm.snapshot.dao.*;
import org.apache.cloudstack.framework.jobs.*;
import org.apache.cloudstack.framework.jobs.impl.AsyncJobVO;
import org.apache.cloudstack.jobs.JobInfo;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class CloneSnapshotCleanupTest {
    private UserVmManagerImpl manager;
    private VMSnapshotDetailsDao details;
    private VMSnapshotDao snapshots;
    private VMSnapshotManager service;
    private AsyncJobManager jobs;
    private VolumeDetailsDao volumeDetails;
    private VolumeDao volumes;
    private VMSnapshotVO snapshot;
    @Before public void setup() {
        manager = spy(new UserVmManagerImpl()); details = mock(VMSnapshotDetailsDao.class);
        snapshots = mock(VMSnapshotDao.class); service = mock(VMSnapshotManager.class); jobs = mock(AsyncJobManager.class);
        volumeDetails = mock(VolumeDetailsDao.class); volumes = mock(VolumeDao.class);
        ReflectionTestUtils.setField(manager, "vmSnapshotDetailsDao", details);
        ReflectionTestUtils.setField(manager, "_vmSnapshotDao", snapshots);
        ReflectionTestUtils.setField(manager, "_vmSnapshotMgr", service);
        ReflectionTestUtils.setField(manager, "fastCloneJobManager", jobs);
        ReflectionTestUtils.setField(manager, "volumeDetailsDao", volumeDetails);
        ReflectionTestUtils.setField(manager, "_volsDao", volumes);
        snapshot = mock(VMSnapshotVO.class); when(snapshot.getState()).thenReturn(VMSnapshot.State.Ready);
        when(snapshots.findById(1L)).thenReturn(snapshot);
        when(details.findDetail(1L, UserVmManagerImpl.CLONE_SNAPSHOT_CLEANUP)).thenReturn(detail("pending"));
    }
    private VMSnapshotDetailsVO detail(String value) { return new VMSnapshotDetailsVO(1L, "test", value, false); }
    @Test public void userSnapshotsAreNeverAdopted() {
        when(details.findDetail(1L, UserVmManagerImpl.CLONE_SNAPSHOT_CLEANUP)).thenReturn(null);
        manager.reconcileCloneVMSnapshot(1L, false); verifyNoInteractions(service);
    }
    @Test public void activeOrUnobservedOriginJobPreservesParent() {
        when(details.findDetail(1L, UserVmManagerImpl.CLONE_SNAPSHOT_JOB)).thenReturn(detail("5"));
        assertTrue(manager.isCloneSnapshotJobActive(1L));
        AsyncJobVO job = mock(AsyncJobVO.class); when(jobs.getAsyncJob(5L)).thenReturn(job);
        when(job.getStatus()).thenReturn(JobInfo.Status.IN_PROGRESS); assertTrue(manager.isCloneSnapshotJobActive(1L));
        manager.reconcileCloneVMSnapshot(1L, false); verifyNoInteractions(service);
        when(job.getStatus()).thenReturn(JobInfo.Status.FAILED); assertFalse(manager.isCloneSnapshotJobActive(1L));
    }
    @Test public void partialLinkedChildRemovalKeepsParentUntilLastDiskExpunged() {
        when(details.findDetails(1L, "kvmStorageSnapshot")).thenReturn(List.of(detail("10")));
        when(volumeDetails.findDetails("linked.clone.backing.snapshot.id", "10", null))
            .thenReturn(List.of(new VolumeDetailVO(20L,"ref","10",false),new VolumeDetailVO(21L,"ref","10",false)));
        VolumeVO first=mock(VolumeVO.class),last=mock(VolumeVO.class);
        when(volumes.findByIdIncludingRemoved(20L)).thenReturn(first); when(volumes.findByIdIncludingRemoved(21L)).thenReturn(last);
        when(first.getState()).thenReturn(Volume.State.Expunged); when(last.getState()).thenReturn(Volume.State.Ready);
        assertTrue(manager.hasLinkedCloneSnapshotReferences(1L));
        manager.reconcileCloneVMSnapshot(1L,false); verifyNoInteractions(service);
        when(last.getState()).thenReturn(Volume.State.Expunged); assertFalse(manager.hasLinkedCloneSnapshotReferences(1L));
    }
    @Test public void restartResumesPersistentFailureAndDuplicateSuccessIsHarmless() {
        when(details.findDetail(1L, UserVmManagerImpl.CLONE_SNAPSHOT_ATTEMPTS)).thenReturn(detail("2"));
        when(service.deleteVMSnapshot(1L)).thenReturn(true);
        manager.runCloneSnapshotCleanup(1L);
        verify(details).addDetail(1L,UserVmManagerImpl.CLONE_SNAPSHOT_ATTEMPTS,"3",false);
        verify(details).removeDetail(1L,UserVmManagerImpl.CLONE_SNAPSHOT_CLEANUP);
        when(snapshots.findById(1L)).thenReturn(null); manager.reconcileCloneVMSnapshot(1L,false);
        verify(service,times(1)).deleteVMSnapshot(1L);
    }
    @Test public void backoffAndExhaustionSurviveRestartWithoutUnboundedRetries() {
        when(details.findDetail(1L, UserVmManagerImpl.CLONE_SNAPSHOT_NEXT)).thenReturn(detail(String.valueOf(Long.MAX_VALUE)));
        manager.runCloneSnapshotCleanup(1L); verifyNoInteractions(service);
        when(details.findDetail(1L, UserVmManagerImpl.CLONE_SNAPSHOT_NEXT)).thenReturn(null);
        when(details.findDetail(1L, UserVmManagerImpl.CLONE_SNAPSHOT_ATTEMPTS)).thenReturn(detail("4"));
        when(service.deleteVMSnapshot(1L)).thenReturn(false); manager.runCloneSnapshotCleanup(1L);
        verify(details).addDetail(1L,UserVmManagerImpl.CLONE_SNAPSHOT_CLEANUP,"retry-exhausted",false);
        when(details.findDetail(1L, UserVmManagerImpl.CLONE_SNAPSHOT_ATTEMPTS)).thenReturn(detail("5"));
        manager.runCloneSnapshotCleanup(1L); verify(service,times(1)).deleteVMSnapshot(1L);
    }
    @Test public void transientFailureIsRecordedSeparatelyFromSuccessfulClones() {
        when(service.deleteVMSnapshot(1L)).thenThrow(new com.cloud.utils.exception.CloudRuntimeException("Agent unavailable"));
        manager.runCloneSnapshotCleanup(1L);
        verify(details).addDetail(1L,UserVmManagerImpl.CLONE_SNAPSHOT_CLEANUP,"retry",false);
        verifyNoInteractions(volumes); // No rollback of already-created clone volumes.
    }
    @Test public void legacyRecoveryRequiresCompleteFullFlattenedRbdProvenance() {
        org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao pools=mock(org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao.class);
        SnapshotDao diskSnapshots=mock(SnapshotDao.class);
        org.apache.cloudstack.engine.subsystem.api.storage.SnapshotDataFactory factory=mock(org.apache.cloudstack.engine.subsystem.api.storage.SnapshotDataFactory.class);
        ReflectionTestUtils.setField(manager,"_storagePoolDao",pools); ReflectionTestUtils.setField(manager,"_snapshotDao",diskSnapshots); ReflectionTestUtils.setField(manager,"snapshotFactory",factory);
        when(snapshot.getId()).thenReturn(1L); when(snapshot.getVmId()).thenReturn(2L); when(snapshot.getName()).thenReturn("vm-snapshot");
        VolumeVO root=mock(VolumeVO.class); when(root.getId()).thenReturn(3L); when(root.getPoolId()).thenReturn(4L);
        when(root.getVolumeType()).thenReturn(Volume.Type.ROOT); when(root.getUuid()).thenReturn("source-uuid"); when(root.getPath()).thenReturn("source-path");
        when(volumes.findByInstance(2L)).thenReturn(List.of(root));
        org.apache.cloudstack.storage.datastore.db.StoragePoolVO pool=mock(org.apache.cloudstack.storage.datastore.db.StoragePoolVO.class);
        when(pools.findById(4L)).thenReturn(pool); when(pool.getPoolType()).thenReturn(Storage.StoragePoolType.RBD);
        SnapshotVO disk=mock(SnapshotVO.class); when(disk.getId()).thenReturn(5L); when(disk.getName()).thenReturn("1_source-uuid"); when(disk.getCloneType()).thenReturn("full-flattened");
        when(disk.getHypervisorType()).thenReturn(com.cloud.hypervisor.Hypervisor.HypervisorType.KVM);
        when(diskSnapshots.listByVolumeIdIncludingRemoved(3L)).thenReturn(List.of(disk));
        org.apache.cloudstack.engine.subsystem.api.storage.SnapshotInfo info=mock(org.apache.cloudstack.engine.subsystem.api.storage.SnapshotInfo.class);
        when(factory.getSnapshotOnPrimaryStore(5L)).thenReturn(info); when(info.getPath()).thenReturn("rbd/source-path/vm-snapshot");
        assertEquals(List.of(disk),manager.recoverableLegacyCloneDisks(snapshot));
        when(disk.getCloneType()).thenReturn("full"); assertTrue(manager.recoverableLegacyCloneDisks(snapshot).isEmpty());
        when(disk.getCloneType()).thenReturn("full-flattened"); when(info.getPath()).thenReturn("rbd/another/vm-snapshot"); assertTrue(manager.recoverableLegacyCloneDisks(snapshot).isEmpty());
        when(info.getPath()).thenReturn("rbd/source-path/vm-snapshot"); when(disk.getName()).thenReturn("user-snapshot"); assertTrue(manager.recoverableLegacyCloneDisks(snapshot).isEmpty());
    }

}
