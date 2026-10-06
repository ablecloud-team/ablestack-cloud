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

import java.util.Set;

import org.junit.Assert;
import org.junit.Test;

import com.google.gson.JsonObject;

public class KubernetesNodeLoadBalancerDrainTest {
    static final String NODE_UID = "7b4bc911-7746-412d-86df-8ad8e7f5fb35";
    static final String SERVICE_UID = "3faf8936-dfd9-4af2-8f72-28617f138f9f";
    static final String PREFIX = "a3faf8936dfd94af28f7228617f138f9";

    static String node(String uid, String previous) {
        JsonObject metadata = new JsonObject();
        metadata.addProperty("name", "worker-3");
        metadata.addProperty("uid", uid);
        JsonObject labels = new JsonObject();
        if (previous != null) {
            labels.addProperty(KubernetesNodeLoadBalancerDrain.EXCLUSION_LABEL, previous);
        }
        metadata.add("labels", labels);
        JsonObject node = new JsonObject();
        node.add("metadata", metadata);
        return node.toString();
    }

    static String services() {
        return "{\"items\":[{\"spec\":{\"type\":\"LoadBalancer\"},\"metadata\":{\"uid\":\"" + SERVICE_UID + "\"}}]}";
    }

    @Test
    public void testServiceOwnershipUsesExactUidAndRuleNameBoundary() {
        KubernetesNodeLoadBalancerDrain.Snapshot snapshot = KubernetesNodeLoadBalancerDrain.parseSnapshot("worker-3", node(NODE_UID, null), services());
        Assert.assertEquals(Set.of(PREFIX), snapshot.serviceRulePrefixes);
        Assert.assertTrue(KubernetesNodeLoadBalancerDrain.ownsRule(PREFIX + "-tcp-18087", snapshot.serviceRulePrefixes));
        Assert.assertTrue(KubernetesNodeLoadBalancerDrain.ownsRule(PREFIX, snapshot.serviceRulePrefixes));
        Assert.assertFalse(KubernetesNodeLoadBalancerDrain.ownsRule(PREFIX + "unrelated", snapshot.serviceRulePrefixes));
        Assert.assertFalse(KubernetesNodeLoadBalancerDrain.ownsRule("manual-lb", snapshot.serviceRulePrefixes));
    }

    @Test
    public void testClusterIpServiceDoesNotCreateAnOwnershipPrefix() {
        String services = "{\"items\":[{\"spec\":{\"type\":\"ClusterIP\"},\"metadata\":{}}]}";
        Assert.assertTrue(KubernetesNodeLoadBalancerDrain.parseSnapshot("worker-3", node(NODE_UID, null), services).serviceRulePrefixes.isEmpty());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testUnexpectedNodeNameIsRejected() {
        KubernetesNodeLoadBalancerDrain.parseSnapshot("other-node", node(NODE_UID, null), services());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNonCanonicalServiceUidIsRejected() {
        KubernetesNodeLoadBalancerDrain.parseSnapshot("worker-3", node(NODE_UID, null), services().replace(SERVICE_UID, "1-2-3-4-5"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testMissingServiceListDoesNotMeanNoLoadBalancers() {
        KubernetesNodeLoadBalancerDrain.parseSnapshot("worker-3", node(NODE_UID, null), "{}");
    }

    @Test
    public void testRestorationPreservesEmptyExistingLabel() {
        KubernetesNodeLoadBalancerDrain.Snapshot snapshot = KubernetesNodeLoadBalancerDrain.parseSnapshot("worker-3", node(NODE_UID, ""), services());
        String command = KubernetesNodeLoadBalancerDrain.labelPatchCommand("worker-3", snapshot, false);
        Assert.assertTrue(command.contains("\"op\":\"add\""));
        Assert.assertTrue(command.contains("\"value\":\"\""));
        Assert.assertTrue(command.contains("\"value\":\"" + KubernetesNodeLoadBalancerDrain.EXCLUSION_MARKER + "\""));
    }

    @Test
    public void testRestorationRemovesOnlyAnOriginallyAbsentLabel() {
        KubernetesNodeLoadBalancerDrain.Snapshot snapshot = KubernetesNodeLoadBalancerDrain.parseSnapshot("worker-3", node(NODE_UID, null), services());
        String command = KubernetesNodeLoadBalancerDrain.labelPatchCommand("worker-3", snapshot, false);
        Assert.assertTrue(command.contains("\"op\":\"remove\""));
        Assert.assertTrue(command.contains("\"path\":\"/metadata/uid\""));
        Assert.assertTrue(command.contains(NODE_UID));
        Assert.assertTrue(command.contains("labels/node.kubernetes.io~1exclude-from-external-load-balancers"));
    }

    @Test
    public void testReplacementNodeOrConcurrentLabelOwnerCannotBeRemoved() {
        KubernetesNodeLoadBalancerDrain.Snapshot snapshot = KubernetesNodeLoadBalancerDrain.parseSnapshot("worker-3", node(NODE_UID, null), services());
        Assert.assertTrue(KubernetesNodeLoadBalancerDrain.stillOwnsExclusion("worker-3", snapshot,
                node(NODE_UID, KubernetesNodeLoadBalancerDrain.EXCLUSION_MARKER)));
        Assert.assertFalse(KubernetesNodeLoadBalancerDrain.stillOwnsExclusion("worker-3", snapshot,
                node(SERVICE_UID, KubernetesNodeLoadBalancerDrain.EXCLUSION_MARKER)));
        Assert.assertFalse(KubernetesNodeLoadBalancerDrain.stillOwnsExclusion("worker-3", snapshot, node(NODE_UID, "another-owner")));
    }
    @Test
    public void testExclusionPatchChecksOriginalLabelsBeforeChangingThem() {
        KubernetesNodeLoadBalancerDrain.Snapshot snapshot = KubernetesNodeLoadBalancerDrain.parseSnapshot("worker-3", node(NODE_UID, null), services());
        String command = KubernetesNodeLoadBalancerDrain.labelPatchCommand("worker-3", snapshot, true);
        Assert.assertTrue(command.contains("\"path\":\"/metadata/labels\",\"value\":{}"));
        Assert.assertTrue(command.indexOf("/metadata/labels\"") < command.indexOf("\"op\":\"add\""));
    }

}
