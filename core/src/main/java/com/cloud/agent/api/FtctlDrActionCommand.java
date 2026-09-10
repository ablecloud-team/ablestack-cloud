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
package com.cloud.agent.api;

import java.util.HashMap;
import java.util.Map;

public class FtctlDrActionCommand extends Command {

    public static final String ACTION_CONTRACT_VERSION = "2026-08-06";
    public static final String REPROTECT_AUTHORITY_CONTRACT_VERSION = "2026-08-26";

    public enum Action {
        SYNC("dr-sync-start"),
        RECOVER_SYNC("dr-sync-recover"),
        PAUSE_SYNC("dr-sync-pause"),
        RESUME_SYNC("dr-sync-resume"),
        TEST_FAILOVER("dr-test-failover"),
        TEST_CLEANUP("dr-test-cleanup"),
        TEST_PREPARE("dr-test-prepare"),
        TEST_ARTIFACT_CLEANUP("dr-test-artifact-cleanup"),
        FAILOVER("dr-failover"),
        FAILBACK("dr-failback"),
        REPROTECT("dr-reprotect"),
        TARGET_MATERIALIZED("dr-target-materialized"),
        CHECKPOINT_RESTORE("dr-checkpoint-restore"),
        CHECKPOINT_PUBLISH("dr-checkpoint-publish"),
        CHECKPOINT_ACK("dr-checkpoint-ack"),
        TARGET_EXPORT_START("dr-target-export-start"),
        TARGET_EXPORT_STOP("dr-target-export-stop"),
        CUTOVER_COMMIT("dr-cutover-commit"),
        CUTOVER_COMMIT_STATUS("dr-cutover-commit-status"),
        FAILOVER_ABORT("dr-failover-abort"),
        FAILBACK_COMMIT("dr-failback-commit"),
        FAILBACK_COMMIT_STATUS("dr-failback-commit-status"),
        FAILBACK_ABORT("dr-failback-abort"),
        RELEASE("dr-release");

        private final String cliCommand;

        Action(String cliCommand) {
            this.cliCommand = cliCommand;
        }

        public String getCliCommand() {
            return cliCommand;
        }
    }

    private Action action;
    private String actionName;
    private String cliCommand;
    private String planUuid;
    private String runUuid;
    private String runType;
    private String actionIntent;
    private String direction;
    private String role;
    private String sourceWorkerUuid;
    private String targetWorkerUuid;
    private String coordinatorWorkerUuid;
    @LogLevel(LogLevel.Log4jLevel.Off)
    private String profileJson;
    @LogLevel(LogLevel.Log4jLevel.Off)
    private String requestJson;
    private String artifactContractVersion;
    @LogLevel(LogLevel.Log4jLevel.Off)
    private String artifactSpecJson;
    private String authorityContractVersion;
    @LogLevel(LogLevel.Log4jLevel.Off)
    private String authoritySpecJson;
    private String mode;
    private String checkpointRef;
    private Long resumeBaselineCheckpointSequence;
    private Long minimumCompletedCheckpointSequence;
    private Long authoritySequenceFloor;
    private boolean forceImmediateCycle;
    private String cutoverCommitContractVersion;
    private String cutoverEngineSessionId;
    private String cutoverCloudSessionId;
    private Long cutoverCheckpointSequence;
    private String cutoverManifestSha256;
    private Long cutoverAuthorityGeneration;
    private String cutoverCommitAttemptId;
    private String cutoverCommitEnvelopeSha256;
    private Long cutoverTargetVmId;
    private String cutoverTargetExternalRef;
    private String cutoverTargetPowerState;
    private String cutoverBootValidationState;
    private String cutoverSourceFenceState;
    private String cutoverSourcePowerState;
    private String failbackCommitContractVersion;
    private String failbackSessionId;
    private Long failbackCheckpointSequence;
    private Long failbackAuthorityGeneration;
    private Long failbackBaselineGeneration;
    private String failbackEvidenceRunUuid;
    private String failbackCommitAttemptId;
    private String failbackCommitEnvelopeSha256;
    private String failbackTargetPowerState;
    private String failbackSourcePowerState;
    private String failbackBootValidationState;
    @Deprecated
    private Long restorePointId;
    private boolean force;
    private boolean dryRun;
    private boolean waitForCompletion;
    @LogLevel(LogLevel.Log4jLevel.Off)
    private Map<String, String> context = new HashMap<>();

    public FtctlDrActionCommand() {
    }

    public FtctlDrActionCommand(Action action, String planUuid, String runUuid) {
        this.action = action;
        if (action != null) {
            this.actionName = action.name();
            this.cliCommand = action.getCliCommand();
        }
        this.planUuid = planUuid;
        this.runUuid = runUuid;
    }

    public Action getAction() {
        return action;
    }

    public void setAction(Action action) {
        this.action = action;
        if (action != null) {
            this.actionName = action.name();
            this.cliCommand = action.getCliCommand();
        }
    }

    public String getActionName() {
        if (actionName != null && !actionName.trim().isEmpty()) {
            return actionName;
        }
        return action != null ? action.name() : null;
    }

    public void setActionName(String actionName) {
        this.actionName = actionName;
    }

    public String getCliCommand() {
        if (cliCommand != null && !cliCommand.trim().isEmpty()) {
            return cliCommand;
        }
        return action != null ? action.getCliCommand() : null;
    }

    public void setCliCommand(String cliCommand) {
        this.cliCommand = cliCommand;
    }

    public String getPlanUuid() {
        return planUuid;
    }

    public String getRunUuid() {
        return runUuid;
    }

    public String getRunType() {
        return runType;
    }

    public void setRunType(String runType) {
        this.runType = runType;
    }

    public String getActionIntent() {
        return actionIntent;
    }

    public void setActionIntent(String actionIntent) {
        this.actionIntent = actionIntent;
    }

    public String getDirection() {
        return direction;
    }

    public void setDirection(String direction) {
        this.direction = direction;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getSourceWorkerUuid() {
        return sourceWorkerUuid;
    }

    public void setSourceWorkerUuid(String sourceWorkerUuid) {
        this.sourceWorkerUuid = sourceWorkerUuid;
    }

    public String getTargetWorkerUuid() {
        return targetWorkerUuid;
    }

    public void setTargetWorkerUuid(String targetWorkerUuid) {
        this.targetWorkerUuid = targetWorkerUuid;
    }

    public String getCoordinatorWorkerUuid() {
        return coordinatorWorkerUuid;
    }

    public void setCoordinatorWorkerUuid(String coordinatorWorkerUuid) {
        this.coordinatorWorkerUuid = coordinatorWorkerUuid;
    }

    public String getProfileJson() {
        return profileJson;
    }

    public void setProfileJson(String profileJson) {
        this.profileJson = profileJson;
    }

    public String getRequestJson() {
        return requestJson;
    }

    public void setRequestJson(String requestJson) {
        this.requestJson = requestJson;
    }

    public String getArtifactContractVersion() {
        return artifactContractVersion;
    }

    public void setArtifactContractVersion(String artifactContractVersion) {
        this.artifactContractVersion = artifactContractVersion;
    }

    public String getArtifactSpecJson() {
        return artifactSpecJson;
    }

    public void setArtifactSpecJson(String artifactSpecJson) {
        this.artifactSpecJson = artifactSpecJson;
    }

    public String getAuthorityContractVersion() {
        return authorityContractVersion;
    }

    public void setAuthorityContractVersion(String authorityContractVersion) {
        this.authorityContractVersion = authorityContractVersion;
    }

    public String getAuthoritySpecJson() {
        return authoritySpecJson;
    }

    public void setAuthoritySpecJson(String authoritySpecJson) {
        this.authoritySpecJson = authoritySpecJson;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getCheckpointRef() {
        return checkpointRef;
    }

    public void setCheckpointRef(String checkpointRef) {
        this.checkpointRef = checkpointRef;
    }

    public Long getResumeBaselineCheckpointSequence() {
        return resumeBaselineCheckpointSequence;
    }

    public void setResumeBaselineCheckpointSequence(Long resumeBaselineCheckpointSequence) {
        this.resumeBaselineCheckpointSequence = resumeBaselineCheckpointSequence;
    }

    public Long getMinimumCompletedCheckpointSequence() {
        return minimumCompletedCheckpointSequence;
    }

    public void setMinimumCompletedCheckpointSequence(Long minimumCompletedCheckpointSequence) {
        this.minimumCompletedCheckpointSequence = minimumCompletedCheckpointSequence;
    }

    public Long getAuthoritySequenceFloor() {
        return authoritySequenceFloor;
    }

    public void setAuthoritySequenceFloor(Long authoritySequenceFloor) {
        this.authoritySequenceFloor = authoritySequenceFloor;
    }

    public boolean isForceImmediateCycle() {
        return forceImmediateCycle;
    }

    public void setForceImmediateCycle(boolean forceImmediateCycle) {
        this.forceImmediateCycle = forceImmediateCycle;
    }

    public String getCutoverCommitContractVersion() { return cutoverCommitContractVersion; }
    public void setCutoverCommitContractVersion(String value) { cutoverCommitContractVersion = value; }
    public String getCutoverEngineSessionId() { return cutoverEngineSessionId; }
    public void setCutoverEngineSessionId(String value) { cutoverEngineSessionId = value; }
    public String getCutoverCloudSessionId() { return cutoverCloudSessionId; }
    public void setCutoverCloudSessionId(String value) { cutoverCloudSessionId = value; }
    public Long getCutoverCheckpointSequence() { return cutoverCheckpointSequence; }
    public void setCutoverCheckpointSequence(Long value) { cutoverCheckpointSequence = value; }
    public String getCutoverManifestSha256() { return cutoverManifestSha256; }
    public void setCutoverManifestSha256(String value) { cutoverManifestSha256 = value; }
    public Long getCutoverAuthorityGeneration() { return cutoverAuthorityGeneration; }
    public void setCutoverAuthorityGeneration(Long value) { cutoverAuthorityGeneration = value; }
    public String getCutoverCommitAttemptId() { return cutoverCommitAttemptId; }
    public void setCutoverCommitAttemptId(String value) { cutoverCommitAttemptId = value; }
    public String getCutoverCommitEnvelopeSha256() { return cutoverCommitEnvelopeSha256; }
    public void setCutoverCommitEnvelopeSha256(String value) { cutoverCommitEnvelopeSha256 = value; }
    public Long getCutoverTargetVmId() { return cutoverTargetVmId; }
    public void setCutoverTargetVmId(Long value) { cutoverTargetVmId = value; }
    public String getCutoverTargetExternalRef() { return cutoverTargetExternalRef; }
    public void setCutoverTargetExternalRef(String value) { cutoverTargetExternalRef = value; }
    public String getCutoverTargetPowerState() { return cutoverTargetPowerState; }
    public void setCutoverTargetPowerState(String value) { cutoverTargetPowerState = value; }
    public String getCutoverBootValidationState() { return cutoverBootValidationState; }
    public void setCutoverBootValidationState(String value) { cutoverBootValidationState = value; }
    public String getCutoverSourceFenceState() { return cutoverSourceFenceState; }
    public void setCutoverSourceFenceState(String value) { cutoverSourceFenceState = value; }
    public String getCutoverSourcePowerState() { return cutoverSourcePowerState; }
    public void setCutoverSourcePowerState(String value) { cutoverSourcePowerState = value; }

    public String getFailbackCommitContractVersion() { return failbackCommitContractVersion; }
    public void setFailbackCommitContractVersion(String value) { failbackCommitContractVersion = value; }
    public String getFailbackSessionId() { return failbackSessionId; }
    public void setFailbackSessionId(String value) { failbackSessionId = value; }
    public Long getFailbackCheckpointSequence() { return failbackCheckpointSequence; }
    public void setFailbackCheckpointSequence(Long value) { failbackCheckpointSequence = value; }
    public Long getFailbackAuthorityGeneration() { return failbackAuthorityGeneration; }
    public void setFailbackAuthorityGeneration(Long value) { failbackAuthorityGeneration = value; }
    public Long getFailbackBaselineGeneration() { return failbackBaselineGeneration; }
    public void setFailbackBaselineGeneration(Long value) { failbackBaselineGeneration = value; }
    public String getFailbackEvidenceRunUuid() { return failbackEvidenceRunUuid; }
    public void setFailbackEvidenceRunUuid(String value) { failbackEvidenceRunUuid = value; }
    public String getFailbackCommitAttemptId() { return failbackCommitAttemptId; }
    public void setFailbackCommitAttemptId(String value) { failbackCommitAttemptId = value; }
    public String getFailbackCommitEnvelopeSha256() { return failbackCommitEnvelopeSha256; }
    public void setFailbackCommitEnvelopeSha256(String value) { failbackCommitEnvelopeSha256 = value; }
    public String getFailbackTargetPowerState() { return failbackTargetPowerState; }
    public void setFailbackTargetPowerState(String value) { failbackTargetPowerState = value; }
    public String getFailbackSourcePowerState() { return failbackSourcePowerState; }
    public void setFailbackSourcePowerState(String value) { failbackSourcePowerState = value; }
    public String getFailbackBootValidationState() { return failbackBootValidationState; }
    public void setFailbackBootValidationState(String value) { failbackBootValidationState = value; }

    public Long getRestorePointId() {
        return restorePointId;
    }

    public void setRestorePointId(Long restorePointId) {
        this.restorePointId = restorePointId;
    }

    public boolean isForce() {
        return force;
    }

    public void setForce(boolean force) {
        this.force = force;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

    public boolean isWaitForCompletion() {
        return waitForCompletion;
    }

    public void setWaitForCompletion(boolean waitForCompletion) {
        this.waitForCompletion = waitForCompletion;
    }

    public Map<String, String> getContext() {
        return context;
    }

    public void setContext(Map<String, String> context) {
        this.context = context == null ? new HashMap<>() : context;
    }

    public void setContextParam(String key, String value) {
        if (key != null && value != null) {
            context.put(key, value);
        }
    }

    @Override
    public boolean executeInSequence() {
        return false;
    }
}
