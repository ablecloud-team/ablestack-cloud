/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.cloudstack.storage.vmsnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.cloudstack.engine.subsystem.api.storage.StrategyPriority;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;

import com.cloud.storage.Storage;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.snapshot.VMSnapshot;

@RunWith(MockitoJUnitRunner.class)
public class DefaultVMSnapshotStrategyTest {
    @Mock
    VolumeDao volumeDao;
    @Mock
    PrimaryDataStoreDao primaryDataStoreDao;
    @Mock
    UserVmDao userVmDao;
    @Mock
    com.cloud.storage.dao.VolumeDetailsDao volumeDetailsDao;

    @Mock
    com.cloud.agent.AgentManager agentMgr;
    @Mock
    org.apache.cloudstack.framework.config.dao.ConfigurationDao configurationDao;
    @Mock
    com.cloud.service.dao.ServiceOfferingDao serviceOfferingDao;

    @Test
    public void internalCounterTransitionPreservesProvenanceAndDoesNotGrowOnReplayOrResize() {
        setupVolumeDaoPersistMock();
        VolumeVO volume = Mockito.spy(createVolume(20L, 1L));
        Mockito.when(volume.getId()).thenReturn(10L);
        volume.setInstanceId(20L);
        volume.setPoolId(1L);
        volume.setFormat(Storage.ImageFormat.QCOW2);
        volume.setSize(100L);
        volume.setVmSnapshotChainSize(300L);
        Mockito.when(volumeDao.findById(10L)).thenReturn(volume);
        StoragePoolVO pool = createStoragePool("shared", Storage.StoragePoolType.SharedMountPoint);
        Mockito.when(primaryDataStoreDao.findById(1L)).thenReturn(pool);
        UserVmVO vm = Mockito.mock(UserVmVO.class);
        Mockito.when(vm.getHypervisorType()).thenReturn(com.cloud.hypervisor.Hypervisor.HypervisorType.KVM);
        Mockito.when(userVmDao.findById(20L)).thenReturn(vm);
        VolumeObjectTO to = new VolumeObjectTO();
        to.setId(10L); to.setPath("same-qcow-path");
        defaultVMSnapshotStrategy.updateVolumePath(List.of(to), "finalizeCreate");
        Assert.assertEquals(Long.valueOf(0), volume.getVmSnapshotChainSize());
        Mockito.verify(volumeDetailsDao).addDetail(10L, com.cloud.storage.InternalVmSnapshotAccounting.LEGACY_KEY, "300", false);
        Mockito.when(volumeDetailsDao.findDetail(10L, com.cloud.storage.InternalVmSnapshotAccounting.VERSION_KEY)).thenReturn(new com.cloud.storage.VolumeDetailVO());
        volume.setSize(200L);
        for (String action : List.of("finalizeCreate", "finalizeCreate", "finalizeRevert", "finalizeDelete", "finalizeDelete")) {
            defaultVMSnapshotStrategy.updateVolumePath(List.of(to), action);
            Assert.assertEquals(Long.valueOf(0), volume.getVmSnapshotChainSize());
        }
        Mockito.verify(volumeDetailsDao, Mockito.times(1)).addDetail(10L, com.cloud.storage.InternalVmSnapshotAccounting.LEGACY_KEY, "300", false);
    }

    @Spy
    @InjectMocks
    private final DefaultVMSnapshotStrategy defaultVMSnapshotStrategy = new DefaultVMSnapshotStrategy();

    protected List<VolumeVO> persistedVolumes = new ArrayList<>();


    private void setupVolumeDaoPersistMock() {
        persistedVolumes.clear();
        Mockito.when(volumeDao.persist(Mockito.any())).thenAnswer((Answer<VolumeVO>) invocation -> {
            VolumeVO volume = (VolumeVO)invocation.getArguments()[0];
            persistedVolumes.add(volume);
            return volume;
        });
    }

    @Test
    public void testUpdateVolumePath() {
        setupVolumeDaoPersistMock();
        VolumeObjectTO vol1 = Mockito.mock(VolumeObjectTO.class);
        Mockito.when(vol1.getDataStoreUuid()).thenReturn(null);
        Mockito.when(vol1.getPath()).thenReturn(null);
        Mockito.when(vol1.getChainInfo()).thenReturn(null);
        VolumeObjectTO vol2 = Mockito.mock(VolumeObjectTO.class);
        Long volumeId = 1L;
        String newDSUuid = UUID.randomUUID().toString();
        String oldVolPath = "old";
        String newVolPath = "new";
        String oldVolChain = "old-chain";
        String newVolChain = "new-chain";
        Long vmSnapshotChainSize = 1000L;
        Long oldPoolId = 1L;
        Long newPoolId = 2L;
        Mockito.when(vol2.getDataStoreUuid()).thenReturn(newDSUuid);
        Mockito.when(vol2.getPath()).thenReturn(newVolPath);
        Mockito.when(vol2.getChainInfo()).thenReturn(newVolChain);
        Mockito.when(vol2.getSize()).thenReturn(vmSnapshotChainSize);
        Mockito.when(vol2.getId()).thenReturn(volumeId);
        VolumeVO volumeVO = new VolumeVO("name", 0L, 0L, 0L, 0L, 0L, "folder", "path", Storage.ProvisioningType.THIN, 0L, Volume.Type.ROOT);
        volumeVO.setPoolId(oldPoolId);
        volumeVO.setChainInfo(oldVolChain);
        volumeVO.setPath(oldVolPath);
        Mockito.when(volumeDao.findById(volumeId)).thenReturn(volumeVO);
        StoragePoolVO storagePoolVO = Mockito.mock(StoragePoolVO.class);
        Mockito.when(storagePoolVO.getId()).thenReturn(newPoolId);
        Mockito.when(primaryDataStoreDao.findPoolByUUID(newDSUuid)).thenReturn(storagePoolVO);
        Mockito.when(volumeDao.findById(volumeId)).thenReturn(volumeVO);
        defaultVMSnapshotStrategy.updateVolumePath(List.of(vol1, vol2), null);
        Assert.assertEquals(1, persistedVolumes.size());
        VolumeVO persistedVolume = persistedVolumes.get(0);
        Assert.assertNotNull(persistedVolume);
        Assert.assertEquals(newPoolId, persistedVolume.getPoolId());
        Assert.assertEquals(newVolPath, persistedVolume.getPath());
        Assert.assertEquals(vmSnapshotChainSize, persistedVolume.getVmSnapshotChainSize());
        Assert.assertEquals(newVolChain, persistedVolume.getChainInfo());
    }

    @Test
    public void testCanHandleRunningVMOnClvmStorageCantHandle() {
        Long vmId = 1L;
        VMSnapshot vmSnapshot = Mockito.mock(VMSnapshot.class);
        Mockito.when(vmSnapshot.getVmId()).thenReturn(vmId);

        UserVmVO vm = Mockito.mock(UserVmVO.class);
        Mockito.when(vm.getId()).thenReturn(vmId);
        Mockito.when(vm.getState()).thenReturn(State.Running);
        Mockito.when(userVmDao.findById(vmId)).thenReturn(vm);

        VolumeVO volumeOnClvm = createVolume(vmId, 1L);
        List<VolumeVO> volumes = List.of(volumeOnClvm);
        Mockito.when(volumeDao.findByInstance(vmId)).thenReturn(volumes);

        StoragePoolVO clvmPool = createStoragePool("clvm-pool", Storage.StoragePoolType.CLVM);
        Mockito.when(primaryDataStoreDao.findById(1L)).thenReturn(clvmPool);

        StrategyPriority result = defaultVMSnapshotStrategy.canHandle(vmSnapshot);

        Assert.assertEquals("Should return CANT_HANDLE for running VM on CLVM storage",
                StrategyPriority.CANT_HANDLE, result);
    }

    @Test
    public void testCanHandleStoppedVMOnClvmStorageCanHandle() {
        Long vmId = 1L;
        VMSnapshot vmSnapshot = Mockito.mock(VMSnapshot.class);
        Mockito.when(vmSnapshot.getVmId()).thenReturn(vmId);

        UserVmVO vm = Mockito.mock(UserVmVO.class);
        Mockito.when(vm.getId()).thenReturn(vmId);
        Mockito.when(vm.getState()).thenReturn(State.Stopped);
        Mockito.when(userVmDao.findById(vmId)).thenReturn(vm);

        StrategyPriority result = defaultVMSnapshotStrategy.canHandle(vmSnapshot);
        Assert.assertEquals("Should return DEFAULT for stopped VM on CLVM storage",
                StrategyPriority.DEFAULT, result);
    }

    @Test
    public void testCanHandleRunningVMOnNfsStorageCanHandle() {
        Long vmId = 1L;
        VMSnapshot vmSnapshot = Mockito.mock(VMSnapshot.class);
        Mockito.when(vmSnapshot.getVmId()).thenReturn(vmId);

        UserVmVO vm = Mockito.mock(UserVmVO.class);
        Mockito.when(vm.getId()).thenReturn(vmId);
        Mockito.when(vm.getState()).thenReturn(State.Running);
        Mockito.when(userVmDao.findById(vmId)).thenReturn(vm);

        VolumeVO volumeOnNfs = createVolume(vmId, 1L);
        List<VolumeVO> volumes = List.of(volumeOnNfs);
        Mockito.when(volumeDao.findByInstance(vmId)).thenReturn(volumes);

        StoragePoolVO nfsPool = createStoragePool("nfs-pool", Storage.StoragePoolType.NetworkFilesystem);
        Mockito.when(primaryDataStoreDao.findById(1L)).thenReturn(nfsPool);

        StrategyPriority result = defaultVMSnapshotStrategy.canHandle(vmSnapshot);

        Assert.assertEquals("Should return DEFAULT for running VM on NFS storage",
                StrategyPriority.DEFAULT, result);
    }

    @Test
    public void testCanHandleRunningVMWithMixedStorageClvmAndNfsCantHandle() {
        // Arrange - VM has volumes on both CLVM and NFS
        Long vmId = 1L;
        VMSnapshot vmSnapshot = Mockito.mock(VMSnapshot.class);
        Mockito.when(vmSnapshot.getVmId()).thenReturn(vmId);

        UserVmVO vm = Mockito.mock(UserVmVO.class);
        Mockito.when(vm.getId()).thenReturn(vmId);
        Mockito.when(vm.getState()).thenReturn(State.Running);
        Mockito.when(userVmDao.findById(vmId)).thenReturn(vm);

        VolumeVO volumeOnClvm = createVolume(vmId, 1L);
        VolumeVO volumeOnNfs = createVolume(vmId, 2L);
        List<VolumeVO> volumes = List.of(volumeOnClvm, volumeOnNfs);
        Mockito.when(volumeDao.findByInstance(vmId)).thenReturn(volumes);

        StoragePoolVO clvmPool = createStoragePool("clvm-pool", Storage.StoragePoolType.CLVM);
        StoragePoolVO nfsPool = createStoragePool("nfs-pool", Storage.StoragePoolType.NetworkFilesystem);
        Mockito.when(primaryDataStoreDao.findById(1L)).thenReturn(clvmPool);

        StrategyPriority result = defaultVMSnapshotStrategy.canHandle(vmSnapshot);

        Assert.assertEquals("Should return CANT_HANDLE if any volume is on CLVM storage for running VM",
                StrategyPriority.CANT_HANDLE, result);
    }

    private List<VolumeObjectTO> setupPhysicalGuard() throws Exception {
        UserVmVO vm = Mockito.mock(UserVmVO.class);
        Mockito.when(vm.getHypervisorType()).thenReturn(com.cloud.hypervisor.Hypervisor.HypervisorType.KVM);
        Mockito.when(userVmDao.findById(20L)).thenReturn(vm);
        com.cloud.service.ServiceOfferingVO offering = Mockito.mock(com.cloud.service.ServiceOfferingVO.class);
        Mockito.when(offering.getRamSize()).thenReturn(2048);
        Mockito.when(serviceOfferingDao.findById(vm.getId(), vm.getServiceOfferingId())).thenReturn(offering);
        Mockito.doReturn(0.9d).when(defaultVMSnapshotStrategy).internalSnapshotPhysicalThreshold(Mockito.any(com.cloud.storage.StoragePool.class));
        List<VolumeObjectTO> tos = new ArrayList<>();
        for (long id : List.of(2L, 1L)) {
            VolumeVO volume = createVolume(20L, id);
            volume.setFormat(Storage.ImageFormat.QCOW2);
            if (id == 2) volume.setVolumeType(Volume.Type.DATADISK);
            Mockito.when(volumeDao.findById(id)).thenReturn(volume);
            StoragePoolVO pool = createStoragePool("shared" + id, Storage.StoragePoolType.SharedMountPoint);
            Mockito.when(pool.getId()).thenReturn(id);
            Mockito.when(primaryDataStoreDao.findById(id)).thenReturn(pool);
            com.cloud.utils.db.GlobalLock lock = Mockito.mock(com.cloud.utils.db.GlobalLock.class);
            Mockito.lenient().when(lock.lock(120)).thenReturn(true);
            Mockito.lenient().doReturn(lock).when(defaultVMSnapshotStrategy).internalSnapshotPoolLock(id);
            VolumeObjectTO to = new VolumeObjectTO(); to.setId(id); tos.add(to);
        }
        return tos;
    }

    @Test
    public void physicalGuardLocksAllRootAndDataPoolsInStableOrderAndUsesFreshStats() throws Exception {
        List<VolumeObjectTO> tos = setupPhysicalGuard();
        Mockito.when(agentMgr.send(Mockito.eq(3L), Mockito.any(com.cloud.agent.api.GetStorageStatsCommand.class)))
            .thenAnswer(invocation -> new com.cloud.agent.api.GetStorageStatsAnswer(invocation.getArgument(1), 100L << 30, 70L << 30));
        List<com.cloud.utils.db.GlobalLock> locks = new ArrayList<>();
        defaultVMSnapshotStrategy.checkInternalSnapshotPhysicalSpace(userVmDao.findById(20L), tos, 3L, locks);
        Assert.assertEquals(2, locks.size());
        org.mockito.InOrder order = Mockito.inOrder(defaultVMSnapshotStrategy);
        order.verify(defaultVMSnapshotStrategy).internalSnapshotPoolLock(1L);
        order.verify(defaultVMSnapshotStrategy).internalSnapshotPoolLock(2L);
        Mockito.verify(agentMgr, Mockito.times(2)).send(Mockito.eq(3L), Mockito.any(com.cloud.agent.api.GetStorageStatsCommand.class));
    }

    @Test(expected = com.cloud.utils.exception.CloudRuntimeException.class)
    public void physicalShortageRejectsEvenWhenLogicalCapacityIsAvailable() throws Exception {
        List<VolumeObjectTO> tos = setupPhysicalGuard();
        Mockito.when(agentMgr.send(Mockito.eq(3L), Mockito.any(com.cloud.agent.api.GetStorageStatsCommand.class)))
            .thenAnswer(invocation -> new com.cloud.agent.api.GetStorageStatsAnswer(invocation.getArgument(1), 100L << 30, 89L << 30));
        defaultVMSnapshotStrategy.checkInternalSnapshotPhysicalSpace(userVmDao.findById(20L), tos, 3L, new ArrayList<>());
    }

    @Test(expected = com.cloud.utils.exception.CloudRuntimeException.class)
    public void dataPoolPhysicalThresholdIsRespectedIndependentlyOfRootPool() throws Exception {
        List<VolumeObjectTO> tos = setupPhysicalGuard();
        com.cloud.storage.StoragePool dataPool = primaryDataStoreDao.findById(2L);
        Mockito.doReturn(0.65d).when(defaultVMSnapshotStrategy).internalSnapshotPhysicalThreshold(dataPool);
        Mockito.when(agentMgr.send(Mockito.eq(3L), Mockito.any(com.cloud.agent.api.GetStorageStatsCommand.class)))
            .thenAnswer(invocation -> new com.cloud.agent.api.GetStorageStatsAnswer(invocation.getArgument(1), 100L << 30, 70L << 30));
        defaultVMSnapshotStrategy.checkInternalSnapshotPhysicalSpace(userVmDao.findById(20L), tos, 3L, new ArrayList<>());
    }

    @Test(expected = com.cloud.utils.exception.CloudRuntimeException.class)
    public void unavailableFreshStatsRejectWithoutStaleDatabaseFallback() throws Exception {
        List<VolumeObjectTO> tos = setupPhysicalGuard();
        Mockito.when(agentMgr.send(Mockito.eq(3L), Mockito.any(com.cloud.agent.api.GetStorageStatsCommand.class)))
            .thenAnswer(invocation -> new com.cloud.agent.api.GetStorageStatsAnswer(invocation.getArgument(1), "unavailable"));
        defaultVMSnapshotStrategy.checkInternalSnapshotPhysicalSpace(userVmDao.findById(20L), tos, 3L, new ArrayList<>());
    }

    private VolumeVO createVolume(Long vmId, Long poolId) {
        VolumeVO volume = new VolumeVO("volume", 0L, 0L, 0L, 0L, 0L,
                "folder", "path", Storage.ProvisioningType.THIN, 0L, Volume.Type.ROOT);
        volume.setInstanceId(vmId);
        volume.setPoolId(poolId);
        return volume;
    }

    private StoragePoolVO createStoragePool(String name, Storage.StoragePoolType poolType) {
        StoragePoolVO pool = Mockito.mock(StoragePoolVO.class);
        Mockito.when(pool.getName()).thenReturn(name);
        Mockito.when(pool.getPoolType()).thenReturn(poolType);
        return pool;
    }
}
