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

import org.junit.Assert;
import org.junit.Test;
import com.google.gson.JsonObject;
import com.google.gson.JsonNull;
import com.cloud.utils.exception.CloudRuntimeException;

public class StorageRuntimeVersionCompatibilityTest {
    private static JsonObject manifest() {
        JsonObject value = new JsonObject();value.addProperty("bundleVersion", "runtime-immutable-1");JsonObject compat = new JsonObject();compat.addProperty("schemaVersion", 1);
        for (String key : new String[] {"manager", "agent", "template"}) {JsonObject range = new JsonObject();range.addProperty("minimumVersion", "4.23.0.0");range.addProperty("maximumVersionExclusive", "4.24.0.0");compat.add(key, range);}
        value.add("compatibility", compat);return value;
    }
    private static JsonObject evaluate(JsonObject manifest, String manager, String agent, String template) {
        return StorageRuntimeVersionCompatibility.evaluate(manifest, manager, agent, template, StorageRuntimeVersionCompatibility.Mode.NEW_ACTIVATION, null);
    }
    private static void allowed(JsonObject result) {Assert.assertTrue(result.toString(), result.get("compatible").getAsBoolean());StorageRuntimeVersionCompatibility.requireCompatible(result);}
    private static void blocked(JsonObject result, String code) {Assert.assertFalse(result.toString(), result.get("compatible").getAsBoolean());Assert.assertTrue(result.getAsJsonArray("blockers").toString(), result.getAsJsonArray("blockers").toString().contains(code));Assert.assertThrows(CloudRuntimeException.class, () -> StorageRuntimeVersionCompatibility.requireCompatible(result));}
    private static StorageRuntimeVersionCompatibility.RetainedPreviousEvidence evidence(boolean lkg, boolean feature, boolean provenance, boolean root, String version, String manifestHash, String archiveHash) {
        return new StorageRuntimeVersionCompatibility.RetainedPreviousEvidence(version, "a".repeat(64), manifestHash, "b".repeat(64), archiveHash, lkg, feature, provenance, root);
    }

    @Test public void inclusiveMinimumAndExclusiveMaximumUseOfficialCloudStackComparison() {
        allowed(evaluate(manifest(), "4.23.0.0", "4.23.0.1", "4.23.99.9"));
        blocked(evaluate(manifest(), "4.22.9.9", "4.23.0.0", "4.23.0.0"), "MANAGER_VERSION_INCOMPATIBLE");
        blocked(evaluate(manifest(), "4.23.0.0", "4.24.0.0", "4.23.0.0"), "AGENT_VERSION_INCOMPATIBLE");
        blocked(evaluate(manifest(), "4.23.0.0", "4.23.0.0", "4.24.0.0"), "TEMPLATE_VERSION_INCOMPATIBLE");
    }
    @Test public void threeFourPartsBrandingAndSnapshotMatchOfficialNormalization() {
        JsonObject result = evaluate(manifest(), "4.23.0.0-Mold.Europa-202610011115", "4.23.0-SNAPSHOT", "4.23.0.0-88");allowed(result);
        Assert.assertEquals("4.23.0.0", result.getAsJsonObject("consumers").getAsJsonObject("manager").get("normalizedCurrentVersion").getAsString());
    }
    @Test public void fourthNumericSecurityComponentIsNotDropped() {
        JsonObject m = manifest();m.getAsJsonObject("compatibility").getAsJsonObject("template").addProperty("minimumVersion", "4.23.0.2");
        blocked(evaluate(m, "4.23.0.0", "4.23.0.0", "4.23.0.1"), "TEMPLATE_VERSION_INCOMPATIBLE");allowed(evaluate(m, "4.23.0.0", "4.23.0.0", "4.23.0.2"));
    }
    @Test public void templateFilenameFiveComponentBuildCannotReplaceFreshPlatformVersion() {
        for (String invalid : new String[] {"4.23.0.0.88", "systemvmtemplate-4.23.0.0.88-x86_64-kvm.qcow2", "epic898-20261008", "unknown"}) {
            blocked(evaluate(manifest(), "4.23.0.0", "4.23.0.0", invalid), invalid.equals("unknown") ? "TEMPLATE_VERSION_UNAVAILABLE" : "TEMPLATE_VERSION_INVALID");
        }
    }
    @Test public void unknownNullOrMalformedConsumersFailClosedInsteadOfBorrowingManagerVersion() {
        blocked(evaluate(manifest(), "4.23.0.0", null, "4.23.0.0"), "AGENT_VERSION_UNAVAILABLE");
        blocked(evaluate(manifest(), "4.23.0.0", "4.23.0.0", null), "TEMPLATE_VERSION_UNAVAILABLE");
        for (String invalid : new String[] {"4.23", "4.23.0.0x", "4.23.0.0\n", "4294967296.0.0", "4.23.0--" + "x".repeat(130)}) blocked(evaluate(manifest(), invalid, "4.23.0.0", "4.23.0.0"), "MANAGER_VERSION_INVALID");
    }
    @Test public void missingNullWrongTypeOrUnknownCompatibilitySchemaNeverActivates() {
        JsonObject legacy = manifest();legacy.remove("compatibility");blocked(evaluate(legacy, "4.23.0.0", "4.23.0.0", "4.23.0.0"), "SIGNED_COMPATIBILITY_RANGE_MISSING");
        for (String schema : new String[] {"null", "1.5", "2", "\"1\"", "{}"}) {JsonObject m = manifest();m.getAsJsonObject("compatibility").add("schemaVersion", com.google.gson.JsonParser.parseString(schema));blocked(evaluate(m, "4.23.0.0", "4.23.0.0", "4.23.0.0"), "SIGNED_COMPATIBILITY_SCHEMA_INVALID");}
        JsonObject invalid = manifest();invalid.add("compatibility", JsonNull.INSTANCE);blocked(evaluate(invalid, "4.23.0.0", "4.23.0.0", "4.23.0.0"), "SIGNED_COMPATIBILITY_SCHEMA_INVALID");
    }
    @Test public void missingUpperLowerConsumerOrUnknownSignedFieldIsRejected() {
        JsonObject missing = manifest();missing.getAsJsonObject("compatibility").remove("agent");blocked(evaluate(missing, "4.23.0.0", "4.23.0.0", "4.23.0.0"), "SIGNED_COMPATIBILITY_SCHEMA_INVALID");
        for (String field : new String[] {"minimumVersion", "maximumVersionExclusive"}) {JsonObject m = manifest();m.getAsJsonObject("compatibility").getAsJsonObject("manager").remove(field);blocked(evaluate(m, "4.23.0.0", "4.23.0.0", "4.23.0.0"), "MANAGER_RANGE_INVALID");}
        JsonObject unknown = manifest();unknown.getAsJsonObject("compatibility").getAsJsonObject("agent").addProperty("maximumVersion", "4.24.0.0");blocked(evaluate(unknown, "4.23.0.0", "4.23.0.0", "4.23.0.0"), "AGENT_RANGE_INVALID");
    }
    @Test public void emptyInvertedMalformedOrOverflowRangeCannotPass() {
        for (String minimum : new String[] {"4.24.0.0", "4.25.0.0", "4294967296.0.0", "4.23.0.0.88", "", "unknown"}) {JsonObject m = manifest();m.getAsJsonObject("compatibility").getAsJsonObject("manager").addProperty("minimumVersion", minimum);blocked(evaluate(m, "4.23.0.0", "4.23.0.0", "4.23.0.0"), "MANAGER_RANGE_INVALID");}
    }
    @Test public void legacyCryptoVerifiedRowsStayDescribableButNewActivationIsIncompatible() {
        JsonObject legacy = manifest();legacy.remove("compatibility");JsonObject result = evaluate(legacy, "4.23.0.0", "4.23.0.0", "4.23.0.0");
        blocked(result, "SIGNED_COMPATIBILITY_RANGE_MISSING");Assert.assertFalse(result.get("compatibilityDeclared").getAsBoolean());Assert.assertFalse(result.get("legacyExceptionApplied").getAsBoolean());
        Assert.assertEquals("LEGACY_RANGE_UNDECLARED", result.getAsJsonObject("consumers").getAsJsonObject("template").get("state").getAsString());
    }
    @Test public void onlyExplicitRetainedPreviousModeAndVerifiedReceiptCanUseLegacyException() {
        JsonObject legacy = manifest();legacy.remove("compatibility");StorageRuntimeVersionCompatibility.RetainedPreviousEvidence approved = evidence(true,true,true,true,"runtime-immutable-1","a".repeat(64),"b".repeat(64));
        JsonObject retained = StorageRuntimeVersionCompatibility.evaluate(legacy, null, null, null, StorageRuntimeVersionCompatibility.Mode.RETAINED_PREVIOUS_ROLLBACK, approved);allowed(retained);
        Assert.assertTrue(retained.get("legacyExceptionApplied").getAsBoolean());Assert.assertFalse(retained.get("rangeCompatibilityVerified").getAsBoolean());Assert.assertEquals("LEGACY_RETAINED_PREVIOUS_APPROVED", retained.get("state").getAsString());
        blocked(StorageRuntimeVersionCompatibility.evaluate(legacy,"4.23.0.0","4.23.0.0","4.23.0.0",StorageRuntimeVersionCompatibility.Mode.NEW_ACTIVATION,approved), "SIGNED_COMPATIBILITY_RANGE_MISSING");
    }
    @Test public void missingLkgFeatureProvenanceBindingOrPinMismatchRejectsLegacyRollback() {
        JsonObject legacy = manifest();legacy.remove("compatibility");
        for (StorageRuntimeVersionCompatibility.RetainedPreviousEvidence invalid : new StorageRuntimeVersionCompatibility.RetainedPreviousEvidence[] {
            null,evidence(false,true,true,true,"runtime-immutable-1","a".repeat(64),"b".repeat(64)),evidence(true,false,true,true,"runtime-immutable-1","a".repeat(64),"b".repeat(64)),
            evidence(true,true,false,true,"runtime-immutable-1","a".repeat(64),"b".repeat(64)),evidence(true,true,true,false,"runtime-immutable-1","a".repeat(64),"b".repeat(64)),
            evidence(true,true,true,true,"different-runtime","a".repeat(64),"b".repeat(64)),evidence(true,true,true,true,"runtime-immutable-1","c".repeat(64),"b".repeat(64)),
            evidence(true,true,true,true,"runtime-immutable-1","a".repeat(64),"c".repeat(64))}) {
            blocked(StorageRuntimeVersionCompatibility.evaluate(legacy,"4.23.0.0","4.23.0.0","4.23.0.0",StorageRuntimeVersionCompatibility.Mode.RETAINED_PREVIOUS_ROLLBACK,invalid), "RETAINED_PREVIOUS_PROVENANCE_UNVERIFIED");
        }
    }
    @Test public void retainedEvidenceCannotOverrideDeclaredIncompatibleOrMalformedRanges() {
        StorageRuntimeVersionCompatibility.RetainedPreviousEvidence approved = evidence(true,true,true,true,"runtime-immutable-1","a".repeat(64),"b".repeat(64));
        blocked(StorageRuntimeVersionCompatibility.evaluate(manifest(),"4.24.0.0","4.23.0.0","4.23.0.0",StorageRuntimeVersionCompatibility.Mode.RETAINED_PREVIOUS_ROLLBACK,approved), "MANAGER_VERSION_INCOMPATIBLE");
        JsonObject malformed = manifest();malformed.getAsJsonObject("compatibility").addProperty("schemaVersion",2);blocked(StorageRuntimeVersionCompatibility.evaluate(malformed,"4.23.0.0","4.23.0.0","4.23.0.0",StorageRuntimeVersionCompatibility.Mode.RETAINED_PREVIOUS_ROLLBACK,approved), "SIGNED_COMPATIBILITY_SCHEMA_INVALID");
    }
    @Test public void evaluationIsPureAndMissingContextIsExplicitlyIncompatible() {
        JsonObject m = manifest(), before = m.deepCopy();evaluate(m,"4.23.0.0","4.23.0.0","4.23.0.0");Assert.assertEquals(before,m);
        blocked(StorageRuntimeVersionCompatibility.evaluate(null,null,null,null,null,null), "SIGNED_COMPATIBILITY_CONTEXT_UNAVAILABLE");
    }
    private static StorageRuntimeVersionCompatibility.RetainedPreviousEvidence originalUnknownEvidence(boolean observedUnknown) {
        return new StorageRuntimeVersionCompatibility.RetainedPreviousEvidence("runtime-immutable-1", "a".repeat(64), "a".repeat(64), "b".repeat(64), "b".repeat(64), true, true, true, true, observedUnknown);
    }
    @Test public void declaredRangesCanRetainOnlyTheExplicitlyObservedUnknownOriginalSourcePlatform() {
        JsonObject m = manifest();StorageRuntimeVersionCompatibility.RetainedPreviousEvidence prior = originalUnknownEvidence(true);
        JsonObject permitted = StorageRuntimeVersionCompatibility.evaluate(m,"4.23.0.0","4.23.0.0",null,StorageRuntimeVersionCompatibility.Mode.RETAINED_PREVIOUS_ROLLBACK,prior);allowed(permitted);
        Assert.assertTrue(permitted.get("legacyExceptionApplied").getAsBoolean());Assert.assertFalse(permitted.get("rangeCompatibilityVerified").getAsBoolean());
        Assert.assertEquals("RETAINED_PREVIOUS_PLATFORM_UNKNOWN_APPROVED",permitted.get("state").getAsString());
        blocked(StorageRuntimeVersionCompatibility.evaluate(m,"4.23.0.0","4.23.0.0",null,StorageRuntimeVersionCompatibility.Mode.RETAINED_PREVIOUS_ROLLBACK,originalUnknownEvidence(false)),"TEMPLATE_VERSION_UNAVAILABLE");
        blocked(StorageRuntimeVersionCompatibility.evaluate(m,"4.23.0.0","4.23.0.0",null,StorageRuntimeVersionCompatibility.Mode.NEW_ACTIVATION,prior),"TEMPLATE_VERSION_UNAVAILABLE");
    }
    @Test public void sourceUnknownExceptionCannotBypassKnownIncompatibilityMalformedValueOrOtherConsumer() {
        StorageRuntimeVersionCompatibility.RetainedPreviousEvidence prior = originalUnknownEvidence(true);
        blocked(StorageRuntimeVersionCompatibility.evaluate(manifest(),"4.24.0.0","4.23.0.0",null,StorageRuntimeVersionCompatibility.Mode.RETAINED_PREVIOUS_ROLLBACK,prior),"MANAGER_VERSION_INCOMPATIBLE");
        blocked(StorageRuntimeVersionCompatibility.evaluate(manifest(),"4.23.0.0",null,null,StorageRuntimeVersionCompatibility.Mode.RETAINED_PREVIOUS_ROLLBACK,prior),"AGENT_VERSION_UNAVAILABLE");
        blocked(StorageRuntimeVersionCompatibility.evaluate(manifest(),"4.23.0.0","4.23.0.0","4.23.0.0.88",StorageRuntimeVersionCompatibility.Mode.RETAINED_PREVIOUS_ROLLBACK,prior),"TEMPLATE_VERSION_INVALID");
        blocked(StorageRuntimeVersionCompatibility.evaluate(manifest(),"4.23.0.0","4.23.0.0","4.24.0.0",StorageRuntimeVersionCompatibility.Mode.RETAINED_PREVIOUS_ROLLBACK,prior),"TEMPLATE_VERSION_INCOMPATIBLE");
    }

}
