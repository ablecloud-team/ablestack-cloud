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

import org.apache.log4j.Level;

import com.cloud.exception.ConcurrentOperationException;
import com.cloud.ssv.SSV;
import com.cloud.ssv.SSVManagerImpl;
import com.cloud.ssv.SSVVmMapVO;
import com.cloud.uservm.UserVm;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VirtualMachine;

public class SSVStopWorker extends SSVActionWorker {
    public SSVStopWorker(final SSV ssv, final SSVManagerImpl appManager) {
        super(ssv, appManager);
    }

    public boolean stop() throws CloudRuntimeException {
        // init();
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info(String.format("Stopping Shared Storage VM  : %s", ssv.getName()));
        }
        stateTransitTo(ssv.getId(), SSV.Event.StopRequested);
        SSVVmMapVO vo = ssvVmMapDao.listVmBySSVServiceId(ssv.getId());
        UserVm vm = userVmDao.findById(vo.getVmId());
        if (vm == null) {
            logTransitStateAndThrow(Level.ERROR, String.format("Failed to Stop VMs in Shared Storage VM : %s", ssv.getName()), ssv.getId(), SSV.Event.OperationFailed);
        } else {
            try {
                userVmService.stopVirtualMachine(vm.getId(), false);
            } catch (ConcurrentOperationException ex) {
                LOGGER.warn(String.format("Failed to Stop VM : %s in Shared Storage VM  : %s due to ", vm.getDisplayName(), ssv.getName()) + ex);
                // dont bail out here. proceed further to stop the reset of the VM's
            }
            if (!vm.getState().equals(VirtualMachine.State.Running)) {
                logTransitStateAndThrow(Level.ERROR, String.format("Failed to Stop Control VMs in Shared Storage VM : %s", ssv.getName()), ssv.getId(), SSV.Event.OperationFailed);
            }
        }
        stateTransitTo(ssv.getId(), SSV.Event.OperationSucceeded);
        return true;
    }
}
