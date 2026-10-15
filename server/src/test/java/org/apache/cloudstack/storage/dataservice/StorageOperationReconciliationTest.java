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

import java.util.List;
import org.junit.Assert;
import org.junit.Test;
import com.cloud.exception.InvalidParameterValueException;

public class StorageOperationReconciliationTest {
    private StorageServiceOperationVO row(long instance, long revision, String state) {
        StorageServiceOperationVO value = new StorageServiceOperationVO();value.setInstanceId(instance);value.setRevision(revision);value.setState(state);return value;
    }
    @Test public void laterSuccessReconcilesFailedRevisionWithoutRewindingIt() {
        Assert.assertTrue(StorageOperationReconciliation.superseded(row(7, 4, "RECOVERY_REQUIRED"), List.of(row(7, 5, "COMPLETE"))));
        Assert.assertTrue(StorageOperationReconciliation.superseded(row(7, 4, "RECONCILED_SUPERSEDED"), List.of(row(7, 5, "COMPLETE"))));
    }
    @Test public void otherInstanceOlderOrFailedRevisionsCannotAuthorizeReconciliation() {
        StorageServiceOperationVO failed = row(7, 4, "RECOVERY_REQUIRED");
        Assert.assertFalse(StorageOperationReconciliation.superseded(failed, List.of(row(9, 8, "COMPLETE"), row(7, 3, "COMPLETE"), row(7, 5, "ROLLED_BACK"))));
    }
    @Test public void healthyOrActiveOperationCannotBeRewrittenByRecovery() {
        for (String state : new String[] {"COMPLETE", "RUNNING", "APPLYING"}) {
            Assert.assertThrows(InvalidParameterValueException.class, () -> StorageOperationReconciliation.superseded(row(7, 4, state), List.of(row(7, 5, "COMPLETE"))));
        }
    }
    private com.google.gson.JsonObject status(StorageServiceOperationVO operation) {
        com.google.gson.JsonObject value=new com.google.gson.JsonObject(),generation=new com.google.gson.JsonObject();
        value.addProperty("success",true);value.addProperty("generationSupported",true);value.addProperty("generationStatus","IN_SYNC");
        value.addProperty("runtimeRevision",operation.getRevision());value.addProperty("configurationSha256","a".repeat(64));
        value.addProperty("bootId","11111111-1111-4111-8111-111111111111");value.add("pendingOperationUuid",com.google.gson.JsonNull.INSTANCE);
        generation.addProperty("instanceUuid","instance");generation.addProperty("operationUuid",operation.getUuid());
        generation.addProperty("revision",operation.getRevision());generation.addProperty("configurationSha256","a".repeat(64));value.add("generation",generation);return value;
    }
    private void target(StorageServiceOperationVO operation,com.google.gson.JsonObject status) {
        operation.setSnapshotJson("{\"tables\":[]}");com.google.gson.JsonObject result=new com.google.gson.JsonObject();
        result.addProperty("_response","done");result.add("_targetResourceBindings",new com.google.gson.JsonObject());
        result.addProperty("_targetNativeBootId",status.get("bootId").getAsString());result.addProperty("_targetConfigurationSha256","a".repeat(64));
        operation.setResultJson(result.toString());
    }
    @Test public void exactCommittedTargetCanFinalizeWithoutAnySourceReplay() {
        StorageServiceOperationVO operation=row(7,8,"RECOVERY_REQUIRED");com.google.gson.JsonObject status=status(operation);target(operation,status);
        StorageOperationReconciliation.requireTarget("instance",operation,operation.getSnapshotJson(),new com.google.gson.JsonObject(),status);
        status.addProperty("pendingOperationUuid",operation.getUuid());
        StorageOperationReconciliation.requireTarget("instance",operation,operation.getSnapshotJson(),new com.google.gson.JsonObject(),status);
    }
    @Test public void foreignBootDigestRevisionPendingOrMissingTargetCannotFinalize() {
        for(String field:new String[]{"bootId","configurationSha256","runtimeRevision","pendingOperationUuid"}) {
            StorageServiceOperationVO operation=row(7,8,"RECOVERY_REQUIRED");com.google.gson.JsonObject status=status(operation);target(operation,status);
            if(field.equals("runtimeRevision"))status.addProperty(field,9);else status.addProperty(field,field.equals("configurationSha256")?"b".repeat(64):"foreign");
            Assert.assertThrows(RuntimeException.class,()->StorageOperationReconciliation.requireTarget("instance",operation,operation.getSnapshotJson(),new com.google.gson.JsonObject(),status));
        }
        StorageServiceOperationVO operation=row(7,8,"RECOVERY_REQUIRED");com.google.gson.JsonObject status=status(operation);target(operation,status);
        Assert.assertThrows(RuntimeException.class,()->StorageOperationReconciliation.requireTarget("instance",operation,"{\"tables\":[{}]}",new com.google.gson.JsonObject(),status));
        com.google.gson.JsonObject foreign=new com.google.gson.JsonObject();foreign.addProperty("vmUuid","foreign");
        Assert.assertThrows(RuntimeException.class,()->StorageOperationReconciliation.requireTarget("instance",operation,operation.getSnapshotJson(),foreign,status));
        operation.setSnapshotJson(null);Assert.assertThrows(RuntimeException.class,()->StorageOperationReconciliation.requireTarget("instance",operation,"{}",new com.google.gson.JsonObject(),status));
    }
    @Test public void numericOverflowAndStringBooleansCannotForgeCommit() {
        StorageServiceOperationVO operation=row(7,8,"RECOVERY_REQUIRED");com.google.gson.JsonObject status=status(operation);
        status.getAsJsonObject("generation").addProperty("revision",new java.math.BigInteger("18446744073709551624"));
        Assert.assertFalse(StorageOperationReconciliation.committedTarget("instance",operation,status));
        status=status(operation);status.addProperty("success","true");Assert.assertFalse(StorageOperationReconciliation.committedTarget("instance",operation,status));
    }
    @Test public void readOnlyArtifactCleanupUsesUnchangedSourceNotTargetCommit() {
        StorageServiceOperationVO source=row(7,7,"COMPLETE");com.google.gson.JsonObject status=status(source);
        StorageServiceOperationVO backup=row(7,8,"RECOVERY_REQUIRED");backup.setAction("CONFIGURATION_BACKUP_READ_ONLY");
        backup.setPreviousSnapshotJson("{\"tables\":[]}");backup.setSnapshotJson(backup.getPreviousSnapshotJson());
        com.google.gson.JsonObject result=new com.google.gson.JsonObject();result.addProperty("_response","artifact");
        result.add("_targetResourceBindings",new com.google.gson.JsonObject());result.add("_readOnlyNativeSource",StorageOperationReconciliation.readOnlyNative(status));backup.setResultJson(result.toString());
        StorageOperationReconciliation.requireReadOnlySource(backup,backup.getPreviousSnapshotJson(),new com.google.gson.JsonObject(),status);
        Assert.assertFalse(StorageOperationReconciliation.committedTarget("instance",backup,status));
        status.addProperty("pendingOperationUuid",backup.getUuid());
        Assert.assertThrows(RuntimeException.class,()->StorageOperationReconciliation.requireReadOnlySource(backup,backup.getPreviousSnapshotJson(),new com.google.gson.JsonObject(),status));
    }

    @Test public void readOnlyArtifactReceiptRequiresSameStoredSourceArchive() throws Exception {
        java.nio.file.Path directory=java.nio.file.Files.createTempDirectory("storage-readonly-backup-");
        java.nio.file.Files.setPosixFilePermissions(directory,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        StorageConfigArtifactStore store=new StorageConfigArtifactStore(directory);
        StorageConfigArtifactVO artifact=new StorageConfigArtifactVO();artifact.setInstanceId(7);artifact.setKind("BACKUP");artifact.setState("COMPLETE");artifact.setDesiredRevision(7);
        byte[] bytes="protected archive fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8);artifact.setSize(bytes.length);artifact.setSha256(StorageConfigArchive.sha256(bytes));
        store.write(artifact.getUuid(),bytes);
        org.apache.cloudstack.storage.dataservice.dao.StorageConfigArtifactDao artifacts=org.mockito.Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageConfigArtifactDao.class);
        org.mockito.Mockito.when(artifacts.findByUuid(artifact.getUuid())).thenReturn(artifact);
        StorageServiceConfiguration configuration=new StorageServiceConfiguration(org.mockito.Mockito.mock(StorageServiceManagerImpl.class),artifacts,
                org.mockito.Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao.class),store);
        StorageServiceOperationVO operation=row(7,8,"RECOVERY_REQUIRED");
        com.google.gson.JsonObject response=new com.google.gson.JsonObject(),result=new com.google.gson.JsonObject();response.addProperty("id",artifact.getUuid());result.add("_response",response);operation.setResultJson(result.toString());
        try {
            configuration.bindReadOnlyArtifact(operation);configuration.verifyReadOnlyArtifact(operation);
            artifact.setInstanceId(9);Assert.assertThrows(RuntimeException.class,()->configuration.verifyReadOnlyArtifact(operation));
            artifact.setInstanceId(7);artifact.setDesiredRevision(8);Assert.assertThrows(RuntimeException.class,()->configuration.verifyReadOnlyArtifact(operation));
            artifact.setDesiredRevision(7);java.nio.file.Files.write(directory.resolve(artifact.getUuid()+".zip"),new byte[]{1});
            Assert.assertThrows(RuntimeException.class,()->configuration.verifyReadOnlyArtifact(operation));
        } finally {store.remove(artifact.getUuid());java.nio.file.Files.delete(directory);}
    }

    @Test public void incompleteReadOnlyRestartCanOnlyCleanUnchangedSourceAndCannotBecomeSuccessfulArtifact() {
        StorageServiceOperationVO source=row(7,7,"COMPLETE");com.google.gson.JsonObject status=status(source);
        StorageServiceOperationVO backup=row(7,8,"RECOVERY_REQUIRED");backup.setAction("CONFIGURATION_BACKUP_READ_ONLY");backup.setPreviousSnapshotJson("{\"tables\":[]}");
        com.google.gson.JsonObject result=new com.google.gson.JsonObject();result.add("_targetResourceBindings",new com.google.gson.JsonObject());
        result.add("_readOnlyNativeSource",StorageOperationReconciliation.readOnlyNative(status));backup.setResultJson(result.toString());
        StorageOperationReconciliation.requireReadOnlySource(backup,backup.getPreviousSnapshotJson(),new com.google.gson.JsonObject(),status,false);
        Assert.assertThrows(RuntimeException.class,()->StorageOperationReconciliation.requireReadOnlySource(backup,backup.getPreviousSnapshotJson(),new com.google.gson.JsonObject(),status,true));
        Assert.assertThrows(RuntimeException.class,()->StorageOperationReconciliation.requireReadOnlySource(backup,"{\"tables\":[{}]}",new com.google.gson.JsonObject(),status,false));
        status.addProperty("bootId","22222222-2222-4222-8222-222222222222");
        Assert.assertThrows(RuntimeException.class,()->StorageOperationReconciliation.requireReadOnlySource(backup,backup.getPreviousSnapshotJson(),new com.google.gson.JsonObject(),status,false));
    }

    @Test public void restoredSourceCleanupRequiresOriginalDeclarationNativeGenerationAndBindings() {
        StorageServiceOperationVO source=row(7,7,"COMPLETE");com.google.gson.JsonObject status=status(source);
        StorageServiceOperationVO operation=row(7,8,"RECOVERY_REQUIRED");
        operation.setPreviousSnapshotJson("{\"schemaVersion\":2,\"instanceId\":7,\"tables\":[],\"nativeGeneration\":{}}");
        String current="{\"schemaVersion\":2,\"instanceId\":7,\"tables\":[]}";
        com.google.gson.JsonObject result=new com.google.gson.JsonObject();result.add("_leaseSourceNativeGeneration",StorageOperationReconciliation.readOnlyNative(status));
        result.add("_leaseSourceResourceBindings",new com.google.gson.JsonObject());operation.setResultJson(result.toString());
        StorageOperationReconciliation.requireRestoredSource(operation,current,new com.google.gson.JsonObject(),status);
        Assert.assertThrows(RuntimeException.class,()->StorageOperationReconciliation.requireRestoredSource(operation,"{\"schemaVersion\":2,\"instanceId\":7,\"tables\":[{}]}",new com.google.gson.JsonObject(),status));
        com.google.gson.JsonObject changedBinding=new com.google.gson.JsonObject();changedBinding.addProperty("vmUuid","foreign");
        Assert.assertThrows(RuntimeException.class,()->StorageOperationReconciliation.requireRestoredSource(operation,current,changedBinding,status));
        status.getAsJsonObject("generation").addProperty("operationUuid",operation.getUuid());
        Assert.assertThrows(RuntimeException.class,()->StorageOperationReconciliation.requireRestoredSource(operation,current,new com.google.gson.JsonObject(),status));
    }
}
