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
package com.cloud.dr;

import java.nio.file.Paths;
import java.util.UUID;
import javax.inject.Inject;
import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.FtctlDrActionAnswer;
import com.cloud.agent.api.FtctlDrActionCommand;
import com.cloud.dr.adapter.ftctl.FtctlDrUnifiedActionAdapter;
import com.cloud.dr.dao.DrEventDao;
import com.cloud.dr.dao.DrPlanDao;
import com.cloud.dr.dao.DrRestorePointDao;
import com.cloud.dr.dao.DrRunDao;
import com.cloud.dr.dao.DrTestSessionDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class DrCheckpointCleanupService extends ManagerBase {
    @Inject private DrPlanDao plans;
    @Inject private DrRunDao runs;
    @Inject private DrTestSessionDao tests;
    @Inject private DrRestorePointDao checkpoints;
    @Inject private DrEventDao events;
    @Inject private DrWorkerPlacementService placement;
    @Inject private FtctlDrUnifiedActionAdapter adapter;
    @Inject private AgentManager agents;

    public JsonObject records() {
        JsonArray records = new JsonArray();
        for (DrEventVO event : events.listCleanupRecords()) {
            JsonObject item = JsonParser.parseString(event.getDetailsJson()).getAsJsonObject();
            item.addProperty("id", event.getUuid());
            item.addProperty("created", event.getCreated().toInstant().toString());
            records.add(item);
        }
        JsonObject result = new JsonObject();
        result.add("records", records);
        return result;
    }

    public void preserveUnregistered(DrPlanVO plan) {
        JsonObject snapshot = new JsonObject();
        snapshot.addProperty("planUuid", plan.getUuid());
        snapshot.addProperty("planName", plan.getName());
        snapshot.addProperty("targetSiteId", plan.getTargetSiteId());
        snapshot.addProperty("state", "REMOTE_CLEANUP_UNVERIFIED");
        snapshot.addProperty("disposition", "VM_AND_VOLUME_PRESERVED");
        try {
            snapshot.add("disks", inventorySpec(plan).getAsJsonArray("disks"));
        } catch (RuntimeException e) {
            snapshot.addProperty("inventoryState", "MAPPING_INCOMPLETE");
        }
        // Independent evidence: deliberately no plan_id FK or credential/profile JSON.
        DrEventVO record = new DrEventVO("DR_UNREGISTERED_RESOURCES", "WARN", "CLOUD");
        record.setMessage("Plan unregistered; remote resource cleanup is not confirmed");
        record.setDetailsJson(snapshot.toString());
        if (events.persist(record) == null) {
            throw new CloudRuntimeException("DR_CLEANUP_RECORD_PERSIST_FAILED");
        }
    }

    JsonObject inventorySpec(DrPlanVO plan) {
        JsonObject spec = JsonParser.parseString(adapter.buildCheckpointInventorySpec(plan)).getAsJsonObject();
        for (JsonElement element : spec.getAsJsonArray("disks")) {
            JsonObject disk = element.getAsJsonObject();
            if ("FILE".equals(disk.get("provider").getAsString())) {
                disk.addProperty("storageRoot", Paths.get(disk.get("canonicalLocator").getAsString().substring(5)).getParent().toString());
            }
        }
        return spec;
    }

    public JsonObject manage(String planUuid, Integer keepCount, Integer keepDays, String selection, String reason, boolean savePolicy) {
        UUID.fromString(planUuid);
        DrPlanVO plan = plans.findByUuid(planUuid);
        if (plan == null) throw new InvalidParameterValueException("DR_PLAN_NOT_FOUND");
        DrEventVO saved = events.findLatestByPlanIdAndEventType(plan.getId(), "DR_RETENTION_POLICY");
        JsonObject policy = saved == null ? new JsonObject() : JsonParser.parseString(saved.getDetailsJson()).getAsJsonObject();
        if (keepCount == null) keepCount = policy.has("keepCount") ? policy.get("keepCount").getAsInt() : 5;
        if (keepDays == null) keepDays = policy.has("keepDays") ? policy.get("keepDays").getAsInt() : 0;
        if (keepCount < 2 || keepCount > 10000 || keepDays < 0 || keepDays > 36500) {
            throw new InvalidParameterValueException("DR_CHECKPOINT_POLICY_INVALID");
        }
        if (savePolicy) {
            JsonObject value = new JsonObject();
            value.addProperty("keepCount", keepCount);
            value.addProperty("keepDays", keepDays);
            value.addProperty("automaticCleanup", false);
            DrEventVO event = new DrEventVO("DR_RETENTION_POLICY", "INFO", "CLOUD");
            event.setPlanId(plan.getId());
            event.setMessage("Manual checkpoint retention selection policy updated");
            event.setDetailsJson(value.toString());
            events.persist(event);
        }
        JsonArray selected = selection == null ? new JsonArray() : JsonParser.parseString(selection).getAsJsonArray();
        if (selected.size() > 10) throw new InvalidParameterValueException("DR_CHECKPOINT_SELECTION_LIMIT: select at most 10 sets");
        if (selected.size() > 0) {
            if (reason == null || reason.trim().isEmpty() || reason.length() > 1024) {
                throw new InvalidParameterValueException("DR_CHECKPOINT_REASON_REQUIRED");
            }
            if (runs.findActiveByPlanId(plan.getId()) != null || tests.findActiveByPlanId(plan.getId()) != null
                    || "TARGET".equalsIgnoreCase(plan.getActiveSide())) {
                throw new InvalidParameterValueException("DR_CHECKPOINT_IN_USE: finish the active recovery/test operation first");
            }
        }
        JsonObject spec = inventorySpec(plan);
        spec.addProperty("keepCount", keepCount);
        spec.addProperty("keepDays", keepDays);
        spec.addProperty("reason", reason);
        spec.add("selected", selected);
        JsonArray refs = new JsonArray();
        DrRestorePointVO latest = checkpoints.findLatestTargetReadyByPlanId(plan.getId());
        if (latest != null) refs.add(latest.getSourceSnapshotRef());
        spec.add("protectedRefs", refs);
        Long host = placement.resolveWorkerHostId(plan, DrWorkerRole.TARGET);
        if (host == null) throw new CloudRuntimeException("DR_CHECKPOINT_TARGET_UNREACHABLE");
        FtctlDrActionCommand command = new FtctlDrActionCommand(null, planUuid, UUID.randomUUID().toString());
        // Existing Agent forward-compatible named-command transport; no profile mutation.
        command.setActionName("CHECKPOINT_MANAGE");
        command.setCliCommand("dr-checkpoint-manage");
        command.setArtifactSpecJson(spec.toString());
        command.setWait(45);
        Answer answer = agents.easySend(host, command);
        if (!(answer instanceof FtctlDrActionAnswer) || !answer.getResult()) {
            String code = answer instanceof FtctlDrActionAnswer ? ((FtctlDrActionAnswer) answer).getErrorCode() : null;
            throw new CloudRuntimeException(code != null ? code : "DR_CHECKPOINT_TARGET_UNREACHABLE");
        }
        JsonObject result = JsonParser.parseString(((FtctlDrActionAnswer) answer).getStatusJson()).getAsJsonObject();
        if (!planUuid.equals(result.get("planUuid").getAsString())) {
            throw new CloudRuntimeException("DR_CHECKPOINT_IDENTITY_MISMATCH");
        }
        JsonObject effectivePolicy = new JsonObject();
        effectivePolicy.addProperty("keepCount", keepCount);
        effectivePolicy.addProperty("keepDays", keepDays);
        result.add("policy", effectivePolicy);
        if (selected.size() > 0) {
            for (JsonElement element : result.getAsJsonArray("sets")) {
                JsonObject set = element.getAsJsonObject();
                if ("DELETED".equals(set.get("state").getAsString())) {
                    DrRestorePointVO checkpoint = checkpoints.findByPlanIdAndSourceSnapshotRef(plan.getId(), set.get("checkpointRef").getAsString());
                    if (checkpoint != null) {
                        checkpoint.setState("EXPIRED");
                        checkpoints.update(checkpoint.getId(), checkpoint);
                    }
                }
            }
            DrEventVO event = new DrEventVO("DR_CHECKPOINT_CLEANUP", "INFO", "CLOUD");
            event.setPlanId(plan.getId());
            event.setMessage(reason);
            event.setDetailsJson(spec.toString());
            events.persist(event);
        }
        return result;
    }
}
