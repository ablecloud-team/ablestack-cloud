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
package com.cloud.template;

import java.util.EnumSet;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.storage.Storage.StoragePoolType;
import com.cloud.storage.StoragePoolStatus;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;

final class TemplatePreparationValidation {
    private static final EnumSet<StoragePoolType> CACHE_TYPES = EnumSet.of(StoragePoolType.NetworkFilesystem,
            StoragePoolType.SharedMountPoint, StoragePoolType.Filesystem, StoragePoolType.EXT,
            StoragePoolType.VMFS, StoragePoolType.PreSetup);
    private TemplatePreparationValidation() { }
    static void validate(StoragePoolVO pool, long zoneId, HypervisorType templateHypervisor) {
        if (pool == null) throw new InvalidParameterValueException("Primary storage pool does not exist");
        if (pool.getDataCenterId() != zoneId) throw new InvalidParameterValueException("Primary storage pool must belong to the requested Zone");
        if (pool.getStatus() != StoragePoolStatus.Up) throw new InvalidParameterValueException("Primary storage pool must be Up");
        if (pool.isManaged() || !CACHE_TYPES.contains(pool.getPoolType())) throw new InvalidParameterValueException("Primary storage pool does not support explicit template preloading");
        if (pool.getHypervisor() != null && pool.getHypervisor() != HypervisorType.None && pool.getHypervisor() != templateHypervisor) throw new InvalidParameterValueException("Template and primary storage hypervisors must match");
    }
}
