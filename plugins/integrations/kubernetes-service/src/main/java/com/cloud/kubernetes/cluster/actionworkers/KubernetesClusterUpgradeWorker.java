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
        String cmdStr = String.format("sudo ./%s %s %s %s %s %s",
                upgradeScriptFile.getName(),
                upgradeVersion.getSemanticVersion(),
                index == 0 ? "true" : "false",
                KubernetesVersionManagerImpl.compareSemanticVersions(upgradeVersion.getSemanticVersion(), "1.15.0") < 0 ? "true" : "false",
                Hypervisor.HypervisorType.VMware.equals(vm.getHypervisorType()), Objects.isNull(kubernetesCluster.getCniConfigId()));
        return SshHelper.sshExecute(nodeAddress, nodeSshPort, getControlNodeLoginUser(), sshKeyFile, null,
                cmdStr,
                10000, 10000, 10 * 60 * 1000);
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
        stateTransitTo(kubernetesCluster.getId(), KubernetesCluster.Event.UpgradeRequested);
        if (!rebalanceHaDns()) {
            logTransitStateDetachIsoAndThrow(Level.ERROR, "HA DNS readiness preflight failed before Kubernetes upgrade",
                    kubernetesCluster, clusterVMs, KubernetesCluster.Event.OperationFailed, null);
        }
        ensureUpgradeWorkloadsReady(true);
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
