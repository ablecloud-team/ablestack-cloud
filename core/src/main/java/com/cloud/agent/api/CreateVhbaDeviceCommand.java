/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.cloud.agent.api;

public class CreateVhbaDeviceCommand extends Command {
    private Long hostId;
    private String parentHbaName;
    private String wwnn;
    private String wwpn;
    private String vhbaName;
    private String xmlContent;

    public CreateVhbaDeviceCommand() {
    }

    public CreateVhbaDeviceCommand(Long hostId, String parentHbaName, String wwnn, String wwpn, String vhbaName, String xmlContent) {
        this.hostId = hostId;
        this.parentHbaName = parentHbaName;
        this.wwnn = wwnn;
        this.wwpn = wwpn;
        this.vhbaName = vhbaName;
        this.xmlContent = xmlContent;
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

    public String getParentHbaName() {
        return parentHbaName;
    }

    public void setParentHbaName(String parentHbaName) {
        this.parentHbaName = parentHbaName;
    }

    public String getWwnn() {
        return wwnn;
    }

    public void setWwnn(String wwnn) {
        this.wwnn = wwnn;
    }

    public String getWwpn() {
        return wwpn;
    }

    public void setWwpn(String wwpn) {
        this.wwpn = wwpn;
    }

    public String getVhbaName() {
        return vhbaName;
    }

    public void setVhbaName(String vhbaName) {
        this.vhbaName = vhbaName;
    }

    public String getXmlContent() {
        return xmlContent;
    }

    public void setXmlContent(String xmlContent) {
        this.xmlContent = xmlContent;
    }
}