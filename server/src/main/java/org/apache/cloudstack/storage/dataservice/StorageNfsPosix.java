// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
// http://www.apache.org/licenses/LICENSE-2.0
package org.apache.cloudstack.storage.dataservice;

import java.util.Map;
import org.apache.cloudstack.api.command.user.storage.dataservice.BaseStorageServiceConfigurationCmd;
import com.google.gson.JsonObject;

/** Existing NFS parameters describe this operation's POSIX intent, not a render default. */
final class StorageNfsPosix {
    private StorageNfsPosix() { }
    static boolean exportCommand(BaseStorageServiceConfigurationCmd cmd) {
        String name = cmd.getClass().getSimpleName();
        return name.equals("CreateStorageNfsExportCmd") || name.equals("UpdateStorageNfsExportCmd");
    }
    static void requireDeferredCapability(BaseStorageServiceConfigurationCmd cmd, JsonObject capability) {
        if (cmd instanceof org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageNfsExportCmd
                && Boolean.TRUE.equals(((org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageNfsExportCmd)cmd).getDeferApply())
                && (!capability.has("nfsDeferredPosixVersion") || capability.get("nfsDeferredPosixVersion").getAsInt() != 1))
            throw new com.cloud.utils.exception.CloudRuntimeException("NFS deferred creation requires POSIX initialization runtime v1");
    }
    static JsonObject deferredPayload(StorageServiceInstanceVO instance, StorageFileShareVO share) {
        JsonObject value = new JsonObject();
        value.addProperty("instanceUuid", instance.getUuid());
        value.addProperty("posixOnly", true);
        JsonObject row = new JsonObject();
        row.addProperty("uuid", share.getUuid());
        row.addProperty("path", share.getPath());
        row.addProperty("state", "Allocated");
        row.add("config", com.google.gson.JsonParser.parseString(share.getConfigJson()));
        com.google.gson.JsonArray exports = new com.google.gson.JsonArray();
        exports.add(row); value.add("exports", exports);
        return value;
    }
    static JsonObject fields(Map<String, Object> request) {
        JsonObject result = new JsonObject();
        for (String name : new String[]{"owneruid", "ownergid", "mode", "recursivepermission"}) {
            Object value = request.get(name);
            if (value == null || name.equals("recursivepermission") && !Boolean.TRUE.equals(value)) continue;
            String key = name.equals("owneruid") ? "ownerUid" : name.equals("ownergid") ? "ownerGid" :
                    name.equals("recursivepermission") ? "recursivePermission" : "mode";
            result.add(key, new com.google.gson.Gson().toJsonTree(value));
        }
        return result;
    }
    static JsonObject intent(BaseStorageServiceConfigurationCmd cmd, StorageServiceConfigurationOperationVO op, JsonObject report) {
        JsonObject value = new JsonObject();
        value.addProperty("version", 1);
        value.addProperty("targetUuid", op.getResourceUuid());
        value.addProperty("create", cmd.getClass().getSimpleName().equals("CreateStorageNfsExportCmd"));
        value.add("fields", exportCommand(cmd) ? fields(StorageServiceConfigurationCoordinator.publicRequest(cmd)) : new JsonObject());
        if (report.has("posixobservation") && report.get("posixobservation").isJsonObject()) {
            JsonObject observed = report.getAsJsonObject("posixobservation");
            if (observed.has("fingerprint")) value.add("expectedObservation", observed.get("fingerprint"));
        }
        return value;
    }
}
