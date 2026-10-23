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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import org.apache.cloudstack.backup.dao.BackupDetailsDao;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolDetailVO;
import org.apache.cloudstack.storage.datastore.db.StoragePoolDetailsDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.MockitoJUnitRunner;
import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.Storage;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.VMInstanceDao;
import com.google.gson.Gson;

@RunWith(MockitoJUnitRunner.Silent.class)
public class AblestackSharedRawBackupServiceTest {
    @Mock VolumeDao volumes; @Mock VMInstanceDao vms; @Mock PrimaryDataStoreDao pools;
    @Mock StoragePoolDetailsDao poolDetails; @Mock DiskOfferingDao offerings;
    @Mock HostDao hosts; @Mock AgentManager agents; @Mock BackupDetailsDao backupDetails;
    @InjectMocks AblestackSharedRawBackupServiceImpl service;
    VolumeVO disk; StoragePoolVO pool; VMInstanceVO vm; HostVO worker;
    MockedStatic<GlobalLock> locks; MockedStatic<Transaction> tx;
    @Before public void setup() throws Exception {
        GlobalLock lock = mock(GlobalLock.class); when(lock.lock(anyInt())).thenReturn(true);
        locks = mockStatic(GlobalLock.class); locks.when(() -> GlobalLock.getInternLock(anyString())).thenReturn(lock);
        tx = mockStatic(Transaction.class); tx.when(() -> Transaction.execute(any(TransactionCallback.class)))
                .thenAnswer(call -> ((TransactionCallback<?>) call.getArgument(0)).doInTransaction(null));
        disk = mock(VolumeVO.class); when(disk.getId()).thenReturn(1L); when(disk.getPoolId()).thenReturn(10L);
        when(disk.getUuid()).thenReturn("disk"); when(disk.getPath()).thenReturn("shared.raw");
        when(disk.getSize()).thenReturn(4096L); when(disk.getFormat()).thenReturn(Storage.ImageFormat.RAW);
        when(disk.getInstanceId()).thenReturn(20L); when(disk.getDiskOfferingId()).thenReturn(30L);
        pool = mock(StoragePoolVO.class); when(pool.getId()).thenReturn(10L); when(pool.getUuid()).thenReturn("pool");
        when(pool.getPoolType()).thenReturn(Storage.StoragePoolType.SharedMountPoint); when(pool.getClusterId()).thenReturn(40L);
        when(pools.findById(10L)).thenReturn(pool);
        DiskOfferingVO offering = mock(DiskOfferingVO.class); when(offering.getShareable()).thenReturn(true);
        when(offerings.findByIdIncludingRemoved(30L)).thenReturn(offering);
        vm = mock(VMInstanceVO.class); when(vm.getState()).thenReturn(VirtualMachine.State.Stopped);
        when(vm.getInstanceName()).thenReturn("vm"); when(vms.findById(20L)).thenReturn(vm);
        when(volumes.findByInstance(20L)).thenReturn(List.of(disk)); when(volumes.findById(1L)).thenReturn(disk);
        when(volumes.findBySharedVolume(10L, "shared.raw")).thenReturn(List.of(disk));
        worker = mock(HostVO.class); when(worker.getId()).thenReturn(50L); when(worker.getClusterId()).thenReturn(40L);
        when(worker.getStatus()).thenReturn(Status.Up); when(hosts.findByClusterId(40L, Host.Type.Routing)).thenReturn(List.of(worker));
        when(agents.send(anyLong(), any(AblestackSharedRawBackupCommand.class))).thenAnswer(call -> {
            AblestackSharedRawBackupCommand command = call.getArgument(1);
            return new Answer(command, true, new Gson().toJson(command.getPlan()));
        });
    }
    @After public void close() { tx.close(); locks.close(); }
    Backup backup(String type) {
        Backup backup = mock(Backup.class); when(backup.getId()).thenReturn(60L);
        when(backup.getType()).thenReturn(type); return backup;
    }
    @Test public void stoppedRawRequiresFullAndAllHostsConfirmBeforeDurableOwnership() throws Exception {
        var plan = service.protect(backup("FULL"), worker, "job", "BACKUP", List.of(disk));
        assertEquals("raw", plan.disks.get(0).format); assertTrue(plan.disks.get(0).shareable);
        verify(agents).send(eq(50L), any(AblestackSharedRawBackupCommand.class));
        verify(backupDetails).addDetail(eq(60L), eq(AblestackSharedRawBackup.RETENTION_PENDING), eq("true"), eq(false));
    }
    @Test public void incrementalRequestIsRejectedBeforeHostOrDbMutation() {
        assertThrows(CloudRuntimeException.class, () -> service.protect(backup("INCREMENTAL"), worker, "job", "BACKUP", List.of(disk)));
        verifyNoInteractions(agents, backupDetails, poolDetails);
    }
    @Test public void runningTargetCannotBeBackedUp() {
        when(vm.getState()).thenReturn(VirtualMachine.State.Running);
        assertThrows(CloudRuntimeException.class, () -> service.checkSources(20L)); verifyNoInteractions(agents);
    }
    @Test public void actualAliasIsInspectedEvenWithStaleNonshareableOffering() {
        when(offerings.findByIdIncludingRemoved(30L).getShareable()).thenReturn(false);
        VolumeVO peer = mock(VolumeVO.class); when(peer.getFormat()).thenReturn(Storage.ImageFormat.RAW);
        when(peer.getSize()).thenReturn(4096L); when(peer.getInstanceId()).thenReturn(21L);
        when(volumes.findBySharedVolume(10L, "shared.raw")).thenReturn(List.of(disk, peer));
        VMInstanceVO running = mock(VMInstanceVO.class); when(running.getState()).thenReturn(VirtualMachine.State.Running);
        when(vms.findById(21L)).thenReturn(running);
        assertThrows(CloudRuntimeException.class, () -> service.checkSources(20L));
    }
    @Test public void unknownPeerFailsClosed() {
        when(vms.findById(20L)).thenReturn(null);
        assertThrows(CloudRuntimeException.class, () -> service.checkSources(20L));
    }
    @Test public void incompleteAliasInventoryFailsClosed() {
        when(volumes.findBySharedVolume(10L, "shared.raw")).thenReturn(List.of());
        assertThrows(CloudRuntimeException.class, () -> service.checkSources(20L));
    }
    @Test public void anUnreachableClusterHostBlocksBackup() {
        when(worker.getStatus()).thenReturn(Status.Disconnected);
        assertThrows(CloudRuntimeException.class, () -> service.protect(backup("FULL"), worker, "job", "BACKUP", List.of(disk)));
        verify(backupDetails, never()).addDetail(anyLong(), anyString(), anyString(), anyBoolean());
    }
    @Test public void olderAgentCannotIgnoreGuardProtocol() throws Exception {
        when(agents.send(anyLong(), any(AblestackSharedRawBackupCommand.class))).thenReturn(new Answer(null, true, ""));
        assertThrows(CloudRuntimeException.class, () -> service.protect(backup("FULL"), worker, "job", "BACKUP", List.of(disk)));
        verify(backupDetails).addDetail(eq(60L), eq(AblestackSharedRawBackup.BACKUP_KEY + ".prepareFailed"), anyString(), eq(false));
    }
    @Test public void originalSharedRestoreRequiresEveryPeerStopped() {
        when(vm.getState()).thenReturn(VirtualMachine.State.Running);
        assertThrows(CloudRuntimeException.class, () -> service.protect(backup("FULL"), worker, "restore", "RESTORE", List.of(disk)));
    }
    @Test public void targetRestoringStateIsAllowedOnlyForRestore() {
        when(vm.getState()).thenReturn(VirtualMachine.State.Restoring);
        assertNotNull(service.protect(backup("FULL"), worker, "restore", "RESTORE", List.of(disk)));
        assertThrows(CloudRuntimeException.class, () -> service.checkSources(20L));
    }
    @Test public void sourceOwnershipBlocksStartAttachDetachAndPathUpdates() {
        String key = AblestackSharedRawBackupServiceImpl.marker("shared.raw");
        var plan = new AblestackSharedRawBackup.Plan(); plan.jobId = "job"; plan.operation = "BACKUP";
        when(poolDetails.findDetail(10L, key)).thenReturn(new StoragePoolDetailVO(10L, key, new Gson().toJson(plan), false));
        assertThrows(CloudRuntimeException.class, () -> service.guardStart(20L));
        assertThrows(CloudRuntimeException.class, () -> service.guardVolume(null, 1L, null, null));
        assertThrows(CloudRuntimeException.class, () -> service.guardVolume(null, null, 10L, "shared.raw"));
    }
    @Test public void rbdRawKeepsExistingOnlineIncrementalPath() {
        when(pool.getPoolType()).thenReturn(Storage.StoragePoolType.RBD);
        assertFalse(service.requiresOfflineFull(List.of(disk)));
        assertNull(service.protect(backup("INCREMENTAL"), worker, "job", "BACKUP", List.of(disk)));
        verifyNoInteractions(agents, backupDetails);
    }
    @Test public void sourceReleaseCannotRemoveProtectionWhileHostIsUnconfirmed() throws Exception {
        var plan = new AblestackSharedRawBackup.Plan(); plan.jobId = "job"; plan.hostId = 50L; plan.operation = "BACKUP";
        when(backupDetails.findDetail(60L, AblestackSharedRawBackup.BACKUP_KEY)).thenReturn(new BackupDetailVO(60L, "plan", new Gson().toJson(plan), false));
        when(agents.send(anyLong(), any(AblestackSharedRawBackupCommand.class))).thenReturn(new Answer(null, false, "reader active"));
        assertThrows(CloudRuntimeException.class, () -> service.release(backup("FULL"), "job", "BACKUP"));
        verify(poolDetails, never()).removeDetail(anyLong(), anyString());
    }
}
