// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.user;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.exception.CloudRuntimeException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.junit.Test;
import org.mockito.MockedStatic;

public class CniConfigurationReferencesTest {
    @Test
    public void optionalCniDoesNotAcquireLock() {
        try (MockedStatic<GlobalLock> mocks = mockStatic(GlobalLock.class)) {
            assertEquals("plain", CniConfigurationReferences.withLock(null, () -> "plain"));
            mocks.verifyNoInteractions();
        }
    }

    @Test
    public void timeoutCannotExecuteActionOrUnlockAndAlwaysReleasesReference() {
        try (MockedStatic<GlobalLock> mocks = mockStatic(GlobalLock.class)) {
            GlobalLock lock = mock(GlobalLock.class); mocks.when(() -> GlobalLock.getInternLock("CniConfiguration.Reference.17")).thenReturn(lock);
            AtomicBoolean called = new AtomicBoolean();
            assertThrows(CloudRuntimeException.class, () -> CniConfigurationReferences.withLock(17L, () -> called.getAndSet(true)));
            assertFalse(called.get()); verify(lock, never()).unlock(); verify(lock).releaseRef();
        }
    }

    @Test
    public void callbackFailureAlwaysUnlocksAndReleases() {
        try (MockedStatic<GlobalLock> mocks = mockStatic(GlobalLock.class)) {
            GlobalLock lock = mock(GlobalLock.class); mocks.when(() -> GlobalLock.getInternLock("CniConfiguration.Reference.17")).thenReturn(lock);
            when(lock.lock(10)).thenReturn(true);
            assertThrows(IllegalStateException.class, () -> CniConfigurationReferences.withLock(17L, () -> { throw new IllegalStateException(); }));
            verify(lock).unlock(); verify(lock).releaseRef();
        }
    }

    private boolean serialized(ReentrantLock serial, Supplier<Boolean> action) throws Exception {
        try (MockedStatic<GlobalLock> mocks = mockStatic(GlobalLock.class)) {
            GlobalLock lock = mock(GlobalLock.class); mocks.when(() -> GlobalLock.getInternLock("CniConfiguration.Reference.17")).thenReturn(lock);
            when(lock.lock(10)).thenAnswer(inv -> serial.tryLock(3, TimeUnit.SECONDS));
            doAnswer(inv -> { serial.unlock(); return null; }).when(lock).unlock();
            return CniConfigurationReferences.withLock(17L, action);
        }
    }

    @Test
    public void bothPublicationDeletionOrdersCannotLeaveAnActiveDanglingReference() throws Exception {
        for (boolean createFirst : new boolean[] {true, false}) {
            ReentrantLock serial = new ReentrantLock(); AtomicBoolean exists = new AtomicBoolean(true); AtomicBoolean referenced = new AtomicBoolean();
            CountDownLatch inside = new CountDownLatch(1); CountDownLatch release = new CountDownLatch(1);
            Supplier<Boolean> create = () -> { if (!exists.get()) return false; referenced.set(true); return true; };
            Supplier<Boolean> delete = () -> { if (referenced.get()) return false; exists.set(false); return true; };
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<Boolean> first = executor.submit(() -> serialized(serial, () -> {
                    inside.countDown(); try { if (!release.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); } catch (InterruptedException e) { throw new RuntimeException(e); }
                    return (createFirst ? create : delete).get();
                }));
                assertTrue(inside.await(3, TimeUnit.SECONDS));
                Future<Boolean> second = executor.submit(() -> serialized(serial, createFirst ? delete : create));
                release.countDown(); assertTrue(first.get(5, TimeUnit.SECONDS)); assertFalse(second.get(5, TimeUnit.SECONDS));
                assertFalse(referenced.get() && !exists.get());
            } finally { release.countDown(); executor.shutdownNow(); }
        }
    }
}
