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
package com.cloud.vm.process;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.VmProcessAction;
import com.cloud.agent.api.VmProcessActionAnswer;
import com.cloud.agent.api.VmProcessActionCommand;
import com.cloud.agent.api.VmProcessSnapshot;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.user.AccountManager;
import com.cloud.utils.db.TransactionLegacy;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.UserVmDao;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import org.apache.cloudstack.acl.SecurityChecker.AccessType;
import org.apache.cloudstack.api.response.VmProcessActionResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.vm.process.VmProcessActionService;
import org.apache.cloudstack.vm.process.VmProcessSnapshotService;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.Objects;
import java.util.UUID;

import javax.inject.Inject;

public class VmProcessActionServiceImpl extends com.cloud.utils.component.ManagerBase
        implements VmProcessActionService, com.cloud.utils.component.PluggableService {
    @Inject private UserVmDao vmDao;
    @Inject private HostDao hostDao;
    @Inject private AccountManager accountManager;
    @Inject private AgentManager agentManager;
    @Inject private VmProcessSnapshotService snapshots;
    @Inject private org.apache.cloudstack.vm.process.VmProcessProfileService profiles;
    private VmProcessOperationStore store = new VmProcessOperationStore();
    private static final Gson JSON =
            new GsonBuilder().serializeNulls().disableHtmlEscaping().create();

    @Override
    public List<Class<?>> getCommands() {
        return List.of(
                org.apache.cloudstack.api.command.user.vm.RestartVirtualMachineProcessCmd.class,
                org.apache.cloudstack.api.command.user.vm.TerminateVirtualMachineProcessCmd.class,
                org.apache.cloudstack.api.command.user.vm.KillVirtualMachineProcessCmd.class,
                org.apache.cloudstack.api.command.user.vm.RestartVirtualMachineServiceCmd.class,
                org.apache.cloudstack.api.command.user.vm.GetVirtualMachineProcessOperationCmd
                        .class);
    }

    boolean enabled() {
        return Boolean.TRUE.equals(VmProcessCapabilityServiceImpl.MANAGEMENT_ENABLED.value());
    }

    private UserVmVO authorized(long id, boolean mutate) {
        UserVmVO vm = vmDao.findById(id);
        if (vm == null || vm.getRemoved() != null || vm.getType() != VirtualMachine.Type.User)
            throw new InvalidParameterValueException("User VM not found");
        accountManager.checkAccess(
                CallContext.current().getCallingAccount(),
                mutate ? AccessType.OperateEntry : AccessType.ListEntry,
                true,
                vm);
        if (!enabled())
            throw new InvalidParameterValueException("VM process management is disabled");
        return vm;
    }

    static void uuid(String s) {
        try {
            if (s != null && UUID.fromString(s).toString().equals(s)) return;
        } catch (IllegalArgumentException e) {
            // Invalid input must remain a parameter error, before any journal access.
        }
        throw new InvalidParameterValueException("Canonical UUID required");
    }

    static String fingerprint(long vm, String snapshot, long pid, String action, String service) {
        try {
            return org.apache.commons.codec.binary.Hex.encodeHexString(
                            java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            JSON.toJson(
                                                            Arrays.asList(
                                                                    vm, snapshot, pid, action,
                                                                    service))
                                                    .getBytes(
                                                            java.nio.charset.StandardCharsets
                                                                    .UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean placement(UserVmVO vm, VmProcessOperationStore.Record r) {
        return vm.getState() == VirtualMachine.State.Running
                && vm.getHypervisorType() == HypervisorType.KVM
                && Objects.equals(vm.getHostId(), r.host)
                && vm.getUpdated() == r.generation;
    }

    @Override
    public VmProcessActionResponse execute(
            long vmId,
            String requestId,
            String snapshotId,
            long pid,
            String action,
            String service) {
        return executeFixed(vmId,requestId,snapshotId,pid,action,service,null,0);
    }

    @Override public VmProcessActionResponse restartProfile(long vmId,String requestId,String snapshotId,long pid,String profileId,int profileVersion) {
        uuid(profileId);if(profileVersion<1)throw new InvalidParameterValueException("Profile version required");
        return executeFixed(vmId,requestId,snapshotId,pid,"process.restart",null,profileId,profileVersion);
    }

    private VmProcessActionResponse executeFixed(long vmId,String requestId,String snapshotId,long pid,String action,String service,String profileId,int profileVersion) {
        uuid(requestId);
        uuid(snapshotId);
        if (pid < 1
                || pid > 4294967295L
                || !Set.of("process.terminate", "process.kill", "service.restart", "process.restart").contains(action)
                || ("service.restart".equals(action)
                        ? service == null || !service.matches("[A-Za-z0-9_@.:-]{1,256}")
                        : service != null))
            throw new InvalidParameterValueException("Invalid fixed action target");
        UserVmVO vm = authorized(vmId, true);
        long caller = CallContext.current().getCallingAccountId();
        String digest = fingerprint(vmId, snapshotId, pid, action, profileId==null?service:profileId+"@"+profileVersion);
        VmProcessOperationStore.Record record;
        try {
            record = store.find(caller, requestId);
            if (record != null) {
                if (record.vm != vmId || !digest.equals(record.fingerprint))
                    throw new InvalidParameterValueException("REQUEST_CONFLICT");
                return audited(vmId, VmProcessAction.parse(record.result), "REPLAY");
            }
            Map<String, Object> target = snapshots.actionTarget(vmId, snapshotId, pid, service);
            Map<String,Object> profile=profileId==null?null:profiles.approved(vmId,profileId,profileVersion);
            record = new VmProcessOperationStore.Record();
            record.operation = UUID.randomUUID().toString();
            record.request = requestId;
            record.account = caller;
            record.vm = vmId;
            record.host = vm.getHostId();
            record.generation = vm.getUpdated();
            record.fingerprint = digest;
            Map<String, Object> request = new LinkedHashMap<>(target);
            request.put("schemaVersion", profile==null?"1.0":"1.1");
            if(profile!=null)request.put("profile",profile);
            request.put("kind", "actionRequest");
            request.put("requestId", requestId);
            request.put("operationId", record.operation);
            request.put("snapshotId", snapshotId);
            request.put("action", action);
            request.put("budgetMs", ("service.restart".equals(action) || "process.restart".equals(action)) ? 85000 : 14000);
            record.json = JSON.toJson(request);
            request = VmProcessAction.parse(record.json);
            record.result = JSON.toJson(VmProcessAction.unknown(request));
            record.state = "UNKNOWN";
            try {
                store.reserve(record);
            } catch (java.sql.SQLException e) {
                VmProcessOperationStore.Record other = store.find(caller, requestId);
                if (other != null && other.vm == vmId && digest.equals(other.fingerprint))
                    return audited(vmId, VmProcessAction.parse(other.result), "REPLAY");
                throw new InvalidParameterValueException(
                        "BUSY or REQUEST_CONFLICT; no mutation dispatched");
            }
            Map<String, Object> result = VmProcessAction.unknown(request);
            boolean dispatched = false;
            // Lock the VM row through the bounded agent call. Lifecycle state transitions
            // cannot commit a new placement while this command owns the row fence.
            try (java.sql.Connection tx =
                    TransactionLegacy.getStandaloneConnectionWithException()) {
                tx.setAutoCommit(false);
                try {
                    boolean valid;
                    try (java.sql.PreparedStatement fence =
                            tx.prepareStatement(
                                    "SELECT state,host_id,update_count FROM vm_instance WHERE id=?"
                                        + " AND removed IS NULL FOR UPDATE")) {
                        fence.setLong(1, vmId);
                        fence.setQueryTimeout(5);
                        try (java.sql.ResultSet row = fence.executeQuery()) {
                            valid =
                                    row.next()
                                            && "Running".equals(row.getString(1))
                                            && row.getLong(2) == record.host
                                            && row.getLong(3) == record.generation;
                        }
                    }
                    authorized(vmId, true);
                    if (!valid) result = VmProcessAction.rejected(request, "STALE_AUTHORITY");
                    else {
                        snapshots.actionTarget(vmId, snapshotId, pid, service);
                        if(profile!=null)profiles.fence(tx,vmId,profile);
                        dispatched = true;
                        Answer answer =
                                agentManager.send(
                                        record.host,
                                        new VmProcessActionCommand(
                                                vm.getUuid(), record.json, false));
                        if (answer instanceof VmProcessActionAnswer && answer.getResult())
                            result =
                                    VmProcessAction.decode(
                                            ((VmProcessActionAnswer) answer).getResultJson(),
                                            request);
                    }
                    tx.commit();
                } catch (Exception e) {
                    tx.rollback();
                    if (!dispatched)
                        result =
                                VmProcessAction.rejected(
                                        request,
                                        "CHECK_FAILED"); /* durable UNKNOWN prevents replay */
                }
            }
            store.finish(record, (String) result.get("state"), JSON.toJson(result));
            return audited(vmId, result, "RESULT");
        } catch (java.sql.SQLException | java.io.IOException e) {
            throw new InvalidParameterValueException(
                    "Process operation store unavailable; do not repeat with a new requestId");
        }
    }

    @Override
    public VmProcessActionResponse get(long vmId, String operationId, String requestId) {
        if ((operationId == null) == (requestId == null))
            throw new InvalidParameterValueException(
                    "Exactly one operationId or requestId required");
        uuid(operationId == null ? requestId : operationId);
        UserVmVO vm = authorized(vmId, false);
        try {
            VmProcessOperationStore.Record r =
                    operationId == null
                            ? store.find(CallContext.current().getCallingAccountId(), requestId)
                            : store.get(vmId, operationId);
            if (r == null || r.vm != vmId)
                throw new InvalidParameterValueException("Operation not found");
            Map<String, Object> result = VmProcessAction.parse(r.result);
            if ("UNKNOWN".equals(r.state) && placement(vm, r)) {
                Map<String, Object> original = VmProcessAction.parse(r.json),
                        query = new LinkedHashMap<>();
                for (String k : List.of("schemaVersion", "authority", "operationId"))
                    query.put(k, original.get(k));
                query.put("kind", "readRequest");
                query.put("requestId", UUID.randomUUID().toString());
                query.put("operation", "operation.get");
                query.put("budgetMs", 10000);
                try {
                    Answer answer =
                            agentManager.send(
                                    r.host,
                                    new VmProcessActionCommand(
                                            vm.getUuid(), JSON.toJson(query), true));
                    authorized(vmId, false);
                    if (placement(vmDao.findById(vmId), r)
                            && answer instanceof VmProcessActionAnswer
                            && answer.getResult()) {
                        result =
                                VmProcessAction.decode(
                                        ((VmProcessActionAnswer) answer).getResultJson(), original);
                        store.finish(r, (String) result.get("state"), JSON.toJson(result));
                        if (!"UNKNOWN".equals(result.get("state")))
                            audit(vmId, result, "RECONCILED");
                    }
                } catch (Exception e) {
                    /* query failure is not proof that dispatch did not occur */
                }
            }
            authorized(vmId, false);
            return response(result);
        } catch (java.sql.SQLException | java.io.IOException e) {
            throw new InvalidParameterValueException("Process operation unavailable");
        }
    }

    void audit(long vmId, Map<String, Object> result, String phase) {
        CallContext c = CallContext.current();
        UserVmVO vm = vmDao.findById(vmId);
        String action = (String) result.get("action");
        String type =
                "VM.PROCESS." + action.substring(action.indexOf('.') + 1).toUpperCase(Locale.ROOT);
        String description =
                "Process operation "
                        + phase
                        + " requestId="
                        + result.get("requestId")
                        + " operationId="
                        + result.get("operationId")
                        + " state="
                        + result.get("state")
                        + " effect="
                        + result.get("effect")
                        + " postcondition="
                        + result.get("postcondition");
        com.cloud.event.ActionEventUtils.onCompletedActionEvent(
                c.getCallingUserId(),
                vm.getAccountId(),
                "SUCCEEDED".equals(result.get("state")) ? "INFO" : "ERROR",
                type,
                description,
                vmId,
                org.apache.cloudstack.api.ApiCommandResourceType.VirtualMachine.toString(),
                c.getStartEventId());
    }

    private VmProcessActionResponse audited(long vmId, Map<String, Object> result, String phase) {
        audit(vmId, result, phase);
        return response(result);
    }

    static VmProcessActionResponse response(Map<String, Object> result) {
        Map<String, Object> projection = new LinkedHashMap<>(result);
        projection.put(
                "authority",
                Map.of("vmUuid", VmProcessSnapshot.map(result.get("authority")).get("vmUuid")));
        VmProcessActionResponse r = new VmProcessActionResponse();
        r.setProcessState(projection);
        return r;
    }
}
