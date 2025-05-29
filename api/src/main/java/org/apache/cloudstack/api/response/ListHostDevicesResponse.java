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
public class ListHostDevicesResponse extends BaseResponse {

    @SerializedName(ApiConstants.HOSTDEVICES_NAME)
    @Param(description = "Allocated IP address")
    private List<String> hostDevicesName;

    @SerializedName(ApiConstants.HOSTDEVICES_TEXT)
    @Param(description = "the ID of the pod the  IP address belongs to")
    private List<String> hostDevicesText;

    @SerializedName("vmallocations")
    @Param(description = "Map of device to VM allocations")
    private Map<String, String> vmAllocations;

    public ListHostDevicesResponse(List<String> hostDevicesName, List<String> hostDevicesText) {
        this.hostDevicesName = hostDevicesName;
        this.hostDevicesText = hostDevicesText;
    }

    public ListHostDevicesResponse() {
        super();
        this.setObjectName("listhostdevices");
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

    public void setVmAllocations(Map<String, String> vmAllocations) {
        this.vmAllocations = vmAllocations;
    }

    public Map<String, String> getVmAllocations() {
        return this.vmAllocations;
    }

}
