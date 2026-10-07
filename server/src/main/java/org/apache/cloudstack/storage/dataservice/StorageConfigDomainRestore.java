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

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.EnableStorageServiceProtocolCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStoragePosixDirectoryPolicyCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.CreateStoragePosixDirectoryPolicyCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageNfsExportCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageNfsExportCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageSmbShareCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageSmbShareCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageIscsiTargetCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageIscsiTargetCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageNvmeOfNamespaceCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageNvmeOfNamespaceCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageNvmeOfSubsystemCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageNvmeOfSubsystemCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageNfsAclCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageNfsAclCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageSmbNetworkAclCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageSmbNetworkAclCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageSmbAclCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageSmbAclCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageIscsiAclCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageIscsiAclCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageNvmeOfHostAclCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageNvmeOfHostAclCmd;
import com.cloud.exception.InvalidParameterValueException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Applies reviewed resources through current API validators; never restores uploaded SQL rows. */
public final class StorageConfigDomainRestore {
    private final StorageServiceManagerImpl manager;
    public StorageConfigDomainRestore(StorageServiceManagerImpl manager) { this.manager = manager; }
    private static String text(JsonObject value, String key) { return value.has(key) && !value.get(key).isJsonNull() ? value.get(key).getAsString() : null; }
    private static final class Command {
        final Class<? extends BaseCmd> type;final String method;
        Command(Class<? extends BaseCmd> type, String method) { this.type = type;this.method = method; }
    }
    private Command command(String kind, JsonObject row, boolean update, Map<String, JsonObject> owners) {
        String protocol = text(row, "protocol");
        if ("protocols".equals(kind)) return new Command(EnableStorageServiceProtocolCmd.class, "enableStorageServiceProtocol");
        if ("posix-directory-policies".equals(kind)) return new Command(update ? UpdateStoragePosixDirectoryPolicyCmd.class : CreateStoragePosixDirectoryPolicyCmd.class, "executeStoragePosixDirectoryPolicy");
        if ("file-shares".equals(kind)) {
            if ("NFS".equals(protocol)) return new Command(update ? UpdateStorageNfsExportCmd.class : CreateStorageNfsExportCmd.class, update ? "updateStorageNfsExport" : "createStorageNfsExport");
            if ("SMB".equals(protocol)) return new Command(update ? UpdateStorageSmbShareCmd.class : CreateStorageSmbShareCmd.class, update ? "updateStorageSmbShare" : "createStorageSmbShare");
        }
        if ("block-targets".equals(kind)) {
            if ("ISCSI".equals(protocol)) return new Command(update ? UpdateStorageIscsiTargetCmd.class : CreateStorageIscsiTargetCmd.class, update ? "updateStorageIscsiTarget" : "createStorageIscsiTarget");
            if ("NVME_OF".equals(protocol)) {
                String resource = row.has("config") ? text(row.getAsJsonObject("config"), "type") : null;
                if ("namespace".equals(resource)) return new Command(update ? UpdateStorageNvmeOfNamespaceCmd.class : CreateStorageNvmeOfNamespaceCmd.class, update ? "updateStorageNvmeOfNamespace" : "createStorageNvmeOfNamespace");
                return new Command(update ? UpdateStorageNvmeOfSubsystemCmd.class : CreateStorageNvmeOfSubsystemCmd.class, update ? "updateStorageNvmeOfSubsystem" : "createStorageNvmeOfSubsystem");
            }
        }
        if ("access-rules".equals(kind)) {
            JsonObject owner = owners.get(text(row, "resourceUuid"));if (owner == null) throw new InvalidParameterValueException("Configuration ACL owner is unavailable");
            String ownerProtocol = text(owner, "protocol");String type = text(row, "principal_type");
            if ("NFS".equals(ownerProtocol)) return new Command(update ? UpdateStorageNfsAclCmd.class : CreateStorageNfsAclCmd.class, update ? "updateStorageNfsAcl" : "createStorageNfsAcl");
            if ("SMB".equals(ownerProtocol)) {
                if (Set.of("CIDR", "IP_ADDRESS").contains(type)) return new Command(update ? UpdateStorageSmbNetworkAclCmd.class : CreateStorageSmbNetworkAclCmd.class, update ? "updateStorageSmbNetworkAcl" : "createStorageSmbNetworkAcl");
                if (Set.of("AD_USER", "AD_GROUP").contains(type)) throw new InvalidParameterValueException("SMB AD configuration restore is deferred");
                return new Command(update ? UpdateStorageSmbAclCmd.class : CreateStorageSmbAclCmd.class, update ? "updateStorageSmbAcl" : "createStorageSmbAcl");
            }
            if ("ISCSI".equals(ownerProtocol)) return new Command(update ? UpdateStorageIscsiAclCmd.class : CreateStorageIscsiAclCmd.class, update ? "updateStorageIscsiAcl" : "createStorageIscsiAcl");
            if ("NVME_OF".equals(ownerProtocol)) return new Command(update ? UpdateStorageNvmeOfHostAclCmd.class : CreateStorageNvmeOfHostAclCmd.class, update ? "updateStorageNvmeOfHostAcl" : "createStorageNvmeOfHostAcl");
        }
        throw new InvalidParameterValueException("Unsupported configuration resource or deferred identity domain");
    }
    public void validateBindings(JsonObject plan) {
        Map<String, JsonObject> owners = new HashMap<>();
        for (String action : new String[] {"keep", "update", "create"}) for (JsonElement item : plan.getAsJsonArray(action)) {
            JsonObject change = item.getAsJsonObject();owners.put(text(change, "sourceUuid"), change.getAsJsonObject("desired"));
        }
        for (String action : new String[] {"update", "create"}) for (JsonElement item : plan.getAsJsonArray(action)) {
            JsonObject change = item.getAsJsonObject();String kind = text(change, "kind");JsonObject desired = change.getAsJsonObject("desired");
            if ("identity-domain".equals(kind)) continue;// AD is blocked by semantic validation; an empty identity observation is not an apply input.
            Command command = command(kind, desired, "update".equals(action), owners);
            JsonObject parameters = StorageConfigCommandBinding.parameters(desired, kind);
            if ("access-rules".equals(kind)) {
                String protocol = text(owners.get(text(desired, "resourceUuid")), "protocol");
                if ("update".equals(action)) parameters.remove("principaltype");
                if ("NFS".equals(protocol)) for (String key : new String[] {"readonly", "endpointmode", "listenerports", "mode", "owneruid", "ownergid", "recursivepermission"}) parameters.remove(key);
                if ("SMB".equals(protocol) && Set.of("CIDR", "IP_ADDRESS").contains(text(desired, "principal_type"))) parameters.remove("permission");
                if ("ISCSI".equals(protocol)) { parameters.add("initiatoriqn", parameters.remove("principal"));parameters.remove("principaltype"); }
                if ("NVME_OF".equals(protocol)) { parameters.add("hostnqn", parameters.remove("principal"));parameters.remove("principaltype");parameters.remove("permission"); }
            }
            StorageConfigCommandBinding.bind(command.type, parameters);
        }
    }
    public void apply(StorageServiceInstanceVO instance, JsonObject plan, JsonObject credentials) {
        if (plan.getAsJsonArray("blockers").size() > 0 || !instance.getUuid().equals(text(plan, "targetInstanceUuid"))) {
            throw new InvalidParameterValueException("Configuration restore plan is blocked or changed scope");
        }
        Map<String, Long> currentIds = manager.configurationResourceIds(instance.getId());
        Map<String, Long> mapped = new HashMap<>();Map<String, JsonObject> owners = new HashMap<>();
        JsonArray changes = new JsonArray();
        for (String action : new String[] {"keep", "update", "create"}) for (JsonElement item : plan.getAsJsonArray(action)) {
            JsonObject change = item.getAsJsonObject();changes.add(change);JsonObject desired = change.getAsJsonObject("desired");
            owners.put(text(change, "sourceUuid"), desired);
            String target = text(change, "targetUuid");if (target != null) {
                Long id = currentIds.get(target);if (id == null) throw new InvalidParameterValueException("Mapped configuration resource disappeared");
                mapped.put(text(change, "sourceUuid"), id);
            }
        }
        manager.beginConfigurationBatch(instance.getId());
        try {
            if ("CREATE_NEW".equals(text(plan, "targetMode"))) {
                Set<String> prepared = new java.util.HashSet<>();
                for (JsonElement item : changes) {
                    JsonObject change = item.getAsJsonObject();String kind = text(change, "kind");
                    if (!Set.of("file-shares", "posix-directory-policies").contains(kind)) continue;
                    JsonObject desired = change.getAsJsonObject("desired");String volume = text(desired, "volumeUuid");
                    String relative = "posix-directory-policies".equals(kind) ? text(desired, "relative_path") : desired.has("config") ? text(desired.getAsJsonObject("config"), "relativeSharePath") : null;
                    if (volume == null || relative == null) throw new InvalidParameterValueException("New-service directory requires explicit backing and relative path");
                    String mappedVolume = plan.getAsJsonObject("volumeMappings").get(volume).getAsString();
                    if (prepared.add(mappedVolume + ":" + relative)) manager.prepareConfigurationDirectory(instance, mappedVolume, relative);
                }
            }
            for (String kind : new String[] {"protocols", "posix-directory-policies", "file-shares", "block-targets", "access-rules"}) {
                java.util.List<JsonElement> ordered = new java.util.ArrayList<>();
                for (JsonElement item : changes) ordered.add(item);
                if ("block-targets".equals(kind)) ordered.sort(java.util.Comparator.comparingInt(item -> {
                    JsonObject desired = item.getAsJsonObject().getAsJsonObject("desired");
                    return desired.has("config") && "namespace".equals(text(desired.getAsJsonObject("config"), "type")) ? 1 : 0;
                }));
                for (JsonElement item : ordered) {
                    JsonObject change = item.getAsJsonObject();if (!kind.equals(text(change, "kind")) || "KEEP".equals(text(change, "action"))) continue;
                    if ("identity-domain".equals(kind)) continue;
                    JsonObject desired = change.getAsJsonObject("desired");boolean update = "UPDATE".equals(text(change, "action"));
                    Command command = command(kind, desired, update, owners);JsonObject parameters = StorageConfigCommandBinding.parameters(desired, kind);
                    if (update) parameters.addProperty("id", mapped.get(text(change, "sourceUuid")));
                    else if (!command.type.equals(CreateStorageNvmeOfNamespaceCmd.class) && !kind.equals("access-rules")) parameters.addProperty("instanceid", instance.getId());
                    String volume = text(desired, "volumeUuid");
                    if (volume != null) parameters.addProperty("volumeid", manager.configurationVolumeId(instance, plan.getAsJsonObject("volumeMappings").get(volume).getAsString()));
                    String policy = text(desired, "posixPolicyUuid");
                    if (policy != null) {
                        Long id = mapped.get(policy);if (id == null) throw new InvalidParameterValueException("Mapped POSIX policy is not applied");
                        parameters.addProperty("posixpolicyid", id);
                    }
                    if ("access-rules".equals(kind)) {
                        Long owner = mapped.get(text(desired, "resourceUuid"));if (owner == null) throw new InvalidParameterValueException("Mapped ACL owner is not applied");
                        JsonObject resource = owners.get(text(desired, "resourceUuid"));String protocol = text(resource, "protocol");
                        if (!update) parameters.addProperty("NFS".equals(protocol) ? "exportid" : "SMB".equals(protocol) ? "shareid" : "NVME_OF".equals(protocol) ? "subsystemid" : "targetid", owner);
                        if (credentials.has(text(desired, "uuid"))) for (Map.Entry<String, JsonElement> value : credentials.getAsJsonObject(text(desired, "uuid")).entrySet()) {
                            if (!Set.of("password", "chapsecret", "mutualchapsecret", "dhchapkey", "dhchapctrlkey").contains(value.getKey())) {
                                throw new InvalidParameterValueException("Unexpected configuration credential field");
                            }
                            parameters.add(value.getKey(), value.getValue().deepCopy());
                        }
                        if (update) parameters.remove("principaltype");
                        if ("NFS".equals(protocol)) {
                            for (String unrelated : new String[] {"readonly", "endpointmode", "listenerports", "mode", "owneruid", "ownergid", "recursivepermission"}) parameters.remove(unrelated);
                        } else if ("SMB".equals(protocol) && Set.of("CIDR", "IP_ADDRESS").contains(text(desired, "principal_type"))) {
                            parameters.remove("permission");
                        } else if ("ISCSI".equals(protocol)) {
                            parameters.add("initiatoriqn", parameters.remove("principal"));parameters.remove("principaltype");
                        } else if ("NVME_OF".equals(protocol)) {
                            parameters.add("hostnqn", parameters.remove("principal"));parameters.remove("principaltype");parameters.remove("permission");
                        }
                    }
                    if ("block-targets".equals(kind)) {
                        if ("ISCSI".equals(text(desired, "protocol"))) {
                            parameters.add("targetname", desired.get("target_name").deepCopy());
                            if (desired.has("lun_or_namespace") && !desired.get("lun_or_namespace").isJsonNull()) parameters.add("lun", desired.get("lun_or_namespace").deepCopy());
                        } else if (command.type.equals(CreateStorageNvmeOfNamespaceCmd.class) || command.type.equals(UpdateStorageNvmeOfNamespaceCmd.class)) {
                            if (!update) {
                                Long subsystem = null;
                                for (Map.Entry<String, JsonObject> owner : owners.entrySet()) {
                                    JsonObject candidate = owner.getValue();
                                    if (text(desired, "target_name").equals(text(candidate, "target_name")) && candidate.has("config")
                                            && "subsystem".equals(text(candidate.getAsJsonObject("config"), "type"))) subsystem = mapped.get(owner.getKey());
                                }
                                if (subsystem == null) throw new InvalidParameterValueException("Mapped NVMe-oF subsystem is not applied");
                                parameters.addProperty("subsystemid", subsystem);
                            }
                            if (desired.has("lun_or_namespace") && !desired.get("lun_or_namespace").isJsonNull()) parameters.add("namespaceid", desired.get("lun_or_namespace").deepCopy());
                        } else parameters.add("subsystemnqn", desired.get("target_name").deepCopy());
                    }
                    BaseCmd cmd = StorageConfigCommandBinding.bind(command.type, parameters);
                    Object response = manager.invokeConfigurationDomainCommand(cmd, command.method);
                    Map<String, Long> after = manager.configurationResourceIds(instance.getId());
                    if (update) continue;
                    // Current API services allocate fresh UUIDs. Bind provenance to the one new scoped row.
                    Set<String> added = new java.util.HashSet<>(after.keySet());added.removeAll(currentIds.keySet());
                    if (added.isEmpty() && "protocols".equals(kind)) {
                        JsonObject observed = new com.google.gson.Gson().toJsonTree(response).getAsJsonObject();
                        String uuid = text(observed, "id");
                        if (uuid == null || !after.containsKey(uuid)) throw new InvalidParameterValueException("Existing protocol endpoint identity was not returned");
                        mapped.put(text(change, "sourceUuid"), after.get(uuid));currentIds = after;continue;
                    }
                    if (added.size() != 1) throw new InvalidParameterValueException("Configuration API did not allocate exactly one resource");
                    String newUuid = added.iterator().next();mapped.put(text(change, "sourceUuid"), after.get(newUuid));currentIds = after;
                }
            }
            manager.finishConfigurationBatch(instance);
        } finally { manager.abortConfigurationBatch(); }
    }
}
