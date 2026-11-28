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
package com.cloud.hypervisor.kvm.resource.wrapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.cloud.agent.api.AblestackN2KStatusAnswer;
import com.cloud.agent.api.AblestackN2KStatusCommand;
import com.cloud.agent.api.Answer;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;
import com.cloud.utils.script.OutputInterpreter;
import com.cloud.utils.script.Script;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.commons.lang3.StringUtils;

@ResourceWrapper(handles = AblestackN2KStatusCommand.class)
public class LibvirtAblestackN2KStatusCommandWrapper extends CommandWrapper<AblestackN2KStatusCommand, Answer, LibvirtComputingResource> {

    @Override
    public Answer execute(AblestackN2KStatusCommand cmd, LibvirtComputingResource serverResource) {
        if (StringUtils.isBlank(cmd.getWorkdir())) {
            return new AblestackN2KStatusAnswer(cmd, false, "Missing workdir for ablestack_n2k status command");
        }

        final long timeout = (long) cmd.getWait() * 1000;
        Script script = new Script("ablestack_n2k", timeout, logger);
        script.add("--workdir", cmd.getWorkdir());
        script.add("--json");
        script.add("status");

        OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
        String result = script.execute(parser);
        int exitValue = script.getExitValue();
        if (exitValue != 0) {
            return new AblestackN2KStatusAnswer(cmd, false,
                    StringUtils.defaultIfBlank(result, parser.getLines()));
        }

        String output = StringUtils.defaultIfBlank(parser.getLines(), StringUtils.defaultString(result));
        try {
            JsonObject status = new JsonParser().parse(output).getAsJsonObject();
            JsonObject resume = getObject(status, "resume");
            JsonObject runtime = getObject(status, "runtime");
            JsonObject phases = getObject(status, "phases");
            JsonObject progress = getObject(runtime, "progress");

            JsonObject cloudRuntime = getObject(runtime, "cloud");
            JsonObject checkpoint = getObject(cloudRuntime, "checkpoint");
            String completedCloudVmId = getBoolean(checkpoint, "complete")
                    && "ablestack-cloud".equals(getString(cloudRuntime, "provider")) ? getString(checkpoint, "vm_id") : null;
            boolean completedCloudCutover = StringUtils.isNotBlank(completedCloudVmId);
            String phase = completedCloudCutover ? "phase2" : resolvePhase(runtime, phases, resume, progress);
            String migrationState = completedCloudCutover ? "completed" : resolveMigrationState(runtime, phases, resume, progress);
            String failedPhase = "phase2".equals(phase) && "completed".equals(migrationState)
                    ? null : readFailedRunnerPhase(cmd.getWorkdir(), phase);
            if (failedPhase != null) {
                phase = failedPhase;
                migrationState = "failed";
            }
            String migrationStep = StringUtils.defaultIfBlank(getString(resume, "next_step"),
                    StringUtils.defaultIfBlank(getString(resume, "last_step"),
                            StringUtils.defaultIfBlank(getString(progress, "next_step"), getString(progress, "last_step"))));
            if (failedPhase != null) {
                migrationStep = failedPhase + "_failed";
            }
            String syncPhysical = getPercent(resume);
            String workdir = StringUtils.defaultIfBlank(getString(status, "workdir"), cmd.getWorkdir());
            JsonObject target = getObject(status, "target");
            JsonObject targetResult = getObject(target, "result");
            String targetProvider = StringUtils.defaultIfBlank(getString(targetResult, "provider"),
                    StringUtils.defaultIfBlank(getString(cloudRuntime, "provider"), getString(target, "provider")));
            String cloudVmId = StringUtils.defaultIfBlank(completedCloudVmId,
                    StringUtils.defaultIfBlank(getString(targetResult, "vm_id"), getString(cloudRuntime, "vm_id")));
            AblestackN2KStatusAnswer answer = new AblestackN2KStatusAnswer(cmd, true, "OK", phase, migrationState, migrationStep,
                    syncPhysical, workdir, status.toString(), targetProvider, cloudVmId);
            applyStructuredProgress(answer, status);
            if (failedPhase != null) {
                answer.setDisplayStep(failedPhase.equals("phase2") ? "PHASE2_FAILED" : "PHASE1_FAILED");
            }
            return answer;
        } catch (RuntimeException e) {
            return new AblestackN2KStatusAnswer(cmd, false,
                    String.format("Unable to parse ablestack_n2k status output for workdir %s: %s", cmd.getWorkdir(), e.getMessage()));
        }
    }

    private String readFailedRunnerPhase(String workdir, String inferredPhase) {
        Path statePath = Path.of(workdir, "runner.json");
        if (!Files.isRegularFile(statePath) || !Files.isReadable(statePath)) {
            return null;
        }
        try {
            JsonObject runner = new JsonParser().parse(Files.readString(statePath)).getAsJsonObject();
            String phase = getString(runner, "split");
            if ("failed".equals(getString(runner, "state"))) {
                return "phase1".equals(phase) || "phase2".equals(phase) ? phase : inferredPhase;
            }
        } catch (IOException | RuntimeException e) {
            logger.debug("Unable to read the ablestack_n2k runner state for workdir {}", workdir);
        }
        return null;
    }

    private void applyStructuredProgress(AblestackN2KStatusAnswer answer, JsonObject status) {
        answer.setDisplayStep(StringUtils.defaultIfBlank(getString(status, "display_step"), answer.getMigrationStep()));
        JsonObject syncProgress = getObject(status, "sync_progress");
        if (syncProgress != null) {
            answer.setSyncProgressLabel(getString(syncProgress, "mode"));
            answer.setSyncDoneBytes(getLong(syncProgress, "done_bytes"));
            answer.setSyncTotalBytes(getLong(syncProgress, "total_bytes"));
            answer.setSyncPercent(getInteger(syncProgress, "percent"));
        }
        JsonObject syncTotal = getObject(status, "sync_total");
        if (syncTotal != null) {
            answer.setSyncCumulativeDoneBytes(getLong(syncTotal, "done_bytes"));
            answer.setSyncCumulativeKnownBytes(getLong(syncTotal, "known_total_bytes"));
            answer.setSyncCumulativePercent(getInteger(syncTotal, "percent"));
        }
    }

    private String resolvePhase(JsonObject runtime, JsonObject phases, JsonObject resume, JsonObject progress) {
        JsonObject split = getObject(runtime, "split");
        if (isDone(getObject(split, "phase2")) || isDone(getObject(phases, "cutover"))) {
            return "phase2";
        }
        if (isPhase1Done(split, phases, progress)) {
            return "phase1";
        }
        String nextStep = getString(resume, "next_step");
        if (StringUtils.contains(nextStep, "phase2")) {
            return "phase1";
        }
        String lastStep = getString(resume, "last_step");
        if (StringUtils.contains(lastStep, "final") || StringUtils.contains(lastStep, "cutover")) {
            return "phase2";
        }
        return "phase1";
    }

    private String resolveMigrationState(JsonObject runtime, JsonObject phases, JsonObject resume, JsonObject progress) {
        if (getBoolean(resume, "completed") || isDone(getObject(getObject(runtime, "split"), "phase2")) || isDone(getObject(phases, "cutover"))) {
            return "completed";
        }
        if (isPhase1Done(getObject(runtime, "split"), phases, progress)) {
            return "completed";
        }
        return "running";
    }

    private boolean isPhase1Done(JsonObject split, JsonObject phases, JsonObject progress) {
        if (isDone(getObject(split, "phase1"))) {
            return true;
        }
        String lastStep = StringUtils.defaultIfBlank(getString(progress, "last_step"), getString(progress, "step"));
        if (StringUtils.equalsIgnoreCase(lastStep, "phase1_done") || StringUtils.equalsIgnoreCase(lastStep, "phase1_completed")) {
            return true;
        }
        return isDone(getObject(phases, "base_sync")) && isDone(getObject(phases, "incr_sync")) &&
                !isDone(getObject(phases, "final_sync")) && !isDone(getObject(phases, "cutover"));
    }

    private boolean isDone(JsonObject object) {
        return object != null && getBoolean(object, "done");
    }

    private String getPercent(JsonObject object) {
        JsonElement element = object != null ? object.get("percent") : null;
        if (element == null || element.isJsonNull()) {
            return null;
        }
        return element.getAsString() + "%";
    }

    private JsonObject getObject(JsonObject object, String memberName) {
        if (object == null) {
            return null;
        }
        JsonElement element = object.get(memberName);
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        return element.getAsJsonObject();
    }

    private String getString(JsonObject object, String memberName) {
        if (object == null) {
            return null;
        }
        JsonElement element = object.get(memberName);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return null;
        }
        return element.getAsString();
    }

    private boolean getBoolean(JsonObject object, String memberName) {
        if (object == null) {
            return false;
        }
        JsonElement element = object.get(memberName);
        return element != null && element.isJsonPrimitive() && element.getAsBoolean();
    }

    private Long getLong(JsonObject object, String memberName) {
        if (object == null) {
            return null;
        }
        JsonElement element = object.get(memberName);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return null;
        }
        try {
            return element.getAsLong();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Integer getInteger(JsonObject object, String memberName) {
        Long value = getLong(object, memberName);
        return value != null ? value.intValue() : null;
    }
}
