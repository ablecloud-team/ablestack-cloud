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

import com.google.gson.annotations.SerializedName;
import org.apache.cloudstack.api.EntityReference;
import java.util.List;
import com.cloud.host.Host;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseResponse;

import com.cloud.serializer.Param;

@EntityReference(value = Host.class)
public class ListHostDevicesResponse extends BaseResponse {

    @SerializedName(ApiConstants.PCI_NAME)
    @Param(description = "Allocated IP address")
    private List<String> pciNames;

    @SerializedName(ApiConstants.PCI_TEXT)
    @Param(description = "the ID of the pod the  IP address belongs to")
    private List<String> pciTexts;

    public ListHostDevicesResponse(List<String> pciNames, List<String> pciTexts) {
        this.pciNames = pciNames;
        this.pciTexts = pciTexts;
    }

    public ListHostDevicesResponse() {
        super();
        this.setObjectName("listhostdevices");
    }

    public List<String> getPciNames() {
        return pciNames;
    }

    public List<String> getPciTexts() {
        return pciTexts;
    }

    public void setPciNames(List<String> pciNames) {
        this.pciNames = pciNames;
    }

    public void setPciTexts(List<String> pciTexts) {
        this.pciTexts = pciTexts;
    }

}
