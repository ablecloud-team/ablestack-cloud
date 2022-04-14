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

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Properties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.config.ApiServiceConfiguration;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.collections.CollectionUtils;
import org.apache.cloudstack.context.CallContext;
import org.apache.log4j.Level;

import com.cloud.dc.DataCenter;
import com.cloud.deploy.DeployDestination;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.ManagementServerException;
import com.cloud.exception.NetworkRuleConflictException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.network.IpAddress;
import com.cloud.network.Network.IpAddresses;
import com.cloud.network.Network;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.offering.ServiceOffering;
import com.cloud.user.Account;
import com.cloud.user.UserAccount;
import com.cloud.uservm.UserVm;
import com.cloud.utils.StringUtils;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.PropertiesUtil;
import com.cloud.utils.server.ServerProperties;
import com.cloud.desktop.cluster.DesktopCluster;
import com.cloud.desktop.cluster.DesktopClusterVmMapVO;
import com.cloud.desktop.cluster.DesktopClusterManagerImpl;
import com.cloud.desktop.version.DesktopControllerVersion;
import com.cloud.vm.ReservationContext;
import com.cloud.vm.ReservationContextImpl;
import com.cloud.vm.VirtualMachine;
import com.cloud.api.query.vo.UserAccountJoinVO;

public class DesktopClusterStartWorker extends DesktopClusterResourceModifierActionWorker {

    private DesktopControllerVersion desktopClusterVersion;
    private static final long GiB_TO_BYTES = 1024 * 1024 * 1024;

    public DesktopClusterStartWorker(final DesktopCluster desktopCluster, final DesktopClusterManagerImpl clusterManager) {
        super(desktopCluster, clusterManager);
    }

    public DesktopControllerVersion getDesktopClusterVersion() {
        if (desktopClusterVersion == null) {
            desktopClusterVersion = desktopControllerVersionDao.findById(desktopCluster.getDesktopVersionId());
        }
        return desktopClusterVersion;
    }

    private String getDesktopClusterDcConfig(final String instance) throws IOException {
        String desktopClusterDcConfig = readResourceFile("/conf/dc");
        final String instanceName = "{{ instance_name }}";
        final String sambaIp = "{{ samba_ip }}";
        final String domainName = "{{ domain_name }}";
        desktopClusterDcConfig = desktopClusterDcConfig.replace(instanceName, instance);
        desktopClusterDcConfig = desktopClusterDcConfig.replace(sambaIp, desktopCluster.getWorksIp());
        desktopClusterDcConfig = desktopClusterDcConfig.replace(domainName, desktopCluster.getAdDomainName());
        return desktopClusterDcConfig;
    }

    private String getDesktopClusterWorksConfig(final DataCenter zone) throws IOException {
        String[] keys = getServiceUserKeys(owner);
        String[] info = getServerProperties();
        final String managementIp = ApiServiceConfiguration.ManagementServerAddresses.value();
        String desktopClusterWorksConfig = readResourceFile("/conf/works");
        final String clusterName = "{{ cluster_name }}";
        final String domainName = "{{ domain_name }}";
        final String worksIp = "{{ works_ip }}";
        final String dcIp = "{{ dc_ip }}";
        final String zoneUuid = "{{ zone_id }}";
        final String networkId = "{{ network_id }}";
        final String accountName = "{{ account_name }}";
        final String domainUuid = "{{ domain_uuid }}";
        final String apiKey = "{{ api_key }}";
        final String secretKey = "{{ secret_key }}";
        final String moldIp = "{{ mold_ip }}";
        final String moldPort = "{{ mold_port }}";
        final String moldProtocol = "{{ mold_protocol }}";
        List<UserAccountJoinVO> domain = userAccountJoinDao.searchByAccountId(owner.getId());
        desktopClusterWorksConfig = desktopClusterWorksConfig.replace(clusterName, desktopCluster.getName());
        desktopClusterWorksConfig = desktopClusterWorksConfig.replace(domainName, desktopCluster.getAdDomainName());
        desktopClusterWorksConfig = desktopClusterWorksConfig.replace(worksIp, desktopCluster.getWorksIp());
        desktopClusterWorksConfig = desktopClusterWorksConfig.replace(dcIp, desktopCluster.getDcIp());
        desktopClusterWorksConfig = desktopClusterWorksConfig.replace(zoneUuid, zone.getUuid());
        desktopClusterWorksConfig = desktopClusterWorksConfig.replace(networkId, Long.toString(desktopCluster.getNetworkId()));
        desktopClusterWorksConfig = desktopClusterWorksConfig.replace(accountName, owner.getAccountName());
        desktopClusterWorksConfig = desktopClusterWorksConfig.replace(domainUuid, domain.get(0).getDomainUuid());
        desktopClusterWorksConfig = desktopClusterWorksConfig.replace(apiKey, keys[0]);
        desktopClusterWorksConfig = desktopClusterWorksConfig.replace(secretKey, keys[1]);
        desktopClusterWorksConfig = desktopClusterWorksConfig.replace(moldIp, managementIp);
        desktopClusterWorksConfig = desktopClusterWorksConfig.replace(moldPort, info[0]);
        desktopClusterWorksConfig = desktopClusterWorksConfig.replace(moldProtocol, info[1]);
        String base64UserData = Base64.encodeBase64String(desktopClusterWorksConfig.getBytes(StringUtils.getPreferredCharset()));
        String desktopClusterWorksEncodeConfig = readResourceFile("/conf/works.yml");
        final String worksEncode = "{{ works_encode }}";
        desktopClusterWorksEncodeConfig = desktopClusterWorksEncodeConfig.replace(worksEncode, base64UserData);
        return desktopClusterWorksEncodeConfig;
    }

    private UserVm provisionDesktopClusterDcControlVm(final Network network) throws ManagementServerException,
            ResourceUnavailableException, InsufficientCapacityException {
        UserVm dcControlVm = null;
        final String type = "dcvm";
        dcControlVm = createDesktopClusterDcControlVm(network);
        addDesktopClusterVm(desktopCluster.getId(), dcControlVm.getId(), type);
        startDesktopVM(dcControlVm);
        dcControlVm = userVmDao.findById(dcControlVm.getId());
        if (dcControlVm == null) {
            throw new ManagementServerException(String.format("Failed to provision DC Control VM for desktop cluster : %s" , desktopCluster.getName()));
        }
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info(String.format("Provisioned DC Control VM : %s in to the desktop cluster : %s", dcControlVm.getDisplayName(), desktopCluster.getName()));
        }
        return dcControlVm;
    }

    private UserVm createDesktopClusterDcControlVm(final Network network) throws ManagementServerException,
            ResourceUnavailableException, InsufficientCapacityException {
        UserVm dcControlVm = null;
        LinkedHashMap<Long, IpAddresses> ipToNetworkMap = null;
        DataCenter zone = dataCenterDao.findById(desktopCluster.getZoneId());
        ServiceOffering serviceOffering = serviceOfferingDao.findById(desktopCluster.getServiceOfferingId());
        List<Long> networkIds = new ArrayList<Long>();
        networkIds.add(desktopCluster.getNetworkId());
        String dcIp = desktopCluster.getDcIp();
        String reName = desktopCluster.getName();
        String hostName = reName + "-dc";
        Map<String, String> customParameterMap = new HashMap<String, String>();
        DiskOfferingVO diskOffering = diskOfferingDao.findById(serviceOffering.getId());
        long rootDiskSizeInBytes = diskOffering.getDiskSize();
        if (rootDiskSizeInBytes > 0) {
            long rootDiskSizeInGiB = rootDiskSizeInBytes / GiB_TO_BYTES;
            customParameterMap.put("rootdisksize", String.valueOf(rootDiskSizeInGiB));
        }
        String desktopClusterDcConfig = null;
        try {
            desktopClusterDcConfig = getDesktopClusterDcConfig(hostName);
        } catch (IOException e) {
            logAndThrow(Level.ERROR, "Failed to read Desktop Cluster Userdata configuration file", e);
        }
        String base64UserData = Base64.encodeBase64String(desktopClusterDcConfig.getBytes(StringUtils.getPreferredCharset()));
        List<String> keypairs = new ArrayList<String>(); // 키페어 파라메타 임시 생성
        if (dcIp == null || network.getGuestType() == Network.GuestType.L2) {
            Network.IpAddresses addrs = new Network.IpAddresses(null, null, null);
            dcControlVm = userVmService.createAdvancedVirtualMachine(zone, serviceOffering, dcTemplate, networkIds, owner,
                hostName, hostName, null, null, null,
                dcTemplate.getHypervisorType(), BaseCmd.HTTPMethod.POST, base64UserData, keypairs,
                null, addrs, null, null, null, customParameterMap, null, null, null, null, true, null, null);
        } else {
            ipToNetworkMap = new LinkedHashMap<Long, IpAddresses>();
            Network.IpAddresses addrs = new Network.IpAddresses(null, null, null);
            Network.IpAddresses dcAddrs = new Network.IpAddresses(dcIp, null, null);
            ipToNetworkMap.put(desktopCluster.getNetworkId(), dcAddrs);
            dcControlVm = userVmService.createAdvancedVirtualMachine(zone, serviceOffering, dcTemplate, networkIds, owner,
                hostName, hostName, null, null, null,
                dcTemplate.getHypervisorType(), BaseCmd.HTTPMethod.POST, base64UserData, keypairs,
                ipToNetworkMap, addrs, null, null, null, customParameterMap, null, null, null, null, true, null, null);
        }
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info(String.format("Created Control VM ID: %s, %s in the desktop cluster : %s", dcControlVm.getUuid(), hostName, desktopCluster.getName()));
        }
        return dcControlVm;
    }

    private UserVm provisionDesktopClusterWorksControlVm(final Network network) throws
            InsufficientCapacityException, ManagementServerException, ResourceUnavailableException {
        UserVm worksControlVm = null;
        final String type = "worksvm";
        worksControlVm = createDesktopClusterWorksControlVm(network);
        addDesktopClusterVm(desktopCluster.getId(), worksControlVm.getId(), type);
        startDesktopVM(worksControlVm);
        worksControlVm = userVmDao.findById(worksControlVm.getId());
        if (worksControlVm == null) {
            throw new ManagementServerException(String.format("Failed to provision Works Control VM for desktop cluster : %s" , desktopCluster.getName()));
        }
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info(String.format("Provisioned Works Control VM : %s in to the desktop cluster : %s", worksControlVm.getDisplayName(), desktopCluster.getName()));
        }
        return worksControlVm;
    }


    private UserVm createDesktopClusterWorksControlVm(final Network network) throws ManagementServerException,
            ResourceUnavailableException, InsufficientCapacityException {
        UserVm worksControlVm = null;
        LinkedHashMap<Long, IpAddresses> ipToNetworkMap = null;
        DataCenter zone = dataCenterDao.findById(desktopCluster.getZoneId());
        ServiceOffering serviceOffering = serviceOfferingDao.findById(desktopCluster.getServiceOfferingId());
        List<Long> networkIds = new ArrayList<Long>();
        networkIds.add(desktopCluster.getNetworkId());
        String worksIp = desktopCluster.getWorksIp();
        String reName = desktopCluster.getName();
        String hostName = reName + "-works";
        Map<String, String> customParameterMap = new HashMap<String, String>();
        DiskOfferingVO diskOffering = diskOfferingDao.findById(serviceOffering.getId());
        long rootDiskSizeInBytes = diskOffering.getDiskSize();
        if (rootDiskSizeInBytes > 0) {
            long rootDiskSizeInGiB = rootDiskSizeInBytes / GiB_TO_BYTES;
            customParameterMap.put("rootdisksize", String.valueOf(rootDiskSizeInGiB));
        }
        String desktopClusterWorksConfig = null;
        try {
            desktopClusterWorksConfig = getDesktopClusterWorksConfig(zone);
        } catch (IOException e) {
            logAndThrow(Level.ERROR, "Failed to read Desktop Cluster Userdata configuration file", e);
        }
        String base64UserData = Base64.encodeBase64String(desktopClusterWorksConfig.getBytes(StringUtils.getPreferredCharset()));
        List<String> keypairs = new ArrayList<String>(); // 키페어 파라메타 임시 생성
        if (worksIp == null || network.getGuestType() == Network.GuestType.L2) {
            Network.IpAddresses addrs = new Network.IpAddresses(null, null, null);
            worksControlVm = userVmService.createAdvancedVirtualMachine(zone, serviceOffering, worksTemplate, networkIds, owner,
                hostName, hostName, null, null, null,
                worksTemplate.getHypervisorType(), BaseCmd.HTTPMethod.POST, base64UserData, keypairs,
                null, addrs, null, null, null, customParameterMap, null, null, null, null, true, null, null);
        } else {
            ipToNetworkMap = new LinkedHashMap<Long, IpAddresses>();
            Network.IpAddresses addrs = new Network.IpAddresses(null, null, null);
            Network.IpAddresses worksAddrs = new Network.IpAddresses(worksIp, null, null);
            ipToNetworkMap.put(desktopCluster.getNetworkId(), worksAddrs);
            worksControlVm = userVmService.createAdvancedVirtualMachine(zone, serviceOffering, worksTemplate, networkIds, owner,
                hostName, hostName, null, null, null,
                worksTemplate.getHypervisorType(), BaseCmd.HTTPMethod.POST, base64UserData, keypairs,
                ipToNetworkMap, addrs, null, null, null, customParameterMap, null, null, null, null, true, null, null);
        }
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info(String.format("Created Control VM ID : %s, %s in the desktop cluster : %s", worksControlVm.getUuid(), hostName, desktopCluster.getName()));
        }
        return worksControlVm;
    }

    private Network startDesktopClusterNetwork(final DeployDestination destination) throws ManagementServerException {
        final ReservationContext context = new ReservationContextImpl(null, null, null, owner);
        Network network = networkDao.findById(desktopCluster.getNetworkId());
        if (network == null) {
            String msg  = String.format("Network for desktop cluster : %s not found", desktopCluster.getName());
            LOGGER.warn(msg);
            stateTransitTo(desktopCluster.getId(), DesktopCluster.Event.CreateFailed);
            throw new ManagementServerException(msg);
        }
        try {
            networkMgr.startNetwork(network.getId(), destination, context);
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info(String.format("Network : %s is started for the desktop cluster : %s", network.getName(), desktopCluster.getName()));
            }
        } catch (ConcurrentOperationException | ResourceUnavailableException |InsufficientCapacityException e) {
            String msg = String.format("Failed to start desktop cluster : %s as unable to start associated network : %s" , desktopCluster.getName(), network.getName());
            LOGGER.error(msg, e);
            stateTransitTo(desktopCluster.getId(), DesktopCluster.Event.CreateFailed);
            throw new ManagementServerException(msg, e);
        }
        return network;
    }

    private void startDesktopClusterVMs() {
        List <UserVm> clusterVms = getControlVMs();
        for (final UserVm vm : clusterVms) {
            if (vm == null) {
                logTransitStateAndThrow(Level.ERROR, String.format("Failed to start Control VMs in desktop cluster : %s", desktopCluster.getName()), desktopCluster.getId(), DesktopCluster.Event.OperationFailed);
            }
            try {
                startDesktopVM(vm);
            } catch (ManagementServerException ex) {
                LOGGER.warn(String.format("Failed to start VM : %s in desktop cluster : %s due to ", vm.getDisplayName(), desktopCluster.getName()) + ex);
                // dont bail out here. proceed further to stop the reset of the VM's
            }
        }
        for (final UserVm userVm : clusterVms) {
            UserVm vm = userVmDao.findById(userVm.getId());
            if (vm == null || !vm.getState().equals(VirtualMachine.State.Running)) {
                logTransitStateAndThrow(Level.ERROR, String.format("Failed to start Control VMs in desktop cluster : %s", desktopCluster.getName()), desktopCluster.getId(), DesktopCluster.Event.OperationFailed);
            }
        }
    }

    private String[] getServiceUserKeys(Account owner) {
        if (owner == null) {
            owner = CallContext.current().getCallingAccount();
        }
        String username = owner.getAccountName();
        UserAccount user = accountService.getActiveUserAccount(username, owner.getDomainId());
        String[] keys = null;
        String apiKey = user.getApiKey();
        String secretKey = user.getSecretKey();
        if ((apiKey == null || apiKey.length() == 0) || (secretKey == null || secretKey.length() == 0)) {
            keys = accountService.createApiKeyAndSecretKey(user.getId());
        } else {
            keys = new String[]{apiKey, secretKey};
        }
        return keys;
    }

    private String[] getServerProperties() {
        String[] serverInfo = null;
        final String HTTP_PORT = "http.port";
        final String HTTPS_ENABLE = "https.enable";
        final String HTTPS_PORT = "https.port";
        final File confFile = PropertiesUtil.findConfigFile("server.properties");
        try {
            InputStream is = new FileInputStream(confFile);
            String port = null;
            String protocol = null;
            final Properties properties = ServerProperties.getServerProperties(is);
            if (properties.getProperty(HTTPS_ENABLE).equals("true")){
                port = properties.getProperty(HTTPS_PORT);
                protocol = "https://";
            } else {
                port = properties.getProperty(HTTP_PORT);
                protocol = "http://";
            }
            serverInfo = new String[]{port, protocol};
        } catch (final IOException e) {
            LOGGER.warn("Failed to read configuration from server.properties file", e);
        }
        return serverInfo;
    }

    private boolean setupDesktopClusterNetworkRules(Network network, UserVm worksVm, IpAddress publicIp) throws ManagementServerException {
        boolean egress = false;
        boolean firewall = false;
        boolean firewall2 = false;
        boolean portForwarding = false;
        // Firewall Egress Network
        try {
            egress = provisionEgressFirewallRules(network, owner, CLUSTER_USER_PORTAL_PORT, CLUSTER_ADMIN_PORTAL_PORT);
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info(String.format("Provisioned egress firewall rule to open up port %d to %d on %s for Desktop cluster : %s", CLUSTER_USER_PORTAL_PORT, CLUSTER_ADMIN_PORTAL_PORT, publicIp.getAddress().addr(), desktopCluster.getName()));
            }
        } catch (NoSuchFieldException | IllegalAccessException | ResourceUnavailableException | NetworkRuleConflictException e) {
            throw new ManagementServerException(String.format("Failed to provision egress firewall rules for Web access for the Desktop cluster : %s", desktopCluster.getName()), e);
        }
        // Firewall rule fo Web access on WorksVM
        if (egress) {
            try {
                firewall = provisionFirewallRules(publicIp, owner, CLUSTER_USER_PORTAL_PORT, CLUSTER_API_PORT);
                if (LOGGER.isInfoEnabled()) {
                    LOGGER.info(String.format("Provisioned firewall rule to open up port %d to %d on %s for Desktop cluster : %s", CLUSTER_USER_PORTAL_PORT, CLUSTER_API_PORT, publicIp.getAddress().addr(), desktopCluster.getName()));
                }
                firewall2 = provisionFirewallRules(publicIp, owner, CLUSTER_SAMBA_PORT, CLUSTER_SAMBA_PORT);
                if (LOGGER.isInfoEnabled()) {
                    LOGGER.info(String.format("Provisioned firewall rule to open up port %d to %d on %s for Desktop cluster : %s", CLUSTER_SAMBA_PORT, CLUSTER_SAMBA_PORT, publicIp.getAddress().addr(), desktopCluster.getName()));
                }
            } catch (NoSuchFieldException | IllegalAccessException | ResourceUnavailableException | NetworkRuleConflictException e) {
                throw new ManagementServerException(String.format("Failed to provision firewall rules for Web access for the Desktop cluster : %s", desktopCluster.getName()), e);
            }
            if (firewall && firewall2) {
                // Port forwarding rule fo Web access on WorksVM
                try {
                    portForwarding = provisionPortForwardingRules(publicIp, network, owner, worksVm, CLUSTER_ADMIN_PORTAL_PORT, CLUSTER_USER_PORTAL_PORT, CLUSTER_SAMBA_PORT, CLUSTER_API_PORT);
                } catch (ResourceUnavailableException | NetworkRuleConflictException e) {
                    throw new ManagementServerException(String.format("Failed to activate Web port forwarding rules for the Desktop cluster : %s", desktopCluster.getName()), e);
                }
                if (portForwarding) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean startDesktopClusterOnCreate() {
        init();
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info(String.format("Starting Desktop cluster : %s", desktopCluster.getName()));
        }
        stateTransitTo(desktopCluster.getId(), DesktopCluster.Event.StartRequested);
        DeployDestination dest = null;
        try {
            dest = plan();
        } catch (InsufficientCapacityException e) {
            logTransitStateAndThrow(Level.ERROR, String.format("Provisioning the cluster failed due to insufficient capacity in the desktop cluster: %s", desktopCluster.getUuid()), desktopCluster.getId(), DesktopCluster.Event.CreateFailed, e);
        }
        Network network = null;
        try {
            network = startDesktopClusterNetwork(dest);
        } catch (ManagementServerException e) {
            logTransitStateAndThrow(Level.ERROR, String.format("Failed to start desktop cluster : %s as its network cannot be started", desktopCluster.getName()), desktopCluster.getId(), DesktopCluster.Event.CreateFailed, e);
        }
        IpAddress publicIpAddress = null;
        publicIpAddress = getDesktopClusterServerIp();
        if (publicIpAddress == null) {
            logTransitStateAndThrow(Level.ERROR, String.format("Failed to start Desktop cluster : %s as no public IP found for the cluster" , desktopCluster.getName()), desktopCluster.getId(), DesktopCluster.Event.CreateFailed);
        }
        List<UserVm> clusterVMs = new ArrayList<>();
        UserVm worksVM = null;
        try {
            worksVM = provisionDesktopClusterWorksControlVm(network);
        }  catch (CloudRuntimeException | ManagementServerException | ResourceUnavailableException | InsufficientCapacityException e) {
            logTransitStateAndThrow(Level.ERROR, String.format("Provisioning the Works Control VM failed in the desktop cluster : %s, %s", desktopCluster.getName(), e), desktopCluster.getId(), DesktopCluster.Event.CreateFailed, e);
        }
        clusterVMs.add(worksVM);
        if (worksVM.getState().equals(VirtualMachine.State.Running)) {
            boolean setup = false;
            try {
                setup = setupDesktopClusterNetworkRules(network, worksVM, publicIpAddress);
            } catch (ManagementServerException e) {
                logTransitStateAndThrow(Level.ERROR, String.format("Failed to setup Desktop cluster : %s, unable to setup network rules", desktopCluster.getName()), desktopCluster.getId(), DesktopCluster.Event.CreateFailed, e);
            }
            if (setup) {
                try {
                    if (callApi(publicIpAddress.getAddress().addr())) {
                        UserVm dcVM = null;
                        try {
                            dcVM = provisionDesktopClusterDcControlVm(network);
                        } catch (CloudRuntimeException | ManagementServerException | ResourceUnavailableException | InsufficientCapacityException e) {
                            logTransitStateAndThrow(Level.ERROR, String.format("Provisioning the DC Control VM failed in the desktop cluster : %s, %s", desktopCluster.getName(), e), desktopCluster.getId(), DesktopCluster.Event.CreateFailed, e);
                        }
                        clusterVMs.add(dcVM);
                        if (LOGGER.isInfoEnabled()) {
                            LOGGER.info(String.format("Desktop cluster : %s Control VMs successfully provisioned", desktopCluster.getName()));
                        }
                        stateTransitTo(desktopCluster.getId(), DesktopCluster.Event.OperationSucceeded);
                        return true;
                    }
                } catch (IOException | InterruptedException e) {
                    logTransitStateAndThrow(Level.ERROR, String.format("Provisioning failed in the desktop cluster : %s, %s", desktopCluster.getName(), e), desktopCluster.getId(), DesktopCluster.Event.CreateFailed, e);
                }
            }
        }
        return false;
    }

    public boolean startStoppedDesktopCluster() throws CloudRuntimeException {
        init();
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info(String.format("Starting desktop cluster : %s", desktopCluster.getName()));
        }
        stateTransitTo(desktopCluster.getId(), DesktopCluster.Event.StartRequested);
        startDesktopClusterVMs();
        stateTransitTo(desktopCluster.getId(), DesktopCluster.Event.OperationSucceeded);
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info(String.format("Desktop cluster : %s successfully started", desktopCluster.getName()));
        }
        return true;
    }

    public boolean reconcileAlertCluster() {
        init();
        List<DesktopClusterVmMapVO> vmMapVOList = getControlVMMaps();
        if (CollectionUtils.isEmpty(vmMapVOList)) {
            return false;
        }
        // mark the cluster to be running
        stateTransitTo(desktopCluster.getId(), DesktopCluster.Event.RecoveryRequested);
        stateTransitTo(desktopCluster.getId(), DesktopCluster.Event.OperationSucceeded);
        return true;
    }

    public boolean callApi(String sambaIp) throws InterruptedException, IOException {
        int tryCount = 0;
        HttpURLConnection conn = null;
        while (tryCount < 10) {
            Thread.sleep(60000);
            try {
                URL url = new URL("http://"+sambaIp+":9017/api/v1/version");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Connection", "keep-alive");
                conn.setConnectTimeout(180000);
                conn.setReadTimeout(180000);
                conn.setDoOutput(true);
                int responseCode = conn.getResponseCode();
                if (responseCode == 200) {
                    return true;
                }
            } catch (ConnectException e) {
                tryCount++;
                if (tryCount > 8) {
                    logTransitStateAndThrow(Level.ERROR, String.format("DC Control VM could not be deployed because Works API call failed. : %s, %s", desktopCluster.getName(), e), desktopCluster.getId(), DesktopCluster.Event.CreateFailed, e);
                }
            }
        }
        return false;
    }
}
