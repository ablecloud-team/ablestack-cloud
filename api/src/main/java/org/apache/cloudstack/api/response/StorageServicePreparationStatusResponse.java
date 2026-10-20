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
import java.util.List;
/** Fresh readiness is advisory; the server repeats it before initial mutation. */
public class StorageServicePreparationStatusResponse extends BaseResponse {
    @SerializedName("id") @Param(description="Storage Service instance UUID") private String id;
    @SerializedName("sharedfilesystemid") @Param(description="Shared FileSystem UUID") private String sharedFilesystemId;
    @SerializedName("purpose") @Param(description="Requested preparation purpose") private String purpose;
    @SerializedName("ready") @Param(description="Whether the current boot permits the requested next step") private boolean ready;
    @SerializedName("status") @Param(description="Preparation state") private String status;
    @SerializedName("reason") @Param(description="Closed preparation reason code") private String reason;
    @SerializedName("message") @Param(description="Safe preparation description") private String message;
    @SerializedName("bootid") @Param(description="Observed current native boot UUID") private String bootId;
    @SerializedName("observedatmillis") @Param(description="Server observation completion epoch milliseconds") private long observedAtMillis;
    @SerializedName("expiresatmillis") @Param(description="Advisory observation expiry epoch milliseconds") private long expiresAtMillis;
    @SerializedName("conditions") @Param(description="Readiness condition details") private List<Condition> conditions;
    @SerializedName("runtimeclisha256") @Param(description="Observed native CLI SHA256") private String runtimeCliSha256;
    public static class Condition {
        @SerializedName("name") @Param(description="Condition") private String name;
        @SerializedName("ready") @Param(description="Condition ready") private boolean ready;
        @SerializedName("status") @Param(description="Closed condition status") private String status;
        public Condition(String name,boolean ready,String status){this.name=name;this.ready=ready;this.status=status;}
    }
    public void setId(String v){id=v;} public void setSharedFilesystemId(String v){sharedFilesystemId=v;}
    public void setPurpose(String v){purpose=v;} public void setReady(boolean v){ready=v;}
    public void setStatus(String v){status=v;} public void setReason(String v){reason=v;} public void setMessage(String v){message=v;}
    public void setBootId(String v){bootId=v;} public void setObservedAtMillis(long v){observedAtMillis=v;}
    public void setExpiresAtMillis(long v){expiresAtMillis=v;} public void setConditions(List<Condition> v){conditions=v;}
    public void setRuntimeCliSha256(String v){runtimeCliSha256=v;}
}
