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

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Internal C1 envelope. Host authority is removed only at the API boundary. */
public final class VmProcessCapability {
    public static final List<String> RPCS = Collections.unmodifiableList(Arrays.asList(
            "guest-exec", "guest-exec-status", "guest-file-open", "guest-file-close",
            "guest-file-read", "guest-file-write", "guest-file-seek", "guest-file-flush"));
    private VmProcessCapability() { }
    public static Map<String, Object> empty(GetVmProcessCapabilitiesCommand command) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", "1.0"); result.put("kind", "capability");
        result.put("requestId", command.getRequestId());
        Map<String, String> authority = new LinkedHashMap<>();
        authority.put("vmUuid", command.getVmUuid()); authority.put("hostUuid", command.getHostUuid());
        authority.put("placementGeneration", command.getGeneration()); result.put("authority", authority);
        result.put("observedAt", Instant.now().toString());
        result.put("os", new LinkedHashMap<>(Map.of("family", "unknown", "id", "unknown", "version", "unknown", "arch", "unsupported")));
        result.put("qgaVersion", null); result.put("hostToolsVersion", null); result.put("guestAdapterVersion", null);
        result.put("supportedSchemaVersions", Collections.emptyList());
        Map<String, String> rpcs = new LinkedHashMap<>(); RPCS.forEach(rpc -> rpcs.put(rpc, "UNKNOWN"));
        result.put("rpcs", rpcs); result.put("allowedActions", Collections.emptyList());
        return fail(result, "CHECK_FAILED", "Capability observation unavailable");
    }
    public static Map<String, Object> fail(Map<String, Object> result, String code, String message) {
        result.put("readiness", code);
        result.put("allowedActions", Collections.emptyList());
        result.put("error", Map.of("code", code, "message", message, "retryMode", "READ_ONLY"));
        return result;
    }
    /** An Agent success flag alone cannot certify readiness. Check the complete current authority and invariants. */
    public static boolean valid(Map<String, Object> result, GetVmProcessCapabilitiesCommand command) {
        try {
            if (!"1.0".equals(result.get("schemaVersion")) || !"capability".equals(result.get("kind"))
                    || !command.getRequestId().equals(result.get("requestId"))
                    || !empty(command).get("authority").equals(result.get("authority"))) return false;
            Map<String, Object> os = VmProcessSnapshot.map(result.get("os"));
            for (String field : List.of("family", "id", "version", "arch")) if (!text(os.get(field))) return false;
            String state = String.valueOf(result.get("readiness"));
            if (!List.of("READY", "RPC_DISABLED", "RPC_UNSUPPORTED", "QGA_UNREACHABLE", "TOOLS_REQUIRED", "HOST_TOOL_MISSING",
                    "UNSUPPORTED_OS", "UNSUPPORTED_HYPERVISOR", "VM_NOT_RUNNING", "CHECK_FAILED").contains(state)) return false;
            Map<String, Object> rpcs = VmProcessSnapshot.map(result.get("rpcs"));
            if (!rpcs.keySet().equals(new java.util.HashSet<>(RPCS))
                    || rpcs.values().stream().anyMatch(v -> !List.of("ENABLED", "DISABLED", "UNSUPPORTED", "UNKNOWN").contains(v))) return false;
            if (!(result.get("allowedActions") instanceof List) || !(result.get("supportedSchemaVersions") instanceof List)) return false;
            List<?> actions = (List<?>) result.get("allowedActions"); List<?> versions = (List<?>) result.get("supportedSchemaVersions");
            if (actions.size() > 4 || actions.size() != new java.util.HashSet<>(actions).size()
                    || actions.stream().anyMatch(a -> !List.of("process.list", "process.terminate", "process.kill", "service.restart").contains(a))
                    || versions.size() > 16 || versions.size() != new java.util.HashSet<>(versions).size()
                    || versions.stream().anyMatch(v -> !(v instanceof String) || !((String) v).matches("[0-9]+\\.[0-9]+"))) return false;
            if ("READY".equals(state)) return rpcs.values().stream().allMatch("ENABLED"::equals)
                    && versions.contains("1.0") && actions.contains("process.list") && text(result.get("qgaVersion"))
                    && text(result.get("hostToolsVersion")) && text(result.get("guestAdapterVersion")) && result.get("error") == null
                    && List.of("linux", "windows").contains(os.get("family")) && "x86_64".equals(os.get("arch"))
                    && (!"windows".equals(os.get("family")) || !actions.contains("process.terminate"));
            return actions.isEmpty() && state.equals(VmProcessSnapshot.map(result.get("error")).get("code"));
        } catch (RuntimeException e) { return false; }
    }
    private static boolean text(Object value) {
        return value instanceof String && !((String) value).isEmpty() && ((String) value).length() <= 256;
    }
}
