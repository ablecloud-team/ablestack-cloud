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

package com.cloud.ssv.actionworkers;

// import java.io.File;
// import java.io.FileInputStream;
// import java.io.InputStream;
// import java.net.ConnectException;
// import java.net.HttpURLConnection;
// import java.net.URL;
import java.io.IOException;
import java.util.ArrayList;
// import java.util.Properties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.command.user.ssv.CreateSSVCmd;
// import org.apache.cloudstack.config.ApiServiceConfiguration;
import org.apache.commons.codec.binary.Base64;
// import org.apache.commons.collections.CollectionUtils;
// import org.apache.cloudstack.context.CallContext;
import org.apache.log4j.Level;

import com.cloud.api.ApiDBUtils;
import com.cloud.dc.DataCenter;
import com.cloud.deploy.DeployDestination;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.ManagementServerException;
// import com.cloud.exception.NetworkRuleConflictException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.network.IpAddress;
import com.cloud.network.Network.IpAddresses;
import com.cloud.network.Network;
import com.cloud.network.NetworkProfile;
import com.cloud.storage.DiskOfferingVO;
// import com.cloud.user.User;
import com.cloud.offering.ServiceOffering;
// import com.cloud.user.Account;
// import com.cloud.user.UserAccount;
import com.cloud.uservm.UserVm;
import com.cloud.vm.UserVmVO;
// import com.cloud.vm.VMInstanceVO;
import com.cloud.utils.StringUtils;
import com.cloud.utils.exception.CloudRuntimeException;
// import com.cloud.utils.PropertiesUtil;
// import com.cloud.utils.server.ServerProperties;
import com.cloud.ssv.SSV;
import com.cloud.ssv.SSVManagerImpl;
import com.cloud.ssv.SSVNetMapVO;
import com.cloud.ssv.SSVVO;
import com.cloud.ssv.SSVVmMapVO;
import com.cloud.vm.ReservationContext;
import com.cloud.vm.ReservationContextImpl;
import com.cloud.vm.VirtualMachine;
// import com.cloud.api.query.vo.UserAccountJoinVO;

public class SSVStartWorker extends SSVModifierActionWorker {

    // private DesktopControllerVersion ssvVersion;
    private static final long GiB_TO_BYTES = 1024 * 1024 * 1024;

    public SSVStartWorker(final SSV ssv, final SSVManagerImpl ssvManager) {
        super(ssv, ssvManager);
    }

    // public DesktopControllerVersion getSSVVersion() {
    //     if (ssvVersion == null) {
    //         ssvVersion = desktopControllerVersionDao.findById(ssv.getDesktopVersionId());
    //     }
    //     return ssvVersion;
    // }
    private String getSSVConfig(CreateSSVCmd cmd, final DataCenter zone) throws IOException {
        // String[] keys = getServiceUserKeys(owner);
        // String[] info = getServerProperties();
        // String ssvConfig = readResourceFile("/conf/ssv");
        // Network network = networkService.getNetwork(cmd.getNetworkId());
        // final String ssvName = "{{ ssv_name }}";
        // final String ssvType = "{{ ssv_type }}";
        // final String ssvNetworkIp = "{{ ssv_network_ip }}";
        // final String ssvNetworkType = "{{ ssv_network_type }}";
        // final String ssvNetworkGateway = "{{ ssv_network_gateway }}";
        // final String ssvNetworkNetmask = "{{ ssv_network_netmask }}";
        // final String ssvNetworkDns = "{{ ssv_network_dns }}";

        // List<UserAccountJoinVO> domain = userAccountJoinDao.searchByAccountId(owner.getId());
        // ssvConfig = ssvConfig.replace(ssvName, ssv.getName());
        // ssvConfig = ssvConfig.replace(ssvType, ssv.getSsvType());
        // ssvConfig = ssvConfig.replace(ssvNetworkIp, cmd.getSsvIp());
        // ssvConfig = ssvConfig.replace(ssvNetworkType, network.getGuestType().toString());
        // ssvConfig = ssvConfig.replace(ssvNetworkGateway, cmd.getGateway());
        // ssvConfig = ssvConfig.replace(ssvNetworkNetmask, cmd.getNetmask());
        // ssvConfig = ssvConfig.replace(ssvNetworkDns, network.getDns1());

        JSONObject obj = new JSONObject();

        try {
            JSONArray jArray = new JSONArray();//배열이 필요할때
            List<SSVNetMapVO> netvo = ssvNetMapDao.listBySSVServiceId(ssv.getId());
            for(SSVNetMapVO vo : netvo) {
                Network network = networkDao.findByIdIncludingRemoved(vo.getNetworkId());
                NetworkProfile profile = ApiDBUtils.getNetworkProfile(network.getId());
                JSONObject sObject = new JSONObject();//배열 내에 들어갈 json
                sObject.put("ssv.network.type", network.getGuestType().toString());
                sObject.put("ssv.network.ip", vo.getNetworkIp());
                sObject.put("ssv.network.l2.gateway", vo.getNetworkGateway());
                sObject.put("ssv.network.l2.netmask", vo.getNetworkNetmask());
                sObject.put("ssv.network.l2.dns", profile.getDns1());
                jArray.put(sObject);
            }
            obj.put("ssv.name", ssv.getName());
            obj.put("ssv.type", ssv.getSsvType());
            obj.put("ssv.networks", jArray);
        } catch (JSONException e) {
            logAndThrow(Level.ERROR, "Failed to create data in json format.", e);
        }
        LOGGER.debug("JSON STRING :::::::::: " + obj.toString());
        String base64UserData = Base64.encodeBase64String(obj.toString().getBytes(StringUtils.getPreferredCharset()));
        String ssvEncodeConfig = readResourceFile("/conf/ssv.yml");
        ssvEncodeConfig = ssvEncodeConfig.replace("{{ ssv_encode }}", base64UserData);
        return ssvEncodeConfig;
    }

    private UserVm provisionSSV(CreateSSVCmd cmd, final Network network) throws
            InsufficientCapacityException, ManagementServerException, ResourceUnavailableException {
        UserVm vm = null;
        vm = createSSV(cmd, network);
        LOGGER.info("provisionSSV Done 1111:::::");
        startVM(vm);
        LOGGER.info("provisionSSV Done22222 :::::");
        vm = userVmDao.findById(vm.getId());
        if (vm == null) {
            throw new ManagementServerException(String.format("Failed to provision Shared Storage VM"));
        }
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info(String.format("Provisioned VM : %s in to the Shared Storage VM : %s", vm.getDisplayName(), vm.getName()));
        }
        return vm;
    }


    private UserVm createSSV(CreateSSVCmd cmd, final Network network) throws ManagementServerException,
        ResourceUnavailableException, InsufficientCapacityException {
        LOGGER.info("createSSV Start!!!!!!:::::");
        UserVm vm = null;
        LinkedHashMap<Long, IpAddresses> ipToNetworkMap = null;
        DataCenter zone = dataCenterDao.findById(ssv.getZoneId());
        ServiceOffering serviceOffering = serviceOfferingDao.findById(ssv.getServiceOfferingId());
        List<Long> networkIds = new ArrayList<Long>();
        networkIds.add(cmd.getNetworkId());
        String reName = ssv.getName();
        String hostName = reName + "-SSV";
        Map<String, String> customParameterMap = new HashMap<String, String>();
        DiskOfferingVO diskOffering = diskOfferingDao.findById(serviceOffering.getId());
        long rootDiskSizeInBytes = diskOffering.getDiskSize();
        if (rootDiskSizeInBytes > 0) {
            long rootDiskSizeInGiB = rootDiskSizeInBytes / GiB_TO_BYTES;
            customParameterMap.put("rootdisksize", String.valueOf(rootDiskSizeInGiB));
        }
        String ssvConfig = null;
        try {
            ssvConfig = getSSVConfig(cmd, zone);
            LOGGER.info("createSSV ssvConfig!!!!!!:::::" + ssvConfig);
        } catch (IOException e) {
            logAndThrow(Level.ERROR, "Failed to read Shared Storage VM  Userdata configuration file", e);
        }
        String base64UserData = Base64.encodeBase64String(ssvConfig.getBytes(StringUtils.getPreferredCharset()));
        List<String> keypairs = new ArrayList<String>(); // 키페어 파라메타 임시 생성
        if (network.getGuestType().equals(Network.GuestType.L2)) {
            LOGGER.info("createSSV vm L2!!!!!!:::::" + owner.getAccountName());
            LOGGER.info("createSSV vm L2!!!!!serviceOffering!:::::"+serviceOffering);
            LOGGER.info("createSSV vm L2!!!!!cmd.getDiskOfferingId()!:::::"+cmd.getDiskOfferingId());
            Network.IpAddresses addrs = new Network.IpAddresses(null, null, null);
            vm = userVmService.createAdvancedVirtualMachine(zone, serviceOffering, ssvTemplate, networkIds, owner,
                hostName, hostName, cmd.getDiskOfferingId(), cmd.getSize(), null,
                ssvTemplate.getHypervisorType(), BaseCmd.HTTPMethod.POST, base64UserData, null, null, keypairs,
                null, addrs, false, null, null, customParameterMap, null, null, null, null, true, null, null);
            LOGGER.info("createSSV vm Done!!!!!!:::::" + vm);
        } else {
            LOGGER.info("createSSV Done L2 NONONO!!!!!!:::::");
            ipToNetworkMap = new LinkedHashMap<Long, IpAddresses>();
            Network.IpAddresses addrs = new Network.IpAddresses(null, null, null);
            Network.IpAddresses ssvAddrs = new Network.IpAddresses(cmd.getSsvIp(), null, null);
            ipToNetworkMap.put(cmd.getNetworkId(), ssvAddrs);
            vm = userVmService.createAdvancedVirtualMachine(zone, serviceOffering, ssvTemplate, networkIds, owner,
                hostName, hostName, cmd.getDiskOfferingId(), cmd.getSize(), null,
                ssvTemplate.getHypervisorType(), BaseCmd.HTTPMethod.POST, base64UserData, null, null, keypairs,
                ipToNetworkMap, addrs, false, null, null, customParameterMap, null, null, null, null, true, null, null);
        }
        LOGGER.info("createSSV Done!!!!!!:::::"+ vm.getId());
        SSVVO ssvvo = ssvDao.findById(ssv.getId());
        LOGGER.info("ssvvo.getId() :::::" + ssvvo.getId());

        SSVVmMapVO svmv = new SSVVmMapVO(ssv.getId(), vm.getId());
        ssvVmMapDao.persist(svmv);

        // final VMInstanceVO vmForUpdate = vmInstanceDao.findById(vm.getId());
        // vmForUpdate.setAccountId(User.UID_ADMIN);
        // vmInstanceDao.update(vm.getId(), vmForUpdate);

        // if (!ssvDao.update(ssv.getId(), ssvvo)) {
        //     LOGGER.info("createSSV update!!!!!!:::::");
        //     throw new CloudRuntimeException(String.format("Failed to update Shared Storage VM ID: %s", ssv.getUuid()));
        // }
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info(String.format("Created VM ID : %s, %s in the Shared Storage VM  : %s", vm.getUuid(), hostName, ssv.getName()));
        }
        return vm;
    }

    private Network startSSVNetwork(CreateSSVCmd cmd, final DeployDestination destination) throws ManagementServerException {
        final ReservationContext context = new ReservationContextImpl(null, null, null, owner);
        Network network = networkDao.findById(cmd.getNetworkId());
        if (network == null) {
            String msg  = String.format("Network for Shared Storage VM  : %s not found", ssv.getName());
            LOGGER.warn(msg);
            stateTransitTo(ssv.getId(), SSV.Event.CreateFailed);
            throw new ManagementServerException(msg);
        }
        try {
            networkMgr.startNetwork(network.getId(), destination, context);
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info(String.format("Network : %s is started for the Shared Storage VM  : %s", network.getName(), ssv.getName()));
            }
        } catch (ConcurrentOperationException | ResourceUnavailableException |InsufficientCapacityException e) {
            String msg = String.format("Failed to start Shared Storage VM  : %s as unable to start associated network : %s" , ssv.getName(), network.getName());
            LOGGER.error(msg, e);
            stateTransitTo(ssv.getId(), SSV.Event.CreateFailed);
            throw new ManagementServerException(msg, e);
        }
        return network;
    }

    private void startSSV() {
        SSVVmMapVO vo = ssvVmMapDao.listVmBySSVServiceId(ssv.getId());
        UserVm vm = userVmDao.findById(vo.getVmId());
        if (vm == null) {
            logTransitStateAndThrow(Level.ERROR, String.format("Failed to start VMs in Shared Storage VM  : %s", ssv.getName()), ssv.getId(), SSV.Event.OperationFailed);
        } else {
            try {
                startVM(vm);
            } catch (ManagementServerException ex) {
                LOGGER.warn(String.format("Failed to start VM : %s in Shared Storage VM  : %s due to ", vm.getDisplayName(), ssv.getName()) + ex);
                // dont bail out here. proceed further to stop the reset of the VM's
            }
            int cnt = 10;
            while (cnt > 0) {
                vm = userVmDao.findById(vo.getVmId());
                if (vm.getState().equals(VirtualMachine.State.Running)) {
                    break;
                } else {
                    if (cnt == 0) {
                        logTransitStateAndThrow(Level.ERROR, String.format("Failed to start Control VMs in Shared Storage VM  : %s", ssv.getName()), ssv.getId(), SSV.Event.OperationFailed);
                    } else {
                        try {
                            Thread.sleep(1000);
                            cnt--;
                        } catch (InterruptedException e) {
                        }
                    }
                }
            }
        }
    }

    // private String[] getServiceUserKeys(Account owner) {
    //     if (owner == null) {
    //         owner = CallContext.current().getCallingAccount();
    //     }
    //     String username = owner.getAccountName();
    //     UserAccount user = accountService.getActiveUserAccount(username, owner.getDomainId());
    //     String[] keys = null;
    //     String apiKey = user.getApiKey();
    //     String secretKey = user.getSecretKey();
    //     if ((apiKey == null || apiKey.length() == 0) || (secretKey == null || secretKey.length() == 0)) {
    //         keys = accountService.createApiKeyAndSecretKey(user.getId());
    //     } else {
    //         keys = new String[]{apiKey, secretKey};
    //     }
    //     return keys;
    // }

    // private String[] getServerProperties() {
    //     String[] serverInfo = null;
    //     final String HTTP_PORT = "http.port";
    //     final String HTTPS_ENABLE = "https.enable";
    //     final String HTTPS_PORT = "https.port";
    //     final File confFile = PropertiesUtil.findConfigFile("server.properties");
    //     try {
    //         InputStream is = new FileInputStream(confFile);
    //         String port = null;
    //         String protocol = null;
    //         final Properties properties = ServerProperties.getServerProperties(is);
    //         if (properties.getProperty(HTTPS_ENABLE).equals("true")){
    //             port = properties.getProperty(HTTPS_PORT);
    //             protocol = "https://";
    //         } else {
    //             port = properties.getProperty(HTTP_PORT);
    //             protocol = "http://";
    //         }
    //         serverInfo = new String[]{port, protocol};
    //     } catch (final IOException e) {
    //         LOGGER.warn("Failed to read configuration from server.properties file", e);
    //     }
    //     return serverInfo;
    // }

    private boolean setupSSVNetworkRules(Network network, UserVm worksVm, IpAddress publicIp) throws ManagementServerException {
        // boolean egress = false;
        // boolean firewall = false;
        // boolean portForwarding = false;
        // // Firewall Egress Network
        // try {
        //     egress = provisionEgressFirewallRules(network, owner, NFS_PORT, CLUSTER_LITE_PORT);
        //     if (LOGGER.isInfoEnabled()) {
        //         LOGGER.info(String.format("Provisioned egress firewall rule to open up port %d to %d on %s for Shared Storage VM  : %s", NFS_PORT, CLUSTER_LITE_PORT, publicIp.getAddress().addr(), ssv.getName()));
        //     }
        // } catch (NoSuchFieldException | IllegalAccessException | ResourceUnavailableException | NetworkRuleConflictException e) {
        //     throw new ManagementServerException(String.format("Failed to provision egress firewall rules for Web access for the Shared Storage VM  : %s", ssv.getName()), e);
        // }
        // // Firewall rule fo Web access on WorksVM
        // if (egress) {
        //     try {
        //         firewall = provisionFirewallRules(publicIp, owner, NFS_PORT, CLUSTER_API_PORT);
        //         if (LOGGER.isInfoEnabled()) {
        //             LOGGER.info(String.format("Provisioned firewall rule to open up port %d to %d on %s for Shared Storage VM  : %s", NFS_PORT, CLUSTER_API_PORT, publicIp.getAddress().addr(), ssv.getName()));
        //         }
        //         firewall2 = provisionFirewallRules(publicIp, owner, CLUSTER_SAMBA_PORT, CLUSTER_SAMBA_PORT);
        //         if (LOGGER.isInfoEnabled()) {
        //             LOGGER.info(String.format("Provisioned firewall rule to open up port %d to %d on %s for Shared Storage VM  : %s", CLUSTER_SAMBA_PORT, CLUSTER_SAMBA_PORT, publicIp.getAddress().addr(), ssv.getName()));
        //         }
        //     } catch (NoSuchFieldException | IllegalAccessException | ResourceUnavailableException | NetworkRuleConflictException e) {
        //         throw new ManagementServerException(String.format("Failed to provision firewall rules for Web access for the Shared Storage VM  : %s", ssv.getName()), e);
        //     }
        //     if (firewall && firewall2) {
        //         // Port forwarding rule fo Web access on WorksVM
        //         try {
        //             portForwarding = provisionPortForwardingRules(publicIp, network, owner, worksVm, CLUSTER_LITE_PORT, NFS_PORT, CLUSTER_SAMBA_PORT, CLUSTER_API_PORT);
        //         } catch (ResourceUnavailableException | NetworkRuleConflictException e) {
        //             throw new ManagementServerException(String.format("Failed to activate Web port forwarding rules for the Shared Storage VM  : %s", ssv.getName()), e);
        //         }
        //         if (portForwarding) {
        //             return true;
        //         }
        //     }
        // }
        return false;
    }

    public boolean startSSVOnCreate(CreateSSVCmd cmd) {
        LOGGER.info("startSSVOnCreate start :::::");
        init();
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info(String.format("Starting Shared Storage VM  : %s", ssv.getName()));
        }
        stateTransitTo(ssv.getId(), SSV.Event.StartRequested);
        DeployDestination dest = null;
        try {
            dest = plan();
        } catch (InsufficientCapacityException e) {
            logTransitStateAndThrow(Level.ERROR, String.format("Provisioning the cluster failed due to insufficient capacity in the Shared Storage VM : %s", ssv.getUuid()), ssv.getId(), SSV.Event.CreateFailed, e);
        }
        Network network = null;
        try {
            network = startSSVNetwork(cmd, dest);
        } catch (ManagementServerException e) {
            logTransitStateAndThrow(Level.ERROR, String.format("Failed to start Shared Storage VM  : %s as its network cannot be started", ssv.getName()), ssv.getId(), SSV.Event.CreateFailed, e);
        }
        IpAddress publicIpAddress = null;

        if(network != null && Network.GuestType.Isolated.equals(network.getGuestType())){
            publicIpAddress = getSSVServerIp(cmd);
            if (publicIpAddress == null) {
                logTransitStateAndThrow(Level.ERROR, String.format("Failed to start Shared Storage VM  : %s as no public IP found for the cluster" , ssv.getName()), ssv.getId(), SSV.Event.CreateFailed);
            }
        }

        UserVm vm = null;
        try {
            LOGGER.info("startSSVOnCreate ssv value :::::" + ssv.getName());
            vm = provisionSSV(cmd, network);

            if (LOGGER.isInfoEnabled()) {
                LOGGER.info(String.format("Shared Storage VM  : %s VM successfully provisioned", vm.getName()));
            }
            stateTransitTo(ssv.getId(), SSV.Event.OperationSucceeded);
            return true;
        }  catch (CloudRuntimeException | ManagementServerException | ResourceUnavailableException | InsufficientCapacityException e) {
            logTransitStateAndThrow(Level.ERROR, String.format("Provisioning the Shared Storage VM failed : %s, %s", vm.getName(), e), ssv.getId(), SSV.Event.CreateFailed, e);
        }
        LOGGER.info("startSSVOnCreate Done :::::");

        return false;
    }

    public boolean startStoppedSSV() throws CloudRuntimeException {
        init();
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info(String.format("Starting Shared Storage VM  : %s", ssv.getName()));
        }
        stateTransitTo(ssv.getId(), SSV.Event.StartRequested);
        startSSV();
        stateTransitTo(ssv.getId(), SSV.Event.OperationSucceeded);
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info(String.format("Shared Storage VM  : %s successfully started", ssv.getName()));
        }
        return true;
    }

    public boolean reconcileAlertCluster() {
        init();
        SSVVmMapVO vo = ssvVmMapDao.listVmBySSVServiceId(ssv.getId());
        UserVmVO vm = userVmDao.findById(vo.getVmId());
        if (vm == null || vm.isRemoved()) {
            return false;
        }
        // mark the cluster to be running
        stateTransitTo(ssv.getId(), SSV.Event.RecoveryRequested);
        stateTransitTo(ssv.getId(), SSV.Event.OperationSucceeded);
        return true;
    }

    // public boolean callApi(String sambaIp) throws InterruptedException, IOException {
    //     int tryCount = 0;
    //     HttpURLConnection conn = null;
    //     while (tryCount < 10) {
    //         Thread.sleep(60000);
    //         try {
    //             URL url = new URL("http://"+sambaIp+":9017/api/v1/version");
    //             conn = (HttpURLConnection) url.openConnection();
    //             conn.setRequestMethod("GET");
    //             conn.setRequestProperty("Content-Type", "application/json");
    //             conn.setRequestProperty("Connection", "keep-alive");
    //             conn.setConnectTimeout(180000);
    //             conn.setReadTimeout(180000);
    //             conn.setDoOutput(true);
    //             int responseCode = conn.getResponseCode();
    //             if (responseCode == 200) {
    //                 return true;
    //             }
    //         } catch (ConnectException e) {
    //             tryCount++;
    //             if (tryCount > 8) {
    //                 logTransitStateAndThrow(Level.ERROR, String.format("DC Control VM could not be deployed because Works API call failed. : %s, %s", ssv.getName(), e), ssv.getId(), SSV.Event.CreateFailed, e);
    //             }
    //         }
    //     }
    //     return false;
    // }
}
