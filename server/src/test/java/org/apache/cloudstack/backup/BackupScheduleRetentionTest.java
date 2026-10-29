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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.cloudstack.backup.dao.BackupDao;
import org.apache.cloudstack.backup.dao.BackupDetailsDao;
import org.apache.cloudstack.backup.dao.BackupOfferingDao;
import org.apache.cloudstack.backup.dao.BackupScheduleDao;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.event.ActionEventUtils;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.dao.VMInstanceDao;

@RunWith(Parameterized.class)
public class BackupScheduleRetentionTest {
    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> providers() {
        return Arrays.asList(new Object[][] {{"ablestack-netbackup"}, {"ablestack-veeam"},
                {"ablestack-commvault"}, {"ablestack-nas"}, {"another-provider"}});
    }

    private final String provider;
    private BackupManagerImpl manager;
    private BackupDao backups;
    private BackupScheduleDao schedules;
    private BackupOfferingVO offering;
    private VMInstanceVO vm;
    private List<BackupScheduleVO> scheduleRows;

    public BackupScheduleRetentionTest(final String provider) {
        this.provider = provider;
    }

    @Before
    public void setup() {
        manager = spy(new BackupManagerImpl());
        backups = mock(BackupDao.class);
        schedules = mock(BackupScheduleDao.class);
        final BackupOfferingDao offerings = mock(BackupOfferingDao.class);
        final VMInstanceDao vms = mock(VMInstanceDao.class);
        ReflectionTestUtils.setField(manager, "backupDao", backups);
        ReflectionTestUtils.setField(manager, "backupDetailsDao", mock(BackupDetailsDao.class));
        ReflectionTestUtils.setField(manager, "backupScheduleDao", schedules);
        ReflectionTestUtils.setField(manager, "backupOfferingDao", offerings);
        ReflectionTestUtils.setField(manager, "vmInstanceDao", vms);
        vm = mock(VMInstanceVO.class);
        when(vm.getId()).thenReturn(1L);
        when(vm.getDataCenterId()).thenReturn(1L);
        when(vm.getBackupOfferingId()).thenReturn(2L);
        when(vms.findById(1L)).thenReturn(vm);
        offering = mock(BackupOfferingVO.class);
        when(offering.getProvider()).thenReturn(provider);
        when(offerings.findById(2L)).thenReturn(offering);
        scheduleRows = new ArrayList<>();
        when(schedules.listAll()).thenReturn(scheduleRows);
    }

    private BackupVO retainedBackup(final long id, final String parent, final Backup.Status status) {
        final BackupVO backup = new BackupVO();
        ReflectionTestUtils.setField(backup, "id", id);
        ReflectionTestUtils.setField(backup, "uuid", "backup-" + id);
        backup.setVmId(1L);
        backup.setZoneId(1L);
        backup.setBackupOfferingId(2L);
        backup.setBackupScheduleId(5L);
        backup.setDate(new Date(id));
        backup.setType(parent == null ? "FULL" : "INCREMENTAL");
        backup.setStatus(status);
        final Map<String, String> details = new HashMap<>();
        if (BackupProviderNameUtils.isNetBackupFamily(provider) || BackupProviderNameUtils.isVeeamFamily(provider)
                || BackupProviderNameUtils.isCommvaultFamily(provider)) {
            details.put(ThirdPartyBackupManifest.MODE_KEY, ThirdPartyBackupManifest.VOLUME_MODE);
        }
        if (parent != null) {
            final String prefix = BackupProviderNameUtils.isVeeamFamily(provider) ? "ablestack.veeam" : provider.replace("ablestack-", "");
            details.put(prefix + ".parent.backup.uuid", parent);
        }
        backup.setDetails(details);
        return backup;
    }

    private List<BackupVO> retainOneChain(final BackupVO... rows) throws Exception {
        final BackupScheduleVO schedule = new BackupScheduleVO();
        ReflectionTestUtils.setField(schedule, "id", 5L);
        ReflectionTestUtils.setField(schedule, "vmId", 1L);
        schedule.setScheduledTimestamp(new Date(System.currentTimeMillis() + 3600000));
        scheduleRows.add(schedule);
        schedule.setMaxBackups(1);
        when(schedules.findById(5L)).thenReturn(schedule);
        final List<BackupVO> retained = new ArrayList<>(Arrays.asList(rows));
        // Match BackupDaoImpl: only successful backups are visible to schedule retention.
        when(backups.listBySchedule(5L)).thenAnswer(invocation -> retained.stream()
                .filter(backup -> backup.getStatus() == Backup.Status.BackedUp).collect(Collectors.toList()));
        doAnswer(invocation -> {
            final Long id = invocation.getArgument(0);
            retained.removeIf(backup -> backup.getId() == id);
            return true;
        }).when(manager).deleteBackup(anyLong(), eq(false));
        return retained;
    }

    private void reconcileScheduleRetention() {
        final BackupProvider backupProvider = mock(BackupProvider.class);
        when(backupProvider.getName()).thenReturn(provider);
        final DataCenter zone = mock(DataCenter.class);
        when(zone.getId()).thenReturn(1L);
        manager.new BackupSyncTask(manager).reconcileBackupScheduleRetention(backupProvider, zone);
    }

    @Test
    public void backgroundRetentionRemovesOldChainsAndKeepsLatestFullWithItsIncrementals() throws Exception {
        final BackupVO full = retainedBackup(1L, null, Backup.Status.BackedUp);
        final BackupVO incremental = retainedBackup(2L, "backup-1", Backup.Status.BackedUp);
        final BackupVO secondFull = retainedBackup(3L, null, Backup.Status.BackedUp);
        final BackupVO secondIncremental = retainedBackup(4L, "backup-3", Backup.Status.BackedUp);
        final BackupVO latestFull = retainedBackup(5L, null, Backup.Status.BackedUp);
        final BackupVO latestIncremental = retainedBackup(6L, "backup-5", Backup.Status.BackedUp);
        final List<BackupVO> retained = retainOneChain(latestIncremental, secondFull, full, latestFull, incremental, secondIncremental);
        try (MockedStatic<ActionEventUtils> events = mockStatic(ActionEventUtils.class)) {
            // No create API or pending flag is needed: existing chains and older hooks are covered.
            reconcileScheduleRetention();
            final InOrder deletion = inOrder(manager);
            deletion.verify(manager).deleteBackup(2L, false);
            deletion.verify(manager).deleteBackup(1L, false);
            deletion.verify(manager).deleteBackup(4L, false);
            deletion.verify(manager).deleteBackup(3L, false);
            assertEquals(List.of(latestIncremental, latestFull), retained);
            verify(manager, never()).deleteBackup(5L, false);
            verify(manager, never()).deleteBackup(6L, false);
        }
    }

    @Test
    public void asynchronousReplacementCountsOnlyAfterSuccessfulCompletion() throws Exception {
        final BackupVO full = retainedBackup(1L, null, Backup.Status.BackedUp);
        final BackupVO replacement = retainedBackup(2L, null, Backup.Status.BackingUp);
        final List<BackupVO> retained = retainOneChain(full, replacement);
        for (final Backup.Status state : List.of(Backup.Status.Allocated, Backup.Status.Queued, Backup.Status.BackingUp,
                Backup.Status.Failed, Backup.Status.Error, Backup.Status.Canceled)) {
            replacement.setStatus(state);
            reconcileScheduleRetention();
        }
        verify(manager, never()).deleteBackup(anyLong(), eq(false));
        replacement.setStatus(Backup.Status.BackedUp);
        try (MockedStatic<ActionEventUtils> events = mockStatic(ActionEventUtils.class)) {
            reconcileScheduleRetention();
            assertEquals(List.of(replacement), retained);
        }
    }

    @Test
    public void blockedRetentionIsRetriedOnTheNextBackgroundPass() throws Exception {
        final BackupVO full = retainedBackup(1L, null, Backup.Status.BackedUp);
        final BackupVO replacement = retainedBackup(2L, null, Backup.Status.BackedUp);
        final List<BackupVO> retained = retainOneChain(full, replacement);
        doThrow(new CloudRuntimeException("A restore is still active"))
                .doAnswer(invocation -> {
                    retained.remove(full);
                    return true;
                }).when(manager).deleteBackup(1L, false);
        try (MockedStatic<ActionEventUtils> events = mockStatic(ActionEventUtils.class)) {
            reconcileScheduleRetention();
            assertEquals(List.of(full, replacement), retained);
            reconcileScheduleRetention();
            verify(manager, times(2)).deleteBackup(1L, false);
            assertEquals(List.of(replacement), retained);
        }
    }

    @Test
    public void activeJobReconciliationAppliesRetentionAfterBackupCompletion() throws Exception {
        final BackupVO full = retainedBackup(1L, null, Backup.Status.BackedUp);
        final BackupVO replacement = retainedBackup(2L, null, Backup.Status.BackingUp);
        final List<BackupVO> retained = retainOneChain(full, replacement);
        final BackupProvider backupProvider = mock(BackupProvider.class);
        when(backupProvider.getName()).thenReturn(provider);
        final DataCenterVO zone = mock(DataCenterVO.class);
        when(zone.getId()).thenReturn(1L);
        final DataCenterDao zones = mock(DataCenterDao.class);
        when(zones.listAllZones()).thenReturn(List.of(zone));
        ReflectionTestUtils.setField(manager, "dataCenterDao", zones);
        ReflectionTestUtils.setField(manager, "ablestackRestoreInstanceCleanup", mock(AblestackRestoreInstanceCleanup.class));
        ReflectionTestUtils.setField(manager, "netBackupRestoreCoordinator", mock(NetBackupRestoreCoordinator.class));
        doReturn(false).when(manager).isDisabled(1L);
        doReturn(List.of(backupProvider)).when(manager).getBackupProvidersForZone(1L);
        final BackupManagerImpl.BackupSyncTask task = spy(manager.new BackupSyncTask(manager));
        doNothing().when(task).processPostRestoreMaintenanceTasks();
        doNothing().when(task).reconcilePendingRestoreJobCleanups();
        doNothing().when(task).reconcileInterruptedRestoreJobs(backupProvider, zone);
        doNothing().when(task).reconcileAblestackOfflineRetention(backupProvider, zone);
        doAnswer(invocation -> {
            replacement.setStatus(Backup.Status.BackedUp);
            return null;
        }).when(task).reconcileBackingUpBackups(backupProvider, zone);
        try (MockedStatic<ActionEventUtils> events = mockStatic(ActionEventUtils.class)) {
            task.reconcileActiveBackupAndRestoreJobs("test");
            assertEquals(List.of(replacement), retained);
        }
    }

    @Test
    public void backgroundRetentionHonorsLimitsGreaterThanOne() throws Exception {
        final BackupVO first = retainedBackup(1L, null, Backup.Status.BackedUp);
        final BackupVO second = retainedBackup(2L, null, Backup.Status.BackedUp);
        final BackupVO third = retainedBackup(3L, null, Backup.Status.BackedUp);
        final List<BackupVO> retained = retainOneChain(first, second, third);
        scheduleRows.get(0).setMaxBackups(2);
        try (MockedStatic<ActionEventUtils> events = mockStatic(ActionEventUtils.class)) {
            reconcileScheduleRetention();
            assertEquals(List.of(second, third), retained);
        }
    }

    @Test
    public void backgroundRetentionSkipsUnlimitedSchedules() throws Exception {
        retainOneChain(retainedBackup(1L, null, Backup.Status.BackedUp), retainedBackup(2L, null, Backup.Status.BackedUp));
        scheduleRows.get(0).setMaxBackups(0);
        reconcileScheduleRetention();
        verify(manager, never()).deleteBackup(anyLong(), eq(false));
    }

    @Test
    public void backgroundRetentionSkipsOtherProvidersAndZones() throws Exception {
        retainOneChain(retainedBackup(1L, null, Backup.Status.BackedUp), retainedBackup(2L, null, Backup.Status.BackedUp));
        when(offering.getProvider()).thenReturn("other-provider");
        reconcileScheduleRetention();
        when(offering.getProvider()).thenReturn(provider);
        when(vm.getDataCenterId()).thenReturn(2L);
        reconcileScheduleRetention();
        verify(manager, never()).deleteBackup(anyLong(), eq(false));
    }
}
