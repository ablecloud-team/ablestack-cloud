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

import java.util.Date;
import com.cloud.kubernetes.cluster.KubernetesCluster;
import com.cloud.kubernetes.cluster.KubernetesClusterManagerImpl;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.LoadBalancerDao;
import com.cloud.network.dao.LoadBalancerVO;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.rules.FirewallRuleVO;
import com.cloud.server.ResourceTag.ResourceObjectType;
import com.cloud.utils.exception.CloudRuntimeException;
import org.junit.Test;
import org.mockito.Mockito;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

public class KubernetesDeletedRuleRetryTest {
    private static final String RESOURCE = "11111111-1111-4111-8111-111111111111";
    private static final String SERVICE = "22222222-2222-4222-8222-222222222222";
    private static final String NETWORK = "33333333-3333-4333-8333-333333333333";
    private static final String IP = "44444444-4444-4444-8444-444444444444";
    private static final String OLD = "55555555-5555-4555-8555-555555555555";
    private static final String CURRENT = "66666666-6666-4666-8666-666666666666";

    private KubernetesClusterDestroyWorker worker() {
        KubernetesCluster c = Mockito.mock(KubernetesCluster.class);
        Mockito.when(c.getAccountId()).thenReturn(13L);
        KubernetesClusterDestroyWorker w = new KubernetesClusterDestroyWorker(c, new KubernetesClusterManagerImpl());
        w.ipAddressDao = Mockito.mock(IPAddressDao.class);
        w.loadBalancerDao = Mockito.mock(LoadBalancerDao.class);
        IPAddressVO ip = Mockito.mock(IPAddressVO.class);
        Mockito.when(ip.getId()).thenReturn(8L);
        Mockito.when(ip.getUuid()).thenReturn(IP);
        Mockito.when(ip.getAccountId()).thenReturn(13L);
        Mockito.when(ip.getAllocatedTime()).thenReturn(new Date());
        Mockito.when(ip.getAllocationGeneration()).thenReturn(CURRENT);
        Mockito.when(ip.getAssociatedWithNetworkId()).thenReturn(9L);
        Mockito.when(w.ipAddressDao.findByUuid(IP)).thenReturn(ip);
        return w;
    }
    private NetworkVO network() {
        NetworkVO n = Mockito.mock(NetworkVO.class);
        Mockito.when(n.getId()).thenReturn(9L);
        Mockito.when(n.getUuid()).thenReturn(NETWORK);
        return n;
    }
    private KubernetesOwnedResourceReceipt receipt(String generation) {
        return new KubernetesOwnedResourceReceipt(ResourceObjectType.LoadBalancer, 7L, RESOURCE, SERVICE, NETWORK, IP, generation);
    }
    private LoadBalancerVO live(KubernetesClusterDestroyWorker w) {
        LoadBalancerVO rule = Mockito.mock(LoadBalancerVO.class);
        Mockito.when(rule.getUuid()).thenReturn(RESOURCE);
        Mockito.when(rule.getAccountId()).thenReturn(13L);
        Mockito.when(rule.getNetworkId()).thenReturn(9L);
        Mockito.when(rule.getSourceIpAddressId()).thenReturn(8L);
        Mockito.when(w.loadBalancerDao.findById(7L)).thenReturn(rule);
        return rule;
    }
    @Test
    public void absentHistoricalRuleDoesNotClaimNewIpAllocation() {
        KubernetesClusterDestroyWorker w = worker();
        w.validateOwnedResource(receipt(OLD), network());
        assertNull(w.findLiveCleanupRule(receipt(OLD)));
    }
    @Test
    public void softDeletedHistoricalRuleDoesNotClaimNewIpAllocation() {
        KubernetesClusterDestroyWorker w = worker();
        LoadBalancerVO rule = live(w);
        Mockito.when(rule.getRemoved()).thenReturn(new Date());
        w.validateOwnedResource(receipt(OLD), network());
        assertNull(w.findLiveCleanupRule(receipt(OLD)));
    }
    @Test
    public void liveOldGenerationRuleStillBlocksBeforeMutation() {
        KubernetesClusterDestroyWorker w = worker();
        live(w);
        try { w.validateOwnedResource(receipt(OLD), network()); fail("live stale allocation must block"); }
        catch (CloudRuntimeException expected) { }
    }
    @Test
    public void replacementRuleUuidStillBlocksEvenWithCurrentAllocation() {
        KubernetesClusterDestroyWorker w = worker();
        LoadBalancerVO rule = live(w);
        Mockito.when(rule.getUuid()).thenReturn(SERVICE);
        try { w.validateOwnedResource(receipt(CURRENT), network()); fail("replacement UUID must be preserved"); }
        catch (CloudRuntimeException expected) { }
    }
    @Test
    public void exactLiveRuleAndCurrentAllocationRemainEligible() {
        KubernetesClusterDestroyWorker w = worker();
        FirewallRuleVO rule = live(w);
        w.validateOwnedResource(receipt(CURRENT), network());
        assertSame(rule, w.findLiveCleanupRule(receipt(CURRENT)));
    }
}
