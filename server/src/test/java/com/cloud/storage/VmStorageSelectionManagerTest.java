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
package com.cloud.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import java.util.Collections;
import org.apache.cloudstack.api.command.user.vm.DeployVMCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.cloud.dc.DataCenter;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.offering.ServiceOffering;
import com.cloud.storage.dao.VolumeDetailsDao;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.User;
import com.cloud.vm.VirtualMachine;

public class VmStorageSelectionManagerTest {
    private final VmStorageSelectionManager manager = new VmStorageSelectionManager();
    private final VolumeDetailsDao details = mock(VolumeDetailsDao.class);
    private final PrimaryDataStoreDao pools = mock(PrimaryDataStoreDao.class);
    private final AccountManager accounts = mock(AccountManager.class);
    private final Account account = mock(Account.class);
    @Before public void setup() {
        ReflectionTestUtils.setField(manager, "detailsDao", details);
        ReflectionTestUtils.setField(manager, "poolDao", pools);
        ReflectionTestUtils.setField(manager, "accountManager", accounts);
        when(account.getId()).thenReturn(2L);
        CallContext.register(mock(User.class), account);
    }
    @After public void tearDown() { CallContext.unregister(); }
    @Test public void automaticDeploymentNeedsNoAdministratorOverride() {
        DeployVMCmd cmd = mock(DeployVMCmd.class);
        when(cmd.getRootStorageId()).thenReturn(null);
        when(cmd.getVolumeId()).thenReturn(null);
        when(cmd.getSnapshotId()).thenReturn(null);
        assertTrue(manager.prepare(cmd, null, null, null, null).isEmpty());
    }
    @Test public void dataDiskPoolCannotBypassAdministratorPermission() {
        DeployVMCmd cmd = mock(DeployVMCmd.class);
        com.cloud.vm.VmDiskInfo disk = mock(com.cloud.vm.VmDiskInfo.class);
        when(disk.getStoragePoolId()).thenReturn(9L);
        when(disk.getDeviceId()).thenReturn(1L);
        when(cmd.getRootStorageId()).thenReturn(null);
        when(cmd.getVolumeId()).thenReturn(null);
        when(cmd.getSnapshotId()).thenReturn(null);
        when(cmd.getDataDiskInfoList()).thenReturn(Collections.singletonList(disk));
        assertThrows(PermissionDeniedException.class, () -> manager.prepare(cmd, mock(DataCenter.class), account, mock(ServiceOffering.class), mock(VirtualMachineTemplate.class)));
    }
    private VmStorageSelectionManager uploadedManager(DeployVMCmd cmd) {
        VmStorageSelectionManager automatic = org.mockito.Mockito.spy(manager);
        com.cloud.storage.dao.VolumeDao volumes = mock(com.cloud.storage.dao.VolumeDao.class);
        com.cloud.storage.dao.DiskOfferingDao offerings = mock(com.cloud.storage.dao.DiskOfferingDao.class);
        com.cloud.storage.dao.VMTemplateDao templates = mock(com.cloud.storage.dao.VMTemplateDao.class);
        ReflectionTestUtils.setField(automatic, "volumeDao", volumes);
        ReflectionTestUtils.setField(automatic, "offeringDao", offerings);
        ReflectionTestUtils.setField(automatic, "templateDao", templates);
        ReflectionTestUtils.setField(automatic, "configurationManager", mock(com.cloud.configuration.ConfigurationManager.class));
        StorageManager storage = mock(StorageManager.class);
        ReflectionTestUtils.setField(automatic, "storageManager", storage);
        VolumeVO source = mock(VolumeVO.class);
        when(source.getState()).thenReturn(Volume.State.Uploaded);
        when(source.getSize()).thenReturn(1073741824L);
        when(source.getDiskOfferingId()).thenReturn(7L);
        when(cmd.getVolumeId()).thenReturn(4L);
        when(cmd.getRootStorageId()).thenReturn(null);
        when(cmd.getSnapshotId()).thenReturn(null);
        when(cmd.getDetails()).thenReturn(new java.util.HashMap<>());
        when(volumes.findById(4L)).thenReturn(source);
        DiskOfferingVO offering = mock(DiskOfferingVO.class);
        when(offering.isCustomized()).thenReturn(true);
        when(offerings.findById(7L)).thenReturn(offering);
        VMTemplateVO template = mock(VMTemplateVO.class);
        when(template.getId()).thenReturn(5L);
        when(template.getFormat()).thenReturn(Storage.ImageFormat.QCOW2);
        when(template.getHypervisorType()).thenReturn(com.cloud.hypervisor.Hypervisor.HypervisorType.KVM);
        when(templates.findByIdIncludingRemoved(5L)).thenReturn(template);
        StoragePoolVO pool = mock(StoragePoolVO.class);
        when(pool.getStatus()).thenReturn(StoragePoolStatus.Up);
        when(pools.findById(9L)).thenReturn(pool);
        when(storage.storagePoolHasEnoughSpace(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any())).thenReturn(true);
        when(storage.storagePoolHasEnoughIops(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any())).thenReturn(true);
        org.mockito.Mockito.doReturn(java.util.Map.of(9L, java.util.Set.of("host"))).when(automatic).candidates(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        return automatic;
    }
    private java.util.Map<Long, Long> prepareUploaded(VmStorageSelectionManager automatic, DeployVMCmd cmd) {
        VirtualMachineTemplate template = mock(VirtualMachineTemplate.class); when(template.getId()).thenReturn(5L);
        return automatic.prepare(cmd, mock(DataCenter.class), account, mock(ServiceOffering.class), template);
    }
    @Test public void uploadedAutomaticPlacementWorksWithoutAdministratorOrPodOverride() {
        DeployVMCmd cmd = mock(DeployVMCmd.class);
        VmStorageSelectionManager automatic = uploadedManager(cmd);
        assertEquals(java.util.Map.of(0L, 9L), prepareUploaded(automatic, cmd));
    }
    @Test public void uploadedAutomaticPlacementIncludesDataDiskCapacity() {
        DeployVMCmd cmd = mock(DeployVMCmd.class);
        VmStorageSelectionManager automatic = uploadedManager(cmd);
        DiskOfferingVO offering = mock(DiskOfferingVO.class); when(offering.isCustomized()).thenReturn(true);
        when(cmd.getDataDiskInfoList()).thenReturn(java.util.List.of(new com.cloud.vm.VmDiskInfo(offering, 1L, null, null, 1L)));
        StorageManager storage = (StorageManager) ReflectionTestUtils.getField(automatic, "storageManager");
        when(storage.storagePoolHasEnoughSpace(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> (Long) call.getArgument(0) <= 1073741824L);
        assertThrows(InvalidParameterValueException.class, () -> prepareUploaded(automatic, cmd));
        verifyNoInteractions(details);
    }
    @Test public void uploadedAutomaticPlacementRejectsMissingCompatibleHost() {
        DeployVMCmd cmd = mock(DeployVMCmd.class);
        VmStorageSelectionManager automatic = uploadedManager(cmd);
        org.mockito.Mockito.doReturn(Collections.emptyMap()).when(automatic).candidates(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertThrows(InvalidParameterValueException.class, () -> prepareUploaded(automatic, cmd));
        verifyNoInteractions(details);
    }
    @Test public void selectedPoolSurvivesMetadataOnlyFirstStart() {
        Volume volume = mock(Volume.class);
        VirtualMachine vm = mock(VirtualMachine.class);
        when(vm.getLastHostId()).thenReturn(null);
        when(volume.getId()).thenReturn(4L);
        when(volume.getDataCenterId()).thenReturn(1L);
        VolumeDetailVO detail = new VolumeDetailVO(4L, VmStorageSelectionService.REQUIRED_POOL, "pool-uuid", false);
        when(details.findDetail(4L, VmStorageSelectionService.REQUIRED_POOL)).thenReturn(detail);
        StoragePoolVO pool = mock(StoragePoolVO.class);
        when(pool.getId()).thenReturn(9L);
        when(pool.getStatus()).thenReturn(StoragePoolStatus.Up);
        when(pool.getDataCenterId()).thenReturn(1L);
        when(pools.findByUuid("pool-uuid")).thenReturn(pool);
        assertEquals(Long.valueOf(9), manager.requiredPool(volume, vm));
    }
    @Test public void missingPoolFailsClosedInsteadOfFallingBack() {
        Volume volume = mock(Volume.class);
        when(volume.getId()).thenReturn(4L);
        when(details.findDetail(4L, VmStorageSelectionService.REQUIRED_POOL)).thenReturn(
            new VolumeDetailVO(4L, VmStorageSelectionService.REQUIRED_POOL, "deleted", false));
        VirtualMachine vm = mock(VirtualMachine.class);
        when(vm.getLastHostId()).thenReturn(null);
        assertThrows(InvalidParameterValueException.class, () -> manager.requiredPool(volume, vm));
    }
    @Test public void firstStartChoiceDoesNotPinSubsequentMigration() {
        VirtualMachine vm = mock(VirtualMachine.class);
        when(vm.getLastHostId()).thenReturn(8L);
        assertNull(manager.requiredPool(mock(Volume.class), vm));
        verifyNoInteractions(details, pools);
    }

    @Test public void rootPoolCannotBypassAdministratorPermission() {
        DeployVMCmd cmd = mock(DeployVMCmd.class);
        when(cmd.getRootStorageId()).thenReturn(9L);
        assertThrows(PermissionDeniedException.class, () -> manager.prepare(cmd, null, account, null, null));
    }

    @Test public void existingRootVolumeCannotBeRetargetedByDeployParameter() {
        DeployVMCmd cmd = mock(DeployVMCmd.class);
        when(cmd.getRootStorageId()).thenReturn(9L);
        when(cmd.getVolumeId()).thenReturn(4L);
        com.cloud.storage.dao.VolumeDao volumes = mock(com.cloud.storage.dao.VolumeDao.class);
        org.springframework.test.util.ReflectionTestUtils.setField(manager, "volumeDao", volumes);
        VolumeVO source = mock(VolumeVO.class); when(source.getState()).thenReturn(Volume.State.Ready);
        when(volumes.findById(4L)).thenReturn(source);
        when(accounts.isRootAdmin(2L)).thenReturn(true);
        assertThrows(InvalidParameterValueException.class, () -> manager.prepare(cmd, null, account, null, null));
    }

    @Test public void maintenancePoolRejectsFirstDeployment() {
        Volume volume = mock(Volume.class);
        VirtualMachine vm = mock(VirtualMachine.class);
        when(vm.getLastHostId()).thenReturn(null);
        when(volume.getId()).thenReturn(4L);
        when(volume.getDataCenterId()).thenReturn(1L);
        when(details.findDetail(4L, VmStorageSelectionService.REQUIRED_POOL)).thenReturn(
            new VolumeDetailVO(4L, VmStorageSelectionService.REQUIRED_POOL, "pool-uuid", false));
        StoragePoolVO pool = mock(StoragePoolVO.class);
        when(pool.getStatus()).thenReturn(StoragePoolStatus.Maintenance);
        when(pool.getDataCenterId()).thenReturn(1L);
        when(pools.findByUuid("pool-uuid")).thenReturn(pool);
        assertThrows(InvalidParameterValueException.class, () -> manager.requiredPool(volume, vm));
    }

    @Test public void poolOutsideVolumeZoneRejectsFirstDeployment() {
        Volume volume = mock(Volume.class);
        VirtualMachine vm = mock(VirtualMachine.class);
        when(vm.getLastHostId()).thenReturn(null);
        when(volume.getId()).thenReturn(4L);
        when(volume.getDataCenterId()).thenReturn(1L);
        when(details.findDetail(4L, VmStorageSelectionService.REQUIRED_POOL)).thenReturn(
            new VolumeDetailVO(4L, VmStorageSelectionService.REQUIRED_POOL, "pool-uuid", false));
        StoragePoolVO pool = mock(StoragePoolVO.class);
        when(pool.getStatus()).thenReturn(StoragePoolStatus.Up);
        when(pool.getDataCenterId()).thenReturn(2L);
        when(pools.findByUuid("pool-uuid")).thenReturn(pool);
        assertThrows(InvalidParameterValueException.class, () -> manager.requiredPool(volume, vm));
    }

    @Test public void allSelectedTargetsSurviveFailureOfTheFirstDiskValidation() {
        com.cloud.storage.dao.VolumeDao volumes = mock(com.cloud.storage.dao.VolumeDao.class);
        com.cloud.service.dao.ServiceOfferingDao computes = mock(com.cloud.service.dao.ServiceOfferingDao.class);
        com.cloud.storage.dao.VMTemplateDao templates = mock(com.cloud.storage.dao.VMTemplateDao.class);
        com.cloud.storage.dao.DiskOfferingDao offerings = mock(com.cloud.storage.dao.DiskOfferingDao.class);
        com.cloud.dc.dao.DataCenterDao zones = mock(com.cloud.dc.dao.DataCenterDao.class);
        com.cloud.host.dao.HostDao hosts = mock(com.cloud.host.dao.HostDao.class);
        ReflectionTestUtils.setField(manager, "volumeDao", volumes);
        ReflectionTestUtils.setField(manager, "computeDao", computes);
        ReflectionTestUtils.setField(manager, "templateDao", templates);
        ReflectionTestUtils.setField(manager, "offeringDao", offerings);
        ReflectionTestUtils.setField(manager, "zoneDao", zones);
        ReflectionTestUtils.setField(manager, "hostDao", hosts);
        ReflectionTestUtils.setField(manager, "configurationManager", mock(com.cloud.configuration.ConfigurationManager.class));
        com.cloud.uservm.UserVm vm = mock(com.cloud.uservm.UserVm.class);
        when(vm.getId()).thenReturn(1L);
        when(vm.getAccountId()).thenReturn(2L);
        when(vm.getDataCenterId()).thenReturn(1L);
        when(vm.getServiceOfferingId()).thenReturn(1L);
        when(vm.getTemplateId()).thenReturn(1L);
        when(vm.getHypervisorType()).thenReturn(com.cloud.hypervisor.Hypervisor.HypervisorType.KVM);
        when(accounts.getAccount(2L)).thenReturn(account);
        when(computes.findById(1L)).thenReturn(mock(com.cloud.service.ServiceOfferingVO.class));
        when(templates.findById(1L)).thenReturn(mock(com.cloud.storage.VMTemplateVO.class));
        when(zones.findById(1L)).thenReturn(mock(com.cloud.dc.DataCenterVO.class));
        com.cloud.storage.DiskOfferingVO offering = mock(com.cloud.storage.DiskOfferingVO.class);
        when(offerings.findById(1L)).thenReturn(offering);
        VolumeVO root = mock(VolumeVO.class);
        VolumeVO data = mock(VolumeVO.class);
        when(root.getId()).thenReturn(10L);
        when(root.getVolumeType()).thenReturn(Volume.Type.ROOT);
        when(data.getId()).thenReturn(11L);
        when(data.getVolumeType()).thenReturn(Volume.Type.DATADISK);
        when(data.getDeviceId()).thenReturn(1L);
        when(root.getDiskOfferingId()).thenReturn(1L);
        when(data.getDiskOfferingId()).thenReturn(1L);
        when(volumes.findUsableVolumesForInstance(1L)).thenReturn(java.util.Arrays.asList(root, data));
        StoragePoolVO pool = mock(StoragePoolVO.class);
        when(pool.getUuid()).thenReturn("selected-pool");
        when(pools.findByIdIncludingRemoved(9L)).thenReturn(pool);
        when(hosts.listAllRoutingHostsByZoneAndHypervisorType(1L, com.cloud.hypervisor.Hypervisor.HypervisorType.KVM)).thenReturn(Collections.emptyList());
        java.util.Map<Long, Long> selected = new java.util.HashMap<>();
        selected.put(0L, 9L);
        selected.put(1L, 9L);
        assertThrows(InvalidParameterValueException.class, () -> manager.bind(vm, selected));
        verify(details).addDetail(10L, VmStorageSelectionService.REQUIRED_POOL, "selected-pool", false);
        verify(details).addDetail(11L, VmStorageSelectionService.REQUIRED_POOL, "selected-pool", false);
    }
}
