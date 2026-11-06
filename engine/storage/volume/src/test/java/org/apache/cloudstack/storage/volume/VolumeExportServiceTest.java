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
package org.apache.cloudstack.storage.volume;

import java.util.Map;
import java.time.Instant;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreDriver;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeService;
import org.apache.cloudstack.framework.async.AsyncCallFuture;
import com.cloud.storage.DataStoreRole;
import com.cloud.storage.Storage;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeAllocationPolicy;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.storage.dao.VolumeDetailsDao;
import com.cloud.storage.snapshot.SnapshotManager;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class VolumeExportServiceTest {
    private VolumeServiceImpl service;
    private VolumeInfo source;
    private VolumeVO row;
    private PrimaryDataStore primary;
    private DataStore image;
    private VolumeDetailsDao details;
    @Before public void setup() {
        service=spy(new VolumeServiceImpl()); source=mock(VolumeInfo.class); row=mock(VolumeVO.class);
        primary=mock(PrimaryDataStore.class); image=mock(DataStore.class); details=mock(VolumeDetailsDao.class);
        VolumeDao volumes=mock(VolumeDao.class); SnapshotManager snapshots=mock(SnapshotManager.class);
        ReflectionTestUtils.setField(service,"volDao",volumes); ReflectionTestUtils.setField(service,"_volumeDetailsDao",details); ReflectionTestUtils.setField(service,"snapshotMgr",snapshots);
        when(source.getId()).thenReturn(1L); when(row.getId()).thenReturn(1L); when(source.getDataStore()).thenReturn(primary); when(primary.getRole()).thenReturn(DataStoreRole.Primary);
        when(image.getRole()).thenReturn(DataStoreRole.Image); when(source.getState()).thenReturn(Volume.State.Ready);
        when(source.getHypervisorType()).thenReturn(com.cloud.hypervisor.Hypervisor.HypervisorType.KVM); when(primary.getPoolType()).thenReturn(Storage.StoragePoolType.RBD);
        when(row.getFormat()).thenReturn(Storage.ImageFormat.RAW); when(row.getPassphraseId()).thenReturn(null); when(row.getKmsKeyId()).thenReturn(null); when(row.getInstanceId()).thenReturn(null);
        when(volumes.findById(1L)).thenReturn(row); when(snapshots.canOperateOnVolume(source)).thenReturn(true);
        when(details.listDetailsKeyPairs(1L)).thenReturn(VolumeAllocationPolicy.evidence(VolumeAllocationPolicy.request("off","raw",null),"RBD","create","rbd-thin",0L,Instant.now(),"rbd-du-exact-image-used-size"));
        doReturn(new AsyncCallFuture<VolumeService.VolumeApiResult>()).when(service).copyVolumeFromPrimaryToImage(source,image);
    }
    @Test public void verifiedExplicitSourceUsesScopedExport() { assertNotNull(service.exportVolume(source,image)); verify(service).copyVolumeFromPrimaryToImage(source,image); verify(details,never()).removeDetails(anyLong()); }
    @Test public void legacyReadySourceKeepsExport() { when(details.listDetailsKeyPairs(1L)).thenReturn(Map.of()); assertNotNull(service.exportVolume(source,image)); }
    @Test(expected=com.cloud.exception.InvalidParameterValueException.class) public void primaryMigrationCannotUseExportEntry() { service.exportVolume(source,primary); }
    @Test(expected=com.cloud.exception.InvalidParameterValueException.class) public void missingCreationEvidenceRejected() { when(details.listDetailsKeyPairs(1L)).thenReturn(VolumeAllocationPolicy.request("off","raw",null)); service.exportVolume(source,image); }
    @Test(expected=com.cloud.exception.InvalidParameterValueException.class) public void concurrentSnapshotRejected() { ReflectionTestUtils.setField(service,"snapshotMgr",mock(SnapshotManager.class)); service.exportVolume(source,image); }
    @Test(expected=com.cloud.exception.InvalidParameterValueException.class) public void busySourceRejected() { when(source.getState()).thenReturn(Volume.State.Migrating); service.exportVolume(source,image); }
    @Test public void failedExportCleanupDeletesOnlyImageAndReference() throws Exception { checkCleanup(false); }
    @Test public void failedPhysicalDeleteRetainsImageReference() throws Exception { checkCleanup(true); }
    private void checkCleanup(boolean failed) throws Exception {
        VolumeInfo imageVolume=mock(VolumeInfo.class);
        when(imageVolume.getDataStore()).thenReturn(image); when(imageVolume.getId()).thenReturn(1L); when(imageVolume.getAccountId()).thenReturn(2L); when(image.getId()).thenReturn(3L);
        var refs=mock(org.apache.cloudstack.storage.datastore.db.VolumeDataStoreDao.class);
        var ref=new org.apache.cloudstack.storage.datastore.db.VolumeDataStoreVO(); ReflectionTestUtils.setField(ref,"id",10L); ref.setInstallPath("volumes/2/1");
        when(refs.findByStoreVolume(3L,1L)).thenReturn(ref); ReflectionTestUtils.setField(service,"_volumeStoreDao",refs);
        DataStoreDriver driver=mock(DataStoreDriver.class); when(image.getDriver()).thenReturn(driver);
        doAnswer(invocation -> {
            var result=new org.apache.cloudstack.storage.command.CommandResult(); if(failed)result.setResult("injected delete failure");
            ((org.apache.cloudstack.framework.async.AsyncCompletionCallback<org.apache.cloudstack.storage.command.CommandResult>)invocation.getArgument(2)).complete(result); return null;
        }).when(driver).deleteAsync(eq(image),eq(imageVolume),any());
        try { service.cleanupFailedVolumeExport(imageVolume); if(failed)fail("must retain failed delete"); }
        catch(com.cloud.utils.exception.CloudRuntimeException e) { assertTrue(failed); }
        if(failed)verify(refs,never()).expunge(anyLong()); else verify(refs).expunge(ref.getId());
        verifyNoInteractions(service.volDao); verify(source,never()).processEvent(any());
    }
}
