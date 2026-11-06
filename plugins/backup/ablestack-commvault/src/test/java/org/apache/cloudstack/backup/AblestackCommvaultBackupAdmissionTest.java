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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.apache.cloudstack.backup.commvault.AblestackCommvaultClient;
import org.apache.cloudstack.backup.dao.BackupDao;
import org.apache.cloudstack.backup.dao.BackupDetailsDao;
import org.apache.cloudstack.backup.dao.BackupOfferingDao;
import org.apache.cloudstack.backup.dao.BackupOfferingDetailsDao;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.impl.ConfigDepotImpl;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.storage.DataStoreRole;
import com.cloud.storage.Storage;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.vm.snapshot.dao.VMSnapshotDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.doThrow;

public class AblestackCommvaultBackupAdmissionTest {
    private final AblestackCommvaultClient client = mock(AblestackCommvaultClient.class);
    private final AblestackCommvaultBackupProvider provider = new AblestackCommvaultBackupProvider() {
        @Override AblestackCommvaultClient getOrCreateClient(Long zoneId, ClientSettings settings) { return client; }
    };
    private final BackupDao backups = mock(BackupDao.class);
    private final VolumeDao volumes = mock(VolumeDao.class);
    private final PrimaryDataStoreDao pools = mock(PrimaryDataStoreDao.class);
    private final HostDao hosts = mock(HostDao.class);
    private final DataStoreManager stores = mock(DataStoreManager.class);
    private final ThirdPartyBackupStagingService staging = mock(ThirdPartyBackupStagingService.class);
    private final ThirdPartyBackupVolumeService jobs = mock(ThirdPartyBackupVolumeService.class);
    private final BackupManager manager = mock(BackupManager.class);
    private final ConfigDepotImpl depot = mock(ConfigDepotImpl.class);
    private final VMInstanceVO vm = mock(VMInstanceVO.class);
    private final VolumeVO root = mock(VolumeVO.class);
    private final StoragePoolVO pool = mock(StoragePoolVO.class);
    private final PrimaryDataStoreTO poolTo = mock(PrimaryDataStoreTO.class);
    private final HostVO host = mock(HostVO.class);
    private final BackupVolumeAllocationService allocation = mock(BackupVolumeAllocationService.class);
    private ConfigDepotImpl originalDepot;

    @Before public void setup() {
        originalDepot = (ConfigDepotImpl) ReflectionTestUtils.getField(BackupManager.KvmIncrementalBackup, "s_depot");
        ConfigKey.init(depot);
        ReflectionTestUtils.setField(provider, "backupDao", backups);
        ReflectionTestUtils.setField(provider, "backupVolumeAllocationService", allocation);
        ReflectionTestUtils.setField(provider, "volumeDao", volumes);
        ReflectionTestUtils.setField(provider, "primaryDataStoreDao", pools);
        ReflectionTestUtils.setField(provider, "hostDao", hosts);
        ReflectionTestUtils.setField(provider, "dataStoreMgr", stores);
        ReflectionTestUtils.setField(provider, "thirdPartyBackupStagingService", staging);
        ReflectionTestUtils.setField(provider, "thirdPartyBackupVolumeService", jobs);
        ReflectionTestUtils.setField(provider, "backupManager", manager);
        ReflectionTestUtils.setField(provider, "diskOfferingDao", mock(DiskOfferingDao.class));
        ReflectionTestUtils.setField(provider, "vmSnapshotDao", mock(VMSnapshotDao.class));
        ReflectionTestUtils.setField(provider, "backupDetailsDao", mock(BackupDetailsDao.class));
        BackupOfferingDao offerings = mock(BackupOfferingDao.class);
        BackupOfferingVO offering = mock(BackupOfferingVO.class);
        when(offering.getExternalId()).thenReturn("7");
        when(offerings.findById(2L)).thenReturn(offering);
        ReflectionTestUtils.setField(provider, "backupOfferingDao", offerings);
        ReflectionTestUtils.setField(provider, "backupOfferingDetailsDao", mock(BackupOfferingDetailsDao.class));
        when(client.prepareVmBackupSet("host-test", "i-2-10-VM", "7")).thenReturn("22");
        when(depot.getConfigStringValue(eq("backup.plugin.commvault.url"), any(), any())).thenReturn("https://commvault.test/api");
        when(manager.getBackupDetailsFromVM(vm)).thenAnswer(call -> new HashMap<>());
        when(vm.getId()).thenReturn(10L);
        when(vm.getState()).thenReturn(VirtualMachine.State.Running);
        when(vm.getHostId()).thenReturn(30L);
        when(vm.getInstanceName()).thenReturn("i-2-10-VM");
        when(vm.getDataCenterId()).thenReturn(1L);
        when(vm.getBackupOfferingId()).thenReturn(2L);
        when(root.getUuid()).thenReturn("root-volume");
        when(root.getVolumeType()).thenReturn(Volume.Type.ROOT);
        when(root.getState()).thenReturn(Volume.State.Ready);
        when(root.getPoolId()).thenReturn(20L);
        when(root.getDeviceId()).thenReturn(0L);
        when(root.getSize()).thenReturn(10L << 30);
        when(root.getPath()).thenReturn("root-volume");
        when(volumes.findByInstance(10L)).thenAnswer(call -> new ArrayList<>(List.of(root)));
        when(volumes.getInstanceRootVolume(10L)).thenReturn(root);
        when(pools.findById(20L)).thenReturn(pool);
        when(pool.getId()).thenReturn(20L);
        when(pool.getClusterId()).thenReturn(40L);
        when(pool.getPath()).thenReturn("/mnt/primary");
        when(pool.getPoolType()).thenReturn(Storage.StoragePoolType.SharedMountPoint);
        when(poolTo.getPoolType()).thenReturn(Storage.StoragePoolType.SharedMountPoint);
        DataStore store = mock(DataStore.class);
        when(stores.getDataStore(20L, DataStoreRole.Primary)).thenReturn(store);
        when(store.getTO()).thenReturn(poolTo);
        when(hosts.findById(30L)).thenReturn(host);
        when(host.getId()).thenReturn(30L);
        when(host.getStatus()).thenReturn(Status.Up);
        when(host.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(host.getName()).thenReturn("host-test");
        when(staging.getStageRootPath("ablestack-commvault")).thenReturn("/mnt/staging/ablestack-commvault");
        when(staging.getAvailableBytes(eq(host), any())).thenReturn(Long.MAX_VALUE);
        when(staging.prepareBackupStart(eq(host), any())).thenReturn(Long.MAX_VALUE);
        when(manager.getBackupNameFromVM(vm)).thenReturn("backup-test");
        when(backups.persist(any(BackupVO.class))).thenAnswer(call -> call.getArgument(0));
        when(depot.getConfigStringValue("kvm.incremental.backup", ConfigKey.Scope.Cluster, 40L)).thenReturn("false");
        when(depot.getConfigStringValue("kvm.backup.chain.size", ConfigKey.Scope.Global, null)).thenReturn("8");
    }

    @After public void restoreConfigurationDepot() { ConfigKey.init(originalDepot); }

    @Test public void sourceOutageRejectsBeforeRegistrationReleasesTheLeaseAndAllowsRecovery() {
        ThirdPartyBackupVolumeService.Lease lease = mock(ThirdPartyBackupVolumeService.Lease.class);
        when(jobs.acquireLifecycle(10L)).thenReturn(lease);
        CloudRuntimeException unavailable = new CloudRuntimeException("BACKUP_START_DEFERRED: Source I/O error");
        doThrow(unavailable).doThrow(unavailable).doThrow(unavailable).doNothing().when(staging)
                .validateBackupSources(eq(host), any(), any());
        for (int attempt = 0; attempt < 3; attempt++) {
            assertTrue(assertThrows(CloudRuntimeException.class, () -> provider.takeBackup(vm, false, false, null))
                    .getMessage().contains("BACKUP_START_DEFERRED"));
        }
        verify(backups, never()).persist(any(BackupVO.class));
        verify(jobs, never()).persistBackupPlan(any());
        verify(lease, times(3)).close();
        assertTrue(provider.takeBackup(vm, false, false, null).first());
        verify(backups).persist(any(BackupVO.class));
        verify(lease, times(4)).close();
    }

    @Test public void missingNfsRejectsBeforeAnyPointOrSourceProbeIsCreated() {
        when(staging.prepareBackupStart(eq(host), any())).thenThrow(new CloudRuntimeException("NFS unavailable"));
        for (int attempt = 0; attempt < 3; attempt++) {
            assertThrows(CloudRuntimeException.class, () -> provider.takeBackup(vm, false, false, null));
        }
        verify(backups, never()).persist(any(BackupVO.class));
        verify(staging, never()).validateBackupSources(eq(host), any(), any());
    }

    @Test public void disconnectedHostRejectsBeforeRegistration() {
        when(host.getStatus()).thenReturn(Status.Disconnected);
        assertThrows(CloudRuntimeException.class, () -> provider.takeBackup(vm, false, false, null));
        verify(backups, never()).persist(any(BackupVO.class));
    }

    @Test public void dispatchUncertaintyRetainsTheRegisteredAttempt() {
        doThrow(new CloudRuntimeException("Agent response timed out")).when(jobs).dispatchBackup(any(BackupVO.class), eq(host), any(), any());
        assertTrue(provider.takeBackup(vm, false, false, null).first());
        verify(backups).persist(any(BackupVO.class));
        verify(jobs).recordBackupStartUnconfirmed(any(BackupVO.class), eq("Agent response timed out"));
        verify(backups, never()).remove(any(Long.class));
    }

}
