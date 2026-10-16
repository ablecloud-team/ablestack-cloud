// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.cloudstack.backup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.cloudstack.backup.commvault.AblestackCommvaultClient;
import org.junit.Test;
import org.apache.cloudstack.backup.dao.BackupDetailsDao;
import org.apache.cloudstack.backup.dao.BackupDao;
import org.apache.cloudstack.backup.dao.BackupOfferingDetailsDao;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.host.HostVO;
import com.cloud.host.Host;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.snapshot.dao.VMSnapshotDao;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VirtualMachine;

public class AblestackCommvaultBackupProviderTest {
    @Test
    public void offeringRemovalPreservesBackupSetsWithoutContactingCommvault() {
        AblestackCommvaultBackupProvider provider = new AblestackCommvaultBackupProvider() {
            @Override
            AblestackCommvaultClient createClient(ClientSettings settings) {
                throw new AssertionError("Offering removal must not contact or delete Commvault resources");
            }
        };
        assertTrue(provider.removeVMFromBackupOffering(mock(VirtualMachine.class)));
        assertFalse(provider.willDeleteBackupsOnOfferingRemoval());
    }

    @Test
    public void offeringAssignmentReusesBackupSetsOnTheSelectedHost() {
        AblestackCommvaultClient client = mock(AblestackCommvaultClient.class);
        HostVO host = mock(HostVO.class);
        when(host.getName()).thenReturn("ablecube4");
        AblestackCommvaultBackupProvider provider = new AblestackCommvaultBackupProvider() {
            @Override
            AblestackCommvaultClient getOrCreateClient(Long zoneId, ClientSettings settings) {
                return client;
            }
            @Override
            protected Host getVMHypervisorHostForBackup(VirtualMachine vm) {
                return host;
            }
        };
        ReflectionTestUtils.setField(provider, "vmSnapshotDao", mock(VMSnapshotDao.class));
        BackupOfferingDetailsDao details = mock(BackupOfferingDetailsDao.class);
        ReflectionTestUtils.setField(provider, "backupOfferingDetailsDao", details);
        VirtualMachine vm = mock(VirtualMachine.class);
        when(vm.getInstanceName()).thenReturn("i-2-220-VM");
        BackupOffering offering = mock(BackupOffering.class);
        when(offering.getExternalId()).thenReturn("7");
        when(client.prepareVmBackupSet("ablecube4", "i-2-220-VM", "7")).thenReturn("9");

        assertTrue(provider.assignVMToBackupOffering(vm, offering));

        verify(client).prepareVmBackupSet("ablecube4", "i-2-220-VM", "7");
        verify(details).addDetail(eq(0L), eq("commvault.managed.backupset.0.0"), any(), eq(false));
        verify(client, never()).createBackupSet(any(), any(), any(), any());
    }

    @Test
    public void clientsAreReusedPerZoneAndRecreatedWhenConfigurationChanges() {
        AtomicInteger creations = new AtomicInteger();
        AblestackCommvaultBackupProvider provider = new AblestackCommvaultBackupProvider() {
            @Override
            AblestackCommvaultClient createClient(ClientSettings settings) {
                creations.incrementAndGet();
                return mock(AblestackCommvaultClient.class);
            }
        };
        AblestackCommvaultBackupProvider.ClientSettings original =
                new AblestackCommvaultBackupProvider.ClientSettings("https://commvault/api", "admin", "password", true, 30);
        AblestackCommvaultBackupProvider.ClientSettings changed =
                new AblestackCommvaultBackupProvider.ClientSettings("https://commvault/api", "admin", "new-password", true, 30);

        AblestackCommvaultClient zoneOneClient = provider.getOrCreateClient(1L, original);
        assertSame(zoneOneClient, provider.getOrCreateClient(1L, original));
        assertNotSame(zoneOneClient, provider.getOrCreateClient(2L, original));
        assertNotSame(zoneOneClient, provider.getOrCreateClient(1L, changed));
        assertEquals(3, creations.get());
    }

    @Test
    public void primaryCopyCacheIsSharedAcrossJobsScopedByZoneAndInvalidatedWhenCopyDisappears() {
        AblestackCommvaultBackupProvider provider = new AblestackCommvaultBackupProvider();
        AblestackCommvaultClient client = mock(AblestackCommvaultClient.class);
        when(client.isPrimaryStoragePolicyCopy("4", "3")).thenReturn(true);
        when(client.isPrimaryStoragePolicyCopy("4", "7")).thenReturn(true);
        List<AblestackCommvaultClient.JobRetentionInfo> firstJob = List.of(
                new AblestackCommvaultClient.JobRetentionInfo("4", "3", "1791590564"));
        List<AblestackCommvaultClient.JobRetentionInfo> changedJob = List.of(
                new AblestackCommvaultClient.JobRetentionInfo("4", "7", "1791590564"));

        assertEquals("3", provider.resolvePrimaryStoragePolicyCopyId(1L, "4", firstJob, client));
        assertEquals("3", provider.resolvePrimaryStoragePolicyCopyId(1L, "4", firstJob, client));
        verify(client, times(1)).isPrimaryStoragePolicyCopy("4", "3");

        assertEquals("3", provider.resolvePrimaryStoragePolicyCopyId(2L, "4", firstJob, client));
        verify(client, times(2)).isPrimaryStoragePolicyCopy("4", "3");

        assertEquals("7", provider.resolvePrimaryStoragePolicyCopyId(1L, "4", changedJob, client));
        verify(client, times(1)).isPrimaryStoragePolicyCopy("4", "7");
    }

    @Test
    public void checkpointIsSavedBeforeDeletionAndRetriesUseTheSavedCopy() throws Exception {
        AblestackCommvaultBackupProvider provider = new AblestackCommvaultBackupProvider();
        AgentManager agent = mock(AgentManager.class);
        BackupDetailsDao detailsDao = mock(BackupDetailsDao.class);
        ReflectionTestUtils.setField(provider, "agentManager", agent);
        ReflectionTestUtils.setField(provider, "backupDetailsDao", detailsDao);
        Backup backup = mock(Backup.class);
        HostVO host = mock(HostVO.class);
        when(host.getId()).thenReturn(14L);
        when(backup.getId()).thenReturn(1L);
        Map<String, String> details = new HashMap<>();
        details.put("commvault.backup.engine", "QCOW2");
        details.put("commvault.checkpoint.path", "/staging/checkpoints/current.xml");
        when(backup.getDetails()).thenReturn(details);
        when(backup.getDetail(any())).thenAnswer(invocation -> details.get(invocation.getArgument(0)));
        String xml = "<domaincheckpoint><name>current</name></domaincheckpoint>";
        when(agent.send(eq(14L), any(AblestackCommvaultReadFileCommand.class)))
                .thenAnswer(invocation -> new Answer(invocation.getArgument(1), true, xml));
        ThirdPartyBackupVolumeService.Transfer transfer = ReflectionTestUtils.invokeMethod(provider, "volumeTransfer", mock(VirtualMachine.class), backup, host);

        doThrow(new CloudRuntimeException("DB unavailable")).doNothing().when(detailsDao)
                .addDetail(1L, "commvault.checkpoint.xml", xml, false);
        assertThrows(CloudRuntimeException.class, () -> transfer.completed(backup));
        assertEquals(null, details.get("commvault.checkpoint.xml"));
        transfer.completed(backup);
        assertEquals(xml, details.get("commvault.checkpoint.xml"));
        // Simulate a lost finalization response: staging is now gone, metadata is durable.
        when(agent.send(eq(14L), any(AblestackCommvaultReadFileCommand.class)))
                .thenThrow(new CloudRuntimeException("Staging was already removed"));
        transfer.completed(backup);
        verify(agent, times(2)).send(eq(14L), any(AblestackCommvaultReadFileCommand.class));
        verify(detailsDao, times(2)).addDetail(1L, "commvault.checkpoint.xml", xml, false);
    }

    @Test
    public void offeringDeletionRemovesOnlyItsVmsBackupSetsInTheSameZoneAndPlan() {
        CleanupFixture fixture = new CleanupFixture();
        VMInstanceVO vm = fixture.vm("i-2-220-VM", 1L);
        VMInstanceVO otherPlanVm = fixture.vm("i-2-221-VM", 1L);
        VMInstanceVO otherZoneVm = fixture.vm("i-2-999-VM", 2L);
        when(fixture.vms.searchIncludingRemoved(any(), any(), any(), eq(false))).thenReturn(List.of(vm, otherPlanVm, otherZoneVm));
        List<HostVO> hosts = List.of(fixture.host("ablecube4", 1L), fixture.host("other-zone", 2L));
        when(fixture.hosts.searchIncludingRemoved(any(), any(), any(), eq(false))).thenReturn(hosts);
        AblestackCommvaultClient.VmBackupSet own = fixture.reference("i-2-220-VM", "9", "7");
        when(fixture.client.listVmBackupSets("ablecube4")).thenReturn(List.of(own,
                fixture.reference("manual-backup", "10", "7"), fixture.reference("defaultBackupSet", "11", "7"),
                fixture.reference("i-2-221-VM", "12", "8"), fixture.reference("i-2-999-VM", "13", "7")));
        when(fixture.client.deleteVmBackupSet(own)).thenReturn(true);

        assertTrue(fixture.provider.cleanupBackupOffering(fixture.offering));

        verify(fixture.client).deleteVmBackupSet(own);
        verify(fixture.client, times(1)).deleteVmBackupSet(any());
        verify(fixture.client, never()).listVmBackupSets("other-zone");
    }

    @Test
    public void recordedBackupSetsAreCleanedAfterVmAndHostRecordsHaveBeenPurged() {
        CleanupFixture fixture = new CleanupFixture();
        AblestackCommvaultClient.VmBackupSet own = fixture.reference("i-2-220-VM", "9", "7");
        when(fixture.details.listDetails(3L)).thenReturn(List.of(new BackupOfferingDetailsVO(3L,
                "commvault.managed.backupset.4.220", new com.google.gson.Gson().toJson(own), false)));
        when(fixture.client.listVmBackupSets("ablecube4")).thenReturn(List.of(own));
        when(fixture.client.deleteVmBackupSet(own)).thenReturn(true);

        assertTrue(fixture.provider.cleanupBackupOffering(fixture.offering));

        verify(fixture.client).deleteVmBackupSet(own);
    }

    @Test
    public void incompleteHostInventoryDoesNotStartBackupSetDeletion() {
        CleanupFixture fixture = new CleanupFixture();
        VMInstanceVO vm = fixture.vm("i-2-220-VM", 1L);
        List<HostVO> hosts = List.of(fixture.host("ablecube4", 1L), fixture.host("ablecube1", 1L));
        when(fixture.vms.searchIncludingRemoved(any(), any(), any(), eq(false))).thenReturn(List.of(vm));
        when(fixture.hosts.searchIncludingRemoved(any(), any(), any(), eq(false))).thenReturn(hosts);
        when(fixture.client.listVmBackupSets("ablecube4")).thenReturn(List.of(fixture.reference("i-2-220-VM", "9", "7")));
        when(fixture.client.listVmBackupSets("ablecube1")).thenThrow(new CloudRuntimeException("Commvault unavailable"));

        assertThrows(CloudRuntimeException.class, () -> fixture.provider.cleanupBackupOffering(fixture.offering));

        verify(fixture.client, never()).deleteVmBackupSet(any());
    }

    @Test
    public void offeringDeletionStopsWhenARecordedBackupSetIdentityChangesOrDeletionFails() {
        CleanupFixture fixture = new CleanupFixture();
        AblestackCommvaultClient.VmBackupSet own = fixture.reference("i-2-220-VM", "9", "7");
        when(fixture.details.listDetails(3L)).thenReturn(List.of(new BackupOfferingDetailsVO(3L,
                "commvault.managed.backupset.4.220", new com.google.gson.Gson().toJson(own), false)));
        when(fixture.client.listVmBackupSets("ablecube4")).thenReturn(List.of(fixture.reference("i-2-220-VM", "10", "7")));
        assertThrows(CloudRuntimeException.class, () -> fixture.provider.cleanupBackupOffering(fixture.offering));
        verify(fixture.client, never()).deleteVmBackupSet(any());

        when(fixture.client.listVmBackupSets("ablecube4")).thenReturn(List.of(own));
        when(fixture.client.deleteVmBackupSet(own)).thenReturn(false);
        assertFalse(fixture.provider.cleanupBackupOffering(fixture.offering));
    }

    @Test
    public void offeringDeletionStopsWhenARecordedPlanIsUnconfirmedOrChanged() {
        CleanupFixture fixture = new CleanupFixture();
        AblestackCommvaultClient.VmBackupSet own = fixture.reference("i-2-220-VM", "9", "7");
        when(fixture.details.listDetails(3L)).thenReturn(List.of(new BackupOfferingDetailsVO(3L,
                "commvault.managed.backupset.4.220", new com.google.gson.Gson().toJson(own), false)));
        for (String plan : List.of("", "8")) {
            AblestackCommvaultClient.VmBackupSet current = fixture.reference("i-2-220-VM", "9", plan);
            when(fixture.client.listVmBackupSets("ablecube4")).thenReturn(List.of(current));

            assertThrows(CloudRuntimeException.class, () -> fixture.provider.cleanupBackupOffering(fixture.offering));
        }
        verify(fixture.client, never()).deleteVmBackupSet(any());
    }

    @Test
    public void offeringDeletionRefusesToCleanWhileBackupsOrAssignmentsRemain() {
        CleanupFixture fixture = new CleanupFixture();
        when(fixture.backups.listByOfferingId(3L)).thenReturn(List.of(mock(Backup.class)));
        assertThrows(CloudRuntimeException.class, () -> fixture.provider.cleanupBackupOffering(fixture.offering));
        when(fixture.backups.listByOfferingId(3L)).thenReturn(List.of());
        when(fixture.vms.listByZoneAndBackupOffering(1L, 3L)).thenReturn(List.of(mock(VMInstanceVO.class)));
        assertThrows(CloudRuntimeException.class, () -> fixture.provider.cleanupBackupOffering(fixture.offering));
        verify(fixture.client, never()).deleteVmBackupSet(any());
    }

    private static class CleanupFixture {
        final AblestackCommvaultClient client = mock(AblestackCommvaultClient.class);
        final BackupDao backups = mock(BackupDao.class);
        final VMInstanceDao vms = mock(VMInstanceDao.class);
        final HostDao hosts = mock(HostDao.class);
        final BackupOfferingDetailsDao details = mock(BackupOfferingDetailsDao.class);
        final BackupOffering offering = mock(BackupOffering.class);
        final AblestackCommvaultBackupProvider provider = new AblestackCommvaultBackupProvider() {
            @Override AblestackCommvaultClient getOrCreateClient(Long zoneId, ClientSettings settings) { return client; }
        };

        @SuppressWarnings("unchecked")
        CleanupFixture() {
            when(offering.getId()).thenReturn(3L);
            when(offering.getZoneId()).thenReturn(1L);
            when(offering.getProvider()).thenReturn("ablestack-commvault");
            when(offering.getExternalId()).thenReturn("7");
            when(vms.createSearchCriteria()).thenReturn(mock(SearchCriteria.class));
            when(hosts.createSearchCriteria()).thenReturn(mock(SearchCriteria.class));
            ReflectionTestUtils.setField(provider, "backupDao", backups);
            ReflectionTestUtils.setField(provider, "vmInstanceDao", vms);
            ReflectionTestUtils.setField(provider, "hostDao", hosts);
            ReflectionTestUtils.setField(provider, "backupOfferingDetailsDao", details);
        }

        VMInstanceVO vm(String name, long zoneId) {
            VMInstanceVO vm = mock(VMInstanceVO.class);
            when(vm.getInstanceName()).thenReturn(name);
            when(vm.getDataCenterId()).thenReturn(zoneId);
            when(vm.getType()).thenReturn(VirtualMachine.Type.User);
            when(vm.getHypervisorType()).thenReturn(Hypervisor.HypervisorType.KVM);
            return vm;
        }

        HostVO host(String name, long zoneId) {
            HostVO host = mock(HostVO.class);
            when(host.getName()).thenReturn(name);
            when(host.getDataCenterId()).thenReturn(zoneId);
            when(host.getType()).thenReturn(Host.Type.Routing);
            when(host.getHypervisorType()).thenReturn(Hypervisor.HypervisorType.KVM);
            return host;
        }

        AblestackCommvaultClient.VmBackupSet reference(String vmName, String id, String plan) {
            return new AblestackCommvaultClient.VmBackupSet("ablecube4", vmName, id, plan);
        }
    }

    @Test
    public void migratedRunningVmUsesOnlyTheNewHostsClientAndRecordsItsBackupSet() {
        CleanupFixture fixture = new CleanupFixture();
        ReflectionTestUtils.setField(fixture.provider, "vmSnapshotDao", mock(VMSnapshotDao.class));
        VMInstanceVO vm = fixture.vm("i-2-220-VM", 1L);
        when(vm.getId()).thenReturn(220L);
        when(vm.getState()).thenReturn(VirtualMachine.State.Running);
        when(vm.getHostId()).thenReturn(2L);
        HostVO host = fixture.host("ablecube2", 1L);
        when(host.getId()).thenReturn(2L);
        when(host.getStatus()).thenReturn(com.cloud.host.Status.Up);
        when(fixture.hosts.findById(2L)).thenReturn(host);
        when(fixture.client.prepareVmBackupSet("ablecube2", "i-2-220-VM", "7")).thenReturn("22");

        assertTrue(fixture.provider.assignVMToBackupOffering(vm, fixture.offering));

        verify(fixture.client).prepareVmBackupSet("ablecube2", "i-2-220-VM", "7");
        verify(fixture.client, never()).prepareVmBackupSet(eq("ablecube1"), any(), any());
        verify(fixture.details).addDetail(eq(3L), eq("commvault.managed.backupset.2.220"), any(), eq(false));
    }

    @Test
    public void migrationKeepsRbdIncrementalEligibilityButRequiresANewQcow2Chain() {
        AblestackCommvaultBackupProvider provider = new AblestackCommvaultBackupProvider();
        BackupVO previous = new BackupVO();
        previous.setDetails(new HashMap<>());
        previous.getDetails().put("commvault.stage.host", "ablecube1");
        previous.getDetails().put("commvault.backup.engine", "RBD_DIFF");
        HostVO current = mock(HostVO.class);
        when(current.getName()).thenReturn("ablecube2");
        VirtualMachine vm = mock(VirtualMachine.class);

        assertEquals(Boolean.TRUE, ReflectionTestUtils.invokeMethod(provider, "canContinueIncrementalChain", vm, previous, current));
        previous.getDetails().put("commvault.backup.engine", "QCOW2");
        assertEquals(Boolean.FALSE, ReflectionTestUtils.invokeMethod(provider, "canContinueIncrementalChain", vm, previous, current));
    }
}
