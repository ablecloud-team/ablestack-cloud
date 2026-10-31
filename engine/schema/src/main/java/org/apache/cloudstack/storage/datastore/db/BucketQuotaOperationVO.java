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
package org.apache.cloudstack.storage.datastore.db;

import java.util.Date;
import java.util.UUID;
import javax.persistence.*;

@Entity
@Table(name = "bucket_quota_operation")
public class BucketQuotaOperationVO {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id") private Long id;
    @Column(name = "uuid") private String uuid;
    @Column(name = "bucket_id") private Long bucketId;
    @Column(name = "active_bucket_id") private Long activeBucketId;
    @Column(name = "account_id") private long accountId;
    @Column(name = "domain_id") private long domainId;
    @Column(name = "object_store_id") private long objectStoreId;
    @Column(name = "operation_type") private String operationType;
    @Column(name = "previous_quota") private Integer previousQuota;
    @Column(name = "requested_quota") private Integer requestedQuota;
    @Column(name = "previous_snapshot", length = 1024) private String previousSnapshot;
    @Column(name = "store_url", length = 2048) private String storeUrl;
    @Column(name = "external_id") private String externalId;
    @Column(name = "external_created") private boolean externalCreated;
    @Column(name = "state") private String state;
    @Column(name = "account_hold_bytes") private long accountHoldBytes;
    @Column(name = "reserved_bytes") private long reservedBytes;
    @Column(name = "attempts") private int attempts;
    @Column(name = "error_code") private String errorCode;
    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "created") private Date created;
    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "updated_at") private Date updated;
    public BucketQuotaOperationVO() { uuid = UUID.randomUUID().toString(); state = "Prepared"; created = new Date(); updated = new Date(); }
    public boolean isActive() { return !"Succeeded".equals(state) && !"Failed".equals(state); }
    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public String getUuid() { return uuid; }
    public void setUuid(String value) { uuid = value; }
    public Long getBucketId() { return bucketId; }
    public void setBucketId(Long value) { bucketId = value; }
    public Long getActiveBucketId() { return activeBucketId; }
    public void setActiveBucketId(Long value) { activeBucketId = value; }
    public long getAccountId() { return accountId; }
    public void setAccountId(long value) { accountId = value; }
    public long getDomainId() { return domainId; }
    public void setDomainId(long value) { domainId = value; }
    public long getObjectStoreId() { return objectStoreId; }
    public void setObjectStoreId(long value) { objectStoreId = value; }
    public String getOperationType() { return operationType; }
    public void setOperationType(String value) { operationType = value; }
    public Integer getPreviousQuota() { return previousQuota; }
    public void setPreviousQuota(Integer value) { previousQuota = value; }
    public Integer getRequestedQuota() { return requestedQuota; }
    public void setRequestedQuota(Integer value) { requestedQuota = value; }
    public String getPreviousSnapshot() { return previousSnapshot; }
    public void setPreviousSnapshot(String value) { previousSnapshot = value; }
    public String getStoreUrl() { return storeUrl; }
    public void setStoreUrl(String value) { storeUrl = value; }
    public String getExternalId() { return externalId; }
    public void setExternalId(String value) { externalId = value; }
    public boolean getExternalCreated() { return externalCreated; }
    public void setExternalCreated(boolean value) { externalCreated = value; }
    public String getState() { return state; }
    public void setState(String value) { state = value; }
    public long getAccountHoldBytes() { return accountHoldBytes; }
    public void setAccountHoldBytes(long value) { accountHoldBytes = value; }
    public long getReservedBytes() { return reservedBytes; }
    public void setReservedBytes(long value) { reservedBytes = value; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int value) { attempts = value; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String value) { errorCode = value; }
    public Date getCreated() { return created; }
    public void setCreated(Date value) { created = value; }
    public Date getUpdated() { return updated; }
    public void setUpdated(Date value) { updated = value; }
    @Override public String toString() { return "BucketQuotaOperation[" + uuid + ",state=" + state + "]"; }
}
