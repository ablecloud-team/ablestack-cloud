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

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import com.cloud.agent.AgentManager;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.storage.Storage;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.dao.VMInstanceDao;

@RunWith(MockitoJUnitRunner.class)
public class BackupSourceSupportGuardTest {
    @Mock private PrimaryDataStoreDao poolDao;
    @Mock private VolumeDao volumeDao;
    @Mock private VMInstanceDao vmDao;
    @Mock private AgentManager agentManager;
    @Mock private AblestackSharedRawBackupService sharedRaw;
    @InjectMocks private BackupSnapshotGuard guard;

    private void vm() {
        VMInstanceVO vm = mock(VMInstanceVO.class);
        when(vm.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(vmDao.findById(1L)).thenReturn(vm);
    }

    private VolumeVO raw(Storage.StoragePoolType poolType) {
        VolumeVO volume = mock(VolumeVO.class);
        when(volume.getFormat()).thenReturn(Storage.ImageFormat.RAW);
        when(volume.getPoolId()).thenReturn(10L);
        StoragePoolVO pool = mock(StoragePoolVO.class);
        when(pool.getPoolType()).thenReturn(poolType);
        when(poolDao.findById(10L)).thenReturn(pool);
        return volume;
    }

    @Test public void allFourProvidersCheckAllSharedRawWritersBeforeDispatch() {
        vm();
        VolumeVO raw = raw(Storage.StoragePoolType.SharedMountPoint);
        when(volumeDao.findByInstance(1L)).thenReturn(Collections.singletonList(raw));
        for (String provider : Arrays.asList("ablestack-nas", "ablestack-commvault", "ablestack-netbackup", "ablestack-veeam"))
            guard.checkBackupSource(1L, provider);
        org.mockito.Mockito.verify(sharedRaw, org.mockito.Mockito.times(4)).checkSources(1L);
        verifyNoInteractions(agentManager);
    }

    @Test public void aRunningSharedWriterRejectsTheEntireVm() {
        vm();
        VolumeVO data = raw(Storage.StoragePoolType.SharedMountPoint);
        when(volumeDao.findByInstance(1L)).thenReturn(Collections.singletonList(data));
        org.mockito.Mockito.doThrow(new CloudRuntimeException("SHARED_RAW_VM_NOT_STOPPED"))
                .when(sharedRaw).checkSources(1L);
        org.junit.Assert.assertThrows(CloudRuntimeException.class, () -> guard.checkBackupSource(1L, "ablestack-netbackup"));
        verifyNoInteractions(agentManager);
    }

    @Test public void rbdRawRemainsSupported() {
        vm();
        VolumeVO volume = raw(Storage.StoragePoolType.RBD);
        when(volumeDao.findByInstance(1L)).thenReturn(Collections.singletonList(volume));
        for (String provider : Arrays.asList("ablestack-nas", "ablestack-commvault", "ablestack-netbackup", "ablestack-veeam")) {
            guard.checkBackupSource(1L, provider);
        }
    }

    @Test public void qcow2KeepsLegacyAndExplicitAllocationPolicies() {
        vm();
        VolumeVO volume = mock(VolumeVO.class);
        when(volume.getFormat()).thenReturn(Storage.ImageFormat.QCOW2);
        when(volumeDao.findByInstance(1L)).thenReturn(Collections.singletonList(volume));
        guard.checkBackupSource(1L, "ablestack-veeam");
        verifyNoInteractions(poolDao, agentManager);
    }

    @Test public void nfsRawIsOutsideThisRestriction() {
        vm();
        VolumeVO volume = raw(Storage.StoragePoolType.NetworkFilesystem);
        when(volumeDao.findByInstance(1L)).thenReturn(Collections.singletonList(volume));
        guard.checkBackupSource(1L, "ablestack-nas");
    }

    @Test public void otherHypervisorsKeepExistingProviderBehavior() {
        VMInstanceVO vm = mock(VMInstanceVO.class);
        when(vm.getHypervisorType()).thenReturn(HypervisorType.VMware);
        when(vmDao.findById(1L)).thenReturn(vm);
        guard.checkBackupSource(1L, "ablestack-veeam");
        verifyNoInteractions(volumeDao, poolDao, agentManager);
    }

    @Test public void otherProvidersAreUnchanged() {
        guard.checkBackupSource(1L, "kboss");
        verifyNoInteractions(vmDao, volumeDao, poolDao, agentManager);
    }

    @Test public void missingRawPoolFailsBeforeBackup() {
        vm();
        VolumeVO volume = mock(VolumeVO.class);
        when(volume.getFormat()).thenReturn(Storage.ImageFormat.RAW);
        when(volumeDao.findByInstance(1L)).thenReturn(Collections.singletonList(volume));
        try {
            guard.checkBackupSource(1L, "ablestack-commvault");
            fail("Expected an unresolved RAW source to be rejected");
        } catch (CloudRuntimeException error) {
            assertTrue(error.getMessage().startsWith("BACKUP_SOURCE_UNKNOWN:"));
        }
        verifyNoInteractions(agentManager);
    }
}
