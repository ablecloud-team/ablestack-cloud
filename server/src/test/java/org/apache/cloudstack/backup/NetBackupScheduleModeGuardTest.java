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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Date;
import javax.inject.Provider;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;
import org.apache.cloudstack.backup.dao.BackupDao;
import org.apache.cloudstack.backup.dao.BackupDetailsDao;
import org.apache.cloudstack.backup.dao.BackupOfferingDao;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.impl.ConfigDepotImpl;
import org.apache.cloudstack.api.command.user.backup.CreateNetBackupCmd;
import org.apache.cloudstack.framework.jobs.dao.AsyncJobDao;
import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.exception.CloudRuntimeException;

public class NetBackupScheduleModeGuardTest {
    private NetBackupScheduleModeGuard guard;
    private ConfigDepotImpl previous;
    private ConfigDepotImpl depot;
    private BackupDao backups;
    private BackupOfferingDao offerings;
    private BackupDetailsDao details;
    private HostDao hosts;
    private HostVO host;
    private AgentManager agents;
    private BackupProvider provider;
    private AsyncJobDao jobs;
    private GlobalLock modeLock;
    private GlobalLock configLock;
    private MockedStatic<GlobalLock> locks;

    @Before public void setup() throws Exception {
        previous = (ConfigDepotImpl) ReflectionTestUtils.getField(BackupManager.NetBackupVmScheduleEnabled, "s_depot");
        depot = mock(ConfigDepotImpl.class);
        ConfigKey.init(depot);
        when(depot.getConfigStringValue(eq(BackupManager.NetBackupVmScheduleEnabled.key()), any(), any())).thenReturn("true");
        when(depot.getConfigStringValue(eq(BackupManager.BackupProviderPlugin.key()), any(), any())).thenReturn("ablestack-netbackup");
        guard = new NetBackupScheduleModeGuard();
        backups = mock(BackupDao.class); offerings = mock(BackupOfferingDao.class); details = mock(BackupDetailsDao.class);
        hosts = mock(HostDao.class); agents = mock(AgentManager.class); provider = mock(BackupProvider.class);
        jobs = mock(AsyncJobDao.class);
        ThirdPartyBackupVolumeService volumeService = mock(ThirdPartyBackupVolumeService.class);
        ReflectionTestUtils.setField(guard, "volumeService", (Provider<ThirdPartyBackupVolumeService>) () -> volumeService);
        host = host(14L, 1L); when(hosts.listByType(Host.Type.Routing)).thenReturn(List.of(host));
        when(agents.send(eq(14L), any(NetBackupHostConfigurationCommand.class))).thenReturn(new Answer(null, true,
                "{\"state\":\"NOT_CONFIGURED\",\"configured\":false,\"configuredpolicies\":[],\"policyinventorycomplete\":true}"));
        BackupManager manager = mock(BackupManager.class);
        when(manager.getBackupProvider("ablestack-netbackup")).thenReturn(provider);
        Provider<BackupManager> lazy = () -> manager;
        for (Object[] dependency : new Object[][] {{"backupDao", backups}, {"offeringDao", offerings}, {"detailsDao", details},
                {"hostDao", hosts}, {"agentManager", agents}, {"backupManager", lazy}, {"asyncJobDao", jobs}}) {
            ReflectionTestUtils.setField(guard, (String) dependency[0], dependency[1]);
        }
        modeLock = mock(GlobalLock.class); configLock = mock(GlobalLock.class);
        when(modeLock.lock(5)).thenReturn(true); when(configLock.lock(5)).thenReturn(true);
        locks = mockStatic(GlobalLock.class);
        locks.when(() -> GlobalLock.getInternLock("backup.netbackup.schedule.mode")).thenReturn(modeLock);
        locks.when(() -> GlobalLock.getInternLock(ThirdPartyBackupStagingService.CONFIGURATION_LOCK)).thenReturn(configLock);
    }
    @After public void cleanup() { locks.close(); ConfigKey.init(previous); }

    private HostVO host(long id, long zone) {
        HostVO value = mock(HostVO.class);
        when(value.getId()).thenReturn(id); when(value.getDataCenterId()).thenReturn(zone);
        when(value.getName()).thenReturn("host-" + id); when(value.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(value.getStatus()).thenReturn(Status.Up);
        return value;
    }
    private BackupVO backup(String providerName, Backup.Status status) {
        BackupOfferingVO offering = mock(BackupOfferingVO.class);
        when(offering.getId()).thenReturn(10L); when(offering.getZoneId()).thenReturn(1L); when(offering.getProvider()).thenReturn(providerName);
        when(offerings.listAllIncludingRemoved()).thenReturn(List.of(offering));
        BackupVO backup = mock(BackupVO.class);
        when(backup.getId()).thenReturn(7L); when(backup.getBackupOfferingId()).thenReturn(10L);
        when(backup.getUuid()).thenReturn("backup-7"); when(backup.getStatus()).thenReturn(status);
        when(backups.listAllIncludingRemoved()).thenReturn(List.of(backup));
        when(backups.findByIdIncludingRemoved(7L)).thenReturn(backup);
        return backup;
    }
    private void denied(String code) {
        CloudRuntimeException error = assertThrows(CloudRuntimeException.class, () -> guard.update("false", () -> { throw new AssertionError("Setting must not be saved"); }));
        assertTrue(error.getMessage(), error.getMessage().startsWith("NETBACKUP_VM_SCHEDULE_MODE_" + code + ":"));
    }

    @Test public void initialSetupAllowsModeChangeWithoutNetBackupCredentialsOrPolicies() {
        assertEquals("saved", guard.update("false", () -> "saved"));
        verifyNoInteractions(provider);
        verify(modeLock).unlock(); verify(configLock).unlock();
    }
    @Test public void hookSynchronizationSelectsOnlyConfiguredClientsAndCanRecoverPendingUpdates() throws Exception {
        when(agents.send(eq(14L), any(NetBackupHostConfigurationCommand.class))).thenReturn(new Answer(null, true,
                "{\"state\":\"COMPLETED\",\"configured\":true,\"clientinstalled\":true,\"hooktimeoutpending\":true,"
                        + "\"configuredpolicies\":[\"policy-a\"],\"policyinventorycomplete\":true}"));
        assertEquals(List.of(host), guard.withIdleHosts(value -> value));
        denied("UNCONFIRMED");
    }
    @Test public void hookSynchronizationSkipsClientOnlyAndMissingBpConfHosts() {
        assertTrue(guard.withIdleHosts(value -> value).isEmpty());
        verifyNoInteractions(provider);
    }
    @Test public void allConfiguredPoliciesInAllZonesAreValidated() throws Exception {
        HostVO second = host(15L, 2L);
        when(hosts.listByType(Host.Type.Routing)).thenReturn(List.of(host, second));
        when(agents.send(any(Long.class), any(NetBackupHostConfigurationCommand.class))).thenReturn(new Answer(null, true,
                "{\"state\":\"COMPLETED\",\"configured\":true,\"configuredpolicies\":[\"policy-a\",\"policy-b\"],\"policyinventorycomplete\":true}"));
        assertEquals("saved", guard.update("false", () -> "saved"));
        verify(provider).validateVmScheduleModeChange(1L, List.of("policy-a", "policy-b"));
        verify(provider).validateVmScheduleModeChange(2L, List.of("policy-a", "policy-b"));
    }
    @Test public void activeAndQueuedBackupStatesPreventChangingMode() {
        BackupVO backup = backup("ablestack-netbackup", Backup.Status.BackingUp);
        for (Backup.Status status : List.of(Backup.Status.Queued, Backup.Status.BackingUp, Backup.Status.ReadyForImageTransfer,
                Backup.Status.FinalizingImageTransfer, Backup.Status.Restoring)) {
            when(backup.getStatus()).thenReturn(status); denied("BACKUP_BUSY");
        }
        verifyNoInteractions(agents, provider);
    }
    @Test public void completedHistoryAndSchedulesAreNotAReasonToBlockOrRewriteData() {
        BackupVO backup = backup("ablestack-netbackup", Backup.Status.BackedUp);
        when(backup.getDetail(AblestackBackupFrameworkUtils.RESTORE_JOB_STATE_DETAIL)).thenReturn("COMPLETED");
        when(backup.getDetail(AblestackBackupFrameworkUtils.RESTORE_JOB_ID_DETAIL)).thenReturn("old-restore");
        assertEquals("saved", guard.update("false", () -> "saved"));
        verify(backups, never()).remove(any(Long.class));
    }
    @Test public void removedLegacyHistoryDoesNotBlockModeOrHookChangesWithStaleActiveStatus() {
        BackupVO backup = backup("ablestack-netbackup", Backup.Status.BackingUp);
        when(backup.getRemoved()).thenReturn(new Date(1));
        for (Backup.Status status : List.of(Backup.Status.Allocated, Backup.Status.Queued, Backup.Status.BackingUp,
                Backup.Status.ReadyForImageTransfer, Backup.Status.FinalizingImageTransfer, Backup.Status.Restoring)) {
            when(backup.getStatus()).thenReturn(status);
            assertEquals("saved", guard.update("false", () -> "saved"));
            assertEquals("saved", guard.withIdleHosts(value -> "saved"));
        }
        verify(backups, never()).remove(any(Long.class));
    }
    @Test public void removedCurrentVolumeOrStartOwnershipStillBlocksChanges() {
        BackupVO backup = backup("ablestack-netbackup", Backup.Status.BackingUp);
        when(backup.getRemoved()).thenReturn(new Date(1));
        when(backup.getDetail(ThirdPartyBackupManifest.MODE_KEY)).thenReturn(ThirdPartyBackupManifest.VOLUME_MODE);
        denied("BACKUP_BUSY");
        when(backup.getDetail(ThirdPartyBackupManifest.MODE_KEY)).thenReturn(null);
        when(backup.getDetail(ThirdPartyBackupStart.DETAIL_KEY)).thenReturn("{\"state\":\"DISPATCHED\"}");
        denied("BACKUP_BUSY");
    }
    @Test public void removedLegacyHistoryStillBlocksUnfinishedCleanupAndRestore() {
        BackupVO backup = backup("ablestack-netbackup", Backup.Status.BackingUp);
        when(backup.getRemoved()).thenReturn(new Date(1));
        when(backup.getDetail(ThirdPartyBackupManifest.SOURCE_CLEANUP_STATE_KEY)).thenReturn("WAITING");
        denied("CLEANUP_PENDING");
        when(backup.getDetail(ThirdPartyBackupManifest.SOURCE_CLEANUP_STATE_KEY)).thenReturn("COMPLETED");
        when(backup.getDetail(AblestackBackupFrameworkUtils.RESTORE_JOB_STATE_DETAIL)).thenReturn("RUNNING");
        denied("CLEANUP_PENDING");
    }
    @Test public void removedLegacyHistoryStillBlocksUnreleasedAdmissions() {
        BackupVO backup = backup("ablestack-netbackup", Backup.Status.BackingUp);
        when(backup.getRemoved()).thenReturn(new Date(1));
        BackupDetailVO detail = mock(BackupDetailVO.class);
        when(detail.getResourceId()).thenReturn(7L);
        when(details.findDetails(ThirdPartyBackupAdmission.BACKUP_KEY)).thenReturn(List.of(detail));
        when(detail.getValue()).thenReturn("{\"state\":\"ADMITTED\"}");
        denied("BACKUP_BUSY");
        when(detail.getValue()).thenReturn("{\"state\":\"RELEASED\"}");
        assertEquals("saved", guard.update("false", () -> "saved"));
    }
    @Test public void otherProvidersDoNotBlockNetBackupModeChanges() {
        backup("ablestack-veeam", Backup.Status.BackingUp);
        assertEquals("saved", guard.update("false", () -> "saved"));
    }
    @Test public void failedBackupWithPendingCleanupStillBlocks() {
        BackupVO backup = backup("ablestack-netbackup", Backup.Status.Canceled);
        when(backup.getDetail(ThirdPartyBackupManifest.CLEANUP_STATE_KEY)).thenReturn("WAITING");
        denied("CLEANUP_PENDING");
        when(backup.getDetail(ThirdPartyBackupManifest.CLEANUP_STATE_KEY)).thenReturn("COMPLETED");
        assertEquals("saved", guard.update("false", () -> "saved"));
    }
    @Test public void pendingSubmissionBlocksBeforeAnyBackupRecordExists() {
        when(jobs.countPendingJobs(null, CreateNetBackupCmd.class.getName())).thenReturn(1L);
        denied("BACKUP_BUSY");
        verifyNoInteractions(backups, agents, provider);
    }
    @Test public void confirmedUnnecessaryCleanupIsAllowedButUnconfirmedCancellationIsNot() {
        BackupVO backup = backup("ablestack-netbackup", Backup.Status.Failed);
        when(backup.getDetail(ThirdPartyBackupRestore.CLEANUP_STATE_KEY)).thenReturn("NOT_REQUIRED");
        assertEquals("saved", guard.update("false", () -> "saved"));
        when(backup.getDetail(AblestackBackupFrameworkUtils.BACKUP_CANCEL_REQUESTED_DETAIL)).thenReturn("true");
        denied("CLEANUP_PENDING");
    }
    @Test public void unresolvedRestoreAndDeletionMarkersBlock() {
        BackupVO backup = backup("ablestack-netbackup", Backup.Status.BackedUp);
        when(backup.getDetail(AblestackBackupFrameworkUtils.RESTORE_JOB_STATE_DETAIL)).thenReturn("WAITING");
        denied("CLEANUP_PENDING");
        when(backup.getDetail(AblestackBackupFrameworkUtils.RESTORE_JOB_STATE_DETAIL)).thenReturn("COMPLETED");
        when(backup.getDetail(BackupDeletionService.REQUEST_KEY)).thenReturn("pending-deletion");
        denied("CLEANUP_PENDING");
    }
    @Test public void waitingAdmissionAndPendingCancellationBlockEvenForTerminalBackupRecords() {
        backup("ablestack-netbackup", Backup.Status.Failed);
        BackupDetailVO detail = mock(BackupDetailVO.class); when(detail.getResourceId()).thenReturn(7L);
        when(details.findDetails(ThirdPartyBackupAdmission.BACKUP_KEY)).thenReturn(List.of(detail));
        for (String state : List.of("WAITING", "ADMITTING", "ADMITTED", "CANCEL_PENDING")) {
            when(detail.getValue()).thenReturn("{\"state\":\"" + state + "\"}"); denied("BACKUP_BUSY");
        }
        when(detail.getValue()).thenReturn("{\"state\":\"RELEASED\"}");
        assertEquals("saved", guard.update("false", () -> "saved"));
    }
    @Test public void hostSetupAndUnconfirmedHostOperationsBlock() throws Exception {
        for (String state : List.of("RUNNING", "UNCONFIRMED")) {
            when(agents.send(eq(14L), any(NetBackupHostConfigurationCommand.class))).thenReturn(new Answer(null, true,
                    "{\"state\":\"" + state + "\",\"configuredpolicies\":[],\"policyinventorycomplete\":true}"));
            denied("HOST_BUSY");
        }
    }
    @Test public void disconnectedHostOrLegacyIncompleteInventoryCannotBeAssumedIdle() throws Exception {
        when(host.getStatus()).thenReturn(Status.Disconnected); denied("UNCONFIRMED");
        when(host.getStatus()).thenReturn(Status.Up);
        when(agents.send(eq(14L), any(NetBackupHostConfigurationCommand.class))).thenReturn(new Answer(null, true,
                "{\"state\":\"COMPLETED\",\"configured\":true,\"policyname\":\"last-policy-only\"}"));
        denied("UNCONFIRMED");
    }
    @Test public void externalPolicyOrJobRejectionLeavesSettingUntouched() throws Exception {
        when(agents.send(eq(14L), any(NetBackupHostConfigurationCommand.class))).thenReturn(new Answer(null, true,
                "{\"state\":\"COMPLETED\",\"configured\":true,\"configuredpolicies\":[\"policy-a\"],\"policyinventorycomplete\":true}"));
        doThrow(new CloudRuntimeException("NETBACKUP_VM_SCHEDULE_MODE_POLICY_ACTIVE: policy-a"))
                .when(provider).validateVmScheduleModeChange(1L, List.of("policy-a"));
        denied("POLICY_ACTIVE");
    }
    @Test public void executionAndConfigurationLocksCannotBeBypassed() {
        when(modeLock.lock(5)).thenReturn(false); denied("BUSY");
        verifyNoInteractions(backups, agents);
        when(modeLock.lock(5)).thenReturn(true); when(configLock.lock(5)).thenReturn(false); denied("BUSY");
    }
    @Test public void savingTheSameValueDoesNotRequireStoppingJobsOrContactingHosts() {
        assertEquals("unchanged", guard.update("true", () -> "unchanged"));
        verifyNoInteractions(backups, agents, provider);
    }
    @Test public void invalidBooleanCannotReachTheSaveCallback() { deniedInvalid(); }
    private void deniedInvalid() {
        assertThrows(CloudRuntimeException.class, () -> guard.update("not-a-boolean", () -> { throw new AssertionError(); }));
    }
}
