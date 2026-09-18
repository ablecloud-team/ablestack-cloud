// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
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

import com.cloud.vm.VirtualMachine;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public final class AblestackBackupFrameworkUtils {
    public static final long DEFAULT_STALE_BACKING_UP_THRESHOLD_MS = TimeUnit.DAYS.toMillis(1);
    public static final String RESOURCE_COUNT_PENDING_DETAIL = "backup.resource.count.pending";
    public static final String BACKUP_IN_PROGRESS_MARKER = ".backup.inprogress";
    public static final String BACKUP_COMPLETE_MARKER = ".backup.complete";
    public static final String STAGING_IN_PROGRESS_MARKER = ".staging.inprogress";
    public static final String STAGING_COMPLETE_MARKER = ".staging.complete";
    public static final String ASYNC_BACKUP_JOB_ROOT = "/var/lib/cloudstack/ablestack-backup/jobs";
    public static final String TRACE_MARKER = "[ABLESTACK_BACKUP_TRACE]";
    public static final String OPERATION_BACKUP = "BACKUP";
    public static final String OPERATION_RESTORE = "RESTORE";
    public static final String CAPABILITY_CANCEL = "cancel";
    public static final String CAPABILITY_EVENTS = "events";
    public static final String CAPABILITY_LOG = "log";
    public static final String CAPABILITY_PROGRESS = "progress";
    public static final String CAPABILITY_RESTORE_PROGRESS = "restore-progress";
    public static final String CAPABILITY_LIVE_BANDWIDTH = "live-bandwidth";
    public static final String RESTORE_JOB_ID_DETAIL = "ablestack.restore.job.id";
    public static final String RESTORE_HOST_ID_DETAIL = "ablestack.restore.host.id";
    public static final String RESTORE_HOST_NAME_DETAIL = "ablestack.restore.host.name";
    public static final String RESTORE_JOB_STATE_DETAIL = "ablestack.restore.job.state";
    public static final String RESTORE_JOB_STEP_DETAIL = "ablestack.restore.job.step";
    public static final String RESTORE_JOB_PROGRESS_DETAIL = "ablestack.restore.job.progress";
    public static final String RESTORE_JOB_FAILURE_REASON_DETAIL = "ablestack.restore.job.failure.reason";
    public static final String RESTORE_JOB_CLEANUP_ID_DETAIL = "ablestack.restore.job.cleanup.id";
    public static final String RESTORE_JOB_TRACKED_AT_DETAIL = "ablestack.restore.job.tracked.at";
    public static final String RESTORE_ASYNC_JOB_ID_DETAIL = "ablestack.restore.async.job.id";
    public static final String RESTORE_OPERATION_TYPE_DETAIL = "ablestack.restore.operation.type";
    public static final String RESTORE_TARGET_VM_ID_DETAIL = "ablestack.restore.target.vm.id";
    public static final String RESTORE_SOURCE_VOLUME_UUID_DETAIL = "ablestack.restore.source.volume.uuid";
    public static final String RESTORE_TARGET_DATASTORE_UUID_DETAIL = "ablestack.restore.target.datastore.uuid";
    public static final String RESTORE_TARGET_VOLUME_UUID_DETAIL = "ablestack.restore.target.volume.uuid";
    public static final String RESTORE_EVENT_ID_DETAIL = "ablestack.restore.event.id";
    public static final String RESTORE_START_VM_DETAIL = "ablestack.restore.start.vm";

    public static final String RESTORE_OPERATION_VM = "VM_RESTORE";
    public static final String RESTORE_OPERATION_CREATE_INSTANCE = "CREATE_INSTANCE";
    public static final String RESTORE_OPERATION_VOLUME_ATTACH = "VOLUME_ATTACH";

    private AblestackBackupFrameworkUtils() {
    }

    public static boolean isBackingUp(final Backup backup) {
        return backup != null && Backup.Status.BackingUp.equals(backup.getStatus());
    }

    public static boolean isOlderThan(final Date date, final long ageMs) {
        return date != null && date.getTime() <= System.currentTimeMillis() - ageMs;
    }

    public static boolean isStaleBackingUp(final Backup backup) {
        return isStaleBackingUp(backup, DEFAULT_STALE_BACKING_UP_THRESHOLD_MS);
    }

    public static boolean isStaleBackingUp(final Backup backup, final long staleThresholdMs) {
        return isBackingUp(backup) && isOlderThan(backup.getDate(), staleThresholdMs);
    }

    public static int getEffectiveIncrementalLimit(final int defaultLimit, final List<Integer> scheduleMaxBackups) {
        int effectiveLimit = defaultLimit;
        if (scheduleMaxBackups == null) {
            return effectiveLimit;
        }
        for (Integer maxBackups : scheduleMaxBackups) {
            if (maxBackups != null && maxBackups > 0) {
                effectiveLimit = Math.min(effectiveLimit, maxBackups);
            }
        }
        return effectiveLimit;
    }

    public static <T extends Backup> int getBackupChainSize(final T latestBackup, final Map<String, ? extends T> backupsByUuid,
            final Function<T, String> parentBackupUuidResolver) {
        if (latestBackup == null) {
            return 0;
        }
        int chainSize = 1;
        T current = latestBackup;
        while (current != null) {
            final String parentBackupUuid = parentBackupUuidResolver.apply(current);
            if (parentBackupUuid == null) {
                break;
            }
            current = backupsByUuid.get(parentBackupUuid);
            if (current != null) {
                chainSize++;
            }
        }
        return chainSize;
    }

    public static boolean requiresRunningVmAttach(final VirtualMachine.State vmState) {
        return VirtualMachine.State.Running.equals(vmState);
    }

    public static boolean shouldExecuteRestoreOnSourceHost(final VirtualMachine.State vmState) {
        return !requiresRunningVmAttach(vmState);
    }

    public static BackupRestorePlan createRestorePlan(final boolean attachRequired, final boolean cleanupRequired) {
        final List<BackupRestoreStage> stages = new ArrayList<>();
        stages.add(BackupRestoreStage.PREPARE_SOURCE);
        stages.add(BackupRestoreStage.VALIDATE_CHAIN);
        stages.add(BackupRestoreStage.RESTORE_DATA);
        if (attachRequired) {
            stages.add(BackupRestoreStage.ATTACH_VOLUME);
        }
        if (cleanupRequired) {
            stages.add(BackupRestoreStage.CLEANUP_SOURCE);
        }
        return new BackupRestorePlan(stages);
    }

    public static boolean hasRestoreStage(final BackupRestorePlan restorePlan, final BackupRestoreStage stage) {
        return restorePlan == null || restorePlan.hasStage(stage);
    }

    public static List<String> sanitizeChainFiles(final List<String> chainFiles) {
        final LinkedHashSet<String> sanitized = new LinkedHashSet<>();
        if (chainFiles == null) {
            return new ArrayList<>();
        }
        for (final String chainFile : chainFiles) {
            if (StringUtils.isNotBlank(chainFile)) {
                sanitized.add(chainFile.trim());
            }
        }
        return new ArrayList<>(sanitized);
    }

    public static List<String> buildRestoreBackupFiles(final List<Backup.VolumeInfo> backedVolumes,
            final boolean legacyBackup, final Function<Backup.VolumeInfo, String> legacyFileNameResolver) {
        final List<String> backupFiles = new ArrayList<>();
        for (final Backup.VolumeInfo backedVolume : getSortedVolumeInfos(backedVolumes)) {
            backupFiles.add(legacyBackup ? legacyFileNameResolver.apply(backedVolume) : backedVolume.getPath());
        }
        return backupFiles;
    }

    public static List<String> buildRestoreBackupFileChains(final List<Backup.VolumeInfo> backedVolumes,
            final Function<Backup.VolumeInfo, List<String>> chainResolver) {
        final List<String> backupFileChains = new ArrayList<>();
        for (final Backup.VolumeInfo backedVolume : getSortedVolumeInfos(backedVolumes)) {
            backupFileChains.add(buildRestoreBackupFileChain(backedVolume, chainResolver));
        }
        return backupFileChains;
    }

    public static String buildRestoreBackupFileChain(final Backup.VolumeInfo backedVolume,
            final Function<Backup.VolumeInfo, List<String>> chainResolver) {
        return StringUtils.join(sanitizeChainFiles(chainResolver.apply(backedVolume)), ";");
    }

    public static List<BackupVolumeChainState> buildRestoreVolumeChainStates(final List<Backup.VolumeInfo> backedVolumes,
            final String backupEngine, final Function<Backup.VolumeInfo, List<String>> chainResolver) {
        final List<BackupVolumeChainState> volumeChainStates = new ArrayList<>();
        for (final Backup.VolumeInfo backedVolume : getSortedVolumeInfos(backedVolumes)) {
            volumeChainStates.add(new BackupVolumeChainState(backedVolume.getUuid(), backupEngine,
                    sanitizeChainFiles(chainResolver.apply(backedVolume))));
        }
        validateVolumeChainStates(volumeChainStates);
        return volumeChainStates;
    }

    public static List<Backup.VolumeInfo> getSortedVolumeInfos(final List<Backup.VolumeInfo> backedVolumes) {
        final List<Backup.VolumeInfo> sortedVolumes = new ArrayList<>();
        if (backedVolumes != null) {
            sortedVolumes.addAll(backedVolumes);
        }
        sortedVolumes.sort(Comparator.comparingLong(Backup.VolumeInfo::getDeviceId));
        return sortedVolumes;
    }

    public static void validateVolumeChainStates(final List<BackupVolumeChainState> volumeChainStates) {
        if (volumeChainStates == null || volumeChainStates.isEmpty()) {
            throw new IllegalArgumentException("Backup volume chain states cannot be empty");
        }
        for (final BackupVolumeChainState volumeChainState : volumeChainStates) {
            if (volumeChainState == null) {
                throw new IllegalArgumentException("Backup volume chain state cannot be null");
            }
            if (StringUtils.isBlank(volumeChainState.getVolumeUuid())) {
                throw new IllegalArgumentException("Backup volume chain state must include a volume UUID");
            }
            if (sanitizeChainFiles(volumeChainState.getChainFiles()).isEmpty()) {
                throw new IllegalArgumentException(String.format("Backup volume chain state for volume [%s] must include at least one chain file",
                        volumeChainState.getVolumeUuid()));
            }
        }
    }

    public static boolean hasUsableVolumeChainStates(final List<BackupVolumeChainState> volumeChainStates) {
        try {
            validateVolumeChainStates(volumeChainStates);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static String getAsyncBackupJobLogPath(final String backupJobId) {
        return getAsyncOperationJobLogPath(backupJobId);
    }

    public static String getAsyncRestoreJobLogPath(final String restoreJobId) {
        return getAsyncOperationJobLogPath(restoreJobId);
    }

    public static String getAsyncOperationJobLogPath(final String jobId) {
        return ASYNC_BACKUP_JOB_ROOT + "/" + sanitizeAsyncBackupJobId(jobId) + "/job.log";
    }

    public static String buildTracePrefix(final String provider, final String operation) {
        final List<String> parts = new ArrayList<>();
        parts.add(TRACE_MARKER);
        if (StringUtils.isNotBlank(provider)) {
            parts.add("provider=[" + provider.toLowerCase() + "]");
        }
        if (StringUtils.isNotBlank(operation)) {
            parts.add("operation=[" + operation.toUpperCase() + "]");
        }
        return StringUtils.join(parts, " ");
    }

    public static String resolveJobOperation(final String backupType) {
        return OPERATION_RESTORE.equalsIgnoreCase(backupType) ? OPERATION_RESTORE : OPERATION_BACKUP;
    }

    public static String createRestoreJobId(final String provider, final String backupUuid, final String vmName, final String volumeUuid) {
        final List<String> parts = new ArrayList<>();
        parts.add("restore");
        if (StringUtils.isNotBlank(provider)) {
            parts.add(provider);
        }
        if (StringUtils.isNotBlank(backupUuid)) {
            parts.add(backupUuid);
        }
        if (StringUtils.isNotBlank(vmName)) {
            parts.add(vmName);
        }
        if (StringUtils.isNotBlank(volumeUuid)) {
            parts.add(volumeUuid);
        }
        return sanitizeAsyncBackupJobId(StringUtils.join(parts, "-"));
    }

    public static String sanitizeAsyncBackupJobId(final String backupJobId) {
        return backupJobId == null ? "" : backupJobId.replaceAll("[^A-Za-z0-9_.-]", "_");
    }
}
