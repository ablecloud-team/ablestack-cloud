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
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

public class StorageIdentityCheckpointLifecycleTest {
    @Test public void unfinishedAndRecoveryOperationsKeepTheProtectedCheckpoint() {
        StorageServiceManagerImpl manager = new StorageServiceManagerImpl();
        StorageServiceOperationDao operations = Mockito.mock(StorageServiceOperationDao.class);
        ReflectionTestUtils.setField(manager, "storageOperationDao", operations);
        for (String state : new String[] {"RUNNING", "RECOVERY_REQUIRED", "PENDING"}) {
            StorageServiceOperationVO operation = new StorageServiceOperationVO();
            operation.setState(state);operation.setPreviousSnapshotJson("not-read-until-terminal");
            manager.cleanupConfigurationIdentityCheckpoint(operation);
            Assert.assertEquals("not-read-until-terminal", operation.getPreviousSnapshotJson());
        }
        Mockito.verifyNoInteractions(operations);
    }

    @Test public void successfulRollbackRemovesOnlyItsNamedProtectedFiles() throws Exception {
        Path root = Files.createTempDirectory("identity-cleanup-test-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        String oldPath = System.getProperty("cloudstack.storage.identity.path");
        String capsule = "11111111-1111-4111-8111-111111111111", key = "22222222-2222-4222-8222-222222222222";
        try {
            System.setProperty("cloudstack.storage.identity.path", root.toString());
            StorageConfigArtifactStore store = new StorageConfigArtifactStore(root);
            store.write(capsule, new byte[] {1});store.write(key, new byte[] {2});
            Path foreign = root.resolve("preserved-audit");Files.write(foreign, new byte[] {3});
            StorageServiceManagerImpl manager = new StorageServiceManagerImpl();
            StorageServiceOperationDao operations = Mockito.mock(StorageServiceOperationDao.class);
            ReflectionTestUtils.setField(manager, "storageOperationDao", operations);
            StorageServiceOperationVO operation = new StorageServiceOperationVO();operation.setState("ROLLED_BACK");
            com.google.gson.JsonObject reference = new com.google.gson.JsonObject();reference.addProperty("operationUuid", capsule);reference.addProperty("keyId", key);
            com.google.gson.JsonObject snapshot = new com.google.gson.JsonObject();snapshot.add("nativeIdentityCapsule", reference);operation.setPreviousSnapshotJson(snapshot.toString());
            manager.cleanupConfigurationIdentityCheckpoint(operation);
            Assert.assertFalse(Files.exists(root.resolve(capsule + ".zip")));Assert.assertFalse(Files.exists(root.resolve(key + ".zip")));
            Assert.assertArrayEquals(new byte[] {3}, Files.readAllBytes(foreign));Assert.assertTrue(operation.getPreviousSnapshotJson().contains("CLEANED"));
            Files.delete(foreign);
        } finally {
            if (oldPath == null) System.clearProperty("cloudstack.storage.identity.path");else System.setProperty("cloudstack.storage.identity.path", oldPath);
            Files.deleteIfExists(root.resolve(capsule + ".zip"));Files.deleteIfExists(root.resolve(key + ".zip"));Files.delete(root);
        }
    }
}
