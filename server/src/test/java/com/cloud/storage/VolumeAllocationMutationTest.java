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
import java.time.Instant;
import org.apache.cloudstack.resourcedetail.dao.DiskOfferingDetailsDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.storage.dao.VolumeDetailsDao;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.exception.InvalidParameterValueException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.InjectMocks;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;

@RunWith(MockitoJUnitRunner.Silent.class)
public class VolumeAllocationMutationTest {
    @InjectMocks private VolumeApiServiceImpl service;
    @Mock private VolumeDao volumes;
    @Mock private VolumeDetailsDao details;
    @Mock private DiskOfferingDetailsDao offerings;
    @Mock private DiskOfferingDao diskOfferings;
    @Mock private DiskOfferingVO target;
    @Mock private VolumeVO volume;
    @Before public void setup() {
        when(volumes.findById(1L)).thenReturn(volume); when(volume.getId()).thenReturn(1L);
        when(volume.getSize()).thenReturn(1024L); when(volume.getFormat()).thenReturn(Storage.ImageFormat.RAW);
        when(volume.getProvisioningType()).thenReturn(Storage.ProvisioningType.FAT);
        when(diskOfferings.findById(2L)).thenReturn(target); when(target.getProvisioningType()).thenReturn(Storage.ProvisioningType.FAT);
    }
    private void validate(Long targetId) { ReflectionTestUtils.invokeMethod(service, "validateAllocationMutation", 1L, targetId, "resize"); }
    private void size(Long targetId, long expected, long requested) { ReflectionTestUtils.invokeMethod(service, "validateAllocationMutationSize", 1L, targetId, expected, requested); }
    private Map<String,String> verified() { return VolumeAllocationPolicy.evidence(VolumeAllocationPolicy.request("full","raw",null),"RBD","create","rbd-thick-create",1024L,Instant.now(),"test"); }
    @Test(expected = InvalidParameterValueException.class) public void fullResizeStopsBeforeMutation() { when(details.listDetailsKeyPairs(1L)).thenReturn(verified()); size(null,1024L,2048L); }
    @Test(expected = InvalidParameterValueException.class) public void explicitThinLifecycleAlsoNeedsQualification() { when(details.listDetailsKeyPairs(1L)).thenReturn(VolumeAllocationPolicy.request("off", "raw", null)); size(null,1024L,2048L); }
    @Test(expected = InvalidParameterValueException.class) public void legacyVolumeCannotAcquireFullByOfferingChange() { when(details.listDetailsKeyPairs(1L)).thenReturn(Map.of()); when(offerings.listDetailsKeyPairs(2L)).thenReturn(VolumeAllocationPolicy.request("full", "raw", null)); validate(2L); }
    @Test public void legacyPathRetainsExistingValidationAndDoesNotWriteEvidence() { when(details.listDetailsKeyPairs(1L)).thenReturn(Map.of()); size(null,1024L,2048L); verify(volumes,never()).update(anyLong(),any()); verifyNoInteractions(offerings); }
    @Test public void sameExplicitPolicyOfferingChangesOnlyMetadata() { when(details.listDetailsKeyPairs(1L)).thenReturn(verified()); when(offerings.listDetailsKeyPairs(2L)).thenReturn(VolumeAllocationPolicy.request("full","raw",null)); size(2L,1024L,1024L); verify(volumes,never()).update(anyLong(),any()); }
    @Test(expected = InvalidParameterValueException.class) public void staleQueuedVolumeSizeIsRejectedBeforeAnyAction() { when(details.listDetailsKeyPairs(1L)).thenReturn(verified()); size(null,512L,1024L); }
    @Test(expected = InvalidParameterValueException.class) public void missingCreationEvidenceCannotAuthorizeQosOnlyChange() { when(details.listDetailsKeyPairs(1L)).thenReturn(VolumeAllocationPolicy.request("full","raw",null)); size(null,1024L,1024L); }
    @Test(expected = InvalidParameterValueException.class) public void offeringPolicyChangedBetweenEnqueueAndExecutionIsRejected() { when(details.listDetailsKeyPairs(1L)).thenReturn(verified()); when(offerings.listDetailsKeyPairs(2L)).thenReturn(VolumeAllocationPolicy.request("off","raw",null)); size(2L,1024L,1024L); }
    @Test public void explicitQosOnlyChangeNeverDispatchesImageResize() {
        when(details.listDetailsKeyPairs(1L)).thenReturn(verified());
        org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo info=mock(org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo.class);
        org.apache.cloudstack.engine.subsystem.api.storage.VolumeService engine=mock(org.apache.cloudstack.engine.subsystem.api.storage.VolumeService.class);
        when(info.getId()).thenReturn(1L); when(info.getSize()).thenReturn(1024L);
        when(info.getpayload()).thenReturn(new ResizeVolumePayload(1024L,10L,20L,null,false,"none",null,false));
        Object result=ReflectionTestUtils.invokeMethod(service,"resizeWithAllocationGuard",engine,info);
        assertNotNull(result); verifyNoInteractions(engine); verify(volumes,never()).update(anyLong(),any());
    }
    @Test public void legacyThinResizeStillDispatchesEstablishedBackendPath() {
        when(details.listDetailsKeyPairs(1L)).thenReturn(Map.of());
        org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo info=mock(org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo.class);
        when(info.getId()).thenReturn(1L);
        org.apache.cloudstack.engine.subsystem.api.storage.VolumeService engine=mock(org.apache.cloudstack.engine.subsystem.api.storage.VolumeService.class);
        ReflectionTestUtils.invokeMethod(service,"resizeWithAllocationGuard",engine,info);
        verify(engine).resize(info);
    }
    @Test(expected=InvalidParameterValueException.class) public void directUploadCannotClaimExplicitPolicy() {
        when(offerings.listDetailsKeyPairs(2L)).thenReturn(VolumeAllocationPolicy.request("full","raw",null));
        ReflectionTestUtils.invokeMethod(service,"validateAllocationUploadOffering",2L);
    }
    @Test(expected=InvalidParameterValueException.class) public void administrativePathChangeCannotTransferOldEvidenceToAnotherImage() {
        when(details.listDetailsKeyPairs(1L)).thenReturn(verified());
        ReflectionTestUtils.invokeMethod(service,"validateAllocationIdentityMutation",1L,"another-image",null,null,null,null);
    }
    @Test public void ordinaryNameOrDeleteProtectionEditDoesNotNeedToRewriteAllocation() {
        when(details.listDetailsKeyPairs(1L)).thenReturn(verified());
        ReflectionTestUtils.invokeMethod(service,"validateAllocationIdentityMutation",1L,null,null,null,null,null);
        verifyNoInteractions(details);
    }

}
