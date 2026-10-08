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

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseResponse;
import org.apache.cloudstack.api.EntityReference;
import org.apache.cloudstack.backup.Backup;

import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;

@EntityReference(value = Backup.class)
public class BackupJobStatusResponse extends BaseResponse {

    @SerializedName(ApiConstants.ID)
    @Param(description = "ID of the Instance backup")
    private String id;

    @SerializedName(ApiConstants.STATUS)
    @Param(description = "Backup row status")
    private Backup.Status status;

    @SerializedName(ApiConstants.BACKUP_JOB_STATE)
    @Param(description = "Current backup operation state")
    private String state;

    @SerializedName(ApiConstants.BACKUP_JOB_STEP)
    @Param(description = "Current backup operation step")
    private String step;

    @SerializedName(ApiConstants.DETAILS)
    @Param(description = "Current host-side backup or restore job details")
    private String details;

    @SerializedName(ApiConstants.BACKUP_JOB_PROGRESS)
    @Param(description = "Current host-side backup job progress percentage")
    private Integer progress;

    @SerializedName("volumeindex")
    @Param(description = "Current volume index, starting at one")
    private Integer volumeIndex;

    @SerializedName("volumecount")
    @Param(description = "Number of volumes in the logical backup or restore")
    private Integer volumeCount;

    @SerializedName("stagingqueue")
    @Param(description = "Persisted staging admission and waiting reason for this operation")
    private BackupStagingQueueResponse stagingQueue;

    @SerializedName("vmrestore")
    @Param(description = "VM primary volume transaction outcome, separate from external file restore jobs")
    private BackupStagingInfoResponse.VmRestoreInfo vmRestore;

    @SerializedName("hoststate")
    @Param(description = "Host data restore state, separate from instance creation and startup")
    private String hostState;

    @SerializedName("restoreoperationtype")
    @Param(description = "Tracked restore operation type")
    private String restoreOperationType;

    @SerializedName("restoretargetvmid")
    @Param(description = "UUID of the instance created from the backup")
    private String restoreTargetVmId;

    @SerializedName("restoretargetvmname")
    @Param(description = "Name of the instance created from the backup")
    private String restoreTargetVmName;

    @SerializedName("restoretargetvmstate")
    @Param(description = "Current state of the instance created from the backup")
    private String restoreTargetVmState;

    public void setHostState(String value) { hostState = value; }
    public void setRestoreOperationType(String value) { restoreOperationType = value; }
    public void setRestoreTargetVmId(String value) { restoreTargetVmId = value; }
    public void setRestoreTargetVmName(String value) { restoreTargetVmName = value; }
    public void setRestoreTargetVmState(String value) { restoreTargetVmState = value; }

    public void setVmRestore(BackupStagingInfoResponse.VmRestoreInfo value) { vmRestore = value; }

    public void setStagingQueue(BackupStagingQueueResponse value) { stagingQueue = value; }

    public void setVolumeIndex(Integer value) { volumeIndex = value; }
    public void setVolumeCount(Integer value) { volumeCount = value; }

    @SerializedName(ApiConstants.BACKUP_JOB_EVENTS_OFFSET)
    @Param(description = "Offset for the next backup job events query")
    private Long eventsOffset;

    @SerializedName(ApiConstants.BACKUP_JOB_EVENTS)
    @Param(description = "JSON encoded backup job events returned from the host")
    private String events;

    @SerializedName(ApiConstants.BACKUP_JOB_LOG_PATH)
    @Param(description = "Host-side backup job log path")
    private String logPath;

    @SerializedName(ApiConstants.BACKUP_JOB_EXIT_CODE)
    @Param(description = "Host-side backup job exit code")
    private Integer exitCode;

    @SerializedName(ApiConstants.BACKUP_JOB_OPERATION)
    @Param(description = "Host-side backup job operation")
    private String operation;

    @SerializedName(ApiConstants.BACKUP_JOB_CAPABILITIES)
    @Param(description = "Comma-separated host-side backup job capabilities")
    private String capabilities;

    @SerializedName(ApiConstants.BACKUP_JOB_BANDWIDTH_LIMIT_MBPS)
    @Param(description = "Current requested backup bandwidth limit in Mbps")
    private Integer bandwidthLimitMbps;

    @SerializedName(ApiConstants.BACKUP_JOB_BANDWIDTH_STATUS)
    @Param(description = "Current backup bandwidth adjustment status")
    private String bandwidthStatus;

    public String getId() {
        return id;
    }

    public void setId(final String id) {
        this.id = id;
    }

    public Backup.Status getStatus() {
        return status;
    }

    public void setStatus(final Backup.Status status) {
        this.status = status;
    }

    public String getState() {
        return state;
    }

    public void setState(final String state) {
        this.state = state;
    }

    public String getStep() {
        return step;
    }

    public void setStep(final String step) {
        this.step = step;
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(final String details) {
        this.details = details;
    }

    public Integer getProgress() {
        return progress;
    }

    public void setProgress(final Integer progress) {
        this.progress = progress;
    }

    public Long getEventsOffset() {
        return eventsOffset;
    }

    public void setEventsOffset(final Long eventsOffset) {
        this.eventsOffset = eventsOffset;
    }

    public String getEvents() {
        return events;
    }

    public void setEvents(final String events) {
        this.events = events;
    }

    public String getLogPath() {
        return logPath;
    }

    public void setLogPath(final String logPath) {
        this.logPath = logPath;
    }

    public Integer getExitCode() {
        return exitCode;
    }

    public void setExitCode(final Integer exitCode) {
        this.exitCode = exitCode;
    }

    public String getOperation() {
        return operation;
    }

    public void setOperation(final String operation) {
        this.operation = operation;
    }

    public String getCapabilities() {
        return capabilities;
    }

    public void setCapabilities(final String capabilities) {
        this.capabilities = capabilities;
    }

    public Integer getBandwidthLimitMbps() {
        return bandwidthLimitMbps;
    }

    public void setBandwidthLimitMbps(final Integer bandwidthLimitMbps) {
        this.bandwidthLimitMbps = bandwidthLimitMbps;
    }

    public String getBandwidthStatus() {
        return bandwidthStatus;
    }

    public void setBandwidthStatus(final String bandwidthStatus) {
        this.bandwidthStatus = bandwidthStatus;
    }
}
