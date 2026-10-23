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
package org.apache.cloudstack.backup;

import com.cloud.agent.api.Command;
import com.cloud.storage.Storage;
import com.cloud.storage.Volume;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Persist destination allocation and primary capacity policies with each detached restore command. */
public abstract class AblestackRestoreBackupCommand extends Command {
    private Integer primaryStorageCapacityBufferPercent;
    private Map<String, BackupVolumeAllocation> restoreVolumeAllocation;
    public Map<String, BackupVolumeAllocation> getRestoreVolumeAllocation() { return restoreVolumeAllocation; }
    public void setRestoreVolumeAllocation(Map<String, BackupVolumeAllocation> value) { restoreVolumeAllocation = value; }

    private Map<String, Storage.ProvisioningType> restoreVolumeProvisioningTypes;

    /** Destination policy, serialized with detached commands rather than inferred from backup data. */
    public Map<String, Storage.ProvisioningType> getRestoreVolumeProvisioningTypes() {
        return restoreVolumeProvisioningTypes;
    }

    public void setRestoreVolumeProvisioningTypes(List<? extends Volume> volumes, List<String> paths) {
        if (volumes == null || paths == null || volumes.size() != paths.size()) {
            throw new IllegalArgumentException("Restore destination volumes and paths must match");
        }
        Map<String, Storage.ProvisioningType> policies = new LinkedHashMap<>();
        for (int index = 0; index < paths.size(); index++) {
            String path = paths.get(index);
            if (path == null || path.isBlank() || policies.containsKey(path)) {
                throw new IllegalArgumentException("Invalid or duplicate restore destination path");
            }
            // RBD has its own allocation path. A missing file policy is rejected by the file converter.
            policies.put(path, volumes.get(index).getProvisioningType());
        }
        restoreVolumeProvisioningTypes = policies;
    }

    public int getPrimaryStorageCapacityBufferPercent() {
        return BackupRestoreCapacity.bufferPercent(primaryStorageCapacityBufferPercent);
    }

    public void setPrimaryStorageCapacityBufferPercent(Integer percent) {
        BackupRestoreCapacity.bufferPercent(percent);
        primaryStorageCapacityBufferPercent = percent;
    }
}
