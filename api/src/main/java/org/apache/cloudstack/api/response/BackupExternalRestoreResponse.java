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
import org.apache.cloudstack.backup.ThirdPartyExternalRestore;
import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;

public class BackupExternalRestoreResponse {
    @SerializedName("jobid") @Param(description = "External restore Job or session ID") public String jobId;
    @SerializedName("externalid") @Param(description = "Exact metadata backup image or restore point") public String externalId;
    @SerializedName("date") @Param(description = "Time the rejected restore was recorded") public Date date;
    @SerializedName("host") @Param(description = "Host receiving the metadata") public String host;
    @SerializedName("step") @Param(description = "PRECHECK: rejected before VM restoration") public String step;
    @SerializedName("cleanupstate") @Param(description = "Metadata cleanup: WAITING or COMPLETED") public String cleanupState;
    @SerializedName("cleanupdetails") @Param(description = "Why rejected metadata cleanup is waiting") public String cleanupDetails;

    public BackupExternalRestoreResponse(ThirdPartyExternalRestore.Attempt attempt) {
        jobId = attempt.jobId;
        externalId = attempt.externalId;
        date = new Date(attempt.recordedAt);
        host = attempt.proof == null ? null : attempt.proof.hostName;
        step = attempt.step;
        cleanupState = attempt.cleanupState;
        cleanupDetails = attempt.cleanupDetails;
    }
}
