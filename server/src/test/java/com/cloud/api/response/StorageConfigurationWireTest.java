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
package com.cloud.api.response;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.cloudstack.api.response.StorageServiceConfigurationOperationResponse;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
/** A real serializer fixture consumed by the UI decoder regression. */
public class StorageConfigurationWireTest {
    @Test public void configurationOperationUsesTheActualNamedObjectEnvelope() throws Exception {
        StorageServiceConfigurationOperationResponse r = new StorageServiceConfigurationOperationResponse();
        r.setObjectName("configurationoperation"); r.setResponseName("getstorageserviceconfigurationoperationresponse");
        r.setId("c46af534-b1cf-4d0a-b0cf-d3187fef3cf9"); r.setRequestid("775e54bc-4141-48a3-a850-769d27aa775f");
        r.setState("ROLLED_BACK"); r.setPhase("COMPLETED");
        r.setOriginalfailure(JsonParser.parseString("{\"code\":\"VERIFY_FAILED\",\"phase\":\"VERIFYING\"}").getAsJsonObject());
        r.setRollback(JsonParser.parseString("{\"state\":\"SUCCEEDED\"}").getAsJsonObject());
        String text = ApiResponseSerializer.toSerializedString(r, "json");
        JsonObject body = JsonParser.parseString(text).getAsJsonObject()
                .getAsJsonObject("getstorageserviceconfigurationoperationresponse").getAsJsonObject("configurationoperation");
        assertEquals("ROLLED_BACK", body.get("state").getAsString());
        assertEquals("VERIFY_FAILED", body.getAsJsonObject("originalfailure").get("code").getAsString());
        assertFalse(text.contains("before_snapshot")); assertFalse(text.contains("candidate_snapshot"));
        String output = System.getProperty("configuration.wire.fixture");
        if (output != null) {
            java.nio.file.Path path = java.nio.file.Paths.get(output);
            assertTrue(path.toAbsolutePath().startsWith("/root/work/epic1438-preparation/"));
            java.nio.file.Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(JsonParser.parseString(text)) + "\n");
        }
    }
}
