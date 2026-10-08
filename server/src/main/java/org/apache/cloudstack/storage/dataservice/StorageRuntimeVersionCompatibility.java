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
package org.apache.cloudstack.storage.dataservice;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.apache.cloudstack.utils.CloudStackVersion;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

/** Signed consumer ranges; callers supply fresh platform observations, never template filenames or manager projections. */
public final class StorageRuntimeVersionCompatibility {
    public enum Mode { NEW_ACTIVATION, RETAINED_PREVIOUS_ROLLBACK }

    /** Protected server evidence only. Never construct this from API request fields or unverified catalog metadata. */
    public static final class RetainedPreviousEvidence {
        private final String pinnedBundleVersion, pinnedManifestSha256, verifiedManifestSha256, pinnedArchiveSha256, verifiedArchiveSha256;
        private final boolean approvedLkgReceipt, featureCompatibilityVerified, provenanceVerified, previousRootBindingVerified;
        public RetainedPreviousEvidence(String pinnedBundleVersion, String pinnedManifestSha256, String verifiedManifestSha256,
                String pinnedArchiveSha256, String verifiedArchiveSha256, boolean approvedLkgReceipt,
                boolean featureCompatibilityVerified, boolean provenanceVerified, boolean previousRootBindingVerified) {
            this.pinnedBundleVersion = pinnedBundleVersion;this.pinnedManifestSha256 = pinnedManifestSha256;this.verifiedManifestSha256 = verifiedManifestSha256;
            this.pinnedArchiveSha256 = pinnedArchiveSha256;this.verifiedArchiveSha256 = verifiedArchiveSha256;this.approvedLkgReceipt = approvedLkgReceipt;
            this.featureCompatibilityVerified = featureCompatibilityVerified;this.provenanceVerified = provenanceVerified;this.previousRootBindingVerified = previousRootBindingVerified;
        }
        private boolean permits(JsonObject manifest) {
            return approvedLkgReceipt && featureCompatibilityVerified && provenanceVerified && previousRootBindingVerified
                    && pinnedBundleVersion != null && pinnedBundleVersion.equals(string(manifest, "bundleVersion"))
                    && sameDigest(pinnedManifestSha256, verifiedManifestSha256) && sameDigest(pinnedArchiveSha256, verifiedArchiveSha256);
        }
    }
    private StorageRuntimeVersionCompatibility() { }

    public static JsonObject evaluate(JsonObject verifiedManifest, String managerVersion, String agentVersion,
            String templatePlatformVersion, Mode mode, RetainedPreviousEvidence retainedEvidence) {
        JsonObject result = new JsonObject();JsonArray blockers = new JsonArray();JsonArray warnings = new JsonArray();JsonObject consumers = new JsonObject();
        result.addProperty("mode", mode == null ? "UNKNOWN" : mode.name());result.addProperty("rangeCompatibilityVerified", false);
        result.addProperty("legacyExceptionApplied", false);result.add("consumers", consumers);result.add("blockers", blockers);result.add("warnings", warnings);
        Map<String, String> actual = new LinkedHashMap<>();actual.put("manager", managerVersion);actual.put("agent", agentVersion);actual.put("template", templatePlatformVersion);
        if (verifiedManifest == null || mode == null) return rejected(result, blockers, "SIGNED_COMPATIBILITY_CONTEXT_UNAVAILABLE");
        if (!verifiedManifest.has("compatibility")) {
            result.addProperty("compatibilityDeclared", false);
            for (Map.Entry<String, String> item : actual.entrySet()) {
                JsonObject consumer = current(item.getValue());consumer.addProperty("compatible", false);consumer.addProperty("state", "LEGACY_RANGE_UNDECLARED");consumers.add(item.getKey(), consumer);
            }
            if (mode == Mode.RETAINED_PREVIOUS_ROLLBACK && retainedEvidence != null && retainedEvidence.permits(verifiedManifest)) {
                result.addProperty("compatible", true);result.addProperty("state", "LEGACY_RETAINED_PREVIOUS_APPROVED");result.addProperty("legacyExceptionApplied", true);warnings.add("SIGNED_COMPATIBILITY_RANGE_MISSING");return result;
            }
            if (mode == Mode.RETAINED_PREVIOUS_ROLLBACK) blockers.add("RETAINED_PREVIOUS_PROVENANCE_UNVERIFIED");
            return rejected(result, blockers, "SIGNED_COMPATIBILITY_RANGE_MISSING");
        }
        result.addProperty("compatibilityDeclared", true);
        JsonElement declared = verifiedManifest.get("compatibility");
        if (!declared.isJsonObject()) return rejected(result, blockers, "SIGNED_COMPATIBILITY_SCHEMA_INVALID");
        JsonObject compatibility = declared.getAsJsonObject();
        if (!compatibility.keySet().equals(Set.of("schemaVersion", "manager", "agent", "template")) || !schemaOne(compatibility.get("schemaVersion"))) {
            return rejected(result, blockers, "SIGNED_COMPATIBILITY_SCHEMA_INVALID");
        }
        for (Map.Entry<String, String> item : actual.entrySet()) {
            JsonObject consumer = current(item.getValue());consumers.add(item.getKey(), consumer);String prefix = item.getKey().toUpperCase(java.util.Locale.ROOT);
            JsonElement range = compatibility.get(item.getKey());
            if (!range.isJsonObject() || !range.getAsJsonObject().keySet().equals(Set.of("minimumVersion", "maximumVersionExclusive"))) {
                consumer.addProperty("compatible", false);consumer.addProperty("state", "RANGE_INVALID");consumer.addProperty("errorCode", prefix + "_RANGE_INVALID");blockers.add(prefix + "_RANGE_INVALID");continue;
            }
            String minimum = string(range.getAsJsonObject(), "minimumVersion"), maximum = string(range.getAsJsonObject(), "maximumVersionExclusive");
            consumer.add("minimumVersion", nullable(minimum));consumer.add("maximumVersionExclusive", nullable(maximum));
            CloudStackVersion lower = parse(minimum), upper = parse(maximum), observed = parse(item.getValue());
            if (lower == null || upper == null || lower.compareTo(upper) >= 0) {
                consumer.addProperty("compatible", false);consumer.addProperty("state", "RANGE_INVALID");consumer.addProperty("errorCode", prefix + "_RANGE_INVALID");blockers.add(prefix + "_RANGE_INVALID");continue;
            }
            consumer.addProperty("normalizedMinimumVersion", lower.toString());consumer.addProperty("normalizedMaximumVersionExclusive", upper.toString());
            if (observed == null) {
                String code = prefix + (item.getValue() == null || item.getValue().isBlank() || "unknown".equalsIgnoreCase(item.getValue()) ? "_VERSION_UNAVAILABLE" : "_VERSION_INVALID");
                consumer.addProperty("compatible", false);consumer.addProperty("state", "OBSERVATION_UNAVAILABLE");consumer.addProperty("errorCode", code);blockers.add(code);continue;
            }
            boolean inside = observed.compareTo(lower) >= 0 && observed.compareTo(upper) < 0;
            consumer.addProperty("compatible", inside);consumer.addProperty("state", inside ? "COMPATIBLE" : "OUTSIDE_RANGE");
            if (!inside) {consumer.addProperty("errorCode", prefix + "_VERSION_INCOMPATIBLE");blockers.add(prefix + "_VERSION_INCOMPATIBLE");}
        }
        boolean compatible = blockers.size() == 0;result.addProperty("compatible", compatible);result.addProperty("rangeCompatibilityVerified", compatible);result.addProperty("state", compatible ? "COMPATIBLE" : "INCOMPATIBLE");return result;
    }

    public static void requireCompatible(JsonObject verdict) {
        if (!verdict.has("compatible") || !verdict.get("compatible").isJsonPrimitive() || !verdict.get("compatible").getAsJsonPrimitive().isBoolean() || !verdict.get("compatible").getAsBoolean()) {
            throw new CloudRuntimeException("Runtime consumer compatibility is blocked: " + (verdict.has("blockers") ? verdict.get("blockers").toString() : "UNVERIFIED"));
        }
    }
    private static JsonObject current(String raw) {JsonObject value = new JsonObject();value.add("currentVersion", nullable(raw));CloudStackVersion parsed = parse(raw);if (parsed != null) value.addProperty("normalizedCurrentVersion", parsed.toString());return value;}
    private static JsonObject rejected(JsonObject result, JsonArray blockers, String code) {blockers.add(code);result.addProperty("compatible", false);result.addProperty("state", "INCOMPATIBLE");return result;}
    private static JsonElement nullable(String value) {return value == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(value);}
    private static String string(JsonObject value, String key) {JsonElement element = value.get(key);return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString() ? element.getAsString() : null;}
    private static CloudStackVersion parse(String value) {
        if (value == null || value.length() > 128 || !value.matches("[0-9]+(?:\\.[0-9]+){2,3}(?:-[A-Za-z0-9._-]+)?")) return null;
        try {return CloudStackVersion.parse(value);} catch (IllegalArgumentException | IllegalStateException invalid) {return null;}
    }
    private static boolean schemaOne(JsonElement value) {try {return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber() && value.getAsBigDecimal().intValueExact() == 1;}catch (ArithmeticException invalid) {return false;}}
    private static boolean sameDigest(String expected, String observed) {return expected != null && expected.matches("[a-f0-9]{64}") && expected.equals(observed);}
}
