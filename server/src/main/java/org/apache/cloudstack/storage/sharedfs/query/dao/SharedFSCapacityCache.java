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

package org.apache.cloudstack.storage.sharedfs.query.dao;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Instant;

/** Bounded, read-only observations populated by existing health/inventory requests, never list-time guest probes. */
public final class SharedFSCapacityCache {
    private SharedFSCapacityCache() { }
    public static final class Usage {
        public final long usedBytes;
        public final long observedEpoch;
        public final long staleAfterSeconds;
        Usage(long used, long epoch, long stale) { usedBytes=used;observedEpoch=epoch;staleAfterSeconds=stale; }
        public boolean fresh(long now) { return observedEpoch>0 && observedEpoch<=now+30 && now-observedEpoch<=staleAfterSeconds; }
    }
    private static final Map<Long, Map<String, Usage>> cache=new ConcurrentHashMap<>();
    public static void record(long vmId, JsonObject result) {
        if (!result.has("capacitySnapshot") || !result.get("capacitySnapshot").isJsonObject()) return;
        JsonObject snapshot=result.getAsJsonObject("capacitySnapshot");
        try {
            if (!snapshot.has("success") || !snapshot.get("success").getAsBoolean() || !snapshot.has("capacity") || !snapshot.get("capacity").isJsonArray()) return;
            long epoch=(long)snapshot.get("generatedEpoch").getAsDouble();
            long stale=snapshot.has("staleAfterSeconds") ? snapshot.get("staleAfterSeconds").getAsLong() : 15;
            stale=Math.max(1,Math.min(300,stale));
            Map<String,Usage> volumes=new HashMap<>();
            for (JsonElement value:snapshot.getAsJsonArray("capacity")) {
                if (!value.isJsonObject()) continue;
                JsonObject item=value.getAsJsonObject();
                if (!item.has("target") || !item.has("usedBytes") || !item.has("sizeBytes")) continue;
                String target=item.get("target").getAsString();
                String prefix="/srv/ablestack-storage/volumes/";
                if (!target.startsWith(prefix) && item.has("peerPath") && !item.get("peerPath").isJsonNull()) target=item.get("peerPath").getAsString();
                if (!target.startsWith(prefix)) continue;
                String uuid=target.substring(prefix.length()).split("/",2)[0];
                UUID.fromString(uuid);
                long used=item.get("usedBytes").getAsLong(),size=item.get("sizeBytes").getAsLong();
                if (used<0 || size<0 || used>size) continue;
                Usage previous=volumes.get(uuid);
                if (previous==null || used>previous.usedBytes) volumes.put(uuid,new Usage(used,epoch,stale));
                if (volumes.size()>512) return;
            }
            if (cache.size()>=4096 && !cache.containsKey(vmId)) cache.clear();
            cache.put(vmId,Collections.unmodifiableMap(volumes));
        } catch (RuntimeException malformed) {
            // A malformed runtime observation is not authoritative capacity evidence.
        }
    }
    public static Usage get(long vmId,String uuid) { return cache.getOrDefault(vmId,Collections.emptyMap()).get(uuid); }
    public static void invalidate(long vmId) { cache.remove(vmId); }
    public static String observedAt(long epoch) { return Instant.ofEpochSecond(epoch).toString(); }
}
