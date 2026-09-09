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
