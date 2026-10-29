// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with this
// work for additional information regarding copyright ownership. The ASF
// licenses this file to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0 .
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
// WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
// License for the specific language governing permissions and limitations.
package com.cloud.storage;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class VolumeAllocationCloneTest {
    private static final long SIZE = 1048576;
    private Map<String, String> intent(String policy, String format) { return VolumeAllocationPolicy.request(policy, format, null); }
    private void rejects(String reason, Runnable task) {
        try { task.run(); fail("must reject before mutation"); }
        catch (RuntimeException e) { assertTrue(e.getMessage(), e.getMessage().contains(reason)); }
    }
    @Test public void rbdOffSupportsBothTypesWithoutMutatingIntent() {
        Map<String, String> source = Map.copyOf(intent("off", "raw"));
        for (String type : new String[]{"full", "linked"}) {
            assertEquals(source, VolumeAllocationClone.validate(source, Storage.StoragePoolType.RBD, "RAW", type));
        }
    }
    @Test public void fullIsSupportedOnlyWithAnIndependentCopy() {
        Map<String, String> full = intent("full", "raw");
        assertEquals(full, VolumeAllocationClone.validate(full, Storage.StoragePoolType.RBD, "raw", "full"));
        rejects("TYPE_CONFLICT", () -> VolumeAllocationClone.validate(full, Storage.StoragePoolType.RBD, "raw", "linked"));
    }
    @Test public void malformedAndWrongFormatDoNotBecomeLegacy() {
        rejects("Unknown allocation", () -> VolumeAllocationClone.validate(Map.of(VolumeAllocationPolicy.VERSION, "future"), Storage.StoragePoolType.RBD, "raw", "full"));
        rejects("FORMAT_MISMATCH", () -> VolumeAllocationClone.validate(intent("off", "raw"), Storage.StoragePoolType.RBD, "qcow2", "full"));
        rejects("BACKEND_UNSUPPORTED", () -> VolumeAllocationClone.validate(intent("metadata", "qcow2"), Storage.StoragePoolType.NetworkFilesystem, "qcow2", "full"));
    }
    @Test public void snapshotCloneRequiresExactSourceIntentAndInternalProvenance() {
        Map<String, String> source = intent("off", "raw");
        Map<String, String> target = new HashMap<>(source);
        rejects("OPERATION_UNSUPPORTED", () -> VolumeAllocationClone.requireSnapshotTarget(source, target, 10));
        target.put(VolumeAllocationClone.SOURCE, "10"); target.put(VolumeAllocationClone.TYPE, "linked");
        VolumeAllocationClone.requireSnapshotTarget(source, target, 10);
        rejects("OPERATION_UNSUPPORTED", () -> VolumeAllocationClone.requireSnapshotTarget(source, target, 11));
        rejects("OPERATION_UNSUPPORTED", () -> VolumeAllocationClone.requireSnapshotTarget(source, Map.of(), 10));
        rejects("OPERATION_UNSUPPORTED", () -> VolumeAllocationClone.requireSnapshotTarget(Map.of(), target, 10));
    }
    @Test public void linkedEvidenceIsExplicitCowEvidenceAndNeverFull() {
        Map<String, String> source = intent("off", "raw");
        Map<String, String> receipt = VolumeAllocationPolicy.evidence(source, "RBD", "clone", "rbd-cow-clone", 0L, Instant.now(), "rbd-du-exact-image-used-size");
        assertEquals(receipt, VolumeAllocationClone.requireResult(source, receipt, "RBD", SIZE));
        assertEquals("verified", VolumeAllocationPolicy.describe(receipt, "thin", "raw").get("evidence"));
        rejects("EVIDENCE_INVALID", () -> VolumeAllocationClone.requireResult(intent("full", "raw"), receipt, "RBD", SIZE));
    }
    @Test public void cloneCannotReuseCreationOrIncompleteObservations() {
        Map<String, String> source = intent("full", "raw");
        Map<String, String> old = VolumeAllocationPolicy.evidence(source, "RBD", "create", "rbd-thick-create", SIZE, Instant.now(), "rbd-du-exact-image-used-size");
        rejects("EVIDENCE_INVALID", () -> VolumeAllocationClone.requireResult(source, old, "RBD", SIZE));
        Map<String, String> receipt = VolumeAllocationPolicy.evidence(source, "RBD", "copy", "rbd-thick-convert-n-S0", SIZE, Instant.now(), "rbd-du-exact-image-used-size");
        assertEquals(receipt, VolumeAllocationClone.requireResult(source, receipt, "RBD", SIZE));
        receipt.remove(VolumeAllocationPolicy.OBSERVED_AT);
        rejects("EVIDENCE_INVALID", () -> VolumeAllocationClone.requireResult(source, receipt, "RBD", SIZE));
    }
}
