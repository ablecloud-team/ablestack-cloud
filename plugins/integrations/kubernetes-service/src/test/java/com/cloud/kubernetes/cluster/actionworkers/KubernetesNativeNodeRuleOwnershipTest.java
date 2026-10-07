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

import com.cloud.kubernetes.cluster.KubernetesCluster;
import com.cloud.kubernetes.cluster.KubernetesClusterManagerImpl;
import com.cloud.kubernetes.cluster.dao.KubernetesClusterDetailsDao;
import com.cloud.network.IpAddress;
import com.cloud.network.Network;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.rules.PortForwardingRuleVO;
import com.cloud.server.ResourceTag.ResourceObjectType;
import com.cloud.utils.exception.CloudRuntimeException;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import static org.junit.Assert.assertNull;

public class KubernetesNativeNodeRuleOwnershipTest {
    private static final String CLUSTER = "00000000-0000-0000-0000-000000000001";
    private static final String NETWORK = "00000000-0000-0000-0000-000000000002";
    private static final String IP = "00000000-0000-0000-0000-000000000003";
    private static final String RULE = "00000000-0000-0000-0000-000000000004";
    private static final String GENERATION = "00000000-0000-0000-0000-000000000005";
    private final KubernetesCluster cluster = Mockito.mock(KubernetesCluster.class);
    private final Network network = Mockito.mock(Network.class);
    private final IpAddress address = Mockito.mock(IpAddress.class);
    private final IPAddressVO current = Mockito.mock(IPAddressVO.class);
    private final PortForwardingRuleVO rule = Mockito.mock(PortForwardingRuleVO.class);
    private KubernetesClusterActionWorker worker;
    private KubernetesOwnedResourceReceipt receipt;

    @Before public void setUp() {
        Mockito.when(cluster.getId()).thenReturn(1L); Mockito.when(cluster.getUuid()).thenReturn(CLUSTER); Mockito.when(cluster.getAccountId()).thenReturn(8L);
        Mockito.when(network.getId()).thenReturn(2L); Mockito.when(network.getUuid()).thenReturn(NETWORK);
        Mockito.when(address.getId()).thenReturn(3L); Mockito.when(address.getUuid()).thenReturn(IP);
        Mockito.when(current.getAllocationGeneration()).thenReturn(GENERATION);
        Mockito.when(rule.getId()).thenReturn(4L); Mockito.when(rule.getUuid()).thenReturn(RULE);
        Mockito.when(rule.getAccountId()).thenReturn(8L); Mockito.when(rule.getNetworkId()).thenReturn(2L); Mockito.when(rule.getSourceIpAddressId()).thenReturn(3L);
        worker = new KubernetesClusterActionWorker(cluster, Mockito.mock(KubernetesClusterManagerImpl.class));
        worker.ipAddressDao = Mockito.mock(IPAddressDao.class); worker.kubernetesClusterDetailsDao = Mockito.mock(KubernetesClusterDetailsDao.class);
        Mockito.when(worker.ipAddressDao.findById(3L)).thenReturn(current);
        receipt = new KubernetesOwnedResourceReceipt(ResourceObjectType.PortForwardingRule, 4L, RULE, CLUSTER, NETWORK, IP, GENERATION);
    }
    @Test public void ownedRuleHasExactClusterNetworkIpGenerationAndTargetIdentity() { worker.validateOwnedNodeRule(receipt, rule, network, address); }
    @Test public void manualRuleWithoutReceiptIsPreserved() { assertNull(worker.findOwnedNativeRule(rule, network, address)); }
    @Test(expected = CloudRuntimeException.class) public void reallocatedIpCannotAuthorizeDeletion() {
        Mockito.when(current.getAllocationGeneration()).thenReturn("00000000-0000-0000-0000-000000000006");
        worker.validateOwnedNodeRule(receipt, rule, network, address);
    }
    @Test(expected = CloudRuntimeException.class) public void changedRuleIdentityCannotAuthorizeDeletion() {
        Mockito.when(rule.getUuid()).thenReturn("00000000-0000-0000-0000-000000000006");
        worker.validateOwnedNodeRule(receipt, rule, network, address);
    }
    @Test(expected = CloudRuntimeException.class) public void foreignAccountRuleIsPreserved() {
        Mockito.when(rule.getAccountId()).thenReturn(9L); worker.validateOwnedNodeRule(receipt, rule, network, address);
    }
}
