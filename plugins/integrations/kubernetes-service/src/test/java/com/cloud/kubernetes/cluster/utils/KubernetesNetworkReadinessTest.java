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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Assert;
import org.junit.Test;

public class KubernetesNetworkReadinessTest {
    private static final String BOOT = "ca669e78-5397-41ee-8f40-1b489a1ab0cd";
    private static final long NOW = java.time.Instant.parse("2026-10-07T08:00:00Z").getEpochSecond();

    private JsonObject fixture() {
        String node = "{kind:'Node',metadata:{name:'worker'},spec:{providerID:'external-cloudstack://vm',unschedulable:true},status:{nodeInfo:{bootID:'" + BOOT + "'},conditions:[{type:'Ready',status:'True'},{type:'NetworkUnavailable',status:'False'}]}}";
        String lease = "{kind:'Lease',metadata:{name:'worker',namespace:'kube-node-lease'},spec:{renewTime:'2026-10-07T08:00:00Z'}}";
        JsonArray items = new JsonArray();
        items.add(JsonParser.parseString(node));
        items.add(JsonParser.parseString(lease));
        for (String name : new String[]{"calico-node", "kube-proxy"}) {
            items.add(JsonParser.parseString("{kind:'Pod',metadata:{namespace:'kube-system',ownerReferences:[{kind:'DaemonSet',name:'" + name + "',uid:'ds-uid'}]},spec:{nodeName:'worker'},status:{phase:'Running',conditions:[{type:'Ready',status:'True'}],containerStatuses:[{ready:true,state:{running:{startedAt:'2026-10-07T07:59:30Z'}}}]}}"));
        }
        JsonObject snapshot = new JsonObject();
        snapshot.add("items", items);
        return snapshot;
    }

    private String reason(JsonObject snapshot) {
        return KubernetesNetworkReadiness.failureReason(snapshot.toString(), "worker", "vm", BOOT, NOW - 60, NOW);
    }

    @Test
    public void currentBootNetworkCanBeHealthyWhileOperatorCordoned() {
        Assert.assertNull(reason(fixture()));
        Assert.assertEquals(1, KubernetesNetworkReadiness.readyNodeCount(fixture().toString()));
    }

    @Test
    public void nodeReadyAloneCannotPassMissingOrUnreadyCniAndProxy() {
        for (int index : new int[]{2, 3}) {
            JsonObject absent = fixture();
            absent.getAsJsonArray("items").remove(index);
            Assert.assertNotNull(reason(absent));
            JsonObject unready = fixture();
            unready.getAsJsonArray("items").get(index).getAsJsonObject().getAsJsonObject("status").getAsJsonArray("containerStatuses").get(0).getAsJsonObject().addProperty("ready", false);
            Assert.assertNotNull(reason(unready));
        }
    }

    @Test
    public void staleBootProviderNetworkConditionAndLeaseFailClosed() {
        JsonObject boot = fixture();
        boot.getAsJsonArray("items").get(0).getAsJsonObject().getAsJsonObject("status").getAsJsonObject("nodeInfo").addProperty("bootID", "old");
        Assert.assertNotNull(reason(boot));
        JsonObject provider = fixture();
        provider.getAsJsonArray("items").get(0).getAsJsonObject().getAsJsonObject("spec").addProperty("providerID", "cloudstack://foreign");
        Assert.assertNotNull(reason(provider));
        for (String status : new String[]{"True", "Unknown"}) {
            JsonObject network = fixture();
            network.getAsJsonArray("items").get(0).getAsJsonObject().getAsJsonObject("status").getAsJsonArray("conditions").get(1).getAsJsonObject().addProperty("status", status);
            Assert.assertNotNull(reason(network));
        }
        JsonObject lease = fixture();
        lease.getAsJsonArray("items").get(1).getAsJsonObject().getAsJsonObject("spec").addProperty("renewTime", "2026-10-07T07:57:00Z");
        Assert.assertNotNull(reason(lease));
    }

    @Test
    public void oldBootContainersTerminatingPodsAndPartialReturnFail() {
        for (int index : new int[]{2, 3}) {
            JsonObject old = fixture();
            old.getAsJsonArray("items").get(index).getAsJsonObject().getAsJsonObject("status").getAsJsonArray("containerStatuses").get(0).getAsJsonObject().getAsJsonObject("state").getAsJsonObject("running").addProperty("startedAt", "2026-10-07T07:58:00Z");
            Assert.assertNotNull(reason(old));
            JsonObject deleting = fixture();
            deleting.getAsJsonArray("items").get(index).getAsJsonObject().getAsJsonObject("metadata").addProperty("deletionTimestamp", "2026-10-07T08:00:00Z");
            Assert.assertNotNull(reason(deleting));
        }
        JsonObject missing = fixture();
        missing.getAsJsonArray("items").remove(0);
        Assert.assertNotNull(reason(missing));
    }

    @Test
    public void invalidAndEmptySnapshotsDoNotPass() {
        Assert.assertNotNull(KubernetesNetworkReadiness.failureReason("invalid", "worker", "vm", BOOT, NOW - 60, NOW));
        Assert.assertNotNull(KubernetesNetworkReadiness.failureReason("{}", "worker", "vm", BOOT, NOW - 60, NOW));
        Assert.assertEquals(0, KubernetesNetworkReadiness.readyNodeCount("invalid"));
    }
}
