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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import com.cloud.storage.*;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.storage.dao.VolumeDetailsDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.apache.cloudstack.storage.command.PrepareSharedMountPointCloneAnswer;
import org.apache.cloudstack.api.command.user.vm.CloneVMCmd;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class AllocatedVmCloneTest {
    private UserVmManagerImpl manager;
    private VolumeDao volumes;
    private VolumeDetailsDao details;
    private PrimaryDataStoreDao pools;
    private VolumeAllocationService allocation;
    private VolumeVO root;
    private UserVmVO source;
    private CloneVMCmd cmd;
    private StoragePoolVO pool;
    @Before public void setup() {
        manager = new UserVmManagerImpl();
        volumes = mock(VolumeDao.class); details = mock(VolumeDetailsDao.class); pools = mock(PrimaryDataStoreDao.class);
        allocation = mock(VolumeAllocationService.class);
        ReflectionTestUtils.setField(manager, "_volsDao", volumes);
        ReflectionTestUtils.setField(manager, "volumeDetailsDao", details);
        ReflectionTestUtils.setField(manager, "_storagePoolDao", pools);
        ReflectionTestUtils.setField(manager, "allocationService", allocation);
        ReflectionTestUtils.setField(manager, "vmInstanceDetailsDao", mock(VMInstanceDetailsDao.class));
        source = mock(UserVmVO.class); when(source.getId()).thenReturn(10L); when(source.getState()).thenReturn(VirtualMachine.State.Stopped); when(source.getDataCenterId()).thenReturn(1L);
        root = mock(VolumeVO.class); when(root.getId()).thenReturn(20L); when(root.getPoolId()).thenReturn(30L); when(root.getVolumeType()).thenReturn(Volume.Type.ROOT);
        when(root.getState()).thenReturn(Volume.State.Ready); when(root.getFormat()).thenReturn(Storage.ImageFormat.RAW); when(root.getSize()).thenReturn(1048576L); when(root.getUuid()).thenReturn("new-uuid");
        when(root.getPassphraseId()).thenReturn(null); when(root.getKmsKeyId()).thenReturn(null);
        when(volumes.findByInstance(10L)).thenReturn(List.of(root));
        when(details.listDetailsKeyPairs(20L)).thenReturn(VolumeAllocationPolicy.request("off", "raw", null));
        pool = mock(StoragePoolVO.class); when(pool.getPoolType()).thenReturn(Storage.StoragePoolType.RBD); when(pool.getId()).thenReturn(30L); when(pools.findById(30L)).thenReturn(pool);
        cmd = mock(CloneVMCmd.class); when(cmd.getType()).thenReturn("full");
    }
    private void rejects(String reason, Runnable call) {
        try { call.run(); fail("must fail before clone mutation"); }
        catch (RuntimeException e) { assertTrue(e.getMessage(), e.getMessage().contains(reason)); }
    }
    @Test public void explicitStoppedRbdReachesQualifiedExecutionWithoutChangingDetails() {
        manager.validateCloneAllocation(cmd, source);
        verify(allocation).validateClone(VolumeAllocationPolicy.request("off", "raw", null), root.getProvisioningType(), 1L, 30L, false);
        verify(details, never()).addDetail(anyLong(), anyString(), anyString(), anyBoolean());
    }
    @Test public void linkedFullConflictRejectsBeforeAgentQualification() {
        when(cmd.getType()).thenReturn("linked"); when(details.listDetailsKeyPairs(20L)).thenReturn(VolumeAllocationPolicy.request("full", "raw", null));
        rejects("TYPE_CONFLICT", () -> manager.validateCloneAllocation(cmd, source)); verifyNoInteractions(allocation);
    }
    @Test public void encryptionAndNonReadySourcesAreNotQualified() {
        when(root.getPassphraseId()).thenReturn(5L);
        rejects("SOURCE_INVALID", () -> manager.validateCloneAllocation(cmd, source)); verifyNoInteractions(allocation);
        when(root.getPassphraseId()).thenReturn(null); when(root.getState()).thenReturn(Volume.State.Creating);
        rejects("SOURCE_INVALID", () -> manager.validateCloneAllocation(cmd, source));
    }
    @Test public void transitionalVmCannotStartPhysicalWork() {
        when(source.getState()).thenReturn(VirtualMachine.State.Starting);
        rejects("STATE_INVALID", () -> manager.validateCloneAllocation(cmd, source)); verifyNoInteractions(allocation);
    }
    @Test public void rawGfsRequiresStoppedAndMaterializedCopy() {
        when(pool.getPoolType()).thenReturn(Storage.StoragePoolType.SharedMountPoint);
        when(source.getState()).thenReturn(VirtualMachine.State.Running);
        rejects("REQUIRES_STOPPED", () -> manager.validateCloneAllocation(cmd, source));
        when(source.getState()).thenReturn(VirtualMachine.State.Stopped);
        manager.validateCloneAllocation(cmd, source); assertTrue(manager.isStoppedAllocatedGfsClone(source));
    }
    @Test public void oldAgentAnswerCannotMarkAllocatedCloneReady() {
        PrimaryDataStoreTO store = mock(PrimaryDataStoreTO.class);
        rejects("EVIDENCE_INVALID", () -> manager.validatePreparedCloneResults(new com.cloud.agent.api.Answer(null, true, "legacy"), store, List.of(root), true));
        verify(root, never()).setState(any());
    }
    @Test public void newGfsCopyNeedsExactTargetIdentityAndFreshCopyReceipt() {
        when(root.getFormat()).thenReturn(Storage.ImageFormat.QCOW2);
        Map<String,String> intent = VolumeAllocationPolicy.request("metadata", "qcow2", null);
        when(details.listDetailsKeyPairs(20L)).thenReturn(intent);
        PrimaryDataStoreTO store = mock(PrimaryDataStoreTO.class); when(store.getUuid()).thenReturn("pool-uuid");
        VolumeObjectTO result = new VolumeObjectTO(); result.setPath("new-uuid"); result.setSize(1048576L); result.setFormat(Storage.ImageFormat.QCOW2); result.setDataStore(store);
        result.setAllocation(VolumeAllocationPolicy.evidence(intent, "GFS2", "copy", "qemu-preallocation-metadata", 4096L, Instant.now(), "stat-st-blocks-times-512"));
        PrepareSharedMountPointCloneAnswer answer = new PrepareSharedMountPointCloneAnswer(null, Map.of(20L,result));
        assertEquals(result, manager.validatePreparedCloneResults(answer, store, List.of(root), true).get(20L));
        result.setPath("source-uuid");
        rejects("EVIDENCE_INVALID", () -> manager.validatePreparedCloneResults(answer, store, List.of(root), true));
    }
}
