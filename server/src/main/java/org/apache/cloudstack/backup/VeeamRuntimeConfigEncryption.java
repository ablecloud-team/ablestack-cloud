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
package org.apache.cloudstack.backup;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import org.apache.cloudstack.api.response.VeeamRuntimeConfigResponse;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.Gson;

/** No Veeam password or reusable decryption key is written to the Host filesystem. */
final class VeeamRuntimeConfigEncryption {
    static final String CONTEXT = "ablestack-veeam-runtime-config-v1";

    private VeeamRuntimeConfigEncryption() { }

    static VeeamRuntimeConfigResponse encrypt(Map<String, String> settings, String encodedPublicKey) {
        try {
            if (encodedPublicKey == null || encodedPublicKey.length() > 8192) { throw new IllegalArgumentException(); }
            RSAPublicKey publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encodedPublicKey)));
            if (publicKey.getModulus().bitLength() < 2048 || publicKey.getModulus().bitLength() > 4096) { throw new IllegalArgumentException(); }
            byte[] key = new byte[32];
            byte[] nonce = new byte[12];
            SecureRandom random = new SecureRandom();
            random.nextBytes(key);
            random.nextBytes(nonce);
            Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
            aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            aes.updateAAD(CONTEXT.getBytes(StandardCharsets.US_ASCII));
            byte[] ciphertext = aes.doFinal(new Gson().toJson(settings).getBytes(StandardCharsets.UTF_8));
            Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
            rsa.init(Cipher.ENCRYPT_MODE, publicKey,
                    new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
            VeeamRuntimeConfigResponse response = new VeeamRuntimeConfigResponse();
            response.wrappedkey = Base64.getEncoder().encodeToString(rsa.doFinal(key));
            response.nonce = Base64.getEncoder().encodeToString(nonce);
            response.ciphertext = Base64.getEncoder().encodeToString(ciphertext);
            response.setObjectName("veeamruntimeconfig");
            return response;
        } catch (Exception e) {
            throw new CloudRuntimeException("Unable to protect Veeam runtime configuration; provide a valid 2048-4096-bit RSA public key");
        }
    }
}
