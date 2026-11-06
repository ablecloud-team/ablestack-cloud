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

import java.util.Map;
import com.cloud.storage.dao.VolumeDetailsDao;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class VolumeExportGuardTest {
    private VolumeApiServiceImpl service;
    private VolumeVO volume;
    private VolumeDetailsDao details;
    private VMInstanceDao vms;
    private VMInstanceDetailsDao vmDetails;
    @Before public void setup() {
        service=new VolumeApiServiceImpl(); volume=mock(VolumeVO.class); details=mock(VolumeDetailsDao.class); vms=mock(VMInstanceDao.class); vmDetails=mock(VMInstanceDetailsDao.class);
        ReflectionTestUtils.setField(service,"_volsDetailsDao",details); ReflectionTestUtils.setField(service,"_vmInstanceDao",vms); ReflectionTestUtils.setField(service,"vmInstanceDetailsDao",vmDetails);
        when(volume.getPassphraseId()).thenReturn(null); when(volume.getKmsKeyId()).thenReturn(null); when(volume.getInstanceId()).thenReturn(null);
        when(volume.getId()).thenReturn(1L); when(volume.getPoolId()).thenReturn(1L); when(volume.getState()).thenReturn(Volume.State.Ready); when(details.listDetailsKeyPairs(1L)).thenReturn(Map.of());
    }
    @Test public void detachedReadyAllowed() { service.validateExtractVolumeState(volume); }
    @Test public void stoppedAttachedAllowed() { stopped(); service.validateExtractVolumeState(volume); }
    private VMInstanceVO stopped() { when(volume.getInstanceId()).thenReturn(2L); VMInstanceVO vm=mock(VMInstanceVO.class); when(vm.getId()).thenReturn(2L); when(vm.getState()).thenReturn(VirtualMachine.State.Stopped); when(vms.findById(2L)).thenReturn(vm); return vm; }
    @Test(expected=com.cloud.exception.PermissionDeniedException.class) public void runningBlockedAfterQueueDelay() { VMInstanceVO vm=stopped(); when(vm.getState()).thenReturn(VirtualMachine.State.Running); service.validateExtractVolumeState(volume); }
    @Test(expected=com.cloud.exception.InvalidParameterValueException.class) public void encryptionBlockedAfterQueueDelay() { when(volume.getPassphraseId()).thenReturn(1L); service.validateExtractVolumeState(volume); }
    @Test(expected=com.cloud.exception.InvalidParameterValueException.class) public void kmsBlockedAfterQueueDelay() { when(volume.getKmsKeyId()).thenReturn(1L); service.validateExtractVolumeState(volume); }
    @Test(expected=com.cloud.exception.InvalidParameterValueException.class) public void busyBlocked() { when(volume.getState()).thenReturn(Volume.State.Migrating); service.validateExtractVolumeState(volume); }
    @Test(expected=com.cloud.exception.InvalidParameterValueException.class) public void flattenBlocked() { when(details.listDetailsKeyPairs(1L)).thenReturn(Map.of("clone.fast.flatten.status","running")); service.validateExtractVolumeState(volume); }
    @Test(expected=com.cloud.exception.InvalidParameterValueException.class) public void faultProtectedSourceBlocked() { stopped(); when(vmDetails.listDetailsKeyPairs(2L)).thenReturn(Map.of("ftctl.role","primary")); service.validateExtractVolumeState(volume); }
    @Test public void staleExportDeletesUrlAndImageWithoutPrimaryExpunge() {
        var store=mock(org.apache.cloudstack.storage.image.datastore.ImageStoreEntity.class);
        var image=mock(org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo.class);
        var refs=mock(org.apache.cloudstack.storage.datastore.db.VolumeDataStoreDao.class);
        var ref=new org.apache.cloudstack.storage.datastore.db.VolumeDataStoreVO(); ReflectionTestUtils.setField(ref,"id",10L);
        when(store.getId()).thenReturn(3L); when(store.getRole()).thenReturn(DataStoreRole.Image);
        when(image.getDataStore()).thenReturn(store); when(image.getId()).thenReturn(1L); when(image.getAccountId()).thenReturn(2L);
        ref.setInstallPath("volumes/2/1/image.export-v1.qcow2"); ref.setExtractUrl("http://export/url");
        when(refs.findByStoreVolume(3L,1L)).thenReturn(ref); ReflectionTestUtils.setField(service,"_volumeStoreDao",refs);
        service.cleanupVolumeExport(image);
        verify(store).deleteExtractUrl(ref.getInstallPath(),ref.getExtractUrl(),Upload.Type.VOLUME);
        verify(refs).expunge(ref.getId()); verifyNoInteractions(vms,details);
    }
    @Test(expected=com.cloud.utils.exception.CloudRuntimeException.class) public void primaryCannotUseExportCleanup() {
        var store=mock(org.apache.cloudstack.engine.subsystem.api.storage.DataStore.class);
        var image=mock(org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo.class);
        when(image.getDataStore()).thenReturn(store); when(store.getRole()).thenReturn(DataStoreRole.Primary);
        service.cleanupVolumeExport(image);
    }
}
