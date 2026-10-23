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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.storage.Volume;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.Gson;
import org.apache.cloudstack.backup.dao.BackupDao;
import org.apache.cloudstack.backup.dao.BackupDetailsDao;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.Assert.assertEquals;
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

public class ThirdPartyBackupRestoreHistoryDeletionTest {
    private final Map<String, String> details = new HashMap<>();
    private final Gson gson = new Gson();
    private final ThirdPartyBackupVolumeServiceImpl service = new ThirdPartyBackupVolumeServiceImpl();
    private final BackupVO backup = mock(BackupVO.class);
    private final BackupDetailsDao detailDao = mock(BackupDetailsDao.class);
    private final AgentManager agents = mock(AgentManager.class);
    private final HostDao hosts = mock(HostDao.class);
    private MockedStatic<GlobalLock> locks;

    @Before
    public void setup() throws Exception {
        GlobalLock lock = mock(GlobalLock.class);
        when(lock.lock(5)).thenReturn(true);
        locks = Mockito.mockStatic(GlobalLock.class);
        locks.when(() -> GlobalLock.getInternLock(anyString())).thenReturn(lock);
        when(backup.getId()).thenReturn(1L);
        when(backup.getUuid()).thenReturn("backup-1");
        when(backup.getStatus()).thenReturn(Backup.Status.BackedUp);
        ThirdPartyBackupManifest manifest = ThirdPartyBackupManifest.create("ablestack-commvault", "backup-1", "vm-1", "2026.10.16.12.00.00", "FULL",
                "host-4", "/stage/ablestack-commvault/vm-1/2026.10.16.12.00.00", "QCOW2",
                Collections.singletonList(new Backup.VolumeInfo("volume-1", "root.qcow2", Volume.Type.ROOT, 1073741824L, 0L, null, null, null)), null, null);
        details.put(ThirdPartyBackupManifest.DETAIL_KEY, manifest.toJson());
        when(backup.getDetail(anyString())).thenAnswer(call -> details.get(call.<String>getArgument(0)));
        when(detailDao.findDetail(eq(1L), anyString())).thenAnswer(call -> {
            String key = call.getArgument(1);
            return details.containsKey(key) ? new BackupDetailVO(1L, key, details.get(key), false) : null;
        });
        when(detailDao.listDetails(1L)).thenAnswer(call -> {
            ArrayList<BackupDetailVO> rows = new ArrayList<>();
            details.forEach((key, value) -> rows.add(new BackupDetailVO(1L, key, value, false)));
            return rows;
        });
        doAnswer(call -> {
            details.put(call.getArgument(1), call.getArgument(2));
            return null;
        }).when(detailDao).addDetail(eq(1L), anyString(), anyString(), eq(false));
        for (long id : new long[]{4L, 5L}) {
            HostVO host = mock(HostVO.class);
            when(host.getId()).thenReturn(id);
            when(host.getName()).thenReturn("host-" + id);
            when(host.getStatus()).thenReturn(Status.Up);
            when(hosts.findById(id)).thenReturn(host);
        }
        when(agents.send(Mockito.anyLong(), any(AblestackVolumeStagingCommand.class))).thenAnswer(call -> {
            AblestackVolumeStagingCommand command = call.getArgument(1);
            ThirdPartyBackupRestore.Plan plan = gson.fromJson(command.getManifest(), ThirdPartyBackupRestore.Plan.class);
            return new Answer(command, true, gson.toJson(Map.of("version", 1, "kind", "RESTORE", "jobId", plan.jobId,
                    "sourceBackupUuid", plan.manifest.getBackupUuid(), "targetVmName", plan.targetVmName)));
        });
        ReflectionTestUtils.setField(service, "backupDetailsDao", detailDao);
        ReflectionTestUtils.setField(service, "agentManager", agents);
        ReflectionTestUtils.setField(service, "hostDao", hosts);
    }

    @After
    public void close() { locks.close(); }

    private ThirdPartyBackupRestore.Operation attempt(String id, long hostId, String cleanup) {
        ThirdPartyBackupRestore.Operation operation = new ThirdPartyBackupRestore.Operation();
        operation.plan = new ThirdPartyBackupRestore.Plan();
        operation.plan.jobId = id;
        operation.plan.hostId = hostId;
        operation.plan.hostName = "host-" + hostId;
        operation.plan.targetVmName = "target-" + id;
        operation.plan.manifest = ThirdPartyBackupManifest.fromJson(details.get(ThirdPartyBackupManifest.DETAIL_KEY));
        operation.plan.volumeUuids = Collections.singletonList("volume-1");
        operation.cleanupState = cleanup;
        operation.hostState = "COMPLETED";
        details.put(ThirdPartyBackupRestore.operationKey(id), gson.toJson(operation));
        return operation;
    }

    private void retire() { ReflectionTestUtils.invokeMethod(service, "retireRestoreHistoryForDeletion", backup); }

    @Test
    public void deletesAllAttemptsOnTheirOwnHostsAndPersistsEachAcknowledgement() throws Exception {
        attempt("older-attempt", 4L, "COMPLETED");
        attempt("latest-attempt", 5L, "COMPLETED");
        retire();
        for (String id : new String[]{"older-attempt", "latest-attempt"}) {
            ThirdPartyBackupRestore.Operation saved = gson.fromJson(details.get(ThirdPartyBackupRestore.operationKey(id)), ThirdPartyBackupRestore.Operation.class);
            assertTrue(saved.hostRecordsRetiredAt > 0);
            verify(agents).send(eq(saved.plan.hostId), Mockito.<AblestackVolumeStagingCommand>argThat(command ->
                    "RESTORE_RETIRE".equals(command.getAction()) && id.equals(command.getJobId())));
        }
        retire();
        verify(agents, Mockito.times(2)).send(Mockito.anyLong(), any(AblestackVolumeStagingCommand.class));
        verify(detailDao, never()).removeDetails(1L);
    }

    @Test
    public void offlineHostRetainsDbOwnershipAndCanBeRetriedWithoutRequiringAnAcknowledgedHost() throws Exception {
        attempt("older-attempt", 4L, "COMPLETED");
        when(hosts.findById(4L).getStatus()).thenReturn(Status.Disconnected);
        CloudRuntimeException error = assertThrows(CloudRuntimeException.class, this::retire);
        assertTrue(error.getMessage().contains("Host is unavailable"));
        assertEquals("WAITING", details.get(ThirdPartyBackupManifest.CLEANUP_STATE_KEY));
        assertTrue(details.containsKey(ThirdPartyBackupRestore.operationKey("older-attempt")));
        verifyNoInteractions(agents);
        when(hosts.findById(4L).getStatus()).thenReturn(Status.Up);
        retire();
        when(hosts.findById(4L).getStatus()).thenReturn(Status.Disconnected);
        retire();
        verify(agents, Mockito.times(1)).send(eq(4L), any(AblestackVolumeStagingCommand.class));
    }

    @Test
    public void incompleteEarlierAttemptBlocksDeletionEvenWhenLatestAttemptCompleted() {
        attempt("older-attempt", 4L, "WAITING");
        attempt("latest-attempt", 5L, "COMPLETED");
        assertThrows(CloudRuntimeException.class, this::retire);
        verifyNoInteractions(agents);
    }

    @Test
    public void pendingFailedInstanceCleanupKeepsItsRestoreHistory() {
        attempt("attempt-1", 4L, "COMPLETED");
        details.put(AblestackRestoreInstanceCleanup.PENDING_KEY, "301");
        assertThrows(CloudRuntimeException.class, this::retire);
        verifyNoInteractions(agents);
    }

    @Test
    public void historyFromAnotherBackupIsNeverSentToHost() {
        ThirdPartyBackupRestore.Operation operation = attempt("attempt-1", 4L, "COMPLETED");
        operation.plan.manifest.setBackupUuid("other-backup");
        details.put(ThirdPartyBackupRestore.operationKey(operation.plan.jobId), gson.toJson(operation));
        assertThrows(CloudRuntimeException.class, this::retire);
        verifyNoInteractions(agents);
    }

    @Test
    public void unrelatedHostAcknowledgementCannotAuthorizeDeletion() throws Exception {
        attempt("attempt-1", 4L, "COMPLETED");
        when(agents.send(eq(4L), any(AblestackVolumeStagingCommand.class))).thenReturn(new Answer(null, true,
                "{\"version\":1,\"kind\":\"RESTORE\",\"jobId\":\"other-job\",\"sourceBackupUuid\":\"backup-1\",\"targetVmName\":\"target-attempt-1\"}"));
        assertThrows(CloudRuntimeException.class, this::retire);
        assertEquals(0L, gson.fromJson(details.get(ThirdPartyBackupRestore.operationKey("attempt-1")), ThirdPartyBackupRestore.Operation.class).hostRecordsRetiredAt);
    }

    @Test
    public void previouslyCompletedDeletionStillBackfillsHostRestoreCleanup() {
        attempt("attempt-1", 4L, "COMPLETED");
        details.put(ThirdPartyBackupManifest.DELETE_PROGRESS_KEY, "{\"complete\":true}");
        BackupDao backups = mock(BackupDao.class);
        when(backups.findById(1L)).thenReturn(backup);
        ReflectionTestUtils.setField(service, "backupDao", backups);
        assertTrue(ReflectionTestUtils.<Boolean>invokeMethod(service, "deleteLocked", 1L, mock(ThirdPartyBackupVolumeService.ArtifactCleanup.class)));
        assertTrue(gson.fromJson(details.get(ThirdPartyBackupRestore.operationKey("attempt-1")), ThirdPartyBackupRestore.Operation.class).hostRecordsRetiredAt > 0);
    }

    @Test
    public void failedHostCleanupHappensBeforeExternalBackupDeletion() throws Exception {
        attempt("attempt-1", 4L, "COMPLETED");
        BackupDao backups = mock(BackupDao.class);
        when(backups.findById(1L)).thenReturn(backup);
        ReflectionTestUtils.setField(service, "backupDao", backups);
        when(agents.send(eq(4L), any(AblestackVolumeStagingCommand.class))).thenReturn(new Answer(null, false, "cleanup pending"));
        ThirdPartyBackupVolumeService.ArtifactCleanup cleanup = mock(ThirdPartyBackupVolumeService.ArtifactCleanup.class);
        assertThrows(CloudRuntimeException.class, () -> ReflectionTestUtils.invokeMethod(service, "deleteLocked", 1L, cleanup));
        verifyNoInteractions(cleanup);
        assertEquals("WAITING", details.get(ThirdPartyBackupManifest.CLEANUP_STATE_KEY));
    }

    @Test
    public void unresolvedExternalWriterKeepsHistoryEvenIfCleanupWasMarkedCompleted() {
        ThirdPartyBackupRestore.Operation operation = attempt("attempt-1", 4L, "COMPLETED");
        operation.plan.destination = "/stage/ablestack-commvault/restore/attempt-1";
        ThirdPartyBackupManifest.Artifact artifact = new ThirdPartyBackupManifest.Artifact();
        artifact.path = "/backup/vm/timestamp";
        artifact.backupUuid = "backup-1";
        operation.plan.manifest.setMetadata(artifact);
        details.put(ThirdPartyBackupRestore.operationKey(operation.plan.jobId), gson.toJson(operation));
        ThirdPartyBackupRestore.Record record = new ThirdPartyBackupRestore.Record();
        record.request = ReflectionTestUtils.invokeMethod(service, "restoreRequest", operation.plan, 0, -1, -1, true, artifact);
        record.result = new ThirdPartyBackupRestore.Result();
        record.result.sequence = 0;
        record.result.submissionPending = true;
        details.put(ThirdPartyBackupRestore.recordKey(operation.plan.jobId, 0), gson.toJson(record));
        CloudRuntimeException failure = assertThrows(CloudRuntimeException.class, this::retire);
        assertTrue(failure.getMessage(), failure.getMessage().contains("writer termination is unconfirmed"));
        verifyNoInteractions(agents);
    }
}
