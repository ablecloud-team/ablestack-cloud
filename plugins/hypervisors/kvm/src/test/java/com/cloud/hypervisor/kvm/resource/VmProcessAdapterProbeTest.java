// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.hypervisor.kvm.resource;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import com.cloud.agent.api.GetVmProcessCapabilitiesCommand;
import com.google.gson.GsonBuilder;

public class VmProcessAdapterProbeTest {
    private final GetVmProcessCapabilitiesCommand command = new GetVmProcessCapabilitiesCommand("vm",
            "11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222", "4", "request");
    private final com.google.gson.Gson gson = new GsonBuilder().serializeNulls().create();
    private final String hash = "a".repeat(64);
    private Map<String, Object> bundle(String id, String profile, Map<String, String> hashes) {
        return Map.of("id", id, "profile", profile, "sha256", hashes);
    }
    private String catalog() {
        return gson.toJson(Map.of("schemaVersion", 1, "protocolVersion", "1.0", "bundles", List.of(
                bundle("linux-profile", "linux-profile", Map.of("process_action_linux.py", hash, "process-action-launcher", hash, "process_profile_linux.py", hash)),
                bundle("windows-profile", "windows-profile", Map.of("ProcessAction.ps1", hash, "AbleProcessAction.dll", hash, "AbleProcessIdentity.dll", hash, "ProcessProfile.ps1", hash, "Start-ProcessProfile.ps1", hash)),
                bundle("old-read", "ubuntu-read", Map.of("process_list_linux.py", hash)),
                bundle("new-read", "ubuntu-read", Map.of("process_list_linux.py", "b".repeat(64))),
                bundle("action", "linux-action", Map.of("process_action_linux.py", hash, "process-action-launcher", hash)),
                bundle("windows-read", "windows-read", Map.of("ProcessList.ps1", hash, "AbleProcessIdentity.dll", hash)),
                bundle("windows-action", "windows-action", Map.of("ProcessAction.ps1", hash, "AbleProcessAction.dll", hash, "AbleProcessIdentity.dll", hash)))));
    }
    private Map<String, Object> proof(String read, String action, boolean runtime) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requestId", command.getRequestId()); result.put("readBundle", read); result.put("actionBundle", action);
        result.put("readRuntime", true); result.put("actionRuntime", runtime); result.put("code", "READY");
        return result;
    }
    private VmProcessAdapterProbe probe(Map<String, Object> proof) {
        return new VmProcessAdapterProbe() {
            @Override protected String catalog() { return VmProcessAdapterProbeTest.this.catalog(); }
            @Override protected String execute(String uuid, boolean windows, String program) {
                assertEquals(command.getVmUuid(), uuid);
                if (program.contains("$hashProof")) return hashTransport(command.getRequestId());
                assertTrue(program.contains("matched") || program.contains("Matched"));
                return gson.toJson(Map.of("state", "SUCCEEDED", "exit_code", 0, "encoding_loss", false,
                        "out_truncated", false, "err_truncated", false, "stdout_raw", gson.toJson(proof)));
            }
        };
    }
    @Test public void windowsProbePassesCatalogAsLiteralDataWithoutCompressedStaging() throws Exception {
        GetVmProcessCapabilitiesCommand quoted = new GetVmProcessCapabilitiesCommand("vm", command.getVmUuid(),
                command.getHostUuid(), "4", "request'quoted");
        VmProcessAdapterProbe probe = new VmProcessAdapterProbe() {
            @Override protected String catalog() { return VmProcessAdapterProbeTest.this.catalog(); }
            @Override protected String execute(String uuid, boolean windows, String program) {
                assertTrue(windows);
                assertTrue(program.contains("$ProgressPreference='SilentlyContinue'"));
                org.junit.Assert.assertFalse(program.contains("GZipStream"));
                org.junit.Assert.assertFalse(program.contains("FromBase64String"));
                assertTrue(program.contains("request\\u0027quoted"));
                if (program.contains("$hashProof")) return hashTransport(quoted.getRequestId());
                assertTrue(program.contains("windows-read"));
                assertTrue(program.contains("windows-action"));
                assertTrue(program.contains("Get-FileHash"));
                assertTrue(program.contains("$config='"));
                Map<String, Object> result = proof("windows-read", "windows-action", true);
                result.put("requestId", quoted.getRequestId());
                return gson.toJson(Map.of("state", "SUCCEEDED", "exit_code", 0, "encoding_loss", false,
                        "out_truncated", false, "err_truncated", false, "stdout_raw", gson.toJson(result)));
            }
        };
        assertEquals("READY", probe.observe(quoted, Map.of("family", "windows", "id", "mswindows")).get("readiness"));
    }
    private String hashTransport(String request) {
        return gson.toJson(Map.of("state", "SUCCEEDED", "exit_code", 0, "encoding_loss", false,
                "out_truncated", false, "err_truncated", false, "stdout_raw", gson.toJson(Map.of("requestId", request,
                "sha256", Map.of("ProcessList.ps1", hash, "AbleProcessIdentity.dll", hash, "ProcessAction.ps1", hash, "AbleProcessAction.dll", hash),
                "normalizedList", hash))));
    }
    @Test public void maximumCatalogKeepsAllApprovalsAndBoundedWindowsArguments() throws Exception {
        List<Map<String, Object>> entries = new java.util.ArrayList<>();
        for (int i = 0; i < 128; i++) {
            String value = i < 2 ? hash : String.format("%064x", i);
            entries.add(bundle("legacy-" + i, i % 2 == 0 ? "windows-read" : "windows-action", i % 2 == 0
                    ? Map.of("ProcessList.ps1", value, "AbleProcessIdentity.dll", value)
                    : Map.of("ProcessAction.ps1", value, "AbleProcessAction.dll", value, "AbleProcessIdentity.dll", value)));
        }
        int[] calls = {0};
        VmProcessAdapterProbe bounded = new VmProcessAdapterProbe() {
            @Override protected String catalog() { return gson.toJson(Map.of("schemaVersion", 1, "protocolVersion", "1.0", "bundles", entries)); }
            @Override protected String execute(String uuid, boolean windows, String program) {
                calls[0]++;
                assertTrue(java.util.Base64.getEncoder().encodeToString(program.getBytes(java.nio.charset.StandardCharsets.UTF_16LE)).length() < 31000);
                if (program.contains("$hashProof")) return hashTransport(command.getRequestId());
                assertTrue(program.contains("legacy-0")); assertTrue(program.contains("legacy-1"));
                org.junit.Assert.assertFalse(program.contains("legacy-127"));
                return gson.toJson(Map.of("state", "SUCCEEDED", "exit_code", 0, "encoding_loss", false,
                        "out_truncated", false, "err_truncated", false, "stdout_raw", gson.toJson(proof("legacy-0", "legacy-1", true))));
            }
        };
        assertEquals("READY", bounded.observe(command, Map.of("family", "windows", "id", "mswindows")).get("readiness"));
        assertEquals(2, calls[0]); assertEquals(128, entries.size());
    }

    @Test public void legacyApprovedBundleSurvivesHostUpgradeAndMigration() throws Exception {
        Map<String, Object> result = probe(proof("old-read", "action", true)).observe(command, Map.of("family", "linux", "id", "ubuntu"));
        assertEquals("READY", result.get("readiness"));
        assertEquals("old-read/action", result.get("guestAdapterVersion"));
        assertEquals(List.of("process.list", "process.terminate", "process.kill", "service.restart"), result.get("allowedActions"));
    }
    @Test public void unsupportedActionRuntimeAllowsOnlyProvenRead() throws Exception {
        assertEquals(List.of("process.list"), probe(proof("old-read", "action", false))
                .observe(command, Map.of("family", "linux", "id", "debian")).get("allowedActions"));
        assertEquals(List.of("process.list", "process.kill", "service.restart"), probe(proof("windows-read", "windows-action", true))
                .observe(command, Map.of("family", "windows", "id", "mswindows")).get("allowedActions"));
    }
    @Test public void unapprovedOrWrongProfileAndRequestProofAreRejected() {
        for (Map<String, Object> value : List.of(proof("unknown", "action", true), proof("windows-read", "action", true), proof("old-read", "unknown", true)))
            assertThrows(IOException.class, () -> probe(value).observe(command, Map.of("family", "linux", "id", "ubuntu")));
        Map<String, Object> value = proof("old-read", "action", true); value.put("requestId", "old-request");
        assertThrows(IOException.class, () -> probe(value).observe(command, Map.of("family", "linux", "id", "ubuntu")));
    }
    @Test public void rejectsMalformedCatalogAndNeverWeakensHashPolicy() {
        for (String invalid : List.of("{}", catalog().replace("\"1.0\"", "\"2.0\""),
                catalog().replace("\"new-read\"", "\"old-read\""), catalog().replace(hash, "x".repeat(64)),
                catalog().replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1")))
            assertThrows(IOException.class, () -> VmProcessAdapterProbe.bundles(invalid));
    }
    @Test public void missingHostCatalogIsActionableAndNeverReady() throws Exception {
        VmProcessAdapterProbe probe = new VmProcessAdapterProbe() {
            @Override protected String catalog() throws IOException { throw new IOException("missing"); }
        };
        assertEquals("HOST_TOOL_MISSING", probe.observe(command, Map.of("family", "linux", "id", "ubuntu")).get("readiness"));
    }
    @Test public void guestSmokeRejectsMixedBundlesBeforeLoadingCode() throws Exception {
        org.junit.Assume.assumeTrue(java.nio.file.Files.isExecutable(java.nio.file.Path.of("/usr/bin/python3")));
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("readiness-bundle-test-");
        try {
            String script = "import os\nfrom pathlib import Path\nPath('" + dir.resolve("loaded") + "').touch()\n"
                    + "def stat_record(text): return (os.getpid(),'self','R',0,'1')\n";
            byte[] a = script.getBytes(java.nio.charset.StandardCharsets.UTF_8); byte[] launcher = "launcher-a".getBytes();
            java.nio.file.Files.write(dir.resolve("process_list_linux.py"), a);
            java.nio.file.Files.write(dir.resolve("process-read-launcher"), "launcher-b".getBytes());
            java.nio.file.Files.setPosixFilePermissions(dir.resolve("process-read-launcher"), java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
            Map<String, Object> config = Map.of("requestId", "test", "readProfile", "rocky-read", "actionProfile", "linux-action", "bundles", List.of(
                    bundle("a", "rocky-read", Map.of("process_list_linux.py", digest(a), "process-read-launcher", digest(launcher))),
                    bundle("b", "rocky-read", Map.of("process_list_linux.py", digest("script-b".getBytes()), "process-read-launcher", digest("launcher-b".getBytes())))));
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            try (java.util.zip.GZIPOutputStream zip = new java.util.zip.GZIPOutputStream(bytes)) { zip.write(gson.toJson(config).getBytes()); }
            String program;
            try (java.io.InputStream input = getClass().getResourceAsStream("/vm-process-readiness.py")) {
                program = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                        .replace("__CONFIG_BASE64__", java.util.Base64.getEncoder().encodeToString(bytes.toByteArray()))
                        .replace("/usr/libexec/ablestack-qemu-exec-tools/process", dir.toString())
                        .replace("info.st_uid != 0", "info.st_uid != os.geteuid()");
            }
            Process process = new ProcessBuilder("/usr/bin/python3", "-I", "-B", "-c", program).start();
            assertTrue(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
            Map<String, Object> result = com.cloud.agent.api.VmProcessAction.parse(new String(process.getInputStream().readAllBytes()));
            assertEquals("TOOLS_REQUIRED", result.get("code"));
            org.junit.Assert.assertFalse(java.nio.file.Files.exists(dir.resolve("loaded")));
        } finally {
            try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(dir)) {
                for (java.nio.file.Path path : files.sorted(java.util.Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList())) java.nio.file.Files.delete(path);
            }
        }
    }
    private String digest(byte[] bytes) throws Exception {
        byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder text = new StringBuilder();
        for (byte value : hash) text.append(String.format("%02x", value & 255));
        return text.toString();
    }
    @Test public void approvedLegacyPinnedHandleWorksWithoutPythonPidfdOpen() throws Exception {
        Map<String, Object> result = legacyHandleProof(true);
        assertEquals("READY", result.get("code"));
        assertEquals(true, result.get("readRuntime"));
        assertEquals(true, result.get("actionRuntime"));
    }
    @Test public void unsupportedLegacyHandleKeepsOnlyProvenRead() throws Exception {
        Map<String, Object> result = legacyHandleProof(false);
        assertEquals("READY", result.get("code"));
        assertEquals(true, result.get("readRuntime"));
        assertEquals(false, result.get("actionRuntime"));
    }
    private Map<String, Object> legacyHandleProof(boolean supported) throws Exception {
        org.junit.Assume.assumeTrue(java.nio.file.Files.isExecutable(java.nio.file.Path.of("/usr/bin/python3")));
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("readiness-legacy-handle-");
        try {
            String collector = "import os\ndef stat_record(text): return (os.getpid(),'self','R',0,'1')\n";
            String action = "import os,signal\ndef validate(): pass\ndef action(): pass\n"
                    + "def open_target(pid):\n assert pid == os.getpid()\n assert not hasattr(os,'pidfd_open')\n"
                    + (supported ? " fd=os.open('/proc/self',os.O_RDONLY|os.O_DIRECTORY)\n signal.pidfd_send_signal(fd,0)\n return fd,False\n"
                                 : " raise OSError('unsupported kernel handle')\n");
            byte[] read = collector.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] mutation = action.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] launcher = "fixture-launcher".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            java.nio.file.Files.write(dir.resolve("process_list_linux.py"), read);
            java.nio.file.Files.write(dir.resolve("process_action_linux.py"), mutation);
            java.nio.file.Files.write(dir.resolve("process-action-launcher"), launcher);
            java.nio.file.Files.setPosixFilePermissions(dir.resolve("process-action-launcher"),
                    java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
            Map<String, Object> config = Map.of("requestId", "test", "readProfile", "ubuntu-read", "actionProfile", "linux-action", "bundles", List.of(
                    bundle("read", "ubuntu-read", Map.of("process_list_linux.py", digest(read))),
                    bundle("action", "linux-action", Map.of("process_action_linux.py", digest(mutation), "process-action-launcher", digest(launcher)))));
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            try (java.util.zip.GZIPOutputStream zip = new java.util.zip.GZIPOutputStream(bytes)) {
                zip.write(gson.toJson(config).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            String program;
            try (java.io.InputStream input = getClass().getResourceAsStream("/vm-process-readiness.py")) {
                program = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                        .replace("__CONFIG_BASE64__", java.util.Base64.getEncoder().encodeToString(bytes.toByteArray()))
                        .replace("/usr/libexec/ablestack-qemu-exec-tools/process", dir.toString())
                        .replace("info.st_uid != 0", "info.st_uid != os.geteuid()")
                        .replace("import runpy", "import runpy\nif hasattr(os, 'pidfd_open'): delattr(os, 'pidfd_open')");
            }
            Process process = new ProcessBuilder("/usr/bin/python3", "-I", "-B", "-c", program).start();
            assertTrue(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
            return com.cloud.agent.api.VmProcessAction.parse(new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        } finally {
            try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(dir)) {
                for (java.nio.file.Path path : files.sorted(java.util.Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList())) {
                    java.nio.file.Files.delete(path);
                }
            }
        }
    }
}
