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

package com.cloud.hypervisor.kvm.resource.wrapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.storage.Storage;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.Gson;
import org.apache.cloudstack.backup.AblestackBackupFrameworkUtils;
import org.apache.cloudstack.backup.ThirdPartyBackupManifest;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;

final class LibvirtAblestackVolumeBackupHelper {
    private LibvirtAblestackVolumeBackupHelper() { }

    static String[] buildCommand(LibvirtComputingResource resource, String manifestJson, String backupPath,
            List<PrimaryDataStoreTO> pools, List<String> paths, String checkpoint, String parentCheckpoint,
            Map<String, String> parentXmlChain, Boolean quiesce, int timeout, Integer bufferPercent) {
        try {
            ThirdPartyBackupManifest manifest = ThirdPartyBackupManifest.fromJson(manifestJson);
            if (!manifest.getBackupUuid().matches("[A-Za-z0-9-]+") || pools.size() != paths.size()
                    || manifest.getVolumes().size() != paths.size()) {
                throw new CloudRuntimeException("Invalid source volume staging plan");
            }
            Path script = Path.of(resource.getAbleCvtBackupPath()).getParent().resolve("thirdparty_volume_backup.py");
            if (!Files.isRegularFile(script)) {
                throw new CloudRuntimeException("Volume backup script is not installed on the host");
            }
            boolean rbd = pools.stream().allMatch(p -> p.getPoolType() == Storage.StoragePoolType.RBD);
            Map<String, Object> plan = new LinkedHashMap<>();
            plan.put("manifest", manifest);
            plan.put("backupPath", backupPath);
            plan.put("vmName", manifest.getVmName());
            plan.put("sourceHost", manifest.getCurrentArtifacts().get(0).sourceHost);
            plan.put("stageRoot", Path.of(backupPath).getParent().getParent().getParent().toString());
            plan.put("diskPaths", LibvirtAblestackTakeBackupCommandHelper.resolveDiskPaths(resource, pools, paths));
            plan.put("rbd", rbd);
            plan.put("checkpointName", checkpoint);
            plan.put("parentCheckpointName", parentCheckpoint);
            plan.put("parentCheckpointXmlChain", parentXmlChain);
            plan.put("quiesce", Boolean.TRUE.equals(quiesce));
            plan.put("timeout", timeout);
            plan.put("bufferPercent", bufferPercent == null ? 0 : bufferPercent);
            plan.put("providerStep", manifest.getProvider().substring("ablestack-".length()).toUpperCase(java.util.Locale.ROOT) + "_TRANSFER");
            Path directory = Path.of(AblestackBackupFrameworkUtils.ASYNC_BACKUP_JOB_ROOT, manifest.getBackupUuid());
            Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            Path file = directory.resolve("volume-plan.json");
            Files.writeString(file, new Gson().toJson(plan));
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            return new String[] {"python3", script.toString(), "--plan-file", file.toString()};
        } catch (java.io.IOException e) {
            throw new CloudRuntimeException("Unable to prepare volume backup plan", e);
        }
    }
}
