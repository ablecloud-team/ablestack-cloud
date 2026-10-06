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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.host.Host;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.Gson;
import org.apache.cloudstack.backup.dao.BackupDao;
import org.apache.commons.lang3.StringUtils;

/** Every successful artifact is persisted before the Host is allowed to remove its staged image. */
public class ThirdPartyBackupVolumeServiceImpl extends ManagerBase implements ThirdPartyBackupVolumeService {
    @Inject private AgentManager agentManager;
    @Inject private BackupDao backupDao;
    @Inject private ThirdPartyBackupStagingService stagingService;
    private final Map<Long, Runnable> pending = new ConcurrentHashMap<>();
    private final Map<String, java.util.concurrent.ScheduledFuture<?>> restores = new ConcurrentHashMap<>();
    private ScheduledExecutorService executor;

    @Override
    public boolean start() {
        executor = Executors.newScheduledThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "thirdparty-volume-transfer");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(() -> pending.forEach((id, task) -> {
            try { task.run(); } catch (RuntimeException e) { logger.warn("Volume job reconciliation failed for backup [{}]", id, e); }
        }), 1, 5, TimeUnit.SECONDS);
        return true;
    }

    @Override
    public boolean stop() {
        if (executor != null) { executor.shutdownNow(); }
        pending.clear();
        restores.clear();
        return true;
    }

    @Override
    public void track(Backup backup, Host host, Transfer transfer) {
        if (backup == null || host == null) { return; }
        pending.putIfAbsent(backup.getId(), () -> {
            BackupVO current = backupDao.findById(backup.getId());
            if (current == null || current.getStatus() != Backup.Status.BackingUp) {
                pending.remove(backup.getId());
            } else {
                reconcile(current, host, transfer);
            }
        });
    }

    @Override
    public ThirdPartyBackupRestore.Plan prepareRestore(Backup backup, Host host, java.util.List<String> volumeUuids, int timeout) {
        stagingService.requireEnabled();
        BackupVO tracked = backupDao.findById(backup.getId());
        backupDao.loadDetails(tracked);
        ThirdPartyBackupManifest manifest = ThirdPartyBackupManifest.fromJson(tracked.getDetail(ThirdPartyBackupManifest.DETAIL_KEY));
        manifest.validate(true);
        if (!Backup.Status.BackedUp.equals(tracked.getStatus()) || host == null || volumeUuids == null || volumeUuids.isEmpty()
                || new java.util.HashSet<>(volumeUuids).size() != volumeUuids.size()) {
            throw new CloudRuntimeException("Invalid volume restore selection");
        }
        volumeUuids.forEach(uuid -> {
            if (manifest.getVolumes().stream().noneMatch(volume -> volume.uuid.equals(uuid))) {
                throw new CloudRuntimeException("Selected volume is absent from the backup manifest");
            }
        });
        ThirdPartyBackupRestore.Plan plan = new ThirdPartyBackupRestore.Plan();
        plan.jobId = AblestackBackupFrameworkUtils.createRestoreJobId(manifest.getProvider(), backup.getUuid(), manifest.getVmName(),
                volumeUuids.size() == 1 ? volumeUuids.get(0) : null);
        plan.hostId = host.getId();
        plan.hostName = host.getName();
        java.nio.file.Path providerRoot = java.nio.file.Path.of(stagingService.getStageRootPath(manifest.getProvider()));
        plan.stageRoot = providerRoot.getParent().toString();
        plan.destination = providerRoot.resolve("restore").resolve(plan.jobId).toString();
        plan.bufferPercent = stagingService.getCapacityBufferPercent();
        plan.timeout = timeout;
        plan.manifest = manifest;
        plan.volumeUuids = new java.util.ArrayList<>(volumeUuids);
        // This also mounts/validates the configured staging filesystem on the chosen Worker Host.
        stagingService.getAvailableBytes(host, plan.destination);
        tracked.getDetails().put(ThirdPartyBackupRestore.PLAN_KEY, new Gson().toJson(plan));
        tracked.getDetails().remove(ThirdPartyBackupRestore.TRANSFER_KEY);
        if (!backupDao.update(tracked.getId(), tracked)) {
            throw new CloudRuntimeException("Unable to persist volume restore plan");
        }
        return plan;
    }

    @Override
    public void trackRestore(Backup backup, Host host, RestoreTransfer transfer) {
        BackupVO tracked = backupDao.findById(backup.getId());
        if (tracked == null || host == null || executor == null) { return; }
        backupDao.loadDetails(tracked);
        String json = tracked.getDetail(ThirdPartyBackupRestore.PLAN_KEY);
        if (StringUtils.isBlank(json)) { return; }
        ThirdPartyBackupRestore.Plan plan = new Gson().fromJson(json, ThirdPartyBackupRestore.Plan.class);
        if (plan.hostId != host.getId()) { throw new CloudRuntimeException("Restore Worker Host changed"); }
        restores.computeIfAbsent(plan.jobId, id -> executor.scheduleWithFixedDelay(
                () -> reconcileRestore(backup.getId(), host, plan, transfer), 1, 5, TimeUnit.SECONDS));
    }

    private void reconcileRestore(long backupId, Host host, ThirdPartyBackupRestore.Plan plan, RestoreTransfer transfer) {
        GlobalLock lock = GlobalLock.getInternLock("backup.volume.restore." + plan.jobId);
        boolean acquired = false;
        try {
            acquired = lock.lock(1);
            if (!acquired) { return; }
            BackupVO tracked = backupDao.findById(backupId);
            if (tracked == null) { finishRestoreTracking(plan.jobId); return; }
            backupDao.loadDetails(tracked);
            ThirdPartyBackupRestore.Plan savedPlan = new Gson().fromJson(tracked.getDetail(ThirdPartyBackupRestore.PLAN_KEY),
                    ThirdPartyBackupRestore.Plan.class);
            if (savedPlan == null || !plan.jobId.equals(savedPlan.jobId)) { finishRestoreTracking(plan.jobId); return; }
            Answer statusAnswer = agentManager.send(host.getId(), new AblestackRestoreJobStatusCommand(plan.jobId));
            if (!(statusAnswer instanceof BackupAnswer) || !statusAnswer.getResult()) { return; }
            String state = ((BackupAnswer) statusAnswer).getState();
            if (java.util.Set.of("COMPLETED", "FAILED", "INTERRUPTED", "CANCELED").contains(StringUtils.defaultString(state))) {
                finishRestoreTracking(plan.jobId);
                return;
            }
            Answer answer = agentManager.send(host.getId(), new AblestackVolumeStagingCommand(plan.jobId, "RESTORE_STATUS", -1, null));
            if (answer == null || !answer.getResult() || StringUtils.isBlank(answer.getDetails())) { return; }
            ThirdPartyBackupRestore.Request request = new Gson().fromJson(answer.getDetails(), ThirdPartyBackupRestore.Request.class);
            validateRestoreRequest(savedPlan, request);
            ThirdPartyBackupRestore.Result saved = new Gson().fromJson(tracked.getDetail(ThirdPartyBackupRestore.TRANSFER_KEY),
                    ThirdPartyBackupRestore.Result.class);
            if (saved == null || saved.sequence != request.sequence) {
                saved = new ThirdPartyBackupRestore.Result();
                saved.sequence = request.sequence;
            }
            if (!saved.completed) {
                if (StringUtils.isBlank(saved.jobId)) {
                    if (saved.submissionPending) {
                        throw new CloudRuntimeException("External restore submission outcome is unknown; reconcile it before retrying");
                    }
                    saved.submissionPending = true;
                    persistRestoreResult(tracked, saved);
                }
                ThirdPartyBackupRestore.Result result = transfer.restore(request, saved);
                if (result == null) { return; }
                if (StringUtils.isBlank(result.jobId)) { throw new CloudRuntimeException("External restore did not return a job ID"); }
                result.sequence = request.sequence;
                result.submissionPending = false;
                persistRestoreResult(tracked, result);
                saved = result;
            }
            if (saved.completed) {
                agentManager.send(host.getId(), new AblestackVolumeStagingCommand(plan.jobId, "RESTORE_ACK", request.sequence,
                        new Gson().toJson(request)));
            }
        } catch (com.cloud.exception.AgentUnavailableException | com.cloud.exception.OperationTimedoutException e) {
            logger.warn("Restore Worker Host is temporarily unavailable for job [{}]", plan.jobId);
        } catch (RuntimeException e) {
            logger.warn("Volume restore transfer failed for job [{}]: {}", plan.jobId, e.getMessage());
            try {
                Answer failed = agentManager.send(host.getId(), new AblestackVolumeStagingCommand(plan.jobId, "RESTORE_FAIL", -1, e.getMessage()));
                if (failed != null && failed.getResult()) { finishRestoreTracking(plan.jobId); }
            } catch (Exception unavailable) { logger.warn("Unable to notify restore Worker Host [{}]", plan.hostId); }
        } finally {
            if (acquired) { lock.unlock(); }
            lock.releaseRef();
        }
    }

    private void persistRestoreResult(BackupVO backup, ThirdPartyBackupRestore.Result result) {
        backup.getDetails().put(ThirdPartyBackupRestore.TRANSFER_KEY, new Gson().toJson(result));
        if (!backupDao.update(backup.getId(), backup)) { throw new CloudRuntimeException("Unable to persist external restore job"); }
    }

    private void finishRestoreTracking(String id) {
        java.util.concurrent.ScheduledFuture<?> task = restores.remove(id);
        if (task != null) { task.cancel(false); }
    }

    private void validateRestoreRequest(ThirdPartyBackupRestore.Plan plan, ThirdPartyBackupRestore.Request request) {
        if (request == null || !plan.jobId.equals(request.jobId) || request.sequence < 0) {
            throw new CloudRuntimeException("Invalid volume restore request");
        }
        ThirdPartyBackupManifest.Artifact artifact;
        if (request.metadata) {
            if (request.sequence != 0) { throw new CloudRuntimeException("Invalid metadata restore sequence"); }
            artifact = plan.manifest.getMetadata();
        } else {
            if (request.volumeIndex < 0 || request.volumeIndex >= plan.volumeUuids.size()) {
                throw new CloudRuntimeException("Invalid restore volume index");
            }
            ThirdPartyBackupManifest.Volume volume = plan.manifest.getVolumes().stream()
                    .filter(v -> v.uuid.equals(plan.volumeUuids.get(request.volumeIndex))).findFirst().orElseThrow();
            if (request.chainIndex < 0 || request.chainIndex >= volume.chain.size()) {
                throw new CloudRuntimeException("Invalid restore chain index");
            }
            artifact = volume.chain.get(request.chainIndex);
        }
        String expected = java.nio.file.Path.of(plan.destination).resolve(request.metadata ? "metadata" : "payload")
                .resolve(java.nio.file.Path.of(artifact.path).getFileName()).toString();
        if (!expected.equals(request.destination) || !new Gson().toJson(artifact).equals(new Gson().toJson(request.artifact))) {
            throw new CloudRuntimeException("Host restore selection differs from the persisted catalog plan");
        }
    }

    @Override
    public boolean reconcile(Backup backup, Host host, Transfer transfer) {
        BackupVO tracked = backupDao.findById(backup.getId());
        if (tracked == null) {
            return false;
        }
        backupDao.loadDetails(tracked);
        if (!ThirdPartyBackupManifest.VOLUME_MODE.equals(tracked.getDetail(ThirdPartyBackupManifest.MODE_KEY))) {
            return false;
        }
        if (tracked.getStatus() != Backup.Status.BackingUp || host == null) {
            return true;
        }
        GlobalLock lock = GlobalLock.getInternLock("backup.volume." + tracked.getUuid());
        boolean acquired = false;
        try {
            acquired = lock.lock(1);
            if (!acquired) {
                return true;
            }
            tracked = backupDao.findById(backup.getId());
            if (tracked == null || tracked.getStatus() != Backup.Status.BackingUp) {
                return true;
            }
            backupDao.loadDetails(tracked);
            ThirdPartyBackupManifest manifest = ThirdPartyBackupManifest.fromJson(tracked.getDetail(ThirdPartyBackupManifest.DETAIL_KEY));
            BackupAnswer status = (BackupAnswer) agentManager.send(host.getId(), new AblestackBackupJobStatusCommand(tracked.getUuid()));
            if (status == null || !status.getResult()) {
                return true;
            }
            if ("FAILED".equals(status.getState()) || "INTERRUPTED".equals(status.getState()) || "CANCELED".equals(status.getState())) {
                notifyFailure(transfer, tracked);
                tracked.setStatus("CANCELED".equals(status.getState()) ? Backup.Status.Canceled : Backup.Status.Failed);
                tracked.getDetails().put("thirdparty.volume.failure", StringUtils.defaultString(status.getDetails()));
                backupDao.update(tracked.getId(), tracked);
                return true;
            }
            if ("COMPLETED".equals(status.getState())) {
                manifest.validate(true);
                transfer.completed(tracked);
                tracked.setStatus(Backup.Status.BackedUp);
                tracked.setSize(manifest.getCurrentArtifacts().stream().mapToLong(artifact -> artifact.size).reduce(0L, Math::addExact));
                if (!backupDao.update(tracked.getId(), tracked)) {
                    throw new CloudRuntimeException("Unable to persist logical backup completion");
                }
                return true;
            }
            Answer requestAnswer = agentManager.send(host.getId(), new AblestackVolumeStagingCommand(tracked.getUuid(), "STATUS", -1, null));
            if (requestAnswer == null || !requestAnswer.getResult() || StringUtils.isBlank(requestAnswer.getDetails())) {
                return true;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> request = new Gson().fromJson(requestAnswer.getDetails(), Map.class);
            if (Boolean.TRUE.equals(request.get("gate"))) {
                if (!tracked.getUuid().equals(request.get("backupUuid"))) {
                    throw new CloudRuntimeException("Source preparation belongs to another backup");
                }
                if (transfer.ready()) {
                    agentManager.send(host.getId(), new AblestackVolumeStagingCommand(tracked.getUuid(), "START", -1, null));
                }
                return true;
            }
            int index = ((Number) request.get("index")).intValue();
            boolean metadata = Boolean.TRUE.equals(request.get("metadata"));
            if (!tracked.getUuid().equals(request.get("backupUuid")) || index < 0
                    || metadata != (index == manifest.getVolumes().size()) || index > manifest.getVolumes().size()) {
                throw new CloudRuntimeException("Host requested an invalid logical backup artifact");
            }
            ThirdPartyBackupManifest.Artifact requested = new Gson().fromJson(new Gson().toJson(request.get("artifact")),
                    ThirdPartyBackupManifest.Artifact.class);
            ThirdPartyBackupManifest.Artifact artifact;
            if (metadata) {
                manifest.getCurrentArtifacts().forEach(current -> {
                    if (!current.completed || StringUtils.isBlank(current.externalId)) {
                        throw new CloudRuntimeException("Metadata cannot be transferred before every volume completes");
                    }
                });
                if (!tracked.getUuid().equals(requested.backupUuid) || !requested.path.equals(tracked.getExternalId().split(",", 2)[0])) {
                    throw new CloudRuntimeException("Metadata request belongs to another backup");
                }
                artifact = manifest.getMetadata();
                if (artifact == null) {
                    artifact = requested;
                    manifest.setMetadata(artifact);
                }
            } else {
                artifact = manifest.getCurrentArtifacts().get(index);
                if (!artifact.path.equals(requested.path) || !artifact.backupUuid.equals(requested.backupUuid)) {
                    throw new CloudRuntimeException("Host requested an artifact outside its saved volume plan");
                }
                artifact.size = requested.size;
            }
            if (!artifact.completed) {
                if (StringUtils.isBlank(artifact.jobId)) {
                    if (artifact.submissionPending) {
                        throw new CloudRuntimeException("External artifact submission outcome is unknown; reconcile the external job before retrying");
                    }
                    // A controller restart after an accepted request must not silently submit it again.
                    artifact.submissionPending = true;
                    tracked.getDetails().put(ThirdPartyBackupManifest.DETAIL_KEY, manifest.toJson());
                    if (!backupDao.update(tracked.getId(), tracked)) {
                        throw new CloudRuntimeException("Unable to persist external artifact submission intent");
                    }
                }
                // Save an accepted child job before polling it. Only a confirmed catalog reference permits unlink.
                ThirdPartyBackupManifest.Artifact result = transfer.backup(artifact, metadata);
                if (result == null) {
                    return true;
                }
                if (StringUtils.isBlank(result.jobId) || (result.completed && StringUtils.isBlank(result.externalId))
                        || !artifact.path.equals(result.path) || !artifact.backupUuid.equals(result.backupUuid)) {
                    throw new CloudRuntimeException("Invalid external artifact job reference");
                }
                artifact.externalId = result.externalId;
                artifact.jobId = result.jobId;
                artifact.backupTime = result.backupTime;
                artifact.completed = result.completed;
                artifact.submissionPending = false;
                if (metadata && artifact.completed) {
                    manifest.setComplete(true);
                    if ("ablestack-commvault".equals(manifest.getProvider())) {
                        tracked.setExternalId(artifact.path + "," + artifact.jobId);
                    } else if ("ablestack-netbackup".equals(manifest.getProvider())) {
                        tracked.getDetails().put("netbackup.backup.id", artifact.externalId);
                        if (artifact.backupTime != null) {
                            tracked.getDetails().put("netbackup.backup.time", artifact.backupTime);
                        }
                    } else if ("ablestack-veeam".equals(manifest.getProvider())) {
                        tracked.getDetails().put("ablestack.veeam.restore.point.id", artifact.externalId);
                    }
                }
                tracked.getDetails().put(ThirdPartyBackupManifest.DETAIL_KEY, manifest.toJson());
                if (!backupDao.update(tracked.getId(), tracked)) {
                    throw new CloudRuntimeException("Unable to persist artifact catalog reference");
                }
                if (!artifact.completed) {
                    return true;
                }
            }
            Answer ack = agentManager.send(host.getId(), new AblestackVolumeStagingCommand(tracked.getUuid(), "ACK", index, manifest.toJson()));
            if (ack == null || !ack.getResult()) {
                logger.warn("Artifact was persisted but Host acknowledgment is pending for backup [{}], volume [{}]", tracked.getUuid(), index);
            }
        } catch (com.cloud.exception.AgentUnavailableException | com.cloud.exception.OperationTimedoutException e) {
            logger.warn("Volume pipeline Host is temporarily unavailable for backup [{}]", backup.getUuid());
        } catch (RuntimeException e) {
            // Preserve staged artifacts and confirmed child references; do not pretend a partial VM backup completed.
            notifyFailure(transfer, tracked);
            tracked.getDetails().put("thirdparty.volume.failure", StringUtils.defaultString(e.getMessage()));
            tracked.setStatus(Backup.Status.Failed);
            backupDao.update(tracked.getId(), tracked);
            try {
                agentManager.send(host.getId(), new AblestackVolumeStagingCommand(tracked.getUuid(), "CANCEL", -1, null));
            } catch (Exception cancelFailure) {
                logger.warn("Unable to notify Host of a failed volume pipeline [{}]", tracked.getUuid());
            }
            logger.warn("Logical backup [{}] failed during volume transfer: {}", tracked.getUuid(), e.getMessage());
        } finally {
            if (acquired) {
                lock.unlock();
            }
            lock.releaseRef();
        }
        return true;
    }

    private void notifyFailure(Transfer transfer, Backup backup) {
        try {
            transfer.failed(backup);
        } catch (RuntimeException e) {
            logger.warn("Unable to seal the incremental source after volume backup failure [{}]", backup.getUuid(), e);
        }
    }
}
