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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import java.util.function.Function;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.EnableStorageServiceProtocolCmd;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** One authenticated empty NFS enable may have introduced only its primary endpoint cache. */
final class StorageInitialPrimaryEndpointRecovery {
    private StorageInitialPrimaryEndpointRecovery() { }

    static JsonObject authenticate(long instanceId,String primary,String fingerprint,Function<BaseCmd,String> authenticate) {
        need(fingerprint!=null && fingerprint.matches("API_INTENT_HMAC_V1:[a-f0-9]{64}"));
        JsonObject matched=null;int matches=0;
        // Exactly 18 public candidates. No secondary address, custom port, AD approval or credential candidates.
        for(boolean explicitPort:new boolean[]{false,true})for(String mode:new String[]{null,"V4_ONLY","V3V4_DUAL"})
            for(String mapping:new String[]{null,"NAME_DOMAIN","NUMERIC"}) {
                JsonObject parameters=new JsonObject();parameters.addProperty("instanceid",instanceId);parameters.addProperty("protocol","NFS");
                parameters.addProperty("listenip",primary);if(explicitPort)parameters.addProperty("port",2049);
                if(mode!=null)parameters.addProperty("protocolmode",mode);if(mapping!=null)parameters.addProperty("idmappingmode",mapping);
                BaseCmd candidate=StorageConfigCommandBinding.bind(EnableStorageServiceProtocolCmd.class,parameters);
                if(MessageDigest.isEqual(fingerprint.getBytes(StandardCharsets.UTF_8),authenticate.apply(candidate).getBytes(StandardCharsets.UTF_8))) {
                    matches++;matched=parameters;
                }
            }
        need(matches==1);return matched;
    }

    static boolean emptyEnabledReplayDrift(JsonObject frozen,JsonObject status) {
        try {
            JsonObject source=frozen.getAsJsonObject("configurationDesiredState"),current=status.getAsJsonObject("configurationDesiredState");
            JsonObject old=source.getAsJsonObject("desired-state/nfs-export-apply.json"),now=current.getAsJsonObject("desired-state/nfs-export-apply.json");
            if(!source.get("network-endpoints.json").isJsonNull() || !current.get("network-endpoints.json").isJsonNull()
                    || !flag(old,"enabled",false) || !flag(now,"enabled",true)
                    || !old.has("exports") || !old.get("exports").isJsonArray() || old.getAsJsonArray("exports").size()!=0)return false;
            JsonObject disabled=now.deepCopy();disabled.addProperty("enabled",false);
            return StorageUnchangedVerifiedSourceRecovery.same(old,disabled);
        } catch(RuntimeException unknown) {return false;}
    }

    static void requireCanonical(JsonObject frozen,JsonObject status,String primary) {
        JsonObject source=frozen.getAsJsonObject("configurationDesiredState"),current=status.getAsJsonObject("configurationDesiredState");
        need(source!=null && current!=null && source.keySet().equals(StorageRenderedDesiredState.PATHS) && current.keySet().equals(source.keySet()));
        need(source.get("network-endpoints.json").isJsonNull());
        for(String path:source.keySet())if(!Set.of("network-endpoints.json","desired-state/nfs-export-apply.json").contains(path))
            need(StorageUnchangedVerifiedSourceRecovery.same(source.get(path),current.get(path)));
        JsonObject nfs=source.getAsJsonObject("desired-state/nfs-export-apply.json");
        need(nfs!=null && flag(nfs,"enabled",false) && nfs.has("exports") && nfs.get("exports").isJsonArray() && nfs.getAsJsonArray("exports").size()==0);
        if(current.get("network-endpoints.json").isJsonNull()) {
            need(emptyEnabledReplayDrift(frozen,status));
        } else {
            need(StorageUnchangedVerifiedSourceRecovery.same(nfs,current.get("desired-state/nfs-export-apply.json")));
            JsonObject endpoint=current.getAsJsonObject("network-endpoints.json");
            need(endpoint!=null && endpoint.keySet().equals(Set.of("endpoints")) && endpoint.get("endpoints").isJsonArray() && endpoint.getAsJsonArray("endpoints").size()==1);
            JsonObject row=endpoint.getAsJsonArray("endpoints").get(0).getAsJsonObject();
            need(row.keySet().equals(Set.of("listenIp","primaryIp","port","prefixlen")) && primary.equals(text(row,"listenIp")) && primary.equals(text(row,"primaryIp"))
                    && number(row,"port")==2049 && number(row,"prefixlen")>=0 && number(row,"prefixlen")<=32);
        }
        need(flag(status,"success",true) && flag(status,"generationSupported",true) && "PENDING".equals(text(status,"generationStatus"))
                && StorageUnchangedVerifiedSourceRecovery.same(status.get("generation"),frozen.get("generation"))
                && text(status,"configurationSha256").matches("[a-f0-9]{64}")
                && java.util.UUID.fromString(text(status,"bootId")).toString().equals(text(status,"bootId")));
    }

    /** A historical NFS enable replaced only one existing primary endpoint's port. */
    static void requireExistingPrimaryPortDrift(JsonObject frozen, JsonObject status, String primary) {
        JsonObject source = frozen.getAsJsonObject("configurationDesiredState"), current = status.getAsJsonObject("configurationDesiredState");
        need(source != null && current != null && source.keySet().equals(StorageRenderedDesiredState.PATHS) && current.keySet().equals(source.keySet()));
        need(source.get("desired-state/nfs-export-apply.json").isJsonNull() && current.get("desired-state/nfs-export-apply.json").isJsonNull());
        JsonObject before = source.getAsJsonObject("network-endpoints.json"), after = current.getAsJsonObject("network-endpoints.json");
        need(before != null && after != null && before.has("endpoints") && before.get("endpoints").isJsonArray()
                && after.has("endpoints") && after.get("endpoints").isJsonArray()
                && before.getAsJsonArray("endpoints").size() == after.getAsJsonArray("endpoints").size());
        JsonObject normalized = current.deepCopy();
        java.util.Set<String> seen = new java.util.HashSet<>();
        int changed = 0;
        for (int i = 0; i < before.getAsJsonArray("endpoints").size(); i++) {
            JsonObject old = before.getAsJsonArray("endpoints").get(i).getAsJsonObject();
            JsonObject now = normalized.getAsJsonObject("network-endpoints.json").getAsJsonArray("endpoints").get(i).getAsJsonObject();
            need(seen.add(text(old,"listenIp")));
            if (StorageUnchangedVerifiedSourceRecovery.same(old,now)) continue;
            need(primary.equals(text(old,"listenIp")) && primary.equals(text(now,"listenIp"))
                    && primary.equals(text(old,"primaryIp")) && primary.equals(text(now,"primaryIp"))
                    && number(old,"prefixlen") == number(now,"prefixlen") && number(old,"prefixlen") <= 32
                    && number(old,"port") > 0 && number(old,"port") <= 65535 && number(old,"port") != 2049 && number(now,"port") == 2049);
            now.add("port",old.get("port").deepCopy());
            need(StorageUnchangedVerifiedSourceRecovery.same(old,now)); changed++;
        }
        need(changed == 1 && StorageUnchangedVerifiedSourceRecovery.same(source,normalized));
        need(flag(status,"success",true) && flag(status,"generationSupported",true) && "PENDING".equals(text(status,"generationStatus"))
                && StorageUnchangedVerifiedSourceRecovery.same(status.get("generation"),frozen.get("generation"))
                && text(status,"configurationSha256").matches("[a-f0-9]{64}")
                && java.util.UUID.fromString(text(status,"bootId")).toString().equals(text(status,"bootId")));
    }

    static void requireNfsAbsent(JsonObject health,JsonObject inventory,double now) {
        for(JsonObject observation:new JsonObject[]{health,inventory})need(observation.has("generatedEpoch") && observation.get("generatedEpoch").isJsonPrimitive() && observation.get("generatedEpoch").getAsJsonPrimitive().isNumber());
        StorageRecoveryObservation.requireFresh(health,now);StorageRecoveryObservation.requireFresh(inventory,now);
        need(flag(health,"success",true) && flag(inventory,"success",true) && "ok".equals(text(health,"status")) && "ok".equals(text(inventory,"status")));
        if(health.has("cached"))need(flag(health,"cached",false));if(inventory.has("cached"))need(flag(inventory,"cached",false));
        JsonObject nfs=health.getAsJsonObject("nfsGanesha"),ports=health.getAsJsonObject("listenPorts");
        need(nfs!=null && number(nfs,"active")==0 && number(nfs,"configured")==0 && flag(nfs,"listening",false)
                && nfs.has("endpoints") && nfs.get("endpoints").isJsonArray() && nfs.getAsJsonArray("endpoints").size()==0
                && ports!=null && flag(ports,"nfs",false));
        JsonObject services=health.getAsJsonObject("services");need(services!=null && "inactive".equals(text(services,"nfs")));
        for(String key:Set.of("nfsExports","nfsGaneshaExports"))need(inventory.has(key) && inventory.get(key).isJsonArray() && inventory.getAsJsonArray(key).size()==0);
        // Other protocols are neither declared absent nor changed by this canonical-only normalization.
    }

    private static boolean flag(JsonObject value,String key,boolean expected) {
        JsonElement item=value.get(key);return item!=null && item.isJsonPrimitive() && item.getAsJsonPrimitive().isBoolean() && item.getAsBoolean()==expected;
    }
    private static String text(JsonObject value,String key) {
        JsonElement item=value.get(key);need(item!=null && item.isJsonPrimitive() && item.getAsJsonPrimitive().isString());return item.getAsString();
    }
    private static long number(JsonObject value,String key) {
        JsonElement item=value.get(key);need(item!=null && item.isJsonPrimitive() && item.getAsJsonPrimitive().isNumber() && item.getAsString().matches("[0-9]+"));return Long.parseLong(item.getAsString());
    }
    private static void need(boolean condition) {if(!condition)throw new CloudRuntimeException("Initial primary endpoint recovery requires its exact authenticated empty NFS SOURCE");}
}
