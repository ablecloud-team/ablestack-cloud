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

import java.util.List;
import com.cloud.exception.InvalidParameterValueException;

/** Failed-operation reconciliation preserves later successful desired-state revisions. */
public final class StorageOperationReconciliation {
    private StorageOperationReconciliation() { }
    public static boolean superseded(final StorageServiceOperationVO operation, final List<StorageServiceOperationVO> history) {
        if (operation == null || !("RECOVERY_REQUIRED".equals(operation.getState()) || "BLOCKED".equals(operation.getState())
                || "ROLLED_BACK".equals(operation.getState()) || "RECONCILED_SUPERSEDED".equals(operation.getState()))) {
            throw new InvalidParameterValueException("Operation does not require reconciliation");
        }
        return history.stream().anyMatch(row -> row.getInstanceId() == operation.getInstanceId() && "COMPLETE".equals(row.getState())
                && row.getRevision() >= operation.getRevision());
    }

    public static boolean supersededSummaries(final StorageServiceOperationVO operation,
            final List<StorageServiceOperationSummary> history) {
        // Preserve the same operation eligibility rules before using metadata for this existence test.
        superseded(operation,java.util.Collections.emptyList());
        return history.stream().anyMatch(row -> row.getInstanceId()==operation.getInstanceId()
                && "COMPLETE".equals(row.getState()) && row.getRevision()>=operation.getRevision());
    }

    /** Native current is authoritative only with exact scope, digest and BOOT evidence. */
    public static boolean committedTarget(String instanceUuid, StorageServiceOperationVO operation, com.google.gson.JsonObject status) {
        try {
            com.google.gson.JsonObject current = status.getAsJsonObject("generation");
            String digest = literal(status, "configurationSha256");
            String pending = nullableLiteral(status, "pendingOperationUuid");
            return bool(status,"success") && bool(status,"generationSupported")
                    && instanceUuid.equals(literal(current,"instanceUuid")) && operation.getUuid().equals(literal(current,"operationUuid"))
                    && whole(current,"revision") == operation.getRevision() && whole(status,"runtimeRevision") == operation.getRevision()
                    && digest.matches("[a-f0-9]{64}") && digest.equals(literal(current,"configurationSha256"))
                    && literal(status,"bootId").matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
                    && (pending == null || pending.equals(operation.getUuid()));
        } catch (RuntimeException malformed) { return false; }
    }

    public static void requireTarget(String instanceUuid, StorageServiceOperationVO operation, String currentSnapshot,
            com.google.gson.JsonObject currentBindings, com.google.gson.JsonObject status) {
        requireTargetEvidence(instanceUuid,operation,currentSnapshot,currentBindings,status,java.util.Set.of("RUNNING","RECOVERY_REQUIRED"));
    }

    /** Completion publication never rewrites an unresolved operation or replays its target. */
    public static void requireCompletedTarget(String instanceUuid, StorageServiceOperationVO operation, String currentSnapshot,
            com.google.gson.JsonObject currentBindings, com.google.gson.JsonObject status) {
        requireTargetEvidence(instanceUuid,operation,currentSnapshot,currentBindings,status,java.util.Set.of("COMPLETE"));
    }

    private static void requireTargetEvidence(String instanceUuid, StorageServiceOperationVO operation, String currentSnapshot,
            com.google.gson.JsonObject currentBindings, com.google.gson.JsonObject status,java.util.Set<String> states) {
        com.google.gson.JsonObject result = object(operation.getResultJson());
        if (!states.contains(operation.getState())
                || operation.getSnapshotJson() == null || !object(operation.getSnapshotJson()).equals(object(currentSnapshot))
                || !result.has("_response") || !currentBindings.equals(result.get("_targetResourceBindings"))
                || !committedTarget(instanceUuid,operation,status)
                || !literal(status,"bootId").equals(literal(result,"_targetNativeBootId"))
                || !literal(status,"configurationSha256").equals(literal(result,"_targetConfigurationSha256"))) {
            throw new com.cloud.utils.exception.CloudRuntimeException("Committed target requires its exact durable target, response, VM/DATA and native generation proof");
        }
    }

    public static void requireRestoredSource(StorageServiceOperationVO operation,String currentSnapshot,
            com.google.gson.JsonObject currentBindings,com.google.gson.JsonObject status) {
        com.google.gson.JsonObject result=object(operation.getResultJson()),previous=object(operation.getPreviousSnapshotJson()),current=object(currentSnapshot);
        com.google.gson.JsonObject desired=new com.google.gson.JsonObject();
        for(String field:java.util.List.of("schemaVersion","instanceId","tables")) if(previous.has(field))desired.add(field,previous.get(field).deepCopy());
        if (!desired.equals(current) || !currentBindings.equals(result.get("_leaseSourceResourceBindings"))
                || !readOnlyNative(status).equals(result.get("_leaseSourceNativeGeneration")))
            throw new com.cloud.utils.exception.CloudRuntimeException("Resource cleanup requires its exact restored SOURCE, VM/DATA and native generation");
    }

    public static boolean readOnly(StorageServiceOperationVO operation) {
        return operation != null && "CONFIGURATION_BACKUP_READ_ONLY".equals(operation.getAction());
    }

    public static void requireReadOnlySource(StorageServiceOperationVO operation, String currentSnapshot,
            com.google.gson.JsonObject currentBindings, com.google.gson.JsonObject status) {
        requireReadOnlySource(operation,currentSnapshot,currentBindings,status,true);
    }

    public static void requireReadOnlySource(StorageServiceOperationVO operation, String currentSnapshot,
            com.google.gson.JsonObject currentBindings, com.google.gson.JsonObject status, boolean artifactCollected) {
        com.google.gson.JsonObject result = object(operation.getResultJson());
        if (!readOnly(operation) || operation.getPreviousSnapshotJson() == null
                || !object(operation.getPreviousSnapshotJson()).equals(object(currentSnapshot))
                || (artifactCollected && (operation.getSnapshotJson()==null || !object(operation.getSnapshotJson()).equals(object(currentSnapshot)) || !result.has("_response")))
                || !currentBindings.equals(result.get("_targetResourceBindings"))
                || !readOnlyNative(status).equals(result.get("_readOnlyNativeSource"))) {
            throw new com.cloud.utils.exception.CloudRuntimeException("Read-only cleanup requires its unchanged SOURCE, artifact response, VM/DATA and native generation");
        }
    }

    public static com.google.gson.JsonObject readOnlyNative(com.google.gson.JsonObject status) {
        if (!bool(status,"success") || !bool(status,"generationSupported")
                || !"IN_SYNC".equals(literal(status,"generationStatus")) || nullableLiteral(status,"pendingOperationUuid") != null
                || !literal(status,"configurationSha256").matches("[a-f0-9]{64}")
                || !literal(status,"bootId").matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) {
            throw new com.cloud.utils.exception.CloudRuntimeException("Read-only native SOURCE is unavailable");
        }
        com.google.gson.JsonObject proof = new com.google.gson.JsonObject();
        for (String field : java.util.List.of("generation","configurationSha256","bootId","runtimeRevision")) proof.add(field,status.get(field).deepCopy());
        com.google.gson.JsonObject generation = proof.getAsJsonObject("generation");
        if (!literal(generation,"configurationSha256").equals(literal(status,"configurationSha256"))
                || whole(generation,"revision") != whole(status,"runtimeRevision")) throw new IllegalArgumentException("Read-only native SOURCE differs");
        return proof;
    }

    private static com.google.gson.JsonObject object(String value) {
        com.google.gson.JsonElement parsed = new com.google.gson.JsonParser().parse(value);
        if (!parsed.isJsonObject()) throw new IllegalArgumentException("Durable operation object is unavailable");
        return parsed.getAsJsonObject();
    }
    private static String literal(com.google.gson.JsonObject value,String key) {
        com.google.gson.JsonElement item=value.get(key);
        if (item==null || !item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Operation proof string is unavailable");
        return item.getAsString();
    }
    private static String nullableLiteral(com.google.gson.JsonObject value,String key) {
        if (!value.has(key)) throw new IllegalArgumentException("Operation proof field is unavailable");
        return value.get(key).isJsonNull()?null:literal(value,key);
    }
    private static boolean bool(com.google.gson.JsonObject value,String key) {
        com.google.gson.JsonElement item=value.get(key);
        return item!=null && item.isJsonPrimitive() && item.getAsJsonPrimitive().isBoolean() && item.getAsBoolean();
    }
    private static long whole(com.google.gson.JsonObject value,String key) {
        com.google.gson.JsonElement item=value.get(key);
        if (item==null || !item.isJsonPrimitive() || !item.getAsJsonPrimitive().isNumber() || !item.getAsString().matches("[0-9]+")) throw new IllegalArgumentException("Operation proof revision is unavailable");
        return Long.parseLong(item.getAsString());
    }
}
