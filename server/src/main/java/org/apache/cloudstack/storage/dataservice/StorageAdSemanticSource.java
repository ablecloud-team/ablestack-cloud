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

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.MessageDigest;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.cloud.utils.exception.CloudRuntimeException;

/** A public ZIP descriptor cannot issue its own authority or select management vault paths. */
public final class StorageAdSemanticSource {
    public static final String KIND = "STORAGE_AD_SEMANTIC_SOURCE";
    public static final String ZIP_ENTRY = "identity/ad-source-descriptor.json";
    private static final Set<String> FIELDS = Set.of("schemaVersion","kind","ownerArtifactUuid","sourceInstanceUuid","sourceOperationUuid",
            "sourceConfigurationSha256","ciphertextSha256","issuerMac");
    private StorageAdSemanticSource() { }
    private static String text(JsonObject value,String field) {
        JsonElement item=value.get(field);
        if(item==null||!item.isJsonPrimitive()||!item.getAsJsonPrimitive().isString())throw new CloudRuntimeException("AD source descriptor string is invalid: "+field);
        return item.getAsString();
    }
    public static JsonObject validateDescriptor(JsonObject value) {
        if(value==null||!value.keySet().equals(FIELDS)||!KIND.equals(text(value,"kind")))throw new CloudRuntimeException("AD source descriptor kind or fields are invalid");
        JsonElement schema=value.get("schemaVersion");
        if(!schema.isJsonPrimitive()||!schema.getAsJsonPrimitive().isNumber()||!"1".equals(schema.getAsString()))throw new CloudRuntimeException("AD source descriptor schema is not literal version one");
        for(String field:Set.of("ownerArtifactUuid","sourceInstanceUuid","sourceOperationUuid"))if(!text(value,field).matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))throw new CloudRuntimeException("AD source descriptor UUID is invalid");
        for(String field:Set.of("sourceConfigurationSha256","ciphertextSha256","issuerMac"))if(!text(value,field).matches("[a-f0-9]{64}"))throw new CloudRuntimeException("AD source descriptor digest is invalid");
        return value.deepCopy();
    }
    private static byte[] canonical(JsonObject value) {
        JsonObject ordered=new JsonObject();
        for(String key:new TreeSet<>(value.keySet()))if(!"issuerMac".equals(key))ordered.add(key,value.get(key).deepCopy());
        return ordered.toString().getBytes(StandardCharsets.UTF_8);
    }
    private static String issuerMac(JsonObject value,PrivateKey key) {
        byte[] encoded=key.getEncoded(),derived=null;
        try {
            derived=MessageDigest.getInstance("SHA-256").digest(encoded);
            Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(derived,"HmacSHA256"));
            byte[] result=mac.doFinal(canonical(value));StringBuilder hex=new StringBuilder();
            for(byte item:result)hex.append(Character.forDigit((item>>>4)&15,16)).append(Character.forDigit(item&15,16));
            return hex.toString();
        } catch(java.security.GeneralSecurityException failure){throw new CloudRuntimeException("AD source issuer authentication is unavailable",failure);}
        finally {java.util.Arrays.fill(encoded,(byte)0);if(derived!=null)java.util.Arrays.fill(derived,(byte)0);}
    }
    private static String identity(String artifact,String purpose) {
        return UUID.nameUUIDFromBytes(("ad-semantic-source:"+artifact+":"+purpose).getBytes(StandardCharsets.UTF_8)).toString();
    }
    private static void writeSame(StorageConfigArtifactStore store,String id,byte[] value) {
        if(store.contains(id))store.read(id,StorageConfigArchive.sha256(value));
        else store.write(id,value);
    }
    private static void sourceScope(JsonObject scope,String instance,String operation) {sourceScope(scope,instance,operation,false);}
    private static void sourceScope(JsonObject scope,String instance,String operation,boolean root) {
        if(scope==null||!scope.keySet().equals(Set.of("instanceUuid","operationUuid",root?"templateUpgradeUuid":"maintenanceUuid","revision"))
                ||!instance.equals(text(scope,"instanceUuid"))||!operation.equals(text(scope,"operationUuid"))||!root&&!operation.equals(text(scope,"maintenanceUuid")))throw new CloudRuntimeException("AD source vault requires its exact original SERVICE scope");
        if(root&&!text(scope,"templateUpgradeUuid").matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))throw new CloudRuntimeException("ROOT TARGET vault template transaction is invalid");
        JsonElement revision=scope.get("revision");
        if(!revision.isJsonPrimitive()||!revision.getAsJsonPrimitive().isNumber()||!revision.getAsString().matches("[0-9]+")||revision.getAsLong()<1)throw new CloudRuntimeException("AD source vault revision is not literal");
    }
    private static void ownedReference(JsonObject reference,JsonObject descriptor) {
        Set<String> fields=new java.util.HashSet<>(Set.of("kind","ownerArtifactUuid","sourceInstanceUuid","sourceOperationUuid","cipherId","keyId","cipherDataSha256","keyDataSha256","sourceMaintenanceScope","sourceServiceIdentityCheckpoint","descriptor","keyBlobKind"));
        if(reference.has("checkpointRole")){fields.add("checkpointRole");if(!Set.of("LKG_TARGET","ROOT_LKG_TARGET").contains(text(reference,"checkpointRole")))throw new CloudRuntimeException("AD source private checkpoint role is invalid");}
        if(!reference.keySet().equals(fields)||!KIND.equals(text(reference,"kind"))||!"MANAGEMENT_DB_ENCRYPTED_PKCS8_PRIVATE_KEY".equals(text(reference,"keyBlobKind")))throw new CloudRuntimeException("AD source private vault reference kind or fields changed");
        String owner=text(reference,"ownerArtifactUuid");
        if(!identity(owner,"cipher").equals(text(reference,"cipherId"))||!identity(owner,"key").equals(text(reference,"keyId")))throw new CloudRuntimeException("AD source vault cannot select another owner's entries");
        for(String field:Set.of("cipherDataSha256","keyDataSha256"))if(!text(reference,field).matches("[a-f0-9]{64}"))throw new CloudRuntimeException("AD source vault integrity digest is invalid");
        sourceScope(reference.getAsJsonObject("sourceMaintenanceScope"),text(descriptor,"sourceInstanceUuid"),text(descriptor,"sourceOperationUuid"),reference.has("checkpointRole")&&"ROOT_LKG_TARGET".equals(text(reference,"checkpointRole")));
    }
    public static JsonObject validateManagedReference(JsonObject descriptor,JsonObject reference) {
        validateDescriptor(descriptor);if(reference==null)throw new CloudRuntimeException("AD source has no managed vault reference");
        ownedReference(reference,descriptor);return reference.deepCopy();
    }
    public static JsonObject retain(StorageConfigArtifactStore store,String ownerArtifact,String instance,String operation,JsonObject nativeReference,PrivateKey key) {
        return retainIdentity(store,ownerArtifact,instance,operation,nativeReference,key,false,false);
    }
    public static JsonObject retainTarget(StorageConfigArtifactStore store,String ownerArtifact,String instance,String operation,JsonObject nativeReference,PrivateKey key) {
        if(!nativeReference.keySet().equals(Set.of("targetMaintenanceScope","targetConfigurationSha256","operationUuid","capsuleId","capsuleSha256","keyId","keySha256","targetServiceIdentityCheckpoint")))throw new CloudRuntimeException("LKG TARGET native reference is not closed");
        return retainIdentity(store,ownerArtifact,instance,operation,nativeReference,key,true,false);
    }
    public static JsonObject retainRootTarget(StorageConfigArtifactStore store,String ownerArtifact,String instance,String operation,JsonObject nativeReference,PrivateKey key) {
        if(!nativeReference.keySet().equals(Set.of("targetMaintenanceScope","targetConfigurationSha256","operationUuid","capsuleId","capsuleSha256","keyId","keySha256","targetRootIdentityCheckpoint")))throw new CloudRuntimeException("ROOT LKG TARGET native reference is not closed");
        return retainIdentity(store,ownerArtifact,instance,operation,nativeReference,key,true,true);
    }
    private static JsonObject retainIdentity(StorageConfigArtifactStore store,String ownerArtifact,String instance,String operation,JsonObject nativeReference,PrivateKey key,boolean target,boolean root) {
        JsonObject scope=nativeReference.getAsJsonObject(target?"targetMaintenanceScope":"sourceMaintenanceScope");String checksum=text(nativeReference,target?"targetConfigurationSha256":"sourceConfigurationSha256");
        sourceScope(scope,instance,operation,root);
        String expectedKey=UUID.nameUUIDFromBytes(((root?"identity-key-root-lkg-target:":target?"identity-key-lkg-target:":"identity-key:")+operation).getBytes(StandardCharsets.UTF_8)).toString();
        String capsuleId=target?UUID.nameUUIDFromBytes(((root?"identity-root-lkg-target:":"identity-lkg-target:")+operation).getBytes(StandardCharsets.UTF_8)).toString():operation;
        if(!operation.equals(text(nativeReference,"operationUuid"))||!expectedKey.equals(text(nativeReference,"keyId"))||target&&!capsuleId.equals(text(nativeReference,"capsuleId")))throw new CloudRuntimeException("AD identity cannot borrow another operation's native wrapping key or role");
        byte[] capsuleBytes=store.read(capsuleId,text(nativeReference,"capsuleSha256")),protectedKey=store.read(text(nativeReference,"keyId"),text(nativeReference,"keySha256"));
        java.security.PrivateKey actualKey=StorageIdentityCapsule.unwrapProtectedPrivateKey(protectedKey);byte[] expectedDer=key.getEncoded(),actualDer=actualKey.getEncoded();
        try {if(!MessageDigest.isEqual(expectedDer,actualDer))throw new CloudRuntimeException("AD backup issuer key differs from its exact capsule wrapping key");}
        finally {java.util.Arrays.fill(expectedDer,(byte)0);java.util.Arrays.fill(actualDer,(byte)0);}
        JsonObject capsule=JsonParser.parseString(new String(capsuleBytes,StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject checkpoint=nativeReference.getAsJsonObject(root?"targetRootIdentityCheckpoint":target?"targetServiceIdentityCheckpoint":"sourceServiceIdentityCheckpoint");
        if(root)StorageAdIdentityProof.rootTargetCipherCheckpoint(checkpoint,capsule,scope,checksum);else if(target)StorageAdIdentityProof.targetCipherCheckpoint(checkpoint,capsule,scope,checksum);else StorageAdIdentityProof.serviceCipherCheckpoint(checkpoint,capsule,scope,checksum);
        JsonObject descriptor=new JsonObject();descriptor.addProperty("schemaVersion",1);descriptor.addProperty("kind",KIND);descriptor.addProperty("ownerArtifactUuid",ownerArtifact);
        descriptor.addProperty("sourceInstanceUuid",instance);descriptor.addProperty("sourceOperationUuid",operation);descriptor.addProperty("sourceConfigurationSha256",checksum);descriptor.addProperty("ciphertextSha256",text(capsule,"sha256"));
        descriptor.addProperty("issuerMac",issuerMac(descriptor,key));validateDescriptor(descriptor);
        String cipherId=identity(ownerArtifact,"cipher"),keyId=identity(ownerArtifact,"key");
        writeSame(store,cipherId,capsuleBytes);writeSame(store,keyId,protectedKey);
        JsonObject reference=new JsonObject();reference.addProperty("kind",KIND);reference.addProperty("ownerArtifactUuid",ownerArtifact);reference.addProperty("sourceInstanceUuid",instance);reference.addProperty("sourceOperationUuid",operation);
        reference.addProperty("cipherId",cipherId);reference.addProperty("keyId",keyId);reference.addProperty("cipherDataSha256",StorageConfigArchive.sha256(capsuleBytes));reference.addProperty("keyDataSha256",StorageConfigArchive.sha256(protectedKey));
        reference.add("sourceMaintenanceScope",scope.deepCopy());reference.add("sourceServiceIdentityCheckpoint",checkpoint.deepCopy());if(target)reference.addProperty("checkpointRole",root?"ROOT_LKG_TARGET":"LKG_TARGET");reference.addProperty("keyBlobKind","MANAGEMENT_DB_ENCRYPTED_PKCS8_PRIVATE_KEY");reference.add("descriptor",descriptor);ownedReference(reference,descriptor);return reference;
    }
    public static JsonObject authenticate(StorageConfigArtifactStore store,JsonObject descriptor,JsonObject trustedReference,String archiveSha,String originalArchiveSha,PrivateKey key) {
        validateDescriptor(descriptor);
        if(trustedReference==null)throw new CloudRuntimeException("AD source has no managed issuer reference");
        ownedReference(trustedReference,descriptor);
        if(archiveSha==null||!archiveSha.matches("[a-f0-9]{64}")||!archiveSha.equals(originalArchiveSha)||trustedReference==null
                ||!KIND.equals(text(trustedReference,"kind"))||!descriptor.equals(trustedReference.get("descriptor")))throw new CloudRuntimeException("Uploaded AD descriptor is not its original managed backup archive");
        for(String field:Set.of("ownerArtifactUuid","sourceInstanceUuid","sourceOperationUuid"))if(!text(descriptor,field).equals(text(trustedReference,field)))throw new CloudRuntimeException("AD backup authority owner binding changed");
        if(!MessageDigest.isEqual(text(descriptor,"issuerMac").getBytes(StandardCharsets.US_ASCII),issuerMac(descriptor,key).getBytes(StandardCharsets.US_ASCII)))throw new CloudRuntimeException("AD source issuer authentication changed");
        byte[] bytes=store.read(text(trustedReference,"cipherId"),text(trustedReference,"cipherDataSha256"));
        store.read(text(trustedReference,"keyId"),text(trustedReference,"keyDataSha256"));
        JsonObject capsule=JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();
        if(trustedReference.has("checkpointRole")&&"ROOT_LKG_TARGET".equals(text(trustedReference,"checkpointRole")))StorageAdIdentityProof.rootTargetCipherCheckpoint(trustedReference.getAsJsonObject("sourceServiceIdentityCheckpoint"),capsule,trustedReference.getAsJsonObject("sourceMaintenanceScope"),text(descriptor,"sourceConfigurationSha256"));
        else if(trustedReference.has("checkpointRole"))StorageAdIdentityProof.targetCipherCheckpoint(trustedReference.getAsJsonObject("sourceServiceIdentityCheckpoint"),capsule,trustedReference.getAsJsonObject("sourceMaintenanceScope"),text(descriptor,"sourceConfigurationSha256"));
        else StorageAdIdentityProof.serviceCipherCheckpoint(trustedReference.getAsJsonObject("sourceServiceIdentityCheckpoint"),capsule,trustedReference.getAsJsonObject("sourceMaintenanceScope"),text(descriptor,"sourceConfigurationSha256"));
        if(!text(descriptor,"ciphertextSha256").equals(text(capsule,"sha256"))||!(text(descriptor,"sourceInstanceUuid")+":"+text(descriptor,"sourceOperationUuid")).equals(text(capsule,"scope")))throw new CloudRuntimeException("AD source cipher belongs to another original operation");
        return capsule;
    }
    public static void remove(StorageConfigArtifactStore store,JsonObject trustedReference) {
        if(trustedReference==null)return;
        String owner=text(trustedReference,"ownerArtifactUuid");
        if(!identity(owner,"cipher").equals(text(trustedReference,"cipherId"))||!identity(owner,"key").equals(text(trustedReference,"keyId")))throw new CloudRuntimeException("AD source cleanup cannot select foreign vault entries");
        store.remove(text(trustedReference,"cipherId"));store.remove(text(trustedReference,"keyId"));
    }
}
