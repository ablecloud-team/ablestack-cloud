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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.storage.Volume;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.VMInstanceDao;
import com.google.gson.Gson;
import org.apache.cloudstack.backup.dao.BackupDao;
import org.apache.cloudstack.backup.dao.BackupDetailsDao;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class ThirdPartyStoppedBackupStartTest {
    private final ThirdPartyBackupVolumeServiceImpl service = spy(new ThirdPartyBackupVolumeServiceImpl());
    private final ThirdPartyBackupVolumeService.Lease lease = mock(ThirdPartyBackupVolumeService.Lease.class);
    private final BackupDao backups = mock(BackupDao.class);
    private final BackupDetailsDao details = mock(BackupDetailsDao.class);
    private final AgentManager agents = mock(AgentManager.class);
    private final VMInstanceVO vm = mock(VMInstanceVO.class);
    private final BackupVO backup = mock(BackupVO.class);
    private final Map<String, String> saved = new HashMap<>();
    private ThirdPartyBackupStart.Operation start;

    @Before public void setup() {
        VMInstanceDao vms = mock(VMInstanceDao.class);
        ReflectionTestUtils.setField(service, "vmDao", vms);
        ReflectionTestUtils.setField(service, "backupDao", backups);
        ReflectionTestUtils.setField(service, "backupDetailsDao", details);
        ReflectionTestUtils.setField(service, "agentManager", agents);
        when(vms.findById(10L)).thenReturn(vm);
        when(vm.getType()).thenReturn(VirtualMachine.Type.User);
        when(vm.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(vm.getDataCenterId()).thenReturn(1L);
        when(backup.getId()).thenReturn(2L);
        when(backup.getUuid()).thenReturn("backup-1");
        when(backups.listByVmId(1L, 10L)).thenReturn(List.of(backup));
        when(details.findDetail(eq(2L), anyString())).thenAnswer(call -> {
            String value = saved.get(call.getArgument(1));
            return value == null ? null : new BackupDetailVO(2L, call.getArgument(1), value, false);
        });
        doAnswer(call -> { saved.put(call.getArgument(1), call.getArgument(2)); return null; })
                .when(details).addDetail(eq(2L), anyString(), anyString(), eq(false));
        doReturn(lease).when(service).acquireLifecycle(10L);
        start = new ThirdPartyBackupStart.Operation();
        start.state = "PREPARING";
        start.createdAt = 1;
        start.plan = new ThirdPartyBackupStart.Plan();
        start.plan.jobId = "backup-1";
        start.plan.hostId = 4;
        start.plan.backupPath = "/staging/ablestack-netbackup/vm/point";
        start.plan.protectStoppedSource = true;
        engine("RBD_DIFF");
    }

    private void engine(String engine) {
        start.plan.manifest = ThirdPartyBackupManifest.create("ablestack-netbackup", "backup-1", "vm", "point", "FULL",
                "host-4", start.plan.backupPath, engine,
                List.of(new Backup.VolumeInfo("volume-1", "root", Volume.Type.ROOT, 1073741824L, 0L, null, null, null)), null, null);
        saved.put(ThirdPartyBackupStart.DETAIL_KEY, new Gson().toJson(start));
    }

    private void proof(boolean released, String phase) throws Exception {
        ThirdPartyBackupStart.SourceProof proof = new ThirdPartyBackupStart.SourceProof();
        proof.plan = new Gson().fromJson(new Gson().toJson(start.plan), ThirdPartyBackupStart.Plan.class);
        proof.released = released;
        proof.phase = phase;
        when(agents.send(eq(4L), any(AblestackVolumeStagingCommand.class)))
                .thenAnswer(call -> new Answer(call.getArgument(1), true, new Gson().toJson(proof)));
    }

    @Test public void rbdPreparationBlocksAndClosesLifecycleLease() throws Exception {
        proof(false, "PROTECTED");
        assertThrows(CloudRuntimeException.class, () -> service.guardVmStart(10L));
        verify(lease).close();
    }

    @Test public void allRbdSnapshotsPermitStartDuringBackupAndCacheExactProof() throws Exception {
        when(backup.getStatus()).thenReturn(Backup.Status.BackingUp);
        proof(true, "SNAPSHOTS_COMPLETE");
        ThirdPartyBackupVolumeService.Lease held = service.guardVmStart(10L);
        verify(lease, never()).close();
        held.close();
        service.guardVmStart(10L).close();
        verify(agents).send(eq(4L), any(AblestackVolumeStagingCommand.class));
    }

    @Test public void qcow2CannotUseRbdSnapshotProof() throws Exception {
        engine("QCOW2");
        proof(true, "SNAPSHOTS_COMPLETE");
        assertThrows(CloudRuntimeException.class, () -> service.guardVmStart(10L));
    }

    @Test public void failedQcow2RequiresConfirmedDummyCleanup() throws Exception {
        engine("QCOW2");
        when(backup.getStatus()).thenReturn(Backup.Status.Failed);
        proof(false, "PROTECTED");
        assertThrows(CloudRuntimeException.class, () -> service.guardVmStart(10L));
        proof(true, "SOURCE_CLEANED");
        service.guardVmStart(10L).close();
    }

    @Test public void unreachableWorkerAndMissingProofFailClosed() throws Exception {
        when(agents.send(eq(4L), any(AblestackVolumeStagingCommand.class))).thenReturn(null);
        assertThrows(CloudRuntimeException.class, () -> service.guardVmStart(10L));
        verify(lease).close();
    }

    @Test public void proofFromDifferentHostCannotReleaseSource() throws Exception {
        ThirdPartyBackupStart.SourceProof proof = new ThirdPartyBackupStart.SourceProof();
        proof.plan = new Gson().fromJson(new Gson().toJson(start.plan), ThirdPartyBackupStart.Plan.class);
        proof.plan.hostId = 99;
        proof.released = true;
        proof.phase = "SOURCE_CLEANED";
        when(agents.send(eq(4L), any(AblestackVolumeStagingCommand.class)))
                .thenAnswer(call -> new Answer(call.getArgument(1), true, new Gson().toJson(proof)));
        assertThrows(CloudRuntimeException.class, () -> service.guardVmStart(10L));
    }

    @Test public void canceledUnstartedAttemptRequiresPermanentFence() throws Exception {
        when(backup.getStatus()).thenReturn(Backup.Status.Canceled);
        proof(true, "UNSTARTED_FENCED");
        service.guardVmStart(10L).close();
    }

    @Test public void runningSourceBackupDoesNotBlockStart() {
        start.plan.protectStoppedSource = false;
        saved.put(ThirdPartyBackupStart.DETAIL_KEY, new Gson().toJson(start));
        service.guardVmStart(10L).close();
        verifyNoInteractions(agents);
    }

    @Test public void systemVmStartDoesNotAcquireBackupLifecycle() {
        when(vm.getType()).thenReturn(VirtualMachine.Type.ConsoleProxy);
        service.guardVmStart(10L).close();
        verify(service, never()).acquireLifecycle(10L);
        verifyNoInteractions(agents);
    }

    private void nas(String engine) {
        start.plan.manifest = null;
        start.plan.backupPath = "vm/point";
        start.plan.nasSource = new ThirdPartyBackupStart.NasSource();
        start.plan.nasSource.vmName = "vm";
        start.plan.nasSource.engine = engine;
        start.plan.nasSource.backupType = "FULL";
        start.plan.nasSource.checkpointName = "point";
        start.plan.nasSource.volumeCount = 2;
        start.plan.nasSource.requestDigest = "a".repeat(64);
        saved.put(ThirdPartyBackupStart.DETAIL_KEY, new Gson().toJson(start));
        com.cloud.host.dao.HostDao hosts = mock(com.cloud.host.dao.HostDao.class);
        com.cloud.host.HostVO host = mock(com.cloud.host.HostVO.class);
        when(host.getId()).thenReturn(4L);
        when(hosts.findById(4L)).thenReturn(host);
        ReflectionTestUtils.setField(service, "hostDao", hosts);
    }

    private Answer receipt(AblestackVolumeStagingCommand command, String state) {
        ThirdPartyBackupStart.Receipt receipt = new ThirdPartyBackupStart.Receipt();
        receipt.plan = start.plan;
        receipt.state = state;
        receipt.checkedAt = 1;
        return new Answer(command, true, new Gson().toJson(receipt));
    }

    @Test public void nasRbdCanStartOnlyAfterItsEntireSnapshotSetCompletes() throws Exception {
        nas("RBD_DIFF");
        proof(false, "PROTECTED");
        assertThrows(CloudRuntimeException.class, () -> service.guardVmStart(10L));
        proof(true, "SNAPSHOTS_COMPLETE");
        service.guardVmStart(10L).close();
    }

    @Test public void nasQcow2RequiresDummyCleanupRatherThanSnapshotProof() throws Exception {
        nas("QCOW2");
        proof(true, "SNAPSHOTS_COMPLETE");
        assertFalse(service.isNasBackupSourceReleased(backup));
        proof(true, "SOURCE_CLEANED");
        assertTrue(service.isNasBackupSourceReleased(backup));
    }

    @Test public void nasUnstartedCleanupFencesDelayedCommandsBeforeReleasingProtection() throws Exception {
        nas("QCOW2");
        when(agents.send(eq(4L), any(AblestackVolumeStagingCommand.class))).thenAnswer(call -> {
            AblestackVolumeStagingCommand command = call.getArgument(1);
            if ("BACKUP_START_PREPARE".equals(command.getAction())) { return receipt(command, "PREPARED"); }
            if ("BACKUP_START_ABORT".equals(command.getAction())) { return receipt(command, "START_FAILED"); }
            ThirdPartyBackupStart.SourceProof proof = new ThirdPartyBackupStart.SourceProof();
            proof.plan = start.plan;
            proof.released = true;
            proof.phase = "UNSTARTED_FENCED";
            return new Answer(command, true, new Gson().toJson(proof));
        });
        assertTrue(service.cleanupNasBackupSource(backup));
        verify(agents, never()).send(eq(4L), org.mockito.ArgumentMatchers.argThat(
                (AblestackVolumeStagingCommand command) -> "NAS_SOURCE_CLEANUP".equals(command.getAction())));
        service.guardVmStart(10L).close();
    }

    @Test public void nasCleanupCannotTreatEarlyRbdReleaseAsWorkerTermination() throws Exception {
        nas("RBD_DIFF");
        when(agents.send(eq(4L), any(AblestackVolumeStagingCommand.class))).thenAnswer(call -> {
            AblestackVolumeStagingCommand command = call.getArgument(1);
            return "BACKUP_START_PREPARE".equals(command.getAction()) ? receipt(command, "STARTED")
                    : new Answer(command, false, "Worker is still active");
        });
        assertFalse(service.cleanupNasBackupSource(backup));
    }

    @Test public void nasPreparationPersistsOwnershipBeforeCheckingAgentCapabilities() throws Exception {
        nas("QCOW2");
        saved.remove(ThirdPartyBackupStart.DETAIL_KEY);
        when(vm.getState()).thenReturn(VirtualMachine.State.Stopped);
        when(vm.getInstanceName()).thenReturn("vm");
        when(backup.getVmId()).thenReturn(10L);
        when(backup.getHostId()).thenReturn(4L);
        when(backup.getExternalId()).thenReturn("vm/point");
        when(backup.getStatus()).thenReturn(Backup.Status.BackingUp);
        when(backups.lockRow(2L, true)).thenReturn(backup);
        when(agents.send(eq(4L), any(AblestackVolumeStagingCommand.class))).thenAnswer(call -> {
            assertTrue(saved.containsKey(ThirdPartyBackupStart.DETAIL_KEY));
            return new Answer(call.getArgument(1), false, "Older Agent cannot prepare a NAS source plan");
        });
        try (var transactions = org.mockito.Mockito.mockStatic(com.cloud.utils.db.Transaction.class)) {
            transactions.when(() -> com.cloud.utils.db.Transaction.execute(org.mockito.ArgumentMatchers
                    .<com.cloud.utils.db.TransactionCallback<Boolean>>any())).thenAnswer(call ->
                    call.<com.cloud.utils.db.TransactionCallback<Boolean>>getArgument(0).doInTransaction(null));
            assertThrows(CloudRuntimeException.class, () -> service.prepareNasBackupSource(backup, start.plan));
        }
        assertTrue(saved.containsKey(ThirdPartyBackupStart.DETAIL_KEY));
    }
}
