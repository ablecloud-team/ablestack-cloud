// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.
package org.apache.cloudstack.storage.dataservice;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Set;
import java.util.Base64;
import com.google.gson.JsonObject;
import org.junit.Test;
import org.junit.Assert;

public class StorageAdSemanticSourceTest {
    private Object previousDbEncryptor,previousCheckerEncryptor;private boolean previousUseEncryption;
    @org.junit.Before public void enableProtectedFixtureEncryption(){
        previousDbEncryptor=org.springframework.test.util.ReflectionTestUtils.getField(com.cloud.utils.crypt.DBEncryptionUtil.class,"s_encryptor");
        previousCheckerEncryptor=org.springframework.test.util.ReflectionTestUtils.getField(com.cloud.utils.crypt.EncryptionSecretKeyChecker.class,"s_encryptor");
        previousUseEncryption=com.cloud.utils.crypt.EncryptionSecretKeyChecker.useEncryption();
        com.cloud.utils.crypt.EncryptionSecretKeyChecker.initEncryptor("ephemeral-test-only-master-secret");
        org.springframework.test.util.ReflectionTestUtils.setField(com.cloud.utils.crypt.DBEncryptionUtil.class,"s_encryptor",new com.cloud.utils.crypt.CloudStackEncryptor("ephemeral-test-only-master-secret",null,com.cloud.utils.crypt.DBEncryptionUtil.class));
    }
    @org.junit.After public void restoreProtectedFixtureEncryption(){
        org.springframework.test.util.ReflectionTestUtils.setField(com.cloud.utils.crypt.DBEncryptionUtil.class,"s_encryptor",previousDbEncryptor);
        org.springframework.test.util.ReflectionTestUtils.setField(com.cloud.utils.crypt.EncryptionSecretKeyChecker.class,"s_encryptor",previousCheckerEncryptor);
        org.springframework.test.util.ReflectionTestUtils.setField(com.cloud.utils.crypt.EncryptionSecretKeyChecker.class,"s_useEncryption",previousUseEncryption);
    }
    private static final String INSTANCE="11111111-1111-1111-1111-111111111111",OP="22222222-2222-2222-2222-222222222222",ARTIFACT="33333333-3333-3333-3333-333333333333",KEY="44444444-4444-4444-4444-444444444444";
    private static final class Fixture {Path root;StorageConfigArtifactStore store;JsonObject capsule,nativeRef,retained;KeyPair key;}
    private Fixture fixture() throws Exception {return fixture(false);}
    private Fixture fixture(boolean realCipher) throws Exception {
        Fixture f=new Fixture();f.root=Files.createTempDirectory("ad-semantic-source-");f.store=new StorageConfigArtifactStore(f.root);f.key=StorageIdentityCapsule.wrappingKey();
        byte[] cipher=new byte[32];java.util.Arrays.fill(cipher,(byte)7);
        f.capsule=new JsonObject();f.capsule.addProperty("schemaVersion",1);f.capsule.addProperty("scope",INSTANCE+":"+OP);f.capsule.addProperty("nonce",Base64.getEncoder().encodeToString(new byte[12]));f.capsule.addProperty("wrappedKey",Base64.getEncoder().encodeToString(new byte[256]));f.capsule.addProperty("ciphertext",Base64.getEncoder().encodeToString(cipher));f.capsule.addProperty("sha256",StorageConfigArchive.sha256(cipher));
        if(realCipher){
            JsonObject payload=new JsonObject();payload.addProperty("schemaVersion",1);payload.add("files",new JsonObject());payload.add("accounts",new JsonObject());payload.addProperty("sourceConfigurationSha256","a".repeat(64));payload.add("adIdentity",originalIdentity());
            byte[] aes=new byte[32],nonce=new byte[12];java.security.SecureRandom random=new java.security.SecureRandom();random.nextBytes(aes);random.nextBytes(nonce);
            javax.crypto.Cipher encrypted=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");encrypted.init(javax.crypto.Cipher.ENCRYPT_MODE,new javax.crypto.spec.SecretKeySpec(aes,"AES"),new javax.crypto.spec.GCMParameterSpec(128,nonce));encrypted.updateAAD((INSTANCE+":"+OP).getBytes(StandardCharsets.UTF_8));cipher=encrypted.doFinal(payload.toString().getBytes(StandardCharsets.UTF_8));
            javax.crypto.Cipher wrapped=javax.crypto.Cipher.getInstance("RSA/ECB/OAEPPadding");wrapped.init(javax.crypto.Cipher.ENCRYPT_MODE,f.key.getPublic(),new javax.crypto.spec.OAEPParameterSpec("SHA-256","MGF1",java.security.spec.MGF1ParameterSpec.SHA256,javax.crypto.spec.PSource.PSpecified.DEFAULT));
            f.capsule.addProperty("wrappedKey",Base64.getEncoder().encodeToString(wrapped.doFinal(aes)));f.capsule.addProperty("nonce",Base64.getEncoder().encodeToString(nonce));f.capsule.addProperty("ciphertext",Base64.getEncoder().encodeToString(cipher));f.capsule.addProperty("sha256",StorageConfigArchive.sha256(cipher));java.util.Arrays.fill(aes,(byte)0);
        }
        JsonObject scope=new JsonObject();scope.addProperty("instanceUuid",INSTANCE);scope.addProperty("operationUuid",OP);scope.addProperty("maintenanceUuid",OP);scope.addProperty("revision",4);
        JsonObject receipt=new JsonObject();receipt.addProperty("kind","SERVICE_SOURCE_IDENTITY_CHECKPOINT");receipt.add("scope",scope);receipt.addProperty("capsuleSha256",f.capsule.get("sha256").getAsString());receipt.addProperty("sourceConfigurationSha256","a".repeat(64));receipt.addProperty("checkpointRecordSha256","d".repeat(64));
        String originalKey=java.util.UUID.nameUUIDFromBytes(("identity-key:"+OP).getBytes(StandardCharsets.UTF_8)).toString();byte[] bytes=f.capsule.toString().getBytes(StandardCharsets.UTF_8),protectedFixture=StorageIdentityCapsule.protectedPrivateKey(f.key);f.store.write(OP,bytes);f.store.write(originalKey,protectedFixture);
        f.nativeRef=new JsonObject();f.nativeRef.add("sourceMaintenanceScope",scope);f.nativeRef.addProperty("sourceConfigurationSha256","a".repeat(64));f.nativeRef.addProperty("capsuleSha256",StorageConfigArchive.sha256(bytes));f.nativeRef.addProperty("operationUuid",OP);f.nativeRef.addProperty("keyId",originalKey);f.nativeRef.addProperty("keySha256",StorageConfigArchive.sha256(protectedFixture));f.nativeRef.add("sourceServiceIdentityCheckpoint",receipt);
        f.retained=StorageAdSemanticSource.retain(f.store,ARTIFACT,INSTANCE,OP,f.nativeRef,f.key.getPrivate());return f;
    }
    private JsonObject descriptor(Fixture f){return f.retained.getAsJsonObject("descriptor").deepCopy();}
    @Test public void descriptorContainsOnlyClosedPublicOwnershipAndMacFields() throws Exception {
        Fixture f=fixture();JsonObject descriptor=descriptor(f);
        Assert.assertEquals(Set.of("schemaVersion","kind","ownerArtifactUuid","sourceInstanceUuid","sourceOperationUuid","sourceConfigurationSha256","ciphertextSha256","issuerMac"),descriptor.keySet());
        Assert.assertEquals(f.capsule,StorageAdSemanticSource.authenticate(f.store,descriptor,f.retained,"b".repeat(64),"b".repeat(64),f.key.getPrivate()));
        Assert.assertFalse(descriptor.toString().contains("PRIVATE KEY"));Assert.assertFalse(descriptor.has("keyId"));Assert.assertFalse(descriptor.has("wrappedKey"));
        Assert.assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),Files.getPosixFilePermissions(f.root.resolve(f.retained.get("keyId").getAsString()+".zip")));
    }
    @Test public void unchangedRetryPreservesOwnedCipherAndKeyWithoutReplacingBytes() throws Exception {
        Fixture f=fixture();JsonObject retry=StorageAdSemanticSource.retain(f.store,ARTIFACT,INSTANCE,OP,f.nativeRef,f.key.getPrivate());Assert.assertEquals(f.retained,retry);
        try(java.util.stream.Stream<Path> files=Files.list(f.root)){Assert.assertEquals(4,files.count());}
    }
    @Test public void forgedDescriptorOrArchiveCannotIssueOriginalSourceAuthority() throws Exception {
        Fixture f=fixture();JsonObject wrong=descriptor(f);wrong.addProperty("sourceInstanceUuid",KEY);
        Assert.assertThrows(RuntimeException.class,()->StorageAdSemanticSource.authenticate(f.store,wrong,f.retained,"b".repeat(64),"b".repeat(64),f.key.getPrivate()));
        Assert.assertThrows(RuntimeException.class,()->StorageAdSemanticSource.authenticate(f.store,descriptor(f),f.retained,"c".repeat(64),"b".repeat(64),f.key.getPrivate()));
        JsonObject forged=descriptor(f);forged.addProperty("issuerMac","0".repeat(64));JsonObject untrusted=f.retained.deepCopy();untrusted.add("descriptor",forged);
        Assert.assertThrows(RuntimeException.class,()->StorageAdSemanticSource.authenticate(f.store,forged,untrusted,"b".repeat(64),"b".repeat(64),f.key.getPrivate()));
    }
    @Test public void differentWrappingKeyRejectsBeforeOriginalCipherOrKeyChanges() throws Exception {
        Fixture f=fixture();KeyPair other=StorageIdentityCapsule.wrappingKey();byte[] before=f.store.read(f.retained.get("cipherId").getAsString(),f.retained.get("cipherDataSha256").getAsString());
        Assert.assertThrows(RuntimeException.class,()->StorageAdSemanticSource.authenticate(f.store,descriptor(f),f.retained,"b".repeat(64),"b".repeat(64),other.getPrivate()));
        Assert.assertArrayEquals(before,f.store.read(f.retained.get("cipherId").getAsString(),f.retained.get("cipherDataSha256").getAsString()));
    }
    @Test public void sourceCleanupCannotDeleteForeignVaultEntry() throws Exception {
        Fixture f=fixture();String foreign="55555555-5555-5555-5555-555555555555";byte[] bytes="foreign-preserved".getBytes(StandardCharsets.UTF_8);f.store.write(foreign,bytes);
        JsonObject wrong=f.retained.deepCopy();wrong.addProperty("keyId",foreign);Assert.assertThrows(RuntimeException.class,()->StorageAdSemanticSource.remove(f.store,wrong));
        Assert.assertArrayEquals(bytes,f.store.read(foreign,StorageConfigArchive.sha256(bytes)));Assert.assertTrue(f.store.contains(f.retained.get("cipherId").getAsString()));
        StorageAdSemanticSource.remove(f.store,f.retained);Assert.assertFalse(f.store.contains(f.retained.get("keyId").getAsString()));Assert.assertTrue(f.store.contains(foreign));
    }
    @Test public void descriptorRejectsStringSchemaExtraSecretAndMalformedMac() throws Exception {
        Fixture f=fixture();for(String field:Set.of("schemaVersion","privateKey","issuerMac")){JsonObject wrong=descriptor(f);wrong.addProperty(field,field.equals("issuerMac")?"invalid":"1");Assert.assertThrows(RuntimeException.class,()->StorageAdSemanticSource.validateDescriptor(wrong));}
    }
    @Test public void issuerMacMatchesPythonOnProtectedStdinWithoutPrivateFilesOrArgv() throws Exception {
        Fixture f=fixture();JsonObject request=new JsonObject();request.add("descriptor",descriptor(f));request.getAsJsonObject("descriptor").remove("issuerMac");request.addProperty("privateDer",Base64.getEncoder().encodeToString(f.key.getPrivate().getEncoded()));
        String script="import sys,json,hmac,hashlib,base64;v=json.load(sys.stdin);print(hmac.new(hashlib.sha256(base64.b64decode(v['privateDer'])).digest(),json.dumps(v['descriptor'],sort_keys=True,separators=(',',':')).encode(),hashlib.sha256).hexdigest())";
        Process process=new ProcessBuilder("python3","-c",script).start();try(java.io.OutputStream input=process.getOutputStream()){input.write(request.toString().getBytes(StandardCharsets.UTF_8));}
        String result=new String(process.getInputStream().readAllBytes(),StandardCharsets.US_ASCII).trim();Assert.assertEquals(0,process.waitFor());Assert.assertEquals(descriptor(f).get("issuerMac").getAsString(),result);
    }
    @Test public void mixedOwnerVaultScopeAndKeyKindsRejectBeforeSourceRead() throws Exception {
        Fixture f=fixture();for(String field:Set.of("cipherId","keyId","keyBlobKind","sourceMaintenanceScope","sourceServiceIdentityCheckpoint")){
            JsonObject wrong=f.retained.deepCopy();
            if(field.equals("sourceMaintenanceScope"))wrong.getAsJsonObject(field).addProperty("revision","4");
            else if(field.equals("sourceServiceIdentityCheckpoint"))wrong.getAsJsonObject(field).addProperty("sourceConfigurationSha256","f".repeat(64));
            else wrong.addProperty(field,KEY);
            Assert.assertThrows(field,RuntimeException.class,()->StorageAdSemanticSource.authenticate(f.store,descriptor(f),wrong,"b".repeat(64),"b".repeat(64),f.key.getPrivate()));
        }
        JsonObject wrong=f.nativeRef.deepCopy();wrong.addProperty("keyId",KEY);
        Assert.assertThrows(RuntimeException.class,()->StorageAdSemanticSource.retain(f.store,ARTIFACT,INSTANCE,OP,wrong,f.key.getPrivate()));
    }

    @Test public void realZipArchiveCarriesPublicDescriptorAndRejectsRawIdentityEntries() throws Exception {
        Fixture f=fixture();java.util.Map<String,byte[]> entries=new java.util.LinkedHashMap<>();
        entries.put("desired/instance.json","{}".getBytes(StandardCharsets.UTF_8));entries.put(StorageAdSemanticSource.ZIP_ENTRY,descriptor(f).toString().getBytes(StandardCharsets.UTF_8));
        JsonObject publicMetadata=new JsonObject();publicMetadata.addProperty("adIdentityCoverage","VERIFIED_ENCRYPTED_FULL_IDENTITY");publicMetadata.add("adIdentitySourceDescriptor",descriptor(f));
        byte[] zip=StorageConfigArchive.create(entries,publicMetadata);java.util.Map<String,byte[]> restored=StorageConfigArchive.validate(zip);
        Assert.assertEquals(descriptor(f),StorageConfigArchive.json(restored.get(StorageAdSemanticSource.ZIP_ENTRY)));
        Assert.assertFalse(restored.containsKey("identity/capsule.json"));Assert.assertFalse(StorageConfigArchive.json(restored.get("manifest.json")).getAsJsonObject().has("adIdentityCipherReference"));
        java.util.Map<String,byte[]> raw=new java.util.LinkedHashMap<>(entries);raw.put("identity/capsule.json",f.capsule.toString().getBytes(StandardCharsets.UTF_8));
        Assert.assertThrows(RuntimeException.class,()->StorageConfigArchive.create(raw,publicMetadata));
    }

    @Test public void descriptorIssuerCannotSubstituteAnotherRealRsaForCapsuleWrappingKey() throws Exception {
        Fixture f=fixture();KeyPair differentMasterRsa=StorageIdentityCapsule.wrappingKey();
        Assert.assertThrows(RuntimeException.class,()->StorageAdSemanticSource.retain(f.store,"66666666-6666-6666-6666-666666666666",INSTANCE,OP,f.nativeRef,differentMasterRsa.getPrivate()));
        byte[] original=f.store.read(f.retained.get("keyId").getAsString(),f.retained.get("keyDataSha256").getAsString());
        java.security.PrivateKey unwrapped=StorageIdentityCapsule.unwrapProtectedPrivateKey(original);
        Assert.assertArrayEquals(f.key.getPrivate().getEncoded(),unwrapped.getEncoded());Assert.assertFalse(java.util.Arrays.equals(differentMasterRsa.getPrivate().getEncoded(),unwrapped.getEncoded()));
        Assert.assertEquals(f.capsule,StorageAdSemanticSource.authenticate(f.store,descriptor(f),f.retained,"b".repeat(64),"b".repeat(64),unwrapped));
    }

    private JsonObject originalIdentity() {
        return com.google.gson.JsonParser.parseString("{\"schemaVersion\":1,\"domain\":\"example.test\",\"realm\":\"EXAMPLE.TEST\",\"workgroup\":\"EXAMPLE\",\"netbiosName\":\"ORIGINAL\",\"machineSid\":\"S-1-5-21-9-8-7\",\"domainSid\":\"S-1-5-21-4-5-6\",\"machineAccountSid\":\"S-1-5-21-4-5-6-1000\",\"trustVerified\":true,\"machineConfigurationSha256\":\""+"f".repeat(64)+"\",\"servicePrincipals\":[\"cifs/original.example.test\",\"host/original.example.test\"],\"dnsAliases\":[{\"hostname\":\"original.example.test\",\"addresses\":[\"10.10.13.239\"]}],\"idmapPolicy\":{\"default\":{\"backend\":\"tdb\",\"range\":[10000,60000]},\"domain\":{\"backend\":\"rid\",\"range\":[1000000,1999999],\"baseRid\":0}}}").getAsJsonObject();
    }
    @Test public void javaIssuedRealEncryptedSourceRoundTripsThroughNativeCli() throws Exception {
        Fixture f=fixture(true);Path root=Path.of(System.getProperty("user.dir")).toAbsolutePath();while(!Files.exists(root.resolve("systemvm/debian/usr/local/bin/ablestack-storagectl")))root=root.getParent();
        Path nativeSource=Path.of(System.getProperty("cloudstack.storage.ad.proof.cli",root.resolve("systemvm/debian/usr/local/bin/ablestack-storagectl").toString()));byte[] bytes=Files.readAllBytes(nativeSource);Path cli=Files.createTempFile("ad-semantic-verified-",".sh");Files.write(cli,bytes);Files.setPosixFilePermissions(cli,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        JsonObject request=new JsonObject();request.addProperty("instanceUuid","77777777-7777-4777-8777-777777777777");request.addProperty("operationUuid","88888888-8888-4888-8888-888888888888");request.addProperty("revision",2);request.add("originalSourceAuthority",descriptor(f));request.add("originalSourceCapsule",f.capsule);
        byte[] protectedKey=f.store.read(f.retained.get("keyId").getAsString(),f.retained.get("keyDataSha256").getAsString());java.security.PrivateKey key=StorageIdentityCapsule.unwrapProtectedPrivateKey(protectedKey);request.addProperty("originalSourceCredentialPrivateKey",StorageIdentityCapsule.pem("PRIVATE KEY",key.getEncoded()));
        try {
            Process process=new ProcessBuilder(cli.toString(),"identity","capsule","semantic-original","/dev/stdin").start();try(java.io.OutputStream stdin=process.getOutputStream()){stdin.write(request.toString().getBytes(StandardCharsets.UTF_8));}
            String output=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8),error=new String(process.getErrorStream().readAllBytes(),StandardCharsets.UTF_8);Assert.assertEquals("Committed native decoder rejected real Java source",0,process.waitFor());Assert.assertFalse(output.contains("PRIVATE KEY"));Assert.assertFalse(error.contains("PRIVATE KEY"));
            JsonObject result=com.google.gson.JsonParser.parseString(output).getAsJsonObject();Assert.assertEquals(Set.of("success","scope","originalSourceAuthority","originalIdentity"),result.keySet());Assert.assertEquals(originalIdentity(),result.get("originalIdentity"));Assert.assertEquals(descriptor(f),result.get("originalSourceAuthority"));
            request.addProperty("originalSourceCredentialPrivateKey",StorageIdentityCapsule.pem("PRIVATE KEY",StorageIdentityCapsule.wrappingKey().getPrivate().getEncoded()));
            Process wrong=new ProcessBuilder(cli.toString(),"identity","capsule","semantic-original","/dev/stdin").start();try(java.io.OutputStream stdin=wrong.getOutputStream()){stdin.write(request.toString().getBytes(StandardCharsets.UTF_8));}String rejected=new String(wrong.getInputStream().readAllBytes(),StandardCharsets.UTF_8)+new String(wrong.getErrorStream().readAllBytes(),StandardCharsets.UTF_8);Assert.assertNotEquals(0,wrong.waitFor());Assert.assertFalse(rejected.contains("PRIVATE KEY"));
        }finally{Files.deleteIfExists(cli);}
    }
    private final class ApplyFixture {
        Fixture identity;StorageServiceManagerImpl manager;org.apache.cloudstack.storage.dataservice.dao.StorageConfigArtifactDao artifacts;org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao operations;StorageServiceConfiguration configuration;StorageServiceInstanceVO owner;StorageConfigArtifactVO original,imported;StorageServiceOperationVO sourceOperation;StorageConfigRequest request;JsonObject archiveMetadata,importMetadata,plan;
    }
    private ApplyFixture applyFixture() throws Exception {
        ApplyFixture a=new ApplyFixture();a.identity=fixture(true);a.manager=org.mockito.Mockito.mock(StorageServiceManagerImpl.class);a.artifacts=org.mockito.Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageConfigArtifactDao.class);a.operations=org.mockito.Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao.class);a.owner=org.mockito.Mockito.mock(StorageServiceInstanceVO.class);org.mockito.Mockito.when(a.owner.getId()).thenReturn(7L);org.mockito.Mockito.when(a.owner.getUuid()).thenReturn(INSTANCE);org.mockito.Mockito.when(a.owner.getName()).thenReturn("fixture");org.mockito.Mockito.when(a.manager.requireInstance(7L)).thenReturn(a.owner);
        a.archiveMetadata=new JsonObject();a.archiveMetadata.addProperty("adIdentityCoverage","VERIFIED_ENCRYPTED_FULL_IDENTITY");a.archiveMetadata.add("adIdentitySourceDescriptor",descriptor(a.identity));a.archiveMetadata.add("adIdentityCipherReference",a.identity.retained);
        java.util.Map<String,byte[]> entries=new java.util.LinkedHashMap<>();entries.put("desired/instance.json","{}".getBytes(StandardCharsets.UTF_8));entries.put(StorageAdSemanticSource.ZIP_ENTRY,descriptor(a.identity).toString().getBytes(StandardCharsets.UTF_8));JsonObject publicMetadata=a.archiveMetadata.deepCopy();publicMetadata.remove("adIdentityCipherReference");byte[] archive=StorageConfigArchive.create(entries,publicMetadata);
        a.original=new StorageConfigArtifactVO();org.springframework.test.util.ReflectionTestUtils.setField(a.original,"id",9L);org.springframework.test.util.ReflectionTestUtils.setField(a.original,"uuid",ARTIFACT);a.original.setInstanceId(7L);a.original.setKind("BACKUP");a.original.setState("COMPLETE");a.original.setExpires(new java.util.Date(System.currentTimeMillis()+600000));a.original.setSha256(StorageConfigArchive.sha256(archive));a.original.setMetadataJson(a.archiveMetadata.toString());
        a.imported=new StorageConfigArtifactVO();org.springframework.test.util.ReflectionTestUtils.setField(a.imported,"id",8L);a.imported.setInstanceId(7L);a.imported.setKind("IMPORT");a.imported.setState("AVAILABLE");a.imported.setSha256(a.original.getSha256());a.imported.setExpires(new java.util.Date(System.currentTimeMillis()+600000));a.imported.setSize(archive.length);
        a.sourceOperation=org.mockito.Mockito.mock(StorageServiceOperationVO.class);org.mockito.Mockito.when(a.sourceOperation.getUuid()).thenReturn(OP);org.mockito.Mockito.when(a.sourceOperation.getState()).thenReturn("COMPLETE");org.mockito.Mockito.when(a.sourceOperation.getRevision()).thenReturn(4L);org.mockito.Mockito.when(a.operations.listByInstance(7L)).thenReturn(java.util.List.of(a.sourceOperation));org.mockito.Mockito.when(a.artifacts.findByUuid(ARTIFACT)).thenReturn(a.original);org.mockito.Mockito.when(a.artifacts.findById(8L)).thenReturn(a.imported);
        StorageConfigArtifactStore archives=new StorageConfigArtifactStore(Files.createTempDirectory("ad-semantic-archives-"));archives.write(a.imported.getUuid(),archive);a.configuration=new StorageServiceConfiguration(a.manager,a.artifacts,a.operations,archives);
        a.plan=new JsonObject();a.plan.addProperty("targetMode","RESTORE_EXISTING");a.plan.addProperty("targetInstanceUuid",INSTANCE);a.plan.addProperty("targetName","fixture");a.plan.addProperty("artifactSha256",a.imported.getSha256());a.plan.addProperty("expectedRevision",4);a.plan.add("requiredCredentials",new com.google.gson.JsonArray());a.plan.add("adIdentitySourceDescriptor",descriptor(a.identity));a.plan.addProperty("adIdentityRestoreRequiresMaintenance",true);
        JsonObject capability=new JsonObject();capability.addProperty("user",11);capability.addProperty("expires",System.currentTimeMillis()+600000);capability.addProperty("hash",StorageConfigArchive.sha256("synthetic-review-token".getBytes(StandardCharsets.UTF_8)));capability.addProperty("planSha256",StorageConfigArchive.sha256(a.plan.toString().getBytes(StandardCharsets.UTF_8)));capability.addProperty("baselineSha256",StorageConfigArchive.sha256("{}".getBytes(StandardCharsets.UTF_8)));a.importMetadata=new JsonObject();a.importMetadata.addProperty("planState","PLANNED");a.importMetadata.add("plan",a.plan);a.importMetadata.add("planToken",capability);a.imported.setMetadataJson(a.importMetadata.toString());
        a.request=org.mockito.Mockito.mock(StorageConfigRequest.class);org.mockito.Mockito.when(a.request.getArtifactId()).thenReturn(8L);org.mockito.Mockito.when(a.request.getPlanToken()).thenReturn("synthetic-review-token");org.mockito.Mockito.when(a.request.getConfirmation()).thenReturn("fixture");org.mockito.Mockito.when(a.request.getMaintenanceWindow()).thenReturn(false);return a;
    }
    private void applyRejectedBeforeEffects(ApplyFixture a) throws Exception {
        java.lang.reflect.Method apply=StorageServiceConfiguration.class.getDeclaredMethod("applyLocked",StorageServiceInstanceVO.class,StorageConfigRequest.class);apply.setAccessible(true);
        java.lang.reflect.InvocationTargetException rejected=Assert.assertThrows(java.lang.reflect.InvocationTargetException.class,()->apply.invoke(a.configuration,a.owner,a.request));Assert.assertTrue(rejected.getCause() instanceof RuntimeException);
        Assert.assertTrue(com.google.gson.JsonParser.parseString(a.imported.getMetadataJson()).getAsJsonObject().has("planToken"));org.mockito.Mockito.verify(a.artifacts,org.mockito.Mockito.never()).update(org.mockito.Mockito.anyLong(),org.mockito.Mockito.any());org.mockito.Mockito.verify(a.manager,org.mockito.Mockito.never()).createConfigurationNewService(org.mockito.Mockito.any(), org.mockito.Mockito.any(), org.mockito.Mockito.any());org.mockito.Mockito.verify(a.manager,org.mockito.Mockito.never()).executeProtectedIdentityConfiguration(org.mockito.Mockito.any(),org.mockito.Mockito.any(),org.mockito.Mockito.any(),org.mockito.Mockito.any(),org.mockito.Mockito.any());
    }
    @Test public void protectedApplyReauthenticatesExpiryOwnerArchiveOperationAndKeyBeforeConsumption() throws Exception {
        com.cloud.user.User user=org.mockito.Mockito.mock(com.cloud.user.User.class);org.mockito.Mockito.when(user.getId()).thenReturn(11L);org.apache.cloudstack.context.CallContext.register(user,org.mockito.Mockito.mock(com.cloud.user.Account.class));String previous=System.getProperty("cloudstack.storage.identity.path");
        try {
            for(String fault:java.util.List.of("maintenance","expiry","owner","archive","operation","cipher","key")){
                ApplyFixture a=applyFixture();System.setProperty("cloudstack.storage.identity.path",a.identity.root.toString());org.mockito.Mockito.when(a.request.getMaintenanceWindow()).thenReturn(!fault.equals("maintenance"));
                if(fault.equals("expiry"))a.original.setExpires(new java.util.Date(1));if(fault.equals("owner"))org.mockito.Mockito.when(a.owner.getUuid()).thenReturn(KEY);if(fault.equals("archive"))a.original.setSha256("f".repeat(64));if(fault.equals("operation"))org.mockito.Mockito.when(a.sourceOperation.getState()).thenReturn("RECOVERY_REQUIRED");
                if(fault.equals("cipher")||fault.equals("key"))Files.write(a.identity.root.resolve(a.identity.retained.get(fault.equals("key")?"keyId":"cipherId").getAsString()+".zip"),"changed-protected-source".getBytes(StandardCharsets.UTF_8));
                applyRejectedBeforeEffects(a);
            }
        }finally{if(previous==null)System.clearProperty("cloudstack.storage.identity.path");else System.setProperty("cloudstack.storage.identity.path",previous);org.apache.cloudstack.context.CallContext.unregister();}
    }
    @Test public void lostTargetResponseKeepsOriginalIdentityReferenceUntilExactOperationCompletes() throws Exception {
        ApplyFixture a=applyFixture();JsonObject scope=new JsonObject();scope.addProperty("targetInstanceId",7);scope.addProperty("targetInstanceUuid",INSTANCE);scope.addProperty("operationUuid",OP);scope.addProperty("revision",4);JsonObject usages=new JsonObject();usages.add(OP,scope);JsonObject metadata=new JsonObject();metadata.add("adIdentitySourceUsages",usages);
        org.mockito.Mockito.when(a.sourceOperation.getState()).thenReturn("RECOVERY_REQUIRED");Assert.assertTrue(a.configuration.semanticSourceInUse(metadata));org.mockito.Mockito.when(a.operations.listByInstance(7L)).thenReturn(java.util.List.of());Assert.assertTrue(a.configuration.semanticSourceInUse(metadata));
        org.mockito.Mockito.when(a.operations.listByInstance(7L)).thenReturn(java.util.List.of(a.sourceOperation));org.mockito.Mockito.when(a.sourceOperation.getState()).thenReturn("COMPLETE");Assert.assertFalse(a.configuration.semanticSourceInUse(metadata));
        scope.addProperty("revision","4");Assert.assertThrows(RuntimeException.class,()->a.configuration.semanticSourceInUse(metadata));
    }
    @Test public void lastKnownGoodAdapterCarriesOperatorMaintenanceApprovalWithoutInventingIt() throws Exception {
        ApplyFixture a=applyFixture();a.imported.setKind("RESTORE_POINT");a.imported.setState("ACTIVE_LKG");org.mockito.Mockito.when(a.artifacts.listByInstance(7L)).thenReturn(java.util.List.of(a.imported));java.lang.reflect.Method adapter=StorageServiceConfiguration.class.getDeclaredMethod("lastKnownGoodRequest",StorageServiceInstanceVO.class,StorageConfigRequest.class);adapter.setAccessible(true);
        StorageConfigRequest falseApproval=(StorageConfigRequest)adapter.invoke(a.configuration,a.owner,a.request);Assert.assertEquals(Boolean.FALSE,falseApproval.getMaintenanceWindow());org.mockito.Mockito.when(a.request.getMaintenanceWindow()).thenReturn(true);StorageConfigRequest approved=(StorageConfigRequest)adapter.invoke(a.configuration,a.owner,a.request);Assert.assertEquals(Boolean.TRUE,approved.getMaintenanceWindow());Assert.assertEquals(a.imported.getId(),approved.getArtifactId().longValue());Assert.assertEquals("fixture",approved.getConfirmation());org.mockito.Mockito.verify(a.artifacts,org.mockito.Mockito.never()).update(org.mockito.Mockito.anyLong(),org.mockito.Mockito.any());
    }
    private JsonObject targetReference(Fixture f) {
        JsonObject target=new JsonObject();
        target.add("targetMaintenanceScope",f.nativeRef.get("sourceMaintenanceScope").deepCopy());
        target.add("targetConfigurationSha256",f.nativeRef.get("sourceConfigurationSha256").deepCopy());
        target.addProperty("operationUuid",OP);
        String id=java.util.UUID.nameUUIDFromBytes(("identity-lkg-target:"+OP).getBytes(StandardCharsets.UTF_8)).toString(),keyId=java.util.UUID.nameUUIDFromBytes(("identity-key-lkg-target:"+OP).getBytes(StandardCharsets.UTF_8)).toString();
        byte[] cipher=f.capsule.toString().getBytes(StandardCharsets.UTF_8),key=f.store.read(f.nativeRef.get("keyId").getAsString(),f.nativeRef.get("keySha256").getAsString());
        f.store.write(id,cipher);
        f.store.write(keyId,key);
        target.addProperty("capsuleId",id);
        target.addProperty("capsuleSha256",StorageConfigArchive.sha256(cipher));
        target.addProperty("keyId",keyId);
        target.addProperty("keySha256",StorageConfigArchive.sha256(key));
        JsonObject receipt=f.nativeRef.getAsJsonObject("sourceServiceIdentityCheckpoint").deepCopy();
        receipt.addProperty("kind","SERVICE_TARGET_IDENTITY_CHECKPOINT");
        receipt.add("targetConfigurationSha256",receipt.remove("sourceConfigurationSha256"));
        target.add("targetServiceIdentityCheckpoint",receipt);
        return target;
    }
    @Test public void lkgTargetRoleUsesIndependentVaultAndRejectsSourceReceiptOrWrappingKeySwap() throws Exception {
        Fixture f=fixture(true);JsonObject target=targetReference(f),retained=StorageAdSemanticSource.retainTarget(f.store,"77777777-7777-4777-8777-777777777777",INSTANCE,OP,target,f.key.getPrivate());Assert.assertEquals("LKG_TARGET",retained.get("checkpointRole").getAsString());Assert.assertEquals(f.capsule,StorageAdSemanticSource.authenticate(f.store,retained.getAsJsonObject("descriptor"),retained,"b".repeat(64),"b".repeat(64),f.key.getPrivate()));
        JsonObject wrong=target.deepCopy();wrong.add("targetServiceIdentityCheckpoint",f.nativeRef.get("sourceServiceIdentityCheckpoint").deepCopy());Assert.assertThrows(RuntimeException.class,()->StorageAdSemanticSource.retainTarget(f.store,ARTIFACT,INSTANCE,OP,wrong,f.key.getPrivate()));Assert.assertThrows(RuntimeException.class,()->StorageAdSemanticSource.retainTarget(f.store,ARTIFACT,INSTANCE,OP,target,StorageIdentityCapsule.wrappingKey().getPrivate()));
        JsonObject mixed=retained.deepCopy();mixed.remove("checkpointRole");Assert.assertThrows(RuntimeException.class,()->StorageAdSemanticSource.authenticate(f.store,retained.getAsJsonObject("descriptor"),mixed,"b".repeat(64),"b".repeat(64),f.key.getPrivate()));Assert.assertEquals(f.capsule,StorageAdSemanticSource.authenticate(f.store,descriptor(f),f.retained,"b".repeat(64),"b".repeat(64),f.key.getPrivate()));
    }
    @Test public void actualCloneApplyRealizesFoundationAndProfileBeforeProtectedDomainWriter() throws Exception {
        ApplyFixture a=applyFixture();com.cloud.user.User user=org.mockito.Mockito.mock(com.cloud.user.User.class);org.mockito.Mockito.when(user.getId()).thenReturn(11L);org.apache.cloudstack.context.CallContext.register(user,org.mockito.Mockito.mock(com.cloud.user.Account.class));String old=System.getProperty("cloudstack.storage.identity.path");System.setProperty("cloudstack.storage.identity.path",a.identity.root.toString());
        a.plan.addProperty("targetMode","CREATE_NEW");
        JsonObject blueprint=new JsonObject();blueprint.addProperty("name","fixture");blueprint.addProperty("zoneid","99999999-9999-4999-8999-999999999999");blueprint.addProperty("templateid","66666666-6666-4666-8666-666666666666");a.plan.add("createNew",blueprint);
        StorageServiceManagerImpl templateVerifier=org.mockito.Mockito.spy(new StorageServiceManagerImpl());
        org.mockito.Mockito.doNothing().when(templateVerifier).requireConfigurationAdministrator();
        org.mockito.Mockito.doNothing().when(templateVerifier).preflightConfigurationNewService(org.mockito.Mockito.any());
        com.cloud.storage.dao.VMTemplateDao templates=org.mockito.Mockito.mock(com.cloud.storage.dao.VMTemplateDao.class);
        com.cloud.storage.VMTemplateVO selected=org.mockito.Mockito.mock(com.cloud.storage.VMTemplateVO.class);
        org.mockito.Mockito.when(selected.getId()).thenReturn(66L);org.mockito.Mockito.when(selected.getUuid()).thenReturn("66666666-6666-4666-8666-666666666666");org.mockito.Mockito.when(selected.getTemplateType()).thenReturn(com.cloud.storage.Storage.TemplateType.SYSTEM);
        org.mockito.Mockito.when(selected.getState()).thenReturn(com.cloud.template.VirtualMachineTemplate.State.Active);org.mockito.Mockito.when(selected.isDynamicallyScalable()).thenReturn(true);org.mockito.Mockito.when(selected.getFormat()).thenReturn(com.cloud.storage.Storage.ImageFormat.QCOW2);
        org.mockito.Mockito.when(selected.getHypervisorType()).thenReturn(com.cloud.hypervisor.Hypervisor.HypervisorType.KVM);org.mockito.Mockito.when(selected.getArch()).thenReturn(com.cloud.cpu.CPU.CPUArch.amd64);org.mockito.Mockito.when(selected.getChecksum()).thenReturn("{SHA-256}"+"c".repeat(64));
        org.mockito.Mockito.when(selected.getDetails()).thenReturn(java.util.Map.of("storage.service.local.identity.seed.absent","true"));org.mockito.Mockito.when(templates.findByUuid("66666666-6666-4666-8666-666666666666")).thenReturn(selected);
        org.springframework.test.util.ReflectionTestUtils.setField(templateVerifier,"rootUpgradeTemplateDao",templates);
        com.cloud.dc.dao.DataCenterDao zones=org.mockito.Mockito.mock(com.cloud.dc.dao.DataCenterDao.class);com.cloud.dc.DataCenterVO zone=org.mockito.Mockito.mock(com.cloud.dc.DataCenterVO.class);org.mockito.Mockito.when(zone.getId()).thenReturn(1L);org.mockito.Mockito.when(zones.findByUuid("99999999-9999-4999-8999-999999999999")).thenReturn(zone);org.springframework.test.util.ReflectionTestUtils.setField(templateVerifier,"dataCenterDao",zones);
        JsonObject templatePin=templateVerifier.pinConfigurationCloneTemplate(blueprint);a.plan.add("cloneTemplatePin",templatePin);
        org.mockito.Mockito.doAnswer(call->{templateVerifier.requireFreshStorageIdentityTemplateBlueprint(call.getArgument(0));return null;}).when(a.manager).requireFreshStorageIdentityTemplateBlueprint(org.mockito.Mockito.any());
        org.mockito.Mockito.doAnswer(call->{templateVerifier.requireConfigurationCloneTemplatePin(call.getArgument(0),call.getArgument(1),call.getArgument(2));return null;}).when(a.manager).requireConfigurationCloneTemplatePin(org.mockito.Mockito.any(),org.mockito.Mockito.any(),org.mockito.Mockito.any());
        a.plan.addProperty("runtimeBundleUuid",KEY);JsonObject pin=new JsonObject();pin.addProperty("sourceCommit","a".repeat(40));a.plan.add("cloneRuntimePin",pin);org.mockito.Mockito.when(a.manager.configurationCloneRuntimePin(KEY)).thenReturn(pin);
        JsonObject allocation=new JsonObject();
        allocation.addProperty("schemaVersion",1);
        allocation.add("scope",new JsonObject());
        allocation.add("allocations",new com.google.gson.JsonArray());
        allocation.add("volumeMappings",new JsonObject());
        allocation.addProperty("planSha256",StorageConfigurationVolumePlan.sha256(StorageConfigurationVolumePlan.canonical(allocation)));
        a.plan.add("volumeAllocationPlan",allocation);
        a.importMetadata.getAsJsonObject("planToken").addProperty("planSha256",StorageConfigArchive.sha256(a.plan.toString().getBytes(StandardCharsets.UTF_8)));
        a.imported.setMetadataJson(a.importMetadata.toString());
        org.mockito.Mockito.when(a.request.getMaintenanceWindow()).thenReturn(true);
        org.mockito.Mockito.when(a.manager.captureConfigurationSnapshot(7L)).thenReturn("{}");
        StorageServiceInstanceVO target=org.mockito.Mockito.mock(StorageServiceInstanceVO.class);
        org.mockito.Mockito.when(target.getId()).thenReturn(13L);
        org.mockito.Mockito.when(target.getUuid()).thenReturn("77777777-7777-4777-8777-777777777777");
        org.mockito.Mockito.when(target.getVmId()).thenReturn(55L);org.mockito.Mockito.when(target.getAccountId()).thenReturn(2L);org.mockito.Mockito.when(target.getDataCenterId()).thenReturn(1L);
        com.cloud.vm.dao.UserVmDao vms=org.mockito.Mockito.mock(com.cloud.vm.dao.UserVmDao.class);com.cloud.vm.UserVmVO vm=org.mockito.Mockito.mock(com.cloud.vm.UserVmVO.class);org.mockito.Mockito.when(vm.getId()).thenReturn(55L);org.mockito.Mockito.when(vm.getUuid()).thenReturn("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");org.mockito.Mockito.when(vm.getTemplateId()).thenReturn(66L);org.mockito.Mockito.when(vm.getAccountId()).thenReturn(2L);org.mockito.Mockito.when(vm.getDataCenterId()).thenReturn(1L);org.mockito.Mockito.when(vms.findById(55L)).thenReturn(vm);org.springframework.test.util.ReflectionTestUtils.setField(templateVerifier,"rootUpgradeVmDao",vms);
        org.apache.cloudstack.storage.sharedfs.dao.SharedFSDao shared=org.mockito.Mockito.mock(org.apache.cloudstack.storage.sharedfs.dao.SharedFSDao.class);org.apache.cloudstack.storage.sharedfs.SharedFSVO fs=org.mockito.Mockito.mock(org.apache.cloudstack.storage.sharedfs.SharedFSVO.class);org.mockito.Mockito.when(fs.getVmId()).thenReturn(55L);org.mockito.Mockito.when(fs.getAccountId()).thenReturn(2L);org.mockito.Mockito.when(fs.getDataCenterId()).thenReturn(1L);org.mockito.Mockito.when(shared.findByVm(55L)).thenReturn(fs);org.springframework.test.util.ReflectionTestUtils.setField(templateVerifier,"sharedFSDao",shared);
        com.cloud.storage.dao.VolumeDao volumes=org.mockito.Mockito.mock(com.cloud.storage.dao.VolumeDao.class);com.cloud.storage.VolumeVO root=org.mockito.Mockito.mock(com.cloud.storage.VolumeVO.class);org.mockito.Mockito.when(root.getUuid()).thenReturn("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");org.mockito.Mockito.when(root.getInstanceId()).thenReturn(55L);org.mockito.Mockito.when(root.getTemplateId()).thenReturn(66L);org.mockito.Mockito.when(root.getState()).thenReturn(com.cloud.storage.Volume.State.Ready);org.mockito.Mockito.when(root.getAccountId()).thenReturn(2L);org.mockito.Mockito.when(root.getDataCenterId()).thenReturn(1L);org.mockito.Mockito.when(volumes.findByInstanceAndType(55L,com.cloud.storage.Volume.Type.ROOT)).thenReturn(java.util.List.of(root));org.springframework.test.util.ReflectionTestUtils.setField(templateVerifier,"volumeDao",volumes);
        org.mockito.Mockito.when(a.manager.configurationCloneCreatedTemplateBinding(org.mockito.Mockito.eq(target),org.mockito.Mockito.eq(templatePin))).thenAnswer(call->templateVerifier.configurationCloneCreatedTemplateBinding(target,templatePin));
        org.mockito.Mockito.when(a.manager.createConfigurationNewService(org.mockito.Mockito.any(), org.mockito.Mockito.any(), org.mockito.Mockito.any())).thenAnswer(call -> {
            StorageConfigArtifactVO artifact = call.getArgument(2);
            com.google.gson.JsonObject metadata = com.google.gson.JsonParser.parseString(artifact.getMetadataJson()).getAsJsonObject();
            metadata.add("cloneAllocation", new com.google.gson.JsonObject());artifact.setMetadataJson(metadata.toString());return target;
        });
        org.mockito.Mockito.when(a.manager.bindConfigurationVolumeExecution(org.mockito.Mockito.eq(target),org.mockito.Mockito.eq(a.imported),org.mockito.Mockito.any())).thenReturn(allocation);
        org.mockito.Mockito.when(a.artifacts.lockRow(8L,true)).thenReturn(a.imported);
        org.mockito.Mockito.when(a.artifacts.update(org.mockito.Mockito.anyLong(),org.mockito.Mockito.any())).thenReturn(true);
        org.mockito.Mockito.doThrow(new com.cloud.utils.exception.CloudRuntimeException("Stop before domain mutation in the scoped test")).when(a.manager).executeProtectedIdentityConfiguration(org.mockito.Mockito.eq(target),org.mockito.Mockito.any(),org.mockito.Mockito.any(),org.mockito.Mockito.any(),org.mockito.Mockito.any());
        try(org.mockito.MockedStatic<com.cloud.utils.db.Transaction> transactions=org.mockito.Mockito.mockStatic(com.cloud.utils.db.Transaction.class)){
            transactions.when(()->com.cloud.utils.db.Transaction.execute(org.mockito.ArgumentMatchers.<com.cloud.utils.db.TransactionCallback<Object>>any())).thenAnswer(call->((com.cloud.utils.db.TransactionCallback<?>)call.getArgument(0)).doInTransaction(null));
            java.lang.reflect.Method apply=StorageServiceConfiguration.class.getDeclaredMethod("applyLocked",StorageServiceInstanceVO.class,StorageConfigRequest.class);apply.setAccessible(true);Assert.assertThrows(java.lang.reflect.InvocationTargetException.class,()->apply.invoke(a.configuration,a.owner,a.request));
            org.mockito.InOrder order=org.mockito.Mockito.inOrder(a.manager);order.verify(a.manager).createConfigurationNewService(org.mockito.Mockito.any(), org.mockito.Mockito.any(), org.mockito.Mockito.any());order.verify(a.manager).upgradeConfigurationNewServiceRuntime(target,KEY);order.verify(a.manager).bindConfigurationVolumeExecution(org.mockito.Mockito.eq(target),org.mockito.Mockito.eq(a.imported),org.mockito.Mockito.any());order.verify(a.manager).prepareConfigurationCloneFoundation(org.mockito.Mockito.eq(target),org.mockito.Mockito.eq(a.imported),org.mockito.Mockito.any());order.verify(a.manager).authorizeConfigurationCloneProfile(org.mockito.Mockito.eq(target),org.mockito.Mockito.eq(a.imported),org.mockito.Mockito.any());order.verify(a.manager).executeProtectedIdentityConfiguration(org.mockito.Mockito.eq(target),org.mockito.Mockito.any(),org.mockito.Mockito.any(),org.mockito.Mockito.any(),org.mockito.Mockito.any());
            Assert.assertEquals("EXPLICIT_SYSTEM",a.plan.getAsJsonObject("cloneTemplatePin").get("selectionMode").getAsString());
            Assert.assertEquals("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",com.google.gson.JsonParser.parseString(a.imported.getMetadataJson()).getAsJsonObject().getAsJsonObject("createdTargetTemplateBinding").get("rootVolumeUuid").getAsString());
        }finally{if(old==null)System.clearProperty("cloudstack.storage.identity.path");else System.setProperty("cloudstack.storage.identity.path",old);org.apache.cloudstack.context.CallContext.unregister();}
    }
    @Test public void rootTargetVaultRoleRemainsRootTypedAndRejectsServiceOrSourceRoleSwap() throws Exception {
        Fixture f=fixture(true);JsonObject root=f.nativeRef.getAsJsonObject("sourceMaintenanceScope").deepCopy();root.remove("maintenanceUuid");root.addProperty("templateUpgradeUuid","88888888-8888-4888-8888-888888888888");
        JsonObject targetCapsule=f.capsule.deepCopy(),payload=new JsonObject();payload.addProperty("schemaVersion",1);payload.add("files",new JsonObject());payload.add("accounts",new JsonObject());payload.addProperty("sourceConfigurationSha256","a".repeat(64));payload.add("adIdentity",originalIdentity());
        byte[] aes=new byte[32],nonce=new byte[12];java.security.SecureRandom random=new java.security.SecureRandom();random.nextBytes(aes);random.nextBytes(nonce);javax.crypto.Cipher encrypted=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");encrypted.init(javax.crypto.Cipher.ENCRYPT_MODE,new javax.crypto.spec.SecretKeySpec(aes,"AES"),new javax.crypto.spec.GCMParameterSpec(128,nonce));encrypted.updateAAD((INSTANCE+":"+OP).getBytes(StandardCharsets.UTF_8));byte[] targetCipher=encrypted.doFinal(payload.toString().getBytes(StandardCharsets.UTF_8));javax.crypto.Cipher wrapped=javax.crypto.Cipher.getInstance("RSA/ECB/OAEPPadding");wrapped.init(javax.crypto.Cipher.ENCRYPT_MODE,f.key.getPublic(),new javax.crypto.spec.OAEPParameterSpec("SHA-256","MGF1",java.security.spec.MGF1ParameterSpec.SHA256,javax.crypto.spec.PSource.PSpecified.DEFAULT));
        targetCapsule.addProperty("wrappedKey",Base64.getEncoder().encodeToString(wrapped.doFinal(aes)));targetCapsule.addProperty("nonce",Base64.getEncoder().encodeToString(nonce));targetCapsule.addProperty("ciphertext",Base64.getEncoder().encodeToString(targetCipher));targetCapsule.addProperty("sha256",StorageConfigArchive.sha256(targetCipher));java.util.Arrays.fill(aes,(byte)0);Assert.assertNotEquals(f.capsule.get("sha256"),targetCapsule.get("sha256"));
        JsonObject target=new JsonObject();target.add("targetMaintenanceScope",root);target.addProperty("targetConfigurationSha256","a".repeat(64));target.addProperty("operationUuid",OP);String id=java.util.UUID.nameUUIDFromBytes(("identity-root-lkg-target:"+OP).getBytes(StandardCharsets.UTF_8)).toString(),keyId=java.util.UUID.nameUUIDFromBytes(("identity-key-root-lkg-target:"+OP).getBytes(StandardCharsets.UTF_8)).toString();byte[] bytes=targetCapsule.toString().getBytes(StandardCharsets.UTF_8),key=f.store.read(f.nativeRef.get("keyId").getAsString(),f.nativeRef.get("keySha256").getAsString());f.store.write(id,bytes);f.store.write(keyId,key);target.addProperty("capsuleId",id);target.addProperty("capsuleSha256",StorageConfigArchive.sha256(bytes));target.addProperty("keyId",keyId);target.addProperty("keySha256",StorageConfigArchive.sha256(key));
        JsonObject receipt=new JsonObject();receipt.addProperty("kind","ROOT_TARGET_IDENTITY_CHECKPOINT");receipt.add("scope",root.deepCopy());receipt.addProperty("capsuleSha256",targetCapsule.get("sha256").getAsString());receipt.addProperty("targetConfigurationSha256","a".repeat(64));receipt.addProperty("checkpointRecordSha256","e".repeat(64));target.add("targetRootIdentityCheckpoint",receipt);String owner="77777777-7777-4777-8777-777777777777";
        JsonObject retained=StorageAdSemanticSource.retainRootTarget(f.store,owner,INSTANCE,OP,target,f.key.getPrivate());Assert.assertEquals("ROOT_LKG_TARGET",retained.get("checkpointRole").getAsString());Assert.assertFalse(retained.getAsJsonObject("sourceMaintenanceScope").has("maintenanceUuid"));Assert.assertEquals(targetCapsule,StorageAdSemanticSource.authenticate(f.store,retained.getAsJsonObject("descriptor"),retained,"b".repeat(64),"b".repeat(64),f.key.getPrivate()));Assert.assertEquals(retained,StorageAdSemanticSource.retainRootTarget(f.store,owner,INSTANCE,OP,target,f.key.getPrivate()));
        for(String fault:Set.of("serviceKind","sourceKind","foreignTemplate","foreignCipherId","wrongKey")){
            JsonObject bad=target.deepCopy();if(fault.equals("serviceKind"))bad.getAsJsonObject("targetRootIdentityCheckpoint").addProperty("kind","SERVICE_TARGET_IDENTITY_CHECKPOINT");if(fault.equals("sourceKind"))bad.getAsJsonObject("targetRootIdentityCheckpoint").addProperty("kind","SERVICE_SOURCE_IDENTITY_CHECKPOINT");if(fault.equals("foreignTemplate"))bad.getAsJsonObject("targetMaintenanceScope").addProperty("templateUpgradeUuid","99999999-9999-4999-8999-999999999999");if(fault.equals("foreignCipherId"))bad.addProperty("capsuleId",OP);Assert.assertThrows(fault,RuntimeException.class,()->StorageAdSemanticSource.retainRootTarget(f.store,owner,INSTANCE,OP,bad,fault.equals("wrongKey")?StorageIdentityCapsule.wrappingKey().getPrivate():f.key.getPrivate()));
        }
        JsonObject mixed=retained.deepCopy();mixed.addProperty("checkpointRole","LKG_TARGET");Assert.assertThrows(RuntimeException.class,()->StorageAdSemanticSource.authenticate(f.store,retained.getAsJsonObject("descriptor"),mixed,"b".repeat(64),"b".repeat(64),f.key.getPrivate()));
        assertNativeRootTargetSemanticSource(retained.getAsJsonObject("descriptor"),targetCapsule,f.key.getPrivate());
    }
    private void assertNativeRootTargetSemanticSource(JsonObject descriptor,JsonObject capsule,java.security.PrivateKey key) throws Exception {
        Path root=Path.of(System.getProperty("user.dir")).toAbsolutePath();while(!Files.exists(root.resolve("systemvm/debian/usr/local/bin/ablestack-storagectl")))root=root.getParent();Path source=Path.of(System.getProperty("cloudstack.storage.ad.proof.cli",root.resolve("systemvm/debian/usr/local/bin/ablestack-storagectl").toString()));Path cli=Files.createTempFile("root-target-source-",".sh");Files.write(cli,Files.readAllBytes(source));Files.setPosixFilePermissions(cli,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        JsonObject request=new JsonObject();request.addProperty("instanceUuid","77777777-7777-4777-8777-777777777777");request.addProperty("operationUuid","88888888-8888-4888-8888-888888888888");request.addProperty("revision",2);request.add("originalSourceAuthority",descriptor.deepCopy());request.add("originalSourceCapsule",capsule.deepCopy());request.addProperty("originalSourceCredentialPrivateKey",StorageIdentityCapsule.pem("PRIVATE KEY",key.getEncoded()));
        try {
            Process process=new ProcessBuilder(cli.toString(),"identity","capsule","semantic-original","/dev/stdin").start();try(java.io.OutputStream stdin=process.getOutputStream()){stdin.write(request.toString().getBytes(StandardCharsets.UTF_8));}String output=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8),error=new String(process.getErrorStream().readAllBytes(),StandardCharsets.UTF_8);Assert.assertEquals(0,process.waitFor());Assert.assertFalse(output.contains("PRIVATE KEY"));Assert.assertFalse(error.contains("PRIVATE KEY"));JsonObject response=com.google.gson.JsonParser.parseString(output).getAsJsonObject();Assert.assertEquals(originalIdentity(),response.get("originalIdentity"));Assert.assertEquals(descriptor,response.get("originalSourceAuthority"));
        }finally {Files.deleteIfExists(cli);}
    }
}
