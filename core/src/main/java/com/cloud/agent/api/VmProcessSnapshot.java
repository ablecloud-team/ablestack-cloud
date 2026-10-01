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
package com.cloud.agent.api;

import java.io.StringReader;
import java.math.BigDecimal;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

/** Strict, bounded wire decoder shared by the agent and management server. */
public final class VmProcessSnapshot {
    private VmProcessSnapshot() { }
    public static Map<String, Object> authority(GetVmProcessSnapshotCommand c) {
        return Map.of("vmUuid", c.getVmUuid(), "hostUuid", c.getHostUuid(), "placementGeneration", c.getGeneration());
    }
    public static Map<String, Object> request(GetVmProcessSnapshotCommand c) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("schemaVersion", "1.0"); r.put("kind", "readRequest"); r.put("requestId", c.getRequestId());
        r.put("authority", authority(c)); r.put("operation", "process.list"); r.put("operationId", null); r.put("budgetMs", 5000);
        return r;
    }
    public static Map<String, Object> failure(GetVmProcessSnapshotCommand c, String code) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("schemaVersion", "1.0"); r.put("kind", "failure"); r.put("requestId", c.getRequestId());
        r.put("authority", authority(c));
        r.put("error", Map.of("code", code, "message", "Process snapshot unavailable", "retryMode", "READ_ONLY"));
        return r;
    }
    public static Map<String, Object> decode(String text, GetVmProcessSnapshotCommand c) throws IOException {
        if (text.getBytes(StandardCharsets.UTF_8).length > 1048576) throw new IOException("Output limit");
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setLenient(false);
            Map<String, Object> result = map(read(reader, 0));
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Trailing JSON");
            validate(result, c); return result;
        } catch (RuntimeException e) { throw new IOException("Invalid snapshot", e); }
    }
    static Object read(JsonReader r, int depth) throws IOException {
        if (depth > 12) throw new IOException("JSON depth");
        switch (r.peek()) {
            case BEGIN_OBJECT:
                Map<String, Object> object = new LinkedHashMap<>(); r.beginObject();
                while (r.hasNext()) { String key = r.nextName(); if (object.containsKey(key)) throw new IOException("Duplicate key"); object.put(key, read(r, depth + 1)); }
                r.endObject(); return object;
            case BEGIN_ARRAY:
                List<Object> array = new ArrayList<>(); r.beginArray();
                while (r.hasNext()) { if (array.size() >= 10000) throw new IOException("Array limit"); array.add(read(r, depth + 1)); }
                r.endArray(); return array;
            case STRING: return r.nextString();
            case NUMBER: return new java.math.BigDecimal(r.nextString());
            case BOOLEAN: return r.nextBoolean();
            case NULL: r.nextNull(); return null;
            default: throw new IOException("JSON token");
        }
    }
    @SuppressWarnings("unchecked") public static Map<String, Object> map(Object value) {
        if (!(value instanceof Map)) throw new IllegalArgumentException("Object required");
        return (Map<String, Object>) value;
    }
    private static void fields(Map<String, Object> m, String... names) {
        if (!m.keySet().equals(Set.of(names))) throw new IllegalArgumentException("Unexpected fields");
    }
    private static void text(Object value, int min, int max) {
        if (!(value instanceof String) || ((String) value).length() < min || ((String) value).length() > max)
            throw new IllegalArgumentException("Invalid text");
    }
    private static long number(Object value, long min, long max) {
        if (!(value instanceof Number)) throw new IllegalArgumentException("Number required");
        long n = new java.math.BigDecimal(value.toString()).longValueExact();
        if (n < min || n > max) throw new IllegalArgumentException("Numeric bound"); return n;
    }
    private static void cpuPercent(Object value) {
        if (!(value instanceof Number)) throw new IllegalArgumentException("CPU number required");
        try {
            if (new BigDecimal(value.toString()).signum() < 0) throw new IllegalArgumentException("Negative CPU");
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid CPU number", e);
        }
    }
    private static void uuid(Object value) {
        if (!(value instanceof String) || !UUID.fromString((String) value).toString().equals(value)) throw new IllegalArgumentException("UUID");
    }
    public static void validate(Map<String, Object> r, GetVmProcessSnapshotCommand c) {
        if (!"1.0".equals(r.get("schemaVersion")) || !c.getRequestId().equals(r.get("requestId")) || !authority(c).equals(r.get("authority")))
            throw new IllegalArgumentException("Authority mismatch");
        if ("failure".equals(r.get("kind"))) {
            fields(r, "schemaVersion", "kind", "requestId", "authority", "error");
            Map<String, Object> error = map(r.get("error")); fields(error, "code", "message", "retryMode");
            if (!Set.of("CHECK_FAILED", "BUSY", "STALE_AUTHORITY", "TOOLS_REQUIRED", "HOST_TOOL_MISSING", "QGA_UNREACHABLE", "OUTPUT_LIMIT", "UNSUPPORTED_VERSION").contains(error.get("code"))) throw new IllegalArgumentException("Error code");
            text(error.get("message"), 1, 512);
            if (!Set.of("READ_ONLY", "NONE").contains(error.get("retryMode"))) throw new IllegalArgumentException("Retry mode");
            // Never forward guest or host diagnostics into a public API.
            error.put("message", "Process snapshot unavailable"); return;
        }
        fields(r, "schemaVersion", "kind", "requestId", "authority", "snapshotId", "bootId", "observedAt", "expiresAt", "status", "truncated", "totalKnown", "processes");
        if (!"snapshot".equals(r.get("kind"))) throw new IllegalArgumentException("Kind"); uuid(r.get("snapshotId"));
        text(r.get("bootId"), 1, 256); String boot = (String) r.get("bootId");
        if (boot.startsWith("linux:")) uuid(boot.substring(6));
        else if (!boot.matches("windows:[0-9]{1,20}")) throw new IllegalArgumentException("Boot ID");
        Instant observed = Instant.parse((String) r.get("observedAt")), expires = Instant.parse((String) r.get("expiresAt"));
        if (!((String) r.get("observedAt")).endsWith("Z") || !((String) r.get("expiresAt")).endsWith("Z") || !expires.isAfter(observed) || expires.isAfter(observed.plusSeconds(10))) throw new IllegalArgumentException("TTL");
        if (!Set.of("OK", "PARTIAL").contains(r.get("status")) || !(r.get("truncated") instanceof Boolean) || Boolean.TRUE.equals(r.get("truncated")) && !"PARTIAL".equals(r.get("status"))) throw new IllegalArgumentException("Status");
        if (!(r.get("processes") instanceof List)) throw new IllegalArgumentException("Rows");
        List<?> rows = (List<?>) r.get("processes"); if (rows.size() > 10000) throw new IllegalArgumentException("Row limit");
        Set<Long> pids = new HashSet<>();
        for (Object value : rows) {
            Map<String, Object> row = map(value); fields(row, "identity", "ppid", "name", "owner", "state", "memoryBytes", "cpuPercent", "services", "allowedActions");
            Map<String, Object> id = map(row.get("identity")); fields(id, "vmUuid", "bootId", "pid", "startTicks");
            if (!c.getVmUuid().equals(id.get("vmUuid")) || !boot.equals(id.get("bootId")) || !pids.add(number(id.get("pid"), 1, 4294967295L))) throw new IllegalArgumentException("Identity");
            if (!(id.get("startTicks") instanceof String) || !((String) id.get("startTicks")).matches("[0-9]{1,20}")) throw new IllegalArgumentException("Ticks");
            text(row.get("name"), 1, 256); text(row.get("state"), 1, 256);
            if (row.get("owner") != null) text(row.get("owner"), 0, 256);
            if (row.get("ppid") != null) number(row.get("ppid"), 0, 4294967295L);
            if (row.get("memoryBytes") != null) number(row.get("memoryBytes"), 0, 9007199254740991L);
            if (row.get("cpuPercent") != null) cpuPercent(row.get("cpuPercent"));
            if (!List.of().equals(row.get("allowedActions"))) throw new IllegalArgumentException("Unimplemented actions");
            if (!(row.get("services") instanceof List) || ((List<?>) row.get("services")).size() > 128) throw new IllegalArgumentException("Services");
            for (Object service : (List<?>) row.get("services")) {
                Map<String, Object> s = map(service); fields(s, "manager", "name", "configurationHash");
                if (!Set.of("systemd", "scm").contains(s.get("manager"))) throw new IllegalArgumentException("Manager");
                text(s.get("name"), 1, 256);
                if (!(s.get("configurationHash") instanceof String) || !((String) s.get("configurationHash")).matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Hash");
            }
        }
        if (r.get("totalKnown") != null) number(r.get("totalKnown"), rows.size(), 9007199254740991L);
        if ("OK".equals(r.get("status")) && (r.get("totalKnown") == null || number(r.get("totalKnown"), 0, 9007199254740991L) != rows.size())) throw new IllegalArgumentException("Complete count mismatch");
    }
}
