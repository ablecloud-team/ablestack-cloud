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

import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonObject;
import org.junit.Assert;
import org.junit.Test;

public class StorageLocalSourceNotStartedProofTest {
    private JsonObject context() {
        JsonObject value = new JsonObject();
        value.addProperty("instanceUuid", "11111111-1111-4111-8111-111111111111");
        value.addProperty("operationUuid", "22222222-2222-4222-8222-222222222222");
        value.addProperty("localCheckpointUuid", "33333333-3333-4333-8333-333333333333");
        value.addProperty("revision", 2);value.add("sourceGeneration", new JsonObject());
        value.addProperty("sourceConfigurationSha256", "a".repeat(64));
        value.addProperty("expectedBootId", "44444444-4444-4444-8444-444444444444");
        return value;
    }

    private JsonObject proof(JsonObject context) {
        JsonObject value = new JsonObject();
        value.addProperty("success", true);value.addProperty("checkpointSupported", true);
        value.add("scope", StorageLocalSourceIdentityProof.scope(context));
        value.add("sourceGeneration", context.get("sourceGeneration").deepCopy());
        value.add("sourceConfigurationSha256", context.get("sourceConfigurationSha256").deepCopy());
        value.add("bootId", context.get("expectedBootId").deepCopy());
        value.addProperty("journalPresent", false);value.addProperty("phase", "NOT_STARTED");
        value.addProperty("sourceStopNotStarted", true);value.addProperty("checkpointCipherAbsent", true);
        value.addProperty("sourceRuntimeVerified", true);value.addProperty("currentSessionsVerifiedEmpty", true);
        value.addProperty("canonicalDesiredStateChanged", false);return value;
    }

    private void reject(JsonObject context, JsonObject value) {
        try {StorageLocalSourceIdentityProof.resumed(context, value);Assert.fail("Incomplete or foreign proof must fail");}
        catch (CloudRuntimeException expected) { }
    }

    @Test public void unchangedEmptySourceMayCancelBeforeStopWithoutClaimingResume() {
        JsonObject context = context(), value = proof(context);
        StorageLocalSourceIdentityProof.resumed(context, value);
        Assert.assertFalse(value.has("sourceSmbResumed"));
    }

    @Test public void missingStringFalseOrForeignProofNeverAuthorizesCancellation() {
        JsonObject context = context();
        for (String field : new String[]{"sourceStopNotStarted", "checkpointCipherAbsent", "sourceRuntimeVerified", "currentSessionsVerifiedEmpty"}) {
            JsonObject value = proof(context);value.remove(field);reject(context, value);
            value = proof(context);value.addProperty(field, false);reject(context, value);
            value = proof(context);value.addProperty(field, "true");reject(context, value);
        }
        JsonObject value = proof(context);value.addProperty("canonicalDesiredStateChanged", true);reject(context, value);
        value = proof(context);value.addProperty("journalPresent", true);reject(context, value);
        value = proof(context);value.addProperty("phase", "STOPPED");reject(context, value);
        value = proof(context);value.addProperty("bootId", "55555555-5555-4555-8555-555555555555");reject(context, value);
        value = proof(context);value.getAsJsonObject("scope").addProperty("revision", 3);reject(context, value);
        value = proof(context);value.addProperty("sourceConfigurationSha256", "b".repeat(64));reject(context, value);
    }

    @Test public void existingJournalStillRequiresVerifiedResume() {
        JsonObject context = context(), value = proof(context);
        value.addProperty("journalPresent", true);value.addProperty("phase", "RESUMED");
        value.remove("sourceStopNotStarted");value.addProperty("sourceSmbResumed", true);
        StorageLocalSourceIdentityProof.resumed(context, value);
        value.addProperty("phase", "STOPPED");reject(context, value);
        value.addProperty("phase", "RESUMED");value.addProperty("sourceSmbResumed", false);reject(context, value);
    }
}
