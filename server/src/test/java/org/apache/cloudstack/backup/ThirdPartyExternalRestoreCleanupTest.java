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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.cloudstack.api.response.BackupResponse;
import org.apache.cloudstack.backup.dao.BackupDao;
import org.apache.cloudstack.backup.dao.BackupDetailsDao;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;
import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.storage.Volume;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.db.DbUtil;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.VMInstanceDao;
import com.google.gson.Gson;

public class ThirdPartyExternalRestoreCleanupTest {
    private final Gson gson = new Gson();
    private final BackupDao backups = mock(BackupDao.class);
    private final BackupDetailsDao details = mock(BackupDetailsDao.class);
    private final HostDao hosts = mock(HostDao.class);
    private final VMInstanceDao vms = mock(VMInstanceDao.class);
    private final AgentManager agents = mock(AgentManager.class);
    private final ThirdPartyBackupStagingService staging = mock(ThirdPartyBackupStagingService.class);
    private final ThirdPartyBackupVolumeService volumes = mock(ThirdPartyBackupVolumeService.class);
    private final BackupVO backup = mock(BackupVO.class);
    private final HostVO host = mock(HostVO.class);
    private final VMInstanceVO vm = mock(VMInstanceVO.class);
    private final Map<String, String> saved = new HashMap<>();
    private ThirdPartyExternalRestoreCleanup service;
    private MockedStatic<Transaction> transactions;
    private String proof;
    private String provider;

    @Before
    public void setup() throws Exception {
        service = new ThirdPartyExternalRestoreCleanup(backups, details, hosts, vms, agents, staging, volumes);
        transactions = mockStatic(Transaction.class);
        transactions.when(() -> Transaction.execute(org.mockito.Mockito.<TransactionCallback<Boolean>>any()))
                .thenAnswer(call -> call.<TransactionCallback<Boolean>>getArgument(0).doInTransaction(null));
        when(backup.getId()).thenReturn(1L);
        when(backup.getUuid()).thenReturn("backup-1");
        when(backup.getVmId()).thenReturn(2L);
        when(backup.getZoneId()).thenReturn(3L);
        when(backup.getStatus()).thenReturn(Backup.Status.BackedUp);
        when(backup.getDetail(anyString())).thenAnswer(call -> saved.get(call.getArgument(0)));
        when(backups.findById(1L)).thenReturn(backup);
        when(backups.listByVmId(3L, 2L)).thenReturn(List.of(backup));
        when(hosts.findByName("ablecube4")).thenReturn(host);
        when(hosts.findById(4L)).thenReturn(host);
        when(host.getId()).thenReturn(4L);
        when(host.getDataCenterId()).thenReturn(3L);
        when(host.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(host.getStatus()).thenReturn(Status.Up);
        when(vms.findByIdIncludingRemoved(2L)).thenReturn(vm);
        when(vm.getUuid()).thenReturn("vm-2");
        when(vm.getState()).thenReturn(VirtualMachine.State.Running);
        doAnswer(call -> { saved.put(call.getArgument(1), call.getArgument(2)); return null; })
                .when(details).addDetail(eq(1L), anyString(), anyString(), eq(false));
        doAnswer(call -> { saved.remove(call.getArgument(1)); return null; }).when(details).removeDetail(eq(1L), anyString());
        when(details.listDetails(1L)).thenAnswer(call -> saved.entrySet().stream()
                .map(entry -> new BackupDetailVO(1L, entry.getKey(), entry.getValue(), false)).collect(Collectors.toList()));
        when(details.findDetails(eq(ThirdPartyExternalRestore.PENDING_KEY), eq("true"), isNull()))
                .thenReturn(List.of(new BackupDetailVO(1L, ThirdPartyExternalRestore.PENDING_KEY, "true", false)));
        when(volumes.acquireLifecycle(2L)).thenReturn(mock(ThirdPartyBackupVolumeService.Lease.class));
        when(agents.send(eq(4L), any(AblestackVolumeStagingCommand.class)))
                .thenAnswer(call -> new Answer(call.getArgument(1), true, ((AblestackVolumeStagingCommand) call.getArgument(1)).getJobId()));
        proof = gson.toJson(Map.of("hostName", "ablecube4", "startedAt", System.currentTimeMillis() - 1000, "endedAt", System.currentTimeMillis()));
        prepare("ablestack-netbackup");
    }

    @After public void teardown() { transactions.close(); }

    private void prepare(String value) {
        provider = value;
        saved.clear();
        String root = "/staging/" + provider + "/i-2-25-VM/2026.10.23.10.25.03.498";
        ThirdPartyBackupManifest manifest = ThirdPartyBackupManifest.create(provider, "backup-1", "i-2-25-VM",
                "2026.10.23.10.25.03.498", "FULL", "ablecube4", root, "QCOW2", List.of(
                new Backup.VolumeInfo("disk", "root.disk.qcow2", Volume.Type.ROOT, 1024L, 0L, null, null, null)), null, null);
        var volume = manifest.getCurrentArtifacts().get(0);
        volume.completed = true;
        volume.externalId = "root-point";
        volume.jobId = "root-job";
        var metadata = new ThirdPartyBackupManifest.Artifact();
        metadata.backupUuid = "backup-1";
        metadata.path = root;
        metadata.externalId = "metadata-point";
        metadata.jobId = "metadata-job";
        metadata.completed = true;
        manifest.setMetadata(metadata);
        manifest.setComplete(true);
        saved.put(ThirdPartyBackupManifest.DETAIL_KEY, manifest.toJson());
        var snapshot = new ThirdPartyBackupStagingService.Snapshot();
        snapshot.type = "GFS2";
        snapshot.mountPath = "/staging";
        snapshot.rootPath = "/staging";
        when(staging.captureConfiguration()).thenReturn(snapshot);
        saved.put(ThirdPartyBackupStagingService.SNAPSHOT_KEY, gson.toJson(snapshot));
    }

    private void record() { service.record(backup, provider, "metadata-point", "121", "The VM must be Stopped", proof); }
    private ThirdPartyExternalRestore.Attempt attempt() {
        return gson.fromJson(saved.get(ThirdPartyExternalRestore.key(provider, "121")), ThirdPartyExternalRestore.Attempt.class);
    }

    @Test public void recordsFailureAndCleansMetadataForBothProvidersWithoutChangingTheBackup() throws Exception {
        for (String value : List.of("ablestack-netbackup", "ablestack-veeam")) {
            prepare(value);
            record();
            assertEquals("FAILED", attempt().state);
            assertEquals("PRECHECK", attempt().step);
            assertEquals("COMPLETED", attempt().cleanupState);
            assertEquals(Backup.Status.BackedUp, backup.getStatus());
            assertEquals(1, attempt().destinations.size());
            verify(backups, never()).update(anyLong(), any(BackupVO.class));
        }
    }

    @Test public void cleanupDefersWhileAnotherBackupOwnsTheVmAndResumesAfterRestart() throws Exception {
        when(backup.getStatus()).thenReturn(Backup.Status.BackingUp);
        record();
        assertEquals("WAITING", attempt().cleanupState);
        verifyNoInteractions(agents);
        when(backup.getStatus()).thenReturn(Backup.Status.BackedUp);
        new ThirdPartyExternalRestoreCleanup(backups, details, hosts, vms, agents, staging, volumes).resumePending();
        assertEquals("COMPLETED", attempt().cleanupState);
        assertNull(saved.get(ThirdPartyExternalRestore.PENDING_KEY));
    }

    @Test public void hostUnavailableAndUnconfirmedAnswersRetainCleanupEvidence() throws Exception {
        when(host.getStatus()).thenReturn(Status.Disconnected);
        record();
        assertEquals("WAITING", attempt().cleanupState);
        when(host.getStatus()).thenReturn(Status.Up);
        when(agents.send(eq(4L), any(AblestackVolumeStagingCommand.class))).thenReturn(null);
        service.resumePending();
        assertEquals("WAITING", attempt().cleanupState);
        assertEquals("true", saved.get(ThirdPartyExternalRestore.PENDING_KEY));
    }

    @Test public void duplicateCompletedCallbackDoesNotAdoptNewFilesOrNewProof() throws Exception {
        record();
        proof = "{}";
        when(vm.getState()).thenReturn(VirtualMachine.State.Stopped);
        record();
        assertEquals("COMPLETED", attempt().cleanupState);
        verify(agents, times(1)).send(eq(4L), any(AblestackVolumeStagingCommand.class));
        assertThrows(CloudRuntimeException.class, () -> service.record(backup, provider, "another-point", "121", "stopped", proof));
    }

    @Test public void rejectedApiRequiresAnUnownedMetadataArtifact() {
        BackupManagerImpl manager = spy(new BackupManagerImpl());
        ReflectionTestUtils.setField(manager, "backupDao", backups);
        ReflectionTestUtils.setField(manager, "thirdPartyBackupVolumeService", volumes);
        when(backups.findByUuid("backup-1")).thenReturn(backup);
        var resolved = new org.apache.cloudstack.api.response.BackupArtifactResolutionResponse();
        resolved.matched = true;
        resolved.id = "backup-1";
        resolved.artifacttype = "METADATA";
        doReturn(resolved).when(manager).resolveBackupArtifact(provider, "metadata-point", "121", null);
        manager.rejectBackupArtifactRestore(provider, "metadata-point", "121", "VM must be Stopped", proof);
        verify(volumes).rejectExternalRestore(backup, provider, "metadata-point", "121", "VM must be Stopped", proof);
        resolved.tracked = true;
        assertThrows(CloudRuntimeException.class, () -> manager.rejectBackupArtifactRestore(provider, "metadata-point", "121", "Stopped", proof));
        resolved.tracked = false;
        resolved.artifacttype = "VOLUME";
        assertThrows(CloudRuntimeException.class, () -> manager.rejectBackupArtifactRestore(provider, "metadata-point", "121", "Stopped", proof));
    }

    @Test public void invalidCompletionProofNeverAuthorizesDeletion() {
        proof = "{}";
        assertThrows(CloudRuntimeException.class, this::record);
        verifyNoInteractions(agents);
        assertNull(saved.get(ThirdPartyExternalRestore.LATEST_KEY));
    }

    @Test public void failureResponseKeepsBackupAvailableAndYieldsToNewerVmRestoration() {
        record();
        BackupManagerImpl manager = new BackupManagerImpl();
        BackupResponse response = new BackupResponse();
        ReflectionTestUtils.invokeMethod(manager, "populateRejectedExternalRestore", backup, response);
        assertEquals("FAILED", response.getRestoreJobState());
        assertTrue(gson.toJson(response).contains("121"));
        saved.put(ThirdPartyExternalRestore.STARTED_KEY, String.valueOf(System.currentTimeMillis() + 1000));
        response = new BackupResponse();
        ReflectionTestUtils.invokeMethod(manager, "populateRejectedExternalRestore", backup, response);
        assertNull(response.getRestoreJobState());
        saved.remove(ThirdPartyExternalRestore.STARTED_KEY);
        saved.put(AblestackBackupFrameworkUtils.RESTORE_JOB_STATE_DETAIL, "RUNNING");
        response = new BackupResponse();
        ReflectionTestUtils.invokeMethod(manager, "populateRejectedExternalRestore", backup, response);
        assertNull(response.getRestoreJobState());
    }

    private String callbackKey(String jobId) {
        return "thirdparty.volume.ui.restore." + java.util.UUID.nameUUIDFromBytes(
                (provider + ":" + jobId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private BackupManagerImpl callbackManager(String jobId) {
        BackupManagerImpl manager = spy(new BackupManagerImpl());
        ReflectionTestUtils.setField(manager, "backupDao", backups);
        ReflectionTestUtils.setField(manager, "backupDetailsDao", details);
        ReflectionTestUtils.setField(manager, "thirdPartyBackupVolumeService", volumes);
        when(backups.findByUuid("backup-1")).thenReturn(backup);
        var resolved = new org.apache.cloudstack.api.response.BackupArtifactResolutionResponse();
        resolved.matched = true;
        resolved.restoreallowed = true;
        resolved.id = "backup-1";
        resolved.artifacttype = "METADATA";
        doReturn(resolved).when(manager).resolveBackupArtifact(provider, "metadata-point", jobId, null);
        doReturn(resolved).when(manager).resolveBackupArtifact(provider, "metadata-point", null, null);
        doAnswer(call -> {
            service.record(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3),
                    call.getArgument(4), call.getArgument(5));
            return null;
        }).when(volumes).rejectExternalRestore(eq(backup), eq(provider), eq("metadata-point"), eq(jobId), anyString(), anyString());
        doAnswer(call -> {
            service.trackAccepted(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3), call.getArgument(4));
            return null;
        }).when(volumes).trackExternalRestoreMetadata(eq(backup), eq(provider), eq("metadata-point"), eq(jobId), anyString());
        return manager;
    }

    private MockedStatic<DbUtil> mysqlLocks() {
        MockedStatic<DbUtil> database = mockStatic(DbUtil.class);
        database.when(() -> DbUtil.getGlobalLock(anyString(), anyInt()))
                .thenAnswer(call -> ((String) call.getArgument(0)).length() <= 64);
        database.when(() -> DbUtil.releaseGlobalLock(anyString())).thenReturn(true);
        return database;
    }

    @Test public void externalCallbackUsesCompactLockAndKeepsItsPersistedReceiptKeyForBothProviders() {
        for (String value : List.of("ablestack-netbackup", "ablestack-veeam")) {
            prepare(value);
            String jobId = provider.equals("ablestack-netbackup") ? "359" : "12345678-1234-1234-1234-123456789012";
            BackupManagerImpl manager = callbackManager(jobId);
            doReturn(true).when(manager).restoreBackup(1L, false, null);
            try (MockedStatic<DbUtil> database = mysqlLocks()) {
                assertTrue(manager.restoreBackupArtifact(provider, "metadata-point", jobId, proof));
                var tracked = new org.apache.cloudstack.api.response.BackupArtifactResolutionResponse();
                tracked.matched = tracked.tracked = true;
                doReturn(tracked).when(manager).resolveBackupArtifact(provider, "metadata-point", jobId, null);
                assertTrue(manager.restoreBackupArtifact(provider, "metadata-point", jobId, proof));
                verify(manager, times(1)).restoreBackup(1L, false, null);
                String lock = ThirdPartyBackupRestore.externalLockName(provider, jobId);
                assertEquals(38, lock.length());
                database.verify(() -> DbUtil.getGlobalLock(lock, 5), times(2));
                database.verify(() -> DbUtil.releaseGlobalLock(lock), times(2));
                assertEquals(65, callbackKey(jobId).length());
                assertEquals("SUBMITTED", gson.fromJson(saved.get(callbackKey(jobId)), Map.class).get("state"));
                assertNull(saved.get(ThirdPartyExternalRestore.LATEST_KEY));
            }
        }
    }

    @Test public void callbackFailureReplacesPreviousUiFailureAndCleansItsMetadataForBothProviders() {
        for (String value : List.of("ablestack-netbackup", "ablestack-veeam")) {
            prepare(value);
            record();
            String jobId = provider.equals("ablestack-netbackup") ? "359" : "12345678-1234-1234-1234-123456789012";
            BackupManagerImpl manager = callbackManager(jobId);
            CloudRuntimeException failure = new CloudRuntimeException("Restore target capacity is unavailable");
            doThrow(failure).when(manager).restoreBackup(1L, false, null);
            try (MockedStatic<DbUtil> database = mysqlLocks()) {
                assertSame(failure, assertThrows(CloudRuntimeException.class,
                        () -> manager.restoreBackupArtifact(provider, "metadata-point", jobId, proof)));
                assertEquals("FAILED", gson.fromJson(saved.get(callbackKey(jobId)), Map.class).get("state"));
                var latest = gson.fromJson(saved.get(ThirdPartyExternalRestore.key(provider, jobId)), ThirdPartyExternalRestore.Attempt.class);
                assertEquals("COMPLETED", latest.cleanupState);
                assertEquals(failure.getMessage(), latest.reason);
                assertEquals(latest.id, saved.get(ThirdPartyExternalRestore.LATEST_KEY));
                BackupResponse response = new BackupResponse();
                ReflectionTestUtils.invokeMethod(manager, "populateRejectedExternalRestore", backup, response);
                assertEquals("FAILED", response.getRestoreJobState());
                assertTrue(gson.toJson(response).contains(jobId));
                assertTrue(gson.toJson(response).contains(failure.getMessage()));
                assertEquals(Backup.Status.BackedUp, backup.getStatus());
                verify(backups, never()).update(anyLong(), any(BackupVO.class));
            }
        }
    }

    @Test public void declinedRestoreIsAlsoRecordedAsTheLatestFailure() {
        BackupManagerImpl manager = callbackManager("359");
        doReturn(false).when(manager).restoreBackup(1L, false, null);
        try (MockedStatic<DbUtil> database = mysqlLocks()) {
            assertEquals("Logical backup restore was not submitted", assertThrows(CloudRuntimeException.class,
                    () -> manager.restoreBackupArtifact(provider, "metadata-point", "359", proof)).getMessage());
            var latest = gson.fromJson(saved.get(ThirdPartyExternalRestore.key(provider, "359")), ThirdPartyExternalRestore.Attempt.class);
            assertEquals("COMPLETED", latest.cleanupState);
            assertEquals("Logical backup restore was not submitted", latest.reason);
        }
    }

    @Test public void contendingCallbackCannotStartRestoreOrDeleteAnotherRequestsMetadata() {
        BackupManagerImpl manager = callbackManager("359");
        String lock = ThirdPartyBackupRestore.externalLockName(provider, "359");
        try (MockedStatic<DbUtil> database = mysqlLocks()) {
            database.when(() -> DbUtil.getGlobalLock(lock, 5)).thenReturn(false);
            assertEquals("External restore callback is being claimed; retry shortly", assertThrows(CloudRuntimeException.class,
                    () -> manager.restoreBackupArtifact(provider, "metadata-point", "359", proof)).getMessage());
            verify(manager, never()).restoreBackup(anyLong(), eq(false), isNull());
            verifyNoInteractions(agents);
            database.verify(() -> DbUtil.releaseGlobalLock(lock), never());
            assertNull(saved.get(ThirdPartyExternalRestore.LATEST_KEY));
        }
    }

    @Test public void failedCallbackCannotCleanAnAlreadySubmittedClaim() {
        BackupManagerImpl manager = callbackManager("359");
        saved.put(callbackKey("359"), gson.toJson(Map.of("state", "SUBMITTED")));
        CloudRuntimeException failure = new CloudRuntimeException("Another request owns this callback");
        doThrow(failure).when(manager).resolveBackupArtifact(provider, "metadata-point", "359", null);
        try (MockedStatic<DbUtil> database = mysqlLocks()) {
            assertSame(failure, assertThrows(CloudRuntimeException.class,
                    () -> manager.restoreBackupArtifact(provider, "metadata-point", "359", proof)));
            verifyNoInteractions(agents);
            assertNull(saved.get(ThirdPartyExternalRestore.LATEST_KEY));
        }
    }

    @Test public void legacyCallbackWithoutProofStillWorksButCannotAuthorizeFailureCleanup() {
        BackupManagerImpl manager = callbackManager("359");
        doReturn(true).when(manager).restoreBackup(1L, false, null);
        try (MockedStatic<DbUtil> database = mysqlLocks()) {
            assertTrue(manager.restoreBackupArtifact(provider, "metadata-point", "359"));
            BackupManagerImpl failedManager = callbackManager("360");
            CloudRuntimeException failure = new CloudRuntimeException("Cannot prepare restore");
            doThrow(failure).when(failedManager).restoreBackup(1L, false, null);
            assertSame(failure, assertThrows(CloudRuntimeException.class,
                    () -> failedManager.restoreBackupArtifact(provider, "metadata-point", "360")));
            verifyNoInteractions(agents);
            assertNull(saved.get(ThirdPartyExternalRestore.LATEST_KEY));
        }
    }

    private void submittedClaim(String jobId, String state) {
        saved.put(callbackKey(jobId), gson.toJson(Map.of("state", state, "provider", provider, "jobId", jobId,
                "externalId", "metadata-point", "backupUuid", "backup-1")));
    }

    @Test public void acceptedMetadataWaitsForSubmissionVmTerminationAndStagingCleanupAcrossRestartForBothProviders() throws Exception {
        for (String value : List.of("ablestack-netbackup", "ablestack-veeam")) {
            prepare(value);
            service.trackAccepted(backup, provider, "metadata-point", "121", proof);
            assertTrue(attempt().accepted);
            assertEquals("WAITING", attempt().cleanupState);
            assertNull(saved.get(ThirdPartyExternalRestore.LATEST_KEY));
            submittedClaim("121", "CLAIMED");
            service.resumePending();
            assertEquals("WAITING", attempt().cleanupState);
            submittedClaim("121", "SUBMITTED");
            when(backup.getStatus()).thenReturn(Backup.Status.Restoring);
            service.resumePending();
            assertEquals("WAITING", attempt().cleanupState);
            when(backup.getStatus()).thenReturn(Backup.Status.BackedUp);
            saved.put(ThirdPartyBackupRestore.PLAN_KEY, "{}");
            saved.put(ThirdPartyBackupRestore.CLEANUP_STATE_KEY, "WAITING");
            service.resumePending();
            assertEquals("WAITING", attempt().cleanupState);
            saved.put(ThirdPartyBackupRestore.CLEANUP_STATE_KEY, "COMPLETED");
            saved.put(AblestackBackupFrameworkUtils.RESTORE_JOB_STATE_DETAIL, "RUNNING");
            service.resumePending();
            assertEquals("WAITING", attempt().cleanupState);
            saved.put(AblestackBackupFrameworkUtils.RESTORE_JOB_STATE_DETAIL, "COMPLETED");
            new ThirdPartyExternalRestoreCleanup(backups, details, hosts, vms, agents, staging, volumes).resumePending();
            assertEquals("COMPLETED", attempt().cleanupState);
            assertEquals("COMPLETED", saved.get(AblestackBackupFrameworkUtils.RESTORE_JOB_STATE_DETAIL));
            assertNull(saved.get(ThirdPartyExternalRestore.PENDING_KEY));
            assertNull(saved.get(ThirdPartyExternalRestore.LATEST_KEY));
            assertEquals(4L, attempt().hostId);
        }
        verify(agents, times(2)).send(eq(4L), any(AblestackVolumeStagingCommand.class));
    }

    @Test public void callbackPersistsAcceptedMetadataBeforeTheVmRestoreCanStart() {
        BackupManagerImpl manager = callbackManager("364");
        doAnswer(call -> {
            var accepted = gson.fromJson(saved.get(ThirdPartyExternalRestore.key(provider, "364")), ThirdPartyExternalRestore.Attempt.class);
            assertTrue(accepted.accepted);
            assertEquals("WAITING", accepted.cleanupState);
            assertEquals("CLAIMED", gson.fromJson(saved.get(callbackKey("364")), Map.class).get("state"));
            service.resumePending();
            verifyNoInteractions(agents);
            return true;
        }).when(manager).restoreBackup(1L, false, null);
        try (MockedStatic<DbUtil> database = mysqlLocks()) {
            assertTrue(manager.restoreBackupArtifact(provider, "metadata-point", "364", proof));
        }
        service.resumePending();
        var accepted = gson.fromJson(saved.get(ThirdPartyExternalRestore.key(provider, "364")), ThirdPartyExternalRestore.Attempt.class);
        assertEquals("COMPLETED", accepted.cleanupState);
        assertNull(saved.get(ThirdPartyExternalRestore.LATEST_KEY));
    }

    @Test public void acceptedCleanupIsVisibleWithoutReplacingTheSuccessfulVmResult() {
        BackupManagerImpl manager = callbackManager("121");
        service.trackAccepted(backup, provider, "metadata-point", "121", proof);
        saved.put(ThirdPartyBackupRestore.CLEANUP_STATE_KEY, "COMPLETED");
        BackupResponse response = new BackupResponse();
        response.setRestoreJobState("COMPLETED");
        response.setRestoreCleanupState("COMPLETED");
        ReflectionTestUtils.invokeMethod(manager, "populateExternalMetadataCleanup", backup, response);
        assertEquals("COMPLETED", response.getRestoreJobState());
        assertTrue(gson.toJson(response).contains("External metadata cleanup pending for Job 121"));
        assertTrue(gson.toJson(response).contains("\"restorecleanupstate\":\"WAITING\""));
        assertFalse(gson.toJson(response).contains("externalrestorefailure"));
        submittedClaim("121", "SUBMITTED");
        service.resumePending();
        response = new BackupResponse();
        response.setRestoreJobState("COMPLETED");
        response.setRestoreCleanupState("COMPLETED");
        ReflectionTestUtils.invokeMethod(manager, "populateExternalMetadataCleanup", backup, response);
        assertTrue(gson.toJson(response).contains("\"restorecleanupstate\":\"COMPLETED\""));
        assertFalse(gson.toJson(response).contains("External metadata cleanup pending"));
    }

    @Test public void metadataCleanupDoesNotHideAnUnfinishedVmOrPrimaryStorageCleanupReason() {
        BackupManagerImpl manager = callbackManager("121");
        service.trackAccepted(backup, provider, "metadata-point", "121", proof);
        saved.put(ThirdPartyBackupRestore.CLEANUP_STATE_KEY, "WAITING");
        BackupResponse response = new BackupResponse();
        response.setRestoreCleanupState("WAITING");
        response.setRestoreCleanupDetails("Primary volume rollback must be reconciled");
        ReflectionTestUtils.invokeMethod(manager, "populateExternalMetadataCleanup", backup, response);
        assertTrue(gson.toJson(response).contains("Primary volume rollback must be reconciled"));
        assertFalse(gson.toJson(response).contains("External metadata cleanup pending"));
    }

    @Test public void cleanupOnlyReconcilesAnOlderSubmittedCallbackWithoutStartingAnotherRestoreForBothProviders() {
        for (String value : List.of("ablestack-netbackup", "ablestack-veeam")) {
            prepare(value);
            String jobId = provider.equals("ablestack-netbackup") ? "364" : "12345678-1234-1234-1234-123456789012";
            BackupManagerImpl manager = callbackManager(jobId);
            submittedClaim(jobId, "SUBMITTED");
            var tracked = new org.apache.cloudstack.api.response.BackupArtifactResolutionResponse();
            tracked.matched = tracked.tracked = true;
            tracked.id = "backup-1";
            doReturn(tracked).when(manager).resolveBackupArtifact(provider, "metadata-point", jobId, null);
            try (MockedStatic<DbUtil> database = mysqlLocks()) {
                assertTrue(manager.restoreBackupArtifact(provider, "metadata-point", jobId, proof, true));
                verify(manager, never()).restoreBackup(anyLong(), eq(false), isNull());
                service.resumePending();
                var accepted = gson.fromJson(saved.get(ThirdPartyExternalRestore.key(provider, jobId)), ThirdPartyExternalRestore.Attempt.class);
                assertTrue(accepted.accepted);
                assertEquals("COMPLETED", accepted.cleanupState);
                // A replay never replaces the original proof or creates another VM restore.
                assertTrue(manager.restoreBackupArtifact(provider, "metadata-point", jobId, "{}", true));
                assertEquals(gson.fromJson(proof, Map.class), gson.fromJson(gson.toJson(gson.fromJson(
                        saved.get(ThirdPartyExternalRestore.key(provider, jobId)), ThirdPartyExternalRestore.Attempt.class).proof), Map.class));
                assertNull(saved.get(ThirdPartyExternalRestore.LATEST_KEY));
            }
        }
    }

    @Test public void cleanupOnlyRequiresAnExactPreviouslySubmittedClaimAndNeverLaunchesAVmRestore() {
        BackupManagerImpl manager = callbackManager("364");
        try (MockedStatic<DbUtil> database = mysqlLocks()) {
            assertThrows(CloudRuntimeException.class, () -> manager.restoreBackupArtifact(provider, "metadata-point", "364", proof, true));
            var tracked = new org.apache.cloudstack.api.response.BackupArtifactResolutionResponse();
            tracked.matched = tracked.tracked = true;
            tracked.id = "backup-1";
            doReturn(tracked).when(manager).resolveBackupArtifact(provider, "metadata-point", "364", null);
            assertThrows(CloudRuntimeException.class, () -> manager.restoreBackupArtifact(provider, "metadata-point", "364", proof, true));
            for (String state : List.of("CLAIMED", "FAILED")) {
                submittedClaim("364", state);
                assertThrows(CloudRuntimeException.class, () -> manager.restoreBackupArtifact(provider, "metadata-point", "364", proof, true));
            }
            submittedClaim("364", "SUBMITTED");
            assertThrows(CloudRuntimeException.class, () -> manager.restoreBackupArtifact(provider, "metadata-point", "364", null, true));
            verify(manager, never()).restoreBackup(anyLong(), eq(false), isNull());
            verifyNoInteractions(agents);
            assertNull(saved.get(ThirdPartyExternalRestore.LATEST_KEY));
            assertNull(saved.get(ThirdPartyExternalRestore.key(provider, "364")));
        }
    }

    @Test public void trackedCallbacksLoadDetailsOnFreshDatabaseObjectsForBothProviders() {
        for (String value : List.of("ablestack-netbackup", "ablestack-veeam")) {
            for (boolean cleanupOnly : List.of(false, true)) {
                prepare(value);
                String jobId = provider.equals("ablestack-netbackup") ? "364" : "12345678-1234-1234-1234-123456789012";
                BackupManagerImpl manager = callbackManager(jobId);
                submittedClaim(jobId, "SUBMITTED");
                BackupVO fresh = new BackupVO();
                ReflectionTestUtils.setField(fresh, "id", 1L);
                ReflectionTestUtils.setField(fresh, "uuid", "backup-1");
                fresh.setVmId(2L);
                fresh.setZoneId(3L);
                fresh.setStatus(Backup.Status.BackedUp);
                assertNull(fresh.getDetails());
                when(backups.findByUuid("backup-1")).thenReturn(fresh);
                doAnswer(call -> { fresh.setDetails(new HashMap<>(saved)); return null; }).when(backups).loadDetails(fresh);
                doAnswer(call -> {
                    service.trackAccepted(fresh, provider, "metadata-point", jobId, proof);
                    return null;
                }).when(volumes).trackExternalRestoreMetadata(eq(fresh), eq(provider), eq("metadata-point"), eq(jobId), eq(proof));
                var tracked = new org.apache.cloudstack.api.response.BackupArtifactResolutionResponse();
                tracked.matched = tracked.tracked = true;
                tracked.id = "backup-1";
                doReturn(tracked).when(manager).resolveBackupArtifact(provider, "metadata-point", jobId, null);
                try (MockedStatic<DbUtil> database = mysqlLocks()) {
                    assertTrue(manager.restoreBackupArtifact(provider, "metadata-point", jobId, proof, cleanupOnly));
                    verify(manager, never()).restoreBackup(anyLong(), eq(false), isNull());
                    service.resumePending();
                    var accepted = gson.fromJson(saved.get(ThirdPartyExternalRestore.key(provider, jobId)), ThirdPartyExternalRestore.Attempt.class);
                    assertEquals("COMPLETED", accepted.cleanupState);
                    assertNull(saved.get(ThirdPartyExternalRestore.LATEST_KEY));
                }
            }
        }
    }
}
