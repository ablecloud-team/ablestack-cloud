/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.cloud.api;

import org.apache.cloudstack.api.ResponseObject;
import org.apache.cloudstack.api.response.SnapshotArchiveResponse;
import org.junit.Assert;
import org.junit.Test;

public class SnapshotArchiveSerializationTest {
    @Test public void asyncDiagnosisRetainsEligibilityReasonAndRevision() {
        SnapshotArchiveResponse response = new SnapshotArchiveResponse("snapshot", "fixture", "BackedUp", true,
                "REUSE_SECONDARY", "Verify backup before removing primary", 1, 1, "revision");
        com.google.gson.JsonObject result = new ResponseObjectTypeAdapter().serialize(response, ResponseObject.class, null)
                .getAsJsonObject().getAsJsonObject("archivesnapshotresponse");
        Assert.assertTrue(result.get("eligible").getAsBoolean());
        Assert.assertTrue(result.get("dryrun").getAsBoolean());
        Assert.assertEquals("REUSE_SECONDARY", result.get("reason").getAsString());
        Assert.assertEquals("revision", result.get("scopehash").getAsString());
        Assert.assertEquals(1, result.get("primaryreferences").getAsInt());
    }
}
