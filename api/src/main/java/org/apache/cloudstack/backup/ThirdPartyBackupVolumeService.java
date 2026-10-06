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

import com.cloud.host.Host;
import org.apache.cloudstack.api.response.BackupStagingInfoResponse;

/** Management-side handoff between one host artifact and one external backup job. */
public interface ThirdPartyBackupVolumeService {
    interface Lease extends AutoCloseable {
        @Override void close();
    }

    /** Serialize parent selection with deletion of artifacts shared by an incremental chain. */
    Lease acquireLifecycle(long vmId);

    @FunctionalInterface
    interface ArtifactCleanup {
        /** Policy expiration permits orphan job cleanup only; operator deletion may erase retained artifacts. */
        DeleteResult delete(ThirdPartyBackupManifest.Artifact artifact, boolean metadata, String pendingTicket, boolean policyExpiration);
    }

    class DeleteResult {
        public final boolean completed;
        public final String pendingTicket;
        public DeleteResult(boolean completed, String pendingTicket) {
            this.completed = completed;
            this.pendingTicket = pendingTicket;
        }
    }

    @FunctionalInterface
    interface ArtifactInventory {
        /** Failures must throw; an incomplete or failed query must never report absence. */
        boolean exists(ThirdPartyBackupManifest.Artifact artifact);
    }

    /** Delete owned image jobs and the metadata job, retaining persisted progress until local cleanup succeeds. */
    boolean delete(Backup backup, ArtifactCleanup cleanup);

    /** Check every required image and metadata reference; keep partial groups available for explicit cleanup. */
    void reconcileCatalog(Backup backup, ArtifactInventory inventory, ArtifactCleanup cleanup);

    /** Retention sync removes a successfully deleted logical group with resource accounting exactly once. */
    void removeDeletedBackup(Backup backup);

    @FunctionalInterface
    interface Transfer {
        /** Submit when jobId is empty; otherwise poll that exact saved job without waiting. */
        ThirdPartyBackupManifest.Artifact backup(ThirdPartyBackupManifest.Artifact artifact, boolean metadata);

        /** Read-only discovery/polling. Never submit a job when acceptance is uncertain. */
        default ThirdPartyBackupManifest.Artifact recover(ThirdPartyBackupManifest.Artifact artifact, boolean metadata) { return null; }

        /** Verify an operator-supplied ID against the exact artifact before polling it. */
        default ThirdPartyBackupManifest.Artifact link(ThirdPartyBackupManifest.Artifact artifact, boolean metadata, String jobId) {
            throw new UnsupportedOperationException("External backup Job ID verification is unavailable");
        }

        /** An external UI backup's pre/post hooks must finish before its child jobs may start. */
        default boolean ready() { return true; }

        /** Persist engine checkpoint metadata after the Host finishes the entire logical backup. */
        default void completed(Backup backup) { }

        /** A failed checkpoint must not be reused as a healthy incremental source. */
        default void failed(Backup backup) { }
    }

    /** Returns true when this is a volume pipeline (including when it is waiting). */
    boolean reconcile(Backup backup, Host host, Transfer transfer);

    /** Drive accepted jobs independently of browser polling and resume them during provider sync. */
    void track(Backup backup, Host host, Transfer transfer);

    @FunctionalInterface
    interface RestoreTransfer {
        /** Submit once when saved.jobId is empty; otherwise poll that exact external restore. */
        ThirdPartyBackupRestore.Result restore(ThirdPartyBackupRestore.Request request, ThirdPartyBackupRestore.Result saved);

        /** Recover a durable receipt or an exact external reference without starting another restore. */
        default ThirdPartyBackupRestore.Result recover(ThirdPartyBackupRestore.Request request, ThirdPartyBackupRestore.Result saved) {
            saved.recoveryOnly = true;
            try { return restore(request, saved); } finally { saved.recoveryOnly = false; }
        }

        /** Exact request receipts/correlation must prove ownership independently of the supplied ID. */
        default ThirdPartyBackupRestore.Result link(ThirdPartyBackupRestore.Request request,
                ThirdPartyBackupRestore.Result saved, String jobId) {
            ThirdPartyBackupRestore.Result probe = new com.google.gson.Gson().fromJson(
                    new com.google.gson.Gson().toJson(saved), ThirdPartyBackupRestore.Result.class);
            probe.jobId = null;
            ThirdPartyBackupRestore.Result result = recover(request, probe);
            if (result == null || !jobId.equals(result.jobId)) {
                throw new IllegalStateException("External restore Job ID cannot be verified from the exact request receipt or correlation");
            }
            return result;
        }

        /** Remove only operation receipts after the Host transaction and staging cleanup completes. */
        default void cleanup(ThirdPartyBackupRestore.Plan plan) { }
    }

    ThirdPartyBackupRestore.Plan prepareRestore(Backup backup, Host host, java.util.List<String> volumeUuids,
            java.util.List<Long> targetVolumeBytes, String targetVmName, int timeout);

    /** Persist dispatch intent; reject attempts already fenced by start-failure reconciliation. */
    void markRestoreDispatch(Backup backup, ThirdPartyBackupRestore.Plan plan);

    Host selectRestoreHost(com.cloud.vm.VirtualMachine vm, String provider, java.util.List<Long> poolIds,
            String hostIdentifier, long requiredBytes);

    Host selectBackupHost(com.cloud.vm.VirtualMachine vm, String provider, java.util.List<Long> poolIds, long requiredBytes);

    /** Resume an accepted restore from persisted selections and external job IDs. */
    void trackRestore(Backup backup, Host host, RestoreTransfer transfer);

    Host getWorkerHost(Backup backup, String operation);

    BackupStagingInfoResponse inspect(Backup backup, String operation, String stagingJobId);

    BackupStagingInfoResponse manage(Backup backup, Host workerHost, String operation, String jobId,
            String action, Integer artifactIndex, String externalJobId, Transfer transfer, RestoreTransfer restoreTransfer,
            ArtifactInventory inventory);

    /** Cancel only the exact queued attempt, before Host admission; never cancels an active writer. */
    boolean cancelWaiting(Backup backup, String operation, String jobId);
}
