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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.apache.cloudstack.storage.dataservice.dao.StorageFileShareDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceProtocolDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageBlockTargetDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageAccessRuleDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceRuntimeUpgradeDao;

/** Read-only planning/admission; never invokes a configuration candidate or runtime-upgrade preflight. */
final class StorageServiceConfigurationPreflight {
    private static final Set<String> APIS = new HashSet<>();
    static {
        for (String kind : Arrays.asList("NfsExport","NfsAcl","SmbShare","SmbAcl","IscsiTarget","IscsiAcl","NvmeOfSubsystem","NvmeOfNamespace","NvmeOfHostAcl"))
            for (String action : Arrays.asList("create","update","delete")) APIS.add(action+"Storage"+kind);
        APIS.add("enableStorageServiceProtocol"); APIS.add("deleteStorageServiceProtocol");
    }
    private final StorageServiceProtocolDao protocols;
    private final StorageFileShareDao shares;
    private final StorageBlockTargetDao targets;
    private final StorageAccessRuleDao rules;
    private final VolumeDao volumes;
    private final StorageServiceRuntimeUpgradeDao upgrades;
    private final StorageServiceGuestCommandDispatcher guest;
    private final Consumer<VolumeVO> volumeAccess;
    StorageServiceConfigurationPreflight(StorageServiceProtocolDao p, StorageFileShareDao s, StorageBlockTargetDao t, StorageAccessRuleDao a,
            VolumeDao v, StorageServiceRuntimeUpgradeDao u, StorageServiceGuestCommandDispatcher g, Consumer<VolumeVO> access) {
        protocols=p; shares=s; targets=t; rules=a; volumes=v; upgrades=u; guest=g; volumeAccess=access;
    }
    static String api(String name) {
        if (name.endsWith("Cmd")) name=Character.toLowerCase(name.charAt(0))+name.substring(1,name.length()-3);
        if (!APIS.contains(name)) throw new InvalidParameterValueException("Unsupported configuration intent");
        return name;
    }
    static boolean runtimeWriting(StorageServiceRuntimeUpgradeVO value) {
        return value != null && (value.getState()==StorageServiceRuntimeUpgradeVO.State.OBSERVATION_PENDING ||
                value.getState()==StorageServiceRuntimeUpgradeVO.State.RUNNING &&
                Arrays.asList("ACTIVATING","VERIFYING","ROLLING_BACK").contains(value.getPhase()));
    }
    static JsonObject parseIntent(String raw) {
        if (raw == null || raw.getBytes(StandardCharsets.UTF_8).length>4096)
            throw new InvalidParameterValueException("Public preflight intent exceeds its bound");
        try {
            JsonObject value=JsonParser.parseString(raw).getAsJsonObject();
            Set<String> allowed=new HashSet<>(Arrays.asList("api","resourceid","protocol","recursive","volumeid","volumemode","newvolume"));
            if (!allowed.containsAll(value.keySet())) throw new IllegalArgumentException();
            api(value.get("api").getAsString());
            for (String k:Arrays.asList("api","resourceid","protocol","volumeid","volumemode"))
                if (value.has(k) && !value.get(k).isJsonPrimitive()) throw new IllegalArgumentException();
            if (value.has("recursive") && (!value.get("recursive").isJsonPrimitive() || !value.getAsJsonPrimitive("recursive").isBoolean())) throw new IllegalArgumentException();
            if (value.has("newvolume")) {
                JsonObject n=value.getAsJsonObject("newvolume");
                if (!new HashSet<>(Arrays.asList("diskofferingid","storageid","size")).containsAll(n.keySet())) throw new IllegalArgumentException();
                for (JsonElement e:n.asMap().values()) if (!e.isJsonPrimitive()) throw new IllegalArgumentException();
            }
            return value;
        } catch (RuntimeException error) { throw new InvalidParameterValueException("Invalid public configuration intent"); }
    }
    static JsonObject commandIntent(org.apache.cloudstack.api.command.user.storage.dataservice.BaseStorageServiceConfigurationCmd cmd) {
        JsonObject value=new JsonObject(); value.addProperty("api",api(cmd.getClass().getSimpleName()));
        Map<String,Object> request=StorageServiceConfigurationCoordinator.publicRequest(cmd);
        for (String name:Arrays.asList("id","protocol","volumeid","recursivepermission"))
            if (request.get(name)!=null) value.add(name,new Gson().toJsonTree(request.get(name)));
        return value;
    }
    private StorageFileShareVO share(JsonElement id) {
        if (id==null || id.isJsonNull()) return null;
        String v=id.getAsString(); return v.matches("[0-9]+")?shares.findById(Long.valueOf(v)):shares.findByUuid(v);
    }
    private StorageBlockTargetVO target(JsonElement id) {
        if (id==null || id.isJsonNull()) return null;
        String v=id.getAsString(); return v.matches("[0-9]+")?targets.findById(Long.valueOf(v)):targets.findByUuid(v);
    }
    private StorageAccessRuleVO rule(JsonElement id) {
        if (id==null || id.isJsonNull()) return null;
        String v=id.getAsString(); return v.matches("[0-9]+")?rules.findById(Long.valueOf(v)):rules.findByUuid(v);
    }
    JsonObject observe(StorageServiceInstanceVO instance, JsonObject intent, Long owner) {
        String name=api(intent.get("api").getAsString());
        StorageServiceInstance.Protocol protocol=name.contains("Nfs")?StorageServiceInstance.Protocol.NFS:
                name.contains("Smb")?StorageServiceInstance.Protocol.SMB:name.contains("Iscsi")?StorageServiceInstance.Protocol.ISCSI:
                name.contains("NvmeOf")?StorageServiceInstance.Protocol.NVME_OF:
                StorageServiceInstance.Protocol.valueOf(intent.get("protocol").getAsString().toUpperCase(Locale.ROOT));
        List<StorageFileShareVO> fs=shares.listByInstanceIdAndProtocol(instance.getId(),protocol);
        List<StorageBlockTargetVO> bs=targets.listByInstanceIdAndProtocol(instance.getId(),protocol);
        JsonElement ref=intent.has("resourceid")?intent.get("resourceid"):intent.get("id");
        StorageFileShareVO selectedFile=null; StorageBlockTargetVO selectedBlock=null;
        if (ref!=null) {
            if (name.contains("Acl")) {
                StorageAccessRuleVO selected=rule(ref);
                if (selected==null) throw new InvalidParameterValueException("Configuration resource not found");
                JsonPrimitive parent=new JsonPrimitive(selected.getResourceId());
                if (selected.getResourceType()==StorageServiceInstance.AccessResourceType.FILE_SHARE) selectedFile=share(parent);
                else selectedBlock=target(parent);
            } else if (protocol==StorageServiceInstance.Protocol.NFS || protocol==StorageServiceInstance.Protocol.SMB) selectedFile=share(ref);
            else selectedBlock=target(ref);
            if (selectedFile==null && selectedBlock==null || selectedFile!=null && (selectedFile.getInstanceId()!=instance.getId() || selectedFile.getProtocol()!=protocol)
                    || selectedBlock!=null && (selectedBlock.getInstanceId()!=instance.getId() || selectedBlock.getProtocol()!=protocol))
                throw new InvalidParameterValueException("Configuration resource is outside this instance/protocol");
        }
        VolumeVO volume=null;
        if (intent.has("volumeid") && !intent.get("volumeid").isJsonNull() && !intent.get("volumeid").getAsString().isEmpty()) {
            String v=intent.get("volumeid").getAsString(); volume=v.matches("[0-9]+")?volumes.findById(Long.valueOf(v)):volumes.findByUuid(v);
            if (volume==null) throw new InvalidParameterValueException("Backing volume not found");
            volumeAccess.accept(volume);
            if (volume.getDataCenterId()!=instance.getDataCenterId() || !"Ready".equals(String.valueOf(volume.getState()))
                    || volume.getInstanceId()!=null && !Objects.equals(volume.getInstanceId(),instance.getVmId()))
                throw new InvalidParameterValueException("Backing volume is not ready for this instance");
        }
        boolean drain=name.startsWith("delete") || name.startsWith("update") &&
                (name.contains("Acl") || name.contains("Iscsi") || name.contains("NvmeOf"));
        Long original=selectedFile!=null?selectedFile.getVolumeId():selectedBlock!=null?selectedBlock.getVolumeId():null;
        if (name.startsWith("update") && volume!=null && !Objects.equals(original,volume.getId())) drain=true;
        JsonObject query=new JsonObject(); query.addProperty("instanceUuid",instance.getUuid());
        query.addProperty("revision",instance.getConfigurationRevision()); query.addProperty("protocol",protocol.name());
        query.addProperty("action",name.startsWith("delete")?"DELETE":name.startsWith("update")?"UPDATE":"CREATE");
        query.addProperty("drainrequired",drain);
        query.addProperty("recursive",intent.has("recursive")?intent.get("recursive").getAsBoolean():
                intent.has("recursivepermission") && intent.get("recursivepermission").getAsBoolean());
        List<String> refs=java.util.stream.Stream.concat(fs.stream().map(StorageFileShareVO::getUuid),bs.stream().map(StorageBlockTargetVO::getUuid)).sorted().collect(java.util.stream.Collectors.toList());
        JsonArray ids=new JsonArray(); refs.stream().limit(20).forEach(ids::add);
        Set<Integer> ports=new java.util.TreeSet<>();
        for (StorageServiceProtocolVO row:protocols.listByInstanceId(instance.getId())) {
            if (row.getProtocol()!=protocol) continue;
            if (row.getPort()!=null) ports.add(row.getPort());
            JsonObject config=StorageServiceConfigurationCoordinator.parsed(row.getConfigJson());
            if (config.has("listenerGroups")) for (JsonElement item:config.getAsJsonArray("listenerGroups"))
                if (item.isJsonObject() && item.getAsJsonObject().has("port")) ports.add(item.getAsJsonObject().get("port").getAsInt());
        }
        JsonArray listenerPorts=new JsonArray();ports.forEach(listenerPorts::add);query.add("ports",listenerPorts);
        Set<Long> volumeRefs=new HashSet<>();
        fs.stream().map(StorageFileShareVO::getVolumeId).filter(Objects::nonNull).forEach(volumeRefs::add);
        bs.stream().map(StorageBlockTargetVO::getVolumeId).filter(Objects::nonNull).forEach(volumeRefs::add);
        if (volume!=null) volumeRefs.add(volume.getId());
        JsonArray volumeUuids=new JsonArray();
        for (Long volumeRef:volumeRefs) {
            VolumeVO backing=volumes.findById(volumeRef);
            if (backing!=null) volumeUuids.add(backing.getUuid());
        }
        query.add("volumeuuids",volumeUuids);
        query.add("resourceids",ids); query.addProperty("resourcecount",refs.size());
        query.addProperty("resourcesdigest",org.apache.commons.codec.digest.DigestUtils.sha256Hex(String.join("\n",refs)));
        query.addProperty("ackrequired",(protocol==StorageServiceInstance.Protocol.NFS || protocol==StorageServiceInstance.Protocol.SMB) && !refs.isEmpty());
        if (instance.getVmId()==null) throw new InvalidParameterValueException("SystemVM is unavailable");
        JsonObject report;
        try {
            if (owner==null) {
                StorageServiceGuestCommandResult caps=guest.dispatch(new StorageServiceGuestCommand(instance.getVmId(),
                    "configuration capability show","{}",20,Collections.emptySet()));
                JsonObject capability=StorageServiceConfigurationCoordinator.parsed(caps==null?null:caps.getResultJson());
                if (caps==null || !caps.isSuccess() || !capability.has("configurationPreflightVersion") || capability.get("configurationPreflightVersion").getAsInt()!=1)
                    throw new CloudRuntimeException("PREFLIGHT_CAPABILITY_REQUIRED");
            }
            StorageServiceGuestCommandResult wire=guest.dispatch(new StorageServiceGuestCommand(instance.getVmId(),
                "configuration preflight show",query.toString(),20,Collections.emptySet()));
            report=StorageServiceConfigurationCoordinator.parsed(wire==null?null:wire.getResultJson());
            if (wire==null || !wire.isSuccess() || !report.has("checks") || !report.has("status")) throw new CloudRuntimeException("PREFLIGHT_OBSERVATION_UNAVAILABLE");
        } catch (CloudRuntimeException error) {
            report=new JsonObject(); report.addProperty("status","UNKNOWN");report.addProperty("revision",instance.getConfigurationRevision());
            report.addProperty("observedat",System.currentTimeMillis()/1000.0);report.addProperty("expiresat",System.currentTimeMillis()/1000.0);
            report.addProperty("fingerprint","");report.add("checks",new JsonArray());
            JsonObject failed=new JsonObject();failed.addProperty("code","PREFLIGHT_CAPABILITY_REQUIRED".equals(error.getMessage()) ? "PREFLIGHT_CAPABILITY_REQUIRED" : "RESOURCE_OBSERVATION_UNAVAILABLE");
            failed.addProperty("blocking",true);failed.addProperty("severity","ERROR");report.getAsJsonArray("checks").add(failed);
        }
        if (instance.getActiveConfigurationOperationId()!=null && !Objects.equals(owner,instance.getActiveConfigurationOperationId()))
            block(report,"CONFIGURATION_WRITER_ACTIVE");
        if (runtimeWriting(upgrades.findActiveByInstanceId(instance.getId()))) block(report,"RUNTIME_WRITER_ACTIVE");
        report.add("intent", query);
        return report;
    }
    static void block(JsonObject report,String code) {
        report.addProperty("status","BLOCKED");JsonObject c=new JsonObject();c.addProperty("code",code);c.addProperty("blocking",true);
        c.addProperty("severity","ERROR");report.getAsJsonArray("checks").add(c);
    }
    static void requireReady(JsonObject report,String ack) {
        if (!"READY".equals(report.get("status").getAsString()) || report.getAsJsonArray("checks").asList().stream()
                .anyMatch(c -> c.isJsonObject() && c.getAsJsonObject().has("blocking") && c.getAsJsonObject().get("blocking").getAsBoolean())) throw new Rejected(report);
        if (report.has("impact") && report.getAsJsonObject("impact").get("ackrequired").getAsBoolean()
                && (!report.has("fingerprint") || !Objects.equals(ack,report.get("fingerprint").getAsString()))) {
            block(report,"IMPACT_ACK_REQUIRED");throw new Rejected(report);
        }
    }
    static final class Rejected extends CloudRuntimeException {
        final JsonObject report;
        Rejected(JsonObject value) {super("CONFIGURATION_PREFLIGHT_BLOCKED");report=value;}
    }
}
