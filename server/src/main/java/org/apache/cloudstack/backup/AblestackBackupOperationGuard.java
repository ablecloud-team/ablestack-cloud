// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE for copyright ownership.
// Licensed under the Apache License, Version 2.0 (the "License");
// http://www.apache.org/licenses/LICENSE-2.0 . Distributed AS IS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
package org.apache.cloudstack.backup;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;

import org.apache.cloudstack.backup.dao.BackupDao;
import org.apache.cloudstack.backup.dao.BackupDetailsDao;
import org.apache.cloudstack.backup.dao.BackupOfferingDao;
import org.apache.commons.lang3.StringUtils;

import com.cloud.storage.Snapshot;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.SnapshotDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.VMInstanceDao;

/** Admission checks for user requests, before VM/volume state changes or provider dispatch. */
public class AblestackBackupOperationGuard extends ManagerBase {
    @Inject private VMInstanceDao vmDao;
    @Inject private VolumeDao volumeDao;
    @Inject private SnapshotDao snapshotDao;
    @Inject private BackupDao backupDao;
    @Inject private BackupDetailsDao backupDetailsDao;
    @Inject private BackupOfferingDao offeringDao;
    @Inject private BackupVolumeGuard backupVolumeGuard;

    private static final Set<String> TERMINAL = Set.of("COMPLETED", "FAILED", "CANCELED", "CANCELLED", "INTERRUPTED");

    public static boolean supports(String provider) {
        return BackupProviderNameUtils.isNasFamily(provider) || BackupProviderNameUtils.isCommvaultFamily(provider)
                || BackupProviderNameUtils.isNetBackupFamily(provider) || BackupProviderNameUtils.isVeeamFamily(provider);
    }

    public void checkBackup(long vmId, String provider) {
        checkRequest(vmId, provider, false, false, null);
    }

    public void checkRestore(long vmId, String provider, boolean volumeAttach) {
        checkRestore(vmId, provider, volumeAttach, null);
    }

    public void checkRestore(long vmId, String provider, boolean volumeAttach, Long acceptedBackupId) {
        checkRequest(vmId, provider, true, volumeAttach, acceptedBackupId);
    }

    private void checkRequest(long vmId, String provider, boolean restore, boolean volumeAttach, Long acceptedBackupId) {
        if (!supports(provider)) { return; }
        VMInstanceVO vm = vmDao.findByIdIncludingRemoved(vmId);
        if (vm == null || vm.getRemoved() != null || vm.getState() == null) {
            throw new CloudRuntimeException("BACKUP_STATE_UNKNOWN: The target VM is unavailable; refresh and retry.");
        }
        boolean destroyedRestore = restore && !volumeAttach && vm.getState() == VirtualMachine.State.Destroyed;
        boolean allowed = vm.getState() == VirtualMachine.State.Stopped || destroyedRestore
                || ((!restore || volumeAttach) && vm.getState() == VirtualMachine.State.Running);
        if (!allowed) {
            throw new CloudRuntimeException("BACKUP_VM_STATE: Cannot start " + (restore ? "restore" : "backup")
                    + " while VM " + vm.getUuid() + " is " + vm.getState() + ". Wait for its current operation to complete.");
        }
        List<VolumeVO> volumes = volumeDao.findByInstance(vmId);
        if (volumes == null || volumes.isEmpty()) {
            throw new CloudRuntimeException("BACKUP_VOLUME_STATE_UNKNOWN: No target volumes could be verified.");
        }
        for (VolumeVO volume : volumes) {
            // Destroyed-VM restoration already has explicit Destroy/Expunged -> Restoring transitions.
            boolean retained = destroyedRestore && (volume.getState() == Volume.State.Destroy || volume.getState() == Volume.State.Expunged);
            if (volume.getState() != Volume.State.Ready && !retained) {
                throw new CloudRuntimeException("BACKUP_VOLUME_STATE: Cannot start " + (restore ? "restore" : "backup")
                        + " while volume " + volume.getUuid() + " is " + volume.getState()
                        + ". Complete its current operation first.");
            }
            if (!snapshotDao.listByStatus(volume.getId(), Snapshot.State.Creating, Snapshot.State.CreatedOnPrimary, Snapshot.State.BackingUp).isEmpty()) {
                throw new CloudRuntimeException("BACKUP_SNAPSHOT_BUSY: A volume snapshot operation is still in progress for " + volume.getUuid() + ".");
            }
        }
        checkActiveOperations(vmId, acceptedBackupId);
    }

    /** Completed recovery points do not block native volume snapshots. */
    public void checkSnapshotOperation(VolumeVO volume, String operation) {
        checkVolumeOperation(volume, operation + " a volume snapshot");
    }

    /** Standard CloudStack providers must not acquire the additional ABLESTACK request lock. */
    public BackupVolumeGuard.Lease acquireVolumeChangeGuard(VolumeVO volume) {
        if (volume == null || volume.getInstanceId() == null) { return null; }
        long vmId = volume.getInstanceId();
        VMInstanceVO vm = vmDao.findByIdIncludingRemoved(vmId);
        if (!hasAblestackOffering(vm) && activeOperationError(vmId, null) == null) { return null; }
        return backupVolumeGuard.acquire(vmId);
    }

    private boolean hasAblestackOffering(VMInstanceVO vm) {
        BackupOffering offering = vm == null || vm.getBackupOfferingId() == null ? null
                : offeringDao.findByIdIncludingRemoved(vm.getBackupOfferingId());
        return offering != null && supports(offering.getProvider());
    }

    public void checkVolumeOperation(VolumeVO volume, String operation) {
        if (volume == null || volume.getInstanceId() == null) { return; }
        long vmId = volume.getInstanceId();
        VMInstanceVO vm = vmDao.findByIdIncludingRemoved(vmId);
        if (hasAblestackOffering(vm) && (volume.getState() == Volume.State.Restoring
                || (vm != null && vm.getState() == VirtualMachine.State.Restoring))) {
            throw new CloudRuntimeException("BACKUP_RESTORE_BUSY: Cannot " + operation + " while VM restoration is in progress.");
        }
        checkActiveOperations(vmId, null);
    }

    private void checkActiveOperations(long vmId, Long acceptedBackupId) {
        String error = activeOperationError(vmId, acceptedBackupId);
        if (error != null) { throw new CloudRuntimeException(error); }
    }

    private String activeOperationError(long vmId, Long acceptedBackupId) {
        Set<Long> ids = new LinkedHashSet<>();
        for (Backup backup : backupDao.listByVmId(null, vmId)) { ids.add(backup.getId()); }
        // A volume restore can target a different VM without changing that VM's power state.
        for (BackupDetailVO detail : backupDetailsDao.findDetails(AblestackBackupFrameworkUtils.RESTORE_TARGET_VM_ID_DETAIL, String.valueOf(vmId), null)) {
            ids.add(detail.getResourceId());
        }
        for (Long id : ids) {
            BackupVO backup = backupDao.findById(id);
            if (backup == null) { continue; }
            BackupOffering offering = offeringDao.findByIdIncludingRemoved(backup.getBackupOfferingId());
            if (offering == null || !supports(offering.getProvider())) { continue; }
            if (backup.getVmId() == vmId && (backup.getStatus() == Backup.Status.BackingUp || backup.getStatus() == Backup.Status.Restoring)) {
                return "BACKUP_OPERATION_BUSY: A backup or restore is already running for this VM.";
            }
            backupDao.loadDetails(backup);
            String target = backup.getDetail(AblestackBackupFrameworkUtils.RESTORE_TARGET_VM_ID_DETAIL);
            boolean targetsVm = StringUtils.isBlank(target) ? backup.getVmId() == vmId : String.valueOf(vmId).equals(target);
            String state = backup.getDetail(AblestackBackupFrameworkUtils.RESTORE_JOB_STATE_DETAIL);
            if (!id.equals(acceptedBackupId) && targetsVm && ((StringUtils.isNotBlank(state) && !TERMINAL.contains(state))
                    || (StringUtils.isBlank(state) && StringUtils.isNotBlank(backup.getDetail(AblestackBackupFrameworkUtils.RESTORE_JOB_ID_DETAIL)))
                    || StringUtils.isNotBlank(backup.getDetail(AblestackBackupFrameworkUtils.RESTORE_JOB_CLEANUP_ID_DETAIL))
                    || "WAITING".equals(backup.getDetail(ThirdPartyBackupRestore.CLEANUP_STATE_KEY))
                    || StringUtils.isNotBlank(backup.getDetail(AblestackRestoreInstanceCleanup.PENDING_KEY)))) {
                return "BACKUP_RESTORE_BUSY: Restore or rollback completion has not been confirmed for this VM.";
            }
        }
        return null;
    }
}
