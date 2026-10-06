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

import java.util.List;

/** Persisted restore destination and exact catalog selections, independent of the source Host. */
public final class ThirdPartyBackupRestore {
    public static final String PLAN_KEY = "thirdparty.volume.restore.plan";
    public static final String HISTORY_PREFIX = "thirdparty.volume.restore.history.";
    public static final int HISTORY_VERSION = 1;
    public static final int START_PROTOCOL_VERSION = 1;
    public static final String TRANSFER_KEY = "thirdparty.volume.restore.transfer";
    public static final String CLEANUP_STATE_KEY = "thirdparty.volume.restore.cleanup.state";
    public static final String CLEANUP_DETAILS_KEY = "thirdparty.volume.restore.cleanup.details";

    private ThirdPartyBackupRestore() { }

    public static class Plan {
        public String jobId;
        public long hostId;
        public String hostName;
        public String stageRoot;
        public String destination;
        public int bufferPercent;
        public int timeout;
        public int queueTimeout;
        /** Host must acknowledge a durable start receipt before this plan can be dispatched. */
        public int startProtocolVersion;
        public int primaryCapacityVersion;
        public int vmResultVersion;
        public ThirdPartyBackupManifest manifest;
        public List<String> volumeUuids;
        /** Keep the current provisioned disk capacity when restoring an older, smaller backup. */
        public List<Long> targetVolumeBytes;
        /** Host journal identifies the actual restore target, including restore into another VM. */
        public String targetVmName;
    }

    public static class Request {
        public String jobId;
        public int sequence;
        public int volumeIndex;
        public int chainIndex;
        public boolean metadata;
        /** Exact restored file/directory; providers restore its basename into its parent. */
        public String destination;
        public ThirdPartyBackupManifest.Artifact artifact;
    }

    /** Each attempt keeps its original worker/destination even after a later attempt starts. */
    public static class Operation {
        public int version = HISTORY_VERSION;
        public Plan plan;
        public long createdAt;
        public long updatedAt;
        public int latestSequence = -1;
        public boolean legacy;
        public int legacyThroughSequence = -1;
        public String hostState;
        public long hostCheckedAt;
        public String hostError;
        public String cleanupState;
        public String cleanupReason;
        public String startState;
        public long startSubmittedAt;
        public long startCheckedAt;
        public String startFailureReason;
        public VmResult vmResult;
        public long vmResultCheckedAt;
        public String vmResultError;
    }

    /** Actual primary volume transaction result, independent of external file restore jobs. */
    public static class VmResult {
        public int version = 1;
        public String jobId;
        public String sourceBackupUuid;
        public String provider;
        public String targetVmName;
        public String transactionId;
        public long revision;
        public long updatedAt;
        public String phase;
        public String outcome;
        public String primaryCleanupState;
        public String failure;
        public String recoveryError;
        public long preparedAt;
        public long switchStartedAt;
        public long committedAt;
        public long rollbackStartedAt;
        public long rolledBackAt;
        public long primaryCleanupCompletedAt;
        public List<VmVolumeResult> volumes = new java.util.ArrayList<>();

        public void validate(Plan plan) {
            if (plan == null || version != 1 || !java.util.Objects.equals(plan.jobId, jobId)
                    || !java.util.Objects.equals(plan.manifest.getBackupUuid(), sourceBackupUuid)
                    || !java.util.Objects.equals(plan.manifest.getProvider(), provider)
                    || !java.util.Objects.equals(plan.targetVmName, targetVmName) || revision <= 0 || updatedAt <= 0
                    || volumes == null || volumes.size() != plan.volumeUuids.size()
                    || !java.util.Set.of("NOT_STARTED", "RUNNING", "COMMITTED", "ROLLED_BACK", "FAILED", "UNKNOWN", "RECOVERY_REQUIRED").contains(outcome == null ? "" : outcome)
                    || !java.util.Set.of("WAITING", "PREPARING", "PREPARED", "SWITCHING", "COMMITTED", "ROLLING_BACK", "ROLLED_BACK",
                            "COMPLETED", "FAILED", "START_FAILED", "COMMIT_UNCONFIRMED").contains(phase == null ? "" : phase)
                    || !java.util.Set.of("NOT_REQUIRED", "NOT_STARTED", "RUNNING", "WAITING", "COMPLETED")
                            .contains(primaryCleanupState == null ? "" : primaryCleanupState)) {
                throw new IllegalArgumentException("VM volume result does not match the exact recorded restore attempt");
            }
            boolean transaction = transactionId != null && !transactionId.isEmpty();
            if (java.util.Set.of("RUNNING", "COMMITTED", "ROLLED_BACK", "UNKNOWN", "RECOVERY_REQUIRED").contains(outcome) != transaction
                    || ("COMMITTED".equals(outcome) && committedAt <= 0) || ("ROLLED_BACK".equals(outcome) && rolledBackAt <= 0)) {
                throw new IllegalArgumentException("VM transaction outcome lacks its required journal proof");
            }
            for (int i = 0; i < volumes.size(); i++) {
                VmVolumeResult volume = volumes.get(i);
                if (volume == null || volume.index != i || !plan.volumeUuids.get(i).equals(volume.volumeUuid)
                        || (transaction && (volume.poolUuid == null || volume.destination == null || volume.hadOriginal == null))
                        || ("COMMITTED".equals(outcome) && (!"PREPARED".equals(volume.prepareState) || !"SWITCHED".equals(volume.switchState)))
                        || ("ROLLED_BACK".equals(outcome) && !"ROLLED_BACK".equals(volume.rollbackState))) {
                    throw new IllegalArgumentException("VM result differs from the selected restore volume order or confirmed transaction outcome");
                }
            }
        }
    }

    public static class VmVolumeResult {
        public int index;
        public String volumeUuid;
        public String poolUuid;
        public String destination;
        public Boolean hadOriginal;
        public String prepareState;
        public String switchState;
        public String rollbackState;
        public String cleanupState;
        public String failure;
        public long prepareStartedAt;
        public long preparedAt;
        public long switchStartedAt;
        public long switchedAt;
        public long rollbackStartedAt;
        public long rolledBackAt;
        public long cleanedAt;
    }

    /** Kept outside removable Host job files so a delayed command cannot reopen a failed start. */
    public static class StartReceipt {
        public int version = START_PROTOCOL_VERSION;
        public Plan plan;
        public String state;
        public long checkedAt;
        public String reason;
    }

    /** A request is immutable after its first observation; all mutations require the attempt's DB lock. */
    public static class Record {
        public int version = HISTORY_VERSION;
        public Request request;
        public Result result;
        public long recordedAt;
        public long updatedAt;
        public long checkedAt;
        public long completedAt;
        public long terminalAt;
        public long acknowledgedAt;
        public String queryError;
        public boolean legacy;
    }


    /** Keep detail names short even when the restore job ID contains VM and volume names. */
    public static String historyPrefix(String jobId) {
        if (jobId == null || jobId.isEmpty()) { throw new IllegalArgumentException("Missing restore job ID"); }
        return HISTORY_PREFIX + java.util.UUID.nameUUIDFromBytes(jobId.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + ".";
    }

    public static String operationKey(String jobId) { return historyPrefix(jobId) + "operation"; }
    public static String recordKey(String jobId, int sequence) { return historyPrefix(jobId) + sequence; }

    public static class Result {
        public String jobId;
        public boolean completed;
        public boolean submissionPending;
        public boolean notSubmitted;
        /** Additional exact external sessions (for example Veeam FLR mount) owned by this request. */
        public java.util.List<String> callbackJobIds = new java.util.ArrayList<>();
        public int sequence;
        public String failure;
        public boolean externalTerminal;
        public boolean recoveryOnly;
        public long submittedAt;

        public Result() { }
        public Result(String jobId, boolean completed) {
            this.jobId = jobId;
            this.completed = completed;
            this.externalTerminal = completed;
        }

        public static Result failed(String jobId, String reason) {
            Result result = new Result(jobId, false);
            result.failure = reason;
            result.externalTerminal = true;
            return result;
        }

        public static Result notSubmitted(String reason) {
            Result result = failed(null, reason);
            result.notSubmitted = true;
            return result;
        }
    }
}
