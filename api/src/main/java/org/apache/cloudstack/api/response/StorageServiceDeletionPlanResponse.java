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
public class StorageServiceDeletionPlanResponse extends BaseResponse {
    @SerializedName("id") @Param(description="shared filesystem ID") private String id;
    @SerializedName("datavolumepolicy") @Param(description="requested data-volume retention policy") private String policy;
    @SerializedName("planhash") @Param(description="stable hash of the planned data-volume inventory") private String planHash;
    @SerializedName("plan") @Param(description="server-authoritative impact inventory; no deletion is performed") private String plan;
    public void setId(String value) { id=value; }
    public void setPolicy(String value) { policy=value; }
    public void setPlanHash(String value) { planHash=value; }
    public void setPlan(String value) { plan=value; }
}
