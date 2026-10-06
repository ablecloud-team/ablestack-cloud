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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Comparator;
import java.util.Set;
import java.io.File;
import java.util.stream.Collectors;

import javax.inject.Inject;

import com.cloud.bgp.BGPService;
import com.cloud.dc.ASNumberVO;
import com.cloud.dc.DataCenter;
import com.cloud.dc.dao.ASNumberDao;
import org.apache.cloudstack.annotation.AnnotationService;
import org.apache.cloudstack.annotation.dao.AnnotationDao;
import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.context.CallContext;
import org.apache.commons.collections.CollectionUtils;

import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientAddressCapacityException;
import com.cloud.exception.ManagementServerException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.kubernetes.cluster.KubernetesCluster;
import com.cloud.kubernetes.cluster.KubernetesClusterDetailsVO;
import com.cloud.kubernetes.cluster.KubernetesClusterManagerImpl;
import com.cloud.kubernetes.cluster.KubernetesClusterVO;
import com.cloud.kubernetes.cluster.KubernetesClusterVmMap;
import com.cloud.kubernetes.cluster.KubernetesClusterVmMapVO;
import com.cloud.network.IpAddress;
import com.cloud.network.Network;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.LoadBalancerVO;
import com.cloud.network.dao.LoadBalancerVMMapDao;
import com.cloud.network.dao.LoadBalancerVMMapVO;
import com.cloud.network.rules.FirewallRuleVO;
import com.cloud.network.vpc.NetworkACLItemVO;
import com.cloud.server.ResourceTag;
import com.cloud.server.ResourceTag.ResourceObjectType;
import com.cloud.tags.ResourceTagVO;
import com.cloud.tags.dao.ResourceTagDao;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.Pair;
import com.cloud.utils.ssh.SshHelper;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.rules.FirewallRule;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.User;
import com.cloud.uservm.UserVm;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.ReservationContext;
import com.cloud.vm.ReservationContextImpl;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import org.apache.logging.log4j.Level;

public class KubernetesClusterDestroyWorker extends KubernetesClusterResourceModifierActionWorker {

    @Inject
    protected AccountManager accountManager;
    @Inject
    private AnnotationDao annotationDao;
    @Inject
    private ASNumberDao asNumberDao;
    @Inject
    private BGPService bgpService;

    @Inject
    protected ResourceTagDao resourceTagDao;
    @Inject
    protected LoadBalancerVMMapDao loadBalancerVMMapDao;

    private List<KubernetesClusterVmMapVO> clusterVMs;

    public KubernetesClusterDestroyWorker(final KubernetesCluster kubernetesCluster, final KubernetesClusterManagerImpl clusterManager) {
        super(kubernetesCluster, clusterManager);
    }

    private void validateClusterSate() {
        if (!(kubernetesCluster.getState().equals(KubernetesCluster.State.Running)
                || kubernetesCluster.getState().equals(KubernetesCluster.State.Stopped)
                || kubernetesCluster.getState().equals(KubernetesCluster.State.Alert)
                || kubernetesCluster.getState().equals(KubernetesCluster.State.Error)
                || kubernetesCluster.getState().equals(KubernetesCluster.State.Destroying))) {
            String msg = String.format("Cannot perform delete operation on cluster %s in state: %s",
                    kubernetesCluster, kubernetesCluster.getState());
            logger.warn(msg);
            throw new PermissionDeniedException(msg);
        }
    }

    private boolean destroyClusterVMs() {
        boolean vmDestroyed = true;
        if (!CollectionUtils.isEmpty(clusterVMs)) {
            for (KubernetesClusterVmMapVO clusterVM : clusterVMs) {
                long vmID = clusterVM.getVmId();

                // delete only if VM exists and is not removed
                UserVmVO userVM = userVmDao.findById(vmID);
                if (userVM == null || userVM.isRemoved()) {
                    continue;
                }
                CallContext vmContext = CallContext.register(CallContext.current(),
                        ApiCommandResourceType.VirtualMachine);
                vmContext.setEventResourceId(vmID);
                try {
                    UserVm vm = userVmService.destroyVm(vmID, true);
                    if (!userVmManager.expunge(userVM)) {
                        logger.warn("Unable to expunge VM {}, destroying Kubernetes cluster will probably fail", vm);
                    }
                    kubernetesClusterVmMapDao.expunge(clusterVM.getId());
                    if (logger.isInfoEnabled()) {
                        logger.info("Destroyed VM {} as part of Kubernetes cluster : {} cleanup", vm, kubernetesCluster);
                    }
                } catch (ResourceUnavailableException | ConcurrentOperationException e) {
                    logger.warn("Failed to destroy VM {} part of the Kubernetes cluster {} " +
                            "cleanup. Moving on with destroying remaining resources provisioned " +
                            "for the Kubernetes cluster", userVM, kubernetesCluster, e);
                    return false;
                } finally {
                    CallContext.unregister();
                }
            }
        }
        return vmDestroyed;
    }

    private boolean updateKubernetesClusterEntryForGC() {
        KubernetesClusterVO kubernetesClusterVO = kubernetesClusterDao.findById(kubernetesCluster.getId());
        kubernetesClusterVO.setCheckForGc(true);
        return kubernetesClusterDao.update(kubernetesCluster.getId(), kubernetesClusterVO);
    }

    private void destroyKubernetesClusterNetwork() throws ManagementServerException {
        NetworkVO network = networkDao.findById(kubernetesCluster.getNetworkId());
        if (network != null && network.getRemoved() == null) {
            Account owner = accountManager.getAccount(network.getAccountId());
            User callerUser = accountManager.getActiveUser(CallContext.current().getCallingUserId());
            ReservationContext context = new ReservationContextImpl(null, null, callerUser, owner);
            releaseASNumber(kubernetesCluster.getZoneId(), kubernetesCluster.getNetworkId());
            boolean networkDestroyed = networkMgr.destroyNetwork(kubernetesCluster.getNetworkId(), context, true);
            if (!networkDestroyed) {
                String msg = String.format("Failed to destroy network: %s as part of Kubernetes cluster: %s cleanup", network, kubernetesCluster);
                logger.warn(msg);
                throw new ManagementServerException(msg);
            }
            if (logger.isInfoEnabled()) {
                logger.info("Destroyed network: {} as part of Kubernetes cluster: {} cleanup", network, kubernetesCluster);
            }
        }
    }

    private void releaseASNumber(Long zoneId, long networkId) {
        DataCenter zone = dataCenterDao.findById(zoneId);
        ASNumberVO asNumber = asNumberDao.findByZoneAndNetworkId(zone.getId(), networkId);
        if (asNumber != null) {
            logger.debug(String.format("Releasing AS number %s from network %s", asNumber.getAsNumber(), networkId));
            bgpService.releaseASNumber(zone.getId(), asNumber.getAsNumber(), true);
        }
    }

    protected void deleteKubernetesClusterIsolatedNetworkRules(Network network, List<Long> removedVmIds) throws ManagementServerException {
        IpAddress publicIp = getNetworkSourceNatIp(network);
        if (publicIp == null) {
            throw new ManagementServerException(String.format("No source NAT IP addresses found for network : %s", network.getName()));
        }
        // API LB rules are removed only from manager receipts validated before VM destruction.
        FirewallRule firewallRule = null;
        if (firewallRule == null) {
            logMessage(Level.WARN, "Firewall rule for API access can't be removed", null);
        }
        firewallRule = null;
        if (firewallRule == null) {
            logMessage(Level.WARN, "Firewall rule for SSH access can't be removed", null);
        }
        try {
            removePortForwardingRules(publicIp, network, owner, removedVmIds);
        } catch (ResourceUnavailableException e) {
            throw new ManagementServerException(String.format("Failed to KubernetesCluster port forwarding rules for network : %s", network.getName()), e);
        }
    }

    protected void deleteKubernetesClusterVpcTierRules(Network network, List<Long> removedVmIds) throws ManagementServerException {
        IpAddress publicIp = getVpcTierKubernetesPublicIp(network);
        if (publicIp == null) {
            return;
        }
        // Only manager-created ACL receipts are removed by cleanupNativeAclResources().
        try {
            removePortForwardingRules(publicIp, network, owner, removedVmIds);
        } catch (ResourceUnavailableException e) {
            throw new ManagementServerException(String.format("Failed to KubernetesCluster port forwarding rules for network : %s", network.getName()));
        }
    }

    private void deleteKubernetesClusterNetworkRules() throws ManagementServerException {
        NetworkVO network = networkDao.findById(kubernetesCluster.getNetworkId());
        if (network == null) {
            return;
        }
        List<Long> removedVmIds = new ArrayList<>();
        if (!CollectionUtils.isEmpty(clusterVMs)) {
            removedVmIds = clusterVMs.stream().map(KubernetesClusterVmMapVO::getVmId).collect(Collectors.toList());
        }
        if (network.getVpcId() != null) {
            deleteKubernetesClusterVpcTierRules(network, removedVmIds);
            return;
        }
        deleteKubernetesClusterIsolatedNetworkRules(network, removedVmIds);
    }

    private void validateClusterVMsDestroyed() {
        if(clusterVMs!=null  && !clusterVMs.isEmpty()) { // Wait for few seconds to get all VMs really expunged
            final int maxRetries = 3;
            int retryCounter = 0;
            while (retryCounter < maxRetries) {
                boolean allVMsRemoved = true;
                for (KubernetesClusterVmMap clusterVM : clusterVMs) {
                    UserVmVO userVM = userVmDao.findById(clusterVM.getVmId());
                    if (userVM != null && !userVM.isRemoved()) {
                        allVMsRemoved = false;
                        break;
                    }
                }
                if (allVMsRemoved) {
                    break;
                }
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException ie) {}
                retryCounter++;
            }
        }
    }

    private void checkForRulesToDelete() throws ManagementServerException {
        NetworkVO kubernetesClusterNetwork = networkDao.findById(kubernetesCluster.getNetworkId());
        if (kubernetesClusterNetwork != null && !manager.isDirectAccess(kubernetesClusterNetwork)) {
            deleteKubernetesClusterNetworkRules();
        }
    }

    private void releaseVpcTierPublicIpIfNeeded() throws InsufficientAddressCapacityException {
        NetworkVO networkVO = networkDao.findById(kubernetesCluster.getNetworkId());
        if (networkVO == null || networkVO.getVpcId() == null) {
            return;
        }
        IpAddress address = getVpcTierKubernetesPublicIp(networkVO);
        if (address == null) {
            return;
        }
        kubernetesClusterDetailsDao.addDetail(kubernetesCluster.getId(), "cleanup.retained." + address.getUuid(), "UnverifiedLegacyVpcIp", false);
    }

    protected boolean hasUnclaimedNetworkResources() {
        for (IPAddressVO address : ipAddressDao.listByAssociatedNetwork(kubernetesCluster.getNetworkId(), null)) {
            if (!address.isSourceNat() || !firewallRulesDao.listByIpAndNotRevoked(address.getId()).isEmpty()) {
                return true;
            }
        }
        NetworkVO network = networkDao.findById(kubernetesCluster.getNetworkId());
        if (network != null && network.getNetworkACLId() != null && !networkACLItemDao.listByACL(network.getNetworkACLId()).isEmpty()) {
            return true;
        }
        return false;
    }

    protected void recordVerifiedLegacyApiLoadBalancer() {
        Network network = networkDao.findById(kubernetesCluster.getNetworkId());
        if (network == null || manager.isDirectAccess(network)) {
            return;
        }
        IpAddress address = network.getVpcId() == null ? getNetworkSourceNatIp(network) : getVpcTierKubernetesPublicIp(network);
        if (address == null) {
            return;
        }
        Set<Long> controls = clusterVMs.stream().filter(KubernetesClusterVmMapVO::isControlNode)
                .map(KubernetesClusterVmMapVO::getVmId).collect(Collectors.toSet());
        if (controls.isEmpty()) {
            return;
        }
        for (LoadBalancerVO rule : loadBalancerDao.listByIpAddress(address.getId())) {
            if (rule.getAccountId() != kubernetesCluster.getAccountId() || !Long.valueOf(network.getId()).equals(rule.getNetworkId())
                    || rule.getSourcePortStart() != CLUSTER_API_PORT || rule.getSourcePortEnd() != CLUSTER_API_PORT
                    || !"api-lb".equals(rule.getName()) || !resourceTagDao.listBy(rule.getId(), ResourceObjectType.LoadBalancer).isEmpty()) {
                continue;
            }
            Set<Long> members = loadBalancerVMMapDao.listByLoadBalancerId(rule.getId(), false).stream()
                    .map(LoadBalancerVMMapVO::getInstanceId).collect(Collectors.toSet());
            if (members.equals(controls)) {
                // Resource/account/network and exact native control membership prove the legacy association.
                recordNativeNetworkResource(ResourceObjectType.LoadBalancer, rule.getId(), rule.getUuid(), address);
            }
        }
    }

    protected boolean ownershipCleanupEnabled() {
        KubernetesClusterDetailsVO detail = kubernetesClusterDetailsDao.findDetail(kubernetesCluster.getId(), "provider.ownership.v1");
        return detail != null && "true".equals(detail.getValue());
    }

    protected List<KubernetesOwnedResourceReceipt> recordOwnedNetworkResources(boolean includeNative) {
        NetworkVO network = networkDao.findById(kubernetesCluster.getNetworkId());
        if (network == null) {
            throw new CloudRuntimeException("Cannot verify the Kubernetes cleanup network");
        }
        SearchBuilder<ResourceTagVO> builder = resourceTagDao.createSearchBuilder();
        builder.and("key", builder.entity().getKey(), SearchCriteria.Op.EQ);
        builder.and("value", builder.entity().getValue(), SearchCriteria.Op.EQ);
        builder.and("account", builder.entity().getAccountId(), SearchCriteria.Op.EQ);
        SearchCriteria<ResourceTagVO> criteria = builder.create();
        criteria.setParameters("key", KubernetesOwnedResourceReceipt.CLUSTER);
        criteria.setParameters("value", kubernetesCluster.getUuid());
        criteria.setParameters("account", kubernetesCluster.getAccountId());
        for (ResourceTagVO tag : resourceTagDao.search(criteria, null)) {
            ResourceObjectType type = tag.getResourceType();
            if (!(type == ResourceObjectType.LoadBalancer || type == ResourceObjectType.FirewallRule
                    || type == ResourceObjectType.NetworkACL || type == ResourceObjectType.PublicIpAddress)) {
                continue;
            }
            Map<String, String> tags = new HashMap<>();
            for (ResourceTag item : resourceTagDao.listBy(tag.getResourceId(), type)) {
                tags.put(item.getKey(), item.getValue());
            }
            KubernetesOwnedResourceReceipt receipt = KubernetesOwnedResourceReceipt.fromTags(type, tag.getResourceId(),
                    tag.getResourceUuid(), tags, kubernetesCluster.getUuid(), network.getUuid());
            validateOwnedResource(receipt, network);
            kubernetesClusterDetailsDao.addDetail(kubernetesCluster.getId(), receipt.key(), receipt.encode(), false);
        }
        List<KubernetesOwnedResourceReceipt> receipts = new ArrayList<>();
        for (Map.Entry<String, String> detail : kubernetesClusterDetailsDao.listDetailsKeyPairs(kubernetesCluster.getId()).entrySet()) {
            if (detail.getKey().startsWith(KubernetesOwnedResourceReceipt.PREFIX)
                    || (includeNative && detail.getKey().startsWith("cleanup.native.") && !detail.getKey().startsWith("cleanup.native.acl."))) {
                KubernetesOwnedResourceReceipt receipt = KubernetesOwnedResourceReceipt.decode(detail.getValue());
                if (!receipt.network.equals(network.getUuid())) {
                    throw new CloudRuntimeException("Persisted cleanup network changed");
                }
                validateOwnedResource(receipt, network);
                receipts.add(receipt);
            }
        }
        receipts.sort(Comparator.comparingInt(r -> r.type == ResourceObjectType.PublicIpAddress ? 1 : 0));
        return receipts;
    }

    protected void validateOwnedResource(KubernetesOwnedResourceReceipt receipt, NetworkVO network) {
        IPAddressVO ip = ipAddressDao.findByUuid(receipt.ip);
        if (ip != null && ip.getAllocatedTime() != null && (ip.getAccountId() != kubernetesCluster.getAccountId()
                || !receipt.generation.equals(ip.getAllocationGeneration())
                || (ip.getAssociatedWithNetworkId() != null && ip.getAssociatedWithNetworkId() != network.getId())
                || (ip.getVpcId() != null && !ip.getVpcId().equals(network.getVpcId())))) {
            throw new CloudRuntimeException("Public IP cleanup allocation changed: " + receipt.ip);
        }
        if (receipt.type == ResourceObjectType.LoadBalancer || receipt.type == ResourceObjectType.FirewallRule
                || receipt.type == ResourceObjectType.PortForwardingRule) {
            FirewallRuleVO rule = receipt.type == ResourceObjectType.LoadBalancer
                    ? loadBalancerDao.findById(receipt.id) : receipt.type == ResourceObjectType.PortForwardingRule
                    ? portForwardingRulesDao.findById(receipt.id) : firewallRulesDao.findById(receipt.id);
            if (rule != null && (!receipt.resource.equals(rule.getUuid()) || rule.getAccountId() != kubernetesCluster.getAccountId()
                    || !Long.valueOf(network.getId()).equals(rule.getNetworkId()) || ip == null
                    || !Long.valueOf(ip.getId()).equals(rule.getSourceIpAddressId()))) {
                throw new CloudRuntimeException("Network rule cleanup identity changed: " + receipt.resource);
            }
        } else if (receipt.type == ResourceObjectType.NetworkACL) {
            NetworkACLItemVO rule = networkACLItemDao.findById(receipt.id);
            if (rule != null && (!receipt.resource.equals(rule.getUuid()) || !Long.valueOf(rule.getAclId()).equals(network.getNetworkACLId()))) {
                throw new CloudRuntimeException("ACL cleanup identity changed: " + receipt.resource);
            }
        } else if (ip != null && (!receipt.resource.equals(ip.getUuid()) || receipt.id != ip.getId())) {
            throw new CloudRuntimeException("IP cleanup identity changed: " + receipt.resource);
        }
    }

    protected NetworkVO requireCleanupNetworkAccess() {
        NetworkVO network = networkDao.findById(kubernetesCluster.getNetworkId());
        if (network == null) {
            throw new CloudRuntimeException("Cannot verify the Kubernetes cleanup network");
        }
        accountManager.checkAccess(CallContext.current().getCallingAccount(), null, true, network);
        return network;
    }

    protected void cleanupOwnedNetworkResources(boolean includeNative) throws ResourceUnavailableException, InsufficientAddressCapacityException {
        NetworkVO network = requireCleanupNetworkAccess();
        List<KubernetesOwnedResourceReceipt> receipts = recordOwnedNetworkResources(includeNative);
        for (KubernetesOwnedResourceReceipt receipt : receipts) {
            validateOwnedResource(receipt, network);
            kubernetesClusterDetailsDao.addDetail(kubernetesCluster.getId(), "cleanup.remaining", receipt.resource, true);
            boolean done = true;
            switch (receipt.type) {
                case LoadBalancer:
                    if (loadBalancerDao.findById(receipt.id) != null) {
                        done = lbService.deleteLoadBalancerRule(receipt.id, true);
                    }
                    break;
                case FirewallRule:
                    if (firewallRulesDao.findById(receipt.id) != null) {
                        done = firewallManager.revokeIngressFirewallRule(receipt.id, true);
                    }
                    break;
                case PortForwardingRule:
                    if (portForwardingRulesDao.findById(receipt.id) != null) {
                        done = rulesService.revokePortForwardingRule(receipt.id, true);
                    }
                    break;
                case NetworkACL:
                    if (networkACLItemDao.findById(receipt.id) != null) {
                        done = networkACLService.revokeNetworkACLItem(receipt.id);
                    }
                    break;
                case PublicIpAddress:
                    IPAddressVO ip = ipAddressDao.findById(receipt.id);
                    if (ip != null && ip.getAllocatedTime() != null) {
                        // Preserve shared/manual/source-NAT/static-NAT and their remaining rules.
                        if (ip.isSourceNat() || ip.isOneToOneNat() || ip.isPortable()
                                || !firewallRulesDao.listByIpAndNotRevoked(ip.getId()).isEmpty()) {
                            kubernetesClusterDetailsDao.addDetail(kubernetesCluster.getId(), "cleanup.retained." + receipt.resource, "SharedOrProtectedIp", false);
                        } else {
                            done = networkService.releaseIpAddress(ip.getId(), receipt.generation);
                        }
                    }
                    break;
                default:
                    throw new CloudRuntimeException("Unsupported cleanup resource");
            }
            if (!done) {
                throw new CloudRuntimeException("Cluster-owned resource cleanup failed: " + receipt.resource);
            }
        }
        kubernetesClusterDetailsDao.removeDetail(kubernetesCluster.getId(), "cleanup.remaining");
    }

    protected void cleanupNativeAclResources() throws ResourceUnavailableException {
        NetworkVO network = networkDao.findById(kubernetesCluster.getNetworkId());
        for (Map.Entry<String, String> detail : kubernetesClusterDetailsDao.listDetailsKeyPairs(kubernetesCluster.getId()).entrySet()) {
            if (!detail.getKey().startsWith("cleanup.native.acl.")) {
                continue;
            }
            String[] receipt = detail.getValue().split("\\|", -1);
            if (receipt.length != 3 || !network.getUuid().equals(receipt[2])) {
                throw new CloudRuntimeException("Native ACL cleanup receipt changed");
            }
            NetworkACLItemVO acl = networkACLItemDao.findById(Long.parseLong(receipt[0]));
            if (acl == null) {
                continue;
            }
            if (!KubernetesOwnedResourceReceipt.canonical(receipt[1]).equals(acl.getUuid())
                    || !Long.valueOf(acl.getAclId()).equals(network.getNetworkACLId())) {
                throw new CloudRuntimeException("Native ACL cleanup identity changed");
            }
            if (!networkACLService.revokeNetworkACLItem(acl.getId())) {
                throw new CloudRuntimeException("Native ACL cleanup failed: " + acl.getUuid());
            }
        }
    }

    protected boolean executeServiceCleanup(String action) {
        try {
            File script = retrieveScriptFile("cleanup-owned-services");
            Pair<String, Integer> endpoint = getKubernetesClusterServerIpSshPort(null);
            copyScriptFile(endpoint.first(), endpoint.second(), script, "cleanup-owned-services");
            Pair<Boolean, String> result = SshHelper.sshExecute(endpoint.first(), endpoint.second(), getControlNodeLoginUser(),
                    getManagementServerSshPublicKeyFile(), null, "sudo " + scriptPath + "/cleanup-owned-services --" + action,
                    10000, 10000, 180000);
            return Boolean.TRUE.equals(result.first());
        } catch (Exception error) {
            logMessage(Level.WARN, "Kubernetes Service cleanup unavailable; cluster-owned fallback is required", error);
            return false;
        }
    }

    protected void stopNodesForOfflineOwnedCleanup() throws ConcurrentOperationException {
        for (KubernetesClusterVmMapVO map : clusterVMs) {
            UserVmVO vm = userVmDao.findById(map.getVmId());
            if (vm != null && !vm.isRemoved() && vm.getState() == VirtualMachine.State.Running) {
                UserVm stopped = userVmService.stopVirtualMachine(vm.getId(), false);
                if (stopped == null || stopped.getState() != VirtualMachine.State.Stopped) {
                    throw new CloudRuntimeException("Cannot stop the cluster controller before offline cleanup");
                }
            }
        }
    }

    protected void prepareServiceCleanupBeforeNodeRemoval() {
        kubernetesClusterDetailsDao.addDetail(kubernetesCluster.getId(), "cleanup.status", "InProgress", true);
        kubernetesClusterDetailsDao.addDetail(kubernetesCluster.getId(), "cleanup.phase", "ServiceLoadBalancers", true);
        try {
            requireCleanupNetworkAccess();
            boolean online = executeServiceCleanup("request");
            if (!online && !ownershipCleanupEnabled()) {
                throw new CloudRuntimeException("Kubernetes API/CCM cleanup unavailable without durable ownership; preserve nodes and retry after recovery");
            }
            if (!ownershipCleanupEnabled() && !executeServiceCleanup("wait")) {
                throw new CloudRuntimeException("CCM did not finalize its legacy Services; preserve nodes and retry after recovery");
            }
            if (ownershipCleanupEnabled()) {
                // Persist all receipts before the first mutation, including while API is unavailable.
                recordOwnedNetworkResources(false);
                if (!online) {
                    stopNodesForOfflineOwnedCleanup();
                }
                cleanupOwnedNetworkResources(false);
                if (online && !executeServiceCleanup("finalize")) {
                    throw new CloudRuntimeException("Owned Mold resources were cleaned but Service finalization failed; retry with nodes preserved");
                }
            }
            cleanupNativeAclResources();
            cleanupOwnedNetworkResources(true);
            kubernetesClusterDetailsDao.removeDetail(kubernetesCluster.getId(), "cleanup.status");
            kubernetesClusterDetailsDao.removeDetail(kubernetesCluster.getId(), "cleanup.phase");
        } catch (Exception error) {
            kubernetesClusterDetailsDao.addDetail(kubernetesCluster.getId(), "cleanup.status", "Blocked", true);
            throw new CloudRuntimeException("Kubernetes Service cleanup blocked; nodes and receipts are preserved for retry", error);
        }
    }

    protected void prepareCsiCleanupBeforeNodeRemoval() {
        if (kubernetesCluster.isCsiEnabled() && !deletePVsWithReclaimPolicyDelete()) {
            kubernetesClusterDetailsDao.addDetail(kubernetesCluster.getId(), "cleanup.status", "Blocked", true);
            kubernetesClusterDetailsDao.addDetail(kubernetesCluster.getId(), "cleanup.phase", "MoldCsiDeleteVolumes", true);
            throw new CloudRuntimeException("Mold CSI Delete volume cleanup failed or timed out; cluster nodes are preserved. Retry after API/CSI recovery.");
        }
    }

    protected boolean isUnprovisionedFailure() {
        if (!KubernetesCluster.State.Error.equals(kubernetesCluster.getState())
                && !KubernetesCluster.State.Destroying.equals(kubernetesCluster.getState())) {
            return false;
        }
        KubernetesClusterDetailsVO phase = kubernetesClusterDetailsDao.findDetail(kubernetesCluster.getId(), "lifecycle.provisioning.phase");
        return phase != null && "Preflight".equals(phase.getValue())
                && CollectionUtils.isEmpty(kubernetesClusterVmMapDao.listByClusterId(kubernetesCluster.getId()));
    }

    protected void prepareNodeRemoval() {
        KubernetesClusterDetailsVO prepared = kubernetesClusterDetailsDao.findDetail(kubernetesCluster.getId(), "cleanup.nodes.prepared");
        if (prepared == null || !"v1".equals(prepared.getValue())) {
            if (isUnprovisionedFailure()) {
                kubernetesClusterDetailsDao.addDetail(kubernetesCluster.getId(), "cleanup.nodes.prepared", "v1", false);
                return;
            }
            requireCleanupNetworkAccess();
            recordVerifiedLegacyApiLoadBalancer();
            prepareCsiCleanupBeforeNodeRemoval();
            prepareServiceCleanupBeforeNodeRemoval();
            kubernetesClusterDetailsDao.addDetail(kubernetesCluster.getId(), "cleanup.nodes.prepared", "v1", false);
        }
    }

    public boolean destroy() throws CloudRuntimeException {
        init();
        validateClusterSate();
        this.clusterVMs = kubernetesClusterVmMapDao.listByClusterId(kubernetesCluster.getId());
        final boolean unprovisionedFailure = isUnprovisionedFailure();
        List<VMInstanceVO> vms = this.clusterVMs.stream().map(vmMap -> vmInstanceDao.findById(vmMap.getVmId())).collect(Collectors.toList());
        if (KubernetesClusterManagerImpl.checkIfVmsAssociatedWithBackupOffering(vms)) {
            throw new CloudRuntimeException("Unable to delete Kubernetes cluster, as node(s) are associated to a backup offering");
        }
        boolean cleanupNetwork = true;
        final KubernetesClusterDetailsVO clusterDetails = kubernetesClusterDetailsDao.findDetail(kubernetesCluster.getId(), "networkCleanup");
        if (clusterDetails != null) {
            cleanupNetwork = Boolean.parseBoolean(clusterDetails.getValue());
        }
        if (cleanupNetwork) { // if network has additional VM, cannot proceed with cluster destroy
            NetworkVO network = networkDao.findById(kubernetesCluster.getNetworkId());
            List<KubernetesClusterVmMapVO> externalNodes = clusterVMs.stream().filter(KubernetesClusterVmMapVO::isExternalNode).collect(Collectors.toList());
            if (!externalNodes.isEmpty()) {
                String errMsg = String.format("Failed to delete kubernetes cluster %s as there are %s external node(s) present. Please remove the external node(s) from the cluster (and network) or delete them before deleting the cluster.", kubernetesCluster.getName(), externalNodes.size());
                logger.error(errMsg);
                throw new CloudRuntimeException(errMsg);
            }
            if (network != null) {
                List<VMInstanceVO> networkVMs = vmInstanceDao.listNonRemovedVmsByTypeAndNetwork(network.getId(), VirtualMachine.Type.User);
                if (networkVMs.size() > clusterVMs.size()) {
                    logAndThrow(Level.ERROR, String.format("Network : %s for Kubernetes cluster : %s has instances using it which are not part of the Kubernetes cluster", network.getName(), kubernetesCluster.getName()));
                }
                for (VMInstanceVO vm : networkVMs) {
                    boolean vmFoundInKubernetesCluster = false;
                    for (KubernetesClusterVmMap clusterVM : clusterVMs) {
                        if (vm.getId() == clusterVM.getVmId()) {
                            vmFoundInKubernetesCluster = true;
                            break;
                        }
                    }
                    if (!vmFoundInKubernetesCluster) {
                        logAndThrow(Level.ERROR, String.format("VM : %s which is not a part of Kubernetes cluster : %s is using Kubernetes cluster network : %s", vm.getUuid(), kubernetesCluster.getName(), network.getName()));
                    }
                }
            } else {
                logger.error("Failed to find network for Kubernetes cluster : {}", kubernetesCluster);
            }
        }
        if (logger.isInfoEnabled()) {
            logger.info("Destroying Kubernetes cluster : {}", kubernetesCluster);
        }
        prepareNodeRemoval();
        stateTransitTo(kubernetesCluster.getId(), KubernetesCluster.Event.DestroyRequested);
        boolean vmsDestroyed = destroyClusterVMs();
        if (cleanupNetwork && hasUnclaimedNetworkResources()) {
            cleanupNetwork = false;
            kubernetesClusterDetailsDao.addDetail(kubernetesCluster.getId(), "cleanup.retained.network", "UnclaimedNetworkResources", false);
        }
        // if there are VM's that were not expunged, we can not delete the network
        if (vmsDestroyed) {
            if (cleanupNetwork) {
                validateClusterVMsDestroyed();
                try {
                    destroyKubernetesClusterNetwork();
                } catch (ManagementServerException e) {
                    String msg = String.format("Failed to destroy network of Kubernetes cluster: %s cleanup", kubernetesCluster);
                    logger.warn(msg, e);
                    updateKubernetesClusterEntryForGC();
                    throw new CloudRuntimeException(msg, e);
                }
            } else {
                try {
                    if (!unprovisionedFailure) {
                        checkForRulesToDelete();
                    }
                } catch (ManagementServerException e) {
                    String msg = String.format("Failed to remove network rules of Kubernetes cluster: %s", kubernetesCluster);
                    logger.warn(msg, e);
                    updateKubernetesClusterEntryForGC();
                    throw new CloudRuntimeException(msg, e);
                }
                try {
                    releaseVpcTierPublicIpIfNeeded();
                } catch (InsufficientAddressCapacityException e) {
                    String msg = String.format("Failed to release public IP for VPC tier used by Kubernetes cluster: %s", kubernetesCluster);
                    logger.warn(msg, e);
                    updateKubernetesClusterEntryForGC();
                    throw new CloudRuntimeException(msg, e);
                }
            }
        } else {
            String msg = String.format("Failed to destroy one or more VMs as part of Kubernetes cluster: %s cleanup", kubernetesCluster);
            logger.warn(msg);
            updateKubernetesClusterEntryForGC();
            throw new CloudRuntimeException(msg);
        }
        manager.removeClusterServiceKeys(kubernetesCluster);
        stateTransitTo(kubernetesCluster.getId(), KubernetesCluster.Event.OperationSucceeded);
        annotationDao.removeByEntityType(AnnotationService.EntityType.KUBERNETES_CLUSTER.name(), kubernetesCluster.getUuid());
        kubernetesClusterDetailsDao.removeDetails(kubernetesCluster.getId());
        kubernetesClusterAffinityGroupMapDao.removeByClusterId(kubernetesCluster.getId());
        boolean deleted = kubernetesClusterDao.remove(kubernetesCluster.getId());
        if (!deleted) {
            logMessage(Level.WARN, String.format("Failed to delete Kubernetes cluster: %s", kubernetesCluster), null);
            updateKubernetesClusterEntryForGC();
            return false;
        }
        if (logger.isInfoEnabled()) {
            logger.info("Kubernetes cluster: {} is successfully deleted", kubernetesCluster);
        }
        return true;
    }
}
