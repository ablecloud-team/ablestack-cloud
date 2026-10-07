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

import com.cloud.event.ActionEventUtils;
import com.cloud.event.EventVO;
import com.cloud.exception.ManagementServerException;
import com.cloud.kubernetes.cluster.KubernetesCluster;
import com.cloud.kubernetes.cluster.KubernetesClusterEventTypes;
import com.cloud.kubernetes.cluster.KubernetesClusterManagerImpl;
import com.cloud.kubernetes.cluster.KubernetesClusterService;
import com.cloud.kubernetes.cluster.KubernetesClusterVO;
import com.cloud.kubernetes.cluster.KubernetesClusterDetailsVO;
import com.cloud.server.ResourceTag.ResourceObjectType;
import com.cloud.network.IpAddress;
import com.cloud.network.Network;
import com.cloud.network.dao.FirewallRulesDao;
import com.cloud.network.rules.FirewallRuleVO;
import com.cloud.network.rules.FirewallRule;
import com.cloud.network.rules.PortForwardingRuleVO;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.ssh.SshHelper;
import com.cloud.vm.UserVmVO;
import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.context.CallContext;

import javax.inject.Inject;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.commons.lang3.StringUtils;
import java.util.Objects;
import java.util.Optional;

public class KubernetesClusterRemoveWorker extends KubernetesClusterActionWorker {

    @Inject
    private FirewallRulesDao firewallRulesDao;

    private long removeNodeTimeoutTime;

    public KubernetesClusterRemoveWorker(KubernetesCluster kubernetesCluster, KubernetesClusterManagerImpl clusterManager) {
        super(kubernetesCluster, clusterManager);
    }

    public boolean removeNodesFromCluster(List<Long> nodeIds) {
        init();
        removeNodeTimeoutTime = System.currentTimeMillis() + KubernetesClusterService.KubernetesClusterRemoveNodeTimeout.value() * 1000;
        Long networkId = kubernetesCluster.getNetworkId();
        Network network = networkDao.findById(networkId);
        if (Objects.isNull(network)) {
            throw new CloudRuntimeException(String.format("Failed to find network with id: %s", networkId));
        }
        IpAddress publicIp = null;
        try {
            publicIp = getPublicIp(network);
        } catch (ManagementServerException e) {
            throw new CloudRuntimeException(String.format("Failed to retrieve public IP for the network: %s ", network.getName()));
        }
        if (!stateTransitTo(kubernetesCluster.getId(), KubernetesCluster.Event.RemoveNodeRequested)) {
            throw new CloudRuntimeException("Another Kubernetes cluster operation prevents external node removal");
        }
        boolean result;
        try {
            result = removeNodesFromCluster(nodeIds, network, publicIp);
        } catch (RuntimeException e) {
            stateTransitTo(kubernetesCluster.getId(), KubernetesCluster.Event.OperationFailed);
            throw new CloudRuntimeException("External node removal is incomplete; retry receipts are retained", e);
        }
        if (!result) {
            stateTransitTo(kubernetesCluster.getId(), KubernetesCluster.Event.OperationFailed);
        } else {
            stateTransitTo(kubernetesCluster.getId(), KubernetesCluster.Event.OperationSucceeded);
        }
        String description = result ? String.format("Successfully removed %s nodes from the Kubernetes Cluster %s", nodeIds.size(), kubernetesCluster.getUuid())
                : String.format("External node removal is incomplete for Kubernetes Cluster %s; failed node mappings are retained", kubernetesCluster.getUuid());
        ActionEventUtils.onCompletedActionEvent(CallContext.current().getCallingUserId(), CallContext.current().getCallingAccountId(),
                result ? EventVO.LEVEL_INFO : EventVO.LEVEL_ERROR, KubernetesClusterEventTypes.EVENT_KUBERNETES_CLUSTER_NODES_REMOVE,
                description, kubernetesCluster.getId(), ApiCommandResourceType.KubernetesCluster.toString(), 0);
        return result;
    }

    protected boolean removeNodesFromCluster(List<Long> nodeIds, Network network, IpAddress publicIp) {
        boolean result = true;
        List<Long> removedNodeIds = new ArrayList<>();
        long removedMemory = 0L;
        long removedCores = 0L;
        for (Long nodeId : nodeIds) {
            UserVmVO vm = userVmDao.findById(nodeId);
            if (vm == null) {
                logger.debug(String.format("Couldn't find a VM with ID %s, skipping removal from Kubernetes cluster", nodeId));
                result = false;
                continue;
            }
            try {
                String nodeName = StringUtils.defaultIfBlank(vm.getHostName(), vm.getDisplayName()).toLowerCase(Locale.ROOT);
                prepareNodeRemovalRules(vm, network, publicIp);
                String completedKey = "external.remove.native." + vm.getUuid();
                if (kubernetesClusterDetailsDao.findDetail(kubernetesCluster.getId(), completedKey) == null) {
                    removeNodeVmFromCluster(nodeId, nodeName, publicIp.getAddress().addr());
                    kubernetesClusterDetailsDao.addDetail(kubernetesCluster.getId(), completedKey, nodeName, false);
                }
                if (!removeNodePortForwardingRules(nodeId, network, vm)) {
                    result = false;
                    continue;
                }
                if (System.currentTimeMillis() > removeNodeTimeoutTime) {
                    logger.error(String.format("Removal of node %s from Kubernetes cluster %s timed out", vm.getName(), kubernetesCluster.getName()));
                    result = false;
                    continue;
                }
                ServiceOfferingVO offeringVO = serviceOfferingDao.findById(vm.getId(), vm.getServiceOfferingId());
                removedNodeIds.add(nodeId);
                removedMemory += offeringVO.getRamSize();
                removedCores += offeringVO.getCpu();
                String description = String.format("Successfully removed the node %s from Kubernetes cluster %s", vm.getUuid(), kubernetesCluster.getUuid());
                logger.info(description);
                recordNodeRemovalEvent(description, vm.getId());
            } catch (Exception e) {
                String err = String.format("Error trying to remove node %s from Kubernetes Cluster %s: %s", vm.getUuid(), kubernetesCluster.getUuid(), e.getMessage());
                logger.error(err, e);
                result = false;
            }
        }
        if (!removedNodeIds.isEmpty()) {
            updateKubernetesCluster(kubernetesCluster.getId(), removedNodeIds, removedMemory, removedCores);
            for (Long id : removedNodeIds) {
                UserVmVO vm = userVmDao.findById(id);
                kubernetesClusterDetailsDao.removeDetail(kubernetesCluster.getId(), "external.remove.native." + vm.getUuid());
                kubernetesClusterDetailsDao.removeDetail(kubernetesCluster.getId(), "external.remove.rules." + vm.getUuid());
            }
        }
        return result;
    }

    protected void recordNodeRemovalEvent(String description, long vmId) {
        ActionEventUtils.onCompletedActionEvent(CallContext.current().getCallingUserId(), CallContext.current().getCallingAccountId(),
                EventVO.LEVEL_INFO, KubernetesClusterEventTypes.EVENT_KUBERNETES_CLUSTER_NODES_REMOVE,
                description, vmId, ApiCommandResourceType.VirtualMachine.toString(), 0);
    }

    protected void prepareNodeRemovalRules(UserVmVO vm, Network network, IpAddress publicIp) {
        String key = "external.remove.rules." + vm.getUuid();
        if (kubernetesClusterDetailsDao.findDetail(kubernetesCluster.getId(), key) != null) {
            return;
        }
        List<String> receipts = new ArrayList<>();
        for (PortForwardingRuleVO rule : portForwardingRulesDao.listByVm(vm.getId())) {
            KubernetesOwnedResourceReceipt receipt = findOwnedNativeRule(rule, network, publicIp);
            if (receipt == null) {
                continue;
            }
            receipts.add(receipt.encode());
            if (network.getVpcId() == null) {
                for (FirewallRuleVO firewall : firewallRulesDao.listByIpPurposeProtocolAndNotRevoked(publicIp.getId(), FirewallRule.Purpose.Firewall, "tcp")) {
                    if (rule.getSourcePortStart() != null && rule.getSourcePortEnd() != null
                            && Objects.equals(firewall.getSourcePortStart(), rule.getSourcePortStart())
                            && Objects.equals(firewall.getSourcePortEnd(), rule.getSourcePortEnd())) {
                        KubernetesOwnedResourceReceipt firewallReceipt = findOwnedNativeRule(firewall, network, publicIp);
                        if (firewallReceipt != null && !receipts.contains(firewallReceipt.encode())) {
                            receipts.add(firewallReceipt.encode());
                        }
                    }
                }
            }
        }
        if (receipts.isEmpty()) {
            throw new CloudRuntimeException("External Kubernetes node SSH rules have no verified cluster ownership");
        }
        kubernetesClusterDetailsDao.addDetail(kubernetesCluster.getId(), key, String.join("\n", receipts), false);
    }

    protected boolean removeNodePortForwardingRules(Long nodeId, Network network, UserVmVO vm) {
        KubernetesClusterDetailsVO detail = kubernetesClusterDetailsDao.findDetail(kubernetesCluster.getId(), "external.remove.rules." + vm.getUuid());
        if (detail == null) {
            return false;
        }
        try {
            IpAddress address = getPublicIp(network);
            for (String encoded : detail.getValue().split("\n")) {
                KubernetesOwnedResourceReceipt receipt = KubernetesOwnedResourceReceipt.decode(encoded);
                FirewallRuleVO rule;
                if (receipt.type == ResourceObjectType.PortForwardingRule) {
                    PortForwardingRuleVO forwarding = portForwardingRulesDao.findById(receipt.id);
                    if (forwarding != null && forwarding.getVirtualMachineId() != nodeId) {
                        throw new CloudRuntimeException("External node port forwarding target changed");
                    }
                    rule = forwarding;
                } else if (receipt.type == ResourceObjectType.FirewallRule) {
                    rule = firewallRulesDao.findById(receipt.id);
                } else {
                    throw new CloudRuntimeException("Unexpected external node removal receipt type");
                }
                if (rule == null || rule.getRemoved() != null) {
                    continue;
                }
                validateOwnedNodeRule(receipt, rule, network, address);
                boolean revoked = receipt.type == ResourceObjectType.PortForwardingRule
                        ? rulesService.revokePortForwardingRule(receipt.id, true)
                        : firewallService.revokeIngressFirewallRule(receipt.id, true);
                if (!revoked) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            logger.error("External node rule cleanup is incomplete; mapping and retry receipts are retained", e);
            return false;
        }
    }

    protected Pair<Boolean, String> executeNodeRemoval(String publicIp, int port, String command) throws Exception {
        return SshHelper.sshExecute(publicIp, port, getControlNodeLoginUser(), getManagementServerSshPublicKeyFile(),
                null, command, 10000, 10000, 3 * 60 * 1000);
    }

    protected void requireNodeRemovalCommand(String publicIp, int port, String command, String stage) throws Exception {
        Pair<Boolean, String> result = executeNodeRemoval(publicIp, port, command);
        if (!Boolean.TRUE.equals(result.first())) {
            // Do not publish remote command output or configuration in the failure response.
            throw new CloudRuntimeException("External Kubernetes node removal failed at " + stage);
        }
    }

    protected void removeNodeVmFromCluster(Long nodeId, String nodeName, String publicIp) throws Exception {
        if (StringUtils.isBlank(nodeName) || !nodeName.matches("[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?")) {
            throw new CloudRuntimeException("Invalid external Kubernetes node hostname");
        }
        List<PortForwardingRuleVO> rules = portForwardingRulesDao.listByVm(nodeId);
        Network network = networkDao.findById(kubernetesCluster.getNetworkId());
        IpAddress address = getPublicIp(network);
        Optional<PortForwardingRuleVO> nodeSshPort = rules.stream().filter(rule -> rule.getDestinationPortStart() == DEFAULT_SSH_PORT
                && rule.getVirtualMachineId() == nodeId && findOwnedNativeRule(rule, network, address) != null).findFirst();
        if (nodeSshPort.isEmpty()) {
            throw new CloudRuntimeException("External Kubernetes node SSH mapping is missing; node removal is incomplete");
        }
        File script = retrieveScriptFile(removeNodeFromClusterScript);
        Pair<String, Integer> control = getKubernetesClusterServerIpSshPort(null);
        copyScriptFile(control.first(), control.second(), script, removeNodeFromClusterScript);
        String prefix = String.format("sudo %s%s %s", scriptPath, removeNodeFromClusterScript, nodeName);
        requireNodeRemovalCommand(control.first(), control.second(), prefix + " control remove", "DRAIN");
        copyScriptFile(publicIp, nodeSshPort.get().getSourcePortStart(), script, removeNodeFromClusterScript);
        requireNodeRemovalCommand(publicIp, nodeSshPort.get().getSourcePortStart(), prefix + " worker remove", "RESET");
        requireNodeRemovalCommand(control.first(), control.second(), prefix + " control delete", "NODE_DELETE");
    }

    protected void updateKubernetesCluster(long clusterId, List<Long> nodesRemoved, long deallocatedRam, long deallocatedCores) {
        KubernetesClusterVO kubernetesClusterVO = kubernetesClusterDao.findById(clusterId);
        kubernetesClusterVO.setNodeCount(kubernetesClusterVO.getNodeCount() - nodesRemoved.size());
        kubernetesClusterVO.setMemory(kubernetesClusterVO.getMemory() - deallocatedRam);
        kubernetesClusterVO.setCores(kubernetesClusterVO.getCores() - deallocatedCores);
        kubernetesClusterDao.update(clusterId, kubernetesClusterVO);

        if (!nodesRemoved.isEmpty()) {
            kubernetesClusterVmMapDao.removeByClusterIdAndVmIdsIn(clusterId, nodesRemoved);
        }
    }
}
