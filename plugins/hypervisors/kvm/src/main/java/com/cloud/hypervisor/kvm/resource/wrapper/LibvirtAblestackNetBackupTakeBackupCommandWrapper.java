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
import org.apache.cloudstack.backup.AblestackNetBackupTakeBackupCommand;

@ResourceWrapper(handles = AblestackNetBackupTakeBackupCommand.class)
public class LibvirtAblestackNetBackupTakeBackupCommandWrapper extends CommandWrapper<AblestackNetBackupTakeBackupCommand, Answer, LibvirtComputingResource> {
    private static final String BACKUP_TRACE = AblestackBackupFrameworkUtils.buildTracePrefix("netbackup", AblestackBackupFrameworkUtils.OPERATION_BACKUP);

    @Override
    public Answer execute(final AblestackNetBackupTakeBackupCommand command, final LibvirtComputingResource libvirtComputingResource) {
        final AblestackNetBackupTakeBackupCommand delegate = new AblestackNetBackupTakeBackupCommand(command.getVmName(), command.getBackupPath());
        delegate.setWait(command.getWait());
        delegate.setQuiesce(command.getQuiesce());
        delegate.setVolumePools(command.getVolumePools());
        delegate.setVolumePaths(command.getVolumePaths());
        delegate.setBackupType(command.getBackupType());
        delegate.setCheckpointName(command.getCheckpointName());
        delegate.setParentBackupPath(command.getParentBackupPath());
        delegate.setParentCheckpointName(command.getParentCheckpointName());
        delegate.setParentCheckpointPath(command.getParentCheckpointPath());
        delegate.setParentCheckpointXml(command.getParentCheckpointXml());
        delegate.setParentCheckpointXmlChain(command.getParentCheckpointXmlChain());
        delegate.setBackupFiles(command.getBackupFiles());
        delegate.setWaitForCompletion(command.isWaitForCompletion());
        delegate.setBackupJobId(command.getBackupJobId());
        delegate.setBandwidthLimitMbps(command.getBandwidthLimitMbps());

        final LibvirtAblestackNetBackupHelper backupHelper = new LibvirtAblestackNetBackupHelper(libvirtComputingResource);
        return LibvirtAblestackTakeBackupCommandHelper.execute(command, logger, BACKUP_TRACE, "NetBackup",
                new LibvirtAblestackTakeBackupCommandHelper.BackupCommandContext(command.getBackupJobId(), command.getVmName(),
                        command.getBackupPath(), command.getBackupType(), command.isWaitForCompletion()),
                () -> backupHelper.buildDetachedBackupScriptCommand(delegate),
                () -> backupHelper.executeBackup(delegate),
                LibvirtAblestackNetBackupHelper.EXIT_CLEANUP_FAILED,
                "NetBackup backup helper returned failure without details",
                false,
                result -> backupHelper.calculateBackupSize(delegate));
    }
}
