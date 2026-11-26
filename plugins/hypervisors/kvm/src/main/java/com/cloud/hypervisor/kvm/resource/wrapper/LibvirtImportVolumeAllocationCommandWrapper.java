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

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.ImportVolumeAllocationCommand;
import com.cloud.agent.api.ImportVolumeAllocationAnswer;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.hypervisor.kvm.storage.Gfs2VolumeImportAllocation;
import com.cloud.hypervisor.kvm.storage.KVMStoragePool;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;
import com.cloud.storage.Storage;

@ResourceWrapper(handles = ImportVolumeAllocationCommand.class)
public final class LibvirtImportVolumeAllocationCommandWrapper extends CommandWrapper<ImportVolumeAllocationCommand, Answer, LibvirtComputingResource> {
    @Override public Answer execute(ImportVolumeAllocationCommand command, LibvirtComputingResource resource) {
        try {
            if (command.getPool() == null || command.getPool().getType() != Storage.StoragePoolType.SharedMountPoint) {
                return new Answer(command, false, "ALLOCATION_IMPORT_UNSUPPORTED: qualified GFS2 file import only");
            }
            KVMStoragePool pool = resource.getStoragePoolMgr().getStoragePool(command.getPool().getType(), command.getPool().getUuid(), true, true);
            Gfs2VolumeImportAllocation.Result result = new Gfs2VolumeImportAllocation().execute(command, pool);
            return new ImportVolumeAllocationAnswer(command, result.volume, result.allocation);
        } catch (Exception failure) {
            return new Answer(command, false, "ALLOCATION_IMPORT_UNVERIFIED: " + failure.getMessage());
        }
    }
}
