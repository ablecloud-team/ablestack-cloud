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
@Table(name = "dr_test_disk")
public class DrTestDiskVO implements InternalIdentity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private long id;
    @Column(name = "session_id") private long sessionId;
    @Column(name = "disk_index") private int diskIndex;
    private String provider;
    @Column(name = "artifact_ref") private String artifactRef;
    @Column(name = "target_volume_id") private Long targetVolumeId;
    @Column(name = "target_volume_uuid") private String targetVolumeUuid;
    private String state;
    @Column(name = "details_json", length = 16777215) private String detailsJson;
    @Temporal(TemporalType.TIMESTAMP) private Date created = new Date();
    @Temporal(TemporalType.TIMESTAMP) private Date updated = new Date();
    @Temporal(TemporalType.TIMESTAMP) private Date removed;

    protected DrTestDiskVO() { }
    public DrTestDiskVO(long sessionId, int diskIndex) { this.sessionId = sessionId; this.diskIndex = diskIndex; }
    @Override public long getId() { return id; }
    public long getSessionId() { return sessionId; }
    public int getDiskIndex() { return diskIndex; }
    public String getProvider() { return provider; }
    public String getArtifactRef() { return artifactRef; }
    public Long getTargetVolumeId() { return targetVolumeId; }
    public String getTargetVolumeUuid() { return targetVolumeUuid; }
    public String getState() { return state; }
    public String getDetailsJson() { return detailsJson; }
    public Date getRemoved() { return removed; }
    public void setProvider(String value) { provider = value; }
    public void setArtifactRef(String value) { artifactRef = value; }
    public void setTargetVolumeId(Long value) { targetVolumeId = value; }
    public void setTargetVolumeUuid(String value) { targetVolumeUuid = value; }
    public void setState(String value) { state = value; }
    public void setDetailsJson(String value) { detailsJson = value; }
    public void markUpdated() { updated = new Date(); }
    public void setRemoved(Date value) { removed = value; }
}
