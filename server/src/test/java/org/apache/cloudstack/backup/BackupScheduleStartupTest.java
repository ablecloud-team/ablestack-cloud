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
package org.apache.cloudstack.backup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import org.apache.cloudstack.backup.dao.BackupOfferingDao;
import org.apache.cloudstack.backup.dao.BackupScheduleDao;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.dao.VMInstanceDao;

@RunWith(Parameterized.class)
public class BackupScheduleStartupTest {
    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> providers() {
        return Arrays.asList(new Object[][] {
            {"ablestack-netbackup", true}, {"ablestack-veeam", true}, {"veeam", false}, {"ablestack-nas", false}
        });
    }

    private final boolean preserve;
    private final String provider;
    private final BackupManagerImpl manager = new BackupManagerImpl();
    private final BackupScheduleDao schedules = mock(BackupScheduleDao.class);
    private final BackupScheduleVO schedule = new BackupScheduleVO();
    private final Date startup = new Date(1795398300000L);

    public BackupScheduleStartupTest(String provider, boolean preserve) {
        this.provider = provider;
        this.preserve = preserve;
    }

    @Before public void setup() {
        ReflectionTestUtils.setField(manager, "backupScheduleDao", schedules);
        ReflectionTestUtils.setField(manager, "currentTimestamp", startup);
        VMInstanceDao vms = mock(VMInstanceDao.class);
        BackupOfferingDao offerings = mock(BackupOfferingDao.class);
        VMInstanceVO vm = mock(VMInstanceVO.class);
        BackupOfferingVO offering = mock(BackupOfferingVO.class);
        ReflectionTestUtils.setField(manager, "vmInstanceDao", vms);
        ReflectionTestUtils.setField(manager, "backupOfferingDao", offerings);
        ReflectionTestUtils.setField(schedule, "id", 10L);
        ReflectionTestUtils.setField(schedule, "vmId", 20L);
        schedule.setScheduleType((short) 0);
        schedule.setSchedule("30");
        schedule.setTimezone("UTC");
        schedule.setAsyncJobId(123L);
        when(schedules.listAll()).thenReturn(List.of(schedule));
        when(vms.findById(20L)).thenReturn(vm);
        when(vm.getBackupOfferingId()).thenReturn(30L);
        when(offerings.findById(30L)).thenReturn(offering);
        when(offering.getProvider()).thenReturn(provider);
    }

    private void initialize() {
        try (MockedStatic<Transaction> transactions = mockStatic(Transaction.class)) {
            transactions.when(() -> Transaction.execute(any(TransactionCallback.class)))
                    .thenAnswer(call -> ((TransactionCallback<?>) call.getArgument(0)).doInTransaction(null));
            manager.initializeBackupSchedulesOnStartup();
        }
    }

    @Test public void restartDoesNotConsumeAnUnacceptedProviderSchedule() {
        Date due = new Date(startup.getTime() - 60000);
        schedule.setScheduledTimestamp(due);
        initialize();
        if (preserve) {
            assertSame(due, schedule.getScheduledTimestamp());
            assertEquals(Long.valueOf(123), schedule.getAsyncJobId());
            verify(schedules, never()).update(anyLong(), any());
        } else {
            assertTrue(schedule.getScheduledTimestamp().after(startup));
            assertNull(schedule.getAsyncJobId());
            verify(schedules).update(eq(10L), eq(schedule));
        }
    }

    @Test public void restartPreservesAFutureProviderScheduleAsWell() {
        Date future = new Date(startup.getTime() + 86400000);
        schedule.setScheduledTimestamp(future);
        initialize();
        if (preserve) {
            assertSame(future, schedule.getScheduledTimestamp());
            verify(schedules, never()).update(anyLong(), any());
        } else {
            verify(schedules).update(eq(10L), eq(schedule));
        }
    }

    @Test public void aMissingTimestampStillReceivesAnInitialSchedule() {
        initialize();
        assertNotNull(schedule.getScheduledTimestamp());
        assertTrue(schedule.getScheduledTimestamp().after(startup));
        assertNull(schedule.getAsyncJobId());
        verify(schedules).update(eq(10L), eq(schedule));
    }
}
