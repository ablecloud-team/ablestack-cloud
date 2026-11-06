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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
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
        VMInstanceVO target = mock(VMInstanceVO.class);
        when(target.getState()).thenReturn(VirtualMachine.State.Stopped);
        VMInstanceDao vms = mock(VMInstanceDao.class);
        when(vms.findByIdIncludingRemoved(2L)).thenReturn(target);
        ReflectionTestUtils.setField(service, "backupDao", backups);
        ReflectionTestUtils.setField(service, "backupDetailsDao", detailDao);
        ReflectionTestUtils.setField(service, "vmDao", vms);
        ReflectionTestUtils.setField(service, "agentManager", agents);
        doReturn(host).when(service).getWorkerHost(backup, "RESTORE");
        ThirdPartyBackupRestore.Request request = ReflectionTestUtils.invokeMethod(service, "restoreRequest", plan, 0, -1, -1, true, metadata);
        when(agents.send(eq(1L), any(Command.class))).thenAnswer(call -> {
            Command command = call.getArgument(1);
            if (command instanceof AblestackRestoreJobStatusCommand) {
                BackupAnswer status = new BackupAnswer(command, true, "Restore job is running");
                status.setState("RUNNING");
                return status;
            }
            if ("RESTORE_STATUS".equals(((AblestackVolumeStagingCommand) command).getAction())) {
                return new Answer(command, true, gson.toJson(request));
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
        when(transfer.restore(any(), any())).thenReturn(new ThirdPartyBackupRestore.Result("391", false));
        reconcile();
        assertEquals("391", record().result.jobId);
        assertNull(record().result.submissionProtocol);
    }
}
