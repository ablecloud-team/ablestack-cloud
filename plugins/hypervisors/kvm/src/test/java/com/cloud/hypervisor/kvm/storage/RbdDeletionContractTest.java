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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cloud.agent.api.Answer;
import com.cloud.storage.Storage;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.script.Script;
import org.apache.cloudstack.storage.command.DeleteCommand;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.junit.Before;
import org.junit.Test;
import org.libvirt.LibvirtException;
import org.libvirt.StoragePool;
import org.libvirt.StorageVol;

public class RbdDeletionContractTest {
    private static final String IMAGE = "7a927ec9-3d37-4723-872f-c7562132fc12";
    private static final String POOL = "91cae554-3fce-3f93-89d1-cefaf9bf8122";
    private final LibvirtStoragePool pool = mock(LibvirtStoragePool.class);
    private final StoragePool nativePool = mock(StoragePool.class);
    private final StorageVol volume = mock(StorageVol.class);
    private final LibvirtStorageAdaptor adapter = spy(new LibvirtStorageAdaptor(null));
    private final RbdDeletionProbe.Result present = new RbdDeletionProbe.Result(RbdDeletionProbe.State.PRESENT, "READ_IMAGE", 0, "fsid", 3, 0, "image-id");
    private final RbdDeletionProbe.Result absent = new RbdDeletionProbe.Result(RbdDeletionProbe.State.ABSENT_CONFIRMED, "OPEN_IMAGE", -2, "fsid", 3, -1, null);
    private final RbdDeletionProbe.Result unknown = RbdDeletionProbe.unknown("CONNECT", -13, null, -1);

    @Before public void setup() throws Exception {
        when(pool.getType()).thenReturn(Storage.StoragePoolType.RBD); when(pool.getUuid()).thenReturn(POOL); when(pool.getPool()).thenReturn(nativePool);
        when(nativePool.storageVolLookupByName(IMAGE)).thenReturn(volume);
        doReturn(present, absent).when(adapter).probeRbdDeletion(pool, IMAGE);
    }
    private boolean delete() { return adapter.deletePhysicalDisk(IMAGE, pool, Storage.ImageFormat.RAW); }
    private void fails(String reason) {
        try { delete(); fail("Deletion must not be acknowledged"); }
        catch (CloudRuntimeException e) { assertTrue(e.getMessage(), e.getMessage().contains(reason)); }
    }
    private LibvirtException error(org.libvirt.Error.ErrorNumber code) {
        LibvirtException e = mock(LibvirtException.class); org.libvirt.Error details = mock(org.libvirt.Error.class);
        when(details.getCode()).thenReturn(code); when(e.getError()).thenReturn(details); return e;
    }

    @Test public void normalRemovalConfirmsAbsenceAndNeverUnmapsOrUsesCli() throws Exception {
        try (var script = mockStatic(Script.class)) {
            assertTrue(delete()); verify(volume).delete(0); verify(volume).free(); script.verifyNoInteractions();
        }
        verify(adapter, times(2)).probeRbdDeletion(pool, IMAGE);
    }
    @Test public void confirmedAbsentIsIdempotentWithoutMutation() throws Exception {
        doReturn(absent).when(adapter).probeRbdDeletion(pool, IMAGE); assertTrue(delete()); verifyNoInteractions(nativePool, volume);
    }
    @Test public void unverifiedPresenceNeverDeletes() throws Exception {
        doReturn(unknown).when(adapter).probeRbdDeletion(pool, IMAGE); fails("RBD_LOOKUP_UNKNOWN"); verifyNoInteractions(nativePool, volume);
    }
    @Test public void misleadingEnoentCannotHideAnExistingImage() throws Exception {
        doReturn(present).when(adapter).probeRbdDeletion(pool, IMAGE); doThrow(error(org.libvirt.Error.ErrorNumber.VIR_ERR_SYSTEM_ERROR)).when(volume).delete(0);
        fails("IMAGE_PRESENT_DELETE_FAILED"); verify(volume).free();
    }
    @Test public void successfulLibvirtDeleteWithImageStillPresentFails() throws Exception {
        doReturn(present).when(adapter).probeRbdDeletion(pool, IMAGE); fails("IMAGE_PRESENT_AFTER_DELETE"); verify(volume).free();
    }
    @Test public void successfulDeleteWithFailedPostReadIsUnconfirmed() throws Exception {
        doReturn(present, unknown).when(adapter).probeRbdDeletion(pool, IMAGE); fails("RBD_LOOKUP_UNKNOWN"); verify(volume).free();
    }
    @Test public void failedDeleteWithVerifiedAbsenceIsIdempotent() throws Exception {
        doThrow(error(org.libvirt.Error.ErrorNumber.VIR_ERR_SYSTEM_ERROR)).when(volume).delete(0); assertTrue(delete()); verify(volume).free();
    }
    @Test public void absenceOnAnotherClusterCannotAcknowledgeDeletion() {
        RbdDeletionProbe.Result other = new RbdDeletionProbe.Result(RbdDeletionProbe.State.ABSENT_CONFIRMED, "OPEN_IMAGE", -2, "other-fsid", 3, -1, null);
        doReturn(present, other).when(adapter).probeRbdDeletion(pool, IMAGE); fails("RBD_TARGET_CHANGED");
    }
    @Test public void missingLibvirtEntryRefreshesOnceAfterRbdPresenceEvidence() throws Exception {
        LibvirtException missing = error(org.libvirt.Error.ErrorNumber.VIR_ERR_NO_STORAGE_VOL);
        when(nativePool.storageVolLookupByName(IMAGE)).thenThrow(missing).thenReturn(volume);
        assertTrue(delete()); verify(nativePool).refresh(0); verify(volume).delete(0);
    }
    @Test public void persistentLookupMismatchDoesNotBecomeSuccess() throws Exception {
        doReturn(present).when(adapter).probeRbdDeletion(pool, IMAGE);
        LibvirtException missing = error(org.libvirt.Error.ErrorNumber.VIR_ERR_NO_STORAGE_VOL);
        when(nativePool.storageVolLookupByName(IMAGE)).thenThrow(missing);
        fails("IMAGE_PRESENT_DELETE_FAILED"); verify(nativePool).refresh(0); verify(volume, never()).delete(0);
    }
    @Test public void lookupAuthFailureDoesNotRefreshOrSucceed() throws Exception {
        doReturn(present).when(adapter).probeRbdDeletion(pool, IMAGE);
        LibvirtException denied = error(org.libvirt.Error.ErrorNumber.VIR_ERR_AUTH_FAILED);
        when(nativePool.storageVolLookupByName(IMAGE)).thenThrow(denied);
        fails("IMAGE_PRESENT_DELETE_FAILED"); verify(nativePool, never()).refresh(0); verify(volume, never()).delete(0);
    }
    @Test public void nullLookupWithPresentImageCannotSucceed() throws Exception {
        doReturn(present).when(adapter).probeRbdDeletion(pool, IMAGE); when(nativePool.storageVolLookupByName(IMAGE)).thenReturn(null);
        fails("IMAGE_PRESENT_DELETE_FAILED"); verify(volume, never()).delete(0);
    }
    @Test public void snapshotReferencesFollowExistingPreparationContract() throws Exception {
        RbdDeletionProbe.Result snapshots = new RbdDeletionProbe.Result(RbdDeletionProbe.State.PRESENT, "READ_IMAGE", 0, "fsid", 3, 1, "image-id");
        doReturn(snapshots, absent).when(adapter).probeRbdDeletion(pool, IMAGE); doNothing().when(adapter).prepareRbdDeletionSnapshots(pool, IMAGE);
        assertTrue(delete()); var order = inOrder(adapter, volume); order.verify(adapter).prepareRbdDeletionSnapshots(pool, IMAGE); order.verify(volume).delete(0);
    }
    @Test public void protectedChildFailurePreservesVolumeAndReportsEvidence() throws Exception {
        RbdDeletionProbe.Result snapshots = new RbdDeletionProbe.Result(RbdDeletionProbe.State.PRESENT, "READ_IMAGE", 0, "fsid", 3, 1, "image-id");
        doReturn(snapshots).when(adapter).probeRbdDeletion(pool, IMAGE); doThrow(new CloudRuntimeException("child reference")).when(adapter).prepareRbdDeletionSnapshots(pool, IMAGE);
        fails("IMAGE_PRESENT_DELETE_FAILED"); verify(volume, never()).delete(0);
    }
    @Test public void sharedMountPointDeletionRetainsLegacyPath() throws Exception {
        when(pool.getType()).thenReturn(Storage.StoragePoolType.SharedMountPoint); assertTrue(delete());
        verify(volume).delete(0); verify(adapter, never()).probeRbdDeletion(any(), anyString());
    }

    private Answer gateway(Storage.StoragePoolType type, boolean deleted, boolean throwsError) {
        KVMStoragePoolManager manager = mock(KVMStoragePoolManager.class); when(pool.getType()).thenReturn(type);
        when(manager.getStoragePool(type, POOL)).thenReturn(pool);
        if (throwsError) { when(pool.deletePhysicalDisk(IMAGE, Storage.ImageFormat.RAW)).thenThrow(new CloudRuntimeException("RBD_LOOKUP_UNKNOWN")); }
        else { when(pool.deletePhysicalDisk(IMAGE, Storage.ImageFormat.RAW)).thenReturn(deleted); }
        VolumeObjectTO v = new VolumeObjectTO(); v.setPath(IMAGE); v.setFormat(Storage.ImageFormat.RAW);
        PrimaryDataStoreTO store = mock(PrimaryDataStoreTO.class); when(store.getPoolType()).thenReturn(type); when(store.getUuid()).thenReturn(POOL); v.setDataStore(store);
        return new KVMStorageProcessor(manager, null).deleteVolume(new DeleteCommand(v));
    }
    @Test public void processorRbdPathNeverUsesCatchAllPhysicalLookup() throws Exception {
        assertTrue(gateway(Storage.StoragePoolType.RBD, true, false).getResult()); verify(pool, never()).getPhysicalDisk(anyString());
    }
    @Test public void processorPreservesUnknownRbdFailure() { assertFalse(gateway(Storage.StoragePoolType.RBD, false, true).getResult()); }
    @Test public void processorDoesNotIgnoreRbdFalseReturn() { assertFalse(gateway(Storage.StoragePoolType.RBD, false, false).getResult()); }
}
