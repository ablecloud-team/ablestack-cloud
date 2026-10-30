// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE for copyright ownership.
// Licensed under the Apache License, Version 2.0 (the "License");
// http://www.apache.org/licenses/LICENSE-2.0 . Distributed AS IS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
package org.apache.cloudstack.backup;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.cloudstack.backup.dao.BackupDao;
import org.apache.cloudstack.backup.dao.BackupDetailsDao;
import org.apache.cloudstack.backup.dao.BackupOfferingDao;
import com.cloud.storage.Snapshot;
import com.cloud.storage.SnapshotVO;
import com.cloud.storage.Storage;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.SnapshotDao;
import com.cloud.storage.dao.SnapshotDetailsDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.storage.dao.VolumeDetailsDao;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;
import com.cloud.vm.snapshot.VMSnapshotVO;
import com.cloud.vm.snapshot.dao.VMSnapshotDao;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class AblestackBackupOperationGuardTest {
    private static final List<String> PROVIDERS = List.of("ablestack-nas", "ablestack-commvault", "ablestack-netbackup", "ablestack-veeam");
    private static final List<String> CLOUDSTACK_PROVIDERS = List.of("nas", "commvault", "netbackup", "veeam", "networker", "kboss", "dummy", "bx");
    @Mock private VMInstanceDao vmDao;
    @Mock private VolumeDao volumeDao;
    @Mock private SnapshotDao snapshotDao;
    @Mock private BackupDao backupDao;
    @Mock private BackupDetailsDao backupDetailsDao;
    @Mock private BackupOfferingDao offeringDao;
    @Mock private BackupVolumeGuard backupVolumeGuard;
    @Mock private VMInstanceDetailsDao vmInstanceDetailsDao;
    @Mock private VolumeDetailsDao volumeDetailsDao;
    @Mock private VMSnapshotDao vmSnapshotDao;
    @Mock private PrimaryDataStoreDao poolDao;
    @Mock private SnapshotDetailsDao snapshotDetailsDao;
    @InjectMocks private AblestackBackupOperationGuard guard;
    private VMInstanceVO vm;
    private VolumeVO root;

    @Before public void setup() {
        vm = mock(VMInstanceVO.class);
        root = mock(VolumeVO.class);
        lenient().when(vm.getState()).thenReturn(State.Stopped);
        lenient().when(vm.getUuid()).thenReturn("vm-1");
        lenient().when(vmDao.findByIdIncludingRemoved(1L)).thenReturn(vm);
        lenient().when(volumeDao.findByInstance(1L)).thenReturn(List.of(root));
        lenient().when(root.getId()).thenReturn(10L);
        lenient().when(root.getInstanceId()).thenReturn(1L);
        lenient().when(root.getUuid()).thenReturn("root-1");
        lenient().when(root.getState()).thenReturn(Volume.State.Ready);
        lenient().when(root.getPoolId()).thenReturn(20L);
        StoragePoolVO pool = mock(StoragePoolVO.class);
        lenient().when(pool.getPoolType()).thenReturn(Storage.StoragePoolType.SharedMountPoint);
        lenient().when(poolDao.findById(20L)).thenReturn(pool);
    }

    @Test public void allProvidersAllowReadyVolumesForOnlineAndOfflineBackups() {
        for (String provider : PROVIDERS) {
            when(vm.getState()).thenReturn(State.Running);
            guard.checkBackup(1L, provider);
            when(vm.getState()).thenReturn(State.Stopped);
            guard.checkBackup(1L, provider);
        }
    }

    @Test public void aBusyDataDiskRejectsTheWholeBackupAndRestoreForEveryProvider() {
        VolumeVO data = mock(VolumeVO.class);
        when(data.getUuid()).thenReturn("data-2");
        when(volumeDao.findByInstance(1L)).thenReturn(List.of(root, data));
        for (Volume.State state : List.of(Volume.State.Allocated, Volume.State.Creating, Volume.State.Migrating,
                Volume.State.Snapshotting, Volume.State.Restoring, Volume.State.Consolidating, Volume.State.Destroy)) {
            when(data.getState()).thenReturn(state);
            for (String provider : PROVIDERS) {
                CloudRuntimeException failure = assertThrows(CloudRuntimeException.class, () -> guard.checkBackup(1L, provider));
                assertTrue(failure.getMessage().contains("data-2"));
                assertTrue(failure.getMessage().contains(state.name()));
                assertThrows(CloudRuntimeException.class, () -> guard.checkRestore(1L, provider, false));
            }
        }
    }

    @Test public void transitionStatesAreRejectedBeforeBackupOrRestoreStarts() {
        for (State state : List.of(State.Starting, State.Stopping, State.Migrating, State.Restoring)) {
            when(vm.getState()).thenReturn(state);
            for (String provider : PROVIDERS) {
                assertThrows(CloudRuntimeException.class, () -> guard.checkBackup(1L, provider));
                assertThrows(CloudRuntimeException.class, () -> guard.checkRestore(1L, provider, false));
                assertThrows(CloudRuntimeException.class, () -> guard.checkRestore(1L, provider, true));
            }
        }
    }

    @Test public void liveVolumeAttachKeepsItsExistingPowerStateContract() {
        when(vm.getState()).thenReturn(State.Running);
        for (String provider : PROVIDERS) {
            guard.checkRestore(1L, provider, true);
            assertThrows(CloudRuntimeException.class, () -> guard.checkRestore(1L, provider, false));
        }
    }

    @Test public void destroyedVmRestoreRetainsTheExplicitVolumeStateTransitions() {
        when(vm.getState()).thenReturn(State.Destroyed);
        for (Volume.State state : List.of(Volume.State.Destroy, Volume.State.Expunged)) {
            when(root.getState()).thenReturn(state);
            for (String provider : PROVIDERS) {
                guard.checkRestore(1L, provider, false);
                assertThrows(CloudRuntimeException.class, () -> guard.checkBackup(1L, provider));
                assertThrows(CloudRuntimeException.class, () -> guard.checkRestore(1L, provider, true));
            }
        }
    }

    @Test public void allocatedSnapshotJobsBlockNewBackupAndRestoreEvenBeforeVolumeStateChanges() {
        when(snapshotDao.listByStatus(10L, Snapshot.State.Creating, Snapshot.State.CreatedOnPrimary, Snapshot.State.BackingUp))
                .thenReturn(List.of(mock(SnapshotVO.class)));
        for (String provider : PROVIDERS) {
            assertThrows(CloudRuntimeException.class, () -> guard.checkBackup(1L, provider));
            assertThrows(CloudRuntimeException.class, () -> guard.checkRestore(1L, provider, false));
        }
    }

    private Map<String, String> backup(String provider, long sourceVm, Backup.Status status, boolean foreignTarget) {
        Map<String, String> details = new HashMap<>();
        BackupVO backup = mock(BackupVO.class);
        lenient().when(backup.getId()).thenReturn(7L);
        when(backup.getBackupOfferingId()).thenReturn(9L);
        lenient().when(backup.getVmId()).thenReturn(sourceVm);
        lenient().when(backup.getStatus()).thenReturn(status);
        lenient().when(backup.getDetail(anyString())).thenAnswer(i -> details.get(i.getArgument(0)));
        when(backupDao.findById(7L)).thenReturn(backup);
        BackupOfferingVO offering = mock(BackupOfferingVO.class);
        when(offering.getProvider()).thenReturn(provider);
        when(offeringDao.findByIdIncludingRemoved(9L)).thenReturn(offering);
        if (foreignTarget) {
            BackupDetailVO target = mock(BackupDetailVO.class);
            when(target.getResourceId()).thenReturn(7L);
            when(backupDetailsDao.findDetails(AblestackBackupFrameworkUtils.RESTORE_TARGET_VM_ID_DETAIL, "1", null)).thenReturn(List.of(target));
        } else {
            when(backupDao.listByVmId(null, 1L)).thenReturn(List.of(backup));
        }
        return details;
    }

    @Test public void activeBackupsBlockCompetingRequestsAndNativeSnapshotChangesForAllProviders() {
        for (String provider : PROVIDERS) {
            backup(provider, 1L, Backup.Status.BackingUp, false);
            assertThrows(CloudRuntimeException.class, () -> guard.checkBackup(1L, provider));
            assertThrows(CloudRuntimeException.class, () -> guard.checkRestore(1L, provider, false));
            assertThrows(CloudRuntimeException.class, () -> guard.checkClone(1L));
            for (String operation : List.of("create", "delete", "revert", "archive", "force delete")) {
                assertThrows(CloudRuntimeException.class, () -> guard.checkSnapshotOperation(root, operation));
            }
            assertThrows(CloudRuntimeException.class, () -> guard.checkVolumeOperation(root, "resize"));
            assertThrows(CloudRuntimeException.class, () -> guard.checkVolumeOperation(root, "migrate"));
        }
    }

    @Test public void aRestoreFromAnotherVmBlocksItsRunningAttachTargetUntilCleanupIsConfirmed() {
        Map<String, String> details = backup("ablestack-veeam", 2L, Backup.Status.BackedUp, true);
        details.put(AblestackBackupFrameworkUtils.RESTORE_TARGET_VM_ID_DETAIL, "1");
        details.put(AblestackBackupFrameworkUtils.RESTORE_JOB_STATE_DETAIL, "RUNNING");
        assertThrows(CloudRuntimeException.class, () -> guard.checkBackup(1L, "ablestack-nas"));
        assertThrows(CloudRuntimeException.class, () -> guard.checkClone(1L));
        assertThrows(CloudRuntimeException.class, () -> guard.checkSnapshotOperation(root, "delete"));
        details.put(AblestackBackupFrameworkUtils.RESTORE_JOB_STATE_DETAIL, "FAILED");
        details.put(ThirdPartyBackupRestore.CLEANUP_STATE_KEY, "WAITING");
        assertThrows(CloudRuntimeException.class, () -> guard.checkRestore(1L, "ablestack-commvault", true));
        details.put(ThirdPartyBackupRestore.CLEANUP_STATE_KEY, "COMPLETED");
        guard.checkBackup(1L, "ablestack-netbackup");
    }

    @Test public void completedRecoveryPointsPermitNewBackupsAndNativeSnapshots() {
        backup("ablestack-nas", 1L, Backup.Status.BackedUp, false);
        guard.checkBackup(1L, "ablestack-nas");
        guard.checkRestore(1L, "ablestack-nas", false);
        guard.checkSnapshotOperation(root, "create");
        guard.checkSnapshotOperation(root, "delete");
    }

    @Test public void acceptedRestoreCanEnterItsWorkerWithoutAdmittingAnotherRequest() {
        Map<String, String> details = backup("ablestack-nas", 1L, Backup.Status.BackedUp, false);
        details.put(AblestackBackupFrameworkUtils.RESTORE_TARGET_VM_ID_DETAIL, "1");
        details.put(AblestackBackupFrameworkUtils.RESTORE_JOB_STATE_DETAIL, "STARTING");
        assertThrows(CloudRuntimeException.class, () -> guard.checkRestore(1L, "ablestack-nas", false));
        guard.checkRestore(1L, "ablestack-nas", false, 7L);
        assertThrows(CloudRuntimeException.class, () -> guard.checkRestore(1L, "ablestack-nas", false, 8L));
        assertThrows(CloudRuntimeException.class, () -> guard.checkBackup(1L, "ablestack-nas"));
    }

    @Test public void restoringIntoAnotherVmDoesNotBlockTheOriginalVm() {
        Map<String, String> details = backup("ablestack-commvault", 1L, Backup.Status.BackedUp, false);
        details.put(AblestackBackupFrameworkUtils.RESTORE_TARGET_VM_ID_DETAIL, "2");
        details.put(AblestackBackupFrameworkUtils.RESTORE_JOB_STATE_DETAIL, "RUNNING");
        guard.checkBackup(1L, "ablestack-commvault");
    }

    @Test public void restoreVmStateBlocksSnapshotsEvenBeforeAProviderJobIdExists() {
        when(vm.getBackupOfferingId()).thenReturn(9L);
        BackupOfferingVO offering = mock(BackupOfferingVO.class);
        when(offeringDao.findByIdIncludingRemoved(9L)).thenReturn(offering);
        when(vm.getState()).thenReturn(State.Restoring);
        for (String provider : PROVIDERS) {
            when(offering.getProvider()).thenReturn(provider);
            assertThrows(CloudRuntimeException.class, () -> guard.checkSnapshotOperation(root, "delete"));
            assertThrows(CloudRuntimeException.class, () -> guard.checkClone(1L));
        }
    }

    @Test public void missingSourceVolumesFailBeforeProviderDispatch() {
        when(volumeDao.findByInstance(1L)).thenReturn(List.of());
        assertThrows(CloudRuntimeException.class, () -> guard.checkBackup(1L, "ablestack-veeam"));
        assertThrows(CloudRuntimeException.class, () -> guard.checkRestore(1L, "ablestack-veeam", false));
    }

    private void rejectsDependenciesForEveryOperation() {
        for (String provider : PROVIDERS) {
            assertThrows(CloudRuntimeException.class, () -> guard.checkDependencies(1L, provider));
            assertThrows(CloudRuntimeException.class, () -> guard.checkBackup(1L, provider));
            assertThrows(CloudRuntimeException.class, () -> guard.checkRestore(1L, provider, false));
            assertThrows(CloudRuntimeException.class, () -> guard.checkRestore(1L, provider, true));
            assertThrows(CloudRuntimeException.class, () -> guard.checkRestore(1L, provider, false, 7L));
        }
    }

    @Test public void vmSnapshotsBlockConfigurationAndEveryRestoreEntryWithoutDeletingThem() {
        when(vmSnapshotDao.findByVm(1L)).thenReturn(List.of(mock(VMSnapshotVO.class)));
        rejectsDependenciesForEveryOperation();
        verify(vmSnapshotDao, org.mockito.Mockito.never()).remove(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test public void sourceCommitAndClonePhasesRemainBlockedEvenAfterFlattenReportsDone() {
        for (String phase : List.of("preparing", "prepared", "ready", "committing", "failed", "unknown", "")) {
            when(vmInstanceDetailsDao.listDetailsKeyPairs(1L)).thenReturn(Map.of("clone.fast.source.phase", phase,
                    "clone.fast.status", "done"));
            rejectsDependenciesForEveryOperation();
            when(vmInstanceDetailsDao.listDetailsKeyPairs(1L)).thenReturn(Map.of("clone.fast.clone.phase", phase));
            rejectsDependenciesForEveryOperation();
        }
    }

    @Test public void unresolvedDependenciesOnAnyDataDiskBlockTheWholeVm() {
        VolumeVO data = mock(VolumeVO.class);
        when(data.getId()).thenReturn(11L);
        when(volumeDao.findByInstance(1L)).thenReturn(List.of(root, data));
        for (Map<String, String> marker : List.of(Map.of("clone.fast.flatten.status", "done"),
                Map.of("clone.fast.flatten.status", "paused"), Map.of("clone.fast.role", "source"),
                Map.of("linked.clone.backing.snapshot.id", "123"), Map.of("linked.clone.backing.path", "rbd:pool/parent@snapshot"),
                Map.of("clone.vm.type", "linked"), Map.of("clone.vm.type", "unknown"), Map.of("clone.vm.source.volume.id", "10"))) {
            when(volumeDetailsDao.listDetailsKeyPairs(11L)).thenReturn(marker);
            rejectsDependenciesForEveryOperation();
        }
        verify(volumeDetailsDao, org.mockito.Mockito.never()).removeDetails(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test public void independentFullCloneProvenanceDoesNotBlockBackupOrRestore() {
        when(volumeDetailsDao.listDetailsKeyPairs(10L)).thenReturn(Map.of("clone.vm.type", "full", "clone.vm.source.volume.id", "99"));
        for (String provider : PROVIDERS) {
            guard.checkDependencies(1L, provider);
            guard.checkBackup(1L, provider);
            guard.checkRestore(1L, provider, false);
            guard.checkRestore(1L, provider, true);
        }
    }

    @Test public void unknownDependencyQueriesNeverBecomeAnEmptyInventory() {
        when(vmSnapshotDao.findByVm(1L)).thenReturn(null);
        rejectsDependenciesForEveryOperation();
        when(vmSnapshotDao.findByVm(1L)).thenReturn(List.of());
        when(vmInstanceDetailsDao.listDetailsKeyPairs(1L)).thenReturn(null);
        rejectsDependenciesForEveryOperation();
        when(vmInstanceDetailsDao.listDetailsKeyPairs(1L)).thenReturn(Map.of());
        when(volumeDetailsDao.listDetailsKeyPairs(10L)).thenReturn(null);
        rejectsDependenciesForEveryOperation();
    }

    @Test public void nativeRbdSnapshotsBlockReplacementButPermitBackupAndNewVolumeAttach() {
        StoragePoolVO pool = mock(StoragePoolVO.class);
        when(pool.getPoolType()).thenReturn(Storage.StoragePoolType.RBD);
        when(poolDao.findById(20L)).thenReturn(pool);
        SnapshotVO snapshot = mock(SnapshotVO.class);
        when(snapshot.getState()).thenReturn(Snapshot.State.BackedUp);
        when(snapshotDao.listByVolumeIdIncludingRemoved(10L)).thenReturn(List.of(snapshot));
        for (String provider : PROVIDERS) {
            guard.checkBackup(1L, provider);
            guard.checkRestore(1L, provider, true);
            assertThrows(CloudRuntimeException.class, () -> guard.checkRestore(1L, provider, false));
            assertThrows(CloudRuntimeException.class, () -> guard.checkRestore(1L, provider, false, 7L));
        }
    }

    @Test public void missingRestoreStorageOrSnapshotInventoryFailsBeforeReplacement() {
        when(poolDao.findById(20L)).thenReturn(null);
        for (String provider : PROVIDERS) {
            assertThrows(CloudRuntimeException.class, () -> guard.checkRestore(1L, provider, false));
        }
        StoragePoolVO pool = mock(StoragePoolVO.class);
        when(pool.getPoolType()).thenReturn(Storage.StoragePoolType.RBD);
        when(poolDao.findById(20L)).thenReturn(pool);
        when(snapshotDao.listByVolumeIdIncludingRemoved(10L)).thenReturn(null);
        for (String provider : PROVIDERS) {
            assertThrows(CloudRuntimeException.class, () -> guard.checkRestore(1L, provider, false));
        }
    }

    @Test public void legacyProvidersDoNotUseTheAblestackAdmissionRules() {
        for (String provider : CLOUDSTACK_PROVIDERS) {
            guard.checkDependencies(1L, provider);
            guard.checkBackup(1L, provider);
            guard.checkRestore(1L, provider, false);
            guard.checkRestore(1L, provider, true);
        }
        verifyNoInteractions(vmDao, volumeDao, snapshotDao, backupDao, backupDetailsDao, offeringDao, backupVolumeGuard,
                vmInstanceDetailsDao, volumeDetailsDao, vmSnapshotDao, poolDao, snapshotDetailsDao);
    }

    @Test public void cloudStackProvidersDoNotLockOrRejectVolumeChangesDuringTheirBackupsOrRestores() {
        when(vm.getBackupOfferingId()).thenReturn(9L);
        lenient().when(vm.getState()).thenReturn(State.Restoring);
        lenient().when(root.getState()).thenReturn(Volume.State.Restoring);
        for (String provider : CLOUDSTACK_PROVIDERS) {
            backup(provider, 1L, Backup.Status.Restoring, false);
            assertNull(guard.acquireVolumeChangeGuard(root));
            guard.checkVolumeOperation(root, "resize");
            guard.checkVolumeOperation(root, "migrate");
            guard.checkSnapshotOperation(root, "create");
            guard.checkSnapshotOperation(root, "delete");
        }
        verifyNoInteractions(backupVolumeGuard, volumeDao, snapshotDao);
    }

    @Test public void assignedAblestackProvidersStillSerializeVolumeChanges() {
        when(vm.getBackupOfferingId()).thenReturn(9L);
        BackupOfferingVO offering = mock(BackupOfferingVO.class);
        when(offeringDao.findByIdIncludingRemoved(9L)).thenReturn(offering);
        BackupVolumeGuard.Lease lease = mock(BackupVolumeGuard.Lease.class);
        when(backupVolumeGuard.acquire(1L)).thenReturn(lease);
        for (String provider : PROVIDERS) {
            when(offering.getProvider()).thenReturn(provider);
            try (BackupVolumeGuard.Lease acquired = guard.acquireVolumeChangeGuard(root)) {
                assertSame(lease, acquired);
            }
        }
        verify(backupVolumeGuard, times(PROVIDERS.size())).acquire(1L);
        verify(lease, times(PROVIDERS.size())).close();
    }

    @Test public void anUnassignedTargetIsLockedWhileAnAblestackRestoreFromAnotherVmIsActive() {
        Map<String, String> details = backup("ablestack-veeam", 2L, Backup.Status.BackedUp, true);
        details.put(AblestackBackupFrameworkUtils.RESTORE_TARGET_VM_ID_DETAIL, "1");
        details.put(AblestackBackupFrameworkUtils.RESTORE_JOB_STATE_DETAIL, "RUNNING");
        BackupVolumeGuard.Lease lease = mock(BackupVolumeGuard.Lease.class);
        when(backupVolumeGuard.acquire(1L)).thenReturn(lease);
        assertSame(lease, guard.acquireVolumeChangeGuard(root));
        assertThrows(CloudRuntimeException.class, () -> guard.checkVolumeOperation(root, "migrate"));
    }

    @Test public void completedAblestackHistoryDoesNotAddLocksAfterTheOfferingIsRemoved() {
        backup("ablestack-nas", 1L, Backup.Status.BackedUp, false);
        assertNull(guard.acquireVolumeChangeGuard(root));
        guard.checkVolumeOperation(root, "resize");
        verifyNoInteractions(backupVolumeGuard);
    }
}
