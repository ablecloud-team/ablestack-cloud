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
package com.cloud.ssv;

import java.util.Date;
import java.util.UUID;


import javax.persistence.Column;

import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;

import com.cloud.utils.db.GenericDao;

@Entity
@Table(name = "ssv_service")
public class SSVVO implements SSV {

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

    @Column(name = "domain_id")
    private long domainId;

    @Column(name = "account_id")
    private long accountId;

    @Column(name = "template_id")
    private long templateId;

    @Column(name = "service_offering_id")
    private long serviceOfferingId;

    @Column(name = "disk_offering_id")
    private long diskOfferingId;

    @Column(name = "shared_storage_vm_type")
    private String sharedStorageVmType;

    @Column(name = "state")
    private State  state;

    @Column(name = GenericDao.CREATED_COLUMN)
    private Date created;

    @Column(name = GenericDao.REMOVED_COLUMN)
    private Date removed;

    @Override
    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    @Override
    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    @Override
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Override
    public boolean isDisplay() {
        return true;
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
    public long getServiceOfferingId() {
        return serviceOfferingId;
    }

    public void setServiceOfferingId(long serviceOfferingId) {
        this.serviceOfferingId = serviceOfferingId;
    }

    @Override
    public long getTemplateId() {
        return templateId;
    }

    public void setTemplateId(long templateId) {
        this.templateId = templateId;
    }


    @Override
    public String getSsvType() {
        return sharedStorageVmType;
    }

    public void setSsvType(String ssvType) {
        this.sharedStorageVmType = ssvType;
    }

    @Override
    public long getDomainId() {
        return domainId;
    }

    public void setDomainId(long domainId) {
        this.domainId = domainId;
    }

    @Override
    public long getAccountId() {
        return accountId;
    }

    public void setAccountId(long accountId) {
        this.accountId = accountId;
    }

    @Override
    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public long getDiskOfferingId() {
        return diskOfferingId;
    }

    public void setDiskOfferingId(long diskOfferingId) {
        this.diskOfferingId = diskOfferingId;
    }

    public String getSharedStorageVmType() {
        return sharedStorageVmType;
    }

    public void setSharedStorageVmType(String sharedStorageVmType) {
        this.sharedStorageVmType = sharedStorageVmType;
    }

    public Date getRemoved() {
        if (removed == null)
            return null;
        return new Date(removed.getTime());
    }

    @Override
    public Date getCreated() {
        return created;
    }

    public SSVVO(){
    }

    public SSVVO(String name, String description, long zoneId, long domainId, long accountId,
                long templateId, long serviceOfferingId, long diskOfferingId, String ssvType, State state) {
        this.uuid = UUID.randomUUID().toString();
        this.name = name;
        this.description = description;
        this.zoneId = zoneId;
        this.domainId = domainId;
        this.accountId = accountId;
        this.templateId = templateId;
        this.serviceOfferingId = serviceOfferingId;
        this.diskOfferingId = diskOfferingId;
        this.sharedStorageVmType = ssvType;
        this.state = state;
    }

    @Override
    public Class<?> getEntityType() {
        return SSV.class;
    }
}
