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
@Table(name = "storage_posix_directory_policy")
public class StoragePosixDirectoryPolicyVO implements StoragePosixDirectoryPolicy {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private long id;
    @Column(name = "uuid")
    private String uuid = UUID.randomUUID().toString();
    @Column(name = "instance_id")
    private long instanceId;
    @Column(name = "volume_id")
    private long volumeId;
    @Column(name = "relative_path")
    private String relativePath;
    @Column(name = "path_key")
    private String pathKey;
    @Column(name = "revision")
    private long revision;
    @Column(name = "state")
    private String state;
    @Column(name = "config_json", length = 16777215, columnDefinition = "MEDIUMTEXT")
    private String configJson;
    @Column(name = "effective_json", length = 16777215, columnDefinition = "MEDIUMTEXT")
    private String effectiveJson;
    @Column(name = "created")
    @Temporal(TemporalType.TIMESTAMP)
    private java.util.Date created = new java.util.Date();
    @Column(name = "last_applied")
    @Temporal(TemporalType.TIMESTAMP)
    private java.util.Date lastApplied;
    public StoragePosixDirectoryPolicyVO() { }
    public long getId() { return id; }
    public String getUuid() { return uuid; }
    public long getInstanceId() { return instanceId; }
    public void setInstanceId(long value) { instanceId = value; }
    public long getVolumeId() { return volumeId; }
    public void setVolumeId(long value) { volumeId = value; }
    public String getRelativePath() { return relativePath; }
    public void setRelativePath(String value) { relativePath = value; }
    public String getPathKey() { return pathKey; }
    public void setPathKey(String value) { pathKey = value; }
    public long getRevision() { return revision; }
    public void setRevision(long value) { revision = value; }
    public String getState() { return state; }
    public void setState(String value) { state = value; }
    public String getConfigJson() { return configJson; }
    public void setConfigJson(String value) { configJson = value; }
    public String getEffectiveJson() { return effectiveJson; }
    public void setEffectiveJson(String value) { effectiveJson = value; }
    public java.util.Date getLastApplied() { return lastApplied; }
    public void setLastApplied(java.util.Date value) { lastApplied = value; }
}
