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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.api.command.user.backup.ListExternalBackupCandidatesCmd;
import org.apache.cloudstack.api.command.user.backup.CreateAblestackVeeamBackupCmd;
import org.apache.cloudstack.api.command.user.backup.CreateNetBackupCmd;
import org.apache.cloudstack.api.response.ExternalBackupCandidatesResponse;
import org.apache.cloudstack.backup.dao.BackupDao;
import org.apache.cloudstack.backup.dao.BackupOfferingDao;
import org.apache.cloudstack.backup.dao.BackupScheduleDao;
import org.apache.cloudstack.context.CallContext;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockito.MockedStatic;
import org.mockito.MockedConstruction;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.event.UsageEventUtils;
import com.cloud.event.ActionEventUtils;
import com.cloud.api.response.ApiResponseSerializer;
import com.cloud.host.HostVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.resourcelimit.CheckedReservation;
import com.cloud.utils.DateUtil;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.db.TransactionLegacy;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.Pair;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.User;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.VMInstanceDao;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

@RunWith(Parameterized.class)
public class ExternalBackupScheduleTest {
    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> providers() {
        return Arrays.asList(new Object[][] {{"ablestack-netbackup"}, {"ablestack-veeam"}});
    }

    private final String provider;
    private BackupManagerImpl manager;
    private BackupScheduleDao schedules;
    private BackupOfferingDao offerings;
    private VMInstanceDao vms;
    private BackupDao backups;
    private BackupOfferingVO offering;
    private VMInstanceVO vm;
    private HostVO host;
    private List<BackupScheduleVO> scheduleRows;
    private List<VMInstanceVO> hostVms;
    private ListExternalBackupCandidatesCmd cmd;

    public ExternalBackupScheduleTest(final String provider) {
        this.provider = provider;
    }

    @Before
    public void setup() {
        manager = spy(new BackupManagerImpl());
        schedules = mock(BackupScheduleDao.class);
        offerings = mock(BackupOfferingDao.class);
        vms = mock(VMInstanceDao.class);
        backups = mock(BackupDao.class);
        ReflectionTestUtils.setField(manager, "backupScheduleDao", schedules);
        ReflectionTestUtils.setField(manager, "backupOfferingDao", offerings);
        ReflectionTestUtils.setField(manager, "vmInstanceDao", vms);
        ReflectionTestUtils.setField(manager, "backupDao", backups);
        doNothing().when(manager).validateBackupForZone(anyLong());
        host = mock(HostVO.class);
        when(host.getId()).thenReturn(14L);
        when(host.getUuid()).thenReturn("host-uuid");
        when(host.getName()).thenReturn("host-test");
        when(host.getDataCenterId()).thenReturn(1L);
        final com.cloud.host.dao.HostDao hosts = mock(com.cloud.host.dao.HostDao.class);
        when(hosts.findByName("host-test")).thenReturn(host);
        ReflectionTestUtils.setField(manager, "hostDao", hosts);
        cmd = mock(ListExternalBackupCandidatesCmd.class);
        when(cmd.getProvider()).thenReturn(provider);
        when(cmd.getVmScheduleBackup()).thenReturn(null);
        when(cmd.getHostId()).thenReturn(null);
        when(cmd.getHostName()).thenReturn("host-test");
        when(cmd.getPolicyId()).thenReturn("parent-policy");
        offering = mock(BackupOfferingVO.class);
        when(offering.getId()).thenReturn(2L);
        when(offering.getUuid()).thenReturn("offering-uuid");
        when(offering.getProvider()).thenReturn(provider);
        when(offering.getExternalId()).thenReturn(provider.endsWith("netbackup") ? "netbackup" : "veeam");
        when(offerings.findById(2L)).thenReturn(offering);
        vm = mock(VMInstanceVO.class);
        when(vm.getId()).thenReturn(1L);
        when(vm.getUuid()).thenReturn("vm-uuid");
        when(vm.getInstanceName()).thenReturn("i-2-1-VM");
        when(vm.getType()).thenReturn(VirtualMachine.Type.User);
        when(vm.getState()).thenReturn(VirtualMachine.State.Running);
        when(vm.getHostId()).thenReturn(14L);
        when(vm.getDataCenterId()).thenReturn(1L);
        when(vm.getBackupOfferingId()).thenReturn(2L);
        when(vms.findById(1L)).thenReturn(vm);
        scheduleRows = new ArrayList<>();
        hostVms = new ArrayList<>(List.of(vm));
        when(schedules.listAll()).thenReturn(scheduleRows);
        when(vms.listByHostId(14L)).thenReturn(hostVms);
    }

    private BackupScheduleVO schedule(final long id, final long timestamp) {
        final BackupScheduleVO schedule = new BackupScheduleVO();
        ReflectionTestUtils.setField(schedule, "id", id);
        ReflectionTestUtils.setField(schedule, "vmId", 1L);
        schedule.setScheduleType((short) DateUtil.IntervalType.DAILY.ordinal());
        schedule.setSchedule("00:01");
        schedule.setTimezone("UTC");
        schedule.setScheduledTimestamp(new Date(timestamp));
        scheduleRows.add(schedule);
        return schedule;
    }

    private BackupScheduleVO validate(final String mode, final Long expectedOffering, final Long scheduleId) {
        return ReflectionTestUtils.invokeMethod(manager, "validateExternalBackupExecution", vm, provider,
                "parent-policy", mode, 14L, expectedOffering, scheduleId, null);
    }

    @Test
    public void noSchedulesReturnsConfigCandidatesIncludingUnassignedVm() {
        when(vm.getBackupOfferingId()).thenReturn(null);
        final ExternalBackupCandidatesResponse response = manager.listExternalBackupCandidates(cmd);
        assertEquals("CONFIG", response.getSelectionMode());
        assertEquals(1, response.getCandidates().size());
    }

    @Test
    public void explicitScheduleModeWithoutSchedulesNeverFallsBackToHostTargets() {
        when(cmd.getVmScheduleBackup()).thenReturn(true);
        final ExternalBackupCandidatesResponse response = manager.listExternalBackupCandidates(cmd);
        assertEquals("SCHEDULE", response.getSelectionMode());
        assertEquals(0, response.getCandidates().size());
    }

    @Test
    public void explicitHostModeUsesHostTargetsEvenWhenMoldSchedulesExist() {
        schedule(5L, 1L);
        when(cmd.getVmScheduleBackup()).thenReturn(false);
        final ExternalBackupCandidatesResponse response = manager.listExternalBackupCandidates(cmd);
        assertEquals("CONFIG", response.getSelectionMode());
        assertEquals(1, response.getCandidates().size());
        verify(schedules, never()).listAll();
    }

    @Test
    public void explicitScheduleModeStillPropagatesScheduleLookupFailure() {
        when(cmd.getVmScheduleBackup()).thenReturn(true);
        when(schedules.listAll()).thenThrow(new CloudRuntimeException("schedule lookup unavailable"));
        assertThrows(CloudRuntimeException.class, () -> manager.listExternalBackupCandidates(cmd));
    }

    @Test
    public void explicitHostExecutionDoesNotConsumeAMoldSchedule() {
        schedule(5L, 1L);
        final Object result = ReflectionTestUtils.invokeMethod(manager, "validateExternalBackupExecution", vm, provider,
                "parent-policy", "CONFIG", 14L, 2L, null, false);
        assertEquals(null, result);
        verify(schedules, never()).listAll();
        verify(schedules, never()).update(anyLong(), any(BackupScheduleVO.class));
    }

    @Test
    public void executionCannotFallbackFromExplicitScheduleMode() {
        assertThrows(CloudRuntimeException.class, () -> ReflectionTestUtils.invokeMethod(manager, "validateExternalBackupExecution", vm, provider,
                "parent-policy", "CONFIG", 14L, 2L, null, true));
    }

    @Test
    public void executionCannotUseSchedulesWhenExplicitlyDisabled() {
        schedule(5L, 1L);
        assertThrows(CloudRuntimeException.class, () -> ReflectionTestUtils.invokeMethod(manager, "validateExternalBackupExecution", vm, provider,
                "parent-policy", "SCHEDULE", 14L, 2L, 5L, false));
    }

    @Test
    public void apiSerializationPreservesModeAndEmptyCandidateList() {
        final ExternalBackupCandidatesResponse response = new ExternalBackupCandidatesResponse(true, List.of());
        response.setResponseName("listexternalbackupcandidatesresponse");
        response.setObjectName("externalbackupcandidates");
        final String json = ApiResponseSerializer.toJSONSerializedString(response, new StringBuilder());
        final JsonObject body = JsonParser.parseString(json).getAsJsonObject()
                .getAsJsonObject("listexternalbackupcandidatesresponse").getAsJsonObject("externalbackupcandidates");
        assertEquals("SCHEDULE", body.get("selectionmode").getAsString());
        assertEquals(0, body.getAsJsonArray("candidate").size());
    }

    @Test
    public void futureSchedulePreventsConfigurationFallback() {
        schedule(5L, System.currentTimeMillis() + 3600000);
        final ExternalBackupCandidatesResponse response = manager.listExternalBackupCandidates(cmd);
        assertEquals("SCHEDULE", response.getSelectionMode());
        assertEquals(0, response.getCandidates().size());
    }

    @Test
    public void stoppedScheduledVmStillPreventsConfigurationFallback() {
        schedule(5L, 1L);
        when(vm.getState()).thenReturn(VirtualMachine.State.Stopped);
        when(vm.getHostId()).thenReturn(null);
        when(vm.getLastHostId()).thenReturn(14L);
        final ExternalBackupCandidatesResponse response = manager.listExternalBackupCandidates(cmd);
        assertEquals("SCHEDULE", response.getSelectionMode());
        assertEquals(0, response.getCandidates().size());
    }

    @Test
    public void dueSchedulesSelectVmOnceAndAreNotConsumedByLookup() {
        schedule(5L, 1L);
        schedule(6L, 2L);
        final ExternalBackupCandidatesResponse response = manager.listExternalBackupCandidates(cmd);
        assertEquals("SCHEDULE", response.getSelectionMode());
        assertEquals(1, response.getCandidates().size());
        verify(schedules, never()).update(anyLong(), any(BackupScheduleVO.class));
    }

    @Test
    public void otherSolutionOfferingIsExcluded() {
        when(offering.getProvider()).thenReturn("ablestack-commvault");
        assertEquals(0, manager.listExternalBackupCandidates(cmd).getCandidates().size());
    }

    @Test
    public void customOfferingMustMatchParentPolicy() {
        when(offering.getExternalId()).thenReturn("other-policy");
        assertEquals(0, manager.listExternalBackupCandidates(cmd).getCandidates().size());
        when(offering.getExternalId()).thenReturn("parent-policy");
        assertEquals(1, manager.listExternalBackupCandidates(cmd).getCandidates().size());
    }

    @Test
    public void schedulesOnAnotherHostDoNotSelectThisHostsScheduleMode() {
        schedule(5L, 1L);
        when(vm.getHostId()).thenReturn(15L);
        assertEquals("CONFIG", manager.listExternalBackupCandidates(cmd).getSelectionMode());
    }

    @Test
    public void activeBackupIsExcludedWithoutConfigurationFallback() {
        schedule(5L, 1L);
        final BackupVO active = mock(BackupVO.class);
        when(active.getStatus()).thenReturn(Backup.Status.BackingUp);
        when(backups.listByVmIdAndOffering(1L, 1L, 2L)).thenReturn(List.of(active));
        final ExternalBackupCandidatesResponse response = manager.listExternalBackupCandidates(cmd);
        assertEquals("SCHEDULE", response.getSelectionMode());
        assertEquals(0, response.getCandidates().size());
    }

    @Test
    public void scheduleQueryFailurePropagatesWithoutConfigurationFallback() {
        when(schedules.listAll()).thenThrow(new CloudRuntimeException("database unavailable"));
        assertThrows(CloudRuntimeException.class, () -> manager.listExternalBackupCandidates(cmd));
    }

    @Test
    public void scheduledExecutionRequiresSameOfferingAndDueSchedule() {
        schedule(5L, 1L);
        assertNotNull(validate("SCHEDULE", 2L, 5L));
        when(vm.getBackupOfferingId()).thenReturn(null);
        assertThrows(CloudRuntimeException.class, () -> validate("SCHEDULE", 2L, 5L));
    }

    @Test
    public void consumedOrDeletedScheduleCannotBeExecuted() {
        final BackupScheduleVO schedule = schedule(5L, System.currentTimeMillis() + 3600000);
        assertThrows(CloudRuntimeException.class, () -> validate("SCHEDULE", 2L, 5L));
        schedule.setScheduledTimestamp(new Date(1L));
        scheduleRows.clear();
        assertThrows(CloudRuntimeException.class, () -> validate("SCHEDULE", 2L, 5L));
    }

    @Test
    public void configExecutionStopsIfAScheduleWasAdded() {
        schedule(5L, System.currentTimeMillis() + 3600000);
        assertThrows(CloudRuntimeException.class, () -> validate("CONFIG", 2L, null));
    }

    @Test
    public void movedVmCannotBeExecuted() {
        when(vm.getHostId()).thenReturn(15L);
        assertThrows(CloudRuntimeException.class, () -> validate("CONFIG", 2L, null));
    }

    @Test
    public void existingOfferingIsRetainedWithoutReassignment() {
        assertSame(offering, ReflectionTestUtils.invokeMethod(manager, "resolveExternalBackupOffering", vm, provider, true));
        verify(offerings, never()).findByExternalId(any(), anyLong());
        verify(vm, never()).setBackupOfferingId(anyLong());
    }

    @Test
    public void scheduledModeNeverAutoAssigns() {
        when(vm.getBackupOfferingId()).thenReturn(null);
        assertThrows(CloudRuntimeException.class,
                () -> ReflectionTestUtils.invokeMethod(manager, "resolveExternalBackupOffering", vm, provider, false));
        verify(vm, never()).setBackupOfferingId(anyLong());
    }

    @Test
    public void configModeAssignsDefaultOfferingOnlyToAnUnassignedVm() {
        when(vm.getBackupOfferingId()).thenReturn(null);
        when(offerings.findByExternalId(offering.getExternalId(), 1L)).thenReturn(offering);
        final BackupProvider backupProvider = mock(BackupProvider.class);
        when(backupProvider.assignVMToBackupOffering(vm, offering)).thenReturn(true);
        final Map<String, BackupProvider> providers = new HashMap<>();
        providers.put(provider, backupProvider);
        ReflectionTestUtils.setField(manager, "backupProvidersMap", providers);
        ReflectionTestUtils.setField(manager, "volumeDao", mock(VolumeDao.class));
        when(vms.update(1L, vm)).thenReturn(true);
        try (MockedStatic<Transaction> transaction = mockStatic(Transaction.class);
                MockedStatic<UsageEventUtils> usage = mockStatic(UsageEventUtils.class)) {
            transaction.when(() -> Transaction.execute(eq(TransactionLegacy.CLOUD_DB), any(TransactionCallback.class)))
                    .thenAnswer(invocation -> ((TransactionCallback<?>) invocation.getArgument(1)).doInTransaction(null));
            assertSame(offering, ReflectionTestUtils.invokeMethod(manager, "resolveExternalBackupOffering", vm, provider, true));
            verify(vm).setBackupOfferingId(2L);
            verify(backupProvider).assignVMToBackupOffering(vm, offering);
        }
    }

    private void executeScheduledBackup(final boolean accepted) throws Exception {
        final BackupScheduleVO schedule = schedule(5L, 1L);
        when(schedules.findById(5L)).thenReturn(schedule);
        final BackupProvider backupProvider = mock(BackupProvider.class);
        when(backupProvider.getName()).thenReturn(provider);
        when(backupProvider.takeNetBackup(vm, "parent-policy", 5L)).thenReturn(new Pair<>(accepted, null));
        when(backupProvider.takeBackup(eq(vm), any(), eq(5L), eq("parent-policy"))).thenReturn(new Pair<>(accepted, null));
        final Map<String, BackupProvider> providers = new HashMap<>();
        providers.put(provider, backupProvider);
        ReflectionTestUtils.setField(manager, "backupProvidersMap", providers);
        ReflectionTestUtils.setField(manager, "volumeDao", mock(VolumeDao.class));
        ReflectionTestUtils.setField(manager, "backupVolumeGuard", mock(BackupVolumeGuard.class));
        ReflectionTestUtils.setField(manager, "backupSnapshotGuard", mock(BackupSnapshotGuard.class));
        final AccountManager accounts = mock(AccountManager.class);
        ReflectionTestUtils.setField(manager, "accountManager", accounts);
        final Account owner = mock(Account.class);
        when(accounts.getAccount(anyLong())).thenReturn(owner);
        doNothing().when(manager).checkNoActiveFastCloneFlattenForBackup(1L);
        doNothing().when(manager).deleteOldestBackupFromScheduleIfRequired(1L, 5L);
        CallContext.register(mock(User.class), owner);
        try (MockedConstruction<CheckedReservation> reservations = mockConstruction(CheckedReservation.class);
                MockedStatic<ActionEventUtils> events = mockStatic(ActionEventUtils.class);
                MockedStatic<Transaction> transactions = mockStatic(Transaction.class)) {
            transactions.when(() -> Transaction.execute(any(TransactionCallback.class)))
                    .thenAnswer(invocation -> ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null));
            if (provider.endsWith("netbackup")) {
                final CreateNetBackupCmd request = new CreateNetBackupCmd();
                ReflectionTestUtils.setField(request, "vmId", 1L);
                ReflectionTestUtils.setField(request, "scheduleId", 5L);
                ReflectionTestUtils.setField(request, "hostId", 14L);
                ReflectionTestUtils.setField(request, "backupOfferingId", 2L);
                ReflectionTestUtils.setField(request, "selectionMode", "SCHEDULE");
                ReflectionTestUtils.setField(request, "vmScheduleBackup", true);
                ReflectionTestUtils.setField(request, "policyId", "parent-policy");
                manager.createNetBackup(request);
            } else {
                final CreateAblestackVeeamBackupCmd request = new CreateAblestackVeeamBackupCmd();
                ReflectionTestUtils.setField(request, "vmId", 1L);
                ReflectionTestUtils.setField(request, "scheduleId", 5L);
                ReflectionTestUtils.setField(request, "hostId", 14L);
                ReflectionTestUtils.setField(request, "backupOfferingId", 2L);
                ReflectionTestUtils.setField(request, "selectionMode", "SCHEDULE");
                ReflectionTestUtils.setField(request, "vmScheduleBackup", true);
                ReflectionTestUtils.setField(request, "veeamJobName", "parent-policy");
                manager.createAblestackVeeamBackup(request, null);
            }
        } finally {
            CallContext.unregister();
        }
    }

    @Test
    public void acceptedBackupAdvancesTheSchedule() throws Exception {
        executeScheduledBackup(true);
        verify(schedules).update(eq(5L), any(BackupScheduleVO.class));
        assertEquals(true, scheduleRows.get(0).getScheduledTimestamp().after(new Date()));
    }

    @Test
    public void rejectedBackupLeavesTheScheduleDue() {
        assertThrows(CloudRuntimeException.class, () -> executeScheduledBackup(false));
        verify(schedules, never()).update(anyLong(), any(BackupScheduleVO.class));
        assertEquals(new Date(1L), scheduleRows.get(0).getScheduledTimestamp());
    }
}
