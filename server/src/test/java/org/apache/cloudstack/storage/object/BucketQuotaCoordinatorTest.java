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

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicLong;
import com.google.gson.Gson;
import com.cloud.configuration.Resource.ResourceType;
import com.cloud.storage.BucketVO;
import com.cloud.storage.dao.BucketDao;
import com.cloud.storage.DataStoreRole;
import com.cloud.user.*;
import com.cloud.user.AccountVO;
import com.cloud.utils.db.DbUtil;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.resourcelimit.ResourceLimitManagerImpl;
import org.apache.cloudstack.api.command.user.bucket.CreateBucketCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.subsystem.api.storage.*;
import org.apache.cloudstack.reservation.ReservationVO;
import org.apache.cloudstack.reservation.dao.ReservationDao;
import org.apache.cloudstack.storage.datastore.db.*;
import org.junit.*;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

public class BucketQuotaCoordinatorTest {
    private static final long G = 1073741824L;
    private final ObjectStoreDao stores = mock(ObjectStoreDao.class);
    private final BucketDao buckets = mock(BucketDao.class);
    private final BucketQuotaOperationDao ops = mock(BucketQuotaOperationDao.class);
    private final DataStoreManager dataStores = mock(DataStoreManager.class);
    private final AccountManager accounts = mock(AccountManager.class);
    private final ResourceLimitManagerImpl limits = mock(ResourceLimitManagerImpl.class);
    private final ReservationDao reservations = mock(ReservationDao.class);
    private final ObjectStoreEntity entity = mock(ObjectStoreEntity.class);
    private final DataStoreDriver driver = mock(DataStoreDriver.class, withSettings().extraInterfaces(ObjectStoreQuotaProvider.class));
    private final ObjectStoreQuotaProvider provider = (ObjectStoreQuotaProvider)driver;
    private final ObjectStoreVO store = mock(ObjectStoreVO.class);
    private final AccountVO owner = mock(AccountVO.class);
    private final Map<Long, BucketQuotaOperationVO> rows = new HashMap<>();
    private final Gson gson = new Gson();
    private BucketVO bucket;
    private BucketQuotaState live;
    private AtomicLong allocated, ledger;
    private BucketQuotaCoordinator service;
    private MockedStatic<DbUtil> locks;

    private BucketQuotaOperationVO copy(BucketQuotaOperationVO op) {
        return op == null ? null : gson.fromJson(gson.toJson(op), BucketQuotaOperationVO.class);
    }
    @Before public void setup() {
        bucket = new BucketVO(7, 1, 3, "qa-quota", 1, false, false, false, "Private");
        ReflectionTestUtils.setField(bucket, "id", 5L); bucket.setState(Bucket.State.Created);
        live = new BucketQuotaState(true, "external-id", true, G, -1, 0, false);
        allocated = new AtomicLong(G); ledger = new AtomicLong(G);
        when(stores.findById(3L)).thenReturn(store); when(store.getId()).thenReturn(3L);
        when(store.getUrl()).thenReturn("http://store"); when(store.getTotalSize()).thenReturn(4 * G);
        when(store.getAllocatedSize()).thenAnswer(i -> allocated.get());
        when(stores.compareAndSetAllocatedSize(eq(3L), anyLong(), anyLong())).thenAnswer(i -> allocated.compareAndSet(i.getArgument(1), i.getArgument(2)));
        when(buckets.findById(5L)).thenAnswer(i -> bucket);
        when(buckets.createForUpdate(5L)).thenAnswer(i -> new BucketVO("projection"));
        when(buckets.update(eq(5L), any(BucketVO.class))).thenAnswer(i -> {
            BucketVO patch = i.getArgument(1);
            if (patch.getQuota() != null) { bucket.setQuota(patch.getQuota()); bucket.setState(patch.getState()); }
            return true;
        });
        when(buckets.persist(any(BucketVO.class))).thenAnswer(i -> {
            bucket = i.getArgument(0); ReflectionTestUtils.setField(bucket, "id", 5L); return bucket;
        });
        when(buckets.remove(5L)).thenAnswer(i -> { bucket = null; return true; });
        when(ops.persist(any())).thenAnswer(i -> {
            BucketQuotaOperationVO op = i.getArgument(0); op.setId(1L); rows.put(1L, copy(op)); return op;
        });
        when(ops.update(anyLong(), any())).thenAnswer(i -> { rows.put(i.getArgument(0), copy(i.getArgument(1))); return true; });
        when(ops.findById(anyLong())).thenAnswer(i -> copy(rows.get(i.getArgument(0))));
        when(ops.findActiveByBucket(anyLong())).thenAnswer(i -> rows.values().stream().filter(BucketQuotaOperationVO::isActive).findFirst().map(this::copy).orElse(null));
        when(ops.listActive()).thenAnswer(i -> new ArrayList<>(rows.values()));
        when(ops.reservedBytesForStore(3L)).thenAnswer(i -> rows.values().stream().filter(BucketQuotaOperationVO::isActive).mapToLong(BucketQuotaOperationVO::getReservedBytes).sum());
        when(dataStores.getDataStore(3L, DataStoreRole.Object)).thenReturn(entity); when(entity.getDriver()).thenReturn(driver);
        when(provider.readBucketQuota(any())).thenAnswer(i -> live);
        doAnswer(i -> { live = i.getArgument(1); return null; }).when(provider).writeBucketQuota(any(), any());
        doAnswer(i -> { live = BucketQuotaState.missing(); return null; }).when(provider).deleteEmptyBucket(any());
        when(entity.createUser(7L)).thenReturn(true);
        when(entity.createBucket(any(), anyBoolean())).thenAnswer(i -> { live = new BucketQuotaState(true, "new-id", false, -1, -1, 0, false); return bucket; });
        when(entity.deleteBucket(any())).thenAnswer(i -> { live = BucketQuotaState.missing(); return true; });
        when(accounts.getActiveAccountById(7L)).thenReturn(owner); when(owner.getId()).thenReturn(7L);
        when(owner.getAccountId()).thenReturn(7L); when(owner.getDomainId()).thenReturn(1L);
        when(reservations.persist(any())).thenAnswer(i -> {
            ReservationVO row = i.getArgument(0); ReflectionTestUtils.setField(row, "id", 4L); return row;
        });
        doAnswer(i -> { ledger.addAndGet(i.getArgument(2)); return null; }).when(limits).incrementResourceCount(eq(7L), eq(ResourceType.object_storage), anyLong());
        doAnswer(i -> { ledger.addAndGet(-((Long)i.getArgument(2))); return null; }).when(limits).decrementBucketResourceCount(eq(7L), eq(ResourceType.object_storage), anyLong());
        locks = mockStatic(DbUtil.class);
        locks.when(() -> DbUtil.getGlobalLock(anyString(), anyInt())).thenReturn(true);
        locks.when(() -> DbUtil.releaseGlobalLock(anyString())).thenReturn(true);
        CallContext.register(mock(User.class), owner);
        service = new BucketQuotaCoordinator(stores, buckets, ops, dataStores, accounts, limits, reservations) {
            @Override protected <T> T inTransaction(Supplier<T> action) {
                long oldAllocated = allocated.get(), oldLedger = ledger.get();
                Integer oldQuota = bucket == null ? null : bucket.getQuota();
                Map<Long, BucketQuotaOperationVO> saved = new HashMap<>(); rows.forEach((id, op) -> saved.put(id, copy(op)));
                try { return action.get(); }
                catch (RuntimeException failure) {
                    allocated.set(oldAllocated); ledger.set(oldLedger);
                    if (bucket != null) bucket.setQuota(oldQuota);
                    rows.clear(); rows.putAll(saved); throw failure;
                }
            }
        };
    }
    @After public void close() { locks.close(); CallContext.unregister(); }
    private BucketQuotaOperationVO last() { return rows.get(1L); }
    private void failed(Runnable action, String state) {
        try { action.run(); fail("operation should fail"); } catch (BucketQuotaException expected) { assertFalse(expected.getMessage().contains("PRIVATE")); }
        assertEquals(state, last().getState());
    }
    @Test public void zeroRejected() { try { BucketQuotaCoordinator.quotaBytes(0); fail(); } catch (InvalidParameterValueException expected) { } }
    @Test public void negativeRejected() { try { BucketQuotaCoordinator.quotaBytes(-1); fail(); } catch (InvalidParameterValueException expected) { } }
    @Test public void nullRejected() { try { BucketQuotaCoordinator.quotaBytes(null); fail(); } catch (InvalidParameterValueException expected) { } }
    @Test public void maximumIsExactLongBytes() { assertEquals(2305843008139952128L, BucketQuotaCoordinator.quotaBytes(Integer.MAX_VALUE)); }
    @Test public void positiveUpdateCommitsHoldExactlyOnce() throws Exception {
        assertTrue(service.update(bucket, 2)); assertEquals(2 * G, allocated.get()); assertEquals(2 * G, ledger.get());
        assertEquals(0, last().getReservedBytes()); assertEquals(0, last().getAccountHoldBytes());
        service.recover(); assertEquals(2 * G, ledger.get());
    }
    @Test public void shrinkReleasesCommittedCapacity() throws Exception {
        bucket.setQuota(2); allocated.set(2 * G); ledger.set(2 * G); live = live.withLimitGiB(2);
        assertTrue(service.update(bucket, 1)); assertEquals(G, allocated.get()); assertEquals(G, ledger.get());
    }
    @Test public void liveUsageRejectsShrinkBeforeWrite() throws Exception {
        bucket.setQuota(2); live = new BucketQuotaState(true, "external-id", true, 2 * G, -1, G + 1, false);
        try { service.update(bucket, 1); fail(); } catch (InvalidParameterValueException expected) { }
        verify(provider, never()).writeBucketQuota(any(), any()); assertTrue(rows.isEmpty());
    }
    @Test public void storeCapacityRejectsIncreaseBeforeWrite() throws Exception {
        try { service.update(bucket, 5); fail(); } catch (InvalidParameterValueException expected) { }
        verify(provider, never()).writeBucketQuota(any(), any()); assertEquals(G, ledger.get());
    }
    @Test public void accountLimitRejectsBeforeProviderWrite() throws Exception {
        doThrow(new com.cloud.exception.ResourceAllocationException("limit", ResourceType.object_storage)).when(limits)
            .checkResourceLimitWithTag(owner, 1L, true, ResourceType.object_storage, null, G);
        try { service.update(bucket, 2); fail(); } catch (com.cloud.exception.ResourceAllocationException expected) { }
        verifyNoInteractions(provider); assertTrue(rows.isEmpty());
    }
    @Test public void externalQuotaMismatchDoesNotOverwriteAdministratorChange() throws Exception {
        live = live.withLimitGiB(3);
        try { service.update(bucket, 2); fail(); } catch (InvalidParameterValueException expected) { }
        verify(provider, never()).writeBucketQuota(any(), any());
    }
    @Test public void providerFailureRollsBackHeldCapacity() throws Exception {
        doThrow(new CloudRuntimeException("PRIVATE")).when(provider).writeBucketQuota(any(), any());
        failed(() -> { try { service.update(bucket, 2); } catch (com.cloud.exception.ResourceAllocationException e) { throw new RuntimeException(e); } }, "Failed");
        assertEquals(G, ledger.get()); assertEquals(G, allocated.get()); assertEquals(G, live.getMaxSizeBytes());
    }
    @Test public void dbFailureAfterProviderSuccessRestoresExactPreviousQuota() throws Exception {
        live = new BucketQuotaState(true, "external-id", true, G, 17, 0, true);
        when(stores.compareAndSetAllocatedSize(anyLong(), anyLong(), anyLong())).thenReturn(false);
        failed(() -> { try { service.update(bucket, 2); } catch (com.cloud.exception.ResourceAllocationException e) { throw new RuntimeException(e); } }, "Failed");
        assertEquals(G, live.getMaxSizeBytes()); assertEquals(17, live.getMaxObjects()); assertTrue(live.isCheckOnRaw());
        assertEquals(G, ledger.get()); assertEquals(Integer.valueOf(1), bucket.getQuota());
    }
    @Test public void rollbackFailureKeepsReservationAndExplicitRetryFinishesOnce() throws Exception {
        when(stores.compareAndSetAllocatedSize(anyLong(), anyLong(), anyLong())).thenReturn(false);
        doAnswer(i -> { BucketQuotaState target = i.getArgument(1); if (target.getMaxSizeBytes() == G) throw new CloudRuntimeException("PRIVATE"); live = target; return null; })
            .when(provider).writeBucketQuota(any(), any());
        failed(() -> { try { service.update(bucket, 2); } catch (com.cloud.exception.ResourceAllocationException e) { throw new RuntimeException(e); } }, "NeedsReconcile");
        assertEquals(G, last().getReservedBytes()); assertEquals(2 * G, ledger.get());
        when(stores.compareAndSetAllocatedSize(eq(3L), anyLong(), anyLong())).thenAnswer(i -> allocated.compareAndSet(i.getArgument(1), i.getArgument(2)));
        assertTrue(service.reconcile(bucket, last().getUuid())); assertEquals(2 * G, allocated.get()); assertEquals(2 * G, ledger.get());
        service.recover(); assertEquals(2 * G, ledger.get());
    }
    @Test public void secondOperationIsBlockedWhileRecoveryPending() throws Exception {
        BucketQuotaOperationVO op = new BucketQuotaOperationVO(); op.setBucketId(5L); op.setActiveBucketId(5L); op.setState("NeedsReconcile"); rows.put(1L, op);
        try { service.update(bucket, 2); fail(); } catch (BucketQuotaException expected) { }
        verify(provider, never()).writeBucketQuota(any(), any());
    }
    @Test public void staleRecoveryOperationIdIsRejected() {
        try { service.reconcile(bucket, "old-id"); fail(); } catch (InvalidParameterValueException expected) { }
        verifyNoInteractions(provider);
    }
    @Test public void deleteCommitsCounterAndAllocationOnlyOnce() {
        assertTrue(service.delete(bucket)); assertNull(bucket); assertEquals(0, ledger.get()); assertEquals(0, allocated.get());
        service.recover(); assertEquals(0, ledger.get());
    }
    @Test public void createReservesBeforeExternalUserOrBucketCreation() throws Exception {
        live = BucketQuotaState.missing(); allocated.set(0); ledger.set(0);
        CreateBucketCmd cmd = mock(CreateBucketCmd.class);
        when(cmd.getEntityOwnerId()).thenReturn(7L); when(cmd.getObjectStoragePoolId()).thenReturn(3L);
        when(cmd.getBucketName()).thenReturn("new-bucket"); when(cmd.getQuota()).thenReturn(2);
        BucketVO result = service.allocate(cmd); assertEquals(2 * G, last().getReservedBytes());
        verify(entity, never()).createUser(anyLong()); verify(entity, never()).createBucket(any(), anyBoolean());
        service.create(result.getId()); assertEquals(2 * G, allocated.get()); assertEquals(2 * G, ledger.get()); assertEquals("Succeeded", last().getState());
    }
}
