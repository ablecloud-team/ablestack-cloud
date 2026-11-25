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
package org.apache.cloudstack.backup.netbackup;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.apache.cloudstack.backup.AblestackBackupFrameworkUtils;
import org.apache.cloudstack.backup.Backup;
import org.apache.cloudstack.backup.BackupVO;
import org.apache.cloudstack.backup.ThirdPartyBackupManifest;
import org.apache.cloudstack.backup.ThirdPartyBackupRestore;
import org.apache.cloudstack.backup.ThirdPartyBackupVolumeService;
import org.apache.cloudstack.backup.ThirdPartyExternalRestore;
import org.apache.cloudstack.backup.dao.BackupDao;
import org.apache.cloudstack.backup.dao.BackupDetailsDao;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;

import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.Gson;

/** Preserve native image retention separately from renewable, short-lived backup protection. */
public final class NetBackupChainRetention {
    static final int LEASE_SECONDS = 1800;
    static final int RENEW_BEFORE_SECONDS = 600;
    static final int RECHECK_SECONDS = 300;
    private static final long CHECK_INTERVAL_MS = 60000;
    private final AblestackNetBackupClient client;
    private final BackupDao backups;
    private final BackupDetailsDao details;
    private final Clock clock;

    public static final class Pending {
        public String target;
        public String previous;
        public String ticket;
    }

    public static final class CopyState {
        public String original;
        public String managed;
        public String releaseAfter;
        public boolean restorable;
        public boolean externallyChanged;
    }

    public static final class State {
        public int version = 2;
        public String backupUuid;
        public String state;
        public String preparationUntil;
        public String protectedUntil;
        public String reason;
        public boolean legacy;
        public boolean leaseActive;
        public boolean readersClosed;
        public long checkedAt;
        public long nextCheckAt;
        public String checkedManifest;
        public Map<String, Pending> pending = new LinkedHashMap<>();
        public Map<String, CopyState> copies = new LinkedHashMap<>();
    }

    /** Restore admission uses the same VM lock as expiration restoration. */
    public static final class Guard implements AutoCloseable {
        private final GlobalLock lock;
        private Guard(GlobalLock lock) { this.lock = lock; }
        @Override public void close() { lock.unlock(); lock.releaseRef(); }
    }

    public static Guard acquire(long vmId) {
        GlobalLock lock = GlobalLock.getInternLock("backup.netbackup.retention." + vmId);
        if (!lock.lock(1)) {
            lock.releaseRef();
            throw new CloudRuntimeException("NetBackup retention protection is being reconciled; retry shortly");
        }
        return new Guard(lock);
    }

    private static final class Reference {
        private final Backup owner;
        private final ThirdPartyBackupManifest.Artifact artifact;
        private final boolean metadata;
        private final String policy;

        private Reference(Backup owner, ThirdPartyBackupManifest.Artifact artifact, boolean metadata) {
            this.owner = owner;
            this.artifact = artifact;
            this.metadata = metadata;
            policy = "USER_BACKUP".equals(owner.getDetail("netbackup.transfer.mode")) ? owner.getDetail("netbackup.policy.name") : null;
            if ("USER_BACKUP".equals(owner.getDetail("netbackup.transfer.mode")) && StringUtils.isBlank(policy)) {
                throw new CloudRuntimeException("NetBackup retention source policy is missing");
            }
        }
    }

    private final class Point {
        private final Backup backup;
        private final State state;
        private final ThirdPartyBackupManifest manifest;
        private Point(Backup backup, State state) {
            this.backup = backup;
            this.state = state;
            manifest = StringUtils.isBlank(backup.getDetail(ThirdPartyBackupManifest.DETAIL_KEY)) ? null
                    : ThirdPartyBackupManifest.fromJson(backup.getDetail(ThirdPartyBackupManifest.DETAIL_KEY));
        }
    }

    private final class Context {
        private final Map<Long, Point> points = new LinkedHashMap<>();
        private final Map<String, Reference> references;
        private final Map<String, AblestackNetBackupClient.ImageRetention> images = new LinkedHashMap<>();
        private final Map<String, Instant> repairs = new LinkedHashMap<>();
        private final Point current;
        private boolean shortenAllowed = true;

        private Context(Backup backup, State state, ThirdPartyBackupManifest manifest, boolean finalizing) {
            current = new Point(backup, state);
            points.put(backup.getId(), current);
            for (Backup other : backups.listByVmId(backup.getZoneId(), backup.getVmId())) {
                if (other.getId() == backup.getId()) { continue; }
                if (!Objects.equals(other.getVmId(), backup.getVmId()) || other.getAccountId() != backup.getAccountId()
                        || !Objects.equals(other.getZoneId(), backup.getZoneId())) {
                    throw new CloudRuntimeException("NetBackup retention dependent ownership is unconfirmed");
                }
                if (other instanceof BackupVO) { backups.loadDetails((BackupVO) other); }
                Point point = new Point(other, readState(other));
                points.put(other.getId(), point);
            }
            references = references(backup, manifest, finalizing);
            for (Reference reference : references.values()) {
                if (!points.containsKey(reference.owner.getId())) {
                    points.put(reference.owner.getId(), new Point(reference.owner, readState(reference.owner)));
                }
                images.put(reference.artifact.externalId, read(reference));
            }
            // No automatic shortening while any restore or unconfirmed failed reader may still use these images.
            for (Point point : points.values()) {
                if (hasRestoreReaders(point.backup) || org.apache.cloudstack.backup.BackupDeletionService.isPending(point.backup)
                        || StringUtils.isNotBlank(point.backup.getDetail(ThirdPartyBackupManifest.DELETE_PROGRESS_KEY))
                        || (isFailed(point.backup) && !point.state.readersClosed
                            && ThirdPartyBackupVolumeService.needsBackupCleanup(point.backup))) {
                    shortenAllowed = false;
                }
            }
        }

        private CopyState copy(Reference reference, int number) {
            State owner = points.get(reference.owner.getId()).state;
            String key = key(reference, number);
            CopyState copy = owner.copies.get(key);
            if (copy == null) {
                copy = new CopyState();
                copy.original = images.get(reference.artifact.externalId).copies.get(number).toString();
                copy.restorable = !owner.legacy;
                owner.copies.put(key, copy);
            }
            return copy;
        }

        private void saveOwners() {
            for (Point point : points.values()) {
                if (!point.state.copies.isEmpty() || point == current) { save(point.backup, point.state); }
            }
        }

        private void settlePending() {
            for (Point point : points.values()) {
                for (Map.Entry<String, Pending> entry : new ArrayList<>(point.state.pending.entrySet())) {
                    String key = entry.getKey();
                    int separator = key.lastIndexOf(':');
                    if (separator <= 0) { throw new CloudRuntimeException("NetBackup retention copy identity is invalid"); }
                    Reference reference = references.get(key.substring(0, separator));
                    if (reference == null) {
                        if (point == current) { throw new CloudRuntimeException("NetBackup retention intent no longer matches its manifest"); }
                        continue;
                    }
                    int number = Integer.parseInt(key.substring(separator + 1));
                    Instant observed = images.get(reference.artifact.externalId).copies.get(number);
                    Pending pending = entry.getValue();
                    Instant target = Instant.parse(pending.target);
                    boolean shortening = pending.previous != null && target.isBefore(Instant.parse(pending.previous));
                    if (observed == null || (shortening ? !observed.equals(target) : observed.isBefore(target))) {
                        if (!shortening && observed != null && StringUtils.isBlank(pending.ticket) && target.getNano() != 0
                                && observed.equals(target.truncatedTo(ChronoUnit.SECONDS))) {
                            repairs.put(key, AblestackNetBackupClient.retentionTarget(target));
                        } else {
                            throw new CloudRuntimeException("NetBackup retention approval or acceptance from backup " + point.backup.getUuid() + " is pending for " + key
                                    + ", ticket=" + StringUtils.defaultIfBlank(pending.ticket, "UNCONFIRMED"));
                        }
                    }
                    CopyState receipt = copy(reference, number);
                    if (pending.previous == null) {
                        // Version-one intents had no original-date receipt. A confirmed old extension is a floor, not a rollback baseline.
                        receipt.restorable = false;
                        receipt.original = later(Instant.parse(receipt.original), repairs.get(key)).toString();
                    }
                    receipt.managed = repairs.containsKey(key) ? observed.toString() : target.toString();
                    if (!repairs.containsKey(key) && !observed.equals(target)) { receipt.externallyChanged = true; }
                    point.state.pending.remove(key);
                    save(point.backup, point.state);
                    save(reference.owner, points.get(reference.owner.getId()).state);
                }
            }
        }

        private Instant nativeExpiration(Point point) {
            if (point.manifest == null) { return null; }
            Instant result = null;
            for (ThirdPartyBackupManifest.Artifact artifact : point.manifest.getOwnedArtifacts()) {
                if (!artifact.completed || StringUtils.isBlank(artifact.externalId)) { return null; }
                String prefix = artifact.externalId + ":";
                boolean found = false;
                for (Map.Entry<String, CopyState> entry : point.state.copies.entrySet()) {
                    if (!entry.getKey().startsWith(prefix)) { continue; }
                    CopyState copy = entry.getValue();
                    if (copy.original == null) { return null; }
                    result = later(result, Instant.parse(copy.original));
                    found = true;
                }
                if (!found) { return null; }
            }
            return result;
        }

        private void captureDependentDates() {
            for (Point point : points.values()) {
                if (point.backup.getStatus() != Backup.Status.BackedUp || point.manifest == null
                        || !"ablestack-netbackup".equals(point.manifest.getProvider()) || !point.manifest.isComplete()
                        || nativeExpiration(point) != null) { continue; }
                boolean related = point.manifest.getRequiredArtifacts().stream().anyMatch(artifact -> references.values().stream()
                        .anyMatch(reference -> Objects.equals(artifact.backupUuid, reference.artifact.backupUuid)));
                if (!related) { continue; }
                point.manifest.validate(true);
                if (!point.backup.getUuid().equals(point.manifest.getBackupUuid())) {
                    throw new CloudRuntimeException("NetBackup retention dependent manifest identity differs");
                }
                for (ThirdPartyBackupManifest.Artifact artifact : point.manifest.getOwnedArtifacts()) {
                    Reference reference = new Reference(point.backup, artifact, artifact == point.manifest.getMetadata());
                    if (!images.containsKey(artifact.externalId)) { images.put(artifact.externalId, read(reference)); }
                    for (int number : images.get(artifact.externalId).copies.keySet()) { copy(reference, number); }
                }
            }
        }

        private Instant required(Reference reference, int number, boolean finalizing) {
            Instant required = Instant.parse(copy(reference, number).original);
            for (Point point : points.values()) {
                if (point.manifest == null || !"ablestack-netbackup".equals(point.manifest.getProvider())) { continue; }
                // Protect the ancestor's complete recovery point, including its metadata and every copy.
                boolean uses = point.manifest.getRequiredArtifacts().stream().anyMatch(artifact ->
                        Objects.equals(artifact.backupUuid, reference.artifact.backupUuid));
                if (!uses) { continue; }
                if (point.backup.getStatus() == Backup.Status.BackedUp || (point == current && finalizing)) {
                    Instant nativeDate = nativeExpiration(point);
                    if (nativeDate == null) { shortenAllowed = false; }
                    else { required = later(required, nativeDate); }
                } else if (point.backup.getStatus() == Backup.Status.BackingUp) {
                    if (point.state.leaseActive && point.state.preparationUntil != null
                            && Instant.parse(point.state.preparationUntil).isAfter(clock.instant())) {
                        required = later(required, Instant.parse(point.state.preparationUntil));
                    } else {
                        // A registered backup that has not established its protection lease still owns its parents.
                        shortenAllowed = false;
                    }
                }
            }
            return later(required, repairs.get(key(reference, number)));
        }
    }

    public NetBackupChainRetention(AblestackNetBackupClient client, BackupDao backups, BackupDetailsDao details) {
        this(client, backups, details, Clock.systemUTC());
    }

    NetBackupChainRetention(AblestackNetBackupClient client, BackupDao backups, BackupDetailsDao details, Clock clock) {
        this.client = client;
        this.backups = backups;
        this.details = details;
        this.clock = clock;
    }

    public static boolean needsReconciliation(Backup backup) {
        String json = backup.getDetail(ThirdPartyBackupManifest.RETENTION_PROTECTION_KEY);
        if (StringUtils.isBlank(json)) { return true; }
        try {
            State state = new Gson().fromJson(json, State.class);
            return state == null || (state.version != 1 && state.version != 2) || !backup.getUuid().equals(state.backupUuid)
                    || !"COMPLETED".equals(state.state) || state.pending == null || !state.pending.isEmpty()
                    || (hasRestoreReaders(backup) && (!state.leaseActive || state.preparationUntil == null
                        || !Instant.parse(state.preparationUntil).isAfter(Instant.now().plusSeconds(RENEW_BEFORE_SECONDS))))
                    || (state.nextCheckAt > 0 && state.nextCheckAt <= System.currentTimeMillis());
        } catch (RuntimeException e) { return true; }
    }

    /** A positive preparation argument activates a renewable lease, independent of the data-operation timeout. */
    public void protect(Backup backup, int preparationSeconds, boolean finalizing) {
        try (Guard guard = acquire(backup.getVmId())) {
            if (backup instanceof BackupVO) { backups.loadDetails((BackupVO) backup); }
            if (ThirdPartyBackupVolumeService.isDeletionPending(backup)) {
                throw new CloudRuntimeException("NetBackup retention cannot change a backup pending deletion");
            }
            protectLocked(backup, (preparationSeconds > 0 && !finalizing) || hasRestoreReaders(backup), finalizing);
        }
    }

    /** Called only once failed Host/external readers are terminal. Ancestor receipts are reconciled by normal sync. */
    public void release(Backup backup) {
        try (Guard guard = acquire(backup.getVmId())) {
            if (backup instanceof BackupVO) { backups.loadDetails((BackupVO) backup); }
            State state = readState(backup);
            state.leaseActive = false;
            state.preparationUntil = null;
            state.readersClosed = true;
            save(backup, state);
        }
    }

    private void protectLocked(Backup backup, boolean active, boolean finalizing) {
        State state = readState(backup);
        Instant now = clock.instant();
        try {
            ThirdPartyBackupManifest manifest = ThirdPartyBackupManifest.fromJson(backup.getDetail(ThirdPartyBackupManifest.DETAIL_KEY));
            if (!"ablestack-netbackup".equals(manifest.getProvider()) || !backup.getUuid().equals(manifest.getBackupUuid())) {
                throw new CloudRuntimeException("NetBackup retention manifest belongs to another backup");
            }
            if (finalizing) { manifest.validate(true); }
            String signature = DigestUtils.sha256Hex(manifest.toJson());
            if (active && state.leaseActive && "COMPLETED".equals(state.state) && state.pending.isEmpty()
                    && signature.equals(state.checkedManifest) && now.toEpochMilli() - state.checkedAt < CHECK_INTERVAL_MS) { return; }
            if (active) {
                if (!state.leaseActive || state.preparationUntil == null
                        || !Instant.parse(state.preparationUntil).isAfter(now.plusSeconds(RENEW_BEFORE_SECONDS))) {
                    state.preparationUntil = AblestackNetBackupClient.retentionTarget(now.plusSeconds(LEASE_SECONDS)).toString();
                }
            } else { state.preparationUntil = null; }
            state.leaseActive = active;
            Context context = new Context(backup, state, manifest, finalizing);
            context.settlePending();
            for (Reference reference : context.references.values()) {
                for (Map.Entry<Integer, Instant> observed : context.images.get(reference.artifact.externalId).copies.entrySet()) {
                    CopyState copy = context.copy(reference, observed.getKey());
                    String expected = copy.managed == null ? copy.original : copy.managed;
                    if (!observed.getValue().equals(Instant.parse(expected))) {
                        copy.externallyChanged = true;
                    }
                }
            }
            context.captureDependentDates();
            // Save every original date before the first external update, including ancestors owned by other recovery points.
            context.saveOwners();
            Map<String, Instant> targets = new LinkedHashMap<>();
            for (Reference reference : context.references.values()) {
                for (int number : context.images.get(reference.artifact.externalId).copies.keySet()) {
                    targets.put(key(reference, number), context.required(reference, number, finalizing));
                }
            }
            // Establish all required future dates before attempting to release any temporary protection.
            for (Reference reference : context.references.values()) {
                for (Map.Entry<Integer, Instant> entry : context.images.get(reference.artifact.externalId).copies.entrySet()) {
                    Instant target = targets.get(key(reference, entry.getKey()));
                    if (active) { target = later(target, Instant.parse(state.preparationUntil)); }
                    if (entry.getValue().isBefore(target)) {
                        if (context.copy(reference, entry.getKey()).externallyChanged) {
                            throw new CloudRuntimeException("NetBackup retention was changed externally; verify protection before continuing");
                        }
                        update(backup, state, context, reference, entry.getKey(), entry.getValue(), target);
                    }
                }
            }
            if (!active && context.shortenAllowed) {
                for (Reference reference : context.references.values()) {
                    for (Map.Entry<Integer, Instant> entry : context.images.get(reference.artifact.externalId).copies.entrySet()) {
                        int number = entry.getKey();
                        CopyState copy = context.copy(reference, number);
                        Instant target = targets.get(key(reference, number));
                        if (!copy.restorable || copy.externallyChanged || copy.managed == null
                                || !entry.getValue().equals(Instant.parse(copy.managed))) { continue; }
                        if (!target.isAfter(now)) {
                            if (copy.releaseAfter == null) { copy.releaseAfter = AblestackNetBackupClient.retentionTarget(now.plusSeconds(RECHECK_SECONDS)).toString(); }
                            target = Instant.parse(copy.releaseAfter);
                        }
                        if (target.isAfter(now) && target.isBefore(entry.getValue())) {
                            update(backup, state, context, reference, number, entry.getValue(), target);
                        }
                    }
                }
            }
            for (Reference reference : context.references.values()) {
                AblestackNetBackupClient.ImageRetention verified = read(reference);
                for (Map.Entry<Integer, Instant> entry : verified.copies.entrySet()) {
                    Instant target = targets.get(key(reference, entry.getKey()));
                    if (target == null) { throw new CloudRuntimeException("NetBackup retention copy inventory changed during protection"); }
                    if (active) { target = later(target, Instant.parse(state.preparationUntil)); }
                    if (target.isAfter(now) && entry.getValue().isBefore(target)) {
                        throw new CloudRuntimeException("NetBackup retention is not reflected in the catalog: " + reference.artifact.externalId);
                    }
                }
                if (!verified.copies.keySet().equals(context.images.get(reference.artifact.externalId).copies.keySet())) {
                    throw new CloudRuntimeException("NetBackup retention copy inventory changed during protection");
                }
            }
            Instant nativeDate = context.nativeExpiration(context.current);
            Instant protectedDate = later(nativeDate, active ? Instant.parse(state.preparationUntil) : null);
            state.protectedUntil = protectedDate == null ? null : protectedDate.toString();
            state.checkedAt = now.toEpochMilli();
            state.checkedManifest = signature;
            state.state = "COMPLETED";
            state.reason = null;
            for (Point point : context.points.values()) {
                point.state.nextCheckAt = point.state.copies.values().stream().anyMatch(copy ->
                        copy.restorable && !copy.externallyChanged && copy.managed != null && !copy.managed.equals(copy.original))
                        || hasRestoreReaders(point.backup) ? now.plusSeconds(RECHECK_SECONDS).toEpochMilli() : 0;
            }
            context.saveOwners();
        } catch (RuntimeException e) {
            state.state = "WAITING";
            state.reason = "NETBACKUP_RETENTION_UNCONFIRMED: " + e.getMessage();
            save(backup, state);
            throw new CloudRuntimeException(state.reason, e);
        }
    }

    private void update(Backup backup, State state, Context context, Reference reference, int number, Instant previous, Instant requested) {
        Instant target = AblestackNetBackupClient.retentionTarget(requested);
        String key = key(reference, number);
        Pending pending = new Pending();
        pending.target = target.toString();
        pending.previous = previous.toString();
        state.pending.put(key, pending);
        state.state = "WAITING";
        context.saveOwners();
        boolean shortening = target.isBefore(previous);
        final AblestackNetBackupClient.ExpireImageResult result;
        try {
            result = shortening ? client.restoreImageRetention(reference.artifact, reference.metadata, reference.policy, number, previous, target)
                    : client.extendImageRetention(reference.artifact, reference.metadata, reference.policy, number, target);
        } catch (AblestackNetBackupClient.RetentionUpdateRejected e) {
            state.pending.remove(key);
            throw e;
        }
        if (!result.isCompleted()) {
            pending.ticket = StringUtils.defaultIfBlank(result.getMpaTicket(), "MPA_TICKET_UNAVAILABLE");
            throw new CloudRuntimeException("NetBackup retention change is awaiting MPA approval for " + key + ", ticket=" + pending.ticket);
        }
        Instant observed = read(reference).copies.get(number);
        if (observed == null || (shortening ? !observed.equals(target) : observed.isBefore(target))) {
            throw new CloudRuntimeException("NetBackup retention update is accepted but catalog verification is pending for " + key);
        }
        CopyState receipt = context.copy(reference, number);
        receipt.managed = target.toString();
        if (!shortening) { receipt.releaseAfter = null; }
        if (!observed.equals(target)) { receipt.externallyChanged = true; }
        state.pending.remove(key);
        context.saveOwners();
    }

    private static String key(Reference reference, int number) { return reference.artifact.externalId + ":" + number; }

    private static Instant later(Instant first, Instant second) {
        return first == null || (second != null && second.isAfter(first)) ? second : first;
    }

    private static boolean isFailed(Backup backup) {
        return backup.getStatus() == Backup.Status.Failed || backup.getStatus() == Backup.Status.Error || backup.getStatus() == Backup.Status.Canceled;
    }

    private static boolean hasRestoreReaders(Backup backup) {
        return backup.getStatus() == Backup.Status.Restoring
                || ((StringUtils.isNotBlank(backup.getDetail(AblestackBackupFrameworkUtils.RESTORE_JOB_ID_DETAIL))
                    || StringUtils.isNotBlank(backup.getDetail(ThirdPartyBackupRestore.PLAN_KEY)))
                    && !"COMPLETED".equals(backup.getDetail(ThirdPartyBackupRestore.CLEANUP_STATE_KEY)))
                || StringUtils.isNotBlank(backup.getDetail(ThirdPartyExternalRestore.PENDING_KEY));
    }

    private AblestackNetBackupClient.ImageRetention read(Reference reference) {
        return client.imageRetention(reference.artifact, reference.metadata, reference.policy);
    }

    private Map<String, Reference> references(Backup backup, ThirdPartyBackupManifest manifest, boolean finalizing) {
        Map<String, Reference> references = new LinkedHashMap<>();
        Map<String, ThirdPartyBackupManifest> parents = new LinkedHashMap<>();
        for (ThirdPartyBackupManifest.Artifact required : manifest.getRequiredArtifacts()) {
            if (backup.getUuid().equals(required.backupUuid)) { continue; }
            if (!parents.containsKey(required.backupUuid)) {
                BackupVO owner = backups.findByUuid(required.backupUuid);
                if (owner == null || !Objects.equals(owner.getVmId(), backup.getVmId()) || !Objects.equals(owner.getZoneId(), backup.getZoneId())
                        || owner.getAccountId() != backup.getAccountId() || owner.getStatus() != Backup.Status.BackedUp) {
                    throw new CloudRuntimeException("NetBackup retention ancestor ownership/state is unconfirmed: " + required.backupUuid);
                }
                backups.loadDetails(owner);
                if (ThirdPartyBackupVolumeService.isDeletionPending(owner)) {
                    throw new CloudRuntimeException("NetBackup retention ancestor is pending deletion");
                }
                ThirdPartyBackupManifest source = ThirdPartyBackupManifest.fromJson(owner.getDetail(ThirdPartyBackupManifest.DETAIL_KEY));
                source.validate(true);
                if (!"ablestack-netbackup".equals(source.getProvider()) || !owner.getUuid().equals(source.getBackupUuid())) {
                    throw new CloudRuntimeException("NetBackup retention ancestor manifest identity differs");
                }
                parents.put(owner.getUuid(), source);
                addOwned(references, owner, source, true);
            }
            ThirdPartyBackupManifest source = parents.get(required.backupUuid);
            boolean matches = source.getCurrentArtifacts().stream().anyMatch(artifact -> Objects.equals(required.externalId, artifact.externalId)
                    && Objects.equals(required.path, artifact.path) && Objects.equals(required.sha256, artifact.sha256));
            if (!matches) { throw new CloudRuntimeException("NetBackup retention ancestor image differs from its owner"); }
        }
        addOwned(references, backup, manifest, finalizing);
        return references;
    }

    private void addOwned(Map<String, Reference> references, Backup owner, ThirdPartyBackupManifest manifest, boolean required) {
        for (ThirdPartyBackupManifest.Artifact artifact : manifest.getOwnedArtifacts()) {
            if (!artifact.completed || StringUtils.isBlank(artifact.externalId)) {
                if (required) { throw new CloudRuntimeException("NetBackup retention image completion is unconfirmed"); }
                continue;
            }
            Reference reference = new Reference(owner, artifact, artifact == manifest.getMetadata());
            Reference previous = references.putIfAbsent(artifact.externalId, reference);
            if (previous != null && (!Objects.equals(previous.artifact.path, artifact.path)
                    || !Objects.equals(previous.artifact.backupUuid, artifact.backupUuid))) {
                throw new CloudRuntimeException("NetBackup retention image is shared by conflicting artifacts");
            }
        }
    }

    private State readState(Backup backup) {
        String json = backup.getDetail(ThirdPartyBackupManifest.RETENTION_PROTECTION_KEY);
        State state = StringUtils.isBlank(json) ? new State() : new Gson().fromJson(json, State.class);
        if (state == null || (state.version != 1 && state.version != 2) || state.pending == null
                || (state.backupUuid != null && !backup.getUuid().equals(state.backupUuid))) {
            throw new CloudRuntimeException("NetBackup retention ownership record is invalid");
        }
        if (state.version == 1) {
            // Old receipts never recorded the original date. Keep their observed dates as a conservative floor.
            state.version = 2;
            state.legacy = true;
            state.leaseActive = false;
            state.copies = new LinkedHashMap<>();
        }
        if (state.copies == null) { throw new CloudRuntimeException("NetBackup original retention inventory is invalid"); }
        state.backupUuid = backup.getUuid();
        return state;
    }

    private void save(Backup backup, State state) {
        String json = new Gson().toJson(state);
        details.addDetail(backup.getId(), ThirdPartyBackupManifest.RETENTION_PROTECTION_KEY, json, false);
        backup.getDetails().put(ThirdPartyBackupManifest.RETENTION_PROTECTION_KEY, json);
    }
}
