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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.commons.lang3.StringUtils;

import com.cloud.agent.api.FtctlDrReversePreflightAnswer;

public class DrFailbackPreflightResult {
    private boolean ready;
    private String errorCode;
    private String message;
    private DrSiteVO activeSite;
    private DrSiteVO destinationSite;
    private String activeCredentialState;
    private String destinationCredentialState;
    private DrRestorePointVO checkpoint;
    private DrSourceIsolationPreflightResult transitionPreflight;
    private String operationIntent = "FAILBACK_FINAL";
    private String requestedMode = "AUTO";
    private String dataPreflightState = DrFailbackPreflightStage.STATE_NOT_RUN;
    private String failureStage;
    private Date observedAt = new Date();
    private Date expiresAt = new Date(System.currentTimeMillis() + 15000L);
    private final List<DrFailbackPreflightStage> stages = new ArrayList<DrFailbackPreflightStage>();
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

    public static DrFailbackPreflightResult success(DrSiteVO activeSite, DrSiteVO destinationSite,
            DrRestorePointVO checkpoint) {
        DrFailbackPreflightResult result = new DrFailbackPreflightResult();
        result.ready = true;
        result.activeSite = activeSite;
        result.destinationSite = destinationSite;
        result.activeCredentialState = DrConstants.CREDENTIAL_STATE_CONFIGURED;
        result.destinationCredentialState = DrConstants.CREDENTIAL_STATE_CONFIGURED;
        result.checkpoint = checkpoint;
        return result;
    }

    public static DrFailbackPreflightResult failure(String errorCode, String message,
            DrSiteVO activeSite, DrSiteVO destinationSite, String activeCredentialState,
            String destinationCredentialState, DrRestorePointVO checkpoint) {
        DrFailbackPreflightResult result = new DrFailbackPreflightResult();
        result.ready = false;
        result.errorCode = errorCode;
        result.message = message;
        result.activeSite = activeSite;
        result.destinationSite = destinationSite;
        result.activeCredentialState = activeCredentialState;
        result.destinationCredentialState = destinationCredentialState;
        result.checkpoint = checkpoint;
        return result;
    }

    public boolean isReady() { return ready; }
    public String getErrorCode() { return errorCode; }
    public String getMessage() { return message; }
    public DrSiteVO getActiveSite() { return activeSite; }
    public DrSiteVO getDestinationSite() { return destinationSite; }
    public String getActiveCredentialState() { return activeCredentialState; }
    public String getDestinationCredentialState() { return destinationCredentialState; }
    public DrRestorePointVO getCheckpoint() { return checkpoint; }
    public Date getCheckpointReadyAt() { return checkpoint != null ? checkpoint.getTargetReadyAt() : null; }
    public DrSourceIsolationPreflightResult getTransitionPreflight() { return transitionPreflight; }
    public String getOperationIntent() { return operationIntent; }
    public String getRequestedMode() { return requestedMode; }
    public String getDataPreflightState() { return dataPreflightState; }
    public String getFailureStage() { return failureStage; }
    public Date getObservedAt() { return observedAt; }
    public Date getExpiresAt() { return expiresAt; }
    public List<DrFailbackPreflightStage> getStages() { return Collections.unmodifiableList(stages); }
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

    public DrFailbackPreflightResult withTransitionPreflight(
            DrSourceIsolationPreflightResult transitionPreflight) {
        this.transitionPreflight = transitionPreflight;
        if (transitionPreflight != null) {
            this.stages.clear();
            this.stages.addAll(transitionPreflight.getStages());
            this.failureStage = transitionPreflight.getFailureStage();
        }
        return this;
    }

    public DrFailbackPreflightResult withReversePreflight(FtctlDrReversePreflightAnswer answer) {
        if (answer == null) {
            this.ready = false;
            this.dataPreflightState = DrFailbackPreflightStage.STATE_BLOCKED;
            this.failureStage = "REVERSE_DATA";
            this.errorCode = "DR_REVERSE_PREFLIGHT_UNAVAILABLE";
            this.message = "FTCTL reverse-data preflight returned no typed answer";
            this.stages.add(DrFailbackPreflightStage.blocked("REVERSE_DATA", errorCode,
                    message, "FTCTL"));
            return this;
        }
        this.operationIntent = answer.getOperationIntent();
        this.requestedMode = answer.getRequestedMode();
        this.effectiveMode = answer.getEffectiveMode();
        this.modeDecisionCode = answer.getModeDecisionCode();
        this.initialSeedRequired = answer.getInitialSeedRequired();
        this.baselineFileState = answer.getBaselineFileState();
        this.sourceDomainProbeState = answer.getSourceDomainProbeState();
        this.sourceDiskProbeState = answer.getSourceDiskProbeState();
        this.sourceDiskCount = answer.getSourceDiskCount();
        this.targetWriterProbeState = answer.getTargetWriterProbeState();
        this.estimatedVirtualBytes = answer.getEstimatedVirtualBytes();
        this.statusEvidenceContractVersion = answer.getStatusEvidenceContractVersion();
        this.statusEvidencePublicationReady = answer.getStatusEvidencePublicationReady();
        this.statusEvidenceErrorCode = answer.getStatusEvidenceErrorCode();
        boolean publicationReady = statusEvidenceContractVersion != null
                && statusEvidenceContractVersion >= 1
                && Boolean.TRUE.equals(statusEvidencePublicationReady);
        boolean reverseReady = answer.getResult() && Boolean.TRUE.equals(answer.getReady()) && publicationReady;
        this.dataPreflightState = reverseReady ? DrFailbackPreflightStage.STATE_READY
                : DrFailbackPreflightStage.STATE_BLOCKED;
        if (reverseReady) {
            this.stages.add(DrFailbackPreflightStage.ready("REVERSE_DATA", "FTCTL",
                    "Reverse replication data path is ready"));
        } else {
            this.ready = false;
            this.failureStage = "REVERSE_DATA";
            if (answer.getResult() && Boolean.TRUE.equals(answer.getReady()) && !publicationReady) {
                this.errorCode = StringUtils.defaultIfBlank(statusEvidenceErrorCode,
                        "DR_FAILBACK_DATA_EVIDENCE_CONTRACT_UNSUPPORTED");
                this.message = "FTCTL cannot publish the durable reverse-data evidence required by Cloud";
            } else {
                this.errorCode = answer.getErrorCode() != null ? answer.getErrorCode()
                        : "DR_REVERSE_PREFLIGHT_FAILED";
                this.message = answer.getDetails();
            }
            this.stages.add(DrFailbackPreflightStage.blocked("REVERSE_DATA", errorCode,
                    message, "FTCTL"));
        }
        return this;
    }

    public DrFailbackPreflightResult appendReverseNotRun() {
        this.dataPreflightState = DrFailbackPreflightStage.STATE_NOT_RUN;
        boolean present = false;
        for (DrFailbackPreflightStage stage : stages) {
            present = present || "REVERSE_DATA".equals(stage.getCode());
        }
        if (!present) {
            stages.add(DrFailbackPreflightStage.notRun("REVERSE_DATA",
                    "Skipped because an earlier safety stage is blocked"));
        }
        return this;
    }
}
