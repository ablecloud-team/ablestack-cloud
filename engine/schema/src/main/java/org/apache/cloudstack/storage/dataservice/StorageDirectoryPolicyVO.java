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
// specific language govening permissions and limitations
// under the License.

package org.apache.cloudstack.storage.dataservice;

import javax.persistence.*;
import java.util.Date;

@Entity
@Table(name = "storage_directory_policy")
public class StorageDirectoryPolicyVO implements StorageDirectoryPolicy {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "id")
    private long id;
    @Column(name = "uuid")
    private String uuid = java.util.UUID.randomUUID().toString();
    @Column(name = "instance_id")
    private long instanceId;
    @Column(name = "volume_id")
    private long volumeId;
    @Column(name = "canonical_path")
    private String canonicalPath;
    @Column(name = "canonical_hash")
    private String canonicalHash;
    @Column(name = "owner_uid")
    private long ownerUid;
    @Column(name = "owner_gid")
    private long ownerGid;
    @Column(name = "mode")
    private String mode;
    @Column(name = "revision")
    private long revision = 1;
    @Column(name = "last_recursive")
    private boolean lastRecursive;
    @Lob
    @Column(name = "metadata_json", length = 16777215, columnDefinition = "MEDIUMTEXT")
    private String metadataJson = "{}";
    @Column(name = "created") @Temporal(TemporalType.TIMESTAMP) private Date created = new Date();
    @Column(name = "updated") @Temporal(TemporalType.TIMESTAMP) private Date updated;
    public StorageDirectoryPolicyVO() { }
    public long getId() { return id; }
    public String getUuid() { return uuid; }
    public void setUuid(String value) { uuid = value; }
    public long getInstanceId() { return instanceId; }
    public void setInstanceId(long value) { instanceId = value; }
    public long getVolumeId() { return volumeId; }
    public void setVolumeId(long value) { volumeId = value; }
    public String getCanonicalPath() { return canonicalPath; }
    public void setCanonicalPath(String value) { canonicalPath = value; }
    public String getCanonicalHash() { return canonicalHash; }
    public void setCanonicalHash(String value) { canonicalHash = value; }
    public long getOwnerUid() { return ownerUid; }
    public void setOwnerUid(long value) { ownerUid = value; }
    public long getOwnerGid() { return ownerGid; }
    public void setOwnerGid(long value) { ownerGid = value; }
    public String getMode() { return mode; }
    public void setMode(String value) { mode = value; }
    public long getRevision() { return revision; }
    public void setRevision(long value) { revision = value; }
    public boolean getLastRecursive() { return lastRecursive; }
    public void setLastRecursive(boolean value) { lastRecursive = value; }
    public String getMetadataJson() { return metadataJson; }
    public void setMetadataJson(String value) { metadataJson = value; }
    public Date getCreated() { return created; }
    public void setCreated(Date value) { created = value; }
    public Date getUpdated() { return updated; }
    public void setUpdated(Date value) { updated = value; }
}
