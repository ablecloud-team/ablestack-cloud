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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.CreateStoragePosixDirectoryPolicyCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStoragePosixDirectoryPolicyCmd;
import org.apache.cloudstack.storage.dataservice.dao.StoragePosixDirectoryPolicyDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageFileShareDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.storage.VolumeVO;
import com.google.gson.JsonObject;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class PosixDirectoryPolicyCrudTest {
    private final Map<Long, StoragePosixDirectoryPolicyVO> rows = new LinkedHashMap<>();
    private Harness manager;
    private StorageServiceInstanceVO instance;
    private VolumeVO volume;
    private class Harness extends StorageServiceManagerImpl {
        int applies;
        @Override protected <T> T executeDesiredChange(BaseCmd cmd, Class<T> type, java.util.function.Supplier<T> action) { return action.get(); }
        @Override public Long getStorageServiceSyncId(BaseCmd cmd) { return 3L; }
        @Override protected StorageServiceInstanceVO requireInstance(Long id) { return instance; }
        @Override protected VolumeVO requireVolume(Long id) { return volume; }
        @Override protected void validateStorageServiceBackingVolume(StorageServiceInstanceVO value, Long id, String name) { }
        @Override protected String resolveFileShareVolumeMountRoot(StorageServiceInstanceVO value, VolumeVO selected, String path) { return "/srv/ablestack-storage/volumes/" + selected.getUuid(); }
        @Override protected JsonObject dispatchPosixDirectoryCommand(StorageServiceInstanceVO value, String action, JsonObject payload) {
            if ("apply".equals(action)) applies++;
            JsonObject result = new JsonObject();result.addProperty("success", true);result.addProperty("effectiveUid", 1001001);
            result.addProperty("effectiveGid", 1001001);result.addProperty("effectiveMode", "2775");result.addProperty("driftStatus", "CONSISTENT");
            result.addProperty("canonicalPath", "/srv/ablestack-storage/volumes/" + volume.getUuid() + "/shared");return result;
        }
    }
    @Before public void setUp() {
        rows.clear();manager = new Harness();instance = mock(StorageServiceInstanceVO.class);volume = mock(VolumeVO.class);
        when(instance.getId()).thenReturn(3L);when(instance.getUuid()).thenReturn("00b331db-f1ba-4a40-aa19-931fdd393a0e");when(instance.getVmId()).thenReturn(43L);
        when(volume.getUuid()).thenReturn("ca9bcef3-8881-4b3b-84be-a989e654fd6a");
        StoragePosixDirectoryPolicyDao policies = mock(StoragePosixDirectoryPolicyDao.class);StorageFileShareDao shares = mock(StorageFileShareDao.class);
        when(policies.findById(anyLong())).thenAnswer(call -> rows.get(call.getArgument(0)));
        when(policies.findByPath(anyLong(), anyString())).thenAnswer(call -> rows.values().stream().filter(row -> row.getPathKey().equals(call.getArgument(1))).findFirst().orElse(null));
        when(policies.listByInstance(anyLong())).thenAnswer(call -> new ArrayList<>(rows.values()));
        when(policies.persist(any())).thenAnswer(call -> {
            StoragePosixDirectoryPolicyVO row = call.getArgument(0);Assert.assertNotNull(row.getState());long id = rows.size() + 1;
            ReflectionTestUtils.setField(row, "id", id);rows.put(id, row);return row;
        });
        when(shares.listByInstanceIdAndProtocol(anyLong(), any())).thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(manager, "storagePosixPolicyDao", policies);ReflectionTestUtils.setField(manager, "storageFileShareDao", shares);
    }
    private CreateStoragePosixDirectoryPolicyCmd create(boolean preview) {
        CreateStoragePosixDirectoryPolicyCmd cmd = new CreateStoragePosixDirectoryPolicyCmd();ReflectionTestUtils.setField(cmd, "instanceId", 3L);
        ReflectionTestUtils.setField(cmd, "volumeId", 45L);ReflectionTestUtils.setField(cmd, "relativePath", "shared");ReflectionTestUtils.setField(cmd, "preview", preview);return cmd;
    }
    @Test public void previewDoesNotPersistOrApplyPermissionsAndKeepsObservedMode() {
        manager.executeStoragePosixDirectoryPolicy(create(true));Assert.assertTrue(rows.isEmpty());Assert.assertEquals(0, manager.applies);
    }
    @Test public void createPersistsOneCanonicalPolicyWithExplicitNonNullStateAndObservedMode() {
        manager.executeStoragePosixDirectoryPolicy(create(false));Assert.assertEquals(1, rows.size());Assert.assertEquals(1, manager.applies);
        StoragePosixDirectoryPolicyVO row = rows.get(1L);Assert.assertEquals("Ready", row.getState());Assert.assertTrue(row.getConfigJson().contains("2775"));
        Assert.assertThrows(InvalidParameterValueException.class, () -> manager.executeStoragePosixDirectoryPolicy(create(false)));
        Assert.assertEquals(1, manager.applies);
    }
    @Test public void changedRevisionAndRecursiveModificationAreRejectedBeforeApply() {
        manager.executeStoragePosixDirectoryPolicy(create(false));UpdateStoragePosixDirectoryPolicyCmd cmd = new UpdateStoragePosixDirectoryPolicyCmd();
        ReflectionTestUtils.setField(cmd, "id", 1L);ReflectionTestUtils.setField(cmd, "expectedPolicyRevision", 99L);
        Assert.assertThrows(InvalidParameterValueException.class, () -> manager.executeStoragePosixDirectoryPolicy(cmd));
        ReflectionTestUtils.setField(cmd, "expectedPolicyRevision", 1L);ReflectionTestUtils.setField(cmd, "recursive", true);
        Assert.assertThrows(InvalidParameterValueException.class, () -> manager.executeStoragePosixDirectoryPolicy(cmd));Assert.assertEquals(1, manager.applies);
    }
    @Test public void policyFromAnotherInstanceCannotBeMutated() {
        manager.executeStoragePosixDirectoryPolicy(create(false));rows.get(1L).setInstanceId(9L);
        UpdateStoragePosixDirectoryPolicyCmd cmd = new UpdateStoragePosixDirectoryPolicyCmd();ReflectionTestUtils.setField(cmd, "id", 1L);
        Assert.assertThrows(InvalidParameterValueException.class, () -> manager.executeStoragePosixDirectoryPolicy(cmd));Assert.assertEquals(1, manager.applies);
    }
}
