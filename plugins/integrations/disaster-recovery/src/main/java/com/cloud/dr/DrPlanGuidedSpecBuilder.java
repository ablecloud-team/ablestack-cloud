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
package com.cloud.dr;

import javax.inject.Inject;

import org.apache.commons.lang3.StringUtils;

import com.cloud.utils.component.ManagerBase;
import com.cloud.dr.inventory.DrSourceHardwareInventoryService;
import com.cloud.dr.inventory.DrSourceVmHardware;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class DrPlanGuidedSpecBuilder extends ManagerBase {
    private static final Gson GSON = new Gson();

    @Inject
    private DrPlanTargetPlacementResolver drPlanTargetPlacementResolver;
    @Inject
    private DrSourceHardwareInventoryService drSourceHardwareInventoryService;

    public DrPlanGeneratedSpec build(DrPlanVO plan, DrPlanGuidedSpec spec) {
        DrPlanGeneratedSpec generated = new DrPlanGeneratedSpec();
        DrPlanGuidedSpec guided = spec != null ? spec : new DrPlanGuidedSpec();
        DrSourceVmHardware sourceHardware = resolveSourceHardware(plan);
        DrResolvedTargetPlacement placement = resolvePlacement(plan, guided);
        if (sourceHardware != null && sourceHardware.isComplete() && placement != null) {
            new DrTargetHardwareResolver().resolve(plan, guided, placement, null, sourceHardware.toJsonObject());
        }
        generated.setMappingJson(GSON.toJson(buildMapping(plan, guided, placement, sourceHardware)));
        generated.setScheduleJson(GSON.toJson(buildSchedule(plan, guided)));
        generated.setPolicyJson(GSON.toJson(buildPolicy(plan, guided, sourceHardware)));
        generated.setQuiescePolicyJson(GSON.toJson(buildQuiescePolicy(guided)));
        if (placement != null) {
            generated.getBlockingReasons().addAll(placement.getBlockingReasons());
            generated.getWarnings().addAll(placement.getWarnings());
        }
        if (sourceHardware != null && !sourceHardware.isComplete()) {
            generated.addBlockingReason(StringUtils.defaultIfBlank(sourceHardware.getErrorCode(),
                    DrPlanReadinessValidator.REASON_SOURCE_HARDWARE_INVENTORY_REQUIRED));
        }
        if (StringUtils.isBlank(guided.getTargetStorageRef())) {
            generated.addWarning("target storage was not selected");
        }
        if (parseDiskMappings(guided.getDiskMappingsJson()).size() == 0) {
            generated.addWarning("disk mapping was not selected");
        }
        if (StringUtils.isBlank(guided.getTargetNetworkRef()) && plan != null && StringUtils.endsWithIgnoreCase(plan.getDirection(), "_VMWARE")) {
            generated.addWarning("target VMware network was not selected");
        }
        return generated;
    }

    public void applyIfRequested(DrPlanVO plan, DrPlanGuidedSpec spec) {
        if (plan == null || spec == null || !spec.shouldApply()) {
            return;
        }
        DrPlanGeneratedSpec generated = build(plan, spec);
        for (String reason : generated.getBlockingReasons()) {
            if (reason.startsWith(DrPlanReadinessValidator.REASON_TARGET_COMPUTE_SIZE_INVALID)) {
                throw new com.cloud.exception.InvalidParameterValueException(reason);
            }
        }
        plan.setMappingJson(generated.getMappingJson());
        plan.setScheduleJson(generated.getScheduleJson());
        plan.setPolicyJson(generated.getPolicyJson());
        plan.setQuiescePolicyJson(generated.getQuiescePolicyJson());
    }

    private DrResolvedTargetPlacement resolvePlacement(DrPlanVO plan, DrPlanGuidedSpec spec) {
        return drPlanTargetPlacementResolver != null ? drPlanTargetPlacementResolver.resolve(plan, spec) : null;
    }

    private DrSourceVmHardware resolveSourceHardware(DrPlanVO plan) {
        return drSourceHardwareInventoryService != null ? drSourceHardwareInventoryService.resolve(plan) : null;
    }

    private JsonObject buildMapping(DrPlanVO plan, DrPlanGuidedSpec spec, DrResolvedTargetPlacement placement,
            DrSourceVmHardware sourceHardware) {
        JsonObject mapping = new JsonObject();
        mapping.addProperty("schemaVersion", "DR_PLAN_GUIDED_SPEC_V2");
        if (plan != null) {
            mapping.addProperty("direction", plan.getDirection());
            mapping.addProperty("sourceSiteId", plan.getSourceSiteId());
            mapping.addProperty("targetSiteId", plan.getTargetSiteId());
            if (plan.getSourceVmId() != null) {
                mapping.addProperty("sourceVmId", plan.getSourceVmId());
            }
            if (StringUtils.isNotBlank(plan.getSourceExternalRef())) {
                mapping.addProperty("sourceExternalRef", plan.getSourceExternalRef());
            }
        }
        JsonObject source = buildSource(plan, sourceHardware);
        if (!source.entrySet().isEmpty()) {
            mapping.add("source", source);
        }
        addString(mapping, "targetVmName", StringUtils.defaultIfBlank(spec.getTargetVmName(), defaultTargetVmName(plan)));
        addString(mapping, "targetZoneId", spec.getTargetZoneId());
        addString(mapping, "targetStorageRef", spec.getTargetStorageRef());
        addString(mapping, "targetDatastoreRef", spec.getTargetStorageRef());
        addString(mapping, "targetComputeRef", spec.getTargetComputeRef());
        addString(mapping, "targetResourcePoolRef", spec.getTargetComputeRef());
        addInteger(mapping, "targetCpuNumber", spec.getTargetCpuNumber());
        addInteger(mapping, "targetCpuSpeed", spec.getTargetCpuSpeed());
        addInteger(mapping, "targetMemory", spec.getTargetMemory());
        addString(mapping, "targetBootType", spec.getTargetBootType());
        addString(mapping, "targetBootMode", spec.getTargetBootMode());
        addString(mapping, "targetRootDiskController", spec.getTargetRootDiskController());
        addString(mapping, "targetDataDiskController", spec.getTargetDataDiskController());
        addBoolean(mapping, "targetIoThreadsEnabled", spec.getTargetIoThreadsEnabled());
        addString(mapping, "targetIoPolicy", spec.getTargetIoPolicy());
        addString(mapping, "targetNetworkRef", spec.getTargetNetworkRef());
        addString(mapping, "networkRef", spec.getTargetNetworkRef());
        addString(mapping, "targetFolderPath", spec.getTargetFolderPath());
        addString(mapping, "folderPath", spec.getTargetFolderPath());
        if (placement != null) {
            addLong(mapping, "targetZoneId", placement.getZoneId());
            addString(mapping, "targetStorageRef", placement.getStorageRef());
            addString(mapping, "targetStorageLocalId", placement.getStorageLocalId());
            addString(mapping, "targetComputeRef", placement.getServiceOfferingId());
            addString(mapping, "targetComputeLocalId", placement.getServiceOfferingLocalId());
            addInteger(mapping, "targetCpuNumber", placement.getTargetCpuNumber());
            addInteger(mapping, "targetCpuSpeed", placement.getTargetCpuSpeed());
            addInteger(mapping, "targetMemory", placement.getTargetMemory());
        }
        JsonObject target = buildTarget(plan, spec, placement);
        if (!target.entrySet().isEmpty()) {
            mapping.add("target", target);
        }
        JsonArray disks = buildDisks(spec, placement);
        if (disks.size() > 0) {
            mapping.add("disks", disks);
        }
        return mapping;
    }

    private JsonObject buildSource(DrPlanVO plan, DrSourceVmHardware sourceHardware) {
        JsonObject source = new JsonObject();
        JsonObject vm = new JsonObject();
        if (plan != null) {
            addLong(vm, "vmId", plan.getSourceVmId());
            addString(vm, "externalRef", plan.getSourceExternalRef());
            addLong(source, "siteId", plan.getSourceSiteId());
        }
        if (sourceHardware != null) {
            addString(vm, "externalRef", sourceHardware.getSourceVmRef());
            addString(vm, "guestId", sourceHardware.getGuestId());
            source.add("hardware", sourceHardware.toJsonObject());
        }
        if (!vm.entrySet().isEmpty()) {
            source.add("vm", vm);
        }
        return source;
    }

    private JsonObject buildTarget(DrPlanVO plan, DrPlanGuidedSpec spec, DrResolvedTargetPlacement placement) {
        JsonObject target = new JsonObject();
        if (plan != null) {
            addString(target, "hypervisor", targetHypervisor(plan.getDirection()));
            addLong(target, "siteId", plan.getTargetSiteId());
        }
        addString(target, "zoneId", spec.getTargetZoneId());
        addString(target, "vmName", StringUtils.defaultIfBlank(spec.getTargetVmName(), defaultTargetVmName(plan)));
        addString(target, "storageRef", spec.getTargetStorageRef());
        addString(target, "serviceOfferingId", spec.getTargetComputeRef());
        addInteger(target, "cpuNumber", spec.getTargetCpuNumber());
        addInteger(target, "cpuSpeed", spec.getTargetCpuSpeed());
        addInteger(target, "memory", spec.getTargetMemory());
        JsonObject hardware = new JsonObject();
        addString(hardware, "bootType", spec.getTargetBootType());
        addString(hardware, "bootMode", spec.getTargetBootMode());
        addString(hardware, "rootDiskController", spec.getTargetRootDiskController());
        addString(hardware, "dataDiskController", spec.getTargetDataDiskController());
        addBoolean(hardware, "ioThreadsEnabled", spec.getTargetIoThreadsEnabled());
        addString(hardware, "ioPolicy", spec.getTargetIoPolicy());
        if (placement != null) {
            addLong(target, "zoneId", placement.getZoneId());
            addLong(target, "workerHostId", placement.getWorkerHostId());
            addString(target, "vmName", StringUtils.defaultIfBlank(placement.getTargetVmName(), StringUtils.defaultIfBlank(spec.getTargetVmName(), defaultTargetVmName(plan))));
            addString(target, "storageRef", placement.getStorageRef());
            addString(target, "storagePoolId", placement.getStorageLocalId());
            addString(target, "storagePath", placement.getStoragePath());
            addString(target, "storagePoolType", placement.getStoragePoolType());
            addString(target, "storageHostAddress", placement.getStorageHostAddress());
            addString(target, "krbdPath", placement.getKrbdPath());
            addString(target, "serviceOfferingId", placement.getServiceOfferingId());
            addString(target, "serviceOfferingLocalId", placement.getServiceOfferingLocalId());
            addInteger(target, "cpuNumber", placement.getTargetCpuNumber());
            addInteger(target, "cpuSpeed", placement.getTargetCpuSpeed());
            addInteger(target, "memory", placement.getTargetMemory());
            if (placement.getTargetHardware() != null) {
                hardware = placement.getTargetHardware().toJsonObject();
            }
            if (!placement.getNetworks().isEmpty()) {
                JsonArray networks = new JsonArray();
                for (DrResolvedNetworkMapping network : placement.getNetworks()) {
                    networks.add(network.toJsonObject());
                }
                target.add("networks", networks);
            }
        } else if (StringUtils.isNotBlank(spec.getTargetNetworkRef())) {
            JsonArray networks = new JsonArray();
            for (String networkRef : StringUtils.split(spec.getTargetNetworkRef(), ',')) {
                if (StringUtils.isNotBlank(networkRef)) {
                    JsonObject network = new JsonObject();
                    network.addProperty("networkId", StringUtils.trim(networkRef));
                    network.addProperty("role", networks.size() == 0 ? "default" : "additional");
                    networks.add(network);
                }
            }
            target.add("networks", networks);
        }
        if (!hardware.entrySet().isEmpty()) {
            target.add("hardware", hardware);
        }
        addString(target, "folderPath", spec.getTargetFolderPath());
        return target;
    }

    private JsonArray buildDisks(DrPlanGuidedSpec spec, DrResolvedTargetPlacement placement) {
        JsonArray disks = new JsonArray();
        if (placement != null && !placement.getDisks().isEmpty()) {
            for (DrResolvedDiskMapping disk : placement.getDisks()) {
                disks.add(disk.toJsonObject());
            }
            return disks;
        }
        return parseDiskMappings(spec.getDiskMappingsJson());
    }

    private String targetHypervisor(String direction) {
        if (StringUtils.isBlank(direction)) {
            return null;
        }
        return StringUtils.endsWithIgnoreCase(direction, "_VMWARE") ? DrConstants.HYPERVISOR_TYPE_VMWARE : DrConstants.HYPERVISOR_TYPE_KVM;
    }

    private JsonObject buildSchedule(DrPlanVO plan, DrPlanGuidedSpec spec) {
        JsonObject schedule = new JsonObject();
        schedule.addProperty("schemaVersion", "DR_SCHEDULE_V1");
        schedule.addProperty("mode", "continuous");
        schedule.addProperty("intervalSeconds", positiveOrDefault(spec.getSyncIntervalSeconds(), positiveOrDefault(plan != null ? plan.getRpoSeconds() : null, 300)));
        schedule.addProperty("retentionCount", positiveOrDefault(spec.getRetentionCount(), 24));
        return schedule;
    }

    private JsonObject buildPolicy(DrPlanVO plan, DrPlanGuidedSpec spec,
            DrSourceVmHardware sourceHardware) {
        JsonObject policy = new JsonObject();
        policy.addProperty("schemaVersion", "DR_POLICY_V1");
        if (plan != null) {
            addInteger(policy, "rpoSeconds", plan.getRpoSeconds());
            addInteger(policy, "rtoSeconds", plan.getRtoSeconds());
        }
        policy.addProperty("consistencyMode", StringUtils.defaultIfBlank(StringUtils.upperCase(spec.getConsistencyMode()), "CRASH_CONSISTENT"));
        policy.addProperty("testNetworkMode", StringUtils.defaultIfBlank(StringUtils.upperCase(spec.getTestNetworkMode()), "ISOLATED"));
        policy.addProperty("testBootValidationMode", StringUtils.defaultIfBlank(StringUtils.upperCase(spec.getTestBootValidationMode()), "POWER_STATE_ONLY"));
        if (sourceHardware != null && StringUtils.containsIgnoreCase(sourceHardware.getGuestId(), "windows")) {
            policy.addProperty("failbackBootValidationMode", "GUEST_HEARTBEAT_REQUIRED");
        }
        policy.addProperty("testBootTimeoutSeconds", positiveOrDefault(spec.getTestBootTimeoutSeconds(), 180));
        JsonObject guestPreparation = new JsonObject();
        guestPreparation.addProperty("requiredForVmwareToKvm", true);
        guestPreparation.addProperty("linux", "INITRAMFS_VIRTIO");
        guestPreparation.addProperty("windows", "WINPE_VIRTIO");
        policy.add("guestPreparation", guestPreparation);
        JsonObject failover = new JsonObject();
        failover.addProperty("powerOn", spec.getFailoverPowerOn() == null || Boolean.TRUE.equals(spec.getFailoverPowerOn()));
        policy.add("failover", failover);
        JsonObject retry = new JsonObject();
        retry.addProperty("maxAttempts", positiveOrDefault(spec.getRetryCount(), 3));
        policy.add("retry", retry);
        if (spec.getBandwidthLimitMbps() != null && spec.getBandwidthLimitMbps() > 0) {
            policy.addProperty("bandwidthLimitMbps", spec.getBandwidthLimitMbps());
        }
        if (plan != null && StringUtils.startsWithIgnoreCase(plan.getDirection(), "VMWARE_")) {
            JsonObject cbtPolicy = new JsonObject();
            cbtPolicy.addProperty("required", true);
            cbtPolicy.addProperty("autoEnable", true);
            cbtPolicy.addProperty("failIfPreExistingSnapshots", false);
            policy.add("cbtPolicy", cbtPolicy);
        }
        return policy;
    }

    private JsonObject buildQuiescePolicy(DrPlanGuidedSpec spec) {
        JsonObject quiescePolicy = new JsonObject();
        quiescePolicy.addProperty("schemaVersion", "DR_QUIESCE_POLICY_V1");
        String consistencyMode = StringUtils.upperCase(spec.getConsistencyMode());
        boolean applicationConsistent = StringUtils.equalsAny(consistencyMode, "APPLICATION", "APPLICATION_CONSISTENT");
        quiescePolicy.addProperty("enabled", applicationConsistent);
        quiescePolicy.addProperty("mode", applicationConsistent ? "APPLICATION" : "CRASH_CONSISTENT");
        return quiescePolicy;
    }

    private String defaultTargetVmName(DrPlanVO plan) {
        String sourceRef = plan != null ? StringUtils.defaultIfBlank(plan.getSourceExternalRef(), plan.getSourceVmId() != null ? String.valueOf(plan.getSourceVmId()) : null) : null;
        return StringUtils.isNotBlank(sourceRef) ? sourceRef + "-dr" : null;
    }

    private Integer positiveOrDefault(Integer value, Integer defaultValue) {
        return value != null && value > 0 ? value : defaultValue;
    }

    private void addString(JsonObject object, String key, String value) {
        if (StringUtils.isNotBlank(value)) {
            object.addProperty(key, StringUtils.trim(value));
        }
    }

    private void addLong(JsonObject object, String key, Long value) {
        if (value != null) {
            object.addProperty(key, value);
        }
    }

    private void addInteger(JsonObject object, String key, Integer value) {
        if (value != null) {
            object.addProperty(key, value);
        }
    }

    private void addBoolean(JsonObject object, String key, Boolean value) {
        if (value != null) {
            object.addProperty(key, value);
        }
    }

    private JsonArray parseDiskMappings(String diskMappingsJson) {
        JsonArray result = new JsonArray();
        if (StringUtils.isBlank(diskMappingsJson)) {
            return result;
        }
        try {
            JsonElement parsed = JsonParser.parseString(diskMappingsJson);
            if (parsed != null && parsed.isJsonArray()) {
                for (JsonElement element : parsed.getAsJsonArray()) {
                    if (element != null && element.isJsonObject()) {
                        result.add(sanitizeDiskMapping(element.getAsJsonObject()));
                    }
                }
            } else if (parsed != null && parsed.isJsonObject()) {
                JsonObject object = parsed.getAsJsonObject();
                for (String key : new String[] {"disks", "diskMappings", "volumes", "volumeMappings"}) {
                    JsonElement element = object.get(key);
                    if (element != null && element.isJsonArray()) {
                        for (JsonElement disk : element.getAsJsonArray()) {
                            if (disk != null && disk.isJsonObject()) {
                                result.add(sanitizeDiskMapping(disk.getAsJsonObject()));
                            }
                        }
                        break;
                    }
                }
            }
        } catch (RuntimeException ignored) {
        }
        return result;
    }

    private JsonObject sanitizeDiskMapping(JsonObject disk) {
        JsonObject source = objectAt(disk, "source");
        JsonObject target = objectAt(disk, "target");
        JsonObject diskController = objectAt(disk, "controller");
        JsonObject sourceController = objectAt(source, "controller");
        JsonObject sanitized = new JsonObject();
        addString(sanitized, "label", firstString(disk, "label", "name"));
        String controllerBusNumber = firstNonBlank(firstString(disk, "controllerBusNumber", "controllerBus", "bus"),
                firstString(source, "controllerBusNumber", "controllerBus", "bus"));
        String unitNumber = firstNonBlank(firstString(disk, "unitNumber", "unit"),
                firstString(source, "unitNumber", "unit"));
        String cbtDiskId = firstNonBlank(firstString(disk, "cbtDiskId", "sourceCbtDiskId"),
                firstString(source, "cbtDiskId", "device"));
        addString(sanitized, "device", firstNonBlank(cbtDiskId, firstNonBlank(firstString(disk, "device"), firstString(source, "device"))));
        addString(sanitized, "cbtDiskId", cbtDiskId);
        addString(sanitized, "sourceDiskKey", firstNonBlank(firstString(disk, "sourceDiskKey", "deviceKey", "key"),
                firstString(source, "sourceDiskKey", "deviceKey", "key")));
        String controllerType = firstNonBlank(firstString(disk, "sourceController", "controllerType", "controller"),
                firstString(source, "sourceController", "controllerType", "controller"),
                firstString(diskController, "type", "name", "controllerType"),
                firstString(sourceController, "type", "name", "controllerType"));
        addString(sanitized, "sourceController", controllerType);
        addString(sanitized, "controllerBusNumber", controllerBusNumber);
        addString(sanitized, "unitNumber", unitNumber);
        addString(sanitized, "sourceRef", firstNonBlank(firstString(disk, "sourceRef", "sourceDiskRef", "sourceVolumeId", "sourcevolumeid"),
                firstString(source, "diskRef", "ref", "uuid", "id")));
        addString(sanitized, "sourcePath", firstNonBlank(firstString(disk, "sourcePath", "sourceVmdkPath", "sourceDisk"),
                firstString(source, "path", "vmdkPath")));
        String sourceType = firstNonBlank(firstString(disk, "sourceType"), firstString(source, "type", "sourceType"));
        String sourceFormat = firstNonBlank(firstString(disk, "sourceFormat"), firstString(source, "format", "sourceFormat"));
        if (StringUtils.isBlank(sourceType) && StringUtils.equalsAnyIgnoreCase(sourceFormat, "qcow2", "vmdk")) {
            sourceType = "file";
        }
        addString(sanitized, "sourceType", sourceType);
        addString(sanitized, "sourceFormat", sourceFormat);
        addString(sanitized, "targetRef", firstNonBlank(firstString(disk, "targetRef", "targetDiskRef", "targetVolumeId", "targetvolumeid"),
                firstString(target, "diskRef", "ref", "uuid", "id", "name")));
        addString(sanitized, "targetStorageRef", firstNonBlank(firstString(disk, "targetStorageRef", "storageRef"),
                firstString(target, "storageRef", "storagePoolId", "targetStorageRef")));
        addString(sanitized, "targetDiskOfferingId", firstNonBlank(firstString(disk, "targetDiskOfferingId", "diskOfferingId"),
                firstString(target, "diskOfferingId", "diskOfferingRef", "offeringId")));
        String capacityBytes = positiveLongString(firstNonBlank(firstString(disk, "capacityBytes", "sizeBytes", "virtualSize", "bytesTotal"),
                firstString(source, "capacityBytes", "sizeBytes", "virtualSize", "bytesTotal")));
        addString(sanitized, "capacityBytes", capacityBytes);
        addString(sanitized, "sizeBytes", capacityBytes);
        JsonObject sanitizedSource = new JsonObject();
        addString(sanitizedSource, "diskRef", firstString(sanitized, "sourceRef"));
        addString(sanitizedSource, "device", firstString(sanitized, "device"));
        addString(sanitizedSource, "cbtDiskId", firstString(sanitized, "cbtDiskId"));
        addString(sanitizedSource, "deviceKey", firstString(sanitized, "sourceDiskKey"));
        addString(sanitizedSource, "controllerType", controllerType);
        addString(sanitizedSource, "controllerBusNumber", controllerBusNumber);
        addString(sanitizedSource, "unitNumber", unitNumber);
        addString(sanitizedSource, "path", firstString(sanitized, "sourcePath"));
        addString(sanitizedSource, "vmdkPath", firstString(sanitized, "sourcePath"));
        addString(sanitizedSource, "type", sourceType);
        addString(sanitizedSource, "sourceType", sourceType);
        addString(sanitizedSource, "format", sourceFormat);
        addString(sanitizedSource, "sourceFormat", sourceFormat);
        addString(sanitizedSource, "label", firstString(source, "label", "name"));
        addString(sanitizedSource, "capacityBytes", capacityBytes);
        addString(sanitizedSource, "sizeBytes", capacityBytes);
        JsonElement boot = source.get("boot");
        if (boot != null && !boot.isJsonNull() && boot.isJsonPrimitive()) {
            try {
                sanitizedSource.addProperty("boot", boot.getAsBoolean());
            } catch (RuntimeException ignored) {
            }
        }
        if (!sanitizedSource.entrySet().isEmpty()) {
            sanitized.add("source", sanitizedSource);
        }
        JsonObject sanitizedTarget = new JsonObject();
        addString(sanitizedTarget, "name", firstNonBlank(firstString(target, "name"), firstString(sanitized, "targetRef")));
        addString(sanitizedTarget, "diskRef", firstString(sanitized, "targetRef"));
        addString(sanitizedTarget, "storageRef", firstString(sanitized, "targetStorageRef"));
        addString(sanitizedTarget, "diskOfferingId", firstString(sanitized, "targetDiskOfferingId"));
        String targetType = firstString(target, "type", "targetType");
        addString(sanitizedTarget, "type", targetType);
        addString(sanitizedTarget, "targetType", targetType);
        addString(sanitizedTarget, "format", StringUtils.equalsIgnoreCase(targetType, "rbd")
                ? "raw" : StringUtils.defaultIfBlank(firstString(target, "format", "targetFormat"), "qcow2"));
        addString(sanitizedTarget, "cacheMode", firstNonBlank(firstString(disk, "targetCacheMode", "cacheMode"),
                firstString(target, "targetCacheMode", "cacheMode")));
        addString(sanitizedTarget, "capacityBytes", capacityBytes);
        addString(sanitizedTarget, "sizeBytes", capacityBytes);
        if (!sanitizedTarget.entrySet().isEmpty()) {
            sanitized.add("target", sanitizedTarget);
        }
        return sanitized;
    }

    private JsonObject objectAt(JsonObject object, String key) {
        JsonElement element = object != null ? object.get(key) : null;
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    private String firstString(JsonObject object, String... keys) {
        if (object == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            JsonElement element = object.get(key);
            if (element != null && !element.isJsonNull() && element.isJsonPrimitive()) {
                String value = StringUtils.trimToNull(element.getAsString());
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    private String firstNonBlank(String first, String second) {
        return StringUtils.isNotBlank(first) ? first : StringUtils.trimToNull(second);
    }

    private String firstNonBlank(String first, String second, String third, String... rest) {
        String value = StringUtils.isNotBlank(first) ? first : (StringUtils.isNotBlank(second) ? second : StringUtils.trimToNull(third));
        if (StringUtils.isNotBlank(value) || rest == null) {
            return value;
        }
        for (String candidate : rest) {
            if (StringUtils.isNotBlank(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private String positiveLongString(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        try {
            long parsed = Long.parseLong(StringUtils.trim(value));
            return parsed > 0 ? String.valueOf(parsed) : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
