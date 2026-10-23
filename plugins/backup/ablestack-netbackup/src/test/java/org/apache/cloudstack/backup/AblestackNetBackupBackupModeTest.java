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
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import org.apache.cloudstack.backup.dao.BackupDao;
import org.apache.cloudstack.backup.dao.BackupOfferingDao;
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
import com.cloud.utils.Pair;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AblestackNetBackupBackupModeTest {
    private final AblestackNetBackupProvider provider = new AblestackNetBackupProvider();
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
        when(staging.getStageRootPath("ablestack-netbackup")).thenReturn("/mnt/staging/ablestack-netbackup");
        when(staging.getAvailableBytes(eq(host), any())).thenReturn(Long.MAX_VALUE);
        when(manager.getBackupNameFromVM(vm)).thenReturn("backup-test");
        when(backups.persist(any(BackupVO.class))).thenAnswer(call -> call.getArgument(0));
        when(depot.getConfigStringValue("kvm.incremental.backup", ConfigKey.Scope.Cluster, 40L)).thenReturn("false");
        when(depot.getConfigStringValue("kvm.backup.chain.size", ConfigKey.Scope.Global, null)).thenReturn("8");
    }

    @After public void restoreConfigurationDepot() { ConfigKey.init(originalDepot); }

    @Test public void disabledIncrementalDispatchesStandaloneQcow2FullWithoutReadingHistory() {
        AblestackNetBackupTakeBackupCommand command = start(false);
        assertFull(command, ".qcow2");
        verify(backups, never()).listByVmIdAndOffering(1L, 10L, 2L);
    }

    @Test public void disabledIncrementalDispatchesRawRbdFullWithoutAParentSnapshot() {
        when(pool.getPoolType()).thenReturn(Storage.StoragePoolType.RBD);
        when(poolTo.getPoolType()).thenReturn(Storage.StoragePoolType.RBD);
        assertFull(start(false), ".raw");
        verify(backups, never()).listByVmIdAndOffering(1L, 10L, 2L);
    }

    @Test public void enabledIncrementalStillStartsWithFullWhenNoParentExists() {
        enabled();
        assertFull(start(false), ".qcow2");
        verify(backups).listByVmIdAndOffering(1L, 10L, 2L);
    }

    @Test public void enabledIncrementalContinuesAHealthyChain() {
        enabled();
        BackupVO parent = parent();
        when(backups.listByVmIdAndOffering(1L, 10L, 2L)).thenReturn(List.of(parent));
        AblestackNetBackupTakeBackupCommand command = start(false);
        assertEquals("INCREMENTAL", command.getBackupType());
        assertEquals("previous-point", command.getParentCheckpointName());
        ThirdPartyBackupManifest manifest = ThirdPartyBackupManifest.fromJson(command.getVolumeStagingManifest());
        assertEquals(parent.getUuid(), manifest.getParentBackupUuid());
        assertEquals(2, manifest.getVolumes().get(0).chain.size());
    }

    @Test public void reachingTheChainLimitStartsANewFullWithoutParentReferences() {
        enabled();
        when(depot.getConfigStringValue("kvm.backup.chain.size", ConfigKey.Scope.Global, null)).thenReturn("1");
        BackupVO parent = parent();
        when(backups.listByVmIdAndOffering(1L, 10L, 2L)).thenReturn(List.of(parent));
        assertFull(start(false), ".qcow2");
    }

    @Test public void sealedParentStartsANewFullEvenWhenIncrementalIsEnabled() {
        enabled();
        BackupVO parent = parent();
        parent.getDetails().put("netbackup.chain.sealed", "true");
        when(backups.listByVmIdAndOffering(1L, 10L, 2L)).thenReturn(List.of(parent));
        assertFull(start(false), ".qcow2");
    }

    @Test public void moldBackupInFullModeKeepsPolicyButDoesNotLinkThePreviousChain() {
        BackupVO parent = parent();
        when(backups.listByVmIdAndOffering(1L, 10L, 2L)).thenReturn(List.of(parent));
        AblestackNetBackupTakeBackupCommand command = start(true);
        assertFull(command, ".qcow2");
        assertEquals("source-policy", command.getPolicyId());
    }

    @Test public void catalogSynchronizationUsesExactImageDeletionForUserBackups() throws Exception {
        com.github.tomakehurst.wiremock.WireMockServer server = new com.github.tomakehurst.wiremock.WireMockServer(
                com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig().dynamicPort());
        server.start();
        try {
            when(depot.getConfigStringValue("backup.plugin.netbackup.url", ConfigKey.Scope.Zone, 1L))
                    .thenReturn("http://localhost:" + server.port() + "/netbackup");
            when(depot.getConfigStringValue("backup.plugin.netbackup.apikey", ConfigKey.Scope.Zone, 1L)).thenReturn("synthetic-key");
            BackupOfferingDao offerings = mock(BackupOfferingDao.class);
            BackupOfferingVO offering = mock(BackupOfferingVO.class);
            when(offering.getProvider()).thenReturn("ablestack-netbackup");
            when(offerings.findById(2L)).thenReturn(offering);
            ReflectionTestUtils.setField(provider, "backupOfferingDao", offerings);
            ReflectionTestUtils.setField(provider, "backupDeletionService", mock(BackupDeletionService.class));
            BackupVO point = new BackupVO();
            point.setVmId(10L);
            point.setZoneId(1L);
            point.setBackupOfferingId(2L);
            point.setDate(new Date(System.currentTimeMillis() - 1200000));
            point.setStatus(Backup.Status.BackedUp);
            point.setDetails(new HashMap<>());
            point.getDetails().put(ThirdPartyBackupManifest.MODE_KEY, ThirdPartyBackupManifest.VOLUME_MODE);
            point.getDetails().put("netbackup.transfer.mode", "USER_BACKUP");
            point.getDetails().put("netbackup.policy.name", "source-policy");
            when(backups.listByVmId(1L, 10L)).thenReturn(List.of(point));
            provider.syncBackups(vm);
            ArgumentCaptor<ThirdPartyBackupVolumeService.ArtifactCleanup> cleanup = ArgumentCaptor.forClass(ThirdPartyBackupVolumeService.ArtifactCleanup.class);
            verify(jobs).reconcileCatalog(eq(point), any(), cleanup.capture());
            ThirdPartyBackupManifest.Artifact artifact = new ThirdPartyBackupManifest.Artifact();
            artifact.backupUuid = point.getUuid();
            artifact.path = "/mnt/staging/ablestack-netbackup/i-2-10-VM/point/root.raw";
            artifact.sourceHost = "host-test";
            artifact.completed = true;
            artifact.jobId = "42";
            artifact.externalId = "host-test_1792636130";
            server.stubFor(get(urlEqualTo("/netbackup/admin/jobs/42")).willReturn(aResponse().withStatus(404)));
            server.stubFor(get(urlEqualTo("/netbackup/catalog/images/" + artifact.externalId)).willReturn(aResponse().withStatus(404)));
            assertTrue(cleanup.getValue().delete(artifact, false, null, true).completed);
        } finally { server.stop(); }
    }

    @Test public void offlineRawStartsIndependentRawFullDespiteEnabledIncremental() {
        enabled();
        when(vm.getState()).thenReturn(VirtualMachine.State.Stopped);
        when(vm.getLastHostId()).thenReturn(30L);
        when(root.getFormat()).thenReturn(Storage.ImageFormat.RAW);
        when(allocation.requiresOfflineFull(any())).thenReturn(true);
        BackupVO previous = parent();
        when(backups.listByVmIdAndOffering(1L, 10L, 2L)).thenReturn(List.of(previous));
        assertFull(start(false), ".raw");
    }

    private void enabled() {
        when(depot.getConfigStringValue("kvm.incremental.backup", ConfigKey.Scope.Cluster, 40L)).thenReturn("true");
    }

    private AblestackNetBackupTakeBackupCommand start(boolean mold) {
        Pair<Boolean, Backup> result = mold ? provider.takeBackup(vm, false, false, null) : provider.takeNetBackup(vm, "source-policy");
        assertTrue(result.first());
        assertEquals("USER_BACKUP", result.second().getDetail("netbackup.transfer.mode"));
        ArgumentCaptor<AblestackNetBackupTakeBackupCommand> command = ArgumentCaptor.forClass(AblestackNetBackupTakeBackupCommand.class);
        verify(jobs).dispatchBackup(any(BackupVO.class), eq(host), command.capture(), any());
        return command.getValue();
    }

    private void assertFull(AblestackNetBackupTakeBackupCommand command, String suffix) {
        assertEquals("FULL", command.getBackupType());
        assertNull(command.getParentCheckpointName());
        assertNull(command.getParentBackupPath());
        assertNull(command.getParentCheckpointXml());
        assertTrue(command.getBackupFiles().get(0).endsWith(suffix));
        ThirdPartyBackupManifest manifest = ThirdPartyBackupManifest.fromJson(command.getVolumeStagingManifest());
        assertEquals("FULL", manifest.getBackupType());
        assertNull(manifest.getParentBackupUuid());
        assertEquals(1, manifest.getVolumes().get(0).chain.size());
        assertNull(manifest.getVolumes().get(0).chain.get(0).parentCheckpointName);
    }

    private BackupVO parent() {
        BackupVO parent = new BackupVO();
        parent.setDate(new Date());
        parent.setStatus(Backup.Status.BackedUp);
        parent.setType("FULL");
        parent.setExternalId("/mnt/staging/ablestack-netbackup/i-2-10-VM/previous-point");
        parent.setDetails(new HashMap<>());
        parent.getDetails().put("netbackup.policy.name", "source-policy");
        parent.getDetails().put("netbackup.backup.engine", "QCOW2");
        parent.getDetails().put("netbackup.checkpoint.name", "previous-point");
        parent.getDetails().put("netbackup.checkpoint.path", parent.getExternalId() + "/checkpoints/previous-point.xml");
        parent.getDetails().put("netbackup.checkpoint.xml", "<domaincheckpoint><name>previous-point</name></domaincheckpoint>");
        Backup.VolumeInfo info = new Backup.VolumeInfo(root.getUuid(), "root.root-volume.qcow2", Volume.Type.ROOT, root.getSize(), 0L, null, null, null);
        ThirdPartyBackupManifest manifest = ThirdPartyBackupManifest.create("ablestack-netbackup", parent.getUuid(), vm.getInstanceName(),
                "previous-point", "FULL", host.getName(), parent.getExternalId(), "QCOW2", List.of(info), null, null);
        manifest.setComplete(true);
        manifest.setMetadata(new ThirdPartyBackupManifest.Artifact());
        manifest.getMetadata().completed = true;
        manifest.getMetadata().jobId = "metadata-job";
        manifest.getMetadata().externalId = "metadata-id";
        for (ThirdPartyBackupManifest.Volume volume : manifest.getVolumes()) {
            for (ThirdPartyBackupManifest.Artifact artifact : volume.chain) {
                artifact.completed = true;
                artifact.jobId = "volume-job";
                artifact.externalId = "volume-id";
            }
        }
        parent.getDetails().put(ThirdPartyBackupManifest.DETAIL_KEY, manifest.toJson());
        return parent;
    }
}
