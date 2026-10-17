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

import org.junit.Assert;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

public class ApiSensitiveParamUtilsTest {

    @Test
    public void redactValuesMasksSensitiveParametersOnly() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("host", "https://10.10.132.100:9440");
        params.put("password", "secret");
        params.put("apiSecretKey", "api-secret");
        params.put("sourcecredential", "credential-json");

        Map<String, String> redacted = ApiSensitiveParamUtils.redactValues(params);

        Assert.assertEquals("https://10.10.132.100:9440", redacted.get("host"));
        Assert.assertEquals("******", redacted.get("password"));
        Assert.assertEquals("******", redacted.get("apiSecretKey"));
        Assert.assertEquals("******", redacted.get("sourcecredential"));
    }

    @Test
    public void encryptSensitiveValuesDoesNotPersistMaskOrChangeValuesWhenEncryptionDisabled() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("password", "secret");
        params.put("host", "https://10.10.132.100:9440");

        Map<String, String> encrypted = ApiSensitiveParamUtils.encryptSensitiveValues(params);

        Assert.assertEquals("secret", encrypted.get("password"));
        Assert.assertEquals("https://10.10.132.100:9440", encrypted.get("host"));
    }
    @Test public void configurationPayloadAndCredentialMappingNeverAppearInAuditValues() {
        Map<String, String> values = new LinkedHashMap<>();values.put("configurationpayload", "synthetic-archive");
        values.put("credentials", "synthetic-credentials");values.put("plantoken", "synthetic-token");values.put("artifactid", "public-artifact");
        Map<String, String> redacted = ApiSensitiveParamUtils.redactValues(values);
        Assert.assertEquals("******", redacted.get("configurationpayload"));Assert.assertEquals("******", redacted.get("credentials"));
        Assert.assertEquals("******", redacted.get("plantoken"));Assert.assertEquals("public-artifact", redacted.get("artifactid"));
    }

}
