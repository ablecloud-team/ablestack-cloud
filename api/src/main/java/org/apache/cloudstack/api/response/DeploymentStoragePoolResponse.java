/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.cloudstack.api.response;

import java.util.List;
import org.apache.cloudstack.api.BaseResponse;
import org.apache.cloudstack.api.EntityReference;
import org.apache.cloudstack.api.ApiConstants;
import com.cloud.serializer.Param;
import com.cloud.storage.StoragePool;
import com.google.gson.annotations.SerializedName;

@EntityReference(value = StoragePool.class)
public class DeploymentStoragePoolResponse extends BaseResponse {
    @SerializedName(ApiConstants.ID) @Param(description = "Storage pool UUID") private String id;
    @SerializedName(ApiConstants.NAME) @Param(description = "Storage pool name") private String name;
    @SerializedName("type") @Param(description = "Storage pool type") private String type;
    @SerializedName("scope") @Param(description = "Storage pool scope") private String scope;
    @SerializedName("disksizetotal") @Param(description = "Reported capacity in bytes") private Long total;
    @SerializedName("disksizeallocated") @Param(description = "Allocated capacity including reservations and template overhead") private Long allocated;
    @SerializedName("physicalavailable") @Param(description = "Reported unused physical bytes; distinct from allocatable capacity") private Long physicalAvailable;
    @SerializedName("allocationavailable") @Param(description = "Additional allocatable bytes using the deployment capacity policy") private Long allocationAvailable;
    @SerializedName("requiredbytes") @Param(description = "Requested bytes for this storage pool") private Long requiredBytes;
    @SerializedName("suitable") @Param(description = "Whether the requested size fits") private boolean suitable;
    @SerializedName("hostids") @Param(description = "UUIDs of common eligible deployment hosts") private List<String> hostIds;

    @SerializedName("unsuitablereason") @Param(description = "Capacity or IOPS limitation") private String unsuitableReason;
    @SerializedName("diskoffering") @Param(description = "Effective disk offering for this candidate") private DiskOfferingResponse diskOffering;
    public void setDiskOffering(DiskOfferingResponse offering) { diskOffering = offering; }
    public void setIopsSufficient(boolean sufficient) {
        if (!sufficient) { suitable = false; unsuitableReason = "iops"; }
    }
    public void setPool(StoragePool pool, Long allocated, Long physical, Long available, Long required, List<String> hosts, String poolScope) {
        id = pool.getUuid(); name = pool.getName(); type = pool.getPoolType().toString(); scope = poolScope;
        total = pool.getCapacityBytes(); this.allocated = allocated; physicalAvailable = physical; allocationAvailable = available;
        requiredBytes = required; suitable = required != null && available != null && required <= available; hostIds = hosts;
        if (required != null && !suitable) { unsuitableReason = "capacity"; }
        setObjectName("deploymentstoragepool");
    }
}
