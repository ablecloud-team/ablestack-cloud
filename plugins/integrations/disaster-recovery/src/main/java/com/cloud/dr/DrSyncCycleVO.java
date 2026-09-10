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
@Table(name = "dr_sync_cycle")
public class DrSyncCycleVO implements InternalIdentity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;
    private String uuid = UUID.randomUUID().toString();
    @Column(name = "plan_id")
    private long planId;
    @Column(name = "run_id")
    private Long runId;
    @Column(name = "engine_run_uuid")
    private String engineRunUuid;
    @Column(name = "scheduler_session_uuid")
    private String schedulerSessionUuid;
    @Column(name = "scheduler_lease_epoch")
    private Long schedulerLeaseEpoch;
    @Column(name = "authority_sequence")
    private Long authoritySequence;
    private long sequence;
    @Column(name = "cycle_token")
    private String cycleToken;
    @Column(name = "requested_mode")
    private String requestedMode;
    @Column(name = "effective_mode")
    private String effectiveMode;
    private String state;
    @Column(name = "commit_state")
    private String commitState;
    @Column(name = "baseline_generation")
    private Long baselineGeneration;
    @Column(name = "baseline_state")
    private String baselineState;
    @Column(name = "reseed_reason")
    private String reseedReason;
    @Column(name = "automatic_reseed")
    private Boolean automaticReseed;
    @Column(name = "mode_decision_code")
    private String modeDecisionCode;
    @Column(name = "invalid_baseline_disk_count")
    private Integer invalidBaselineDiskCount;
    @Column(name = "incremental_verified")
    private Boolean incrementalVerified;
    @Column(name = "metrics_estimated")
    private Boolean metricsEstimated;
    @Column(name = "virtual_bytes")
    private Long virtualBytes;
    @Column(name = "changed_bytes")
    private Long changedBytes;
    @Column(name = "source_read_bytes")
    private Long sourceReadBytes;
    @Column(name = "target_written_bytes")
    private Long targetWrittenBytes;
    @Column(name = "transfer_payload_bytes")
    private Long transferPayloadBytes;
    @Column(name = "changed_extent_count")
    private Long changedExtentCount;
    @Column(name = "duration_ms")
    private Long durationMs;
    @Column(name = "throughput_bps")
    private Long throughputBps;
    @Column(name = "nbd_teardown_state")
    private String nbdTeardownState;
    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "nbd_teardown_started_at")
    private Date nbdTeardownStartedAt;
    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "nbd_teardown_completed_at")
    private Date nbdTeardownCompletedAt;
    @Column(name = "nbd_teardown_duration_ms")
    private Long nbdTeardownDurationMs;
    @Column(name = "nbd_source_device_count")
    private Integer nbdSourceDeviceCount;
    @Column(name = "nbd_target_device_count")
    private Integer nbdTargetDeviceCount;
    @Column(name = "nbd_quarantined_device_count")
    private int nbdQuarantinedDeviceCount;
    @Column(name = "nbd_teardown_error_code")
    private String nbdTeardownErrorCode;
    @Column(name = "nbd_teardown_error_message", length = 4096)
    private String nbdTeardownErrorMessage;
    @Column(name = "error_code")
    private String errorCode;
    @Column(name = "error_message", length = 4096)
    private String errorMessage;
    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "source_checkpoint_at")
    private Date sourceCheckpointAt;
    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "target_durable_at")
    private Date targetDurableAt;
    @Temporal(TemporalType.TIMESTAMP)
    private Date started;
    @Temporal(TemporalType.TIMESTAMP)
    private Date completed;
    @Temporal(TemporalType.TIMESTAMP)
    private Date created = new Date();
    @Temporal(TemporalType.TIMESTAMP)
    private Date updated;
    @Temporal(TemporalType.TIMESTAMP)
    private Date removed;

    protected DrSyncCycleVO() {
    }

    public DrSyncCycleVO(long planId, String engineRunUuid, long sequence) {
        this.planId = planId;
        this.engineRunUuid = engineRunUuid;
        this.sequence = sequence;
    }

    @Override
    public long getId() { return id; }
    public String getUuid() { return uuid; }
    public long getPlanId() { return planId; }
    public Long getRunId() { return runId; }
    public String getEngineRunUuid() { return engineRunUuid; }
    public String getSchedulerSessionUuid() { return schedulerSessionUuid; }
    public Long getSchedulerLeaseEpoch() { return schedulerLeaseEpoch; }
    public Long getAuthoritySequence() { return authoritySequence; }
    public long getSequence() { return sequence; }
    public long getCheckpointSequence() {
        if (cycleToken != null) {
            int separator = cycleToken.lastIndexOf(':');
            if (separator >= 0 && separator + 1 < cycleToken.length()) {
                try {
                    long checkpointSequence = Long.parseLong(cycleToken.substring(separator + 1));
                    if (checkpointSequence > 0) {
                        return checkpointSequence;
                    }
                } catch (NumberFormatException ignored) {
                    // Legacy tokens fall back to the canonical Cloud sequence.
                }
            }
        }
        return sequence;
    }
    public String getCycleToken() { return cycleToken; }
    public String getRequestedMode() { return requestedMode; }
    public String getEffectiveMode() { return effectiveMode; }
    public String getState() { return state; }
    public String getCommitState() { return commitState; }
    public Long getBaselineGeneration() { return baselineGeneration; }
    public String getBaselineState() { return baselineState; }
    public String getReseedReason() { return reseedReason; }
    public Boolean getAutomaticReseed() { return automaticReseed; }
    public String getModeDecisionCode() { return modeDecisionCode; }
    public Integer getInvalidBaselineDiskCount() { return invalidBaselineDiskCount; }
    public Boolean getIncrementalVerified() { return incrementalVerified; }
    public Boolean getMetricsEstimated() { return metricsEstimated; }
    public Long getVirtualBytes() { return virtualBytes; }
    public Long getChangedBytes() { return changedBytes; }
    public Long getSourceReadBytes() { return sourceReadBytes; }
    public Long getTargetWrittenBytes() { return targetWrittenBytes; }
    public Long getTransferPayloadBytes() { return transferPayloadBytes; }
    public Long getChangedExtentCount() { return changedExtentCount; }
    public Long getDurationMs() { return durationMs; }
    public Long getThroughputBps() { return throughputBps; }
    public String getNbdTeardownState() { return nbdTeardownState; }
    public Date getNbdTeardownStartedAt() { return nbdTeardownStartedAt; }
    public Date getNbdTeardownCompletedAt() { return nbdTeardownCompletedAt; }
    public Long getNbdTeardownDurationMs() { return nbdTeardownDurationMs; }
    public Integer getNbdSourceDeviceCount() { return nbdSourceDeviceCount; }
    public Integer getNbdTargetDeviceCount() { return nbdTargetDeviceCount; }
    public int getNbdQuarantinedDeviceCount() { return nbdQuarantinedDeviceCount; }
    public String getNbdTeardownErrorCode() { return nbdTeardownErrorCode; }
    public String getNbdTeardownErrorMessage() { return nbdTeardownErrorMessage; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public Date getSourceCheckpointAt() { return sourceCheckpointAt; }
    public Date getTargetDurableAt() { return targetDurableAt; }
    public Date getStarted() { return started; }
    public Date getCompleted() { return completed; }
    public Date getCreated() { return created; }
    public Date getUpdated() { return updated; }
    public Date getRemoved() { return removed; }

    public void setRunId(Long value) { runId = value; }
    public void setSchedulerSessionUuid(String value) { schedulerSessionUuid = value; }
    public void setSchedulerLeaseEpoch(Long value) { schedulerLeaseEpoch = value; }
    public void setAuthoritySequence(Long value) { authoritySequence = value; }
    public void setCycleToken(String value) { cycleToken = value; }
    public void setRequestedMode(String value) { requestedMode = value; }
    public void setEffectiveMode(String value) { effectiveMode = value; }
    public void setState(String value) { state = value; }
    public void setCommitState(String value) { commitState = value; }
    public void setBaselineGeneration(Long value) { baselineGeneration = value; }
    public void setBaselineState(String value) { baselineState = value; }
    public void setReseedReason(String value) { reseedReason = value; }
    public void setAutomaticReseed(Boolean value) { automaticReseed = value; }
    public void setModeDecisionCode(String value) { modeDecisionCode = value; }
    public void setInvalidBaselineDiskCount(Integer value) { invalidBaselineDiskCount = value; }
    public void setIncrementalVerified(Boolean value) { incrementalVerified = value; }
    public void setMetricsEstimated(Boolean value) { metricsEstimated = value; }
    public void setVirtualBytes(Long value) { virtualBytes = value; }
    public void setChangedBytes(Long value) { changedBytes = value; }
    public void setSourceReadBytes(Long value) { sourceReadBytes = value; }
    public void setTargetWrittenBytes(Long value) { targetWrittenBytes = value; }
    public void setTransferPayloadBytes(Long value) { transferPayloadBytes = value; }
    public void setChangedExtentCount(Long value) { changedExtentCount = value; }
    public void setDurationMs(Long value) { durationMs = value; }
    public void setThroughputBps(Long value) { throughputBps = value; }
    public void setNbdTeardownState(String value) { nbdTeardownState = value; }
    public void setNbdTeardownStartedAt(Date value) { nbdTeardownStartedAt = value; }
    public void setNbdTeardownCompletedAt(Date value) { nbdTeardownCompletedAt = value; }
    public void setNbdTeardownDurationMs(Long value) { nbdTeardownDurationMs = value; }
    public void setNbdSourceDeviceCount(Integer value) { nbdSourceDeviceCount = value; }
    public void setNbdTargetDeviceCount(Integer value) { nbdTargetDeviceCount = value; }
    public void setNbdQuarantinedDeviceCount(int value) { nbdQuarantinedDeviceCount = value; }
    public void setNbdTeardownErrorCode(String value) { nbdTeardownErrorCode = value; }
    public void setNbdTeardownErrorMessage(String value) { nbdTeardownErrorMessage = value; }
    public void setErrorCode(String value) { errorCode = value; }
    public void setErrorMessage(String value) { errorMessage = value; }
    public void setSourceCheckpointAt(Date value) { sourceCheckpointAt = value; }
    public void setTargetDurableAt(Date value) { targetDurableAt = value; }
    public void setStarted(Date value) { started = value; }
    public void setCompleted(Date value) { completed = value; }
    public void markUpdated() { updated = new Date(); }
}
