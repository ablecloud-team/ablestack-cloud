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

import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;

import org.apache.cloudstack.api.BaseResponse;

import java.util.Map;

public class MigrationGuestConfigurationResponse extends BaseResponse {
    @SerializedName("contractversion")
    @Param(description = "Guest configuration protocol version")
    private int version = 1;

    @SerializedName("supported")
    @Param(description = "Supported on a live worker")
    private boolean supported;

    @SerializedName("verified")
    @Param(description = "Guest configuration verified")
    private boolean verified;

    @SerializedName("status")
    @Param(description = "Readiness state")
    private String status;

    @SerializedName("reason")
    @Param(description = "Structured reason")
    private String reason;

    @SerializedName("id")
    @Param(description = "VM identity")
    private String id;

    @SerializedName("storageid")
    @Param(description = "Storage capability scope")
    private String storageId;

    @SerializedName("planhash")
    @Param(description = "Frozen plan hash")
    private String planHash;

    @SerializedName("operationid")
    @Param(description = "Frozen operation identity")
    private String operationId;

    @SerializedName("observation")
    @Param(description = "Bound guest observation")
    private Map<String, Object> observation;

    public void capability(String storage, boolean ok, String why) {
        storageId = storage;
        supported = ok;
        reason = why;
        status = ok ? "SUPPORTED" : "UNSUPPORTED";
    }

    public void evidence(String vm, String hash, String operation, Map<String, Object> value) {
        id = vm;
        planHash = hash;
        operationId = operation;
        observation = value;
        supported = true;
        status = String.valueOf(value.get("status"));
        verified = "VERIFIED".equals(status);
        reason = (String) value.get("reason");
    }
}
