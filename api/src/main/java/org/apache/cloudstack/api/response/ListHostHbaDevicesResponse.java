//Licensed to the Apache Software Foundation (ASF) under one
//or more contributor license agreements.  See the NOTICE file
//distributed with this work for additional information
//regarding copyright ownership.  The ASF licenses this file
//to you under the Apache License, Version 2.0 (the
//"License"); you may not use this file except in compliance
//with the License.  You may obtain a copy of the License at
//
//http://www.apache.org/licenses/LICENSE-2.0
//
//Unless required by applicable law or agreed to in writing,
//software distributed under the License is distributed on an
//"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
//KIND, either express or implied.  See the License for the
//specific language governing permissions and limitations
//under the License.

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
public class ListHostHbaDevicesResponse extends BaseResponse {

    @SerializedName(ApiConstants.HOSTDEVICES_NAME)
    @Param(description = "Allocated IP address")
    private List<String> hostDevicesName;

    @SerializedName(ApiConstants.HOSTDEVICES_TEXT)
    @Param(description = "the ID of the pod the  IP address belongs to")
    private List<String> hostDevicesText;

    @SerializedName("vmallocations")
    @Param(description = "Map of device to VM allocations")
    private Map<String, String> vmAllocations;

    @SerializedName("devicetypes")
    @Param(description = "List of device types (physical/virtual)")
    private List<String> deviceTypes;

    @SerializedName("parenthbanames")
    @Param(description = "List of parent HBA names for vHBA devices")
    private List<String> parentHbaNames;

    public ListHostHbaDevicesResponse(List<String> hostDevicesName, List<String> hostDevicesText) {
        this.hostDevicesName = hostDevicesName;
        this.hostDevicesText = hostDevicesText;
    }

    public ListHostHbaDevicesResponse(List<String> hostDevicesName, List<String> hostDevicesText,
                                     List<String> deviceTypes, List<String> parentHbaNames) {
        this.hostDevicesName = hostDevicesName;
        this.hostDevicesText = hostDevicesText;
        this.deviceTypes = deviceTypes;
        this.parentHbaNames = parentHbaNames;
    }

    public ListHostHbaDevicesResponse() {
        super();
        this.setObjectName("listhosthbadevices");
    }

    public List<String> getHostDevicesNames() {
        return hostDevicesName;
    }

    public List<String> getHostDevicesTexts() {
        return hostDevicesText;
    }

    public List<String> getDeviceTypes() {
        return deviceTypes;
    }

    public List<String> getParentHbaNames() {
        return parentHbaNames;
    }

    public void setHostDevicesNames(List<String> hostDevicesName) {
        this.hostDevicesName = hostDevicesName;
    }

    public void setHostDevicesTexts(List<String> hostDevicesText) {
        this.hostDevicesText = hostDevicesText;
    }

    public void setDeviceTypes(List<String> deviceTypes) {
        this.deviceTypes = deviceTypes;
    }

    public void setParentHbaNames(List<String> parentHbaNames) {
        this.parentHbaNames = parentHbaNames;
    }

    public void setVmAllocations(Map<String, String> vmAllocations) {
        this.vmAllocations = vmAllocations;
    }

    public Map<String, String> getVmAllocations() {
        return this.vmAllocations;
    }

}
