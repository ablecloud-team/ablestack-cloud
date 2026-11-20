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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import javax.inject.Inject;
import org.apache.cloudstack.framework.config.ConfigDepot;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.host.HostVO;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Apply managed Client limits before publishing Global; private Host journals protect uncertain outcomes. */
public class NetBackupHookTimeoutService extends ManagerBase {
    @Inject private NetBackupScheduleModeGuard guard;
    @Inject private AgentManager agents;
    @Inject private ConfigDepot configDepot;
    @Inject private ConfigurationDao configDao;

    public <T> T update(String value, Supplier<T> save) {
        final int target;
        try {
            target = Integer.parseInt(value);
            if (target <= 0) { throw new NumberFormatException(); }
        } catch (NumberFormatException error) {
            throw failure("INVALID_VALUE", "Use a positive Integer in seconds", error);
        }
        try { return guard.withIdleHosts(hosts -> synchronize(hosts, target, save)); }
        catch (RuntimeException error) {
            if (error.getMessage() != null && error.getMessage().startsWith("NETBACKUP_HOOK_TIMEOUT_")) { throw error; }
            throw failure("UNSAFE", "Deactivate configured policies and finish pending work; " + error.getMessage(), error);
        }
    }

    private <T> T synchronize(List<HostVO> hosts, int target, Supplier<T> save) {
        int previous = Integer.parseInt(configDao.getValue(BackupManager.NetBackupHookTimeout.key()));
        List<Change> checked = new ArrayList<>();
        List<Change> attempted = new ArrayList<>();
        String operation = UUID.randomUUID().toString();
        boolean publishing = false;
        try {
            for (HostVO host : hosts) {
                Change change = new Change(host, payload(host, operation, previous, target));
                send(change, "RECOVER", "RECOVERED");
                JsonObject result = send(change, "CHECK", "CHECKED", "SKIPPED");
                if ("SKIPPED".equals(result.get("state").getAsString())) { continue; }
                change.payload.put("fingerprint", result.get("fingerprint").getAsString());
                checked.add(change);
            }
            for (Change change : checked) {
                // Include lost APPLY responses in rollback; only the Host knows whether it wrote the file.
                attempted.add(change);
                send(change, "APPLY", "APPLIED");
            }
            publishing = true;
            T result = save.get();
            complete(attempted);
            return result;
        } catch (RuntimeException error) {
            String actual = configDao.getValue(BackupManager.NetBackupHookTimeout.key());
            if (publishing && String.valueOf(target).equals(actual)) {
                // The DB can commit before an event/cache error. Never undo files behind its published value.
                complete(attempted);
                throw failure("SAVE_UNCONFIRMED", "Refresh the Global value; Host application was confirmed", error);
            }
            if (!String.valueOf(previous).equals(actual)) {
                throw failure("RECOVERY_PENDING", "Global changed unexpectedly; resave after inspection. Operation " + operation, error);
            }
            boolean recovered = true;
            for (int i = attempted.size() - 1; i >= 0; i--) {
                try { send(attempted.get(i), "ROLLBACK", "ROLLED_BACK"); }
                catch (RuntimeException rollback) {
                    recovered = false;
                    logger.warn("Hook timeout rollback is unconfirmed for Host [{}], operation [{}]", attempted.get(i).host.getName(), operation);
                }
            }
            throw failure(recovered ? "SYNC_FAILED" : "RECOVERY_PENDING",
                    (recovered ? "Global was not changed; Host files were restored. " : "Resave the setting to reconcile pending Host files. ")
                            + "Operation " + operation + "; " + error.getMessage(), error);
        }
    }

    private void complete(List<Change> changes) {
        for (Change change : changes) {
            try { send(change, "COMMIT", "COMMITTED"); }
            catch (RuntimeException error) {
                // A confirmed APPLIED journal matching Global is safe for new Hooks.
                // Resaving the setting retires the journal after a lost COMMIT response.
                logger.warn("Hook timeout journal retirement will be retried for Host [{}]", change.host.getName());
            }
        }
    }

    private Map<String, Object> payload(HostVO host, String operation, int previous, int target) {
        Map<String, Object> result = new HashMap<>();
        result.put("operationid", operation);
        result.put("previous", previous);
        result.put("timeout", target);
        result.put("hostname", host.getName());
        result.put("hostip", host.getPrivateIpAddress());
        result.put("netbackupurl", configDepot.get("backup.plugin.netbackup.url").valueInScope(ConfigKey.Scope.Zone, host.getDataCenterId()));
        result.put("netbackupapikey", configDepot.get("backup.plugin.netbackup.apikey").valueInScope(ConfigKey.Scope.Zone, host.getDataCenterId()));
        return result;
    }

    private JsonObject send(Change change, String action, String... expected) {
        try {
            change.payload.put("action", "HOOK_TIMEOUT_" + action);
            NetBackupHostConfigurationCommand command = new NetBackupHostConfigurationCommand("HOOK_TIMEOUT_" + action, new Gson().toJson(change.payload));
            command.setWait(200);
            Answer answer = agents.send(change.host.getId(), command);
            if (answer == null || !answer.getResult()) { throw new IllegalStateException("Host response unavailable"); }
            JsonObject result = JsonParser.parseString(answer.getDetails()).getAsJsonObject();
            String state = result.get("state").getAsString();
            if (!List.of(expected).contains(state)) {
                throw new IllegalStateException(result.has("message") ? result.get("message").getAsString() : state);
            }
            return result;
        } catch (Exception error) {
            throw failure("SYNC_FAILED", change.host.getName() + " / " + action + ": " + error.getMessage(), error);
        }
    }

    private static CloudRuntimeException failure(String code, String detail, Throwable cause) {
        return new CloudRuntimeException("NETBACKUP_HOOK_TIMEOUT_" + code + ": " + detail, cause);
    }

    private static final class Change {
        private final HostVO host;
        private final Map<String, Object> payload;
        private Change(HostVO host, Map<String, Object> payload) { this.host = host; this.payload = payload; }
    }
}
