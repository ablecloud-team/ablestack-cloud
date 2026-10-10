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

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Table;
import javax.persistence.Id;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;
import java.util.UUID;

@Entity
@Table(name = "storage_service_operation")
public class StorageServiceOperationVO implements StorageServiceOperation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private long id;
    @Column(name = "uuid")
    private String uuid = UUID.randomUUID().toString();
    @Column(name = "instance_id")
    private long instanceId;
    @Column(name = "request_key")
    private String requestKey;
    @Column(name = "action")
    private String action;
    @Column(name = "state")
    private String state;
    @Column(name = "phase")
    private String phase;
    @Column(name = "revision")
    private long revision;
    @Column(name = "progress")
    private int progress;
    @Column(name = "previous_snapshot_json", length = 16777215, columnDefinition = "LONGTEXT")
    private String previousSnapshotJson;
    public String getPreviousSnapshotJson() { return previousSnapshotJson; }
    public void setPreviousSnapshotJson(String value) { previousSnapshotJson = value; }
    @Column(name = "snapshot_json", length = 16777215, columnDefinition = "LONGTEXT")
    private String snapshotJson;
    @Column(name = "result_json", length = 16777215, columnDefinition = "LONGTEXT")
    private String resultJson;
    @Column(name = "diagnostic", length = 8192, columnDefinition = "TEXT")
    private String diagnostic;
    @Column(name = "created_by")
    private long createdBy;
    @Column(name = "created")
    @Temporal(TemporalType.TIMESTAMP)
    private java.util.Date created = new java.util.Date();
    @Column(name = "heartbeat")
    @Temporal(TemporalType.TIMESTAMP)
    private java.util.Date heartbeat = new java.util.Date();
    @Column(name = "completed")
    @Temporal(TemporalType.TIMESTAMP)
    private java.util.Date completed;
    public StorageServiceOperationVO() { }
    public long getId() { return id; }
    public String getUuid() { return uuid; }
    public long getInstanceId() { return instanceId; }
    public void setInstanceId(long value) { instanceId = value; }
    public String getRequestKey() { return requestKey; }
    public void setRequestKey(String value) { requestKey = value; }
    public String getAction() { return action; }
    public void setAction(String value) { action = value; }
    public String getState() { return state; }
    public void setState(String value) { state = value; }
    public String getPhase() { return phase; }
    public void setPhase(String value) { phase = value; }
    public long getRevision() { return revision; }
    public void setRevision(long value) { revision = value; }
    public int getProgress() { return progress; }
    public void setProgress(int value) { progress = value; }
    public String getSnapshotJson() { return snapshotJson; }
    public void setSnapshotJson(String value) { snapshotJson = value; }
    public String getResultJson() { return resultJson; }
    public void setResultJson(String value) { resultJson = value; }
    public String getDiagnostic() { return diagnostic; }
    public void setDiagnostic(String value) { diagnostic = value; }
    public long getCreatedBy() { return createdBy; }
    public void setCreatedBy(long value) { createdBy = value; }
    public java.util.Date getCreated() { return created; }
    public java.util.Date getHeartbeat() { return heartbeat; }
    public void setHeartbeat(java.util.Date value) { heartbeat = value; }
    public java.util.Date getCompleted() { return completed; }
    public void setCompleted(java.util.Date value) { completed = value; }
}
