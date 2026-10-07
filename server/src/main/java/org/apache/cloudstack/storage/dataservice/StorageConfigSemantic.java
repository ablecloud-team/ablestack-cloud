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
import java.util.Set;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Public configuration uses semantic UUID references; internal SQL snapshots never cross this boundary. */
public final class StorageConfigSemantic {
    private static final Map<String, String> FILES = Map.of(
            "storage_service_protocol", "desired/protocols.json",
            "storage_file_share", "desired/file-shares.json",
            "storage_block_target", "desired/block-targets.json",
            "storage_access_rule", "desired/access-rules.json",
            "storage_identity_domain", "desired/identity-domain.json",
            "storage_posix_directory_policy", "desired/posix-directory-policies.json");
    private static final Set<String> OBSERVATIONS = Set.of("lastInspection", "backingPath", "volumeMountPath", "attachedVolumeName",
            "attachedVolumeUuid", "filesystemUuid", "observedDevicePath", "posixPolicyPath", "posixPolicyRevision",
            "managedUser", "managedGroup", "managedPosixUser", "managedPosixGroup", "effectiveUid", "effectiveGid", "effectiveMode");
    private StorageConfigSemantic() { }
    public static JsonElement redact(JsonElement value) {
        if (value == null || value.isJsonNull()) return com.google.gson.JsonNull.INSTANCE;
        if (value.isJsonArray()) {
            JsonArray result = new JsonArray();for (JsonElement item : value.getAsJsonArray()) result.add(redact(item));return result;
        }
        if (!value.isJsonObject()) return value.deepCopy();
        JsonObject result = new JsonObject();
        for (Map.Entry<String, JsonElement> field : value.getAsJsonObject().entrySet()) {
            String key = field.getKey();String lower = key.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("password") || lower.contains("secret") || Set.of("lmhash", "nthash", "keytab", "dhchapkey", "dhchapctrlkey", "privatekey").contains(lower)) continue;
            result.add(key, redact(field.getValue()));
        }
        return result;
    }
    public static Map<String, byte[]> export(String snapshot, JsonObject instance, Map<Long, JsonObject> volumes) {
        JsonObject internal = new JsonParser().parse(snapshot).getAsJsonObject();
        Map<String, Map<Long, String>> identities = new LinkedHashMap<>();
        for (JsonElement value : internal.getAsJsonArray("tables")) {
            JsonObject table = value.getAsJsonObject();Map<Long, String> ids = new LinkedHashMap<>();
            for (JsonElement item : table.getAsJsonArray("rows")) {
                JsonObject row = item.getAsJsonObject();ids.put(row.get("id").getAsLong(), row.get("uuid").getAsString());
            }
            identities.put(table.get("table").getAsString(), ids);
        }
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("desired/instance.json", bytes(redact(instance)));
        JsonArray volumeInventory = new JsonArray();for (JsonObject volume : volumes.values()) volumeInventory.add(redact(volume));
        entries.put("desired/volumes.json", bytes(volumeInventory));
        for (JsonElement value : internal.getAsJsonArray("tables")) {
            JsonObject table = value.getAsJsonObject();String name = table.get("table").getAsString();
            if (!FILES.containsKey(name)) throw new CloudRuntimeException("Unsupported internal desired-state table");
            JsonArray resources = new JsonArray();
            for (JsonElement item : table.getAsJsonArray("rows")) {
                JsonObject row = item.getAsJsonObject();JsonObject resource = new JsonObject();
                for (Map.Entry<String, JsonElement> field : row.entrySet()) {
                    String key = field.getKey();JsonElement data = field.getValue();
                    String lower = key.toLowerCase(java.util.Locale.ROOT);
                    if (lower.contains("secret") || lower.contains("password") || lower.contains("privatekey")) continue;
                    if (Set.of("id", "instance_id", "volume_id", "resource_id", "posix_policy_id", "created", "updated", "removed", "last_applied", "effective_json", "path_key").contains(key)) continue;
                    if ("config_json".equals(key)) {
                        JsonObject config = data.isJsonNull() ? new JsonObject() : new JsonParser().parse(data.getAsString()).getAsJsonObject();
                        config = redact(config).getAsJsonObject();for (String observation : OBSERVATIONS) config.remove(observation);
                        resource.add("config", config);
                    } else resource.add(key, data.deepCopy());
                }
                if (row.has("volume_id") && !row.get("volume_id").isJsonNull()) {
                    JsonObject volume = volumes.get(row.get("volume_id").getAsLong());
                    if (volume == null || !volume.has("uuid")) throw new CloudRuntimeException("Backing volume UUID is unavailable");
                    resource.add("volumeUuid", volume.get("uuid").deepCopy());
                }
                if (row.has("posix_policy_id") && !row.get("posix_policy_id").isJsonNull()) {
                    String policy = identities.get("storage_posix_directory_policy").get(row.get("posix_policy_id").getAsLong());
                    if (policy == null) throw new CloudRuntimeException("Directory policy UUID is unavailable");
                    resource.addProperty("posixPolicyUuid", policy);
                }
                if ("storage_access_rule".equals(name)) {
                    String ownerTable = "FILE_SHARE".equals(row.get("resource_type").getAsString()) ? "storage_file_share" : "storage_block_target";
                    String owner = identities.get(ownerTable).get(row.get("resource_id").getAsLong());
                    if (owner == null) throw new CloudRuntimeException("ACL owner UUID is unavailable");
                    resource.addProperty("resourceUuid", owner);
                }
                resources.add(resource);
            }
            entries.put(FILES.get(name), bytes(resources));
        }
        return entries;
    }
    private static byte[] bytes(JsonElement value) { return value.toString().getBytes(StandardCharsets.UTF_8); }
}
