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
// "AS IS" BASIS, WITHOUT WARRANTIES OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.cloudstack.backup;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.inject.Inject;

import com.cloud.configuration.Resource;
import com.cloud.user.ResourceLimitService;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.Gson;
import org.apache.cloudstack.backup.dao.BackupDao;
import org.apache.cloudstack.backup.dao.BackupDetailsDao;
import org.apache.cloudstack.backup.dao.BackupOfferingDao;
import org.apache.cloudstack.framework.jobs.AsyncJobManager;
import org.apache.commons.lang3.StringUtils;

/** Scope is persisted before deleting anything; every physical completion precedes DB removal. */
public class BackupDeletionServiceImpl extends ManagerBase implements BackupDeletionService {
    @Inject private BackupDao backupDao;
    @Inject private BackupDetailsDao detailsDao;
    @Inject private BackupOfferingDao offeringDao;
    @Inject private ThirdPartyBackupVolumeService volumeService;
    @Inject private ResourceLimitService resourceLimitService;
    @Inject private AsyncJobManager asyncJobManager;
    @Inject private AblestackRestoreInstanceCleanup instanceCleanup;

    @Override
    public Plan plan(Backup backup, boolean forced) {
        backupDao.loadDetails((BackupVO) backup);
        Plan plan = buildPlan(backup, forced, candidates(backup));
        plan.provider = provider(backup);
        return plan;
    }

    private String provider(Backup backup) {
        BackupOffering offering = offeringDao.findByIdIncludingRemoved(backup.getBackupOfferingId());
        if (offering == null || !BackupDeletionService.supports(offering.getProvider())) {
            throw new CloudRuntimeException("Deletion planning is available only for ABLESTACK backup providers");
        }
        return offering.getProvider();
    }

    private List<Backup> candidates(Backup root) {
        String provider = provider(root);
        List<Backup> result = new ArrayList<>();
        for (Backup backup : backupDao.listByVmId(null, root.getVmId())) {
            BackupOffering offering = offeringDao.findByIdIncludingRemoved(backup.getBackupOfferingId());
            if (offering != null && provider.equals(offering.getProvider())) {
                backupDao.loadDetails((BackupVO) backup);
                if (ThirdPartyBackupManifest.VOLUME_MODE.equals(backup.getDetail(ThirdPartyBackupManifest.MODE_KEY))
                        && !provider.equals(ThirdPartyBackupManifest.fromJson(backup.getDetail(ThirdPartyBackupManifest.DETAIL_KEY)).getProvider())) {
                    throw new CloudRuntimeException("Backup manifest provider ownership is unconfirmed: " + backup.getUuid());
                }
                result.add(backup);
            }
        }
        return result;
    }

    static Set<String> references(Backup backup) {
        Set<String> result = new LinkedHashSet<>();
        if (ThirdPartyBackupManifest.VOLUME_MODE.equals(backup.getDetail(ThirdPartyBackupManifest.MODE_KEY))) {
            ThirdPartyBackupManifest manifest = ThirdPartyBackupManifest.fromJson(backup.getDetail(ThirdPartyBackupManifest.DETAIL_KEY));
            if (!backup.getUuid().equals(manifest.getBackupUuid())) {
                throw new CloudRuntimeException("Backup manifest ownership is unconfirmed: " + backup.getUuid());
            }
            manifest.getRequiredArtifacts().stream().map(artifact -> artifact.backupUuid)
                    .filter(StringUtils::isNotBlank).filter(uuid -> !backup.getUuid().equals(uuid)).forEach(result::add);
        }
        for (String key : List.of("nas.parent.backup.uuid", "commvault.parent.backup.uuid", "netbackup.parent.backup.uuid", "ablestack.veeam.parent.backup.uuid", "veeam.parent.backup.uuid")) {
            if (StringUtils.isNotBlank(backup.getDetail(key))) { result.add(backup.getDetail(key)); }
        }
        if (result.contains(backup.getUuid())) { throw new CloudRuntimeException("Backup dependency cycle prevents safe deletion"); }
        return result;
    }

    static Plan buildPlan(Backup root, boolean forced, List<Backup> candidates) {
        Map<String, Backup> points = candidates.stream().collect(Collectors.toMap(Backup::getUuid, b -> b, (a, b) -> a, LinkedHashMap::new));
        points.putIfAbsent(root.getUuid(), root);
        Map<String, Set<String>> refs = new LinkedHashMap<>();
        points.forEach((uuid, backup) -> refs.put(uuid, references(backup)));
        Set<String> affected = new LinkedHashSet<>();
        affected.add(root.getUuid());
        boolean changed;
        do {
            changed = false;
            for (String uuid : points.keySet()) {
                if (!affected.contains(uuid) && refs.get(uuid).stream().anyMatch(affected::contains)) {
                    affected.add(uuid);
                    changed = true;
                }
            }
        } while (changed);
        // A leaf never has a reference from another remaining point. This also detects cycles.
        List<Backup> ordered = new ArrayList<>();
        Set<String> remaining = new LinkedHashSet<>(affected);
        while (!remaining.isEmpty()) {
            String leaf = remaining.stream().filter(uuid -> remaining.stream().noneMatch(other -> !other.equals(uuid) && refs.get(other).contains(uuid)))
                    .max(Comparator.comparingLong(uuid -> points.get(uuid).getId())).orElseThrow(
                            () -> new CloudRuntimeException("Backup dependency cycle prevents safe deletion"));
            ordered.add(points.get(leaf));
            remaining.remove(leaf);
        }
        Plan plan = new Plan();
        plan.rootId = root.getId();
        plan.rootUuid = root.getUuid();
        plan.forced = forced;
        plan.backups = (forced ? ordered : List.of(root)).stream().map(BackupDeletionServiceImpl::entry).collect(Collectors.toList());
        plan.dependents = ordered.stream().filter(b -> b.getId() != root.getId()).map(BackupDeletionServiceImpl::entry).collect(Collectors.toList());
        plan.state = !forced && !plan.dependents.isEmpty() ? "WAITING_DEPENDENCIES" : "READY";
        plan.reason = "WAITING_DEPENDENCIES".equals(plan.state) ? "Retained until dependent recovery points are deleted" : null;
        plan.fingerprint = fingerprint(root.getUuid(), forced, affected);
        return plan;
    }

    private static Entry entry(Backup backup) {
        Entry entry = new Entry();
        entry.id = backup.getId();
        entry.uuid = backup.getUuid();
        entry.name = backup instanceof BackupVO ? ((BackupVO) backup).getName() : backup.getUuid();
        entry.type = backup.getType() == null ? null : backup.getType().toString();
        return entry;
    }

    private static String fingerprint(String root, boolean forced, Set<String> affected) {
        try {
            String scope = root + ":" + forced + ":" + affected.stream().sorted().collect(Collectors.joining(","));
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(scope.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    @Override
    public boolean delete(Backup backup, boolean forced, String expectedPlan, Cleanup cleanup) {
        try (ThirdPartyBackupVolumeService.Lease lease = volumeService.acquireLifecycle(backup.getVmId())) {
            BackupVO root = backupDao.findById(backup.getId());
            if (root == null || root.getRemoved() != null) { return true; }
            backupDao.loadDetails(root);
            List<Backup> candidates = candidates(root);
            Plan proposed = buildPlan(root, forced, candidates);
            proposed.provider = provider(root);
            if (StringUtils.isNotBlank(expectedPlan) && !expectedPlan.equals(proposed.fingerprint)) {
                throw new CloudRuntimeException("Backup deletion scope changed; refresh the deletion preview and try again");
            }
            List<Backup> allBackups = backupDao.listByVmId(null, root.getVmId());
            allBackups.forEach(point -> backupDao.loadDetails((BackupVO) point));
            assertIdle(allBackups);
            Plan saved = new Gson().fromJson(root.getDetail(REQUEST_KEY), Plan.class);
            if (saved != null && saved.forced && !forced) {
                throw new CloudRuntimeException("Forced deletion is already in progress; retry with forced enabled");
            }
            if (saved != null && saved.forced) {
                Set<String> accepted = saved.backups.stream().map(e -> e.uuid).collect(Collectors.toSet());
                if (proposed.backups.stream().anyMatch(e -> !accepted.contains(e.uuid))) {
                    throw new CloudRuntimeException("New dependent backups are outside the accepted deletion scope");
                }
            }
            for (Entry member : proposed.backups) {
                Backup current = candidates.stream().filter(b -> b.getUuid().equals(member.uuid)).findFirst().orElse(root);
                String owner = current.getDetail(OWNER_KEY);
                if (StringUtils.isNotBlank(owner) && !owner.equals(root.getUuid())) {
                    BackupVO ownerBackup = backupDao.findByUuid(owner);
                    if (ownerBackup != null) {
                        backupDao.loadDetails(ownerBackup);
                        Plan ownerPlan = new Gson().fromJson(ownerBackup.getDetail(REQUEST_KEY), Plan.class);
                        if (ownerPlan != null && ownerPlan.forced) {
                            throw new CloudRuntimeException("Another forced deletion already owns this backup: " + owner);
                        }
                    }
                }
            }
            Transaction.execute((TransactionCallback<Boolean>) status -> {
                save(root.getId(), REQUEST_KEY, new Gson().toJson(proposed));
                for (Entry member : proposed.backups) { save(member.id, OWNER_KEY, root.getUuid()); }
                return true;
            });
            execute(root, proposed, cleanup);
            sweepDeferred(backup, cleanup);
            return true;
        }
    }

    private void assertIdle(List<Backup> candidates) {
        for (Backup backup : candidates) {
            String restore = backup.getDetail(AblestackBackupFrameworkUtils.RESTORE_JOB_STATE_DETAIL);
            boolean canceledAndCleaned = backup.getStatus() == Backup.Status.Canceled
                    && ThirdPartyBackupManifest.VOLUME_MODE.equals(backup.getDetail(ThirdPartyBackupManifest.MODE_KEY))
                    && !ThirdPartyBackupVolumeService.needsBackupCleanup(backup);
            ThirdPartyBackupStart.Operation start = StringUtils.isBlank(backup.getDetail(ThirdPartyBackupStart.DETAIL_KEY)) ? null
                    : new Gson().fromJson(backup.getDetail(ThirdPartyBackupStart.DETAIL_KEY), ThirdPartyBackupStart.Operation.class);
            String termination = backup.getDetail(AblestackBackupFrameworkUtils.BACKUP_CLEANUP_STATE_DETAIL);
            com.google.gson.JsonObject creation = StringUtils.isBlank(backup.getDetail(AblestackBackupFrameworkUtils.RESTORE_CREATE_INSTANCE_PROGRESS_DETAIL)) ? null
                    : new Gson().fromJson(backup.getDetail(AblestackBackupFrameworkUtils.RESTORE_CREATE_INSTANCE_PROGRESS_DETAIL), com.google.gson.JsonObject.class);
            String creationState = creation != null && creation.has("state") ? creation.get("state").getAsString() : "";
            if (Backup.Status.BackingUp.equals(backup.getStatus()) || Backup.Status.Restoring.equals(backup.getStatus())
                    || Set.of("STARTING", "RUNNING").contains(StringUtils.defaultString(restore))
                    || Set.of("WAITING", "ARTIFACTS_REMOVED").contains(StringUtils.defaultString(termination))
                    || (!canceledAndCleaned && ((Boolean.parseBoolean(backup.getDetail(AblestackBackupFrameworkUtils.BACKUP_CANCEL_REQUESTED_DETAIL))
                            && !"COMPLETED".equals(termination)) || (start != null && start.cancelRequestedAt > 0)))
                    || Set.of("STARTING", "RUNNING").contains(creationState)
                    || instanceCleanup.isPending(backup.getId())
                    || asyncJobManager.countPendingJobs(backup.getUuid(),
                            "org.apache.cloudstack.api.command.user.vm.CreateVMFromBackupCmd",
                            "org.apache.cloudstack.api.command.admin.vm.CreateVMFromBackupCmdByAdmin",
                            "org.apache.cloudstack.api.command.user.backup.RestoreBackupCmd",
                            "org.apache.cloudstack.api.command.user.backup.RestoreVolumeFromBackupAndAttachToVMCmd") > 0) {
                throw new CloudRuntimeException("A backup, restore or unconfirmed cleanup is still active for this VM");
            }
        }
    }

    private void execute(BackupVO root, Plan plan, Cleanup cleanup) {
        if ("WAITING_DEPENDENCIES".equals(plan.state)) { return; }
        plan.state = "RUNNING";
        save(root.getId(), REQUEST_KEY, new Gson().toJson(plan));
        try {
            for (Entry member : plan.backups) {
                BackupVO current = backupDao.findById(member.id);
                if (current == null || current.getRemoved() != null) { continue; }
                backupDao.loadDetails(current);
                if (!member.uuid.equals(current.getUuid())) { throw new CloudRuntimeException("Backup deletion ownership changed"); }
                if (!Boolean.parseBoolean(current.getDetail(CLEANED_KEY))) {
                    if (!cleanup.delete(current)) { throw new CloudRuntimeException("Backup artifact cleanup remains pending: " + member.uuid); }
                    save(current.getId(), CLEANED_KEY, "true");
                }
                removeCleaned(current.getId());
            }
        } catch (RuntimeException e) {
            plan.state = "WAITING";
            plan.reason = StringUtils.defaultString(e.getMessage());
            save(root.getId(), REQUEST_KEY, new Gson().toJson(plan));
            throw e;
        }
    }

    private void removeCleaned(long id) {
        Transaction.execute((TransactionCallback<Boolean>) status -> {
            BackupVO current = backupDao.lockRow(id, true);
            if (current == null || current.getRemoved() != null) { return true; }
            backupDao.loadDetails(current);
            if (!Boolean.parseBoolean(current.getDetail(CLEANED_KEY)) || !backupDao.remove(id)) {
                throw new CloudRuntimeException("Unable to remove backup records after artifact cleanup");
            }
            if (!Boolean.parseBoolean(current.getDetail(AblestackBackupFrameworkUtils.RESOURCE_COUNT_PENDING_DETAIL))) {
                resourceLimitService.decrementResourceCount(current.getAccountId(), Resource.ResourceType.backup);
                resourceLimitService.decrementResourceCount(current.getAccountId(), Resource.ResourceType.backup_storage,
                        current.getSize() == null ? 0L : current.getSize());
            }
            detailsDao.removeDetails(id);
            return true;
        });
    }

    private void sweepDeferred(Backup origin, Cleanup cleanup) {
        boolean removed;
        do {
            removed = false;
            List<Backup> candidates = candidates(origin);
            for (Backup candidate : candidates) {
                Plan saved = new Gson().fromJson(candidate.getDetail(REQUEST_KEY), Plan.class);
                if (saved == null || saved.forced) { continue; }
                Plan plan = buildPlan(candidate, false, candidates);
                if (!plan.dependents.isEmpty()) { continue; }
                plan.provider = provider(candidate);
                execute((BackupVO) candidate, plan, cleanup);
                removed = true;
                break;
            }
        } while (removed);
    }

    @Override
    public boolean resume(Backup backup, Cleanup cleanup) {
        BackupVO current = backupDao.findById(backup.getId());
        if (current == null || current.getRemoved() != null) { return true; }
        backupDao.loadDetails(current);
        if (!BackupDeletionService.isPending(current)) { return false; }
        String owner = StringUtils.defaultIfBlank(current.getDetail(OWNER_KEY), current.getUuid());
        BackupVO root = backupDao.findByUuid(owner);
        if (root == null || root.getRemoved() != null) { throw new CloudRuntimeException("Backup deletion owner is unavailable: " + owner); }
        backupDao.loadDetails(root);
        Plan saved = new Gson().fromJson(root.getDetail(REQUEST_KEY), Plan.class);
        if (saved == null) { throw new CloudRuntimeException("Backup deletion request is unavailable: " + owner); }
        try { delete(root, saved.forced, null, cleanup); }
        catch (RuntimeException e) { logger.debug("Backup deletion remains pending for {}: {}", owner, e.getMessage()); }
        return true;
    }

    private void save(long id, String key, String value) { detailsDao.addDetail(id, key, value, false); }
}
