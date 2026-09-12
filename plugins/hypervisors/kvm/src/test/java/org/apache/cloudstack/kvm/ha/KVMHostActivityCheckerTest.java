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
package org.apache.cloudstack.kvm.ha;

import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.CheckOnHostAnswer;
import com.cloud.agent.api.CheckOnHostCommand;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.resource.ResourceManager;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.Storage.StoragePoolType;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.dao.VMInstanceDao;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.outofbandmanagement.OutOfBandManagement.PowerState;
import org.apache.cloudstack.outofbandmanagement.OutOfBandManagementVO;
import org.apache.cloudstack.outofbandmanagement.dao.OutOfBandManagementDao;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

@RunWith(MockitoJUnitRunner.class)
public class KVMHostActivityCheckerTest {
    @InjectMocks private KVMHostActivityChecker checker;
    @Mock private AgentManager agentMgr;
    @Mock private VMInstanceDao vmInstanceDao;
    @Mock private VolumeDao volumeDao;
    @Mock private PrimaryDataStoreDao storagePoolDao;
    @Mock private ResourceManager resourceManager;
    @Mock private OutOfBandManagementDao outOfBandManagementDao;
    private HostVO host;
    private HostVO neighbor;

    @Before
    public void setUp() {
        host = mock(HostVO.class);
        neighbor = mock(HostVO.class);
        lenient().when(host.getId()).thenReturn(1L);
        lenient().when(host.getClusterId()).thenReturn(7L);
        lenient().when(host.getHypervisorType()).thenReturn(HypervisorType.KVM);
        lenient().when(neighbor.getId()).thenReturn(2L);
        lenient().when(neighbor.getHypervisorType()).thenReturn(HypervisorType.KVM);
        lenient().when(resourceManager.listHostsInClusterByStatus(7L, Status.Up)).thenReturn(List.of(host, neighbor));
    }

    private CheckOnHostAnswer observation(Boolean alive) {
        return new CheckOnHostAnswer(new CheckOnHostCommand(host), alive, "fixture observation");
    }

    @Test
    public void onlySuccessfulDeterminedAnswersEstablishLifeOrDeath() {
        assertEquals(Status.Disconnected, checker.getDeterminedHostStatus(null));
        assertEquals(Status.Disconnected, checker.getDeterminedHostStatus(new Answer(null, true, "wrong reply")));
        assertEquals(Status.Disconnected, checker.getDeterminedHostStatus(new CheckOnHostAnswer(new CheckOnHostCommand(host), "transport error")));
        assertEquals(Status.Disconnected, checker.getDeterminedHostStatus(observation(null)));
        assertEquals(Status.Up, checker.getDeterminedHostStatus(observation(true)));
        assertEquals(Status.Down, checker.getDeterminedHostStatus(observation(false)));
    }

    @Test
    public void missingOrUnknownOobmCannotOverrideAliveNeighbor() {
        when(agentMgr.easySend(eq(1L), any(CheckOnHostCommand.class))).thenReturn(null);
        doReturn(observation(true)).when(agentMgr).easySend(eq(2L), any(CheckOnHostCommand.class));
        assertEquals(Status.Disconnected, checker.getHostAgentStatus(host));
        OutOfBandManagementVO oobm = mock(OutOfBandManagementVO.class);
        when(outOfBandManagementDao.findByHost(1L)).thenReturn(oobm);
        when(oobm.getPowerState()).thenReturn(PowerState.Unknown);
        assertEquals(Status.Disconnected, checker.getHostAgentStatus(host));
        when(oobm.getPowerState()).thenReturn(PowerState.Off);
        assertEquals(Status.Down, checker.getHostAgentStatus(host));
    }

    @Test
    public void unknownNeighborsDoNotProveHostDeath() {
        doReturn(observation(null)).when(agentMgr).easySend(eq(1L), any(CheckOnHostCommand.class));
        when(agentMgr.easySend(eq(2L), any(CheckOnHostCommand.class))).thenReturn(new Answer(null, false, "agent unavailable"));
        assertEquals(Status.Disconnected, checker.getHostAgentStatus(host));
        doReturn(observation(false)).when(agentMgr).easySend(eq(2L), any(CheckOnHostCommand.class));
        assertEquals(Status.Down, checker.getHostAgentStatus(host));
        doReturn(observation(true)).when(agentMgr).easySend(eq(1L), any(CheckOnHostCommand.class));
        assertEquals(Status.Up, checker.getHostAgentStatus(host));
    }

    @Test
    public void rbdVolumesAreForwardedOnlyForOptedInPool() {
        VMInstanceVO vm = mock(VMInstanceVO.class);
        when(vm.getId()).thenReturn(11L);
        when(vmInstanceDao.listByHostId(1L)).thenReturn(List.of(vm));
        VolumeVO volume = mock(VolumeVO.class);
        when(volume.getPoolId()).thenReturn(21L);
        when(volume.getPath()).thenReturn("rbd-volume-a");
        when(volumeDao.findByInstance(11L)).thenReturn(List.of(volume));
        StoragePoolVO pool = mock(StoragePoolVO.class);
        when(pool.getPoolType()).thenReturn(StoragePoolType.RBD);
        when(storagePoolDao.findById(21L)).thenReturn(pool);
        KVMHostActivityChecker spy = spy(checker);
        doReturn(false).when(spy).isStoragePoolHeartbeatEnabled(pool);
        assertNull(spy.createHostCheckCommand(host, false).getVolumeList());
        doReturn(true).when(spy).isStoragePoolHeartbeatEnabled(pool);
        CheckOnHostCommand command = spy.createHostCheckCommand(host, true);
        assertEquals("rbd-volume-a", command.getVolumeList());
        assertTrue(command.shouldReportIfHeartBeatFailedForOneStoragePool());
        assertEquals(20, command.getWait());
    }
}
