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

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.kubernetes.cluster.KubernetesCluster;
import com.cloud.kubernetes.cluster.KubernetesClusterManagerImpl;
import com.cloud.kubernetes.cluster.KubernetesServiceHelper.KubernetesClusterNodeType;
import com.cloud.network.Network;
import com.cloud.offering.ServiceOffering;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.uservm.UserVm;
import com.cloud.vm.UserVmService;
import com.cloud.vm.VmDetailConstants;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class KubernetesEtcdRootDiskTest {
    private static class Worker extends KubernetesClusterStartWorker {
        Worker(KubernetesCluster cluster) {
            super(cluster, new KubernetesClusterManagerImpl());
        }

        @Override
        protected String readK8sConfigFile(String resource) {
            return "#cloud-config\n";
        }

        @Override
        protected String prepareKubernetesUserData(String data) {
            return data;
        }

        @Override
        protected List<Long> getMergedAffinityGroupIds(KubernetesClusterNodeType type, Long domain, Long account) {
            return Collections.singletonList(7L);
        }

        @Override
        protected ServiceOffering getServiceOfferingForNodeTypeOnCluster(KubernetesClusterNodeType type, KubernetesCluster cluster) {
            return Mockito.mock(ServiceOffering.class);
        }
    }

    private void verifyEtcdVmRequest(boolean securityGroups, long rootSize, Hypervisor.HypervisorType hypervisor) throws Exception {
        KubernetesCluster cluster = Mockito.mock(KubernetesCluster.class);
        Mockito.when(cluster.getNodeRootDiskSize()).thenReturn(rootSize);
        Mockito.when(cluster.getZoneId()).thenReturn(1L);
        Mockito.when(cluster.getNetworkId()).thenReturn(2L);
        Worker worker = new Worker(cluster);
        worker.dataCenterDao = Mockito.mock(DataCenterDao.class);
        DataCenterVO zone = Mockito.mock(DataCenterVO.class);
        Mockito.when(zone.isSecurityGroupEnabled()).thenReturn(securityGroups);
        Mockito.when(worker.dataCenterDao.findById(1L)).thenReturn(zone);
        worker.configurationDao = Mockito.mock(ConfigurationDao.class);
        worker.clusterTemplate = Mockito.mock(VirtualMachineTemplate.class);
        worker.etcdTemplate = Mockito.mock(VirtualMachineTemplate.class);
        Mockito.when(worker.etcdTemplate.getHypervisorType()).thenReturn(hypervisor);
        UserVm vm = Mockito.mock(UserVm.class);
        Map<?, ?>[] captured = new Map<?, ?>[1];
        worker.userVmService = Mockito.mock(UserVmService.class, invocation -> {
            if (invocation.getMethod().getName().startsWith("createAdvanced")) {
                Object[] args = invocation.getArguments();
                assertSame(worker.etcdTemplate, args[2]);
                // The custom parameters follow affinity groups in both VM provisioning APIs.
                for (int i = 0; i < args.length - 1; i++) {
                    if (Collections.singletonList(7L).equals(args[i])) {
                        captured[0] = (Map<?, ?>) args[i + 1];
                    }
                }
                return vm;
            }
            return Mockito.RETURNS_DEFAULTS.answer(invocation);
        });
        Method method = KubernetesClusterStartWorker.class.getDeclaredMethod("createEtcdNode", List.class, List.class,
                int.class, Long.class, Long.class);
        method.setAccessible(true);
        assertSame(vm, method.invoke(worker, Collections.singletonList(new Network.IpAddresses("10.1.0.10", null)),
                Collections.singletonList("etcd-1"), 0, 1L, 1L));
        assertNotNull(captured[0]);
        assertEquals(rootSize > 0 ? Long.toString(rootSize) : null, captured[0].get("rootdisksize"));
        if (hypervisor == Hypervisor.HypervisorType.VMware) {
            assertEquals("scsi", captured[0].get(VmDetailConstants.ROOT_DISK_CONTROLLER));
        } else {
            assertNull(captured[0].get(VmDetailConstants.ROOT_DISK_CONTROLLER));
        }
        assertEquals(1, Mockito.mockingDetails(worker.userVmService).getInvocations().size());
    }

    @Test
    public void isolatedEtcdCustomDiskOfferingReceivesClusterRootSize() throws Exception {
        verifyEtcdVmRequest(false, 40, Hypervisor.HypervisorType.KVM);
    }

    @Test
    public void securityGroupEtcdCustomDiskOfferingReceivesClusterRootSize() throws Exception {
        verifyEtcdVmRequest(true, 40, Hypervisor.HypervisorType.KVM);
    }

    @Test
    public void templateDefaultRootSizeIsNotOverridden() throws Exception {
        verifyEtcdVmRequest(false, 0, Hypervisor.HypervisorType.KVM);
    }

    @Test
    public void vmwareEtcdUsesItsRoleTemplateController() throws Exception {
        verifyEtcdVmRequest(false, 40, Hypervisor.HypervisorType.VMware);
    }
}
