//
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
//
package com.cloud.resourcelimit;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.cloudstack.reservation.ReservationVO;
import org.apache.cloudstack.reservation.dao.ReservationDao;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.configuration.Resource.ResourceType;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.user.Account;
import com.cloud.user.ResourceLimitService;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.exception.CloudRuntimeException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class CheckedReservationFailureTest {
    private final Account account = mock(Account.class);
    private final ReservationDao dao = mock(ReservationDao.class);
    private final ResourceLimitService limits = mock(ResourceLimitService.class);
    private final GlobalLock lock = mock(GlobalLock.class);
    private final Map<Long, ReservationVO> rows = new HashMap<>();
    private final AtomicLong nextId = new AtomicLong();

    private MockedStatic<GlobalLock> prepare() {
        when(account.getAccountId()).thenReturn(2L);
        when(account.getDomainId()).thenReturn(1L);
        when(lock.lock(anyInt())).thenReturn(true);
        when(dao.persist(any(ReservationVO.class))).thenAnswer(invocation -> {
            ReservationVO row = invocation.getArgument(0);
            long id = nextId.incrementAndGet();
            ReflectionTestUtils.setField(row, "id", id);
            rows.put(id, row);
            return row;
        });
        when(dao.remove(anyLong())).thenAnswer(invocation -> rows.remove(invocation.getArgument(0)) != null);
        MockedStatic<GlobalLock> factory = Mockito.mockStatic(GlobalLock.class);
        factory.when(() -> GlobalLock.getInternLock(anyString())).thenReturn(lock);
        return factory;
    }

    @Test
    public void limitFailureRemovesEarlierGlobalAndTagReservations() throws Exception {
        try (MockedStatic<GlobalLock> ignored = prepare()) {
            doThrow(new ResourceAllocationException("tag full", ResourceType.cpu)).when(limits)
                    .checkResourceLimitWithTag(account, 1L, true, ResourceType.cpu, "full", 2L);
            try {
                new CheckedReservation(account, ResourceType.cpu, List.of("ok", "full"), 2L, dao, limits);
                fail("Expected a resource limit failure");
            } catch (ResourceAllocationException expected) {
                assertEquals("tag full", expected.getMessage());
            }
            assertEquals(2L, nextId.get());
            assertTrue(rows.isEmpty());
            verify(lock).unlock();
            verify(lock).releaseRef();
        }
    }

    @Test
    public void persistenceFailureRemovesEarlierReservations() throws Exception {
        try (MockedStatic<GlobalLock> ignored = prepare()) {
            when(dao.persist(any(ReservationVO.class))).thenAnswer(invocation -> {
                if (!rows.isEmpty()) {
                    throw new CloudRuntimeException("fixture persistence failure");
                }
                ReservationVO row = invocation.getArgument(0);
                ReflectionTestUtils.setField(row, "id", 1L);
                rows.put(1L, row);
                return row;
            });
            try {
                new CheckedReservation(account, ResourceType.cpu, List.of("tag"), 2L, dao, limits);
                fail("Expected a database failure");
            } catch (CloudRuntimeException expected) {
                assertEquals("fixture persistence failure", expected.getMessage());
            }
            assertTrue(rows.isEmpty());
            verify(dao).remove(1L);
            verify(lock).unlock();
            verify(lock).releaseRef();
        }
    }

    @Test
    public void failedLockReleasesReferenceWithoutUnlockingAnotherOwner() throws Exception {
        try (MockedStatic<GlobalLock> ignored = prepare()) {
            when(lock.lock(anyInt())).thenReturn(false);
            try {
                new CheckedReservation(account, ResourceType.cpu, 2L, dao, limits);
                fail("Expected lock contention");
            } catch (ResourceAllocationException expected) {
                assertTrue(rows.isEmpty());
            }
            verify(lock, never()).unlock();
            verify(lock).releaseRef();
        }
    }

    @Test
    public void offeringChangeReservesDeltaAndNewTagsThenReleasesEverything() throws Exception {
        try (MockedStatic<GlobalLock> ignored = prepare();
             CheckedReservation reservation = new CheckedReservation(account, ResourceType.cpu, null,
                     List.of("same", "new"), List.of("same", "old"), 4L, 2L, dao, limits)) {
            Map<String, Long> totals = new HashMap<>();
            for (ReservationVO row : rows.values()) {
                totals.merge(row.getTag(), row.getReservedAmount(), Long::sum);
            }
            assertEquals(Long.valueOf(2), totals.get(null));
            assertEquals(Long.valueOf(2), totals.get("same"));
            assertEquals(Long.valueOf(-2), totals.get("old"));
            assertEquals(Long.valueOf(4), totals.get("new"));
        }
        assertTrue(rows.isEmpty());
    }
    @Test
    public void cleanupAttemptsEveryRowAndCanRetryAfterDatabaseFailure() throws Exception {
        try (MockedStatic<GlobalLock> ignored = prepare()) {
            CheckedReservation reservation = new CheckedReservation(account, ResourceType.cpu, List.of("tag"), 2L, dao, limits);
            doThrow(new CloudRuntimeException("temporary DB failure"))
                    .doAnswer(invocation -> rows.remove(1L) != null).when(dao).remove(1L);
            try {
                reservation.close();
                fail("Expected cleanup failure");
            } catch (CloudRuntimeException expected) {
                assertEquals(1, rows.size());
                verify(dao).remove(2L);
            }
            reservation.close();
            assertTrue(rows.isEmpty());
        }
    }

    @Test
    public void cleanupContinuesAcrossResourceTypesWhenOneCloseFails() {
        org.apache.cloudstack.resourcelimit.Reserver cpu = mock(org.apache.cloudstack.resourcelimit.Reserver.class);
        org.apache.cloudstack.resourcelimit.Reserver memory = mock(org.apache.cloudstack.resourcelimit.Reserver.class);
        doThrow(new CloudRuntimeException("CPU cleanup failed")).when(cpu).close();
        try {
            ReservationHelper.closeAll(List.of(cpu, memory));
            fail("Expected cleanup failure");
        } catch (CloudRuntimeException expected) {
            verify(memory).close();
        }
    }

}
