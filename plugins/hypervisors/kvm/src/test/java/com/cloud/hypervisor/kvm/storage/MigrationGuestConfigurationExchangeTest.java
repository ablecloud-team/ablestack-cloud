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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

public class MigrationGuestConfigurationExchangeTest {
    private JsonObject plan() {
        return JsonParser.parseString(
                        "{\"version\":1,\"os\":\"linux\",\"operation_id\":\"00000000-0000-0000-0000-000000000001\",\"source_vm_uuid\":\"source\",\"hash\":\"plan\",\"root_identity\":\"fs:root\",\"network\":{\"interfaces\":[{\"mac\":\"00:50:56:39:90:01\",\"ifname\":\"ens192\"}]}}")
                .getAsJsonObject();
    }

    private JsonObject binding() {
        return JsonParser.parseString(
                        "{\"operation_id\":\"00000000-0000-0000-0000-000000000001\",\"plan_hash\":\"plan\",\"vm_uuid\":\"vm\",\"boot_scope\":\"new-boot\"}")
                .getAsJsonObject();
    }

    private static class Guest {
        Map<String, String> files = new HashMap<>();
        Map<Long, String> handles = new HashMap<>();
        long next = 1;
        Map<Long, Integer> offsets = new HashMap<>();
        boolean windowsEof = false;
        boolean enabled = true;

        String call(String text) {
            var r = JsonParser.parseString(text).getAsJsonObject();
            String name = r.get("execute").getAsString();
            var a = r.getAsJsonObject("arguments");
            JsonObject out = new JsonObject();
            if (name.equals("guest-info")) {
                JsonArray commands = new JsonArray();
                for (String cmd :
                        new String[] {
                            "guest-file-open",
                            "guest-file-read",
                            "guest-file-write",
                            "guest-file-close",
                            "guest-file-flush"
                        }) {
                    var c = new JsonObject();
                    c.addProperty("name", cmd);
                    c.addProperty("enabled", enabled);
                    commands.add(c);
                }
                JsonObject value = new JsonObject();
                value.add("supported_commands", commands);
                out.add("return", value);
            } else if (name.equals("guest-file-open")) {
                String path = a.get("path").getAsString();
                assertTrue(path.startsWith("/var/lib/ablestack-v2k/guest-config/") || path.startsWith("C:/ProgramData/ABLECLOUD/V2K/GuestConfig/"));
                if (a.get("mode").getAsString().equals("r") && !files.containsKey(path)) {
                    out.add("error", new JsonObject());
                } else {
                    long handle = next++;
                    handles.put(handle, path);
                    offsets.put(handle, 0);
                    out.addProperty("return", handle);
                }
            } else if (name.equals("guest-file-read")) {
                String value = files.get(handles.get(a.get("handle").getAsLong()));
                byte[] all = value.getBytes(StandardCharsets.UTF_8);
                long handle = a.get("handle").getAsLong();
                int start = offsets.get(handle),
                        count = Math.min(a.get("count").getAsInt(), all.length - start);
                byte[] bytes = java.util.Arrays.copyOfRange(all, start, start + count);
                offsets.put(handle, start + count);
                JsonObject v = new JsonObject();
                v.addProperty("count", count);
                if (count > 0) v.addProperty("buf-b64", Base64.getEncoder().encodeToString(bytes));
                v.addProperty("eof", windowsEof ? count == 0 : start + count == all.length);
                out.add("return", v);
            } else if (name.equals("guest-file-write")) {
                byte[] bytes = Base64.getDecoder().decode(a.get("buf-b64").getAsString());
                files.put(
                        handles.get(a.get("handle").getAsLong()),
                        new String(bytes, StandardCharsets.UTF_8));
                JsonObject v = new JsonObject();
                v.addProperty("count", bytes.length);
                out.add("return", v);
            } else {
                if (name.equals("guest-file-close")) {
                    handles.remove(a.get("handle").getAsLong());
                    offsets.remove(a.get("handle").getAsLong());
                }
                out.add("return", new JsonObject());
            }
            return out.toString();
        }
    }

    @Test
    public void metadataTextCannotPretendToBeTheGuestChannel() {
        try {
            MigrationGuestConfigurationExchange.requireChannel(
                    "<domain><metadata><channel><target"
                        + " name='org.qemu.guest_agent.0'/></channel></metadata><devices/></domain>");
            fail("Expected missing channel rejection");
        } catch (com.cloud.utils.exception.CloudRuntimeException expected) {
            assertEquals("GUEST_CHANNEL_MISSING", expected.getMessage());
        }
    }

    @Test
    public void channelMustUseTheQualifiedVirtioUnixTransport() {
        MigrationGuestConfigurationExchange.requireChannel(
                "<domain><devices><channel type='unix'><target type='virtio'"
                        + " name='org.qemu.guest_agent.0'/></channel></devices></domain>");
    }

    @Test
    public void publishesBoundPayloadAndCommitWithClosedHandles() {
        var guest = new Guest();
        var result =
                new MigrationGuestConfigurationExchange(guest::call).reconcile(plan(), binding());
        assertEquals("PENDING", result.get("status"));
        assertEquals(2, guest.files.size());
        assertTrue(guest.handles.isEmpty());
    }

    @Test
    public void lostCommitIsRecoveredWithoutChangingThePayload() {
        var guest = new Guest();
        var b = binding();
        b.addProperty("observation_nonce", "00000000-0000-0000-0000-000000000004");
        String data = com.cloud.storage.MigrationDiskControllerPolicy.canonical(b);
        String path =
                "/var/lib/ablestack-v2k/guest-config/binding-00000000-0000-0000-0000-000000000001";
        guest.files.put(path + ".json", data);
        new MigrationGuestConfigurationExchange(guest::call).reconcile(plan(), b);
        assertEquals(data, guest.files.get(path + ".json"));
        assertTrue(guest.files.containsKey(path + ".commit"));
        assertTrue(guest.handles.isEmpty());
    }

    @Test
    public void pendingObservationRetainsItsNonceAcrossAgentInstances() {
        var guest = new Guest();
        var first =
                new MigrationGuestConfigurationExchange(guest::call)
                        .reconcile(plan(), binding(), null);
        var second =
                new MigrationGuestConfigurationExchange(guest::call)
                        .reconcile(plan(), binding(), null);
        assertEquals(first.get("binding_sha256"), second.get("binding_sha256"));
    }

    @Test
    public void controlledGuestRebootGetsOneFreshChallengeWithinTheSameQemuProcess() {
        var guest = new Guest();
        var p = plan(); p.addProperty("version", 2); p.addProperty("os", "windows");
        p.addProperty("helper_sha256", "b".repeat(64));
        var b = binding();
        var first = new MigrationGuestConfigurationExchange(guest::call).reconcile(p, b);
        String directory = "C:/ProgramData/ABLECLOUD/V2K/GuestConfig/";
        var report = new JsonObject();
        report.addProperty("version", 2);
        report.add("operation_id", p.get("operation_id")); report.add("plan_hash", p.get("hash"));
        report.add("vm_uuid", b.get("vm_uuid")); report.add("boot_scope", b.get("boot_scope"));
        report.add("root_identity", p.get("root_identity")); report.add("helper_sha256", p.get("helper_sha256"));
        report.addProperty("binding_sha256", (String)first.get("binding_sha256"));
        report.addProperty("boot_id", "previous-guest-boot"); report.addProperty("status", "PENDING");
        report.addProperty("reason", "WINDOWS_NETWORK_REBOOT_REQUIRED");
        guest.files.put(directory + "report.json", report.toString());
        var second = new MigrationGuestConfigurationExchange(guest::call).reconcile(p, b);
        org.junit.Assert.assertNotEquals(first.get("binding_sha256"), second.get("binding_sha256"));
        var third = new MigrationGuestConfigurationExchange(guest::call).reconcile(p, b);
        assertEquals(second.get("binding_sha256"), third.get("binding_sha256"));
        assertTrue(guest.handles.isEmpty());
    }

    @Test
    public void consumedEvidenceNeedsAFreshChallengeEvenWithTheSameQemuProcess() {
        var guest = new Guest();
        var first =
                new MigrationGuestConfigurationExchange(guest::call)
                        .reconcile(plan(), binding(), null);
        var second =
                new MigrationGuestConfigurationExchange(guest::call)
                        .reconcile(plan(), binding(), (String) first.get("binding_sha256"));
        org.junit.Assert.assertNotEquals(first.get("binding_sha256"), second.get("binding_sha256"));
        assertEquals("PENDING", second.get("status"));
        assertTrue(guest.handles.isEmpty());
    }

    @Test
    public void precreatedEmptyWindowsStyleFilesDoNotRequireABase64Field() {
        var guest = new Guest();
        String path =
                "/var/lib/ablestack-v2k/guest-config/binding-00000000-0000-0000-0000-000000000001";
        guest.files.put(path + ".json", "");
        guest.files.put(path + ".commit", "");
        assertEquals(
                "PENDING",
                new MigrationGuestConfigurationExchange(guest::call)
                        .reconcile(plan(), binding())
                        .get("status"));
        assertTrue(guest.handles.isEmpty());
    }

    @Test
    public void windowsEofRequiresASecondReadEvenForASmallFile() {
        var guest = new Guest();
        guest.windowsEof = true;
        new MigrationGuestConfigurationExchange(guest::call).reconcile(plan(), binding());
        var result =
                new MigrationGuestConfigurationExchange(guest::call).reconcile(plan(), binding());
        assertEquals("PENDING", result.get("status"));
        assertTrue(guest.handles.isEmpty());
    }

    @Test
    public void oversizedInputStopsAndClosesTheHandle() {
        var guest = new Guest();
        guest.windowsEof = true;
        guest.files.put(
                "/var/lib/ablestack-v2k/guest-config/binding-00000000-0000-0000-0000-000000000001.json",
                "x".repeat(65537));
        try {
            new MigrationGuestConfigurationExchange(guest::call).reconcile(plan(), binding());
            fail("Expected size limit");
        } catch (com.cloud.utils.exception.CloudRuntimeException expected) {
            assertEquals("GUEST_REPORT_LIMIT_OR_PARTIAL", expected.getMessage());
        }
        assertTrue(guest.handles.isEmpty());
    }

    @Test
    public void disabledFileRpcNeverWrites() {
        var guest = new Guest();
        guest.enabled = false;
        try {
            new MigrationGuestConfigurationExchange(guest::call).reconcile(plan(), binding());
            fail("Expected rejection");
        } catch (com.cloud.utils.exception.CloudRuntimeException expected) {
            assertTrue(guest.files.isEmpty());
        }
    }

    @Test
    public void cachedWrongBootCannotVerify() {
        var guest = new Guest();
        guest.files.put(
                "/var/lib/ablestack-v2k/guest-config/report.json",
                "{\"version\":1,\"status\":\"VERIFIED\",\"operation_id\":\"old\"}");
        var result =
                new MigrationGuestConfigurationExchange(guest::call).reconcile(plan(), binding());
        assertEquals("PENDING", result.get("status"));
        assertEquals("GUEST_REPORT_STALE_OR_UNBOUND", result.get("reason"));
        assertTrue(guest.handles.isEmpty());
    }

    @Test
    public void wrongInterfaceCannotBeVerified() {
        var report =
                JsonParser.parseString(
                                "{\"network\":{\"status\":\"VERIFIED\",\"interfaces\":[{\"mac\":\"00:50:56:39:90:02\",\"ifname\":\"ens192\",\"status\":\"VERIFIED\"}]}}")
                        .getAsJsonObject();
        try {
            MigrationGuestConfigurationExchange.verifyReport(report, plan(), binding());
            fail("Expected rejection");
        } catch (com.cloud.utils.exception.CloudRuntimeException expected) {
            assertTrue(expected.getMessage().contains("MISMATCH"));
        }
    }
}
