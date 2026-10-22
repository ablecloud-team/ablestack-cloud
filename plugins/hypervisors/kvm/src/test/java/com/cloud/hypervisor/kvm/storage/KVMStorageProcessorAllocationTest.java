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
package com.cloud.hypervisor.kvm.storage;

import org.apache.cloudstack.storage.command.CopyCommand;
import org.apache.cloudstack.storage.command.CreateObjectCommand;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;
import org.apache.cloudstack.storage.to.TemplateObjectTO;
import com.cloud.agent.api.Answer;
import com.cloud.storage.Storage;
import com.cloud.storage.VolumeAllocationPolicy;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

public class KVMStorageProcessorAllocationTest {
    private KVMStoragePoolManager pools;
    private KVMStorageProcessor processor;
    private VolumeObjectTO volume;
    private PrimaryDataStoreTO store;
    @Before public void setup() {
        pools = mock(KVMStoragePoolManager.class); processor = new KVMStorageProcessor(pools, mock(LibvirtComputingResource.class));
        store = mock(PrimaryDataStoreTO.class); when(store.getPoolType()).thenReturn(Storage.StoragePoolType.RBD); when(store.getUuid()).thenReturn("pool");
        volume = new VolumeObjectTO(); volume.setDataStore(store); volume.setUuid("00000000-0000-0000-0000-000000000142"); volume.setSize(67108864L);
    }
    private void rejected(Answer answer, String message) { assertFalse(answer.getResult()); assertTrue(answer.getDetails(), answer.getDetails().contains(message)); }
    @Test public void legacyFatCreateCannotReachOldRbdThinPath() {
        volume.setProvisioningType(Storage.ProvisioningType.FAT); rejected(processor.createVolume(new CreateObjectCommand(volume)), "RBD_LEGACY_ALLOCATION_UNVERIFIED"); verifyNoInteractions(pools);
    }
    @Test public void legacySparseTemplateCannotReachCowClone() {
        volume.setProvisioningType(Storage.ProvisioningType.SPARSE); rejected(processor.cloneVolumeFromBaseTemplate(new CopyCommand(new TemplateObjectTO(), volume, 60, false)), "RBD_LEGACY_ALLOCATION_UNVERIFIED"); verifyNoInteractions(pools);
    }
    @Test public void legacyFatImageCopyCannotMasqueradeAsFull() {
        volume.setProvisioningType(Storage.ProvisioningType.FAT); rejected(processor.copyVolumeFromImageCacheToPrimary(new CopyCommand(new VolumeObjectTO(), volume, 60, false)), "RBD_LEGACY_ALLOCATION_UNVERIFIED"); verifyNoInteractions(pools);
    }
    @Test public void explicitFullDoesNotFallbackWhenTemplateMissing() {
        volume.setAllocation(VolumeAllocationPolicy.request("full", "raw", null));
        when(pools.getStoragePool(Storage.StoragePoolType.RBD, "pool")).thenReturn(mock(KVMStoragePool.class));
        TemplateObjectTO template = mock(TemplateObjectTO.class); when(template.getDataStore()).thenReturn(store); when(template.getPath()).thenReturn("rbd/missing");
        rejected(processor.cloneVolumeFromBaseTemplate(new CopyCommand(template, volume, 60, false)), "SOURCE_MISSING");
        verify(pools).getStoragePool(Storage.StoragePoolType.RBD, "pool");
        verify(pools).getPhysicalDisk(Storage.StoragePoolType.RBD, "pool", "rbd/missing"); verifyNoMoreInteractions(pools);
    }
    @Test public void explicitSnapshotRestoreFailsBeforeAnyStorageLookup() {
        volume.setAllocation(VolumeAllocationPolicy.request("full", "raw", null));
        rejected(processor.createVolumeFromSnapshot(new CopyCommand(new VolumeObjectTO(), volume, 60, false)), "ALLOCATION_OPERATION_UNSUPPORTED"); verifyNoInteractions(pools);
    }
    @Test public void explicitMigrationFailsBeforeAnyStorageLookup() {
        volume.setAllocation(VolumeAllocationPolicy.request("off", "raw", null));
        rejected(processor.copyVolumeFromPrimaryToPrimary(new CopyCommand(new VolumeObjectTO(), volume, 60, false)), "ALLOCATION_OPERATION_UNSUPPORTED"); verifyNoInteractions(pools);
    }
    @Test public void fullSourceCannotBeMigratedToLegacyTarget() {
        VolumeObjectTO source = new VolumeObjectTO(); source.setAllocation(VolumeAllocationPolicy.request("full", "raw", null));
        rejected(processor.copyVolumeFromPrimaryToPrimary(new CopyCommand(source, volume, 60, false)), "ALLOCATION_OPERATION_UNSUPPORTED"); verifyNoInteractions(pools);
    }
    @Test public void legacyFatSnapshotTargetIsAlsoRejected() {
        volume.setProvisioningType(Storage.ProvisioningType.FAT);
        rejected(processor.createVolumeFromSnapshot(new CopyCommand(new VolumeObjectTO(), volume, 60, false)), "RBD_LEGACY_ALLOCATION_UNVERIFIED"); verifyNoInteractions(pools);
    }
}
