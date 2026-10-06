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

import com.google.gson.Gson;

import org.apache.commons.codec.binary.Base64;

import java.nio.charset.StandardCharsets;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import java.util.Collections;
import javax.inject.Inject;
import com.cloud.network.IpAddress;
import com.cloud.network.Network;
import com.cloud.network.dao.LoadBalancerDao;
import com.cloud.network.dao.LoadBalancerVO;
import com.cloud.network.dao.LoadBalancerVMMapDao;
import com.cloud.network.dao.LoadBalancerVMMapVO;
import com.cloud.network.lb.LoadBalancingRulesService;
import com.cloud.vm.Nic;
import com.cloud.tags.dao.ResourceTagDao;
import com.cloud.server.ResourceTag.ResourceObjectType;
import java.util.stream.Collectors;

import com.cloud.kubernetes.cluster.KubernetesClusterVmMapVO;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Level;

import com.cloud.hypervisor.Hypervisor;
import com.cloud.kubernetes.cluster.KubernetesCluster;
import com.cloud.kubernetes.cluster.KubernetesClusterManagerImpl;
import com.cloud.kubernetes.cluster.KubernetesClusterService;
import com.cloud.kubernetes.cluster.KubernetesClusterVO;
import com.cloud.kubernetes.cluster.utils.KubernetesClusterUtil;
import com.cloud.kubernetes.version.KubernetesSupportedVersion;
import com.cloud.kubernetes.version.KubernetesVersionManagerImpl;
import com.cloud.uservm.UserVm;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.ssh.SshHelper;

public class KubernetesClusterUpgradeWorker extends KubernetesClusterActionWorker {

    protected List<UserVm> clusterVMs = new ArrayList<>();
    private KubernetesSupportedVersion upgradeVersion;
    private final String upgradeScriptFilename = "upgrade-kubernetes.sh";
    private File upgradeScriptFile;
    private long upgradeTimeoutTime;
    @Inject protected LoadBalancerDao loadBalancerDao;
    @Inject protected LoadBalancerVMMapDao loadBalancerVMMapDao;
    @Inject protected LoadBalancingRulesService lbService;
    @Inject protected ResourceTagDao resourceTagDao;
    protected LoadBalancerVO upgradeApiLoadBalancer;

    public KubernetesClusterUpgradeWorker(final KubernetesCluster kubernetesCluster,
                                          final KubernetesSupportedVersion upgradeVersion,
                                          final KubernetesClusterManagerImpl clusterManager,
                                          final String[] keys) {
        super(kubernetesCluster, clusterManager);
        this.upgradeVersion = upgradeVersion;
        this.keys = keys;
    }

    protected void retrieveScriptFiles() {
        super.retrieveScriptFiles();
        upgradeScriptFile = retrieveScriptFile(upgradeScriptFilename);
    }

    private Pair<Boolean, String> runInstallScriptOnVM(final UserVm vm, final int index) throws Exception {
        int nodeSshPort = sshPort == 22 ? sshPort : sshPort + index;
        String nodeAddress = (index > 0 && sshPort == 22) ? vm.getPrivateIpAddress() : publicIpAddress;
        SshHelper.scpTo(nodeAddress, nodeSshPort, getControlNodeLoginUser(), sshKeyFile, null,
                "~/", upgradeScriptFile.getAbsolutePath(), "0755");
        String cmdStr = String.format("sudo ./%s %s %s %s %s %s %s",
                upgradeScriptFile.getName(),
                upgradeVersion.getSemanticVersion(),
                index == 0 ? "true" : "false",
                KubernetesVersionManagerImpl.compareSemanticVersions(upgradeVersion.getSemanticVersion(), "1.15.0") < 0 ? "true" : "false",
                Hypervisor.HypervisorType.VMware.equals(vm.getHypervisorType()), Objects.isNull(kubernetesCluster.getCniConfigId()), upgradeApiLoadBalancer != null);
        return SshHelper.sshExecute(nodeAddress, nodeSshPort, getControlNodeLoginUser(), sshKeyFile, null,
                cmdStr,
                10000, 10000, 10 * 60 * 1000);
    }

    protected Set<Long> controlVmIds() {
        return kubernetesClusterVmMapDao.listByClusterId(kubernetesCluster.getId()).stream()
                .filter(KubernetesClusterVmMapVO::isControlNode).map(KubernetesClusterVmMapVO::getVmId).collect(Collectors.toSet());
    }

    protected LoadBalancerVO findHaUpgradeApiLoadBalancer() {
        if (kubernetesCluster.getControlNodeCount() < 3) {
            return null;
        }
        Network network = networkDao.findById(kubernetesCluster.getNetworkId());
        if (network == null) {
            throw new CloudRuntimeException("Cannot verify the HA upgrade network");
        }
        if (manager.isDirectAccess(network)) {
            return null; // An operator-managed endpoint is not changed by Mold.
        }
        IpAddress address = network.getVpcId() == null ? getNetworkSourceNatIp(network) : getVpcTierKubernetesPublicIp(network);
        if (address == null || address.getAccountId() != kubernetesCluster.getAccountId()) {
            throw new CloudRuntimeException("Cannot verify the HA upgrade API address owner");
        }
        List<LoadBalancerVO> rules = loadBalancerDao.listByIpAddress(address.getId()).stream()
                .filter(rule -> rule.getRemoved() == null && rule.getAccountId() == kubernetesCluster.getAccountId()
                        && Long.valueOf(network.getId()).equals(rule.getNetworkId()) && "api-lb".equals(rule.getName())
                        && rule.getSourcePortStart() == CLUSTER_API_PORT && rule.getSourcePortEnd() == CLUSTER_API_PORT
                        && rule.getDefaultPortStart() == CLUSTER_API_PORT && rule.getDefaultPortEnd() == CLUSTER_API_PORT
                        && "tcp".equalsIgnoreCase(rule.getProtocol())
                        && resourceTagDao.listBy(rule.getId(), ResourceObjectType.LoadBalancer).isEmpty())
                .collect(Collectors.toList());
        if (rules.size() != 1) {
            throw new CloudRuntimeException("Cannot uniquely verify the HA upgrade API load balancer");
        }
        LoadBalancerVO rule = rules.get(0);
        Set<Long> controls = controlVmIds();
        List<LoadBalancerVMMapVO> mappings = loadBalancerVMMapDao.listByLoadBalancerId(rule.getId(), false);
        Set<Long> members = mappings.stream().map(LoadBalancerVMMapVO::getInstanceId).collect(Collectors.toSet());
        if (controls.size() != kubernetesCluster.getControlNodeCount() || members.size() < 2 || mappings.size() != members.size() || !controls.containsAll(members)) {
            throw new CloudRuntimeException("HA upgrade API load balancer membership is not cluster-scoped");
        }
        for (LoadBalancerVMMapVO mapping : mappings) {
            Nic nic = networkModel.getNicInNetwork(mapping.getInstanceId(), network.getId());
            if (nic == null || !Objects.equals(nic.getIPv4Address(), mapping.getInstanceIp())) {
                throw new CloudRuntimeException("HA upgrade API backend address does not match the cluster control NIC");
            }
        }
        return rule;
    }

    protected void setUpgradeApiMember(UserVm vm, boolean include) {
        if (upgradeApiLoadBalancer == null || !controlVmIds().contains(vm.getId())) {
            return;
        }
        Nic nic = networkModel.getNicInNetwork(vm.getId(), kubernetesCluster.getNetworkId());
        if (nic == null || StringUtils.isBlank(nic.getIPv4Address())) {
            throw new CloudRuntimeException("Cannot verify the HA upgrade control node address");
        }
        List<LoadBalancerVMMapVO> members = loadBalancerVMMapDao.listByLoadBalancerId(upgradeApiLoadBalancer.getId(), false).stream()
                .filter(member -> member.getInstanceId() == vm.getId()).collect(Collectors.toList());
        if (members.size() > 1 || members.size() == 1 && !Objects.equals(members.get(0).getInstanceIp(), nic.getIPv4Address())) {
            throw new CloudRuntimeException("HA API maintenance member address changed during upgrade");
        }
        if (include == !members.isEmpty()) {
            return; // Reconciliation does not reassign an already registered backend.
        }
        Map<Long, List<String>> addresses = new HashMap<>();
        addresses.put(vm.getId(), Collections.singletonList(nic.getIPv4Address()));
        boolean changed = include ? lbService.assignToLoadBalancer(upgradeApiLoadBalancer.getId(), null, addresses, null, false)
                : lbService.removeFromLoadBalancer(upgradeApiLoadBalancer.getId(), null, addresses, false);
        if (!changed) {
            logTransitStateDetachIsoAndThrow(Level.ERROR, "Failed to update HA API load balancer maintenance membership",
                    kubernetesCluster, clusterVMs, KubernetesCluster.Event.OperationFailed, null);
        }
    }

    protected String getControlPlaneGateCommand(String host) throws Exception {
        if (!host.matches("[a-z0-9.-]+")) {
            throw new CloudRuntimeException("Invalid HA control node hostname");
        }
        String encoded = Base64.encodeBase64String(readResourceFile("/script/upgrade-control-plane-gate.py").getBytes(StandardCharsets.UTF_8));
        return "sudo python3 -c 'import base64;exec(base64.b64decode(\"" + encoded + "\"))' --host " + host
                + " --control-count " + kubernetesCluster.getControlNodeCount() + " --timeout 120";
    }

    protected Pair<Boolean, String> executeControlPlaneGateCommand(UserVm vm, int index, String command) throws Exception {
        int port = sshPort == 22 ? sshPort : sshPort + index;
        String address = index > 0 && sshPort == 22 ? vm.getPrivateIpAddress() : publicIpAddress;
        return SshHelper.sshExecute(address, port, getControlNodeLoginUser(), sshKeyFile, null, command, 10000, 10000, 180000);
    }

    protected void ensureControlPlaneReady(UserVm vm, int index, boolean preflight) {
        if (kubernetesCluster.getControlNodeCount() < 3 || !controlVmIds().contains(vm.getId())) {
            return;
        }
        try {
            Pair<Boolean, String> result = executeControlPlaneGateCommand(vm, index, getControlPlaneGateCommand(vm.getHostName().toLowerCase()));
            if (Boolean.TRUE.equals(result.first()) && result.second().contains("UPGRADE_CONTROL_PLANE_AND_ETCD_READY")) {
                return;
            }
        } catch (Exception e) {
            logger.warn("HA upgrade control-plane readiness failed for cluster {}", kubernetesCluster.getUuid());
        }
        if (preflight) {
            logAndThrow(Level.ERROR, "HA upgrade control-plane or etcd preflight failed");
        }
        logTransitStateDetachIsoAndThrow(Level.ERROR, "HA upgrade paused; control-plane or etcd readiness did not recover",
                kubernetesCluster, clusterVMs, KubernetesCluster.Event.OperationFailed, null);
    }

    private void upgradeKubernetesClusterNodes() {
        for (int i = 0; i < clusterVMs.size(); ++i) {
            UserVm vm = clusterVMs.get(i);
            String hostName = vm.getHostName();
            if (StringUtils.isNotEmpty(hostName)) {
                hostName = hostName.toLowerCase();
            }
            Pair<Boolean, String> result;
            if (logger.isInfoEnabled()) {
                logger.info("Upgrading node on VM {} in Kubernetes cluster {} with Kubernetes version {}", vm, kubernetesCluster, upgradeVersion);
            }
            String errorMessage = String.format("Failed to upgrade Kubernetes cluster : %s, unable to drain Kubernetes node on VM : %s", kubernetesCluster.getName(), vm.getDisplayName());
            for (int retry = KubernetesClusterService.KubernetesClusterUpgradeRetries.value(); retry >= 0; retry--) {
                try {
                    result = SshHelper.sshExecute(publicIpAddress, sshPort, getControlNodeLoginUser(), sshKeyFile, null,
                            String.format("sudo /opt/bin/kubectl drain %s --ignore-daemonsets --delete-emptydir-data", hostName),
                            10000, 10000, 60000);
                    if (result.first()) {
                        break;
                    }
                    if (retry > 0) {
                        logger.error(String.format("%s, retries left: %s", errorMessage, retry));
                    } else {
                        logTransitStateDetachIsoAndThrow(Level.ERROR, errorMessage, kubernetesCluster, clusterVMs, KubernetesCluster.Event.OperationFailed, null);
                    }
                } catch (Exception e) {
                    if (retry > 0) {
                        logger.error(String.format("%s due to %s, retries left: %s", errorMessage, e, retry));
                    } else {
                        logTransitStateDetachIsoAndThrow(Level.ERROR, errorMessage, kubernetesCluster, clusterVMs, KubernetesCluster.Event.OperationFailed, e);
                    }
                }
            }
            if (System.currentTimeMillis() > upgradeTimeoutTime) {
                logTransitStateDetachIsoAndThrow(Level.ERROR, String.format("Failed to upgrade Kubernetes cluster : %s, upgrade action timed out", kubernetesCluster.getName()), kubernetesCluster, clusterVMs, KubernetesCluster.Event.OperationFailed, null);
            }
            setUpgradeApiMember(vm, false);
            errorMessage = String.format("Failed to upgrade Kubernetes cluster : %s, unable to upgrade Kubernetes node on VM : %s", kubernetesCluster.getName(), vm.getDisplayName());
            for (int retry = KubernetesClusterService.KubernetesClusterUpgradeRetries.value(); retry >= 0; retry--) {
                try {
                    deployProvider();
                    result = runInstallScriptOnVM(vm, i);
                    if (result.first()) {
                        break;
                    }
                    if (retry > 0) {
                        logger.error(String.format("%s, retries left: %s", errorMessage, retry));
                    } else {
                        logTransitStateDetachIsoAndThrow(Level.ERROR, errorMessage, kubernetesCluster, clusterVMs, KubernetesCluster.Event.OperationFailed, null);
                    }
                } catch (Exception e) {
                    if (retry > 0) {
                        logger.error(String.format("%s due to %s, retries left: %s", errorMessage, e, retry));
                    } else {
                        logTransitStateDetachIsoAndThrow(Level.ERROR, errorMessage, kubernetesCluster, clusterVMs, KubernetesCluster.Event.OperationFailed, e);
                    }
                }
            }
            if (System.currentTimeMillis() > upgradeTimeoutTime) {
                logTransitStateDetachIsoAndThrow(Level.ERROR, String.format("Failed to upgrade Kubernetes cluster : %s, upgrade action timed out", kubernetesCluster.getName()), kubernetesCluster, clusterVMs, KubernetesCluster.Event.OperationFailed, null);
            }
            if (!KubernetesClusterUtil.uncordonKubernetesClusterNode(kubernetesCluster, publicIpAddress, sshPort, getControlNodeLoginUser(), getManagementServerSshPublicKeyFile(), vm, upgradeTimeoutTime, 15000)) {
                logTransitStateDetachIsoAndThrow(Level.ERROR, String.format("Failed to upgrade Kubernetes cluster : %s, unable to uncordon Kubernetes node on VM : %s", kubernetesCluster.getName(), vm.getDisplayName()), kubernetesCluster, clusterVMs, KubernetesCluster.Event.OperationFailed, null);
            }
            if (!KubernetesClusterUtil.isKubernetesClusterNodeReady(kubernetesCluster, publicIpAddress, sshPort, getControlNodeLoginUser(), getManagementServerSshPublicKeyFile(), hostName, upgradeTimeoutTime, 15000)) {
                logTransitStateDetachIsoAndThrow(Level.ERROR, String.format("Failed to upgrade Kubernetes cluster : %s, unable to get Kubernetes node on VM : %s in ready state", kubernetesCluster.getName(), vm.getDisplayName()), kubernetesCluster, clusterVMs, KubernetesCluster.Event.OperationFailed, null);
            }
            if (!KubernetesClusterUtil.clusterNodeVersionMatches(upgradeVersion.getSemanticVersion(), publicIpAddress, sshPort, getControlNodeLoginUser(), getManagementServerSshPublicKeyFile(), hostName, upgradeTimeoutTime, 15000, vm.getId(), kubernetesClusterVmMapDao)) {
                logTransitStateDetachIsoAndThrow(Level.ERROR, String.format("Failed to upgrade Kubernetes cluster : %s, unable to get Kubernetes node on VM : %s upgraded to version %s", kubernetesCluster.getName(), vm.getDisplayName(), upgradeVersion.getSemanticVersion()), kubernetesCluster, clusterVMs, KubernetesCluster.Event.OperationFailed, null);
            }
            ensureControlPlaneReady(vm, i, false);
            setUpgradeApiMember(vm, true);
            ensureUpgradeWorkloadsReady(false);
            if (logger.isInfoEnabled()) {
                logger.info("Successfully upgraded node on VM {} in Kubernetes cluster {} with Kubernetes version {}", vm, kubernetesCluster, upgradeVersion);
            }
        }
    }

    protected String getControllerUpgradeCommand() {
        String command = "sudo /opt/bin/kubectl -n kube-system "
                + "rollout status deployment/cloud-controller-manager --timeout=120s";
        if (kubernetesCluster.getAutoscalingEnabled()) {
            Long minSize = kubernetesCluster.getMinSize();
            Long maxSize = kubernetesCluster.getMaxSize();
            String clusterUuid = kubernetesCluster.getUuid();
            if (StringUtils.isEmpty(clusterUuid) || !clusterUuid.matches("[A-Za-z0-9-]+")
                    || minSize == null || maxSize == null || minSize < 1 || maxSize < minSize) {
                throw new CloudRuntimeException("Invalid autoscaling configuration during Kubernetes upgrade");
            }
            command += String.format(" && sudo /opt/bin/autoscale-kube-cluster -i %s -e -M %d -m %d",
                    clusterUuid, maxSize, minSize);
            command += " && sudo /opt/bin/kubectl -n kube-system "
                    + "rollout status deployment/cluster-autoscaler --timeout=120s";
        }
        return command;
    }

    protected Pair<Boolean, String> executeControllerUpgradeCommand(String command) throws Exception {
        return SshHelper.sshExecute(publicIpAddress, sshPort, getControlNodeLoginUser(), sshKeyFile, null,
                command, 10000, 10000, 5 * 60 * 1000);
    }

    protected void upgradeKubernetesControllers() {
        try {
            Pair<Boolean, String> result = executeControllerUpgradeCommand(getControllerUpgradeCommand());
            if (Boolean.TRUE.equals(result.first())) {
                return;
            }
        } catch (Exception e) {
            // The remote output may contain configuration data; do not log it.
            logger.warn("Kubernetes controller upgrade did not complete for cluster {}", kubernetesCluster.getUuid());
        }
        logTransitStateDetachIsoAndThrow(Level.ERROR,
                String.format("Failed to upgrade controllers for Kubernetes cluster: %s", kubernetesCluster.getName()),
                kubernetesCluster, clusterVMs, KubernetesCluster.Event.OperationFailed, null);
    }

    protected String getWorkloadGateCommand(boolean capture) throws Exception {
        String uuid = kubernetesCluster.getUuid();
        if (StringUtils.isBlank(uuid) || !uuid.matches("[a-fA-F0-9-]{36}")) {
            throw new CloudRuntimeException("Invalid cluster UUID for upgrade readiness receipt");
        }
        String script = readResourceFile("/script/upgrade-workload-gate.py");
        String encoded = Base64.encodeBase64String(script.getBytes(StandardCharsets.UTF_8));
        String command = "sudo python3 -c 'import base64;exec(base64.b64decode(\"" + encoded + "\"))'"
                + " --baseline /var/lib/mold/kubernetes/upgrades/" + uuid + "/workload-baseline.json --timeout 120";
        if (capture) {
            List<String> names = clusterVMs.stream().map(UserVm::getHostName).collect(Collectors.toList());
            if (names.stream().anyMatch(StringUtils::isBlank)) {
                throw new CloudRuntimeException("Missing upgrade node hostname");
            }
            command += " --capture --nodes-base64 " + Base64.encodeBase64String(new Gson().toJson(names).getBytes(StandardCharsets.UTF_8));
        }
        return command;
    }

    protected Pair<Boolean, String> executeWorkloadGateCommand(String command) throws Exception {
        return SshHelper.sshExecute(publicIpAddress, sshPort, getControlNodeLoginUser(), sshKeyFile, null,
                command, 10000, 10000, 180000);
    }

    protected void ensureUpgradeWorkloadsReady(boolean capture) {
        try {
            Pair<Boolean, String> result = executeWorkloadGateCommand(getWorkloadGateCommand(capture));
            String marker = capture ? "UPGRADE_WORKLOAD_BASELINE_READY" : "UPGRADE_WORKLOADS_AND_ENDPOINTS_READY";
            if (Boolean.TRUE.equals(result.first()) && result.second().contains(marker)) {
                return;
            }
        } catch (Exception e) {
            logger.warn("Kubernetes upgrade workload readiness gate failed for cluster {}", kubernetesCluster.getUuid());
        }
        if (capture) {
            logAndThrow(Level.ERROR, "Kubernetes upgrade preflight failed; check PDB and workload readiness");
        }
        logTransitStateDetachIsoAndThrow(Level.ERROR,
                capture ? "Kubernetes upgrade preflight failed; check PDB and workload readiness"
                        : "Kubernetes upgrade paused; workloads or Service endpoints did not recover before the next node",
                kubernetesCluster, clusterVMs, KubernetesCluster.Event.OperationFailed, null);
    }

    public boolean upgradeCluster() throws CloudRuntimeException {
        init();
        if (logger.isInfoEnabled()) {
            logger.info("Upgrading Kubernetes cluster: {}", kubernetesCluster);
        }
        upgradeTimeoutTime = System.currentTimeMillis() + KubernetesClusterService.KubernetesClusterUpgradeTimeout.value() * 1000;
        Pair<String, Integer> publicIpSshPort = getKubernetesClusterServerIpSshPort(null);
        publicIpAddress = publicIpSshPort.first();
        sshPort = publicIpSshPort.second();
        if (StringUtils.isEmpty(publicIpAddress)) {
            logAndThrow(Level.ERROR, String.format("Upgrade failed for Kubernetes cluster: %s, unable to retrieve associated public IP", kubernetesCluster));
        }
        clusterVMs = getKubernetesClusterVMs();
        if (CollectionUtils.isEmpty(clusterVMs)) {
            logAndThrow(Level.ERROR, String.format("Upgrade failed for Kubernetes cluster: %s, unable to retrieve VMs for cluster", kubernetesCluster));
        }
        filterOutManualUpgradeNodesFromClusterUpgrade();
        retrieveScriptFiles();
        if (!rebalanceHaDns()) {
            logAndThrow(Level.ERROR, "HA DNS readiness preflight failed before Kubernetes upgrade");
        }
        upgradeApiLoadBalancer = findHaUpgradeApiLoadBalancer();
        ensureControlPlaneReady(clusterVMs.get(0), 0, true);
        if (upgradeApiLoadBalancer != null) {
            for (UserVm vm : clusterVMs) {
                setUpgradeApiMember(vm, true); // Restore a verified healthy member after a paused prior attempt.
            }
        }
        ensureUpgradeWorkloadsReady(true);
        stateTransitTo(kubernetesCluster.getId(), KubernetesCluster.Event.UpgradeRequested);
        attachIsoKubernetesVMs(clusterVMs, upgradeVersion);
        upgradeKubernetesClusterNodes();
        upgradeKubernetesControllers();
        if (!rebalanceHaDns()) {
            logTransitStateDetachIsoAndThrow(Level.ERROR, "HA DNS did not recover after Kubernetes upgrade",
                    kubernetesCluster, clusterVMs, KubernetesCluster.Event.OperationFailed, null);
        }
        ensureUpgradeWorkloadsReady(false);
        detachIsoKubernetesVMs(clusterVMs);
        KubernetesClusterVO kubernetesClusterVO = kubernetesClusterDao.findById(kubernetesCluster.getId());
        kubernetesClusterVO.setKubernetesVersionId(upgradeVersion.getId());
        boolean updated = kubernetesClusterDao.update(kubernetesCluster.getId(), kubernetesClusterVO);
        if (!updated) {
            stateTransitTo(kubernetesCluster.getId(), KubernetesCluster.Event.OperationFailed);
        } else {
            stateTransitTo(kubernetesCluster.getId(), KubernetesCluster.Event.OperationSucceeded);
        }
        return updated;
    }

    protected void filterOutManualUpgradeNodesFromClusterUpgrade() {
        if (CollectionUtils.isEmpty(clusterVMs)) {
            return;
        }
        clusterVMs = clusterVMs.stream().filter(x -> {
            KubernetesClusterVmMapVO mapVO = kubernetesClusterVmMapDao.getClusterMapFromVmId(x.getId());
            return mapVO != null && !mapVO.isManualUpgrade();
        }).collect(Collectors.toList());
    }
}
