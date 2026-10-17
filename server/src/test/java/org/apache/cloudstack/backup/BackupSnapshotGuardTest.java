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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import java.util.Collections;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.apache.cloudstack.backup.dao.BackupDao;
import org.apache.cloudstack.backup.dao.BackupScheduleDao;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.CheckVmBackupTrackingCommand;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
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
    @Mock private AgentManager agentManager;
    @Mock private VolumeDao volumeDao;
    @Mock private VolumeDataFactory volumeDataFactory;
    @InjectMocks private BackupSnapshotGuard guard;
    private VMInstanceVO vm;
    @Before public void setup() {
        vm = mock(VMInstanceVO.class);
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

    @Test public void stoppedVmSendsVolumesToLastHost() throws Exception {
        when(vm.getState()).thenReturn(VirtualMachine.State.Stopped);
        when(vm.getHostId()).thenReturn(null);
        when(vm.getLastHostId()).thenReturn(10L);
        when(vm.getInstanceName()).thenReturn("vm");
        VolumeObjectTO volume = mock(VolumeObjectTO.class);
        VolumeVO volumeVO = mock(VolumeVO.class);
        VolumeInfo volumeInfo = mock(VolumeInfo.class);
        when(volumeVO.getId()).thenReturn(100L);
        when(volumeDao.findByInstance(1L)).thenReturn(Collections.singletonList(volumeVO));
        when(volumeDataFactory.getVolume(100L)).thenReturn(volumeInfo);
        when(volumeInfo.getTO()).thenReturn(volume);
        when(agentManager.send(eq(10L), any(CheckVmBackupTrackingCommand.class))).thenReturn(new Answer(null, true, null));
        guard.checkRuntime(1L);
        ArgumentCaptor<CheckVmBackupTrackingCommand> command = ArgumentCaptor.forClass(CheckVmBackupTrackingCommand.class);
        verify(agentManager).send(eq(10L), command.capture());
        assertEquals("vm", command.getValue().getVmName());
        assertTrue(command.getValue().isStopped());
        assertEquals(Collections.singletonList(volume), command.getValue().getVolumes());
    }

    @Test public void runningVmUsesCurrentHostWithoutOfflineVolumes() throws Exception {
        when(vm.getState()).thenReturn(VirtualMachine.State.Running);
        when(vm.getHostId()).thenReturn(20L);
        when(agentManager.send(eq(20L), any(CheckVmBackupTrackingCommand.class))).thenReturn(new Answer(null, true, null));
        guard.checkRuntime(1L);
        ArgumentCaptor<CheckVmBackupTrackingCommand> command = ArgumentCaptor.forClass(CheckVmBackupTrackingCommand.class);
        verify(agentManager).send(eq(20L), command.capture());
        assertFalse(command.getValue().isStopped());
        assertNull(command.getValue().getVolumes());
        verify(volumeDao, never()).findByInstance(anyLong());
        verifyNoInteractions(volumeDataFactory);
    }

    @Test public void stoppedVmWithoutVolumesBlocksBeforeAgentInspection() throws Exception {
        when(vm.getState()).thenReturn(VirtualMachine.State.Stopped);
        when(vm.getHostId()).thenReturn(10L);
        when(volumeDao.findByInstance(1L)).thenReturn(Collections.emptyList());
        try {
            guard.checkRuntime(1L);
            org.junit.Assert.fail("Expected tracking inspection to fail without volumes");
        } catch (CloudRuntimeException e) {
            assertTrue(e.getMessage().startsWith("BACKUP_TRACKING_UNKNOWN:"));
        }
        verifyNoInteractions(agentManager);
    }

    @Test public void unavailableVolumeBlocksBeforeAgentInspection() throws Exception {
        when(vm.getState()).thenReturn(VirtualMachine.State.Stopped);
        when(vm.getHostId()).thenReturn(10L);
        VolumeVO volume = mock(VolumeVO.class);
        when(volume.getId()).thenReturn(100L);
        when(volumeDao.findByInstance(1L)).thenReturn(Collections.singletonList(volume));
        try {
            guard.checkRuntime(1L);
            org.junit.Assert.fail("Expected tracking inspection to fail for an unavailable volume");
        } catch (CloudRuntimeException e) {
            assertTrue(e.getMessage().startsWith("BACKUP_TRACKING_UNKNOWN:"));
        }
        verifyNoInteractions(agentManager);
    }

    @Test(expected = CloudRuntimeException.class) public void failedRuntimeInspectionStillBlocksSnapshot() throws Exception {
        when(vm.getState()).thenReturn(VirtualMachine.State.Running);
        when(vm.getHostId()).thenReturn(20L);
        when(agentManager.send(eq(20L), any(CheckVmBackupTrackingCommand.class)))
                .thenReturn(new Answer(null, false, "BACKUP_TRACKING_UNKNOWN: Cannot inspect disks"));
        guard.checkRuntime(1L);
    }
}
