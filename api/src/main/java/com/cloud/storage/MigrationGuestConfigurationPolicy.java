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

import com.cloud.exception.InvalidParameterValueException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.List;
import java.util.UUID;

public final class MigrationGuestConfigurationPolicy {
    public static final String PREFIX = "migration.guest.config.";
    public static final String PLAN = PREFIX + "plan";
    public static final String BINDING = PREFIX + "binding";
    public static final String EVIDENCE = PREFIX + "evidence";

    private MigrationGuestConfigurationPolicy() {}

    /** v2 includes explicit null intent (automatic metric); v1 bytes remain unchanged. */
    public static String canonical(com.google.gson.JsonElement value) {
        if (value.isJsonObject() && value.getAsJsonObject().has("version")
                && value.getAsJsonObject().get("version").getAsInt() == 2)
            return canonicalV2(value);
        return MigrationDiskControllerPolicy.canonical(value);
    }

    private static String canonicalV2(com.google.gson.JsonElement value) {
        var gson = new com.google.gson.GsonBuilder().disableHtmlEscaping().serializeNulls().create();
        if (value.isJsonObject()) {
            var out = new JsonObject();
            for (var key : new java.util.TreeSet<>(value.getAsJsonObject().keySet()))
                out.add(key, JsonParser.parseString(canonicalV2(value.getAsJsonObject().get(key))));
            return gson.toJson(out);
        }
        if (value.isJsonArray()) {
            var out = new com.google.gson.JsonArray();
            for (var item : value.getAsJsonArray()) out.add(JsonParser.parseString(canonicalV2(item)));
            return gson.toJson(out);
        }
        return gson.toJson(value);
    }

    public static String hash(JsonObject unsigned) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonical(unsigned).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }

    private static boolean frozenHashMatches(JsonObject value) {
        var unsigned = value.deepCopy();
        String expected = unsigned.remove("hash").getAsString();
        return expected.equals(hash(unsigned));
    }

    /** Recover only nulls lost by the old writer, and only with the original hash. */
    private static JsonObject restoreV2NullEncoding(JsonObject value) {
        if (frozenHashMatches(value)) return value;
        var restored = value.deepCopy();
        for (var n : restored.getAsJsonObject("network").getAsJsonArray("interfaces")) {
            var metric = n.getAsJsonObject().getAsJsonObject("interface_metric");
            if (metric.get("automatic").getAsBoolean() && !metric.has("value"))
                metric.add("value", com.google.gson.JsonNull.INSTANCE);
        }
        if (frozenHashMatches(restored)) return restored;
        for (String key : List.of("source_boot_id", "source_collected_at"))
            if (!restored.has(key)) restored.add(key, com.google.gson.JsonNull.INSTANCE);
        return frozenHashMatches(restored) ? restored : value;
    }

    public static JsonObject parse(String text) {
        try {
            if (text == null || text.length() > 65536) throw new IllegalArgumentException();
            JsonObject plan = JsonParser.parseString(text).getAsJsonObject();
            if (!List.of(1, 2).contains(plan.get("version").getAsInt())
                    || !"preserve".equals(plan.get("policy").getAsString())
                    || !List.of("linux", "windows").contains(plan.get("os").getAsString())
                    || plan.get("helper_version").getAsInt() != plan.get("version").getAsInt())
                throw new IllegalArgumentException();
            if (plan.get("version").getAsInt() == 2) {
                plan = restoreV2NullEncoding(plan);
                if (!"windows".equals(plan.get("os").getAsString())
                        || !"20348".equals(plan.get("os_version").getAsString())
                        || !plan.getAsJsonArray("required_features").toString().equals("[\"windows-network-ipv4-v1\"]")) throw new IllegalArgumentException();
                for (String key : List.of("network_reader_sha256", "network_helper_sha256"))
                    if (!plan.get(key).getAsString().matches("[a-f0-9]{64}")) throw new IllegalArgumentException();
                MigrationWindowsNetworkPolicy.validate(plan.getAsJsonObject("network"));
            } else if ("windows".equals(plan.get("os").getAsString()) && (plan.has("network") || plan.has("required_features"))) throw new IllegalArgumentException();
            UUID.fromString(plan.get("operation_id").getAsString());
            UUID.fromString(plan.get("source_vm_uuid").getAsString());
            if (!plan.has("helper_sha256")
                    || !plan.get("helper_sha256").getAsString().matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException();
            if (!plan.get("controller_plan_hash").getAsString().matches("[a-f0-9]{64}")
                    || !plan.has("root_source_disk_id")
                    || plan.get("root_source_disk_id").getAsString().isBlank()
                    || plan.get("root_identity").getAsString().isBlank()
                    || plan.get("source_capture_id").getAsString().isBlank())
                throw new IllegalArgumentException();
            JsonObject unsigned = plan.deepCopy();
            String hash = unsigned.remove("hash").getAsString();
            if (!hash.matches("[a-f0-9]{64}")
                    || !hash.equals(hash(unsigned)))
                throw new IllegalArgumentException();
            if ("linux".equals(plan.get("os").getAsString())) {
                var seen = new java.util.HashSet<String>();
                for (var nic : plan.getAsJsonObject("network").getAsJsonArray("interfaces")) {
                    var n = nic.getAsJsonObject();
                    String mac = n.get("mac").getAsString();
                    if (!mac.matches("([a-f0-9]{2}:){5}[a-f0-9]{2}")
                            || !seen.add(mac)
                            || !n.get("ifname").getAsString().matches("[A-Za-z0-9_.-]{1,15}"))
                        throw new IllegalArgumentException();
                }
                if (seen.isEmpty()) throw new IllegalArgumentException();
            } else {
                var seen = new java.util.HashSet<String>();
                var paths = new java.util.HashSet<String>();
                for (var value : plan.getAsJsonObject("windows").getAsJsonArray("disks")) {
                    var disk = value.getAsJsonObject();
                    if (!seen.add(disk.get("source_disk_id").getAsString())
                            || disk.get("identity").getAsString().isBlank()
                            || disk.get("size_bytes").getAsLong() <= 0
                            || !disk.get("online").getAsJsonPrimitive().isBoolean()
                            || !disk.get("read_only").getAsJsonPrimitive().isBoolean())
                        throw new IllegalArgumentException();
                    for (var part : disk.getAsJsonArray("partitions"))
                        for (var path : part.getAsJsonObject().getAsJsonArray("access_paths")) {
                            String p = path.getAsString();
                            if (p.length() != 3
                                    || p.charAt(0) < 'D'
                                    || p.charAt(0) > 'Z'
                                    || p.charAt(1) != ':'
                                    || p.charAt(2) != 92
                                    || !paths.add(p)) throw new IllegalArgumentException();
                        }
                }
            }
            return plan;
        } catch (RuntimeException error) {
            throw new InvalidParameterValueException("GUEST_CONFIG_PLAN_INVALID");
        }
    }

    public static String directory(JsonObject plan) {
        return "linux".equals(plan.get("os").getAsString())
                ? "/var/lib/ablestack-v2k/guest-config/"
                : "C:/ProgramData/ABLECLOUD/V2K/GuestConfig/";
    }
}
