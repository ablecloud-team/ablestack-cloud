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

import java.util.Date;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;

/** Independent CAS record: ordinary writer phase updates cannot erase concurrent operator intent. */
@Entity
@Table(name = "storage_service_operation_control")
public class StorageServiceOperationControlVO {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "id") private long id;
    @Column(name = "operation_id") private long operationId;
    @Column(name = "instance_id") private long instanceId;
    @Column(name = "control_revision") private long controlRevision = 1;
    @Column(name = "cancel_requested") private boolean cancelRequested;
    @Column(name = "cancel_requested_by") private Long cancelRequestedBy;
    @Column(name = "drain_state") private String drainState = "NOT_REQUESTED";
    @Column(name = "policy_json", length = 16777215, columnDefinition = "LONGTEXT") private String policyJson;
    @Column(name = "lease_json", length = 16777215, columnDefinition = "LONGTEXT") private String leaseJson;
    @Column(name = "created_by") private long createdBy;
    @Column(name = "created") @Temporal(TemporalType.TIMESTAMP) private Date created = new Date();
    @Column(name = "updated") @Temporal(TemporalType.TIMESTAMP) private Date updated = new Date();
    public long getId() { return id; }
    public long getOperationId() { return operationId; }
    public void setOperationId(long value) { operationId = value; }
    public long getInstanceId() { return instanceId; }
    public void setInstanceId(long value) { instanceId = value; }
    public long getControlRevision() { return controlRevision; }
    public void setControlRevision(long value) { controlRevision = value; }
    public boolean isCancelRequested() { return cancelRequested; }
    public void setCancelRequested(boolean value) { cancelRequested = value; }
    public Long getCancelRequestedBy() { return cancelRequestedBy; }
    public void setCancelRequestedBy(Long value) { cancelRequestedBy = value; }
    public String getDrainState() { return drainState; }
    public void setDrainState(String value) { drainState = value; }
    public String getPolicyJson() { return policyJson; }
    public void setPolicyJson(String value) { policyJson = value; }
    public String getLeaseJson() { return leaseJson; }
    public void setLeaseJson(String value) { leaseJson = value; }
    public long getCreatedBy() { return createdBy; }
    public void setCreatedBy(long value) { createdBy = value; }
    public Date getCreated() { return created; }
    public Date getUpdated() { return updated; }
    public void setUpdated(Date value) { updated = value; }
}
