// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
// http://www.apache.org/licenses/LICENSE-2.0
package org.apache.cloudstack.storage.dataservice;

import java.nio.charset.StandardCharsets;
import java.util.*;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.storage.VolumeVO;
import com.google.gson.*;
import org.apache.cloudstack.api.command.user.storage.dataservice.BaseStorageServiceConfigurationCmd;
import org.apache.cloudstack.storage.dataservice.dao.StorageFileShareDao;

/** New physical-path mode; public /export/name aliases and legacy requests remain unchanged. */
final class StorageFileSharePaths {
    static final String CAPABILITY = "nestedFileSharePathVersion";
    static final String PROTECTION = "nestedFileSharePathProtectionVersion";
    static JsonObject config(String raw) {
        try { return raw == null || raw.isEmpty() ? new JsonObject() : JsonParser.parseString(raw).getAsJsonObject(); }
        catch (RuntimeException e) { throw new InvalidParameterValueException("DIRECTORY_OBSERVATION_UNAVAILABLE"); }
    }
    static boolean bound(JsonObject c) { return c!=null && c.has("pathBindingVersion") && c.get("pathBindingVersion").getAsInt()==1; }
    static String normalize(String value) {
        if (value==null || value.isEmpty() || value.length()>255 || value.startsWith("/") || value.contains("\\"))
            throw new InvalidParameterValueException("DIRECTORY_RELATIVE_PATH_INVALID");
        for (String part:value.split("/",-1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.getBytes(StandardCharsets.UTF_8).length>255
                    || part.chars().anyMatch(c->c<32 || c==127))
                throw new InvalidParameterValueException("DIRECTORY_RELATIVE_PATH_INVALID");
        }
        return value;
    }
    static String relative(StorageFileShareVO row) {
        JsonObject c=config(row.getConfigJson());
        if (c.has("relativeSharePath") && !c.get("relativeSharePath").getAsString().isEmpty()) return normalize(c.get("relativeSharePath").getAsString());
        String mount=c.has("volumeMountPath")?c.get("volumeMountPath").getAsString():null;
        String path=c.has("backingPath")?c.get("backingPath").getAsString():null;
        if (mount!=null && path!=null) {
            if (path.equals(mount)) return "";
            if (path.startsWith(mount+"/")) return normalize(path.substring(mount.length()+1));
            throw new InvalidParameterValueException("DIRECTORY_POLICY_PATH_MISMATCH");
        }
        return row.getPath()!=null && row.getPath().startsWith("/export/")?normalize(row.getPath().substring(1)):null;
    }
    static boolean child(String value,String parent) {
        return value!=null && parent!=null && !value.equals(parent)
                && (parent.isEmpty() || value.startsWith(parent+"/"));
    }
    static List<StorageFileShareVO> all(long instance,StorageFileShareDao dao) {
        List<StorageFileShareVO> rows=new ArrayList<>();
        for(StorageServiceInstance.Protocol p:Arrays.asList(StorageServiceInstance.Protocol.NFS,StorageServiceInstance.Protocol.SMB))
            rows.addAll(dao.listByInstanceIdAndProtocol(instance,p));
        return rows;
    }
    static void requireNoChildren(StorageFileShareVO parent,List<StorageFileShareVO> rows) {
        String root=relative(parent);
        List<String> names=new ArrayList<>();
        for(StorageFileShareVO row:rows)
            if(row.getId()!=parent.getId() && Objects.equals(row.getVolumeId(),parent.getVolumeId()) && child(relative(row),root))
                names.add(row.getProtocol()+":"+row.getName());
        if(!names.isEmpty())throw new InvalidParameterValueException("DIRECTORY_CHILD_SHARES_EXIST: "+String.join(", ",names));
    }
    static JsonArray relationships(StorageFileShareVO selected,Long volume,String relative,List<StorageFileShareVO> rows) {
        JsonArray result=new JsonArray();
        for(StorageFileShareVO row:rows) {
            if(selected!=null && row.getId()==selected.getId() || !Objects.equals(volume,row.getVolumeId()))continue;
            String other=relative(row);
            if(other==null)throw new InvalidParameterValueException("DIRECTORY_OBSERVATION_UNAVAILABLE");
            String relation=other.equals(relative)?"EXACT_ALIAS":child(relative,other)?"ANCESTOR":child(other,relative)?"DESCENDANT":"SIBLING";
            JsonObject r=new JsonObject();r.addProperty("uuid",row.getUuid());r.addProperty("name",row.getName());
            r.addProperty("protocol",row.getProtocol().name());r.addProperty("relativepath",other);r.addProperty("relation",relation);
            result.add(r);
        }
        return result;
    }
    static String configure(String raw,String requested,VolumeVO volume,String importMode,Boolean create,JsonObject preview) {
        JsonObject c=config(raw);
        if(requested==null && !bound(c))return raw;
        if(volume==null)throw new InvalidParameterValueException("DIRECTORY_VOLUME_REQUIRED");
        String mount="/srv/ablestack-storage/volumes/"+volume.getUuid();
        String path=requested==null?normalize(c.get("relativeSharePath").getAsString()):normalize(requested);
        if(importMode!=null && !Arrays.asList("REUSE_ATTACHED","MOUNT_EXISTING","INSPECT_ONLY").contains(importMode.toUpperCase(Locale.ROOT)))
            throw new InvalidParameterValueException("DIRECTORY_REUSED_VOLUME_FORMAT_FORBIDDEN");
        boolean changed=c.has("relativeSharePath") && !c.get("relativeSharePath").getAsString().equals(path);
        if(changed)c.remove("pathIdentity");
        if(requested==null && c.has("volumeMountPath") && !mount.equals(c.get("volumeMountPath").getAsString()))
            throw new InvalidParameterValueException("DIRECTORY_POLICY_PATH_MISMATCH");
        c.addProperty("pathBindingVersion",1);c.addProperty("relativeSharePath",path);
        c.addProperty("volumeMountPath",mount);c.addProperty("backingPath",mount+"/"+path);
        if(create!=null)c.addProperty("createDirectory",create);
        else if(requested!=null && !bound(config(raw)))c.addProperty("createDirectory",false);
        if(preview!=null && preview.has("exists") && preview.get("exists").getAsBoolean()
                && path.equals(preview.get("relativepath").getAsString()) && volume.getUuid().equals(preview.get("volumeuuid").getAsString())) {
            JsonObject id=new JsonObject();id.add("filesystemUuid",preview.get("filesystemuuid"));
            id.add("volumeRootInode",preview.get("rootinode"));id.add("inode",preview.get("inode"));c.add("pathIdentity",id);
        }
        return c.toString();
    }
    static void guard(StorageFileShareVO candidate,List<StorageFileShareVO> peers) {
        JsonObject c=config(candidate.getConfigJson());
        if(!bound(c))return;
        String rel=relative(candidate);
        for(StorageFileShareVO peer:peers) {
            if(peer.getId()==candidate.getId())continue;
            if(peer.getProtocol()==StorageServiceInstance.Protocol.SMB && candidate.getProtocol()==StorageServiceInstance.Protocol.SMB
                    && peer.getName().equalsIgnoreCase(candidate.getName()))
                throw new InvalidParameterValueException("DIRECTORY_ALIAS_CONFLICT");
            if(Objects.equals(peer.getVolumeId(),candidate.getVolumeId()) && Objects.equals(relative(peer),rel)) {
                JsonObject p=config(peer.getConfigJson());
                if(!p.has("directoryPolicyUuid") || !c.has("directoryPolicyUuid")
                        || !p.get("directoryPolicyUuid").equals(c.get("directoryPolicyUuid")))
                    throw new InvalidParameterValueException("DIRECTORY_POLICY_REQUIRED");
            }
        }
    }
    static void requireCapability(JsonObject capability) {
        if(!capability.has(CAPABILITY) || capability.get(CAPABILITY).getAsInt()!=1
                || !capability.has(PROTECTION) || capability.get(PROTECTION).getAsInt()!=1)
            throw new InvalidParameterValueException("DIRECTORY_PATH_RUNTIME_REQUIRED");
    }
    static void applyBindings(StorageServiceConfigurationPlan plan,StorageServiceConfigurationOperationVO op,JsonObject observation) {
        if(!observation.has("pathbindings")) {
            JsonObject saved=StorageServiceConfigurationCoordinator.parsed(op.getCandidateSnapshot());
            boolean simple=saved.has("onlineSmbOptions") && saved.get("onlineSmbOptions").getAsBoolean();
            if(!simple)for(JsonObject value:plan.commands.values())for(String key:Arrays.asList("exports","shares"))
                if(value.has(key))for(JsonElement e:value.getAsJsonArray(key))
                    if(bound(e.getAsJsonObject().getAsJsonObject("config")) && !Arrays.asList("Disabled","Destroyed","Error").contains(e.getAsJsonObject().get("state").getAsString()))throw new InvalidParameterValueException("DIRECTORY_BINDING_RECEIPT_MISSING");
            return;
        }
        JsonObject receipt=observation.getAsJsonObject("pathbindings");
        if(!op.getUuid().equals(receipt.get("operationid").getAsString()))
            throw new InvalidParameterValueException("DIRECTORY_BINDING_RECEIPT_MISMATCH");
        StorageFileShareDao dao=plan.dao(StorageFileShareDao.class);
        List<StorageFileShareVO> rows=all(plan.instanceId,dao);
        Set<String> received=new HashSet<>();
        for(JsonElement e:receipt.getAsJsonArray("bindings")) {
            JsonObject b=e.getAsJsonObject();String uuid=b.get("resourceuuid").getAsString();
            StorageFileShareVO row=rows.stream().filter(r->r.getUuid().equals(uuid)).findFirst().orElseThrow(()->new InvalidParameterValueException("DIRECTORY_BINDING_RECEIPT_MISMATCH"));
            JsonObject c=config(row.getConfigJson());
            if(!bound(c) || !relative(row).equals(b.get("relativepath").getAsString())
                    || !c.get("volumeMountPath").getAsString().endsWith("/"+b.get("volumeuuid").getAsString()))
                throw new InvalidParameterValueException("DIRECTORY_BINDING_RECEIPT_MISMATCH");
            JsonObject identity=b.getAsJsonObject("pathIdentity");
            if(identity==null || !identity.has("filesystemUuid") || !identity.has("volumeRootInode") || !identity.has("inode"))
                throw new InvalidParameterValueException("DIRECTORY_BINDING_RECEIPT_MISMATCH");
            if(c.has("pathIdentity") && !c.get("pathIdentity").equals(identity))
                throw new InvalidParameterValueException("DIRECTORY_PATH_IDENTITY_CHANGED");
            c.add("pathIdentity",identity.deepCopy());row.setConfigJson(c.toString());dao.update(row.getId(),row);received.add(uuid);
        }
        for(JsonObject command:plan.commands.values())
            for(String key:Arrays.asList("exports","shares"))
                if(command.has(key))for(JsonElement element:command.getAsJsonArray(key)) {
                    JsonObject row=element.getAsJsonObject();
                    if(bound(row.getAsJsonObject("config")) && !Arrays.asList("Disabled","Destroyed","Error").contains(row.get("state").getAsString())
                            && !received.contains(row.get("uuid").getAsString()))
                        throw new InvalidParameterValueException("DIRECTORY_BINDING_RECEIPT_MISSING");
                }
        JsonObject snapshot=StorageServiceConfigurationCoordinator.parsed(op.getCandidateSnapshot());
        snapshot.add("tables",JsonParser.parseString(plan.snapshot()));
        if(snapshot.has("response")) {
            JsonObject response=snapshot.getAsJsonObject("response");
            for(StorageFileShareVO row:rows)if(row.getUuid().equals(op.getResourceUuid()) && response.has("config"))
                response.addProperty("config",row.getConfigJson());
        }
        op.setCandidateSnapshot(snapshot.toString());
    }
    static JsonObject query(String api,JsonObject intent,StorageFileShareVO selected,VolumeVO volume,List<StorageFileShareVO> peers) {
        boolean file=api.endsWith("StorageNfsExport") || api.endsWith("StorageSmbShare");
        JsonObject original=selected==null?new JsonObject():config(selected.getConfigJson());
        boolean requested=file && intent.has("relativepath");
        if(file && api.startsWith("delete") && selected!=null)requireNoChildren(selected,peers);
        boolean affected=selected!=null && peers.stream().anyMatch(r->Objects.equals(r.getVolumeId(),selected.getVolumeId()) && bound(config(r.getConfigJson())));
        if(!requested && !bound(original) && !(file && selected!=null && affected))return new JsonObject();
        if(volume==null)throw new InvalidParameterValueException("DIRECTORY_VOLUME_REQUIRED");
        String relative=requested?normalize(intent.get("relativepath").getAsString()):relative(selected);
        if(relative==null || relative.isEmpty())throw new InvalidParameterValueException("DIRECTORY_RELATIVE_PATH_INVALID");
        JsonObject c=original.deepCopy();
        if(requested && (!original.has("relativeSharePath") || !relative.equals(original.get("relativeSharePath").getAsString())))c.remove("pathIdentity");
        String root="/srv/ablestack-storage/volumes/"+volume.getUuid();
        c.addProperty("relativeSharePath",relative);c.addProperty("volumeMountPath",root);c.addProperty("backingPath",root+"/"+relative);
        c.addProperty("pathBindingVersion",1);
        if(intent.has("createdirectory"))c.add("createDirectory",intent.get("createdirectory"));
        else if(selected==null)c.addProperty("createDirectory",false);
        JsonObject target=new JsonObject();target.addProperty("uuid",selected==null?"NEW":selected.getUuid());target.add("config",c);
        JsonObject result=new JsonObject();result.add("pathtarget",target);
        result.add("pathresources",relationships(selected,volume.getId(),relative,peers));result.addProperty("pathoperation",api);
        return result;
    }

}
