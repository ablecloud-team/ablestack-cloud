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
package com.cloud.metadata;

import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.apache.cloudstack.resourcedetail.dao.DiskOfferingDetailsDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.storage.VolumeAllocationPolicy;
import com.cloud.server.ResourceManagerUtil;
import com.cloud.storage.dao.VolumeDetailsDao;
import com.cloud.server.ResourceTag.ResourceObjectType;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.anyLong;

@RunWith(MockitoJUnitRunner.Silent.class)
public class VolumeAllocationMetadataTest {
    @InjectMocks private ResourceMetaDataManagerImpl manager;
    @Mock private DiskOfferingDetailsDao diskDao;
    @Mock private VolumeDetailsDao volumeDao;
    @Mock private ResourceManagerUtil resourceManagerUtil;
    @Before public void setUp() throws Exception {
        manager.configure(null, null);
        when(resourceManagerUtil.getResourceId("fixture", ResourceObjectType.DiskOffering)).thenReturn(1L);
        when(resourceManagerUtil.getResourceId("fixture", ResourceObjectType.Volume)).thenReturn(1L);
        when(diskDao.listDetailsKeyPairs(1L)).thenReturn(VolumeAllocationPolicy.request("full", "raw", null));
    }
    private void invalid(Runnable action) {
        try { action.run(); fail("Allocation metadata must be protected"); }
        catch (InvalidParameterValueException expected) { assertTrue(expected.getMessage().contains("Allocation")); }
    }
    @Test public void genericAddsCannotForgeIntentOrEvidence() {
        for (ResourceObjectType type : new ResourceObjectType[] {ResourceObjectType.Volume, ResourceObjectType.DiskOffering, ResourceObjectType.ServiceOffering}) {
            invalid(() -> manager.addResourceMetaData("fixture", type, Map.of("allocation.effective.policy", "full"), true));
        }
    }
    @Test public void removingOnePolicyKeyIsRejected() {
        invalid(() -> manager.deleteResourceMetaData("fixture", ResourceObjectType.DiskOffering, VolumeAllocationPolicy.POLICY));
        verify(diskDao, never()).removeDetails(anyLong());
    }
    @Test public void removingAllOfferingMetadataCannotEraseIntent() {
        invalid(() -> manager.deleteResourceMetaData("fixture", ResourceObjectType.DiskOffering, null));
        verify(diskDao, never()).removeDetails(anyLong());
    }
    @Test public void unrelatedVolumeDetailsCanStillBeRemoved() {
        assertTrue(manager.deleteResourceMetaData("fixture", ResourceObjectType.Volume, "ordinary"));
        verify(volumeDao).removeDetail(1L, "ordinary");
    }
}
