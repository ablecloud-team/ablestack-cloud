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

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.spec.MGF1ParameterSpec;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Assert;
import org.junit.Test;
import com.cloud.utils.exception.CloudRuntimeException;

public class StorageRenderedCredentialCapsuleTest {
    private JsonObject scope(){return JsonParser.parseString("{\"instanceUuid\":\"instance\",\"operationUuid\":\"operation\",\"revision\":3}").getAsJsonObject();}
    private JsonObject desired(){return JsonParser.parseString("{\"desired-state/smb-share-apply.json\":{\"shares\":[{\"uuid\":\"share\",\"acls\":[{\"uuid\":\"acl\"}]}]}}").getAsJsonObject();}
    private JsonObject credentials(){return JsonParser.parseString("{\"SMB\":{\"acl\":{\"password\":\"synthetic-test-input\"}}}").getAsJsonObject();}
    private JsonObject decrypt(JsonObject capsule,KeyPair key,String aad)throws Exception {
        Cipher rsa=Cipher.getInstance("RSA/ECB/OAEPPadding");rsa.init(Cipher.DECRYPT_MODE,key.getPrivate(),new OAEPParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA256,PSource.PSpecified.DEFAULT));byte[] aesKey=rsa.doFinal(Base64.getDecoder().decode(capsule.get("wrappedKey").getAsString()));Cipher aes=Cipher.getInstance("AES/GCM/NoPadding");aes.init(Cipher.DECRYPT_MODE,new SecretKeySpec(aesKey,"AES"),new GCMParameterSpec(128,Base64.getDecoder().decode(capsule.get("nonce").getAsString())));aes.updateAAD(aad.getBytes(StandardCharsets.UTF_8));return JsonParser.parseString(new String(aes.doFinal(Base64.getDecoder().decode(capsule.get("ciphertext").getAsString())),StandardCharsets.UTF_8)).getAsJsonObject();
    }
    @Test public void durableCiphertextBindsExactTargetDeclarationScopeAndAclInputsWithoutPublicSecrets()throws Exception {
        KeyPair key=StorageIdentityCapsule.wrappingKey();JsonObject capsule=StorageRenderedCredentialCapsule.encrypt(scope(),desired(),credentials(),key.getPublic());Assert.assertFalse(capsule.toString().contains("synthetic-test-input"));Assert.assertFalse(capsule.toString().contains("PRIVATE KEY"));JsonObject payload=decrypt(capsule,key,"instance:operation");Assert.assertEquals(scope(),payload.get("scope"));Assert.assertEquals(desired(),payload.get("configurationDesiredState"));Assert.assertEquals(credentials(),payload.get("credentials"));Assert.assertEquals("RENDERED_TARGET_CREDENTIALS",payload.get("kind").getAsString());
    }
    @Test public void foreignOperationAndForeignPrivateKeyCannotDecryptTheSameArtifact() {
        KeyPair key=StorageIdentityCapsule.wrappingKey();JsonObject capsule=StorageRenderedCredentialCapsule.encrypt(scope(),desired(),credentials(),key.getPublic());Assert.assertThrows(Exception.class,()->decrypt(capsule,key,"instance:foreign"));Assert.assertThrows(Exception.class,()->decrypt(capsule,StorageIdentityCapsule.wrappingKey(),"instance:operation"));
    }
    @Test public void changedEnvelopeBytesCannotPassTheDurableArtifactShaPin() {
        KeyPair key=StorageIdentityCapsule.wrappingKey();JsonObject capsule=StorageRenderedCredentialCapsule.encrypt(scope(),desired(),credentials(),key.getPublic());byte[] bytes=capsule.toString().getBytes(StandardCharsets.UTF_8);JsonObject reference=new JsonObject();reference.addProperty("kind","TARGET_CREDENTIAL_CAPSULE");reference.addProperty("artifactUuid","artifact");reference.addProperty("artifactSha256",StorageConfigArchive.sha256(bytes));JsonObject transport=StorageRenderedCredentialCapsule.transport(reference,bytes);Assert.assertArrayEquals(bytes,Base64.getDecoder().decode(transport.get("encodedCapsule").getAsString()));bytes[bytes.length-2]^=1;Assert.assertThrows(CloudRuntimeException.class,()->StorageRenderedCredentialCapsule.transport(reference,bytes));
    }
    @Test public void repeatedEncryptionProducesIndependentWrappedKeyNonceAndCiphertext() {
        KeyPair key=StorageIdentityCapsule.wrappingKey();JsonObject first=StorageRenderedCredentialCapsule.encrypt(scope(),desired(),credentials(),key.getPublic()),second=StorageRenderedCredentialCapsule.encrypt(scope(),desired(),credentials(),key.getPublic());for(String field:new String[]{"wrappedKey","nonce","ciphertext","sha256"})Assert.assertNotEquals(first.get(field),second.get(field));
    }
}
