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

public class DeleteVhbaDeviceAnswer extends Answer {
    private String vhbaName;
    private String details;

    public DeleteVhbaDeviceAnswer() {
    }

    public DeleteVhbaDeviceAnswer(DeleteVhbaDeviceCommand cmd, boolean success, String details) {
        super(cmd, success, details);
        this.vhbaName = cmd.getVhbaName();
        this.details = details;
    }

    public DeleteVhbaDeviceAnswer(DeleteVhbaDeviceCommand cmd, boolean success, String details, String vhbaName) {
        super(cmd, success, details);
        this.vhbaName = vhbaName;
        this.details = details;
    }

    public String getVhbaName() {
        return vhbaName;
    }

    public void setVhbaName(String vhbaName) {
        this.vhbaName = vhbaName;
    }

    @Override
    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
    }
}