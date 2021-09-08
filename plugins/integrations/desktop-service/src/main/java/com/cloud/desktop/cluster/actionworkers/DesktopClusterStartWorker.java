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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

import org.apache.cloudstack.api.BaseCmd;
import org.apache.commons.collections.CollectionUtils;
import org.apache.log4j.Level;

import com.cloud.dc.DataCenter;
import com.cloud.deploy.DeployDestination;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.ManagementServerException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.network.IpAddressManager;
import com.cloud.network.Network.IpAddresses;
import com.cloud.network.Network;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.offering.ServiceOffering;
import com.cloud.uservm.UserVm;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.desktop.cluster.DesktopCluster;
import com.cloud.desktop.cluster.DesktopClusterVmMapVO;
import com.cloud.desktop.cluster.DesktopClusterManagerImpl;
import com.cloud.desktop.version.DesktopControllerVersion;
import com.cloud.vm.ReservationContext;
import com.cloud.vm.ReservationContextImpl;
import com.cloud.vm.VirtualMachine;

public class DesktopClusterStartWorker extends DesktopClusterResourceModifierActionWorker {

    private DesktopControllerVersion desktopClusterVersion;
    private IpAddressManager ipAddressManager;
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
        if (dcIp == null || network.getGuestType() == Network.GuestType.L2) {
            Network.IpAddresses addrs = new Network.IpAddresses(null, null, null);
            dcControlVm = userVmService.createAdvancedVirtualMachine(zone, serviceOffering, dcTemplate, networkIds, owner,
                hostName, hostName, null, null, null,
                dcTemplate.getHypervisorType(), BaseCmd.HTTPMethod.POST, null, null,
                null, addrs, null, null, null, customParameterMap, null, null, null, null, true);
        } else {
            ipToNetworkMap = new LinkedHashMap<Long, IpAddresses>();
            Network.IpAddresses addrs = new Network.IpAddresses(null, null, null);
            Network.IpAddresses dcAddrs = new Network.IpAddresses(dcIp, null, null);
            ipToNetworkMap.put(desktopCluster.getNetworkId(), dcAddrs);
            dcControlVm = userVmService.createAdvancedVirtualMachine(zone, serviceOffering, dcTemplate, networkIds, owner,
                hostName, hostName, null, null, null,
                dcTemplate.getHypervisorType(), BaseCmd.HTTPMethod.POST, null, null,
                ipToNetworkMap, addrs, null, null, null, customParameterMap, null, null, null, null, true);
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
        if (worksIp == null || network.getGuestType() == Network.GuestType.L2) {
            Network.IpAddresses addrs = new Network.IpAddresses(null, null, null);
            worksControlVm = userVmService.createAdvancedVirtualMachine(zone, serviceOffering, dcTemplate, networkIds, owner,
                hostName, hostName, null, null, null,
                worksTemplate.getHypervisorType(), BaseCmd.HTTPMethod.POST, null, null,
                null, addrs, null, null, null, customParameterMap, null, null, null, null, true);
        } else {
            ipToNetworkMap = new LinkedHashMap<Long, IpAddresses>();
            Network.IpAddresses addrs = new Network.IpAddresses(null, null, null);
            Network.IpAddresses worksAddrs = new Network.IpAddresses(worksIp, null, null);
            ipToNetworkMap.put(desktopCluster.getNetworkId(), worksAddrs);
            worksControlVm = userVmService.createAdvancedVirtualMachine(zone, serviceOffering, dcTemplate, networkIds, owner,
                hostName, hostName, null, null, null,
                worksTemplate.getHypervisorType(), BaseCmd.HTTPMethod.POST, null, null,
                ipToNetworkMap, addrs, null, null, null, customParameterMap, null, null, null, null, true);
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

    public boolean startDesktopClusterOnCreate() {
        init();
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info(String.format("Starting Desktop cluster : %s", desktopCluster.getName()));
        }
        final long startTimeoutTime = System.currentTimeMillis() + 3600 * 1000;
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
        List<UserVm> clusterVMs = new ArrayList<>();
        UserVm dcVM = null;
        try {
            dcVM = provisionDesktopClusterDcControlVm(network);
        } catch (CloudRuntimeException | ManagementServerException | ResourceUnavailableException | InsufficientCapacityException e) {
            logTransitStateAndThrow(Level.ERROR, String.format("Provisioning the DC Control VM failed in the desktop cluster : %s", desktopCluster.getName()), desktopCluster.getId(), DesktopCluster.Event.CreateFailed, e);
        }
        clusterVMs.add(dcVM);
        UserVm worksVM = null;
        try {
            worksVM = provisionDesktopClusterWorksControlVm(network);
        }  catch (CloudRuntimeException | ManagementServerException | ResourceUnavailableException | InsufficientCapacityException e) {
            logTransitStateAndThrow(Level.ERROR, String.format("Provisioning the Works Control VM failed in the desktop cluster : %s", desktopCluster.getName()), desktopCluster.getId(), DesktopCluster.Event.CreateFailed, e);
        }
        clusterVMs.add(worksVM);
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info(String.format("Desktop cluster : %s Control VMs successfully provisioned", desktopCluster.getName()));
        }
        stateTransitTo(desktopCluster.getId(), DesktopCluster.Event.OperationSucceeded);
        return true;
    }

    public boolean startStoppedDesktopCluster() throws CloudRuntimeException {
        init();
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info(String.format("Starting desktop cluster : %s", desktopCluster.getName()));
        }
        final long startTimeoutTime = System.currentTimeMillis() + 3600 * 1000;
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
        final long startTimeoutTime = System.currentTimeMillis() + 3 * 60 * 1000;
        List<DesktopClusterVmMapVO> vmMapVOList = getControlVMMaps();
        if (CollectionUtils.isEmpty(vmMapVOList)) {
            return false;
        }
        // mark the cluster to be running
        stateTransitTo(desktopCluster.getId(), DesktopCluster.Event.RecoveryRequested);
        stateTransitTo(desktopCluster.getId(), DesktopCluster.Event.OperationSucceeded);
        return true;
    }
}
