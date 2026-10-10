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

package org.apache.cloudstack.storage.sharedfs;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.Optional;

import org.apache.cloudstack.api.ResponseObject;
import org.apache.cloudstack.api.command.user.storage.sharedfs.ChangeSharedFSDiskOfferingCmd;
import org.apache.cloudstack.api.command.user.storage.sharedfs.ChangeSharedFSServiceOfferingCmd;
import org.apache.cloudstack.api.command.user.storage.sharedfs.CreateSharedFSCmd;
import org.apache.cloudstack.api.command.user.storage.sharedfs.DestroySharedFSCmd;
import org.apache.cloudstack.api.command.user.storage.sharedfs.ListSharedFSCmd;
import org.apache.cloudstack.api.command.user.storage.sharedfs.UpdateSharedFSCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.storage.sharedfs.dao.SharedFSDao;
import org.apache.cloudstack.storage.sharedfs.query.dao.SharedFSJoinDao;
import org.apache.cloudstack.storage.sharedfs.query.vo.SharedFSJoinVO;
import org.apache.cloudstack.storage.dataservice.StorageServiceGuestCommandDispatcher;
import org.apache.cloudstack.storage.dataservice.StorageServiceGuestCommandResult;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.configuration.ConfigurationManager;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ManagementServerException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.exception.VirtualMachineMigrationException;
import com.cloud.network.Network;
import com.cloud.network.NetworkModel;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.org.Grouping;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.VolumeApiService;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.utils.Pair;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.fsm.NoTransitionException;
import com.cloud.utils.fsm.StateMachine2;
import com.cloud.vm.NicVO;
import com.cloud.vm.dao.NicDao;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;

@RunWith(MockitoJUnitRunner.class)
public class SharedFSServiceImplTest {

    @Mock
    private AccountManager accountMgr;

    @Mock
    private SharedFSDao sharedFSDao;

    @Mock
    private SharedFSJoinDao sharedFSJoinDao;

    @Mock
    private DataCenterDao dataCenterDao;

    @Mock
    private DiskOfferingDao diskOfferingDao;

    @Mock
    VolumeDao volumeDao;

    @Mock
    com.cloud.vm.dao.VMInstanceDao vmInstanceDao;

    @Mock
    PrimaryDataStoreDao storagePoolDao;

    @Mock
    NicDao nicDao;

    @Mock
    NetworkDao networkDao;
    @Mock
    org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService recordedNetworkOrchestration;

    @Mock
    NetworkModel networkModel;

    @Mock
    private ConfigurationManager configMgr;

    @Mock
    private VolumeApiService volumeApiService;

    @Mock
    private SharedFSProvider provider;

    @Mock
    private SharedFSLifeCycle lifeCycle;

    @Mock
    private StorageServiceGuestCommandDispatcher guestCommandDispatcher;

    @Mock
    private com.cloud.service.dao.ServiceOfferingDao serviceOfferingDao;

    @Mock
    private org.apache.cloudstack.storage.dataservice.dao.StorageFileShareDao storageFileShareDao;
    @Mock
    private org.apache.cloudstack.storage.dataservice.dao.StorageBlockTargetDao storageBlockTargetDao;

    @Mock
    org.apache.cloudstack.storage.dataservice.dao.StorageServiceTemplateUpgradeDao storageTemplateUpgradeDao;
    @Mock
    org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao storageOperationDao;
    @Mock
    com.cloud.storage.dao.VolumeDetailsDao volumeDetailsDao;
    @Mock
    org.apache.cloudstack.storage.dataservice.dao.StorageServiceInstanceDao storageServiceInstanceDao;

    @Spy
    @InjectMocks
    private SharedFSServiceImpl sharedFSServiceImpl;

    private static final long s_ownerId = 1L;
    private static final long s_zoneId = 2L;
    private static final long s_diskOfferingId = 3L;
    private static final long s_serviceOfferingId = 4L;
    private static final long s_domainId = 5L;
    private static final long s_volumeId = 6L;
    private static final long s_vmId = 7L;
    private static final long s_networkId = 8L;
    private static final long s_sharedFSId = 9L;
    private static final long s_storageId = 11L;
    private static final long s_size = 10L;
    private static final long s_minIops = 1000L;
    private static final long s_maxIops = 2000L;
    private static final String s_providerName = "SHAREDFSVM";
    private static final String s_fsFormat = "EXT4";
    private static final String s_name = "TestSharedFS";
    private static final String s_description = "Test Description";

    @Mock
    Account owner;
    @Mock
    protected StateMachine2<SharedFS.State, SharedFS.Event, SharedFS> _stateMachine;

    private MockedStatic<CallContext> callContextMocked;

    private AutoCloseable closeable;

    @Before
    public void setUp() {
        closeable = MockitoAnnotations.openMocks(this);
        callContextMocked = mockStatic(CallContext.class);
        CallContext callContextMock = mock(CallContext.class);
        callContextMocked.when(CallContext::current).thenReturn(callContextMock);
        when(callContextMock.getCallingAccount()).thenReturn(owner);
        when(accountMgr.getActiveAccountById(s_ownerId)).thenReturn(owner);

        Map<String, SharedFSProvider> mockProviderMap = new HashMap<>();
        mockProviderMap.put(s_providerName, provider);
        ReflectionTestUtils.setField(sharedFSServiceImpl, "sharedFSProviderMap", mockProviderMap);
        when(sharedFSServiceImpl.getSharedFSProvider(s_providerName)).thenReturn(provider);
        when(provider.getSharedFSLifeCycle()).thenReturn(lifeCycle);
        when(lifeCycle.stopSharedFS(any(),any())).thenReturn(true);
        Mockito.lenient().when(sharedFSDao.update(Mockito.anyLong(),any())).thenReturn(true);
        ReflectionTestUtils.setField(sharedFSServiceImpl, "sharedFSStateMachine", _stateMachine);
        com.cloud.service.ServiceOfferingVO sparseService = mock(com.cloud.service.ServiceOfferingVO.class);
        when(sparseService.getDiskOfferingId()).thenReturn(124L);
        when(serviceOfferingDao.findById(s_serviceOfferingId)).thenReturn(sparseService);
        DiskOfferingVO sparseRootOffering = mock(DiskOfferingVO.class);
        when(sparseRootOffering.getProvisioningType()).thenReturn(com.cloud.storage.Storage.ProvisioningType.SPARSE);
        when(diskOfferingDao.findById(124L)).thenReturn(sparseRootOffering);
        VolumeVO allocatedSparseRoot = mock(VolumeVO.class);
        when(allocatedSparseRoot.getProvisioningType()).thenReturn(com.cloud.storage.Storage.ProvisioningType.SPARSE);
        when(volumeDao.findByInstanceAndType(s_vmId,Volume.Type.ROOT)).thenReturn(java.util.List.of(allocatedSparseRoot));

    }

    @After
    public void tearDown() throws Exception {
        callContextMocked.close();
        closeable.close();
    }

    private CreateSharedFSCmd getMockCreateSharedFSCmd() {
        CreateSharedFSCmd cmd = mock(CreateSharedFSCmd.class);
        when(cmd.getTemplateId()).thenReturn(null);
        when(cmd.getEntityOwnerId()).thenReturn(s_ownerId);
        when(cmd.getZoneId()).thenReturn(s_zoneId);
        when(cmd.getDiskOfferingId()).thenReturn(s_diskOfferingId);
        when(cmd.getStorageId()).thenReturn(s_storageId);
        when(cmd.getSize()).thenReturn(s_size);
        when(cmd.getMinIops()).thenReturn(s_minIops);
        when(cmd.getMaxIops()).thenReturn(s_maxIops);
        when(cmd.getSharedFSProviderName()).thenReturn(s_providerName);
        when(cmd.getServiceOfferingId()).thenReturn(s_serviceOfferingId);
        when(cmd.getNetworkId()).thenReturn(s_networkId);
        when(cmd.getFsFormat()).thenReturn(s_fsFormat);
        when(cmd.getNetworkMode()).thenReturn(SharedFS.NetworkMode.DHCP);
        return cmd;
    }

    private SharedFSVO getMockSharedFS() {
        SharedFSVO sharedFS = new SharedFSVO(s_name, s_description, s_domainId, s_ownerId, s_zoneId,
                s_providerName, SharedFS.Protocol.NFS, SharedFS.FileSystemType.valueOf(s_fsFormat), s_serviceOfferingId);
        return sharedFS;
    }

    @Test
    public void testExistingInitialVolumeRejectsWrongOwnerZoneStateAndAttachment() throws Exception {
        VolumeVO volume=mock(VolumeVO.class);
        when(volumeDao.findById(6L)).thenReturn(volume);
        when(volume.getVolumeType()).thenReturn(Volume.Type.DATADISK);when(volume.getState()).thenReturn(Volume.State.Ready);
        when(volume.getAccountId()).thenReturn(1L);when(volume.getDataCenterId()).thenReturn(2L);when(volume.getPoolId()).thenReturn(11L);when(volume.getSize()).thenReturn(20L<<30);
        when(volume.getInstanceId()).thenReturn(7L);
        Assert.assertThrows(InvalidParameterValueException.class,()->sharedFSServiceImpl.validateExistingInitialVolume(6L,1L,2L,-1));
        when(volume.getInstanceId()).thenReturn(null);when(volume.getAccountId()).thenReturn(9L);
        Assert.assertThrows(InvalidParameterValueException.class,()->sharedFSServiceImpl.validateExistingInitialVolume(6L,1L,2L,-1));
        when(volume.getAccountId()).thenReturn(1L);when(volume.getDataCenterId()).thenReturn(3L);
        Assert.assertThrows(InvalidParameterValueException.class,()->sharedFSServiceImpl.validateExistingInitialVolume(6L,1L,2L,-1));
        when(volume.getDataCenterId()).thenReturn(2L);when(volume.getState()).thenReturn(Volume.State.Allocated);
        Assert.assertThrows(InvalidParameterValueException.class,()->sharedFSServiceImpl.validateExistingInitialVolume(6L,1L,2L,-1));
        verify(lifeCycle,never()).deployWithExistingVolume(any(),any(),any());
    }

    @Test
    public void testExistingFilesystemIsAuthoritativeAndNeverFormats() {
        SharedFSVO fs=getMockSharedFS();fs.setVmId(7L);fs.setVolumeId(6L);
        VolumeVO volume=mock(VolumeVO.class);when(volumeDao.findById(6L)).thenReturn(volume);
        when(volume.getInstanceId()).thenReturn(7L);when(volume.getUuid()).thenReturn("volume-uuid");when(volume.getSize()).thenReturn(20L<<30);
        org.apache.cloudstack.storage.dataservice.StorageServiceGuestCommandResult observed=mock(org.apache.cloudstack.storage.dataservice.StorageServiceGuestCommandResult.class);
        when(observed.isSuccess()).thenReturn(true);when(observed.getResultJson()).thenReturn("{\"success\":true,\"volumeUuid\":\"volume-uuid\",\"filesystem\":\"ext4\"}");
        when(guestCommandDispatcher.dispatch(any())).thenAnswer(invocation->{
            org.apache.cloudstack.storage.dataservice.StorageServiceGuestCommand command=invocation.getArgument(0);
            Assert.assertTrue(command.getPayload().contains("MOUNT_EXISTING"));Assert.assertFalse(command.getPayload().contains("FORMAT"));return observed;
        });
        sharedFSServiceImpl.inspectExistingInitialVolume(fs);
        Assert.assertEquals(SharedFS.FileSystemType.EXT4,fs.getFsType());Assert.assertEquals("MOUNTED_EXISTING",fs.getInitialImportState());
    }

    @Test
    public void testFailedInitialAttachmentCleanupPreservesData() {
        SharedFSVO fs=getMockSharedFS();ReflectionTestUtils.setField(fs,"id",9L);fs.setVmId(7L);fs.setVolumeId(6L);
        VolumeVO volume=mock(VolumeVO.class);when(volumeDao.findById(6L)).thenReturn(volume);when(volume.getInstanceId()).thenReturn(7L);when(volume.getId()).thenReturn(6L);when(volume.getUuid()).thenReturn("22222222-2222-4222-8222-222222222222");when(volume.getVolumeType()).thenReturn(Volume.Type.DATADISK);when(volume.getAccountId()).thenReturn(s_ownerId);when(volume.getDomainId()).thenReturn(s_domainId);when(volume.getDataCenterId()).thenReturn(s_zoneId);when(volume.getPoolId()).thenReturn(null);when(volume.getSize()).thenReturn(1024L);when(sharedFSDao.update(Mockito.anyLong(),any())).thenReturn(true);
        when(lifeCycle.deleteSharedFS(fs,SharedFS.DataVolumePolicy.PRESERVE_VOLUMES,Set.of(6L))).thenReturn(true);
        sharedFSServiceImpl.cleanupFailedInitialVolume(fs,lifeCycle,new CloudRuntimeException("inspection failed"));
        verify(lifeCycle).deleteSharedFS(fs,SharedFS.DataVolumePolicy.PRESERVE_VOLUMES,Set.of(6L));verify(sharedFSDao).remove(fs.getId());
    }

    @Test
    public void testDeploySharedFS() throws ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException, NoTransitionException, OperationTimedoutException {
        CreateSharedFSCmd cmd = getMockCreateSharedFSCmd();
        DiskOfferingVO sparseData=mock(DiskOfferingVO.class);when(sparseData.getProvisioningType()).thenReturn(com.cloud.storage.Storage.ProvisioningType.SPARSE);when(diskOfferingDao.findById(s_diskOfferingId)).thenReturn(sparseData);

        SharedFSVO sharedFS = getMockSharedFS();
        when(sharedFSDao.findById(0L)).thenReturn(sharedFS);

        Pair<Long, Long> result = new Pair<>(s_volumeId, s_vmId);
        when(lifeCycle.deploySharedFS(sharedFS, s_networkId, s_diskOfferingId, s_storageId, s_size, s_minIops, s_maxIops)).thenReturn(result);
        when(sharedFSDao.update(sharedFS.getId(), sharedFS)).thenReturn(true);

        Assert.assertEquals(sharedFSServiceImpl.deploySharedFS(cmd), sharedFS);
        Assert.assertEquals(Optional.ofNullable(sharedFS.getVmId()), Optional.ofNullable(s_vmId));
        Assert.assertEquals(Optional.ofNullable(sharedFS.getVolumeId()), Optional.ofNullable(s_volumeId));
        verify(_stateMachine, times(1)).transitTo(sharedFS, SharedFS.Event.OperationSucceeded, null, sharedFSDao);
    }

    @Test
    public void testDeploySharedFSException() throws ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException, NoTransitionException, OperationTimedoutException {
        CreateSharedFSCmd cmd = getMockCreateSharedFSCmd();
        DiskOfferingVO sparseData=mock(DiskOfferingVO.class);when(sparseData.getProvisioningType()).thenReturn(com.cloud.storage.Storage.ProvisioningType.SPARSE);when(diskOfferingDao.findById(s_diskOfferingId)).thenReturn(sparseData);

        SharedFSVO sharedFS = getMockSharedFS();
        when(sharedFSDao.findById(0L)).thenReturn(sharedFS);

        when(lifeCycle.deploySharedFS(sharedFS, s_networkId, s_diskOfferingId, s_storageId, s_size, s_minIops, s_maxIops)).thenThrow(new CloudRuntimeException(""));

        Assert.assertThrows(CloudRuntimeException.class, () -> sharedFSServiceImpl.deploySharedFS(cmd));
        verify(_stateMachine, times(1)).transitTo(sharedFS, SharedFS.Event.OperationFailed, null, sharedFSDao);
        verify(_stateMachine, never()).transitTo(sharedFS, SharedFS.Event.OperationSucceeded, null, sharedFSDao);
    }

    @Test
    public void testAllocSharedFS() throws NoTransitionException {
        CreateSharedFSCmd cmd = getMockCreateSharedFSCmd();

        when(dataCenterDao.findById(s_zoneId)).thenReturn(null);
        Assert.assertThrows(InvalidParameterValueException.class, () -> sharedFSServiceImpl.allocSharedFS(cmd));

        DataCenterVO zone = mock(DataCenterVO.class);
        when(dataCenterDao.findById(s_zoneId)).thenReturn(zone);
        when(zone.getId()).thenReturn(s_zoneId);
        when(zone.getAllocationState()).thenReturn(Grouping.AllocationState.Enabled);

        DiskOfferingVO diskOfferingVO = mock(DiskOfferingVO.class);
        when(diskOfferingVO.getProvisioningType()).thenReturn(com.cloud.storage.Storage.ProvisioningType.SPARSE);
        when(diskOfferingDao.findById(s_diskOfferingId)).thenReturn(diskOfferingVO);
        when(diskOfferingVO.isCustomized()).thenReturn(true);
        when(diskOfferingVO.isCustomizedIops()).thenReturn(true);
        StoragePoolVO storagePool = mock(StoragePoolVO.class);
        when(storagePoolDao.findById(s_storageId)).thenReturn(storagePool);
        when(storagePool.getDataCenterId()).thenReturn(s_zoneId);
        when(volumeApiService.doesStoragePoolSupportDiskOffering(storagePool, diskOfferingVO)).thenReturn(true);

        SharedFSVO sharedFS = getMockSharedFS();
        ReflectionTestUtils.setField(sharedFS, "id", s_sharedFSId);

        when(cmd.getNetworkId()).thenReturn(s_networkId);
        NetworkVO networkVO = mock(NetworkVO.class);
        when(networkVO.getId()).thenReturn(s_networkId);
        when(networkVO.getGuestType()).thenReturn(Network.GuestType.Isolated);
        when(networkDao.findById(s_networkId)).thenReturn(networkVO);
        when(networkModel.areServicesSupportedInNetwork(s_networkId, Network.Service.UserData)).thenReturn(true);

        sharedFSServiceImpl.allocSharedFS(cmd);
        Assert.assertEquals(Optional.ofNullable(sharedFS.getAccountId()), Optional.ofNullable(s_ownerId));
        Assert.assertEquals(Optional.ofNullable(sharedFS.getDataCenterId()), Optional.ofNullable(s_zoneId));
        Assert.assertEquals(Optional.ofNullable(sharedFS.getServiceOfferingId()), Optional.ofNullable(s_serviceOfferingId));
    }

    @Test
    public void testAllocSharedFSInvalidZone() {
        CreateSharedFSCmd cmd = getMockCreateSharedFSCmd();

        when(dataCenterDao.findById(s_zoneId)).thenReturn(null);
        Assert.assertThrows(InvalidParameterValueException.class, () -> sharedFSServiceImpl.allocSharedFS(cmd));

        DataCenterVO zone = mock(DataCenterVO.class);
        when(dataCenterDao.findById(s_zoneId)).thenReturn(zone);
        when(zone.getAllocationState()).thenReturn(Grouping.AllocationState.Disabled);
        Assert.assertThrows(PermissionDeniedException.class, () -> sharedFSServiceImpl.allocSharedFS(cmd));

        when(zone.getAllocationState()).thenReturn(Grouping.AllocationState.Enabled);
        when(zone.isSecurityGroupEnabled()).thenReturn(true);
        Assert.assertThrows(PermissionDeniedException.class, () -> sharedFSServiceImpl.allocSharedFS(cmd));
    }

    @Test
    public void tesAllocSharedFSInvalidDiskOffering() {
        CreateSharedFSCmd cmd = getMockCreateSharedFSCmd();

        DataCenterVO zone = mock(DataCenterVO.class);
        when(dataCenterDao.findById(s_zoneId)).thenReturn(zone);
        when(zone.getAllocationState()).thenReturn(Grouping.AllocationState.Enabled);

        DiskOfferingVO diskOfferingVO = mock(DiskOfferingVO.class);
        when(diskOfferingDao.findById(s_diskOfferingId)).thenReturn(diskOfferingVO);
        when(diskOfferingVO.isCustomized()).thenReturn(false);
        Assert.assertThrows(InvalidParameterValueException.class, () -> sharedFSServiceImpl.allocSharedFS(cmd));

        when(diskOfferingVO.isCustomized()).thenReturn(true);
        when(diskOfferingVO.isCustomizedIops()).thenReturn(false);
        Assert.assertThrows(InvalidParameterValueException.class, () -> sharedFSServiceImpl.allocSharedFS(cmd));
    }

    @Test
    public void testAllocSharedFSInvalidCustomizedIops() {
        CreateSharedFSCmd cmd = getMockCreateSharedFSCmd();

        DataCenterVO zone = mock(DataCenterVO.class);
        when(dataCenterDao.findById(s_zoneId)).thenReturn(zone);
        when(zone.getAllocationState()).thenReturn(Grouping.AllocationState.Enabled);

        DiskOfferingVO diskOfferingVO = mock(DiskOfferingVO.class);
        when(diskOfferingDao.findById(s_diskOfferingId)).thenReturn(diskOfferingVO);
        when(diskOfferingVO.isCustomized()).thenReturn(true);
        when(diskOfferingVO.isCustomizedIops()).thenReturn(true);

        when(cmd.getMinIops()).thenReturn(s_minIops);
        when(cmd.getMaxIops()).thenReturn(null);
        Assert.assertThrows(InvalidParameterValueException.class, () -> sharedFSServiceImpl.allocSharedFS(cmd));

        when(cmd.getMinIops()).thenReturn(null);
        when(cmd.getMaxIops()).thenReturn(s_maxIops);
        Assert.assertThrows(InvalidParameterValueException.class, () -> sharedFSServiceImpl.allocSharedFS(cmd));

        when(cmd.getMinIops()).thenReturn(0L);
        when(cmd.getMaxIops()).thenReturn(s_maxIops);
        Assert.assertThrows(InvalidParameterValueException.class, () -> sharedFSServiceImpl.allocSharedFS(cmd));

        when(cmd.getMinIops()).thenReturn(s_maxIops);
        when(cmd.getMaxIops()).thenReturn(s_minIops);
        Assert.assertThrows(InvalidParameterValueException.class, () -> sharedFSServiceImpl.allocSharedFS(cmd));
    }

    @Test
    public void testSharedFSStorageServiceReconciliationEligibility() {
        SharedFSVO ready = mock(SharedFSVO.class);
        when(ready.getVmId()).thenReturn(s_vmId);
        when(ready.getVolumeId()).thenReturn(s_volumeId);
        when(ready.getState()).thenReturn(SharedFS.State.Ready);
        Assert.assertTrue(sharedFSServiceImpl.shouldReconcileSharedFSToStorageService(ready));

        SharedFSVO destroyed = mock(SharedFSVO.class);
        when(destroyed.getVmId()).thenReturn(s_vmId);
        when(destroyed.getVolumeId()).thenReturn(s_volumeId);
        when(destroyed.getState()).thenReturn(SharedFS.State.Destroyed);
        Assert.assertFalse(sharedFSServiceImpl.shouldReconcileSharedFSToStorageService(destroyed));

        SharedFSVO unbound = mock(SharedFSVO.class);
        when(unbound.getVmId()).thenReturn(s_vmId);
        when(unbound.getVolumeId()).thenReturn(null);
        Assert.assertFalse(sharedFSServiceImpl.shouldReconcileSharedFSToStorageService(unbound));
    }

    @Test
    public void testAllocSharedFSInvalidFsFormat() {
        CreateSharedFSCmd cmd = getMockCreateSharedFSCmd();

        DataCenterVO zone = mock(DataCenterVO.class);
        when(dataCenterDao.findById(s_zoneId)).thenReturn(zone);
        when(zone.getId()).thenReturn(s_zoneId);
        when(zone.getAllocationState()).thenReturn(Grouping.AllocationState.Enabled);

        DiskOfferingVO diskOfferingVO = mock(DiskOfferingVO.class);
        when(diskOfferingVO.getProvisioningType()).thenReturn(com.cloud.storage.Storage.ProvisioningType.SPARSE);
        when(diskOfferingDao.findById(s_diskOfferingId)).thenReturn(diskOfferingVO);
        when(diskOfferingVO.isCustomized()).thenReturn(true);
        when(diskOfferingVO.isCustomizedIops()).thenReturn(true);

        StoragePoolVO storagePool = mock(StoragePoolVO.class);
        when(storagePoolDao.findById(s_storageId)).thenReturn(storagePool);
        when(storagePool.getDataCenterId()).thenReturn(s_zoneId);
        when(volumeApiService.doesStoragePoolSupportDiskOffering(storagePool, diskOfferingVO)).thenReturn(true);

        NetworkVO networkVO = mock(NetworkVO.class);
        when(networkVO.getId()).thenReturn(s_networkId);
        when(networkDao.findById(s_networkId)).thenReturn(networkVO);
        when(networkModel.areServicesSupportedInNetwork(s_networkId, Network.Service.UserData)).thenReturn(true);

        when(cmd.getFsFormat()).thenReturn("ext2");
        Assert.assertThrows(InvalidParameterValueException.class, () -> sharedFSServiceImpl.allocSharedFS(cmd));
    }

    @Test
    public void testAllocSharedFSNetworkMustSupportUserData() {
        CreateSharedFSCmd cmd = getMockCreateSharedFSCmd();

        DataCenterVO zone = mock(DataCenterVO.class);
        when(dataCenterDao.findById(s_zoneId)).thenReturn(zone);
        when(zone.getId()).thenReturn(s_zoneId);
        when(zone.getAllocationState()).thenReturn(Grouping.AllocationState.Enabled);

        DiskOfferingVO diskOfferingVO = mock(DiskOfferingVO.class);
        when(diskOfferingVO.getProvisioningType()).thenReturn(com.cloud.storage.Storage.ProvisioningType.SPARSE);
        when(diskOfferingDao.findById(s_diskOfferingId)).thenReturn(diskOfferingVO);
        when(diskOfferingVO.isCustomized()).thenReturn(true);
        when(diskOfferingVO.isCustomizedIops()).thenReturn(true);

        StoragePoolVO storagePool = mock(StoragePoolVO.class);
        when(storagePoolDao.findById(s_storageId)).thenReturn(storagePool);
        when(storagePool.getDataCenterId()).thenReturn(s_zoneId);
        when(volumeApiService.doesStoragePoolSupportDiskOffering(storagePool, diskOfferingVO)).thenReturn(true);

        NetworkVO networkVO = mock(NetworkVO.class);
        when(networkVO.getId()).thenReturn(s_networkId);
        when(networkVO.getUuid()).thenReturn("network-without-userdata");
        when(networkDao.findById(s_networkId)).thenReturn(networkVO);
        when(networkModel.areServicesSupportedInNetwork(s_networkId, Network.Service.UserData)).thenReturn(false);

        InvalidParameterValueException exception = Assert.assertThrows(InvalidParameterValueException.class, () -> sharedFSServiceImpl.allocSharedFS(cmd));
        Assert.assertEquals("Network network-without-userdata does not support UserData or ConfigDrive. Select STATIC network mode and provide ipcidr for this L2 SharedFS network.",
                exception.getMessage());
    }

    @Test
    public void testStaticNetworkAllowsOptionalGatewayAndDns() {
        CreateSharedFSCmd cmd = getMockCreateSharedFSCmd();
        when(cmd.getNetworkMode()).thenReturn(SharedFS.NetworkMode.STATIC);
        when(cmd.getIpCidr()).thenReturn("10.10.1.201/24");
        when(cmd.getGateway()).thenReturn(null);
        when(cmd.getDns1()).thenReturn(null);
        when(cmd.getDns2()).thenReturn(null);

        NetworkVO network = mock(NetworkVO.class);
        when(network.getId()).thenReturn(s_networkId);
        when(network.getGuestType()).thenReturn(Network.GuestType.L2);

        SharedFSServiceImpl.StaticNetworkConfiguration configuration = sharedFSServiceImpl.validateStaticNetworkConfiguration(cmd, network);
        Assert.assertEquals("10.10.1.201", configuration.ipAddress);
        Assert.assertEquals("10.10.1.0/24", configuration.networkCidr);
    }

    @Test
    public void testStaticNetworkRejectsNonRouterGateways() {
        for (String gateway : new String[] {"10.10.1.201", "10.10.1.0", "10.10.1.255", "0.0.0.0", "127.0.0.1", "169.254.1.1", "224.0.0.1"}) {
            CreateSharedFSCmd cmd = getMockCreateSharedFSCmd();
            when(cmd.getNetworkMode()).thenReturn(SharedFS.NetworkMode.STATIC);
            when(cmd.getIpCidr()).thenReturn("10.10.1.201/24");
            when(cmd.getGateway()).thenReturn(gateway);
            NetworkVO network = mock(NetworkVO.class);
            when(network.getGuestType()).thenReturn(Network.GuestType.L2);
            Assert.assertThrows(InvalidParameterValueException.class,
                    () -> sharedFSServiceImpl.validateStaticNetworkConfiguration(cmd, network));
        }
    }

    @Test
    public void testStaticNetworkNormalizesHostPrefixToNetworkCidr() {
        SharedFSServiceImpl.StaticNetworkConfiguration configuration = sharedFSServiceImpl.parseStaticIpCidr("10.10.15.211/16");

        Assert.assertEquals("10.10.15.211", configuration.ipAddress);
        Assert.assertEquals("10.10.0.0/16", configuration.networkCidr);
    }

    @Test
    public void testStaticNetworkAcceptsHostPrefix() {
        SharedFSServiceImpl.StaticNetworkConfiguration configuration = sharedFSServiceImpl.parseStaticIpCidr("10.10.1.211/32");

        Assert.assertEquals("10.10.1.211", configuration.ipAddress);
        Assert.assertEquals("10.10.1.211/32", configuration.networkCidr);
    }

    @Test
    public void testStaticNetworkRejectsInvalidIpPrefix() {
        Assert.assertThrows(InvalidParameterValueException.class,
                () -> sharedFSServiceImpl.parseStaticIpCidr("10.10.1.211/33"));
    }

    @Test
    public void testStartSharedFS() throws ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException, OperationTimedoutException, NoTransitionException {
        SharedFSVO sharedFS = getMockSharedFS();
        when(sharedFSDao.findById(s_sharedFSId)).thenReturn(sharedFS);
        ReflectionTestUtils.setField(sharedFS, "id", s_sharedFSId);
        ReflectionTestUtils.setField(sharedFS, "state", SharedFS.State.Stopped);

        Assert.assertEquals(sharedFSServiceImpl.startSharedFS(s_sharedFSId), sharedFS);
        verify(_stateMachine, times(1)).transitTo(sharedFS, SharedFS.Event.StartRequested, null, sharedFSDao);
        verify(_stateMachine, times(1)).transitTo(sharedFS, SharedFS.Event.OperationSucceeded, null, sharedFSDao);
    }

    @Test
    public void testStartSharedFSException() throws ResourceUnavailableException, InsufficientCapacityException, OperationTimedoutException, NoTransitionException {
        SharedFSVO sharedFS = getMockSharedFS();
        ReflectionTestUtils.setField(sharedFS, "state", SharedFS.State.Stopped);
        when(sharedFSDao.findById(s_sharedFSId)).thenReturn(sharedFS);
        doThrow(CloudRuntimeException.class).when(lifeCycle).startSharedFS(sharedFS);

        Assert.assertThrows(CloudRuntimeException.class, () -> sharedFSServiceImpl.startSharedFS(s_sharedFSId));
        verify(_stateMachine, times(1)).transitTo(sharedFS, SharedFS.Event.StartRequested, null, sharedFSDao);
        verify(_stateMachine, times(1)).transitTo(sharedFS, SharedFS.Event.OperationFailed, null, sharedFSDao);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testStartSharedFSInvalidState() throws ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException, OperationTimedoutException {
        SharedFSVO sharedFS = getMockSharedFS();
        when(sharedFSDao.findById(s_sharedFSId)).thenReturn(sharedFS);
        ReflectionTestUtils.setField(sharedFS, "state", SharedFS.State.Ready);
        sharedFSServiceImpl.startSharedFS(s_sharedFSId);
    }

    @Test
    public void testStopSharedFS() throws NoTransitionException {
        SharedFSVO sharedFS = getMockSharedFS();
        when(sharedFSDao.findById(s_sharedFSId)).thenReturn(sharedFS);
        ReflectionTestUtils.setField(sharedFS, "state", SharedFS.State.Ready);
        Assert.assertEquals(sharedFSServiceImpl.stopSharedFS(s_sharedFSId, false), sharedFS);
        verify(lifeCycle, Mockito.times(1)).stopSharedFS(any(), any());
        verify(_stateMachine, times(1)).transitTo(sharedFS, SharedFS.Event.StopRequested, null, sharedFSDao);
        verify(_stateMachine, times(1)).transitTo(sharedFS, SharedFS.Event.OperationSucceeded, null, sharedFSDao);
    }

    @Test
    public void testStopSharedFSException() throws NoTransitionException {
        SharedFSVO sharedFS = getMockSharedFS();
        when(sharedFSDao.findById(s_sharedFSId)).thenReturn(sharedFS);
        ReflectionTestUtils.setField(sharedFS, "state", SharedFS.State.Ready);
        doThrow(CloudRuntimeException.class).when(lifeCycle).stopSharedFS(sharedFS, false);

        Assert.assertThrows(CloudRuntimeException.class, () -> sharedFSServiceImpl.stopSharedFS(s_sharedFSId, false));
        verify(lifeCycle, Mockito.times(1)).stopSharedFS(any(), any());
        verify(_stateMachine, times(1)).transitTo(sharedFS, SharedFS.Event.StopRequested, null, sharedFSDao);
        verify(_stateMachine, times(1)).transitTo(sharedFS, SharedFS.Event.OperationFailed, null, sharedFSDao);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testStopSharedFSInvalidState() {
        SharedFSVO sharedFS = getMockSharedFS();
        when(sharedFSDao.findById(s_sharedFSId)).thenReturn(sharedFS);
        ReflectionTestUtils.setField(sharedFS, "state", SharedFS.State.Stopped);
        sharedFSServiceImpl.stopSharedFS(s_sharedFSId, false);
    }

    @Test
    public void testRestartSharedFSWithoutCleanup() throws OperationTimedoutException, ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException, NoTransitionException {
        SharedFSVO sharedFS = getMockSharedFS();
        ReflectionTestUtils.setField(sharedFS, "id", s_sharedFSId);
        ReflectionTestUtils.setField(sharedFS, "state", SharedFS.State.Stopped);
        when(sharedFSDao.findById(s_sharedFSId)).thenReturn(sharedFS);
        sharedFSServiceImpl.restartSharedFS(s_sharedFSId, false);
        verify(lifeCycle, never()).stopSharedFS(any(), any());
        verify(lifeCycle, Mockito.times(1)).startSharedFS(any());
        verify(_stateMachine, times(1)).transitTo(sharedFS, SharedFS.Event.StartRequested, null, sharedFSDao);
        verify(_stateMachine, times(1)).transitTo(sharedFS, SharedFS.Event.OperationSucceeded, null, sharedFSDao);
    }

    @Test
    public void testRestartSharedFSWithCleanup() throws OperationTimedoutException, ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException, NoTransitionException {
        SharedFSVO sharedFS = getMockSharedFS();
        ReflectionTestUtils.setField(sharedFS, "id", s_sharedFSId);
        ReflectionTestUtils.setField(sharedFS, "state", SharedFS.State.Ready);
        when(sharedFSDao.findById(s_sharedFSId)).thenReturn(sharedFS);

        DataCenterVO zone = mock(DataCenterVO.class);

        when(lifeCycle.reDeploySharedFS(sharedFS)).thenReturn(true);
        sharedFSServiceImpl.restartSharedFS(s_sharedFSId, true);
        verify(lifeCycle, never()).stopSharedFS(any(), any());
    }

    @Test
    public void testUpdateSharedFS() {
        String newName = "New SharedFS";
        String newDescription = "New SharedFS Description";
        UpdateSharedFSCmd cmd = mock(UpdateSharedFSCmd.class);
        when(cmd.getId()).thenReturn(s_sharedFSId);
        when(cmd.getName()).thenReturn(newName);
        when(cmd.getDescription()).thenReturn(newDescription);

        SharedFSVO sharedFS = getMockSharedFS();
        when(sharedFSDao.findById(s_sharedFSId)).thenReturn(sharedFS);

        sharedFSServiceImpl.updateSharedFS(cmd);
        Assert.assertEquals(sharedFS.getName(), newName);
        Assert.assertEquals(sharedFS.getDescription(), newDescription);
    }

    @Test
    public void testChangeSharedFSDiskOffering() throws ResourceAllocationException {
        Long newSize = 200L;
        Long newMinIops = 2000L;
        Long newMaxIops = 4000L;
        Long newDiskOfferingId = 10L;
        ChangeSharedFSDiskOfferingCmd cmd = mock(ChangeSharedFSDiskOfferingCmd.class);
        when(cmd.getId()).thenReturn(s_sharedFSId);
        when(cmd.getDiskOfferingId()).thenReturn(newDiskOfferingId);
        when(cmd.getSize()).thenReturn(newSize);
        when(cmd.getMinIops()).thenReturn(newMinIops);
        when(cmd.getMaxIops()).thenReturn(newMaxIops);

        SharedFSVO sharedFS = getMockSharedFS();
        ReflectionTestUtils.setField(sharedFS, "state", SharedFS.State.Ready);
        when(sharedFSDao.findById(s_sharedFSId)).thenReturn(sharedFS);

        DataCenterVO zone = mock(DataCenterVO.class);
        when(dataCenterDao.findById(s_zoneId)).thenReturn(zone);
        when(zone.getAllocationState()).thenReturn(Grouping.AllocationState.Enabled);

        DiskOfferingVO diskOfferingVO = mock(DiskOfferingVO.class);
        when(diskOfferingDao.findById(newDiskOfferingId)).thenReturn(diskOfferingVO);
        when(diskOfferingVO.isCustomized()).thenReturn(true);
        when(diskOfferingVO.isCustomizedIops()).thenReturn(true);

        sharedFSServiceImpl.changeSharedFSDiskOffering(cmd);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testChangeSharedFSDiskOfferingInvalidState() throws ResourceAllocationException {
        ChangeSharedFSDiskOfferingCmd cmd = mock(ChangeSharedFSDiskOfferingCmd.class);
        when(cmd.getId()).thenReturn(s_sharedFSId);

        SharedFSVO sharedFS = getMockSharedFS();
        ReflectionTestUtils.setField(sharedFS, "state", SharedFS.State.Destroyed);
        when(sharedFSDao.findById(s_sharedFSId)).thenReturn(sharedFS);
        sharedFSServiceImpl.changeSharedFSDiskOffering(cmd);
    }

    @Test
    public void testChangeSharedFSServiceOffering() throws ResourceUnavailableException, InsufficientCapacityException, ManagementServerException, OperationTimedoutException, NoTransitionException, VirtualMachineMigrationException {
        ChangeSharedFSServiceOfferingCmd cmd = mock(ChangeSharedFSServiceOfferingCmd.class);
        Long newServiceOfferingId = 100L;
        when(cmd.getServiceOfferingId()).thenReturn(newServiceOfferingId);
        when(cmd.getId()).thenReturn(s_sharedFSId);

        SharedFSVO sharedFS = getMockSharedFS();
        ReflectionTestUtils.setField(sharedFS, "id", s_sharedFSId);
        ReflectionTestUtils.setField(sharedFS, "state", SharedFS.State.Stopped);
        when(sharedFSDao.findById(s_sharedFSId)).thenReturn(sharedFS);

        DataCenterVO zone = mock(DataCenterVO.class);
        when(dataCenterDao.findById(s_zoneId)).thenReturn(zone);
        when(zone.getAllocationState()).thenReturn(Grouping.AllocationState.Enabled);

        com.cloud.service.ServiceOfferingVO original=mock(com.cloud.service.ServiceOfferingVO.class);
        com.cloud.service.ServiceOfferingVO target=mock(com.cloud.service.ServiceOfferingVO.class);
        when(serviceOfferingDao.findByIdIncludingRemoved(s_serviceOfferingId)).thenReturn(original);
        when(serviceOfferingDao.findById(newServiceOfferingId)).thenReturn(target);
        when(original.getCpu()).thenReturn(2);when(original.getRamSize()).thenReturn(4096);when(original.getSpeed()).thenReturn(2000);
        when(target.getCpu()).thenReturn(4);when(target.getRamSize()).thenReturn(8192);when(target.getSpeed()).thenReturn(2000);
        when(lifeCycle.changeSharedFSServiceOffering(sharedFS, newServiceOfferingId)).thenReturn(true);

        sharedFSServiceImpl.changeSharedFSServiceOffering(cmd);
        Assert.assertEquals(Optional.ofNullable(sharedFS.getServiceOfferingId()), Optional.ofNullable(newServiceOfferingId));
    }

    @Test
    public void testColdOfferingChangeRejectsDownscaleAndCustomResources() {
        com.cloud.service.ServiceOfferingVO original=mock(com.cloud.service.ServiceOfferingVO.class);
        com.cloud.service.ServiceOfferingVO target=mock(com.cloud.service.ServiceOfferingVO.class);
        when(serviceOfferingDao.findByIdIncludingRemoved(4L)).thenReturn(original);
        when(serviceOfferingDao.findById(100L)).thenReturn(target);
        when(original.getCpu()).thenReturn(2);when(original.getRamSize()).thenReturn(4096);when(original.getSpeed()).thenReturn(2000);
        when(target.getCpu()).thenReturn(4);when(target.getRamSize()).thenReturn(8192);when(target.getSpeed()).thenReturn(1000);
        Assert.assertThrows(InvalidParameterValueException.class,()->sharedFSServiceImpl.validateScaleOfferings(4L,100L,false));
        when(target.getSpeed()).thenReturn(2000);when(target.getCpu()).thenReturn(null);
        Assert.assertThrows(InvalidParameterValueException.class,()->sharedFSServiceImpl.validateScaleOfferings(4L,100L,false));
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testChangeSharedFSServiceOfferingInvalidState() throws ResourceUnavailableException, InsufficientCapacityException, ManagementServerException, OperationTimedoutException, VirtualMachineMigrationException {
        ChangeSharedFSServiceOfferingCmd cmd = mock(ChangeSharedFSServiceOfferingCmd.class);
        Long newServiceOfferingId = 100L;
        when(cmd.getId()).thenReturn(s_sharedFSId);

        SharedFSVO sharedFS = getMockSharedFS();
        ReflectionTestUtils.setField(sharedFS, "id", s_sharedFSId);
        ReflectionTestUtils.setField(sharedFS, "state", SharedFS.State.Starting);
        when(sharedFSDao.findById(s_sharedFSId)).thenReturn(sharedFS);

        sharedFSServiceImpl.changeSharedFSServiceOffering(cmd);
    }

    @Test
    public void testDestroySharedFS() throws NoTransitionException {
        configureRemovalCollaborators();
        DestroySharedFSCmd cmd = mock(DestroySharedFSCmd.class);
        when(cmd.getId()).thenReturn(s_sharedFSId);
        when(cmd.isExpunge()).thenReturn(false);

        SharedFSVO sharedFS = getMockSharedFS();
        ReflectionTestUtils.setField(sharedFS,"id",s_sharedFSId);
        when(sharedFSDao.findById(s_sharedFSId)).thenReturn(sharedFS);
        ReflectionTestUtils.setField(sharedFS, "state", SharedFS.State.Stopped);

        Assert.assertEquals(sharedFSServiceImpl.destroySharedFS(cmd), true);
        verify(lifeCycle, never()).deleteSharedFS(any());
        verify(_stateMachine, times(1)).transitTo(sharedFS, SharedFS.Event.DestroyRequested, null, sharedFSDao);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testDestroySharedFSInvalidState() {
        configureRemovalCollaborators();
        DestroySharedFSCmd cmd = mock(DestroySharedFSCmd.class);
        when(cmd.getId()).thenReturn(s_sharedFSId);
        when(cmd.isExpunge()).thenReturn(false);
        when(cmd.isForced()).thenReturn(false);

        SharedFSVO sharedFS = getMockSharedFS();
        ReflectionTestUtils.setField(sharedFS,"id",s_sharedFSId);
        when(sharedFSDao.findById(s_sharedFSId)).thenReturn(sharedFS);
        ReflectionTestUtils.setField(sharedFS, "state", SharedFS.State.Ready);

        sharedFSServiceImpl.destroySharedFS(cmd);
    }

    @Test
    public void testRecoverSharedFS() throws NoTransitionException {
        SharedFSVO sharedFS = getMockSharedFS();
        when(sharedFSDao.findById(s_sharedFSId)).thenReturn(sharedFS);
        ReflectionTestUtils.setField(sharedFS, "state", SharedFS.State.Destroyed);
        Assert.assertEquals(sharedFSServiceImpl.recoverSharedFS(s_sharedFSId), sharedFS);
        verify(_stateMachine, times(1)).transitTo(sharedFS, SharedFS.Event.RecoveryRequested, null, sharedFSDao);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testRecoverSharedFSInvalidState() {
        SharedFSVO sharedFS = getMockSharedFS();
        when(sharedFSDao.findById(s_sharedFSId)).thenReturn(sharedFS);
        ReflectionTestUtils.setField(sharedFS, "state", SharedFS.State.Expunged);
        sharedFSServiceImpl.recoverSharedFS(s_sharedFSId);
    }

    @Test
    public void testDeleteSharedFS() throws NoTransitionException {
        configureRemovalCollaborators();
        SharedFSVO sharedFS = getMockSharedFS();
        ReflectionTestUtils.setField(sharedFS,"id",s_sharedFSId);
        when(sharedFSDao.findById(s_sharedFSId)).thenReturn(sharedFS);
        ReflectionTestUtils.setField(sharedFS, "state", SharedFS.State.Destroyed);
        sharedFSServiceImpl.deleteSharedFS(s_sharedFSId);
        verify(lifeCycle, Mockito.times(1)).deleteSharedFS(any(), Mockito.eq(SharedFS.DataVolumePolicy.PRESERVE_VOLUMES), Mockito.anySet());
        verify(_stateMachine, times(1)).transitTo(sharedFS, SharedFS.Event.ExpungeOperation, null, sharedFSDao);
    }

    @Test (expected = CloudRuntimeException.class)
    public void testDeleteSharedFSTransitionException() throws NoTransitionException {
        configureRemovalCollaborators();
        SharedFSVO sharedFS = getMockSharedFS();
        ReflectionTestUtils.setField(sharedFS,"id",s_sharedFSId);
        when(sharedFSDao.findById(s_sharedFSId)).thenReturn(sharedFS);
        ReflectionTestUtils.setField(sharedFS, "state", SharedFS.State.Destroyed);
        when(_stateMachine.transitTo(sharedFS, SharedFS.Event.ExpungeOperation, null, sharedFSDao)).thenThrow(new NoTransitionException(""));
        sharedFSServiceImpl.deleteSharedFS(s_sharedFSId);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testDeleteSharedFSInvalidState() {
        configureRemovalCollaborators();
        SharedFSVO sharedFS = getMockSharedFS();
        ReflectionTestUtils.setField(sharedFS,"id",s_sharedFSId);
        when(sharedFSDao.findById(s_sharedFSId)).thenReturn(sharedFS);
        ReflectionTestUtils.setField(sharedFS, "state", SharedFS.State.Stopped);
        sharedFSServiceImpl.deleteSharedFS(s_sharedFSId);
    }

    private void configureRemovalCollaborators() {
        Mockito.lenient().doAnswer(call -> ((java.util.function.Supplier<?>) call.getArgument(1)).get())
                .when(sharedFSServiceImpl).withSharedFSWriterLock(Mockito.any(), Mockito.any());
        Mockito.lenient().doNothing().when(sharedFSServiceImpl).auditSharedFSDeletion(Mockito.any(), Mockito.anyString());
        Mockito.lenient().when(lifeCycle.deleteSharedFS(Mockito.any(), Mockito.any(), Mockito.anySet())).thenReturn(true);
        Mockito.lenient().when(sharedFSDao.update(Mockito.anyLong(),Mockito.any())).thenReturn(true);
        Mockito.lenient().when(sharedFSDao.remove(Mockito.anyLong())).thenReturn(true);
        ReflectionTestUtils.setField(sharedFSServiceImpl, "storageServiceInstanceDao",
                Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageServiceInstanceDao.class));
    }

    private ListSharedFSCmd getMockListSharedFSCmd() {
        ListSharedFSCmd cmd = mock(ListSharedFSCmd.class);
        when(cmd.getId()).thenReturn(s_sharedFSId);
        when(cmd.getName()).thenReturn(s_name);
        when(cmd.getZoneId()).thenReturn(s_zoneId);
        when(cmd.getDiskOfferingId()).thenReturn(s_diskOfferingId);
        when(cmd.getServiceOfferingId()).thenReturn(s_serviceOfferingId);
        when(cmd.getAccountName()).thenReturn("account");
        when(cmd.getDomainId()).thenReturn(s_domainId);
        when(cmd.getNetworkId()).thenReturn(s_networkId);
        return cmd;
    }

    @Test
    public void testSearchForSharedFS() {
        SearchBuilder<SharedFSVO> sb = mock(SearchBuilder.class);
        when(sharedFSDao.createSearchBuilder()).thenReturn(sb);

        SharedFSVO sharedFS = getMockSharedFS();
        when(sb.entity()).thenReturn(sharedFS);
        ReflectionTestUtils.setField(sharedFS, "id", s_sharedFSId);

        VolumeVO volume = mock(VolumeVO.class);
        SearchBuilder<VolumeVO> volumeSb = mock(SearchBuilder.class);
        when(volumeSb.entity()).thenReturn(volume);
        when(volumeDao.createSearchBuilder()).thenReturn(volumeSb);

        NicVO nic = mock(NicVO.class);
        SearchBuilder<NicVO> nicSb = mock(SearchBuilder.class);
        when(nicSb.entity()).thenReturn(nic);
        when(nicDao.createSearchBuilder()).thenReturn(nicSb);

        SearchCriteria<SharedFSVO> sc = mock(SearchCriteria.class);
        Mockito.when(sb.create()).thenReturn(sc);

        Pair<List<SharedFSVO>, Integer> result = new Pair<>(List.of(sharedFS), 1);
        when(sharedFSDao.searchAndCount(any(), any())).thenReturn(result);
        SharedFSJoinVO sharedFSJoinVO = mock(SharedFSJoinVO.class);
        when(sharedFSJoinDao.searchByIds(List.of(s_sharedFSId).toArray(new Long[0]))).thenReturn(List.of(sharedFSJoinVO));

        when(owner.getId()).thenReturn(s_ownerId);
        when(accountMgr.isRootAdmin(any())).thenReturn(true);
        when(sharedFSJoinDao.createSharedFSResponses(any(), any())).thenReturn(null);

        ListSharedFSCmd cmd = getMockListSharedFSCmd();
        sharedFSServiceImpl.searchForSharedFS(ResponseObject.ResponseView.Restricted, cmd);

        verify(sc, times(1)).setParameters("id", s_sharedFSId);
        verify(sc, times(1)).setParameters("name", s_name);
        verify(sc, times(1)).setParameters("dataCenterId", s_zoneId);
        verify(sc, times(1)).setParameters("serviceOfferingId", s_serviceOfferingId);
        verify(sc, times(1)).setJoinParameters("volSearch", "diskOfferingId", s_diskOfferingId);
        verify(sc, times(1)).setJoinParameters("nicSearch", "networkId", s_networkId);
        verify(sharedFSDao, times(1)).searchAndCount(any(), any());
    }

    @Test
    public void testCleanupSharedFS() throws NoTransitionException {
        SharedFSVO sharedFS = getMockSharedFS();
        ReflectionTestUtils.setField(sharedFS, "state", SharedFS.State.Destroyed);
        when(sharedFSDao.listSharedFSToBeDestroyed(any(Date.class))).thenReturn(List.of(sharedFS));
        try (MockedStatic<GlobalLock> globalLockMocked = Mockito.mockStatic(GlobalLock.class)) {
            GlobalLock scanlock = mock(GlobalLock.class);
            when(GlobalLock.getInternLock("sharedfsservice.cleanup")).thenReturn(scanlock);
            when(scanlock.lock(30)).thenReturn(true);
            sharedFSServiceImpl.cleanupSharedFS(true);
            verify(_stateMachine, times(1)).transitTo(sharedFS, SharedFS.Event.ExpungeOperation, null, sharedFSDao);
            verify(_stateMachine, times(1)).transitTo(sharedFS, SharedFS.Event.OperationFailed, null, sharedFSDao);
        }
    }

    @Test
    public void testCleanupSharedFSInvalidState() throws NoTransitionException {
        SharedFSVO sharedFS = getMockSharedFS();
        ReflectionTestUtils.setField(sharedFS, "state", SharedFS.State.Stopped);
        when(sharedFSDao.listSharedFSToBeDestroyed(any(Date.class))).thenReturn(List.of(sharedFS));
        try (MockedStatic<GlobalLock> globalLockMocked = Mockito.mockStatic(GlobalLock.class)) {
            GlobalLock scanlock = mock(GlobalLock.class);
            when(GlobalLock.getInternLock("sharedfsservice.cleanup")).thenReturn(scanlock);
            when(scanlock.lock(30)).thenReturn(true);
            sharedFSServiceImpl.cleanupSharedFS(true);
            verify(_stateMachine, times(1)).transitTo(sharedFS, SharedFS.Event.ExpungeOperation, null, sharedFSDao);
            verify(_stateMachine, times(1)).transitTo(sharedFS, SharedFS.Event.OperationFailed, null, sharedFSDao);
        }
    }
    @Test public void newSharedFsVmCannotUseAThinOrUnknownRootOffering() {
        com.cloud.service.ServiceOfferingVO service=mock(com.cloud.service.ServiceOfferingVO.class);when(service.getDiskOfferingId()).thenReturn(125L);when(serviceOfferingDao.findById(90L)).thenReturn(service);DiskOfferingVO thin=mock(DiskOfferingVO.class);when(thin.getProvisioningType()).thenReturn(com.cloud.storage.Storage.ProvisioningType.THIN);when(diskOfferingDao.findById(125L)).thenReturn(thin);
        Assert.assertThrows(InvalidParameterValueException.class,()->sharedFSServiceImpl.validateSparseNewRootOffering(90L));Assert.assertThrows(InvalidParameterValueException.class,()->sharedFSServiceImpl.validateSparseNewRootOffering(91L));sharedFSServiceImpl.validateSparseNewRootOffering(s_serviceOfferingId);
    }
    @Test public void onlyNewDataAllocationRequiresSparseAndAllocatedRootIsRechecked() {
        DiskOfferingVO thin=mock(DiskOfferingVO.class);when(thin.getProvisioningType()).thenReturn(com.cloud.storage.Storage.ProvisioningType.THIN);when(diskOfferingDao.findById(126L)).thenReturn(thin);Assert.assertThrows(InvalidParameterValueException.class,()->sharedFSServiceImpl.validateSparseNewDataOffering(126L));sharedFSServiceImpl.validateSparseNewDataOffering(124L);
        VolumeVO root=mock(VolumeVO.class);when(root.getProvisioningType()).thenReturn(com.cloud.storage.Storage.ProvisioningType.THIN);when(volumeDao.findByInstanceAndType(77L,Volume.Type.ROOT)).thenReturn(java.util.List.of(root));Assert.assertThrows(InvalidParameterValueException.class,()->sharedFSServiceImpl.verifySparseAllocatedRoot(77L));sharedFSServiceImpl.verifySparseAllocatedRoot(s_vmId);
    }

    @Test public void orphanFormatterJournalBlocksLifecycleEvenWhenLegacyWriterLeaseSaysIdle() {
        SharedFSVO shared=getMockSharedFS();shared.setVmId(s_vmId);com.cloud.vm.VMInstanceVO vm=mock(com.cloud.vm.VMInstanceVO.class);when(vm.getId()).thenReturn(s_vmId);when(vm.getState()).thenReturn(com.cloud.vm.VirtualMachine.State.Running);when(vmInstanceDao.findById(s_vmId)).thenReturn(vm);VolumeVO data=mock(VolumeVO.class);when(data.getUuid()).thenReturn("owned-data");when(volumeDao.findByInstanceAndType(s_vmId,Volume.Type.DATADISK)).thenReturn(List.of(data));
        when(guestCommandDispatcher.dispatch(any())).thenAnswer(call->{org.apache.cloudstack.storage.dataservice.StorageServiceGuestCommand command=call.getArgument(0);return new StorageServiceGuestCommandResult(true,"observed",command.getOperation().equals("operation writer-idle")?"{\"success\":true,\"status\":\"WRITER_IDLE\"}":"{\"success\":true,\"status\":\"TIMED_OUT_PENDING_RECONCILE\",\"formatterActive\":false,\"operation\":{\"formatStarted\":true,\"phase\":\"TIMED_OUT_PENDING_RECONCILE\",\"filesystemUuid\":\"partial-header\"}}");});
        Assert.assertThrows(CloudRuntimeException.class,()->sharedFSServiceImpl.requireNativeLifecycleIdle(shared));verify(lifeCycle,never()).stopSharedFS(any(),any());verify(lifeCycle,never()).deleteSharedFS(any(),any(),any());
    }

    @Test
    public void unauthorizedLifecycleCallsNeverProbeTheGuest() throws Exception {
        when(sharedFSDao.findById(s_sharedFSId)).thenReturn(getMockSharedFS());
        Mockito.doThrow(new PermissionDeniedException("foreign owner")).when(accountMgr).checkAccess(any(), any(), eq(false), any(SharedFS.class));
        DestroySharedFSCmd destroy = mock(DestroySharedFSCmd.class);when(destroy.getId()).thenReturn(s_sharedFSId);
        Assert.assertThrows(PermissionDeniedException.class, () -> sharedFSServiceImpl.destroySharedFS(destroy));
        Assert.assertThrows(PermissionDeniedException.class, () -> sharedFSServiceImpl.deleteSharedFS(s_sharedFSId));
        ChangeSharedFSDiskOfferingCmd disk = mock(ChangeSharedFSDiskOfferingCmd.class);
        when(disk.getId()).thenReturn(s_sharedFSId);
        ChangeSharedFSServiceOfferingCmd service = mock(ChangeSharedFSServiceOfferingCmd.class);
        when(service.getId()).thenReturn(s_sharedFSId);
        Assert.assertThrows(PermissionDeniedException.class, () -> sharedFSServiceImpl.stopSharedFS(s_sharedFSId, false));
        Assert.assertThrows(PermissionDeniedException.class, () -> sharedFSServiceImpl.restartSharedFS(s_sharedFSId, false));
        Assert.assertThrows(PermissionDeniedException.class, () -> sharedFSServiceImpl.changeSharedFSDiskOffering(disk));
        Assert.assertThrows(PermissionDeniedException.class, () -> sharedFSServiceImpl.changeSharedFSServiceOffering(service));
        verifyNoInteractions(guestCommandDispatcher);
    }

    @Test
    public void missingLifecycleResourceFailsBeforeAnyGuestObservation() {
        when(sharedFSDao.findById(999L)).thenReturn(null);
        Assert.assertThrows(InvalidParameterValueException.class, () -> sharedFSServiceImpl.stopSharedFS(999L, false));
        Assert.assertThrows(InvalidParameterValueException.class, () -> sharedFSServiceImpl.restartSharedFS(999L, false));
        verifyNoInteractions(guestCommandDispatcher);
    }

    @Test public void scaleRecoveryCanExcludeOnlyItsExactRollingBackWriter() {
        org.apache.cloudstack.storage.dataservice.StorageServiceInstanceVO instance=Mockito.mock(org.apache.cloudstack.storage.dataservice.StorageServiceInstanceVO.class);Mockito.when(instance.getId()).thenReturn(7L);
        SharedFSVO shared=Mockito.mock(SharedFSVO.class);Mockito.when(shared.getVmId()).thenReturn(8L);Mockito.when(storageServiceInstanceDao.findByVmId(8L)).thenReturn(instance);
        org.apache.cloudstack.storage.dataservice.StorageServiceOperationVO own=new org.apache.cloudstack.storage.dataservice.StorageServiceOperationVO();own.setInstanceId(7L);own.setAction("SHAREDFS_ONLINE_SCALE");own.setState("RUNNING");own.setPhase("ROLLING_BACK");
        Mockito.when(storageOperationDao.listByInstance(7L)).thenReturn(java.util.List.of(own));
        ThreadLocal<String> approved=(ThreadLocal<String>)org.springframework.test.util.ReflectionTestUtils.getField(sharedFSServiceImpl,"approvedScaleRecovery");approved.set(own.getUuid());
        try {
            sharedFSServiceImpl.requireNoUnresolvedWriter(shared);
            own.setPhase("RESIZING");Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,()->sharedFSServiceImpl.requireNoUnresolvedWriter(shared));own.setPhase("ROLLING_BACK");
            org.apache.cloudstack.storage.dataservice.StorageServiceOperationVO foreign=new org.apache.cloudstack.storage.dataservice.StorageServiceOperationVO();foreign.setInstanceId(7L);foreign.setState("RECOVERY_REQUIRED");foreign.setPhase("FORMAT_STARTED");
            Mockito.when(storageOperationDao.listByInstance(7L)).thenReturn(java.util.List.of(own,foreign));Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,()->sharedFSServiceImpl.requireNoUnresolvedWriter(shared));
        } finally {approved.remove();}
    }

    @Test public void explicitFixtureReceiptFailureRetainsAllocatedVmDataForRecovery() throws Exception {
        CreateSharedFSCmd cmd=getMockCreateSharedFSCmd();when(cmd.getTemplateId()).thenReturn(90L);
        DiskOfferingVO sparseData=mock(DiskOfferingVO.class);when(sparseData.getProvisioningType()).thenReturn(com.cloud.storage.Storage.ProvisioningType.SPARSE);when(diskOfferingDao.findById(s_diskOfferingId)).thenReturn(sparseData);
        SharedFSVO fs=getMockSharedFS();when(sharedFSDao.findById(cmd.getEntityId())).thenReturn(fs);
        DataCenterVO zone=mock(DataCenterVO.class);when(zone.getNetworkType()).thenReturn(com.cloud.dc.DataCenter.NetworkType.Advanced);when(dataCenterDao.findById(fs.getDataCenterId())).thenReturn(zone);
        com.cloud.storage.VMTemplateVO target=mock(com.cloud.storage.VMTemplateVO.class);when(target.getTemplateType()).thenReturn(com.cloud.storage.Storage.TemplateType.SYSTEM);
        Mockito.doReturn(target).when(sharedFSServiceImpl).validateExplicitTemplate(cmd,owner,zone);
        when(lifeCycle.deploySharedFS(fs,s_networkId,s_diskOfferingId,s_storageId,s_size,s_minIops,s_maxIops,90L)).thenReturn(new Pair<>(s_volumeId,s_vmId));
        Mockito.doThrow(new CloudRuntimeException("receipt persistence failed")).when(sharedFSServiceImpl).completeExplicitTemplateFixture(cmd,fs,target);
        CloudRuntimeException failed=Assert.assertThrows(CloudRuntimeException.class,()->sharedFSServiceImpl.deploySharedFS(cmd));Assert.assertEquals("receipt persistence failed",failed.getMessage());
        Assert.assertEquals(Long.valueOf(s_vmId),fs.getVmId());Assert.assertEquals(Long.valueOf(s_volumeId),fs.getVolumeId());
        verify(sharedFSDao).update(fs.getId(),fs);verify(lifeCycle,never()).deleteSharedFS(any());
    }
    @Test public void privateFixtureAllocatedRetryCannotCreateASecondVmOrTouchRetainedData() {
        CreateSharedFSCmd cmd=getMockCreateSharedFSCmd();when(cmd.getValidationArtifactUuid()).thenReturn("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        SharedFSVO fs=getMockSharedFS();fs.setVmId(s_vmId);fs.setVolumeId(s_volumeId);when(sharedFSDao.findById(cmd.getEntityId())).thenReturn(fs);Mockito.clearInvocations(lifeCycle,volumeApiService);
        CloudRuntimeException blocked=Assert.assertThrows(CloudRuntimeException.class,()->sharedFSServiceImpl.deploySharedFS(cmd));Assert.assertTrue(blocked.getMessage().contains("already has allocated"));
        verifyNoInteractions(lifeCycle,volumeApiService);Assert.assertEquals(Long.valueOf(s_vmId),fs.getVmId());Assert.assertEquals(Long.valueOf(s_volumeId),fs.getVolumeId());
    }
    @Test public void explicitTemplateUsesActualArchitectureTypeAtConfiguredZoneBoundary() {
        Object previous=ReflectionTestUtils.getField(org.apache.cloudstack.framework.config.ConfigKey.class,"s_depot");
        org.apache.cloudstack.framework.config.impl.ConfigDepotImpl depot=mock(org.apache.cloudstack.framework.config.impl.ConfigDepotImpl.class);
        ReflectionTestUtils.setField(org.apache.cloudstack.framework.config.ConfigKey.class,"s_depot",depot);
        try (MockedStatic<org.apache.cloudstack.storage.dataservice.StorageTemplateCompatibility> compatibility=mockStatic(org.apache.cloudstack.storage.dataservice.StorageTemplateCompatibility.class)) {
            CreateSharedFSCmd cmd=mock(CreateSharedFSCmd.class);when(cmd.getTemplateId()).thenReturn(214L);DataCenterVO zone=mock(DataCenterVO.class);when(zone.getId()).thenReturn(s_zoneId);
            com.cloud.storage.dao.VMTemplateDao templates=mock(com.cloud.storage.dao.VMTemplateDao.class);org.apache.cloudstack.storage.datastore.db.TemplateDataStoreDao stores=mock(org.apache.cloudstack.storage.datastore.db.TemplateDataStoreDao.class);com.cloud.host.dao.HostDao hosts=mock(com.cloud.host.dao.HostDao.class);
            ReflectionTestUtils.setField(sharedFSServiceImpl,"explicitTemplateDao",templates);ReflectionTestUtils.setField(sharedFSServiceImpl,"explicitTemplateStoreDao",stores);ReflectionTestUtils.setField(sharedFSServiceImpl,"explicitTemplateHostDao",hosts);
            com.cloud.storage.VMTemplateVO template=mock(com.cloud.storage.VMTemplateVO.class);when(templates.findById(214L)).thenReturn(template);when(template.getId()).thenReturn(214L);when(template.getState()).thenReturn(com.cloud.template.VirtualMachineTemplate.State.Active);when(template.isDynamicallyScalable()).thenReturn(true);when(template.getHypervisorType()).thenReturn(com.cloud.hypervisor.Hypervisor.HypervisorType.KVM);when(template.getTemplateType()).thenReturn(com.cloud.storage.Storage.TemplateType.SYSTEM);
            org.apache.cloudstack.storage.datastore.db.TemplateDataStoreVO ready=mock(org.apache.cloudstack.storage.datastore.db.TemplateDataStoreVO.class);when(stores.findByTemplateZoneReady(214L,s_zoneId)).thenReturn(ready);when(ready.getDownloadState()).thenReturn(com.cloud.storage.VMTemplateStorageResourceAssoc.Status.DOWNLOADED);when(ready.getState()).thenReturn(org.apache.cloudstack.engine.subsystem.api.storage.ObjectInDataStoreStateMachine.State.Ready);com.cloud.host.HostVO host=mock(com.cloud.host.HostVO.class);when(hosts.listAllHostsUpByZoneAndHypervisor(s_zoneId,com.cloud.hypervisor.Hypervisor.HypervisorType.KVM)).thenReturn(List.of(host));
            com.google.gson.JsonObject supported=new com.google.gson.JsonObject();supported.addProperty("compatible",true);compatibility.when(()->org.apache.cloudstack.storage.dataservice.StorageTemplateCompatibility.evaluate(eq(template),eq(template),any(),eq(true),any(),any(),eq(false),eq(false))).thenReturn(supported);
            Assert.assertEquals("amd64",com.cloud.cpu.CPU.CPUArch.amd64.name());Assert.assertEquals("x86_64",com.cloud.cpu.CPU.CPUArch.amd64.getType());
            for(com.cloud.cpu.CPU.CPUArch architecture:List.of(com.cloud.cpu.CPU.CPUArch.amd64,com.cloud.cpu.CPU.CPUArch.arm64)) {
                when(template.getArch()).thenReturn(architecture);when(depot.getConfigStringValue(eq("system.vm.preferred.architecture"),any(),eq(s_zoneId))).thenReturn(architecture.getType());
                Assert.assertSame(template,sharedFSServiceImpl.validateExplicitTemplate(cmd,owner,zone));
                when(depot.getConfigStringValue(eq("system.vm.preferred.architecture"),any(),eq(s_zoneId))).thenReturn(architecture==com.cloud.cpu.CPU.CPUArch.amd64?"aarch64":"x86_64");
                Assert.assertThrows(InvalidParameterValueException.class,()->sharedFSServiceImpl.validateExplicitTemplate(cmd,owner,zone));
            }
            verifyNoInteractions(lifeCycle,volumeApiService,guestCommandDispatcher);
        } finally {ReflectionTestUtils.setField(org.apache.cloudstack.framework.config.ConfigKey.class,"s_depot",previous);}
    }
    @Test public void architectureTypeFixKeepsInactiveRemovedNonScalableAndNonKvmTemplateBlockedBeforeAllocation() {
        Object previous=ReflectionTestUtils.getField(org.apache.cloudstack.framework.config.ConfigKey.class,"s_depot");org.apache.cloudstack.framework.config.impl.ConfigDepotImpl depot=mock(org.apache.cloudstack.framework.config.impl.ConfigDepotImpl.class);ReflectionTestUtils.setField(org.apache.cloudstack.framework.config.ConfigKey.class,"s_depot",depot);
        try {
            CreateSharedFSCmd cmd=mock(CreateSharedFSCmd.class);when(cmd.getTemplateId()).thenReturn(214L);DataCenterVO zone=mock(DataCenterVO.class);when(zone.getId()).thenReturn(s_zoneId);when(depot.getConfigStringValue(eq("system.vm.preferred.architecture"),any(),eq(s_zoneId))).thenReturn("x86_64");
            com.cloud.storage.dao.VMTemplateDao templates=mock(com.cloud.storage.dao.VMTemplateDao.class);ReflectionTestUtils.setField(sharedFSServiceImpl,"explicitTemplateDao",templates);
            for(String fault:List.of("removed","inactive","notScalable","notKvm","nullArch","wrongArch")) {
                com.cloud.storage.VMTemplateVO template=mock(com.cloud.storage.VMTemplateVO.class);when(templates.findById(214L)).thenReturn(template);
                Mockito.lenient().when(template.getState()).thenReturn(com.cloud.template.VirtualMachineTemplate.State.Active);Mockito.lenient().when(template.isDynamicallyScalable()).thenReturn(true);Mockito.lenient().when(template.getHypervisorType()).thenReturn(com.cloud.hypervisor.Hypervisor.HypervisorType.KVM);Mockito.lenient().when(template.getArch()).thenReturn(com.cloud.cpu.CPU.CPUArch.amd64);
                if(fault.equals("removed"))when(template.getRemoved()).thenReturn(new Date());if(fault.equals("inactive"))when(template.getState()).thenReturn(com.cloud.template.VirtualMachineTemplate.State.Inactive);if(fault.equals("notScalable"))when(template.isDynamicallyScalable()).thenReturn(false);if(fault.equals("notKvm"))when(template.getHypervisorType()).thenReturn(com.cloud.hypervisor.Hypervisor.HypervisorType.VMware);if(fault.equals("nullArch"))when(template.getArch()).thenReturn(null);if(fault.equals("wrongArch"))when(template.getArch()).thenReturn(com.cloud.cpu.CPU.CPUArch.arm64);
                Assert.assertThrows(fault,InvalidParameterValueException.class,()->sharedFSServiceImpl.validateExplicitTemplate(cmd,owner,zone));
            }
            verifyNoInteractions(lifeCycle,volumeApiService,guestCommandDispatcher);
        } finally {ReflectionTestUtils.setField(org.apache.cloudstack.framework.config.ConfigKey.class,"s_depot",previous);}
    }    @Test
    public void recordedAllocationNetworkGuruIsClosedWithoutChangingOrdinaryAllocation() throws Exception {
        NetworkVO network = mock(NetworkVO.class);when(networkDao.findById(s_networkId)).thenReturn(network);
        when(network.getGuruName()).thenReturn("ExternalGuestNetworkGuru");
        com.cloud.network.guru.ExternalGuestNetworkGuru guest = mock(com.cloud.network.guru.ExternalGuestNetworkGuru.class);
        when(guest.getName()).thenReturn("ExternalGuestNetworkGuru");when(recordedNetworkOrchestration.getNetworkGurus()).thenReturn(java.util.List.of(guest));
        sharedFSServiceImpl.requireRecordedAllocationNetwork(s_networkId);
        when(network.getGuruName()).thenReturn("DirectNetworkGuru");
        com.cloud.network.guru.DirectNetworkGuru direct = mock(com.cloud.network.guru.DirectNetworkGuru.class);
        when(direct.getName()).thenReturn("DirectNetworkGuru");when(recordedNetworkOrchestration.getNetworkGurus()).thenReturn(java.util.List.of(direct));
        sharedFSServiceImpl.requireRecordedAllocationNetwork(s_networkId);
        for (String name : java.util.Arrays.asList(null, "ExternalGuru", "GuestNetworkGuruSuffix")) {
            when(network.getGuruName()).thenReturn(name);
            Assert.assertThrows(InvalidParameterValueException.class, () -> sharedFSServiceImpl.requireRecordedAllocationNetwork(s_networkId));
        }
        verify(sharedFSDao, never()).persist(any());verify(lifeCycle, never()).startSharedFS(any());
    }

    @Test
    public void recordedPreflightExcludesOnlyItsExactReservedNameAndRetainsOwnerAndFormatGuards() throws Exception {
        CreateSharedFSCmd cmd = getMockCreateSharedFSCmd();when(cmd.getName()).thenReturn(s_name);
        when(owner.getDomainId()).thenReturn(s_domainId);when(owner.getAccountId()).thenReturn(s_ownerId);
        DataCenterVO zone = mock(DataCenterVO.class);when(dataCenterDao.findById(s_zoneId)).thenReturn(zone);
        when(zone.getId()).thenReturn(s_zoneId);when(zone.getAllocationState()).thenReturn(Grouping.AllocationState.Enabled);
        DiskOfferingVO offering = mock(DiskOfferingVO.class);
        when(offering.getProvisioningType()).thenReturn(com.cloud.storage.Storage.ProvisioningType.SPARSE);
        when(offering.isCustomized()).thenReturn(true);when(offering.isCustomizedIops()).thenReturn(true);
        when(diskOfferingDao.findById(s_diskOfferingId)).thenReturn(offering);
        StoragePoolVO pool = mock(StoragePoolVO.class);when(storagePoolDao.findById(s_storageId)).thenReturn(pool);
        when(pool.getDataCenterId()).thenReturn(s_zoneId);when(volumeApiService.doesStoragePoolSupportDiskOffering(pool, offering)).thenReturn(true);
        when(pool.getStatus()).thenReturn(com.cloud.storage.StoragePoolStatus.Up);when(pool.getPoolType()).thenReturn(com.cloud.storage.Storage.StoragePoolType.SharedMountPoint);
        when(pool.isShared()).thenReturn(true);when(storagePoolDao.listByStatusInZone(s_zoneId, com.cloud.storage.StoragePoolStatus.Up)).thenReturn(java.util.List.of(pool));
        when(volumeApiService.doesStoragePoolSupportDiskOffering(pool, diskOfferingDao.findById(124L))).thenReturn(true);
        NetworkVO network = mock(NetworkVO.class);when(networkDao.findById(s_networkId)).thenReturn(network);
        when(network.getId()).thenReturn(s_networkId);when(network.getGuestType()).thenReturn(Network.GuestType.Isolated);
        when(network.getGuruName()).thenReturn("ExternalGuestNetworkGuru");
        com.cloud.network.guru.ExternalGuestNetworkGuru guest = mock(com.cloud.network.guru.ExternalGuestNetworkGuru.class);
        when(guest.getName()).thenReturn("ExternalGuestNetworkGuru");when(recordedNetworkOrchestration.getNetworkGurus()).thenReturn(java.util.List.of(guest));
        when(networkModel.areServicesSupportedInNetwork(s_networkId, Network.Service.UserData)).thenReturn(true);
        SharedFSVO retained = getMockSharedFS();ReflectionTestUtils.setField(retained, "id", s_sharedFSId);
        retained.setNetworkMode(SharedFS.NetworkMode.DHCP);
        when(sharedFSDao.findById(s_sharedFSId)).thenReturn(retained);
        when(sharedFSDao.findSharedFSByNameAccountDomain(s_name, s_ownerId, cmd.getDomainId())).thenReturn(retained);
        Assert.assertSame(retained, sharedFSServiceImpl.preflightSharedFS(cmd, s_sharedFSId));
        Assert.assertThrows(InvalidParameterValueException.class, () -> sharedFSServiceImpl.preflightSharedFS(cmd));
        ReflectionTestUtils.setField(retained, "accountId", 999L);
        Assert.assertThrows(InvalidParameterValueException.class, () -> sharedFSServiceImpl.preflightSharedFS(cmd, s_sharedFSId));
        verify(sharedFSDao, never()).persist(any());verify(lifeCycle, never()).startSharedFS(any());
    }
    @Test public void recordedPoolPolicyKeepsRbdAndRejectsUnsupportedRootCandidateBeforeAllocation() {
        CreateSharedFSCmd cmd = getMockCreateSharedFSCmd();
        StoragePoolVO pool = mock(StoragePoolVO.class);when(storagePoolDao.findById(s_storageId)).thenReturn(pool);
        when(pool.getDataCenterId()).thenReturn(s_zoneId);when(pool.getStatus()).thenReturn(com.cloud.storage.StoragePoolStatus.Up);
        when(pool.getPoolType()).thenReturn(com.cloud.storage.Storage.StoragePoolType.RBD);when(pool.isShared()).thenReturn(true);
        when(storagePoolDao.listByStatusInZone(s_zoneId, com.cloud.storage.StoragePoolStatus.Up)).thenReturn(java.util.List.of(pool));
        when(volumeApiService.doesStoragePoolSupportDiskOffering(pool, diskOfferingDao.findById(124L))).thenReturn(true);
        sharedFSServiceImpl.requireRecordedFormatPools(cmd);
        when(pool.getPoolType()).thenReturn(com.cloud.storage.Storage.StoragePoolType.Linstor);
        Assert.assertThrows(InvalidParameterValueException.class, () -> sharedFSServiceImpl.requireRecordedFormatPools(cmd));
        verify(sharedFSDao, never()).persist(any());
    }
    private static class ForeignNamedGuestGuru extends com.cloud.network.guru.ExternalGuestNetworkGuru { }

    @Test public void allowedGuruLabelWithForeignConcreteImplementationIsRejected() {
        NetworkVO network = mock(NetworkVO.class);when(networkDao.findById(s_networkId)).thenReturn(network);
        when(network.getGuruName()).thenReturn("ExternalGuestNetworkGuru");
        ForeignNamedGuestGuru foreign = mock(ForeignNamedGuestGuru.class);when(foreign.getName()).thenReturn("ExternalGuestNetworkGuru");
        when(recordedNetworkOrchestration.getNetworkGurus()).thenReturn(java.util.List.of(foreign));
        Assert.assertThrows(InvalidParameterValueException.class, () -> sharedFSServiceImpl.requireRecordedAllocationNetwork(s_networkId));
        verify(sharedFSDao, never()).persist(any());
    }
    @Test public void sourceDefinedDbOnlyComponentProxyIsAcceptedAndUnknownInterceptorRejects() {
        NetworkVO network = mock(NetworkVO.class);when(networkDao.findById(s_networkId)).thenReturn(network);
        when(network.getGuruName()).thenReturn("ExternalGuestNetworkGuru");
        com.cloud.utils.component.ComponentInstantiationPostProcessor processor = new com.cloud.utils.component.ComponentInstantiationPostProcessor();
        processor.setInterceptors(java.util.List.of(new com.cloud.utils.db.TransactionContextBuilder()));
        com.cloud.network.guru.ExternalGuestNetworkGuru proxy = (com.cloud.network.guru.ExternalGuestNetworkGuru)
                processor.postProcessBeforeInstantiation(com.cloud.network.guru.ExternalGuestNetworkGuru.class, "public-proxy");
        proxy.setName("ExternalGuestNetworkGuru");when(recordedNetworkOrchestration.getNetworkGurus()).thenReturn(java.util.List.of(proxy));
        sharedFSServiceImpl.requireRecordedAllocationNetwork(s_networkId);
        com.cloud.utils.component.ComponentMethodInterceptor foreign = mock(com.cloud.utils.component.ComponentMethodInterceptor.class);
        processor.setInterceptors(java.util.List.of(foreign));
        Assert.assertThrows(InvalidParameterValueException.class, () -> sharedFSServiceImpl.requireRecordedAllocationNetwork(s_networkId));
        processor.setInterceptors(java.util.List.of(new com.cloud.utils.db.TransactionContextBuilder()));
        when(networkModel.networkIsConfiguredForExternalNetworking(network.getDataCenterId(), s_networkId)).thenReturn(true);
        Assert.assertThrows(InvalidParameterValueException.class, () -> sharedFSServiceImpl.requireRecordedAllocationNetwork(s_networkId));
        verify(sharedFSDao, never()).persist(any());
    }

}
