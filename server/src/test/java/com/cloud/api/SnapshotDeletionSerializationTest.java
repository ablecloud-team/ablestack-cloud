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
package com.cloud.api;

import com.google.gson.JsonElement;
import org.apache.cloudstack.api.ResponseObject;
import org.apache.cloudstack.api.response.SnapshotDeletionResponse;
import org.junit.Assert;
import org.junit.Test;

public class SnapshotDeletionSerializationTest {
    @Test
    public void asyncResultRetainsDiagnosisInsteadOfSuccessOnly() {
        SnapshotDeletionResponse preview = new SnapshotDeletionResponse("snapshot", "fixture", "Destroyed", "zone");
        preview.setScopeHash("revision");
        preview.addReference(95L, "store", "Secondary", "Image", "Destroying", "zone");
        JsonElement wire = new ResponseObjectTypeAdapter().serialize(preview, ResponseObject.class, null);
        Assert.assertTrue(wire.getAsJsonObject().has("deletesnapshotresponse"));
        com.google.gson.JsonObject result = wire.getAsJsonObject().getAsJsonObject("deletesnapshotresponse");
        Assert.assertEquals("snapshot", result.get("snapshotid").getAsString());
        Assert.assertEquals("revision", result.get("scopehash").getAsString());
        Assert.assertEquals(95L, result.getAsJsonArray("reference").get(0).getAsJsonObject().get("referenceid").getAsLong());
    }
    @Test
    public void forcedDatabaseOnlyResultDoesNotClaimPhysicalCompletion() {
        SnapshotDeletionResponse response = new SnapshotDeletionResponse("snapshot", "fixture", "Destroyed", null);
        response.setExecutionResult(false, 1);
        response.addCleanupResult(95L, "Primary", "DB_ONLY", "MISSING_TARGET_METADATA");
        com.google.gson.JsonObject result = new ResponseObjectTypeAdapter().serialize(response, ResponseObject.class, null)
                .getAsJsonObject().getAsJsonObject("deletesnapshotresponse");
        Assert.assertTrue(result.get("force").getAsBoolean());
        Assert.assertTrue(result.get("dbcleanupcomplete").getAsBoolean());
        Assert.assertFalse(result.get("dryrun").getAsBoolean());
        Assert.assertFalse(result.get("physicalcleanupcomplete").getAsBoolean());
        Assert.assertEquals("DB_ONLY", result.getAsJsonArray("cleanupresult").get(0).getAsJsonObject().get("action").getAsString());
    }

}
