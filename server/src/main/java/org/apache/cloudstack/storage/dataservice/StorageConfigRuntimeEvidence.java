// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.cloudstack.storage.dataservice;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonObject;

/** A diagnostic cache bound to a freshly verified native generation; never an apply input. */
final class StorageConfigRuntimeEvidence {
    private static final Set<String> COLLECTORS = Set.of("health", "inventory");
    private StorageConfigRuntimeEvidence() { }

    static JsonObject generation(JsonObject status, String instance, String operation, long revision) {
        require(revision > 0 && status != null && flag(status, "success") && flag(status, "generationSupported"), "Native generation is unavailable");
        JsonObject generation = object(status, "generation");
        require(generation.keySet().equals(Set.of("instanceUuid", "operationUuid", "revision", "configurationSha256", "verifiedAt"))
                && generation.get("verifiedAt").isJsonPrimitive() && generation.get("verifiedAt").getAsJsonPrimitive().isNumber()
                && Double.isFinite(generation.get("verifiedAt").getAsDouble()) && generation.get("verifiedAt").getAsDouble() > 0,
                "Native generation verification timestamp is invalid");
        require(instance.equals(text(generation, "instanceUuid")) && operation.equals(text(generation, "operationUuid"))
                && whole(generation, "revision") == revision && whole(status, "runtimeRevision") == revision, "Native generation scope differs");
        String boot = text(status, "bootId"), checksum = text(status, "configurationSha256");
        require(boot.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
                && checksum.matches("[a-f0-9]{64}") && checksum.equals(text(generation, "configurationSha256")), "Native generation identity differs");
        require(status.has("pendingOperationUuid"), "Native pending writer observation is absent");
        boolean pending = !status.get("pendingOperationUuid").isJsonNull();
        require(pending ? operation.equals(text(status, "pendingOperationUuid")) && "PENDING".equals(text(status, "generationStatus"))
                : "IN_SYNC".equals(text(status, "generationStatus")), "Native generation has a foreign or unsettled writer");
        JsonObject result = new JsonObject();
        for (String field : new String[] {"bootId", "generation", "configurationSha256", "runtimeRevision", "generationStatus", "pendingOperationUuid"})
            result.add(field, status.get(field).deepCopy());
        return result;
    }

    static JsonObject create(String instance, String operation, long revision, String snapshot,
            JsonObject before, JsonObject after, Map<String, byte[]> entries, long now) {
        JsonObject first = generation(before, instance, operation, revision), last = generation(after, instance, operation, revision);
        require(first.equals(last), "Native generation changed while collecting verified runtime");
        JsonObject result = new JsonObject();result.addProperty("schemaVersion", 1);result.addProperty("source", "VERIFIED_RUNTIME");
        result.addProperty("instanceUuid", instance);result.addProperty("operationUuid", operation);result.addProperty("desiredRevision", revision);
        result.addProperty("observedAtMillis", now);result.addProperty("desiredSnapshotSha256", StorageConfigArchive.sha256(snapshot.getBytes(StandardCharsets.UTF_8)));
        result.add("nativeGeneration", last);
        JsonObject collectors = new JsonObject();
        for (String command : COLLECTORS) {
            byte[] bytes = entries.get("runtime/" + command + ".json");
            require(bytes != null, "Verified runtime collector is absent");
            JsonObject value = StorageConfigArchive.json(bytes).getAsJsonObject();
            require(flag(value, "success") && "ok".equalsIgnoreCase(text(value, "status")), "Runtime collector is not verified");
            requireEpoch(value, now);
            JsonObject collector = new JsonObject();collector.addProperty("sha256", StorageConfigArchive.sha256(bytes));
            collector.add("generatedEpoch", value.get("generatedEpoch").deepCopy());collectors.add(command, collector);
        }
        result.add("collectors", collectors);
        return result;
    }

    static void requireStored(JsonObject evidence, String instance, String operation, long revision, Map<String, byte[]> entries) {
        require(whole(evidence, "schemaVersion") == 1 && "VERIFIED_RUNTIME".equals(text(evidence, "source"))
                && instance.equals(text(evidence, "instanceUuid")) && operation.equals(text(evidence, "operationUuid"))
                && whole(evidence, "desiredRevision") == revision, "Stored verified runtime scope differs");
        long observed = whole(evidence, "observedAtMillis");
        require(observed > 0 && observed <= System.currentTimeMillis() + 5000
                && text(evidence, "desiredSnapshotSha256").matches("[a-f0-9]{64}"), "Stored runtime observation identity is invalid");
        JsonObject projected = object(evidence, "nativeGeneration"), nativeStatus = projected.deepCopy();
        nativeStatus.addProperty("success", true);nativeStatus.addProperty("generationSupported", true);
        require(projected.equals(generation(nativeStatus, instance, operation, revision)), "Stored native generation differs");
        JsonObject collectors = object(evidence, "collectors");
        require(collectors.keySet().equals(COLLECTORS), "Stored runtime collector set differs");
        for (String command : COLLECTORS) {
            byte[] bytes = entries.get("runtime/" + command + ".json");
            require(bytes != null, "Stored runtime collector is absent");
            JsonObject value = StorageConfigArchive.json(bytes).getAsJsonObject(), collector = object(collectors, command);
            require(flag(value, "success") && "ok".equalsIgnoreCase(text(value, "status"))
                    && StorageConfigArchive.sha256(bytes).equals(text(collector, "sha256"))
                    && value.has("generatedEpoch") && value.get("generatedEpoch").equals(collector.get("generatedEpoch")), "Stored runtime collector changed");
            // Check freshness at the original collection time, never promote old evidence as fresh.
            requireEpoch(value, observed);
        }
    }

    static JsonObject stale(String command, String artifactUuid, JsonObject evidence, Map<String, byte[]> entries) {
        if (!COLLECTORS.contains(command)) return null;
        requireStored(evidence, text(evidence, "instanceUuid"), text(evidence, "operationUuid"), whole(evidence, "desiredRevision"), entries);
        JsonObject result = new JsonObject();result.addProperty("success", false);result.addProperty("status", "STALE");
        result.addProperty("source", "ACTIVE_LKG_VERIFIED_RUNTIME");result.addProperty("sourceArtifactUuid", artifactUuid);
        for (String field : new String[] {"observedAtMillis", "desiredRevision", "instanceUuid", "operationUuid", "nativeGeneration"})
            result.add(field, evidence.get(field).deepCopy());
        result.add("cachedObservation", StorageConfigSemantic.redact(StorageConfigArchive.json(entries.get("runtime/" + command + ".json"))));
        return result;
    }

    static void requireFresh(JsonObject evidence, Map<String, byte[]> entries, long now) {
        for (String command : COLLECTORS)
            requireEpoch(StorageConfigArchive.json(entries.get("runtime/" + command + ".json")).getAsJsonObject(), now);
    }

    private static void requireEpoch(JsonObject value, long now) {
        require(!value.has("cached") || value.get("cached").isJsonPrimitive()
                && value.get("cached").getAsJsonPrimitive().isBoolean() && !value.get("cached").getAsBoolean(), "A monitor cache cannot attest a fresh runtime probe");
        require(value.has("generatedEpoch") && value.get("generatedEpoch").isJsonPrimitive()
                && value.get("generatedEpoch").getAsJsonPrimitive().isNumber(), "Runtime observation timestamp is invalid");
        StorageRecoveryObservation.requireFresh(value, now / 1000.0);
    }

    private static JsonObject object(JsonObject value, String field) {
        require(value != null && value.has(field) && value.get(field).isJsonObject(), "Runtime evidence object is absent");
        return value.getAsJsonObject(field);
    }
    private static String text(JsonObject value, String field) {
        require(value != null && value.has(field) && value.get(field).isJsonPrimitive()
                && value.get(field).getAsJsonPrimitive().isString(), "Runtime evidence text is absent");
        return value.get(field).getAsString();
    }
    private static long whole(JsonObject value, String field) {
        require(value != null && value.has(field) && value.get(field).isJsonPrimitive()
                && value.get(field).getAsJsonPrimitive().isNumber() && value.get(field).getAsString().matches("[0-9]+"), "Runtime evidence integer is invalid");
        try { return Long.parseLong(value.get(field).getAsString()); }
        catch (NumberFormatException invalid) { throw new CloudRuntimeException("Runtime evidence integer is out of range"); }
    }
    private static boolean flag(JsonObject value, String field) {
        return value != null && value.has(field) && value.get(field).isJsonPrimitive()
                && value.get(field).getAsJsonPrimitive().isBoolean() && value.get(field).getAsBoolean();
    }
    private static void require(boolean valid, String message) { if (!valid) throw new CloudRuntimeException(message); }
}
