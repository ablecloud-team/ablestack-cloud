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
import com.cloud.agent.api.LogLevel;

public class AblestackNasInspectBackupCommand extends Command {
    private String backupPath;
    private String backupRepoType;
    private String backupRepoAddress;
    @LogLevel(LogLevel.Log4jLevel.Off)
    private String mountOptions;
    private Integer mountTimeout;

    protected AblestackNasInspectBackupCommand() {
        super();
    }

    public AblestackNasInspectBackupCommand(final String backupPath, final String backupRepoType, final String backupRepoAddress) {
        this.backupPath = backupPath;
        this.backupRepoType = backupRepoType;
        this.backupRepoAddress = backupRepoAddress;
    }

    public String getBackupPath() {
        return backupPath;
    }

    public String getBackupRepoType() {
        return backupRepoType;
    }

    public String getBackupRepoAddress() {
        return backupRepoAddress;
    }

    public String getMountOptions() {
        return mountOptions;
    }

    public void setMountOptions(final String mountOptions) {
        this.mountOptions = mountOptions;
    }

    public Integer getMountTimeout() {
        return mountTimeout == null ? 0 : mountTimeout;
    }

    public void setMountTimeout(final Integer mountTimeout) {
        this.mountTimeout = mountTimeout;
    }

    @Override
    public boolean executeInSequence() {
        return true;
    }
}
