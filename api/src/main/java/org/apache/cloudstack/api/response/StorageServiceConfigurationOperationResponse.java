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
/** Public, credential-free operation projection. Private before-images are never serialized. */
public class StorageServiceConfigurationOperationResponse extends BaseResponse {
    @SerializedName("id") @Param(description = "configuration operation id") private String id;
    @SerializedName("instanceid") @Param(description = "configuration operation instanceid") private String instanceid;
    @SerializedName("requestid") @Param(description = "configuration operation requestid") private String requestid;
    @SerializedName("state") @Param(description = "configuration operation state") private String state;
    @SerializedName("phase") @Param(description = "configuration operation phase") private String phase;
    @SerializedName("protocol") @Param(description = "configuration operation protocol") private String protocol;
    @SerializedName("resourceid") @Param(description = "configuration operation resourceid") private String resourceid;
    @SerializedName("baserevision") @Param(description = "configuration operation baserevision") private Long baserevision;
    @SerializedName("targetrevision") @Param(description = "configuration operation targetrevision") private Long targetrevision;
    @SerializedName("canresume") @Param(description = "configuration operation canresume") private Boolean canresume;
    @SerializedName("originalfailure") @Param(description = "configuration operation originalfailure") private com.google.gson.JsonObject originalfailure;
    @SerializedName("rollback") @Param(description = "configuration operation rollback") private com.google.gson.JsonObject rollback;
    @SerializedName("observation") @Param(description = "configuration operation observation") private com.google.gson.JsonObject observation;
    @SerializedName("created") @Param(description = "configuration operation created") private java.util.Date created;
    @SerializedName("updated") @Param(description = "configuration operation updated") private java.util.Date updated;
    @SerializedName("completed") @Param(description = "configuration operation completed") private java.util.Date completed;
    @SerializedName("result") @Param(description = "committed public API result") private com.google.gson.JsonElement result;
    @SerializedName("candidate") @Param(description = "accepted public request without credentials") private com.google.gson.JsonObject candidate;
    public void setResult(final com.google.gson.JsonElement value) { result = value; }
    public void setCandidate(final com.google.gson.JsonObject value) { candidate = value; }
    public void setId(final String value) { id = value; }
    public void setInstanceid(final String value) { instanceid = value; }
    public void setRequestid(final String value) { requestid = value; }
    public void setState(final String value) { state = value; }
    public void setPhase(final String value) { phase = value; }
    public void setProtocol(final String value) { protocol = value; }
    public void setResourceid(final String value) { resourceid = value; }
    public void setBaserevision(final Long value) { baserevision = value; }
    public void setTargetrevision(final Long value) { targetrevision = value; }
    public void setCanresume(final Boolean value) { canresume = value; }
    public void setOriginalfailure(final com.google.gson.JsonObject value) { originalfailure = value; }
    public void setRollback(final com.google.gson.JsonObject value) { rollback = value; }
    public void setObservation(final com.google.gson.JsonObject value) { observation = value; }
    public void setCreated(final java.util.Date value) { created = value; }
    public void setUpdated(final java.util.Date value) { updated = value; }
    public void setCompleted(final java.util.Date value) { completed = value; }
}
