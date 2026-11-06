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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.cloudstack.backup.dao.BackupDao;
import org.apache.cloudstack.backup.dao.BackupDetailsDao;
import org.apache.cloudstack.resourcedetail.dao.DiskOfferingDetailsDao;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;
import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.host.Host;
import com.cloud.storage.Storage;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeAllocationPolicy;
import com.cloud.storage.VolumeDetailVO;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.storage.dao.VolumeDetailsDao;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.Gson;

@RunWith(MockitoJUnitRunner.class)
public class BackupVolumeAllocationServiceTest {
    @Mock private VolumeDetailsDao volumeDetailsDao;
    @Mock private DiskOfferingDetailsDao offeringDetailsDao;
    @Mock private VolumeDao volumeDao;
    @Mock private PrimaryDataStoreDao poolDao;
    @Mock private BackupDao backupDao;
    @Mock private BackupDetailsDao detailsDao;
    @Mock private AgentManager agentManager;
    @Spy @InjectMocks private BackupVolumeAllocationServiceImpl service;
    private static final String JOB = "restore-test";
    private static final String PATH = "/gfs/volume";

    private BackupVolumeAllocation policy(String backend, String policy) {
        BackupVolumeAllocation result = new BackupVolumeAllocation();
        result.volumeUuid = "volume"; result.volumeId = 1; result.accountId = 2; result.bytes = 4096;
        result.poolUuid = "pool"; result.backend = backend; result.format = backend.equals("GFS2") ? "qcow2" : "raw";
        result.intent = VolumeAllocationPolicy.request(policy, result.format, null);
        return result;
    }
    private BackupVO backup() {
        BackupVO backup = mock(BackupVO.class);
        when(backup.getId()).thenReturn(9L);
        return backup;
    }
    private VolumeVO volume(BackupVolumeAllocation policy) {
        VolumeVO volume = mock(VolumeVO.class);
        when(volume.getId()).thenReturn(1L); when(volume.getPoolId()).thenReturn(3L);
        when(volume.getUuid()).thenReturn("volume"); when(volume.getAccountId()).thenReturn(2L);
        when(volume.getSize()).thenReturn(4096L); when(volume.getFormat()).thenReturn(Storage.ImageFormat.valueOf(policy.format.toUpperCase(Locale.ROOT)));
        return volume;
    }
    private void intent(BackupVolumeAllocation policy) {
        List<VolumeDetailVO> values = new ArrayList<>();
        policy.intent.forEach((name, value) -> values.add(new VolumeDetailVO(1L, name, value, false)));
        when(volumeDetailsDao.listDetails(1L)).thenReturn(values);
    }
    @Test public void oldVolumesDoNotInferIntentFromTheirOffering() {
        VolumeVO volume = mock(VolumeVO.class);
        when(volume.getId()).thenReturn(1L);
        assertNull(service.capture(volume));
        verifyNoInteractions(offeringDetailsDao, poolDao, agentManager);
    }
    @Test public void exactIntentIsCapturedFromTheVolume() {
        var policy = policy("GFS2", "falloc"); var volume = volume(policy); intent(policy);
        StoragePoolVO pool = mock(StoragePoolVO.class);
        when(pool.getUuid()).thenReturn("pool"); when(pool.getPoolType()).thenReturn(Storage.StoragePoolType.SharedMountPoint);
        when(poolDao.findById(3L)).thenReturn(pool);
        var saved = service.capture(volume);
        assertEquals(policy.intent, saved.intent); assertEquals("qcow2", saved.format);
        verifyNoInteractions(offeringDetailsDao);
    }
    @Test public void oldAgentCannotSilentlyIgnoreTheNewRestorePolicy() throws Exception {
        var backup = backup(); Host host = mock(Host.class); when(host.getId()).thenReturn(7L);
        Volume volume = mock(Volume.class); doReturn(policy("RBD", "full")).when(service).capture(volume);
        when(agentManager.send(eq(7L), any(BackupRestoreAllocationCommand.class))).thenReturn(new Answer(null, true, null));
        assertThrows(CloudRuntimeException.class, () -> service.configureRestore(backup, host, JOB, List.of(volume), List.of(PATH)));
        verify(detailsDao, never()).addDetail(anyLong(), anyString(), anyString(), anyBoolean());
    }
    @Test public void preflightEchoMustMatchTheExactPolicy() throws Exception {
        var backup = backup(); Host host = mock(Host.class); when(host.getId()).thenReturn(7L);
        Volume volume = mock(Volume.class); doReturn(policy("GFS2", "falloc")).when(service).capture(volume);
        when(agentManager.send(eq(7L), any(BackupRestoreAllocationCommand.class))).thenAnswer(call -> {
            var command = (BackupRestoreAllocationCommand) call.getArgument(1);
            var result = new BackupVolumeAllocation.Result(); result.jobId = JOB; result.policies.put(PATH, policy("GFS2", "full"));
            return new BackupRestoreAllocationAnswer(command, result);
        });
        assertThrows(CloudRuntimeException.class, () -> service.configureRestore(backup, host, JOB, List.of(volume), List.of(PATH)));
    }
    @Test public void newAndLegacyVolumesCanShareOneRestoreRequest() throws Exception {
        var backup = backup(); Host host = mock(Host.class); when(host.getId()).thenReturn(7L);
        Volume exact = mock(Volume.class); Volume legacy = mock(Volume.class);
        var policy = policy("GFS2", "metadata"); doReturn(policy).when(service).capture(exact);
        doReturn(null).when(service).capture(legacy);
        when(agentManager.send(eq(7L), any(BackupRestoreAllocationCommand.class))).thenAnswer(call -> {
            var command = (BackupRestoreAllocationCommand) call.getArgument(1);
            var result = new BackupVolumeAllocation.Result(); result.jobId = JOB; result.policies = command.getPolicies();
            return new BackupRestoreAllocationAnswer(command, result);
        });
        assertEquals(Map.of(PATH, policy), service.configureRestore(backup, host, JOB, List.of(exact, legacy), List.of(PATH, "/gfs/old")));
        verify(detailsDao).addDetail(eq(9L), eq(BackupVolumeAllocation.restoreKey(JOB)), anyString(), eq(false));
    }
    @Test public void missingCommittedReceiptNeverPublishesAllocationEvidence() throws Exception {
        var backup = backup(); var policy = policy("RBD", "full");
        when(backup.getDetail(AblestackBackupFrameworkUtils.RESTORE_JOB_ID_DETAIL)).thenReturn(JOB);
        when(backup.getDetail(AblestackBackupFrameworkUtils.RESTORE_HOST_ID_DETAIL)).thenReturn("7");
        var expected = new BackupVolumeAllocation.Result(); expected.jobId = JOB; expected.policies = Map.of(PATH, policy);
        when(detailsDao.findDetail(9L, BackupVolumeAllocation.restoreKey(JOB))).thenReturn(new BackupDetailVO(9L, BackupVolumeAllocation.restoreKey(JOB), new Gson().toJson(expected), false));
        when(agentManager.send(eq(7L), any(BackupRestoreAllocationCommand.class))).thenReturn(new Answer(null, false, "missing"));
        assertThrows(CloudRuntimeException.class, () -> service.completeRestore(backup));
        verifyNoInteractions(volumeDao, volumeDetailsDao);
    }
    @Test public void changedRestoreRequestCannotReplaceAnEarlierRequest() {
        var backup = backup(); Host host = mock(Host.class);
        Volume volume = mock(Volume.class); doReturn(policy("GFS2", "falloc")).when(service).capture(volume);
        when(detailsDao.findDetail(9L, BackupVolumeAllocation.restoreKey(JOB))).thenReturn(new BackupDetailVO(9L, "old", "different", false));
        assertThrows(CloudRuntimeException.class, () -> service.configureRestore(backup, host, JOB, List.of(volume), List.of(PATH)));
        verifyNoInteractions(agentManager);
    }
    @Test public void committedRbdFullPublishesFreshCopyEvidenceAtomically() throws Exception {
        var backup = backup(); var policy = policy("RBD", "full");
        when(backup.getDetail(AblestackBackupFrameworkUtils.RESTORE_JOB_ID_DETAIL)).thenReturn(JOB);
        when(backup.getDetail(AblestackBackupFrameworkUtils.RESTORE_HOST_ID_DETAIL)).thenReturn("7");
        var expected = new BackupVolumeAllocation.Result(); expected.jobId = JOB; expected.policies = Map.of(PATH, policy);
        when(detailsDao.findDetail(9L, BackupVolumeAllocation.restoreKey(JOB))).thenReturn(new BackupDetailVO(9L, "allocation", new Gson().toJson(expected), false));
        VolumeVO volume = mock(VolumeVO.class);
        when(volume.getId()).thenReturn(1L); when(volume.getPoolId()).thenReturn(3L);
        when(volume.getSize()).thenReturn(4096L); when(volume.getFormat()).thenReturn(Storage.ImageFormat.RAW);
        when(volumeDao.findByUuid("volume")).thenReturn(volume);
        StoragePoolVO pool = mock(StoragePoolVO.class); when(pool.getUuid()).thenReturn("pool");
        when(poolDao.findById(3L)).thenReturn(pool); intent(policy);
        var result = new BackupVolumeAllocation.Result(); result.jobId = JOB; result.policies = expected.policies;
        result.evidence.put(PATH, VolumeAllocationPolicy.evidence(policy.intent, "RBD", "copy", "rbd-thick-convert-n-S0",
                4096L, Instant.now(), "rbd-du-exact-image-used-size"));
        when(agentManager.send(eq(7L), any(BackupRestoreAllocationCommand.class))).thenAnswer(call ->
                new BackupRestoreAllocationAnswer(call.getArgument(1), result));
        try (var transaction = mockStatic(com.cloud.utils.db.Transaction.class)) {
            transaction.when(() -> com.cloud.utils.db.Transaction.execute(any(com.cloud.utils.db.TransactionCallback.class)))
                    .thenAnswer(call -> ((com.cloud.utils.db.TransactionCallback<?>) call.getArgument(0)).doInTransaction(null));
            service.completeRestore(backup);
        }
        verify(volumeDetailsDao).addDetail(1L, VolumeAllocationPolicy.FIXED_POLICY, "full", false);
        verify(volumeDetailsDao).addDetail(1L, VolumeAllocationPolicy.METHOD, "rbd-thick-convert-n-S0", false);
        verify(detailsDao).addDetail(eq(9L), eq(BackupVolumeAllocation.restoreKey(JOB) + ".completed"), anyString(), eq(false));
    }

    @Test public void newRawRestoreTargetUsesTheSelectedOfferingFormat() {
        var target = new VolumeVO(Volume.Type.DATADISK, null, 1L, 1L, 1L, 0, null, 4096L, null, null, null); target.setUuid("new-volume"); target.setPoolId(3L);
        target.setSize(4096L); target.setDiskOfferingId(4L); target.setFormat(Storage.ImageFormat.QCOW2);
        Map<String, String> intent = VolumeAllocationPolicy.request("falloc", "raw", null);
        List<org.apache.cloudstack.resourcedetail.DiskOfferingDetailVO> details = new ArrayList<>();
        intent.forEach((key, value) -> details.add(new org.apache.cloudstack.resourcedetail.DiskOfferingDetailVO(4L, key, value, false)));
        when(offeringDetailsDao.listDetails(4L)).thenReturn(details);
        StoragePoolVO pool = mock(StoragePoolVO.class); when(pool.getUuid()).thenReturn("pool");
        when(pool.getPoolType()).thenReturn(Storage.StoragePoolType.SharedMountPoint);
        when(poolDao.findById(3L)).thenReturn(pool);
        var policy = service.capture(target);
        assertEquals("raw", policy.format); assertEquals(Storage.ImageFormat.RAW, target.getFormat());
        assertEquals("falloc", policy.intent.get(VolumeAllocationPolicy.POLICY));
    }

    @Test public void gfsRawWithoutAnExplicitAllocationContractFailsClosed() {
        VolumeVO source = mock(VolumeVO.class); when(source.getId()).thenReturn(1L);
        when(source.getFormat()).thenReturn(Storage.ImageFormat.RAW); when(source.getPoolId()).thenReturn(3L);
        StoragePoolVO pool = mock(StoragePoolVO.class); when(pool.getPoolType()).thenReturn(Storage.StoragePoolType.SharedMountPoint);
        when(poolDao.findById(3L)).thenReturn(pool);
        assertThrows(CloudRuntimeException.class, () -> service.capture(source));
    }

}
