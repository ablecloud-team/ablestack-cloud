// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
// http://www.apache.org/licenses/LICENSE-2.0
package org.apache.cloudstack.storage.dataservice;

import java.util.*;
import java.util.function.Consumer;
import java.nio.charset.StandardCharsets;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.*;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.cloudstack.api.command.user.storage.dataservice.*;
import org.apache.cloudstack.api.response.StorageDirectoryPolicyResponse;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.storage.dataservice.dao.*;

/** Scoped common-directory planning. Guest writes still belong to the existing coordinator. */
final class StorageDirectoryPolicies {
    private final StorageDirectoryPolicyDao policies;
    private final StorageFileShareDao shares;
    private final StorageAccessRuleDao rules;
    private final VolumeDao volumes;
    private final StorageServiceGuestCommandDispatcher guest;
    private final Consumer<VolumeVO> access;
    StorageDirectoryPolicies(StorageDirectoryPolicyDao p, StorageFileShareDao s, StorageAccessRuleDao a,
            VolumeDao v, StorageServiceGuestCommandDispatcher g, Consumer<VolumeVO> access) {
        policies=p; shares=s; rules=a; volumes=v; guest=g; this.access=access;
    }
    static boolean command(BaseStorageServiceConfigurationCmd cmd) {
        return cmd instanceof CreateStorageDirectoryPolicyCmd || cmd instanceof UpdateStorageDirectoryPolicyCmd;
    }
    static JsonObject parsed(String s) { return StorageServiceConfigurationCoordinator.parsed(s); }
    private JsonObject rpc(StorageServiceInstanceVO instance,String name,JsonObject value) {
        if (instance.getVmId()==null) throw new CloudRuntimeException("DIRECTORY_OBSERVATION_UNAVAILABLE");
        StorageServiceGuestCommandResult r=guest.dispatch(new StorageServiceGuestCommand(instance.getVmId(),name,value.toString(),20,Collections.emptySet()));
        JsonObject data=parsed(r==null?null:r.getResultJson());
        if (r==null || !r.isSuccess() || data.has("error")) throw new CloudRuntimeException(data.has("code")?data.get("code").getAsString():"DIRECTORY_OBSERVATION_UNAVAILABLE");
        return data;
    }
    private List<StorageFileShareVO> all(long instance) {
        List<StorageFileShareVO> value=new ArrayList<>();
        for (StorageServiceInstance.Protocol p:Arrays.asList(StorageServiceInstance.Protocol.NFS,StorageServiceInstance.Protocol.SMB))
            value.addAll(shares.listByInstanceIdAndProtocol(instance,p));
        return value;
    }
    private JsonObject row(StorageFileShareVO s) {
        JsonObject value=new JsonObject();value.addProperty("uuid",s.getUuid());value.addProperty("name",s.getName());
        value.addProperty("protocol",s.getProtocol().name());value.addProperty("state",String.valueOf(s.getState()));
        value.add("config",parsed(s.getConfigJson()));JsonArray a=new JsonArray();
        for(StorageAccessRuleVO rule:rules.listByResource(StorageServiceInstance.AccessResourceType.FILE_SHARE,s.getId())) {
            JsonObject v=new JsonObject();v.addProperty("principalType",rule.getPrincipalType().name());
            v.addProperty("principal",rule.getPrincipal());v.addProperty("permission",rule.getPermission().name());
            v.addProperty("state",String.valueOf(rule.getState()));a.add(v);
        }
        value.add("acls",a);return value;
    }
    JsonArray observations(StorageServiceInstanceVO instance,List<StorageFileShareVO> rows) {
        JsonObject q=new JsonObject();JsonArray a=new JsonArray();rows.forEach(s->a.add(row(s)));q.add("shares",a);
        return rpc(instance,"directory policy inspect",q).getAsJsonArray("directories");
    }
    private StorageDirectoryPolicyVO require(StorageServiceInstanceVO instance,JsonElement id) {
        if(id==null) throw new InvalidParameterValueException("DIRECTORY_POLICY_REQUIRED");
        String s=id.getAsString();StorageDirectoryPolicyVO p=s.matches("[0-9]+")?policies.findById(Long.valueOf(s)):policies.findByUuid(s);
        if(p==null || p.getRevision()<1 || p.getInstanceId()!=instance.getId()) throw new InvalidParameterValueException("DIRECTORY_POLICY_NOT_FOUND");
        return p;
    }
    private VolumeVO volume(StorageServiceInstanceVO instance,long id) {
        VolumeVO v=volumes.findById(id);
        if(v==null) throw new InvalidParameterValueException("Backing volume not found");
        access.accept(v);
        if(v.getDataCenterId()!=instance.getDataCenterId() || !Objects.equals(v.getInstanceId(),instance.getVmId())
                || !"Ready".equals(String.valueOf(v.getState()))) throw new InvalidParameterValueException("DIRECTORY_VOLUME_NOT_ATTACHED");
        return v;
    }
    private JsonObject policyJson(StorageDirectoryPolicyVO p) {
        JsonObject meta=parsed(p.getMetadataJson());
        JsonObject value=new JsonObject();
        value.addProperty("uuid",p.getUuid());value.addProperty("volumeuuid",volumes.findById(p.getVolumeId()).getUuid());
        value.addProperty("canonicalpath",p.getCanonicalPath());value.addProperty("owneruid",p.getOwnerUid());
        value.addProperty("ownergid",p.getOwnerGid());value.addProperty("mode",p.getMode());value.addProperty("revision",p.getRevision());
        for(String key:Arrays.asList("filesystemuuid","inode")) value.add(key,meta.get(key));
        return value;
    }
    private JsonObject proposal(StorageServiceInstanceVO instance,JsonObject intent,JsonArray observations,List<StorageFileShareVO> rows) {
        final boolean adopt=intent.get("api").getAsString().startsWith("create");
        StorageDirectoryPolicyVO existing=adopt?null:require(instance,intent.has("resourceid")?intent.get("resourceid"):intent.get("id"));
        VolumeVO v;
        if(adopt) {
            String id=intent.get("volumeid").getAsString();
            VolumeVO selected=id.matches("[0-9]+")?volumes.findById(Long.valueOf(id)):volumes.findByUuid(id);
            if(selected==null) throw new InvalidParameterValueException("Backing volume not found");
            v=volume(instance,selected.getId());
        } else {
            v=volume(instance,existing.getVolumeId());
            if(!intent.has("expectedpolicyrevision") || existing.getRevision()!=intent.get("expectedpolicyrevision").getAsLong())
                throw new InvalidParameterValueException("DIRECTORY_POLICY_STALE");
        }
        JsonObject observed=null;
        for(JsonElement e:observations) {
            JsonObject o=e.getAsJsonObject();
            if(!"OBSERVED".equals(o.get("status").getAsString()) || !v.getUuid().equals(o.get("volumeuuid").getAsString())) continue;
            if(!adopt && existing.getCanonicalPath().equals(o.get("canonicalpath").getAsString())) { observed=o;break; }
            if(adopt && intent.has("path")) {
                String path=intent.get("path").getAsString();
                boolean match=(path.equals("/") && ".".equals(o.get("canonicalpath").getAsString())) || path.equals(o.get("path").getAsString()) || path.equals(o.get("canonicalpath").getAsString())
                    || ("/"+o.get("canonicalpath").getAsString()).equals(path);
                for(JsonElement ref:o.getAsJsonArray("references")) for(StorageFileShareVO share:rows)
                    if(share.getUuid().equals(ref.getAsJsonObject().get("uuid").getAsString()) && path.equals(share.getPath())) match=true;
                if(match) { observed=o;break; }
            }
        }
        if(observed==null) throw new InvalidParameterValueException("DIRECTORY_OBSERVATION_UNAVAILABLE");
        JsonObject p=existing==null?new JsonObject():policyJson(existing);
        if(adopt) {
            String canonical=observed.get("canonicalpath").getAsString();
            for(StorageDirectoryPolicyVO old:policies.listByInstanceId(instance.getId()))
                if(old.getVolumeId()==v.getId() && old.getCanonicalPath().equals(canonical))
                    throw new InvalidParameterValueException("DIRECTORY_POLICY_CONFLICT");
            p.addProperty("uuid",UUID.nameUUIDFromBytes((instance.getUuid()+":"+v.getUuid()+":"+canonical).getBytes(StandardCharsets.UTF_8)).toString());
            for(String key:Arrays.asList("volumeuuid","canonicalpath","owneruid","ownergid","mode","filesystemuuid","inode")) p.add(key,observed.get(key));
            p.addProperty("revision",1);
        }
        JsonObject changes=intent.has("posixchanges")?intent.getAsJsonObject("posixchanges").deepCopy():new JsonObject();
        if(adopt && changes.size()>0) throw new InvalidParameterValueException("DIRECTORY_POLICY_INVALID");
        if(!adopt) {
            if(changes.size()==0) throw new InvalidParameterValueException("DIRECTORY_POLICY_NO_CHANGES");
            for(String key:Arrays.asList("ownerUid","ownerGid","mode")) if(changes.has(key)) {
                JsonElement value=changes.get(key);
                if(key.equals("mode")) {
                    if(!value.getAsString().matches("^0?[0-7]{3,4}$")) throw new InvalidParameterValueException("DIRECTORY_POLICY_INVALID");
                    value=new JsonPrimitive(String.format("%04o",Integer.parseInt(value.getAsString(),8)));
                } else if(value.getAsLong()<0 || value.getAsLong()>65535) throw new InvalidParameterValueException("DIRECTORY_POLICY_INVALID");
                p.add(key.toLowerCase(Locale.ROOT),value);
            }
            p.addProperty("revision",existing.getRevision()+1);
        }
        JsonObject value=new JsonObject();value.addProperty("action",adopt?"ADOPT":"UPDATE");value.add("policy",p);
        value.add("references",observed.get("references"));
        value.add("changes",changes);value.add("expectedObservation",observed.get("fingerprint"));value.add("units",observed.get("units"));
        if(existing!=null) value.addProperty("expectedPolicyRevision",existing.getRevision());
        JsonArray smb=new JsonArray();rows.stream().filter(s->s.getProtocol()==StorageServiceInstance.Protocol.SMB).forEach(s->smb.add(row(s)));
        value.add("smbShares",smb);
        return value;
    }
    JsonObject preflight(StorageServiceInstanceVO instance,JsonObject intent,Long owner) {
        List<StorageFileShareVO> rows=all(instance.getId());
        JsonArray observations=observations(instance,rows);
        JsonObject candidate=proposal(instance,intent,observations,rows);
        JsonObject preview=rpc(instance,"directory policy preview",candidate);
        candidate.add("expectedTreeFingerprint",preview.get("treefingerprint"));
        JsonArray collateral=new JsonArray();
        for(JsonElement group:observations) {
            JsonObject o=group.getAsJsonObject();if(!o.has("path")) continue;
            boolean affected=false;
            for(JsonElement unit:candidate.getAsJsonArray("units"))
                for(JsonElement path:unit.getAsJsonObject().getAsJsonArray("paths"))
                    if(path.getAsString().equals(o.get("path").getAsString())) affected=true;
            if(affected) for(JsonElement ref:o.getAsJsonArray("references"))
                if("NFS".equals(ref.getAsJsonObject().get("protocol").getAsString())) collateral.add(ref);
        }
        preview.add("unitreferences",collateral);
        JsonObject query=new JsonObject();
        query.addProperty("instanceUuid",instance.getUuid());query.addProperty("revision",instance.getConfigurationRevision());
        query.addProperty("protocol","NFS");query.addProperty("action",candidate.get("action").getAsString().equals("ADOPT")?"CREATE":"UPDATE");
        JsonObject changes=candidate.getAsJsonObject("changes");
        boolean recursive=changes.has("recursivePermission") && changes.get("recursivePermission").getAsBoolean();
        query.addProperty("recursive",recursive);query.addProperty("drainrequired",recursive);query.addProperty("ackrequired",true);
        JsonArray refs=new JsonArray(),ports=new JsonArray(),volumes=new JsonArray();
        rows.stream().map(StorageFileShareVO::getUuid).sorted().forEach(refs::add);
        for(JsonElement unit:candidate.getAsJsonArray("units")) ports.add(unit.getAsJsonObject().get("port"));
        ports.add(445);volumes.add(candidate.getAsJsonObject("policy").get("volumeuuid"));
        query.add("resourceids",refs);query.addProperty("resourcecount",refs.size());query.add("ports",ports);query.add("volumeuuids",volumes);
        query.addProperty("resourcesdigest",DigestUtils.sha256Hex(candidate.toString()+rows.stream().map(s->s.getUuid()+s.getConfigJson()).sorted().collect(java.util.stream.Collectors.joining("\n"))));
        query.add("directorytarget",candidate);
        JsonObject report=rpc(instance,"configuration preflight show",query);
        report.add("intent",query);report.add("directory",candidate);report.add("directorypreview",preview);
        if(instance.getActiveConfigurationOperationId()!=null && !Objects.equals(owner,instance.getActiveConfigurationOperationId()))
            StorageServiceConfigurationPreflight.block(report,"CONFIGURATION_WRITER_ACTIVE");
        return report;
    }
    StorageDirectoryPolicyResponse candidate(StorageServiceInstanceVO instance,StorageServiceConfigurationPlan plan,boolean adopt) {
        JsonObject value=plan.directoryReport.getAsJsonObject("directory").deepCopy(),p=value.getAsJsonObject("policy");
        StorageDirectoryPolicyVO record=adopt?new StorageDirectoryPolicyVO():policies.findByUuid(p.get("uuid").getAsString());
        record.setUuid(p.get("uuid").getAsString());record.setInstanceId(instance.getId());
        VolumeVO volume=volumes.findByUuid(p.get("volumeuuid").getAsString());record.setVolumeId(volume.getId());
        record.setCanonicalPath(p.get("canonicalpath").getAsString());record.setCanonicalHash(DigestUtils.sha256Hex(record.getCanonicalPath()));
        record.setOwnerUid(p.get("owneruid").getAsLong());record.setOwnerGid(p.get("ownergid").getAsLong());record.setMode(p.get("mode").getAsString());
        record.setRevision(p.get("revision").getAsLong());record.setLastRecursive(value.getAsJsonObject("changes").has("recursivePermission") && value.getAsJsonObject("changes").get("recursivePermission").getAsBoolean());
        JsonObject meta=new JsonObject();meta.add("filesystemuuid",p.get("filesystemuuid"));meta.add("inode",p.get("inode"));
        meta.addProperty("lastOperationUuid",plan.operationUuid);record.setMetadataJson(meta.toString());record.setUpdated(new Date());
        if(adopt) record=policies.persist(record);else policies.update(record.getId(),record);
        p.addProperty("operationuuid",plan.operationUuid);
        JsonArray references=new JsonArray();
        for(StorageFileShareVO share:all(instance.getId())) {
            JsonObject config=parsed(share.getConfigJson());
            boolean linked=false;
            for(JsonElement ref:value.getAsJsonArray("references"))
                if(share.getUuid().equals(ref.getAsJsonObject().get("uuid").getAsString())) linked=true;
            if(linked) {
                config.addProperty("directoryPolicyUuid",record.getUuid());share.setConfigJson(config.toString());shares.update(share.getId(),share);
                references.add(row(share));
            }
        }
        plan.commands.put("directory policy apply",value);
        StorageDirectoryPolicyResponse response=response(instance,record,null,references);response.setObservationstatus("APPLIED");return response;
    }

    void guardShare(StorageServiceInstanceVO instance,StorageFileShareVO candidate,Long selected,boolean posixSpecified) {
        List<StorageFileShareVO> peers=new ArrayList<>();
        for(StorageFileShareVO share:all(instance.getId()))
            if(Objects.equals(share.getVolumeId(),candidate.getVolumeId()) && !share.getUuid().equals(candidate.getUuid())) peers.add(share);
        List<StorageDirectoryPolicyVO> known=policies.listByInstanceId(instance.getId());
        String originalConfig=candidate.getConfigJson();
        JsonObject config=parsed(originalConfig);
        String previous=config.has("directoryPolicyUuid")?config.get("directoryPolicyUuid").getAsString():null;
        boolean relevant=selected!=null || previous!=null || known.stream().anyMatch(p->Objects.equals(p.getVolumeId(),candidate.getVolumeId()))
            || peers.stream().anyMatch(p->p.getProtocol()!=candidate.getProtocol());
        if(!relevant) return; // unchanged single-protocol/new-volume path
        StorageDirectoryPolicyVO explicit=selected==null?(previous==null?null:policies.findByUuid(previous)):policies.findById(selected);
        if(selected!=null || previous!=null) {
            if(explicit==null || explicit.getRevision()<1 || explicit.getInstanceId()!=instance.getId()
                    || !Objects.equals(explicit.getVolumeId(),candidate.getVolumeId()))
                throw new InvalidParameterValueException("DIRECTORY_POLICY_PATH_MISMATCH");
            VolumeVO backing=volume(instance,explicit.getVolumeId());
            JsonObject identity=parsed(explicit.getMetadataJson());
            String mount="/srv/ablestack-storage/volumes/"+backing.getUuid();
            config.addProperty("volumeMountPath",mount);
            config.addProperty("backingPath",explicit.getCanonicalPath().equals(".")?mount:mount+"/"+explicit.getCanonicalPath());
            config.add("filesystemUuid",identity.get("filesystemuuid"));
            config.addProperty("directoryPolicyUuid",explicit.getUuid());
            config.addProperty("createDirectory",false);
            candidate.setConfigJson(config.toString());
        } else if(!config.has("backingPath") && candidate.getVolumeId()!=null) {
            VolumeVO backing=volumes.findById(candidate.getVolumeId());
            if(backing!=null && candidate.getPath()!=null) {
                String mount="/srv/ablestack-storage/volumes/"+backing.getUuid();
                config.addProperty("volumeMountPath",mount);config.addProperty("backingPath",mount+candidate.getPath());
                candidate.setConfigJson(config.toString());
            }
        }
        peers.add(candidate);
        JsonObject observed=null;JsonArray observedRows;
        try { observedRows=observations(instance,peers); }
        catch(CloudRuntimeException unavailable) {
            if(selected==null && previous==null && known.stream().noneMatch(p->Objects.equals(p.getVolumeId(),candidate.getVolumeId()))) {
                candidate.setConfigJson(originalConfig);return;
            }
            throw unavailable;
        }
        for(JsonElement e:observedRows) for(JsonElement ref:e.getAsJsonObject().getAsJsonArray("references"))
            if(candidate.getUuid().equals(ref.getAsJsonObject().get("uuid").getAsString())) observed=e.getAsJsonObject();
        if(observed==null || !"OBSERVED".equals(observed.get("status").getAsString())) {
            if(selected!=null || previous!=null) throw new InvalidParameterValueException("DIRECTORY_OBSERVATION_UNAVAILABLE");
            candidate.setConfigJson(originalConfig);
            return; // new isolated directory, existing preparation retains its guards
        }
        StorageDirectoryPolicyVO policy=null;
        for(StorageDirectoryPolicyVO value:known)
            if(value.getRevision()>0 && Objects.equals(value.getVolumeId(),candidate.getVolumeId())
                && value.getCanonicalPath().equals(observed.get("canonicalpath").getAsString())) policy=value;
        if(selected!=null) {
            StorageDirectoryPolicyVO chosen=policies.findById(selected);
            if(chosen==null || policy==null || chosen.getId()!=policy.getId()) throw new InvalidParameterValueException("DIRECTORY_POLICY_PATH_MISMATCH");
        }
        if(previous!=null && selected==null && (policy==null || !policy.getUuid().equals(previous)))
            throw new InvalidParameterValueException("DIRECTORY_POLICY_PATH_MISMATCH");
        boolean cross=false;
        for(JsonElement ref:observed.getAsJsonArray("references"))
            if(!candidate.getProtocol().name().equals(ref.getAsJsonObject().get("protocol").getAsString())) cross=true;
        if(cross && policy==null) throw new InvalidParameterValueException("DIRECTORY_POLICY_REQUIRED");
        if(policy!=null) {
            if(posixSpecified) throw new InvalidParameterValueException("DIRECTORY_POLICY_MANAGED");
            if(!observed.has("policy") || !policy.getUuid().equals(observed.getAsJsonObject("policy").get("uuid").getAsString())
                || policy.getRevision()!=observed.getAsJsonObject("policy").get("revision").getAsLong()
                || policy.getOwnerUid()!=observed.get("owneruid").getAsLong() || policy.getOwnerGid()!=observed.get("ownergid").getAsLong()
                || !policy.getMode().equals(observed.get("mode").getAsString()))
                throw new InvalidParameterValueException("DIRECTORY_POLICY_DRIFT");
            config.addProperty("directoryPolicyUuid",policy.getUuid());
            config.addProperty("createDirectory",false);
            if(observed.has("filesystem")) candidate.setFilesystem(observed.get("filesystem").getAsString());
            candidate.setConfigJson(config.toString());
        } else candidate.setConfigJson(originalConfig);
    }
    private StorageDirectoryPolicyResponse response(StorageServiceInstanceVO instance,StorageDirectoryPolicyVO p,JsonObject o,JsonArray refs) {
        StorageDirectoryPolicyResponse v=new StorageDirectoryPolicyResponse();v.setInstanceid(instance.getUuid());v.setReferences(refs);
        if(p!=null) {
            v.setId(p.getUuid());v.setVolumeid(volumes.findById(p.getVolumeId())==null?null:volumes.findById(p.getVolumeId()).getUuid());v.setPath(p.getCanonicalPath().equals(".")?"/":"/"+p.getCanonicalPath());
            v.setOwneruid(p.getOwnerUid());v.setOwnergid(p.getOwnerGid());v.setMode(p.getMode());v.setPolicyrevision(p.getRevision());
        } else if(o!=null) {
            if(o.has("volumeuuid")) v.setVolumeid(o.get("volumeuuid").getAsString());
            if(o.has("canonicalpath")) v.setPath(o.get("canonicalpath").getAsString().equals(".")?"/":"/"+o.get("canonicalpath").getAsString());
            else if(o.has("path") && !o.get("path").isJsonNull()) v.setPath(o.get("path").getAsString());
        }
        v.setObservation(o);
        v.setObservationstatus(o==null || !o.has("status") || !"OBSERVED".equals(o.get("status").getAsString())?"UNAVAILABLE":p==null?"UNREGISTERED":
            o.has("policy") && p.getRevision()==o.getAsJsonObject("policy").get("revision").getAsLong()
                && o.has("owneruid") && p.getOwnerUid()==o.get("owneruid").getAsLong()
                && p.getOwnerGid()==o.get("ownergid").getAsLong() && p.getMode().equals(o.get("mode").getAsString())?"APPLIED":"DRIFT");
        return v;
    }
    ListResponse<StorageDirectoryPolicyResponse> list(StorageServiceInstanceVO instance,ListStorageDirectoryPoliciesCmd cmd) {
        List<StorageDirectoryPolicyVO> stored=policies.listByInstanceId(instance.getId());
        List<StorageDirectoryPolicyResponse> result=new ArrayList<>();JsonArray observations;
        List<StorageFileShareVO> current=all(instance.getId());
        try { observations=observations(instance,current); }
        catch(RuntimeException unavailable) {
            observations=new JsonArray();
            for(StorageFileShareVO share:current) {
                JsonObject o=new JsonObject(),ref=new JsonObject();o.addProperty("status","UNAVAILABLE");o.addProperty("path",share.getPath());
                if(share.getVolumeId()!=null && volumes.findById(share.getVolumeId())!=null) o.addProperty("volumeuuid",volumes.findById(share.getVolumeId()).getUuid());
                JsonArray refs=new JsonArray();ref.addProperty("uuid",share.getUuid());ref.addProperty("name",share.getName());ref.addProperty("protocol",share.getProtocol().name());refs.add(ref);o.add("references",refs);observations.add(o);
            }
        }
        Set<String> seen=new HashSet<>();
        for(JsonElement e:observations) {
            JsonObject o=e.getAsJsonObject();StorageDirectoryPolicyVO match=null;
            if(o.has("canonicalpath")) for(StorageDirectoryPolicyVO p:stored)
                if(p.getRevision()>0 && p.getCanonicalPath().equals(o.get("canonicalpath").getAsString())
                    && volumes.findById(p.getVolumeId())!=null && volumes.findById(p.getVolumeId()).getUuid().equals(o.get("volumeuuid").getAsString())) { match=p;seen.add(p.getUuid());break; }
            if(match==null && !o.has("canonicalpath")) {
                for(JsonElement ref:o.getAsJsonArray("references")) for(StorageFileShareVO share:current) {
                    JsonObject config=parsed(share.getConfigJson());
                    if(share.getUuid().equals(ref.getAsJsonObject().get("uuid").getAsString()) && config.has("directoryPolicyUuid"))
                        for(StorageDirectoryPolicyVO p:stored) if(p.getUuid().equals(config.get("directoryPolicyUuid").getAsString())) {match=p;seen.add(p.getUuid());}
                }
            }
            result.add(response(instance,match,o,o.getAsJsonArray("references")));
        }
        for(StorageDirectoryPolicyVO p:stored) if(p.getRevision()>0 && !seen.contains(p.getUuid())) result.add(response(instance,p,null,new JsonArray()));
        int total=result.size(),start=(int)Math.min(total,cmd.getStartIndex()),end=(int)Math.min(total,start+cmd.getPageSizeVal());
        ListResponse<StorageDirectoryPolicyResponse> response=new ListResponse<>();
        response.setResponses(result.subList(start,end),total);return response;
    }
}
