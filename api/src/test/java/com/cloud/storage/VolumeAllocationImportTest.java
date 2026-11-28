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

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.Test;
import static org.junit.Assert.*;

public class VolumeAllocationImportTest {
    private final String id = UUID.randomUUID().toString();
    @Test public void expectationCannotSilentlyBecomeLegacy() {
        assertTrue(VolumeAllocationImport.expected(Map.of(), null, null, null, null).isEmpty());
        for (String format : java.util.List.of("raw", "qcow2")) {
            for (String policy : VolumeAllocationImport.methods(format).keySet()) {
                Map<String, String> intent = VolumeAllocationPolicy.request(policy, format, null);
                assertEquals(intent, VolumeAllocationImport.expected(intent, policy, format, 1, id));
                reject(() -> VolumeAllocationImport.expected(Map.of(), policy, format, 1, id));
                reject(() -> VolumeAllocationImport.expected(intent, policy, format, 2, id));
                reject(() -> VolumeAllocationImport.expected(intent, null, null, null, null));
            }
        }
    }
    @Test public void formatAndRequestIdentityAreValidated() {
        Map<String, String> intent = VolumeAllocationPolicy.request("falloc", "raw", null);
        reject(() -> VolumeAllocationImport.expected(intent, "metadata", "raw", 1, id));
        reject(() -> VolumeAllocationImport.expected(intent, "falloc", "raw", 1, "../../image"));
        assertFalse(VolumeAllocationImport.methods("raw").containsKey("metadata"));
        reject(() -> VolumeAllocationPolicy.validateCapability("RBD", "raw", "import", Map.of("off", "rbd-thin")));
    }
    @Test public void adoptionDescribesPresentStateNotHistoricalCreateMethod() {
        Map<String, String> intent = VolumeAllocationPolicy.request("falloc", "qcow2", null);
        Map<String, String> evidence = receipt(VolumeAllocationPolicy.evidence(intent, "GFS2", "import", "import-adopt-falloc", 1048576L,
                Instant.now(), "stat-st-blocks-times-512"), "adopt");
        VolumeAllocationImport.validateEvidence(intent, evidence, id, "pool", "image", "qcow2", false);
        assertEquals("import-time", VolumeAllocationPolicy.describe(evidence, "fat", "qcow2").get("evidencescope"));
        evidence.put(VolumeAllocationPolicy.METHOD, "qemu-preallocation-falloc");
        reject(() -> VolumeAllocationImport.validateEvidence(intent, evidence, id, "pool", "image", "qcow2", false));
    }
    @Test public void copiedFullRequiresBothRealEvidenceAndOperatorApproval() {
        Map<String, String> intent = VolumeAllocationPolicy.request("full", "raw", null);
        Map<String, String> evidence = receipt(VolumeAllocationPolicy.evidence(intent, "GFS2", "copy", "qemu-preallocation-full", 1048576L,
                Instant.now(), "stat-st-blocks-times-512"), "copy");
        reject(() -> VolumeAllocationImport.validateEvidence(intent, evidence, id, "pool", "image", "raw", false));
        VolumeAllocationImport.validateEvidence(intent, evidence, id, "pool", "image", "raw", true);
        evidence.put(VolumeAllocationImport.REQUEST_ID, UUID.randomUUID().toString());
        reject(() -> VolumeAllocationImport.validateEvidence(intent, evidence, id, "pool", "image", "raw", true));
    }
    @Test public void rootAssemblyRequiresTheExactUnattachedVerifiedImport() {
        Volume volume = org.mockito.Mockito.mock(Volume.class);
        com.cloud.vm.VirtualMachine vm = org.mockito.Mockito.mock(com.cloud.vm.VirtualMachine.class);
        org.mockito.Mockito.when(volume.getInstanceId()).thenReturn(null);
        org.mockito.Mockito.when(volume.getState()).thenReturn(Volume.State.Ready);
        org.mockito.Mockito.when(volume.getVolumeType()).thenReturn(Volume.Type.DATADISK);
        org.mockito.Mockito.when(volume.getAccountId()).thenReturn(2L);
        org.mockito.Mockito.when(vm.getAccountId()).thenReturn(2L);
        org.mockito.Mockito.when(volume.getPoolId()).thenReturn(1L);
        org.mockito.Mockito.when(volume.getUuid()).thenReturn(id);
        org.mockito.Mockito.when(volume.getPath()).thenReturn("image");
        org.mockito.Mockito.when(volume.getFormat()).thenReturn(Storage.ImageFormat.RAW);
        Map<String, String> evidence = receipt(VolumeAllocationPolicy.evidence(VolumeAllocationPolicy.request("falloc", "raw", null),
                "GFS2", "import", "import-adopt-falloc", 1048576L, Instant.now(), "stat-st-blocks-times-512"), "adopt");
        evidence.put(VolumeAllocationImport.VERIFIED_AT, Instant.now().toString());
        VolumeAllocationImport.validateRootAssembly(volume, vm, evidence);
        org.mockito.Mockito.when(volume.getInstanceId()).thenReturn(99L);
        reject(() -> VolumeAllocationImport.validateRootAssembly(volume, vm, evidence));
        org.mockito.Mockito.when(volume.getInstanceId()).thenReturn(null);
        org.mockito.Mockito.when(vm.getAccountId()).thenReturn(3L);
        reject(() -> VolumeAllocationImport.validateRootAssembly(volume, vm, evidence));
        org.mockito.Mockito.when(vm.getAccountId()).thenReturn(2L);
        org.mockito.Mockito.when(volume.getFormat()).thenReturn(Storage.ImageFormat.QCOW2);
        reject(() -> VolumeAllocationImport.validateRootAssembly(volume, vm, evidence));
        org.mockito.Mockito.when(volume.getFormat()).thenReturn(Storage.ImageFormat.RAW);
        evidence.put(VolumeAllocationImport.STARTED_AT, Instant.now().toString());
        reject(() -> VolumeAllocationImport.validateRootAssembly(volume, vm, evidence));
    }
    private Map<String, String> receipt(Map<String, String> evidence, String strategy) {
        evidence = new LinkedHashMap<>(evidence); evidence.put(VolumeAllocationImport.REQUEST_ID,id);
        evidence.put(VolumeAllocationImport.POOL,"pool"); evidence.put(VolumeAllocationImport.FINAL_PATH,"image");
        evidence.put(VolumeAllocationImport.IDENTITY,"0".repeat(64)); evidence.put(VolumeAllocationImport.STRATEGY,strategy); return evidence;
    }
    private void reject(Runnable call) {
        try { call.run(); fail("Expected allocation contract rejection"); }
        catch (com.cloud.exception.InvalidParameterValueException expected) { assertNotNull(expected.getMessage()); }
    }
}
