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

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.cloud.agent.api.VmGuestIpAddress;
import com.cloud.agent.api.VmGuestNetworkInterface;
import com.cloud.agent.api.VmGuestNetworkState;
import com.cloud.agent.api.VmGuestRoute;
import com.cloud.utils.net.NetUtils;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class QemuGuestNetworkStateParser {
    static final String INTERFACE_CAPABILITY = "guest-network-get-interfaces";
    static final String ROUTE_CAPABILITY = "guest-network-get-route";
    public static final int MAX_ROUTES = 4096;

    public boolean parseCapabilities(String json, VmGuestNetworkState state) {
        JsonObject response = parseResponseObject(json);
        JsonObject result = requireObject(response, "return");
        JsonElement version = result.get("version");
        if (version != null && !version.isJsonNull()) {
            state.setAgentVersion(version.getAsString());
        }

        JsonArray commands = requireArray(result, "supported_commands");
        boolean interfaceCapabilityEnabled = false;
        for (JsonElement element : commands) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject command = element.getAsJsonObject();
            JsonElement nameElement = command.get("name");
            if (nameElement == null || nameElement.isJsonNull()) {
                continue;
            }
            String name = nameElement.getAsString();
            boolean enabled = !command.has("enabled") || command.get("enabled").getAsBoolean();
            state.putCapability(name, enabled);
            if (INTERFACE_CAPABILITY.equals(name)) {
                interfaceCapabilityEnabled = enabled;
            }
        }
        return interfaceCapabilityEnabled;
    }

    public List<VmGuestNetworkInterface> parseInterfaces(String json, Map<String, String> cloudNicIdsByMac) {
        JsonObject response = parseResponseObject(json);
        JsonArray interfaces = requireArray(response, "return");
        Map<String, String> normalizedCloudNicIds = normalizeCloudNicIds(cloudNicIdsByMac);
        List<VmGuestNetworkInterface> parsedInterfaces = new ArrayList<>();

        for (JsonElement element : interfaces) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject source = element.getAsJsonObject();
            VmGuestNetworkInterface networkInterface = new VmGuestNetworkInterface();
            networkInterface.setName(readString(source, "name"));
            String hardwareAddress = readString(source, "hardware-address");
            networkInterface.setHardwareAddress(hardwareAddress);
            String normalizedMacAddress = normalizeMacAddress(hardwareAddress);
            networkInterface.setNormalizedMacAddress(normalizedMacAddress);
            networkInterface.setCloudNicId(normalizedMacAddress == null ? null : normalizedCloudNicIds.get(normalizedMacAddress));

            List<VmGuestIpAddress> addresses = parseAddresses(source.get("ip-addresses"));
            networkInterface.setAddresses(addresses);
            networkInterface.setLoopback(isLoopback(networkInterface.getName(), addresses));
            parsedInterfaces.add(networkInterface);
        }
        return parsedInterfaces;
    }

    public RouteParseResult parseRoutes(String json) {
        JsonObject response = parseResponseObject(json);
        return parseRouteArray(requireArray(response, "return"), null, RouteSource.STANDARD);
    }

    public RouteParseResult parseLinuxRoutes(String json, String family) {
        JsonElement root = new JsonParser().parse(json);
        if (!root.isJsonArray()) {
            throw new IllegalArgumentException("Linux route output must be a JSON array");
        }
        return parseRouteArray(root.getAsJsonArray(), normalizeRouteFamily(family, null, null), RouteSource.LINUX);
    }

    public RouteParseResult parseWindowsRoutes(String json) {
        JsonElement root = new JsonParser().parse(json);
        JsonArray routes = new JsonArray();
        if (root.isJsonArray()) {
            root.getAsJsonArray().forEach(routes::add);
        } else if (root.isJsonObject()) {
            routes.add(root);
        } else {
            throw new IllegalArgumentException("Windows route output must be a JSON object or array");
        }
        return parseRouteArray(routes, null, RouteSource.WINDOWS);
    }

    public String parseOsId(String json) {
        QemuGuestOsInfo osInfo = parseOsInfo(json);
        String osId = firstNonBlank(osInfo.getId(), osInfo.getKernelName(), osInfo.getName());
        return osId == null ? null : osId.toLowerCase(Locale.ROOT);
    }

    public QemuGuestOsInfo parseOsInfo(String json) {
        JsonObject response = parseResponseObject(json);
        JsonObject result = requireObject(response, "return");
        return new QemuGuestOsInfo(
                readString(result, "id"),
                readString(result, "kernel-name"),
                readString(result, "name"),
                readString(result, "pretty-name"));
    }

    private RouteParseResult parseRouteArray(JsonArray sourceRoutes, String forcedFamily, RouteSource source) {
        List<VmGuestRoute> routes = new ArrayList<>();
        boolean truncated = false;
        for (JsonElement element : sourceRoutes) {
            if (routes.size() >= MAX_ROUTES) {
                truncated = true;
                break;
            }
            if (!element.isJsonObject()) {
                continue;
            }
            VmGuestRoute route = parseRoute(element.getAsJsonObject(), forcedFamily, source);
            if (route != null) {
                routes.add(route);
            }
        }
        return new RouteParseResult(routes, truncated, sourceRoutes.size());
    }

    private VmGuestRoute parseRoute(JsonObject source, String forcedFamily, RouteSource routeSource) {
        String destinationWithPrefix = readAnyString(source,
                routeSource == RouteSource.LINUX ? "dst" : "destination",
                "destination-prefix", "DestinationPrefix", "network");
        String destination = destinationWithPrefix;
        Integer prefix = readAnyInteger(source, "prefix", "prefix-length", "PrefixLength", "desprefixlen");
        if (prefix == null) {
            String mask = readString(source, "mask");
            if (NetUtils.isValidIp4(mask)) {
                int maskPrefix = (int) NetUtils.getCidrSize(mask);
                if (mask.equals(NetUtils.getCidrNetmask(maskPrefix))) {
                    prefix = maskPrefix;
                }
            }
        }
        if (destinationWithPrefix != null && destinationWithPrefix.contains("/")) {
            String[] parts = destinationWithPrefix.split("/", 2);
            destination = parts[0];
            if (prefix == null) {
                prefix = parseInteger(parts[1]);
            }
        }
        String gateway = readAnyString(source,
                routeSource == RouteSource.LINUX ? "gateway" : "gateway",
                "next-hop", "NextHop", "nexthop");
        String family = forcedFamily;
        if (family == null) {
            family = normalizeRouteFamily(readAnyString(source,
                    "family", "type", "ip-address-type", "AddressFamily", "version"), destination, gateway);
        }
        if (family == null) {
            return null;
        }

        boolean defaultRoute = isDefaultDestination(destination, prefix)
                || readAnyBoolean(source, "default", "default-route", "DefaultRoute");
        if (defaultRoute) {
            destination = "ipv6".equals(family) ? "::" : "0.0.0.0";
            prefix = 0;
        }
        if (destination == null) {
            return null;
        }

        VmGuestRoute route = new VmGuestRoute();
        route.setFamily(family);
        route.setDestination(destination);
        route.setPrefix(prefix);
        route.setGateway(normalizeGateway(gateway));
        route.setInterfaceName(readAnyString(source,
                routeSource == RouteSource.LINUX ? "dev" : "interface",
                "ifname", "interface-name", "InterfaceAlias", "InterfaceIndex", "iface"));
        route.setMetric(readAnyInteger(source, "metric", "RouteMetric"));
        route.setTable(readAnyString(source, "table", "PolicyStore", "Store"));
        route.setProtocol(readAnyString(source, "protocol", "proto", "Protocol"));
        route.setScope(readAnyString(source, "scope", "Scope"));
        route.setDefaultRoute(defaultRoute);
        return route;
    }

    private String normalizeRouteFamily(String family, String destination, String gateway) {
        if (family != null) {
            String normalized = family.toLowerCase(Locale.ROOT);
            if ("ipv4".equals(normalized) || "inet".equals(normalized) || "2".equals(normalized) || "4".equals(normalized)) {
                return "ipv4";
            }
            if ("ipv6".equals(normalized) || "inet6".equals(normalized) || "23".equals(normalized) || "6".equals(normalized)) {
                return "ipv6";
            }
        }
        String address = destination;
        if (address == null || "default".equalsIgnoreCase(address)) {
            address = gateway;
        }
        if (address != null && address.contains("/")) {
            address = address.substring(0, address.indexOf('/'));
        }
        if (NetUtils.isValidIp4(address)) {
            return "ipv4";
        }
        if (NetUtils.isValidIp6(address)) {
            return "ipv6";
        }
        return null;
    }

    private boolean isDefaultDestination(String destination, Integer prefix) {
        if (destination == null) {
            return false;
        }
        return "default".equalsIgnoreCase(destination)
                || (Integer.valueOf(0).equals(prefix)
                    && ("0.0.0.0".equals(destination) || "::".equals(destination)));
    }

    private String normalizeGateway(String gateway) {
        if (gateway == null || "0.0.0.0".equals(gateway) || "::".equals(gateway)
                || "on-link".equalsIgnoreCase(gateway)) {
            return null;
        }
        return gateway;
    }

    private List<VmGuestIpAddress> parseAddresses(JsonElement addressElement) {
        List<VmGuestIpAddress> addresses = new ArrayList<>();
        if (addressElement == null || !addressElement.isJsonArray()) {
            return addresses;
        }
        for (JsonElement element : addressElement.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject source = element.getAsJsonObject();
            String address = readString(source, "ip-address");
            String family = normalizeFamily(readString(source, "ip-address-type"), address);
            if (address == null || family == null) {
                continue;
            }
            Integer prefix = source.has("prefix") && !source.get("prefix").isJsonNull()
                    ? source.get("prefix").getAsInt() : null;
            addresses.add(new VmGuestIpAddress(family, address, prefix, resolveScope(address)));
        }
        return addresses;
    }

    private String normalizeFamily(String family, String address) {
        if (family != null) {
            String normalized = family.toLowerCase(Locale.ROOT);
            if ("ipv4".equals(normalized) || "ipv6".equals(normalized)) {
                return normalized;
            }
        }
        if (address == null) {
            return null;
        }
        if (NetUtils.isValidIp4(address)) {
            return "ipv4";
        }
        if (NetUtils.isValidIp6(address)) {
            return "ipv6";
        }
        return null;
    }

    private String resolveScope(String address) {
        if (!NetUtils.isValidIp4(address) && !NetUtils.isValidIp6(address)) {
            return "unknown";
        }
        try {
            InetAddress inetAddress = InetAddress.getByName(address);
            if (inetAddress.isLoopbackAddress()) {
                return "loopback";
            }
            if (inetAddress.isLinkLocalAddress()) {
                return "link-local";
            }
            if (inetAddress.isMulticastAddress()) {
                return "multicast";
            }
            if (inetAddress.isSiteLocalAddress()) {
                return "private";
            }
            return "global";
        } catch (UnknownHostException e) {
            return "unknown";
        }
    }

    private boolean isLoopback(String interfaceName, List<VmGuestIpAddress> addresses) {
        if (interfaceName != null) {
            String normalizedName = interfaceName.toLowerCase(Locale.ROOT);
            if ("lo".equals(normalizedName) || "lo0".equals(normalizedName) || normalizedName.contains("loopback")) {
                return true;
            }
        }
        return !addresses.isEmpty() && addresses.stream().allMatch(address -> "loopback".equals(address.getScope()));
    }

    private Map<String, String> normalizeCloudNicIds(Map<String, String> cloudNicIdsByMac) {
        Map<String, String> normalized = new LinkedHashMap<>();
        if (cloudNicIdsByMac == null) {
            return normalized;
        }
        cloudNicIdsByMac.forEach((macAddress, cloudNicId) -> {
            String normalizedMacAddress = normalizeMacAddress(macAddress);
            if (normalizedMacAddress != null) {
                normalized.put(normalizedMacAddress, cloudNicId);
            }
        });
        return normalized;
    }

    private String normalizeMacAddress(String macAddress) {
        if (macAddress == null || !NetUtils.isValidMac(macAddress)) {
            return null;
        }
        return NetUtils.standardizeMacAddress(macAddress);
    }

    private JsonObject parseResponseObject(String json) {
        JsonElement root = new JsonParser().parse(json);
        if (!root.isJsonObject()) {
            throw new IllegalArgumentException("QGA response must be a JSON object");
        }
        return root.getAsJsonObject();
    }

    private JsonObject requireObject(JsonObject object, String member) {
        JsonElement element = object.get(member);
        if (element == null || !element.isJsonObject()) {
            throw new IllegalArgumentException("QGA response member '" + member + "' must be an object");
        }
        return element.getAsJsonObject();
    }

    private JsonArray requireArray(JsonObject object, String member) {
        JsonElement element = object.get(member);
        if (element == null || !element.isJsonArray()) {
            throw new IllegalArgumentException("QGA response member '" + member + "' must be an array");
        }
        return element.getAsJsonArray();
    }

    private String readString(JsonObject object, String member) {
        JsonElement element = object.get(member);
        return element == null || element.isJsonNull() ? null : element.getAsString();
    }

    private String readAnyString(JsonObject object, String... members) {
        for (String member : members) {
            String value = readString(object, member);
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }

    private Integer readAnyInteger(JsonObject object, String... members) {
        for (String member : members) {
            JsonElement element = object.get(member);
            if (element == null || element.isJsonNull()) {
                continue;
            }
            try {
                return element.getAsInt();
            } catch (NumberFormatException | UnsupportedOperationException e) {
                return parseInteger(element.getAsString());
            }
        }
        return null;
    }

    private boolean readAnyBoolean(JsonObject object, String... members) {
        for (String member : members) {
            JsonElement element = object.get(member);
            if (element == null || element.isJsonNull()) {
                continue;
            }
            try {
                return element.getAsBoolean();
            } catch (RuntimeException e) {
                return Boolean.parseBoolean(element.getAsString());
            }
        }
        return false;
    }

    private Integer parseInteger(String value) {
        try {
            return value == null ? null : Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private enum RouteSource {
        STANDARD,
        LINUX,
        WINDOWS
    }

    public static final class RouteParseResult {
        private final List<VmGuestRoute> routes;
        private final boolean truncated;
        private final int originalCount;

        RouteParseResult(List<VmGuestRoute> routes, boolean truncated, int originalCount) {
            this.routes = routes == null ? Collections.emptyList() : routes;
            this.truncated = truncated;
            this.originalCount = originalCount;
        }

        public List<VmGuestRoute> getRoutes() {
            return routes;
        }

        public boolean isTruncated() {
            return truncated;
        }

        public int getOriginalCount() {
            return originalCount;
        }
    }
}
