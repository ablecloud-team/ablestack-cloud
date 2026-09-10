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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class DrSourceIsolationPreflightResult {
    private final boolean ready;
    private final String errorCode;
    private final String message;
    private final String operation;
    private final Long authorityGeneration;
    private final String sourceFenceState;
    private final String sourcePowerState;
    private final String targetPowerState;
    private final String engineEvidenceJson;
    private final List<DrFailbackPreflightStage> stages;
    private final String failureStage;
    private final String enginePreflightState;
    private final String runtimeDriftState;
    private final String targetDbState;
    private final String targetAgentState;
    private final Long targetVmId;
    private final Long targetHostId;

    private DrSourceIsolationPreflightResult(boolean ready, String errorCode, String message,
            String operation, Long authorityGeneration, String sourceFenceState,
            String sourcePowerState, String targetPowerState, String engineEvidenceJson,
            List<DrFailbackPreflightStage> stages, String failureStage,
            String enginePreflightState, String runtimeDriftState, String targetDbState,
            String targetAgentState, Long targetVmId, Long targetHostId) {
        this.ready = ready;
        this.errorCode = errorCode;
        this.message = message;
        this.operation = operation;
        this.authorityGeneration = authorityGeneration;
        this.sourceFenceState = sourceFenceState;
        this.sourcePowerState = sourcePowerState;
        this.targetPowerState = targetPowerState;
        this.engineEvidenceJson = engineEvidenceJson;
        this.stages = stages != null ? new ArrayList<DrFailbackPreflightStage>(stages)
                : new ArrayList<DrFailbackPreflightStage>();
        this.failureStage = failureStage;
        this.enginePreflightState = enginePreflightState;
        this.runtimeDriftState = runtimeDriftState;
        this.targetDbState = targetDbState;
        this.targetAgentState = targetAgentState;
        this.targetVmId = targetVmId;
        this.targetHostId = targetHostId;
    }

    public static DrSourceIsolationPreflightResult success(String operation, Long authorityGeneration,
            String sourceFenceState, String sourcePowerState, String targetPowerState,
            String engineEvidenceJson) {
        return new DrSourceIsolationPreflightResult(true, null, null, operation, authorityGeneration,
                sourceFenceState, sourcePowerState, targetPowerState, engineEvidenceJson,
                null, null, DrFailbackPreflightStage.STATE_READY, "CONSISTENT", null,
                targetPowerState, null, null);
    }

    public static DrSourceIsolationPreflightResult failure(String errorCode, String message,
            String operation, Long authorityGeneration, String sourceFenceState,
            String sourcePowerState, String targetPowerState, String engineEvidenceJson) {
        return new DrSourceIsolationPreflightResult(false, errorCode, message, operation,
                authorityGeneration, sourceFenceState, sourcePowerState, targetPowerState,
                engineEvidenceJson, null, null, DrFailbackPreflightStage.STATE_NOT_RUN,
                null, null, targetPowerState, null, null);
    }

    public static DrSourceIsolationPreflightResult of(boolean ready, String errorCode, String message,
            String operation, Long authorityGeneration, String sourceFenceState,
            String sourcePowerState, String targetPowerState, String engineEvidenceJson,
            List<DrFailbackPreflightStage> stages, String failureStage,
            String enginePreflightState, String runtimeDriftState, String targetDbState,
            String targetAgentState, Long targetVmId, Long targetHostId) {
        return new DrSourceIsolationPreflightResult(ready, errorCode, message, operation,
                authorityGeneration, sourceFenceState, sourcePowerState, targetPowerState,
                engineEvidenceJson, stages, failureStage, enginePreflightState, runtimeDriftState,
                targetDbState, targetAgentState, targetVmId, targetHostId);
    }

    public boolean isReady() { return ready; }
    public String getErrorCode() { return errorCode; }
    public String getMessage() { return message; }
    public String getOperation() { return operation; }
    public Long getAuthorityGeneration() { return authorityGeneration; }
    public String getSourceFenceState() { return sourceFenceState; }
    public String getSourcePowerState() { return sourcePowerState; }
    public String getTargetPowerState() { return targetPowerState; }
    public String getEngineEvidenceJson() { return engineEvidenceJson; }
    public List<DrFailbackPreflightStage> getStages() { return Collections.unmodifiableList(stages); }
    public String getFailureStage() { return failureStage; }
    public String getEnginePreflightState() { return enginePreflightState; }
    public String getRuntimeDriftState() { return runtimeDriftState; }
    public String getTargetDbState() { return targetDbState; }
    public String getTargetAgentState() { return targetAgentState; }
    public Long getTargetVmId() { return targetVmId; }
    public Long getTargetHostId() { return targetHostId; }
}
