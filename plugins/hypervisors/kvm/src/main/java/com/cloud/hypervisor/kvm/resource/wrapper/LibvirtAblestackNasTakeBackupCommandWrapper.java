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
import org.apache.cloudstack.backup.AblestackNasTakeBackupCommand;

import java.util.List;

@ResourceWrapper(handles = AblestackNasTakeBackupCommand.class)
public class LibvirtAblestackNasTakeBackupCommandWrapper extends CommandWrapper<AblestackNasTakeBackupCommand, Answer, LibvirtComputingResource> {
    private static final String BACKUP_TRACE = AblestackBackupFrameworkUtils.buildTracePrefix("nas", AblestackBackupFrameworkUtils.OPERATION_BACKUP);

    @Override
    public Answer execute(AblestackNasTakeBackupCommand command, LibvirtComputingResource libvirtComputingResource) {
        LibvirtAblestackNasBackupHelper backupHelper = new LibvirtAblestackNasBackupHelper(libvirtComputingResource);
        List<String> diskPaths = backupHelper.resolveDiskPaths(command.getVolumePools(), command.getVolumePaths());
        return LibvirtAblestackTakeBackupCommandHelper.execute(command, logger, BACKUP_TRACE, "NAS",
                new LibvirtAblestackTakeBackupCommandHelper.BackupCommandContext(command.getBackupJobId(), command.getVmName(),
                        command.getBackupPath(), command.getBackupType(), command.isWaitForCompletion()),
                () -> backupHelper.buildDetachedBackupScriptCommand(command),
                () -> backupHelper.executeBackup(command),
                LibvirtAblestackNasBackupHelper.EXIT_CLEANUP_FAILED,
                "NAS backup helper returned failure without details",
                true,
                result -> backupHelper.parseBackupSize(result.second(), diskPaths));
    }
}
