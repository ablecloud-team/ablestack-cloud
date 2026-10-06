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
public class SharedFSDeletionAuditResponse extends BaseResponse {
    @SerializedName("id") @Param(description="audit entry ID") private Long id;
    @SerializedName("sharedfsuuid") @Param(description="shared filesystem UUID retained after service removal") private String uuid;
    @SerializedName("phase") @Param(description="removal phase") private String phase;
    @SerializedName("policy") @Param(description="data volume policy") private String policy;
    @SerializedName("plan") @Param(description="recorded immutable impact inventory") private String plan;
    @SerializedName("created") @Param(description="audit creation time") private java.util.Date created;
    public void setId(Long value) { id=value; }
    public void setUuid(String value) { uuid=value; }
    public void setPhase(String value) { phase=value; }
    public void setPolicy(String value) { policy=value; }
    public void setPlan(String value) { plan=value; }
    public void setCreated(java.util.Date value) { created=value; }
}
