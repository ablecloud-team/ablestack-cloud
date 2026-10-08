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

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.cloud.utils.exception.CloudRuntimeException;

/** Share references and ACL-scoped transient credentials must resolve through the same public declaration. */
public final class StorageRenderedCredentialBindings {
    private StorageRenderedCredentialBindings() { }
    public static void validateSmb(JsonObject canonical,JsonObject refs,JsonObject transientTarget) {
        JsonElement desired=canonical.get("desired-state/smb-share-apply.json");Map<String,String> aclShares=new HashMap<>();
        JsonObject shares=refs.has("SMB")&&refs.get("SMB").isJsonObject()?refs.getAsJsonObject("SMB"):new JsonObject();
        Set<String> seen=new java.util.HashSet<>();
        if(desired!=null&&desired.isJsonObject())for(JsonElement value:desired.getAsJsonObject().getAsJsonArray("shares")) {
            JsonObject share=value.getAsJsonObject();String uuid=share.get("uuid").getAsString();if(!seen.add(uuid))throw new CloudRuntimeException("Rendered SMB share identity is ambiguous");
            if(!shares.has(uuid)||!shares.get(uuid).isJsonObject())throw new CloudRuntimeException("Rendered SMB share lacks its scoped credential reference");
            for(JsonElement item:share.getAsJsonArray("acls")){JsonObject acl=item.getAsJsonObject();String id=acl.get("uuid").getAsString();if(aclShares.put(id,uuid)!=null)throw new CloudRuntimeException("Rendered SMB ACL identity is shared by multiple resources");}
        }
        for(String ref:shares.keySet())if(!seen.contains(ref))throw new CloudRuntimeException("Rendered SMB credential reference belongs to another share");
        if(transientTarget.has("SMB"))for(Map.Entry<String,JsonElement> entry:transientTarget.getAsJsonObject("SMB").entrySet()) {
            if(!aclShares.containsKey(entry.getKey()) || !entry.getValue().isJsonObject() || !entry.getValue().getAsJsonObject().keySet().equals(Set.of("password")))throw new CloudRuntimeException("Rendered SMB transient credential is not an exact declared ACL input");
            JsonElement password=entry.getValue().getAsJsonObject().get("password");if(!password.isJsonPrimitive()||!password.getAsJsonPrimitive().isString()||password.getAsString().isEmpty())throw new CloudRuntimeException("Rendered SMB credential has an invalid protected input type");
            JsonObject reference=shares.getAsJsonObject(aclShares.get(entry.getKey()));if(!reference.has("targetCredentialVersion")||!reference.get("targetCredentialVersion").isJsonObject())throw new CloudRuntimeException("Rendered new SMB credential has no durable encrypted version reference");
            JsonObject version=reference.getAsJsonObject("targetCredentialVersion");boolean bound=false;if(version.has("aclUuids")&&version.get("aclUuids").isJsonArray())for(JsonElement id:version.getAsJsonArray("aclUuids"))if(entry.getKey().equals(id.getAsString()))bound=true;if(!bound)throw new CloudRuntimeException("Rendered encrypted credential version belongs to another share ACL");
        }
    }
}
