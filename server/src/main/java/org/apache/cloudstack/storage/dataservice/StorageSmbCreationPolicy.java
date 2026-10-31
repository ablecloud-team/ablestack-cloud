// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
// http://www.apache.org/licenses/LICENSE-2.0
package org.apache.cloudstack.storage.dataservice;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.apache.cloudstack.api.command.user.storage.dataservice.BaseStorageServiceConfigurationCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageSmbShareCmd;

/** One strict octal/null-preserve contract for public admission and SQL candidates. */
final class StorageSmbCreationPolicy {
    static final Map<String,String> DEFAULTS = new LinkedHashMap<>();
    static { DEFAULTS.put("createMask","0660"); DEFAULTS.put("directoryMask","0770");
        DEFAULTS.put("forceCreateMode","0000"); DEFAULTS.put("forceDirectoryMode","0000"); }
    static JsonObject fields(Map<String,Object> request) {
        JsonObject result=new JsonObject();
        for(String key:DEFAULTS.keySet()) if(request.get(key.toLowerCase(java.util.Locale.ROOT))!=null)
            result.add(key,new com.google.gson.Gson().toJsonTree(request.get(key.toLowerCase(java.util.Locale.ROOT))));
        validateFields(result); return result;
    }
    static void validateFields(JsonObject value) {
        if(!DEFAULTS.keySet().containsAll(value.keySet())) throw new InvalidParameterValueException("SMB_CREATION_MODE_INVALID");
        for(JsonElement item:value.asMap().values())
            if(!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString() || !item.getAsString().matches("0?[0-7]{3}"))
                throw new InvalidParameterValueException("SMB_CREATION_MODE_INVALID");
    }
    static JsonObject effective(JsonObject config) {
        JsonObject result=new JsonObject();
        for(Map.Entry<String,String> entry:DEFAULTS.entrySet()) {
            JsonElement item=config.get(entry.getKey());
            if(item!=null && (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()))
                throw new InvalidParameterValueException("SMB_CREATION_MODE_INVALID");
            String raw=item==null?entry.getValue():item.getAsString();
            if(!raw.matches("0?[0-7]{3}")) throw new InvalidParameterValueException("SMB_CREATION_MODE_INVALID");
            result.addProperty(entry.getKey(),String.format("%04o",Integer.parseInt(raw,8)));
        }
        for(String[] pair:new String[][]{{"forceCreateMode","createMask"},{"forceDirectoryMode","directoryMask"}})
            if((Integer.parseInt(result.get(pair[0]).getAsString(),8)&~Integer.parseInt(result.get(pair[1]).getAsString(),8))!=0)
                throw new InvalidParameterValueException("SMB_FORCE_MODE_OUTSIDE_MASK");
        return result;
    }
    static String merge(String original,JsonObject patch) {
        validateFields(patch);
        JsonObject value=StorageDirectoryPolicies.parsed(original);
        patch.entrySet().forEach(e->value.addProperty(e.getKey(),String.format("%04o",Integer.parseInt(e.getValue().getAsString(),8))));
        effective(value); return value.toString();
    }
    static JsonObject options(Map<String,Object> request) {
        JsonObject value=new JsonObject();
        for(String key:Arrays.asList("readOnly","browseable")) {
            Object item=request.get(key.toLowerCase(java.util.Locale.ROOT));
            if(item!=null) value.add(key,new com.google.gson.Gson().toJsonTree(item));
        }
        return value;
    }
    static boolean optionsOnly(BaseStorageServiceConfigurationCmd cmd) {
        if(!(cmd instanceof UpdateStorageSmbShareCmd))return false;
        Map<String,Object> request=StorageServiceConfigurationCoordinator.publicRequest(cmd);
        HashSet<String> allowed=new HashSet<>(Arrays.asList("id","createmask","directorymask","forcecreatemode","forcedirectorymode",
                "readonly","browseable","inheritowner","requestid","expectedrevision","impactack","api"));
        HashSet<String> present=new HashSet<>(); request.forEach((k,v)->{if(v!=null)present.add(k);});
        return allowed.containsAll(present) && present.stream().anyMatch(k->!Arrays.asList("id","requestid","expectedrevision","impactack","api").contains(k));
    }
    static void validateIntent(JsonObject intent) {
        if(!StorageSmbParentInheritance.shareApi(intent.get("api").getAsString()))throw new IllegalArgumentException();
        if(intent.has("smbcreation"))validateFields(intent.getAsJsonObject("smbcreation"));
        if(intent.has("smboptions")) {
            JsonObject options=intent.getAsJsonObject("smboptions");
            if(!new HashSet<>(Arrays.asList("readOnly","browseable")).containsAll(options.keySet()))throw new IllegalArgumentException();
            for(JsonElement v:options.asMap().values())if(!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isBoolean())throw new IllegalArgumentException();
        }
    }
    static boolean requested(BaseStorageServiceConfigurationCmd cmd) {
        return fields(StorageServiceConfigurationCoordinator.publicRequest(cmd)).size()>0;
    }
    static void requireCapability(BaseStorageServiceConfigurationCmd cmd,JsonObject capability) {
        if(!requested(cmd) && !optionsOnly(cmd))return;
        if(!capability.has("smbCreationPolicyVersion") || capability.get("smbCreationPolicyVersion").getAsInt()!=1)
            throw new CloudRuntimeException("SMB_CREATION_POLICY_CAPABILITY_REQUIRED");
        if(!capability.has("smbCreationPolicyProtectionVersion") || capability.get("smbCreationPolicyProtectionVersion").getAsInt()!=1)
            throw new CloudRuntimeException("SMB_CREATION_POLICY_PROTECTION_REQUIRED");
    }
    static JsonObject query(StorageFileShareVO share,JsonObject intent) {
        if(intent.has("smbfullapply") && intent.get("smbfullapply").getAsBoolean() && !intent.has("smbcreation")) return new JsonObject();
        JsonObject patch=new JsonObject();
        if(intent.has("smbinheritance") && intent.getAsJsonObject("smbinheritance").has("inheritOwner")) patch.add("inheritOwner",intent.getAsJsonObject("smbinheritance").get("inheritOwner"));
        if(intent.has("smbcreation"))intent.getAsJsonObject("smbcreation").entrySet().forEach(e->patch.add(e.getKey(),e.getValue()));
        if(intent.has("smboptions"))intent.getAsJsonObject("smboptions").entrySet().forEach(e->patch.add(e.getKey(),e.getValue()));
        if(patch.size()==0)return new JsonObject();
        JsonObject current=share==null?new JsonObject():StorageDirectoryPolicies.parsed(share.getConfigJson());
        JsonObject combined=current.deepCopy(); patch.entrySet().forEach(e->combined.add(e.getKey(),e.getValue())); effective(combined);
        JsonObject result=new JsonObject();result.add("smbcreation",patch);
        if(share!=null) {
            JsonObject target=new JsonObject();target.addProperty("uuid",share.getUuid());target.addProperty("name",share.getName());
            target.addProperty("path",share.getPath());target.add("config",StorageDirectoryPolicies.parsed(share.getConfigJson()));
            result.add("smbcreationtarget",target);
        }
        return result;
    }
}
