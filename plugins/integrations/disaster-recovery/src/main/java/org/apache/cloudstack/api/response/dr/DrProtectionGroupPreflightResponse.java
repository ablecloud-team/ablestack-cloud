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

import java.util.List;

import org.apache.cloudstack.api.BaseResponse;

import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;

public class DrProtectionGroupPreflightResponse extends BaseResponse {
    @SerializedName("action") @Param(description = "the normalized group action") private String action;
    @SerializedName("ready") @Param(description = "whether every selected plan is eligible") private boolean ready;
    @SerializedName("plans") @Param(description = "ordered per-plan preflight results")
    private List<DrProtectionGroupPlanPreflightResponse> plans;

    public void setAction(String value) { action = value; }
    public void setReady(boolean value) { ready = value; }
    public void setPlans(List<DrProtectionGroupPlanPreflightResponse> value) { plans = value; }
}
