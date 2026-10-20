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
    static final String SERVICE_IMPORT_RESULT_KEY = "_preImportServiceSourceRecovery";
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

    /** Separate admission: the original create/NFS and initial-clone boundaries stay unchanged. */
    static boolean eligiblePreImportService(StorageServiceOperationVO operation) {
        try {
            if (operation==null || !"SERVICE_MAINTENANCE".equals(operation.getAction())
                    || !"RECOVERY_REQUIRED".equals(operation.getState()) || !"RECOVERY_REQUIRED".equals(operation.getPhase())
                    || operation.getRevision()<=2 || operation.getProgress()!=0 || operation.getSnapshotJson()!=null
                    || operation.getRequestKey()==null || !operation.getRequestKey().startsWith("SERVICE_MAINTENANCE:"))return false;
            JsonObject previous=object(operation.getPreviousSnapshotJson()),result=object(operation.getResultJson());
            return previous.keySet().equals(SCHEMA)
                    && result.keySet().stream().allMatch(k->Set.of("_requestFingerprint",SERVICE_IMPORT_RESULT_KEY,"_renderedFailureDiagnostic").contains(k))
                    && result.has("_requestFingerprint");
        } catch (RuntimeException invalid) {return false;}
    }

    static JsonObject require(String instanceUuid, StorageServiceOperationVO original, StorageServiceOperationVO source,
            String currentSnapshot, JsonObject bindings, JsonObject status, JsonObject frozen) {
        return require(instanceUuid,original,source,currentSnapshot,bindings,status,frozen,false);
    }

    static JsonObject requirePreImportService(String instanceUuid, StorageServiceOperationVO original, StorageServiceOperationVO source,
            String currentSnapshot, JsonObject bindings, JsonObject status, JsonObject frozen) {
        return require(instanceUuid,original,source,currentSnapshot,bindings,status,frozen,true);
    }

    private static JsonObject require(String instanceUuid, StorageServiceOperationVO original, StorageServiceOperationVO source,
            String currentSnapshot, JsonObject bindings, JsonObject status, JsonObject frozen,boolean serviceImport) {
        try {
            need((serviceImport?eligiblePreImportService(original):eligible(original)) && source != null && uuid(instanceUuid)
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
            if(serviceImport) {
                need(source.getRevision()==original.getRevision()-1);
                need(result.keySet().stream().allMatch(k->Set.of("_requestFingerprint",SERVICE_IMPORT_RESULT_KEY,"_renderedFailureDiagnostic").contains(k)));
                if(result.has("_renderedFailureDiagnostic"))requireImportDiagnostic(instanceUuid,original,result.getAsJsonObject("_renderedFailureDiagnostic"));
            } else need(result.keySet().stream().allMatch(k -> "_requestFingerprint".equals(k) || RESULT_KEY.equals(k)));
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
            JsonObject proof = new JsonObject();proof.addProperty("kind",serviceImport?"UNCHANGED_VERIFIED_PREIMPORT_SERVICE_SOURCE":"UNCHANGED_VERIFIED_SOURCE");
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

    /** The optional receipt is descriptive only; none of its text establishes absence of guest effects. */
    private static void requireImportDiagnostic(String instance,StorageServiceOperationVO operation,JsonObject receipt) {
        need(receipt.keySet().equals(Set.of("schemaVersion","scope","action","diagnostic"))
                && whole(receipt,"schemaVersion")==1 && "import".equals(text(receipt,"action")));
        JsonObject scope=receipt.getAsJsonObject("scope");
        need(scope!=null&&scope.keySet().equals(Set.of("instanceUuid","operationUuid","revision"))
                && instance.equals(text(scope,"instanceUuid"))&&operation.getUuid().equals(text(scope,"operationUuid"))
                && operation.getRevision()==whole(scope,"revision"));
        String fixed=text(receipt,"diagnostic");
        need(fixed.length()<=256);
        java.util.Map<String,String> phases=java.util.Map.ofEntries(
            java.util.Map.entry("NATIVE_CHILD_TIMEOUT","TRANSPORT"),java.util.Map.entry("NATIVE_CHILD_OUTPUT_INVALID","TRANSPORT"),
            java.util.Map.entry("NATIVE_CHILD_FAILED","TRANSPORT"),java.util.Map.entry("NATIVE_CHILD_INPUT_UNAVAILABLE","TRANSPORT"),
            java.util.Map.entry("RUNTIME_PREPARE_FAILED","PREPARE"),java.util.Map.entry("RUNTIME_APPLY_FAILED","APPLY"),
            java.util.Map.entry("RUNTIME_VERIFY_FAILED","VERIFY"),java.util.Map.entry("RUNTIME_ROLLBACK_FAILED","ROLLBACK"),
            java.util.Map.entry("ENDPOINT_LISTENER_ADDRESS_MISMATCH","VERIFY"),java.util.Map.entry("NFS_ALIAS_UNMOUNT_FAILED","CLEANUP"),
            java.util.Map.entry("NFS_ALIAS_UNMOUNT_UNVERIFIED","CLEANUP"),
            java.util.Map.entry("IMPORT_INPUT_INVALID","IMPORT"),java.util.Map.entry("IMPORT_CREDENTIAL_BINDING_FAILED","IMPORT"),
            java.util.Map.entry("IMPORT_RENDER_FAILED","IMPORT"),java.util.Map.entry("IMPORT_SOURCE_SCOPE_MISMATCH","IMPORT"),
            java.util.Map.entry("IMPORT_CANDIDATE_VALIDATION_FAILED","IMPORT"),java.util.Map.entry("IMPORT_SOURCE_VERIFY_FAILED","IMPORT"),
            java.util.Map.entry("IMPORT_PUBLICATION_FAILED","IMPORT"));
        String[] parts=fixed.split("; ",-1);boolean runtime=false;
        if(parts.length==4&&"runtimeSchemaVersion=1".equals(parts[0])&&parts[1].startsWith("protocol=")
                &&parts[2].startsWith("phase=")&&parts[3].startsWith("code=")) {
            String protocol=parts[1].substring(9),phase=parts[2].substring(6),code=parts[3].substring(5);
            runtime=Set.of("NFS","SMB","ISCSI","NVMEOF","GENERATION").contains(protocol)
                    &&java.util.Objects.equals(phases.get(code),phase);
            if((code.startsWith("NFS_")||"ENDPOINT_LISTENER_ADDRESS_MISMATCH".equals(code))&&!"NFS".equals(protocol))runtime=false;
            if(("RUNTIME_PREPARE_FAILED".equals(code)||"NATIVE_CHILD_INPUT_UNAVAILABLE".equals(code)
                    ||code.startsWith("IMPORT_")&&!"IMPORT_RENDER_FAILED".equals(code))&&!"GENERATION".equals(protocol))runtime=false;
        }
        boolean nfsValidator=java.util.Map.ofEntries(
            java.util.Map.entry("NFS_VALIDATOR_RUNTIME_UNAVAILABLE","RUNTIME_SELECTION"),java.util.Map.entry("NFS_VALIDATOR_RUNTIME_UNTRUSTED","RUNTIME_SELECTION"),
            java.util.Map.entry("NFS_VALIDATOR_NAMESPACE_FAILED","NAMESPACE_SETUP"),java.util.Map.entry("NFS_VALIDATOR_CHROOT_FAILED","CHROOT_SETUP"),
            java.util.Map.entry("NFS_VALIDATOR_PRIVATE_SERVICES_FAILED","PRIVATE_SERVICES"),java.util.Map.entry("NFS_VALIDATOR_PARSER_REJECTED","PARSER_STARTUP"),
            java.util.Map.entry("NFS_VALIDATOR_PARSER_TIMEOUT","PARSER_STARTUP"),java.util.Map.entry("NFS_VALIDATOR_WORKER_FAILED","WORKER_TRANSPORT"),
            java.util.Map.entry("NFS_VALIDATOR_WORKER_TIMEOUT","WORKER_TRANSPORT")).entrySet().stream()
            .anyMatch(pair->fixed.equals("protocol=NFS; stage="+pair.getValue()+"; runtimeValidationCode="+pair.getKey()));
        need(runtime||nfsValidator);
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
