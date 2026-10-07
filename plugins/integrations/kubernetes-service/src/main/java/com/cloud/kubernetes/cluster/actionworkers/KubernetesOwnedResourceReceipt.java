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

import java.util.Map;
import java.util.UUID;
import com.cloud.server.ResourceTag.ResourceObjectType;
import com.cloud.utils.exception.CloudRuntimeException;

/**
 * A compact durable receipt. IP pool UUID alone never authorizes release.
 * CCM receipts use the native Service UID; manager-created native receipts use
 * the cluster UID in that slot and live under the separate cleanup.native prefix.
 */
final class KubernetesOwnedResourceReceipt {
    static final String CLUSTER = "mold.k8s.cluster-uid";
    static final String SERVICE = "mold.k8s.service-uid";
    static final String NETWORK = "mold.k8s.network-uid";
    static final String GENERATION = "mold.k8s.ip-generation";
    static final String IP = "mold.k8s.ip-uid";
    static final String PREFIX = "cleanup.receipt.";

    final ResourceObjectType type;
    final long id;
    final String resource;
    final String service;
    final String network;
    final String ip;
    final String generation;

    KubernetesOwnedResourceReceipt(ResourceObjectType type, long id, String resource, String service,
            String network, String ip, String generation) {
        if (id <= 0 || !(type == ResourceObjectType.LoadBalancer || type == ResourceObjectType.FirewallRule
                || type == ResourceObjectType.NetworkACL || type == ResourceObjectType.PublicIpAddress || type == ResourceObjectType.PortForwardingRule)) {
            throw new CloudRuntimeException("Unsupported Kubernetes cleanup receipt");
        }
        this.type = type;
        this.id = id;
        this.resource = canonical(resource);
        this.service = canonical(service);
        this.network = canonical(network);
        this.ip = canonical(ip);
        this.generation = canonical(generation);
    }

    static String canonical(String value) {
        if (value == null || !UUID.fromString(value).toString().equals(value)) {
            throw new CloudRuntimeException("Incomplete or invalid Kubernetes ownership receipt");
        }
        return value;
    }

    static KubernetesOwnedResourceReceipt fromTags(ResourceObjectType type, long id, String uuid,
            Map<String, String> tags, String cluster, String network) {
        if (!cluster.equals(tags.get(CLUSTER)) || !network.equals(tags.get(NETWORK))) {
            throw new CloudRuntimeException("Kubernetes cleanup owner or network changed");
        }
        return new KubernetesOwnedResourceReceipt(type, id, uuid, tags.get(SERVICE), network, tags.get(IP), tags.get(GENERATION));
    }

    String key() {
        return PREFIX + type + "." + resource;
    }

    String encode() {
        return type + "|" + id + "|" + resource + "|" + service + "|" + network + "|" + ip + "|" + generation;
    }

    static KubernetesOwnedResourceReceipt decode(String value) {
        String[] parts = value.split("\\|", -1);
        if (parts.length != 7) {
            throw new CloudRuntimeException("Invalid persisted Kubernetes cleanup receipt");
        }
        return new KubernetesOwnedResourceReceipt(ResourceObjectType.valueOf(parts[0]), Long.parseLong(parts[1]),
                parts[2], parts[3], parts[4], parts[5], parts[6]);
    }
}
