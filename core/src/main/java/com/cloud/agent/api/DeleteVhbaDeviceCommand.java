//
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

package com.cloud.agent.api;

public class DeleteVhbaDeviceCommand extends Command {
    private Long hostId;
    private String vhbaName;
    private String wwnn;

    public DeleteVhbaDeviceCommand() {
    }

    public DeleteVhbaDeviceCommand(Long hostId, String vhbaName) {
        this.hostId = hostId;
        this.vhbaName = vhbaName;
    }

    public DeleteVhbaDeviceCommand(Long hostId, String vhbaName, String wwnn) {
        this.hostId = hostId;
        this.vhbaName = vhbaName;
        this.wwnn = wwnn;
    }

    @Override
    public boolean executeInSequence() {
        return false;
    }

    public Long getHostId() {
        return hostId;
    }

    public void setHostId(Long hostId) {
        this.hostId = hostId;
    }

    public String getVhbaName() {
        return vhbaName;
    }

    public void setVhbaName(String vhbaName) {
        this.vhbaName = vhbaName;
    }

    public String getWwnn() {
        return wwnn;
    }

    public void setWwnn(String wwnn) {
        this.wwnn = wwnn;
    }
}