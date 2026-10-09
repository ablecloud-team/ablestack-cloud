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
import java.util.Set;
import java.util.UUID;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** LOCAL stopped-source authority never substitutes for ROOT, SERVICE or CURRENT identity. */
public final class StorageLocalSourceIdentityProof {
    static final String KIND = "LOCAL_SOURCE_IDENTITY_CHECKPOINT";
    private static final Set<String> SCOPE = Set.of("instanceUuid", "operationUuid", "revision", "localCheckpointUuid");
    private StorageLocalSourceIdentityProof() { }

    static JsonObject source(JsonObject before) {
        require(literal(before, "success", true) && literal(before, "generationSupported", true)
                && ("IN_SYNC".equals(string(before, "generationStatus"))
                    || object(before, "generation").size() == 0 && "UNVERIFIED".equals(string(before, "generationStatus")))
                && (!before.has("pendingOperationUuid") || before.get("pendingOperationUuid").isJsonNull()), "LOCAL source has a pending or unverified native generation");
        JsonObject source = object(before, "configurationDesiredState");
        require(source.keySet().equals(StorageRenderedDesiredState.PATHS), "LOCAL source lacks the exact seven canonical files");
        return source;
    }

    static JsonObject context(JsonObject writer, JsonObject before) {
        require(writer != null && writer.keySet().equals(Set.of("instanceUuid", "operationUuid", "revision")), "LOCAL checkpoint has no exact writer scope");
        uuid(writer, "instanceUuid");uuid(writer, "operationUuid");require(number(writer, "revision") > 0, "LOCAL writer revision is invalid");source(before);
        JsonObject generation = object(before, "generation");String checksum = hash(before, "configurationSha256");
        if (generation.size() != 0) {
            uuid(generation, "instanceUuid");uuid(generation, "operationUuid");
            require(writer.get("instanceUuid").equals(generation.get("instanceUuid")) && number(generation, "revision") >= 0
                    && number(generation, "revision") < number(writer, "revision")
                    && checksum.equals(hash(generation, "configurationSha256")), "LOCAL checkpoint belongs to another source generation");
        }
        JsonObject context = writer.deepCopy();
        context.addProperty("localCheckpointUuid", UUID.nameUUIDFromBytes(("local-source-checkpoint:" + string(writer, "operationUuid")).getBytes(StandardCharsets.UTF_8)).toString());
        context.add("sourceGeneration", generation.deepCopy());context.addProperty("sourceConfigurationSha256", checksum);
        context.addProperty("expectedBootId", uuid(before, "bootId"));return context;
    }

    static void requireContext(JsonObject writer, JsonObject context, JsonObject before) {
        require(context(writer, before).equals(context), "LOCAL checkpoint source, boot, purpose or scope changed");
    }

    static void sameSourceObservation(JsonObject context, JsonObject source, JsonObject observed) {
        require(literal(observed, "success", true) && literal(observed, "generationSupported", true)
                && context.get("sourceGeneration").equals(observed.get("generation"))
                && context.get("sourceConfigurationSha256").equals(observed.get("configurationSha256"))
                && context.get("expectedBootId").equals(observed.get("bootId"))
                && source.get("configurationDesiredState").equals(observed.get("configurationDesiredState")),
                "LOCAL retry changed its immutable source generation, configuration or boot");
        if (observed.has("pendingOperationUuid") && !observed.get("pendingOperationUuid").isJsonNull())
            require(context.get("operationUuid").equals(observed.get("pendingOperationUuid")), "LOCAL retry found another pending writer");
    }

    static JsonObject scope(JsonObject context) {
        JsonObject result = new JsonObject();for (String key : SCOPE) result.add(key, context.get(key).deepCopy());return result;
    }

    static void base(JsonObject context, JsonObject response) {
        require(literal(response, "success", true) && scope(context).equals(response.get("scope")), "LOCAL checkpoint response has a foreign scope or nonliteral success");
    }

    static JsonObject exported(JsonObject context, JsonObject response) {
        base(context, response);runtime(response);
        require(literal(response, "sourceIdentityCheckpointCaptured", true), "LOCAL checkpoint was not captured while stopped");
        JsonObject receipt = object(response, "localSourceIdentityCheckpoint");checkpoint(context, object(response, "capsule"), receipt);return receipt.deepCopy();
    }

    static void checkpoint(JsonObject context, JsonObject capsule, JsonObject receipt) {
        require(receipt != null && receipt.keySet().equals(Set.of("kind", "scope", "capsuleSha256", "sourceConfigurationSha256", "checkpointRecordSha256"))
                && KIND.equals(string(receipt, "kind")) && scope(context).equals(receipt.get("scope"))
                && context.get("sourceConfigurationSha256").equals(receipt.get("sourceConfigurationSha256")), "LOCAL checkpoint receipt is foreign or relabeled");
        hash(receipt, "checkpointRecordSha256");String digest = hash(receipt, "capsuleSha256");
        require(capsule.keySet().equals(Set.of("schemaVersion", "scope", "wrappedKey", "nonce", "ciphertext", "sha256"))
                && number(capsule, "schemaVersion") == 1
                && (string(context, "instanceUuid") + ":" + string(context, "operationUuid")).equals(string(capsule, "scope")), "LOCAL encrypted envelope shape or AAD differs");
        byte[] cipher, nonce, wrapped;
        try {
            cipher = java.util.Base64.getDecoder().decode(string(capsule, "ciphertext"));nonce = java.util.Base64.getDecoder().decode(string(capsule, "nonce"));
            wrapped = java.util.Base64.getDecoder().decode(string(capsule, "wrappedKey"));
        } catch (IllegalArgumentException invalid) {throw new CloudRuntimeException("LOCAL cipher encoding is invalid", invalid);}
        require(cipher.length >= 16 && cipher.length <= 16 * 1024 * 1024 && nonce.length == 12 && wrapped.length >= 256 && wrapped.length <= 1024
                && digest.equals(hash(capsule, "sha256")) && digest.equals(StorageConfigArchive.sha256(cipher)), "LOCAL encrypted digest or cryptographic shape changed");
    }

    static JsonObject reference(JsonObject checkpoint) {
        require(checkpoint != null && checkpoint.keySet().equals(Set.of("kind", "scope", "capsuleSha256", "sourceConfigurationSha256", "checkpointRecordSha256"))
                && KIND.equals(string(checkpoint, "kind")), "LOCAL rendered reference cannot use another checkpoint purpose");
        JsonObject scope = object(checkpoint, "scope");require(scope.keySet().equals(SCOPE), "LOCAL rendered reference has an unknown scope");
        uuid(scope, "instanceUuid");uuid(scope, "operationUuid");uuid(scope, "localCheckpointUuid");require(number(scope, "revision") > 0, "LOCAL rendered checkpoint revision is invalid");
        JsonObject result = new JsonObject();result.add("localCheckpointUuid", scope.get("localCheckpointUuid").deepCopy());
        result.addProperty("sha256", hash(checkpoint, "checkpointRecordSha256"));return result;
    }

    static void status(JsonObject context, JsonObject response) {
        base(context, response);require(literal(response, "checkpointSupported", true)
                && context.get("sourceGeneration").equals(response.get("sourceGeneration"))
                && context.get("sourceConfigurationSha256").equals(response.get("sourceConfigurationSha256"))
                && string(context, "expectedBootId").equals(uuid(response, "bootId")), "LOCAL checkpoint status changed its source or boot");
    }

    static void resumed(JsonObject context, JsonObject response) {
        status(context, response);
        require(literal(response, "journalPresent", true) && "RESUMED".equals(string(response, "phase")), "LOCAL source STOP is unresolved; terminal rollback is forbidden");
        runtime(response);
    }

    static void restoreSafe(JsonObject context, JsonObject reference, JsonObject response) {
        status(context, response);require(literal(response, "journalPresent", true) && literal(response, "sourceIdentityRestoreSupported", true)
                && literal(response, "currentRuntimeOwnershipVerified", true) && literal(response, "currentSessionsVerifiedEmpty", true)
                && reference.equals(response.get("localSourceCheckpointReference")), "LOCAL checkpoint cannot safely restore its owned SMB source");
        hash(response, "currentConfigurationSha256");hash(response, "currentSmbConfigurationSha256");
    }

    static void imported(JsonObject context, JsonObject response) {
        base(context, response);runtime(response);
        require(literal(response, "sourceIdentityRestored", true), "LOCAL original identity was not restored under its stopped-source authority");
    }

    static void authReplayed(JsonObject context, JsonObject reference, com.google.gson.JsonArray domains, JsonObject response) {
        base(context, response);
        require(literal(response, "sourceAuthReplayed", true) && literal(response, "canonicalDesiredStateChanged", false)
                && literal(response, "smbIdentityChanged", false) && domains.equals(response.get("replayedDomains"))
                && context.get("sourceConfigurationSha256").equals(response.get("sourceConfigurationSha256"))
                && reference.equals(response.get("localSourceCheckpointReference")), "LOCAL original authentication replay changed its source, domain or purpose");
        if (domains.contains(new com.google.gson.JsonPrimitive("NVMEOF")))
            require(literal(response, "nvmeRestored", true), "LOCAL original NVMe authentication was not restored");
    }

    private static void runtime(JsonObject response) {
        require(literal(response, "sourceSmbResumed", true) && literal(response, "sourceRuntimeVerified", true)
                && literal(response, "canonicalDesiredStateChanged", false), "LOCAL checkpoint has no verified owned source resume");
    }
    private static boolean literal(JsonObject value, String field, boolean expected) {
        JsonElement element = value == null ? null : value.get(field);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean() && element.getAsBoolean() == expected;
    }
    private static JsonObject object(JsonObject value, String field) {
        require(value != null && value.has(field) && value.get(field).isJsonObject(), "LOCAL checkpoint object is unavailable: " + field);return value.getAsJsonObject(field);
    }
    private static String string(JsonObject value, String field) {
        JsonElement element = value == null ? null : value.get(field);
        require(element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString(), "LOCAL checkpoint string is unavailable: " + field);return element.getAsString();
    }
    private static String hash(JsonObject value, String field) {
        String text = string(value, field);require(text.matches("[a-f0-9]{64}"), "LOCAL checkpoint hash is invalid");return text;
    }
    private static String uuid(JsonObject value, String field) {
        String text = string(value, field);require(text.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"), "LOCAL checkpoint UUID is invalid");return text;
    }
    private static long number(JsonObject value, String field) {
        JsonElement element = value == null ? null : value.get(field);
        require(element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber(), "LOCAL checkpoint number is unavailable: " + field);
        try {return element.getAsBigDecimal().longValueExact();} catch (ArithmeticException invalid) {throw new CloudRuntimeException("LOCAL checkpoint number is not integral", invalid);}
    }
    private static void require(boolean value, String message) {if (!value) throw new CloudRuntimeException(message);}
}
