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

import java.time.Instant;
import java.util.Map;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import com.cloud.storage.Storage;
import com.cloud.storage.VolumeAllocationPolicy;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDetailsDao;
import com.cloud.utils.exception.CloudRuntimeException;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyBoolean;

public class VolumeAllocationResultTest {
    private VolumeObject object;
    private VolumeVO volume;
    private VolumeDetailsDao details;
    private VolumeObjectTO result;
    private Map<String, String> intent;
    @Before public void setup() {
        object = new VolumeObject(); details = mock(VolumeDetailsDao.class); object.volumeDetailsDao = details;
        object.dataStore = mock(DataStore.class); when(object.dataStore.getUuid()).thenReturn("pool");
        volume = mock(VolumeVO.class); when(volume.getId()).thenReturn(1L); when(volume.getUuid()).thenReturn("volume"); when(volume.getSize()).thenReturn(67108864L);
        intent = VolumeAllocationPolicy.request("full", "raw", null); when(details.listDetailsKeyPairs(1L)).thenReturn(intent);
        result = new VolumeObjectTO(); result.setPath("volume"); result.setSize(67108864L); result.setFormat(Storage.ImageFormat.RAW);
        PrimaryDataStoreTO pool = mock(PrimaryDataStoreTO.class); when(pool.getUuid()).thenReturn("pool"); result.setDataStore(pool);
        result.setAllocation(VolumeAllocationPolicy.evidence(intent, "RBD", "create", "rbd-thick-create", 67108864L, Instant.now(), "rbd-du-exact-image-used-size"));
    }
    private void reject() {
        try { object.validateAllocationResult(result, volume); fail("Unverified Agent result must not update path/Ready/evidence"); }
        catch (CloudRuntimeException expected) { assertTrue(expected.getMessage().contains("ALLOCATION_EVIDENCE_INVALID")); }
        verify(details, never()).addDetail(anyLong(), anyString(), anyString(), anyBoolean());
    }
    @Test public void completeReceiptIsPersistedWithFrozenIntent() { object.validateAllocationResult(result, volume); verify(details).addDetail(1L, VolumeAllocationPolicy.FIXED_POLICY, "full", false); }
    @Test public void oldAgentIgnoringContractCannotReportSuccess() { result.setAllocation(Map.of()); reject(); }
    @Test public void wrongPoolCannotReportSuccess() { PrimaryDataStoreTO other = mock(PrimaryDataStoreTO.class); when(other.getUuid()).thenReturn("foreign"); result.setDataStore(other); reject(); }
    @Test public void wrongImageCannotReportSuccess() { result.setPath("other"); reject(); }
    @Test public void wrongSizeCannotReportSuccess() { result.setSize(33554432L); reject(); }
    @Test public void wrongFormatCannotReportSuccess() { result.setFormat(Storage.ImageFormat.QCOW2); reject(); }
    @Test public void sparseResultCannotMasqueradeAsFull() { Map<String,String> evidence = result.getAllocation(); evidence.put(VolumeAllocationPolicy.OBSERVED_BYTES, "0"); result.setAllocation(evidence); reject(); }
    @Test public void legacyColumnCannotReplaceFrozenIntent() { result.setAllocation(VolumeAllocationPolicy.evidence(VolumeAllocationPolicy.request("off", "raw", null), "RBD", "create", "rbd-thin", 0L, Instant.now(), "rbd-du-exact-image-used-size")); reject(); }
    @Test public void unqualifiedLifecycleReceiptIsRejected() { Map<String,String> evidence = result.getAllocation(); evidence.put(VolumeAllocationPolicy.OPERATION, "resize"); result.setAllocation(evidence); reject(); }
    @Test public void observationSourceIsExplicitAndImageScoped() { Map<String,String> evidence = result.getAllocation(); evidence.put(VolumeAllocationPolicy.OBSERVED_SOURCE, "cluster physical usage"); result.setAllocation(evidence); reject(); }
    @Test public void genericMetadataCannotSmuggleExtraAppliedFields() { Map<String,String> evidence = result.getAllocation(); evidence.put("allocation.fake", "full"); result.setAllocation(evidence); reject(); }
    @Test public void legacyResultsDoNotBackfillAllocation() { when(details.listDetailsKeyPairs(1L)).thenReturn(Map.of()); object.validateAllocationResult(null, volume); verify(details, never()).addDetail(anyLong(), anyString(), anyString(), anyBoolean()); }
    private void gfs(String policy, String format, long observed) {
        object.dataStore = mock(org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStore.class);
        when(object.dataStore.getUuid()).thenReturn("pool");
        when(((org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStore) object.dataStore).getPoolType()).thenReturn(Storage.StoragePoolType.SharedMountPoint);
        intent = VolumeAllocationPolicy.request(policy, format, null); when(details.listDetailsKeyPairs(1L)).thenReturn(intent);
        result.setFormat(Storage.ImageFormat.valueOf(format.toUpperCase(java.util.Locale.ROOT)));
        result.setAllocation(VolumeAllocationPolicy.evidence(intent, "GFS2", "copy", "qemu-preallocation-"+policy, observed, Instant.now(), "stat-st-blocks-times-512"));
    }
    @Test public void gfsMetadataAndRawOffReceiptsPersistWithoutClaimingFull() {
        gfs("metadata", "qcow2", 1048576); object.validateAllocationResult(result, volume);
        verify(details).addDetail(1L, VolumeAllocationPolicy.FIXED_POLICY, "metadata", false);
    }
    @Test public void gfsFullReceiptPersistsRequestedQcow2Format() {
        gfs("full", "qcow2", 68157440); object.validateAllocationResult(result, volume);
        verify(details).addDetail(1L, VolumeAllocationPolicy.FORMAT, "qcow2", false);
    }
    @Test public void gfsFallocCannotReportSparseSuccess() { gfs("falloc", "raw", 0); reject(); }
    @Test public void gfsCannotSupplyRbdReceiptForSamePoolUuid() { gfs("full", "raw", 67108864); result.setAllocation(VolumeAllocationPolicy.evidence(intent, "RBD", "create", "rbd-thick-create", 67108864L, Instant.now(), "rbd-du-exact-image-used-size")); reject(); }
    @Test public void gfsCannotReturnDifferentActualFormat() { gfs("full", "qcow2", 67108864); result.setFormat(Storage.ImageFormat.RAW); reject(); }

}
