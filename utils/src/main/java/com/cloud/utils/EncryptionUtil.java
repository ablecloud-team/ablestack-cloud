/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.cloud.utils;

import com.cloud.utils.crypt.CloudStackEncryptor;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.commons.codec.binary.Base64;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.UnsupportedEncodingException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

public class EncryptionUtil {
    protected static Logger LOGGER = LogManager.getLogger(EncryptionUtil.class);
    private static CloudStackEncryptor encryptor;

    private static void initialize(String key) {
        encryptor = new CloudStackEncryptor(key, null, EncryptionUtil.class);
    }

    public static String encodeData(String data, String key) {
        if (encryptor == null) {
            initialize(key);
        }
        return encryptor.encrypt(data);
    }

    public static String decodeData(String encodedData, String key) {
        if (encryptor == null) {
            initialize(key);
        }
        return encryptor.decrypt(encodedData);
    }

    public static String generateSignature(String data, String key) {
        try {
            final Mac mac = Mac.getInstance("HmacSHA256");
            final SecretKeySpec keySpec = new SecretKeySpec(key.getBytes("UTF-8"), "HmacSHA256");
            mac.init(keySpec);
            mac.update(data.getBytes("UTF-8"));
            final byte[] encryptedBytes = mac.doFinal();
            return Base64.encodeBase64String(encryptedBytes);
        } catch (NoSuchAlgorithmException | InvalidKeyException | UnsupportedEncodingException e) {
            LOGGER.error("exception occurred which encoding the data." + e.getMessage());
            throw new CloudRuntimeException("unable to generate signature", e);
        }
    }
}
