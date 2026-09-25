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
import java.util.Map;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Test;
import com.cloud.agent.api.GetVmProcessCapabilitiesCommand;
import com.cloud.agent.api.VmProcessCapability;

public class VmProcessCapabilityProbeTest {
    private final GetVmProcessCapabilitiesCommand command = new GetVmProcessCapabilitiesCommand("i-2-15-VM",
            "11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222", "42",
            "33333333-3333-4333-8333-333333333333");
    private String info(String disabled, String missing) {
        return "{\"return\":{\"version\":\"10.1\",\"supported_commands\":[" + VmProcessCapability.RPCS.stream()
                .filter(rpc -> !rpc.equals(missing)).map(rpc -> "{\"name\":\"" + rpc + "\",\"enabled\":" + !rpc.equals(disabled) + "}")
                .collect(Collectors.joining(",")) + "]}}";
    }
    private String os(String id, String version) {
        return "{\"return\":{\"id\":\"" + id + "\",\"version-id\":\"" + version + "\",\"machine\":\"x86_64\",\"variant-id\":\"server\"}}";
    }
    private Map<String, Object> observe(boolean present, String guestInfo, String os) {
        return new VmProcessCapabilityProbe() {
            @Override protected boolean hostToolPresent() { return present; }
            @Override protected String hostToolsVersion() { return "test"; }
            @Override protected String guest(String uuid, String operation) throws Exception {
                assertEquals(command.getVmUuid(), uuid);
                assertTrue(List.of("guest-info", "guest-get-osinfo").contains(operation));
                if (guestInfo == null) throw new java.io.IOException("offline");
                return operation.equals("guest-info") ? guestInfo : os;
            }
        }.observe(command);
    }
    @Test public void classifiesEveryRequiredRpc() {
        for (String rpc : VmProcessCapability.RPCS) {
            assertEquals("DISABLED", VmProcessCapabilityProbe.parseRpcs(info(rpc, "")).get(rpc));
            assertEquals("UNSUPPORTED", VmProcessCapabilityProbe.parseRpcs(info("", rpc)).get(rpc));
        }
        assertEquals(8, VmProcessCapabilityProbe.parseRpcs(info("", "")).size());
    }
    @Test public void rejectsMalformedAndAmbiguousCapabilities() {
        for (String value : List.of("garbage", "{}", "{\"error\":{}}", info("", "").replace("true", "\"true\""),
                info("", "").replace("guest-exec-status", "guest-exec"))) {
            assertEquals("CHECK_FAILED", observe(true, value, os("rocky", "9.7")).get("readiness"));
        }
    }
    @Test public void followsReadinessPriorityAndNeverInfersReadyFromRpcFlags() {
        assertEquals("QGA_UNREACHABLE", observe(true, null, null).get("readiness"));
        assertEquals("HOST_TOOL_MISSING", observe(false, null, null).get("readiness"));
        assertEquals("HOST_TOOL_MISSING", observe(false, info("", ""), null).get("readiness"));
        assertEquals("RPC_UNSUPPORTED", observe(true, info("guest-exec", "guest-file-read"), os("rocky", "10.2")).get("readiness"));
        assertEquals("RPC_DISABLED", observe(true, info("guest-exec", ""), os("rocky", "9.7")).get("readiness"));
        assertEquals("UNSUPPORTED_OS", observe(true, info("", ""), os("rocky", "8.10")).get("readiness"));
        Map<String, Object> result = observe(true, info("", ""), os("mswindows", "2025"));
        assertEquals("TOOLS_REQUIRED", result.get("readiness"));
        assertEquals(List.of(), result.get("allowedActions"));
        assertEquals(List.of(), result.get("supportedSchemaVersions"));
        assertNull(result.get("guestAdapterVersion"));
        assertEquals("TOOLS_REQUIRED", observe(true, info("", ""), os("rocky", "10.2")).get("readiness"));
        assertEquals("TOOLS_REQUIRED", observe(true, info("", ""), os("ubuntu", "26.04")).get("readiness"));
    }
    @Test public void clientWindowsAndUnsupportedArchAreNotSupported() {
        Map<String, String> windows = VmProcessCapabilityProbe.parseOs(os("mswindows", "2025").replace("server", "client"));
        assertFalse(VmProcessCapabilityProbe.supportedOs(windows));
        assertFalse(VmProcessCapabilityProbe.supportedOs(VmProcessCapabilityProbe.parseOs(os("rocky", "9.7").replace("x86_64", "aarch64"))));
        assertTrue(VmProcessCapabilityProbe.supportedOs(VmProcessCapabilityProbe.parseOs(os("ubuntu", "24.04"))));
    }
    @Test public void guardResolvesNameBeforeGuestUuidObservation() {
        try (org.mockito.MockedStatic<KvmVmOperationGuard> guard = org.mockito.Mockito.mockStatic(KvmVmOperationGuard.class)) {
            new VmProcessCapabilityProbe().collect(command);
            guard.verify(() -> KvmVmOperationGuard.collect(org.mockito.ArgumentMatchers.isNull(),
                    org.mockito.ArgumentMatchers.eq(command.getVmName()), org.mockito.ArgumentMatchers.any()));
        }
    }

}
