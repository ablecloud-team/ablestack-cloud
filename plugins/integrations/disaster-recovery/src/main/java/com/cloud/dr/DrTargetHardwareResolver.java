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

import java.util.Locale;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.commons.lang3.StringUtils;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class DrTargetHardwareResolver {
    public DrResolvedTargetHardware resolve(DrPlanVO plan, DrPlanGuidedSpec guided, DrResolvedTargetPlacement placement) {
        return resolve(plan, guided, placement, null);
    }

    public DrResolvedTargetHardware resolve(DrPlanVO plan, DrPlanGuidedSpec guided, DrResolvedTargetPlacement placement, JsonObject runtime) {
        return resolve(plan, guided, placement, runtime, null);
    }

    public DrResolvedTargetHardware resolve(DrPlanVO plan, DrPlanGuidedSpec guided, DrResolvedTargetPlacement placement,
            JsonObject runtime, JsonObject sourceHardwareOverride) {
        JsonObject mapping = parseObject(plan != null ? plan.getMappingJson() : null);
        JsonObject target = objectAt(mapping, "target");
        JsonObject targetHardware = objectAt(target, "hardware");
        JsonObject source = objectAt(mapping, "source");
        JsonObject sourceVm = firstObject(source, "vm", "sourceVm");
        JsonObject mappedSourceHardware = firstObject(source, "hardware", "sourceHardware");
        JsonObject sourceHardware = sourceHardwareOverride != null && !sourceHardwareOverride.entrySet().isEmpty()
                ? sourceHardwareOverride : mappedSourceHardware;
        JsonObject runtimeSource = objectAt(runtime, "source");
        JsonObject runtimeSourceVm = firstObject(runtimeSource, "vm", "sourceVm");
        JsonObject runtimeSourceHardware = firstObject(runtimeSource, "hardware", "sourceHardware");
        boolean kvmToKvm = plan != null && StringUtils.equalsIgnoreCase(plan.getDirection(), DrConstants.DIRECTION_KVM_TO_KVM);

        DrResolvedTargetHardware hardware = new DrResolvedTargetHardware();
        boolean requireSourceBoot = plan != null && StringUtils.startsWithIgnoreCase(plan.getDirection(), "VMWARE_");
        hardware.setBootType(resolveBootType(guided, mapping, target, targetHardware, sourceVm, sourceHardware,
                runtimeSourceVm, runtimeSourceHardware, requireSourceBoot, kvmToKvm));
        hardware.setBootMode(resolveBootMode(guided, mapping, target, targetHardware, sourceVm, sourceHardware,
                runtimeSourceVm, runtimeSourceHardware, hardware.getBootType(), kvmToKvm));
        hardware.setRootDiskController(resolveController(true, guided, mapping, target, targetHardware, sourceVm, sourceHardware, runtimeSourceVm, runtimeSourceHardware, placement));
        hardware.setDataDiskController(resolveController(false, guided, mapping, target, targetHardware, sourceVm, sourceHardware, runtimeSourceVm, runtimeSourceHardware, placement));
        hardware.setIoThreadsEnabled(resolveBoolean(guided != null ? guided.getTargetIoThreadsEnabled() : null,
                firstBoolean(targetHardware, "ioThreadsEnabled", "iothreadsEnabled"),
                firstBoolean(target, "ioThreadsEnabled", "iothreadsEnabled"),
                firstBoolean(mapping, "targetIoThreadsEnabled", "iothreadsEnabled"),
                firstBoolean(sourceHardware, "ioThreadsEnabled", "iothreadsEnabled"),
                firstBoolean(runtimeSourceHardware, "ioThreadsEnabled", "iothreadsEnabled"),
                Boolean.TRUE));
        hardware.setIoPolicy(resolveIoPolicy(guided, mapping, target, targetHardware, placement));
        if (placement != null) {
            placement.setTargetHardware(hardware);
        }
        return hardware;
    }

    private ApiConstants.BootType resolveBootType(DrPlanGuidedSpec guided, JsonObject mapping, JsonObject target, JsonObject targetHardware,
            JsonObject sourceVm, JsonObject sourceHardware, JsonObject runtimeSourceVm, JsonObject runtimeSourceHardware,
            boolean requireSourceBoot, boolean kvmToKvm) {
        if (kvmToKvm) {
            String uefiMode = firstNonBlank(firstString(sourceHardware, "UEFI", "uefi", "uefiMode"),
                    firstString(sourceVm, "UEFI", "uefi", "uefiMode"),
                    firstString(runtimeSourceHardware, "UEFI", "uefi", "uefiMode"),
                    firstString(runtimeSourceVm, "UEFI", "uefi", "uefiMode"));
            if (StringUtils.isNotBlank(uefiMode)) {
                return ApiConstants.BootType.UEFI;
            }
            String inventorySource = firstNonBlank(firstString(sourceHardware, "inventorySource"),
                    firstString(runtimeSourceHardware, "inventorySource"));
            String legacyFirmware = firstNonBlank(firstString(sourceHardware, "firmware"),
                    firstString(runtimeSourceHardware, "firmware"));
            if (StringUtils.isNotBlank(inventorySource) && StringUtils.containsIgnoreCase(legacyFirmware, "efi")) {
                return ApiConstants.BootType.UEFI;
            }
            return ApiConstants.BootType.BIOS;
        }
        String explicit = firstNonBlank(guided != null ? guided.getTargetBootType() : null,
                firstString(targetHardware, "bootType", "boottype"),
                firstString(target, "bootType", "boottype"),
                firstString(mapping, "targetBootType", "boottype"));
        ApiConstants.BootType parsed = parseBootType(explicit);
        if (parsed != null) {
            return parsed;
        }
        String firmware = firstNonBlank(firstString(sourceHardware, "firmware", "bootType", "boottype"),
                firstString(sourceVm, "firmware", "bootType", "boottype"),
                firstString(runtimeSourceHardware, "firmware", "bootType", "boottype"),
                firstString(runtimeSourceVm, "firmware", "bootType", "boottype"));
        Boolean secureBoot = resolveBoolean(null,
                firstBoolean(sourceHardware, "secureBoot", "secure_boot", "secure"),
                firstBoolean(sourceVm, "secureBoot", "secure_boot", "secure"),
                firstBoolean(runtimeSourceHardware, "secureBoot", "secure_boot", "secure"),
                firstBoolean(runtimeSourceVm, "secureBoot", "secure_boot", "secure"),
                null);
        if (StringUtils.containsIgnoreCase(firmware, "efi") || Boolean.TRUE.equals(secureBoot)) {
            return ApiConstants.BootType.UEFI;
        }
        if (requireSourceBoot && StringUtils.isBlank(firmware) && secureBoot == null) {
            return null;
        }
        return ApiConstants.BootType.BIOS;
    }

    private ApiConstants.BootMode resolveBootMode(DrPlanGuidedSpec guided, JsonObject mapping, JsonObject target, JsonObject targetHardware,
            JsonObject sourceVm, JsonObject sourceHardware, JsonObject runtimeSourceVm, JsonObject runtimeSourceHardware,
            ApiConstants.BootType bootType, boolean kvmToKvm) {
        if (kvmToKvm) {
            String uefiMode = firstNonBlank(firstString(sourceHardware, "UEFI", "uefi", "uefiMode"),
                    firstString(sourceVm, "UEFI", "uefi", "uefiMode"),
                    firstString(runtimeSourceHardware, "UEFI", "uefi", "uefiMode"),
                    firstString(runtimeSourceVm, "UEFI", "uefi", "uefiMode"));
            ApiConstants.BootMode parsedUefiMode = parseBootMode(uefiMode);
            if (parsedUefiMode != null) {
                return parsedUefiMode;
            }
            Boolean secureBoot = resolveBoolean(null,
                    firstBoolean(sourceHardware, "secureBoot", "secure_boot", "secure"),
                    firstBoolean(sourceVm, "secureBoot", "secure_boot", "secure"),
                    firstBoolean(runtimeSourceHardware, "secureBoot", "secure_boot", "secure"),
                    firstBoolean(runtimeSourceVm, "secureBoot", "secure_boot", "secure"),
                    Boolean.FALSE);
            return bootType == ApiConstants.BootType.UEFI && Boolean.TRUE.equals(secureBoot)
                    ? ApiConstants.BootMode.SECURE : ApiConstants.BootMode.LEGACY;
        }
        String explicit = firstNonBlank(guided != null ? guided.getTargetBootMode() : null,
                firstString(targetHardware, "bootMode", "bootmode"),
                firstString(target, "bootMode", "bootmode"),
                firstString(mapping, "targetBootMode", "bootmode"));
        ApiConstants.BootMode parsed = parseBootMode(explicit);
        if (parsed != null) {
            return parsed;
        }
        Boolean secureBoot = resolveBoolean(null,
                firstBoolean(sourceHardware, "secureBoot", "secure_boot", "secure"),
                firstBoolean(sourceVm, "secureBoot", "secure_boot", "secure"),
                firstBoolean(runtimeSourceHardware, "secureBoot", "secure_boot", "secure"),
                firstBoolean(runtimeSourceVm, "secureBoot", "secure_boot", "secure"),
                Boolean.FALSE);
        if (bootType == null) {
            return null;
        }
        return bootType == ApiConstants.BootType.UEFI && Boolean.TRUE.equals(secureBoot)
                ? ApiConstants.BootMode.SECURE : ApiConstants.BootMode.LEGACY;
    }

    private String resolveController(boolean root, DrPlanGuidedSpec guided, JsonObject mapping, JsonObject target, JsonObject targetHardware,
            JsonObject sourceVm, JsonObject sourceHardware, JsonObject runtimeSourceVm, JsonObject runtimeSourceHardware,
            DrResolvedTargetPlacement placement) {
        String fromSpec = root ? (guided != null ? guided.getTargetRootDiskController() : null)
                : (guided != null ? guided.getTargetDataDiskController() : null);
        String key = root ? "rootDiskController" : "dataDiskController";
        String mappingKey = root ? "targetRootDiskController" : "targetDataDiskController";
        String source = firstNonBlank(fromSpec,
                firstString(targetHardware, key, root ? "rootController" : "dataController"),
                firstString(target, key),
                firstString(mapping, mappingKey),
                firstString(sourceHardware, key, root ? "rootController" : "dataController"),
                firstString(sourceVm, key),
                firstString(runtimeSourceHardware, key, root ? "rootController" : "dataController"),
                firstString(runtimeSourceVm, key),
                diskController(root, placement));
        String normalized = normalizeController(source);
        if (normalized == null && StringUtils.isNotBlank(source)) {
            addBlocker(placement, DrPlanReadinessValidator.REASON_TARGET_DISK_CONTROLLER_UNSUPPORTED + ":" + source);
        }
        return StringUtils.defaultIfBlank(normalized, "scsi");
    }

    private ApiConstants.IoDriverPolicy resolveIoPolicy(DrPlanGuidedSpec guided, JsonObject mapping, JsonObject target,
            JsonObject targetHardware, DrResolvedTargetPlacement placement) {
        String raw = firstNonBlank(guided != null ? guided.getTargetIoPolicy() : null,
                firstString(targetHardware, "ioPolicy", "io.policy", "ioDriverPolicy"),
                firstString(target, "ioPolicy", "io.policy", "ioDriverPolicy"),
                firstString(mapping, "targetIoPolicy", "ioPolicy", "io.policy"));
        if (StringUtils.isBlank(raw)) {
            return ApiConstants.IoDriverPolicy.IO_URING;
        }
        for (ApiConstants.IoDriverPolicy policy : ApiConstants.IoDriverPolicy.values()) {
            if (StringUtils.equalsIgnoreCase(raw, policy.toString())
                    || StringUtils.equalsIgnoreCase(normalizeEnum(raw), policy.name())) {
                return policy;
            }
        }
        addBlocker(placement, DrPlanReadinessValidator.REASON_TARGET_IO_POLICY_UNSUPPORTED + ":" + raw);
        return ApiConstants.IoDriverPolicy.IO_URING;
    }

    private String diskController(boolean root, DrResolvedTargetPlacement placement) {
        if (placement == null || placement.getDisks().isEmpty()) {
            return null;
        }
        String firstData = null;
        for (DrResolvedDiskMapping disk : placement.getDisks()) {
            if (disk == null) {
                continue;
            }
            if (root && Boolean.TRUE.equals(disk.getBoot()) && StringUtils.isNotBlank(disk.getSourceController())) {
                return disk.getSourceController();
            }
            if (!root && !Boolean.TRUE.equals(disk.getBoot()) && StringUtils.isNotBlank(disk.getSourceController())) {
                if (firstData == null) {
                    firstData = disk.getSourceController();
                } else if (!StringUtils.equalsIgnoreCase(normalizeController(firstData), normalizeController(disk.getSourceController()))) {
                    addBlocker(placement, DrPlanReadinessValidator.REASON_TARGET_MIXED_DATA_CONTROLLER_UNSUPPORTED);
                }
            }
        }
        return root ? (StringUtils.isNotBlank(placement.getDisks().get(0).getSourceController())
                ? placement.getDisks().get(0).getSourceController() : null) : firstData;
    }

    private String normalizeController(String controller) {
        String raw = StringUtils.lowerCase(StringUtils.trimToEmpty(controller), Locale.ROOT);
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        if (raw.contains("pvscsi") || raw.contains("lsilogic") || raw.contains("lsisas")
                || raw.contains("buslogic") || raw.contains("paravirtual") || raw.contains("scsi")) {
            return "scsi";
        }
        if (raw.contains("virtio")) {
            return "virtio";
        }
        if (raw.contains("sata") || raw.contains("ahci")) {
            return "sata";
        }
        if (raw.contains("ide")) {
            return "ide";
        }
        return null;
    }

    private ApiConstants.BootType parseBootType(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        try {
            return ApiConstants.BootType.valueOf(normalizeEnum(value));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private ApiConstants.BootMode parseBootMode(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        try {
            return ApiConstants.BootMode.valueOf(normalizeEnum(value));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String normalizeEnum(String value) {
        return StringUtils.upperCase(StringUtils.replace(StringUtils.trimToEmpty(value), "-", "_"), Locale.ROOT);
    }

    private Boolean resolveBoolean(Boolean first, Boolean second, Boolean third, Boolean fourth, Boolean fifth, Boolean sixth, Boolean defaultValue) {
        if (first != null) {
            return first;
        }
        if (second != null) {
            return second;
        }
        if (third != null) {
            return third;
        }
        if (fourth != null) {
            return fourth;
        }
        if (fifth != null) {
            return fifth;
        }
        return sixth != null ? sixth : defaultValue;
    }

    private Boolean resolveBoolean(Boolean first, Boolean second, Boolean third, Boolean fourth, Boolean fifth, Boolean defaultValue) {
        return resolveBoolean(first, second, third, fourth, fifth, null, defaultValue);
    }

    private JsonObject parseObject(String json) {
        if (StringUtils.isBlank(json)) {
            return new JsonObject();
        }
        try {
            JsonElement parsed = JsonParser.parseString(json);
            return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException ignored) {
            return new JsonObject();
        }
    }

    private JsonObject objectAt(JsonObject object, String key) {
        JsonElement element = object != null ? object.get(key) : null;
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    private JsonObject firstObject(JsonObject object, String... keys) {
        if (object == null || keys == null) {
            return new JsonObject();
        }
        for (String key : keys) {
            JsonObject child = objectAt(object, key);
            if (!child.entrySet().isEmpty()) {
                return child;
            }
        }
        return new JsonObject();
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

    private Boolean firstBoolean(JsonObject object, String... keys) {
        if (object == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            JsonElement element = object.get(key);
            if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
                continue;
            }
            String value = StringUtils.trimToNull(element.getAsString());
            if (StringUtils.equalsAnyIgnoreCase(value, "true", "yes", "enabled", "1")) {
                return true;
            }
            if (StringUtils.equalsAnyIgnoreCase(value, "false", "no", "disabled", "0")) {
                return false;
            }
            throw new com.cloud.utils.exception.CloudRuntimeException(
                    "TARGET_TUNING_INVALID: " + key + " must be a Boolean");
        }
        return null;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            String trimmed = StringUtils.trimToNull(value);
            if (trimmed != null) {
                return trimmed;
            }
        }
        return null;
    }

    private void addBlocker(DrResolvedTargetPlacement placement, String reason) {
        if (placement != null && StringUtils.isNotBlank(reason)) {
            placement.addBlockingReason(reason);
        }
    }
}
