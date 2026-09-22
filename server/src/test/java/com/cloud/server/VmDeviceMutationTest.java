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
package com.cloud.server;

import java.util.Collections;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.Assert;
import org.springframework.test.util.ReflectionTestUtils;
import org.apache.cloudstack.context.CallContext;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.DetailVO;
import com.cloud.host.dao.HostDao;
import com.cloud.host.dao.HostDetailsDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.user.Account;
import com.cloud.user.User;
import com.cloud.user.AccountManager;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.snapshot.VMSnapshotVO;
import com.cloud.vm.snapshot.dao.VMSnapshotDao;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class VmDeviceMutationTest {
    private ManagementServerImpl manager;
    private HostDetailsDao details;
    private VMInstanceDao vms;
    private VMSnapshotDao snapshots;
    private VMInstanceVO vm;

    @Before
    public void setup() {
        manager = new ManagementServerImpl();
        details = mock(HostDetailsDao.class);
        vms = mock(VMInstanceDao.class);
        snapshots = mock(VMSnapshotDao.class);
        HostDao hosts = mock(HostDao.class);
        ReflectionTestUtils.setField(manager, "_hostDetailsDao", details);
        ReflectionTestUtils.setField(manager, "_vmInstanceDao", vms);
        ReflectionTestUtils.setField(manager, "deviceVmSnapshotDao", snapshots);
        ReflectionTestUtils.setField(manager, "_hostDao", hosts);
        ReflectionTestUtils.setField(manager, "_accountMgr", mock(AccountManager.class));
        when(details.createSearchCriteria()).thenReturn(mock(SearchCriteria.class));
        vm = mock(VMInstanceVO.class);
        when(vm.getId()).thenReturn(7L);
        when(vm.getUuid()).thenReturn("vm-uuid");
        when(vm.getHostId()).thenReturn(3L);
        when(vm.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(vm.getState()).thenReturn(State.Running);
        when(vms.findById(7L)).thenReturn(vm);
        when(vms.findByUuid("vm-uuid")).thenReturn(vm);
        HostVO host = mock(HostVO.class);
        when(host.getStatus()).thenReturn(Status.Up);
        when(hosts.findById(3L)).thenReturn(host);
        CallContext.register(mock(User.class), mock(Account.class));
    }
    @After
    public void cleanup() { CallContext.unregister(); }

    @Test
    public void allocationCannotStealAnotherVmsDevice() {
        when(details.findByName("002:004")).thenReturn(Collections.singletonList(new DetailVO(3L, "002:004", "8")));
        rejected(7L, null, false, "already allocated");
        verify(vms, never()).findById(anyLong());
    }
    @Test
    public void releaseAcceptsUuidButValidatesOwnership() {
        when(details.findByName("002:004")).thenReturn(Collections.singletonList(new DetailVO(3L, "002:004", "7")));
        Assert.assertEquals("7", manager.validateVmDeviceMutation(3L, "002:004", null, "vm-uuid", false));
    }
    @Test
    public void releaseCannotTargetAnotherVmsAllocation() {
        when(details.findByName("002:004")).thenReturn(Collections.singletonList(new DetailVO(3L, "002:004", "8")));
        rejected(null, "vm-uuid", false, "does not belong");
    }
    @Test
    public void snapshotAndHostMismatchPreventMutation() {
        when(snapshots.findByVm(7L)).thenReturn(Collections.singletonList(mock(VMSnapshotVO.class)));
        rejected(7L, null, false, "snapshots");
        when(vm.getHostId()).thenReturn(9L);
        rejected(7L, null, false, "host");
    }
    @Test
    public void pciRequiresStoppedVmAndUsesLastHost() {
        rejected(7L, null, true, "Stop the VM");
        when(vm.getState()).thenReturn(State.Stopped);
        when(vm.getHostId()).thenReturn(null);
        when(vm.getLastHostId()).thenReturn(3L);
        Assert.assertEquals("7", manager.validateVmDeviceMutation(3L, "002:004", 7L, null, true));
    }
    @Test
    public void missingVmDoesNotAuthorizeCleanup() {
        rejected(null, "missing-uuid", false, "cannot be verified");
    }
    private void rejected(Long id, String current, boolean pci, String message) {
        try {
            manager.validateVmDeviceMutation(3L, "002:004", id, current, pci);
            Assert.fail("Mutation should have been rejected");
        } catch (CloudRuntimeException e) {
            Assert.assertTrue(e.getMessage(), e.getMessage().contains(message));
        }
    }
}
