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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.Command;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.storage.Volume;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.google.gson.Gson;
import org.apache.cloudstack.api.response.BackupStagingInfoResponse;
import org.apache.cloudstack.backup.dao.BackupDao;
import org.apache.cloudstack.backup.dao.BackupDetailsDao;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class ThirdPartyBackupCompletedJobCleanupTest {
    private final Map<String, String> details = new HashMap<>();
    private final Gson gson = new Gson();
    private ThirdPartyBackupVolumeServiceImpl service;
    private BackupVO backup;
    private BackupDao backups;
    private BackupDetailsDao detailDao;
    private AgentManager agent;
    private HostDao hosts;
    private HostVO host;
    private ThirdPartyBackupManifest manifest;
    private ThirdPartyBackupAdmission.Entry admission;

    @Before
    public void setup() throws Exception {
        service = new ThirdPartyBackupVolumeServiceImpl();
        backup = mock(BackupVO.class);
        backups = mock(BackupDao.class);
        detailDao = mock(BackupDetailsDao.class);
        agent = mock(AgentManager.class);
        hosts = mock(HostDao.class);
        host = mock(HostVO.class);
        when(host.getId()).thenReturn(4L);
        when(host.getName()).thenReturn("host-4");
        when(host.getStatus()).thenReturn(com.cloud.host.Status.Up);
        when(hosts.findById(4L)).thenReturn(host);
        when(backup.getId()).thenReturn(1L);
        when(backup.getUuid()).thenReturn("backup-1");
        when(backup.getHostId()).thenReturn(4L);
        when(backup.getStatus()).thenReturn(Backup.Status.BackedUp);
        when(backup.getDetails()).thenReturn(details);
        when(backup.getDetail(anyString())).thenAnswer(call -> details.get(call.getArgument(0)));
        when(backups.findById(1L)).thenReturn(backup);
        when(backups.update(1L, backup)).thenReturn(true);
        doAnswer(call -> {
            when(backup.getStatus()).thenReturn(call.getArgument(0));
            return null;
        }).when(backup).setStatus(any());
        when(detailDao.findDetail(eq(1L), anyString())).thenAnswer(call -> {
            String key = call.getArgument(1);
            return details.containsKey(key) ? new BackupDetailVO(1L, key, details.get(key), false) : null;
        });
        doAnswer(call -> {
            details.put(call.getArgument(1), call.getArgument(2));
            return null;
        }).when(detailDao).addDetail(eq(1L), anyString(), anyString(), eq(false));
        doAnswer(call -> {
            details.remove(call.getArgument(1));
            return null;
        }).when(detailDao).removeDetail(eq(1L), anyString());
        when(agent.send(eq(4L), any(Command.class))).thenAnswer(call -> new Answer(call.getArgument(1), true, "cleaned"));
        ReflectionTestUtils.setField(service, "backupDao", backups);
        ReflectionTestUtils.setField(service, "backupDetailsDao", detailDao);
        ReflectionTestUtils.setField(service, "agentManager", agent);
        ReflectionTestUtils.setField(service, "hostDao", hosts);
        prepare("ablestack-commvault");
    }

    private void prepare(String provider) {
        manifest = ThirdPartyBackupManifest.create(provider, "backup-1", "vm-1", "2026.10.16.12.00.00", "FULL", "host-4",
                "/staging/" + provider + "/vm-1/2026.10.16.12.00.00", "QCOW2",
                Collections.singletonList(new Backup.VolumeInfo("volume-1", "root.qcow2", Volume.Type.ROOT, 1073741824L,
                        0L, null, null, null)), null, null);
        ThirdPartyBackupManifest.Artifact metadata = new ThirdPartyBackupManifest.Artifact();
        metadata.backupUuid = "backup-1";
        metadata.sourceHost = "host-4";
        metadata.path = "/staging/" + provider + "/vm-1/2026.10.16.12.00.00";
        manifest.setMetadata(metadata);
        manifest.getOwnedArtifacts().forEach(artifact -> {
            artifact.completed = true;
            artifact.jobId = artifact.externalId = "1186";
        });
        manifest.setComplete(true);
        details.put(ThirdPartyBackupManifest.MODE_KEY, ThirdPartyBackupManifest.VOLUME_MODE);
        details.put(ThirdPartyBackupManifest.DETAIL_KEY, manifest.toJson());
        details.put(ThirdPartyBackupManifest.CLEANUP_STATE_KEY, "COMPLETED");
        details.put(ThirdPartyBackupManifest.FINALIZATION_STATE_KEY, "COMPLETED");
        details.put(ThirdPartyBackupManifest.JOB_CLEANUP_STATE_KEY, "WAITING");
        admission = new ThirdPartyBackupAdmission.Entry();
        admission.jobId = "backup-1";
        admission.hostId = 4L;
        admission.operation = "BACKUP";
        admission.state = "RELEASED";
        details.put(ThirdPartyBackupAdmission.BACKUP_KEY, gson.toJson(admission));
    }

    private void clean() {
        try (MockedStatic<Transaction> transactions = Mockito.mockStatic(Transaction.class)) {
            transactions.when(() -> Transaction.execute(Mockito.<TransactionCallback<Boolean>>any()))
                    .thenAnswer(call -> call.<TransactionCallback<Boolean>>getArgument(0).doInTransaction(null));
            ReflectionTestUtils.invokeMethod(service, "cleanupSuccessfulBackupRecords", backup, host);
        }
    }

    @Test
    public void completedRecordsAreRemovedForAllThreeProvidersWithoutChangingRecoveryMetadata() throws Exception {
        for (String provider : new String[]{"ablestack-commvault", "ablestack-netbackup", "ablestack-veeam"}) {
            setup();
            prepare(provider);
            details.put("saved.checkpoint.xml", "<domaincheckpoint/>");
            String before = details.get(ThirdPartyBackupManifest.DETAIL_KEY);
            clean();
            assertEquals("COMPLETED", details.get(ThirdPartyBackupManifest.JOB_CLEANUP_STATE_KEY));
            assertEquals("COMPLETED", details.get(ThirdPartyBackupManifest.CLEANUP_STATE_KEY));
            assertEquals(before, details.get(ThirdPartyBackupManifest.DETAIL_KEY));
            assertEquals("<domaincheckpoint/>", details.get("saved.checkpoint.xml"));
            verify(agent).send(eq(4L), any(AblestackBackupJobCleanupCommand.class));
            verify(agent).send(eq(4L), Mockito.<Command>argThat(command -> command instanceof AblestackBackupJobCleanupCommand
                    && !((AblestackBackupJobCleanupCommand) command).isBackupDeleted()));
            verify(agent, never()).send(eq(4L), any(AblestackVolumeStagingCommand.class));
        }
    }

    @Test
    public void physicalFinalizationAndReservationReleaseMustPrecedeDeletion() {
        for (String key : new String[]{ThirdPartyBackupManifest.CLEANUP_STATE_KEY, ThirdPartyBackupManifest.FINALIZATION_STATE_KEY}) {
            details.put(key, "WAITING");
            clean();
            verifyNoInteractions(agent);
            details.put(key, "COMPLETED");
        }
        admission.state = "ADMITTED";
        details.put(ThirdPartyBackupAdmission.BACKUP_KEY, gson.toJson(admission));
        clean();
        verifyNoInteractions(agent);
        admission.state = "RELEASED";
        admission.jobId = "another-backup";
        details.put(ThirdPartyBackupAdmission.BACKUP_KEY, gson.toJson(admission));
        clean();
        verifyNoInteractions(agent);
    }

    @Test
    public void parentCleanupRetainsJobInputsUntilItsCompletion() throws Exception {
        manifest.getCurrentArtifacts().get(0).parentCheckpointName = "parent";
        details.put(ThirdPartyBackupManifest.DETAIL_KEY, manifest.toJson());
        details.put(ThirdPartyBackupManifest.SOURCE_CLEANUP_STATE_KEY, "WAITING");
        clean();
        verifyNoInteractions(agent);
        details.put(ThirdPartyBackupManifest.SOURCE_CLEANUP_STATE_KEY, "COMPLETED");
        clean();
        verify(agent).send(eq(4L), any(AblestackBackupJobCleanupCommand.class));
    }

    @Test
    public void lostCleanupResponseRetriesOnlyRecordDeletion() throws Exception {
        when(agent.send(eq(4L), any(AblestackBackupJobCleanupCommand.class)))
                .thenReturn(null).thenAnswer(call -> new Answer(call.getArgument(1), true, "already cleaned"));
        clean();
        assertEquals("WAITING", details.get(ThirdPartyBackupManifest.JOB_CLEANUP_STATE_KEY));
        assertEquals("COMPLETED", details.get(ThirdPartyBackupManifest.CLEANUP_STATE_KEY));
        assertEquals("COMPLETED", details.get(ThirdPartyBackupManifest.FINALIZATION_STATE_KEY));
        assertEquals(Backup.Status.BackedUp, backup.getStatus());
        clean();
        assertEquals("COMPLETED", details.get(ThirdPartyBackupManifest.JOB_CLEANUP_STATE_KEY));
        assertNull(details.get(ThirdPartyBackupManifest.JOB_CLEANUP_DETAILS_KEY));
        verify(agent, Mockito.times(2)).send(eq(4L), any(AblestackBackupJobCleanupCommand.class));
        verify(agent, never()).send(eq(4L), any(AblestackVolumeStagingCommand.class));
    }

    @Test
    public void sourceFailureAndOfflineHostAreExcludedFromSuccessfulCleanup() {
        for (Backup.Status status : new Backup.Status[]{Backup.Status.BackingUp, Backup.Status.Failed, Backup.Status.Error, Backup.Status.Canceled}) {
            when(backup.getStatus()).thenReturn(status);
            clean();
            verifyNoInteractions(agent);
        }
        when(backup.getStatus()).thenReturn(Backup.Status.BackedUp);
        when(host.getStatus()).thenReturn(com.cloud.host.Status.Down);
        clean();
        assertEquals("WAITING", details.get(ThirdPartyBackupManifest.JOB_CLEANUP_STATE_KEY));
        verifyNoInteractions(agent);
    }

    @Test
    public void inspectionUsesDurableCompletionEvenAfterFilesAndHostAreGone() {
        when(hosts.findById(4L)).thenReturn(null);
        BackupStagingInfoResponse info = ReflectionTestUtils.invokeMethod(service, "inspectLocked", backup, "BACKUP");
        assertEquals("COMPLETED", info.hoststate);
        assertEquals("COMPLETED", info.step);
        assertEquals(Integer.valueOf(100), info.progress);
        assertEquals("RELEASED", info.reservationstate);
        assertEquals(Long.valueOf(0), info.reservedbytes);
        assertNull(info.hosterror);
        assertNull(info.reservationerror);
        assertEquals(2, info.artifact.size());
        verifyNoInteractions(agent);
    }

    @Test
    public void restartCanResumeRecordCleanupInterruptedInRunningState() throws Exception {
        details.put(ThirdPartyBackupManifest.JOB_CLEANUP_STATE_KEY, "RUNNING");
        GlobalLock lock = mock(GlobalLock.class);
        when(lock.lock(Mockito.anyInt())).thenReturn(true);
        try (MockedStatic<GlobalLock> locks = Mockito.mockStatic(GlobalLock.class);
                MockedStatic<Transaction> transactions = Mockito.mockStatic(Transaction.class)) {
            locks.when(() -> GlobalLock.getInternLock(anyString())).thenReturn(lock);
            transactions.when(() -> Transaction.execute(Mockito.<TransactionCallback<Boolean>>any()))
                    .thenAnswer(call -> call.<TransactionCallback<Boolean>>getArgument(0).doInTransaction(null));
            ReflectionTestUtils.invokeMethod(service, "reconcileSuccessfulBackupRecords", 1L);
        }
        assertEquals("COMPLETED", details.get(ThirdPartyBackupManifest.JOB_CLEANUP_STATE_KEY));
        verify(agent).send(eq(4L), any(AblestackBackupJobCleanupCommand.class));
    }

    @Test
    public void catalogRefreshPreservesTheCompletedPhysicalCleanupState() {
        GlobalLock lock = mock(GlobalLock.class);
        when(lock.lock(Mockito.anyInt())).thenReturn(true);
        try (MockedStatic<GlobalLock> locks = Mockito.mockStatic(GlobalLock.class)) {
            locks.when(() -> GlobalLock.getInternLock(anyString())).thenReturn(lock);
            service.reconcileCatalog(backup, artifact -> true, (artifact, metadata, ticket, expired) -> {
                throw new AssertionError("An available recovery point must not be deleted");
            });
        }
        assertEquals("COMPLETED", details.get(ThirdPartyBackupManifest.CLEANUP_STATE_KEY));
        assertTrue(ThirdPartyBackupVolumeService.hasFinalizedBackupStaging(backup));
        verifyNoInteractions(agent);
    }

    @Test
    public void partialCatalogExpirationRetainsSourceSnapshotsAndRemainingImages() {
        when(backups.createForUpdate(1L)).thenReturn(backup);
        GlobalLock lock = mock(GlobalLock.class);
        when(lock.lock(Mockito.anyInt())).thenReturn(true);
        try (MockedStatic<GlobalLock> locks = Mockito.mockStatic(GlobalLock.class)) {
            locks.when(() -> GlobalLock.getInternLock(anyString())).thenReturn(lock);
            service.reconcileCatalog(backup, artifact -> !artifact.path.equals(manifest.getMetadata().path),
                    (artifact, metadata, ticket, expired) -> { throw new AssertionError("Retained images must not be expired by catalog synchronization"); });
        }
        assertEquals("PARTIAL", gson.fromJson(details.get(ThirdPartyBackupManifest.CATALOG_KEY), ThirdPartyBackupManifest.Catalog.class).state);
        assertEquals("RETAINED", details.get(ThirdPartyBackupManifest.CLEANUP_STATE_KEY));
        verifyNoInteractions(agent);
    }

    @Test
    public void firstConfirmedAbsenceWaitsForReconfirmationBeforeDeletingSnapshots() {
        when(backups.createForUpdate(1L)).thenReturn(backup);
        GlobalLock lock = mock(GlobalLock.class);
        when(lock.lock(Mockito.anyInt())).thenReturn(true);
        try (MockedStatic<GlobalLock> locks = Mockito.mockStatic(GlobalLock.class)) {
            locks.when(() -> GlobalLock.getInternLock(anyString())).thenReturn(lock);
            service.reconcileCatalog(backup, artifact -> false,
                    (artifact, metadata, ticket, expired) -> { throw new AssertionError("Absence must be reconfirmed first"); });
        }
        assertEquals("EXPIRED", gson.fromJson(details.get(ThirdPartyBackupManifest.CATALOG_KEY), ThirdPartyBackupManifest.Catalog.class).state);
        assertEquals("WAITING", details.get(ThirdPartyBackupManifest.CLEANUP_STATE_KEY));
        verifyNoInteractions(agent);
    }

    @Test
    public void rbdSnapshotCleanupUsesRecordedDisksAfterStagingAndJobFilesAreRemoved() throws Exception {
        prepare("ablestack-netbackup");
        details.put("netbackup.rbd.disk.paths", "rbd:pool/root,rbd:pool/data");
        details.put(ThirdPartyBackupManifest.JOB_CLEANUP_STATE_KEY, "COMPLETED");
        ThirdPartyBackupStagingService staging = mock(ThirdPartyBackupStagingService.class);
        when(staging.getStageRootPath(manifest.getProvider())).thenReturn("/staging/" + manifest.getProvider());
        ReflectionTestUtils.setField(service, "stagingService", staging);
        ReflectionTestUtils.invokeMethod(service, "cleanupVolumeArtifacts", backup, manifest);
        verify(agent).send(eq(4L), Mockito.<Command>argThat(command -> command instanceof AblestackDeleteBackupCommand
                && "rbd:pool/root,rbd:pool/data".equals(((AblestackDeleteBackupCommand) command).getDiskPaths())
                && manifest.getTimestamp().equals(((AblestackDeleteBackupCommand) command).getCheckpointName())));
    }

    @Test
    public void recordedFinalizationCanRecoverTheCatalogOnlyNoneState() {
        details.put(ThirdPartyBackupManifest.CLEANUP_STATE_KEY, "NONE");
        clean();
        assertEquals("COMPLETED", details.get(ThirdPartyBackupManifest.CLEANUP_STATE_KEY));
        assertEquals("COMPLETED", details.get(ThirdPartyBackupManifest.JOB_CLEANUP_STATE_KEY));
    }

    @Test
    public void deletionAfterRecordRemovalUsesStoredCheckpointInformation() throws Exception {
        details.put(ThirdPartyBackupManifest.JOB_CLEANUP_STATE_KEY, "COMPLETED");
        ThirdPartyBackupStagingService staging = mock(ThirdPartyBackupStagingService.class);
        when(staging.getStageRootPath(manifest.getProvider())).thenReturn("/staging/" + manifest.getProvider());
        ReflectionTestUtils.setField(service, "stagingService", staging);
        ReflectionTestUtils.invokeMethod(service, "cleanupVolumeArtifacts", backup, manifest);
        verify(agent).send(eq(4L), Mockito.<Command>argThat(command -> command instanceof AblestackVolumeStagingCommand
                && "CLEANUP_CHECK".equals(((AblestackVolumeStagingCommand) command).getAction())));
        verify(agent).send(eq(4L), Mockito.<Command>argThat(command -> command instanceof AblestackDeleteBackupCommand
                && manifest.getTimestamp().equals(((AblestackDeleteBackupCommand) command).getCheckpointName())));
        verify(agent, never()).send(eq(4L), any(AblestackBackupJobStatusCommand.class));
        verify(agent, never()).send(eq(4L), Mockito.<Command>argThat(command -> command instanceof AblestackVolumeStagingCommand
                && "CLEANUP_FAILED".equals(((AblestackVolumeStagingCommand) command).getAction())));
        org.mockito.InOrder order = Mockito.inOrder(agent);
        order.verify(agent).send(eq(4L), any(AblestackDeleteBackupCommand.class));
        order.verify(agent).send(eq(4L), Mockito.<Command>argThat(command -> command instanceof AblestackBackupJobCleanupCommand
                && ((AblestackBackupJobCleanupCommand) command).isBackupDeleted()));
    }

    @Test
    public void deletionFailureRetainsTheHostReasonAndRetryDoesNotDeleteExternalArtifactsAgain() throws Exception {
        ThirdPartyBackupStagingService staging = mock(ThirdPartyBackupStagingService.class);
        when(staging.getStageRootPath(manifest.getProvider())).thenReturn("/staging/" + manifest.getProvider());
        ReflectionTestUtils.setField(service, "stagingService", staging);
        when(agent.send(eq(4L), any(AblestackBackupJobCleanupCommand.class)))
                .thenAnswer(call -> new Answer(call.getArgument(1), false, "Host job history helper is unavailable"))
                .thenAnswer(call -> new Answer(call.getArgument(1), true, "already cleaned"));
        ThirdPartyBackupVolumeService.ArtifactCleanup cleanup = mock(ThirdPartyBackupVolumeService.ArtifactCleanup.class);
        when(cleanup.delete(any(), Mockito.anyBoolean(), any(), Mockito.anyBoolean()))
                .thenReturn(new ThirdPartyBackupVolumeService.DeleteResult(true, null));
        CloudRuntimeException failure = assertThrows(CloudRuntimeException.class,
                () -> ReflectionTestUtils.invokeMethod(service, "deleteLocked", 1L, cleanup));
        assertEquals("Host backup job record cleanup remains pending: Host job history helper is unavailable", failure.getMessage());
        assertEquals("WAITING", details.get(ThirdPartyBackupManifest.CLEANUP_STATE_KEY));
        assertEquals(failure.getMessage(), details.get(ThirdPartyBackupManifest.CLEANUP_DETAILS_KEY));
        assertEquals(Backup.Status.BackedUp, backup.getStatus());
        assertTrue(ReflectionTestUtils.<Boolean>invokeMethod(service, "deleteLocked", 1L, cleanup));
        assertEquals("COMPLETED", details.get(ThirdPartyBackupManifest.CLEANUP_STATE_KEY));
        verify(cleanup, Mockito.times(manifest.getOwnedArtifacts().size())).delete(any(), Mockito.anyBoolean(), any(), Mockito.anyBoolean());
    }

    @Test
    public void failedLocalDeletionCannotAuthorizeRecordRemoval() throws Exception {
        ThirdPartyBackupStagingService staging = mock(ThirdPartyBackupStagingService.class);
        when(staging.getStageRootPath(manifest.getProvider())).thenReturn("/staging/" + manifest.getProvider());
        ReflectionTestUtils.setField(service, "stagingService", staging);
        when(agent.send(eq(4L), any(AblestackDeleteBackupCommand.class)))
                .thenAnswer(call -> new Answer(call.getArgument(1), false, "checkpoint deletion failed"));
        CloudRuntimeException failure = assertThrows(CloudRuntimeException.class,
                () -> ReflectionTestUtils.invokeMethod(service, "cleanupVolumeArtifacts", backup, manifest));
        assertTrue(failure.getMessage().contains("checkpoint deletion failed"));
        verify(agent, never()).send(eq(4L), any(AblestackBackupJobCleanupCommand.class));
    }

    @Test
    public void logicalCompletionMustBeCommittedBeforeExecutionRecordsAreRemoved() throws Exception {
        when(backup.getStatus()).thenReturn(Backup.Status.BackingUp);
        ThirdPartyBackupStagingService staging = mock(ThirdPartyBackupStagingService.class);
        when(staging.getStageRootPath(manifest.getProvider())).thenReturn("/staging/" + manifest.getProvider());
        ReflectionTestUtils.setField(service, "stagingService", staging);
        when(agent.send(eq(4L), any(AblestackVolumeStagingCommand.class))).thenAnswer(call -> new Answer(call.getArgument(1), true,
                "{\"backupUuid\":\"backup-1\",\"stagingCleanupState\":\"COMPLETED\"}"));
        GlobalLock lock = mock(GlobalLock.class);
        when(lock.lock(Mockito.anyInt())).thenReturn(true);
        try (MockedStatic<GlobalLock> locks = Mockito.mockStatic(GlobalLock.class);
                MockedStatic<Transaction> transactions = Mockito.mockStatic(Transaction.class)) {
            locks.when(() -> GlobalLock.getInternLock(anyString())).thenReturn(lock);
            transactions.when(() -> Transaction.execute(Mockito.<TransactionCallback<Boolean>>any()))
                    .thenAnswer(call -> call.<TransactionCallback<Boolean>>getArgument(0).doInTransaction(null));
            ReflectionTestUtils.invokeMethod(service, "finalizeBackup", backup, host, manifest,
                    mock(ThirdPartyBackupVolumeService.Transfer.class));
        }
        org.mockito.InOrder order = Mockito.inOrder(backups, detailDao, agent);
        order.verify(backups).update(1L, backup);
        order.verify(detailDao).addDetail(1L, ThirdPartyBackupManifest.FINALIZATION_STATE_KEY, "COMPLETED", false);
        order.verify(agent).send(eq(4L), any(AblestackBackupJobCleanupCommand.class));
        assertEquals("COMPLETED", details.get(ThirdPartyBackupManifest.JOB_CLEANUP_STATE_KEY));
    }

    @Test
    public void aCleanedFailureRemainsInspectableFromItsSavedState() {
        when(backup.getStatus()).thenReturn(Backup.Status.Failed);
        details.remove(ThirdPartyBackupManifest.FINALIZATION_STATE_KEY);
        details.put(ThirdPartyBackupManifest.JOB_CLEANUP_STATE_KEY, "COMPLETED");
        BackupStagingInfoResponse info = ReflectionTestUtils.invokeMethod(service, "inspectLocked", backup, "BACKUP");
        assertEquals("FAILED", info.hoststate);
        assertEquals("RELEASED", info.reservationstate);
        assertFalse("COMPLETED".equals(info.hoststate));
        verifyNoInteractions(agent);
    }

    @Test
    public void catalogRetentionDoesNotRequeryRemovedHostExecutionFiles() {
        details.put(ThirdPartyBackupManifest.JOB_CLEANUP_STATE_KEY, "COMPLETED");
        details.put(ThirdPartyBackupManifest.CLEANUP_STATE_KEY, "RETAINED");
        details.put(ThirdPartyBackupManifest.CATALOG_FAILURE_KEY, "Required external artifact is unavailable");
        when(backup.getStatus()).thenReturn(Backup.Status.Failed);
        BackupStagingInfoResponse info = ReflectionTestUtils.invokeMethod(service, "inspectLocked", backup, "BACKUP");
        assertEquals("COMPLETED", info.hoststate);
        assertEquals("RETAINED", info.cleanupstate);
        verifyNoInteractions(agent);
    }
}
