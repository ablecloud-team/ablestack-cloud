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
/** Configuration and read-only process-bound observation are deliberately separate. */
public class StorageNfsIdentityMappingResponse extends BaseResponse {
    @SerializedName("instanceid") @Param(description = "instanceid") private String instanceid;
    public void setInstanceid(String value) { instanceid = value; }
    @SerializedName("mode") @Param(description = "mode") private String mode;
    public void setMode(String value) { mode = value; }
    @SerializedName("policyrevision") @Param(description = "policyrevision") private Long policyrevision;
    public void setPolicyrevision(Long value) { policyrevision = value; }
    @SerializedName("effectivemode") @Param(description = "effectivemode") private String effectivemode;
    public void setEffectivemode(String value) { effectivemode = value; }
    @SerializedName("observationstatus") @Param(description = "observationstatus") private String observationstatus;
    public void setObservationstatus(String value) { observationstatus = value; }
    @SerializedName("observeddomain") @Param(description = "observeddomain") private String observeddomain;
    public void setObserveddomain(String value) { observeddomain = value; }
    @SerializedName("observedat") @Param(description = "observedat") private String observedat;
    public void setObservedat(String value) { observedat = value; }
    @SerializedName("securityflavors") @Param(description = "securityflavors") private com.google.gson.JsonArray securityflavors;
    public void setSecurityflavors(com.google.gson.JsonArray value) { securityflavors = value; }
    @SerializedName("endpoints") @Param(description = "endpoints") private com.google.gson.JsonArray endpoints;
    public void setEndpoints(com.google.gson.JsonArray value) { endpoints = value; }
    public StorageNfsIdentityMappingResponse() { setObjectName("nfsidentitymapping"); }
}
