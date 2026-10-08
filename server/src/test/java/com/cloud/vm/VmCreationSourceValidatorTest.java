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
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.apache.cloudstack.api.response.VmCreationSourceResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.subsystem.api.storage.SnapshotInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.SnapshotDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.ObjectInDataStoreStateMachine;
import org.apache.cloudstack.snapshot.SnapshotHelper;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.storage.DataStoreRole;
import com.cloud.storage.GuestOSVO;
import com.cloud.storage.Snapshot;
import com.cloud.storage.SnapshotVO;
import com.cloud.storage.ScopeType;
import com.cloud.storage.Storage;
import com.cloud.storage.StoragePoolStatus;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.GuestOSDao;
import com.cloud.storage.dao.SnapshotDao;
import com.cloud.storage.dao.SnapshotDetailsDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.storage.dao.VolumeDetailsDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.User;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;

public class VmCreationSourceValidatorTest {
    VmCreationSourceValidator validator;
    VolumeVO volume;
    VMTemplateVO template;
    StoragePoolVO pool;
    SnapshotVO snapshot;
    SnapshotInfo info;
    @Before public void setup() {
        validator = new VmCreationSourceValidator();
        validator.volumes = mock(VolumeDao.class); validator.snapshots = mock(SnapshotDao.class);
        validator.volumeDetails = mock(VolumeDetailsDao.class); validator.snapshotDetails = mock(SnapshotDetailsDao.class);
        validator.templates = mock(VMTemplateDao.class); validator.pools = mock(PrimaryDataStoreDao.class);
        validator.zones = mock(DataCenterDao.class); validator.guestOs = mock(GuestOSDao.class);
        validator.vms = mock(VMInstanceDao.class); validator.vmDetails = mock(VMInstanceDetailsDao.class);
        validator.hosts = mock(HostDao.class); validator.clusters = mock(com.cloud.dc.dao.ClusterDao.class);
        validator.agents = mock(com.cloud.agent.AgentManager.class); validator.accounts = mock(AccountManager.class);
        validator.snapshotFactory = mock(SnapshotDataFactory.class); validator.snapshotHelper = mock(SnapshotHelper.class);
        Account caller = mock(Account.class); when(caller.getId()).thenReturn(2L); CallContext.register(mock(User.class), caller);
        when(validator.accounts.isRootAdmin(2L)).thenReturn(true);
        volume = mock(VolumeVO.class); when(volume.getInstanceId()).thenReturn(null); when(volume.getId()).thenReturn(101L); when(volume.getUuid()).thenReturn("root-uuid");
        when(volume.getName()).thenReturn("ROOT-test"); when(volume.getDataCenterId()).thenReturn(1L);
        when(volume.getVolumeType()).thenReturn(Volume.Type.ROOT); when(volume.getState()).thenReturn(Volume.State.Ready);
        when(volume.getPath()).thenReturn("root-data"); when(volume.getTemplateId()).thenReturn(201L);
        when(volume.getSize()).thenReturn(64L << 30); when(volume.getPoolId()).thenReturn(301L);
        when(validator.volumeDetails.listDetailsKeyPairs(101L)).thenReturn(Collections.emptyMap());
        template = mock(VMTemplateVO.class); when(template.getId()).thenReturn(201L); when(template.getUuid()).thenReturn("template-uuid");
        when(template.getHypervisorType()).thenReturn(HypervisorType.KVM); when(template.getGuestOSId()).thenReturn(401L);
        when(template.getDetails()).thenReturn(new HashMap<>()); when(validator.templates.findByIdIncludingRemoved(201L)).thenReturn(template);
        GuestOSVO os = mock(GuestOSVO.class); when(os.getUuid()).thenReturn("os-uuid"); when(os.getDisplayName()).thenReturn("Rocky Linux");
        when(validator.guestOs.findById(401L)).thenReturn(os);
        DataCenterVO zone = mock(DataCenterVO.class); when(zone.getUuid()).thenReturn("zone-uuid"); when(validator.zones.findById(1L)).thenReturn(zone);
        pool = mock(StoragePoolVO.class); when(pool.getId()).thenReturn(301L); when(pool.getUuid()).thenReturn("pool-uuid");
        when(pool.getScope()).thenReturn(ScopeType.CLUSTER); when(pool.getPoolType()).thenReturn(Storage.StoragePoolType.SharedMountPoint);
        when(pool.getStatus()).thenReturn(StoragePoolStatus.Up); when(pool.getClusterId()).thenReturn(501L);
        when(validator.pools.findByIdIncludingRemoved(301L)).thenReturn(pool); when(validator.pools.findById(301L)).thenReturn(pool);
        when(validator.volumes.findByUuid("root-uuid")).thenReturn(volume);
        snapshot = mock(SnapshotVO.class); when(snapshot.getId()).thenReturn(601L); when(snapshot.getUuid()).thenReturn("snapshot-uuid");
        when(snapshot.getName()).thenReturn("snapshot-test"); when(snapshot.getState()).thenReturn(Snapshot.State.BackedUp);
        when(snapshot.getHypervisorType()).thenReturn(HypervisorType.KVM); when(snapshot.getDataCenterId()).thenReturn(1L);
        when(snapshot.getSize()).thenReturn(64L << 30); when(validator.snapshotDetails.listDetailsKeyPairs(601L)).thenReturn(Collections.emptyMap());
        info = mock(SnapshotInfo.class); when(info.getPath()).thenReturn("snapshot-data");
        when(info.getDataStore()).thenReturn(mock(org.apache.cloudstack.engine.subsystem.api.storage.DataStore.class));
        when(info.getStatus()).thenReturn(ObjectInDataStoreStateMachine.State.Ready);
        when(validator.snapshotHelper.getDataStoreRole(snapshot)).thenReturn(DataStoreRole.Image);
        when(validator.snapshotFactory.getSnapshotWithRoleAndZone(601L, DataStoreRole.Image, 1L)).thenReturn(info);
    }
    @After public void teardown() { CallContext.unregister(); }
    @Test public void detachedGfs2RootIsEligibleWithoutScopeMutation() {
        VmCreationSourceResponse source = validator.inspect(volume, null, 1L, null);
        assertTrue(source.reasoncodes.toString(), source.allowed); assertEquals("adopt-existing", source.sourceusage);
        assertEquals("CLUSTER", source.storage.get("scope")); verify(pool, never()).setScope(any());
    }
    @Test public void clusterRbdIsSupportedAndHostScopeIsBlocked() {
        when(pool.getPoolType()).thenReturn(Storage.StoragePoolType.RBD); assertTrue(validator.inspect(volume, null, 1L, null).allowed);
        when(pool.getScope()).thenReturn(ScopeType.HOST); assertTrue(validator.inspect(volume, null, 1L, null).reasoncodes.contains("STORAGE_SCOPE_UNSUPPORTED"));
    }
    @Test public void arbitraryDataDiskCannotBecomeBootable() {
        when(volume.getVolumeType()).thenReturn(Volume.Type.DATADISK);
        assertTrue(validator.inspect(volume, null, 1L, null).reasoncodes.contains("ROOT_PROVENANCE_UNKNOWN"));
    }
    @Test public void attachedAndNonReadySourcesAreBlocked() {
        when(volume.getInstanceId()).thenReturn(77L); when(volume.getState()).thenReturn(Volume.State.Creating);
        VmCreationSourceResponse source = validator.inspect(volume, null, 1L, null);
        assertTrue(source.reasoncodes.contains("SOURCE_ATTACHED")); assertTrue(source.reasoncodes.contains("SOURCE_NOT_READY"));
    }
    @Test public void missingTemplateHasStableReasonInsteadOfNullDereference() {
        when(validator.templates.findByIdIncludingRemoved(201L)).thenReturn(null);
        assertTrue(validator.inspect(volume, snapshot, 1L, null).reasoncodes.contains("SOURCE_TEMPLATE_MISSING"));
    }
    @Test public void creatingSnapshotAndMissingStoredObjectAreNotRestorable() {
        when(snapshot.getState()).thenReturn(Snapshot.State.Creating);
        assertTrue(validator.inspect(volume, snapshot, 1L, null).reasoncodes.contains("SNAPSHOT_NOT_RESTORABLE"));
        when(snapshot.getState()).thenReturn(Snapshot.State.BackedUp); when(info.getPath()).thenReturn(null);
        assertTrue(validator.inspect(volume, snapshot, 1L, null).reasoncodes.contains("SNAPSHOT_NOT_RESTORABLE"));
    }
    @Test public void missingOriginalVolumeRowIsBlockedWithoutLosingCapturedBootAndLogicalSize() {
        Map<String, String> saved = new HashMap<>();
        saved.put("volumetype", "ROOT"); saved.put("templateid", "201"); saved.put("ostypeid", "os-uuid"); saved.put("boottype", "UEFI");
        saved.put("bootmode", "LEGACY"); saved.put("sizebytes", String.valueOf(64L << 30)); saved.put("provenance", "captured");
        Map<String, String> details = new HashMap<>(); saved.forEach((key, value) -> details.put(VmCreationSourceService.PREFIX + key, value));
        when(validator.snapshotDetails.listDetailsKeyPairs(601L)).thenReturn(details);
        VmCreationSourceResponse source = validator.inspect(null, snapshot, 1L, null);
        assertTrue(!source.allowed); assertTrue(source.reasoncodes.contains("SOURCE_VOLUME_METADATA_MISSING")); assertEquals(Long.valueOf(64L << 30), source.sizebytes);
        assertEquals("UEFI", source.bootprofile.get("boottype"));
    }
    @Test public void legacySnapshotDoesNotReadMutableCurrentVmBootMetadata() {
        when(volume.getInstanceId()).thenReturn(77L);
        VmCreationSourceResponse source = validator.inspect(volume, snapshot, 1L, null);
        assertTrue(source.allowed); assertEquals("legacy-template", source.bootprofile.get("provenance"));
        verifyNoInteractions(validator.vmDetails);
    }
    @Test public void originalClusterAndRevisionAreRechecked() {
        VmCreationSourceResponse source = validator.inspect(volume, null, 1L, null);
        validator.deploymentConstraints(source, null, 502L, null, null, "old");
        assertTrue(source.reasoncodes.contains("SOURCE_CLUSTER_MISMATCH")); assertTrue(source.reasoncodes.contains("SOURCE_CHANGED"));
    }
    @Test public void secureBootAndTpmDependentSourcesFailClosed() {
        Map<String, String> boot = new HashMap<>(); boot.put("UEFI", "SECURE"); boot.put(VmDetailConstants.TPM_VERSION, "2.0");
        when(template.getDetails()).thenReturn(boot);
        VmCreationSourceResponse source = validator.inspect(volume, null, 1L, null);
        assertTrue(source.reasoncodes.contains("TPM_STATE_UNSUPPORTED")); assertTrue(source.reasoncodes.contains("SECURE_BOOT_STATE_UNSUPPORTED"));
    }
    @Test public void snapshotCaptureRecordsRootAndBootAtCaptureTime() {
        validator.captureSnapshot(snapshot, volume);
        verify(validator.snapshotDetails).addDetail(601L, VmCreationSourceService.PREFIX + "volumetype", "ROOT", false);
        verify(validator.snapshotDetails).addDetail(601L, VmCreationSourceService.PREFIX + "sizebytes", String.valueOf(64L << 30), false);
        verify(validator.snapshotDetails).addDetail(601L, VmCreationSourceService.PREFIX + "boottype", "BIOS", false);
    }
    @Test public void activeHostConsumerBlocksAdoption() {
        com.cloud.host.HostVO host = mock(com.cloud.host.HostVO.class);
        when(host.getId()).thenReturn(7L); when(host.getClusterId()).thenReturn(501L);
        when(host.getStatus()).thenReturn(com.cloud.host.Status.Up);
        when(validator.hosts.listAllRoutingHostsByZoneAndHypervisorType(1L, HypervisorType.KVM)).thenReturn(java.util.List.of(host));
        when(validator.agents.easySend(org.mockito.ArgumentMatchers.eq(7L), any(com.cloud.agent.api.CheckVolumeUseCommand.class)))
                .thenReturn(new com.cloud.agent.api.Answer(null, false, "SOURCE_IN_USE"));
        VmCreationSourceResponse source = validator.inspect(volume, null, 1L, null); validator.validateRuntime(source);
        assertTrue(source.reasoncodes.contains("SOURCE_IN_USE"));
    }
    @Test public void missingAgentResponseFailsClosedWithoutVolumeMutation() {
        com.cloud.host.HostVO host = mock(com.cloud.host.HostVO.class);
        when(host.getId()).thenReturn(7L); when(host.getClusterId()).thenReturn(501L);
        when(host.getStatus()).thenReturn(com.cloud.host.Status.Up);
        when(validator.hosts.listAllRoutingHostsByZoneAndHypervisorType(1L, HypervisorType.KVM)).thenReturn(java.util.List.of(host));
        VmCreationSourceResponse source = validator.inspect(volume, null, 1L, null); validator.validateRuntime(source);
        assertTrue(source.reasoncodes.contains("SOURCE_RUNTIME_UNKNOWN")); verify(volume, never()).setInstanceId(any());
    }
    @Test public void standaloneRootRestoreKeepsRootProvenance() {
        when(snapshot.getVolumeId()).thenReturn(101L); when(validator.volumes.findByIdIncludingRemoved(101L)).thenReturn(volume);
        Volume restored = mock(Volume.class); when(restored.getId()).thenReturn(102L);
        validator.captureRestoredVolume(snapshot, restored);
        verify(validator.volumeDetails).addDetail(102L, VmCreationSourceService.PREFIX + "volumetype", "ROOT", false);
        verify(validator.volumeDetails).addDetail(102L, VmCreationSourceService.PREFIX + "templateid", "201", false);
    }

}
