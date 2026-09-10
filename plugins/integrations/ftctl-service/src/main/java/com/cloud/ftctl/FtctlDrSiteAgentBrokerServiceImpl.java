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
package com.cloud.ftctl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import javax.inject.Inject;

import org.apache.cloudstack.api.response.ftctl.FtctlDrSiteAgentCommandResponse;
import org.apache.commons.lang3.StringUtils;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.FtctlDrActionCommand;
import com.cloud.agent.api.FtctlDrCapabilitiesCommand;
import com.cloud.agent.api.FtctlDrCancelCommand;
import com.cloud.agent.api.FtctlDrReversePreflightCommand;
import com.cloud.agent.api.FtctlDrStatusAnswer;
import com.cloud.agent.api.FtctlDrStatusCommand;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.dao.UserVmDao;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Executes a narrow set of FTCTL DR commands on a host owned by this Cloud.
 * Plan lifecycle authority remains with the signed API caller's Cloud.
 */
public class FtctlDrSiteAgentBrokerServiceImpl extends ManagerBase implements FtctlDrSiteAgentBrokerService {
    private static final Gson GSON = new Gson();

    @Inject private AgentManager agentManager;
    @Inject private FtctlDrReverseExportCoordinator reverseExportCoordinator;
    @Inject private HostDao hostDao;
    @Inject private DataCenterDao dataCenterDao;
    @Inject private UserVmDao userVmDao;

    @Override
    public FtctlDrSiteAgentCommandResponse execute(String commandType, String commandJson, String workerHostUuid) {
        String normalizedType = StringUtils.upperCase(StringUtils.trim(commandType), Locale.ROOT);
        Command command = deserialize(normalizedType, commandJson);
        if (command instanceof FtctlDrActionCommand) {
            FtctlDrActionCommand action = (FtctlDrActionCommand) command;
            if ((action.getAction() == FtctlDrActionCommand.Action.TARGET_EXPORT_START
                    || action.getAction() == FtctlDrActionCommand.Action.TARGET_EXPORT_STOP)
                    && "reverse-target".equals(action.getRole())) {
                return reverseExportCoordinator.execute(action);
            }
        }
        List<HostVO> candidates = eligibleWorkers();
        preferCurrentVmHost(command, candidates);
        preferOperationRuntimeOwner(command, candidates);
        if (candidates.isEmpty()) {
            throw new CloudRuntimeException("No eligible FTCTL DR site Agent worker is available");
        }
        DispatchResult dispatched = dispatch(normalizedType, command, candidates);
        HostVO host = dispatched.host;
        Answer answer = dispatched.answer;
        if (answer == null) {
            throw new CloudRuntimeException("FTCTL DR site Agent returned no answer for " + normalizedType);
        }
        FtctlDrSiteAgentCommandResponse response = new FtctlDrSiteAgentCommandResponse();
        response.setObjectName("ftctldrsiteagentcommand");
        response.setCommandType(normalizedType);
        response.setWorkerHostUuid(host.getUuid());
        response.setResult(answer.getResult());
        response.setDetails(answer.getDetails());
        response.setAnswerClass(answer.getClass().getName());
        response.setAnswerJson(GSON.toJson(answer));
        return response;
    }

    private DispatchResult dispatch(String commandType, Command command, List<HostVO> candidates) {
        RuntimeException lastError = null;
        DispatchResult readOnlyFallback = null;
        for (HostVO host : candidates) {
            try {
                prepareSiteLocalCommand(command, host);
                Answer answer = agentManager.send(host.getId(), command);
                if (answer != null) {
                    DispatchResult result = new DispatchResult(host, answer);
                    if (!isReadOnlyOrCancel(commandType)) {
                        return result;
                    }
                    if (meaningful(answer)) {
                        if (!(answer instanceof FtctlDrStatusAnswer)) {
                            return result;
                        }
                        if (readOnlyFallback == null
                                || compareStatusAuthority((FtctlDrStatusAnswer) answer,
                                        (FtctlDrStatusAnswer) readOnlyFallback.answer,
                                        command) > 0) {
                            readOnlyFallback = result;
                        }
                    }
                }
            } catch (AgentUnavailableException e) {
                lastError = new CloudRuntimeException("FTCTL DR site Agent worker is unavailable: "
                        + host.getUuid(), e);
            } catch (OperationTimedoutException e) {
                lastError = new CloudRuntimeException("FTCTL DR site command timed out: " + commandType, e);
            }
            if ("ACTION".equals(commandType) || "REVERSE_PREFLIGHT".equals(commandType)) {
                break;
            }
        }
        if (readOnlyFallback != null) {
            return readOnlyFallback;
        }
        if (lastError != null) {
            throw lastError;
        }
        throw new CloudRuntimeException("No FTCTL DR site Agent returned matching evidence for " + commandType);
    }

    private boolean isReadOnlyOrCancel(String commandType) {
        return StringUtils.equalsAny(commandType, "STATUS", "CAPABILITIES", "CANCEL");
    }

    private boolean meaningful(Answer answer) {
        if (!answer.getResult()) {
            return false;
        }
        if (answer instanceof com.cloud.agent.api.FtctlDrStatusAnswer) {
            com.cloud.agent.api.FtctlDrStatusAnswer status = (com.cloud.agent.api.FtctlDrStatusAnswer) answer;
            return StringUtils.isNotBlank(status.getState())
                    && !StringUtils.equalsAnyIgnoreCase(status.getState(), "NOT_FOUND", "UNKNOWN");
        }
        if (answer instanceof com.cloud.agent.api.FtctlDrCancelAnswer) {
            return Boolean.TRUE.equals(((com.cloud.agent.api.FtctlDrCancelAnswer) answer).getAccepted());
        }
        return true;
    }

    private int compareStatusAuthority(FtctlDrStatusAnswer candidate, FtctlDrStatusAnswer selected,
            Command command) {
        String requestedRunUuid = command instanceof FtctlDrStatusCommand
                ? ((FtctlDrStatusCommand) command).getRunUuid() : null;
        int compared = Boolean.compare(runMatches(candidate, requestedRunUuid), runMatches(selected, requestedRunUuid));
        if (compared != 0) {
            return compared;
        }
        compared = Long.compare(statusSequence(candidate), statusSequence(selected));
        if (compared != 0) {
            return compared;
        }
        compared = Boolean.compare(Boolean.TRUE.equals(candidate.getSchedulerPidAlive()),
                Boolean.TRUE.equals(selected.getSchedulerPidAlive()));
        if (compared != 0) {
            return compared;
        }
        compared = Integer.compare(schedulerHealthRank(candidate.getSchedulerHealth()),
                schedulerHealthRank(selected.getSchedulerHealth()));
        if (compared != 0) {
            return compared;
        }
        return Integer.compare(stateRank(candidate.getState()), stateRank(selected.getState()));
    }

    private boolean runMatches(FtctlDrStatusAnswer status, String requestedRunUuid) {
        return StringUtils.isNotBlank(requestedRunUuid) && StringUtils.equals(requestedRunUuid, status.getRunUuid());
    }

    private long statusSequence(FtctlDrStatusAnswer status) {
        long authority = status.getAuthoritySequence() != null ? status.getAuthoritySequence() : 0L;
        long generation = status.getRuntimeGeneration() != null ? status.getRuntimeGeneration() : 0L;
        long completed = status.getLatestCompletedCycleSequence() != null
                ? status.getLatestCompletedCycleSequence() : 0L;
        return Math.max(authority, Math.max(generation, completed));
    }

    private int schedulerHealthRank(String health) {
        if (StringUtils.equalsIgnoreCase(health, "HEALTHY")) {
            return 2;
        }
        if (StringUtils.equalsIgnoreCase(health, "DEGRADED")) {
            return 1;
        }
        return 0;
    }

    private int stateRank(String state) {
        if (StringUtils.equalsAnyIgnoreCase(state, "READY", "TARGET_READY", "SYNCING", "PAUSED")) {
            return 2;
        }
        return StringUtils.equalsAnyIgnoreCase(state, "ERROR", "FAILED") ? 0 : 1;
    }

    private List<HostVO> eligibleWorkers() {
        List<HostVO> result = new ArrayList<HostVO>();
        List<DataCenterVO> zones = dataCenterDao != null ? dataCenterDao.listEnabledZones() : null;
        if (zones != null) {
            for (DataCenterVO zone : zones) {
                List<HostVO> hosts = hostDao.listAllHostsUpByZoneAndHypervisor(zone.getId(),
                        Hypervisor.HypervisorType.KVM);
                if (hosts != null) {
                    for (HostVO host : hosts) {
                        if (host != null && host.getRemoved() == null && Status.Up.equals(host.getStatus())) {
                            result.add(host);
                        }
                    }
                }
            }
        }
        result.sort(Comparator.comparingLong(HostVO::getId));
        return result;
    }

    private void preferCurrentVmHost(Command command, List<HostVO> candidates) {
        if (userVmDao == null || candidates.isEmpty()) {
            return;
        }
        String sourceVmUuid = sourceVmUuid(command);
        UserVmVO vm = StringUtils.isNotBlank(sourceVmUuid) ? userVmDao.findByUuid(sourceVmUuid) : null;
        Long hostId = vm != null ? vm.getHostId() : null;
        if (hostId == null) {
            return;
        }
        for (int index = 0; index < candidates.size(); index++) {
            if (candidates.get(index).getId() == hostId.longValue()) {
                HostVO current = candidates.remove(index);
                candidates.add(0, current);
                return;
            }
        }
    }

    private void preferOperationRuntimeOwner(Command command, List<HostVO> candidates) {
        if (!(command instanceof FtctlDrActionCommand) || candidates.isEmpty()) {
            return;
        }
        FtctlDrActionCommand action = (FtctlDrActionCommand) command;
        if (!continuesExistingRuntime(action.getAction()) || StringUtils.isBlank(action.getRunUuid())) {
            return;
        }
        FtctlDrStatusCommand status = new FtctlDrStatusCommand(action.getPlanUuid(), action.getRunUuid(),
                FtctlDrStatusCommand.StatusScope.OPERATION);
        status.setSourceVmUuid(sourceVmUuid(action));
        try {
            DispatchResult owner = dispatch("STATUS", status, new ArrayList<HostVO>(candidates));
            if (owner.answer instanceof FtctlDrStatusAnswer
                    && runMatches((FtctlDrStatusAnswer) owner.answer, action.getRunUuid())) {
                candidates.removeIf(candidate -> candidate.getId() == owner.host.getId());
                candidates.add(0, owner.host);
            }
        } catch (RuntimeException ignored) {
            // Keep the live-placement fallback when no prior runtime is discoverable.
        }
    }

    private boolean continuesExistingRuntime(FtctlDrActionCommand.Action action) {
        return action == FtctlDrActionCommand.Action.CUTOVER_COMMIT
                || action == FtctlDrActionCommand.Action.CUTOVER_COMMIT_STATUS
                || action == FtctlDrActionCommand.Action.FAILOVER_ABORT
                || action == FtctlDrActionCommand.Action.FAILBACK_COMMIT
                || action == FtctlDrActionCommand.Action.FAILBACK_COMMIT_STATUS
                || action == FtctlDrActionCommand.Action.FAILBACK_ABORT;
    }

    private String sourceVmUuid(Command command) {
        if (command instanceof FtctlDrStatusCommand) {
            return StringUtils.trimToNull(((FtctlDrStatusCommand) command).getSourceVmUuid());
        }
        if (command instanceof FtctlDrCancelCommand) {
            return StringUtils.trimToNull(((FtctlDrCancelCommand) command).getSourceVmUuid());
        }
        if (!(command instanceof FtctlDrActionCommand)) {
            return null;
        }
        FtctlDrActionCommand action = (FtctlDrActionCommand) command;
        String contextSourceVmUuid = action.getContext() != null
                ? StringUtils.trimToNull(action.getContext().get("sourceVmUuid")) : null;
        if (contextSourceVmUuid != null) {
            return contextSourceVmUuid;
        }
        if (StringUtils.isBlank(action.getProfileJson())) {
            return null;
        }
        try {
            JsonObject profile = GSON.fromJson(action.getProfileJson(), JsonObject.class);
            JsonObject source = objectAt(profile, "source");
            JsonObject vm = objectAt(source, "vm");
            String sourceRef = firstString(source, "externalRef", "vmUuid", "sourceVmUuid", "uuid");
            return StringUtils.defaultIfBlank(sourceRef, firstString(vm, "uuid", "id", "externalRef"));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void prepareSiteLocalCommand(Command command, HostVO host) {
        if (!(command instanceof FtctlDrActionCommand) || host == null) {
            return;
        }
        FtctlDrActionCommand actionCommand = (FtctlDrActionCommand) command;
        if (actionCommand.getAction() != FtctlDrActionCommand.Action.TARGET_EXPORT_START) {
            return;
        }
        if (StringUtils.isBlank(host.getPrivateIpAddress())) {
            throw new CloudRuntimeException("FTCTL DR target export worker address is required: " + host.getUuid());
        }
        JsonObject profile = parseObject(actionCommand.getProfileJson());
        JsonObject transport = objectAt(profile, "transport");
        transport.addProperty("targetHostUuid", host.getUuid());
        transport.addProperty("targetHostAddress", host.getPrivateIpAddress());
        transport.addProperty("remoteNbdExportAddress", host.getPrivateIpAddress());
        transport.remove("exports");
        actionCommand.setProfileJson(GSON.toJson(profile));
    }

    private JsonObject parseObject(String json) {
        if (StringUtils.isBlank(json)) {
            return new JsonObject();
        }
        try {
            JsonElement parsed = JsonParser.parseString(json);
            return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException e) {
            throw new CloudRuntimeException("FTCTL DR action profile JSON is invalid", e);
        }
    }

    private JsonObject objectAt(JsonObject object, String key) {
        JsonElement value = object != null ? object.get(key) : null;
        if (value != null && value.isJsonObject()) {
            return value.getAsJsonObject();
        }
        JsonObject child = new JsonObject();
        object.add(key, child);
        return child;
    }

    private String firstString(JsonObject object, String... keys) {
        if (object == null) {
            return null;
        }
        for (String key : keys) {
            JsonElement value = object.get(key);
            if (value != null && !value.isJsonNull() && value.isJsonPrimitive()) {
                String text = StringUtils.trimToNull(value.getAsString());
                if (text != null) {
                    return text;
                }
            }
        }
        return null;
    }

    private static class DispatchResult {
        private final HostVO host;
        private final Answer answer;

        DispatchResult(HostVO host, Answer answer) {
            this.host = host;
            this.answer = answer;
        }
    }

    private Command deserialize(String commandType, String commandJson) {
        if (StringUtils.isBlank(commandJson)) {
            throw new CloudRuntimeException("FTCTL DR site command JSON is required");
        }
        switch (commandType) {
            case "ACTION":
                return GSON.fromJson(commandJson, FtctlDrActionCommand.class);
            case "STATUS":
                return GSON.fromJson(commandJson, FtctlDrStatusCommand.class);
            case "CAPABILITIES":
                return GSON.fromJson(commandJson, FtctlDrCapabilitiesCommand.class);
            case "CANCEL":
                return GSON.fromJson(commandJson, FtctlDrCancelCommand.class);
            case "REVERSE_PREFLIGHT":
                return GSON.fromJson(commandJson, FtctlDrReversePreflightCommand.class);
            default:
                throw new CloudRuntimeException("Unsupported FTCTL DR site command type: " + commandType);
        }
    }
}
