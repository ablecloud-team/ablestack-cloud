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
package com.cloud.storage;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.MigrationGuestConfigurationAnswer;
import com.cloud.agent.api.MigrationGuestConfigurationCommand;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.resource.ResourceState;
import com.cloud.storage.dao.StoragePoolHostDao;
import com.cloud.user.Account;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.NicVO;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.apache.cloudstack.api.command.admin.storage.GetMigrationGuestConfigurationCapabilitiesCmd;
import org.apache.cloudstack.api.command.admin.vm.ReconcileMigratedGuestConfigurationCmd;
import org.apache.cloudstack.api.response.MigrationGuestConfigurationResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

@Component
public class MigrationGuestConfigurationManagerImpl extends ManagerBase
        implements MigrationGuestConfigurationService {
    @Inject private AgentManager agentManager;
    @Inject private HostDao hostDao;
    @Inject private PrimaryDataStoreDao poolDao;
    @Inject private StoragePoolHostDao poolHostDao;
    @Inject private VMInstanceDao vmDao;
    @Inject private VMInstanceDetailsDao detailsDao;
    @Inject private NicDao nicDao;
    @Inject private com.cloud.network.dao.NetworkDao networkDao;
    @Inject private MigrationDiskControllerService controllerService;

    @Override
    public List<Class<?>> getCommands() {
        return List.of(
                GetMigrationGuestConfigurationCapabilitiesCmd.class,
                ReconcileMigratedGuestConfigurationCmd.class);
    }

    private void admin() {
        if (CallContext.current().getCallingAccount().getType() != Account.Type.ADMIN)
            throw new PermissionDeniedException("Root admin required");
    }

    private boolean live(HostVO host) {
        return host != null
                && host.getStatus() == Status.Up
                && host.getResourceState() == ResourceState.Enabled
                && host.getHypervisorType() == HypervisorType.KVM;
    }

    private Map<String, Object> send(long hostId, MigrationGuestConfigurationCommand command) {
        if (!live(hostDao.findById(hostId)))
            throw new CloudRuntimeException("GUEST_CONFIG_WORKER_UNAVAILABLE");
        Answer raw = agentManager.easySend(hostId, command);
        if (!(raw instanceof MigrationGuestConfigurationAnswer)
                || !raw.getResult()
                || ((MigrationGuestConfigurationAnswer) raw).getContractVersion() != command.getContractVersion())
            throw new CloudRuntimeException("GUEST_CONFIG_UNCONFIRMED");
        Map<String, Object> value = ((MigrationGuestConfigurationAnswer) raw).getObservation();
        if (value == null
                || !Boolean.TRUE.equals(value.get("supported"))
                || !Boolean.TRUE.equals(value.get("fresh_observation")))
            throw new CloudRuntimeException("GUEST_CONFIG_UNCONFIRMED");
        if (command.getContractVersion() == 2 && "CAPABILITIES".equals(command.getAction())
                && (!(value.get("features") instanceof java.util.Collection)
                    || !((java.util.Collection<?>) value.get("features")).contains(MigrationWindowsNetworkPolicy.FEATURE)))
            throw new CloudRuntimeException("GUEST_CONFIG_CAPABILITY_REQUIRED");
        if (command.getPlan() != null
                && !MigrationGuestConfigurationPolicy.parse(command.getPlan())
                        .get("hash")
                        .getAsString()
                        .equals(value.get("plan_hash")))
            throw new CloudRuntimeException("GUEST_CONFIG_PLAN_ACK_MISMATCH");
        return value;
    }

    @Override
    public MigrationGuestConfigurationResponse capabilities(
            GetMigrationGuestConfigurationCapabilitiesCmd cmd) {
        admin();
        var pool = poolDao.findById(cmd.getStorageId());
        if (pool == null) throw new InvalidParameterValueException("Primary storage not found");
        int version = Math.max(1, cmd.getContractVersion());
        var response = new MigrationGuestConfigurationResponse();
        response.setContractVersion(version);
        response.capability(pool.getUuid(), false, "NO_QUALIFIED_LIVE_WORKER");
        int candidates = 0, qualified = 0;
        for (var link : poolHostDao.listByPoolId(pool.getId())) {
            if (cmd.getHostId() != null && !cmd.getHostId().equals(link.getHostId())) continue;
            if (!live(hostDao.findById(link.getHostId()))) continue;
            candidates++;
            try {
                var command = new MigrationGuestConfigurationCommand("CAPABILITIES", null, null, null, null, null);
                command.setContractVersion(version);
                var value = send(link.getHostId(), command);
                if ("qga-file-v1".equals(value.get("transport"))) qualified++;
            } catch (CloudRuntimeException unavailable) {
            }
        }
        boolean ok = qualified > 0 && (version == 1 || qualified == candidates);
        response.capability(pool.getUuid(), ok, ok ? null : "GUEST_CONFIG_CAPABILITY_REQUIRED");
        response.setWindowsNetworkSupported(version == 2 && ok);
        return response;
    }

    private void save(long id, String key, String value) {
        com.cloud.utils.db.Transaction.execute(
                (com.cloud.utils.db.TransactionCallback<Void>)
                        s -> {
                            for (var entry :
                                    MigrationDiskControllerPolicy.chunks(key, value).entrySet())
                                detailsDao.addDetail(id, entry.getKey(), entry.getValue(), false);
                            return null;
                        });
    }

    private JsonObject plan(Map<String, String> details) {
        String text =
                MigrationDiskControllerPolicy.read(details, MigrationGuestConfigurationPolicy.PLAN);
        var plan = MigrationGuestConfigurationPolicy.parse(text);
        var controller =
                MigrationDiskControllerPolicy.parse(
                        MigrationDiskControllerPolicy.read(
                                details, MigrationDiskControllerPolicy.PLAN));
        if (!plan.get("controller_plan_hash").equals(controller.get("hash"))
                || !plan.get("source_vm_uuid").equals(controller.get("source_vm_uuid")))
            throw new CloudRuntimeException("GUEST_CONTROLLER_PLAN_MISMATCH");
        var expectedData = new java.util.HashSet<String>();
        for (var value : controller.getAsJsonArray("disks")) {
            var disk = value.getAsJsonObject();
            if ("root".equals(disk.get("role").getAsString())) {
                if (!disk.get("disk_id").equals(plan.get("root_source_disk_id")))
                    throw new CloudRuntimeException("GUEST_ROOT_BINDING_MISMATCH");
            } else expectedData.add(disk.get("disk_id").getAsString());
        }
        if ("windows".equals(plan.get("os").getAsString())) {
            var plannedData = new java.util.HashSet<String>();
            for (var value : plan.getAsJsonObject("windows").getAsJsonArray("disks"))
                plannedData.add(value.getAsJsonObject().get("source_disk_id").getAsString());
            if (!expectedData.equals(plannedData))
                throw new CloudRuntimeException("GUEST_DATA_BINDING_MISMATCH");
        }
        return plan;
    }

    private JsonArray nics(long vmId, JsonObject plan) {
        var values = new ArrayList<>(nicDao.listByVmId(vmId));
        values.sort(Comparator.comparingInt(NicVO::getDeviceId));
        JsonArray result = new JsonArray();
        var macs = new java.util.HashSet<String>();
        for (var nic : values) {
            JsonObject item = new JsonObject();
            item.addProperty("id", nic.getUuid());
            item.addProperty("network_id", nic.getNetworkId());
            item.addProperty("mac", nic.getMacAddress().toLowerCase(java.util.Locale.ROOT));
            macs.add(nic.getMacAddress().toLowerCase(java.util.Locale.ROOT));
            if (plan.get("version").getAsInt() == 2) {
                var network = networkDao.findById(nic.getNetworkId());
                if (network == null || network.getGuestType() != com.cloud.network.Network.GuestType.L2)
                    throw new CloudRuntimeException("WINDOWS_NETWORK_IPAM_UNSUPPORTED");
                item.addProperty("network_uuid", network.getUuid());
            }
            result.add(item);
        }
        if (plan.get("version").getAsInt() == 2) {
            var wanted = plan.getAsJsonObject("network").getAsJsonArray("interfaces");
            if (wanted.size() != result.size()) throw new CloudRuntimeException("NIC_BINDING_MISMATCH");
            for (var value : wanted) {
                var want = value.getAsJsonObject(); int count = 0;
                for (var actual : result) {
                    var nic = actual.getAsJsonObject();
                    if (want.get("target_mac").equals(nic.get("mac")) && want.get("target_network_uuid").equals(nic.get("network_uuid"))) count++;
                }
                if (count != 1) throw new CloudRuntimeException("NIC_BINDING_MISMATCH");
            }
        }
        if ("linux".equals(plan.get("os").getAsString())) {
            var wanted = plan.getAsJsonObject("network").getAsJsonArray("interfaces");
            if (wanted.size() != result.size())
                throw new CloudRuntimeException("NIC_BINDING_MISMATCH");
            for (var item : wanted)
                if (!macs.contains(item.getAsJsonObject().get("mac").getAsString()))
                    throw new CloudRuntimeException("NIC_BINDING_MISMATCH");
        }
        return result;
    }

    @Override
    public void beforeStart(long vmId) {
        var details = detailsDao.listDetailsKeyPairs(vmId);
        if (details.keySet().stream()
                .noneMatch(k -> k.startsWith(MigrationGuestConfigurationPolicy.PREFIX))) return;
        var p = plan(details);
        var vm = vmDao.findById(vmId);
        if (vm == null) throw new CloudRuntimeException("GUEST_VM_MISSING");
        nics(vmId, p);
        String volumes =
                MigrationDiskControllerPolicy.read(details, MigrationDiskControllerPolicy.BINDINGS);
        if (volumes == null) throw new CloudRuntimeException("GUEST_VOLUME_BINDING_REQUIRED");
        for (var b : MigrationDiskControllerPolicy.bindingArray(volumes)) {
            var pool = poolDao.findByUuid(b.getAsJsonObject().get("pool").getAsString());
            if (pool == null) throw new CloudRuntimeException("GUEST_CONFIG_POOL_UNAVAILABLE");
            boolean capable = false;
            for (var link : poolHostDao.listByPoolId(pool.getId())) {
                if (!live(hostDao.findById(link.getHostId()))) continue;
                try {
                    var command = new MigrationGuestConfigurationCommand("CAPABILITIES", vm.getInstanceName(), vm.getUuid(), MigrationGuestConfigurationPolicy.canonical(p), null, null);
                    command.setContractVersion(p.get("version").getAsInt());
                    send(link.getHostId(), command);
                    capable = true;
                    if (p.get("version").getAsInt() == 1) break;
                } catch (CloudRuntimeException unavailable) {
                    if (p.get("version").getAsInt() == 2) throw new CloudRuntimeException("GUEST_CONFIG_CAPABILITY_REQUIRED");
                }
            }
            if (!capable) throw new CloudRuntimeException("GUEST_CONFIG_WORKER_UNAVAILABLE");
        }
    }

    @Override
    public MigrationGuestConfigurationResponse reconcile(
            ReconcileMigratedGuestConfigurationCmd cmd) {
        admin();
        VMInstanceVO vm = vmDao.findById(cmd.getId());
        if (vm == null) throw new InvalidParameterValueException("Migration VM not found");
        var details = detailsDao.listDetailsKeyPairs(vm.getId());
        var p = plan(details);
        if (!p.get("hash").getAsString().equals(cmd.getPlanHash())
                || !p.get("operation_id").getAsString().equals(cmd.getOperationId()))
            throw new InvalidParameterValueException("GUEST_CONFIG_PLAN_HASH_MISMATCH");
        if (Math.max(1, cmd.getContractVersion()) != p.get("version").getAsInt())
            throw new InvalidParameterValueException("GUEST_CONFIG_CONTRACT_UNSUPPORTED");
        controllerService.beforeStart(vm.getId());
        String
                ctl =
                        MigrationDiskControllerPolicy.read(
                                details, MigrationDiskControllerPolicy.PLAN),
                volumes =
                        MigrationDiskControllerPolicy.read(
                                details, MigrationDiskControllerPolicy.BINDINGS);
        if (volumes == null) throw new CloudRuntimeException("GUEST_VOLUME_BINDING_REQUIRED");
        JsonObject current = new JsonObject();
        current.add("volumes", JsonParser.parseString(volumes));
        current.add("nics", nics(vm.getId(), p));
        String binding = MigrationDiskControllerPolicy.canonical(current);
        String previous =
                MigrationDiskControllerPolicy.read(
                        details, MigrationGuestConfigurationPolicy.BINDING);
        if (previous != null && !previous.equals(binding))
            throw new CloudRuntimeException("GUEST_CONFIG_FROZEN_BINDING_CHANGED");
        if (previous == null) save(vm.getId(), MigrationGuestConfigurationPolicy.BINDING, binding);
        Map<String, Object> observation = new LinkedHashMap<>();
        observation.put("supported", true);
        observation.put("status", "BOUND");
        if (vm.getState() == VirtualMachine.State.Running) {
            if (vm.getHostId() == null)
                throw new CloudRuntimeException("GUEST_CONFIG_HOST_UNAVAILABLE");
            var command =
                    new MigrationGuestConfigurationCommand(
                            "RECONCILE",
                            vm.getInstanceName(),
                            vm.getUuid(),
                            MigrationGuestConfigurationPolicy.canonical(p),
                            ctl,
                            volumes);
            command.setContractVersion(p.get("version").getAsInt());
            String previousEvidence =
                    MigrationDiskControllerPolicy.read(
                            details, MigrationGuestConfigurationPolicy.EVIDENCE);
            if (previousEvidence != null) {
                var evidence = JsonParser.parseString(previousEvidence).getAsJsonObject();
                if (evidence.has("binding_sha256"))
                    command.setLastVerifiedBindingHash(
                            evidence.get("binding_sha256").getAsString());
            }
            command.setNicBindings(MigrationDiskControllerPolicy.canonical(current.get("nics")));
            try {
                observation = send(vm.getHostId(), command);
            } catch (CloudRuntimeException unavailable) {
                observation.put("status", "UNKNOWN");
                observation.put("reason", "GUEST_CHANNEL_UNAVAILABLE");
            }
            if ("VERIFIED".equals(observation.get("status")))
                save(
                        vm.getId(),
                        MigrationGuestConfigurationPolicy.EVIDENCE,
                        new Gson().toJson(observation));
        } else if (vm.getState() != VirtualMachine.State.Stopped)
            throw new CloudRuntimeException("GUEST_CONFIG_VM_STATE_UNAVAILABLE");
        var response = new MigrationGuestConfigurationResponse();
        response.setContractVersion(p.get("version").getAsInt());
        response.setWindowsNetworkSupported(p.get("version").getAsInt() == 2);
        response.evidence(vm.getUuid(), cmd.getPlanHash(), cmd.getOperationId(), observation);
        return response;
    }
}
