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
import com.cloud.vm.VirtualMachine;
import com.google.gson.annotations.SerializedName;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseResponse;
import org.apache.cloudstack.api.EntityReference;


@EntityReference(value = {Host.class, VirtualMachine.class})
public class UpdateHostUsbDevicesResponse extends BaseResponse {

    @SerializedName(ApiConstants.HOSTDEVICES_NAME)
    @Param(description = "Device name")
    private String hostDevicesName;

    @SerializedName(ApiConstants.VIRTUAL_MACHINE_ID)
    @Param(description = "ID of the VM the device is allocated to")
    private String virtualmachineid;

    @SerializedName("allocated")
    @Param(description = "Whether the device is allocated")
    private boolean allocated;

    public UpdateHostUsbDevicesResponse(String hostDevicesName, String virtualmachineid, boolean allocated) {
    this.hostDevicesName = hostDevicesName;
    this.virtualmachineid = virtualmachineid;
    this.allocated = allocated;
}

    public UpdateHostUsbDevicesResponse() {
        super();
        setObjectName("updatehostdevices");
    }

    public String getHostDeviceName() {
        return hostDevicesName;
    }

    public void setHostDeviceName(String deviceName) {
        this.hostDevicesName = deviceName;
    }

    public String getVirtualMachineId() {
        return virtualmachineid;
    }

    public void setVirtualMachineId(String virtualmachineid) {
        this.virtualmachineid = virtualmachineid;
    }

    public boolean isAllocated() {
        return allocated;
    }

    public void setAllocated(boolean allocated) {
        this.allocated = allocated;
    }
}