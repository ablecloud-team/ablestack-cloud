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
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import java.util.Collections;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.apache.cloudstack.backup.dao.BackupDao;
import org.apache.cloudstack.backup.dao.BackupScheduleDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.snapshot.VMSnapshotVO;
import com.cloud.vm.snapshot.dao.VMSnapshotDao;
import com.cloud.utils.exception.CloudRuntimeException;
@RunWith(MockitoJUnitRunner.class)
public class BackupSnapshotGuardTest {
    @Mock private BackupDao backupDao;
    @Mock private BackupScheduleDao scheduleDao;
    @Mock private VMSnapshotDao snapshotDao;
    @Mock private VMInstanceDao vmDao;
    @InjectMocks private BackupSnapshotGuard guard;
    @Before public void setup() {
        VMInstanceVO vm = mock(VMInstanceVO.class);
        when(vm.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(vmDao.findById(1L)).thenReturn(vm);
    }
    @Test public void cleanVmIsAllowed() {
        assertNull(guard.snapshotReason(1L));
        assertNull(guard.backupReason(1L));
    }
    @Test public void anyBackupStateBlocksSnapshot() {
        when(backupDao.listByVmId(null, 1L)).thenReturn(Collections.singletonList(mock(Backup.class)));
        assertEquals("BACKUP_EXISTS", guard.snapshotReason(1L));
    }
    @Test public void scheduleWithoutBackupBlocksSnapshot() {
        when(scheduleDao.listByVM(1L)).thenReturn(Collections.singletonList(mock(BackupScheduleVO.class)));
        assertEquals("BACKUP_SCHEDULE_EXISTS", guard.snapshotReason(1L));
    }
    @Test(expected = CloudRuntimeException.class) public void snapshotBlocksBackupRegardlessOfType() {
        when(snapshotDao.findByVm(1L)).thenReturn(Collections.singletonList(mock(VMSnapshotVO.class)));
        guard.checkBackup(1L);
    }
    @Test(expected = CloudRuntimeException.class) public void backupBlocksCreateAndRestore() {
        when(backupDao.listByVmId(null, 1L)).thenReturn(Collections.singletonList(mock(Backup.class)));
        guard.checkSnapshot(1L);
    }
}
