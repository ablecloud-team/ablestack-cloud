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
import javax.persistence.PrimaryKeyJoinColumn;
import javax.persistence.Table;

@Entity
@Table(name = "vbmc_port")
@PrimaryKeyJoinColumn(name = "id")
public class VbmcVO extends VMInstanceVO implements Vbmc {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    Long id;

    @Column(name = "vm_id", updatable = true, nullable = true)
    private Long vmId;

    @Column(name = "port", updatable = true, nullable = false)
    private int port;

    public VbmcVO(Long vmId, int port) {
        this.vmId = vmId;
        this.port = port;
    }

    @Override
    public Long getVmId() {
        return vmId;
    }

    @Override
    public int getPort() {
        return port;
    }

    public void setVmId(Long vmId) {
        this.vmId = vmId;
    }

    public void setPort(int port) {
        this.port = port;
    }
}
