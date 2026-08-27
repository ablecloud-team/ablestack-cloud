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
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.uservm.UserVm;
import com.cloud.utils.StringUtils;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VirtualMachine;
import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.Level;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public class AutomationControllerStopWorker extends AutomationControllerActionWorker {
    private static final long AUTOMATION_CONTROLLER_STOP_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(10);
    private static final long AUTOMATION_CONTROLLER_STOP_RETRY_INTERVAL_MS = TimeUnit.SECONDS.toMillis(5);

    public AutomationControllerStopWorker(final AutomationController automationController, final AutomationControllerManagerImpl automationManager) {
        super(automationController, automationManager);
    }

    @Override
    protected String readResourceFile(String resource) throws IOException {
        return IOUtils.toString(Objects.requireNonNull(Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)), StringUtils.getPreferredCharset());
    }

    public boolean stop() throws CloudRuntimeException {
        init();
        if (logger.isInfoEnabled()) {
            logger.info(String.format("Stopping automation controller : %s", automationController.getName()));
        }
        stateTransitTo(automationController.getId(), AutomationController.Event.StopRequested);
        List<UserVm> automationControllerVMs = getAutomationControllerVMs();
        for (UserVm vm : automationControllerVMs) {
            if (vm == null) {
                logTransitStateAndThrow(Level.ERROR, String.format("Failed to find automation controller : %s", automationController.getName()), automationController.getId(), AutomationController.Event.OperationFailed);
            }
            try {
                userVmService.stopVirtualMachine(vm.getId(), false);
            } catch (ConcurrentOperationException ex) {
                logger.warn(String.format("Failed to stop VM : %s in automation controller : %s",
                    vm.getDisplayName(), automationController.getName()), ex);
            }
        }
        for (final UserVm userVm : automationControllerVMs) {
            if (!waitForVmStopped(userVm.getId(), AUTOMATION_CONTROLLER_STOP_TIMEOUT_MS,
                    AUTOMATION_CONTROLLER_STOP_RETRY_INTERVAL_MS)) {
                logTransitStateAndThrow(Level.ERROR, String.format("Failed to stop VMs in automation controller : %s",
                automationController.getName()), automationController.getId(), AutomationController.Event.OperationFailed);
            }
        }
        stateTransitTo(automationController.getId(), AutomationController.Event.OperationSucceeded);
        return true;
    }

    private boolean waitForVmStopped(long vmId, long timeoutMs, long retryIntervalMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            UserVm vm = userVmDao.findById(vmId);
            if (vm != null && vm.getState().equals(VirtualMachine.State.Stopped)) {
                return true;
            }

            if (logger.isDebugEnabled()) {
                String currentState = vm == null ? "null" : vm.getState().toString();
                logger.debug(String.format("VM %d for automation controller %s is not stopped yet (state=%s)",
                        vmId, automationController.getName(), currentState));
            }

            try {
                Thread.sleep(retryIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn(String.format("Interrupted while waiting for automation controller %s VM %d to stop",
                        automationController.getName(), vmId), e);
                return false;
            }
        }

        logger.warn(String.format("Timed out waiting for automation controller %s VM %d to stop after %d ms",
                automationController.getName(), vmId, timeoutMs));
        return false;
    }
}
