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

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

/** Pure validation only. The caller owns explicit administration, cancellation, locks and every effect. */
public final class StorageVerifiedNfsAclTargetRecovery {
    private static final List<String> TABLES = List.of("storage_service_protocol", "storage_file_share",
            "storage_block_target", "storage_identity_domain", "storage_access_rule", "storage_posix_directory_policy");
    private static final Set<String> PATHS = Set.of("desired-state/nfs-export-apply.json", "desired-state/smb-share-apply.json",
            "iscsi-targets.json", "nvmeof-subsystems.json", "posix-directory-policies.json", "network-endpoints.json", "sharedfs-network.json");
    private static final String NFS = "desired-state/nfs-export-apply.json";
    private static final Set<String> ACL_FIELDS = Set.of("id", "uuid", "resource_type", "resource_id", "principal_type",
            "principal", "permission", "secret_ref", "state", "config_json", "created", "updated", "removed");
    private static final Set<String> ACL_CONFIG = Set.of("readOnly", "rootSquash", "allSquash", "sync", "secure",
            "protocolMode", "endpointMode", "listenerGroupPorts", "anonUid", "anonGid");
    private static final Pattern NUMBER = Pattern.compile("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?");
    private static final int MAX = 16 * 1024 * 1024;

    private StorageVerifiedNfsAclTargetRecovery() { }

    public static boolean eligible(StorageServiceOperationVO operation) {
        return operation != null && "createstoragenfsaclresponse".equals(operation.getAction())
                && "RECOVERY_REQUIRED".equals(operation.getState());
    }

    public static JsonObject require(String instanceUuid, StorageServiceOperationVO operation, String currentSnapshotJson,
            JsonObject currentBindings, JsonObject nativeStatus, JsonObject frozenSource, JsonObject frozenTarget) {
        try {
            requireValue(eligible(operation) && operation.getId() > 0 && operation.getInstanceId() > 0
                    && operation.getRevision() > 1 && operation.getCreatedBy() > 0);
            uuid(instanceUuid); uuid(operation.getUuid());
            JsonObject previous = parse(operation.getPreviousSnapshotJson());
            JsonObject source = projection(previous), target = parse(operation.getSnapshotJson());
            JsonObject live = parse(currentSnapshotJson), result = parse(operation.getResultJson());
            Map<String, JsonObject> before = snapshot(source, operation.getInstanceId());
            Map<String, JsonObject> after = snapshot(target, operation.getInstanceId());
            snapshot(live, operation.getInstanceId());
            requireValue(same(live, source) || same(live, target));
            for (String table : TABLES) requireValue(same(before.get(table).get("columns"), after.get(table).get("columns")));
            for (String table : List.of("storage_service_protocol", "storage_block_target", "storage_identity_domain", "storage_posix_directory_policy")) {
                requireValue(same(before.get(table), after.get(table)));
            }

            JsonObject share = changedShare(before.get("storage_file_share"), after.get("storage_file_share"), operation.getInstanceId());
            JsonObject acl = insertedAcl(before.get("storage_access_rule"), after.get("storage_access_rule"), share);
            JsonObject aclConfig = parse(text(acl, "config_json"));
            validateAclConfig(aclConfig);
            JsonObject response = object(result, "_response");
            for (String field : List.of("id", "resourcetype", "resourceid", "principaltype", "principal", "permission", "state", "config")) text(response, field);
            requireValue(text(response, "id").equals(text(acl, "uuid"))
                    && text(response, "resourceid").equals(text(share, "uuid"))
                    && "FILE_SHARE".equals(text(response, "resourcetype"))
                    && text(response, "principaltype").equals(text(acl, "principal_type"))
                    && text(response, "principal").equals(text(acl, "principal"))
                    && text(response, "permission").equals(text(acl, "permission"))
                    && "Ready".equals(text(response, "state"))
                    && same(parse(text(response, "config")), aclConfig));
            requireValue(response.keySet().stream().allMatch(Set.of("id", "resourcetype", "resourceid", "principaltype",
                    "principal", "permission", "state", "config", "jobid", "jobstatus")::contains));

            bindings(currentBindings);
            requireValue(same(currentBindings, object(result, "_targetResourceBindings")));
            String boot = uuid(text(result, "_targetNativeBootId")), checksum = checksum(text(result, "_targetConfigurationSha256"));
            requireValue(bool(nativeStatus, "success") && bool(nativeStatus, "generationSupported")
                    && "PENDING".equals(text(nativeStatus, "generationStatus"))
                    && operation.getUuid().equals(uuid(text(nativeStatus, "pendingOperationUuid")))
                    && boot.equals(uuid(text(nativeStatus, "bootId")))
                    && checksum.equals(checksum(text(nativeStatus, "configurationSha256"))));
            JsonObject sourceGeneration = frozen(frozenSource, instanceUuid, null, 0);
            JsonObject targetGeneration = frozen(frozenTarget, instanceUuid, operation.getUuid(), operation.getRevision());
            requireValue(whole(sourceGeneration, "revision") < operation.getRevision()
                    && whole(nativeStatus, "runtimeRevision") == whole(sourceGeneration, "revision")
                    && same(object(nativeStatus, "generation"), sourceGeneration)
                    && same(object(object(previous, "nativeGeneration"), "previous"), sourceGeneration)
                    && same(object(previous, "nativeDesiredState"), object(frozenSource, "configurationDesiredState"))
                    && checksum.equals(text(targetGeneration, "configurationSha256"))
                    && checksum.equals(text(frozenTarget, "configurationSha256")));
            JsonObject sourceDesired = desired(object(frozenSource, "configurationDesiredState"));
            JsonObject targetDesired = desired(object(frozenTarget, "configurationDesiredState"));
            requireValue(same(desired(object(nativeStatus, "configurationDesiredState")), targetDesired));
            for (String path : PATHS) if (!NFS.equals(path)) requireValue(same(sourceDesired.get(path), targetDesired.get(path)));
            nativeNfs(object(sourceDesired, NFS), object(targetDesired, NFS), share, acl, aclConfig, currentBindings);

            JsonObject proof = new JsonObject(), scope = new JsonObject();
            scope.addProperty("instanceUuid", instanceUuid); scope.addProperty("operationUuid", operation.getUuid());
            scope.addProperty("revision", operation.getRevision());
            proof.addProperty("schemaVersion", 1); proof.addProperty("kind", "VERIFIED_PENDING_NFS_ACL_TARGET");
            proof.add("scope", scope); proof.addProperty("bootId", boot); proof.addProperty("configurationSha256", checksum);
            proof.addProperty("sourceSnapshotSha256", digest(source)); proof.addProperty("targetSnapshotSha256", digest(target));
            proof.addProperty("bindingsSha256", digest(currentBindings)); proof.addProperty("sourceGenerationSha256", digest(sourceGeneration));
            // Normal verify rewrites only TARGET verifiedAt; an explicit retry must keep the same durable authority.
            JsonObject stableTargetGeneration = targetGeneration.deepCopy(); stableTargetGeneration.remove("verifiedAt");
            proof.addProperty("targetGenerationSha256", digest(stableTargetGeneration));
            proof.addProperty("shareUuid", text(share, "uuid")); proof.addProperty("aclUuid", text(acl, "uuid"));
            proof.addProperty("databasePosition", same(live, target) ? "TARGET" : "SOURCE");
            return proof;
        } catch (RuntimeException failure) {
            throw new CloudRuntimeException("Verified pending NFS ACL target authority is unavailable", failure);
        }
    }

    /** The source-to-target database position changes legitimately after the durable restore intent. */
    public static JsonObject authority(JsonObject proof) {
        requireValue(proof != null && Set.of("SOURCE", "TARGET").contains(text(proof, "databasePosition")));
        JsonObject result = proof.deepCopy(); result.remove("databasePosition"); return result;
    }

    private static JsonObject changedShare(JsonObject source, JsonObject target, long instanceId) {
        Map<Long, JsonObject> old = rows(source), latest = rows(target);
        requireValue(old.keySet().equals(latest.keySet()));
        JsonObject changed = null;
        for (long id : old.keySet()) {
            JsonObject a = old.get(id), b = latest.get(id);
            if (same(a, b)) continue;
            requireValue(changed == null && "Allocated".equals(text(a, "state")) && "Ready".equals(text(b, "state")));
            JsonObject copy = b.deepCopy(); copy.add("state", a.get("state"));
            requireValue(same(a, copy) && "NFS".equals(text(b, "protocol"))
                    && whole(b, "instance_id") == instanceId && whole(b, "volume_id") > 0);
            uuid(text(b, "uuid")); changed = b;
        }
        requireValue(changed != null); return changed;
    }

    private static JsonObject insertedAcl(JsonObject source, JsonObject target, JsonObject share) {
        Map<Long, JsonObject> old = rows(source), latest = rows(target);
        requireValue(latest.size() == old.size() + 1 && latest.keySet().containsAll(old.keySet()));
        for (long id : old.keySet()) requireValue(same(old.get(id), latest.get(id)));
        List<Long> added = new ArrayList<>(latest.keySet()); added.removeAll(old.keySet());
        requireValue(added.size() == 1);
        JsonObject acl = latest.get(added.get(0));
        requireValue(acl.keySet().equals(ACL_FIELDS) && "FILE_SHARE".equals(text(acl, "resource_type"))
                && whole(acl, "resource_id") == whole(share, "id") && acl.get("secret_ref").isJsonNull()
                && "Ready".equals(text(acl, "state")) && whole(acl, "created") > 0
                && Set.of("READ_ONLY", "READ_WRITE").contains(text(acl, "permission")));
        requireValue(acl.get("removed").isJsonNull()
                && (acl.get("updated").isJsonNull() || whole(acl, "updated") > 0));
        for (JsonElement item : array(target, "columns")) {
            JsonObject column = item.getAsJsonObject();
            if (Set.of("updated", "removed").contains(text(column, "name"))) {
                requireValue(whole(column, "type") == java.sql.Types.TIMESTAMP);
            }
        }
        uuid(text(acl, "uuid"));
        String principal = text(acl, "principal"), type = text(acl, "principal_type");
        if ("CIDR".equals(type)) {
            String[] parts = principal.split("/", -1); requireValue(parts.length == 2); ipv4(parts[0]);
            requireValue(parts[1].matches("0|[1-9]|[12][0-9]|3[0-2]"));
        } else { requireValue("IP_ADDRESS".equals(type)); ipv4(principal); }
        for (JsonObject row : old.values()) requireValue(!("FILE_SHARE".equals(text(row, "resource_type"))
                && whole(row, "resource_id") == whole(share, "id")));
        return acl;
    }

    private static void validateAclConfig(JsonObject config) {
        requireValue(ACL_CONFIG.containsAll(config.keySet()));
        for (String key : List.of("readOnly", "rootSquash", "allSquash", "sync", "secure")) bool(config, key);
        requireValue(Set.of("V4_ONLY", "V3V4_DUAL").contains(text(config, "protocolMode"))
                && Set.of("LISTENER_GROUP", "ALL").contains(text(config, "endpointMode")));
        JsonArray ports = array(config, "listenerGroupPorts");
        requireValue(ports.size() == 1 && whole(ports.get(0)) == 2049);
        for (String key : List.of("anonUid", "anonGid")) if (config.has(key)) requireValue(whole(config, key) >= 0 && whole(config, key) <= Integer.MAX_VALUE);
    }

    private static void nativeNfs(JsonObject source, JsonObject target, JsonObject share, JsonObject acl,
            JsonObject aclConfig, JsonObject binding) {
        JsonObject a = source.deepCopy(), b = target.deepCopy(); a.remove("exports"); b.remove("exports");
        requireValue(same(a, b));
        Map<String, JsonObject> old = exports(array(source, "exports")), latest = exports(array(target, "exports"));
        String shareUuid = text(share, "uuid");
        requireValue(!old.containsKey(shareUuid) && latest.size() == old.size() + 1 && latest.keySet().containsAll(old.keySet()));
        for (String id : old.keySet()) requireValue(same(old.get(id), latest.get(id)));
        JsonObject export = latest.get(shareUuid);
        requireValue(export != null && whole(export, "id") == whole(share, "id")
                && text(export, "name").equals(text(share, "name")) && "Ready".equals(text(export, "state"))
                && whole(export, "volumeId") == whole(share, "volume_id")
                && text(export, "filesystem").equals(text(share, "filesystem")));
        JsonObject config = object(export, "config"), savedConfig = parse(text(share, "config_json"));
        String volume = uuid(text(savedConfig, "attachedVolumeUuid"));
        requireValue(object(binding, "dataVolumes").has(volume) && "MOUNT_EXISTING".equals(text(savedConfig, "importMode"))
                && uuid(text(savedConfig, "filesystemUuid")).equals(uuid(text(config, "filesystemUuid")))
                && volume.equals(uuid(text(config, "attachedVolumeUuid"))));
        JsonObject expectedConfig = redact(savedConfig).getAsJsonObject();
        expectedConfig.add("protocolMode", target.get("protocolMode").deepCopy());
        requireValue(same(config, expectedConfig));
        String expectedPath = savedConfig.has("relativeSharePath") && !savedConfig.get("relativeSharePath").isJsonNull()
                ? "/export/" + text(share, "name") : text(share, "path");
        requireValue(expectedPath.equals(text(export, "path")));
        Set<String> fields = new HashSet<>(Set.of("id", "uuid", "name", "path", "volumeId", "filesystem", "state", "config", "acls"));
        if (share.has("quota_bytes") && !share.get("quota_bytes").isJsonNull()) {
            fields.add("quotaBytes"); requireValue(same(share.get("quota_bytes"), export.get("quotaBytes")));
        }
        requireValue(export.keySet().equals(fields));
        JsonArray rules = array(export, "acls"); requireValue(rules.size() == 1);
        JsonObject rule = rules.get(0).getAsJsonObject();
        requireValue(rule.keySet().equals(Set.of("id", "uuid", "principalType", "principal", "permission", "state", "config"))
                && whole(rule, "id") == whole(acl, "id") && text(rule, "uuid").equals(text(acl, "uuid"))
                && text(rule, "principalType").equals(text(acl, "principal_type"))
                && text(rule, "principal").equals(text(acl, "principal"))
                && text(rule, "permission").equals(text(acl, "permission")) && "Ready".equals(text(rule, "state"))
                && same(object(rule, "config"), aclConfig));
    }

    private static Map<String, JsonObject> exports(JsonArray values) {
        Map<String, JsonObject> result = new HashMap<>();
        for (JsonElement item : values) {
            requireValue(item.isJsonObject()); JsonObject row = item.getAsJsonObject();
            requireValue(result.put(uuid(text(row, "uuid")), row) == null);
        }
        return result;
    }

    private static void bindings(JsonObject value) {
        requireValue(value != null && value.keySet().equals(Set.of("vmId", "rootVolumeUuid", "rootVolumeId", "templateId", "vmUuid", "dataVolumes"))
                && whole(value, "vmId") > 0 && whole(value, "rootVolumeId") > 0 && whole(value, "templateId") > 0);
        uuid(text(value, "vmUuid")); uuid(text(value, "rootVolumeUuid"));
        JsonObject data = object(value, "dataVolumes"); requireValue(!data.entrySet().isEmpty() && data.size() <= 128);
        for (Map.Entry<String, JsonElement> entry : data.entrySet()) {
            uuid(entry.getKey()); JsonObject row = entry.getValue().getAsJsonObject();
            requireValue(row.keySet().equals(Set.of("sizeBytes", "poolId", "path", "deviceId", "provisioningType"))
                    && whole(row, "sizeBytes") > 0 && whole(row, "poolId") > 0 && whole(row, "deviceId") >= 0
                    && !text(row, "path").isEmpty() && Set.of("SPARSE", "FAT").contains(text(row, "provisioningType")));
        }
    }

    private static JsonObject frozen(JsonObject value, String instance, String operation, long revision) {
        requireValue(bool(value, "success") && bool(value, "generationSupported") && bool(value, "frozen"));
        JsonObject generation = object(value, "generation");
        requireValue(generation.keySet().equals(Set.of("instanceUuid", "operationUuid", "revision", "configurationSha256", "verifiedAt"))
                && instance.equals(uuid(text(generation, "instanceUuid"))) && whole(generation, "revision") > 0);
        uuid(text(generation, "operationUuid"));
        if (operation != null) requireValue(operation.equals(text(generation, "operationUuid")) && whole(generation, "revision") == revision);
        JsonElement at = generation.get("verifiedAt");
        requireValue(at != null && at.isJsonPrimitive() && at.getAsJsonPrimitive().isNumber()
                && Double.isFinite(at.getAsDouble()) && at.getAsDouble() > 0);
        String checksum = checksum(text(value, "configurationSha256"));
        requireValue(checksum.equals(text(generation, "configurationSha256"))
                && checksum.equals(digest(desired(object(value, "configurationDesiredState")))));
        return generation;
    }

    private static JsonObject desired(JsonObject value) {
        requireValue(value.keySet().equals(PATHS));
        for (Map.Entry<String, JsonElement> entry : value.entrySet()) requireValue(entry.getValue().isJsonNull() || entry.getValue().isJsonObject());
        requireValue(same(value, redact(value))); return value;
    }

    private static JsonElement redact(JsonElement value) {
        if (value.isJsonObject()) {
            JsonObject out = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
                String key = entry.getKey(), lower = key.toLowerCase(java.util.Locale.ROOT);
                if (Set.of("lastApply", "lastApplied", "observedAt", "generatedEpoch").contains(key)
                        || List.of("password", "secret", "dhchapkey", "dhchapctrlkey", "privatekey", "keytab", "capsule").stream().anyMatch(lower::contains)) continue;
                out.add(key, redact(entry.getValue()));
            }
            return out;
        }
        if (value.isJsonArray()) { JsonArray out = new JsonArray(); for (JsonElement item : value.getAsJsonArray()) out.add(redact(item)); return out; }
        return value.deepCopy();
    }

    private static JsonObject projection(JsonObject value) {
        JsonObject out = new JsonObject();
        for (String key : List.of("schemaVersion", "instanceId", "tables")) { requireValue(value.has(key)); out.add(key, value.get(key).deepCopy()); }
        return out;
    }

    private static Map<String, JsonObject> snapshot(JsonObject value, long instance) {
        requireValue(value.keySet().equals(Set.of("schemaVersion", "instanceId", "tables")) && whole(value, "schemaVersion") == 2 && whole(value, "instanceId") == instance);
        JsonArray tables = array(value, "tables"); requireValue(tables.size() == TABLES.size());
        Map<String, JsonObject> out = new HashMap<>();
        for (int index = 0; index < TABLES.size(); index++) {
            JsonObject entry = tables.get(index).getAsJsonObject(); String name = TABLES.get(index);
            requireValue(entry.keySet().equals(Set.of("table", "columns", "rows")) && name.equals(text(entry, "table")));
            Set<String> columns = new HashSet<>();
            for (JsonElement item : array(entry, "columns")) {
                JsonObject column = item.getAsJsonObject();
                requireValue(column.keySet().equals(Set.of("name", "type")) && text(column, "name").matches("[A-Za-z0-9_]+")
                        && columns.add(text(column, "name"))); whole(column, "type");
            }
            requireValue(columns.containsAll(Set.of("id", "uuid")));
            long previous = 0; Set<String> uuids = new HashSet<>();
            for (JsonElement item : array(entry, "rows")) {
                JsonObject row = item.getAsJsonObject();
                requireValue(row.keySet().equals(columns) && whole(row, "id") > previous && uuids.add(uuid(text(row, "uuid"))));
                previous = whole(row, "id");
                for (Map.Entry<String, JsonElement> field : row.entrySet()) requireValue(field.getValue().isJsonNull() || field.getValue().isJsonPrimitive());
                if (!"storage_access_rule".equals(name)) requireValue(whole(row, "instance_id") == instance);
            }
            out.put(name, entry);
        }
        StorageServiceDesiredSnapshot.validateSnapshot(instance, value.toString());
        return out;
    }

    private static Map<Long, JsonObject> rows(JsonObject entry) {
        Map<Long, JsonObject> out = new HashMap<>();
        for (JsonElement item : array(entry, "rows")) { JsonObject row = item.getAsJsonObject(); requireValue(out.put(whole(row, "id"), row) == null); }
        return out;
    }

    private static JsonObject object(JsonObject parent, String key) { JsonElement value = parent == null ? null : parent.get(key); requireValue(value != null && value.isJsonObject()); return value.getAsJsonObject(); }
    private static JsonArray array(JsonObject parent, String key) { JsonElement value = parent == null ? null : parent.get(key); requireValue(value != null && value.isJsonArray()); return value.getAsJsonArray(); }
    private static String text(JsonObject parent, String key) { JsonElement value = parent == null ? null : parent.get(key); requireValue(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()); return value.getAsString(); }
    private static boolean bool(JsonObject parent, String key) { JsonElement value = parent == null ? null : parent.get(key); requireValue(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()); return value.getAsBoolean(); }
    private static long whole(JsonObject parent, String key) { return whole(parent == null ? null : parent.get(key)); }
    private static long whole(JsonElement value) { requireValue(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber() && value.getAsString().matches("-?(0|[1-9][0-9]*)")); return Long.parseLong(value.getAsString()); }
    private static String uuid(String value) { requireValue(UUID.fromString(value).toString().equals(value)); return value; }
    private static String checksum(String value) { requireValue(value.matches("[a-f0-9]{64}")); return value; }
    private static void ipv4(String value) {
        String[] parts = value.split("\\.", -1); requireValue(parts.length == 4);
        for (String part : parts) requireValue(part.matches("0|[1-9][0-9]{0,2}") && Integer.parseInt(part) <= 255);
    }
    private static void requireValue(boolean value) { if (!value) throw new IllegalArgumentException("Verified pending NFS ACL target proof rejected"); }

    private static JsonObject parse(String raw) {
        requireValue(raw != null && raw.getBytes(StandardCharsets.UTF_8).length <= MAX);
        try (JsonReader reader = new JsonReader(new StringReader(raw))) {
            reader.setLenient(false); scan(reader, 0); requireValue(reader.peek() == JsonToken.END_DOCUMENT);
        } catch (IOException invalid) { throw new IllegalArgumentException("Malformed protected operation proof", invalid); }
        JsonElement parsed = JsonParser.parseString(raw); requireValue(parsed.isJsonObject()); return parsed.getAsJsonObject();
    }
    private static void scan(JsonReader reader, int depth) throws IOException {
        requireValue(depth <= 64); JsonToken type = reader.peek();
        if (type == JsonToken.BEGIN_OBJECT) {
            reader.beginObject(); Set<String> names = new HashSet<>();
            while (reader.hasNext()) { requireValue(names.add(reader.nextName())); scan(reader, depth + 1); } reader.endObject();
        } else if (type == JsonToken.BEGIN_ARRAY) {
            reader.beginArray(); while (reader.hasNext()) scan(reader, depth + 1); reader.endArray();
        } else if (type == JsonToken.STRING) reader.nextString();
        else if (type == JsonToken.NUMBER) validNumber(reader.nextString());
        else if (type == JsonToken.BOOLEAN) reader.nextBoolean();
        else if (type == JsonToken.NULL) reader.nextNull();
        else requireValue(false);
    }

    private static boolean same(JsonElement a, JsonElement b) { return a != null && b != null && canonical(a).equals(canonical(b)); }
    private static String digest(JsonElement value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(canonical(value).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(); for (byte b : bytes) out.append(String.format("%02x", b & 255)); return out.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    /** Match protected native json.dumps(sort_keys=True,separators=(',',':'),ensure_ascii=True). */
    private static String canonical(JsonElement value) {
        if (value.isJsonNull()) return "null";
        if (value.isJsonArray()) { List<String> parts = new ArrayList<>(); for (JsonElement item : value.getAsJsonArray()) parts.add(canonical(item)); return "[" + String.join(",", parts) + "]"; }
        if (value.isJsonObject()) {
            List<String> keys = new ArrayList<>(value.getAsJsonObject().keySet()); keys.sort((a, b) -> {
                int[] x = a.codePoints().toArray(), y = b.codePoints().toArray();
                for (int i = 0; i < Math.min(x.length, y.length); i++) if (x[i] != y[i]) return Integer.compare(x[i], y[i]);
                return Integer.compare(x.length, y.length);
            });
            List<String> parts = new ArrayList<>(); for (String key : keys) parts.add(quoted(key) + ":" + canonical(value.getAsJsonObject().get(key)));
            return "{" + String.join(",", parts) + "}";
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (primitive.isString()) return quoted(primitive.getAsString());
        if (primitive.isBoolean()) return primitive.getAsBoolean() ? "true" : "false";
        String number = primitive.getAsString(); validNumber(number); return number;
    }
    private static void validNumber(String value) { requireValue(value.length() <= 512 && NUMBER.matcher(value).matches() && Double.isFinite(Double.parseDouble(value))); }
    private static String quoted(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') out.append("\\\""); else if (c == '\\') out.append("\\\\");
            else if (c == '\b') out.append("\\b"); else if (c == '\f') out.append("\\f");
            else if (c == '\n') out.append("\\n"); else if (c == '\r') out.append("\\r"); else if (c == '\t') out.append("\\t");
            else if (c < 32 || c >= 127) out.append(String.format("\\u%04x", (int) c)); else out.append(c);
        }
        return out.append('"').toString();
    }
}
