// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package org.apache.cloudstack.backup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.cloudstack.api.command.user.backup.CreateNetBackupCmd;
import org.apache.cloudstack.backup.dao.BackupDao;
import org.apache.cloudstack.backup.dao.BackupOfferingDao;
import org.apache.cloudstack.backup.dao.BackupScheduleDao;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.impl.ConfigDepotImpl;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;
import com.cloud.event.ActionEventUtils;
import com.cloud.resourcelimit.CheckedReservation;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.User;
import com.cloud.utils.Pair;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.VMInstanceDao;

public class NetBackupPreparationTest {
    private static final long VM_ID = 1;
    private final BackupProvider provider = mock(BackupProvider.class);
    private final BackupDao backups = mock(BackupDao.class);
    private final BackupScheduleDao schedules = mock(BackupScheduleDao.class);
    private final VMInstanceVO vm = mock(VMInstanceVO.class);
    private final ConfigDepotImpl depot = mock(ConfigDepotImpl.class);
    private final BackupScheduleVO schedule = new BackupScheduleVO();
    private final BackupVO accepted = new BackupVO();
    private final CreateNetBackupCmd request = spy(new CreateNetBackupCmd());
    private final BackupVolumeGuard volumeGuard = mock(BackupVolumeGuard.class);
    private final BackupSnapshotGuard snapshotGuard = mock(BackupSnapshotGuard.class);
    private final BackupVolumeGuard.Lease volumeLease = mock(BackupVolumeGuard.Lease.class);
    private final BackupSnapshotGuard.Lease snapshotLease = mock(BackupSnapshotGuard.Lease.class);
    private WaitingManager manager;
    private ConfigDepotImpl previous;
    private MockedConstruction<CheckedReservation> reservations;
    private MockedStatic<ActionEventUtils> events;
    private MockedStatic<Transaction> transactions;

    private static class WaitingManager extends BackupManagerImpl {
        long now = System.currentTimeMillis();
        int waits;
        boolean volumeHeld, snapshotHeld, interruptWait;
        Runnable duringWait;
        @Override protected long backupPreparationTime() { return now; }
        @Override protected void awaitBackupPreparation(long millis) {
            assertFalse("Volume lock must be released before preparation waits", volumeHeld);
            assertFalse("Snapshot lock must be released before preparation waits", snapshotHeld);
            waits++;
            if (interruptWait) {
                Thread.currentThread().interrupt();
                super.awaitBackupPreparation(millis);
            }
            now += millis;
            if (duringWait != null) { duringWait.run(); }
        }
    }

    @Before public void setup() throws Exception {
        previous = (ConfigDepotImpl) ReflectionTestUtils.getField(BackupManager.NetBackupHookTimeout, "s_depot");
        ConfigKey.init(depot);
        when(depot.getConfigStringValue(eq(BackupManager.NetBackupHookTimeout.key()), eq(ConfigKey.Scope.Global), isNull())).thenReturn("86400");
        when(depot.getConfigStringValue(eq(BackupManager.NetBackupVmScheduleEnabled.key()), eq(ConfigKey.Scope.Global), isNull())).thenReturn("true");
        manager = spy(new WaitingManager());
        ReflectionTestUtils.setField(manager, "backupVolumeGuard", volumeGuard);
        ReflectionTestUtils.setField(manager, "backupSnapshotGuard", snapshotGuard);
        ReflectionTestUtils.setField(manager, "netBackupScheduleModeGuard", mock(NetBackupScheduleModeGuard.class));
        ReflectionTestUtils.setField(manager, "ablestackBackupOperationGuard", mock(AblestackBackupOperationGuard.class));
        ReflectionTestUtils.setField(manager, "volumeDao", mock(VolumeDao.class));
        ReflectionTestUtils.setField(manager, "backupDao", backups);
        ReflectionTestUtils.setField(manager, "backupScheduleDao", schedules);
        ReflectionTestUtils.setField(manager, "thirdPartyBackupVolumeService", mock(ThirdPartyBackupVolumeService.class));
        VMInstanceDao vms = mock(VMInstanceDao.class);
        when(vms.findById(VM_ID)).thenReturn(vm);
        ReflectionTestUtils.setField(manager, "vmInstanceDao", vms);
        when(vm.getId()).thenReturn(VM_ID);
        when(vm.getUuid()).thenReturn("vm-uuid");
        when(vm.getInstanceName()).thenReturn("i-2-1-VM");
        when(vm.getState()).thenReturn(VirtualMachine.State.Running);
        when(vm.getType()).thenReturn(VirtualMachine.Type.User);
        when(vm.getHostId()).thenReturn(14L);
        when(vm.getDataCenterId()).thenReturn(1L);
        when(vm.getBackupOfferingId()).thenReturn(2L);
        when(provider.getName()).thenReturn("ablestack-netbackup");
        ReflectionTestUtils.setField(manager, "backupProvidersMap", Map.of("ablestack-netbackup", provider));
        BackupOfferingVO offering = mock(BackupOfferingVO.class);
        when(offering.getProvider()).thenReturn("ablestack-netbackup");
        when(offering.getExternalId()).thenReturn("netbackup");
        BackupOfferingDao offerings = mock(BackupOfferingDao.class);
        when(offerings.findById(2L)).thenReturn(offering);
        ReflectionTestUtils.setField(manager, "backupOfferingDao", offerings);
        ReflectionTestUtils.setField(schedule, "id", 5L);
        ReflectionTestUtils.setField(schedule, "vmId", VM_ID);
        schedule.setScheduledTimestamp(new Date(1));
        schedule.setScheduleType((short) 0);
        schedule.setSchedule("30");
        schedule.setTimezone("UTC");
        schedule.setQuiesceVM(true);
        when(schedules.listAll()).thenReturn(List.of(schedule));
        when(schedules.findById(5L)).thenReturn(schedule);
        ReflectionTestUtils.setField(accepted, "id", 40L);
        accepted.setStatus(Backup.Status.BackingUp);
        accepted.setDetails(new HashMap<>());
        accepted.getDetails().put(ThirdPartyBackupManifest.MODE_KEY, ThirdPartyBackupManifest.VOLUME_MODE);
        when(backups.findById(40L)).thenReturn(accepted);
        Account owner = mock(Account.class);
        AccountManager accounts = mock(AccountManager.class);
        when(accounts.getAccount(anyLong())).thenReturn(owner);
        ReflectionTestUtils.setField(manager, "accountManager", accounts);
        doNothing().when(manager).validateBackupForZone(anyLong());
        doNothing().when(manager).checkNoActiveFastCloneFlattenForBackup(VM_ID);
        doNothing().when(manager).deleteOldestBackupFromScheduleIfRequired(VM_ID, 5L);
        when(volumeGuard.acquire(VM_ID)).thenAnswer(call -> { manager.volumeHeld = true; return volumeLease; });
        when(snapshotGuard.acquire(VM_ID)).thenAnswer(call -> { manager.snapshotHeld = true; return snapshotLease; });
        doAnswer(call -> { manager.volumeHeld = false; return null; }).when(volumeLease).close();
        doAnswer(call -> { manager.snapshotHeld = false; return null; }).when(snapshotLease).close();
        ReflectionTestUtils.setField(request, "vmId", VM_ID);
        ReflectionTestUtils.setField(request, "scheduleId", 5L);
        ReflectionTestUtils.setField(request, "hostId", 14L);
        ReflectionTestUtils.setField(request, "backupOfferingId", 2L);
        ReflectionTestUtils.setField(request, "selectionMode", "SCHEDULE");
        ReflectionTestUtils.setField(request, "policyId", "parent-policy");
        CallContext.register(mock(User.class), owner);
        reservations = mockConstruction(CheckedReservation.class);
        events = mockStatic(ActionEventUtils.class);
        transactions = mockStatic(Transaction.class);
        transactions.when(() -> Transaction.execute(any(TransactionCallback.class)))
                .thenAnswer(call -> ((TransactionCallback<?>) call.getArgument(0)).doInTransaction(null));
    }

    @After public void cleanup() {
        if (transactions != null) { transactions.close(); }
        if (events != null) { events.close(); }
        if (reservations != null) { reservations.close(); }
        CallContext.unregister();
        ConfigKey.init(previous);
        Thread.interrupted();
    }

    @Test public void cleanupContentionWaitsWithoutVmLocksAndAcceptsExactlyOnce() throws Exception {
        when(provider.takeNetBackup(eq(vm), eq("parent-policy"), eq(5L), eq(true), anyLong()))
                .thenThrow(new BackupPreparation.Busy(VM_ID)).thenReturn(new Pair<>(true, accepted));
        assertTrue(manager.createNetBackup(request));
        assertEquals(1, manager.waits);
        verify(request).setCreatedBackupId(accepted.getUuid());
        verify(schedules, times(1)).update(eq(5L), any(BackupScheduleVO.class));
        verify(provider, times(2)).takeNetBackup(eq(vm), eq("parent-policy"), eq(5L), eq(true), anyLong());
        assertTrue(schedule.getScheduledTimestamp().after(new Date()));
    }

    @Test public void aBusyRequestExpiresWithoutConsumingTheSchedule() throws Exception {
        ReflectionTestUtils.setField(request, "preparationDeadline", manager.now + 11000);
        when(provider.takeNetBackup(eq(vm), anyString(), eq(5L), any(), anyLong()))
                .thenThrow(new BackupPreparation.Busy(VM_ID));
        CloudRuntimeException error = assertThrows(CloudRuntimeException.class, () -> manager.createNetBackup(request));
        assertTrue(error.getMessage().contains("BACKUP_PREPARATION_TIMEOUT"));
        assertEquals(3, manager.waits);
        assertEquals(1, schedule.getScheduledTimestamp().getTime());
        verify(schedules, never()).update(anyLong(), any());
        verify(request, never()).setCreatedBackupId(any());
    }

    @Test public void recordedCleanupWaitsBeforeCallingTheProvider() throws Exception {
        BackupVO previousBackup = new BackupVO();
        previousBackup.setDetails(new HashMap<>());
        previousBackup.getDetails().put(AblestackBackupFrameworkUtils.BACKUP_CLEANUP_STATE_DETAIL, "WAITING");
        when(backups.listByVmId(null, VM_ID)).thenReturn(List.of(previousBackup));
        manager.duringWait = () -> previousBackup.getDetails().put(AblestackBackupFrameworkUtils.BACKUP_CLEANUP_STATE_DETAIL, "COMPLETED");
        when(provider.takeNetBackup(eq(vm), anyString(), eq(5L), any(), anyLong())).thenReturn(new Pair<>(true, accepted));
        assertTrue(manager.createNetBackup(request));
        assertEquals(1, manager.waits);
        verify(provider, times(1)).takeNetBackup(eq(vm), anyString(), eq(5L), any(), anyLong());
        verify(schedules, times(1)).update(eq(5L), any());
    }

    @Test public void aChangedHostIsRevalidatedAfterWaiting() throws Exception {
        when(provider.takeNetBackup(eq(vm), anyString(), eq(5L), any(), anyLong())).thenThrow(new BackupPreparation.Busy(VM_ID));
        manager.duringWait = () -> when(vm.getHostId()).thenReturn(99L);
        assertThrows(CloudRuntimeException.class, () -> manager.createNetBackup(request));
        assertEquals(1, manager.waits);
        verify(provider, times(1)).takeNetBackup(eq(vm), anyString(), eq(5L), any(), anyLong());
        verify(schedules, never()).update(anyLong(), any());
    }

    @Test public void aChangedModeIsRevalidatedAfterWaiting() throws Exception {
        when(provider.takeNetBackup(eq(vm), anyString(), eq(5L), any(), anyLong())).thenThrow(new BackupPreparation.Busy(VM_ID));
        manager.duringWait = () -> when(depot.getConfigStringValue(eq(BackupManager.NetBackupVmScheduleEnabled.key()), eq(ConfigKey.Scope.Global), isNull())).thenReturn("false");
        assertThrows(CloudRuntimeException.class, () -> manager.createNetBackup(request));
        assertEquals(1, manager.waits);
        verify(provider, times(1)).takeNetBackup(eq(vm), anyString(), eq(5L), any(), anyLong());
    }

    @Test public void anAlreadyAdvancedScheduleIsNotConsumedAgainAfterWaiting() throws Exception {
        when(provider.takeNetBackup(eq(vm), anyString(), eq(5L), any(), anyLong())).thenThrow(new BackupPreparation.Busy(VM_ID));
        manager.duringWait = () -> schedule.setScheduledTimestamp(new Date(System.currentTimeMillis() + 3600000));
        assertThrows(CloudRuntimeException.class, () -> manager.createNetBackup(request));
        assertEquals(1, manager.waits);
        verify(provider, times(1)).takeNetBackup(eq(vm), anyString(), eq(5L), any(), anyLong());
        verify(schedules, never()).update(anyLong(), any());
    }

    @Test public void aChangedOfferingIsRevalidatedAfterWaiting() throws Exception {
        when(provider.takeNetBackup(eq(vm), anyString(), eq(5L), any(), anyLong())).thenThrow(new BackupPreparation.Busy(VM_ID));
        manager.duringWait = () -> when(vm.getBackupOfferingId()).thenReturn(99L);
        assertThrows(CloudRuntimeException.class, () -> manager.createNetBackup(request));
        verify(provider, times(1)).takeNetBackup(eq(vm), anyString(), eq(5L), any(), anyLong());
        verify(schedules, never()).update(anyLong(), any());
    }

    @Test public void contentionForAnotherVmIsNotRetried() throws Exception {
        when(provider.takeNetBackup(eq(vm), anyString(), eq(5L), any(), anyLong())).thenThrow(new BackupPreparation.Busy(99));
        assertThrows(BackupPreparation.Busy.class, () -> manager.createNetBackup(request));
        assertEquals(0, manager.waits);
        verify(provider, times(1)).takeNetBackup(eq(vm), anyString(), eq(5L), any(), anyLong());
    }

    @Test public void unknownFailuresAreNotResubmitted() throws Exception {
        when(provider.takeNetBackup(eq(vm), anyString(), eq(5L), any(), anyLong())).thenThrow(new CloudRuntimeException("Host result unknown"));
        assertThrows(CloudRuntimeException.class, () -> manager.createNetBackup(request));
        assertEquals(0, manager.waits);
        verify(provider, times(1)).takeNetBackup(eq(vm), anyString(), eq(5L), any(), anyLong());
    }

    @Test public void anAcceptedUuidPreventsAnyFurtherCreationAttempt() throws Exception {
        when(provider.takeNetBackup(eq(vm), anyString(), eq(5L), any(), anyLong())).thenAnswer(call -> {
            request.setCreatedBackupId("existing-backup");
            throw new BackupPreparation.Busy(VM_ID);
        });
        assertThrows(BackupPreparation.Busy.class, () -> manager.createNetBackup(request));
        assertEquals(0, manager.waits);
        verify(provider, times(1)).takeNetBackup(eq(vm), anyString(), eq(5L), any(), anyLong());
    }

    @Test public void anExpiredCallerDoesNotReachTheProvider() throws Exception {
        ReflectionTestUtils.setField(request, "preparationDeadline", manager.now - 1);
        assertThrows(CloudRuntimeException.class, () -> manager.createNetBackup(request));
        verifyNoInteractions(volumeGuard, snapshotGuard);
        verify(provider, never()).takeNetBackup(any(), any(), any(), any(), anyLong());
    }

    @Test public void configurationModeAlsoWaitsWithinTheSameRequest() throws Exception {
        ReflectionTestUtils.setField(request, "selectionMode", "CONFIG");
        ReflectionTestUtils.setField(request, "scheduleId", null);
        when(depot.getConfigStringValue(eq(BackupManager.NetBackupVmScheduleEnabled.key()), eq(ConfigKey.Scope.Global), isNull())).thenReturn("false");
        when(provider.takeNetBackup(eq(vm), anyString(), isNull(), any(), anyLong()))
                .thenThrow(new BackupPreparation.Busy(VM_ID)).thenReturn(new Pair<>(true, accepted));
        assertTrue(manager.createNetBackup(request));
        assertEquals(1, manager.waits);
        verify(request).setCreatedBackupId(accepted.getUuid());
        verify(schedules, never()).update(anyLong(), any());
    }

    @Test public void interruptionExitsWithoutAReplacementRequest() throws Exception {
        when(provider.takeNetBackup(eq(vm), anyString(), eq(5L), any(), anyLong())).thenThrow(new BackupPreparation.Busy(VM_ID));
        manager.interruptWait = true;
        assertThrows(CloudRuntimeException.class, () -> manager.createNetBackup(request));
        assertTrue(Thread.currentThread().isInterrupted());
        verify(provider, times(1)).takeNetBackup(eq(vm), anyString(), eq(5L), any(), anyLong());
        verify(schedules, never()).update(anyLong(), any());
    }
}
