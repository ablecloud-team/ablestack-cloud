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
@Table(name = "dr_resource_lease")
public class DrResourceLeaseVO implements InternalIdentity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private long id;

    @Column(name = "uuid")
    private String uuid = UUID.randomUUID().toString();

    @Column(name = "resource_key")
    private String resourceKey;

    @Column(name = "operation_class")
    private String operationClass;

    @Column(name = "plan_id")
    private long planId;

    @Column(name = "run_id")
    private long runId;

    @Column(name = "state")
    private String state;

    @Column(name = "expires_at")
    @Temporal(TemporalType.TIMESTAMP)
    private Date expiresAt;

    @Column(name = "created")
    @Temporal(TemporalType.TIMESTAMP)
    private Date created = new Date();

    @Column(name = "updated")
    @Temporal(TemporalType.TIMESTAMP)
    private Date updated = new Date();

    protected DrResourceLeaseVO() {
    }

    public DrResourceLeaseVO(String resourceKey, String operationClass, long planId, long runId, Date expiresAt) {
        this.resourceKey = resourceKey;
        this.operationClass = operationClass;
        this.planId = planId;
        this.runId = runId;
        this.expiresAt = expiresAt;
        this.state = "ACTIVE";
    }

    @Override public long getId() { return id; }
    public String getUuid() { return uuid; }
    public String getResourceKey() { return resourceKey; }
    public String getOperationClass() { return operationClass; }
    public long getPlanId() { return planId; }
    public long getRunId() { return runId; }
    public String getState() { return state; }
    public Date getExpiresAt() { return expiresAt; }
    public Date getCreated() { return created; }
    public Date getUpdated() { return updated; }
    public void setState(String state) { this.state = state; this.updated = new Date(); }
    public void setExpiresAt(Date expiresAt) { this.expiresAt = expiresAt; this.updated = new Date(); }
}
