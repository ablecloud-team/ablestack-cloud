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
import java.util.List;
import java.util.Map;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseResponse;
import org.apache.cloudstack.api.EntityReference;

@EntityReference(value = Host.class)
public class CreateVhbaDeviceResponse extends BaseResponse {

    @SerializedName(ApiConstants.PARENT_HB_NAME)
    @Param(description = "Parent HBA device name")
    private List<String> parentHbaName;

    @SerializedName(ApiConstants.VHBA_NAME)
    @Param(description = "vHBA device name")
    private String vhbaName;

    @SerializedName(ApiConstants.WWNN)
    @Param(description = "World Wide Node Name")
    private String wwnn;

    @SerializedName(ApiConstants.WWPN)
    @Param(description = "World Wide Port Name")
    private String wwpn;

    @SerializedName("success")
    @Param(description = "Whether the operation was successful")
    private Boolean success;

    @SerializedName("details")
    @Param(description = "Operation details")
    private String details;

    public CreateVhbaDeviceResponse(List<String> parentHbaName, String vhbaName, String wwnn, String wwpn, Boolean success, String details) {
        this.parentHbaName = parentHbaName;
        this.vhbaName = vhbaName;
        this.wwnn = wwnn;
        this.wwpn = wwpn;
        this.success = success;
        this.details = details;
    }

    public CreateVhbaDeviceResponse(String vhbaName, String createdDeviceName, Boolean success) {
        this.vhbaName = vhbaName;
        this.details = createdDeviceName;
        this.success = success;
    }

    public CreateVhbaDeviceResponse() {
        super();
        setObjectName("createvhbadevice");
    }

    public List<String> getParentHbaName() {
        return parentHbaName;
    }

    public void setParentHbaName(List<String> parentHbaName) {
        this.parentHbaName = parentHbaName;
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

    public String getWwpn() {
        return wwpn;
    }

    public void setWwpn(String wwpn) {
        this.wwpn = wwpn;
    }

    public Boolean getSuccess() {
        return success;
    }

    public void setSuccess(Boolean success) {
        this.success = success;
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
    }
} 