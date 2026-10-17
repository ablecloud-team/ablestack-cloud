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

import java.util.List;
import java.util.ArrayList;
import org.junit.Test;
import org.junit.Assert;
import org.mockito.Mockito;
import com.google.gson.JsonObject;
import com.google.gson.JsonNull;
import org.springframework.test.util.ReflectionTestUtils;

public class StorageAdServiceCheckpointTest {
    private static class Manager extends StorageServiceManagerImpl {
        List<String> events=new ArrayList<>();JsonObject before;boolean wrongCapture,wrongStopped,wrongBoot,bootstrapCreated,wrongBootstrap,lostBootstrap;StorageServiceOperationVO activeOperation;
        @Override protected JsonObject requiredRenderedValidationProfile(StorageServiceInstanceVO instance){return new JsonObject();}
        @Override protected void requireNoPendingVolumeFormatter(StorageServiceInstanceVO instance){ }
        @Override protected void prepareRenderedBatch(StorageServiceInstanceVO instance,StorageServiceOperationVO operation,JsonObject nativeBefore){events.add("IMPORT_READONLY_SOURCE");JsonObject manifest=new JsonObject();manifest.addProperty("manifestSha256","b".repeat(64));ThreadLocal<RenderedBatch> batches=(ThreadLocal<RenderedBatch>)ReflectionTestUtils.getField(this,"renderedBatch");batches.set(new RenderedBatch(operation,nativeBefore,manifest,StorageIdentityCapsule.wrappingKey()));}
        @Override protected JsonObject nativeConfigurationGeneration(StorageServiceInstanceVO instance,StorageServiceOperationVO operation,String action){events.add("GEN_"+action);return before.deepCopy();}
        @Override protected JsonObject rootGuest(StorageServiceInstanceVO instance,String command,JsonObject scope,int timeout){events.add(command);
        JsonObject result=new JsonObject();
        result.addProperty("success",true);
        result.add("scope",scope.deepCopy());
        result.add("sourceGeneration",before.get("generation").deepCopy());
        result.addProperty("sourceConfigurationSha256","a".repeat(64));
        result.add("publicAdPreStopSha256",JsonNull.INSTANCE);
        result.addProperty("publicLocalMachineSid","S-1-5-21-1-2-3");
        result.addProperty("bootId","33333333-3333-3333-3333-333333333333");
        if(command.equals("identity local-sam bootstrap")){
            Assert.assertTrue(com.google.gson.JsonParser.parseString(activeOperation.getPreviousSnapshotJson()).getAsJsonObject().get("adSamBootstrapAttempted").getAsBoolean());
            Assert.assertEquals(java.util.Set.of("instanceUuid","operationUuid","revision","netbiosName","initializationApproved","expectedGeneration","expectedConfigurationSha256","expectedBootId"),scope.keySet());
            if(lostBootstrap)throw new com.cloud.utils.exception.CloudRuntimeException("Lost bootstrap reply");
            JsonObject binding=new JsonObject();for(String key:List.of("instanceUuid","operationUuid","revision"))binding.add(key,scope.get(key).deepCopy());result.add("scope",binding);
            result.add("generation",before.get("generation").deepCopy());result.addProperty("configurationSha256","a".repeat(64));result.addProperty("netbiosName",scope.get("netbiosName").getAsString());
            result.addProperty("localMachineSid","S-1-5-21-1-2-3");result.addProperty("localSamInitialized",bootstrapCreated);result.addProperty("identityPreserved",!bootstrapCreated);result.addProperty("sideEffects",bootstrapCreated);result.addProperty("canonicalDesiredStateChanged",false);
            if(wrongBootstrap)result.addProperty("identityPreserved","true");return result;
        }
        if(command.endsWith("capture-source")){result.addProperty("sourceCaptured",true);
        result.addProperty("canonicalDesiredStateChanged",false);
        result.addProperty("sourceRenderedManifestSha256","b".repeat(64));
        result.addProperty("publicAdPreStopCaptured",false);
        if(wrongCapture)result.addProperty("publicAdPreStopCaptured","false");
        }else {result.addProperty("bootHeld",true);
        result.addProperty("serviceSourceStoppedVerified",!wrongStopped);
        result.addProperty("maintenanceKind","SERVICE");
        result.addProperty("stoppedReceiptSha256","c".repeat(64));
        if(wrongBoot)result.addProperty("bootId","44444444-4444-4444-4444-444444444444");
        }return result;
        }
        @Override protected void checkpointConfigurationIdentity(StorageServiceInstanceVO instance,JsonObject scope,String sha){events.add("RAW_IDENTITY_AFTER_STOP");Assert.assertTrue(scope.has("maintenanceUuid"));Assert.assertFalse(scope.has("templateUpgradeUuid"));Assert.assertEquals("a".repeat(64),sha);}
    }
    private static class Fixture {Manager manager;StorageServiceInstanceVO instance;StorageServiceOperationVO operation;}
    private Fixture fixture(){Fixture f=new Fixture();
        f.manager=new Manager();
        f.instance=Mockito.mock(StorageServiceInstanceVO.class);
        Mockito.when(f.instance.getUuid()).thenReturn("11111111-1111-1111-1111-111111111111");
        f.operation=new StorageServiceOperationVO();
        f.operation.setRevision(4);
        f.operation.setPreviousSnapshotJson("{}");
        JsonObject gen=new JsonObject();
        gen.addProperty("instanceUuid",f.instance.getUuid());
        gen.addProperty("revision",3);
        f.manager.before=new JsonObject();
        f.manager.before.add("generation",gen);
        f.manager.before.addProperty("generationStatus","IN_SYNC");
        f.manager.before.addProperty("bootId","33333333-3333-3333-3333-333333333333");f.manager.activeOperation=f.operation;
        f.manager.before.addProperty("configurationSha256","a".repeat(64));
        f.manager.before.add("configurationDesiredState",new JsonObject());
        org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao operations=Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao.class);
        Mockito.when(operations.update(Mockito.anyLong(),Mockito.any())).thenReturn(true);
        ReflectionTestUtils.setField(f.manager,"storageOperationDao",operations);
        return f;
        }
    @Test public void serviceSourceImportCapturePendingStopAndRawIdentityHaveStrictOrder(){Fixture f=fixture();f.manager.prepareAdServiceCheckpoint(f.instance,f.operation);List<String> events=f.manager.events;Assert.assertTrue(events.indexOf("IMPORT_READONLY_SOURCE")<events.indexOf("operation generation render-service-capture-source"));Assert.assertTrue(events.indexOf("operation generation render-service-capture-source")<events.indexOf("GEN_begin"));Assert.assertTrue(events.indexOf("GEN_begin")<events.indexOf("operation maintenance service-enter"));Assert.assertTrue(events.indexOf("operation maintenance service-enter")<events.indexOf("RAW_IDENTITY_AFTER_STOP"));JsonObject saved=com.google.gson.JsonParser.parseString(f.operation.getPreviousSnapshotJson()).getAsJsonObject();Assert.assertTrue(saved.has("adServiceSource"));Assert.assertTrue(saved.has("adServiceStopped"));}
    @Test public void coercedPrestopProofRejectsBeforePendingMarkerStopOrRawRead(){Fixture f=fixture();f.manager.wrongCapture=true;Assert.assertThrows(RuntimeException.class,()->f.manager.prepareAdServiceCheckpoint(f.instance,f.operation));Assert.assertFalse(f.manager.events.contains("GEN_begin"));Assert.assertFalse(f.manager.events.contains("operation maintenance service-enter"));Assert.assertFalse(f.manager.events.contains("RAW_IDENTITY_AFTER_STOP"));}
    @Test public void missingStoppedReceiptPreservesDurableEnterIntentAndCannotReadRawTdb(){Fixture f=fixture();f.manager.wrongStopped=true;Assert.assertThrows(RuntimeException.class,()->f.manager.prepareAdServiceCheckpoint(f.instance,f.operation));Assert.assertFalse(f.manager.events.contains("RAW_IDENTITY_AFTER_STOP"));Assert.assertTrue(com.google.gson.JsonParser.parseString(f.operation.getPreviousSnapshotJson()).getAsJsonObject().get("adServiceEnterAttempted").getAsBoolean());}
    @Test public void restartedServiceSourceCannotReadRawIdentityAfterOldBootStop(){
        Fixture f=fixture();f.manager.wrongBoot=true;
        Assert.assertThrows(RuntimeException.class,()->f.manager.prepareAdServiceCheckpoint(f.instance,f.operation));
        Assert.assertFalse(f.manager.events.contains("RAW_IDENTITY_AFTER_STOP"));
        Assert.assertTrue(com.google.gson.JsonParser.parseString(f.operation.getPreviousSnapshotJson()).getAsJsonObject().get("adServiceEnterAttempted").getAsBoolean());
    }
    @Test public void durableServiceSourceBindingRejectsChangedStoppedSamAndBoot(){
        Fixture f=fixture();f.manager.prepareAdServiceCheckpoint(f.instance,f.operation);
        ThreadLocal<StorageServiceOperationVO> writers=(ThreadLocal<StorageServiceOperationVO>)ReflectionTestUtils.getField(f.manager,"storageWriterOperation");writers.set(f.operation);
        JsonObject snapshot=com.google.gson.JsonParser.parseString(f.operation.getPreviousSnapshotJson()).getAsJsonObject();
        Assert.assertEquals(snapshot.get("adServiceSource"),f.manager.requiredAdServiceSourceIdentity(f.instance));
        for(String field:List.of("bootId","publicLocalMachineSid")){
            JsonObject wrong=snapshot.deepCopy();wrong.getAsJsonObject("adServiceStopped").addProperty(field,field.equals("bootId")?"44444444-4444-4444-4444-444444444444":"S-1-5-21-1-2-4");
            f.operation.setPreviousSnapshotJson(wrong.toString());
            Assert.assertThrows(field,RuntimeException.class,()->f.manager.requiredAdServiceSourceIdentity(f.instance));
        }
    }

    private static JsonObject cipher(JsonObject scope){
        byte[] ciphertext=new byte[32];java.util.Arrays.fill(ciphertext,(byte)7);
        JsonObject value=new JsonObject();value.addProperty("schemaVersion",1);value.addProperty("scope",scope.get("instanceUuid").getAsString()+":"+scope.get("operationUuid").getAsString());
        value.addProperty("ciphertext",java.util.Base64.getEncoder().encodeToString(ciphertext));value.addProperty("nonce",java.util.Base64.getEncoder().encodeToString(new byte[12]));value.addProperty("wrappedKey",java.util.Base64.getEncoder().encodeToString(new byte[256]));value.addProperty("sha256",StorageConfigArchive.sha256(ciphertext));return value;
    }
    private static JsonObject cipherReceipt(JsonObject scope,JsonObject capsule){
        JsonObject value=new JsonObject();value.addProperty("kind","SERVICE_SOURCE_IDENTITY_CHECKPOINT");value.add("scope",scope.deepCopy());value.addProperty("capsuleSha256",capsule.get("sha256").getAsString());value.addProperty("sourceConfigurationSha256","a".repeat(64));value.addProperty("checkpointRecordSha256","d".repeat(64));return value;
    }
    private static JsonObject cipherScope(){
        JsonObject scope=new JsonObject();scope.addProperty("instanceUuid","11111111-1111-1111-1111-111111111111");scope.addProperty("operationUuid","22222222-2222-2222-2222-222222222222");scope.addProperty("maintenanceUuid","22222222-2222-2222-2222-222222222222");scope.addProperty("revision",4);return scope;
    }
    @Test public void encryptedServiceSourceUsesTheExactCipherScopeAndOriginalConfigurationReceipt(){
        JsonObject scope=cipherScope(),capsule=cipher(scope),receipt=cipherReceipt(scope,capsule);
        Assert.assertEquals(receipt,StorageAdIdentityProof.serviceCipherCheckpoint(receipt,capsule,scope,"a".repeat(64)));
        for(String field:List.of("scope","sourceConfigurationSha256","checkpointRecordSha256","kind")){
            JsonObject wrong=receipt.deepCopy();
            if(field.equals("scope"))wrong.getAsJsonObject(field).addProperty("maintenanceUuid","33333333-3333-3333-3333-333333333333");
            else wrong.addProperty(field,field.equals("sourceConfigurationSha256")?"b".repeat(64):"wrong");
            Assert.assertThrows(field,RuntimeException.class,()->StorageAdIdentityProof.serviceCipherCheckpoint(wrong,capsule,scope,"a".repeat(64)));
        }
        JsonObject extra=receipt.deepCopy();extra.addProperty("privateKey","synthetic-secret");
        Assert.assertThrows(RuntimeException.class,()->StorageAdIdentityProof.serviceCipherCheckpoint(extra,capsule,scope,"a".repeat(64)));
    }
    @Test public void changedCiphertextDigestOrMalformedEncryptionCannotBeRetainedAsSource(){
        JsonObject scope=cipherScope(),capsule=cipher(scope),receipt=cipherReceipt(scope,capsule);
        for(String field:List.of("ciphertext","nonce","wrappedKey","scope","schemaVersion","sha256")){
            JsonObject wrong=capsule.deepCopy();
            if(field.equals("ciphertext"))wrong.addProperty(field,java.util.Base64.getEncoder().encodeToString(new byte[32]));
            else if(field.equals("nonce")||field.equals("wrappedKey"))wrong.addProperty(field,"AAAA");
            else wrong.addProperty(field,"changed");
            Assert.assertThrows(field,RuntimeException.class,()->StorageAdIdentityProof.serviceCipherCheckpoint(receipt,wrong,scope,"a".repeat(64)));
        }
    }
    private static class CipherFixture {
        StorageServiceManagerImpl manager;StorageServiceInstanceVO instance;StorageServiceOperationVO operation;JsonObject scope,capsule,response;
        org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao operations;
    }
    private CipherFixture cipherFixture(){
        CipherFixture f=new CipherFixture();
        f.manager=new StorageServiceManagerImpl(){@Override protected void requireProtectedIdentityTransport(StorageServiceInstanceVO instance,String operationUuid){}};
        f.instance=Mockito.mock(StorageServiceInstanceVO.class);Mockito.when(f.instance.getUuid()).thenReturn("11111111-1111-1111-1111-111111111111");Mockito.when(f.instance.getVmId()).thenReturn(7L);
        f.operation=new StorageServiceOperationVO();f.operation.setRevision(4);f.operation.setPreviousSnapshotJson("{}");
        ThreadLocal<StorageServiceOperationVO> writers=(ThreadLocal<StorageServiceOperationVO>)ReflectionTestUtils.getField(f.manager,"storageWriterOperation");writers.set(f.operation);
        f.scope=cipherScope();f.scope.addProperty("operationUuid",f.operation.getUuid());f.scope.addProperty("maintenanceUuid",f.operation.getUuid());f.capsule=cipher(f.scope);
        f.response=new JsonObject();f.response.addProperty("success",true);f.response.add("capsule",f.capsule);f.response.add("serviceIdentityCheckpoint",cipherReceipt(f.scope,f.capsule));
        f.operations=Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao.class);ReflectionTestUtils.setField(f.manager,"storageOperationDao",f.operations);
        org.apache.cloudstack.storage.dataservice.dao.StorageFileShareDao shares=Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageFileShareDao.class);
        Mockito.when(shares.listByInstanceIdAndProtocol(Mockito.anyLong(),Mockito.any())).thenReturn(java.util.Collections.emptyList());ReflectionTestUtils.setField(f.manager,"storageFileShareDao",shares);
        org.apache.cloudstack.storage.dataservice.dao.StorageBlockTargetDao targets=Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageBlockTargetDao.class);
        Mockito.when(targets.listByInstanceIdAndProtocol(Mockito.anyLong(),Mockito.any())).thenReturn(java.util.Collections.emptyList());ReflectionTestUtils.setField(f.manager,"storageBlockTargetDao",targets);
        StorageServiceGuestCommandDispatcher guest=Mockito.mock(StorageServiceGuestCommandDispatcher.class);
        Mockito.when(guest.dispatch(Mockito.any())).thenAnswer(call->new StorageServiceGuestCommandResult(true,"encrypted",f.response.toString()));ReflectionTestUtils.setField(f.manager,"guestCommandDispatcher",guest);
        return f;
    }
    @Test public void nativeSourceCipherReceiptMustBeDurableBeforeExportReturnsToManagedStorage(){
        CipherFixture f=cipherFixture();Mockito.when(f.operations.update(Mockito.anyLong(),Mockito.any())).thenAnswer(call->{StorageServiceOperationVO operation=call.getArgument(1);Assert.assertTrue(com.google.gson.JsonParser.parseString(operation.getPreviousSnapshotJson()).getAsJsonObject().has("adServiceCipherCheckpoint"));return true;});
        Assert.assertEquals(f.capsule,f.manager.exportConfigurationIdentity(f.instance,f.operation.getUuid(),StorageIdentityCapsule.wrappingKey(),f.scope,"a".repeat(64)));
        Mockito.verify(f.operations).update(Mockito.anyLong(),Mockito.any());
    }
    @Test public void missingOrForeignSourceReceiptRejectsBeforeDurableSourcePublication(){
        for(String wrong:List.of("missing","source","success")){
            CipherFixture f=cipherFixture();
            if(wrong.equals("missing"))f.response.remove("serviceIdentityCheckpoint");
            else if(wrong.equals("success"))f.response.addProperty("success","true");
            else f.response.getAsJsonObject("serviceIdentityCheckpoint").addProperty("sourceConfigurationSha256","b".repeat(64));
            Assert.assertThrows(wrong,RuntimeException.class,()->f.manager.exportConfigurationIdentity(f.instance,f.operation.getUuid(),StorageIdentityCapsule.wrappingKey(),f.scope,"a".repeat(64)));
            Mockito.verifyNoInteractions(f.operations);
        }
    }
    @Test public void failedDurableSourceReceiptWriteCannotReturnCipherToManagedStorage(){
        CipherFixture f=cipherFixture();Mockito.when(f.operations.update(Mockito.anyLong(),Mockito.any())).thenReturn(false);
        Assert.assertThrows(RuntimeException.class,()->f.manager.exportConfigurationIdentity(f.instance,f.operation.getUuid(),StorageIdentityCapsule.wrappingKey(),f.scope,"a".repeat(64)));
    }

    @Test public void firstJoinDispatchesApprovedSamBootstrapBeforeSourceImportAndCapture(){
        for(boolean created:new boolean[]{false,true}){
            Fixture f=fixture();f.operation.setAction("joinStorageServiceToAdDomain");f.manager.bootstrapCreated=created;f.manager.prepareAdServiceCheckpoint(f.instance,f.operation);
            Assert.assertTrue(f.manager.events.indexOf("identity local-sam bootstrap")<f.manager.events.indexOf("IMPORT_READONLY_SOURCE"));
            Assert.assertTrue(f.manager.events.indexOf("identity local-sam bootstrap")<f.manager.events.indexOf("operation generation render-service-capture-source"));
            JsonObject snapshot=com.google.gson.JsonParser.parseString(f.operation.getPreviousSnapshotJson()).getAsJsonObject();JsonObject proof=snapshot.getAsJsonObject("adSamBootstrapReceipt");
            Assert.assertEquals(created,proof.get("localSamInitialized").getAsBoolean());Assert.assertEquals(!created,proof.get("identityPreserved").getAsBoolean());
            Assert.assertEquals(proof.get("localMachineSid"),snapshot.getAsJsonObject("adServiceSource").get("publicLocalMachineSid"));
        }
    }
    @Test public void unknownSamInitializationCannotCaptureSourceOrClaimGenericRollback(){
        for(boolean lost:new boolean[]{false,true}){
            Fixture f=fixture();f.operation.setAction("joinStorageServiceToAdDomain");f.manager.lostBootstrap=lost;f.manager.wrongBootstrap=!lost;
            Assert.assertThrows(RuntimeException.class,()->f.manager.prepareAdServiceCheckpoint(f.instance,f.operation));Assert.assertFalse(f.manager.events.contains("IMPORT_READONLY_SOURCE"));Assert.assertFalse(f.manager.events.contains("GEN_begin"));Assert.assertFalse(f.manager.events.contains("RAW_IDENTITY_AFTER_STOP"));
            JsonObject snapshot=com.google.gson.JsonParser.parseString(f.operation.getPreviousSnapshotJson()).getAsJsonObject();Assert.assertTrue(snapshot.get("adSamBootstrapAttempted").getAsBoolean());Assert.assertFalse(snapshot.has("adSamBootstrapReceipt"));
            Assert.assertThrows(RuntimeException.class,()->ReflectionTestUtils.invokeMethod(f.manager,"rollbackNativeConfigurationGeneration",f.instance,f.operation));Assert.assertFalse(f.manager.events.contains("GEN_rollback"));
        }
    }

    private static class RootTargetManager extends StorageServiceManagerImpl {
        List<String> events=new ArrayList<>();JsonObject status,scope;boolean failResume,wrongBoot,wrongStop,wrongCipherKind;
        @Override protected boolean hasJoinedStorageAdDomain(StorageServiceInstanceVO instance){return true;}
        @Override protected JsonObject nativeConfigurationGeneration(StorageServiceInstanceVO instance,StorageServiceOperationVO operation,String action){return status.deepCopy();}
        @Override protected void verifyReconciledStorageDesiredState(StorageServiceInstanceVO instance){events.add("ALL4_AFTER_RELEASE_VERIFY");}
        @Override protected JsonObject configurationIdentityExportRequest(StorageServiceInstanceVO instance,String operation,java.security.KeyPair key){JsonObject value=StorageIdentityCapsule.exportRequest(instance.getUuid(),operation,key,new com.google.gson.JsonArray());value.add("nvmeHosts",new com.google.gson.JsonArray());return value;}
        @Override protected JsonObject rootGuest(StorageServiceInstanceVO instance,String command,JsonObject request,int timeout){events.add(command);org.junit.Assert.assertTrue(request.has("templateUpgradeUuid"));org.junit.Assert.assertFalse(request.has("maintenanceUuid"));org.junit.Assert.assertEquals(java.util.Set.of("instanceUuid","operationUuid","revision","templateUpgradeUuid","targetConfigurationSha256","importedRootAuthorization"),request.keySet());
            if(command.endsWith("resume-target")&&failResume)throw new com.cloud.utils.exception.CloudRuntimeException("Target resume response lost");JsonObject proof=new JsonObject();proof.addProperty("success",true);proof.add("scope",scope.deepCopy());proof.addProperty("targetCaptured",true);proof.addProperty("canonicalDesiredStateChanged",false);proof.add("targetGeneration",status.get("generation").deepCopy());proof.addProperty("targetConfigurationSha256","b".repeat(64));proof.addProperty("targetRenderedManifestSha256","c".repeat(64));proof.add("bootId",status.get("bootId").deepCopy());proof.addProperty("publicLocalMachineSid","S-1-5-21-1-2-3");if(wrongBoot)proof.addProperty("bootId","55555555-5555-5555-5555-555555555555");
            if(command.endsWith("quiesce-target")){proof.addProperty("rootTargetStoppedVerified",!wrongStop);proof.addProperty("maintenanceKind","ROOT");proof.addProperty("stoppedReceiptSha256","d".repeat(64));proof.addProperty("bootHeld",true);proof.addProperty("sideEffects",false);}if(command.endsWith("resume-target"))proof.addProperty("targetRuntimeVerified",true);return proof;
        }
    }
    @Test public void rootTargetStopCipherResumeAndPromotionHaveIndependentRoleAndRetryWithoutReexport() throws Exception {
        java.nio.file.Path identities=java.nio.file.Files.createTempDirectory("root-target-identity-"),keys=java.nio.file.Files.createTempDirectory("root-target-key-");for(java.nio.file.Path path:List.of(identities,keys))java.nio.file.Files.setPosixFilePermissions(path,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));String oldIdentity=System.getProperty("cloudstack.storage.identity.path"),oldKeys=System.getProperty("cloudstack.storage.rendered.keys.path");System.setProperty("cloudstack.storage.identity.path",identities.toString());System.setProperty("cloudstack.storage.rendered.keys.path",keys.toString());
        try(org.mockito.MockedStatic<com.cloud.utils.crypt.DBEncryptionUtil> crypto=Mockito.mockStatic(com.cloud.utils.crypt.DBEncryptionUtil.class)) {
            RootTargetManager m=new RootTargetManager();StorageServiceOperationVO op=new StorageServiceOperationVO();op.setAction("ROOT_TEMPLATE_UPGRADE");op.setRevision(4);StorageServiceInstanceVO instance=Mockito.mock(StorageServiceInstanceVO.class);Mockito.when(instance.getUuid()).thenReturn("11111111-1111-1111-1111-111111111111");Mockito.when(instance.getId()).thenReturn(6L);Mockito.when(instance.getVmId()).thenReturn(7L);
            m.scope=new JsonObject();m.scope.addProperty("instanceUuid",instance.getUuid());m.scope.addProperty("operationUuid",op.getUuid());m.scope.addProperty("templateUpgradeUuid","22222222-2222-2222-2222-222222222222");m.scope.addProperty("revision",4);JsonObject common=m.scope.deepCopy();common.remove("templateUpgradeUuid");m.status=new JsonObject();m.status.addProperty("generationStatus","IN_SYNC");m.status.add("generation",common);m.status.addProperty("configurationSha256","b".repeat(64));m.status.addProperty("bootId","33333333-3333-3333-3333-333333333333");
            java.security.KeyPair pair=StorageIdentityCapsule.wrappingKey();crypto.when(()->com.cloud.utils.crypt.DBEncryptionUtil.decrypt("opaque-key")).thenReturn(StorageIdentityCapsule.pem("PRIVATE KEY",pair.getPrivate().getEncoded()));byte[] protectedKey="opaque-key".getBytes(java.nio.charset.StandardCharsets.UTF_8);String keyId="44444444-4444-4444-4444-444444444444";new StorageConfigArtifactStore(keys).write(keyId,protectedKey);JsonObject rendered=new JsonObject();rendered.addProperty("keyId",keyId);rendered.addProperty("keySha256",StorageConfigArchive.sha256(protectedKey));
            JsonObject snapshot=new JsonObject();snapshot.add("renderedGeneration",rendered);JsonObject original=new JsonObject();original.addProperty("capsuleSha256","e".repeat(64));snapshot.add("nativeIdentityCapsule",original);op.setPreviousSnapshotJson(snapshot.toString());org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao operations=Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao.class);Mockito.when(operations.update(Mockito.anyLong(),Mockito.any())).thenReturn(true);ReflectionTestUtils.setField(m,"storageOperationDao",operations);
            org.apache.cloudstack.storage.dataservice.dao.StorageIdentityDomainDao domains=Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageIdentityDomainDao.class);StorageIdentityDomainVO domain=Mockito.mock(StorageIdentityDomainVO.class);Mockito.when(domain.getConfigJson()).thenReturn("{\"identityReceipt\":{\"machineSid\":\"S-1-5-21-1-2-3\"}}");Mockito.when(domains.findByInstanceId(6L)).thenReturn(domain);ReflectionTestUtils.setField(m,"storageIdentityDomainDao",domains);
            StorageServiceManagerImpl.RenderedBatch batch=new StorageServiceManagerImpl.RenderedBatch(op,new JsonObject(),new JsonObject(),pair);batch.importedRootAuthorization=new JsonObject();batch.importedRootAuthorization.addProperty("authorizationUuid","66666666-6666-6666-6666-666666666666");batch.importedRootAuthorization.addProperty("sha256","f".repeat(64));JsonObject receipt=new JsonObject(),staged=new JsonObject();receipt.addProperty("phase","GENERATION_COMMITTED");staged.addProperty("configurationSha256","b".repeat(64));staged.addProperty("renderedManifestSha256","c".repeat(64));receipt.add("staged",staged);batch.receipt=receipt;
            StorageServiceGuestCommandDispatcher guest=Mockito.mock(StorageServiceGuestCommandDispatcher.class);
            Mockito.when(guest.dispatch(Mockito.any())).thenAnswer(call->{StorageServiceGuestCommand command=call.getArgument(0);
            m.events.add(command.getOperation());
            Assert.assertEquals("identity capsule export-root-target",command.getOperation());
            Assert.assertEquals(java.util.Set.of("capsule"),command.getMaskedFields());
            JsonObject frame=com.google.gson.JsonParser.parseString(command.getPayload()).getAsJsonObject();
            Assert.assertTrue(frame.has("importedRootAuthorization"));
            Assert.assertFalse(frame.has("retainedRootAuthorization"));
            Assert.assertTrue(frame.get("includePosixPolicyReceipts").getAsBoolean());
            JsonObject cipher=cipher(m.scope),checkpoint=new JsonObject();
            checkpoint.addProperty("kind",m.wrongCipherKind?"SERVICE_SOURCE_IDENTITY_CHECKPOINT":"ROOT_TARGET_IDENTITY_CHECKPOINT");
            checkpoint.add("scope",m.scope.deepCopy());
            checkpoint.add("capsuleSha256",cipher.get("sha256"));
            checkpoint.addProperty("targetConfigurationSha256","b".repeat(64));
            checkpoint.addProperty("checkpointRecordSha256","a".repeat(64));
            JsonObject result=new JsonObject();
            result.addProperty("success",true);
            result.add("capsule",cipher);
            result.add("rootIdentityCheckpoint",m.wrongCipherKind?checkpoint:actualNativeRootTargetCheckpoint(frame,cipher));
            return new StorageServiceGuestCommandResult(true,"cipher",result.toString());
            });
            ReflectionTestUtils.setField(m,"guestCommandDispatcher",guest);
            for(String fault:List.of("pendingGeneration","changedBoot","failedStop","twoAuthorities","sourceAsTarget")) {
                op.setPreviousSnapshotJson(snapshot.toString());m.events.clear();m.status.remove("pendingOperationUuid");m.wrongBoot=false;m.wrongStop=false;m.wrongCipherKind=false;batch.retainedRootAuthorization=null;Mockito.clearInvocations(guest);
                if(fault.equals("pendingGeneration"))m.status.addProperty("pendingOperationUuid",op.getUuid());if(fault.equals("changedBoot"))m.wrongBoot=true;if(fault.equals("failedStop"))m.wrongStop=true;if(fault.equals("twoAuthorities"))batch.retainedRootAuthorization=batch.importedRootAuthorization.deepCopy();if(fault.equals("sourceAsTarget"))m.wrongCipherKind=true;
                Assert.assertThrows(fault,RuntimeException.class,()->m.prepareRootLkgTargetIdentity(instance,op,batch,m.scope,receipt));JsonObject rejected=com.google.gson.JsonParser.parseString(op.getPreviousSnapshotJson()).getAsJsonObject();Assert.assertFalse(rejected.has("rootLkgTargetIdentity"));Assert.assertEquals(original,rejected.get("nativeIdentityCapsule"));if(!fault.equals("sourceAsTarget"))Mockito.verifyNoInteractions(guest);if(fault.equals("pendingGeneration")||fault.equals("twoAuthorities"))Assert.assertTrue(m.events.isEmpty());
            }
            op.setPreviousSnapshotJson(snapshot.toString());m.events.clear();m.status.remove("pendingOperationUuid");m.wrongBoot=false;m.wrongStop=false;m.wrongCipherKind=false;batch.retainedRootAuthorization=null;Mockito.clearInvocations(guest);
            m.failResume=true;Assert.assertThrows(RuntimeException.class,()->m.prepareRootLkgTargetIdentity(instance,op,batch,m.scope,receipt));JsonObject stopped=com.google.gson.JsonParser.parseString(op.getPreviousSnapshotJson()).getAsJsonObject();Assert.assertTrue(stopped.has("rootLkgTargetIdentity"));Assert.assertFalse(stopped.has("rootLkgTargetResumed"));Assert.assertEquals(original,stopped.get("nativeIdentityCapsule"));Assert.assertThrows(RuntimeException.class,()->m.retainLkgTargetIdentity(instance,op,"77777777-7777-7777-7777-777777777777"));
            m.failResume=false;m.prepareRootLkgTargetIdentity(instance,op,batch,m.scope,receipt);Mockito.verify(guest,Mockito.times(1)).dispatch(Mockito.any());Assert.assertEquals(1,m.events.stream().filter(x->x.endsWith("quiesce-target")).count());Assert.assertTrue(m.events.indexOf("operation generation render-root-quiesce-target")<m.events.indexOf("identity capsule export-root-target"));Assert.assertTrue(m.events.indexOf("identity capsule export-root-target")<m.events.indexOf("operation generation render-root-resume-target"));
            ThreadLocal<StorageServiceManagerImpl.RenderedBatch> batches=(ThreadLocal<StorageServiceManagerImpl.RenderedBatch>)ReflectionTestUtils.getField(m,"renderedBatch");batches.set(batch);Assert.assertEquals("ROOT_LKG_TARGET",m.retainLkgTargetIdentity(instance,op,"77777777-7777-7777-7777-777777777777").get("checkpointRole").getAsString());
            receipt.addProperty("phase","RELEASED");m.events.clear();m.prepareRootLkgTargetIdentity(instance,op,batch,m.scope,receipt);Assert.assertEquals(List.of("ALL4_AFTER_RELEASE_VERIFY"),m.events);
        }finally {if(oldIdentity==null)System.clearProperty("cloudstack.storage.identity.path");else System.setProperty("cloudstack.storage.identity.path",oldIdentity);if(oldKeys==null)System.clearProperty("cloudstack.storage.rendered.keys.path");else System.setProperty("cloudstack.storage.rendered.keys.path",oldKeys);}
    }
    private static JsonObject actualNativeRootTargetCheckpoint(JsonObject request,JsonObject capsule) throws Exception {
        java.nio.file.Path root=java.nio.file.Path.of(System.getProperty("user.dir")).toAbsolutePath();while(!java.nio.file.Files.exists(root.resolve("systemvm/debian/usr/local/bin/ablestack-storagectl")))root=root.getParent();String cli=System.getProperty("cloudstack.storage.ad.proof.cli",root.resolve("systemvm/debian/usr/local/bin/ablestack-storagectl").toString());
        String code=String.join("\n",
                "import ast,base64,hashlib,json,os,sys,tempfile,uuid",
                "from pathlib import Path",
                "from types import SimpleNamespace",
                "source=Path(sys.argv[1]).read_text().split(\"<<'PYIDENTITY'\\n\",1)[1].split(\"\\nPYIDENTITY\",1)[0]",
                "names={'ServiceTargetCipher','RootTargetCipher','RootIdentityReference','service_cipher_digest'}",
                "definitions=[n for n in ast.parse(source).body if isinstance(n,(ast.FunctionDef,ast.ClassDef)) and n.name in names]",
                "assert {n.name for n in definitions}==names",
                "namespace={'Path':Path,'os':os,'json':json,'hashlib':hashlib,'base64':base64,'uuid':uuid}",
                "exec(compile(ast.Module(body=definitions,type_ignores=[]),sys.argv[1],'exec'),namespace)",
                "data=json.load(sys.stdin);request=data['request'];scope={k:request[k] for k in ('instanceUuid','templateUpgradeUuid','operationUuid','revision')}",
                "digest=namespace['service_cipher_digest'];generation={k:scope[k] for k in ('instanceUuid','operationUuid','revision')}",
                "stop={'scope':scope,'bootId':'33333333-3333-3333-3333-333333333333','stopped':True}",
                "saved={'kind':'ROOT_IDENTITY_TARGET','scope':scope,'phase':'STOPPED','targetGeneration':generation,'targetConfigurationSha256':request['targetConfigurationSha256'],'targetStoppedReceipt':stop,'bootId':stop['bootId']}",
                "target={'scope':scope,'rootTargetStoppedVerified':True,'targetGeneration':generation,'targetConfigurationSha256':saved['targetConfigurationSha256'],'stoppedReceiptSha256':digest(stop)}",
                "key={'scope':scope,'targetWrappingKeyVerified':True,'originalCapsuleSha256':'e'*64}",
                "with tempfile.TemporaryDirectory() as directory:",
                " publisher=namespace['RootTargetCipher'].__new__(namespace['RootTargetCipher']);publisher.root=Path(directory);publisher.files=SimpleNamespace(read=lambda path:saved,write=lambda path,value:None)",
                " result=publisher.retain(request,data['capsule'],target,key)",
                " print(json.dumps(result,sort_keys=True))");
        Process process=new ProcessBuilder("python3","-c",code,cli).start();JsonObject payload=new JsonObject();payload.add("request",request.deepCopy());payload.add("capsule",capsule.deepCopy());try(java.io.OutputStream stream=process.getOutputStream()){stream.write(payload.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}String output=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8),error=new String(process.getErrorStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);Assert.assertTrue(error.isEmpty());Assert.assertEquals(0,process.waitFor());return com.google.gson.JsonParser.parseString(output).getAsJsonObject();
    }
}
