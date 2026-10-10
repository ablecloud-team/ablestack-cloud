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
package com.cloud.api;

import com.cloud.utils.crypt.DBEncryptionUtil;
import com.cloud.utils.crypt.EncryptionSecretKeyChecker;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

final class ApiSensitiveParamUtils {

    private static final String MASKED_VALUE = "******";
    private static final String ENCRYPTED_PREFIX = "CSENC(";
    private static final String ENCRYPTED_SUFFIX = ")";
    private static final Set<String> SENSITIVE_FIELDS = new HashSet<>(Arrays.asList(
            "password", "secretkey", "apikey", "token",
            "sessionkey", "accesskey", "signature",
            "authorization", "credential", "secret", "configurationpayload"
    ));

    private ApiSensitiveParamUtils() {
    }

    static boolean isSensitiveParameter(String name) {
        if (StringUtils.isBlank(name)) {
            return false;
        }
        String lowerName = name.toLowerCase();
        return SENSITIVE_FIELDS.stream().anyMatch(lowerName::contains);
    }

    static String redactValue(String name, String value) {
        return isSensitiveParameter(name) ? MASKED_VALUE : value;
    }

    static Map<String, String> redactValues(Map<String, String> params) {
        Map<String, String> redacted = new HashMap<>();
        if (params == null) {
            return redacted;
        }
        for (Map.Entry<String, String> entry : params.entrySet()) {
            redacted.put(entry.getKey(), redactValue(entry.getKey(), entry.getValue()));
        }
        return redacted;
    }

    static Map<String, String> encryptSensitiveValues(Map<String, String> params) {
        Map<String, String> encrypted = new HashMap<>();
        if (params == null) {
            return encrypted;
        }
        for (Map.Entry<String, String> entry : params.entrySet()) {
            String value = entry.getValue();
            if (isSensitiveParameter(entry.getKey()) && StringUtils.isNotBlank(value) &&
                    !isEncryptedSensitiveValue(value) && EncryptionSecretKeyChecker.useEncryption()) {
                encrypted.put(entry.getKey(), ENCRYPTED_PREFIX + DBEncryptionUtil.encrypt(value) + ENCRYPTED_SUFFIX);
            } else {
                encrypted.put(entry.getKey(), value);
            }
        }
        return encrypted;
    }

    static void decryptSensitiveValues(Map<String, String> params) {
        if (params == null) {
            return;
        }
        for (Map.Entry<String, String> entry : params.entrySet()) {
            String value = entry.getValue();
            if (isSensitiveParameter(entry.getKey()) && isEncryptedSensitiveValue(value)) {
                String encryptedValue = value.substring(ENCRYPTED_PREFIX.length(), value.length() - ENCRYPTED_SUFFIX.length());
                entry.setValue(DBEncryptionUtil.decrypt(encryptedValue));
            }
        }
    }

    private static boolean isEncryptedSensitiveValue(String value) {
        return StringUtils.startsWith(value, ENCRYPTED_PREFIX) && StringUtils.endsWith(value, ENCRYPTED_SUFFIX);
    }
}
