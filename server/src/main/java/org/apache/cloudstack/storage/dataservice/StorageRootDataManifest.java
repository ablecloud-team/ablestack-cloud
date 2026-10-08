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

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Frozen identities for every DATA disk; device names may change, DATA contents cannot. */
public final class StorageRootDataManifest {
    private StorageRootDataManifest() { }

    public static JsonObject freeze(JsonArray requested, JsonObject observed,
            Map<String, Set<String>> declaredFilesystems, long now) {
        if (!observed.has("generatedEpoch") || !observed.has("bootId")
                || !Double.isFinite(observed.get("generatedEpoch").getAsDouble())
                || Math.abs(now / 1000.0 - observed.get("generatedEpoch").getAsDouble()) > 30) {
            throw new CloudRuntimeException("Fresh ROOT DATA identity observation is unavailable");
        }
        Map<String, JsonObject> actual = new HashMap<>();
        for (JsonElement entry : observed.getAsJsonArray("volumes")) {
            JsonObject value = entry.getAsJsonObject();
            String uuid = text(value, "volumeUuid");
            if (uuid == null || actual.put(uuid, value) != null) {
                throw new CloudRuntimeException("ROOT DATA observation contains duplicate or unknown identities");
            }
        }
        if (actual.size() != requested.size()) {
            throw new CloudRuntimeException("ROOT DATA observation does not cover every attached DATA disk");
        }
        JsonArray volumes = new JsonArray();
        Set<String> unique = new HashSet<>();
        for (JsonElement entry : requested) {
            JsonObject request = entry.getAsJsonObject();
            String uuid = text(request, "volumeUuid");
            JsonObject value = actual.get(uuid);
            if (!unique.add(uuid) || value == null || !"EXACT".equals(text(value, "mappingStatus"))
                    || !"VOLUME_SERIAL".equals(text(value, "matchedBy")) || text(value, "serial") == null
                    || !Objects.equals(text(request, "kind"), text(value, "kind"))
                    || request.get("sizeBytes").getAsLong() != value.get("sizeBytes").getAsLong()) {
                throw new CloudRuntimeException("ROOT DATA device identity or size is not exact");
            }
            String filesystem = text(value, "filesystemUuid");
            if ("FILE_DATA".equals(text(request, "kind")) && (filesystem == null || text(value, "filesystem") == null)) {
                throw new CloudRuntimeException("Existing FILE DATA filesystem identity is unavailable");
            }
            for (String declared : declaredFilesystems.getOrDefault(uuid, Set.of())) {
                if (!Objects.equals(declared, filesystem)) {
                    throw new CloudRuntimeException("Declared file backing filesystem differs from the actual guest identity");
                }
            }
            volumes.add(value.deepCopy());
        }
        JsonObject result = new JsonObject();
        result.addProperty("generatedEpoch", observed.get("generatedEpoch").getAsDouble());
        result.add("bootId", observed.get("bootId"));
        result.add("volumes", volumes);
        return result;
    }

    public static JsonObject volume(JsonObject manifest, String uuid) {
        for (JsonElement value : manifest.getAsJsonArray("volumes")) {
            if (uuid.equals(text(value.getAsJsonObject(), "volumeUuid"))) return value.getAsJsonObject();
        }
        throw new CloudRuntimeException("DATA disk is outside the frozen ROOT manifest");
    }

    public static void requireSame(JsonObject expected, JsonObject observed) {
        if (expected.getAsJsonArray("volumes").size() != observed.getAsJsonArray("volumes").size()) {
            throw new CloudRuntimeException("Attached DATA disk coverage changed");
        }
        for (JsonElement entry : expected.getAsJsonArray("volumes")) {
            JsonObject before = entry.getAsJsonObject();
            JsonObject after = volume(observed, text(before, "volumeUuid"));
            for (String key : List.of("kind", "serial", "wwn", "sizeBytes")) {
                if (!Objects.equals(before.get(key), after.get(key))) {
                    throw new CloudRuntimeException("Stable DATA disk identity changed across ROOT replacement");
                }
            }
            if (!"BLOCK_RAW".equals(text(before, "kind"))) {
                for (String key : List.of("filesystemUuid", "filesystem")) {
                    if (!Objects.equals(before.get(key), after.get(key))) {
                        throw new CloudRuntimeException("DATA filesystem identity changed across ROOT replacement");
                    }
                }
            }
        }
    }

    private static String text(JsonObject value, String key) {
        return value.has(key) && !value.get(key).isJsonNull() && !value.get(key).getAsString().isBlank()
                ? value.get(key).getAsString() : null;
    }
}
