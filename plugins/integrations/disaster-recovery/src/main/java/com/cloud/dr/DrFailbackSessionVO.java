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
import java.util.UUID;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;

import org.apache.cloudstack.api.InternalIdentity;

@Entity
@Table(name = "dr_failback_session")
public class DrFailbackSessionVO implements InternalIdentity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;
    private String uuid = UUID.randomUUID().toString();
    @Column(name = "plan_id") private long planId;
    @Column(name = "run_id") private long runId;
    @Column(name = "engine_session_id") private String engineSessionId;
    @Column(name = "checkpoint_sequence") private Long checkpointSequence;
    @Column(name = "authority_generation") private Long authorityGeneration;
    private String state;
    @Column(name = "acceptance_state") private String acceptanceState;
    @Column(name = "failure_phase") private String failurePhase;
    @Column(name = "failed_component") private String failedComponent;
    @Column(name = "driver_exit_code") private Integer driverExitCode;
    @Column(name = "baseline_file_state") private String baselineFileState;
    @Column(name = "operation_intent") private String operationIntent;
    @Column(name = "requested_mode") private String requestedMode;
    @Column(name = "effective_mode") private String effectiveMode;
    @Column(name = "mode_decision_code") private String modeDecisionCode;
    @Column(name = "initial_seed_required") private Boolean initialSeedRequired;
    @Column(name = "source_disk_probe_state") private String sourceDiskProbeState;
    @Column(name = "source_disk_count") private Integer sourceDiskCount;
    @Column(name = "target_writer_probe_state") private String targetWriterProbeState;
    @Column(name = "estimated_virtual_bytes") private Long estimatedVirtualBytes;
    @Column(name = "worker_pid_alive") private Boolean workerPidAlive;
    @Column(name = "target_power_state") private String targetPowerState;
    @Column(name = "source_power_state") private String sourcePowerState;
    @Column(name = "boot_validation_state") private String bootValidationState;
    @Column(name = "engine_ack_state") private String engineAckState;
    @Column(name = "commit_attempt_id") private String commitAttemptId;
    @Column(name = "commit_outcome") private String commitOutcome;
    @Column(name = "commit_contract_version") private String commitContractVersion;
    @Column(name = "commit_envelope_sha256") private String commitEnvelopeSha256;
    @Column(name = "commit_dispatch_state") private String commitDispatchState;
    @Column(name = "commit_probe_count") private Integer commitProbeCount;
    @Column(name = "scheduler_generation") private Long schedulerGeneration;
    @Column(name = "scheduler_ack_generation") private Long schedulerAckGeneration;
    @Column(name = "scheduler_state") private String schedulerState;
    @Column(name = "rollback_state") private String rollbackState;
    @Column(name = "rollback_generation") private Long rollbackGeneration;
    @Column(name = "lifecycle_version") private long lifecycleVersion;
    @Column(name = "resume_baseline_checkpoint_sequence") private Long resumeBaselineCheckpointSequence;
    @Column(name = "required_post_failback_checkpoint_sequence") private Long requiredPostFailbackCheckpointSequence;
    @Column(name = "post_failback_checkpoint_sequence") private Long postFailbackCheckpointSequence;
    @Column(name = "replication_direction") private String replicationDirection;
    @Column(name = "provider_pair") private String providerPair;
    @Column(name = "baseline_generation") private Long baselineGeneration;
    @Column(name = "baseline_state") private String baselineState;
    @Column(name = "tracker_state") private String trackerState;
    @Column(name = "writer_state") private String writerState;
    @Column(name = "target_written") private Boolean targetWritten;
    @Column(name = "write_verified") private Boolean writeVerified;
    @Column(name = "guest_compatibility_state") private String guestCompatibilityState;
    @Temporal(TemporalType.TIMESTAMP) @Column(name = "data_ready_at") private Date dataReadyAt;
    @Temporal(TemporalType.TIMESTAMP) @Column(name = "target_stopped_at") private Date targetStoppedAt;
    @Temporal(TemporalType.TIMESTAMP) @Column(name = "source_powered_on_at") private Date sourcePoweredOnAt;
    @Temporal(TemporalType.TIMESTAMP) @Column(name = "boot_validated_at") private Date bootValidatedAt;
    @Temporal(TemporalType.TIMESTAMP) @Column(name = "engine_ack_at") private Date engineAckAt;
    @Temporal(TemporalType.TIMESTAMP) @Column(name = "commit_requested_at") private Date commitRequestedAt;
    @Temporal(TemporalType.TIMESTAMP) @Column(name = "commit_verified_at") private Date commitVerifiedAt;
    @Temporal(TemporalType.TIMESTAMP) @Column(name = "commit_dispatched_at") private Date commitDispatchedAt;
    @Temporal(TemporalType.TIMESTAMP) @Column(name = "commit_probe_deadline_at") private Date commitProbeDeadlineAt;
    @Temporal(TemporalType.TIMESTAMP) @Column(name = "protection_resume_requested_at") private Date protectionResumeRequestedAt;
    @Temporal(TemporalType.TIMESTAMP) @Column(name = "protection_resume_verified_at") private Date protectionResumeVerifiedAt;
    @Temporal(TemporalType.TIMESTAMP) @Column(name = "rollback_requested_at") private Date rollbackRequestedAt;
    @Temporal(TemporalType.TIMESTAMP) @Column(name = "rollback_verified_at") private Date rollbackVerifiedAt;
    @Temporal(TemporalType.TIMESTAMP) @Column(name = "last_probe_at") private Date lastProbeAt;
    @Temporal(TemporalType.TIMESTAMP) @Column(name = "completed_at") private Date completedAt;
    @Column(name = "details_json", length = 16777215) private String detailsJson;
    @Column(name = "error_code") private String errorCode;
    @Column(name = "error_message") private String errorMessage;
    @Temporal(TemporalType.TIMESTAMP) private Date created = new Date();
    @Temporal(TemporalType.TIMESTAMP) private Date updated = new Date();
    @Temporal(TemporalType.TIMESTAMP) private Date removed;

    protected DrFailbackSessionVO() {
    }

    public DrFailbackSessionVO(long planId, long runId, String engineSessionId, String state) {
        this.planId = planId;
        this.runId = runId;
        this.engineSessionId = engineSessionId;
        this.state = state;
    }

    @Override public long getId() { return id; }
    public String getUuid() { return uuid; }
    public long getPlanId() { return planId; }
    public long getRunId() { return runId; }
    public String getEngineSessionId() { return engineSessionId; }
    public Long getCheckpointSequence() { return checkpointSequence; }
    public Long getAuthorityGeneration() { return authorityGeneration; }
    public String getState() { return state; }
    public String getAcceptanceState() { return acceptanceState; }
    public String getFailurePhase() { return failurePhase; }
    public String getFailedComponent() { return failedComponent; }
    public Integer getDriverExitCode() { return driverExitCode; }
    public String getBaselineFileState() { return baselineFileState; }
    public String getOperationIntent() { return operationIntent; }
    public String getRequestedMode() { return requestedMode; }
    public String getEffectiveMode() { return effectiveMode; }
    public String getModeDecisionCode() { return modeDecisionCode; }
    public Boolean getInitialSeedRequired() { return initialSeedRequired; }
    public String getSourceDiskProbeState() { return sourceDiskProbeState; }
    public Integer getSourceDiskCount() { return sourceDiskCount; }
    public String getTargetWriterProbeState() { return targetWriterProbeState; }
    public Long getEstimatedVirtualBytes() { return estimatedVirtualBytes; }
    public Boolean getWorkerPidAlive() { return workerPidAlive; }
    public String getTargetPowerState() { return targetPowerState; }
    public String getSourcePowerState() { return sourcePowerState; }
    public String getBootValidationState() { return bootValidationState; }
    public String getEngineAckState() { return engineAckState; }
    public String getCommitAttemptId() { return commitAttemptId; }
    public String getCommitOutcome() { return commitOutcome; }
    public String getCommitContractVersion() { return commitContractVersion; }
    public String getCommitEnvelopeSha256() { return commitEnvelopeSha256; }
    public String getCommitDispatchState() { return commitDispatchState; }
    public Integer getCommitProbeCount() { return commitProbeCount; }
    public Long getSchedulerGeneration() { return schedulerGeneration; }
    public Long getSchedulerAckGeneration() { return schedulerAckGeneration; }
    public String getSchedulerState() { return schedulerState; }
    public String getRollbackState() { return rollbackState; }
    public Long getRollbackGeneration() { return rollbackGeneration; }
    public long getLifecycleVersion() { return lifecycleVersion; }
    public Long getResumeBaselineCheckpointSequence() { return resumeBaselineCheckpointSequence; }
    public Long getRequiredPostFailbackCheckpointSequence() { return requiredPostFailbackCheckpointSequence; }
    public Long getPostFailbackCheckpointSequence() { return postFailbackCheckpointSequence; }
    public String getReplicationDirection() { return replicationDirection; }
    public String getProviderPair() { return providerPair; }
    public Long getBaselineGeneration() { return baselineGeneration; }
    public String getBaselineState() { return baselineState; }
    public String getTrackerState() { return trackerState; }
    public String getWriterState() { return writerState; }
    public Boolean getTargetWritten() { return targetWritten; }
    public Boolean getWriteVerified() { return writeVerified; }
    public String getGuestCompatibilityState() { return guestCompatibilityState; }
    public Date getDataReadyAt() { return dataReadyAt; }
    public Date getTargetStoppedAt() { return targetStoppedAt; }
    public Date getSourcePoweredOnAt() { return sourcePoweredOnAt; }
    public Date getBootValidatedAt() { return bootValidatedAt; }
    public Date getEngineAckAt() { return engineAckAt; }
    public Date getCommitRequestedAt() { return commitRequestedAt; }
    public Date getCommitVerifiedAt() { return commitVerifiedAt; }
    public Date getCommitDispatchedAt() { return commitDispatchedAt; }
    public Date getCommitProbeDeadlineAt() { return commitProbeDeadlineAt; }
    public Date getProtectionResumeRequestedAt() { return protectionResumeRequestedAt; }
    public Date getProtectionResumeVerifiedAt() { return protectionResumeVerifiedAt; }
    public Date getRollbackRequestedAt() { return rollbackRequestedAt; }
    public Date getRollbackVerifiedAt() { return rollbackVerifiedAt; }
    public Date getLastProbeAt() { return lastProbeAt; }
    public Date getCompletedAt() { return completedAt; }
    public String getDetailsJson() { return detailsJson; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public Date getCreated() { return created; }
    public Date getUpdated() { return updated; }
    public Date getRemoved() { return removed; }

    public void setEngineSessionId(String value) { engineSessionId = value; }
    public void setCheckpointSequence(Long value) { checkpointSequence = value; }
    public void setAuthorityGeneration(Long value) { authorityGeneration = value; }
    public void setState(String value) { state = value; }
    public void setAcceptanceState(String value) { acceptanceState = value; }
    public void setFailurePhase(String value) { failurePhase = value; }
    public void setFailedComponent(String value) { failedComponent = value; }
    public void setDriverExitCode(Integer value) { driverExitCode = value; }
    public void setBaselineFileState(String value) { baselineFileState = value; }
    public void setOperationIntent(String value) { operationIntent = value; }
    public void setRequestedMode(String value) { requestedMode = value; }
    public void setEffectiveMode(String value) { effectiveMode = value; }
    public void setModeDecisionCode(String value) { modeDecisionCode = value; }
    public void setInitialSeedRequired(Boolean value) { initialSeedRequired = value; }
    public void setSourceDiskProbeState(String value) { sourceDiskProbeState = value; }
    public void setSourceDiskCount(Integer value) { sourceDiskCount = value; }
    public void setTargetWriterProbeState(String value) { targetWriterProbeState = value; }
    public void setEstimatedVirtualBytes(Long value) { estimatedVirtualBytes = value; }
    public void setWorkerPidAlive(Boolean value) { workerPidAlive = value; }
    public void setTargetPowerState(String value) { targetPowerState = value; }
    public void setSourcePowerState(String value) { sourcePowerState = value; }
    public void setBootValidationState(String value) { bootValidationState = value; }
    public void setEngineAckState(String value) { engineAckState = value; }
    public void setCommitAttemptId(String value) { commitAttemptId = value; }
    public void setCommitOutcome(String value) { commitOutcome = value; }
    public void setCommitContractVersion(String value) { commitContractVersion = value; }
    public void setCommitEnvelopeSha256(String value) { commitEnvelopeSha256 = value; }
    public void setCommitDispatchState(String value) { commitDispatchState = value; }
    public void setCommitProbeCount(Integer value) { commitProbeCount = value; }
    public void setSchedulerGeneration(Long value) { schedulerGeneration = value; }
    public void setSchedulerAckGeneration(Long value) { schedulerAckGeneration = value; }
    public void setSchedulerState(String value) { schedulerState = value; }
    public void setRollbackState(String value) { rollbackState = value; }
    public void setRollbackGeneration(Long value) { rollbackGeneration = value; }
    public void setLifecycleVersion(long value) { lifecycleVersion = value; }
    public void setResumeBaselineCheckpointSequence(Long value) { resumeBaselineCheckpointSequence = value; }
    public void setRequiredPostFailbackCheckpointSequence(Long value) { requiredPostFailbackCheckpointSequence = value; }
    public void setPostFailbackCheckpointSequence(Long value) { postFailbackCheckpointSequence = value; }
    public void setReplicationDirection(String value) { replicationDirection = value; }
    public void setProviderPair(String value) { providerPair = value; }
    public void setBaselineGeneration(Long value) { baselineGeneration = value; }
    public void setBaselineState(String value) { baselineState = value; }
    public void setTrackerState(String value) { trackerState = value; }
    public void setWriterState(String value) { writerState = value; }
    public void setTargetWritten(Boolean value) { targetWritten = value; }
    public void setWriteVerified(Boolean value) { writeVerified = value; }
    public void setGuestCompatibilityState(String value) { guestCompatibilityState = value; }
    public void setDataReadyAt(Date value) { dataReadyAt = value; }
    public void setTargetStoppedAt(Date value) { targetStoppedAt = value; }
    public void setSourcePoweredOnAt(Date value) { sourcePoweredOnAt = value; }
    public void setBootValidatedAt(Date value) { bootValidatedAt = value; }
    public void setEngineAckAt(Date value) { engineAckAt = value; }
    public void setCommitRequestedAt(Date value) { commitRequestedAt = value; }
    public void setCommitVerifiedAt(Date value) { commitVerifiedAt = value; }
    public void setCommitDispatchedAt(Date value) { commitDispatchedAt = value; }
    public void setCommitProbeDeadlineAt(Date value) { commitProbeDeadlineAt = value; }
    public void setProtectionResumeRequestedAt(Date value) { protectionResumeRequestedAt = value; }
    public void setProtectionResumeVerifiedAt(Date value) { protectionResumeVerifiedAt = value; }
    public void setRollbackRequestedAt(Date value) { rollbackRequestedAt = value; }
    public void setRollbackVerifiedAt(Date value) { rollbackVerifiedAt = value; }
    public void setLastProbeAt(Date value) { lastProbeAt = value; }
    public void setCompletedAt(Date value) { completedAt = value; }
    public void setDetailsJson(String value) { detailsJson = value; }
    public void setErrorCode(String value) { errorCode = value; }
    public void setErrorMessage(String value) { errorMessage = value; }
    public void setRemoved(Date value) { removed = value; }
    public void markUpdated() {
        updated = new Date();
        lifecycleVersion++;
    }
}
