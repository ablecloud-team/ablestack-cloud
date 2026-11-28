//
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
//

package com.cloud.hypervisor.kvm.resource.wrapper;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.CheckVolumeAnswer;
import com.cloud.agent.api.CheckVolumeCommand;
import com.cloud.agent.api.GetVolumesOnStorageAnswer;
import com.cloud.agent.api.GetVolumesOnStorageCommand;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;
import com.cloud.storage.Storage.StoragePoolType;
import org.apache.cloudstack.storage.volume.VolumeOnStorageTO;
import org.apache.commons.lang3.StringUtils;

@ResourceWrapper(handles = CheckVolumeCommand.class)
public final class LibvirtCheckVolumeCommandWrapper extends CommandWrapper<CheckVolumeCommand, Answer, LibvirtComputingResource> {
    @Override
    public Answer execute(CheckVolumeCommand command, LibvirtComputingResource resource) {
        StoragePoolType poolType = command.getStorageFilerTO().getType();
        if (StringUtils.isBlank(command.getSrcFile()) || StoragePoolType.RBD.equals(poolType)
                || !LibvirtGetVolumesOnStorageCommandWrapper.STORAGE_POOL_TYPES_SUPPORTED_BY_QEMU_IMG.contains(poolType)) {
            return new Answer(command, false, "A QCOW2 or RAW file in the selected pool is required");
        }

        Answer answer = new LibvirtGetVolumesOnStorageCommandWrapper().execute(
                new GetVolumesOnStorageCommand(command.getStorageFilerTO(), command.getSrcFile(), null), resource);
        if (!(answer instanceof GetVolumesOnStorageAnswer) || !answer.getResult()) {
            return new Answer(command, false, answer.getDetails());
        }

        GetVolumesOnStorageAnswer inspection = (GetVolumesOnStorageAnswer) answer;
        if (inspection.getVolumes().size() != 1) {
            return new Answer(command, false, "Source file not found in the selected pool");
        }
        VolumeOnStorageTO volume = inspection.getVolumes().get(0);
        return new CheckVolumeAnswer(command, volume.getPath(), volume.getVirtualSize(), volume.getDetails());
    }
}
