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

import java.util.List;

import com.cloud.storage.Volume;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.Gson;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class ThirdPartyBackupRestoreCapacityTest {
    private static final long GIB = 1024L * 1024 * 1024;

    @Test
    public void admissionUsesTheCapturedPrimaryPercentageForEveryThirdPartyProvider() {
        for (String provider : List.of("ablestack-commvault", "ablestack-netbackup", "ablestack-veeam")) {
            for (String storage : List.of("file:primary", "rbd:primary")) {
                ThirdPartyBackupRestore.Plan plan = plan(provider);
                ThirdPartyBackupAdmission.PrimaryCapacity report = report(storage, 1150 * GIB);
                validate(plan, report, 1200 * GIB);
                report.primaryClaims.get(0).requiredBytes = 1200 * GIB;
                assertThrows(CloudRuntimeException.class, () -> validate(plan, report, 1200 * GIB));
            }
        }
    }

    @Test
    public void sharedFilesystemAddsTheDistinctPrimaryAndStagingReservations() {
        ThirdPartyBackupRestore.Plan plan = plan("ablestack-commvault");
        ThirdPartyBackupAdmission.PrimaryCapacity report = report("staging", 1150 * GIB);
        validate(plan, report, 2350 * GIB);
        assertThrows(CloudRuntimeException.class, () -> validate(plan, report, 2400 * GIB));
    }

    @Test
    public void savedPlansKeepBothPercentagesAndLegacyPlansRetainTwentyPercent() {
        Gson gson = new Gson();
        ThirdPartyBackupRestore.Plan plan = plan("ablestack-veeam");
        String saved = gson.toJson(plan);
        plan.primaryStorageCapacityBufferPercent = 30;
        ThirdPartyBackupRestore.Plan restored = gson.fromJson(saved, ThirdPartyBackupRestore.Plan.class);
        assertEquals(15, restored.getPrimaryStorageCapacityBufferPercent());
        assertEquals(20, restored.bufferPercent);
        restored.primaryStorageCapacityBufferPercent = null;
        validate(restored, report("file:primary", 1200 * GIB), 1200 * GIB);
        assertEquals(20, gson.fromJson("{}", ThirdPartyBackupRestore.Plan.class).getPrimaryStorageCapacityBufferPercent());
        restored.primaryStorageCapacityBufferPercent = 101;
        assertThrows(IllegalArgumentException.class, restored::getPrimaryStorageCapacityBufferPercent);
        long size = 1000 * GIB + 1;
        assertEquals(size + size / 5, BackupRestoreCapacity.requiredBytes(size, null));
    }

    private ThirdPartyBackupRestore.Plan plan(String provider) {
        ThirdPartyBackupRestore.Plan plan = new ThirdPartyBackupRestore.Plan();
        plan.jobId = "restore-job";
        plan.bufferPercent = 20;
        plan.primaryStorageCapacityBufferPercent = 15;
        plan.volumeUuids = List.of("volume-1");
        plan.manifest = ThirdPartyBackupManifest.create(provider, "backup-1", "vm-1", "2026.10.15", "FULL", "host-4",
                "/staging/" + provider + "/vm-1/backup-1", "QCOW2", List.of(new Backup.VolumeInfo("volume-1", "root.qcow2",
                        Volume.Type.ROOT, 1000 * GIB, 0L, null, null, null)), null, null);
        return plan;
    }

    private ThirdPartyBackupAdmission.PrimaryCapacity report(String storage, long required) {
        ThirdPartyBackupAdmission.PrimaryCapacity report = new ThirdPartyBackupAdmission.PrimaryCapacity();
        report.jobId = "restore-job";
        report.stagingStorageKey = "staging";
        ThirdPartyBackupAdmission.PrimaryClaim claim = new ThirdPartyBackupAdmission.PrimaryClaim();
        claim.storageKey = storage;
        claim.volumeBytes = 1000 * GIB;
        claim.requiredBytes = required;
        claim.availableBytes = 1175 * GIB;
        report.primaryClaims.add(claim);
        return report;
    }

    private void validate(ThirdPartyBackupRestore.Plan plan, ThirdPartyBackupAdmission.PrimaryCapacity report, long staged) {
        ThirdPartyBackupAdmission.Entry entry = new ThirdPartyBackupAdmission.Entry();
        entry.jobId = plan.jobId;
        entry.stagingStorageKey = report.stagingStorageKey;
        entry.requiredBytes = staged;
        ReflectionTestUtils.invokeMethod(new ThirdPartyBackupVolumeServiceImpl(), "validatePrimaryClaims", entry, plan, report);
    }
}
