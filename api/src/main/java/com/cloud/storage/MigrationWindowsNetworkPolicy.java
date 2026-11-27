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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Bounded Windows IPv4 intent. Parsing never resolves names or calls a guest. */
public final class MigrationWindowsNetworkPolicy {
    public static final String FEATURE = "windows-network-ipv4-v1";

    private MigrationWindowsNetworkPolicy() {}

    private static void require(boolean valid) {
        if (!valid) throw new IllegalArgumentException("WINDOWS_NETWORK_UNSUPPORTED");
    }

    private static void keys(JsonObject value, String... names) {
        require(value.keySet().equals(Set.of(names)));
    }

    private static boolean bool(JsonObject value, String key) {
        require(value.get(key).isJsonPrimitive() && value.get(key).getAsJsonPrimitive().isBoolean());
        return value.get(key).getAsBoolean();
    }

    private static int integer(JsonObject value, String key, int min, int max) {
        var field = value.get(key);
        require(field.isJsonPrimitive() && field.getAsJsonPrimitive().isNumber()
                && field.getAsString().matches("[0-9]+"));
        int number = field.getAsInt();
        require(number >= min && number <= max);
        return number;
    }

    private static long ip(String text) {
        require(text.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}"));
        String[] parts = text.split("\\.");
        long result = 0;
        for (String part : parts) {
            int n = Integer.parseInt(part);
            require(n <= 255 && part.equals(Integer.toString(n)));
            result = result * 256 + n;
        }
        require(result != 0 && result != 0xffffffffL && (result >>> 24) != 127
                && (result >>> 24) < 224 && (result >>> 16) != 0xa9fe);
        return result;
    }

    public static String mac(String value) {
        require(value.matches("[0-9a-f][02468ace](:[0-9a-f]{2}){5}")
                && !value.equals("00:00:00:00:00:00"));
        return value;
    }

    public static void validate(JsonObject network) {
        keys(network, "adapter", "version", "interfaces");
        require("windows-nettcpip".equals(network.get("adapter").getAsString())
                && integer(network, "version", 1, 1) == 1);
        var nics = network.getAsJsonArray("interfaces");
        require(nics.size() >= 1 && nics.size() <= 8);
        Set<String> macs = new HashSet<>(), guids = new HashSet<>(), pnps = new HashSet<>();
        Set<Long> addresses = new HashSet<>();
        int defaults = 0;
        for (var value : nics) {
            var nic = value.getAsJsonObject();
            keys(nic, "source_nic_id", "source_mac", "source_interface_guid", "source_pnp_id",
                    "target_mac", "target_network_uuid", "admin_enabled", "ipv4",
                    "default_routes", "interface_metric", "dns");
            String m = mac(nic.get("source_mac").getAsString());
            require(macs.add(m) && m.equals(mac(nic.get("target_mac").getAsString())));
            require(guids.add(UUID.fromString(nic.get("source_interface_guid").getAsString()).toString()));
            UUID.fromString(nic.get("target_network_uuid").getAsString());
            String pnp = nic.get("source_pnp_id").getAsString();
            require(pnp.matches("[A-Za-z0-9_&.{}\\\\#-]{1,256}")
                    && pnps.add(pnp.toLowerCase(java.util.Locale.ROOT)));
            require(nic.get("source_nic_id").getAsString().length() >= 1
                    && nic.get("source_nic_id").getAsString().length() <= 128
                    && bool(nic, "admin_enabled"));
            var ipv4 = nic.getAsJsonObject("ipv4");
            keys(ipv4, "dhcp", "addresses");
            boolean dhcp = bool(ipv4, "dhcp");
            var ips = ipv4.getAsJsonArray("addresses");
            require(ips.size() <= 8 && dhcp == (ips.size() == 0));
            boolean primary = false;
            for (var a : ips) {
                var address = a.getAsJsonObject();
                keys(address, "address", "prefix_length", "skip_as_source");
                long number = ip(address.get("address").getAsString());
                int prefix = integer(address, "prefix_length", 1, 32);
                long mask = (0xffffffffL << (32 - prefix)) & 0xffffffffL;
                require(addresses.add(number) && (prefix > 30
                        || (number != (number & mask) && number != ((number & mask) | (~mask & 0xffffffffL)))));
                primary |= !bool(address, "skip_as_source");
            }
            require(dhcp || primary);
            var metric = nic.getAsJsonObject("interface_metric");
            keys(metric, "automatic", "value");
            if (bool(metric, "automatic")) require(metric.get("value").isJsonNull());
            else integer(metric, "value", 1, 9999);
            var routes = nic.getAsJsonArray("default_routes");
            require(routes.size() <= 1 && (!dhcp || routes.size() == 0));
            defaults += routes.size();
            for (var r : routes) {
                var route = r.getAsJsonObject();
                keys(route, "next_hop", "route_metric", "persistent");
                require(bool(route, "persistent"));
                integer(route, "route_metric", 0, 65535);
                long gateway = ip(route.get("next_hop").getAsString());
                boolean onLink = false;
                for (var a : ips) {
                    var address = a.getAsJsonObject();
                    long number = ip(address.get("address").getAsString());
                    long mask = (0xffffffffL << (32 - address.get("prefix_length").getAsInt())) & 0xffffffffL;
                    if ((number & mask) == (gateway & mask) && number != gateway) onLink = true;
                }
                require(onLink);
            }
            var dns = nic.getAsJsonObject("dns");
            keys(dns, "mode", "servers");
            String mode = dns.get("mode").getAsString();
            var servers = dns.getAsJsonArray("servers");
            require(List.of("static", "dhcp", "none").contains(mode) && servers.size() <= 8
                    && "static".equals(mode) == (servers.size() > 0)
                    && (!"dhcp".equals(mode) || dhcp));
            Set<Long> seen = new HashSet<>();
            for (var server : servers) require(seen.add(ip(server.getAsString())));
        }
        require(defaults <= 1);
    }

    private static String sorted(JsonArray values) {
        return java.util.stream.StreamSupport.stream(values.spliterator(), false).map(MigrationDiskControllerPolicy::canonical).sorted()
                .collect(java.util.stream.Collectors.joining(","));
    }

    private static void intent(JsonObject actual, JsonObject expected) {
        require(actual.getAsJsonObject("ipv4").get("dhcp").equals(expected.getAsJsonObject("ipv4").get("dhcp")));
        require(sorted(actual.getAsJsonObject("ipv4").getAsJsonArray("addresses"))
                .equals(sorted(expected.getAsJsonObject("ipv4").getAsJsonArray("addresses"))));
        require(sorted(actual.getAsJsonArray("default_routes")).equals(sorted(expected.getAsJsonArray("default_routes"))));
        for (String key : List.of("interface_metric", "dns")) require(actual.get(key).equals(expected.get(key)));
    }

    public static void verifyReport(JsonObject plan, JsonObject report, JsonObject binding) {
        try {
            for (String hash : List.of("network_reader_sha256", "network_helper_sha256"))
                require(plan.get(hash).equals(report.get(hash)));
            var network = report.getAsJsonObject("network");
            require("VERIFIED".equals(network.get("status").getAsString()));
            var expected = plan.getAsJsonObject("network").getAsJsonArray("interfaces");
            var actual = network.getAsJsonArray("interfaces");
            require(expected.size() == actual.size() && binding.getAsJsonArray("nics").size() == actual.size());
            Set<String> seen = new HashSet<>();
            for (var value : expected) {
                var want = value.getAsJsonObject();
                String mac = want.get("target_mac").getAsString();
                JsonObject found = null, bound = null;
                for (var item : actual) if (mac.equals(item.getAsJsonObject().get("target_mac").getAsString())) {
                    require(found == null); found = item.getAsJsonObject();
                }
                for (var item : binding.getAsJsonArray("nics"))
                    if (mac.equals(item.getAsJsonObject().get("mac").getAsString())) {
                        require(bound == null); bound = item.getAsJsonObject();
                    }
                require(found != null && bound != null && seen.add(mac)
                        && "VERIFIED".equals(found.get("status").getAsString())
                        && found.get("nic_uuid").equals(bound.get("id"))
                        && found.get("network_uuid").equals(bound.get("network_uuid"))
                        && found.get("network_uuid").equals(want.get("target_network_uuid")));
                UUID.fromString(found.get("interface_guid").getAsString());
                require(found.get("ifindex").getAsInt() > 0 && !found.get("pnp_id").getAsString().isBlank());
                intent(found, want);
                var observed = found.getAsJsonObject("observed");
                intent(observed.getAsJsonObject("intent"), want);
                require(found.get("interface_guid").equals(observed.get("interface_guid"))
                        && found.get("pnp_id").equals(observed.get("pnp_id"))
                        && found.get("ifindex").equals(observed.get("ifindex"))
                        && "Up".equals(observed.get("admin_status").getAsString())
                        && "Up".equals(observed.get("link_status").getAsString()));
                var addresses = observed.getAsJsonArray("addresses");
                if (want.getAsJsonObject("ipv4").get("dhcp").getAsBoolean()) {
                    boolean leased = false;
                    for (var a : addresses) {
                        var address = a.getAsJsonObject();
                        if ("Dhcp".equals(address.get("origin").getAsString())
                                && "Preferred".equals(address.get("state").getAsString())) {
                            ip(address.get("address").getAsString()); leased = true;
                        }
                    }
                    require(leased);
                } else for (var a : want.getAsJsonObject("ipv4").getAsJsonArray("addresses")) {
                    var address = a.getAsJsonObject(); int count = 0;
                    for (var b : addresses) {
                        var current = b.getAsJsonObject();
                        if (address.get("address").equals(current.get("address"))
                                && address.get("prefix_length").equals(current.get("prefix_length"))
                                && address.get("skip_as_source").equals(current.get("skip_as_source"))
                                && "Manual".equals(current.get("origin").getAsString())
                                && "Preferred".equals(current.get("state").getAsString())) count++;
                    }
                    require(count == 1);
                }
                var routes = observed.getAsJsonArray("routes");
                for (var r : want.getAsJsonArray("default_routes")) {
                    var route = r.getAsJsonObject(); int count = 0;
                    for (var item : routes) {
                        var current = item.getAsJsonObject();
                        if (route.get("next_hop").equals(current.get("next_hop"))
                                && route.get("route_metric").equals(current.get("route_metric"))) count++;
                    }
                    require(count == 1);
                }
                var dns = want.getAsJsonObject("dns");
                var servers = observed.getAsJsonArray("dns_servers");
                if ("dhcp".equals(dns.get("mode").getAsString())) {
                    require(servers.size() > 0); for (var server : servers) ip(server.getAsString());
                } else require(servers.equals(dns.get("servers")));
            }
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("GUEST_WINDOWS_NETWORK_REPORT_MISMATCH");
        }
    }
}
