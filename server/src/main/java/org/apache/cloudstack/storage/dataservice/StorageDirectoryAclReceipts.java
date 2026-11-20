// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
// http://www.apache.org/licenses/LICENSE-2.0
package org.apache.cloudstack.storage.dataservice;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.cloudstack.storage.dataservice.dao.StorageDirectoryPolicyDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageFileShareDao;

/** A verified SMB delta joins the existing SQL commit; reads never adopt observed ACLs. */
final class StorageDirectoryAclReceipts {
    private static final Gson JSON = new Gson();
    private static final List<String> TAGS = Arrays.asList("user","group","mask","other");
    private StorageDirectoryAclReceipts() { }
    private static void require(boolean valid) {
        if (!valid) throw new CloudRuntimeException("DIRECTORY_ACL_RECEIPT_MISMATCH");
    }
    private static JsonObject object(JsonObject value,String field) {
        return value.has(field) ? value.getAsJsonObject(field) : new JsonObject();
    }
    private static String key(JsonObject entry) { return entry.get("tag").getAsString()+":"+entry.get("id").getAsString(); }
    private static String bits(int value) {
        return ((value&4)!=0?"r":"-")+((value&2)!=0?"w":"-")+((value&1)!=0?"x":"-");
    }
    static JsonArray overlay(JsonArray input,JsonObject old,JsonObject owned,JsonArray managed,String scope,int mode) {
        Map<String,JsonObject> entries=new HashMap<>();
        for(JsonElement value:input) { JsonObject e=value.getAsJsonObject(); require(!entries.containsKey(key(e))); entries.put(key(e),e.deepCopy()); }
        Set<String> explicit=new HashSet<>();
        for(JsonElement value:managed) {JsonObject e=value.getAsJsonObject();if(scope.equals(e.get("scope").getAsString())) explicit.add(key(e));}
        for(String k:old.keySet()) {
            require(k.matches("[ug]:[0-9]+") && old.get(k).getAsString().matches("[r-][w-][x-]"));
            String full=(k.charAt(0)=='u'?"user:":"group:")+k.substring(2);
            if(!owned.has(k) && !explicit.contains(full)) entries.remove(full);
        }
        if(owned.size()>0 && scope.equals("DEFAULT") && entries.isEmpty()) {
            for(String tag:Arrays.asList("user","group","other")) {
                int n=tag.equals("user")?(mode>>6)&7:tag.equals("group")?(mode>>3)&7:mode&7;
                entries.put(tag+":",entry(tag,"",bits(n)));
            }
        }
        for(String k:owned.keySet()) {
            String permissions=owned.get(k).getAsString();
            require(k.matches("[ug]:[0-9]+") && permissions.matches("[r-][w-][x-]"));
            String tag=k.charAt(0)=='u'?"user":"group",id=k.substring(2),full=tag+":"+id;
            if(explicit.contains(full)) require(entries.containsKey(full) && entries.get(full).get("permissions").getAsString().equals(permissions));
            entries.put(full,entry(tag,id,permissions));
        }
        if(owned.size()>0 && !entries.containsKey("mask:")) entries.put("mask:",entry("mask","",bits((mode>>3)&7)));
        JsonArray result=new JsonArray();
        entries.values().stream().sorted(Comparator.comparingInt((JsonObject e)->TAGS.indexOf(e.get("tag").getAsString()))
            .thenComparing(e->!e.get("id").getAsString().isEmpty())
            .thenComparingLong(e->e.get("id").getAsString().isEmpty()?0:Long.parseLong(e.get("id").getAsString())))
            .forEach(result::add);
        return result;
    }
    private static JsonObject entry(String tag,String id,String permissions) {
        JsonObject result=new JsonObject();result.addProperty("tag",tag);result.addProperty("id",id);result.addProperty("permissions",permissions);return result;
    }
    private static JsonArray array(JsonObject value,String field) {return value.has(field)?value.getAsJsonArray(field):new JsonArray();}

    static void apply(StorageServiceConfigurationPlan plan,StorageServiceConfigurationOperationVO op,JsonObject observation) {
        JsonObject saved=StorageServiceConfigurationCoordinator.parsed(op.getCandidateSnapshot());
        JsonObject command=plan.commands.get("smb share apply");
        if(!"SMB".equals(op.getProtocol()) || StorageServiceConfigurationCoordinator.smbEndpointOperation(op)
                || command!=null && command.has("listenerOnly") && command.get("listenerOnly").getAsBoolean()
                || saved.has("restoreProof") || saved.has("onlineSmbOptions") && saved.get("onlineSmbOptions").getAsBoolean()
                || saved.has("onlineSmbClientAccess") && saved.get("onlineSmbClientAccess").getAsBoolean()
                || !plan.tables.containsKey(StorageDirectoryPolicyDao.class)) return;
        StorageDirectoryPolicyDao dao=plan.dao(StorageDirectoryPolicyDao.class);
        JsonObject baseline=StorageServiceConfigurationCoordinator.parsed(op.getBeforeSnapshot());
        Map<String,StorageDirectoryPolicyVO> originals=new HashMap<>();
        for(JsonElement e:baseline.getAsJsonArray("StorageDirectoryPolicyVO")) {
            StorageDirectoryPolicyVO p=JSON.fromJson(e,StorageDirectoryPolicyVO.class);originals.put(p.getUuid(),p);
        }
        List<StorageFileShareVO> shares=new ArrayList<>(StorageFileSharePaths.all(plan.instanceId,plan.dao(StorageFileShareDao.class)));
        for(JsonElement e:baseline.getAsJsonArray("StorageFileShareVO")) shares.add(JSON.fromJson(e,StorageFileShareVO.class));
        Set<String> expected=new HashSet<>();
        for(StorageDirectoryPolicyVO p:originals.values()) {
            JsonObject meta=StorageDirectoryPolicies.parsed(p.getMetadataJson());
            if(!meta.has("aclPolicyVersion"))continue;
            for(StorageFileShareVO share:shares) if(share.getProtocol()==StorageServiceInstance.Protocol.SMB
                    && Objects.equals(share.getVolumeId(),p.getVolumeId())) {
                JsonObject c=StorageDirectoryPolicies.parsed(share.getConfigJson());
                if(c.has("directoryPolicyUuid") && c.get("directoryPolicyUuid").getAsString().equals(p.getUuid())) expected.add(p.getUuid());
            }
        }
        if(expected.isEmpty())return;
        require(observation.has("directoryAclReceipts"));
        JsonObject proof=observation.getAsJsonObject("directoryAclReceipts");
        require(proof.get("version").getAsInt()==1 && op.getUuid().equals(proof.get("operationid").getAsString())
            && proof.get("baseRevision").getAsLong()==op.getBaseRevision() && proof.get("targetRevision").getAsLong()==op.getTargetRevision()
            && Objects.equals(observation.get("instanceUuid"),proof.get("instanceUuid")));
        Set<String> seen=new HashSet<>();
        for(JsonElement value:proof.getAsJsonArray("entries")) {
            JsonObject before=value.getAsJsonObject().getAsJsonObject("before"),after=value.getAsJsonObject().getAsJsonObject("after");
            String uuid=before.get("uuid").getAsString();require(expected.contains(uuid) && seen.add(uuid));
            StorageDirectoryPolicyVO original=originals.get(uuid),row=dao.findByUuid(uuid);
            require(row!=null && original.getInstanceId()==plan.instanceId);
            JsonObject meta=StorageDirectoryPolicies.parsed(original.getMetadataJson());
            for(String k:Arrays.asList("uuid","volumeuuid","canonicalpath","filesystemuuid","inode","revision","owneruid","ownergid","mode",
                    "aclPolicyVersion","managedEntries","principalBindings")) require(Objects.equals(before.get(k),after.get(k)));
            require(uuid.equals(after.get("uuid").getAsString()) && original.getCanonicalPath().equals(after.get("canonicalpath").getAsString())
                && original.getRevision()==after.get("revision").getAsLong() && original.getOwnerUid()==after.get("owneruid").getAsLong()
                && original.getOwnerGid()==after.get("ownergid").getAsLong() && original.getMode().equals(after.get("mode").getAsString()));
            for(String k:Arrays.asList("filesystemuuid","inode","aclPolicyVersion","managedEntries","principalBindings"))
                require(Objects.equals(meta.get(k),before.get(k)));
            require(shares.stream().anyMatch(s->s.getProtocol()==StorageServiceInstance.Protocol.SMB && Objects.equals(s.getVolumeId(),original.getVolumeId())
                && StorageDirectoryPolicies.parsed(s.getConfigJson()).has("volumeMountPath")
                && StorageDirectoryPolicies.parsed(s.getConfigJson()).get("volumeMountPath").getAsString().equals("/srv/ablestack-storage/volumes/"+after.get("volumeuuid").getAsString())
                && uuid.equals(objectUuid(s))));
            int mode=Integer.parseInt(original.getMode(),8);
            for(String field:Arrays.asList("accessAcl","defaultAcl")) {
                String scope=field.equals("accessAcl")?"ACCESS":"DEFAULT";
                require(overlay(meta.getAsJsonArray(field),object(meta,"ownedacl"),object(before,"ownedacl"),array(meta,"managedEntries"),scope,mode).equals(before.get(field)));
                require(overlay(before.getAsJsonArray(field),object(before,"ownedacl"),object(after,"ownedacl"),array(meta,"managedEntries"),scope,mode).equals(after.get(field)));
            }
            JsonObject updated=StorageDirectoryPolicies.parsed(row.getMetadataJson()).deepCopy();
            for(String field:Arrays.asList("accessAcl","defaultAcl")) updated.add(field,after.get(field).deepCopy());
            updated.add("ownedacl",object(after,"ownedacl").deepCopy());
            updated.addProperty("lastSmbAclOperationUuid",op.getUuid());
            row.setMetadataJson(updated.toString());row.setUpdated(new Date());dao.update(row.getId(),row);
        }
        require(seen.equals(expected));
        saved.add("tables",JsonParser.parseString(plan.snapshot()));op.setCandidateSnapshot(saved.toString());
    }
    private static String objectUuid(StorageFileShareVO share) {
        JsonObject config=StorageDirectoryPolicies.parsed(share.getConfigJson());
        return config.has("directoryPolicyUuid")?config.get("directoryPolicyUuid").getAsString():"";
    }
}
