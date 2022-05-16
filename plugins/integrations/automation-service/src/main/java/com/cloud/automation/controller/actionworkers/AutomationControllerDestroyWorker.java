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
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.ManagementServerException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.network.IpAddress;
import com.cloud.network.Network;
import com.cloud.network.dao.NetworkVO;
import com.cloud.server.ResourceTag;
import com.cloud.server.ResourceTag.ResourceObjectType;
import com.cloud.tags.dao.ResourceTagDao;
import com.cloud.user.AccountManager;
import com.cloud.uservm.UserVm;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VMInstanceVO;
import org.apache.cloudstack.context.CallContext;
import org.apache.commons.collections.CollectionUtils;
import org.apache.log4j.Level;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.List;

public class AutomationControllerDestroyWorker extends AutomationControllerActionWorker {

    @Inject
    protected AccountManager accountManager;
    @Inject
    protected ResourceTagDao resourceTagDao;

    private List<AutomationControllerVmMapVO> clusterVMs;

    public AutomationControllerDestroyWorker(final AutomationController automationController, final AutomationControllerManagerImpl clusterManager) {
        super(automationController, clusterManager);
    }

    private void validateClusterState() {
        if (!(automationController.getState().equals(AutomationController.State.Running)
                || automationController.getState().equals(AutomationController.State.Stopped)
                || automationController.getState().equals(AutomationController.State.Alert)
                || automationController.getState().equals(AutomationController.State.Error)
                || automationController.getState().equals(AutomationController.State.Destroying))) {
            String msg = String.format("Cannot perform delete operation on cluster : %s in state: %s",
            automationController.getName(), automationController.getState());
            LOGGER.warn(msg);
            throw new PermissionDeniedException(msg);
        }
    }

    private boolean destroyClusterVMs() {
        boolean vmDestroyed = true;
        //ControlVM removed
        if (!CollectionUtils.isEmpty(clusterVMs)) {
            for (AutomationControllerVmMapVO clusterVM : clusterVMs) {
                long vmID = clusterVM.getVmId();

                // delete only if VM exists and is not removed
                UserVmVO userVM = userVmDao.findById(vmID);
                if (userVM == null || userVM.isRemoved()) {
                    continue;
                }
                try {
                    UserVm vm = userVmService.destroyVm(vmID, true);
                    if (!userVmManager.expunge(userVM, CallContext.current().getCallingUserId(), CallContext.current().getCallingAccount())) {
                        LOGGER.warn(String.format("Unable to expunge VM %s : %s, destroying automation controller will probably fail",
                            vm.getInstanceName() , vm.getUuid()));
                    }
                    automationControllerVmMapDao.expunge(clusterVM.getId());
                    if (LOGGER.isInfoEnabled()) {
                        LOGGER.info(String.format("Destroyed VM : %s as part of automation controller : %s cleanup", vm.getDisplayName(), automationController.getName()));
                    }
                } catch (ResourceUnavailableException | ConcurrentOperationException e) {
                    LOGGER.warn(String.format("Failed to destroy VM : %s part of the automation controller : %s cleanup. Moving on with destroying remaining resources provisioned for the automation controller", userVM.getDisplayName(), automationController.getName()), e);
                    return false;
                }
            }
            //DesktopVM removed
            List<VMInstanceVO> vmList = vmInstanceDao.listByZoneId(automationController.getZoneId());
            String resourceKey = "ClusterName";
            if (vmList != null && !vmList.isEmpty()) {
                for (VMInstanceVO vmVO : vmList) {
                    ResourceTag desktopvm = resourceTagDao.findByKey(vmVO.getId(), ResourceObjectType.UserVm, resourceKey);
                    if (desktopvm != null) {
                        if (desktopvm.getValue().equals(automationController.getName())) {
                            long desktopvmID = vmVO.getId();
                            // delete only if VM exists and is not removed
                            UserVmVO userDesktopVM = userVmDao.findById(desktopvmID);
                            if (userDesktopVM == null || userDesktopVM.isRemoved()) {
                                continue;
                            }
                            try {
                                UserVm deskvm = userVmService.destroyVm(desktopvmID, true);
                                if (!userVmManager.expunge(userDesktopVM, CallContext.current().getCallingUserId(), CallContext.current().getCallingAccount())) {
                                    LOGGER.warn(String.format("Unable to expunge VM %s : %s, Destroying a desktop virtual machine in a automation controller will probably fail",
                                    deskvm.getInstanceName() , deskvm.getUuid()));
                                }
                                if (LOGGER.isInfoEnabled()) {
                                    LOGGER.info(String.format("Destroyed VM : %s as part of automation controller : %s desktop virtual machine cleanup", deskvm.getDisplayName(), automationController.getName()));
                                }
                            } catch (ResourceUnavailableException | ConcurrentOperationException e) {
                                LOGGER.warn(String.format("Failed to destroy VM : %s part of the automation controller : %s desktop virtual machine cleanup. Moving on with destroying remaining resources provisioned for the automation controller", userDesktopVM.getDisplayName(), automationController.getName()), e);
                                return false;
                            }
                        }
                    }
                }
            }
        }
        return vmDestroyed;
    }

    private boolean updateAutomationControllerEntryForGC() {
        AutomationControllerVO automationControllerVO = automationControllerDao.findById(automationController.getId());
//        automationControllerVO.setCheckForGc(true);
        return automationControllerDao.update(automationController.getId(), automationControllerVO);
    }


    private void deleteAutomationControllerNetworkRules() throws ManagementServerException {
        NetworkVO network = networkDao.findById(automationController.getNetworkId());
        if (network == null) {
            return;
        }
        List<Long> removedVmIds = new ArrayList<>();
        if (!CollectionUtils.isEmpty(clusterVMs)) {
            for (AutomationControllerVmMapVO clusterVM : clusterVMs) {
                removedVmIds.add(clusterVM.getVmId());
            }
        }
        IpAddress publicIp = getSourceNatIp(network);
        if (publicIp == null) {
            throw new ManagementServerException(String.format("No source NAT IP addresses found for network : %s", network.getName()));
        }
        removeFirewallIngressRule(publicIp);
        removeFirewallEgressRule(network);
        try {
            removePortForwardingRules(publicIp, network, owner, removedVmIds);
        } catch (ResourceUnavailableException e) {
            // throw new ManagementServerException(String.format("Failed to automation controller port forwarding rules for network : %s", network.getName()));
        }
    }

    private void validateClusterVMsDestroyed() {
        if(clusterVMs!=null  && !clusterVMs.isEmpty()) { // Wait for few seconds to get all VMs really expunged
            final int maxRetries = 3;
            int retryCounter = 0;
            while (retryCounter < maxRetries) {
                boolean allVMsRemoved = true;
                for (AutomationControllerVmMap clusterVM : clusterVMs) {
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
        NetworkVO automationControllerNetwork = networkDao.findById(automationController.getNetworkId());
        if (automationControllerNetwork != null && automationControllerNetwork.getGuestType() != Network.GuestType.Shared) {
            deleteAutomationControllerNetworkRules();
        }
    }

//    private boolean destroyClusterIps() {
//        boolean ipDestroyed = true;
//        List<AutomationControllerIpRangeVO> ipRangeList = automationControllerIpRangeDao.listByAutomationControllerId(automationController.getId());
//            for (AutomationControllerIpRangeVO iprange : ipRangeList) {
//                boolean deletedIp = automationControllerIpRangeDao.remove(iprange.getId());
//                if (!deletedIp) {
//                    logMessage(Level.WARN, String.format("Failed to delete automation controller ip range : %s", automationController.getName()), null);
//                    return false;
//                }
//                if (LOGGER.isInfoEnabled()) {
//                    LOGGER.info(String.format("Automation Controller ip range : %s is successfully deleted", automationController.getName()));
//                }
//            }
//        return ipDestroyed;
//    }

    public boolean destroy() throws CloudRuntimeException {
        init();
        validateClusterState();
        this.clusterVMs = automationControllerVmMapDao.listByAutomationControllerId(automationController.getId());
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info(String.format("Destroying automation controller : %s", automationController.getName()));
        }
        stateTransitTo(automationController.getId(), AutomationController.Event.DestroyRequested);
        boolean vmsDestroyed = destroyClusterVMs();
        // if there are VM's that were not expunged, we can not delete the network
        if (vmsDestroyed) {
            validateClusterVMsDestroyed();
            try {
                checkForRulesToDelete();
            } catch (ManagementServerException e) {
                String msg = String.format("Failed to remove network rules of automation controller : %s", automationController.getName());
                LOGGER.warn(msg, e);
                updateAutomationControllerEntryForGC();
                throw new CloudRuntimeException(msg, e);
            }
        } else {
            String msg = String.format("Failed to destroy one or more VMs as part of automation controller : %s cleanup",automationController.getName());
            LOGGER.warn(msg);
            updateAutomationControllerEntryForGC();
            throw new CloudRuntimeException(msg);
        }
        stateTransitTo(automationController.getId(), AutomationController.Event.OperationSucceeded);
        final String accessType = "internal";
        // Automation Controller IP Range remove
//        if (automationController.getAccessType().equals(accessType)) {
//            boolean ipDestroyed = destroyClusterIps();
//        }
        boolean deleted = automationControllerDao.remove(automationController.getId());
        if (!deleted) {
            logMessage(Level.WARN, String.format("Failed to delete automation controller : %s", automationController.getName()), null);
            updateAutomationControllerEntryForGC();
            return false;
        }
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info(String.format("Automation Controller : %s is successfully deleted", automationController.getName()));
        }
        return true;
    }
}
