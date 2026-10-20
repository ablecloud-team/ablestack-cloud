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

import org.apache.cloudstack.api.BaseResponse;

import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;

public class StorageServiceProtocolEndpointResponse extends BaseResponse {
    @SerializedName("ipaddress")
    @Param(description = "effective service endpoint IP address")
    private String ipAddress;

    @SerializedName("port")
    @Param(description = "effective service endpoint port")
    private Integer port;

    @SerializedName("role")
    @Param(description = "endpoint address role: PRIMARY, ALIAS, or DEDICATED")
    private String role;

    @SerializedName("runtimestate") @Param(description="Fresh owned listener state: READY, DEGRADED, UNAVAILABLE, or DISABLED") private String runtimeState;
    @SerializedName("listening") @Param(description="Fresh exact IP and port TCP readiness") private Boolean listening;
    @SerializedName("listenerowned") @Param(description="Listener belongs to the SMB service") private Boolean listenerOwned;
    @SerializedName("observedepoch") @Param(description="Guest observation epoch") private Double observedEpoch;
    @SerializedName("diagnostic") @Param(description="Fresh endpoint verification limitation") private String diagnostic;
    public void setRuntimeState(String value){runtimeState=value;}
    public void setListening(Boolean value){listening=value;}
    public void setListenerOwned(Boolean value){listenerOwned=value;}
    public void setObservedEpoch(Double value){observedEpoch=value;}
    public void setDiagnostic(String value){diagnostic=value;}

    public void setIpAddress(final String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public void setPort(final Integer port) {
        this.port = port;
    }

    public void setRole(final String role) {
        this.role = role;
    }
}
