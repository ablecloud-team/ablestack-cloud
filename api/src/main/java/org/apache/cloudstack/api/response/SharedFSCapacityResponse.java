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

/** Read-only derived SharedFS capacity. Existing size fields retain their original semantics. */
public class SharedFSCapacityResponse extends BaseResponse {
    @SerializedName("version") @Param(description = "SharedFS capacity version")
    public int version = 1;
    @SerializedName("initialvolumebytes") @Param(description = "SharedFS capacity initialvolumebytes")
    public Long initialVolumeBytes;
    @SerializedName("provisionedbytes") @Param(description = "SharedFS capacity provisionedbytes")
    public Long provisionedBytes;
    @SerializedName("volumecount") @Param(description = "SharedFS capacity volumecount")
    public Integer volumeCount;
    @SerializedName("provisioningstatus") @Param(description = "SharedFS capacity provisioningstatus")
    public String provisioningStatus = "UNAVAILABLE";
    @SerializedName("filesystemusedbytes") @Param(description = "SharedFS capacity filesystemusedbytes")
    public Long filesystemUsedBytes;
    @SerializedName("filesystemtotalbytes") @Param(description = "SharedFS capacity filesystemtotalbytes")
    public Long filesystemTotalBytes;
    @SerializedName("filesystemcount") @Param(description = "SharedFS capacity filesystemcount")
    public int filesystemCount = 0;
    @SerializedName("observedvolumecount") @Param(description = "SharedFS capacity observedvolumecount")
    public int observedVolumeCount = 0;
    @SerializedName("unsupportedvolumecount") @Param(description = "SharedFS capacity unsupportedvolumecount")
    public int unsupportedVolumeCount = 0;
    @SerializedName("unobservedvolumecount") @Param(description = "SharedFS capacity unobservedvolumecount")
    public int unobservedVolumeCount = 0;
    @SerializedName("usagestatus") @Param(description = "SharedFS capacity usagestatus")
    public String usageStatus = "UNAVAILABLE";
    @SerializedName("observedat") @Param(description = "SharedFS capacity observedat")
    public String observedAt;
    @SerializedName("collectedat") @Param(description = "SharedFS capacity collectedat")
    public String collectedAt;
    @SerializedName("reason") @Param(description = "SharedFS capacity reason")
    public String reason;
    @SerializedName("volumes") @Param(description = "SharedFS capacity volumes")
    public java.util.List<Volume> volumes;

    public static class Volume extends BaseResponse {
        @SerializedName("id") @Param(description = "Backing DATA id")
        public String id;
        @SerializedName("name") @Param(description = "Backing DATA name")
        public String name;
        @SerializedName("size") @Param(description = "Backing DATA size")
        public Long size;
        @SerializedName("state") @Param(description = "Backing DATA state")
        public String state;
        @SerializedName("hidden") @Param(description = "Backing DATA hidden")
        public boolean hidden;
        @SerializedName("status") @Param(description = "Backing DATA status")
        public String status;
        @SerializedName("filesystem") @Param(description = "Backing DATA filesystem")
        public String filesystem;
        @SerializedName("filesystemuuid") @Param(description = "Backing DATA filesystemuuid")
        public String filesystemUuid;
        @SerializedName("usedbytes") @Param(description = "Backing DATA usedbytes")
        public Long usedBytes;
        @SerializedName("totalbytes") @Param(description = "Backing DATA totalbytes")
        public Long totalBytes;
        @SerializedName("observedat") @Param(description = "Backing DATA observedat")
        public String observedAt;
        @SerializedName("nfsreferences") @Param(description = "Backing DATA nfsreferences")
        public Integer nfsReferences;
        @SerializedName("smbreferences") @Param(description = "Backing DATA smbreferences")
        public Integer smbReferences;
        @SerializedName("blockreferences") @Param(description = "Backing DATA blockreferences")
        public Integer blockReferences;
    }
}
