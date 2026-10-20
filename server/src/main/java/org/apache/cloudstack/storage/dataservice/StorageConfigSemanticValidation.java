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

import java.util.Map;
import java.util.Set;
import com.cloud.exception.InvalidParameterValueException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Pure domain validation before any restored database, directory or identity is changed. */
public final class StorageConfigSemanticValidation {
    private StorageConfigSemanticValidation() { }
    private static String text(JsonObject value, String key) {
        return value.has(key) && !value.get(key).isJsonNull() ? value.get(key).getAsString() : null;
    }
    public static void compatibility(JsonObject manifest, String version) {
        if (!manifest.has("configurationCompatibility") || !manifest.get("configurationCompatibility").isJsonObject()) {
            throw new InvalidParameterValueException("Configuration compatibility range is missing");
        }
        JsonObject range = manifest.getAsJsonObject("configurationCompatibility");
        String minimum = text(range, "minimumManagerVersion");String maximum = text(range, "maximumManagerVersionExclusive");
        if (minimum == null || maximum == null || version == null || version.equals("unknown")) throw new InvalidParameterValueException("Configuration manager version cannot be verified");
        int[] actual = versionNumbers(version);int[] lower = versionNumbers(minimum);int[] upper = versionNumbers(maximum);
        if (compare(actual, lower) < 0 || compare(actual, upper) >= 0 || compare(lower, upper) >= 0) {
            throw new InvalidParameterValueException("Configuration bundle is incompatible with the management version");
        }
    }
    private static int[] versionNumbers(String value) {
        java.util.regex.Matcher match = java.util.regex.Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)(?:[.\\-].*)?$").matcher(value);
        if (!match.matches()) throw new InvalidParameterValueException("Invalid configuration compatibility version");
        try { return new int[] {Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2)), Integer.parseInt(match.group(3))}; }
        catch (NumberFormatException invalid) { throw new InvalidParameterValueException("Invalid configuration compatibility version"); }
    }
    private static int compare(int[] left, int[] right) {
        for (int i = 0; i < left.length; i++) if (left[i] != right[i]) return Integer.compare(left[i], right[i]);return 0;
    }
    public static void validate(Map<String, byte[]> archive, StorageServiceManagerImpl manager) {validate(archive,manager,false);}
    public static void validate(Map<String, byte[]> archive, StorageServiceManagerImpl manager,boolean authenticatedAdSource) {
        Map<String, JsonArray> collections = StorageConfigRestorePlan.resources(archive);
        for (Map.Entry<String, JsonArray> collection : collections.entrySet()) for (JsonElement item : collection.getValue()) {
            JsonObject row = item.getAsJsonObject();JsonObject config = row.has("config") ? row.getAsJsonObject("config") : new JsonObject();
            if (config.has("recursive") && config.get("recursive").getAsBoolean()
                    || config.has("recursivePermission") && config.get("recursivePermission").getAsBoolean()) {
                throw new InvalidParameterValueException("Configuration restore does not recursively change existing data ownership or permissions");
            }
            if ("protocols".equals(collection.getKey())) {
                StorageServiceInstance.Protocol protocol = manager.parseProtocol(text(row, "protocol"));
                manager.normalizeStorageServiceProtocolPort(protocol, row.has("port") && !row.get("port").isJsonNull() ? row.get("port").getAsInt() : null);
            } else if ("posix-directory-policies".equals(collection.getKey())) {
                PosixDirectoryPolicy.relativePath(text(row, "relative_path"));
                if (config.has("directoryMode")) PosixDirectoryPolicy.directoryMode(text(config, "directoryMode"));
                for (String key : new String[] {"ownerUid", "ownerGid"}) if (config.has(key)) PosixDirectoryPolicy.numericId(config.get(key).getAsLong());
                for (String key : new String[] {"accessEntries", "defaultEntries"}) if (config.has(key)) PosixDirectoryPolicy.aclEntries(config.getAsJsonArray(key));
                if (config.has("applyOwner") && config.get("applyOwner").getAsBoolean() && (!config.has("ownerUid") || !config.has("ownerGid"))) {
                    throw new InvalidParameterValueException("Explicit POSIX owner application requires UID and GID");
                }
            } else if ("file-shares".equals(collection.getKey())) {
                String protocol = text(row, "protocol");
                manager.validateFileShareFilesystem(text(row, "filesystem"), "MOUNT_EXISTING");
                if (config.has("relativeSharePath")) PosixDirectoryPolicy.relativePath(text(config, "relativeSharePath"));
                if ("NFS".equals(protocol)) manager.validateNfsExportName(text(row, "name"));
                else if ("SMB".equals(protocol)) {
                    manager.validateSmbShareName(text(row, "name"));
                    SmbCreationPolicy.merge(config, null, null, null, null, null, null);
                    SmbOwnershipPolicy.inheritance(config, null, null);SmbOwnershipPolicy.forced(config, null, null, null);
                } else throw new InvalidParameterValueException("Invalid file-share protocol");
                if (row.has("quota_bytes") && !row.get("quota_bytes").isJsonNull() && row.get("quota_bytes").getAsLong() < 0) throw new InvalidParameterValueException("Negative share quota");
            } else if ("identity-domain".equals(collection.getKey())) {
                if (text(row, "domain_name") != null && !text(row, "domain_name").isBlank()) {
                    if(!authenticatedAdSource)throw new InvalidParameterValueException("SMB AD configuration restore requires an authenticated managed encrypted source");
                    JsonObject identity=config.has("identityReceipt")&&config.get("identityReceipt").isJsonObject()?config.getAsJsonObject("identityReceipt"):null;
                    if(identity==null||!"JOINED".equals(text(identity,"joinState"))||!text(row,"domain_name").equals(text(identity,"domain"))||!identity.has("idmapPolicy"))throw new InvalidParameterValueException("AD source declaration lacks its original joined identity");
                    StorageAdLifecycleRequest.validateIdmap(identity.getAsJsonObject("idmapPolicy"));
                }
            } else if ("access-rules".equals(collection.getKey())) {
                String principal = text(row, "principal");String type = text(row, "principal_type");
                if (principal == null || principal.isBlank() || principal.length() > 255 || principal.chars().anyMatch(Character::isISOControl)) throw new InvalidParameterValueException("Invalid access principal");
                if (Set.of("AD_USER", "AD_GROUP").contains(type)&&!authenticatedAdSource) throw new InvalidParameterValueException("SMB AD configuration restore requires an authenticated managed encrypted source");
                StorageServiceInstance.PrincipalType.valueOf(type);
                StorageServiceInstance.Permission.valueOf(text(row, "permission"));
            } else if ("block-targets".equals(collection.getKey())) {
                String protocol = text(row, "protocol");String name = text(row, "target_name");
                if (!Set.of("ISCSI", "NVME_OF").contains(protocol) || name == null || name.isBlank() || name.length() > 223
                        || name.chars().anyMatch(Character::isWhitespace) || name.chars().anyMatch(Character::isISOControl)) throw new InvalidParameterValueException("Invalid block target identity");
                if ("ISCSI".equals(protocol) && config.has("backstoreType")) manager.validateIscsiBlockOnlyBackstore(text(config, "backstoreType"));
                if ("NVME_OF".equals(protocol) && !Set.of("subsystem", "namespace").contains(text(config, "type"))) throw new InvalidParameterValueException("Invalid NVMe resource type");
            }
        }
    }
}
