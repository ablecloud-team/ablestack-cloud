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
package org.apache.cloudstack.storage.dataservice;

import com.google.gson.JsonObject;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

public class StorageSmbIdentityRepairRuntimeTest {
    private static class Manager extends StorageServiceManagerImpl {
        JsonObject originalGeneration;
        int rebinds;
        boolean sessionBusy,foreignAfter,liveIdentity;
        @Override protected void requireVolumeResumeIdle(StorageServiceInstanceVO instance,StorageServiceOperationVO operation) { }
        @Override protected void requireNoPendingVolumeFormatter(StorageServiceInstanceVO instance) { }
        @Override protected JsonObject nativeConfigurationGeneration(StorageServiceInstanceVO instance,StorageServiceOperationVO operation,String action) {
            Assert.assertEquals("status",action);JsonObject value=new JsonObject();value.addProperty("generationStatus","IN_SYNC");value.add("generation",originalGeneration);return value;
        }
        @Override protected JsonObject rootGuest(StorageServiceInstanceVO instance,String command,JsonObject request,int timeout) {
            if ("smb identity rebind".equals(command)) {rebinds++;JsonObject value=StorageSmbIdentityRepairProofTest.result();value.add("scope",smbIdentityRepairScope(instance,writer));return value;}
            Assert.assertEquals("smb identity inspect",command);Assert.assertTrue(timeout<=5);
            JsonObject value=StorageSmbIdentityRepairProofTest.inspection();value.add("scope",request.deepCopy());value.getAsJsonObject("generation").addProperty("instanceUuid",instance.getUuid());originalGeneration=value.getAsJsonObject("generation").deepCopy();
            if (sessionBusy) value.getAsJsonObject("sessions").addProperty("establishedTcpCount",1);
            value.addProperty("identityDatabaseAligned",rebinds>0);value.addProperty("identityRestoreSafe",!liveIdentity);
            if (foreignAfter && rebinds>0)value.getAsJsonObject("databases").getAsJsonObject("PASSDB").addProperty("inode",999);
            return value;
        }
        StorageServiceOperationVO writer;
    }
    private Manager manager;
    private StorageServiceInstanceVO instance;
    private StorageServiceOperationVO operation;
    private StorageServiceOperationDao operations;
    @Before public void setup() {
        manager=new Manager();instance=Mockito.mock(StorageServiceInstanceVO.class);Mockito.when(instance.getUuid()).thenReturn("instance");
        operation=new StorageServiceOperationVO();operation.setAction("SMB_IDENTITY_REPAIR");operation.setRevision(10);operation.setState("RUNNING");JsonObject intent=new JsonObject();intent.addProperty("desiredStateChanged",false);intent.addProperty("baseDesiredRevision",9);operation.setResultJson(intent.toString());manager.writer=operation;
        operations=Mockito.mock(StorageServiceOperationDao.class);Mockito.when(operations.update(Mockito.anyLong(),Mockito.any())).thenReturn(true);ReflectionTestUtils.setField(manager,"storageOperationDao",operations);
    }
    @Test public void exactSessionlessRebindCompletesWithoutChangingDesiredOrNativeRevision() {
        manager.recoverSmbIdentityRepair(instance,operation);Assert.assertEquals("COMPLETE_NO_CONFIG_CHANGE",operation.getState());Assert.assertEquals(1,manager.rebinds);Assert.assertEquals(9,manager.originalGeneration.get("revision").getAsLong());Assert.assertNull(operation.getSnapshotJson());
    }
    @Test public void realConnectionsBlockBeforeOwnedUnitsAreRestarted() {
        manager.sessionBusy=true;Assert.assertThrows(CloudRuntimeException.class,()->manager.recoverSmbIdentityRepair(instance,operation));Assert.assertEquals(0,manager.rebinds);Assert.assertEquals("RECOVERY_REQUIRED",operation.getState());
    }
    @Test public void responseLossOrChangedDatabaseIdentityCannotClaimRepairComplete() {
        manager.foreignAfter=true;Assert.assertThrows(CloudRuntimeException.class,()->manager.recoverSmbIdentityRepair(instance,operation));Assert.assertEquals("RECOVERY_REQUIRED",operation.getState());Assert.assertNotNull(operation.getPreviousSnapshotJson());Assert.assertNull(operation.getSnapshotJson());
    }
    @Test public void liveAuthenticationDescriptorsRejectIdentityRestoreBeforeDatabaseRollback() {
        Mockito.when(instance.getVmId()).thenReturn(7L);JsonObject checkpoint=new JsonObject();checkpoint.add("nativeIdentityCapsule",new JsonObject());operation.setPreviousSnapshotJson(checkpoint.toString());manager.liveIdentity=true;
        Assert.assertThrows(CloudRuntimeException.class,()->manager.requireIdentityRollbackSafe(instance,operation));Assert.assertEquals(0,manager.rebinds);
        Mockito.verify(operations,Mockito.never()).update(Mockito.anyLong(),Mockito.any());
    }
    @Test public void exactRecoveryRetryClearsStaleFailureDiagnosticAndKeepsOriginalScope() {
        manager.foreignAfter=true;
        Assert.assertThrows(CloudRuntimeException.class,()->manager.recoverSmbIdentityRepair(instance,operation));
        JsonObject frozen=com.google.gson.JsonParser.parseString(operation.getPreviousSnapshotJson()).getAsJsonObject();
        Assert.assertEquals("SMB_REPAIR_READBACK_REQUIRED",com.google.gson.JsonParser.parseString(operation.getResultJson()).getAsJsonObject().get("errorCode").getAsString());
        manager.foreignAfter=false;
        manager.recoverSmbIdentityRepair(instance,operation);
        Assert.assertEquals("COMPLETE_NO_CONFIG_CHANGE",operation.getState());
        Assert.assertFalse(operation.getDiagnostic().contains("requires exact"));
        Assert.assertFalse(com.google.gson.JsonParser.parseString(operation.getResultJson()).getAsJsonObject().has("errorCode"));
        Assert.assertEquals(frozen,com.google.gson.JsonParser.parseString(operation.getPreviousSnapshotJson()));
    }


    static JsonObject currentContext() {
        JsonObject context = new JsonObject();context.addProperty("instanceUuid", "3480bb2c-99ee-42f2-91cf-715ded5dd35f");
        context.addProperty("operationUuid", "cfe87d14-7b32-4b9c-999d-fcdae29dbb3d");context.addProperty("revision", 4);
        JsonObject generation = new JsonObject();generation.addProperty("instanceUuid", context.get("instanceUuid").getAsString());
        generation.addProperty("operationUuid", "23340c92-7269-4730-9616-cfb231bfc592");generation.addProperty("revision", 3);
        generation.addProperty("configurationSha256", "a".repeat(64));context.add("sourceGeneration", generation);
        context.addProperty("sourceConfigurationSha256", "a".repeat(64));
        JsonObject binding = new JsonObject();binding.addProperty("vmUuid", "9e57d31b-bb68-4ba3-a377-b0acbc89c22d");
        binding.addProperty("rootVolumeUuid", "f24d1253-cbe3-46d7-8b77-055bd17ed1bc");context.add("rootVmBinding", binding);
        JsonObject pin = new JsonObject();pin.addProperty("bundleVersion", "synthetic-current-fixture");
        for (String field : java.util.Set.of("archiveSha256", "manifestSha256", "updaterSha256")) pin.addProperty(field, "b".repeat(64));
        context.add("runtimePin", pin);return context;
    }
    static JsonObject currentReview(JsonObject context) {
        JsonObject review = new JsonObject();review.addProperty("success", true);review.addProperty("sideEffects", false);
        review.addProperty("schemaVersion", 1);review.addProperty("kind", StorageSmbCurrentIdentityRecoveryProof.REVIEW);
        review.add("scope", StorageSmbCurrentIdentityRecoveryProof.scope(context));
        for (String field : java.util.Set.of("sourceGeneration", "sourceConfigurationSha256", "rootVmBinding", "runtimePin")) review.add(field, context.get(field).deepCopy());
        review.addProperty("bootId", "0fce7e4a-7f46-4b33-9a6e-0137cdbcf8da");review.addProperty("currentReviewHash", "c".repeat(64));
        review.addProperty("generatedEpoch", 1000);
        JsonObject facts = new JsonObject();facts.add("bootId", review.get("bootId").deepCopy());facts.addProperty("loadedDaemonSidVerified", false);
        JsonObject databases = new JsonObject();
        for (String name : java.util.Set.of("PASSDB", "SECRETS")) {
            JsonObject file = new JsonObject();file.addProperty("present", true);file.addProperty("device", 1);file.addProperty("inode", "PASSDB".equals(name) ? 10 : 11);
            file.addProperty("uid", 0);file.addProperty("gid", 0);file.addProperty("mode", "0600");
            file.addProperty("path", "/var/lib/samba/private/" + ("PASSDB".equals(name) ? "passdb.tdb" : "secrets.tdb"));databases.add(name, file);
        }
        facts.add("databases", databases);com.google.gson.JsonArray namespaces = new com.google.gson.JsonArray();
        JsonObject namespace = new JsonObject();namespace.addProperty("samNamespace", "SYSTEMVM");namespace.addProperty("machineSid", "S-1-5-21-1-2-3");
        namespaces.add(namespace);facts.add("publicNamespaceSids", namespaces);
        com.google.gson.JsonArray units = new com.google.gson.JsonArray();JsonObject unit = new JsonObject();unit.addProperty("unit", "smbd.service");
        unit.addProperty("pid", 100);unit.addProperty("startTicks", "12345");unit.addProperty("cgroup", "0::/system.slice/smbd.service");
        unit.addProperty("configurationPath", "/etc/samba/smb.conf");
        JsonObject executable = new JsonObject();executable.addProperty("path", "/usr/sbin/smbd");executable.addProperty("device", 1);
        executable.addProperty("inode", 99);executable.addProperty("sha256", "f".repeat(64));unit.add("executable", executable);
        JsonObject vendor = executable.deepCopy();vendor.addProperty("path", "/lib/systemd/system/smbd.service");unit.add("vendorUnit", vendor);
        com.google.gson.JsonArray argv = new com.google.gson.JsonArray();argv.add("/usr/sbin/smbd");unit.add("argv", argv);units.add(unit);facts.add("units", units);
        com.google.gson.JsonArray listeners = new com.google.gson.JsonArray();JsonObject listener = new JsonObject();
        listener.addProperty("ip", "0.0.0.0");listener.addProperty("port", 445);listener.addProperty("state", "LISTEN");
        com.google.gson.JsonArray pids = new com.google.gson.JsonArray();pids.add(100);listener.add("pids", pids);listeners.add(listener);facts.add("listeners", listeners);facts.add("identityHolders", new com.google.gson.JsonArray());facts.addProperty("configurationSha256", "d".repeat(64));
        JsonObject sessions = new JsonObject();
        for (String field : java.util.Set.of("available", "lockingDatabasesAligned", "safeToRebind")) sessions.addProperty(field, true);
        for (String field : java.util.Set.of("establishedTcpCount", "synRecvTcpCount", "smbSessionCount", "treeConnectionCount", "openFileCount", "byteLockOpenFileCount")) sessions.addProperty(field, 0);
        facts.add("sessions", sessions);review.add("currentFacts", facts);return review;
    }
    static JsonObject currentRetained(JsonObject context, JsonObject reference) {
        JsonObject result = new JsonObject();result.addProperty("success", true);result.add("scope", StorageSmbCurrentIdentityRecoveryProof.scope(context));
        result.addProperty("kind", "LOCAL_IDENTITY_RETAINED_WITHOUT_SMB_CONFIG");result.addProperty("currentIdentityRetained", true);
        result.addProperty("originalIdentityRestored", false);result.addProperty("originalConfigurationSourceRestored", true);result.addProperty("automaticSmbExposure", false);
        result.add("generation", context.get("sourceGeneration").deepCopy());result.add("sourceConfigurationSha256", context.get("sourceConfigurationSha256").deepCopy());
        result.add("currentIdentityReference", reference.deepCopy());return result;
    }
    static JsonObject currentDataIdentity() {
        JsonObject data = new JsonObject();com.google.gson.JsonArray volumes = new com.google.gson.JsonArray();JsonObject volume = new JsonObject();
        volume.addProperty("volumeUuid", "ceaee4ff-0dc6-4c4f-ac5d-e28e658906a7");volume.addProperty("kind", "UNUSED");
        volume.addProperty("serial", "ceaee4ff0dc64c4fac5de28e658906a7");volume.addProperty("wwn", "synthetic-own-wwn");volume.addProperty("sizeBytes", 20L * 1024 * 1024 * 1024);
        volume.addProperty("filesystemUuid", "b54f3304-3ab3-4be7-a63a-a324bee2255b");volume.addProperty("filesystem", "xfs");volumes.add(volume);data.add("volumes", volumes);return data;
    }
    private static class CurrentManager extends StorageServiceManagerImpl {
        final java.util.List<String> calls = new java.util.ArrayList<>();
        boolean pending = true, responseLost, failRetention, changedData, joined, idleRejected, formatterRejected, freshFactsChanged;
        @Override protected boolean hasJoinedStorageAdDomain(StorageServiceInstanceVO instance) {return joined;}
        @Override protected void requireVolumeResumeIdle(StorageServiceInstanceVO instance, StorageServiceOperationVO own) {
            if (idleRejected) throw new CloudRuntimeException("synthetic ROOT/SERVICE/foreign writer hold");
        }
        @Override protected void requireNoPendingVolumeFormatter(StorageServiceInstanceVO instance) {
            if (formatterRejected) throw new CloudRuntimeException("synthetic formatter hold");
        }
        JsonObject context = currentContext();
        @Override protected JsonObject currentSmbOriginalMaterial(StorageServiceInstanceVO instance, StorageServiceOperationVO operation, JsonObject reference) {
            JsonObject material = new JsonObject();material.add("capsule", new JsonObject());material.addProperty("credentialPrivateKey", "SYNTHETIC_SEALED_INPUT");return material;
        }
        @Override protected JsonObject currentSmbRecoveryGuest(StorageServiceInstanceVO instance, String action, JsonObject request, int timeout) {
            if ("current-review".equals(action)) {
                JsonObject fresh = currentReview(context);fresh.addProperty("generatedEpoch", System.currentTimeMillis() / 1000.0);
                if (freshFactsChanged) fresh.getAsJsonObject("currentFacts").addProperty("configurationSha256", "e".repeat(64));
                return fresh;
            }
            calls.add(action);
            if ("current-quiesce".equals(action)) {
                Assert.assertTrue(request.has("originalCapsule"));Assert.assertEquals("fixture", request.get("confirmation").getAsString());
                JsonObject stopped = new JsonObject();stopped.addProperty("success", true);stopped.add("scope", StorageSmbCurrentIdentityRecoveryProof.scope(context));
                stopped.addProperty("kind", "CURRENT_LOCAL_SMB_IDENTITY_STOPPED");stopped.addProperty("currentReviewHash", "c".repeat(64));
                stopped.addProperty("bootId", "0fce7e4a-7f46-4b33-9a6e-0137cdbcf8da");stopped.addProperty("currentStoppedVerified", true);
                stopped.addProperty("originalIdentityRestored", false);stopped.addProperty("automaticSmbExposure", false);return stopped;
            }
            if (failRetention) throw new CloudRuntimeException("synthetic retained-ref mismatch");
            Assert.assertFalse(request.has("credentialPrivateKey"));Assert.assertFalse(request.has("originalCapsule"));
            return currentRetained(context, request.getAsJsonObject("currentIdentityReference"));
        }
        @Override protected void checkpointCurrentSmbIdentity(StorageServiceInstanceVO instance, StorageServiceOperationVO operation, JsonObject saved) {
            if (!saved.has("currentReference")) {
                calls.add("current-export");JsonObject nativeRef = new JsonObject();nativeRef.add("operationUuid", context.get("operationUuid").deepCopy());nativeRef.addProperty("sha256", "e".repeat(64));
                JsonObject reference = new JsonObject();reference.add("nativeReference", nativeRef);saved.add("currentReference", reference);
                saveCurrentSmbRecovery(operation, saved, "CURRENT_LOCAL_IDENTITY_EXPORTED");
            }
        }
        @Override protected void restoreCurrentSmbSourceConfiguration(StorageServiceInstanceVO instance, StorageServiceOperationVO operation, JsonObject saved) {
            calls.add("restore-source-config-only");saved.addProperty("sourceConfigurationRestored", true);
        }
        @Override protected JsonObject nativeConfigurationGeneration(StorageServiceInstanceVO instance, StorageServiceOperationVO operation, String action) {
            JsonObject result = new JsonObject();result.add("generation", context.get("sourceGeneration").deepCopy());
            if ("rollback".equals(action)) {calls.add("generation-rollback");pending = false;if (responseLost) throw new CloudRuntimeException("synthetic rollback response lost");}
            if (pending) result.add("pendingOperationUuid", context.get("operationUuid").deepCopy());return result;
        }
        @Override protected JsonObject currentSmbDataIdentity(StorageServiceInstanceVO instance, StorageServiceOperationVO operation) {
            JsonObject data = currentDataIdentity();if (changedData) data.getAsJsonArray("volumes").get(0).getAsJsonObject().addProperty("filesystemUuid", "changed");return data;
        }
    }
    private JsonObject currentSaved() {
        JsonObject saved = new JsonObject();saved.add("context", currentContext());saved.add("review", currentReview(currentContext()));
        saved.add("originalReference", new JsonObject());saved.add("dataIdentity", currentDataIdentity());return saved;
    }
    private CurrentManager currentManager() {
        CurrentManager current = new CurrentManager();ReflectionTestUtils.setField(current, "storageOperationDao", operations);
        Mockito.when(instance.getName()).thenReturn("fixture");operation.setAction("createstoragesmbshareresponse");operation.setState("RECOVERY_REQUIRED");
        operation.setPreviousSnapshotJson("{\"nativeIdentityCapsule\":{\"immutable\":true}}");return current;
    }
    @Test public void currentReviewRejectsStaleForeignSourceRuntimeAndStringBooleanBeforeStop() {
        JsonObject context = currentContext(), review = currentReview(context);
        StorageSmbCurrentIdentityRecoveryProof.review(context, review, 1000);
        Assert.assertThrows(CloudRuntimeException.class, () -> StorageSmbCurrentIdentityRecoveryProof.review(context, review, 1061));
        for (String field : java.util.Set.of("sourceGeneration", "rootVmBinding", "runtimePin")) {
            JsonObject wrong = review.deepCopy();wrong.getAsJsonObject(field).addProperty("foreign", true);
            Assert.assertThrows(CloudRuntimeException.class, () -> StorageSmbCurrentIdentityRecoveryProof.review(context, wrong, 1000));
        }
        review.addProperty("sideEffects", "false");Assert.assertThrows(CloudRuntimeException.class, () -> StorageSmbCurrentIdentityRecoveryProof.review(context, review, 1000));
    }
    @Test public void currentPublicProjectionRejectsMissingNamespaceDatabaseForeignPidAndLiveSessions() {
        JsonObject context = currentContext();
        for (String failure : java.util.List.of("namespace", "database", "pid", "session", "boot", "configuration", "listener", "holder")) {
            JsonObject review = currentReview(context), facts = review.getAsJsonObject("currentFacts");
            if ("namespace".equals(failure)) facts.getAsJsonArray("publicNamespaceSids").get(0).getAsJsonObject().remove("machineSid");
            if ("database".equals(failure)) facts.getAsJsonObject("databases").getAsJsonObject("SECRETS").addProperty("present", "true");
            if ("pid".equals(failure)) facts.getAsJsonArray("units").get(0).getAsJsonObject().addProperty("cgroup", "foreign");
            if ("session".equals(failure)) facts.getAsJsonObject("sessions").addProperty("openFileCount", 1);
            if ("boot".equals(failure)) facts.addProperty("bootId", "foreign");
            if ("configuration".equals(failure)) facts.addProperty("configurationSha256", true);
            if ("listener".equals(failure)) facts.getAsJsonArray("listeners").get(0).getAsJsonObject().addProperty("port", "445");
            if ("holder".equals(failure)) facts.addProperty("identityHolders", "not an array");
            Assert.assertThrows(CloudRuntimeException.class, () -> StorageSmbCurrentIdentityRecoveryProof.review(context, review, 1000));
        }
    }
    @Test public void currentPublicReviewNeverPublishesNamespaceSidArgvOrProtectedReferences() {
        CurrentManager current = currentManager();JsonObject publicReview = current.currentSmbReviewPublic(currentContext(), currentReview(currentContext()));
        Assert.assertEquals(java.util.Set.of("bootId", "databaseCount", "ownedMasterCount", "sessionsVerifiedEmpty", "namespaceObserved", "loadedDaemonSidVerified"), publicReview.getAsJsonObject("publicFacts").keySet());
        Assert.assertFalse(publicReview.toString().contains("S-1-5-21"));Assert.assertFalse(publicReview.toString().contains("currentIdentityReference"));
        Assert.assertTrue(StorageSmbCurrentIdentityRecoveryProof.literal(publicReview.getAsJsonObject("publicFacts"), "loadedDaemonSidVerified", false));
    }
    @Test public void approvedCurrentRecoveryOrdersConfigOnlyBeforeRetentionAndKeepsImmutableOriginal() {
        CurrentManager current = currentManager();String original = operation.getPreviousSnapshotJson();JsonObject saved = currentSaved();
        current.recoverCurrentSmbIdentity(instance, operation, saved);
        Assert.assertEquals(java.util.List.of("current-quiesce", "current-export", "restore-source-config-only", "current-retain", "generation-rollback", "current-verify"), current.calls);
        Assert.assertEquals("ROLLED_BACK", operation.getState());Assert.assertEquals(StorageSmbCurrentIdentityRecoveryProof.PHASE, operation.getPhase());
        Assert.assertEquals(original, operation.getPreviousSnapshotJson());Assert.assertTrue(saved.has("currentReference"));
        Assert.assertTrue(StorageSmbCurrentIdentityRecoveryProof.literal(saved.getAsJsonObject("verified"), "originalIdentityRestored", false));
    }
    @Test public void currentRollbackResponseLossResumesStoredCipherWithoutSecondStopOrExport() {
        CurrentManager current = currentManager();JsonObject saved = currentSaved();current.responseLost = true;
        Assert.assertThrows(CloudRuntimeException.class, () -> current.recoverCurrentSmbIdentity(instance, operation, saved));
        Assert.assertEquals("RECOVERY_REQUIRED", operation.getState());Assert.assertTrue(saved.has("currentReference"));Assert.assertFalse(current.pending);
        JsonObject durable = com.google.gson.JsonParser.parseString(operation.getResultJson()).getAsJsonObject().getAsJsonObject("_currentLocalIdentityRecovery");
        current.responseLost = false;current.recoverCurrentSmbIdentity(instance, operation, durable);
        Assert.assertEquals(1, java.util.Collections.frequency(current.calls, "current-quiesce"));Assert.assertEquals(1, java.util.Collections.frequency(current.calls, "current-export"));
        Assert.assertEquals(1, java.util.Collections.frequency(current.calls, "generation-rollback"));Assert.assertEquals("ROLLED_BACK", operation.getState());
    }
    @Test public void currentRetentionFailureKeepsPendingAndCannotPublishTerminalRollback() {
        CurrentManager current = currentManager();JsonObject saved = currentSaved();current.failRetention = true;
        Assert.assertThrows(CloudRuntimeException.class, () -> current.recoverCurrentSmbIdentity(instance, operation, saved));
        Assert.assertTrue(current.pending);Assert.assertFalse(current.calls.contains("generation-rollback"));Assert.assertEquals("RECOVERY_REQUIRED", operation.getState());
        Assert.assertNotNull(operation.getPreviousSnapshotJson());Assert.assertTrue(saved.has("currentReference"));
    }
    @Test public void changedCurrentDataFilesystemCannotClaimDataUnchangedAfterConfigurationRollback() {
        CurrentManager current = currentManager();current.changedData = true;
        Assert.assertThrows(CloudRuntimeException.class, () -> current.recoverCurrentSmbIdentity(instance, operation, currentSaved()));
        Assert.assertEquals("RECOVERY_REQUIRED", operation.getState());Assert.assertNotEquals(StorageSmbCurrentIdentityRecoveryProof.PHASE, operation.getPhase());
    }
    @Test public void approvedCurrentRecoveryNeverFallsBackToOriginalIdentityImport() {
        CurrentManager current = currentManager();JsonObject result = new JsonObject();result.add("_currentLocalIdentityRecovery", currentSaved());operation.setResultJson(result.toString());
        Assert.assertThrows(CloudRuntimeException.class, () -> current.recoverInterruptedStorageWriter(instance, operation));Assert.assertTrue(current.calls.isEmpty());
    }


    @Test public void currentEligibilityRequiresActualActionOwnerActorOriginalKeyPurposeAndIdleBoundaries() {
        CurrentManager current = currentManager();org.apache.cloudstack.context.CallContext caller = Mockito.mock(org.apache.cloudstack.context.CallContext.class);
        com.cloud.user.Account account = Mockito.mock(com.cloud.user.Account.class);Mockito.when(account.getId()).thenReturn(2L);
        Mockito.when(caller.getCallingAccount()).thenReturn(account);Mockito.when(caller.getCallingUserId()).thenReturn(9L);
        Mockito.when(instance.getId()).thenReturn(7L);Mockito.when(instance.getAccountId()).thenReturn(2L);operation.setInstanceId(7);operation.setCreatedBy(9);
        JsonObject original = new JsonObject();original.addProperty("operationUuid", operation.getUuid());
        original.addProperty("keyId", java.util.UUID.nameUUIDFromBytes(("identity-key:" + operation.getUuid()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());
        original.addProperty("capsuleSha256", "a".repeat(64));original.addProperty("keySha256", "b".repeat(64));
        JsonObject snapshot = new JsonObject();snapshot.add("nativeIdentityCapsule", original);operation.setPreviousSnapshotJson(snapshot.toString());
        Mockito.when(operations.findById(77L)).thenReturn(operation);
        try (org.mockito.MockedStatic<org.apache.cloudstack.context.CallContext> contexts = Mockito.mockStatic(org.apache.cloudstack.context.CallContext.class)) {
            contexts.when(org.apache.cloudstack.context.CallContext::current).thenReturn(caller);
            Assert.assertSame(operation, current.requireCurrentSmbRecoveryOperation(instance, 77L));
            Mockito.when(account.getId()).thenReturn(3L);Assert.assertThrows(com.cloud.exception.InvalidParameterValueException.class, () -> current.requireCurrentSmbRecoveryOperation(instance, 77L));Mockito.when(account.getId()).thenReturn(2L);
            operation.setCreatedBy(10);Assert.assertThrows(com.cloud.exception.InvalidParameterValueException.class, () -> current.requireCurrentSmbRecoveryOperation(instance, 77L));operation.setCreatedBy(9);
            for (String marker : java.util.Set.of("renderedGeneration", "adServiceMaintenanceScope", "sourceRootScope", "adIdentityEffectPhase")) {
                JsonObject blocked = snapshot.deepCopy();blocked.add(marker, new JsonObject());operation.setPreviousSnapshotJson(blocked.toString());
                Assert.assertThrows(CloudRuntimeException.class, () -> current.requireCurrentSmbRecoveryOperation(instance, 77L));
            }
            operation.setPreviousSnapshotJson(snapshot.toString());current.joined = true;Assert.assertThrows(CloudRuntimeException.class, () -> current.requireCurrentSmbRecoveryOperation(instance, 77L));current.joined = false;
            current.idleRejected = true;Assert.assertThrows(CloudRuntimeException.class, () -> current.requireCurrentSmbRecoveryOperation(instance, 77L));current.idleRejected = false;
            current.formatterRejected = true;Assert.assertThrows(CloudRuntimeException.class, () -> current.requireCurrentSmbRecoveryOperation(instance, 77L));current.formatterRejected = false;
            original.addProperty("keyId", java.util.UUID.randomUUID().toString());operation.setPreviousSnapshotJson(snapshot.toString());
            Assert.assertThrows(CloudRuntimeException.class, () -> current.requireCurrentSmbRecoveryOperation(instance, 77L));Assert.assertTrue(current.calls.isEmpty());
        }
    }
    @Test public void recoveryRequestFingerprintPinsApprovalOperationHashNameRevisionAndRetryMode() {
        JsonObject parameters = new JsonObject();parameters.addProperty("instanceid", 7);parameters.addProperty("operationid", 77);
        parameters.addProperty("recoverymode", StorageSmbCurrentIdentityRecoveryProof.MODE);parameters.addProperty("currentreviewhash", "a".repeat(64));
        parameters.addProperty("maintenancewindow", true);parameters.addProperty("confirmation", "fixture");parameters.addProperty("expectedrevision", 3);parameters.addProperty("idempotencykey", "same-approved-retry");
        org.apache.cloudstack.api.command.user.storage.dataservice.RepairStorageServiceSmbIdentityCmd cmd =
                (org.apache.cloudstack.api.command.user.storage.dataservice.RepairStorageServiceSmbIdentityCmd) StorageConfigCommandBinding.bind(
                        org.apache.cloudstack.api.command.user.storage.dataservice.RepairStorageServiceSmbIdentityCmd.class, parameters);
        String fingerprint = StorageServiceRequestFingerprint.of(cmd, "ephemeral-current-request-test-key");
        for (String field : java.util.Set.of("operationid", "currentreviewhash", "confirmation", "recoverymode")) {
            JsonObject changed = parameters.deepCopy();if ("operationid".equals(field)) changed.addProperty(field, 78);
            else if ("expectedrevision".equals(field)) changed.addProperty(field, 4);else changed.addProperty(field, "changed");
            org.apache.cloudstack.api.command.user.storage.dataservice.RepairStorageServiceSmbIdentityCmd other =
                    (org.apache.cloudstack.api.command.user.storage.dataservice.RepairStorageServiceSmbIdentityCmd) StorageConfigCommandBinding.bind(
                            org.apache.cloudstack.api.command.user.storage.dataservice.RepairStorageServiceSmbIdentityCmd.class, changed);
            Assert.assertNotEquals(fingerprint, StorageServiceRequestFingerprint.of(other, "ephemeral-current-request-test-key"));
        }
        Assert.assertEquals(Long.valueOf(77), cmd.getOperationId());Assert.assertEquals("a".repeat(64), cmd.getCurrentReviewHash());
        Assert.assertEquals(Long.valueOf(3), cmd.getExpectedRevision());
    }
    @Test public void currentWrappingKeyRequiresSeparatePurposeAndSameActualRsaPublicPrivatePair() {
        CurrentManager current = currentManager();Object previous = ReflectionTestUtils.getField(com.cloud.utils.crypt.DBEncryptionUtil.class, "s_encryptor");
        Object previousChecker = ReflectionTestUtils.getField(com.cloud.utils.crypt.EncryptionSecretKeyChecker.class, "s_encryptor");
        boolean previousUse = (boolean) ReflectionTestUtils.getField(com.cloud.utils.crypt.EncryptionSecretKeyChecker.class, "s_useEncryption");
        byte[] protectedKey = null;
        try {
            com.cloud.utils.crypt.EncryptionSecretKeyChecker.initEncryptor("ephemeral-current-test-only-master");
            ReflectionTestUtils.setField(com.cloud.utils.crypt.DBEncryptionUtil.class, "s_encryptor", new com.cloud.utils.crypt.CloudStackEncryptor("ephemeral-current-test-only-master", null, com.cloud.utils.crypt.DBEncryptionUtil.class));
            java.security.KeyPair pair = StorageIdentityCapsule.wrappingKey(), other = StorageIdentityCapsule.wrappingKey();protectedKey = StorageIdentityCapsule.protectedPrivateKey(pair);
            JsonObject reference = new JsonObject();reference.addProperty("keyId", java.util.UUID.nameUUIDFromBytes(("smb-current-key:" + operation.getUuid()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());
            reference.addProperty("keySha256", StorageConfigArchive.sha256(protectedKey));reference.addProperty("publicKey", StorageIdentityCapsule.pem("PUBLIC KEY", pair.getPublic().getEncoded()));
            current.requireCurrentSmbWrappingKey(operation, reference, protectedKey);final byte[] sealed = protectedKey;
            reference.addProperty("publicKey", StorageIdentityCapsule.pem("PUBLIC KEY", other.getPublic().getEncoded()));
            Assert.assertThrows(CloudRuntimeException.class, () -> current.requireCurrentSmbWrappingKey(operation, reference, sealed));
            reference.addProperty("publicKey", StorageIdentityCapsule.pem("PUBLIC KEY", pair.getPublic().getEncoded()));
            reference.addProperty("keyId", java.util.UUID.nameUUIDFromBytes(("identity-key:" + operation.getUuid()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());
            Assert.assertThrows(CloudRuntimeException.class, () -> current.requireCurrentSmbWrappingKey(operation, reference, sealed));
        } finally {
            if (protectedKey != null) java.util.Arrays.fill(protectedKey, (byte) 0);
            ReflectionTestUtils.setField(com.cloud.utils.crypt.DBEncryptionUtil.class, "s_encryptor", previous);
            ReflectionTestUtils.setField(com.cloud.utils.crypt.EncryptionSecretKeyChecker.class, "s_encryptor", previousChecker);
            ReflectionTestUtils.setField(com.cloud.utils.crypt.EncryptionSecretKeyChecker.class, "s_useEncryption", previousUse);
        }
    }


    private static class CurrentApprovalManager extends CurrentManager {
        StorageServiceInstanceVO owned;StorageServiceOperationVO original;
        @Override protected StorageServiceInstanceVO requireInstance(Long id) {return owned;}
        @Override protected StorageServiceOperationVO requireCurrentSmbRecoveryOperation(StorageServiceInstanceVO instance, Long id) {
            if (!Long.valueOf(77).equals(id)) throw new com.cloud.exception.InvalidParameterValueException("synthetic foreign operation");return original;
        }
        @Override protected JsonObject currentSmbRecoveryContext(StorageServiceInstanceVO instance, StorageServiceOperationVO operation) {return context.deepCopy();}
        @Override protected JsonObject currentSmbRecoveryGuest(StorageServiceInstanceVO instance, String action, JsonObject request, int timeout) {
            if ("current-review".equals(action)) {JsonObject review = currentReview(context);review.addProperty("generatedEpoch", System.currentTimeMillis() / 1000.0);return review;}
            return super.currentSmbRecoveryGuest(instance, action, request, timeout);
        }
        @Override protected void requireCurrentSmbPublishedReference(StorageServiceOperationVO operation, JsonObject saved) {
            Assert.assertTrue(saved.has("currentReference"));
        }
    }
    private org.apache.cloudstack.api.command.user.storage.dataservice.RepairStorageServiceSmbIdentityCmd currentCommand(JsonObject parameters) {
        return (org.apache.cloudstack.api.command.user.storage.dataservice.RepairStorageServiceSmbIdentityCmd) StorageConfigCommandBinding.bind(
                org.apache.cloudstack.api.command.user.storage.dataservice.RepairStorageServiceSmbIdentityCmd.class, parameters);
    }
    @Test public void actualRepairEntryRejectsChangedApprovedRequestAndRetriesSameTerminalScopeWithoutStop() {
        CurrentApprovalManager current = new CurrentApprovalManager();current.owned = instance;current.original = operation;
        ReflectionTestUtils.setField(current, "storageOperationDao", operations);Mockito.when(instance.getName()).thenReturn("fixture");
        operation.setState("RECOVERY_REQUIRED");operation.setPreviousSnapshotJson("{\"nativeIdentityCapsule\":{\"immutable\":true}}");
        JsonObject parameters = new JsonObject();parameters.addProperty("instanceid", 7);parameters.addProperty("operationid", 77);
        parameters.addProperty("recoverymode", StorageSmbCurrentIdentityRecoveryProof.MODE);parameters.addProperty("currentreviewhash", "c".repeat(64));
        parameters.addProperty("maintenancewindow", true);parameters.addProperty("confirmation", "fixture");parameters.addProperty("expectedrevision", 3);parameters.addProperty("idempotencykey", "approved-current-retry");
        com.cloud.utils.db.GlobalLock lock = Mockito.mock(com.cloud.utils.db.GlobalLock.class);Mockito.when(lock.lock(120)).thenReturn(true);
        java.util.Properties properties = new java.util.Properties();properties.setProperty("db.cloud.encrypt.secret", "ephemeral-current-request-test-key");
        try (org.mockito.MockedStatic<com.cloud.utils.db.GlobalLock> locks = Mockito.mockStatic(com.cloud.utils.db.GlobalLock.class);
                org.mockito.MockedStatic<com.cloud.utils.db.DbProperties> db = Mockito.mockStatic(com.cloud.utils.db.DbProperties.class)) {
            locks.when(() -> com.cloud.utils.db.GlobalLock.getInternLock(Mockito.anyString())).thenReturn(lock);
            db.when(com.cloud.utils.db.DbProperties::getDbProperties).thenReturn(properties);
            current.repairStorageServiceSmbIdentity(currentCommand(parameters));Assert.assertEquals("ROLLED_BACK", operation.getState());
            int calls = current.calls.size();String original = operation.getPreviousSnapshotJson();
            for (String field : java.util.Set.of("currentreviewhash", "confirmation", "expectedrevision", "operationid", "idempotencykey", "recoverymode")) {
                JsonObject changed = parameters.deepCopy();
                if ("expectedrevision".equals(field)) changed.addProperty(field, 4);else if ("operationid".equals(field)) changed.addProperty(field, 78);
                else if ("currentreviewhash".equals(field)) changed.addProperty(field, "d".repeat(64));else changed.addProperty(field, "changed");
                Assert.assertThrows(RuntimeException.class, () -> current.repairStorageServiceSmbIdentity(currentCommand(changed)));
                Assert.assertEquals(calls, current.calls.size());
            }
            current.repairStorageServiceSmbIdentity(currentCommand(parameters));
            Assert.assertEquals(1, java.util.Collections.frequency(current.calls, "current-quiesce"));
            Assert.assertEquals(1, java.util.Collections.frequency(current.calls, "current-export"));
            Assert.assertEquals(original, operation.getPreviousSnapshotJson());Assert.assertEquals("ROLLED_BACK", operation.getState());
            Mockito.verify(lock, Mockito.atLeastOnce()).releaseRef();
        }
    }

    @Test public void originalCapsuleCannotBeRelabeledAsIndependentCurrentPurposeBeforeVaultRead() {
        CurrentManager current = currentManager();JsonObject saved = currentSaved(), reference = new JsonObject();
        reference.addProperty("capsuleId", operation.getUuid());saved.add("currentReference", reference);
        Assert.assertThrows(CloudRuntimeException.class, () -> current.requireCurrentSmbPublishedReference(operation, saved));
        reference.addProperty("capsuleId", java.util.UUID.nameUUIDFromBytes(("smb-current-capsule:" + operation.getUuid()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());
        Assert.assertThrows(CloudRuntimeException.class, () -> current.requireCurrentSmbPublishedReference(operation, saved));
        Assert.assertTrue(current.calls.isEmpty());
    }

    @Test public void agedApprovedReviewBeforeStopRefreshesOnlyEpochAndChangedStableFactsFailClosed() {
        CurrentManager current = currentManager();JsonObject saved = currentSaved(), approved = saved.getAsJsonObject("review").deepCopy();
        current.recoverCurrentSmbIdentity(instance, operation, saved);
        JsonObject refreshed = saved.getAsJsonObject("review").deepCopy();
        Assert.assertTrue(refreshed.get("generatedEpoch").getAsDouble() > 1000);
        refreshed.remove("generatedEpoch");approved.remove("generatedEpoch");Assert.assertEquals(approved, refreshed);
        Assert.assertEquals("ROLLED_BACK", operation.getState());Assert.assertEquals(1, java.util.Collections.frequency(current.calls, "current-quiesce"));
        CurrentManager changed = currentManager();changed.freshFactsChanged = true;JsonObject old = currentSaved();
        Assert.assertThrows(CloudRuntimeException.class, () -> changed.recoverCurrentSmbIdentity(instance, operation, old));
        Assert.assertTrue(changed.calls.isEmpty());Assert.assertEquals(1000, old.getAsJsonObject("review").get("generatedEpoch").getAsDouble(), 0);
        Assert.assertEquals("RECOVERY_REQUIRED", operation.getState());Assert.assertFalse(old.has("currentReference"));
    }

    private static class CurrentCanonicalManager extends CurrentManager {
        JsonObject frozen;int rootBindings;
        @Override protected JsonObject frozenRecoveryConfiguration(StorageServiceInstanceVO instance, StorageServiceOperationVO operation) {return frozen;}
        @Override protected long rootDesiredRevision(long instanceId) {return 3;}
        @Override protected JsonObject rootResourceBinding(StorageServiceInstanceVO instance) {
            rootBindings++;throw new CloudRuntimeException("EXACT_SOURCE_ACCEPTED_ROOT_BINDING_REQUIRED");
        }
    }
    @Test public void currentContextUsesActualCanonicalSevenFileSmbPathAndKeepsMissingOrConfiguredSourceClosed() {
        CurrentCanonicalManager current = new CurrentCanonicalManager();operation.setRevision(4);current.context.addProperty("operationUuid", operation.getUuid());Mockito.when(instance.getVmId()).thenReturn(54L);
        com.cloud.vm.dao.VMInstanceDao vms = Mockito.mock(com.cloud.vm.dao.VMInstanceDao.class);
        Mockito.when(vms.findById(54L)).thenReturn(Mockito.mock(com.cloud.vm.VMInstanceVO.class));ReflectionTestUtils.setField(current, "vmInstanceDao", vms);
        JsonObject source = new JsonObject();for (String path : StorageRenderedDesiredState.PATHS) source.add(path, com.google.gson.JsonNull.INSTANCE);
        JsonObject frozen = new JsonObject();frozen.add("generation", currentContext().get("sourceGeneration").deepCopy());
        frozen.add("configurationDesiredState", source);frozen.addProperty("configurationSha256", "a".repeat(64));current.frozen = frozen;
        // Real canonical producer key; the old unqualified SMB basename throws NPE here.
        Assert.assertEquals("desired-state/smb-share-apply.json", StorageRenderedDesiredState.PROTOCOL_PATHS.get(StorageServiceInstance.Protocol.SMB));
        CloudRuntimeException accepted = Assert.assertThrows(CloudRuntimeException.class, () -> current.currentSmbRecoveryContext(instance, operation));
        Assert.assertEquals("EXACT_SOURCE_ACCEPTED_ROOT_BINDING_REQUIRED", accepted.getMessage());Assert.assertEquals(1, current.rootBindings);
        source.remove("desired-state/smb-share-apply.json");
        CloudRuntimeException missing = Assert.assertThrows(CloudRuntimeException.class, () -> current.currentSmbRecoveryContext(instance, operation));
        Assert.assertTrue(missing.getMessage().contains("original absent SMB"));Assert.assertEquals(1, current.rootBindings);
        source.add("desired-state/smb-share-apply.json", new JsonObject());
        CloudRuntimeException configured = Assert.assertThrows(CloudRuntimeException.class, () -> current.currentSmbRecoveryContext(instance, operation));
        Assert.assertTrue(configured.getMessage().contains("original absent SMB"));Assert.assertEquals(1, current.rootBindings);
    }

    private static class CurrentRuntimeContextManager extends CurrentManager {
        JsonObject frozen, root, pin;
        @Override protected JsonObject frozenRecoveryConfiguration(StorageServiceInstanceVO instance, StorageServiceOperationVO operation) {return frozen;}
        @Override protected long rootDesiredRevision(long instanceId) {return 3;}
        @Override protected JsonObject rootResourceBinding(StorageServiceInstanceVO instance) {return root;}
        @Override protected JsonObject configurationCloneRuntimePin(String bundleUuid) {return pin;}
    }
    @Test public void actualCurrentContextProjectsCompletedReadbackTransactionAndRejectsMissingNonstringOrPathIdentifier() {
        CurrentRuntimeContextManager current = new CurrentRuntimeContextManager();operation.setRevision(4);current.context.addProperty("operationUuid", operation.getUuid());
        Mockito.when(instance.getId()).thenReturn(7L);Mockito.when(instance.getVmId()).thenReturn(54L);Mockito.when(instance.getAccountId()).thenReturn(2L);
        Mockito.when(instance.getCurrentRuntimeBundleId()).thenReturn(88L);
        JsonObject source = new JsonObject();for (String path : StorageRenderedDesiredState.PATHS) source.add(path, com.google.gson.JsonNull.INSTANCE);
        current.frozen = new JsonObject();current.frozen.add("generation", current.context.get("sourceGeneration").deepCopy());
        current.frozen.add("configurationDesiredState", source);current.frozen.addProperty("configurationSha256", "a".repeat(64));
        current.root = new JsonObject();current.root.addProperty("rootVolumeId", 99);current.root.addProperty("rootVolumeUuid", "f24d1253-cbe3-46d7-8b77-055bd17ed1bc");
        com.cloud.vm.dao.VMInstanceDao vms = Mockito.mock(com.cloud.vm.dao.VMInstanceDao.class);com.cloud.vm.VMInstanceVO vm = Mockito.mock(com.cloud.vm.VMInstanceVO.class);
        Mockito.when(vm.getState()).thenReturn(com.cloud.vm.VirtualMachine.State.Running);Mockito.when(vm.getAccountId()).thenReturn(2L);
        Mockito.when(vm.getUuid()).thenReturn("9e57d31b-bb68-4ba3-a377-b0acbc89c22d");Mockito.when(vms.findById(54L)).thenReturn(vm);ReflectionTestUtils.setField(current, "vmInstanceDao", vms);
        com.cloud.storage.dao.VolumeDao volumes = Mockito.mock(com.cloud.storage.dao.VolumeDao.class);com.cloud.storage.VolumeVO root = Mockito.mock(com.cloud.storage.VolumeVO.class);
        Mockito.when(root.getAccountId()).thenReturn(2L);Mockito.when(root.getInstanceId()).thenReturn(54L);Mockito.when(root.getState()).thenReturn(com.cloud.storage.Volume.State.Ready);
        Mockito.when(volumes.findById(99L)).thenReturn(root);ReflectionTestUtils.setField(current, "volumeDao", volumes);
        org.apache.cloudstack.storage.sharedfs.dao.SharedFSDao shared = Mockito.mock(org.apache.cloudstack.storage.sharedfs.dao.SharedFSDao.class);
        Mockito.when(shared.findByVm(54L)).thenReturn(Mockito.mock(org.apache.cloudstack.storage.sharedfs.SharedFSVO.class));ReflectionTestUtils.setField(current, "sharedFSDao", shared);
        org.apache.cloudstack.storage.dataservice.dao.StorageServiceRuntimeBundleDao bundles = Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageServiceRuntimeBundleDao.class);
        StorageServiceRuntimeBundleVO bundle = Mockito.mock(StorageServiceRuntimeBundleVO.class);Mockito.when(bundle.getUuid()).thenReturn("runtime-bundle-fixture");
        Mockito.when(bundle.getVersion()).thenReturn("actual-completed-runtime-fixture");Mockito.when(bundle.getSha256()).thenReturn("b".repeat(64));Mockito.when(bundle.getManifestSha256()).thenReturn("c".repeat(64));
        Mockito.when(bundles.findById(88L)).thenReturn(bundle);ReflectionTestUtils.setField(current, "storageRuntimeBundleDao", bundles);
        current.pin = new JsonObject();current.pin.addProperty("bundleUuid", bundle.getUuid());current.pin.addProperty("bundleSha256", bundle.getSha256());
        current.pin.addProperty("manifestSha256", bundle.getManifestSha256());current.pin.addProperty("expectedCliSha256", "d".repeat(64));
        JsonObject proof = new JsonObject();for (String field : java.util.Set.of("readOnly", "signedRuntimeVerified", "nativeFileHashesVerified")) proof.addProperty(field, true);
        JsonObject readbackPin = new JsonObject();readbackPin.addProperty("bundleUuid", bundle.getUuid());readbackPin.addProperty("archiveSha256", bundle.getSha256());
        readbackPin.addProperty("manifestSha256", bundle.getManifestSha256());proof.add("runtimePin", readbackPin);proof.addProperty("actualCliSha256", "d".repeat(64));
        proof.addProperty("updaterSha256", "e".repeat(64));proof.addProperty("transactionId", "runtime-61f56d8c-4afb-47f4-83b9-22af32672684");
        StorageServiceRuntimeUpgradeManager runtime = Mockito.mock(StorageServiceRuntimeUpgradeManager.class);
        Mockito.when(runtime.freshSignedRuntimeValidationProof(7L, "d".repeat(64))).thenAnswer(invocation -> proof.deepCopy());ReflectionTestUtils.setField(current, "runtimeUpgradeManager", runtime);
        JsonObject context = current.currentSmbRecoveryContext(instance, operation);
        Assert.assertEquals(java.util.Set.of("bundleVersion", "archiveSha256", "manifestSha256", "updaterSha256", "transactionId"), context.getAsJsonObject("runtimePin").keySet());
        Assert.assertEquals(proof.get("transactionId"), context.getAsJsonObject("runtimePin").get("transactionId"));
        Assert.assertFalse(context.getAsJsonObject("runtimePin").get("transactionId").getAsString().equals("smb-current-" + operation.getUuid()));
        for (com.google.gson.JsonElement invalid : java.util.List.of(new com.google.gson.JsonPrimitive(123), new com.google.gson.JsonPrimitive("../state"),
                new com.google.gson.JsonPrimitive("/runtime/path"), new com.google.gson.JsonPrimitive("runtime:wrong"), new com.google.gson.JsonPrimitive("x".repeat(129)))) {
            proof.add("transactionId", invalid);Assert.assertThrows(CloudRuntimeException.class, () -> current.currentSmbRecoveryContext(instance, operation));
        }
        proof.remove("transactionId");Assert.assertThrows(CloudRuntimeException.class, () -> current.currentSmbRecoveryContext(instance, operation));
        Assert.assertTrue(current.calls.isEmpty());
    }

    @Test public void currentRawSamNamespacesPreserveBothDistinctExistingSidsWithoutWirePrefixCollapse() {
        JsonObject context = currentContext(), review = currentReview(context);com.google.gson.JsonArray namespaces = review.getAsJsonObject("currentFacts").getAsJsonArray("publicNamespaceSids");
        String raw = "EPIC898-S-B6AA-ALL4-F1-20261009-RAW-SAM-HOST-NAMESPACE52";
        Assert.assertTrue(raw.length() > 15 && raw.length() <= 63);
        JsonObject configured = new JsonObject();configured.addProperty("samNamespace", raw);configured.addProperty("machineSid", "S-1-5-21-111-222-333");
        JsonObject derived = new JsonObject();derived.addProperty("samNamespace", "STOR3480BB2C99");derived.addProperty("machineSid", "S-1-5-21-444-555-666");
        namespaces.remove(0);namespaces.add(configured);namespaces.add(derived);
        StorageSmbCurrentIdentityRecoveryProof.review(context, review, 1000);
        JsonObject facts = StorageSmbCurrentIdentityRecoveryProof.publicFacts(review);
        Assert.assertTrue(StorageSmbCurrentIdentityRecoveryProof.literal(facts, "namespaceObserved", true));
        Assert.assertTrue(StorageSmbCurrentIdentityRecoveryProof.literal(facts, "loadedDaemonSidVerified", false));
        Assert.assertFalse(facts.toString().contains(raw));Assert.assertFalse(facts.toString().contains("S-1-5-21"));
        Assert.assertEquals(raw, namespaces.get(0).getAsJsonObject().get("samNamespace").getAsString());
        Assert.assertNotEquals(namespaces.get(0).getAsJsonObject().get("machineSid"), namespaces.get(1).getAsJsonObject().get("machineSid"));
        JsonObject reordered = review.deepCopy();com.google.gson.JsonArray reverse = new com.google.gson.JsonArray();reverse.add(derived.deepCopy());reverse.add(configured.deepCopy());
        reordered.getAsJsonObject("currentFacts").add("publicNamespaceSids", reverse);
        StorageSmbCurrentIdentityRecoveryProof.review(context, reordered, 1000);
        // No sorting or wire truncation occurs in this consumer; the native approval hash binds canonical order.
        Assert.assertNotEquals(review.get("currentFacts"), reordered.get("currentFacts"));
        for (String invalid : java.util.List.of("x".repeat(64), "ROOT/NAME", "ROOT\\\\NAME", "ROOT NAME", "ROOT" + (char) 0 + "NAME", "lowercase")) {
            JsonObject wrong = review.deepCopy();wrong.getAsJsonObject("currentFacts").getAsJsonArray("publicNamespaceSids").get(0).getAsJsonObject().addProperty("samNamespace", invalid);
            Assert.assertThrows(CloudRuntimeException.class, () -> StorageSmbCurrentIdentityRecoveryProof.review(context, wrong, 1000));
        }
        JsonObject oldWire = review.deepCopy(), oldRow = oldWire.getAsJsonObject("currentFacts").getAsJsonArray("publicNamespaceSids").get(0).getAsJsonObject();
        oldRow.remove("samNamespace");oldRow.addProperty("netbiosName", raw.substring(0, 15));
        Assert.assertThrows(CloudRuntimeException.class, () -> StorageSmbCurrentIdentityRecoveryProof.review(context, oldWire, 1000));
        JsonObject duplicate = review.deepCopy();duplicate.getAsJsonObject("currentFacts").getAsJsonArray("publicNamespaceSids").add(configured.deepCopy());
        Assert.assertThrows(CloudRuntimeException.class, () -> StorageSmbCurrentIdentityRecoveryProof.review(context, duplicate, 1000));
        JsonObject missing = review.deepCopy();missing.getAsJsonObject("currentFacts").getAsJsonArray("publicNamespaceSids").get(0).getAsJsonObject().remove("samNamespace");
        Assert.assertThrows(CloudRuntimeException.class, () -> StorageSmbCurrentIdentityRecoveryProof.review(context, missing, 1000));
        JsonObject overflow = review.deepCopy();overflow.getAsJsonObject("currentFacts").getAsJsonArray("publicNamespaceSids").get(0).getAsJsonObject().addProperty("machineSid", "S-1-5-21-4294967296-222-333");
        Assert.assertThrows(CloudRuntimeException.class, () -> StorageSmbCurrentIdentityRecoveryProof.review(context, overflow, 1000));
    }

}
