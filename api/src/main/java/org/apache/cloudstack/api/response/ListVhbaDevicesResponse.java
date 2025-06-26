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

package org.apache.cloudstack.api.response;

import com.cloud.host.Host;
import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;
import org.apache.cloudstack.api.BaseResponse;
import org.apache.cloudstack.api.EntityReference;

@EntityReference(value = Host.class)
public class ListVhbaDevicesResponse extends BaseResponse {

    @SerializedName("vhbaname")
    @Param(description = "vHBA device name")
    private String vhbaName;

    @SerializedName("parenthbaname")
    @Param(description = "Parent HBA device name")
    private String parentHbaName;

    @SerializedName("wwnn")
    @Param(description = "World Wide Node Name")
    private String wwnn;

    @SerializedName("wwpn")
    @Param(description = "World Wide Port Name")
    private String wwpn;

    @SerializedName("description")
    @Param(description = "vHBA device description")
    private String description;

    @SerializedName("status")
    @Param(description = "vHBA device status")
    private String status;

    public ListVhbaDevicesResponse(String vhbaName, String parentHbaName, String wwnn, String wwpn, String description, String status) {
        this.vhbaName = vhbaName;
        this.parentHbaName = parentHbaName;
        this.wwnn = wwnn;
        this.wwpn = wwpn;
        this.description = description;
        this.status = status;
    }

    public ListVhbaDevicesResponse() {
        super();
        setObjectName("listvhbadevices");
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