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
package org.apache.cloudstack.backup;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.cloud.storage.Volume;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VMInstanceVO;
import org.apache.cloudstack.backup.dao.BackupDao;
import org.apache.cloudstack.backup.dao.BackupOfferingDao;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.impl.ConfigDepotImpl;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class AblestackNetBackupCatalogSyncTest {
    private final AblestackNetBackupProvider provider = new AblestackNetBackupProvider();
    private final BackupDao backups = mock(BackupDao.class);
    private final ThirdPartyBackupVolumeService volumes = mock(ThirdPartyBackupVolumeService.class);
    private final ConfigDepotImpl depot = mock(ConfigDepotImpl.class);
    private final VMInstanceVO vm = mock(VMInstanceVO.class);
    private final Map<String, Backup> remaining = new LinkedHashMap<>();
    private final List<Backup> reconciled = new ArrayList<>();
    private final Set<String> retained = new HashSet<>();
    private final Set<String> failedCleanup = new HashSet<>();
    private ConfigDepotImpl originalDepot;

    @Before
    public void setup() {
        originalDepot = (ConfigDepotImpl) ReflectionTestUtils.getField(BackupManager.KvmIncrementalBackup, "s_depot");
        ConfigKey.init(depot);
        when(depot.getConfigStringValue("backup.plugin.netbackup.url", ConfigKey.Scope.Zone, 1L))
                .thenReturn("https://netbackup.example/netbackup");
        when(depot.getConfigStringValue("backup.plugin.netbackup.apikey", ConfigKey.Scope.Zone, 1L)).thenReturn("test-key");
        when(vm.getId()).thenReturn(10L);
        when(vm.getDataCenterId()).thenReturn(1L);
        when(backups.listByVmId(1L, 10L)).thenAnswer(call -> new ArrayList<>(remaining.values()));
        BackupOfferingDao offerings = mock(BackupOfferingDao.class);
        BackupOfferingVO offering = mock(BackupOfferingVO.class);
        when(offering.getProvider()).thenReturn("ablestack-netbackup");
        when(offerings.findById(2L)).thenReturn(offering);
        ReflectionTestUtils.setField(provider, "backupDao", backups);
        ReflectionTestUtils.setField(provider, "backupOfferingDao", offerings);
        ReflectionTestUtils.setField(provider, "backupDeletionService", mock(BackupDeletionService.class));
        ReflectionTestUtils.setField(provider, "thirdPartyBackupVolumeService", volumes);
        doAnswer(call -> {
            Backup backup = call.getArgument(0);
            reconciled.add(backup);
            if (failedCleanup.contains(backup.getUuid())) { throw new CloudRuntimeException("Worker Host unavailable"); }
            if (retained.contains(backup.getUuid())) { return null; }
            // Model the volume service's existing deletion contract: any remaining
            // dependent recovery point prevents removal of its ancestor artifacts.
            for (Backup other : remaining.values()) {
                if (other.getId() != backup.getId() && manifest(other).references(backup.getUuid())) { return null; }
            }
            manifest(backup);
            remaining.remove(backup.getUuid());
            return null;
        }).when(volumes).reconcileCatalog(any(), any(), any());
    }

    @After
    public void restoreConfigurationDepot() {
        ConfigKey.init(originalDepot);
    }

    @Test
    public void fullyExpiredOldestFirstChainIsRemovedInOneSync() {
        List<Backup> chain = new ArrayList<>();
        Backup parent = null;
        for (int i = 1; i <= 8; i++) {
            parent = point(i, parent);
            chain.add(0, parent);
        }

        provider.syncBackups(vm);

        assertEquals(chain, reconciled);
        assertTrue(remaining.isEmpty());
    }

    @Test
    public void shuffledBranchesUseDependenciesDespiteEqualOrSkewedDates() {
        Backup full = point(1, null);
        Backup first = point(2, full);
        Backup second = point(3, full);
        Backup leaf = point(4, first);
        ((BackupVO) full).setDate(new Date(System.currentTimeMillis() - 1200000));
        ((BackupVO) first).setDate(full.getDate());
        remaining.clear();
        for (Backup backup : List.of(first, full, leaf, second)) { remaining.put(backup.getUuid(), backup); }

        provider.syncBackups(vm);

        assertTrue(remaining.isEmpty());
        assertTrue(reconciled.indexOf(leaf) < reconciled.indexOf(first));
        assertTrue(reconciled.indexOf(first) < reconciled.indexOf(full));
        assertTrue(reconciled.indexOf(second) < reconciled.indexOf(full));
    }

    @Test
    public void retainedChildKeepsItsAncestorsWhileIndependentExpiredBackupIsRemoved() {
        Backup full = point(1, null);
        Backup child = point(2, full);
        Backup expiredLeaf = point(3, child);
        point(4, null);
        retained.add(child.getUuid());

        provider.syncBackups(vm);

        assertEquals(Set.of(full.getUuid(), child.getUuid()), remaining.keySet());
        assertTrue(reconciled.indexOf(expiredLeaf) < reconciled.indexOf(child));
        assertTrue(reconciled.indexOf(child) < reconciled.indexOf(full));
    }

    @Test
    public void childCleanupFailureKeepsItsAncestorsAndDoesNotAbortOtherChains() {
        Backup full = point(1, null);
        Backup child = point(2, full);
        point(3, null);
        failedCleanup.add(child.getUuid());

        provider.syncBackups(vm);

        assertEquals(Set.of(full.getUuid(), child.getUuid()), remaining.keySet());
        assertEquals(3, reconciled.size());
    }

    @Test
    public void malformedManifestDoesNotAbortCatalogChecksForOtherBackups() {
        Backup broken = point(1, null);
        Backup full = point(2, null);
        Backup child = point(3, full);
        broken.getDetails().put(ThirdPartyBackupManifest.DETAIL_KEY, "invalid-json");

        provider.syncBackups(vm);

        assertEquals(3, reconciled.size());
        assertTrue(reconciled.indexOf(child) < reconciled.indexOf(full));
        assertTrue(remaining.containsKey(broken.getUuid()));
    }

    @Test
    public void cyclicReferencesRemainProtectedWithoutBlockingIndependentBackups() {
        Backup full = point(1, null);
        Backup child = point(2, full);
        ThirdPartyBackupManifest cyclic = manifest(full);
        cyclic.getVolumes().get(0).chain.add(0, manifest(child).getCurrentArtifacts().get(0));
        full.getDetails().put(ThirdPartyBackupManifest.DETAIL_KEY, cyclic.toJson());
        point(3, null);

        provider.syncBackups(vm);

        assertEquals(Set.of(full.getUuid(), child.getUuid()), remaining.keySet());
        assertEquals(3, reconciled.size());
    }

    private Backup point(long id, Backup parent) {
        BackupVO backup = new BackupVO();
        ReflectionTestUtils.setField(backup, "id", id);
        backup.setVmId(10L);
        backup.setZoneId(1L);
        backup.setBackupOfferingId(2L);
        backup.setDate(new Date(System.currentTimeMillis() - 3600000 + id * 1000));
        backup.setStatus(Backup.Status.Failed);
        backup.setDetails(new HashMap<>());
        String timestamp = "point-" + id;
        String path = "/mnt/staging/ablestack-netbackup/i-2-10-VM/" + timestamp;
        ThirdPartyBackupManifest manifest = ThirdPartyBackupManifest.create("ablestack-netbackup", backup.getUuid(),
                "i-2-10-VM", timestamp, parent == null ? "FULL" : "INCREMENTAL", "host-test", path, "RBD_DIFF",
                List.of(new Backup.VolumeInfo("root-volume", parent == null ? "root.raw" : "root.rbdiff", Volume.Type.ROOT,
                        200L << 30, 0L, null, null, null)),
                parent == null ? null : parent.getDetail(ThirdPartyBackupManifest.DETAIL_KEY),
                parent == null ? null : manifest(parent).getTimestamp());
        ThirdPartyBackupManifest.Artifact metadata = new ThirdPartyBackupManifest.Artifact();
        metadata.backupUuid = backup.getUuid();
        metadata.path = path;
        manifest.setMetadata(metadata);
        for (ThirdPartyBackupManifest.Artifact artifact : manifest.getOwnedArtifacts()) {
            artifact.completed = true;
            artifact.sourceHost = "host-test";
            artifact.jobId = Long.toString(id);
            artifact.externalId = backup.getUuid();
        }
        manifest.setComplete(true);
        backup.getDetails().put(ThirdPartyBackupManifest.MODE_KEY, ThirdPartyBackupManifest.VOLUME_MODE);
        backup.getDetails().put(ThirdPartyBackupManifest.DETAIL_KEY, manifest.toJson());
        backup.getDetails().put(ThirdPartyBackupManifest.CATALOG_FAILURE_KEY, "Expired catalog image");
        remaining.put(backup.getUuid(), backup);
        return backup;
    }

    private ThirdPartyBackupManifest manifest(Backup backup) {
        return ThirdPartyBackupManifest.fromJson(backup.getDetail(ThirdPartyBackupManifest.DETAIL_KEY));
    }
}
