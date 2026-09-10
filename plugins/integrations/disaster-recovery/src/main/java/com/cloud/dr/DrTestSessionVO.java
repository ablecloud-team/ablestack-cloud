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
@Table(name = "dr_test_session")
public class DrTestSessionVO implements InternalIdentity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;
    private String uuid = UUID.randomUUID().toString();
    @Column(name = "plan_id") private long planId;
    @Column(name = "run_id") private long runId;
    @Column(name = "cleanup_run_id") private Long cleanupRunId;
    private String state;
    @Column(name = "network_mode") private String networkMode;
    @Column(name = "network_id") private Long networkId;
    @Column(name = "target_vm_id") private Long targetVmId;
    @Column(name = "target_vm_uuid") private String targetVmUuid;
    @Column(name = "target_vm_name") private String targetVmName;
    @Column(name = "checkpoint_sequence") private Long checkpointSequence;
    @Column(name = "restore_point_ref", length = 1024) private String restorePointRef;
    @Column(name = "validation_mode") private String validationMode;
    @Column(name = "boot_timeout_seconds") private Integer bootTimeoutSeconds;
    @Column(name = "artifact_contract_version") private String artifactContractVersion;
    @Column(name = "artifact_manifest", length = 16777215) private String artifactManifest;
    @Column(name = "boot_validation_state") private String bootValidationState;
    @Column(name = "cleanup_required") private boolean cleanupRequired;
    @Column(name = "error_code") private String errorCode;
    @Column(name = "error_message") private String errorMessage;
    @Column(name = "details_json", length = 16777215) private String detailsJson;
    @Temporal(TemporalType.TIMESTAMP) private Date created = new Date();
    @Temporal(TemporalType.TIMESTAMP) private Date updated = new Date();
    @Temporal(TemporalType.TIMESTAMP) private Date removed;

    protected DrTestSessionVO() { }

    public DrTestSessionVO(long planId, long runId, String state) {
        this.planId = planId;
        this.runId = runId;
        this.state = state;
    }

    @Override public long getId() { return id; }
    public String getUuid() { return uuid; }
    public long getPlanId() { return planId; }
    public long getRunId() { return runId; }
    public Long getCleanupRunId() { return cleanupRunId; }
    public String getState() { return state; }
    public String getNetworkMode() { return networkMode; }
    public Long getNetworkId() { return networkId; }
    public Long getTargetVmId() { return targetVmId; }
    public String getTargetVmUuid() { return targetVmUuid; }
    public String getTargetVmName() { return targetVmName; }
    public Long getCheckpointSequence() { return checkpointSequence; }
    public String getRestorePointRef() { return restorePointRef; }
    public String getValidationMode() { return validationMode; }
    public Integer getBootTimeoutSeconds() { return bootTimeoutSeconds; }
    public String getArtifactContractVersion() { return artifactContractVersion; }
    public String getArtifactManifest() { return artifactManifest; }
    public String getBootValidationState() { return bootValidationState; }
    public boolean isCleanupRequired() { return cleanupRequired; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public String getDetailsJson() { return detailsJson; }
    public Date getCreated() { return created; }
    public Date getUpdated() { return updated; }
    public Date getRemoved() { return removed; }
    public void setCleanupRunId(Long value) { cleanupRunId = value; }
    public void setState(String value) { state = value; }
    public void setNetworkMode(String value) { networkMode = value; }
    public void setNetworkId(Long value) { networkId = value; }
    public void setTargetVmId(Long value) { targetVmId = value; }
    public void setTargetVmUuid(String value) { targetVmUuid = value; }
    public void setTargetVmName(String value) { targetVmName = value; }
    public void setCheckpointSequence(Long value) { checkpointSequence = value; }
    public void setRestorePointRef(String value) { restorePointRef = value; }
    public void setValidationMode(String value) { validationMode = value; }
    public void setBootTimeoutSeconds(Integer value) { bootTimeoutSeconds = value; }
    public void setArtifactContractVersion(String value) { artifactContractVersion = value; }
    public void setArtifactManifest(String value) { artifactManifest = value; }
    public void setBootValidationState(String value) { bootValidationState = value; }
    public void setCleanupRequired(boolean value) { cleanupRequired = value; }
    public void setErrorCode(String value) { errorCode = value; }
    public void setErrorMessage(String value) { errorMessage = value; }
    public void setDetailsJson(String value) { detailsJson = value; }
    public void markUpdated() { updated = new Date(); }
    public void setRemoved(Date value) { removed = value; }
}
