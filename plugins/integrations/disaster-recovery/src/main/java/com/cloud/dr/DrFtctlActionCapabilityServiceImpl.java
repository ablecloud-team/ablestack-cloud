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

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.inject.Inject;

import org.apache.commons.lang3.StringUtils;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.FtctlDrActionCommand;
import com.cloud.agent.api.FtctlDrCapabilitiesAnswer;
import com.cloud.agent.api.FtctlDrCapabilitiesCommand;
import com.cloud.dr.adapter.ftctl.DrRemoteAgentClient;
import com.cloud.utils.component.ManagerBase;

public class DrFtctlActionCapabilityServiceImpl extends ManagerBase implements DrFtctlActionCapabilityService {
    static final String CAPABILITY_UNAVAILABLE = "DR_ACTION_CAPABILITY_UNAVAILABLE";
    static final String CAPABILITY_MISMATCH = "DR_ACTION_CAPABILITY_MISMATCH";
    static final String REPROTECT_CONTRACT_UNSUPPORTED = "DR_ACTION_REPROTECT_CONTRACT_UNSUPPORTED";
    private static final long SNAPSHOT_TTL_MS = 30_000L;

    private static final Map<String, FtctlDrActionCommand.Action> ACTIONS = actionMap();

    @Inject private AgentManager agentManager;
    @Inject private DrRemoteAgentClient drRemoteAgentClient;
    @Inject private DrWorkerPlacementService drWorkerPlacementService;

    private final Map<String, CachedCapabilities> cache = new ConcurrentHashMap<String, CachedCapabilities>();

    @Override
    public DrFtctlActionCapabilitySnapshot evaluate(DrPlanVO plan) {
        if (plan == null) {
            return emptySnapshot();
        }
        try {
            FtctlDrCapabilitiesAnswer capabilities = capabilities(plan);
            return mergeRecoveryCapabilities(plan, evaluate(capabilities));
        } catch (RuntimeException e) {
            return mergeRecoveryCapabilities(plan, unavailableSnapshot(e.getClass().getSimpleName()));
        }
    }

    private DrFtctlActionCapabilitySnapshot mergeRecoveryCapabilities(DrPlanVO plan,
            DrFtctlActionCapabilitySnapshot source) {
        DrFtctlActionCapabilitySnapshot target;
        try {
            target = evaluate(capabilities(plan, true));
        } catch (RuntimeException e) {
            target = unavailableSnapshot(e.getClass().getSimpleName());
        }
        Map<String, String> reasons = new LinkedHashMap<String, String>();
        Map<String, Map<String, String>> args = new LinkedHashMap<String, Map<String, String>>();
        for (String action : ACTIONS.keySet()) {
            boolean recovery = Arrays.asList("testFailover", "stopTestFailover", "failover").contains(action);
            DrFtctlActionCapabilitySnapshot selected = recovery ? target : source;
            if (selected.getBlockingReason(action) != null) {
                reasons.put(action, selected.getBlockingReason(action));
                args.put(action, selected.getReasonArgs(action));
            }
        }
        return new DrFtctlActionCapabilitySnapshot(reasons, args);
    }

    DrFtctlActionCapabilitySnapshot evaluate(FtctlDrCapabilitiesAnswer capabilities) {
        if (capabilities == null || !capabilities.getResult()) {
            return unavailableSnapshot(capabilities == null ? "NO_ANSWER"
                    : StringUtils.defaultIfBlank(capabilities.getDetails(), "CAPABILITY_REJECTED"));
        }
        Map<String, String> reasons = new LinkedHashMap<String, String>();
        Map<String, Map<String, String>> args = new LinkedHashMap<String, Map<String, String>>();
        for (Map.Entry<String, FtctlDrActionCommand.Action> entry : ACTIONS.entrySet()) {
            FtctlDrActionCommand.Action action = entry.getValue();
            if (!containsIgnoreCase(capabilities.getSupportedActions(), action.name())
                    || !containsIgnoreCase(capabilities.getSupportedCliCommands(), action.getCliCommand())) {
                reasons.put(entry.getKey(), CAPABILITY_MISMATCH);
                args.put(entry.getKey(), args("action", action.name(), "command", action.getCliCommand()));
            }
        }
        if (!containsIgnoreCase(capabilities.getReprotectAuthorityContractVersions(),
                DrReprotectAuthoritySpec.CONTRACT_VERSION)) {
            reasons.put("reprotect", REPROTECT_CONTRACT_UNSUPPORTED);
            args.put("reprotect", args("requiredVersion", DrReprotectAuthoritySpec.CONTRACT_VERSION,
                    "supportedVersions", StringUtils.join(capabilities.getReprotectAuthorityContractVersions(), ',')));
        }
        return new DrFtctlActionCapabilitySnapshot(reasons, args);
    }

    private FtctlDrCapabilitiesAnswer capabilities(DrPlanVO plan) {
        return capabilities(plan, false);
    }

    private FtctlDrCapabilitiesAnswer capabilities(DrPlanVO plan, boolean targetOnly) {
        boolean remoteSource = !targetOnly && usesRemoteSource(plan);
        DrWorkerRole role = targetOnly ? DrWorkerRole.TARGET : StringUtils.startsWithIgnoreCase(plan.getDirection(), "VMWARE_")
                ? DrWorkerRole.VDDK_DATA_PLANE : DrWorkerRole.COORDINATOR;
        Long hostId = drWorkerPlacementService != null
                ? drWorkerPlacementService.resolveWorkerHostId(plan, role) : null;
        String cacheKey = remoteSource ? "remote:" + plan.getSourceSiteId()
                : "host:" + hostId;
        CachedCapabilities cached = cache.get(cacheKey);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.checkedAt < SNAPSHOT_TTL_MS) {
            return cached.answer;
        }
        FtctlDrCapabilitiesCommand command = new FtctlDrCapabilitiesCommand(plan.getUuid(), "availability");
        Answer answer = remoteSource
                ? drRemoteAgentClient.execute(plan, "CAPABILITIES", command, null,
                        FtctlDrCapabilitiesAnswer.class)
                : hostId == null ? null : agentManager.easySend(hostId, command);
        if (!(answer instanceof FtctlDrCapabilitiesAnswer)) {
            throw new IllegalStateException(answer == null ? "NO_ANSWER" : answer.getClass().getSimpleName());
        }
        FtctlDrCapabilitiesAnswer capabilities = (FtctlDrCapabilitiesAnswer) answer;
        cache.put(cacheKey, new CachedCapabilities(now, capabilities));
        return capabilities;
    }

    private boolean usesRemoteSource(DrPlanVO plan) {
        return drRemoteAgentClient != null
                && StringUtils.equalsIgnoreCase(plan.getDirection(), DrConstants.DIRECTION_KVM_TO_KVM)
                && plan.getSourceVmId() == null && StringUtils.isNotBlank(plan.getSourceExternalRef())
                && !StringUtils.equalsIgnoreCase(plan.getActiveSide(), "TARGET");
    }

    private DrFtctlActionCapabilitySnapshot unavailableSnapshot(String detail) {
        Map<String, String> reasons = new LinkedHashMap<String, String>();
        Map<String, Map<String, String>> args = new LinkedHashMap<String, Map<String, String>>();
        for (String action : ACTIONS.keySet()) {
            reasons.put(action, CAPABILITY_UNAVAILABLE);
            args.put(action, args("detail", StringUtils.defaultString(detail)));
        }
        return new DrFtctlActionCapabilitySnapshot(reasons, args);
    }

    private DrFtctlActionCapabilitySnapshot emptySnapshot() {
        return new DrFtctlActionCapabilitySnapshot(Collections.emptyMap(), Collections.emptyMap());
    }

    private static Map<String, FtctlDrActionCommand.Action> actionMap() {
        Map<String, FtctlDrActionCommand.Action> result = new LinkedHashMap<String, FtctlDrActionCommand.Action>();
        result.put("sync", FtctlDrActionCommand.Action.SYNC);
        result.put("recoverSync", FtctlDrActionCommand.Action.RECOVER_SYNC);
        result.put("pauseSync", FtctlDrActionCommand.Action.PAUSE_SYNC);
        result.put("resumeSync", FtctlDrActionCommand.Action.RESUME_SYNC);
        result.put("testFailover", FtctlDrActionCommand.Action.TEST_FAILOVER);
        result.put("stopTestFailover", FtctlDrActionCommand.Action.TEST_CLEANUP);
        result.put("failover", FtctlDrActionCommand.Action.FAILOVER);
        result.put("failback", FtctlDrActionCommand.Action.FAILBACK);
        result.put("reprotect", FtctlDrActionCommand.Action.REPROTECT);
        result.put("releaseProtection", FtctlDrActionCommand.Action.RELEASE);
        return Collections.unmodifiableMap(result);
    }

    private static boolean containsIgnoreCase(List<String> values, String expected) {
        if (values == null) {
            return false;
        }
        for (String value : values) {
            if (StringUtils.equalsIgnoreCase(value, expected)) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, String> args(String... values) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            result.put(values[index], values[index + 1]);
        }
        return result;
    }

    private static Long firstNonNull(Long... values) {
        return Arrays.stream(values).filter(value -> value != null).findFirst().orElse(null);
    }

    private static com.google.gson.JsonObject parseObject(String json) {
        if (StringUtils.isBlank(json)) {
            return new com.google.gson.JsonObject();
        }
        try {
            return com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException e) {
            return new com.google.gson.JsonObject();
        }
    }

    private static com.google.gson.JsonObject objectAt(com.google.gson.JsonObject root, String name) {
        return root != null && root.has(name) && root.get(name).isJsonObject()
                ? root.getAsJsonObject(name) : new com.google.gson.JsonObject();
    }

    private static String firstString(com.google.gson.JsonObject root, String... names) {
        for (String name : names) {
            if (root != null && root.has(name) && !root.get(name).isJsonNull()) {
                String value = root.get(name).getAsString();
                if (StringUtils.isNotBlank(value)) {
                    return value;
                }
            }
        }
        return null;
    }

    private static String firstNonBlank(String first, String second) {
        return StringUtils.isNotBlank(first) ? first : second;
    }

    private static class CachedCapabilities {
        private final long checkedAt;
        private final FtctlDrCapabilitiesAnswer answer;

        CachedCapabilities(long checkedAt, FtctlDrCapabilitiesAnswer answer) {
            this.checkedAt = checkedAt;
            this.answer = answer;
        }
    }
}
