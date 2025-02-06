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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.inject.Inject;

import org.apache.cloudstack.api.command.user.vm.StartVMCmd;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.cloudstack.api.command.user.firewall.CreateFirewallRuleCmd;
import org.apache.cloudstack.api.command.user.firewall.CreateEgressFirewallRuleCmd;
import org.apache.cloudstack.config.ApiServiceConfiguration;

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
import com.cloud.exception.NetworkRuleConflictException;
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
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.firewall.FirewallService;
import com.cloud.network.lb.LoadBalancingRulesService;
import com.cloud.network.rules.FirewallRule;
import com.cloud.network.rules.FirewallRule.TrafficType;
import com.cloud.network.rules.FirewallRuleVO;
import com.cloud.network.rules.PortForwardingRuleVO;
import com.cloud.network.rules.RulesService;
import com.cloud.network.rules.dao.PortForwardingRulesDao;
import com.cloud.offering.ServiceOffering;
import com.cloud.resource.ResourceManager;
import com.cloud.storage.VolumeApiService;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.Account;
import com.cloud.uservm.UserVm;
import com.cloud.utils.net.Ip;
import com.cloud.utils.Pair;
import com.cloud.utils.component.ComponentContext;
import com.cloud.utils.exception.ExecutionException;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallbackWithException;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.vm.Nic;
import com.cloud.vm.UserVmManager;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.api.query.dao.UserAccountJoinDao;

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
    @Inject
    protected IPAddressDao ipAddressDao;
    @Inject
    protected UserAccountJoinDao userAccountJoinDao;

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
                if (StringUtils.isNotEmpty(offering.getHostTag()) && !(h.getHostTags() != null && h.getHostTags().contains(offering.getHostTag()))) {
                    continue;
                }
                int reserved = hp.second();
                reserved++;
                ClusterVO cluster = clusterDao.findById(h.getClusterId());
                ClusterDetailsVO cluster_detail_cpu = clusterDetailsDao.findDetail(cluster.getId(), "cpuOvercommitRatio");
                ClusterDetailsVO cluster_detail_ram = clusterDetailsDao.findDetail(cluster.getId(), "memoryOvercommitRatio");
                Float cpuOvercommitRatio = Float.parseFloat(cluster_detail_cpu.getValue());
                Float memoryOvercommitRatio = Float.parseFloat(cluster_detail_ram.getValue());
                if (logger.isDebugEnabled()) {
                    logger.debug(String.format("Checking host : %s for capacity already reserved %d", h.getName(), reserved));
                }
                if (capacityManager.checkIfHostHasCapacity(h.getId(), cpu_requested * reserved, ram_requested * reserved, false, cpuOvercommitRatio, memoryOvercommitRatio, true)) {
                    if (logger.isDebugEnabled()) {
                        logger.debug(String.format("Found host : %s for with enough capacity, CPU=%d RAM=%s", h.getName(), cpu_requested * reserved, toHumanReadableSize(ram_requested * reserved)));
                    }
                    hostEntry.setValue(new Pair<HostVO, Integer>(h, reserved));
                    suitable_host_found = true;
                    break;
                }
            }
            if (!suitable_host_found) {
                if (logger.isInfoEnabled()) {
                    logger.info(String.format("Suitable hosts not found in datacenter : %s for node %d, with offering : %s and hypervisor: %s",
                        zone.getName(), i, offering.getName(), worksTemplate.getHypervisorType().toString()));
                }
                break;
            }
        }
        if (suitable_host_found) {
            if (logger.isInfoEnabled()) {
                logger.info(String.format("Suitable hosts found in datacenter : %s, creating deployment destination", zone.getName()));
            }
            return new DeployDestination(zone, null, null, null);
        }
        String msg = String.format("Cannot find enough capacity for desktop cluster(requested cpu=%d memory=%s) with offering : %s and hypervisor: %s",
                cpu_requested * nodesCount, toHumanReadableSize(ram_requested * nodesCount), offering.getName(), worksTemplate.getHypervisorType().toString());

        logger.warn(msg);
        throw new InsufficientServerCapacityException(msg, DataCenter.class, zone.getId());
    }

    protected DeployDestination plan() throws InsufficientServerCapacityException {
        ServiceOffering offering = serviceOfferingDao.findById(desktopCluster.getServiceOfferingId());
        DataCenter zone = dataCenterDao.findById(desktopCluster.getZoneId());
        if (logger.isDebugEnabled()) {
            logger.debug(String.format("Checking deployment destination for desktop cluster : %s in zone : %s", desktopCluster.getName(), zone.getName()));
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
            if (logger.isInfoEnabled()) {
                logger.info(String.format("Started VM : %s in the desktop cluster : %s", vm.getDisplayName(), desktopCluster.getName()));
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

    protected void removeFirewallIngressRule(final IpAddress publicIp) {
        List<FirewallRuleVO> firewallRules = firewallRulesDao.listByIpAndPurposeAndNotRevoked(publicIp.getId(), FirewallRule.Purpose.Firewall);
        for (FirewallRuleVO firewallRule : firewallRules) {
            if (firewallRule.getSourcePortStart() != null && firewallRule.getSourcePortEnd() != null) {
                if (firewallRule.getSourcePortStart() == CLUSTER_PORTAL_PORT &&
                        firewallRule.getSourcePortEnd() == CLUSTER_API_PORT && firewallRule.getTrafficType() == TrafficType.Ingress) {
                    firewallService.revokeIngressFwRule(firewallRule.getId(), true);
                }
                if (firewallRule.getSourcePortStart() == CLUSTER_SAMBA_PORT &&
                        firewallRule.getSourcePortEnd() == CLUSTER_SAMBA_PORT && firewallRule.getTrafficType() == TrafficType.Ingress) {
                    firewallService.revokeIngressFwRule(firewallRule.getId(), true);
                }
            }
        }
    }

    protected void removeFirewallEgressRule(final Network network) {
        List<FirewallRuleVO> firewallRules = firewallRulesDao.listByNetworkAndPurposeAndNotRevoked(network.getId(), FirewallRule.Purpose.Firewall);
        for (FirewallRuleVO firewallRule : firewallRules) {
            if (firewallRule.getSourcePortStart() != null && firewallRule.getSourcePortEnd() != null) {
                if (firewallRule.getSourcePortStart() == CLUSTER_PORTAL_PORT && firewallRule.getSourcePortEnd() == CLUSTER_LITE_PORT && firewallRule.getTrafficType() == TrafficType.Egress) {
                    firewallService.revokeIngressFwRule(firewallRule.getId(), true);
                }
            }
        }
    }

    protected void removePortForwardingRules(final IpAddress publicIp, final Network network, final Account account, final List<Long> removedVMIds) throws ResourceUnavailableException {
        if (!CollectionUtils.isEmpty(removedVMIds)) {
            for (Long vmId : removedVMIds) {
                List<PortForwardingRuleVO> pfRules = portForwardingRulesDao.listByNetwork(network.getId());
                for (PortForwardingRuleVO pfRule : pfRules) {
                    if (pfRule.getVirtualMachineId() == vmId) {
                        portForwardingRulesDao.remove(pfRule.getId());
                    }
                }
            }
            rulesService.applyPortForwardingRules(publicIp.getId(), account);
        }
    }

    protected boolean provisionFirewallRules(final IpAddress publicIp, final Account account, int startPort, int endPort) throws NoSuchFieldException,
            IllegalAccessException, ResourceUnavailableException, NetworkRuleConflictException {
        List<String> sourceCidrList = new ArrayList<String>();
        sourceCidrList.add("0.0.0.0/0");

        CreateFirewallRuleCmd rule = new CreateFirewallRuleCmd();
        rule = ComponentContext.inject(rule);

        Field addressField = rule.getClass().getDeclaredField("ipAddressId");
        addressField.setAccessible(true);
        addressField.set(rule, publicIp.getId());

        Field protocolField = rule.getClass().getDeclaredField("protocol");
        protocolField.setAccessible(true);
        protocolField.set(rule, "TCP");

        Field startPortField = rule.getClass().getDeclaredField("publicStartPort");
        startPortField.setAccessible(true);
        startPortField.set(rule, startPort);

        Field endPortField = rule.getClass().getDeclaredField("publicEndPort");
        endPortField.setAccessible(true);
        endPortField.set(rule, endPort);

        Field cidrField = rule.getClass().getDeclaredField("cidrlist");
        cidrField.setAccessible(true);
        cidrField.set(rule, sourceCidrList);

        boolean sccuess = false;
        FirewallRule result = firewallService.createIngressFirewallRule(rule);
        if (result != null) {
            sccuess = firewallService.applyIngressFwRules(publicIp.getId(), account);
        }
        return sccuess;
    }

    protected boolean provisionEgressFirewallRules(final Network network, final Account account, int startPort, int endPort) throws NoSuchFieldException,
            IllegalAccessException, ResourceUnavailableException, NetworkRuleConflictException {
        List<String> sourceCidrList = new ArrayList<String>();
        String worksVmIp = desktopCluster.getWorksIp();
        sourceCidrList.add(worksVmIp+"/32");

        List<String> destinationCidrList = new ArrayList<String>();
        String manageIp = ApiServiceConfiguration.ManagementServerAddresses.value();
        destinationCidrList.add(manageIp+"/32");

        CreateEgressFirewallRuleCmd rule = new CreateEgressFirewallRuleCmd();
        rule = ComponentContext.inject(rule);

        Field addressField = rule.getClass().getDeclaredField("networkId");
        addressField.setAccessible(true);
        addressField.set(rule, network.getId());

        Field protocolField = rule.getClass().getDeclaredField("protocol");
        protocolField.setAccessible(true);
        protocolField.set(rule, "TCP");

        Field startPortField = rule.getClass().getDeclaredField("publicStartPort");
        startPortField.setAccessible(true);
        startPortField.set(rule, startPort);

        Field endPortField = rule.getClass().getDeclaredField("publicEndPort");
        endPortField.setAccessible(true);
        endPortField.set(rule, endPort);

        Field cidrField = rule.getClass().getDeclaredField("cidrlist");
        cidrField.setAccessible(true);
        cidrField.set(rule, sourceCidrList);

        Field destCidrField = rule.getClass().getDeclaredField("destCidrList");
        destCidrField.setAccessible(true);
        destCidrField.set(rule, destinationCidrList);

        boolean sccuess = false;
        FirewallRule result = firewallService.createEgressFirewallRule(rule);
        if (result != null) {
            sccuess = firewallService.applyEgressFirewallRules(result, account);
        }
        return sccuess;
    }

    protected boolean provisionPortForwardingRules(IpAddress publicIp, Network network, Account account, UserVm worksVM, int adminPort, int userPort, int sambaPort, int apiPort) throws ResourceUnavailableException, NetworkRuleConflictException {
        boolean result = false;
        if (worksVM != null) {
            final long publicIpId = publicIp.getId();
            final long networkId = network.getId();
            final long accountId = account.getId();
            final long domainId = account.getDomainId();
            long vmId = worksVM.getId();
            Nic vmNic = networkModel.getNicInNetwork(vmId, networkId);
            final Ip vmIp = new Ip(vmNic.getIPv4Address());
            final long vmIdFinal = vmId;
            PortForwardingRuleVO pfRule = Transaction.execute(new TransactionCallbackWithException<PortForwardingRuleVO, NetworkRuleConflictException>() {
                @Override
                public PortForwardingRuleVO doInTransaction(TransactionStatus status) throws NetworkRuleConflictException {
                    PortForwardingRuleVO newRule =
                        new PortForwardingRuleVO(null, publicIpId, userPort, userPort, vmIp, 8080, 8080, "tcp", networkId, accountId, domainId, vmIdFinal);
                    newRule.setDisplay(true);
                    newRule.setState(FirewallRule.State.Add);
                    newRule = portForwardingRulesDao.persist(newRule);
                    return newRule;
                }
            });
            rulesService.applyPortForwardingRules(publicIp.getId(), account);
            if (logger.isInfoEnabled()) {
                logger.info(String.format("Provisioned web access port forwarding rule from port %d to 8080 on %s to the VM IP : %s in Desktop cluster : %s", userPort, publicIp.getAddress().addr(), vmIp.toString(), desktopCluster.getName()));
            }
            PortForwardingRuleVO pfRule2 = Transaction.execute(new TransactionCallbackWithException<PortForwardingRuleVO, NetworkRuleConflictException>() {
                @Override
                public PortForwardingRuleVO doInTransaction(TransactionStatus status) throws NetworkRuleConflictException {
                    PortForwardingRuleVO newRule2 =
                        new PortForwardingRuleVO(null, publicIpId, adminPort, adminPort, vmIp, 8081, 8081, "tcp", networkId, accountId, domainId, vmIdFinal);
                    newRule2.setDisplay(true);
                    newRule2.setState(FirewallRule.State.Add);
                    newRule2 = portForwardingRulesDao.persist(newRule2);
                    return newRule2;
                }
            });
            rulesService.applyPortForwardingRules(publicIp.getId(), account);
            if (logger.isInfoEnabled()) {
                logger.info(String.format("Provisioned web access port forwarding rule from port %d to 8081 on %s to the VM IP : %s in Desktop cluster : %s", adminPort, publicIp.getAddress().addr(), vmIp.toString(), desktopCluster.getName()));
            }
            PortForwardingRuleVO pfRule3 = Transaction.execute(new TransactionCallbackWithException<PortForwardingRuleVO, NetworkRuleConflictException>() {
                @Override
                public PortForwardingRuleVO doInTransaction(TransactionStatus status) throws NetworkRuleConflictException {
                    PortForwardingRuleVO newRule3 =
                        new PortForwardingRuleVO(null, publicIpId, apiPort, apiPort, vmIp, 8082, 8082, "tcp", networkId, accountId, domainId, vmIdFinal);
                    newRule3.setDisplay(true);
                    newRule3.setState(FirewallRule.State.Add);
                    newRule3 = portForwardingRulesDao.persist(newRule3);
                    return newRule3;
                }
            });
            rulesService.applyPortForwardingRules(publicIp.getId(), account);
            if (logger.isInfoEnabled()) {
                logger.info(String.format("Provisioned api access port forwarding rule from port %d to 8082 on %s to the VM IP : %s in Desktop cluster : %s", apiPort, publicIp.getAddress().addr(), vmIp.toString(), desktopCluster.getName()));
            }
            PortForwardingRuleVO pfRule4 = Transaction.execute(new TransactionCallbackWithException<PortForwardingRuleVO, NetworkRuleConflictException>() {
                @Override
                public PortForwardingRuleVO doInTransaction(TransactionStatus status) throws NetworkRuleConflictException {
                    PortForwardingRuleVO newRule4 =
                        new PortForwardingRuleVO(null, publicIpId, sambaPort, sambaPort, vmIp, 9017, 9017, "tcp", networkId, accountId, domainId, vmIdFinal);
                    newRule4.setDisplay(true);
                    newRule4.setState(FirewallRule.State.Add);
                    newRule4 = portForwardingRulesDao.persist(newRule4);
                    return newRule4;
                }
            });
            rulesService.applyPortForwardingRules(publicIp.getId(), account);
            if (logger.isInfoEnabled()) {
                logger.info(String.format("Provisioned samba access port forwarding rule from port %d to 9017 on %s to the VM IP : %s in Desktop cluster : %s", sambaPort, publicIp.getAddress().addr(), vmIp.toString(), desktopCluster.getName()));
            }
            return true;
        }
        return false;
    }
}
