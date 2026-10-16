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

import java.util.Date;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseResponse;

import com.cloud.serializer.Param;
import com.cloud.utils.DateUtil;
import com.google.gson.annotations.SerializedName;

public class NetBackupBackupCandidateResponse extends BaseResponse {
    @SerializedName(ApiConstants.VIRTUAL_MACHINE_ID)
    @Param(description = "ID of the Instance")
    private String vmId;

    @SerializedName(ApiConstants.VIRTUAL_MACHINE_NAME)
    @Param(description = "Name of the Instance")
    private String vmName;

    @SerializedName("instancename")
    @Param(description = "Internal Instance name used by the hypervisor")
    private String instanceName;

    @SerializedName(ApiConstants.SCHEDULE_ID)
    @Param(description = "ID of the backup schedule")
    private String scheduleId;

    @SerializedName(ApiConstants.SCHEDULE)
    @Param(description = "Backup schedule expression")
    private String schedule;

    @SerializedName("intervaltype")
    @Param(description = "The interval type of the backup schedule")
    private DateUtil.IntervalType intervalType;

    @SerializedName("scheduledtimestamp")
    @Param(description = "Time this backup schedule was due")
    private Date scheduledTimestamp;

    @SerializedName("nextscheduledtimestamp")
    @Param(description = "Next scheduled time after this candidate was claimed")
    private Date nextScheduledTimestamp;

    @SerializedName(ApiConstants.QUIESCE_VM)
    @Param(description = "quiesce the instance before checkpointing the disks for backup")
    private Boolean quiesceVM;

    @SerializedName(ApiConstants.POLICY_ID)
    @Param(description = "NetBackup policy ID/name")
    private String policyId;

    @SerializedName(ApiConstants.HOST_ID)
    @Param(description = "Host ID where the Instance is running")
    private String hostId;

    @SerializedName(ApiConstants.HOST_NAME)
    @Param(description = "Host name where the Instance is running")
    private String hostName;

    public void setVmId(final String vmId) {
        this.vmId = vmId;
    }

    public void setVmName(final String vmName) {
        this.vmName = vmName;
    }

    public void setInstanceName(final String instanceName) {
        this.instanceName = instanceName;
    }

    public void setScheduleId(final String scheduleId) {
        this.scheduleId = scheduleId;
    }

    public void setSchedule(final String schedule) {
        this.schedule = schedule;
    }

    public void setIntervalType(final DateUtil.IntervalType intervalType) {
        this.intervalType = intervalType;
    }

    public void setScheduledTimestamp(final Date scheduledTimestamp) {
        this.scheduledTimestamp = scheduledTimestamp;
    }

    public void setNextScheduledTimestamp(final Date nextScheduledTimestamp) {
        this.nextScheduledTimestamp = nextScheduledTimestamp;
    }

    public void setQuiesceVM(final Boolean quiesceVM) {
        this.quiesceVM = quiesceVM;
    }

    public void setPolicyId(final String policyId) {
        this.policyId = policyId;
    }

    public void setHostId(final String hostId) {
        this.hostId = hostId;
    }

    public void setHostName(final String hostName) {
        this.hostName = hostName;
    }
}
