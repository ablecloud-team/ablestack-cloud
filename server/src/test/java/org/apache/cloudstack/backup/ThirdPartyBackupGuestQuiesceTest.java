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
import java.util.Map;

import com.cloud.storage.Volume;
import com.cloud.utils.exception.CloudRuntimeException;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ThirdPartyBackupGuestQuiesceTest {
    private ThirdPartyBackupManifest manifest() {
        return ThirdPartyBackupManifest.create("ablestack-netbackup", "backup-1", "vm-1", "2026.11.20.11.00.00",
                "FULL", "host-1", "/staging/ablestack-netbackup/vm-1/2026.11.20.11.00.00", "QCOW2",
                List.of(new Backup.VolumeInfo("volume-1", "root.qcow2", Volume.Type.ROOT,
                        1073741824L, 0L, null, null, null)), null, null);
    }

    @Test public void verifiedEvidenceSurvivesSerializationAndReplay() {
        ThirdPartyBackupManifest manifest = manifest();
        Map<String, Object> value = Map.of("requested", true, "status", "VERIFIED");
        assertTrue(ThirdPartyBackupVolumeServiceImpl.mergeGuestQuiesce(manifest, true, value));
        ThirdPartyBackupManifest recovered = ThirdPartyBackupManifest.fromJson(manifest.toJson());
        assertEquals("VERIFIED", recovered.getGuestQuiesce().status);
        assertFalse(ThirdPartyBackupVolumeServiceImpl.mergeGuestQuiesce(recovered, true, value));
    }

    @Test public void absentLegacyEvidenceDoesNotEraseTheHostResult() {
        ThirdPartyBackupManifest manifest = new ThirdPartyBackupManifest();
        assertFalse(ThirdPartyBackupVolumeServiceImpl.mergeGuestQuiesce(manifest, true, null));
        assertTrue(ThirdPartyBackupVolumeServiceImpl.mergeGuestQuiesce(manifest, true,
                Map.of("requested", true, "status", "VERIFIED")));
        assertFalse(ThirdPartyBackupVolumeServiceImpl.mergeGuestQuiesce(manifest, true, null));
        assertEquals("VERIFIED", manifest.getGuestQuiesce().status);
    }

    @Test public void rejectsAChangedOrUnconfirmedResult() {
        ThirdPartyBackupManifest manifest = new ThirdPartyBackupManifest();
        assertThrows(CloudRuntimeException.class, () -> ThirdPartyBackupVolumeServiceImpl.mergeGuestQuiesce(manifest, true,
                Map.of("requested", false, "status", "NOT_REQUESTED")));
        assertThrows(CloudRuntimeException.class, () -> ThirdPartyBackupVolumeServiceImpl.mergeGuestQuiesce(manifest, true,
                Map.of("requested", true, "status", "FAILED")));
        ThirdPartyBackupVolumeServiceImpl.mergeGuestQuiesce(manifest, true, Map.of("requested", true, "status", "VERIFIED"));
        assertThrows(CloudRuntimeException.class, () -> ThirdPartyBackupVolumeServiceImpl.mergeGuestQuiesce(manifest, true,
                Map.of("requested", true, "status", "NOT_APPLICABLE")));
        assertEquals("VERIFIED", manifest.getGuestQuiesce().status);
    }
}
