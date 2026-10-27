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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import javax.inject.Inject;
import org.apache.cloudstack.backup.dao.BackupDetailsDao;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolDetailsDao;
import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.host.Host;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.storage.Storage;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.VMInstanceDao;
import com.google.gson.Gson;

/** ABLESTACK only: durable file ownership, serialized with power and disk identity mutations. */
public class AblestackSharedRawBackupServiceImpl extends ManagerBase implements AblestackSharedRawBackupService {
    @Inject private VolumeDao volumes;
    @Inject private VMInstanceDao vms;
    @Inject private PrimaryDataStoreDao pools;
    @Inject private StoragePoolDetailsDao poolDetails;
    @Inject private DiskOfferingDao offerings;
    @Inject private HostDao hosts;
    @Inject private AgentManager agents;
    @Inject private BackupDetailsDao backupDetails;
    private static final Gson JSON = new Gson();
    private static final String LOCK = "ablestack.backup.shared.raw.lifecycle";
    private final ThreadLocal<Integer> depth = ThreadLocal.withInitial(() -> 0);
    private final ThreadLocal<GlobalLock> held = new ThreadLocal<>();

    static String marker(String path) {
        return AblestackSharedRawBackup.MARKER + "." + digest(path);
    }
    private static String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder key = new StringBuilder();
            for (byte octet : hash) key.append(String.format("%02x", octet & 255));
            return key.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    /** Pool detail values are limited to 255 characters; the full plan belongs in backup_details. */
    static String ownership(AblestackSharedRawBackup.Plan plan) {
        var owner = new com.google.gson.JsonObject();
        owner.addProperty("version", 2);
        owner.addProperty("backupId", plan.backupId);
        owner.addProperty("jobId", plan.jobId);
        owner.addProperty("hostId", plan.hostId);
        owner.addProperty("operation", plan.operation);
        owner.addProperty("planHash", digest(JSON.toJson(plan)));
        String value = owner.toString();
        if (value.length() > 255) throw new CloudRuntimeException("SHARED_RAW_UNKNOWN: ownership identity exceeds the pool detail limit");
        return value;
    }
    private boolean owns(String value, String record, AblestackSharedRawBackup.Plan plan) {
        if (ownership(plan).equals(value) || record.equals(value)) return true;
        // Recover only the exact 255-character truncation produced by the old DAO writer.
        // The complete backup/job/Host/operation identity must fit before the truncation.
        String identity = "{\"version\":1,\"backupId\":" + plan.backupId + ",\"jobId\":" + JSON.toJson(plan.jobId)
                + ",\"hostId\":" + plan.hostId + ",\"operation\":" + JSON.toJson(plan.operation) + ",";
        return value != null && value.length() == 255 && record.length() > 255 && identity.length() < 255
                && value.startsWith(identity) && record.startsWith(value);
    }
    private void verifyOwnership(AblestackSharedRawBackup.Plan plan, String record) {
        for (var disk : plan.disks) {
            var owner = poolDetails.findDetail(disk.poolId, marker(disk.path));
            if (owner != null && !owns(owner.getValue(), record, plan)) throw new CloudRuntimeException("Shared RAW ownership changed");
        }
    }
    private Lease lock() {
        if (depth.get() == 0) {
            GlobalLock lock = GlobalLock.getInternLock(LOCK);
            if (!lock.lock(5)) { lock.releaseRef(); throw new CloudRuntimeException("SHARED_RAW_BUSY: disk protection is being changed"); }
            held.set(lock);
        }
        depth.set(depth.get() + 1);
        return () -> {
            int remaining = depth.get() - 1;
            depth.set(remaining);
            if (remaining == 0) {
                GlobalLock lock = held.get(); held.remove(); depth.remove();
                try { lock.unlock(); } finally { lock.releaseRef(); }
            }
        };
    }
    private boolean sharedFile(Volume volume) {
        var pool = volume.getPoolId() == null ? null : pools.findById(volume.getPoolId());
        return pool != null && pool.getPoolType() == Storage.StoragePoolType.SharedMountPoint;
    }
    private boolean raw(Volume volume) {
        if (volume.getFormat() != Storage.ImageFormat.RAW) return false;
        var pool = volume.getPoolId() == null ? null : pools.findById(volume.getPoolId());
        if (pool == null) throw new CloudRuntimeException("SHARED_RAW_UNKNOWN: RAW source pool is unavailable");
        return pool.getPoolType() == Storage.StoragePoolType.SharedMountPoint;
    }
    @Override public boolean requiresOfflineFull(List<? extends Volume> source) {
        if (source == null) throw new CloudRuntimeException("SHARED_RAW_UNKNOWN: source inventory is unavailable");
        return source.stream().anyMatch(this::raw);
    }

    private void checkMarker(Long pool, String path) {
        if (pool == null || path == null || path.isBlank()) return;
        var active = poolDetails.findDetail(pool, marker(path));
        if (active != null) {
            var plan = JSON.fromJson(active.getValue(), AblestackSharedRawBackup.Plan.class);
            throw new CloudRuntimeException("SHARED_RAW_PROTECTED: " + plan.operation + " job " + plan.jobId + " owns this shared file");
        }
    }
    @Override public Lease guardStart(long vmId) { return guardVolume(vmId, null, null, null); }
    @Override public Lease guardVolume(Long vmId, Long volumeId, Long poolId, String path) {
        List<VolumeVO> attached = vmId == null ? List.of() : volumes.findByInstance(vmId);
        if (attached == null) throw new CloudRuntimeException("SHARED_RAW_UNKNOWN: VM disk inventory is unavailable");
        Volume selected = volumeId == null ? null : volumes.findById(volumeId);
        var destination = poolId == null ? null : pools.findById(poolId);
        if (attached.stream().noneMatch(this::sharedFile) && (selected == null || !sharedFile(selected))
                && (destination == null || destination.getPoolType() != Storage.StoragePoolType.SharedMountPoint)) return () -> {};
        Lease lease = lock();
        try {
            if (vmId != null) for (Volume volume : volumes.findByInstance(vmId)) checkMarker(volume.getPoolId(), volume.getPath());
            if (volumeId != null) {
                Volume volume = volumes.findById(volumeId);
                if (volume == null) throw new CloudRuntimeException("SHARED_RAW_UNKNOWN: disk identity is unavailable");
                checkMarker(volume.getPoolId(), volume.getPath());
                if (path != null) checkMarker(poolId == null ? volume.getPoolId() : poolId, path);
            }
            checkMarker(poolId, path);
            return lease;
        } catch (RuntimeException e) { lease.close(); throw e; }
    }
    @Override public void checkSources(long vmId) {
        List<VolumeVO> source = volumes.findByInstance(vmId);
        if (!requiresOfflineFull(source)) return;
        try (Lease lease = lock()) { build(source, "BACKUP"); }
    }

    private List<AblestackSharedRawBackup.Disk> build(List<? extends Volume> source, String operation) {
        Set<Long> targets = new HashSet<>();
        source.forEach(volume -> { if (volume.getInstanceId() != null) targets.add(volume.getInstanceId()); });
        List<AblestackSharedRawBackup.Disk> result = new ArrayList<>();
        Set<String> identities = new HashSet<>();
        for (Volume volume : source) {
            var pool = volume.getPoolId() == null ? null : pools.findById(volume.getPoolId());
            String path = volume.getPath();
            if (pool == null || pool.getPoolType() != Storage.StoragePoolType.SharedMountPoint || path == null
                    || !path.matches("[A-Za-z0-9][A-Za-z0-9_.-]*") || pool.getClusterId() == null
                    || (volume.getFormat() != Storage.ImageFormat.RAW && volume.getFormat() != Storage.ImageFormat.QCOW2)) {
                throw new CloudRuntimeException("SHARED_RAW_UNKNOWN: offline RAW requires known GFS2 file identities for every target disk");
            }
            if (!identities.add(pool.getUuid() + ":" + path)) throw new CloudRuntimeException("SHARED_RAW_UNKNOWN: duplicate physical disk in this VM");
            var disk = new AblestackSharedRawBackup.Disk();
            disk.poolUuid = pool.getUuid(); disk.poolId = pool.getId(); disk.path = path;
            disk.format = volume.getFormat().toString().toLowerCase(Locale.ROOT); disk.bytes = volume.getSize();
            var offering = volume.getDiskOfferingId() == null ? null : offerings.findByIdIncludingRemoved(volume.getDiskOfferingId());
            if (offering == null) throw new CloudRuntimeException("SHARED_RAW_UNKNOWN: disk offering and shareable setting are unavailable");
            disk.shareable = offering.getShareable();
            List<VolumeVO> peers = volumes.findBySharedVolume(pool.getId(), path);
            if (peers == null || (volume.getId() > 0 && peers.stream().noneMatch(peer -> peer.getId() == volume.getId()))) {
                throw new CloudRuntimeException("SHARED_RAW_UNKNOWN: connected-volume inventory is incomplete");
            }
            // The offering identifies shared intent; actual aliases are inspected even if a flag is stale.
            for (Volume peer : peers) {
                if (peer.getFormat() != volume.getFormat() || !Objects.equals(peer.getSize(), volume.getSize()))
                    throw new CloudRuntimeException("SHARED_RAW_UNKNOWN: aliases disagree about format or capacity");
                disk.volumeIds.add(peer.getId());
                if (peer.getInstanceId() != null) disk.vmIds.add(peer.getInstanceId());
            }
            if (volume.getInstanceId() != null) disk.vmIds.add(volume.getInstanceId());
            disk.volumeIds = new ArrayList<>(new TreeSet<>(disk.volumeIds));
            disk.vmIds = new ArrayList<>(new TreeSet<>(disk.vmIds));
            for (long vmId : disk.vmIds) {
                var vm = vms.findById(vmId);
                if (vm == null || (vm.getState() != VirtualMachine.State.Stopped
                        && !("RESTORE".equals(operation) && targets.contains(vmId) && vm.getState() == VirtualMachine.State.Restoring))) {
                    String message = "RESTORE".equals(operation)
                            ? "RESTORE_VM_NOT_STOPPED: VM must be stopped before restoring a backup"
                            : "BACKUP_VM_NOT_STOPPED: VM must be stopped before creating a backup";
                    throw new CloudRuntimeException(message + "; vmId=" + vmId);
                }
            }
            result.add(disk);
        }
        return result;
    }
    private String key(String job, String operation) { return "BACKUP".equals(operation) ? AblestackSharedRawBackup.BACKUP_KEY : AblestackSharedRawBackup.restoreKey(job); }
    private void control(long host, String action, AblestackSharedRawBackup.Plan plan) {
        try {
            Answer answer = agents.send(host, new AblestackSharedRawBackupCommand(action, plan));
            if (answer == null || !answer.getResult() || !JSON.toJson(plan).equals(answer.getDetails())) {
                throw new CloudRuntimeException("SHARED_RAW_UNCONFIRMED: " + action + " was not confirmed by Host " + host);
            }
        } catch (com.cloud.exception.AgentUnavailableException | com.cloud.exception.OperationTimedoutException e) {
            throw new CloudRuntimeException("SHARED_RAW_UNCONFIRMED: cannot inspect every Host", e);
        }
    }
    @Override public AblestackSharedRawBackup.Plan protect(Backup backup, Host host, String jobId, String operation, List<? extends Volume> source) {
        if (!requiresOfflineFull(source)) return null;
        if ("BACKUP".equals(operation) && !"FULL".equalsIgnoreCase(backup.getType())) throw new CloudRuntimeException("GFS2_RAW_INCREMENTAL_UNSUPPORTED: offline RAW backups must be FULL");
        try (Lease lease = lock()) {
            var prior = backupDetails.findDetail(backup.getId(), key(jobId, operation));
            if (prior != null) {
                if (backupDetails.findDetail(backup.getId(), key(jobId, operation) + ".released") != null)
                    throw new CloudRuntimeException("SHARED_RAW_RETIRED: this offline attempt has already released its source");
                if (backupDetails.findDetail(backup.getId(), key(jobId, operation) + ".prepareFailed") != null)
                    throw new CloudRuntimeException("SHARED_RAW_UNCONFIRMED: previous preparation cleanup must finish before retry");
                var existing = JSON.fromJson(prior.getValue(), AblestackSharedRawBackup.Plan.class);
                if (existing == null || existing.hostId != host.getId() || !jobId.equals(existing.jobId) || !operation.equals(existing.operation)
                        || !JSON.toJson(existing.disks).equals(JSON.toJson(build(source, operation))))
                    throw new CloudRuntimeException("SHARED_RAW_UNCONFIRMED: offline source or Worker identity changed");
                return existing;
            }
            var plan = new AblestackSharedRawBackup.Plan();
            plan.backupId = backup.getId(); plan.jobId = jobId; plan.hostId = host.getId(); plan.operation = operation;
            plan.disks = build(source, operation);
            var owner = source.stream().map(Volume::getInstanceId).filter(Objects::nonNull).findFirst().orElse(null);
            plan.vmName = owner == null ? null : vms.findById(owner).getInstanceName();
            plan.vmUuid = owner == null ? null : vms.findById(owner).getUuid();
            if (backup.getDetails() != null) {
                for (String name : List.of(org.apache.cloudstack.api.ApiConstants.SERVICE_OFFERING_ID,
                        org.apache.cloudstack.api.ApiConstants.TEMPLATE_ID, org.apache.cloudstack.api.ApiConstants.OS_TYPE_ID,
                        org.apache.cloudstack.api.ApiConstants.OS_NAME, org.apache.cloudstack.api.ApiConstants.NICS,
                        org.apache.cloudstack.api.ApiConstants.VM_SETTINGS)) {
                    String value = backup.getDetail(name);
                    if (value != null) plan.vmConfiguration.put(name, value);
                }
            }
            for (var disk : plan.disks) checkMarker(disk.poolId, disk.path);
            String ownerValue = ownership(plan);
            Set<Long> checked = new HashSet<>();
            try {
                for (var disk : plan.disks) {
                    var pool = pools.findById(disk.poolId);
                    if (!Objects.equals(pool.getClusterId(), host.getClusterId())) throw new CloudRuntimeException("SHARED_RAW_UNKNOWN: disks and Worker must use the same GFS2 cluster");
                    List<com.cloud.host.HostVO> cluster = hosts.findByClusterId(pool.getClusterId(), Host.Type.Routing);
                    if (cluster == null || cluster.isEmpty()) throw new CloudRuntimeException("SHARED_RAW_UNKNOWN: cluster Host inventory is unavailable");
                    for (Host candidate : cluster) {
                        if (candidate.getStatus() != Status.Up) throw new CloudRuntimeException("SHARED_RAW_UNKNOWN: every cluster Host must be reachable");
                        if (checked.add(candidate.getId())) control(candidate.getId(), "PREPARE", plan);
                    }
                }
                Transaction.execute((com.cloud.utils.db.TransactionCallback<Boolean>) status -> {
                    for (var disk : plan.disks) poolDetails.addDetail(disk.poolId, marker(disk.path), ownerValue, false);
                    backupDetails.addDetail(backup.getId(), key(jobId, operation), JSON.toJson(plan), false);
                    if ("BACKUP".equals(operation)) backupDetails.addDetail(backup.getId(), AblestackSharedRawBackup.RETENTION_PENDING, "true", false);
                    return true;
                });
            } catch (RuntimeException failure) {
                boolean uncertain = false;
                for (long checkedHost : checked) try { control(checkedHost, "ABORT_PREPARE", plan); }
                catch (RuntimeException retained) { uncertain = true; failure.addSuppressed(retained); }
                if (uncertain) {
                    // Retain ownership of partial preparations so restart/cancel cleanup
                    // can release the exact files instead of leaving untracked Host markers.
                    Transaction.execute((com.cloud.utils.db.TransactionCallback<Boolean>) status -> {
                        for (var disk : plan.disks) poolDetails.addDetail(disk.poolId, marker(disk.path), ownerValue, false);
                        backupDetails.addDetail(backup.getId(), key(jobId, operation), JSON.toJson(plan), false);
                        backupDetails.addDetail(backup.getId(), key(jobId, operation) + ".prepareFailed", "{\"failed\":true}", false);
                        return true;
                    });
                }
                throw failure;
            }
            return plan;
        }
    }
    @Override public void release(Backup backup, String jobId, String operation) {
        try (Lease lease = lock()) {
            var record = backupDetails.findDetail(backup.getId(), key(jobId, operation));
            if (record == null) return;
            var plan = JSON.fromJson(record.getValue(), AblestackSharedRawBackup.Plan.class);
            if (plan == null || plan.version != 1 || plan.backupId != backup.getId()
                    || !jobId.equals(plan.jobId) || !operation.equals(plan.operation))
                throw new CloudRuntimeException("SHARED_RAW_UNCONFIRMED: release identity differs from its protected plan");
            if (backupDetails.findDetail(backup.getId(), key(jobId, operation) + ".released") != null) return;
            verifyOwnership(plan, record.getValue());
            // Exclusive file locks and absence of orphan readers prove source IO has ended.
            // Delayed workers must revalidate ownership and cannot start after release.
            control(plan.hostId, "RELEASE", plan);
            Transaction.execute((com.cloud.utils.db.TransactionCallback<Boolean>) status -> {
                verifyOwnership(plan, record.getValue());
                for (var disk : plan.disks) {
                    poolDetails.removeDetail(disk.poolId, marker(disk.path));
                }
                backupDetails.addDetail(backup.getId(), key(jobId, operation) + ".released", "{\"released\":true}", false);
                return true;
            });
        }
    }
}
