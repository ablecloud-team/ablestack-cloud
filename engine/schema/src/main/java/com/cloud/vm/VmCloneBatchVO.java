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
package com.cloud.vm;

import java.util.Date;

import javax.persistence.*;

@Entity
@Table(name = "vm_clone_batch")
public class VmCloneBatchVO {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private long id;

    @Column(name = "uuid")
    private String uuid;

    @Column(name = "source_vm_id")
    private long sourceVmId;

    @Column(name = "account_id")
    private long accountId;

    @Column(name = "user_id")
    private long userId;

    @Column(name = "fingerprint")
    private String fingerprint;

    @Column(name = "state")
    private String state;

    @Column(name = "data", length = 16777215)
    private String data;

    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "created")
    private Date created;

    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "updated")
    private Date updated;

    protected VmCloneBatchVO() {}

    public VmCloneBatchVO(
            String uuid,
            long sourceVmId,
            long accountId,
            long userId,
            String fingerprint,
            String data) {
        this.uuid = uuid;
        this.sourceVmId = sourceVmId;
        this.accountId = accountId;
        this.userId = userId;
        this.fingerprint = fingerprint;
        this.data = data;
        state = "QUEUED";
        created = new Date();
        updated = created;
    }

    public long getId() {
        return id;
    }

    public String getUuid() {
        return uuid;
    }

    public long getSourceVmId() {
        return sourceVmId;
    }

    public long getAccountId() {
        return accountId;
    }

    public long getUserId() {
        return userId;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public void setUpdated(Date updated) {
        this.updated = updated;
    }

    public String getData() {
        return data;
    }

    public Date getCreated() {
        return created;
    }

    public void setData(String data, String state) {
        this.data = data;
        this.state = state;
        updated = new Date();
    }
}
