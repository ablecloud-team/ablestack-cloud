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

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Table;

@Entity
@Table(name = "vbmc_port")
public class VbmcVO {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    Long id;

    @Column(name = "vm_id", updatable = true, nullable = false)
    private Long vmId;

    @Column(name = "port", updatable = false, nullable = false)
    private int port;

    @Column(name = "status")
    private String status;

    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }

    @Column(name = "host_id")
    private Long hostId;

    public Long getHostId() { return hostId; }
    public void setHostId(Long value) { hostId = value; }

    @Column(name = "instance_name")
    private String instanceName;

    public String getInstanceName() { return instanceName; }
    public void setInstanceName(String value) { instanceName = value; }

    @Column(name = "owner_token")
    private String token;

    public String getToken() { return token; }
    public void setToken(String value) { token = value; }

    @Column(name = "address")
    private String address;

    public String getAddress() { return address; }
    public void setAddress(String value) { address = value; }

    @Column(name = "allowed_cidr")
    private String allowedCidr;

    public String getAllowedCidr() { return allowedCidr; }
    public void setAllowedCidr(String value) { allowedCidr = value; }

    @Column(name = "last_error")
    private String lastError;

    public String getLastError() { return lastError; }
    public void setLastError(String value) { lastError = value; }

    @javax.persistence.Temporal(javax.persistence.TemporalType.TIMESTAMP)
    @Column(name = "last_checked")
    private java.util.Date lastChecked;

    public java.util.Date getLastChecked() { return lastChecked; }
    public void setLastChecked(java.util.Date value) { lastChecked = value; }

    public VbmcVO() {
    }

    public VbmcVO(Long vmId, int port) {
        this.vmId = vmId;
        this.port = port;
    }

    public Long getId() {
        return id;
    }

    public Long getVmId() {
        return vmId;
    }

    public int getPort() {
        return port;
    }

    public void setVmId(long vmId) {
        this.vmId = vmId;
    }

}
