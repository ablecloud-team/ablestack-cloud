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
package org.apache.cloudstack.storage.object;

import java.util.Date;
import java.util.Objects;
import java.util.function.Supplier;

import com.google.gson.Gson;
import com.cloud.agent.api.to.BucketTO;
import com.cloud.configuration.Resource.ResourceType;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.resourcelimit.CheckedReservation;
import com.cloud.resourcelimit.ResourceLimitManagerImpl;
import com.cloud.storage.BucketVO;
import com.cloud.storage.DataStoreRole;
import com.cloud.storage.dao.BucketDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.api.command.user.bucket.CreateBucketCmd;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.reservation.dao.ReservationDao;
import org.apache.cloudstack.storage.datastore.db.BucketQuotaOperationDao;
import org.apache.cloudstack.storage.datastore.db.BucketQuotaOperationVO;
import org.apache.cloudstack.storage.datastore.db.ObjectStoreDao;
import org.apache.cloudstack.storage.datastore.db.ObjectStoreVO;

/**
 * A durable, store-serialized quota saga. No SQL transaction spans provider I/O.
 * Reservations remain charged until a verified provider outcome and one atomic ledger commit.
 */
public class BucketQuotaCoordinator {
    private final ObjectStoreDao stores;
    private final BucketDao buckets;
    private final BucketQuotaOperationDao operations;
    private final DataStoreManager dataStores;
    private final AccountManager accounts;
    private final ResourceLimitManagerImpl limits;
    private final ReservationDao reservations;
    private final Gson gson = new Gson();

    public BucketQuotaCoordinator(ObjectStoreDao stores, BucketDao buckets, BucketQuotaOperationDao operations,
            DataStoreManager dataStores, AccountManager accounts, ResourceLimitManagerImpl limits, ReservationDao reservations) {
        this.stores = stores; this.buckets = buckets; this.operations = operations; this.dataStores = dataStores;
        this.accounts = accounts; this.limits = limits; this.reservations = reservations;
    }

    protected <T> T inTransaction(Supplier<T> action) {
        return Transaction.execute((com.cloud.utils.db.TransactionCallback<T>) status -> action.get());
    }

    public static long quotaBytes(Integer quota) {
        if (quota == null || quota <= 0) {
            throw new InvalidParameterValueException("Bucket quota must be a positive integer GiB (1-2147483647)");
        }
        return Math.multiplyExact(quota.longValue(), ResourceType.bytesToGiB);
    }

    static long legacyBytes(Integer quota) {
        return quota == null || quota <= 0 ? 0 : quotaBytes(quota);
    }

    public static String lockName(long storeId) { return "BucketQuota-Store-" + storeId; }

    public static <T> T withStoreLock(long storeId, Supplier<T> action) {
        GlobalLock lock = GlobalLock.getInternLock(lockName(storeId));
        try {
            if (!lock.lock(30)) {
                throw new InvalidParameterValueException("Another bucket or object store operation is running; refresh and retry");
            }
            try { return action.get(); } finally { lock.unlock(); }
        } finally { lock.releaseRef(); }
    }

    private ObjectStoreVO store(long id) {
        ObjectStoreVO store = stores.findById(id);
        if (store == null) { throw new InvalidParameterValueException("Object store is unavailable"); }
        return store;
    }

    private ObjectStoreEntity entity(long id) {
        return (ObjectStoreEntity)dataStores.getDataStore(id, DataStoreRole.Object);
    }

    private ObjectStoreQuotaProvider provider(ObjectStoreEntity store) {
        if (!(store.getDriver() instanceof ObjectStoreQuotaProvider)) {
            throw new InvalidParameterValueException("Object store provider does not support verified bucket quota operations");
        }
        return (ObjectStoreQuotaProvider)store.getDriver();
    }

    private void capacity(ObjectStoreVO store, long additional) {
        long occupied = Math.addExact(Math.max(0L, Objects.requireNonNullElse(store.getAllocatedSize(), 0L)),
                operations.reservedBytesForStore(store.getId()));
        long requested = Math.addExact(occupied, Math.max(0L, additional));
        if (store.getTotalSize() != null && store.getTotalSize() > 0 && requested > store.getTotalSize()) {
            throw new InvalidParameterValueException("Bucket quota exceeds available object store capacity (including pending reservations)");
        }
    }

    private void idle(long bucketId) {
        BucketQuotaOperationVO pending = operations.findActiveByBucket(bucketId);
        if (pending != null) { throw new BucketQuotaException(pending.getUuid(), true); }
    }

    private BucketQuotaOperationVO operation(BucketVO bucket, String kind, Integer requested, BucketQuotaState before) {
        BucketQuotaOperationVO op = new BucketQuotaOperationVO();
        op.setBucketId(bucket.getId()); op.setActiveBucketId(bucket.getId());
        op.setObjectStoreId(bucket.getObjectStoreId()); op.setAccountId(bucket.getAccountId()); op.setDomainId(bucket.getDomainId());
        op.setOperationType(kind); op.setPreviousQuota(bucket.getQuota()); op.setRequestedQuota(requested);
        op.setStoreUrl(store(bucket.getObjectStoreId()).getUrl());
        op.setPreviousSnapshot(gson.toJson(before));
        op.setExternalId(before.getExternalId());
        return op;
    }

    public BucketVO allocate(CreateBucketCmd cmd) throws ResourceAllocationException {
        quotaBytes(cmd.getQuota());
        // Keep checked reservations alive through the atomic bucket + durable store reservation transaction.
        Account owner = accounts.getActiveAccountById(cmd.getEntityOwnerId());
        try (CheckedReservation count = new CheckedReservation(owner, ResourceType.bucket, 1L, reservations, limits);
             CheckedReservation bytes = new CheckedReservation(owner, ResourceType.object_storage,
                     quotaBytes(cmd.getQuota()), reservations, limits)) {
            return withStoreLock(cmd.getObjectStoragePoolId(), () -> {
                ObjectStoreVO store = store(cmd.getObjectStoragePoolId());
                capacity(store, quotaBytes(cmd.getQuota()));
                BucketVO candidate = new BucketVO(owner.getId(), owner.getDomainId(), store.getId(), cmd.getBucketName(),
                        cmd.getQuota(), cmd.isVersioning(), cmd.isEncryption(), cmd.isObjectLocking(), cmd.getPolicy());
                if (provider(entity(store.getId())).readBucketQuota(candidate).exists()) {
                    throw new InvalidParameterValueException("Bucket name already exists in object store");
                }
                return inTransaction(() -> {
                    BucketVO bucket = buckets.persist(candidate);
                    BucketQuotaOperationVO op = operation(bucket, "CREATE", cmd.getQuota(), BucketQuotaState.missing());
                    op.setReservedBytes(quotaBytes(cmd.getQuota()));
                    operations.persist(op);
                    limits.incrementResourceCount(owner.getId(), ResourceType.bucket);
                    limits.incrementResourceCount(owner.getId(), ResourceType.object_storage, quotaBytes(cmd.getQuota()));
                    return bucket;
                });
            });
        }
    }

    public BucketVO create(long bucketId) {
        BucketVO bucket = buckets.findById(bucketId);
        if (bucket == null) { throw new InvalidParameterValueException("Bucket is unavailable"); }
        return withStoreLock(bucket.getObjectStoreId(), () -> {
            BucketQuotaOperationVO op = operations.findActiveByBucket(bucketId);
            if (op == null) {
                BucketVO current = buckets.findById(bucketId);
                if (current != null && current.getState() == Bucket.State.Created) { return current; }
                throw new InvalidParameterValueException("Bucket create operation is unavailable");
            }
            execute(op, false);
            return buckets.findById(bucketId);
        });
    }

    public boolean update(BucketVO initial, Integer requested) throws ResourceAllocationException {
        long desired = quotaBytes(requested);
        Account owner = accounts.getActiveAccountById(initial.getAccountId());
        // Reservation protects concurrent account/domain/project allocation, even across stores.
        long increase = Math.max(0L, desired - legacyBytes(initial.getQuota()));
        BucketQuotaOperationVO prepared;
        try (CheckedReservation reservation = new CheckedReservation(owner, ResourceType.object_storage,
                increase, reservations, limits)) {
            prepared = withStoreLock(initial.getObjectStoreId(), () -> {
                BucketVO bucket = buckets.findById(initial.getId());
                idle(bucket.getId());
                if (Objects.equals(bucket.getQuota(), requested)) { return null; }
                long delta = desired - legacyBytes(bucket.getQuota());
                // A stale caller must not reserve less account capacity than the current delta.
                if (delta > increase) { throw new InvalidParameterValueException("Bucket quota changed; refresh and retry"); }
                ObjectStoreVO store = store(bucket.getObjectStoreId());
                capacity(store, Math.max(0L, delta));
                BucketQuotaState before = provider(entity(store.getId())).readBucketQuota(bucket);
                if (!before.exists()) { throw new InvalidParameterValueException("Bucket is missing from object store; reconcile it before changing quota"); }
                if (desired < before.getUsedBytes()) { throw new InvalidParameterValueException("Bucket quota is below verified live object usage"); }
                if (legacyBytes(bucket.getQuota()) > 0 && (!before.isEnabled()
                        || before.getMaxSizeBytes() != legacyBytes(bucket.getQuota()))) {
                    throw new InvalidParameterValueException("Cloud and object store quota differ; reconcile provider configuration before changing quota");
                }
                BucketQuotaOperationVO op = inTransaction(() -> {
                    BucketQuotaOperationVO next = operation(bucket, "UPDATE", requested, before);
                    next.setReservedBytes(Math.max(0L, delta));
                    next.setAccountHoldBytes(Math.max(0L, delta));
                    operations.persist(next);
                    if (delta > 0) { limits.incrementResourceCount(bucket.getAccountId(), ResourceType.object_storage, delta); }
                    return next;
                });
                return op;
            });
        }
        // Durable account hold replaces the ephemeral CheckedReservation before any provider write.
        if (prepared == null) { return true; }
        return withStoreLock(initial.getObjectStoreId(), () -> {
            BucketQuotaOperationVO current = operations.findById(prepared.getId());
            if (current.isActive()) { execute(current, false); }
            else if (!"Succeeded".equals(current.getState())) { throw new BucketQuotaException(current.getUuid(), false); }
            return true;
        });
    }

    public boolean delete(BucketVO initial) {
        return withStoreLock(initial.getObjectStoreId(), () -> {
            BucketVO bucket = buckets.findById(initial.getId());
            idle(bucket.getId());
            BucketQuotaState before = provider(entity(bucket.getObjectStoreId())).readBucketQuota(bucket);
            BucketQuotaOperationVO op = inTransaction(() ->
                    operations.persist(operation(bucket, "DELETE", null, before)));
            execute(op, false);
            return true;
        });
    }

    public boolean reconcile(BucketVO bucket, String expectedOperation) {
        return withStoreLock(bucket.getObjectStoreId(), () -> {
            BucketQuotaOperationVO op = operations.findActiveByBucket(bucket.getId());
            if (op == null || !op.getUuid().equals(expectedOperation)) {
                throw new InvalidParameterValueException("Quota operation changed; refresh and retry");
            }
            execute(op, true);
            if (!"Succeeded".equals(operations.findById(op.getId()).getState())) {
                throw new BucketQuotaException(op.getUuid(), false);
            }
            return true;
        });
    }

    public void recover() {
        for (BucketQuotaOperationVO item : operations.listActive()) {
            if ("NeedsReconcile".equals(item.getState())) { continue; }
            try {
                withStoreLock(item.getObjectStoreId(), () -> {
                    BucketQuotaOperationVO current = operations.findById(item.getId());
                    if (current != null && current.isActive() && !"NeedsReconcile".equals(current.getState())) { execute(current, false); }
                    return null;
                });
            } catch (RuntimeException ignored) {
                // Operation ID/state is durable; never log provider response bodies or credentials.
            }
        }
    }

    private void save(BucketQuotaOperationVO op, String state) {
        op.setState(state); op.setUpdated(new Date());
        if (!operations.update(op.getId(), op)) { throw new CloudRuntimeException("Unable to save bucket quota operation"); }
    }

    private BucketQuotaState previous(BucketQuotaOperationVO op) {
        return gson.fromJson(op.getPreviousSnapshot(), BucketQuotaState.class);
    }

    private void sameIdentity(BucketQuotaState current, BucketQuotaOperationVO op) {
        if (current.exists() && op.getExternalId() != null && !op.getExternalId().equals(current.getExternalId())) {
            throw new CloudRuntimeException("Bucket identity changed");
        }
    }

    private void execute(BucketQuotaOperationVO op, boolean manual) {
        if ("NeedsReconcile".equals(op.getState()) && !manual) { throw new BucketQuotaException(op.getUuid(), true); }
        BucketVO bucket = buckets.findById(op.getBucketId());
        if (bucket == null || !Objects.equals(store(op.getObjectStoreId()).getUrl(), op.getStoreUrl())) {
            save(op, "NeedsReconcile"); throw new BucketQuotaException(op.getUuid(), true);
        }
        ObjectStoreEntity store = entity(op.getObjectStoreId());
        ObjectStoreQuotaProvider provider = provider(store);
        op.setAttempts(op.getAttempts() + 1);
        try {
            if ("Compensating".equals(op.getState())) { compensate(op, bucket, provider); return; }
            BucketQuotaState live = provider.readBucketQuota(bucket);
            sameIdentity(live, op);
            if ("DELETE".equals(op.getOperationType())) {
                save(op, "Applying");
                if (live.exists() && !store.deleteBucket(new BucketTO(bucket))) { throw new CloudRuntimeException("Provider refused bucket deletion"); }
                if (provider.readBucketQuota(bucket).exists()) { throw new CloudRuntimeException("Bucket deletion was not verified"); }
            } else {
                if ("CREATE".equals(op.getOperationType()) && !op.getExternalCreated()) {
                    // An ambiguous create response never grants permission to delete an existing bucket.
                    if (live.exists() && !manual) { save(op, "NeedsReconcile"); throw new BucketQuotaException(op.getUuid(), true); }
                    save(op, "Applying");
                    if (!live.exists()) {
                        if (!store.createUser(bucket.getAccountId())) { throw new CloudRuntimeException("Unable to initialize object store account"); }
                        store.createBucket(bucket, bucket.isObjectLock());
                        live = provider.readBucketQuota(bucket);
                    }
                    if (!live.exists()) { throw new CloudRuntimeException("Bucket creation was not verified"); }
                    op.setExternalCreated(true); op.setExternalId(live.getExternalId());
                    save(op, "Applying");
                }
                if (!live.exists()) { throw new CloudRuntimeException("Provider bucket is missing"); }
                sameIdentity(live, op);
                BucketQuotaState old = previous(op);
                BucketQuotaState target = live.withLimitGiB(op.getRequestedQuota());
                if ("UPDATE".equals(op.getOperationType()) && !live.sameQuota(old) && !live.sameQuota(target)) {
                    save(op, "NeedsReconcile"); throw new BucketQuotaException(op.getUuid(), true);
                }
                if (quotaBytes(op.getRequestedQuota()) < live.getUsedBytes()) { throw new CloudRuntimeException("Requested quota is below live object usage"); }
                save(op, "Applying");
                if ("CREATE".equals(op.getOperationType())) {
                    BucketTO to = new BucketTO(buckets.findById(bucket.getId()));
                    if (bucket.isVersioning() && !store.setBucketVersioning(to)) { throw new CloudRuntimeException("Unable to set bucket versioning"); }
                    if (bucket.isEncryption() && !store.setBucketEncryption(to)) { throw new CloudRuntimeException("Unable to set bucket encryption"); }
                    if (bucket.getPolicy() != null) { store.setBucketPolicy(to, bucket.getPolicy()); }
                }
                if (!live.sameQuota(target)) { provider.writeBucketQuota(bucket, target); }
                BucketQuotaState verified = provider.readBucketQuota(bucket);
                sameIdentity(verified, op);
                if (!verified.sameQuota(target)) { throw new CloudRuntimeException("Provider quota write was not verified"); }
            }
            save(op, "Committing");
            commit(op, bucket);
        } catch (BucketQuotaException known) {
            throw known;
        } catch (Exception failure) {
            // DAO rollback does not roll back Java objects; reload holds and phase before compensation.
            op = operations.findById(op.getId());
            if (!op.isActive()) { return; }
            if ("Committing".equals(op.getState())) {
                // Cloud DB failure after provider success: compensate before releasing the reservation.
                try { save(op, "Compensating"); compensate(op, bucket, provider); }
                catch (Exception compensationFailure) { op = pending(op); }
            } else {
                try { save(op, "Compensating"); compensate(op, bucket, provider); }
                catch (Exception compensationFailure) { op = pending(op); }
            }
            throw new BucketQuotaException(op.getUuid(), op.isActive());
        }
    }

    private BucketQuotaOperationVO pending(BucketQuotaOperationVO op) {
        // A failed transaction may have mutated the DTO: preserve the durable reservation and active key.
        BucketQuotaOperationVO durable = operations.findById(op.getId());
        durable.setErrorCode("RECONCILIATION_REQUIRED");
        try { save(durable, "NeedsReconcile"); } catch (RuntimeException ignored) { /* prior durable phase drives restart */ }
        return durable;
    }

    private void compensate(BucketQuotaOperationVO op, BucketVO bucket, ObjectStoreQuotaProvider provider) {
        BucketQuotaState current = provider.readBucketQuota(bucket);
        sameIdentity(current, op);
        if ("DELETE".equals(op.getOperationType())) {
            if (!current.exists()) { save(op, "Committing"); commit(op, bucket); return; }
        } else if ("CREATE".equals(op.getOperationType())) {
            if (current.exists()) {
                if (!op.getExternalCreated() || op.getExternalId() == null) { throw new CloudRuntimeException("Ambiguous bucket creation; data preserved"); }
                provider.deleteEmptyBucket(bucket);
                if (provider.readBucketQuota(bucket).exists()) { throw new CloudRuntimeException("Create compensation was not verified"); }
            }
        } else {
            BucketQuotaState old = previous(op);
            if (!current.exists() || old.getEffectiveLimitBytes() >= 0 && old.getEffectiveLimitBytes() < current.getUsedBytes()) {
                throw new CloudRuntimeException("Previous quota cannot be safely restored");
            }
            if (!current.sameQuota(old)) { provider.writeBucketQuota(bucket, old); }
            if (!provider.readBucketQuota(bucket).sameQuota(old)) { throw new CloudRuntimeException("Quota compensation was not verified"); }
        }
        inTransaction(() -> {
            if ("CREATE".equals(op.getOperationType())) {
                limits.decrementResourceCount(bucket.getAccountId(), ResourceType.bucket);
                limits.decrementResourceCount(bucket.getAccountId(), ResourceType.object_storage, quotaBytes(op.getRequestedQuota()));
                if (!buckets.remove(bucket.getId())) { throw new CloudRuntimeException("Unable to roll back bucket metadata"); }
            } else if (op.getAccountHoldBytes() > 0) {
                limits.decrementResourceCount(bucket.getAccountId(), ResourceType.object_storage, op.getAccountHoldBytes());
            }
            finish(op, "Failed");
            return null;
        });
    }

    private void finish(BucketQuotaOperationVO op, String state) {
        op.setActiveBucketId(null); op.setReservedBytes(0); op.setAccountHoldBytes(0); op.setErrorCode(null);
        save(op, state);
    }

    private void commit(BucketQuotaOperationVO op, BucketVO bucket) {
        // Terminal marker, account counters, bucket and CAS allocation share one SQL transaction.
        inTransaction(() -> {
            BucketQuotaOperationVO current = operations.findById(op.getId());
            if (!current.isActive()) { return null; }
            ObjectStoreVO store = store(op.getObjectStoreId());
            long old = "CREATE".equals(op.getOperationType()) ? 0 : legacyBytes(op.getPreviousQuota());
            long next = "DELETE".equals(op.getOperationType()) ? 0 : quotaBytes(op.getRequestedQuota());
            long allocated = Objects.requireNonNullElse(store.getAllocatedSize(), 0L);
            long newAllocated = Math.addExact(allocated, next - old);
            if (newAllocated < 0 || !stores.compareAndSetAllocatedSize(store.getId(), allocated, newAllocated)) {
                throw new CloudRuntimeException("Object store allocation changed; reconciliation required");
            }
            if ("DELETE".equals(op.getOperationType())) {
                limits.decrementResourceCount(bucket.getAccountId(), ResourceType.bucket);
                if (old > 0) { limits.decrementResourceCount(bucket.getAccountId(), ResourceType.object_storage, old); }
                if (!buckets.remove(bucket.getId())) { throw new CloudRuntimeException("Unable to remove bucket metadata"); }
            } else {
                BucketVO update = buckets.createForUpdate(bucket.getId());
                update.setQuota(op.getRequestedQuota()); update.setState(Bucket.State.Created);
                if (!buckets.update(bucket.getId(), update)) { throw new CloudRuntimeException("Unable to update bucket quota metadata"); }
                if ("UPDATE".equals(op.getOperationType()) && next < old) {
                    limits.decrementResourceCount(bucket.getAccountId(), ResourceType.object_storage, old - next);
                }
            }
            finish(op, "Succeeded");
            return null;
        });
    }
}
