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

import com.cloud.agent.api.Command;
import com.cloud.agent.api.LogLevel;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;

import java.util.List;

public class AblestackNasImportVeeamSeedCommand extends Command {
    private String vmName;
    private String backupPath;
    private String checkpointName;
    private String backupRepoType;
    private String backupRepoAddress;
    private List<PrimaryDataStoreTO> volumePools;
    private List<String> volumePaths;
    private List<String> backupFiles;
    private List<String> stagingDiskPaths;
    private String sourceFormat;
    private String veeamRestorePointId;
    private Boolean bootstrapCheckpoint;
    @LogLevel(LogLevel.Log4jLevel.Off)
    private String mountOptions;

    public AblestackNasImportVeeamSeedCommand(String vmName, String backupPath) {
        super();
        this.vmName = vmName;
        this.backupPath = backupPath;
    }

    public String getVmName() {
        return vmName;
    }

    public void setVmName(String vmName) {
        this.vmName = vmName;
    }

    public String getBackupPath() {
        return backupPath;
    }

    public void setBackupPath(String backupPath) {
        this.backupPath = backupPath;
    }

    public String getCheckpointName() {
        return checkpointName;
    }

    public void setCheckpointName(String checkpointName) {
        this.checkpointName = checkpointName;
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

    public List<PrimaryDataStoreTO> getVolumePools() {
        return volumePools;
    }

    public void setVolumePools(List<PrimaryDataStoreTO> volumePools) {
        this.volumePools = volumePools;
    }

    public List<String> getVolumePaths() {
        return volumePaths;
    }

    public void setVolumePaths(List<String> volumePaths) {
        this.volumePaths = volumePaths;
    }

    public List<String> getBackupFiles() {
        return backupFiles;
    }

    public void setBackupFiles(List<String> backupFiles) {
        this.backupFiles = backupFiles;
    }

    public List<String> getStagingDiskPaths() {
        return stagingDiskPaths;
    }

    public void setStagingDiskPaths(List<String> stagingDiskPaths) {
        this.stagingDiskPaths = stagingDiskPaths;
    }

    public String getSourceFormat() {
        return sourceFormat;
    }

    public void setSourceFormat(String sourceFormat) {
        this.sourceFormat = sourceFormat;
    }

    public String getVeeamRestorePointId() {
        return veeamRestorePointId;
    }

    public void setVeeamRestorePointId(String veeamRestorePointId) {
        this.veeamRestorePointId = veeamRestorePointId;
    }

    public Boolean getBootstrapCheckpoint() {
        return bootstrapCheckpoint;
    }

    public void setBootstrapCheckpoint(Boolean bootstrapCheckpoint) {
        this.bootstrapCheckpoint = bootstrapCheckpoint;
    }

    public String getMountOptions() {
        return mountOptions;
    }

    public void setMountOptions(String mountOptions) {
        this.mountOptions = mountOptions;
    }

    @Override
    public boolean executeInSequence() {
        return true;
    }
}
