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

import java.util.Date;
import java.util.UUID;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Lob;
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;

import com.cloud.utils.db.GenericDao;

@Entity
@Table(name = "storage_file_share")
public class StorageFileShareVO implements StorageFileShare {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private long id;

    @Column(name = "uuid")
    private String uuid = UUID.randomUUID().toString();

    @Column(name = "instance_id")
    private long instanceId;

    @Column(name = "protocol")
    @Enumerated(value = EnumType.STRING)
    private StorageServiceInstance.Protocol protocol;

    @Column(name = "name")
    private String name;

    @Column(name = "path")
    private String path;

    @Column(name = "volume_id")
    private Long volumeId;

    @Column(name = "filesystem")
    private String filesystem;

    @Column(name = "quota_bytes")
    private Long quotaBytes;

    @Column(name = "state")
    @Enumerated(value = EnumType.STRING)
    private StorageServiceInstance.ResourceState state = StorageServiceInstance.ResourceState.Allocated;

    @Lob
    @Column(name = "config_json", length = 16777215, columnDefinition = "MEDIUMTEXT")
    private String configJson;

    @Column(name = GenericDao.CREATED_COLUMN)
    @Temporal(value = TemporalType.TIMESTAMP)
    private Date created = new Date();

    public StorageFileShareVO() {
    }

    public StorageFileShareVO(long instanceId, StorageServiceInstance.Protocol protocol, String name, String path, Long volumeId,
            String filesystem, Long quotaBytes, StorageServiceInstance.ResourceState state, String configJson) {
        this.instanceId = instanceId;
        this.protocol = protocol;
        this.name = name;
        this.path = path;
        this.volumeId = volumeId;
        this.filesystem = filesystem;
        this.quotaBytes = quotaBytes;
        this.state = state;
        this.configJson = configJson;
    }

    public long getId() {
        return id;
    }

    public String getUuid() {
        return uuid;
    }

    public long getInstanceId() {
        return instanceId;
    }

    public StorageServiceInstance.Protocol getProtocol() {
        return protocol;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public Long getVolumeId() {
        return volumeId;
    }

    public void setVolumeId(Long volumeId) {
        this.volumeId = volumeId;
    }

    public String getFilesystem() {
        return filesystem;
    }

    public void setFilesystem(String filesystem) {
        this.filesystem = filesystem;
    }

    public Long getQuotaBytes() {
        return quotaBytes;
    }

    public void setQuotaBytes(Long quotaBytes) {
        this.quotaBytes = quotaBytes;
    }

    public StorageServiceInstance.ResourceState getState() {
        return state;
    }

    public void setState(StorageServiceInstance.ResourceState state) {
        this.state = state;
    }

    public String getConfigJson() {
        return configJson;
    }

    public void setConfigJson(String configJson) {
        this.configJson = configJson;
    }
}
