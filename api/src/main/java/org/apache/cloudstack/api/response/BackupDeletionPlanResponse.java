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

import java.util.List;
import java.util.stream.Collectors;
import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;
import org.apache.cloudstack.api.BaseResponse;
import org.apache.cloudstack.backup.BackupDeletionService;

public class BackupDeletionPlanResponse extends BaseResponse {
    @SerializedName("id") @Param(description = "Selected backup UUID") public String id;
    @SerializedName("provider") @Param(description = "Backup provider") public String provider;
    @SerializedName("forced") @Param(description = "Delete selected and dependent recovery points") public boolean forced;
    @SerializedName("deletionplan") @Param(description = "Fingerprint to pass to deleteBackup; changed scope requires a fresh preview") public String deletionplan;
    @SerializedName("state") @Param(description = "READY or WAITING_DEPENDENCIES") public String state;
    @SerializedName("reason") @Param(description = "Reason ordinary deletion is deferred") public String reason;
    @SerializedName("backup") @Param(description = "Recovery points to delete, leaves first") public List<Point> backup;
    @SerializedName("dependentbackup") @Param(description = "Dependent recovery points which prevent ordinary deletion") public List<Point> dependentbackup;

    public static class Point {
        @SerializedName("id") @Param(description = "Backup UUID") public String id;
        @SerializedName("name") @Param(description = "Backup name") public String name;
        @SerializedName("type") @Param(description = "FULL or INCREMENTAL") public String type;
        Point(BackupDeletionService.Entry entry) { id = entry.uuid; name = entry.name; type = entry.type; }
    }

    public BackupDeletionPlanResponse(BackupDeletionService.Plan plan) {
        id = plan.rootUuid;
        provider = plan.provider;
        forced = plan.forced;
        deletionplan = plan.fingerprint;
        state = plan.state;
        reason = plan.reason;
        backup = plan.backups.stream().map(Point::new).collect(Collectors.toList());
        dependentbackup = plan.dependents.stream().map(Point::new).collect(Collectors.toList());
        setObjectName("backupdeletionplan");
    }
}
