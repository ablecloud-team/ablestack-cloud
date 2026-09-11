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
import com.cloud.utils.Pair;
import org.apache.cloudstack.backup.AblestackVeeamTakeBackupCommand;
import org.apache.cloudstack.backup.BackupAnswer;
import org.apache.commons.lang3.StringUtils;

@ResourceWrapper(handles = AblestackVeeamTakeBackupCommand.class)
public class LibvirtAblestackVeeamTakeBackupCommandWrapper extends CommandWrapper<AblestackVeeamTakeBackupCommand, Answer, LibvirtComputingResource> {
    private static final String BACKUP_TRACE = "[ABLESTACK_VEEAM_BACKUP_TRACE]";

    @Override
    public Answer execute(final AblestackVeeamTakeBackupCommand command, final LibvirtComputingResource libvirtComputingResource) {
        logger.info("{} phase=[AGENT_ENTER], vm=[{}], backupPath=[{}], backupType=[{}]",
                BACKUP_TRACE, command.getVmName(), command.getBackupPath(), command.getBackupType());
        final AblestackVeeamTakeBackupCommand delegate = new AblestackVeeamTakeBackupCommand(command.getVmName(), command.getBackupPath());
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

        final LibvirtAblestackVeeamHelper backupHelper = new LibvirtAblestackVeeamHelper(libvirtComputingResource);
        final Pair<Integer, String> result = backupHelper.executeBackup(delegate);
        if (result.first() != 0) {
            final String failureDetails = StringUtils.defaultIfBlank(result.second(),
                    "Veeam backup helper returned failure without details");
            logger.warn("{} phase=[AGENT_FAILED], vm=[{}], backupPath=[{}], backupType=[{}], resultCode=[{}], reason=[{}]",
                    BACKUP_TRACE, command.getVmName(), command.getBackupPath(), command.getBackupType(), result.first(), failureDetails);
            final BackupAnswer answer = new BackupAnswer(command, false, failureDetails);
            if (result.first() == LibvirtAblestackVeeamHelper.EXIT_CLEANUP_FAILED) {
                answer.setNeedsCleanup(true);
            }
            return answer;
        }

        final BackupAnswer answer = new BackupAnswer(command, true, "success");
        try {
            answer.setSize(backupHelper.calculateBackupSize(delegate));
        } catch (RuntimeException e) {
            logger.warn("{} phase=[SIZE_CALC_FAILED], vm=[{}], backupPath=[{}], error=[{}]",
                    BACKUP_TRACE, command.getVmName(), command.getBackupPath(), e.getMessage());
        }
        logger.info("{} phase=[AGENT_DONE], vm=[{}], backupPath=[{}], backupType=[{}]",
                BACKUP_TRACE, command.getVmName(), command.getBackupPath(), command.getBackupType());
        return answer;
    }
}
