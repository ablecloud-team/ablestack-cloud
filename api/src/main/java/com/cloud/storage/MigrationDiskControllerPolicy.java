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
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Immutable operator intent. Physical execution evidence is produced only by Agents. */
public final class MigrationDiskControllerPolicy {
    public static final String PLAN = "migration.disk.controller.plan";
    public static final String BINDINGS = "migration.disk.controller.bindings";
    public static final String EVIDENCE = "migration.disk.controller.evidence";
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private MigrationDiskControllerPolicy() {}

    public static String canonical(JsonElement value) {
        if (value.isJsonObject()) {
            JsonObject out = new JsonObject();
            Map<String, JsonElement> sorted = new TreeMap<>();
            for (var e : value.getAsJsonObject().entrySet()) sorted.put(e.getKey(), e.getValue());
            sorted.forEach((k, v) -> out.add(k, JsonParser.parseString(canonical(v))));
            return GSON.toJson(out);
        }
        if (value.isJsonArray()) {
            JsonArray out = new JsonArray();
            for (var v : value.getAsJsonArray()) out.add(JsonParser.parseString(canonical(v)));
            return GSON.toJson(out);
        }
        return GSON.toJson(value);
    }

    public static String hash(JsonElement value) {
        try {
            var bytes =
                    MessageDigest.getInstance("SHA-256")
                            .digest(canonical(value).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : bytes) out.append(String.format("%02x", b & 255));
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static JsonObject parse(String text) {
        try {
            if (text == null || text.length() > 65536) throw new IllegalArgumentException();
            JsonObject p = JsonParser.parseString(text).getAsJsonObject();
            if (p.get("version").getAsInt() != 1
                    || !"q35".equals(p.get("machine").getAsString())
                    || !List.of("bios", "efi").contains(p.get("firmware").getAsString())
                    || !List.of("auto", "sata", "virtio-scsi", "preserve")
                            .contains(p.get("policy").getAsString()))
                throw new IllegalArgumentException();
            String expected = p.get("hash").getAsString();
            JsonObject unsigned = p.deepCopy();
            unsigned.remove("hash");
            if (!expected.matches("[a-f0-9]{64}") || !expected.equals(hash(unsigned)))
                throw new IllegalArgumentException();
            JsonArray disks = p.getAsJsonArray("disks");
            if (disks.size() == 0 || disks.size() > 256) throw new IllegalArgumentException();
            var ids = new java.util.HashSet<String>();
            var keys = new java.util.HashSet<String>();
            String dataBus = null;
            for (int i = 0; i < disks.size(); i++) {
                JsonObject d = disks.get(i).getAsJsonObject(), t = d.getAsJsonObject("target");
                String bus = t.get("bus").getAsString();
                if (d.get("index").getAsInt() != i
                        || !d.get("role").getAsString().equals(i == 0 ? "root" : "data")
                        || !List.of("ide", "sata", "scsi").contains(d.get("kind").getAsString())
                        || d.get("size_bytes").getAsLong() <= 0
                        || !ids.add(d.get("disk_id").getAsString())
                        || !keys.add(d.get("device_key").getAsString())
                        || !List.of("sata", "scsi").contains(bus)
                        || !t.get("device").getAsString().equals("sd" + letter(i))
                        || !t.get("model")
                                .getAsString()
                                .equals(bus.equals("scsi") ? "virtio-scsi" : "ahci"))
                    throw new IllegalArgumentException();
                if (i > 0) {
                    if (dataBus != null && !dataBus.equals(bus))
                        throw new IllegalArgumentException();
                    dataBus = bus;
                }
                if (p.get("policy").getAsString().equals("sata") && !bus.equals("sata"))
                    throw new IllegalArgumentException();
                if (p.get("policy").getAsString().equals("virtio-scsi") && !bus.equals("scsi"))
                    throw new IllegalArgumentException();
                if (p.get("policy").getAsString().equals("preserve")
                        && (!d.get("kind").getAsString().equals("sata") || !bus.equals("sata")))
                    throw new IllegalArgumentException();
            }
            return p;
        } catch (RuntimeException e) {
            throw new InvalidParameterValueException(
                    "DISK_CONTROLLER_POLICY_INVALID: malformed, changed or unsupported migration plan");
        }
    }

    public static String letter(int index) {
        String s = "";
        for (int n = index + 1; n > 0; n = (n - 1) / 26) s = (char) ('a' + (n - 1) % 26) + s;
        return s;
    }

    public static String read(Map<String, String> details, String key) {
        if (details == null || !details.containsKey(key)) return null;
        String marker = details.get(key);
        if (!marker.startsWith("chunks:")) return marker;
        int count = Integer.parseInt(marker.substring(7));
        if (count < 1 || count > 32)
            throw new InvalidParameterValueException("DISK_CONTROLLER_POLICY_CHUNKS_INVALID");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) {
            String part = details.get(key + "." + i);
            if (part == null)
                throw new InvalidParameterValueException("DISK_CONTROLLER_POLICY_CHUNK_MISSING");
            out.append(part);
        }
        return out.toString();
    }

    public static Map<String, String> chunks(String key, String value) {
        if (value.length() > 65536)
            throw new InvalidParameterValueException("DISK_CONTROLLER_POLICY_TOO_LARGE");
        Map<String, String> out = new LinkedHashMap<>();
        int count = (value.length() + 3999) / 4000;
        out.put(key, "chunks:" + count);
        for (int i = 0; i < count; i++)
            out.put(
                    key + "." + i,
                    value.substring(i * 4000, Math.min(value.length(), (i + 1) * 4000)));
        return out;
    }

    public static JsonArray bindingArray(String text) {
        return JsonParser.parseString(text).getAsJsonArray();
    }
}
