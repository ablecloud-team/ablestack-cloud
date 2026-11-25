/*
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
*/

// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information.
package com.cloud.api.response;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import org.apache.cloudstack.api.response.AsyncJobResponse;
import org.apache.cloudstack.api.response.CreateExternalBackupResponse;
import org.apache.cloudstack.api.response.CreateNetBackupResponse;
import org.apache.cloudstack.api.response.SuccessResponse;
import org.junit.Assert;
import org.junit.Test;

public class ApiResponseSerializerTest {
    @Test public void asyncExternalBackupAcceptanceKeepsItsUuidInTheActualJobResult() {
        for (CreateExternalBackupResponse acceptance : new CreateExternalBackupResponse[]{
                new CreateNetBackupResponse(), new CreateExternalBackupResponse()}) {
            acceptance.setBackupId("07d03010-a77d-4e72-82c7-1c6c02d767db");
            AsyncJobResponse response = new AsyncJobResponse();
            response.setResponseName("queryasyncjobresultresponse");
            response.setJobResult(acceptance);
            String json = ApiResponseSerializer.toJSONSerializedString(response, new StringBuilder());
            var result = JsonParser.parseString(json).getAsJsonObject()
                    .getAsJsonObject("queryasyncjobresultresponse").getAsJsonObject("jobresult");
            Assert.assertTrue(result.get("success").getAsBoolean());
            Assert.assertEquals(acceptance.getBackupId(), result.get("backupid").getAsString());
            Assert.assertFalse(result.has("null"));
        }
    }

    @Test public void ordinaryAsyncSuccessRetainsItsExistingFields() {
        SuccessResponse success = new SuccessResponse();
        success.setDisplayText("operation completed");
        AsyncJobResponse response = new AsyncJobResponse();
        response.setResponseName("queryasyncjobresultresponse");
        response.setJobResult(success);
        String json = ApiResponseSerializer.toJSONSerializedString(response, new StringBuilder());
        var result = JsonParser.parseString(json).getAsJsonObject()
                .getAsJsonObject("queryasyncjobresultresponse").getAsJsonObject("jobresult");
        Assert.assertEquals(2, result.size());
        Assert.assertTrue(result.get("success").getAsBoolean());
        Assert.assertEquals("operation completed", result.get("details").getAsString());
    }

    @Test public void asyncExternalBackupWithoutAnIdDoesNotInventOne() {
        AsyncJobResponse response = new AsyncJobResponse();
        response.setResponseName("queryasyncjobresultresponse");
        response.setJobResult(new CreateNetBackupResponse());
        String json = ApiResponseSerializer.toJSONSerializedString(response, new StringBuilder());
        var result = JsonParser.parseString(json).getAsJsonObject()
                .getAsJsonObject("queryasyncjobresultresponse").getAsJsonObject("jobresult");
        Assert.assertTrue(result.get("success").getAsBoolean());
        Assert.assertFalse(result.has("backupid"));
    }

    @Test public void nestedJsonWithEqualsRemainsValidAndLossless() {
        Gson gson = new Gson();
        String inner = gson.toJson(java.util.Map.of("reason", "io.policy=threads"));
        String outer = gson.toJson(java.util.Map.of("details", inner));
        String actual = ApiResponseSerializer.unescape(outer);
        Assert.assertEquals(inner, JsonParser.parseString(actual).getAsJsonObject().get("details").getAsString());
    }
    @Test public void literalUnicodeEscapeIsNotDecodedAgain() {
        String literal = "C:" + "\\" + "u003d";
        String encoded = new Gson().toJson(literal);
        Assert.assertEquals(literal, JsonParser.parseString(ApiResponseSerializer.unescape(encoded)).getAsString());
    }
    @Test public void jsonSpecialCharactersRemainEscaped() {
        for (String code : new String[] {"0022", "005c", "000a", "0000"}) {
            String encoded = "\"" + "\\" + "u" + code + "\"";
            Assert.assertEquals(JsonParser.parseString(encoded), JsonParser.parseString(ApiResponseSerializer.unescape(encoded)));
        }
    }
}
