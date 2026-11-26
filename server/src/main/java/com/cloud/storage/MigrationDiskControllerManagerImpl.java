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
import com.cloud.agent.api.MigrationDiskControllerAnswer;
import com.cloud.agent.api.MigrationDiskControllerCommand;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.resource.ResourceState;
import com.cloud.storage.dao.StoragePoolHostDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.storage.dao.VolumeDetailsDao;
import com.cloud.user.Account;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.apache.cloudstack.api.command.admin.storage.GetMigrationDiskControllerCapabilitiesCmd;
import org.apache.cloudstack.api.command.admin.vm.VerifyMigratedVirtualMachineCmd;
import org.apache.cloudstack.api.response.MigrationDiskControllerResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;

@Component
public class MigrationDiskControllerManagerImpl extends ManagerBase
        implements MigrationDiskControllerService {
    @Inject private AgentManager agentManager;
    @Inject private HostDao hostDao;
    @Inject private PrimaryDataStoreDao poolDao;
    @Inject private StoragePoolHostDao poolHostDao;
    @Inject private VolumeDao volumeDao;
    @Inject private VolumeDetailsDao volumeDetailsDao;
    @Inject private VMInstanceDao vmDao;
    @Inject private VMInstanceDetailsDao detailsDao;

    @Override
    public List<Class<?>> getCommands() {
        return List.of(
                GetMigrationDiskControllerCapabilitiesCmd.class,
                VerifyMigratedVirtualMachineCmd.class);
    }

    private void admin() {
        if (CallContext.current().getCallingAccount().getType() != Account.Type.ADMIN)
            throw new PermissionDeniedException("Root admin required");
    }

    private boolean live(HostVO h) {
        return h != null
                && h.getStatus() == Status.Up
                && h.getResourceState() == ResourceState.Enabled
                && h.getHypervisorType() == HypervisorType.KVM;
    }

    private Map<String, Object> send(HostVO host, MigrationDiskControllerCommand command) {
        if (!live(host)) throw new CloudRuntimeException("DISK_CONTROLLER_WORKER_UNAVAILABLE");
        Answer raw = agentManager.easySend(host.getId(), command);
        if (!(raw instanceof MigrationDiskControllerAnswer)
                || !raw.getResult()
                || ((MigrationDiskControllerAnswer) raw).getContractVersion() != 1)
            throw new CloudRuntimeException(
                    "DISK_CONTROLLER_UNCONFIRMED: "
                            + (raw == null ? "no Agent answer" : raw.getDetails()));
        Map<String, Object> result = ((MigrationDiskControllerAnswer) raw).getObservation();
        if (result == null
                || !Boolean.TRUE.equals(result.get("supported"))
                || !String.valueOf(result.get("machine")).contains(command.getMachine())
                || !command.getFirmware().equals(result.get("firmware")))
            throw new CloudRuntimeException("DISK_CONTROLLER_CAPABILITY_SCOPE_MISMATCH");
        if (command.getPlan() != null
                && !MigrationDiskControllerPolicy.parse(command.getPlan())
                        .get("hash")
                        .getAsString()
                        .equals(result.get("planhash")))
            throw new CloudRuntimeException("DISK_CONTROLLER_PLAN_ACK_MISMATCH");
        return result;
    }

    @Override
    public MigrationDiskControllerResponse capabilities(
            GetMigrationDiskControllerCapabilitiesCmd cmd) {
        admin();
        StoragePoolVO pool = poolDao.findById(cmd.getStorageId());
        if (pool == null) throw new InvalidParameterValueException("Primary storage not found");
        if (!"q35".equals(cmd.getMachine()) || !List.of("bios", "efi").contains(cmd.getFirmware()))
            throw new InvalidParameterValueException("Unsupported machine or firmware");
        MigrationDiskControllerResponse response = new MigrationDiskControllerResponse();
        response.reason("NO_QUALIFIED_LIVE_WORKER");
        response.capabilityScope(pool.getUuid(), cmd.getMachine(), cmd.getFirmware());
        for (StoragePoolHostVO link : poolHostDao.listByPoolId(pool.getId())) {
            if (cmd.getHostId() != null && !cmd.getHostId().equals(link.getHostId())) continue;
            try {
                var result =
                        send(
                                hostDao.findById(link.getHostId()),
                                new MigrationDiskControllerCommand(
                                        "CAPABILITIES",
                                        cmd.getMachine(),
                                        cmd.getFirmware(),
                                        null,
                                        null,
                                        null));
                response.supported(true);
                response.buses((List<String>) result.get("buses"));
                response.reason(null);
                return response;
            } catch (CloudRuntimeException unavailable) {
                /* Capability is positively established on a current worker, never inferred. */
            }
        }
        return response;
    }

    private JsonArray bindings(VMInstanceVO vm, JsonObject plan) {
        List<VolumeVO> vols = new ArrayList<>(volumeDao.findByInstance(vm.getId()));
        vols.sort(Comparator.comparingLong(v -> v.getDeviceId() == null ? -1 : v.getDeviceId()));
        JsonArray disks = plan.getAsJsonArray("disks"), out = new JsonArray();
        if (vols.size() != disks.size())
            throw new CloudRuntimeException("DISK_CONTROLLER_VOLUME_COUNT_MISMATCH");
        for (int i = 0; i < vols.size(); i++) {
            VolumeVO v = vols.get(i);
            JsonObject expected = disks.get(i).getAsJsonObject();
            if (v.getState() != Volume.State.Ready
                    || v.getDeviceId() == null
                    || v.getDeviceId() != i
                    || v.getAccountId() != vm.getAccountId()
                    || v.getVolumeType() != (i == 0 ? Volume.Type.ROOT : Volume.Type.DATADISK)
                    || v.getSize() != expected.get("size_bytes").getAsLong()
                    || v.getPoolId() == null
                    || v.getFormat() == null)
                throw new CloudRuntimeException("DISK_CONTROLLER_VOLUME_IDENTITY_MISMATCH");
            StoragePoolVO pool = poolDao.findById(v.getPoolId());
            if (pool == null || v.getPath() == null)
                throw new CloudRuntimeException("DISK_CONTROLLER_POOL_UNAVAILABLE");
            String storage, path;
            if (pool.getPoolType() == Storage.StoragePoolType.RBD) {
                storage = "rbd";
                path = pool.getPath().replaceAll("^/+|/+$", "") + "/" + v.getPath();
            } else if (List.of(
                            Storage.StoragePoolType.SharedMountPoint,
                            Storage.StoragePoolType.NetworkFilesystem,
                            Storage.StoragePoolType.Filesystem)
                    .contains(pool.getPoolType())) {
                storage = "file";
                Path directory = Path.of(pool.getPath()).toAbsolutePath().normalize(),
                        target = directory.resolve(v.getPath()).normalize();
                if (!target.startsWith(directory))
                    throw new CloudRuntimeException("DISK_CONTROLLER_PATH_OUTSIDE_POOL");
                path = target.toString();
            } else throw new CloudRuntimeException("DISK_CONTROLLER_STORAGE_UNSUPPORTED");
            JsonObject item = new JsonObject();
            item.addProperty("id", v.getUuid());
            item.addProperty("index", i);
            item.addProperty("storage", storage);
            item.addProperty("pool", pool.getUuid());
            item.addProperty("path", path);
            item.addProperty("format", v.getFormat().toString().toLowerCase(Locale.ROOT));
            out.add(item);
        }
        return out;
    }

    private void save(long vmId, String key, String text) {
        Transaction.execute(
                (TransactionCallback<Void>)
                        status -> {
                            for (var e : MigrationDiskControllerPolicy.chunks(key, text).entrySet())
                                detailsDao.addDetail(vmId, e.getKey(), e.getValue(), false);
                            return null;
                        });
    }

    private Map<String, Object> qualifiedFor(VMInstanceVO vm, JsonObject plan, JsonArray bound) {
        Set<Long> poolIds = new HashSet<>();
        for (VolumeVO v : volumeDao.findByInstance(vm.getId())) poolIds.add(v.getPoolId());
        if (poolIds.isEmpty())
            throw new CloudRuntimeException("DISK_CONTROLLER_VOLUME_COUNT_MISMATCH");
        for (StoragePoolHostVO link : poolHostDao.listByPoolId(poolIds.iterator().next())) {
            boolean connected = true;
            for (Long pool : poolIds)
                if (poolHostDao.findByPoolHost(pool, link.getHostId()) == null) connected = false;
            if (!connected) continue;
            try {
                return send(
                        hostDao.findById(link.getHostId()),
                        new MigrationDiskControllerCommand(
                                "CAPABILITIES",
                                plan.get("machine").getAsString(),
                                plan.get("firmware").getAsString(),
                                vm.getInstanceName(),
                                MigrationDiskControllerPolicy.canonical(plan),
                                MigrationDiskControllerPolicy.canonical(bound)));
            } catch (CloudRuntimeException unavailable) {
                /* retry other currently connected storage workers */
            }
        }
        throw new CloudRuntimeException("DISK_CONTROLLER_NO_QUALIFIED_WORKER");
    }

    @Override
    public MigrationDiskControllerResponse verify(VerifyMigratedVirtualMachineCmd cmd) {
        admin();
        VMInstanceVO vm = vmDao.findById(cmd.getId());
        if (vm == null) throw new InvalidParameterValueException("Migration VM not found");
        Map<String, String> details = detailsDao.listDetailsKeyPairs(vm.getId());
        String storedPlan =
                MigrationDiskControllerPolicy.read(details, MigrationDiskControllerPolicy.PLAN);
        if (storedPlan == null && cmd.getControllerPlan() != null) {
            // Recovery is confined to stopped, never-started strict imports with durable bindings.
            if (vm.getState() != VirtualMachine.State.Stopped
                    || MigrationDiskControllerPolicy.read(
                                    details, MigrationDiskControllerPolicy.BINDINGS)
                            == null)
                throw new CloudRuntimeException("DISK_CONTROLLER_RECOVERY_UNAVAILABLE");
            for (VolumeVO volume : volumeDao.findByInstance(vm.getId())) {
                Map<String, String> receipt = volumeDetailsDao.listDetailsKeyPairs(volume.getId());
                if (!receipt.containsKey(VolumeAllocationImport.REQUEST_ID)
                        || receipt.containsKey(VolumeAllocationImport.STARTED_AT))
                    throw new CloudRuntimeException("DISK_CONTROLLER_RECOVERY_SCOPE_CLOSED");
            }
            JsonObject proposed = MigrationDiskControllerPolicy.parse(cmd.getControllerPlan());
            if (!proposed.get("hash").getAsString().equals(cmd.getPlanHash()))
                throw new InvalidParameterValueException("DISK_CONTROLLER_PLAN_HASH_MISMATCH");
            String frozen =
                    MigrationDiskControllerPolicy.read(
                            details, MigrationDiskControllerPolicy.BINDINGS);
            if (!frozen.equals(MigrationDiskControllerPolicy.canonical(bindings(vm, proposed))))
                throw new CloudRuntimeException("DISK_CONTROLLER_RECOVERY_BINDINGS_CHANGED");
            storedPlan = MigrationDiskControllerPolicy.canonical(proposed);
            save(vm.getId(), MigrationDiskControllerPolicy.PLAN, storedPlan);
            details = detailsDao.listDetailsKeyPairs(vm.getId());
        }
        JsonObject plan = MigrationDiskControllerPolicy.parse(storedPlan);
        if (!plan.get("hash").getAsString().equals(cmd.getPlanHash()))
            throw new InvalidParameterValueException("DISK_CONTROLLER_PLAN_HASH_MISMATCH");
        JsonArray bound = bindings(vm, plan);
        String canonical = MigrationDiskControllerPolicy.canonical(bound),
                saved =
                        MigrationDiskControllerPolicy.read(
                                details, MigrationDiskControllerPolicy.BINDINGS);
        if (saved != null && !saved.equals(canonical))
            throw new CloudRuntimeException("DISK_CONTROLLER_FROZEN_BINDINGS_CHANGED");
        String scope;
        Map<String, Object> observation;
        if (vm.getState() == VirtualMachine.State.Stopped) {
            observation = qualifiedFor(vm, plan, bound);
            if (saved == null) save(vm.getId(), MigrationDiskControllerPolicy.BINDINGS, canonical);
            scope = "pre-first-start";
        } else if (vm.getState() == VirtualMachine.State.Running) {
            if (saved == null || vm.getHostId() == null)
                throw new CloudRuntimeException("DISK_CONTROLLER_BINDINGS_REQUIRED");
            observation =
                    send(
                            hostDao.findById(vm.getHostId()),
                            new MigrationDiskControllerCommand(
                                    "VERIFY",
                                    plan.get("machine").getAsString(),
                                    plan.get("firmware").getAsString(),
                                    vm.getInstanceName(),
                                    MigrationDiskControllerPolicy.canonical(plan),
                                    canonical));
            if (!cmd.getPlanHash().equals(observation.get("planhash")))
                throw new CloudRuntimeException("DISK_CONTROLLER_OBSERVATION_MISMATCH");
            scope = "running-xml";
            save(
                    vm.getId(),
                    MigrationDiskControllerPolicy.EVIDENCE,
                    new Gson().toJson(observation));
        } else throw new CloudRuntimeException("DISK_CONTROLLER_VM_STATE_UNAVAILABLE");
        MigrationDiskControllerResponse response = new MigrationDiskControllerResponse();
        response.evidence(vm.getUuid(), cmd.getPlanHash(), scope, observation);
        return response;
    }

    @Override
    public void beforeStart(long vmId) {
        Map<String, String> details = detailsDao.listDetailsKeyPairs(vmId);
        String text =
                MigrationDiskControllerPolicy.read(details, MigrationDiskControllerPolicy.PLAN);
        if (text == null) {
            if (details.keySet().stream()
                    .anyMatch(key -> key.startsWith("migration.disk.controller.")))
                throw new CloudRuntimeException("DISK_CONTROLLER_POLICY_MISSING");
            return;
        }
        VMInstanceVO vm = vmDao.findById(vmId);
        JsonObject plan = MigrationDiskControllerPolicy.parse(text);
        JsonArray current = bindings(vm, plan);
        String saved =
                MigrationDiskControllerPolicy.read(details, MigrationDiskControllerPolicy.BINDINGS);
        if (saved == null || !saved.equals(MigrationDiskControllerPolicy.canonical(current)))
            throw new CloudRuntimeException("DISK_CONTROLLER_BINDINGS_REQUIRED_OR_CHANGED");
        qualifiedFor(vm, plan, current);
    }
}
