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
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import com.google.gson.JsonObject;
import com.cloud.utils.exception.CloudRuntimeException;

/** Only encrypted envelope bytes are durable; the target declaration and ACL inputs are authenticated together. */
public final class StorageRenderedCredentialCapsule {
    private StorageRenderedCredentialCapsule() { }
    public static JsonObject encrypt(JsonObject scope,JsonObject canonical,JsonObject credentials,PublicKey publicKey) {
        JsonObject payload=new JsonObject();payload.addProperty("schemaVersion",1);payload.addProperty("kind","RENDERED_TARGET_CREDENTIALS");payload.add("scope",scope.deepCopy());payload.add("configurationDesiredState",canonical.deepCopy());payload.add("credentials",credentials.deepCopy());
        byte[] plain=payload.toString().getBytes(StandardCharsets.UTF_8);if(plain.length>8*1024*1024)throw new CloudRuntimeException("Rendered target credential capsule exceeds its protected size limit");
        byte[] key=new byte[32],nonce=new byte[12];SecureRandom random=new SecureRandom();random.nextBytes(key);random.nextBytes(nonce);
        String aad=scope.get("instanceUuid").getAsString()+":"+scope.get("operationUuid").getAsString();
        try {
            Cipher aes=Cipher.getInstance("AES/GCM/NoPadding");aes.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));aes.updateAAD(aad.getBytes(StandardCharsets.UTF_8));byte[] cipher=aes.doFinal(plain);
            Cipher rsa=Cipher.getInstance("RSA/ECB/OAEPPadding");rsa.init(Cipher.ENCRYPT_MODE,publicKey,new OAEPParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA256,PSource.PSpecified.DEFAULT));
            JsonObject capsule=new JsonObject();capsule.addProperty("schemaVersion",1);capsule.addProperty("scope",aad);capsule.addProperty("wrappedKey",Base64.getEncoder().encodeToString(rsa.doFinal(key)));capsule.addProperty("nonce",Base64.getEncoder().encodeToString(nonce));capsule.addProperty("ciphertext",Base64.getEncoder().encodeToString(cipher));capsule.addProperty("sha256",StorageConfigArchive.sha256(cipher));return capsule;
        } catch(GeneralSecurityException invalid){throw new CloudRuntimeException("Rendered target credential envelope cannot be encrypted",invalid);}
        finally {java.util.Arrays.fill(key,(byte)0);java.util.Arrays.fill(plain,(byte)0);}
    }
    public static JsonObject transport(JsonObject reference,byte[] encryptedBytes) {
        if(!"TARGET_CREDENTIAL_CAPSULE".equals(reference.get("kind").getAsString())||!StorageConfigArchive.sha256(encryptedBytes).equals(reference.get("artifactSha256").getAsString()))throw new CloudRuntimeException("Rendered target credential artifact differs from its durable ciphertext pin");
        JsonObject result=new JsonObject();result.add("artifactUuid",reference.get("artifactUuid").deepCopy());result.add("artifactSha256",reference.get("artifactSha256").deepCopy());result.addProperty("encodedCapsule",Base64.getEncoder().encodeToString(encryptedBytes));return result;
    }
}
