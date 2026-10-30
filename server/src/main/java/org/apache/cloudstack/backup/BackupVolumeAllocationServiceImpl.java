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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import javax.inject.Inject;
import org.apache.cloudstack.backup.dao.BackupDao;
import org.apache.cloudstack.backup.dao.BackupDetailsDao;
import org.apache.cloudstack.resourcedetail.dao.DiskOfferingDetailsDao;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.host.Host;
import com.cloud.storage.Storage;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.VolumeAllocationLifecycle;
import com.cloud.storage.VolumeAllocationPolicy;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.storage.dao.VolumeDetailsDao;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.Gson;

/** Freeze restore intent before dispatch; accept observations only for the exact committed request. */
public class BackupVolumeAllocationServiceImpl extends ManagerBase implements BackupVolumeAllocationService {
    @Inject private VolumeDetailsDao volumeDetailsDao;
    @Inject private DiskOfferingDetailsDao offeringDetailsDao;
    @Inject private VolumeDao volumeDao;
    @Inject private PrimaryDataStoreDao poolDao;
    @Inject private BackupDao backupDao;
    @Inject private BackupDetailsDao detailsDao;
    @Inject private AgentManager agentManager;
    @Inject private AblestackSharedRawBackupService sharedRaw;
    private static final Gson JSON = new Gson();

    private Map<String, String> details(Volume volume) {
        Map<String, String> values = new LinkedHashMap<>();
        if (volume.getId() > 0) {
            volumeDetailsDao.listDetails(volume.getId()).forEach(detail -> values.put(detail.getName(), detail.getValue()));
        } else if (volume.getDiskOfferingId() != null) {
            offeringDetailsDao.listDetails(volume.getDiskOfferingId()).forEach(detail -> values.put(detail.getName(), detail.getValue()));
        }
        return values;
    }

    @Override public BackupVolumeAllocation capture(Volume volume) {
        Map<String, String> intent = VolumeAllocationLifecycle.intent(details(volume));
        if (intent.isEmpty()) {
            var sourcePool = volume.getFormat() != Storage.ImageFormat.RAW || volume.getPoolId() == null ? null : poolDao.findById(volume.getPoolId());
            if (volume.getFormat() == Storage.ImageFormat.RAW && sourcePool != null
                    && sourcePool.getPoolType() == Storage.StoragePoolType.SharedMountPoint)
                throw new CloudRuntimeException("BACKUP_ALLOCATION_INVALID: GFS2 RAW requires an explicit allocation contract");
            return null;
        }
        if (volume.getId() == 0 && volume instanceof VolumeVO) {
            // New restore targets inherit the selected offering's explicit image format.
            ((VolumeVO) volume).setFormat(Storage.ImageFormat.valueOf(intent.get(VolumeAllocationPolicy.FORMAT).toUpperCase(Locale.ROOT)));
        }
        var pool = volume.getPoolId() == null ? null : poolDao.findById(volume.getPoolId());
        if (pool == null) throw new CloudRuntimeException("BACKUP_ALLOCATION_INVALID: volume pool is unavailable");
        BackupVolumeAllocation policy = new BackupVolumeAllocation();
        policy.volumeUuid = volume.getUuid(); policy.volumeId = volume.getId(); policy.accountId = volume.getAccountId();
        policy.bytes = volume.getSize(); policy.poolUuid = pool.getUuid();
        policy.backend = pool.getPoolType() == Storage.StoragePoolType.RBD ? "RBD"
                : pool.getPoolType() == Storage.StoragePoolType.SharedMountPoint ? "GFS2" : "UNSUPPORTED";
        policy.format = volume.getFormat() == null ? null : volume.getFormat().toString().toLowerCase(Locale.ROOT);
        policy.intent = intent; policy.validate();
        return policy;
    }

    private String key(String jobId) { return BackupVolumeAllocation.restoreKey(jobId); }

    @Override public boolean requiresOfflineFull(List<? extends Volume> volumes) { return sharedRaw.requiresOfflineFull(volumes); }
    @Override public AblestackSharedRawBackup.Plan protectBackup(Backup backup, Host host, List<? extends Volume> volumes) {
        List<Volume> ordered = new java.util.ArrayList<>(volumes);
        ordered.sort(java.util.Comparator.comparing(Volume::getDeviceId));
        for (Volume volume : ordered) capture(volume);
        return sharedRaw.protect(backup, host, backup.getUuid(), "BACKUP", ordered);
    }
    @Override public void releaseBackup(Backup backup) { sharedRaw.release(backup, backup.getUuid(), "BACKUP"); }

    @Override public Map<String, BackupVolumeAllocation> configureRestore(Backup backup, Host host, String jobId,
            List<? extends Volume> volumes, List<String> paths) {
        if (volumes.size() != paths.size()) throw new CloudRuntimeException("Restore allocation paths differ from their volumes");
        Map<String, BackupVolumeAllocation> policies = new LinkedHashMap<>();
        for (int i = 0; i < volumes.size(); i++) {
            BackupVolumeAllocation policy = capture(volumes.get(i));
            if (policy != null && policies.put(paths.get(i), policy) != null) throw new CloudRuntimeException("Duplicate allocation destination");
        }
        if (policies.isEmpty()) return null; // Old volumes and old backups retain their established conversion path.
        if (host == null || jobId == null || !jobId.matches("[A-Za-z0-9_.-]+")) throw new CloudRuntimeException("Missing restore allocation owner");
        BackupVolumeAllocation.Result expected = new BackupVolumeAllocation.Result();
        expected.jobId = jobId; expected.policies = policies;
        var old = detailsDao.findDetail(backup.getId(), key(jobId));
        if (old != null && !old.getValue().equals(JSON.toJson(expected))) throw new CloudRuntimeException("Restore allocation request changed");
        BackupVolumeAllocation.Result checked = request(host.getId(), "CHECK", expected);
        if (!same(expected.policies, checked.policies)) throw new CloudRuntimeException("Agent allocation preflight differs from the request");
        if (old == null) detailsDao.addDetail(backup.getId(), key(jobId), JSON.toJson(expected), false);
        if (policies.values().stream().anyMatch(policy -> "GFS2".equals(policy.backend) && "raw".equals(policy.format))) {
            try { sharedRaw.protect(backup, host, jobId, "RESTORE", volumes); }
            catch (RuntimeException failure) {
                if (detailsDao.findDetail(backup.getId(), AblestackSharedRawBackup.restoreKey(jobId)) != null) {
                    // No restore engine was dispatched. Keep enough context to retry
                    // partial Host-marker cleanup after a management restart.
                    detailsDao.addDetail(backup.getId(), AblestackBackupFrameworkUtils.RESTORE_HOST_ID_DETAIL, String.valueOf(host.getId()), false);
                    detailsDao.addDetail(backup.getId(), AblestackBackupFrameworkUtils.RESTORE_JOB_CLEANUP_ID_DETAIL, jobId, false);
                    try { releaseRestore(backup, jobId); }
                    catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
                }
                throw failure;
            }
        }
        return policies;
    }

    private BackupVolumeAllocation.Result request(long hostId, String action, BackupVolumeAllocation.Result expected) {
        try {
            Answer answer = agentManager.send(hostId, new BackupRestoreAllocationCommand(action, expected.jobId, expected.policies));
            if (!(answer instanceof BackupRestoreAllocationAnswer) || !answer.getResult()) {
                throw new CloudRuntimeException("BACKUP_ALLOCATION_AGENT_UNSUPPORTED: a compatible Host Agent is required; "
                        + (answer == null ? "no answer" : answer.getDetails()));
            }
            BackupVolumeAllocation.Result result = ((BackupRestoreAllocationAnswer) answer).getAllocation();
            if (result == null || result.version != 1 || !expected.jobId.equals(result.jobId)) throw new CloudRuntimeException("Invalid allocation receipt owner");
            return result;
        } catch (com.cloud.exception.AgentUnavailableException | com.cloud.exception.OperationTimedoutException error) {
            throw new CloudRuntimeException("Restore allocation confirmation is unavailable", error);
        }
    }

    private boolean same(Object first, Object second) { return JSON.toJson(first).equals(JSON.toJson(second)); }

    @Override public void releaseRestore(Backup backup, String jobId) {
        if (detailsDao.findDetail(backup.getId(), AblestackSharedRawBackup.restoreKey(jobId)) != null)
            sharedRaw.release(backup, jobId, "RESTORE");
    }

    @Override public void completeRestore(Backup backup) {
        backupDao.loadDetails((BackupVO) backup);
        String jobId = backup.getDetail(AblestackBackupFrameworkUtils.RESTORE_JOB_ID_DETAIL);
        if (jobId == null) return;
        var record = detailsDao.findDetail(backup.getId(), key(jobId));
        if (record == null) return;
        if (detailsDao.findDetail(backup.getId(), key(jobId) + ".completed") != null) {
            releaseRestore(backup, jobId);
            return;
        }
        BackupVolumeAllocation.Result expected = JSON.fromJson(record.getValue(), BackupVolumeAllocation.Result.class);
        String host = backup.getDetail(AblestackBackupFrameworkUtils.RESTORE_HOST_ID_DETAIL);
        if (host == null) throw new CloudRuntimeException("Restore allocation Host is missing");
        BackupVolumeAllocation.Result result = request(Long.parseLong(host), "RESULT", expected);
        if (!same(expected.policies, result.policies) || !result.evidence.keySet().equals(expected.policies.keySet())) {
            throw new CloudRuntimeException("Committed allocation result differs from its restore request");
        }
        Map<Long, Map<String, String>> updates = new LinkedHashMap<>();
        expected.policies.forEach((path, policy) -> {
            policy.validate();
            VolumeVO volume = volumeDao.findByUuid(policy.volumeUuid);
            var pool = volume == null || volume.getPoolId() == null ? null : poolDao.findById(volume.getPoolId());
            Map<String, String> evidence = result.evidence.get(path);
            if (volume == null || pool == null || !policy.poolUuid.equals(pool.getUuid()) || !Objects.equals(volume.getSize(), policy.bytes)
                    || !policy.format.equalsIgnoreCase(String.valueOf(volume.getFormat()))
                    || !same(policy.intent, VolumeAllocationLifecycle.intent(evidence))
                    || !policy.backend.equals(evidence.get(VolumeAllocationPolicy.BACKEND))
                    || !"copy".equals(evidence.get(VolumeAllocationPolicy.OPERATION))
                    || !"verified".equals(VolumeAllocationPolicy.describe(evidence, null, policy.format).get("evidence"))) {
                throw new CloudRuntimeException("Restored disk allocation could not be verified: " + policy.volumeUuid);
            }
            Map<String, String> current = VolumeAllocationLifecycle.intent(details(volume));
            if ((policy.volumeId > 0 && current.isEmpty()) || (!current.isEmpty() && !same(current, policy.intent))) throw new CloudRuntimeException("Destination allocation contract changed");
            long observed = Long.parseLong(evidence.get(VolumeAllocationPolicy.OBSERVED_BYTES));
            if (observed < 0 || (("full".equals(policy.intent.get(VolumeAllocationPolicy.POLICY))
                    || "falloc".equals(policy.intent.get(VolumeAllocationPolicy.POLICY))) && observed < policy.bytes)) {
                throw new CloudRuntimeException("Restored disk is not fully allocated as requested");
            }
            updates.put(volume.getId(), evidence);
        });
        com.cloud.utils.db.Transaction.execute(new com.cloud.utils.db.TransactionCallbackNoReturn() {
            @Override public void doInTransactionWithoutResult(com.cloud.utils.db.TransactionStatus status) {
                updates.forEach((volumeId, evidence) -> {
                    volumeDetailsDao.listDetails(volumeId).stream().filter(detail -> detail.getName().startsWith("allocation."))
                            .forEach(detail -> volumeDetailsDao.removeDetail(volumeId, detail.getName()));
                    evidence.forEach((name, value) -> volumeDetailsDao.addDetail(volumeId, name, value, false));
                });
                detailsDao.addDetail(backup.getId(), key(jobId) + ".completed", "{\"allocationCompleted\":true}", false);
            }
        });
        releaseRestore(backup, jobId);
    }
}
