/*
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
*/

// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information.
package com.cloud.ftctl;

import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import org.apache.cloudstack.api.response.ftctl.FtctlDrSiteAgentCommandResponse;
import org.apache.commons.lang3.StringUtils;
import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.FtctlDrActionAnswer;
import com.cloud.agent.api.FtctlDrActionCommand;
import com.cloud.ftctl.FtctlDrReverseExportStore.Journal;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.UserVmDao;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

/** Owns remote-site export grants; historical workers are revocation obligations, not placement. */
public class FtctlDrReverseExportCoordinator {
    private static final Gson GSON = new Gson();
    @Inject private AgentManager agentManager;
    @Inject private HostDao hostDao;
    @Inject private UserVmDao userVmDao;
    @Inject private FtctlDrReverseExportStore store;

    public FtctlDrSiteAgentCommandResponse execute(FtctlDrActionCommand command) {
        GlobalLock lock = GlobalLock.getInternLock("dr-reverse-export-" + command.getPlanUuid());
        boolean acquired = false;
        try {
            acquired = lock.lock(10);
            if (!acquired) { throw pending("transition is already running"); }
            return executeLocked(command);
        } finally {
            if (acquired) { lock.unlock(); }
            lock.releaseRef();
        }
    }

    FtctlDrSiteAgentCommandResponse executeLocked(FtctlDrActionCommand command) {
        JsonObject profile = GSON.fromJson(command.getProfileJson(), JsonObject.class);
        JsonObject request = profile != null ? profile.getAsJsonObject("request") : null;
        String plan = command.getPlanUuid();
        boolean start = command.getAction() == FtctlDrActionCommand.Action.TARGET_EXPORT_START;
        if (request == null || StringUtils.isBlank(plan) || StringUtils.isBlank(command.getRunUuid())
                || !plan.equals(string(request, "exportAuthorityScope"))
                || !"REVERSE".equals(string(request, "exportDirection"))) {
            throw pending("scoped reverse ownership request required");
        }
        long generation;
        try { generation = request.get("exportGeneration").getAsBigDecimal().longValueExact(); }
        catch (RuntimeException e) { throw pending("invalid generation"); }
        if (generation < 2) { throw pending("invalid generation"); }
        String vmUuid = command.getContext() != null ? command.getContext().get("sourceVmUuid") : null;
        UserVmVO vm = vmUuid != null ? userVmDao.findByUuid(vmUuid) : null;
        Journal journal = store.load(plan);
        if (journal == null && vm == null) { throw pending("source VM identity is unavailable"); }
        if (journal != null && !journal.sourceVmUuid.equals(vmUuid)) { throw pending("source VM identity changed"); }
        if (start && (vm == null || vm.getState() != VirtualMachine.State.Stopped)) {
            throw pending("original VM must be stopped before opening reverse target disks");
        }
        String operation = start ? "START" : "STOP";
        if (journal == null) {
            journal = new Journal();
            journal.sourceVmUuid = vmUuid;
            journal.zoneId = vm.getDataCenterId();
            List<HostVO> legacy = hostDao.listAllHostsByZoneAndHypervisorType(journal.zoneId, HypervisorType.KVM);
            if (legacy == null || legacy.isEmpty()) { throw pending("legacy export inventory is unavailable"); }
            for (HostVO host : legacy) { journal.hosts.put(host.getId(), "UNKNOWN"); }
        }
        if (generation < journal.generation || (generation == journal.generation
                && (!operation.equals(journal.operation) || !command.getRunUuid().equals(journal.runUuid)))) {
            throw pending("stale or conflicting transition generation");
        }
        boolean replay = generation == journal.generation;
        if (!replay) {
            journal.generation = generation;
            journal.operation = operation;
            journal.runUuid = command.getRunUuid();
            journal.selectedHost = null;
            store.save(plan, journal);
        }
        // A repeated uncertain START must contact its recorded worker, never grant elsewhere.
        if (!(replay && start && journal.selectedHost != null)) {
            for (Map.Entry<Long, String> entry : journal.hosts.entrySet()) {
                if ("REVOKED".equals(entry.getValue())) { continue; }
                stop(command, entry.getKey(), start ? generation - 1 : generation);
                entry.setValue("REVOKED");
                store.save(plan, journal);
            }
        }
        JsonObject status = new JsonObject();
        HostVO selected = null;
        if (start) {
            if (journal.selectedHost != null) {
                selected = hostDao.findById(journal.selectedHost);
            } else {
                List<HostVO> eligible = hostDao.listAllHostsUpByZoneAndHypervisor(journal.zoneId, HypervisorType.KVM);
                if (eligible != null) {
                    for (HostVO host : eligible) {
                        if (host.getRemoved() == null && host.getStatus() == Status.Up) { selected = host; break; }
                    }
                }
                if (selected == null) { throw pending("no live reverse target worker"); }
                // Persist uncertainty before STOP/START, including a new host not in the legacy inventory.
                journal.hosts.put(selected.getId(), "UNKNOWN");
                store.save(plan, journal);
                stop(command, selected.getId(), generation - 1);
                journal.selectedHost = selected.getId();
                journal.hosts.put(selected.getId(), "GRANTED");
                store.save(plan, journal);
            }
            if (selected == null || selected.getRemoved() != null || selected.getStatus() != Status.Up) {
                throw pending("recorded grant worker unavailable; retry or supersede after safe revocation");
            }
            FtctlDrActionCommand startCommand = copy(command);
            JsonObject actual = GSON.fromJson(startCommand.getProfileJson(), JsonObject.class);
            JsonObject transport = actual.has("transport") ? actual.getAsJsonObject("transport") : new JsonObject();
            if (StringUtils.isBlank(selected.getPrivateIpAddress())) { throw pending("worker address missing"); }
            transport.addProperty("targetHostUuid", selected.getUuid());
            transport.addProperty("targetHostAddress", selected.getPrivateIpAddress());
            transport.addProperty("remoteNbdExportAddress", selected.getPrivateIpAddress());
            transport.remove("exports"); actual.add("transport", transport);
            startCommand.setProfileJson(GSON.toJson(actual));
            startCommand.setTargetWorkerUuid(selected.getUuid());
            status = send(startCommand, selected, generation);
        } else {
            status.addProperty("ownershipProtocol", 2);
            status.addProperty("exportGeneration", generation);
            status.addProperty("exportAuthorityScope", plan);
            status.addProperty("exportDirection", "REVERSE");
            status.addProperty("state", "STOPPED");
        }
        status.addProperty("ownershipBrokerProtocol", 1);
        FtctlDrActionAnswer answer = new FtctlDrActionAnswer(command, true, null, command.getAction(),
                plan, command.getRunUuid(), "ok", true, start ? "READY" : "STOPPED", "export-ownership-confirmed",
                100, null, null, null, 0, null, GSON.toJson(status));
        FtctlDrSiteAgentCommandResponse response = new FtctlDrSiteAgentCommandResponse();
        response.setObjectName("ftctldrsiteagentcommand"); response.setCommandType("ACTION");
        response.setWorkerHostUuid(selected != null ? selected.getUuid() : null);
        response.setResult(true); response.setAnswerClass(answer.getClass().getName());
        response.setAnswerJson(GSON.toJson(answer));
        return response;
    }
    private void stop(FtctlDrActionCommand original, long hostId, long generation) {
        HostVO host = hostDao.findById(hostId);
        if (host == null || host.getRemoved() != null) { throw pending("historical worker " + hostId + " needs verified revocation"); }
        FtctlDrActionCommand command = copy(original);
        command.setAction(FtctlDrActionCommand.Action.TARGET_EXPORT_STOP);
        command.setActionName("TARGET_EXPORT_STOP"); command.setCliCommand("dr-target-export-stop");
        command.setTargetWorkerUuid(host.getUuid()); command.setCutoverCheckpointSequence(null);
        JsonObject profile = GSON.fromJson(command.getProfileJson(), JsonObject.class);
        profile.getAsJsonObject("request").addProperty("exportGeneration", generation);
        command.setProfileJson(GSON.toJson(profile));
        send(command, host, generation);
    }
    private JsonObject send(FtctlDrActionCommand command, HostVO host, long generation) {
        Answer answer;
        try { answer = agentManager.send(host.getId(), command); }
        catch (Exception e) { throw pending("worker " + host.getUuid() + " did not acknowledge: " + e.getClass().getSimpleName()); }
        if (!(answer instanceof FtctlDrActionAnswer) || !answer.getResult()) { throw pending("worker rejected ownership transition"); }
        JsonObject status = GSON.fromJson(((FtctlDrActionAnswer) answer).getStatusJson(), JsonObject.class);
        if (status == null || !"2".equals(string(status, "ownershipProtocol"))
                || !Long.toString(generation).equals(string(status, "exportGeneration"))
                || !command.getPlanUuid().equals(string(status, "exportAuthorityScope"))
                || !"REVERSE".equals(string(status, "exportDirection"))
                || !(command.getAction() == FtctlDrActionCommand.Action.TARGET_EXPORT_STOP ? "STOPPED" : "READY").equals(string(status, "state"))) { throw pending("worker returned no matching scoped ownership ACK"); }
        return status;
    }
    private FtctlDrActionCommand copy(FtctlDrActionCommand command) {
        return GSON.fromJson(GSON.toJson(command), FtctlDrActionCommand.class);
    }
    private String string(JsonObject object, String name) {
        return object.has(name) && object.get(name).isJsonPrimitive() ? object.get(name).getAsString() : null;
    }
    private CloudRuntimeException pending(String detail) {
        return new CloudRuntimeException("DR_EXPORT_OWNERSHIP_PENDING: " + detail);
    }
}