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
import org.apache.cloudstack.storage.dataservice.StoragePosixDirectoryPolicy;
import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;

@EntityReference(value = StoragePosixDirectoryPolicy.class)
public class StoragePosixDirectoryPolicyResponse extends BaseResponse {
    @SerializedName("id")
    @Param(description = "Directory policy UUID")
    private String id;
    @SerializedName("instanceid")
    @Param(description = "Storage Service instance UUID")
    private String instanceId;
    @SerializedName("volumeid")
    @Param(description = "Backing volume UUID")
    private String volumeId;
    @SerializedName("relativepath")
    @Param(description = "Canonical volume-relative directory path")
    private String relativePath;
    @SerializedName("canonicalpath")
    @Param(description = "Read-only actual managed backing directory path")
    private String canonicalPath;
    @SerializedName("revision")
    @Param(description = "Common directory policy revision")
    private Long revision;
    @SerializedName("state")
    @Param(description = "Policy lifecycle state")
    private String state;
    @SerializedName("config")
    @Param(description = "Protocol-neutral desired owner, mode, access and default ACL")
    private String config;
    @SerializedName("effective")
    @Param(description = "Observed stat, ACL and resolved numeric principals")
    private String effective;
    @SerializedName("driftstatus")
    @Param(description = "CONSISTENT, DRIFT, UNOBSERVED or CONFLICT")
    private String driftStatus;
    @SerializedName("affectedshares")
    @Param(description = "NFS exports and SMB shares sharing this canonical path")
    private java.util.List<String> affectedShares;
    @SerializedName("preview")
    @Param(description = "Version 2 permission preview, stat identity, recommendation and approval token JSON")
    private String preview;
    public void setPreview(String value) { preview = value; }
    public void setId(String value) { id = value; }
    public void setInstanceId(String value) { instanceId = value; }
    public void setVolumeId(String value) { volumeId = value; }
    public void setRelativePath(String value) { relativePath = value; }
    public void setCanonicalPath(String value) { canonicalPath = value; }
    public void setRevision(Long value) { revision = value; }
    public void setState(String value) { state = value; }
    public void setConfig(String value) { config = value; }
    public void setEffective(String value) { effective = value; }
    public void setDriftStatus(String value) { driftStatus = value; }
    public void setAffectedShares(java.util.List<String> value) { affectedShares = value; }
}
