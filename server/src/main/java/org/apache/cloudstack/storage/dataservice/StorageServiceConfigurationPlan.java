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
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import javax.persistence.Column;
import com.cloud.utils.db.GenericDao;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.cloudstack.storage.dataservice.dao.StorageAccessRuleDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageBlockTargetDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageFileShareDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceProtocolDao;

/**
 * A per-attempt overlay for the four existing configuration DAOs. Reads and builders
 * see detached candidates; SQL updates/removes run only after guest verification.
 * Newly allocated numeric IDs are explicitly reserved as Creating, never Ready.
 */
final class StorageServiceConfigurationPlan {
    private static final Gson JSON = new Gson();
    private static final int LIMIT = 1024 * 1024;
    final Map<String, JsonObject> commands = new LinkedHashMap<>();
    final List<Runnable> databaseActions = new ArrayList<>();
    final List<JsonObject> networkChanges = new ArrayList<>();
    final Map<Class<?>, Table<?>> tables = new LinkedHashMap<>();
    final long instanceId;
    private final Consumer<String> checkpoint;
    private boolean recordingBaseline;

    StorageServiceConfigurationPlan(final long instanceId, final StorageServiceProtocolDao protocols,
            final StorageFileShareDao shares, final StorageBlockTargetDao targets, final StorageAccessRuleDao rules,
            final Consumer<String> checkpoint) {
        this.instanceId = instanceId; this.checkpoint = checkpoint;
        add(StorageServiceProtocolDao.class, StorageServiceProtocolVO.class, protocols, protocols.listByInstanceId(instanceId));
        List<StorageFileShareVO> fs = new ArrayList<>();
        List<StorageBlockTargetVO> bs = new ArrayList<>();
        for (StorageServiceInstance.Protocol p : Arrays.asList(StorageServiceInstance.Protocol.NFS, StorageServiceInstance.Protocol.SMB))
            fs.addAll(shares.listByInstanceIdAndProtocol(instanceId, p));
        for (StorageServiceInstance.Protocol p : Arrays.asList(StorageServiceInstance.Protocol.ISCSI, StorageServiceInstance.Protocol.NVME_OF))
            bs.addAll(targets.listByInstanceIdAndProtocol(instanceId, p));
        add(StorageFileShareDao.class, StorageFileShareVO.class, shares, fs);
        add(StorageBlockTargetDao.class, StorageBlockTargetVO.class, targets, bs);
        List<StorageAccessRuleVO> scopedRules = new ArrayList<>();
        for (StorageFileShareVO v : fs) scopedRules.addAll(rules.listByResource(StorageServiceInstance.AccessResourceType.FILE_SHARE, v.getId()));
        for (StorageBlockTargetVO v : bs) scopedRules.addAll(rules.listByResource(StorageServiceInstance.AccessResourceType.BLOCK_TARGET, v.getId()));
        add(StorageAccessRuleDao.class, StorageAccessRuleVO.class, rules, scopedRules);
        bounded(snapshot());
    }

    private <T> void add(final Class<?> contract, final Class<T> type, final GenericDao<T, Long> dao, final List<T> baseline) {
        tables.put(contract, new Table<>(contract, type, dao, baseline));
    }
    @SuppressWarnings("unchecked")
    <D> D dao(final Class<D> type) { return (D)tables.get(type).proxy; }
    void baseline(final boolean value) { recordingBaseline = value; }
    void reset() { tables.values().forEach(Table::reset); commands.clear(); }
    String snapshot() {
        JsonObject value = new JsonObject();
        for (Table<?> table : tables.values()) value.add(table.type.getSimpleName(), table.snapshot());
        String text = JSON.toJson(value); bounded(text); return text;
    }
    static void bounded(final String value) {
        if (value.getBytes(StandardCharsets.UTF_8).length > LIMIT)
            throw new CloudRuntimeException("Configuration snapshot exceeds the 1 MiB UTF-8 limit");
    }
    void restore(final String candidate, final String before) {
        JsonObject value = JsonParser.parseString(candidate).getAsJsonObject().getAsJsonObject("tables");
        JsonObject baseline = JsonParser.parseString(before).getAsJsonObject();
        for (Table<?> table : tables.values()) table.restore(value.getAsJsonArray(table.type.getSimpleName()),
                baseline.getAsJsonArray(table.type.getSimpleName()));
    }
    void commit() { for (Table<?> table : tables.values()) table.commit(false); for (Table<?> table : tables.values()) table.commit(true);
        databaseActions.forEach(Runnable::run); }
    void discardReservations() { for (Table<?> table : tables.values()) table.discard(); }
    String resourceUuid(final String command, final Map<String, Object> request) {
        Object id = request.get("id");
        Class<?> table = command.contains("Acl") ? StorageAccessRuleDao.class :
                command.contains("NfsExport") || command.contains("SmbShare") ? StorageFileShareDao.class : StorageBlockTargetDao.class;
        if (!(id instanceof Number)) return null;
        Object row = tables.get(table).rows.get(((Number)id).longValue());
        return row == null ? null : String.valueOf(property(row, "Uuid"));
    }
    void validateNaturalKeys() {
        for (Table<?> table : tables.values()) {
            Set<String> keys = new HashSet<>();
            for (Object row : table.rows.values()) {
                String key;
                if (row instanceof StorageServiceProtocolVO) {
                    StorageServiceProtocolVO v = (StorageServiceProtocolVO)row;
                    key = v.getProtocol() == StorageServiceInstance.Protocol.SMB
                            ? v.getProtocol() + ":" + (v.getListenIp() == null || v.getListenIp().trim().isEmpty() ? "0.0.0.0" : v.getListenIp().trim()) + ":" + (v.getPort() == null ? 445 : v.getPort())
                            : v.getProtocol() + ":" + v.getListenIp() + ":" + v.getPort();
                } else if (row instanceof StorageFileShareVO) {
                    StorageFileShareVO v = (StorageFileShareVO)row;
                    key = v.getProtocol() + ":" + v.getName();
                } else if (row instanceof StorageBlockTargetVO) {
                    StorageBlockTargetVO v = (StorageBlockTargetVO)row;
                    key = v.getProtocol() + ":" + v.getTargetName() + ":" + v.getLunOrNamespace();
                } else {
                    StorageAccessRuleVO v = (StorageAccessRuleVO)row;
                    key = v.getResourceType() + ":" + v.getResourceId() + ":" + v.getPrincipalType() + ":" + v.getPrincipal();
                }
                if (!keys.add(key)) throw new CloudRuntimeException("Duplicate configuration resource in candidate");
            }
        }
    }

    private static Object property(final Object row, final String name) {
        try { return row.getClass().getMethod("get" + name).invoke(row); }
        catch (ReflectiveOperationException e) { throw new CloudRuntimeException("Unsupported configuration property", e); }
    }
    private final class Table<T> implements InvocationHandler {
        final Class<T> type; final GenericDao<T, Long> underlying; final Object proxy;
        final Map<Long, T> original = new LinkedHashMap<>(), rows = new LinkedHashMap<>();
        final Set<Long> reservations = new LinkedHashSet<>(), dirty = new LinkedHashSet<>(), removed = new LinkedHashSet<>();
        Table(final Class<?> contract, final Class<T> type, final GenericDao<T, Long> dao, final List<T> baseline) {
            this.type = type; underlying = dao;
            for (T value : baseline) original.put(id(value), copy(value));
            reset();
            proxy = Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract}, this);
        }
        long id(final Object value) { return ((Number)property(value, "Id")).longValue(); }
        T copy(final T value) { return JSON.fromJson(JSON.toJson(value, type), type); }
        void reset() { rows.clear(); original.forEach((id, value) -> rows.put(id, copy(value))); dirty.clear(); removed.clear(); }
        JsonArray snapshot() { JsonArray array = new JsonArray(); for (T row : rows.values()) array.add(JSON.toJsonTree(row, type)); return array; }
        @Override public Object invoke(final Object ignored, final Method method, final Object[] args) throws Exception {
            String name = method.getName();
            if ("update".equals(name)) {
                long id = ((Number)args[0]).longValue();
                if (!rows.containsKey(id)) throw new CloudRuntimeException("Configuration resource is outside the reserved instance");
                rows.put(id, type.cast(args[1])); dirty.add(id); return true;
            }
            if ("remove".equals(name)) {
                long id = ((Number)args[0]).longValue();
                if (!rows.containsKey(id)) return false;
                rows.remove(id); removed.add(id); return true;
            }
            if ("persist".equals(name)) {
                if (recordingBaseline) throw new CloudRuntimeException("Baseline observation cannot allocate configuration resources");
                T candidate = type.cast(args[0]), reservation = copy(candidate);
                Method setState = type.getMethod("setState", StorageServiceInstance.ResourceState.class);
                setState.invoke(reservation, StorageServiceInstance.ResourceState.Creating);
                // Reservation and its durable ID checkpoint share one short SQL transaction.
                return com.cloud.utils.db.Transaction.execute((com.cloud.utils.db.TransactionCallback<T>) status -> {
                    T stored = underlying.persist(reservation);
                    long id = id(stored);
                    try { Field field = type.getDeclaredField("id"); field.setAccessible(true); field.set(candidate, id); }
                    catch (ReflectiveOperationException e) { throw new CloudRuntimeException("Cannot reserve configuration identity", e); }
                    reservations.add(id); dirty.add(id); rows.put(id, candidate);
                    checkpoint.accept(StorageServiceConfigurationPlan.this.snapshot());
                    return candidate;
                });
            }
            if ("findById".equals(name) || "findByIdIncludingRemoved".equals(name)) return rows.get(((Number)args[0]).longValue());
            if ("findByUuid".equals(name)) return rows.values().stream().filter(v -> Objects.equals(property(v, "Uuid"), args[0])).findFirst().orElse(null);
            List<T> result = new ArrayList<>(rows.values());
            if ("listAll".equals(name)) return result;
            if ("listByInstanceId".equals(name) || "listByInstanceIdAndProtocol".equals(name) || "findByInstanceIdAndProtocol".equals(name)
                    || "findByInstanceIdVolumeIdAndProtocol".equals(name)) {
                result.removeIf(v -> !Objects.equals(property(v, "InstanceId"), args[0]));
                if (args.length > 1 && args[1] instanceof StorageServiceInstance.Protocol)
                    result.removeIf(v -> !Objects.equals(property(v, "Protocol"), args[1]));
                if (args.length == 3) {
                    result.removeIf(v -> !Objects.equals(property(v, "VolumeId"), args[1])
                            || !Objects.equals(property(v, "Protocol"), args[2]));
                }
            } else if ("listByProtocol".equals(name)) result.removeIf(v -> !Objects.equals(property(v, "Protocol"), args[0]));
            else if ("listByResource".equals(name)) result.removeIf(v -> !Objects.equals(property(v, "ResourceType"), args[0])
                    || !Objects.equals(property(v, "ResourceId"), args[1]));
            else throw new CloudRuntimeException("Unsupported configuration DAO operation: " + name);
            return name.startsWith("find") ? (result.isEmpty() ? null : result.get(0)) : result;
        }
        void restore(final JsonArray candidate, final JsonArray baseline) {
            Set<Long> oldIds = new HashSet<>();
            for (JsonElement item : baseline) oldIds.add(item.getAsJsonObject().get("id").getAsLong());
            rows.clear(); dirty.clear(); removed.clear();
            for (JsonElement item : candidate) {
                T row = JSON.fromJson(item, type); long id = id(row);
                rows.put(id, row); dirty.add(id); if (!oldIds.contains(id)) reservations.add(id);
            }
            for (Long id : original.keySet()) if (!rows.containsKey(id)) removed.add(id);
        }
        void commit(final boolean deletes) {
            if (deletes) { for (Long id : removed) underlying.remove(id); return; }
            for (Long id : dirty) {
                T row = rows.get(id); if (row == null) continue;
                T update = underlying.createForUpdate(id);
                for (Field field : type.getDeclaredFields()) {
                    if (field.getAnnotation(Column.class) == null || Arrays.asList("id", "uuid", "created").contains(field.getName())) continue;
                    String setter = "set" + Character.toUpperCase(field.getName().charAt(0)) + field.getName().substring(1);
                    try { field.setAccessible(true); type.getMethod(setter, field.getType()).invoke(update, field.get(row)); }
                    catch (NoSuchMethodException e) { /* Immutable columns have no setter. */ }
                    catch (ReflectiveOperationException e) { throw new CloudRuntimeException("Cannot commit configuration candidate", e); }
                }
                if (!underlying.update(id, update)) throw new CloudRuntimeException("Configuration row changed before commit");
            }
        }
        void discard() { for (Long id : reservations) underlying.remove(id); }
    }
}
