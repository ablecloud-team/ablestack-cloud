// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.
package org.apache.cloudstack.backup;

import java.util.Arrays;
import java.util.Collections;

import com.cloud.utils.db.DbUtil;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.backup.dao.BackupDao;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ThirdPartyBackupRestoreLockTest {
    private static final String BACKUP_UUID = "47f20b21-8b7a-40a5-852a-ea536830b22d";

    private MockedStatic<DbUtil> mockMySqlLocks() {
        MockedStatic<DbUtil> database = Mockito.mockStatic(DbUtil.class);
        database.when(() -> DbUtil.getGlobalLock(anyString(), anyInt()))
                .thenAnswer(invocation -> ((String) invocation.getArgument(0)).length() <= 64);
        database.when(() -> DbUtil.releaseGlobalLock(anyString())).thenReturn(true);
        return database;
    }

    private Backup backup() {
        Backup backup = mock(Backup.class);
        when(backup.getId()).thenReturn(1L);
        when(backup.getVmId()).thenReturn(220L);
        when(backup.getUuid()).thenReturn(BACKUP_UUID);
        return backup;
    }

    @Test
    public void realBackupUuidCanAcquirePreparationLockOnMySql() {
        ThirdPartyBackupVolumeServiceImpl service = new ThirdPartyBackupVolumeServiceImpl();
        ThirdPartyBackupStagingService staging = mock(ThirdPartyBackupStagingService.class);
        ReflectionTestUtils.setField(service, "stagingService", staging);
        RuntimeException reachedPreparation = new RuntimeException("Preparation lock acquired");
        doThrow(reachedPreparation).when(staging).requireEnabled();

        try (MockedStatic<DbUtil> database = mockMySqlLocks()) {
            assertSame(reachedPreparation, assertThrows(RuntimeException.class,
                    () -> service.prepareRestore(backup(), null, Collections.emptyList(), Collections.emptyList(), "i-2-220-VM", 300)));
            String key = ThirdPartyBackupRestore.prepareLockName(BACKUP_UUID);
            database.verify(() -> DbUtil.getGlobalLock(key, 5));
            database.verify(() -> DbUtil.releaseGlobalLock(key));
            database.verify(() -> DbUtil.releaseGlobalLock("backup.volume.lifecycle.220"));
        }
    }

    @Test
    public void preparationStillRejectsAContendingRequest() {
        ThirdPartyBackupVolumeServiceImpl service = new ThirdPartyBackupVolumeServiceImpl();
        ThirdPartyBackupStagingService staging = mock(ThirdPartyBackupStagingService.class);
        ReflectionTestUtils.setField(service, "stagingService", staging);
        String key = ThirdPartyBackupRestore.prepareLockName(BACKUP_UUID);

        try (MockedStatic<DbUtil> database = mockMySqlLocks()) {
            database.when(() -> DbUtil.getGlobalLock(key, 5)).thenReturn(false);
            CloudRuntimeException failure = assertThrows(CloudRuntimeException.class,
                    () -> service.prepareRestore(backup(), null, Collections.emptyList(), Collections.emptyList(), "i-2-220-VM", 300));
            assertEquals("Another restore is being prepared for this backup", failure.getMessage());
            verify(staging, never()).requireEnabled();
            database.verify(() -> DbUtil.releaseGlobalLock(key), never());
            database.verify(() -> DbUtil.releaseGlobalLock("backup.volume.lifecycle.220"));
        }
    }

    @Test
    public void longProviderJobIdsShareOneLockAcrossDispatchReconciliationAndManagement() {
        for (String provider : Arrays.asList("ablestack-commvault", "ablestack-netbackup", "ablestack-veeam")) {
            String jobId = AblestackBackupFrameworkUtils.createRestoreJobId(provider, BACKUP_UUID, "i-2-220-VM", null)
                    + "-f3f8907e-3dab-4e65-893b-8a81d3fdb3a1";
            ThirdPartyBackupRestore.Plan plan = new ThirdPartyBackupRestore.Plan();
            plan.jobId = jobId;
            ThirdPartyBackupVolumeServiceImpl service = new ThirdPartyBackupVolumeServiceImpl();
            ReflectionTestUtils.setField(service, "backupDao", mock(BackupDao.class));
            Backup backup = backup();
            String key = ThirdPartyBackupRestore.lockName(jobId);

            try (MockedStatic<DbUtil> database = mockMySqlLocks()) {
                assertEquals("Selected backup no longer exists", assertThrows(CloudRuntimeException.class,
                        () -> service.markRestoreDispatch(backup, plan)).getMessage());
                ReflectionTestUtils.invokeMethod(service, "reconcileRestore", 1L, null, plan, null);
                assertEquals("Selected backup no longer exists", assertThrows(CloudRuntimeException.class,
                        () -> service.manage(backup, null, "RESTORE", jobId, "RECHECK", null, null, null, null, null)).getMessage());
                database.verify(() -> DbUtil.getGlobalLock(key, 5), times(2));
                database.verify(() -> DbUtil.getGlobalLock(key, 1));
                database.verify(() -> DbUtil.releaseGlobalLock(key), times(3));
            }
        }
    }

    @Test
    public void lockNamesPreserveValidNamesAndBoundLongIdentifiers() {
        String prefix = "backup.volume.restore.";
        for (int length : new int[] {63, 64, 65, 1024}) {
            String jobId = "x".repeat(length - prefix.length());
            String key = ThirdPartyBackupRestore.lockName(jobId);
            assertTrue(key.length() <= 64);
            assertEquals(key, ThirdPartyBackupRestore.lockName(jobId));
            if (length <= 64) { assertEquals(prefix + jobId, key); }
        }
        assertTrue(ThirdPartyBackupRestore.prepareLockName(BACKUP_UUID).length() <= 64);
        assertNotEquals(ThirdPartyBackupRestore.prepareLockName(BACKUP_UUID),
                ThirdPartyBackupRestore.prepareLockName("47f20b21-8b7a-40a5-852a-ea536830b22e"));
        String longJobId = "restore-" + BACKUP_UUID + "-" + "x".repeat(256);
        assertNotEquals(ThirdPartyBackupRestore.lockName(longJobId + "-attempt-1"),
                ThirdPartyBackupRestore.lockName(longJobId + "-attempt-2"));
        assertNotEquals(ThirdPartyBackupRestore.prepareLockName(longJobId), ThirdPartyBackupRestore.lockName(longJobId));
    }
}
