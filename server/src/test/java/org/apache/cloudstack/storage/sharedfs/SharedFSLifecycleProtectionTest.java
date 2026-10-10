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

import java.util.List;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.VolumeDetailVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.storage.dao.VolumeDetailsDao;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.storage.dataservice.StorageServiceInstanceVO;
import org.apache.cloudstack.storage.dataservice.StorageServiceOperationVO;
import org.apache.cloudstack.storage.dataservice.StorageServiceGuestCommand;
import org.apache.cloudstack.storage.dataservice.StorageServiceGuestCommandDispatcher;
import org.apache.cloudstack.storage.dataservice.StorageServiceGuestCommandResult;
import org.apache.cloudstack.storage.dataservice.StorageVolumeLifecycleProtection;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceInstanceDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class SharedFSLifecycleProtectionTest {
    private SharedFSServiceImpl service;
    private SharedFS shared;
    private VolumeVO data;
    private VMInstanceVO vm;
    private VolumeDetailsDao details;
    private StorageServiceGuestCommandDispatcher guest;
    private StorageServiceOperationDao operations;
    @Before public void setup() {
        service=new SharedFSServiceImpl();shared=mock(SharedFS.class);data=mock(VolumeVO.class);vm=mock(VMInstanceVO.class);
        when(shared.getVmId()).thenReturn(7L);when(data.getId()).thenReturn(42L);when(data.getUuid()).thenReturn("22222222-2222-4222-8222-222222222222");
        when(data.getVolumeType()).thenReturn(Volume.Type.DATADISK);when(data.getAccountId()).thenReturn(2L);when(data.getDomainId()).thenReturn(3L);when(data.getDataCenterId()).thenReturn(4L);when(data.getPoolId()).thenReturn(5L);when(data.getSize()).thenReturn(1024L);
        when(vm.getId()).thenReturn(7L);when(vm.getState()).thenReturn(VirtualMachine.State.Stopped);
        VolumeDao volumes=mock(VolumeDao.class);when(volumes.findByInstanceAndType(7L,Volume.Type.DATADISK)).thenReturn(List.of(data));
        VMInstanceDao vms=mock(VMInstanceDao.class);when(vms.findById(7L)).thenReturn(vm);
        StorageServiceInstanceDao instances=mock(StorageServiceInstanceDao.class);StorageServiceInstanceVO instance=mock(StorageServiceInstanceVO.class);when(instance.getId()).thenReturn(8L);when(instances.findByVmId(7L)).thenReturn(instance);
        details=mock(VolumeDetailsDao.class);guest=mock(StorageServiceGuestCommandDispatcher.class);operations=mock(StorageServiceOperationDao.class);
        ReflectionTestUtils.setField(service,"vmInstanceDao",vms);ReflectionTestUtils.setField(service,"volumeDao",volumes);ReflectionTestUtils.setField(service,"volumeDetailsDao",details);ReflectionTestUtils.setField(service,"guestCommandDispatcher",guest);ReflectionTestUtils.setField(service,"storageServiceInstanceDao",instances);ReflectionTestUtils.setField(service,"storageOperationDao",operations);
    }
    private void receipt(String state) {
        when(details.findDetail(42L,StorageVolumeLifecycleProtection.STATE)).thenReturn(new VolumeDetailVO(42L,StorageVolumeLifecycleProtection.STATE,state,false));
        String pinned = StorageVolumeLifecycleProtection.identity(data).toString();
        when(details.findDetail(42L,StorageVolumeLifecycleProtection.RECEIPT)).thenReturn(new VolumeDetailVO(42L,StorageVolumeLifecycleProtection.RECEIPT,pinned,false));
    }
    @Test public void stoppedPartialFormatAndUnknownObservationPreserveVmAndData() {
        Assert.assertThrows(CloudRuntimeException.class,()->service.requireNativeLifecycleIdle(shared));
        receipt("UNVERIFIED_FORMAT_INTENT");
        Assert.assertThrows(CloudRuntimeException.class,()->service.requireNativeLifecycleIdle(shared));
        verifyNoInteractions(guest);
    }
    @Test public void stoppedVerifiedReceiptRequiresExactUuidOwnerPoolAndSize() {
        receipt("VERIFIED");service.requireNativeLifecycleIdle(shared);
        when(data.getPoolId()).thenReturn(55L);
        Assert.assertThrows(CloudRuntimeException.class,()->service.requireNativeLifecycleIdle(shared));
        verifyNoInteractions(guest);
    }
    @Test public void persistentRecoveryWriterBlocksBeforeAnyGuestProbe() {
        StorageServiceOperationVO writer=new StorageServiceOperationVO();writer.setState("RECOVERY_REQUIRED");when(operations.listByInstance(8L)).thenReturn(List.of(writer));
        when(vm.getState()).thenReturn(VirtualMachine.State.Running);
        Assert.assertThrows(CloudRuntimeException.class,()->service.requireNativeLifecycleIdle(shared));
        verifyNoInteractions(guest,details);
    }
    @Test public void freshIdleJournalRecordsReceiptBeforeControlledStop() {
        when(vm.getState()).thenReturn(VirtualMachine.State.Running);
        when(guest.dispatch(any())).thenAnswer(call->{StorageServiceGuestCommand command=call.getArgument(0);return new StorageServiceGuestCommandResult(true,"observed",command.getOperation().equals("operation writer-idle")?"{\"success\":true,\"status\":\"WRITER_IDLE\"}":"{\"success\":true,\"status\":\"NOT_STARTED\",\"formatterActive\":false}");});
        service.requireNativeLifecycleIdle(shared);
        verify(details).addDetail(42L,StorageVolumeLifecycleProtection.RECEIPT,StorageVolumeLifecycleProtection.identity(data).toString(),false);
        verify(details).addDetail(42L,StorageVolumeLifecycleProtection.STATE,"VERIFIED",false);
    }
    @Test public void partialJournalCannotOverwritePreviousSafetyReceipt() {
        when(vm.getState()).thenReturn(VirtualMachine.State.Running);
        when(guest.dispatch(any())).thenAnswer(call->{StorageServiceGuestCommand command=call.getArgument(0);return new StorageServiceGuestCommandResult(true,"observed",command.getOperation().equals("operation writer-idle")?"{\"success\":true,\"status\":\"WRITER_IDLE\"}":"{\"success\":true,\"status\":\"RECOVERY_REQUIRED\",\"formatterActive\":false,\"operation\":{\"phase\":\"FORMATTING\",\"formatStarted\":true}}");});
        Assert.assertThrows(CloudRuntimeException.class,()->service.requireNativeLifecycleIdle(shared));verifyNoInteractions(details);
    }
}
