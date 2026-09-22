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
import com.cloud.vm.VirtualMachine;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;

import java.util.List;

public class AblestackNasRestoreBackupCommand extends Command  {
    private String vmName;
    private String backupPath;
    private String backupRepoType;
    private String backupRepoAddress;
    private List<String> backupVolumesUUIDs;
    private List<PrimaryDataStoreTO> restoreVolumePools;
    private List<String> restoreVolumePaths;
    private List<String> volumePaths;
    private List<String> backupFiles;
    private List<String> backupFileChains;
    private List<BackupVolumeChainState> volumeChainStates;
    private BackupRestorePlan restorePlan;
    private String diskType;
    private Boolean vmExists;
    private VirtualMachine.State vmState;
    private Integer mountTimeout;
    private String cacheMode;
    private String restoreJobId;
    private boolean waitForCompletion = true;

    protected AblestackNasRestoreBackupCommand() {
        super();
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

    public List<PrimaryDataStoreTO> getRestoreVolumePools() {
        return restoreVolumePools;
    }

    public void setRestoreVolumePools(List<PrimaryDataStoreTO> restoreVolumePools) {
        this.restoreVolumePools = restoreVolumePools;
    }

    public List<String> getRestoreVolumePaths() {
        return restoreVolumePaths;
    }

    public void setRestoreVolumePaths(List<String> restoreVolumePaths) {
        this.restoreVolumePaths = restoreVolumePaths;
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

    public List<String> getBackupFileChains() {
        return backupFileChains;
    }

    public void setBackupFileChains(List<String> backupFileChains) {
        this.backupFileChains = backupFileChains;
    }

    public List<BackupVolumeChainState> getVolumeChainStates() {
        return volumeChainStates;
    }

    public void setVolumeChainStates(List<BackupVolumeChainState> volumeChainStates) {
        this.volumeChainStates = volumeChainStates;
    }

    public BackupRestorePlan getRestorePlan() {
        return restorePlan;
    }

    public void setRestorePlan(BackupRestorePlan restorePlan) {
        this.restorePlan = restorePlan;
    }

    public Boolean isVmExists() {
        return vmExists;
    }

    public void setVmExists(Boolean vmExists) {
        this.vmExists = vmExists;
    }

    public String getDiskType() {
        return diskType;
    }

    public void setDiskType(String diskType) {
        this.diskType = diskType;
    }

    public String getMountOptions() {
        return mountOptions;
    }

    public void setMountOptions(String mountOptions) {
        this.mountOptions = mountOptions;
    }

    public VirtualMachine.State getVmState() {
        return vmState;
    }

    public void setVmState(VirtualMachine.State vmState) {
        this.vmState = vmState;
    }

    @LogLevel(LogLevel.Log4jLevel.Off)
    private String mountOptions;
    @Override

    public boolean executeInSequence() {
        return true;
    }

    public List<String> getBackupVolumesUUIDs() {
        return backupVolumesUUIDs;
    }

    public void setBackupVolumesUUIDs(List<String> backupVolumesUUIDs) {
        this.backupVolumesUUIDs = backupVolumesUUIDs;
    }

    public Integer getMountTimeout() {
        return this.mountTimeout == null ? 0 : this.mountTimeout;
    }

    public void setMountTimeout(Integer mountTimeout) {
        this.mountTimeout = mountTimeout;
    }

    public String getCacheMode() {
        return cacheMode;
    }

    public void setCacheMode(String cacheMode) {
        this.cacheMode = cacheMode;
    }

    public String getRestoreJobId() {
        return restoreJobId;
    }

    public void setRestoreJobId(String restoreJobId) {
        this.restoreJobId = restoreJobId;
    }

    public boolean isWaitForCompletion() {
        return waitForCompletion;
    }

    public void setWaitForCompletion(boolean waitForCompletion) {
        this.waitForCompletion = waitForCompletion;
    }
}
