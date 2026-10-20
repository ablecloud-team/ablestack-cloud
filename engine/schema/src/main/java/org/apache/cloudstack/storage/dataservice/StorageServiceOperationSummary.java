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

/** Read-only operation metadata. Never a replacement for a recovery authority's full row. */
public final class StorageServiceOperationSummary {
    private final long id, instanceId, revision, createdBy;
    private final String uuid, requestKey, action, state, phase, diagnostic;
    private final int progress;
    private final Date created, heartbeat, completed;

    public StorageServiceOperationSummary(long id, String uuid, long instanceId, String requestKey, String action,
            String state, String phase, long revision, int progress, long createdBy, Date created, Date heartbeat,
            Date completed, String diagnostic) {
        this.id=id;this.uuid=uuid;this.instanceId=instanceId;this.requestKey=requestKey;this.action=action;
        this.state=state;this.phase=phase;this.revision=revision;this.progress=progress;this.createdBy=createdBy;
        this.created=copy(created);this.heartbeat=copy(heartbeat);this.completed=copy(completed);this.diagnostic=diagnostic;
    }
    private static Date copy(Date value) { return value==null?null:new Date(value.getTime()); }
    public static StorageServiceOperationSummary from(StorageServiceOperationVO row) {
        return new StorageServiceOperationSummary(row.getId(),row.getUuid(),row.getInstanceId(),row.getRequestKey(),
                row.getAction(),row.getState(),row.getPhase(),row.getRevision(),row.getProgress(),row.getCreatedBy(),
                row.getCreated(),row.getHeartbeat(),row.getCompleted(),row.getDiagnostic());
    }
    public static java.util.List<StorageServiceOperationSummary> requireScope(java.util.List<StorageServiceOperationSummary> rows,long instanceId) {
        if(rows==null || rows.stream().anyMatch(row->row==null || row.getInstanceId()!=instanceId))
            throw new com.cloud.utils.exception.CloudRuntimeException("Operation metadata scope is unavailable");
        return new java.util.ArrayList<>(rows);
    }
    public static StorageServiceOperationVO requireFull(
            org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao dao,StorageServiceOperationSummary summary) {
        StorageServiceOperationVO row=dao.findById(summary.getId());
        if(!summary.matches(row))throw new com.cloud.utils.exception.CloudRuntimeException("Selected operation authority changed");
        return row;
    }
    public boolean matches(StorageServiceOperationVO row) {
        return row!=null && id==row.getId() && instanceId==row.getInstanceId() && revision==row.getRevision()
                && createdBy==row.getCreatedBy() && java.util.Objects.equals(uuid,row.getUuid())
                && java.util.Objects.equals(requestKey,row.getRequestKey()) && java.util.Objects.equals(action,row.getAction())
                && java.util.Objects.equals(state,row.getState()) && java.util.Objects.equals(phase,row.getPhase());
    }
    public boolean stale(long now) {
        Date observed=heartbeat==null?created:heartbeat;
        return "RUNNING".equals(state) && observed!=null && now-observed.getTime()>=120000;
    }
    public long getId() { return id; }
    public String getUuid() { return uuid; }
    public long getInstanceId() { return instanceId; }
    public String getRequestKey() { return requestKey; }
    public String getAction() { return action; }
    public String getState() { return state; }
    public String getPhase() { return phase; }
    public long getRevision() { return revision; }
    public int getProgress() { return progress; }
    public long getCreatedBy() { return createdBy; }
    public Date getCreated() { return copy(created); }
    public Date getHeartbeat() { return copy(heartbeat); }
    public Date getCompleted() { return copy(completed); }
    public String getDiagnostic() { return diagnostic; }
}
