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

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseResponse;
import org.apache.cloudstack.api.EntityReference;

import com.cloud.ssv.SSV;
import com.cloud.network.Network.GuestType;
import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;

@SuppressWarnings("unused")
@EntityReference(value = {SSV.class})
public class SSVNetResponse extends BaseResponse {
    @SerializedName(ApiConstants.ID)
    @Param(description = "the id of the Shared Storage VM")
    private String id;

    @SerializedName(ApiConstants.NETWORK_ID)
    @Param(description = "the ID of the network of the Shared Storage VM")
    private long networkId;

    @SerializedName(ApiConstants.NETWORK_NAME)
    @Param(description = "the name of the Shared Storage VM")
    private String networkName;

    @SerializedName(ApiConstants.NETWORK_TYPE)
    @Param(description = "the ID of the network of the Shared Storage VM")
    private GuestType networkType;

    @SerializedName("networkip")
    @Param(description = "the name of the network of the Shared Storage VM")
    private String networkIp;

    @SerializedName("networkgateway")
    @Param(description = "the account associated with the Shared Storage VM")
    private String networkGateway;

    @SerializedName("networknetmask")
    @Param(description = "the project id of the Shared Storage VM")
    private String networkNetmask;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public void setNetworkName(String networkName) {
        this.networkName = networkName;
    }

    public void setNetworkId(long networkId) {
        this.networkId = networkId;
    }

    public void setNetworkType(GuestType networkType) {
        this.networkType = networkType;
    }

    public void setNetworkIp(String networkIp) {
        this.networkIp = networkIp;
    }

    public void setNetworkGateway(String networkGateway) {
        this.networkGateway = networkGateway;
    }

    public void setNetworkNetmask(String networkNetmask) {
        this.networkNetmask = networkNetmask;
    }

}
