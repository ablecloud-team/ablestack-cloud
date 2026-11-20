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

package org.apache.cloudstack.backup;

import com.cloud.agent.api.LogLevel;

public class AblestackDeleteBackupCommand extends AblestackDataOperationCommand {
    private String backupPath;
    private String backupRepoType;
    private String backupRepoAddress;
    private String backupProvider;
    private String vmName;
    private String checkpointName;
    private String cleanupCheckpointNames;
    private String diskPaths;
    private boolean forced;
    @LogLevel(LogLevel.Log4jLevel.Off)
    private String mountOptions;
    private Integer mountTimeout;

    public AblestackDeleteBackupCommand(String backupPath, String backupRepoType, String backupRepoAddress, String mountOptions, boolean forced) {
        super();
        this.backupPath = backupPath;
        this.backupRepoType = backupRepoType;
        this.backupRepoAddress = backupRepoAddress;
        this.mountOptions = mountOptions;
        this.forced = forced;
    }

    public String getBackupPath() {
        return backupPath;
    }

    public void setBackupPath(String backupPath) {
        this.backupPath = backupPath;
    }

    public String getBackupRepoType() {
        return backupRepoType;
    }

    public void setBackupRepoType(String backupRepoType) {
        this.backupRepoType = backupRepoType;
    }

    public String getBackupRepoAddress() {
        return backupRepoAddress;
    }

    public void setBackupRepoAddress(String backupRepoAddress) {
        this.backupRepoAddress = backupRepoAddress;
    }

    public String getBackupProvider() {
        return backupProvider;
    }

    public void setBackupProvider(String backupProvider) {
        this.backupProvider = backupProvider;
    }

    public String getVmName() {
        return vmName;
    }

    public void setVmName(String vmName) {
        this.vmName = vmName;
    }

    public String getCheckpointName() {
        return checkpointName;
    }

    public void setCheckpointName(String checkpointName) {
        this.checkpointName = checkpointName;
    }

    public String getCleanupCheckpointNames() {
        return cleanupCheckpointNames;
    }

    public void setCleanupCheckpointNames(String cleanupCheckpointNames) {
        this.cleanupCheckpointNames = cleanupCheckpointNames;
    }

    public String getDiskPaths() {
        return diskPaths;
    }

    public void setDiskPaths(String diskPaths) {
        this.diskPaths = diskPaths;
    }

    public String getMountOptions() {
        return mountOptions == null ? "" : mountOptions;
    }

    public void setMountOptions(String mountOptions) {
        this.mountOptions = mountOptions;
    }

    public Integer getMountTimeout() {
        return this.mountTimeout == null ? 0 : this.mountTimeout;
    }

    public void setMountTimeout(Integer mountTimeout) {
        this.mountTimeout = mountTimeout;
    }

    public boolean isForced() {
        return forced;
    }

    public void setForced(boolean forced) {
        this.forced = forced;
    }

    @Override
    public boolean executeInSequence() {
        return true;
    }
}
