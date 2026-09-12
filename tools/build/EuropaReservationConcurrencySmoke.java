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
import java.io.FileInputStream;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.cloud.configuration.Resource.ResourceType;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.resourcelimit.CheckedReservation;
import com.cloud.user.Account;
import com.cloud.user.AccountVO;
import com.cloud.user.ResourceLimitService;
import com.cloud.utils.db.DbProperties;
import com.cloud.utils.db.TransactionLegacy;
import org.apache.cloudstack.reservation.dao.ReservationDaoImpl;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

/** Real reservation DAO and MySQL global locks; the limit policy is a one-CPU fixture. */
public class EuropaReservationConcurrencySmoke {
    public static void main(String[] args) {
        try {
            Properties properties = new Properties();
            try (FileInputStream input = new FileInputStream(args[0])) {
                properties.load(input);
            }
            if (!properties.getProperty("db.cloud.host", "").startsWith("epic992-mysql-")) {
                throw new IllegalArgumentException("Only disposable S4 fixture hosts are allowed");
            }
            DbProperties.setDbProperties(properties);
            TransactionLegacy mainTransaction = TransactionLegacy.open("s4-fixture-main");
            ReservationDaoImpl dao = new ReservationDaoImpl();
            dao.configure("s4-fixture", Map.of());
            AccountVO account = new AccountVO("s4-fixture", 1L, null, Account.Type.NORMAL, "s4-fixture");
            ReflectionTestUtils.setField(account, "id", 2L);
            if (dao.getAccountReservation(2L, ResourceType.cpu, null) != 0) {
                throw new AssertionError("Fixture already has reservations");
            }
            ResourceLimitService limits = Mockito.mock(ResourceLimitService.class);
            Mockito.doAnswer(invocation -> {
                long requested = invocation.getArgument(5);
                if (dao.getAccountReservation(2L, ResourceType.cpu, null) + requested > 1) {
                    throw new ResourceAllocationException("fixture CPU limit", ResourceType.cpu);
                }
                return null;
            }).when(limits).checkResourceLimitWithTag(account, 1L, true, ResourceType.cpu, null, 1L);
            int workers = 8;
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch attempted = new CountDownLatch(workers);
            AtomicInteger accepted = new AtomicInteger();
            AtomicInteger rejected = new AtomicInteger();
            var executor = Executors.newFixedThreadPool(workers);
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < workers; i++) {
                futures.add(executor.submit(() -> {
                    CheckedReservation reservation = null;
                    try (TransactionLegacy transaction = TransactionLegacy.open("s4-fixture-worker")) {
                        start.await();
                        try {
                            reservation = new CheckedReservation(account, ResourceType.cpu, List.of(), 1L, dao, limits);
                            accepted.incrementAndGet();
                        } catch (ResourceAllocationException expected) {
                            rejected.incrementAndGet();
                        } finally {
                            attempted.countDown();
                        }
                        if (!attempted.await(45, TimeUnit.SECONDS)) {
                            throw new AssertionError("Concurrent reservation timed out");
                        }
                        if (reservation != null) {
                            reservation.close();
                            reservation = null;
                        }
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    } finally {
                        if (reservation != null) {
                            try (TransactionLegacy cleanup = TransactionLegacy.open("s4-fixture-cleanup")) {
                                reservation.close();
                            }
                        }
                    }
                }));
            }
            start.countDown();
            for (var future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
            executor.shutdown();
            mainTransaction.close();
            mainTransaction = TransactionLegacy.open("s4-fixture-result");
            if (accepted.get() != 1 || rejected.get() != 7 || dao.getAccountReservation(2L, ResourceType.cpu, null) != 0) {
                throw new AssertionError("Concurrent limit or reservation cleanup failed");
            }
            System.out.println("S4_RESERVATION_MYSQL_CONCURRENCY_PASS accepted=1 rejected=7 remaining=0");
            System.exit(0);
        } catch (Exception failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }
}
