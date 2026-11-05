// Licensed under the Apache License, Version 2.0. See NOTICE.
// http://www.apache.org/licenses/LICENSE-2.0
package org.apache.cloudstack.storage.dataservice;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import com.cloud.exception.InvalidParameterValueException;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Public ACL patch shared by UI planning and the actual API command; no caller-selected execution mode. */
final class StorageNfsClientPolicy {
    private static final List<String> FIELDS = Arrays.asList("principal", "principals", "principaltype", "permission",
            "rootsquash", "allsquash", "anonuid", "anongid", "sync", "secure", "inheritpolicy");
    static boolean aclApi(String api) {
        return Arrays.asList("createStorageNfsAcl", "updateStorageNfsAcl", "deleteStorageNfsAcl").contains(api);
    }
    static JsonObject fields(Map<String,Object> request) {
        JsonObject value = new JsonObject();
        for (String name : FIELDS) if (request.get(name) != null) value.add(name,new Gson().toJsonTree(request.get(name)));
        return patch(value);
    }
    static JsonObject patch(JsonObject value) {
        if (value == null || !FIELDS.containsAll(value.keySet())) throw invalid();
        JsonObject result = new JsonObject();
        for (String name : FIELDS) {
            JsonElement field = value.get(name);
            if (field == null || field.isJsonNull()) continue;
            if (!field.isJsonPrimitive()) throw invalid();
            if (Arrays.asList("rootsquash","allsquash","sync","secure","inheritpolicy").contains(name)) {
                if (!field.getAsJsonPrimitive().isBoolean()) throw invalid();
                result.addProperty(name,field.getAsBoolean());
            } else if (Arrays.asList("anonuid","anongid").contains(name)) {
                if (!field.getAsJsonPrimitive().isNumber()) throw invalid();
                double number=field.getAsDouble();
                if (!Double.isFinite(number) || number<0 || number>65535 || number!=Math.floor(number)) throw invalid();
                result.addProperty(name,(int)number);
            } else {
                if (!field.getAsJsonPrimitive().isString() || field.getAsString().length()>4096) throw invalid();
                String text=field.getAsString();
                if (Arrays.asList("permission","principaltype").contains(name)) text=text.toUpperCase(Locale.ROOT);
                if (name.equals("permission") && !Arrays.asList("READ_ONLY","READ_WRITE").contains(text)) throw invalid();
                if (name.equals("principaltype") && !Arrays.asList("CIDR","IP_ADDRESS").contains(text)) throw invalid();
                result.addProperty(name,text);
            }
        }
        return result;
    }
    static JsonObject query(String api,JsonObject intent,StorageFileShareVO share,StorageAccessRuleVO rule) {
        if (!aclApi(api) || share==null || share.getProtocol()!=StorageServiceInstance.Protocol.NFS
                || share.getState()!=StorageServiceInstance.ResourceState.Ready || !intent.has("nfsclientpolicy")) return null;
        if (intent.has("recursive") && intent.get("recursive").getAsBoolean()
                || intent.has("posixchanges") && intent.getAsJsonObject("posixchanges").size()>0
                || intent.has("volumeid") || intent.has("newvolume") || intent.has("relativepath")) return null;
        if (!api.startsWith("create") && (rule==null || rule.getState()!=StorageServiceInstance.ResourceState.Ready)) return null;
        JsonObject result=new JsonObject();result.addProperty("api",api);result.addProperty("resourceuuid",share.getUuid());
        if (rule!=null) result.addProperty("ruleuuid",rule.getUuid());
        result.add("patch",patch(intent.getAsJsonObject("nfsclientpolicy")));
        return result;
    }
    static boolean online(JsonObject report) {
        return report.has("impact") && report.get("impact").isJsonObject()
                && report.getAsJsonObject("impact").has("method")
                && "NFS_CLIENT_RELOAD".equals(report.getAsJsonObject("impact").get("method").getAsString());
    }
    private static InvalidParameterValueException invalid() {
        return new InvalidParameterValueException("NFS_CLIENT_INVALID_INTENT");
    }
}
