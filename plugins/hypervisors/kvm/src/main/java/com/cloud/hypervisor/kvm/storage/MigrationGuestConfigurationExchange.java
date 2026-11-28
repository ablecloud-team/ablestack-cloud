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
package com.cloud.hypervisor.kvm.storage;

import com.cloud.storage.MigrationDiskControllerPolicy;
import com.cloud.storage.MigrationGuestConfigurationPolicy;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public final class MigrationGuestConfigurationExchange {
    private final Function<String, String> runner;

    public MigrationGuestConfigurationExchange(Function<String, String> runner) {
        this.runner = runner;
    }

    private JsonElement rpc(String name, JsonObject args) {
        JsonObject req = new JsonObject();
        req.addProperty("execute", name);
        if (args != null) req.add("arguments", args);
        JsonObject response =
                JsonParser.parseString(runner.apply(req.toString())).getAsJsonObject();
        if (response.has("error") || !response.has("return"))
            throw new CloudRuntimeException("GUEST_CHANNEL_UNAVAILABLE");
        return response.get("return");
    }

    private void commands() {
        JsonObject info = rpc("guest-info", null).getAsJsonObject();
        Set<String> enabled = new HashSet<>();
        for (var value : info.getAsJsonArray("supported_commands")) {
            var item = value.getAsJsonObject();
            if (item.get("enabled").getAsBoolean()) enabled.add(item.get("name").getAsString());
        }
        if (!enabled.containsAll(
                List.of(
                        "guest-file-open",
                        "guest-file-read",
                        "guest-file-write",
                        "guest-file-close",
                        "guest-file-flush")))
            throw new CloudRuntimeException("GUEST_FILE_RPC_DISABLED");
    }

    private long open(String path, String mode) {
        JsonObject args = new JsonObject();
        args.addProperty("path", path);
        args.addProperty("mode", mode);
        return rpc("guest-file-open", args).getAsLong();
    }

    private void close(long handle) {
        JsonObject args = new JsonObject();
        args.addProperty("handle", handle);
        rpc("guest-file-close", args);
    }

    private String read(String path) {
        long handle;
        try {
            handle = open(path, "r");
        } catch (CloudRuntimeException missing) {
            return null;
        }
        try {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            while (bytes.size() <= 65536) {
                int limit = Math.min(8192, 65537 - bytes.size());
                JsonObject args = new JsonObject();
                args.addProperty("handle", handle);
                args.addProperty("count", limit);
                JsonObject result = rpc("guest-file-read", args).getAsJsonObject();
                long count = result.get("count").getAsLong();
                byte[] data =
                        count == 0
                                ? new byte[0]
                                : Base64.getDecoder().decode(result.get("buf-b64").getAsString());
                if (count != data.length || count > limit || bytes.size() + data.length > 65536)
                    throw new CloudRuntimeException("GUEST_REPORT_LIMIT_OR_PARTIAL");
                bytes.write(data, 0, data.length);
                if (result.get("eof").getAsBoolean())
                    return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
                // Windows only reports EOF on a subsequent zero-byte read.
                if (count == 0) throw new CloudRuntimeException("GUEST_REPORT_LIMIT_OR_PARTIAL");
            }
            throw new CloudRuntimeException("GUEST_REPORT_LIMIT_OR_PARTIAL");
        } finally {
            close(handle);
        }
    }

    private void write(String path, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 65536) throw new CloudRuntimeException("GUEST_BINDING_TOO_LARGE");
        long handle = open(path, "w");
        try {
            JsonObject args = new JsonObject();
            args.addProperty("handle", handle);
            args.addProperty("buf-b64", Base64.getEncoder().encodeToString(bytes));
            if (rpc("guest-file-write", args).getAsJsonObject().get("count").getAsLong()
                    != bytes.length) throw new CloudRuntimeException("GUEST_BINDING_PARTIAL_WRITE");
            JsonObject flush = new JsonObject();
            flush.addProperty("handle", handle);
            rpc("guest-file-flush", flush);
        } finally {
            close(handle);
        }
    }

    public static String bytesHash(String value) {
        try {
            byte[] bytes =
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : bytes) out.append(String.format("%02x", b & 255));
            return out.toString();
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static Element child(Element root, String name) {
        for (Node n = root.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element && name.equals(n.getNodeName())) return (Element) n;
        return null;
    }

    private static List<Element> children(Element root, String name) {
        List<Element> out = new ArrayList<>();
        for (Node n = root.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element && name.equals(n.getNodeName())) out.add((Element) n);
        return out;
    }

    public static String bootScope(String name, String uuid) {
        try {
            if (!name.matches("[A-Za-z0-9_.-]+")) throw new IllegalArgumentException();
            String pid = Files.readString(Path.of("/run/libvirt/qemu", name + ".pid")).trim();
            if (!pid.matches("[0-9]+")) throw new IllegalArgumentException();
            String args =
                    new String(
                            Files.readAllBytes(Path.of("/proc", pid, "cmdline")),
                            StandardCharsets.UTF_8);
            if (!args.contains(uuid)) throw new IllegalArgumentException();
            String stat = Files.readString(Path.of("/proc", pid, "stat"));
            String[] fields = stat.substring(stat.lastIndexOf(')') + 2).split(" ");
            return bytesHash(
                    uuid
                            + ":"
                            + Files.readString(Path.of("/proc/sys/kernel/random/boot_id")).trim()
                            + ":"
                            + pid
                            + ":"
                            + fields[19]);
        } catch (Exception error) {
            throw new CloudRuntimeException("GUEST_BOOT_SCOPE_UNAVAILABLE");
        }
    }

    public static void requireChannel(String xml) {
        Element devices =
                child(MigrationDiskControllerGuard.xml(xml).getDocumentElement(), "devices");
        if (devices != null) {
            for (Element channel : children(devices, "channel")) {
                Element target = child(channel, "target");
                if ("unix".equals(channel.getAttribute("type"))
                        && target != null
                        && "virtio".equals(target.getAttribute("type"))
                        && "org.qemu.guest_agent.0".equals(target.getAttribute("name"))) {
                    return;
                }
            }
        }
        throw new CloudRuntimeException("GUEST_CHANNEL_MISSING");
    }

    public static JsonObject binding(
            JsonObject plan,
            JsonObject controller,
            String volumes,
            String xml,
            String vmUuid,
            String scope) {
        new MigrationDiskControllerGuard()
                .validate(MigrationDiskControllerPolicy.canonical(controller), volumes, xml, true);
        Element root = MigrationDiskControllerGuard.xml(xml).getDocumentElement(),
                devices = child(root, "devices");
        if (!vmUuid.equalsIgnoreCase(child(root, "uuid").getTextContent()))
            throw new CloudRuntimeException("GUEST_VM_IDENTITY_MISMATCH");
        requireChannel(xml);
        JsonObject result = new JsonObject();
        result.add("version", plan.get("version"));
        result.addProperty("operation_id", plan.get("operation_id").getAsString());
        result.addProperty("plan_hash", plan.get("hash").getAsString());
        result.addProperty("source_vm_uuid", plan.get("source_vm_uuid").getAsString());
        result.addProperty("vm_uuid", vmUuid);
        result.addProperty("boot_scope", scope);
        JsonArray disks = new JsonArray();
        var bound = MigrationDiskControllerPolicy.bindingArray(volumes);
        Set<String> serials = new HashSet<>();
        for (int i = 0; i < controller.getAsJsonArray("disks").size(); i++) {
            var source = controller.getAsJsonArray("disks").get(i).getAsJsonObject();
            String device = source.getAsJsonObject("target").get("device").getAsString();
            Element found = null;
            for (Element d : children(devices, "disk")) {
                Element t = child(d, "target");
                if (t != null && device.equals(t.getAttribute("dev"))) {
                    if (found != null) throw new CloudRuntimeException("DATA_DEVICE_DUPLICATED");
                    found = d;
                }
            }
            Element serial = found == null ? null : child(found, "serial");
            if (serial == null
                    || serial.getTextContent().isBlank()
                    || !serials.add(serial.getTextContent()))
                throw new CloudRuntimeException("DATA_SERIAL_UNAVAILABLE");
            JsonObject item = new JsonObject();
            item.addProperty("source_disk_id", source.get("disk_id").getAsString());
            item.addProperty("role", source.get("role").getAsString());
            item.addProperty("volume_uuid", bound.get(i).getAsJsonObject().get("id").getAsString());
            item.addProperty("serial", serial.getTextContent());
            item.addProperty("size_bytes", source.get("size_bytes").getAsLong());
            disks.add(item);
        }
        result.add("disks", disks);
        JsonArray nics = new JsonArray();
        Set<String> macs = new HashSet<>();
        for (Element nic : children(devices, "interface")) {
            Element mac = child(nic, "mac");
            if (mac != null) {
                String value = mac.getAttribute("address").toLowerCase(java.util.Locale.ROOT);
                if (!macs.add(value)) throw new CloudRuntimeException("NIC_MAPPING_AMBIGUOUS");
                JsonObject item = new JsonObject();
                item.addProperty("mac", value);
                nics.add(item);
            }
        }
        if ("linux".equals(plan.get("os").getAsString()))
            for (var nic : plan.getAsJsonObject("network").getAsJsonArray("interfaces"))
                if (!macs.contains(nic.getAsJsonObject().get("mac").getAsString()))
                    throw new CloudRuntimeException("NIC_MAPPING_MISMATCH");
        if (plan.get("version").getAsInt() == 2)
            for (var nic : plan.getAsJsonObject("network").getAsJsonArray("interfaces"))
                if (!macs.contains(nic.getAsJsonObject().get("target_mac").getAsString()))
                    throw new CloudRuntimeException("NIC_MAPPING_MISMATCH");
        result.add("nics", nics);
        boolean empty = true;
        for (Element d : children(devices, "disk"))
            if ("cdrom".equals(d.getAttribute("device"))) {
                Element source = child(d, "source");
                if (source != null && source.hasAttributes()) empty = false;
            }
        result.addProperty("media_all_empty", empty);
        return result;
    }

    private static boolean matches(
            JsonObject report, JsonObject plan, JsonObject binding, String sha) {
        return report.has("version")
                && report.get("version").equals(plan.get("version"))
                && report.has("operation_id")
                && report.get("operation_id").equals(plan.get("operation_id"))
                && report.has("plan_hash")
                && report.get("plan_hash").equals(plan.get("hash"))
                && report.has("vm_uuid")
                && report.get("vm_uuid").equals(binding.get("vm_uuid"))
                && report.has("binding_sha256")
                && sha.equals(report.get("binding_sha256").getAsString())
                && report.has("boot_scope")
                && report.get("boot_scope").equals(binding.get("boot_scope"))
                && report.has("root_identity")
                && report.get("root_identity").equals(plan.get("root_identity"))
                && report.has("boot_id")
                && !report.get("boot_id").getAsString().isBlank();
    }

    public static void verifyReport(JsonObject report, JsonObject plan, JsonObject binding) {
        if (plan.get("version").getAsInt() == 2)
            com.cloud.storage.MigrationWindowsNetworkPolicy.verifyReport(plan, report, binding);
        if (plan.has("helper_sha256")
                && (!report.has("helper_sha256")
                        || !plan.get("helper_sha256").equals(report.get("helper_sha256"))))
            throw new CloudRuntimeException("GUEST_HELPER_HASH_MISMATCH");
        if ("linux".equals(plan.get("os").getAsString())) {
            var actual = report.getAsJsonObject("network");
            if (!"VERIFIED".equals(actual.get("status").getAsString())
                    || actual.getAsJsonArray("interfaces").size()
                            != plan.getAsJsonObject("network").getAsJsonArray("interfaces").size())
                throw new CloudRuntimeException("GUEST_NETWORK_REPORT_MISMATCH");
            for (var n : plan.getAsJsonObject("network").getAsJsonArray("interfaces")) {
                boolean found = false;
                for (var item : actual.getAsJsonArray("interfaces")) {
                    var v = item.getAsJsonObject();
                    if (v.get("mac").equals(n.getAsJsonObject().get("mac"))
                            && v.get("ifname").equals(n.getAsJsonObject().get("ifname"))
                            && "VERIFIED".equals(v.get("status").getAsString())) found = true;
                }
                if (!found) throw new CloudRuntimeException("GUEST_NETWORK_REPORT_MISMATCH");
            }
        } else {
            var actual = report.getAsJsonObject("windows");
            if (!"VERIFIED".equals(actual.get("status").getAsString())
                    || !actual.get("san_policy")
                            .equals(plan.getAsJsonObject("windows").get("san_policy"))
                    || actual.getAsJsonArray("disks").size()
                            != plan.getAsJsonObject("windows").getAsJsonArray("disks").size())
                throw new CloudRuntimeException("GUEST_DATA_REPORT_MISMATCH");
            for (var disk : plan.getAsJsonObject("windows").getAsJsonArray("disks")) {
                var wanted = disk.getAsJsonObject();
                boolean found = false;
                for (var item : actual.getAsJsonArray("disks")) {
                    var v = item.getAsJsonObject();
                    if (v.get("source_disk_id").equals(wanted.get("source_disk_id"))
                            && v.get("identity").equals(wanted.get("identity"))
                            && v.get("online").equals(wanted.get("online"))
                            && v.get("read_only").equals(wanted.get("read_only"))
                            && "VERIFIED".equals(v.get("status").getAsString())) {
                        Set<String> expectedPaths = new HashSet<>(), actualPaths = new HashSet<>();
                        if (wanted.get("online").getAsBoolean())
                            for (var p : wanted.getAsJsonArray("partitions"))
                                for (var a : p.getAsJsonObject().getAsJsonArray("access_paths"))
                                    expectedPaths.add(a.getAsString());
                        for (var a : v.getAsJsonArray("access_paths"))
                            actualPaths.add(a.getAsString());
                        boolean serial = false;
                        for (var b : binding.getAsJsonArray("disks"))
                            if (b.getAsJsonObject()
                                            .get("source_disk_id")
                                            .equals(wanted.get("source_disk_id"))
                                    && b.getAsJsonObject().get("serial").equals(v.get("serial")))
                                serial = true;
                        if (!expectedPaths.equals(actualPaths) || !serial)
                            throw new CloudRuntimeException("GUEST_DATA_REPORT_MISMATCH");
                        found = true;
                    }
                }
                if (!found) throw new CloudRuntimeException("GUEST_DATA_REPORT_MISMATCH");
            }
        }
    }

    public Map<String, Object> reconcile(JsonObject plan, JsonObject binding) {
        return reconcile(plan, binding, null);
    }

    public Map<String, Object> reconcile(
            JsonObject plan, JsonObject binding, String lastVerifiedHash) {

        commands();
        String directory = MigrationGuestConfigurationPolicy.directory(plan);
        String name = "binding-" + plan.get("operation_id").getAsString();
        String path = directory + name + ".json";
        String existing = read(path);
        JsonObject desired = binding.deepCopy();
        desired.remove("observation_nonce");
        boolean reuse = false;
        boolean rebootChallenge = false;
        if (existing != null && plan.get("version").getAsInt() == 2) {
            String pending = read(directory + "report.json");
            if (pending != null) {
                try {
                    var report = JsonParser.parseString(pending).getAsJsonObject();
                    var previous = JsonParser.parseString(existing).getAsJsonObject();
                    rebootChallenge = matches(report, plan, previous, bytesHash(existing))
                            && "PENDING".equals(report.get("status").getAsString())
                            && report.has("reason")
                            && "WINDOWS_NETWORK_REBOOT_REQUIRED".equals(report.get("reason").getAsString())
                            && plan.get("helper_sha256").equals(report.get("helper_sha256"));
                } catch (RuntimeException incomplete) {
                    // Incomplete or unbound reports cannot request a new challenge.
                }
            }
        }
        if (!rebootChallenge && existing != null && !bytesHash(existing).equals(lastVerifiedHash)) {
            try {
                JsonObject candidate = JsonParser.parseString(existing).getAsJsonObject();
                var nonce = candidate.remove("observation_nonce");
                if (nonce != null
                        && MigrationDiskControllerPolicy.canonical(candidate)
                                .equals(MigrationDiskControllerPolicy.canonical(desired))) {
                    java.util.UUID.fromString(nonce.getAsString());
                    desired.add("observation_nonce", nonce);
                    reuse = true;
                }
            } catch (RuntimeException invalid) {
                /* Replace an incomplete/unqualified request. */
            }
        }
        if (!reuse)
            desired.addProperty("observation_nonce", java.util.UUID.randomUUID().toString());
        binding = desired;
        String data = MigrationDiskControllerPolicy.canonical(binding), sha = bytesHash(data);
        JsonObject commit = new JsonObject();
        commit.addProperty("sha256", sha);
        commit.addProperty("bytes", data.getBytes(StandardCharsets.UTF_8).length);
        String previousCommit = read(directory + name + ".commit");
        if (!data.equals(existing)) write(directory + name + ".json", data);
        if (!data.equals(existing) || !commit.toString().equals(previousCommit))
            write(directory + name + ".commit", commit.toString());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("supported", true);
        out.put("fresh_observation", true);
        out.put("plan_hash", plan.get("hash").getAsString());
        out.put("operation_id", plan.get("operation_id").getAsString());
        out.put("vm_uuid", binding.get("vm_uuid").getAsString());
        out.put("boot_scope", binding.get("boot_scope").getAsString());
        out.put("binding_sha256", sha);
        String text = read(directory + "report.json");
        out.put("status", "PENDING");
        if (text == null) return out;
        JsonObject report = JsonParser.parseString(text).getAsJsonObject();
        if (!matches(report, plan, binding, sha)) {
            out.put("reason", "GUEST_REPORT_STALE_OR_UNBOUND");
            return out;
        }
        String status = report.get("status").getAsString();
        if (!List.of("VERIFIED", "PENDING", "FAILED", "DRIFT", "UNKNOWN").contains(status))
            throw new CloudRuntimeException("GUEST_REPORT_STATUS_INVALID");
        if ("VERIFIED".equals(status)) verifyReport(report, plan, binding);
        out.put("status", status);
        out.put("report", new Gson().fromJson(report, Map.class));
        if (report.has("reason")) {
            String reason = report.get("reason").getAsString();
            out.put("reason", reason.matches("[A-Z0-9_]{1,128}") ? reason : "GUEST_CONFIG_FAILED");
        }
        return out;
    }
}
