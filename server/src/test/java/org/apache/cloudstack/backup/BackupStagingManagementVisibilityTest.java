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
package org.apache.cloudstack.backup;

import java.util.HashMap;
import java.util.Map;

import org.apache.cloudstack.api.response.BackupStagingInfoResponse;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class BackupStagingManagementVisibilityTest {
    private Backup backup;
    private Map<String, String> details;

    @Before
    public void setup() {
        backup = mock(Backup.class);
        details = new HashMap<>();
        details.put(ThirdPartyBackupManifest.MODE_KEY, ThirdPartyBackupManifest.VOLUME_MODE);
        when(backup.getUuid()).thenReturn("backup-1");
        when(backup.getStatus()).thenReturn(Backup.Status.BackingUp);
        when(backup.getDetail(anyString())).thenAnswer(invocation -> details.get(invocation.getArgument(0)));
    }

    @Test
    public void cleanedFailedBackupWithNoCatalogOrReleasedReservationIsHidden() {
        when(backup.getStatus()).thenReturn(Backup.Status.Failed);
        details.put(ThirdPartyBackupManifest.CLEANUP_STATE_KEY, "COMPLETED");
        details.put(ThirdPartyBackupAdmission.BACKUP_KEY, "{\"jobId\":\"backup-1\",\"state\":\"RELEASED\"}");
        details.put(ThirdPartyBackupStart.DETAIL_KEY, "{\"state\":\"START_FAILED\",\"reason\":\"Previous failure\"}");

        assertFalse(ThirdPartyBackupVolumeService.needsStagingManagement(backup, null));
    }

    @Test
    public void ordinaryExecutionCapacityWaitAndFinalizationAreHidden() {
        details.put(ThirdPartyBackupAdmission.BACKUP_KEY, "{\"jobId\":\"backup-1\",\"state\":\"WAITING\"}");
        details.put(ThirdPartyBackupManifest.CATALOG_KEY, "{\"state\":\"PENDING\"}");
        assertFalse(ThirdPartyBackupVolumeService.needsStagingManagement(backup, null));

        details.put(ThirdPartyBackupManifest.FINALIZATION_STATE_KEY, "WAITING");
        details.put(ThirdPartyBackupManifest.FINALIZATION_DETAILS_KEY, "Waiting for Host finalization and logical backup completion");
        assertFalse(ThirdPartyBackupVolumeService.needsStagingManagement(backup, null));
    }

    @Test
    public void unconfirmedSubmissionRecordedAsWaitingPermitsRecovery() {
        details.put(ThirdPartyBackupManifest.CLEANUP_STATE_KEY, "WAITING");
        details.put(ThirdPartyBackupManifest.CLEANUP_DETAILS_KEY, "External submission is unconfirmed; waiting for an exact artifact job reference");
        assertTrue(ThirdPartyBackupVolumeService.needsStagingManagement(backup, null));
    }

    @Test
    public void completedStagingCleanupDoesNotHideUnreleasedReservationOrHostRecordFailure() {
        when(backup.getStatus()).thenReturn(Backup.Status.Failed);
        details.put(ThirdPartyBackupManifest.CLEANUP_STATE_KEY, "COMPLETED");
        details.put(ThirdPartyBackupAdmission.BACKUP_KEY, "{\"jobId\":\"backup-1\",\"state\":\"ADMITTED\"}");
        assertTrue(ThirdPartyBackupVolumeService.needsStagingManagement(backup, null));

        details.put(ThirdPartyBackupAdmission.BACKUP_KEY, "{\"jobId\":\"backup-1\",\"state\":\"RELEASED\"}");
        details.put(ThirdPartyBackupManifest.JOB_CLEANUP_STATE_KEY, "WAITING");
        assertTrue(ThirdPartyBackupVolumeService.needsStagingManagement(backup, null));
    }

    @Test
    public void failedRestoreCleanupAndSourceSnapshotCleanupAreVisible() {
        details.put(ThirdPartyBackupRestore.CLEANUP_STATE_KEY, "FAILED");
        assertTrue(ThirdPartyBackupVolumeService.needsStagingManagement(backup, null));
        details.remove(ThirdPartyBackupRestore.CLEANUP_STATE_KEY);
        details.put(ThirdPartyBackupManifest.SOURCE_CLEANUP_STATE_KEY, "WAITING");
        assertTrue(ThirdPartyBackupVolumeService.needsStagingManagement(backup, null));
    }

    @Test
    public void normalRestoreAndClosedRollbackAreHiddenButUnresolvedRecoveryRemainsVisible() {
        when(backup.getStatus()).thenReturn(Backup.Status.BackedUp);
        BackupStagingInfoResponse.VmRestoreInfo restore = new BackupStagingInfoResponse.VmRestoreInfo(null, 0, null);
        restore.outcome = "RUNNING";
        restore.primarycleanupstate = "RUNNING";
        assertFalse(ThirdPartyBackupVolumeService.needsStagingManagement(backup, restore));

        details.put(ThirdPartyBackupRestore.CLEANUP_STATE_KEY, "COMPLETED");
        restore.outcome = "ROLLED_BACK";
        restore.primarycleanupstate = "COMPLETED";
        restore.failure = "A volume failed before rollback";
        restore.queryerror = "Historical Host lookup failed";
        assertFalse(ThirdPartyBackupVolumeService.needsStagingManagement(backup, restore));

        restore.outcome = "RECOVERY_REQUIRED";
        assertTrue(ThirdPartyBackupVolumeService.needsStagingManagement(backup, restore));
    }

    @Test
    public void catalogQueryFailureIsVisibleButConfirmedPolicyExpirationIsHidden() {
        when(backup.getStatus()).thenReturn(Backup.Status.BackedUp);
        details.put(ThirdPartyBackupManifest.CATALOG_KEY, "{\"state\":\"AVAILABLE\",\"error\":\"Catalog endpoint unavailable\"}");
        assertTrue(ThirdPartyBackupVolumeService.needsStagingManagement(backup, null));

        when(backup.getStatus()).thenReturn(Backup.Status.Failed);
        details.put(ThirdPartyBackupManifest.CATALOG_FAILURE_KEY, "Required image expired");
        details.put(ThirdPartyBackupManifest.CLEANUP_STATE_KEY, "RETAINED");
        details.put(ThirdPartyBackupManifest.CATALOG_KEY, "{\"state\":\"EXPIRED\"}");
        assertFalse(ThirdPartyBackupVolumeService.needsStagingManagement(backup, null));
    }

    @Test
    public void unknownStartAndUnfinishedRestoreHostErrorsAreVisible() {
        details.put(ThirdPartyBackupStart.DETAIL_KEY, "{\"state\":\"SUBMISSION_PENDING\",\"reason\":\"Host response lost\"}");
        assertTrue(ThirdPartyBackupVolumeService.needsStagingManagement(backup, null));

        details.remove(ThirdPartyBackupStart.DETAIL_KEY);
        details.put(ThirdPartyBackupRestore.PLAN_KEY, "{\"jobId\":\"restore-1\"}");
        details.put(ThirdPartyBackupRestore.operationKey("restore-1"), "{\"hostError\":\"Host response lost\"}");
        assertTrue(ThirdPartyBackupVolumeService.needsStagingManagement(backup, null));
    }

    @Test
    public void corruptCurrentRecoveryRecordsRemainInspectable() {
        details.put(ThirdPartyBackupStart.DETAIL_KEY, "malformed");
        assertTrue(ThirdPartyBackupVolumeService.needsStagingManagement(backup, null));
    }

    @Test
    public void ordinaryProvidersAndDeletionResumeDoNotUseStagingManagement() {
        details.put(ThirdPartyBackupManifest.CLEANUP_STATE_KEY, "WAITING");
        details.remove(ThirdPartyBackupManifest.MODE_KEY);
        assertFalse(ThirdPartyBackupVolumeService.needsStagingManagement(backup, null));

        details.put(ThirdPartyBackupManifest.MODE_KEY, ThirdPartyBackupManifest.VOLUME_MODE);
        details.put(ThirdPartyBackupManifest.DELETE_PROGRESS_KEY, "{\"complete\":false}");
        assertFalse(ThirdPartyBackupVolumeService.needsStagingManagement(backup, null));
    }

    @Test
    public void olderCompletedBackupsRemainInspectableUntilMetadataCopiesAreRemoved() {
        when(backup.getStatus()).thenReturn(Backup.Status.BackedUp);
        String root = "/stage/ablestack-commvault/vm/2026.10.14.09.00.00.000";
        ThirdPartyBackupManifest manifest = ThirdPartyBackupManifest.create("ablestack-commvault", "backup-1", "vm",
                "2026.10.14.09.00.00.000", "FULL", "host", root, "QCOW2",
                java.util.List.of(new Backup.VolumeInfo("volume-1", "root.qcow2", com.cloud.storage.Volume.Type.ROOT,
                        1073741824L, 0L, null, null, null)), null, null);
        manifest.getCurrentArtifacts().forEach(artifact -> {
            artifact.completed = true;
            artifact.externalId = "1127";
            artifact.jobId = "1127";
        });
        ThirdPartyBackupManifest.Artifact metadata = new ThirdPartyBackupManifest.Artifact();
        metadata.backupUuid = "backup-1";
        metadata.path = root;
        metadata.sourceHost = "host";
        metadata.completed = true;
        metadata.externalId = "1129";
        metadata.jobId = "1129";
        manifest.setMetadata(metadata);
        manifest.setComplete(true);
        details.put(ThirdPartyBackupManifest.DETAIL_KEY, manifest.toJson());
        details.put(ThirdPartyBackupManifest.FINALIZATION_STATE_KEY, "COMPLETED");
        details.put(ThirdPartyBackupManifest.CLEANUP_STATE_KEY, "NONE");
        details.put(ThirdPartyBackupManifest.CATALOG_KEY, "{\"state\":\"AVAILABLE\"}");
        assertTrue(ThirdPartyBackupVolumeService.needsStagingManagement(backup, null));
        details.put(ThirdPartyBackupManifest.CLEANUP_STATE_KEY, "COMPLETED");
        assertFalse(ThirdPartyBackupVolumeService.needsStagingManagement(backup, null));
    }
}
