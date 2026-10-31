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
// specific language govening permissions and limitations
// under the License.

package org.apache.cloudstack.api.response;
import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.apache.cloudstack.api.BaseResponse;
import org.apache.cloudstack.api.EntityReference;
import org.apache.cloudstack.storage.dataservice.StorageDirectoryPolicy;
@EntityReference(value = StorageDirectoryPolicy.class)
public class StorageDirectoryPolicyResponse extends BaseResponse {
    @SerializedName("id") @Param(description = "id") private String id;
    public void setId(String value) { id=value; }
    @SerializedName("instanceid") @Param(description = "instanceid") private String instanceid;
    public void setInstanceid(String value) { instanceid=value; }
    @SerializedName("volumeid") @Param(description = "volumeid") private String volumeid;
    public void setVolumeid(String value) { volumeid=value; }
    @SerializedName("path") @Param(description = "path") private String path;
    public void setPath(String value) { path=value; }
    @SerializedName("owneruid") @Param(description = "owneruid") private Long owneruid;
    public void setOwneruid(Long value) { owneruid=value; }
    @SerializedName("ownergid") @Param(description = "ownergid") private Long ownergid;
    public void setOwnergid(Long value) { ownergid=value; }
    @SerializedName("mode") @Param(description = "mode") private String mode;
    public void setMode(String value) { mode=value; }
    @SerializedName("policyrevision") @Param(description = "policyrevision") private Long policyrevision;
    public void setPolicyrevision(Long value) { policyrevision=value; }
    @SerializedName("observationstatus") @Param(description = "observationstatus") private String observationstatus;
    public void setObservationstatus(String value) { observationstatus=value; }
    @SerializedName("observation") @Param(description = "observation") private JsonObject observation;
    public void setObservation(JsonObject value) { observation=value; }
    @SerializedName("references") @Param(description = "references") private JsonArray references;
    public void setReferences(JsonArray value) { references=value; }
    public StorageDirectoryPolicyResponse() { setObjectName("directorypolicy"); }
}
