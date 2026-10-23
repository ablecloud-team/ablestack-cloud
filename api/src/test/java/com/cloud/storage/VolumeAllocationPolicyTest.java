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

import java.util.Map;
import java.lang.reflect.Field;
import org.junit.Test;
import org.apache.cloudstack.api.command.admin.offering.CreateDiskOfferingCmd;
import org.apache.cloudstack.api.command.admin.offering.CreateServiceOfferingCmd;
import com.cloud.exception.InvalidParameterValueException;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class VolumeAllocationPolicyTest {
    private void invalid(Runnable operation) {
        try { operation.run(); fail("Expected an explicit parameter error"); }
        catch (InvalidParameterValueException expected) { assertNotNull(expected.getMessage()); }
    }
    @Test public void legacyValuesKeepTheirMeaning() {
        assertEquals(Storage.ProvisioningType.THIN, Storage.ProvisioningType.getProvisioningType("thin"));
        assertEquals(Storage.ProvisioningType.SPARSE, Storage.ProvisioningType.getProvisioningType("sparse"));
        assertEquals(Storage.ProvisioningType.FAT, Storage.ProvisioningType.getProvisioningType("fat"));
        assertEquals("thin", VolumeAllocationPolicy.legacyColumn(null, null, null));
        assertTrue(VolumeAllocationPolicy.request(null, null, "sparse").isEmpty());
    }
    @Test public void legacyInvalidParametersAreUsefulErrors() {
        for (String value : new String[] {null, "metadata", "falloc", "full", "", "Thin"}) {
            invalid(() -> Storage.ProvisioningType.getProvisioningType(value));
        }
    }
    @Test public void explicitPolicyMatrixHasNoSilentFallback() {
        for (String format : new String[] {"qcow2", "raw"}) {
            for (String policy : new String[] {"off", "metadata", "falloc", "full"}) {
                if (format.equals("raw") && policy.equals("metadata")) {
                    invalid(() -> VolumeAllocationPolicy.request(policy, format, null));
                } else {
                    Map<String, String> intent = VolumeAllocationPolicy.request(policy, format, null);
                    assertEquals(policy, intent.get(VolumeAllocationPolicy.POLICY));
                    assertEquals(format, intent.get(VolumeAllocationPolicy.FORMAT));
                    assertEquals(intent, VolumeAllocationPolicy.requireCreationIntent(intent));
                    invalid(() -> VolumeAllocationPolicy.requireLegacyExecution(intent));
                }
            }
        }
    }
    @Test public void incompleteAndConflictingIntentIsRejected() {
        invalid(() -> VolumeAllocationPolicy.request("falloc", null, null));
        invalid(() -> VolumeAllocationPolicy.request(null, "raw", null));
        invalid(() -> VolumeAllocationPolicy.request("full", "raw", "fat"));
        invalid(() -> VolumeAllocationPolicy.request("sparse", "qcow2", null));
        invalid(() -> VolumeAllocationPolicy.request("full", "vmdk", null));
    }
    @Test public void linkedDiskOwnsTheIntentForBothRootAndData() {
        Map<String, String> intent = VolumeAllocationPolicy.request("falloc", "qcow2", null);
        assertEquals(intent, VolumeAllocationPolicy.resolve(Map.of(), intent, true));
        assertEquals(intent, VolumeAllocationPolicy.resolve(intent, null, false));
        assertTrue(VolumeAllocationPolicy.resolve(Map.of(), Map.of(), true).isEmpty());
        invalid(() -> VolumeAllocationPolicy.resolve(intent, intent, true));
        invalid(() -> VolumeAllocationPolicy.resolve(intent, Map.of(), true));
    }
    @Test public void partialOrFutureStoredContractNeverFallsBackToLegacy() {
        invalid(() -> VolumeAllocationPolicy.validateStored(Map.of(VolumeAllocationPolicy.VERSION, "1")));
        invalid(() -> VolumeAllocationPolicy.validateStored(Map.of(VolumeAllocationPolicy.POLICY, "full")));
        invalid(() -> VolumeAllocationPolicy.validateStored(Map.of(VolumeAllocationPolicy.VERSION, "2")));
        VolumeAllocationPolicy.requireLegacyExecution(Map.of("other", "detail"));
    }
    @Test public void legacyFatIsNotEvidenceOfRbdFull() {
        Map<String, String> response = VolumeAllocationPolicy.describe(Map.of(), "fat", "RAW");
        assertEquals("fat", response.get("requestedpolicy"));
        assertEquals("raw", response.get("actualformat"));
        assertEquals("unknown", response.get("fixedpolicy"));
        assertEquals("unknown", response.get("effectivepolicy"));
        assertEquals("unknown", response.get("appliedmethod"));
    }
    @Test public void noInventedRbdLazyThickOrFullResize() {
        VolumeAllocationPolicy.validateCapability("RBD", "raw", "create", Map.of("off", "rbd-thin", "full", "rbd-thick-create"));
        VolumeAllocationPolicy.validateCapability("RBD", "raw", "copy", Map.of("full", "rbd-thick-convert-n-S0"));
        invalid(() -> VolumeAllocationPolicy.validateCapability("RBD", "raw", "create", Map.of("falloc", "rbd-lazy")));
        invalid(() -> VolumeAllocationPolicy.validateCapability("RBD", "raw", "resize", Map.of("full", "rbd-thick-create")));
        invalid(() -> VolumeAllocationPolicy.validateCapability("RBD", "qcow2", "create", Map.of("off", "rbd-thin")));
        invalid(() -> VolumeAllocationPolicy.validateCapability("RBD", "raw", "clone", Map.of("full", "rbd-thick-create")));
    }
    @Test public void gfsPolicyFormatAndMethodMustAgree() {
        VolumeAllocationPolicy.validateCapability("GFS2", "qcow2", "create", Map.of("metadata", "qemu-preallocation-metadata", "falloc", "qemu-preallocation-falloc", "full", "qemu-preallocation-full"));
        VolumeAllocationPolicy.validateCapability("GFS2", "raw", "create", Map.of("off", "qemu-preallocation-off", "falloc", "qemu-preallocation-falloc", "full", "qemu-preallocation-full"));
        invalid(() -> VolumeAllocationPolicy.validateCapability("GFS2", "raw", "create", Map.of("metadata", "qemu-preallocation-metadata")));
        invalid(() -> VolumeAllocationPolicy.validateCapability("GFS2", "qcow2", "create", Map.of("full", "qemu-preallocation-falloc")));
        invalid(() -> VolumeAllocationPolicy.validateCapability("NFS", "qcow2", "create", Map.of("full", "qemu-preallocation-full")));
        invalid(() -> VolumeAllocationPolicy.validateCapability("GFS2", "raw", "bad", Map.of("off", "qemu-preallocation-off")));
    }
    private void set(Object target, String name, String value) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    @Test public void publicCommandsPreserveDefaultAndPersistSameIntent() throws Exception {
        CreateDiskOfferingCmd disk = new CreateDiskOfferingCmd();
        CreateServiceOfferingCmd service = new CreateServiceOfferingCmd();
        assertEquals("thin", disk.getProvisioningType());
        assertEquals("thin", service.getProvisioningType());
        set(disk, "allocationPolicy", "falloc"); set(disk, "allocationFormat", "qcow2");
        set(service, "allocationPolicy", "falloc"); set(service, "allocationFormat", "qcow2");
        assertEquals(disk.getDetails(), service.getDetails());
        assertEquals("falloc", disk.getDetails().get(VolumeAllocationPolicy.POLICY));
        assertEquals("fat", disk.getProvisioningType());
        set(disk, "provisioningType", "sparse");
        invalid(disk::getDetails);
        invalid(disk::getProvisioningType);
    }
    @Test public void verifiedEvidenceAndObservationAreSeparateFromIntent() {
        var intent = VolumeAllocationPolicy.request("full", "raw", null);
        var at = java.time.Instant.parse("2026-10-19T00:00:00Z");
        var evidence = VolumeAllocationPolicy.evidence(intent, "RBD", "create", "rbd-thick-create", 67108864L, at, "rbd-du-used-size");
        var response = VolumeAllocationPolicy.describe(evidence, "fat", "raw");
        assertEquals("full", response.get("requestedpolicy"));
        assertEquals("full", response.get("fixedpolicy"));
        assertEquals("full", response.get("effectivepolicy"));
        assertEquals("rbd-thick-create", response.get("appliedmethod"));
        assertEquals("67108864", response.get("observedallocatedbytes"));
        assertEquals(at.toString(), response.get("observedat"));
        assertEquals("verified", response.get("evidence"));
        assertFalse(intent.containsKey(VolumeAllocationPolicy.FIXED_POLICY));
    }
    @Test public void legacyOrUnsupportedExecutionCannotProduceVerifiedEvidence() {
        invalid(() -> VolumeAllocationPolicy.evidence(Map.of(), "RBD", "create", "rbd-thick-create", null, null, null));
        var intent = VolumeAllocationPolicy.request("full", "raw", null);
        invalid(() -> VolumeAllocationPolicy.evidence(intent, "RBD", "resize", "rbd-thick-create", null, null, null));
        invalid(() -> VolumeAllocationPolicy.evidence(intent, "RBD", "create", "rbd-thick-create", -1L, java.time.Instant.now(), "rbd-du"));
        invalid(() -> VolumeAllocationPolicy.evidence(intent, "RBD", "create", "rbd-thick-create", 1L, null, null));
    }
    @Test public void malformedEvidenceNeverClaimsEffectiveFull() {
        var evidence = VolumeAllocationPolicy.evidence(VolumeAllocationPolicy.request("full", "raw", null), "RBD", "create", "rbd-thick-create", null, null, null);
        evidence.put(VolumeAllocationPolicy.FIXED_POLICY, "falloc");
        var response = VolumeAllocationPolicy.describe(evidence, "fat", "raw");
        assertEquals("unknown", response.get("effectivepolicy"));
        assertEquals("invalid", response.get("evidence"));
        assertEquals("invalid", VolumeAllocationPolicy.describe(Map.of(VolumeAllocationPolicy.VERSION, "9"), "fat", "raw").get("evidence"));
    }
}
