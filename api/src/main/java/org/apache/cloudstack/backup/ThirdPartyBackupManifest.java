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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.Gson;

/** Catalog for one logical VM backup. Image payloads are transferred independently of this metadata. */
public class ThirdPartyBackupManifest {
    public static final int VERSION = 1;
    public static final String FILE_NAME = "backup-manifest.json";
    public static final String DETAIL_KEY = "thirdparty.volume.manifest";
    public static final String MODE_KEY = "thirdparty.staging.mode";
    public static final String VOLUME_MODE = "VOLUME";
    private int version = VERSION;
    private String provider;
    private String backupUuid;
    private String vmName;
    private String timestamp;
    private String backupType;
    private String parentBackupUuid;
    private boolean complete;
    private List<Volume> volumes = new ArrayList<>();
    private Artifact metadata;

    public static class Artifact {
        public String backupUuid;
        public String path;
        public String sourceHost;
        public String externalId;
        public String jobId;
        public String backupTime;
        public String checkpointName;
        public String parentCheckpointName;
        public long size;
        public String sha256;
        public boolean completed;
        public boolean submissionPending;
    }

    public static class Volume {
        public String uuid;
        public long deviceId;
        public long provisionedBytes;
        public String engine;
        public List<Artifact> chain = new ArrayList<>();
    }

    public static ThirdPartyBackupManifest fromJson(String json) {
        final ThirdPartyBackupManifest result = new Gson().fromJson(json, ThirdPartyBackupManifest.class);
        if (result == null) {
            throw new CloudRuntimeException("Missing third-party backup manifest");
        }
        result.validate(false);
        return result;
    }

    public String toJson() {
        validate(false);
        return new Gson().toJson(this);
    }

    /** Added, removed, resized or replaced disks require a new Full chain. */
    public static boolean canContinue(Backup parent, String provider, String vmName, String engine,
            List<? extends com.cloud.storage.Volume> sourceVolumes) {
        if (parent == null || blank(parent.getDetail(DETAIL_KEY))) {
            return false;
        }
        try {
            ThirdPartyBackupManifest manifest = fromJson(parent.getDetail(DETAIL_KEY));
            manifest.validate(true);
            return provider.equals(manifest.provider) && vmName.equals(manifest.vmName)
                    && sourceVolumes.size() == manifest.volumes.size()
                    && sourceVolumes.stream().allMatch(source -> manifest.volumes.stream().anyMatch(volume ->
                            volume.uuid.equals(source.getUuid()) && volume.deviceId == source.getDeviceId()
                                    && volume.provisionedBytes == source.getSize() && engine.equals(volume.engine)));
        } catch (CloudRuntimeException e) {
            return false;
        }
    }

    public static ThirdPartyBackupManifest create(String provider, String backupUuid, String vmName, String timestamp,
            String backupType, String sourceHost, String backupPath, String engine, List<Backup.VolumeInfo> volumeInfos,
            String parentJson, String parentCheckpoint) {
        ThirdPartyBackupManifest manifest = new ThirdPartyBackupManifest();
        manifest.provider = provider;
        manifest.backupUuid = backupUuid;
        manifest.vmName = vmName;
        manifest.timestamp = timestamp;
        manifest.backupType = backupType;
        ThirdPartyBackupManifest parent = parentJson == null ? null : fromJson(parentJson);
        if (parent != null) {
            parent.validate(true);
            if (!provider.equals(parent.provider) || !vmName.equals(parent.vmName)
                    || parent.volumes.size() != volumeInfos.size()) {
                throw new CloudRuntimeException("Incremental manifest does not match the VM volume plan");
            }
            manifest.parentBackupUuid = parent.backupUuid;
        }
        for (Backup.VolumeInfo info : volumeInfos) {
            Volume volume = new Volume();
            volume.uuid = info.getUuid();
            volume.deviceId = info.getDeviceId();
            volume.provisionedBytes = info.getSize();
            volume.engine = engine;
            if (parent != null) {
                Volume previous = parent.volumes.stream().filter(v -> v.uuid.equals(info.getUuid())).findFirst()
                        .orElseThrow(() -> new CloudRuntimeException("Incremental volume has no parent artifact"));
                if (previous.provisionedBytes != info.getSize() || previous.deviceId != info.getDeviceId()
                        || !engine.equals(previous.engine)) {
                    throw new CloudRuntimeException("Incremental source volume changed since its parent backup");
                }
                volume.chain.addAll(previous.chain);
            }
            Artifact artifact = new Artifact();
            artifact.backupUuid = backupUuid;
            artifact.path = backupPath + "/" + info.getPath();
            artifact.sourceHost = sourceHost;
            artifact.checkpointName = timestamp;
            artifact.parentCheckpointName = parentCheckpoint;
            volume.chain.add(artifact);
            manifest.volumes.add(volume);
        }
        manifest.validate(false);
        return manifest;
    }

    public void validate(boolean requireComplete) {
        if (version != VERSION || !ThirdPartyBackupStagingService.isStagingProvider(provider)
                || blank(backupUuid) || blank(vmName) || blank(timestamp) || volumes == null || volumes.isEmpty()) {
            throw new CloudRuntimeException("Invalid third-party volume backup manifest");
        }
        final Set<String> ids = new HashSet<>();
        final Set<Long> devices = new HashSet<>();
        for (Volume volume : volumes) {
            if (volume == null || blank(volume.uuid) || !ids.add(volume.uuid) || !devices.add(volume.deviceId)
                    || volume.provisionedBytes <= 0 || blank(volume.engine) || volume.chain == null || volume.chain.isEmpty()) {
                throw new CloudRuntimeException("Invalid or duplicate volume in backup manifest");
            }
            final Set<String> paths = new HashSet<>();
            for (Artifact artifact : volume.chain) {
                if (artifact == null || blank(artifact.path) || !artifact.path.startsWith("/")
                        || !paths.add(artifact.path) || artifact.path.indexOf('\n') >= 0 || artifact.path.indexOf('\r') >= 0
                        || blank(artifact.backupUuid)) {
                    throw new CloudRuntimeException("Invalid volume artifact in backup manifest");
                }
                if (requireComplete && (!artifact.completed || artifact.submissionPending || blank(artifact.jobId)
                        || blank(artifact.externalId) || blank(artifact.sourceHost))) {
                    throw new CloudRuntimeException("A volume artifact has not been confirmed by the backup provider");
                }
            }
        }
        if (requireComplete && (!complete || metadata == null || !metadata.completed || metadata.submissionPending
                || blank(metadata.jobId) || blank(metadata.externalId))) {
            throw new CloudRuntimeException("The logical backup metadata job has not completed");
        }
    }

    /** Peak image staging is one artifact, including every required Full/incremental step. */
    public long getRequiredStagingBytes() {
        validate(false);
        return volumes.stream().mapToLong(volume -> volume.provisionedBytes).max().orElseThrow();
    }

    public List<Artifact> getCurrentArtifacts() {
        final List<Artifact> result = new ArrayList<>();
        for (Volume volume : volumes) {
            Artifact current = volume.chain.get(volume.chain.size() - 1);
            if (!backupUuid.equals(current.backupUuid)) {
                throw new CloudRuntimeException("Volume manifest leaf belongs to another logical backup");
            }
            result.add(current);
        }
        return Collections.unmodifiableList(result);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public String getProvider() { return provider; }
    public void setProvider(String value) { provider = value; }
    public String getBackupUuid() { return backupUuid; }
    public void setBackupUuid(String value) { backupUuid = value; }
    public String getVmName() { return vmName; }
    public void setVmName(String value) { vmName = value; }
    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String value) { timestamp = value; }
    public String getBackupType() { return backupType; }
    public void setBackupType(String value) { backupType = value; }
    public String getParentBackupUuid() { return parentBackupUuid; }
    public void setParentBackupUuid(String value) { parentBackupUuid = value; }
    public List<Volume> getVolumes() { return volumes; }
    public void setVolumes(List<Volume> value) { volumes = value; }
    public Artifact getMetadata() { return metadata; }
    public void setMetadata(Artifact value) { metadata = value; }
    public boolean isComplete() { return complete; }
    public void setComplete(boolean value) { complete = value; }
}
