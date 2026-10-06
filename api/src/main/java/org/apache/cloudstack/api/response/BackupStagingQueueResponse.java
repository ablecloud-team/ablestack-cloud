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

import java.util.Date;
import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;
import org.apache.cloudstack.backup.ThirdPartyBackupAdmission;

public class BackupStagingQueueResponse {
    @SerializedName("stagingjobid") @Param(description = "Exact staging attempt ID") private String jobId;
    @SerializedName("operation") @Param(description = "BACKUP or RESTORE") private String operation;
    @SerializedName("state") @Param(description = "WAITING, ADMITTING, ADMITTED, CANCEL_REQUESTED or RELEASED") private String state;
    @SerializedName("reason") @Param(description = "Capacity, concurrency, timeout or cancellation reason") private String reason;
    @SerializedName("queuedat") @Param(description = "Time of queue admission") private Date queuedAt;
    @SerializedName("deadline") @Param(description = "Deadline for waiting; never expires an active reservation") private Date deadline;
    @SerializedName("requiredbytes") @Param(description = "Staging bytes including buffer and shared primary scratch") private long requiredBytes;
    @SerializedName("effectiveavailablebytes") @Param(description = "Available staging bytes after active reservations at the last capacity check") private long effectiveAvailableBytes;
    @SerializedName("primarystorage") @Param(description = "Primary restore storage claims, held while admission is ADMITTING or ADMITTED")
    private java.util.List<PrimaryStorageResponse> primaryStorage;

    public static class PrimaryStorageResponse {
        @SerializedName("storagekey") @Param(description = "Physical Ceph pool or mounted filesystem identity") private String storageKey;
        @SerializedName("requiredbytes") @Param(description = "All prepared volumes plus primary restore overhead") private long requiredBytes;
        @SerializedName("availablebytes") @Param(description = "Physical available bytes at the last admission check") private long availableBytes;
        @SerializedName("effectiveavailablebytes") @Param(description = "Physical available bytes minus all active reservations") private long effectiveAvailableBytes;

        PrimaryStorageResponse(ThirdPartyBackupAdmission.PrimaryClaim claim) {
            storageKey = claim.storageKey;
            requiredBytes = claim.requiredBytes;
            availableBytes = claim.availableBytes;
            effectiveAvailableBytes = claim.effectiveAvailableBytes;
        }
    }

    public BackupStagingQueueResponse(ThirdPartyBackupAdmission.Entry entry) {
        jobId = entry.jobId;
        operation = entry.operation;
        state = entry.state;
        reason = entry.reason;
        queuedAt = new Date(entry.queuedAt);
        deadline = new Date(entry.deadline);
        requiredBytes = entry.requiredBytes;
        effectiveAvailableBytes = entry.effectiveAvailableBytes;
        primaryStorage = entry.primaryClaims == null ? java.util.List.of()
                : entry.primaryClaims.stream().map(PrimaryStorageResponse::new).collect(java.util.stream.Collectors.toList());
    }
}
