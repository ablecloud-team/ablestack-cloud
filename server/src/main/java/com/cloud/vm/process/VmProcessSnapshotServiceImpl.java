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

import java.time.Instant;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import javax.inject.Inject;
import org.apache.cloudstack.acl.SecurityChecker.AccessType;
import org.apache.cloudstack.api.response.VmProcessSnapshotResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.vm.process.VmProcessSnapshotService;
import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.GetVmProcessSnapshotCommand;
import com.cloud.agent.api.GetVmProcessSnapshotAnswer;
import com.cloud.agent.api.VmProcessSnapshot;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.user.AccountManager;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.UserVmDao;

/** Short lived, node-local observations. No process inventory is persisted. */
public class VmProcessSnapshotServiceImpl extends com.cloud.utils.component.ManagerBase
        implements VmProcessSnapshotService, com.cloud.utils.component.PluggableService {
    @Inject private UserVmDao vmDao;
    @Inject private AccountManager accountManager;
    @Inject private HostDao hostDao;
    @Inject private AgentManager agentManager;
    private final Semaphore admission = new Semaphore(8);
    // Keep bounded per-VM state and refuse extra work rather than evicting in-flight entries.
    private final Map<Long, Entry> entries = new LinkedHashMap<>();
    private static final class Entry {
        long started, completed, host, generation;
        boolean running;
        String guestObservedAt;
        Map<String, Object> snapshot;
    }
    @Override public List<Class<?>> getCommands() {
        return List.of(org.apache.cloudstack.api.command.user.vm.ListVirtualMachineProcessesCmd.class,
                org.apache.cloudstack.api.command.user.vm.RefreshVirtualMachineProcessesCmd.class);
    }
    boolean enabled() { return Boolean.TRUE.equals(VmProcessCapabilityServiceImpl.MANAGEMENT_ENABLED.value()); }
    long now() { return System.nanoTime(); }
    private void requireEnabled() {
        if (!enabled()) { synchronized (entries) { entries.values().forEach(e -> e.snapshot = null); }
            throw new InvalidParameterValueException("VM process management is disabled; enable vm.process.management.enabled explicitly"); }
    }
    private UserVmVO authorized(long id) {
        UserVmVO vm = vmDao.findById(id);
        if (vm == null || vm.getRemoved() != null || vm.getType() != VirtualMachine.Type.User) throw new InvalidParameterValueException("User VM not found");
        accountManager.checkAccess(CallContext.current().getCallingAccount(), AccessType.ListEntry, true, vm);
        requireEnabled(); return vm;
    }
    private GetVmProcessSnapshotCommand command(UserVmVO vm) {
        HostVO host = vm.getHostId() == null ? null : hostDao.findById(vm.getHostId());
        if (vm.getHypervisorType() != HypervisorType.KVM || vm.getState() != VirtualMachine.State.Running || host == null)
            throw new InvalidParameterValueException("Running KVM VM with a current host required");
        return new GetVmProcessSnapshotCommand(vm.getInstanceName(), vm.getUuid(), host.getUuid(), Long.toString(vm.getUpdated()), UUID.randomUUID().toString());
    }
    @Override public VmProcessSnapshotResponse refresh(long vmId) {
        UserVmVO vm = authorized(vmId); GetVmProcessSnapshotCommand c = command(vm);
        final long hostId = vm.getHostId(), generation = vm.getUpdated();
        Entry entry;
        synchronized (entries) {
            long time = now();
            entries.entrySet().removeIf(e -> !e.getValue().running && time - e.getValue().started > 15_000_000_000L);
            entry = entries.get(vmId);
            if (entry != null && entry.host == hostId && entry.generation == generation) {
                if (entry.running) return response(VmProcessSnapshot.failure(c, "BUSY"), 0, false, "UNAVAILABLE");
                if (time - entry.started < 5_000_000_000L) {
                    if (entry.snapshot != null) return refreshed(entry.snapshot);
                    return response(VmProcessSnapshot.failure(c, "BUSY"), 0, false, "UNAVAILABLE");
                }
            }
            if (entry != null && entry.running || entries.size() >= 64 && entry == null || !admission.tryAcquire())
                return response(VmProcessSnapshot.failure(c, "BUSY"), 0, false, "UNAVAILABLE");
            entry = new Entry(); entry.started = time; entry.host = hostId; entry.generation = generation; entry.running = true; entries.put(vmId, entry);
        }
        Map<String, Object> result = VmProcessSnapshot.failure(c, "CHECK_FAILED");
        try {
            Answer answer = agentManager.send(hostId, c);
            UserVmVO current = authorized(vmId);
            if (current.getState() != VirtualMachine.State.Running || !Objects.equals(current.getHostId(), hostId)
                    || current.getUpdated() != generation || !current.getUuid().equals(c.getVmUuid()) || now() - entry.started > 10_000_000_000L)
                result = VmProcessSnapshot.failure(c, "STALE_AUTHORITY");
            else if (answer instanceof GetVmProcessSnapshotAnswer && answer.getResult()) {
                // Round trip enforces byte bounds, strict fields and exact numeric identity.
                result = VmProcessSnapshot.decode(((GetVmProcessSnapshotAnswer) answer).getSnapshotJson(), c);
                if ("snapshot".equals(result.get("kind"))) {
                    entry.guestObservedAt = (String) result.get("observedAt");
                    Instant time = Instant.now(); result.put("observedAt", time.toString()); result.put("expiresAt", time.plusSeconds(10).toString());
                    synchronized (entries) { entry.snapshot = result; entry.completed = now(); }
                }
            }
        } catch (com.cloud.exception.AgentUnavailableException | com.cloud.exception.OperationTimedoutException | java.io.IOException e) {
            result = VmProcessSnapshot.failure(c, "CHECK_FAILED");
        } finally {
            synchronized (entries) { entry.running = false; } admission.release();
        }
        requireEnabled();
        return "snapshot".equals(result.get("kind")) ? refreshed(result) : response(result, 0, false, "UNAVAILABLE");
    }
    @Override public VmProcessSnapshotResponse list(long vmId, String snapshotId, String keyword, String sort, boolean descending, int page, int size) {
        UserVmVO vm = authorized(vmId); GetVmProcessSnapshotCommand c = command(vm);
        if (snapshotId == null || !UUID.fromString(snapshotId).toString().equals(snapshotId)) throw new InvalidParameterValueException("Canonical snapshot ID required");
        synchronized (entries) {
            Entry e = entries.get(vmId);
            if (e == null || e.snapshot == null || !snapshotId.equals(e.snapshot.get("snapshotId")) || e.host != vm.getHostId() || e.generation != vm.getUpdated() || now() - e.completed >= 10_000_000_000L) {
                return response(VmProcessSnapshot.failure(c, "STALE_SNAPSHOT"), 0, true, "UNAVAILABLE");
            }
            return page(e.snapshot, keyword, sort == null ? "pid" : sort, descending, page, size);
        }
    }
    @Override public Map<String,Object> actionTarget(long vmId,String snapshotId,long pid,String serviceName) {
        UserVmVO vm=authorized(vmId);command(vm);
        synchronized(entries) {
            Entry e=entries.get(vmId);
            if(e==null || e.running || e.snapshot==null || !snapshotId.equals(e.snapshot.get("snapshotId")) || e.host!=vm.getHostId()
                    || e.generation!=vm.getUpdated() || now()-e.completed>=10_000_000_000L) throw new InvalidParameterValueException("STALE_SNAPSHOT");
            for(Object object:(List<?>)e.snapshot.get("processes")) {
                Map<String,Object> row=VmProcessSnapshot.map(object),identity=VmProcessSnapshot.map(row.get("identity"));
                if(((Number)identity.get("pid")).longValue()!=pid)continue;
                Map<String,Object> selected=new LinkedHashMap<>();selected.put("identity",new LinkedHashMap<>(identity));selected.put("authority",e.snapshot.get("authority"));
                selected.put("observedAt",e.guestObservedAt);selected.put("service",null);
                if(serviceName!=null) {
                    for(Object service:(List<?>)row.get("services")) if(serviceName.equals(VmProcessSnapshot.map(service).get("name")))selected.put("service",service);
                    if(selected.get("service")==null)throw new InvalidParameterValueException("STALE_IDENTITY");
                }
                return selected;
            }
            throw new InvalidParameterValueException("STALE_IDENTITY");
        }
    }
    private static VmProcessSnapshotResponse refreshed(Map<String, Object> snapshot) {
        // Async job results are persisted by Cloud: keep process inventory out of them.
        Map<String, Object> metadata = new LinkedHashMap<>(snapshot);
        int count = ((List<?>) metadata.get("processes")).size(); metadata.put("processes", List.of());
        return response(metadata, count, false, (String) snapshot.get("status"));
    }
    static VmProcessSnapshotResponse page(Map<String, Object> snapshot, String keyword, String sort, boolean descending, int page, int size) {
        if (page < 1 || size < 1 || size > 200 || keyword != null && keyword.length() > 256 || !Set.of("pid", "name", "memoryBytes", "cpuPercent").contains(sort)) throw new InvalidParameterValueException("Invalid snapshot page, search or sort");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object row : (List<?>) snapshot.get("processes")) {
            Map<String, Object> value = VmProcessSnapshot.map(row);
            if (keyword == null || ((String) value.get("name")).toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT))) rows.add(value);
        }
        Comparator<Map<String, Object>> byPid = Comparator.comparingLong(v -> ((Number) VmProcessSnapshot.map(v.get("identity")).get("pid")).longValue());
        Comparator<Map<String, Object>> comparator;
        if ("cpuPercent".equals(sort)) {
            comparator = (left, right) -> {
                Object a = left.get("cpuPercent"), b = right.get("cpuPercent");
                // Missing measurements stay last in both directions.
                if (a == null || b == null) return a == b ? 0 : a == null ? 1 : -1;
                int comparison = new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString()));
                return descending ? -comparison : comparison;
            };
        } else {
            comparator = "name".equals(sort) ? Comparator.comparing(v -> (String) v.get("name")) : "memoryBytes".equals(sort) ? Comparator.comparingLong(v -> v.get("memoryBytes") == null ? -1 : ((Number) v.get("memoryBytes")).longValue()) : byPid;
            if (descending) comparator = comparator.reversed();
        }
        rows.sort(comparator.thenComparing(byPid));
        int start = (int) Math.min(rows.size(), ((long) page - 1) * size);
        Map<String, Object> projection = new LinkedHashMap<>(snapshot); projection.put("processes", new ArrayList<>(rows.subList(start, Math.min(rows.size(), start + size))));
        return response(projection, rows.size(), false, (String) snapshot.get("status"));
    }
    static VmProcessSnapshotResponse response(Map<String, Object> internal, int count, boolean stale, String availability) {
        Map<String, Object> projection = new LinkedHashMap<>(internal);
        projection.put("authority", Map.of("vmUuid", VmProcessSnapshot.map(internal.get("authority")).get("vmUuid")));
        VmProcessSnapshotResponse response = new VmProcessSnapshotResponse(); response.setProcessState(projection); response.setPageMetadata(count, stale, availability); return response;
    }
}
