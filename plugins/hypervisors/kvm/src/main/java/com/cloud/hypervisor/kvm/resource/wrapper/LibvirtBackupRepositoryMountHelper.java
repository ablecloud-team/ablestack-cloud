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

import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.commons.lang3.StringUtils;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Objects;

/**
 * Mount helpers for backup repositories on KVM hosts.
 * Local repositories use bind mounts (see ablestack_nasbackup.sh mount_operation).
 */
public final class LibvirtBackupRepositoryMountHelper {

    private static final String MOUNT_COMMAND = "sudo mount -t %s %s %s";

    private LibvirtBackupRepositoryMountHelper() {
    }

    public static boolean isLocalBackupRepositoryType(final String backupRepoType) {
        if (StringUtils.isBlank(backupRepoType)) {
            return false;
        }
        final String normalized = backupRepoType.toLowerCase(Locale.ROOT);
        return "local".equals(normalized) || "dir".equals(normalized) || "localfs".equals(normalized);
    }

    public static String buildMountCommand(final String backupRepoAddress, final String backupRepoType,
            String mountOptions, final String mountDirectory) {
        if (isLocalBackupRepositoryType(backupRepoType)) {
            if (!Files.isDirectory(Paths.get(backupRepoAddress))) {
                throw new CloudRuntimeException(String.format(
                        "Local backup directory does not exist on the KVM host: %s", backupRepoAddress));
            }
            return String.format("sudo mount --bind %s %s", backupRepoAddress, mountDirectory);
        }

        String mount = String.format(MOUNT_COMMAND, backupRepoType, backupRepoAddress, mountDirectory);
        if ("cifs".equals(backupRepoType)) {
            if (Objects.isNull(mountOptions) || mountOptions.trim().isEmpty()) {
                mountOptions = "nobrl";
            } else {
                mountOptions += ",nobrl";
            }
        }
        if (Objects.nonNull(mountOptions) && !mountOptions.trim().isEmpty()) {
            mount += " -o " + mountOptions;
        }
        return mount;
    }
}
