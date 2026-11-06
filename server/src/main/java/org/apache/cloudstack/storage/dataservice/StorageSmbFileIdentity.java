// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
// http://www.apache.org/licenses/LICENSE-2.0
package org.apache.cloudstack.storage.dataservice;
import java.util.Arrays;
import java.util.Map;
import java.util.HashSet;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.cloud.exception.InvalidParameterValueException;
import org.apache.cloudstack.api.command.user.storage.dataservice.BaseStorageServiceConfigurationCmd;

final class StorageSmbFileIdentity {
    static JsonObject fields(Map<String,Object> request) {
        JsonObject patch=new JsonObject();
        if(request.get("fileidentitymode")!=null)patch.addProperty("mode",String.valueOf(request.get("fileidentitymode")));
        for(String key:Arrays.asList("uid","gid"))if(request.get("fixed"+key)!=null)
            patch.add(key,new Gson().toJsonTree(request.get("fixed"+key)));
        validatePatch(patch);return patch;
    }
    static void validatePatch(JsonObject patch) {
        if(!new HashSet<>(Arrays.asList("mode","uid","gid")).containsAll(patch.keySet()))invalid();
        if(patch.has("mode") && (!patch.get("mode").isJsonPrimitive() || !patch.getAsJsonPrimitive("mode").isString()
                || !Arrays.asList("AUTHENTICATED","FIXED").contains(patch.get("mode").getAsString())))invalid();
        for(String key:Arrays.asList("uid","gid"))if(patch.has(key)) {
            JsonElement v=patch.get(key);
            if(!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber() || !v.getAsString().matches("[0-9]+"))invalid();
            long n=v.getAsLong();if(n<1000 || n>65533 || n>=61184 && n<=65519)
                throw new InvalidParameterValueException("SMB_FIXED_IDENTITY_PROTECTED_ID");
        }
    }
    private static void invalid() {throw new InvalidParameterValueException("SMB_FIXED_IDENTITY_INVALID");}
    static JsonObject effective(JsonObject config) {
        if(!config.has("fileIdentity")) {
            JsonObject ordinary=new JsonObject();ordinary.addProperty("version",1);ordinary.addProperty("mode","AUTHENTICATED");return ordinary;
        }
        if(!config.get("fileIdentity").isJsonObject()) {invalid();}
        JsonObject value=config.getAsJsonObject("fileIdentity").deepCopy();
        if(!value.has("version") || !value.get("version").isJsonPrimitive() || !value.getAsJsonPrimitive("version").isNumber() || !value.get("version").getAsString().equals("1"))invalid();
        value.remove("version");validatePatch(value);
        if(!value.has("mode"))invalid();
        if("FIXED".equals(value.get("mode").getAsString())) {
            if(!value.has("uid") || !value.has("gid"))invalid();
            if(config.has("guestOk") && config.get("guestOk").getAsBoolean())
                throw new InvalidParameterValueException("SMB_FIXED_IDENTITY_GUEST_FORBIDDEN");
            if(config.has("inheritOwner") && config.get("inheritOwner").getAsBoolean())
                throw new InvalidParameterValueException("SMB_PARENT_IDENTITY_CONFLICT");
        } else if(value.has("uid") || value.has("gid"))invalid();
        value.addProperty("version",1);return value;
    }
    static String merge(String original,JsonObject patch) {
        JsonObject config=StorageDirectoryPolicies.parsed(original);
        if(patch.size()==0) {effective(config);return original;}
        JsonObject value=effective(config);
        if(patch.has("mode") && "AUTHENTICATED".equals(patch.get("mode").getAsString())) {
            value.remove("uid");value.remove("gid");
        }
        patch.entrySet().forEach(e->value.add(e.getKey(),e.getValue()));
        config.add("fileIdentity",value);effective(config);return config.toString();
    }
    static boolean fixed(JsonObject config) {return "FIXED".equals(effective(config).get("mode").getAsString());}
    static void requireCapability(JsonObject caps) {
        if(!caps.has("smbFileIdentityVersion") || caps.get("smbFileIdentityVersion").getAsInt()!=1
                || !caps.has("smbFileIdentityProtectionVersion") || caps.get("smbFileIdentityProtectionVersion").getAsInt()!=1)
            throw new InvalidParameterValueException("SMB_FIXED_IDENTITY_CAPABILITY_REQUIRED");
    }
    static JsonObject query(StorageFileShareVO selected,JsonObject intent,JsonObject pathQuery,boolean hasAcl) {
        JsonObject current=selected==null?new JsonObject():StorageDirectoryPolicies.parsed(selected.getConfigJson());
        if(!intent.has("smbfileidentity") && !current.has("fileIdentity"))return new JsonObject();
        JsonObject config=pathQuery.has("pathtarget")?pathQuery.getAsJsonObject("pathtarget").getAsJsonObject("config").deepCopy():current.deepCopy();
        if(intent.has("smbinheritance") && intent.getAsJsonObject("smbinheritance").has("inheritOwner"))
            config.add("inheritOwner",intent.getAsJsonObject("smbinheritance").get("inheritOwner"));
        if(intent.has("guestok"))config.add("guestOk",intent.get("guestok"));
        if(intent.has("smboptions") && intent.getAsJsonObject("smboptions").has("readOnly"))config.add("readOnly",intent.getAsJsonObject("smboptions").get("readOnly"));
        config=StorageDirectoryPolicies.parsed(merge(config.toString(),intent.has("smbfileidentity")?intent.getAsJsonObject("smbfileidentity"):new JsonObject()));
        JsonObject target=new JsonObject();target.addProperty("uuid",selected==null?"NEW":selected.getUuid());
        target.addProperty("name",selected==null?(intent.has("name")?intent.get("name").getAsString():"NEW"):selected.getName());
        target.add("config",config);target.add("currentConfig",current.deepCopy());
        target.addProperty("publicationRequired",hasAcl || intent.has("publicationrequired") && intent.get("publicationrequired").getAsBoolean()
                || intent.get("api").getAsString().contains("Acl") && !intent.get("api").getAsString().startsWith("delete"));
        target.addProperty("verifyCurrent",selected!=null && hasAcl);
        JsonObject result=new JsonObject();result.add("fileidentitytarget",target);
        result.add("smbfileidentity",effective(config));return result;
    }
}
