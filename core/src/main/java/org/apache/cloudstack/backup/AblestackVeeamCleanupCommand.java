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

package org.apache.cloudstack.backup;

import com.cloud.agent.api.Command;

import java.util.List;

public class AblestackVeeamCleanupCommand extends Command {
    private List<String> backupPaths;
    private String backupRootPath;
    private String checkpointName;
    private List<String> cleanupCheckpointNames;
    private String diskPaths;
    private boolean forced;

    protected AblestackVeeamCleanupCommand() {
        super();
    }

    public AblestackVeeamCleanupCommand(final List<String> backupPaths) {
        this.backupPaths = backupPaths;
    }

    public AblestackVeeamCleanupCommand(final List<String> backupPaths, final String backupRootPath) {
        this.backupPaths = backupPaths;
        this.backupRootPath = backupRootPath;
    }

    public List<String> getBackupPaths() {
        return backupPaths;
    }

    public void setBackupPaths(final List<String> backupPaths) {
        this.backupPaths = backupPaths;
    }

    public String getBackupRootPath() {
        return backupRootPath;
    }

    public void setBackupRootPath(final String backupRootPath) {
        this.backupRootPath = backupRootPath;
    }

    public String getCheckpointName() {
        return checkpointName;
    }

    public void setCheckpointName(final String checkpointName) {
        this.checkpointName = checkpointName;
    }

    public List<String> getCleanupCheckpointNames() {
        return cleanupCheckpointNames;
    }

    public void setCleanupCheckpointNames(final List<String> cleanupCheckpointNames) {
        this.cleanupCheckpointNames = cleanupCheckpointNames;
    }

    public String getDiskPaths() {
        return diskPaths;
    }

    public void setDiskPaths(final String diskPaths) {
        this.diskPaths = diskPaths;
    }

    public boolean isForced() {
        return forced;
    }

    public void setForced(final boolean forced) {
        this.forced = forced;
    }

    @Override
    public boolean executeInSequence() {
        return true;
    }
}
