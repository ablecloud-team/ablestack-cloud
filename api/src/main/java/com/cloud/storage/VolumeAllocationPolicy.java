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
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.cloud.exception.InvalidParameterValueException;

/** Versioned intent, independent of the legacy provisioning column and observed allocation. */
public final class VolumeAllocationPolicy {
    public static final String VERSION = "allocation.contract.version";
    public static final String POLICY = "allocation.requested.policy";
    public static final String FORMAT = "allocation.requested.format";
    public static final String FIXED_POLICY = "allocation.fixed.policy";
    public static final String EVIDENCE_VERSION = "allocation.evidence.version";
    public static final String BACKEND = "allocation.applied.backend";
    public static final String OPERATION = "allocation.applied.operation";
    public static final String METHOD = "allocation.applied.method";
    public static final String OBSERVED_BYTES = "allocation.observed.bytes";
    public static final String OBSERVED_AT = "allocation.observed.at";
    public static final String OBSERVED_SOURCE = "allocation.observed.source";
    public static final String AGENT_VERSION = "volume.allocation.capability.version";
    public static final Set<String> OPERATIONS = Set.of("create", "copy", "resize", "restore", "migrate", "clone");

    public enum Policy { OFF, METADATA, FALLOC, FULL }
    public enum Format { QCOW2, RAW }

    private VolumeAllocationPolicy() { }

    private static <T extends Enum<T>> T parse(Class<T> type, String value, String name) {
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new InvalidParameterValueException("Invalid " + name + ": " + value);
        }
    }

    public static Map<String, String> request(String policy, String format, String explicitLegacy) {
        Map<String, String> result = new LinkedHashMap<>();
        if (policy == null && format == null) {
            if (explicitLegacy != null) {
                legacy(explicitLegacy);
            }
            return result;
        }
        if (policy == null || format == null) {
            throw new InvalidParameterValueException("allocationpolicy and allocationformat must be supplied together");
        }
        if (explicitLegacy != null) {
            throw new InvalidParameterValueException("Use either provisioningtype or allocationpolicy/allocationformat");
        }
        Policy requested = parse(Policy.class, policy, "allocationpolicy (off, metadata, falloc, full)");
        Format requestedFormat = parse(Format.class, format, "allocationformat (qcow2, raw)");
        if (requestedFormat == Format.RAW && requested == Policy.METADATA) {
            throw new InvalidParameterValueException("raw does not support metadata allocation");
        }
        result.put(VERSION, "1");
        result.put(POLICY, requested.name().toLowerCase(Locale.ROOT));
        result.put(FORMAT, requestedFormat.name().toLowerCase(Locale.ROOT));
        return result;
    }

    public static Storage.ProvisioningType legacy(String value) {
        for (Storage.ProvisioningType type : Storage.ProvisioningType.values()) {
            if (type.toString().equals(value)) {
                return type;
            }
        }
        throw new InvalidParameterValueException("Invalid provisioningtype: " + value + "; use thin, sparse, fat. For the new contract use allocationpolicy/allocationformat");
    }

    /** The old column remains a compatibility value; it is never an execution fallback. */
    public static String legacyColumn(String policy, String format, String explicitLegacy) {
        Map<String, String> intent = request(policy, format, explicitLegacy);
        if (intent.isEmpty()) {
            return explicitLegacy == null ? "thin" : explicitLegacy;
        }
        return "metadata".equals(intent.get(POLICY)) ? "sparse" :
                ("off".equals(intent.get(POLICY)) ? "thin" : "fat");
    }

    /** A linked disk offering owns all disk intent. Compute-only offerings own their internal disk offering. */
    public static Map<String, String> resolve(Map<String, String> explicit, Map<String, String> linked, boolean hasLinkedDisk) {
        if (hasLinkedDisk) {
            if (!explicit.isEmpty()) {
                throw new InvalidParameterValueException("Allocation policy must be defined on the linked disk offering");
            }
            return validateStored(linked);
        }
        return validateStored(explicit);
    }

    public static Map<String, String> validateStored(Map<String, String> details) {
        if (details == null || (!details.containsKey(VERSION) && !details.containsKey(POLICY) && !details.containsKey(FORMAT))) {
            return new LinkedHashMap<>();
        }
        if (!"1".equals(details.get(VERSION)) || details.get(POLICY) == null || details.get(FORMAT) == null) {
            throw new InvalidParameterValueException("Unknown allocation contract version");
        }
        return request(details.get(POLICY), details.get(FORMAT), null);
    }

    public static void requireLegacyExecution(Map<String, String> details) {
        if (!VolumeAllocationLifecycle.intent(details).isEmpty()) {
            throw new InvalidParameterValueException("ALLOCATION_EXECUTION_NOT_IMPLEMENTED: explicit allocation requires a verified backend execution adapter and Agent capability; no legacy fallback is allowed");
        }
    }

    /** Validate immutable creation intent; backend/format/operation capability is checked separately. */
    public static Map<String, String> requireCreationIntent(Map<String, String> details) {
        return VolumeAllocationLifecycle.intent(details);
    }

    public static void requireRbdLegacyThin(Storage.ProvisioningType type) {
        if (type != null && type != Storage.ProvisioningType.THIN) {
            throw new InvalidParameterValueException("RBD_LEGACY_ALLOCATION_UNVERIFIED: sparse/fat cannot guarantee RBD allocation; select explicit raw off/full");
        }
    }

    public static void requireUnqualifiedLifecycle(Map<String, String> details, String operation) {
        if (!VolumeAllocationLifecycle.intent(details).isEmpty()) {
            throw new InvalidParameterValueException("ALLOCATION_OPERATION_UNSUPPORTED: " + operation +
                    " is not qualified for explicit allocation; source and allocation evidence are preserved");
        }
    }

    public static void validateCapability(String backend, String format, String operation, Map<String, String> methods) {
        if (operation == null || !OPERATIONS.contains(operation)) {
            throw new InvalidParameterValueException("Invalid allocation operation: " + operation);
        }
        // These are allowed method identifiers, not a claim that any deployed Agent implements them.
        Map<String, String> allowed = new LinkedHashMap<>();
        if ("GFS2".equals(backend)) {
            allowed.put("full", "qemu-preallocation-full");
            allowed.put("falloc", "qemu-preallocation-falloc");
            allowed.put("qcow2".equals(format) ? "metadata" : "off",
                    "qcow2".equals(format) ? "qemu-preallocation-metadata" : "qemu-preallocation-off");
        } else if ("RBD".equals(backend) && "raw".equals(format)) {
            allowed.put("off", "rbd-thin");
            if ("create".equals(operation) || "copy".equals(operation)) {
                allowed.put("full", "create".equals(operation) ? "rbd-thick-create" : "rbd-thick-convert-n-S0");
            }
        } else {
            throw new InvalidParameterValueException("Unsupported allocation backend/format");
        }
        parse(Format.class, format, "allocationformat");
        if (methods == null || methods.isEmpty() || !methods.entrySet().stream().allMatch(e -> e.getValue() != null && e.getValue().equals(allowed.get(e.getKey())))) {
            throw new InvalidParameterValueException("Agent returned unsupported allocation policy/method for this operation");
        }
    }

    /** For trusted backend adapters, after successful execution and verification. Not a public metadata writer. */
    public static Map<String, String> evidence(Map<String, String> requested, String backend, String operation, String method,
            Long observedBytes, java.time.Instant observedAt, String observedSource) {
        Map<String, String> result = validateStored(requested);
        if (result.isEmpty()) {
            throw new InvalidParameterValueException("Legacy provisioning is not evidence of applied allocation");
        }
        validateCapability(backend, result.get(FORMAT), operation, java.util.Collections.singletonMap(result.get(POLICY), method));
        if ((observedBytes == null) != (observedAt == null) || (observedBytes == null) != (observedSource == null) ||
                (observedBytes != null && (observedBytes < 0 || observedSource.isBlank()))) {
            throw new InvalidParameterValueException("Allocation observation requires non-negative bytes, timestamp and source together");
        }
        result.put(FIXED_POLICY, result.get(POLICY));
        result.put(EVIDENCE_VERSION, "1");
        result.put(BACKEND, backend);
        result.put(OPERATION, operation);
        result.put(METHOD, method);
        if (observedBytes != null) {
            result.put(OBSERVED_BYTES, Long.toString(observedBytes));
            result.put(OBSERVED_AT, observedAt.toString());
            result.put(OBSERVED_SOURCE, observedSource);
        }
        return result;
    }

    public static Map<String, String> describe(Map<String, String> details, String legacy, String actualFormat) {
        Map<String, String> result = new LinkedHashMap<>();
        Map<String, String> intent;
        try {
            intent = validateStored(details);
        } catch (InvalidParameterValueException e) {
            result.put("contractversion", "invalid");
            result.put("fixedpolicy", "unknown");
            result.put("effectivepolicy", "unknown");
            result.put("appliedmethod", "unknown");
            result.put("evidence", "invalid");
            return result;
        }
        result.put("contractversion", intent.isEmpty() ? "legacy" : "1");
        result.put("requestedpolicy", intent.isEmpty() ? legacy : intent.get(POLICY));
        result.put("requestedformat", intent.isEmpty() ? "unspecified" : intent.get(FORMAT));
        result.put("policyorigin", intent.isEmpty() ? "legacy" : "explicit");
        result.put("fixedpolicy", "unknown");
        result.put("effectivepolicy", "unknown");
        result.put("appliedmethod", "unknown");
        result.put("actualformat", actualFormat == null ? "unknown" : actualFormat.toLowerCase(Locale.ROOT));
        result.put("evidence", "unverified");
        if (details != null && details.containsKey(EVIDENCE_VERSION)) {
            try {
                Long bytes = details.containsKey(OBSERVED_BYTES) ? Long.valueOf(details.get(OBSERVED_BYTES)) : null;
                java.time.Instant at = details.containsKey(OBSERVED_AT) ? java.time.Instant.parse(details.get(OBSERVED_AT)) : null;
                evidence(intent, details.get(BACKEND), details.get(OPERATION), details.get(METHOD), bytes, at, details.get(OBSERVED_SOURCE));
                if (!"1".equals(details.get(EVIDENCE_VERSION)) || !intent.get(POLICY).equals(details.get(FIXED_POLICY)) ||
                        (actualFormat != null && !actualFormat.equalsIgnoreCase(intent.get(FORMAT)))) {
                    throw new InvalidParameterValueException("Invalid allocation evidence");
                }
                result.put("fixedpolicy", details.get(FIXED_POLICY));
                result.put("effectivepolicy", details.get(FIXED_POLICY));
                result.put("appliedmethod", details.get(METHOD));
                result.put("appliedbackend", details.get(BACKEND));
                result.put("appliedoperation", details.get(OPERATION));
                result.put("evidence", "verified");
                result.put("evidencescope", "creation-or-copy-time");
                if (bytes != null) {
                    result.put("observedallocatedbytes", bytes.toString());
                    result.put("observedat", at.toString());
                    result.put("observedsource", details.get(OBSERVED_SOURCE));
                }
            } catch (RuntimeException e) {
                // Malformed historical observations must not masquerade as successful allocation.
                result.put("evidence", "invalid");
            }
        }
        return result;
    }
}
