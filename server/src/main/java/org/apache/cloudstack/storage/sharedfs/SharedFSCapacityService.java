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

package org.apache.cloudstack.storage.sharedfs;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import javax.inject.Inject;
import org.apache.cloudstack.api.response.SharedFSCapacityResponse;
import org.apache.cloudstack.api.response.SharedFSResponse;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.cloudstack.storage.dataservice.*;
import org.apache.cloudstack.storage.dataservice.dao.*;
import org.apache.cloudstack.storage.sharedfs.dao.SharedFSDao;
import org.apache.cloudstack.storage.sharedfs.query.vo.SharedFSJoinVO;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.concurrency.NamedThreadFactory;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.VMInstanceDao;
import com.google.gson.*;

/** SharedFS-only derived statistics. Listing never initiates a guest command. */
public class SharedFSCapacityService extends ManagerBase implements Configurable {
    static final ConfigKey<Integer> POLL = new ConfigKey<>("Advanced", Integer.class,
        "sharedfs.capacity.poll.interval", "60", "Seconds between bounded SharedFS capacity observations", true);
    static final ConfigKey<Integer> STALE = new ConfigKey<>("Advanced", Integer.class,
        "sharedfs.capacity.stale.seconds", "180", "Maximum age in seconds for current SharedFS filesystem observations", true);
    static final ConfigKey<Integer> WORKERS = new ConfigKey<>("Advanced", Integer.class,
        "sharedfs.capacity.workers", "4", "Maximum concurrent read-only SharedFS capacity collectors (restart required)", false);
    static final Set<String> EXCLUDED = Set.of("Destroy", "Destroying", "Expunging", "Expunged");
    static final Set<String> CONNECTED = Set.of("Ready", "Snapshotting", "RevertSnapshotting", "Resizing", "Migrating", "Consolidating");
    @Inject VolumeDao volumeDao;
    @Inject SharedFSDao sharedFSDao;
    @Inject VMInstanceDao vmDao;
    @Inject StorageServiceInstanceDao instanceDao;
    @Inject StorageServiceGuestCommandDispatcher dispatcher;
    @Inject StorageFileShareDao fileShareDao;
    @Inject StorageBlockTargetDao blockTargetDao;
    final ConcurrentMap<Long, Snapshot> cache = new ConcurrentHashMap<>();
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<Long, Long> attempts = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, Integer> failures = new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler;
    private ThreadPoolExecutor workers;

    static final class Snapshot {
        final String fingerprint;
        final JsonObject data;
        final Instant collectedAt;
        Snapshot(String fingerprint, JsonObject data, Instant collectedAt) {
            this.fingerprint = fingerprint; this.data = data.deepCopy(); this.collectedAt = collectedAt;
        }
    }

    @Override public String getConfigComponentName() { return "SharedFSCapacity"; }
    @Override public ConfigKey<?>[] getConfigKeys() { return new ConfigKey<?>[]{POLL, STALE, WORKERS}; }
    @Override public boolean start() {
        int count = Math.max(1, Math.min(8, WORKERS.value()));
        workers = new ThreadPoolExecutor(count, count, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(count * 4),
            new NamedThreadFactory("SharedFS-Capacity"), new ThreadPoolExecutor.AbortPolicy());
        scheduler = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("SharedFS-Capacity-Schedule"));
        scheduler.scheduleWithFixedDelay(this::scheduleCollection, 10, 10, TimeUnit.SECONDS);
        return true;
    }
    @Override public boolean stop() {
        if (scheduler != null) scheduler.shutdownNow();
        if (workers != null) workers.shutdownNow();
        cache.clear(); inFlight.clear(); attempts.clear(); failures.clear();
        return true;
    }

    void scheduleCollection() {
        try {
            long now = System.currentTimeMillis();
            List<SharedFSVO> services = new ArrayList<>(sharedFSDao.listAll());
            Set<Long> ids = new HashSet<>();
            services.removeIf(fs -> fs.getVmId() == null || fs.getState() == SharedFS.State.Destroyed);
            services.forEach(fs -> ids.add(fs.getVmId()));
            cache.keySet().retainAll(ids); attempts.keySet().retainAll(ids); failures.keySet().retainAll(ids);
            // Oldest/unobserved first prevents bounded-queue starvation on large installations.
            services.sort(Comparator.comparingLong(fs -> attempts.getOrDefault(fs.getVmId(), 0L)));
            for (SharedFSVO fs : services) {
                long vm = fs.getVmId();
                long delay = Math.max(15, POLL.value()) * 1000L * (1L << Math.min(3, failures.getOrDefault(vm, 0)));
                long jitter = Math.floorMod(vm * 7919, 5000);
                if (now - attempts.getOrDefault(vm, 0L) < delay + jitter || !inFlight.add(vm)) continue;
                try {
                    workers.execute(() -> {
                        attempts.put(vm, System.currentTimeMillis());
                        try { collect(fs); failures.remove(vm); }
                        catch (RuntimeException e) {
                            failures.merge(vm, 1, Integer::sum);
                            logger.debug("SharedFS capacity observation unavailable for VM {}", vm, e);
                        } finally { inFlight.remove(vm); }
                    });
                } catch (RejectedExecutionException e) { inFlight.remove(vm); }
            }
        } catch (RuntimeException e) { logger.warn("Unable to schedule SharedFS capacity observation", e); }
    }

    void collect(SharedFSVO fs) {
        if (fs.getVmId() == null) return;
        long vm = fs.getVmId();
        var running = vmDao.findById(vm);
        if (running == null || running.getState() != VirtualMachine.State.Running) return;
        var instance = instanceDao.findByVmId(vm);
        if (instance == null || instance.getAccountId() != fs.getAccountId()) return;
        List<VolumeVO> before = volumeDao.listCurrentDataByVmIds(Collections.singleton(vm));
        if (before.stream().anyMatch(v -> v.getAccountId() != fs.getAccountId())) return;
        List<VolumeVO> connected = connected(before, vm);
        if (connected.size() > 1024) throw new IllegalStateException("Capacity membership limit exceeded");
        JsonObject request = new JsonObject(); request.addProperty("capacityOnly", true);
        JsonArray volumes = new JsonArray();
        for (VolumeVO v : connected) {
            JsonObject item = new JsonObject(); item.addProperty("uuid", v.getUuid()); item.addProperty("size", v.getSize()); volumes.add(item);
        }
        request.add("volumes", volumes);
        String fingerprint = fingerprint(before, vm);
        StorageServiceGuestCommandResult result = dispatcher.dispatch(new StorageServiceGuestCommand(vm, "inventory",
            request.toString(), 20, Collections.emptySet()));
        if (!result.isSuccess()) throw new IllegalStateException("Capacity inventory unavailable");
        String json = result.getResultJson();
        if (json == null || json.length() > 8 * 1024 * 1024) throw new IllegalStateException("Invalid capacity snapshot size");
        JsonObject observed = JsonParser.parseString(json).getAsJsonObject();
        List<VolumeVO> after = volumeDao.listCurrentDataByVmIds(Collections.singleton(vm));
        if (!fingerprint.equals(fingerprint(after, vm))) observed.addProperty("status", "TRANSITION");
        cache.put(vm, new Snapshot(fingerprint, observed, Instant.now()));
    }

    /** Called only after existing SharedFS access checks and page selection. */
    public void enrich(List<SharedFSResponse> responses, List<SharedFSJoinVO> views, boolean details) {
        if (responses == null || responses.isEmpty() || views == null) return;
        Set<Long> vmIds = new LinkedHashSet<>();
        Map<String, SharedFSJoinVO> byUuid = new HashMap<>();
        for (SharedFSJoinVO view : views) {
            byUuid.put(view.getUuid(), view);
            if (view.getInstanceId() > 0) vmIds.add(view.getInstanceId());
        }
        List<VolumeVO> rows = volumeDao.listCurrentDataByVmIds(vmIds);
        Map<Long, List<VolumeVO>> byVm = new HashMap<>();
        for (VolumeVO row : rows) byVm.computeIfAbsent(row.getInstanceId(), x -> new ArrayList<>()).add(row);
        for (SharedFSResponse response : responses) {
            SharedFSJoinVO view = byUuid.get(response.getId());
            if (view == null) continue;
            List<VolumeVO> data = byVm.getOrDefault(view.getInstanceId(), Collections.emptyList());
            SharedFSCapacityResponse capacity = aggregate(view.getInstanceId(), view.getAccountId(), view.getVolumeId(),
                view.getSize(), data, cache.get(view.getInstanceId()), details, Instant.now(), Math.max(15, STALE.value()));
            if (details && capacity.volumes != null) addReferences(view.getInstanceId(), capacity, data);
            response.setCapacity(capacity);
        }
    }
    public void enrich(SharedFSResponse response, SharedFSJoinVO view) {
        enrich(Collections.singletonList(response), Collections.singletonList(view), false);
    }

    private void addReferences(long vm, SharedFSCapacityResponse capacity, List<VolumeVO> rows) {
        StorageServiceInstanceVO instance = instanceDao.findByVmId(vm);
        if (instance == null) return;
        Map<Long, int[]> refs = new HashMap<>();
        for (var protocol : List.of(StorageServiceInstance.Protocol.NFS, StorageServiceInstance.Protocol.SMB)) {
            for (var share : fileShareDao.listByInstanceIdAndProtocol(instance.getId(), protocol))
                if (share.getVolumeId() != null && share.getState() != StorageServiceInstance.ResourceState.Destroyed)
                    refs.computeIfAbsent(share.getVolumeId(), x -> new int[3])[protocol == StorageServiceInstance.Protocol.NFS ? 0 : 1]++;
        }
        for (var protocol : List.of(StorageServiceInstance.Protocol.ISCSI, StorageServiceInstance.Protocol.NVME_OF))
            for (var target : blockTargetDao.listByInstanceIdAndProtocol(instance.getId(), protocol))
                if (target.getVolumeId() != null && target.getState() != StorageServiceInstance.ResourceState.Destroyed)
                    refs.computeIfAbsent(target.getVolumeId(), x -> new int[3])[2]++;
        Map<String, Long> ids = new HashMap<>(); rows.forEach(v -> ids.put(v.getUuid(), v.getId()));
        capacity.volumes.forEach(v -> {
            int[] counts = refs.getOrDefault(ids.get(v.id), new int[3]);
            v.nfsReferences = counts[0]; v.smbReferences = counts[1]; v.blockReferences = counts[2];
        });
    }

    static List<VolumeVO> candidates(List<VolumeVO> rows, long vm) {
        Map<String, VolumeVO> unique = new TreeMap<>();
        for (VolumeVO v : rows) {
            if (v.getVolumeType() != Volume.Type.DATADISK || v.getRemoved() != null
                || !Objects.equals(v.getInstanceId(), vm) || EXCLUDED.contains(String.valueOf(v.getState()))) continue;
            if (v.getUuid() == null) throw new IllegalArgumentException("DATA UUID is missing");
            unique.put(v.getUuid(), v);
        }
        return new ArrayList<>(unique.values());
    }
    static List<VolumeVO> connected(List<VolumeVO> rows, long vm) {
        List<VolumeVO> result = candidates(rows, vm);
        result.removeIf(v -> !CONNECTED.contains(String.valueOf(v.getState())));
        return result;
    }
    static String fingerprint(List<VolumeVO> rows, long vm) {
        StringBuilder value = new StringBuilder();
        for (VolumeVO v : candidates(rows, vm)) value.append(v.getUuid()).append(':').append(vm).append(':')
            .append(v.getDeviceId()).append(':').append(v.getSize()).append(':').append(v.getAccountId()).append(':')
            .append(CONNECTED.contains(String.valueOf(v.getState())) ? "CONNECTED" : v.getState()).append(':')
            .append(v.getAttached() == null ? "" : v.getAttached().getTime()).append(';');
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    static SharedFSCapacityResponse aggregate(long vm, long account, long initial, Long initialSize, List<VolumeVO> rows,
            Snapshot snapshot, boolean details, Instant now, long staleSeconds) {
        SharedFSCapacityResponse result = new SharedFSCapacityResponse();
        result.initialVolumeBytes = initialSize;
        if (vm <= 0) { result.reason = "VM_NOT_ATTACHED"; return result; }
        List<VolumeVO> selected = candidates(rows, vm);
        if (selected.stream().anyMatch(v -> v.getAccountId() != account)) {
            result.reason = "MEMBERSHIP_UNAVAILABLE"; return result;
        }
        List<VolumeVO> connected = connected(selected, vm);
        result.provisioningStatus = selected.size() == connected.size() ? "CURRENT" : "TRANSITION";
        result.volumeCount = connected.size(); result.provisionedBytes = 0L;
        if (details) result.volumes = new ArrayList<>();
        try {
            for (VolumeVO v : connected) {
                if (v.getSize() == null || v.getSize() < 0) throw new ArithmeticException("Negative size");
                result.provisionedBytes = Math.addExact(result.provisionedBytes, v.getSize());
                if (v.getId() == initial) result.initialVolumeBytes = v.getSize();
            }
        } catch (ArithmeticException e) {
            result.provisionedBytes = null; result.volumeCount = null; result.provisioningStatus = "UNAVAILABLE";
            result.reason = "CAPACITY_OUT_OF_RANGE"; return result;
        }
        Map<String, SharedFSCapacityResponse.Volume> volumeViews = new HashMap<>();
        for (VolumeVO v : selected) {
            var item = new SharedFSCapacityResponse.Volume();
            item.id = v.getUuid(); item.name = v.getName(); item.size = v.getSize(); item.state = String.valueOf(v.getState());
            item.hidden = !v.isDisplayVolume(); item.status = CONNECTED.contains(item.state) ? "UNAVAILABLE" : "TRANSITION";
            volumeViews.put(v.getUuid(), item);
            if (details) result.volumes.add(item);
        }
        result.unobservedVolumeCount = connected.size();
        if (snapshot == null) { result.reason = "NOT_OBSERVED"; return result; }
        result.collectedAt = snapshot.collectedAt.toString();
        if (!snapshot.fingerprint.equals(fingerprint(rows, vm)) || "TRANSITION".equals(text(snapshot.data, "status"))) {
            result.provisioningStatus = "TRANSITION"; result.usageStatus = "TRANSITION"; result.reason = "MEMBERSHIP_CHANGED"; return result;
        }
        JsonObject data = snapshot.data;
        if (!data.has("capacitySnapshotVersion") || data.get("capacitySnapshotVersion").getAsInt() != 1) {
            result.usageStatus = "UNSUPPORTED"; result.reason = "RUNTIME_UNSUPPORTED"; return result;
        }
        if (!data.has("observations") || !data.get("observations").isJsonArray()) { result.reason = "INVALID_OBSERVATION"; return result; }
        Instant observed;
        try { observed = Instant.parse(text(data, "observedAt")); }
        catch (RuntimeException e) { result.reason = "INVALID_OBSERVATION_TIME"; return result; }
        if (observed.isAfter(now.plusSeconds(30))) { result.reason = "INVALID_OBSERVATION_TIME"; return result; }
        result.observedAt = observed.toString();
        boolean stale = observed.isBefore(now.minusSeconds(staleSeconds)) || "STALE".equals(text(data, "status"));
        Map<String, JsonObject> observations = new HashMap<>();
        for (JsonElement e : data.getAsJsonArray("observations")) if (e.isJsonObject()) {
            JsonObject item = e.getAsJsonObject();
            String uuid = text(item, "volumeUuid");
            if (observations.putIfAbsent(uuid, item) != null) { result.reason = "DUPLICATE_OBSERVATION"; return result; }
        }
        Map<String, long[]> filesystems = new HashMap<>();
        long used = 0, total = 0;
        try {
            for (VolumeVO v : connected) {
                var item = volumeViews.get(v.getUuid());
                JsonObject observation = observations.get(v.getUuid());
                if (observation == null || !"EXACT".equals(text(observation, "mappingStatus"))) continue;
                String status = text(observation, "status");
                if ("UNSUPPORTED".equals(status)) {
                    item.status = "UNSUPPORTED"; result.unsupportedVolumeCount++; result.unobservedVolumeCount--; continue;
                }
                if (!observation.has("filesystems") || !observation.get("filesystems").isJsonArray()) continue;
                boolean found = false;
                for (JsonElement element : observation.getAsJsonArray("filesystems")) {
                    JsonObject fs = element.getAsJsonObject();
                    String key = text(fs, "identity"), type = text(fs, "filesystem");
                    if (key.isBlank() || !Set.of("xfs", "ext4").contains(type)) continue;
                    long fsUsed = fs.get("usedBytes").getAsLong(), fsTotal = fs.get("totalBytes").getAsLong();
                    if (fsUsed < 0 || fsTotal <= 0 || fsUsed > fsTotal) throw new IllegalArgumentException("Invalid filesystem sample");
                    long[] previous = filesystems.putIfAbsent(key, new long[]{fsUsed, fsTotal});
                    if (previous == null) { used = Math.addExact(used, fsUsed); total = Math.addExact(total, fsTotal); }
                    else if (previous[0] != fsUsed || previous[1] != fsTotal) throw new IllegalArgumentException("Inconsistent aliases");
                    item.filesystem = type; item.filesystemUuid = text(fs, "uuid"); item.usedBytes = fsUsed; item.totalBytes = fsTotal;
                    item.status = stale ? "STALE" : "CURRENT"; item.observedAt = result.observedAt; found = true;
                }
                if (found) { result.observedVolumeCount++; result.unobservedVolumeCount--; }
            }
        } catch (RuntimeException e) {
            result.reason = "INVALID_FILESYSTEM_OBSERVATION"; result.usageStatus = "UNAVAILABLE";
            result.observedVolumeCount = 0; result.filesystemCount = 0;
            volumeViews.values().forEach(v -> { v.usedBytes = null; v.totalBytes = null; v.status = "UNAVAILABLE"; });
            return result;
        }
        result.filesystemCount = filesystems.size();
        if (!filesystems.isEmpty()) {
            result.filesystemUsedBytes = used; result.filesystemTotalBytes = total;
            result.usageStatus = stale ? "STALE" : result.unobservedVolumeCount + result.unsupportedVolumeCount > 0 ? "PARTIAL" : "CURRENT";
        } else result.usageStatus = result.unsupportedVolumeCount > 0 && result.unobservedVolumeCount == 0 ? "UNSUPPORTED" : "UNAVAILABLE";
        return result;
    }
    static String text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }
}
