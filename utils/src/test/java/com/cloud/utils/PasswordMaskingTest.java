//
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
//

package com.cloud.utils;

import org.junit.Test;
import org.junit.Assert;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class PasswordMaskingTest {
    @Test
    public void masksEscapedAndFormattedPasswordsWithoutBreakingJson() {
        for (String secret : new String[] {"plain-secret", "\"quoted-secret", "\\backslash-secret", "line\nsecret", ""}) {
            JsonObject object = new JsonObject();
            object.addProperty("password", secret);
            object.addProperty("name", "preserved");
            String input = object.toString().replace(":", " : ");
            JsonObject masked = JsonParser.parseString(StringUtils.obfuscatePasswordInJsonLikeString(input)).getAsJsonObject();
            Assert.assertEquals("preserved", masked.get("name").getAsString());
            Assert.assertFalse(masked.get("password").getAsString().contains("secret"));
            Assert.assertEquals(secret.isEmpty(), masked.get("password").getAsString().isEmpty());
        }
    }

    @Test
    public void preservesNullAndNonPasswordPayloads() {
        Assert.assertNull(StringUtils.obfuscatePasswordInJsonLikeString(null));
        Assert.assertEquals("plain", StringUtils.obfuscatePasswordInJsonLikeString("plain"));
    }
}
