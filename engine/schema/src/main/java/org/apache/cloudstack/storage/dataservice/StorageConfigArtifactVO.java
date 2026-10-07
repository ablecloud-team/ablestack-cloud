// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
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
import javax.persistence.Table;
import javax.persistence.Id;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;

@Entity
@Table(name = "storage_service_config_artifact")
public class StorageConfigArtifactVO implements StorageConfigArtifact {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id") private long id;
    @Column(name = "uuid") private String uuid = UUID.randomUUID().toString();
    @Column(name = "instance_id") private long instanceId;
    @Column(name = "kind") private String kind;
    @Column(name = "state") private String state;
    @Column(name = "desired_revision") private long desiredRevision;
    @Column(name = "source_operation_id") private Long sourceOperationId;
    @Column(name = "metadata_json", length = 16777215, columnDefinition = "MEDIUMTEXT") private String metadataJson;
    @Column(name = "sha256") private String sha256;
    @Column(name = "size") private long size;
    @Column(name = "created_by") private long createdBy;
    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "created") private Date created = new Date();
    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "updated") private Date updated = new Date();
    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "expires") private Date expires;
    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "removed") private Date removed;
    public StorageConfigArtifactVO() { }
    public long getId() { return id; }
    public String getUuid() { return uuid; }
    public long getInstanceId() { return instanceId; }
    public void setInstanceId(long value) { instanceId = value; }
    public String getKind() { return kind; }
    public void setKind(String value) { kind = value; }
    public String getState() { return state; }
    public void setState(String value) { state = value; }
    public long getDesiredRevision() { return desiredRevision; }
    public void setDesiredRevision(long value) { desiredRevision = value; }
    public Long getSourceOperationId() { return sourceOperationId; }
    public void setSourceOperationId(Long value) { sourceOperationId = value; }
    public String getMetadataJson() { return metadataJson; }
    public void setMetadataJson(String value) { metadataJson = value; }
    public String getSha256() { return sha256; }
    public void setSha256(String value) { sha256 = value; }
    public long getSize() { return size; }
    public void setSize(long value) { size = value; }
    public long getCreatedBy() { return createdBy; }
    public void setCreatedBy(long value) { createdBy = value; }
    public Date getCreated() { return created; }
    public Date getUpdated() { return updated; }
    public void setUpdated(Date value) { updated = value; }
    public Date getExpires() { return expires; }
    public void setExpires(Date value) { expires = value; }
    public Date getRemoved() { return removed; }
}
