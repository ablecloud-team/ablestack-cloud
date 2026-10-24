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

import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;
import org.apache.cloudstack.api.BaseResponse;

/** Structural archive eligibility; physical backup verification is repeated on execution. */
public class SnapshotArchiveResponse extends BaseResponse {
    @SerializedName("snapshotid") @Param(description = "Snapshot UUID")
    private String snapshotId;
    @SerializedName("name") @Param(description = "Snapshot name")
    private String name;
    @SerializedName("state") @Param(description = "Current snapshot state")
    private String state;
    @SerializedName("eligible") @Param(description = "Whether the archive plan may be executed")
    private boolean eligible;
    @SerializedName("reason") @Param(description = "Stable eligibility or rejection reason code")
    private String reason;
    @SerializedName("message") @Param(description = "Explanation of the archive plan or rejection")
    private String message;
    @SerializedName("primaryreferences") @Param(description = "Non-destroyed primary reference count")
    private int primaryReferences;
    @SerializedName("secondaryreferences") @Param(description = "Non-destroyed secondary reference count")
    private int secondaryReferences;
    @SerializedName("scopehash") @Param(description = "Inspected scope revision; pass as expectedscopehash")
    private String scopeHash;
    @SerializedName("dryrun") @Param(description = "Read-only eligibility diagnosis")
    private boolean dryrun = true;

    public SnapshotArchiveResponse() {
        setObjectName("archivesnapshotresponse");
    }

    public SnapshotArchiveResponse(String id, String name, String state, boolean eligible, String reason, String message,
            int primaryCount, int secondaryCount, String scopeHash) {
        this();
        this.snapshotId = id;
        this.name = name;
        this.state = state;
        this.eligible = eligible;
        this.reason = reason;
        this.message = message;
        this.primaryReferences = primaryCount;
        this.secondaryReferences = secondaryCount;
        this.scopeHash = scopeHash;
    }

    public boolean isEligible() { return eligible; }
    public String getReason() { return reason; }
    public String getMessage() { return message; }
}
