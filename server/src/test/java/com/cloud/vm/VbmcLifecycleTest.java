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
package com.cloud.vm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.api.command.user.vm.AllocateVbmcToVMCmd;
import org.apache.cloudstack.api.command.user.vm.RemoveVbmcToVMCmd;
import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.VbmcCommand;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.resource.ResourceState;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.User;
import com.cloud.utils.db.GlobalLock;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VbmcDao;

public class VbmcLifecycleTest {
    private UserVmManagerImpl manager;
    private VbmcDao dao;
    private AgentManager agent;
    private UserVmVO vm;
    private VbmcVO endpoint;
    private MockedStatic<GlobalLock> locks;
    private final List<String> states = new ArrayList<>();

    private void inject(String name, Object value) throws Exception {
        Field f = UserVmManagerImpl.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(manager, value);
    }

    @Before
    public void setup() throws Exception {
        manager = new UserVmManagerImpl();
        dao = mock(VbmcDao.class);
        agent = mock(AgentManager.class);
        UserVmDao vms = mock(UserVmDao.class);
        HostDao hosts = mock(HostDao.class);
        inject("vbmcDao", dao);
        inject("_agentMgr", agent);
        inject("_vmDao", vms);
        inject("_hostDao", hosts);
        inject("_accountMgr", mock(AccountManager.class));
        vm = mock(UserVmVO.class);
        when(vm.getId()).thenReturn(99L);
        when(vm.getHostId()).thenReturn(2L);
        when(vm.getInstanceName()).thenReturn("i-2-99-VM");
        when(vm.getState()).thenReturn(VirtualMachine.State.Running);
        when(vm.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(vms.findById(99L)).thenReturn(vm);
        HostVO host = mock(HostVO.class);
        when(host.getStatus()).thenReturn(Status.Up);
        when(host.getResourceState()).thenReturn(ResourceState.Enabled);
        when(host.getPrivateIpAddress()).thenReturn("10.10.31.2");
        when(hosts.findById(2L)).thenReturn(host);
        endpoint = new VbmcVO(99L, 6230);
        Field id = VbmcVO.class.getDeclaredField("id");
        id.setAccessible(true);
        id.set(endpoint, 1L);
        endpoint.setHostId(2L);
        endpoint.setToken("test-token");
        endpoint.setInstanceName("i-2-99-VM");
        endpoint.setAddress("10.10.31.2");
        endpoint.setAllowedCidr("10.10.0.0/16");
        endpoint.setStatus("Ready");
        when(dao.listByVmId(99L)).thenReturn(Collections.singletonList(endpoint));
        when(dao.update(eq(1L), any())).thenAnswer(i -> {
            states.add(endpoint.getStatus() + ":" + endpoint.getVmId());
            return true;
        });
        GlobalLock lock = mock(GlobalLock.class);
        when(lock.lock(30)).thenReturn(true);
        locks = mockStatic(GlobalLock.class);
        locks.when(() -> GlobalLock.getInternLock("cloud-vbmc-allocation")).thenReturn(lock);
        CallContext.register(mock(User.class), mock(Account.class));
    }

    @After
    public void teardown() {
        locks.close();
        CallContext.unregister();
    }

    private RemoveVbmcToVMCmd remove() {
        RemoveVbmcToVMCmd cmd = mock(RemoveVbmcToVMCmd.class);
        when(cmd.getVmId()).thenReturn(99L);
        return cmd;
    }

    private AllocateVbmcToVMCmd allocate() {
        AllocateVbmcToVMCmd cmd = mock(AllocateVbmcToVMCmd.class);
        when(cmd.getVmId()).thenReturn(99L);
        when(cmd.getPassword()).thenReturn("test-password-12345");
        when(cmd.getAllowedCidr()).thenReturn("10.10.0.0/16");
        return cmd;
    }

    @Test
    public void failedDeletionKeepsPortAndOriginalHost() throws Exception {
        when(vm.getHostId()).thenReturn(3L);
        when(agent.send(eq(2L), any(VbmcCommand.class))).thenReturn(null);
        assertThrows(RuntimeException.class, () -> manager.removeVbmcToVM(remove()));
        assertEquals(Long.valueOf(99), endpoint.getVmId());
        assertEquals(Long.valueOf(2), endpoint.getHostId());
        assertEquals("CleanupRequired", endpoint.getStatus());
        verify(agent).send(eq(2L), any(VbmcCommand.class));
    }

    @Test
    public void confirmedDeletionReleasesOnlyAfterAgentSuccess() throws Exception {
        when(agent.send(eq(2L), any(VbmcCommand.class))).thenAnswer(i -> {
            assertEquals(Long.valueOf(99), endpoint.getVmId());
            assertEquals("Removing", endpoint.getStatus());
            return new Answer(i.getArgument(1), true, null);
        });
        manager.removeVbmcToVM(remove());
        assertEquals(Long.valueOf(0), endpoint.getVmId());
        assertEquals(java.util.Arrays.asList("Removing:99", "Unallocated:0"), states);
    }

    @Test
    public void repeatedRemovalIsNoOp() {
        when(dao.listByVmId(99L)).thenReturn(Collections.emptyList());
        manager.removeVbmcToVM(remove());
        verifyNoInteractions(agent);
    }

    @Test
    public void duplicateAllocationChecksExistingEndpoint() throws Exception {
        when(agent.send(eq(2L), any(VbmcCommand.class))).thenAnswer(i -> {
            VbmcCommand cmd = i.getArgument(1);
            assertEquals("check", cmd.getAction());
            assertNull(cmd.getPassword());
            return new Answer(cmd, true, null);
        });
        manager.allocateVbmcToVM(allocate());
        verify(dao, never()).findAblePort();
    }

    @Test
    public void uncertainAllocationNeverReleasesPort() throws Exception {
        when(dao.listByVmId(99L)).thenReturn(Collections.emptyList());
        endpoint.setVmId(0);
        when(dao.findAblePort()).thenReturn(Collections.singletonList(endpoint));
        when(agent.send(eq(2L), any(VbmcCommand.class))).thenReturn(null);
        assertThrows(RuntimeException.class, () -> manager.allocateVbmcToVM(allocate()));
        assertEquals(Long.valueOf(99), endpoint.getVmId());
        assertEquals("CleanupRequired", endpoint.getStatus());
    }

    @Test
    public void haVmRejectedBeforePortReservation() {
        when(vm.isHaEnabled()).thenReturn(true);
        assertThrows(RuntimeException.class, () -> manager.allocateVbmcToVM(allocate()));
        verify(dao, never()).findAblePort();
        verifyNoInteractions(agent);
    }
    @Test
    public void stoppedVmRejectedBeforeReservation() {
        when(vm.getState()).thenReturn(VirtualMachine.State.Stopped);
        assertThrows(RuntimeException.class, () -> manager.allocateVbmcToVM(allocate()));
        verify(dao, never()).findAblePort();
        verifyNoInteractions(agent);
    }

    @Test
    public void exhaustedPoolDoesNotSendAgentCommand() {
        when(dao.listByVmId(99L)).thenReturn(Collections.emptyList());
        when(dao.findAblePort()).thenReturn(Collections.emptyList());
        assertThrows(RuntimeException.class, () -> manager.allocateVbmcToVM(allocate()));
        verifyNoInteractions(agent);
    }

    @Test
    public void accessDeniedBeforeReservationOrRemoteCommand() throws Exception {
        AccountManager accounts = mock(AccountManager.class);
        inject("_accountMgr", accounts);
        doThrow(new com.cloud.exception.PermissionDeniedException("denied")).when(accounts)
                .checkAccess(any(Account.class), isNull(), eq(true), eq(vm));
        assertThrows(com.cloud.exception.PermissionDeniedException.class, () -> manager.removeVbmcToVM(remove()));
        verifyNoInteractions(dao, agent);
    }

}
