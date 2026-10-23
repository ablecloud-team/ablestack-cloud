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

import java.util.Collections;
import java.util.Map;
import org.apache.cloudstack.api.BaseResponse;
import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;

public class VolumeAllocationCapabilityResponse extends BaseResponse {
    public VolumeAllocationCapabilityResponse() {
        setObjectName("volumeallocationcapability");
    }

    @SerializedName("contractversion") @Param(description = "Allocation protocol version") private int contractVersion = 1;
    @SerializedName("storageid") @Param(description = "Pool UUID") private String storageId;
    @SerializedName("hostid") @Param(description = "Host UUID") private String hostId;
    @SerializedName("allocationformat") @Param(description = "Requested format") private String format;
    @SerializedName("operation") @Param(description = "Requested operation") private String operation;
    @SerializedName("backend") @Param(description = "Agent-verified backend identity") private String backend = "unknown";
    @SerializedName("supported") @Param(description = "End-to-end explicit allocation execution is available") private boolean supported;
    @SerializedName("reason") @Param(description = "Stable reason code when unavailable") private String reason;
    @SerializedName("agentpolicies") @Param(description = "Validated Agent policy to method declarations, not an execution guarantee")
    private Map<String, String> agentPolicies = Collections.emptyMap();
    public void setScope(String storage, String host, String imageFormat, String op) {
        storageId = storage; hostId = host; format = imageFormat; operation = op;
    }
    public void setReason(String value) { reason = value; }
    public String getReason() { return reason; }
    public boolean isSupported() { return supported; }
    public void setSupported(boolean value) { supported = value; }
    public void setAgentPolicies(String value, Map<String, String> policies) { backend = value; agentPolicies = policies; }
    public Map<String, String> getAgentPolicies() { return agentPolicies; }
}
