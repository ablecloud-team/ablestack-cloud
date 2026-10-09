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
public class StorageServiceRuntimeHealthGateTest {
    private final StorageServiceRuntimeUpgradeManagerImpl manager=new StorageServiceRuntimeUpgradeManagerImpl();
    @Test public void healthyLiveResultMayBePromoted() {
        Assert.assertTrue(manager.runtimeHealthVerified(new StorageServiceGuestCommandResult(true,"done","{\"success\":true,\"status\":\"ok\"}")));
    }
    @Test public void processExitSuccessDoesNotHideDegradedRuntime() {
        Assert.assertFalse(manager.runtimeHealthVerified(new StorageServiceGuestCommandResult(true,"done","{\"success\":true,\"status\":\"degraded\"}")));
    }
    @Test public void malformedOrIncompleteHealthResultsTriggerRollback() {
        Assert.assertFalse(manager.runtimeHealthVerified(new StorageServiceGuestCommandResult(true,"done","invalid")));
        Assert.assertFalse(manager.runtimeHealthVerified(new StorageServiceGuestCommandResult(true,"done","{}")));
        Assert.assertFalse(manager.runtimeHealthVerified(new StorageServiceGuestCommandResult(false,"failed",null)));
    }
    static com.google.gson.JsonObject pausedContext() {
        com.google.gson.JsonObject writer = new com.google.gson.JsonObject();writer.addProperty("instanceUuid", "3480bb2c-99ee-42f2-91cf-715ded5dd35f");
        writer.addProperty("operationUuid", "fef126a6-936a-4936-b22c-201b7e5b9b4d");writer.addProperty("revision", 5);
        return StorageLocalSourceIdentityProof.context(writer, pausedBefore());
    }
    static com.google.gson.JsonObject pausedBefore() {
        com.google.gson.JsonObject source = new com.google.gson.JsonObject();source.addProperty("success", true);source.addProperty("generationSupported", true);
        source.addProperty("generationStatus", "IN_SYNC");source.addProperty("configurationSha256", "a".repeat(64));source.addProperty("bootId", "0fce7e4a-7f46-4b33-9a6e-0137cdbcf8da");
        com.google.gson.JsonObject generation = new com.google.gson.JsonObject();generation.addProperty("instanceUuid", "3480bb2c-99ee-42f2-91cf-715ded5dd35f");
        generation.addProperty("operationUuid", "219b09d5-fe32-40b0-befa-d919c6c964fe");generation.addProperty("revision", 4);generation.addProperty("configurationSha256", "a".repeat(64));source.add("generation", generation);
        com.google.gson.JsonObject files = new com.google.gson.JsonObject();for (String path : StorageRenderedDesiredState.PATHS) files.add(path, com.google.gson.JsonNull.INSTANCE);
        files.add("desired-state/smb-share-apply.json", new com.google.gson.JsonObject());source.add("configurationDesiredState", files);return source;
    }
    static com.google.gson.JsonObject pausedHealth() {
        return com.google.gson.JsonParser.parseString(
                "{\"success\":true,\"status\":\"degraded\",\"services\":{\"iscsiTarget\":\"inactive\",\"nfs\":\"ok\",\"nfsGanesha\":\"ok\",\"nmbd\":\"inactive\",\"qemuGuestAgent\":\"active\",\"smbd\":\"failed\",\"winbind\":\"inactiv" +
                "e\"},\"desiredState\":{\"iscsiTargets\":false,\"nfsQuotas\":true,\"nvmeofSubsystems\":false,\"smbDomain\":false,\"smbDomainError\":false,\"smbQuotas\":true},\"listenPorts\":{\"iscsi\":false,\"iscsiPor" +
                "ts\":{\"3260\":false},\"nfs\":true,\"nvmeof\":true,\"nvmeofPorts\":{},\"smb\":false},\"nfsGanesha\":{\"active\":1,\"configured\":1,\"listening\":true,\"rpcbindRequired\":false,\"rpcbindActive\":true,\"sta" +
                "tus\":\"ok\"},\"smbRuntime\":{\"available\":true,\"configured\":1,\"listening\":false,\"runtimeEndpoints\":[{\"available\":true,\"listenIp\":\"0.0.0.0\",\"listenerOwned\":false,\"listening\":false,\"port\"" +
                ":445,\"tcpReady\":false}]}}").getAsJsonObject();
    }
    static com.google.gson.JsonObject stoppedStatus() {
        com.google.gson.JsonObject context = pausedContext(), value = new com.google.gson.JsonObject();value.addProperty("success", true);
        value.addProperty("checkpointSupported", true);value.addProperty("journalPresent", true);value.add("scope", StorageLocalSourceIdentityProof.scope(context));
        value.add("sourceGeneration", context.get("sourceGeneration"));value.add("sourceConfigurationSha256", context.get("sourceConfigurationSha256"));value.add("bootId", context.get("expectedBootId"));
        com.google.gson.JsonObject proof = new com.google.gson.JsonObject();proof.addProperty("kind", "LOCAL_SOURCE_STOPPED_RUNTIME_QUARANTINE");proof.add("scope", StorageLocalSourceIdentityProof.scope(context));
        proof.add("sourceGeneration", context.get("sourceGeneration"));proof.add("sourceConfigurationSha256", context.get("sourceConfigurationSha256"));proof.add("bootId", context.get("expectedBootId"));
        proof.addProperty("originalCliSha256", "c".repeat(64));proof.addProperty("currentCliSha256", "b".repeat(64));
        for (String field : java.util.Set.of("sourceStoppedVerified", "stoppedMetadataVerified", "privateHoldersAbsent", "cipherAbsent", "sourceCanonicalUnchanged", "originalSignedCodeVerified", "currentSignedCodeVerified")) proof.addProperty(field, true);
        proof.addProperty("serviceAvailabilityVerified", false);value.add("localSourceStoppedRuntimeProof", proof);return value;
    }
    @Test public void pausedRuntimeCodeProofNeverChangesGenericDegradedHealthOrClaimsAvailability() {
        StorageServiceGuestCommandResult health = new StorageServiceGuestCommandResult(true, "public", pausedHealth().toString());
        Assert.assertFalse(manager.runtimeHealthVerified(health));StorageLocalSourceIdentityProof.requireOnlyStoppedSmbHealth(health);
        com.google.gson.JsonObject proof = StorageLocalSourceIdentityProof.runtimeQuarantine(pausedContext(), "b".repeat(64), stoppedStatus());
        Assert.assertFalse(proof.get("serviceAvailabilityVerified").getAsBoolean());Assert.assertEquals(15, proof.size());
    }
    @Test public void pausedCodeRejectsUnrelatedProtocolGuestOrIdentityFailures() {
        for (String field : java.util.Set.of("nfs", "iscsi", "nvmeof")) {
            com.google.gson.JsonObject bad = pausedHealth();bad.getAsJsonObject("listenPorts").addProperty(field, false);
            if ("iscsi".equals(field)) bad.getAsJsonObject("desiredState").addProperty("iscsiTargets", true);
            if ("nvmeof".equals(field)) bad.getAsJsonObject("desiredState").addProperty("nvmeofSubsystems", true);
            Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class, () -> StorageLocalSourceIdentityProof.requireOnlyStoppedSmbHealth(new StorageServiceGuestCommandResult(true, "public", bad.toString())));
        }
        com.google.gson.JsonObject bad = pausedHealth();bad.getAsJsonObject("services").addProperty("qemuGuestAgent", "failed");
        Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class, () -> StorageLocalSourceIdentityProof.requireOnlyStoppedSmbHealth(new StorageServiceGuestCommandResult(true, "public", bad.toString())));
        com.google.gson.JsonObject domain = pausedHealth();domain.getAsJsonObject("desiredState").addProperty("smbDomainError", true);
        Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class, () -> StorageLocalSourceIdentityProof.requireOnlyStoppedSmbHealth(new StorageServiceGuestCommandResult(true, "public", domain.toString())));
    }
    @Test public void stoppedRuntimeProofRejectsStringFlagsCipherUnknownFieldsAndForeignSourceOrCode() {
        for (String field : java.util.Set.of("cipherAbsent", "privateHoldersAbsent", "originalSignedCodeVerified", "currentSignedCodeVerified")) {
            com.google.gson.JsonObject bad = stoppedStatus();bad.getAsJsonObject("localSourceStoppedRuntimeProof").addProperty(field, "true");
            Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class, () -> StorageLocalSourceIdentityProof.runtimeQuarantine(pausedContext(), "b".repeat(64), bad));
        }
        for (String field : java.util.Set.of("sourceConfigurationSha256", "currentCliSha256", "bootId")) {
            com.google.gson.JsonObject bad = stoppedStatus();bad.getAsJsonObject("localSourceStoppedRuntimeProof").addProperty(field, "f".repeat(64));
            Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class, () -> StorageLocalSourceIdentityProof.runtimeQuarantine(pausedContext(), "b".repeat(64), bad));
        }
        com.google.gson.JsonObject unknown = stoppedStatus();unknown.getAsJsonObject("localSourceStoppedRuntimeProof").addProperty("pauseApproved", true);
        Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class, () -> StorageLocalSourceIdentityProof.runtimeQuarantine(pausedContext(), "b".repeat(64), unknown));
        com.google.gson.JsonObject availability = stoppedStatus();availability.getAsJsonObject("localSourceStoppedRuntimeProof").addProperty("serviceAvailabilityVerified", true);
        Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class, () -> StorageLocalSourceIdentityProof.runtimeQuarantine(pausedContext(), "b".repeat(64), availability));
    }
    private static class PausedManager extends StorageServiceRuntimeUpgradeManagerImpl {
        final java.util.List<String> calls = new java.util.ArrayList<>();boolean formatterBusy, rootChanged, badReadback;
        @Override protected void requireRuntimeActivationSafety(StorageServiceInstanceVO instance) {calls.add("data-idle");if (formatterBusy) throw new com.cloud.utils.exception.CloudRuntimeException("synthetic formatter");}
        @Override protected com.google.gson.JsonObject sourceRootBinding(StorageServiceInstanceVO instance) {
            com.google.gson.JsonObject value = new com.google.gson.JsonObject();value.addProperty("rootVolumeUuid", rootChanged ? "other" : "own");return value;
        }
        @Override protected com.google.gson.JsonObject runtimeStoppedSourceContext(StorageServiceInstanceVO instance) {calls.add("db-intent");return pausedContext();}
        @Override protected com.google.gson.JsonObject invoke(StorageServiceInstanceVO instance, com.cloud.agent.api.StorageServiceRuntimeOperation operation, String transaction, com.google.gson.JsonObject request) {
            Assert.assertEquals(com.cloud.agent.api.StorageServiceRuntimeOperation.READBACK, operation);Assert.assertEquals("verified-tx", transaction);calls.add("signed-readback");
            com.google.gson.JsonObject value = new com.google.gson.JsonObject();for (String field : java.util.Set.of("success", "signedRuntimeVerified", "installedFilesVerified", "entrypointsVerified")) value.addProperty(field, !badReadback);
            value.addProperty("currentVersion", "signed-target");value.addProperty("archiveSha256", "d".repeat(64));value.addProperty("manifestSha256", "e".repeat(64));return value;
        }
    }
    @Test public void postActivationCodeAdmissionUsesExactTargetReadbackThenReadonlyOriginalScopeAndFailsClosed() {
        PausedManager paused = new PausedManager();StorageServiceInstanceVO instance = org.mockito.Mockito.mock(StorageServiceInstanceVO.class);org.mockito.Mockito.when(instance.getVmId()).thenReturn(7L);
        StorageServiceRuntimeUpgradeVO upgrade = org.mockito.Mockito.mock(StorageServiceRuntimeUpgradeVO.class);org.mockito.Mockito.when(upgrade.getTransactionId()).thenReturn("verified-tx");
        org.mockito.Mockito.when(upgrade.getPreflightJson()).thenReturn("{\"sourceSignedRuntime\":{\"sourceRootBinding\":{\"rootVolumeUuid\":\"own\"}}}");
        StorageServiceRuntimeBundleVO bundle = org.mockito.Mockito.mock(StorageServiceRuntimeBundleVO.class);org.mockito.Mockito.when(bundle.getVersion()).thenReturn("signed-target");
        org.mockito.Mockito.when(bundle.getSha256()).thenReturn("d".repeat(64));org.mockito.Mockito.when(bundle.getManifestSha256()).thenReturn("e".repeat(64));
        com.google.gson.JsonObject manifest = com.google.gson.JsonParser.parseString("{\"files\":[{\"path\":\"ablestack-storagectl\",\"sha256\":\"" + "b".repeat(64) + "\",\"mode\":\"0755\",\"owner\":\"root\",\"group\":\"root\"}]}").getAsJsonObject();
        StorageServiceGuestCommandDispatcher dispatcher = org.mockito.Mockito.mock(StorageServiceGuestCommandDispatcher.class);
        org.mockito.Mockito.when(dispatcher.dispatch(org.mockito.Mockito.any())).thenAnswer(call -> {
            StorageServiceGuestCommand command = call.getArgument(0);Assert.assertEquals("smb identity local-source-status", command.getOperation());
            Assert.assertEquals(pausedContext(), com.google.gson.JsonParser.parseString(command.getPayload()));paused.calls.add("readonly-status");
            return new StorageServiceGuestCommandResult(true, "public", stoppedStatus().toString());
        });
        org.springframework.test.util.ReflectionTestUtils.setField(paused, "guestCommandDispatcher", dispatcher);
        StorageServiceGuestCommandResult health = new StorageServiceGuestCommandResult(true, "public", pausedHealth().toString());
        Assert.assertNotNull(paused.runtimeStoppedSourceCodeProof(instance, upgrade, bundle, manifest, health));
        Assert.assertEquals(java.util.List.of("data-idle", "db-intent", "signed-readback", "readonly-status", "db-intent"), paused.calls);
        paused.badReadback = true;Assert.assertNull(paused.runtimeStoppedSourceCodeProof(instance, upgrade, bundle, manifest, health));
        paused.badReadback = false;paused.formatterBusy = true;Assert.assertNull(paused.runtimeStoppedSourceCodeProof(instance, upgrade, bundle, manifest, health));
        paused.formatterBusy = false;paused.rootChanged = true;Assert.assertNull(paused.runtimeStoppedSourceCodeProof(instance, upgrade, bundle, manifest, health));
    }

    private static class SourceContextManager extends StorageServiceRuntimeUpgradeManagerImpl {
        @Override protected java.util.Set<String> requiredRuntimeFeatures(StorageServiceInstanceVO instance) {return java.util.Collections.emptySet();}
    }
    @Test public void realPrivateDbIntentAdmissionUsesOnlyUniqueOrdinaryStoppedSourceAndRejectsPurposeOrPublicationChanges() {
        SourceContextManager actual = new SourceContextManager();
        StorageServiceInstanceVO instance = org.mockito.Mockito.mock(StorageServiceInstanceVO.class);org.mockito.Mockito.when(instance.getId()).thenReturn(7L);
        org.mockito.Mockito.when(instance.getUuid()).thenReturn(pausedContext().get("instanceUuid").getAsString());
        StorageServiceOperationVO row = new StorageServiceOperationVO();row.setInstanceId(7L);row.setRevision(5);row.setAction("createstoragesmbaclresponse");row.setState("RECOVERY_REQUIRED");row.setResultJson("{}");
        org.springframework.test.util.ReflectionTestUtils.setField(row, "uuid", pausedContext().get("operationUuid").getAsString());
        com.google.gson.JsonObject intent = new com.google.gson.JsonObject();intent.add("context", pausedContext());intent.add("source", pausedBefore());intent.addProperty("exportAttempted", true);
        intent.addProperty("sourceProtocol", "SMB");intent.add("authReplayDomains", new com.google.gson.JsonArray());
        com.google.gson.JsonObject key = new com.google.gson.JsonObject();key.addProperty("keyId", java.util.UUID.nameUUIDFromBytes(("local-source-key:" + row.getUuid()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());
        key.addProperty("keySha256", "d".repeat(64));key.addProperty("publicKey", "-----BEGIN PUBLIC KEY-----\nCONTROLLED_PUBLIC_ONLY\n-----END PUBLIC KEY-----\n");intent.add("keyReference", key);
        com.google.gson.JsonObject source = new com.google.gson.JsonObject();source.add("localCheckpointIntent", intent);source.add("nativeDesiredState", pausedBefore().get("configurationDesiredState"));
        com.google.gson.JsonObject nativeGeneration = new com.google.gson.JsonObject();nativeGeneration.add("previous", pausedBefore().get("generation"));source.add("nativeGeneration", nativeGeneration);
        source.add("renderedGeneration", com.google.gson.JsonNull.INSTANCE);row.setPreviousSnapshotJson(source.toString());
        org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao dao = org.mockito.Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao.class);
        org.mockito.Mockito.when(dao.listByInstance(7L)).thenReturn(java.util.List.of(row));org.springframework.test.util.ReflectionTestUtils.setField(actual, "rootWriterDao", dao);
        Assert.assertEquals(pausedContext(), actual.runtimeStoppedSourceContext(instance));
        com.google.gson.JsonObject readonly = source.deepCopy();readonly.add("renderedGeneration", new com.google.gson.JsonObject());row.setPreviousSnapshotJson(readonly.toString());
        Assert.assertEquals(pausedContext(), actual.runtimeStoppedSourceContext(instance));
        for (String field : java.util.Set.of("nativeIdentityCapsule", "adServiceSource", "adIdentityEffectPhase", "adSamBootstrapAttempted", "adServiceMaintenanceScope", "rootScope")) {
            com.google.gson.JsonObject wrong = source.deepCopy();wrong.add(field, new com.google.gson.JsonObject());row.setPreviousSnapshotJson(wrong.toString());
            Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class, () -> actual.runtimeStoppedSourceContext(instance));
        }
        for (String field : java.util.Set.of("rootScope", "receipt", "retainedRootAuthorization", "importedRootAuthorization")) {
            com.google.gson.JsonObject wrong = source.deepCopy(), render = new com.google.gson.JsonObject();render.add(field, new com.google.gson.JsonObject());wrong.add("renderedGeneration", render);row.setPreviousSnapshotJson(wrong.toString());
            Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class, () -> actual.runtimeStoppedSourceContext(instance));
        }
        for (String field : java.util.Set.of("context", "keyReference")) {
            com.google.gson.JsonObject wrong = source.deepCopy();wrong.getAsJsonObject("localCheckpointIntent").remove(field);row.setPreviousSnapshotJson(wrong.toString());
            Assert.assertThrows(RuntimeException.class, () -> actual.runtimeStoppedSourceContext(instance));
        }
        com.google.gson.JsonObject foreign = source.deepCopy();foreign.getAsJsonObject("localCheckpointIntent").getAsJsonObject("context").addProperty("instanceUuid", java.util.UUID.randomUUID().toString());row.setPreviousSnapshotJson(foreign.toString());
        Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class, () -> actual.runtimeStoppedSourceContext(instance));
        row.setPreviousSnapshotJson(source.toString());row.setInstanceId(99);
        Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class, () -> actual.runtimeStoppedSourceContext(instance));row.setInstanceId(7);
        for (String action : java.util.Set.of("ROOT_TEMPLATE_FORWARD", "RUNTIME_UPGRADE", "SERVICE_MAINTENANCE")) {
            row.setAction(action);Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class, () -> actual.runtimeStoppedSourceContext(instance));
        }
        row.setAction("createstoragesmbaclresponse");row.setState("COMPLETE");Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class, () -> actual.runtimeStoppedSourceContext(instance));row.setState("RECOVERY_REQUIRED");
        StorageServiceOperationVO another = new StorageServiceOperationVO();another.setInstanceId(7);another.setState("RECOVERY_REQUIRED");another.setAction("create");
        org.mockito.Mockito.when(dao.listByInstance(7L)).thenReturn(java.util.List.of(row, another));Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class, () -> actual.runtimeStoppedSourceContext(instance));
        org.mockito.Mockito.when(dao.listByInstance(7L)).thenReturn(java.util.List.of(row));Assert.assertEquals(pausedContext(), actual.runtimeStoppedSourceContext(instance));
    }

}
