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
import org.apache.cloudstack.api.response.BackupStagingInfoResponse;
import org.apache.cloudstack.backup.dao.BackupDao;
import org.apache.cloudstack.backup.dao.BackupDetailsDao;
import org.apache.commons.lang3.StringUtils;

/** Every successful artifact is persisted before the Host is allowed to remove its staged image. */
public class ThirdPartyBackupVolumeServiceImpl extends ManagerBase implements ThirdPartyBackupVolumeService {
    // Match the external providers' catalog grace period, measured from confirmed absence rather than backup start.
    private static final long EXPIRATION_CONFIRMATION_MS = TimeUnit.MINUTES.toMillis(10);
    @Inject private AgentManager agentManager;
    @Inject private BackupDao backupDao;
    @Inject private BackupDetailsDao backupDetailsDao;
    @Inject private ThirdPartyBackupStagingService stagingService;
    @Inject private com.cloud.host.dao.HostDao hostDao;
    @Inject private com.cloud.storage.dao.StoragePoolHostDao poolHostDao;
    @Inject private com.cloud.user.ResourceLimitService resourceLimitService;
    private final Map<Long, java.util.concurrent.ScheduledFuture<?>> pending = new ConcurrentHashMap<>();
    private final Map<String, java.util.concurrent.ScheduledFuture<?>> restores = new ConcurrentHashMap<>();
    private final Map<Long, java.util.concurrent.ScheduledFuture<?>> sourceCleanups = new ConcurrentHashMap<>();
    private ScheduledExecutorService executor;
    private ScheduledExecutorService restoreExecutor;
    private ScheduledExecutorService cleanupExecutor;

    private static final String MANUAL_INSPECTION_KEY = "thirdparty.staging.manual.inspection";

    @Override
    public void persistBackupPlan(Backup backup) {
        if (!ThirdPartyBackupManifest.VOLUME_MODE.equals(backup.getDetail(ThirdPartyBackupManifest.MODE_KEY))) {
            throw new CloudRuntimeException("The backup does not contain a volume staging plan");
        }
        ThirdPartyBackupManifest manifest = ThirdPartyBackupManifest.fromJson(backup.getDetail(ThirdPartyBackupManifest.DETAIL_KEY));
        manifest.validate(false);
        if (!backup.getUuid().equals(manifest.getBackupUuid())) {
            throw new CloudRuntimeException("The volume staging plan belongs to a different backup");
        }
        com.cloud.utils.db.Transaction.execute((com.cloud.utils.db.TransactionCallback<Boolean>) status -> {
            BackupVO current = backupDao.lockRow(backup.getId(), true);
            if (current == null || current.getRemoved() != null || current.getStatus() != Backup.Status.BackingUp
                    || !current.getUuid().equals(backup.getUuid())) {
                throw new CloudRuntimeException("The backup is no longer available for initial Host dispatch");
            }
            backupDao.loadDetails(current);
            if (StringUtils.isNotBlank(current.getDetail(ThirdPartyBackupManifest.MODE_KEY))) {
                throw new CloudRuntimeException("The initial volume backup plan is already persisted");
            }
            for (Backup other : backupDao.listByVmId(current.getZoneId(), current.getVmId())) {
                if (other.getId() == current.getId()) { continue; }
                backupDao.loadDetails((BackupVO) other);
                if (ThirdPartyBackupManifest.VOLUME_MODE.equals(other.getDetail(ThirdPartyBackupManifest.MODE_KEY))
                        && (other.getStatus() == Backup.Status.BackingUp
                                || (isFailedPipeline(other) && ThirdPartyBackupVolumeService.needsBackupCleanup(other)))) {
                    throw new CloudRuntimeException("The previous volume backup must confirm termination and staging cleanup before another attempt");
                }
            }
            BackupVO update = backupDao.createForUpdate(current.getId());
            update.setBackedUpVolumes(new Gson().toJson(backup.getBackedUpVolumes()));
            update.setDetails(new java.util.HashMap<>(backup.getDetails()));
            if (!backupDao.update(current.getId(), update)) {
                throw new CloudRuntimeException("Unable to persist the volume backup plan");
            }
            backupDetailsDao.addDetail(current.getId(), AblestackBackupFrameworkUtils.RESOURCE_COUNT_PENDING_DETAIL,
                    Boolean.TRUE.toString(), false);
            ThirdPartyBackupStart.Operation start = new ThirdPartyBackupStart.Operation();
            start.plan = new ThirdPartyBackupStart.Plan();
            start.plan.jobId = current.getUuid();
            if (current.getHostId() == null) { throw new CloudRuntimeException("Backup Worker Host is missing"); }
            start.plan.hostId = current.getHostId();
            start.plan.backupPath = current.getExternalId().split(",", 2)[0];
            start.plan.manifest = new Gson().fromJson(manifest.toJson(), ThirdPartyBackupManifest.class);
            start.state = "PREPARING";
            start.createdAt = System.currentTimeMillis();
            saveBackupStart(current.getId(), start);
            return true;
        });
        backup.getDetails().put(AblestackBackupFrameworkUtils.RESOURCE_COUNT_PENDING_DETAIL, Boolean.TRUE.toString());
    }

    private ThirdPartyBackupStart.Operation readBackupStart(long id) {
        BackupDetailVO detail = backupDetailsDao.findDetail(id, ThirdPartyBackupStart.DETAIL_KEY);
        if (detail == null) { return null; }
        ThirdPartyBackupStart.Operation start = new Gson().fromJson(detail.getValue(), ThirdPartyBackupStart.Operation.class);
        if (start == null || start.plan == null || start.plan.version != ThirdPartyBackupStart.VERSION
                || start.plan.manifest == null || StringUtils.isBlank(start.plan.jobId)
                || !start.plan.jobId.equals(start.plan.manifest.getBackupUuid()) || start.plan.hostId <= 0
                || StringUtils.isBlank(start.plan.backupPath) || start.createdAt <= 0
                || start.cancelRequestedAt < 0 || start.cancelConfirmedAt < 0
                || (start.cancelConfirmedAt > 0 && start.cancelRequestedAt == 0)
                || !java.util.Set.of("PREPARING", "PREPARED", "SUBMISSION_PENDING", "STARTED", "START_FAILED")
                        .contains(StringUtils.defaultString(start.state))) {
            throw new CloudRuntimeException("Persisted backup start ownership is unconfirmed");
        }
        return start;
    }

    private void saveBackupStart(long id, ThirdPartyBackupStart.Operation start) {
        backupDetailsDao.addDetail(id, ThirdPartyBackupStart.DETAIL_KEY, new Gson().toJson(start), false);
    }

    private void saveBackupStartReason(long id, String reason) {
        ThirdPartyBackupStart.Operation start = readBackupStart(id);
        if (start == null || "STARTED".equals(start.state) || "START_FAILED".equals(start.state)) { return; }
        start.reason = StringUtils.defaultIfBlank(reason, "Backup start confirmation is pending");
        saveBackupStart(id, start);
    }

    @Override
    public void recordBackupStartUnconfirmed(Backup backup, String reason) {
        GlobalLock lock = GlobalLock.getInternLock("backup.volume." + backup.getUuid());
        boolean acquired = false;
        try {
            acquired = lock.lock(5);
            if (!acquired) { throw new CloudRuntimeException("Backup start confirmation is being reconciled"); }
            saveBackupStartReason(backup.getId(), reason);
        } finally {
            if (acquired) { lock.unlock(); }
            lock.releaseRef();
        }
    }

    private ThirdPartyBackupStart.Receipt backupStartControl(Host host, ThirdPartyBackupStart.Plan plan, String action)
            throws com.cloud.exception.AgentUnavailableException, com.cloud.exception.OperationTimedoutException {
        if (host == null || host.getId() != plan.hostId) { throw new CloudRuntimeException("Backup start Worker Host differs from the recorded attempt"); }
        Answer answer = agentManager.send(host.getId(), new AblestackVolumeStagingCommand(plan.jobId, action, -1, new Gson().toJson(plan)));
        if (answer == null || !answer.getResult() || StringUtils.isBlank(answer.getDetails())) {
            throw new CloudRuntimeException(answer == null ? "Host did not confirm backup start control" : answer.getDetails());
        }
        ThirdPartyBackupStart.Receipt receipt = new Gson().fromJson(answer.getDetails(), ThirdPartyBackupStart.Receipt.class);
        if (receipt == null || receipt.version != ThirdPartyBackupStart.VERSION || receipt.plan == null || receipt.checkedAt <= 0
                || !new Gson().toJsonTree(plan).equals(new Gson().toJsonTree(receipt.plan))
                || !java.util.Set.of("PREPARED", "DISPATCHED", "STARTED", "START_FAILED").contains(StringUtils.defaultString(receipt.state))) {
            throw new CloudRuntimeException("Host backup start proof differs from the recorded dispatch");
        }
        return receipt;
    }

    @Override
    public void dispatchBackup(Backup backup, Host host, com.cloud.agent.api.Command command, Transfer transfer) {
        GlobalLock lock = GlobalLock.getInternLock("backup.volume." + backup.getUuid());
        boolean acquired = false;
        try {
            acquired = lock.lock(5);
            if (!acquired) { throw new CloudRuntimeException("Backup dispatch is being reconciled; no new command was sent"); }
            ThirdPartyBackupStart.Operation start = readBackupStart(backup.getId());
            if (start == null || !backup.getUuid().equals(start.plan.jobId) || !"PREPARING".equals(start.state)) {
                throw new CloudRuntimeException("Backup dispatch is already recorded or permanently blocked");
            }
            if (start.cancelRequestedAt > 0) { throw new CloudRuntimeException("Backup cancellation is pending; no start command was sent"); }
            requireBackupWorker(backup, host);
            registerAdmission(backup.getId(), host, backup.getUuid(), "BACKUP");
            ThirdPartyBackupStart.Receipt receipt = backupStartControl(host, start.plan, "BACKUP_START_PREPARE");
            if (!"PREPARED".equals(receipt.state)) { throw new CloudRuntimeException("Backup start preparation was not accepted"); }
            start.state = "PREPARED";
            start.checkedAt = receipt.checkedAt;
            saveBackupStart(backup.getId(), start);
            String json = new Gson().toJson(start.plan);
            if (command instanceof AblestackCommvaultTakeBackupCommand) { ((AblestackCommvaultTakeBackupCommand) command).setVolumeBackupStartPlan(json); }
            else if (command instanceof AblestackNetBackupTakeBackupCommand) { ((AblestackNetBackupTakeBackupCommand) command).setVolumeBackupStartPlan(json); }
            else if (command instanceof AblestackVeeamTakeBackupCommand) { ((AblestackVeeamTakeBackupCommand) command).setVolumeBackupStartPlan(json); }
            else { throw new CloudRuntimeException("Unsupported volume backup dispatch command"); }
            start.state = "SUBMISSION_PENDING";
            start.submittedAt = System.currentTimeMillis();
            start.reason = null;
            // Persist the intent before sending anything that can initialize the Host engine.
            saveBackupStart(backup.getId(), start);
            Answer answer = agentManager.send(host.getId(), command);
            if (answer == null || !answer.getResult()) {
                saveBackupStartReason(backup.getId(), answer == null ? "Host start response was not received" : answer.getDetails());
            }
        } catch (Exception e) {
            if (acquired) {
                try { saveBackupStartReason(backup.getId(), e.getMessage()); }
                catch (RuntimeException saveFailure) { logger.warn("Unable to persist backup start confirmation reason [{}]", backup.getUuid(), saveFailure); }
            }
            logger.warn("Backup start is unconfirmed [{}]; the same attempt will be reconciled: {}", backup.getUuid(), e.getMessage());
        } finally {
            if (acquired) { lock.unlock(); }
            lock.releaseRef();
        }
        try { track(backup, host, transfer); }
        catch (RuntimeException e) { logger.warn("Backup start tracking will resume during provider reconciliation [{}]", backup.getUuid(), e); }
    }

    @Override
    public void reconcileResourceCounts(Backup backup) {
        com.cloud.utils.db.Transaction.execute((com.cloud.utils.db.TransactionCallback<Boolean>) status -> {
            // Deletion and other Management Servers lock this same row before accounting.
            BackupVO current = backupDao.lockRow(backup.getId(), true);
            if (current == null || current.getRemoved() != null || current.getStatus() != Backup.Status.BackedUp) { return false; }
            backupDao.loadDetails(current);
            if (!ThirdPartyBackupManifest.VOLUME_MODE.equals(current.getDetail(ThirdPartyBackupManifest.MODE_KEY))
                    || !Boolean.parseBoolean(current.getDetail(AblestackBackupFrameworkUtils.RESOURCE_COUNT_PENDING_DETAIL))) {
                return false;
            }
            long size = current.getSize() == null ? 0L : current.getSize();
            if (size < 0L) { throw new CloudRuntimeException("The completed backup has an invalid size"); }
            resourceLimitService.incrementResourceCount(current.getAccountId(), com.cloud.configuration.Resource.ResourceType.backup);
            resourceLimitService.incrementResourceCount(current.getAccountId(), com.cloud.configuration.Resource.ResourceType.backup_storage, size);
            backupDetailsDao.removeDetail(current.getId(), AblestackBackupFrameworkUtils.RESOURCE_COUNT_PENDING_DETAIL);
            return true;
        });
    }

    private BackupVO stagingBackup(long id) {
        BackupVO backup = backupDao.findById(id);
        if (backup == null) { throw new CloudRuntimeException("Selected backup no longer exists"); }
        backupDao.loadDetails(backup);
        if (!ThirdPartyBackupManifest.VOLUME_MODE.equals(backup.getDetail(ThirdPartyBackupManifest.MODE_KEY))) {
            throw new CloudRuntimeException("This backup does not use the third-party volume staging pipeline");
        }
        return backup;
    }

    private ThirdPartyBackupRestore.Plan restorePlan(Backup backup) {
        ThirdPartyBackupRestore.Plan plan = new Gson().fromJson(backup.getDetail(ThirdPartyBackupRestore.PLAN_KEY),
                ThirdPartyBackupRestore.Plan.class);
        if (plan == null || StringUtils.isBlank(plan.jobId)) { throw new CloudRuntimeException("No staged restore attempt is recorded"); }
        return plan;
    }

    @Override
    public Host getWorkerHost(Backup backup, String operation) {
        admissionKey(operation);
        BackupVO current = stagingBackup(backup.getId());
        if ("RESTORE".equals(operation)) {
            ThirdPartyBackupRestore.Plan plan = restorePlan(current);
            ThirdPartyBackupAdmission.Entry entry = readAdmission(current.getId(), operation);
            String recordedHost = current.getDetail(AblestackBackupFrameworkUtils.RESTORE_HOST_ID_DETAIL);
            String recordedJob = current.getDetail(AblestackBackupFrameworkUtils.RESTORE_JOB_ID_DETAIL);
            String cleanupJob = current.getDetail(AblestackBackupFrameworkUtils.RESTORE_JOB_CLEANUP_ID_DETAIL);
            if (plan.hostId <= 0 || plan.manifest == null || !current.getUuid().equals(plan.manifest.getBackupUuid())
                    || (StringUtils.isNotBlank(recordedHost) && !String.valueOf(plan.hostId).equals(recordedHost))
                    || (StringUtils.isNotBlank(recordedJob) && !plan.jobId.equals(recordedJob))
                    || (StringUtils.isNotBlank(cleanupJob) && !plan.jobId.equals(cleanupJob))
                    || (entry != null && (!operation.equals(entry.operation)
                            || (plan.jobId.equals(entry.jobId) ? entry.hostId != plan.hostId : !"RELEASED".equals(entry.state))))) {
                throw new CloudRuntimeException("Restore plan, tracking and admission Worker Host or attempt disagree");
            }
            return hostDao.findById(plan.hostId);
        }
        ThirdPartyBackupStart.Operation start = readBackupStart(current.getId());
        Long hostId = current.getHostId();
        if (start != null) {
            if (!current.getUuid().equals(start.plan.jobId) || (hostId != null && hostId.longValue() != start.plan.hostId)) {
                throw new CloudRuntimeException("Backup start and recorded Worker Host disagree");
            }
            hostId = start.plan.hostId;
        }
        ThirdPartyBackupAdmission.Entry entry = readAdmission(current.getId(), operation);
        if (entry != null) {
            if (!current.getUuid().equals(entry.jobId) || !"BACKUP".equals(entry.operation) || entry.hostId <= 0
                    || (hostId != null && hostId.longValue() != entry.hostId)) {
                throw new CloudRuntimeException("Backup admission and recorded Worker Host disagree");
            }
            hostId = entry.hostId;
        }
        if (hostId != null) { return hostDao.findById(hostId); }
        // Legacy volume jobs may record only the original Host name/address. Never use the VM's current Host.
        ThirdPartyBackupManifest manifest = ThirdPartyBackupManifest.fromJson(current.getDetail(ThirdPartyBackupManifest.DETAIL_KEY));
        String source = manifest.getCurrentArtifacts().get(0).sourceHost;
        Host recorded = hostDao.findByName(source);
        return recorded != null ? recorded : hostDao.findByIp(source);
    }

    private void requireBackupWorker(Backup backup, Host host) {
        Host recorded = getWorkerHost(backup, "BACKUP");
        if (recorded == null || host == null || recorded.getId() != host.getId()) {
            throw new CloudRuntimeException("The recorded backup Worker Host is unavailable or differs from this request; reservations are retained");
        }
    }

    private void requireRestoreWorker(Backup backup, Host host, ThirdPartyBackupRestore.Plan plan) {
        Host recorded = getWorkerHost(backup, "RESTORE");
        if (recorded == null || host == null || recorded.getId() != host.getId() || host.getId() != plan.hostId
                || !new Gson().toJson(plan).equals(new Gson().toJson(restorePlan(stagingBackup(backup.getId()))))) {
            throw new CloudRuntimeException("The recorded restore Worker Host or plan differs from this request; reservations are retained");
        }
    }

    private String stagingAttempt(Backup backup, String operation) {
        admissionKey(operation);
        return "RESTORE".equals(operation) ? restorePlan(backup).jobId : backup.getUuid();
    }

    private boolean hostTerminal(Host host, String operation, String jobId) throws Exception {
        Answer answer = agentManager.send(host.getId(), "RESTORE".equals(operation)
                ? new AblestackRestoreJobStatusCommand(jobId, null, 0) : new AblestackBackupJobStatusCommand(jobId));
        return answer instanceof BackupAnswer && answer.getResult()
                && java.util.Set.of("COMPLETED", "FAILED", "INTERRUPTED", "CANCELED")
                        .contains(StringUtils.defaultString(((BackupAnswer) answer).getState()));
    }

    @Override
    public BackupStagingInfoResponse inspect(Backup backup, String operation, String stagingJobId) {
        BackupVO current = stagingBackup(backup.getId());
        String jobId = stagingAttempt(current, operation);
        if ("BACKUP".equals(operation) && StringUtils.isNotBlank(stagingJobId) && !jobId.equals(stagingJobId)) {
            throw new CloudRuntimeException("Backup staging attempt does not match the selected backup");
        }
        GlobalLock lock = GlobalLock.getInternLock("RESTORE".equals(operation)
                ? "backup.volume.restore." + jobId : "backup.volume." + jobId);
        boolean acquired = false;
        try {
            acquired = lock.lock(5);
            if (!acquired) { throw new CloudRuntimeException("Staging job is being reconciled; retry shortly"); }
            current = stagingBackup(backup.getId());
            if (!jobId.equals(stagingAttempt(current, operation))) { throw new CloudRuntimeException("Staging attempt changed; refresh its details"); }
            return inspectLocked(current, operation, stagingJobId);
        } finally {
            if (acquired) { lock.unlock(); }
            lock.releaseRef();
        }
    }

    private ThirdPartyBackupRestore.Operation recordedRestoreOperation(Backup backup, String jobId) {
        BackupDetailVO detail = backupDetailsDao.findDetail(backup.getId(), ThirdPartyBackupRestore.operationKey(jobId));
        if (detail == null) { throw new CloudRuntimeException("Selected restore attempt has no recorded history"); }
        ThirdPartyBackupRestore.Operation operation = new Gson().fromJson(detail.getValue(), ThirdPartyBackupRestore.Operation.class);
        if (operation == null || operation.plan == null || !jobId.equals(operation.plan.jobId)) {
            throw new CloudRuntimeException("Restore history does not match the selected attempt");
        }
        return readRestoreOperation(backup, operation.plan);
    }

    private java.util.List<BackupStagingInfoResponse.RestoreAttemptInfo> restoreAttempts(Backup backup, ThirdPartyBackupRestore.Plan current) {
        java.util.List<BackupStagingInfoResponse.RestoreAttemptInfo> attempts = new java.util.ArrayList<>();
        for (BackupDetailVO detail : backupDetailsDao.listDetails(backup.getId())) {
            if (!detail.getName().startsWith(ThirdPartyBackupRestore.HISTORY_PREFIX) || !detail.getName().endsWith(".operation")) { continue; }
            ThirdPartyBackupRestore.Operation operation = new Gson().fromJson(detail.getValue(), ThirdPartyBackupRestore.Operation.class);
            if (operation == null || operation.plan == null || StringUtils.isBlank(operation.plan.jobId)
                    || !detail.getName().equals(ThirdPartyBackupRestore.operationKey(operation.plan.jobId))) {
                throw new CloudRuntimeException("Invalid restore operation history key");
            }
            operation = readRestoreOperation(backup, operation.plan);
            BackupStagingInfoResponse.RestoreAttemptInfo attempt = new BackupStagingInfoResponse.RestoreAttemptInfo();
            attempt.stagingjobid = operation.plan.jobId;
            attempt.hostname = operation.plan.hostName;
            attempt.createdat = historyDate(operation.createdAt);
            attempt.cleanupstate = operation.cleanupState;
            attempt.hoststate = operation.hostState;
            attempt.vmrestoreoutcome = operation.vmResult == null ? "NOT_RECORDED" : operation.vmResult.outcome;
            attempt.current = current.jobId.equals(attempt.stagingjobid);
            attempts.add(attempt);
        }
        if (attempts.stream().noneMatch(attempt -> attempt.current)) {
            BackupStagingInfoResponse.RestoreAttemptInfo attempt = new BackupStagingInfoResponse.RestoreAttemptInfo();
            attempt.stagingjobid = current.jobId;
            attempt.hostname = current.hostName;
            attempt.cleanupstate = backup.getDetail(ThirdPartyBackupRestore.CLEANUP_STATE_KEY);
            attempt.current = true;
            attempts.add(attempt);
        }
        attempts.sort(java.util.Comparator.comparing((BackupStagingInfoResponse.RestoreAttemptInfo attempt) -> attempt.current).reversed()
                .thenComparing(attempt -> attempt.createdat, java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())));
        return attempts;
    }

    private BackupStagingInfoResponse inspectLocked(BackupVO backup, String operation) {
        return inspectLocked(backup, operation, null);
    }

    private BackupStagingInfoResponse inspectLocked(BackupVO backup, String operation, String stagingJobId) {
        BackupStagingInfoResponse info = new BackupStagingInfoResponse();
        ThirdPartyBackupManifest manifest = ThirdPartyBackupManifest.fromJson(backup.getDetail(ThirdPartyBackupManifest.DETAIL_KEY));
        ThirdPartyBackupRestore.Plan plan = "RESTORE".equals(operation) ? restorePlan(backup) : null;
        ThirdPartyBackupRestore.Operation recorded = null;
        if (plan != null) {
            info.restoreattempt = restoreAttempts(backup, plan);
            info.historical = StringUtils.isNotBlank(stagingJobId) && !plan.jobId.equals(stagingJobId);
            recorded = info.historical ? recordedRestoreOperation(backup, stagingJobId) : readRestoreOperation(backup, plan);
            if (recorded != null) { plan = recorded.plan; }
            manifest = plan.manifest;
        }
        info.id = backup.getUuid();
        info.operation = operation;
        info.stagingjobid = plan == null ? backup.getUuid() : plan.jobId;
        info.provider = manifest.getProvider();
        info.vmname = manifest.getVmName();
        info.targetvmname = plan == null ? null : plan.targetVmName;
        info.timestamp = manifest.getTimestamp();
        info.checkedat = new java.util.Date();
        if (recorded != null) {
            info.startstate = recorded.startState;
            info.startreason = recorded.startFailureReason;
            info.startsubmittedat = historyDate(recorded.startSubmittedAt);
            info.startcheckedat = historyDate(recorded.startCheckedAt);
            info.cancelrequestedat = historyDate(recorded.cancelRequestedAt);
            info.cancelconfirmedat = historyDate(recorded.cancelConfirmedAt);
            info.cancelreason = recorded.cancelReason;
        } else if (plan == null) {
            ThirdPartyBackupStart.Operation start = readBackupStart(backup.getId());
            if (start != null) {
                info.startstate = start.state;
                info.startreason = start.reason;
                info.startsubmittedat = historyDate(start.submittedAt);
                info.startcheckedat = historyDate(start.checkedAt);
                info.cancelrequestedat = historyDate(start.cancelRequestedAt);
                info.cancelconfirmedat = historyDate(start.cancelConfirmedAt);
                info.cancelreason = start.cancelReason;
            }
        }
        if (plan != null) {
            info.vmrestore = new BackupStagingInfoResponse.VmRestoreInfo(recorded == null ? null : recorded.vmResult,
                    recorded == null ? 0 : recorded.vmResultCheckedAt, recorded == null ? null : recorded.vmResultError);
        }
        info.destination = plan == null ? java.nio.file.Path.of(manifest.getCurrentArtifacts().get(0).path).getParent().toString() : plan.destination;
        info.cleanupstate = info.historical ? recorded.cleanupState : backup.getDetail(
                plan == null ? ThirdPartyBackupManifest.CLEANUP_STATE_KEY : ThirdPartyBackupRestore.CLEANUP_STATE_KEY);
        info.cleanupreason = info.historical ? recorded.cleanupReason : backup.getDetail(
                plan == null ? ThirdPartyBackupManifest.CLEANUP_DETAILS_KEY : ThirdPartyBackupRestore.CLEANUP_DETAILS_KEY);
        if (plan == null) {
            info.sourcecleanupstate = backup.getDetail(ThirdPartyBackupManifest.SOURCE_CLEANUP_STATE_KEY);
            info.sourcecleanupreason = backup.getDetail(ThirdPartyBackupManifest.SOURCE_CLEANUP_DETAILS_KEY);
            info.jobcleanupstate = backup.getDetail(ThirdPartyBackupManifest.JOB_CLEANUP_STATE_KEY);
            info.jobcleanupreason = backup.getDetail(ThirdPartyBackupManifest.JOB_CLEANUP_DETAILS_KEY);
            info.finalizationstate = backup.getDetail(ThirdPartyBackupManifest.FINALIZATION_STATE_KEY);
            info.finalizationreason = backup.getDetail(ThirdPartyBackupManifest.FINALIZATION_DETAILS_KEY);
        }
        ThirdPartyBackupAdmission.Entry entry = info.historical ? null : readAdmission(backup.getId(), operation);
        if (entry != null && info.stagingjobid.equals(entry.jobId)) {
            info.stagingqueue = new org.apache.cloudstack.api.response.BackupStagingQueueResponse(entry);
        }
        BackupStagingInfoResponse previous = new Gson().fromJson(
                backup.getDetail(MANUAL_INSPECTION_KEY + "." + operation), BackupStagingInfoResponse.class);
        if (previous != null && !info.stagingjobid.equals(previous.stagingjobid)) { previous = null; }
        if (previous != null) { info.reconciliationerror = previous.reconciliationerror; }
        java.util.List<ThirdPartyBackupManifest.Volume> selectedVolumes = new java.util.ArrayList<>();
        if (plan == null) {
            selectedVolumes.addAll(manifest.getVolumes());
            for (int index = 0; index < selectedVolumes.size(); index++) {
                ThirdPartyBackupManifest.Volume volume = selectedVolumes.get(index);
                for (ThirdPartyBackupManifest.Artifact artifact : volume.chain) {
                    info.artifact.add(artifactInfo(artifact, volume.uuid, backup.getUuid().equals(artifact.backupUuid) ? index : null,
                            false, backup.getUuid(), previous));
                }
            }
            if (manifest.getMetadata() != null) {
                info.artifact.add(artifactInfo(manifest.getMetadata(), null, manifest.getVolumes().size(), true, backup.getUuid(), previous));
            }
        } else {
            for (String uuid : plan.volumeUuids) {
                selectedVolumes.add(manifest.getVolumes().stream().filter(volume -> uuid.equals(volume.uuid)).findFirst().orElseThrow());
            }
            Map<Integer, ThirdPartyBackupRestore.Record> records = new java.util.HashMap<>();
            for (ThirdPartyBackupRestore.Record record : restoreRecords(backup, plan)) { records.put(record.request.sequence, record); }
            ThirdPartyBackupRestore.Result legacy = recorded == null ? new Gson().fromJson(
                    backup.getDetail(ThirdPartyBackupRestore.TRANSFER_KEY), ThirdPartyBackupRestore.Result.class) : null;
            for (ThirdPartyBackupRestore.Request request : restoreRequests(plan)) {
                String volume = request.metadata ? null : selectedVolumes.get(request.volumeIndex).uuid;
                BackupStagingInfoResponse.ArtifactInfo artifact = artifactInfo(request.artifact, volume, request.sequence,
                        request.metadata, backup.getUuid(), previous);
                applyRestoreHistory(artifact, request, records.get(request.sequence), recorded, legacy);
                info.artifact.add(artifact);
            }
        }
        info.hoststate = "UNKNOWN";
        info.reservationstate = "UNKNOWN";
        if (info.historical) {
            info.hostname = plan.hostName;
            info.hoststate = StringUtils.defaultString(recorded.hostState, "UNKNOWN");
            info.hostcheckedat = historyDate(recorded.hostCheckedAt);
            info.hosterror = recorded.hostError;
            info.reservationstate = "NOT_QUERIED";
            return info;
        }
        Host host = getWorkerHost(backup, operation);
        if (host == null) { info.hostname = plan == null ? null : plan.hostName; info.hosterror = "Worker Host is no longer registered"; return info; }
        info.hostname = host.getName();
        try {
            Answer answer = agentManager.send(host.getId(), plan == null ? new AblestackBackupJobStatusCommand(info.stagingjobid)
                    : new AblestackRestoreJobStatusCommand(info.stagingjobid, null, 0));
            if (!(answer instanceof BackupAnswer) || !answer.getResult()) {
                throw new CloudRuntimeException(answer == null ? "Host did not respond" : answer.getDetails());
            }
            BackupAnswer status = (BackupAnswer) answer;
            if (plan != null) {
                recordRestoreHost(backup, plan, status, null);
                recorded = readRestoreOperation(backup, plan);
                info.vmrestore = new BackupStagingInfoResponse.VmRestoreInfo(recorded.vmResult, recorded.vmResultCheckedAt, recorded.vmResultError);
                for (BackupStagingInfoResponse.ArtifactInfo artifact : info.artifact) {
                    ThirdPartyBackupRestore.Record record = readRestoreRecord(backup, plan, artifact.index);
                    if (record != null) { applyRestoreHistory(artifact, record.request, record, recorded, null); }
                }
            }
            info.hoststate = status.getState();
            info.hostcheckedat = new java.util.Date();
            info.step = status.getStep();
            info.progress = status.getProgress();
            info.volumeindex = status.getVolumeIndex();
            info.volumecount = status.getVolumeCount();
            if (info.volumeindex != null && info.volumeindex > 0 && info.volumeindex <= selectedVolumes.size()) {
                info.currentvolumeid = selectedVolumes.get(info.volumeindex - 1).uuid;
            }
        } catch (Exception e) {
            info.hosterror = e.getMessage();
            if (plan != null) {
                recordRestoreHost(backup, plan, null, e.getMessage());
                recorded = readRestoreOperation(backup, plan);
                info.vmrestore = new BackupStagingInfoResponse.VmRestoreInfo(recorded.vmResult, recorded.vmResultCheckedAt, recorded.vmResultError);
            }
        }
        try {
            Answer answer = agentManager.send(host.getId(), new AblestackVolumeStagingCommand(info.stagingjobid, "ADMISSION_INSPECT", -1, null));
            if (answer == null || !answer.getResult()) {
                throw new CloudRuntimeException(answer == null ? "Host reservation query did not respond" : answer.getDetails());
            }
            Map<?, ?> reservation = new Gson().fromJson(answer.getDetails(), Map.class);
            info.reservationstate = (String) reservation.get("state");
            info.reservedbytes = ((Number) reservation.get("reservedBytes")).longValue();
        } catch (Exception e) { info.reservationerror = e.getMessage(); }
        return info;
    }

    private BackupStagingInfoResponse.ArtifactInfo artifactInfo(ThirdPartyBackupManifest.Artifact artifact,
            String volumeId, Integer index, boolean metadata, String backupId, BackupStagingInfoResponse previous) {
        BackupStagingInfoResponse.ArtifactInfo info = new BackupStagingInfoResponse.ArtifactInfo();
        info.index = index;
        info.volumeid = volumeId;
        info.path = artifact.path;
        info.backupid = artifact.backupUuid;
        info.owned = backupId.equals(artifact.backupUuid);
        info.metadata = metadata;
        info.externalid = artifact.externalId;
        info.backuptime = artifact.backupTime;
        info.externaljobid = artifact.jobId;
        info.completed = artifact.completed;
        info.terminal = artifact.externalTerminal || artifact.completed;
        info.submissionpending = artifact.submissionPending;
        info.failure = artifact.externalFailure;
        if (previous != null) {
            previous.artifact.stream().filter(old -> info.path.equals(old.path) && java.util.Objects.equals(info.externalid, old.externalid))
                    .findFirst().ifPresent(old -> {
                        info.availability = old.availability;
                        info.availabilityerror = old.availabilityerror;
                        info.availabilitycheckedat = old.availabilitycheckedat;
                    });
        }
        return info;
    }

    private static java.util.Date historyDate(long timestamp) { return timestamp > 0 ? new java.util.Date(timestamp) : null; }

    private void applyRestoreHistory(BackupStagingInfoResponse.ArtifactInfo info, ThirdPartyBackupRestore.Request request,
            ThirdPartyBackupRestore.Record record, ThirdPartyBackupRestore.Operation operation, ThirdPartyBackupRestore.Result legacy) {
        ThirdPartyBackupRestore.Result result = record == null ? (legacy != null && legacy.sequence == request.sequence ? legacy : null) : record.result;
        info.chainindex = request.metadata ? null : request.chainIndex;
        info.destination = record == null ? request.destination : record.request.destination;
        info.requestrecorded = record != null;
        info.externaljobid = result == null ? null : result.jobId;
        info.completed = result != null && result.completed;
        info.terminal = result != null && (result.externalTerminal || result.completed);
        info.submissionpending = result != null && result.submissionPending;
        info.failure = result == null ? null : result.failure;
        int legacyThrough = operation == null ? (legacy == null ? -1 : legacy.sequence) : operation.legacyThroughSequence;
        info.restorestate = result != null && result.notSubmitted ? "NOT_SUBMITTED" : info.completed ? "COMPLETED" : info.terminal ? "FAILED" : info.submissionpending ? "UNCONFIRMED"
                : StringUtils.isNotBlank(info.externaljobid) ? "RUNNING" : record != null ? "RECORDED"
                : request.sequence <= legacyThrough ? "NOT_RECORDED" : "NOT_STARTED";
        info.submittedat = result == null ? null : historyDate(result.submittedAt);
        if (record != null) {
            info.recordedat = historyDate(record.recordedAt);
            info.completedat = historyDate(record.completedAt);
            info.terminalat = historyDate(record.terminalAt);
            info.acknowledgedat = historyDate(record.acknowledgedAt);
            info.checkedat = historyDate(record.checkedAt);
            info.queryerror = record.queryError;
        }
    }

    @Override
    public BackupStagingInfoResponse manage(Backup backup, Host workerHost, String operation, String jobId,
            String action, Integer artifactIndex, String externalJobId, Transfer transfer, RestoreTransfer restoreTransfer, ArtifactInventory inventory) {
        admissionKey(operation);
        if (!java.util.Set.of("RECHECK", "LINK_JOB", "RETRY_CLEANUP").contains(StringUtils.defaultString(action))) {
            throw new CloudRuntimeException("Staging action must be RECHECK, LINK_JOB or RETRY_CLEANUP");
        }
        if ("LINK_JOB".equals(action) && (artifactIndex == null || StringUtils.isBlank(externalJobId))) {
            throw new CloudRuntimeException("LINK_JOB requires an artifact index and an external Job ID");
        }
        GlobalLock lock = GlobalLock.getInternLock("RESTORE".equals(operation)
                ? "backup.volume.restore." + jobId : "backup.volume." + backup.getUuid());
        boolean acquired = false;
        try {
            acquired = lock.lock(5);
            if (!acquired) { throw new CloudRuntimeException("Staging job is being reconciled; retry shortly"); }
            try (Lease lease = acquireLifecycle(backup.getVmId())) {
                BackupVO current = stagingBackup(backup.getId());
                if (!jobId.equals(stagingAttempt(current, operation))) { throw new CloudRuntimeException("Stale staging attempt; refresh its details"); }
                if ("RETRY_CLEANUP".equals(action) && ("BACKUP".equals(operation)
                        ? !ThirdPartyBackupVolumeService.needsBackupCleanup(current)
                        : "COMPLETED".equals(current.getDetail(ThirdPartyBackupRestore.CLEANUP_STATE_KEY)))) {
                    return inspectLocked(current, operation);
                }
                if (StringUtils.isNotBlank(current.getDetail(ThirdPartyBackupManifest.DELETE_PROGRESS_KEY))) {
                    throw new CloudRuntimeException("This backup is being deleted; resume its existing deletion instead");
                }
                Host host = getWorkerHost(current, operation);
                if (host == null && !"RECHECK".equals(action)) { throw new CloudRuntimeException("Worker Host is no longer registered"); }
                if (host != null && (workerHost == null || host.getId() != workerHost.getId())) {
                    throw new CloudRuntimeException("Worker Host changed; refresh the operation before retrying");
                }
                String requeryError = null;
                try {
                    if ("BACKUP".equals(operation)) {
                        manageBackup(current, host, action, artifactIndex, externalJobId, transfer);
                    } else {
                        manageRestore(current, host, action, artifactIndex, externalJobId, restoreTransfer);
                    }
                } catch (Exception e) {
                    if ("RECHECK".equals(action)) { requeryError = e.getMessage(); }
                    else {
                        if ("RETRY_CLEANUP".equals(action)) {
                            if ("RESTORE".equals(operation)) { saveRestoreCleanup(current.getId(), jobId, "WAITING", e.getMessage()); }
                            else if (isFailedPipeline(current)) { saveCleanupState(current.getId(), "WAITING", e.getMessage()); }
                        }
                        throw e;
                    }
                }
                current = stagingBackup(backup.getId());
                BackupStagingInfoResponse info = inspectLocked(current, operation);
                info.reconciliationerror = requeryError;
                if ("RECHECK".equals(action)) {
                    ThirdPartyBackupAdmission.Entry entry = readAdmission(current.getId(), operation);
                    if (entry != null && jobId.equals(entry.jobId) && "ADMITTING".equals(entry.state)
                            && "RESERVED".equals(info.reservationstate)) {
                        observeAdmitted(current.getId(), host, jobId, operation);
                        info.stagingqueue = new org.apache.cloudstack.api.response.BackupStagingQueueResponse(readAdmission(current.getId(), operation));
                    }
                    ThirdPartyBackupManifest manifest = "RESTORE".equals(operation) ? restorePlan(current).manifest
                            : ThirdPartyBackupManifest.fromJson(current.getDetail(ThirdPartyBackupManifest.DETAIL_KEY));
                    for (BackupStagingInfoResponse.ArtifactInfo artifact : info.artifact) {
                        artifact.availability = "UNKNOWN";
                        artifact.availabilityerror = null;
                        artifact.availabilitycheckedat = new java.util.Date();
                        ThirdPartyBackupManifest.Artifact source = manifest.getRequiredArtifacts().stream()
                                .filter(value -> value.path.equals(artifact.path)).findFirst().orElseThrow();
                        if (!source.completed || StringUtils.isBlank(source.externalId)) {
                            artifact.availabilityerror = "The backup artifact has no confirmed external catalog reference";
                            continue;
                        }
                        try { artifact.availability = inventory.exists(source) ? "AVAILABLE" : "MISSING"; }
                        catch (RuntimeException e) { artifact.availabilityerror = e.getMessage(); }
                    }
                }
                if ("RESTORE".equals(operation)) {
                    ThirdPartyBackupRestore.Operation recorded = readRestoreOperation(current, restorePlan(current));
                    if (recorded != null) {
                        if (info.hostcheckedat != null && StringUtils.isBlank(info.hosterror)) {
                            recorded.hostState = info.hoststate;
                            recorded.hostCheckedAt = info.hostcheckedat.getTime();
                        }
                        recorded.hostError = info.hosterror;
                        writeRestoreOperation(current.getId(), recorded);
                    }
                }
                // History is already saved per sequence. Do not duplicate it in the inventory cache.
                BackupStagingInfoResponse cached = new BackupStagingInfoResponse();
                cached.stagingjobid = info.stagingjobid;
                cached.reconciliationerror = info.reconciliationerror;
                for (BackupStagingInfoResponse.ArtifactInfo artifact : info.artifact) {
                    BackupStagingInfoResponse.ArtifactInfo inventoryInfo = new BackupStagingInfoResponse.ArtifactInfo();
                    inventoryInfo.path = artifact.path;
                    inventoryInfo.externalid = artifact.externalid;
                    inventoryInfo.availability = artifact.availability;
                    inventoryInfo.availabilityerror = artifact.availabilityerror;
                    inventoryInfo.availabilitycheckedat = artifact.availabilitycheckedat;
                    cached.artifact.add(inventoryInfo);
                }
                backupDetailsDao.addDetail(current.getId(), MANUAL_INSPECTION_KEY + "." + operation, new Gson().toJson(cached), false);
                return info;
            }
        } catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new CloudRuntimeException("Staging reconciliation could not be confirmed: " + e.getMessage(), e); }
        finally {
            if (acquired) { lock.unlock(); }
            lock.releaseRef();
        }
    }

    private void manageBackup(BackupVO backup, Host host, String action, Integer index, String jobId, Transfer transfer) throws Exception {
        ThirdPartyBackupManifest manifest = ThirdPartyBackupManifest.fromJson(backup.getDetail(ThirdPartyBackupManifest.DETAIL_KEY));
        if (!"LINK_JOB".equals(action) && host != null
                && (backup.getStatus() == Backup.Status.BackingUp || isFailedPipeline(backup))
                && reconcileBackupStart(backup, host, manifest)) { return; }
        if ("RECHECK".equals(action) && host != null && requestPendingBackupCancellation(backup, host)) { return; }
        java.util.List<ThirdPartyBackupManifest.Artifact> owned = manifest.getOwnedArtifacts();
        if ("LINK_JOB".equals(action)) {
            if (index < 0 || index >= owned.size()) { throw new CloudRuntimeException("Selected owned artifact is unavailable"); }
            ThirdPartyBackupManifest.Artifact artifact = owned.get(index);
            if (artifact.completed || artifact.externalTerminal || artifact.submittedAt <= 0
                    || (!artifact.submissionPending && StringUtils.isBlank(artifact.jobId))) {
                throw new CloudRuntimeException("Only a submitted, unresolved artifact can be connected to an external job");
            }
            if (StringUtils.isNotBlank(artifact.jobId) && !artifact.jobId.startsWith("policy:") && !jobId.equals(artifact.jobId)) {
                throw new CloudRuntimeException("This artifact is already connected to a different external job");
            }
            ThirdPartyBackupManifest.Artifact result = transfer.link(copyArtifact(artifact), artifact == manifest.getMetadata(), jobId);
            if (result == null || !jobId.equals(result.jobId)) { throw new CloudRuntimeException("External Job ID verification failed"); }
            applyRecoveredArtifact(backup, manifest, artifact, result);
        } else if ("RECHECK".equals(action)) {
            java.util.List<String> errors = new java.util.ArrayList<>();
            for (ThirdPartyBackupManifest.Artifact artifact : owned) {
                if (artifact.completed || artifact.externalTerminal || (!artifact.submissionPending && StringUtils.isBlank(artifact.jobId))) { continue; }
                try {
                    ThirdPartyBackupManifest.Artifact result = transfer.recover(copyArtifact(artifact), artifact == manifest.getMetadata());
                    if (result != null && StringUtils.isNotBlank(result.jobId)) { applyRecoveredArtifact(backup, manifest, artifact, result); }
                    else { errors.add("External submission remains unconfirmed: " + artifact.path); }
                } catch (RuntimeException e) { errors.add(artifact.path + ": " + e.getMessage()); }
            }
            if (!errors.isEmpty()) { throw new CloudRuntimeException(String.join("; ", errors)); }
        } else {
            if (!isFailedPipeline(backup)) { throw new CloudRuntimeException("Only a failed or canceled source pipeline permits staging cleanup retry"); }
            if (!"COMPLETED".equals(backup.getDetail(ThirdPartyBackupManifest.CLEANUP_STATE_KEY))
                    && !hostTerminal(host, "BACKUP", backup.getUuid())) {
                throw new CloudRuntimeException("Host engine termination is unconfirmed; staging reservation is retained");
            }
            reconcileFailedBackup(backup, host, manifest, transfer);
        }
    }

    private ThirdPartyBackupManifest.Artifact copyArtifact(ThirdPartyBackupManifest.Artifact artifact) {
        return new Gson().fromJson(new Gson().toJson(artifact), ThirdPartyBackupManifest.Artifact.class);
    }

    private void applyRecoveredArtifact(BackupVO backup, ThirdPartyBackupManifest manifest,
            ThirdPartyBackupManifest.Artifact artifact, ThirdPartyBackupManifest.Artifact result) {
        if (!artifact.path.equals(result.path) || !artifact.backupUuid.equals(result.backupUuid)
                || !java.util.Objects.equals(artifact.sha256, result.sha256)) {
            throw new CloudRuntimeException("External result does not match the selected volume artifact");
        }
        if (StringUtils.isNotBlank(artifact.jobId) && !artifact.jobId.startsWith("policy:") && !artifact.jobId.equals(result.jobId)) {
            throw new CloudRuntimeException("Recovered external Job ID conflicts with the persisted artifact reference");
        }
        artifact.jobId = result.jobId;
        artifact.sourceHost = result.sourceHost;
        artifact.externalId = result.externalId;
        artifact.backupTime = result.backupTime;
        artifact.completed = result.completed;
        artifact.externalTerminal = result.externalTerminal || result.completed;
        artifact.externalFailure = result.externalFailure;
        artifact.submissionPending = false;
        backupDetailsDao.addDetail(backup.getId(), ThirdPartyBackupManifest.DETAIL_KEY, manifest.toJson(), false);
    }

    private void manageRestore(BackupVO backup, Host host, String action, Integer index, String jobId, RestoreTransfer transfer) throws Exception {
        ThirdPartyBackupRestore.Plan plan = restorePlan(backup);
        ensureRestoreOperation(backup, plan);
        if (!"LINK_JOB".equals(action) && host != null && reconcilePendingRestoreCancellation(backup, host, plan, transfer)) { return; }
        if ("LINK_JOB".equals(action)) {
            ThirdPartyBackupRestore.Record record = index == null ? null : readRestoreRecord(backup, plan, index);
            if (record == null || !unresolvedRestore(record)) {
                throw new CloudRuntimeException("Only a recorded, submitted and unresolved restore request can be connected");
            }
            if (StringUtils.isNotBlank(record.result.jobId) && !jobId.equals(record.result.jobId)) {
                throw new CloudRuntimeException("This restore request is already connected to a different external job");
            }
            try {
                ThirdPartyBackupRestore.Result result = transfer.link(record.request, copyRestoreResult(record.result), jobId);
                if (result == null || !jobId.equals(result.jobId)) { throw new CloudRuntimeException("External restore Job ID verification failed"); }
                saveRestoreResult(backup, plan, record, result);
            } catch (RuntimeException e) {
                record.checkedAt = System.currentTimeMillis();
                record.queryError = e.getMessage();
                persistRestoreRecord(backup, plan, record);
                throw e;
            }
        } else {
            recoverRestoreRecords(backup, plan, transfer);
            if ("RECHECK".equals(action)) {
                java.util.List<String> errors = new java.util.ArrayList<>();
                for (ThirdPartyBackupRestore.Record record : restoreRecords(backup, plan)) {
                    if (StringUtils.isNotBlank(record.queryError)) { errors.add("Sequence " + record.request.sequence + ": " + record.queryError); }
                }
                if (!errors.isEmpty()) { throw new CloudRuntimeException(String.join("; ", errors)); }
            }
        }
        if ("RETRY_CLEANUP".equals(action)) {
            Answer answer = agentManager.send(host.getId(), new AblestackRestoreJobStatusCommand(plan.jobId, null, 0));
            BackupAnswer status = answer instanceof BackupAnswer && answer.getResult() ? (BackupAnswer) answer : null;
            recordRestoreHost(backup, plan, status, status == null ? "Host restore engine status is unconfirmed" : null);
            if (status != null && "UNKNOWN".equals(status.getState()) && finishUnstartedRestore(backup, host, plan, true)) { return; }
            if (status == null || !java.util.Set.of("COMPLETED", "FAILED", "INTERRUPTED", "CANCELED")
                    .contains(StringUtils.defaultString(status.getState()))) {
                throw new CloudRuntimeException("Host restore engine termination is unconfirmed; reservation is retained");
            }
            requireRestoreWritersTerminated(backup, plan);
            cleanupRestore(backup, host, plan, transfer);
        }
    }

    private String admissionKey(String operation) {
        if ("BACKUP".equals(operation)) { return ThirdPartyBackupAdmission.BACKUP_KEY; }
        if ("RESTORE".equals(operation)) { return ThirdPartyBackupAdmission.RESTORE_KEY; }
        throw new CloudRuntimeException("Staging operation must be BACKUP or RESTORE");
    }

    private ThirdPartyBackupAdmission.Entry readAdmission(long backupId, String operation) {
        BackupDetailVO detail = backupDetailsDao.findDetail(backupId, admissionKey(operation));
        return detail == null ? null : new Gson().fromJson(detail.getValue(), ThirdPartyBackupAdmission.Entry.class);
    }

    private void saveAdmission(long backupId, ThirdPartyBackupAdmission.Entry entry) {
        backupDetailsDao.addDetail(backupId, admissionKey(entry.operation), new Gson().toJson(entry), false);
    }

    private ThirdPartyBackupAdmission.Entry createAdmission(long backupId, Host host, String jobId, String operation) {
        ThirdPartyBackupAdmission.Entry entry = readAdmission(backupId, operation);
        if (entry != null && jobId.equals(entry.jobId)) {
            if (entry.hostId != host.getId() || !operation.equals(entry.operation)) {
                throw new CloudRuntimeException("Staging registration differs from the recorded Worker Host or operation");
            }
            return entry;
        }
        if (entry != null && !"RELEASED".equals(entry.state)) {
            throw new CloudRuntimeException("The previous staging attempt has not completed cleanup");
        }
        entry = new ThirdPartyBackupAdmission.Entry();
        entry.jobId = jobId;
        entry.operation = operation;
        entry.hostId = host.getId();
        entry.clusterId = host.getClusterId();
        entry.queuedAt = System.currentTimeMillis();
        entry.deadline = entry.queuedAt + TimeUnit.SECONDS.toMillis(BackupManager.ThirdPartyStagingQueueTimeout.value());
        entry.state = "WAITING";
        entry.reason = "PREPARING_ADMISSION";
        saveAdmission(backupId, entry);
        return entry;
    }

    private java.util.List<ThirdPartyBackupAdmission.Entry> admissionEntries() {
        java.util.List<ThirdPartyBackupAdmission.Entry> entries = new java.util.ArrayList<>();
        for (String key : java.util.List.of(ThirdPartyBackupAdmission.BACKUP_KEY, ThirdPartyBackupAdmission.RESTORE_KEY)) {
            for (BackupDetailVO detail : backupDetailsDao.findDetails(key)) {
                entries.add(new Gson().fromJson(detail.getValue(), ThirdPartyBackupAdmission.Entry.class));
            }
        }
        return entries;
    }

    private void registerAdmission(long backupId, Host host, String jobId, String operation) {
        GlobalLock lock = GlobalLock.getInternLock("backup.thirdparty.staging.admission");
        boolean acquired = false;
        try {
            acquired = lock.lock(5);
            if (!acquired) { throw new CloudRuntimeException("Staging queue registration will be retried"); }
            createAdmission(backupId, host, jobId, operation);
        } finally {
            if (acquired) { lock.unlock(); }
            lock.releaseRef();
        }
    }

    private void observeAdmitted(long backupId, Host host, String jobId, String operation) {
        GlobalLock lock = GlobalLock.getInternLock("backup.thirdparty.staging.admission");
        boolean acquired = false;
        try {
            acquired = lock.lock(5);
            if (!acquired) { throw new CloudRuntimeException("Staging admission receipt will be reconciled shortly"); }
            ThirdPartyBackupAdmission.Entry entry = createAdmission(backupId, host, jobId, operation);
            // A normal artifact request proves the Host passed admission, even if its
            // grant response was lost before the controller saved ADMITTED.
            if ("WAITING".equals(entry.state) || "ADMITTING".equals(entry.state)) {
                if ("RESTORE".equals(operation)) {
                    BackupDetailVO detail = backupDetailsDao.findDetail(backupId, ThirdPartyBackupRestore.PLAN_KEY);
                    ThirdPartyBackupRestore.Plan plan = detail == null ? null : new Gson().fromJson(detail.getValue(), ThirdPartyBackupRestore.Plan.class);
                    if (plan != null && plan.primaryCapacityVersion == 1
                            && (entry.capacityVersion != 1 || entry.primaryClaims == null || entry.primaryClaims.isEmpty())) {
                        throw new CloudRuntimeException("Restore primary capacity reservation is unconfirmed; no external transfer is allowed");
                    }
                }
                entry.state = "ADMITTED";
                entry.reason = null;
                saveAdmission(backupId, entry);
            }
        } finally {
            if (acquired) { lock.unlock(); }
            lock.releaseRef();
        }
    }

    private String slotReason(ThirdPartyBackupAdmission.Entry entry, java.util.List<ThirdPartyBackupAdmission.Entry> entries) {
        long host = entries.stream().filter(e -> e.occupiesSlot() && e.hostId == entry.hostId).count();
        long cluster = entries.stream().filter(e -> e.occupiesSlot() && java.util.Objects.equals(e.clusterId, entry.clusterId)).count();
        long total = entries.stream().filter(ThirdPartyBackupAdmission.Entry::occupiesSlot).count();
        if (host >= BackupManager.ThirdPartyStagingConcurrentHost.value()) { return "HOST_CONCURRENCY_LIMIT"; }
        if (cluster >= BackupManager.ThirdPartyStagingConcurrentCluster.value()) { return "CLUSTER_CONCURRENCY_LIMIT"; }
        if (total >= BackupManager.ThirdPartyStagingConcurrentTotal.value()) { return "STAGING_CONCURRENCY_LIMIT"; }
        return null;
    }

    private void reconcileAdmission(BackupVO backup, Host host, String jobId, String operation, Map<String, Object> request)
            throws com.cloud.exception.AgentUnavailableException, com.cloud.exception.OperationTimedoutException {
        if (!jobId.equals(request.get("jobId")) || !operation.equals(request.get("operation"))) {
            throw new CloudRuntimeException("Host staging admission belongs to another operation");
        }
        if ("RESTORE".equals(operation)) { confirmRestoreStarted(backup, restorePlan(backup)); }
        GlobalLock lock = GlobalLock.getInternLock("backup.thirdparty.staging.admission");
        boolean acquired = false;
        try {
            acquired = lock.lock(1);
            if (!acquired) { return; }
            ThirdPartyBackupAdmission.Entry entry = createAdmission(backup.getId(), host, jobId, operation);
            if (entry.hostId != host.getId()) { throw new CloudRuntimeException("Staging Worker Host changed"); }
            if ("ADMITTED".equals(entry.state) || "RELEASED".equals(entry.state)
                    || "CANCEL_REQUESTED".equals(entry.state) || "CANCEL_PENDING".equals(entry.state)) { return; }
            long requestedBytes = ((Number) request.get("requiredBytes")).longValue();
            if (entry.requiredBytes > 0 && entry.requiredBytes != requestedBytes) {
                throw new CloudRuntimeException("Staging capacity requirement changed during admission");
            }
            entry.requiredBytes = requestedBytes;
            if (entry.requiredBytes <= 0) { throw new CloudRuntimeException("Invalid staging capacity request"); }
            if ("ADMITTING".equals(entry.state)) {
                // Read the durable Host receipt before counting a lost response as a free slot.
                Answer receipt = agentManager.send(host.getId(), new AblestackVolumeStagingCommand(jobId, "ADMISSION_STATUS", -1,
                        entry.admissionToken == null ? null : new Gson().toJson(Map.of("token", entry.admissionToken))));
                if (receipt == null || !receipt.getResult()) { return; }
                @SuppressWarnings("unchecked")
                Map<String, Object> result = new Gson().fromJson(receipt.getDetails(), Map.class);
                if ("ADMITTED".equals(result.get("state"))) {
                    entry.state = "ADMITTED";
                    entry.reason = null;
                    saveAdmission(backup.getId(), entry);
                    return;
                }
                if (!"WAITING".equals(result.get("state"))) { return; }
                if (entry.admissionToken != null && !entry.admissionToken.equals(result.get("fencedToken"))) { return; }
                entry.state = "WAITING";
                // Only the exact Host WAITING receipt proves an uncertain grant never started.
                saveAdmission(backup.getId(), entry);
            }
            entry.capacityReady = false;
            if (System.currentTimeMillis() >= entry.deadline) {
                cancelAdmission(backup.getId(), entry, "Staging queue timeout expired");
                return;
            }
            if (!BackupManager.ThirdPartyStagingEnable.value()) {
                entry.reason = "STAGING_DISABLED";
                saveAdmission(backup.getId(), entry);
                return;
            }
            java.util.List<ThirdPartyBackupAdmission.Entry> entries = admissionEntries();
            String reason = slotReason(entry, entries);
            if (reason != null) {
                entry.reason = reason;
                saveAdmission(backup.getId(), entry);
                return;
            }
            // Recheck the configured mount before creating a reservation on the Host.
            ThirdPartyBackupManifest manifest = ThirdPartyBackupManifest.fromJson(backup.getDetail(ThirdPartyBackupManifest.DETAIL_KEY));
            java.nio.file.Path providerRoot = java.nio.file.Path.of(stagingService.getStageRootPath(manifest.getProvider()));
            java.nio.file.Path savedRoot;
            if ("RESTORE".equals(operation)) {
                ThirdPartyBackupRestore.Plan plan = new Gson().fromJson(backup.getDetail(ThirdPartyBackupRestore.PLAN_KEY),
                        ThirdPartyBackupRestore.Plan.class);
                if (plan == null || !jobId.equals(plan.jobId)) { throw new CloudRuntimeException("Restore admission plan changed"); }
                savedRoot = java.nio.file.Path.of(plan.stageRoot);
            } else {
                savedRoot = java.nio.file.Path.of(manifest.getCurrentArtifacts().get(0).path).getParent().getParent().getParent().getParent();
            }
            if (!providerRoot.getParent().equals(savedRoot)) {
                entry.reason = "STAGING_CONFIGURATION_CHANGED";
                saveAdmission(backup.getId(), entry);
                return;
            }
            long stagingAvailable = stagingService.getAvailableBytes(host, providerRoot.toString());
            String requestedKey = (String) request.get("stagingStorageKey");
            if (entry.stagingStorageKey != null && !entry.stagingStorageKey.equals(requestedKey)) {
                throw new CloudRuntimeException("Staging capacity identity changed during admission");
            }
            entry.stagingStorageKey = requestedKey;
            // Older active jobs lack a reliable cross-Host accounting identity.
            // Require their cleanup before admitting any new consumer of capacity.
            if (entries.stream().anyMatch(e -> e.occupiesSlot() && !e.jobId.equals(jobId)
                    && (e.stagingStorageKey == null || (needsPrimaryClaims(e) && e.capacityVersion != 1)))) {
                entry.reason = "LEGACY_CAPACITY_RESERVATION_UNCONFIRMED";
                saveAdmission(backup.getId(), entry);
                return;
            }
            boolean primaryRequired = "RESTORE".equals(operation) ? restorePlan(backup).primaryCapacityVersion == 1
                    : manifest.getVolumes().stream().anyMatch(volume -> !volume.engine.startsWith("RBD"));
            if (primaryRequired) {
                if ("BACKUP".equals(operation) && ((Number) request.getOrDefault("capacityVersion", 0)).intValue() != 1) {
                    entry.reason = "LEGACY_CAPACITY_RESERVATION_UNCONFIRMED";
                    saveAdmission(backup.getId(), entry);
                    return;
                }
                Answer capacity = agentManager.send(host.getId(), new AblestackVolumeStagingCommand(jobId,
                        "RESTORE".equals(operation) ? "RESTORE_PRIMARY_CAPACITY" : "BACKUP_PRIMARY_CAPACITY", -1, null));
                if (capacity == null || !capacity.getResult()) {
                    entry.reason = "PRIMARY_CAPACITY_UNCONFIRMED";
                    saveAdmission(backup.getId(), entry);
                    return;
                }
                ThirdPartyBackupAdmission.PrimaryCapacity current = new Gson().fromJson(capacity.getDetails(), ThirdPartyBackupAdmission.PrimaryCapacity.class);
                if ("RESTORE".equals(operation)) { validatePrimaryClaims(entry, restorePlan(backup), current); }
                else {
                    int buffer = ((Number) request.getOrDefault("bufferPercent", 0)).intValue();
                    if (buffer < 0 || buffer > 100) { throw new CloudRuntimeException("Invalid backup staging buffer"); }
                    validatePrimaryClaims(entry, current, manifest.getVolumes().stream().mapToLong(volume -> volume.provisionedBytes)
                            .reduce(0, Math::addExact), manifest.getVolumes().stream().mapToLong(volume -> volume.provisionedBytes).max().orElseThrow(), buffer);
                }
                entry.capacityVersion = 1;
                entry.primaryClaims = current.primaryClaims;
                for (ThirdPartyBackupAdmission.PrimaryClaim claim : entry.primaryClaims) {
                    claim.effectiveAvailableBytes = Math.max(0, claim.availableBytes - capacityReserved(claim.storageKey, jobId, entries));
                    long required = claim.storageKey.equals(entry.stagingStorageKey) ? entry.requiredBytes : claim.requiredBytes;
                    if (required > claim.effectiveAvailableBytes) {
                        entry.reason = "PRIMARY_STORAGE_CAPACITY";
                        saveAdmission(backup.getId(), entry);
                        return;
                    }
                }
            }
            if (entry.stagingStorageKey != null) {
                entry.effectiveAvailableBytes = Math.max(0, stagingAvailable - capacityReserved(entry.stagingStorageKey, jobId, entries));
                if (entry.requiredBytes > entry.effectiveAvailableBytes) {
                    entry.reason = "STAGING_CAPACITY";
                    saveAdmission(backup.getId(), entry);
                    return;
                }
            }
            entry.capacityReady = true;
            entry.capacityCheckedAt = System.currentTimeMillis();
            // FIFO applies to ready jobs competing for the same Host or capacity domain.
            // An undersized/misconfigured storage domain must not block unrelated workers.
            if (entries.stream().anyMatch(e -> "WAITING".equals(e.state) && e.capacityReady
                    && e.capacityCheckedAt >= System.currentTimeMillis() - TimeUnit.SECONDS.toMillis(15)
                    && e.deadline > System.currentTimeMillis() && !e.jobId.equals(entry.jobId)
                    && (e.queuedAt < entry.queuedAt || (e.queuedAt == entry.queuedAt && e.jobId.compareTo(entry.jobId) < 0))
                    && competesForCapacity(entry, e) && slotReason(e, entries) == null)) {
                entry.reason = "EARLIER_WAITING_JOB";
                saveAdmission(backup.getId(), entry);
                return;
            }
            // Persist every primary claim before sending a grant. The same global DB lock
            // protects capacity checks, reservations, grant recovery and release across Hosts.
            entry.state = "ADMITTING";
            entry.reason = "HOST_ADMISSION_UNCONFIRMED";
            entry.admissionToken = java.util.UUID.randomUUID().toString();
            saveAdmission(backup.getId(), entry);
            Answer answer = agentManager.send(host.getId(), new AblestackVolumeStagingCommand(jobId, "ADMISSION", -1,
                    new Gson().toJson(Map.of("token", entry.admissionToken))));
            if (answer == null || !answer.getResult()) { return; }
            @SuppressWarnings("unchecked")
            Map<String, Object> result = new Gson().fromJson(answer.getDetails(), Map.class);
            if ("ADMITTED".equals(result.get("state"))) {
                entry.state = "ADMITTED";
                entry.reason = null;
            } else if ("WAITING".equals(result.get("state"))) {
                if (!entry.admissionToken.equals(result.get("fencedToken"))) { return; }
                entry.state = "WAITING";
                entry.reason = (String) result.get("reason");
                entry.effectiveAvailableBytes = ((Number) result.get("effectiveAvailableBytes")).longValue();
            } else if ("CANCELED".equals(result.get("state"))) {
                entry.state = "CANCEL_REQUESTED";
                entry.reason = (String) result.get("reason");
            }
            saveAdmission(backup.getId(), entry);
        } finally {
            if (acquired) { lock.unlock(); }
            lock.releaseRef();
        }
    }

    private boolean competesForCapacity(ThirdPartyBackupAdmission.Entry first, ThirdPartyBackupAdmission.Entry second) {
        if (first.hostId == second.hostId || java.util.Objects.equals(first.clusterId, second.clusterId)) { return true; }
        java.util.Set<String> keys = new java.util.HashSet<>();
        if (first.stagingStorageKey != null) { keys.add(first.stagingStorageKey); }
        if (first.primaryClaims != null) { first.primaryClaims.forEach(claim -> keys.add(claim.storageKey)); }
        return keys.contains(second.stagingStorageKey) || (second.primaryClaims != null
                && second.primaryClaims.stream().anyMatch(claim -> keys.contains(claim.storageKey)));
    }

    private long capacityReserved(String storageKey, String jobId, java.util.List<ThirdPartyBackupAdmission.Entry> entries) {
        long reserved = 0;
        for (ThirdPartyBackupAdmission.Entry other : entries) {
            if (!other.occupiesSlot() || jobId.equals(other.jobId)) { continue; }
            if (storageKey.equals(other.stagingStorageKey)) { reserved = Math.addExact(reserved, other.requiredBytes); }
            if (other.primaryClaims == null) { continue; }
            for (ThirdPartyBackupAdmission.PrimaryClaim claim : other.primaryClaims) {
                // Shared primary scratch is already included in that job's staging reservation.
                if (storageKey.equals(claim.storageKey) && !storageKey.equals(other.stagingStorageKey)) {
                    reserved = Math.addExact(reserved, claim.requiredBytes);
                }
            }
        }
        return reserved;
    }

    private void validatePrimaryClaims(ThirdPartyBackupAdmission.Entry entry, ThirdPartyBackupRestore.Plan plan,
            ThirdPartyBackupAdmission.PrimaryCapacity current) {
        long expected = 0;
        long largest = 0;
        for (int i = 0; i < plan.volumeUuids.size(); i++) {
            String uuid = plan.volumeUuids.get(i);
            ThirdPartyBackupManifest.Volume volume = plan.manifest.getVolumes().stream().filter(v -> uuid.equals(v.uuid)).findFirst().orElseThrow();
            long bytes = plan.targetVolumeBytes == null ? volume.provisionedBytes : plan.targetVolumeBytes.get(i);
            expected = Math.addExact(expected, bytes);
            largest = Math.max(largest, volume.provisionedBytes);
        }
        validatePrimaryClaims(entry, current, expected, largest, plan.bufferPercent);
    }

    private boolean needsPrimaryClaims(ThirdPartyBackupAdmission.Entry entry) {
        if ("RESTORE".equals(entry.operation)) { return true; }
        BackupDetailVO detail = backupDetailsDao.findDetail(admissionBackupId(entry), ThirdPartyBackupManifest.DETAIL_KEY);
        return detail == null || ThirdPartyBackupManifest.fromJson(detail.getValue()).getVolumes().stream()
                .anyMatch(volume -> !volume.engine.startsWith("RBD"));
    }

    private long admissionBackupId(ThirdPartyBackupAdmission.Entry entry) {
        // Backup attempts use the logical backup UUID as their job ID.
        Backup backup = backupDao.findByUuid(entry.jobId);
        if (backup == null) { throw new CloudRuntimeException("Admission backup is missing; capacity ownership is unconfirmed"); }
        return backup.getId();
    }

    private void validatePrimaryClaims(ThirdPartyBackupAdmission.Entry entry, ThirdPartyBackupAdmission.PrimaryCapacity current,
            long expected, long largest, int bufferPercent) {
        if (current == null || current.version != 1 || !entry.jobId.equals(current.jobId)
                || current.stagingStorageKey == null || !current.stagingStorageKey.equals(entry.stagingStorageKey)
                || current.primaryClaims == null || current.primaryClaims.isEmpty()) {
            throw new CloudRuntimeException("Host primary capacity report differs from the admission plan");
        }
        java.util.Set<String> keys = new java.util.HashSet<>();
        long volumeBytes = 0;
        long shared = 0;
        for (ThirdPartyBackupAdmission.PrimaryClaim claim : current.primaryClaims) {
            if (claim.storageKey == null || !keys.add(claim.storageKey) || claim.volumeBytes <= 0 || claim.availableBytes < 0
                    || claim.requiredBytes != Math.addExact(claim.volumeBytes, Math.max(10L * 1024 * 1024 * 1024, claim.volumeBytes / 5))) {
                throw new CloudRuntimeException("Invalid primary storage capacity claim");
            }
            volumeBytes = Math.addExact(volumeBytes, claim.volumeBytes);
            if (claim.storageKey.equals(current.stagingStorageKey)) { shared = claim.requiredBytes; }
        }
        long staged = Math.addExact(largest, Math.floorDiv(Math.addExact(Math.multiplyExact(largest, bufferPercent), 99), 100));
        if (volumeBytes != expected || entry.requiredBytes != Math.addExact(staged, shared)) {
            throw new CloudRuntimeException("Primary/staging capacity request does not match the selected volumes");
        }
        if (entry.capacityVersion == 1) {
            if (entry.primaryClaims == null || entry.primaryClaims.size() != current.primaryClaims.size()) {
                throw new CloudRuntimeException("Primary storage reservation identity changed");
            }
            for (ThirdPartyBackupAdmission.PrimaryClaim claim : current.primaryClaims) {
                if (entry.primaryClaims.stream().noneMatch(old -> claim.storageKey.equals(old.storageKey)
                        && claim.volumeBytes == old.volumeBytes && claim.requiredBytes == old.requiredBytes)) {
                    throw new CloudRuntimeException("Primary storage reservation identity or size changed");
                }
            }
        }
    }

    private boolean cancelAdmission(long backupId, ThirdPartyBackupAdmission.Entry entry, String reason)
            throws com.cloud.exception.AgentUnavailableException, com.cloud.exception.OperationTimedoutException {
        Answer answer = agentManager.send(entry.hostId, new AblestackVolumeStagingCommand(entry.jobId, "ADMISSION_CANCEL", -1, reason));
        if (answer == null || !answer.getResult()) { return false; }
        @SuppressWarnings("unchecked")
        Map<String, Object> result = new Gson().fromJson(answer.getDetails(), Map.class);
        if ("CANCELED".equals(result.get("state"))) {
            entry.state = "CANCEL_REQUESTED";
            entry.reason = (String) result.get("reason");
            saveAdmission(backupId, entry);
            return true;
        }
        if ("ADMITTED".equals(result.get("state"))) {
            entry.state = "ADMITTED";
            entry.reason = null;
            saveAdmission(backupId, entry);
        }
        return false;
    }

    @Override
    public boolean cancelWaiting(Backup backup, String operation, String jobId) {
        GlobalLock lock = GlobalLock.getInternLock("backup.thirdparty.staging.admission");
        boolean acquired = false;
        try {
            acquired = lock.lock(5);
            if (!acquired) { throw new CloudRuntimeException("Staging admission is in progress; retry cancellation shortly"); }
            ThirdPartyBackupAdmission.Entry entry = readAdmission(backup.getId(), operation);
            if (entry == null || !entry.jobId.equals(jobId)) { throw new CloudRuntimeException("Staging queue attempt changed; refresh before canceling"); }
            ThirdPartyBackupStart.Operation start = "BACKUP".equals(operation) ? readBackupStart(backup.getId()) : null;
            if ("RESTORE".equals(operation)) {
                BackupVO current = stagingBackup(backup.getId());
                ThirdPartyBackupRestore.Plan plan = restorePlan(current);
                if (plan.startProtocolVersion == ThirdPartyBackupRestore.START_PROTOCOL_VERSION) {
                    ThirdPartyBackupRestore.Operation restore = ensureRestoreOperation(current, plan);
                    // Validate recorded ownership; saving intent still permits an absent Worker Host.
                    getWorkerHost(current, operation);
                    if (!jobId.equals(plan.jobId) || !operation.equals(entry.operation) || entry.hostId != plan.hostId) {
                        throw new CloudRuntimeException("Cancellation differs from the recorded restore Worker Host or attempt");
                    }
                    if (restore.cancelRequestedAt > 0) { return true; }
                    if (!"WAITING".equals(entry.state) || "COMPLETED".equals(restore.cleanupState)
                            || !restoreRecords(current, plan).isEmpty()) {
                        throw new CloudRuntimeException("Only waiting restore jobs with no external transfer requests can be canceled");
                    }
                    restore.cancelRequestedAt = System.currentTimeMillis();
                    restore.cancelReason = "Restore cancellation requested; waiting for Host termination and staging cleanup";
                    entry.state = "CANCEL_PENDING";
                    entry.reason = restore.cancelReason;
                    com.cloud.utils.db.Transaction.execute((com.cloud.utils.db.TransactionCallback<Boolean>) status -> {
                        writeRestoreOperation(current.getId(), restore);
                        saveAdmission(current.getId(), entry);
                        backupDetailsDao.addDetail(current.getId(), AblestackBackupFrameworkUtils.RESTORE_JOB_STATE_DETAIL, "RUNNING", false);
                        backupDetailsDao.addDetail(current.getId(), AblestackBackupFrameworkUtils.RESTORE_JOB_STEP_DETAIL, "CANCEL_PENDING", false);
                        backupDetailsDao.addDetail(current.getId(), AblestackBackupFrameworkUtils.RESTORE_JOB_PROGRESS_DETAIL, "0", false);
                        return true;
                    });
                    return true;
                }
            }
            if (start != null) {
                BackupVO current = stagingBackup(backup.getId());
                if (!current.getUuid().equals(jobId) || !jobId.equals(start.plan.jobId) || !"BACKUP".equals(entry.operation)
                        || entry.hostId != start.plan.hostId
                        || (current.getHostId() != null && current.getHostId().longValue() != start.plan.hostId)) {
                    throw new CloudRuntimeException("Cancellation differs from the recorded backup Worker Host or attempt");
                }
                if (start.cancelRequestedAt > 0) { return true; }
                if (current.getStatus() != Backup.Status.BackingUp || !"WAITING".equals(entry.state)) {
                    throw new CloudRuntimeException("Only waiting staging jobs can be canceled");
                }
                ThirdPartyBackupManifest manifest = ThirdPartyBackupManifest.fromJson(current.getDetail(ThirdPartyBackupManifest.DETAIL_KEY));
                if (hasOwnedTransferAttempt(manifest)) {
                    throw new CloudRuntimeException("External artifact transfers have started; queued cancellation is unavailable");
                }
                start.cancelRequestedAt = System.currentTimeMillis();
                start.cancelReason = "Backup cancellation requested; waiting for Host termination and staging cleanup";
                entry.state = "CANCEL_PENDING";
                entry.reason = start.cancelReason;
                com.cloud.utils.db.Transaction.execute((com.cloud.utils.db.TransactionCallback<Boolean>) status -> {
                    saveBackupStart(current.getId(), start);
                    saveAdmission(current.getId(), entry);
                    backupDetailsDao.addDetail(current.getId(), AblestackBackupFrameworkUtils.BACKUP_CANCELLATION_DETAIL,
                            start.cancelReason, false);
                    return true;
                });
                // No Host call is needed to accept the intent. The same coordinator fences an
                // unstarted dispatch or stops the initialized engine, retaining capacity until cleanup.
                return true;
            }
            if ("CANCEL_REQUESTED".equals(entry.state)) { return true; }
            if (!"WAITING".equals(entry.state)) { throw new CloudRuntimeException("Only waiting staging jobs can be canceled"); }
            if (!cancelAdmission(backup.getId(), entry, "Staging queue canceled by operator")) {
                throw new CloudRuntimeException("Host did not confirm queue cancellation; refresh and retry");
            }
            return true;
        } catch (com.cloud.exception.AgentUnavailableException | com.cloud.exception.OperationTimedoutException e) {
            throw new CloudRuntimeException("Worker Host is unavailable; queue cancellation is unconfirmed", e);
        } finally {
            if (acquired) { lock.unlock(); }
            lock.releaseRef();
        }
    }

    private void releaseAdmission(long backupId, String operation, String jobId) {
        GlobalLock lock = GlobalLock.getInternLock("backup.thirdparty.staging.admission");
        boolean acquired = false;
        try {
            acquired = lock.lock(5);
            if (!acquired) { throw new CloudRuntimeException("Staging slot release will be retried"); }
            ThirdPartyBackupAdmission.Entry entry = readAdmission(backupId, operation);
            if (entry != null && entry.jobId.equals(jobId)) {
                entry.state = "RELEASED";
                saveAdmission(backupId, entry);
            }
        } finally {
            if (acquired) { lock.unlock(); }
            lock.releaseRef();
        }
    }

    private static class DeleteProgress {
        java.util.Set<String> completed = new java.util.LinkedHashSet<>();
        Map<String, String> pendingTickets = new java.util.LinkedHashMap<>();
        boolean complete;
        boolean policyExpiration;
        String failure;
    }

    @Override
    public Lease acquireLifecycle(long vmId) {
        GlobalLock lock = GlobalLock.getInternLock("backup.volume.lifecycle." + vmId);
        if (!lock.lock(5)) {
            lock.releaseRef();
            throw new CloudRuntimeException("Backup creation or artifact cleanup is in progress for this VM");
        }
        return () -> { try { lock.unlock(); } finally { lock.releaseRef(); } };
    }

    @Override
    public boolean delete(Backup backup, ArtifactCleanup cleanup) {
        try (Lease lease = acquireLifecycle(backup.getVmId())) {
            return deleteLocked(backup.getId(), cleanup);
        }
    }

    private boolean deleteLocked(long backupId, ArtifactCleanup cleanup) {
        return deleteLocked(backupId, cleanup, null);
    }

    private boolean deleteLocked(long backupId, ArtifactCleanup cleanup, ArtifactInventory inventory) {
        BackupVO tracked = backupDao.findById(backupId);
        if (tracked == null) { return true; }
        backupDao.loadDetails(tracked);
        ThirdPartyBackupManifest manifest = ThirdPartyBackupManifest.fromJson(tracked.getDetail(ThirdPartyBackupManifest.DETAIL_KEY));
        DeleteProgress progress = new Gson().fromJson(tracked.getDetail(ThirdPartyBackupManifest.DELETE_PROGRESS_KEY), DeleteProgress.class);
        if (progress == null) { progress = new DeleteProgress(); }
        if (progress.complete) { return true; }
        validateDeletion(tracked, manifest);
        ThirdPartyBackupStart.Operation start = readBackupStart(tracked.getId());
        if (start != null && "START_FAILED".equals(start.state)) {
            if (!confirmedUnstartedBackupCleanup(tracked, manifest, start) || !progress.pendingTickets.isEmpty()) {
                throw new CloudRuntimeException("Unstarted backup cleanup and reservation release must finish before deleting its records");
            }
            // The acknowledged Host start fence proves this job never acquired staging/source
            // resources. Its cleanup and accounting evidence survive Host removal/config changes.
            for (ThirdPartyBackupManifest.Artifact artifact : manifest.getOwnedArtifacts()) {
                progress.completed.add(artifact.path);
            }
            progress.complete = true;
            progress.failure = null;
            final DeleteProgress completed = progress;
            com.cloud.utils.db.Transaction.execute((com.cloud.utils.db.TransactionCallback<Boolean>) status -> {
                saveDeleteProgress(tracked, completed);
                saveCleanupState(tracked.getId(), "COMPLETED", null);
                return true;
            });
            return true;
        }
        if (progress.policyExpiration && inventory == null) {
            throw new CloudRuntimeException("Expiration cleanup must first reconfirm the external catalog during synchronization");
        }
        progress.failure = null;
        saveDeleteProgress(tracked, progress);
        saveCleanupState(tracked.getId(), "RUNNING", null);
        try {
            for (ThirdPartyBackupManifest.Artifact artifact : manifest.getOwnedArtifacts()) {
                String key = artifact.path;
                if (progress.completed.contains(key)) { continue; }
                if (progress.policyExpiration && inventory.exists(artifact)) {
                    throw new CloudRuntimeException("Artifact is still retained by the external policy; expiration cleanup is waiting: " + key);
                }
                if (artifact.submissionPending && StringUtils.isBlank(artifact.jobId)) {
                    throw new CloudRuntimeException("An external artifact submission is unconfirmed; reconcile it before deleting this backup");
                }
                if (StringUtils.isBlank(artifact.jobId) && StringUtils.isBlank(artifact.externalId)) {
                    // This volume never reached the external provider.
                    progress.completed.add(key);
                } else {
                    DeleteResult result = cleanup.delete(artifact, artifact == manifest.getMetadata(), progress.pendingTickets.get(key),
                            progress.policyExpiration);
                    if (result == null) { throw new CloudRuntimeException("External artifact deletion returned no result"); }
                    if (StringUtils.isNotBlank(result.pendingTicket)) { progress.pendingTickets.put(key, result.pendingTicket); }
                    saveDeleteProgress(tracked, progress);
                    if (!result.completed) {
                        throw new CloudRuntimeException("External artifact deletion is pending or failed: " + key
                                + (result.pendingTicket == null ? "" : " (approval ticket: " + result.pendingTicket + ")"));
                    }
                    progress.completed.add(key);
                    progress.pendingTickets.remove(key);
                }
                saveDeleteProgress(tracked, progress);
            }
            cleanupVolumeArtifacts(tracked, manifest);
            progress.complete = true;
            saveDeleteProgress(tracked, progress);
            saveCleanupState(tracked.getId(), "COMPLETED", null);
            return true;
        } catch (RuntimeException e) {
            progress.failure = StringUtils.defaultString(e.getMessage());
            saveDeleteProgress(tracked, progress);
            saveCleanupState(tracked.getId(), "WAITING", progress.failure);
            throw e;
        }
    }

    private void saveDeleteProgress(BackupVO backup, DeleteProgress progress) {
        backupDetailsDao.addDetail(backup.getId(), ThirdPartyBackupManifest.DELETE_PROGRESS_KEY, new Gson().toJson(progress), false);
    }

    private boolean confirmedUnstartedBackupCleanup(BackupVO backup, ThirdPartyBackupManifest manifest, ThirdPartyBackupStart.Operation start) {
        if (!java.util.Set.of(Backup.Status.Failed, Backup.Status.Error, Backup.Status.Canceled).contains(backup.getStatus())
                || !backup.getUuid().equals(start.plan.jobId) || start.checkedAt <= 0
                || (backup.getHostId() != null && backup.getHostId().longValue() != start.plan.hostId)
                || !new Gson().toJsonTree(start.plan.manifest).equals(new Gson().toJsonTree(manifest))
                || hasOwnedTransferAttempt(manifest)
                || !"COMPLETED".equals(backup.getDetail(ThirdPartyBackupManifest.CLEANUP_STATE_KEY))
                || !"COMPLETED".equals(backup.getDetail(ThirdPartyBackupManifest.JOB_CLEANUP_STATE_KEY))
                || !Boolean.TRUE.toString().equals(backup.getDetail(AblestackBackupFrameworkUtils.RESOURCE_COUNT_PENDING_DETAIL))) { return false; }
        ThirdPartyBackupAdmission.Entry entry = readAdmission(backup.getId(), "BACKUP");
        return entry == null || (backup.getUuid().equals(entry.jobId) && "BACKUP".equals(entry.operation)
                && entry.hostId == start.plan.hostId && "RELEASED".equals(entry.state));
    }

    private void saveCleanupState(long backupId, String state, String details) {
        backupDetailsDao.addDetail(backupId, ThirdPartyBackupManifest.CLEANUP_STATE_KEY, state, false);
        if (StringUtils.isBlank(details)) {
            backupDetailsDao.removeDetail(backupId, ThirdPartyBackupManifest.CLEANUP_DETAILS_KEY);
        } else {
            backupDetailsDao.addDetail(backupId, ThirdPartyBackupManifest.CLEANUP_DETAILS_KEY, details, false);
        }
    }

    private void validateDeletion(BackupVO backup, ThirdPartyBackupManifest manifest) {
        if ("WAITING".equals(backup.getDetail(ThirdPartyBackupManifest.SOURCE_CLEANUP_STATE_KEY))) {
            trackSourceCleanup(backup.getId());
            throw new CloudRuntimeException("Source snapshot cleanup must finish before deleting its job records");
        }
        if (!backup.getUuid().equals(manifest.getBackupUuid())) {
            throw new CloudRuntimeException("Backup manifest does not belong to this recovery point");
        }
        for (Backup candidate : backupDao.listByVmId(null, backup.getVmId())) {
            BackupVO current = (BackupVO) candidate;
            backupDao.loadDetails(current);
            if (Backup.Status.BackingUp.equals(current.getStatus()) || Backup.Status.Restoring.equals(current.getStatus())
                    || java.util.Set.of("STARTING", "RUNNING").contains(StringUtils.defaultString(
                            current.getDetail(AblestackBackupFrameworkUtils.RESTORE_JOB_STATE_DETAIL)))) {
                throw new CloudRuntimeException("A backup or restore is still active for this VM");
            }
            if (current.getId() == backup.getId() || !ThirdPartyBackupManifest.VOLUME_MODE.equals(
                    current.getDetail(ThirdPartyBackupManifest.MODE_KEY))) { continue; }
            ThirdPartyBackupManifest other = ThirdPartyBackupManifest.fromJson(current.getDetail(ThirdPartyBackupManifest.DETAIL_KEY));
            if (!manifest.getProvider().equals(other.getProvider())) { continue; }
            DeleteProgress otherProgress = new Gson().fromJson(current.getDetail(ThirdPartyBackupManifest.DELETE_PROGRESS_KEY), DeleteProgress.class);
            if ((otherProgress == null || !otherProgress.complete) && other.references(backup.getUuid())) {
                throw new CloudRuntimeException("Another backup still requires this Full/incremental artifact; delete dependent recovery points first");
            }
        }
    }

    private void cleanupVolumeArtifacts(BackupVO backup, ThirdPartyBackupManifest manifest) {
        Host host = getWorkerHost(backup, "BACKUP");
        if (host == null || !com.cloud.host.Status.Up.equals(host.getStatus())) {
            throw new CloudRuntimeException("Source Host is unavailable; checkpoint and staging cleanup remains pending");
        }
        String path = manifest.getMetadata() == null ? java.nio.file.Path.of(manifest.getCurrentArtifacts().get(0).path)
                .getParent().toString() : manifest.getMetadata().path;
        AblestackDeleteBackupCommand command = new AblestackDeleteBackupCommand(path, null, null, null, true);
        command.setBackupProvider(manifest.getProvider());
        command.setVmName(manifest.getVmName());
        command.setCheckpointName(manifest.getTimestamp());
        command.setCleanupCheckpointNames(manifest.getTimestamp());
        backup.getDetails().entrySet().stream().filter(entry -> entry.getKey().endsWith("rbd.disk.paths"))
                .map(Map.Entry::getValue).findFirst().ifPresent(command::setDiskPaths);
        try {
            validateCleanupMount(host, manifest);
            Answer guard = agentManager.send(host.getId(), new AblestackVolumeStagingCommand(backup.getUuid(), "CLEANUP_CHECK", -1, manifest.toJson()));
            if (guard != null && !guard.getResult()) {
                // Every owned external artifact was already confirmed deleted before this local cleanup step.
                Answer failedCleanup = agentManager.send(host.getId(), new AblestackVolumeStagingCommand(backup.getUuid(), "CLEANUP_FAILED", -1, manifest.toJson()));
                if (failedCleanup != null && failedCleanup.getResult()) {
                    guard = agentManager.send(host.getId(), new AblestackVolumeStagingCommand(backup.getUuid(), "CLEANUP_CHECK", -1, manifest.toJson()));
                }
            }
            if (guard == null || !guard.getResult()) {
                throw new CloudRuntimeException("Backup engine cleanup is unconfirmed: " + (guard == null ? "no Host response" : guard.getDetails()));
            }
            Answer answer = agentManager.send(host.getId(), command);
            if (answer == null || !answer.getResult()) {
                throw new CloudRuntimeException("Volume backup checkpoint and staging cleanup failed: "
                        + (answer == null ? "no Host response" : answer.getDetails()));
            }
            Answer records = agentManager.send(host.getId(), new AblestackBackupJobCleanupCommand(backup.getUuid()));
            if (records == null || !records.getResult()) { throw new CloudRuntimeException("Host backup job record cleanup remains pending"); }
        } catch (com.cloud.exception.AgentUnavailableException | com.cloud.exception.OperationTimedoutException e) {
            throw new CloudRuntimeException("Volume backup checkpoint and staging cleanup remains pending", e);
        }
    }

    @Override
    public void reconcileCatalog(Backup backup, ArtifactInventory inventory, ArtifactCleanup cleanup) {
        try (Lease lease = acquireLifecycle(backup.getVmId())) {
            BackupVO tracked = backupDao.findById(backup.getId());
            if (tracked == null) { return; }
            backupDao.loadDetails(tracked);
            if (StringUtils.isNotBlank(tracked.getDetail(ThirdPartyBackupManifest.DELETE_PROGRESS_KEY))) {
                try {
                    if (deleteLocked(tracked.getId(), cleanup, inventory)) {
                        removeDeletedBackupLocked(tracked.getId());
                    }
                } catch (RuntimeException e) {
                    saveCleanupState(tracked.getId(), "WAITING", e.getMessage());
                    logger.debug("Volume backup [{}] cleanup is pending: {}", tracked.getUuid(), e.getMessage());
                }
                return;
            }
            ThirdPartyBackupManifest manifest = ThirdPartyBackupManifest.fromJson(tracked.getDetail(ThirdPartyBackupManifest.DETAIL_KEY));
            if (!manifest.isComplete() || (!Backup.Status.BackedUp.equals(tracked.getStatus())
                    && StringUtils.isBlank(tracked.getDetail(ThirdPartyBackupManifest.CATALOG_FAILURE_KEY)))) { return; }
            manifest.validate(true);
            ThirdPartyBackupManifest.Catalog catalog = new Gson().fromJson(tracked.getDetail(ThirdPartyBackupManifest.CATALOG_KEY),
                    ThirdPartyBackupManifest.Catalog.class);
            if (catalog == null) { catalog = new ThirdPartyBackupManifest.Catalog(); }
            catalog.attemptedAt = System.currentTimeMillis();
            java.util.Set<String> missing = new java.util.LinkedHashSet<>();
            java.util.Set<String> available = new java.util.LinkedHashSet<>();
            RuntimeException queryFailure = null;
            for (ThirdPartyBackupManifest.Artifact artifact : manifest.getRequiredArtifacts()) {
                try {
                    if (!inventory.exists(artifact)) { missing.add(artifact.path); }
                    else { available.add(artifact.path); }
                } catch (RuntimeException e) {
                    // An unavailable query must not hide a confirmed expiration of a different required image.
                    if (queryFailure == null) { queryFailure = e; }
                }
            }
            if (queryFailure != null) {
                catalog.expiredSince = 0;
                java.util.Set<String> confirmed = new java.util.LinkedHashSet<>(catalog.missing);
                confirmed.removeAll(available);
                confirmed.addAll(missing);
                catalog.missing = new java.util.ArrayList<>(confirmed);
                if (!confirmed.isEmpty()) {
                    // A confirmed missing image is enough to block restore, even when another query fails.
                    if (!"EXPIRED".equals(catalog.state)) { catalog.state = "PARTIAL"; }
                    markCatalogFailure(tracked, confirmed);
                    saveCleanupState(tracked.getId(), "RETAINED", "Remaining artifact availability is unconfirmed; no expiration cleanup was requested");
                }
                catalog.error = StringUtils.defaultString(queryFailure.getMessage(), "External catalog query could not be confirmed");
                backupDetailsDao.addDetail(tracked.getId(), ThirdPartyBackupManifest.CATALOG_KEY, new Gson().toJson(catalog), false);
                // Keep the last confirmed availability and every artifact on transient failures.
                throw queryFailure;
            }
            catalog.checkedAt = catalog.attemptedAt;
            catalog.error = null;
            catalog.missing = new java.util.ArrayList<>(missing);
            boolean ownedExpired = manifest.getOwnedArtifacts().stream().allMatch(artifact -> missing.contains(artifact.path));
            if (!ownedExpired) { catalog.expiredSince = 0; }
            else if (catalog.expiredSince <= 0) { catalog.expiredSince = catalog.attemptedAt; }
            catalog.state = missing.isEmpty() ? "AVAILABLE" : ownedExpired ? "EXPIRED" : "PARTIAL";
            backupDetailsDao.addDetail(tracked.getId(), ThirdPartyBackupManifest.CATALOG_KEY, new Gson().toJson(catalog), false);
            if (missing.isEmpty()) {
                if (StringUtils.isNotBlank(tracked.getDetail(ThirdPartyBackupManifest.CATALOG_FAILURE_KEY))) {
                    backupDetailsDao.removeDetail(tracked.getId(), ThirdPartyBackupManifest.CATALOG_FAILURE_KEY);
                    BackupVO update = backupDao.createForUpdate(tracked.getId());
                    update.setStatus(Backup.Status.BackedUp);
                    backupDao.update(tracked.getId(), update);
                }
                saveCleanupState(tracked.getId(), "NONE", null);
                return;
            }
            markCatalogFailure(tracked, missing);
            if (ownedExpired) {
                if (catalog.attemptedAt - catalog.expiredSince < EXPIRATION_CONFIRMATION_MS) {
                    saveCleanupState(tracked.getId(), "WAITING", "External artifact absence will be reconfirmed before expiration cleanup");
                    return;
                }
                saveCleanupState(tracked.getId(), "WAITING", "External artifacts have expired; local artifacts and job records await cleanup");
                try {
                    // Persist expiration intent only after checking dependencies. Do not turn it into an operator deletion.
                    validateDeletion(tracked, manifest);
                    DeleteProgress progress = new DeleteProgress();
                    progress.policyExpiration = true;
                    saveDeleteProgress(tracked, progress);
                    if (deleteLocked(tracked.getId(), cleanup, inventory)) {
                        removeDeletedBackupLocked(tracked.getId());
                    }
                } catch (RuntimeException e) {
                    saveCleanupState(tracked.getId(), "WAITING", e.getMessage());
                    logger.debug("Expired volume backup [{}] is retained for cleanup: {}", tracked.getUuid(), e.getMessage());
                }
            } else {
                saveCleanupState(tracked.getId(), "RETAINED", "Remaining external artifacts follow their provider retention policy; explicit deletion is available");
            }
        }
    }

    private void markCatalogFailure(BackupVO tracked, java.util.Set<String> missing) {
        String reason = "Required backup artifacts are absent from the external catalog: " + String.join(", ", missing);
        if (!reason.equals(tracked.getDetail(ThirdPartyBackupManifest.CATALOG_FAILURE_KEY))
                || !Backup.Status.Failed.equals(tracked.getStatus())) {
            backupDetailsDao.addDetail(tracked.getId(), ThirdPartyBackupManifest.CATALOG_FAILURE_KEY, reason, false);
            tracked.setStatus(Backup.Status.Failed);
            // Update status without replacing details concurrently saved by restore tracking.
            BackupVO update = backupDao.createForUpdate(tracked.getId());
            update.setStatus(Backup.Status.Failed);
            backupDao.update(tracked.getId(), update);
            logger.warn("Volume backup [{}] cannot be restored: {}", tracked.getUuid(), reason);
        }
    }

    @Override
    public void removeDeletedBackup(Backup backup) {
        try (Lease lease = acquireLifecycle(backup.getVmId())) { removeDeletedBackupLocked(backup.getId()); }
    }

    private void removeDeletedBackupLocked(long id) {
        com.cloud.utils.db.Transaction.execute((com.cloud.utils.db.TransactionCallback<Boolean>) status -> {
            BackupVO tracked = backupDao.lockRow(id, true);
            if (tracked == null || tracked.getRemoved() != null) { return true; }
            backupDao.loadDetails(tracked);
            DeleteProgress progress = new Gson().fromJson(tracked.getDetail(ThirdPartyBackupManifest.DELETE_PROGRESS_KEY), DeleteProgress.class);
            if (progress == null || !progress.complete) { throw new CloudRuntimeException("Logical backup cleanup has not completed"); }
            if (!backupDao.remove(id)) { throw new CloudRuntimeException("Unable to remove cleaned up logical backup"); }
            if (!Boolean.parseBoolean(tracked.getDetail(AblestackBackupFrameworkUtils.RESOURCE_COUNT_PENDING_DETAIL))) {
                resourceLimitService.decrementResourceCount(tracked.getAccountId(), com.cloud.configuration.Resource.ResourceType.backup);
                resourceLimitService.decrementResourceCount(tracked.getAccountId(), com.cloud.configuration.Resource.ResourceType.backup_storage,
                        tracked.getSize() == null ? 0L : tracked.getSize());
            }
            backupDetailsDao.removeDetails(id);
            return true;
        });
    }

    @Override
    public Host selectBackupHost(com.cloud.vm.VirtualMachine vm, String provider, java.util.List<Long> poolIds, long requiredBytes) {
        return selectWorkerHost(vm, provider, poolIds, null, requiredBytes, "backup");
    }

    @Override
    public Host selectRestoreHost(com.cloud.vm.VirtualMachine vm, String provider, java.util.List<Long> poolIds,
            String hostIdentifier, long requiredBytes) {
        return selectWorkerHost(vm, provider, poolIds, hostIdentifier, requiredBytes, "restore");
    }

    private Host selectWorkerHost(com.cloud.vm.VirtualMachine vm, String provider, java.util.List<Long> poolIds,
            String hostIdentifier, long requiredBytes, String operation) {
        stagingService.requireEnabled();
        java.util.List<Long> connected = poolHostDao.findHostsConnectedToPools(poolIds.stream().distinct()
                .collect(java.util.stream.Collectors.toList()));
        java.util.List<com.cloud.host.HostVO> candidates = new java.util.ArrayList<>();
        if (StringUtils.isNotBlank(hostIdentifier)) {
            com.cloud.host.HostVO host = hostDao.findByName(hostIdentifier);
            if (host == null) { host = hostDao.findByIp(hostIdentifier); }
            if (host != null) { candidates.add(host); }
        } else if (com.cloud.vm.VirtualMachine.State.Running.equals(vm.getState())) {
            com.cloud.host.HostVO host = vm.getHostId() == null ? null : hostDao.findById(vm.getHostId());
            if (host != null) { candidates.add(host); }
        } else {
            connected.forEach(id -> {
                com.cloud.host.HostVO host = hostDao.findById(id);
                if (host != null) { candidates.add(host); }
            });
            Long preferred = vm.getHostId() != null ? vm.getHostId() : vm.getLastHostId();
            java.util.List<ThirdPartyBackupAdmission.Entry> jobs = admissionEntries();
            candidates.sort(java.util.Comparator.comparingLong((com.cloud.host.HostVO host) -> jobs.stream()
                    .filter(entry -> entry.hostId == host.getId() && (entry.occupiesSlot() || "WAITING".equals(entry.state))).count())
                    .thenComparingInt(host -> java.util.Objects.equals(preferred, host.getId()) ? 0 : 1).thenComparingLong(Host::getId));
        }
        long required = Math.addExact(requiredBytes, stagingService.getCapacityBufferBytes(requiredBytes));
        com.cloud.host.HostVO waitingHost = null;
        for (com.cloud.host.HostVO host : candidates) {
            if (host.getDataCenterId() != vm.getDataCenterId() || !connected.contains(host.getId())
                    || poolIds.stream().anyMatch(poolId -> poolHostDao.findByPoolHost(poolId, host.getId()) == null)
                    || !com.cloud.host.Status.Up.equals(host.getStatus())
                    || host.getRemoved() != null || host.getResourceState() != com.cloud.resource.ResourceState.Enabled
                    || !com.cloud.hypervisor.Hypervisor.HypervisorType.KVM.equals(host.getHypervisorType())
                    || (com.cloud.vm.VirtualMachine.State.Running.equals(vm.getState())
                            && !java.util.Objects.equals(vm.getHostId(), host.getId()))) { continue; }
            try {
                if (stagingService.getAvailableBytes(host, java.nio.file.Path.of(stagingService.getStageRootPath(provider))
                        .resolve(operation).toString()) >= required) { return host; }
                if (waitingHost == null) { waitingHost = host; }
            } catch (RuntimeException e) {
                logger.debug("{} staging is unavailable on Host [{}]: {}", operation, host.getId(), e.getMessage());
            }
        }
        if (waitingHost != null) { return waitingHost; }
        throw new CloudRuntimeException("No available KVM Worker Host can access every " + operation + " datastore and the configured staging storage");
    }

    @Override
    public boolean start() {
        executor = Executors.newScheduledThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "thirdparty-volume-transfer");
            thread.setDaemon(true);
            return thread;
        });
        restoreExecutor = Executors.newScheduledThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "thirdparty-volume-restore");
            thread.setDaemon(true);
            return thread;
        });
        cleanupExecutor = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "thirdparty-source-cleanup");
            thread.setDaemon(true);
            return thread;
        });
        cleanupExecutor.scheduleWithFixedDelay(() -> {
            try {
                for (BackupDetailVO detail : backupDetailsDao.findDetails(ThirdPartyBackupManifest.SOURCE_CLEANUP_STATE_KEY, "WAITING", false)) {
                    trackSourceCleanup(detail.getResourceId());
                }
            } catch (RuntimeException e) { logger.warn("Source cleanup recovery is temporarily unavailable", e); }
        }, 1, 30, TimeUnit.SECONDS);
        return true;
    }

    @Override
    public boolean stop() {
        if (executor != null) { executor.shutdownNow(); }
        if (restoreExecutor != null) { restoreExecutor.shutdownNow(); }
        if (cleanupExecutor != null) { cleanupExecutor.shutdownNow(); }
        pending.clear();
        restores.clear();
        sourceCleanups.clear();
        return true;
    }

    @Override
    public void track(Backup backup, Host host, Transfer transfer) {
        if (backup == null || host == null || executor == null) { return; }
        requireBackupWorker(backup, host);
        registerAdmission(backup.getId(), host, backup.getUuid(), "BACKUP");
        pending.computeIfAbsent(backup.getId(), id -> executor.scheduleWithFixedDelay(() -> {
            try {
                BackupVO current = backupDao.findById(backup.getId());
                if (current == null) {
                    finishBackupTracking(backup.getId());
                } else {
                    backupDao.loadDetails(current);
                    boolean cleanupReleased = !ThirdPartyBackupVolumeService.needsBackupCleanup(current);
                    if (current.getStatus() != Backup.Status.BackingUp && (!isFailedPipeline(current) || cleanupReleased)) {
                        finishBackupTracking(backup.getId());
                        return;
                    }
                    reconcile(current, host, transfer);
                }
            } catch (RuntimeException e) { logger.warn("Volume job reconciliation failed for backup [{}]", backup.getId(), e); }
        }, 1, 5, TimeUnit.SECONDS));
    }

    private void finishBackupTracking(long backupId) {
        java.util.concurrent.ScheduledFuture<?> task = pending.remove(backupId);
        if (task != null) { task.cancel(false); }
    }

    private void trackSourceCleanup(long backupId) {
        if (cleanupExecutor == null) { return; }
        sourceCleanups.computeIfAbsent(backupId, id -> cleanupExecutor.scheduleWithFixedDelay(() -> {
            GlobalLock lock = null;
            boolean acquired = false;
            try {
                BackupVO backup = backupDao.findById(id);
                if (backup == null) { finishSourceCleanup(id); return; }
                lock = GlobalLock.getInternLock("backup.volume." + backup.getUuid());
                acquired = lock.lock(1);
                if (!acquired) { return; }
                backupDao.loadDetails(backup);
                if (!"WAITING".equals(backup.getDetail(ThirdPartyBackupManifest.SOURCE_CLEANUP_STATE_KEY))) {
                    finishSourceCleanup(id); return;
                }
                if (backup.getStatus() == Backup.Status.BackingUp) { return; }
                ThirdPartyBackupManifest manifest = ThirdPartyBackupManifest.fromJson(backup.getDetail(ThirdPartyBackupManifest.DETAIL_KEY));
                manifest.validate(true);
                Host host = getWorkerHost(backup, "BACKUP");
                if (host == null || !com.cloud.host.Status.Up.equals(host.getStatus())) {
                    throw new CloudRuntimeException("Source Host is unavailable; snapshot cleanup remains pending");
                }
                Answer answer = agentManager.send(host.getId(), new AblestackVolumeStagingCommand(backup.getUuid(), "CLEANUP_COMPLETED", -1, manifest.toJson()));
                if (answer == null || !answer.getResult()) {
                    throw new CloudRuntimeException(answer == null ? "No source cleanup response" : answer.getDetails());
                }
                backupDetailsDao.addDetail(id, ThirdPartyBackupManifest.SOURCE_CLEANUP_STATE_KEY, "COMPLETED", false);
                backupDetailsDao.removeDetail(id, ThirdPartyBackupManifest.SOURCE_CLEANUP_DETAILS_KEY);
                finishSourceCleanup(id);
            } catch (Exception e) {
                try { backupDetailsDao.addDetail(id, ThirdPartyBackupManifest.SOURCE_CLEANUP_DETAILS_KEY, StringUtils.defaultString(e.getMessage()), false); }
                catch (RuntimeException failure) { logger.warn("Unable to persist snapshot cleanup failure for backup [{}]", id, failure); }
                logger.debug("Source snapshot cleanup remains pending for backup [{}]: {}", id, e.getMessage());
            } finally {
                if (acquired) { lock.unlock(); }
                if (lock != null) { lock.releaseRef(); }
            }
        }, 1, 30, TimeUnit.SECONDS));
    }

    private void finishSourceCleanup(long id) {
        java.util.concurrent.ScheduledFuture<?> task = sourceCleanups.remove(id);
        if (task != null) { task.cancel(false); }
    }

    @Override
    public ThirdPartyBackupRestore.Plan prepareRestore(Backup backup, Host host, java.util.List<String> volumeUuids,
            java.util.List<Long> targetVolumeBytes, String targetVmName, int timeout) {
        try (Lease lease = acquireLifecycle(backup.getVmId())) {
            return prepareRestoreWithLock(backup, host, volumeUuids, targetVolumeBytes, targetVmName, timeout);
        }
    }

    private ThirdPartyBackupRestore.Plan prepareRestoreWithLock(Backup backup, Host host, java.util.List<String> volumeUuids,
            java.util.List<Long> targetVolumeBytes, String targetVmName, int timeout) {
        GlobalLock lock = GlobalLock.getInternLock("backup.volume.restore.prepare." + backup.getUuid());
        boolean acquired = false;
        try {
            acquired = lock.lock(5);
            if (!acquired) { throw new CloudRuntimeException("Another restore is being prepared for this backup"); }
            return prepareRestoreLocked(backup, host, volumeUuids, targetVolumeBytes, targetVmName, timeout);
        } finally {
            if (acquired) { lock.unlock(); }
            lock.releaseRef();
        }
    }

    private ThirdPartyBackupRestore.Plan prepareRestoreLocked(Backup backup, Host host, java.util.List<String> volumeUuids,
            java.util.List<Long> targetVolumeBytes, String targetVmName, int timeout) {
        stagingService.requireEnabled();
        BackupVO tracked = backupDao.findById(backup.getId());
        if (tracked == null) { throw new CloudRuntimeException("Selected backup no longer exists"); }
        backupDao.loadDetails(tracked);
        if (StringUtils.isNotBlank(tracked.getDetail(ThirdPartyBackupManifest.DELETE_PROGRESS_KEY))
                || StringUtils.isNotBlank(tracked.getDetail(ThirdPartyBackupManifest.CATALOG_FAILURE_KEY))) {
            throw new CloudRuntimeException("This logical backup is being deleted or has missing external artifacts");
        }
        ThirdPartyBackupManifest manifest = ThirdPartyBackupManifest.fromJson(tracked.getDetail(ThirdPartyBackupManifest.DETAIL_KEY));
        manifest.validate(true);
        String previousJson = tracked.getDetail(ThirdPartyBackupRestore.PLAN_KEY);
        if (StringUtils.isNotBlank(previousJson)) {
            ThirdPartyBackupRestore.Plan previous = new Gson().fromJson(previousJson, ThirdPartyBackupRestore.Plan.class);
            if (!"COMPLETED".equals(tracked.getDetail(ThirdPartyBackupRestore.CLEANUP_STATE_KEY))) {
                throw new CloudRuntimeException("The previous restore must finish external reconciliation and staging cleanup before another attempt");
            }
            ensureRestoreOperation(tracked, previous);
            requireRestoreWritersTerminated(tracked, previous);
            ThirdPartyBackupAdmission.Entry previousAdmission = readAdmission(tracked.getId(), "RESTORE");
            if (previousAdmission != null && !"RELEASED".equals(previousAdmission.state)) {
                throw new CloudRuntimeException("The previous restore capacity reservation has not been released");
            }
            if (previous.jobId.equals(tracked.getDetail(AblestackBackupFrameworkUtils.RESTORE_JOB_ID_DETAIL))) {
                try {
                    Answer answer = agentManager.send(previous.hostId, new AblestackRestoreJobStatusCommand(previous.jobId, null, 0));
                    boolean storedTerminal = java.util.Set.of("COMPLETED", "FAILED", "INTERRUPTED", "CANCELED")
                            .contains(StringUtils.defaultString(tracked.getDetail(AblestackBackupFrameworkUtils.RESTORE_JOB_STATE_DETAIL)));
                    boolean missing = answer instanceof BackupAnswer && (StringUtils.isBlank(((BackupAnswer) answer).getState())
                            || "UNKNOWN".equals(((BackupAnswer) answer).getState()));
                    if (!(answer instanceof BackupAnswer) || !answer.getResult()
                            || (!(missing && storedTerminal) && !java.util.Set.of("COMPLETED", "FAILED", "INTERRUPTED", "CANCELED")
                                    .contains(StringUtils.defaultString(((BackupAnswer) answer).getState())))) {
                        throw new CloudRuntimeException("The previous restore is active or its outcome cannot be confirmed");
                    }
                } catch (com.cloud.exception.AgentUnavailableException | com.cloud.exception.OperationTimedoutException e) {
                    throw new CloudRuntimeException("Cannot confirm the previous restore before starting another", e);
                }
            }
        }
        if (!Backup.Status.BackedUp.equals(tracked.getStatus()) || host == null || volumeUuids == null || volumeUuids.isEmpty()
                || new java.util.HashSet<>(volumeUuids).size() != volumeUuids.size()
                || targetVolumeBytes == null || targetVolumeBytes.size() != volumeUuids.size()
                || targetVmName == null || !targetVmName.matches("[A-Za-z0-9_.-]+")) {
            throw new CloudRuntimeException("Invalid volume restore selection");
        }
        for (int index = 0; index < volumeUuids.size(); index++) {
            String uuid = volumeUuids.get(index);
            ThirdPartyBackupManifest.Volume volume = manifest.getVolumes().stream().filter(v -> v.uuid.equals(uuid))
                    .findFirst().orElseThrow(() -> new CloudRuntimeException("Selected volume is absent from the backup manifest"));
            if (targetVolumeBytes.get(index) == null || targetVolumeBytes.get(index) < volume.provisionedBytes) {
                throw new CloudRuntimeException("Restore destination is smaller than the backed up volume");
            }
        }
        ThirdPartyBackupRestore.Plan plan = new ThirdPartyBackupRestore.Plan();
        // A new attempt must never consume acknowledgement files left by an earlier restore.
        plan.jobId = AblestackBackupFrameworkUtils.createRestoreJobId(manifest.getProvider(), backup.getUuid(), manifest.getVmName(),
                volumeUuids.size() == 1 ? volumeUuids.get(0) : null) + "-" + java.util.UUID.randomUUID();
        plan.hostId = host.getId();
        plan.hostName = host.getName();
        java.nio.file.Path providerRoot = java.nio.file.Path.of(stagingService.getStageRootPath(manifest.getProvider()));
        plan.stageRoot = providerRoot.getParent().toString();
        plan.destination = providerRoot.resolve("restore").resolve(plan.jobId).toString();
        plan.bufferPercent = stagingService.getCapacityBufferPercent();
        plan.timeout = timeout;
        plan.queueTimeout = BackupManager.ThirdPartyStagingQueueTimeout.value();
        plan.startProtocolVersion = ThirdPartyBackupRestore.START_PROTOCOL_VERSION;
        plan.primaryCapacityVersion = 1;
        plan.vmResultVersion = 1;
        plan.manifest = manifest;
        plan.volumeUuids = new java.util.ArrayList<>(volumeUuids);
        plan.targetVolumeBytes = new java.util.ArrayList<>(targetVolumeBytes);
        plan.targetVmName = targetVmName;
        // This also mounts/validates the configured staging filesystem on the chosen Worker Host.
        stagingService.getAvailableBytes(host, providerRoot.resolve("restore").toString());
        com.cloud.utils.db.Transaction.execute((com.cloud.utils.db.TransactionCallback<Boolean>) status -> {
            backupDetailsDao.addDetail(tracked.getId(), ThirdPartyBackupRestore.PLAN_KEY, new Gson().toJson(plan), false);
            ThirdPartyBackupRestore.Operation operation = new ThirdPartyBackupRestore.Operation();
            operation.plan = new Gson().fromJson(new Gson().toJson(plan), ThirdPartyBackupRestore.Plan.class);
            operation.createdAt = System.currentTimeMillis();
            operation.hostState = "STARTING";
            operation.startState = "PREPARING";
            operation.cleanupState = "WAITING";
            operation.cleanupReason = "Restore job has not finished";
            writeRestoreOperation(tracked.getId(), operation);
            backupDetailsDao.removeDetail(tracked.getId(), ThirdPartyBackupRestore.TRANSFER_KEY);
            backupDetailsDao.addDetail(tracked.getId(), ThirdPartyBackupRestore.CLEANUP_STATE_KEY, "WAITING", false);
            backupDetailsDao.addDetail(tracked.getId(), ThirdPartyBackupRestore.CLEANUP_DETAILS_KEY, "Restore job has not finished", false);
            backupDetailsDao.addDetail(tracked.getId(), AblestackBackupFrameworkUtils.RESTORE_JOB_ID_DETAIL, plan.jobId, false);
            backupDetailsDao.addDetail(tracked.getId(), AblestackBackupFrameworkUtils.RESTORE_JOB_STATE_DETAIL, "STARTING", false);
            backupDetailsDao.addDetail(tracked.getId(), AblestackBackupFrameworkUtils.RESTORE_HOST_ID_DETAIL, String.valueOf(plan.hostId), false);
            backupDetailsDao.addDetail(tracked.getId(), AblestackBackupFrameworkUtils.RESTORE_HOST_NAME_DETAIL, plan.hostName, false);
            return true;
        });
        prepareRestoreStart(tracked, host, plan);
        return plan;
    }

    private void prepareRestoreStart(BackupVO backup, Host host, ThirdPartyBackupRestore.Plan plan) {
        GlobalLock lock = GlobalLock.getInternLock("backup.volume.restore." + plan.jobId);
        boolean acquired = false;
        try {
            acquired = lock.lock(5);
            if (!acquired) { throw new CloudRuntimeException("Restore start preparation is being reconciled"); }
            ThirdPartyBackupRestore.Operation operation = readRestoreOperation(backup, plan);
            if (operation == null || !"PREPARING".equals(operation.startState)) {
                throw new CloudRuntimeException("Restore preparation has already been completed or blocked");
            }
            if (operation.cancelRequestedAt > 0) { throw new CloudRuntimeException("Restore cancellation is pending; no preparation command was sent"); }
            requireRestoreWorker(backup, host, plan);
            try {
                registerAdmission(backup.getId(), host, plan.jobId, "RESTORE");
                ThirdPartyBackupRestore.StartReceipt receipt = restoreStartControl(host, plan, "RESTORE_START_PREPARE");
                if (!"PREPARED".equals(receipt.state)) { throw new CloudRuntimeException("Restore start preparation was not accepted"); }
                operation.startState = "PREPARED";
                operation.startCheckedAt = receipt.checkedAt;
                writeRestoreOperation(backup.getId(), operation);
            } catch (Exception e) {
                // No engine dispatch follows a failed preparation. Background reconciliation closes it with a Host fence.
                operation.startFailureReason = "Restore start preparation is unconfirmed: " + e.getMessage();
                writeRestoreOperation(backup.getId(), operation);
                throw new CloudRuntimeException(operation.startFailureReason, e);
            }
        } finally {
            if (acquired) { lock.unlock(); }
            lock.releaseRef();
        }
    }

    @Override
    public void markRestoreDispatch(Backup backup, ThirdPartyBackupRestore.Plan plan) {
        GlobalLock lock = GlobalLock.getInternLock("backup.volume.restore." + plan.jobId);
        boolean acquired = false;
        try {
            acquired = lock.lock(5);
            if (!acquired) { throw new CloudRuntimeException("Restore dispatch is being reconciled; dispatch was not sent"); }
            BackupVO current = stagingBackup(backup.getId());
            if (!plan.jobId.equals(restorePlan(current).jobId)) { throw new CloudRuntimeException("Restore dispatch attempt changed"); }
            ThirdPartyBackupRestore.Operation operation = readRestoreOperation(current, plan);
            if (operation == null || !"PREPARED".equals(operation.startState)) {
                throw new CloudRuntimeException("Restore start is unprepared, already dispatched or permanently blocked");
            }
            if (operation.cancelRequestedAt > 0) { throw new CloudRuntimeException("Restore cancellation is pending; no dispatch is allowed"); }
            operation.startState = "SUBMISSION_PENDING";
            operation.startSubmittedAt = System.currentTimeMillis();
            writeRestoreOperation(current.getId(), operation);
        } finally {
            if (acquired) { lock.unlock(); }
            lock.releaseRef();
        }
    }

    private ThirdPartyBackupRestore.StartReceipt restoreStartControl(Host host, ThirdPartyBackupRestore.Plan plan, String action)
            throws com.cloud.exception.AgentUnavailableException, com.cloud.exception.OperationTimedoutException {
        if (host == null || host.getId() != plan.hostId) { throw new CloudRuntimeException("Restore start Worker Host differs from the recorded attempt"); }
        Answer answer = agentManager.send(host.getId(), new AblestackVolumeStagingCommand(plan.jobId, action, -1, new Gson().toJson(plan)));
        if (answer == null || !answer.getResult() || StringUtils.isBlank(answer.getDetails())) {
            throw new CloudRuntimeException(answer == null ? "Host did not confirm restore start control" : answer.getDetails());
        }
        ThirdPartyBackupRestore.StartReceipt receipt = new Gson().fromJson(answer.getDetails(), ThirdPartyBackupRestore.StartReceipt.class);
        if (receipt == null || receipt.version != ThirdPartyBackupRestore.START_PROTOCOL_VERSION || receipt.plan == null
                || !new Gson().toJson(plan).equals(new Gson().toJson(receipt.plan)) || receipt.checkedAt <= 0
                || !java.util.Set.of("PREPARED", "INITIALIZED", "START_FAILED").contains(StringUtils.defaultString(receipt.state))) {
            throw new CloudRuntimeException("Host restore start proof differs from the recorded attempt");
        }
        return receipt;
    }

    /** Absence alone is insufficient: a durable Host fence must also reject every delayed start. */
    private boolean finishUnstartedRestore(BackupVO backup, Host host, ThirdPartyBackupRestore.Plan plan, boolean explicit)
            throws com.cloud.exception.AgentUnavailableException, com.cloud.exception.OperationTimedoutException {
        if (plan.startProtocolVersion != ThirdPartyBackupRestore.START_PROTOCOL_VERSION) { return false; }
        ThirdPartyBackupRestore.Operation operation = ensureRestoreOperation(backup, plan);
        if ("STARTED".equals(operation.startState) || !restoreRecords(backup, plan).isEmpty()) { return false; }
        long submittedAt = operation.startSubmittedAt > 0 ? operation.startSubmittedAt : operation.createdAt;
        if (!explicit && !"START_FAILED".equals(operation.startState) && StringUtils.isBlank(operation.startFailureReason)
                && System.currentTimeMillis() < submittedAt + TimeUnit.SECONDS.toMillis(60)) { return false; }
        if ("PREPARING".equals(operation.startState)) {
            // Preparation alone cannot create an engine or reserve data capacity; repeat only this control handshake.
            ThirdPartyBackupRestore.StartReceipt prepared = restoreStartControl(host, plan, "RESTORE_START_PREPARE");
            if ("INITIALIZED".equals(prepared.state)) {
                operation.startState = "STARTED";
                operation.startCheckedAt = prepared.checkedAt;
                writeRestoreOperation(backup.getId(), operation);
                return false;
            }
        }
        ThirdPartyBackupRestore.StartReceipt receipt = restoreStartControl(host, plan, "RESTORE_START_ABORT");
        operation.startCheckedAt = receipt.checkedAt;
        if (!"START_FAILED".equals(receipt.state)) {
            if ("INITIALIZED".equals(receipt.state)) { operation.startState = "STARTED"; }
            writeRestoreOperation(backup.getId(), operation);
            return false;
        }
        operation.startState = "START_FAILED";
        operation.startFailureReason = receipt.reason;
        operation.hostState = operation.cancelRequestedAt > 0 ? "CANCELED" : "FAILED";
        if (operation.cancelRequestedAt > 0) {
            operation.cancelConfirmedAt = receipt.checkedAt;
            operation.cancelReason = "Restore canceled before Host engine initialization; no VM volumes were changed";
        }
        operation.hostCheckedAt = receipt.checkedAt;
        operation.hostError = null;
        if (plan.vmResultVersion == 1) {
            if (operation.vmResult != null && (operation.vmResult.transactionId != null || !"START_FAILED".equals(operation.vmResult.phase))) {
                throw new CloudRuntimeException("VM transaction receipt conflicts with the restore start failure proof");
            }
            ThirdPartyBackupRestore.VmResult result = new ThirdPartyBackupRestore.VmResult();
            result.jobId = plan.jobId;
            result.sourceBackupUuid = plan.manifest.getBackupUuid();
            result.provider = plan.manifest.getProvider();
            result.targetVmName = plan.targetVmName;
            result.phase = "START_FAILED";
            result.outcome = "FAILED";
            result.primaryCleanupState = "NOT_REQUIRED";
            result.failure = receipt.reason;
            result.revision = 1;
            result.updatedAt = receipt.checkedAt;
            for (int i = 0; i < plan.volumeUuids.size(); i++) {
                ThirdPartyBackupRestore.VmVolumeResult volume = new ThirdPartyBackupRestore.VmVolumeResult();
                volume.index = i;
                volume.volumeUuid = plan.volumeUuids.get(i);
                volume.prepareState = volume.switchState = "NOT_STARTED";
                volume.rollbackState = volume.cleanupState = "NOT_REQUIRED";
                result.volumes.add(volume);
            }
            mergeVmRestoreResult(operation, result);
            operation.vmResultCheckedAt = receipt.checkedAt;
            operation.vmResultError = null;
        }
        writeRestoreOperation(backup.getId(), operation);
        releaseAdmission(backup.getId(), "RESTORE", plan.jobId);
        com.cloud.utils.db.Transaction.execute((com.cloud.utils.db.TransactionCallback<Boolean>) status -> {
            backupDetailsDao.addDetail(backup.getId(), AblestackBackupFrameworkUtils.RESTORE_JOB_STATE_DETAIL, operation.hostState, false);
            backupDetailsDao.addDetail(backup.getId(), AblestackBackupFrameworkUtils.RESTORE_JOB_STEP_DETAIL,
                    operation.cancelRequestedAt > 0 ? "CANCELED" : "START_FAILED", false);
            backupDetailsDao.addDetail(backup.getId(), AblestackBackupFrameworkUtils.RESTORE_JOB_PROGRESS_DETAIL, "0", false);
            backupDetailsDao.addDetail(backup.getId(), AblestackBackupFrameworkUtils.RESTORE_JOB_FAILURE_REASON_DETAIL,
                    operation.cancelRequestedAt > 0 ? operation.cancelReason : StringUtils.defaultIfBlank(receipt.reason, "Restore engine did not start"), false);
            saveRestoreCleanup(backup.getId(), plan.jobId, "COMPLETED", null);
            return true;
        });
        finishRestoreTracking(plan.jobId);
        return true;
    }

    private void confirmRestoreStarted(BackupVO backup, ThirdPartyBackupRestore.Plan plan) {
        if (plan.startProtocolVersion != ThirdPartyBackupRestore.START_PROTOCOL_VERSION) { return; }
        ThirdPartyBackupRestore.Operation operation = ensureRestoreOperation(backup, plan);
        if ("START_FAILED".equals(operation.startState)) { throw new CloudRuntimeException("Host request conflicts with a confirmed restore start fence"); }
        if (!"STARTED".equals(operation.startState)) {
            operation.startState = "STARTED";
            operation.startCheckedAt = System.currentTimeMillis();
            writeRestoreOperation(backup.getId(), operation);
        }
    }

    @Override
    public void trackRestore(Backup backup, Host host, RestoreTransfer transfer) {
        BackupVO tracked = backupDao.findById(backup.getId());
        if (tracked == null || host == null || restoreExecutor == null) { return; }
        backupDao.loadDetails(tracked);
        String json = tracked.getDetail(ThirdPartyBackupRestore.PLAN_KEY);
        if (StringUtils.isBlank(json)) { return; }
        ThirdPartyBackupRestore.Plan plan = new Gson().fromJson(json, ThirdPartyBackupRestore.Plan.class);
        if (!matchesRestoreTracking(tracked, plan.jobId)) { return; }
        if ("COMPLETED".equals(tracked.getDetail(ThirdPartyBackupRestore.CLEANUP_STATE_KEY))) { return; }
        requireRestoreWorker(tracked, host, plan);
        restores.computeIfAbsent(plan.jobId, id -> restoreExecutor.scheduleWithFixedDelay(
                () -> reconcileRestore(backup.getId(), host, plan, transfer), 1, 5, TimeUnit.SECONDS));
    }

    private boolean matchesRestoreTracking(Backup backup, String jobId) {
        String active = backup.getDetail(AblestackBackupFrameworkUtils.RESTORE_JOB_ID_DETAIL);
        return jobId.equals(active) || (StringUtils.isBlank(active)
                && jobId.equals(backup.getDetail(AblestackBackupFrameworkUtils.RESTORE_JOB_CLEANUP_ID_DETAIL)));
    }

    private java.util.List<ThirdPartyBackupRestore.Request> restoreRequests(ThirdPartyBackupRestore.Plan plan) {
        java.util.List<ThirdPartyBackupRestore.Request> requests = new java.util.ArrayList<>();
        if (plan == null || plan.manifest == null || plan.manifest.getMetadata() == null || plan.volumeUuids == null) {
            throw new CloudRuntimeException("Restore plan has no exact catalog selections");
        }
        requests.add(restoreRequest(plan, 0, -1, -1, true, plan.manifest.getMetadata()));
        for (int index = 0; index < plan.volumeUuids.size(); index++) {
            String uuid = plan.volumeUuids.get(index);
            ThirdPartyBackupManifest.Volume volume = plan.manifest.getVolumes().stream()
                    .filter(value -> uuid.equals(value.uuid)).findFirst().orElseThrow();
            for (int chain = 0; chain < volume.chain.size(); chain++) {
                requests.add(restoreRequest(plan, requests.size(), index, chain, false, volume.chain.get(chain)));
            }
        }
        return requests;
    }

    private ThirdPartyBackupRestore.Request restoreRequest(ThirdPartyBackupRestore.Plan plan, int sequence, int volume,
            int chain, boolean metadata, ThirdPartyBackupManifest.Artifact artifact) {
        ThirdPartyBackupRestore.Request request = new ThirdPartyBackupRestore.Request();
        request.jobId = plan.jobId;
        request.sequence = sequence;
        request.volumeIndex = volume;
        request.chainIndex = chain;
        request.metadata = metadata;
        request.artifact = copyArtifact(artifact);
        request.destination = java.nio.file.Path.of(plan.destination).resolve(metadata ? "metadata" : "payload")
                .resolve(java.nio.file.Path.of(artifact.path).getFileName()).toString();
        return request;
    }

    private ThirdPartyBackupRestore.Operation readRestoreOperation(Backup backup, ThirdPartyBackupRestore.Plan plan) {
        BackupDetailVO detail = backupDetailsDao.findDetail(backup.getId(), ThirdPartyBackupRestore.operationKey(plan.jobId));
        if (detail == null) { return null; }
        ThirdPartyBackupRestore.Operation operation = new Gson().fromJson(detail.getValue(), ThirdPartyBackupRestore.Operation.class);
        if (operation == null || operation.version != ThirdPartyBackupRestore.HISTORY_VERSION || operation.plan == null || operation.plan.manifest == null
                || !new Gson().toJson(plan).equals(new Gson().toJson(operation.plan))
                || !backup.getUuid().equals(operation.plan.manifest.getBackupUuid())
                || operation.cancelRequestedAt < 0 || operation.cancelConfirmedAt < 0
                || (operation.cancelConfirmedAt > 0 && operation.cancelRequestedAt == 0)) {
            throw new CloudRuntimeException("Persisted restore history belongs to a different plan");
        }
        return operation;
    }

    private void writeRestoreOperation(long backupId, ThirdPartyBackupRestore.Operation operation) {
        operation.updatedAt = System.currentTimeMillis();
        backupDetailsDao.addDetail(backupId, ThirdPartyBackupRestore.operationKey(operation.plan.jobId), new Gson().toJson(operation), false);
    }

    /** Existing operations retain only their known last transfer; earlier outcomes are never invented. */
    private ThirdPartyBackupRestore.Operation ensureRestoreOperation(BackupVO backup, ThirdPartyBackupRestore.Plan plan) {
        ThirdPartyBackupRestore.Operation existing = readRestoreOperation(backup, plan);
        if (existing != null) { return existing; }
        ThirdPartyBackupRestore.Operation operation = new ThirdPartyBackupRestore.Operation();
        operation.plan = new Gson().fromJson(new Gson().toJson(plan), ThirdPartyBackupRestore.Plan.class);
        operation.legacy = true;
        operation.hostState = backup.getDetail(AblestackBackupFrameworkUtils.RESTORE_JOB_STATE_DETAIL);
        operation.cleanupState = backup.getDetail(ThirdPartyBackupRestore.CLEANUP_STATE_KEY);
        operation.cleanupReason = backup.getDetail(ThirdPartyBackupRestore.CLEANUP_DETAILS_KEY);
        ThirdPartyBackupRestore.Result previous = new Gson().fromJson(backup.getDetail(ThirdPartyBackupRestore.TRANSFER_KEY), ThirdPartyBackupRestore.Result.class);
        ThirdPartyBackupRestore.Record record = null;
        if (previous != null) {
            java.util.List<ThirdPartyBackupRestore.Request> requests = restoreRequests(plan);
            if (previous.sequence < 0 || previous.sequence >= requests.size()) {
                throw new CloudRuntimeException("Legacy restore result is outside its persisted plan");
            }
            record = new ThirdPartyBackupRestore.Record();
            record.request = requests.get(previous.sequence);
            record.result = previous;
            record.legacy = true;
            record.updatedAt = System.currentTimeMillis();
            operation.latestSequence = previous.sequence;
            operation.legacyThroughSequence = previous.sequence;
        }
        final ThirdPartyBackupRestore.Record legacyRecord = record;
        com.cloud.utils.db.Transaction.execute((com.cloud.utils.db.TransactionCallback<Boolean>) status -> {
            writeRestoreOperation(backup.getId(), operation);
            if (legacyRecord != null) {
                backupDetailsDao.addDetail(backup.getId(), ThirdPartyBackupRestore.recordKey(plan.jobId, legacyRecord.request.sequence),
                        new Gson().toJson(legacyRecord), false);
            }
            return true;
        });
        return operation;
    }

    private ThirdPartyBackupRestore.Record readRestoreRecord(Backup backup, ThirdPartyBackupRestore.Plan plan, int sequence) {
        BackupDetailVO detail = backupDetailsDao.findDetail(backup.getId(), ThirdPartyBackupRestore.recordKey(plan.jobId, sequence));
        if (detail == null) { return null; }
        return parseRestoreRecord(detail, plan, sequence);
    }

    private ThirdPartyBackupRestore.Record parseRestoreRecord(BackupDetailVO detail, ThirdPartyBackupRestore.Plan plan, int sequence) {
        ThirdPartyBackupRestore.Record record = new Gson().fromJson(detail.getValue(), ThirdPartyBackupRestore.Record.class);
        if (record == null || record.version != ThirdPartyBackupRestore.HISTORY_VERSION || record.request == null || record.result == null
                || record.request.sequence != sequence || record.result.sequence != sequence) {
            throw new CloudRuntimeException("Invalid persisted restore request/result history");
        }
        validateRestoreRequest(plan, record.request);
        return record;
    }

    private java.util.List<ThirdPartyBackupRestore.Record> restoreRecords(Backup backup, ThirdPartyBackupRestore.Plan plan) {
        java.util.List<ThirdPartyBackupRestore.Record> records = new java.util.ArrayList<>();
        String prefix = ThirdPartyBackupRestore.historyPrefix(plan.jobId);
        for (BackupDetailVO detail : backupDetailsDao.listDetails(backup.getId())) {
            if (!detail.getName().startsWith(prefix)) { continue; }
            String sequence = detail.getName().substring(prefix.length());
            if ("operation".equals(sequence)) { continue; }
            if (!sequence.matches("[0-9]+")) { throw new CloudRuntimeException("Invalid restore history sequence key"); }
            records.add(parseRestoreRecord(detail, plan, Integer.parseInt(sequence)));
        }
        records.sort(java.util.Comparator.comparingInt(record -> record.request.sequence));
        return records;
    }

    private boolean externalRestoreStarted(ThirdPartyBackupRestore.Result result) {
        return result != null && (result.submissionPending || StringUtils.isNotBlank(result.jobId) || result.submittedAt > 0);
    }

    private boolean unresolvedRestore(ThirdPartyBackupRestore.Record record) {
        return externalRestoreStarted(record.result) && !record.result.completed && !record.result.externalTerminal;
    }

    private void persistRestoreRecord(BackupVO backup, ThirdPartyBackupRestore.Plan plan, ThirdPartyBackupRestore.Record record) {
        validateRestoreRequest(plan, record.request);
        ThirdPartyBackupRestore.Operation operation = readRestoreOperation(backup, plan);
        if (operation == null) { throw new CloudRuntimeException("Restore operation must be recorded before its transfer"); }
        record.updatedAt = System.currentTimeMillis();
        boolean current = record.request.sequence >= operation.latestSequence;
        operation.latestSequence = Math.max(operation.latestSequence, record.request.sequence);
        com.cloud.utils.db.Transaction.execute((com.cloud.utils.db.TransactionCallback<Boolean>) status -> {
            backupDetailsDao.addDetail(backup.getId(), ThirdPartyBackupRestore.recordKey(plan.jobId, record.request.sequence), new Gson().toJson(record), false);
            writeRestoreOperation(backup.getId(), operation);
            // Kept as a compatibility mirror; per-sequence records are authoritative.
            if (current && record.result != null) {
                backupDetailsDao.addDetail(backup.getId(), ThirdPartyBackupRestore.TRANSFER_KEY, new Gson().toJson(record.result), false);
            }
            return true;
        });
    }

    private ThirdPartyBackupRestore.Record observeRestoreRequest(BackupVO backup, ThirdPartyBackupRestore.Plan plan,
            ThirdPartyBackupRestore.Request request) {
        validateRestoreRequest(plan, request);
        confirmRestoreStarted(backup, plan);
        ThirdPartyBackupRestore.Operation operation = ensureRestoreOperation(backup, plan);
        ThirdPartyBackupRestore.Record record = readRestoreRecord(backup, plan, request.sequence);
        if (record != null) {
            if (request.sequence < operation.latestSequence) { throw new CloudRuntimeException("Host restore request regressed to an earlier sequence"); }
            return record;
        }
        if (request.sequence != operation.latestSequence + 1) {
            throw new CloudRuntimeException("Host restore request skipped or repeated an unrecorded sequence");
        }
        for (ThirdPartyBackupRestore.Record previous : restoreRecords(backup, plan)) {
            if (previous.request.sequence < request.sequence && (previous.result == null || !previous.result.completed)) {
                throw new CloudRuntimeException("Host advanced the restore request before the previous artifact completed");
            }
        }
        record = new ThirdPartyBackupRestore.Record();
        record.request = new Gson().fromJson(new Gson().toJson(request), ThirdPartyBackupRestore.Request.class);
        record.result = new ThirdPartyBackupRestore.Result();
        record.result.sequence = request.sequence;
        record.recordedAt = System.currentTimeMillis();
        // This immutable request is committed before a submission intent or an external call.
        persistRestoreRecord(backup, plan, record);
        return record;
    }

    private ThirdPartyBackupRestore.Result copyRestoreResult(ThirdPartyBackupRestore.Result result) {
        return new Gson().fromJson(new Gson().toJson(result), ThirdPartyBackupRestore.Result.class);
    }

    private void saveRestoreResult(BackupVO backup, ThirdPartyBackupRestore.Plan plan, ThirdPartyBackupRestore.Record record,
            ThirdPartyBackupRestore.Result result) {
        if (result == null || (StringUtils.isBlank(result.jobId) && !(result.notSubmitted && result.externalTerminal && !result.completed))) {
            throw new CloudRuntimeException("External restore reference is unconfirmed");
        }
        ThirdPartyBackupRestore.Result saved = record.result;
        if (saved != null && StringUtils.isNotBlank(saved.jobId) && !saved.jobId.equals(result.jobId)) {
            throw new CloudRuntimeException("Recovered external restore Job ID conflicts with its persisted request");
        }
        if (saved != null && ((saved.completed && !result.completed) || (saved.externalTerminal && !result.externalTerminal && !result.completed))) {
            throw new CloudRuntimeException("External restore reconciliation would regress a confirmed terminal result");
        }
        result.sequence = record.request.sequence;
        result.submittedAt = saved == null ? 0 : saved.submittedAt;
        result.submissionPending = false;
        result.recoveryOnly = false;
        result.externalTerminal = result.externalTerminal || result.completed;
        record.result = copyRestoreResult(result);
        record.checkedAt = System.currentTimeMillis();
        record.queryError = null;
        if (result.completed && record.completedAt == 0) { record.completedAt = record.checkedAt; }
        if (result.externalTerminal && record.terminalAt == 0) { record.terminalAt = record.checkedAt; }
        persistRestoreRecord(backup, plan, record);
    }

    /** Recover every issued transfer from its DB request, including when the Host request file is gone. */
    private void recoverRestoreRecords(BackupVO backup, ThirdPartyBackupRestore.Plan plan, RestoreTransfer transfer) {
        for (ThirdPartyBackupRestore.Record record : restoreRecords(backup, plan)) {
            if (!unresolvedRestore(record)) { continue; }
            try {
                ThirdPartyBackupRestore.Result result = transfer.recover(record.request, copyRestoreResult(record.result));
                saveRestoreResult(backup, plan, record, result);
            } catch (RuntimeException e) {
                if (e instanceof ThirdPartyBackupSubmissionException && StringUtils.isBlank(record.result.jobId)) {
                    saveRestoreResult(backup, plan, record, ThirdPartyBackupRestore.Result.notSubmitted(e.getMessage()));
                    continue;
                }
                record.checkedAt = System.currentTimeMillis();
                record.queryError = StringUtils.defaultString(e.getMessage(), "External restore query was not confirmed");
                persistRestoreRecord(backup, plan, record);
            }
        }
    }

    private void requireRestoreWritersTerminated(Backup backup, ThirdPartyBackupRestore.Plan plan) {
        for (ThirdPartyBackupRestore.Record record : restoreRecords(backup, plan)) {
            if (unresolvedRestore(record)) {
                throw new CloudRuntimeException("External restore writer termination is unconfirmed at sequence "
                        + record.request.sequence + ": " + StringUtils.defaultString(record.result.jobId, "UNCONFIRMED"));
            }
        }
    }

    private void recordRestoreHost(BackupVO backup, ThirdPartyBackupRestore.Plan plan, BackupAnswer status, String error) {
        ThirdPartyBackupRestore.Operation operation = ensureRestoreOperation(backup, plan);
        if (status != null) {
            operation.hostState = status.getState();
            operation.hostCheckedAt = System.currentTimeMillis();
        }
        operation.hostError = error;
        if (status != null && StringUtils.isNotBlank(status.getVmRestoreResult())) {
            try {
                mergeVmRestoreResult(operation, new Gson().fromJson(status.getVmRestoreResult(), ThirdPartyBackupRestore.VmResult.class));
                operation.vmResultError = status.getVmRestoreResultError();
            } catch (RuntimeException e) { operation.vmResultError = e.getMessage(); }
            operation.vmResultCheckedAt = System.currentTimeMillis();
        } else if (error != null || (status != null && StringUtils.isNotBlank(status.getVmRestoreResultError()))
                || (plan.vmResultVersion == 1 && "STARTED".equals(operation.startState))) {
            operation.vmResultError = error != null ? error : status == null ? "VM restore result is unconfirmed"
                    : StringUtils.defaultIfBlank(status.getVmRestoreResultError(), "Host did not return a VM volume transaction result");
            operation.vmResultCheckedAt = System.currentTimeMillis();
        }
        writeRestoreOperation(backup.getId(), operation);
    }

    private void mergeVmRestoreResult(ThirdPartyBackupRestore.Operation operation, ThirdPartyBackupRestore.VmResult result) {
        ThirdPartyBackupRestore.Plan plan = operation.plan;
        if (result == null) { throw new CloudRuntimeException("VM volume result is unavailable"); }
        result.validate(plan);
        ThirdPartyBackupRestore.VmResult previous = operation.vmResult;
        if (previous != null) {
            if (previous.transactionId != null && !previous.transactionId.equals(result.transactionId)) {
                throw new CloudRuntimeException("VM volume transaction ID changed within the same restore attempt");
            }
            if (previous.revision > result.revision) { return; }
            if (previous.revision == result.revision && !new Gson().toJson(previous).equals(new Gson().toJson(result))) {
                throw new CloudRuntimeException("VM volume result changed without advancing its receipt revision");
            }
            if (java.util.Set.of("COMMITTED", "ROLLED_BACK").contains(previous.outcome) && !previous.outcome.equals(result.outcome)) {
                throw new CloudRuntimeException("A confirmed VM transaction outcome cannot be replaced by another outcome");
            }
        }
        operation.vmResult = result;
    }

    /** A saved queued cancellation never submits or acknowledges another external restore request. */
    private boolean reconcilePendingRestoreCancellation(BackupVO backup, Host host, ThirdPartyBackupRestore.Plan plan, RestoreTransfer transfer)
            throws com.cloud.exception.AgentUnavailableException, com.cloud.exception.OperationTimedoutException {
        ThirdPartyBackupRestore.Operation operation = ensureRestoreOperation(backup, plan);
        if (operation.cancelRequestedAt <= 0) { return false; }
        requireRestoreWorker(backup, host, plan);
        if ("COMPLETED".equals(operation.cleanupState)) { return true; }
        if (!restoreRecords(backup, plan).isEmpty()) {
            throw new CloudRuntimeException("Queued restore cancellation conflicts with external transfer records; reservations are retained");
        }
        if (finishUnstartedRestore(backup, host, plan, true)) { return true; }
        Answer answer = agentManager.send(host.getId(), new AblestackRestoreJobStatusCommand(plan.jobId, null, 0));
        if (!(answer instanceof BackupAnswer) || !answer.getResult()) {
            recordRestoreHost(backup, plan, null, "Queued restore cancellation is waiting for Host termination confirmation");
            return true;
        }
        BackupAnswer status = (BackupAnswer) answer;
        recordRestoreHost(backup, plan, status, null);
        operation = ensureRestoreOperation(backup, plan);
        if ("COMPLETED".equals(status.getState())
                || StringUtils.contains(status.getDetails(), AblestackBackupFrameworkUtils.RESTORE_ROLLBACK_REQUIRED)
                || (operation.vmResult != null && "RECOVERY_REQUIRED".equals(operation.vmResult.outcome))) {
            throw new CloudRuntimeException("Host VM restore outcome conflicts with queued cancellation; termination and primary cleanup must be reconciled");
        }
        if (java.util.Set.of("FAILED", "INTERRUPTED", "CANCELED").contains(StringUtils.defaultString(status.getState()))) {
            if (operation.cancelConfirmedAt == 0) { operation.cancelConfirmedAt = System.currentTimeMillis(); }
            operation.hostState = "CANCELED";
            operation.cancelReason = "Restore engine termination confirmed; staging cleanup and capacity release are pending";
            final ThirdPartyBackupRestore.Operation canceled = operation;
            com.cloud.utils.db.Transaction.execute((com.cloud.utils.db.TransactionCallback<Boolean>) transaction -> {
                writeRestoreOperation(backup.getId(), canceled);
                backupDetailsDao.addDetail(backup.getId(), AblestackBackupFrameworkUtils.RESTORE_JOB_STATE_DETAIL, "CANCELED", false);
                backupDetailsDao.addDetail(backup.getId(), AblestackBackupFrameworkUtils.RESTORE_JOB_STEP_DETAIL, "CANCELED", false);
                backupDetailsDao.addDetail(backup.getId(), AblestackBackupFrameworkUtils.RESTORE_JOB_PROGRESS_DETAIL, "0", false);
                backupDetailsDao.addDetail(backup.getId(), AblestackBackupFrameworkUtils.RESTORE_JOB_FAILURE_REASON_DETAIL, canceled.cancelReason, false);
                return true;
            });
            cleanupRestore(backup, host, plan, transfer);
            return true;
        }
        Answer cancel = agentManager.send(host.getId(), new AblestackVolumeStagingCommand(plan.jobId, "RESTORE_CANCEL", -1, new Gson().toJson(plan)));
        if (cancel != null && cancel.getResult() && operation.cancelConfirmedAt == 0) {
            operation.cancelConfirmedAt = System.currentTimeMillis();
            writeRestoreOperation(backup.getId(), operation);
        }
        // A missing response is not completion. Replay this owned marker after reconnect/restart.
        return true;
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
            ThirdPartyBackupRestore.Plan savedPlan = new Gson().fromJson(tracked.getDetail(ThirdPartyBackupRestore.PLAN_KEY), ThirdPartyBackupRestore.Plan.class);
            if (savedPlan == null || !plan.jobId.equals(savedPlan.jobId) || !matchesRestoreTracking(tracked, plan.jobId)
                    || "COMPLETED".equals(tracked.getDetail(ThirdPartyBackupRestore.CLEANUP_STATE_KEY))) {
                finishRestoreTracking(plan.jobId); return;
            }
            requireRestoreWorker(tracked, host, savedPlan);
            ensureRestoreOperation(tracked, savedPlan);
            if (reconcilePendingRestoreCancellation(tracked, host, savedPlan, transfer)) { return; }
            Answer statusAnswer = agentManager.send(host.getId(), new AblestackRestoreJobStatusCommand(plan.jobId, null, 0));
            if (!(statusAnswer instanceof BackupAnswer) || !statusAnswer.getResult()) {
                recordRestoreHost(tracked, savedPlan, null, "Restore Worker Host did not confirm its engine state");
                recoverRestoreRecords(tracked, savedPlan, transfer);
                return;
            }
            BackupAnswer hostStatus = (BackupAnswer) statusAnswer;
            recordRestoreHost(tracked, savedPlan, hostStatus, null);
            boolean terminal = java.util.Set.of("COMPLETED", "FAILED", "INTERRUPTED", "CANCELED")
                    .contains(StringUtils.defaultString(hostStatus.getState()));
            if ("UNKNOWN".equals(hostStatus.getState()) && finishUnstartedRestore(tracked, host, savedPlan, false)) { return; }
            Answer answer = agentManager.send(host.getId(), new AblestackVolumeStagingCommand(plan.jobId, "RESTORE_STATUS", -1, null));
            if (answer == null || !answer.getResult()) {
                // The immutable DB request still permits discovery/polling, never a fresh submission.
                recoverRestoreRecords(tracked, savedPlan, transfer);
                if (terminal) { cleanupRestore(tracked, host, savedPlan, transfer); }
                else { saveRestoreCleanup(backupId, plan.jobId, "WAITING", "Host request is unavailable; recorded external transfers are being reconciled"); }
                return;
            }
            if (StringUtils.isBlank(answer.getDetails())) {
                recoverRestoreRecords(tracked, savedPlan, transfer);
                if (terminal) { cleanupRestore(tracked, host, savedPlan, transfer); }
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> control = new Gson().fromJson(answer.getDetails(), Map.class);
            if (Boolean.TRUE.equals(control.get("admission"))) {
                requireRestoreWritersTerminated(tracked, savedPlan);
                if (terminal) { cleanupRestore(tracked, host, savedPlan, transfer); }
                else { reconcileAdmission(tracked, host, plan.jobId, "RESTORE", control); }
                return;
            }
            ThirdPartyBackupRestore.Request request = new Gson().fromJson(answer.getDetails(), ThirdPartyBackupRestore.Request.class);
            ThirdPartyBackupRestore.Record record = observeRestoreRequest(tracked, savedPlan, request);
            observeAdmitted(backupId, host, plan.jobId, "RESTORE");
            ThirdPartyBackupRestore.Result saved = record.result;
            if (!saved.completed && !saved.externalTerminal) {
                boolean recover = externalRestoreStarted(saved) || terminal;
                if (!externalRestoreStarted(saved)) {
                    if (terminal) { cleanupRestore(tracked, host, savedPlan, transfer); return; }
                    if (!"RUNNING".equals(hostStatus.getState())) { return; }
                    saved.submissionPending = true;
                    saved.submittedAt = System.currentTimeMillis();
                    persistRestoreRecord(tracked, savedPlan, record);
                }
                try {
                    ThirdPartyBackupRestore.Result result = recover
                            ? transfer.recover(record.request, copyRestoreResult(saved))
                            : transfer.restore(record.request, copyRestoreResult(saved));
                    saveRestoreResult(tracked, savedPlan, record, result);
                } catch (RuntimeException e) {
                    if (e instanceof ThirdPartyBackupSubmissionException && StringUtils.isBlank(saved.jobId)) {
                        saveRestoreResult(tracked, savedPlan, record, ThirdPartyBackupRestore.Result.notSubmitted(e.getMessage()));
                    } else {
                        record.checkedAt = System.currentTimeMillis();
                        record.queryError = StringUtils.defaultString(e.getMessage(), "External restore submission or query is unconfirmed");
                        persistRestoreRecord(tracked, savedPlan, record);
                        saveRestoreCleanup(backupId, plan.jobId, "WAITING", "Sequence " + request.sequence + ": " + record.queryError);
                        return;
                    }
                }
                saved = record.result;
            }
            if (terminal) {
                recoverRestoreRecords(tracked, savedPlan, transfer);
                cleanupRestore(tracked, host, savedPlan, transfer);
                return;
            }
            if (saved.externalTerminal && !saved.completed) {
                agentManager.send(host.getId(), new AblestackVolumeStagingCommand(plan.jobId, "RESTORE_FAIL", -1,
                        new Gson().toJson(java.util.Map.of("message", StringUtils.defaultIfBlank(saved.failure, "External artifact restore failed"), "terminal", true))));
                return;
            }
            if (saved.completed) {
                Answer acknowledgment = agentManager.send(host.getId(), new AblestackVolumeStagingCommand(plan.jobId, "RESTORE_ACK", request.sequence,
                        new Gson().toJson(record.request)));
                if (acknowledgment != null && acknowledgment.getResult() && record.acknowledgedAt == 0) {
                    record.acknowledgedAt = System.currentTimeMillis();
                    persistRestoreRecord(tracked, savedPlan, record);
                }
            }
        } catch (com.cloud.exception.AgentUnavailableException | com.cloud.exception.OperationTimedoutException e) {
            BackupVO tracked = backupDao.findById(backupId);
            if (tracked != null) {
                backupDao.loadDetails(tracked);
                if (plan.jobId.equals(stagingAttempt(tracked, "RESTORE"))) {
                    try {
                        recordRestoreHost(tracked, plan, null, "Restore Worker Host is unavailable");
                        recoverRestoreRecords(tracked, plan, transfer);
                    } catch (RuntimeException recoveryError) {
                        logger.debug("Restore history reconciliation is pending [{}]: {}", plan.jobId, recoveryError.getMessage());
                    }
                }
            }
            saveRestoreCleanup(backupId, plan.jobId, "WAITING", "Restore Worker Host is unavailable; its termination and cleanup remain unconfirmed");
        } catch (RuntimeException e) {
            saveRestoreCleanup(backupId, plan.jobId, "WAITING", StringUtils.defaultString(e.getMessage()));
            logger.debug("Volume restore reconciliation is pending for job [{}]: {}", plan.jobId, e.getMessage());
        } finally {
            if (acquired) { lock.unlock(); }
            lock.releaseRef();
        }
    }

    private void cleanupRestore(BackupVO backup, Host host, ThirdPartyBackupRestore.Plan plan, RestoreTransfer transfer)
            throws com.cloud.exception.AgentUnavailableException, com.cloud.exception.OperationTimedoutException {
        requireRestoreWorker(backup, host, plan);
        ensureRestoreOperation(backup, plan);
        requireRestoreWritersTerminated(backup, plan);
        if (finishUnstartedRestore(backup, host, plan, true)) { return; }
        String providerRoot = stagingService.getStageRootPath(plan.manifest.getProvider());
        if (!java.nio.file.Path.of(providerRoot).getParent().toString().equals(plan.stageRoot)) {
            throw new CloudRuntimeException("Restore staging configuration changed; restore the original mount before cleanup");
        }
        stagingService.prepareCleanup(host, plan.destination);
        saveRestoreCleanup(backup.getId(), plan.jobId, "RUNNING", null);
        Answer answer = agentManager.send(host.getId(), new AblestackVolumeStagingCommand(plan.jobId, "RESTORE_CLEANUP", -1,
                new Gson().toJson(plan)));
        boolean done = answer != null && answer.getResult();
        if (done) {
            if (plan.vmResultVersion == 1) {
                ThirdPartyBackupRestore.Operation operation = ensureRestoreOperation(backup, plan);
                mergeVmRestoreResult(operation, new Gson().fromJson(answer.getDetails(), ThirdPartyBackupRestore.VmResult.class));
                operation.vmResultCheckedAt = System.currentTimeMillis();
                operation.vmResultError = null;
                writeRestoreOperation(backup.getId(), operation);
                ThirdPartyBackupRestore.VmResult result = operation.vmResult;
                if ((result.transactionId != null && (!java.util.Set.of("COMMITTED", "ROLLED_BACK").contains(result.outcome)
                        || !"COMPLETED".equals(result.primaryCleanupState)))
                        || (result.transactionId == null && !"FAILED".equals(result.outcome))) {
                    throw new CloudRuntimeException("Host did not confirm a terminal VM outcome and primary cleanup; reservations are retained");
                }
            }
            // Every external request is recorded before its call. A canceled queue with no
            // requests never created provider receipts and needs no external-service cleanup.
            if (ensureRestoreOperation(backup, plan).cancelRequestedAt == 0 || !restoreRecords(backup, plan).isEmpty()) {
                transfer.cleanup(plan);
            }
            releaseAdmission(backup.getId(), "RESTORE", plan.jobId);
        }
        saveRestoreCleanup(backup.getId(), plan.jobId, done ? "COMPLETED" : "WAITING", done ? null
                : answer == null ? "Restore cleanup returned no Host response" : answer.getDetails());
        if (done) { finishRestoreTracking(plan.jobId); }
    }

    private void saveRestoreCleanup(long backupId, String jobId, String state, String details) {
        BackupDetailVO active = backupDetailsDao.findDetail(backupId, ThirdPartyBackupRestore.PLAN_KEY);
        if (active == null) { return; }
        ThirdPartyBackupRestore.Plan plan = new Gson().fromJson(active.getValue(), ThirdPartyBackupRestore.Plan.class);
        if (plan == null || !jobId.equals(plan.jobId)) { return; }
        BackupVO backup = stagingBackup(backupId);
        ThirdPartyBackupRestore.Operation operation = readRestoreOperation(backup, plan);
        if (operation != null) {
            operation.cleanupState = state;
            operation.cleanupReason = details;
            if (operation.cancelRequestedAt > 0 && "COMPLETED".equals(state)) {
                operation.cancelReason = "Restore canceled; staging and capacity reservations cleaned";
            }
        }
        com.cloud.utils.db.Transaction.execute((com.cloud.utils.db.TransactionCallback<Boolean>) status -> {
            backupDetailsDao.addDetail(backupId, ThirdPartyBackupRestore.CLEANUP_STATE_KEY, state, false);
            if (StringUtils.isBlank(details)) { backupDetailsDao.removeDetail(backupId, ThirdPartyBackupRestore.CLEANUP_DETAILS_KEY); }
            else { backupDetailsDao.addDetail(backupId, ThirdPartyBackupRestore.CLEANUP_DETAILS_KEY, details, false); }
            if (operation != null) { writeRestoreOperation(backupId, operation); }
            return true;
        });
    }

    private void finishRestoreTracking(String id) {
        java.util.concurrent.ScheduledFuture<?> task = restores.remove(id);
        if (task != null) { task.cancel(false); }
    }

    private void validateRestoreRequest(ThirdPartyBackupRestore.Plan plan, ThirdPartyBackupRestore.Request request) {
        if (plan == null || plan.manifest == null || plan.volumeUuids == null || request == null || request.sequence < 0) {
            throw new CloudRuntimeException("Restore request has no persisted plan or sequence");
        }
        ThirdPartyBackupRestore.Request expected = null;
        if (request.sequence == 0 && plan.manifest.getMetadata() != null) {
            expected = restoreRequest(plan, 0, -1, -1, true, plan.manifest.getMetadata());
        } else if (request.volumeIndex >= 0 && request.volumeIndex < plan.volumeUuids.size() && request.chainIndex >= 0) {
            int sequence = 1;
            for (int index = 0; index <= request.volumeIndex; index++) {
                String uuid = plan.volumeUuids.get(index);
                ThirdPartyBackupManifest.Volume volume = plan.manifest.getVolumes().stream()
                        .filter(value -> uuid.equals(value.uuid)).findFirst().orElseThrow();
                if (index == request.volumeIndex && request.chainIndex < volume.chain.size()) {
                    expected = restoreRequest(plan, sequence + request.chainIndex, index, request.chainIndex, false, volume.chain.get(request.chainIndex));
                }
                sequence += volume.chain.size();
            }
        }
        if (expected == null || !new Gson().toJson(expected).equals(new Gson().toJson(request))) {
            throw new CloudRuntimeException("Restore request differs from the persisted VM, volume, chain, sequence or destination plan");
        }
    }

    /** Never replay the start command: only the stable Host receipt can settle dispatch uncertainty. */
    private boolean reconcileBackupStart(BackupVO backup, Host host, ThirdPartyBackupManifest manifest)
            throws com.cloud.exception.AgentUnavailableException, com.cloud.exception.OperationTimedoutException {
        ThirdPartyBackupStart.Operation start = readBackupStart(backup.getId());
        if (start == null) { return false; }
        if (!backup.getUuid().equals(start.plan.jobId) || host.getId() != start.plan.hostId) {
            throw new CloudRuntimeException("Backup start receipt belongs to a different Worker Host or job");
        }
        if ("STARTED".equals(start.state)) { return false; }
        if ("START_FAILED".equals(start.state)
                && "COMPLETED".equals(backup.getDetail(ThirdPartyBackupManifest.CLEANUP_STATE_KEY))) { return false; }
        // A lost PREPARE response is safe to recover: it cannot launch an engine.
        String action = java.util.Set.of("PREPARING", "PREPARED").contains(start.state)
                ? "BACKUP_START_PREPARE" : "BACKUP_START_STATUS";
        ThirdPartyBackupStart.Receipt receipt = backupStartControl(host, start.plan, action);
        start.checkedAt = receipt.checkedAt;
        if ("STARTED".equals(receipt.state)) {
            if ("START_FAILED".equals(start.state)) { throw new CloudRuntimeException("Host engine conflicts with the recorded start fence"); }
            start.state = "STARTED";
            start.reason = null;
            saveBackupStart(backup.getId(), start);
            return false;
        }
        long submittedAt = start.submittedAt > 0 ? start.submittedAt : start.createdAt;
        if (!"START_FAILED".equals(receipt.state) && start.cancelRequestedAt == 0
                && !isFailedPipeline(backup) && StringUtils.isBlank(start.reason)
                && System.currentTimeMillis() < submittedAt + TimeUnit.SECONDS.toMillis(60)) {
            saveBackupStart(backup.getId(), start);
            return true;
        }
        if (hasOwnedTransferAttempt(manifest)) {
            throw new CloudRuntimeException("External transfers conflict with the unstarted backup receipt");
        }
        // Host lock also covers Python's first action. ABORT seals delayed Agent and Python starts
        // before acknowledging that no staging/source IO occurred and admission is closed.
        receipt = backupStartControl(host, start.plan, "BACKUP_START_ABORT");
        start.checkedAt = receipt.checkedAt;
        if ("STARTED".equals(receipt.state)) {
            start.state = "STARTED";
            start.reason = null;
            saveBackupStart(backup.getId(), start);
            return false;
        }
        if (!"START_FAILED".equals(receipt.state)) { throw new CloudRuntimeException("Host did not confirm the backup start fence"); }
        start.state = "START_FAILED";
        start.reason = StringUtils.defaultIfBlank(start.reason, receipt.reason);
        if (start.cancelRequestedAt > 0) {
            start.cancelConfirmedAt = receipt.checkedAt;
            start.cancelReason = "Host start blocked; capacity release and Host job record cleanup are pending";
        }
        backup.setStatus(start.cancelRequestedAt > 0 || backup.getStatus() == Backup.Status.Canceled ? Backup.Status.Canceled : Backup.Status.Failed);
        backup.getDetails().put("thirdparty.volume.failure", start.cancelRequestedAt > 0
                ? "Backup canceled before Host engine initialization" : start.reason);
        final ThirdPartyBackupStart.Operation failedStart = start;
        com.cloud.utils.db.Transaction.execute((com.cloud.utils.db.TransactionCallback<Boolean>) transaction -> {
            if (!backupDao.update(backup.getId(), backup)) { throw new CloudRuntimeException("Unable to persist confirmed backup start failure"); }
            saveBackupStart(backup.getId(), failedStart);
            releaseAdmission(backup.getId(), "BACKUP", backup.getUuid());
            saveCleanupState(backup.getId(), "COMPLETED", null);
            backupDetailsDao.addDetail(backup.getId(), ThirdPartyBackupManifest.JOB_CLEANUP_STATE_KEY, "WAITING", false);
            return true;
        });
        backupDao.loadDetails(backup);
        cleanupTerminalBackupRecords(backup, host);
        return true;
    }

    private boolean hasOwnedTransferAttempt(ThirdPartyBackupManifest manifest) {
        return manifest.getOwnedArtifacts().stream().anyMatch(artifact -> artifact.submissionPending || artifact.completed
                || artifact.submittedAt > 0 || artifact.size > 0
                || StringUtils.isNotBlank(artifact.jobId) || StringUtils.isNotBlank(artifact.externalId));
    }

    /** A queued cancellation blocks grants and external submissions until the engine confirms termination. */
    private boolean requestPendingBackupCancellation(BackupVO backup, Host host)
            throws com.cloud.exception.AgentUnavailableException, com.cloud.exception.OperationTimedoutException {
        ThirdPartyBackupStart.Operation start = readBackupStart(backup.getId());
        if (start == null || start.cancelRequestedAt <= 0 || backup.getStatus() != Backup.Status.BackingUp) { return false; }
        if (!"STARTED".equals(start.state) || hasOwnedTransferAttempt(
                ThirdPartyBackupManifest.fromJson(backup.getDetail(ThirdPartyBackupManifest.DETAIL_KEY)))) {
            throw new CloudRuntimeException("Queued cancellation has conflicting engine or external transfer records");
        }
        Answer answer = agentManager.send(host.getId(), new AblestackVolumeStagingCommand(backup.getUuid(), "CANCEL", -1, null));
        if (answer != null && answer.getResult() && start.cancelConfirmedAt == 0) {
            start.cancelConfirmedAt = System.currentTimeMillis();
            saveBackupStart(backup.getId(), start);
        }
        // Retry the idempotent marker while the engine is active, including after response loss/restart.
        return true;
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
        if (host == null) {
            return true;
        }
        // Check ownership before entering the failure handler: a wrong Host must never fail/cancel this pipeline.
        requireBackupWorker(tracked, host);
        GlobalLock lock = GlobalLock.getInternLock("backup.volume." + tracked.getUuid());
        boolean acquired = false;
        try {
            acquired = lock.lock(1);
            if (!acquired) {
                return true;
            }
            tracked = backupDao.findById(backup.getId());
            if (tracked == null) {
                return true;
            }
            backupDao.loadDetails(tracked);
            ThirdPartyBackupManifest manifest = ThirdPartyBackupManifest.fromJson(tracked.getDetail(ThirdPartyBackupManifest.DETAIL_KEY));
            if ((tracked.getStatus() == Backup.Status.BackingUp || isFailedPipeline(tracked))
                    && reconcileBackupStart(tracked, host, manifest)) { return true; }
            if (isFailedPipeline(tracked)) {
                try (Lease lease = acquireLifecycle(tracked.getVmId())) {
                    reconcileFailedBackup(tracked, host, manifest, transfer);
                }
                return true;
            }
            if (tracked.getStatus() != Backup.Status.BackingUp) { return true; }
            if (ThirdPartyBackupManifest.hasCompletedTransfers(tracked)) {
                saveFinalizationWaiting(tracked, StringUtils.defaultIfBlank(
                        tracked.getDetail(ThirdPartyBackupManifest.FINALIZATION_DETAILS_KEY),
                        "Waiting for Host finalization and logical backup completion"));
            }
            BackupAnswer status = (BackupAnswer) agentManager.send(host.getId(), new AblestackBackupJobStatusCommand(tracked.getUuid()));
            if (status == null || !status.getResult()) {
                return true;
            }
            if (ThirdPartyBackupManifest.hasCompletedTransfers(tracked)
                    && java.util.Set.of("COMPLETED", "FAILED", "INTERRUPTED", "CANCELED").contains(StringUtils.defaultString(status.getState()))) {
                // Engine exit only proves termination. Exact persisted catalog references
                // decide success; a cleanup IO error cannot invalidate completed transfers.
                finalizeBackup(tracked, host, manifest, transfer);
                return true;
            }
            if ("FAILED".equals(status.getState()) || "INTERRUPTED".equals(status.getState()) || "CANCELED".equals(status.getState())) {
                ThirdPartyBackupStart.Operation start = readBackupStart(tracked.getId());
                boolean cancellationRequested = start != null && start.cancelRequestedAt > 0;
                notifyFailure(transfer, tracked);
                tracked.setStatus(cancellationRequested || "CANCELED".equals(status.getState()) ? Backup.Status.Canceled : Backup.Status.Failed);
                tracked.getDetails().put("thirdparty.volume.failure", StringUtils.defaultString(status.getDetails()));
                BackupVO terminalBackup = tracked;
                com.cloud.utils.db.Transaction.execute((com.cloud.utils.db.TransactionCallback<Boolean>) transaction -> {
                    if (!backupDao.update(terminalBackup.getId(), terminalBackup)) { throw new CloudRuntimeException("Unable to persist terminal backup state"); }
                    if (Backup.Status.Canceled.equals(terminalBackup.getStatus())) {
                        // Queue cancellation also uses the common cleanup and record deletion sequence.
                        backupDetailsDao.addDetail(terminalBackup.getId(), ThirdPartyBackupManifest.JOB_CLEANUP_STATE_KEY, "WAITING", false);
                        if (cancellationRequested) {
                            if (start.cancelConfirmedAt == 0) { start.cancelConfirmedAt = System.currentTimeMillis(); }
                            start.cancelReason = "Host engine termination confirmed; staging cleanup and capacity release are pending";
                            saveBackupStart(terminalBackup.getId(), start);
                        }
                    }
                    return true;
                });
                return true;
            }
            if ("COMPLETED".equals(status.getState())) {
                manifest.validate(true);
                finalizeBackup(tracked, host, manifest, transfer);
                return true;
            }
            if (requestPendingBackupCancellation(tracked, host)) { return true; }
            Answer requestAnswer = agentManager.send(host.getId(), new AblestackVolumeStagingCommand(tracked.getUuid(), "STATUS", -1, null));
            if (requestAnswer == null || !requestAnswer.getResult() || StringUtils.isBlank(requestAnswer.getDetails())) {
                return true;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> request = new Gson().fromJson(requestAnswer.getDetails(), Map.class);
            if (Boolean.TRUE.equals(request.get("admission"))) {
                reconcileAdmission(tracked, host, tracked.getUuid(), "BACKUP", request);
                return true;
            }
            if (Boolean.TRUE.equals(request.get("gate"))) {
                if (!tracked.getUuid().equals(request.get("backupUuid"))) {
                    throw new CloudRuntimeException("Source preparation belongs to another backup");
                }
                observeAdmitted(tracked.getId(), host, tracked.getUuid(), "BACKUP");
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
            observeAdmitted(tracked.getId(), host, tracked.getUuid(), "BACKUP");
            ThirdPartyBackupManifest.Artifact requested = new Gson().fromJson(new Gson().toJson(request.get("artifact")),
                    ThirdPartyBackupManifest.Artifact.class);
            if (requested == null || requested.size < 0) {
                throw new CloudRuntimeException("Host requested an invalid backup artifact size");
            }
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
                    artifact = new Gson().fromJson(new Gson().toJson(requested), ThirdPartyBackupManifest.Artifact.class);
                    manifest.setMetadata(artifact);
                } else if (!java.util.Objects.equals(artifact.path, requested.path)
                        || !java.util.Objects.equals(artifact.backupUuid, requested.backupUuid)) {
                    throw new CloudRuntimeException("Host metadata differs from its saved transfer request");
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
                        ThirdPartyBackupManifest.Artifact recovered = transfer.recover(artifact, metadata);
                        if (recovered == null || StringUtils.isBlank(recovered.jobId)) {
                            saveCleanupState(tracked.getId(), "WAITING", "External submission is unconfirmed; waiting for an exact artifact job reference");
                            return true;
                        }
                        if (!artifact.path.equals(recovered.path) || !artifact.backupUuid.equals(recovered.backupUuid)
                                || (!metadata && artifact.size != recovered.size)) {
                            throw new CloudRuntimeException("Recovered external job differs from the saved artifact");
                        }
                        // Keep the descriptor attached to the manifest, even when a provider returns a copy.
                        artifact.jobId = recovered.jobId;
                        artifact.externalId = recovered.externalId;
                        artifact.backupTime = recovered.backupTime;
                        artifact.completed = recovered.completed;
                        artifact.externalTerminal = recovered.externalTerminal;
                        artifact.externalFailure = recovered.externalFailure;
                        artifact.submissionPending = false;
                        tracked.getDetails().put(ThirdPartyBackupManifest.DETAIL_KEY, manifest.toJson());
                        if (!backupDao.update(tracked.getId(), tracked)) { throw new CloudRuntimeException("Unable to save recovered artifact job"); }
                    } else {
                        // A controller restart after an accepted request must not silently submit it again.
                        artifact.submissionPending = true;
                        artifact.submittedAt = System.currentTimeMillis();
                        tracked.getDetails().put(ThirdPartyBackupManifest.DETAIL_KEY, manifest.toJson());
                        if (!backupDao.update(tracked.getId(), tracked)) {
                            throw new CloudRuntimeException("Unable to persist external artifact submission intent");
                        }
                    }
                }
                // Save an accepted child job before polling it. Only a confirmed catalog reference permits unlink.
                ThirdPartyBackupManifest.Artifact result;
                try { result = transfer.backup(artifact, metadata); }
                catch (ThirdPartyBackupSubmissionException e) {
                    if (StringUtils.isNotBlank(artifact.jobId)) { throw new CloudRuntimeException("Submission proof conflicts with an accepted Job ID", e); }
                    artifact.submissionPending = false;
                    artifact.externalTerminal = true;
                    artifact.externalFailure = e.getMessage();
                    tracked.getDetails().put(ThirdPartyBackupManifest.DETAIL_KEY, manifest.toJson());
                    if (!backupDao.update(tracked.getId(), tracked)) { throw new CloudRuntimeException("Unable to persist non-submission proof", e); }
                    throw e;
                }
                catch (RuntimeException e) {
                    saveCleanupState(tracked.getId(), "WAITING", "External artifact request or poll is unconfirmed: " + e.getMessage());
                    return true;
                }
                if (result == null) {
                    return true;
                }
                if (StringUtils.isBlank(result.jobId) || (result.completed && StringUtils.isBlank(result.externalId))
                        || !artifact.path.equals(result.path) || !artifact.backupUuid.equals(result.backupUuid)
                        || (!metadata && artifact.size != result.size)) {
                    throw new CloudRuntimeException("Invalid external artifact job reference");
                }
                artifact.externalId = result.externalId;
                artifact.jobId = result.jobId;
                artifact.backupTime = result.backupTime;
                artifact.completed = result.completed;
                artifact.submissionPending = false;
                artifact.externalTerminal = result.externalTerminal || result.completed;
                artifact.externalFailure = result.externalFailure;
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
                if (StringUtils.isNotBlank(artifact.externalFailure)) { throw new CloudRuntimeException(artifact.externalFailure); }
                saveCleanupState(tracked.getId(), "NONE", null);
                if (!artifact.completed) {
                    return true;
                }
            }
            if (metadata && artifact.completed) {
                manifest.validate(true);
                saveFinalizationWaiting(tracked, "Waiting for Host finalization and logical backup completion");
            }
            Answer ack = agentManager.send(host.getId(), new AblestackVolumeStagingCommand(tracked.getUuid(), "ACK", index, manifest.toJson()));
            if (ack == null || !ack.getResult()) {
                logger.warn("Artifact was persisted but Host acknowledgment is pending for backup [{}], volume [{}]", tracked.getUuid(), index);
            }
        } catch (com.cloud.exception.AgentUnavailableException | com.cloud.exception.OperationTimedoutException e) {
            saveBackupStartReason(tracked.getId(), "Host is temporarily unavailable; backup start confirmation is pending");
            if (ThirdPartyBackupManifest.hasCompletedTransfers(tracked)) {
                saveFinalizationWaiting(tracked, "Host is temporarily unavailable; completed transfers are retained");
            }
            logger.warn("Volume pipeline Host is temporarily unavailable for backup [{}]", backup.getUuid());
        } catch (RuntimeException e) {
            ThirdPartyBackupStart.Operation start = readBackupStart(tracked.getId());
            if (start != null && !"STARTED".equals(start.state)) {
                saveBackupStartReason(tracked.getId(), e.getMessage());
                logger.warn("Backup start confirmation remains pending [{}]: {}", tracked.getUuid(), e.getMessage());
                return true;
            }
            if (ThirdPartyBackupManifest.hasCompletedTransfers(tracked)) {
                saveFinalizationWaiting(tracked, e.getMessage());
                logger.warn("Logical backup [{}] transfers are complete; finalization will be retried: {}", tracked.getUuid(), e.getMessage());
                return true;
            }
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

    /** All transfers are confirmed; failures here retry finalization, never submit or delete artifacts. */
    private void finalizeBackup(BackupVO backup, Host host, ThirdPartyBackupManifest manifest, Transfer transfer) {
        try {
            manifest.validate(true);
            long size = manifest.getCurrentArtifacts().stream().mapToLong(artifact -> artifact.size).reduce(0L, Math::addExact);
            validateCleanupMount(host, manifest);
            Answer finalized = agentManager.send(host.getId(), new AblestackVolumeStagingCommand(backup.getUuid(), "FINALIZE_BACKUP", -1, manifest.toJson()));
            if (finalized == null || !finalized.getResult()) {
                throw new CloudRuntimeException(finalized == null ? "Host finalization returned no response" : finalized.getDetails());
            }
            transfer.completed(backup);
            if (manifest.getVolumes().stream().anyMatch(volume -> volume.engine.startsWith("RBD")
                    && StringUtils.isNotBlank(volume.chain.get(volume.chain.size() - 1).parentCheckpointName))
                    && !"COMPLETED".equals(backup.getDetail(ThirdPartyBackupManifest.SOURCE_CLEANUP_STATE_KEY))) {
                backupDetailsDao.addDetail(backup.getId(), ThirdPartyBackupManifest.SOURCE_CLEANUP_STATE_KEY, "WAITING", false);
                trackSourceCleanup(backup.getId());
            }
            releaseAdmission(backup.getId(), "BACKUP", backup.getUuid());
            com.cloud.utils.db.Transaction.execute((com.cloud.utils.db.TransactionCallback<Boolean>) status -> {
                backup.setStatus(Backup.Status.BackedUp);
                backup.setSize(size);
                if (!backupDao.update(backup.getId(), backup)) { throw new CloudRuntimeException("Unable to persist logical backup completion"); }
                backupDetailsDao.addDetail(backup.getId(), ThirdPartyBackupManifest.FINALIZATION_STATE_KEY, "COMPLETED", false);
                backupDetailsDao.removeDetail(backup.getId(), ThirdPartyBackupManifest.FINALIZATION_DETAILS_KEY);
                reconcileResourceCounts(backup);
                return true;
            });
        } catch (Exception e) {
            // The persisted backup remains BackingUp, so restart/provider reconciliation
            // retries Host cleanup and completion without replaying any child transfer.
            saveFinalizationWaiting(backup, e.getMessage());
            logger.warn("Logical backup [{}] transfers are complete; finalization will be retried: {}", backup.getUuid(), e.getMessage());
        }
    }

    private void saveFinalizationWaiting(BackupVO backup, String reason) {
        String details = StringUtils.defaultIfBlank(reason, "Logical backup finalization is pending");
        if ("WAITING".equals(backup.getDetail(ThirdPartyBackupManifest.FINALIZATION_STATE_KEY))
                && details.equals(backup.getDetail(ThirdPartyBackupManifest.FINALIZATION_DETAILS_KEY))) { return; }
        try {
            com.cloud.utils.db.Transaction.execute((com.cloud.utils.db.TransactionCallback<Boolean>) status -> {
                backupDetailsDao.addDetail(backup.getId(), ThirdPartyBackupManifest.FINALIZATION_STATE_KEY, "WAITING", false);
                backupDetailsDao.addDetail(backup.getId(), ThirdPartyBackupManifest.FINALIZATION_DETAILS_KEY, details, false);
                return true;
            });
            backup.getDetails().put(ThirdPartyBackupManifest.FINALIZATION_STATE_KEY, "WAITING");
            backup.getDetails().put(ThirdPartyBackupManifest.FINALIZATION_DETAILS_KEY, details);
        } catch (RuntimeException saveFailure) {
            logger.warn("Unable to persist finalization retry for backup [{}]: {}", backup.getUuid(), saveFailure.getMessage());
        }
    }

    private boolean isFailedPipeline(Backup backup) {
        return java.util.Set.of(Backup.Status.Failed, Backup.Status.Error, Backup.Status.Canceled).contains(backup.getStatus())
                && StringUtils.isBlank(backup.getDetail(ThirdPartyBackupManifest.CATALOG_FAILURE_KEY))
                && StringUtils.isBlank(backup.getDetail(ThirdPartyBackupManifest.DELETE_PROGRESS_KEY));
    }

    private void reconcileFailedBackup(BackupVO backup, Host host, ThirdPartyBackupManifest manifest, Transfer transfer) {
        boolean physicalCleanupCompleted = "COMPLETED".equals(backup.getDetail(ThirdPartyBackupManifest.CLEANUP_STATE_KEY));
        try {
            if (Backup.Status.Canceled.equals(backup.getStatus()) && StringUtils.isBlank(backup.getDetail("thirdparty.volume.failure"))) {
                notifyFailure(transfer, backup);
                backupDetailsDao.addDetail(backup.getId(), "thirdparty.volume.failure", "Backup canceled by operator", false);
            }
            if ("COMPLETED".equals(backup.getDetail(ThirdPartyBackupManifest.CLEANUP_STATE_KEY))) {
                // Recover older completions interrupted between physical cleanup and DB release.
                releaseAdmission(backup.getId(), "BACKUP", backup.getUuid());
                cleanupTerminalBackupRecords(backup, host);
                return;
            }
            Answer state = agentManager.send(host.getId(), new AblestackBackupJobStatusCommand(backup.getUuid()));
            if (!(state instanceof BackupAnswer) || !state.getResult()
                    || !java.util.Set.of("COMPLETED", "FAILED", "INTERRUPTED", "CANCELED").contains(StringUtils.defaultString(((BackupAnswer) state).getState()))) {
                agentManager.send(host.getId(), new AblestackVolumeStagingCommand(backup.getUuid(), "CANCEL", -1, null));
                saveCleanupState(backup.getId(), "WAITING", "Backup engine termination is not yet confirmed");
                return;
            }
            boolean sourceNotStarted = false;
            if (manifest.getOwnedArtifacts().stream().noneMatch(artifact -> artifact.submissionPending || artifact.completed
                    || artifact.submittedAt > 0 || artifact.size > 0
                    || StringUtils.isNotBlank(artifact.jobId) || StringUtils.isNotBlank(artifact.externalId))) {
                Answer start = agentManager.send(host.getId(), new AblestackVolumeStagingCommand(backup.getUuid(), "SOURCE_START_STATUS", -1, null));
                if (start != null && start.getResult()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> receipt = new Gson().fromJson(start.getDetails(), Map.class);
                    sourceNotStarted = receipt != null && receipt.get("version") instanceof Number
                            && ((Number) receipt.get("version")).doubleValue() == 1
                            && backup.getUuid().equals(receipt.get("backupUuid")) && "NOT_STARTED".equals(receipt.get("state"));
                }
            }
            // A missing template/invalid settings must not prevent cleanup when
            // the terminated Host engine proves no source IO or child transfer began.
            if (!sourceNotStarted && !transfer.ready()) {
                saveCleanupState(backup.getId(), "WAITING", "External parent backup and its hooks have not confirmed termination");
                return;
            }
            for (ThirdPartyBackupManifest.Artifact artifact : manifest.getOwnedArtifacts()) {
                if (artifact.completed || artifact.externalTerminal) { continue; }
                if (StringUtils.isBlank(artifact.jobId) && !artifact.submissionPending) { continue; }
                ThirdPartyBackupManifest.Artifact result = transfer.recover(artifact, artifact == manifest.getMetadata());
                if (result != null && StringUtils.isNotBlank(result.jobId)) {
                    if (!artifact.path.equals(result.path) || !backup.getUuid().equals(result.backupUuid)) {
                        throw new CloudRuntimeException("Recovered external job does not belong to this backup artifact");
                    }
                    artifact.jobId = result.jobId;
                    artifact.sourceHost = result.sourceHost;
                    artifact.externalId = result.externalId;
                    artifact.backupTime = result.backupTime;
                    artifact.completed = result.completed;
                    artifact.externalTerminal = result.externalTerminal || result.completed;
                    artifact.externalFailure = result.externalFailure;
                    artifact.submissionPending = false;
                    backupDetailsDao.addDetail(backup.getId(), ThirdPartyBackupManifest.DETAIL_KEY, manifest.toJson(), false);
                }
                if (!artifact.completed && !artifact.externalTerminal) {
                    saveCleanupState(backup.getId(), "WAITING", "External artifact reader is active or unconfirmed: " + artifact.path);
                    return;
                }
            }
            saveCleanupState(backup.getId(), "RUNNING", null);
            validateCleanupMount(host, manifest);
            Answer cleanup = agentManager.send(host.getId(), new AblestackVolumeStagingCommand(backup.getUuid(),
                    sourceNotStarted ? "CLEANUP_UNSTARTED" : "CLEANUP_FAILED", -1, manifest.toJson()));
            if (cleanup == null || !cleanup.getResult()) {
                throw new CloudRuntimeException(cleanup == null ? "Host cleanup returned no response" : cleanup.getDetails());
            }
            // Physical cleanup has proved that no reader/writer retains capacity.
            // Persist release before completion so a restart still schedules a retry.
            releaseAdmission(backup.getId(), "BACKUP", backup.getUuid());
            saveCleanupState(backup.getId(), "COMPLETED", null);
            physicalCleanupCompleted = true;
            cleanupTerminalBackupRecords(backup, host);
        } catch (Exception e) {
            if (physicalCleanupCompleted) {
                // A temporary DB slot-release failure cannot invalidate already
                // confirmed physical cleanup, especially after job records were removed.
                backupDetailsDao.addDetail(backup.getId(), ThirdPartyBackupManifest.CLEANUP_DETAILS_KEY,
                        StringUtils.defaultString(e.getMessage()), false);
            } else {
                saveCleanupState(backup.getId(), "WAITING", StringUtils.defaultString(e.getMessage()));
            }
            logger.debug("Failed volume backup [{}] cleanup is pending: {}", backup.getUuid(), e.getMessage());
        }
    }

    private void cleanupTerminalBackupRecords(BackupVO backup, Host host) {
        if (!java.util.Set.of("WAITING", "RUNNING").contains(StringUtils.defaultString(
                backup.getDetail(ThirdPartyBackupManifest.JOB_CLEANUP_STATE_KEY)))) { return; }
        try {
            ThirdPartyBackupAdmission.Entry admission = readAdmission(backup.getId(), "BACKUP");
            if (admission != null && (!backup.getUuid().equals(admission.jobId) || !"RELEASED".equals(admission.state))) {
                throw new CloudRuntimeException("Capacity reservation release must be confirmed before deleting Host job records");
            }
            backupDetailsDao.addDetail(backup.getId(), ThirdPartyBackupManifest.JOB_CLEANUP_STATE_KEY, "RUNNING", false);
            Answer answer = agentManager.send(host.getId(), new AblestackBackupJobCleanupCommand(backup.getUuid()));
            if (answer == null || !answer.getResult()) {
                throw new CloudRuntimeException(answer == null ? "Host job record cleanup returned no response" : answer.getDetails());
            }
            com.cloud.utils.db.Transaction.execute((com.cloud.utils.db.TransactionCallback<Boolean>) status -> {
                backupDetailsDao.addDetail(backup.getId(), ThirdPartyBackupManifest.JOB_CLEANUP_STATE_KEY, "COMPLETED", false);
                backupDetailsDao.removeDetail(backup.getId(), ThirdPartyBackupManifest.JOB_CLEANUP_DETAILS_KEY);
                if (backup.getStatus() == Backup.Status.Canceled) {
                    backupDetailsDao.addDetail(backup.getId(), AblestackBackupFrameworkUtils.BACKUP_CANCELLATION_DETAIL,
                            "Backup canceled; staging, capacity reservations and Host job records cleaned", false);
                    ThirdPartyBackupStart.Operation start = readBackupStart(backup.getId());
                    if (start != null && start.cancelRequestedAt > 0) {
                        start.cancelReason = "Backup canceled; staging, capacity reservations and Host job records cleaned";
                        saveBackupStart(backup.getId(), start);
                    }
                }
                return true;
            });
        } catch (Exception e) {
            // Physical cleanup stays COMPLETED even if the response to record deletion
            // was lost. The deletion is idempotent and retries independently.
            try {
                backupDetailsDao.addDetail(backup.getId(), ThirdPartyBackupManifest.JOB_CLEANUP_STATE_KEY, "WAITING", false);
                backupDetailsDao.addDetail(backup.getId(), ThirdPartyBackupManifest.JOB_CLEANUP_DETAILS_KEY,
                        StringUtils.defaultIfBlank(e.getMessage(), "Host job record cleanup is pending"), false);
            } catch (RuntimeException saveFailure) {
                logger.warn("Unable to persist terminal backup record cleanup retry [{}]: {}", backup.getUuid(), saveFailure.getMessage());
            }
            logger.debug("Terminal backup [{}] Host job record cleanup remains pending: {}", backup.getUuid(), e.getMessage());
        }
    }

    private void validateCleanupMount(Host host, ThirdPartyBackupManifest manifest) {
        java.nio.file.Path providerRoot = java.nio.file.Path.of(stagingService.getStageRootPath(manifest.getProvider()));
        java.nio.file.Path recordedRoot = java.nio.file.Path.of(manifest.getCurrentArtifacts().get(0).path).getParent().getParent().getParent();
        if (!providerRoot.equals(recordedRoot)) {
            throw new CloudRuntimeException("Backup staging configuration changed; restore the original mount before cleanup");
        }
        stagingService.prepareCleanup(host, providerRoot.toString());
    }

    private void notifyFailure(Transfer transfer, Backup backup) {
        try {
            transfer.failed(backup);
        } catch (RuntimeException e) {
            logger.warn("Unable to seal the incremental source after volume backup failure [{}]", backup.getUuid(), e);
        }
    }
}
