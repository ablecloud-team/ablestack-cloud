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

import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;
import java.util.List;
import java.util.Map;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseResponse;

public class ListVmHostDevicesResponse extends BaseResponse {

    @SerializedName(ApiConstants.VIRTUAL_MACHINE_ID)
    @Param(description = "ID of the virtual machine")
    private Long virtualMachineId;

    @SerializedName(ApiConstants.HOST_ID)
    @Param(description = "ID of the host where the device resides")
    private Long hostId;

    @SerializedName(ApiConstants.HOST_NAME)
    @Param(description = "Name of the host where the device resides")
    private String hostName;

    @SerializedName(ApiConstants.HOSTDEVICES_NAME)
    @Param(description = "List of allocated device names")
    private List<String> hostDevicesName;

    @SerializedName(ApiConstants.HOSTDEVICES_TEXT)
    @Param(description = "List of allocated device descriptions")
    private List<String> hostDevicesText;

    @SerializedName("devicetypes")
    @Param(description = "List of device types (pci/usb/lun/scsi/hba/vhba)")
    private List<String> deviceTypes;

    @SerializedName("vmallocations")
    @Param(description = "Map of device name to VM identifier")
    private Map<String, String> vmAllocations;

    @SerializedName("devicedetails")
    @Param(description = "Map of device name to detailed description")
    private Map<String, String> deviceDetails;

    public ListVmHostDevicesResponse() {
        setObjectName("vmhostdevice");
    }

    public Long getVirtualMachineId() {
        return virtualMachineId;
    }

    public void setVirtualMachineId(Long virtualMachineId) {
        this.virtualMachineId = virtualMachineId;
    }

    public Long getHostId() {
        return hostId;
    }

    public void setHostId(Long hostId) {
        this.hostId = hostId;
    }

    public String getHostName() {
        return hostName;
    }

    public void setHostName(String hostName) {
        this.hostName = hostName;
    }

    public List<String> getHostDevicesName() {
        return hostDevicesName;
    }

    public void setHostDevicesNames(List<String> hostDevicesName) {
        this.hostDevicesName = hostDevicesName;
    }

    public List<String> getHostDevicesText() {
        return hostDevicesText;
    }

    public void setHostDevicesTexts(List<String> hostDevicesText) {
        this.hostDevicesText = hostDevicesText;
    }

    public List<String> getDeviceTypes() {
        return deviceTypes;
    }

    public void setDeviceTypes(List<String> deviceTypes) {
        this.deviceTypes = deviceTypes;
    }

    public Map<String, String> getVmAllocations() {
        return vmAllocations;
    }

    public void setVmAllocations(Map<String, String> vmAllocations) {
        this.vmAllocations = vmAllocations;
    }

    public Map<String, String> getDeviceDetails() {
        return deviceDetails;
    }

    public void setDeviceDetails(Map<String, String> deviceDetails) {
        this.deviceDetails = deviceDetails;
    }
}

