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
package com.cloud.vm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.apache.cloudstack.api.command.user.vm.CloneVMCmd;
import org.apache.cloudstack.storage.command.PrepareSharedMountPointCloneCommand.VolumeCloneSpec;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.storage.dao.VolumeDetailsDao;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.dao.UserVmDetailsDao;
import com.cloud.vm.dao.VMInstanceDao;

public class SharedMountPointCloneTest {
    private UserVmManagerImpl manager;
    private UserVmDetailsDao details;
    private VMInstanceDao instances;
    private VolumeDetailsDao volumeDetails;

    @Before
    public void setUp() {
        manager = new UserVmManagerImpl();
        details = mock(UserVmDetailsDao.class);
        instances = mock(VMInstanceDao.class);
        volumeDetails = mock(VolumeDetailsDao.class);
        ReflectionTestUtils.setField(manager, "userVmDetailsDao", details);
        ReflectionTestUtils.setField(manager, "_vmInstanceDao", instances);
        ReflectionTestUtils.setField(manager, "volumeDetailsDao", volumeDetails);
    }

    @Test
    public void cloneDoesNotInheritSourceOperationMetadata() {
        Map<String, String> source = new HashMap<>();
        source.put("clone.fast.source.phase", "ready");
        source.put("clone.fast.operation.id", "source-operation");
        source.put("cpuNumber", "4");
        source.put("UEFI", "secure");
        when(details.listDetailsKeyPairs(1L)).thenReturn(source);

        Map<String, String> cloned = manager.getCloneVmCustomParameters(1L);

        assertEquals(2, cloned.size());
        assertEquals("4", cloned.get("cpuNumber"));
        assertEquals("secure", cloned.get("UEFI"));
        assertEquals(4, source.size());
    }

    @Test
    public void validatesEveryGeneratedCloneName() {
        CloneVMCmd command = command("copy", 2);
        manager.validateCloneNames(command, 3L);
        verify(instances).findVMByHostNameInZone("copy-1", 3L);
        verify(instances).findVMByHostNameInZone("copy-2", 3L);
    }

    @Test
    public void rejectsDuplicateFinalCloneName() {
        VMInstanceVO existing = mock(VMInstanceVO.class);
        when(existing.getState()).thenReturn(VirtualMachine.State.Stopped);
        when(instances.findVMByHostNameInZone("copy-2", 3L)).thenReturn(existing);
        assertThrows(InvalidParameterValueException.class, () -> manager.validateCloneNames(command("copy", 2), 3L));
    }

    @Test
    public void rejectsCloneNameThatExceedsLimitAfterSuffix() {
        assertThrows(InvalidParameterValueException.class, () -> manager.validateCloneNames(command("a".repeat(62), 10), 3L));
    }

    @Test
    public void blocksPowerForUnconfirmedClonePhase() {
        when(details.findDetail(1L, VmDetailConstants.FAST_CLONE_CLONE_PHASE))
                .thenReturn(new UserVmDetailVO(1L, VmDetailConstants.FAST_CLONE_CLONE_PHASE, "failed", false));
        assertFalse(manager.isSharedMountPointClonePowerAllowed(1L));
    }

    @Test
    public void clearsPhaseAndBandwidthUsingDiploDetailsDao() {
        manager.clearFastCloneVmStatus(1L);
        verify(details).removeDetail(1L, VmDetailConstants.FAST_CLONE_SOURCE_PHASE);
        verify(details).removeDetail(1L, VmDetailConstants.FAST_CLONE_CLONE_PHASE);
        verify(details).removeDetail(1L, VmDetailConstants.FAST_CLONE_BANDWIDTH);
        verify(details).removeDetail(1L, VmDetailConstants.FAST_CLONE_BANDWIDTH_STATUS);
    }

    @Test
    public void acceptsSameMultiDiskRecoveryPlanInDifferentOrder() {
        VolumeCloneSpec root = spec(1L, 11L, "root", 1024L);
        VolumeCloneSpec data = spec(2L, 12L, "data", 2048L);
        manager.verifyFastCloneRecoveryPlanUnchanged(work(root, data), work(data, root));
    }

    @Test
    public void rejectsRecoveryWhenAnyDataDiskChanges() {
        VolumeCloneSpec root = spec(1L, 11L, "root", 1024L);
        VolumeCloneSpec data = spec(2L, 12L, "data", 2048L);
        VolumeCloneSpec changed = spec(2L, 12L, "other-data", 2048L);
        assertThrows(CloudRuntimeException.class, () -> manager.verifyFastCloneRecoveryPlanUnchanged(work(root, data), work(root, changed)));
    }

    @Test
    public void rejectsRecoveryWhenDiskIsMissing() {
        VolumeCloneSpec root = spec(1L, 11L, "root", 1024L);
        assertThrows(CloudRuntimeException.class, () -> manager.verifyFastCloneRecoveryPlanUnchanged(
                work(root, spec(2L, 12L, "data", 2048L)), work(root)));
    }

    private CloneVMCmd command(String name, int count) {
        CloneVMCmd command = mock(CloneVMCmd.class);
        when(command.getName()).thenReturn(name);
        when(command.getCount()).thenReturn(count);
        return command;
    }

    private VolumeCloneSpec spec(long sourceId, long cloneId, String sourcePath, long size) {
        return new VolumeCloneSpec(sourceId, sourcePath, "clone/overlay/" + sourcePath, cloneId, "copy-" + cloneId, size);
    }

    private VmWorkSharedMountPointClone work(VolumeCloneSpec... specs) {
        when(volumeDetails.findDetails("clone.fast.operation.id", "operation", false)).thenReturn(Collections.emptyList());
        return new VmWorkSharedMountPointClone(1L, 1L, 1L, UserVmManagerImpl.VM_WORK_JOB_HANDLER,
                VmWorkSharedMountPointClone.Operation.Prepare, "operation", 1L, Arrays.asList(specs));
    }
}
