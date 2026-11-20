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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongFunction;

/** Typed, portable projection of the configuration writer's view. */
final class StorageConfigSnapshot {
    static final String DEFAULT_NFS_WILDCARD = "DEFAULT_NFS_WILDCARD";
    private static final Set<String> PROTOCOLS = Set.of("NFS", "SMB", "ISCSI", "NVME_OF");
    private static final Set<String> OBSERVATIONS = Set.of("OBSERVED", "UNAVAILABLE", "STALE", "NOT_CONFIGURED");
    private static final Set<String> OBSERVATION_FIELDS = Set.of("state", "observedAt", "bootId", "resources", "bindings");
    private static final Set<String> RUNTIME_FIELDS = Set.of("resource", "state", "address", "port", "volumeUuid",
            "filesystemUuid", "filesystem", "mountSource", "fsRoot", "device", "serial", "bytes");

    private static final Map<String, Set<String>> COLUMNS = Map.of(
            "listeners", Set.of("uuid", "protocol", "enabled", "listenIp", "port", "state"),
            "files", Set.of("uuid", "protocol", "name", "path", "filesystem", "quotaBytes", "state"),
            "blocks", Set.of("uuid", "protocol", "targetName", "lunOrNamespace", "state"),
            "rules", Set.of("uuid", "resourceType", "principalType", "principal", "permission", "state"),
            "directories", Set.of("uuid", "canonicalPath", "ownerUid", "ownerGid", "mode"));
    private static final Map<String, String> TABLES = Map.of(
            "listeners", "StorageServiceProtocolVO", "files", "StorageFileShareVO",
            "blocks", "StorageBlockTargetVO", "rules", "StorageAccessRuleVO", "directories", "StorageDirectoryPolicyVO");

    private static final Set<String> BOOLEAN = Set.of("enabled", "readOnly", "rootSquash", "allSquash", "sync", "secure",
            "browseable", "guestOk", "crossProtocol", "inheritOwner", "inheritPermissions", "allowAnyHost",
            "chapEnabled", "mutualChapEnabled", "dhChapEnabled", "dhChapCtrlEnabled", "localAccount");
    private static final Set<String> NUMBER = Set.of("port", "quotaBytes", "ownerUid", "ownerGid", "anonUid", "anonGid",
            "lunSizeBytes", "namespaceSizeBytes", "version", "uid", "gid", "bytes", "pathBindingVersion");
    private static final Map<String, Set<String>> CONFIG = Map.of(
            "listeners", Set.of("protocolMode", "listenerGroups", "serviceIps", "nfs4IdentityMappingMode", "role",
                    "endpointType", "listenMode", "transport", "engine"),
            "files", Set.of("readOnly", "rootSquash", "allSquash", "sync", "secure", "anonUid", "anonGid", "ownerUid",
                    "ownerGid", "mode", "protocolMode", "listenerGroupPorts", "endpointMode", "listenIps", "browseable",
                    "guestOk", "crossProtocol", "directoryMode", "inheritOwner", "inheritPermissions",
                    "createMask", "directoryMask", "forceCreateMode", "forceDirectoryMode", "fileIdentity", "clientAccess",
                    "relativeSharePath", "pathBindingVersion", "directoryPolicyUuid", "posixPolicy"),
            "blocks", Set.of("type", "backstoreType", "lunSizeBytes", "namespaceSizeBytes", "listenerGroupPorts",
                    "endpointMode", "listenIps", "allowAnyHost", "engine", "transport", "namespaceUuid"),
            "rules", Set.of("localAccount", "identityProvider", "workgroup", "chapEnabled", "chapUsername",
                    "mutualChapEnabled", "mutualChapUsername", "dhChapEnabled", "dhChapCtrlEnabled",
                    "readOnly", "rootSquash", "allSquash", "sync", "secure", "anonUid", "anonGid"),
            "directories", Set.of());
    private static final Set<String> TRANSIENT = Set.of("lastInspection", "lastResize", "lastError", "lastValidation",
            "volumeMountPath", "backingPath", "attachedVolumeName", "attachedVolumeUuid", "volumeMode", "importMode",
            "createDirectory", "recursivePermission", "lastRecursive", "filesystemUuid", "passwordSupplied",
            "engineState", "nfs4IdentityPolicyRevision", "directoryPolicyId", "pathIdentity");
    private static final Map<String, Set<String>> REQUIRED = Map.of(
            "listeners", Set.of("uuid", "protocol", "enabled", "state"),
            "files", Set.of("uuid", "protocol", "name", "path", "state"),
            "blocks", Set.of("uuid", "protocol", "targetName", "state"),
            "rules", Set.of("uuid", "resourceType", "principalType", "principal", "permission", "state"),
            "directories", Set.of("uuid", "volume", "canonicalPath", "ownerUid", "ownerGid", "mode"));
    private static final Map<String, Set<String>> OBJECTS = Map.of(
            "listenerGroups", Set.of("port", "listenIp", "listenIps", "role", "state"),
            "fileIdentity", Set.of("version", "mode", "uid", "gid"),
            "clientAccess", Set.of("version", "cidrs"));

    private StorageConfigSnapshot() { }

    static JsonObject project(JsonObject snapshot, LongFunction<JsonObject> findVolume) {
        JsonObject result = new JsonObject();
        JsonArray omitted = new JsonArray();
        JsonObject missing = new JsonObject();
        Map<Long, JsonObject> volumes = new LinkedHashMap<>();
        Set<Long> nfsExports = new HashSet<>();
        Map<String, Map<Long, String>> parent = new LinkedHashMap<>();
        for (String table : List.of("listeners", "directories", "files", "blocks", "rules")) {
            Map<Long, String> identifiers = new LinkedHashMap<>();
            parent.put(table, identifiers);
            JsonArray rows = new JsonArray();
            JsonArray raw = snapshot.has(TABLES.get(table)) ? snapshot.getAsJsonArray(TABLES.get(table)) : new JsonArray();
            for (JsonElement item : raw) {
                JsonObject source = item.getAsJsonObject();
                if (source.has("state") && Set.of("Creating", "Destroyed").contains(source.get("state").getAsString())) {
                    continue;
                }
                JsonObject row = fields(source, COLUMNS.get(table), false);
                long rowId = source.get("id").getAsLong();
                identifiers.put(rowId, row.get("uuid").getAsString());
                if ("files".equals(table) && "NFS".equals(string(row, "protocol"))) {
                    nfsExports.add(rowId);
                }
                boolean nfsRule = "rules".equals(table) && "FILE_SHARE".equals(string(row, "resourceType"))
                        && nfsExports.contains(source.get("resourceId").getAsLong());
                JsonObject config = StorageServiceConfigurationCoordinator.parsed(string(source, "configJson"));
                JsonObject projected = new JsonObject();
                config.entrySet().forEach(field -> {
                    String name = field.getKey();
                    if (CONFIG.get(table).contains(name)) {
                        projected.add(name, setting(name, field.getValue()));
                    } else if (!TRANSIENT.contains(name) && !sensitive(name)
                            && !(nfsRule && isNfsAclListenerMetadata(name, field.getValue()))) {
                        JsonObject omission = new JsonObject();
                        omission.add("resource", row.get("uuid"));
                        omission.addProperty("reason", "UNSUPPORTED_CONFIGURATION_FIELD");
                        if (!missing.has(row.get("uuid").getAsString())) {
                            omitted.add(omission);
                            missing.addProperty(row.get("uuid").getAsString(), true);
                        }
                    }
                });
                if ("listeners".equals(table) && "NFS".equals(string(row, "protocol"))
                        && unspecifiedListenerAddress(string(row, "listenIp"))) {
                    row.remove("listenIp");
                    row.addProperty("bindingMode", DEFAULT_NFS_WILDCARD);
                }
                row.add("settings", projected);
                if (source.has("volumeId") && !source.get("volumeId").isJsonNull()) {
                    long volumeId = source.get("volumeId").getAsLong();
                    JsonObject volume = volumes.computeIfAbsent(volumeId, findVolume::apply);
                    if (volume == null) {
                        throw StorageConfigArchiveV1.invalid("BACKING_VOLUME_NOT_FOUND");
                    }
                    row.add("volume", volume.get("uuid"));
                }
                if ("files".equals(table) && row.has("volume")) {
                    try {
                        projectFilePath(row, config, projected);
                    } catch (com.cloud.exception.InvalidParameterValueException invalidPath) {
                        projected.remove("relativeSharePath");
                        projected.remove("pathBindingVersion");
                        if (!missing.has(row.get("uuid").getAsString())) {
                            JsonObject omission = new JsonObject();
                            omission.add("resource", row.get("uuid"));
                            omission.addProperty("reason", "FILE_PATH_UNCONFIRMED");
                            omitted.add(omission);
                            missing.addProperty(row.get("uuid").getAsString(), true);
                        }
                    }
                }
                if ("files".equals(table) && config.has("directoryPolicyId")) {
                    String id = parent.get("directories").get(config.get("directoryPolicyId").getAsLong());
                    if (id != null) {
                        projected.addProperty("directoryPolicyUuid", id);
                    }
                }
                if ("rules".equals(table)) {
                    Map<Long, String> resources = parent.get("FILE_SHARE".equals(string(source, "resourceType")) ? "files" : "blocks");
                    String resource = resources.get(source.get("resourceId").getAsLong());
                    if (resource == null) {
                        throw StorageConfigArchiveV1.invalid("CONFIGURATION_PARENT_MISSING");
                    }
                    row.addProperty("resource", resource);
                    JsonArray slots = new JsonArray();
                    if ("LOCAL_USER".equals(string(source, "principalType"))) {
                        slots.add("SMB_LOCAL");
                    }
                    Map<String, String> slotTypes = new java.util.TreeMap<>(Map.of("chapEnabled", "ISCSI_CHAP",
                            "mutualChapEnabled", "ISCSI_MUTUAL_CHAP", "dhChapEnabled", "NVME_DH_CHAP",
                            "dhChapCtrlEnabled", "NVME_DH_CHAP_CTRL"));
                    slotTypes.forEach((flag, slot) -> {
                        if (projected.has(flag) && projected.get(flag).getAsBoolean()) {
                            slots.add(slot);
                        }
                    });
                    row.add("credentialSlots", slots);
                    if (string(source, "principalType").startsWith("AD_")) {
                        JsonObject omission = new JsonObject();
                        omission.add("resource", row.get("uuid"));
                        omission.addProperty("reason", "AD_MEMBERSHIP_NOT_PORTABLE");
                        omitted.add(omission);
                    }
                }
                rows.add(row);
            }
            result.add(table, rows);
        }
        JsonArray volumeRows = new JsonArray();
        volumes.values().forEach(volumeRows::add);
        result.add("volumes", volumeRows);
        result.add("omitted", omitted);
        validateDesired(result);
        return result;
    }

    static void validate(StorageConfigArchiveV1.Bundle bundle) {
        JsonObject manifest = bundle.manifest;
        if (!manifest.keySet().equals(Set.of("schemaVersion", "entries", "sourceInstance", "sourceSharedFs", "revision",
                "createdAt", "coverage")) || !isUuid(string(manifest, "sourceInstance"))
                || !isUuid(string(manifest, "sourceSharedFs")) || !isInteger(manifest.get("revision"))
                || !isInteger(manifest.get("createdAt"))) {
            throw StorageConfigArchiveV1.invalid("INVALID_ARCHIVE_METADATA");
        }
        validateDesired(bundle.desired);
        JsonObject coverage = object(manifest.get("coverage"));
        if (!coverage.keySet().equals(PROTOCOLS) || !bundle.effective.keySet().equals(PROTOCOLS)) {
            throw StorageConfigArchiveV1.invalid("INVALID_PROTOCOL_COVERAGE");
        }
        for (String protocol : PROTOCOLS) {
            JsonObject observed = object(bundle.effective.get(protocol));
            String state = string(observed, "state");
            if (!OBSERVATION_FIELDS.containsAll(observed.keySet()) || !OBSERVATIONS.contains(state)
                    || !state.equals(string(coverage, protocol))) {
                throw StorageConfigArchiveV1.invalid("INVALID_PROTOCOL_COVERAGE");
            }
            if (observed.has("bootId") && !isUuid(string(observed, "bootId"))) {
                throw StorageConfigArchiveV1.invalid("INVALID_OBSERVATION_BOOT");
            }
            if (observed.has("observedAt")) {
                scalar("observedAt", observed.get("observedAt"));
            }
            if (observed.has("bindings")) {
                if (!"NFS".equals(protocol) || !"OBSERVED".equals(state)) {
                    throw StorageConfigArchiveV1.invalid("INVALID_PROTOCOL_BINDINGS");
                }
                StorageConfigNfsBinding.bindings(observed.get("bindings"));
            }
            if (observed.has("resources")) {
                for (JsonElement value : array(observed.get("resources"))) {
                    fields(object(value), RUNTIME_FIELDS, true);
                }
            }
        }
    }

    static void validateDesired(JsonObject desired) {
        if (!desired.keySet().equals(Set.of("listeners", "files", "blocks", "rules", "directories", "volumes", "omitted"))) {
            throw StorageConfigArchiveV1.invalid("INVALID_DESIRED_FIELDS");
        }
        Set<String> ids = new HashSet<>();
        Set<String> volumes = new HashSet<>();
        Set<String> parents = new HashSet<>();
        Set<String> directories = new HashSet<>();
        for (JsonElement entry : array(desired.get("volumes"))) {
            JsonObject volume = object(entry);
            fields(volume, Set.of("uuid", "bytes", "provisioning", "filesystem", "filesystemUuid", "serial", "mountSource", "fsRoot"), true);
            String id = string(volume, "uuid");
            if (!isUuid(id) || !volumes.add(id) || !isInteger(volume.get("bytes")) || volume.get("bytes").getAsLong() == 0
                    || !Set.of("SPARSE", "FAT").contains(string(volume, "provisioning"))) {
                throw StorageConfigArchiveV1.invalid("INVALID_BACKING_VOLUME");
            }
        }
        for (String table : List.of("listeners", "directories", "files", "blocks", "rules")) {
            Set<String> allowed = new HashSet<>(COLUMNS.get(table));
            allowed.addAll(Set.of("settings", "volume", "resource", "credentialSlots"));
            if ("listeners".equals(table)) {
                allowed.add("bindingMode");
            }
            for (JsonElement entry : array(desired.get(table))) {
                JsonObject row = object(entry);
                if (!allowed.containsAll(row.keySet()) || !row.keySet().containsAll(REQUIRED.get(table))
                        || row.entrySet().stream().anyMatch(value -> value.getValue().isJsonNull())) {
                    throw StorageConfigArchiveV1.invalid("INVALID_RESOURCE_FIELDS");
                }
                fields(row, COLUMNS.get(table), false);
                if (row.has("bindingMode") && (!DEFAULT_NFS_WILDCARD.equals(string(row, "bindingMode"))
                        || !"NFS".equals(string(row, "protocol")) || row.has("listenIp"))) {
                    throw StorageConfigArchiveV1.invalid("INVALID_LISTENER_BINDING");
                }
                String id = string(row, "uuid");
                if (!isUuid(id) || !ids.add(id) || row.has("protocol") && !PROTOCOLS.contains(string(row, "protocol"))
                        || row.has("volume") && !volumes.contains(string(row, "volume"))) {
                    throw StorageConfigArchiveV1.invalid("INVALID_RESOURCE_REFERENCE");
                }
                if ("files".equals(table) || "blocks".equals(table)) {
                    if (("files".equals(table)) != Set.of("NFS", "SMB").contains(string(row, "protocol"))) {
                        throw StorageConfigArchiveV1.invalid("INVALID_RESOURCE_PROTOCOL");
                    }
                    parents.add(id);
                }
                if ("directories".equals(table)) {
                    directories.add(id);
                }
                if ("rules".equals(table)) {
                    if (!parents.contains(string(row, "resource"))) {
                        throw StorageConfigArchiveV1.invalid("INVALID_ACCESS_PARENT");
                    }
                    Set<String> slots = new HashSet<>();
                    for (JsonElement slot : array(row.get("credentialSlots"))) {
                        String name = scalar("slot", slot).getAsString();
                        if (!Set.of("SMB_LOCAL", "ISCSI_CHAP", "ISCSI_MUTUAL_CHAP", "NVME_DH_CHAP", "NVME_DH_CHAP_CTRL").contains(name)
                                || !slots.add(name)) {
                            throw StorageConfigArchiveV1.invalid("INVALID_CREDENTIAL_SLOT");
                        }
                    }
                } else if (row.has("resource") || row.has("credentialSlots")) {
                    throw StorageConfigArchiveV1.invalid("INVALID_RESOURCE_FIELDS");
                }
                JsonObject settings = object(row.get("settings"));
                for (Map.Entry<String, JsonElement> item : settings.entrySet()) {
                    if (!CONFIG.get(table).contains(item.getKey())) {
                        throw StorageConfigArchiveV1.invalid("INVALID_PROTOCOL_SETTING");
                    }
                    setting(item.getKey(), item.getValue());
                }
                if ("files".equals(table)) {
                    validateFilePath(row, settings);
                }
                if (settings.has("directoryPolicyUuid") && !directories.contains(string(settings, "directoryPolicyUuid"))) {
                    throw StorageConfigArchiveV1.invalid("INVALID_DIRECTORY_REFERENCE");
                }
            }
        }
        for (JsonElement entry : array(desired.get("omitted"))) {
            JsonObject item = object(entry);
            if (!item.keySet().equals(Set.of("resource", "reason")) || !ids.contains(string(item, "resource"))
                    || !Set.of("UNSUPPORTED_CONFIGURATION_FIELD", "AD_MEMBERSHIP_NOT_PORTABLE", "FILE_PATH_UNCONFIRMED").contains(string(item, "reason"))) {
                throw StorageConfigArchiveV1.invalid("INVALID_OMISSION");
            }
        }
    }

    private static boolean isNfsAclListenerMetadata(String name, JsonElement value) {
        // Older ACL writers shared the export config builder. Only the parent export owns the listener.
        switch (name) {
        case "protocolMode":
            return value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                    && Set.of("V4_ONLY", "V3V4_DUAL").contains(value.getAsString());
        case "endpointMode":
            return value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                    && Set.of("ALL", "SELECTED", "LISTENER_GROUP").contains(value.getAsString());
        case "listenerGroupPorts":
            return value.isJsonArray() && value.getAsJsonArray().size() > 0
                    && value.getAsJsonArray().asList().stream().allMatch(port ->
                            isInteger(port) && port.getAsLong() >= 1 && port.getAsLong() <= 65535);
        default:
            return false;
        }
    }

    private static void projectFilePath(JsonObject row, JsonObject config, JsonObject projected) {
        String root = "/srv/ablestack-storage/volumes/" + string(row, "volume");
        String supplied = string(config, "relativeSharePath");
        String backing = string(config, "backingPath");
        boolean protectedPath = config.has("pathBindingVersion") || config.has("pathIdentity");
        if (protectedPath && (!isInteger(config.get("pathBindingVersion")) || config.get("pathBindingVersion").getAsLong() != 1
                || supplied.isEmpty())) {
            throw StorageConfigArchiveV1.invalid("FILE_PATH_UNCONFIRMED");
        }
        if (backing.isEmpty() && !protectedPath && config.has("relativeSharePath") && !supplied.isEmpty()
                && !config.has("volumeMountPath")) {
            // Some older rows already persist a relative path without a redundant absolute path.
            projected.addProperty("relativeSharePath", StorageFileSharePaths.normalize(supplied));
            return;
        }
        if (!root.equals(string(config, "volumeMountPath"))
                || !(backing.equals(root) || backing.startsWith(root + "/"))) {
            throw StorageConfigArchiveV1.invalid("FILE_PATH_UNCONFIRMED");
        }
        String relative = backing.equals(root) ? "" : StorageFileSharePaths.normalize(backing.substring(root.length() + 1));
        if (config.has("relativeSharePath") && !relative.equals(supplied)) {
            throw StorageConfigArchiveV1.invalid("FILE_PATH_UNCONFIRMED");
        }
        projected.addProperty("relativeSharePath", relative);
    }

    private static void validateFilePath(JsonObject row, JsonObject settings) {
        if (settings.has("relativeSharePath")) {
            String relative = string(settings, "relativeSharePath");
            if (!row.has("volume")) {
                throw StorageConfigArchiveV1.invalid("FILE_PATH_VOLUME_REQUIRED");
            }
            if (!relative.isEmpty()) {
                StorageFileSharePaths.normalize(relative);
            }
        }
        if (settings.has("pathBindingVersion") && (!isInteger(settings.get("pathBindingVersion"))
                || settings.get("pathBindingVersion").getAsLong() != 1 || string(settings, "relativeSharePath").isEmpty())) {
            throw StorageConfigArchiveV1.invalid("INVALID_FILE_PATH_PROTECTION");
        }
    }

    private static JsonElement setting(String key, JsonElement value) {
        if (Set.of("listenIps", "serviceIps", "cidrs", "listenerGroupPorts").contains(key)) {
            JsonArray result = new JsonArray();
            for (JsonElement item : array(value)) {
                result.add(scalar("listenerGroupPorts".equals(key) ? "port" : key, item));
            }
            return result;
        }
        if ("listenerGroups".equals(key)) {
            JsonArray result = new JsonArray();
            for (JsonElement item : array(value)) {
                JsonObject group = object(item);
                if (!OBJECTS.get(key).containsAll(group.keySet()) || !group.has("port")) {
                    throw StorageConfigArchiveV1.invalid("INVALID_LISTENER_GROUP");
                }
                if (group.has("state")) {
                    try {
                        StorageServiceInstance.ResourceState.valueOf(string(group, "state"));
                    } catch (IllegalArgumentException error) {
                        throw StorageConfigArchiveV1.invalid("INVALID_LISTENER_GROUP_STATE");
                    }
                }
                JsonObject projected = new JsonObject();
                group.entrySet().forEach(field -> projected.add(field.getKey(), setting(field.getKey(), field.getValue())));
                result.add(projected);
            }
            return result;
        }
        if (OBJECTS.containsKey(key)) {
            JsonObject object = object(value);
            if (!OBJECTS.get(key).containsAll(object.keySet())) {
                throw StorageConfigArchiveV1.invalid("INVALID_POLICY_FIELDS");
            }
            JsonObject result = new JsonObject();
            object.entrySet().forEach(field -> result.add(field.getKey(), setting(field.getKey(), field.getValue())));
            return result;
        }
        return scalar(key, value);
    }

    private static JsonObject fields(JsonObject from, Set<String> keys, boolean strict) {
        JsonObject result = new JsonObject();
        for (Map.Entry<String, JsonElement> field : from.entrySet()) {
            if (keys.contains(field.getKey())) {
                if (!field.getValue().isJsonNull()) {
                    result.add(field.getKey(), scalar(field.getKey(), field.getValue()));
                }
            } else if (strict) {
                throw StorageConfigArchiveV1.invalid("INVALID_PORTABLE_FIELD");
            }
        }
        return result;
    }

    private static JsonElement scalar(String key, JsonElement value) {
        if (value == null || !value.isJsonPrimitive()) {
            throw StorageConfigArchiveV1.invalid("INVALID_PORTABLE_VALUE");
        }
        JsonPrimitive scalar = value.getAsJsonPrimitive();
        if (BOOLEAN.contains(key) ? !scalar.isBoolean() : NUMBER.contains(key) ? !isInteger(value) : !scalar.isString()) {
            throw StorageConfigArchiveV1.invalid("INVALID_PORTABLE_VALUE");
        }
        if (scalar.isString()) {
            String text = scalar.getAsString();
            if (text.length() > 1024 || text.chars().anyMatch(Character::isISOControl)
                    || text.startsWith("DHHC-1:") || text.contains("PRIVATE KEY-----")) {
                throw StorageConfigArchiveV1.invalid("INVALID_PORTABLE_TEXT");
            }
        }
        if ("port".equals(key) && (scalar.getAsLong() < 1 || scalar.getAsLong() > 65535)) {
            throw StorageConfigArchiveV1.invalid("INVALID_LISTENER_PORT");
        }
        return scalar;
    }

    static boolean isInteger(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            return false;
        }
        try {
            return value.getAsBigDecimal().longValueExact() >= 0;
        } catch (ArithmeticException error) {
            return false;
        }
    }

    static JsonObject object(JsonElement value) {
        if (value == null || !value.isJsonObject()) {
            throw StorageConfigArchiveV1.invalid("INVALID_PORTABLE_OBJECT");
        }
        return value.getAsJsonObject();
    }

    static JsonArray array(JsonElement value) {
        if (value == null || !value.isJsonArray()) {
            throw StorageConfigArchiveV1.invalid("INVALID_PORTABLE_ARRAY");
        }
        return value.getAsJsonArray();
    }

    static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : "";
    }

    static boolean unspecifiedListenerAddress(String address) {
        return address.isBlank() || "0.0.0.0".equals(address) || "::".equals(address);
    }

    static boolean isUuid(String value) {
        try {
            return value.length() == 36 && UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    private static boolean sensitive(String value) {
        String key = value.toLowerCase(java.util.Locale.ROOT);
        return key.contains("secret") || key.contains("password") || key.contains("chapkey") || key.contains("chapctrlkey")
                || key.contains("keytab") || key.contains("hash") || key.contains("privatekey");
    }
}
