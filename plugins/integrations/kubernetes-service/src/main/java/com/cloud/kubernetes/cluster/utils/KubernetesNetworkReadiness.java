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

package com.cloud.kubernetes.cluster.utils;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Read-only readiness contract for the ISO's supported Calico profile. Cordon is deliberately ignored. */
public final class KubernetesNetworkReadiness {
    public static final String SNAPSHOT_COMMAND = "sudo /opt/bin/kubectl get nodes,pods,leases --all-namespaces --request-timeout=20s -o json";
    public static final String BOOT_COMMAND = "cat /proc/sys/kernel/random/boot_id && awk '{printf \"%.0f\\n\", systime()-$1}' /proc/uptime";

    private KubernetesNetworkReadiness() { }

    static String text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    static JsonObject object(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }

    static JsonArray array(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
    }

    static boolean condition(JsonObject status, String type, String expected) {
        for (JsonElement element : array(status, "conditions")) {
            JsonObject condition = element.getAsJsonObject();
            if (type.equals(text(condition, "type"))) {
                return expected.equals(text(condition, "status"));
            }
        }
        return false;
    }

    public static int readyNodeCount(String snapshot) {
        try {
            int count = 0;
            for (JsonElement element : array(JsonParser.parseString(snapshot).getAsJsonObject(), "items")) {
                JsonObject item = element.getAsJsonObject();
                if ("Node".equals(text(item, "kind")) && condition(object(item, "status"), "Ready", "True")) {
                    count++;
                }
            }
            return count;
        } catch (RuntimeException error) {
            return 0;
        }
    }

    /** null is ready; a stable, credential-free reason describes a failed gate. */
    public static String failureReason(String snapshot, String nodeName, String vmUuid, String bootId, long bootEpoch, long nowEpoch) {
        try {
            if (!bootId.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") || bootEpoch <= 0 || bootEpoch > nowEpoch + 30) {
                return "guest boot identity is unavailable";
            }
            JsonObject node = null;
            JsonObject lease = null;
            Map<String, Boolean> pods = new HashMap<>();
            for (JsonElement element : array(JsonParser.parseString(snapshot).getAsJsonObject(), "items")) {
                JsonObject item = element.getAsJsonObject();
                JsonObject metadata = object(item, "metadata");
                String kind = text(item, "kind");
                String name = text(metadata, "name");
                String namespace = text(metadata, "namespace");
                if ("Node".equals(kind) && nodeName.equals(name)) {
                    if (node != null) { return "duplicate Node identity"; }
                    node = item;
                } else if ("Lease".equals(kind) && "kube-node-lease".equals(namespace) && nodeName.equals(name)) {
                    if (lease != null) { return "duplicate Node lease"; }
                    lease = item;
                } else if ("Pod".equals(kind) && "kube-system".equals(namespace)
                        && nodeName.equals(text(object(item, "spec"), "nodeName"))) {
                    for (JsonElement ownerElement : array(metadata, "ownerReferences")) {
                        JsonObject owner = ownerElement.getAsJsonObject();
                        String ownerName = text(owner, "name");
                        if ("DaemonSet".equals(text(owner, "kind")) && !text(owner, "uid").isEmpty()
                                && ("calico-node".equals(ownerName) || "kube-proxy".equals(ownerName))) {
                            boolean ready = podReady(item, bootEpoch);
                            // Every current Pod of the required DS must be ready; old/terminating Pods cannot mask a replacement.
                            pods.put(ownerName, pods.getOrDefault(ownerName, true) && ready);
                        }
                    }
                }
            }
            if (node == null || !condition(object(node, "status"), "Ready", "True")) { return "Node Ready is not True"; }
            String providerId = text(object(node, "spec"), "providerID");
            if (!providerId.equals("external-cloudstack://" + vmUuid) && !providerId.equals("cloudstack://" + vmUuid)) { return "Node provider identity does not match the managed VM"; }
            if (!bootId.equals(text(object(object(node, "status"), "nodeInfo"), "bootID"))) { return "Node boot identity is stale"; }
            if (!condition(object(node, "status"), "NetworkUnavailable", "False")) { return "Node network is unavailable or unverified"; }
            if (lease == null) { return "Node lease is unavailable"; }
            long renewed = Instant.parse(text(object(lease, "spec"), "renewTime")).getEpochSecond();
            if (renewed < bootEpoch || renewed < nowEpoch - 90 || renewed > nowEpoch + 30) { return "Node lease is stale"; }
            if (!Boolean.TRUE.equals(pods.get("calico-node"))) { return "current-boot Calico DaemonSet Pod is not Ready"; }
            if (!Boolean.TRUE.equals(pods.get("kube-proxy"))) { return "current-boot kube-proxy DaemonSet Pod is not Ready"; }
            return null;
        } catch (RuntimeException error) {
            return "native network readiness response is invalid";
        }
    }

    static boolean podReady(JsonObject pod, long bootEpoch) {
        JsonObject metadata = object(pod, "metadata");
        JsonObject status = object(pod, "status");
        if (!text(metadata, "deletionTimestamp").isEmpty() || !"Running".equals(text(status, "phase"))
                || !condition(status, "Ready", "True")) { return false; }
        JsonArray containers = array(status, "containerStatuses");
        if (containers.size() == 0) { return false; }
        for (JsonElement element : containers) {
            JsonObject container = element.getAsJsonObject();
            if (!"true".equals(text(container, "ready"))) { return false; }
            String started = text(object(object(container, "state"), "running"), "startedAt");
            if (started.isEmpty() || Instant.parse(started).getEpochSecond() < bootEpoch - 5) { return false; }
        }
        return true;
    }
}
