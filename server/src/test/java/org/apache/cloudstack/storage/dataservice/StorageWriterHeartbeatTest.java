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

import org.junit.Test;
import org.junit.Assert;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao;

public class StorageWriterHeartbeatTest {
    @Test public void blockedGuestCallRenewsItsOriginalScopeAndStopsAtTheTerminalBoundary() {
        StorageServiceOperationVO operation = Mockito.mock(StorageServiceOperationVO.class);
        Mockito.when(operation.getId()).thenReturn(12L);Mockito.when(operation.getInstanceId()).thenReturn(7L);
        Mockito.when(operation.getUuid()).thenReturn("writer-uuid");
        StorageServiceOperationDao operations = Mockito.mock(StorageServiceOperationDao.class);
        Mockito.when(operations.touchHeartbeat(Mockito.anyLong(), Mockito.any(), Mockito.anyLong())).thenReturn(true);
        ScheduledExecutorService executor = Mockito.mock(ScheduledExecutorService.class);
        ScheduledFuture<?> future = Mockito.mock(ScheduledFuture.class);
        ArgumentCaptor<Runnable> runnable = ArgumentCaptor.forClass(Runnable.class);
        Mockito.doReturn(future).when(executor).scheduleWithFixedDelay(runnable.capture(), Mockito.eq(20L), Mockito.eq(20L), Mockito.eq(TimeUnit.SECONDS));
        try (StorageWriterHeartbeat heartbeat = new StorageWriterHeartbeat(operation, operations, executor, failure -> Assert.fail())) {
            runnable.getValue().run();runnable.getValue().run();
            Mockito.verify(operations, Mockito.times(2)).touchHeartbeat(12L, "writer-uuid", 7L);
            Mockito.verify(operations, Mockito.never()).update(Mockito.anyLong(), Mockito.any());
        }
        Mockito.verify(future).cancel(false);
    }
    @Test public void temporaryDatabaseFailureDoesNotStopFutureLeaseAttemptsOrRewriteOperationState() {
        StorageServiceOperationVO operation = Mockito.mock(StorageServiceOperationVO.class);
        StorageServiceOperationDao operations = Mockito.mock(StorageServiceOperationDao.class);
        Mockito.when(operations.touchHeartbeat(Mockito.anyLong(), Mockito.any(), Mockito.anyLong())).thenThrow(new IllegalStateException("offline")).thenReturn(true);
        ScheduledExecutorService executor = Mockito.mock(ScheduledExecutorService.class);
        ScheduledFuture<?> future = Mockito.mock(ScheduledFuture.class);
        ArgumentCaptor<Runnable> runnable = ArgumentCaptor.forClass(Runnable.class);
        Mockito.doReturn(future).when(executor).scheduleWithFixedDelay(runnable.capture(), Mockito.anyLong(), Mockito.anyLong(), Mockito.any());
        java.util.List<RuntimeException> failures = new java.util.ArrayList<>();
        try (StorageWriterHeartbeat heartbeat = new StorageWriterHeartbeat(operation, operations, executor, failures::add)) {
            runnable.getValue().run();
            Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,heartbeat::requireAvailable);
            runnable.getValue().run();heartbeat.requireAvailable();
            Assert.assertEquals(1, failures.size());
            Mockito.verify(operations, Mockito.times(2)).touchHeartbeat(0L, null, 0L);
            Mockito.verify(operation, Mockito.never()).setState(Mockito.any());
        }
    }
    @Test public void zeroRowHeartbeatNeverRenewsNativeLeaseOrAuthorizesEffect() {
        StorageServiceOperationVO operation = Mockito.mock(StorageServiceOperationVO.class);
        Mockito.when(operation.getId()).thenReturn(12L);Mockito.when(operation.getInstanceId()).thenReturn(7L);
        Mockito.when(operation.getUuid()).thenReturn("owned-operation");
        StorageServiceOperationDao operations = Mockito.mock(StorageServiceOperationDao.class);
        Mockito.when(operations.touchHeartbeat(12L, "owned-operation", 7L)).thenReturn(false);
        ScheduledExecutorService executor = Mockito.mock(ScheduledExecutorService.class);
        ScheduledFuture<?> future = Mockito.mock(ScheduledFuture.class);
        ArgumentCaptor<Runnable> tick = ArgumentCaptor.forClass(Runnable.class);
        Mockito.doReturn(future).when(executor).scheduleWithFixedDelay(tick.capture(), Mockito.eq(20L), Mockito.eq(20L), Mockito.eq(TimeUnit.SECONDS));
        java.util.List<RuntimeException> failures = new java.util.ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger nativeRenewals = new java.util.concurrent.atomic.AtomicInteger();
        try (StorageWriterHeartbeat heartbeat = new StorageWriterHeartbeat(operation, operations, executor, failures::add, nativeRenewals::incrementAndGet)) {
            tick.getValue().run();
            Assert.assertEquals(0, nativeRenewals.get());Assert.assertEquals(1, failures.size());
            Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class, heartbeat::requireAvailable);
            Mockito.verify(operations).touchHeartbeat(12L, "owned-operation", 7L);
            Mockito.verify(operations, Mockito.never()).update(Mockito.anyLong(), Mockito.any());
        }
    }

    @Test public void failedHeartbeatScopeMayRecoverOnlyAfterTheExactRowRenewalSucceeds() {
        StorageServiceOperationVO operation = Mockito.mock(StorageServiceOperationVO.class);
        Mockito.when(operation.getId()).thenReturn(12L);Mockito.when(operation.getInstanceId()).thenReturn(7L);
        Mockito.when(operation.getUuid()).thenReturn("owned-operation");
        StorageServiceOperationDao operations = Mockito.mock(StorageServiceOperationDao.class);
        Mockito.when(operations.touchHeartbeat(12L, "owned-operation", 7L)).thenReturn(false).thenReturn(true);
        ScheduledExecutorService executor = Mockito.mock(ScheduledExecutorService.class);
        ScheduledFuture<?> future = Mockito.mock(ScheduledFuture.class);
        ArgumentCaptor<Runnable> tick = ArgumentCaptor.forClass(Runnable.class);
        Mockito.doReturn(future).when(executor).scheduleWithFixedDelay(tick.capture(), Mockito.eq(20L), Mockito.eq(20L), Mockito.eq(TimeUnit.SECONDS));
        java.util.List<RuntimeException> failures = new java.util.ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger nativeRenewals = new java.util.concurrent.atomic.AtomicInteger();
        try (StorageWriterHeartbeat heartbeat = new StorageWriterHeartbeat(operation, operations, executor, failures::add, nativeRenewals::incrementAndGet)) {
            tick.getValue().run();Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class, heartbeat::requireAvailable);
            Assert.assertEquals(0, nativeRenewals.get());
            tick.getValue().run();heartbeat.requireAvailable();
            Assert.assertEquals(1, nativeRenewals.get());Assert.assertEquals(1, failures.size());
        }
    }

}
