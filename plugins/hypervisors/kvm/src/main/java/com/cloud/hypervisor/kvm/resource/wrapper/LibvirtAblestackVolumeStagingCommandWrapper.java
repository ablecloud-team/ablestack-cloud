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

package com.cloud.hypervisor.kvm.resource.wrapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

import com.cloud.agent.api.Answer;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;
import com.google.gson.Gson;
import org.apache.cloudstack.backup.AblestackBackupFrameworkUtils;
import org.apache.cloudstack.backup.AblestackVolumeStagingCommand;
import org.apache.cloudstack.backup.ThirdPartyBackupManifest;

@ResourceWrapper(handles = AblestackVolumeStagingCommand.class)
public class LibvirtAblestackVolumeStagingCommandWrapper
        extends CommandWrapper<AblestackVolumeStagingCommand, Answer, LibvirtComputingResource> {
    @Override
    public Answer execute(AblestackVolumeStagingCommand command, LibvirtComputingResource resource) {
        try {
            String id = command.getJobId();
            if (id == null || !id.matches("[A-Za-z0-9_.-]+") || id.equals(".") || id.equals("..")) {
                return new Answer(command, false, "Invalid volume staging job ID");
            }
            Path directory = Path.of(AblestackBackupFrameworkUtils.ASYNC_BACKUP_JOB_ROOT, id);
            if (java.util.Set.of("BACKUP_START_PREPARE", "BACKUP_START_STATUS", "BACKUP_START_ABORT").contains(command.getAction())) {
                var plan = new Gson().fromJson(command.getManifest(), org.apache.cloudstack.backup.ThirdPartyBackupStart.Plan.class);
                if (plan == null || !id.equals(plan.jobId)) { return new Answer(command, false, "Start control belongs to another backup"); }
                return new Answer(command, true, new Gson().toJson(LibvirtAblestackBackupStartHelper.control(plan, command.getAction(), logger)));
            }
            if ("RESTORE_RESULT".equals(command.getAction())) {
                var result = LibvirtAblestackRestoreOutcome.read(id);
                return new Answer(command, result != null, result == null ? "VM restore transaction result is not recorded" : new Gson().toJson(result));
            }
            if (java.util.Set.of("RESTORE_START_PREPARE", "RESTORE_START_ABORT").contains(command.getAction())) {
                org.apache.cloudstack.backup.ThirdPartyBackupRestore.Plan plan = new Gson().fromJson(command.getManifest(),
                        org.apache.cloudstack.backup.ThirdPartyBackupRestore.Plan.class);
                if (plan == null || !id.equals(plan.jobId)) { return new Answer(command, false, "Start control belongs to another restore job"); }
                return new Answer(command, true, new Gson().toJson(LibvirtAblestackVolumeRestoreHelper.controlStart(plan,
                        "RESTORE_START_ABORT".equals(command.getAction()))));
            }
            if (java.util.Set.of("ADMISSION", "ADMISSION_STATUS", "ADMISSION_CANCEL", "ADMISSION_INSPECT").contains(command.getAction())) {
                String state = LibvirtAblestackAsyncBackupRunner.getJobState(id, logger);
                if (!"ADMISSION_INSPECT".equals(command.getAction()) && !java.util.Set.of("STARTED", "STARTING", "RUNNING").contains(state)) {
                    return new Answer(command, false, "Host staging engine is not active");
                }
                Path plan = directory.resolve("staging-admission-plan.json");
                Path script = Path.of(resource.getAbleCvtBackupPath()).getParent().resolve("thirdparty_staging_admission.py");
                if (!Files.isRegularFile(plan) || !Files.isRegularFile(script)) {
                    return new Answer(command, false, "Staging admission request is not ready");
                }
                Path result = directory.resolve("staging-admission-result-" + java.util.UUID.randomUUID() + ".json");
                String token = "";
                if (java.util.Set.of("ADMISSION", "ADMISSION_STATUS").contains(command.getAction()) && command.getManifest() != null) {
                    @SuppressWarnings("unchecked")
                    Map<String, String> options = new Gson().fromJson(command.getManifest(), Map.class);
                    token = options.getOrDefault("token", "");
                }
                String output = com.cloud.utils.script.Script.runSimpleBashScriptWithFullResult("python3 " + quote(script.toString())
                        + " --plan-file " + quote(plan.toString()) + " --action "
                        + ("ADMISSION_CANCEL".equals(command.getAction()) ? "cancel --reason "
                                + quote(command.getManifest() == null ? "Staging queue canceled by operator" : command.getManifest())
                                : "ADMISSION_INSPECT".equals(command.getAction()) ? "inspect"
                                : "ADMISSION_STATUS".equals(command.getAction()) ? "status" : "admit")
                        + " --token " + quote(token) + " --result-file " + quote(result.toString()), 30000);
                if (!Files.isRegularFile(result)) {
                    logger.warn("Staging admission failed for job [{}]: {}", id, output);
                    return new Answer(command, false, "Host staging admission did not return a confirmed result");
                }
                String details = Files.readString(result);
                Files.deleteIfExists(result);
                return new Answer(command, true, details);
            }
            if ("BACKUP_PRIMARY_CAPACITY".equals(command.getAction())) {
                Path plan = directory.resolve("backup-primary-capacity.json");
                Path script = Path.of(resource.getAbleCvtBackupPath()).getParent().resolve("thirdparty_primary_capacity.py");
                String output = com.cloud.utils.script.Script.runSimpleBashScriptWithFullResult("python3 " + quote(script.toString())
                        + " --backup-plan-file " + quote(plan.toString()), 30000);
                var capacity = new Gson().fromJson(output, org.apache.cloudstack.backup.ThirdPartyBackupAdmission.PrimaryCapacity.class);
                return new Answer(command, capacity != null && id.equals(capacity.jobId), output);
            }
            if ("FINALIZE_BACKUP".equals(command.getAction())) {
                String state = LibvirtAblestackAsyncBackupRunner.getJobState(id, logger);
                if (!java.util.Set.of("COMPLETED", "FAILED", "INTERRUPTED", "CANCELED").contains(state)) {
                    return new Answer(command, false, "Source backup engine is active or its termination is unconfirmed");
                }
                ThirdPartyBackupManifest manifest = ThirdPartyBackupManifest.fromJson(command.getManifest());
                manifest.validate(true);
                Path plan = directory.resolve("volume-plan.json");
                Path script = Path.of(resource.getAbleCvtBackupPath()).getParent().resolve("thirdparty_volume_backup.py");
                if (!id.equals(manifest.getBackupUuid()) || !Files.isRegularFile(plan) || !Files.isRegularFile(script)) {
                    return new Answer(command, false, "Completed backup plan ownership or finalization helper is unconfirmed");
                }
                String attempt = java.util.UUID.randomUUID().toString();
                Path completedManifest = directory.resolve("volume-finalization-manifest-" + attempt + ".json");
                LibvirtAblestackVolumeRestoreHelper.atomic(completedManifest, manifest.toJson());
                Path resultFile = directory.resolve("volume-finalization-result-" + attempt + ".json");
                String output = com.cloud.utils.script.Script.runSimpleBashScriptWithFullResult("python3 " + quote(script.toString())
                        + " --plan-file " + quote(plan.toString()) + " --action finalize --completed-manifest-file "
                        + quote(completedManifest.toString()) + " --result-file " + quote(resultFile.toString()), 300000);
                Files.deleteIfExists(completedManifest);
                Files.writeString(directory.resolve("volume-finalization.log"), output == null ? "Finalization helper returned no output" : output);
                if (Files.isRegularFile(resultFile)) {
                    var result = new Gson().fromJson(Files.readString(resultFile), com.google.gson.JsonObject.class);
                    Files.deleteIfExists(resultFile);
                    if (result != null && result.has("version") && result.get("version").getAsInt() == 1
                            && result.has("backupUuid") && id.equals(result.get("backupUuid").getAsString())
                            && result.has("state") && "COMPLETED".equals(result.get("state").getAsString())) {
                        LibvirtAblestackAsyncBackupRunner.markVolumeBackupFinalized(id, logger);
                        return new Answer(command, true, "Host finalization completed; current source checkpoint is retained");
                    }
                }
                String reason = output == null ? "Host finalization helper returned no output" : java.util.Arrays.stream(output.split("\\r?\\n"))
                        .filter(line -> line.startsWith("RuntimeError:")).reduce((previous, current) -> current)
                        .orElse("Host finalization is pending; inspect " + directory.resolve("volume-finalization.log"));
                return new Answer(command, false, reason);
            }
            if ("CLEANUP_COMPLETED".equals(command.getAction())) {
                if (!"COMPLETED".equals(LibvirtAblestackAsyncBackupRunner.getJobState(id, logger))) {
                    return new Answer(command, false, "Source backup completion is unconfirmed");
                }
                ThirdPartyBackupManifest manifest = ThirdPartyBackupManifest.fromJson(command.getManifest());
                Path plan = directory.resolve("volume-plan.json");
                var source = new Gson().fromJson(Files.readString(plan), com.google.gson.JsonObject.class);
                if (!id.equals(manifest.getBackupUuid()) || source == null || !source.has("manifest")
                        || !source.get("manifest").isJsonObject() || !source.getAsJsonObject("manifest").has("backupUuid")
                        || !id.equals(source.getAsJsonObject("manifest").get("backupUuid").getAsString())) {
                    return new Answer(command, false, "Source cleanup belongs to another backup");
                }
                Path script = Path.of(resource.getAbleCvtBackupPath()).getParent().resolve("thirdparty_volume_backup.py");
                com.cloud.utils.script.Script.runSimpleBashScriptWithFullResult("python3 " + quote(script.toString())
                        + " --plan-file " + quote(plan.toString()) + " --action cleanup-completed", 300000);
                String result = Files.readString(directory.resolve("source-cleanup.json"));
                return new Answer(command, LibvirtAblestackAsyncBackupRunner.sourceCleanupCompleted(directory, id, source), result);
            }
            if ("SOURCE_START_STATUS".equals(command.getAction())) {
                String state = LibvirtAblestackAsyncBackupRunner.getJobState(id, logger);
                if (!java.util.Set.of("COMPLETED", "FAILED", "INTERRUPTED", "CANCELED").contains(state)) {
                    return new Answer(command, false, "Source engine termination is unconfirmed");
                }
                return new Answer(command, true, new Gson().toJson(Map.of(
                        "version", 1, "backupUuid", id, "state", sourceStartState(directory, id))));
            }
            if (java.util.Set.of("CLEANUP_FAILED", "CLEANUP_UNSTARTED").contains(command.getAction())) {
                String state = LibvirtAblestackAsyncBackupRunner.getJobState(id, logger);
                if (!java.util.Set.of("COMPLETED", "FAILED", "INTERRUPTED", "CANCELED").contains(state)) {
                    return new Answer(command, false, "Backup engine is active or unconfirmed");
                }
                Path plan = directory.resolve("volume-plan.json");
                Path script = Path.of(resource.getAbleCvtBackupPath()).getParent().resolve("thirdparty_volume_backup.py");
                if (!Files.isRegularFile(plan) || !Files.isRegularFile(script)) {
                    return new Answer(command, false, "Failed backup plan or cleanup helper is missing");
                }
                ThirdPartyBackupManifest manifest = ThirdPartyBackupManifest.fromJson(command.getManifest());
                if (!id.equals(manifest.getBackupUuid())) { return new Answer(command, false, "Cleanup belongs to another backup"); }
                boolean unstarted = "CLEANUP_UNSTARTED".equals(command.getAction());
                if (unstarted && (!"NOT_STARTED".equals(sourceStartState(directory, id))
                        || manifest.getOwnedArtifacts().stream().anyMatch(artifact -> artifact.submissionPending || artifact.completed
                                || artifact.submittedAt > 0 || artifact.size > 0 || artifact.jobId != null || artifact.externalId != null))) {
                    return new Answer(command, false, "Unstarted cleanup lacks proof or conflicts with an external transfer");
                }
                Files.deleteIfExists(directory.resolve("volume-cleanup.json"));
                String output = com.cloud.utils.script.Script.runSimpleBashScriptWithFullResult("python3 " + quote(script.toString())
                        + " --plan-file " + quote(plan.toString()) + " --action " + (unstarted ? "cleanup-unstarted" : "cleanup"), 300000);
                Files.writeString(directory.resolve("volume-cleanup.log"), output == null ? "Cleanup helper returned no output" : output);
                String reason = output == null ? "Cleanup helper returned no output" : java.util.Arrays.stream(output.split("\\r?\\n"))
                        .filter(line -> line.startsWith("RuntimeError:")).reduce((previous, current) -> current)
                        .orElse("Source engine cleanup is waiting; inspect " + directory.resolve("volume-cleanup.log"));
                return new Answer(command, Files.isRegularFile(directory.resolve("volume-cleanup.json")),
                        Files.isRegularFile(directory.resolve("volume-cleanup.json")) ? "Failed backup cleanup completed"
                                : reason);
            }
            if ("CLEANUP_CHECK".equals(command.getAction())) {
                String state = LibvirtAblestackAsyncBackupRunner.getJobState(id, logger);
                if (java.util.Set.of("STARTED", "STARTING", "RUNNING").contains(state)
                        || (Files.isRegularFile(directory.resolve("volume-plan.json")) && "UNKNOWN".equals(state))) {
                    return new Answer(command, false, "Source backup engine is active or its termination is unconfirmed");
                }
                ThirdPartyBackupManifest manifest = ThirdPartyBackupManifest.fromJson(command.getManifest());
                Path backupPath = Path.of(manifest.getCurrentArtifacts().get(0).path).toAbsolutePath().normalize().getParent();
                Path vmPath = backupPath.getParent();
                Path providerPath = vmPath.getParent();
                if (!id.equals(manifest.getBackupUuid()) || !backupPath.getFileName().toString().equals(manifest.getTimestamp())
                        || !vmPath.getFileName().toString().equals(manifest.getVmName())
                        || !providerPath.getFileName().toString().equals(manifest.getProvider())) {
                    return new Answer(command, false, "Invalid volume backup cleanup destination");
                }
                Path reservation = providerPath.getParent().resolve(".volume-reservations").resolve(id + ".json");
                if (Files.exists(reservation)) {
                    return new Answer(command, false, "Backup engine cleanup retained its staging reservation; reconcile it before deleting artifacts");
                }
                if (!Files.isRegularFile(Path.of(AblestackBackupFrameworkUtils.STAGING_ADMISSION_CLOSED_ROOT, id + ".closed"))) {
                    return new Answer(command, false, "Durable staging admission closure is unconfirmed; reconcile cleanup before deleting job records");
                }
                return new Answer(command, true, "Volume backup engine no longer reserves staging capacity");
            }
            if (!Files.isDirectory(directory)) {
                return new Answer(command, false, "Volume staging job does not exist");
            }
            if (command.getAction().startsWith("RESTORE_")) {
                if ("RESTORE_CANCEL".equals(command.getAction())) {
                    org.apache.cloudstack.backup.ThirdPartyBackupRestore.Plan expected = new Gson().fromJson(command.getManifest(),
                            org.apache.cloudstack.backup.ThirdPartyBackupRestore.Plan.class);
                    Path saved = directory.resolve("volume-restore-plan.json");
                    if (expected == null || !id.equals(expected.jobId) || !Files.isRegularFile(saved)
                            || !new Gson().toJson(expected).equals(new Gson().toJson(new Gson().fromJson(Files.readString(saved),
                                    org.apache.cloudstack.backup.ThirdPartyBackupRestore.Plan.class)))) {
                        return new Answer(command, false, "Cancellation differs from the initialized restore plan");
                    }
                    LibvirtAblestackVolumeRestoreHelper.atomic(directory.resolve("volume-restore-cancel"), "Queued restore cancellation requested\n");
                    return new Answer(command, true, "Restore cancellation recorded; termination and cleanup must still be confirmed");
                }
                if ("RESTORE_PRIMARY_CAPACITY".equals(command.getAction())) {
                    return new Answer(command, true, new Gson().toJson(LibvirtAblestackPrimaryRestoreCapacity.query(resource, directory)));
                }
                if ("RESTORE_CLEANUP".equals(command.getAction())) {
                    String state = LibvirtAblestackAsyncBackupRunner.getJobState(id, logger);
                    if (!java.util.Set.of("COMPLETED", "FAILED", "INTERRUPTED", "CANCELED").contains(state)) {
                        return new Answer(command, false, "Restore engine is active or unconfirmed");
                    }
                    org.apache.cloudstack.backup.ThirdPartyBackupRestore.Plan plan = new Gson().fromJson(command.getManifest(),
                            org.apache.cloudstack.backup.ThirdPartyBackupRestore.Plan.class);
                    if (!id.equals(plan.jobId)) { return new Answer(command, false, "Cleanup belongs to another restore job"); }
                    LibvirtAblestackVolumeRestoreHelper.cleanup(resource, logger, plan);
                    return new Answer(command, true, plan.vmResultVersion == 1
                            ? new Gson().toJson(LibvirtAblestackRestoreOutcome.read(id))
                            : "Restore transaction, staging and reservation cleanup completed");
                }
                Path restoreRequest = directory.resolve("volume-restore-request.json");
                if ("RESTORE_STATUS".equals(command.getAction())) {
                    Path admission = directory.resolve("staging-admission-request.json");
                    if (Files.isRegularFile(admission)) { return new Answer(command, true, Files.readString(admission)); }
                    return new Answer(command, true, Files.isRegularFile(restoreRequest) ? Files.readString(restoreRequest) : "");
                }
                if ("RESTORE_FAIL".equals(command.getAction())) {
                    LibvirtAblestackVolumeRestoreHelper.atomic(directory.resolve("volume-restore-failure"), command.getManifest());
                    return new Answer(command, true, "Restore transfer failure recorded");
                }
                if ("RESTORE_NETBACKUP_RECOVER".equals(command.getAction())) {
                    @SuppressWarnings("unchecked")
                    Map<String, String> options = new Gson().fromJson(command.getManifest(), Map.class);
                    org.apache.cloudstack.backup.ThirdPartyBackupRestore.Request recorded = new Gson().fromJson(options.get("request"),
                            org.apache.cloudstack.backup.ThirdPartyBackupRestore.Request.class);
                    if (recorded == null || !id.equals(recorded.jobId) || recorded.sequence != command.getIndex() || recorded.sequence < 0) {
                        return new Answer(command, false, "Recovery requires the exact recorded restore request");
                    }
                    // Recovery uses the original per-sequence submission plan, even after the Host has advanced or removed its request.
                    Path planFile = directory.resolve("netbackup-restore-" + recorded.sequence + ".json");
                    if (!Files.isRegularFile(planFile)) {
                        return new Answer(command, false, "Saved NetBackup submission plan is unavailable; submission remains unconfirmed");
                    }
                    Map<String, Object> plan = new java.util.LinkedHashMap<>();
                    plan.put("request", recorded);
                    plan.put("server", options.get("server"));
                    plan.put("destinationClient", options.get("destinationClient"));
                    if (!Files.readString(planFile).equals(new Gson().toJson(plan))) {
                        return new Answer(command, false, "Persisted NetBackup restore selection differs from the recorded request");
                    }
                    return netBackupRestore(command, resource, planFile, true);
                }
                if (!Files.isRegularFile(restoreRequest)) { return new Answer(command, false, "Restore request is not pending"); }
                org.apache.cloudstack.backup.ThirdPartyBackupRestore.Request saved = new Gson().fromJson(Files.readString(restoreRequest),
                        org.apache.cloudstack.backup.ThirdPartyBackupRestore.Request.class);
                if ("RESTORE_NETBACKUP".equals(command.getAction()) && saved.sequence == command.getIndex()) {
                    if (!"RUNNING".equals(LibvirtAblestackAsyncBackupRunner.getJobState(id, logger))) {
                        return new Answer(command, false, "Restore engine has stopped; only saved submission recovery is allowed");
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, String> options = new Gson().fromJson(command.getManifest(), Map.class);
                    if (!new Gson().toJson(saved).equals(options.get("request"))) {
                        return new Answer(command, false, "Submission differs from the recorded restore request");
                    }
                    Path planFile = directory.resolve("netbackup-restore-" + saved.sequence + ".json");
                    Map<String, Object> plan = new java.util.LinkedHashMap<>();
                    plan.put("request", saved);
                    plan.put("server", options.get("server"));
                    plan.put("destinationClient", options.get("destinationClient"));
                    String planJson = new Gson().toJson(plan);
                    if (Files.exists(planFile) && !Files.readString(planFile).equals(planJson)) {
                        return new Answer(command, false, "Persisted NetBackup restore selection differs from this request");
                    }
                    if (!Files.exists(planFile)) { LibvirtAblestackVolumeRestoreHelper.atomic(planFile, planJson); }
                    return netBackupRestore(command, resource, planFile, false);
                }
                if ("RESTORE_ACK".equals(command.getAction()) && saved.sequence == command.getIndex()
                        && new Gson().toJson(saved).equals(command.getManifest())) {
                    LibvirtAblestackVolumeRestoreHelper.atomic(directory.resolve("volume-restore-ack-" + saved.sequence + ".json"),
                            command.getManifest());
                    return new Answer(command, true, "Restore artifact acknowledged");
                }
                return new Answer(command, false, "Invalid restore transfer acknowledgment");
            }
            Path request = directory.resolve("volume-request.json");
            if ("CANCEL".equals(command.getAction())) {
                Files.writeString(directory.resolve("volume-cancel"), "cancel\n");
                return new Answer(command, true, "Volume pipeline cancellation requested");
            }
            if ("STATUS".equals(command.getAction())) {
                Path admission = directory.resolve("staging-admission-request.json");
                if (Files.isRegularFile(admission)) { return new Answer(command, true, Files.readString(admission)); }
                return new Answer(command, true, Files.isRegularFile(request) ? Files.readString(request) : "");
            }
            if ("START".equals(command.getAction())) {
                if (!java.util.Set.of("STARTED", "STARTING", "RUNNING").contains(LibvirtAblestackAsyncBackupRunner.getJobState(id, logger))
                        || Files.exists(directory.resolve("volume-cancel")) || Files.exists(directory.resolve("staging-admission-cancel"))
                        || Files.exists(directory.resolve("staging-admission-closed"))
                        || Files.exists(Path.of(AblestackBackupFrameworkUtils.STAGING_ADMISSION_CLOSED_ROOT, id + ".closed"))) {
                    return new Answer(command, false, "Source engine is stopped or cancellation is pending");
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> gate = Files.isRegularFile(request) ? new Gson().fromJson(Files.readString(request), Map.class) : null;
                if (gate == null || !Boolean.TRUE.equals(gate.get("gate")) || !id.equals(gate.get("backupUuid"))) {
                    return new Answer(command, false, "Source preparation is not pending");
                }
                LibvirtAblestackVolumeRestoreHelper.atomic(directory.resolve("volume-start"), "start\n");
                return new Answer(command, true, "Source preparation acknowledged");
            }
            if (!"ACK".equals(command.getAction()) || command.getIndex() < 0 || !Files.isRegularFile(request)) {
                return new Answer(command, false, "Invalid volume staging acknowledgment");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> current = new Gson().fromJson(Files.readString(request), Map.class);
            if (((Number) current.get("index")).intValue() != command.getIndex()) {
                return new Answer(command, false, "Volume staging request changed");
            }
            ThirdPartyBackupManifest manifest = ThirdPartyBackupManifest.fromJson(command.getManifest());
            if (!id.equals(manifest.getBackupUuid())) {
                return new Answer(command, false, "Acknowledgment belongs to another backup");
            }
            boolean metadata = Boolean.TRUE.equals(current.get("metadata"));
            if (metadata != (command.getIndex() == manifest.getVolumes().size())
                    || command.getIndex() > manifest.getVolumes().size()) {
                return new Answer(command, false, "Acknowledgment has an invalid artifact index");
            }
            ThirdPartyBackupManifest.Artifact requested = new Gson().fromJson(new Gson().toJson(current.get("artifact")),
                    ThirdPartyBackupManifest.Artifact.class);
            ThirdPartyBackupManifest.Artifact confirmed = metadata ? manifest.getMetadata()
                    : manifest.getCurrentArtifacts().get(command.getIndex());
            if (requested == null || confirmed == null || !confirmed.completed
                    || !java.util.Objects.equals(requested.path, confirmed.path)
                    || !java.util.Objects.equals(requested.backupUuid, confirmed.backupUuid)
                    || (!metadata && requested.size != confirmed.size)) {
                return new Answer(command, false, "Acknowledgment differs from the requested artifact");
            }
            Path acknowledgment = directory.resolve("volume-ack-" + command.getIndex() + ".json");
            Path temporary = directory.resolve("volume-ack-" + command.getIndex() + ".tmp");
            Files.writeString(temporary, manifest.toJson(), StandardCharsets.UTF_8);
            try (var channel = java.nio.channels.FileChannel.open(temporary, java.nio.file.StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            Files.move(temporary, acknowledgment, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return new Answer(command, true, "Artifact transfer acknowledged");
        } catch (Exception e) {
            logger.warn("Volume staging control failed for job [{}]", command.getJobId(), e);
            return new Answer(command, false, e.getMessage());
        }
    }

    private Answer netBackupRestore(AblestackVolumeStagingCommand command, LibvirtComputingResource resource,
            Path planFile, boolean recoveryOnly) {
        Path script = Path.of(resource.getAbleCvtBackupPath()).getParent().resolve("netbackup-volume-restore.py");
        if (!Files.isRegularFile(script)) {
            String stem = planFile.getFileName().toString().replaceFirst("\\.json$", "");
            if (!recoveryOnly && !Files.exists(planFile.resolveSibling(stem + ".intent.json"))
                    && !Files.exists(planFile.resolveSibling(stem + ".receipt.json"))) {
                return new Answer(command, false, "ABLESTACK_NOT_SUBMITTED=" + new Gson().toJson(
                        Map.of("notSubmitted", true, "failure", "NetBackup volume restore helper is not installed")));
            }
            return new Answer(command, false, "NetBackup volume restore helper is not installed; earlier submission remains unconfirmed");
        }
        String output = com.cloud.utils.script.Script.runSimpleBashScriptWithFullResult("python3 " + quote(script.toString())
                + " --plan-file " + quote(planFile.toString()) + (recoveryOnly ? " --recover-only" : ""), 120000);
        if (output != null) {
            for (String line : output.split("\\r?\\n")) {
                if (line.startsWith("ABLESTACK_JOB_ID=")) {
                    return new Answer(command, true, line.substring("ABLESTACK_JOB_ID=".length()).trim());
                }
                if (line.startsWith("ABLESTACK_NOT_SUBMITTED=")) { return new Answer(command, false, line); }
            }
        }
        return new Answer(command, false, "NetBackup restore operation could not be confirmed; inspect its saved submission receipt");
    }

    private String sourceStartState(Path directory, String id) throws java.io.IOException {
        Path receipt = directory.resolve("volume-source-start.json");
        Path plan = directory.resolve("volume-plan.json");
        if (!Files.isRegularFile(receipt) || !Files.isRegularFile(plan)) { return "UNKNOWN"; }
        @SuppressWarnings("unchecked")
        Map<String, Object> source = new Gson().fromJson(Files.readString(plan), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> recorded = new Gson().fromJson(Files.readString(receipt), Map.class);
        if (source == null || !(source.get("manifest") instanceof Map) || recorded == null
                || !id.equals(((Map<?, ?>) source.get("manifest")).get("backupUuid"))
                || !id.equals(recorded.get("backupUuid")) || !(recorded.get("version") instanceof Number)
                || ((Number) recorded.get("version")).doubleValue() != 1
                || !("NOT_STARTED".equals(recorded.get("state")) || "STARTED".equals(recorded.get("state")))) {
            return "UNKNOWN";
        }
        return (String) recorded.get("state");
    }

    private static String quote(String value) { return "'" + value.replace("'", "'\"'\"'") + "'"; }
}
