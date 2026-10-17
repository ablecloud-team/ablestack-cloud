// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.cloudstack.storage.dataservice;

import org.junit.Assert;
import org.junit.Test;
import com.cloud.exception.InvalidParameterValueException;
import com.google.gson.JsonParser;

public class StorageNestedSharePathTest {
    private final StorageServiceManagerImpl manager = new StorageServiceManagerImpl();

    @Test public void explicitNestedDirectoryIsIndependentOfVisibleShareName() {
        Assert.assertEquals("/export/share/project-a", manager.resolveNestedSharePath(null, "project", "share/project-a", 7L, true));
        Assert.assertEquals("/export/share/project-a", manager.resolveNestedSharePath(null, "project", "share/project-a", 7L, false));
        Assert.assertEquals("/export/legacy", manager.resolveNestedSharePath(null, "legacy", null, 7L, true));
    }

    @Test public void rejectsTraversalAbsoluteEmptyAndAmbiguousPaths() {
        for (String path : new String[] {"", "/share", "../share", "share/../other", "share/./other", "share\\other", "share/with space"}) {
            Assert.assertThrows(path, InvalidParameterValueException.class, () -> manager.normalizeRelativeSharePath(path));
        }
        Assert.assertThrows(InvalidParameterValueException.class, () -> manager.resolveNestedSharePath(null, "child", "parent/child", null, true));
        Assert.assertThrows(InvalidParameterValueException.class, () -> manager.resolveNestedSharePath("/export/other", "child", "parent/child", 7L, true));
    }

    @Test public void pathChangeDiscardsStalePhysicalObservationButKeepsPolicy() {
        String config = manager.storeRelativeSharePath("{\"readOnly\":true,\"backingPath\":\"/old\",\"lastInspection\":{}}", "share/child");
        com.google.gson.JsonObject parsed = new JsonParser().parse(config).getAsJsonObject();
        Assert.assertTrue(parsed.get("readOnly").getAsBoolean());
        Assert.assertEquals("share/child", parsed.get("relativeSharePath").getAsString());
        Assert.assertFalse(parsed.has("backingPath"));
        Assert.assertFalse(parsed.has("lastInspection"));
    }
    @Test public void rejectsParentDeletionWhileSameVolumeCrossProtocolChildExists() {
        org.apache.cloudstack.storage.dataservice.dao.StorageFileShareDao dao = org.mockito.Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageFileShareDao.class);
        org.springframework.test.util.ReflectionTestUtils.setField(manager, "storageFileShareDao", dao);
        StorageServiceInstanceVO instance = org.mockito.Mockito.mock(StorageServiceInstanceVO.class);
        org.mockito.Mockito.when(instance.getId()).thenReturn(7L);
        StorageFileShareVO parent = org.mockito.Mockito.mock(StorageFileShareVO.class);
        StorageFileShareVO child = org.mockito.Mockito.mock(StorageFileShareVO.class);
        org.mockito.Mockito.when(parent.getId()).thenReturn(1L);
        org.mockito.Mockito.when(child.getId()).thenReturn(2L);
        org.mockito.Mockito.when(parent.getVolumeId()).thenReturn(10L);
        org.mockito.Mockito.when(child.getVolumeId()).thenReturn(10L);
        org.mockito.Mockito.when(parent.getPath()).thenReturn("/export/parent");
        org.mockito.Mockito.when(child.getPath()).thenReturn("/export/export/parent/child");
        org.mockito.Mockito.when(child.getConfigJson()).thenReturn("{\"relativeSharePath\":\"export/parent/child\"}");
        org.mockito.Mockito.when(dao.listByInstanceIdAndProtocol(7L, StorageServiceInstance.Protocol.NFS)).thenReturn(java.util.Collections.singletonList(parent));
        org.mockito.Mockito.when(dao.listByInstanceIdAndProtocol(7L, StorageServiceInstance.Protocol.SMB)).thenReturn(java.util.Collections.singletonList(child));
        Assert.assertThrows(InvalidParameterValueException.class, () -> manager.validateNoChildShares(instance, parent));
        manager.validateNoChildShares(instance, child);
        org.mockito.Mockito.verify(dao, org.mockito.Mockito.never()).remove(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test public void exposesLegacyPhysicalRelativePathWithoutInventingRuntimeEvidence() {
        Assert.assertEquals("export/legacy", manager.observedFileShareRelativePath(new JsonParser().parse("{\"volumeMountPath\":\"/srv/volume\",\"backingPath\":\"/srv/volume/export/legacy\"}").getAsJsonObject()));
        Assert.assertNull(manager.observedFileShareRelativePath(new com.google.gson.JsonObject()));
    }

}
