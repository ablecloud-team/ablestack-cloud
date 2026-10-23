// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.hypervisor.kvm.resource.wrapper;

import com.cloud.agent.api.Answer;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;
import org.apache.cloudstack.backup.BackupRestoreAllocationCommand;
import org.apache.cloudstack.backup.BackupRestoreAllocationAnswer;
import org.apache.cloudstack.backup.BackupVolumeAllocation;

@ResourceWrapper(handles = BackupRestoreAllocationCommand.class)
public class LibvirtBackupRestoreAllocationCommandWrapper extends CommandWrapper<BackupRestoreAllocationCommand, Answer, LibvirtComputingResource> {
    @Override public Answer execute(BackupRestoreAllocationCommand command, LibvirtComputingResource resource) {
        try {
            BackupVolumeAllocation.Result result;
            if ("CHECK".equals(command.getAction())) {
                if (command.getPolicies() == null || command.getPolicies().isEmpty()) throw new IllegalArgumentException("Missing allocation policies");
                LibvirtAblestackRestoreAllocation.check(resource.getStoragePoolMgr(), command.getPolicies());
                result = new BackupVolumeAllocation.Result();
                result.jobId = command.getJobId(); result.policies = command.getPolicies();
            } else if ("RESULT".equals(command.getAction())) {
                result = LibvirtAblestackRestoreAllocation.read(command.getJobId());
            } else throw new IllegalArgumentException("Invalid allocation action");
            return new BackupRestoreAllocationAnswer(command, result);
        } catch (Exception failure) {
            return new Answer(command, false, "BACKUP_ALLOCATION_UNCONFIRMED: " + failure.getMessage());
        }
    }
}
