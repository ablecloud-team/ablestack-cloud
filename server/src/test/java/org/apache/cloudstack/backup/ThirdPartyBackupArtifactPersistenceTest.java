// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.
package org.apache.cloudstack.backup;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.Command;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.storage.Volume;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.Gson;
import org.apache.cloudstack.backup.dao.BackupDao;
import org.apache.cloudstack.backup.dao.BackupDetailsDao;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ThirdPartyBackupArtifactPersistenceTest {
    private static final long BACKUP_ID = 1L;
    private static final long HOST_ID = 14L;
    private static final String JOB_ID = "backup-id";
    private BackupDao backupDao;
    private BackupDetailsDao detailsDao;
    private AgentManager agentManager;
    private HostDao hostDao;
    private ThirdPartyBackupVolumeServiceImpl service;
    private BackupVO backup;
    private HostVO host;
    private ThirdPartyBackupManifest manifest;
    private Map<String, String> persisted;
    private Map<String, String> loaded;

    @Before
    public void setup() {
        backupDao = mock(BackupDao.class);
        detailsDao = mock(BackupDetailsDao.class);
        agentManager = mock(AgentManager.class);
        hostDao = mock(HostDao.class);
        service = new ThirdPartyBackupVolumeServiceImpl();
        ReflectionTestUtils.setField(service, "backupDao", backupDao);
        ReflectionTestUtils.setField(service, "backupDetailsDao", detailsDao);
        ReflectionTestUtils.setField(service, "agentManager", agentManager);
        ReflectionTestUtils.setField(service, "hostDao", hostDao);
    }

    private void prepare(String provider) throws Exception {
        String root = "/staging/" + provider + "/vm/2026.10.12.13.04.55";
        manifest = ThirdPartyBackupManifest.create(provider, JOB_ID, "vm", "2026.10.12.13.04.55", "FULL", "host", root, "QCOW2",
                Collections.singletonList(new Backup.VolumeInfo("volume-id", "root.qcow2", Volume.Type.ROOT,
                        1073741824L, 0L, null, null, null)), null, null);
        ThirdPartyBackupAdmission.Entry admission = new ThirdPartyBackupAdmission.Entry();
        admission.jobId = JOB_ID;
        admission.operation = "BACKUP";
        admission.hostId = HOST_ID;
        admission.state = "ADMITTED";
        persisted = new HashMap<>();
        persisted.put(ThirdPartyBackupManifest.MODE_KEY, ThirdPartyBackupManifest.VOLUME_MODE);
        persisted.put(ThirdPartyBackupManifest.DETAIL_KEY, manifest.toJson());
        persisted.put(ThirdPartyBackupAdmission.BACKUP_KEY, new Gson().toJson(admission));
        persisted.put(ThirdPartyBackupRestore.CLEANUP_STATE_KEY, "COMPLETED");
        loaded = new HashMap<>(persisted);
        backup = mock(BackupVO.class);
        when(backup.getId()).thenReturn(BACKUP_ID);
        when(backup.getUuid()).thenReturn(JOB_ID);
        when(backup.getHostId()).thenReturn(HOST_ID);
        when(backup.getStatus()).thenReturn(Backup.Status.BackingUp);
        when(backup.getExternalId()).thenReturn(root);
        when(backup.getDetails()).thenReturn(loaded);
        when(backup.getDetail(anyString())).thenAnswer(invocation -> loaded.get(invocation.getArgument(0)));
        when(backupDao.findById(BACKUP_ID)).thenReturn(backup);
        host = mock(HostVO.class);
        when(host.getId()).thenReturn(HOST_ID);
        when(hostDao.findById(HOST_ID)).thenReturn(host);
        when(detailsDao.findDetail(eq(BACKUP_ID), anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(1);
            String value = persisted.get(key);
            return value == null ? null : new BackupDetailVO(BACKUP_ID, key, value, false);
        });
        doAnswer(invocation -> {
            persisted.put(invocation.getArgument(1), invocation.getArgument(2));
            return null;
        }).when(detailsDao).addDetail(eq(BACKUP_ID), anyString(), anyString(), eq(false));
        BackupAnswer status = mock(BackupAnswer.class);
        when(status.getResult()).thenReturn(true);
        when(status.getState()).thenReturn("RUNNING");
        ThirdPartyBackupManifest.Artifact artifact = manifest.getCurrentArtifacts().get(0);
        artifact.size = 65536;
        String request = new Gson().toJson(Map.of("index", 0, "metadata", false, "backupUuid", JOB_ID, "artifact", artifact));
        when(agentManager.send(eq(HOST_ID), any(Command.class))).thenAnswer(invocation -> {
            Command command = invocation.getArgument(1);
            if (command instanceof AblestackBackupJobStatusCommand) { return status; }
            String action = ((AblestackVolumeStagingCommand) command).getAction();
            return new Answer(command, true, "STATUS".equals(action) ? request : "ok");
        });
    }

    @Test
    public void unconfirmedHostStatusIsRetriedWithoutFailingOrCancellingBackup() throws Exception {
        for (String provider : Arrays.asList("ablestack-commvault", "ablestack-netbackup", "ablestack-veeam")) {
            setup();
            prepare(provider);
            String reservation = persisted.get(ThirdPartyBackupAdmission.BACKUP_KEY);
            String plannedManifest = persisted.get(ThirdPartyBackupManifest.DETAIL_KEY);
            AblestackBackupJobStatusCommand command = new AblestackBackupJobStatusCommand(JOB_ID);
            BackupAnswer running = new BackupAnswer(command, true, "Backup is running");
            running.setState("RUNNING");
            when(agentManager.send(eq(HOST_ID), any(AblestackBackupJobStatusCommand.class))).thenReturn(
                    new Answer(command, false, "Host status temporarily unavailable"),
                    new Answer(command, true, "Unexpected generic response"),
                    null,
                    new BackupAnswer(command, false, "Status query failed"),
                    running);
            ThirdPartyBackupVolumeService.Transfer transfer = mock(ThirdPartyBackupVolumeService.Transfer.class);
            when(transfer.backup(any(), eq(false))).thenAnswer(invocation -> {
                ThirdPartyBackupManifest.Artifact accepted = invocation.getArgument(0);
                accepted.jobId = "123";
                accepted.externalId = "point-123";
                accepted.completed = true;
                return accepted;
            });
            GlobalLock lock = mock(GlobalLock.class);
            when(lock.lock(Mockito.anyInt())).thenReturn(true);
            try (MockedStatic<GlobalLock> locks = Mockito.mockStatic(GlobalLock.class)) {
                locks.when(() -> GlobalLock.getInternLock(anyString())).thenReturn(lock);
                for (int attempt = 0; attempt < 4; attempt++) {
                    assertTrue(service.reconcile(backup, host, transfer));
                    assertEquals(plannedManifest, persisted.get(ThirdPartyBackupManifest.DETAIL_KEY));
                    assertEquals(reservation, persisted.get(ThirdPartyBackupAdmission.BACKUP_KEY));
                    verify(backup, never()).setStatus(any());
                    verify(backupDao, never()).update(anyLong(), any(BackupVO.class));
                    verify(transfer, never()).failed(any());
                    verify(transfer, never()).backup(any(), Mockito.anyBoolean());
                    verify(agentManager, never()).send(eq(HOST_ID), any(AblestackVolumeStagingCommand.class));
                }
                assertTrue(service.reconcile(backup, host, transfer));
            }
            verify(transfer).backup(any(), eq(false));
            verify(backup, never()).setStatus(any());
            verify(transfer, never()).failed(any());
            verify(agentManager, never()).send(eq(HOST_ID), Mockito.<Command>argThat(sent -> sent instanceof AblestackVolumeStagingCommand
                    && "CANCEL".equals(((AblestackVolumeStagingCommand) sent).getAction())));
            assertTrue(ThirdPartyBackupManifest.fromJson(persisted.get(ThirdPartyBackupManifest.DETAIL_KEY))
                    .getCurrentArtifacts().get(0).completed);
        }
    }

    @Test
    public void unchangedBackupRowDoesNotBlockIntentJobIdOrAcknowledgment() throws Exception {
        for (String provider : Arrays.asList("ablestack-commvault", "ablestack-netbackup", "ablestack-veeam")) {
            setup();
            prepare(provider);
            String reservation = persisted.get(ThirdPartyBackupAdmission.BACKUP_KEY);
            ThirdPartyBackupVolumeService.Transfer transfer = mock(ThirdPartyBackupVolumeService.Transfer.class);
            when(transfer.backup(any(), eq(false))).thenAnswer(invocation -> {
                assertTrue(ThirdPartyBackupManifest.fromJson(persisted.get(ThirdPartyBackupManifest.DETAIL_KEY))
                        .getCurrentArtifacts().get(0).submissionPending);
                ThirdPartyBackupManifest.Artifact accepted = invocation.getArgument(0);
                accepted.jobId = "123";
                accepted.externalId = "point-123";
                accepted.completed = true;
                return accepted;
            });
            GlobalLock lock = mock(GlobalLock.class);
            when(lock.lock(Mockito.anyInt())).thenReturn(true);
            try (MockedStatic<GlobalLock> locks = Mockito.mockStatic(GlobalLock.class)) {
                locks.when(() -> GlobalLock.getInternLock(anyString())).thenReturn(lock);
                assertTrue(service.reconcile(backup, host, transfer));
            }
            ThirdPartyBackupManifest.Artifact saved = ThirdPartyBackupManifest.fromJson(persisted.get(ThirdPartyBackupManifest.DETAIL_KEY))
                    .getCurrentArtifacts().get(0);
            assertEquals("123", saved.jobId);
            assertTrue(saved.completed);
            assertFalse(saved.submissionPending);
            assertEquals(reservation, persisted.get(ThirdPartyBackupAdmission.BACKUP_KEY));
            assertEquals("COMPLETED", persisted.get(ThirdPartyBackupRestore.CLEANUP_STATE_KEY));
            verify(backupDao, never()).update(anyLong(), any(BackupVO.class));
            verify(backup, never()).setStatus(any());
            verify(transfer, never()).failed(any());
            InOrder order = inOrder(detailsDao, transfer, agentManager);
            order.verify(detailsDao).addDetail(eq(BACKUP_ID), eq(ThirdPartyBackupManifest.DETAIL_KEY), anyString(), eq(false));
            order.verify(transfer).backup(any(), eq(false));
            order.verify(detailsDao).addDetail(eq(BACKUP_ID), eq(ThirdPartyBackupManifest.DETAIL_KEY), anyString(), eq(false));
            order.verify(agentManager).send(eq(HOST_ID), Mockito.<Command>argThat(command -> command instanceof AblestackVolumeStagingCommand
                    && "ACK".equals(((AblestackVolumeStagingCommand) command).getAction())));
        }
    }

    @Test
    public void failedIntentWriteDoesNotPublishAPhantomSubmission() throws Exception {
        prepare("ablestack-commvault");
        String original = loaded.get(ThirdPartyBackupManifest.DETAIL_KEY);
        manifest.getCurrentArtifacts().get(0).submissionPending = true;
        doThrow(new CloudRuntimeException("DB unavailable")).when(detailsDao)
                .addDetail(eq(BACKUP_ID), eq(ThirdPartyBackupManifest.DETAIL_KEY), anyString(), eq(false));
        try {
            service.saveVolumeManifest(backup, manifest);
            fail("Failed DB write must propagate");
        } catch (CloudRuntimeException expected) {
            assertEquals("DB unavailable", expected.getMessage());
        }
        assertEquals(original, loaded.get(ThirdPartyBackupManifest.DETAIL_KEY));
        assertEquals(original, persisted.get(ThirdPartyBackupManifest.DETAIL_KEY));
    }

    @Test
    public void metadataReferencesAreWrittenInOneTransactionWithoutReplacingLoadedDetails() throws Exception {
        for (String provider : Arrays.asList("ablestack-commvault", "ablestack-netbackup", "ablestack-veeam")) {
            setup();
            prepare(provider);
            ThirdPartyBackupManifest.Artifact metadata = new ThirdPartyBackupManifest.Artifact();
            metadata.path = backup.getExternalId();
            metadata.backupUuid = JOB_ID;
            metadata.jobId = "456";
            metadata.externalId = "point-456";
            metadata.backupTime = "1791778000";
            metadata.sourceHost = "host";
            metadata.completed = true;
            manifest.setMetadata(metadata);
            manifest.getCurrentArtifacts().forEach(artifact -> {
                artifact.jobId = "123";
                artifact.externalId = "point-123";
                artifact.completed = true;
            });
            BackupVO update = new BackupVO();
            when(backupDao.createForUpdate(BACKUP_ID)).thenReturn(update);
            when(backupDao.update(BACKUP_ID, update)).thenReturn(true);
            try (MockedStatic<Transaction> transactions = Mockito.mockStatic(Transaction.class)) {
                transactions.when(() -> Transaction.execute(Mockito.<TransactionCallback<Boolean>>any()))
                        .thenAnswer(invocation -> invocation.<TransactionCallback<Boolean>>getArgument(0).doInTransaction(null));
                service.saveArtifactCatalogReference(backup, manifest, metadata, true);
                transactions.verify(() -> Transaction.execute(Mockito.<TransactionCallback<Boolean>>any()));
            }
            assertTrue(ThirdPartyBackupManifest.fromJson(persisted.get(ThirdPartyBackupManifest.DETAIL_KEY)).isComplete());
            if ("ablestack-commvault".equals(provider)) {
                assertEquals(metadata.path + ",456", update.getExternalId());
                assertNull(update.getDetails());
                verify(backupDao).update(BACKUP_ID, update);
            } else {
                verify(backupDao, never()).update(anyLong(), any(BackupVO.class));
                String key = "ablestack-netbackup".equals(provider) ? "netbackup.backup.id" : "ablestack.veeam.restore.point.id";
                assertEquals("point-456", persisted.get(key));
                if ("ablestack-netbackup".equals(provider)) { assertEquals(metadata.backupTime, persisted.get("netbackup.backup.time")); }
            }
            assertEquals("COMPLETED", persisted.get(ThirdPartyBackupRestore.CLEANUP_STATE_KEY));
        }
    }

    @Test
    public void failedMetadataTransactionDoesNotPublishCompletionOrExternalId() throws Exception {
        prepare("ablestack-commvault");
        String original = loaded.get(ThirdPartyBackupManifest.DETAIL_KEY);
        ThirdPartyBackupManifest.Artifact metadata = new ThirdPartyBackupManifest.Artifact();
        metadata.path = backup.getExternalId();
        metadata.jobId = "456";
        metadata.completed = true;
        manifest.setMetadata(metadata);
        BackupVO update = new BackupVO();
        when(backupDao.createForUpdate(BACKUP_ID)).thenReturn(update);
        try (MockedStatic<Transaction> transactions = Mockito.mockStatic(Transaction.class)) {
            transactions.when(() -> Transaction.execute(Mockito.<TransactionCallback<Boolean>>any()))
                    .thenAnswer(invocation -> invocation.<TransactionCallback<Boolean>>getArgument(0).doInTransaction(null));
            try {
                service.saveArtifactCatalogReference(backup, manifest, metadata, true);
                fail("A failed metadata reference update must propagate");
            } catch (CloudRuntimeException expected) {
                assertTrue(expected.getMessage().contains("metadata job reference"));
            }
        }
        assertEquals(original, loaded.get(ThirdPartyBackupManifest.DETAIL_KEY));
        verify(backup, never()).setExternalId(anyString());
        verify(detailsDao, never()).addDetail(anyLong(), anyString(), anyString(), Mockito.anyBoolean());
    }

    @Test
    public void legacyPreSubmissionFailureClearsOnlyAnUnsentIntent() throws Exception {
        prepare("ablestack-commvault");
        when(backup.getStatus()).thenReturn(Backup.Status.Failed);
        loaded.put("thirdparty.volume.failure", "Unable to persist external artifact submission intent");
        ThirdPartyBackupManifest.Artifact artifact = manifest.getCurrentArtifacts().get(0);
        artifact.submissionPending = true;
        artifact.submittedAt = 1;
        assertTrue(service.discardLegacyUnsubmittedIntent(backup, manifest));
        ThirdPartyBackupManifest.Artifact saved = ThirdPartyBackupManifest.fromJson(persisted.get(ThirdPartyBackupManifest.DETAIL_KEY))
                .getCurrentArtifacts().get(0);
        assertFalse(saved.submissionPending);
        assertTrue(saved.externalTerminal);
        assertNull(saved.jobId);
        assertEquals("WAITING", persisted.get(ThirdPartyBackupManifest.JOB_CLEANUP_STATE_KEY));
        assertFalse(service.discardLegacyUnsubmittedIntent(backup, manifest));
    }

    @Test
    public void legacyRecoveryKeepsAcceptedJobsAndOtherFailureReasons() throws Exception {
        prepare("ablestack-commvault");
        when(backup.getStatus()).thenReturn(Backup.Status.Failed);
        ThirdPartyBackupManifest.Artifact artifact = manifest.getCurrentArtifacts().get(0);
        artifact.submissionPending = true;
        artifact.submittedAt = 1;
        loaded.put("thirdparty.volume.failure", "Commvault task recovery returned an incomplete job list");
        assertFalse(service.discardLegacyUnsubmittedIntent(backup, manifest));
        loaded.put("thirdparty.volume.failure", "Unable to persist external artifact submission intent");
        artifact.jobId = "123";
        assertFalse(service.discardLegacyUnsubmittedIntent(backup, manifest));
        verify(detailsDao, never()).addDetail(anyLong(), anyString(), anyString(), Mockito.anyBoolean());
    }

    @Test
    public void recoveryQueryErrorsRetainTheJobForReadOnlyRetry() throws Exception {
        prepare("ablestack-commvault");
        ThirdPartyBackupManifest.Artifact artifact = manifest.getCurrentArtifacts().get(0);
        ThirdPartyBackupVolumeService.Transfer transfer = mock(ThirdPartyBackupVolumeService.Transfer.class);
        when(transfer.recover(artifact, false)).thenThrow(new CloudRuntimeException("incomplete job list")).thenReturn(artifact);
        assertNull(service.recoverPendingArtifact(backup, transfer, artifact, false));
        assertEquals("WAITING", persisted.get(ThirdPartyBackupManifest.CLEANUP_STATE_KEY));
        assertTrue(persisted.get(ThirdPartyBackupManifest.CLEANUP_DETAILS_KEY).contains("incomplete job list"));
        verify(backup, never()).setStatus(any());
        verify(agentManager, never()).send(anyLong(), any(Command.class));
        verify(transfer, never()).backup(any(), Mockito.anyBoolean());
        artifact.jobId = "789";
        assertEquals(artifact, service.recoverPendingArtifact(backup, transfer, artifact, false));
    }

    @Test
    public void pendingRecoveryIsRetriedWithoutCancellationOrDuplicateSubmission() throws Exception {
        prepare("ablestack-commvault");
        ThirdPartyBackupManifest.Artifact artifact = manifest.getCurrentArtifacts().get(0);
        artifact.submissionPending = true;
        artifact.submittedAt = 1;
        persisted.put(ThirdPartyBackupManifest.DETAIL_KEY, manifest.toJson());
        loaded.put(ThirdPartyBackupManifest.DETAIL_KEY, manifest.toJson());
        ThirdPartyBackupVolumeService.Transfer transfer = mock(ThirdPartyBackupVolumeService.Transfer.class);
        when(transfer.recover(any(), eq(false))).thenThrow(new CloudRuntimeException("incomplete job list"))
                .thenAnswer(invocation -> {
                    ThirdPartyBackupManifest.Artifact recovered = invocation.getArgument(0);
                    recovered.jobId = "789";
                    return recovered;
                });
        when(transfer.backup(any(), eq(false))).thenAnswer(invocation -> {
            ThirdPartyBackupManifest.Artifact recovered = invocation.getArgument(0);
            assertEquals("789", recovered.jobId);
            assertEquals("789", ThirdPartyBackupManifest.fromJson(persisted.get(ThirdPartyBackupManifest.DETAIL_KEY))
                    .getCurrentArtifacts().get(0).jobId);
            recovered.externalId = "point-789";
            recovered.completed = true;
            return recovered;
        });
        GlobalLock lock = mock(GlobalLock.class);
        when(lock.lock(Mockito.anyInt())).thenReturn(true);
        try (MockedStatic<GlobalLock> locks = Mockito.mockStatic(GlobalLock.class)) {
            locks.when(() -> GlobalLock.getInternLock(anyString())).thenReturn(lock);
            assertTrue(service.reconcile(backup, host, transfer));
            verify(transfer, never()).backup(any(), Mockito.anyBoolean());
            assertEquals("WAITING", persisted.get(ThirdPartyBackupManifest.CLEANUP_STATE_KEY));
            assertTrue(service.reconcile(backup, host, transfer));
        }
        verify(backup, never()).setStatus(any());
        verify(transfer).backup(any(), eq(false));
        verify(agentManager, never()).send(eq(HOST_ID), Mockito.<Command>argThat(command -> command instanceof AblestackVolumeStagingCommand
                && "CANCEL".equals(((AblestackVolumeStagingCommand) command).getAction())));
    }

    private void prepareFinalization() throws Exception {
        prepare("ablestack-commvault");
        manifest.getCurrentArtifacts().forEach(artifact -> {
            artifact.completed = true;
            artifact.jobId = "1127";
            artifact.externalId = "1127";
        });
        ThirdPartyBackupManifest.Artifact metadata = new ThirdPartyBackupManifest.Artifact();
        metadata.backupUuid = JOB_ID;
        metadata.path = backup.getExternalId();
        metadata.sourceHost = "host";
        metadata.completed = true;
        metadata.jobId = "1129";
        metadata.externalId = "1129";
        manifest.setMetadata(metadata);
        manifest.setComplete(true);
        persisted.put(ThirdPartyBackupManifest.DETAIL_KEY, manifest.toJson());
        loaded.put(ThirdPartyBackupManifest.DETAIL_KEY, manifest.toJson());
        ThirdPartyBackupStagingService staging = mock(ThirdPartyBackupStagingService.class);
        ReflectionTestUtils.setField(service, "stagingService", staging);
        when(staging.getStageRootPath("ablestack-commvault")).thenReturn("/staging/ablestack-commvault");
        when(backupDao.update(BACKUP_ID, backup)).thenReturn(true);
        when(agentManager.send(eq(HOST_ID), any(AblestackVolumeStagingCommand.class))).thenAnswer(invocation ->
                new Answer(invocation.getArgument(1), true,
                        "{\"backupUuid\":\"backup-id\",\"stagingCleanupState\":\"COMPLETED\"}"));
    }

    @Test
    public void checkpointPersistencePrecedesHostDirectoryRemovalAndFailureBlocksDeletion() throws Exception {
        prepareFinalization();
        ThirdPartyBackupVolumeService.Transfer transfer = mock(ThirdPartyBackupVolumeService.Transfer.class);
        doThrow(new CloudRuntimeException("Checkpoint DB write failed")).when(transfer).completed(backup);
        ReflectionTestUtils.invokeMethod(service, "finalizeBackup", backup, host, manifest, transfer);
        verify(agentManager, never()).send(eq(HOST_ID), Mockito.<Command>argThat(command -> command instanceof AblestackVolumeStagingCommand
                && "FINALIZE_BACKUP".equals(((AblestackVolumeStagingCommand) command).getAction())));
        verify(backup, never()).setStatus(Backup.Status.BackedUp);
        assertEquals("WAITING", persisted.get(ThirdPartyBackupManifest.CLEANUP_STATE_KEY));
    }

    @Test
    public void completeDirectoryRemovalIsRequiredBeforeCompletionAndCapacityRelease() throws Exception {
        prepareFinalization();
        ThirdPartyBackupVolumeService.Transfer transfer = mock(ThirdPartyBackupVolumeService.Transfer.class);
        GlobalLock lock = mock(GlobalLock.class);
        when(lock.lock(Mockito.anyInt())).thenReturn(true);
        try (MockedStatic<GlobalLock> locks = Mockito.mockStatic(GlobalLock.class);
                MockedStatic<Transaction> transactions = Mockito.mockStatic(Transaction.class)) {
            locks.when(() -> GlobalLock.getInternLock(anyString())).thenReturn(lock);
            transactions.when(() -> Transaction.execute(Mockito.<TransactionCallback<Boolean>>any()))
                    .thenAnswer(invocation -> invocation.<TransactionCallback<Boolean>>getArgument(0).doInTransaction(null));
            ReflectionTestUtils.invokeMethod(service, "finalizeBackup", backup, host, manifest, transfer);
        }
        InOrder order = inOrder(transfer, agentManager, backup);
        order.verify(transfer).completed(backup);
        order.verify(agentManager).send(eq(HOST_ID), Mockito.<Command>argThat(command -> command instanceof AblestackVolumeStagingCommand
                && "FINALIZE_BACKUP".equals(((AblestackVolumeStagingCommand) command).getAction())));
        order.verify(backup).setStatus(Backup.Status.BackedUp);
        assertEquals("COMPLETED", persisted.get(ThirdPartyBackupManifest.CLEANUP_STATE_KEY));
        assertEquals("RELEASED", new Gson().fromJson(persisted.get(ThirdPartyBackupAdmission.BACKUP_KEY), ThirdPartyBackupAdmission.Entry.class).state);
    }

    @Test
    public void qcow2IncrementalTracksParentSourceCleanupAfterSuccessfulFinalization() throws Exception {
        prepareFinalization();
        manifest.getCurrentArtifacts().forEach(artifact -> {
            artifact.checkpointName = "current";
            artifact.parentCheckpointName = "previous";
        });
        GlobalLock lock = mock(GlobalLock.class);
        when(lock.lock(Mockito.anyInt())).thenReturn(true);
        try (MockedStatic<GlobalLock> locks = Mockito.mockStatic(GlobalLock.class);
                MockedStatic<Transaction> transactions = Mockito.mockStatic(Transaction.class)) {
            locks.when(() -> GlobalLock.getInternLock(anyString())).thenReturn(lock);
            transactions.when(() -> Transaction.execute(Mockito.<TransactionCallback<Boolean>>any()))
                    .thenAnswer(invocation -> invocation.<TransactionCallback<Boolean>>getArgument(0).doInTransaction(null));
            ReflectionTestUtils.invokeMethod(service, "finalizeBackup", backup, host, manifest,
                    mock(ThirdPartyBackupVolumeService.Transfer.class));
        }
        assertEquals("WAITING", persisted.get(ThirdPartyBackupManifest.SOURCE_CLEANUP_STATE_KEY));
        assertEquals("COMPLETED", persisted.get(ThirdPartyBackupManifest.CLEANUP_STATE_KEY));
        verify(backup).setStatus(Backup.Status.BackedUp);
    }

    @Test
    public void legacyHostFinalizationWithoutDirectoryRemovalProofRemainsPending() throws Exception {
        prepareFinalization();
        when(agentManager.send(eq(HOST_ID), any(AblestackVolumeStagingCommand.class)))
                .thenAnswer(invocation -> new Answer(invocation.getArgument(1), true, "Host finalization completed"));
        ThirdPartyBackupVolumeService.Transfer transfer = mock(ThirdPartyBackupVolumeService.Transfer.class);
        ReflectionTestUtils.invokeMethod(service, "finalizeBackup", backup, host, manifest, transfer);
        verify(backup, never()).setStatus(Backup.Status.BackedUp);
        assertEquals("WAITING", persisted.get(ThirdPartyBackupManifest.CLEANUP_STATE_KEY));
        assertEquals("ADMITTED", new Gson().fromJson(persisted.get(ThirdPartyBackupAdmission.BACKUP_KEY), ThirdPartyBackupAdmission.Entry.class).state);
    }

    @Test
    public void recheckCleansAnOlderCompletedBackupWithoutSubmittingAnotherTransfer() throws Exception {
        prepareFinalization();
        when(backup.getStatus()).thenReturn(Backup.Status.BackedUp);
        ThirdPartyBackupVolumeService.Transfer transfer = mock(ThirdPartyBackupVolumeService.Transfer.class);
        AblestackBackupJobStatusCommand statusCommand = new AblestackBackupJobStatusCommand(JOB_ID);
        BackupAnswer completed = new BackupAnswer(statusCommand, true, "Engine terminated");
        completed.setState("COMPLETED");
        when(agentManager.send(eq(HOST_ID), any(AblestackBackupJobStatusCommand.class))).thenReturn(completed);
        GlobalLock lock = mock(GlobalLock.class);
        when(lock.lock(Mockito.anyInt())).thenReturn(true);
        try (MockedStatic<GlobalLock> locks = Mockito.mockStatic(GlobalLock.class);
                MockedStatic<Transaction> transactions = Mockito.mockStatic(Transaction.class)) {
            locks.when(() -> GlobalLock.getInternLock(anyString())).thenReturn(lock);
            transactions.when(() -> Transaction.execute(Mockito.<TransactionCallback<Boolean>>any()))
                    .thenAnswer(invocation -> invocation.<TransactionCallback<Boolean>>getArgument(0).doInTransaction(null));
            ReflectionTestUtils.invokeMethod(service, "manageBackup", backup, host, "RECHECK", null, null, transfer);
        }
        verify(transfer).completed(backup);
        verify(transfer, never()).backup(any(), Mockito.anyBoolean());
        assertEquals("COMPLETED", persisted.get(ThirdPartyBackupManifest.CLEANUP_STATE_KEY));
    }
}
