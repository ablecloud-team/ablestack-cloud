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

package com.cloud.agent.api;

public class FtctlDrReversePreflightAnswer extends Answer {
    private Boolean ready;
    private String operationIntent;
    private String requestedMode;
    private String effectiveMode;
    private String modeDecisionCode;
    private Boolean initialSeedRequired;
    private String baselineFileState;
    private String sourceDomainProbeState;
    private String sourceDiskProbeState;
    private Integer sourceDiskCount;
    private String targetWriterProbeState;
    private Long estimatedVirtualBytes;
    private Integer statusEvidenceContractVersion;
    private Boolean statusEvidencePublicationReady;
    private String statusEvidenceErrorCode;
    private String errorCode;
    private Integer exitCode;
    private String statusJson;

    public FtctlDrReversePreflightAnswer(Command command, boolean success, String details) {
        super(command, success, details);
    }

    public FtctlDrReversePreflightAnswer(Command command, boolean success, String details,
            Boolean ready, String operationIntent, String requestedMode, String effectiveMode,
            String modeDecisionCode, Boolean initialSeedRequired, String baselineFileState,
            String sourceDomainProbeState, String sourceDiskProbeState, Integer sourceDiskCount, String targetWriterProbeState,
            Long estimatedVirtualBytes, Integer statusEvidenceContractVersion,
            Boolean statusEvidencePublicationReady, String statusEvidenceErrorCode,
            String errorCode, Integer exitCode, String statusJson) {
        super(command, success, details);
        this.ready = ready;
        this.operationIntent = operationIntent;
        this.requestedMode = requestedMode;
        this.effectiveMode = effectiveMode;
        this.modeDecisionCode = modeDecisionCode;
        this.initialSeedRequired = initialSeedRequired;
        this.baselineFileState = baselineFileState;
        this.sourceDomainProbeState = sourceDomainProbeState;
        this.sourceDiskProbeState = sourceDiskProbeState;
        this.sourceDiskCount = sourceDiskCount;
        this.targetWriterProbeState = targetWriterProbeState;
        this.estimatedVirtualBytes = estimatedVirtualBytes;
        this.statusEvidenceContractVersion = statusEvidenceContractVersion;
        this.statusEvidencePublicationReady = statusEvidencePublicationReady;
        this.statusEvidenceErrorCode = statusEvidenceErrorCode;
        this.errorCode = errorCode;
        this.exitCode = exitCode;
        this.statusJson = statusJson;
    }

    public Boolean getReady() { return ready; }
    public String getOperationIntent() { return operationIntent; }
    public String getRequestedMode() { return requestedMode; }
    public String getEffectiveMode() { return effectiveMode; }
    public String getModeDecisionCode() { return modeDecisionCode; }
    public Boolean getInitialSeedRequired() { return initialSeedRequired; }
    public String getBaselineFileState() { return baselineFileState; }
    public String getSourceDomainProbeState() { return sourceDomainProbeState; }
    public String getSourceDiskProbeState() { return sourceDiskProbeState; }
    public Integer getSourceDiskCount() { return sourceDiskCount; }
    public String getTargetWriterProbeState() { return targetWriterProbeState; }
    public Long getEstimatedVirtualBytes() { return estimatedVirtualBytes; }
    public Integer getStatusEvidenceContractVersion() { return statusEvidenceContractVersion; }
    public Boolean getStatusEvidencePublicationReady() { return statusEvidencePublicationReady; }
    public String getStatusEvidenceErrorCode() { return statusEvidenceErrorCode; }
    public String getErrorCode() { return errorCode; }
    public Integer getExitCode() { return exitCode; }
    public String getStatusJson() { return statusJson; }
}
