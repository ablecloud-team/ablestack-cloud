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

package org.apache.cloudstack.backup;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Provider;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDetailsDao;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.Gson;
import org.apache.commons.lang3.StringUtils;

/** Runs under the staging configuration lock. Activation remains disabled until every path is verified. */
public class ThirdPartyBackupStagingConfigurationSync extends ManagerBase {
    public static final String STATUS_KEY = "backup.staging.sync.status";
    public static final String ERROR_KEY = "backup.staging.sync.error";
    @Inject private AgentManager agentManager;
    @Inject private HostDetailsDao hostDetailsDao;
    @Inject private Provider<BackupManager> backupManager;
    private final Gson gson = new Gson();

    private static class Target {
        HostVO host;
        ThirdPartyBackupStagingSync.Request request;
        ThirdPartyBackupStagingSync.Plan plan;
        boolean changed;
    }

    public void synchronize(ThirdPartyBackupStagingService.Snapshot configuration, List<HostVO> hosts, boolean allowChanges) {
        synchronize(configuration, hosts, allowChanges, () -> { });
    }

    public void synchronize(ThirdPartyBackupStagingService.Snapshot configuration, List<HostVO> hosts, boolean allowChanges, Runnable beforeChanges) {
        String revision = UUID.nameUUIDFromBytes(gson.toJson(configuration).getBytes(StandardCharsets.UTF_8)).toString();
        List<Target> targets = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Map<String, String> policyPaths = new java.util.HashMap<>();
        // Complete read-only discovery for all Hosts and policies before writing any configuration.
        for (HostVO host : hosts) {
            List<String> providers = Arrays.stream(StringUtils.defaultString(BackupManager.BackupProviderPlugin.valueIn(host.getDataCenterId())).split(","))
                    .map(String::trim).map(value -> value.toLowerCase(Locale.ROOT))
                    .filter(value -> List.of("ablestack-netbackup", "ablestack-veeam").contains(value)).distinct().collect(Collectors.toList());
            if (providers.isEmpty()) { continue; }
            Target target = new Target();
            target.host = host;
            target.request = new ThirdPartyBackupStagingSync.Request();
            target.request.hostName = host.getName();
            target.request.configuration = configuration;
            target.request.providers = providers;
            try {
                target.plan = command(target, "PLAN");
                validate(target);
                target.changed = target.plan.changed;
                for (ThirdPartyBackupStagingSync.Binding binding : target.plan.bindings) {
                    if (binding.managed && provider(binding).synchronizeStagingPath(host, binding, "CHECK")) { target.changed = true; }
                    String key = host.getDataCenterId() + ":" + binding.provider + ":" + binding.name;
                    String previous = policyPaths.putIfAbsent(key, binding.path);
                    if (previous != null && !previous.equals(binding.path)) {
                        throw new CloudRuntimeException("The same external policy/Job is configured with different Host paths: " + binding.name);
                    }
                }
                if (target.changed && !allowChanges) {
                    throw new CloudRuntimeException("Disable backup.thirdparty.staging.enable before repairing different Host or policy paths");
                }
                state(target, revision, target.changed ? "PENDING" : "SYNCHRONIZED", null);
                targets.add(target);
            } catch (Exception e) {
                state(target, revision, "FAILED", e.getMessage());
                errors.add(host.getName() + ": " + e.getMessage());
            }
        }
        if (!errors.isEmpty()) {
            throw new CloudRuntimeException("Staging synchronization preflight failed; staging was not activated. " + String.join("; ", errors));
        }
        if (targets.stream().anyMatch(target -> target.changed)) {
            try { beforeChanges.run(); }
            catch (RuntimeException e) {
                for (Target target : targets) {
                    if (target.changed) { state(target, revision, "FAILED", e.getMessage()); }
                }
                throw e;
            }
        }
        for (Target target : targets) {
            if (!target.changed) { continue; }
            try {
                state(target, revision, "APPLYING", null);
                // Each provider rereads its selection and writes only if that selection differs.
                for (ThirdPartyBackupStagingSync.Binding binding : target.plan.bindings) {
                    if (binding.managed) { provider(binding).synchronizeStagingPath(target.host, binding, "APPLY"); }
                }
                String expectedFingerprint = target.plan.fingerprint;
                if (target.plan.changed) { expectedFingerprint = command(target, "APPLY").fingerprint; }
                ThirdPartyBackupStagingSync.Plan actual = command(target, "VERIFY");
                if (actual.changed || !expectedFingerprint.equals(actual.fingerprint)) {
                    throw new CloudRuntimeException("Host path verification failed or configuration changed concurrently");
                }
                for (ThirdPartyBackupStagingSync.Binding binding : target.plan.bindings) {
                    if (binding.managed) { provider(binding).synchronizeStagingPath(target.host, binding, "VERIFY"); }
                }
                state(target, revision, "SYNCHRONIZED", null);
            } catch (Exception e) {
                state(target, revision, "FAILED", e.getMessage());
                throw new CloudRuntimeException("Staging path synchronization failed on Host [" + target.host.getName()
                        + "]; staging was not activated. Retry activation to resume only remaining differences. " + e.getMessage(), e);
            }
        }
    }

    private BackupProvider provider(ThirdPartyBackupStagingSync.Binding binding) {
        return backupManager.get().getBackupProvider(binding.provider);
    }

    private ThirdPartyBackupStagingSync.Plan command(Target target, String action) throws Exception {
        target.request.action = action;
        target.request.fingerprint = target.plan == null ? null : target.plan.fingerprint;
        Answer answer = agentManager.send(target.host.getId(), new AblestackBackupStagingSyncCommand(target.request));
        if (answer == null || !answer.getResult()) {
            throw new CloudRuntimeException(answer == null ? "No staging synchronization answer" : answer.getDetails());
        }
        ThirdPartyBackupStagingSync.Plan plan = gson.fromJson(answer.getDetails(), ThirdPartyBackupStagingSync.Plan.class);
        if (plan == null || plan.version != 1 || !target.host.getName().equals(plan.hostName)
                || !target.request.configuration.rootPath.equals(plan.rootPath)
                || plan.fingerprint == null || !plan.fingerprint.matches("[0-9a-f]{64}") || plan.bindings == null) {
            throw new CloudRuntimeException("Invalid staging synchronization response; update the KVM Agent and host scripts");
        }
        return plan;
    }

    private void validate(Target target) {
        for (ThirdPartyBackupStagingSync.Binding binding : target.plan.bindings) {
            if (binding == null || !target.request.providers.contains(binding.provider) || StringUtils.isBlank(binding.name)) {
                throw new CloudRuntimeException("Invalid Host policy identity in staging synchronization response");
            }
            if (!binding.managed) { continue; } // Explicit Veeam paths remain operator-owned.
            String name = binding.name.replaceAll("[^A-Za-z0-9._-]", "_");
            if (List.of(".", "..").contains(name)) { throw new CloudRuntimeException("Invalid Host policy name"); }
            Path base = Path.of(target.request.configuration.rootPath, binding.provider, "bootstrap");
            String expected = ("ablestack-veeam".equals(binding.provider)
                    ? base.resolve(target.host.getName().replaceAll("[^A-Za-z0-9._-]", "_")).resolve(name) : base.resolve(name)).toString();
            if (!expected.equals(binding.path) || binding.previousPath == null) {
                throw new CloudRuntimeException("Host policy path differs from the requested staging configuration");
            }
        }
    }

    private void state(Target target, String revision, String state, String error) {
        // host_details values are limited to 255 characters. Never persist configuration contents or credentials.
        hostDetailsDao.persist(target.host.getId(), Map.of(STATUS_KEY,
                gson.toJson(Map.of("revision", revision, "state", state, "updated", System.currentTimeMillis())),
                ERROR_KEY, StringUtils.abbreviate(StringUtils.defaultString(error), 240)));
    }
}
