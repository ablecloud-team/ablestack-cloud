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

package com.cloud.automation.controller;

import java.util.Date;
import java.util.UUID;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;

import com.cloud.utils.db.GenericDao;

@Entity
@Table(name = "automation_controller_service_vm")
public class AutomationControllerVO implements AutomationController {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private long id;

    @Column(name = "uuid")
    private String uuid;

    @Column(name = "name")
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "zone_id")
    private long zoneId;

    @Column(name = "automation_template_id")
    private long automationTemplateId;

    @Column(name = "service_offering_id")
    private long serviceOfferingId;

    @Column(name = "instance_id")
    private long instanceId;

    @Column(name = "network_id")
    private long networkId;

    @Column(name = "account_id")
    private long accountId;

    @Column(name = "domain_id")
    private long domainId;

    @Column(name = "state")
    @Enumerated(value = EnumType.STRING)
    State state = State.Enabled;

    @Column(name = "service_ip")
    private String serviceIp;

    @Column(name = GenericDao.CREATED_COLUMN)
    Date created;

    @Column(name = GenericDao.REMOVED_COLUMN)
    Date removed;

//    @Column(name = "gc")
//    private boolean checkForGc;

    public AutomationControllerVO() {
        this.uuid = UUID.randomUUID().toString();
    }

    public AutomationControllerVO(long id, String name, String description, long automationTemplateId, long zoneId, long serviceOfferingId,
                                  long networkId, long domainId, long accountId, String serviceIp) {
        this.uuid = UUID.randomUUID().toString();
        this.id = id;
        this.name = name;
        this.description = description;
        this.automationTemplateId = automationTemplateId;
        this.serviceOfferingId = serviceOfferingId;
//        this.instanceId = instanceId;
        this.networkId = networkId;
        this.accountId = accountId;
        this.domainId = domainId;
        this.serviceIp = serviceIp;
//        this.zoneId = zoneId;
        this.state = state;




        this.zoneId = zoneId;
    }

    public AutomationControllerVO(String name, String description) {
    }

    public AutomationControllerVO(String name, String description, Long zoneId, long id, long automationTemplateId, long domainId, long accountId, State state) {
        this.uuid = UUID.randomUUID().toString();
        this.id = id;
        this.name = name;
        this.description = description;
        this.automationTemplateId = automationTemplateId;
        this.serviceOfferingId = serviceOfferingId;
//        this.instanceId = instanceId;
        this.networkId = networkId;
        this.accountId = accountId;
        this.domainId = domainId;
        this.serviceIp = serviceIp;
        this.zoneId = zoneId;
        this.state = state;
    }

    @Override
    public long getId() {
        return id;
    }

    @Override
    public String getUuid() {
        return uuid;
    }

    @Override
    public Class<?> getEntityType() {
        return AutomationController.class;
    }

    @Override
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Override
    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    @Override
    public long getZoneId() {
        return zoneId;
    }

    public void setZoneId(long zoneId) {
        this.zoneId = zoneId;
    }

    @Override
    public long getAutomationTemplateId() {
        return automationTemplateId;
    }

    public void setAutomationTemplateId(long automationTemplateId) {
        this.automationTemplateId = automationTemplateId;
    }

    @Override
    public long getServiceOfferingId() {
        return serviceOfferingId;
    }

    public void setServiceOfferingId(long serviceOfferingId) {
        this.serviceOfferingId = serviceOfferingId;
    }

    @Override
    public long getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(long instanceId) {
        this.instanceId = instanceId;
    }

    @Override
    public long getNetworkId() {
        return networkId;
    }

    public void setNetworkId(long networkId) {
        this.networkId = networkId;
    }

    @Override
    public long getAccountId() {
        return accountId;
    }

    public void setAccountId(long accountId) {
        this.accountId = accountId;
    }

    @Override
    public long getDomainId() {
        return domainId;
    }

    public void setDomainId(long domainId) {
        this.domainId = domainId;
    }

    @Override
    public String getServiceIp() {
        return serviceIp;
    }

    public void setServiceIp(String serviceIp) {
        this.serviceIp = serviceIp;
    }

    @Override
    public State getState() {
        return this.state;
    }

    public void setState(State state) {
        this.state = state;
    }

    @Override
    public Date getCreated() {
        return created;
    }

    public Date getRemoved() {
        return removed;
    }

    @Override
    public boolean isDisplay() {
        return false;
    }

//    @Override
//    public boolean isCheckForGc() {
//        return checkForGc;
//    }
}
