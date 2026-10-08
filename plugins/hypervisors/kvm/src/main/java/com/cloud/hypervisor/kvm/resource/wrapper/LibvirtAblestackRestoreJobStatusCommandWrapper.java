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

package com.cloud.hypervisor.kvm.resource.wrapper;

import com.cloud.agent.api.Answer;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;

import org.apache.cloudstack.backup.AblestackBackupFrameworkUtils;
import org.apache.cloudstack.backup.AblestackRestoreJobStatusCommand;
import org.apache.cloudstack.backup.BackupAnswer;

@ResourceWrapper(handles = AblestackRestoreJobStatusCommand.class)
public class LibvirtAblestackRestoreJobStatusCommandWrapper
        extends CommandWrapper<AblestackRestoreJobStatusCommand, Answer, LibvirtComputingResource> {

    @Override
    public Answer execute(final AblestackRestoreJobStatusCommand command, final LibvirtComputingResource resource) {
        try {
            String failure = LibvirtAblestackVolumeRestoreHelper.startFailure(command.getRestoreJobId());
            if (failure != null) {
                BackupAnswer failed = new BackupAnswer(command, true, failure);
                failed.setState("FAILED");
                failed.setStep("START_FAILED");
                failed.setProgress(0);
                failed.setOperation(AblestackBackupFrameworkUtils.OPERATION_RESTORE);
                return failed;
            }
        } catch (Exception e) {
            return new BackupAnswer(command, false, "Restore start proof cannot be read: " + e.getMessage());
        }
        LibvirtAblestackAsyncBackupRunner.recoverFailedRestoreTransaction(command.getRestoreJobId(), resource.getStoragePoolMgr(), logger);
        BackupAnswer answer = LibvirtAblestackAsyncBackupRunner.getJobStatus(command, command.getRestoreJobId(),
                command.getEventsOffset(), command.getEventsLimit(), logger);
        try {
            var result = LibvirtAblestackRestoreOutcome.read(command.getRestoreJobId());
            if (result != null) { answer.setVmRestoreResult(new com.google.gson.Gson().toJson(result)); }
        } catch (Exception e) { answer.setVmRestoreResultError("VM volume transaction result cannot be read: " + e.getMessage()); }
        logger.debug("ABLESTACK restore job status command completed. restoreJobId=[{}], state=[{}], jobLog=[{}]",
                command.getRestoreJobId(), answer.getState(), AblestackBackupFrameworkUtils.getAsyncRestoreJobLogPath(command.getRestoreJobId()));
        return answer;
    }
}
