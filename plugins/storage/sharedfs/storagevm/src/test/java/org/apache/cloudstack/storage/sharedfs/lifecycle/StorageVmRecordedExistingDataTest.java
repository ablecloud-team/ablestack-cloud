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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongConsumer;
import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.network.Network;
import com.cloud.offering.ServiceOffering;
import com.cloud.resource.ResourceManager;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.Storage;
import com.cloud.storage.StoragePoolStatus;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeApiService;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.LaunchPermissionDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.uservm.UserVm;
import com.cloud.utils.Pair;
import com.cloud.utils.db.TransactionLegacy;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.NicVO;
import com.cloud.vm.UserVmManager;
import com.cloud.vm.UserVmService;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.UserVmDao;
import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.storage.sharedfs.SharedFS;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;

public class StorageVmRecordedExistingDataTest {
    @Mock AccountManager accountMgr;
    @Mock ResourceManager resourceMgr;
    @Mock VolumeApiService volumeApiService;
    @Mock UserVmService userVmService;
    @Mock UserVmManager userVmManager;
    @Mock DataCenterDao dataCenterDao;
    @Mock VMTemplateDao templateDao;
    @Mock VolumeDao volumeDao;
    @Mock UserVmDao userVmDao;
    @Mock PrimaryDataStoreDao recordedStoragePoolDao;
    @Mock NicDao nicDao;
    @Mock ServiceOfferingDao serviceOfferingDao;
    @Mock LaunchPermissionDao launchPermissionDao;
    @InjectMocks StorageVmSharedFSLifeCycle lifecycle;
    AutoCloseable mocks;
    MockedStatic<CallContext> contexts;
    MockedStatic<TransactionLegacy> transactions;
    final List<String> order = new ArrayList<>();
    final AtomicReference<Long> recordedVm = new AtomicReference<>();
    final AtomicReference<VirtualMachine.State> state = new AtomicReference<>(VirtualMachine.State.Stopped);
    final AtomicBoolean attached = new AtomicBoolean(false);
    SharedFS shared;
    UserVmVO vm;
    VolumeVO root;
    VolumeVO data;
    NicVO nic;
    VMTemplateVO template;
    TransactionLegacy transaction;

    @Before public void prepare() throws Exception {
        mocks = MockitoAnnotations.openMocks(this);
        contexts = mockStatic(CallContext.class);
        CallContext current = mock(CallContext.class), context = mock(CallContext.class);
        contexts.when(CallContext::current).thenReturn(current);
        contexts.when(() -> CallContext.register(current, ApiCommandResourceType.VirtualMachine)).thenReturn(context);
        shared = mock(SharedFS.class);
        when(shared.getVmId()).thenAnswer(call -> recordedVm.get());
        when(shared.getVolumeId()).thenReturn(6L);
        when(shared.getAccountId()).thenReturn(1L);when(shared.getDomainId()).thenReturn(10L);
        when(shared.getDataCenterId()).thenReturn(2L);when(shared.getServiceOfferingId()).thenReturn(4L);
        when(shared.getBackingVolumeMode()).thenReturn(SharedFS.BackingVolumeMode.EXISTING);
        when(shared.getName()).thenReturn("recorded-existing");when(shared.getFsType()).thenReturn(SharedFS.FileSystemType.EXT4);
        when(shared.getNetworkMode()).thenReturn(SharedFS.NetworkMode.STATIC);when(shared.getIpAddress()).thenReturn("192.0.2.78");
        Account owner = mock(Account.class);when(owner.getId()).thenReturn(1L);when(accountMgr.getActiveAccountById(1L)).thenReturn(owner);
        DataCenterVO zone = mock(DataCenterVO.class);when(dataCenterDao.findById(2L)).thenReturn(zone);
        when(serviceOfferingDao.findById(4L)).thenReturn(mock(ServiceOfferingVO.class));
        when(resourceMgr.getSupportedHypervisorTypes(2L, false, null)).thenReturn(List.of(Hypervisor.HypervisorType.KVM));
        template = mock(VMTemplateVO.class);when(templateDao.findById(5L)).thenReturn(template);
        when(template.getTemplateType()).thenReturn(Storage.TemplateType.SYSTEM);when(template.getState()).thenReturn(VirtualMachineTemplate.State.Active);
        when(template.isDynamicallyScalable()).thenReturn(true);when(template.getHypervisorType()).thenReturn(Hypervisor.HypervisorType.KVM);
        vm = mock(UserVmVO.class);when(userVmDao.findById(7L)).thenReturn(vm);when(vm.getId()).thenReturn(7L);
        when(vm.getAccountId()).thenReturn(1L);when(vm.getDataCenterId()).thenReturn(2L);when(vm.getTemplateId()).thenReturn(5L);
        when(vm.getUserVmType()).thenReturn(UserVmManager.SHAREDFSVM);when(vm.getState()).thenAnswer(call -> state.get());
        root = mock(VolumeVO.class);when(root.getId()).thenReturn(21L);when(root.getVolumeType()).thenReturn(Volume.Type.ROOT);
        when(root.getAccountId()).thenReturn(1L);when(root.getDataCenterId()).thenReturn(2L);when(root.getInstanceId()).thenReturn(7L);
        when(root.getState()).thenReturn(Volume.State.Allocated);when(root.getTemplateId()).thenReturn(5L);
        when(root.getPoolId()).thenReturn((Long) null);
        when(root.getFormat()).thenReturn(Storage.ImageFormat.QCOW2);when(root.getProvisioningType()).thenReturn(Storage.ProvisioningType.SPARSE);
        data = mock(VolumeVO.class);when(volumeDao.findById(6L)).thenReturn(data);when(data.getId()).thenReturn(6L);
        when(data.getUuid()).thenReturn("22222222-2222-4222-8222-222222222222");when(data.getName()).thenReturn("preserved-name");
        when(data.getPath()).thenReturn("preserved-path");when(data.getVolumeType()).thenReturn(Volume.Type.DATADISK);
        when(data.getAccountId()).thenReturn(1L);when(data.getDomainId()).thenReturn(10L);when(data.getDataCenterId()).thenReturn(2L);
        when(data.getInstanceId()).thenAnswer(call -> attached.get() ? 7L : null);
        when(data.getState()).thenReturn(Volume.State.Ready);when(data.getPoolId()).thenReturn(9L);
        when(data.getDiskOfferingId()).thenReturn(3L);when(data.getSize()).thenReturn(20L << 30);
        when(data.getFormat()).thenReturn(Storage.ImageFormat.QCOW2);when(data.getProvisioningType()).thenReturn(Storage.ProvisioningType.SPARSE);
        when(data.getTemplateId()).thenReturn((Long) null);
        StoragePoolVO pool = mock(StoragePoolVO.class);when(recordedStoragePoolDao.findById(9L)).thenReturn(pool);
        when(pool.getDataCenterId()).thenReturn(2L);when(pool.getStatus()).thenReturn(StoragePoolStatus.Up);
        when(pool.getPoolType()).thenReturn(Storage.StoragePoolType.SharedMountPoint);
        when(volumeDao.findByInstanceAndType(7L, Volume.Type.ROOT)).thenReturn(List.of(root));
        when(volumeDao.findByInstanceAndType(7L, Volume.Type.DATADISK)).thenAnswer(call -> attached.get() ? List.of(data) : List.of());
        nic = mock(NicVO.class);when(nic.getNetworkId()).thenReturn(8L);when(nic.isDefaultNic()).thenReturn(true);
        when(nicDao.listByVmId(7L)).thenReturn(List.of(nic));
        when(userVmService.createAdvancedVirtualMachine(any(DataCenter.class), any(ServiceOffering.class), any(VirtualMachineTemplate.class),
                anyList(), any(Account.class), anyString(), anyString(), isNull(), isNull(), any(), isNull(),
                any(Hypervisor.HypervisorType.class), any(BaseCmd.HTTPMethod.class), isNull(), isNull(), isNull(), anyList(),
                isNull(), any(Network.IpAddresses.class), isNull(), isNull(), isNull(), anyMap(),
                isNull(), isNull(), isNull(), isNull(), anyBoolean(), anyString(), isNull(), isNull(), isNull(), isNull()))
                .thenAnswer(call -> { order.add("ALLOCATE_ROOT_ONLY");return vm; });
        doAnswer(call -> {
            Assert.assertFalse("First start must not expose existing DATA", attached.get());
            order.add("START_ROOT_ONLY");state.set(VirtualMachine.State.Running);return vm;
        }).when(userVmService).startVirtualMachine(vm, null);
        when(volumeApiService.attachVolumeToVM(7L, 6L, null, true)).thenAnswer(call -> {
            Assert.assertEquals(VirtualMachine.State.Running, state.get());
            order.add("ATTACH");attached.set(true);return data;
        });
        transaction = mock(TransactionLegacy.class);when(transaction.commit()).thenAnswer(call -> {order.add("COMMIT");return true;});
        transactions = mockStatic(TransactionLegacy.class);
        transactions.when(() -> TransactionLegacy.open("RecordedSharedFSVmAllocation")).thenReturn(transaction);
    }
    @After public void close() throws Exception { transactions.close();contexts.close();mocks.close(); }
    LongConsumer recorder() {
        return id -> {
            Assert.assertEquals(7L, id);recordedVm.set(id);
            order.add(attached.get() ? "RECORD_ATTACHED" : "RECORD_RESERVED");
        };
    }
    Pair<Long, Long> deploy(LongConsumer recorder) throws Exception {
        return lifecycle.deployWithExistingVolume(shared, 8L, 6L, 5L, recorder);
    }
    void noRemoval() throws Exception {
        verify(userVmManager, never()).expunge(any());
        verify(userVmService, never()).destroyVm(anyLong(), anyBoolean());
        verify(volumeApiService, never()).destroyVolume(anyLong(), any(), anyBoolean(), anyBoolean(), any());
        verify(data, never()).setName(any());verify(data, never()).setPath(any());
    }

    @Test public void reservationCommitsBeforeRootOnlyFirstBootAndAttachmentIsRecordedAfterwards() throws Exception {
        Pair<Long, Long> result = deploy(recorder());
        Assert.assertEquals(List.of("ALLOCATE_ROOT_ONLY", "RECORD_RESERVED", "COMMIT", "START_ROOT_ONLY", "ATTACH", "RECORD_ATTACHED"), order);
        Assert.assertEquals(Long.valueOf(6L), result.first());Assert.assertEquals(Long.valueOf(7L), result.second());
        noRemoval();
    }
    @Test public void firstRecordFailureCannotCommitStartAttachOrRemove() throws Exception {
        Assert.assertThrows(CloudRuntimeException.class, () -> deploy(id -> {throw new CloudRuntimeException("Controlled record failure");}));
        verify(transaction, never()).commit();verify(userVmService, never()).startVirtualMachine(any(UserVm.class), any());
        verifyNoInteractions(volumeApiService);noRemoval();
    }
    @Test public void failedAllocationCommitCannotStartOrAttachExistingData() throws Exception {
        doReturn(false).when(transaction).commit();
        Assert.assertThrows(CloudRuntimeException.class, () -> deploy(recorder()));
        verify(userVmService, never()).startVirtualMachine(any(UserVm.class), any());verifyNoInteractions(volumeApiService);noRemoval();
    }
    @Test public void failedFirstStartReusesRecordedRootOnlyVmRatherThanAllocatingAnother() throws Exception {
        doThrow(new ResourceUnavailableException("Controlled start loss", UserVm.class, 7L)).when(userVmService).startVirtualMachine(vm, null);
        Assert.assertThrows(ResourceUnavailableException.class, () -> deploy(recorder()));
        Assert.assertEquals(Long.valueOf(7L), recordedVm.get());Assert.assertFalse(attached.get());
        doAnswer(call -> {Assert.assertFalse(attached.get());order.add("RETRY_START_ROOT_ONLY");state.set(VirtualMachine.State.Running);return vm;})
                .when(userVmService).startVirtualMachine(vm, null);
        deploy(recorder());
        Assert.assertEquals(1, order.stream().filter("ALLOCATE_ROOT_ONLY"::equals).count());
        verify(volumeApiService, times(1)).attachVolumeToVM(7L, 6L, null, true);noRemoval();
    }
    @Test public void failedAttachResponseAfterEffectResumesSameAttachmentWithoutReattaching() throws Exception {
        doAnswer(call -> {attached.set(true);throw new CloudRuntimeException("Controlled response loss");})
                .when(volumeApiService).attachVolumeToVM(7L, 6L, null, true);
        Assert.assertThrows(CloudRuntimeException.class, () -> deploy(recorder()));
        deploy(recorder());
        verify(volumeApiService, times(1)).attachVolumeToVM(7L, 6L, null, true);
        Assert.assertEquals(1, order.stream().filter("ALLOCATE_ROOT_ONLY"::equals).count());
        Assert.assertEquals(1, order.stream().filter("START_ROOT_ONLY"::equals).count());noRemoval();
    }
    @Test public void secondRecordFailurePreservesAttachedDataAndRetryDoesNotAttachAgain() throws Exception {
        Assert.assertThrows(CloudRuntimeException.class, () -> deploy(id -> {
            recordedVm.set(id);if(attached.get())throw new CloudRuntimeException("Controlled attached receipt failure");
        }));
        Assert.assertTrue(attached.get());deploy(recorder());
        verify(volumeApiService, times(1)).attachVolumeToVM(7L, 6L, null, true);noRemoval();
    }
    @Test public void stoppedAttachedVmRequiresExistingReceiptBeforeAnyRestart() throws Exception {
        recordedVm.set(7L);attached.set(true);
        Assert.assertThrows(CloudRuntimeException.class, () -> deploy(id -> {throw new CloudRuntimeException("No original ATTACHED authority");}));
        verifyNoInteractions(userVmService, volumeApiService);
        doAnswer(call -> {order.add("START_VERIFIED_ATTACHED");state.set(VirtualMachine.State.Running);return vm;})
                .when(userVmService).startVirtualMachine(vm, null);
        deploy(recorder());
        Assert.assertEquals(List.of("RECORD_ATTACHED", "START_VERIFIED_ATTACHED", "RECORD_ATTACHED"), order);
        verifyNoInteractions(volumeApiService);noRemoval();
    }
    @Test public void changedDataPlacementAfterFirstBootNeverAttachesOrDeletesIt() throws Exception {
        doAnswer(call -> {state.set(VirtualMachine.State.Running);when(data.getPath()).thenReturn("foreign-path");return vm;})
                .when(userVmService).startVirtualMachine(vm, null);
        Assert.assertThrows(CloudRuntimeException.class, () -> deploy(recorder()));
        verifyNoInteractions(volumeApiService);noRemoval();
    }
    @Test public void changedRootOrNicDuringFirstStartCannotExposeExistingData() throws Exception {
        doAnswer(call -> {state.set(VirtualMachine.State.Running);when(root.getId()).thenReturn(99L);return vm;})
                .when(userVmService).startVirtualMachine(vm, null);
        Assert.assertThrows(CloudRuntimeException.class, () -> deploy(recorder()));
        verifyNoInteractions(volumeApiService);
        when(root.getId()).thenReturn(21L);recordedVm.set(7L);state.set(VirtualMachine.State.Stopped);
        doAnswer(call -> {state.set(VirtualMachine.State.Running);when(nic.getMacAddress()).thenReturn("00:00:00:00:00:99");return vm;})
                .when(userVmService).startVirtualMachine(vm, null);
        Assert.assertThrows(CloudRuntimeException.class, () -> deploy(recorder()));
        verifyNoInteractions(volumeApiService);noRemoval();
    }
    @Test public void foreignAttachmentAndUnresolvedVmStateNeverAllocateOrStart() throws Exception {
        recordedVm.set(7L);when(data.getInstanceId()).thenReturn(99L);
        Assert.assertThrows(CloudRuntimeException.class, () -> deploy(recorder()));
        when(data.getInstanceId()).thenReturn(null);state.set(VirtualMachine.State.Starting);
        Assert.assertThrows(CloudRuntimeException.class, () -> deploy(recorder()));
        verifyNoInteractions(userVmService, volumeApiService);noRemoval();
    }
    @Test public void userTemplateAndThinOrBackedDataRemainRejected() throws Exception {
        when(template.getTemplateType()).thenReturn(Storage.TemplateType.USER);
        Assert.assertThrows(com.cloud.exception.InvalidParameterValueException.class, () -> deploy(recorder()));
        when(template.getTemplateType()).thenReturn(Storage.TemplateType.SYSTEM);
        when(data.getProvisioningType()).thenReturn(Storage.ProvisioningType.THIN);
        Assert.assertThrows(CloudRuntimeException.class, () -> deploy(recorder()));
        when(data.getProvisioningType()).thenReturn(Storage.ProvisioningType.SPARSE);when(data.getChainInfo()).thenReturn("foreign-backing");
        Assert.assertThrows(CloudRuntimeException.class, () -> deploy(recorder()));
        verifyNoInteractions(userVmService, volumeApiService);noRemoval();
    }
}
