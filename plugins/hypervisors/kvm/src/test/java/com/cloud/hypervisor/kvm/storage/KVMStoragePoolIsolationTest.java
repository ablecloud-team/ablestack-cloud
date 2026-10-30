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
package com.cloud.hypervisor.kvm.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.hypervisor.kvm.resource.KVMHAMonitor;
import com.cloud.storage.Storage.StoragePoolType;
import com.cloud.utils.exception.CloudRuntimeException;

public class KVMStoragePoolIsolationTest {
    private KVMStoragePoolManager manager(StorageAdaptor adaptor) {
        KVMStoragePoolManager manager = mock(KVMStoragePoolManager.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(manager, "_storagePools", new ConcurrentHashMap<>());
        ReflectionTestUtils.setField(manager, "storagePoolLocks", new ConcurrentHashMap<>());
        ReflectionTestUtils.setField(manager, "_storageMapper", Map.of("libvirt", adaptor));
        ReflectionTestUtils.setField(manager, "_haMonitor", mock(KVMHAMonitor.class));
        return manager;
    }

    @Test
    public void hungGfsRefreshDoesNotBlockAnotherPoolsLookupRegistrationOrDeletion() throws Exception {
        StorageAdaptor adaptor = mock(StorageAdaptor.class);
        KVMStoragePoolManager manager = manager(adaptor);
        KVMStoragePool failedPool = mock(KVMStoragePool.class);
        KVMStoragePool healthyPool = mock(KVMStoragePool.class);
        when(healthyPool.getUuid()).thenReturn("healthy");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(adaptor.getStoragePool("failed", true)).thenAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(15, TimeUnit.SECONDS));
            return failedPool;
        });
        when(adaptor.getStoragePool("healthy", false)).thenReturn(healthyPool);
        when(adaptor.createStoragePool("healthy", "host", 1, "/path", null,
                StoragePoolType.RBD, Map.of(), true)).thenReturn(healthyPool);
        when(adaptor.deleteStoragePool("healthy")).thenReturn(true);
        when(adaptor.deleteStoragePool("healthy", Map.of())).thenReturn(true);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<KVMStoragePool> blocked = workers.submit(() -> manager.getStoragePool(StoragePoolType.SharedMountPoint, "failed", true, true));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertSame(healthyPool, workers.submit(() -> manager.getStoragePool(StoragePoolType.RBD, "healthy")).get(2, TimeUnit.SECONDS));
            assertSame(healthyPool, workers.submit(() -> manager.createStoragePool("healthy", "host", 1, "/path", null,
                    StoragePoolType.RBD, Map.of())).get(2, TimeUnit.SECONDS));
            assertTrue(workers.submit(() -> manager.deleteStoragePool(StoragePoolType.RBD, "healthy")).get(2, TimeUnit.SECONDS));
            assertTrue(workers.submit(() -> manager.deleteStoragePool(StoragePoolType.RBD, "healthy", Map.of())).get(2, TimeUnit.SECONDS));
            release.countDown();
            assertSame(failedPool, blocked.get(2, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    public void samePoolWaitersFailWithinABoundWithoutRecreatingOrDeletingTheBusyPool() throws Exception {
        StorageAdaptor adaptor = mock(StorageAdaptor.class);
        KVMStoragePoolManager manager = manager(adaptor);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        KVMStoragePool pool = mock(KVMStoragePool.class);
        when(adaptor.getStoragePool("failed", true)).thenAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(20, TimeUnit.SECONDS));
            return pool;
        });
        ExecutorService workers = Executors.newFixedThreadPool(5);
        try {
            Future<KVMStoragePool> blocked = workers.submit(() -> manager.getStoragePool(StoragePoolType.SharedMountPoint, "failed", true, true));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            Future<?> lookup = workers.submit(() -> manager.getStoragePool(StoragePoolType.SharedMountPoint, "failed"));
            Future<?> create = workers.submit(() -> manager.createStoragePool("failed", "host", 1, "/path", null, StoragePoolType.SharedMountPoint));
            Future<?> delete = workers.submit(() -> manager.deleteStoragePool(StoragePoolType.SharedMountPoint, "failed"));
            Future<?> detailedDelete = workers.submit(() -> manager.deleteStoragePool(StoragePoolType.SharedMountPoint, "failed", Map.of()));
            for (Future<?> result : new Future<?>[]{lookup, create, delete, detailedDelete}) {
                ExecutionException failure = assertThrows(ExecutionException.class, () -> result.get(8, TimeUnit.SECONDS));
                assertTrue(failure.getCause().getMessage(), failure.getCause().getMessage().startsWith("STORAGE_POOL_BUSY:"));
            }
            verify(adaptor, never()).getStoragePool("failed", false);
            verify(adaptor, never()).createStoragePool(anyString(), anyString(), anyInt(), anyString(), any(), any(), any(), anyBoolean());
            verify(adaptor, never()).deleteStoragePool("failed");
            verify(adaptor, never()).deleteStoragePool("failed", Map.of());
            release.countDown();
            assertSame(pool, blocked.get(2, TimeUnit.SECONDS));
            when(adaptor.getStoragePool("failed", false)).thenReturn(pool);
            assertSame(pool, manager.getStoragePool(StoragePoolType.SharedMountPoint, "failed"));
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    public void anIoFailureDoesNotLeaveThePoolLockedAfterStorageRecovers() throws Exception {
        StorageAdaptor adaptor = mock(StorageAdaptor.class);
        KVMStoragePoolManager manager = manager(adaptor);
        KVMStoragePool pool = mock(KVMStoragePool.class);
        when(adaptor.getStoragePool("recovering", true)).thenThrow(new CloudRuntimeException("Input/output error"));
        when(adaptor.getStoragePool("recovering", false)).thenReturn(pool);
        ExecutorService workers = Executors.newSingleThreadExecutor();
        try {
            ExecutionException failure = assertThrows(ExecutionException.class, () -> workers.submit(
                    () -> manager.getStoragePool(StoragePoolType.SharedMountPoint, "recovering", true, true)).get(2, TimeUnit.SECONDS));
            assertTrue(failure.getCause().getMessage().contains("Input/output error"));
            // A different thread must be able to resume the same pool after the failed call.
            assertSame(pool, manager.getStoragePool(StoragePoolType.SharedMountPoint, "recovering"));
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    public void missingRegisteredPoolStillUsesReentrantCreationFallback() {
        StorageAdaptor adaptor = mock(StorageAdaptor.class);
        KVMStoragePoolManager manager = manager(adaptor);
        KVMStoragePool pool = mock(KVMStoragePool.class);
        when(pool.getUuid()).thenReturn("registered");
        when(adaptor.createStoragePool("registered", "host", 1, "/path", null,
                StoragePoolType.RBD, Map.of(), true)).thenReturn(pool);
        manager.createStoragePool("registered", "host", 1, "/path", null, StoragePoolType.RBD, Map.of());
        when(adaptor.getStoragePool("registered", false)).thenThrow(new CloudRuntimeException("pool not found"));
        assertSame(pool, manager.getStoragePool(StoragePoolType.RBD, "registered"));
    }

    @Test
    public void interruptedPoolWaitPreservesTheInterruptAndDoesNotAccessStorage() {
        StorageAdaptor adaptor = mock(StorageAdaptor.class);
        KVMStoragePoolManager manager = manager(adaptor);
        try {
            Thread.currentThread().interrupt();
            CloudRuntimeException failure = assertThrows(CloudRuntimeException.class,
                    () -> manager.getStoragePool(StoragePoolType.RBD, "pool"));
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(failure.getMessage().startsWith("STORAGE_POOL_INTERRUPTED:"));
            verify(adaptor, never()).getStoragePool(anyString(), anyBoolean());
        } finally {
            assertEquals(true, Thread.interrupted());
        }
    }
}
