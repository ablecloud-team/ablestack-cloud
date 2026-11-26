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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.Command;
import com.cloud.host.Host;
import com.cloud.storage.Volume;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.VMInstanceDao;
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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ThirdPartyBackupRestoreProtocolTest {
    private static final String PROTOCOL = "netbackup-primary-rest-v12";
    private final Gson gson = new Gson();
    private final Map<String, String> details = new HashMap<>();
    private final ThirdPartyBackupVolumeServiceImpl service = spy(new ThirdPartyBackupVolumeServiceImpl());
    private final ThirdPartyBackupRestore.Plan plan = new ThirdPartyBackupRestore.Plan();
    private final BackupVO backup = mock(BackupVO.class);
    private final Host host = mock(Host.class);
    private final AgentManager agents = mock(AgentManager.class);
    private final ThirdPartyBackupVolumeService.RestoreTransfer transfer = mock(ThirdPartyBackupVolumeService.RestoreTransfer.class);
    private MockedStatic<GlobalLock> locks;
    private MockedStatic<Transaction> transactions;
    private ThirdPartyBackupRestore.Request hostRequest;

    @Before
    public void setup() throws Exception {
        GlobalLock lock = mock(GlobalLock.class);
        when(lock.lock(Mockito.anyInt())).thenReturn(true);
        locks = Mockito.mockStatic(GlobalLock.class);
        locks.when(() -> GlobalLock.getInternLock(anyString())).thenReturn(lock);
        transactions = Mockito.mockStatic(Transaction.class);
        transactions.when(() -> Transaction.execute(Mockito.<TransactionCallback<Boolean>>any()))
                .thenAnswer(call -> call.<TransactionCallback<Boolean>>getArgument(0).doInTransaction(null));
        plan.jobId = "restore-protocol-1";
        plan.hostId = 1L;
        plan.hostName = "ablecube1";
        plan.targetVmName = "new-vm";
        plan.destination = "/local/ablestack-netbackup/restore/" + plan.jobId;
        plan.volumeUuids = List.of("volume-1");
        plan.manifest = ThirdPartyBackupManifest.create("ablestack-netbackup", "backup-1", "source-vm", "point", "FULL",
                "ablecube4", "/local/ablestack-netbackup/source-vm/point", "QCOW2",
                List.of(new Backup.VolumeInfo("volume-1", "root.qcow2", Volume.Type.ROOT, 1024L, 0L, null, null, null)), null, null);
        ThirdPartyBackupManifest.Artifact metadata = new ThirdPartyBackupManifest.Artifact();
        metadata.backupUuid = "backup-1";
        metadata.path = "/local/ablestack-netbackup/source-vm/point";
        metadata.sourceHost = "ablecube4";
        plan.manifest.setMetadata(metadata);
        ThirdPartyBackupRestore.Operation operation = new ThirdPartyBackupRestore.Operation();
        operation.plan = plan;
        operation.latestSequence = -1;
        details.put(ThirdPartyBackupRestore.PLAN_KEY, gson.toJson(plan));
        details.put(ThirdPartyBackupRestore.operationKey(plan.jobId), gson.toJson(operation));
        details.put(ThirdPartyBackupManifest.MODE_KEY, ThirdPartyBackupManifest.VOLUME_MODE);
        details.put(AblestackBackupFrameworkUtils.RESTORE_JOB_ID_DETAIL, plan.jobId);
        ThirdPartyBackupAdmission.Entry admission = new ThirdPartyBackupAdmission.Entry();
        admission.jobId = plan.jobId;
        admission.hostId = plan.hostId;
        admission.operation = "RESTORE";
        admission.state = "ADMITTED";
        details.put(ThirdPartyBackupAdmission.RESTORE_KEY, gson.toJson(admission));
        when(backup.getId()).thenReturn(1L);
        when(backup.getVmId()).thenReturn(2L);
        when(backup.getUuid()).thenReturn("backup-1");
        when(backup.getDetail(anyString())).thenAnswer(call -> details.get(call.<String>getArgument(0)));
        when(host.getId()).thenReturn(plan.hostId);
        BackupDao backups = mock(BackupDao.class);
        when(backups.findById(1L)).thenReturn(backup);
        BackupDetailsDao detailDao = mock(BackupDetailsDao.class);
        when(detailDao.findDetail(eq(1L), anyString())).thenAnswer(call -> {
            String key = call.getArgument(1);
            return details.containsKey(key) ? new BackupDetailVO(1L, key, details.get(key), false) : null;
        });
        when(detailDao.listDetails(1L)).thenAnswer(call -> details.entrySet().stream()
                .map(entry -> new BackupDetailVO(1L, entry.getKey(), entry.getValue(), false)).collect(Collectors.toList()));
        doAnswer(call -> { details.put(call.getArgument(1), call.getArgument(2)); return null; })
                .when(detailDao).addDetail(eq(1L), anyString(), anyString(), eq(false));
        doAnswer(call -> { details.remove(call.getArgument(1)); return null; })
                .when(detailDao).removeDetail(eq(1L), anyString());
        VMInstanceVO target = mock(VMInstanceVO.class);
        when(target.getState()).thenReturn(VirtualMachine.State.Stopped);
        VMInstanceDao vms = mock(VMInstanceDao.class);
        when(vms.findByIdIncludingRemoved(2L)).thenReturn(target);
        ReflectionTestUtils.setField(service, "backupDao", backups);
        ReflectionTestUtils.setField(service, "backupDetailsDao", detailDao);
        ReflectionTestUtils.setField(service, "vmDao", vms);
        ReflectionTestUtils.setField(service, "agentManager", agents);
        doReturn(host).when(service).getWorkerHost(backup, "RESTORE");
        hostRequest = ReflectionTestUtils.invokeMethod(service, "restoreRequest", plan, 0, -1, -1, true, metadata);
        when(agents.send(eq(1L), any(Command.class))).thenAnswer(call -> {
            Command command = call.getArgument(1);
            if (command instanceof AblestackRestoreJobStatusCommand) {
                BackupAnswer status = new BackupAnswer(command, true, "Restore job is running");
                status.setState("RUNNING");
                return status;
            }
            if ("RESTORE_STATUS".equals(((AblestackVolumeStagingCommand) command).getAction())) {
                return new Answer(command, true, gson.toJson(hostRequest));
            }
            return new Answer(command, true, "");
        });
        when(transfer.submissionProtocol()).thenReturn(PROTOCOL);
    }

    @After
    public void close() { transactions.close(); locks.close(); }

    private ThirdPartyBackupRestore.Record record() {
        return gson.fromJson(details.get(ThirdPartyBackupRestore.recordKey(plan.jobId, 0)), ThirdPartyBackupRestore.Record.class);
    }

    private void reconcile() { ReflectionTestUtils.invokeMethod(service, "reconcileRestore", 1L, host, plan, transfer); }

    private static org.mockito.ArgumentMatcher<Command> action(String action) {
        return command -> command instanceof AblestackVolumeStagingCommand
                && action.equals(((AblestackVolumeStagingCommand) command).getAction());
    }

    private void retryAcknowledgment() {
        ThirdPartyBackupRestore.Record saved = record();
        saved.acknowledgmentRetryAt = 0;
        details.put(ThirdPartyBackupRestore.recordKey(plan.jobId, 0), gson.toJson(saved));
    }

    private void rejectAcknowledgment() throws Exception {
        when(agents.send(eq(1L), argThat(action("RESTORE_ACK"))))
                .thenAnswer(call -> new Answer(call.getArgument(1), false, "Invalid restore transfer acknowledgment"));
        when(transfer.restore(any(), any())).thenReturn(new ThirdPartyBackupRestore.Result("392", true));
    }

    @Test
    public void protocolAndIntentAreCommittedBeforePostingAndSurvivePolling() {
        when(transfer.restore(any(), any())).thenAnswer(call -> {
            ThirdPartyBackupRestore.Record durable = record();
            assertEquals(PROTOCOL, durable.result.submissionProtocol);
            assertTrue(durable.result.submissionPending);
            assertTrue(durable.result.submittedAt > 0);
            assertEquals("/local/ablestack-netbackup/restore/restore-protocol-1/metadata/point", durable.request.destination);
            assertEquals("ablecube4", durable.request.artifact.sourceHost);
            return new ThirdPartyBackupRestore.Result("392", false);
        });
        reconcile();
        assertEquals("392", record().result.jobId);
        assertEquals(PROTOCOL, record().result.submissionProtocol);
        assertFalse(record().result.submissionPending);
        when(transfer.recover(any(), any())).thenReturn(new ThirdPartyBackupRestore.Result("392", true));
        reconcile();
        assertTrue(record().result.completed);
        assertEquals(PROTOCOL, record().result.submissionProtocol);
        verify(transfer).restore(any(), any());
    }

    @Test
    public void lostResponseRetainsItsProtocolAndOnlyRecoversOnTheNextPass() {
        when(transfer.restore(any(), any())).thenThrow(new CloudRuntimeException("response lost"));
        reconcile();
        assertEquals(PROTOCOL, record().result.submissionProtocol);
        assertTrue(record().result.submissionPending);
        when(transfer.recover(any(), any())).thenThrow(new CloudRuntimeException("Job ID is unconfirmed"));
        reconcile();
        assertEquals(PROTOCOL, record().result.submissionProtocol);
        assertNull(record().result.jobId);
        assertTrue(record().queryError.contains("unconfirmed"));
        verify(transfer).restore(any(), any());
        verify(transfer).recover(any(), any());
    }

    @Test
    public void explicitRejectionRetainsTheProtocolAndStopsTheHostWithoutSubmittingAgain() {
        when(transfer.restore(any(), any())).thenThrow(new ThirdPartyBackupSubmissionException("HTTP 403"));
        reconcile();
        assertTrue(record().result.notSubmitted);
        assertTrue(record().result.externalTerminal);
        assertEquals(PROTOCOL, record().result.submissionProtocol);
        reconcile();
        verify(transfer).restore(any(), any());
        verify(transfer, never()).recover(any(), any());
    }

    @Test
    public void legacyAndOtherProvidersKeepTheirNullProtocol() {
        when(transfer.submissionProtocol()).thenReturn(null);
        when(transfer.restore(any(), any())).thenReturn(new ThirdPartyBackupRestore.Result("391", true));
        reconcile();
        assertEquals("391", record().result.jobId);
        assertNull(record().result.submissionProtocol);
        assertTrue(record().acknowledgedAt > 0);
    }

    @Test public void rejectedAcknowledgmentsAreVisibleAndRetryWithoutResubmittingExternalRestore() throws Exception {
        rejectAcknowledgment();
        reconcile();
        assertTrue(record().result.completed);
        assertTrue(record().acknowledgmentError.contains("Invalid restore transfer acknowledgment"));
        assertTrue(record().acknowledgmentError.startsWith("RESTORE_ACK_REJECTED:"));
        assertEquals(1, record().acknowledgmentFailures);
        reconcile();
        verify(agents).send(eq(1L), argThat(action("RESTORE_ACK")));
        when(agents.send(eq(1L), argThat(action("RESTORE_ACK"))))
                .thenAnswer(call -> new Answer(call.getArgument(1), true, "Receipt accepted"));
        retryAcknowledgment();
        reconcile();
        assertTrue(record().acknowledgedAt > 0);
        assertNull(record().acknowledgmentError);
        assertEquals(0, record().acknowledgmentAbortRequestedAt);
        assertNull(details.get(ThirdPartyBackupRestore.CLEANUP_DETAILS_KEY));
        verify(transfer).restore(any(), any());
        verify(transfer, never()).recover(any(), any());
    }

    @Test public void repeatedRejectionsStopOnlyThePendingSequenceAndRetainAdmissionUntilHostCleanup() throws Exception {
        rejectAcknowledgment();
        when(agents.send(eq(1L), argThat(action(ThirdPartyBackupRestore.ACK_ABORT_ACTION)))).thenAnswer(call -> {
            AblestackVolumeStagingCommand command = call.getArgument(1);
            assertEquals(0, command.getIndex());
            assertTrue(command.getManifest().contains("restore-protocol-1"));
            return new Answer(command, true, "{\"state\":\"STOPPING\"}");
        });
        reconcile();
        retryAcknowledgment();reconcile();
        retryAcknowledgment();reconcile();
        assertTrue(record().acknowledgmentAbortRequestedAt > 0);
        assertTrue(record().acknowledgmentAbortConfirmedAt > 0);
        assertTrue(record().result.completed);
        assertEquals("392", record().result.jobId);
        assertEquals("WAITING", details.get(ThirdPartyBackupRestore.CLEANUP_STATE_KEY));
        assertEquals("ADMITTED", gson.fromJson(details.get(ThirdPartyBackupAdmission.RESTORE_KEY), ThirdPartyBackupAdmission.Entry.class).state);
        var info = new org.apache.cloudstack.api.response.BackupStagingInfoResponse.ArtifactInfo();
        ReflectionTestUtils.invokeMethod(service, "applyRestoreHistory", info, record().request, record(),
                gson.fromJson(details.get(ThirdPartyBackupRestore.operationKey(plan.jobId)), ThirdPartyBackupRestore.Operation.class), null);
        assertEquals("STOPPING", info.acknowledgmentstate);
        BackupAnswer failed = new BackupAnswer(new AblestackRestoreJobStatusCommand(plan.jobId, null, 0), true, "Stopped");
        failed.setState("FAILED");
        ReflectionTestUtils.invokeMethod(service, "recordRestoreHost", backup, plan, failed, null);
        ReflectionTestUtils.invokeMethod(service, "applyRestoreHistory", info, record().request, record(),
                gson.fromJson(details.get(ThirdPartyBackupRestore.operationKey(plan.jobId)), ThirdPartyBackupRestore.Operation.class), null);
        assertEquals("FAILED", info.acknowledgmentstate);
        assertTrue(info.completed);
        verify(agents).send(eq(1L), argThat(action(ThirdPartyBackupRestore.ACK_ABORT_ACTION)));
        verify(agents, never()).send(eq(1L), argThat(action("RESTORE_FAIL")));
        verify(transfer).restore(any(), any());
    }

    @Test public void missingResponsesHaveADurableDeadlineAcrossManagementRestart() throws Exception {
        when(agents.send(eq(1L), argThat(action("RESTORE_ACK"))))
                .thenThrow(new com.cloud.exception.AgentUnavailableException("Connection lost", 1L));
        when(transfer.restore(any(), any())).thenReturn(new ThirdPartyBackupRestore.Result("392", true));
        reconcile();
        ThirdPartyBackupRestore.Record saved = record();
        assertEquals(0, saved.acknowledgmentRejections);
        assertTrue(saved.acknowledgmentError.startsWith("RESTORE_ACK_UNCONFIRMED:"));
        saved.acknowledgmentFirstFailureAt = System.currentTimeMillis() - java.util.concurrent.TimeUnit.MINUTES.toMillis(6);
        saved.acknowledgmentRetryAt = 0;
        details.put(ThirdPartyBackupRestore.recordKey(plan.jobId, 0), gson.toJson(saved));
        ThirdPartyBackupVolumeServiceImpl restarted = spy(new ThirdPartyBackupVolumeServiceImpl());
        for (String name : List.of("backupDao", "backupDetailsDao", "vmDao", "agentManager")) {
            ReflectionTestUtils.setField(restarted, name, ReflectionTestUtils.getField(service, name));
        }
        doReturn(host).when(restarted).getWorkerHost(backup, "RESTORE");
        when(agents.send(eq(1L), argThat(action(ThirdPartyBackupRestore.ACK_ABORT_ACTION))))
                .thenAnswer(call -> new Answer(call.getArgument(1), true, "{\"state\":\"STOPPING\"}"));
        ReflectionTestUtils.invokeMethod(restarted, "reconcileRestore", 1L, host, plan, transfer);
        assertEquals(2, record().acknowledgmentFailures);
        assertEquals(0, record().acknowledgmentRejections);
        assertTrue(record().acknowledgmentAbortConfirmedAt > 0);
        verify(transfer).restore(any(), any());
        verify(transfer, never()).recover(any(), any());
    }

    @Test public void aLateAcknowledgmentReceiptPreventsStoppingAnAlreadyAdvancedWorker() throws Exception {
        rejectAcknowledgment();
        when(agents.send(eq(1L), argThat(action(ThirdPartyBackupRestore.ACK_ABORT_ACTION))))
                .thenAnswer(call -> new Answer(call.getArgument(1), true, "{\"state\":\"ACKNOWLEDGED\"}"));
        reconcile();retryAcknowledgment();reconcile();retryAcknowledgment();reconcile();
        assertTrue(record().acknowledgedAt > 0);
        assertNull(record().acknowledgmentError);
        assertEquals(0, record().acknowledgmentAbortRequestedAt);
        assertTrue(record().result.completed);
        verify(transfer).restore(any(), any());
    }

    @Test public void oldHostsNeverReceiveAnUnfencedFailureOrReleaseUnconfirmedCapacity() throws Exception {
        rejectAcknowledgment();
        when(agents.send(eq(1L), argThat(action(ThirdPartyBackupRestore.ACK_ABORT_ACTION))))
                .thenAnswer(call -> new Answer(call.getArgument(1), false, "Invalid restore transfer acknowledgment"));
        reconcile();retryAcknowledgment();reconcile();retryAcknowledgment();reconcile();retryAcknowledgment();reconcile();
        assertTrue(record().acknowledgmentAbortRequestedAt > 0);
        assertEquals(0, record().acknowledgmentAbortConfirmedAt);
        assertEquals("ADMITTED", gson.fromJson(details.get(ThirdPartyBackupAdmission.RESTORE_KEY), ThirdPartyBackupAdmission.Entry.class).state);
        verify(agents, times(3)).send(eq(1L), argThat(action("RESTORE_ACK")));
        verify(agents, times(2)).send(eq(1L), argThat(action(ThirdPartyBackupRestore.ACK_ABORT_ACTION)));
        verify(agents, never()).send(eq(1L), argThat(action("RESTORE_FAIL")));
        verify(transfer).restore(any(), any());
    }

    @Test public void nextRecordedSequenceConfirmsAnAcknowledgmentWhoseResponseWasLost() throws Exception {
        when(transfer.restore(any(), any())).thenReturn(new ThirdPartyBackupRestore.Result("392", true),
                new ThirdPartyBackupRestore.Result("393", false));
        when(agents.send(eq(1L), argThat(action("RESTORE_ACK")))).thenReturn(null);
        reconcile();
        hostRequest = ReflectionTestUtils.invokeMethod(service, "restoreRequest", plan, 1, 0, 0, false,
                plan.manifest.getVolumes().get(0).chain.get(0));
        reconcile();
        assertTrue(record().acknowledgedAt > 0);
        assertNull(record().acknowledgmentError);
        verify(agents, never()).send(eq(1L), argThat(action(ThirdPartyBackupRestore.ACK_ABORT_ACTION)));
        verify(transfer, times(2)).restore(any(), any());
    }

    @Test public void inspectionSeparatesExternalSuccessFromHostAcknowledgmentFailure() throws Exception {
        rejectAcknowledgment();reconcile();
        var info = new org.apache.cloudstack.api.response.BackupStagingInfoResponse.ArtifactInfo();
        ReflectionTestUtils.invokeMethod(service, "applyRestoreHistory", info, record().request, record(),
                gson.fromJson(details.get(ThirdPartyBackupRestore.operationKey(plan.jobId)), ThirdPartyBackupRestore.Operation.class), null);
        assertTrue(info.completed);
        assertEquals("COMPLETED", info.restorestate);
        assertEquals("RETRYING", info.acknowledgmentstate);
        assertTrue(info.acknowledgmenterror.contains("Invalid restore transfer acknowledgment"));
        assertEquals(Integer.valueOf(1), info.acknowledgmentattempts);
    }

    @Test public void finalWorkerCompletionRecoversTheLastLostResponseAndItsOldWarning() throws Exception {
        rejectAcknowledgment();reconcile();
        BackupAnswer completed = new BackupAnswer(new AblestackRestoreJobStatusCommand(plan.jobId, null, 0), true, "Completed");
        completed.setState("COMPLETED");
        ReflectionTestUtils.invokeMethod(service, "recordRestoreHost", backup, plan, completed, null);
        assertTrue(record().acknowledgedAt > 0);
        assertNull(record().acknowledgmentError);
        assertNull(details.get(ThirdPartyBackupRestore.CLEANUP_DETAILS_KEY));
        assertTrue(record().result.completed);
        verify(transfer).restore(any(), any());
    }

    @Test public void currentProtocolNeedsACommittedVmReceiptBeforeAssumingFinalAcknowledgment() throws Exception {
        rejectAcknowledgment();reconcile();
        plan.vmResultVersion = 1;
        ThirdPartyBackupRestore.Operation operation = gson.fromJson(details.get(ThirdPartyBackupRestore.operationKey(plan.jobId)),
                ThirdPartyBackupRestore.Operation.class);
        operation.plan = plan;
        details.put(ThirdPartyBackupRestore.PLAN_KEY, gson.toJson(plan));
        details.put(ThirdPartyBackupRestore.operationKey(plan.jobId), gson.toJson(operation));
        BackupAnswer completed = new BackupAnswer(new AblestackRestoreJobStatusCommand(plan.jobId, null, 0), true, "Completed");
        completed.setState("COMPLETED");
        ReflectionTestUtils.invokeMethod(service, "recordRestoreHost", backup, plan, completed, null);
        assertEquals(0, record().acknowledgedAt);
        assertTrue(record().acknowledgmentError.contains("REJECTED"));
    }

    @Test public void validatedVmCommitAlsoConfirmsTheLastAcknowledgmentInTheCurrentProtocol() throws Exception {
        rejectAcknowledgment();reconcile();
        plan.vmResultVersion = 1;
        ThirdPartyBackupRestore.Operation operation = gson.fromJson(details.get(ThirdPartyBackupRestore.operationKey(plan.jobId)),
                ThirdPartyBackupRestore.Operation.class);
        operation.plan = plan;
        details.put(ThirdPartyBackupRestore.PLAN_KEY, gson.toJson(plan));
        details.put(ThirdPartyBackupRestore.operationKey(plan.jobId), gson.toJson(operation));
        var result = new ThirdPartyBackupRestore.VmResult();
        result.jobId = plan.jobId;result.sourceBackupUuid = backup.getUuid();result.provider = plan.manifest.getProvider();
        result.targetVmName = plan.targetVmName;result.transactionId = "transaction-1";result.revision = 1;
        result.updatedAt = System.currentTimeMillis();result.phase = "COMMITTED";result.outcome = "COMMITTED";
        result.primaryCleanupState = "NOT_STARTED";result.committedAt = result.updatedAt;
        var volume = new ThirdPartyBackupRestore.VmVolumeResult();
        volume.index = 0;volume.volumeUuid = "volume-1";volume.poolUuid = "pool-1";volume.destination = "/primary/volume-1";
        volume.hadOriginal = true;volume.prepareState = "PREPARED";volume.switchState = "SWITCHED";
        result.volumes = List.of(volume);
        BackupAnswer completed = new BackupAnswer(new AblestackRestoreJobStatusCommand(plan.jobId, null, 0), true, "Completed");
        completed.setState("COMPLETED");completed.setVmRestoreResult(gson.toJson(result));
        ReflectionTestUtils.invokeMethod(service, "recordRestoreHost", backup, plan, completed, null);
        assertTrue(record().acknowledgedAt > 0);
        assertNull(record().acknowledgmentError);
        assertNull(details.get(ThirdPartyBackupRestore.CLEANUP_DETAILS_KEY));
        assertTrue(record().result.completed);
    }
}
