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
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.cloud.utils.exception.CloudRuntimeException;

public class StorageConfigurationVolumePlanTest {
    static String id(int n) {return String.format("00000000-0000-4000-8000-%012d", n);}
    static JsonObject json(String value) {return com.google.gson.JsonParser.parseString(value).getAsJsonObject();}
    static final class Fixture {
        final Map<String, byte[]> archive = new LinkedHashMap<>();
        final JsonObject scope = json("{\"artifactSha256\":\"" + "a".repeat(64) + "\",\"accountId\":2,\"domainId\":1,\"projectId\":7,\"baselineRevision\":0}");
        final JsonObject blueprint = json("{\"size\":20,\"filesystem\":\"XFS\"}");
        final JsonObject mapping = new JsonObject();final JsonObject catalog = new JsonObject();
        Fixture(int count) {
            scope.addProperty("artifactUuid", id(900));scope.addProperty("allocationNamespace", id(901));scope.addProperty("targetInstanceUuid", id(902));scope.addProperty("zoneUuid", id(903));
            blueprint.addProperty("diskofferingid", id(910));blueprint.addProperty("storageid", id(911));
            JsonObject offering = json("{\"active\":true,\"accessible\":true,\"customized\":true,\"storageType\":\"shared\",\"minSizeGiB\":1,\"maxSizeGiB\":4096,\"maxVolumeSizeGiB\":40000,\"tags\":[\"glue-gfs\"]}");offering.addProperty("uuid", id(910));offering.addProperty("provisioningType", "sparse");
            JsonObject pool = json("{\"up\":true,\"accessible\":true,\"supported\":true,\"tags\":[\"glue-gfs\"]}");pool.addProperty("uuid", id(911));pool.addProperty("zoneUuid", id(903));
            JsonObject offerings = new JsonObject();offerings.add(id(910), offering);catalog.add("offerings", offerings);
            JsonObject pools = new JsonObject();pools.add(id(911), pool);catalog.add("pools", pools);catalog.add("existingVolumes", new JsonObject());
            JsonObject volumes = new JsonObject();JsonArray sources = new JsonArray();JsonArray files = new JsonArray();
            for (int n = 1; n <= count; n++) {
                JsonObject source = json("{\"type\":\"DATADISK\",\"size\":21474836480}");source.addProperty("uuid", id(n));sources.add(source);volumes.addProperty(id(n), "NEW");
                JsonObject file = json("{\"protocol\":\"NFS\",\"filesystem\":\"XFS\"}");file.addProperty("volumeUuid", id(n));files.add(file);
            }
            put("volumes", sources);put("file-shares", files);mapping.add("volumes", volumes);mapping.add("newVolumes", new JsonObject());mapping.addProperty("initialVolumeSourceUuid", id(1));
        }
        void put(String kind, JsonArray rows) {archive.put("desired/" + kind + ".json", rows.toString().getBytes(StandardCharsets.UTF_8));}
        JsonArray rows(String kind) {return com.google.gson.JsonParser.parseString(new String(archive.get("desired/" + kind + ".json"), StandardCharsets.UTF_8)).getAsJsonArray();}
        JsonObject build() {return StorageConfigurationVolumePlan.build(archive, mapping, blueprint, scope, catalog);}
        JsonObject offering() {return catalog.getAsJsonObject("offerings").getAsJsonObject(id(910));}
        JsonObject pool() {return catalog.getAsJsonObject("pools").getAsJsonObject(id(911));}
        void raw(int source) {
            JsonArray files = rows("file-shares"), kept = new JsonArray();for (JsonElement row : files) if (!id(source).equals(row.getAsJsonObject().get("volumeUuid").getAsString())) kept.add(row);
            put("file-shares", kept);JsonArray block = new JsonArray();JsonObject target = json("{\"protocol\":\"ISCSI\"}");target.addProperty("volumeUuid", id(source));block.add(target);put("block-targets", block);
        }
        void existing(int source) {
            mapping.getAsJsonObject("volumes").addProperty(id(source), id(950));
            if (source == 1) {blueprint.addProperty("backingvolumemode", "EXISTING");blueprint.addProperty("existingvolumeid", id(950));}
            JsonObject volume = json("{\"type\":\"DATADISK\",\"state\":\"Ready\",\"reserved\":false,\"sizeBytes\":21474836480,\"accountId\":2,\"domainId\":1,\"projectId\":7}");
            volume.addProperty("uuid", id(950));volume.addProperty("zoneUuid", id(903));volume.addProperty("poolUuid", id(911));catalog.getAsJsonObject("existingVolumes").add(id(950), volume);
        }
    }
    static JsonObject allocation(JsonObject plan, int index) {return plan.getAsJsonArray("allocations").get(index).getAsJsonObject();}
    static void blocked(Fixture fixture) {Assert.assertThrows(CloudRuntimeException.class, fixture::build);}

    @Test public void independentFileRawAndExistingBindingsUseDistinctDeterministicUuids() {
        Fixture f = new Fixture(4);f.raw(3);f.existing(4);
        JsonArray files = f.rows("file-shares");JsonObject alias = files.get(0).deepCopy().getAsJsonObject();alias.addProperty("protocol", "SMB");files.add(alias);f.put("file-shares", files);
        JsonObject plan = f.build();Assert.assertEquals(4, plan.getAsJsonArray("allocations").size());
        Assert.assertEquals("FILE", allocation(plan, 0).get("usage").getAsString());Assert.assertEquals("BLOCK_RAW", allocation(plan, 2).get("usage").getAsString());
        Assert.assertFalse(allocation(plan, 2).has("filesystem"));Assert.assertEquals("EXISTING", allocation(plan, 3).get("mode").getAsString());
        Assert.assertEquals(plan, f.build());Assert.assertNotEquals(allocation(plan, 0).get("plannedUuid"), allocation(plan, 1).get("plannedUuid"));
        f.scope.addProperty("allocationNamespace", id(999));Assert.assertNotEquals(plan.get("planSha256"), f.build().get("planSha256"));
    }
    @Test public void canonicalPlanIsStableAcrossArchiveAndMappingObjectOrder() {
        Fixture f = new Fixture(2);JsonObject before = f.build();JsonArray reversed = new JsonArray();JsonArray sources = f.rows("volumes");reversed.add(sources.get(1));reversed.add(sources.get(0));f.put("volumes", reversed);
        JsonObject reverse = new JsonObject();reverse.addProperty(id(2), "NEW");reverse.addProperty(id(1), "NEW");f.mapping.add("volumes", reverse);Assert.assertEquals(before, f.build());
    }
    @Test public void additionalSizeDefaultsToSourceCeilGiBAndExplicitSpecWins() {
        Fixture f = new Fixture(2);JsonArray rows = f.rows("volumes");rows.get(1).getAsJsonObject().addProperty("size", 10L * 1073741824L + 1);f.put("volumes", rows);
        Assert.assertEquals(11, allocation(f.build(), 1).get("cmdSizeGiB").getAsLong());JsonObject spec = json("{\"sizeGiB\":32}");f.mapping.getAsJsonObject("newVolumes").add(id(2), spec);
        Assert.assertEquals(32, allocation(f.build(), 1).get("cmdSizeGiB").getAsLong());
    }
    @Test public void fixedTenTiBDoesNotPassCmdSizeOrApplyCustomMax4096() {
        Fixture f = new Fixture(1);f.offering().addProperty("customized", false);f.offering().addProperty("sizeBytes", 10L * 1024 * 1073741824L);
        JsonObject allocation = allocation(f.build(), 0);Assert.assertFalse(allocation.has("cmdSizeGiB"));Assert.assertEquals(10995116277760L, allocation.get("sizeBytes").getAsLong());
    }
    @Test public void rootSourceMixedConsumerAndFilesystemConflictsAreBlocked() {
        Fixture root = new Fixture(1);JsonArray volumes = root.rows("volumes");volumes.get(0).getAsJsonObject().addProperty("type", "ROOT");root.put("volumes", volumes);blocked(root);
        Fixture mixed = new Fixture(1);JsonArray block = new JsonArray();JsonObject target = json("{\"protocol\":\"NVME_OF\"}");target.addProperty("volumeUuid", id(1));block.add(target);mixed.put("block-targets", block);blocked(mixed);
        Fixture conflict = new Fixture(1);JsonArray files = conflict.rows("file-shares");JsonObject other = files.get(0).deepCopy().getAsJsonObject();other.addProperty("filesystem", "EXT4");files.add(other);conflict.put("file-shares", files);blocked(conflict);
    }
    @Test public void fakeUsageUnknownSpecSecretFieldAndRawFilesystemAreBlocked() {
        for (String spec : new String[] {"{\"usage\":\"BLOCK_RAW\"}", "{\"password\":\"must-not-enter-receipt\"}", "{\"dataPolicy\":\"DELETE\"}"}) {Fixture f = new Fixture(1);f.mapping.getAsJsonObject("newVolumes").add(id(1), json(spec));blocked(f);}
        Fixture raw = new Fixture(1);raw.raw(1);raw.mapping.getAsJsonObject("newVolumes").add(id(1), json("{\"filesystem\":\"XFS\"}"));blocked(raw);
    }
    @Test public void foreignTenantProjectZoneAndAttachedExistingDataAreBlocked() {
        for (String field : new String[] {"accountId", "domainId", "projectId", "zoneUuid", "attachedVmUuid", "reserved"}) {
            Fixture f = new Fixture(1);f.existing(1);JsonObject existing = f.catalog.getAsJsonObject("existingVolumes").getAsJsonObject(id(950));
            if (field.endsWith("Uuid")) existing.addProperty(field, id(999));else if ("reserved".equals(field)) existing.addProperty(field, true);else existing.addProperty(field, 999);blocked(f);
        }
    }
    @Test public void offeringPoolAccessTagsAndSizeLimitsAreBlocked() {
        Fixture f = new Fixture(1);f.offering().addProperty("accessible", false);blocked(f);
        f = new Fixture(1);f.pool().addProperty("up", false);blocked(f);
        f = new Fixture(1);f.pool().add("tags", new JsonArray());blocked(f);
        f = new Fixture(1);f.pool().addProperty("zoneUuid", id(999));blocked(f);
        f = new Fixture(1);f.blueprint.addProperty("size", 4097);blocked(f);
        f = new Fixture(1);f.offering().addProperty("customized", false);f.offering().addProperty("sizeBytes", 1073741824L);blocked(f);
        f = new Fixture(1);f.offering().addProperty("maxVolumeSizeGiB", 10);blocked(f);
    }
    @Test public void invalidIntegersOverflowUnknownSourceAndDuplicateTargetsAreBlocked() {
        for (String size : new String[] {"0", "-1", "20.5", "9223372036854775808", "9223372036854775807"}) {Fixture f = new Fixture(1);f.blueprint.add("size", com.google.gson.JsonParser.parseString(size));blocked(f);}
        Fixture unknown = new Fixture(1);unknown.mapping.getAsJsonObject("newVolumes").add(id(999), new JsonObject());blocked(unknown);
        Fixture missing = new Fixture(1);missing.mapping.getAsJsonObject("volumes").remove(id(1));blocked(missing);
        Fixture duplicate = new Fixture(2);duplicate.existing(1);duplicate.mapping.getAsJsonObject("volumes").addProperty(id(2), id(950));blocked(duplicate);
    }
    @Test public void frozenPlanRejectsAnyScopeOrAllocationMutation() {
        Fixture f = new Fixture(1);JsonObject plan = f.build();StorageConfigurationVolumePlan.requireFrozen(plan);
        allocation(plan, 0).addProperty("sizeBytes", 1);JsonObject firstChanged = plan;Assert.assertThrows(CloudRuntimeException.class, () -> StorageConfigurationVolumePlan.requireFrozen(firstChanged));
        plan = f.build();plan.getAsJsonObject("scope").addProperty("accountId", 99);JsonObject changed = plan;Assert.assertThrows(CloudRuntimeException.class, () -> StorageConfigurationVolumePlan.requireFrozen(changed));
    }
    @Test public void encryptedNewOfferingRequiresExplicitSupportedKmsPolicy() {
        Fixture f = new Fixture(1);f.offering().addProperty("encrypted", true);blocked(f);
        f.offering().addProperty("encrypted", false);f.build();
    }

    @Test public void initialNewExistingBlueprintMustMatchTheReviewedPrimitiveBinding() {
        Fixture mismatch = new Fixture(1);mismatch.blueprint.addProperty("backingvolumemode", "EXISTING");mismatch.blueprint.addProperty("existingvolumeid", id(950));blocked(mismatch);
        Fixture existing = new Fixture(1);existing.existing(1);existing.build();existing.blueprint.addProperty("existingvolumeid", id(951));blocked(existing);
        Fixture wrongMode = new Fixture(1);wrongMode.blueprint.addProperty("backingvolumemode", "UNKNOWN");blocked(wrongMode);
    }

    @Test public void allNewDataRequiresSparseOrFatAndUnknownOrThinIsBlockedBeforeAllocation() {
        for (String mode : new String[] {"thin", "THIN", "unknown"}) {Fixture f = new Fixture(1);f.offering().addProperty("provisioningType", mode);blocked(f);}
        Fixture missing = new Fixture(1);missing.offering().remove("provisioningType");blocked(missing);
        Fixture sparse = new Fixture(1);Assert.assertEquals("SPARSE", allocation(sparse.build(),0).get("provisioningType").getAsString());
        Fixture fat = new Fixture(1);fat.offering().addProperty("provisioningType", "fat");Assert.assertEquals("FAT", allocation(fat.build(),0).get("provisioningType").getAsString());
    }

}
