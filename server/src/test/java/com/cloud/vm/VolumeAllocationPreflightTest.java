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
package com.cloud.vm;

import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;
import org.apache.cloudstack.resourcedetail.dao.DiskOfferingDetailsDao;
import com.cloud.offering.DiskOffering;
import com.cloud.storage.VolumeApiService;
import com.cloud.storage.VolumeAllocationPolicy;
import com.cloud.exception.InvalidParameterValueException;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

@RunWith(MockitoJUnitRunner.Silent.class)
public class VolumeAllocationPreflightTest {
    @InjectMocks private UserVmManagerImpl manager;
    @Mock private DiskOfferingDetailsDao detailsDao;
    @Mock private DiskOffering offering;
    @Mock private VolumeApiService volumeService;
    @Mock private com.cloud.storage.VolumeAllocationService allocationService;
    @Mock private com.cloud.storage.dao.VolumeDao volumeDao;
    @Mock private com.cloud.storage.dao.VolumeDetailsDao volumeDetails;
    @Before public void setUp() {
        when(offering.getId()).thenReturn(1L);
        when(offering.getDiskSize()).thenReturn(1073741824L);
    }
    private void reject(Map<String, String> details) {
        when(detailsDao.listDetailsKeyPairs(1L)).thenReturn(details);
        try {
            com.cloud.dc.DataCenter zone = org.mockito.Mockito.mock(com.cloud.dc.DataCenter.class); when(zone.getId()).thenReturn(10L);
            org.mockito.Mockito.doThrow(new InvalidParameterValueException("ALLOCATION_AGENT_UNAVAILABLE: unqualified host"))
                    .when(allocationService).validateCreate(org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(10L), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.any());
            ReflectionTestUtils.invokeMethod(manager, "validateAllocationOffering", offering, zone);
            fail("VM must be rejected before allocating VM/NIC/volume records");
        } catch (InvalidParameterValueException expected) {
            assertTrue(expected.getMessage().contains("ALLOCATION_AGENT_UNAVAILABLE"));
        }
        verifyNoInteractions(volumeService);
    }
    @Test public void computeOnlyRootIsRejectedInPreflight() {
        when(offering.isComputeOnly()).thenReturn(true);
        reject(VolumeAllocationPolicy.request("falloc", "qcow2", null));
    }
    @Test public void linkedCustomRootIsRejectedInPreflight() {
        when(offering.isCustomized()).thenReturn(true);
        reject(VolumeAllocationPolicy.request("falloc", "raw", null));
    }
    @Test public void dataOfferingIsRejectedInPreflight() {
        reject(VolumeAllocationPolicy.request("metadata", "qcow2", null));
    }
    @Test public void legacySizeValidationKeepsItsExistingMeaning() {
        when(detailsDao.listDetailsKeyPairs(1L)).thenReturn(Map.of());
        Long size = ReflectionTestUtils.invokeMethod(manager, "verifyAndGetDiskSize", offering, null);
        assertEquals(Long.valueOf(1073741824L), size);
    }
    @Test public void rawFullSyntaxCanProceedOnlyToBackendCapabilityValidation() {
        when(detailsDao.listDetailsKeyPairs(1L)).thenReturn(VolumeAllocationPolicy.request("full", "raw", null));
        com.cloud.dc.DataCenter zone = org.mockito.Mockito.mock(com.cloud.dc.DataCenter.class); when(zone.getId()).thenReturn(10L);
        ReflectionTestUtils.invokeMethod(manager, "validateAllocationOffering", offering, zone);
        org.mockito.Mockito.verify(allocationService).validateCreate(org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(10L), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.any());
        verifyNoInteractions(volumeService);
    }
    @Test(expected = InvalidParameterValueException.class) public void fullVmCloneIsBlockedBeforeMutation() {
        com.cloud.storage.VolumeVO volume = org.mockito.Mockito.mock(com.cloud.storage.VolumeVO.class); when(volume.getId()).thenReturn(2L);
        when(volumeDao.findByInstance(3L)).thenReturn(java.util.List.of(volume));
        when(volumeDetails.listDetailsKeyPairs(2L)).thenReturn(VolumeAllocationPolicy.request("full", "raw", null));
        ReflectionTestUtils.invokeMethod(manager, "requireUnqualifiedVmAllocation", 3L, "VM clone");
    }

    @Test(expected = InvalidParameterValueException.class) public void fullVmStorageMigrationIsBlockedAtEntry() throws Exception {
        com.cloud.storage.VolumeVO volume = org.mockito.Mockito.mock(com.cloud.storage.VolumeVO.class); when(volume.getId()).thenReturn(2L);
        when(volumeDao.findByInstance(3L)).thenReturn(java.util.List.of(volume));
        when(volumeDetails.listDetailsKeyPairs(2L)).thenReturn(VolumeAllocationPolicy.request("full", "raw", null));
        manager.migrateVirtualMachineWithVolume(3L, null, Map.of());
    }

}
