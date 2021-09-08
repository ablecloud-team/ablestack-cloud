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

package com.cloud.desktop.cluster.actionworkers;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.inject.Inject;

import org.apache.cloudstack.api.command.user.vm.StartVMCmd;
import org.apache.commons.collections.CollectionUtils;

import com.cloud.capacity.CapacityManager;
import com.cloud.dc.ClusterDetailsDao;
import com.cloud.dc.ClusterDetailsVO;
import com.cloud.dc.ClusterVO;
import com.cloud.dc.DataCenter;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.deploy.DeployDestination;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InsufficientServerCapacityException;
import com.cloud.exception.ManagementServerException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.desktop.cluster.DesktopCluster;
import com.cloud.desktop.cluster.DesktopClusterManagerImpl;
import com.cloud.network.IpAddress;
import com.cloud.network.Network;
import com.cloud.network.dao.FirewallRulesDao;
import com.cloud.network.dao.LoadBalancerDao;
import com.cloud.network.firewall.FirewallService;
import com.cloud.network.lb.LoadBalancingRulesService;
import com.cloud.network.rules.PortForwardingRuleVO;
import com.cloud.network.rules.RulesService;
import com.cloud.network.rules.dao.PortForwardingRulesDao;
import com.cloud.offering.ServiceOffering;
import com.cloud.resource.ResourceManager;
import com.cloud.storage.VolumeApiService;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.Account;
import com.cloud.uservm.UserVm;
import com.cloud.utils.Pair;
import com.cloud.utils.component.ComponentContext;
import com.cloud.utils.exception.ExecutionException;
import com.cloud.vm.UserVmManager;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.VMInstanceDao;
import com.google.common.base.Strings;

import static com.cloud.utils.NumbersUtil.toHumanReadableSize;

public class DesktopClusterResourceModifierActionWorker extends DesktopClusterActionWorker {

    @Inject
    protected CapacityManager capacityManager;
    @Inject
    protected ClusterDao clusterDao;
    @Inject
    protected ClusterDetailsDao clusterDetailsDao;
    @Inject
    protected HostDao hostDao;
    @Inject
    protected FirewallRulesDao firewallRulesDao;
    @Inject
    protected FirewallService firewallService;
    @Inject
    protected LoadBalancingRulesService lbService;
    @Inject
    protected RulesService rulesService;
    @Inject
    protected PortForwardingRulesDao portForwardingRulesDao;
    @Inject
    protected ResourceManager resourceManager;
    @Inject
    protected LoadBalancerDao loadBalancerDao;
    @Inject
    protected VMInstanceDao vmInstanceDao;
    @Inject
    protected UserVmManager userVmManager;
    @Inject
    protected VolumeApiService volumeService;
    @Inject
    protected VolumeDao volumeDao;

    protected DesktopClusterResourceModifierActionWorker(final DesktopCluster desktopCluster, final DesktopClusterManagerImpl clusterManager) {
        super(desktopCluster, clusterManager);
    }

    protected void init() {
        super.init();
    }

    protected DeployDestination plan(final long nodesCount, final DataCenter zone, final ServiceOffering offering) throws InsufficientServerCapacityException {
        final int cpu_requested = offering.getCpu() * offering.getSpeed();
        final long ram_requested = offering.getRamSize() * 1024L * 1024L;
        List<HostVO> hosts = resourceManager.listAllHostsInOneZoneByType(Host.Type.Routing, zone.getId());
        final Map<String, Pair<HostVO, Integer>> hosts_with_resevered_capacity = new ConcurrentHashMap<String, Pair<HostVO, Integer>>();
        for (HostVO h : hosts) {
            hosts_with_resevered_capacity.put(h.getUuid(), new Pair<HostVO, Integer>(h, 0));
        }
        boolean suitable_host_found = false;
        for (int i = 1; i <= nodesCount; i++) {
            suitable_host_found = false;
            for (Map.Entry<String, Pair<HostVO, Integer>> hostEntry : hosts_with_resevered_capacity.entrySet()) {
                Pair<HostVO, Integer> hp = hostEntry.getValue();
                HostVO h = hp.first();
                if (!h.getHypervisorType().equals(worksTemplate.getHypervisorType())) {
                    continue;
                }
                hostDao.loadHostTags(h);
                if (!Strings.isNullOrEmpty(offering.getHostTag()) && !(h.getHostTags() != null && h.getHostTags().contains(offering.getHostTag()))) {
                    continue;
                }
                int reserved = hp.second();
                reserved++;
                ClusterVO cluster = clusterDao.findById(h.getClusterId());
                ClusterDetailsVO cluster_detail_cpu = clusterDetailsDao.findDetail(cluster.getId(), "cpuOvercommitRatio");
                ClusterDetailsVO cluster_detail_ram = clusterDetailsDao.findDetail(cluster.getId(), "memoryOvercommitRatio");
                Float cpuOvercommitRatio = Float.parseFloat(cluster_detail_cpu.getValue());
                Float memoryOvercommitRatio = Float.parseFloat(cluster_detail_ram.getValue());
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug(String.format("Checking host : %s for capacity already reserved %d", h.getName(), reserved));
                }
                if (capacityManager.checkIfHostHasCapacity(h.getId(), cpu_requested * reserved, ram_requested * reserved, false, cpuOvercommitRatio, memoryOvercommitRatio, true)) {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug(String.format("Found host : %s for with enough capacity, CPU=%d RAM=%s", h.getName(), cpu_requested * reserved, toHumanReadableSize(ram_requested * reserved)));
                    }
                    hostEntry.setValue(new Pair<HostVO, Integer>(h, reserved));
                    suitable_host_found = true;
                    break;
                }
            }
            if (!suitable_host_found) {
                if (LOGGER.isInfoEnabled()) {
                    LOGGER.info(String.format("Suitable hosts not found in datacenter : %s for node %d, with offering : %s and hypervisor: %s",
                        zone.getName(), i, offering.getName(), worksTemplate.getHypervisorType().toString()));
                }
                break;
            }
        }
        if (suitable_host_found) {
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info(String.format("Suitable hosts found in datacenter : %s, creating deployment destination", zone.getName()));
            }
            return new DeployDestination(zone, null, null, null);
        }
        String msg = String.format("Cannot find enough capacity for desktop cluster(requested cpu=%d memory=%s) with offering : %s and hypervisor: %s",
                cpu_requested * nodesCount, toHumanReadableSize(ram_requested * nodesCount), offering.getName(), worksTemplate.getHypervisorType().toString());

        LOGGER.warn(msg);
        throw new InsufficientServerCapacityException(msg, DataCenter.class, zone.getId());
    }

    protected DeployDestination plan() throws InsufficientServerCapacityException {
        ServiceOffering offering = serviceOfferingDao.findById(desktopCluster.getServiceOfferingId());
        DataCenter zone = dataCenterDao.findById(desktopCluster.getZoneId());
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(String.format("Checking deployment destination for desktop cluster : %s in zone : %s", desktopCluster.getName(), zone.getName()));
        }
        final long dest = 2;
        return plan(dest, zone, offering);
    }

    protected void startDesktopVM(final UserVm vm) throws ManagementServerException {
        try {
            StartVMCmd startVm = new StartVMCmd();
            startVm = ComponentContext.inject(startVm);
            Field f = startVm.getClass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(startVm, vm.getId());
            userVmService.startVirtualMachine(startVm);
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info(String.format("Started VM : %s in the desktop cluster : %s", vm.getDisplayName(), desktopCluster.getName()));
            }
        } catch (IllegalAccessException | NoSuchFieldException | ExecutionException |
                ResourceUnavailableException | ResourceAllocationException | InsufficientCapacityException ex) {
            throw new ManagementServerException(String.format("Failed to start VM in the desktop cluster : %s", desktopCluster.getName()), ex);
        }

        UserVm startVm = userVmDao.findById(vm.getId());
        if (!startVm.getState().equals(VirtualMachine.State.Running)) {
            throw new ManagementServerException(String.format("Failed to start VM in the desktop cluster : %s", desktopCluster.getName()));
        }
    }

    protected IpAddress getSourceNatIp(Network network) {
        List<? extends IpAddress> addresses = networkModel.listPublicIpsAssignedToGuestNtwk(network.getId(), true);
        if (CollectionUtils.isEmpty(addresses)) {
            return null;
        }
        for (IpAddress address : addresses) {
            if (address.isSourceNat()) {
                return address;
            }
        }
        return null;
    }

    protected void removePortForwardingRules(final IpAddress publicIp, final Network network, final Account account, final List<Long> removedVMIds) throws ResourceUnavailableException {
        if (!CollectionUtils.isEmpty(removedVMIds)) {
            for (Long vmId : removedVMIds) {
                List<PortForwardingRuleVO> pfRules = portForwardingRulesDao.listByNetwork(network.getId());
                for (PortForwardingRuleVO pfRule : pfRules) {
                    if (pfRule.getVirtualMachineId() == vmId) {
                        portForwardingRulesDao.remove(pfRule.getId());
                        break;
                    }
                }
            }
            rulesService.applyPortForwardingRules(publicIp.getId(), account);
        }
    }
}
