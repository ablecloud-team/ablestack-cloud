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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.util.List;
import com.cloud.api.response.ApiResponseSerializer;
import com.cloud.storage.BucketVO;
import org.apache.cloudstack.api.response.BucketResponse;
import org.apache.cloudstack.api.response.BucketCredentialResponse;
import org.apache.cloudstack.storage.object.BucketCredentials;
import org.junit.Test;

public class BucketCredentialResponseSecurityTest {
    private BucketCredentialResponse response(boolean reveal) {
        BucketVO bucket = new BucketVO(1, 1, 11, "bucket", 1, false, false, false, "Private");
        BucketCredentialResponse response = new BucketCredentialResponse(bucket, "http://store/bucket",
                new BucketCredentials("PRIVATE_ACCESS_VALUE", "PRIVATE_SECRET_VALUE", "Verified", null), reveal, List.of(bucket));
        response.setResponseName("getbucketcredentialsresponse"); return response;
    }
    @Test public void ordinaryResponseCannotSerializeKeysEvenThroughOldSetters() {
        BucketResponse response = new BucketResponse(); response.setResponseName("listbucketsresponse");
        response.setAccessKey("PRIVATE_ACCESS_VALUE"); response.setSecretKey("PRIVATE_SECRET_VALUE");
        String serialized = ApiResponseSerializer.toSerializedStringWithSecureLogs(response, "json", new StringBuilder());
        assertFalse(serialized.contains("PRIVATE_")); assertFalse(serialized.contains("usersecretkey")); assertFalse(serialized.contains("accesskey"));
    }
    @Test public void previewIsMaskedAndContainsNoKeyValues() {
        String serialized = ApiResponseSerializer.toSerializedStringWithSecureLogs(response(false), "json", new StringBuilder());
        assertFalse(serialized.contains("PRIVATE_")); assertFalse(serialized.contains("usersecretkey")); assertTrue(serialized.contains("accesskeyhint"));
    }
    @Test public void explicitRevealReturnsKeysOnlyOnWireAndRedactsAuditSerialization() {
        StringBuilder log = new StringBuilder();
        String serialized = ApiResponseSerializer.toSerializedStringWithSecureLogs(response(true), "json", log);
        assertTrue(serialized.contains("PRIVATE_ACCESS_VALUE")); assertTrue(serialized.contains("PRIVATE_SECRET_VALUE"));
        assertFalse(log.toString().contains("PRIVATE_ACCESS_VALUE")); assertFalse(log.toString().contains("PRIVATE_SECRET_VALUE"));
    }
}
