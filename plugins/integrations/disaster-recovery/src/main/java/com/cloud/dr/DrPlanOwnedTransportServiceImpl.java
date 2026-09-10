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

import java.util.HashSet;
import java.util.TreeSet;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import java.util.Set;

import javax.inject.Inject;

import org.apache.commons.lang3.StringUtils;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.FtctlDrActionAnswer;
import com.cloud.agent.api.FtctlDrActionCommand;
import com.cloud.dr.adapter.ftctl.DrRemoteAgentClient;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class DrPlanOwnedTransportServiceImpl extends ManagerBase implements DrPlanOwnedTransportService {
    private static final Gson GSON = new Gson();
    private static final int TRANSITION_WAIT_SECONDS = 45;

    @Inject private AgentManager agentManager;
    @Inject private DrExportOwnershipStore exportOwnershipStore;
    @Inject private HostDao hostDao;
    @Inject private DrRemoteAgentClient drRemoteAgentClient;
    @Inject private DrWorkerPlacementService drWorkerPlacementService;

    @Override
    public boolean supports(DrPlanVO plan) {
        return plan != null && drRemoteAgentClient != null
                && drRemoteAgentClient.isRemoteKvmSource(plan)
                && StringUtils.equalsIgnoreCase(plan.getDirection(), DrConstants.DIRECTION_KVM_TO_KVM);
    }

    @Override
    public JsonArray startForwardTargetExport(DrPlanVO plan, DrRunVO run, String profileJson) {
        if (!supports(plan)) {
            return new JsonArray();
        }
        HostVO targetHost = targetHost(plan);
        long generation = exportOwnershipStore.nextGeneration(plan.getId());
        revokeForwardExports(plan, run, profileJson, targetHost, generation, null);
        FtctlDrActionCommand command = command(plan, run, FtctlDrActionCommand.Action.TARGET_EXPORT_START,
                "target", targetHost.getUuid(), ownershipProfile(profileJson, generation + 1));
        Answer answer = agentManager.easySend(targetHost.getId(), command);
        requireOwnership(answer, generation + 1, targetHost.getId());
        return requireExports(answer, "Target Agent did not prepare the Plan-owned RBD export",
                plan, profileJson);
    }

    @Override
    public JsonArray startReverseTargetExport(DrPlanVO plan, DrRunVO run, String profileJson) {
        if (!supports(plan)) {
            return new JsonArray();
        }
        String workerUuid = null;
        JsonObject profile = parseObject(profileJson);
        long generation = exportOwnershipStore.nextGeneration(plan.getId()) + 1;
        reverseOwnership(profile, plan, generation);
        FtctlDrActionCommand command = command(plan, run, FtctlDrActionCommand.Action.TARGET_EXPORT_START,
                "reverse-target", workerUuid, GSON.toJson(profile));
        Answer answer = drRemoteAgentClient.execute(plan, "ACTION", command,
                workerUuid, FtctlDrActionAnswer.class);
        requireReverseOwnership(answer, plan, generation);
        return requireExports(answer, "Original-site Agent did not prepare the reverse RBD export",
                plan, profileJson);
    }

    @Override
    public void stopForwardTargetExport(DrPlanVO plan, DrRunVO run, String profileJson,
            Long checkpointSequence) {
        if (!supports(plan)) {
            return;
        }
        HostVO targetHost = targetHost(plan);
        long generation = exportOwnershipStore.nextGeneration(plan.getId());
        revokeForwardExports(plan, run, profileJson, targetHost, generation,
                StringUtils.equalsIgnoreCase(run.getRunType(), DrConstants.RUN_TYPE_TEST_FAILOVER)
                        ? null : checkpointSequence);
    }

    private String ownershipProfile(String profileJson, long generation) {
        JsonObject profile = parseObject(profileJson);
        objectAt(profile, "request").addProperty("exportGeneration", generation);
        return GSON.toJson(profile);
    }

    private void revokeForwardExports(DrPlanVO plan, DrRunVO run, String profileJson,
            HostVO selected, long generation, Long checkpointSequence) {
        Set<Long> hosts = new TreeSet<>();
        for (HostVO host : hostDao.listAllHostsByZoneAndHypervisorType(
                selected.getDataCenterId(), HypervisorType.KVM)) {
            hosts.add(host.getId());
        }
        hosts.add(selected.getId());
        hosts = new TreeSet<>(exportOwnershipStore.rememberHosts(plan.getId(), hosts));
        for (Long hostId : hosts) {
            HostVO host = hostDao.findById(hostId);
            if (host == null) {
                throw new CloudRuntimeException("DR_EXPORT_OWNERSHIP_PENDING: historical worker "
                        + hostId + " requires verified fencing before export transfer");
            }
            FtctlDrActionCommand stop = command(plan, run, FtctlDrActionCommand.Action.TARGET_EXPORT_STOP,
                    "target", host.getUuid(), ownershipProfile(profileJson, generation));
            requireOwnership(agentManager.easySend(hostId, stop), generation, hostId);
        }
        // Only after every writer is drained may a real cutover seal its reverse baseline.
        if (checkpointSequence != null) {
            FtctlDrActionCommand stop = command(plan, run, FtctlDrActionCommand.Action.TARGET_EXPORT_STOP,
                    "target", selected.getUuid(), ownershipProfile(profileJson, generation));
            stop.setCutoverCheckpointSequence(checkpointSequence);
            requireOwnership(agentManager.easySend(selected.getId(), stop), generation, selected.getId());
        }
    }

    private void requireOwnership(Answer answer, long generation, long hostId) {
        if (answer instanceof FtctlDrActionAnswer && answer.getResult()) {
            JsonObject status = parseObject(((FtctlDrActionAnswer) answer).getStatusJson());
            if ("1".equals(firstString(status, "ownershipProtocol"))
                    && Long.toString(generation).equals(firstString(status, "exportGeneration"))) {
                return;
            }
        }
        throw new CloudRuntimeException("DR_EXPORT_OWNERSHIP_PENDING: worker " + hostId
                + " did not confirm generation " + generation + ": "
                + (answer != null ? answer.getDetails() : "Agent unavailable"));
    }

    @Override
    public void stopReverseTargetExport(DrPlanVO plan, DrRunVO run) {
        if (!supports(plan)) {
            return;
        }
        long generation = exportOwnershipStore.nextGeneration(plan.getId());
        JsonObject profile = new JsonObject();
        reverseOwnership(profile, plan, generation);
        FtctlDrActionCommand command = command(plan, run, FtctlDrActionCommand.Action.TARGET_EXPORT_STOP,
                "reverse-target", null, GSON.toJson(profile));
        requireReverseOwnership(drRemoteAgentClient.execute(plan, "ACTION", command,
                null, FtctlDrActionAnswer.class), plan, generation);
    }

    private void reverseOwnership(JsonObject profile, DrPlanVO plan, long generation) {
        JsonObject request = objectAt(profile, "request");
        request.addProperty("reverseTargetExport", true);
        request.addProperty("exportGeneration", generation);
        request.addProperty("exportAuthorityScope", plan.getUuid());
        request.addProperty("exportDirection", "REVERSE");
    }

    private void requireReverseOwnership(Answer answer, DrPlanVO plan, long generation) {
        if (answer instanceof FtctlDrActionAnswer && answer.getResult()) {
            JsonObject status = parseObject(((FtctlDrActionAnswer) answer).getStatusJson());
            if ("2".equals(firstString(status, "ownershipProtocol"))
                    && "1".equals(firstString(status, "ownershipBrokerProtocol"))
                    && Long.toString(generation).equals(firstString(status, "exportGeneration"))
                    && plan.getUuid().equals(firstString(status, "exportAuthorityScope"))
                    && "REVERSE".equals(firstString(status, "exportDirection"))) {
                return;
            }
        }
        throw new CloudRuntimeException("DR_EXPORT_OWNERSHIP_PENDING: original site did not confirm scoped reverse export generation " + generation);
    }

    private FtctlDrActionCommand command(DrPlanVO plan, DrRunVO run,
            FtctlDrActionCommand.Action action, String role, String workerUuid, String profileJson) {
        if (run == null || StringUtils.isBlank(run.getUuid())) {
            throw new CloudRuntimeException("DR Run is required for Plan-owned transport transition");
        }
        FtctlDrActionCommand command = new FtctlDrActionCommand(action, plan.getUuid(), run.getUuid());
        command.setActionName(action.name());
        command.setCliCommand(action.getCliCommand());
        command.setRunType(run.getRunType());
        command.setActionIntent(run.getRunType());
        command.setDirection(plan.getDirection());
        command.setRole(role);
        command.setTargetWorkerUuid(workerUuid);
        if (StringUtils.isNotBlank(profileJson)) {
            command.setProfileJson(profileJson);
        }
        command.setWaitForCompletion(true);
        command.setWait(TRANSITION_WAIT_SECONDS);
        return command;
    }

    private HostVO targetHost(DrPlanVO plan) {
        Long hostId = drWorkerPlacementService != null
                ? drWorkerPlacementService.resolveWorkerHostId(plan, DrWorkerRole.TARGET) : null;
        HostVO host = hostId != null ? hostDao.findById(hostId) : null;
        if (host == null || StringUtils.isBlank(host.getUuid())) {
            throw new CloudRuntimeException("DR target worker host is required for Plan-owned transport");
        }
        return host;
    }

    private JsonArray requireExports(Answer answer, String fallback, DrPlanVO plan, String profileJson) {
        requireSuccess(answer, fallback);
        if (!(answer instanceof FtctlDrActionAnswer)) {
            throw new CloudRuntimeException(fallback + ": Agent returned no structured export status");
        }
        JsonArray exports = firstArray(parseObject(((FtctlDrActionAnswer) answer).getStatusJson()), "exports");
        String exportGeneration = firstString(parseObject(((FtctlDrActionAnswer) answer).getStatusJson()), "exportGeneration");
        if (StringUtils.isNumeric(exportGeneration)) {
            for (JsonElement element : exports) {
                if (element.isJsonObject()) {
                    element.getAsJsonObject().addProperty("exportGeneration", Long.valueOf(exportGeneration));
                }
            }
        }

        if (exports.size() == 0) {
            throw new CloudRuntimeException(fallback + ": Agent returned no RBD export endpoints");
        }
        Set<String> expectedDevices = expectedExportDevices(plan, profileJson);
        Set<String> actualDevices = new HashSet<String>();
        for (JsonElement element : exports) {
            String device = element != null && element.isJsonObject()
                    ? firstString(element.getAsJsonObject(), "device") : null;
            if (StringUtils.isBlank(device) || !actualDevices.add(device)) {
                throw new CloudRuntimeException(fallback
                        + ": Agent returned a blank or duplicate export device");
            }
        }
        if (!expectedDevices.isEmpty() && !expectedDevices.equals(actualDevices)) {
            throw new CloudRuntimeException(fallback + ": Agent returned an incomplete export set; expected "
                    + expectedDevices.size() + " devices but received " + actualDevices.size());
        }
        return exports;
    }

    private Set<String> expectedExportDevices(DrPlanVO plan, String profileJson) {
        JsonObject profile = parseObject(profileJson);
        JsonArray disks = firstArray(objectAt(profile, "mapping"), "disks");
        if (disks.size() == 0 && plan != null) {
            disks = firstArray(parseObject(plan.getMappingJson()), "disks");
        }
        Set<String> devices = new HashSet<String>();
        for (JsonElement element : disks) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject disk = element.getAsJsonObject();
            String device = firstString(disk, "device", "cbtDiskId", "sourceDiskRef");
            if (StringUtils.isNotBlank(device)) {
                devices.add(device);
            }
        }
        return devices;
    }

    private void requireSuccess(Answer answer, String fallback) {
        if (answer == null || !answer.getResult()) {
            throw new CloudRuntimeException(StringUtils.defaultIfBlank(
                    answer != null ? answer.getDetails() : null, fallback));
        }
    }

    private JsonObject parseObject(String json) {
        if (StringUtils.isBlank(json)) {
            return new JsonObject();
        }
        try {
            JsonElement element = JsonParser.parseString(json);
            return element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException e) {
            throw new CloudRuntimeException("DR transport profile JSON is invalid", e);
        }
    }

    private JsonObject objectAt(JsonObject parent, String name) {
        JsonElement current = parent.get(name);
        if (current != null && current.isJsonObject()) {
            return current.getAsJsonObject();
        }
        JsonObject child = new JsonObject();
        parent.add(name, child);
        return child;
    }

    private JsonArray firstArray(JsonObject parent, String name) {
        JsonElement value = parent != null ? parent.get(name) : null;
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
    }

    private String firstString(JsonObject parent, String... names) {
        if (parent == null) {
            return null;
        }
        for (String name : names) {
            JsonElement value = parent.get(name);
            if (value != null && value.isJsonPrimitive()) {
                String text = StringUtils.trimToNull(value.getAsString());
                if (text != null) {
                    return text;
                }
            }
        }
        return null;
    }
}
