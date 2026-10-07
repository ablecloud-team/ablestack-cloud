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

import java.lang.reflect.Field;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import com.cloud.kubernetes.cluster.KubernetesCluster;
import com.cloud.kubernetes.cluster.KubernetesClusterManagerImpl;
import com.cloud.kubernetes.cluster.dao.KubernetesClusterDetailsDao;
import com.cloud.network.IpAddress;
import com.cloud.network.Network;
import com.cloud.network.dao.FirewallRulesDao;
import com.cloud.network.rules.FirewallRule;
import com.cloud.network.rules.FirewallRuleVO;
import com.cloud.network.rules.PortForwardingRuleVO;
import com.cloud.network.rules.dao.PortForwardingRulesDao;
import com.cloud.server.ResourceTag.ResourceObjectType;
import com.cloud.vm.UserVmVO;

public class KubernetesExternalNodeFirewallPlanTest {
    @Test
    public void matchingUncachedIntegerPortsIncludeOwnedFirewallAndExcludeManualOrOtherPorts() throws Exception {
        KubernetesCluster cluster = Mockito.mock(KubernetesCluster.class); Mockito.when(cluster.getId()).thenReturn(1L);
        Network network = Mockito.mock(Network.class); Mockito.when(network.getVpcId()).thenReturn(null); IpAddress ip = Mockito.mock(IpAddress.class); Mockito.when(ip.getId()).thenReturn(90L);
        String owner = "00000000-0000-0000-0000-000000000001", net = "00000000-0000-0000-0000-000000000002";
        String address = "00000000-0000-0000-0000-000000000003", generation = "00000000-0000-0000-0000-000000000004";
        KubernetesOwnedResourceReceipt pfReceipt = new KubernetesOwnedResourceReceipt(ResourceObjectType.PortForwardingRule, 10L,
                "00000000-0000-0000-0000-000000000005", owner, net, address, generation);
        KubernetesOwnedResourceReceipt fwReceipt = new KubernetesOwnedResourceReceipt(ResourceObjectType.FirewallRule, 11L,
                "00000000-0000-0000-0000-000000000006", owner, net, address, generation);
        PortForwardingRuleVO pf = Mockito.mock(PortForwardingRuleVO.class);
        FirewallRuleVO owned = Mockito.mock(FirewallRuleVO.class), manual = Mockito.mock(FirewallRuleVO.class), other = Mockito.mock(FirewallRuleVO.class);
        // Distinct boxed values intentionally reproduce the production reference-comparison defect.
        Integer forwardingPort = new Integer(2225), firewallPort = new Integer(2225);
        Assert.assertNotSame(forwardingPort, firewallPort);
        Mockito.when(pf.getSourcePortStart()).thenReturn(forwardingPort); Mockito.when(pf.getSourcePortEnd()).thenReturn(forwardingPort);
        Mockito.when(owned.getSourcePortStart()).thenReturn(firewallPort); Mockito.when(owned.getSourcePortEnd()).thenReturn(firewallPort);
        Mockito.when(manual.getSourcePortStart()).thenReturn(firewallPort); Mockito.when(manual.getSourcePortEnd()).thenReturn(firewallPort);
        Mockito.when(other.getSourcePortStart()).thenReturn(2226); Mockito.when(other.getSourcePortEnd()).thenReturn(2226);
        KubernetesClusterRemoveWorker worker = new KubernetesClusterRemoveWorker(cluster, Mockito.mock(KubernetesClusterManagerImpl.class)) {
            @Override protected KubernetesOwnedResourceReceipt findOwnedNativeRule(FirewallRule rule, Network n, IpAddress a) {
                if (rule == pf) { return pfReceipt; }
                if (rule == owned) { return fwReceipt; }
                return null;
            }
        };
        worker.kubernetesClusterDetailsDao = Mockito.mock(KubernetesClusterDetailsDao.class);
        worker.portForwardingRulesDao = Mockito.mock(PortForwardingRulesDao.class);
        FirewallRulesDao firewalls = Mockito.mock(FirewallRulesDao.class);
        Field field = KubernetesClusterRemoveWorker.class.getDeclaredField("firewallRulesDao"); field.setAccessible(true); field.set(worker, firewalls);
        UserVmVO vm = Mockito.mock(UserVmVO.class); Mockito.when(vm.getId()).thenReturn(61L); Mockito.when(vm.getUuid()).thenReturn("external-worker");
        Mockito.when(worker.portForwardingRulesDao.listByVm(61L)).thenReturn(List.of(pf));
        Mockito.when(firewalls.listByIpPurposeProtocolAndNotRevoked(90L, FirewallRule.Purpose.Firewall, "tcp")).thenReturn(List.of(owned, manual, other));
        worker.prepareNodeRemovalRules(vm, network, ip);
        ArgumentCaptor<String> captured = ArgumentCaptor.forClass(String.class);
        Mockito.verify(worker.kubernetesClusterDetailsDao).addDetail(Mockito.eq(1L), Mockito.eq("external.remove.rules.external-worker"), captured.capture(), Mockito.eq(false));
        Assert.assertEquals(pfReceipt.encode() + "\n" + fwReceipt.encode(), captured.getValue());
    }
}
