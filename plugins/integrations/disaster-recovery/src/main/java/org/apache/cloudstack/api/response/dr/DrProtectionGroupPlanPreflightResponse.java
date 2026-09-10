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

package org.apache.cloudstack.api.response.dr;

import java.util.Map;

import org.apache.cloudstack.api.BaseResponse;

import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;

public class DrProtectionGroupPlanPreflightResponse extends BaseResponse {
    @SerializedName("planid") @Param(description = "the DR plan UUID") private String planId;
    @SerializedName("planname") @Param(description = "the DR plan name") private String planName;
    @SerializedName("planstate") @Param(description = "the DR plan state") private String planState;
    @SerializedName("adminstate") @Param(description = "the DR plan administrative state") private String adminState;
    @SerializedName("eligible") @Param(description = "whether this plan can execute the group action") private boolean eligible;
    @SerializedName("reasoncode") @Param(description = "stable reason code when execution is blocked") private String reasonCode;
    @SerializedName("reasonargs") @Param(description = "non-sensitive reason arguments") private Map<String, String> reasonArgs;

    public void setPlanId(String value) { planId = value; }
    public void setPlanName(String value) { planName = value; }
    public void setPlanState(String value) { planState = value; }
    public void setAdminState(String value) { adminState = value; }
    public void setEligible(boolean value) { eligible = value; }
    public void setReasonCode(String value) { reasonCode = value; }
    public void setReasonArgs(Map<String, String> value) { reasonArgs = value; }
}
