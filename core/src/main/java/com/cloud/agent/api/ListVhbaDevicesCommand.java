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

import java.util.List;

public class ListVhbaDevicesCommand extends Command {
    private Long hostId;
    private String keyword;
    private List<VhbaDeviceInfo> vhbaDevices;

    public ListVhbaDevicesCommand() {
    }

    public ListVhbaDevicesCommand(Long hostId, String keyword) {
        this.hostId = hostId;
        this.keyword = keyword;
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

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public List<VhbaDeviceInfo> getVhbaDevices() {
        return vhbaDevices;
    }

    public void setVhbaDevices(List<VhbaDeviceInfo> vhbaDevices) {
        this.vhbaDevices = vhbaDevices;
    }

    public static class VhbaDeviceInfo {
        private String vhbaName;
        private String parentHbaName;
        private String wwnn;
        private String wwpn;
        private String description;
        private String status;

        public VhbaDeviceInfo() {
        }

        public VhbaDeviceInfo(String vhbaName, String parentHbaName, String wwnn, String wwpn, String description, String status) {
            this.vhbaName = vhbaName;
            this.parentHbaName = parentHbaName;
            this.wwnn = wwnn;
            this.wwpn = wwpn;
            this.description = description;
            this.status = status;
        }

        public String getVhbaName() {
            return vhbaName;
        }

        public void setVhbaName(String vhbaName) {
            this.vhbaName = vhbaName;
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

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }
    }
} 