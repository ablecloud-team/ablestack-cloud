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
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.Command;
import com.cloud.host.Host;
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
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class ThirdPartyBackupDeletedRestoreTargetTest {
    private final Map<String, String> details = new HashMap<>();
    private final ThirdPartyBackupVolumeServiceImpl service = Mockito.spy(new ThirdPartyBackupVolumeServiceImpl());
    private final BackupVO backup = mock(BackupVO.class);
    private final VMInstanceVO target = mock(VMInstanceVO.class);
    private final VMInstanceDao vms = mock(VMInstanceDao.class);
    private final AgentManager agents = mock(AgentManager.class);
    private final Host host = mock(Host.class);
    private final ThirdPartyBackupVolumeService.RestoreTransfer transfer = mock(ThirdPartyBackupVolumeService.RestoreTransfer.class);
    private final ThirdPartyBackupRestore.Plan plan = new ThirdPartyBackupRestore.Plan();
    private MockedStatic<GlobalLock> locks;
    private MockedStatic<Transaction> transactions;

    @Before
    public void setup() throws Exception {
        GlobalLock lock = mock(GlobalLock.class);
        when(lock.lock(1)).thenReturn(true);
        when(lock.lock(5)).thenReturn(true);
        locks = Mockito.mockStatic(GlobalLock.class);
        locks.when(() -> GlobalLock.getInternLock(anyString())).thenReturn(lock);
        transactions = Mockito.mockStatic(Transaction.class);
        transactions.when(() -> Transaction.execute(Mockito.<TransactionCallback<Boolean>>any()))
                .thenAnswer(call -> call.<TransactionCallback<Boolean>>getArgument(0).doInTransaction(null));
        plan.jobId = "restore-deleted-target";
        plan.hostId = 4L;
        plan.targetVmName = "new-restore-target";
        plan.manifest = new ThirdPartyBackupManifest();
        plan.manifest.setBackupUuid("source-backup");
        plan.manifest.setProvider("ablestack-commvault");
        ThirdPartyBackupRestore.Operation operation = new ThirdPartyBackupRestore.Operation();
        operation.plan = plan;
        operation.startState = "PREPARED";
        details.put(ThirdPartyBackupRestore.operationKey(plan.jobId), new Gson().toJson(operation));
        details.put(ThirdPartyBackupRestore.PLAN_KEY, new Gson().toJson(plan));
        details.put(ThirdPartyBackupManifest.MODE_KEY, ThirdPartyBackupManifest.VOLUME_MODE);
        details.put(AblestackBackupFrameworkUtils.RESTORE_JOB_ID_DETAIL, plan.jobId);
        details.put(AblestackBackupFrameworkUtils.RESTORE_TARGET_VM_ID_DETAIL, "2");
        when(backup.getId()).thenReturn(1L);
        when(backup.getVmId()).thenReturn(100L);
        when(backup.getUuid()).thenReturn("source-backup");
        when(backup.getDetail(anyString())).thenAnswer(call -> details.get(call.<String>getArgument(0)));
        when(host.getId()).thenReturn(4L);
        when(target.getState()).thenReturn(VirtualMachine.State.Stopped);
        when(vms.findByIdIncludingRemoved(2L)).thenReturn(target);
        BackupDao backups = mock(BackupDao.class);
        when(backups.findById(1L)).thenReturn(backup);
        BackupDetailsDao detailDao = mock(BackupDetailsDao.class);
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
        ReflectionTestUtils.setField(service, "backupDao", backups);
        ReflectionTestUtils.setField(service, "backupDetailsDao", detailDao);
        ReflectionTestUtils.setField(service, "vmDao", vms);
        ReflectionTestUtils.setField(service, "agentManager", agents);
        doReturn(host).when(service).getWorkerHost(backup, "RESTORE");
        BackupAnswer running = new BackupAnswer(null, true, "Restore job is running");
        running.setState("RUNNING");
        when(agents.send(eq(4L), any(AblestackRestoreJobStatusCommand.class))).thenReturn(running);
        when(agents.send(eq(4L), any(AblestackVolumeStagingCommand.class))).thenReturn(new Answer(null, true, ""));
    }

    @After
    public void close() {
        transactions.close();
        locks.close();
    }

    @Test
    public void removedTargetCancelsHostWithoutSubmittingOrAcknowledgingTransfers() throws Exception {
        when(target.getRemoved()).thenReturn(new Date());
        reconcile();
        verifyCancellation();
    }

    @Test
    public void missingTargetCancelsHostWithoutSubmittingTransfers() throws Exception {
        when(vms.findByIdIncludingRemoved(2L)).thenReturn(null);
        reconcile();
        verifyCancellation();
    }

    @Test
    public void destroyedTargetCannotDispatchPreparedRestore() {
        when(target.getState()).thenReturn(VirtualMachine.State.Destroyed);
        assertThrows(CloudRuntimeException.class, () -> service.markRestoreDispatch(backup, plan));
        ThirdPartyBackupRestore.Operation saved = new Gson().fromJson(details.get(ThirdPartyBackupRestore.operationKey(plan.jobId)),
                ThirdPartyBackupRestore.Operation.class);
        assertEquals("PREPARED", saved.startState);
        verifyNoInteractions(agents, transfer);
    }

    @Test
    public void existingTargetCanRestoreFromBackupOfDeletedSource() throws Exception {
        reconcile();
        verify(vms).findByIdIncludingRemoved(2L);
        verify(vms, never()).findByIdIncludingRemoved(100L);
        verify(agents, never()).send(eq(4L), Mockito.<Command>argThat(command -> command instanceof AblestackVolumeStagingCommand
                && "RESTORE_CANCEL".equals(((AblestackVolumeStagingCommand) command).getAction())));
    }

    private void reconcile() {
        ReflectionTestUtils.invokeMethod(service, "reconcileRestore", 1L, host, plan, transfer);
    }

    private void verifyCancellation() throws Exception {
        assertEquals("FAILED", details.get(AblestackBackupFrameworkUtils.RESTORE_JOB_STATE_DETAIL));
        assertEquals("TARGET_REMOVED", details.get(AblestackBackupFrameworkUtils.RESTORE_JOB_STEP_DETAIL));
        assertEquals("WAITING", details.get(ThirdPartyBackupRestore.CLEANUP_STATE_KEY));
        verify(agents).send(eq(4L), Mockito.<Command>argThat(command -> command instanceof AblestackVolumeStagingCommand
                && "RESTORE_CANCEL".equals(((AblestackVolumeStagingCommand) command).getAction())));
        verify(agents, never()).send(eq(4L), Mockito.<Command>argThat(command -> command instanceof AblestackVolumeStagingCommand
                && "RESTORE_ACK".equals(((AblestackVolumeStagingCommand) command).getAction())));
        verifyNoInteractions(transfer);
    }
}
