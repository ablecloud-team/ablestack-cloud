// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
// http://www.apache.org/licenses/LICENSE-2.0
package org.apache.cloudstack.storage.dataservice;

import java.util.*;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.*;
import org.apache.cloudstack.api.command.user.storage.dataservice.*;

final class StorageSmbClientAccessPolicy {
    static final String KEY="clientAccess", PREFIX="SMB_CLIENT_ACCESS_";
    static JsonArray normalize(JsonElement raw) {
        if(raw==null || !raw.isJsonArray() || raw.getAsJsonArray().size()>32) invalid("INVALID");
        TreeMap<Long,TreeSet<Integer>> values=new TreeMap<>();
        Set<String> seen=new HashSet<>();
        for(JsonElement item:raw.getAsJsonArray()) {
            if(!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) invalid("INVALID");
            String text=item.getAsString().trim();
            if(text.contains(":"))invalid("IPV6_UNSUPPORTED");
            if(!text.matches("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}(?:/(?:[1-9]|[12][0-9]|3[0-2]))?"))invalid("INVALID");
            String[] pair=text.split("/"),parts=pair[0].split("\\.");
            long ip=0;
            for(String part:parts) { int n=Integer.parseInt(part);if(n>255)invalid("INVALID");ip=(ip<<8)|n; }
            int prefix=pair.length==1?32:Integer.parseInt(pair[1]);
            if(ip==0 && prefix==32 || ip==0xffffffffL || (ip>>>28)==14)invalid("INVALID");
            long network=ip & ((0xffffffffL<<(32-prefix))&0xffffffffL);
            if(!seen.add(network+"/"+prefix))invalid("DUPLICATE");
            values.computeIfAbsent(network,k->new TreeSet<>()).add(prefix);
        }
        JsonArray result=new JsonArray();
        values.forEach((ip,prefixes)->prefixes.forEach(p->result.add(((ip>>>24)&255)+"."+((ip>>>16)&255)+"."+((ip>>>8)&255)+"."+(ip&255)+"/"+p)));
        return result;
    }
    static void invalid(String code) { throw new InvalidParameterValueException(PREFIX+code); }
    static JsonObject fields(Map<String,Object> request) {
        JsonObject result=new JsonObject();Object raw=request.get("clientcidrs"),confirm=request.get("confirmanysource");
        if(raw==null) { if(confirm!=null)invalid("INVALID");return result; }
        if(!(raw instanceof String) || ((String)raw).length()>1024)invalid("INVALID");
        try { result.add("cidrs",normalize(JsonParser.parseString((String)raw))); }
        catch(InvalidParameterValueException e) { throw e; }
        catch(RuntimeException e) { invalid("INVALID"); }
        if(confirm!=null) { if(!(confirm instanceof Boolean))invalid("INVALID");result.addProperty("confirmAnySource",(Boolean)confirm); }
        return result;
    }
    static void validatePatch(JsonObject patch) {
        if(!new HashSet<>(Arrays.asList("cidrs","confirmAnySource")).containsAll(patch.keySet()) || !patch.has("cidrs"))invalid("INVALID");
        normalize(patch.get("cidrs"));
        if(patch.has("confirmAnySource") && (!patch.get("confirmAnySource").isJsonPrimitive() || !patch.getAsJsonPrimitive("confirmAnySource").isBoolean()))invalid("INVALID");
    }
    static JsonArray effective(JsonObject config) {
        if(!config.has(KEY))return new JsonArray();
        JsonElement raw=config.get(KEY);
        if(!raw.isJsonObject()) { invalid("INVALID");return null; }
        JsonObject value=raw.getAsJsonObject();
        if(!value.keySet().equals(new HashSet<>(Arrays.asList("version","cidrs"))) || !value.get("version").isJsonPrimitive()
                || !value.getAsJsonPrimitive("version").isNumber() || !"1".equals(value.get("version").getAsString()))invalid("INVALID");
        return normalize(value.get("cidrs"));
    }
    static boolean restricted(JsonObject config) { return effective(config).size()>0; }
    static String merge(String original,JsonObject patch) {
        JsonObject config=StorageDirectoryPolicies.parsed(original);
        if(patch.size()==0)return config.toString();
        validatePatch(patch);
        JsonArray before=effective(config),after=normalize(patch.get("cidrs"));
        if(before.size()>0 && after.size()==0 && (!patch.has("confirmAnySource") || !patch.get("confirmAnySource").getAsBoolean()))invalid("CONFIRM_ANY_REQUIRED");
        if(after.size()==0)config.remove(KEY);
        else { JsonObject value=new JsonObject();value.addProperty("version",1);value.add("cidrs",after);config.add(KEY,value); }
        return config.toString();
    }
    static boolean only(BaseStorageServiceConfigurationCmd cmd) {
        if(!(cmd instanceof UpdateStorageSmbShareCmd))return false;
        Map<String,Object> values=StorageServiceConfigurationCoordinator.publicRequest(cmd);
        if(values.get("clientcidrs")==null)return false;
        Set<String> allowed=new HashSet<>(Arrays.asList("api","id","clientcidrs","confirmanysource","expectedrevision","impactack","requestid"));
        for(Map.Entry<String,Object> e:values.entrySet())if(e.getValue()!=null && !allowed.contains(e.getKey()))invalid("SEPARATE_REQUEST_REQUIRED");
        fields(values);return true;
    }
    static void requireCapability(JsonObject capability) {
        for(String name:Arrays.asList("smbClientAccessPolicyVersion","smbClientAccessPolicyProtectionVersion"))
            if(!capability.has(name)||capability.get(name).getAsInt()!=1)throw new CloudRuntimeException(PREFIX+"CAPABILITY_REQUIRED");
    }
    static JsonObject query(StorageFileShareVO share,JsonObject intent,boolean publication) {
        JsonObject result=new JsonObject();
        if(!intent.has("smbclientaccess"))return result;
        JsonObject patch=intent.getAsJsonObject("smbclientaccess");validatePatch(patch);
        JsonObject config=share==null?new JsonObject():StorageDirectoryPolicies.parsed(share.getConfigJson());
        merge(config.toString(),patch);
        JsonObject target=new JsonObject();
        target.addProperty("uuid",share==null?"NEW":share.getUuid());
        target.addProperty("name",share==null?"NEW":share.getName());
        target.add("config",config);
        target.addProperty("publicationRequired",publication);
        target.addProperty("verifyCurrent",share!=null);
        JsonObject normalized=patch.deepCopy();normalized.add("cidrs",normalize(patch.get("cidrs")));
        result.add("smbclientaccess",normalized);result.add("smbclientaccesstarget",target);
        return result;
    }
    static void validateListeners(JsonObject payload) {
        if(payload.has("enabled")&&!payload.get("enabled").getAsBoolean())return;
        if(!payload.has("shares") || payload.getAsJsonArray("shares").asList().stream().filter(JsonElement::isJsonObject)
            .map(JsonElement::getAsJsonObject).noneMatch(row->restricted(row.has("config")?row.getAsJsonObject("config"):new JsonObject())))return;
        JsonArray listeners=payload.has("listeners")?payload.getAsJsonArray("listeners"):new JsonArray();
        if(listeners.size()==0)invalid("LISTENER_FAMILY_UNSUPPORTED");
        for(JsonElement listener:listeners) {
            String ip=listener.getAsJsonObject().has("listenIp")?listener.getAsJsonObject().get("listenIp").getAsString():"";
            try { normalize(JsonParser.parseString("[\""+ip+"\"]")); }
            catch(RuntimeException error) { invalid("LISTENER_FAMILY_UNSUPPORTED"); }
            if(ip.equals("0.0.0.0"))invalid("LISTENER_FAMILY_UNSUPPORTED");
        }
    }
}
