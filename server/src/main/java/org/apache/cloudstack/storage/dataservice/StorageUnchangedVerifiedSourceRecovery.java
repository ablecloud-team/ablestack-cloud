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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/** Confirms an unchanged, already verified SOURCE; never authorizes TARGET adoption or guest replay. */
final class StorageUnchangedVerifiedSourceRecovery {
    static final String RESULT_KEY = "_unchangedVerifiedSourceRecovery";
    private static final Set<String> SCHEMA = Set.of("schemaVersion", "instanceId", "tables");
    private static final Set<String> CHECKPOINTS = Set.of("nativeGeneration", "nativeDesiredState", "localCheckpointIntent",
            "nativeIdentityCapsule", "renderedGeneration", "adServiceSource", "adSamBootstrapAttempted");
    private static final Set<String> TARGETS = Set.of("_response", "_targetResourceBindings", "_targetNativeBootId",
            "_targetConfigurationSha256", "_verifiedPendingNfsAclTargetRecovery", "_legacyDeferredFixtureTargetRecovery",
            "_currentLocalIdentityRecovery", "_leaseCleanupSource", "_leaseSourceNativeGeneration", "_leaseSourceResourceBindings");

    private StorageUnchangedVerifiedSourceRecovery() { }

    static boolean eligible(StorageServiceOperationVO operation) {
        if (operation == null || !"createstoragenfsexportresponse".equals(operation.getAction())
                || !"RECOVERY_REQUIRED".equals(operation.getState()) || operation.getPreviousSnapshotJson() == null
                || operation.getSnapshotJson() != null) return false;
        JsonObject previous = object(operation.getPreviousSnapshotJson()), result = object(operation.getResultJson());
        // Once our intent exists, changed checkpoints must fail validation, never fall back to replay.
        if (result.has(RESULT_KEY)) return true;
        return CHECKPOINTS.stream().noneMatch(previous::has) && TARGETS.stream().noneMatch(result::has);
    }

    static JsonObject require(String instanceUuid, StorageServiceOperationVO original, StorageServiceOperationVO source,
            String currentSnapshot, JsonObject bindings, JsonObject status, JsonObject frozen) {
        try {
            need(eligible(original) && source != null && uuid(instanceUuid)
                    && Set.of("INTERRUPTED_RECOVERY_REQUIRED", "RECOVERY_REQUIRED").contains(original.getPhase()));
            need(original.getId() > 0 && source.getId() > 0 && original.getId() != source.getId()
                    && original.getInstanceId() == source.getInstanceId() && original.getInstanceId() > 0
                    && original.getCreatedBy() == source.getCreatedBy() && original.getCreatedBy() > 0
                    && uuid(original.getUuid()) && uuid(source.getUuid()) && !original.getUuid().equals(source.getUuid())
                    && source.getRevision() > 0 && source.getRevision() < original.getRevision()
                    && "COMPLETE".equals(source.getState()) && "COMPLETE".equals(source.getPhase()));
            JsonObject previous = object(original.getPreviousSnapshotJson()), current = object(currentSnapshot);
            JsonObject target = object(source.getSnapshotJson()), result = object(original.getResultJson()), witness = object(source.getResultJson());
            need(previous.keySet().equals(SCHEMA) && target.keySet().equals(SCHEMA) && current.keySet().equals(SCHEMA));
            StorageServiceDesiredSnapshot.validateSnapshot(original.getInstanceId(), previous.toString());
            StorageServiceDesiredSnapshot.validateSnapshot(original.getInstanceId(), target.toString());
            StorageServiceDesiredSnapshot.validateSnapshot(original.getInstanceId(), current.toString());
            need(same(previous, target) && same(previous, current));
            need(result.keySet().stream().allMatch(k -> "_requestFingerprint".equals(k) || RESULT_KEY.equals(k)));
            need(result.has("_requestFingerprint") && !text(result, "_requestFingerprint").isBlank() && text(result, "_requestFingerprint").length() <= 256);
            need(CHECKPOINTS.stream().noneMatch(previous::has) && TARGETS.stream().noneMatch(result::has));
            need(witness.has("_response") && witness.get("_response").isJsonObject());
            requireBindings(bindings);
            need(witness.has("_targetResourceBindings") && witness.get("_targetResourceBindings").isJsonObject()
                    && same(bindings, witness.get("_targetResourceBindings")));
            String boot = text(witness, "_targetNativeBootId"), configuration = text(witness, "_targetConfigurationSha256");
            need(uuid(boot) && configuration.matches("[a-f0-9]{64}"));
            need(bool(status, "success") && bool(status, "generationSupported") && "IN_SYNC".equals(text(status, "generationStatus"))
                    && status.has("pendingOperationUuid") && status.get("pendingOperationUuid").isJsonNull()
                    && boot.equals(text(status, "bootId")) && configuration.equals(text(status, "configurationSha256"))
                    && whole(status, "runtimeRevision") == source.getRevision());
            JsonObject generation = status.getAsJsonObject("generation");
            need(generation != null && generation.keySet().equals(Set.of("instanceUuid", "operationUuid", "revision", "configurationSha256", "verifiedAt"))
                    && instanceUuid.equals(text(generation, "instanceUuid")) && source.getUuid().equals(text(generation, "operationUuid"))
                    && whole(generation, "revision") == source.getRevision() && configuration.equals(text(generation, "configurationSha256")));
            JsonElement verified = generation.get("verifiedAt");
            need(verified != null && verified.isJsonPrimitive() && verified.getAsJsonPrimitive().isNumber()
                    && Double.isFinite(verified.getAsDouble()) && verified.getAsDouble() > 0);
            need(bool(frozen, "success") && bool(frozen, "generationSupported") && bool(frozen, "frozen")
                    && same(generation, frozen.get("generation")) && configuration.equals(text(frozen, "configurationSha256")));
            JsonObject desired = status.getAsJsonObject("configurationDesiredState");
            need(desired != null && desired.keySet().equals(StorageRenderedDesiredState.PATHS)
                    && same(desired, frozen.get("configurationDesiredState")));
            JsonObject proof = new JsonObject();proof.addProperty("kind", "UNCHANGED_VERIFIED_SOURCE");
            proof.addProperty("instanceUuid", instanceUuid);proof.addProperty("operationUuid", original.getUuid());
            proof.addProperty("revision", original.getRevision());proof.addProperty("phase", original.getPhase());
            proof.addProperty("createdBy", original.getCreatedBy());proof.addProperty("sourceOperationUuid", source.getUuid());
            proof.addProperty("sourceRevision", source.getRevision());proof.addProperty("bootId", boot);
            proof.addProperty("configurationSha256", configuration);proof.addProperty("snapshotSha256", digest(previous));
            proof.addProperty("resourceBindingsSha256", digest(bindings));proof.addProperty("sourceGenerationSha256", digest(generation));
            proof.addProperty("sourceDesiredSha256", digest(desired));proof.addProperty("sourceResultSha256", digest(witness));
            proof.addProperty("requestFingerprintSha256", digest(result.get("_requestFingerprint")));return proof;
        } catch (RuntimeException invalid) {
            throw new CloudRuntimeException("Unchanged verified SOURCE requires exact original intent, completed witness and current state", invalid);
        }
    }

    private static void requireBindings(JsonObject value) {
        need(value != null && value.keySet().equals(Set.of("vmId", "vmUuid", "rootVolumeUuid", "rootVolumeId", "templateId", "dataVolumes"))
                && whole(value, "vmId") > 0 && whole(value, "rootVolumeId") > 0 && whole(value, "templateId") > 0
                && uuid(text(value, "vmUuid")) && uuid(text(value, "rootVolumeUuid")));
        JsonObject data = value.getAsJsonObject("dataVolumes");need(data != null && data.size() > 0 && data.size() <= 16);
        for (String volume : data.keySet()) {
            JsonObject row = data.getAsJsonObject(volume);
            need(uuid(volume) && row.keySet().equals(Set.of("sizeBytes", "poolId", "path", "deviceId", "provisioningType"))
                    && whole(row, "sizeBytes") > 0 && whole(row, "poolId") > 0 && whole(row, "deviceId") >= 0
                    && !text(row, "path").isBlank() && !text(row, "provisioningType").isBlank());
        }
    }

    static boolean same(JsonElement first, JsonElement second) {
        if (first == null || second == null) return first == second;
        if (first.isJsonNull() || second.isJsonNull()) return first.isJsonNull() && second.isJsonNull();
        if (first.isJsonObject() && second.isJsonObject()) {
            JsonObject a = first.getAsJsonObject(), b = second.getAsJsonObject();
            if (!a.keySet().equals(b.keySet())) return false;
            for (String key : a.keySet()) if (!same(a.get(key), b.get(key))) return false;
            return true;
        }
        if (first.isJsonArray() && second.isJsonArray()) {
            JsonArray a = first.getAsJsonArray(), b = second.getAsJsonArray();if (a.size() != b.size()) return false;
            for (int i = 0; i < a.size(); i++) if (!same(a.get(i), b.get(i))) return false;
            return true;
        }
        if (!first.isJsonPrimitive() || !second.isJsonPrimitive()) return false;
        JsonPrimitive a = first.getAsJsonPrimitive(), b = second.getAsJsonPrimitive();
        return a.isBoolean() == b.isBoolean() && a.isNumber() == b.isNumber() && a.isString() == b.isString()
                && a.getAsString().equals(b.getAsString());
    }

    private static JsonElement canonical(JsonElement element) {
        if (element.isJsonObject()) {
            JsonObject out = new JsonObject();
            for (String key : new TreeSet<>(element.getAsJsonObject().keySet())) out.add(key, canonical(element.getAsJsonObject().get(key)));
            return out;
        }
        if (element.isJsonArray()) {
            JsonArray out = new JsonArray();for (JsonElement row : element.getAsJsonArray()) out.add(canonical(row));return out;
        }
        return element.deepCopy();
    }

    private static String digest(JsonElement element) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(canonical(element).toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();for (byte value : bytes) out.append(String.format("%02x", value & 255));return out.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static JsonObject object(String text) {
        need(text != null && text.getBytes(StandardCharsets.UTF_8).length <= 16 * 1024 * 1024);
        return JsonParser.parseString(text).getAsJsonObject();
    }
    private static String text(JsonObject object, String key) {
        JsonElement value = object.get(key);need(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString());
        return value.getAsString();
    }
    private static long whole(JsonObject object, String key) {
        JsonElement value = object.get(key);need(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                && value.getAsString().matches("[0-9]+"));return Long.parseLong(value.getAsString());
    }
    private static boolean bool(JsonObject object, String key) {
        JsonElement value = object.get(key);return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean();
    }
    private static boolean uuid(String value) {
        try { return value != null && UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException invalid) { return false; }
    }
    private static void need(boolean value) { if (!value) throw new IllegalArgumentException("UNCHANGED_SOURCE_PROOF_MISMATCH"); }
}
