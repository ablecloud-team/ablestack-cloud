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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Fresh observations are evaluated only for the declared IP and port scope. */
public final class StorageSmbEndpointObservation {
    private StorageSmbEndpointObservation() { }

    public static JsonObject aggregate(JsonObject health, long now) {
        JsonArray scope = new JsonArray();
        if (health != null && health.has("smbRuntime") && health.get("smbRuntime").isJsonObject()
                && health.getAsJsonObject("smbRuntime").has("runtimeEndpoints")) {
            scope = health.getAsJsonObject("smbRuntime").getAsJsonArray("runtimeEndpoints");
        }
        return aggregate(health, scope, now);
    }

    public static JsonObject aggregate(JsonObject health, JsonArray declaredEndpoints, long now) {
        JsonArray observed = new JsonArray();
        String state = "READY";
        for (JsonElement value : declaredEndpoints) {
            JsonObject row = value.getAsJsonObject();
            JsonObject endpoint = project(health, row.has("listenIp") ? row.get("listenIp").getAsString() : null,
                    row.has("port") ? row.get("port").getAsInt() : 445, now);
            endpoint.add("listenIp", row.get("listenIp"));
            endpoint.add("port", row.get("port"));
            observed.add(endpoint);
            String current = endpoint.get("runtimeState").getAsString();
            if ("UNAVAILABLE".equals(current)) state = "UNAVAILABLE";
            else if (!"UNAVAILABLE".equals(state) && !"READY".equals(current)) state = "DEGRADED";
        }
        if (observed.size() == 0) state = "UNAVAILABLE";
        JsonObject result = new JsonObject();
        result.addProperty("runtimeState", state);
        result.addProperty("listenerScope", "SERVICE");
        result.add("endpoints", observed);
        return result;
    }

    public static JsonObject project(JsonObject health, String ip, int port, long now) {
        JsonObject result = new JsonObject();
        result.addProperty("runtimeState", "UNAVAILABLE");
        result.addProperty("listening", false);
        result.addProperty("listenerOwned", false);
        if (health == null || !health.has("generatedEpoch") || health.get("generatedEpoch").isJsonNull()
                || !health.has("smbRuntime") || !health.get("smbRuntime").isJsonObject()) {
            result.addProperty("diagnostic", "Fresh SMB endpoint observation is unavailable");
            return result;
        }
        try {
            double epoch = health.get("generatedEpoch").getAsDouble();
            if (!Double.isFinite(epoch) || Math.abs(now / 1000.0 - epoch) > 30) {
                result.addProperty("diagnostic", "Fresh SMB endpoint observation is unavailable");
                return result;
            }
            JsonObject runtime = health.getAsJsonObject("smbRuntime");
            if (!runtime.has("available") || !runtime.get("available").getAsBoolean()
                    || !runtime.has("runtimeEndpoints") || !runtime.get("runtimeEndpoints").isJsonArray()) {
                result.addProperty("diagnostic", "SMB socket ownership could not be observed");
                return result;
            }
            JsonObject exact = null;
            String wanted = ip == null || ip.isBlank() ? "0.0.0.0" : ip;
            for (JsonElement value : runtime.getAsJsonArray("runtimeEndpoints")) {
                JsonObject endpoint = value.getAsJsonObject();
                if (endpoint.has("listenIp") && wanted.equals(endpoint.get("listenIp").getAsString())
                        && endpoint.has("port") && port == endpoint.get("port").getAsInt()) {
                    if (exact != null) {
                        result.addProperty("diagnostic", "SMB endpoint observation is ambiguous");
                        return result;
                    }
                    exact = endpoint;
                }
            }
            result.add("observedEpoch", health.get("generatedEpoch"));
            if (exact == null) {
                result.addProperty("runtimeState", "DEGRADED");
                result.addProperty("diagnostic", "Declared SMB IP and port are absent from owned runtime listeners");
                return result;
            }
            if (!exact.has("available") || !exact.get("available").getAsBoolean()
                    || !exact.has("listenerOwned") || !exact.has("tcpReady")) {
                result.addProperty("diagnostic", "SMB endpoint ownership or TCP probe is unobserved");
                return result;
            }
            boolean owned = exact.get("listenerOwned").getAsBoolean();
            boolean listening = owned && exact.get("tcpReady").getAsBoolean();
            result.addProperty("listenerOwned", owned);
            result.addProperty("listening", listening);
            result.addProperty("runtimeState", listening ? "READY" : "DEGRADED");
            if (!listening) result.addProperty("diagnostic", "Owned SMB listener or TCP readiness failed");
            return result;
        } catch (RuntimeException malformed) {
            result.addProperty("diagnostic", "SMB endpoint observation is incomplete or malformed");
            return result;
        }
    }
}
