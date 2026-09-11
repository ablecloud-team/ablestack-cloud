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

public class AblestackVeeamTakeBackupCommand extends Command {
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

    public AblestackVeeamTakeBackupCommand(final String vmName, final String backupPath) {
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

    public Boolean getQuiesce() {
        return quiesce;
    }

    public void setQuiesce(final Boolean quiesce) {
        this.quiesce = quiesce;
    }

    public String getBackupType() {
        return backupType;
    }

    public void setBackupType(final String backupType) {
        this.backupType = backupType;
    }

    public String getCheckpointName() {
        return checkpointName;
    }

    public void setCheckpointName(final String checkpointName) {
        this.checkpointName = checkpointName;
    }

    public String getParentBackupPath() {
        return parentBackupPath;
    }

    public void setParentBackupPath(final String parentBackupPath) {
        this.parentBackupPath = parentBackupPath;
    }

    public String getParentCheckpointName() {
        return parentCheckpointName;
    }

    public void setParentCheckpointName(final String parentCheckpointName) {
        this.parentCheckpointName = parentCheckpointName;
    }

    public String getParentCheckpointPath() {
        return parentCheckpointPath;
    }

    public void setParentCheckpointPath(final String parentCheckpointPath) {
        this.parentCheckpointPath = parentCheckpointPath;
    }

    public String getParentCheckpointXml() {
        return parentCheckpointXml;
    }

    public void setParentCheckpointXml(final String parentCheckpointXml) {
        this.parentCheckpointXml = parentCheckpointXml;
    }

    public Map<String, String> getParentCheckpointXmlChain() {
        return parentCheckpointXmlChain;
    }

    public void setParentCheckpointXmlChain(final Map<String, String> parentCheckpointXmlChain) {
        this.parentCheckpointXmlChain = parentCheckpointXmlChain != null ? new LinkedHashMap<>(parentCheckpointXmlChain) : null;
    }

    public List<String> getBackupFiles() {
        return backupFiles;
    }

    public void setBackupFiles(final List<String> backupFiles) {
        this.backupFiles = backupFiles;
    }

    @Override
    public boolean executeInSequence() {
        return true;
    }
}
