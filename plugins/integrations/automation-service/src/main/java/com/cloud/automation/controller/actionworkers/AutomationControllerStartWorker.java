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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import com.cloud.api.query.vo.UserAccountJoinVO;
import com.cloud.dc.DataCenter;
import com.cloud.deploy.DeployDestination;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.ManagementServerException;
import com.cloud.exception.NetworkRuleConflictException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.network.IpAddress;
import com.cloud.network.Network;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.NetworkVO;
import com.cloud.offering.ServiceOffering;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.user.Account;
import com.cloud.user.UserAccount;
import com.cloud.utils.PropertiesUtil;
import com.cloud.utils.component.ComponentContext;
import com.cloud.utils.exception.ExecutionException;
import com.cloud.utils.server.ServerProperties;
import com.cloud.vm.ReservationContext;
import com.cloud.vm.ReservationContextImpl;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.command.user.vm.StartVMCmd;
import org.apache.cloudstack.config.ApiServiceConfiguration;
import org.apache.cloudstack.context.CallContext;
import org.apache.commons.codec.binary.Base64;
import org.apache.logging.log4j.Level;

import com.cloud.uservm.UserVm;
import com.cloud.utils.StringUtils;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.automation.controller.AutomationController;
import com.cloud.automation.controller.AutomationControllerManagerImpl;
import com.cloud.vm.VirtualMachine;



public class AutomationControllerStartWorker extends AutomationControllerResourceModifierActionWorker {

    private static final long GiB_TO_BYTES = 1024 * 1024 * 1024;
    private static final int AUTOMATION_CONTROLLER_HTTP_PORT = 80;
    private static final int HTTP_CONNECT_TIMEOUT_MS = 5000;
    private static final int HTTP_READ_TIMEOUT_MS = 5000;
    private static final long AUTOMATION_CONTROLLER_CREATE_READY_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(30);
    private static final long AUTOMATION_CONTROLLER_READY_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(15);
    private static final long AUTOMATION_CONTROLLER_READY_RETRY_INTERVAL_MS = TimeUnit.SECONDS.toMillis(5);
    private static final int AUTOMATION_CONTROLLER_READY_SUCCESS_THRESHOLD = 3;

    public AutomationControllerStartWorker(final AutomationController automationController, final AutomationControllerManagerImpl automationManager) {
        super(automationController, automationManager);
    }

    private void startAutomationControllerVMs() {
        List <UserVm> automationVms = getAutomationControllerVMs();
        for (final UserVm vm : automationVms) {
            if (vm == null) {
                logTransitStateAndThrow(Level.ERROR, String.format("Failed to start Control VMs in automation controller : %s", automationController.getName()), automationController.getId(), AutomationController.Event.OperationFailed);
            }
            if (VirtualMachine.State.Running.equals(vm.getState())) {
                continue;
            }
            try {
                startAutomationVM(vm);
            } catch (ManagementServerException ex) {
                logger.warn(String.format("Failed to start VM : %s in automation controller : %s due to ", vm.getDisplayName(), automationController.getName()) + ex);
                // dont bail out here. proceed further to stop the reset of the VM's
            }
        }
        for (final UserVm userVm : automationVms) {
            UserVm vm = userVmDao.findById(userVm.getId());
            if (vm == null || !vm.getState().equals(VirtualMachine.State.Running)) {
                logTransitStateAndThrow(Level.ERROR, String.format("Failed to start Control VMs in automation controller : %s", automationController.getName()), automationController.getId(), AutomationController.Event.OperationFailed);
            }
        }
    }

    protected void startAutomationVM(final UserVm vm) throws ManagementServerException {
        try {
            StartVMCmd startVm = new StartVMCmd();
            startVm = ComponentContext.inject(startVm);
            Field f = startVm.getClass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(startVm, vm.getId());
            userVmService.startVirtualMachine(startVm);
            if (logger.isInfoEnabled()) {
                logger.info(String.format("Started VM : %s in the automation controller : %s", vm.getDisplayName(), automationController.getName()));
            }
        } catch (IllegalAccessException | NoSuchFieldException | ExecutionException |
                 ResourceUnavailableException | ResourceAllocationException | InsufficientCapacityException ex) {
            throw new ManagementServerException(String.format("Failed to start VM in the automation controller : %s", automationController.getName()), ex);
        }

        UserVm startVm = userVmDao.findById(vm.getId());
        if (!startVm.getState().equals(VirtualMachine.State.Running)) {
            throw new ManagementServerException(String.format("Failed to start VM in the automation controller : %s", automationController.getName()));
        }
    }

    private Network startAutomationControllerNetwork(final DeployDestination destination) throws ManagementServerException {
        final ReservationContext context = new ReservationContextImpl(null, null, null, owner);
        Network network = networkDao.findById(automationController.getNetworkId());
        if (network == null) {
            String msg  = String.format("Network for automation controller : %s not found", automationController.getName());
            logger.warn(msg);
            stateTransitTo(automationController.getId(), AutomationController.Event.CreateFailed);
            throw new ManagementServerException(msg);
        }
        try {
            networkMgr.startNetwork(network.getId(), destination, context);
            if (logger.isInfoEnabled()) {
                logger.info(String.format("Network : %s is started for the automation controller : %s", network.getName(), automationController.getName()));
            }
        } catch (ConcurrentOperationException | ResourceUnavailableException | InsufficientCapacityException e) {
            String msg = String.format("Failed to start automation controller : %s as unable to start associated network : %s" , automationController.getName(), network.getName());
            logger.error(msg, e);
            stateTransitTo(automationController.getId(), AutomationController.Event.CreateFailed);
            throw new ManagementServerException(msg, e);
        }
        return network;
    }

    private UserVm createAutomationControllerVM(final Network network) throws ManagementServerException,
            ResourceUnavailableException, InsufficientCapacityException {
        UserVm genieControlVms = null;
        LinkedHashMap<Long, Network.IpAddresses> ipToNetworkMap = null;
        DataCenter zone = dataCenterDao.findById(automationController.getZoneId());
        ServiceOffering serviceOffering = serviceOfferingDao.findById(automationController.getServiceOfferingId());
        List<Long> networkIds = new ArrayList<Long>();
        networkIds.add(automationController.getNetworkId());
        String automationControllerIp = automationController.getAutomationControllerIp();
        String reName = automationController.getName();
        String hostName = reName + "-genie";
        Map<String, String> customParameterMap = new HashMap<String, String>();
        DiskOfferingVO diskOffering = diskOfferingDao.findById(serviceOffering.getId());
        long rootDiskSizeInBytes = diskOffering.getDiskSize();
        if (rootDiskSizeInBytes > 0) {
            long rootDiskSizeInGiB = rootDiskSizeInBytes / GiB_TO_BYTES;
            customParameterMap.put("rootdisksize", String.valueOf(rootDiskSizeInGiB));
        }
        String automationControllerConfig = null;
        try {
            automationControllerConfig = getAutomationControllerConfig(zone);
        } catch (IOException e) {
            logAndThrow(Level.ERROR, "Failed to read Automation Cluster Userdata configuration file", e);
        }
        String base64UserData = Base64.encodeBase64String(automationControllerConfig.getBytes(StringUtils.getPreferredCharset()));
        List<String> keypairs = new ArrayList<String>(); // 키페어 파라메타 임시 생성
        if (automationControllerIp == null || network.getGuestType() == Network.GuestType.L2) {
            Network.IpAddresses addrs = new Network.IpAddresses(null, null, null);
            genieControlVms = userVmService.createAdvancedVirtualMachine(zone, serviceOffering, templates, networkIds, owner,
                    hostName, hostName, null, null, null,
                    templates.getHypervisorType(), BaseCmd.HTTPMethod.POST, base64UserData, null, null, keypairs,
                    null, addrs, null, null, null, customParameterMap, null, null, null, null, true, null, null);
        } else {
            ipToNetworkMap = new LinkedHashMap<Long, Network.IpAddresses>();
            Network.IpAddresses addrs = new Network.IpAddresses(null, null, null);
            Network.IpAddresses controllerAddrs = new Network.IpAddresses(automationControllerIp, null, null);
            ipToNetworkMap.put(automationController.getNetworkId(), controllerAddrs);
            genieControlVms = userVmService.createAdvancedVirtualMachine(zone, serviceOffering, templates, networkIds, owner,
                    hostName, hostName, null, null, null,
                    templates.getHypervisorType(), BaseCmd.HTTPMethod.POST, base64UserData, null, null, keypairs,
                    ipToNetworkMap, addrs, null, null, null, customParameterMap, null, null, null, null, true, null, null);
        }
        if (logger.isInfoEnabled()) {
            logger.info(String.format("Created Control VM ID : %s, %s in the automation controller : %s", genieControlVms.getUuid(), hostName, automationController.getName()));
        }
        return genieControlVms;
    }

    private String[] getServiceUserKeys(Account owner) {
        if (owner == null) {
            owner = CallContext.current().getCallingAccount();
        }
        String username = owner.getAccountName();
        UserAccount user = accountService.getActiveUserAccount(username, owner.getDomainId());
        if (user == null) {
            throw new CloudRuntimeException(String.format("Unable to find an active API user for automation controller account %s", username));
        }
        String[] keys = null;
        String apiKey = user.getApiKey();
        String secretKey = user.getSecretKey();
        if ((apiKey == null || apiKey.length() == 0) || (secretKey == null || secretKey.length() == 0)) {
            keys = accountService.createApiKeyAndSecretKey(user.getId());
        } else {
            keys = new String[]{apiKey, secretKey};
        }
        if (keys == null || keys.length < 2 || keys[0] == null || keys[1] == null) {
            throw new CloudRuntimeException(String.format("Unable to prepare API keys for automation controller account %s", username));
        }
        return keys;
    }

    private String[] getServerProperties() {
        String[] serverInfo = null;
        final String HTTP_PORT = "http.port";
        final String HTTPS_ENABLE = "https.enable";
        final String HTTPS_PORT = "https.port";
        final File confFile = PropertiesUtil.findConfigFile("server.properties");
        if (confFile == null) {
            throw new CloudRuntimeException("Unable to locate server.properties while preparing Genie cloud-init data");
        }
        try (InputStream is = new FileInputStream(confFile)) {
            String port = null;
            String protocol = null;
            final Properties properties = ServerProperties.getServerProperties(is);
            if (Boolean.parseBoolean(properties.getProperty(HTTPS_ENABLE, "false"))){
                port = properties.getProperty(HTTPS_PORT);
                protocol = "https://";
            } else {
                port = properties.getProperty(HTTP_PORT);
                protocol = "http://";
            }
            if (port == null || port.trim().isEmpty()) {
                throw new CloudRuntimeException("Management server API port is not configured in server.properties");
            }
            serverInfo = new String[]{port, protocol};
        } catch (final IOException e) {
            throw new CloudRuntimeException("Failed to read configuration from server.properties file", e);
        }
        return serverInfo;
    }

    private String getAutomationControllerConfig(final DataCenter zone) throws IOException {
        String[] keys = getServiceUserKeys(owner);
        String[] info = getServerProperties();
        String automationControllerConfig = readResourceFile("/conf/genie");
        NetworkVO ntwk = networkDao.findByIdIncludingRemoved(automationController.getNetworkId());
        if (ntwk == null) {
            throw new CloudRuntimeException(String.format("Automation controller network %d was not found",
                    automationController.getNetworkId()));
        }
        if (zone == null) {
            throw new CloudRuntimeException(String.format("Automation controller zone %d was not found",
                    automationController.getZoneId()));
        }
        final String configuredManagementAddresses = ApiServiceConfiguration.ManagementServerAddresses.value();
        if (configuredManagementAddresses == null || configuredManagementAddresses.trim().isEmpty()) {
            throw new CloudRuntimeException("Management server address is not configured");
        }
        final String managementIp = configuredManagementAddresses.split(",")[0].trim();
        final String automationControllerId = "{{ automation_controller_id }}";
        final String automationControllerName = "{{ automation_controller_instance_name }}";
        final String acPublicIp = "{{ ac_public_ip }}";
        final String zoneUuid = "{{ zone_id }}";
        final String zoneName = "{{ zone_name }}";
        final String networkId = "{{ network_id }}";
        final String networkName = "{{ network_name }}";
        final String accountName = "{{ account_name }}";
        final String domainUuid = "{{ domain_uuid }}";
        final String apiKey = "{{ api_key }}";
        final String secretKey = "{{ secret_key }}";
        final String moldIp = "{{ mold_ip }}";
        final String moldPort = "{{ mold_port }}";
        final String moldProtocol = "{{ mold_protocol }}";
        final String moldEndPoint = "{{ mold_end_point}}";
        automationControllerConfig = automationControllerConfig.replace(automationControllerId, automationController.getUuid());
        automationControllerConfig = automationControllerConfig.replace(automationControllerName, automationController.getName()+"-genie");
        if (ntwk.getGuestType() == Network.GuestType.Isolated) {
            List<IPAddressVO> ipAddresses = ipAddressDao.listByAssociatedNetwork(ntwk.getId(), true);
            IPAddressVO sourceNatIp = findSourceNatIp(ipAddresses);
            if (sourceNatIp == null) {
                throw new CloudRuntimeException(String.format("Source NAT IP was not found for automation controller network %s", ntwk.getName()));
            }
            automationControllerConfig = automationControllerConfig.replace(acPublicIp, sourceNatIp.getAddress().addr());
        }
        List<UserAccountJoinVO> domain = userAccountJoinDao.searchByAccountId(owner.getId());
        if (domain == null || domain.isEmpty()) {
            throw new CloudRuntimeException(String.format("Unable to resolve domain information for automation controller account %s",
                    owner.getAccountName()));
        }
        automationControllerConfig = automationControllerConfig.replace(zoneUuid, zone.getUuid());
        automationControllerConfig = automationControllerConfig.replace(zoneName, zone.getName());
        automationControllerConfig = automationControllerConfig.replace(networkId, Long.toString(automationController.getNetworkId()));
        automationControllerConfig = automationControllerConfig.replace(networkName, automationController.getNetworkName());
        automationControllerConfig = automationControllerConfig.replace(accountName, owner.getAccountName());
        automationControllerConfig = automationControllerConfig.replace(domainUuid, domain.get(0).getDomainUuid());
        automationControllerConfig = automationControllerConfig.replace(apiKey, keys[0]);
        automationControllerConfig = automationControllerConfig.replace(secretKey, keys[1]);
        automationControllerConfig = automationControllerConfig.replace(moldIp, managementIp);
        automationControllerConfig = automationControllerConfig.replace(moldPort, info[0]);
        automationControllerConfig = automationControllerConfig.replace(moldProtocol, info[1]);
        automationControllerConfig = automationControllerConfig.replace(moldEndPoint, info[1]+managementIp+":"+info[0]+"/client/api");
        String base64UserData = Base64.encodeBase64String(automationControllerConfig.getBytes(StringUtils.getPreferredCharset()));
        String automationControllerGenieConfig = readResourceFile("/conf/genie.yml");
        final String genieEncode = "{{ genie_encode }}";
        automationControllerGenieConfig = automationControllerGenieConfig.replace(genieEncode, base64UserData);
        return automationControllerGenieConfig;
    }

    private UserVm provisionAutomationControllerVm(final Network network) throws
            InsufficientCapacityException, ManagementServerException, ResourceUnavailableException {
        UserVm genieControlVms = null;
        genieControlVms = createAutomationControllerVM(network);
        if (genieControlVms == null) {
            throw new ManagementServerException(String.format("Failed to provision VM for automation controller : %s" , automationController.getName()));
        }
        addAutomationControllerVm(automationController.getId(), genieControlVms.getId());
        if (logger.isInfoEnabled()) {
            logger.info(String.format("Created Genie Automation Control VM : %s in the automation controller : %s", genieControlVms.getDisplayName(), automationController.getName()));
        }
        return genieControlVms;
    }

    private void setupAutomationControllerNetworkRules(Network network) throws ManagementServerException {
        try {
            boolean tcpEgressReady = provisionEgressFirewallRules(network, owner, "TCP", null, null);
            boolean udpEgressReady = provisionEgressFirewallRules(network, owner, "UDP", null, null);
            if (!tcpEgressReady || !udpEgressReady) {
                throw new ManagementServerException(String.format("Unable to apply bootstrap egress rules for automation controller : %s",
                        automationController.getName()));
            }
        } catch (NoSuchFieldException | IllegalAccessException | ResourceUnavailableException |
                 NetworkRuleConflictException e) {
            throw new ManagementServerException(String.format("Failed to provision bootstrap egress rules for the Automation controller : %s",
                    automationController.getName()), e);
        }
    }

    public boolean startAutomationControllerOnCreate() {
        init();
        if (logger.isInfoEnabled()) {
            logger.info(String.format("Starting Automation Controller : %s", automationController.getName()));
        }
        if (!stateTransitTo(automationController.getId(), AutomationController.Event.StartRequested)) {
            throw new CloudRuntimeException(String.format("Cannot create automation controller %s from state %s",
                    automationController.getName(), automationController.getState()));
        }
        DeployDestination dest = null;
        try {
            dest = plan();
        } catch (InsufficientCapacityException e) {
            logTransitStateAndThrow(Level.ERROR, String.format("Provisioning the cluster failed due to insufficient capacity in the automation controller: %s", automationController.getUuid()), automationController.getId(), AutomationController.Event.CreateFailed, e);
        }
        Network network = null;
        try {
            network = startAutomationControllerNetwork(dest);
        } catch (ManagementServerException e) {
            logTransitStateAndThrow(Level.ERROR, String.format("Failed to start Automation Controller : %s as its network cannot be started", automationController.getName()), automationController.getId(), AutomationController.Event.CreateFailed, e);
        }
        IpAddress publicIpAddress = null;
        publicIpAddress = getAutomationControllerServerIp();
        if (publicIpAddress == null) {
            logTransitStateAndThrow(Level.ERROR, String.format("Failed to start Automation Controller : %s as no public IP found for the Automation Controller" , automationController.getName()), automationController.getId(), AutomationController.Event.CreateFailed);
        }
        UserVm genieVM = null;
        try {
            genieVM = provisionAutomationControllerVm(network);
        }  catch (CloudRuntimeException | ManagementServerException | ResourceUnavailableException | InsufficientCapacityException e) {
            logTransitStateAndThrow(Level.ERROR, String.format("Provisioning the Automation Controller VM failed in the automation controller : %s, %s", automationController.getName(), e), automationController.getId(), AutomationController.Event.CreateFailed, e);
        }
        try {
            setupAutomationControllerNetworkRules(network);
            startAutomationVM(genieVM);
            genieVM = userVmDao.findById(genieVM.getId());
        } catch (ManagementServerException e) {
            logTransitStateAndThrow(Level.ERROR, String.format("Failed to start Automation Controller : %s after preparing bootstrap network rules",
                    automationController.getName()), automationController.getId(), AutomationController.Event.CreateFailed, e);
        }
        if (genieVM != null && genieVM.getState().equals(VirtualMachine.State.Running)) {
            if (logger.isInfoEnabled()) {
                logger.info(String.format("automation controller : %s automation controller VMs successfully provisioned", automationController.getName()));
            }
            String publicIpAddressStr = String.valueOf(publicIpAddress.getAddress());
            if (waitForAutomationControllerReady(publicIpAddressStr, AUTOMATION_CONTROLLER_HTTP_PORT,
                    AUTOMATION_CONTROLLER_CREATE_READY_TIMEOUT_MS, AUTOMATION_CONTROLLER_READY_RETRY_INTERVAL_MS)) {
                if (logger.isInfoEnabled()) {
                    logger.info(String.format("Starting automation controller : %s", automationController.getName()));
                }
                stateTransitTo(automationController.getId(), AutomationController.Event.OperationSucceeded);
                if (logger.isInfoEnabled()) {
                    logger.info(String.format("Automation Controller : %s successfully started", automationController.getName()));
                }
                return true;
            } else {
                if (logger.isInfoEnabled()) {
                    logger.info(String.format("Starting automation controller : %s", automationController.getName()));
                }
                stateTransitTo(automationController.getId(), AutomationController.Event.OperationFailed);
                if (logger.isInfoEnabled()) {
                    logger.info(String.format("Automation Controller : %s unsuccessfully started", automationController.getName()));
                }
                return false;
            }
        }
        stateTransitTo(automationController.getId(), AutomationController.Event.OperationFailed);
        return false;
    }

    public boolean startStoppedAutomationController() throws CloudRuntimeException {
        init();
        IpAddress publicIpAddress = getAutomationControllerServerIp();
        if (publicIpAddress == null) {
            throw new CloudRuntimeException(String.format("No source NAT IP found for automation controller : %s", automationController.getName()));
        }
        String publicIpAddressStr = String.valueOf(publicIpAddress.getAddress());
        AutomationController.Event startEvent = AutomationController.State.Alert.equals(automationController.getState())
                ? AutomationController.Event.RecoveryRequested : AutomationController.Event.StartRequested;
        if (!stateTransitTo(automationController.getId(), startEvent)) {
            throw new CloudRuntimeException(String.format("Cannot start automation controller %s from state %s",
                    automationController.getName(), automationController.getState()));
        }
        startAutomationControllerVMs();
        if (waitForAutomationControllerReady(publicIpAddressStr, AUTOMATION_CONTROLLER_HTTP_PORT,
                AUTOMATION_CONTROLLER_READY_TIMEOUT_MS, AUTOMATION_CONTROLLER_READY_RETRY_INTERVAL_MS)) {
            if (logger.isInfoEnabled()) {
                logger.info(String.format("Starting automation controller : %s", automationController.getName()));
            }
            stateTransitTo(automationController.getId(), AutomationController.Event.OperationSucceeded);
            if (logger.isInfoEnabled()) {
                logger.info(String.format("Automation Controller : %s successfully started", automationController.getName()));
            }
            return true;
        } else {
            if (logger.isInfoEnabled()) {
                logger.info(String.format("Starting automation controller : %s", automationController.getName()));
            }
            stateTransitTo(automationController.getId(), AutomationController.Event.OperationFailed);
            if (logger.isInfoEnabled()) {
                logger.info(String.format("Automation Controller : %s unsuccessfully started", automationController.getName()));
            }
            return false;
        }
    }

    private boolean waitForAutomationControllerReady(String address, int port, long timeoutMs, long retryIntervalMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        int consecutiveSuccesses = 0;
        while (System.currentTimeMillis() < deadline) {
            int responseCode = getHttpResponseCode(address, port);
            if (responseCode >= 200 && responseCode < 400) {
                consecutiveSuccesses++;
                if (consecutiveSuccesses >= AUTOMATION_CONTROLLER_READY_SUCCESS_THRESHOLD) {
                    return true;
                }
            } else {
                consecutiveSuccesses = 0;
            }

            if (logger.isDebugEnabled()) {
                logger.debug(String.format("Automation controller %s is not ready yet on %s:%d (HTTP %d)",
                        automationController.getName(), address, port, responseCode));
            }

            try {
                Thread.sleep(retryIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn(String.format("Interrupted while waiting for automation controller %s readiness", automationController.getName()), e);
                return false;
            }
        }

        logger.warn(String.format("Timed out waiting for automation controller %s readiness on %s:%d after %d ms",
                automationController.getName(), address, port, timeoutMs));
        return false;
    }

    private IPAddressVO findSourceNatIp(List<IPAddressVO> ipAddresses) {
        if (ipAddresses == null) {
            return null;
        }
        for (IPAddressVO ipAddress : ipAddresses) {
            if (ipAddress != null && ipAddress.isSourceNat()) {
                return ipAddress;
            }
        }
        return null;
    }

    private static int getHttpResponseCode(String address, int port) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL("http://" + address + ":" + port);
            URLConnection con = url.openConnection();
            connection = (HttpURLConnection) con;
            connection.setConnectTimeout(HTTP_CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(HTTP_READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            return connection.getResponseCode();
        } catch (IOException exception) {
            return -1;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }


}
