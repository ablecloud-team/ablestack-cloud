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
package com.cloud.vm;

import com.cloud.exception.*;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.storage.dao.VolumeDetailsDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.uservm.UserVm;
import com.cloud.utils.component.PluggableService;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;
import com.cloud.vm.dao.VmCloneBatchDao;
import com.google.gson.Gson;

import org.apache.cloudstack.api.*;
import org.apache.cloudstack.api.ApiServerService.AsyncCmdResult;
import org.apache.cloudstack.api.command.user.vm.CloneVMCmd;
import org.apache.cloudstack.api.command.user.vm.QueryVMCloneBatchCmd;
import org.apache.cloudstack.api.response.VmCloneBatchResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.framework.jobs.AsyncJobExecutionContext;
import org.apache.cloudstack.framework.jobs.dao.AsyncJobDao;
import org.apache.cloudstack.framework.jobs.impl.AsyncJobVO;
import org.apache.commons.codec.digest.DigestUtils;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.inject.Inject;

/** Durable batch receipts; no target is rediscovered by a user-controlled name. */
public class VmCloneBatchManager implements PluggableService {
    @Inject VmCloneBatchDao batches;
    @Inject AsyncJobDao jobs;
    @Inject UserVmDao vms;
    @Inject VolumeDao volumes;
    @Inject VolumeDetailsDao volumeDetails;
    @Inject VMInstanceDetailsDao vmDetails;
    @Inject AccountManager accounts;
    private final Gson gson = new Gson();

    @Override
    public List<Class<?>> getCommands() {
        return Collections.singletonList(QueryVMCloneBatchCmd.class);
    }

    @FunctionalInterface
    public interface Submission {
        AsyncCmdResult run() throws Exception;
    }

    @FunctionalInterface
    public interface Work {
        Optional<UserVm> run()
                throws ResourceAllocationException,
                        ResourceUnavailableException,
                        InsufficientCapacityException;
    }

    static String uuid(String value) {
        try {
            if (!UUID.fromString(value).toString().equals(value))
                throw new IllegalArgumentException();
            return value;
        } catch (RuntimeException e) {
            throw new InvalidParameterValueException(
                    "Clone batch identifiers must be canonical UUIDs");
        }
    }

    protected <T> T locked(String name, Supplier<T> work) {
        GlobalLock lock = GlobalLock.getInternLock("vm-clone-batch-" + name);
        boolean held = false;
        try {
            held = lock.lock(30);
            if (!held)
                throw new CloudRuntimeException("Clone batch is busy; query its existing result");
            return work.get();
        } finally {
            if (held) lock.unlock();
            lock.releaseRef();
        }
    }

    public AsyncCmdResult submit(BaseAsyncCmd command, Map<String, String> params, Submission work)
            throws Exception {
        if (!(command instanceof CloneVMCmd)) return work.run();
        String attempt =
                uuid(
                        params.computeIfAbsent(
                                ApiConstants.CUSTOM_JOB_ID, k -> UUID.randomUUID().toString()));
        String batch = uuid(params.getOrDefault("batchid", attempt));
        GlobalLock lock = GlobalLock.getInternLock("vm-clone-batch-submit-" + batch);
        boolean held = false;
        try {
            held = lock.lock(30);
            if (!held)
                throw new CloudRuntimeException(
                        "Clone batch submission is busy; query the same requestid");
            return work.run();
        } finally {
            if (held) lock.unlock();
            lock.releaseRef();
        }
    }

    public AsyncJobVO submitted(CloneVMCmd cmd, long userId, long accountId) {
        AsyncJobVO job = jobs.findByUuidIncludingRemoved(cmd.getInjectedJobId());
        if (job != null) {
            VmCloneBatchVO row = batches.findByUuid(cmd.getBatchId());
            if (job.getUserId() != userId
                    || job.getAccountId() != accountId
                    || !CloneVMCmd.class.getName().equals(job.getCmd())
                    || row == null
                    || row.getSourceVmId() != cmd.getId()
                    || !decode(row).attempts.containsKey(cmd.getInjectedJobId()))
                throw new InvalidParameterValueException(
                        "Clone request is not available for replay");
        }
        return job;
    }

    static int count(CloneVMCmd cmd) {
        return cmd.getCount() == null ? 1 : cmd.getCount();
    }

    static String type(CloneVMCmd cmd) {
        return cmd.getType() == null ? "full" : cmd.getType();
    }

    static String fingerprint(CloneVMCmd cmd) {
        return DigestUtils.sha256Hex(
                cmd.getId()
                        + "/"
                        + cmd.getName()
                        + "/"
                        + count(cmd)
                        + "/"
                        + cmd.getStartVm()
                        + "/"
                        + type(cmd));
    }

    VmCloneBatchData decode(VmCloneBatchVO row) {
        return gson.fromJson(row.getData(), VmCloneBatchData.class);
    }

    void save(VmCloneBatchVO row, VmCloneBatchData data) {
        String text = gson.toJson(data);
        if (text.length() > 8000000)
            throw new CloudRuntimeException("Clone batch receipt is too large");
        row.setData(text, data.status);
        if (!batches.update(row.getId(), row))
            throw new CloudRuntimeException("Cannot persist clone batch checkpoint");
    }

    private boolean permitted(VmCloneBatchVO row, UserVm source) {
        Account caller = CallContext.current().getCallingAccount();
        if (source == null || source.getAccountId() != row.getAccountId()) return false;
        try {
            accounts.checkAccess(caller, null, true, source);
            return true;
        } catch (PermissionDeniedException e) {
            return false;
        }
    }

    VmCloneBatchVO require(CloneVMCmd cmd, UserVm source) {
        VmCloneBatchVO row = batches.findByUuid(uuid(cmd.getBatchId()));
        if (row == null || row.getSourceVmId() != source.getId() || !permitted(row, source))
            throw new InvalidParameterValueException("Clone batch is not available");
        if (!row.getFingerprint().equals(fingerprint(cmd)))
            throw new InvalidParameterValueException(
                    "A clone batch identifier cannot be reused with different inputs");
        return row;
    }

    /** True means replay or a retry that only resumes already prepared resources. */
    public boolean preflight(CloneVMCmd cmd, UserVm source) {
        if (cmd.getBatchId() == null) return false;
        uuid(cmd.getBatchId());
        uuid(cmd.getInjectedJobId());
        if (count(cmd) < 1 || count(cmd) > 50 || !List.of("full", "linked").contains(type(cmd)))
            throw new InvalidParameterValueException(
                    "Clone count must be 1..50 and type full or linked");
        accounts.checkAccess(CallContext.current().getCallingAccount(), null, true, source);
        if (cmd.getDomainId() != null && !Objects.equals(cmd.getDomainId(), source.getDomainId()))
            throw new InvalidParameterValueException(
                    "Changing clone ownership or domain is not supported");
        if (cmd.getAccountName() != null) {
            Account owner =
                    cmd.getDomainId() == null
                            ? null
                            : accounts.getActiveAccountByName(
                                    cmd.getAccountName(), cmd.getDomainId());
            if (owner == null || owner.getId() != source.getAccountId())
                throw new InvalidParameterValueException(
                        "Only the source clone account is supported");
        }
        if (cmd.getZoneIds() != null
                && (cmd.getZoneIds().size() != 1
                        || !Objects.equals(cmd.getZoneIds().get(0), source.getDataCenterId())))
            throw new InvalidParameterValueException("Only the source clone zone is supported");
        VmCloneBatchVO row = batches.findByUuid(cmd.getBatchId());
        if (row == null) {
            if (cmd.getRetryIndices() != null
                    || !cmd.getBatchId().equals(cmd.getInjectedJobId())
                    || jobs.findByUuidIncludingRemoved(cmd.getInjectedJobId()) != null)
                throw new InvalidParameterValueException("Clone batch is not available for retry");
            return false;
        }
        row = require(cmd, source);
        VmCloneBatchData data = decode(row);
        VmCloneBatchData.Attempt previous = data.attempts.get(cmd.getInjectedJobId());
        List<Integer> requested = indices(cmd, data.count);
        if (previous != null) {
            if (!previous.indices.equals(requested))
                throw new InvalidParameterValueException(
                        "Retry identifier cannot change its targets");
            return true;
        }
        if (jobs.findByUuidIncludingRemoved(cmd.getInjectedJobId()) != null)
            throw new InvalidParameterValueException(
                    "Retry identifier is already used by another job");
        if (cmd.getRetryIndices() == null)
            throw new InvalidParameterValueException(
                    "Use retryindices and a new customjobid for failed targets");
        if (data.attempts.size() >= 50)
            throw new InvalidParameterValueException("Clone batch retry limit reached");
        if (busy(data))
            throw new InvalidParameterValueException(
                    "Previous clone attempt is still active or unconfirmed; query the same batch");
        List<Integer> create = new ArrayList<>();
        for (int index : requested) {
            VmCloneBatchData.Item item = data.items.get(index - 1);
            String action = action(row, data, item);
            if ("NONE".equals(action))
                throw new InvalidParameterValueException(
                        "Target "
                                + index
                                + " cannot be retried safely; inspect retained resources");
            if ("CREATE".equals(action)) create.add(index);
        }
        cmd.setCloneIndices(create);
        if (create.isEmpty()) {
            prepare(cmd, source);
            return true;
        }
        return false;
    }

    static List<Integer> indices(CloneVMCmd cmd, int count) {
        List<Integer> result =
                cmd.getRetryIndices() == null
                        ? new ArrayList<>()
                        : new ArrayList<>(cmd.getRetryIndices());
        if (cmd.getRetryIndices() == null) for (int i = 1; i <= count; i++) result.add(i);
        if (result.isEmpty()
                || new HashSet<>(result).size() != result.size()
                || result.stream().anyMatch(i -> i == null || i < 1 || i > count))
            throw new InvalidParameterValueException("Invalid clone retry indices");
        Collections.sort(result);
        return result;
    }

    public void prepare(CloneVMCmd cmd, UserVm source) {
        if (cmd.getBatchId() == null) return;
        locked(
                "receipt-" + cmd.getBatchId(),
                () -> {
                    VmCloneBatchVO row = batches.findByUuid(cmd.getBatchId());
                    VmCloneBatchData data;
                    if (row == null) {
                        data = new VmCloneBatchData();
                        data.name = cmd.getName();
                        data.count = count(cmd);
                        data.type = type(cmd);
                        data.start = cmd.getStartVm();
                        data.sourceUuid = source.getUuid();
                        data.sourceOfferingId = source.getServiceOfferingId();
                        data.sourceTemplateId = source.getTemplateId();
                        for (VolumeVO volume : volumes.findByInstance(source.getId())) {
                            VmCloneBatchData.SourceDisk disk = new VmCloneBatchData.SourceDisk();
                            disk.id = volume.getId();
                            disk.type = volume.getVolumeType().name();
                            disk.device = volume.getDeviceId();
                            disk.uuid = volume.getUuid();
                            disk.pool = volume.getPoolId();
                            disk.size = volume.getSize();
                            disk.format = String.valueOf(volume.getFormat());
                            disk.policy = policy(volume);
                            data.sourceDisks.add(disk);
                        }
                        for (int i = 1; i <= data.count; i++) {
                            VmCloneBatchData.Item item = new VmCloneBatchData.Item();
                            item.index = i;
                            item.name = data.name + (data.count > 1 ? "-" + i : "");
                            data.items.add(item);
                        }
                        row =
                                batches.persist(
                                        new VmCloneBatchVO(
                                                cmd.getBatchId(),
                                                source.getId(),
                                                source.getAccountId(),
                                                CallContext.current().getCallingUserId(),
                                                fingerprint(cmd),
                                                gson.toJson(data)));
                        if (row == null)
                            throw new CloudRuntimeException("Cannot persist clone batch plan");
                    } else {
                        row = require(cmd, source);
                        data = decode(row);
                    }
                    if (!data.attempts.containsKey(cmd.getInjectedJobId())) {
                        VmCloneBatchData.Attempt a = new VmCloneBatchData.Attempt();
                        a.indices = indices(cmd, data.count);
                        data.attempts.put(cmd.getInjectedJobId(), a);
                        data.currentAttempt = cmd.getInjectedJobId();
                        data.status = "QUEUED";
                        save(row, data);
                    }
                    return null;
                });
    }

    protected Long currentJob() {
        String id = AsyncJobExecutionContext.getOriginJobId();
        return id == null ? null : Long.valueOf(id);
    }

    private void change(CloneVMCmd cmd, Consumer<VmCloneBatchData> update) {
        if (cmd.getBatchId() == null) return;
        locked(
                "receipt-" + cmd.getBatchId(),
                () -> {
                    VmCloneBatchVO row = batches.findByUuid(cmd.getBatchId());
                    if (row == null)
                        throw new CloudRuntimeException("Clone batch receipt disappeared");
                    VmCloneBatchData data = decode(row);
                    update.accept(data);
                    save(row, data);
                    return null;
                });
    }

    public Optional<UserVm> execute(CloneVMCmd cmd, Work work)
            throws ResourceAllocationException,
                    ResourceUnavailableException,
                    InsufficientCapacityException {
        if (cmd.getBatchId() == null) return work.run();
        VmCloneBatchData initial = data(cmd);
        String original = initial.name;
        GlobalLock workLock = GlobalLock.getInternLock("vm-clone-batch-work-" + cmd.getBatchId());
        boolean held = workLock.lock(1);
        if (!held) {
            workLock.releaseRef();
            throw new CloudRuntimeException(
                    "Clone attempt is already executing; query the existing batch");
        }
        GlobalLock sourceLock = GlobalLock.getInternLock("vm-clone-batch-source-" + cmd.getId());
        if (!sourceLock.lock(1)) {
            sourceLock.releaseRef();
            workLock.unlock();
            workLock.releaseRef();
            throw new CloudRuntimeException(
                    "Another clone batch is active for this source; query before retrying");
        }
        try {
            change(
                    cmd,
                    d -> {
                        VmCloneBatchData.Attempt a = d.attempts.get(cmd.getInjectedJobId());
                        if (a == null || a.started)
                            throw new CloudRuntimeException(
                                    "Clone attempt already started; query its durable result before"
                                        + " retrying");
                        a.started = true;
                        a.jobId = currentJob();
                        d.currentAttempt = cmd.getInjectedJobId();
                        d.status = "RUNNING";
                        d.error = null;
                    });
        } catch (RuntimeException e) {
            sourceLock.unlock();
            sourceLock.releaseRef();
            workLock.unlock();
            workLock.releaseRef();
            throw e;
        }
        try {
            Optional<UserVm> result = work.run();
            change(
                    cmd,
                    d -> {
                        d.attempts.get(cmd.getInjectedJobId()).finished = true;
                        d.status =
                                d.items.stream().allMatch(i -> "SUCCEEDED".equals(i.phase))
                                        ? "SUCCEEDED"
                                        : "PARTIAL";
                    });
            return result;
        } catch (ResourceAllocationException
                | ResourceUnavailableException
                | InsufficientCapacityException
                | RuntimeException e) {
            change(
                    cmd,
                    d -> {
                        VmCloneBatchData.Attempt a = d.attempts.get(cmd.getInjectedJobId());
                        if (a != null) a.finished = true;
                        d.error = error(e);
                        d.status = d.items.stream().anyMatch(i -> i.created) ? "PARTIAL" : "FAILED";
                    });
            throw e;
        } finally {
            cmd.setName(original);
            sourceLock.unlock();
            sourceLock.releaseRef();
            workLock.unlock();
            workLock.releaseRef();
        }
    }

    VmCloneBatchData data(CloneVMCmd cmd) {
        VmCloneBatchVO row = batches.findByUuid(cmd.getBatchId());
        if (row == null) throw new CloudRuntimeException("Clone batch receipt missing");
        return decode(row);
    }

    public List<Integer> selected(CloneVMCmd cmd) {
        return data(cmd).attempts.get(cmd.getInjectedJobId()).indices;
    }

    public String retryAction(CloneVMCmd cmd, int index) {
        VmCloneBatchVO row = batches.findByUuid(cmd.getBatchId());
        VmCloneBatchData d = decode(row);
        return action(row, d, d.items.get(index - 1));
    }

    public void phase(CloneVMCmd cmd, String phase) {
        change(
                cmd,
                d -> {
                    VmCloneBatchData.Item i = d.items.get(cmd.getBatchItemIndex() - 1);
                    i.phase = phase;
                    i.error = null;
                });
    }

    public void volume(CloneVMCmd cmd, VolumeVO source, String targetUuid) {
        change(
                cmd,
                d -> {
                    VmCloneBatchData.Item i = d.items.get(cmd.getBatchItemIndex() - 1);
                    VmCloneBatchData.Disk disk = new VmCloneBatchData.Disk();
                    disk.uuid = targetUuid;
                    disk.sourceId = source.getId();
                    disk.type = source.getVolumeType().name();
                    disk.attempt = cmd.getInjectedJobId();
                    i.disks.add(disk);
                    i.phase = "COPYING";
                });
    }

    public void vm(CloneVMCmd cmd, UserVm vm) {
        change(
                cmd,
                d -> {
                    VmCloneBatchData.Item i = d.items.get(cmd.getBatchItemIndex() - 1);
                    i.vmId = vm.getId();
                    i.vmRecorded = true;
                });
    }

    public void created(CloneVMCmd cmd, UserVm vm) {
        change(
                cmd,
                d -> {
                    VmCloneBatchData.Item i = d.items.get(cmd.getBatchItemIndex() - 1);
                    i.vmId = vm.getId();
                    i.vmRecorded = true;
                    i.created = true;
                    i.phase = d.start ? "CREATED" : "SUCCEEDED";
                });
    }

    public void success(CloneVMCmd cmd, UserVm vm) {
        change(
                cmd,
                d -> {
                    VmCloneBatchData.Item i = d.items.get(cmd.getBatchItemIndex() - 1);
                    i.vmId = vm.getId();
                    i.created = true;
                    i.phase = "SUCCEEDED";
                    i.error = null;
                });
    }

    public void failure(CloneVMCmd cmd, Throwable failure) {
        change(
                cmd,
                d -> {
                    VmCloneBatchData.Item i = d.items.get(cmd.getBatchItemIndex() - 1);
                    i.error = error(failure);
                    i.phase = "STARTING".equals(i.phase) ? "START_FAILED" : i.created ? "FINALIZE_FAILED" : "CREATE_FAILED";
                });
    }

    public void preparation(CloneVMCmd cmd, String operation, boolean prepared) {
        change(
                cmd,
                d -> {
                    d.operationId = operation;
                    d.prepared = prepared;
                });
    }

    static String error(Throwable e) {
        String s =
                String.valueOf(e.getMessage())
                        .replaceAll("[\\r\\n\\t]+", " ")
                        .replaceAll(
                                "(?i)(password|secretkey|sessionkey|apikey|key)[=:][^ ,;]+",
                                "$1=[REDACTED]");
        return s.substring(0, Math.min(500, s.length()));
    }

    boolean busy(VmCloneBatchData data) {
        VmCloneBatchData.Attempt a = data.attempts.get(data.currentAttempt);
        if (a == null) return false;
        if (a.jobId == null) {
            AsyncJobVO job = jobs.findByUuidIncludingRemoved(data.currentAttempt);
            return job == null ? !a.finished : job.getStatus().ordinal() == 0;
        }
        AsyncJobVO job = jobs.findByIdIncludingRemoved(a.jobId);
        return job == null ? !a.finished : job.getStatus().ordinal() == 0;
    }

    List<VolumeVO> disks(VmCloneBatchData.Item item) {
        List<VolumeVO> result = new ArrayList<>();
        for (VmCloneBatchData.Disk disk : item.disks) {
            VolumeVO v = volumes.findByUuidIncludingRemoved(disk.uuid);
            if (v != null) result.add(v);
        }
        return result;
    }

    UserVmVO vm(VmCloneBatchVO row, VmCloneBatchData.Item item) {
        if (item.vmId != null) {
            UserVmVO vm = vms.findByIdIncludingRemoved(item.vmId);
            if (vm != null) return vm;
        }
        for (VolumeVO volume : disks(item))
            if (volume.getVolumeType() == Volume.Type.ROOT
                    && volume.getInstanceId() != null
                    && volume.getInstanceId() != row.getSourceVmId())
                return vms.findByIdIncludingRemoved(volume.getInstanceId());
        return null;
    }

    boolean removed(VolumeVO v) {
        return v.getRemoved() != null && v.getState() == Volume.State.Expunged;
    }

    boolean fullDisks(VmCloneBatchData data, VmCloneBatchData.Item item, UserVmVO vm) {
        List<VolumeVO> disks = disks(item);
        return data.sourceDisks.stream()
                .allMatch(
                        source ->
                                item.disks.stream()
                                        .anyMatch(
                                                plan ->
                                                        plan.sourceId == source.id
                                                                && disks.stream()
                                                                        .anyMatch(
                                                                                v ->
                                                                                        v.getUuid()
                                                                                                        .equals(
                                                                                                                plan.uuid)
                                                                                                && v
                                                                                                                .getRemoved()
                                                                                                        == null
                                                                                                && v
                                                                                                                .getState()
                                                                                                        == Volume
                                                                                                                .State
                                                                                                                .Ready
                                                                                                && v
                                                                                                                .getAccountId()
                                                                                                        == vm
                                                                                                                .getAccountId()
                                                                                                && Objects
                                                                                                        .equals(
                                                                                                                v
                                                                                                                        .getInstanceId(),
                                                                                                                vm
                                                                                                                        .getId()))));
    }

    boolean prepared(
            VmCloneBatchVO row, VmCloneBatchData data, VmCloneBatchData.Item item, UserVmVO vm) {
        if (!data.prepared
                || data.operationId == null
                || (vm != null
                        && (!item.vmRecorded || vm.getState() != VirtualMachine.State.Stopped)))
            return false;
        List<VolumeVO> disks = disks(item);
        for (VmCloneBatchData.SourceDisk source : data.sourceDisks) {
            boolean match = false;
            for (VmCloneBatchData.Disk plan : item.disks)
                if (plan.sourceId == source.id) {
                    for (VolumeVO v : disks)
                        if (v.getUuid().equals(plan.uuid)
                                && v.getRemoved() == null
                                && v.getState() == Volume.State.Ready
                                && (v.getInstanceId() == null
                                        || (vm != null && v.getInstanceId() == vm.getId()))) {
                            Map<String, String> details =
                                    volumeDetails.listDetailsKeyPairs(v.getId());
                            match =
                                    data.operationId.equals(details.get("clone.fast.operation.id"))
                                            && "clone".equals(details.get("clone.fast.role"));
                        }
                }
            if (!match) return false;
        }
        var operation = vmDetails.findDetail(row.getSourceVmId(), "clone.fast.operation.id");
        var phase = vmDetails.findDetail(row.getSourceVmId(), "clone.fast.source.phase");
        return operation != null
                && data.operationId.equals(operation.getValue())
                && phase != null
                && List.of("ready", "prepared").contains(phase.getValue());
    }

    String policy(VolumeVO volume) {
        return gson.toJson(
                new TreeMap<>(
                        com.cloud.storage.VolumeAllocationLifecycle.intent(
                                Optional.ofNullable(
                                                volumeDetails.listDetailsKeyPairs(volume.getId()))
                                        .orElse(Collections.emptyMap()))));
    }

    boolean sameSource(VmCloneBatchVO row, VmCloneBatchData data) {
        UserVmVO source = vms.findById(row.getSourceVmId());
        if (source == null
                || source.getServiceOfferingId() != data.sourceOfferingId
                || !Objects.equals(source.getTemplateId(), data.sourceTemplateId)) return false;
        List<VolumeVO> now = volumes.findByInstance(row.getSourceVmId());
        if (now.size() != data.sourceDisks.size()) return false;
        return data.sourceDisks.stream()
                .allMatch(
                        d ->
                                now.stream()
                                        .anyMatch(
                                                v ->
                                                        v.getId() == d.id
                                                                && Objects.equals(
                                                                        v.getUuid(), d.uuid)
                                                                && Objects.equals(
                                                                        v.getPoolId(), d.pool)
                                                                && Objects.equals(
                                                                        v.getSize(), d.size)
                                                                && Objects.equals(
                                                                        v.getDeviceId(), d.device)
                                                                && String.valueOf(v.getFormat())
                                                                        .equals(d.format)
                                                                && policy(v).equals(d.policy)));
    }

    String action(VmCloneBatchVO row, VmCloneBatchData data, VmCloneBatchData.Item item) {
        if ("SUCCEEDED".equals(item.phase)) return "NONE";
        UserVmVO vm = vm(row, item);
        List<VolumeVO> disks = disks(item);
        if (disks.stream().anyMatch(v -> !removed(v) && v.getAccountId() != row.getAccountId()))
            return "NONE";
        if (vm != null && vm.getRemoved() == null) {
            if (vm.getAccountId() != row.getAccountId() || !item.name.equals(vm.getName()))
                return "NONE";
            if (fullDisks(data, item, vm))
                return data.start && vm.getState() == VirtualMachine.State.Stopped
                        ? "START"
                        : "NONE";
            return prepared(row, data, item, vm) ? "RESUME" : "NONE";
        }
        if (item.created) return "NONE";
        if (prepared(row, data, item, null)) return "RESUME";
        return disks.stream().allMatch(this::removed) && sameSource(row, data) ? "CREATE" : "NONE";
    }

    public List<VolumeVO> preparedVolumes(CloneVMCmd cmd, int index) {
        VmCloneBatchData d = data(cmd);
        VmCloneBatchData.Item i = d.items.get(index - 1);
        List<VolumeVO> out = new ArrayList<>();
        for (VmCloneBatchData.SourceDisk source : d.sourceDisks) {
            for (VmCloneBatchData.Disk plan : i.disks)
                if (plan.sourceId == source.id) {
                    VolumeVO v = volumes.findByUuid(plan.uuid);
                    if (v != null
                            && v.getRemoved() == null
                            && v.getState() == Volume.State.Ready
                            && !out.contains(v)) out.add(v);
                }
        }
        out.sort(Comparator.comparing(v -> v.getVolumeType() == Volume.Type.ROOT ? 0 : 1));
        return out;
    }

    public UserVmVO target(CloneVMCmd cmd, int index) {
        VmCloneBatchVO row = batches.findByUuid(cmd.getBatchId());
        return vm(row, decode(row).items.get(index - 1));
    }

    public VmCloneBatchResponse query(String requestId) {
        String id = uuid(requestId);
        VmCloneBatchResponse response = new VmCloneBatchResponse();
        response.requestId = id;
        response.status = "NOTFOUND";
        VmCloneBatchVO row = batches.findByUuid(id);
        if (row == null) return response;
        UserVmVO source = vms.findByIdIncludingRemoved(row.getSourceVmId());
        if (!permitted(row, source)) return response;
        VmCloneBatchData data =
                locked(
                        "receipt-" + id,
                        () -> {
                            VmCloneBatchVO latest = batches.findByUuid(id);
                            VmCloneBatchData current = decode(latest);
                            boolean changed = false;
                            if (!busy(current))
                                for (VmCloneBatchData.Item item : current.items) {
                                    UserVmVO target = vm(latest, item);
                                    if (!item.created
                                            && target != null
                                            && target.getRemoved() == null
                                            && target.getAccountId() == latest.getAccountId()
                                            && item.name.equals(target.getName())
                                            && fullDisks(current, item, target)) {
                                        item.created = true;
                                        item.vmId = target.getId();
                                        changed = true;
                                    }
                                }
                            if (changed) save(latest, current);
                            return current;
                        });
        boolean busy = busy(data);
        response.attemptId = data.currentAttempt;
        response.sourceId = data.sourceUuid;
        response.requestedCount = data.count;
        response.startVm = data.start;
        response.cloneType = data.type;
        response.status = data.status;
        if (!busy && List.of("RUNNING", "QUEUED").contains(data.status))
            response.status = "INTERRUPTED";
        AsyncJobVO job = jobs.findByUuidIncludingRemoved(data.currentAttempt);
        if (job != null) {
            response.setJobId(job.getUuid());
            response.setJobStatus(job.getStatus().ordinal());
        }
        boolean restricted = false;
        for (VmCloneBatchData.Item item : data.items) {
            VmCloneBatchResponse.Target target = new VmCloneBatchResponse.Target();
            target.index = item.index;
            target.name = item.name;
            target.phase = item.phase;
            target.retryAction = "NONE";
            UserVmVO vm = vm(row, item);
            List<VolumeVO> allocated = disks(item);
            boolean allowed = true;
            try {
                if (vm != null)
                    accounts.checkAccess(CallContext.current().getCallingAccount(), null, true, vm);
                for (VolumeVO disk : allocated)
                    accounts.checkAccess(
                            CallContext.current().getCallingAccount(), null, true, disk);
            } catch (PermissionDeniedException e) {
                allowed = false;
            }
            if (!allowed) {
                restricted = true;
                target.phase = "ACCESS_RESTRICTED";
                target.cleanup = "unconfirmed";
                target.error = "Target access changed; inspect through an authorized account";
                response.targets.add(target);
                continue;
            }
            target.created = item.created || (vm != null && fullDisks(data, item, vm));
            target.error = item.error;
            target.state =
                    vm == null
                            ? "NOT_CREATED"
                            : vm.getRemoved() != null ? "REMOVED" : vm.getState().name();
            if (vm != null) {
                target.id = vm.getUuid();
            }
            if (!busy) target.retryAction = action(row, data, item);
            target.cleanup =
                    allocated.isEmpty()
                            ? "not-created"
                            : allocated.stream().allMatch(this::removed)
                                    ? "removed-in-cloud"
                                    : target.created
                                            ? "preserved"
                                            : prepared(row, data, item, vm)
                                                    ? "prepared"
                                                    : allocated.stream()
                                                                    .anyMatch(
                                                                            v ->
                                                                                    List.of(
                                                                                                    Volume
                                                                                                            .State
                                                                                                            .Destroy,
                                                                                                    Volume
                                                                                                            .State
                                                                                                            .Expunging)
                                                                                            .contains(
                                                                                                    v
                                                                                                            .getState()))
                                                            ? "pending"
                                                            : "unconfirmed";
            for (VmCloneBatchData.Disk plan : item.disks) {
                VmCloneBatchResponse.Disk disk = new VmCloneBatchResponse.Disk();
                VolumeVO current = volumes.findByUuidIncludingRemoved(plan.uuid);
                disk.id = plan.uuid;
                disk.type = plan.type;
                disk.state = current == null ? "NOT_CREATED" : current.getState().name();
                disk.cleanup =
                        current == null
                                ? "not-created"
                                : removed(current)
                                        ? "removed-in-cloud"
                                        : List.of(Volume.State.Destroy, Volume.State.Expunging)
                                                        .contains(current.getState())
                                                ? "pending"
                                                : "retained";
                target.volumes.add(disk);
            }
            response.targets.add(target);
        }
        response.error = restricted ? null : data.error;
        return response;
    }
}
