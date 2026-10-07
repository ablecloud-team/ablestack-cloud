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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import org.apache.cloudstack.api.response.StorageServiceConfigArtifactResponse;
import org.apache.cloudstack.storage.dataservice.dao.StorageConfigArtifactDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao;
import com.cloud.exception.PermissionDeniedException;
import com.google.gson.JsonParser;

public class StorageConfigReadScopeTest {
    private Path root() throws Exception { return Files.createTempDirectory("configuration-read-test-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))); }
    @Test public void foreignInstanceIsRejectedBeforeReadingAnyArtifact() throws Exception {
        Path root = root();
        try {
            StorageServiceManagerImpl manager = Mockito.mock(StorageServiceManagerImpl.class);StorageConfigArtifactDao artifacts = Mockito.mock(StorageConfigArtifactDao.class);
            Mockito.when(manager.requireInstance(7L)).thenThrow(new PermissionDeniedException("foreign"));
            StorageConfigRequest request = Mockito.mock(StorageConfigRequest.class);
            Mockito.when(request.getConfigAction()).thenReturn("BACKUPS");Mockito.when(request.getInstanceId()).thenReturn(7L);
            StorageServiceConfiguration service = new StorageServiceConfiguration(manager, artifacts, Mockito.mock(StorageServiceOperationDao.class), new StorageConfigArtifactStore(root));
            Assert.assertThrows(PermissionDeniedException.class, () -> service.execute(request));
            Mockito.verifyNoInteractions(artifacts);
        } finally { Files.delete(root); }
    }
    @Test public void ownerMetadataReadNeverReturnsDownloadOrPlanCapability() throws Exception {
        Path root = root();
        try {
            StorageServiceManagerImpl manager = Mockito.mock(StorageServiceManagerImpl.class);StorageConfigArtifactDao artifacts = Mockito.mock(StorageConfigArtifactDao.class);
            StorageServiceInstanceVO instance = Mockito.mock(StorageServiceInstanceVO.class);Mockito.when(instance.getId()).thenReturn(7L);
            Mockito.when(manager.requireInstance(7L)).thenReturn(instance);
            StorageConfigArtifactVO row = new StorageConfigArtifactVO();row.setInstanceId(7);row.setKind("BACKUP");row.setState("COMPLETE");
            row.setMetadataJson("{'downloadToken':{'hash':'synthetic'},'planToken':{'hash':'synthetic'},'runtimeStatus':'AVAILABLE'}".replace((char) 39, (char) 34));
            Mockito.when(artifacts.listByInstance(7)).thenReturn(List.of(row));
            StorageConfigRequest request = Mockito.mock(StorageConfigRequest.class);Mockito.when(request.getConfigAction()).thenReturn("BACKUPS");Mockito.when(request.getInstanceId()).thenReturn(7L);
            StorageServiceConfiguration service = new StorageServiceConfiguration(manager, artifacts, Mockito.mock(StorageServiceOperationDao.class), new StorageConfigArtifactStore(root));
            StorageServiceConfigArtifactResponse response = service.execute(request);
            String result = (String) ReflectionTestUtils.getField(response, "result");
            String metadata = new JsonParser().parse(result).getAsJsonObject().getAsJsonArray("artifacts").get(0).getAsJsonObject().get("metadata").toString();
            Assert.assertFalse(metadata.contains("downloadToken"));Assert.assertFalse(metadata.contains("planToken"));Assert.assertTrue(metadata.contains("AVAILABLE"));
            Mockito.verify(manager, Mockito.never()).requireConfigurationAdministrator();
        } finally { Files.delete(root); }
    }
    @Test public void transferRequiresRootAdministratorBeforePrivateArtifactLookup() throws Exception {
        Path root = root();
        try {
            StorageServiceManagerImpl manager = Mockito.mock(StorageServiceManagerImpl.class);StorageConfigArtifactDao artifacts = Mockito.mock(StorageConfigArtifactDao.class);
            Mockito.doThrow(new PermissionDeniedException("root required")).when(manager).requireConfigurationAdministrator();
            StorageConfigRequest request = Mockito.mock(StorageConfigRequest.class);Mockito.when(request.getConfigAction()).thenReturn("DOWNLOAD");
            StorageServiceConfiguration service = new StorageServiceConfiguration(manager, artifacts, Mockito.mock(StorageServiceOperationDao.class), new StorageConfigArtifactStore(root));
            Assert.assertThrows(PermissionDeniedException.class, () -> service.execute(request));Mockito.verifyNoInteractions(artifacts);
        } finally { Files.delete(root); }
    }
}
