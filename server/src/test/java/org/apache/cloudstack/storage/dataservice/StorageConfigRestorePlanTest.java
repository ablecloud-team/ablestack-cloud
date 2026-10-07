// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.cloudstack.storage.dataservice;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public class StorageConfigRestorePlanTest {
    private static final String SHARE = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    private static final String EXTRA = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
    private static final String VOLUME = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";
    private static final String INSTANCE = "dddddddd-dddd-4ddd-8ddd-dddddddddddd";
    private Map<String, byte[]> archive(boolean withShare, boolean extra) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (String kind : StorageConfigRestorePlan.ROW_KEYS.keySet()) entries.put("desired/" + kind + ".json", "[]".getBytes(StandardCharsets.UTF_8));
        JsonArray shares = new JsonArray();
        if (withShare) shares.add(share(SHARE));if (extra) shares.add(share(EXTRA));
        entries.put("desired/file-shares.json", shares.toString().getBytes(StandardCharsets.UTF_8));
        JsonArray volumes = new JsonArray();JsonObject volume = new JsonObject();volume.addProperty("uuid", VOLUME);volume.addProperty("size", 4096);volumes.add(volume);
        entries.put("desired/volumes.json", volumes.toString().getBytes(StandardCharsets.UTF_8));return entries;
    }
    private JsonObject share(String uuid) {
        JsonObject share = new JsonObject();share.addProperty("uuid", uuid);share.addProperty("protocol", "NFS");share.addProperty("name", "project");
        share.addProperty("volumeUuid", VOLUME);share.addProperty("state", "Ready");JsonObject config = new JsonObject();config.addProperty("readOnly", false);share.add("config", config);return share;
    }
    private JsonObject mapping() {
        JsonObject mappings = new JsonObject();JsonObject volumes = new JsonObject();volumes.addProperty(VOLUME, VOLUME);mappings.add("volumes", volumes);return mappings;
    }
    @Test public void equalResourceIsKeptAndResourcesOutsideBackupArePreserved() {
        JsonObject plan = StorageConfigRestorePlan.build(archive(true, false), archive(true, true), mapping(), "RESTORE_EXISTING", INSTANCE, 28);
        Assert.assertEquals(1, plan.getAsJsonArray("keep").size());Assert.assertEquals(1, plan.getAsJsonArray("preserve").size());
        Assert.assertEquals(0, plan.getAsJsonArray("delete").size());Assert.assertEquals(0, plan.getAsJsonArray("blockers").size());
        Assert.assertFalse(plan.get("automaticFormat").getAsBoolean());Assert.assertEquals(28, plan.get("expectedRevision").getAsLong());
    }
    @Test public void missingVolumeMappingIsBlockedRatherThanGuessedFromMatchingUuid() {
        JsonObject plan = StorageConfigRestorePlan.build(archive(true, false), archive(true, false), new JsonObject(), "RESTORE_EXISTING", INSTANCE, 28);
        Assert.assertTrue(plan.getAsJsonArray("blockers").toString().contains("VOLUME_MAPPING_REQUIRED"));
    }
    @Test public void missingTargetVolumeCannotBeSilentlySubstituted() {
        Map<String, byte[]> current = archive(false, false);current.put("desired/volumes.json", "[]".getBytes(StandardCharsets.UTF_8));
        JsonObject plan = StorageConfigRestorePlan.build(archive(true, false), current, mapping(), "RESTORE_EXISTING", INSTANCE, 28);
        Assert.assertTrue(plan.getAsJsonArray("blockers").toString().contains("VOLUME_MAPPING_UNAVAILABLE"));
    }
    @Test public void importedDatabaseIdsAndDanglingAclOwnersCannotEnterPlan() {
        Map<String, byte[]> source = archive(true, false);JsonObject share = share(SHARE);share.addProperty("id", 99);
        JsonArray shares = new JsonArray();shares.add(share);source.put("desired/file-shares.json", shares.toString().getBytes(StandardCharsets.UTF_8));
        Map<String, byte[]> databaseFields = source;
        Assert.assertThrows(CloudRuntimeException.class, () -> StorageConfigRestorePlan.resources(databaseFields));
        source = archive(true, false);JsonObject acl = new JsonObject();acl.addProperty("uuid", EXTRA);acl.addProperty("resource_type", "FILE_SHARE");acl.addProperty("resourceUuid", INSTANCE);
        JsonArray acls = new JsonArray();acls.add(acl);source.put("desired/access-rules.json", acls.toString().getBytes(StandardCharsets.UTF_8));
        Map<String, byte[]> dangling = source;Assert.assertThrows(CloudRuntimeException.class, () -> StorageConfigRestorePlan.resources(dangling));
    }
    @Test public void clonePlanUsesCreationActionsAndDoesNotReuseSourceResourceIds() {
        JsonObject plan = StorageConfigRestorePlan.build(archive(true, false), archive(false, false), mapping(), "CREATE_NEW", INSTANCE, 0);
        Assert.assertEquals(1, plan.getAsJsonArray("create").size());Assert.assertEquals(0, plan.getAsJsonArray("update").size());
        Assert.assertFalse(plan.getAsJsonArray("create").get(0).getAsJsonObject().has("targetUuid"));Assert.assertEquals(0, plan.getAsJsonArray("delete").size());
    }
    @Test public void changedDesiredConfigIsAReviewedUpdateNotAnImplicitDelete() {
        Map<String, byte[]> source = archive(true, false);JsonObject share = share(SHARE);share.getAsJsonObject("config").addProperty("readOnly", true);
        JsonArray shares = new JsonArray();shares.add(share);source.put("desired/file-shares.json", shares.toString().getBytes(StandardCharsets.UTF_8));
        JsonObject plan = StorageConfigRestorePlan.build(source, archive(true, false), mapping(), "RESTORE_EXISTING", INSTANCE, 28);
        Assert.assertEquals(1, plan.getAsJsonArray("update").size());Assert.assertEquals(0, plan.getAsJsonArray("delete").size());
        Assert.assertTrue(plan.getAsJsonArray("update").get(0).getAsJsonObject().has("current"));
    }
    @Test public void mappedVolumeIdentityIsComparedAtTheTargetRatherThanTheSource() {
        Map<String, byte[]> current = archive(true, false);JsonObject actual = share(SHARE);actual.addProperty("volumeUuid", INSTANCE);
        JsonArray shares = new JsonArray();shares.add(actual);current.put("desired/file-shares.json", shares.toString().getBytes(StandardCharsets.UTF_8));
        JsonArray volumes = new JsonArray();JsonObject volume = new JsonObject();volume.addProperty("uuid", INSTANCE);volumes.add(volume);
        current.put("desired/volumes.json", volumes.toString().getBytes(StandardCharsets.UTF_8));
        JsonObject mappings = mapping();mappings.getAsJsonObject("volumes").addProperty(VOLUME, INSTANCE);
        JsonObject plan = StorageConfigRestorePlan.build(archive(true, false), current, mappings, "RESTORE_EXISTING", INSTANCE, 28);
        Assert.assertEquals(1, plan.getAsJsonArray("keep").size());Assert.assertEquals(0, plan.getAsJsonArray("update").size());
    }
    @Test public void allRequiredSecretsAndOnlyReviewedSecretFieldsAreAcceptedBeforeMutation() {
        JsonArray required = new JsonArray();JsonObject item = new JsonObject();item.addProperty("ruleUuid", SHARE);
        JsonArray fields = new JsonArray();fields.add("chapsecret");fields.add("mutualchapsecret");item.add("fields", fields);required.add(item);
        JsonObject supplied = new JsonObject();JsonObject entry = new JsonObject();entry.addProperty("chapsecret", "synthetic");supplied.add(SHARE, entry);
        Assert.assertThrows(CloudRuntimeException.class, () -> StorageConfigRestorePlan.requireCredentials(required, supplied));
        entry.addProperty("mutualchapsecret", "synthetic2");StorageConfigRestorePlan.requireCredentials(required, supplied);
        entry.addProperty("password", "unexpected");
        Assert.assertThrows(CloudRuntimeException.class, () -> StorageConfigRestorePlan.requireCredentials(required, supplied));
        entry.remove("password");supplied.add(EXTRA, entry.deepCopy());
        Assert.assertThrows(CloudRuntimeException.class, () -> StorageConfigRestorePlan.requireCredentials(required, supplied));
    }

}
