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

import java.util.List;

import javax.inject.Inject;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.commons.lang3.StringUtils;

import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dr.dao.DrSiteDao;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.network.Network;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.service.dao.ServiceOfferingDetailsDao;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.StoragePoolStatus;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.utils.component.ManagerBase;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class DrPlanTargetPlacementResolverImpl extends ManagerBase implements DrPlanTargetPlacementResolver {
    @Inject
    private DrSiteDao drSiteDao;
    @Inject
    private DataCenterDao dataCenterDao;
    @Inject
    private HostDao hostDao;
    @Inject
    private DrWorkerPlacementService drWorkerPlacementService;
    @Inject
    private PrimaryDataStoreDao primaryDataStoreDao;
    @Inject
    private ServiceOfferingDao serviceOfferingDao;
    @Inject
    private ServiceOfferingDetailsDao serviceOfferingDetailsDao;
    @Inject
    private DiskOfferingDao diskOfferingDao;
    @Inject
    private NetworkDao networkDao;
    @Inject
    private com.cloud.user.AccountManager accountManager;

    @Override
    public DrResolvedTargetPlacement resolve(DrPlanVO plan, DrPlanGuidedSpec spec) {
        if (plan == null || !isKvmTarget(plan)) {
            return null;
        }
        DrResolvedTargetPlacement placement = new DrResolvedTargetPlacement();
        DrPlanGuidedSpec guided = spec != null ? spec : new DrPlanGuidedSpec();
        placement.setTargetVmName(StringUtils.trimToNull(guided.getTargetVmName()));
        DrSiteVO targetSite = drSiteDao != null ? drSiteDao.findById(plan.getTargetSiteId()) : null;
        Long zoneId = resolveZoneId(targetSite, guided.getTargetZoneId(), placement);
        placement.setZoneId(zoneId);
        resolveTargetWorker(plan, zoneId, placement);
        resolveStorage(StringUtils.trimToNull(guided.getTargetStorageRef()), zoneId, placement,
                !diskMappingsProvideTargetStorage(guided.getDiskMappingsJson()));
        resolveServiceOffering(StringUtils.trimToNull(guided.getTargetComputeRef()), guided, placement);
        resolveNetworks(StringUtils.trimToNull(guided.getTargetNetworkRef()), zoneId, placement);
        resolveDisks(guided, zoneId, placement);
        new DrTargetHardwareResolver().resolve(plan, guided, placement);
        return placement;
    }

    private boolean isKvmTarget(DrPlanVO plan) {
        return plan != null && StringUtils.endsWithIgnoreCase(plan.getDirection(), "_KVM");
    }

    private Long resolveZoneId(DrSiteVO targetSite, String targetZoneRef, DrResolvedTargetPlacement placement) {
        if (StringUtils.isNotBlank(targetZoneRef)) {
            Long resolved = findZoneId(targetZoneRef);
            if (resolved != null) {
                return resolved;
            }
            placement.addBlockingReason(DrPlanReadinessValidator.REASON_TARGET_SITE_ZONE_REQUIRED + ":" + targetZoneRef);
            return null;
        }
        if (targetSite != null && targetSite.getZoneId() != null) {
            return targetSite.getZoneId();
        }
        if (dataCenterDao != null) {
            List<DataCenterVO> zones = dataCenterDao.listEnabledZones();
            if (zones != null && zones.size() == 1) {
                DataCenterVO zone = zones.get(0);
                placement.addWarning("TARGET_SITE_ZONE_INFERRED");
                return zone.getId();
            }
        }
        placement.addBlockingReason(DrPlanReadinessValidator.REASON_TARGET_SITE_ZONE_REQUIRED);
        return null;
    }

    private Long findZoneId(String ref) {
        String targetRef = StringUtils.trimToNull(ref);
        if (targetRef == null) {
            return null;
        }
        Long numeric = parseLong(targetRef);
        if (numeric != null && dataCenterDao != null && dataCenterDao.findById(numeric) != null) {
            return numeric;
        }
        if (dataCenterDao != null) {
            List<DataCenterVO> zones = dataCenterDao.listEnabledZones();
            if (zones != null) {
                for (DataCenterVO zone : zones) {
                    if (zone != null && matchesRef(targetRef, zone.getId(), zone.getUuid())) {
                        return zone.getId();
                    }
                }
            }
        }
        return null;
    }

    private void resolveTargetWorker(DrPlanVO plan, Long zoneId, DrResolvedTargetPlacement placement) {
        Long workerHostId = drWorkerPlacementService != null
                ? drWorkerPlacementService.resolveWorkerHostId(plan, DrWorkerRole.TARGET) : null;
        if (workerHostId == null) {
            placement.addBlockingReason(DrPlanReadinessValidator.REASON_TARGET_WORKER_REQUIRED + ":NO_ELIGIBLE_WORKER");
            return;
        }
        placement.setWorkerHostId(workerHostId);
        HostVO host = hostDao != null ? hostDao.findById(workerHostId) : null;
        if (host == null) {
            placement.addBlockingReason(DrPlanReadinessValidator.REASON_WORKER_HOST_NOT_FOUND + ":target:" + workerHostId);
            return;
        }
        if (zoneId != null && host.getDataCenterId() != zoneId.longValue()) {
            placement.addBlockingReason(DrPlanReadinessValidator.REASON_TARGET_WORKER_REQUIRED + ":ZONE_MISMATCH:" + workerHostId);
        }
    }

    private void resolveStorage(String storageRef, Long zoneId, DrResolvedTargetPlacement placement, boolean required) {
        if (StringUtils.isBlank(storageRef)) {
            if (required) {
                placement.addBlockingReason(DrPlanReadinessValidator.REASON_TARGET_STORAGE_REQUIRED);
            }
            return;
        }
        StoragePoolVO pool = findStoragePool(storageRef, zoneId);
        if (pool == null) {
            if (required) {
                placement.addBlockingReason(DrPlanReadinessValidator.REASON_TARGET_STORAGE_REQUIRED + ":" + storageRef);
            } else {
                placement.addWarning(DrPlanReadinessValidator.REASON_TARGET_STORAGE_REQUIRED + ":" + storageRef);
            }
            return;
        }
        applyStorage(pool, storageRef, placement);
    }

    private boolean diskMappingsProvideTargetStorage(String diskMappingsJson) {
        JsonArray disks = parseDiskMappings(diskMappingsJson);
        if (disks.size() == 0) {
            return false;
        }
        for (int i = 0; i < disks.size(); i++) {
            JsonElement element = disks.get(i);
            if (element == null || !element.isJsonObject()) {
                return false;
            }
            JsonObject disk = element.getAsJsonObject();
            JsonObject target = objectAt(disk, "target");
            if (StringUtils.isBlank(firstNonBlank(firstString(disk, "targetStorageRef", "storageRef"),
                    firstString(target, "storageRef", "storagePoolId", "targetStorageRef")))) {
                return false;
            }
        }
        return true;
    }

    private void resolveServiceOffering(String serviceOfferingRef, DrPlanGuidedSpec guided, DrResolvedTargetPlacement placement) {
        if (StringUtils.isBlank(serviceOfferingRef)) {
            placement.addBlockingReason(DrPlanReadinessValidator.REASON_TARGET_SERVICE_OFFERING_REQUIRED);
            return;
        }
        ServiceOfferingVO offering = findServiceOffering(serviceOfferingRef);
        if (offering == null) {
            placement.addBlockingReason(DrPlanReadinessValidator.REASON_TARGET_SERVICE_OFFERING_REQUIRED + ":" + serviceOfferingRef);
            return;
        }
        com.cloud.user.Account caller = org.apache.cloudstack.context.CallContext.current().getCallingAccount();
        if (caller != null && placement.getZoneId() != null) {
            accountManager.checkAccess(caller, offering, dataCenterDao.findById(placement.getZoneId()));
        }
        placement.setServiceOfferingId(serviceOfferingRef);
        placement.setServiceOfferingLocalId(String.valueOf(offering.getId()));
        resolveComputeSizing(offering, guided, placement);
    }

    void resolveComputeSizing(ServiceOfferingVO offering, DrPlanGuidedSpec guided, DrResolvedTargetPlacement placement) {
        Integer cpuNumber = firstNonNull(positiveInteger(offering.getCpu()), positiveInteger(guided != null ? guided.getTargetCpuNumber() : null));
        Integer cpuSpeed = firstNonNull(positiveInteger(offering.getSpeed()), positiveInteger(guided != null ? guided.getTargetCpuSpeed() : null));
        Integer memory = firstNonNull(positiveInteger(offering.getRamSize()), positiveInteger(guided != null ? guided.getTargetMemory() : null));
        if (offering.isDynamic()) {
            if (guided != null) {
                validateExplicitComputeValue(offering.getCpu(), guided.getTargetCpuNumber(), "cpuNumber", placement);
                validateExplicitComputeValue(offering.getSpeed(), guided.getTargetCpuSpeed(), "cpuSpeed", placement);
                validateExplicitComputeValue(offering.getRamSize(), guided.getTargetMemory(), "memory", placement);
            }
            if (offering.getCpu() == null) {
                validateRequiredComputeValue(cpuNumber, "cpuNumber", placement);
                validateRange(cpuNumber, detailInteger(offering, ApiConstants.MIN_CPU_NUMBER),
                        detailInteger(offering, ApiConstants.MAX_CPU_NUMBER), "cpuNumber", placement);
            }
            if (offering.getSpeed() == null) {
                validateRequiredComputeValue(cpuSpeed, "cpuSpeed", placement);
            }
            if (offering.getRamSize() == null) {
                validateRequiredComputeValue(memory, "memory", placement);
                validateRange(memory, detailInteger(offering, ApiConstants.MIN_MEMORY),
                        detailInteger(offering, ApiConstants.MAX_MEMORY), "memory", placement);
            }
        }
        placement.setTargetCpuNumber(cpuNumber);
        placement.setTargetCpuSpeed(cpuSpeed);
        placement.setTargetMemory(memory);
    }

    private void validateExplicitComputeValue(Integer fixed, Integer requested, String field, DrResolvedTargetPlacement placement) {
        if (fixed == null && requested != null && requested <= 0) {
            placement.addBlockingReason(DrPlanReadinessValidator.REASON_TARGET_COMPUTE_SIZE_INVALID + ":" + field);
        }
    }

    private void validateRequiredComputeValue(Integer value, String field, DrResolvedTargetPlacement placement) {
        if (value == null || value <= 0) {
            placement.addBlockingReason(DrPlanReadinessValidator.REASON_TARGET_COMPUTE_SIZE_REQUIRED + ":" + field);
        }
    }

    private void validateRange(Integer value, Integer min, Integer max, String field, DrResolvedTargetPlacement placement) {
        if (value == null) {
            return;
        }
        if (min != null && value < min) {
            placement.addBlockingReason(DrPlanReadinessValidator.REASON_TARGET_COMPUTE_SIZE_INVALID + ":" + field + ":MIN:" + min);
        }
        if (max != null && value > max) {
            placement.addBlockingReason(DrPlanReadinessValidator.REASON_TARGET_COMPUTE_SIZE_INVALID + ":" + field + ":MAX:" + max);
        }
    }

    private Integer detailInteger(ServiceOfferingVO offering, String key) {
        if (offering == null || serviceOfferingDetailsDao == null || StringUtils.isBlank(key)) {
            return null;
        }
        return positiveInteger(serviceOfferingDetailsDao.getDetail(offering.getId(), key));
    }

    private void resolveNetworks(String networkRefs, Long zoneId, DrResolvedTargetPlacement placement) {
        if (StringUtils.isBlank(networkRefs)) {
            placement.addBlockingReason(DrPlanReadinessValidator.REASON_TARGET_NETWORK_REQUIRED);
            return;
        }
        int index = 0;
        for (String ref : StringUtils.split(networkRefs, ',')) {
            String networkRef = StringUtils.trimToNull(ref);
            if (networkRef == null) {
                continue;
            }
            NetworkVO network = findNetwork(networkRef, zoneId);
            if (network == null) {
                placement.addBlockingReason(DrPlanReadinessValidator.REASON_TARGET_NETWORK_REQUIRED + ":" + networkRef);
                continue;
            }
            DrResolvedNetworkMapping mapping = new DrResolvedNetworkMapping();
            mapping.setNetworkId(networkRef);
            mapping.setNetworkLocalId(String.valueOf(network.getId()));
            mapping.setRole(index == 0 ? "default" : "additional");
            mapping.setName(StringUtils.defaultIfBlank(network.getName(), network.getUuid()));
            placement.addNetwork(mapping);
            index++;
        }
    }

    private void resolveDisks(DrPlanGuidedSpec guided, Long zoneId, DrResolvedTargetPlacement placement) {
        JsonArray disks = parseDiskMappings(guided.getDiskMappingsJson());
        if (disks.size() == 0) {
            placement.addBlockingReason(DrPlanReadinessValidator.REASON_DISK_MAPPING_REQUIRED);
            return;
        }
        for (int i = 0; i < disks.size(); i++) {
            JsonElement element = disks.get(i);
            if (element == null || !element.isJsonObject()) {
                placement.addBlockingReason(DrPlanReadinessValidator.REASON_DISK_MAPPING_REQUIRED + ":" + i);
                continue;
            }
            JsonObject disk = element.getAsJsonObject();
            JsonObject source = objectAt(disk, "source");
            JsonObject target = objectAt(disk, "target");
            JsonObject diskController = objectAt(disk, "controller");
            JsonObject sourceController = objectAt(source, "controller");
            DrResolvedDiskMapping resolved = new DrResolvedDiskMapping();
            resolved.setLabel(firstString(disk, "label", "name"));
            resolved.setSourceRef(firstString(disk, "sourceRef", "sourceDiskRef", "sourceVolumeId", "sourcevolumeid"));
            resolved.setSourcePath(firstString(disk, "sourcePath", "sourceVmdkPath", "sourceDisk"));
            resolved.setSourceLabel(firstString(source, "label", "name"));
            resolved.setSourceController(firstNonBlank(firstString(disk, "sourceController", "controllerType", "controller"),
                    firstString(source, "sourceController", "controllerType", "controller"),
                    firstString(diskController, "type", "name", "controllerType"),
                    firstString(sourceController, "type", "name", "controllerType")));
            String controllerBusNumber = firstNonBlank(firstString(disk, "controllerBusNumber", "controllerBus", "bus"),
                    firstString(source, "controllerBusNumber", "controllerBus", "bus"));
            String unitNumber = firstNonBlank(firstString(disk, "unitNumber", "unit"),
                    firstString(source, "unitNumber", "unit"));
            String cbtDiskId = firstNonBlank(firstString(disk, "cbtDiskId", "sourceCbtDiskId"),
                    firstString(source, "cbtDiskId", "device"));
            if (StringUtils.isBlank(cbtDiskId)) {
                cbtDiskId = inferVmwareCbtDiskId(controllerBusNumber, unitNumber);
            }
            resolved.setSourceCbtDiskId(cbtDiskId);
            resolved.setDevice(firstNonBlank(cbtDiskId, firstNonBlank(firstString(disk, "device"), firstString(source, "device"))));
            resolved.setSourceDiskKey(firstNonBlank(firstString(disk, "sourceDiskKey", "deviceKey", "key"),
                    firstString(source, "sourceDiskKey", "deviceKey", "key")));
            resolved.setControllerBusNumber(controllerBusNumber);
            resolved.setUnitNumber(unitNumber);
            resolved.setCapacityBytes(firstNonBlank(firstString(disk, "capacityBytes", "sizeBytes", "virtualSize", "bytesTotal"),
                    firstString(source, "capacityBytes", "sizeBytes", "virtualSize", "bytesTotal")));
            if (positiveLong(resolved.getCapacityBytes()) == null) {
                placement.addBlockingReason(DrPlanReadinessValidator.REASON_SOURCE_DISK_SIZE_UNRESOLVED + ":" + i);
            }
            resolved.setBoot(firstBoolean(source, "boot"));
            if (StringUtils.isBlank(resolved.getSourceRef())) {
                resolved.setSourceRef(firstString(source, "diskRef", "ref", "uuid", "id"));
            }
            if (StringUtils.isBlank(resolved.getSourcePath())) {
                resolved.setSourcePath(firstString(source, "path", "vmdkPath"));
            }
            if (StringUtils.isBlank(resolved.getSourceRef()) && StringUtils.isBlank(resolved.getSourcePath())) {
                placement.addBlockingReason(DrPlanReadinessValidator.REASON_DISK_SOURCE_REQUIRED + ":" + i);
            }
            resolved.setTargetRef(firstString(disk, "targetRef", "targetDiskRef", "targetVolumeId", "targetvolumeid"));
            resolved.setTargetName(StringUtils.defaultIfBlank(firstString(target, "name", "diskRef", "ref"), resolved.getTargetRef()));
            if (StringUtils.isBlank(resolved.getTargetRef())) {
                resolved.setTargetRef(firstString(target, "diskRef", "ref", "uuid", "id", "name"));
            }
            if (StringUtils.isBlank(resolved.getTargetName())) {
                placement.addBlockingReason(DrPlanReadinessValidator.REASON_DISK_TARGET_REQUIRED + ":" + i);
            }
            String storageRef = firstNonBlank(firstString(disk, "targetStorageRef", "storageRef"),
                    firstString(target, "storageRef", "storagePoolId", "targetStorageRef"),
                    placement.getStorageRef());
            resolved.setTargetStorageRef(storageRef);
            applyDiskStorage(storageRef, zoneId, resolved, placement, i);
            String diskOfferingRef = firstNonBlank(firstString(disk, "targetDiskOfferingId", "diskOfferingId"),
                    firstString(target, "diskOfferingId", "diskOfferingRef", "offeringId"));
            resolved.setTargetDiskOfferingId(diskOfferingRef);
            applyDiskOffering(diskOfferingRef, resolved, placement, i);
            String requestedTargetType = firstString(target, "type", "targetType");
            if (StringUtils.isNotBlank(requestedTargetType)) {
                resolved.setTargetType(StringUtils.lowerCase(requestedTargetType));
            }
            resolved.setTargetCacheMode(firstNonBlank(firstString(disk, "targetCacheMode", "cacheMode"),
                    firstString(target, "targetCacheMode", "cacheMode")));
            resolved.setTargetFormat(resolveTargetFormat(resolved, firstString(target, "format", "targetFormat")));
            placement.addDisk(resolved);
        }
    }

    private void applyDiskStorage(String storageRef, Long zoneId, DrResolvedDiskMapping resolved, DrResolvedTargetPlacement placement, int index) {
        if (StringUtils.isBlank(storageRef)) {
            placement.addBlockingReason(DrPlanReadinessValidator.REASON_TARGET_STORAGE_REQUIRED + ":" + index);
            return;
        }
        StoragePoolVO pool = findStoragePool(storageRef, zoneId);
        if (pool == null) {
            placement.addBlockingReason(DrPlanReadinessValidator.REASON_TARGET_STORAGE_REQUIRED + ":" + index + ":" + storageRef);
            return;
        }
        resolved.setTargetStorageLocalId(String.valueOf(pool.getId()));
        resolved.setStoragePath(pool.getPath());
        resolved.setStoragePoolType(pool.getPoolType() != null ? pool.getPoolType().toString() : null);
        resolved.setStorageHostAddress(pool.getHostAddress());
        resolved.setKrbdPath(pool.getKrbdPath());
        resolved.setTargetType(isRbdStorage(pool) ? "rbd" : "file");
    }

    private void applyDiskOffering(String diskOfferingRef, DrResolvedDiskMapping resolved, DrResolvedTargetPlacement placement, int index) {
        if (StringUtils.isBlank(diskOfferingRef)) {
            placement.addBlockingReason(DrPlanReadinessValidator.REASON_TARGET_DISK_OFFERING_REQUIRED + ":" + index);
            return;
        }
        DiskOfferingVO offering = findDiskOffering(diskOfferingRef);
        if (offering == null) {
            placement.addBlockingReason(DrPlanReadinessValidator.REASON_TARGET_DISK_OFFERING_REQUIRED + ":" + index + ":" + diskOfferingRef);
            return;
        }
        resolved.setTargetDiskOfferingLocalId(String.valueOf(offering.getId()));
    }

    private StoragePoolVO findStoragePool(String ref, Long zoneId) {
        String storageRef = StringUtils.trimToNull(ref);
        if (storageRef == null || primaryDataStoreDao == null) {
            return null;
        }
        Long numeric = parseLong(storageRef);
        if (numeric != null) {
            StoragePoolVO pool = primaryDataStoreDao.findById(numeric);
            if (isUsablePool(pool) && (zoneId == null || pool.getDataCenterId() == zoneId.longValue())) {
                return pool;
            }
        }
        if (zoneId == null) {
            return null;
        }
        List<StoragePoolVO> pools = primaryDataStoreDao.listByDataCenterId(zoneId);
        if (pools == null) {
            return null;
        }
        for (StoragePoolVO pool : pools) {
            if (isUsablePool(pool) && matchesRef(storageRef, pool.getId(), pool.getUuid())) {
                return pool;
            }
        }
        return null;
    }

    private boolean isUsablePool(StoragePoolVO pool) {
        return pool != null && pool.getRemoved() == null && pool.getStatus() == StoragePoolStatus.Up;
    }

    private ServiceOfferingVO findServiceOffering(String ref) {
        String offeringRef = StringUtils.trimToNull(ref);
        if (offeringRef == null || serviceOfferingDao == null) {
            return null;
        }
        List<ServiceOfferingVO> offerings = serviceOfferingDao.listAll();
        if (offerings == null) {
            return null;
        }
        for (ServiceOfferingVO offering : offerings) {
            if (offering != null && offering.getRemoved() == null && !offering.isSystemUse()
                    && StringUtils.equalsIgnoreCase(String.valueOf(offering.getState()), "Active")
                    && matchesRef(offeringRef, offering.getId(), offering.getUuid())) {
                return offering;
            }
        }
        return null;
    }

    private DiskOfferingVO findDiskOffering(String ref) {
        String offeringRef = StringUtils.trimToNull(ref);
        if (offeringRef == null || diskOfferingDao == null) {
            return null;
        }
        List<DiskOfferingVO> offerings = diskOfferingDao.listAllActiveAndNonComputeDiskOfferings();
        if (offerings == null) {
            return null;
        }
        for (DiskOfferingVO offering : offerings) {
            if (offering != null && offering.getRemoved() == null && !offering.isComputeOnly()
                    && StringUtils.equalsIgnoreCase(String.valueOf(offering.getState()), "Active")
                    && matchesRef(offeringRef, offering.getId(), offering.getUuid())) {
                return offering;
            }
        }
        return null;
    }

    private NetworkVO findNetwork(String ref, Long zoneId) {
        String networkRef = StringUtils.trimToNull(ref);
        if (networkRef == null || networkDao == null || zoneId == null) {
            return null;
        }
        List<NetworkVO> networks = networkDao.listByZone(zoneId);
        if (networks == null) {
            return null;
        }
        for (NetworkVO network : networks) {
            if (network == null || network.getRemoved() != null || network.getTrafficType() != TrafficType.Guest
                    || network.getState() == Network.State.Destroy || network.getState() == Network.State.Shutdown) {
                continue;
            }
            if (matchesRef(networkRef, network.getId(), network.getUuid())) {
                return network;
            }
        }
        return null;
    }

    private void applyStorage(StoragePoolVO pool, String selectedRef, DrResolvedTargetPlacement placement) {
        placement.setStorageRef(StringUtils.defaultIfBlank(selectedRef, pool.getUuid()));
        placement.setStorageLocalId(String.valueOf(pool.getId()));
        placement.setStoragePath(pool.getPath());
        placement.setStoragePoolType(pool.getPoolType() != null ? pool.getPoolType().toString() : null);
        placement.setStorageHostAddress(pool.getHostAddress());
        placement.setKrbdPath(pool.getKrbdPath());
    }

    private String resolveTargetFormat(DrResolvedDiskMapping resolved, String requestedFormat) {
        if (StringUtils.equalsIgnoreCase(resolved.getTargetType(), "rbd")
                || StringUtils.containsIgnoreCase(resolved.getStoragePoolType(), "rbd")) {
            return "raw";
        }
        return StringUtils.defaultIfBlank(StringUtils.lowerCase(requestedFormat), "qcow2");
    }

    private String inferVmwareCbtDiskId(String controllerBusNumber, String unitNumber) {
        Long bus = parseLong(controllerBusNumber);
        Long unit = parseLong(unitNumber);
        if (bus == null || unit == null || bus < 0 || unit < 0) {
            return null;
        }
        return "scsi" + bus + ":" + unit;
    }

    private boolean isRbdStorage(StoragePoolVO pool) {
        return pool != null && StringUtils.containsIgnoreCase(pool.getPoolType() != null ? pool.getPoolType().toString() : null, "rbd");
    }

    private JsonArray parseDiskMappings(String diskMappingsJson) {
        JsonArray result = new JsonArray();
        if (StringUtils.isBlank(diskMappingsJson)) {
            return result;
        }
        try {
            JsonElement parsed = JsonParser.parseString(diskMappingsJson);
            if (parsed != null && parsed.isJsonArray()) {
                return parsed.getAsJsonArray();
            }
            if (parsed != null && parsed.isJsonObject()) {
                JsonObject object = parsed.getAsJsonObject();
                for (String key : new String[] {"disks", "diskMappings", "volumes", "volumeMappings"}) {
                    JsonElement element = object.get(key);
                    if (element != null && element.isJsonArray()) {
                        return element.getAsJsonArray();
                    }
                }
            }
        } catch (RuntimeException ignored) {
        }
        return result;
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

    private Boolean firstBoolean(JsonObject object, String... keys) {
        if (object == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            JsonElement element = object.get(key);
            if (element != null && !element.isJsonNull() && element.isJsonPrimitive()) {
                try {
                    return element.getAsBoolean();
                } catch (RuntimeException ignored) {
                }
            }
        }
        return null;
    }

    private String firstNonBlank(String first, String second, String third) {
        if (StringUtils.isNotBlank(first)) {
            return first;
        }
        if (StringUtils.isNotBlank(second)) {
            return second;
        }
        return StringUtils.trimToNull(third);
    }

    private String firstNonBlank(String first, String second) {
        return StringUtils.isNotBlank(first) ? first : StringUtils.trimToNull(second);
    }

    private String firstNonBlank(String first, String second, String third, String... rest) {
        String value = firstNonBlank(first, second, third);
        if (StringUtils.isNotBlank(value)) {
            return value;
        }
        if (rest == null) {
            return null;
        }
        for (String candidate : rest) {
            if (StringUtils.isNotBlank(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private boolean matchesRef(String ref, long id, String uuid) {
        return StringUtils.equals(ref, String.valueOf(id)) || StringUtils.equals(ref, uuid);
    }

    private Long parseLong(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        try {
            return Long.valueOf(StringUtils.trim(value));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private Long positiveLong(String value) {
        Long parsed = parseLong(value);
        return parsed != null && parsed > 0 ? parsed : null;
    }

    private Integer positiveInteger(Integer value) {
        return value != null && value > 0 ? value : null;
    }

    private Integer positiveInteger(String value) {
        Long parsed = positiveLong(value);
        return parsed != null && parsed <= Integer.MAX_VALUE ? parsed.intValue() : null;
    }

    private Integer firstNonNull(Integer first, Integer second) {
        return first != null ? first : second;
    }
}
