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

import java.util.HashSet;
import java.util.Set;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Compares observed protocol access without accepting desired-state cache as proof. */
public final class StorageRecoveryObservation {
    private StorageRecoveryObservation() { }

    public static void requireFresh(JsonObject inventory, double now) {
        if (!inventory.has("generatedEpoch")) throw new CloudRuntimeException("Runtime observation has no timestamp");
        double age = now - inventory.get("generatedEpoch").getAsDouble();
        if (!Double.isFinite(age) || age < -5 || age > 45) throw new CloudRuntimeException("Runtime observation is stale");
    }

    private static String text(JsonObject object, String key, String fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : fallback;
    }
    private static boolean flag(JsonObject object, String key, boolean fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsBoolean() : fallback;
    }
    public static JsonObject nfsClient(String principal, boolean writable, JsonObject share, JsonObject acl) {
        JsonObject merged = share.deepCopy();
        for (java.util.Map.Entry<String, JsonElement> item : acl.entrySet()) merged.add(item.getKey(), item.getValue());
        JsonObject result = new JsonObject();
        result.addProperty("clients", "0.0.0.0/0".equals(principal) || "::/0".equals(principal) ? "*" : principal);
        result.addProperty("access", writable && !flag(share, "readOnly", false) ? "RW" : "RO");
        result.addProperty("squash", flag(merged, "allSquash", false) ? "All_Squash" :
                (flag(merged, "rootSquash", true) ? "Root_Squash" : "No_Root_Squash"));
        result.addProperty("anonUid", Integer.parseInt(text(merged, "anonUid", "65534")));
        result.addProperty("anonGid", Integer.parseInt(text(merged, "anonGid", "65534")));
        return result;
    }
    public static void requireNfsClients(JsonArray expected, JsonObject observed) {
        if (!observed.has("clients") || !values(expected).equals(values(observed.getAsJsonArray("clients")))) {
            throw new CloudRuntimeException("NFS client ACL or squash policy differs from desired state");
        }
    }
    private static Set<JsonElement> values(JsonArray values) {
        Set<JsonElement> result = new HashSet<>();
        for (JsonElement value : values) result.add(value);
        return result;
    }
    public static Set<String> strings(JsonArray values) {
        Set<String> result = new HashSet<>();
        for (JsonElement value : values) result.add(value.getAsString());
        return result;
    }
    public static void requireNvmeHost(JsonObject config, JsonObject observation) {
        if (observation == null || flag(config, "dhChapEnabled", false) != flag(observation, "dhChapConfigured", false)
                || flag(config, "dhChapCtrlEnabled", false) != flag(observation, "dhChapCtrlConfigured", false)) {
            throw new CloudRuntimeException("NVMe-oF host authentication differs from desired state");
        }
    }
    public static void requireNamespace(JsonObject config, JsonObject observed, String volumeUuid) {
        if (!flag(observed, "configfsPresent", false) || !flag(observed, "enabled", false)
                || !"EXACT".equals(text(observed, "mappingStatus", "")) || !volumeUuid.equals(text(observed, "runtimeVolumeUuid", ""))
                || !observed.has("actualSizeBytes") || observed.get("actualSizeBytes").getAsLong() <= 0
                || (config.has("namespaceSizeBytes") && config.get("namespaceSizeBytes").getAsLong() != observed.get("actualSizeBytes").getAsLong())) {
            throw new CloudRuntimeException("NVMe-oF namespace identity or effective size differs from desired state");
        }
    }
}
