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

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Properties;

import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.Gson;
import org.apache.cloudstack.backup.AblestackBackupFrameworkUtils;
import org.apache.cloudstack.backup.ThirdPartyBackupRestore;
import org.apache.cloudstack.backup.ThirdPartyBackupRestore.VmResult;
import org.apache.cloudstack.backup.ThirdPartyBackupRestore.VmVolumeResult;

/** Retained outside removable job files. Journals are deleted only after their final result is archived. */
final class LibvirtAblestackRestoreOutcome {
    private static final Path ROOT = Path.of(AblestackBackupFrameworkUtils.ASYNC_BACKUP_JOB_ROOT).getParent().resolve("restore-outcomes");

    private LibvirtAblestackRestoreOutcome() { }

    private static Path file(String jobId) {
        if (jobId == null || !jobId.matches("[A-Za-z0-9_.-]+") || jobId.equals(".") || jobId.equals("..")) {
            throw new CloudRuntimeException("Invalid restore outcome job ID");
        }
        return ROOT.resolve(jobId + ".json");
    }

    static VmResult read(String jobId) throws IOException {
        Path file = file(jobId);
        if (!Files.isRegularFile(file)) { return null; }
        VmResult result = new Gson().fromJson(Files.readString(file), VmResult.class);
        if (result == null || result.version != 1 || !jobId.equals(result.jobId) || result.revision <= 0 || result.updatedAt <= 0) {
            throw new CloudRuntimeException("Invalid saved VM restore outcome");
        }
        return result;
    }

    static void initialize(ThirdPartyBackupRestore.Plan plan) throws IOException {
        if (plan.vmResultVersion != 1) { return; }
        VmResult result = new VmResult();
        result.jobId = plan.jobId;
        result.sourceBackupUuid = plan.manifest.getBackupUuid();
        result.provider = plan.manifest.getProvider();
        result.targetVmName = plan.targetVmName;
        result.revision = 1;
        result.updatedAt = System.currentTimeMillis();
        result.phase = "WAITING";
        result.outcome = "NOT_STARTED";
        result.primaryCleanupState = "NOT_REQUIRED";
        for (int i = 0; i < plan.volumeUuids.size(); i++) {
            VmVolumeResult volume = new VmVolumeResult();
            volume.index = i;
            volume.volumeUuid = plan.volumeUuids.get(i);
            volume.prepareState = volume.switchState = "NOT_STARTED";
            volume.rollbackState = volume.cleanupState = "NOT_REQUIRED";
            result.volumes.add(volume);
        }
        publish(result);
    }

    static void recordFailure(ThirdPartyBackupRestore.Plan plan, String failure) throws IOException {
        if (plan.vmResultVersion != 1) { return; }
        VmResult result = read(plan.jobId);
        // A transaction's journal is authoritative, including commit uncertainty and pending rollback.
        if (result == null || result.transactionId != null || "FAILED".equals(result.outcome)
                || LibvirtAblestackRestoreTransaction.hasJournal(plan.jobId, plan.targetVmName)) { return; }
        result.revision++;
        result.updatedAt = System.currentTimeMillis();
        result.phase = "FAILED";
        result.outcome = "FAILED";
        result.failure = failure;
        publish(result);
    }

    static void attach(Properties state, ThirdPartyBackupRestore.Plan plan, String transactionId) throws IOException {
        if (plan == null || plan.vmResultVersion != 1) { return; }
        VmResult previous = read(plan.jobId);
        if (previous == null || previous.transactionId != null || !"NOT_STARTED".equals(previous.outcome)) {
            throw new CloudRuntimeException("Restore outcome initialization is missing or the transaction already started");
        }
        state.setProperty("audit.job", plan.jobId);
        state.setProperty("audit.backup", plan.manifest.getBackupUuid());
        state.setProperty("audit.provider", plan.manifest.getProvider());
        state.setProperty("audit.transaction", transactionId);
        state.setProperty("audit.revision", String.valueOf(previous.revision));
        for (int i = 0; i < plan.volumeUuids.size(); i++) { state.setProperty(i + ".volumeUuid", plan.volumeUuids.get(i)); }
    }

    static void publish(Properties state) throws IOException {
        if (state.getProperty("audit.job") == null) { return; }
        VmResult result = new VmResult();
        result.jobId = state.getProperty("audit.job");
        result.sourceBackupUuid = state.getProperty("audit.backup");
        result.provider = state.getProperty("audit.provider");
        result.targetVmName = state.getProperty("vm");
        result.transactionId = state.getProperty("audit.transaction");
        result.revision = time(state, "audit.revision");
        result.updatedAt = time(state, "audit.updatedAt");
        String phase = state.getProperty("phase");
        result.phase = "COMMITTING".equals(phase) ? "SWITCHING" : phase;
        result.outcome = "COMMITTED".equals(phase) ? "COMMITTED" : "ROLLED_BACK".equals(phase)
                || time(state, "audit.rolledBackAt") > 0 ? "ROLLED_BACK" : "RUNNING";
        result.primaryCleanupState = state.getProperty("audit.cleanupState", "NOT_STARTED");
        if ("COMMITTED".equals(phase) && "COMPLETED".equals(result.primaryCleanupState)) { result.phase = "COMPLETED"; }
        result.failure = state.getProperty("audit.failure");
        result.recoveryError = state.getProperty("audit.recoveryError");
        if (result.recoveryError != null && "RUNNING".equals(result.outcome)) { result.outcome = "RECOVERY_REQUIRED"; }
        result.preparedAt = time(state, "audit.preparedAt");
        result.switchStartedAt = time(state, "audit.switchStartedAt");
        result.committedAt = time(state, "audit.committedAt");
        result.rollbackStartedAt = time(state, "audit.rollbackStartedAt");
        result.rolledBackAt = time(state, "audit.rolledBackAt");
        result.primaryCleanupCompletedAt = time(state, "audit.cleanupCompletedAt");
        if (flag(state, "audit.commitUnconfirmed")) {
            result.phase = "COMMIT_UNCONFIRMED";
            result.outcome = "UNKNOWN";
            result.committedAt = 0;
        }
        for (int i = 0; i < Integer.parseInt(state.getProperty("count")); i++) {
            String prefix = i + ".";
            VmVolumeResult volume = new VmVolumeResult();
            volume.index = i;
            volume.volumeUuid = state.getProperty(prefix + "volumeUuid");
            volume.poolUuid = state.getProperty(prefix + "pool");
            volume.destination = state.getProperty(prefix + "target");
            volume.hadOriginal = Boolean.valueOf(state.getProperty(prefix + "hadOriginal"));
            volume.prepareStartedAt = time(state, prefix + "prepareStartedAt");
            volume.preparedAt = time(state, prefix + "preparedAt");
            volume.switchStartedAt = time(state, prefix + "switchStartedAt");
            volume.switchedAt = time(state, prefix + "switchedAt");
            volume.rollbackStartedAt = time(state, prefix + "rollbackStartedAt");
            volume.rolledBackAt = time(state, prefix + "rolledBackAt");
            volume.cleanedAt = time(state, prefix + "cleanedAt");
            volume.prepareState = flag(state, prefix + "ready") ? "PREPARED" : state.getProperty(prefix + "prepareError") != null
                    ? "FAILED" : volume.prepareStartedAt > 0 ? "PREPARING" : "NOT_STARTED";
            volume.switchState = flag(state, prefix + "switched") ? "SWITCHED" : state.getProperty(prefix + "switchError") != null
                    ? "FAILED" : flag(state, prefix + "switching") ? "SWITCHING" : "NOT_STARTED";
            volume.rollbackState = flag(state, prefix + "rolledBack") ? "ROLLED_BACK"
                    : volume.rollbackStartedAt > 0 ? "ROLLING_BACK" : "ROLLING_BACK".equals(phase) ? "NOT_STARTED" : "NOT_REQUIRED";
            volume.cleanupState = volume.cleanedAt > 0 ? "COMPLETED" : "COMMITTED".equals(phase) || "ROLLING_BACK".equals(phase)
                    || "ROLLED_BACK".equals(phase) ? "WAITING" : "NOT_STARTED";
            volume.failure = state.getProperty(prefix + "prepareError", state.getProperty(prefix + "switchError"));
            result.volumes.add(volume);
        }
        publish(result);
    }

    static void commitUnconfirmed(Properties state, String reason) throws IOException {
        if (state.getProperty("audit.job") == null) { return; }
        state.setProperty("audit.commitUnconfirmed", "true");
        state.setProperty("audit.recoveryError", reason);
        state.setProperty("audit.revision", String.valueOf(Math.addExact(time(state, "audit.revision"), 1)));
        state.setProperty("audit.updatedAt", String.valueOf(System.currentTimeMillis()));
        publish(state);
    }

    private static boolean flag(Properties state, String name) { return Boolean.parseBoolean(state.getProperty(name)); }
    private static long time(Properties state, String name) { return Long.parseLong(state.getProperty(name, "0")); }

    private static void publish(VmResult result) throws IOException {
        Path output = file(result.jobId);
        Files.createDirectories(ROOT);
        try (FileChannel channel = FileChannel.open(ROOT.resolve(result.jobId + ".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                java.nio.channels.FileLock lock = channel.lock()) {
            VmResult previous = read(result.jobId);
            if (previous != null) {
                if (!java.util.Objects.equals(previous.sourceBackupUuid, result.sourceBackupUuid)
                        || !java.util.Objects.equals(previous.targetVmName, result.targetVmName)
                        || (previous.transactionId != null && !previous.transactionId.equals(result.transactionId))) {
                    throw new CloudRuntimeException("Restore outcome belongs to another transaction");
                }
                if (previous.revision > result.revision) { throw new CloudRuntimeException("Restore journal outcome is older than its archived receipt"); }
                if (previous.revision == result.revision) {
                    if (!new Gson().toJson(previous).equals(new Gson().toJson(result))) {
                        throw new CloudRuntimeException("Restore outcome changed without advancing its revision");
                    }
                    forceDirectories();
                    return;
                }
            }
            LibvirtAblestackVolumeRestoreHelper.atomic(output, new Gson().toJson(result));
            forceDirectories();
        }
    }

    private static void forceDirectories() throws IOException {
        try (FileChannel directory = FileChannel.open(ROOT, StandardOpenOption.READ)) { directory.force(true); }
        try (FileChannel parent = FileChannel.open(ROOT.getParent(), StandardOpenOption.READ)) { parent.force(true); }
    }
}
