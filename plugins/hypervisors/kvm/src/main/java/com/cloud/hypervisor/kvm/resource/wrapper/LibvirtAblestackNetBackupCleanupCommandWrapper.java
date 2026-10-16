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
import org.apache.cloudstack.backup.AblestackNetBackupCleanupCommand;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

@ResourceWrapper(handles = AblestackNetBackupCleanupCommand.class)
public class LibvirtAblestackNetBackupCleanupCommandWrapper
        extends CommandWrapper<AblestackNetBackupCleanupCommand, Answer, LibvirtComputingResource> {
    private static final Path LEGACY_BACKUP_ROOT = Path.of("/tmp/mold/netbackup").toAbsolutePath().normalize();

    @Override
    public Answer execute(final AblestackNetBackupCleanupCommand command, final LibvirtComputingResource serverResource) {
        if (CollectionUtils.isEmpty(command.getBackupPaths())) {
            return new Answer(command, true, "No NetBackup restore paths to cleanup.");
        }

        final List<String> failures = command.getBackupPaths().stream()
                .distinct()
                .map(path -> cleanupPath(command, path))
                .filter(result -> result != null)
                .collect(Collectors.toList());
        if (CollectionUtils.isNotEmpty(failures)) {
            return new Answer(command, false, String.join("; ", failures));
        }
        return new Answer(command, true, "NetBackup restore cleanup completed.");
    }

    private String cleanupPath(final AblestackNetBackupCleanupCommand command, final String backupPath) {
        if (StringUtils.isBlank(backupPath)) {
            return null;
        }
        final Path path = Path.of(backupPath);
        final Path configuredRoot = StringUtils.isNotBlank(command.getBackupRootPath())
                ? Path.of(command.getBackupRootPath()) : LEGACY_BACKUP_ROOT;
        try {
            LibvirtAblestackStagingCleanup.delete("ablestack-netbackup", path, configuredRoot);
            return null;
        } catch (final IOException e) {
            return String.format("Failed to cleanup NetBackup path [%s]: %s", path, e.getMessage());
        }
    }

}
