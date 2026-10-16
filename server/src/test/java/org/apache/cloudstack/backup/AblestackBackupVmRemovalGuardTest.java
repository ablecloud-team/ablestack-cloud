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
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.util.Collections;
import java.util.HashMap;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.apache.cloudstack.backup.dao.BackupDao;
import org.apache.cloudstack.backup.dao.BackupOfferingDao;
import org.apache.cloudstack.backup.dao.BackupScheduleDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.utils.exception.CloudRuntimeException;

@RunWith(MockitoJUnitRunner.class)
public class AblestackBackupVmRemovalGuardTest {
    @Mock private BackupDao backupDao;
    @Mock private BackupOfferingDao offeringDao;
    @Mock private BackupScheduleDao scheduleDao;
    @Mock private VMInstanceDao vmDao;
    @InjectMocks private AblestackBackupVmRemovalGuard guard;
    private VMInstanceVO vm;
    @Before public void setup() {
        vm = mock(VMInstanceVO.class);
        when(vm.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(vmDao.findById(1L)).thenReturn(vm);
        when(vmDao.findByIdIncludingRemoved(1L)).thenReturn(vm);
    }
    private BackupVO retainedBackup(String provider) {
        when(vm.getState()).thenReturn(com.cloud.vm.VirtualMachine.State.Stopped);
        when(vm.getBackupOfferingId()).thenReturn(null);
        BackupVO backup = new BackupVO();
        backup.setStatus(Backup.Status.BackedUp);
        backup.setBackupOfferingId(3L);
        backup.setDetails(new HashMap<>());
        when(backupDao.listByVmId(null, 1L)).thenReturn(Collections.singletonList(backup));
        BackupOfferingVO offering = mock(BackupOfferingVO.class);
        when(offering.getProvider()).thenReturn(provider);
        when(offeringDao.findByIdIncludingRemoved(3L)).thenReturn(offering);
        return backup;
    }

    @Test public void vmRemovalPreservesCompletedAblestackBackups() {
        for (String provider : new String[]{"ablestack-nas", "ablestack-commvault", "ablestack-netbackup", "ablestack-veeam"}) {
            retainedBackup(provider);
            guard.checkVmRemoval(1L);
            assertEquals(true, guard.supports(1L));
        }
        verify(backupDao, never()).remove(anyLong());
    }

    @Test public void vmRemovalRequiresStoppedVmAndRemovedProtection() {
        when(vm.getState()).thenReturn(com.cloud.vm.VirtualMachine.State.Running);
        assertThrows(CloudRuntimeException.class, () -> guard.checkVmRemoval(1L));
        when(vm.getState()).thenReturn(com.cloud.vm.VirtualMachine.State.Restoring);
        assertThrows(CloudRuntimeException.class, () -> guard.checkVmRemoval(1L));
        when(vm.getState()).thenReturn(com.cloud.vm.VirtualMachine.State.Stopped);
        when(vm.getBackupOfferingId()).thenReturn(3L);
        assertThrows(CloudRuntimeException.class, () -> guard.checkVmRemoval(1L));
        when(vm.getBackupOfferingId()).thenReturn(null);
        when(scheduleDao.listByVM(1L)).thenReturn(Collections.singletonList(mock(BackupScheduleVO.class)));
        assertThrows(CloudRuntimeException.class, () -> guard.checkVmRemoval(1L));
    }

    @Test public void vmRemovalRejectsActiveBackupStates() {
        BackupVO backup = retainedBackup("ablestack-nas");
        for (Backup.Status status : new Backup.Status[]{Backup.Status.Allocated, Backup.Status.Queued, Backup.Status.BackingUp,
                Backup.Status.ReadyForImageTransfer, Backup.Status.FinalizingImageTransfer, Backup.Status.Restoring}) {
            backup.setStatus(status);
            assertThrows(CloudRuntimeException.class, () -> guard.checkVmRemoval(1L));
        }
    }

    @Test public void vmRemovalWaitsForAllPhysicalCleanup() {
        BackupVO backup = retainedBackup("ablestack-netbackup");
        for (String key : new String[]{AblestackBackupFrameworkUtils.BACKUP_CLEANUP_STATE_DETAIL,
                ThirdPartyBackupManifest.CLEANUP_STATE_KEY, ThirdPartyBackupManifest.SOURCE_CLEANUP_STATE_KEY,
                ThirdPartyBackupManifest.JOB_CLEANUP_STATE_KEY, ThirdPartyBackupManifest.FINALIZATION_STATE_KEY,
                ThirdPartyBackupRestore.CLEANUP_STATE_KEY}) {
            backup.getDetails().put(key, "WAITING");
            assertThrows(CloudRuntimeException.class, () -> guard.checkVmRemoval(1L));
            backup.getDetails().put(key, "COMPLETED");
        }
        guard.checkVmRemoval(1L);
    }

    @Test public void vmRemovalWaitsForRestoreAndCancellationTermination() {
        BackupVO backup = retainedBackup("ablestack-nas");
        backup.getDetails().put(AblestackBackupFrameworkUtils.RESTORE_JOB_STATE_DETAIL, "RUNNING");
        assertThrows(CloudRuntimeException.class, () -> guard.checkVmRemoval(1L));
        backup.getDetails().put(AblestackBackupFrameworkUtils.RESTORE_JOB_STATE_DETAIL, "COMPLETED");
        backup.getDetails().put(AblestackBackupFrameworkUtils.RESTORE_JOB_CLEANUP_ID_DETAIL, "restore-job");
        assertThrows(CloudRuntimeException.class, () -> guard.checkVmRemoval(1L));
        backup.getDetails().remove(AblestackBackupFrameworkUtils.RESTORE_JOB_CLEANUP_ID_DETAIL);
        backup.getDetails().put(AblestackBackupFrameworkUtils.BACKUP_CANCEL_REQUESTED_DETAIL, "true");
        assertThrows(CloudRuntimeException.class, () -> guard.checkVmRemoval(1L));
        backup.getDetails().put(AblestackBackupFrameworkUtils.BACKUP_CLEANUP_STATE_DETAIL, "COMPLETED");
        guard.checkVmRemoval(1L);
    }

    @Test public void vmRemovalRejectsUnknownProviderOrVm() {
        retainedBackup("kboss");
        assertThrows(CloudRuntimeException.class, () -> guard.checkVmRemoval(1L));
        assertThrows(CloudRuntimeException.class, () -> guard.checkVmRemoval(2L));
    }

    @Test public void existingProvidersAndUnknownHistoryKeepTheirOriginalPath() {
        for (String provider : new String[]{"nas", "commvault", "netbackup", "veeam", "kboss", "dummy"}) {
            retainedBackup(provider);
            assertEquals(false, guard.supports(1L));
        }
        when(offeringDao.findByIdIncludingRemoved(3L)).thenReturn(null);
        assertEquals(false, guard.supports(1L));
        when(backupDao.listByVmId(null, 1L)).thenReturn(Collections.emptyList());
        assertEquals(false, guard.supports(1L));
    }

    @Test public void offeringRemovalPreservesBackupsOnRunningVmAndRejectsPendingOperations() {
        BackupVO backup = retainedBackup("ablestack-commvault");
        when(vm.getState()).thenReturn(com.cloud.vm.VirtualMachine.State.Running);
        guard.checkOfferingRemoval(1L);
        backup.setStatus(Backup.Status.BackingUp);
        assertThrows(CloudRuntimeException.class, () -> guard.checkOfferingRemoval(1L));
        backup.setStatus(Backup.Status.BackedUp);
        backup.getDetails().put(AblestackBackupFrameworkUtils.RESTORE_JOB_STATE_DETAIL, "RUNNING");
        assertThrows(CloudRuntimeException.class, () -> guard.checkOfferingRemoval(1L));
        backup.getDetails().clear();
        backup.getDetails().put(ThirdPartyBackupManifest.CLEANUP_STATE_KEY, "WAITING");
        assertThrows(CloudRuntimeException.class, () -> guard.checkOfferingRemoval(1L));
        backup.getDetails().clear();
        backup.getDetails().put(ThirdPartyBackupManifest.DELETE_PROGRESS_KEY, "{\"state\":\"WAITING\"}");
        assertThrows(CloudRuntimeException.class, () -> guard.checkOfferingRemoval(1L));
        verify(backupDao, never()).remove(anyLong());
    }

    @Test public void offeringRemovalCanFinishDuringVmExpungeWhenNoBackupsRemain() {
        when(vm.getState()).thenReturn(com.cloud.vm.VirtualMachine.State.Expunging);
        when(backupDao.listByVmId(null, 1L)).thenReturn(Collections.emptyList());
        guard.checkOfferingRemoval(1L);
    }
}
