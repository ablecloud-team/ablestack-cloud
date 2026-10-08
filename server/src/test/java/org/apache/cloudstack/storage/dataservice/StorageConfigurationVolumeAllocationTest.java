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

import java.util.HashMap;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.cloud.utils.exception.CloudRuntimeException;

public class StorageConfigurationVolumeAllocationTest {
    static class Runtime implements StorageConfigurationVolumeAllocation.Runtime {
        final Map<String, JsonObject> receipts = new HashMap<>(), volumes = new HashMap<>(), observations = new HashMap<>();
        String failOnce;int allocCalls, createCalls, attachCalls, formatCalls, mountCalls, deleteCalls;boolean denyFreshScope, unsafe, failCas;
        private void fault(String stage) {if (stage.equals(failOnce)) {failOnce = null;throw new CloudRuntimeException("Injected " + stage);}}
        private String id(JsonObject allocation) {return allocation.get("plannedUuid").getAsString();}
        public void validateAllocation(JsonObject allocation) {if (denyFreshScope) throw new CloudRuntimeException("Tenant or resource access changed");fault("validate");}
        public JsonObject loadReceipt(String uuid) {JsonObject value = receipts.get(uuid);return value == null ? null : value.deepCopy();}
        public void saveReceipt(JsonObject expected, JsonObject next) {
            if (failCas) {failCas = false;throw new StorageConfigurationVolumeAllocation.ReceiptConflictException("Concurrent receipt CAS failure");}
            String uuid = next.get("plannedUuid").getAsString();Assert.assertEquals(expected, receipts.get(uuid));receipts.put(uuid, next.deepCopy());
        }
        public JsonObject findVolume(String uuid) {JsonObject value = volumes.get(uuid);return value == null ? null : value.deepCopy();}
        public void allocateAndRecord(JsonObject allocation, JsonObject expected, JsonObject allocated) {
            fault("allocate-before");String uuid = id(allocation);Assert.assertEquals(expected, receipts.get(uuid));Assert.assertFalse(volumes.containsKey(uuid));allocCalls++;
            JsonObject volume = volume(allocation, "Allocated");volume.add("provenance", allocation.deepCopy());
            // Fake transaction commits both rows together. Production adapter must use Transaction.execute.
            volumes.put(uuid, volume);receipts.put(uuid, allocated.deepCopy());fault("allocate-after-commit");
        }
        JsonObject volume(JsonObject allocation, String state) {
            JsonObject value = StorageConfigurationVolumePlanTest.json("{\"type\":\"DATADISK\"}");value.addProperty("uuid", id(allocation));value.addProperty("state", state);
            for (String key : new String[] {"accountId", "domainId", "projectId", "zoneUuid", "sizeBytes"}) if (allocation.has(key)) value.add(key, allocation.get(key).deepCopy());return value;
        }
        public void createPhysical(JsonObject allocation) {
            fault("create-before");createCalls++;JsonObject value = volumes.get(id(allocation));value.addProperty("state", "Ready");value.add("poolUuid", allocation.get("poolUuid").deepCopy());fault("create-after");
        }
        public void attach(JsonObject allocation) {
            fault("attach-before");attachCalls++;volumes.get(id(allocation)).add("attachedInstanceUuid", allocation.get("targetInstanceUuid").deepCopy());
            if (!observations.containsKey(id(allocation))) {JsonObject observed = new JsonObject();observed.addProperty("volumeUuid", id(allocation));observed.addProperty("mappingStatus", "EXACT");observed.add("sizeBytes", allocation.get("sizeBytes").deepCopy());observed.addProperty("blank", true);observations.put(id(allocation), observed);}
            fault("attach-after");
        }
        public JsonObject inspect(JsonObject allocation) {fault("inspect");return observations.get(id(allocation)).deepCopy();}
        public JsonObject prepareFile(JsonObject allocation, String mode) {
            Assert.assertEquals("FILE", allocation.get("usage").getAsString());fault("prepare-before");JsonObject observed = observations.get(id(allocation));
            if ("FORMAT_IF_EMPTY".equals(mode)) {
                Assert.assertTrue(observed.get("blank").getAsBoolean());Assert.assertFalse(observed.has("filesystemUuid"));formatCalls++;
                observed.addProperty("blank", false);observed.addProperty("filesystemUuid", "filesystem-" + id(allocation));observed.add("filesystem", allocation.get("filesystem").deepCopy());observed.addProperty("preparationVolumeUuid", id(allocation));observed.addProperty("preparationStarted", true);observed.addProperty("preparationComplete", true);
            } else {Assert.assertEquals("MOUNT_EXISTING", mode);Assert.assertTrue(observed.has("filesystemUuid"));mountCalls++;}
            fault("prepare-after");return observed.deepCopy();
        }
        public JsonObject cleanupSafety(JsonObject allocation) {JsonObject value = new JsonObject();for (String field : new String[] {"referenced", "published", "mounted", "formatterActive", "activeSessions", "writerActive", "usedByAnotherVm"}) value.addProperty(field, false);value.addProperty("published", unsafe);return value;}
        public void deleteUnpublished(JsonObject allocation) {deleteCalls++;fault("delete-before");volumes.remove(id(allocation));fault("delete-after");}
        void seedExisting(JsonObject entry) {
            JsonObject volume = volume(entry, "Ready");volume.add("poolUuid", entry.get("poolUuid").deepCopy());volumes.put(id(entry), volume);
            JsonObject observed = new JsonObject();observed.addProperty("volumeUuid", id(entry));observed.addProperty("mappingStatus", "EXACT");observed.add("sizeBytes", entry.get("sizeBytes").deepCopy());observed.addProperty("blank", false);observed.addProperty("filesystemUuid", "existing-filesystem");observed.addProperty("filesystem", "XFS");observations.put(id(entry), observed);
        }
    }
    static JsonObject plan(int count) {return new StorageConfigurationVolumePlanTest.Fixture(count).build();}
    static JsonObject entry(JsonObject plan, int index) {JsonObject allocation = StorageConfigurationVolumePlanTest.allocation(plan, index).deepCopy();allocation.add("planSha256", plan.get("planSha256").deepCopy());return allocation;}
    static String uuid(JsonObject plan, int index) {return entry(plan, index).get("plannedUuid").getAsString();}
    static JsonObject prepare(JsonObject plan, Runtime runtime) {return StorageConfigurationVolumeAllocation.prepare(plan, runtime);}
    static void fail(JsonObject plan, Runtime runtime) {Assert.assertThrows(CloudRuntimeException.class, () -> prepare(plan, runtime));}

    @Test public void multiNewFormatsEachFileOnceAndNeverFormatsRawOrExisting() {
        StorageConfigurationVolumePlanTest.Fixture f = new StorageConfigurationVolumePlanTest.Fixture(4);f.raw(3);f.existing(4);JsonObject plan = f.build();Runtime r = new Runtime();r.seedExisting(entry(plan, 3));
        Assert.assertEquals("PREPARED", prepare(plan, r).get("status").getAsString());Assert.assertEquals(3, r.allocCalls);Assert.assertEquals(2, r.formatCalls);Assert.assertEquals(1, r.mountCalls);
        prepare(plan, r);Assert.assertEquals(3, r.allocCalls);Assert.assertEquals(2, r.formatCalls);Assert.assertEquals(3, r.createCalls);Assert.assertEquals(4, r.attachCalls);
        Assert.assertFalse(r.receipts.get(uuid(plan, 2)).get("formatStarted").getAsBoolean());
    }
    @Test public void lostAllocationCommitResponseReusesExactUuidAndProvenance() {
        JsonObject plan = plan(1);Runtime r = new Runtime();r.failOnce = "allocate-after-commit";fail(plan, r);
        Assert.assertEquals(1, r.allocCalls);Assert.assertEquals("ALLOCATED", r.receipts.get(uuid(plan, 0)).get("phase").getAsString());
        prepare(plan, r);Assert.assertEquals(1, r.allocCalls);Assert.assertEquals(1, r.formatCalls);
    }
    @Test public void interruptedBeforeAllocationCanRetryWithoutDuplicateResource() {
        JsonObject plan = plan(1);Runtime r = new Runtime();r.failOnce = "allocate-before";fail(plan, r);Assert.assertEquals(0, r.allocCalls);Assert.assertTrue(r.volumes.isEmpty());
        prepare(plan, r);Assert.assertEquals(1, r.allocCalls);
    }
    @Test public void createAndAttachResponseLossReconcileWithoutRepeatingEffects() {
        for (String stage : new String[] {"create-before", "create-after", "attach-before", "attach-after", "inspect"}) {
            JsonObject plan = plan(1);Runtime r = new Runtime();r.failOnce = stage;fail(plan, r);prepare(plan, r);
            Assert.assertEquals(stage, 1, r.allocCalls);Assert.assertEquals(stage, 1, r.createCalls);Assert.assertEquals(stage, 1, r.attachCalls);Assert.assertEquals(stage, 1, r.formatCalls);Assert.assertEquals(0, r.deleteCalls);
        }
    }
    @Test public void completedFormatterResponseLossMountsSameUuidWithoutFormattingAgain() {
        JsonObject plan = plan(1);Runtime r = new Runtime();r.failOnce = "prepare-after";fail(plan, r);Assert.assertEquals(1, r.formatCalls);
        String filesystem = r.observations.get(uuid(plan, 0)).get("filesystemUuid").getAsString();prepare(plan, r);
        Assert.assertEquals(1, r.formatCalls);Assert.assertEquals(1, r.mountCalls);Assert.assertEquals(filesystem, r.receipts.get(uuid(plan, 0)).get("filesystemUuid").getAsString());
    }
    @Test public void blankAfterDurableFormatIntentNeverAutomaticallyFormatsOrDeletes() {
        JsonObject plan = plan(1);Runtime r = new Runtime();r.failOnce = "prepare-before";fail(plan, r);Assert.assertEquals(0, r.formatCalls);
        fail(plan, r);Assert.assertEquals(0, r.formatCalls);Assert.assertEquals(0, r.deleteCalls);
        Assert.assertEquals("RECOVERY_REQUIRED", r.receipts.get(uuid(plan, 0)).get("state").getAsString());Assert.assertTrue(r.receipts.get(uuid(plan, 0)).get("formatStarted").getAsBoolean());
    }
    @Test public void partialOrForeignFilesystemWithoutMatchingNativeCompletionCannotBeAdopted() {
        for (boolean partial : new boolean[] {true, false}) {
            JsonObject plan = plan(1);Runtime r = new Runtime();r.failOnce = "prepare-before";fail(plan, r);JsonObject observed = r.observations.get(uuid(plan, 0));observed.addProperty("blank", false);
            if (!partial) {observed.addProperty("filesystemUuid", "foreign-filesystem");observed.addProperty("filesystem", "XFS");observed.addProperty("preparationVolumeUuid", StorageConfigurationVolumePlanTest.id(999));observed.addProperty("preparationComplete", true);}
            fail(plan, r);Assert.assertEquals(0, r.formatCalls);Assert.assertEquals(0, r.mountCalls);Assert.assertEquals(0, r.deleteCalls);
        }
    }
    @Test public void plannedUuidCollisionCannotBeAllocatedAdoptedFormattedOrDeleted() {
        JsonObject plan = plan(1);Runtime r = new Runtime();JsonObject allocation = entry(plan, 0);JsonObject hijack = r.volume(allocation, "Ready");hijack.add("poolUuid", allocation.get("poolUuid"));hijack.add("provenance", new JsonObject());r.volumes.put(uuid(plan, 0), hijack);
        fail(plan, r);Assert.assertEquals(0, r.allocCalls);Assert.assertEquals(0, r.attachCalls);Assert.assertEquals(0, r.formatCalls);Assert.assertEquals(0, r.deleteCalls);
        Assert.assertThrows(CloudRuntimeException.class, () -> StorageConfigurationVolumeAllocation.cleanupUnpublished(plan, StorageConfigurationVolumePlanTest.id(1), true, r));
    }
    @Test public void changedOwnerProjectPoolSizeAttachmentOrReceiptProvenanceFailsClosed() {
        for (String field : new String[] {"accountId", "projectId", "poolUuid", "sizeBytes", "attachedInstanceUuid", "provenance"}) {
            JsonObject plan = plan(1);Runtime r = new Runtime();r.failOnce = "prepare-after";fail(plan, r);JsonObject volume = r.volumes.get(uuid(plan, 0));
            if (field.endsWith("Uuid")) volume.addProperty(field, StorageConfigurationVolumePlanTest.id(999));else if ("provenance".equals(field)) volume.add(field, new JsonObject());else volume.addProperty(field, 999);
            fail(plan, r);Assert.assertEquals(field, 1, r.formatCalls);Assert.assertEquals(0, r.deleteCalls);
        }
        JsonObject plan = plan(1);Runtime r = new Runtime();r.failOnce = "create-before";fail(plan, r);r.receipts.get(uuid(plan, 0)).getAsJsonObject("provenance").addProperty("baselineRevision", 999);fail(plan, r);Assert.assertEquals(0, r.deleteCalls);
    }
    @Test public void preparedFilesystemUuidDriftOrDataDisappearanceNeverReformatsOrReallocates() {
        JsonObject plan = plan(1);Runtime r = new Runtime();prepare(plan, r);r.observations.get(uuid(plan, 0)).addProperty("filesystemUuid", "changed");fail(plan, r);Assert.assertEquals(1, r.formatCalls);
        r.volumes.clear();fail(plan, r);Assert.assertEquals(1, r.allocCalls);Assert.assertEquals(0, r.deleteCalls);
    }
    @Test public void defaultPreserveExplicitCleanupSafetyAndExistingGuardPreventDataDeletion() {
        JsonObject plan = plan(1);Runtime r = new Runtime();r.failOnce = "create-before";fail(plan, r);Assert.assertEquals(0, r.deleteCalls);
        Assert.assertThrows(CloudRuntimeException.class, () -> StorageConfigurationVolumeAllocation.cleanupUnpublished(plan, StorageConfigurationVolumePlanTest.id(1), false, r));
        r.unsafe = true;Assert.assertThrows(CloudRuntimeException.class, () -> StorageConfigurationVolumeAllocation.cleanupUnpublished(plan, StorageConfigurationVolumePlanTest.id(1), true, r));Assert.assertEquals(0, r.deleteCalls);
        Runtime prepared = new Runtime();prepare(plan, prepared);Assert.assertThrows(CloudRuntimeException.class, () -> StorageConfigurationVolumeAllocation.cleanupUnpublished(plan, StorageConfigurationVolumePlanTest.id(1), true, prepared));Assert.assertEquals(0, prepared.deleteCalls);
        StorageConfigurationVolumePlanTest.Fixture f = new StorageConfigurationVolumePlanTest.Fixture(1);f.existing(1);JsonObject existing = f.build();Assert.assertThrows(CloudRuntimeException.class, () -> StorageConfigurationVolumeAllocation.cleanupUnpublished(existing, StorageConfigurationVolumePlanTest.id(1), true, new Runtime()));
    }
    @Test public void explicitUnpublishedCleanupRetriesSameUuidAndReconcilesLostDeleteResponse() {
        for (String stage : new String[] {"delete-before", "delete-after"}) {
            JsonObject plan = plan(1);Runtime r = new Runtime();r.failOnce = "create-before";fail(plan, r);r.failOnce = stage;
            Assert.assertThrows(CloudRuntimeException.class, () -> StorageConfigurationVolumeAllocation.cleanupUnpublished(plan, StorageConfigurationVolumePlanTest.id(1), true, r));
            JsonObject deleted = StorageConfigurationVolumeAllocation.cleanupUnpublished(plan, StorageConfigurationVolumePlanTest.id(1), true, r);Assert.assertEquals("DELETED", deleted.get("phase").getAsString());Assert.assertFalse(r.volumes.containsKey(uuid(plan, 0)));
            int calls = r.deleteCalls;StorageConfigurationVolumeAllocation.cleanupUnpublished(plan, StorageConfigurationVolumePlanTest.id(1), true, r);Assert.assertEquals(calls, r.deleteCalls);
        }
    }
    @Test public void targetScopeRevalidationAndConcurrentCasPreventAllocation() {
        JsonObject plan = plan(1);Runtime r = new Runtime();r.denyFreshScope = true;fail(plan, r);Assert.assertTrue(r.receipts.isEmpty());Assert.assertEquals(0, r.allocCalls);
        r = new Runtime();r.failCas = true;fail(plan, r);Assert.assertEquals(0, r.allocCalls);
    }
    @Test public void failureOnSecondNewKeepsFirstProvenanceAndNoAutomaticCleanup() {
        JsonObject plan = plan(2);Runtime r = new Runtime() {
            @Override public void allocateAndRecord(JsonObject allocation, JsonObject expected, JsonObject receipt) {if (StorageConfigurationVolumePlanTest.id(2).equals(allocation.get("sourceUuid").getAsString()) && allocCalls == 1) {failOnce = "allocate-before";}super.allocateAndRecord(allocation, expected, receipt);}
        };
        fail(plan, r);Assert.assertEquals("PREPARED", r.receipts.get(uuid(plan, 0)).get("phase").getAsString());Assert.assertEquals(1, r.formatCalls);Assert.assertEquals(0, r.deleteCalls);
    }
    @Test public void allMembersArePreflightedBeforeFirstAllocation() {
        JsonObject plan = plan(2);Runtime r = new Runtime() {
            @Override public void validateAllocation(JsonObject allocation) {if (StorageConfigurationVolumePlanTest.id(2).equals(allocation.get("sourceUuid").getAsString())) throw new CloudRuntimeException("Second tenant mapping no longer authorized");}
        };
        fail(plan, r);Assert.assertEquals(0, r.allocCalls);Assert.assertTrue(r.receipts.isEmpty());
    }
    @Test public void nativeJournalFormatIntentOrLiveFormatterPreventsDuplicateMkfs() {
        for (String indicator : new String[] {"preparationStarted", "formatterActive"}) {
            JsonObject plan = plan(1);Runtime r = new Runtime();r.failOnce = "inspect";fail(plan, r);
            r.observations.get(uuid(plan, 0)).addProperty(indicator, true);fail(plan, r);Assert.assertEquals(0, r.formatCalls);Assert.assertEquals(0, r.deleteCalls);
        }
    }

    @Test public void existingFilesystemIsPinnedBeforeMountSoResponseLossCannotAdoptChangedData() {
        StorageConfigurationVolumePlanTest.Fixture f = new StorageConfigurationVolumePlanTest.Fixture(1);f.existing(1);JsonObject plan = f.build();Runtime r = new Runtime();r.seedExisting(entry(plan, 0));r.failOnce = "prepare-after";
        fail(plan, r);Assert.assertEquals("existing-filesystem", r.receipts.get(uuid(plan, 0)).get("filesystemUuid").getAsString());
        r.observations.get(uuid(plan, 0)).addProperty("filesystemUuid", "unexpected-replacement");fail(plan, r);Assert.assertEquals(0, r.formatCalls);Assert.assertEquals(1, r.mountCalls);
    }
    @Test public void formatCompletionCannotBeAcceptedForWrongDeviceUuidOrChangedSize() {
        for (String field : new String[] {"volumeUuid", "sizeBytes", "mappingStatus"}) {
            JsonObject plan = plan(1);Runtime r = new Runtime();r.failOnce = "prepare-after";fail(plan, r);JsonObject observed = r.observations.get(uuid(plan, 0));
            if ("sizeBytes".equals(field)) observed.addProperty(field, 1);else observed.addProperty(field, "wrong-identity");fail(plan, r);Assert.assertEquals(1, r.formatCalls);Assert.assertEquals(0, r.mountCalls);Assert.assertEquals(0, r.deleteCalls);
        }
    }

}
