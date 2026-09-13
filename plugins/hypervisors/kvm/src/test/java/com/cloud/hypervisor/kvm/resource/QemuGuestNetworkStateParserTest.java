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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

import org.apache.commons.io.IOUtils;
import org.junit.Test;

import com.cloud.agent.api.VmGuestIpAddress;
import com.cloud.agent.api.VmGuestNetworkInterface;
import com.cloud.agent.api.VmGuestNetworkState;
import com.cloud.agent.api.VmGuestRoute;
import com.cloud.hypervisor.kvm.resource.QemuGuestNetworkStateParser.RouteParseResult;

public class QemuGuestNetworkStateParserTest {
    private final QemuGuestNetworkStateParser parser = new QemuGuestNetworkStateParser();

    @Test
    public void testWindowsQgaRouteMaskAndInterfaceAliases() {
        String json = "{\"return\":["
                + "{\"version\":4,\"iface\":\"Ethernet Instance 0\",\"destination\":\"0.0.0.0\","
                + "\"gateway\":\"10.10.0.1\",\"mask\":\"0.0.0.0\",\"metric\":15},"
                + "{\"version\":4,\"iface\":\"Ethernet Instance 0\",\"destination\":\"10.10.0.0\","
                + "\"gateway\":\"0.0.0.0\",\"mask\":\"255.255.0.0\"}]}";
        List<VmGuestRoute> routes = parser.parseRoutes(json).getRoutes();
        assertEquals(2, routes.size());
        assertEquals(Integer.valueOf(0), routes.get(0).getPrefix());
        assertTrue(routes.get(0).isDefaultRoute());
        assertEquals("Ethernet Instance 0", routes.get(0).getInterfaceName());
        assertEquals("10.10.0.1", routes.get(0).getGateway());
        assertEquals(Integer.valueOf(16), routes.get(1).getPrefix());
        assertFalse(routes.get(1).isDefaultRoute());
        assertNull(routes.get(1).getGateway());
    }

    @Test
    public void testWindowsQgaIpv6RoutePrefixAndNextHopAliases() {
        String json = "{\"return\":[{\"version\":6,\"iface\":\"Ethernet Instance 0\","
                + "\"destination\":\"::\",\"desprefixlen\":\"0\",\"nexthop\":\"fe80::1\"},"
                + "{\"version\":6,\"destination\":\"fd12::\",\"desprefixlen\":\"64\",\"nexthop\":\"::\"}]}";
        List<VmGuestRoute> routes = parser.parseRoutes(json).getRoutes();
        assertEquals("ipv6", routes.get(0).getFamily());
        assertTrue(routes.get(0).isDefaultRoute());
        assertEquals(Integer.valueOf(0), routes.get(0).getPrefix());
        assertEquals("fe80::1", routes.get(0).getGateway());
        assertEquals("Ethernet Instance 0", routes.get(0).getInterfaceName());
        assertEquals(Integer.valueOf(64), routes.get(1).getPrefix());
        assertNull(routes.get(1).getGateway());
    }

    @Test
    public void testInvalidMaskDoesNotInventPrefixOrDefaultRoute() {
        for (String mask : new String[] {"invalid", "255.0.255.0"}) {
            VmGuestRoute route = parser.parseRoutes("{\"return\":[{\"destination\":\"0.0.0.0\",\"mask\":\""
                    + mask + "\"}]}").getRoutes().get(0);
            assertNull(route.getPrefix());
            assertFalse(route.isDefaultRoute());
        }
    }

    @Test
    public void testCapabilitiesRespectEnabledFlag() throws IOException {
        VmGuestNetworkState state = new VmGuestNetworkState("vm-1");

        boolean enabled = parser.parseCapabilities(readFixture("guest-info-capabilities.json"), state);

        assertTrue(enabled);
        assertEquals("8.2.0", state.getAgentVersion());
        assertTrue(state.getCapabilities().get("guest-network-get-interfaces"));
        assertFalse(state.getCapabilities().get("guest-get-fsinfo"));
        assertTrue(state.getCapabilities().get("guest-network-get-route"));
        assertTrue(state.getCapabilities().get("guest-exec"));
    }

    @Test
    public void testLinuxInterfacesPreserveMultipleIpv4Ipv6LoopbackAndMaclessInterface() throws IOException {
        List<VmGuestNetworkInterface> interfaces = parser.parseInterfaces(
                readFixture("guest-network-get-interfaces-linux.json"),
                Collections.singletonMap("52-54-00-ab-cd-01", "cloud-nic-linux"));

        assertEquals(3, interfaces.size());
        VmGuestNetworkInterface loopback = interfaces.get(0);
        assertTrue(loopback.isLoopback());
        assertEquals(2, loopback.getAddresses().size());

        VmGuestNetworkInterface eth0 = interfaces.get(1);
        assertEquals("52:54:00:ab:cd:01", eth0.getNormalizedMacAddress());
        assertEquals("cloud-nic-linux", eth0.getCloudNicId());
        assertEquals(4, eth0.getAddresses().size());
        assertAddress(eth0.getAddresses().get(0), "ipv4", "10.10.22.101", 24, "private");
        assertAddress(eth0.getAddresses().get(1), "ipv4", "10.10.22.102", 24, "private");
        assertAddress(eth0.getAddresses().get(2), "ipv6", "2001:db8:22::101", 64, "global");
        assertAddress(eth0.getAddresses().get(3), "ipv6", "fe80::5054:ff:feab:cd01", 64, "link-local");

        VmGuestNetworkInterface tunnel = interfaces.get(2);
        assertEquals("tun0", tunnel.getName());
        assertNull(tunnel.getHardwareAddress());
        assertNull(tunnel.getNormalizedMacAddress());
        assertNull(tunnel.getCloudNicId());
        assertEquals(1, tunnel.getAddresses().size());
    }

    @Test
    public void testWindowsMacNormalizationAndLoopbackDetection() throws IOException {
        List<VmGuestNetworkInterface> interfaces = parser.parseInterfaces(
                readFixture("guest-network-get-interfaces-windows.json"),
                Collections.singletonMap("52:54:00:ab:cd:02", "cloud-nic-windows"));

        VmGuestNetworkInterface ethernet = interfaces.get(0);
        assertEquals("52-54-00-AB-CD-02", ethernet.getHardwareAddress());
        assertEquals("52:54:00:ab:cd:02", ethernet.getNormalizedMacAddress());
        assertEquals("cloud-nic-windows", ethernet.getCloudNicId());
        assertEquals(3, ethernet.getAddresses().size());
        assertTrue(interfaces.get(1).isLoopback());
    }

    @Test
    public void testDisabledInterfaceCapabilityIsNotCollectable() {
        String json = "{\"return\":{\"version\":\"8.2.0\",\"supported_commands\":["
                + "{\"name\":\"guest-network-get-interfaces\",\"enabled\":false}]}}";

        assertFalse(parser.parseCapabilities(json, new VmGuestNetworkState("vm-1")));
    }

    @Test
    public void testStandardRoutesNormalizeIpv4Ipv6AndDefaults() throws IOException {
        RouteParseResult result = parser.parseRoutes(readFixture("guest-network-get-route-standard.json"));

        assertEquals(4, result.getRoutes().size());
        assertFalse(result.isTruncated());
        assertRoute(result.getRoutes().get(0), "ipv4", "0.0.0.0", 0,
                "10.10.22.1", "eth0", true);
        assertRoute(result.getRoutes().get(1), "ipv4", "10.10.22.0", 24,
                null, "eth0", false);
        assertRoute(result.getRoutes().get(2), "ipv6", "::", 0,
                "fe80::1", "eth0", true);
    }

    @Test
    public void testLinuxFallbackRoutesPreserveBothFamilies() throws IOException {
        RouteParseResult ipv4 = parser.parseLinuxRoutes(
                readFixture("guest-exec-linux-route-v4.json"), "ipv4");
        RouteParseResult ipv6 = parser.parseLinuxRoutes(
                readFixture("guest-exec-linux-route-v6.json"), "ipv6");

        assertEquals(2, ipv4.getRoutes().size());
        assertEquals(2, ipv6.getRoutes().size());
        assertRoute(ipv4.getRoutes().get(0), "ipv4", "0.0.0.0", 0,
                "10.10.22.1", "eth0", true);
        assertRoute(ipv6.getRoutes().get(0), "ipv6", "::", 0,
                "fe80::1", "eth0", true);
    }

    @Test
    public void testWindowsFallbackNormalizesDestinationAndOnLinkGateway() throws IOException {
        RouteParseResult result = parser.parseWindowsRoutes(
                readFixture("guest-exec-windows-route.json"));

        assertEquals(3, result.getRoutes().size());
        assertRoute(result.getRoutes().get(0), "ipv4", "0.0.0.0", 0,
                "10.10.22.1", "Ethernet", true);
        assertRoute(result.getRoutes().get(1), "ipv6", "::", 0,
                "fe80::1", "Ethernet", true);
        assertNull(result.getRoutes().get(2).getGateway());
    }

    @Test
    public void testOsInfoUsesStableLowercaseIdentifier() {
        assertEquals("linux", parser.parseOsId(
                "{\"return\":{\"id\":\"Linux\",\"kernel-name\":\"Linux\"}}"));
        assertEquals("mswindows", parser.parseOsId(
                "{\"return\":{\"id\":\"mswindows\",\"kernel-name\":\"Windows\"}}"));
    }

    @Test
    public void testOsInfoPreservesIndependentFieldsWhenKernelNameIsMissing() throws IOException {
        QemuGuestOsInfo osInfo = parser.parseOsInfo(
                readFixture("guest-get-osinfo-debian.json"));

        assertEquals("debian", osInfo.getId());
        assertNull(osInfo.getKernelName());
        assertEquals("Debian GNU/Linux", osInfo.getName());
        assertEquals("Debian GNU/Linux 12 (bookworm)", osInfo.getPrettyName());
        assertEquals("debian", parser.parseOsId(
                readFixture("guest-get-osinfo-debian.json")));
    }

    @Test
    public void testStandardRouteCountIsBoundedAndReportedAsTruncated() {
        StringBuilder json = new StringBuilder("{\"return\":[");
        for (int index = 0; index < QemuGuestNetworkStateParser.MAX_ROUTES + 2; index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append("{\"family\":\"ipv4\",\"destination\":\"10.10.22.0\",\"prefix\":24}");
        }
        json.append("]}");

        RouteParseResult result = parser.parseRoutes(json.toString());

        assertEquals(QemuGuestNetworkStateParser.MAX_ROUTES, result.getRoutes().size());
        assertEquals(QemuGuestNetworkStateParser.MAX_ROUTES + 2, result.getOriginalCount());
        assertTrue(result.isTruncated());
    }

    private void assertAddress(VmGuestIpAddress address, String family, String value, int prefix, String scope) {
        assertEquals(family, address.getFamily());
        assertEquals(value, address.getAddress());
        assertEquals(Integer.valueOf(prefix), address.getPrefix());
        assertEquals(scope, address.getScope());
    }

    private void assertRoute(VmGuestRoute route, String family, String destination, int prefix,
            String gateway, String interfaceName, boolean defaultRoute) {
        assertEquals(family, route.getFamily());
        assertEquals(destination, route.getDestination());
        assertEquals(Integer.valueOf(prefix), route.getPrefix());
        assertEquals(gateway, route.getGateway());
        assertEquals(interfaceName, route.getInterfaceName());
        assertEquals(defaultRoute, route.isDefaultRoute());
    }

    private String readFixture(String name) throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/qga/" + name)) {
            if (input == null) {
                throw new IOException("Missing fixture: " + name);
            }
            return IOUtils.toString(input, StandardCharsets.UTF_8);
        }
    }
}
