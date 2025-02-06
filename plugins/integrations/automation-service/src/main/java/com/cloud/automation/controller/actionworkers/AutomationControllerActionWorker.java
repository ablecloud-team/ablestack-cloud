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

package com.cloud.automation.controller.actionworkers;

import com.cloud.automation.controller.AutomationController;
import com.cloud.automation.controller.AutomationControllerManagerImpl;
import com.cloud.automation.controller.AutomationControllerVO;
import com.cloud.automation.controller.AutomationControllerVmMap;
import com.cloud.automation.controller.AutomationControllerVmMapVO;
import com.cloud.automation.controller.dao.AutomationControllerDao;
import com.cloud.automation.controller.dao.AutomationControllerVmMapDao;
import com.cloud.automation.resource.dao.AutomationDeployedResourceDao;
import com.cloud.automation.version.AutomationControllerVersionVO;
import com.cloud.automation.version.dao.AutomationControllerVersionDao;
import com.cloud.capacity.CapacityManager;
import com.cloud.dc.ClusterDetailsDao;
import com.cloud.dc.ClusterDetailsVO;
import com.cloud.dc.ClusterVO;
import com.cloud.dc.DataCenter;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.VlanDao;
import com.cloud.deploy.DeployDestination;
import com.cloud.exception.InsufficientServerCapacityException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.network.IpAddress;
import com.cloud.network.Network;
import com.cloud.network.NetworkModel;
import com.cloud.network.NetworkService;
import com.cloud.network.dao.FirewallRulesDao;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.firewall.FirewallService;
import com.cloud.network.rules.FirewallRule;
import com.cloud.network.rules.FirewallRuleVO;
import com.cloud.network.rules.PortForwardingRuleVO;
import com.cloud.network.rules.RulesService;
import com.cloud.network.rules.dao.PortForwardingRulesDao;
import com.cloud.offering.ServiceOffering;
import com.cloud.resource.ResourceManager;
import com.cloud.server.ManagementService;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.template.TemplateApiService;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.user.Account;
import com.cloud.user.AccountService;
import com.cloud.user.dao.AccountDao;
import com.cloud.uservm.UserVm;
import com.cloud.utils.Pair;
import com.cloud.utils.StringUtils;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.fsm.NoTransitionException;
import com.cloud.utils.fsm.StateMachine2;
import com.cloud.vm.UserVmManager;
import com.cloud.vm.UserVmService;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDao;
import org.apache.cloudstack.ca.CAManager;
import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import javax.inject.Inject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import static com.cloud.utils.NumbersUtil.toHumanReadableSize;

public class AutomationControllerActionWorker {

    public static final int CLUSTER_USER_PORTAL_PORT = 8080;
    public static final int CLUSTER_ADMIN_PORTAL_PORT = 8081;
    public static final int CLUSTER_API_PORT = 8082;
    public static final int CLUSTER_SAMBA_PORT = 9017;
    public static final Integer AUTOMATION_CONTROLLER_PORT = null;

    protected Logger logger = LogManager.getLogger(getClass());

    protected StateMachine2<AutomationController.State, AutomationController.Event, AutomationController> _stateMachine = AutomationController.State.getStateMachine();

    @Inject
    protected CAManager caManager;
    @Inject
    protected ConfigurationDao configurationDao;
    @Inject
    protected DataCenterDao dataCenterDao;
    @Inject
    protected AccountDao accountDao;
    @Inject
    protected NetworkOrchestrationService networkMgr;
    @Inject
    protected NetworkDao networkDao;
    @Inject
    protected NetworkModel networkModel;
    @Inject
    protected ServiceOfferingDao serviceOfferingDao;
    @Inject
    protected DiskOfferingDao diskOfferingDao;
    @Inject
    protected VMTemplateDao templateDao;
    @Inject
    protected TemplateApiService templateService;
    @Inject
    protected UserVmDao userVmDao;
    @Inject
    protected UserVmService userVmService;
    @Inject
    protected VlanDao vlanDao;
    @Inject
    protected AccountService accountService;
    @Inject
    protected ManagementService managementService;
    @Inject
    protected NetworkService networkService;
    @Inject
    protected IPAddressDao ipAddressDao;
    @Inject
    protected UserVmManager userVmManager;
    @Inject
    protected VMInstanceDao vmInstanceDao;
    @Inject
    protected PortForwardingRulesDao portForwardingRulesDao;
    @Inject
    protected FirewallRulesDao firewallRulesDao;
    @Inject
    protected FirewallService firewallService;
    @Inject
    protected RulesService rulesService;
    @Inject
    protected ResourceManager resourceManager;
    @Inject
    protected HostDao hostDao;
    @Inject
    protected ClusterDao clusterDao;
    @Inject
    protected ClusterDetailsDao clusterDetailsDao;
    @Inject
    protected CapacityManager capacityManager;

    protected AutomationDeployedResourceDao automationDeployedResourceDao;
    protected AutomationControllerDao automationControllerDao;
    protected AutomationControllerVmMapDao automationControllerVmMapDao;
    protected VirtualMachineTemplate templates;
    protected AutomationController automationController;
    protected AutomationControllerVmMap automationControllerVmMap;
    protected AutomationControllerVersionDao automationControllerVersionDao;
    protected Account owner;
    protected String publicIpAddress;

    protected AutomationControllerActionWorker(final AutomationController automationController, final AutomationControllerManagerImpl automationManager) {
        this.automationControllerVersionDao = automationManager.automationControllerVersionDao;
        this.automationController = automationController;
        this.automationControllerDao = automationManager.automationControllerDao;
        this.automationControllerVmMapDao = automationManager.automationControllerVmMapDao;
        this.automationDeployedResourceDao = automationManager.automationDeployedResourceDao;
    }

    protected void init() {
        this.owner = accountDao.findById(automationController.getAccountId());
        List<AutomationControllerVersionVO> templateList = automationControllerVersionDao.listByVersionId(automationController.getAutomationTemplateId());
        for (AutomationControllerVersionVO templateMapVO : templateList) {
            this.templates = templateDao.findById(templateMapVO.getTemplateId());
        }
    }

    protected String readResourceFile(String resource) throws IOException {
        return IOUtils.toString(Objects.requireNonNull(Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)), StringUtils.getPreferredCharset());
    }

    protected void logMessage(final Level logLevel, final String message, final Exception e) {
        if (logLevel == Level.INFO) {
            if (logger.isInfoEnabled()) {
                if (e != null) {
                    logger.info(message, e);
                } else {
                    logger.info(message);
                }
            }
        } else if (logLevel == Level.DEBUG) {
            if (logger.isDebugEnabled()) {
                if (e != null) {
                    logger.debug(message, e);
                } else {
                    logger.debug(message);
                }
            }
        } else if (logLevel == Level.WARN) {
            if (e != null) {
                logger.warn(message, e);
            } else {
                logger.warn(message);
            }
        } else {
            if (e != null) {
                logger.error(message, e);
            } else {
                logger.error(message);
            }
        }
    }

    protected void logTransitStateAndThrow(final Level logLevel, final String message, final Long automationControllerId, final AutomationController.Event event, final Exception e) throws CloudRuntimeException {
        logMessage(logLevel, message, e);
        if (automationControllerId != null && event != null) {
            stateTransitTo(automationControllerId, event);
        }
        if (e == null) {
            throw new CloudRuntimeException(message);
        }
        throw new CloudRuntimeException(message, e);
    }

    protected void logTransitStateAndThrow(final Level logLevel, final String message, final Long automationControllerId, final AutomationController.Event event) throws CloudRuntimeException {
        logTransitStateAndThrow(logLevel, message, automationControllerId, event, null);
    }

    protected void logAndThrow(final Level logLevel, final String message) throws CloudRuntimeException {
        logTransitStateAndThrow(logLevel, message, null, null, null);
    }

    protected void logAndThrow(final Level logLevel, final String message, final Exception ex) throws CloudRuntimeException {
        logTransitStateAndThrow(logLevel, message, null, null, ex);
    }

    protected AutomationControllerVmMapVO addAutomationControllerVm(final long automationControllerId, final long vmId) {
        return Transaction.execute(new TransactionCallback<AutomationControllerVmMapVO>() {
            @Override
            public AutomationControllerVmMapVO doInTransaction(TransactionStatus status) {
                AutomationControllerVmMapVO newClusterVmMap = new AutomationControllerVmMapVO(automationControllerId, vmId);
                automationControllerVmMapDao.persist(newClusterVmMap);
                return newClusterVmMap;
            }
        });
    }

    protected List<AutomationControllerVmMapVO> getControlVMMaps() {
        List<AutomationControllerVmMapVO> automationControllerVMs = automationControllerVmMapDao.listByAutomationControllerId(automationController.getId());
        if (!CollectionUtils.isEmpty(automationControllerVMs)) {
            automationControllerVMs.sort((t1, t2) -> (int)((t1.getId() - t2.getId())/Math.abs(t1.getId() - t2.getId())));
        }
        return automationControllerVMs;
    }

    protected List<UserVm> getAutomationControllerVMs() {
        List<UserVm> vmList = new ArrayList<>();
        List<AutomationControllerVmMapVO> automationControllerVMs = getControlVMMaps();
        if (!CollectionUtils.isEmpty(automationControllerVMs)) {
            for (AutomationControllerVmMapVO vmMap : automationControllerVMs) {
                vmList.add(userVmDao.findById(vmMap.getVmId()));
            }
        }
        return vmList;
    }

    protected boolean stateTransitTo(long automationControllerId, AutomationController.Event e) {
        AutomationControllerVO automationController = automationControllerDao.findById(automationControllerId);
        try {
            return _stateMachine.transitTo(automationController, e, null, automationControllerDao);
        } catch (NoTransitionException nte) {
            logger.warn(String.format("Failed to transition state of the automation automation : %s in state %s on event %s",
            automationController.getName(), automationController.getState().toString(), e.toString()), nte);
            return false;
        }
    }

    protected IpAddress getAutomationControllerServerIp() {
        Network network = networkDao.findById(automationController.getNetworkId());
        if (network == null) {
            logger.warn(String.format("Network for automation controller : %s cannot be found", automationController.getName()));
            return null;
        }
        if (Network.GuestType.Isolated.equals(network.getGuestType())) {
            List<? extends IpAddress> addresses = networkModel.listPublicIpsAssignedToGuestNtwk(network.getId(), true);
            if (CollectionUtils.isEmpty(addresses)) {
                logger.warn(String.format("No public IP addresses found for network : %s, automation controller : %s", network.getName(), automationController.getName()));
                return null;
            }
            for (IpAddress address : addresses) {
                if (address.isSourceNat()) {
                    return address;
                }
            }
            logger.warn(String.format("No source NAT IP addresses found for network : %s, automation controller : %s", network.getName(), automationController.getName()));
            return null;
        }
        logger.warn(String.format("Unable to retrieve server IP address for automation controller : %s", automationController.getName()));
        return null;
    }

    protected void removeFirewallIngressRule(final IpAddress publicIp) {
        List<FirewallRuleVO> firewallRules = firewallRulesDao.listByIpAndPurposeAndNotRevoked(publicIp.getId(), FirewallRule.Purpose.Firewall);
        for (FirewallRuleVO firewallRule : firewallRules) {
            if (firewallRule.getSourcePortStart() != null && firewallRule.getSourcePortEnd() != null) {
                if (firewallRule.getSourcePortStart() == CLUSTER_USER_PORTAL_PORT &&
                        firewallRule.getSourcePortEnd() == CLUSTER_API_PORT && firewallRule.getTrafficType() == FirewallRule.TrafficType.Ingress) {
                    firewallService.revokeIngressFwRule(firewallRule.getId(), true);
                }
                if (firewallRule.getSourcePortStart() == CLUSTER_SAMBA_PORT &&
                        firewallRule.getSourcePortEnd() == CLUSTER_SAMBA_PORT && firewallRule.getTrafficType() == FirewallRule.TrafficType.Ingress) {
                    firewallService.revokeIngressFwRule(firewallRule.getId(), true);
                }
            }
        }
    }

    protected void removeFirewallEgressRule(final Network network) {
        List<FirewallRuleVO> firewallRules = firewallRulesDao.listByNetworkAndPurposeAndNotRevoked(network.getId(), FirewallRule.Purpose.Firewall);
        for (FirewallRuleVO firewallRule : firewallRules) {
            if (firewallRule.getSourcePortStart() != null && firewallRule.getSourcePortEnd() != null) {
                if (firewallRule.getSourcePortStart() == CLUSTER_USER_PORTAL_PORT && firewallRule.getSourcePortEnd() == CLUSTER_ADMIN_PORTAL_PORT && firewallRule.getTrafficType() == FirewallRule.TrafficType.Egress) {
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
//                if (!h.getHypervisorType().equals(worksTemplate.getHypervisorType())) {
//                    continue;
//                }
                hostDao.loadHostTags(h);
                if (org.apache.commons.lang3.StringUtils.isNotEmpty(offering.getHostTag()) && !(h.getHostTags() != null && h.getHostTags().contains(offering.getHostTag()))) {
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
        }
        if (suitable_host_found) {
            if (logger.isInfoEnabled()) {
                logger.info(String.format("Suitable hosts found in datacenter : %s, creating deployment destination", zone.getName()));
            }
            return new DeployDestination(zone, null, null, null);
        }
        String msg = String.format("Cannot find enough capacity for automation controller(requested cpu=%d memory=%s) with offering : %s and hypervisor: %s",
                cpu_requested * nodesCount, toHumanReadableSize(ram_requested * nodesCount), offering.getName());

        logger.warn(msg);
        throw new InsufficientServerCapacityException(msg, DataCenter.class, zone.getId());
    }

    protected DeployDestination plan() throws InsufficientServerCapacityException {
        ServiceOffering offering = serviceOfferingDao.findById(automationController.getServiceOfferingId());
        DataCenter zone = dataCenterDao.findById(automationController.getZoneId());
        if (logger.isDebugEnabled()) {
            logger.debug(String.format("Checking deployment destination for automation controller : %s in zone : %s", automationController.getName(), zone.getName()));
        }
        final long dest = 2;
        return plan(dest, zone, offering);
    }

}
