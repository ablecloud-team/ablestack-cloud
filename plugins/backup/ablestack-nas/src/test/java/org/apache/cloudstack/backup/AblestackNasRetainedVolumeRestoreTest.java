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
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.apache.cloudstack.backup.dao.BackupDao;
import org.apache.cloudstack.backup.dao.BackupDetailsDao;
import org.apache.cloudstack.backup.dao.BackupRepositoryDao;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import com.cloud.agent.AgentManager;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.storage.DataStoreRole;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.Storage;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.Pair;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.VMInstanceDao;
import com.google.gson.Gson;
import org.mockito.Mockito;

@RunWith(MockitoJUnitRunner.class)
public class AblestackNasRetainedVolumeRestoreTest {
    @InjectMocks private AblestackNasBackupProvider provider;
    @Mock private BackupDao backupDao;
    @Mock private BackupDetailsDao backupDetailsDao;
    @Mock private BackupRepositoryDao backupRepositoryDao;
    @Mock private VolumeDao volumeDao;
    @Mock private DiskOfferingDao diskOfferingDao;
    @Mock private VMInstanceDao vmInstanceDao;
    @Mock private PrimaryDataStoreDao primaryDataStoreDao;
    @Mock private HostDao hostDao;
    @Mock private AgentManager agentManager;
    @Mock private DataStoreManager dataStoreMgr;

    @Test
    public void incrementalVolumeRestoreDoesNotNeedTheOriginalVmOrVolume() throws Exception {
        Backup.VolumeInfo saved = new Backup.VolumeInfo("source-volume", "datadisk.source-volume.qcow2",
                Volume.Type.DATADISK, 64L * 1024 * 1024 * 1024, 1L, "disk-offering", null, null);
        BackupVO full = backup("full", "/source-vm/full", saved);
        BackupVO incremental = backup("inc", "/source-vm/inc", saved);
        incremental.getDetails().put("nas.parent.backup.uuid", full.getUuid());
        when(backupDao.findByUuid(full.getUuid())).thenReturn(full);
        when(backupDao.listByVmIdAndOffering(0L, 42L, 0L)).thenReturn(List.of(full, incremental));
        // Both original VM and original volume are absent from active records.
        when(volumeDao.findByUuidIncludingRemoved(saved.getUuid())).thenReturn(null);
        DiskOfferingVO offering = Mockito.mock(DiskOfferingVO.class);
        when(diskOfferingDao.findByUuid("disk-offering")).thenReturn(offering);
        VMInstanceVO target = Mockito.mock(VMInstanceVO.class);
        when(target.getId()).thenReturn(9L);
        when(vmInstanceDao.findVMByInstanceName("target-vm")).thenReturn(target);
        StoragePoolVO pool = Mockito.mock(StoragePoolVO.class);
        when(pool.getId()).thenReturn(8L);
        when(pool.getPoolType()).thenReturn(Storage.StoragePoolType.SharedMountPoint);
        when(pool.getPath()).thenReturn("/mnt/glue-gfs");
        when(primaryDataStoreDao.findByUuid("target-pool")).thenReturn(pool);
        HostVO host = Mockito.mock(HostVO.class);
        when(host.getId()).thenReturn(7L);
        when(hostDao.findByIp("10.0.0.7")).thenReturn(host);
        BackupRepository repository = Mockito.mock(BackupRepository.class);
        when(backupRepositoryDao.findByBackupOfferingId(0L)).thenReturn(repository);
        DataStore dataStore = Mockito.mock(DataStore.class);
        when(dataStoreMgr.getDataStore(8L, DataStoreRole.Primary)).thenReturn(dataStore);
        when(dataStore.getTO()).thenReturn(Mockito.mock(PrimaryDataStoreTO.class));
        when(agentManager.send(eq(7L), any(AblestackNasRestoreBackupCommand.class)))
                .thenAnswer(call -> new BackupAnswer(call.getArgument(1), true, null));

        Pair<Boolean, String> result = provider.startRestoreBackedUpVolume(incremental, saved,
                "10.0.0.7", "target-pool", new Pair<>("target-vm", VirtualMachine.State.Stopped), target, false);

        assertTrue(result.first());
        ArgumentCaptor<AblestackNasRestoreBackupCommand> command = ArgumentCaptor.forClass(AblestackNasRestoreBackupCommand.class);
        verify(agentManager).send(eq(7L), command.capture());
        assertEquals("target-vm", command.getValue().getVmName());
        assertEquals("datadisk", command.getValue().getDiskType());
        assertEquals(List.of("/source-vm/full/datadisk.source-volume.qcow2", "/source-vm/inc/datadisk.source-volume.qcow2"),
                command.getValue().getVolumeChainStates().get(0).getChainFiles());
        ArgumentCaptor<VolumeVO> restored = ArgumentCaptor.forClass(VolumeVO.class);
        verify(volumeDao).persist(restored.capture());
        assertEquals(saved.getSize(), restored.getValue().getSize());
        assertEquals(result.second(), restored.getValue().getUuid());
        verify(volumeDao, never()).findByUuid(saved.getUuid());
        verify(vmInstanceDao, never()).findById(any());
    }

    private BackupVO backup(String uuid, String path, Backup.VolumeInfo saved) {
        BackupVO backup = new BackupVO();
        org.springframework.test.util.ReflectionTestUtils.setField(backup, "uuid", uuid);
        backup.setDetails(new HashMap<>());
        backup.getDetails().put("nas.backup.engine", "QCOW2");
        backup.setVmId(42L);
        backup.setExternalId(path);
        backup.setBackedUpVolumes(new Gson().toJson(List.of(saved)));
        return backup;
    }
}
