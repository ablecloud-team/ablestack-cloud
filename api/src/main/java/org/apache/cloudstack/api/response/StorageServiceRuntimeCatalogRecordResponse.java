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
public class StorageServiceRuntimeCatalogRecordResponse extends BaseResponse {
    @SerializedName("id") @Param(description = "catalogue id") private String id;
    public void setId(String value) { id = value; }
    @SerializedName("bundleid") @Param(description = "catalogue bundleid") private String bundleid;
    public void setBundleid(String value) { bundleid = value; }
    @SerializedName("name") @Param(description = "catalogue name") private String name;
    public void setName(String value) { name = value; }
    @SerializedName("type") @Param(description = "catalogue type") private String type;
    public void setType(String value) { type = value; }
    @SerializedName("state") @Param(description = "catalogue state") private String state;
    public void setState(String value) { state = value; }
    @SerializedName("phase") @Param(description = "catalogue phase") private String phase;
    public void setPhase(String value) { phase = value; }
    @SerializedName("requestid") @Param(description = "catalogue requestid") private String requestid;
    public void setRequestid(String value) { requestid = value; }
    @SerializedName("compatibility") @Param(description = "catalogue compatibility") private String compatibility;
    public void setCompatibility(String value) { compatibility = value; }
    @SerializedName("detailsjson") @Param(description = "catalogue detailsjson") private String detailsjson;
    public void setDetailsjson(String value) { detailsjson = value; }
    @SerializedName("errorcode") @Param(description = "catalogue errorcode") private String errorcode;
    public void setErrorcode(String value) { errorcode = value; }
    @SerializedName("errormessage") @Param(description = "catalogue errormessage") private String errormessage;
    public void setErrormessage(String value) { errormessage = value; }
    @SerializedName("instanceid") @Param(description = "catalogue instanceid") private String instanceid;
    public void setInstanceid(String value) { instanceid = value; }
    @SerializedName("vmid") @Param(description = "catalogue vmid") private String vmid;
    public void setVmid(String value) { vmid = value; }
    @SerializedName("sharedfilesystemid") @Param(description = "catalogue sharedfilesystemid") private String sharedfilesystemid;
    public void setSharedfilesystemid(String value) { sharedfilesystemid = value; }
    @SerializedName("action") @Param(description = "catalogue action") private String action;
    public void setAction(String value) { action = value; }
    @SerializedName("reason") @Param(description = "catalogue reason") private String reason;
    public void setReason(String value) { reason = value; }
    @SerializedName("revision") @Param(description = "catalogue revision") private Long revision;
    public void setRevision(Long value) { revision = value; }
    @SerializedName("actoruserid") @Param(description = "catalogue actoruserid") private Long actoruserid;
    public void setActoruserid(Long value) { actoruserid = value; }
    @SerializedName("created") @Param(description = "catalogue created") private java.util.Date created;
    public void setCreated(java.util.Date value) { created = value; }
    @SerializedName("completed") @Param(description = "catalogue completed") private java.util.Date completed;
    public void setCompleted(java.util.Date value) { completed = value; }
}
