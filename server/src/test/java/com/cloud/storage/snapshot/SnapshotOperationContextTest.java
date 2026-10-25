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
package com.cloud.storage.snapshot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;

public class SnapshotOperationContextTest {
    private VolumeVO volume;
    private VMInstanceVO vm;

    @Before
    public void setup() {
        volume = mock(VolumeVO.class);
        vm = mock(VMInstanceVO.class);
        when(volume.getInstanceId()).thenReturn(null);
        when(volume.getUuid()).thenReturn("volume");
        when(volume.getName()).thenReturn("source");
        when(volume.getState()).thenReturn(Volume.State.Ready);
        when(vm.getUuid()).thenReturn("vm");
        when(vm.getState()).thenReturn(VirtualMachine.State.Stopped);
    }

    @Test
    public void detachedReadyVolumeCanRevertAndCreate() {
        Map<String, Object> context = SnapshotOperationContext.describe(volume, null, false, false);
        assertEquals("", context.get("createreason"));
        assertEquals("", context.get("revertreason"));
        assertEquals("volume", context.get("volumeid"));
        assertTrue((Boolean) context.get("sourceavailable"));
    }

    @Test
    public void missingSourceIsExplicitAndDoesNotFabricateVmData() {
        Map<String, Object> context = SnapshotOperationContext.describe(null, null, false, false);
        assertFalse((Boolean) context.get("sourceavailable"));
        assertEquals("SOURCE_VOLUME_UNAVAILABLE", context.get("revertreason"));
        assertEquals(null, context.get("virtualmachineid"));
    }

    @Test
    public void runningVmCanCreateButCannotRevertItsSource() {
        when(volume.getInstanceId()).thenReturn(7L);
        when(vm.getState()).thenReturn(VirtualMachine.State.Running);
        Map<String, Object> context = SnapshotOperationContext.describe(volume, vm, false, false);
        assertEquals("", context.get("createreason"));
        assertEquals("VM_MUST_BE_STOPPED", context.get("revertreason"));
    }

    @Test
    public void stoppedVmAllowsRevert() {
        when(volume.getInstanceId()).thenReturn(7L);
        assertEquals("", SnapshotOperationContext.revertReason(volume, vm, false, false));
    }

    @Test
    public void retainedVmSnapshotsBlockRevertWithoutBlockingUnrelatedCreation() {
        when(volume.getInstanceId()).thenReturn(7L);
        Map<String, Object> context = SnapshotOperationContext.describe(volume, vm, true, false);
        assertEquals("VM_SNAPSHOTS_EXIST", context.get("revertreason"));
        assertEquals("", context.get("createreason"));
    }

    @Test
    public void sourceRestoreBlocksBothOperations() {
        when(volume.getInstanceId()).thenReturn(7L);
        Map<String, Object> context = SnapshotOperationContext.describe(volume, vm, false, true);
        assertEquals("BACKUP_RESTORE_IN_PROGRESS", context.get("revertreason"));
        assertEquals("BACKUP_RESTORE_IN_PROGRESS", context.get("createreason"));
    }

    @Test
    public void missingAttachedVmIsNotAssumedDetached() {
        when(volume.getInstanceId()).thenReturn(7L);
        assertEquals("SOURCE_VM_UNAVAILABLE", SnapshotOperationContext.revertReason(volume, null, false, false));
    }

    @Test
    public void transitioningVmBlocksCreationAndRevert() {
        when(volume.getInstanceId()).thenReturn(7L);
        when(vm.getState()).thenReturn(VirtualMachine.State.Starting);
        assertEquals("VM_NOT_STABLE", SnapshotOperationContext.describe(volume, vm, false, false).get("createreason"));
        assertEquals("VM_MUST_BE_STOPPED", SnapshotOperationContext.revertReason(volume, vm, false, false));
    }

    @Test
    public void nonReadyVolumeBlocksEvenWithStoppedVm() {
        when(volume.getState()).thenReturn(Volume.State.Creating);
        assertEquals("VOLUME_NOT_READY", SnapshotOperationContext.revertReason(volume, vm, false, false));
    }
}
