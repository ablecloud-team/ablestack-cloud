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
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Lob;
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;
import org.apache.cloudstack.api.InternalIdentity;
import org.apache.cloudstack.api.Identity;

/** Durable configuration outcome; first failure is write-once. No credentials belong here. */
@Entity
@Table(name = "storage_service_configuration_operation")
public class StorageServiceConfigurationOperationVO implements InternalIdentity, Identity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "id")
    private long id;
    @Column(name = "uuid") private String uuid = UUID.randomUUID().toString();
    @Column(name = "instance_id")
    private long instanceId;
    @Column(name = "account_id")
    private long accountId;
    @Column(name = "domain_id")
    private long domainId;
    @Column(name = "job_id")
    private Long jobId;
    @Column(name = "request_id")
    private String requestId;
    @Column(name = "request_hash")
    private String requestHash;
    @Column(name = "protocol")
    private String protocol;
    @Column(name = "resource_type")
    private String resourceType;
    @Column(name = "resource_uuid")
    private String resourceUuid;
    @Column(name = "action")
    private String action;
    @Column(name = "base_revision")
    private long baseRevision;
    @Column(name = "target_revision")
    private long targetRevision;
    @Column(name = "state")
    private String state;
    @Column(name = "phase")
    private String phase;
    @Lob
    @Column(name = "before_snapshot", length = 16777215, columnDefinition = "MEDIUMTEXT")
    private String beforeSnapshot;
    @Lob
    @Column(name = "candidate_snapshot", length = 16777215, columnDefinition = "MEDIUMTEXT")
    private String candidateSnapshot;
    @Lob
    @Column(name = "original_failure_json", length = 65535, columnDefinition = "TEXT")
    private String originalFailureJson;
    @Lob
    @Column(name = "rollback_result_json", length = 65535, columnDefinition = "TEXT")
    private String rollbackResultJson;
    @Lob
    @Column(name = "guest_observation_json", length = 65535, columnDefinition = "TEXT")
    private String guestObservationJson;
    @Column(name = "created")
    @Temporal(TemporalType.TIMESTAMP)
    private Date created = new Date();
    @Column(name = "updated")
    @Temporal(TemporalType.TIMESTAMP)
    private Date updated = new Date();
    @Column(name = "completed")
    @Temporal(TemporalType.TIMESTAMP)
    private Date completed;
    public StorageServiceConfigurationOperationVO() { }
    public long getId() { return id; }
    public String getUuid() { return uuid; }
    public long getInstanceId() { return instanceId; }
    public void setInstanceId(final long value) { instanceId = value; }
    public long getAccountId() { return accountId; }
    public void setAccountId(final long value) { accountId = value; }
    public long getDomainId() { return domainId; }
    public void setDomainId(final long value) { domainId = value; }
    public Long getJobId() { return jobId; }
    public void setJobId(final Long value) { jobId = value; }
    public String getRequestId() { return requestId; }
    public void setRequestId(final String value) { requestId = value; }
    public String getRequestHash() { return requestHash; }
    public void setRequestHash(final String value) { requestHash = value; }
    public String getProtocol() { return protocol; }
    public void setProtocol(final String value) { protocol = value; }
    public String getResourceType() { return resourceType; }
    public void setResourceType(final String value) { resourceType = value; }
    public String getResourceUuid() { return resourceUuid; }
    public void setResourceUuid(final String value) { resourceUuid = value; }
    public String getAction() { return action; }
    public void setAction(final String value) { action = value; }
    public long getBaseRevision() { return baseRevision; }
    public void setBaseRevision(final long value) { baseRevision = value; }
    public long getTargetRevision() { return targetRevision; }
    public void setTargetRevision(final long value) { targetRevision = value; }
    public String getState() { return state; }
    public void setState(final String value) { state = value; }
    public String getPhase() { return phase; }
    public void setPhase(final String value) { phase = value; }
    public String getBeforeSnapshot() { return beforeSnapshot; }
    public void setBeforeSnapshot(final String value) { beforeSnapshot = value; }
    public String getCandidateSnapshot() { return candidateSnapshot; }
    public void setCandidateSnapshot(final String value) { candidateSnapshot = value; }
    public String getOriginalFailureJson() { return originalFailureJson; }
    public void setOriginalFailureJson(final String value) { if (originalFailureJson == null) originalFailureJson = value; }
    public String getRollbackResultJson() { return rollbackResultJson; }
    public void setRollbackResultJson(final String value) { rollbackResultJson = value; }
    public String getGuestObservationJson() { return guestObservationJson; }
    public void setGuestObservationJson(final String value) { guestObservationJson = value; }
    public Date getCreated() { return created; }
    public void setCreated(final Date value) { created = value; }
    public Date getUpdated() { return updated; }
    public void setUpdated(final Date value) { updated = value; }
    public Date getCompleted() { return completed; }
    public void setCompleted(final Date value) { completed = value; }
}
