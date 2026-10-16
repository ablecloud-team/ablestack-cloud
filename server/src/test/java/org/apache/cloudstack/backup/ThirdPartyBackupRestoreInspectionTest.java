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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.Command;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.storage.Volume;
import com.google.gson.Gson;
import org.apache.cloudstack.api.response.BackupStagingInfoResponse;
import org.apache.cloudstack.backup.dao.BackupDao;
import org.apache.cloudstack.backup.dao.BackupDetailsDao;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class ThirdPartyBackupRestoreInspectionTest {
    private final Gson gson = new Gson();
    private final Map<String, String> details = new HashMap<>();
    private ThirdPartyBackupVolumeServiceImpl service;
    private ThirdPartyBackupRestore.Plan plan;
    private ThirdPartyBackupRestore.Operation operation;
    private ThirdPartyBackupAdmission.Entry admission;
    private BackupVO backup;
    private AgentManager agent;
    private HostDao hosts;

    @Before
    public void setup() throws Exception {
        ThirdPartyBackupManifest manifest = ThirdPartyBackupManifest.create("ablestack-commvault", "backup-1", "vm-1",
                "2026.10.15.12.52.56", "FULL", "host-4", "/staging/ablestack-commvault/vm-1/backup-1", "QCOW2",
                Collections.singletonList(new Backup.VolumeInfo("volume-1", "root.qcow2", Volume.Type.ROOT,
                        1073741824L, 0L, null, null, null)), null, null);
        ThirdPartyBackupManifest.Artifact metadata = new ThirdPartyBackupManifest.Artifact();
        metadata.backupUuid = manifest.getBackupUuid();
        metadata.path = "/staging/ablestack-commvault/vm-1/backup-1";
        manifest.setMetadata(metadata);
        plan = new ThirdPartyBackupRestore.Plan();
        plan.jobId = "restore-attempt-1";
        plan.hostId = 4L;
        plan.hostName = "host-4";
        plan.manifest = manifest;
        plan.targetVmName = "vm-1";
        plan.stageRoot = "/staging";
        plan.destination = "/staging/ablestack-commvault/restore/" + plan.jobId;
        plan.volumeUuids = Collections.singletonList("volume-1");
        operation = new ThirdPartyBackupRestore.Operation();
        operation.plan = plan;
        operation.hostState = "COMPLETED";
        operation.hostStep = "COMPLETED";
        operation.hostCheckedAt = 1234L;
        operation.cleanupState = "COMPLETED";
        admission = new ThirdPartyBackupAdmission.Entry();
        admission.jobId = plan.jobId;
        admission.hostId = plan.hostId;
        admission.operation = "RESTORE";
        admission.state = "RELEASED";
        details.put(ThirdPartyBackupManifest.MODE_KEY, ThirdPartyBackupManifest.VOLUME_MODE);
        details.put(ThirdPartyBackupManifest.DETAIL_KEY, manifest.toJson());
        details.put(ThirdPartyBackupRestore.PLAN_KEY, gson.toJson(plan));
        backup = mock(BackupVO.class);
        when(backup.getId()).thenReturn(1L);
        when(backup.getUuid()).thenReturn(manifest.getBackupUuid());
        when(backup.getDetail(anyString())).thenAnswer(call -> details.get(call.getArgument(0)));
        BackupDao backups = mock(BackupDao.class);
        when(backups.findById(1L)).thenReturn(backup);
        BackupDetailsDao detailDao = mock(BackupDetailsDao.class);
        when(detailDao.findDetail(eq(1L), anyString())).thenAnswer(call -> {
            String key = call.getArgument(1);
            return details.containsKey(key) ? new BackupDetailVO(1L, key, details.get(key), false) : null;
        });
        when(detailDao.listDetails(1L)).thenAnswer(call -> details.entrySet().stream()
                .map(entry -> new BackupDetailVO(1L, entry.getKey(), entry.getValue(), false)).collect(Collectors.toList()));
        doAnswer(call -> {
            details.put(call.getArgument(1), call.getArgument(2));
            return null;
        }).when(detailDao).addDetail(eq(1L), anyString(), anyString(), eq(false));
        agent = mock(AgentManager.class);
        hosts = mock(HostDao.class);
        HostVO host = mock(HostVO.class);
        when(host.getId()).thenReturn(plan.hostId);
        when(host.getName()).thenReturn(plan.hostName);
        when(hosts.findById(plan.hostId)).thenReturn(host);
        when(agent.send(eq(plan.hostId), any(Command.class))).thenAnswer(call -> {
            Command command = call.getArgument(1);
            if (command instanceof AblestackRestoreJobStatusCommand) {
                BackupAnswer status = new BackupAnswer(command, true, "Restoring volume");
                status.setState("RUNNING");
                status.setStep("PREPARE_VOLUME");
                return status;
            }
            return new Answer(command, false, "Staging admission request is not ready");
        });
        service = new ThirdPartyBackupVolumeServiceImpl();
        ReflectionTestUtils.setField(service, "backupDao", backups);
        ReflectionTestUtils.setField(service, "backupDetailsDao", detailDao);
        ReflectionTestUtils.setField(service, "agentManager", agent);
        ReflectionTestUtils.setField(service, "hostDao", hosts);
    }

    private BackupStagingInfoResponse inspect() {
        details.put(ThirdPartyBackupRestore.operationKey(plan.jobId), gson.toJson(operation));
        details.put(ThirdPartyBackupRestore.CLEANUP_STATE_KEY, operation.cleanupState);
        details.put(ThirdPartyBackupAdmission.RESTORE_KEY, gson.toJson(admission));
        return ReflectionTestUtils.invokeMethod(service, "inspectLocked", backup, "RESTORE");
    }

    private void vmResult(String outcome, String phase, String cleanup) {
        ThirdPartyBackupRestore.VmResult result = new ThirdPartyBackupRestore.VmResult();
        result.jobId = plan.jobId;
        result.sourceBackupUuid = plan.manifest.getBackupUuid();
        result.provider = plan.manifest.getProvider();
        result.targetVmName = plan.targetVmName;
        result.transactionId = "transaction-1";
        result.revision = result.updatedAt = 1L;
        result.outcome = outcome;
        result.phase = phase;
        result.primaryCleanupState = cleanup;
        result.committedAt = "COMMITTED".equals(outcome) ? 1L : 0;
        result.rolledBackAt = "ROLLED_BACK".equals(outcome) ? 1L : 0;
        ThirdPartyBackupRestore.VmVolumeResult volume = new ThirdPartyBackupRestore.VmVolumeResult();
        volume.volumeUuid = "volume-1";
        volume.poolUuid = "pool-1";
        volume.destination = "/primary/volume-1";
        volume.hadOriginal = true;
        volume.prepareState = "PREPARED";
        volume.switchState = "SWITCHED";
        volume.rollbackState = "ROLLED_BACK";
        result.volumes.add(volume);
        operation.vmResult = result;
    }

    @Test
    public void cleanedRestoreUsesRecordedStatusWithoutQueryingRemovedHostFiles() {
        BackupStagingInfoResponse info = inspect();
        assertEquals("COMPLETED", info.hoststate);
        assertEquals("COMPLETED", info.step);
        assertEquals("RELEASED", info.reservationstate);
        assertEquals(Long.valueOf(0), info.reservedbytes);
        assertEquals(1234L, info.hostcheckedat.getTime());
        assertNull(info.hosterror);
        assertNull(info.reservationerror);
        verifyNoInteractions(agent, hosts);
    }

    @Test
    public void oldUnknownInspectionCanDisplayValidatedCommittedOutcomeAfterCleanup() {
        operation.hostState = operation.hostStep = "UNKNOWN";
        vmResult("COMMITTED", "COMPLETED", "COMPLETED");
        BackupStagingInfoResponse info = inspect();
        assertEquals("COMPLETED", info.hoststate);
        assertEquals("COMPLETED", info.step);
        assertEquals("COMMITTED", info.vmrestore.outcome);
        assertEquals("UNKNOWN", gson.fromJson(details.get(ThirdPartyBackupRestore.operationKey(plan.jobId)),
                ThirdPartyBackupRestore.Operation.class).hostState);
        verifyNoInteractions(agent);
    }

    @Test
    public void cleanedRollbackDoesNotBecomeASuccessfulRestore() {
        operation.hostState = operation.hostStep = "UNKNOWN";
        vmResult("ROLLED_BACK", "ROLLED_BACK", "COMPLETED");
        BackupStagingInfoResponse info = inspect();
        assertEquals("FAILED", info.hoststate);
        assertEquals("ROLLED_BACK", info.vmrestore.outcome);
        verifyNoInteractions(agent);
    }

    @Test
    public void archivedFailureAndItsStepRemainVisible() {
        operation.hostState = "FAILED";
        operation.hostStep = "START_FAILED";
        BackupStagingInfoResponse info = inspect();
        assertEquals("FAILED", info.hoststate);
        assertEquals("START_FAILED", info.step);
        verifyNoInteractions(agent);
    }

    @Test
    public void partialPrimaryCleanupCannotRecoverUnknownAsCompleted() {
        operation.hostState = operation.hostStep = "UNKNOWN";
        vmResult("COMMITTED", "COMMITTED", "WAITING");
        assertEquals("UNKNOWN", inspect().hoststate);
    }

    @Test
    public void pendingCleanupStillQueriesHostAndReportsReservationErrors() throws Exception {
        operation.cleanupState = "WAITING";
        admission.state = "ADMITTED";
        BackupStagingInfoResponse info = inspect();
        assertEquals("RUNNING", info.hoststate);
        assertEquals("PREPARE_VOLUME", info.step);
        assertEquals("Staging admission request is not ready", info.reservationerror);
        verify(agent).send(eq(plan.hostId), any(AblestackRestoreJobStatusCommand.class));
        verify(agent).send(eq(plan.hostId), any(AblestackVolumeStagingCommand.class));
        ThirdPartyBackupRestore.Operation saved = gson.fromJson(details.get(ThirdPartyBackupRestore.operationKey(plan.jobId)),
                ThirdPartyBackupRestore.Operation.class);
        assertEquals("PREPARE_VOLUME", saved.hostStep);
    }

    @Test
    public void unreleasedReservationStillRequiresLiveInspection() throws Exception {
        admission.state = "ADMITTED";
        BackupStagingInfoResponse info = inspect();
        assertTrue(info.reservationerror.contains("not ready"));
        verify(agent).send(eq(plan.hostId), any(AblestackVolumeStagingCommand.class));
    }
}
