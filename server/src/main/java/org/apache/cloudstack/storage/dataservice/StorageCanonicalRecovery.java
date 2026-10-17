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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** File presence is exact; accepted resource transitions have the same applied meaning. */
final class StorageCanonicalRecovery {
    private StorageCanonicalRecovery() { }

    static String protocolPath(StorageServiceInstance.Protocol protocol) {
        switch (protocol) {
            case NFS: return "desired-state/nfs-export-apply.json";
            case SMB: return "desired-state/smb-share-apply.json";
            case ISCSI: return "iscsi-targets.json";
            default: return "nvmeof-subsystems.json";
        }
    }

    static boolean emptyProtocolFile(StorageServiceInstance.Protocol protocol, JsonElement value) {
        if (value == null || !value.isJsonObject()) return false;
        String collection = protocol == StorageServiceInstance.Protocol.NFS ? "exports"
                : protocol == StorageServiceInstance.Protocol.SMB ? "shares"
                : protocol == StorageServiceInstance.Protocol.ISCSI ? "targets" : "subsystems";
        JsonElement items = value.getAsJsonObject().get(collection);
        return items != null && items.isJsonArray() && items.getAsJsonArray().size() == 0;
    }

    static void requireSameMeaning(JsonObject source, JsonObject observed) {
        if (source == null || observed == null || !normalize(source).equals(normalize(observed))) {
            throw new CloudRuntimeException("Reconciled runtime declarative semantics differ from the verified recovery source");
        }
    }

    /** A successful opaque SOURCE replay may rediscover a BLOCK device under a new kernel name. */
    static List<JsonObject> requireNvmeSourceRebind(JsonObject source, JsonObject observed, JsonObject inventory) {
        try {
            if (!source.keySet().equals(StorageRenderedDesiredState.PATHS) || !observed.keySet().equals(source.keySet())
                    || !flag(inventory.getAsJsonObject("nvmeofRuntime"), "listening"))
                throw new IllegalArgumentException();
            JsonObject corrected = observed.deepCopy();
            JsonArray before = source.getAsJsonObject("nvmeof-subsystems.json").getAsJsonArray("subsystems");
            JsonArray after = corrected.getAsJsonObject("nvmeof-subsystems.json").getAsJsonArray("subsystems");
            JsonArray live = inventory.getAsJsonObject("nvmeofSubsystems").getAsJsonArray("subsystems");
            if (before.size() != after.size()) throw new IllegalArgumentException();
            List<JsonObject> rebound = new ArrayList<>();
            Set<String> identities = new HashSet<>();
            for (int i = 0; i < before.size(); i++) {
                JsonObject oldSubsystem = before.get(i).getAsJsonObject(), subsystem = after.get(i).getAsJsonObject();
                JsonArray oldNamespaces = oldSubsystem.getAsJsonArray("namespaces"), namespaces = subsystem.getAsJsonArray("namespaces");
                if (oldNamespaces.size() != namespaces.size()) throw new IllegalArgumentException();
                for (int j = 0; j < oldNamespaces.size(); j++) {
                    JsonObject old = oldNamespaces.get(j).getAsJsonObject(), current = namespaces.get(j).getAsJsonObject();
                    if (old.equals(current)) continue;
                    String oldPath = text(old.getAsJsonObject("runtime"), "backingPath"), newPath = text(current.getAsJsonObject("runtime"), "backingPath");
                    String uuid = text(old, "uuid"), volume = text(old, "volumeUuid");
                    String nqn = text(oldSubsystem, "targetName"), nsid = text(old, "lunOrNamespace");
                    if (oldPath.equals(newPath) || !devicePath(oldPath) || !devicePath(newPath)
                            || !canonicalUuid(uuid) || !canonicalUuid(volume) || !nsid.matches("[1-9][0-9]*")
                            || !"BLOCK".equals(text(old, "backstoreType")) || positive(old, "volumeId") <= 0
                            || !identities.add(uuid) || !identities.add(nqn + ":" + nsid))
                        throw new IllegalArgumentException();
                    long size = positive(old, "volumeSizeBytes");
                    JsonObject runtime = null;
                    for (JsonElement entry : live) {
                        JsonObject actualSubsystem = entry.getAsJsonObject();
                        if (!nqn.equals(text(actualSubsystem, "targetName"))) continue;
                        if (!flag(actualSubsystem.getAsJsonObject("runtime"), "configfsPresent")) throw new IllegalArgumentException();
                        for (JsonElement item : actualSubsystem.getAsJsonArray("namespaces")) {
                            JsonObject actualNamespace = item.getAsJsonObject();
                            if (!uuid.equals(text(actualNamespace, "uuid")) || !nsid.equals(text(actualNamespace, "lunOrNamespace"))) continue;
                            if (runtime != null) throw new IllegalArgumentException();
                            runtime = actualNamespace.getAsJsonObject("runtime");
                        }
                    }
                    String serial = runtime == null ? "" : text(runtime, "serial").replace("-", "").toLowerCase(java.util.Locale.ROOT);
                    String expected = volume.replace("-", "");
                    if (runtime == null || !flag(runtime, "configfsPresent") || !flag(runtime, "enabled")
                            || !"READY".equals(text(runtime, "runtimeState")) || !"EXACT".equals(text(runtime, "mappingStatus"))
                            || !volume.equals(text(runtime, "runtimeVolumeUuid")) || !volume.equals(text(runtime, "volumeUuid"))
                            || !nqn.equals(text(runtime, "subsystemNqn")) || !nsid.equals(text(runtime, "namespaceId"))
                            || !newPath.equals(text(runtime, "backingPath")) || !newPath.equals(text(runtime, "resolvedBackingPath"))
                            || serial.length() < 20 || !expected.startsWith(serial) || positive(runtime, "actualSizeBytes") != size)
                        throw new IllegalArgumentException();
                    current.getAsJsonObject("runtime").addProperty("backingPath", oldPath);
                    rebound.add(old.deepCopy());
                }
            }
            if (rebound.isEmpty() || !source.equals(corrected)) throw new IllegalArgumentException();
            return rebound;
        } catch (RuntimeException invalid) {
            throw new CloudRuntimeException("LOCAL SOURCE differs beyond exact serial-verified NVMe backing-path rebinding");
        }
    }

    static void requireSourceData(JsonObject source, JsonObject data) {
        Map<String, JsonObject> wanted = new LinkedHashMap<>();
        for (String path : List.of("desired-state/nfs-export-apply.json","desired-state/smb-share-apply.json")) {
            JsonElement file = source.get(path);if (file == null || file.isJsonNull()) continue;
            String list = path.contains("nfs-") ? "exports" : "shares";
            for (JsonElement entry : file.getAsJsonObject().getAsJsonArray(list)) {
                JsonObject config=entry.getAsJsonObject().getAsJsonObject("config"), row=new JsonObject();
                String uuid=text(config,"attachedVolumeUuid"), filesystem=text(config,"filesystemUuid");
                if (!canonicalUuid(uuid) || !canonicalUuid(filesystem)) throw new IllegalArgumentException();
                row.addProperty("kind","FILE_DATA");row.addProperty("filesystemUuid",filesystem);
                row.addProperty("sizeBytes",positive(config.getAsJsonObject("lastInspection"),"sizeBytes"));
                if (wanted.containsKey(uuid) && !wanted.get(uuid).equals(row)) throw new IllegalArgumentException();
                wanted.put(uuid,row);
            }
        }
        JsonElement iscsi=source.get("iscsi-targets.json"), nvme=source.get("nvmeof-subsystems.json");
        List<JsonObject> raw = new ArrayList<>();
        if (iscsi!=null&&!iscsi.isJsonNull()) for(JsonElement row:iscsi.getAsJsonObject().getAsJsonArray("targets"))raw.add(row.getAsJsonObject());
        if (nvme!=null&&!nvme.isJsonNull()) for(JsonElement subsystem:nvme.getAsJsonObject().getAsJsonArray("subsystems"))
            for(JsonElement row:subsystem.getAsJsonObject().getAsJsonArray("namespaces"))raw.add(row.getAsJsonObject());
        for(JsonObject entry:raw) {
            if(!"BLOCK".equals(text(entry,"backstoreType")))throw new IllegalArgumentException();
            String uuid=text(entry,"volumeUuid");if(!canonicalUuid(uuid))throw new IllegalArgumentException();
            JsonObject row=new JsonObject();row.addProperty("kind","BLOCK_RAW");row.addProperty("sizeBytes",positive(entry,"volumeSizeBytes"));
            if(wanted.containsKey(uuid)&&!wanted.get(uuid).equals(row))throw new IllegalArgumentException();wanted.put(uuid,row);
        }
        Set<String> seen=new HashSet<>();
        for(JsonElement value:data.getAsJsonArray("volumes")) {
            JsonObject actual=value.getAsJsonObject();String uuid=text(actual,"volumeUuid");JsonObject expected=wanted.get(uuid);
            String serial=text(actual,"serial").replace("-","").toLowerCase(java.util.Locale.ROOT),full=uuid.replace("-","");
            if(expected==null||!seen.add(uuid)||!"EXACT".equals(text(actual,"mappingStatus"))||!"VOLUME_SERIAL".equals(text(actual,"matchedBy"))
                    ||!Set.of(full,full.substring(0,20)).contains(serial)||!text(expected,"kind").equals(text(actual,"kind"))
                    ||positive(expected,"sizeBytes")!=positive(actual,"sizeBytes")
                    ||expected.has("filesystemUuid")&&!expected.get("filesystemUuid").equals(actual.get("filesystemUuid")))throw new IllegalArgumentException();
        }
        if(!seen.equals(wanted.keySet()))throw new IllegalArgumentException();
    }

    /** Binds the existing fresh protected NFS readback to exactly the frozen SOURCE exports. */
    static JsonObject requireNfsSourceRuntime(JsonObject source, JsonObject health, JsonObject inventory, JsonObject data, String boot) {
        if(!flag(source,"enabled")||source.getAsJsonArray("exports").size()==0)throw new IllegalArgumentException();
        String mode=text(source,"protocolMode"),defaultIp=source.has("listenIp")&&!source.get("listenIp").isJsonNull()?text(source,"listenIp"):"0.0.0.0";
        Map<String,List<JsonObject>> wanted=new LinkedHashMap<>();
        for(JsonElement item:source.getAsJsonArray("exports")) {
            JsonObject export=item.getAsJsonObject(),config=export.getAsJsonObject("config");
            List<String> ips=new ArrayList<>();JsonElement chosen=config.get("listenIps");
            if("SELECTED".equals(config.has("endpointMode")?text(config,"endpointMode"):"")&&chosen!=null) {
                if(chosen.isJsonArray())for(JsonElement ip:chosen.getAsJsonArray())ips.add(ip.getAsString());
                else for(String ip:chosen.getAsString().split(","))ips.add(ip.trim());
            }
            if(ips.isEmpty())ips.add(defaultIp);
            List<Integer> ports=new ArrayList<>();JsonElement groups=config.get("listenerGroupPorts");
            if(groups!=null&&groups.isJsonArray())for(JsonElement port:groups.getAsJsonArray())ports.add(port.getAsInt());
            if(ports.isEmpty())ports.add(source.get("port").getAsInt());
            if("V3V4_DUAL".equals(mode)){ips=List.of("0.0.0.0");ports=List.of(2049);}
            else if(!"V4_ONLY".equals(mode))throw new IllegalArgumentException();
            for(String ip:ips)for(int port:ports) {
                if(port<1||port>65535||!ip.matches("[0-9.]+"))throw new IllegalArgumentException();
                wanted.computeIfAbsent(ip+":"+port,key->new ArrayList<>()).add(export);
            }
            JsonObject volume=StorageRootDataManifest.volume(data,text(config,"attachedVolumeUuid"));
            String relative=text(config,"backingPath").substring(text(config,"volumeMountPath").length());
            boolean mounted=false;
            for(JsonElement mount:volume.getAsJsonArray("mounts")) {
                JsonObject entry=mount.getAsJsonObject();
                if(!text(export,"path").equals(text(entry,"target")))continue;
                String actual=text(entry,"source");
                if(!relative.isEmpty()&&!actual.endsWith("["+relative+"]"))throw new IllegalArgumentException();
                if(entry.has("uuid")&&!entry.get("uuid").isJsonNull()&&!config.get("filesystemUuid").equals(entry.get("uuid")))throw new IllegalArgumentException();
                mounted=true;
            }
            if(!mounted)throw new IllegalArgumentException();
        }
        JsonArray endpoints=health.getAsJsonArray("endpoints"),actualExports=inventory.getAsJsonArray("nfsGaneshaExports");
        if(endpoints.size()!=wanted.size()||actualExports.size()!=wanted.size())throw new IllegalArgumentException();
        JsonObject result=new JsonObject();Set<String> seen=new HashSet<>();
        for(JsonElement item:endpoints) {
            JsonObject endpoint=item.getAsJsonObject();String ip=text(endpoint,"listenIp");long port=positive(endpoint,"port");String key=ip+":"+port;
            List<JsonObject> exports=wanted.get(key);
            if(exports==null||!seen.add(key)||!boot.equals(text(endpoint,"bootId"))||!mode.equals(text(endpoint,"protocolMode"))
                    ||!text(endpoint,"configurationSha256").matches("[a-f0-9]{64}")||!text(endpoint,"startTicks").matches("[1-9][0-9]*")
                    ||!("/etc/ganesha/ablestack-storage/"+ip+"_"+port+".conf").equals(text(endpoint,"configurationTarget")))throw new IllegalArgumentException();
            for(String field:List.of("processRunning","listening","listenerOwned","exportInventoryReady","probeObservationVerified","probeSuccess"))
                if(!flag(endpoint,field))throw new IllegalArgumentException();
            long pid=positive(endpoint,"pid");
            JsonObject listener=null;
            for(JsonElement value:actualExports){JsonObject row=value.getAsJsonObject();if(ip.equals(text(row,"listenIp"))&&port==positive(row,"port")){if(listener!=null)throw new IllegalArgumentException();listener=row;}}
            if(listener==null||positive(listener,"pid")!=pid||!flag(listener,"processRunning")||!flag(listener,"listening")
                    ||!"active".equals(text(listener,"status"))||listener.getAsJsonArray("entries").size()!=exports.size()
                    ||endpoint.getAsJsonArray("probeResults").size()!=exports.size())throw new IllegalArgumentException();
            Set<String> found=new HashSet<>();
            for(JsonObject export:exports) {
                String uuid=text(export,"uuid"),path=text(export,"path"),pseudo="/"+text(export,"name");
                JsonObject observed=null,probe=null;
                for(JsonElement value:listener.getAsJsonArray("entries")){JsonObject row=value.getAsJsonObject();if(path.equals(text(row,"path"))&&pseudo.equals(text(row,"pseudo"))){if(observed!=null)throw new IllegalArgumentException();observed=row;}}
                for(JsonElement value:endpoint.getAsJsonArray("probeResults")){JsonObject row=value.getAsJsonObject();if(uuid.equals(text(row,"exportUuid"))){if(probe!=null)throw new IllegalArgumentException();probe=row;}}
                if(observed==null||probe==null||!found.add(uuid)||!flag(probe,"success")||!path.equals(text(probe,"targetPath"))||!pseudo.equals(text(probe,"pseudoPath")))throw new IllegalArgumentException();
                JsonArray clients=new JsonArray();JsonObject config=export.getAsJsonObject("config");
                for(JsonElement value:export.getAsJsonArray("acls")){JsonObject acl=value.getAsJsonObject();clients.add(StorageRecoveryObservation.nfsClient(text(acl,"principal"),"READ_WRITE".equals(text(acl,"permission")),config,acl.getAsJsonObject("config")));}
                if(clients.size()==0)clients.add(StorageRecoveryObservation.nfsClient("*",true,config,new JsonObject()));
                StorageRecoveryObservation.requireNfsClients(clients,observed);
            }
            JsonObject binding=new JsonObject();for(String field:List.of("bootId","configurationSha256","configurationTarget","pid","startTicks"))binding.add(field,endpoint.get(field).deepCopy());result.add(key,binding);
        }
        return result;
    }

    private static String text(JsonObject object, String key) {
        JsonElement value = object == null ? null : object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException();
        return value.getAsString();
    }
    private static boolean flag(JsonObject object, String key) {
        JsonElement value = object == null ? null : object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean();
    }
    private static long positive(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                || !value.getAsString().matches("[1-9][0-9]*")) throw new IllegalArgumentException();
        return Long.parseLong(value.getAsString());
    }
    private static boolean devicePath(String value) { return value.matches("/dev/[A-Za-z0-9][A-Za-z0-9_.-]*"); }
    private static boolean canonicalUuid(String value) { return java.util.UUID.fromString(value).toString().equals(value); }

    private static JsonElement normalize(JsonElement value) {
        if (value.isJsonObject()) {
            JsonObject result = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
                JsonElement field = entry.getValue();
                if ("state".equals(entry.getKey()) && field.isJsonPrimitive() && field.getAsJsonPrimitive().isString()
                        && Set.of("Ready", "Creating", "Updating").contains(field.getAsString())) {
                    result.addProperty(entry.getKey(), "Ready");
                } else {
                    result.add(entry.getKey(), normalize(field));
                }
            }
            return result;
        }
        if (value.isJsonArray()) {
            JsonArray result = new JsonArray();
            for (JsonElement entry : value.getAsJsonArray()) result.add(normalize(entry));
            return result;
        }
        return value.deepCopy();
    }
}
