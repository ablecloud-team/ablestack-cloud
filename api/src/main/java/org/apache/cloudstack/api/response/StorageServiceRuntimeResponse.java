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

import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;

public class StorageServiceRuntimeResponse extends BaseResponse {
    @SerializedName(ApiConstants.ID)
    @Param(description = "the ID of the Storage Service instance")
    private String id;

    @SerializedName("operation")
    @Param(description = "runtime operation")
    private String operation;

    @SerializedName("success")
    @Param(description = "whether the runtime operation succeeded")
    private Boolean success;

    @SerializedName("status")
    @Param(description = "runtime status")
    private String status;

    @SerializedName("details")
    @Param(description = "operation details")
    private String details;

    @SerializedName("resultjson")
    @Param(description = "raw runtime result JSON")
    private String resultJson;

    public void setId(final String id) {
        this.id = id;
    }

    public void setOperation(final String operation) {
        this.operation = operation;
    }

    public void setSuccess(final Boolean success) {
        this.success = success;
    }

    public void setStatus(final String status) {
        this.status = status;
    }

    public void setDetails(final String details) {
        this.details = details;
    }

    public void setResultJson(final String resultJson) {
        String normalized = resultJson;
        while (normalized != null && normalized.contains("\\=")) {
            normalized = normalized.replace("\\=", "=");
        }
        this.resultJson = normalized;
    }
}
