// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.hypervisor.kvm.resource.wrapper;

import java.util.ArrayList;
import java.util.List;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.StartAnswer;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;
import com.cloud.storage.Storage;
import org.apache.cloudstack.backup.AblestackSharedRawDiskCommand;
import org.apache.cloudstack.storage.command.AttachAnswer;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;
import org.apache.cloudstack.storage.to.VolumeObjectTO;

@ResourceWrapper(handles = AblestackSharedRawDiskCommand.class)
public class LibvirtAblestackSharedRawDiskCommandWrapper extends CommandWrapper<AblestackSharedRawDiskCommand, Answer, LibvirtComputingResource> {
    @Override public Answer execute(AblestackSharedRawDiskCommand command, LibvirtComputingResource resource) {
        try {
            List<com.cloud.agent.api.to.DiskTO> disks = command.getStart() != null
                    ? java.util.Arrays.asList(command.getStart().getVirtualMachine().getDisks()) : List.of(command.getAttach().getDisk());
            List<String> sources = new ArrayList<>();
            for (var disk : disks) if (disk.getData() instanceof VolumeObjectTO) {
                VolumeObjectTO volume = (VolumeObjectTO) disk.getData();
                if (volume.getDataStore() instanceof PrimaryDataStoreTO
                        && ((PrimaryDataStoreTO) volume.getDataStore()).getPoolType() == Storage.StoragePoolType.SharedMountPoint)
                    sources.add(LibvirtAblestackSharedRawGuard.resolve(resource, (PrimaryDataStoreTO) volume.getDataStore(), volume.getPath()).toString());
            }
            try (var lease = LibvirtAblestackSharedRawGuard.acquire(sources, null)) {
                return LibvirtRequestWrapper.getInstance().execute(command.getStart() != null ? command.getStart() : command.getAttach(), resource);
            }
        } catch (Exception failure) {
            return command.getStart() != null ? new StartAnswer(command.getStart(), failure.getMessage()) : new AttachAnswer(failure.getMessage());
        }
    }
}
