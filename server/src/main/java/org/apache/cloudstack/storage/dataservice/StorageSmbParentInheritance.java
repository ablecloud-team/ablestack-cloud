// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
// http://www.apache.org/licenses/LICENSE-2.0
package org.apache.cloudstack.storage.dataservice;
import java.util.Arrays;
import java.util.HashSet;
import com.cloud.exception.InvalidParameterValueException;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** The public preview and candidate use the same optional-boolean and policy identity contract. */
final class StorageSmbParentInheritance {
    static boolean shareApi(String api) {
        return "createStorageSmbShare".equals(api) || "updateStorageSmbShare".equals(api);
    }
    static void validateIntent(JsonObject value) {
        if (!shareApi(value.get("api").getAsString())) throw new IllegalArgumentException();
        JsonObject intent=value.getAsJsonObject("smbinheritance");
        if (!new HashSet<>(Arrays.asList("inheritOwner","directoryPolicyId")).containsAll(intent.keySet()))
            throw new IllegalArgumentException();
        if (intent.has("inheritOwner") && (!intent.get("inheritOwner").isJsonPrimitive()
                || !intent.getAsJsonPrimitive("inheritOwner").isBoolean())) throw new IllegalArgumentException();
        if (intent.has("directoryPolicyId") && (!intent.get("directoryPolicyId").isJsonPrimitive()
                || intent.get("directoryPolicyId").getAsString().length()>40)) throw new IllegalArgumentException();
    }
    static boolean enabled(JsonObject config) {
        if (!config.has("inheritOwner")) return false;
        JsonElement value=config.get("inheritOwner");
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())
            throw new InvalidParameterValueException("SMB_INHERIT_OWNER_INVALID");
        return value.getAsBoolean();
    }
    static String merge(String current,Boolean requested) {
        JsonObject config=StorageDirectoryPolicies.parsed(current);
        if (requested!=null) config.addProperty("inheritOwner",requested);
        enabled(config);
        return config.toString();
    }
    static void requirePolicy(StorageFileShareVO share) {
        JsonObject config=StorageDirectoryPolicies.parsed(share.getConfigJson());
        if (enabled(config) && !config.has("directoryPolicyUuid"))
            throw new InvalidParameterValueException("DIRECTORY_POLICY_REQUIRED");
    }
    static JsonObject query(StorageServiceInstanceVO instance,StorageFileShareVO selected,JsonObject intent,
            StorageDirectoryPolicies policies) {
        JsonObject config=selected==null?new JsonObject():StorageDirectoryPolicies.parsed(selected.getConfigJson()).deepCopy();
        JsonObject requested=intent.has("smbinheritance")?intent.getAsJsonObject("smbinheritance"):new JsonObject();
        if (!intent.has("smbinheritance") && !config.has("inheritOwner")) return new JsonObject();
        boolean inherit=requested.has("inheritOwner")?requested.get("inheritOwner").getAsBoolean():enabled(config);
        config.addProperty("inheritOwner",inherit);
        JsonElement ref=requested.has("directoryPolicyId")?requested.get("directoryPolicyId"):config.get("directoryPolicyUuid");
        JsonObject result=new JsonObject();JsonObject normalized=new JsonObject();normalized.addProperty("inheritOwner",inherit);
        if (ref!=null && !ref.isJsonNull() && !ref.getAsString().isEmpty()) {
            result=policies.inheritanceTarget(instance,selected,ref);
            normalized.addProperty("directoryPolicyId",result.getAsJsonObject("inheritancepolicy").get("uuid").getAsString());
            result.getAsJsonObject("inheritancetarget").getAsJsonObject("config").addProperty("inheritOwner",inherit);
        } else if (inherit) throw new InvalidParameterValueException("DIRECTORY_POLICY_REQUIRED");
        result.add("smbinheritance",normalized);
        return result;
    }
}
