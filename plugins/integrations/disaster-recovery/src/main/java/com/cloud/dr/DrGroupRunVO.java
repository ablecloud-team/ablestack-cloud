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
@Table(name = "dr_group_run")
public class DrGroupRunVO implements InternalIdentity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "id") private long id;
    @Column(name = "uuid") private String uuid = UUID.randomUUID().toString();
    @Column(name = "group_uuid") private String groupUuid;
    @Column(name = "group_name") private String groupName;
    @Column(name = "action") private String action;
    @Column(name = "state") private String state;
    @Column(name = "plan_ids_json", length = 65535) private String planIdsJson;
    @Column(name = "progress_json", length = 16777215) private String progressJson;
    @Column(name = "max_parallel") private int maxParallel;
    @Column(name = "quiesce_required") private boolean quiesceRequired;
    @Column(name = "total_count") private int totalCount;
    @Column(name = "succeeded_count") private int succeededCount;
    @Column(name = "failed_count") private int failedCount;
    @Column(name = "created") @Temporal(TemporalType.TIMESTAMP) private Date created = new Date();
    @Column(name = "updated") @Temporal(TemporalType.TIMESTAMP) private Date updated = new Date();
    @Column(name = "completed") @Temporal(TemporalType.TIMESTAMP) private Date completed;

    protected DrGroupRunVO() { }

    public DrGroupRunVO(String groupUuid, String groupName, String action, String planIdsJson,
            int maxParallel, boolean quiesceRequired, int totalCount) {
        this.groupUuid = groupUuid;
        this.groupName = groupName;
        this.action = action;
        this.planIdsJson = planIdsJson;
        this.maxParallel = maxParallel;
        this.quiesceRequired = quiesceRequired;
        this.totalCount = totalCount;
        this.state = "QUEUED";
    }

    @Override public long getId() { return id; }
    public String getUuid() { return uuid; }
    public String getGroupUuid() { return groupUuid; }
    public String getGroupName() { return groupName; }
    public String getAction() { return action; }
    public String getState() { return state; }
    public String getPlanIdsJson() { return planIdsJson; }
    public String getProgressJson() { return progressJson; }
    public int getMaxParallel() { return maxParallel; }
    public boolean isQuiesceRequired() { return quiesceRequired; }
    public int getTotalCount() { return totalCount; }
    public int getSucceededCount() { return succeededCount; }
    public int getFailedCount() { return failedCount; }
    public Date getCreated() { return created; }
    public Date getUpdated() { return updated; }
    public Date getCompleted() { return completed; }
    public void setState(String value) { state = value; markUpdated(); }
    public void setProgressJson(String value) { progressJson = value; markUpdated(); }
    public void setSucceededCount(int value) { succeededCount = value; markUpdated(); }
    public void setFailedCount(int value) { failedCount = value; markUpdated(); }
    public void setCompleted(Date value) { completed = value; markUpdated(); }
    public void markUpdated() { updated = new Date(); }
}
