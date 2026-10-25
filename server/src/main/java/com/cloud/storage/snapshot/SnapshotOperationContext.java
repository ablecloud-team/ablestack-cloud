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

import java.util.LinkedHashMap;
import java.util.Map;

import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;

/** Current source facts shared by the read preflight and revert validation. */
public final class SnapshotOperationContext {
    private SnapshotOperationContext() { }

    public static String revertReason(VolumeVO volume, VMInstanceVO vm, boolean vmSnapshots, boolean restoring) {
        if (volume == null) { return "SOURCE_VOLUME_UNAVAILABLE"; }
        if (volume.getState() != Volume.State.Ready) { return "VOLUME_NOT_READY"; }
        if (volume.getInstanceId() != null) {
            if (vm == null) { return "SOURCE_VM_UNAVAILABLE"; }
            if (vm.getState() != VirtualMachine.State.Stopped && vm.getState() != VirtualMachine.State.Shutdown) {
                return "VM_MUST_BE_STOPPED";
            }
            if (vmSnapshots) { return "VM_SNAPSHOTS_EXIST"; }
        }
        return restoring ? "BACKUP_RESTORE_IN_PROGRESS" : "";
    }

    public static Map<String, Object> describe(VolumeVO volume, VMInstanceVO vm, boolean vmSnapshots, boolean restoring) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", 1);
        result.put("sourceavailable", volume != null);
        result.put("volumeid", volume == null ? null : volume.getUuid());
        result.put("volumename", volume == null ? null : volume.getName());
        result.put("volumestate", volume == null ? null : volume.getState());
        result.put("virtualmachineid", vm == null ? null : vm.getUuid());
        result.put("virtualmachinename", vm == null ? null : vm.getHostName());
        result.put("virtualmachinestate", vm == null ? null : vm.getState());
        result.put("revertreason", revertReason(volume, vm, vmSnapshots, restoring));
        String create = "";
        if (volume == null) { create = "SOURCE_VOLUME_UNAVAILABLE"; }
        else if (volume.getState() != Volume.State.Ready) { create = "VOLUME_NOT_READY"; }
        else if (volume.getInstanceId() != null && vm == null) { create = "SOURCE_VM_UNAVAILABLE"; }
        else if (vm != null && !java.util.EnumSet.of(VirtualMachine.State.Running, VirtualMachine.State.Stopped,
                VirtualMachine.State.Shutdown).contains(vm.getState())) { create = "VM_NOT_STABLE"; }
        else if (restoring) { create = "BACKUP_RESTORE_IN_PROGRESS"; }
        result.put("createreason", create);
        return result;
    }
}
