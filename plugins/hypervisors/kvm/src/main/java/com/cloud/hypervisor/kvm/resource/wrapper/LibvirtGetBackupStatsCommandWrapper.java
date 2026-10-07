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

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.cloudstack.backup.BackupStorageStatsAnswer;
import org.apache.cloudstack.backup.GetBackupStorageStatsCommand;

import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;
import com.cloud.utils.Pair;
import com.cloud.utils.script.Script;

@ResourceWrapper(handles = GetBackupStorageStatsCommand.class)
public class LibvirtGetBackupStatsCommandWrapper extends CommandWrapper<GetBackupStorageStatsCommand, BackupStorageStatsAnswer, LibvirtComputingResource> {
    private static final Pattern STORAGE_STATS_PATTERN = Pattern.compile("(?m)^[ \\t]*([0-9]+)[ \\t]+([0-9]+)[ \\t]*\\r?$");

    @Override
    public BackupStorageStatsAnswer execute(GetBackupStorageStatsCommand command, LibvirtComputingResource libvirtComputingResource) {
        final String backupRepoType = command.getBackupRepoType();
        final String backupRepoAddress = command.getBackupRepoAddress();
        final String mountOptions = command.getMountOptions();

        List<String[]> commands = new ArrayList<>();
        commands.add(new String[]{
                libvirtComputingResource.getNasBackupPath(),
                "-o", "stats",
                "-t", backupRepoType,
                "-s", backupRepoAddress,
                "-m", mountOptions,
                "-w", String.valueOf(command.getMountTimeout())
        });

        try {
            Pair<Integer, String> result = Script.executePipedCommands(commands, libvirtComputingResource.getCmdsTimeout());

            logger.debug(String.format("Get backup storage stats result: %s , exit code: %s", result.second(), result.first()));

            if (result.first() != 0) {
                logger.debug(String.format("Failed to get backup storage stats: %s", result.second()));
                return new BackupStorageStatsAnswer(command, false, result.second());
            }

            // nasbackup.sh emits the mount path and a separate line containing total/used KiB.
            // Mount warnings can precede them; only the two-column numeric line is capacity data.
            String output = result.second();
            Matcher stats = STORAGE_STATS_PATTERN.matcher(output == null ? "" : output);
            if (!stats.find()) {
                return new BackupStorageStatsAnswer(command, false, "Invalid backup storage stats output: " + output);
            }
            long total = Math.multiplyExact(Long.parseLong(stats.group(1)), 1024L);
            long used = Math.multiplyExact(Long.parseLong(stats.group(2)), 1024L);
            if (stats.find() || total <= 0L || used > total) {
                return new BackupStorageStatsAnswer(command, false, "Invalid or ambiguous backup storage stats output: " + output);
            }

            BackupStorageStatsAnswer answer = new BackupStorageStatsAnswer(command, true, output);
            answer.setTotalSize(total);
            answer.setUsedSize(used);
            return answer;
        } catch (RuntimeException e) {
            logger.warn("Failed to get backup storage stats", e);
            return new BackupStorageStatsAnswer(command, false, "Failed to get backup storage stats: " + e.getMessage());
        }
    }
}
