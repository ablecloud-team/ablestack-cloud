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
import javax.persistence.*;

@Entity
@Table(name = "storage_service_runtime_bundle_event")
public class StorageServiceRuntimeBundleEventVO {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;
    @Column(name = "uuid", length = 40)
    private String uuid = UUID.randomUUID().toString();
    @Column(name = "bundle_id")
    private long bundleId;
    @Column(name = "request_id", length = 128)
    private String requestId;
    @Column(name = "actor_user_id")
    private Long actorUserId;
    @Column(name = "account_id")
    private Long accountId;
    @Column(name = "action", length = 32)
    private String action;
    @Column(name = "from_state", length = 32)
    private String fromState;
    @Column(name = "to_state", length = 32)
    private String toState;
    @Column(name = "from_revision")
    private long fromRevision;
    @Column(name = "to_revision")
    private long toRevision;
    @Column(name = "reason", length = 1024)
    private String reason;
    @Column(name = "result_code", length = 128)
    private String resultCode;
    @Column(name = "validation_id")
    private Long validationId;
    @Column(name = "job_id")
    private Long jobId;
    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "created")
    private Date created = new Date();

    public Long getId() { return id; }
    public String getUuid() { return uuid; }
    public long getBundleId() { return bundleId; }
    public void setBundleId(long value) { bundleId = value; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String value) { requestId = value; }
    public Long getActorUserId() { return actorUserId; }
    public void setActorUserId(Long value) { actorUserId = value; }
    public Long getAccountId() { return accountId; }
    public void setAccountId(Long value) { accountId = value; }
    public String getAction() { return action; }
    public void setAction(String value) { action = value; }
    public String getFromState() { return fromState; }
    public void setFromState(String value) { fromState = value; }
    public String getToState() { return toState; }
    public void setToState(String value) { toState = value; }
    public long getFromRevision() { return fromRevision; }
    public void setFromRevision(long value) { fromRevision = value; }
    public long getToRevision() { return toRevision; }
    public void setToRevision(long value) { toRevision = value; }
    public String getReason() { return reason; }
    public void setReason(String value) { reason = value; }
    public String getResultCode() { return resultCode; }
    public void setResultCode(String value) { resultCode = value; }
    public Long getValidationId() { return validationId; }
    public void setValidationId(Long value) { validationId = value; }
    public Long getJobId() { return jobId; }
    public void setJobId(Long value) { jobId = value; }
    public Date getCreated() { return created; }
    public void setCreated(Date value) { created = value; }
}
