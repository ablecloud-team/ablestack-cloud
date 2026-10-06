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
    private final Map<Long, Runnable> pending = new ConcurrentHashMap<>();
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
            backupDao.loadDetails(tracked);
            ThirdPartyBackupManifest manifest = ThirdPartyBackupManifest.fromJson(tracked.getDetail(ThirdPartyBackupManifest.DETAIL_KEY));
            BackupAnswer status = (BackupAnswer) agentManager.send(host.getId(), new AblestackBackupJobStatusCommand(tracked.getUuid()));
            if (status == null || !status.getResult()) {
                return true;
            }
            if ("FAILED".equals(status.getState()) || "INTERRUPTED".equals(status.getState()) || "CANCELED".equals(status.getState())) {
                tracked.setStatus("CANCELED".equals(status.getState()) ? Backup.Status.Canceled : Backup.Status.Failed);
                tracked.getDetails().put("thirdparty.volume.failure", StringUtils.defaultString(status.getDetails()));
                backupDao.update(tracked.getId(), tracked);
                return true;
            }
            if ("COMPLETED".equals(status.getState())) {
                manifest.validate(true);
                tracked.setStatus(Backup.Status.BackedUp);
                tracked.setSize(tracked.getProtectedSize());
                backupDao.update(tracked.getId(), tracked);
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
}
