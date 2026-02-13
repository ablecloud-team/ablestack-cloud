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

public class CommvaultRestoreBackupCommand extends Command  {
    private String vmName;
    private String backupPath;
    private List<String> backupVolumesUUIDs;
    private List<PrimaryDataStoreTO> restoreVolumePools;
    private List<String> restoreVolumePaths;
    private String diskType;
    private Boolean vmExists;
    private String restoreVolumeUUID;
    private VirtualMachine.State vmState;
    private Integer timeout;
    private String cacheMode;
    private String hostName;

    protected CommvaultRestoreBackupCommand() {
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

    public String getRestoreVolumeUUID() {
        return restoreVolumeUUID;
    }

    public void setRestoreVolumeUUID(String restoreVolumeUUID) {
        this.restoreVolumeUUID = restoreVolumeUUID;
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

    public Integer getTimeout() {
        return this.timeout == null ? 0 : this.timeout;
    }

    public void setTimeout(Integer timeout) {
        this.timeout = timeout;
    }

    public String getCacheMode() {
        return cacheMode;
    }

    public void setCacheMode(String cacheMode) {
        this.cacheMode = cacheMode;
    }

    public String getHostName() {
        return hostName;
    }

    public void setHostName(String hostName) {
        this.hostName = hostName;
    }
}
