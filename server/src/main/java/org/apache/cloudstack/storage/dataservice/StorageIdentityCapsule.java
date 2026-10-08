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
import java.security.KeyPairGenerator;
import java.util.Base64;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonObject;

/** The guest only exports encrypted identity. Private wrapping keys never enter DB, events, or public artifacts. */
public final class StorageIdentityCapsule {
    private StorageIdentityCapsule() { }
    public static KeyPair wrappingKey() {
        try { KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");generator.initialize(2048);return generator.generateKeyPair(); }
        catch (java.security.GeneralSecurityException failure) { throw new CloudRuntimeException("Identity capsule wrapping key is unavailable", failure); }
    }
    public static String pem(String type, byte[] bytes) {
        String encoded = Base64.getMimeEncoder(64, new byte[] {(byte) 10}).encodeToString(bytes);
        return "-----BEGIN " + type + "-----" + (char) 10 + encoded + (char) 10 + "-----END " + type + "-----" + (char) 10;
    }
    public static JsonObject exportRequest(String instanceUuid, String operationUuid, KeyPair key, com.google.gson.JsonArray names) {
        JsonObject request = new JsonObject();request.addProperty("instanceUuid", instanceUuid);request.addProperty("operationUuid", operationUuid);
        request.addProperty("publicKey", pem("PUBLIC KEY", key.getPublic().getEncoded()));request.add("names", names);return request;
    }
    public static byte[] protectedPrivateKey(KeyPair key) {
        String plain = pem("PRIVATE KEY", key.getPrivate().getEncoded());
        String encrypted = com.cloud.utils.crypt.DBEncryptionUtil.encrypt(plain);
        if (plain.equals(encrypted) || !plain.equals(com.cloud.utils.crypt.DBEncryptionUtil.decrypt(encrypted))) {
            throw new CloudRuntimeException("Identity capsule requires protected management encryption");
        }
        return encrypted.getBytes(StandardCharsets.UTF_8);
    }
    public static java.security.PrivateKey unwrapProtectedPrivateKey(byte[] protectedKey) {
        String encrypted=new String(protectedKey,StandardCharsets.UTF_8),plain=com.cloud.utils.crypt.DBEncryptionUtil.decrypt(encrypted);
        if(plain.equals(encrypted)||!plain.startsWith("-----BEGIN PRIVATE KEY-----")||!plain.trim().endsWith("-----END PRIVATE KEY-----"))throw new CloudRuntimeException("Managed identity wrapping key is not protected or is unavailable");
        byte[] encoded=null;
        try {
            encoded=Base64.getDecoder().decode(plain.replace("-----BEGIN PRIVATE KEY-----","").replace("-----END PRIVATE KEY-----","").replaceAll("\\s",""));
            java.security.PrivateKey key=java.security.KeyFactory.getInstance("RSA").generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(encoded));
            if(!(key instanceof java.security.interfaces.RSAPrivateKey)||((java.security.interfaces.RSAPrivateKey)key).getModulus().bitLength()<2048)throw new CloudRuntimeException("Managed identity wrapping key strength is invalid");
            return key;
        } catch(java.security.GeneralSecurityException|IllegalArgumentException invalid){throw new CloudRuntimeException("Managed identity wrapping key cannot be decoded",invalid);}
        finally {if(encoded!=null)java.util.Arrays.fill(encoded,(byte)0);}
    }
    public static JsonObject importRequest(String instanceUuid, String operationUuid, JsonObject capsule, byte[] protectedKey) {
        JsonObject request = new JsonObject();request.addProperty("instanceUuid", instanceUuid);request.addProperty("operationUuid", operationUuid);request.add("capsule", capsule);
        request.addProperty("credentialPrivateKey", com.cloud.utils.crypt.DBEncryptionUtil.decrypt(new String(protectedKey, StandardCharsets.UTF_8)));return request;
    }
}
