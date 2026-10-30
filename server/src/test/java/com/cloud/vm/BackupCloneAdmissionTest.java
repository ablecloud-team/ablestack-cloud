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
package com.cloud.vm;

import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.apache.cloudstack.backup.AblestackBackupOperationGuard;
import org.apache.cloudstack.backup.dao.BackupDao;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.utils.exception.CloudRuntimeException;

public class BackupCloneAdmissionTest {
    @Test
    public void aForeignRestoreBlocksCloneBeforeReadingTheSourcesOwnBackupHistory() {
        UserVmManagerImpl manager = new UserVmManagerImpl();
        AblestackBackupOperationGuard guard = mock(AblestackBackupOperationGuard.class);
        BackupDao backups = mock(BackupDao.class);
        ReflectionTestUtils.setField(manager, "ablestackBackupOperationGuard", guard);
        ReflectionTestUtils.setField(manager, "backupDao", backups);
        doThrow(new CloudRuntimeException("BACKUP_RESTORE_BUSY")).when(guard).checkClone(1L);

        assertThrows(CloudRuntimeException.class, () -> manager.checkNoActiveBackupForClone(1L));

        verify(guard).checkClone(1L);
        verifyNoInteractions(backups);
    }
}
