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

package org.apache.cloudstack.api.response.dr;

import java.util.Date;
import java.util.ArrayList;
import java.util.List;

import org.apache.cloudstack.api.BaseResponse;

import com.cloud.dr.DrFailbackPreflightResult;
import com.cloud.dr.DrFailbackPreflightStage;
import com.cloud.dr.DrRestorePointVO;
import com.cloud.dr.DrSiteVO;
import com.cloud.dr.DrSourceIsolationPreflightResult;
import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;

public class DrFailbackPreflightResponse extends BaseResponse {
    @SerializedName("ready") @Param(description = "whether failback can be started")
    private boolean ready;
    @SerializedName("errorcode") @Param(description = "typed preflight error code")
    private String errorCode;
    @SerializedName("message") @Param(description = "preflight result message")
    private String message;
    @SerializedName("activesiteid") @Param(description = "active target site ID")
    private String activeSiteId;
    @SerializedName("activesitename") @Param(description = "active target site name")
    private String activeSiteName;
    @SerializedName("activesitetype") @Param(description = "active target site type")
    private String activeSiteType;
    @SerializedName("activesitehealth") @Param(description = "active target site health")
    private String activeSiteHealth;
    @SerializedName("activecredentialstate") @Param(description = "active site credential state")
    private String activeCredentialState;
    @SerializedName("destinationsiteid") @Param(description = "registered source site ID")
    private String destinationSiteId;
    @SerializedName("destinationsitename") @Param(description = "registered source site name")
    private String destinationSiteName;
    @SerializedName("destinationsitetype") @Param(description = "registered source site type")
    private String destinationSiteType;
    @SerializedName("destinationsitehealth") @Param(description = "registered source site health")
    private String destinationSiteHealth;
    @SerializedName("destinationcredentialstate") @Param(description = "destination site credential state")
    private String destinationCredentialState;
    @SerializedName("checkpointid") @Param(description = "latest durable checkpoint ID")
    private String checkpointId;
    @SerializedName("checkpointsequence") @Param(description = "latest durable checkpoint sequence")
    private Long checkpointSequence;
    @SerializedName("checkpointreadyat") @Param(description = "latest durable checkpoint time")
    private Date checkpointReadyAt;
    @SerializedName("authoritygeneration") @Param(description = "committed target authority generation")
    private Long authorityGeneration;
    @SerializedName("sourcefencestate") @Param(description = "source isolation or fence evidence")
    private String sourceFenceState;
    @SerializedName("sourcepowerstate") @Param(description = "source VM power evidence")
    private String sourcePowerState;
    @SerializedName("targetpowerstate") @Param(description = "serving target VM power evidence")
    private String targetPowerState;
    @SerializedName("enginepreflightready") @Param(description = "whether FTCTL transition preflight passed")
    private Boolean enginePreflightReady;
    @SerializedName("operationintent") @Param(description = "reverse replication operation intent")
    private String operationIntent;
    @SerializedName("requestedmode") @Param(description = "requested reverse replication mode")
    private String requestedMode;
    @SerializedName("datapreflightstate") @Param(description = "reverse data preflight state")
    private String dataPreflightState;
    @SerializedName("failurestage") @Param(description = "first blocked preflight stage")
    private String failureStage;
    @SerializedName("observedat") @Param(description = "preflight observation time")
    private Date observedAt;
    @SerializedName("expiresat") @Param(description = "display cache expiry time")
    private Date expiresAt;
    @SerializedName("runtimedriftstate") @Param(description = "Cloud DB and Agent runtime drift")
    private String runtimeDriftState;
    @SerializedName("targetdbstate") @Param(description = "serving target VM Cloud DB state")
    private String targetDbState;
    @SerializedName("targetagentstate") @Param(description = "serving target VM Agent state")
    private String targetAgentState;
    @SerializedName("targetvmid") @Param(description = "serving target VM internal ID")
    private Long targetVmId;
    @SerializedName("targetvmhostid") @Param(description = "serving target VM host internal ID")
    private Long targetVmHostId;
    @SerializedName("effectivemode") @Param(description = "effective reverse replication mode")
    private String effectiveMode;
    @SerializedName("modedecisioncode") @Param(description = "reverse mode decision code")
    private String modeDecisionCode;
    @SerializedName("initialseedrequired") @Param(description = "whether a full reverse seed is required")
    private Boolean initialSeedRequired;
    @SerializedName("baselinefilestate") @Param(description = "reverse baseline state")
    private String baselineFileState;
    @SerializedName("sourcedomainprobestate") @Param(description = "serving KVM source domain probe state")
    private String sourceDomainProbeState;
    @SerializedName("sourcediskprobestate") @Param(description = "reverse source disk probe state")
    private String sourceDiskProbeState;
    @SerializedName("sourcediskcount") @Param(description = "reverse source disk count")
    private Integer sourceDiskCount;
    @SerializedName("targetwriterprobestate") @Param(description = "VMware writer probe state")
    private String targetWriterProbeState;
    @SerializedName("estimatedvirtualbytes") @Param(description = "estimated reverse virtual bytes")
    private Long estimatedVirtualBytes;
    @SerializedName("statusevidencecontractversion") @Param(description = "FTCTL reverse evidence publication contract version")
    private Integer statusEvidenceContractVersion;
    @SerializedName("statusevidencepublicationready") @Param(description = "whether FTCTL can publish durable reverse evidence")
    private Boolean statusEvidencePublicationReady;
    @SerializedName("statusevidenceerrorcode") @Param(description = "typed evidence publication preflight error")
    private String statusEvidenceErrorCode;
    @SerializedName("stages") @Param(description = "ordered failback preflight stages")
    private List<StageResponse> stages = new ArrayList<StageResponse>();

    public static DrFailbackPreflightResponse from(DrFailbackPreflightResult result) {
        DrFailbackPreflightResponse response = new DrFailbackPreflightResponse();
        response.setObjectName("drfailbackpreflight");
        response.ready = result.isReady();
        response.errorCode = result.getErrorCode();
        response.message = result.getMessage();
        response.activeCredentialState = result.getActiveCredentialState();
        response.destinationCredentialState = result.getDestinationCredentialState();
        response.operationIntent = result.getOperationIntent();
        response.requestedMode = result.getRequestedMode();
        response.dataPreflightState = result.getDataPreflightState();
        response.failureStage = result.getFailureStage();
        response.observedAt = result.getObservedAt();
        response.expiresAt = result.getExpiresAt();
        response.effectiveMode = result.getEffectiveMode();
        response.modeDecisionCode = result.getModeDecisionCode();
        response.initialSeedRequired = result.getInitialSeedRequired();
        response.baselineFileState = result.getBaselineFileState();
        response.sourceDomainProbeState = result.getSourceDomainProbeState();
        response.sourceDiskProbeState = result.getSourceDiskProbeState();
        response.sourceDiskCount = result.getSourceDiskCount();
        response.targetWriterProbeState = result.getTargetWriterProbeState();
        response.estimatedVirtualBytes = result.getEstimatedVirtualBytes();
        response.statusEvidenceContractVersion = result.getStatusEvidenceContractVersion();
        response.statusEvidencePublicationReady = result.getStatusEvidencePublicationReady();
        response.statusEvidenceErrorCode = result.getStatusEvidenceErrorCode();
        for (DrFailbackPreflightStage stage : result.getStages()) {
            response.stages.add(StageResponse.from(stage));
        }
        response.copyActiveSite(result.getActiveSite());
        response.copyDestinationSite(result.getDestinationSite());
        DrRestorePointVO checkpoint = result.getCheckpoint();
        if (checkpoint != null) {
            response.checkpointId = checkpoint.getUuid();
            response.checkpointSequence = checkpoint.getCheckpointSequence();
            response.checkpointReadyAt = checkpoint.getTargetReadyAt();
        }
        DrSourceIsolationPreflightResult transition = result.getTransitionPreflight();
        if (transition != null) {
            response.authorityGeneration = transition.getAuthorityGeneration();
            response.sourceFenceState = transition.getSourceFenceState();
            response.sourcePowerState = transition.getSourcePowerState();
            response.targetPowerState = transition.getTargetPowerState();
            response.enginePreflightReady = "READY".equals(transition.getEnginePreflightState())
                    ? Boolean.TRUE : "BLOCKED".equals(transition.getEnginePreflightState())
                            ? Boolean.FALSE : null;
            response.runtimeDriftState = transition.getRuntimeDriftState();
            response.targetDbState = transition.getTargetDbState();
            response.targetAgentState = transition.getTargetAgentState();
            response.targetVmId = transition.getTargetVmId();
            response.targetVmHostId = transition.getTargetHostId();
        }
        return response;
    }

    public static class StageResponse {
        @SerializedName("code") @Param(description = "stage code")
        private String code;
        @SerializedName("state") @Param(description = "READY, BLOCKED, or NOT_RUN")
        private String state;
        @SerializedName("errorcode") @Param(description = "typed stage error code")
        private String errorCode;
        @SerializedName("message") @Param(description = "stage message")
        private String message;
        @SerializedName("observedby") @Param(description = "runtime observation authority")
        private String observedBy;
        @SerializedName("observedat") @Param(description = "stage observation time")
        private Date observedAt;

        private static StageResponse from(DrFailbackPreflightStage stage) {
            StageResponse response = new StageResponse();
            response.code = stage.getCode();
            response.state = stage.getState();
            response.errorCode = stage.getErrorCode();
            response.message = stage.getMessage();
            response.observedBy = stage.getObservedBy();
            response.observedAt = stage.getObservedAt();
            return response;
        }
    }

    private void copyActiveSite(DrSiteVO site) {
        if (site == null) {
            return;
        }
        activeSiteId = site.getUuid();
        activeSiteName = site.getName();
        activeSiteType = site.getSiteType();
        activeSiteHealth = site.getHealthState();
    }

    private void copyDestinationSite(DrSiteVO site) {
        if (site == null) {
            return;
        }
        destinationSiteId = site.getUuid();
        destinationSiteName = site.getName();
        destinationSiteType = site.getSiteType();
        destinationSiteHealth = site.getHealthState();
    }
}
