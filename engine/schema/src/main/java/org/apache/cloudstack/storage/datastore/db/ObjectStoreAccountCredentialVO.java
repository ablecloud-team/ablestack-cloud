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
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;
import com.cloud.utils.db.Encrypt;

@Entity
@Table(name = "object_store_account_credential")
public class ObjectStoreAccountCredentialVO {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "id")
    private Long id;
    @Column(name = "account_id") private long accountId;
    @Column(name = "object_store_id") private long objectStoreId;
    @Column(name = "rgw_user_id") private String rgwUserId;
    @Encrypt @Column(name = "access_key") private String accessKey;
    @Encrypt @Column(name = "secret_key") private String secretKey;
    @Column(name = "state") private String state;
    @Column(name = "revision") private long revision;
    @Temporal(TemporalType.TIMESTAMP) @Column(name = "checked_at") private Date checked;
    @Column(name = "provisioning_source") private String provisioningSource;

    @Encrypt @Column(name = "pending_access_key") private String pendingAccessKey;
    public String getPendingAccessKey() { return pendingAccessKey; }
    public void setPendingAccessKey(String value) { pendingAccessKey = value; }
    @Encrypt @Column(name = "pending_secret_key") private String pendingSecretKey;
    public String getPendingSecretKey() { return pendingSecretKey; }
    public void setPendingSecretKey(String value) { pendingSecretKey = value; }
    @Encrypt @Column(name = "previous_access_key") private String previousAccessKey;
    public String getPreviousAccessKey() { return previousAccessKey; }
    public void setPreviousAccessKey(String value) { previousAccessKey = value; }
    @Encrypt @Column(name = "previous_secret_key") private String previousSecretKey;
    public String getPreviousSecretKey() { return previousSecretKey; }
    public void setPreviousSecretKey(String value) { previousSecretKey = value; }
    @Column(name = "rotation_uuid") private String rotationUuid;
    public String getRotationUuid() { return rotationUuid; }
    public void setRotationUuid(String value) { rotationUuid = value; }
    @Column(name = "rotation_phase") private String rotationPhase;
    public String getRotationPhase() { return rotationPhase; }
    public void setRotationPhase(String value) { rotationPhase = value; }
    @Column(name = "rotation_error") private String rotationError;
    public String getRotationError() { return rotationError; }
    public void setRotationError(String value) { rotationError = value; }
    @Temporal(TemporalType.TIMESTAMP) @Column(name = "retire_after") private Date retireAfter;
    public Date getRetireAfter() { return retireAfter; }
    public void setRetireAfter(Date value) { retireAfter = value; }

    @Column(name = "rotation_grace_seconds") private Integer rotationGraceSeconds;
    public Integer getRotationGraceSeconds() { return rotationGraceSeconds; }
    public void setRotationGraceSeconds(Integer value) { rotationGraceSeconds = value; }
    public ObjectStoreAccountCredentialVO() { }
    public ObjectStoreAccountCredentialVO(long accountId, long storeId, String userId) {
        this.accountId = accountId; this.objectStoreId = storeId; this.rgwUserId = userId;
        this.state = "NeedsVerification"; this.provisioningSource = "Existing";
    }
    public Long getId() { return id; }
    public long getAccountId() { return accountId; }
    public long getObjectStoreId() { return objectStoreId; }
    public String getRgwUserId() { return rgwUserId; }
    public String getAccessKey() { return accessKey; }
    public void setAccessKey(String value) { accessKey = value; }
    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String value) { secretKey = value; }
    public String getState() { return state; }
    public void setState(String value) { state = value; }
    public long getRevision() { return revision; }
    public void setRevision(long value) { revision = value; }
    public Date getChecked() { return checked; }
    public void setChecked(Date value) { checked = value; }
    public String getProvisioningSource() { return provisioningSource; }
    public void setProvisioningSource(String value) { provisioningSource = value; }
    @Override public String toString() {
        return "ObjectStoreAccountCredential[account=" + accountId + ",store=" + objectStoreId + ",state=" + state + "]";
    }
}
