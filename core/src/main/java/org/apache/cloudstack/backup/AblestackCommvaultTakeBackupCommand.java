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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AblestackCommvaultTakeBackupCommand extends Command {
    private String vmName;
    private String backupPath;
    private List<PrimaryDataStoreTO> volumePools;
    private List<String> volumePaths;
    private Boolean quiesce;
    private String backupType;
    private String checkpointName;
    private String parentBackupPath;
    private String parentCheckpointName;
    private String parentCheckpointPath;
    private String parentCheckpointXml;
    private Map<String, String> parentCheckpointXmlChain;
    private List<String> backupFiles;
    private Integer bandwidthLimitMbps;

    public AblestackCommvaultTakeBackupCommand(String vmName, String backupPath) {
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

    public Boolean getQuiesce() {
        return quiesce;
    }

    public void setQuiesce(Boolean quiesce) {
        this.quiesce = quiesce;
    }

    public String getBackupType() {
        return backupType;
    }

    public void setBackupType(String backupType) {
        this.backupType = backupType;
    }

    public String getCheckpointName() {
        return checkpointName;
    }

    public void setCheckpointName(String checkpointName) {
        this.checkpointName = checkpointName;
    }

    public String getParentBackupPath() {
        return parentBackupPath;
    }

    public void setParentBackupPath(String parentBackupPath) {
        this.parentBackupPath = parentBackupPath;
    }

    public String getParentCheckpointName() {
        return parentCheckpointName;
    }

    public void setParentCheckpointName(String parentCheckpointName) {
        this.parentCheckpointName = parentCheckpointName;
    }

    public String getParentCheckpointPath() {
        return parentCheckpointPath;
    }

    public void setParentCheckpointPath(String parentCheckpointPath) {
        this.parentCheckpointPath = parentCheckpointPath;
    }

    public String getParentCheckpointXml() {
        return parentCheckpointXml;
    }

    public void setParentCheckpointXml(String parentCheckpointXml) {
        this.parentCheckpointXml = parentCheckpointXml;
    }

    public Map<String, String> getParentCheckpointXmlChain() {
        return parentCheckpointXmlChain;
    }

    public void setParentCheckpointXmlChain(Map<String, String> parentCheckpointXmlChain) {
        this.parentCheckpointXmlChain = parentCheckpointXmlChain != null ? new LinkedHashMap<>(parentCheckpointXmlChain) : null;
    }

    public List<String> getBackupFiles() {
        return backupFiles;
    }

    public void setBackupFiles(List<String> backupFiles) {
        this.backupFiles = backupFiles;
    }

    public Integer getBandwidthLimitMbps() {
        return bandwidthLimitMbps == null ? 0 : bandwidthLimitMbps;
    }

    public void setBandwidthLimitMbps(Integer bandwidthLimitMbps) {
        this.bandwidthLimitMbps = bandwidthLimitMbps;
    }

    @Override
    public boolean executeInSequence() {
        return true;
    }
}
