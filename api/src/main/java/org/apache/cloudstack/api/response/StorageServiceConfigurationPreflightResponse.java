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
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.annotations.SerializedName;
public class StorageServiceConfigurationPreflightResponse extends BaseResponse {
    @SerializedName("status") @Param(description="Admission decision") private String status;
    @SerializedName("revision") @Param(description="Configuration revision") private Long revision;
    @SerializedName("observedat") @Param(description="Measurement time") private java.util.Date observedat;
    @SerializedName("expiresat") @Param(description="UI observation cache expiry") private java.util.Date expiresat;
    @SerializedName("fingerprint") @Param(description="Impact confirmation fingerprint") private String fingerprint;
    @SerializedName("checks") @Param(description="Bounded public resource checks") private JsonArray checks;
    @SerializedName("impact") @Param(description="Actual protocol impact") private JsonObject impact;
    @SerializedName("profile") @Param(description="Execution observation profile") private JsonObject profile;
    @SerializedName("cancellationsupported") @Param(description="Actual cancellation support") private Boolean cancellationsupported=false;
    public StorageServiceConfigurationPreflightResponse(JsonObject report) {
        setObjectName("configurationpreflight");
        status=report.get("status").getAsString(); revision=report.get("revision").getAsLong();
        observedat=new java.util.Date((long)(report.get("observedat").getAsDouble()*1000));
        expiresat=new java.util.Date((long)(report.get("expiresat").getAsDouble()*1000));
        fingerprint=report.get("fingerprint").getAsString();checks=report.getAsJsonArray("checks");
        impact=report.has("impact")?report.getAsJsonObject("impact"):new JsonObject();
        profile=report.has("profile")?report.getAsJsonObject("profile"):new JsonObject();
    }
}
