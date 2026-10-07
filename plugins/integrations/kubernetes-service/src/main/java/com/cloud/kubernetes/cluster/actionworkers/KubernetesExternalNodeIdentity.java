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

package com.cloud.kubernetes.cluster.actionworkers;

import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Resolve external nodes by their CloudStack VM identity, never a display-name guess. */
public final class KubernetesExternalNodeIdentity {
    private KubernetesExternalNodeIdentity() { }

    public static final class NativeNode {
        public final String name;
        public final String uid;
        public final boolean ready;
        NativeNode(String name, String uid, boolean ready) {
            this.name = name; this.uid = uid; this.ready = ready;
        }
    }

    public static NativeNode find(String nodeList, String vmUuid) {
        if (vmUuid == null || vmUuid.isBlank()) {
            throw new CloudRuntimeException("External Kubernetes VM identity is missing");
        }
        try {
            JsonObject list = JsonParser.parseString(nodeList).getAsJsonObject();
            NativeNode match = null;
            for (JsonElement element : list.getAsJsonArray("items")) {
                JsonObject node = element.getAsJsonObject();
                JsonObject spec = node.getAsJsonObject("spec");
                if (spec == null || !spec.has("providerID")) { continue; }
                String providerId = spec.get("providerID").getAsString();
                if (!(providerId.startsWith("external-cloudstack://") || providerId.startsWith("cloudstack://"))
                        || !vmUuid.equalsIgnoreCase(providerId.substring(providerId.lastIndexOf('/') + 1))) { continue; }
                if (match != null) { throw new IllegalArgumentException("ambiguous VM identity"); }
                JsonObject metadata = node.getAsJsonObject("metadata");
                String name = metadata.get("name").getAsString();
                String uid = metadata.get("uid").getAsString();
                if (!name.matches("[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?") || name.length() > 253
                        || !uid.matches("[0-9a-fA-F-]{36}")) { throw new IllegalArgumentException("invalid node identity"); }
                boolean ready = false;
                JsonObject status = node.getAsJsonObject("status");
                if (status != null && status.has("conditions")) {
                    for (JsonElement condition : status.getAsJsonArray("conditions")) {
                        JsonObject value = condition.getAsJsonObject();
                        if ("Ready".equals(value.get("type").getAsString()) && "True".equals(value.get("status").getAsString())) {
                            ready = true;
                        }
                    }
                }
                match = new NativeNode(name, uid, ready);
            }
            return match;
        } catch (RuntimeException e) {
            // Do not propagate native output or parser messages into an API response.
            throw new CloudRuntimeException("External Kubernetes native identity query is invalid or ambiguous");
        }
    }
}
