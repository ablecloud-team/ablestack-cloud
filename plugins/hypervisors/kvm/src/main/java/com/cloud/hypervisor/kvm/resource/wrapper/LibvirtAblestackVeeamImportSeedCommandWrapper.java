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
import org.apache.cloudstack.backup.AblestackVeeamImportSeedCommand;
import org.apache.cloudstack.backup.BackupAnswer;

import java.util.List;

@ResourceWrapper(handles = AblestackVeeamImportSeedCommand.class)
public class LibvirtAblestackVeeamImportSeedCommandWrapper
        extends CommandWrapper<AblestackVeeamImportSeedCommand, Answer, LibvirtComputingResource> {

    @Override
    public Answer execute(final AblestackVeeamImportSeedCommand command, final LibvirtComputingResource libvirtComputingResource) {
        final LibvirtAblestackVeeamHelper backupHelper = new LibvirtAblestackVeeamHelper(libvirtComputingResource);
        final List<String> diskPaths = backupHelper.resolveDiskPaths(command.getVolumePools(), command.getVolumePaths());
        final Pair<Integer, String> result = backupHelper.executeImportSeed(command, diskPaths);

        if (result.first() != 0) {
            final BackupAnswer answer = new BackupAnswer(command, false,
                    result.second() != null ? result.second().trim() : "Veeam seed import failed");
            if (result.first() == LibvirtAblestackVeeamHelper.EXIT_CLEANUP_FAILED) {
                answer.setNeedsCleanup(true);
            }
            return answer;
        }

        final BackupAnswer answer = new BackupAnswer(command, true,
                result.second() != null ? result.second().trim() : "success");
        try {
            answer.setSize(backupHelper.parseBackupSize(result.second(), diskPaths));
        } catch (RuntimeException e) {
            logger.warn("Failed to parse Veeam seed import size for vm=[{}]: {}", command.getVmName(), e.getMessage());
        }
        return answer;
    }
}
