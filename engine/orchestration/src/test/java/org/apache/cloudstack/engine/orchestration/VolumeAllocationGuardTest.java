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
package org.apache.cloudstack.engine.orchestration;

import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.apache.cloudstack.resourcedetail.dao.DiskOfferingDetailsDao;
import com.cloud.offering.DiskOffering;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeAllocationPolicy;
import com.cloud.vm.VirtualMachine;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.exception.InvalidParameterValueException;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.Silent.class)
public class VolumeAllocationGuardTest {
    @InjectMocks private VolumeOrchestrator orchestrator;
    @Mock private DiskOfferingDetailsDao detailsDao;
    @Mock private DiskOffering offering;
    @Mock private Volume volume;
    @Mock private VirtualMachine vm;
    @Mock private com.cloud.storage.dao.VolumeDetailsDao volumeDetails;
    @Mock private com.cloud.storage.dao.VolumeDao _volsDao;
    @Before public void setUp() {
        when(offering.getId()).thenReturn(1L); when(volume.getDiskOfferingId()).thenReturn(1L);
        when(detailsDao.listDetailsKeyPairs(1L)).thenReturn(VolumeAllocationPolicy.request("falloc", "qcow2", null));
    }
    private void rejected(Runnable action) {
        try { action.run(); fail("Explicit intent must not reach a legacy adapter"); }
        catch (InvalidParameterValueException expected) { assertTrue(expected.getMessage().contains("ALLOCATION_EXECUTION_NOT_IMPLEMENTED")); }
    }
    @Test public void gfsQcow2CreationFreezesRequestedPolicyBeforeAgentDispatch() {
        when(volumeDetails.listDetailsKeyPairs(2L)).thenReturn(Map.of());
        orchestrator.saveVolumeDetails(1L, 2L);
        org.mockito.Mockito.verify(volumeDetails).addDetail(2L, VolumeAllocationPolicy.POLICY, "falloc", false);
        org.mockito.Mockito.verify(volumeDetails).addDetail(2L, VolumeAllocationPolicy.FORMAT, "qcow2", false);
    }
    @Test public void rawCreationKeepsItsExplicitFormatInsteadOfKvmDefaultQcow2() {
        when(volumeDetails.listDetailsKeyPairs(2L)).thenReturn(Map.of());
        when(detailsDao.listDetailsKeyPairs(1L)).thenReturn(VolumeAllocationPolicy.request("full", "raw", null));
        orchestrator.saveVolumeDetails(1L, 2L);
        org.mockito.Mockito.verify(volumeDetails).addDetail(2L, VolumeAllocationPolicy.FORMAT, "raw", false);
    }
    @Test public void volumeImportDoesNotAssertNewAllocationWithoutEvidence() {
        rejected(() -> orchestrator.importVolume(Volume.Type.DATADISK, "owned", offering, 1L, null, null, 1L, HypervisorType.KVM, null, null, null, 1L, 1L, null, "owned", null));
    }
    @Test public void duplicationCannotDropTheExplicitContract() {
        rejected(() -> orchestrator.allocateDuplicateVolumeVO(volume, offering, null));
    }
    @Test(expected = InvalidParameterValueException.class) public void offeringChangeCannotOverwriteFrozenIntent() {
        when(volumeDetails.listDetailsKeyPairs(2L)).thenReturn(VolumeAllocationPolicy.request("full","qcow2",null));
        orchestrator.saveVolumeDetails(1L,2L);
    }
    @Test(expected = InvalidParameterValueException.class) public void readyLegacyImageCannotAcquireExplicitIntent() {
        com.cloud.storage.VolumeVO existing=org.mockito.Mockito.mock(com.cloud.storage.VolumeVO.class);
        when(existing.getState()).thenReturn(Volume.State.Ready);
        when(_volsDao.findById(2L)).thenReturn(existing);
        orchestrator.saveVolumeDetails(1L,2L);
    }
    @Test(expected = InvalidParameterValueException.class) public void duplicateUsesFrozenSourceEvenWhenOfferingIsLegacy() {
        when(detailsDao.listDetailsKeyPairs(1L)).thenReturn(Map.of());
        when(volumeDetails.listDetailsKeyPairs(0L)).thenReturn(VolumeAllocationPolicy.request("full","raw",null));
        orchestrator.allocateDuplicateVolumeVO(volume,null,null);
    }
    @Test(expected = InvalidParameterValueException.class) public void migrationCannotBypassTheApiGuard() throws Exception {
        when(volumeDetails.listDetailsKeyPairs(0L)).thenReturn(VolumeAllocationPolicy.request("full","raw",null));
        orchestrator.migrateVolume(volume,null);
    }

}
