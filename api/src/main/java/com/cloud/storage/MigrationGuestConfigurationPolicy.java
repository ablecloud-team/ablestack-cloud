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

    public static JsonObject parse(String text) {
        try {
            if (text == null || text.length() > 65536) throw new IllegalArgumentException();
            JsonObject plan = JsonParser.parseString(text).getAsJsonObject();
            if (plan.get("version").getAsInt() != 1
                    || !"preserve".equals(plan.get("policy").getAsString())
                    || !List.of("linux", "windows").contains(plan.get("os").getAsString())
                    || plan.get("helper_version").getAsInt() != 1)
                throw new IllegalArgumentException();
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
                    || !hash.equals(MigrationDiskControllerPolicy.hash(unsigned)))
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
