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
package com.cloud.hypervisor.kvm.resource.wrapper;

import java.util.Map;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.GetVolumeAllocationCapabilitiesAnswer;
import com.cloud.agent.api.GetVolumeAllocationCapabilitiesCommand;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.hypervisor.kvm.storage.KVMStoragePool;
import com.cloud.hypervisor.kvm.storage.RbdVolumeAllocation;
import com.cloud.hypervisor.kvm.storage.Gfs2VolumeAllocation;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;
import com.cloud.storage.Storage;

@ResourceWrapper(handles = GetVolumeAllocationCapabilitiesCommand.class)
public final class LibvirtGetVolumeAllocationCapabilitiesCommandWrapper extends
        CommandWrapper<GetVolumeAllocationCapabilitiesCommand, Answer, LibvirtComputingResource> {
    @Override public Answer execute(GetVolumeAllocationCapabilitiesCommand command, LibvirtComputingResource resource) {
        try {
            KVMStoragePool pool = resource.getStoragePoolMgr().getStoragePoolByUuid(command.getPoolUuid());
            String backend = pool.getType() == Storage.StoragePoolType.SharedMountPoint ? "GFS2" : "RBD";
            Map<String, String> methods = "GFS2".equals(backend) ?
                    new Gfs2VolumeAllocation().capabilities(pool, command.getFormat(), command.getOperation()) :
                    new RbdVolumeAllocation().capabilities(pool, command.getFormat(), command.getOperation());
            if (command.getContractVersion() != 1 || methods.isEmpty()) {
                return new Answer(command, false, "ALLOCATION_CAPABILITY_UNAVAILABLE: qualified GFS2/RBD create/copy requires mounted backend and installed tools");
            }
            return new GetVolumeAllocationCapabilitiesAnswer(command, backend, methods);
        } catch (Exception e) {
            return new Answer(command, false, "ALLOCATION_CAPABILITY_UNAVAILABLE: " + e.getMessage());
        }
    }
}
