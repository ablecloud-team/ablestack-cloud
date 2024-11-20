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
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseResponse;
import org.apache.cloudstack.api.EntityReference;

@EntityReference(value = Host.class)
public class LicenseHostResponse extends BaseResponse {

    // @SerializedName(ApiConstants.LICENSEHOST_NAME)
    // @Param(description = "Name of the License Host")
    // private List<String> licenseHostName;

    @SerializedName(ApiConstants.LICENSEHOST_VALUE)
    @Param(description = "Value of the License Host")
    private List<String> licenseHostValue;

    public LicenseHostResponse() {
        super();
        this.setObjectName("licensehost");
    }

    public LicenseHostResponse(List<String> licenseHostValue) {
        // this.licenseHostName = licenseHostName;
        this.licenseHostValue = licenseHostValue;
        this.setObjectName("licensehost");
    }

    // public List<String> getLicenseHostName() {
    //     return licenseHostName;
    // }

    public List<String> getLicenseHostValue() {
        return licenseHostValue;
    }

    // public void setLicenseHostName(List<String> licenseHostName) {
    //     this.licenseHostName = licenseHostName;
    // }

    public void setLicenseHostValue(List<String> licenseHostValue) {
        this.licenseHostValue = licenseHostValue;
    }
}
