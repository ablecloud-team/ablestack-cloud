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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import com.cloud.exception.InvalidParameterValueException;

/** Import intent is not a client assertion of historical allocation execution. */
public final class VolumeAllocationImport {
    public static final String REQUEST_ID = "allocation.import.requestid";
    public static final String REQUEST_PATH = "allocation.import.requestpath";
    public static final String SOURCE_PATH = "allocation.import.sourcepath";
    public static final String SOURCE_IDENTITY = "allocation.import.sourceidentity";
    public static final String FINAL_PATH = "allocation.import.finalpath";
    public static final String IDENTITY = "allocation.import.identity";
    public static final String STRATEGY = "allocation.import.strategy";
    public static final String POOL = "allocation.import.pool";
    public static final String VERIFIED_AT = "allocation.import.verifiedat";
    public static final String STARTED_AT = "allocation.import.startedat";
    private VolumeAllocationImport() { }

    public static Map<String, String> expected(Map<String, String> offering, String policy, String format,
            Integer version, String requestId) {
        Map<String, String> intent = VolumeAllocationPolicy.validateStored(offering);
        if (policy == null && format == null && version == null && requestId == null && intent.isEmpty()) {
            return intent;
        }
        if (version == null || version != 1 || requestId == null || policy == null || format == null) {
            throw new InvalidParameterValueException("ALLOCATION_IMPORT_UNSUPPORTED: expected policy, format, contract version 1 and request UUID are required");
        }
        try {
            if (!UUID.fromString(requestId).toString().equalsIgnoreCase(requestId)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            throw new InvalidParameterValueException("Invalid allocationrequestid UUID");
        }
        if (!intent.equals(VolumeAllocationPolicy.request(policy, format, null))) {
            throw new InvalidParameterValueException("ALLOCATION_POLICY_MISMATCH: import expectation differs from disk offering intent");
        }
        return intent;
    }

    public static Map<String, String> methods(String format) {
        Map<String, String> result = new LinkedHashMap<>();
        if (!"qcow2".equals(format) && !"raw".equals(format)) return result;
        result.put("off", "import-adopt-off");
        result.put("falloc", "import-adopt-falloc");
        if ("qcow2".equals(format)) result.put("metadata", "import-copy-qemu-preallocation-metadata");
        result.put("full", "import-copy-qemu-preallocation-full");
        return result;
    }

    /** Only the stopped VM allocation transaction may promote an independently verified import. */
    public static void validateRootAssembly(Volume volume, com.cloud.vm.VirtualMachine vm, Map<String, String> details) {
        if (volume == null || vm == null || details == null || volume.getInstanceId() != null ||
                volume.getState() != Volume.State.Ready || volume.getVolumeType() != Volume.Type.DATADISK ||
                volume.getAccountId() != vm.getAccountId() || volume.getPoolId() == null ||
                !java.util.Objects.equals(volume.getUuid(), details.get(REQUEST_ID)) ||
                !java.util.Objects.equals(volume.getPath(), details.get(FINAL_PATH)) ||
                volume.getFormat() == null || !volume.getFormat().toString().equalsIgnoreCase(details.get(VolumeAllocationPolicy.FORMAT)) ||
                !details.containsKey(VERIFIED_AT) || details.containsKey(STARTED_AT) ||
                !"verified".equals(VolumeAllocationPolicy.describe(details, null, volume.getFormat().toString()).get("evidence"))) {
            throw new InvalidParameterValueException("ALLOCATION_POLICY_MISMATCH: only a verified unattached import can become the new VM ROOT");
        }
    }

    public static boolean requiresCopy(String policy) { return "metadata".equals(policy) || "full".equals(policy); }

    public static void validateEvidence(Map<String, String> intent, Map<String, String> result,
            String requestId, String poolUuid, String path, String format, boolean allowCopy) {
        if (result == null || path == null || !"GFS2".equals(result.get(VolumeAllocationPolicy.BACKEND)) || !intent.equals(VolumeAllocationPolicy.validateStored(result)) ||
                !requestId.equals(result.get(REQUEST_ID)) || !poolUuid.equals(result.get(POOL)) ||
                !path.equals(result.get(FINAL_PATH)) || !format.equals(intent.get(VolumeAllocationPolicy.FORMAT)) ||
                result.get(IDENTITY) == null || !result.get(IDENTITY).matches("[a-f0-9]{64}") ||
                !"verified".equals(VolumeAllocationPolicy.describe(result, null, format).get("evidence"))) {
            throw new InvalidParameterValueException("ALLOCATION_EVIDENCE_REQUIRED: Agent import evidence does not match the request and final image");
        }
        String policy = intent.get(VolumeAllocationPolicy.POLICY), strategy = result.get(STRATEGY);
        if (!("adopt".equals(strategy) && !requiresCopy(policy) && "import".equals(result.get(VolumeAllocationPolicy.OPERATION)) &&
                ("import-adopt-" + policy).equals(result.get(VolumeAllocationPolicy.METHOD))) &&
            !("copy".equals(strategy) && allowCopy && "copy".equals(result.get(VolumeAllocationPolicy.OPERATION)) &&
                ("qemu-preallocation-" + policy).equals(result.get(VolumeAllocationPolicy.METHOD)))) {
            throw new InvalidParameterValueException("ALLOCATION_EVIDENCE_REQUIRED: unqualified or unapproved import method");
        }
    }
}
