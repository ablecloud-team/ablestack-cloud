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
import org.junit.Assert;
import org.junit.Test;

public class ApiResponseSerializerTest {
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
