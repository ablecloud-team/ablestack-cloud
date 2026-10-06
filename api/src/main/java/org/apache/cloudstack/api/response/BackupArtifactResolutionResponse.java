// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.cloudstack.api.response;

import org.apache.cloudstack.api.BaseResponse;
import org.apache.cloudstack.api.EntityReference;
import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;

@EntityReference(value = org.apache.cloudstack.backup.Backup.class)
public class BackupArtifactResolutionResponse extends BaseResponse {
    @SerializedName("matched") @Param(description = "An exact volume pipeline artifact or recorded restore job was found") public boolean matched;
    @SerializedName("id") @Param(description = "Logical Mold backup UUID") public String id;
    @SerializedName("virtualmachineid") @Param(description = "Original VM UUID") public String virtualmachineid;
    @SerializedName("vmname") @Param(description = "Original VM instance name") public String vmname;
    @SerializedName("provider") @Param(description = "ABLESTACK provider") public String provider;
    @SerializedName("timestamp") @Param(description = "Exact logical backup timestamp") public String timestamp;
    @SerializedName("artifacttype") @Param(description = "METADATA or VOLUME") public String artifacttype;
    @SerializedName("artifactpath") @Param(description = "Original artifact selection") public String artifactpath;
    @SerializedName("metadatapath") @Param(description = "Metadata Job selection for external UI restores") public String metadatapath;
    @SerializedName("tracked") @Param(description = "This external restore job was submitted by Mold; its post callback must not start another restore") public boolean tracked;
    @SerializedName("restoreallowed") @Param(description = "Metadata UI restore may trigger Mold; VM must be Stopped and the logical backup restorable") public boolean restoreallowed;
    @SerializedName("reason") @Param(description = "Why a post callback must skip or wait") public String reason;

    public BackupArtifactResolutionResponse() { setObjectName("backupartifact"); }
}

