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

import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import org.apache.cloudstack.backup.dao.BackupDao;
import org.apache.cloudstack.backup.dao.BackupOfferingDao;
import org.apache.cloudstack.backup.dao.BackupScheduleDao;
import org.apache.commons.lang3.StringUtils;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.dao.VMInstanceDao;

/** Provider-specific removal policy for independently retained ABLESTACK recovery points. */
public class AblestackBackupVmRemovalGuard extends ManagerBase {
    @Inject private BackupDao backupDao;
    @Inject private BackupOfferingDao offeringDao;
    @Inject private BackupScheduleDao scheduleDao;
    @Inject private VMInstanceDao vmDao;

    public boolean supports(long vmId) {
        VMInstanceVO vm = vmDao.findById(vmId);
        if (vm == null || vm.getHypervisorType() != HypervisorType.KVM) { return false; }
        List<Backup> backups = backupDao.listByVmId(null, vmId);
        return backups != null && !backups.isEmpty() && backups.stream().allMatch(backup -> {
            BackupOfferingVO offering = offeringDao.findByIdIncludingRemoved(backup.getBackupOfferingId());
            return offering != null && isRetainedBackupProvider(offering.getProvider());
        });
    }

    private boolean isRetainedBackupProvider(String provider) {
        return BackupProviderNameUtils.isNasFamily(provider) || BackupProviderNameUtils.isNetBackupFamily(provider)
                || BackupProviderNameUtils.isVeeamFamily(provider) || BackupProviderNameUtils.isCommvaultFamily(provider);
    }

    /** Offering removal must leave in-flight backup, restore and cleanup tracking attached. */
    public void checkOfferingRemoval(long vmId) {
        VMInstanceVO vm = vmDao.findByIdIncludingRemoved(vmId);
        if (vm == null || vm.getState() == null) {
            throw new CloudRuntimeException("BACKUP_STATE_UNKNOWN: Unable to verify VM state before removing its backup offering.");
        }
        if (vm.getState() == State.Restoring) {
            throw new CloudRuntimeException("BACKUP_BUSY: Complete VM restoration before removing its backup offering.");
        }
        for (Backup backup : backupDao.listByVmId(null, vmId)) {
            checkBackupOperations(backup);
        }
    }

    /** VM removal preserves completed external backups; ordinary topology changes remain protected. */
    public void checkVmRemoval(long vmId) {
        VMInstanceVO vm = vmDao.findById(vmId);
        if (vm == null) {
            throw new CloudRuntimeException("VOLUME_BACKUP_STATE_UNKNOWN: Unable to verify VM removal state.");
        }
        if (vm.getState() == null || !Set.of(State.Stopped, State.Destroyed, State.Expunging).contains(vm.getState())) {
            throw new CloudRuntimeException("VOLUME_BACKUP_VM_STATE: Stop the VM before removing its volumes.");
        }
        if (vm.getHypervisorType() != HypervisorType.KVM) {
            throw new CloudRuntimeException("VOLUME_BACKUP_POLICY: ABLESTACK VM removal requires KVM.");
        }
        if (vm.getBackupOfferingId() != null || !scheduleDao.listByVM(vmId).isEmpty()) {
            throw new CloudRuntimeException("VOLUME_BACKUP_POLICY: Remove the backup offering and schedules before removing the VM.");
        }
        for (Backup backup : backupDao.listByVmId(null, vmId)) {
            BackupOfferingVO offering = offeringDao.findByIdIncludingRemoved(backup.getBackupOfferingId());
            String provider = offering == null ? null : offering.getProvider();
            if (!isRetainedBackupProvider(provider)) {
                throw new CloudRuntimeException("VOLUME_BACKUP_BACKUP_EXISTS: VM removal with preserved backups is unavailable for this provider.");
            }
            checkBackupOperations(backup);
        }
    }

    private void checkBackupOperations(Backup backup) {
        if (backup.getStatus() == null || !Set.of(Backup.Status.BackedUp, Backup.Status.Hidden, Backup.Status.Removed,
                Backup.Status.Expunged, Backup.Status.Failed, Backup.Status.Canceled, Backup.Status.Error).contains(backup.getStatus())) {
            throw new CloudRuntimeException("VOLUME_BACKUP_BUSY: Complete the backup or restore before removing the VM or its backup offering.");
        }
        BackupVO tracked = backup instanceof BackupVO ? (BackupVO) backup : backupDao.findById(backup.getId());
        if (tracked == null) {
            throw new CloudRuntimeException("VOLUME_BACKUP_STATE_UNKNOWN: Unable to verify backup cleanup before removal.");
        }
        backupDao.loadDetails(tracked);
        if (StringUtils.isNotBlank(tracked.getDetail(ThirdPartyBackupManifest.DELETE_PROGRESS_KEY))) {
            throw new CloudRuntimeException("VOLUME_BACKUP_BUSY: Complete backup deletion before removal.");
        }
        for (String key : new String[]{AblestackBackupFrameworkUtils.BACKUP_CLEANUP_STATE_DETAIL,
                ThirdPartyBackupManifest.CLEANUP_STATE_KEY, ThirdPartyBackupManifest.SOURCE_CLEANUP_STATE_KEY,
                ThirdPartyBackupManifest.JOB_CLEANUP_STATE_KEY, ThirdPartyBackupManifest.FINALIZATION_STATE_KEY,
                ThirdPartyBackupRestore.CLEANUP_STATE_KEY}) {
            String state = tracked.getDetail(key);
            if (StringUtils.isNotBlank(state) && !Set.of("COMPLETED", "NOT_REQUIRED", "NONE").contains(state)) {
                throw new CloudRuntimeException("VOLUME_BACKUP_BUSY: Complete backup and restore cleanup before removing the VM or its backup offering.");
            }
        }
        String restoreState = tracked.getDetail(AblestackBackupFrameworkUtils.RESTORE_JOB_STATE_DETAIL);
        if ((StringUtils.isNotBlank(restoreState) && !Set.of("COMPLETED", "FAILED", "CANCELED", "CANCELLED", "INTERRUPTED").contains(restoreState))
                || (StringUtils.isBlank(restoreState) && StringUtils.isNotBlank(tracked.getDetail(AblestackBackupFrameworkUtils.RESTORE_JOB_ID_DETAIL)))
                || StringUtils.isNotBlank(tracked.getDetail(AblestackBackupFrameworkUtils.RESTORE_JOB_CLEANUP_ID_DETAIL))
                || (Boolean.parseBoolean(tracked.getDetail(AblestackBackupFrameworkUtils.BACKUP_CANCEL_REQUESTED_DETAIL))
                    && !"COMPLETED".equals(tracked.getDetail(AblestackBackupFrameworkUtils.BACKUP_CLEANUP_STATE_DETAIL)))) {
            throw new CloudRuntimeException("VOLUME_BACKUP_BUSY: Backup or restore termination is unconfirmed; removal is unavailable.");
        }
    }

}
