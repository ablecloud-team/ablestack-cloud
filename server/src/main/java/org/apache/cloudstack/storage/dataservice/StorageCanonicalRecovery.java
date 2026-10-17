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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** File presence is exact; accepted resource transitions have the same applied meaning. */
final class StorageCanonicalRecovery {
    private StorageCanonicalRecovery() { }

    static String protocolPath(StorageServiceInstance.Protocol protocol) {
        switch (protocol) {
            case NFS: return "desired-state/nfs-export-apply.json";
            case SMB: return "desired-state/smb-share-apply.json";
            case ISCSI: return "iscsi-targets.json";
            default: return "nvmeof-subsystems.json";
        }
    }

    static boolean emptyProtocolFile(StorageServiceInstance.Protocol protocol, JsonElement value) {
        if (value == null || !value.isJsonObject()) return false;
        String collection = protocol == StorageServiceInstance.Protocol.NFS ? "exports"
                : protocol == StorageServiceInstance.Protocol.SMB ? "shares"
                : protocol == StorageServiceInstance.Protocol.ISCSI ? "targets" : "subsystems";
        JsonElement items = value.getAsJsonObject().get(collection);
        return items != null && items.isJsonArray() && items.getAsJsonArray().size() == 0;
    }

    static void requireSameMeaning(JsonObject source, JsonObject observed) {
        if (source == null || observed == null || !normalize(source).equals(normalize(observed))) {
            throw new CloudRuntimeException("Reconciled runtime declarative semantics differ from the verified recovery source");
        }
    }

    /** A successful opaque SOURCE replay may rediscover a BLOCK device under a new kernel name. */
    static List<JsonObject> requireNvmeSourceRebind(JsonObject source, JsonObject observed, JsonObject inventory) {
        try {
            if (!source.keySet().equals(StorageRenderedDesiredState.PATHS) || !observed.keySet().equals(source.keySet())
                    || !flag(inventory.getAsJsonObject("nvmeofRuntime"), "listening"))
                throw new IllegalArgumentException();
            JsonObject corrected = observed.deepCopy();
            JsonArray before = source.getAsJsonObject("nvmeof-subsystems.json").getAsJsonArray("subsystems");
            JsonArray after = corrected.getAsJsonObject("nvmeof-subsystems.json").getAsJsonArray("subsystems");
            JsonArray live = inventory.getAsJsonObject("nvmeofSubsystems").getAsJsonArray("subsystems");
            if (before.size() != after.size()) throw new IllegalArgumentException();
            List<JsonObject> rebound = new ArrayList<>();
            Set<String> identities = new HashSet<>();
            for (int i = 0; i < before.size(); i++) {
                JsonObject oldSubsystem = before.get(i).getAsJsonObject(), subsystem = after.get(i).getAsJsonObject();
                JsonArray oldNamespaces = oldSubsystem.getAsJsonArray("namespaces"), namespaces = subsystem.getAsJsonArray("namespaces");
                if (oldNamespaces.size() != namespaces.size()) throw new IllegalArgumentException();
                for (int j = 0; j < oldNamespaces.size(); j++) {
                    JsonObject old = oldNamespaces.get(j).getAsJsonObject(), current = namespaces.get(j).getAsJsonObject();
                    if (old.equals(current)) continue;
                    String oldPath = text(old.getAsJsonObject("runtime"), "backingPath"), newPath = text(current.getAsJsonObject("runtime"), "backingPath");
                    String uuid = text(old, "uuid"), volume = text(old, "volumeUuid");
                    String nqn = text(oldSubsystem, "targetName"), nsid = text(old, "lunOrNamespace");
                    if (oldPath.equals(newPath) || !devicePath(oldPath) || !devicePath(newPath)
                            || !canonicalUuid(uuid) || !canonicalUuid(volume) || !nsid.matches("[1-9][0-9]*")
                            || !"BLOCK".equals(text(old, "backstoreType")) || positive(old, "volumeId") <= 0
                            || !identities.add(uuid) || !identities.add(nqn + ":" + nsid))
                        throw new IllegalArgumentException();
                    long size = positive(old, "volumeSizeBytes");
                    JsonObject runtime = null;
                    for (JsonElement entry : live) {
                        JsonObject actualSubsystem = entry.getAsJsonObject();
                        if (!nqn.equals(text(actualSubsystem, "targetName"))) continue;
                        if (!flag(actualSubsystem.getAsJsonObject("runtime"), "configfsPresent")) throw new IllegalArgumentException();
                        for (JsonElement item : actualSubsystem.getAsJsonArray("namespaces")) {
                            JsonObject actualNamespace = item.getAsJsonObject();
                            if (!uuid.equals(text(actualNamespace, "uuid")) || !nsid.equals(text(actualNamespace, "lunOrNamespace"))) continue;
                            if (runtime != null) throw new IllegalArgumentException();
                            runtime = actualNamespace.getAsJsonObject("runtime");
                        }
                    }
                    String serial = runtime == null ? "" : text(runtime, "serial").replace("-", "").toLowerCase(java.util.Locale.ROOT);
                    String expected = volume.replace("-", "");
                    if (runtime == null || !flag(runtime, "configfsPresent") || !flag(runtime, "enabled")
                            || !"READY".equals(text(runtime, "runtimeState")) || !"EXACT".equals(text(runtime, "mappingStatus"))
                            || !volume.equals(text(runtime, "runtimeVolumeUuid")) || !volume.equals(text(runtime, "volumeUuid"))
                            || !nqn.equals(text(runtime, "subsystemNqn")) || !nsid.equals(text(runtime, "namespaceId"))
                            || !newPath.equals(text(runtime, "backingPath")) || !newPath.equals(text(runtime, "resolvedBackingPath"))
                            || serial.length() < 20 || !expected.startsWith(serial) || positive(runtime, "actualSizeBytes") != size)
                        throw new IllegalArgumentException();
                    current.getAsJsonObject("runtime").addProperty("backingPath", oldPath);
                    rebound.add(old.deepCopy());
                }
            }
            if (rebound.isEmpty() || !source.equals(corrected)) throw new IllegalArgumentException();
            return rebound;
        } catch (RuntimeException invalid) {
            throw new CloudRuntimeException("LOCAL SOURCE differs beyond exact serial-verified NVMe backing-path rebinding");
        }
    }

    private static String text(JsonObject object, String key) {
        JsonElement value = object == null ? null : object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException();
        return value.getAsString();
    }
    private static boolean flag(JsonObject object, String key) {
        JsonElement value = object == null ? null : object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean();
    }
    private static long positive(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                || !value.getAsString().matches("[1-9][0-9]*")) throw new IllegalArgumentException();
        return Long.parseLong(value.getAsString());
    }
    private static boolean devicePath(String value) { return value.matches("/dev/[A-Za-z0-9][A-Za-z0-9_.-]*"); }
    private static boolean canonicalUuid(String value) { return java.util.UUID.fromString(value).toString().equals(value); }

    private static JsonElement normalize(JsonElement value) {
        if (value.isJsonObject()) {
            JsonObject result = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
                JsonElement field = entry.getValue();
                if ("state".equals(entry.getKey()) && field.isJsonPrimitive() && field.getAsJsonPrimitive().isString()
                        && Set.of("Ready", "Creating", "Updating").contains(field.getAsString())) {
                    result.addProperty(entry.getKey(), "Ready");
                } else {
                    result.add(entry.getKey(), normalize(field));
                }
            }
            return result;
        }
        if (value.isJsonArray()) {
            JsonArray result = new JsonArray();
            for (JsonElement entry : value.getAsJsonArray()) result.add(normalize(entry));
            return result;
        }
        return value.deepCopy();
    }
}
