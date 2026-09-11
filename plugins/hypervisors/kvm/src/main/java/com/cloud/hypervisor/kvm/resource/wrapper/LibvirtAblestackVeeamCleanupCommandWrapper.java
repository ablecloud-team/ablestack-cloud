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
import org.apache.cloudstack.backup.AblestackVeeamCleanupCommand;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@ResourceWrapper(handles = AblestackVeeamCleanupCommand.class)
public class LibvirtAblestackVeeamCleanupCommandWrapper
        extends CommandWrapper<AblestackVeeamCleanupCommand, Answer, LibvirtComputingResource> {
    private static final Path LEGACY_BACKUP_ROOT = Path.of("/tmp/mold/veeam").toAbsolutePath().normalize();

    @Override
    public Answer execute(final AblestackVeeamCleanupCommand command, final LibvirtComputingResource serverResource) {
        if (CollectionUtils.isEmpty(command.getBackupPaths())) {
            return new Answer(command, true, "No Veeam restore paths to cleanup.");
        }

        final List<String> failures = command.getBackupPaths().stream()
                .distinct()
                .map(path -> cleanupPath(command, path))
                .filter(result -> result != null)
                .collect(Collectors.toList());
        if (CollectionUtils.isNotEmpty(failures)) {
            return new Answer(command, false, String.join("; ", failures));
        }
        return new Answer(command, true, "Veeam restore cleanup completed.");
    }

    private String cleanupPath(final AblestackVeeamCleanupCommand command, final String backupPath) {
        if (StringUtils.isBlank(backupPath)) {
            return null;
        }
        final Path path = Path.of(backupPath).toAbsolutePath().normalize();
        if (!isSafeCleanupPath(command, path)) {
            return String.format("Skipping unsafe Veeam cleanup path [%s]", path);
        }
        if (!Files.exists(path)) {
            return null;
        }
        try (Stream<Path> stream = Files.walk(path)) {
            final List<Path> paths = stream.sorted(Comparator.reverseOrder()).collect(Collectors.toList());
            for (final Path item : paths) {
                Files.deleteIfExists(item);
            }
            return null;
        } catch (final IOException e) {
            return String.format("Failed to cleanup Veeam path [%s]: %s", path, e.getMessage());
        }
    }

    private boolean isSafeCleanupPath(final AblestackVeeamCleanupCommand command, final Path path) {
        final Path configuredRoot = StringUtils.isNotBlank(command.getBackupRootPath())
                ? Path.of(command.getBackupRootPath()).toAbsolutePath().normalize()
                : LEGACY_BACKUP_ROOT;
        return isChildPath(path, configuredRoot) || isChildPath(path, LEGACY_BACKUP_ROOT);
    }

    private boolean isChildPath(final Path path, final Path root) {
        return path.startsWith(root) && !path.equals(root);
    }
}
