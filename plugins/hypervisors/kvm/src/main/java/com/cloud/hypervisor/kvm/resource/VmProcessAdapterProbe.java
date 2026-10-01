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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.cloud.agent.api.GetVmProcessCapabilitiesCommand;
import com.cloud.agent.api.VmProcessAction;
import com.google.gson.Gson;

/** Uses the qemu-owned approved bundle catalog and vm_exec. Never changes a guest process or file. */
public class VmProcessAdapterProbe {
    private static final Map<String, List<String>> PROFILES = Map.of(
            "windows-read", List.of("ProcessList.ps1", "AbleProcessIdentity.dll"),
            "windows-action", List.of("ProcessAction.ps1", "AbleProcessAction.dll", "AbleProcessIdentity.dll"),
            "rocky-read", List.of("process_list_linux.py", "process-read-launcher"),
            "ubuntu-read", List.of("process_list_linux.py"),
            "linux-action", List.of("process_action_linux.py", "process-action-launcher"));

    protected String catalog() throws IOException {
        for (String root : List.of("/usr/libexec/ablestack-qemu-exec-tools", "/usr/local/lib/ablestack-qemu-exec-tools", "/usr/lib/ablestack-qemu-exec-tools")) {
            Path path = Path.of(root, "process", "guest_adapter_compat.json");
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) continue;
            for (Path current = path; current != null; current = current.getParent()) {
                Map<String, Object> attrs = Files.readAttributes(current, "unix:uid,mode,nlink", LinkOption.NOFOLLOW_LINKS);
                int mode = (Integer) attrs.get("mode");
                if (!Integer.valueOf(0).equals(attrs.get("uid")) || (mode & 0022) != 0
                        || Files.isSymbolicLink(current) || current.equals(path)
                            && (!Files.isRegularFile(current, LinkOption.NOFOLLOW_LINKS) || !Integer.valueOf(1).equals(attrs.get("nlink"))))
                    throw new IOException("Unsafe host adapter catalog");
            }
            try (SeekableByteChannel channel = Files.newByteChannel(path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                if (channel.size() > 65536) throw new IOException("Adapter catalog limit");
                ByteBuffer bytes = ByteBuffer.allocate(65537);
                while (bytes.hasRemaining() && channel.read(bytes) != -1) { }
                if (!bytes.hasRemaining()) throw new IOException("Adapter catalog limit");
                bytes.flip();
                return StandardCharsets.UTF_8.newDecoder().decode(bytes).toString();
            }
        }
        throw new IOException("Host adapter catalog missing");
    }

    static List<Map<String, Object>> bundles(String text) throws IOException {
        Map<String, Object> catalog = VmProcessAction.parse(text);
        if (!catalog.keySet().equals(Set.of("schemaVersion", "protocolVersion", "bundles"))
                || !"1".equals(String.valueOf(catalog.get("schemaVersion"))) || !"1.0".equals(catalog.get("protocolVersion"))
                || !(catalog.get("bundles") instanceof List)) throw new IOException("Unsupported adapter catalog");
        List<?> entries = (List<?>) catalog.get("bundles");
        if (entries.isEmpty() || entries.size() > 128) throw new IOException("Adapter bundle limit");
        Set<String> ids = new HashSet<>(); List<Map<String, Object>> result = new ArrayList<>();
        for (Object entry : entries) {
            Map<String, Object> bundle = com.cloud.agent.api.VmProcessSnapshot.map(entry);
            String id = String.valueOf(bundle.get("id")); String profile = String.valueOf(bundle.get("profile"));
            if (!bundle.keySet().equals(Set.of("id", "profile", "sha256")) || !id.matches("[a-z0-9][a-z0-9._-]{0,79}")
                    || !ids.add(id) || !PROFILES.containsKey(profile)) throw new IOException("Invalid adapter bundle");
            Map<String, Object> hashes = com.cloud.agent.api.VmProcessSnapshot.map(bundle.get("sha256"));
            if (!hashes.keySet().equals(Set.copyOf(PROFILES.get(profile)))
                    || hashes.values().stream().anyMatch(h -> !(h instanceof String) || !((String) h).matches("[a-f0-9]{64}")))
                throw new IOException("Invalid adapter hashes");
            result.add(bundle);
        }
        return result;
    }

    protected String execute(String uuid, boolean windows, String program) throws IOException, InterruptedException {
        List<String> argv = new ArrayList<>(List.of("/usr/bin/vm_exec", windows ? "-w" : "-l", uuid,
                "--json", "--timeout", "6", "--rpc-timeout", "2", "--max-output-bytes", "32768", "--"));
        if (windows) argv.addAll(List.of("C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe",
                "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand",
                Base64.getEncoder().encodeToString(program.getBytes(StandardCharsets.UTF_16LE))));
        else argv.addAll(List.of("/usr/bin/python3", "-I", "-B", "-c", program));
        if (windows && argv.get(argv.size() - 1).length() > 31000) throw new IOException("Windows readiness argument limit");
        return KvmVmOperationGuard.probe(7000, argv.toArray(new String[0]));
    }

    public Map<String, Object> observe(GetVmProcessCapabilitiesCommand command, Map<String, String> os) throws IOException, InterruptedException {
        boolean windows = "windows".equals(os.get("family"));
        String read = windows ? "windows-read" : Set.of("rocky", "rhel").contains(os.get("id")) ? "rocky-read" : "ubuntu-read";
        String action = windows ? "windows-action" : "linux-action";
        List<Map<String, Object>> entries;
        try { entries = bundles(catalog()); }
        catch (IOException | RuntimeException e) { return Map.of("readiness", "HOST_TOOL_MISSING"); }
        List<Map<String, Object>> selected = entries.stream().filter(b -> Set.of(read, action).contains(b.get("profile"))).collect(java.util.stream.Collectors.toList());
        if (selected.stream().noneMatch(b -> read.equals(b.get("profile")))) return Map.of("readiness", "HOST_TOOL_MISSING");
        Map<String, Object> config = Map.of("requestId", command.getRequestId(), "readProfile", read, "actionProfile", action, "bundles", selected);
        String json = new Gson().toJson(config);
        String program;
        try (java.io.InputStream input = getClass().getResourceAsStream(windows ? "/vm-process-readiness.ps1" : "/vm-process-readiness.py")) {
            if (input == null) throw new IOException("Readiness program missing");
            program = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            if (windows) {
                // Keep the source license; omit comments/indentation only on the bounded wire command.
                // Literal JSON avoids Defender's compressed PowerShell staging detection.
                program = program.lines().map(String::trim).filter(line -> !line.isEmpty() && !line.startsWith("#"))
                        .collect(java.util.stream.Collectors.joining("\n"))
                        .replace("__CONFIG_JSON__", json.replace("'", "''"));
            } else {
                java.io.ByteArrayOutputStream compressed = new java.io.ByteArrayOutputStream();
                try (java.util.zip.GZIPOutputStream zip = new java.util.zip.GZIPOutputStream(compressed)) {
                    zip.write(json.getBytes(StandardCharsets.UTF_8));
                }
                program = program.replace("__CONFIG_BASE64__", Base64.getEncoder().encodeToString(compressed.toByteArray()));
            }
        }
        Map<String, Object> transport = VmProcessAction.parse(execute(command.getVmUuid(), windows, program));
        if (!"SUCCEEDED".equals(transport.get("state")) || !"0".equals(String.valueOf(transport.get("exit_code")))
                || !Boolean.FALSE.equals(transport.get("encoding_loss")) || !Boolean.FALSE.equals(transport.get("out_truncated"))
                || !Boolean.FALSE.equals(transport.get("err_truncated")) || !(transport.get("stdout_raw") instanceof String))
            throw new IOException("Guest readiness execution unavailable");
        Map<String, Object> proof = VmProcessAction.parse((String) transport.get("stdout_raw"));
        if (!proof.keySet().equals(Set.of("requestId", "readBundle", "actionBundle", "readRuntime", "actionRuntime", "code"))
                || !command.getRequestId().equals(proof.get("requestId")) || !(proof.get("readRuntime") instanceof Boolean)
                || !(proof.get("actionRuntime") instanceof Boolean)) throw new IOException("Guest readiness identity mismatch");
        String code = String.valueOf(proof.get("code"));
        if (!Set.of("READY", "TOOLS_REQUIRED", "CHECK_FAILED").contains(code)) throw new IOException("Invalid readiness result");
        if (!"READY".equals(code)) return Map.of("readiness", code);
        if (!Boolean.TRUE.equals(proof.get("readRuntime")) || !matches(selected, read, proof.get("readBundle")))
            throw new IOException("Unproven read adapter");
        List<String> allowed = new ArrayList<>(List.of("process.list"));
        String version = (String) proof.get("readBundle");
        if (Boolean.TRUE.equals(proof.get("actionRuntime"))) {
            if (!matches(selected, action, proof.get("actionBundle"))) throw new IOException("Unproven action adapter");
            if (!windows) allowed.add("process.terminate");
            allowed.addAll(List.of("process.kill", "service.restart"));
            version += "/" + proof.get("actionBundle");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("readiness", "READY"); result.put("guestAdapterVersion", version);
        result.put("supportedSchemaVersions", List.of("1.0")); result.put("allowedActions", allowed); result.put("error", null);
        return result;
    }

    private static boolean matches(List<Map<String, Object>> entries, String profile, Object id) {
        return id instanceof String && entries.stream().anyMatch(b -> profile.equals(b.get("profile")) && id.equals(b.get("id")));
    }
}
