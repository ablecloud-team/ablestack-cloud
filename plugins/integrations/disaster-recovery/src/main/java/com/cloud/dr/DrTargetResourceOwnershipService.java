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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import org.apache.commons.lang3.StringUtils;

import com.cloud.dr.dao.DrReplicaDao;
import com.cloud.dr.dao.DrReplicaDiskDao;
import com.cloud.dr.dao.DrTargetResourceClaimDao;
import com.cloud.storage.VolumeVO;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.dao.VMInstanceDetailsDao;

/**
 * Owns the authoritative reuse decision for DR target resources. Resource
 * names and storage locators are discovery hints only and never ownership.
 */
public class DrTargetResourceOwnershipService {
    @Inject
    private DrTargetResourceClaimDao claimDao;
    @Inject
    private DrReplicaDao replicaDao;
    @Inject
    private DrReplicaDiskDao replicaDiskDao;
    @Inject
    private VMInstanceDetailsDao vmInstanceDetailsDao;

    public DrTargetResourceClaimVO claimVm(DrPlanVO plan, DrReplicaVO replica, DrRunVO run, UserVmVO vm) {
        assertVmOwnedBy(plan, replica, vm);
        return claim(plan, replica, null, run, "VM", vm.getId(), vm.getUuid(), null, "TARGET_VM");
    }

    public DrTargetResourceClaimVO claimVolume(DrPlanVO plan, DrReplicaVO replica, DrReplicaDiskVO disk,
            DrRunVO run, VolumeVO volume, String locator) {
        assertVolumeOwnedBy(plan, replica, disk, volume);
        String locatorHash = sha256(StringUtils.defaultString(locator));
        DrTargetResourceClaimVO claim = claim(plan, replica, disk, run, "VOLUME", volume.getId(),
                volume.getUuid(), locatorHash, "TARGET_DISK:" + disk.getId());
        disk.setTargetClaimId(claim.getId());
        disk.setArtifactUuid(StringUtils.defaultIfBlank(disk.getArtifactUuid(), volume.getUuid()));
        disk.setLocatorHash(locatorHash);
        disk.markUpdated();
        replicaDiskDao.update(disk.getId(), disk);
        return claim;
    }

    public void assertVmOwnedBy(DrPlanVO plan, DrReplicaVO replica, UserVmVO vm) {
        Map<String, String> details = vmInstanceDetailsDao.listDetailsKeyPairs(vm.getId());
        details = details != null ? details : Collections.<String, String>emptyMap();
        String ownerPlanId = details.get("dr.plan.id");
        String ownerPlanUuid = details.get("dr.plan.uuid");
        String ownerReplicaId = details.get("dr.replica.id");
        boolean planMatches = StringUtils.equals(String.valueOf(plan.getId()), ownerPlanId)
                || StringUtils.equals(plan.getUuid(), ownerPlanUuid);
        boolean replicaMatches = StringUtils.isBlank(ownerReplicaId)
                || StringUtils.equals(String.valueOf(replica.getId()), ownerReplicaId);
        if (!planMatches || !replicaMatches) {
            throw ownershipConflict("VM", vm.getId(), ownerPlanId, ownerPlanUuid);
        }
    }

    public void assertVolumeOwnedBy(DrPlanVO plan, DrReplicaVO replica, DrReplicaDiskVO disk, VolumeVO volume) {
        List<DrReplicaDiskVO> history = replicaDiskDao.listByTargetVolumeId(volume.getId());
        if (history != null) {
            for (DrReplicaDiskVO previousDisk : history) {
                if (previousDisk.getId() == disk.getId() || previousDisk.getRemoved() != null) {
                    continue;
                }
                DrReplicaVO previousReplica = replicaDao.findByIdIncludingRemoved(previousDisk.getReplicaId());
                if (previousReplica != null && previousReplica.getPlanId() != plan.getId()) {
                    throw ownershipConflict("VOLUME", volume.getId(), String.valueOf(previousReplica.getPlanId()), null);
                }
            }
        }
        if (volume.getInstanceId() != null && (replica.getTargetVmId() == null
                || !volume.getInstanceId().equals(replica.getTargetVmId()))) {
            Map<String, String> vmDetails = vmInstanceDetailsDao.listDetailsKeyPairs(volume.getInstanceId());
            vmDetails = vmDetails != null ? vmDetails : Collections.<String, String>emptyMap();
            String attachedPlanId = vmDetails.get("dr.plan.id");
            if (!StringUtils.equals(String.valueOf(plan.getId()), attachedPlanId)) {
                throw ownershipConflict("VOLUME", volume.getId(), attachedPlanId, vmDetails.get("dr.plan.uuid"));
            }
        }
    }

    public void releasePlanClaims(long planId) {
        List<DrTargetResourceClaimVO> claims = claimDao.listActiveByPlanId(planId);
        if (claims == null) {
            return;
        }
        for (DrTargetResourceClaimVO claim : claims) {
            if (claim == null || !StringUtils.equals("CLAIMED", claim.getClaimState())) {
                continue;
            }
            claim.release();
            claimDao.update(claim.getId(), claim);
        }
    }

    private DrTargetResourceClaimVO claim(DrPlanVO plan, DrReplicaVO replica, DrReplicaDiskVO disk,
            DrRunVO run, String type, Long resourceId, String resourceUuid, String locatorHash, String role) {
        String resourceKey = type + ":" + resourceId;
        String roleKey = "PLAN:" + plan.getId() + ":REPLICA:" + replica.getId() + ":" + role;
        DrTargetResourceClaimVO existing = claimDao.findActiveByResourceKey(resourceKey);
        if (existing != null) {
            if (existing.getPlanId() == plan.getId() && existing.getReplicaId() == replica.getId()) {
                return existing;
            }
            throw ownershipConflict(type, resourceId, String.valueOf(existing.getPlanId()), null);
        }
        DrTargetResourceClaimVO roleClaim = claimDao.findActiveByRoleKey(roleKey);
        if (roleClaim != null && (!resourceId.equals(roleClaim.getResourceId())
                || !StringUtils.equals(type, roleClaim.getResourceType()))) {
            throw ownershipConflict(type, resourceId, String.valueOf(roleClaim.getPlanId()), null);
        }
        long generation = replica.getOwnershipGeneration() != null ? replica.getOwnershipGeneration() : 1L;
        DrTargetResourceClaimVO created = new DrTargetResourceClaimVO(plan.getId(), replica.getId(),
                disk != null ? disk.getId() : null, run != null ? run.getId() : null, type, resourceId,
                resourceUuid, locatorHash, generation, resourceKey, roleKey);
        try {
            return claimDao.persist(created);
        } catch (RuntimeException e) {
            DrTargetResourceClaimVO winner = claimDao.findActiveByResourceKey(resourceKey);
            if (winner != null && winner.getPlanId() == plan.getId() && winner.getReplicaId() == replica.getId()) {
                return winner;
            }
            throw ownershipConflict(type, resourceId, winner != null ? String.valueOf(winner.getPlanId()) : null, null);
        }
    }

    public static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private CloudRuntimeException ownershipConflict(String type, Long resourceId, String ownerPlanId, String ownerPlanUuid) {
        return new CloudRuntimeException(DrConstants.ERROR_TARGET_OWNERSHIP_CONFLICT + ": resource=" + type + ":"
                + resourceId + " ownerPlanId=" + StringUtils.defaultString(ownerPlanId, "unknown")
                + " ownerPlanUuid=" + StringUtils.defaultString(ownerPlanUuid, "unknown"));
    }
}
