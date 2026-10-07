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
import java.util.List;
import com.cloud.kubernetes.cluster.KubernetesCluster;
import com.cloud.kubernetes.cluster.KubernetesClusterManagerImpl;
import com.cloud.kubernetes.cluster.KubernetesClusterVmMapVO;
import com.cloud.kubernetes.cluster.dao.KubernetesClusterVmMapDao;
import com.cloud.network.Network;
import com.cloud.network.lb.LoadBalancingRulesService;
import com.cloud.uservm.UserVm;
import com.cloud.network.NetworkModel;
import com.cloud.vm.Nic;
import com.cloud.tags.dao.ResourceTagDao;
import com.cloud.network.IpAddress;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.dao.LoadBalancerDao;
import com.cloud.network.dao.LoadBalancerVO;
import com.cloud.network.dao.LoadBalancerVMMapDao;
import com.cloud.network.dao.LoadBalancerVMMapVO;
import com.cloud.utils.exception.CloudRuntimeException;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

public class KubernetesHaUpgradeApiPoolTest {
    private KubernetesCluster cluster;
    private Worker worker;
    private LoadBalancerVO rule;
    private IpAddress address;
    private static class Worker extends KubernetesClusterUpgradeWorker {
        IpAddress address;
        Worker(KubernetesCluster cluster) { super(cluster, null, Mockito.mock(KubernetesClusterManagerImpl.class), null); }
        @Override protected IpAddress getNetworkSourceNatIp(Network network) { return address; }
    }
    @Before public void setup() {
        cluster = Mockito.mock(KubernetesCluster.class);
        Mockito.when(cluster.getId()).thenReturn(7L);
        Mockito.when(cluster.getAccountId()).thenReturn(9L);
        Mockito.when(cluster.getNetworkId()).thenReturn(5L);
        Mockito.when(cluster.getControlNodeCount()).thenReturn(3L);
        worker = new Worker(cluster);
        worker.networkDao = Mockito.mock(NetworkDao.class);
        NetworkVO network = Mockito.mock(NetworkVO.class);
        Mockito.when(network.getId()).thenReturn(5L);
        Mockito.when(network.getVpcId()).thenReturn(null);
        Mockito.when(worker.networkDao.findById(5L)).thenReturn(network);
        worker.loadBalancerDao = Mockito.mock(LoadBalancerDao.class);
        worker.networkModel = Mockito.mock(NetworkModel.class);
        worker.resourceTagDao = Mockito.mock(ResourceTagDao.class);
        for (long id : new long[]{1, 2, 3, 4}) {
            Nic nic = Mockito.mock(Nic.class);
            Mockito.when(nic.getIPv4Address()).thenReturn("10.0.0." + id);
            Mockito.when(worker.networkModel.getNicInNetwork(id, 5L)).thenReturn(nic);
        }
        worker.loadBalancerVMMapDao = Mockito.mock(LoadBalancerVMMapDao.class);
        worker.kubernetesClusterVmMapDao = Mockito.mock(KubernetesClusterVmMapDao.class);
        address = Mockito.mock(IpAddress.class);
        Mockito.when(address.getId()).thenReturn(6L);
        Mockito.when(address.getAccountId()).thenReturn(9L); worker.address = address;
        rule = Mockito.mock(LoadBalancerVO.class);
        Mockito.when(rule.getId()).thenReturn(10L); Mockito.when(rule.getAccountId()).thenReturn(9L);
        Mockito.when(rule.getNetworkId()).thenReturn(5L); Mockito.when(rule.getProtocol()).thenReturn("tcp"); Mockito.when(rule.getName()).thenReturn("api-lb");
        Mockito.when(rule.getSourcePortStart()).thenReturn(6443); Mockito.when(rule.getSourcePortEnd()).thenReturn(6443);
        Mockito.when(rule.getDefaultPortStart()).thenReturn(6443); Mockito.when(rule.getDefaultPortEnd()).thenReturn(6443);
        Mockito.when(worker.loadBalancerDao.listByIpAddress(6L)).thenReturn(Collections.singletonList(rule));
        List<KubernetesClusterVmMapVO> controls = Arrays.asList(control(1), control(2), control(3));
        Mockito.when(worker.kubernetesClusterVmMapDao.listByClusterId(7L)).thenReturn(controls);
        members(1, 2, 3);
    }
    private KubernetesClusterVmMapVO control(long id) { KubernetesClusterVmMapVO m = Mockito.mock(KubernetesClusterVmMapVO.class); Mockito.when(m.isControlNode()).thenReturn(true); Mockito.when(m.getVmId()).thenReturn(id); return m; }
    private void members(long... ids) { List<LoadBalancerVMMapVO> rows = new java.util.ArrayList<>(); for (long id : ids) { LoadBalancerVMMapVO row = new LoadBalancerVMMapVO(10L, id); row.setInstanceIp("10.0.0." + id); rows.add(row); } Mockito.when(worker.loadBalancerVMMapDao.listByLoadBalancerId(10L, false)).thenReturn(rows); }
    private void rejects() { try { worker.findHaUpgradeApiLoadBalancer(); fail("unverified API pool must be rejected"); } catch (CloudRuntimeException expected) { } }
    @Test public void exactNativeControlsAccepted() { assertSame(rule, worker.findHaUpgradeApiLoadBalancer()); }
    @Test public void operatorBackendRejected() { members(1, 2, 4); rejects(); }
    @Test public void missingQuorumBackendRejected() { members(1); rejects(); }
    @Test public void previouslyPausedSingleMemberCanBeRecoveredAfterHealthGate() { members(1, 2); assertSame(rule, worker.findHaUpgradeApiLoadBalancer()); }
    @Test public void differentAddressOwnerRejected() { Mockito.when(address.getAccountId()).thenReturn(99L); rejects(); }
    @Test public void ambiguousApiRulesRejected() { Mockito.when(worker.loadBalancerDao.listByIpAddress(6L)).thenReturn(Arrays.asList(rule, rule)); rejects(); }
    @Test public void repeatedBackendForOneVmRejected() { members(1, 2, 3, 3); rejects(); }
    @Test public void differentBackendIpRejected() { Nic nic = Mockito.mock(Nic.class); Mockito.when(nic.getIPv4Address()).thenReturn("10.0.0.99"); Mockito.when(worker.networkModel.getNicInNetwork(1L, 5L)).thenReturn(nic); rejects(); }
    @Test public void existingBackendIsNotAssignedAgain() {
        worker.upgradeApiLoadBalancer = rule;
        worker.lbService = Mockito.mock(LoadBalancingRulesService.class);
        UserVm vm = Mockito.mock(UserVm.class);
        Mockito.when(vm.getId()).thenReturn(1L);
        worker.setUpgradeApiMember(vm, true);
        Mockito.verifyNoInteractions(worker.lbService);
    }
    @Test public void absentBackendIsNotWithdrawnAgain() {
        worker.upgradeApiLoadBalancer = rule;
        worker.lbService = Mockito.mock(LoadBalancingRulesService.class);
        members(2, 3);
        UserVm vm = Mockito.mock(UserVm.class);
        Mockito.when(vm.getId()).thenReturn(1L);
        worker.setUpgradeApiMember(vm, false);
        Mockito.verifyNoInteractions(worker.lbService);
    }
    @Test public void singletonDoesNotChangeApiPool() { Mockito.when(cluster.getControlNodeCount()).thenReturn(1L); assertNull(worker.findHaUpgradeApiLoadBalancer()); Mockito.verifyNoInteractions(worker.loadBalancerDao); }
}
