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

import java.util.List;
import com.cloud.kubernetes.cluster.KubernetesCluster;
import com.cloud.kubernetes.cluster.KubernetesClusterManagerImpl;
import com.cloud.network.IpAddress;
import com.cloud.network.Network;
import com.cloud.network.dao.FirewallRulesDao;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.rules.FirewallRule;
import com.cloud.network.rules.FirewallRuleVO;
import com.cloud.network.rules.PortForwardingRuleVO;
import com.cloud.network.rules.dao.PortForwardingRulesDao;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

public class KubernetesScaleNetworkOwnershipTest {
    private KubernetesClusterScaleWorker worker(FirewallRule owned, boolean failValidation) {
        return new KubernetesClusterScaleWorker(Mockito.mock(KubernetesCluster.class), Mockito.mock(KubernetesClusterManagerImpl.class)) {
            @Override protected KubernetesOwnedResourceReceipt findOwnedNativeRule(FirewallRule rule, Network network, IpAddress ip) {
                if (rule != owned) { return null; }
                if (failValidation) { throw new com.cloud.utils.exception.CloudRuntimeException("allocation generation changed"); }
                return Mockito.mock(KubernetesOwnedResourceReceipt.class);
            }
        };
    }

    @Test public void sshRangeCleanupPreservesManualAndNonSshRules() {
        PortForwardingRuleVO owned = Mockito.mock(PortForwardingRuleVO.class), manual = Mockito.mock(PortForwardingRuleVO.class);
        PortForwardingRuleVO web = Mockito.mock(PortForwardingRuleVO.class), outside = Mockito.mock(PortForwardingRuleVO.class);
        Mockito.when(owned.getSourcePortStart()).thenReturn(new Integer(2223)); Mockito.when(owned.getDestinationPortStart()).thenReturn(new Integer(22));
        Mockito.when(manual.getSourcePortStart()).thenReturn(2223); Mockito.when(manual.getDestinationPortStart()).thenReturn(22);
        Mockito.when(web.getSourcePortStart()).thenReturn(2224); Mockito.when(web.getDestinationPortStart()).thenReturn(80);
        Mockito.when(outside.getSourcePortStart()).thenReturn(2325); Mockito.when(outside.getDestinationPortStart()).thenReturn(22);
        KubernetesClusterScaleWorker worker = worker(owned, false); worker.portForwardingRulesDao = Mockito.mock(PortForwardingRulesDao.class);
        Network network = Mockito.mock(Network.class); Mockito.when(network.getId()).thenReturn(7L);
        Mockito.when(worker.portForwardingRulesDao.listByNetwork(7L)).thenReturn(List.of(manual, web, outside, owned));
        Assert.assertEquals(List.of(owned), worker.planOwnedSshForwardingRules(Mockito.mock(IpAddress.class), network, 2222, 2225));
        Mockito.verify(worker.portForwardingRulesDao, Mockito.never()).remove(Mockito.anyLong());
    }

    @Test public void changedReceiptRejectsRangeBeforeAnyDeletion() throws Exception {
        PortForwardingRuleVO owned = Mockito.mock(PortForwardingRuleVO.class);
        Mockito.when(owned.getSourcePortStart()).thenReturn(2223); Mockito.when(owned.getDestinationPortStart()).thenReturn(22);
        KubernetesClusterScaleWorker worker = worker(owned, true); worker.portForwardingRulesDao = Mockito.mock(PortForwardingRulesDao.class);
        Network network = Mockito.mock(Network.class); Mockito.when(network.getId()).thenReturn(7L);
        Mockito.when(worker.portForwardingRulesDao.listByNetwork(7L)).thenReturn(List.of(owned));
        try { worker.removePortForwardingRules(Mockito.mock(IpAddress.class), network, null, 2222, 2225); Assert.fail("invalid receipt accepted"); }
        catch (com.cloud.utils.exception.CloudRuntimeException expected) { }
        Mockito.verify(worker.portForwardingRulesDao, Mockito.never()).remove(Mockito.anyLong());
    }

    @Test public void manualSshFirewallCannotBeSelectedAheadOfOwnedBaseRule() {
        FirewallRuleVO owned = Mockito.mock(FirewallRuleVO.class), manual = Mockito.mock(FirewallRuleVO.class);
        Mockito.when(owned.getSourcePortStart()).thenReturn(new Integer(2222)); Mockito.when(owned.getSourcePortEnd()).thenReturn(2225);
        Mockito.when(manual.getSourcePortStart()).thenReturn(2325); Mockito.when(manual.getSourcePortEnd()).thenReturn(2325);
        PortForwardingRuleVO manualPf = Mockito.mock(PortForwardingRuleVO.class); Mockito.when(manualPf.getDestinationPortStart()).thenReturn(22);
        KubernetesClusterScaleWorker worker = worker(owned, false);
        worker.firewallRulesDao = Mockito.mock(FirewallRulesDao.class); worker.portForwardingRulesDao = Mockito.mock(PortForwardingRulesDao.class);
        worker.networkDao = Mockito.mock(NetworkDao.class); worker.firewallService = Mockito.mock(com.cloud.network.firewall.FirewallService.class);
        IpAddress ip = Mockito.mock(IpAddress.class); Mockito.when(ip.getId()).thenReturn(90L);
        Mockito.when(worker.firewallRulesDao.listByIpPurposeProtocolAndNotRevoked(90L, FirewallRule.Purpose.Firewall, "tcp")).thenReturn(List.of(manual, owned));
        Mockito.when(worker.portForwardingRulesDao.findByNetworkAndPorts(7L, 2325, 2325)).thenReturn(manualPf);
        Mockito.when(manual.getId()).thenReturn(60L); Mockito.when(owned.getId()).thenReturn(61L);
        Assert.assertSame(owned, worker.removeSshFirewallRule(ip, 7L));
        Mockito.verify(worker.firewallService).revokeIngressFwRule(61L, true);
        Mockito.verify(worker.firewallService, Mockito.never()).revokeIngressFwRule(60L, true);
    }
}
