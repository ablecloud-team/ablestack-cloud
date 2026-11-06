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
package org.apache.cloudstack.backup;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.apache.cloudstack.backup.dao.BackupDao;
import org.apache.cloudstack.backup.dao.BackupDetailsDao;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;
import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.resource.ResourceState;
import com.cloud.storage.StoragePoolHostVO;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.StoragePoolHostDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VMInstanceDetailVO;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ThirdPartyBackupHostPolicyPlacementTest {
    private final ThirdPartyBackupVolumeServiceImpl service = new ThirdPartyBackupVolumeServiceImpl();
    private final VMInstanceVO vm = mock(VMInstanceVO.class);
    private final HostDao hosts = mock(HostDao.class);
    private final BackupDao backups = mock(BackupDao.class);
    private final VMInstanceDetailsDao details = mock(VMInstanceDetailsDao.class);
    private final VolumeDao volumes = mock(VolumeDao.class);
    private final AgentManager agents = mock(AgentManager.class);
    private final GlobalLock lock = mock(GlobalLock.class);
    private final List<Backup> history = new ArrayList<>();
    private final Map<String, VMInstanceDetailVO> saved = new ConcurrentHashMap<>();
    private final VolumeVO disk = mock(VolumeVO.class);
    private HostVO first;
    private HostVO second;

    @Before public void setup() throws Exception {
        ReflectionTestUtils.setField(service, "hostDao", hosts);
        ReflectionTestUtils.setField(service, "backupDao", backups);
        ReflectionTestUtils.setField(service, "vmDetailsDao", details);
        ReflectionTestUtils.setField(service, "volumeDao", volumes);
        ReflectionTestUtils.setField(service, "agentManager", agents);
        ReflectionTestUtils.setField(service, "backupDetailsDao", mock(BackupDetailsDao.class));
        VMInstanceDao vms = mock(VMInstanceDao.class);
        ReflectionTestUtils.setField(service, "vmDao", vms);
        when(vm.getId()).thenReturn(10L);
        when(vm.getUuid()).thenReturn("vm-uuid");
        when(vm.getInstanceName()).thenReturn("i-2-10-VM");
        when(vm.getState()).thenReturn(VirtualMachine.State.Stopped);
        when(vm.getHostId()).thenReturn(null);
        when(vm.getLastHostId()).thenReturn(null);
        when(vm.getBackupOfferingId()).thenReturn(2L);
        when(vm.getDataCenterId()).thenReturn(1L);
        when(vms.findById(10L)).thenReturn(vm);
        when(backups.listByVmIdAndOffering(1L, 10L, 2L)).thenReturn(history);
        when(details.findDetail(eq(10L), anyString())).thenAnswer(call -> saved.get(call.getArgument(1)));
        doAnswer(call -> { saved.put(call.getArgument(1), new VMInstanceDetailVO(10L, call.getArgument(1), call.getArgument(2), false)); return null; })
                .when(details).addDetail(eq(10L), anyString(), anyString(), eq(false));
        when(lock.lock(1)).thenReturn(true);
        when(disk.getPoolId()).thenReturn(20L);
        when(disk.getPath()).thenReturn("root");
        when(disk.getState()).thenReturn(Volume.State.Ready);
        when(disk.getSize()).thenReturn(100L);
        when(volumes.findByInstance(10L)).thenReturn(List.of(disk));
        StoragePoolHostDao pools = mock(StoragePoolHostDao.class);
        ReflectionTestUtils.setField(service, "poolHostDao", pools);
        when(pools.findHostsConnectedToPools(List.of(20L))).thenReturn(List.of(14L, 15L));
        when(pools.findByPoolHost(eq(20L), anyLong())).thenReturn(mock(StoragePoolHostVO.class));
        ThirdPartyBackupStagingService staging = mock(ThirdPartyBackupStagingService.class);
        ReflectionTestUtils.setField(service, "stagingService", staging);
        when(staging.getStageRootPath(anyString())).thenReturn("/staging");
        when(staging.getAvailableBytes(any(), anyString())).thenReturn(1000L);
        first = host(14L);
        second = host(15L);
    }

    private HostVO host(long id) {
        HostVO host = mock(HostVO.class);
        when(host.getId()).thenReturn(id);
        when(host.getName()).thenReturn("host-" + id);
        when(host.getDataCenterId()).thenReturn(1L);
        when(host.getStatus()).thenReturn(Status.Up);
        when(host.getResourceState()).thenReturn(ResourceState.Enabled);
        when(host.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(hosts.findById(id)).thenReturn(host);
        when(hosts.findByName(host.getName())).thenReturn(host);
        return host;
    }

    private MockedStatic<GlobalLock> placementLock() {
        MockedStatic<GlobalLock> mock = mockStatic(GlobalLock.class);
        mock.when(() -> GlobalLock.getInternLock("backup.volume.lifecycle.10")).thenReturn(lock);
        return mock;
    }

    private Host discover(Host host, String policy) {
        try (MockedStatic<GlobalLock> ignored = placementLock()) {
            return service.resolveHostPolicyBackupHost(vm, "ablestack-netbackup", policy, host, candidate -> true);
        }
    }

    private BackupVO parent() {
        BackupVO parent = new BackupVO();
        parent.setDate(new Date());
        parent.setHostId(14L);
        parent.setStatus(Backup.Status.BackedUp);
        parent.setDetails(new HashMap<>());
        parent.getDetails().put("netbackup.policy.name", "policy-a");
        Backup.VolumeInfo info = new Backup.VolumeInfo("root", "root.qcow2", Volume.Type.ROOT, 100L, 0L, null, null, null);
        ThirdPartyBackupManifest manifest = ThirdPartyBackupManifest.create("ablestack-netbackup", parent.getUuid(),
                vm.getInstanceName(), "parent", "FULL", first.getName(), "/staging/vm/parent", "QCOW2", List.of(info), null, null);
        for (ThirdPartyBackupManifest.Artifact artifact : manifest.getCurrentArtifacts()) {
            artifact.completed = true; artifact.jobId = "job"; artifact.externalId = "image";
        }
        ThirdPartyBackupManifest.Artifact metadata = new ThirdPartyBackupManifest.Artifact();
        metadata.completed = true; metadata.jobId = "metadata-job"; metadata.externalId = "metadata-image";
        manifest.setMetadata(metadata);
        manifest.setComplete(true);
        parent.getDetails().put(ThirdPartyBackupManifest.DETAIL_KEY, manifest.toJson());
        history.add(parent);
        return parent;
    }

    @Test public void historicalVmHostAlwaysTakesPriority() {
        when(vm.getLastHostId()).thenReturn(14L);
        assertSame(first, discover(second, "policy-b"));
        verify(details, never()).addDetail(anyLong(), anyString(), anyString(), anyBoolean());
    }

    @Test public void missingHistoryUsesThePreviousSuccessfulBackupHost() {
        parent();
        assertSame(first, discover(second, "policy-a"));
        assertNull(discover(second, "policy-b"));
        verify(details, never()).addDetail(anyLong(), anyString(), anyString(), anyBoolean());
    }

    @Test public void previousManifestCanRecoverAMissingBackupHostId() {
        parent().setHostId(null);
        assertSame(first, discover(second, "policy-a"));
    }

    @Test public void conflictingPreviousHostAndManifestCannotSelectANewWorker() {
        parent().setHostId(15L);
        assertThrows(CloudRuntimeException.class, () -> discover(second, "policy-b"));
        verify(details, never()).addDetail(anyLong(), anyString(), anyString(), anyBoolean());
    }

    @Test public void firstQualifiedHookPersistsPlacementWithoutChangingVmHistory() {
        assertSame(first, discover(first, "policy-a"));
        assertSame(first, service.resolveHostPolicyBackupHost(vm, "ablestack-netbackup", null, null, candidate -> false));
        assertNull(discover(second, "policy-b"));
        verify(details, times(1)).addDetail(anyLong(), anyString(), anyString(), eq(false));
        verify(vm, never()).setLastHostId(anyLong());
    }

    @Test public void executionCannotInventPlacementWhenNoHookHasClaimedTheVm() {
        assertThrows(CloudRuntimeException.class,
                () -> service.resolveHostPolicyBackupHost(vm, "ablestack-netbackup", null, null, candidate -> true));
    }

    @Test public void unreadyDisksAreNotAssigned() {
        when(disk.getState()).thenReturn(Volume.State.Allocated);
        assertNull(discover(first, "policy-a"));
        assertTrue(saved.isEmpty());
    }

    @Test public void invalidPolicyCannotClaimAHostlessVm() {
        try (MockedStatic<GlobalLock> ignored = placementLock()) {
            assertNull(service.resolveHostPolicyBackupHost(vm, "ablestack-netbackup", "policy-a", first, candidate -> false));
        }
        assertTrue(saved.isEmpty());
    }

    @Test public void placementLockTimeoutDefersWithoutWritingOrChoosingAnotherHost() {
        when(lock.lock(1)).thenReturn(false);
        assertNull(discover(first, "policy-a"));
        verify(details, never()).findDetail(anyLong(), anyString());
        assertTrue(saved.isEmpty());
    }

    @Test public void unavailableRecordedHostCannotBeReassigned() {
        discover(first, "policy-a");
        when(hosts.findById(14L)).thenReturn(null);
        assertNull(discover(second, "policy-b"));
        verify(details, times(1)).addDetail(anyLong(), anyString(), anyString(), eq(false));
    }

    @Test public void concurrentHostHooksWriteExactlyOnePlacement() throws Exception {
        ReentrantLock mutex = new ReentrantLock();
        when(lock.lock(1)).thenAnswer(call -> mutex.tryLock(1, TimeUnit.SECONDS));
        when(lock.unlock()).thenAnswer(call -> { mutex.unlock(); return true; });
        CyclicBarrier start = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var a = executor.submit(() -> { start.await(); return discover(first, "policy-a"); });
            var b = executor.submit(() -> { start.await(); return discover(second, "policy-b"); });
            Host firstResult = a.get(10, TimeUnit.SECONDS);
            Host secondResult = b.get(10, TimeUnit.SECONDS);
            assertTrue((firstResult == null) != (secondResult == null));
            verify(details, times(1)).addDetail(anyLong(), anyString(), anyString(), eq(false));
            assertSame(firstResult == null ? secondResult : firstResult,
                    service.resolveHostPolicyBackupHost(vm, "ablestack-netbackup", null, null, candidate -> false));
        } finally { executor.shutdownNow(); }
    }

    private CheckBackupIncrementalSourceCommand inspection() {
        return new CheckBackupIncrementalSourceCommand(vm.getInstanceName(), true, "QCOW2",
                List.of(mock(PrimaryDataStoreTO.class)), List.of("root"), "parent", "<domaincheckpoint/>");
    }

    @Test public void changedBackupHostDefersBeforeInspectingOrStartingAFull() throws Exception {
        assertThrows(CloudRuntimeException.class,
                () -> service.validateHostPolicyBackupChain(vm, parent(), second, "policy-a", inspection()));
        verify(agents, never()).send(anyLong(), any(com.cloud.agent.api.Command.class));
    }

    @Test public void onlyAnExactSourceInspectionProofAllowsContinuation() throws Exception {
        CheckBackupIncrementalSourceCommand command = inspection();
        when(agents.send(eq(14L), any(com.cloud.agent.api.Command.class))).thenReturn(new Answer(command, true,
                "{\"version\":1,\"validated\":true,\"vmName\":\"i-2-10-VM\",\"checkpointName\":\"parent\",\"volumeCount\":1}"));
        service.validateHostPolicyBackupChain(vm, parent(), first, "policy-a", command);
    }

    @Test public void unavailableOldOrMismatchedAgentProofCannotTriggerAFullFallback() throws Exception {
        BackupVO previous = parent();
        CheckBackupIncrementalSourceCommand command = inspection();
        for (String proof : new String[] {null, "{}", "{\"version\":1,\"validated\":false}"}) {
            when(agents.send(eq(14L), any(com.cloud.agent.api.Command.class))).thenReturn(new Answer(command, true, proof));
            assertThrows(CloudRuntimeException.class,
                    () -> service.validateHostPolicyBackupChain(vm, previous, first, "policy-a", command));
        }
    }
}
