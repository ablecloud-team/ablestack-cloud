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

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.cloudstack.api.response.SharedFSPreparationResponse;
import org.junit.Assert;
import org.junit.Test;

/** Exact wire contract shared with the UI fixture captured from cluster 13. */
public class SharedFSPreparationWireTest {
    @Test public void actualSerializerMatchesCapturedPreparationWire() throws Exception {
        String fixture;
        try (InputStream input = getClass().getResourceAsStream("/sharedfs-preparation-api-wire.json")) {
            Assert.assertNotNull(input);
            fixture = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        JsonObject expected = JsonParser.parseString(fixture).getAsJsonObject();
        JsonObject state = expected.getAsJsonObject("getsharedfilesystempreparationresponse")
            .getAsJsonObject("sharedfilesystempreparation").getAsJsonObject("preparation");
        SharedFSPreparationResponse response = new SharedFSPreparationResponse();
        response.setObjectName("sharedfilesystempreparation");
        response.setResponseName("getsharedfilesystempreparationresponse");
        response.setPreparationJson(state.toString());
        String actual = ApiResponseSerializer.toSerializedString(response, "json");
        Assert.assertEquals(expected, JsonParser.parseString(actual));
        Assert.assertFalse(expected.getAsJsonObject("getsharedfilesystempreparationresponse").has("preparation"));
        Assert.assertEquals("PREPARED", state.get("status").getAsString());
    }
}
