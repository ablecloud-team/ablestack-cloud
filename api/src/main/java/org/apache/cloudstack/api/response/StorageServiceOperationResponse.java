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
import org.apache.cloudstack.api.EntityReference;
import org.apache.cloudstack.storage.dataservice.StorageServiceOperation;
import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;

@EntityReference(StorageServiceOperation.class)
public class StorageServiceOperationResponse extends BaseResponse {
    @SerializedName("id") @Param(description = "Storage Service operation id") private String id;
    @SerializedName("instanceid") @Param(description = "Storage Service operation instanceid") private String instanceid;
    @SerializedName("action") @Param(description = "Storage Service operation action") private String action;
    @SerializedName("state") @Param(description = "Storage Service operation state") private String state;
    @SerializedName("phase") @Param(description = "Storage Service operation phase") private String phase;
    @SerializedName("revision") @Param(description = "Storage Service operation revision") private long revision;
    @SerializedName("progress") @Param(description = "Storage Service operation progress") private int progress;
    @SerializedName("created") @Param(description = "Storage Service operation created") private java.util.Date created;
    @SerializedName("heartbeat") @Param(description = "Storage Service operation heartbeat") private java.util.Date heartbeat;
    @SerializedName("completed") @Param(description = "Storage Service operation completed") private java.util.Date completed;
    @SerializedName("diagnostic") @Param(description = "Storage Service operation diagnostic") private String diagnostic;
    public void setId(String value) { id = value; }
    public void setInstanceid(String value) { instanceid = value; }
    public void setAction(String value) { action = value; }
    public void setState(String value) { state = value; }
    public void setPhase(String value) { phase = value; }
    public void setRevision(long value) { revision = value; }
    public void setProgress(int value) { progress = value; }
    public void setCreated(java.util.Date value) { created = value; }
    public void setHeartbeat(java.util.Date value) { heartbeat = value; }
    public void setCompleted(java.util.Date value) { completed = value; }
    public void setDiagnostic(String value) { diagnostic = value; }
}
