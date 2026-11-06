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
import com.cloud.utils.script.Script;
import org.apache.cloudstack.backup.AblestackBackupFrameworkUtils;
import org.apache.cloudstack.backup.AblestackNasInspectBackupCommand;
import org.apache.cloudstack.backup.BackupAnswer;
import org.apache.commons.lang3.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

@ResourceWrapper(handles = AblestackNasInspectBackupCommand.class)
public class LibvirtAblestackNasInspectBackupCommandWrapper
        extends CommandWrapper<AblestackNasInspectBackupCommand, Answer, LibvirtComputingResource> {
    private static final int UNMOUNT_TIMEOUT_SECONDS = 60;

    @Override
    public Answer execute(final AblestackNasInspectBackupCommand command, final LibvirtComputingResource resource) {
        Path mountPoint = null;
        try {
            mountPoint = Files.createTempDirectory("csbackup-inspect.");
            String mountCommand = buildMountCommand(command, mountPoint);
            int mountTimeoutMillis = command.getMountTimeout() > 0
                    ? Math.toIntExact(TimeUnit.SECONDS.toMillis(command.getMountTimeout()))
                    : resource.getCmdsTimeout();
            if (Script.runSimpleBashScriptForExitValue(mountCommand, mountTimeoutMillis, false) != 0) {
                return new BackupAnswer(command, false, "Failed to mount NAS backup repository for inspection");
            }

            Path backupPath = mountPoint.resolve(command.getBackupPath());
            if (!Files.exists(backupPath.resolve(AblestackBackupFrameworkUtils.BACKUP_COMPLETE_MARKER))) {
                return new BackupAnswer(command, false, "NAS backup is not complete");
            }
            BackupAnswer answer = new BackupAnswer(command, true, "complete");
            answer.setSize(calculateBackupSize(backupPath));
            return answer;
        } catch (Exception e) {
            return new BackupAnswer(command, false, e.getMessage());
        } finally {
            cleanupMountPoint(mountPoint);
        }
    }

    private String buildMountCommand(final AblestackNasInspectBackupCommand command, final Path mountPoint) {
        StringBuilder mount = new StringBuilder()
                .append("mount -t ").append(shellQuote(command.getBackupRepoType()))
                .append(" ").append(shellQuote(command.getBackupRepoAddress()))
                .append(" ").append(shellQuote(mountPoint.toString()));
        if (StringUtils.isNotBlank(command.getMountOptions())) {
            mount.append(" -o ").append(shellQuote(command.getMountOptions()));
        }
        return mount.toString();
    }

    private long calculateBackupSize(final Path backupPath) throws java.io.IOException {
        try (var walk = Files.walk(backupPath)) {
            return walk.filter(Files::isRegularFile).mapToLong(path -> {
                try {
                    return Files.size(path);
                } catch (Exception e) {
                    return 0L;
                }
            }).sum();
        }
    }

    private void cleanupMountPoint(final Path mountPoint) {
        if (mountPoint == null) {
            return;
        }
        Script.runSimpleBashScriptForExitValue(String.format("timeout %d umount %s",
                UNMOUNT_TIMEOUT_SECONDS, shellQuote(mountPoint.toString())));
        try {
            Files.deleteIfExists(mountPoint);
        } catch (Exception e) {
            logger.warn("Failed to remove NAS inspect mount point [{}]", mountPoint, e);
        }
    }

    private String shellQuote(final String value) {
        return "'" + StringUtils.defaultString(value).replace("'", "'\"'\"'") + "'";
    }
}
