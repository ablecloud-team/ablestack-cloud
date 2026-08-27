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

package org.apache.cloudstack.storage.command;

import java.util.ArrayList;
import java.util.List;

import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;

public class PrepareSharedMountPointCloneCommand extends StorageSubSystemCommand {
    public static class VolumeCloneSpec {
        private long sourceVolumeId;
        private String sourceVolumePath;
        private String sourceOverlayPath;
        private long cloneVolumeId;
        private String cloneVolumePath;
        private long size;

        protected VolumeCloneSpec() {
        }

        public VolumeCloneSpec(long sourceVolumeId, String sourceVolumePath, String sourceOverlayPath, long cloneVolumeId, String cloneVolumePath, long size) {
            this.sourceVolumeId = sourceVolumeId;
            this.sourceVolumePath = sourceVolumePath;
            this.sourceOverlayPath = sourceOverlayPath;
            this.cloneVolumeId = cloneVolumeId;
            this.cloneVolumePath = cloneVolumePath;
            this.size = size;
        }

        public long getSourceVolumeId() {
            return sourceVolumeId;
        }

        public String getSourceVolumePath() {
            return sourceVolumePath;
        }

        public String getSourceOverlayPath() {
            return sourceOverlayPath;
        }

        public long getCloneVolumeId() {
            return cloneVolumeId;
        }

        public String getCloneVolumePath() {
            return cloneVolumePath;
        }

        public long getSize() {
            return size;
        }
    }

    private PrimaryDataStoreTO dataStore;
    private String vmName;
    private boolean sourceVmRunning;
    private String operationId;
    private boolean executeInSequence = true;
    private List<VolumeCloneSpec> volumeCloneSpecs = new ArrayList<>();

    protected PrepareSharedMountPointCloneCommand() {
    }

    public PrepareSharedMountPointCloneCommand(PrimaryDataStoreTO dataStore, String vmName, boolean sourceVmRunning, String operationId, List<VolumeCloneSpec> volumeCloneSpecs) {
        this.dataStore = dataStore;
        this.vmName = vmName;
        this.sourceVmRunning = sourceVmRunning;
        this.operationId = operationId;
        this.volumeCloneSpecs = volumeCloneSpecs;
    }

    public PrimaryDataStoreTO getDataStore() {
        return dataStore;
    }

    public String getVmName() {
        return vmName;
    }

    public boolean isSourceVmRunning() {
        return sourceVmRunning;
    }

    public String getOperationId() {
        return operationId;
    }

    public List<VolumeCloneSpec> getVolumeCloneSpecs() {
        return volumeCloneSpecs;
    }

    @Override
    public boolean executeInSequence() {
        return executeInSequence;
    }

    @Override
    public void setExecuteInSequence(boolean inSeq) {
        executeInSequence = inSeq;
    }
}
