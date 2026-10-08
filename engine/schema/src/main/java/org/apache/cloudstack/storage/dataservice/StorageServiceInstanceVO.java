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
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;

import com.cloud.utils.db.GenericDao;

@Entity
@Table(name = "storage_service_instance")
public class StorageServiceInstanceVO implements StorageServiceInstance {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private long id;

    @Column(name = "uuid")
    private String uuid = UUID.randomUUID().toString();

    @Column(name = "name")
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "domain_id")
    private long domainId;

    @Column(name = "account_id")
    private long accountId;

    @Column(name = "data_center_id")
    private long dataCenterId;

    @Column(name = "vm_id")
    private Long vmId;

    @Column(name = "service_offering_id")
    private Long serviceOfferingId;

    @Column(name = "provider")
    private String provider;

    @Column(name = "state")
    @Enumerated(value = EnumType.STRING)
    private State state = State.Allocated;

    @Column(name = "operation_control_policy_json", length = 16777215, columnDefinition = "LONGTEXT")
    private String operationControlPolicyJson;
    public String getOperationControlPolicyJson() { return operationControlPolicyJson; }
    public void setOperationControlPolicyJson(String value) { operationControlPolicyJson = value; }

    @Column(name = "current_runtime_bundle_id")
    private Long currentRuntimeBundleId;

    @Column(name = "previous_runtime_bundle_id")
    private Long previousRuntimeBundleId;

    @Column(name = "runtime_state")
    private String runtimeState;

    @Column(name = "runtime_verified_at")
    @Temporal(value = TemporalType.TIMESTAMP)
    private Date runtimeVerifiedAt;

    @Column(name="current_template_id") private Long currentTemplateId;
    @Column(name="previous_template_id") private Long previousTemplateId;
    @Column(name="template_upgrade_state") private String templateUpgradeState;
    @Column(name="last_template_upgrade_id") private Long lastTemplateUpgradeId;
    @Column(name="template_verified_at") @Temporal(TemporalType.TIMESTAMP) private Date templateVerifiedAt;
    public Long getCurrentTemplateId(){return currentTemplateId;}
    public void setCurrentTemplateId(Long value){currentTemplateId=value;}
    public Long getPreviousTemplateId(){return previousTemplateId;}
    public void setPreviousTemplateId(Long value){previousTemplateId=value;}
    public String getTemplateUpgradeState(){return templateUpgradeState;}
    public void setTemplateUpgradeState(String value){templateUpgradeState=value;}
    public Long getLastTemplateUpgradeId(){return lastTemplateUpgradeId;}
    public void setLastTemplateUpgradeId(Long value){lastTemplateUpgradeId=value;}
    public Date getTemplateVerifiedAt(){return templateVerifiedAt;}
    public void setTemplateVerifiedAt(Date value){templateVerifiedAt=value;}

    @Column(name = GenericDao.CREATED_COLUMN)
    @Temporal(value = TemporalType.TIMESTAMP)
    private Date created = new Date();

    @Column(name = "updated")
    @Temporal(value = TemporalType.TIMESTAMP)
    private Date updated;

    @Column(name = GenericDao.REMOVED_COLUMN)
    private Date removed;

    public StorageServiceInstanceVO() {
    }

    public StorageServiceInstanceVO(String name, String description, long domainId, long accountId, long dataCenterId, Long serviceOfferingId, String provider) {
        this.name = name;
        this.description = description;
        this.domainId = domainId;
        this.accountId = accountId;
        this.dataCenterId = dataCenterId;
        this.serviceOfferingId = serviceOfferingId;
        this.provider = provider;
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
    public long getDomainId() {
        return domainId;
    }

    @Override
    public long getAccountId() {
        return accountId;
    }

    @Override
    public long getDataCenterId() {
        return dataCenterId;
    }

    @Override
    public Long getVmId() {
        return vmId;
    }

    public void setVmId(Long vmId) {
        this.vmId = vmId;
    }

    @Override
    public Long getServiceOfferingId() {
        return serviceOfferingId;
    }

    public void setServiceOfferingId(Long serviceOfferingId) {
        this.serviceOfferingId = serviceOfferingId;
    }

    @Override
    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    @Override
    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public Long getCurrentRuntimeBundleId() {
        return currentRuntimeBundleId;
    }

    public void setCurrentRuntimeBundleId(final Long value) {
        currentRuntimeBundleId = value;
    }

    public Long getPreviousRuntimeBundleId() {
        return previousRuntimeBundleId;
    }

    public void setPreviousRuntimeBundleId(final Long value) {
        previousRuntimeBundleId = value;
    }

    public String getRuntimeState() {
        return runtimeState;
    }

    public void setRuntimeState(final String value) {
        runtimeState = value;
    }

    public Date getRuntimeVerifiedAt() {
        return runtimeVerifiedAt;
    }

    public void setRuntimeVerifiedAt(final Date value) {
        runtimeVerifiedAt = value;
    }

    @Override
    public Date getCreated() {
        return created;
    }

    public Date getUpdated() {
        return updated;
    }

    public void setUpdated(Date value) { updated = value; }

    public Date getRemoved() {
        return removed;
    }

    @Override
    public Class<?> getEntityType() {
        return StorageServiceInstance.class;
    }
}
