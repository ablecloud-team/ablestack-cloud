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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** A read-only semantic plan. Mapping confirmation and current domain validators precede execution. */
public final class StorageConfigRestorePlan {
    public static final Map<String, Set<String>> ROW_KEYS = Map.of(
            "protocols", Set.of("uuid", "protocol", "enabled", "listen_ip", "port", "state", "config"),
            "file-shares", Set.of("uuid", "protocol", "name", "path", "filesystem", "quota_bytes", "state", "config", "volumeUuid", "posixPolicyUuid"),
            "block-targets", Set.of("uuid", "protocol", "target_name", "lun_or_namespace", "state", "config", "volumeUuid"),
            "access-rules", Set.of("uuid", "resource_type", "principal_type", "principal", "permission", "state", "config", "resourceUuid"),
            "posix-directory-policies", Set.of("uuid", "relative_path", "revision", "state", "config", "volumeUuid"),
            "identity-domain", Set.of("uuid", "domain_name", "organizational_unit", "dns_servers", "join_state", "health_state", "config"));
    private StorageConfigRestorePlan() { }
    private static String text(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : null;
    }
    private static void uuid(String value) {
        try {
            if (value == null || !java.util.UUID.fromString(value).toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) { throw new CloudRuntimeException("Configuration resource UUID is invalid"); }
    }
    public static Map<String, JsonArray> resources(Map<String, byte[]> archive) {
        Map<String, JsonArray> result = new LinkedHashMap<>();
        Set<String> identities = new java.util.HashSet<>();
        for (Map.Entry<String, Set<String>> kind : ROW_KEYS.entrySet()) {
            byte[] bytes = archive.get("desired/" + kind.getKey() + ".json");
            if (bytes == null) throw new CloudRuntimeException("Configuration omits a desired resource collection");
            JsonElement value = StorageConfigArchive.json(bytes);
            if (!value.isJsonArray() || value.getAsJsonArray().size() > 2000) throw new CloudRuntimeException("Invalid or excessive configuration resource collection");
            JsonArray rows = value.getAsJsonArray();
            for (JsonElement entry : rows) {
                if (!entry.isJsonObject()) throw new CloudRuntimeException("Configuration resource is not an object");
                JsonObject row = entry.getAsJsonObject();String id = text(row, "uuid");uuid(id);
                if (!identities.add(id) || !kind.getValue().containsAll(row.keySet())) throw new CloudRuntimeException("Duplicate identity or unknown configuration resource field");
                if (row.has("config") && !row.get("config").isJsonObject()) throw new CloudRuntimeException("Invalid structured resource configuration");
                if (row.has("volumeUuid")) uuid(text(row, "volumeUuid"));
                if (row.has("resourceUuid")) uuid(text(row, "resourceUuid"));
                if (row.has("posixPolicyUuid")) uuid(text(row, "posixPolicyUuid"));
            }
            result.put(kind.getKey(), rows);
        }
        Set<String> shareIds = ids(result.get("file-shares"));Set<String> targetIds = ids(result.get("block-targets"));Set<String> policyIds = ids(result.get("posix-directory-policies"));
        for (JsonElement value : result.get("access-rules")) {
            JsonObject row = value.getAsJsonObject();String type = text(row, "resource_type");
            Set<String> owners = "FILE_SHARE".equals(type) ? shareIds : "BLOCK_TARGET".equals(type) ? targetIds : Set.of();
            if (!owners.contains(text(row, "resourceUuid"))) throw new CloudRuntimeException("Configuration ACL references an unknown resource");
        }
        for (JsonElement value : result.get("file-shares")) {
            JsonObject row = value.getAsJsonObject();
            if (row.has("posixPolicyUuid") && !policyIds.contains(text(row, "posixPolicyUuid"))) throw new CloudRuntimeException("Configuration share references an unknown directory policy");
        }
        return result;
    }
    private static Set<String> ids(JsonArray values) {
        Set<String> ids = new java.util.HashSet<>();for (JsonElement value : values) ids.add(text(value.getAsJsonObject(), "uuid"));return ids;
    }
    public static JsonObject build(Map<String, byte[]> archive, Map<String, byte[]> current, JsonObject mappings,
            String targetMode, String targetInstanceUuid, long currentRevision) {
        if (!Set.of("RESTORE_EXISTING", "CREATE_NEW").contains(targetMode)) throw new CloudRuntimeException("Unsupported configuration restore mode");
        uuid(targetInstanceUuid);
        Map<String, JsonArray> source = resources(archive);Map<String, JsonArray> target = resources(current);
        JsonArray creates = new JsonArray();JsonArray updates = new JsonArray();JsonArray keeps = new JsonArray();JsonArray preserves = new JsonArray();JsonArray blockers = new JsonArray();
        JsonObject usedMappings = new JsonObject();
        Set<String> mappedTargetIds = new java.util.HashSet<>();
        for (String kind : ROW_KEYS.keySet()) {
            Map<String, JsonObject> existing = new LinkedHashMap<>();
            for (JsonElement row : target.get(kind)) existing.put(text(row.getAsJsonObject(), "uuid"), row.getAsJsonObject());
            JsonObject requested = mappings.has(kind) ? mappings.getAsJsonObject(kind) : new JsonObject();JsonObject applied = new JsonObject();
            Set<String> consumed = new java.util.HashSet<>();
            for (JsonElement value : source.get(kind)) {
                JsonObject row = value.getAsJsonObject();String id = text(row, "uuid");
                String selected = "CREATE_NEW".equals(targetMode) ? null : (requested.has(id) ? requested.get(id).getAsString() : (existing.containsKey(id) ? id : null));
                JsonObject change = new JsonObject();change.addProperty("kind", kind);change.addProperty("sourceUuid", id);change.add("desired", row.deepCopy());
                if ("identity-domain".equals(kind) && text(row, "domain_name") != null && !text(row, "domain_name").isBlank()) {
                    blockers.add("SMB_AD_DEFERRED");
                }
                if (selected == null) {
                    change.addProperty("action", "CREATE");creates.add(change);
                } else {
                    uuid(selected);
                    if (!existing.containsKey(selected) || !mappedTargetIds.add(selected)) {
                        blockers.add("RESOURCE_MAPPING_INVALID:" + id);continue;
                    }
                    applied.addProperty(id, selected);change.addProperty("targetUuid", selected);consumed.add(selected);
                    JsonObject desired = row.deepCopy();JsonObject actual = existing.get(selected).deepCopy();
                    desired.remove("uuid");actual.remove("uuid");desired.remove("state");actual.remove("state");desired.remove("revision");actual.remove("revision");
                    change.add("current", existing.get(selected).deepCopy());
                    if (desired.equals(actual)) { change.addProperty("action", "KEEP");keeps.add(change); }
                    else { change.addProperty("action", "UPDATE");updates.add(change); }
                }
            }
            for (Map.Entry<String, JsonObject> value : existing.entrySet()) if (!consumed.contains(value.getKey())) {
                JsonObject preserve = new JsonObject();preserve.addProperty("kind", kind);preserve.addProperty("targetUuid", value.getKey());preserves.add(preserve);
            }
            usedMappings.add(kind, applied);
        }
        JsonObject volumeMappings = mappings.has("volumes") ? mappings.getAsJsonObject("volumes") : new JsonObject();
        Set<String> targetVolumes = new java.util.HashSet<>();
        byte[] volumeBytes = current.get("desired/volumes.json");
        if (volumeBytes != null) for (JsonElement value : StorageConfigArchive.json(volumeBytes).getAsJsonArray()) targetVolumes.add(text(value.getAsJsonObject(), "uuid"));
        for (String kind : new String[] {"file-shares", "block-targets", "posix-directory-policies"}) for (JsonElement value : source.get(kind)) {
            JsonObject row = value.getAsJsonObject();String volume = text(row, "volumeUuid");if (volume == null) continue;
            if (!volumeMappings.has(volume)) blockers.add("VOLUME_MAPPING_REQUIRED:" + volume);
            else {
                String selected = volumeMappings.get(volume).getAsString();uuid(selected);
                if (!targetVolumes.contains(selected)) blockers.add("VOLUME_MAPPING_UNAVAILABLE:" + selected);
            }
        }
        JsonObject plan = new JsonObject();plan.addProperty("schemaVersion", 1);plan.addProperty("targetMode", targetMode);
        plan.addProperty("targetInstanceUuid", targetInstanceUuid);plan.addProperty("expectedRevision", currentRevision);
        plan.add("create", creates);plan.add("update", updates);plan.add("keep", keeps);plan.add("preserve", preserves);plan.add("delete", new JsonArray());
        plan.add("blockers", blockers);plan.add("resourceMappings", usedMappings);plan.add("volumeMappings", volumeMappings.deepCopy());
        plan.addProperty("dataPolicy", "PRESERVE");plan.addProperty("automaticFormat", false);
        return plan;
    }
}
