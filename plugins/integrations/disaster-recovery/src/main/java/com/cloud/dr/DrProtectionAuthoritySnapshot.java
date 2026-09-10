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

package com.cloud.dr;

import java.util.Date;

public class DrProtectionAuthoritySnapshot {
    private final DrPlanRuntimeVO runtime;
    private final boolean normalCutoverReady;
    private final String normalCutoverReason;

    public DrProtectionAuthoritySnapshot(DrPlanRuntimeVO runtime, boolean normalCutoverReady) {
        this(runtime, normalCutoverReady, normalCutoverReady ? null : "DR_AUTHORITY_NOT_READY");
    }

    public DrProtectionAuthoritySnapshot(DrPlanRuntimeVO runtime, boolean normalCutoverReady, String normalCutoverReason) {
        this.runtime = runtime;
        this.normalCutoverReady = normalCutoverReady;
        this.normalCutoverReason = normalCutoverReason;
    }

    public DrPlanRuntimeVO getRuntime() { return runtime; }
    public String getProtectionState() { return runtime != null ? runtime.getProtectionState() : "UNKNOWN"; }
    public String getFreshnessState() { return runtime != null ? runtime.getFreshnessState() : "UNKNOWN"; }
    public String getSchedulerState() { return runtime != null ? runtime.getSchedulerState() : null; }
    public boolean isSchedulerPidAlive() { return runtime != null && runtime.isSchedulerPidAlive(); }
    public String getSchedulerSessionUuid() { return runtime != null ? runtime.getSchedulerSessionUuid() : null; }
    public Long getSchedulerLeaseEpoch() { return runtime != null ? runtime.getSchedulerLeaseEpoch() : null; }
    public Long getAuthoritySequence() { return runtime != null ? runtime.getAuthoritySequence() : null; }
    public String getSchedulerHealthState() { return runtime != null ? runtime.getSchedulerHealthState() : null; }
    public String getReplicationActivityState() { return runtime != null ? runtime.getReplicationActivityState() : null; }
    public String getActiveWorkerRunUuid() { return runtime != null ? runtime.getActiveWorkerRunUuid() : null; }
    public Date getWorkerHeartbeatAt() { return runtime != null ? runtime.getWorkerHeartbeatAt() : null; }
    public boolean isOwnerMatched() { return runtime != null && runtime.isOwnerMatched(); }
    public Long getRuntimeGeneration() { return runtime != null ? runtime.getRuntimeGeneration() : null; }
    public Long getCurrentCycleSequence() { return runtime != null ? runtime.getCurrentCycleSequence() : null; }
    public String getCurrentCycleState() { return runtime != null ? runtime.getCurrentCycleState() : null; }
    public String getCurrentCycleMode() { return runtime != null ? runtime.getCurrentCycleMode() : null; }
    public String getBaselineState() { return runtime != null ? runtime.getBaselineState() : null; }
    public String getReseedReason() { return runtime != null ? runtime.getReseedReason() : null; }
    public String getProjectionIntegrityState() { return runtime != null ? runtime.getProjectionIntegrityState() : null; }
    public String getProjectionIntegrityCode() { return runtime != null ? runtime.getProjectionIntegrityCode() : null; }
    public Long getProjectionIntegritySequence() { return runtime != null ? runtime.getProjectionIntegritySequence() : null; }
    public String getErrorCode() { return runtime != null ? runtime.getErrorCode() : null; }
    public String getErrorMessage() { return runtime != null ? runtime.getErrorMessage() : null; }
    public Date getLastStatusAt() { return runtime != null ? runtime.getLastStatusAt() : null; }
    public Date getLastTargetDurableAt() { return runtime != null ? runtime.getLastTargetDurableAt() : null; }
    public Long getRpoAgeSeconds() { return runtime != null ? runtime.getRpoAgeSeconds() : null; }
    public boolean isRpoOverdue() { return runtime == null || runtime.isRpoOverdue(); }
    public Date getSchedulerNextRunAt() { return runtime != null ? runtime.getSchedulerNextRunAt() : null; }
    public Integer getSchedulerExecutionBudgetSeconds() { return runtime != null ? runtime.getSchedulerExecutionBudgetSeconds() : null; }
    public Integer getSchedulerCycleWallDurationSeconds() { return runtime != null ? runtime.getSchedulerCycleWallDurationSeconds() : null; }
    public boolean isNormalCutoverReady() { return normalCutoverReady; }
    public String getNormalCutoverReason() { return normalCutoverReason; }
}
