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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.exception.CloudRuntimeException;
import org.junit.Test;
import org.mockito.MockedStatic;

public class BackupPreparationLifecycleTest {
    @Test public void onlyInitialPreparationHasARetryableBusyResult() {
        GlobalLock lock = mock(GlobalLock.class);
        try (MockedStatic<GlobalLock> locks = mockStatic(GlobalLock.class)) {
            locks.when(() -> GlobalLock.getInternLock("backup.volume.lifecycle.42")).thenReturn(lock);
            ThirdPartyBackupVolumeServiceImpl service = new ThirdPartyBackupVolumeServiceImpl();
            BackupPreparation.Busy busy = assertThrows(BackupPreparation.Busy.class, () -> service.acquireBackupPreparation(42));
            assertEquals(42, busy.getVmId());
            CloudRuntimeException standard = assertThrows(CloudRuntimeException.class, () -> service.acquireLifecycle(42));
            assertFalse(standard instanceof BackupPreparation.Busy);
            verify(lock, times(2)).releaseRef();
            verify(lock, never()).unlock();
        }
    }
    @Test public void successfulPreparationReleasesTheExistingLifecycleLock() {
        GlobalLock lock = mock(GlobalLock.class);
        when(lock.lock(5)).thenReturn(true);
        try (MockedStatic<GlobalLock> locks = mockStatic(GlobalLock.class)) {
            locks.when(() -> GlobalLock.getInternLock("backup.volume.lifecycle.42")).thenReturn(lock);
            try (ThirdPartyBackupVolumeService.Lease lease = new ThirdPartyBackupVolumeServiceImpl().acquireBackupPreparation(42)) {
                verify(lock, never()).unlock();
            }
            verify(lock).unlock();
            verify(lock).releaseRef();
        }
    }
}
