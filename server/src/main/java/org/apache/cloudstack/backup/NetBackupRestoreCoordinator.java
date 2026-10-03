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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import javax.inject.Inject;

import org.apache.cloudstack.api.command.admin.backup.UpdateNetBackupCmd;
import org.apache.cloudstack.backup.dao.BackupDao;
import org.apache.cloudstack.backup.dao.BackupDetailsDao;
import org.apache.cloudstack.backup.dao.BackupOfferingDao;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import com.cloud.agent.AgentManager;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.dao.VMInstanceDao;

public class NetBackupRestoreCoordinator extends ManagerBase {

    public static final String DETAIL_NETBACKUP_BACKUP_ID = "netbackup.backup.id";
    public static final String DETAIL_NETBACKUP_POLICY_NAME = "netbackup.policy.name";
    private static final String DETAIL_NETBACKUP_MEMBER_COUNT = "netbackup.backup.member.count";

    private static final String DETAIL_NETBACKUP_RESTORE_VM_UUID = "netbackup.restore.vm.uuid";
    private static final String DETAIL_NETBACKUP_RESTORE_REQUEST_ID = "netbackup.restore.request.id";
    private static final String DETAIL_NETBACKUP_RESTORE_PHASE = "netbackup.restore.phase";
    private static final String DETAIL_NETBACKUP_RESTORE_UPDATED_AT = "netbackup.restore.updated.at";
    private static final String DETAIL_NETBACKUP_RESTORE_ROOT_JOB_ID = "netbackup.restore.root.job.id";
    private static final String DETAIL_NETBACKUP_RESTORE_CHAIN_JOB_ID = "netbackup.restore.chain.job.id";
    private static final String DETAIL_NETBACKUP_RESTORE_HOST_NAME = "netbackup.restore.host.name";
    private static final String DETAIL_NETBACKUP_RESTORE_RESOLVED_EXTERNAL_ID = "netbackup.restore.resolved.external.id";
    private static final String DETAIL_NETBACKUP_RESTORE_FAILURE_REASON = "netbackup.restore.failure.reason";
    private static final long STALE_RESTORE_STATE_THRESHOLD_MS = TimeUnit.DAYS.toMillis(1);

    private static final ConcurrentHashMap<Long, RestoreGuard> RESTORE_GUARDS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, RestoreSession> RESTORE_SESSIONS = new ConcurrentHashMap<>();

    @Inject
    private BackupDao backupDao;
    @Inject
    private BackupDetailsDao backupDetailsDao;
    @Inject
    private BackupOfferingDao backupOfferingDao;
    @Inject
    private VMInstanceDao vmInstanceDao;
    @Inject
    private HostDao hostDao;
    @Inject
    private AgentManager agentMgr;

    public enum RestorePhase {
        CLAIMED,
        PREPARED_PATH_VALIDATING,
        ROOT_RESTORE_IN_PROGRESS,
        CHAIN_RESTORE_IN_PROGRESS,
        HOST_RESTORE_IN_PROGRESS,
        IMPORT_IN_PROGRESS,
        ATTACH_VOLUME_IN_PROGRESS,
        CLEANUP_IN_PROGRESS,
        COMPLETED,
        FAILED
    }

    private static final class RestoreGuard {
        private final String token;
        private final String requestIdentifier;
        private final String vmName;

        private RestoreGuard(final String token, final String requestIdentifier, final String vmName) {
            this.token = token;
            this.requestIdentifier = requestIdentifier;
            this.vmName = vmName;
        }
    }

    public static final class RestoreSession {
        private final String requestIdentifier;
        private final Long backupId;
        private final String backupUuid;
        private final String externalId;
        private final String vmName;
        private volatile RestorePhase phase;

        private RestoreSession(final String requestIdentifier, final Long backupId,
                final String backupUuid, final String externalId, final String vmName) {
            this.requestIdentifier = requestIdentifier;
            this.backupId = backupId;
            this.backupUuid = backupUuid;
            this.externalId = externalId;
            this.vmName = vmName;
            this.phase = RestorePhase.CLAIMED;
        }

        public String getRequestIdentifier() {
            return requestIdentifier;
        }

        public RestorePhase getPhase() {
            return phase;
        }

        private void setPhase(final RestorePhase phase) {
            this.phase = phase;
        }

        private boolean isTerminal() {
            return RestorePhase.COMPLETED.equals(phase) || RestorePhase.FAILED.equals(phase);
        }
    }

    public static final class RestoreResolution {
        private final BackupVO backup;
        private final String requestIdentifier;
        private final String preparedRestoreHostName;

        private RestoreResolution(final BackupVO backup, final String requestIdentifier,
                final String preparedRestoreHostName) {
            this.backup = backup;
            this.requestIdentifier = requestIdentifier;
            this.preparedRestoreHostName = preparedRestoreHostName;
        }

        public BackupVO getBackup() {
            return backup;
        }

        public String getRequestIdentifier() {
            return requestIdentifier;
        }

        public String getPreparedRestoreHostName() {
            return preparedRestoreHostName;
        }
    }

    public BackupVO updateBackupMetadata(final UpdateNetBackupCmd cmd, final VMInstanceVO vm) {
        final BackupVO backup = backupDao.listByVmId(vm.getDataCenterId(), vm.getId()).stream()
                .filter(BackupVO.class::isInstance)
                .map(BackupVO.class::cast)
                .peek(backupDao::loadDetails)
                .filter(candidate -> Backup.Status.BackedUp.equals(candidate.getStatus()) || Backup.Status.BackingUp.equals(candidate.getStatus()))
                .filter(candidate -> {
                    final BackupOffering offering = backupOfferingDao.findById(candidate.getBackupOfferingId());
                    return offering != null && BackupProviderNameUtils.isNetBackupFamily(offering.getProvider());
                })
                .filter(candidate -> StringUtils.equals(candidate.getExternalId(), cmd.getExternalId()))
                .max(Comparator.comparing(BackupVO::getDate, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(BackupVO::getId))
                .orElseThrow(() -> new CloudRuntimeException(String.format(
                        "Unable to find NetBackup backup row for VM [%s] and external ID [%s].",
                        vm.getInstanceName(), cmd.getExternalId())));

        backup.setStatus(resolveStatus(cmd.getStatus()));
        backup.setDate(new Date());
        backupDao.update(backup.getId(), backup);
        upsertBackupDetail(backup.getId(), DETAIL_NETBACKUP_BACKUP_ID, cmd.getBackupId());
        if (StringUtils.isNotBlank(cmd.getPolicyId())) {
            upsertBackupDetail(backup.getId(), DETAIL_NETBACKUP_POLICY_NAME, cmd.getPolicyId());
        }
        if (cmd.getMemberCount() != null && cmd.getMemberCount() > 0) {
            upsertBackupDetail(backup.getId(), DETAIL_NETBACKUP_MEMBER_COUNT, String.valueOf(cmd.getMemberCount()));
        }
        backupDao.loadDetails(backup);
        return backup;
    }

    public String acquireRestoreGuard(final VMInstanceVO vm, final String requestIdentifier) {
        final AtomicReference<String> acquiredToken = new AtomicReference<>();
        final AtomicBoolean blockedByFreshGuard = new AtomicBoolean(false);
        RESTORE_GUARDS.compute(vm.getId(), (vmId, existing) -> {
            if (existing == null) {
                final String token = UUID.randomUUID().toString();
                acquiredToken.set(token);
                logger.info("Acquired NetBackup restore guard for VM [{}] (vmId=[{}]) requestIdentifier=[{}] token=[{}].",
                        vm.getInstanceName(), vmId, requestIdentifier, token);
                return new RestoreGuard(token, requestIdentifier, vm.getInstanceName());
            }

            blockedByFreshGuard.set(true);
            return existing;
        });

        if (acquiredToken.get() == null && blockedByFreshGuard.get()) {
            final RestoreGuard existing = RESTORE_GUARDS.get(vm.getId());
            logger.info("Skipping NetBackup restore re-entry for VM [{}] (vmId=[{}]) requestIdentifier=[{}]; activeRequestIdentifier=[{}], activeVmName=[{}].",
                    vm.getInstanceName(), vm.getId(), requestIdentifier,
                    existing != null ? existing.requestIdentifier : "unknown",
                    existing != null ? existing.vmName : "unknown");
        }
        return acquiredToken.get();
    }

    public void releaseRestoreGuard(final Long vmId, final String guardToken, final String requestIdentifier) {
        if (vmId == null || StringUtils.isBlank(guardToken)) {
            return;
        }

        final AtomicBoolean released = new AtomicBoolean(false);
        RESTORE_GUARDS.computeIfPresent(vmId, (key, existing) -> {
            if (Objects.equals(existing.token, guardToken)) {
                released.set(true);
                return null;
            }
            return existing;
        });

        if (released.get()) {
            logger.info("Released NetBackup restore guard for vmId=[{}] requestIdentifier=[{}] token=[{}].",
                    vmId, requestIdentifier, guardToken);
        } else {
            logger.debug("Skipped releasing NetBackup restore guard for vmId=[{}] requestIdentifier=[{}] token=[{}] because the active guard token changed.",
                    vmId, requestIdentifier, guardToken);
        }
    }

    public RestoreResolution resolveRestoreRequest(final String externalId, final String backupId,
            final int restorePathDiscoveryWindowSeconds, final boolean requireSingleRestorePathInVmRoot) {
        return resolveRestoreRequest(externalId, backupId, restorePathDiscoveryWindowSeconds,
                requireSingleRestorePathInVmRoot, false);
    }

    public RestoreResolution resolveRestoreRequest(final String externalId, final String backupId,
            final int restorePathDiscoveryWindowSeconds, final boolean requireSingleRestorePathInVmRoot,
            final boolean requireSingleCandidateRestorePath) {
        if (StringUtils.isNotBlank(externalId)) {
            try {
                logger.debug("Resolving NetBackup restore by externalId [{}].", externalId);
                final BackupVO backup = findBackupByExternalId(externalId);
                if (requireSingleRestorePathInVmRoot) {
                    backupDao.loadDetails(backup);
                    final String restoreHostName = resolveRestoreHostName(Collections.singletonList(backup),
                            StringUtils.defaultIfBlank(backup.getDetail(DETAIL_NETBACKUP_BACKUP_ID), externalId));
                    final List<String> allowedRestorePaths = getExternalIds(Collections.singletonList(backup));
                    final String resolvedExternalId = resolveRestoredExternalIdOnHost(
                            StringUtils.defaultIfBlank(backup.getDetail(DETAIL_NETBACKUP_BACKUP_ID), externalId),
                            restoreHostName, Collections.singletonList(backup), restorePathDiscoveryWindowSeconds, true,
                            allowedRestorePaths);
                    return new RestoreResolution(findBackupByExternalId(resolvedExternalId), externalId, restoreHostName);
                }
                return new RestoreResolution(backup, externalId, null);
            } catch (CloudRuntimeException e) {
                if (StringUtils.isBlank(backupId) && !looksLikeBackupId(externalId)) {
                    throw e;
                }
                logger.info("Falling back to NetBackup backup ID based restore resolution for input [{}]: {}",
                        externalId, e.getMessage());
            }
        }

        final String resolvedBackupId = StringUtils.defaultIfBlank(backupId, externalId);
        if (StringUtils.isBlank(resolvedBackupId)) {
            throw new CloudRuntimeException("NetBackup backup ID could not be resolved from the request.");
        }

        final List<BackupVO> candidates = findBackupsByBackupId(resolvedBackupId);
        logger.info("NetBackup backupId [{}] matched [{}] candidate backup rows: {}",
                resolvedBackupId, candidates.size(), summarizeCandidates(candidates));
        final String restoreHostName = resolveRestoreHostName(candidates, resolvedBackupId);
        final List<String> allowedRestorePaths = requireSingleRestorePathInVmRoot
                ? getExternalIds(candidates)
                : Collections.emptyList();
        final String resolvedExternalId = resolveRestoredExternalIdOnHost(
                resolvedBackupId, restoreHostName, candidates, restorePathDiscoveryWindowSeconds, requireSingleRestorePathInVmRoot,
                requireSingleCandidateRestorePath, allowedRestorePaths);
        logger.info("NetBackup backupId [{}] resolved to externalId [{}] on restoreHostName [{}].",
                resolvedBackupId, resolvedExternalId, restoreHostName);
        return new RestoreResolution(findBackupByExternalId(resolvedExternalId), resolvedBackupId, restoreHostName);
    }

    public void validatePreparedRestorePath(final BackupVO backup, final RestoreResolution resolution,
            final int restorePathDiscoveryWindowSeconds) {
        backupDao.loadDetails(backup);
        final String backupId = StringUtils.defaultIfBlank(backup.getDetail(DETAIL_NETBACKUP_BACKUP_ID), resolution.getRequestIdentifier());
        final String restoreHostName = StringUtils.defaultIfBlank(resolution.getPreparedRestoreHostName(),
                resolveRestoreHostName(Collections.singletonList(backup), backupId));
        resolveRestoredExternalIdOnHost(
                backupId, restoreHostName, Collections.singletonList(backup), restorePathDiscoveryWindowSeconds, true,
                getExternalIds(Collections.singletonList(backup)));
    }

    public RestoreSession claimSession(final VMInstanceVO vm, final String requestIdentifier, final BackupVO backup) {
        final AtomicReference<RestoreSession> claimedSession = new AtomicReference<>();
        RESTORE_SESSIONS.compute(vm.getId(), (vmId, existing) -> {
            if (existing == null || existing.isTerminal()) {
                final RestoreSession session = new RestoreSession(
                        requestIdentifier, backup.getId(), backup.getUuid(), backup.getExternalId(), vm.getInstanceName());
                claimedSession.set(session);
                return session;
            }
            return existing;
        });

        final RestoreSession session = claimedSession.get();
        if (session != null) {
            logger.info("NetBackup restore session claimed. vm=[{}], vmId=[{}], requestIdentifier=[{}], backupUuid=[{}], externalId=[{}]",
                    vm.getInstanceName(), vm.getId(), requestIdentifier, backup.getUuid(), backup.getExternalId());
        }
        return claimedSession.get();
    }

    public RestoreSession findSession(final Long vmId) {
        if (vmId == null) {
            return null;
        }
        final RestoreSession session = RESTORE_SESSIONS.get(vmId);
        if (session == null) {
            return null;
        }
        if (session.isTerminal()) {
            RESTORE_SESSIONS.remove(vmId, session);
            return null;
        }
        return session;
    }

    public void updateSessionPhase(final Long vmId, final String requestIdentifier, final RestorePhase phase) {
        if (vmId == null || phase == null) {
            return;
        }

        RESTORE_SESSIONS.computeIfPresent(vmId, (key, existing) -> {
            if (StringUtils.isBlank(requestIdentifier) || Objects.equals(existing.requestIdentifier, requestIdentifier)) {
                existing.setPhase(phase);
                logger.info("Updated NetBackup restore session phase for vmId=[{}] requestIdentifier=[{}] phase=[{}].",
                        vmId, requestIdentifier, phase);
            }
            return existing;
        });
    }

    public void completeSession(final Long vmId, final String requestIdentifier) {
        if (vmId == null) {
            return;
        }

        final AtomicBoolean completed = new AtomicBoolean(false);
        RESTORE_SESSIONS.computeIfPresent(vmId, (key, existing) -> {
            if (StringUtils.isBlank(requestIdentifier) || Objects.equals(existing.requestIdentifier, requestIdentifier)) {
                existing.setPhase(RestorePhase.COMPLETED);
                completed.set(true);
                return null;
            }
            return existing;
        });

        if (completed.get()) {
            logger.info("Completed NetBackup restore session for vmId=[{}] requestIdentifier=[{}].", vmId, requestIdentifier);
        }
    }

    public void failSession(final Long vmId, final String requestIdentifier, final String reason) {
        if (vmId == null) {
            return;
        }

        final AtomicBoolean failed = new AtomicBoolean(false);
        RESTORE_SESSIONS.computeIfPresent(vmId, (key, existing) -> {
            if (StringUtils.isBlank(requestIdentifier) || Objects.equals(existing.requestIdentifier, requestIdentifier)) {
                existing.setPhase(RestorePhase.FAILED);
                failed.set(true);
                return null;
            }
            return existing;
        });

        if (failed.get()) {
            logger.info("Failed NetBackup restore session for vmId=[{}] requestIdentifier=[{}] reason=[{}].",
                    vmId, requestIdentifier, reason);
        } else {
            logger.debug("Skipped failing NetBackup restore session for vmId=[{}] requestIdentifier=[{}] because the active session changed.",
                    vmId, requestIdentifier);
        }
    }

    public void persistRestoreState(final BackupVO backup, final VMInstanceVO vm, final String requestIdentifier,
            final RestorePhase phase) {
        if (backup == null || vm == null || phase == null) {
            return;
        }

        upsertBackupDetail(backup.getId(), DETAIL_NETBACKUP_RESTORE_VM_UUID, vm.getUuid());
        upsertBackupDetail(backup.getId(), DETAIL_NETBACKUP_RESTORE_PHASE, phase.name());
        upsertBackupDetail(backup.getId(), DETAIL_NETBACKUP_RESTORE_UPDATED_AT, String.valueOf(System.currentTimeMillis()));
        if (StringUtils.isNotBlank(requestIdentifier)) {
            upsertBackupDetail(backup.getId(), DETAIL_NETBACKUP_RESTORE_REQUEST_ID, requestIdentifier);
        }
    }

    public void persistRestoreContext(final BackupVO backup, final VMInstanceVO vm, final String requestIdentifier,
            final RestorePhase phase, final String restoreHostName, final String resolvedExternalId) {
        persistRestoreState(backup, vm, requestIdentifier, phase);
        if (backup == null) {
            return;
        }
        if (StringUtils.isNotBlank(restoreHostName)) {
            upsertBackupDetail(backup.getId(), DETAIL_NETBACKUP_RESTORE_HOST_NAME, restoreHostName);
        }
        if (StringUtils.isNotBlank(resolvedExternalId)) {
            upsertBackupDetail(backup.getId(), DETAIL_NETBACKUP_RESTORE_RESOLVED_EXTERNAL_ID, resolvedExternalId);
        }
    }

    public void persistRestoreFailure(final BackupVO backup, final VMInstanceVO vm, final String requestIdentifier,
            final String reason) {
        persistRestoreState(backup, vm, requestIdentifier, RestorePhase.FAILED);
        if (backup != null && StringUtils.isNotBlank(reason)) {
            upsertBackupDetail(backup.getId(), DETAIL_NETBACKUP_RESTORE_FAILURE_REASON, reason);
        }
    }

    public void clearRestoreState(final long backupId, final Long vmId) {
        upsertBackupDetail(backupId, DETAIL_NETBACKUP_RESTORE_VM_UUID, null);
        upsertBackupDetail(backupId, DETAIL_NETBACKUP_RESTORE_REQUEST_ID, null);
        upsertBackupDetail(backupId, DETAIL_NETBACKUP_RESTORE_PHASE, null);
        upsertBackupDetail(backupId, DETAIL_NETBACKUP_RESTORE_UPDATED_AT, null);
        upsertBackupDetail(backupId, DETAIL_NETBACKUP_RESTORE_ROOT_JOB_ID, null);
        upsertBackupDetail(backupId, DETAIL_NETBACKUP_RESTORE_CHAIN_JOB_ID, null);
        upsertBackupDetail(backupId, DETAIL_NETBACKUP_RESTORE_HOST_NAME, null);
        upsertBackupDetail(backupId, DETAIL_NETBACKUP_RESTORE_RESOLVED_EXTERNAL_ID, null);
        upsertBackupDetail(backupId, DETAIL_NETBACKUP_RESTORE_FAILURE_REASON, null);
        if (vmId != null) {
            RESTORE_SESSIONS.remove(vmId);
            RESTORE_GUARDS.remove(vmId);
        }
    }

    public void persistRootRestoreJobId(final BackupVO backup, final String rootJobId) {
        if (backup == null || StringUtils.isBlank(rootJobId)) {
            return;
        }
        upsertBackupDetail(backup.getId(), DETAIL_NETBACKUP_RESTORE_ROOT_JOB_ID, rootJobId);
    }

    public void persistChainRestoreJobId(final BackupVO backup, final String chainJobId) {
        if (backup == null || StringUtils.isBlank(chainJobId)) {
            return;
        }
        upsertBackupDetail(backup.getId(), DETAIL_NETBACKUP_RESTORE_CHAIN_JOB_ID, chainJobId);
    }

    public BackupVO findRestoreBackupByJobId(final String jobId) {
        if (StringUtils.isBlank(jobId)) {
            return null;
        }

        final BackupVO chainJobBackup = findBackupByRestoreJobDetail(DETAIL_NETBACKUP_RESTORE_CHAIN_JOB_ID, jobId);
        if (chainJobBackup != null) {
            return chainJobBackup;
        }
        return findBackupByRestoreJobDetail(DETAIL_NETBACKUP_RESTORE_ROOT_JOB_ID, jobId);
    }

    private BackupVO findBackupByRestoreJobDetail(final String key, final String jobId) {
        return backupDetailsDao.findDetails(key, jobId, false).stream()
                .map(BackupDetailVO::getResourceId)
                .map(backupDao::findByIdIncludingRemoved)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    public BackupVO findActiveRestoreBackupForVm(final VMInstanceVO vm) {
        if (vm == null || StringUtils.isBlank(vm.getUuid())) {
            return null;
        }

        final List<BackupDetailVO> restoreMarkers = backupDetailsDao.findDetails(DETAIL_NETBACKUP_RESTORE_VM_UUID, vm.getUuid(), false);
        if (CollectionUtils.isEmpty(restoreMarkers)) {
            return null;
        }

        for (final BackupDetailVO marker : restoreMarkers) {
            final BackupVO backup = backupDao.findByIdIncludingRemoved(marker.getResourceId());
            if (backup == null) {
                continue;
            }
            backupDao.loadDetails(backup);
            final String phase = backup.getDetail(DETAIL_NETBACKUP_RESTORE_PHASE);
            if (isActiveRestorePhase(phase)) {
                return backup;
            }
        }
        return null;
    }

    public boolean isActiveRestorePhase(final String phase) {
        return StringUtils.isNotBlank(phase)
                && !StringUtils.equalsIgnoreCase(RestorePhase.COMPLETED.name(), phase)
                && !StringUtils.equalsIgnoreCase(RestorePhase.FAILED.name(), phase);
    }

    public String getRestorePhase(final BackupVO backup) {
        return backup != null ? backup.getDetail(DETAIL_NETBACKUP_RESTORE_PHASE) : null;
    }

    public String getRestoreRequestId(final BackupVO backup) {
        return backup != null ? backup.getDetail(DETAIL_NETBACKUP_RESTORE_REQUEST_ID) : null;
    }

    public String getRestoreRootJobId(final BackupVO backup) {
        return backup != null ? backup.getDetail(DETAIL_NETBACKUP_RESTORE_ROOT_JOB_ID) : null;
    }

    public String getRestoreChainJobId(final BackupVO backup) {
        return backup != null ? backup.getDetail(DETAIL_NETBACKUP_RESTORE_CHAIN_JOB_ID) : null;
    }

    public String getRestoreHostName(final BackupVO backup) {
        return backup != null ? backup.getDetail(DETAIL_NETBACKUP_RESTORE_HOST_NAME) : null;
    }

    public String getRestoreFailureReason(final BackupVO backup) {
        return backup != null ? backup.getDetail(DETAIL_NETBACKUP_RESTORE_FAILURE_REASON) : null;
    }

    public void reconcileActiveRestoreStates(final Long zoneId) {
        final List<BackupVO> backups = backupDao.listByZone(zoneId);
        if (CollectionUtils.isEmpty(backups)) {
            return;
        }
        int activeRestoreCount = 0;
        for (final BackupVO backup : backups) {
            try {
                final BackupOffering offering = backupOfferingDao.findByIdIncludingRemoved(backup.getBackupOfferingId());
                if (offering == null || !BackupProviderNameUtils.isNetBackupFamily(offering.getProvider())) {
                    continue;
                }
                backupDao.loadDetails(backup);
                final String phase = getRestorePhase(backup);
                if (!isActiveRestorePhase(phase)) {
                    continue;
                }
                activeRestoreCount++;
                final String updatedAt = backup.getDetail(DETAIL_NETBACKUP_RESTORE_UPDATED_AT);
                final long updatedAtMs = parseLong(updatedAt, 0L);
                final long ageMs = updatedAtMs > 0L ? System.currentTimeMillis() - updatedAtMs : Long.MAX_VALUE;
                logger.info("Reconciling active NetBackup restore state. zoneId=[{}], backupUuid=[{}], vmId=[{}], "
                                + "phase=[{}], requestId=[{}], restoreHost=[{}], resolvedExternalId=[{}], ageMs=[{}]",
                        zoneId, backup.getUuid(), backup.getVmId(), phase, getRestoreRequestId(backup),
                        getRestoreHostName(backup), backup.getDetail(DETAIL_NETBACKUP_RESTORE_RESOLVED_EXTERNAL_ID), ageMs);
                if (ageMs >= STALE_RESTORE_STATE_THRESHOLD_MS) {
                    markStaleRestoreFailed(backup, phase, ageMs);
                }
            } catch (Exception e) {
                logger.warn("Failed to reconcile NetBackup restore state for backup [{}] in zone [{}]: {}",
                        backup.getUuid(), zoneId, e.getMessage(), e);
            }
        }
        if (activeRestoreCount > 0) {
            logger.info("Checked [{}] active NetBackup restore states in zone [{}].", activeRestoreCount, zoneId);
        }
    }

    private void markStaleRestoreFailed(final BackupVO backup, final String phase, final long ageMs) {
        final VMInstanceVO markerVm = getRestoreMarkerVm(backup, null);
        final String requestId = getRestoreRequestId(backup);
        final String reason = String.format("NetBackup restore state remained active in phase [%s] for [%d] ms. "
                + "The Mold async job may have been interrupted; manual verification is required before retry.", phase, ageMs);
        persistRestoreFailure(backup, markerVm, requestId, reason);
        failSession(markerVm != null ? markerVm.getId() : null, requestId, reason);
        logger.warn("Marked stale NetBackup restore state as FAILED. backupUuid=[{}], vmId=[{}], phase=[{}], requestId=[{}], ageMs=[{}], reason=[{}]",
                backup.getUuid(), backup.getVmId(), phase, requestId, ageMs, reason);
    }

    private long parseLong(final String value, final long defaultValue) {
        if (StringUtils.isBlank(value)) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public String getMoldRestoreRequestIdentifier(final Backup backup) {
        if (backup == null) {
            return null;
        }
        return StringUtils.defaultIfBlank(backup.getExternalId(), backup.getUuid());
    }

    public VMInstanceVO getRestoreMarkerVm(final Backup backup, final VMInstanceVO fallbackVm) {
        if (backup == null || backup.getVmId() == null) {
            return fallbackVm;
        }
        final VMInstanceVO sourceVm = vmInstanceDao.findByIdIncludingRemoved(backup.getVmId());
        return sourceVm != null ? sourceVm : fallbackVm;
    }

    public void blockIfRestoreAlreadyActive(final VMInstanceVO vm, final String requestIdentifier) {
        if (vm == null) {
            return;
        }
        final BackupVO activeRestoreBackup = findActiveRestoreBackupForVm(vm);
        if (activeRestoreBackup == null) {
            return;
        }
        final String activePhase = getRestorePhase(activeRestoreBackup);
        final String activeRequestId = getRestoreRequestId(activeRestoreBackup);
        throw new CloudRuntimeException(String.format(
                "NetBackup restore is already active for VM [%s] on backup [%s] in phase [%s] for request [%s]. Requested restore [%s] is blocked.",
                vm.getInstanceName(), activeRestoreBackup.getUuid(), activePhase, activeRequestId, requestIdentifier));
    }

    public List<BackupVO> getRestoreChain(final BackupVO backup) {
        final LinkedHashMap<String, BackupVO> chainByUuid = new LinkedHashMap<>();
        BackupVO current = backup;
        final Set<String> visitedBackupUuids = new HashSet<>();

        while (current != null && visitedBackupUuids.add(current.getUuid())) {
            backupDao.loadDetails(current);
            chainByUuid.put(current.getUuid(), current);

            final String parentBackupUuid = getParentBackupUuid(current);
            if (StringUtils.isBlank(parentBackupUuid)) {
                break;
            }

            final BackupVO parentBackup = backupDao.findByUuid(parentBackupUuid);
            if (parentBackup == null) {
                throw new CloudRuntimeException(String.format(
                        "Failed to resolve parent NetBackup backup [%s] for backup [%s].",
                        parentBackupUuid, current.getUuid()));
            }
            current = parentBackup;
        }

        final List<BackupVO> restoreChain = new ArrayList<>(chainByUuid.values());
        Collections.reverse(restoreChain);
        return restoreChain;
    }

    private void upsertBackupDetail(final long backupId, final String key, final String value) {
        if (StringUtils.isBlank(key)) {
            return;
        }

        backupDetailsDao.removeDetail(backupId, key);
        if (StringUtils.isNotBlank(value)) {
            backupDetailsDao.addDetail(backupId, key, value, false);
        }
    }

    private BackupVO findBackupByExternalId(final String externalId) {
        final Backup backup = backupDao.findByExternalId(null, externalId);
        if (!(backup instanceof BackupVO)) {
            throw new CloudRuntimeException(String.format(
                    "Unable to find NetBackup backup row for external ID [%s].", externalId));
        }

        final BackupVO backupVO = (BackupVO) backup;
        final BackupOffering offering = backupOfferingDao.findByIdIncludingRemoved(backupVO.getBackupOfferingId());
        if (offering == null || !BackupProviderNameUtils.isNetBackupFamily(offering.getProvider())) {
            throw new CloudRuntimeException(String.format(
                    "Backup external ID [%s] is not associated with a NetBackup backup row.", externalId));
        }
        return backupVO;
    }

    private List<BackupVO> findBackupsByBackupId(final String backupId) {
        final List<BackupVO> backups = backupDetailsDao.findDetails(DETAIL_NETBACKUP_BACKUP_ID, backupId, false).stream()
                .map(BackupDetailVO::getResourceId)
                .map(backupDao::findByIdIncludingRemoved)
                .filter(BackupVO.class::isInstance)
                .map(BackupVO.class::cast)
                .filter(backup -> {
                    final BackupOffering offering = backupOfferingDao.findByIdIncludingRemoved(backup.getBackupOfferingId());
                    return offering != null && BackupProviderNameUtils.isNetBackupFamily(offering.getProvider());
                })
                .sorted(Comparator.comparing(
                                BackupVO::getDate, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(backup -> backup.getId(), Comparator.reverseOrder()))
                .collect(Collectors.toList());
        if (CollectionUtils.isEmpty(backups)) {
            throw new CloudRuntimeException(String.format(
                    "Unable to find NetBackup backup rows for backup ID [%s].", backupId));
        }
        backups.forEach(backupDao::loadDetails);
        logger.debug("Loaded NetBackup backup rows for backupId [{}]: {}", backupId, summarizeCandidates(backups));
        return backups;
    }

    private String resolveRestoreHostName(final List<BackupVO> backups, final String backupId) {
        for (final BackupVO backup : backups) {
            final String hostName = backup.getDetail(DETAIL_NETBACKUP_POLICY_NAME);
            if (StringUtils.isNotBlank(hostName)) {
                return hostName;
            }
        }
        final int index = backupId.lastIndexOf('_');
        if (index > 0) {
            return backupId.substring(0, index);
        }
        throw new CloudRuntimeException(String.format(
                "Unable to determine restore host for NetBackup backup ID [%s].", backupId));
    }

    private String resolveRestoredExternalIdOnHost(final String backupId,
            final String restoreHostName, final List<BackupVO> backups, final int restorePathDiscoveryWindowSeconds,
            final boolean requireSingleRestorePathInVmRoot, final List<String> allowedRestorePathsInVmRoot) {
        return resolveRestoredExternalIdOnHost(backupId, restoreHostName, backups, restorePathDiscoveryWindowSeconds,
                requireSingleRestorePathInVmRoot, false, allowedRestorePathsInVmRoot);
    }

    private String resolveRestoredExternalIdOnHost(final String backupId,
            final String restoreHostName, final List<BackupVO> backups, final int restorePathDiscoveryWindowSeconds,
            final boolean requireSingleRestorePathInVmRoot, final boolean requireSingleCandidateRestorePath,
            final List<String> allowedRestorePathsInVmRoot) {
        final HostVO host = findRestoreHost(restoreHostName);
        if (host == null) {
            throw new CloudRuntimeException(String.format(
                    "Unable to find restore host [%s] for NetBackup backup ID [%s].", restoreHostName, backupId));
        }

        final List<String> restoreCandidatePaths = backups.stream()
                .map(BackupVO::getExternalId)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .collect(Collectors.toList());
        if (CollectionUtils.isEmpty(restoreCandidatePaths)) {
            throw new CloudRuntimeException(String.format(
                    "No candidate external IDs are available for NetBackup backup ID [%s].", backupId));
        }

        logger.info("Resolving NetBackup restored externalId on host [{}] for backupId [{}] with candidate paths {}",
                restoreHostName, backupId, restoreCandidatePaths);
        final AblestackNetBackupResolveRestorePathCommand command =
                new AblestackNetBackupResolveRestorePathCommand(
                        backupId, restoreCandidatePaths, null, restorePathDiscoveryWindowSeconds,
                        requireSingleRestorePathInVmRoot, requireSingleCandidateRestorePath, allowedRestorePathsInVmRoot);
        try {
            final BackupAnswer answer = (BackupAnswer) agentMgr.send(host.getId(), command);
            if (answer == null || !answer.getResult() || StringUtils.isBlank(answer.getDetails())) {
                throw new CloudRuntimeException(
                        answer != null ? answer.getDetails() : String.format(
                                "No response from restore host [%s] while resolving NetBackup backup ID [%s].",
                                restoreHostName, backupId));
            }
            logger.info("NetBackup restore host [{}] selected restored externalId [{}] for backupId [{}].",
                    restoreHostName, answer.getDetails(), backupId);
            return answer.getDetails();
        } catch (Exception e) {
            throw new CloudRuntimeException(String.format(
                    "Failed to resolve restored path on host [%s] for NetBackup backup ID [%s]: %s",
                    restoreHostName, backupId, e.getMessage()), e);
        }
    }

    private String summarizeCandidates(final List<BackupVO> candidates) {
        if (CollectionUtils.isEmpty(candidates)) {
            return "[]";
        }
        return candidates.stream()
                .map(candidate -> String.format("{uuid=%s, externalId=%s, type=%s, date=%s}",
                        candidate.getUuid(), candidate.getExternalId(), candidate.getType(), candidate.getDate()))
                .collect(Collectors.joining(", ", "[", "]"));
    }

    private HostVO findRestoreHost(final String restoreHostName) {
        HostVO host = hostDao.findByName(restoreHostName);
        if (host != null) {
            return host;
        }
        return hostDao.findByIp(restoreHostName);
    }

    private List<String> getExternalIds(final List<BackupVO> backups) {
        if (CollectionUtils.isEmpty(backups)) {
            return Collections.emptyList();
        }
        final LinkedHashSet<String> externalIds = new LinkedHashSet<>();
        for (final BackupVO backup : backups) {
            if (backup == null || StringUtils.isBlank(backup.getExternalId())) {
                continue;
            }
            externalIds.add(backup.getExternalId());
        }
        return new ArrayList<>(externalIds);
    }

    private String getParentBackupUuid(final BackupVO backup) {
        backupDao.loadDetails(backup);
        Map<String, String> details = backup.getDetails();
        if (details == null || details.isEmpty()) {
            return null;
        }

        return details.entrySet().stream()
                .filter(entry -> StringUtils.endsWith(entry.getKey(), ".parent.backup.uuid"))
                .map(Map.Entry::getValue)
                .filter(StringUtils::isNotBlank)
                .findFirst()
                .orElse(null);
    }

    private boolean looksLikeBackupId(final String value) {
        return StringUtils.isNotBlank(value) && value.matches("^[^/\\\\]+_\\d+$");
    }

    private Backup.Status resolveStatus(final String status) {
        if (StringUtils.isBlank(status)) {
            return Backup.Status.BackedUp;
        }
        try {
            return Backup.Status.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw new CloudRuntimeException(String.format("Invalid NetBackup final status [%s].", status), e);
        }
    }
}
