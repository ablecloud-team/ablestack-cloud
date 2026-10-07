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

import java.util.Arrays;
import java.util.Collections;
import com.cloud.kubernetes.cluster.KubernetesCluster;
import com.cloud.kubernetes.cluster.KubernetesClusterManagerImpl;
import com.cloud.network.Network;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.rules.dao.PortForwardingRulesDao;
import com.cloud.network.rules.PortForwardingRuleVO;
import com.cloud.network.rules.FirewallRule;
import org.apache.cloudstack.network.RoutedIpv4Manager;
import com.cloud.uservm.UserVm;
import com.cloud.utils.Pair;
import com.cloud.utils.net.Ip;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

public class KubernetesNodeBootEndpointTest {
    private KubernetesClusterActionWorker worker;
    private UserVm vm;
    private NetworkVO network;

    @Before
    public void prepare() {
        KubernetesCluster cluster = Mockito.mock(KubernetesCluster.class);
        Mockito.when(cluster.getNetworkId()).thenReturn(Long.valueOf("1000"));
        worker = new KubernetesClusterActionWorker(cluster, Mockito.mock(KubernetesClusterManagerImpl.class));
        worker.networkDao = Mockito.mock(NetworkDao.class);
        worker.routedIpv4Manager = Mockito.mock(RoutedIpv4Manager.class);
        worker.portForwardingRulesDao = Mockito.mock(PortForwardingRulesDao.class);
        worker.ipAddressDao = Mockito.mock(IPAddressDao.class);
        network = Mockito.mock(NetworkVO.class);
        Mockito.when(worker.networkDao.findById(1000L)).thenReturn(network);
        Mockito.when(network.getGuestType()).thenReturn(Network.GuestType.Isolated);
        vm = Mockito.mock(UserVm.class);
        Mockito.when(vm.getId()).thenReturn(2000L);
    }

    private PortForwardingRuleVO rule(long networkId, int port, FirewallRule.State state) {
        PortForwardingRuleVO rule = new PortForwardingRuleVO("rule", 3000L, port, new Ip("10.1.0.7"), 22, "tcp", networkId, 1L, 1L, 2000L);
        rule.setState(state);
        return rule;
    }

    @Test
    public void separateBoxedNetworkIdsSelectTheActualVmSshPort() {
        Mockito.when(worker.portForwardingRulesDao.listByVm(2000L)).thenReturn(Arrays.asList(
                rule(999L, 2223, FirewallRule.State.Active), rule(1000L, 2228, FirewallRule.State.Active)));
        IPAddressVO ip = Mockito.mock(IPAddressVO.class);
        Mockito.when(ip.getAddress()).thenReturn(new Ip("10.10.31.114"));
        Mockito.when(worker.ipAddressDao.findById(3000L)).thenReturn(ip);
        Pair<String, Integer> endpoint = worker.nodeBootEndpoint(vm);
        Assert.assertEquals("10.10.31.114", endpoint.first());
        Assert.assertEquals(Integer.valueOf(2228), endpoint.second());
    }

    @Test
    public void revokedOrForeignNetworkRulesCannotProvideBootIdentity() {
        Mockito.when(worker.portForwardingRulesDao.listByVm(2000L)).thenReturn(Arrays.asList(
                rule(999L, 2223, FirewallRule.State.Active), rule(1000L, 2228, FirewallRule.State.Revoke)));
        Assert.assertNull(worker.nodeBootEndpoint(vm));
    }

    @Test
    public void ambiguousSshRulesFailClosed() {
        Mockito.when(worker.portForwardingRulesDao.listByVm(2000L)).thenReturn(Arrays.asList(
                rule(1000L, 2223, FirewallRule.State.Active), rule(1000L, 2228, FirewallRule.State.Active)));
        Assert.assertNull(worker.nodeBootEndpoint(vm));
    }

    @Test
    public void missingPublicIpFailsClosed() {
        Mockito.when(worker.portForwardingRulesDao.listByVm(2000L)).thenReturn(Collections.singletonList(rule(1000L, 2228, FirewallRule.State.Active)));
        Assert.assertNull(worker.nodeBootEndpoint(vm));
    }
}
