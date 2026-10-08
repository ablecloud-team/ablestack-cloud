//
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
//

package com.cloud.hypervisor.kvm.resource.wrapper;

import com.cloud.agent.api.Answer;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;
import org.apache.cloudstack.backup.AblestackBackupFrameworkUtils;
import org.apache.cloudstack.backup.AblestackCommvaultTakeBackupCommand;

@ResourceWrapper(handles = AblestackCommvaultTakeBackupCommand.class)
public class LibvirtAblestackCommvaultTakeBackupCommandWrapper extends CommandWrapper<AblestackCommvaultTakeBackupCommand, Answer, LibvirtComputingResource> {
    private static final String BACKUP_TRACE = AblestackBackupFrameworkUtils.buildTracePrefix("commvault", AblestackBackupFrameworkUtils.OPERATION_BACKUP);

    @Override
    public Answer execute(AblestackCommvaultTakeBackupCommand command, LibvirtComputingResource libvirtComputingResource) {
        LibvirtAblestackCommvaultBackupHelper backupHelper = new LibvirtAblestackCommvaultBackupHelper(libvirtComputingResource);
        return LibvirtAblestackTakeBackupCommandHelper.execute(command, logger, BACKUP_TRACE, "Commvault",
                new LibvirtAblestackTakeBackupCommandHelper.BackupCommandContext(command.getBackupJobId(), command.getVmName(),
                        command.getBackupPath(), command.getBackupType(), command.isWaitForCompletion()),
                () -> backupHelper.buildDetachedBackupScriptCommand(command),
                () -> backupHelper.executeBackup(command),
                LibvirtAblestackCommvaultBackupHelper.EXIT_CLEANUP_FAILED,
                "Commvault backup helper returned failure without details",
                false,
                null);
    }
}
