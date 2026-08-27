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
import com.cloud.automation.controller.AutomationControllerVmMap;
import com.cloud.automation.controller.AutomationControllerVmMapVO;
import com.cloud.automation.resource.AutomationDeployedResourceVO;
import com.cloud.exception.ManagementServerException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.network.IpAddress;
import com.cloud.network.Network;
import com.cloud.network.dao.NetworkVO;
import com.cloud.uservm.UserVm;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.UserVmVO;
import org.apache.commons.collections.CollectionUtils;

import java.util.ArrayList;
import java.util.List;

public class AutomationControllerDestroyWorker extends AutomationControllerActionWorker {

    private List<AutomationControllerVmMapVO> automationControllerVMs;

    public AutomationControllerDestroyWorker(final AutomationController automationController, final AutomationControllerManagerImpl clusterManager) {
        super(automationController, clusterManager);
    }

    private void validateControllerState() {
        if (AutomationController.State.Enabled.equals(automationController.getState())) {
            String msg = String.format("Cannot perform delete operation on controller : %s in state: %s",
            automationController.getName(), automationController.getState());
            logger.warn(msg);
            throw new PermissionDeniedException(msg);
        }
    }

    private void validateDeployedPackages() {
        final Long automationControllerId = automationController.getId();
        final List<AutomationDeployedResourceVO> serviceGroupList = automationDeployedResourceDao.listAll();
        for (final AutomationDeployedResourceVO serviceGroup : serviceGroupList) {
            if(serviceGroup.getControllerId() == (automationControllerId)){
                String msg = String.format("Cannot perform delete operation. Packages deployed to that controller should be deleted.",
                        automationController.getName(), automationController.getState());
                logger.warn(msg);
                throw new PermissionDeniedException(msg);
            }
        }
    }

    private boolean destroyAutomationControllerVMs() {
        boolean allVmsDestroyed = true;
        if (!CollectionUtils.isEmpty(automationControllerVMs)) {
            for (AutomationControllerVmMapVO automationControllerVM : automationControllerVMs) {
                long vmID = automationControllerVM.getVmId();

                UserVmVO userVM = userVmDao.findById(vmID);
                if (userVM == null || userVM.isRemoved()) {
                    automationControllerVmMapDao.expunge(automationControllerVM.getId());
                    continue;
                }
                try {
                    UserVm vm = userVmService.destroyVm(vmID, true);
                    if (!userVmManager.expunge(userVM)) {
                        logger.warn(String.format("Unable to expunge VM %s while destroying automation controller %s",
                                userVM.getUuid(), automationController.getName()));
                        allVmsDestroyed = false;
                        continue;
                    }
                    automationControllerVmMapDao.expunge(automationControllerVM.getId());
                    if (logger.isInfoEnabled()) {
                        String vmName = vm == null ? userVM.getDisplayName() : vm.getDisplayName();
                        logger.info(String.format("Destroyed VM : %s as part of automation controller : %s cleanup", vmName, automationController.getName()));
                    }
                } catch (ResourceUnavailableException | CloudRuntimeException e) {
                    logger.warn(String.format("Failed to destroy VM : %s as part of automation controller : %s cleanup",
                            userVM.getDisplayName(), automationController.getName()), e);
                    allVmsDestroyed = false;
                }
            }
        }
        return allVmsDestroyed;
    }


    private void deleteAutomationControllerNetworkRules() throws ManagementServerException {
        NetworkVO network = networkDao.findById(automationController.getNetworkId());
        if (network == null) {
            return;
        }
        List<Long> removedVmIds = new ArrayList<>();
        if (!CollectionUtils.isEmpty(automationControllerVMs)) {
            for (AutomationControllerVmMapVO automationControllerVM : automationControllerVMs) {
                removedVmIds.add(automationControllerVM.getVmId());
            }
        }
        IpAddress publicIp = getSourceNatIp(network);
        if (publicIp == null) {
            logger.warn(String.format("Source NAT IP for network %s is already absent; skipping automation controller network-rule cleanup",
                    network.getName()));
            return;
        }
        removeFirewallIngressRule(publicIp);
        removeFirewallEgressRule(network);
        try {
            removePortForwardingRules(publicIp, network, owner, removedVmIds);
        } catch (ResourceUnavailableException e) {
            throw new ManagementServerException(String.format("Failed to remove automation controller port forwarding rules for network : %s", network.getName()), e);
        }
    }

    private boolean validateControllerVMsDestroyed() {
        if (automationControllerVMs != null && !automationControllerVMs.isEmpty()) {
            final int maxRetries = 3;
            int retryCounter = 0;
            while (retryCounter < maxRetries) {
                boolean allVMsRemoved = true;
                for (AutomationControllerVmMap automationControllerVM : automationControllerVMs) {
                    UserVmVO userVM = userVmDao.findById(automationControllerVM.getVmId());
                    if (userVM != null && !userVM.isRemoved()) {
                        allVMsRemoved = false;
                        break;
                    }
                }
                if (allVMsRemoved) {
                    return true;
                }
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                retryCounter++;
            }
        }
        return CollectionUtils.isEmpty(automationControllerVMs);
    }

    private void checkForRulesToDelete() throws ManagementServerException {
        NetworkVO automationControllerNetwork = networkDao.findById(automationController.getNetworkId());
        if (automationControllerNetwork != null && automationControllerNetwork.getGuestType() != Network.GuestType.Shared) {
            deleteAutomationControllerNetworkRules();
        }
    }

    public boolean destroy() throws CloudRuntimeException {
        init();
        validateControllerState();
        validateDeployedPackages();
        this.automationControllerVMs = automationControllerVmMapDao.listByAutomationControllerId(automationController.getId());
        if (logger.isInfoEnabled()) {
            logger.info(String.format("Destroying automation controller : %s", automationController.getName()));
        }
        if (!AutomationController.State.Destroyed.equals(automationController.getState())
                && !stateTransitTo(automationController.getId(), AutomationController.Event.DestroyRequested)) {
            throw new CloudRuntimeException(String.format("Failed to move automation controller %s into Destroying state",
                    automationController.getName()));
        }
        boolean vmsDestroyed = destroyAutomationControllerVMs();
        if (!vmsDestroyed || !validateControllerVMsDestroyed()) {
            String msg = String.format("Failed to destroy one or more VMs as part of automation controller : %s cleanup",automationController.getName());
            logger.warn(msg);
            throw new CloudRuntimeException(msg);
        }
        try {
            checkForRulesToDelete();
        } catch (ManagementServerException | CloudRuntimeException e) {
            logger.warn(String.format("Failed to remove one or more network rules of automation controller %s; continuing controller cleanup",
                    automationController.getName()), e);
        }
        if (!AutomationController.State.Destroyed.equals(automationController.getState())
                && !stateTransitTo(automationController.getId(), AutomationController.Event.OperationSucceeded)) {
            throw new CloudRuntimeException(String.format("Failed to mark automation controller %s as Destroyed",
                    automationController.getName()));
        }
        boolean deleted = automationControllerDao.remove(automationController.getId());
        if (!deleted) {
            throw new CloudRuntimeException(String.format("Failed to delete automation controller : %s. The delete operation can be retried.",
                    automationController.getName()));
        }
        if (logger.isInfoEnabled()) {
            logger.info(String.format("Automation Controller : %s is successfully deleted", automationController.getName()));
        }
        return true;
    }
}
