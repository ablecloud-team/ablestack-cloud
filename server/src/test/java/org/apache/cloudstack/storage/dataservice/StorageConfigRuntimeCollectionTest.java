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
import com.google.gson.JsonObject;
public class StorageConfigRuntimeCollectionTest {
    private JsonObject collectors(boolean inventory, boolean health, boolean sessions) {
        JsonObject all = new JsonObject();String[] names = {"inventory", "health", "sessions"};
        boolean[] values = {inventory, health, sessions};
        for (int i = 0; i < names.length; i++) { JsonObject value = new JsonObject();value.addProperty("success", values[i]);all.add(names[i], value); }
        return all;
    }
    @Test public void stoppedOrUnreachableRuntimeIsUnavailableRatherThanPartial() {
        Assert.assertEquals("UNAVAILABLE", StorageServiceConfiguration.runtimeCollectionStatus(true, collectors(false, false, false)));
        Assert.assertEquals("UNAVAILABLE", StorageServiceConfiguration.runtimeCollectionStatus(true, new JsonObject()));
    }
    @Test public void oneMissingCollectorCannotClaimCompleteRuntime() {
        Assert.assertEquals("PARTIAL", StorageServiceConfiguration.runtimeCollectionStatus(true, collectors(true, false, true)));
        JsonObject incomplete = collectors(true, true, true);incomplete.remove("sessions");
        Assert.assertEquals("PARTIAL", StorageServiceConfiguration.runtimeCollectionStatus(true, incomplete));
    }
    @Test public void explicitlySkippedRuntimeIsNotAnAvailabilityFailure() {
        Assert.assertEquals("NOT_REQUESTED", StorageServiceConfiguration.runtimeCollectionStatus(false, new JsonObject()));
        Assert.assertEquals("AVAILABLE", StorageServiceConfiguration.runtimeCollectionStatus(true, collectors(true, true, true)));
    }

    /** Real backup producer/DTO/store; DAO locking deliberately returns a fresh, separately hydrated VO. */
    private static final class BackupFixture implements AutoCloseable {
        final java.nio.file.Path directory;
        final StorageConfigArtifactStore store;
        final StorageServiceInstanceVO instance = new StorageServiceInstanceVO();
        final java.util.concurrent.atomic.AtomicReference<StorageConfigArtifactVO> database = new java.util.concurrent.atomic.AtomicReference<>();
        final org.apache.cloudstack.storage.dataservice.dao.StorageConfigArtifactDao artifacts = org.mockito.Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageConfigArtifactDao.class);
        final org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao operations = org.mockito.Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao.class);
        final com.google.gson.Gson gson = new com.google.gson.Gson();
        final org.mockito.MockedStatic<com.cloud.utils.db.Transaction> transactions;
        final org.mockito.MockedStatic<com.cloud.utils.db.GlobalLock> locks;
        final StorageServiceConfiguration configuration;
        final StorageConfigRequest request;
        final StorageServiceOperationVO readOnly = new StorageServiceOperationVO();
        boolean failUpdate;boolean failCompletion;boolean foreignCompletionScope;boolean runtimeUnavailable;boolean runtimeDegraded;boolean admissionUnavailable;boolean admissionChanged;int admissionCalls;boolean managedBackup;int admittedCalls;
        StorageConfigArtifactVO activePoint;
        BackupFixture() throws Exception {
            directory=java.nio.file.Files.createTempDirectory("storage-backup-real-producer-");
            java.nio.file.Files.setPosixFilePermissions(directory,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
            store=new StorageConfigArtifactStore(directory);
            org.springframework.test.util.ReflectionTestUtils.setField(instance,"id",7L);instance.setName("owned-source");
            com.cloud.user.User user=org.mockito.Mockito.mock(com.cloud.user.User.class);org.mockito.Mockito.when(user.getId()).thenReturn(2L);
            org.apache.cloudstack.context.CallContext.register(user,org.mockito.Mockito.mock(com.cloud.user.Account.class));
            transactions=org.mockito.Mockito.mockStatic(com.cloud.utils.db.Transaction.class);
            transactions.when(()->com.cloud.utils.db.Transaction.execute(org.mockito.ArgumentMatchers.any(com.cloud.utils.db.TransactionCallback.class)))
                    .thenAnswer(call->((com.cloud.utils.db.TransactionCallback<?>)call.getArgument(0)).doInTransaction(null));
            locks=org.mockito.Mockito.mockStatic(com.cloud.utils.db.GlobalLock.class);
            com.cloud.utils.db.GlobalLock lock=org.mockito.Mockito.mock(com.cloud.utils.db.GlobalLock.class);
            org.mockito.Mockito.when(lock.lock(120)).thenReturn(true);
            locks.when(()->com.cloud.utils.db.GlobalLock.getInternLock("StorageServiceWriter-7")).thenReturn(lock);
            org.mockito.Mockito.when(artifacts.persist(org.mockito.ArgumentMatchers.any())).thenAnswer(call->{
                StorageConfigArtifactVO created=call.getArgument(0);org.springframework.test.util.ReflectionTestUtils.setField(created,"id",11L);
                database.set(copy(created));return created;
            });
            org.mockito.Mockito.when(artifacts.lockRow(11L,true)).thenAnswer(call->{
                StorageConfigArtifactVO locked=copy(database.get());
                if(foreignCompletionScope&&locked.getMetadataJson()!=null&&locked.getMetadataJson().contains("COLLECTING_SESSIONS"))locked.setInstanceId(99);
                return locked;
            });
            org.mockito.Mockito.when(artifacts.findByUuid(org.mockito.ArgumentMatchers.anyString())).thenAnswer(call->{
                StorageConfigArtifactVO stored=database.get();return stored!=null&&stored.getUuid().equals(call.getArgument(0))?copy(stored):null;
            });
            org.mockito.Mockito.when(artifacts.update(org.mockito.ArgumentMatchers.eq(11L),org.mockito.ArgumentMatchers.any())).thenAnswer(call->{
                StorageConfigArtifactVO updated=call.getArgument(1);
                if(failUpdate||failCompletion&&"COMPLETE".equals(updated.getState()))return false;
                database.set(copy(updated));return true;
            });
            StorageServiceOperationVO committed=new StorageServiceOperationVO();committed.setInstanceId(7);committed.setState("COMPLETE");committed.setRevision(6);
            readOnly.setInstanceId(7);readOnly.setCreatedBy(2);readOnly.setRevision(7);readOnly.setAction("CONFIGURATION_BACKUP_READ_ONLY");readOnly.setState("RECOVERY_REQUIRED");
            org.apache.cloudstack.storage.dataservice.StorageOperationHistoryFixtures.rows(operations,7L,java.util.List.of(committed,readOnly));
            org.mockito.Mockito.when(artifacts.listByInstance(7L)).thenAnswer(call->activePoint==null?java.util.List.of():java.util.List.of(activePoint));
            StorageServiceManagerImpl manager=new StorageServiceManagerImpl() {
                @Override protected void requireConfigurationAdministrator() { }
                @Override protected StorageServiceInstanceVO requireInstance(Long id) {Assert.assertEquals(Long.valueOf(7),id);return instance;}
                @Override protected boolean managedReadOnlyBackup(StorageServiceInstanceVO value,StorageConfigRequest source) {return managedBackup;}
                @Override protected <T> T executeReadOnlyConfigurationBackup(StorageServiceInstanceVO value,StorageConfigRequest source,
                        Class<T> responseClass,java.util.function.Supplier<T> collect) {
                    admittedCalls++;throw new com.cloud.utils.exception.CloudRuntimeException("known managed operation requires its own recovery");
                }
                @Override protected String captureConfigurationSnapshot(long id) {return "{\"schemaVersion\":2,\"instanceId\":7,\"tables\":[]}";}
                @Override protected JsonObject configurationInstanceMetadata(StorageServiceInstanceVO value) {
                    JsonObject result=new JsonObject();result.addProperty("uuid",instance.getUuid());result.addProperty("productVersion","4.23.0");return result;
                }
                @Override protected java.util.Map<Long,JsonObject> configurationVolumeMetadata(String source) {return java.util.Map.of();}
                @Override protected JsonObject configurationBackupAvailability(StorageServiceInstanceVO value) {
                    admissionCalls++;JsonObject result=new JsonObject();result.addProperty("sourceNativeVerified",!admissionUnavailable);
                    result.addProperty("reason",admissionUnavailable?"NATIVE_OBSERVATION_UNAVAILABLE":"VERIFIED_NATIVE_SOURCE");
                    JsonObject status=StorageConfigRuntimeEvidenceTest.status(instance.getUuid(),"22222222-2222-4222-8222-222222222222",6);
                    // A real native generation retains its verifiedAt between read-only status calls.
                    status.getAsJsonObject("generation").addProperty("verifiedAt",1000);
                    JsonObject projected=StorageConfigRuntimeEvidence.generation(status,instance.getUuid(),"22222222-2222-4222-8222-222222222222",6);
                    if(admissionChanged&&admissionCalls>1)projected.addProperty("bootId","44444444-4444-4444-8444-444444444444");
                    result.add("nativeStatus",projected);return result;
                }
                @Override protected JsonObject observeConfigurationRuntime(StorageServiceInstanceVO value,String command) {
                    JsonObject result=new JsonObject();result.addProperty("success",!runtimeUnavailable);
                    result.addProperty("status",runtimeUnavailable?"UNAVAILABLE":runtimeDegraded?"degraded":"ok");return result;
                }
            };
            configuration=new StorageServiceConfiguration(manager,artifacts,operations,store);
            request=new StorageConfigRequest() {
                public org.apache.cloudstack.api.BaseCmd getBaseCmd(){return null;}
                public String getConfigAction(){return "BACKUP";}
                public Long getInstanceId(){return 7L;}
                public Boolean getIncludeRuntime(){return true;}
                public Boolean getIncludeAdIdentity(){return false;}
                public Integer getRetentionHours(){return 168;}
            };
        }
        StorageConfigArtifactVO copy(StorageConfigArtifactVO value) {return gson.fromJson(gson.toJson(value),StorageConfigArtifactVO.class);}
        org.apache.cloudstack.api.response.StorageServiceConfigArtifactResponse produce() {
            org.apache.cloudstack.api.response.StorageServiceConfigArtifactResponse response=configuration.execute(request);
            JsonObject envelope=new JsonObject();envelope.add("_response",gson.toJsonTree(response));readOnly.setResultJson(envelope.toString());return response;
        }
        JsonObject dto(org.apache.cloudstack.api.response.StorageServiceConfigArtifactResponse response) {return gson.toJsonTree(response).getAsJsonObject();}
        String snapshot(){return "{\"schemaVersion\":2,\"instanceId\":7,\"tables\":[]}";}
        JsonObject bindings(){JsonObject value=new JsonObject();value.addProperty("vmUuid","owned-vm");return value;}
        JsonObject sourceStatus(){
            JsonObject value=new JsonObject(),generation=new JsonObject();
            value.addProperty("success",true);value.addProperty("generationSupported",true);value.addProperty("generationStatus","IN_SYNC");
            value.addProperty("runtimeRevision",6);value.addProperty("configurationSha256","a".repeat(64));value.addProperty("bootId","11111111-1111-4111-8111-111111111111");
            value.add("pendingOperationUuid",com.google.gson.JsonNull.INSTANCE);
            generation.addProperty("instanceUuid",instance.getUuid());generation.addProperty("operationUuid","22222222-2222-4222-8222-222222222222");
            generation.addProperty("revision",6);generation.addProperty("configurationSha256","a".repeat(64));value.add("generation",generation);return value;
        }
        void legacyMissingIntegrity() {
            StorageConfigArtifactVO stored=copy(database.get());stored.setSha256(null);stored.setSize(0);database.set(stored);
            readOnly.setPreviousSnapshotJson(snapshot());readOnly.setSnapshotJson(snapshot());
            JsonObject result=new com.google.gson.JsonParser().parse(readOnly.getResultJson()).getAsJsonObject();
            result.add("_readOnlyNativeSource",StorageOperationReconciliation.readOnlyNative(sourceStatus()));result.add("_targetResourceBindings",bindings());
            readOnly.setResultJson(result.toString());
        }

        @Override public void close() throws Exception {
            locks.close();transactions.close();org.apache.cloudstack.context.CallContext.unregister();
            if(database.get()!=null)store.remove(database.get().getUuid());
            if(activePoint!=null)store.remove(activePoint.getUuid());java.nio.file.Files.delete(directory);
        }
    }

    @Test public void realProducerPersistsArchiveIntegrityOnSeparatelyLockedVoBeforeReturningDto() throws Exception {
        try(BackupFixture fixture=new BackupFixture()) {
            org.apache.cloudstack.api.response.StorageServiceConfigArtifactResponse response=fixture.produce();
            JsonObject wire=fixture.dto(response);
            JsonObject publicRow=new com.google.gson.JsonParser().parse(wire.get("result").getAsString()).getAsJsonObject();
            StorageConfigArtifactVO stored=fixture.database.get();
            Assert.assertEquals("BACKUP",stored.getKind());Assert.assertEquals("COMPLETE",stored.getState());
            Assert.assertEquals(6,stored.getDesiredRevision());Assert.assertEquals(publicRow.get("sha256").getAsString(),stored.getSha256());
            Assert.assertEquals(publicRow.get("size").getAsLong(),stored.getSize());
            Assert.assertEquals(StorageConfigArchive.sha256(fixture.store.read(stored.getUuid(),stored.getSha256())),stored.getSha256());
            fixture.configuration.bindReadOnlyArtifact(fixture.readOnly);fixture.configuration.verifyReadOnlyArtifact(fixture.readOnly);
        }
    }

    @Test public void sameOperationRecoveryReconcilesOnlyBothMissingFieldsFromRealDurableDtoAndArchive() throws Exception {
        try(BackupFixture fixture=new BackupFixture()) {
            fixture.produce();fixture.legacyMissingIntegrity();String metadata=fixture.database.get().getMetadataJson();
            org.mockito.Mockito.clearInvocations(fixture.artifacts);
            fixture.configuration.bindReadOnlyArtifact(fixture.readOnly,fixture.snapshot(),fixture.bindings(),fixture.sourceStatus());
            fixture.configuration.verifyReadOnlyArtifact(fixture.readOnly);
            Assert.assertNotNull(fixture.database.get().getSha256());Assert.assertTrue(fixture.database.get().getSize()>0);
            Assert.assertEquals(6,fixture.database.get().getDesiredRevision());Assert.assertEquals(metadata,fixture.database.get().getMetadataJson());
            org.mockito.Mockito.verify(fixture.artifacts,org.mockito.Mockito.times(1)).update(org.mockito.ArgumentMatchers.eq(11L),org.mockito.ArgumentMatchers.any());
            org.mockito.Mockito.clearInvocations(fixture.artifacts);
            fixture.configuration.bindReadOnlyArtifact(fixture.readOnly,fixture.snapshot(),fixture.bindings(),fixture.sourceStatus());
            org.mockito.Mockito.verify(fixture.artifacts,org.mockito.Mockito.never()).update(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.any());
        }
    }

    @Test public void recoveryCannotAdoptForeignChangedPartialOrUnprovenArchiveMetadata() throws Exception {
        for(int mode=0;mode<11;mode++)try(BackupFixture fixture=new BackupFixture()) {
            fixture.produce();fixture.legacyMissingIntegrity();JsonObject status=fixture.sourceStatus(),bindings=fixture.bindings();String source=fixture.snapshot();
            StorageConfigArtifactVO stored=fixture.copy(fixture.database.get());
            if(mode==0)stored.setInstanceId(99);
            if(mode==1)stored.setCreatedBy(99);
            if(mode==2)stored.setDesiredRevision(5);
            if(mode==3)stored.setState("DELETED");
            if(mode==4)stored.setKind("IMPORT");
            if(mode==5)stored.setSha256("b".repeat(64));
            if(mode==6)stored.setSize(1);
            if(mode==7)status.addProperty("bootId","33333333-3333-4333-8333-333333333333");
            if(mode==8)bindings.addProperty("vmUuid","foreign-vm");
            if(mode==9)stored.setExpires(new java.util.Date(1));
            if(mode==10) {
                JsonObject envelope=new com.google.gson.JsonParser().parse(fixture.readOnly.getResultJson()).getAsJsonObject();
                JsonObject dto=new com.google.gson.JsonParser().parse(envelope.getAsJsonObject("_response").get("result").getAsString()).getAsJsonObject();
                dto.addProperty("sha256","b".repeat(64));envelope.getAsJsonObject("_response").addProperty("result",dto.toString());fixture.readOnly.setResultJson(envelope.toString());
            }
            fixture.database.set(stored);org.mockito.Mockito.clearInvocations(fixture.artifacts);
            Assert.assertThrows(RuntimeException.class,()->fixture.configuration.bindReadOnlyArtifact(fixture.readOnly,source,bindings,status));
            org.mockito.Mockito.verify(fixture.artifacts,org.mockito.Mockito.never()).update(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.any());
        }
    }

    @Test public void failedCompletionPersistenceCannotExposeSuccessfulBackupDto() throws Exception {
        try(BackupFixture fixture=new BackupFixture()) {
            fixture.failCompletion=true;
            Assert.assertThrows(RuntimeException.class,fixture::produce);
            Assert.assertEquals("FAILED",fixture.database.get().getState());Assert.assertNull(fixture.database.get().getSha256());Assert.assertEquals(0,fixture.database.get().getSize());
        }
    }

    @Test public void changedLockedPublicationScopeCannotPublishArchiveIntegrity() throws Exception {
        try(BackupFixture fixture=new BackupFixture()) {
            fixture.foreignCompletionScope=true;
            Assert.assertThrows(RuntimeException.class,fixture::produce);
            Assert.assertNotEquals("COMPLETE",fixture.database.get().getState());Assert.assertNull(fixture.database.get().getSha256());
        }
    }

    @Test public void unavailableBackupIncludesOnlyBoundHistoricalCacheAndNeverClaimsRuntimeSuccess() throws Exception {
        try (BackupFixture fixture=new BackupFixture()) {
            long now=System.currentTimeMillis();String operation="22222222-2222-4222-8222-222222222222";
            java.util.Map<String,byte[]> cached=new java.util.LinkedHashMap<>(StorageConfigSemantic.export(fixture.snapshot(),
                    new JsonObject(),java.util.Map.of()));
            JsonObject observation=new JsonObject();observation.addProperty("success",true);observation.addProperty("status","ok");
            observation.addProperty("generatedEpoch",now/1000.0);observation.addProperty("password","must-be-redacted");
            byte[] safe=StorageConfigSemantic.redact(observation).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            cached.put("runtime/health.json",safe);cached.put("runtime/inventory.json",safe);
            JsonObject nativeStatus=StorageConfigRuntimeEvidenceTest.status(fixture.instance.getUuid(),operation,6);
            JsonObject evidence=StorageConfigRuntimeEvidence.create(fixture.instance.getUuid(),operation,6,fixture.snapshot(),nativeStatus,nativeStatus,cached,now);
            JsonObject metadata=new JsonObject();metadata.addProperty("operationUuid",operation);metadata.add("runtimeEvidence",evidence);
            byte[] archive=StorageConfigArchive.create(cached,metadata);
            fixture.activePoint=new StorageConfigArtifactVO();fixture.activePoint.setInstanceId(7);fixture.activePoint.setKind("RESTORE_POINT");
            fixture.activePoint.setState("ACTIVE_LKG");fixture.activePoint.setDesiredRevision(6);fixture.activePoint.setMetadataJson(metadata.toString());
            fixture.activePoint.setSha256(StorageConfigArchive.sha256(archive));fixture.activePoint.setSize(archive.length);
            fixture.store.write(fixture.activePoint.getUuid(),archive);fixture.runtimeUnavailable=true;
            fixture.produce();StorageConfigArtifactVO backup=fixture.database.get();
            Assert.assertEquals("PARTIAL",backup.getState());
            JsonObject result=new com.google.gson.JsonParser().parse(backup.getMetadataJson()).getAsJsonObject();
            Assert.assertEquals("STALE",result.get("runtimeStatus").getAsString());
            java.util.Map<String,byte[]> exported=StorageConfigArchive.validate(fixture.store.read(backup.getUuid(),backup.getSha256()));
            JsonObject health=StorageConfigArchive.json(exported.get("runtime/health.json")).getAsJsonObject();
            Assert.assertFalse(health.get("success").getAsBoolean());Assert.assertEquals("STALE",health.get("status").getAsString());
            Assert.assertEquals(fixture.activePoint.getUuid(),health.get("sourceArtifactUuid").getAsString());
            Assert.assertEquals(now,health.get("observedAtMillis").getAsLong());Assert.assertEquals(6,health.get("desiredRevision").getAsLong());
            Assert.assertFalse(health.getAsJsonObject("currentObservation").get("success").getAsBoolean());
            Assert.assertFalse(new String(exported.get("runtime/health.json"),java.nio.charset.StandardCharsets.UTF_8).contains("must-be-redacted"));
            Assert.assertEquals("UNAVAILABLE",StorageConfigArchive.json(exported.get("runtime/sessions.json")).getAsJsonObject().get("status").getAsString());
            Assert.assertEquals("ACTIVE_LKG",fixture.activePoint.getState());
        }
    }

    @Test public void legacyMissingOrCorruptCacheNeverInventsSuccessfulObservation() throws Exception {
        for(int mode=0;mode<3;mode++)try(BackupFixture fixture=new BackupFixture()) {
            fixture.runtimeUnavailable=true;
            if(mode>0) {
                fixture.activePoint=new StorageConfigArtifactVO();fixture.activePoint.setInstanceId(7);fixture.activePoint.setKind("RESTORE_POINT");
                fixture.activePoint.setState("ACTIVE_LKG");fixture.activePoint.setDesiredRevision(6);
                fixture.activePoint.setMetadataJson(mode==1?"{}":"{\"runtimeEvidence\":{}}");
            }
            fixture.produce();JsonObject result=new com.google.gson.JsonParser().parse(fixture.database.get().getMetadataJson()).getAsJsonObject();
            Assert.assertEquals("UNAVAILABLE",result.get("runtimeStatus").getAsString());
            Assert.assertEquals("PARTIAL",fixture.database.get().getState());
        }
    }

    @Test public void degradedCurrentRuntimeCannotProduceCompleteBackup() throws Exception {
        try(BackupFixture fixture=new BackupFixture()) {
            fixture.runtimeDegraded=true;fixture.produce();
            Assert.assertEquals("PARTIAL",fixture.database.get().getState());
            JsonObject metadata=new com.google.gson.JsonParser().parse(fixture.database.get().getMetadataJson()).getAsJsonObject();
            Assert.assertEquals("UNAVAILABLE",metadata.get("runtimeStatus").getAsString());
            JsonObject health=StorageConfigArchive.json(StorageConfigArchive.validate(fixture.store.read(fixture.database.get().getUuid(),
                    fixture.database.get().getSha256())).get("runtime/health.json")).getAsJsonObject();
            Assert.assertFalse(health.get("success").getAsBoolean());Assert.assertEquals("degraded",health.get("status").getAsString());
        }
    }

    @Test public void offlineBackupNeverAdmitsManagedWriterAndKeepsDesiredArchiveAvailable() throws Exception {
        try(BackupFixture fixture=new BackupFixture()) {
            fixture.admissionUnavailable=true;fixture.managedBackup=true;fixture.produce();
            Assert.assertEquals("PARTIAL",fixture.database.get().getState());
            JsonObject metadata=new com.google.gson.JsonParser().parse(fixture.database.get().getMetadataJson()).getAsJsonObject();
            Assert.assertFalse(metadata.get("sourceNativeVerified").getAsBoolean());Assert.assertTrue(metadata.get("runtimeRevision").isJsonNull());
            Assert.assertEquals("UNAVAILABLE",metadata.get("driftStatus").getAsString());Assert.assertEquals(1,fixture.admissionCalls);Assert.assertEquals(0,fixture.admittedCalls);
            Assert.assertNotNull(StorageConfigArchive.validate(fixture.store.read(fixture.database.get().getUuid(),fixture.database.get().getSha256())).get("desired/instance.json"));
        }
    }
    @Test public void generationChangeDuringBackupIsPartialDiagnosticAndCannotClaimCurrentRevision() throws Exception {
        try(BackupFixture fixture=new BackupFixture()) {
            fixture.admissionChanged=true;fixture.produce();
            JsonObject metadata=new com.google.gson.JsonParser().parse(fixture.database.get().getMetadataJson()).getAsJsonObject();
            Assert.assertEquals("PARTIAL",fixture.database.get().getState());Assert.assertEquals("PARTIAL",metadata.get("runtimeStatus").getAsString());
            Assert.assertEquals("DRIFTED",metadata.get("driftStatus").getAsString());Assert.assertEquals("CONFIG_CHANGED",metadata.get("runtimeObservationErrorCode").getAsString());
            Assert.assertFalse(metadata.get("sourceNativeVerified").getAsBoolean());Assert.assertTrue(metadata.get("runtimeRevision").isJsonNull());
        }
    }

    @Test public void admittedManagedFailureNeverFallsBackToSecondArtifactOrAnotherWriter() throws Exception {
        try(BackupFixture fixture=new BackupFixture()) {
            fixture.managedBackup=true;Assert.assertThrows(RuntimeException.class,fixture::produce);
            Assert.assertEquals(1,fixture.admittedCalls);Assert.assertEquals(1,fixture.admissionCalls);
            Assert.assertNull(fixture.database.get());
            org.mockito.Mockito.verify(fixture.artifacts,org.mockito.Mockito.never()).persist(org.mockito.ArgumentMatchers.any());
        }
    }

}
