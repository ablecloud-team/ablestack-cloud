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
import java.util.Map;
import java.util.LinkedHashMap;
import org.junit.Test;
import com.cloud.exception.InvalidParameterValueException;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class VolumeAllocationLifecycleTest {
    private static Map<String, String> intent(String policy, String format) {
        return VolumeAllocationPolicy.request(policy, format, null);
    }
    private static Map<String, String> verified(String backend, String policy, String format) {
        String method = "RBD".equals(backend) ? ("off".equals(policy) ? "rbd-thin" : "rbd-thick-create") : "qemu-preallocation-" + policy;
        return VolumeAllocationPolicy.evidence(intent(policy, format), backend, "create", method, 1024L, Instant.parse("2026-10-20T00:00:00Z"), "test");
    }
    private static void rejected(Runnable action, String code) {
        try { action.run(); fail("Must reject an unqualified transition"); }
        catch (InvalidParameterValueException e) { assertTrue(e.getMessage(), e.getMessage().contains(code)); }
    }
    @Test public void samePolicyOfferingPreservesAllEightBackendCombinationsAndHistoricalEvidence() {
        for (String backend : new String[]{"GFS2", "RBD"}) {
            for (String format : new String[]{"qcow2", "raw"}) {
                if ("RBD".equals(backend) && "qcow2".equals(format)) continue;
                String[] policies = "RBD".equals(backend) ? new String[]{"off", "full"} : new String[]{"raw".equals(format) ? "off" : "metadata", "falloc", "full"};
                for (String policy : policies) {
                    Map<String, String> source = verified(backend, policy, format);
                    Map<String, String> before = new LinkedHashMap<>(source);
                    VolumeAllocationLifecycle.requireCompatibleOffering(source, intent(policy, format), Storage.ProvisioningType.FAT, Storage.ProvisioningType.FAT);
                    VolumeAllocationLifecycle.requireMetadataOnly(source, 1024L, 1024L, format);
                    assertEquals(before, source);
                    assertEquals("creation-or-copy-time", VolumeAllocationPolicy.describe(source, "fat", format).get("evidencescope"));
                }
            }
        }
    }
    @Test public void explicitToLegacyCannotRemoveGuarantees() { rejected(() -> VolumeAllocationLifecycle.requireCompatibleOffering(intent("full", "raw"), Map.of(), Storage.ProvisioningType.FAT, Storage.ProvisioningType.FAT), "ALLOCATION_CONVERSION_UNSUPPORTED"); }
    @Test public void legacyToExplicitCannotInventEvidence() { rejected(() -> VolumeAllocationLifecycle.requireCompatibleOffering(Map.of(), intent("full", "raw"), Storage.ProvisioningType.FAT, Storage.ProvisioningType.FAT), "ALLOCATION_CONVERSION_UNSUPPORTED"); }
    @Test public void policyChangeCannotMasqueradeAsMetadata() { rejected(() -> VolumeAllocationLifecycle.requireCompatibleOffering(intent("falloc", "raw"), intent("full", "raw"), Storage.ProvisioningType.FAT, Storage.ProvisioningType.FAT), "ALLOCATION_CONVERSION_UNSUPPORTED"); }
    @Test public void imageFormatChangeCannotMasqueradeAsMetadata() { rejected(() -> VolumeAllocationLifecycle.requireCompatibleOffering(intent("full", "qcow2"), intent("full", "raw"), Storage.ProvisioningType.FAT, Storage.ProvisioningType.FAT), "ALLOCATION_CONVERSION_UNSUPPORTED"); }
    @Test public void legacySparseToFatDoesNotClaimPhysicalConversion() { rejected(() -> VolumeAllocationLifecycle.requireCompatibleOffering(Map.of(), Map.of(), Storage.ProvisioningType.SPARSE, Storage.ProvisioningType.FAT), "ALLOCATION_CONVERSION_UNSUPPORTED"); }
    @Test public void sameLegacyOfferingAndNormalThinExpansionRetainExistingPath() {
        VolumeAllocationLifecycle.requireCompatibleOffering(Map.of(), Map.of(), Storage.ProvisioningType.THIN, Storage.ProvisioningType.THIN);
        VolumeAllocationLifecycle.requireMetadataOnly(Map.of(), 1024L, 2048L, "raw");
    }
    @Test public void rbdFullExpansionAndShrinkAreRejectedBeforeAnyMutation() {
        Map<String, String> source=verified("RBD", "full", "raw");
        rejected(() -> VolumeAllocationLifecycle.requireMetadataOnly(source, 1024L, 2048L, "raw"), "ALLOCATION_OPERATION_UNSUPPORTED");
        rejected(() -> VolumeAllocationLifecycle.requireMetadataOnly(source, 1024L, 512L, "raw"), "ALLOCATION_OPERATION_UNSUPPORTED");
    }
    @Test public void explicitThinNeedsItsOwnQualifiedResizeAdapterRatherThanLegacyFallback() { rejected(() -> VolumeAllocationLifecycle.requireMetadataOnly(verified("RBD", "off", "raw"), 1024L, 2048L, "raw"), "ALLOCATION_OPERATION_UNSUPPORTED"); }
    @Test public void missingEvidenceCannotAuthorizeMetadataUpdate() { rejected(() -> VolumeAllocationLifecycle.requireMetadataOnly(intent("full", "raw"), 1024L, 1024L, "raw"), "ALLOCATION_EVIDENCE_REQUIRED"); }
    @Test public void actualFormatMismatchCannotAuthorizeMetadataUpdate() { rejected(() -> VolumeAllocationLifecycle.requireMetadataOnly(verified("GFS2", "full", "raw"), 1024L, 1024L, "qcow2"), "ALLOCATION_EVIDENCE_REQUIRED"); }
    @Test public void orphanedEvidenceIsNotLegacy() {
        Map<String, String> broken=Map.of(VolumeAllocationPolicy.EVIDENCE_VERSION,"1");
        rejected(() -> VolumeAllocationLifecycle.intent(broken), "ALLOCATION_CONTRACT_INVALID");
        rejected(() -> VolumeAllocationPolicy.requireUnqualifiedLifecycle(broken,"migrate"), "ALLOCATION_CONTRACT_INVALID");
    }
    @Test public void malformedIntentFailsClosed() { rejected(() -> VolumeAllocationLifecycle.intent(Map.of(VolumeAllocationPolicy.VERSION,"99")), "Unknown allocation contract version"); }
}
