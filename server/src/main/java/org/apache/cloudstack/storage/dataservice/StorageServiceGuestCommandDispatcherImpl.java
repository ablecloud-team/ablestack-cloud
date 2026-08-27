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
// specific language govening permissions and limitations
// under the License.

package org.apache.cloudstack.storage.dataservice;

import javax.inject.Inject;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.StorageServiceHostAnswer;
import com.cloud.agent.api.StorageServiceHostCommand;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.dao.VMInstanceDao;

public class StorageServiceGuestCommandDispatcherImpl implements StorageServiceGuestCommandDispatcher {
    @Inject
    private AgentManager agentManager;
    @Inject
    private VMInstanceDao vmInstanceDao;

    @Override
    public StorageServiceGuestCommandResult dispatch(StorageServiceGuestCommand command) {
        VMInstanceVO vm = vmInstanceDao.findById(command.getVmId());
        if (vm == null) {
            throw new CloudRuntimeException("Unable to find Storage Service System VM with id " + command.getVmId());
        }

        Long hostId = vm.getHostId();
        if (hostId == null) {
            throw new CloudRuntimeException("Storage Service System VM is not running on a host: " + vm.getInstanceName());
        }

        StorageServiceHostCommand hostCommand = new StorageServiceHostCommand(vm.getInstanceName(), command.getOperation(),
                command.getPayload(), command.getTimeoutSeconds(), command.getMaskedFields());
        Answer answer = agentManager.easySend(hostId, hostCommand);
        if (answer == null) {
            throw new CloudRuntimeException("No response from host agent for Storage Service System VM: " + vm.getInstanceName());
        }
        if (!(answer instanceof StorageServiceHostAnswer)) {
            throw new CloudRuntimeException("Unexpected Storage Service host answer: " + answer.getClass().getName());
        }

        StorageServiceHostAnswer storageAnswer = (StorageServiceHostAnswer) answer;
        return new StorageServiceGuestCommandResult(storageAnswer.getResult(), storageAnswer.getDetails(), storageAnswer.getResultJson());
    }
}
