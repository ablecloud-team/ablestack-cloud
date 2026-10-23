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
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;

import org.apache.cloudstack.backup.dao.BackupDao;
import org.apache.cloudstack.backup.dao.BackupDetailsDao;
import org.apache.cloudstack.backup.dao.BackupOfferingDao;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.framework.async.AsyncCallFuture;
import org.apache.cloudstack.storage.command.CommandResult;
import org.apache.cloudstack.storage.datastore.db.VolumeDataStoreDao;
import org.apache.commons.lang3.StringUtils;

import com.cloud.storage.Volume;
import com.cloud.storage.DataStoreRole;
import com.cloud.storage.VolumeApiService;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.dao.AccountDao;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.UserVmManager;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachineManager;
import com.cloud.vm.dao.UserVmDao;
import com.google.gson.Gson;

/** Deletes only the resources allocated for a failed ABLESTACK create-from-backup attempt. */
public class AblestackRestoreInstanceCleanup extends ManagerBase {
    static final String CONTEXT_KEY = "ablestack.restore.instance.resources";
    static final String PENDING_KEY = "ablestack.restore.instance.cleanup.pending";
    static final String REASON_KEY = "ablestack.restore.instance.cleanup.reason";

    @Inject private BackupDao backupDao;
    @Inject private BackupDetailsDao detailsDao;
    @Inject private BackupOfferingDao offeringDao;
    @Inject private UserVmDao vmDao;
    @Inject private VolumeDao volumeDao;
    @Inject private VirtualMachineManager vmManager;
    @Inject private UserVmManager userVmManager;
    @Inject private VolumeApiService volumeApiService;
    @Inject private VolumeDataFactory volumeFactory;
    @Inject private VolumeDataStoreDao volumeStoreDao;
    @Inject private AccountDao accountDao;

    private boolean supports(BackupVO backup) {
        if (backup == null) { return false; }
        BackupOfferingVO offering = offeringDao.findByIdIncludingRemoved(backup.getBackupOfferingId());
        String provider = offering == null ? null : offering.getProvider();
        return BackupProviderNameUtils.isNasFamily(provider) || BackupProviderNameUtils.isCommvaultFamily(provider)
                || BackupProviderNameUtils.isNetBackupFamily(provider) || BackupProviderNameUtils.isVeeamFamily(provider);
    }

    /** Capture identities before ordinary VM expunge detaches DATA disks. */
    public void prepare(long backupId, long vmId) {
        BackupVO backup = backupDao.findById(backupId);
        if (!supports(backup)) { return; }
        withLock(backupId, () -> {
            if (isPending(backupId)) {
                throw new CloudRuntimeException("Previous instance cleanup is still pending for backup " + backupId);
            }
            UserVmVO vm = vmDao.findById(vmId);
            if (vm == null || vmId == backup.getVmId() || vm.getAccountId() != backup.getAccountId()) {
                throw new CloudRuntimeException("Unable to verify newly allocated restore instance " + vmId);
            }
            Resources resources = read(backupId);
            if (resources != null && resources.vmId != vmId) {
                throw new CloudRuntimeException("Another instance creation is already recorded for backup " + backupId);
            }
            if (resources == null) {
                resources = new Resources();
                resources.vmId = vmId;
                resources.vmUuid = vm.getUuid();
                resources.accountId = vm.getAccountId();
            }
            if (!Objects.equals(resources.vmUuid, vm.getUuid())) {
                throw new CloudRuntimeException("Restore instance identity changed");
            }
            captureVolumes(resources);
            save(backupId, CONTEXT_KEY, new Gson().toJson(resources));
        });
    }

    public boolean isPending(long backupId) {
        return detailsDao.findDetail(backupId, PENDING_KEY) != null;
    }

    public void complete(long backupId, long vmId) {
        if (!supports(backupDao.findById(backupId))) { return; }
        withLock(backupId, () -> {
            Resources resources = read(backupId);
            if (resources != null && resources.vmId == vmId && !isPending(backupId)) {
                detailsDao.removeDetail(backupId, CONTEXT_KEY);
            }
        });
    }

    /** Returns false only when the existing non-ABLESTACK cleanup path should be used. */
    public boolean cleanup(long backupId, long vmId) {
        BackupVO backup = backupDao.findById(backupId);
        if (!supports(backup)) { return false; }
        Resources requested = read(backupId);
        if (requested == null && isCompleted(backupId, vmId)) { return true; }
        validateTarget(backup, vmId, requested);
        // Keep the retry request even when another management worker holds the operation lock.
        save(backupId, PENDING_KEY, String.valueOf(vmId));
        withLock(backupId, () -> {
            Resources resources = read(backupId);
            if (resources == null && isCompleted(backupId, vmId)) {
                detailsDao.removeDetail(backupId, PENDING_KEY);
                return;
            }
            validateTarget(backup, vmId, resources);
            if (!resources.cleanupStarted) {
                captureVolumes(resources);
                resources.cleanupStarted = true;
                save(backupId, CONTEXT_KEY, new Gson().toJson(resources));
            }
            phase(backupId, vmId, "CLEANUP_REQUIRED");
            try {
                deleteResources(resources);
                Transaction.execute((TransactionCallback<Void>) status -> {
                    phase(backupId, vmId, "CLEANUP_COMPLETED");
                    detailsDao.removeDetail(backupId, PENDING_KEY);
                    detailsDao.removeDetail(backupId, REASON_KEY);
                    detailsDao.removeDetail(backupId, CONTEXT_KEY);
                    return null;
                });
            } catch (Exception e) {
                if (e instanceof InterruptedException) { Thread.currentThread().interrupt(); }
                save(backupId, REASON_KEY, StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()));
                logger.warn("Failed restore instance cleanup remains pending. backupId=[{}], vmId=[{}]: {}",
                        backupId, vmId, e.getMessage(), e);
            }
        });
        return true;
    }

    private void validateTarget(BackupVO backup, long vmId, Resources resources) {
        if (resources == null || resources.vmId != vmId || vmId == backup.getVmId()) {
            throw new CloudRuntimeException("Restore resource ownership is unavailable for instance " + vmId);
        }
    }

    private boolean isCompleted(long backupId, long vmId) {
        BackupDetailVO target = detailsDao.findDetail(backupId, AblestackBackupFrameworkUtils.RESTORE_TARGET_VM_ID_DETAIL);
        BackupDetailVO step = detailsDao.findDetail(backupId, AblestackBackupFrameworkUtils.RESTORE_JOB_STEP_DETAIL);
        return target != null && String.valueOf(vmId).equals(target.getValue())
                && step != null && "CLEANUP_COMPLETED".equals(step.getValue());
    }

    public void reconcilePending() {
        for (BackupDetailVO detail : detailsDao.findDetails(PENDING_KEY)) {
            try {
                cleanup(detail.getResourceId(), Long.parseLong(detail.getValue()));
            } catch (Exception e) {
                logger.warn("Unable to retry failed restore instance cleanup for backup [{}]: {}",
                        detail.getResourceId(), e.getMessage(), e);
            }
        }
    }

    private void deleteResources(Resources resources) throws Exception {
        for (VolumeRef ref : resources.volumes) {
            validateVolume(resources, ref, volumeDao.findByIdIncludingRemoved(ref.id));
        }
        UserVmVO vm = vmDao.findByIdIncludingRemoved(resources.vmId);
        if (vm != null && (!Objects.equals(vm.getUuid(), resources.vmUuid) || vm.getAccountId() != resources.accountId)) {
            throw new CloudRuntimeException("Restore instance ownership changed");
        }
        if (vm != null && vm.getRemoved() == null) {
            if (vm.getState() == null || !Set.of(VirtualMachine.State.Stopped, VirtualMachine.State.Error, VirtualMachine.State.Destroyed,
                    VirtualMachine.State.Expunging).contains(vm.getState())) {
                throw new CloudRuntimeException("Restore instance is not ready for cleanup: " + vm.getState());
            }
            for (VolumeVO current : volumeDao.findIncludingRemovedByInstanceAndType(resources.vmId, null)) {
                if (current.getRemoved() == null && resources.volumes.stream().noneMatch(
                        ref -> ref.id == current.getId() && Objects.equals(ref.uuid, current.getUuid()))) {
                    throw new CloudRuntimeException("Unrecorded volume is attached to restore instance: " + current.getUuid());
                }
            }
            vmManager.destroy(vm.getUuid(), true);
            vm = vmDao.findById(resources.vmId);
            if (vm != null && !userVmManager.expunge(vm)) {
                throw new CloudRuntimeException("Instance expunge has not completed");
            }
            if (vmDao.findById(resources.vmId) != null) {
                throw new CloudRuntimeException("Instance removal has not completed");
            }
        }
        for (VolumeRef ref : resources.volumes) {
            VolumeVO volume = volumeDao.findByIdIncludingRemoved(ref.id);
            if (volume == null) { continue; }
            validateVolume(resources, ref, volume);
            if (isDeleted(volume)) { continue; }
            if (volume.getRemoved() != null) {
                retryRemovedVolume(volume);
            } else if (!volumeApiService.deleteVolume(volume.getId(), accountDao.findById(resources.accountId))) {
                throw new CloudRuntimeException("Restore volume deletion failed: " + ref.uuid);
            }
            volume = volumeDao.findByIdIncludingRemoved(ref.id);
            if (volume != null && !isDeleted(volume)) {
                throw new CloudRuntimeException("Restore volume deletion is unconfirmed: " + ref.uuid);
            }
        }
    }

    private void validateVolume(Resources resources, VolumeRef ref, VolumeVO volume) {
        if (volume != null && (!Objects.equals(ref.uuid, volume.getUuid()) || volume.getAccountId() != resources.accountId
                || (volume.getInstanceId() != null && volume.getInstanceId() != resources.vmId))) {
            throw new CloudRuntimeException("Restore volume ownership changed: " + ref.uuid);
        }
    }

    private void captureVolumes(Resources resources) {
        for (VolumeVO volume : volumeDao.findIncludingRemovedByInstanceAndType(resources.vmId, null)) {
            if (resources.volumes.stream().noneMatch(ref -> ref.id == volume.getId())) {
                VolumeRef ref = new VolumeRef();
                ref.id = volume.getId();
                ref.uuid = volume.getUuid();
                resources.volumes.add(ref);
            }
        }
    }

    private boolean isDeleted(VolumeVO volume) {
        return volume.getRemoved() != null && (volume.getState() == Volume.State.Expunged
                // VM expunge can destroy an allocated volume without ever provisioning it.
                || ((volume.getState() == Volume.State.Allocated || volume.getState() == Volume.State.Destroy)
                    && volume.getPoolId() == null && StringUtils.isBlank(volume.getPath())
                    && volumeStoreDao.findByVolume(volume.getId()) == null));
    }

    /** Removed rows are excluded by the ordinary volume reaper; retry the recorded target explicitly. */
    private void retryRemovedVolume(VolumeVO volume) throws Exception {
        if (!Set.of(Volume.State.Destroy, Volume.State.Expunging).contains(volume.getState())) {
            throw new CloudRuntimeException("Removed restore volume deletion is unconfirmed: " + volume.getUuid());
        }
        VolumeInfo info = volumeFactory.getVolume(volume.getId());
        if (info == null || info.getDataStore() == null || info.getDataStore().getRole() != DataStoreRole.Primary
                || StringUtils.isBlank(volume.getPath())) {
            throw new CloudRuntimeException("Restore volume storage is unavailable: " + volume.getUuid());
        }
        if (volume.getState() == Volume.State.Destroy) {
            if (!volumeDao.updateState(Volume.State.Destroy, Volume.Event.ExpungingRequested,
                    Volume.State.Expunging, volume, null)) {
                throw new CloudRuntimeException("Unable to retry removed restore volume " + volume.getUuid());
            }
            volume = volumeDao.findByIdIncludingRemoved(volume.getId());
        }
        AsyncCallFuture<CommandResult> future = new AsyncCallFuture<>();
        info.getDataStore().getDriver().deleteAsync(info.getDataStore(), info, future);
        CommandResult result = future.get(60, TimeUnit.SECONDS);
        if (result == null || !result.isSuccess()) {
            throw new CloudRuntimeException("Restore volume storage deletion failed: "
                    + (result == null ? "response unavailable" : result.getResult()));
        }
        if (!volumeDao.updateState(volume.getState(), Volume.Event.OperationSucceeded, Volume.State.Expunged, volume, null)) {
            throw new CloudRuntimeException("Unable to confirm deleted restore volume " + volume.getUuid());
        }
    }

    private Resources read(long backupId) {
        BackupDetailVO detail = detailsDao.findDetail(backupId, CONTEXT_KEY);
        if (detail == null) { return null; }
        Resources resources = new Gson().fromJson(detail.getValue(), Resources.class);
        if (resources == null || resources.vmId <= 0 || resources.accountId <= 0
                || StringUtils.isBlank(resources.vmUuid) || resources.volumes == null
                || resources.volumes.stream().anyMatch(ref -> ref == null || ref.id <= 0 || StringUtils.isBlank(ref.uuid))) {
            throw new CloudRuntimeException("Restore resource record is incomplete");
        }
        return resources;
    }

    private void phase(long backupId, long vmId, String step) {
        save(backupId, AblestackBackupFrameworkUtils.RESTORE_OPERATION_TYPE_DETAIL, AblestackBackupFrameworkUtils.RESTORE_OPERATION_CREATE_INSTANCE);
        save(backupId, AblestackBackupFrameworkUtils.RESTORE_TARGET_VM_ID_DETAIL, String.valueOf(vmId));
        save(backupId, AblestackBackupFrameworkUtils.RESTORE_JOB_STATE_DETAIL, "FAILED");
        save(backupId, AblestackBackupFrameworkUtils.RESTORE_JOB_STEP_DETAIL, step);
        save(backupId, AblestackBackupFrameworkUtils.RESTORE_CREATE_INSTANCE_PROGRESS_DETAIL,
                "{\"state\":\"FAILED\",\"step\":\"" + step + "\",\"progress\":0}");
    }

    private void save(long backupId, String key, String value) {
        Transaction.execute((TransactionCallback<Void>) status -> {
            detailsDao.addDetail(backupId, key, value, false);
            return null;
        });
    }

    private void withLock(long backupId, Runnable action) {
        GlobalLock lock = GlobalLock.getInternLock("backup.restore." + backupId);
        try {
            if (!lock.lock(5)) { throw new CloudRuntimeException("Unable to lock restore instance cleanup"); }
            try { action.run(); } finally { lock.unlock(); }
        } finally { lock.releaseRef(); }
    }

    private static final class Resources {
        long vmId;
        String vmUuid;
        long accountId;
        boolean cleanupStarted;
        List<VolumeRef> volumes = new ArrayList<>();
    }

    private static final class VolumeRef {
        long id;
        String uuid;
    }
}
