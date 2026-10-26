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
package org.apache.cloudstack.storage.dataservice;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.nio.charset.StandardCharsets;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.command.user.storage.dataservice.BaseStorageServiceConfigurationCmd;
import org.apache.cloudstack.storage.dataservice.dao.StorageAccessRuleDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageBlockTargetDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageFileShareDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceConfigurationOperationDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceInstanceDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceProtocolDao;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Short SQL reservation/commit; no transaction spans a guest RPC. */
final class StorageServiceConfigurationCoordinator {
    private static final Gson JSON = new Gson();
    private final StorageServiceInstanceDao instances;
    private final StorageServiceConfigurationOperationDao operations;
    private final StorageServiceProtocolDao protocols;
    private final StorageFileShareDao shares;
    private final StorageBlockTargetDao targets;
    private final StorageAccessRuleDao rules;
    private final StorageServiceGuestCommandDispatcher guest;
    private final StorageServiceConfigurationPreflight preflight;
    private final org.apache.cloudstack.storage.dataservice.dao.StorageServiceRuntimeUpgradeDao upgrades;

    StorageServiceConfigurationCoordinator(final StorageServiceInstanceDao instances,
            final StorageServiceConfigurationOperationDao operations, final StorageServiceProtocolDao protocols,
            final StorageFileShareDao shares, final StorageBlockTargetDao targets, final StorageAccessRuleDao rules,
            final StorageServiceGuestCommandDispatcher guest, final StorageServiceConfigurationPreflight preflight,
            final org.apache.cloudstack.storage.dataservice.dao.StorageServiceRuntimeUpgradeDao upgrades) {
        this.instances = instances; this.operations = operations; this.protocols = protocols; this.shares = shares;
        this.targets = targets; this.rules = rules; this.guest = guest; this.preflight = preflight; this.upgrades = upgrades;
    }

    <T> T execute(final BaseStorageServiceConfigurationCmd cmd, final StorageServiceInstanceVO instance,
            final StorageServiceInstance.Protocol protocol, final Class<T> responseType, final Supplier<T> candidate,
            final Consumer<StorageServiceConfigurationPlan> context, final Runnable baselineBuilder, final String vmUuid) {
        final String requestId = cmd.getConfigurationRequestId(), hash = requestHash(cmd);
        final boolean[] created = {false};
        final StorageServiceConfigurationOperationVO op = Transaction.execute((com.cloud.utils.db.TransactionCallback<StorageServiceConfigurationOperationVO>) status -> {
            StorageServiceInstanceVO locked = instances.lockRow(instance.getId(), true);
            StorageServiceConfigurationOperationVO existing = operations.findByRequest(instance.getId(), requestId);
            if (existing != null) {
                if (!hash.equals(existing.getRequestHash())) throw new CloudRuntimeException("requestid conflicts with the accepted public configuration");
                return existing;
            }
            if (StorageServiceConfigurationPreflight.runtimeWriting(upgrades.findActiveByInstanceId(instance.getId())))
                throw new CloudRuntimeException("RUNTIME_WRITER_ACTIVE");
            if (locked.getActiveConfigurationOperationId() != null) throw new CloudRuntimeException("Configuration recovery is pending; inspect the active operation before a new change");
            if (cmd.getExpectedRevision() != null && cmd.getExpectedRevision() != locked.getConfigurationRevision())
                throw new CloudRuntimeException("Configuration revision changed; update the form before submitting");
            StorageServiceConfigurationOperationVO value = new StorageServiceConfigurationOperationVO();
            value.setInstanceId(instance.getId()); value.setAccountId(instance.getAccountId()); value.setDomainId(instance.getDomainId());
            value.setRequestId(requestId); value.setRequestHash(hash); value.setProtocol(protocol.name());
            value.setResourceType(cmd.getClass().getSimpleName()); value.setAction(action(cmd));
            value.setBaseRevision(locked.getConfigurationRevision()); value.setTargetRevision(locked.getConfigurationRevision() + 1);
            if (cmd.getJob() instanceof org.apache.cloudstack.api.InternalIdentity) value.setJobId(((org.apache.cloudstack.api.InternalIdentity)cmd.getJob()).getId());
            value.setState("PENDING"); value.setPhase("PREPARING");
            value = operations.persist(value); created[0] = true;
            locked.setActiveConfigurationOperationId(value.getId()); instances.update(locked.getId(), locked);
            return value;
        });
        if (!created[0]) return replay(op, responseType);
        StorageServiceConfigurationPlan plan = null;
        try {
            // A version gate precedes candidate preparation and any guest/configuration mutation.
            if (instance.getVmId() != null) {
                JsonObject capability = rpc(instance, "configuration capability show", new JsonObject());
                if (protocol == StorageServiceInstance.Protocol.NFS && (!capability.has("nfsPosixIntentVersion") || capability.get("nfsPosixIntentVersion").getAsInt() != 1))
                    throw new CloudRuntimeException("NFS_POSIX_CAPABILITY_REQUIRED");
                if (smbEndpointCommand(cmd) && (!capability.has("smbListenerProfilesVersion") || capability.get("smbListenerProfilesVersion").getAsInt() != 1))
                    throw new CloudRuntimeException("SMB_LISTENER_CAPABILITY_REQUIRED");
                if (!capability.has("configurationPreflightVersion") || capability.get("configurationPreflightVersion").getAsInt()!=1)
                    throw new CloudRuntimeException("PREFLIGHT_CAPABILITY_REQUIRED");
                if (!capability.has("configurationTransactionVersion") || capability.get("configurationTransactionVersion").getAsInt() != 1)
                    throw new CloudRuntimeException("SharedFS configuration transaction module v1 is required on the System VM");
                for (StorageServiceConfigurationOperationVO previous : operations.listByInstance(instance.getId())) {
                    if ("COMMITTED".equals(previous.getState()) && previous.getTargetRevision() == op.getBaseRevision()) {
                        // Recover a lost metadata ACK using the authoritative committed SQL revision.
                        rpc(instance, "configuration operation commit", identity(previous, instance));
                        break;
                    }
                }
            }
            JsonObject accepted = new JsonObject();
            accepted.add("publicRequest", JSON.toJsonTree(publicRequest(cmd)));
            op.setCandidateSnapshot(accepted.toString()); save(op);
            JsonObject report = preflight.observe(instances.findById(instance.getId()), StorageServiceConfigurationPreflight.commandIntent(cmd), op.getId());
            StorageServiceConfigurationPreflight.requireReady(report, cmd.getImpactAck());
            op.setGuestObservationJson(report.toString()); save(op);
            plan = new StorageServiceConfigurationPlan(instance.getId(), protocols, shares, targets, rules,
                    snapshot -> { op.setCandidateSnapshot(snapshot); save(op); });
            plan.operationUuid = op.getUuid();
            op.setResourceUuid(plan.resourceUuid(cmd.getClass().getSimpleName(), publicRequest(cmd)));
            op.setBeforeSnapshot(plan.snapshot()); op.setState("RUNNING"); op.setPhase("VALIDATING"); save(op);
            context.accept(plan);
            plan.baseline(true); baselineBuilder.run();
            JsonObject beforeCommands = commandJson(plan.commands);
            plan.reset(); plan.baseline(false);
            T response = candidate.get();
            JsonElement publicResult = JSON.toJsonTree(response, responseType);
            if (publicResult.isJsonObject() && publicResult.getAsJsonObject().has("id")) op.setResourceUuid(publicResult.getAsJsonObject().get("id").getAsString());
            plan.validateNaturalKeys();
            JsonObject snapshot = new JsonObject();
            snapshot.add("tables", JsonParser.parseString(plan.snapshot()));
            snapshot.add("response", JSON.toJsonTree(response, responseType));
            snapshot.add("publicRequest", JSON.toJsonTree(publicRequest(cmd)));
            snapshot.add("networkChanges", JSON.toJsonTree(plan.networkChanges));
            op.setCandidateSnapshot(JSON.toJson(snapshot)); StorageServiceConfigurationPlan.bounded(op.getCandidateSnapshot()); save(op);
            context.accept(null);
            if (instance.getVmId() == null) {
                commit(instance, op, plan); return response;
            }
            JsonObject payload = identity(op, instance);
            if (vmUuid != null) payload.addProperty("vmUuid", vmUuid);
            payload.add("before", beforeCommands); payload.add("candidate", commandJson(plan.commands));
            if (protocol == StorageServiceInstance.Protocol.NFS && payload.getAsJsonObject("candidate").has("nfs export apply")) {
                JsonObject nfs = payload.getAsJsonObject("candidate").getAsJsonObject("nfs export apply");
                nfs.addProperty("configurationOperationId", op.getUuid());
                nfs.add("posixIntent", StorageNfsPosix.intent(cmd, op, report));
            }
            JsonObject admission = report.getAsJsonObject("intent").deepCopy();
            admission.addProperty("requestbytes", payload.toString().getBytes(StandardCharsets.UTF_8).length);
            payload.add("preflight", admission); payload.add("executionProfile", report.getAsJsonObject("profile"));
            op.setPhase("APPLYING"); save(op);
            StorageServiceGuestCommandResult result = guest.dispatch(new StorageServiceGuestCommand(instance.getVmId(),
                    "configuration operation apply", JSON.toJson(payload), 20,
                    new HashSet<>(Arrays.asList("password", "secrets", "chapSecret", "mutualChapSecret", "dhChapKey", "dhChapCtrlKey"))));
            JsonObject observation = parsed(result.getResultJson());
            observation = observeUntilDecision(instance, op, observation, report.getAsJsonObject("profile").get("observationseconds").getAsInt());
            if (!matches(op, observation)) {
                op.setPhase("OBSERVING"); op.setState("RECOVERY_REQUIRED"); op.setGuestObservationJson("{\"code\":\"RESPONSE_UNCONFIRMED\"}"); save(op);
                throw new CloudRuntimeException("Configuration response is unconfirmed; observe operation " + op.getUuid() + " before recovery");
            }
            op.setGuestObservationJson(JSON.toJson(observation));
            if (observation.has("originalfailure")) op.setOriginalFailureJson(JSON.toJson(observation.get("originalfailure")));
            if (observation.has("rollback")) op.setRollbackResultJson(JSON.toJson(observation.get("rollback")));
            String state = observation.has("state") ? observation.get("state").getAsString() : "RECOVERY_REQUIRED";
            if ("VERIFIED".equals(state)) {
                if (smbEndpointCommand(cmd)) {
                    commitSmbListeners(instance, op, plan);
                    return response;
                }
                commit(instance, op, plan);
                try { rpc(instance, "configuration operation commit", identity(op, instance)); }
                catch (RuntimeException e) { /* DB commit is authoritative; never roll back an acknowledged SQL revision. */ }
                return response;
            }
            if ("ROLLED_BACK".equals(state)) {
                plan.discardReservations(); finish(op, "ROLLED_BACK", true);
            } else { op.setState("RECOVERY_REQUIRED"); op.setPhase("OBSERVING"); save(op); }
            throw new CloudRuntimeException("Configuration " + op.getUuid() + " ended as " + op.getState() + "; inspect the preserved failure and rollback result");
        } catch (RuntimeException error) {
            if ("PENDING".equals(op.getState()) || "VALIDATING".equals(op.getPhase())) {
                if (error instanceof StorageServiceConfigurationPreflight.Rejected) {
                    JsonObject denied = ((StorageServiceConfigurationPreflight.Rejected)error).report;
                    op.setGuestObservationJson(denied.toString());
                    JsonObject failure = new JsonObject(); failure.addProperty("phase","PREFLIGHT");
                    failure.addProperty("code",denied.getAsJsonArray("checks").asList().stream().filter(JsonElement::isJsonObject).map(JsonElement::getAsJsonObject)
                        .filter(c -> c.has("blocking") && c.get("blocking").getAsBoolean()).map(c -> c.has("code") ? c.get("code").getAsString() : "RESOURCE_OBSERVATION_UNAVAILABLE")
                        .findFirst().orElse("RESOURCE_OBSERVATION_UNAVAILABLE"));
                    op.setOriginalFailureJson(failure.toString());
                } else op.setOriginalFailureJson("{\"code\":\"PREPARATION_REJECTED\",\"phase\":\"VALIDATING\",\"type\":\"" + error.getClass().getSimpleName() + "\"}");
                op.setRollbackResultJson("{\"state\":\"NOT_NEEDED\"}");
                if (plan != null) plan.discardReservations(); finish(op, "ROLLED_BACK", true);
            } else if ("RUNNING".equals(op.getState())) {
                // An RPC/SQL exception can be ambiguous. Keep reservation; a read never starts compensation.
                op.setState("RECOVERY_REQUIRED"); op.setPhase("OBSERVING"); save(op);
            }
            throw error;
        } finally { context.accept(null); }
    }

    void resume(final StorageServiceInstanceVO instance, final StorageServiceConfigurationOperationVO op,
            final Consumer<StorageServiceConfigurationPlan> recoverDatabaseActions) {
        if ("COMMITTED".equals(op.getState()) || "ROLLED_BACK".equals(op.getState())) return;
        StorageServiceInstanceVO current = instances.findById(instance.getId());
        if (!Objects.equals(current.getActiveConfigurationOperationId(), op.getId()))
            throw new CloudRuntimeException("Configuration reservation does not belong to this operation");
        if (instance.getVmId() == null || op.getBeforeSnapshot() == null || op.getCandidateSnapshot() == null)
            throw new CloudRuntimeException("Configuration recovery requires the original System VM and durable candidate");
        if (smbEndpointOperation(op) && current.getConfigurationRevision() == op.getTargetRevision()) {
            finalizeSmbListeners(instance, op);
            return;
        }
        JsonObject observation = rpc(instance, "configuration operation show", identity(op, instance));
        if (!matches(op, observation)) throw new CloudRuntimeException("Guest configuration identity is unconfirmed");
        String state = observation.has("state") ? observation.get("state").getAsString() : "UNKNOWN";
        if (!Arrays.asList("VERIFIED", "COMMITTED", "ROLLED_BACK").contains(state))
            observation = rpc(instance, "configuration operation resume", identity(op, instance));
        if (!matches(op, observation)) throw new CloudRuntimeException("Guest configuration identity is unconfirmed");
        op.setGuestObservationJson(JSON.toJson(observation));
        if (observation.has("originalfailure")) op.setOriginalFailureJson(JSON.toJson(observation.get("originalfailure")));
        if (observation.has("rollback")) op.setRollbackResultJson(JSON.toJson(observation.get("rollback")));
        state = observation.has("state") ? observation.get("state").getAsString() : "UNKNOWN";
        StorageServiceConfigurationPlan plan = new StorageServiceConfigurationPlan(instance.getId(), protocols, shares, targets, rules, text -> { });
        plan.restore(op.getCandidateSnapshot(), op.getBeforeSnapshot());
        JsonObject saved = parsed(op.getCandidateSnapshot());
        if (saved.has("networkChanges")) for (JsonElement item : saved.getAsJsonArray("networkChanges")) plan.networkChanges.add(item.getAsJsonObject());
        if ("VERIFIED".equals(state) || "COMMITTED".equals(state)) {
            recoverDatabaseActions.accept(plan);
            if (smbEndpointOperation(op)) {
                commitSmbListeners(instance, op, plan);
                return;
            }
            commit(instance, op, plan);
            try { rpc(instance, "configuration operation commit", identity(op, instance)); } catch (RuntimeException ignored) { }
        } else if ("ROLLED_BACK".equals(state)) {
            plan.discardReservations(); finish(op, "ROLLED_BACK", true);
        } else { op.setState("RECOVERY_REQUIRED"); op.setPhase("OBSERVING"); save(op); }
    }

    static boolean smbEndpointCommand(final BaseStorageServiceConfigurationCmd cmd) {
        if (cmd instanceof org.apache.cloudstack.api.command.user.storage.dataservice.EnableStorageServiceProtocolCmd)
            return "SMB".equalsIgnoreCase(((org.apache.cloudstack.api.command.user.storage.dataservice.EnableStorageServiceProtocolCmd)cmd).getProtocol());
        if (cmd instanceof org.apache.cloudstack.api.command.user.storage.dataservice.DeleteStorageServiceProtocolCmd)
            return "SMB".equalsIgnoreCase(((org.apache.cloudstack.api.command.user.storage.dataservice.DeleteStorageServiceProtocolCmd)cmd).getProtocol());
        return false;
    }

    static boolean smbEndpointOperation(final StorageServiceConfigurationOperationVO op) {
        return "SMB".equals(op.getProtocol()) && Arrays.asList("EnableStorageServiceProtocolCmd", "DeleteStorageServiceProtocolCmd").contains(op.getResourceType());
    }

    private void commitSmbListeners(final StorageServiceInstanceVO instance, final StorageServiceConfigurationOperationVO op,
            final StorageServiceConfigurationPlan plan) {
        op.setState("RUNNING"); op.setPhase("SMB_FINALIZING"); save(op);
        Transaction.execute((com.cloud.utils.db.TransactionCallback<Boolean>) status -> {
            StorageServiceInstanceVO locked = instances.lockRow(instance.getId(), true);
            if (!Objects.equals(locked.getActiveConfigurationOperationId(), op.getId()))
                throw new CloudRuntimeException("SMB listener reservation changed before commit");
            if (locked.getConfigurationRevision() == op.getTargetRevision()) return true;
            if (locked.getConfigurationRevision() != op.getBaseRevision())
                throw new CloudRuntimeException("SMB listener revision changed before commit");
            plan.commit();
            locked.setConfigurationRevision(op.getTargetRevision());
            // Keep the reservation until the guest confirms final listener cleanup.
            instances.update(locked.getId(), locked);
            return true;
        });
        finalizeSmbListeners(instance, op);
    }

    private void finalizeSmbListeners(final StorageServiceInstanceVO instance, final StorageServiceConfigurationOperationVO op) {
        try {
            JsonObject observed = rpc(instance, "configuration operation commit", identity(op, instance));
            if (!matches(op, observed) || !observed.has("state") || !"COMMITTED".equals(observed.get("state").getAsString()))
                throw new CloudRuntimeException("SMB listener finalization is unconfirmed");
            op.setGuestObservationJson(JSON.toJson(observed));
            finish(op, "COMMITTED", true);
        } catch (RuntimeException error) {
            op.setState("RECOVERY_REQUIRED"); op.setPhase("SMB_FINALIZING");
            op.setOriginalFailureJson("{\"code\":\"SMB_FINALIZATION_UNCONFIRMED\",\"phase\":\"SMB_FINALIZING\"}");
            save(op);
            throw new CloudRuntimeException("SMB listener finalization is pending; resume operation " + op.getUuid());
        }
    }

    private void commit(final StorageServiceInstanceVO instance, final StorageServiceConfigurationOperationVO op,
            final StorageServiceConfigurationPlan plan) {
        op.setPhase("COMMITTING"); save(op);
        Transaction.execute((com.cloud.utils.db.TransactionCallback<Boolean>) status -> {
            StorageServiceInstanceVO locked = instances.lockRow(instance.getId(), true);
            if (locked.getConfigurationRevision() == op.getTargetRevision()
                    && "COMMITTED".equals(operations.findById(op.getId()).getState())) return true;
            if (!Objects.equals(locked.getActiveConfigurationOperationId(), op.getId()) || locked.getConfigurationRevision() != op.getBaseRevision())
                throw new CloudRuntimeException("Configuration reservation/revision changed before commit");
            plan.commit();
            locked.setConfigurationRevision(op.getTargetRevision()); locked.setActiveConfigurationOperationId(null);
            instances.update(locked.getId(), locked);
            op.setState("COMMITTED"); op.setPhase("COMPLETED"); op.setCompleted(new Date()); save(op);
            return true;
        });
    }
    private void finish(final StorageServiceConfigurationOperationVO op, final String state, final boolean release) {
        Transaction.execute((com.cloud.utils.db.TransactionCallback<Boolean>) status -> {
            StorageServiceInstanceVO locked = instances.lockRow(op.getInstanceId(), true);
            if (release && Objects.equals(locked.getActiveConfigurationOperationId(), op.getId())) {
                locked.setActiveConfigurationOperationId(null); instances.update(locked.getId(), locked);
            }
            op.setState(state); op.setPhase("COMPLETED"); op.setCompleted(new Date()); save(op); return true;
        });
    }
    private void save(final StorageServiceConfigurationOperationVO value) {
        StorageServiceConfigurationOperationVO update = operations.createForUpdate(value.getId());
        update.setResourceUuid(value.getResourceUuid());
        update.setState(value.getState()); update.setPhase(value.getPhase()); update.setUpdated(new Date());
        update.setBeforeSnapshot(value.getBeforeSnapshot()); update.setCandidateSnapshot(value.getCandidateSnapshot());
        // Do not overwrite a failure recorded by an earlier phase/recovery attempt.
        StorageServiceConfigurationOperationVO current = operations.findById(value.getId());
        if (current.getOriginalFailureJson() == null) update.setOriginalFailureJson(value.getOriginalFailureJson());
        update.setRollbackResultJson(value.getRollbackResultJson()); update.setGuestObservationJson(value.getGuestObservationJson());
        update.setCompleted(value.getCompleted()); operations.update(value.getId(), update);
    }
    private <T> T replay(final StorageServiceConfigurationOperationVO op, final Class<T> type) {
        if (!"COMMITTED".equals(op.getState())) throw new CloudRuntimeException("Existing configuration operation " + op.getUuid() + " is " + op.getState() + "; no duplicate apply was sent");
        return JSON.fromJson(parsed(op.getCandidateSnapshot()).get("response"), type);
    }
    private static String action(final BaseStorageServiceConfigurationCmd cmd) {
        String n = cmd.getClass().getSimpleName(); return n.startsWith("Delete") ? "DELETE" : n.startsWith("Update") ? "UPDATE" : "CREATE";
    }
    static String requestHash(final BaseStorageServiceConfigurationCmd cmd) {
        SortedMap<String, Object> fields = publicRequest(cmd);
        fields.remove("impactack");
        return DigestUtils.sha256Hex(JSON.toJson(fields).getBytes(StandardCharsets.UTF_8));
    }
    static SortedMap<String, Object> publicRequest(final BaseStorageServiceConfigurationCmd cmd) {
        SortedMap<String, Object> fields = new TreeMap<>();
        fields.put("api", cmd.getClass().getSimpleName());
        for (Class<?> c = cmd.getClass(); c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                Parameter p = f.getAnnotation(Parameter.class);
                if (p == null || p.name().equals("requestid") || p.name().toLowerCase(Locale.ROOT).matches(".*(password|secret|chapkey|chapctrlkey).*")) continue;
                try { f.setAccessible(true); fields.put(p.name(), f.get(cmd)); }
                catch (IllegalAccessException e) { throw new CloudRuntimeException("Cannot canonicalize configuration request", e); }
            }
        }
        return fields;
    }
    private static JsonObject commandJson(final Map<String, JsonObject> commands) {
        JsonObject value = new JsonObject(); commands.forEach(value::add); return value;
    }
    static JsonObject parsed(final String value) {
        try { JsonElement e = JsonParser.parseString(value); return e.isJsonObject() ? e.getAsJsonObject() : new JsonObject(); }
        catch (RuntimeException e) { return new JsonObject(); }
    }
    private static JsonObject identity(final StorageServiceConfigurationOperationVO op, final StorageServiceInstanceVO instance) {
        JsonObject p = new JsonObject(); p.addProperty("operationid", op.getUuid()); p.addProperty("instanceUuid", instance.getUuid());
        p.addProperty("baseRevision", op.getBaseRevision()); p.addProperty("targetRevision", op.getTargetRevision()); return p;
    }
    private JsonObject observeUntilDecision(StorageServiceInstanceVO instance, StorageServiceConfigurationOperationVO op,
            JsonObject first, int seconds) {
        JsonObject value = first;
        long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(Math.min(2100, Math.max(60,seconds)));
        int unavailable = 0;
        while (System.nanoTime() < end) {
            if (matches(op,value)) {
                String state = value.has("state") ? value.get("state").getAsString() : "UNKNOWN";
                if (Arrays.asList("VERIFIED","COMMITTED","ROLLED_BACK").contains(state) ||
                    "RECOVERY_REQUIRED".equals(state) && (!value.has("workerActive") || !value.get("workerActive").getAsBoolean())) return value;
            }
            try { Thread.sleep(2000); value = rpc(instance,"configuration operation show",identity(op,instance)); unavailable=0; }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            catch (RuntimeException e) { if (++unavailable >= 3) break; }
        }
        return value;
    }
    private static boolean matches(final StorageServiceConfigurationOperationVO op, final JsonObject observation) {
        return observation.has("operationid") && op.getUuid().equals(observation.get("operationid").getAsString());
    }
    private JsonObject rpc(final StorageServiceInstanceVO instance, final String operation, final JsonObject payload) {
        StorageServiceGuestCommandResult r = guest.dispatch(new StorageServiceGuestCommand(instance.getVmId(), operation,
                JSON.toJson(payload), 20, Collections.emptySet()));
        JsonObject value = parsed(r.getResultJson());
        if (!r.isSuccess() && !value.has("operationid")) throw new CloudRuntimeException("Unable to observe SharedFS configuration transaction capability/outcome");
        return value;
    }
}
