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
@Table(name = "dr_plan_runtime")
public class DrPlanRuntimeVO implements InternalIdentity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;
    @Column(name = "plan_id")
    private long planId;
    @Column(name = "engine_run_uuid")
    private String engineRunUuid;
    @Column(name = "runtime_generation")
    private long runtimeGeneration;
    @Column(name = "scheduler_state")
    private String schedulerState;
    @Column(name = "scheduler_desired_state")
    private String schedulerDesiredState;
    @Column(name = "scheduler_service_unit")
    private String schedulerServiceUnit;
    @Column(name = "scheduler_unit_active_state")
    private String schedulerUnitActiveState;
    @Column(name = "scheduler_unit_sub_state")
    private String schedulerUnitSubState;
    @Column(name = "scheduler_unit_main_pid")
    private Long schedulerUnitMainPid;
    @Column(name = "scheduler_cgroup", length = 512)
    private String schedulerCgroup;
    @Column(name = "scheduler_recovery_state")
    private String schedulerRecoveryState;
    @Column(name = "scheduler_recovery_trigger")
    private String schedulerRecoveryTrigger;
    @Column(name = "scheduler_recovery_attempts")
    private int schedulerRecoveryAttempts;
    @Column(name = "scheduler_recovery_error_code")
    private String schedulerRecoveryErrorCode;
    @Column(name = "scheduler_recovery_error_message", length = 4096)
    private String schedulerRecoveryErrorMessage;
    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "scheduler_recovered_at")
    private Date schedulerRecoveredAt;
    @Column(name = "scheduler_pid_alive")
    private boolean schedulerPidAlive;
    @Column(name = "scheduler_session_uuid")
    private String schedulerSessionUuid;
    @Column(name = "scheduler_lease_epoch")
    private long schedulerLeaseEpoch;
    @Column(name = "authority_sequence")
    private long authoritySequence;
    @Column(name = "plan_cycle_sequence")
    private Long planCycleSequence;
    @Column(name = "scheduler_health_state")
    private String schedulerHealthState;
    @Column(name = "replication_activity_state")
    private String replicationActivityState;
    @Column(name = "active_worker_run_uuid")
    private String activeWorkerRunUuid;
    @Column(name = "active_worker_pid")
    private Long activeWorkerPid;
    @Column(name = "active_worker_start_ticks")
    private Long activeWorkerStartTicks;
    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "worker_heartbeat_at")
    private Date workerHeartbeatAt;
    @Column(name = "control_request_run_uuid")
    private String controlRequestRunUuid;
    @Column(name = "owner_matched")
    private boolean ownerMatched;
    @Column(name = "worker_state")
    private String workerState;
    @Column(name = "worker_identity_state")
    private String workerIdentityState;
    @Column(name = "worker_liveness_state")
    private String workerLivenessState;
    @Column(name = "worker_launch_nonce")
    private String workerLaunchNonce;
    @Column(name = "worker_generation")
    private Long workerGeneration;
    @Column(name = "transfer_activity_state")
    private String transferActivityState;
    @Column(name = "transfer_payload_bytes")
    private Long transferPayloadBytes;
    @Column(name = "transfer_progress_schema_version")
    private Integer transferProgressSchemaVersion;
    @Column(name = "transfer_cycle_sequence")
    private Long transferCycleSequence;
    @Column(name = "transfer_sample_sequence")
    private Long transferSampleSequence;
    @Column(name = "transfer_phase")
    private String transferPhase;
    @Column(name = "transfer_mode")
    private String transferMode;
    @Column(name = "transfer_bytes_total")
    private Long transferBytesTotal;
    @Column(name = "transfer_bytes_processed")
    private Long transferBytesProcessed;
    @Column(name = "transfer_source_read_bytes")
    private Long transferSourceReadBytes;
    @Column(name = "transfer_target_written_bytes")
    private Long transferTargetWrittenBytes;
    @Column(name = "transfer_verified_bytes")
    private Long transferVerifiedBytes;
    @Column(name = "transfer_percent")
    private Double transferPercent;
    @Column(name = "transfer_throughput_bps")
    private Long transferThroughputBps;
    @Column(name = "transfer_eta_seconds")
    private Long transferEtaSeconds;
    @Column(name = "transfer_current_disk_index")
    private Integer transferCurrentDiskIndex;
    @Column(name = "transfer_disk_count")
    private Integer transferDiskCount;
    @Column(name = "transfer_progress_estimated")
    private Boolean transferProgressEstimated;
    @Column(name = "transfer_progress_sampled_at")
    @Temporal(TemporalType.TIMESTAMP)
    private Date transferProgressSampledAt;
    @Column(name = "transfer_progress_stale")
    private Boolean transferProgressStale;
    @Column(name = "owned_process_count")
    private int ownedProcessCount;
    @Column(name = "runtime_endpoints_drained")
    private boolean runtimeEndpointsDrained;
    @Column(name = "reconciliation_state")
    private String reconciliationState;
    @Column(name = "reconciliation_run_uuid")
    private String reconciliationRunUuid;
    @Column(name = "reconciliation_checks")
    private int reconciliationChecks;
    @Column(name = "terminal_source")
    private String terminalSource;
    @Column(name = "terminal_version")
    private Integer terminalVersion;
    @Column(name = "terminal_authoritative")
    private boolean terminalAuthoritative;
    @Column(name = "current_cycle_sequence")
    private Long currentCycleSequence;
    @Column(name = "current_cycle_state")
    private String currentCycleState;
    @Column(name = "current_cycle_mode")
    private String currentCycleMode;
    @Column(name = "baseline_state")
    private String baselineState;
    @Column(name = "reseed_reason")
    private String reseedReason;
    @Column(name = "last_mode_decision_code")
    private String lastModeDecisionCode;
    @Column(name = "consecutive_automatic_reseed_count")
    private int consecutiveAutomaticReseedCount;
    @Column(name = "latest_completed_cycle_sequence")
    private Long latestCompletedCycleSequence;
    @Column(name = "latest_completed_incremental_verified")
    private Boolean latestCompletedIncrementalVerified;
    @Column(name = "projection_integrity_state")
    private String projectionIntegrityState;
    @Column(name = "projection_integrity_code")
    private String projectionIntegrityCode;
    @Column(name = "projection_integrity_sequence")
    private Long projectionIntegritySequence;
    @Column(name = "nbd_teardown_state")
    private String nbdTeardownState;
    @Column(name = "nbd_quarantined_device_count")
    private int nbdQuarantinedDeviceCount;
    @Column(name = "nbd_teardown_error_code")
    private String nbdTeardownErrorCode;
    @Column(name = "nbd_teardown_error_message", length = 4096)
    private String nbdTeardownErrorMessage;
    @Column(name = "protection_state")
    private String protectionState;
    @Column(name = "freshness_state")
    private String freshnessState;
    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "scheduler_next_run_at")
    private Date schedulerNextRunAt;
    @Column(name = "scheduler_execution_budget_seconds")
    private Integer schedulerExecutionBudgetSeconds;
    @Column(name = "scheduler_cycle_wall_duration_seconds")
    private Integer schedulerCycleWallDurationSeconds;
    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "last_status_at")
    private Date lastStatusAt;
    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "last_source_checkpoint_at")
    private Date lastSourceCheckpointAt;
    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "last_target_durable_at")
    private Date lastTargetDurableAt;
    @Column(name = "rpo_age_seconds")
    private Long rpoAgeSeconds;
    @Column(name = "rpo_overdue")
    private boolean rpoOverdue;
    @Column(name = "error_code")
    private String errorCode;
    @Column(name = "error_message", length = 4096)
    private String errorMessage;
    @Column(name = "status_json", length = 16777215, columnDefinition = "MEDIUMTEXT")
    private String statusJson;
    @Temporal(TemporalType.TIMESTAMP)
    private Date created = new Date();
    @Temporal(TemporalType.TIMESTAMP)
    private Date updated;

    protected DrPlanRuntimeVO() {
    }

    public DrPlanRuntimeVO(long planId) {
        this.planId = planId;
    }

    @Override
    public long getId() { return id; }
    public long getPlanId() { return planId; }
    public String getEngineRunUuid() { return engineRunUuid; }
    public long getRuntimeGeneration() { return runtimeGeneration; }
    public String getSchedulerState() { return schedulerState; }
    public String getSchedulerDesiredState() { return schedulerDesiredState; }
    public String getSchedulerServiceUnit() { return schedulerServiceUnit; }
    public String getSchedulerUnitActiveState() { return schedulerUnitActiveState; }
    public String getSchedulerUnitSubState() { return schedulerUnitSubState; }
    public Long getSchedulerUnitMainPid() { return schedulerUnitMainPid; }
    public String getSchedulerCgroup() { return schedulerCgroup; }
    public String getSchedulerRecoveryState() { return schedulerRecoveryState; }
    public String getSchedulerRecoveryTrigger() { return schedulerRecoveryTrigger; }
    public int getSchedulerRecoveryAttempts() { return schedulerRecoveryAttempts; }
    public String getSchedulerRecoveryErrorCode() { return schedulerRecoveryErrorCode; }
    public String getSchedulerRecoveryErrorMessage() { return schedulerRecoveryErrorMessage; }
    public Date getSchedulerRecoveredAt() { return schedulerRecoveredAt; }
    public boolean isSchedulerPidAlive() { return schedulerPidAlive; }
    public String getSchedulerSessionUuid() { return schedulerSessionUuid; }
    public long getSchedulerLeaseEpoch() { return schedulerLeaseEpoch; }
    public long getAuthoritySequence() { return authoritySequence; }
    public Long getPlanCycleSequence() { return planCycleSequence; }
    public String getSchedulerHealthState() { return schedulerHealthState; }
    public String getReplicationActivityState() { return replicationActivityState; }
    public String getActiveWorkerRunUuid() { return activeWorkerRunUuid; }
    public Long getActiveWorkerPid() { return activeWorkerPid; }
    public Long getActiveWorkerStartTicks() { return activeWorkerStartTicks; }
    public Date getWorkerHeartbeatAt() { return workerHeartbeatAt; }
    public String getControlRequestRunUuid() { return controlRequestRunUuid; }
    public boolean isOwnerMatched() { return ownerMatched; }
    public String getWorkerState() { return workerState; }
    public String getWorkerIdentityState() { return workerIdentityState; }
    public String getWorkerLivenessState() { return workerLivenessState; }
    public String getWorkerLaunchNonce() { return workerLaunchNonce; }
    public Long getWorkerGeneration() { return workerGeneration; }
    public String getTransferActivityState() { return transferActivityState; }
    public Long getTransferPayloadBytes() { return transferPayloadBytes; }
    public Integer getTransferProgressSchemaVersion() { return transferProgressSchemaVersion; }
    public Long getTransferCycleSequence() { return transferCycleSequence; }
    public Long getTransferSampleSequence() { return transferSampleSequence; }
    public String getTransferPhase() { return transferPhase; }
    public String getTransferMode() { return transferMode; }
    public Long getTransferBytesTotal() { return transferBytesTotal; }
    public Long getTransferBytesProcessed() { return transferBytesProcessed; }
    public Long getTransferSourceReadBytes() { return transferSourceReadBytes; }
    public Long getTransferTargetWrittenBytes() { return transferTargetWrittenBytes; }
    public Long getTransferVerifiedBytes() { return transferVerifiedBytes; }
    public Double getTransferPercent() { return transferPercent; }
    public Long getTransferThroughputBps() { return transferThroughputBps; }
    public Long getTransferEtaSeconds() { return transferEtaSeconds; }
    public Integer getTransferCurrentDiskIndex() { return transferCurrentDiskIndex; }
    public Integer getTransferDiskCount() { return transferDiskCount; }
    public Boolean getTransferProgressEstimated() { return transferProgressEstimated; }
    public Date getTransferProgressSampledAt() { return transferProgressSampledAt; }
    public Boolean getTransferProgressStale() { return transferProgressStale; }
    public int getOwnedProcessCount() { return ownedProcessCount; }
    public boolean isRuntimeEndpointsDrained() { return runtimeEndpointsDrained; }
    public String getReconciliationState() { return reconciliationState; }
    public String getReconciliationRunUuid() { return reconciliationRunUuid; }
    public int getReconciliationChecks() { return reconciliationChecks; }
    public String getTerminalSource() { return terminalSource; }
    public Integer getTerminalVersion() { return terminalVersion; }
    public boolean isTerminalAuthoritative() { return terminalAuthoritative; }
    public Long getCurrentCycleSequence() { return currentCycleSequence; }
    public String getCurrentCycleState() { return currentCycleState; }
    public String getCurrentCycleMode() { return currentCycleMode; }
    public String getBaselineState() { return baselineState; }
    public String getReseedReason() { return reseedReason; }
    public String getLastModeDecisionCode() { return lastModeDecisionCode; }
    public int getConsecutiveAutomaticReseedCount() { return consecutiveAutomaticReseedCount; }
    public Long getLatestCompletedCycleSequence() { return latestCompletedCycleSequence; }
    public Boolean getLatestCompletedIncrementalVerified() { return latestCompletedIncrementalVerified; }
    public String getProjectionIntegrityState() { return projectionIntegrityState; }
    public String getProjectionIntegrityCode() { return projectionIntegrityCode; }
    public Long getProjectionIntegritySequence() { return projectionIntegritySequence; }
    public String getNbdTeardownState() { return nbdTeardownState; }
    public int getNbdQuarantinedDeviceCount() { return nbdQuarantinedDeviceCount; }
    public String getNbdTeardownErrorCode() { return nbdTeardownErrorCode; }
    public String getNbdTeardownErrorMessage() { return nbdTeardownErrorMessage; }
    public String getProtectionState() { return protectionState; }
    public String getFreshnessState() { return freshnessState; }
    public Date getSchedulerNextRunAt() { return schedulerNextRunAt; }
    public Integer getSchedulerExecutionBudgetSeconds() { return schedulerExecutionBudgetSeconds; }
    public Integer getSchedulerCycleWallDurationSeconds() { return schedulerCycleWallDurationSeconds; }
    public Date getLastStatusAt() { return lastStatusAt; }
    public Date getLastSourceCheckpointAt() { return lastSourceCheckpointAt; }
    public Date getLastTargetDurableAt() { return lastTargetDurableAt; }
    public Long getRpoAgeSeconds() { return rpoAgeSeconds; }
    public boolean isRpoOverdue() { return rpoOverdue; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public String getStatusJson() { return statusJson; }
    public Date getCreated() { return created; }
    public Date getUpdated() { return updated; }

    public void setEngineRunUuid(String value) { engineRunUuid = value; }
    public void setRuntimeGeneration(long value) { runtimeGeneration = value; }
    public void setSchedulerState(String value) { schedulerState = value; }
    public void setSchedulerDesiredState(String value) { schedulerDesiredState = value; }
    public void setSchedulerServiceUnit(String value) { schedulerServiceUnit = value; }
    public void setSchedulerUnitActiveState(String value) { schedulerUnitActiveState = value; }
    public void setSchedulerUnitSubState(String value) { schedulerUnitSubState = value; }
    public void setSchedulerUnitMainPid(Long value) { schedulerUnitMainPid = value; }
    public void setSchedulerCgroup(String value) { schedulerCgroup = value; }
    public void setSchedulerRecoveryState(String value) { schedulerRecoveryState = value; }
    public void setSchedulerRecoveryTrigger(String value) { schedulerRecoveryTrigger = value; }
    public void setSchedulerRecoveryAttempts(int value) { schedulerRecoveryAttempts = value; }
    public void setSchedulerRecoveryErrorCode(String value) { schedulerRecoveryErrorCode = value; }
    public void setSchedulerRecoveryErrorMessage(String value) { schedulerRecoveryErrorMessage = value; }
    public void setSchedulerRecoveredAt(Date value) { schedulerRecoveredAt = value; }
    public void setSchedulerPidAlive(boolean value) { schedulerPidAlive = value; }
    public void setSchedulerSessionUuid(String value) { schedulerSessionUuid = value; }
    public void setSchedulerLeaseEpoch(long value) { schedulerLeaseEpoch = value; }
    public void setAuthoritySequence(long value) { authoritySequence = value; }
    public void setPlanCycleSequence(Long value) { planCycleSequence = value; }
    public void setSchedulerHealthState(String value) { schedulerHealthState = value; }
    public void setReplicationActivityState(String value) { replicationActivityState = value; }
    public void setActiveWorkerRunUuid(String value) { activeWorkerRunUuid = value; }
    public void setActiveWorkerPid(Long value) { activeWorkerPid = value; }
    public void setActiveWorkerStartTicks(Long value) { activeWorkerStartTicks = value; }
    public void setWorkerHeartbeatAt(Date value) { workerHeartbeatAt = value; }
    public void setControlRequestRunUuid(String value) { controlRequestRunUuid = value; }
    public void setOwnerMatched(boolean value) { ownerMatched = value; }
    public void setWorkerState(String value) { workerState = value; }
    public void setWorkerIdentityState(String value) { workerIdentityState = value; }
    public void setWorkerLivenessState(String value) { workerLivenessState = value; }
    public void setWorkerLaunchNonce(String value) { workerLaunchNonce = value; }
    public void setWorkerGeneration(Long value) { workerGeneration = value; }
    public void setTransferActivityState(String value) { transferActivityState = value; }
    public void setTransferPayloadBytes(Long value) { transferPayloadBytes = value; }
    public void setTransferProgressSchemaVersion(Integer value) { transferProgressSchemaVersion = value; }
    public void setTransferCycleSequence(Long value) { transferCycleSequence = value; }
    public void setTransferSampleSequence(Long value) { transferSampleSequence = value; }
    public void setTransferPhase(String value) { transferPhase = value; }
    public void setTransferMode(String value) { transferMode = value; }
    public void setTransferBytesTotal(Long value) { transferBytesTotal = value; }
    public void setTransferBytesProcessed(Long value) { transferBytesProcessed = value; }
    public void setTransferSourceReadBytes(Long value) { transferSourceReadBytes = value; }
    public void setTransferTargetWrittenBytes(Long value) { transferTargetWrittenBytes = value; }
    public void setTransferVerifiedBytes(Long value) { transferVerifiedBytes = value; }
    public void setTransferPercent(Double value) { transferPercent = value; }
    public void setTransferThroughputBps(Long value) { transferThroughputBps = value; }
    public void setTransferEtaSeconds(Long value) { transferEtaSeconds = value; }
    public void setTransferCurrentDiskIndex(Integer value) { transferCurrentDiskIndex = value; }
    public void setTransferDiskCount(Integer value) { transferDiskCount = value; }
    public void setTransferProgressEstimated(Boolean value) { transferProgressEstimated = value; }
    public void setTransferProgressSampledAt(Date value) { transferProgressSampledAt = value; }
    public void setTransferProgressStale(Boolean value) { transferProgressStale = value; }
    public void setOwnedProcessCount(int value) { ownedProcessCount = value; }
    public void setRuntimeEndpointsDrained(boolean value) { runtimeEndpointsDrained = value; }
    public void setReconciliationState(String value) { reconciliationState = value; }
    public void setReconciliationRunUuid(String value) { reconciliationRunUuid = value; }
    public void setReconciliationChecks(int value) { reconciliationChecks = value; }
    public void setTerminalSource(String value) { terminalSource = value; }
    public void setTerminalVersion(Integer value) { terminalVersion = value; }
    public void setTerminalAuthoritative(boolean value) { terminalAuthoritative = value; }
    public void setCurrentCycleSequence(Long value) { currentCycleSequence = value; }
    public void setCurrentCycleState(String value) { currentCycleState = value; }
    public void setCurrentCycleMode(String value) { currentCycleMode = value; }
    public void setBaselineState(String value) { baselineState = value; }
    public void setReseedReason(String value) { reseedReason = value; }
    public void setLastModeDecisionCode(String value) { lastModeDecisionCode = value; }
    public void setConsecutiveAutomaticReseedCount(int value) { consecutiveAutomaticReseedCount = value; }
    public void setLatestCompletedCycleSequence(Long value) { latestCompletedCycleSequence = value; }
    public void setLatestCompletedIncrementalVerified(Boolean value) { latestCompletedIncrementalVerified = value; }
    public void setProjectionIntegrityState(String value) { projectionIntegrityState = value; }
    public void setProjectionIntegrityCode(String value) { projectionIntegrityCode = value; }
    public void setProjectionIntegritySequence(Long value) { projectionIntegritySequence = value; }
    public void setNbdTeardownState(String value) { nbdTeardownState = value; }
    public void setNbdQuarantinedDeviceCount(int value) { nbdQuarantinedDeviceCount = value; }
    public void setNbdTeardownErrorCode(String value) { nbdTeardownErrorCode = value; }
    public void setNbdTeardownErrorMessage(String value) { nbdTeardownErrorMessage = value; }
    public void setProtectionState(String value) { protectionState = value; }
    public void setFreshnessState(String value) { freshnessState = value; }
    public void setSchedulerNextRunAt(Date value) { schedulerNextRunAt = value; }
    public void setSchedulerExecutionBudgetSeconds(Integer value) { schedulerExecutionBudgetSeconds = value; }
    public void setSchedulerCycleWallDurationSeconds(Integer value) { schedulerCycleWallDurationSeconds = value; }
    public void setLastStatusAt(Date value) { lastStatusAt = value; }
    public void setLastSourceCheckpointAt(Date value) { lastSourceCheckpointAt = value; }
    public void setLastTargetDurableAt(Date value) { lastTargetDurableAt = value; }
    public void setRpoAgeSeconds(Long value) { rpoAgeSeconds = value; }
    public void setRpoOverdue(boolean value) { rpoOverdue = value; }
    public void setErrorCode(String value) { errorCode = value; }
    public void setErrorMessage(String value) { errorMessage = value; }
    public void setStatusJson(String value) { statusJson = value; }
    public void markUpdated() { updated = new Date(); }
}
