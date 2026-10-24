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

import java.util.List;
import java.util.Map;
import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;
import org.apache.cloudstack.api.BaseResponse;

/** Read-only Secondary copy support and exact target revision. */
public class SnapshotCopyTargetsResponse extends BaseResponse {
    @SerializedName("snapshotid") @Param(description = "Snapshot UUID")
    private String snapshotId;
    @SerializedName("sourcezoneid") @Param(description = "Frozen source Zone UUID")
    private String sourceZoneId;
    @SerializedName("sourcezonename") @Param(description = "Source Zone name")
    private String sourceZoneName;
    @SerializedName("sourcestoreid") @Param(description = "Source image store UUID, administrators only")
    private String sourceStoreId;
    @SerializedName("sourcestorename") @Param(description = "Source image store name, administrators only")
    private String sourceStoreName;
    @SerializedName("eligible") @Param(description = "Whether the source supports Secondary copy")
    private boolean eligible;
    @SerializedName("reason") @Param(description = "Stable source rejection code, empty when supported")
    private String reason;
    @SerializedName("scopehash") @Param(description = "Source and destination revision; pass as expectedscopehash")
    private String scopeHash;
    @SerializedName("destinations") @Param(description = "Visible destination Zones with eligibility, reason, method and optional administrator store identity")
    private List<Map<String, Object>> destinations;

    public SnapshotCopyTargetsResponse(String snapshotId, String zoneId, String zoneName, String storeId, String storeName,
            boolean eligible, String reason, String scopeHash, List<Map<String, Object>> destinations) {
        this.snapshotId = snapshotId; this.sourceZoneId = zoneId; this.sourceZoneName = zoneName;
        this.sourceStoreId = storeId; this.sourceStoreName = storeName;
        this.eligible = eligible; this.reason = reason; this.scopeHash = scopeHash; this.destinations = destinations;
        setObjectName("snapshotcopytargets");
    }
    public boolean isEligible() { return eligible; }
    public String getReason() { return reason; }
    public String getScopeHash() { return scopeHash; }
    public List<Map<String, Object>> getDestinations() { return destinations; }
}
