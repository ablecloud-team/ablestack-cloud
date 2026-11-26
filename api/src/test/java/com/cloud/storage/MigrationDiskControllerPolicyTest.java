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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.gson.JsonParser;

import org.junit.Test;

import java.util.Map;

public class MigrationDiskControllerPolicyTest {
    public static final String PLAN =
            "{\"version\":1,\"policy\":\"auto\",\"machine\":\"q35\",\"firmware\":\"bios\",\"root_selection\":\"source_disk_label\",\"source_vm_uuid\":\"source-uuid\",\"disks\":[{\"index\":0,\"disk_id\":\"ide0:0\",\"device_key\":\"2000\",\"kind\":\"ide\",\"bus\":0,\"unit\":0,\"size_bytes\":1073741824,\"role\":\"root\",\"target\":{\"bus\":\"sata\",\"model\":\"ahci\",\"device\":\"sda\"}},{\"index\":1,\"disk_id\":\"scsi0:0\",\"device_key\":\"2001\",\"kind\":\"scsi\",\"bus\":0,\"unit\":0,\"size_bytes\":1073741824,\"role\":\"data\",\"target\":{\"bus\":\"scsi\",\"model\":\"virtio-scsi\",\"device\":\"sdb\"}}],\"hash\":\"143c43746c7017fd9c613629c42061fb6b3b06dbcab44d65a8ae5b9ca2e26e1f\"}";

    @Test
    public void pythonCanonicalIntentRoundTrips() {
        var p = MigrationDiskControllerPolicy.parse(PLAN);
        assertEquals(
                "ide",
                p.getAsJsonArray("disks").get(0).getAsJsonObject().get("kind").getAsString());
        assertEquals(
                "sata",
                p.getAsJsonArray("disks")
                        .get(0)
                        .getAsJsonObject()
                        .getAsJsonObject("target")
                        .get("bus")
                        .getAsString());
        assertEquals(
                p, MigrationDiskControllerPolicy.parse(MigrationDiskControllerPolicy.canonical(p)));
    }

    @Test
    public void changedPolicyCannotKeepOldHash() {
        var p = JsonParser.parseString(PLAN).getAsJsonObject();
        p.addProperty("policy", "sata");
        reject(() -> MigrationDiskControllerPolicy.parse(p.toString()));
    }

    @Test
    public void selfConsistentHashCannotHideMalformedRoles() {
        var p = JsonParser.parseString(PLAN).getAsJsonObject();
        p.getAsJsonArray("disks").get(0).getAsJsonObject().addProperty("role", "data");
        p.remove("hash");
        p.addProperty("hash", MigrationDiskControllerPolicy.hash(p));
        reject(() -> MigrationDiskControllerPolicy.parse(p.toString()));
    }

    @Test
    public void chunkedMetadataHasNoColumnMigration() {
        String value = PLAN.repeat(8);
        Map<String, String> details =
                MigrationDiskControllerPolicy.chunks(MigrationDiskControllerPolicy.PLAN, value);
        assertTrue(details.values().stream().allMatch(s -> s.length() <= 4000));
        assertEquals(
                value,
                MigrationDiskControllerPolicy.read(details, MigrationDiskControllerPolicy.PLAN));
        details.remove(MigrationDiskControllerPolicy.PLAN + ".1");
        reject(
                () ->
                        MigrationDiskControllerPolicy.read(
                                details, MigrationDiskControllerPolicy.PLAN));
    }

    @Test
    public void addressNamesCoverTwentySeventhDisk() {
        assertEquals("aa", MigrationDiskControllerPolicy.letter(26));
        assertEquals("iv", MigrationDiskControllerPolicy.letter(255));
    }

    private void reject(Runnable action) {
        try {
            action.run();
            fail("Expected contract rejection");
        } catch (com.cloud.exception.InvalidParameterValueException expected) {
            assertNotNull(expected.getMessage());
        }
    }
}
