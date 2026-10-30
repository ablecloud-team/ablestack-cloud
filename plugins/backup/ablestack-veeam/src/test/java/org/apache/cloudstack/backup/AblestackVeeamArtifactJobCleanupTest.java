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
package org.apache.cloudstack.backup;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.backup.ablestackveeam.AblestackVeeamSshClient;
import org.apache.cloudstack.backup.dao.BackupDetailsDao;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.storage.Volume;
import com.cloud.utils.exception.CloudRuntimeException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AblestackVeeamArtifactJobCleanupTest {
    private final AblestackVeeamSshClient client = mock(AblestackVeeamSshClient.class);
    private final AblestackVeeamBackupProvider provider = new AblestackVeeamBackupProvider() {
        @Override protected AblestackVeeamSshClient getSshClient(Long zoneId) { return client; }
    };
    private final BackupDetailsDao details = mock(BackupDetailsDao.class);
    private final Map<String, String> saved = new HashMap<>();
    private final Backup backup = mock(Backup.class);
    private static final String CURRENT = "11111111-1111-4111-8111-111111111111";
    private static final String PARENT = "22222222-2222-4222-8222-222222222222";

    @Before public void setup() {
        ReflectionTestUtils.setField(provider, "backupDetailsDao", details);
        when(backup.getId()).thenReturn(1L);
        when(backup.getZoneId()).thenReturn(1L);
        when(backup.getUuid()).thenReturn(CURRENT);
        when(backup.getDetail(anyString())).thenAnswer(call -> saved.get(call.getArgument(0)));
        doAnswer(call -> { saved.put(call.getArgument(1), call.getArgument(2)); return null; })
                .when(details).addDetail(eq(1L), anyString(), anyString(), eq(false));
    }

    private ThirdPartyBackupManifest manifest(String uuid, ThirdPartyBackupManifest parent) {
        String root = "/staging/ablestack-veeam/vm/" + uuid;
        ThirdPartyBackupManifest manifest = ThirdPartyBackupManifest.create("ablestack-veeam", uuid, "vm", uuid,
                parent == null ? "FULL" : "INCREMENTAL", "ablecube1", root, "RBD_DIFF",
                List.of(new Backup.VolumeInfo("root", "root.raw", Volume.Type.ROOT, 1024L, 0L, null, null, null)),
                parent == null ? null : parent.toJson(), parent == null ? null : PARENT);
        ThirdPartyBackupManifest.Artifact metadata = new ThirdPartyBackupManifest.Artifact();
        metadata.backupUuid = uuid;
        metadata.path = root;
        metadata.sourceHost = "ablecube1";
        manifest.setMetadata(metadata);
        for (ThirdPartyBackupManifest.Artifact artifact : manifest.getOwnedArtifacts()) {
            artifact.jobId = "33333333-3333-4333-8333-333333333333";
            artifact.externalId = "44444444-4444-4444-8444-444444444444";
            artifact.completed = true;
        }
        manifest.setComplete(true);
        return manifest;
    }

    private void retire() { ReflectionTestUtils.invokeMethod(provider, "retireCompletedArtifactJobs", backup); }

    @Test public void incrementalCleanupRetiresOnlyOwnedJobsAndPersistsAnIdempotentReceipt() {
        ThirdPartyBackupManifest manifest = manifest(CURRENT, manifest(PARENT, null));
        String original = manifest.toJson();
        saved.put(ThirdPartyBackupManifest.DETAIL_KEY, original);
        doAnswer(call -> {
            ThirdPartyBackupManifest.Artifact artifact = call.getArgument(0);
            assertEquals(CURRENT, artifact.backupUuid);
            return null;
        }).when(client).retireVolumeArtifactJob(any(), anyBoolean());
        retire();
        retire();
        verify(client, times(2)).retireVolumeArtifactJob(any(), anyBoolean());
        verify(client, never()).deleteVolumeArtifact(any(), anyBoolean(), anyBoolean());
        assertEquals(original, saved.get(ThirdPartyBackupManifest.DETAIL_KEY));
        assertEquals("COMPLETED", saved.get("veeam.artifact.jobs.cleanup"));
    }

    @Test public void failedCleanupRemainsRetryableWithoutChangingRestorePointReferences() {
        ThirdPartyBackupManifest manifest = manifest(CURRENT, null);
        String original = manifest.toJson();
        saved.put(ThirdPartyBackupManifest.DETAIL_KEY, original);
        doThrow(new CloudRuntimeException("Veeam unavailable")).doNothing()
                .when(client).retireVolumeArtifactJob(any(), anyBoolean());
        assertThrows(CloudRuntimeException.class, this::retire);
        assertEquals("WAITING", saved.get("veeam.artifact.jobs.cleanup"));
        assertEquals(original, saved.get(ThirdPartyBackupManifest.DETAIL_KEY));
        retire();
        assertEquals("COMPLETED", saved.get("veeam.artifact.jobs.cleanup"));
        verify(client, times(3)).retireVolumeArtifactJob(any(), anyBoolean());
    }

    @Test public void foreignManifestAndIncompleteTransferCannotRemoveJobConfiguration() {
        saved.put(ThirdPartyBackupManifest.DETAIL_KEY, manifest(PARENT, null).toJson());
        assertThrows(CloudRuntimeException.class, this::retire);
        ThirdPartyBackupManifest current = manifest(CURRENT, null);
        current.getMetadata().completed = false;
        saved.put(ThirdPartyBackupManifest.DETAIL_KEY, current.toJson());
        assertThrows(CloudRuntimeException.class, this::retire);
        verify(client, never()).retireVolumeArtifactJob(any(), anyBoolean());
    }
}
