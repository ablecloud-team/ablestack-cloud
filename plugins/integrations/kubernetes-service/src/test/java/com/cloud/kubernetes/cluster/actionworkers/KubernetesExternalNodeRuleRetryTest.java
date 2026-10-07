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
import com.cloud.kubernetes.cluster.KubernetesCluster;
import com.cloud.kubernetes.cluster.KubernetesClusterDetailsVO;
import com.cloud.kubernetes.cluster.KubernetesClusterManagerImpl;
import com.cloud.kubernetes.cluster.dao.KubernetesClusterDetailsDao;
import com.cloud.network.IpAddress;
import com.cloud.network.Network;
import com.cloud.network.dao.FirewallRulesDao;
import com.cloud.network.rules.dao.PortForwardingRulesDao;
import com.cloud.network.firewall.FirewallService;
import com.cloud.network.rules.FirewallRule;
import com.cloud.network.rules.FirewallRuleVO;
import com.cloud.network.rules.PortForwardingRuleVO;
import com.cloud.network.rules.RulesService;
import com.cloud.server.ResourceTag.ResourceObjectType;
import com.cloud.vm.UserVmVO;
import org.junit.Test;
import org.mockito.Mockito;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class KubernetesExternalNodeRuleRetryTest {
    @Test public void partialRuleCleanupRetriesTheRemainingFirewallAndNeverTouchesManualRules() throws Exception {
        String clusterUuid = "00000000-0000-0000-0000-000000000001", networkUuid = "00000000-0000-0000-0000-000000000002";
        String ipUuid = "00000000-0000-0000-0000-000000000003", generation = "00000000-0000-0000-0000-000000000004";
        String pfUuid = "00000000-0000-0000-0000-000000000005", fwUuid = "00000000-0000-0000-0000-000000000006";
        KubernetesCluster cluster = Mockito.mock(KubernetesCluster.class); Mockito.when(cluster.getId()).thenReturn(1L);
        Network network = Mockito.mock(Network.class); IpAddress ip = Mockito.mock(IpAddress.class);
        KubernetesClusterRemoveWorker worker = new KubernetesClusterRemoveWorker(cluster, Mockito.mock(KubernetesClusterManagerImpl.class)) {
            @Override protected IpAddress getPublicIp(Network net) { return ip; }
            @Override protected void validateOwnedNodeRule(KubernetesOwnedResourceReceipt receipt, FirewallRule rule, Network net, IpAddress address) { }
        };
        worker.kubernetesClusterDetailsDao = Mockito.mock(KubernetesClusterDetailsDao.class);
        worker.portForwardingRulesDao = Mockito.mock(PortForwardingRulesDao.class);
        worker.rulesService = Mockito.mock(RulesService.class); worker.firewallService = Mockito.mock(FirewallService.class);
        FirewallRulesDao firewalls = Mockito.mock(FirewallRulesDao.class);
        Field field = KubernetesClusterRemoveWorker.class.getDeclaredField("firewallRulesDao"); field.setAccessible(true); field.set(worker, firewalls);
        UserVmVO vm = Mockito.mock(UserVmVO.class); Mockito.when(vm.getUuid()).thenReturn("external-worker");
        KubernetesOwnedResourceReceipt pf = new KubernetesOwnedResourceReceipt(ResourceObjectType.PortForwardingRule, 10L, pfUuid, clusterUuid, networkUuid, ipUuid, generation);
        KubernetesOwnedResourceReceipt fw = new KubernetesOwnedResourceReceipt(ResourceObjectType.FirewallRule, 11L, fwUuid, clusterUuid, networkUuid, ipUuid, generation);
        Mockito.when(worker.kubernetesClusterDetailsDao.findDetail(1L, "external.remove.rules.external-worker"))
                .thenReturn(new KubernetesClusterDetailsVO(1L, "external.remove.rules.external-worker", pf.encode() + "\n" + fw.encode(), false));
        PortForwardingRuleVO forwarding = Mockito.mock(PortForwardingRuleVO.class); Mockito.when(forwarding.getVirtualMachineId()).thenReturn(61L);
        Mockito.when(worker.portForwardingRulesDao.findById(10L)).thenReturn(forwarding, null);
        Mockito.when(firewalls.findById(11L)).thenReturn(Mockito.mock(FirewallRuleVO.class));
        Mockito.when(worker.rulesService.revokePortForwardingRule(10L, true)).thenReturn(true);
        Mockito.when(worker.firewallService.revokeIngressFirewallRule(11L, true)).thenReturn(false, true);
        assertFalse(worker.removeNodePortForwardingRules(61L, network, vm));
        assertTrue(worker.removeNodePortForwardingRules(61L, network, vm));
        Mockito.verify(worker.rulesService, Mockito.times(1)).revokePortForwardingRule(10L, true);
        Mockito.verify(worker.firewallService, Mockito.times(2)).revokeIngressFirewallRule(11L, true);
        Mockito.verify(worker.rulesService, Mockito.never()).revokePortForwardingRule(20L, true);
        Mockito.verify(worker.kubernetesClusterDetailsDao, Mockito.never()).removeDetail(Mockito.anyLong(), Mockito.anyString());
    }
}
