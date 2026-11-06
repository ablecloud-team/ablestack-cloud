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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.apache.cloudstack.storage.sharedfs.SharedFS;
import org.apache.cloudstack.storage.sharedfs.SharedFSVO;
import org.apache.cloudstack.storage.sharedfs.dao.SharedFSDao;
import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.GetVmIpAddressCommand;
import com.cloud.event.ActionEventUtils;
import com.cloud.vm.dao.NicDao;

public class UserVmStaticSharedFsIpFetchTest {
    private UserVmManagerImpl manager;
    private NicDao nics;
    private SharedFSDao sharedFilesystems;
    private AgentManager agent;
    private NicVO nic;
    private SharedFSVO sharedFS;
    private Map<Long, UserVmManagerImpl.VmAndCountDetails> pending;

    @SuppressWarnings("unchecked")
    @Before public void setUp() throws Exception {
        manager = new UserVmManagerImpl();
        nics = mock(NicDao.class);
        sharedFilesystems = mock(SharedFSDao.class);
        agent = mock(AgentManager.class);
        field("_nicDao").set(manager, nics);
        field("sharedFSDao").set(manager, sharedFilesystems);
        field("_agentMgr").set(manager, agent);
        pending = (Map<Long, UserVmManagerImpl.VmAndCountDetails>) field("vmIdCountMap").get(manager);
        pending.put(161L, manager.new VmAndCountDetails(132L, 10));
        nic = new NicVO();
        Field id = NicVO.class.getDeclaredField("id"); id.setAccessible(true); id.set(nic, 161L);
        nic.setInstanceId(132L);
        nic.setDefaultNic(true);
        nic.setMacAddress("02:0d:00:cc:00:57");
        nic.setIPv4Address("10.10.13.233");
        when(nics.findById(161L)).thenReturn(nic);
        sharedFS = mock(SharedFSVO.class);
        when(sharedFS.getVmId()).thenReturn(132L);
        when(sharedFS.getNetworkMode()).thenReturn(SharedFS.NetworkMode.STATIC);
    }

    private Field field(String name) throws Exception {
        Field f = UserVmManagerImpl.class.getDeclaredField(name); f.setAccessible(true); return f;
    }

    // Invoke the actual private worker, not a copy of its persistence logic.
    private void runWorker() throws Exception {
        Class<?> worker = Class.forName(UserVmManagerImpl.class.getName() + "$VmIpAddrFetchThread");
        Constructor<?> c = worker.getDeclaredConstructor(UserVmManagerImpl.class, long.class,
                String.class, long.class, String.class, boolean.class, Long.class, String.class, String.class);
        c.setAccessible(true);
        Object job = c.newInstance(manager, 132L, "867bfbfb-e370-345d-b5f9-ccd20d044219",
                161L, "i-2-132-VM", false, 1L, null, "02:0d:00:cc:00:57");
        Method run = worker.getDeclaredMethod("runInContext"); run.setAccessible(true); run.invoke(job);
    }

    private void assertPreserved() {
        assertEquals("10.10.13.233", nic.getIPv4Address());
        assertEquals("02:0d:00:cc:00:57", nic.getMacAddress());
        verify(nics, never()).update(anyLong(), any(NicVO.class));
        assertFalse(pending.containsKey(161L));
    }

    @Test public void queuedStaticWorkerDoesNotDispatchGenericDiscovery() throws Exception {
        when(sharedFilesystems.findByVm(132L)).thenReturn(sharedFS);
        runWorker();
        verifyNoInteractions(agent);
        assertPreserved();
    }

    @Test public void emptyStaticPrimaryIsNotReplacedByAnAliasObservation() throws Exception {
        nic.setIPv4Address(null);
        when(sharedFilesystems.findByVm(132L)).thenReturn(sharedFS);
        runWorker();
        verifyNoInteractions(agent);
        verify(nics, never()).update(anyLong(), any(NicVO.class));
        assertNull(nic.getIPv4Address());
        assertFalse(pending.containsKey(161L));
    }

    @Test public void discardsCapturedAliasResultWhenStaticPolicyAppearsInFlight() throws Exception {
        when(sharedFilesystems.findByVm(132L)).thenReturn(null, sharedFS);
        // Actual cluster-13 wrapper result for the captured .233/.235 domifaddr output.
        when(agent.send(eq(1L), any(GetVmIpAddressCommand.class)))
                .thenAnswer(i -> new Answer(i.getArgument(1), true, "10.10.13.235"));
        runWorker();
        verify(agent).send(eq(1L), any(GetVmIpAddressCommand.class));
        assertPreserved();
    }

    @Test public void delayedNullResultCannotClearStaticIdentity() throws Exception {
        when(sharedFilesystems.findByVm(132L)).thenReturn(null, sharedFS);
        when(agent.send(eq(1L), any(GetVmIpAddressCommand.class)))
                .thenAnswer(i -> new Answer(i.getArgument(1), true, null));
        runWorker();
        assertPreserved();
    }

    @Test public void dhcpSharedFsRetainsExistingDiscoveryAndPersistence() throws Exception {
        when(sharedFS.getNetworkMode()).thenReturn(SharedFS.NetworkMode.DHCP);
        when(sharedFilesystems.findByVm(132L)).thenReturn(sharedFS);
        persistOrdinaryResult("10.10.13.235");
        assertFalse(pending.containsKey(161L));
    }

    @Test public void ordinaryVmRetainsExistingDiscoveryAndPersistence() throws Exception {
        persistOrdinaryResult("10.10.13.235");
        assertFalse(pending.containsKey(161L));
    }

    @Test public void ordinaryVmRetainsNullObservationAndRetryBehavior() throws Exception {
        persistOrdinaryResult(null);
        assertEquals(9, pending.get(161L).getRetrievalCount());
    }

    @Test public void additionalNicOfStaticVmKeepsGenericDiscovery() throws Exception {
        nic.setDefaultNic(false);
        when(sharedFilesystems.findByVm(132L)).thenReturn(sharedFS);
        persistOrdinaryResult("10.10.13.240");
        verifyNoInteractions(sharedFilesystems);
    }

    @Test public void sharedFsBindingMustMatchTheNicVm() {
        when(sharedFilesystems.findByVm(132L)).thenReturn(sharedFS);
        when(sharedFS.getVmId()).thenReturn(133L);
        assertFalse(manager.isStaticSharedFsPrimaryNic(nic));
    }

    private void persistOrdinaryResult(String address) throws Exception {
        when(agent.send(eq(1L), any(GetVmIpAddressCommand.class)))
                .thenAnswer(i -> new Answer(i.getArgument(1), true, address));
        try (MockedStatic<ActionEventUtils> ignored = mockStatic(ActionEventUtils.class)) {
            runWorker();
        }
        verify(agent).send(eq(1L), any(GetVmIpAddressCommand.class));
        verify(nics).update(161L, nic);
        assertEquals(address, nic.getIPv4Address());
    }
}
