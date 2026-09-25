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
package com.cloud.hypervisor.kvm.resource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Semaphore;
import com.cloud.agent.api.GetVmProcessCapabilitiesCommand;
import com.cloud.agent.api.VmProcessCapability;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Read-only readiness. No guest-exec, network collector, or installer is invoked. */
public class VmProcessCapabilityProbe {
    private static final Semaphore ADMISSION = new Semaphore(8);
    public Map<String, Object> collect(GetVmProcessCapabilitiesCommand command) {
        Map<String, Object> unknown = VmProcessCapability.empty(command);
        if (!ADMISSION.tryAcquire()) return VmProcessCapability.fail(unknown, "CHECK_FAILED", "Host observation capacity exhausted");
        try {
            Map<String, Object> result = KvmVmOperationGuard.collect(null, command.getVmName(), () -> {
                if (!command.getVmUuid().equals(KvmVmOperationGuard.probe(1000, "virsh", "-c", "qemu:///system", "domuuid", command.getVmName()).trim()))
                    return VmProcessCapability.fail(unknown, "CHECK_FAILED", "VM domain identity changed");
                return observe(command);
            });
            return result == null ? VmProcessCapability.fail(unknown, "CHECK_FAILED", "VM operation active, state unknown, or observation budget exceeded") : result;
        } finally { ADMISSION.release(); }
    }
    protected boolean hostToolPresent() {
        return Files.isExecutable(Path.of("/usr/bin/vm_exec")) || Files.isExecutable(Path.of("/usr/local/bin/vm_exec"));
    }
    protected String hostToolsVersion() {
        // Diagnostic only. Package metadata must never imply protocol compatibility.
        try {
            if (Files.isExecutable(Path.of("/usr/bin/aspkg")))
                return KvmVmOperationGuard.probe(500, "/usr/bin/aspkg", "-q", "--qf", "%{VERSION}", "ablestack-qemu-exec-tools").trim();
            if (Files.isExecutable(Path.of("/usr/bin/dpkg-query")))
                return KvmVmOperationGuard.probe(500, "/usr/bin/dpkg-query", "-W", "-f=${Version}", "ablestack-qemu-exec-tools").trim();
            return null;
        }
        catch (Exception ignored) { return null; }
    }
    protected String guest(String uuid, String operation) throws Exception {
        return KvmVmOperationGuard.probe(1500, "virsh", "-c", "qemu:///system", "qemu-agent-command",
                uuid, "--timeout", "2", "{\"execute\":\"" + operation + "\"}");
    }
    public Map<String, Object> observe(GetVmProcessCapabilitiesCommand command) {
        Map<String, Object> result = VmProcessCapability.empty(command);
        boolean present = hostToolPresent();
        result.put("hostToolsVersion", present ? hostToolsVersion() : null);
        String info;
        try { info = guest(command.getVmUuid(), "guest-info"); }
        catch (Exception e) { return VmProcessCapability.fail(result, present ? "QGA_UNREACHABLE" : "HOST_TOOL_MISSING", "QGA observation transport unavailable"); }
        try {
            Map<String, String> rpcs = parseRpcs(info);
            result.put("rpcs", rpcs);
            result.put("qgaVersion", text(reply(info), "version"));
            if (!present) return VmProcessCapability.fail(result, "HOST_TOOL_MISSING", "Host vm_exec executable is missing");
            String rpcState = rpcs.containsValue("UNSUPPORTED") ? "RPC_UNSUPPORTED" : rpcs.containsValue("DISABLED") ? "RPC_DISABLED" : null;
            try { result.put("os", parseOs(guest(command.getVmUuid(), "guest-get-osinfo"))); }
            catch (Exception e) {
                return VmProcessCapability.fail(result, rpcState == null ? "CHECK_FAILED" : rpcState, "Guest OS or RPC observation incomplete");
            }
            if (rpcState != null) return VmProcessCapability.fail(result, rpcState, "Required guest RPCs are not enabled");
            @SuppressWarnings("unchecked") Map<String, String> os = (Map<String, String>) result.get("os");
            if (!supportedOs(os)) return VmProcessCapability.fail(result, "UNSUPPORTED_OS", "Guest OS is outside the C1 support matrix");
            // Q4/Q5/Q6 own adapter discovery and harmless execution/file probes.
            return VmProcessCapability.fail(result, "TOOLS_REQUIRED", "Process adapter compatibility and probes have not been verified");
        } catch (RuntimeException e) {
            return VmProcessCapability.fail(result, present ? "CHECK_FAILED" : "HOST_TOOL_MISSING", "Invalid guest-info response");
        }
    }
    static JsonObject reply(String text) {
        if (text == null || text.length() > 1024 * 1024) throw new IllegalArgumentException("Invalid response size");
        JsonObject object = JsonParser.parseString(text).getAsJsonObject();
        if (object.has("error") || !object.has("return") || !object.get("return").isJsonObject()) throw new IllegalArgumentException("Invalid reply");
        return object.getAsJsonObject("return");
    }
    static String text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                || value.getAsString().isEmpty() || value.getAsString().length() > 256) throw new IllegalArgumentException("Invalid string");
        return value.getAsString();
    }
    public static Map<String, String> parseRpcs(String text) {
        JsonObject data = reply(text);
        text(data, "version");
        if (!data.has("supported_commands") || !data.get("supported_commands").isJsonArray()) throw new IllegalArgumentException("Missing RPC list");
        Map<String, String> result = new LinkedHashMap<>();
        VmProcessCapability.RPCS.forEach(rpc -> result.put(rpc, "UNSUPPORTED"));
        Set<String> names = new HashSet<>();
        for (JsonElement entry : data.getAsJsonArray("supported_commands")) {
            JsonObject rpc = entry.getAsJsonObject(); String name = text(rpc, "name");
            if (!names.add(name) || !rpc.has("enabled") || !rpc.get("enabled").isJsonPrimitive()
                    || !rpc.getAsJsonPrimitive("enabled").isBoolean()) throw new IllegalArgumentException("Invalid RPC entry");
            if (result.containsKey(name)) result.put(name, rpc.get("enabled").getAsBoolean() ? "ENABLED" : "DISABLED");
        }
        return result;
    }
    public static Map<String, String> parseOs(String value) {
        JsonObject data = reply(value); String id = text(data, "id").toLowerCase(java.util.Locale.ROOT);
        String family = "mswindows".equals(id) ? "windows" : ("rocky".equals(id) || "ubuntu".equals(id)) ? "linux" : "unknown";
        if ("windows".equals(family) && (!data.has("variant-id") || !"server".equals(text(data, "variant-id")))) family = "unknown";
        String arch = text(data, "machine");
        return Map.of("family", family, "id", id, "version", text(data, "version-id"),
                "arch", "x86_64".equals(arch) || "x86-64".equals(arch) ? "x86_64" : "unsupported");
    }
    public static boolean supportedOs(Map<String, String> os) {
        if (!"x86_64".equals(os.get("arch"))) return false;
        String version = os.get("version");
        return "rocky".equals(os.get("id")) && Set.of("9.6", "9.7", "9.8", "10.2").contains(version)
                || "ubuntu".equals(os.get("id")) && Set.of("22.04", "24.04", "26.04").contains(version)
                || "windows".equals(os.get("family")) && "mswindows".equals(os.get("id")) && Set.of("2022", "2025").contains(version);
    }
}
