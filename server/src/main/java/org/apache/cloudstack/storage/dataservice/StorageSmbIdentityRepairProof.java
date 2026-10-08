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

import java.util.Objects;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Independent descriptor, socket and locking-database evidence precedes an owned SMB restart. */
public final class StorageSmbIdentityRepairProof {
    private StorageSmbIdentityRepairProof() { }
    public static void requirePreflight(JsonObject request, JsonObject observed) {
        requireScope(request, observed);
        require(flag(observed,"smbIdentitySupported") && flag(observed,"ownershipVerified"), "SMB identity ownership/capability is unobserved");
        require(text(observed,"bootId")!=null && text(observed,"configurationSha256")!=null && text(observed,"configurationSha256").matches("[0-9a-f]{64}"), "SMB boot/configuration identity is unavailable");
        JsonObject generation=object(observed,"generation");
        require(text(request,"instanceUuid").equals(text(generation,"instanceUuid")) && number(generation,"revision")==number(request,"revision"), "SMB repair has no exact original generation");
        JsonObject databases=object(observed,"databases");
        for(String key:new String[]{"PASSDB","SECRETS"}) {
            JsonObject file=object(databases,key);
            require(flag(file,"present") && number(file,"device")>=0 && number(file,"inode")>0 && text(file,"path")!=null && text(file,"mode")!=null, "Current protected SMB database identity is unavailable");
        }
        require(observed.has("masters") && observed.get("masters").isJsonArray() && observed.getAsJsonArray("masters").size()>0, "Owned SMB master descriptor observations are unavailable");
        for(JsonElement value:observed.getAsJsonArray("masters")) {
            JsonObject master=value.getAsJsonObject();
            require(number(master,"pid")>0 && startTicks(master)>0 && text(master,"unit")!=null && flag(master,"lockingDatabasesAligned"), "SMB master identity or locking-database observation is unavailable");
        }
        require(observed.has("ownedEndpoints") && observed.get("ownedEndpoints").isJsonArray() && observed.getAsJsonArray("ownedEndpoints").size()>0, "Owned SMB endpoint observations are unavailable");
        JsonObject sessions=object(observed,"sessions");
        require(flag(sessions,"available") && flag(sessions,"lockingDatabasesAligned") && flag(sessions,"safeToRebind"), "SMB session/locking observation cannot authorize restart");
        for(String key:new String[]{"establishedTcpCount","synRecvTcpCount","smbSessionCount","treeConnectionCount","openFileCount","byteLockOpenFileCount"}) require(number(sessions,key)==0, "Live or unknown SMB users/locks prevent rebind");
    }
    public static void requireVerified(JsonObject expected, JsonObject result, JsonObject current) {
        JsonObject scope=object(expected,"scope");requireScope(scope,result);requirePreflight(scope,current);
        require(flag(result,"rebound") && flag(result,"databasesUnchanged") && flag(result,"configurationUnchanged") && result.has("generationAdvanced") && !flag(result,"generationAdvanced"), "SMB rebind did not attest its no-data/no-secret/no-generation-change boundary");
        for(String key:new String[]{"bootId","generation","configurationSha256","databases"}) require(Objects.equals(expected.get(key),current.get(key)), "SMB repair changed the pinned boot/generation/configuration/database identity");
        require(flag(current,"identityDatabaseAligned"), "SMB masters still hold stale authentication database descriptors");
        for(JsonElement value:current.getAsJsonArray("ownedEndpoints")) {
            JsonObject endpoint=value.getAsJsonObject();
            require(flag(endpoint,"listening") && flag(endpoint,"tcpReady") && flag(endpoint,"listenerOwned"), "An owned SMB endpoint did not regain verified readiness");
        }
    }
    public static void requireRestoreSafe(JsonObject request, JsonObject observed) {
        requireScope(request,observed);
        require(flag(observed,"smbIdentitySupported") && flag(observed,"ownershipVerified") && flag(observed,"identityRestoreSafe"), "SMB identity restore requires quiesced, exactly owned descriptors before any database snapshot rollback");
    }
    private static void requireScope(JsonObject request, JsonObject observed) {
        require(flag(observed,"success") && object(observed,"scope").equals(request), "SMB identity observation belongs to another operation scope");
    }
    private static JsonObject object(JsonObject value,String key) {
        require(value!=null && value.has(key) && value.get(key).isJsonObject(), "Required SMB identity evidence is absent: "+key);return value.getAsJsonObject(key);
    }
    private static String text(JsonObject value,String key) {return value!=null && value.has(key) && !value.get(key).isJsonNull()?value.get(key).getAsString():null;}
    private static long number(JsonObject value,String key) {
        try {require(value!=null && value.has(key) && value.get(key).isJsonPrimitive() && value.get(key).getAsJsonPrimitive().isNumber(), "Unknown SMB identity/session counter: "+key);return value.get(key).getAsBigDecimal().longValueExact();}
        catch(ArithmeticException invalid) {throw new CloudRuntimeException("Invalid SMB identity/session counter",invalid);}
    }
    private static long startTicks(JsonObject master) {
        JsonElement value = master == null ? null : master.get("startTicks");
        require(value != null && value.isJsonPrimitive(), "SMB process start ticks are unobserved");
        if (value.getAsJsonPrimitive().isNumber()) return number(master, "startTicks");
        require(value.getAsJsonPrimitive().isString() && value.getAsString().matches("[1-9][0-9]{0,18}"), "SMB process start ticks must be a canonical positive decimal");
        try {return Long.parseLong(value.getAsString());}catch(NumberFormatException overflow){throw new CloudRuntimeException("SMB process start ticks overflow",overflow);}
    }
    private static boolean flag(JsonObject value,String key) {
        return value!=null && value.has(key) && value.get(key).isJsonPrimitive() && value.get(key).getAsJsonPrimitive().isBoolean() && value.get(key).getAsBoolean();
    }
    private static void require(boolean value,String message) {if(!value)throw new CloudRuntimeException(message);}
}
