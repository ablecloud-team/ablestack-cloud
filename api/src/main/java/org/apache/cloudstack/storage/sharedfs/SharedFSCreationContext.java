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
package org.apache.cloudstack.storage.sharedfs;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.cloudstack.api.command.user.storage.sharedfs.CreateSharedFSCmd;
import com.cloud.exception.InvalidParameterValueException;
import com.google.gson.Gson;

/** Server-owned, credential-free initial allocation journal. Not a general operation log. */
public final class SharedFSCreationContext {
    private static final Gson GSON = new Gson();
    public static final int MAX_JSON_BYTES = 65535;
    public static final String INVALID_JOURNAL = "CREATION_JOURNAL_INVALID";

    public static final class InvalidJournalException extends IllegalStateException {
        public InvalidJournalException(Throwable cause) { super(INVALID_JOURNAL, cause); }
    }

    public static void validateSize(String value) {
        if (value != null && value.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
            throw new InvalidParameterValueException("CREATION_JOURNAL_TOO_LARGE: TEXT byte limit exceeded");
        }
    }
    public Boolean prepareNewFileBacking;
    public com.google.gson.JsonObject preparation;
    public static int initialFormatBudget(long bytes, int cap, long remainingSeconds) {
        if (bytes <= 0 || cap < 300 || cap > 7200 || remainingSeconds < 1140)
            throw new InvalidParameterValueException("Initial format has insufficient bounded job budget");
        long tib = (bytes + (1L << 40) - 1) / (1L << 40);
        return (int)Math.min(Math.min(Math.max(300,120 + 180*tib),cap),remainingSeconds-840);
    }

    public int version = 1;
    public boolean existing;
    public String fingerprint;
    public Long volumeId;
    public Long networkId;
    public java.util.List<Long> ownedVolumeIds = new java.util.ArrayList<>();
    public Long originalPoolId;
    public String originalPath;
    public Long originalSize;
    public Long originalDiskOfferingId;
    public Long diskOfferingId;
    public Long storageId;
    public Long size;
    public Long minIops;
    public Long maxIops;
    public Map<String, String> compute = new LinkedHashMap<>();
    public String expectedFilesystem;
    public String importMode;
    public String plannedVmUuid;
    public String phase = "RESERVED";
    public String observedFilesystem;
    public String filesystemUuid;
    public String mountPath;
    public String bootId;
    public String errorCode;
    public String lastJobUuid;
    public boolean cancelled;

    public static SharedFSCreationContext read(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            validateSize(value);
            SharedFSCreationContext c = GSON.fromJson(value, SharedFSCreationContext.class);
            if (c == null || c.version != 1) throw new IllegalStateException("Unsupported creation journal");
            return c;
        } catch (RuntimeException e) {
            throw new InvalidJournalException(e);
        }
    }

    public String json() {
        if (preparation != null && preparation.toString().getBytes(StandardCharsets.UTF_8).length > 8192)
            throw new InvalidParameterValueException("Initial preparation state exceeds 8 KiB");
        String value = GSON.toJson(this);
        validateSize(value);
        return value;
    }

    public static String normalizeKey(String key) {
        if (key == null) return null;
        try {
            String normalized = UUID.fromString(key).toString();
            if (!normalized.equalsIgnoreCase(key)) throw new IllegalArgumentException();
            return normalized;
        } catch (IllegalArgumentException e) {
            throw new InvalidParameterValueException("requestid must be a UUID");
        }
    }

    public static SharedFSCreationContext from(CreateSharedFSCmd cmd) {
        SharedFSCreationContext c = new SharedFSCreationContext();
        c.existing = cmd.getVolumeId() != null;
        c.volumeId = cmd.getVolumeId();
        c.networkId = cmd.getNetworkId();
        c.diskOfferingId = cmd.getDiskOfferingId();
        c.storageId = cmd.getStorageId();
        c.size = cmd.getSize();
        c.minIops = cmd.getMinIops();
        c.maxIops = cmd.getMaxIops();
        c.compute.putAll(cmd.getCustomComputeParameters());
        c.expectedFilesystem = cmd.getFsFormat() == null ? (c.existing ? "AUTO" : "") : cmd.getFsFormat().trim().toUpperCase();
        c.importMode = cmd.getImportMode() == null ? "MOUNT_EXISTING" : cmd.getImportMode().trim().toUpperCase();
        if (c.existing) {
            if (c.diskOfferingId != null || c.storageId != null || c.size != null || c.minIops != null || c.maxIops != null)
                throw new InvalidParameterValueException("Existing backing volumes cannot be combined with new disk allocation parameters");
            if (!"MOUNT_EXISTING".equals(c.importMode) && !"INSPECT_ONLY".equals(c.importMode))
                throw new InvalidParameterValueException("Existing backing volumes support only INSPECT_ONLY or MOUNT_EXISTING");
        } else if ("AUTO".equals(c.expectedFilesystem) || c.expectedFilesystem.isEmpty()) {
            throw new InvalidParameterValueException("A new backing volume requires XFS or EXT4");
        }
        if (!java.util.Set.of("XFS", "EXT4", "AUTO").contains(c.expectedFilesystem))
            throw new InvalidParameterValueException("Supported filesystems are XFS, EXT4, or AUTO for existing volumes");
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("provider", cmd.getSharedFSProviderName());
        input.put("name", cmd.getName()); input.put("description", cmd.getDescription());
        input.put("zone", cmd.getZoneId()); input.put("offering", cmd.getServiceOfferingId());
        input.put("network", c.networkId); input.put("networkMode", cmd.getNetworkMode());
        input.put("cidr", cmd.getIpCidr()); input.put("gateway", cmd.getGateway());
        input.put("dns1", cmd.getDns1()); input.put("dns2", cmd.getDns2());
        input.put("backing", c.json());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(GSON.toJson(input).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b & 255));
            c.fingerprint = hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        // Outside the old fingerprint encoding; replay validates this optional extension separately.
        c.prepareNewFileBacking = c.existing ? null : cmd.getInitialFileBacking();
        return c;
    }

    public void planVm(String sharedFsUuid) {
        plannedVmUuid = UUID.nameUUIDFromBytes(("sharedfs-initial-vm:" + sharedFsUuid).getBytes(StandardCharsets.UTF_8)).toString();
    }

    public void acceptInspection(String fs, String fsUuid) {
        if (fsUuid == null || fsUuid.isBlank() || fs == null || !java.util.Set.of("xfs", "ext4").contains(fs.toLowerCase()))
            throw new InvalidParameterValueException("Existing backing volume has no supported observed filesystem and UUID; formatting is forbidden");
        if (!"AUTO".equals(expectedFilesystem) && !expectedFilesystem.equalsIgnoreCase(fs))
            throw new InvalidParameterValueException("Existing backing filesystem does not match the requested expectation");
        if (filesystemUuid != null && !filesystemUuid.equals(fsUuid))
            throw new InvalidParameterValueException("Observed backing filesystem UUID changed since the initial inspection");
        observedFilesystem = fs.toLowerCase();
        filesystemUuid = fsUuid;
    }
}
