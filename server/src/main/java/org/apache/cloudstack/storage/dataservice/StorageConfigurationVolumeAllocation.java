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

import java.util.Set;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.cloud.utils.exception.CloudRuntimeException;

/** Durable allocation orchestration. Physical create, attach and guest preparation stay outside DB transactions. */
public final class StorageConfigurationVolumeAllocation {
    private static final Set<String> RECEIPT_STATES = Set.of("INTENT", "ALLOCATED", "READY", "ATTACHED", "FILE_PREPARING", "PREPARED", "CLEANUP_PENDING", "DELETED");
    private StorageConfigurationVolumeAllocation() { }

    /** Adapter must throw this for a failed receipt CAS; a concurrent winner is never overwritten as recovery. */
    public static final class ReceiptConflictException extends CloudRuntimeException {
        public ReceiptConflictException(String message) {super(message);}
    }

    public interface Runtime {
        /** Revalidate caller/tenant, offering, pool, quota and the frozen resource fingerprints before effects. */
        void validateAllocation(JsonObject allocation);
        /** Fresh persistent receipt lookup; no process-local state is authoritative. */
        JsonObject loadReceipt(String plannedUuid);
        /** Atomic compare-and-swap. A null expected value means insert only if absent. */
        void saveReceipt(JsonObject expected, JsonObject replacement);
        /** Fresh lookup by exact UUID. Null means absent, never select by display name. */
        JsonObject findVolume(String plannedUuid);
        /**
         * One DB transaction: recheck INTENT CAS; standard allocVolume with customId=plannedUuid;
         * write exact provenance volume details and ALLOCATED receipt; commit together. No physical work.
         * On duplicate UUID, re-read and accept only identical provenance. An exception may follow a committed transaction.
         */
        void allocateAndRecord(JsonObject allocation, JsonObject expectedIntent, JsonObject allocatedReceipt);
        /** Complete or reconcile physical creation in the exact planned pool; safe for an existing staged UUID. */
        void createPhysical(JsonObject allocation);
        /** Attach or reconcile only this volume to this target VM; an existing correct attachment is reused. */
        void attach(JsonObject allocation);
        /** Fresh exact volume/device observation plus durable native preparation journal, without writes. */
        JsonObject inspect(JsonObject allocation);
        /** Only FILE enters this adapter. FORMAT_IF_EMPTY requires NEW+recorded intent; native never auto-reformats. */
        JsonObject prepareFile(JsonObject allocation, String importMode);
        /** Fresh reference/session/mount/writer checks under the same scope before an explicit unpublished cleanup. */
        JsonObject cleanupSafety(JsonObject allocation);
        /** Delete only the verified unpublished NEW UUID. Adapter preserves exact identity through detach/delete. */
        void deleteUnpublished(JsonObject allocation);
    }

    public static JsonObject prepare(JsonObject frozenPlan, Runtime runtime) {
        StorageConfigurationVolumePlan.requireFrozen(frozenPlan);
        // Reject any foreign/unavailable member before the first allocation effect; recheck each again below.
        for (JsonElement value : frozenPlan.getAsJsonArray("allocations")) runtime.validateAllocation(executionAllocation(frozenPlan, value.getAsJsonObject()));
        JsonObject result = new JsonObject();JsonArray receipts = new JsonArray();
        for (JsonElement value : frozenPlan.getAsJsonArray("allocations")) {
            JsonObject allocation = executionAllocation(frozenPlan, value.getAsJsonObject());
            JsonObject receipt = runtime.loadReceipt(text(allocation, "plannedUuid"));
            if (receipt == null) {
                receipt = intent(allocation);runtime.validateAllocation(allocation.deepCopy());runtime.saveReceipt(null, receipt.deepCopy());
            }
            requireReceipt(allocation, receipt);
            try {
                receipt = prepareOne(allocation, receipt, runtime);receipts.add(receipt.deepCopy());
            } catch (RuntimeException failure) {
                // A transaction may have committed before its response was lost. Keep that receipt, never erase it.
                JsonObject latest = runtime.loadReceipt(text(allocation, "plannedUuid"));
                if (latest != null && !(failure instanceof ReceiptConflictException)) {
                    requireReceipt(allocation, latest);JsonObject next = latest.deepCopy();next.addProperty("version", nextVersion(latest));
                    next.addProperty("state", "RECOVERY_REQUIRED");next.addProperty("dataPolicy", "PRESERVE");
                    next.addProperty("diagnosticCode", "VOLUME_PREPARATION_REQUIRES_RECONCILE");
                    runtime.saveReceipt(latest, next);
                }
                throw failure;
            }
        }
        result.add("receipts", receipts);result.add("volumeMappings", frozenPlan.getAsJsonObject("volumeMappings").deepCopy());result.addProperty("status", "PREPARED");return result;
    }

    private static JsonObject prepareOne(JsonObject allocation, JsonObject receipt, Runtime runtime) {
        runtime.validateAllocation(allocation.deepCopy());
        String phase = text(receipt, "phase");require(!Set.of("DELETED", "CLEANUP_PENDING").contains(phase), "Explicit cleanup blocks preparation");
        JsonObject volume = runtime.findVolume(text(allocation, "plannedUuid"));
        if (volume == null) {
            require("NEW".equals(text(allocation, "mode")) && "INTENT".equals(phase), "A staged backing volume disappeared; automatic reallocation is forbidden");
            JsonObject allocated = advance(receipt, "ALLOCATED");
            runtime.allocateAndRecord(allocation.deepCopy(), receipt.deepCopy(), allocated.deepCopy());
            receipt = runtime.loadReceipt(text(allocation, "plannedUuid"));require(receipt != null, "Allocation transaction did not persist its receipt");requireReceipt(allocation, receipt);
            volume = runtime.findVolume(text(allocation, "plannedUuid"));require(volume != null, "Allocation transaction did not persist its volume");
        }
        requireVolume(allocation, volume, false);
        if ("INTENT".equals(text(receipt, "phase"))) receipt = save(runtime, receipt, advance(receipt, "ALLOCATED"));
        if (!"Ready".equals(text(volume, "state"))) {
            require("NEW".equals(text(allocation, "mode")) && Set.of("Allocated", "Creating").contains(text(volume, "state")), "Backing volume is not creatable");
            runtime.createPhysical(allocation.deepCopy());volume = runtime.findVolume(text(allocation, "plannedUuid"));requireVolume(allocation, volume, true);
        } else requireVolume(allocation, volume, true);
        if (Set.of("INTENT", "ALLOCATED").contains(text(receipt, "phase"))) receipt = save(runtime, receipt, advance(receipt, "READY"));
        String attached = nullable(volume, "attachedInstanceUuid");
        require(attached == null || attached.equals(text(allocation, "targetInstanceUuid")), "Backing is attached to another service");
        if (attached == null) {
            runtime.attach(allocation.deepCopy());volume = runtime.findVolume(text(allocation, "plannedUuid"));requireVolume(allocation, volume, true);
        }
        require(text(allocation, "targetInstanceUuid").equals(nullable(volume, "attachedInstanceUuid")), "Exact target attachment could not be verified");
        if (Set.of("ALLOCATED", "READY").contains(text(receipt, "phase"))) receipt = save(runtime, receipt, advance(receipt, "ATTACHED"));
        JsonObject observation = runtime.inspect(allocation.deepCopy());requireObservation(allocation, observation);
        if ("BLOCK_RAW".equals(text(allocation, "usage"))) {
            require(!StorageConfigurationVolumePlan.flag(receipt, "formatStarted"), "Raw backing receipt unexpectedly records formatting");
            JsonObject ready = advance(receipt, "PREPARED");ready.addProperty("rawIdentityVerified", true);return save(runtime, receipt, ready);
        }
        require("FILE".equals(text(allocation, "usage")), "Unknown backing preparation usage");
        String actualFs = nullable(observation, "filesystemUuid");String expectedFs = nullable(receipt, "filesystemUuid");
        require(!observation.has("formatterActive") || !StorageConfigurationVolumePlan.flag(observation, "formatterActive"), "A live formatter must be observed until completion; concurrent preparation is forbidden");
        boolean journalStarted = observation.has("preparationStarted") && StorageConfigurationVolumePlan.flag(observation, "preparationStarted");
        boolean started = StorageConfigurationVolumePlan.flag(receipt, "formatStarted") || journalStarted;
        if (journalStarted && !StorageConfigurationVolumePlan.flag(receipt, "formatStarted")) {
            JsonObject recorded = receipt.deepCopy();recorded.addProperty("version", nextVersion(receipt));recorded.addProperty("formatStarted", true);receipt = save(runtime, receipt, recorded);
        }
        String mode;
        if (actualFs != null) {
            require(text(allocation, "filesystem").equalsIgnoreCase(text(observation, "filesystem")), "Observed filesystem type conflicts with the reviewed plan");
            if (expectedFs != null) require(expectedFs.equals(actualFs), "Stored backing filesystem UUID changed");
            else if ("NEW".equals(text(allocation, "mode"))) require(started && completedNativePreparation(allocation, observation), "Unrecorded filesystem cannot be adopted as a NEW format result");
            if (expectedFs == null) {
                JsonObject pinned = receipt.deepCopy();pinned.addProperty("version", nextVersion(receipt));pinned.addProperty("filesystemUuid", actualFs);receipt = save(runtime, receipt, pinned);expectedFs = actualFs;
            }
            mode = "MOUNT_EXISTING";
        } else {
            require("NEW".equals(text(allocation, "mode")) && !started && StorageConfigurationVolumePlan.flag(observation, "blank"), "Blank or partial DATA after a format intent requires manual recovery; no automatic reformat");
            JsonObject preparing = advance(receipt, "FILE_PREPARING");preparing.addProperty("formatStarted", true);receipt = save(runtime, receipt, preparing);mode = "FORMAT_IF_EMPTY";
        }
        JsonObject prepared = runtime.prepareFile(allocation.deepCopy(), mode);requireObservation(allocation, prepared);
        String filesystemUuid = text(prepared, "filesystemUuid");require(text(allocation, "filesystem").equalsIgnoreCase(text(prepared, "filesystem")), "Prepared filesystem type differs from plan");
        require(actualFs == null || actualFs.equals(filesystemUuid), "Filesystem changed while mounting existing DATA");
        if (expectedFs != null) require(expectedFs.equals(filesystemUuid), "Prepared filesystem UUID differs from frozen receipt");
        JsonObject verified = runtime.inspect(allocation.deepCopy());requireObservation(allocation, verified);
        require(filesystemUuid.equals(text(verified, "filesystemUuid")) && text(allocation, "filesystem").equalsIgnoreCase(text(verified, "filesystem")), "Prepared filesystem did not pass fresh identity verification");
        JsonObject next = advance(receipt, "PREPARED");next.addProperty("filesystemUuid", filesystemUuid);return save(runtime, receipt, next);
    }

    /** Explicit opt-in only. Default failures preserve DATA. Formatted/published/referenced DATA cannot be auto-deleted. */
    public static JsonObject cleanupUnpublished(JsonObject frozenPlan, String sourceUuid, boolean explicitlyRequested, Runtime runtime) {
        StorageConfigurationVolumePlan.requireFrozen(frozenPlan);require(explicitlyRequested, "Default clone failure policy is PRESERVE");
        JsonObject allocation = null;
        for (JsonElement value : frozenPlan.getAsJsonArray("allocations")) if (sourceUuid.equals(text(value.getAsJsonObject(), "sourceUuid"))) allocation = executionAllocation(frozenPlan, value.getAsJsonObject());
        require(allocation != null && "NEW".equals(text(allocation, "mode")), "Only a proven NEW staged DATA may be cleaned up");
        JsonObject receipt = runtime.loadReceipt(text(allocation, "plannedUuid"));require(receipt != null, "Cleanup requires persistent allocation provenance");requireReceipt(allocation, receipt);
        require(!StorageConfigurationVolumePlan.flag(receipt, "formatStarted") && nullable(receipt, "filesystemUuid") == null && !"PREPARED".equals(text(receipt, "phase")), "Prepared or formatted DATA must be preserved");
        if ("DELETED".equals(text(receipt, "phase"))) return receipt;
        runtime.validateAllocation(allocation.deepCopy());JsonObject volume = runtime.findVolume(text(allocation, "plannedUuid"));
        if (volume == null) {require("CLEANUP_PENDING".equals(text(receipt, "phase")), "Missing DATA cannot be assumed to have been cleaned up");return save(runtime, receipt, advance(receipt, "DELETED"));}
        requireVolume(allocation, volume, "Ready".equals(text(volume, "state")));
        JsonObject safety = runtime.cleanupSafety(allocation.deepCopy());
        for (String field : Set.of("referenced", "published", "mounted", "formatterActive", "activeSessions", "writerActive", "usedByAnotherVm")) require(!StorageConfigurationVolumePlan.flag(safety, field), "Cleanup is blocked by live DATA usage");
        JsonObject pending = advance(receipt, "CLEANUP_PENDING");receipt = save(runtime, receipt, pending);
        runtime.deleteUnpublished(allocation.deepCopy());require(runtime.findVolume(text(allocation, "plannedUuid")) == null, "Exact staged DATA deletion was not verified");
        return save(runtime, receipt, advance(receipt, "DELETED"));
    }

    private static JsonObject executionAllocation(JsonObject plan, JsonObject entry) {JsonObject value = entry.deepCopy();value.add("planSha256", plan.get("planSha256").deepCopy());return value;}
    private static JsonObject intent(JsonObject allocation) {JsonObject value = new JsonObject();value.add("provenance", allocation.deepCopy());value.addProperty("plannedUuid", text(allocation, "plannedUuid"));value.addProperty("version", 1);value.addProperty("phase", "INTENT");value.addProperty("state", "INTENT");value.addProperty("formatStarted", false);value.addProperty("dataPolicy", "PRESERVE");return value;}
    private static long nextVersion(JsonObject receipt) {try {return Math.addExact(StorageConfigurationVolumePlan.positive(receipt, "version"), 1);} catch (ArithmeticException overflow) {throw new CloudRuntimeException("Allocation receipt revision overflow");}}
    private static JsonObject advance(JsonObject receipt, String phase) {JsonObject value = receipt.deepCopy();value.addProperty("version", nextVersion(receipt));value.addProperty("phase", phase);value.addProperty("state", phase);value.remove("diagnosticCode");return value;}
    private static JsonObject save(Runtime runtime, JsonObject expected, JsonObject next) {runtime.saveReceipt(expected.deepCopy(), next.deepCopy());return next;}
    private static void requireReceipt(JsonObject allocation, JsonObject receipt) {require(text(allocation, "plannedUuid").equals(text(receipt, "plannedUuid")) && allocation.equals(StorageConfigurationVolumePlan.object(receipt, "provenance")), "Allocation receipt scope or provenance changed");StorageConfigurationVolumePlan.positive(receipt, "version");require(RECEIPT_STATES.contains(text(receipt, "phase")), "Unknown allocation receipt phase");require("PRESERVE".equals(text(receipt, "dataPolicy")), "Receipt data policy cannot authorize automatic deletion");}
    private static void requireVolume(JsonObject allocation, JsonObject volume, boolean ready) {
        require(volume != null && text(allocation, "plannedUuid").equals(text(volume, "uuid")) && "DATADISK".equals(text(volume, "type")), "Exact staged DATA identity is unavailable");
        StorageConfigurationVolumePlan.requireSameScope(allocation, volume);require(StorageConfigurationVolumePlan.number(allocation, "sizeBytes") == StorageConfigurationVolumePlan.number(volume, "sizeBytes"), "Staged DATA size changed");
        String pool = nullable(volume, "poolUuid");require(pool == null && !ready || text(allocation, "poolUuid").equals(pool), "Staged DATA moved outside the reviewed pool");
        if (ready) require("Ready".equals(text(volume, "state")), "Staged DATA is not Ready");
        if ("NEW".equals(text(allocation, "mode"))) require(allocation.equals(StorageConfigurationVolumePlan.object(volume, "provenance")), "UUID collision or foreign DATA provenance prohibits adoption");
        String target = nullable(volume, "attachedInstanceUuid");require(target == null || text(allocation, "targetInstanceUuid").equals(target), "Staged DATA belongs to a different service");
    }
    private static void requireObservation(JsonObject allocation, JsonObject observed) {require(text(allocation, "plannedUuid").equals(text(observed, "volumeUuid")) && "EXACT".equals(text(observed, "mappingStatus")) && StorageConfigurationVolumePlan.number(allocation, "sizeBytes") == StorageConfigurationVolumePlan.number(observed, "sizeBytes"), "Fresh exact backing device identity is unavailable");}
    private static boolean completedNativePreparation(JsonObject allocation, JsonObject observation) {return text(allocation, "plannedUuid").equals(nullable(observation, "preparationVolumeUuid")) && observation.has("preparationComplete") && StorageConfigurationVolumePlan.flag(observation, "preparationComplete");}
    private static String nullable(JsonObject value, String key) {return value.has(key) && !value.get(key).isJsonNull() ? text(value, key) : null;}
    private static String text(JsonObject value, String key) {return StorageConfigurationVolumePlan.text(value, key);}
    private static void require(boolean condition, String message) {StorageConfigurationVolumePlan.require(condition, message);}
}
