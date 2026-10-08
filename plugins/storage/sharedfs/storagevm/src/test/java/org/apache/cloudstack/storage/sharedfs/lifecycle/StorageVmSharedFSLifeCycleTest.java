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

package org.apache.cloudstack.storage.sharedfs.lifecycle;

import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.network.Network;
import com.cloud.offering.ServiceOffering;
import com.cloud.resource.ResourceManager;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeApiService;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.LaunchPermissionDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.uservm.UserVm;
import com.cloud.utils.FileUtil;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.UserVmManager;
import com.cloud.vm.UserVmService;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VirtualMachineManager;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.UserVmDao;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.storage.sharedfs.SharedFS;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;


import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class StorageVmSharedFSLifeCycleTest {
    @Mock
    private AccountManager accountMgr;

    @Mock
    protected ResourceManager resourceMgr;

    @Mock
    private VirtualMachineManager virtualMachineManager;

    @Mock
    private VolumeApiService volumeApiService;

    @Mock
    protected UserVmService userVmService;

    @Mock
    protected UserVmManager userVmManager;

    @Mock
    private DataCenterDao dataCenterDao;

    @Mock
    private VMTemplateDao templateDao;

    @Mock
    VolumeDao volumeDao;

    @Mock
    private UserVmDao userVmDao;

    @Mock
    NicDao nicDao;

    @Mock
    ServiceOfferingDao serviceOfferingDao;

    @Mock
    private DiskOfferingDao diskOfferingDao;

    @Mock
    protected LaunchPermissionDao launchPermissionDao;

    @Spy
    @InjectMocks
    StorageVmSharedFSLifeCycle lifeCycle;

    private static final long s_ownerId = 1L;
    private static final long s_zoneId = 2L;
    private static final long s_diskOfferingId = 3L;
    private static final long s_serviceOfferingId = 4L;
    private static final long s_templateId = 5L;
    private static final long s_volumeId = 6L;
    private static final long s_vmId = 7L;
    private static final long s_networkId = 8L;
    private static final long s_storageId = 9L;
    private static final long s_size = 10L;
    private static final long s_minIops = 1000L;
    private static final long s_maxIops = 2000L;
    private static final String s_fsFormat = "EXT4";
    private static final String s_name = "TestSharedFS";

    private MockedStatic<FileUtil> fileUtilMocked;
    private MockedStatic<CallContext> callContextMocked;

    private AutoCloseable closeable;

    @Before
    public void setUp() {
        closeable = MockitoAnnotations.openMocks(this);
        callContextMocked = mockStatic(CallContext.class);
        CallContext callContextMock = mock(CallContext.class);
        callContextMocked.when(CallContext::current).thenReturn(callContextMock);
        CallContext vmContext = mock(CallContext.class);
        when(callContextMock.register(CallContext.current(), ApiCommandResourceType.VirtualMachine)).thenReturn(vmContext);

        fileUtilMocked = mockStatic(FileUtil.class);
        fileUtilMocked.when(() -> FileUtil.readResourceFile("conf/fsvm-init.yml")).thenReturn("");
    }

    @After
    public void tearDown() throws Exception {
        fileUtilMocked.close();
        callContextMocked.close();
        closeable.close();
    }

    @Test
    public void testCheckPrerequisites() {
        DataCenterVO zone = mock(DataCenterVO.class);
        when(zone.getId()).thenReturn(s_zoneId);
        ServiceOfferingVO serviceOfferingVO = mock(ServiceOfferingVO.class);
        when(serviceOfferingVO.getCpu()).thenReturn(4);
        when(serviceOfferingVO.getRamSize()).thenReturn(1024);
        when(serviceOfferingVO.isOfferHA()).thenReturn(true);
        when(serviceOfferingVO.isDynamicScalingEnabled()).thenReturn(true);
        when(serviceOfferingDao.findById(s_serviceOfferingId)).thenReturn(serviceOfferingVO);
        org.mockito.Mockito.doReturn(true).when(lifeCycle).zoneScalingEnabled(s_zoneId);
        when(resourceMgr.getSupportedHypervisorTypes(s_zoneId,false,null)).thenReturn(List.of(Hypervisor.HypervisorType.KVM));
        VMTemplateVO template=mock(VMTemplateVO.class);
        when(template.isDynamicallyScalable()).thenReturn(true);
        when(templateDao.findSystemVMReadyTemplate(s_zoneId,Hypervisor.HypervisorType.KVM,ResourceManager.SystemVmPreferredArchitecture.defaultValue())).thenReturn(template);
        lifeCycle.checkPrerequisites(zone, s_serviceOfferingId);
    }

    @Test
    public void testCheckPrerequisitesMinCpuException() {
        DataCenterVO zone = mock(DataCenterVO.class);
        when(zone.getId()).thenReturn(s_zoneId);
        ServiceOfferingVO serviceOfferingVO = mock(ServiceOfferingVO.class);
        when(serviceOfferingDao.findById(s_serviceOfferingId)).thenReturn(serviceOfferingVO);
        when(serviceOfferingVO.getCpu()).thenReturn(1);
        InvalidParameterValueException exception = Assert.assertThrows(InvalidParameterValueException.class, () -> lifeCycle.checkPrerequisites(zone, s_serviceOfferingId));
        Assert.assertTrue(exception.getMessage().contains("MINIMUM_CPU_REQUIRED"));
    }

    @Test
    public void testCheckPrerequisitesCustomCpuException() {
        DataCenterVO zone = mock(DataCenterVO.class);
        ServiceOfferingVO serviceOfferingVO = mock(ServiceOfferingVO.class);
        when(serviceOfferingDao.findById(s_serviceOfferingId)).thenReturn(serviceOfferingVO);
        when(serviceOfferingVO.getCpu()).thenReturn(null);

        InvalidParameterValueException exception = Assert.assertThrows(InvalidParameterValueException.class, () -> lifeCycle.checkPrerequisites(zone, s_serviceOfferingId));
        Assert.assertTrue(exception.getMessage().contains("FIXED_CPU_REQUIRED"));
    }

    @Test
    public void testCheckPrerequisitesMinRamException() {
        DataCenterVO zone = mock(DataCenterVO.class);
        when(zone.getId()).thenReturn(s_zoneId);
        ServiceOfferingVO serviceOfferingVO = mock(ServiceOfferingVO.class);
        when(serviceOfferingDao.findById(s_serviceOfferingId)).thenReturn(serviceOfferingVO);
        when(serviceOfferingVO.getCpu()).thenReturn(4);
        when(serviceOfferingVO.getRamSize()).thenReturn(512);
        InvalidParameterValueException exception = Assert.assertThrows(InvalidParameterValueException.class, () -> lifeCycle.checkPrerequisites(zone, s_serviceOfferingId));
        Assert.assertTrue(exception.getMessage().contains("MINIMUM_MEMORY_REQUIRED"));
    }

    @Test
    public void testCheckPrerequisitesCustomRamException() {
        DataCenterVO zone = mock(DataCenterVO.class);
        ServiceOfferingVO serviceOfferingVO = mock(ServiceOfferingVO.class);
        when(serviceOfferingDao.findById(s_serviceOfferingId)).thenReturn(serviceOfferingVO);
        when(serviceOfferingVO.getCpu()).thenReturn(4);
        when(serviceOfferingVO.getRamSize()).thenReturn(null);

        InvalidParameterValueException exception = Assert.assertThrows(InvalidParameterValueException.class, () -> lifeCycle.checkPrerequisites(zone, s_serviceOfferingId));
        Assert.assertTrue(exception.getMessage().contains("FIXED_MEMORY_REQUIRED"));
    }

    @Test
    public void testCheckPrerequisitesHAException() {
        DataCenterVO zone = mock(DataCenterVO.class);
        when(zone.getId()).thenReturn(s_zoneId);
        ServiceOfferingVO serviceOfferingVO = mock(ServiceOfferingVO.class);
        when(serviceOfferingDao.findById(s_serviceOfferingId)).thenReturn(serviceOfferingVO);
        when(serviceOfferingVO.getCpu()).thenReturn(4);
        when(serviceOfferingVO.getRamSize()).thenReturn(1024);
        InvalidParameterValueException exception = Assert.assertThrows(InvalidParameterValueException.class, () -> lifeCycle.checkPrerequisites(zone, s_serviceOfferingId));
        Assert.assertTrue(exception.getMessage().contains("HA_REQUIRED"));
    }

    private SharedFS prepareDeploySharedFS() throws ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException {
        SharedFS sharedFS = mock(SharedFS.class);
        when(sharedFS.getDataCenterId()).thenReturn(s_zoneId);
        when(sharedFS.getName()).thenReturn(s_name);
        when(sharedFS.getServiceOfferingId()).thenReturn(s_serviceOfferingId);
        when(sharedFS.getFsType()).thenReturn(SharedFS.FileSystemType.valueOf(s_fsFormat));
        when(sharedFS.getNetworkMode()).thenReturn(SharedFS.NetworkMode.DHCP);
        when(sharedFS.getAccountId()).thenReturn(s_ownerId);

        DataCenterVO zone = mock(DataCenterVO.class);
        when(dataCenterDao.findById(s_zoneId)).thenReturn(zone);
        when(resourceMgr.getSupportedHypervisorTypes(s_zoneId, false, null)).thenReturn(List.of(Hypervisor.HypervisorType.KVM));

        ServiceOfferingVO serviceOffering = mock(ServiceOfferingVO.class);
        when(serviceOfferingDao.findById(s_serviceOfferingId)).thenReturn(serviceOffering);

        VMTemplateVO template = mock(VMTemplateVO.class);
        when(templateDao.findSystemVMReadyTemplate(s_zoneId, Hypervisor.HypervisorType.KVM, ResourceManager.SystemVmPreferredArchitecture.defaultValue())).thenReturn(template);
        when(template.getId()).thenReturn(s_templateId);
        when(template.isDynamicallyScalable()).thenReturn(true);

        return sharedFS;
    }

    @Test
    public void testDeploySharedFS() throws ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException, IOException, OperationTimedoutException {
        SharedFS sharedFS = prepareDeploySharedFS();
        when(sharedFS.getAccountId()).thenReturn(s_ownerId);

        Account owner = mock(Account.class);
        when(owner.getId()).thenReturn(s_ownerId);
        when(accountMgr.getActiveAccountById(s_ownerId)).thenReturn(owner);

        UserVm vm = mock(UserVm.class);
        when(vm.getId()).thenReturn(s_vmId);
        when(userVmService.createAdvancedVirtualMachine(
                any(DataCenter.class), any(ServiceOffering.class), any(VirtualMachineTemplate.class), anyList(), any(Account.class), anyString(),
                anyString(), anyLong(), anyLong(), any(), isNull(), any(Hypervisor.HypervisorType.class), any(BaseCmd.HTTPMethod.class), anyString(),
                isNull(), isNull(), anyList(), isNull(), any(Network.IpAddresses.class), isNull(), isNull(), isNull(),
                anyMap(), isNull(), isNull(), isNull(), isNull(),
                anyBoolean(), anyString(), isNull(), isNull(), isNull(), isNull())).thenReturn(vm);

        VolumeVO rootVol = mock(VolumeVO.class);
        when(rootVol.getVolumeType()).thenReturn(Volume.Type.ROOT);
        when(rootVol.getName()).thenReturn("ROOT-1");
        VolumeVO dataVol = mock(VolumeVO.class);
        when(dataVol.getId()).thenReturn(s_volumeId);
        when(dataVol.getName()).thenReturn("DATA-1");
        when(dataVol.getVolumeType()).thenReturn(Volume.Type.DATADISK);
        when(dataVol.getPoolId()).thenReturn(s_storageId);
        when(volumeDao.findByInstance(s_vmId)).thenReturn(List.of(rootVol, dataVol));

         Pair<Long, Long> result = lifeCycle.deploySharedFS(sharedFS, s_networkId, s_diskOfferingId, s_storageId, s_size, s_minIops, s_maxIops);
         Assert.assertEquals(Optional.ofNullable(result.first()), Optional.ofNullable(s_volumeId));
         Assert.assertEquals(Optional.ofNullable(result.second()), Optional.ofNullable(s_vmId));
    }

    @Test(expected = CloudRuntimeException.class)
    public void testDeploySharedFSHypervisorNotFound() throws ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException, IOException, OperationTimedoutException {
        SharedFS sharedFS = mock(SharedFS.class);
        when(sharedFS.getDataCenterId()).thenReturn(s_zoneId);
        when(sharedFS.getName()).thenReturn(s_name);
        when(sharedFS.getServiceOfferingId()).thenReturn(s_serviceOfferingId);
        when(sharedFS.getFsType()).thenReturn(SharedFS.FileSystemType.valueOf(s_fsFormat));
        when(sharedFS.getAccountId()).thenReturn(s_ownerId);

        when(accountMgr.getActiveAccountById(s_ownerId)).thenReturn(null);
        DataCenterVO zone = mock(DataCenterVO.class);
        when(dataCenterDao.findById(s_zoneId)).thenReturn(zone);
        lifeCycle.deploySharedFS(sharedFS, s_networkId, s_diskOfferingId, s_storageId, s_size, s_minIops, s_maxIops);
    }

    @Test(expected = CloudRuntimeException.class)
    public void testDeploySharedFSTemplateNotFound() throws ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException, IOException, OperationTimedoutException {
        SharedFS sharedFS = mock(SharedFS.class);
        when(sharedFS.getDataCenterId()).thenReturn(s_zoneId);
        when(sharedFS.getName()).thenReturn(s_name);
        when(sharedFS.getServiceOfferingId()).thenReturn(s_serviceOfferingId);
        when(sharedFS.getFsType()).thenReturn(SharedFS.FileSystemType.valueOf(s_fsFormat));
        when(sharedFS.getAccountId()).thenReturn(s_ownerId);

        when(accountMgr.getActiveAccountById(s_ownerId)).thenReturn(null);
        DataCenterVO zone = mock(DataCenterVO.class);
        when(dataCenterDao.findById(s_zoneId)).thenReturn(zone);
        when(resourceMgr.getSupportedHypervisorTypes(s_zoneId, false, null)).thenReturn(List.of(Hypervisor.HypervisorType.KVM));

        lifeCycle.deploySharedFS(sharedFS, s_networkId, s_diskOfferingId, s_storageId, s_size, s_minIops, s_maxIops);
    }

    @Test
    public void testDeleteSharedFS() throws ResourceUnavailableException {
        SharedFS sharedFS = mock(SharedFS.class);
        when(sharedFS.getVmId()).thenReturn(s_vmId);
        when(sharedFS.getVolumeId()).thenReturn(s_volumeId);

        UserVmVO vm = mock(UserVmVO.class);
        when(vm.getId()).thenReturn(s_vmId);
        when(vm.getState()).thenReturn(com.cloud.vm.VirtualMachine.State.Stopped);
        when(userVmDao.findById(s_vmId)).thenReturn(vm);
        when(userVmService.destroyVm(s_vmId, true)).thenReturn(vm);
        when(userVmManager.expunge(vm)).thenReturn(true);

        VolumeVO volume = mock(VolumeVO.class);
        when(volumeDao.findById(s_volumeId)).thenReturn(volume);
        when(volumeDao.findByIdIncludingRemoved(s_volumeId)).thenReturn(volume);
        when(volume.getVolumeType()).thenReturn(Volume.Type.DATADISK);
        when(volume.getInstanceId()).thenReturn(null);

        Assert.assertEquals(lifeCycle.deleteSharedFS(sharedFS), true);
        org.mockito.Mockito.verify(volumeApiService, org.mockito.Mockito.never()).destroyVolume(anyLong(), any(), anyBoolean(), anyBoolean(), any());
    }

    @Test
    public void testReDeploySharedFS() throws ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException, IOException, OperationTimedoutException {
        SharedFS sharedFS = mock(SharedFS.class);
        Long vmId = 1L;
        when(sharedFS.getVmId()).thenReturn(vmId);
        UserVm vm = mock(UserVm.class);
        when(virtualMachineManager.restoreVirtualMachine(vmId, null, null, true, null)).thenReturn(vm);
        boolean result = lifeCycle.reDeploySharedFS(sharedFS);
        Assert.assertEquals(result, true);
    }
    @Test public void missingReviewedDataBlocksVmExpungeAndDeletion() {
        SharedFS fs=mock(SharedFS.class);when(fs.getVmId()).thenReturn(null);
        Assert.assertThrows(CloudRuntimeException.class,()->lifeCycle.deleteSharedFS(fs,SharedFS.DataVolumePolicy.DELETE_VOLUMES,java.util.Set.of(42L)));
        org.mockito.Mockito.verifyNoInteractions(userVmService,volumeApiService);
    }
    @Test public void externallyRemovedDataCannotClaimPreservation() {
        SharedFS fs=mock(SharedFS.class);when(fs.getVmId()).thenReturn(null);VolumeVO removed=mock(VolumeVO.class);when(removed.getRemoved()).thenReturn(new java.util.Date());when(volumeDao.findByIdIncludingRemoved(42L)).thenReturn(removed);
        Assert.assertThrows(CloudRuntimeException.class,()->lifeCycle.deleteSharedFS(fs,SharedFS.DataVolumePolicy.PRESERVE_VOLUMES,java.util.Set.of(42L)));
        org.mockito.Mockito.verifyNoInteractions(userVmService,volumeApiService);
    }
    @Test public void secondDetachFailurePreservesVmAndRetryDetachesOnlyRemainingData() throws Exception {
        SharedFS fs=mock(SharedFS.class);when(fs.getVmId()).thenReturn(s_vmId);when(fs.getAccountId()).thenReturn(2L);
        UserVmVO vm=mock(UserVmVO.class);when(vm.getState()).thenReturn(com.cloud.vm.VirtualMachine.State.Stopped);when(vm.getId()).thenReturn(s_vmId);when(userVmDao.findById(s_vmId)).thenReturn(vm);
        VolumeVO first=mock(VolumeVO.class),second=mock(VolumeVO.class);
        java.util.concurrent.atomic.AtomicBoolean a=new java.util.concurrent.atomic.AtomicBoolean(true),b=new java.util.concurrent.atomic.AtomicBoolean(true);
        when(first.getId()).thenReturn(42L);when(second.getId()).thenReturn(43L);
        when(first.getVolumeType()).thenReturn(Volume.Type.DATADISK);when(second.getVolumeType()).thenReturn(Volume.Type.DATADISK);when(first.getAccountId()).thenReturn(2L);when(second.getAccountId()).thenReturn(2L);
        when(first.getInstanceId()).thenAnswer(call->a.get()?s_vmId:null);when(second.getInstanceId()).thenAnswer(call->b.get()?s_vmId:null);
        when(volumeDao.findByIdIncludingRemoved(42L)).thenReturn(first);when(volumeDao.findByIdIncludingRemoved(43L)).thenReturn(second);
        when(volumeDao.findById(42L)).thenReturn(first);when(volumeDao.findById(43L)).thenReturn(second);when(volumeDao.findByInstanceAndType(s_vmId,Volume.Type.DATADISK)).thenAnswer(call->b.get()?(a.get()?List.of(first,second):List.of(second)):List.of());
        org.mockito.Mockito.doAnswer(call->{a.set(false);return null;}).when(volumeApiService).detachVolumeViaDestroyVM(s_vmId,42L);
        Assert.assertThrows(CloudRuntimeException.class,()->lifeCycle.deleteSharedFS(fs,SharedFS.DataVolumePolicy.PRESERVE_VOLUMES,new java.util.LinkedHashSet<>(List.of(42L,43L))));
        org.mockito.Mockito.verify(userVmService,org.mockito.Mockito.never()).destroyVm(anyLong(),anyBoolean());
        org.mockito.Mockito.doAnswer(call->{b.set(false);return null;}).when(volumeApiService).detachVolumeViaDestroyVM(s_vmId,43L);when(userVmService.destroyVm(s_vmId,true)).thenReturn(vm);when(userVmManager.expunge(vm)).thenReturn(true);
        Assert.assertTrue(lifeCycle.deleteSharedFS(fs,SharedFS.DataVolumePolicy.PRESERVE_VOLUMES,new java.util.LinkedHashSet<>(List.of(42L,43L))));
        org.mockito.Mockito.verify(volumeApiService,org.mockito.Mockito.times(1)).detachVolumeViaDestroyVM(s_vmId,42L);
        org.mockito.Mockito.verify(volumeApiService,org.mockito.Mockito.times(2)).detachVolumeViaDestroyVM(s_vmId,43L);
        org.mockito.Mockito.verify(volumeApiService,org.mockito.Mockito.never()).destroyVolume(anyLong(),any(),anyBoolean(),anyBoolean(),any());
    }

}
