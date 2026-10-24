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
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.cloudstack.api.response.StorageServiceConfigurationPreflightResponse;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
public class StorageConfigurationPreflightWireTest {
    @Test public void preflightUsesNamedScalarAndRealDateSerialization() throws Exception {
        JsonObject sample=JsonParser.parseString("{\"status\":\"READY\",\"revision\":2,\"observedat\":1792829254,\"expiresat\":1792829284,\"fingerprint\":\"impact\",\"checks\":[{\"code\":\"MEMORY_AVAILABLE\",\"blocking\":false}],\"impact\":{\"ackrequired\":true}}").getAsJsonObject();
        StorageServiceConfigurationPreflightResponse response=new StorageServiceConfigurationPreflightResponse(sample);
        response.setResponseName("preflightstorageserviceconfigurationresponse");
        String text=ApiResponseSerializer.toSerializedString(response,"json");
        JsonObject result=JsonParser.parseString(text).getAsJsonObject().getAsJsonObject("preflightstorageserviceconfigurationresponse").getAsJsonObject("configurationpreflight");
        assertEquals("READY",result.get("status").getAsString());
        assertEquals(2,result.get("revision").getAsInt());
        assertTrue(result.get("checks").isJsonArray());
        assertFalse(result.get("cancellationsupported").getAsBoolean());
        new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ").parse(result.get("expiresat").getAsString());
        String output=System.getProperty("configuration.preflight.wire.fixture");
        if(output!=null) {
            java.nio.file.Path path=java.nio.file.Paths.get(output);
            assertTrue(path.toAbsolutePath().startsWith("/root/work/epic1438-preparation/"));
            java.nio.file.Files.writeString(path,text+"\n");
        }
    }
}
