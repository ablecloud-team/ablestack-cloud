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

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;
import org.apache.cloudstack.api.BaseResponse;

/** Admin-only diagnostics. An unknown Host or external result never permits cleanup. */
public class BackupStagingInfoResponse extends BaseResponse {
    @SerializedName("id") @Param(description = "ID of the logical backup") public String id;
    @SerializedName("operation") @Param(description = "BACKUP or RESTORE") public String operation;
    @SerializedName("stagingjobid") @Param(description = "Exact staging attempt ID") public String stagingjobid;
    @SerializedName("provider") @Param(description = "ABLESTACK backup provider") public String provider;
    @SerializedName("vmname") @Param(description = "Source VM instance name") public String vmname;
    @SerializedName("targetvmname") @Param(description = "Actual restore target VM; unknown for legacy attempts") public String targetvmname;
    @SerializedName("timestamp") @Param(description = "Logical backup timestamp") public String timestamp;
    @SerializedName("hostname") @Param(description = "Worker Host name") public String hostname;
    @SerializedName("destination") @Param(description = "Staging destination") public String destination;
    @SerializedName("hoststate") @Param(description = "Host engine state; UNKNOWN when unconfirmed") public String hoststate;
    @SerializedName("step") @Param(description = "Current Host engine step") public String step;
    @SerializedName("progress") @Param(description = "Host engine progress") public Integer progress;
    @SerializedName("volumeindex") @Param(description = "One-based current or last Host volume index") public Integer volumeindex;
    @SerializedName("volumecount") @Param(description = "Selected volume count") public Integer volumecount;
    @SerializedName("currentvolumeid") @Param(description = "Current or last source volume UUID") public String currentvolumeid;
    @SerializedName("hosterror") @Param(description = "Host query error") public String hosterror;
    @SerializedName("reservedbytes") @Param(description = "Actual staging and shared scratch reservation; null when unconfirmed") public Long reservedbytes;
    @SerializedName("reservationstate") @Param(description = "Host reservation state") public String reservationstate;
    @SerializedName("reservationerror") @Param(description = "Reservation inspection error") public String reservationerror;
    @SerializedName("reconciliationerror") @Param(description = "Last manual requery error; unknown jobs retain their reservations") public String reconciliationerror;
    @SerializedName("cleanupstate") @Param(description = "Cleanup state") public String cleanupstate;
    @SerializedName("cleanupreason") @Param(description = "Reason cleanup is waiting") public String cleanupreason;
    @SerializedName("sourcecleanupstate") @Param(description = "Retirement of the previous RBD source snapshot") public String sourcecleanupstate;
    @SerializedName("sourcecleanupreason") @Param(description = "Why previous source snapshot cleanup is pending") public String sourcecleanupreason;
    @SerializedName("startstate") @Param(description = "Persisted restore start state; null for legacy operations") public String startstate;
    @SerializedName("startreason") @Param(description = "Restore start failure or unconfirmed preparation reason") public String startreason;
    @SerializedName("startsubmittedat") @Param(description = "Time of the dispatch intent before the Host call") public Date startsubmittedat;
    @SerializedName("startcheckedat") @Param(description = "Time the Host start state was confirmed") public Date startcheckedat;
    @SerializedName("checkedat") @Param(description = "Time of this inspection") public Date checkedat;
    @SerializedName("stagingqueue") @Param(description = "Persisted admission state") public BackupStagingQueueResponse stagingqueue;
    @SerializedName("artifact") @Param(description = "Owned and inherited volume artifacts") public List<ArtifactInfo> artifact = new ArrayList<>();

    @SerializedName("historical") @Param(description = "Past restore attempt; management actions are disabled") public boolean historical;
    @SerializedName("hostcheckedat") @Param(description = "Time of the last persisted Host state confirmation") public Date hostcheckedat;
    @SerializedName("restoreattempt") @Param(description = "Recorded restore attempts for this backup") public List<RestoreAttemptInfo> restoreattempt = new ArrayList<>();
    @SerializedName("vmrestore") @Param(description = "Confirmed VM primary volume outcome, separate from external file restore completion") public VmRestoreInfo vmrestore;

    public static class RestoreAttemptInfo {
        @SerializedName("stagingjobid") @Param(description = "Exact restore attempt ID") public String stagingjobid;
        @SerializedName("hostname") @Param(description = "Recorded Worker Host") public String hostname;
        @SerializedName("createdat") @Param(description = "Restore attempt creation; unknown for legacy operations") public Date createdat;
        @SerializedName("cleanupstate") @Param(description = "Recorded cleanup state") public String cleanupstate;
        @SerializedName("hoststate") @Param(description = "Last confirmed Host state") public String hoststate;
        @SerializedName("current") @Param(description = "Current restore attempt") public boolean current;
        @SerializedName("vmrestoreoutcome") @Param(description = "Last recorded primary volume transaction outcome") public String vmrestoreoutcome;
    }

    public static class VmRestoreInfo {
        @SerializedName("stagingjobid") @Param(description = "Exact restore attempt owning the VM result") public String stagingjobid;
        @SerializedName("transactionid") @Param(description = "Host primary volume transaction ID") public String transactionid;
        @SerializedName("revision") @Param(description = "Monotonic Host receipt revision") public Long revision;
        @SerializedName("outcome") @Param(description = "NOT_RECORDED, NOT_STARTED, RUNNING, COMMITTED, ROLLED_BACK, RECOVERY_REQUIRED, FAILED or UNKNOWN") public String outcome = "NOT_RECORDED";
        @SerializedName("phase") @Param(description = "Actual VM volume transaction phase") public String phase = "NOT_RECORDED";
        @SerializedName("primarycleanupstate") @Param(description = "Cleanup of retained originals or prepared volumes") public String primarycleanupstate = "NOT_RECORDED";
        @SerializedName("failure") @Param(description = "Original VM transaction failure") public String failure;
        @SerializedName("recoveryerror") @Param(description = "Rollback, commit recovery or primary cleanup failure") public String recoveryerror;
        @SerializedName("queryerror") @Param(description = "Latest result query error; previous confirmed outcome is retained") public String queryerror;
        @SerializedName("checkedat") @Param(description = "Last Host result query time") public Date checkedat;
        @SerializedName("updatedat") @Param(description = "Time of the last Host journal receipt update") public Date updatedat;
        @SerializedName("preparedat") @Param(description = "Time all prepared volumes were validated") public Date preparedat;
        @SerializedName("switchstartedat") @Param(description = "Time the VM volume switch began") public Date switchstartedat;
        @SerializedName("committedat") @Param(description = "Persisted VM commit decision time") public Date committedat;
        @SerializedName("rollbackstartedat") @Param(description = "Time rollback began") public Date rollbackstartedat;
        @SerializedName("rolledbackat") @Param(description = "Time all original volume destinations were recovered") public Date rolledbackat;
        @SerializedName("primarycleanupcompletedat") @Param(description = "Time original/prepared volume cleanup completed") public Date primarycleanupcompletedat;
        @SerializedName("volume") @Param(description = "Per-volume primary preparation, switch, rollback and cleanup results") public List<VmVolumeInfo> volume = new ArrayList<>();

        public VmRestoreInfo(org.apache.cloudstack.backup.ThirdPartyBackupRestore.VmResult result, long checkedAt, String error) {
            checkedat = date(checkedAt);
            queryerror = error;
            if (result == null) { return; }
            stagingjobid = result.jobId;
            transactionid = result.transactionId;
            revision = result.revision;
            outcome = result.outcome;
            phase = result.phase;
            primarycleanupstate = result.primaryCleanupState;
            failure = result.failure;
            recoveryerror = result.recoveryError;
            updatedat = date(result.updatedAt);
            preparedat = date(result.preparedAt);
            switchstartedat = date(result.switchStartedAt);
            committedat = date(result.committedAt);
            rollbackstartedat = date(result.rollbackStartedAt);
            rolledbackat = date(result.rolledBackAt);
            primarycleanupcompletedat = date(result.primaryCleanupCompletedAt);
            for (var item : result.volumes) { volume.add(new VmVolumeInfo(item)); }
        }
    }

    public static class VmVolumeInfo {
        @SerializedName("index") @Param(description = "One-based selected volume index") public int index;
        @SerializedName("volumeid") @Param(description = "Source volume UUID") public String volumeid;
        @SerializedName("poolid") @Param(description = "Destination primary storage pool UUID") public String poolid;
        @SerializedName("destination") @Param(description = "Actual primary volume destination") public String destination;
        @SerializedName("hadoriginal") @Param(description = "Original destination existed before preparation; null until observed") public Boolean hadoriginal;
        @SerializedName("preparestate") @Param(description = "Primary volume preparation state") public String preparestate;
        @SerializedName("switchstate") @Param(description = "Primary volume switch state") public String switchstate;
        @SerializedName("rollbackstate") @Param(description = "Original destination rollback state") public String rollbackstate;
        @SerializedName("cleanupstate") @Param(description = "Retained original or prepared volume cleanup state") public String cleanupstate;
        @SerializedName("failure") @Param(description = "Volume preparation or switch failure") public String failure;
        @SerializedName("preparestartedat") @Param(description = "Preparation start") public Date preparestartedat;
        @SerializedName("preparedat") @Param(description = "Prepared volume confirmed") public Date preparedat;
        @SerializedName("switchstartedat") @Param(description = "Switch intent before moving the original") public Date switchstartedat;
        @SerializedName("switchedat") @Param(description = "Prepared volume moved to the destination") public Date switchedat;
        @SerializedName("rollbackstartedat") @Param(description = "Rollback start for this volume") public Date rollbackstartedat;
        @SerializedName("rolledbackat") @Param(description = "Original destination recovered") public Date rolledbackat;
        @SerializedName("cleanedat") @Param(description = "Retained original or prepared volume cleanup completed") public Date cleanedat;

        public VmVolumeInfo(org.apache.cloudstack.backup.ThirdPartyBackupRestore.VmVolumeResult item) {
            index = item.index + 1;
            volumeid = item.volumeUuid;
            poolid = item.poolUuid;
            destination = item.destination;
            hadoriginal = item.hadOriginal;
            preparestate = item.prepareState;
            switchstate = item.switchState;
            rollbackstate = item.rollbackState;
            cleanupstate = item.cleanupState;
            failure = item.failure;
            preparestartedat = date(item.prepareStartedAt);
            preparedat = date(item.preparedAt);
            switchstartedat = date(item.switchStartedAt);
            switchedat = date(item.switchedAt);
            rollbackstartedat = date(item.rollbackStartedAt);
            rolledbackat = date(item.rolledBackAt);
            cleanedat = date(item.cleanedAt);
        }
    }

    private static Date date(long timestamp) { return timestamp > 0 ? new Date(timestamp) : null; }

    public BackupStagingInfoResponse() { setObjectName("backupstaginginfo"); }

    public static class ArtifactInfo {
        @SerializedName("index") @Param(description = "Owned artifact index for BACKUP; request sequence for RESTORE") public Integer index;
        @SerializedName("volumeid") @Param(description = "Source volume UUID; null for metadata") public String volumeid;
        @SerializedName("path") @Param(description = "Exact artifact path") public String path;
        @SerializedName("backupid") @Param(description = "Logical backup owning this artifact") public String backupid;
        @SerializedName("externaljobid") @Param(description = "External operation Job ID") public String externaljobid;
        @SerializedName("externalid") @Param(description = "External catalog reference") public String externalid;
        @SerializedName("backuptime") @Param(description = "External point in time") public String backuptime;
        @SerializedName("metadata") @Param(description = "Metadata operation") public boolean metadata;
        @SerializedName("owned") @Param(description = "Owned by the selected logical backup") public boolean owned;
        @SerializedName("completed") @Param(description = "External operation completed successfully") public boolean completed;
        @SerializedName("submissionpending") @Param(description = "External acceptance is uncertain") public boolean submissionpending;
        @SerializedName("terminal") @Param(description = "External operation termination confirmed") public boolean terminal;
        @SerializedName("failure") @Param(description = "External failure") public String failure;
        @SerializedName("restorestate") @Param(description = "NOT_STARTED, NOT_RECORDED, RECORDED, UNCONFIRMED, RUNNING, COMPLETED or FAILED") public String restorestate;
        @SerializedName("chainindex") @Param(description = "Zero-based Full/incremental chain position") public Integer chainindex;
        @SerializedName("destination") @Param(description = "Exact persisted restore destination") public String destination;
        @SerializedName("requestrecorded") @Param(description = "An observed restore request is saved in Management DB") public boolean requestrecorded;
        @SerializedName("recordedat") @Param(description = "Time the request was recorded; unknown for legacy requests") public Date recordedat;
        @SerializedName("submittedat") @Param(description = "Time submission intent was saved before the external call") public Date submittedat;
        @SerializedName("completedat") @Param(description = "Time successful external completion was confirmed") public Date completedat;
        @SerializedName("terminalat") @Param(description = "Time external termination was confirmed") public Date terminalat;
        @SerializedName("acknowledgedat") @Param(description = "Time Host acknowledged the transfer result") public Date acknowledgedat;
        @SerializedName("checkedat") @Param(description = "Time of the latest external reconciliation attempt") public Date checkedat;
        @SerializedName("queryerror") @Param(description = "External reconciliation error; not proof of external failure") public String queryerror;
        @SerializedName("availability") @Param(description = "AVAILABLE, MISSING or UNKNOWN at the last manual requery") public String availability;
        @SerializedName("availabilityerror") @Param(description = "Exact inventory query error") public String availabilityerror;
        @SerializedName("availabilitycheckedat") @Param(description = "Time of exact inventory query") public Date availabilitycheckedat;
    }
}
