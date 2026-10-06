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
package com.cloud.vm;

import java.util.ArrayList;
import java.util.List;

import org.apache.cloudstack.storage.command.PrepareSharedMountPointCloneCommand.VolumeCloneSpec;

public class VmWorkSharedMountPointClone extends VmWork {
    private static final long serialVersionUID = 1L;

    public enum Operation { Prepare, Commit, Flatten, Recover, Bandwidth }

    private final Operation operation;
    private final String operationId;
    private final Long poolId;
    private final List<VolumeCloneSpec> volumeCloneSpecs;
    private Long volumeId;
    private Integer bandwidth;

    public VmWorkSharedMountPointClone(long userId, long accountId, long vmId, String handlerName,
            Operation operation, String operationId, Long poolId, List<VolumeCloneSpec> volumeCloneSpecs) {
        super(userId, accountId, vmId, handlerName);
        this.operation = operation;
        this.operationId = operationId;
        this.poolId = poolId;
        this.volumeCloneSpecs = volumeCloneSpecs == null ? new ArrayList<>() : new ArrayList<>(volumeCloneSpecs);
    }

    public Operation getOperation() {
        return operation;
    }

    public void setVolumeId(Long volumeId) {
        this.volumeId = volumeId;
    }

    public Long getVolumeId() {
        return volumeId;
    }

    public Integer getBandwidth() {
        return bandwidth;
    }

    public void setBandwidth(Integer bandwidth) {
        this.bandwidth = bandwidth;
    }

    public String getOperationId() {
        return operationId;
    }

    public Long getPoolId() {
        return poolId;
    }

    public List<VolumeCloneSpec> getVolumeCloneSpecs() {
        return volumeCloneSpecs;
    }
}
