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
@Table(name = "storage_identity_domain")
public class StorageIdentityDomainVO {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private long id;

    @Column(name = "uuid")
    private String uuid = UUID.randomUUID().toString();

    @Column(name = "instance_id")
    private long instanceId;

    @Column(name = "domain_name")
    private String domainName;

    @Column(name = "organizational_unit")
    private String organizationalUnit;

    @Column(name = "dns_servers")
    private String dnsServers;

    @Column(name = "join_state")
    @Enumerated(value = EnumType.STRING)
    private StorageServiceInstance.DomainJoinState joinState = StorageServiceInstance.DomainJoinState.NOT_JOINED;

    @Column(name = "health_state")
    private String healthState;

    @Lob
    @Column(name = "config_json", length = 16777215, columnDefinition = "MEDIUMTEXT")
    private String configJson;

    @Column(name = GenericDao.CREATED_COLUMN)
    @Temporal(value = TemporalType.TIMESTAMP)
    private Date created = new Date();

    public StorageIdentityDomainVO() {
    }

    public StorageIdentityDomainVO(long instanceId, String domainName, String organizationalUnit, String dnsServers,
            StorageServiceInstance.DomainJoinState joinState, String healthState, String configJson) {
        this.instanceId = instanceId;
        this.domainName = domainName;
        this.organizationalUnit = organizationalUnit;
        this.dnsServers = dnsServers;
        this.joinState = joinState;
        this.healthState = healthState;
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

    public String getDomainName() {
        return domainName;
    }

    public void setDomainName(String domainName) {
        this.domainName = domainName;
    }

    public String getOrganizationalUnit() {
        return organizationalUnit;
    }

    public void setOrganizationalUnit(String organizationalUnit) {
        this.organizationalUnit = organizationalUnit;
    }

    public String getDnsServers() {
        return dnsServers;
    }

    public void setDnsServers(String dnsServers) {
        this.dnsServers = dnsServers;
    }

    public StorageServiceInstance.DomainJoinState getJoinState() {
        return joinState;
    }

    public void setJoinState(StorageServiceInstance.DomainJoinState joinState) {
        this.joinState = joinState;
    }

    public String getHealthState() {
        return healthState;
    }

    public void setHealthState(String healthState) {
        this.healthState = healthState;
    }

    public String getConfigJson() {
        return configJson;
    }

    public void setConfigJson(String configJson) {
        this.configJson = configJson;
    }
}
