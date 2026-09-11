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
import com.cloud.vm.VirtualMachine;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;

import java.util.List;

public class AblestackVeeamRestoreBackupCommand extends Command {
    private String vmName;
    private String backupPath;
    private List<String> backupVolumesUUIDs;
    private List<String> backupFiles;
    private List<String> backupFileChains;
    private List<BackupVolumeChainState> volumeChainStates;
    private BackupRestorePlan restorePlan;
    private List<PrimaryDataStoreTO> restoreVolumePools;
    private List<String> restoreVolumePaths;
    private String diskType;
    private Boolean vmExists;
    private String restoreVolumeUUID;
    private VirtualMachine.State vmState;
    private Integer timeout;
    private String cacheMode;
    private String checkpointName;

    public AblestackVeeamRestoreBackupCommand() {
        super();
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

    public List<String> getBackupVolumesUUIDs() {
        return backupVolumesUUIDs;
    }

    public void setBackupVolumesUUIDs(final List<String> backupVolumesUUIDs) {
        this.backupVolumesUUIDs = backupVolumesUUIDs;
    }

    public List<String> getBackupFiles() {
        return backupFiles;
    }

    public void setBackupFiles(final List<String> backupFiles) {
        this.backupFiles = backupFiles;
    }

    public List<String> getBackupFileChains() {
        return backupFileChains;
    }

    public void setBackupFileChains(final List<String> backupFileChains) {
        this.backupFileChains = backupFileChains;
    }

    public List<BackupVolumeChainState> getVolumeChainStates() {
        return volumeChainStates;
    }

    public void setVolumeChainStates(final List<BackupVolumeChainState> volumeChainStates) {
        this.volumeChainStates = volumeChainStates;
    }

    public BackupRestorePlan getRestorePlan() {
        return restorePlan;
    }

    public void setRestorePlan(final BackupRestorePlan restorePlan) {
        this.restorePlan = restorePlan;
    }

    public List<PrimaryDataStoreTO> getRestoreVolumePools() {
        return restoreVolumePools;
    }

    public void setRestoreVolumePools(final List<PrimaryDataStoreTO> restoreVolumePools) {
        this.restoreVolumePools = restoreVolumePools;
    }

    public List<String> getRestoreVolumePaths() {
        return restoreVolumePaths;
    }

    public void setRestoreVolumePaths(final List<String> restoreVolumePaths) {
        this.restoreVolumePaths = restoreVolumePaths;
    }

    public String getDiskType() {
        return diskType;
    }

    public void setDiskType(final String diskType) {
        this.diskType = diskType;
    }

    public Boolean isVmExists() {
        return vmExists;
    }

    public void setVmExists(final Boolean vmExists) {
        this.vmExists = vmExists;
    }

    public String getRestoreVolumeUUID() {
        return restoreVolumeUUID;
    }

    public void setRestoreVolumeUUID(final String restoreVolumeUUID) {
        this.restoreVolumeUUID = restoreVolumeUUID;
    }

    public VirtualMachine.State getVmState() {
        return vmState;
    }

    public void setVmState(final VirtualMachine.State vmState) {
        this.vmState = vmState;
    }

    public Integer getTimeout() {
        return timeout == null ? 0 : timeout;
    }

    public void setTimeout(final Integer timeout) {
        this.timeout = timeout;
        setWait(timeout == null ? 0 : timeout);
    }

    public String getCacheMode() {
        return cacheMode;
    }

    public void setCacheMode(final String cacheMode) {
        this.cacheMode = cacheMode;
    }

    public String getCheckpointName() {
        return checkpointName;
    }

    public void setCheckpointName(final String checkpointName) {
        this.checkpointName = checkpointName;
    }

    @Override
    public boolean executeInSequence() {
        return true;
    }
}
