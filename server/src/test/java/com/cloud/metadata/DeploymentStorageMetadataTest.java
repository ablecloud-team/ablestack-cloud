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
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.test.util.ReflectionTestUtils;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.server.ResourceTag.ResourceObjectType;
import com.cloud.storage.VmStorageSelectionService;
import com.cloud.storage.VolumeDetailVO;
import com.cloud.storage.dao.VolumeDetailsDao;
import com.cloud.server.ResourceManagerUtil;

public class DeploymentStorageMetadataTest {
    @Test public void metadataApiCannotForgeStorageSelection() {
        ResourceMetaDataManagerImpl manager = new ResourceMetaDataManagerImpl();
        assertThrows(InvalidParameterValueException.class, () -> manager.addResourceMetaData("volume", ResourceObjectType.Volume,
            Collections.singletonMap(VmStorageSelectionService.REQUIRED_POOL.toUpperCase(), "pool"), false));
    }
    @Test public void metadataApiCannotDeleteStorageSelection() {
        ResourceMetaDataManagerImpl manager = new ResourceMetaDataManagerImpl();
        assertThrows(InvalidParameterValueException.class, () -> manager.deleteResourceMetaData("volume", ResourceObjectType.Volume,
            VmStorageSelectionService.REQUIRED_POOL.toUpperCase()));
    }
    @Test public void bulkMetadataRemovalCannotSilentlyEnableFallbackPlacement() {
        ResourceMetaDataManagerImpl manager = new ResourceMetaDataManagerImpl();
        ResourceManagerUtil resources = mock(ResourceManagerUtil.class);
        VolumeDetailsDao details = mock(VolumeDetailsDao.class);
        ReflectionTestUtils.setField(manager, "resourceManagerUtil", resources);
        ReflectionTestUtils.setField(manager, "_volumeDetailDao", details);
        when(resources.getResourceId("volume", ResourceObjectType.Volume)).thenReturn(4L);
        when(details.findDetail(4L, VmStorageSelectionService.REQUIRED_POOL)).thenReturn(new VolumeDetailVO(4L, VmStorageSelectionService.REQUIRED_POOL, "pool", false));
        assertThrows(InvalidParameterValueException.class, () -> manager.deleteResourceMetaData("volume", ResourceObjectType.Volume, null));
        verify(details, never()).removeDetails(anyLong());
    }
}
