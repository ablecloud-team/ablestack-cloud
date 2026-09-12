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
import com.cloud.agent.api.CleanupConvertedInstanceDisksCommand;
import com.cloud.agent.api.to.DataStoreTO;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.hypervisor.kvm.storage.KVMPhysicalDisk;
import com.cloud.hypervisor.kvm.storage.KVMStoragePool;
import com.cloud.hypervisor.kvm.storage.KVMStoragePoolManager;
import com.cloud.resource.ResourceWrapper;

import java.io.File;
import java.util.List;
import java.util.stream.Collectors;
import com.cloud.utils.exception.CloudRuntimeException;

@ResourceWrapper(handles = CleanupConvertedInstanceDisksCommand.class)
public class LibvirtCleanupConvertedInstanceDisksCommandWrapper extends LibvirtBaseConvertCommandWrapper<CleanupConvertedInstanceDisksCommand, Answer, LibvirtComputingResource> {

    @Override
    public Answer execute(CleanupConvertedInstanceDisksCommand command, LibvirtComputingResource serverResource) {
        DataStoreTO vmVolumesStore = command.getVmVolumesStore();
        String vmVolumesPrefix = command.getVmVolumesPrefix();

        final KVMStoragePoolManager storagePoolMgr = serverResource.getStoragePoolMgr();
        try {
            validateConversionPrefix(vmVolumesPrefix);
            KVMStoragePool conversionPool = getTemporaryStoragePool(vmVolumesStore, storagePoolMgr);
            if (conversionPool == null) {
                throw new CloudRuntimeException("Conversion storage pool is unavailable");
            }
            conversionPool.refresh();
            // XML may reference parent or unrelated images. Only this conversion's output is owned here.
            List<KVMPhysicalDisk> temporaryDisks = conversionPool.listPhysicalDisks().stream()
                    .filter(disk -> isConversionDisk(disk.getName(), vmVolumesPrefix))
                    .collect(Collectors.toList());
            boolean xmlExists = new File(conversionPool.getLocalPath(), vmVolumesPrefix + ".xml").exists();
            cleanupDisksAndDomainFromTemporaryLocation(temporaryDisks, conversionPool, vmVolumesPrefix, xmlExists);
        } catch (Exception e) {
            String error = String.format("Error cleaning up converted disks with prefix %s: %s", vmVolumesPrefix, e.getMessage());
            logger.error(error, e);
            return new Answer(command, false, error);
        }

        return new Answer(command);
    }
}
