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

import org.apache.commons.lang3.StringUtils;

import com.cloud.dr.dao.DrReplicaDao;
import com.cloud.dr.dao.DrReplicaDiskDao;
import com.cloud.dr.dao.DrRestorePointDao;
import com.cloud.dr.dao.DrRunDao;
import com.cloud.dr.dao.DrSiteDao;
import com.cloud.host.DetailVO;
import com.cloud.host.Host;
import com.cloud.host.dao.HostDao;
import com.cloud.host.dao.HostDetailsDao;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.component.ManagerBase;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class DrPlanReadinessValidator extends ManagerBase {
    public static final String REASON_PLAN_REQUIRED = "PLAN_REQUIRED";
    public static final String REASON_COORDINATOR_WORKER_REQUIRED = "COORDINATOR_WORKER_REQUIRED";
    public static final String REASON_WORKER_HOST_NOT_FOUND = "WORKER_HOST_NOT_FOUND";
    public static final String REASON_DISK_MAPPING_REQUIRED = "DISK_MAPPING_REQUIRED";
    public static final String REASON_DISK_SOURCE_REQUIRED = "DISK_SOURCE_REQUIRED";
    public static final String REASON_DISK_TARGET_REQUIRED = "DISK_TARGET_REQUIRED";
    public static final String REASON_TARGET_SITE_ZONE_REQUIRED = "TARGET_SITE_ZONE_REQUIRED";
    public static final String REASON_TARGET_WORKER_REQUIRED = "TARGET_WORKER_REQUIRED";
    public static final String REASON_TARGET_STORAGE_REQUIRED = "TARGET_STORAGE_REQUIRED";
    public static final String REASON_TARGET_SERVICE_OFFERING_REQUIRED = "TARGET_SERVICE_OFFERING_REQUIRED";
    public static final String REASON_TARGET_COMPUTE_SIZE_REQUIRED = "TARGET_COMPUTE_SIZE_REQUIRED";
    public static final String REASON_TARGET_COMPUTE_SIZE_INVALID = "TARGET_COMPUTE_SIZE_INVALID";
    public static final String REASON_TARGET_NETWORK_REQUIRED = "TARGET_NETWORK_REQUIRED";
    public static final String REASON_TARGET_DISK_OFFERING_REQUIRED = "TARGET_DISK_OFFERING_REQUIRED";
    public static final String REASON_SOURCE_DISK_SIZE_UNRESOLVED = "SOURCE_DISK_SIZE_UNRESOLVED";
    public static final String REASON_SOURCE_HARDWARE_INVENTORY_REQUIRED = "SOURCE_HARDWARE_INVENTORY_REQUIRED";
    public static final String REASON_SOURCE_FIRMWARE_UNRESOLVED = "SOURCE_FIRMWARE_UNRESOLVED";
    public static final String REASON_SOURCE_SECURE_BOOT_UNRESOLVED = "SOURCE_SECURE_BOOT_UNRESOLVED";
    public static final String REASON_SOURCE_HARDWARE_CHANGED = "SOURCE_HARDWARE_CHANGED";
    public static final String REASON_TARGET_DISK_FORMAT_INVALID = "TARGET_DISK_FORMAT_INVALID";
    public static final String REASON_TARGET_VOLUME_FORMAT_MISMATCH = "DR_TARGET_VOLUME_FORMAT_MISMATCH";
    public static final String REASON_TARGET_BOOT_MODE_UNSUPPORTED = "TARGET_BOOT_MODE_UNSUPPORTED";
    public static final String REASON_TARGET_DISK_CONTROLLER_UNSUPPORTED = "TARGET_DISK_CONTROLLER_UNSUPPORTED";
    public static final String REASON_TARGET_MIXED_DATA_CONTROLLER_UNSUPPORTED = "TARGET_MIXED_DATA_CONTROLLER_UNSUPPORTED";
    public static final String REASON_TARGET_IO_POLICY_UNSUPPORTED = "TARGET_IO_POLICY_UNSUPPORTED";
    public static final String REASON_RELEASE_RESOURCE_REQUIRED = "RELEASE_RESOURCE_REQUIRED";
    public static final String REASON_VDDK_LIBDIR_UNRESOLVED = DrConstants.ERROR_VDDK_LIBDIR_UNRESOLVED;
    public static final String REASON_VDDK_LIBRARY_LOAD_FAILED = DrConstants.ERROR_VDDK_LIBRARY_LOAD_FAILED;
    public static final String REASON_CURRENT_CYCLE_FAILED = "DR_CURRENT_CYCLE_FAILED";
    public static final String REASON_SCHEDULER_NOT_RUNNING = "DR_SCHEDULER_NOT_RUNNING";
    public static final String REASON_RPO_OVERDUE = "DR_RPO_OVERDUE";
    public static final String REASON_RESEED_IN_PROGRESS = "DR_CBT_RESEED_IN_PROGRESS";
    public static final String REASON_TARGET_OWNERSHIP_CONFLICT = DrConstants.ERROR_TARGET_OWNERSHIP_CONFLICT;

    @Inject
    private HostDao hostDao;
    @Inject
    private DrWorkerPlacementService drWorkerPlacementService;
    @Inject
    private HostDetailsDao hostDetailsDao;
    @Inject
    private DrSiteDao drSiteDao;
    @Inject
    private DrReplicaDao drReplicaDao;
    @Inject
    private DrReplicaDiskDao drReplicaDiskDao;
    @Inject
    private DrRestorePointDao drRestorePointDao;
    @Inject
    private DrRunDao drRunDao;
    @Inject
    private DrPlanTargetPlacementResolver drPlanTargetPlacementResolver;
    @Inject
    private VolumeDao volumeDao;
    @Inject
    private DrProtectionAuthorityService drProtectionAuthorityService;

    public DrPlanReadiness validate(DrPlanVO plan) {
        DrPlanReadiness readiness = validateForExecution(plan);
        DrPlanReadiness release = validateForRelease(plan);
        readiness.setReleaseReady(release.isReleaseReady());
        readiness.getWarnings().addAll(release.getWarnings());
        applyTargetReadiness(plan, readiness);
        applyProtectionAuthority(plan, readiness);
        if (readiness.isTargetMaterialized()) {
            readiness.setState(DrPlanReadiness.STATE_TARGET_READY);
        } else if (StringUtils.equalsAny(plan != null ? plan.getState() : null, DrConstants.PLAN_STATE_SYNCING, DrConstants.PLAN_STATE_READY)) {
            readiness.setState(DrPlanReadiness.STATE_TARGET_MATERIALIZING);
        } else if (release.isReleaseReady() && readiness.isExecutionReady()) {
            readiness.setState(DrPlanReadiness.STATE_RUNTIME_ACTIVE);
        }
        return readiness;
    }

    private void applyProtectionAuthority(DrPlanVO plan, DrPlanReadiness readiness) {
        if (plan == null || drProtectionAuthorityService == null || !isFtctlDrPlan(plan)) {
            return;
        }
        DrProtectionAuthoritySnapshot authority = drProtectionAuthorityService.getAuthority(plan.getId());
        if (authority == null || authority.getRuntime() == null) {
            return;
        }
        if (StringUtils.equalsAnyIgnoreCase(authority.getCurrentCycleState(), "ERROR", "FAILED")
                || StringUtils.equalsIgnoreCase(authority.getProtectionState(), DrConstants.PLAN_STATE_ERROR)) {
            readiness.addBlockingReason(REASON_CURRENT_CYCLE_FAILED);
        }
        if (!authority.isSchedulerPidAlive()) {
            readiness.addBlockingReason(REASON_SCHEDULER_NOT_RUNNING);
        }
        if (authority.isRpoOverdue()) {
            readiness.addBlockingReason(REASON_RPO_OVERDUE);
        }
        if (StringUtils.equalsIgnoreCase(authority.getProtectionState(), "RESEEDING")) {
            readiness.addBlockingReason(REASON_RESEED_IN_PROGRESS);
        }
        if (!authority.isNormalCutoverReady()) {
            readiness.setExecutionReady(false);
        }
    }

    public DrPlanReadiness validateTargetReadiness(DrPlanVO plan) {
        DrPlanReadiness readiness = new DrPlanReadiness();
        if (plan == null) {
            readiness.addBlockingReason(REASON_PLAN_REQUIRED);
            readiness.setReasonCode(REASON_PLAN_REQUIRED);
            return readiness;
        }
        applyTargetReadiness(plan, readiness);
        applyProtectionAuthority(plan, readiness);
        readiness.setState(readiness.isTargetMaterialized() ? DrPlanReadiness.STATE_TARGET_READY : DrPlanReadiness.STATE_TARGET_MATERIALIZING);
        return readiness;
    }

    public DrPlanReadiness validateForExecution(DrPlanVO plan) {
        DrPlanReadiness readiness = new DrPlanReadiness();
        if (plan == null) {
            readiness.addBlockingReason(REASON_PLAN_REQUIRED);
            return readiness;
        }
        if (!isFtctlDrPlan(plan)) {
            readiness.setExecutionReady(true);
            readiness.setState(DrPlanReadiness.STATE_EXECUTION_READY);
            return readiness;
        }
        JsonObject mapping = parseObject(plan.getMappingJson());
        validateSourceHardware(plan, mapping, readiness);
        validateWorkers(plan, readiness);
        validateVmwareDataPlane(plan, readiness);
        if (StringUtils.endsWithIgnoreCase(plan.getDirection(), "_KVM") && drPlanTargetPlacementResolver != null) {
            DrResolvedTargetPlacement placement = drPlanTargetPlacementResolver.resolve(plan, buildGuidedSpecFromMapping(mapping));
            if (placement != null) {
                for (String reason : placement.getBlockingReasons()) {
                    readiness.addBlockingReason(reason);
                }
                for (String warning : placement.getWarnings()) {
                    readiness.addWarning(warning);
                }
            }
        } else {
            validateTargetKvmPlacement(plan, mapping, readiness);
            validateDiskMappings(plan, mapping, readiness);
        }
        validateDiskSizeReadiness(plan, mapping, readiness);
        if (readiness.getBlockingReasons().isEmpty()) {
            readiness.setExecutionReady(true);
            readiness.setState(DrPlanReadiness.STATE_EXECUTION_READY);
        }
        return readiness;
    }

    private void validateSourceHardware(DrPlanVO plan, JsonObject mapping, DrPlanReadiness readiness) {
        if (plan == null || !StringUtils.startsWithIgnoreCase(plan.getDirection(), "VMWARE_")) {
            return;
        }
        JsonObject source = objectAt(mapping, "source");
        JsonObject hardware = objectAt(source, "hardware");
        if (hardware.entrySet().isEmpty()) {
            readiness.addBlockingReason(REASON_SOURCE_HARDWARE_INVENTORY_REQUIRED);
            return;
        }
        if (StringUtils.isBlank(firstString(hardware, "firmware", "bootType", "boottype"))) {
            readiness.addBlockingReason(REASON_SOURCE_FIRMWARE_UNRESOLVED);
        }
        if (firstBoolean(hardware, "secureBoot", "secure_boot", "secure") == null) {
            readiness.addBlockingReason(REASON_SOURCE_SECURE_BOOT_UNRESOLVED);
        }
    }

    public DrPlanReadiness validateForRelease(DrPlanVO plan) {
        DrPlanReadiness readiness = new DrPlanReadiness();
        if (plan == null) {
            readiness.addBlockingReason(REASON_PLAN_REQUIRED);
            return readiness;
        }
        if (hasRuntimeResources(plan) || isProtectedPlanState(plan)) {
            readiness.setReleaseReady(true);
            readiness.setState(DrPlanReadiness.STATE_RELEASE_READY);
            return readiness;
        }
        readiness.addBlockingReason(REASON_RELEASE_RESOURCE_REQUIRED);
        return readiness;
    }

    private void validateWorkers(DrPlanVO plan, DrPlanReadiness readiness) {
        Long coordinator = drWorkerPlacementService != null
                ? drWorkerPlacementService.resolveWorkerHostId(plan, DrWorkerRole.COORDINATOR) : null;
        if (coordinator == null) {
            readiness.addBlockingReason(REASON_COORDINATOR_WORKER_REQUIRED);
            return;
        }
        validateHost(coordinator, "coordinator", readiness);
    }

    private void validateHost(Long hostId, String role, DrPlanReadiness readiness) {
        if (hostId == null || hostDao == null) {
            return;
        }
        if (hostDao.findById(hostId) == null) {
            readiness.addBlockingReason(REASON_WORKER_HOST_NOT_FOUND + ":" + role + ":" + hostId);
        }
    }

    private void validateVmwareDataPlane(DrPlanVO plan, DrPlanReadiness readiness) {
        if (plan == null || !StringUtils.startsWithIgnoreCase(plan.getDirection(), "VMWARE_")) {
            return;
        }
        Long dataPlaneHostId = resolveVmwareDataPlaneHostId(plan);
        if (dataPlaneHostId == null) {
            addVmwareDataPlaneBlocker(readiness, REASON_VDDK_LIBDIR_UNRESOLVED,
                    "VMware source DR requires a KVM data-plane worker with a usable VDDK library directory");
            return;
        }
        String support = hostDetailValue(dataPlaneHostId, Host.HOST_VDDK_SUPPORT);
        String libDir = hostDetailValue(dataPlaneHostId, Host.HOST_VDDK_LIB_DIR);
        if (StringUtils.isBlank(libDir)) {
            addVmwareDataPlaneBlocker(readiness, REASON_VDDK_LIBDIR_UNRESOLVED,
                    "The selected data-plane worker has no detected VDDK library directory");
            return;
        }
        if (StringUtils.isNotBlank(support) && !Boolean.parseBoolean(support)) {
            addVmwareDataPlaneBlocker(readiness, REASON_VDDK_LIBRARY_LOAD_FAILED,
                    "The selected data-plane worker cannot load the VDDK library with nbdkit");
        }
    }

    private void addVmwareDataPlaneBlocker(DrPlanReadiness readiness, String reason, String message) {
        readiness.addBlockingReason(reason);
        readiness.setReasonCode(reason);
        readiness.setMessage(message);
    }

    private Long resolveVmwareDataPlaneHostId(DrPlanVO plan) {
        return plan != null && drWorkerPlacementService != null
                ? drWorkerPlacementService.resolveWorkerHostId(plan, DrWorkerRole.VDDK_DATA_PLANE) : null;
    }

    private String hostDetailValue(Long hostId, String name) {
        if (hostId == null || StringUtils.isBlank(name) || hostDetailsDao == null) {
            return null;
        }
        DetailVO detail = hostDetailsDao.findDetail(hostId, name);
        return detail != null ? StringUtils.trimToNull(detail.getValue()) : null;
    }

    private void validateTargetKvmPlacement(DrPlanVO plan, JsonObject mapping, DrPlanReadiness readiness) {
        if (plan == null || !StringUtils.endsWithIgnoreCase(plan.getDirection(), "_KVM")) {
            return;
        }
        DrSiteVO targetSite = drSiteDao != null ? drSiteDao.findById(plan.getTargetSiteId()) : null;
        JsonObject target = objectAt(mapping, "target");
        if ((targetSite == null || targetSite.getZoneId() == null)
                && StringUtils.isBlank(firstString(mapping, "targetZoneId"))
                && StringUtils.isBlank(firstString(target, "zoneId", "zone", "targetZoneId"))) {
            readiness.addBlockingReason(REASON_TARGET_SITE_ZONE_REQUIRED);
        }
        if (drWorkerPlacementService == null
                || drWorkerPlacementService.resolveWorkerHostId(plan, DrWorkerRole.TARGET) == null) {
            readiness.addBlockingReason(REASON_TARGET_WORKER_REQUIRED);
        }
        JsonArray disks = firstArray(mapping, "disks", "diskMappings", "volumes", "volumeMappings");
        if (StringUtils.isBlank(firstString(mapping, "targetStorageRef", "targetDatastoreRef"))
                && StringUtils.isBlank(firstString(target, "storageRef", "storagePoolId", "targetStorageRef"))
                && !diskMappingsProvideTargetStorage(disks)) {
            readiness.addBlockingReason(REASON_TARGET_STORAGE_REQUIRED);
        }
        if (StringUtils.isBlank(firstString(mapping, "targetComputeRef", "serviceOfferingId"))
                && StringUtils.isBlank(firstString(target, "serviceOfferingId", "serviceOfferingRef", "computeOfferingId"))) {
            readiness.addBlockingReason(REASON_TARGET_SERVICE_OFFERING_REQUIRED);
        }
        JsonArray networks = firstArray(target, "networks", "networkRefs", "networkMappings");
        if (StringUtils.isBlank(firstString(mapping, "targetNetworkRef", "networkRef")) && networks.size() == 0) {
            readiness.addBlockingReason(REASON_TARGET_NETWORK_REQUIRED);
        }
    }

    private void validateDiskMappings(DrPlanVO plan, JsonObject mapping, DrPlanReadiness readiness) {
        JsonArray disks = firstArray(mapping, "disks", "diskMappings", "volumes", "volumeMappings");
        if (disks.size() == 0) {
            readiness.addBlockingReason(REASON_DISK_MAPPING_REQUIRED);
            return;
        }
        for (int i = 0; i < disks.size(); i++) {
            JsonElement element = disks.get(i);
            if (element == null || !element.isJsonObject()) {
                readiness.addBlockingReason(REASON_DISK_MAPPING_REQUIRED + ":" + i);
                continue;
            }
            JsonObject disk = element.getAsJsonObject();
            JsonObject source = objectAt(disk, "source");
            JsonObject target = objectAt(disk, "target");
            if (StringUtils.isBlank(firstString(disk, "sourcePath", "sourceDiskRef", "sourceVmdkPath", "sourceDisk", "source"))
                    && StringUtils.isBlank(firstString(source, "path", "diskRef", "vmdkPath", "ref", "uuid"))
                    && firstLong(disk, "sourceVolumeId", "sourcevolumeid") == null) {
                readiness.addBlockingReason(REASON_DISK_SOURCE_REQUIRED + ":" + i);
            }
            if (StringUtils.isBlank(firstString(disk, "targetPath", "targetDiskRef", "targetVmdkPath", "targetDisk", "targetRef", "destination", "dest", "target"))
                    && StringUtils.isBlank(firstString(target, "path", "diskRef", "vmdkPath", "ref", "uuid", "name"))
                    && firstLong(disk, "targetVolumeId", "targetvolumeid") == null) {
                readiness.addBlockingReason(REASON_DISK_TARGET_REQUIRED + ":" + i);
            }
            if (StringUtils.endsWithIgnoreCase(plan.getDirection(), "_KVM")
                    && StringUtils.isBlank(firstString(disk, "targetStorageRef", "storageRef"))
                    && StringUtils.isBlank(firstString(target, "storageRef", "storagePoolId", "targetStorageRef"))) {
                readiness.addBlockingReason(REASON_TARGET_STORAGE_REQUIRED + ":" + i);
            }
            if (StringUtils.endsWithIgnoreCase(plan.getDirection(), "_KVM")
                    && StringUtils.isBlank(firstString(disk, "targetDiskOfferingId", "diskOfferingId"))
                    && StringUtils.isBlank(firstString(target, "diskOfferingId", "diskOfferingRef", "offeringId"))) {
                readiness.addBlockingReason(REASON_TARGET_DISK_OFFERING_REQUIRED + ":" + i);
            }
        }
    }

    private boolean diskMappingsProvideTargetStorage(JsonArray disks) {
        if (disks == null || disks.size() == 0) {
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

    private void validateDiskSizeReadiness(DrPlanVO plan, JsonObject mapping, DrPlanReadiness readiness) {
        if (plan == null || !StringUtils.equalsIgnoreCase(DrConstants.DIRECTION_VMWARE_TO_KVM, plan.getDirection())) {
            return;
        }
        JsonArray disks = firstArray(mapping, "disks", "diskMappings", "volumes", "volumeMappings");
        for (int i = 0; i < disks.size(); i++) {
            JsonElement element = disks.get(i);
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject disk = element.getAsJsonObject();
            JsonObject source = objectAt(disk, "source");
            Long sizeBytes = firstPositiveLong(disk, "sizeBytes", "capacityBytes", "virtualSize", "bytesTotal");
            if (sizeBytes == null) {
                sizeBytes = firstPositiveLong(source, "sizeBytes", "capacityBytes", "virtualSize", "bytesTotal");
            }
            if (sizeBytes == null) {
                readiness.addBlockingReason(REASON_SOURCE_DISK_SIZE_UNRESOLVED + ":" + i);
            }
        }
    }

    private boolean hasRuntimeResources(DrPlanVO plan) {
        if (plan.getTargetReadyAt() != null) {
            return true;
        }
        return plan.getId() > 0
                && ((drReplicaDao != null && !empty(drReplicaDao.listActiveByPlanId(plan.getId())))
                || (drRestorePointDao != null && !empty(drRestorePointDao.listActiveByPlanId(plan.getId()))));
    }

    private void applyTargetReadiness(DrPlanVO plan, DrPlanReadiness readiness) {
        if (plan == null || !isFtctlDrPlan(plan)) {
            return;
        }
        List<DrReplicaVO> replicas = drReplicaDao != null ? drReplicaDao.listActiveByPlanId(plan.getId()) : null;
        DrReplicaVO replica = replicas != null && !replicas.isEmpty() ? replicas.get(0) : null;
        DrRestorePointVO restorePoint = drRestorePointDao != null ? drRestorePointDao.findLatestTargetReadyByPlanId(plan.getId()) : null;
        boolean ownershipValid = replica != null && StringUtils.equalsIgnoreCase(replica.getOwnershipState(), "VALID");
        if (replica != null && StringUtils.equalsIgnoreCase(replica.getOwnershipState(), "QUARANTINED")) {
            readiness.addBlockingReason(REASON_TARGET_OWNERSHIP_CONFLICT);
            readiness.setExecutionReady(false);
            readiness.setReasonCode(REASON_TARGET_OWNERSHIP_CONFLICT);
            readiness.setMessage("DR target resource ownership conflicts with another plan");
        }
        boolean targetVmPresent = hasTargetReference(plan, replica);
        boolean targetStoragePresent = restorePoint != null
                || plan.getLastTargetDurableAt() != null
                || hasMaterializedReplicaDisks(plan, replica);
        boolean replicaFormatsVerified = verifyReplicaDiskFormats(plan, replica, readiness);
        if (!replicaFormatsVerified) {
            targetStoragePresent = false;
        }
        boolean restorePointPresent = restorePoint != null;
        boolean durableCheckpointPresent = plan.getLastTargetDurableAt() != null
                || (restorePoint != null && restorePoint.getTargetReadyAt() != null);
        boolean targetNetworkPresent = targetVmPresent;
        boolean manifestConverged = replica != null && StringUtils.isNotBlank(replica.getMaterializationDigest());
        boolean targetMaterialized = ownershipValid && manifestConverged && targetVmPresent && targetStoragePresent
                && restorePointPresent && durableCheckpointPresent;
        readiness.setTargetVmPresent(targetVmPresent);
        readiness.setTargetStoragePresent(targetStoragePresent);
        readiness.setTargetNetworkPresent(targetNetworkPresent);
        readiness.setRestorePointPresent(restorePointPresent);
        readiness.setDurableCheckpointPresent(durableCheckpointPresent);
        readiness.setTargetMaterialized(targetMaterialized);
        readiness.setEngineAccepted(isProtectedPlanState(plan) || replica != null);
        if (targetMaterialized) {
            readiness.setReasonCode(null);
            readiness.setMessage("DR target is materialized and has a durable restore point");
            return;
        }
        DrRunVO latestRun = drRunDao != null ? drRunDao.findLatestByPlanId(plan.getId()) : null;
        if (latestRun != null && StringUtils.equals(latestRun.getState(), DrConstants.RUN_STATE_FAILED)
                && StringUtils.isNotBlank(latestRun.getErrorCode())) {
            readiness.addWarning(latestRun.getErrorCode());
            readiness.setReasonCode(latestRun.getErrorCode());
            readiness.setMessage(StringUtils.defaultIfBlank(latestRun.getErrorMessage(), "DR runtime worker did not complete target materialization"));
            readiness.setState(DrPlanReadiness.STATE_DEGRADED);
            return;
        }
        if (!targetVmPresent) {
            readiness.addWarning(DrConstants.ERROR_TARGET_VM_NOT_FOUND);
            readiness.setReasonCode(DrConstants.ERROR_TARGET_VM_NOT_FOUND);
            readiness.setMessage("DR target VM is not materialized yet");
        } else if (!targetStoragePresent) {
            readiness.addWarning(DrConstants.ERROR_TARGET_STORAGE_NOT_FOUND);
            readiness.setReasonCode(DrConstants.ERROR_TARGET_STORAGE_NOT_FOUND);
            readiness.setMessage("DR target storage is not materialized yet");
        } else if (!restorePointPresent) {
            readiness.addWarning(DrConstants.ERROR_RESTORE_POINT_NOT_FOUND);
            readiness.setReasonCode(DrConstants.ERROR_RESTORE_POINT_NOT_FOUND);
            readiness.setMessage("DR restore point is not available yet");
        } else if (!durableCheckpointPresent) {
            readiness.addWarning(DrConstants.ERROR_DURABLE_CHECKPOINT_NOT_FOUND);
            readiness.setReasonCode(DrConstants.ERROR_DURABLE_CHECKPOINT_NOT_FOUND);
            readiness.setMessage("DR durable checkpoint is not available yet");
        }
    }

    private boolean hasTargetReference(DrPlanVO plan, DrReplicaVO replica) {
        if (replica == null) {
            return false;
        }
        if (StringUtils.endsWithIgnoreCase(plan.getDirection(), "_KVM")) {
            return replica.getTargetVmId() != null;
        }
        return StringUtils.isNotBlank(replica.getTargetExternalRef()) || replica.getTargetVmId() != null;
    }

    private boolean hasMaterializedReplicaDisks(DrPlanVO plan, DrReplicaVO replica) {
        if (replica == null || drReplicaDiskDao == null) {
            return false;
        }
        List<DrReplicaDiskVO> disks = drReplicaDiskDao.listActiveByReplicaId(replica.getId());
        if (empty(disks)) {
            return false;
        }
        boolean targetAbleStack = StringUtils.endsWithIgnoreCase(plan.getDirection(), "_KVM");
        for (DrReplicaDiskVO disk : disks) {
            if (disk == null || disk.getRemoved() != null) {
                continue;
            }
            if (targetAbleStack && disk.getTargetVolumeId() != null) {
                return true;
            }
            if (!targetAbleStack && StringUtils.isNotBlank(disk.getTargetDiskRef())) {
                return true;
            }
        }
        return false;
    }

    private boolean verifyReplicaDiskFormats(DrPlanVO plan, DrReplicaVO replica, DrPlanReadiness readiness) {
        if (plan == null || replica == null || drReplicaDiskDao == null || volumeDao == null
                || !StringUtils.endsWithIgnoreCase(plan.getDirection(), "_KVM")) {
            return true;
        }
        boolean valid = true;
        for (DrReplicaDiskVO disk : drReplicaDiskDao.listActiveByReplicaId(replica.getId())) {
            if (disk == null || disk.getRemoved() != null || disk.getTargetVolumeId() == null) {
                continue;
            }
            VolumeVO volume = volumeDao.findById(disk.getTargetVolumeId());
            String expected = StringUtils.upperCase(StringUtils.defaultIfBlank(disk.getFormat(), "RAW"));
            String actual = volume != null && volume.getFormat() != null ? volume.getFormat().toString() : "MISSING";
            if (!StringUtils.equals(expected, actual)) {
                valid = false;
                String reason = REASON_TARGET_VOLUME_FORMAT_MISMATCH + ":"
                        + (volume != null ? volume.getUuid() : disk.getTargetVolumeId()) + ":" + expected + ":" + actual;
                readiness.addBlockingReason(reason);
                readiness.setReasonCode(REASON_TARGET_VOLUME_FORMAT_MISMATCH);
                readiness.setMessage("DR target volume format does not match the replica format contract");
            }
        }
        return valid;
    }

    private boolean isProtectedPlanState(DrPlanVO plan) {
        return StringUtils.equalsAny(plan.getState(),
                DrConstants.PLAN_STATE_SYNCING,
                DrConstants.PLAN_STATE_READY,
                DrConstants.PLAN_STATE_TESTING,
                DrConstants.PLAN_STATE_FAILED_OVER,
                DrConstants.PLAN_STATE_PAUSED);
    }

    private boolean isFtctlDrPlan(DrPlanVO plan) {
        return StringUtils.equalsIgnoreCase(DrConstants.ENGINE_TYPE_FTCTL_DR, plan.getEngineType())
                || StringUtils.equalsIgnoreCase(DrConstants.ENGINE_BINDING_TYPE_FTCTL_DR, plan.getEngineBindingType());
    }

    DrPlanGuidedSpec buildGuidedSpecFromMapping(JsonObject mapping) {
        DrPlanGuidedSpec spec = new DrPlanGuidedSpec();
        spec.setGuidedPlan(true);
        JsonObject target = objectAt(mapping, "target");
        spec.setTargetVmName(firstNonBlank(firstString(mapping, "targetVmName"), firstString(target, "vmName", "name")));
        spec.setTargetZoneId(firstNonBlank(firstString(mapping, "targetZoneId"), firstString(target, "zoneId", "zone", "targetZoneId")));
        spec.setTargetStorageRef(firstNonBlank(firstString(mapping, "targetStorageRef", "targetDatastoreRef"),
                firstString(target, "storageRef", "storagePoolId", "targetStorageRef")));
        spec.setTargetComputeRef(firstNonBlank(firstString(mapping, "targetComputeRef", "serviceOfferingId"),
                firstString(target, "serviceOfferingId", "serviceOfferingRef", "computeOfferingId")));
        spec.setTargetCpuNumber(firstInteger(mapping, target, "targetCpuNumber", "cpuNumber"));
        spec.setTargetCpuSpeed(firstInteger(mapping, target, "targetCpuSpeed", "cpuSpeed"));
        spec.setTargetMemory(firstInteger(mapping, target, "targetMemory", "memory"));
        JsonObject targetHardware = objectAt(target, "hardware");
        spec.setTargetBootType(firstNonBlank(firstString(mapping, "targetBootType", "boottype"),
                firstString(targetHardware, "bootType", "boottype")));
        spec.setTargetBootMode(firstNonBlank(firstString(mapping, "targetBootMode", "bootmode"),
                firstString(targetHardware, "bootMode", "bootmode")));
        spec.setTargetRootDiskController(firstNonBlank(firstString(mapping, "targetRootDiskController"),
                firstString(targetHardware, "rootDiskController")));
        spec.setTargetDataDiskController(firstNonBlank(firstString(mapping, "targetDataDiskController"),
                firstString(targetHardware, "dataDiskController")));
        spec.setTargetIoThreadsEnabled(firstBoolean(mapping, "targetIoThreadsEnabled", "iothreadsEnabled"));
        if (spec.getTargetIoThreadsEnabled() == null) {
            spec.setTargetIoThreadsEnabled(firstBoolean(targetHardware, "ioThreadsEnabled", "iothreadsEnabled"));
        }
        spec.setTargetIoPolicy(firstNonBlank(firstString(mapping, "targetIoPolicy", "ioPolicy", "io.policy"),
                firstString(targetHardware, "ioPolicy", "io.policy")));
        String networkRef = firstNonBlank(firstString(mapping, "targetNetworkRef", "networkRef"), networkRefsFromTarget(target));
        spec.setTargetNetworkRef(networkRef);
        spec.setTargetFolderPath(firstNonBlank(firstString(mapping, "targetFolderPath", "folderPath"), firstString(target, "folderPath")));
        JsonArray disks = firstArray(mapping, "disks", "diskMappings", "volumes", "volumeMappings");
        if (disks.size() > 0) {
            spec.setDiskMappingsJson(disks.toString());
        }
        return spec;
    }

    private String networkRefsFromTarget(JsonObject target) {
        JsonArray networks = firstArray(target, "networks", "networkRefs", "networkMappings");
        if (networks.size() == 0) {
            return null;
        }
        StringBuilder refs = new StringBuilder();
        for (int i = 0; i < networks.size(); i++) {
            JsonElement element = networks.get(i);
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject network = element.getAsJsonObject();
            String ref = firstString(network, "networkId", "networkRef", "id", "uuid");
            if (StringUtils.isBlank(ref)) {
                continue;
            }
            if (refs.length() > 0) {
                refs.append(',');
            }
            refs.append(ref);
        }
        return refs.length() > 0 ? refs.toString() : null;
    }

    private boolean empty(List<?> values) {
        return values == null || values.isEmpty();
    }

    private Long firstNonNull(Long first, Long second, Long third) {
        if (first != null) {
            return first;
        }
        if (second != null) {
            return second;
        }
        return third;
    }

    private JsonObject parseObject(String json) {
        if (StringUtils.isBlank(json)) {
            return new JsonObject();
        }
        try {
            JsonElement parsed = JsonParser.parseString(json);
            return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException e) {
            return new JsonObject();
        }
    }

    private JsonArray firstArray(JsonObject object, String... keys) {
        if (object == null || keys == null) {
            return new JsonArray();
        }
        for (String key : keys) {
            JsonElement element = object.get(key);
            if (element != null && element.isJsonArray()) {
                return element.getAsJsonArray();
            }
        }
        return new JsonArray();
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

    private Long firstLong(JsonObject object, String... keys) {
        if (object == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            JsonElement element = object.get(key);
            if (element != null && !element.isJsonNull() && element.isJsonPrimitive()) {
                try {
                    return element.getAsLong();
                } catch (RuntimeException ignored) {
                }
            }
        }
        return null;
    }

    private Long firstPositiveLong(JsonObject object, String... keys) {
        Long value = firstLong(object, keys);
        return value != null && value > 0 ? value : null;
    }

    private Integer firstInteger(JsonObject first, JsonObject second, String firstKey, String secondKey) {
        Integer value = integerAt(first, firstKey);
        return value != null ? value : integerAt(second, secondKey);
    }

    private Integer integerAt(JsonObject object, String key) {
        if (object == null || StringUtils.isBlank(key)) {
            return null;
        }
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return null;
        }
        try {
            return element.getAsInt();
        } catch (RuntimeException ignored) {
            try {
                return Integer.valueOf(StringUtils.trim(element.getAsString()));
            } catch (RuntimeException ignoredAgain) {
                return null;
            }
        }
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
            try {
                return element.getAsBoolean();
            } catch (RuntimeException ignored) {
                String value = StringUtils.trimToNull(element.getAsString());
                if (StringUtils.equalsAnyIgnoreCase(value, "true", "yes", "enabled", "1")) {
                    return true;
                }
                if (StringUtils.equalsAnyIgnoreCase(value, "false", "no", "disabled", "0")) {
                    return false;
                }
            }
        }
        return null;
    }

    private String firstNonBlank(String first, String second) {
        return StringUtils.isNotBlank(first) ? first : StringUtils.trimToNull(second);
    }
}
