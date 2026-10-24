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

package org.apache.cloudstack.storage.dataservice;

import java.util.Date;
import java.util.UUID;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Lob;
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;

import com.cloud.utils.db.GenericDao;

@Entity
@Table(name = "storage_service_runtime_upgrade")
public class StorageServiceRuntimeUpgradeVO implements StorageServiceRuntimeUpgrade {
    public enum State { RUNNING, PREFLIGHT_READY, OBSERVATION_PENDING, COMPLETE, ROLLED_BACK, FAILED, MANUAL_RECOVERY }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private long id;
    @Column(name = "uuid")
    private String uuid = UUID.randomUUID().toString();
    @Column(name = "instance_id")
    private long instanceId;
    @Column(name = "bundle_id")
    private long bundleId;
    @Column(name = "previous_bundle_id")
    private Long previousBundleId;
    @Column(name = "state")
    @Enumerated(EnumType.STRING)
    private State state = State.RUNNING;
    @Column(name = "phase")
    private String phase;
    @Column(name = "progress")
    private Integer progress = 0;
    @Column(name = "transaction_id")
    private String transactionId;
    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "started")
    private Date started = new Date();
    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "heartbeat")
    private Date heartbeat = new Date();
    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "completed")
    private Date completed;
    @Lob
    @Column(name = "preflight_json", length = 16777215, columnDefinition = "MEDIUMTEXT")
    private String preflightJson;
    @Lob
    @Column(name = "verification_json", length = 16777215, columnDefinition = "MEDIUMTEXT")
    private String verificationJson;
    @Lob
    @Column(name = "rollback_result_json", length = 16777215, columnDefinition = "MEDIUMTEXT")
    private String rollbackResultJson;
    @Column(name = "error_code")
    private String errorCode;
    @Column(name = "error_message")
    private String errorMessage;
    @Column(name = "created_by")
    private Long createdBy;
    @Column(name = GenericDao.CREATED_COLUMN)
    @Temporal(TemporalType.TIMESTAMP)
    private Date created = new Date();

    public StorageServiceRuntimeUpgradeVO() { }

    public StorageServiceRuntimeUpgradeVO(final long instanceId, final long bundleId,
            final Long previousBundleId, final String transactionId, final Long createdBy) {
        this.instanceId = instanceId;
        this.bundleId = bundleId;
        this.previousBundleId = previousBundleId;
        this.transactionId = transactionId;
        this.createdBy = createdBy;
        this.phase = "AVAILABLE";
    }

    public long getId() { return id; }
    public String getUuid() { return uuid; }
    public long getInstanceId() { return instanceId; }
    public long getBundleId() { return bundleId; }
    public Long getPreviousBundleId() { return previousBundleId; }
    public State getState() { return state; }
    public String getPhase() { return phase; }
    public Integer getProgress() { return progress; }
    public String getTransactionId() { return transactionId; }
    public Date getStarted() { return started; }
    public Date getHeartbeat() { return heartbeat; }
    public Date getCompleted() { return completed; }
    public String getPreflightJson() { return preflightJson; }
    public String getVerificationJson() { return verificationJson; }
    public String getRollbackResultJson() { return rollbackResultJson; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public Long getCreatedBy() { return createdBy; }
    public Date getCreated() { return created; }
    public void setState(final State value) { state = value; }
    public void setPhase(final String value) { phase = value; heartbeat = new Date(); }
    public void setProgress(final Integer value) { progress = value; heartbeat = new Date(); }
    public void setPreflightJson(final String value) { preflightJson = value; }
    public void setVerificationJson(final String value) { verificationJson = value; }
    public void setRollbackResultJson(final String value) { rollbackResultJson = value; }
    public void setErrorCode(final String value) { errorCode = value; }
    public void setErrorMessage(final String value) { errorMessage = value; }
    public void setCompleted(final Date value) { completed = value; }
}
