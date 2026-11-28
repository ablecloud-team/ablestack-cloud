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
package com.cloud.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.junit.Test;

public class MigrationGuestConfigurationPolicyTest {
    private JsonObject plan() {
        JsonObject p = new JsonObject();
        p.addProperty("version", 1);
        p.addProperty("policy", "preserve");
        p.addProperty("os", "windows");
        p.addProperty("helper_version", 1);
        p.addProperty("helper_sha256", "b".repeat(64));
        p.addProperty("operation_id", "00000000-0000-0000-0000-000000000001");
        p.addProperty("source_vm_uuid", "00000000-0000-0000-0000-000000000002");
        p.addProperty("controller_plan_hash", "a".repeat(64));
        p.addProperty("root_identity", "gpt:root");
        p.addProperty("root_source_disk_id", "ide0:0");
        p.addProperty("source_capture_id", "capture");
        JsonObject w = new JsonObject();
        w.addProperty("san_policy", "OfflineShared");
        w.add("disks", new JsonArray());
        p.add("windows", w);
        p.addProperty("hash", MigrationDiskControllerPolicy.hash(p));
        return p;
    }

    @Test
    public void canonicalPlanAndFixedDirectory() {
        var p = plan();
        assertEquals(p, MigrationGuestConfigurationPolicy.parse(p.toString()));
        assertTrue(
                MigrationGuestConfigurationPolicy.directory(p)
                        .startsWith("C:/ProgramData/ABLECLOUD/"));
    }

    @Test
    public void changedIntentCannotKeepOldHash() {
        var p = plan();
        p.addProperty("root_identity", "other");
        reject(p);
    }

    @Test
    public void malformedIdentityAndUnknownVersionFailClosed() {
        for (String key : new String[] {"operation_id", "source_vm_uuid"}) {
            var p = plan();
            p.addProperty(key, "../other");
            p.remove("hash");
            p.addProperty("hash", MigrationDiskControllerPolicy.hash(p));
            reject(p);
        }
        var p = plan();
        p.addProperty("version", 2);
        reject(p);
    }

    private void reject(JsonObject p) {
        try {
            MigrationGuestConfigurationPolicy.parse(p.toString());
            fail("Expected rejection");
        } catch (com.cloud.exception.InvalidParameterValueException expected) {
            assertTrue(expected.getMessage().contains("GUEST_CONFIG"));
        }
    }
}
