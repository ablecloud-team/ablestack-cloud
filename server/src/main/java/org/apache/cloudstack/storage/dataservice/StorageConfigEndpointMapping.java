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
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.net.NetUtils;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Only reviewed listener addresses are translated; ACLs and unrelated config are never rewritten. */
final class StorageConfigEndpointMapping {
    private StorageConfigEndpointMapping() { }

    static JsonObject apply(Map<String, JsonArray> resources, JsonObject mappings, boolean requireMapping,
            JsonArray blockers) {
        return apply(resources, mappings, requireMapping, blockers, staticPrimary(mappings));
    }

    static JsonObject apply(Map<String, JsonArray> resources, JsonObject mappings, boolean requireMapping,
            JsonArray blockers, String targetPrimary) {
        if (targetPrimary != null) address(targetPrimary);
        JsonObject requested = new JsonObject();
        if (mappings.has("listenerAddresses")) {
            if (!mappings.get("listenerAddresses").isJsonObject()) throw new CloudRuntimeException("Listener mappings must be an object");
            requested = mappings.getAsJsonObject("listenerAddresses");
        }
        for (Map.Entry<String, JsonElement> entry : requested.entrySet()) {
            address(entry.getKey());
            if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString()) {
                throw new CloudRuntimeException("Listener mapping requires a literal address or PRIMARY");
            }
            if (!"PRIMARY".equals(entry.getValue().getAsString())) address(entry.getValue().getAsString());
        }
        Map<String, JsonObject> requirements = new LinkedHashMap<>();
        for (Map.Entry<String, JsonArray> collection : resources.entrySet()) for (JsonElement item : collection.getValue()) {
            JsonObject row = item.getAsJsonObject();
            scalar(row, "listen_ip", collection.getKey(), row, requested, requirements, requireMapping, blockers, targetPrimary);
            if (row.has("config") && row.get("config").isJsonObject()) {
                JsonObject config = row.getAsJsonObject("config");
                scalar(config, "listenIp", collection.getKey(), row, requested, requirements, requireMapping, blockers, targetPrimary);
                if (config.has("listenIps") && !config.get("listenIps").isJsonNull()) {
                    if (!config.get("listenIps").isJsonArray()) throw new CloudRuntimeException("Listener address list must be an array");
                    JsonArray translated = new JsonArray();
                    for (JsonElement original : config.getAsJsonArray("listenIps")) {
                        if (!original.isJsonPrimitive() || !original.getAsJsonPrimitive().isString()) {
                            throw new CloudRuntimeException("Listener address must be literal");
                        }
                        String value = mapped(original.getAsString(), collection.getKey(), row, "config.listenIps",
                                requested, requirements, requireMapping, blockers, targetPrimary);
                        if (!translated.contains(new com.google.gson.JsonPrimitive(value))) translated.add(value);
                    }
                    config.add("listenIps", translated);
                }
            }
        }
        for (String original : requested.keySet()) {
            if (!requirements.containsKey(original)) throw new CloudRuntimeException("Listener mapping references an absent source address");
        }
        JsonArray rows = new JsonArray();for (JsonObject requirement : requirements.values()) rows.add(requirement);
        JsonObject result = new JsonObject();result.add("listenerAddressRequirements", rows);
        result.add("listenerAddressMappings", requested.deepCopy());return result;
    }

    private static void scalar(JsonObject object, String field, String kind, JsonObject row, JsonObject requested,
            Map<String, JsonObject> requirements, boolean required, JsonArray blockers, String targetPrimary) {
        if (!object.has(field) || object.get(field).isJsonNull()) return;
        JsonElement original = object.get(field);
        if (!original.isJsonPrimitive() || !original.getAsJsonPrimitive().isString()) throw new CloudRuntimeException("Listener address must be literal");
        String value = mapped(original.getAsString(), kind, row, object == row ? field : "config." + field,
                requested, requirements, required, blockers, targetPrimary);
        object.addProperty(field, value);
    }

    private static String mapped(String original, String kind, JsonObject row, String path, JsonObject requested,
            Map<String, JsonObject> requirements, boolean required, JsonArray blockers, String targetPrimary) {
        address(original);
        JsonObject requirement = requirements.get(original);
        if (requirement == null) {
            requirement = new JsonObject();requirement.addProperty("sourceAddress", original);
            requirement.add("target", requested.has(original) ? requested.get(original).deepCopy() : com.google.gson.JsonNull.INSTANCE);
            if (requested.has(original)) {
                String target = requested.get(original).getAsString();
                requirement.add("resolvedTarget", "PRIMARY".equals(target)
                        ? targetPrimary == null ? com.google.gson.JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(targetPrimary)
                        : new com.google.gson.JsonPrimitive(target));
            }
            requirement.add("locations", new JsonArray());requirements.put(original, requirement);
        }
        JsonObject location = new JsonObject();location.addProperty("kind", kind);location.add("sourceUuid", row.get("uuid").deepCopy());
        location.addProperty("path", path);requirement.getAsJsonArray("locations").add(location);
        if (!requested.has(original)) {
            if (required) addBlocker(blockers, "LISTENER_MAPPING_REQUIRED:" + original);
            return original;
        }
        String target = requested.get(original).getAsString();
        if (!"PRIMARY".equals(target)) return target;
        if (targetPrimary == null) { addBlocker(blockers, "PRIMARY_LISTENER_REQUIRES_STATIC_ADDRESS");return original; }
        // Empty listenip means wildcard in the real API. PRIMARY must always remain an exact literal.
        return targetPrimary;
    }

    static String staticPrimary(JsonObject mappings) {
        if (!mappings.has("createNew") || !mappings.get("createNew").isJsonObject()) return null;
        JsonObject blueprint = mappings.getAsJsonObject("createNew");
        if (!blueprint.has("networkmode") || !"STATIC".equals(blueprint.get("networkmode").getAsString())
                || !blueprint.has("ipcidr") || blueprint.get("ipcidr").isJsonNull()) return null;
        String value = blueprint.get("ipcidr").getAsString().split("/", -1)[0];address(value);return value;
    }
    private static void address(String value) {
        if (!NetUtils.isValidIp4(value) && !NetUtils.isValidIp6(value)) throw new CloudRuntimeException("Listener mapping requires a literal IP address");
    }
    private static void addBlocker(JsonArray blockers, String value) {
        com.google.gson.JsonPrimitive item = new com.google.gson.JsonPrimitive(value);if (!blockers.contains(item)) blockers.add(item);
    }
}
