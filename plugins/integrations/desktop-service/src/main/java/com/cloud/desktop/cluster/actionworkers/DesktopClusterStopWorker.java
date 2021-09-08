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

import java.util.List;

import org.apache.log4j.Level;

import com.cloud.exception.ConcurrentOperationException;
import com.cloud.desktop.cluster.DesktopCluster;
import com.cloud.desktop.cluster.DesktopClusterManagerImpl;
import com.cloud.uservm.UserVm;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VirtualMachine;

public class DesktopClusterStopWorker extends DesktopClusterActionWorker {
    public DesktopClusterStopWorker(final DesktopCluster desktopCluster, final DesktopClusterManagerImpl clusterManager) {
        super(desktopCluster, clusterManager);
    }

    public boolean stop() throws CloudRuntimeException {
        init();
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info(String.format("Stopping desktop cluster : %s", desktopCluster.getName()));
        }
        stateTransitTo(desktopCluster.getId(), DesktopCluster.Event.StopRequested);
        List<UserVm> clusterVMs = getControlVMs();
        for (UserVm vm : clusterVMs) {
            if (vm == null) {
                logTransitStateAndThrow(Level.ERROR, String.format("Failed to find Control VMs in desktop cluster : %s", desktopCluster.getName()), desktopCluster.getId(), DesktopCluster.Event.OperationFailed);
            }
            try {
                userVmService.stopVirtualMachine(vm.getId(), false);
            } catch (ConcurrentOperationException ex) {
                LOGGER.warn(String.format("Failed to stop VM : %s in desktop cluster : %s",
                    vm.getDisplayName(), desktopCluster.getName()), ex);
            }
        }
        for (final UserVm userVm : clusterVMs) {
            UserVm vm = userVmDao.findById(userVm.getId());
            if (vm == null || !vm.getState().equals(VirtualMachine.State.Stopped)) {
                logTransitStateAndThrow(Level.ERROR, String.format("Failed to stop Control VMs in desktop cluster : %s",
                desktopCluster.getName()), desktopCluster.getId(), DesktopCluster.Event.OperationFailed);
            }
        }
        stateTransitTo(desktopCluster.getId(), DesktopCluster.Event.OperationSucceeded);
        return true;
    }
}
