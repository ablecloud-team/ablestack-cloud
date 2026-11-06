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
import java.util.List;

import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.resource.ResourceState;
import com.cloud.storage.StoragePoolHostVO;
import com.cloud.storage.dao.StoragePoolHostDao;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VirtualMachine;
import com.google.gson.Gson;
import org.apache.cloudstack.backup.dao.BackupDetailsDao;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ThirdPartyBackupRestorePlacementTest {
    private final ThirdPartyBackupVolumeServiceImpl service = new ThirdPartyBackupVolumeServiceImpl();
    private final ThirdPartyBackupStagingService staging = mock(ThirdPartyBackupStagingService.class);
    private final HostDao hosts = mock(HostDao.class);
    private final StoragePoolHostDao pools = mock(StoragePoolHostDao.class);
    private final BackupDetailsDao details = mock(BackupDetailsDao.class);
    private final VirtualMachine vm = mock(VirtualMachine.class);
    private final List<BackupDetailVO> jobs = new ArrayList<>();
    private HostVO first;
    private HostVO second;

    @Before
    public void setup() {
        ReflectionTestUtils.setField(service, "stagingService", staging);
        ReflectionTestUtils.setField(service, "hostDao", hosts);
        ReflectionTestUtils.setField(service, "poolHostDao", pools);
        ReflectionTestUtils.setField(service, "backupDetailsDao", details);
        when(vm.getState()).thenReturn(VirtualMachine.State.Restoring);
        when(vm.getDataCenterId()).thenReturn(1L);
        when(pools.findHostsConnectedToPools(List.of(10L))).thenReturn(List.of(1L, 2L));
        when(pools.findByPoolHost(eq(10L), anyLong())).thenReturn(mock(StoragePoolHostVO.class));
        when(staging.getAvailableBytes(Mockito.any(), anyString())).thenReturn(1000L);
        when(staging.getStageRootPath(anyString())).thenAnswer(call -> "/local/" + call.getArgument(0));
        when(details.findDetails(ThirdPartyBackupAdmission.RESTORE_KEY)).thenAnswer(call -> new ArrayList<>(jobs));
        first = host(1L);
        second = host(2L);
    }

    private HostVO host(long id) {
        HostVO host = mock(HostVO.class);
        when(host.getId()).thenReturn(id);
        when(host.getName()).thenReturn("ablecube" + id);
        when(host.getDataCenterId()).thenReturn(1L);
        when(host.getStatus()).thenReturn(Status.Up);
        when(host.getResourceState()).thenReturn(ResourceState.Enabled);
        when(host.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(hosts.findById(id)).thenReturn(host);
        when(hosts.findByName(host.getName())).thenReturn(host);
        return host;
    }

    private void job(long hostId, String state) {
        ThirdPartyBackupAdmission.Entry entry = new ThirdPartyBackupAdmission.Entry();
        entry.hostId = hostId;
        entry.operation = "RESTORE";
        entry.state = state;
        jobs.add(new BackupDetailVO(jobs.size() + 1L, ThirdPartyBackupAdmission.RESTORE_KEY, new Gson().toJson(entry), false));
    }

    private Host select(String provider) {
        return service.selectRestoreHost(vm, provider, List.of(10L), null, 100L);
    }

    @Test
    public void allStagingProvidersPreferAnIdleHostOverThePreviousHost() {
        when(vm.getLastHostId()).thenReturn(1L);
        for (String provider : List.of("ablestack-netbackup", "ablestack-veeam", "ablestack-commvault")) {
            jobs.clear();
            job(1L, "ADMITTED");
            assertSame(second, select(provider));
            jobs.clear();
            job(1L, "WAITING");
            assertSame(second, select(provider));
        }
    }

    @Test
    public void aRegisteredWaitingRequestChangesTheNextPlacementBeforeExecutionStarts() {
        assertSame(first, select("ablestack-netbackup"));
        job(1L, "WAITING");
        assertSame(second, select("ablestack-veeam"));
        job(2L, "ADMITTING");
        assertSame(first, select("ablestack-commvault"));
    }

    @Test
    public void anAvailableSlotWinsEvenWhenThatHostHasMoreQueuedRequests() {
        job(1L, "ADMITTED");
        job(2L, "WAITING");
        job(2L, "WAITING");
        assertSame(second, select("ablestack-netbackup"));
    }

    @Test
    public void providerEligibilityExcludesAnIdleHostWithoutAValidDestinationClient() {
        job(1L, "ADMITTED");
        assertSame(first, service.selectRestoreHost(vm, "ablestack-commvault", List.of(10L), null, 100L, host -> host.getId() == 1L));
        verify(staging, never()).getAvailableBytes(second, "/local/ablestack-commvault/restore");
        assertThrows(CloudRuntimeException.class,
                () -> service.selectRestoreHost(vm, "ablestack-commvault", List.of(10L), null, 100L, host -> false));
    }

    @Test
    public void localPrimaryStorageAccessAndMountFailuresExcludeOtherwiseIdleHosts() {
        job(1L, "ADMITTED");
        when(pools.findByPoolHost(10L, 2L)).thenReturn(null);
        assertSame(first, select("ablestack-netbackup"));
        when(pools.findByPoolHost(10L, 2L)).thenReturn(mock(StoragePoolHostVO.class));
        when(staging.getAvailableBytes(second, "/local/ablestack-netbackup/restore"))
                .thenThrow(new CloudRuntimeException("staging mount unavailable"));
        assertSame(first, select("ablestack-netbackup"));
    }

    @Test
    public void runningVmAndExplicitHostSelectionsRemainPinnedEvenWhenBusy() {
        job(1L, "ADMITTED");
        when(vm.getState()).thenReturn(VirtualMachine.State.Running);
        when(vm.getHostId()).thenReturn(1L);
        assertSame(first, select("ablestack-netbackup"));
        when(vm.getState()).thenReturn(VirtualMachine.State.Stopped);
        assertSame(first, service.selectRestoreHost(vm, "ablestack-netbackup", List.of(10L), "ablecube1", 100L));
    }

    @Test
    public void failedEligibilityIsNotPermissionToSelectAnotherHostWhenPinned() {
        assertThrows(CloudRuntimeException.class, () -> service.selectRestoreHost(vm, "ablestack-commvault", List.of(10L),
                "ablecube1", 100L, host -> host.getId() == 2L));
    }

    @Test
    public void placementLeaseIsSharedAcrossProvidersAndReleasedBeforeTransfers() {
        GlobalLock lock = mock(GlobalLock.class);
        when(lock.lock(30)).thenReturn(true);
        try (MockedStatic<GlobalLock> locks = Mockito.mockStatic(GlobalLock.class)) {
            locks.when(() -> GlobalLock.getInternLock("backup.thirdparty.restore.placement")).thenReturn(lock);
            try (ThirdPartyBackupVolumeService.Lease ignored = service.acquireRestorePlacement()) {
                verify(lock, never()).unlock();
            }
            verify(lock).unlock();
            verify(lock).releaseRef();
        }
    }

    @Test
    public void contendedPlacementDoesNotProceedWithAnUnprotectedSelection() {
        GlobalLock lock = mock(GlobalLock.class);
        try (MockedStatic<GlobalLock> locks = Mockito.mockStatic(GlobalLock.class)) {
            locks.when(() -> GlobalLock.getInternLock("backup.thirdparty.restore.placement")).thenReturn(lock);
            assertEquals("Restore Worker placement is being prepared; retry the request shortly",
                    assertThrows(CloudRuntimeException.class, service::acquireRestorePlacement).getMessage());
            verify(lock).releaseRef();
            verify(lock, never()).unlock();
        }
    }
}
