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
package org.apache.cloudstack.storage.sharedfs;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.*;
/** Exact reviewed DATA identity; attachment/state changes are expected across a preservation retry. */
public final class StorageSharedFsDeletionIdentity {
    private static final List<String> SCOPE=List.of("sharedfsId","sharedfsUuid","vmId","policy","accountId","domainId","zoneId");
    private static final List<String> TUPLE=List.of("id","uuid","type","accountId","domainId","zoneId","poolId","sizeBytes");
    private StorageSharedFsDeletionIdentity() { }
    public static JsonObject freeze(JsonObject scope,JsonArray volumes) {
        JsonObject out=scope(scope);JsonArray validated=new JsonArray();for(JsonObject row:index(volumes).values()){requireOwner(out,row);requireAttachment(out,row);validated.add(row.deepCopy());}
        out.addProperty("schemaVersion",1);out.add("volumes",validated);out.addProperty("planHash",fingerprint(out));return out;
    }
    public static Set<Long> requireCurrent(JsonObject reviewed,JsonObject currentScope,JsonArray currentVolumes) {
        require(reviewed!=null && reviewed.has("schemaVersion") && integer(reviewed,"schemaVersion",false)==1,"Deletion plan schema is unavailable");
        require(scope(reviewed).equals(scope(currentScope)),"Deletion plan belongs to another service/owner/zone/policy");
        require(string(reviewed,"planHash").equals(fingerprint(reviewed)),"Reviewed DATA identity plan changed");
        Map<Long,JsonObject> approved=index(array(reviewed,"volumes")),current=index(currentVolumes);
        require(approved.keySet().equals(current.keySet()),"DATA inventory is new or missing; refresh the deletion plan");
        for(Map.Entry<Long,JsonObject> item:approved.entrySet()){
            JsonObject actual=current.get(item.getKey());require(tuple(item.getValue()).equals(tuple(actual)),"Reviewed DATA UUID/type/owner/domain/zone/pool/size changed");requireOwner(currentScope,actual);requireAttachment(currentScope,actual);
        }
        return Collections.unmodifiableSet(new TreeSet<>(approved.keySet()));
    }
    private static String fingerprint(JsonObject plan) {
        JsonObject canonical=scope(plan);JsonArray tuples=new JsonArray();for(JsonObject row:index(array(plan,"volumes")).values())tuples.add(tuple(row));canonical.add("volumes",tuples);
        try{return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static JsonObject scope(JsonObject original) {
        require(original!=null,"Service scope is unavailable");JsonObject out=new JsonObject();
        for(String key:SCOPE){
            if(key.equals("sharedfsUuid")){out.addProperty(key,uuid(string(original,key)));}
            else if(key.equals("policy")){String policy=string(original,key);require(Set.of("PRESERVE_VOLUMES","DELETE_VOLUMES").contains(policy),"Unknown DATA policy");out.addProperty(key,policy);}
            else if(key.equals("vmId") && isNull(original,key))out.add(key,JsonNull.INSTANCE);
            else out.addProperty(key,integer(original,key,true));
        }
        return out;
    }
    private static JsonObject tuple(JsonObject original) {
        JsonObject out=new JsonObject();for(String key:TUPLE){
            if(key.equals("uuid"))out.addProperty(key,uuid(string(original,key)));
            else if(key.equals("type")){require("DATADISK".equals(string(original,key)),"Deletion inventory contains ROOT or unsupported volume type");out.addProperty(key,"DATADISK");}
            else if(key.equals("poolId") && isNull(original,key))out.add(key,JsonNull.INSTANCE);
            else out.addProperty(key,integer(original,key,true));
        }
        return out;
    }
    private static Map<Long,JsonObject> index(JsonArray rows) {
        require(rows!=null,"DATA inventory is unobserved");Map<Long,JsonObject> out=new TreeMap<>();Set<String> uuids=new HashSet<>();
        for(JsonElement item:rows){require(item.isJsonObject(),"DATA inventory row is invalid");JsonObject row=item.getAsJsonObject(),t=tuple(row);long id=t.get("id").getAsLong();require(out.put(id,row)==null && uuids.add(t.get("uuid").getAsString()),"Duplicate DATA identity");}
        return out;
    }
    private static void requireOwner(JsonObject owner,JsonObject volume) {for(String key:List.of("accountId","domainId","zoneId"))require(integer(owner,key,true)==integer(volume,key,true),"DATA is outside the approved service owner/domain/zone");}
    private static void requireAttachment(JsonObject owner,JsonObject volume) {
        if(isNull(volume,"attachedVmId"))return;long attached=integer(volume,"attachedVmId",true);require(!isNull(owner,"vmId") && integer(owner,"vmId",true)==attached,"Planned DATA belongs to another VM");
    }
    private static boolean isNull(JsonObject row,String key) {require(row.has(key),"Deletion identity field is absent: "+key);return row.get(key).isJsonNull();}
    private static String string(JsonObject row,String key) {JsonElement v=row.get(key);require(v!=null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isString() && !v.getAsString().isBlank(),"Deletion identity string is invalid: "+key);return v.getAsString();}
    private static String uuid(String raw){try {String normalized=UUID.fromString(raw).toString();require(normalized.equalsIgnoreCase(raw),"Deletion identity UUID is invalid");return normalized;}catch(IllegalArgumentException invalid){throw new CloudRuntimeException("Deletion identity UUID is invalid");}}
    private static long integer(JsonObject row,String key,boolean positive){JsonElement v=row.get(key);require(v!=null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber(),"Deletion identity integer is invalid: "+key);try{long n=v.getAsBigDecimal().longValueExact();require(positive?n>0:n>=0,"Deletion identity integer is out of range: "+key);return n;}catch(ArithmeticException invalid){throw new CloudRuntimeException("Deletion identity integer is out of range: "+key);}}
    private static JsonArray array(JsonObject row,String key){require(row.has(key) && row.get(key).isJsonArray(),"Deletion inventory is absent");return row.getAsJsonArray(key);}
    private static void require(boolean condition,String reason){if(!condition)throw new CloudRuntimeException(reason);}
}
