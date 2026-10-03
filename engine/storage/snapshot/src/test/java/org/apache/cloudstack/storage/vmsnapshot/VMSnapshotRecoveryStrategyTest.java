/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.cloudstack.storage.vmsnapshot;

import java.util.Date;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.Answer;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.Storage;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.snapshot.VMSnapshot;
import com.cloud.vm.snapshot.VMSnapshotVO;
import com.cloud.vm.snapshot.VMSnapshotDetailsVO;
import com.cloud.vm.snapshot.dao.VMSnapshotDao;
import com.cloud.vm.snapshot.dao.VMSnapshotDetailsDao;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.springframework.test.util.ReflectionTestUtils;

public class VMSnapshotRecoveryStrategyTest {
    private DefaultVMSnapshotStrategy strategy;
    private VMSnapshotVO target;
    private VMSnapshotDetailsDao details;
    @Before public void setup() {
        strategy = new DefaultVMSnapshotStrategy(); target = mock(VMSnapshotVO.class); details = mock(VMSnapshotDetailsDao.class);
        strategy.vmSnapshotHelper = mock(VMSnapshotHelper.class); strategy.vmSnapshotDao = mock(VMSnapshotDao.class);
        strategy.volumeDao = mock(VolumeDao.class); strategy.userVmDao = mock(UserVmDao.class);
        strategy.primaryDataStoreDao = mock(PrimaryDataStoreDao.class); strategy.agentMgr = mock(AgentManager.class);
        ReflectionTestUtils.setField(strategy, "vmSnapshotDetailsDao", details);
        when(target.getId()).thenReturn(1L); when(target.getVmId()).thenReturn(2L); when(target.getCreated()).thenReturn(new Date());
        when(target.getUuid()).thenReturn("error-uuid"); when(target.getName()).thenReturn("failed"); when(target.getType()).thenReturn(VMSnapshot.Type.DiskAndMemory);
        UserVmVO vm = mock(UserVmVO.class); when(vm.getHypervisorType()).thenReturn(Hypervisor.HypervisorType.KVM);
        when(vm.getUuid()).thenReturn("vm-uuid"); when(vm.getInstanceName()).thenReturn("vm"); when(strategy.userVmDao.findById(2L)).thenReturn(vm);
        VMSnapshotVO ready = mock(VMSnapshotVO.class); when(ready.getName()).thenReturn("ready"); when(ready.getType()).thenReturn(VMSnapshot.Type.DiskAndMemory); when(ready.getCurrent()).thenReturn(true);
        when(strategy.vmSnapshotDao.listByInstanceId(2L, VMSnapshot.State.Ready)).thenReturn(List.of(ready));
        VolumeObjectTO to = mock(VolumeObjectTO.class); when(to.getId()).thenReturn(3L);
        when(strategy.vmSnapshotHelper.getVolumeTOList(2L)).thenReturn(List.of(to));
        when(strategy.vmSnapshotHelper.pickRunningHost(2L)).thenReturn(5L);
        VolumeVO volume = mock(VolumeVO.class); when(volume.getUuid()).thenReturn("volume"); when(volume.getPoolId()).thenReturn(4L);
        when(volume.getSize()).thenReturn(1024L); when(volume.getPath()).thenReturn("path"); when(volume.getFormat()).thenReturn(Storage.ImageFormat.QCOW2);
        when(strategy.volumeDao.findById(3L)).thenReturn(volume);
        StoragePoolVO pool = mock(StoragePoolVO.class); when(pool.getPoolType()).thenReturn(Storage.StoragePoolType.SharedMountPoint); when(strategy.primaryDataStoreDao.findById(4L)).thenReturn(pool);
    }
    @Test public void providerFailureDoesNotRemoveMetadataOrChangeLogicalVolumeAccounting() throws Exception {
        when(strategy.agentMgr.send(anyLong(), any(Command.class))).thenReturn(new Answer(null, false, "Native VM job is active or unknown"));
        try { strategy.deleteVMSnapshot(target, true); org.junit.Assert.fail("provider failure was hidden"); }
        catch (CloudRuntimeException e) { assertTrue(e.getMessage().contains("active or unknown")); }
        verify(strategy.vmSnapshotDao, never()).remove(anyLong()); verify(strategy.volumeDao, never()).persist(any(VolumeVO.class));
        verify(strategy.vmSnapshotHelper).vmSnapshotStateTransitTo(target, VMSnapshot.Event.OperationFailed);
        verify(details).addDetail(eq(1L), eq("force.delete.target"), eq("error-uuid"), eq(false));
    }
    @Test public void lostAgentAnswerKeepsRecoveryEvidenceAndTarget() throws Exception {
        when(strategy.agentMgr.send(anyLong(), any(Command.class))).thenReturn(null);
        try { strategy.deleteVMSnapshot(target, true); org.junit.Assert.fail("lost response was treated as deletion"); }
        catch (CloudRuntimeException e) { assertTrue(e.getMessage().contains("unknown")); }
        verify(strategy.vmSnapshotDao, never()).remove(anyLong());
    }
    @Test public void changedOriginalVolumeManifestPreventsAgentDispatch() throws Exception {
        VMSnapshotDetailsVO original = mock(VMSnapshotDetailsVO.class); when(original.getValue()).thenReturn("old-volume-generation");
        when(details.findDetail(1L, "snapshot.creation.volumes")).thenReturn(original);
        try { strategy.deleteVMSnapshot(target, true); org.junit.Assert.fail("changed creation target admitted"); }
        catch (CloudRuntimeException e) { assertTrue(e.getMessage().contains("inventory changed")); }
        verify(strategy.agentMgr, never()).send(anyLong(), any(Command.class));
    }
    @Test public void changedRetryFingerprintPreventsDoubleCleanup() throws Exception {
        VMSnapshotDetailsVO previous = mock(VMSnapshotDetailsVO.class); when(previous.getValue()).thenReturn("another-recovery-inventory");
        when(details.findDetail(1L, "force.delete.inventory")).thenReturn(previous);
        try { strategy.deleteVMSnapshot(target, true); org.junit.Assert.fail("retargeted recovery admitted"); }
        catch (CloudRuntimeException e) { assertTrue(e.getMessage().contains("inventory changed")); }
        verify(strategy.agentMgr, never()).send(anyLong(), any(Command.class));
    }
    @Test public void multipleVolumeInventoryFitsDetailColumnAndDetectsChanges() {
        String inventory = new com.google.gson.Gson().toJson(List.of("root:" + "x".repeat(180), "data:" + "y".repeat(180)));
        String digest = DefaultVMSnapshotStrategy.inventoryDigest(inventory);
        assertEquals(71, digest.length());
        assertTrue(DefaultVMSnapshotStrategy.matchesOriginalInventory(digest, inventory));
        assertFalse(DefaultVMSnapshotStrategy.matchesOriginalInventory(digest, inventory + "changed"));
    }
    @Test public void truncatedLegacyRetryRequiresIndependentlyVerifiedMissingVolumeSuffix() {
        String volumes = new com.google.gson.Gson().toJson(List.of("root:" + "x".repeat(220)));
        String inventory = "{\"artifacts\":{},\"current\":\"ready\",\"snapshot\":\"target\",\"volumes\":" + volumes + "}";
        String truncated = inventory.substring(0, 255);
        assertTrue(DefaultVMSnapshotStrategy.matchesRecoveryInventory(truncated, inventory, volumes, volumes));
        assertFalse(DefaultVMSnapshotStrategy.matchesRecoveryInventory(truncated, inventory, null, volumes));
        assertFalse(DefaultVMSnapshotStrategy.matchesRecoveryInventory(truncated, inventory, volumes + "changed", volumes));
        assertFalse(DefaultVMSnapshotStrategy.matchesRecoveryInventory(truncated, inventory.replace("ready", "other"), volumes, volumes));
    }
}
