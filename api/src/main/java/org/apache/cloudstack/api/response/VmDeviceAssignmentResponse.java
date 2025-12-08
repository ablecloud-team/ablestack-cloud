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

import com.google.gson.annotations.SerializedName;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseResponse;

public class VmDeviceAssignmentResponse extends BaseResponse {

    @SerializedName(ApiConstants.DEVICE_TYPE)
    private String deviceType;

    @SerializedName(ApiConstants.HOSTDEVICES_NAME)
    private String deviceName;

    @SerializedName(ApiConstants.HOSTDEVICES_TEXT)
    private String deviceDetail;

    @SerializedName(ApiConstants.HOST_ID)
    private Long hostId;

    @SerializedName(ApiConstants.HOST_NAME)
    private String hostName;

    public VmDeviceAssignmentResponse() {
        setObjectName("vmdeviceassignment");
    }

    public void setDeviceType(String deviceType) {
        this.deviceType = deviceType;
    }

    public void setDeviceName(String deviceName) {
        this.deviceName = deviceName;
    }

    public void setDeviceDetail(String deviceDetail) {
        this.deviceDetail = deviceDetail;
    }

    public void setHostId(Long hostId) {
        this.hostId = hostId;
    }

    public void setHostName(String hostName) {
        this.hostName = hostName;
    }
}

