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

/** Prepare or inspect the configured staging mount on a KVM worker. */
public class AblestackThirdPartyStagingCommand extends Command {
    private String storageType;
    private String rootPath;
    private String mountPath;
    private String nfsSource;
    private String mountOptions;
    private String path;
    private int timeoutSeconds;
    private long requiredBytes;

    protected AblestackThirdPartyStagingCommand() {
    }

    public AblestackThirdPartyStagingCommand(String storageType, String rootPath, String mountPath,
            String nfsSource, String mountOptions,
            String path, int timeoutSeconds, long requiredBytes) {
        this.storageType = storageType;
        this.rootPath = rootPath;
        this.mountPath = mountPath;
        this.nfsSource = nfsSource;
        this.mountOptions = mountOptions;
        this.path = path;
        this.timeoutSeconds = timeoutSeconds;
        this.requiredBytes = requiredBytes;
        setWait(timeoutSeconds + 30);
    }

    public String getStorageType() {
        return storageType;
    }

    public String getRootPath() {
        return rootPath;
    }

    public String getMountPath() {
        return mountPath;
    }

    public String getNfsSource() {
        return nfsSource;
    }

    public String getMountOptions() {
        return mountOptions;
    }

    public String getPath() {
        return path;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public long getRequiredBytes() {
        return requiredBytes;
    }

    @Override
    public boolean executeInSequence() {
        return false;
    }
}
