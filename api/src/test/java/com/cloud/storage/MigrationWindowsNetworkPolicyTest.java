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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.Assert.*;

public class MigrationWindowsNetworkPolicyTest {
    private JsonObject fixture() throws Exception {
        try (var input = getClass().getResourceAsStream("/migration/windows-network-v2.json")) {
            return JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    @Test public void restoresOnlyEncodingLossWithTheSameFrozenHash() throws Exception {
        var p = fixture().getAsJsonObject("plan");
        var metric = p.getAsJsonObject("network").getAsJsonArray("interfaces").get(0).getAsJsonObject().getAsJsonObject("interface_metric");
        metric.addProperty("automatic", true);metric.add("value", com.google.gson.JsonNull.INSTANCE);
        p.remove("hash");p.addProperty("hash", MigrationGuestConfigurationPolicy.hash(p));
        String original = MigrationGuestConfigurationPolicy.canonical(p);
        String lossy = MigrationDiskControllerPolicy.canonical(p);
        assertNotEquals(original, lossy);
        var recovered = MigrationGuestConfigurationPolicy.parse(lossy);
        assertEquals(original, MigrationGuestConfigurationPolicy.canonical(recovered));
        assertEquals(p.get("hash"), recovered.get("hash"));
        var changed = JsonParser.parseString(lossy).getAsJsonObject();
        changed.getAsJsonObject("network").getAsJsonArray("interfaces").get(0).getAsJsonObject().getAsJsonObject("dns").getAsJsonArray("servers").set(0, new com.google.gson.JsonPrimitive("192.0.2.99"));
        try {MigrationGuestConfigurationPolicy.parse(changed.toString());fail("Changed policy accepted");}
        catch (com.cloud.exception.InvalidParameterValueException expected) {}
    }

    @Test public void acceptsFrozenV2PlanAndVerifiedNetwork() throws Exception {
        var f = fixture();
        var p = MigrationGuestConfigurationPolicy.parse(MigrationGuestConfigurationPolicy.canonical(f.get("plan")));
        assertEquals(2, p.get("version").getAsInt());
        MigrationWindowsNetworkPolicy.verifyReport(p, f.getAsJsonObject("report"), f.getAsJsonObject("binding"));
    }

    @Test public void rejectsDataOnlyOrForeignOrStaleNetworkEvidence() throws Exception {
        for (String key : List.of("network", "network_reader_sha256", "network_helper_sha256")) {
            var f = fixture(); f.getAsJsonObject("report").remove(key);
            try {
                MigrationWindowsNetworkPolicy.verifyReport(f.getAsJsonObject("plan"), f.getAsJsonObject("report"), f.getAsJsonObject("binding"));
                fail("Missing v2 field accepted: " + key);
            } catch (IllegalArgumentException expected) {}
        }
        for (String field : List.of("nic_uuid", "network_uuid", "target_mac")) {
            var f = fixture();
            f.getAsJsonObject("report").getAsJsonObject("network").getAsJsonArray("interfaces").get(0).getAsJsonObject().addProperty(field, "foreign");
            try {
                MigrationWindowsNetworkPolicy.verifyReport(f.getAsJsonObject("plan"), f.getAsJsonObject("report"), f.getAsJsonObject("binding"));
                fail("Foreign NIC accepted: " + field);
            } catch (IllegalArgumentException expected) {}
        }
    }

    @Test public void independentlyChecksAddressGatewayDnsAndDad() throws Exception {
        for (String kind : List.of("address", "gateway", "dns", "dad", "dhcp", "metric")) {
            var f = fixture();
            var n = f.getAsJsonObject("report").getAsJsonObject("network").getAsJsonArray("interfaces").get(0).getAsJsonObject();
            switch (kind) {
                case "address": n.getAsJsonObject("observed").getAsJsonArray("addresses").get(0).getAsJsonObject().addProperty("prefix_length", 25); break;
                case "gateway": n.getAsJsonObject("observed").getAsJsonArray("routes").get(0).getAsJsonObject().addProperty("next_hop", "192.0.2.2"); break;
                case "dns": var servers=n.getAsJsonObject("dns").getAsJsonArray("servers"); var first=servers.remove(0); servers.add(first); break;
                case "dad": n.getAsJsonObject("observed").getAsJsonArray("addresses").get(0).getAsJsonObject().addProperty("state", "Duplicate"); break;
                case "dhcp": n.getAsJsonObject("ipv4").addProperty("dhcp", true); break;
                case "metric": n.getAsJsonObject("interface_metric").addProperty("value", 999); break;
                default: fail();
            }
            try {
                MigrationWindowsNetworkPolicy.verifyReport(f.getAsJsonObject("plan"), f.getAsJsonObject("report"), f.getAsJsonObject("binding"));
                fail("Incorrect network accepted: " + kind);
            } catch (IllegalArgumentException expected) {}
        }
    }

    @Test public void v1WindowsDataDoesNotClaimV2Network() throws Exception {
        var p=fixture().getAsJsonObject("plan");
        p.addProperty("version", 1); p.addProperty("helper_version", 1);
        p.remove("hash");p.addProperty("hash", MigrationDiskControllerPolicy.hash(p));
        try {MigrationGuestConfigurationPolicy.parse(MigrationDiskControllerPolicy.canonical(p));fail("v1 claimed network");}
        catch (com.cloud.exception.InvalidParameterValueException expected) {}
        p.remove("network");p.remove("required_features");p.remove("network_reader_sha256");p.remove("network_helper_sha256");
        p.remove("hash");p.addProperty("hash", MigrationDiskControllerPolicy.hash(p));
        assertEquals(1, MigrationGuestConfigurationPolicy.parse(MigrationDiskControllerPolicy.canonical(p)).get("version").getAsInt());
    }
}
