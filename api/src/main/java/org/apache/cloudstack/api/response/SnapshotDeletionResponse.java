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

import java.util.ArrayList;
import java.util.List;
import com.google.gson.annotations.SerializedName;
import com.cloud.serializer.Param;
import org.apache.cloudstack.api.BaseResponse;

public class SnapshotDeletionResponse extends BaseResponse {
    @SerializedName("success") @Param(description = "Diagnosis completed successfully")
    private final boolean success = true;
    @SerializedName("snapshotid") @Param(description = "Snapshot UUID")
    private String snapshotId;
    @SerializedName("name") @Param(description = "Snapshot name")
    private String name;
    @SerializedName("state") @Param(description = "Global snapshot state")
    private String state;
    @SerializedName("zoneid") @Param(description = "Selected zone UUID; absent for all zones")
    private String zoneId;
    @SerializedName("dryrun") @Param(description = "Read-only deletion diagnosis")
    private boolean dryRun = true;
    @SerializedName("reference") @Param(description = "Store references in the selected scope", responseObject = Reference.class)
    private final List<Reference> references = new ArrayList<>();

    public SnapshotDeletionResponse() {
        setObjectName("deletesnapshotresponse");
    }

    public SnapshotDeletionResponse(String snapshotId, String name, String state, String zoneId) {
        this();
        this.snapshotId = snapshotId;
        this.name = name;
        this.state = state;
        this.zoneId = zoneId;
    }

    @SerializedName("scopehash") @Param(description = "Revision of the diagnosed deletion scope")
    private String scopeHash;

    public void setState(String state) { this.state = state; }

    public void setScopeHash(String scopeHash) {
        this.scopeHash = scopeHash;
    }

    public void addReference(long id, String storeId, String storeName, String role, String state, String zoneId) {
        references.add(new Reference(id, storeId, storeName, role, state, zoneId));
    }

    @SerializedName("force") @Param(description = "Explicit administrator forced cleanup mode")
    private boolean force;
    @SerializedName("dbcleanupcomplete") @Param(description = "Selected database metadata was logically deleted")
    private Boolean dbCleanupComplete;
    @SerializedName("physicalcleanupcomplete") @Param(description = "All identified physical targets settled and no pending audit remains; unknown mappings cannot prove absence")
    private Boolean physicalCleanupComplete;
    @SerializedName("pendingcleanup") @Param(description = "Unverified physical target audit entries retained for inspection or retry")
    private int pendingCleanup;
    @SerializedName("cleanupresult") @Param(description = "Forced cleanup plan or per-reference results", responseObject = CleanupResult.class)
    private final List<CleanupResult> cleanupResults = new ArrayList<>();

    public void setForce(boolean force, int pending) { this.force = force; this.pendingCleanup = pending; }
    public void setExecutionResult(boolean physicalComplete, int pending) {
        this.force = true;
        this.dryRun = false;
        this.dbCleanupComplete = true;
        this.physicalCleanupComplete = physicalComplete;
        this.pendingCleanup = pending;
    }
    public void addCleanupResult(long id, String role, String action, String reason) {
        cleanupResults.add(new CleanupResult(id, role, action, reason));
    }
    public boolean isPhysicalCleanupComplete() { return Boolean.TRUE.equals(physicalCleanupComplete); }
    public int getPendingCleanup() { return pendingCleanup; }

    public static class CleanupResult extends BaseResponse {
        @SerializedName("referenceid") @Param(description = "Store reference ID, or zero for unmapped metadata")
        private long referenceId;
        @SerializedName("role") @Param(description = "Store role")
        private String role;
        @SerializedName("action") @Param(description = "PHYSICAL_DELETED, DB_ONLY, or planned PHYSICAL_THEN_DB")
        private String action;
        @SerializedName("reason") @Param(description = "Stable result or missing-information reason")
        private String reason;
        CleanupResult(long id, String role, String action, String reason) {
            this.referenceId = id; this.role = role; this.action = action; this.reason = reason;
        }
    }

    public static class Reference extends BaseResponse {
        @SerializedName("referenceid") @Param(description = "Exact store reference identifier")
        private long referenceId;
        @SerializedName("storeid") @Param(description = "Store UUID")
        private String storeId;
        @SerializedName("storename") @Param(description = "Store name")
        private String storeName;
        @SerializedName("role") @Param(description = "Store role")
        private String role;
        @SerializedName("state") @Param(description = "Reference state; Hidden retains a dependency chain")
        private String state;
        @SerializedName("zoneid") @Param(description = "Store zone UUID")
        private String zoneId;

        Reference(long id, String storeId, String storeName, String role, String state, String zoneId) {
            this.referenceId = id;
            this.storeId = storeId;
            this.storeName = storeName;
            this.role = role;
            this.state = state;
            this.zoneId = zoneId;
        }
    }
}
