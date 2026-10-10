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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.cloud.utils.exception.CloudRuntimeException;

/** Pure multi-volume plan. Catalog entries are fresh, access-checked server observations, never client input. */
public final class StorageConfigurationVolumePlan {
    private static final long GIB = 1073741824L;
    private static final Set<String> SPEC_KEYS = Set.of("diskofferingid", "storageid", "sizeGiB", "usage", "filesystem", "dataPolicy");
    private static final Set<String> SCOPE_KEYS = Set.of("artifactUuid", "artifactSha256", "allocationNamespace", "targetInstanceUuid", "accountId", "domainId", "projectId", "zoneUuid", "baselineRevision");
    private StorageConfigurationVolumePlan() { }

    public static JsonObject build(Map<String, byte[]> archive, JsonObject mapping, JsonObject blueprint,
                                   JsonObject targetScope, JsonObject catalog) {
        JsonObject scope = targetScope.deepCopy();
        require(SCOPE_KEYS.containsAll(scope.keySet()), "Unexpected target allocation scope field");
        for (String key : Set.of("artifactUuid", "allocationNamespace", "targetInstanceUuid", "zoneUuid")) uuid(text(scope, key));
        require(text(scope, "artifactSha256").matches("[a-f0-9]{64}"), "Invalid artifact fingerprint");
        positive(scope, "accountId");positive(scope, "domainId");
        require(number(scope, "baselineRevision") >= 0, "Invalid baseline revision");
        if (scope.has("projectId") && !scope.get("projectId").isJsonNull()) positive(scope, "projectId");
        JsonObject volumes = object(mapping, "volumes");
        JsonObject specs = mapping.has("newVolumes") ? object(mapping, "newVolumes") : new JsonObject();
        Map<String, JsonObject> sources = new TreeMap<>();
        for (JsonElement value : rows(archive, "volumes")) {
            JsonObject source = value.getAsJsonObject();String id = uuid(text(source, "uuid"));
            require("DATADISK".equals(text(source, "type")), "Only DATA can be mapped by a clone volume plan");
            positive(source, "size");require(sources.put(id, source) == null, "Duplicate archive DATA UUID");
        }
        require(!sources.isEmpty() && volumes.keySet().equals(sources.keySet()), "Volume mappings must cover exactly the archive DATA inventory");
        require(sources.keySet().containsAll(specs.keySet()), "Unknown additional NEW source UUID");
        String initial = uuid(text(mapping, "initialVolumeSourceUuid"));require(sources.containsKey(initial), "Initial DATA is outside the archive");
        String initialMode = blueprint.has("backingvolumemode") ? text(blueprint, "backingvolumemode") : "NEW";
        require(Set.of("NEW", "EXISTING").contains(initialMode), "Unknown initial backing mode");
        String initialTarget = text(volumes, initial);
        require("NEW".equals(initialMode) ? "NEW".equals(initialTarget) : initialTarget.equals(text(blueprint, "existingvolumeid")), "Initial volume mapping conflicts with the reviewed service blueprint");
        Map<String, String> usage = new TreeMap<>();Map<String, String> filesystems = new TreeMap<>();
        for (String kind : new String[] {"file-shares", "posix-directory-policies", "block-targets"}) {
            for (JsonElement value : rows(archive, kind)) {
                JsonObject row = value.getAsJsonObject();
                if ("block-targets".equals(kind)) {
                    String protocol = text(row, "protocol");require(Set.of("ISCSI", "NVME_OF").contains(protocol), "Unsupported raw block consumer protocol");
                    JsonElement config = row.get("config");JsonElement subtype = config != null && config.isJsonObject() ? config.getAsJsonObject().get("type") : null;
                    boolean logicalSubsystem = "NVME_OF".equals(protocol) && subtype != null && subtype.isJsonPrimitive()
                            && subtype.getAsJsonPrimitive().isString() && "subsystem".equals(subtype.getAsString());
                    if (logicalSubsystem) {
                        require(!row.has("volumeUuid") || row.get("volumeUuid").isJsonNull(), "A logical NVMe subsystem cannot bind DATA; namespace mapping is required");
                        continue;
                    }
                }
                String source = uuid(text(row, "volumeUuid"));
                require(sources.containsKey(source), "Consumer references an unknown DATA UUID");
                if ("file-shares".equals(kind)) require(Set.of("NFS", "SMB").contains(text(row, "protocol")), "Unsupported FILE consumer protocol");
                String inferred = "block-targets".equals(kind) ? "BLOCK_RAW" : "FILE";
                String before = usage.putIfAbsent(source, inferred);
                require(before == null || before.equals(inferred), "One DATA cannot mix FILE and raw block consumers");
                if ("file-shares".equals(kind)) {
                    String fs = text(row, "filesystem").toUpperCase(java.util.Locale.ROOT);
                    require(Set.of("XFS", "EXT4").contains(fs), "Unsupported clone filesystem");
                    String previous = filesystems.putIfAbsent(source, fs);
                    require(previous == null || previous.equals(fs), "Conflicting FILE filesystem consumers");
                }
            }
        }
        JsonObject bindings = new JsonObject();JsonArray allocations = new JsonArray();Set<String> targets = new HashSet<>();
        for (Map.Entry<String, JsonObject> item : sources.entrySet()) {
            String sourceUuid = item.getKey();JsonObject source = item.getValue();String target = text(volumes, sourceUuid);
            require(usage.containsKey(sourceUuid), "DATA has no supported clone consumer");
            JsonObject allocation = scope.deepCopy();allocation.addProperty("sourceUuid", sourceUuid);
            allocation.addProperty("sourceSizeBytes", number(source, "size"));allocation.addProperty("usage", usage.get(sourceUuid));
            allocation.addProperty("dataPolicy", "PRESERVE");allocation.addProperty("initial", initial.equals(sourceUuid));
            String filesystem = filesystems.get(sourceUuid);
            if ("FILE".equals(usage.get(sourceUuid))) {
                if (filesystem == null) filesystem = text(blueprint, "filesystem").toUpperCase(java.util.Locale.ROOT);
                require(Set.of("XFS", "EXT4").contains(filesystem), "FILE filesystem must be explicit");
                allocation.addProperty("filesystem", filesystem);
            }
            if ("NEW".equals(target)) {
                JsonObject spec = specs.has(sourceUuid) ? object(specs, sourceUuid) : new JsonObject();
                require(SPEC_KEYS.containsAll(spec.keySet()), "Unknown NEW allocation field");
                if (spec.has("usage")) require(usage.get(sourceUuid).equals(text(spec, "usage")), "Consumer usage cannot be overridden");
                if (spec.has("filesystem")) require("FILE".equals(usage.get(sourceUuid)) && filesystem.equals(text(spec, "filesystem").toUpperCase(java.util.Locale.ROOT)), "Filesystem does not match FILE consumers");
                if (spec.has("dataPolicy")) require("PRESERVE".equals(text(spec, "dataPolicy")), "Clone DATA defaults to PRESERVE; automatic delete is forbidden");
                String offeringUuid = uuid(spec.has("diskofferingid") ? text(spec, "diskofferingid") : text(blueprint, "diskofferingid"));
                String poolUuid = uuid(spec.has("storageid") ? text(spec, "storageid") : text(blueprint, "storageid"));
                JsonObject offering = catalogEntry(catalog, "offerings", offeringUuid);JsonObject pool = pool(catalog, poolUuid, scope);
                require(flag(offering, "active") && flag(offering, "accessible"), "Offering is unavailable to the target tenant");
                require("shared".equals(text(offering, "storageType")), "Clone offering must use shared storage");
                String provisioning = text(offering, "provisioningType").toUpperCase(java.util.Locale.ROOT);
                require(Set.of("SPARSE", "FAT").contains(provisioning), "NEW clone DATA must be SPARSE or FAT; THIN is forbidden");
                allocation.addProperty("provisioningType", provisioning);
                require(!offering.has("encrypted") || !flag(offering, "encrypted"), "Encrypted NEW offering requires an explicit supported KMS mapping");
                Set<String> poolTags = strings(pool, "tags");require(poolTags.containsAll(strings(offering, "tags")), "Offering tags do not match the selected pool");
                long minimum = ceilGiB(number(source, "size"));
                long requested = spec.has("sizeGiB") ? positive(spec, "sizeGiB") : initial.equals(sourceUuid) && blueprint.has("size") ? positive(blueprint, "size") : minimum;
                require(requested >= minimum, "NEW DATA is smaller than its source");
                long effective;
                if (flag(offering, "customized")) {
                    require(requested >= positive(offering, "minSizeGiB") && requested <= positive(offering, "maxSizeGiB"), "Custom volume size is outside the allowed range");
                    effective = bytes(requested);allocation.addProperty("cmdSizeGiB", requested);
                } else {
                    effective = positive(offering, "sizeBytes");
                    require(effective >= number(source, "size") && effective >= bytes(requested), "Fixed offering is smaller than the reviewed request");
                }
                require(ceilGiB(effective) <= positive(offering, "maxVolumeSizeGiB"), "Volume exceeds the standard maximum size");
                target = UUID.nameUUIDFromBytes(("storage-config-volume:" + text(scope, "allocationNamespace") + ":" + sourceUuid).getBytes(StandardCharsets.UTF_8)).toString();
                require(!sources.containsKey(target), "A NEW UUID cannot alias source DATA");
                allocation.addProperty("mode", "NEW");allocation.addProperty("offeringUuid", offeringUuid);allocation.addProperty("poolUuid", poolUuid);
                allocation.addProperty("sizeBytes", effective);allocation.addProperty("requestedSizeGiB", requested);
                allocation.addProperty("offeringFingerprint", sha256(canonical(offering)));allocation.addProperty("poolFingerprint", sha256(canonical(pool)));
            } else {
                uuid(target);require(!specs.has(sourceUuid), "EXISTING DATA cannot carry a NEW allocation spec");
                JsonObject existing = catalogEntry(catalog, "existingVolumes", target);
                require("DATADISK".equals(text(existing, "type")) && "Ready".equals(text(existing, "state")), "Existing backing must be a Ready DATA volume");
                require(!flag(existing, "reserved") && (!existing.has("attachedVmUuid") || existing.get("attachedVmUuid").isJsonNull()), "Existing backing must be unattached and unreserved");
                requireSameScope(scope, existing);
                require(positive(existing, "sizeBytes") >= number(source, "size"), "Existing backing is smaller than source DATA");
                String poolUuid = uuid(text(existing, "poolUuid"));pool(catalog, poolUuid, scope);
                allocation.addProperty("mode", "EXISTING");allocation.addProperty("poolUuid", poolUuid);allocation.addProperty("sizeBytes", number(existing, "sizeBytes"));
            }
            require(targets.add(target), "Different source DATA cannot share a target binding");
            allocation.addProperty("plannedUuid", target);bindings.addProperty(sourceUuid, target);allocations.add(allocation);
        }
        JsonObject plan = new JsonObject();plan.addProperty("schemaVersion", 1);plan.add("scope", scope);plan.add("volumeMappings", bindings);plan.add("allocations", allocations);
        plan.addProperty("planSha256", sha256(canonical(plan)));return plan;
    }

    /** Resolve server-created service/initial DATA once, leaving every additional UUID and reviewed resource unchanged. */
    public static JsonObject bindAdditionalExecution(JsonObject reviewed, String actualTargetInstanceUuid, String actualInitialVolumeUuid) {
        requireFrozen(reviewed);uuid(actualTargetInstanceUuid);uuid(actualInitialVolumeUuid);
        if (reviewed.has("realization")) {
            JsonObject saved = object(reviewed, "realization");
            require(actualTargetInstanceUuid.equals(text(saved, "targetInstanceUuid")) && actualInitialVolumeUuid.equals(text(saved, "initialVolumeUuid")), "A persisted execution realization cannot change target or initial DATA");
            return reviewed.deepCopy();
        }
        JsonObject execution = reviewed.deepCopy();JsonArray additional = new JsonArray();JsonObject bindings = reviewed.getAsJsonObject("volumeMappings").deepCopy();String initialSource = null;
        for (JsonElement value : reviewed.getAsJsonArray("allocations")) {
            JsonObject allocation = value.getAsJsonObject();String source = text(allocation, "sourceUuid");
            require(!actualTargetInstanceUuid.equals(source) && !actualTargetInstanceUuid.equals(text(allocation, "plannedUuid")), "Actual target identity cannot alias a DATA binding");
            require(!actualInitialVolumeUuid.equals(source), "Actual initial DATA cannot alias source DATA");
            if (flag(allocation, "initial")) {
                require(initialSource == null, "Execution plan must have exactly one initial DATA source");initialSource = source;
                if ("EXISTING".equals(text(allocation, "mode"))) require(actualInitialVolumeUuid.equals(text(allocation, "plannedUuid")), "Existing initial DATA cannot be replaced by a foreign volume");
            } else {
                require(!actualInitialVolumeUuid.equals(text(allocation, "plannedUuid")), "Actual initial DATA cannot alias additional backing");
                JsonObject realized = allocation.deepCopy();realized.addProperty("targetInstanceUuid", actualTargetInstanceUuid);additional.add(realized);
            }
        }
        require(initialSource != null, "Execution plan has no reviewed initial DATA source");bindings.addProperty(initialSource, actualInitialVolumeUuid);
        execution.getAsJsonObject("scope").addProperty("targetInstanceUuid", actualTargetInstanceUuid);execution.add("allocations", additional);execution.add("volumeMappings", bindings);
        execution.addProperty("parentPlanSha256", text(reviewed, "planSha256"));execution.remove("planSha256");JsonObject realization = new JsonObject();realization.addProperty("targetInstanceUuid", actualTargetInstanceUuid);realization.addProperty("initialVolumeSourceUuid", initialSource);realization.addProperty("initialVolumeUuid", actualInitialVolumeUuid);execution.add("realization", realization);
        execution.addProperty("planSha256", sha256(canonical(execution)));return execution;
    }

    /** Optional stronger DTO overload; no caller-supplied actual resource observation may enter this boundary. */
    public static JsonObject bindAdditionalExecution(JsonObject reviewed, JsonObject actualTarget, JsonObject actualInitial) {
        requireFrozen(reviewed);JsonObject scope = object(reviewed, "scope");requireSameScope(scope, actualTarget);requireSameScope(scope, actualInitial);
        require("DATADISK".equals(text(actualInitial, "type")) && "Ready".equals(text(actualInitial, "state")), "Realized initial backing must be Ready DATA");
        String target = uuid(text(actualTarget, "uuid")), initial = uuid(text(actualInitial, "uuid"));require(target.equals(text(actualInitial, "attachedInstanceUuid")), "Initial DATA is not attached to the realized service");
        for (JsonElement value : reviewed.getAsJsonArray("allocations")) {
            JsonObject item = value.getAsJsonObject();if (!flag(item, "initial")) continue;
            require(text(item, "poolUuid").equals(text(actualInitial, "poolUuid")) && number(item, "sizeBytes") == number(actualInitial, "sizeBytes"), "Initial DATA pool/size differs from the reviewed creator contract");
            if ("NEW".equals(text(item, "mode"))) require(text(item, "provisioningType").equalsIgnoreCase(text(actualInitial, "provisioningType")), "Initial NEW DATA provisioning differs from reviewed SPARSE/FAT");
        }
        return bindAdditionalExecution(reviewed, target, initial);
    }

    public static void requireFrozen(JsonObject plan) {
        JsonObject body = plan.deepCopy();String expected = text(body, "planSha256");body.remove("planSha256");
        require(expected.equals(sha256(canonical(body))), "Allocation plan changed after review");
    }
    static JsonObject object(JsonObject value, String key) {
        require(value != null && value.has(key) && value.get(key).isJsonObject(), "Required allocation object is absent: " + key);return value.getAsJsonObject(key);
    }
    static String text(JsonObject value, String key) {
        require(value != null && value.has(key) && value.get(key).isJsonPrimitive() && value.get(key).getAsJsonPrimitive().isString() && !value.get(key).getAsString().isBlank(), "Required allocation string is absent: " + key);return value.get(key).getAsString();
    }
    static long number(JsonObject value, String key) {
        try {require(value.has(key) && value.get(key).isJsonPrimitive() && value.get(key).getAsJsonPrimitive().isNumber(), "Invalid allocation integer: " + key);return value.get(key).getAsBigDecimal().longValueExact();}
        catch (ArithmeticException | NumberFormatException invalid) {throw new CloudRuntimeException("Allocation integer is out of range: " + key);}
    }
    static long positive(JsonObject value, String key) {long result = number(value, key);require(result > 0, "Allocation value must be positive: " + key);return result;}
    static boolean flag(JsonObject value, String key) {require(value.has(key) && value.get(key).isJsonPrimitive() && value.get(key).getAsJsonPrimitive().isBoolean(), "Required allocation boolean is absent: " + key);return value.get(key).getAsBoolean();}
    static String uuid(String value) {require(value != null && value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"), "Invalid stable allocation UUID");return value;}
    static void require(boolean condition, String message) {if (!condition) throw new CloudRuntimeException(message);}
    static void requireSameScope(JsonObject expected, JsonObject actual) {
        for (String key : Set.of("accountId", "domainId", "zoneUuid")) require(expected.get(key).equals(actual.get(key)), "Mapped DATA is outside target owner/domain/zone");
        JsonElement before = expected.get("projectId"), after = actual.get("projectId");
        require((before == null || before.isJsonNull()) ? after == null || after.isJsonNull() : before.equals(after), "Mapped DATA is outside target project");
    }
    private static JsonObject catalogEntry(JsonObject catalog, String kind, String id) {JsonObject entry = object(object(catalog, kind), id);require(id.equals(text(entry, "uuid")), "Catalog UUID mismatch");return entry;}
    private static JsonObject pool(JsonObject catalog, String id, JsonObject scope) {JsonObject pool = catalogEntry(catalog, "pools", id);require(flag(pool, "up") && flag(pool, "accessible") && flag(pool, "supported") && text(scope, "zoneUuid").equals(text(pool, "zoneUuid")), "Selected pool is unavailable in the target zone");return pool;}
    private static Set<String> strings(JsonObject value, String key) {require(value.has(key) && value.get(key).isJsonArray(), "Required catalog tag array is absent");Set<String> result = new HashSet<>();for (JsonElement entry : value.getAsJsonArray(key)) {require(entry.isJsonPrimitive() && entry.getAsJsonPrimitive().isString(), "Invalid catalog tag");result.add(entry.getAsString());}return result;}
    private static JsonArray rows(Map<String, byte[]> archive, String kind) {byte[] bytes = archive.get("desired/" + kind + ".json");if (bytes == null) return new JsonArray();try {JsonElement value = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));require(value.isJsonArray(), "Archive resource must be an array");return value.getAsJsonArray();} catch (RuntimeException invalid) {throw new CloudRuntimeException("Invalid archive allocation resource: " + kind);}}
    private static long ceilGiB(long bytes) {return 1 + (bytes - 1) / GIB;}
    private static long bytes(long gib) {try {return Math.multiplyExact(gib, GIB);}catch (ArithmeticException overflow) {throw new CloudRuntimeException("Allocation size overflow");}}
    static String canonical(JsonElement value) {
        if (value.isJsonObject()) {JsonObject ordered = new JsonObject();Map<String, JsonElement> fields = new TreeMap<>();for (Map.Entry<String, JsonElement> field : value.getAsJsonObject().entrySet()) fields.put(field.getKey(), field.getValue());fields.forEach((key, item) -> ordered.add(key, JsonParser.parseString(canonical(item))));return ordered.toString();}
        if (value.isJsonArray()) {JsonArray ordered = new JsonArray();for (JsonElement item : value.getAsJsonArray()) ordered.add(JsonParser.parseString(canonical(item)));return ordered.toString();}
        return value.toString();
    }
    static String sha256(String value) {try {byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder hex = new StringBuilder();for (byte b : digest) hex.append(String.format("%02x", b & 255));return hex.toString();}catch (java.security.NoSuchAlgorithmException unavailable) {throw new IllegalStateException(unavailable);}}
}
