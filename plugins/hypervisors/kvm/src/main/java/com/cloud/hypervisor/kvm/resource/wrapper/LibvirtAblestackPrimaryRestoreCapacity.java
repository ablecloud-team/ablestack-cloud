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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.storage.Storage.StoragePoolType;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.script.Script;
import com.google.gson.Gson;
import org.apache.cloudstack.backup.ThirdPartyBackupAdmission.PrimaryCapacity;
import org.apache.cloudstack.backup.ThirdPartyBackupAdmission.PrimaryClaim;
import org.apache.cloudstack.backup.ThirdPartyBackupRestore;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;

/** Saved identities and byte requirements are immutable; available space is queried again for each admission. */
final class LibvirtAblestackPrimaryRestoreCapacity {
    private static class Target {
        String poolUuid;
        StoragePoolType poolType;
        String parent;
        String storageKey;
        long volumeBytes;
    }

    private static class Saved {
        ThirdPartyBackupRestore.Plan plan;
        PrimaryCapacity capacity;
        List<Target> targets = new ArrayList<>();
    }

    private LibvirtAblestackPrimaryRestoreCapacity() { }

    private static PrimaryClaim filesystem(LibvirtComputingResource resource, Path path, boolean prepare) {
        Path script = Path.of(resource.getAbleCvtBackupPath()).getParent().resolve("thirdparty_primary_capacity.py");
        String output = Script.runSimpleBashScriptWithFullResult("python3 " + quote(script.toString()) + " --path " + quote(path.toString())
                + (prepare ? " --prepare-identity" : ""), 30000);
        PrimaryClaim result = new Gson().fromJson(output, PrimaryClaim.class);
        if (result == null || result.storageKey == null || result.availableBytes < 0) {
            throw new CloudRuntimeException("Primary filesystem capacity identity is unconfirmed");
        }
        return result;
    }

    private static PrimaryClaim current(LibvirtComputingResource resource, Target target, int timeout, boolean prepare) {
        if (target.poolType == StoragePoolType.RBD) {
            // Capacity polling must not inherit a multi-hour data restore timeout while admission holds its DB lock.
            int queryTimeout = timeout > 0 ? Math.min(timeout, 30) : 30;
            return LibvirtAblestackRbdRestoreHelper.getCephPoolCapacity(
                    resource.getStoragePoolMgr().getStoragePool(target.poolType, target.poolUuid), queryTimeout);
        }
        return filesystem(resource, Path.of(target.parent), prepare);
    }

    static PrimaryCapacity prepare(LibvirtComputingResource resource, Path job, ThirdPartyBackupRestore.Plan plan,
            List<PrimaryDataStoreTO> pools, List<String> paths, List<Long> sizes) throws java.io.IOException {
        Saved saved = new Saved();
        saved.plan = plan;
        saved.capacity = new PrimaryCapacity();
        saved.capacity.jobId = plan.jobId;
        saved.capacity.stagingStorageKey = filesystem(resource, Path.of(plan.stageRoot), true).storageKey;
        Map<String, PrimaryClaim> claims = new LinkedHashMap<>();
        for (int i = 0; i < paths.size(); i++) {
            Target target = new Target();
            target.poolUuid = pools.get(i).getUuid();
            target.poolType = pools.get(i).getPoolType();
            if (target.poolType != StoragePoolType.RBD) {
                Path destination = Path.of(paths.get(i)).toAbsolutePath().normalize();
                // New VM/volume restores have no destination file yet; only its datastore directory must exist.
                target.parent = (Files.exists(destination, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                        ? destination.toRealPath().getParent() : destination.getParent().toRealPath()).toString();
            }
            target.volumeBytes = sizes.get(i);
            PrimaryClaim actual = current(resource, target, plan.timeout, true);
            target.storageKey = actual.storageKey;
            saved.targets.add(target);
            PrimaryClaim claim = claims.computeIfAbsent(actual.storageKey, key -> actual);
            claim.volumeBytes = Math.addExact(claim.volumeBytes, target.volumeBytes);
            claim.availableBytes = Math.min(claim.availableBytes, actual.availableBytes);
        }
        for (PrimaryClaim claim : claims.values()) {
            // Keep the existing primary restore overhead; staging uses its own configured percentage.
            claim.requiredBytes = Math.addExact(claim.volumeBytes, Math.max(10L * 1024 * 1024 * 1024, claim.volumeBytes / 5));
            saved.capacity.primaryClaims.add(claim);
        }
        Path file = job.resolve("restore-primary-capacity.json");
        if (Files.exists(file)) { throw new CloudRuntimeException("Primary restore capacity plan already exists"); }
        LibvirtAblestackVolumeRestoreHelper.atomic(file, new Gson().toJson(saved));
        return saved.capacity;
    }

    static PrimaryCapacity query(LibvirtComputingResource resource, Path job) throws java.io.IOException {
        Saved saved = new Gson().fromJson(Files.readString(job.resolve("restore-primary-capacity.json")), Saved.class);
        ThirdPartyBackupRestore.Plan actualPlan = new Gson().fromJson(Files.readString(job.resolve("volume-restore-plan.json")),
                ThirdPartyBackupRestore.Plan.class);
        if (saved == null || saved.plan == null || !job.getFileName().toString().equals(saved.plan.jobId)
                || !new Gson().toJson(saved.plan).equals(new Gson().toJson(actualPlan))) {
            throw new CloudRuntimeException("Primary capacity plan differs from the initialized restore plan");
        }
        if (!saved.capacity.stagingStorageKey.equals(filesystem(resource, Path.of(saved.plan.stageRoot), false).storageKey)) {
            throw new CloudRuntimeException("Restore staging filesystem capacity identity changed");
        }
        Map<String, Long> free = new LinkedHashMap<>();
        Map<String, PrimaryClaim> queried = new LinkedHashMap<>();
        for (Target target : saved.targets) {
            String reference = target.poolType == StoragePoolType.RBD ? "rbd:" + target.poolUuid : "file:" + target.parent;
            PrimaryClaim actual = queried.computeIfAbsent(reference, key -> current(resource, target, saved.plan.timeout, false));
            if (!target.storageKey.equals(actual.storageKey)) {
                throw new CloudRuntimeException("Restore primary storage identity changed");
            }
            free.merge(actual.storageKey, actual.availableBytes, Math::min);
        }
        for (PrimaryClaim claim : saved.capacity.primaryClaims) { claim.availableBytes = free.get(claim.storageKey); }
        return saved.capacity;
    }

    static void validate(LibvirtComputingResource resource, Path job) {
        try {
            for (PrimaryClaim claim : query(resource, job).primaryClaims) {
                if (claim.availableBytes < claim.requiredBytes) {
                    throw new CloudRuntimeException("Primary capacity changed after admission: " + claim.storageKey);
                }
            }
        } catch (java.io.IOException e) { throw new CloudRuntimeException("Primary restore capacity cannot be rechecked", e); }
    }

    private static String quote(String value) { return "'" + value.replace("'", "'\"'\"'") + "'"; }
}
