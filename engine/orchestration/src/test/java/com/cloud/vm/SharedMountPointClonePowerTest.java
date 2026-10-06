// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.vm;

import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.dao.UserVmDetailsDao;

public class SharedMountPointClonePowerTest {
    private VirtualMachineManagerImpl manager;
    private UserVmDetailsDao details;
    private ServiceOfferingDao offerings;
    private VMInstanceVO vm;
    private ServiceOfferingVO offering;

    @Before
    public void setUp() {
        manager = new VirtualMachineManagerImpl();
        details = mock(UserVmDetailsDao.class);
        offerings = mock(ServiceOfferingDao.class);
        vm = mock(VMInstanceVO.class);
        offering = mock(ServiceOfferingVO.class);
        ReflectionTestUtils.setField(manager, "userVmDetailsDao", details);
        ReflectionTestUtils.setField(manager, "_offeringDao", offerings);
        when(vm.getId()).thenReturn(1L);
        when(vm.getServiceOfferingId()).thenReturn(2L);
        when(vm.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(offerings.findById(1L, 2L)).thenReturn(offering);
    }

    @Test
    public void leavesMissingAndNonKvmVmsUnchanged() {
        manager.checkFastCloneSourcePowerOperation(null);
        when(vm.getHypervisorType()).thenReturn(HypervisorType.VMware);
        manager.checkFastCloneSourcePowerOperation(vm);
        verifyNoInteractions(details, offerings);
    }

    @Test
    public void leavesOrdinaryKvmVmsUnchanged() {
        manager.checkFastCloneSourcePowerOperation(vm);
        verifyNoInteractions(offerings);
    }

    @Test
    public void allowsReadyNonVolatileSourceVm() {
        phase(VmDetailConstants.FAST_CLONE_SOURCE_PHASE_READY);
        manager.checkFastCloneSourcePowerOperation(vm);
    }

    @Test
    public void blocksPreparingAndFailedSourceVm() {
        for (String phase : new String[] {"preparing", "prepared", "committing", "failed"}) {
            phase(phase);
            assertThrows(CloudRuntimeException.class, () -> manager.checkFastCloneSourcePowerOperation(vm));
        }
    }

    @Test
    public void blocksReadyVolatileSourceVm() {
        phase(VmDetailConstants.FAST_CLONE_SOURCE_PHASE_READY);
        when(offering.isVolatileVm()).thenReturn(true);
        assertThrows(CloudRuntimeException.class, () -> manager.checkFastCloneSourcePowerOperation(vm));
    }

    @Test
    public void blocksReadySourceVmWithoutOffering() {
        phase(VmDetailConstants.FAST_CLONE_SOURCE_PHASE_READY);
        when(offerings.findById(1L, 2L)).thenReturn(null);
        assertThrows(CloudRuntimeException.class, () -> manager.checkFastCloneSourcePowerOperation(vm));
    }

    private void phase(String value) {
        when(details.findDetail(1L, VmDetailConstants.FAST_CLONE_SOURCE_PHASE))
                .thenReturn(new UserVmDetailVO(1L, VmDetailConstants.FAST_CLONE_SOURCE_PHASE, value, false));
    }
}
