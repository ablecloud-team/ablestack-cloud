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
package com.cloud.hypervisor.kvm.resource.wrapper;

import com.cloud.hypervisor.kvm.resource.KvmVmOperationGuard;
import com.cloud.hypervisor.kvm.resource.KvmBoundedStats;
import org.junit.After;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.commons.io.IOUtils;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.libvirt.Connect;
import org.libvirt.Domain;
import org.libvirt.DomainInfo;
import org.libvirt.DomainInfo.DomainState;
import org.libvirt.LibvirtException;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.agent.api.GetVmGuestNetworkStateAnswer;
import com.cloud.agent.api.GetVmGuestNetworkStateCommand;
import com.cloud.agent.api.VmGuestNetworkState;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;

@RunWith(MockitoJUnitRunner.class)
public class LibvirtGetVmGuestNetworkStateCommandWrapperTest {
    private static final String VM_ONE = "vm-one";
    private static final String VM_TWO = "vm-two";

    @Mock
    private LibvirtComputingResource resource;
    @Mock
    private LibvirtUtilitiesHelper helper;
    @Mock
    private Connect connectionOne;
    @Mock
    private Connect connectionTwo;
    @Mock
    private Domain domainOne;
    @Mock
    private Domain domainTwo;

    private LibvirtGetVmGuestNetworkStateCommandWrapper wrapper;

    // These tests retain the existing libvirt fixtures; OS locking and bounded CLI
    // transport are exercised separately by KvmVmOperationGuardTest/KvmBoundedStatsTest.
    private org.mockito.MockedStatic<KvmVmOperationGuard> operationGuard;
    private org.mockito.MockedStatic<KvmBoundedStats> boundedStats;

    @Before
    public void mockMonitoringTransport() throws Exception {
        operationGuard = org.mockito.Mockito.mockStatic(KvmVmOperationGuard.class);
        operationGuard.when(() -> KvmVmOperationGuard.begin(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(org.mockito.Mockito.mock(KvmVmOperationGuard.class));
        operationGuard.when(() -> KvmVmOperationGuard.collect(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> ((java.util.concurrent.Callable<?>) call.getArgument(2)).call());
        operationGuard.when(() -> KvmVmOperationGuard.guestCommand(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(call -> ((org.libvirt.Domain) call.getArgument(0)).qemuAgentCommand(call.getArgument(1), call.getArgument(2), 0));
        boundedStats = org.mockito.Mockito.mockStatic(KvmBoundedStats.class);
        boundedStats.when(() -> KvmBoundedStats.info(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> ((org.libvirt.Domain) call.getArgument(0)).getInfo());
        boundedStats.when(() -> KvmBoundedStats.block(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(call -> ((org.libvirt.Domain) call.getArgument(0)).blockStats(call.getArgument(1)));
        boundedStats.when(() -> KvmBoundedStats.network(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(call -> ((org.libvirt.Domain) call.getArgument(0)).interfaceStats(call.getArgument(1)));
    }

    @After
    public void closeMonitoringTransport() {
        boundedStats.close();
        operationGuard.close();
    }

    @Before
    public void setUp() throws LibvirtException {
        wrapper = new LibvirtGetVmGuestNetworkStateCommandWrapper();
        when(resource.getLibvirtUtilitiesHelper()).thenReturn(helper);
        when(helper.getConnectionByVmName(VM_ONE)).thenReturn(connectionOne);
        when(helper.getConnectionByVmName(VM_TWO)).thenReturn(connectionTwo);
        when(resource.getDomain(connectionOne, VM_ONE)).thenReturn(domainOne);
        when(resource.getDomain(connectionTwo, VM_TWO)).thenReturn(domainTwo);

        DomainInfo running = new DomainInfo();
        running.state = DomainState.VIR_DOMAIN_RUNNING;
        when(domainOne.getInfo()).thenReturn(running);
        when(domainTwo.getInfo()).thenReturn(running);
    }

    @Test
    public void testNicMappingIsScopedToEachVm() throws Exception {
        String guestInfo = readFixture("guest-info-capabilities.json");
        when(domainOne.qemuAgentCommand(anyString(), eq(3), eq(0))).thenReturn(
                guestInfo, readFixture("guest-network-get-interfaces-linux.json"));
        when(domainTwo.qemuAgentCommand(anyString(), eq(3), eq(0))).thenReturn(
                guestInfo, readFixture("guest-network-get-interfaces-windows.json"));

        Map<String, String> vmOneNics = new LinkedHashMap<>();
        vmOneNics.put("52:54:00:ab:cd:01", "vm-one-nic");
        vmOneNics.put("52:54:00:ab:cd:02", "must-not-cross-vm-boundary");
        Map<String, Map<String, String>> nicIds = new LinkedHashMap<>();
        nicIds.put(VM_ONE, vmOneNics);
        nicIds.put(VM_TWO, Collections.emptyMap());
        GetVmGuestNetworkStateCommand command = new GetVmGuestNetworkStateCommand(
                Arrays.asList(VM_ONE, VM_TWO), nicIds);

        GetVmGuestNetworkStateAnswer answer = (GetVmGuestNetworkStateAnswer) wrapper.execute(command, resource);

        assertTrue(answer.getResult());
        assertTrue(answer.getErrors().isEmpty());
        assertEquals("vm-one-nic", answer.getStates().get(VM_ONE).getInterfaces().get(1).getCloudNicId());
        assertNull(answer.getStates().get(VM_TWO).getInterfaces().get(0).getCloudNicId());
        verify(domainOne).free();
        verify(domainTwo).free();
    }

    @Test
    public void testMalformedResponseForOneVmDoesNotFailOtherVm() throws Exception {
        String guestInfo = readFixture("guest-info-capabilities.json");
        when(domainOne.qemuAgentCommand(anyString(), eq(3), eq(0))).thenReturn(guestInfo, "{malformed");
        when(domainTwo.qemuAgentCommand(anyString(), eq(3), eq(0))).thenReturn(
                guestInfo, readFixture("guest-network-get-interfaces-windows.json"));
        GetVmGuestNetworkStateCommand command = new GetVmGuestNetworkStateCommand(
                Arrays.asList(VM_ONE, VM_TWO), Collections.emptyMap());

        GetVmGuestNetworkStateAnswer answer = (GetVmGuestNetworkStateAnswer) wrapper.execute(command, resource);
        VmGuestNetworkState failedState = answer.getStates().get(VM_ONE);
        VmGuestNetworkState successfulState = answer.getStates().get(VM_TWO);

        assertTrue(answer.getResult());
        assertTrue(answer.getErrors().containsKey(VM_ONE));
        assertFalse(answer.getErrors().containsKey(VM_TWO));
        assertEquals("UNAVAILABLE", failedState.getStatus());
        assertEquals("OK", successfulState.getStatus());
        assertEquals(2, successfulState.getInterfaces().size());
    }

    @Test
    public void testCachedCapabilitySkipsGuestInfoProbe() throws Exception {
        when(domainOne.qemuAgentCommand(anyString(), eq(3), eq(0))).thenReturn(
                readFixture("guest-network-get-interfaces-linux.json"));
        GetVmGuestNetworkStateCommand command = new GetVmGuestNetworkStateCommand(
                Collections.singletonList(VM_ONE), Collections.emptyMap(), 3,
                Collections.singleton(VM_ONE));

        GetVmGuestNetworkStateAnswer answer = (GetVmGuestNetworkStateAnswer) wrapper.execute(command, resource);

        assertTrue(answer.getResult());
        assertEquals("OK", answer.getStates().get(VM_ONE).getStatus());
        verify(domainOne, times(1)).qemuAgentCommand(anyString(), eq(3), eq(0));
    }

    @Test
    public void testMultiAddressRoleEnrichmentUsesPreflightPrimary() throws Exception {
        when(domainOne.qemuAgentCommand(anyString(), eq(3), eq(0))).thenReturn(
                readFixture("guest-info-capabilities.json"),
                readFixture("guest-network-get-interfaces-primary-secondary.json"),
                readFixture("guest-get-osinfo-debian.json"),
                "{\"return\":{\"pid\":801}}",
                completedStatus(readFixture(
                        "guest-exec-linux-address-primary-secondary.json")));
        GetVmGuestNetworkStateCommand command = new GetVmGuestNetworkStateCommand(
                Collections.singletonList(VM_ONE), Collections.emptyMap(), 3,
                Collections.emptySet(), Collections.singleton(VM_ONE),
                Collections.emptySet(), Collections.emptySet(), true, 65536);

        GetVmGuestNetworkStateAnswer answer =
                (GetVmGuestNetworkStateAnswer) wrapper.execute(command, resource);
        VmGuestNetworkState state = answer.getStates().get(VM_ONE);

        assertEquals("OK", state.getStatus());
        assertEquals(VmGuestNetworkState.CURRENT_SCHEMA_VERSION, state.getSchemaVersion());
        assertEquals("PRIMARY",
                state.getInterfaces().get(1).getAddresses().get(0).getRole());
        assertTrue(state.getInterfaces().get(1).getAddresses().get(0).isRepresentative());
        assertEquals("10.10.254.230",
                state.getInterfaces().get(1).getAddresses().get(0).getAddress());
        assertEquals("SECONDARY",
                state.getInterfaces().get(1).getAddresses().get(3).getRole());
        verify(domainOne, times(1)).qemuAgentCommand(
                contains("\"execute\":\"guest-exec\""), eq(3), eq(0));
    }

    @Test
    public void testNonRunningVmSkipsQgaCalls() throws Exception {
        DomainInfo stopped = new DomainInfo();
        stopped.state = DomainState.VIR_DOMAIN_SHUTOFF;
        when(domainOne.getInfo()).thenReturn(stopped);
        GetVmGuestNetworkStateCommand command = new GetVmGuestNetworkStateCommand(
                Collections.singletonList(VM_ONE), Collections.emptyMap());

        GetVmGuestNetworkStateAnswer answer = (GetVmGuestNetworkStateAnswer) wrapper.execute(command, resource);

        assertEquals("UNAVAILABLE", answer.getStates().get(VM_ONE).getStatus());
        assertTrue(answer.getErrors().get(VM_ONE).contains("not running"));
        verify(domainOne).free();
    }

    @Test
    public void testStandardRouteCapabilityCollectsIpv4Ipv6Routes() throws Exception {
        when(domainOne.qemuAgentCommand(anyString(), eq(3), eq(0))).thenReturn(
                readFixture("guest-info-capabilities.json"),
                readFixture("guest-network-get-route-standard.json"));
        GetVmGuestNetworkStateCommand command = new GetVmGuestNetworkStateCommand(
                Collections.singletonList(VM_ONE), Collections.emptyMap(), 3,
                Collections.emptySet(), Collections.emptySet(),
                Collections.singleton(VM_ONE), false, 65536);

        GetVmGuestNetworkStateAnswer answer =
                (GetVmGuestNetworkStateAnswer) wrapper.execute(command, resource);
        VmGuestNetworkState state = answer.getStates().get(VM_ONE);

        assertEquals("OK", state.getStatus());
        assertEquals(4, state.getRoutes().size());
        assertTrue(state.getRoutes().get(0).isDefaultRoute());
        assertEquals("ipv6", state.getRoutes().get(2).getFamily());
        assertEquals("OK", state.getSectionStatuses().get("routes").getStatus());
        verify(domainOne, times(2)).qemuAgentCommand(anyString(), eq(3), eq(0));
    }

    @Test
    public void testUnavailableRouteWithSuccessfulInterfacesIsPartial() throws Exception {
        String capabilitiesWithoutRoute = "{\"return\":{\"version\":\"7.2.0\","
                + "\"supported_commands\":["
                + "{\"name\":\"guest-network-get-interfaces\",\"enabled\":true},"
                + "{\"name\":\"guest-exec\",\"enabled\":true}]}}";
        when(domainOne.qemuAgentCommand(anyString(), eq(3), eq(0))).thenReturn(
                capabilitiesWithoutRoute,
                readFixture("guest-network-get-interfaces-linux.json"));
        GetVmGuestNetworkStateCommand command = new GetVmGuestNetworkStateCommand(
                Collections.singletonList(VM_ONE), Collections.emptyMap(), 3,
                Collections.emptySet(), Collections.singleton(VM_ONE),
                Collections.singleton(VM_ONE), false, 65536);

        GetVmGuestNetworkStateAnswer answer =
                (GetVmGuestNetworkStateAnswer) wrapper.execute(command, resource);
        VmGuestNetworkState state = answer.getStates().get(VM_ONE);

        assertEquals("PARTIAL", state.getStatus());
        assertEquals("OK", state.getSectionStatuses().get("interfaces").getStatus());
        assertEquals("UNSUPPORTED", state.getSectionStatuses().get("routes").getStatus());
        assertTrue(answer.getErrors().isEmpty());
    }

    @Test
    public void testDnsOnlyCollectionUsesPersistablePerLinkModel() throws Exception {
        String dnsOutput = readFixture("guest-exec-linux-dns-resolvectl.txt");
        String dnsStatus = "{\"return\":{\"exited\":true,\"exitcode\":0,\"out-data\":\""
                + Base64.getEncoder().encodeToString(dnsOutput.getBytes(StandardCharsets.UTF_8))
                + "\"}}";
        when(domainOne.qemuAgentCommand(anyString(), eq(3), eq(0))).thenReturn(
                readFixture("guest-info-capabilities.json"),
                readFixture("guest-get-osinfo-debian.json"),
                "{\"return\":{\"pid\":601}}",
                dnsStatus);
        GetVmGuestNetworkStateCommand command = new GetVmGuestNetworkStateCommand(
                Collections.singletonList(VM_ONE), Collections.emptyMap(), 3,
                Collections.emptySet(), Collections.emptySet(), Collections.emptySet(),
                Collections.singleton(VM_ONE), true, 65536);

        GetVmGuestNetworkStateAnswer answer =
                (GetVmGuestNetworkStateAnswer) wrapper.execute(command, resource);
        VmGuestNetworkState state = answer.getStates().get(VM_ONE);

        assertEquals("OK", state.getStatus());
        assertEquals("NOT_DUE", state.getSectionStatuses().get("interfaces").getStatus());
        assertEquals("NOT_DUE", state.getSectionStatuses().get("routes").getStatus());
        assertEquals("OK", state.getSectionStatuses().get("dns").getStatus());
        assertEquals("resolvectl", state.getDns().getSource());
        assertEquals(2, state.getDns().getConfigurations().size());
        assertTrue(state.getDns().isUpstreamServersKnown());
        verify(domainOne, times(4)).qemuAgentCommand(anyString(), eq(3), eq(0));
    }

    @Test
    public void testRouteAndDnsShareOneOsInfoLookupForDebian() throws Exception {
        String capabilitiesWithoutRoute = "{\"return\":{\"version\":\"7.2.22\","
                + "\"supported_commands\":["
                + "{\"name\":\"guest-exec\",\"enabled\":true},"
                + "{\"name\":\"guest-exec-status\",\"enabled\":true},"
                + "{\"name\":\"guest-get-osinfo\",\"enabled\":true}]}}";
        when(domainOne.qemuAgentCommand(anyString(), eq(3), eq(0))).thenReturn(
                capabilitiesWithoutRoute,
                readFixture("guest-get-osinfo-debian.json"),
                "{\"return\":{\"pid\":701}}",
                completedStatus(readFixture("guest-exec-linux-route-v4.json")),
                "{\"return\":{\"pid\":702}}",
                completedStatus(readFixture("guest-exec-linux-route-v6.json")),
                "{\"return\":{\"pid\":703}}",
                completedStatus(readFixture("guest-exec-linux-dns-resolvectl.txt")));
        GetVmGuestNetworkStateCommand command = new GetVmGuestNetworkStateCommand(
                Collections.singletonList(VM_ONE), Collections.emptyMap(), 3,
                Collections.emptySet(), Collections.emptySet(),
                Collections.singleton(VM_ONE), Collections.singleton(VM_ONE),
                true, 65536);

        GetVmGuestNetworkStateAnswer answer =
                (GetVmGuestNetworkStateAnswer) wrapper.execute(command, resource);
        VmGuestNetworkState state = answer.getStates().get(VM_ONE);

        assertEquals("OK", state.getStatus());
        assertEquals("OK", state.getSectionStatuses().get("routes").getStatus());
        assertEquals("OK", state.getSectionStatuses().get("dns").getStatus());
        assertEquals(4, state.getRoutes().size());
        assertEquals("resolvectl", state.getDns().getSource());
        verify(domainOne, times(1)).qemuAgentCommand(
                contains("\"execute\":\"guest-get-osinfo\""), eq(3), eq(0));
    }

    @Test
    public void testUnknownOsFailsClosedWithoutGuestExec() throws Exception {
        String capabilitiesWithoutRoute = "{\"return\":{\"version\":\"7.2.22\","
                + "\"supported_commands\":["
                + "{\"name\":\"guest-exec\",\"enabled\":true},"
                + "{\"name\":\"guest-exec-status\",\"enabled\":true},"
                + "{\"name\":\"guest-get-osinfo\",\"enabled\":true}]}}";
        String freeBsdOsInfo = "{\"return\":{\"id\":\"freebsd\","
                + "\"name\":\"FreeBSD\",\"pretty-name\":\"FreeBSD 14.0\"}}";
        when(domainOne.qemuAgentCommand(anyString(), eq(3), eq(0))).thenReturn(
                capabilitiesWithoutRoute, freeBsdOsInfo);
        GetVmGuestNetworkStateCommand command = new GetVmGuestNetworkStateCommand(
                Collections.singletonList(VM_ONE), Collections.emptyMap(), 3,
                Collections.emptySet(), Collections.emptySet(),
                Collections.singleton(VM_ONE), Collections.singleton(VM_ONE),
                true, 65536);

        GetVmGuestNetworkStateAnswer answer =
                (GetVmGuestNetworkStateAnswer) wrapper.execute(command, resource);
        VmGuestNetworkState state = answer.getStates().get(VM_ONE);

        assertEquals("UNSUPPORTED", state.getStatus());
        assertEquals("UNSUPPORTED", state.getSectionStatuses().get("routes").getStatus());
        assertEquals("UNSUPPORTED", state.getSectionStatuses().get("dns").getStatus());
        verify(domainOne, times(2)).qemuAgentCommand(anyString(), eq(3), eq(0));
        verify(domainOne, never()).qemuAgentCommand(
                contains("\"execute\":\"guest-exec\""), eq(3), eq(0));
    }

    @Test
    public void testDisabledDnsFallbackPerformsNoQgaCommand() throws Exception {
        GetVmGuestNetworkStateCommand command = new GetVmGuestNetworkStateCommand(
                Collections.singletonList(VM_ONE), Collections.emptyMap(), 3,
                Collections.emptySet(), Collections.emptySet(), Collections.emptySet(),
                Collections.singleton(VM_ONE), false, 65536);

        GetVmGuestNetworkStateAnswer answer =
                (GetVmGuestNetworkStateAnswer) wrapper.execute(command, resource);
        VmGuestNetworkState state = answer.getStates().get(VM_ONE);

        assertEquals("UNSUPPORTED", state.getStatus());
        assertEquals("UNSUPPORTED", state.getSectionStatuses().get("dns").getStatus());
        verify(domainOne, never()).qemuAgentCommand(anyString(), eq(3), eq(0));
    }

    private String readFixture(String name) throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/qga/" + name)) {
            if (input == null) {
                throw new IOException("Missing fixture: " + name);
            }
            return IOUtils.toString(input, StandardCharsets.UTF_8);
        }
    }

    private String completedStatus(String stdout) {
        return "{\"return\":{\"exited\":true,\"exitcode\":0,\"out-data\":\""
                + Base64.getEncoder().encodeToString(stdout.getBytes(StandardCharsets.UTF_8))
                + "\"}}";
    }
}
