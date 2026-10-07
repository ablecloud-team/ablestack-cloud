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

import java.util.Map;
import org.junit.Assert;
import org.junit.Test;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class StorageConfigSemanticTest {
    private JsonObject row(long id, String uuid) { JsonObject row = new JsonObject();row.addProperty("id", id);row.addProperty("uuid", uuid);return row; }
    private void table(JsonArray tables, String name, JsonObject... rows) {
        JsonObject table = new JsonObject();table.addProperty("table", name);JsonArray values = new JsonArray();
        for (JsonObject row : rows) values.add(row);table.add("rows", values);tables.add(table);
    }
    private String snapshot() {
        JsonArray tables = new JsonArray();
        JsonObject policy = row(11, "policy");policy.addProperty("instance_id", 7);policy.addProperty("volume_id", 45);
        policy.addProperty("relative_path", "smb/project");policy.addProperty("path_key", "internal-index");
        policy.addProperty("config_json", "{'ownerUid':1001001,'directoryMode':'2775'}".replace((char) 39, (char) 34));
        JsonObject share = row(12, "share");share.addProperty("instance_id", 7);share.addProperty("volume_id", 45);share.addProperty("posix_policy_id", 11);
        share.addProperty("config_json", "{'relativeSharePath':'smb/project','volumeMountPath':'/internal/mount','lastInspection':{'device':'/dev/sdb'}}".replace((char) 39, (char) 34));
        JsonObject acl = row(13, "rule");acl.addProperty("instance_id", 7);acl.addProperty("resource_id", 12);acl.addProperty("resource_type", "FILE_SHARE");
        acl.add("secret_ref", com.google.gson.JsonNull.INSTANCE);
        acl.addProperty("principal_type", "LOCAL_USER");acl.addProperty("principal", "synthetic-client");
        acl.addProperty("config_json", "{'localAccount':true,'passwordSupplied':true,'password':'synthetic'}".replace((char) 39, (char) 34));
        table(tables, "storage_service_protocol");table(tables, "storage_file_share", share);table(tables, "storage_block_target");
        table(tables, "storage_identity_domain");table(tables, "storage_access_rule", acl);table(tables, "storage_posix_directory_policy", policy);
        JsonObject snapshot = new JsonObject();snapshot.addProperty("schemaVersion", 2);snapshot.addProperty("instanceId", 7);snapshot.add("tables", tables);return snapshot.toString();
    }
    private Map<Long, JsonObject> volumes() {
        JsonObject volume = new JsonObject();volume.addProperty("uuid", "data-volume");volume.addProperty("size", 20L * 1024 * 1024 * 1024);
        return Map.of(45L, volume);
    }
    @Test public void publicExportUsesUuidBindingsAndNeverRawDatabaseIdsOrRuntimeInspection() {
        Map<String, byte[]> entries = StorageConfigSemantic.export(snapshot(), new JsonObject(), volumes());
        JsonObject share = StorageConfigArchive.json(entries.get("desired/file-shares.json")).getAsJsonArray().get(0).getAsJsonObject();
        Assert.assertEquals("data-volume", share.get("volumeUuid").getAsString());Assert.assertEquals("policy", share.get("posixPolicyUuid").getAsString());
        Assert.assertFalse(share.has("id"));Assert.assertFalse(share.has("volume_id"));Assert.assertFalse(share.has("instance_id"));
        Assert.assertFalse(share.getAsJsonObject("config").has("lastInspection"));Assert.assertFalse(share.getAsJsonObject("config").has("volumeMountPath"));
        JsonObject acl = StorageConfigArchive.json(entries.get("desired/access-rules.json")).getAsJsonArray().get(0).getAsJsonObject();
        Assert.assertEquals("share", acl.get("resourceUuid").getAsString());Assert.assertFalse(acl.has("resource_id"));Assert.assertFalse(acl.has("secret_ref"));
        Assert.assertTrue(acl.getAsJsonObject("config").get("localAccount").getAsBoolean());
        Assert.assertFalse(acl.getAsJsonObject("config").has("password"));Assert.assertFalse(acl.getAsJsonObject("config").has("passwordSupplied"));
        Assert.assertFalse(entries.containsKey("desired/raw-snapshot.json"));
        StorageConfigArchive.validate(StorageConfigArchive.create(entries, new JsonObject()));
    }
    @Test public void unknownOrMissingBackingVolumeCannotBeSilentlyReplaced() {
        Assert.assertThrows(CloudRuntimeException.class, () -> StorageConfigSemantic.export(snapshot(), new JsonObject(), Map.of()));
    }
    @Test public void recursiveCredentialRedactionPreservesNonsecretAuthenticationRequirements() {
        JsonObject original = new JsonParser().parse("{'chapEnabled':true,'nested':[{'dhChapKey':'synthetic','user':'client'}]}").getAsJsonObject();
        JsonObject publicValue = StorageConfigSemantic.redact(original).getAsJsonObject();
        Assert.assertTrue(publicValue.get("chapEnabled").getAsBoolean());
        Assert.assertFalse(publicValue.getAsJsonArray("nested").get(0).getAsJsonObject().has("dhChapKey"));
        Assert.assertEquals("client", publicValue.getAsJsonArray("nested").get(0).getAsJsonObject().get("user").getAsString());
        Assert.assertTrue(original.getAsJsonArray("nested").get(0).getAsJsonObject().has("dhChapKey"));
    }
}
