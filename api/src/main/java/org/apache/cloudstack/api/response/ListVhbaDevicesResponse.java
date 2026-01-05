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
public class ListVhbaDevicesResponse extends BaseResponse {

    @SerializedName(ApiConstants.HOSTDEVICES_NAME)
    @Param(description = "Allocated IP address")
    private List<String> hostDevicesName;

    @SerializedName(ApiConstants.HOSTDEVICES_TEXT)
    @Param(description = "the ID of the pod the  IP address belongs to")
    private List<String> hostDevicesText;

    @SerializedName("parenthbaname")
    @Param(description = "Parent HBA device name")
    private String parentHbaName;

    @SerializedName("vmallocations")
    @Param(description = "Map of device to VM allocations")
    private Map<String, String> vmAllocations;

    @SerializedName("devicetypes")
    @Param(description = "List of device types (physical/virtual)")
    private List<String> deviceTypes;

    @SerializedName("parenthbanames")
    @Param(description = "List of parent HBA names for vHBA devices")
    private List<String> parentHbaNames;

    @SerializedName("wwnns")
    @Param(description = "List of World Wide Node Names")
    private List<String> wwnns;

    @SerializedName("wwpns")
    @Param(description = "List of World Wide Port Names")
    private List<String> wwpns;

    @SerializedName("descriptions")
    @Param(description = "List of vHBA device descriptions")
    private List<String> descriptions;

    @SerializedName("statuses")
    @Param(description = "List of vHBA device statuses")
    private List<String> statuses;

    @SerializedName("devicedetails")
    @Param(description = "Map of device to device details")
    private Map<String, String> deviceDetails;

    public ListVhbaDevicesResponse(List<String> hostDevicesName, List<String> hostDevicesText, String parentHbaName, List<String> wwnns, List<String> wwpns, List<String> descriptions, List<String> statuses) {
        this.hostDevicesName = hostDevicesName;
        this.hostDevicesText = hostDevicesText;
        this.parentHbaName = parentHbaName;
        this.wwnns = wwnns;
        this.wwpns = wwpns;
        this.descriptions = descriptions;
        this.statuses = statuses;
    }

    public ListVhbaDevicesResponse() {
        super();
        setObjectName("listvhbadevices");
    }

    public List<String> getHostDevicesNames() {
        return hostDevicesName;
    }

    public List<String> getHostDevicesTexts() {
        return hostDevicesText;
    }
    public void setHostDevicesNames(List<String> hostDevicesName) {
        this.hostDevicesName = hostDevicesName;
    }

    public void setHostDevicesTexts(List<String> hostDevicesText) {
        this.hostDevicesText = hostDevicesText;
    }

    public String getParentHbaName() {
        return parentHbaName;
    }

    public void setParentHbaName(String parentHbaName) {
        this.parentHbaName = parentHbaName;
    }

    public List<String> getWwnns() {
        return wwnns;
    }

    public void setWwnns(List<String> wwnns) {
        this.wwnns = wwnns;
    }

    public List<String> getWwpns() {
        return wwpns;
    }

    public void setWwpns(List<String> wwpns) {
        this.wwpns = wwpns;
    }

    public List<String> getDescriptions() {
        return descriptions;
    }

    public void setDescriptions(List<String> descriptions) {
        this.descriptions = descriptions;
    }

    public List<String> getStatuses() {
        return statuses;
    }

    public void setStatuses(List<String> statuses) {
        this.statuses = statuses;
    }

    public void setVmAllocations(Map<String, String> vmAllocations) {
        this.vmAllocations = vmAllocations;
    }

    public Map<String, String> getVmAllocations() {
        return this.vmAllocations;
    }

    public void setDeviceTypes(List<String> deviceTypes) {
        this.deviceTypes = deviceTypes;
    }

    public List<String> getDeviceTypes() {
        return this.deviceTypes;
    }
    public void setParentHbaNames(List<String> parentHbaNames) {
        this.parentHbaNames = parentHbaNames;
    }

    public List<String> getParentHbaNames() {
        return this.parentHbaNames;
    }

    public Map<String, String> getDeviceDetails() {
        return this.deviceDetails;
    }

    public void setDeviceDetails(Map<String, String> deviceDetails) {
        this.deviceDetails = deviceDetails;
    }
}