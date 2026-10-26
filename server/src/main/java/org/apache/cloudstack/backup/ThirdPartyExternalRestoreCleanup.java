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

import java.nio.file.Path;
import java.util.List;
import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.VMInstanceDao;
import com.google.gson.Gson;
import org.apache.cloudstack.backup.dao.BackupDao;
import org.apache.cloudstack.backup.dao.BackupDetailsDao;
import org.apache.commons.lang3.StringUtils;

/** External file metadata is separate from the VM restore plan and never owns image payloads. */
class ThirdPartyExternalRestoreCleanup {
    private static final org.apache.logging.log4j.Logger LOGGER = org.apache.logging.log4j.LogManager.getLogger(ThirdPartyExternalRestoreCleanup.class);
    private final BackupDao backups;
    private final BackupDetailsDao details;
    private final HostDao hosts;
    private final VMInstanceDao vms;
    private final AgentManager agents;
    private final ThirdPartyBackupStagingService staging;
    private final ThirdPartyBackupVolumeService volumes;
    private final Gson gson = new Gson();

    ThirdPartyExternalRestoreCleanup(BackupDao backups, BackupDetailsDao details, HostDao hosts, VMInstanceDao vms,
            AgentManager agents, ThirdPartyBackupStagingService staging, ThirdPartyBackupVolumeService volumes) {
        this.backups = backups;
        this.details = details;
        this.hosts = hosts;
        this.vms = vms;
        this.agents = agents;
        this.staging = staging;
        this.volumes = volumes;
    }

    /** The caller holds the VM lifecycle lease and has resolved the exact metadata artifact. */
    void record(Backup backup, String provider, String externalId, String jobId, String reason, String proofJson) {
        cleanup(backup, recordAttempt(backup, provider, externalId, jobId, reason, proofJson, false));
    }

    /** Persist ownership before dispatch, but never clean between callback claim and VM worker start. */
    void trackAccepted(Backup backup, String provider, String externalId, String jobId, String proofJson) {
        recordAttempt(backup, provider, externalId, jobId, null, proofJson, true);
    }

    private ThirdPartyExternalRestore.Attempt recordAttempt(Backup backup, String provider, String externalId, String jobId,
            String reason, String proofJson, boolean accepted) {
        String key = ThirdPartyExternalRestore.key(provider, jobId);
        ThirdPartyExternalRestore.Attempt attempt = gson.fromJson(backup.getDetail(key), ThirdPartyExternalRestore.Attempt.class);
        boolean changed = attempt == null;
        if (attempt != null) {
            requireIdentity(backup, attempt, provider, externalId, jobId);
            changed = attempt.accepted && !accepted;
        } else {
            ThirdPartyExternalRestore.Proof proof = gson.fromJson(proofJson, ThirdPartyExternalRestore.Proof.class);
            if (proof == null || StringUtils.isBlank(proof.hostName) || proof.startedAt <= 0 || proof.endedAt < proof.startedAt
                    || proof.endedAt > System.currentTimeMillis() + 300000L) {
                throw new CloudRuntimeException("Rejected restore requires a destination Host and confirmed external Job start/end times");
            }
            HostVO host = hosts.findByName(proof.hostName);
            if (host == null) { host = hosts.findByIp(proof.hostName); }
            if (host == null || host.getDataCenterId() != backup.getZoneId()
                    || host.getHypervisorType() != com.cloud.hypervisor.Hypervisor.HypervisorType.KVM || host.getRemoved() != null) {
                throw new CloudRuntimeException("External metadata destination is not a KVM Host in the backup Zone");
            }
            var vm = vms.findByIdIncludingRemoved(backup.getVmId());
            if (vm == null || StringUtils.isBlank(vm.getUuid())) { throw new CloudRuntimeException("Original VM identity is unavailable"); }
            ThirdPartyBackupManifest manifest = ThirdPartyBackupManifest.fromJson(backup.getDetail(ThirdPartyBackupManifest.DETAIL_KEY));
            manifest.validate(true);
            if (!provider.equals(manifest.getProvider())) { throw new CloudRuntimeException("External restore provider differs from its backup"); }
            attempt = new ThirdPartyExternalRestore.Attempt();
            attempt.id = ThirdPartyExternalRestore.id(provider, jobId);
            attempt.provider = provider;
            attempt.jobId = jobId;
            attempt.externalId = externalId;
            attempt.backupUuid = backup.getUuid();
            attempt.vmName = manifest.getVmName();
            attempt.vmUuid = vm.getUuid();
            attempt.hostId = host.getId();
            attempt.proof = proof;
            attempt.manifest = manifest;
            ThirdPartyBackupStagingService.Snapshot original = gson.fromJson(backup.getDetail(ThirdPartyBackupStagingService.SNAPSHOT_KEY),
                    ThirdPartyBackupStagingService.Snapshot.class);
            if (original != null) { addDestination(attempt, manifest.getMetadata().path, original); }
            if (BackupManager.ThirdPartyStagingEnable.value()) {
                ThirdPartyBackupStagingService.Snapshot current = staging.captureConfiguration();
                Path root = Path.of(current.rootPath, provider);
                addDestination(attempt, root.resolve(manifest.getVmName()).resolve(manifest.getTimestamp()).toString(), current);
                if (original == null && Path.of(manifest.getMetadata().path).startsWith(root)) {
                    addDestination(attempt, manifest.getMetadata().path, current);
                }
            }
        }
        if (changed) {
            attempt.accepted = accepted;
            attempt.state = accepted ? "SUBMITTED" : "FAILED";
            attempt.step = accepted ? "VM_RESTORE" : "PRECHECK";
            attempt.reason = reason;
            attempt.recordedAt = System.currentTimeMillis();
            attempt.cleanupDetails = accepted ? "Waiting for the VM restore and its staging cleanup to finish" : null;
            ThirdPartyExternalRestore.Attempt created = attempt;
            Transaction.execute((TransactionCallback<Boolean>) status -> {
                details.addDetail(backup.getId(), key, gson.toJson(created), false);
                if (!accepted) { details.addDetail(backup.getId(), ThirdPartyExternalRestore.LATEST_KEY, created.id, false); }
                details.addDetail(backup.getId(), ThirdPartyExternalRestore.PENDING_KEY, "true", false);
                return true;
            });
        }
        return attempt;
    }

    private void addDestination(ThirdPartyExternalRestore.Attempt attempt, String path, ThirdPartyBackupStagingService.Snapshot configuration) {
        Path expected = Path.of(configuration.rootPath, attempt.provider, attempt.vmName, attempt.manifest.getTimestamp());
        if (!expected.equals(Path.of(path)) || !expected.isAbsolute() || !expected.normalize().equals(expected)) {
            throw new CloudRuntimeException("Rejected metadata destination differs from its recorded staging configuration");
        }
        if (attempt.destinations.stream().anyMatch(destination -> path.equals(destination.path))) { return; }
        ThirdPartyExternalRestore.Destination destination = new ThirdPartyExternalRestore.Destination();
        destination.path = path;
        destination.configuration = configuration;
        attempt.destinations.add(destination);
    }

    private void requireIdentity(Backup backup, ThirdPartyExternalRestore.Attempt attempt, String provider, String externalId, String jobId) {
        if (attempt.version != 1 || !backup.getUuid().equals(attempt.backupUuid) || !provider.equals(attempt.provider)
                || !externalId.equals(attempt.externalId) || !jobId.equals(attempt.jobId)
                || !ThirdPartyExternalRestore.id(provider, jobId).equals(attempt.id)) {
            throw new CloudRuntimeException("Rejected restore receipt belongs to another request");
        }
    }

    void resumePending() {
        for (BackupDetailVO pending : details.findDetails(ThirdPartyExternalRestore.PENDING_KEY, "true", null)) {
            BackupVO backup = backups.findById(pending.getResourceId());
            if (backup == null) { continue; }
            try (ThirdPartyBackupVolumeService.Lease lease = volumes.acquireLifecycle(backup.getVmId())) {
                backups.loadDetails(backup);
                for (BackupDetailVO record : details.listDetails(backup.getId())) {
                    if (!record.getName().startsWith(ThirdPartyExternalRestore.PREFIX + "external-restore-")) { continue; }
                    ThirdPartyExternalRestore.Attempt attempt = gson.fromJson(record.getValue(), ThirdPartyExternalRestore.Attempt.class);
                    requireIdentity(backup, attempt, attempt.provider, attempt.externalId, attempt.jobId);
                    if (!"COMPLETED".equals(attempt.cleanupState)) { cleanup(backup, attempt); }
                }
                boolean waiting = details.listDetails(backup.getId()).stream()
                        .filter(record -> record.getName().startsWith(ThirdPartyExternalRestore.PREFIX + "external-restore-"))
                        .map(record -> gson.fromJson(record.getValue(), ThirdPartyExternalRestore.Attempt.class))
                        .anyMatch(attempt -> !"COMPLETED".equals(attempt.cleanupState));
                if (!waiting) { details.removeDetail(backup.getId(), ThirdPartyExternalRestore.PENDING_KEY); }
            } catch (Exception e) {
                LOGGER.warn("Rejected metadata cleanup remains pending for backup [{}]: {}", backup.getUuid(), e.getMessage());
            }
        }
    }

    private void cleanup(Backup backup, ThirdPartyExternalRestore.Attempt attempt) {
        if ("COMPLETED".equals(attempt.cleanupState)) { return; }
        try {
            if (attempt.accepted) { requireSubmittedCallback(backup, attempt); }
            requireNoWriters(backup);
            HostVO host = hosts.findById(attempt.hostId);
            if (host == null || host.getStatus() != com.cloud.host.Status.Up || host.getRemoved() != null) {
                throw new CloudRuntimeException("Metadata destination Host is unavailable");
            }
            if (attempt.destinations.isEmpty()) { throw new CloudRuntimeException("No recorded staging mount can identify the restored metadata"); }
            for (ThirdPartyExternalRestore.Destination destination : attempt.destinations) {
                staging.prepareCleanup(host, Path.of(destination.configuration.rootPath, attempt.provider).toString(), destination.configuration);
            }
            Answer answer = agents.send(host.getId(), new AblestackVolumeStagingCommand(attempt.id, "EXTERNAL_RESTORE_CLEANUP", -1,
                    gson.toJson(attempt)));
            if (answer == null || !answer.getResult() || !attempt.id.equals(answer.getDetails())) {
                throw new CloudRuntimeException(answer == null ? "No metadata cleanup response" : answer.getDetails());
            }
            attempt.cleanupState = "COMPLETED";
            attempt.cleanupDetails = null;
        } catch (Exception e) {
            attempt.cleanupState = "WAITING";
            attempt.cleanupDetails = StringUtils.defaultIfBlank(e.getMessage(), "Rejected metadata cleanup is unconfirmed");
        }
        details.addDetail(backup.getId(), ThirdPartyExternalRestore.key(attempt.provider, attempt.jobId), gson.toJson(attempt), false);
    }

    private void requireSubmittedCallback(Backup backup, ThirdPartyExternalRestore.Attempt attempt) {
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> claim = gson.fromJson(backup.getDetail(ThirdPartyExternalRestore.claimKey(attempt.provider, attempt.jobId)),
                java.util.Map.class);
        if (claim == null || !attempt.provider.equals(claim.get("provider")) || !attempt.jobId.equals(claim.get("jobId"))
                || !attempt.externalId.equals(claim.get("externalId")) || !attempt.backupUuid.equals(claim.get("backupUuid"))
                || !"SUBMITTED".equals(claim.get("state"))) {
            throw new CloudRuntimeException("External metadata cleanup is waiting for confirmed VM restore submission");
        }
    }

    private void requireNoWriters(Backup backup) {
        var vm = vms.findByIdIncludingRemoved(backup.getVmId());
        if (vm != null && vm.getState() == VirtualMachine.State.Restoring) { throw new CloudRuntimeException("VM restoration is active"); }
        for (Backup current : backups.listByVmId(backup.getZoneId(), backup.getVmId())) {
            backups.loadDetails((BackupVO) current);
            if (current.getStatus() == Backup.Status.BackingUp || current.getStatus() == Backup.Status.Restoring
                    || List.of("STARTING", "RUNNING", "CANCEL_PENDING").contains(StringUtils.defaultString(
                            current.getDetail(AblestackBackupFrameworkUtils.RESTORE_JOB_STATE_DETAIL)))) {
                throw new CloudRuntimeException("A backup or restore for this VM is active; metadata cleanup will retry");
            }
            for (String key : List.of(ThirdPartyBackupAdmission.BACKUP_KEY, ThirdPartyBackupAdmission.RESTORE_KEY)) {
                ThirdPartyBackupAdmission.Entry entry = gson.fromJson(current.getDetail(key), ThirdPartyBackupAdmission.Entry.class);
                if (entry != null && !"RELEASED".equals(entry.state)) { throw new CloudRuntimeException("A staging reservation still owns this VM"); }
            }
            if (StringUtils.isNotBlank(current.getDetail(ThirdPartyBackupRestore.PLAN_KEY))
                    && !"COMPLETED".equals(current.getDetail(ThirdPartyBackupRestore.CLEANUP_STATE_KEY))) {
                throw new CloudRuntimeException("Earlier VM restore termination and cleanup are unconfirmed");
            }
        }
    }
}
