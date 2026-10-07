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

import java.util.HashMap;
import java.util.Map;
import com.cloud.server.ResourceTag.ResourceObjectType;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class KubernetesOwnedResourceReceiptTest {
    private static final String CLUSTER = "11111111-1111-4111-8111-111111111111";
    private static final String SERVICE = "22222222-2222-4222-8222-222222222222";
    private static final String NETWORK = "33333333-3333-4333-8333-333333333333";
    private static final String IP = "44444444-4444-4444-8444-444444444444";
    private static final String GENERATION = "55555555-5555-4555-8555-555555555555";
    private static final String RESOURCE = "66666666-6666-4666-8666-666666666666";

    private Map<String, String> tags() {
        Map<String, String> tags = new HashMap<>();
        tags.put(KubernetesOwnedResourceReceipt.CLUSTER, CLUSTER);
        tags.put(KubernetesOwnedResourceReceipt.SERVICE, SERVICE);
        tags.put(KubernetesOwnedResourceReceipt.NETWORK, NETWORK);
        tags.put(KubernetesOwnedResourceReceipt.IP, IP);
        tags.put(KubernetesOwnedResourceReceipt.GENERATION, GENERATION);
        return tags;
    }

    @Test
    public void everySupportedReceiptRoundTripsWithinTheDetailColumn() {
        for (ResourceObjectType type : new ResourceObjectType[] { ResourceObjectType.LoadBalancer,
                ResourceObjectType.FirewallRule, ResourceObjectType.NetworkACL, ResourceObjectType.PublicIpAddress, ResourceObjectType.PortForwardingRule }) {
            KubernetesOwnedResourceReceipt original = KubernetesOwnedResourceReceipt.fromTags(type, 9L, RESOURCE, tags(), CLUSTER, NETWORK);
            String encoded = original.encode();
            assertTrue(encoded.length() <= 255);
            KubernetesOwnedResourceReceipt restored = KubernetesOwnedResourceReceipt.decode(encoded);
            assertEquals(type, restored.type);
            assertEquals(original.key(), restored.key());
            assertEquals(GENERATION, restored.generation);
            assertEquals(SERVICE, restored.service);
            assertEquals(IP, restored.ip);
        }
    }

    @Test
    public void missingIdentityCannotAuthorizeCleanup() {
        for (String key : tags().keySet()) {
            Map<String, String> incomplete = tags();
            incomplete.remove(key);
            try {
                KubernetesOwnedResourceReceipt.fromTags(ResourceObjectType.LoadBalancer, 9L, RESOURCE, incomplete, CLUSTER, NETWORK);
                fail("Accepted incomplete ownership: " + key);
            } catch (RuntimeException expected) {
                // Fail closed before any service invocation.
            }
        }
    }

    @Test
    public void foreignClusterAndNetworkCannotBeClaimed() {
        for (String key : new String[] { KubernetesOwnedResourceReceipt.CLUSTER, KubernetesOwnedResourceReceipt.NETWORK }) {
            Map<String, String> foreign = tags();
            foreign.put(key, RESOURCE);
            try {
                KubernetesOwnedResourceReceipt.fromTags(ResourceObjectType.LoadBalancer, 9L, RESOURCE, foreign, CLUSTER, NETWORK);
                fail("Accepted foreign ownership");
            } catch (RuntimeException expected) {
                // An exact resource UUID is still insufficient without its owner and network.
            }
        }
    }
}
