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
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.cloudstack.backup.dao.BackupDao;
import org.apache.cloudstack.backup.dao.BackupDetailsDao;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.Command;
import com.cloud.agent.manager.Commands;
import com.cloud.host.Host;
import com.cloud.storage.Volume;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.Gson;

public class ThirdPartyBackupStartDispatchTest {
    private final Gson gson = new Gson();
    private final Map<String, String> details = new HashMap<>();
    private final AtomicBoolean coordinatorHeld = new AtomicBoolean();
    private final ThirdPartyBackupVolumeServiceImpl service = spy(new ThirdPartyBackupVolumeServiceImpl());
    private final BackupVO backup = mock(BackupVO.class);
    private final BackupDao backups = mock(BackupDao.class);
    private final BackupDetailsDao detailDao = mock(BackupDetailsDao.class);
    private final Host host = mock(Host.class);
    private final AgentManager agents = mock(AgentManager.class);
    private final ThirdPartyBackupVolumeService.Transfer transfer = mock(ThirdPartyBackupVolumeService.Transfer.class);
    private MockedStatic<GlobalLock> locks;
    private MockedStatic<Transaction> transactions;
    private GlobalLock coordinator;

    @Before
    public void setup() throws Exception {
        coordinator = mock(GlobalLock.class);
        when(coordinator.lock(anyInt())).thenAnswer(call -> { assertFalse(coordinatorHeld.getAndSet(true)); return true; });
        doAnswer(call -> { assertTrue(coordinatorHeld.getAndSet(false)); return null; }).when(coordinator).unlock();
        GlobalLock admission = mock(GlobalLock.class);
        when(admission.lock(anyInt())).thenReturn(true);
        locks = Mockito.mockStatic(GlobalLock.class);
        locks.when(() -> GlobalLock.getInternLock(anyString())).thenReturn(admission);
        locks.when(() -> GlobalLock.getInternLock("backup.volume.backup-1")).thenReturn(coordinator);
        transactions = Mockito.mockStatic(Transaction.class);
        transactions.when(() -> Transaction.execute(Mockito.<TransactionCallback<Boolean>>any()))
                .thenAnswer(call -> call.<TransactionCallback<Boolean>>getArgument(0).doInTransaction(null));
        when(host.getId()).thenReturn(4L);
        when(backup.getId()).thenReturn(1L);
        when(backup.getHostId()).thenReturn(4L);
        when(backup.getUuid()).thenReturn("backup-1");
        when(backup.getStatus()).thenReturn(Backup.Status.BackingUp);
        when(backup.getDetails()).thenReturn(details);
        when(backup.getDetail(anyString())).thenAnswer(call -> details.get(call.getArgument(0)));
        when(backups.findById(1L)).thenReturn(backup);
        when(detailDao.findDetail(eq(1L), anyString())).thenAnswer(call -> {
            String key = call.getArgument(1);
            return details.containsKey(key) ? new BackupDetailVO(1L, key, details.get(key), false) : null;
        });
        doAnswer(call -> { details.put(call.getArgument(1), call.getArgument(2)); return null; })
                .when(detailDao).addDetail(eq(1L), anyString(), anyString(), eq(false));
        ReflectionTestUtils.setField(service, "agentManager", agents);
        ReflectionTestUtils.setField(service, "backupDao", backups);
        ReflectionTestUtils.setField(service, "backupDetailsDao", detailDao);
        doReturn(host).when(service).getWorkerHost(backup, "BACKUP");
        doNothing().when(service).track(backup, host, transfer);
        when(agents.send(eq(4L), any(Command.class))).thenAnswer(call -> {
            AblestackVolumeStagingCommand command = call.getArgument(1);
            assertEquals("BACKUP_START_PREPARE", command.getAction());
            assertEquals(15, command.getWait());
            ThirdPartyBackupStart.Receipt receipt = new ThirdPartyBackupStart.Receipt();
            receipt.plan = gson.fromJson(command.getManifest(), ThirdPartyBackupStart.Plan.class);
            receipt.state = "PREPARED";
            receipt.checkedAt = System.currentTimeMillis();
            return new Answer(command, true, gson.toJson(receipt));
        });
    }

    @After
    public void close() {
        transactions.close();
        locks.close();
    }

    private Command prepare(String provider) {
        ThirdPartyBackupStart.Operation start = new ThirdPartyBackupStart.Operation();
        start.plan = new ThirdPartyBackupStart.Plan();
        start.plan.jobId = "backup-1";
        start.plan.hostId = 4L;
        start.plan.backupPath = "/local/ablestack-" + provider + "/vm/point";
        start.plan.manifest = ThirdPartyBackupManifest.create("ablestack-" + provider, "backup-1", "vm", "point", "FULL",
                "host-4", start.plan.backupPath, "QCOW2",
                List.of(new Backup.VolumeInfo("volume-1", "root.qcow2", Volume.Type.ROOT, 1024L, 0L, null, null, null)), null, null);
        start.state = "PREPARING";
        start.createdAt = System.currentTimeMillis();
        details.put(ThirdPartyBackupStart.DETAIL_KEY, gson.toJson(start));
        details.put(ThirdPartyBackupManifest.MODE_KEY, ThirdPartyBackupManifest.VOLUME_MODE);
        details.put(ThirdPartyBackupManifest.DETAIL_KEY, start.plan.manifest.toJson());
        ThirdPartyBackupAdmission.Entry entry = new ThirdPartyBackupAdmission.Entry();
        entry.jobId = "backup-1";
        entry.hostId = 4L;
        entry.operation = "BACKUP";
        entry.state = "WAITING";
        details.put(ThirdPartyBackupAdmission.BACKUP_KEY, gson.toJson(entry));
        Command command = "commvault".equals(provider) ? new AblestackCommvaultTakeBackupCommand("vm", start.plan.backupPath)
                : "veeam".equals(provider) ? new AblestackVeeamTakeBackupCommand("vm", start.plan.backupPath)
                : new AblestackNetBackupTakeBackupCommand("vm", start.plan.backupPath);
        command.setWait(43200);
        return command;
    }

    @Test
    public void allThreeProvidersReleaseTheCoordinatorAndKeepTheEngineTimeoutDuringDispatch() throws Exception {
        for (String provider : new String[]{"commvault", "netbackup", "veeam"}) {
            Command command = prepare(provider);
            doAnswer(call -> {
                assertFalse(coordinatorHeld.get());
                assertEquals("SUBMISSION_PENDING", gson.fromJson(details.get(ThirdPartyBackupStart.DETAIL_KEY), ThirdPartyBackupStart.Operation.class).state);
                assertSame(command, call.<Commands>getArgument(1).toCommands()[0]);
                assertEquals(43200, command.getWait());
                return new Answer[]{new Answer(command, true, "dispatched")};
            }).when(agents).send(eq(4L), any(Commands.class), eq(15));
            service.dispatchBackup(backup, host, command, transfer);
            assertFalse(coordinatorHeld.get());
        }
        verify(service, times(3)).track(backup, host, transfer);
    }

    @Test
    public void cancellationCanBeRecordedWhileDispatchIsUnconfirmedAndALateFailurePreservesIt() throws Exception {
        Command command = prepare("netbackup");
        when(agents.send(eq(4L), any(Commands.class), eq(15))).thenAnswer(call -> {
            assertFalse(coordinatorHeld.get());
            assertTrue(coordinator.lock(5));
            try { assertTrue(service.cancelWaiting(backup, "BACKUP", "backup-1")); }
            finally { coordinator.unlock(); }
            throw new CloudRuntimeException("Host start response timed out");
        });
        service.dispatchBackup(backup, host, command, transfer);
        ThirdPartyBackupStart.Operation start = gson.fromJson(details.get(ThirdPartyBackupStart.DETAIL_KEY), ThirdPartyBackupStart.Operation.class);
        assertTrue(start.cancelRequestedAt > 0);
        assertEquals(0, start.cancelConfirmedAt);
        assertEquals("SUBMISSION_PENDING", start.state);
        assertEquals("CANCEL_PENDING", gson.fromJson(details.get(ThirdPartyBackupAdmission.BACKUP_KEY), ThirdPartyBackupAdmission.Entry.class).state);
        assertNull(details.get(ThirdPartyBackupManifest.CLEANUP_STATE_KEY));
        verify(backup, never()).setStatus(any());
        service.dispatchBackup(backup, host, command, transfer);
        verify(agents, times(1)).send(eq(4L), any(Commands.class), eq(15));
    }

    @Test
    public void aLateNegativeStartResponseCannotOverwriteAConfirmedStartFence() throws Exception {
        Command command = prepare("netbackup");
        when(agents.send(eq(4L), any(Commands.class), eq(15))).thenAnswer(call -> {
            ThirdPartyBackupStart.Operation start = gson.fromJson(details.get(ThirdPartyBackupStart.DETAIL_KEY), ThirdPartyBackupStart.Operation.class);
            start.state = "START_FAILED";
            start.reason = "Host fenced this attempt";
            details.put(ThirdPartyBackupStart.DETAIL_KEY, gson.toJson(start));
            return new Answer[]{new Answer(command, false, "late failed start")};
        });
        service.dispatchBackup(backup, host, command, transfer);
        ThirdPartyBackupStart.Operation start = gson.fromJson(details.get(ThirdPartyBackupStart.DETAIL_KEY), ThirdPartyBackupStart.Operation.class);
        assertEquals("START_FAILED", start.state);
        assertEquals("Host fenced this attempt", start.reason);
    }
}
