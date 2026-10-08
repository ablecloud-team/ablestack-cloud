// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.cloudstack.storage.dataservice;

import java.util.List;
import java.util.Map;
import java.util.Set;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.cloud.exception.InvalidParameterValueException;

/** Builds the fixed native public generation without mutating the frozen source or transient credentials. */
public final class StorageRenderedDesiredState {
    public static final Map<StorageServiceInstance.Protocol,String> PROTOCOL_PATHS=Map.of(
            StorageServiceInstance.Protocol.NFS,"desired-state/nfs-export-apply.json",StorageServiceInstance.Protocol.SMB,"desired-state/smb-share-apply.json",
            StorageServiceInstance.Protocol.ISCSI,"iscsi-targets.json",StorageServiceInstance.Protocol.NVME_OF,"nvmeof-subsystems.json");
    public static final Set<String> PATHS=Set.of("desired-state/nfs-export-apply.json","desired-state/smb-share-apply.json","iscsi-targets.json","nvmeof-subsystems.json",
            "posix-directory-policies.json","network-endpoints.json","sharedfs-network.json");
    private StorageRenderedDesiredState() { }
    public static String protocolKey(StorageServiceInstance.Protocol protocol) {return protocol==StorageServiceInstance.Protocol.NVME_OF?"NVMEOF":protocol.name();}
    public static JsonElement redact(JsonElement input) {
        if(input==null || input.isJsonNull())return JsonNull.INSTANCE;
        if(input.isJsonObject()) {
            JsonObject result=new JsonObject();
            for(Map.Entry<String,JsonElement> field:input.getAsJsonObject().entrySet()) {
                String key=field.getKey(),lower=key.toLowerCase(java.util.Locale.ROOT);
                if(Set.of("lastApply","lastApplied","observedAt","generatedEpoch").contains(key))continue;
                if(List.of("password","secret","dhchapkey","dhchapctrlkey","privatekey","keytab","capsule").stream().anyMatch(lower::contains))continue;
                result.add(key,redact(field.getValue()));
            }
            return result;
        }
        if(input.isJsonArray()) {JsonArray result=new JsonArray();for(JsonElement item:input.getAsJsonArray())result.add(redact(item));return result;}
        return input.deepCopy();
    }
    public static JsonObject candidate(JsonObject frozen,Map<StorageServiceInstance.Protocol,JsonObject> desired) {
        if(frozen==null || !frozen.keySet().equals(PATHS) || desired==null || !desired.keySet().equals(PROTOCOL_PATHS.keySet()))throw new InvalidParameterValueException("Rendered generation requires the exact seven source files and four protocol declarations");
        JsonObject result=frozen.deepCopy();
        for(StorageServiceInstance.Protocol protocol:StorageServiceInstance.Protocol.values())result.add(PROTOCOL_PATHS.get(protocol),redact(desired.get(protocol)));
        return result;
    }
    public static JsonObject protocols(JsonObject canonical) {
        if(canonical==null || !canonical.keySet().equals(PATHS))throw new InvalidParameterValueException("Rendered protocol generation has an incomplete canonical source");
        JsonObject result=new JsonObject();for(StorageServiceInstance.Protocol protocol:StorageServiceInstance.Protocol.values())result.add(protocolKey(protocol),canonical.get(PROTOCOL_PATHS.get(protocol)).deepCopy());return result;
    }
}
