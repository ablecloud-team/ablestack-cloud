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
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;

import java.util.List;

/**
 * Import Veeam-exported disks as a FULL local seed under /tmp/mold/veeam for subsequent incremental backups.
 */
public class AblestackVeeamImportSeedCommand extends Command {
    private String vmName;
    private String backupPath;
    private String checkpointName;
    private List<PrimaryDataStoreTO> volumePools;
    private List<String> volumePaths;
    private List<String> backupFiles;
    private List<String> stagingDiskPaths;
    private String sourceFormat;
    private String veeamRestorePointId;
    private Boolean bootstrapCheckpoint;

    public AblestackVeeamImportSeedCommand(final String vmName, final String backupPath) {
        super();
        this.vmName = vmName;
        this.backupPath = backupPath;
    }

    public String getVmName() {
        return vmName;
    }

    public void setVmName(final String vmName) {
        this.vmName = vmName;
    }

    public String getBackupPath() {
        return backupPath;
    }

    public void setBackupPath(final String backupPath) {
        this.backupPath = backupPath;
    }

    public String getCheckpointName() {
        return checkpointName;
    }

    public void setCheckpointName(final String checkpointName) {
        this.checkpointName = checkpointName;
    }

    public List<PrimaryDataStoreTO> getVolumePools() {
        return volumePools;
    }

    public void setVolumePools(final List<PrimaryDataStoreTO> volumePools) {
        this.volumePools = volumePools;
    }

    public List<String> getVolumePaths() {
        return volumePaths;
    }

    public void setVolumePaths(final List<String> volumePaths) {
        this.volumePaths = volumePaths;
    }

    public List<String> getBackupFiles() {
        return backupFiles;
    }

    public void setBackupFiles(final List<String> backupFiles) {
        this.backupFiles = backupFiles;
    }

    public List<String> getStagingDiskPaths() {
        return stagingDiskPaths;
    }

    public void setStagingDiskPaths(final List<String> stagingDiskPaths) {
        this.stagingDiskPaths = stagingDiskPaths;
    }

    public String getSourceFormat() {
        return sourceFormat;
    }

    public void setSourceFormat(final String sourceFormat) {
        this.sourceFormat = sourceFormat;
    }

    public String getVeeamRestorePointId() {
        return veeamRestorePointId;
    }

    public void setVeeamRestorePointId(final String veeamRestorePointId) {
        this.veeamRestorePointId = veeamRestorePointId;
    }

    public Boolean getBootstrapCheckpoint() {
        return bootstrapCheckpoint;
    }

    public void setBootstrapCheckpoint(final Boolean bootstrapCheckpoint) {
        this.bootstrapCheckpoint = bootstrapCheckpoint;
    }

    @Override
    public boolean executeInSequence() {
        return true;
    }
}
