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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.cloud.configuration.Resource;
import com.cloud.user.ResourceLimitService;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.Gson;
import org.apache.cloudstack.backup.dao.BackupDao;
import org.apache.cloudstack.backup.dao.BackupDetailsDao;
import org.apache.cloudstack.backup.dao.BackupOfferingDao;
import org.apache.cloudstack.framework.jobs.AsyncJobManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class BackupDeletionServiceImplTest {
    private BackupDeletionServiceImpl service;
    private BackupDao backups;
    private BackupDetailsDao details;
    private BackupOfferingDao offerings;
    private ResourceLimitService counts;
    private AsyncJobManager jobs;
    private final Map<Long, BackupVO> active = new LinkedHashMap<>();
    private final Map<Long, Map<String, String>> metadata = new HashMap<>();
    private MockedStatic<Transaction> transaction;

    @Before
    public void setup() {
        active.clear(); metadata.clear();
        service = new BackupDeletionServiceImpl();
        backups = mock(BackupDao.class);
        details = mock(BackupDetailsDao.class);
        counts = mock(ResourceLimitService.class);
        jobs = mock(AsyncJobManager.class);
        offerings = mock(BackupOfferingDao.class);
        BackupOfferingVO offering = mock(BackupOfferingVO.class);
        when(offering.getProvider()).thenReturn("ablestack-nas");
        when(offerings.findByIdIncludingRemoved(1L)).thenReturn(offering);
        ThirdPartyBackupVolumeService volumes = mock(ThirdPartyBackupVolumeService.class);
        when(volumes.acquireLifecycle(2L)).thenReturn(() -> { });
        when(backups.findById(anyLong())).thenAnswer(call -> active.get(call.getArgument(0)));
        when(backups.lockRow(anyLong(), eq(true))).thenAnswer(call -> active.get(call.getArgument(0)));
        when(backups.findByUuid(anyString())).thenAnswer(call -> active.values().stream()
                .filter(b -> b.getUuid().equals(call.getArgument(0))).findFirst().orElse(null));
        when(backups.listByVmId(isNull(), eq(2L))).thenAnswer(call -> new ArrayList<Backup>(active.values()));
        when(backups.remove(anyLong())).thenAnswer(call -> active.remove(call.getArgument(0)) != null);
        doAnswer(call -> {
            metadata.get(call.getArgument(0)).put(call.getArgument(1), call.getArgument(2));
            return null;
        }).when(details).addDetail(anyLong(), anyString(), anyString(), eq(false));
        ReflectionTestUtils.setField(service, "backupDao", backups);
        ReflectionTestUtils.setField(service, "detailsDao", details);
        ReflectionTestUtils.setField(service, "offeringDao", offerings);
        ReflectionTestUtils.setField(service, "volumeService", volumes);
        ReflectionTestUtils.setField(service, "resourceLimitService", counts);
        ReflectionTestUtils.setField(service, "asyncJobManager", jobs);
        ReflectionTestUtils.setField(service, "instanceCleanup", mock(AblestackRestoreInstanceCleanup.class));
        transaction = Mockito.mockStatic(Transaction.class);
        transaction.when(() -> Transaction.execute(Mockito.<TransactionCallback<Boolean>>any()))
                .thenAnswer(call -> ((TransactionCallback<Boolean>) call.getArgument(0)).doInTransaction(null));
    }

    @After
    public void teardown() { transaction.close(); }

    private BackupVO point(long id, Long parent) {
        BackupVO backup = mock(BackupVO.class);
        Map<String, String> data = new HashMap<>();
        metadata.put(id, data);
        when(backup.getId()).thenReturn(id);
        when(backup.getUuid()).thenReturn("backup-" + id);
        when(backup.getName()).thenReturn("point-" + id);
        when(backup.getType()).thenReturn(parent == null ? "FULL" : "INCREMENTAL");
        when(backup.getVmId()).thenReturn(2L);
        when(backup.getBackupOfferingId()).thenReturn(1L);
        when(backup.getStatus()).thenReturn(Backup.Status.BackedUp);
        when(backup.getAccountId()).thenReturn(7L);
        when(backup.getSize()).thenReturn(100L);
        when(backup.getDetails()).thenReturn(data);
        when(backup.getDetail(anyString())).thenAnswer(call -> data.get(call.getArgument(0)));
        if (parent != null) { data.put("nas.parent.backup.uuid", "backup-" + parent); }
        active.put(id, backup);
        return backup;
    }

    private List<Long> ids(BackupDeletionService.Plan plan) {
        return plan.backups.stream().map(e -> e.id).collect(Collectors.toList());
    }

    @Test
    public void forceDeletesOnlySelectedTailAndKeepsPrefixIndependentChainsAndSiblingBranches() {
        point(1, null); Backup root = point(2, 1L); point(3, 2L); point(4, 1L); point(5, null);
        List<Long> deleted = new ArrayList<>();
        assertEquals(List.of(3L, 2L), ids(service.plan(root, true)));
        service.delete(root, true, null, backup -> { deleted.add(backup.getId()); return true; });
        assertEquals(List.of(3L, 2L), deleted);
        assertEquals(List.of(1L, 4L, 5L), new ArrayList<>(active.keySet()));
        verify(counts, Mockito.times(2)).decrementResourceCount(7L, Resource.ResourceType.backup);
    }

    @Test
    public void ordinaryDeleteDefersParentAndLeafRemovalSweepsDeferredAncestors() {
        Backup full = point(1, null); Backup inc = point(2, 1L); Backup leaf = point(3, 2L);
        List<Long> deleted = new ArrayList<>();
        BackupDeletionService.Cleanup cleanup = backup -> { deleted.add(backup.getId()); return true; };
        assertTrue(service.delete(full, false, null, cleanup));
        assertTrue(service.delete(inc, false, null, cleanup));
        assertTrue(deleted.isEmpty());
        assertTrue(BackupDeletionService.isPending(full));
        assertEquals("WAITING_DEPENDENCIES", new Gson().fromJson(metadata.get(1L).get(BackupDeletionService.REQUEST_KEY), BackupDeletionService.Plan.class).state);
        service.delete(leaf, false, null, cleanup);
        assertEquals(List.of(3L, 2L, 1L), deleted);
        assertTrue(active.isEmpty());
    }

    @Test
    public void failedMoldOnlyPointUsesOrdinaryDeletionAndDoesNotDecrementUncountedResources() {
        BackupVO backup = point(1, null);
        when(backup.getStatus()).thenReturn(Backup.Status.Failed);
        metadata.get(1L).put(AblestackBackupFrameworkUtils.RESOURCE_COUNT_PENDING_DETAIL, "true");
        assertTrue(service.delete(backup, false, null, point -> true));
        assertTrue(active.isEmpty());
        verify(counts, never()).decrementResourceCount(anyLong(), any(Resource.ResourceType.class));
    }

    @Test
    public void independentFullHasSameScopeWithAndWithoutForce() {
        Backup backup = point(1, null); point(2, null);
        assertEquals(ids(service.plan(backup, false)), ids(service.plan(backup, true)));
    }

    @Test
    public void partialFailureKeepsRootAndResumesWithoutRepeatingCompletedRemoteDeletion() {
        Backup root = point(1, null); point(2, 1L); point(3, 2L);
        List<Long> attempts = new ArrayList<>();
        assertThrows(CloudRuntimeException.class, () -> service.delete(root, true, null, point -> {
            attempts.add(point.getId()); return point.getId() != 2;
        }));
        assertEquals(List.of(1L, 2L), new ArrayList<>(active.keySet()));
        assertTrue(metadata.get(1L).get(BackupDeletionService.REQUEST_KEY).contains("WAITING"));
        assertTrue(service.resume(root, point -> { attempts.add(point.getId()); return true; }));
        assertEquals(List.of(3L, 2L, 2L, 1L), attempts);
        assertTrue(active.isEmpty());
        service.delete(root, true, null, point -> { throw new AssertionError("Already removed"); });
        verify(counts, Mockito.times(3)).decrementResourceCount(7L, Resource.ResourceType.backup);
    }

    @Test
    public void cleanedArtifactProofSurvivesDatabaseRemovalFailure() {
        Backup root = point(1, null);
        doReturn(false).doAnswer(call -> active.remove(1L) != null).when(backups).remove(1L);
        List<Long> attempts = new ArrayList<>();
        BackupDeletionService.Cleanup cleanup = point -> { attempts.add(point.getId()); return true; };
        assertThrows(CloudRuntimeException.class, () -> service.delete(root, false, null, cleanup));
        assertEquals("true", metadata.get(1L).get(BackupDeletionService.CLEANED_KEY));
        service.resume(root, cleanup);
        assertEquals(List.of(1L), attempts);
        assertTrue(active.isEmpty());
    }

    @Test
    public void changedPreviewRejectsNewDependentsBeforeAnyMutation() {
        Backup root = point(1, null);
        String preview = service.plan(root, true).fingerprint;
        point(2, 1L);
        assertThrows(CloudRuntimeException.class, () -> service.delete(root, true, preview, point -> { throw new AssertionError("No delete"); }));
        assertFalse(BackupDeletionService.isPending(root));
        verify(details, never()).addDetail(anyLong(), anyString(), anyString(), eq(false));
    }

    @Test
    public void forceDoesNotBypassActiveRestoreOrFailedInstanceCleanup() {
        Backup root = point(1, null); point(2, 1L);
        metadata.get(2L).put(AblestackBackupFrameworkUtils.RESTORE_JOB_STATE_DETAIL, "RUNNING");
        assertThrows(CloudRuntimeException.class, () -> service.delete(root, true, null, point -> true));
        assertFalse(BackupDeletionService.isPending(root));
    }

    @Test
    public void cyclicChainFailsClosed() {
        Backup root = point(1, 2L); point(2, 1L);
        assertThrows(CloudRuntimeException.class, () -> service.plan(root, true));
        assertFalse(BackupDeletionService.isPending(root));
    }

    @Test
    public void volumeManifestFindsDescendantEvenWhenIntermediateDbPointIsMissing() {
        Backup root = point(1, null); Backup leaf = point(3, null);
        ThirdPartyBackupManifest manifest = new ThirdPartyBackupManifest();
        manifest.setProvider("ablestack-netbackup"); manifest.setBackupUuid("backup-3");
        manifest.setVmName("i-2-VM"); manifest.setTimestamp("now");
        ThirdPartyBackupManifest.Volume volume = new ThirdPartyBackupManifest.Volume();
        volume.uuid = "volume"; volume.deviceId = 0; volume.provisionedBytes = 100; volume.engine = "RBD_DIFF";
        for (String uuid : List.of("backup-1", "backup-2", "backup-3")) {
            ThirdPartyBackupManifest.Artifact artifact = new ThirdPartyBackupManifest.Artifact();
            artifact.backupUuid = uuid; artifact.path = "/stage/" + uuid; volume.chain.add(artifact);
        }
        manifest.setVolumes(List.of(volume));
        metadata.get(3L).put(ThirdPartyBackupManifest.MODE_KEY, ThirdPartyBackupManifest.VOLUME_MODE);
        metadata.get(3L).put(ThirdPartyBackupManifest.DETAIL_KEY, manifest.toJson());
        assertEquals(List.of(3L, 1L), ids(BackupDeletionServiceImpl.buildPlan(root, true, List.of(root, leaf))));
    }
    @Test
    public void ordinaryDeferredRequestCanBeUpgradedToForce() {
        Backup root = point(1, null); point(2, 1L);
        service.delete(root, false, null, point -> { throw new AssertionError("Deferred"); });
        List<Long> deleted = new ArrayList<>();
        service.delete(root, true, null, point -> { deleted.add(point.getId()); return true; });
        assertEquals(List.of(2L, 1L), deleted);
        assertTrue(active.isEmpty());
    }

    @Test
    public void forcedRetryCannotExpandItsAcceptedScope() {
        Backup root = point(1, null); point(2, 1L);
        assertThrows(CloudRuntimeException.class, () -> service.delete(root, true, null, point -> false));
        point(3, 2L);
        assertThrows(CloudRuntimeException.class, () -> service.delete(root, true, null, point -> { throw new AssertionError("Scope expanded"); }));
        assertEquals(3, active.size());
    }

    @Test
    public void legacyVeeamParentDetailIsIncluded() {
        Backup root = point(1, null); Backup leaf = point(2, null);
        metadata.get(2L).put("ablestack.veeam.parent.backup.uuid", "backup-1");
        assertEquals(List.of(2L, 1L), ids(BackupDeletionServiceImpl.buildPlan(root, true, List.of(root, leaf))));
    }

    @Test
    public void anotherProviderCannotBeIncludedInTheDeletionScope() {
        Backup root = point(1, null); BackupVO other = point(2, 1L);
        when(other.getBackupOfferingId()).thenReturn(99L);
        BackupOfferingVO kboss = mock(BackupOfferingVO.class);
        when(kboss.getProvider()).thenReturn("kboss");
        when(offerings.findByIdIncludingRemoved(99L)).thenReturn(kboss);
        assertEquals(List.of(1L), ids(service.plan(root, true)));
    }

    @Test
    public void detachedInstanceCreationPreventsDeletion() {
        Backup root = point(1, null);
        metadata.get(1L).put(AblestackBackupFrameworkUtils.RESTORE_CREATE_INSTANCE_PROGRESS_DETAIL, "{\"state\":\"RUNNING\"}");
        assertThrows(CloudRuntimeException.class, () -> service.delete(root, true, null, point -> true));
        assertFalse(BackupDeletionService.isPending(root));
    }

    @Test
    public void failedInstanceCleanupCannotBeBypassedWithForce() {
        Backup root = point(1, null);
        AblestackRestoreInstanceCleanup cleanup = (AblestackRestoreInstanceCleanup) ReflectionTestUtils.getField(service, "instanceCleanup");
        when(cleanup.isPending(1L)).thenReturn(true);
        assertThrows(CloudRuntimeException.class, () -> service.delete(root, true, null, point -> true));
        assertFalse(BackupDeletionService.isPending(root));
    }

    @Test
    public void explicitDeletionDoesNotAdoptAnotherActiveForcedRequest() {
        Backup root = point(1, null); Backup child = point(2, 1L);
        assertThrows(CloudRuntimeException.class, () -> service.delete(root, true, null, point -> false));
        assertThrows(CloudRuntimeException.class, () -> service.delete(child, true, null, point -> true));
        assertEquals("backup-1", metadata.get(2L).get(BackupDeletionService.OWNER_KEY));
        assertEquals(2, active.size());
    }

}
