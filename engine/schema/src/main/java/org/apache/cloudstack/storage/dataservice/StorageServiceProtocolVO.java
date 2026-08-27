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
@Table(name = "storage_service_protocol")
public class StorageServiceProtocolVO {
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

    @Column(name = "enabled")
    private boolean enabled;

    @Column(name = "listen_ip")
    private String listenIp;

    @Column(name = "port")
    private Integer port;

    @Column(name = "state")
    @Enumerated(value = EnumType.STRING)
    private StorageServiceInstance.ResourceState state = StorageServiceInstance.ResourceState.Allocated;

    @Lob
    @Column(name = "config_json", length = 16777215, columnDefinition = "MEDIUMTEXT")
    private String configJson;

    @Column(name = GenericDao.CREATED_COLUMN)
    @Temporal(value = TemporalType.TIMESTAMP)
    private Date created = new Date();

    public StorageServiceProtocolVO() {
    }

    public StorageServiceProtocolVO(long instanceId, StorageServiceInstance.Protocol protocol, boolean enabled, String listenIp, Integer port) {
        this.instanceId = instanceId;
        this.protocol = protocol;
        this.enabled = enabled;
        this.listenIp = listenIp;
        this.port = port;
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

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getListenIp() {
        return listenIp;
    }

    public void setListenIp(String listenIp) {
        this.listenIp = listenIp;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
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
