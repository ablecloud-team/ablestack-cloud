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

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

/** A read-only empty-runtime witness is not a verified native generation. */
public final class StorageConfigInitialBaseline {
    public static final String FEATURE = "STORAGE_CLONE_INITIAL_BASELINE_V1";
    private StorageConfigInitialBaseline() { }

    public static void requireEmptyCanonical(JsonObject desired) {
        if (desired == null || !desired.keySet().equals(StorageRenderedDesiredState.PATHS)) fail();
        for (String path : StorageRenderedDesiredState.PATHS) {
            JsonElement value = desired.get(path);
            if ("sharedfs-network.json".equals(path)) {
                if (!value.isJsonNull() && !value.isJsonObject()) fail();
            } else if (!value.isJsonNull() && !(value.isJsonObject() && value.getAsJsonObject().size() == 0)) fail();
        }
    }

    public static JsonObject stableData(JsonObject manifest) {
        JsonObject result = new JsonObject();result.add("bootId", manifest.get("bootId").deepCopy());
        JsonObject volumes = new JsonObject();
        for (JsonElement value : manifest.getAsJsonArray("volumes")) {
            JsonObject row = value.getAsJsonObject(), stable = new JsonObject();
            String id = uuid(text(row, "volumeUuid"));
            for (String field : new String[] {"kind", "serial", "wwn", "sizeBytes", "filesystem", "filesystemUuid", "observedDevicePath", "mounts"}) {
                stable.add(field, nullable(row, field).deepCopy());
            }
            if (volumes.has(id)) fail();volumes.add(id, stable);
        }
        result.add("volumes", volumes);return result;
    }

    public static void requireWitness(JsonObject reply, JsonObject request, JsonObject data, long nowMillis) {
        try {
            for (String field : new String[] {"success", "readOnly", "cloneInitialBaselineV1Supported", "globalRuntimeEmpty", "observationComplete"})
                if (!flag(reply, field)) fail();
            if (flag(reply, "initialSourceVerified") || !reply.has("generatedEpoch")
                    || !reply.get("generatedEpoch").isJsonPrimitive() || !reply.get("generatedEpoch").getAsJsonPrimitive().isNumber()
                    || !Double.isFinite(reply.get("generatedEpoch").getAsDouble())
                    || Math.abs(nowMillis / 1000.0 - reply.get("generatedEpoch").getAsDouble()) > 30) fail();
            JsonObject scope = new JsonObject();
            for (String field : new String[] {"instanceUuid", "operationUuid", "revision"}) scope.add(field, request.get(field).deepCopy());
            if (!scope.equals(reply.get("scope")) || !request.get("expectedBootId").equals(reply.get("bootId"))
                    || !request.get("expectedConfigurationSha256").equals(reply.get("configurationSha256"))
                    || !request.get("expectedCurrentGeneration").equals(reply.get("generation"))
                    || !request.get("expectedPendingOperationUuid").equals(reply.get("pendingOperationUuid"))
                    || !data.get("bootId").equals(reply.get("bootId"))) fail();
            JsonObject generation = request.getAsJsonObject("expectedCurrentGeneration");
            boolean pending = !request.get("expectedPendingOperationUuid").isJsonNull();
            String boundary = pending ? "OWN_PENDING" : generation.size() == 0 ? "INITIAL_IDLE" : "COMMITTED_BASELINE";
            if (!boundary.equals(text(reply, "witnessBoundary"))
                    || number(reply, "runtimeRevision") != (generation.size() == 0 ? 0 : number(generation, "revision"))
                    || !(pending ? "PENDING" : generation.size() == 0 ? "UNVERIFIED" : "IN_SYNC").equals(text(reply, "generationStatus"))) fail();
            if (pending && (!generation.entrySet().isEmpty()
                    || !request.get("operationUuid").equals(request.get("expectedPendingOperationUuid")))) fail();
            requireEmptyCanonical(reply.getAsJsonObject("configurationDesiredState"));
            JsonObject root = reply.getAsJsonObject("rootIdentity");
            String rootUuid = uuid(text(request, "rootVolumeUuid"));
            if (!rootUuid.equals(text(root, "volumeUuid")) || !serial(rootUuid, text(root, "serial"))
                    || number(root, "sizeBytes") != number(request, "rootSizeBytes")
                    || !"EXACT".equals(text(root, "mappingStatus")) || !"VOLUME_SERIAL".equals(text(root, "matchedBy"))
                    || !request.get("expectedVmUuid").equals(root.get("vmUuid"))) fail();
            JsonArray inventory = reply.getAsJsonArray("diskInventory");
            JsonObject expected = data.getAsJsonObject("volumes");
            if (inventory.size() != expected.size() + 1) fail();
            Set<String> seen = new HashSet<>();int roots = 0;
            for (JsonElement item : inventory) {
                JsonObject disk = item.getAsJsonObject();
                if (!seen.add(text(disk, "devicePath"))) fail();
                if (flag(disk, "isRoot")) {
                    roots++;
                    if (!serial(rootUuid, text(disk, "serial")) || number(disk, "sizeBytes") != number(request, "rootSizeBytes")
                            || !Objects.equals(text(root, "devicePath"), text(disk, "devicePath"))) fail();
                    continue;
                }
                String selected = null;
                for (String id : expected.keySet()) if (serial(id, text(disk, "serial"))) {
                    if (selected != null) fail();selected = id;
                }
                if (selected == null || !seen.add("volume:" + selected)) fail();
                JsonObject observed = expected.getAsJsonObject(selected);
                if (number(observed, "sizeBytes") != number(disk, "sizeBytes")
                        || !Objects.equals(text(observed, "serial"), text(disk, "serial"))
                        || !Objects.equals(text(observed, "observedDevicePath"), text(disk, "devicePath"))
                        || !nullable(observed, "filesystem").equals(nullable(disk, "filesystem"))
                        || !nullable(observed, "filesystemUuid").equals(nullable(disk, "filesystemUuid"))
                        || !mounts(observed.getAsJsonArray("mounts")).equals(mounts(disk.getAsJsonArray("mounts")))
                        || !nullable(disk, "volumeUuid").isJsonNull() && !selected.equals(text(disk, "volumeUuid"))) fail();
            }
            if (roots != 1 || seen.size() != inventory.size() + expected.size()) fail();
        } catch (RuntimeException invalid) {
            throw new CloudRuntimeException("Clone initial baseline requires its exact fresh global-empty and VM/ROOT/DATA witness", invalid);
        }
    }

    private static Set<String> mounts(JsonArray input) {
        Set<String> result = new HashSet<>();
        for (JsonElement value : input) {
            JsonObject projection = new JsonObject(), row = value.getAsJsonObject();
            for (String field : new String[] {"source", "target", "fstype", "options", "uuid"}) projection.add(field, nullable(row, field));
            if (!result.add(StorageConfigurationVolumePlan.canonical(projection))) fail();
        }
        return result;
    }
    private static JsonElement nullable(JsonObject value, String field) {return value.has(field) ? value.get(field) : JsonNull.INSTANCE;}
    private static String text(JsonObject value, String field) {
        JsonElement item = value.get(field);
        if (item == null || !item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString() || item.getAsString().isBlank()) fail();
        return item.getAsString();
    }
    private static boolean flag(JsonObject value, String field) {
        JsonElement item = value.get(field);
        if (item == null || !item.isJsonPrimitive() || !item.getAsJsonPrimitive().isBoolean()) fail();
        return item.getAsBoolean();
    }
    private static long number(JsonObject value, String field) {
        JsonElement item = value.get(field);
        if (item == null || !item.isJsonPrimitive() || !item.getAsJsonPrimitive().isNumber() || !item.getAsString().matches("[0-9]+")) fail();
        return item.getAsBigDecimal().longValueExact();
    }
    private static String uuid(String value) {if (!java.util.UUID.fromString(value).toString().equals(value)) fail();return value;}
    private static boolean serial(String id, String actual) {String canonical = uuid(id).replace("-", "");return canonical.equals(actual) || canonical.substring(0, 20).equals(actual);}
    private static void fail() {throw new CloudRuntimeException("Clone initial baseline identity or empty-source evidence differs");}
}
