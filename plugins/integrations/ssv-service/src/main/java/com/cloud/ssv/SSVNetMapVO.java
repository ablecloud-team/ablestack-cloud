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

import javax.persistence.Column;

import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;

@Entity
@Table(name = "ssv_net_map")
public class SSVNetMapVO implements SSVNetMap {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    long id;

    @Column(name = "ssv_service_id")
    long ssvServiceId;

    @Column(name = "network_id")
    private long networkId;

    @Column(name = "network_ip")
    private String  networkIp;

    @Column(name = "network_gateway")
    private String networkGateway;

    @Column(name = "network_netmask")
    private String networkNetmask;


    public SSVNetMapVO() {
    }

    public SSVNetMapVO(long ssvServiceId, long networkId, String networkIp, String networkGateway, String networkNetmask) {
        this.ssvServiceId = ssvServiceId;
        this.networkId = networkId;
        this.networkIp = networkIp;
        this.networkGateway = networkGateway;
        this.networkNetmask = networkNetmask;
    }

    @Override
    public long getId() {
        return id;
    }

    public long getSsvServiceId() {
        return ssvServiceId;
    }

    public void setSsvServiceId(long ssvServiceId) {
        this.ssvServiceId = ssvServiceId;
    }

    public long getNetworkId() {
        return networkId;
    }

    public void setNetworkId(long networkId) {
        this.networkId = networkId;
    }

    @Override
    public String getNetworkIp() {
        return networkIp;
    }

    public void setNetworkIp(String networkIp) {
        this.networkIp = networkIp;
    }

    @Override
    public String getNetworkGateway() {
        return networkGateway;
    }

    public void setNetworkGateway(String networkGateway) {
        this.networkGateway = networkGateway;
    }

    @Override
    public String getNetworkNetmask() {
        return networkNetmask;
    }

    public void setNetworkNetmask(String networkNetmask) {
        this.networkNetmask = networkNetmask;
    }

}