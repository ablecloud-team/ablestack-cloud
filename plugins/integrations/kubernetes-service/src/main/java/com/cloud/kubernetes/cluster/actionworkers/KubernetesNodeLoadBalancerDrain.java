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

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Native Service ownership and optimistic label updates for worker removal. */
final class KubernetesNodeLoadBalancerDrain {
    static final String EXCLUSION_LABEL = "node.kubernetes.io/exclude-from-external-load-balancers";
    static final String EXCLUSION_MARKER = "mold-draining";

    static final class Snapshot {
        final String uid;
        final String previousLabelValue;
        final JsonObject previousLabels;
        final Set<String> serviceRulePrefixes;

        Snapshot(String uid, String previousLabelValue, JsonObject previousLabels, Set<String> serviceRulePrefixes) {
            this.uid = uid;
            this.previousLabelValue = previousLabelValue;
            this.previousLabels = previousLabels;
            this.serviceRulePrefixes = serviceRulePrefixes;
        }
    }

    private KubernetesNodeLoadBalancerDrain() {
    }

    private static String canonicalUid(String value) {
        String canonical = UUID.fromString(value).toString();
        if (!canonical.equalsIgnoreCase(value)) {
            throw new IllegalArgumentException("Invalid Kubernetes resource UID");
        }
        return canonical;
    }

    static Snapshot parseSnapshot(String nodeName, String nodeJson, String serviceJson) {
        JsonObject node = JsonParser.parseString(nodeJson).getAsJsonObject();
        JsonObject metadata = node.getAsJsonObject("metadata");
        if (!nodeName.equals(metadata.get("name").getAsString())) {
            throw new IllegalArgumentException("Unexpected Kubernetes node name");
        }
        String uid = canonicalUid(metadata.get("uid").getAsString());
        JsonObject labels = metadata.getAsJsonObject("labels");
        String previous = labels != null && labels.has(EXCLUSION_LABEL) ? labels.get(EXCLUSION_LABEL).getAsString() : null;
        Set<String> prefixes = new HashSet<>();
        JsonArray services = JsonParser.parseString(serviceJson).getAsJsonObject().getAsJsonArray("items");
        if (services == null) {
            throw new IllegalArgumentException("Missing Kubernetes Service list");
        }
        services.forEach(item -> {
            JsonObject service = item.getAsJsonObject();
            if ("LoadBalancer".equals(service.getAsJsonObject("spec").get("type").getAsString())) {
                String serviceUid = canonicalUid(service.getAsJsonObject("metadata").get("uid").getAsString());
                // The CloudStack provider uses a + Service UID (without hyphens), truncated to 32.
                prefixes.add(("a" + serviceUid.replace("-", "")).substring(0, 32));
            }
        });
        return new Snapshot(uid, previous, labels == null ? new JsonObject() : labels.deepCopy(), prefixes);
    }

    static String nodeReadCommand(String nodeName) {
        return "sudo /usr/bin/timeout --kill-after=5s 25s /opt/bin/kubectl get node "
                + KubernetesClusterScaleWorker.quoteNodeName(nodeName) + " -o json --request-timeout=10s";
    }

    static String serviceReadCommand() {
        return "sudo /usr/bin/timeout --kill-after=5s 25s /opt/bin/kubectl get services --all-namespaces -o json --request-timeout=10s";
    }

    static String labelPatchCommand(String nodeName, Snapshot snapshot, boolean exclude) {
        String path = "/metadata/labels/" + EXCLUSION_LABEL.replace("/", "~1");
        JsonArray patch = new JsonArray();
        JsonObject uidTest = new JsonObject();
        uidTest.addProperty("op", "test");
        uidTest.addProperty("path", "/metadata/uid");
        uidTest.addProperty("value", snapshot.uid);
        patch.add(uidTest);
        if (exclude) {
            // Protect both an existing value and an absent label against concurrent edits.
            JsonObject labelsTest = new JsonObject();
            labelsTest.addProperty("op", "test");
            labelsTest.addProperty("path", "/metadata/labels");
            labelsTest.add("value", snapshot.previousLabels.deepCopy());
            patch.add(labelsTest);
        } else {
            // Do not overwrite a concurrent label owner during error recovery.
            JsonObject ownerTest = new JsonObject();
            ownerTest.addProperty("op", "test");
            ownerTest.addProperty("path", path);
            ownerTest.addProperty("value", EXCLUSION_MARKER);
            patch.add(ownerTest);
        }
        JsonObject update = new JsonObject();
        update.addProperty("op", exclude || snapshot.previousLabelValue != null ? "add" : "remove");
        update.addProperty("path", path);
        if (exclude || snapshot.previousLabelValue != null) {
            update.addProperty("value", exclude ? EXCLUSION_MARKER : snapshot.previousLabelValue);
        }
        patch.add(update);
        return "sudo /usr/bin/timeout --kill-after=5s 25s /opt/bin/kubectl patch node "
                + KubernetesClusterScaleWorker.quoteNodeName(nodeName) + " --type=json --patch "
                + KubernetesClusterScaleWorker.quoteNodeName(patch.toString()) + " --request-timeout=10s";
    }

    static boolean stillOwnsExclusion(String nodeName, Snapshot snapshot, String nodeJson) {
        Snapshot current = parseSnapshot(nodeName, nodeJson, "{\"items\":[]}");
        return snapshot.uid.equals(current.uid) && EXCLUSION_MARKER.equals(current.previousLabelValue);
    }

    static boolean ownsRule(String ruleName, Set<String> prefixes) {
        return ruleName != null && prefixes.stream().anyMatch(prefix -> ruleName.equals(prefix) || ruleName.startsWith(prefix + "-"));
    }
}
