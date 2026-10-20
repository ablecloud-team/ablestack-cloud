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
package org.apache.cloudstack.storage.dataservice;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/** Actual packaged native producers -> server consumer; no native code or host state is executed here. */
@RunWith(Parameterized.class)
public class StoragePreparationHealthWireContractTest {
    @Parameterized.Parameters(name="{0}")
    public static Collection<Object[]> samples() throws Exception {
        try(java.io.InputStream input=StoragePreparationHealthWireContractTest.class.getResourceAsStream("/storage-config/preparation-health-wire-contract.json")) {
            Assert.assertNotNull(input);
            JsonObject document=JsonParser.parseString(new String(input.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
            Assert.assertEquals(1,document.get("schemaVersion").getAsInt());JsonArray samples=document.getAsJsonArray("samples");
            Collection<Object[]> cases=new ArrayList<>();
            for(JsonElement item:samples){JsonObject sample=item.getAsJsonObject();cases.add(new Object[]{sample.get("name").getAsString(),sample});}
            return cases;
        }
    }
    private final JsonObject sample;
    public StoragePreparationHealthWireContractTest(String name,JsonObject sample){this.sample=sample;}
    @Test public void packagedProducerWireMatchesReadOnlyPreparationState(){
        long now=Math.round(sample.get("evaluatedAtEpoch").getAsDouble()*1000);
        JsonObject health=sample.getAsJsonObject("health"),expected=sample.getAsJsonObject("expected");String original=health.toString();
        StorageServicePreparationState.Result observed=StorageServicePreparationState.evaluate(true,true,true,health,
                sample.getAsJsonObject("before"),sample.getAsJsonObject("after"),sample.getAsJsonObject("idle"),sample.getAsJsonObject("maintenance"),now);
        Assert.assertEquals(expected.get("ready").getAsBoolean(),observed.ready);Assert.assertEquals(expected.get("status").getAsString(),observed.status);
        Assert.assertEquals(expected.get("reason").getAsString(),observed.reason);Assert.assertEquals("No BOOT values may be fabricated into a live payload",original,health.toString());
    }
}
