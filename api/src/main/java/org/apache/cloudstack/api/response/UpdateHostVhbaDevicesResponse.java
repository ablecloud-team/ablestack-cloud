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
public class UpdateHostVhbaDevicesResponse extends BaseResponse {

    @SerializedName("vhbaname")
    @Param(description = "vHBA device name")
    private String vhbaName;

    @SerializedName("virtualmachineid")
    @Param(description = "Virtual machine ID")
    private String virtualMachineId;

    @SerializedName("virtualmachinename")
    @Param(description = "Virtual machine name")
    private String virtualMachineName;

    @SerializedName("isattached")
    @Param(description = "Whether the vHBA device is attached to a VM")
    private Boolean isAttached;

    @SerializedName("success")
    @Param(description = "Whether the operation was successful")
    private Boolean success;

    @SerializedName("details")
    @Param(description = "Operation details")
    private String details;

    public UpdateHostVhbaDevicesResponse(String vhbaName, String virtualMachineId, String virtualMachineName, Boolean isAttached, Boolean success, String details) {
        this.vhbaName = vhbaName;
        this.virtualMachineId = virtualMachineId;
        this.virtualMachineName = virtualMachineName;
        this.isAttached = isAttached;
        this.success = success;
        this.details = details;
    }

    public UpdateHostVhbaDevicesResponse() {
        super();
        setObjectName("updatehostvhbadevices");
    }

    public String getVhbaName() {
        return vhbaName;
    }

    public void setVhbaName(String vhbaName) {
        this.vhbaName = vhbaName;
    }

    public String getVirtualMachineId() {
        return virtualMachineId;
    }

    public void setVirtualMachineId(String virtualMachineId) {
        this.virtualMachineId = virtualMachineId;
    }

    public String getVirtualMachineName() {
        return virtualMachineName;
    }

    public void setVirtualMachineName(String virtualMachineName) {
        this.virtualMachineName = virtualMachineName;
    }

    public Boolean getIsAttached() {
        return isAttached;
    }

    public void setIsAttached(Boolean isAttached) {
        this.isAttached = isAttached;
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