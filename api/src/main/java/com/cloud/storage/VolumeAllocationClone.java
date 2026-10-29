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

import java.util.Map;
import com.cloud.exception.InvalidParameterValueException;

/** Internal VM-clone provenance is separate from immutable allocation intent and execution evidence. */
public final class VolumeAllocationClone {
    public static final String SOURCE = "clone.vm.source.volume.id";
    public static final String TYPE = "clone.vm.type";
    private VolumeAllocationClone() { }

    public static Map<String, String> validate(Map<String, String> details, Storage.StoragePoolType pool, String actualFormat, String type) {
        Map<String, String> intent = VolumeAllocationLifecycle.intent(details);
        if (intent.isEmpty()) { return intent; }
        if (!intent.get(VolumeAllocationPolicy.FORMAT).equalsIgnoreCase(actualFormat == null ? "" : actualFormat)) {
            throw new InvalidParameterValueException("ALLOCATION_CLONE_FORMAT_MISMATCH: source format must match its immutable allocation intent");
        }
        if (pool != Storage.StoragePoolType.RBD && pool != Storage.StoragePoolType.SharedMountPoint) {
            throw new InvalidParameterValueException("ALLOCATION_CLONE_BACKEND_UNSUPPORTED: qualified GFS2/RBD clone required");
        }
        if (pool == Storage.StoragePoolType.RBD) {
            if (!"raw".equals(intent.get(VolumeAllocationPolicy.FORMAT)) ||
                    !java.util.List.of("off", "full").contains(intent.get(VolumeAllocationPolicy.POLICY))) {
                throw new InvalidParameterValueException("ALLOCATION_CLONE_BACKEND_UNSUPPORTED: RBD requires raw off/full");
            }
            if ("linked".equals(type) && "full".equals(intent.get(VolumeAllocationPolicy.POLICY))) {
                throw new InvalidParameterValueException("ALLOCATION_CLONE_TYPE_CONFLICT: a COW Linked image cannot satisfy per-image Full allocation; select Full clone. Source policy is preserved");
            }
        }
        return intent;
    }

    public static void requireSnapshotTarget(Map<String, String> source, Map<String, String> target, long sourceId) {
        Map<String, String> intent = VolumeAllocationLifecycle.intent(source);
        Map<String, String> requested = VolumeAllocationLifecycle.intent(target);
        if (intent.isEmpty() && requested.isEmpty()) { return; }
        if (intent.isEmpty() || !intent.equals(requested) || !Long.toString(sourceId).equals(target.get(SOURCE)) ||
                !java.util.List.of("full", "linked").contains(target.get(TYPE))) {
            throw new InvalidParameterValueException("ALLOCATION_OPERATION_UNSUPPORTED: snapshot clone requires matching source intent and VM-clone provenance");
        }
    }

    /** Reusing a verified, unchanged ROOT performs no conversion, even if its offering is legacy. */
    public static void requireReusableRoot(Map<String, String> source, Map<String, String> offering, String format, long size) {
        Map<String, String> intent = VolumeAllocationLifecycle.intent(source);
        Map<String, String> target = VolumeAllocationLifecycle.intent(offering);
        if (intent.isEmpty() && target.isEmpty()) { return; }
        if (intent.isEmpty() || (!target.isEmpty() && !target.equals(intent))) {
            throw new InvalidParameterValueException("ALLOCATION_CONVERSION_UNSUPPORTED: existing ROOT intent cannot change during clone VM allocation");
        }
        VolumeAllocationLifecycle.requireMetadataOnly(source, size, size, format);
    }

    public static Map<String, String> requireResult(Map<String, String> intent, Map<String, String> result, String backend, long size) {
        try {
            long observed = Long.parseLong(result.get(VolumeAllocationPolicy.OBSERVED_BYTES));
            java.time.Instant at = java.time.Instant.parse(result.get(VolumeAllocationPolicy.OBSERVED_AT));
            String source = result.get(VolumeAllocationPolicy.OBSERVED_SOURCE);
            String operation = result.get(VolumeAllocationPolicy.OPERATION);
            Map<String, String> verified = VolumeAllocationPolicy.evidence(intent, backend, operation,
                    result.get(VolumeAllocationPolicy.METHOD), observed, at, source);
            if (!java.util.List.of("copy", "clone").contains(operation) || !verified.equals(result) ||
                    !("GFS2".equals(backend) ? "stat-st-blocks-times-512" : "rbd-du-exact-image-used-size").equals(source) ||
                    (java.util.List.of("full", "falloc").contains(intent.get(VolumeAllocationPolicy.POLICY)) && observed < size)) {
                throw new IllegalArgumentException("incomplete clone allocation evidence");
            }
            return verified;
        } catch (RuntimeException failure) {
            throw new InvalidParameterValueException("ALLOCATION_EVIDENCE_INVALID: clone result must prove its own policy, method and observed allocation");
        }
    }
}
