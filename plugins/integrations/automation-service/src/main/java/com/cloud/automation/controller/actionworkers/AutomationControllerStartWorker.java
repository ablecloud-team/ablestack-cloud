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

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Objects;
import java.util.List;

import com.cloud.deploy.DeployDestination;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.ManagementServerException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.network.IpAddress;
import com.cloud.utils.component.ComponentContext;
import com.cloud.utils.exception.ExecutionException;
import org.apache.cloudstack.api.command.user.vm.StartVMCmd;
import org.apache.commons.io.IOUtils;
import org.apache.log4j.Level;

import com.cloud.uservm.UserVm;
import com.cloud.utils.StringUtils;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.automation.controller.AutomationController;
import com.cloud.automation.controller.AutomationControllerManagerImpl;
import com.cloud.vm.VirtualMachine;

public class AutomationControllerStartWorker extends AutomationControllerActionWorker {
    public AutomationControllerStartWorker(final AutomationController automationController, final AutomationControllerManagerImpl automationManager) {
        super(automationController, automationManager);
    }

    @Override
    protected String readResourceFile(String resource) throws IOException {
        return IOUtils.toString(Objects.requireNonNull(Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)), StringUtils.getPreferredCharset());
    }

    private void startAutomationControllerVMs() {
        List <UserVm> clusterVms = getControlVMs();
        for (final UserVm vm : clusterVms) {
            if (vm == null) {
                logTransitStateAndThrow(Level.ERROR, String.format("Failed to start Control VMs in automation controller : %s", automationController.getName()), automationController.getId(), AutomationController.Event.OperationFailed);
            }
            try {
                startDesktopVM(vm);
            } catch (ManagementServerException ex) {
                LOGGER.warn(String.format("Failed to start VM : %s in automation controller : %s due to ", vm.getDisplayName(), automationController.getName()) + ex);
                // dont bail out here. proceed further to stop the reset of the VM's
            }
        }
        for (final UserVm userVm : clusterVms) {
            UserVm vm = userVmDao.findById(userVm.getId());
            if (vm == null || !vm.getState().equals(VirtualMachine.State.Running)) {
                logTransitStateAndThrow(Level.ERROR, String.format("Failed to start Control VMs in automation controller : %s", automationController.getName()), automationController.getId(), AutomationController.Event.OperationFailed);
            }
        }
    }

    protected void startDesktopVM(final UserVm vm) throws ManagementServerException {
        try {
            StartVMCmd startVm = new StartVMCmd();
            startVm = ComponentContext.inject(startVm);
            Field f = startVm.getClass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(startVm, vm.getId());
            userVmService.startVirtualMachine(startVm);
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info(String.format("Started VM : %s in the automation controller : %s", vm.getDisplayName(), automationController.getName()));
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

    public boolean startAutomationControllerOnCreate() {
        init();
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info(String.format("Starting Automation Controller : %s", automationController.getName()));
        }
        stateTransitTo(automationController.getId(), AutomationController.Event.StartRequested);
        DeployDestination dest = null;
        try {
            dest = plan();
        } catch (InsufficientCapacityException e) {
            logTransitStateAndThrow(Level.ERROR, String.format("Provisioning the cluster failed due to insufficient capacity in the automation controller: %s", automationController.getUuid()), automationController.getId(), AutomationController.Event.CreateFailed, e);
        }
//        Network network = null;
//        try {
//            network = startAutomationControllerNetwork(dest);
//        } catch (ManagementServerException e) {
//            logTransitStateAndThrow(Level.ERROR, String.format("Failed to start automation controller : %s as its network cannot be started", automationController.getName()), automationController.getId(), AutomationController.Event.CreateFailed, e);
//        }
        IpAddress publicIpAddress = null;
        publicIpAddress = getAutomationControllerServerIp();
        if (publicIpAddress == null) {
            logTransitStateAndThrow(Level.ERROR, String.format("Failed to start Automation Controller : %s as no public IP found for the cluster" , automationController.getName()), automationController.getId(), AutomationController.Event.CreateFailed);
        }
        List<UserVm> clusterVMs = new ArrayList<>();
        UserVm worksVM = null;
//        try {
//            worksVM = provisionAutomationControllerWorksControlVm(network);
//        }  catch (CloudRuntimeException | ManagementServerException | ResourceUnavailableException | InsufficientCapacityException e) {
//            logTransitStateAndThrow(Level.ERROR, String.format("Provisioning the Works Control VM failed in the automation controller : %s, %s", automationController.getName(), e), automationController.getId(), AutomationController.Event.CreateFailed, e);
//        }
        clusterVMs.add(worksVM);
//        if (worksVM.getState().equals(VirtualMachine.State.Running)) {
//            boolean setup = false;
//            try {
//                setup = setupAutomationControllerNetworkRules(network, worksVM, publicIpAddress);
//            } catch (ManagementServerException e) {
//                logTransitStateAndThrow(Level.ERROR, String.format("Failed to setup Automation Controller : %s, unable to setup network rules", automationController.getName()), automationController.getId(), AutomationController.Event.CreateFailed, e);
//            }
//            if (setup) {
//                try {
//                    if (callApi(publicIpAddress.getAddress().addr())) {
//                        UserVm dcVM = null;
//                        try {
//                            dcVM = provisionAutomationControllerDcControlVm(network);
//                        } catch (CloudRuntimeException | ManagementServerException | ResourceUnavailableException | InsufficientCapacityException e) {
//                            logTransitStateAndThrow(Level.ERROR, String.format("Provisioning the DC Control VM failed in the automation controller : %s, %s", automationController.getName(), e), automationController.getId(), AutomationController.Event.CreateFailed, e);
//                        }
//                        clusterVMs.add(dcVM);
//                        if (LOGGER.isInfoEnabled()) {
//                            LOGGER.info(String.format("Automation Controller : %s Control VMs successfully provisioned", automationController.getName()));
//                        }
//                        stateTransitTo(automationController.getId(), AutomationController.Event.OperationSucceeded);
//                        return true;
//                    }
//                } catch (IOException | InterruptedException e) {
//                    logTransitStateAndThrow(Level.ERROR, String.format("Provisioning failed in the automation controller : %s, %s", automationController.getName(), e), automationController.getId(), AutomationController.Event.CreateFailed, e);
//                }
//            }
//        }
        return false;
    }

    public boolean startStoppedAutomationController() throws CloudRuntimeException {
        init();
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info(String.format("Starting automation controller : %s", automationController.getName()));
        }
        stateTransitTo(automationController.getId(), AutomationController.Event.StartRequested);
        startAutomationControllerVMs();
        stateTransitTo(automationController.getId(), AutomationController.Event.OperationSucceeded);
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info(String.format("Automation Controller : %s successfully started", automationController.getName()));
        }
        return true;
    }
}