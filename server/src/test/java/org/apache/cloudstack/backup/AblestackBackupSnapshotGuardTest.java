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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import com.cloud.storage.Snapshot;
import com.cloud.storage.SnapshotVO;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.SnapshotDao;
import com.cloud.storage.dao.SnapshotDetailsDao;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonObject;

public class AblestackBackupSnapshotGuardTest {
    private static final String PENDING = "snapshot.force-delete.pending.13";
    private final SnapshotDao snapshots = mock(SnapshotDao.class);
    private final SnapshotDetailsDao details = mock(SnapshotDetailsDao.class);
    private final VolumeVO volume = mock(VolumeVO.class);
    private final SnapshotVO snapshot = mock(SnapshotVO.class);

    @Before
    public void setup() {
        when(volume.getId()).thenReturn(9L);
        when(volume.getUuid()).thenReturn("volume");
        when(snapshot.getId()).thenReturn(7L);
        when(snapshot.getUuid()).thenReturn("snapshot");
        when(snapshot.getVolumeId()).thenReturn(9L);
        when(snapshot.getState()).thenReturn(Snapshot.State.Destroyed);
        when(snapshots.listByVolumeIdIncludingRemoved(9L)).thenReturn(List.of(snapshot));
    }

    private JsonObject target(String role) {
        JsonObject target = new JsonObject();
        target.addProperty("referenceid", 13L);
        target.addProperty("snapshotuuid", "snapshot");
        target.addProperty("volumeid", 9L);
        target.addProperty("storeid", 20L);
        target.addProperty("role", role);
        return target;
    }

    private boolean blocked() {
        return AblestackBackupSnapshotGuard.hasBlockingSnapshots(volume, snapshots, details);
    }

    private void pending(String json) {
        when(details.listDetailsKeyPairs(7L)).thenReturn(Map.of(PENDING, json));
    }

    private void assertPending() {
        CloudRuntimeException error = assertThrows(CloudRuntimeException.class, this::blocked);
        assertTrue(error.getMessage().startsWith("SNAPSHOT_CLEANUP_PENDING:"));
        assertTrue(error.getMessage().contains("volume"));
    }

    @Test
    public void destroyedSnapshotWithPrimaryCleanupStillBlocksRestore() {
        pending(target("Primary").toString());
        assertPending();
        verify(snapshots).listByVolumeIdIncludingRemoved(9L);
    }

    @Test
    public void removedAndFailedSnapshotsRetainTheirCleanupProtection() {
        when(snapshot.getRemoved()).thenReturn(new Date());
        when(snapshot.getState()).thenReturn(Snapshot.State.Error);
        pending(target("Primary").toString());
        assertPending();
    }

    @Test
    public void unmappedCleanupBlocksEvenWithoutAReferencePayload() {
        when(details.listDetailsKeyPairs(7L)).thenReturn(Map.of("snapshot.force-delete.unmapped", ""));
        assertPending();
    }

    @Test
    public void knownSecondaryCleanupDoesNotBlockTheSourceVolume() {
        for (String role : List.of("Image", "ImageCache")) {
            pending(target(role).toString());
            assertFalse(blocked());
        }
    }

    @Test
    public void primaryCleanupIsNotHiddenByASecondaryCleanupReceipt() {
        JsonObject secondary = target("Image");
        secondary.addProperty("referenceid", 14L);
        when(details.listDetailsKeyPairs(7L)).thenReturn(Map.of(PENDING, target("Primary").toString(),
                "snapshot.force-delete.pending.14", secondary.toString()));
        assertPending();
    }

    @Test
    public void malformedOrUnknownReceiptsCannotAuthorizeRestore() {
        for (String json : List.of("", "null", "[]", "{", "{}", target("Unknown").toString(), target("").toString())) {
            pending(json);
            assertPending();
        }
        JsonObject missingRole = target("Image");
        missingRole.remove("role");
        pending(missingRole.toString());
        assertPending();
    }

    @Test
    public void secondaryReceiptMustMatchTheSnapshotVolumeReferenceAndStore() {
        for (String field : List.of("snapshotuuid", "volumeid", "referenceid", "storeid")) {
            JsonObject mismatched = target("Image");
            mismatched.addProperty(field, field.equals("snapshotuuid") ? "different-snapshot" : "0");
            pending(mismatched.toString());
            assertPending();
            JsonObject missing = target("Image");
            missing.remove(field);
            pending(missing.toString());
            assertPending();
        }
    }

    @Test
    public void invalidReferenceKeyCannotMakeASecondaryReceiptTrusted() {
        when(details.listDetailsKeyPairs(7L)).thenReturn(Map.of("snapshot.force-delete.pending.invalid", target("Image").toString()));
        assertPending();
    }

    @Test
    public void completedPhysicalCleanupAllowsTheSameVolumeOnRetry() {
        pending(target("Primary").toString());
        assertPending();
        when(details.listDetailsKeyPairs(7L)).thenReturn(Map.of());
        assertFalse(blocked());
    }

    @Test
    public void activeNativeSnapshotStillBlocksWithoutCleanupMarkers() {
        for (Snapshot.State state : List.of(Snapshot.State.BackedUp, Snapshot.State.CreatedOnPrimary, Snapshot.State.Destroying)) {
            when(snapshot.getState()).thenReturn(state);
            assertTrue(blocked());
        }
        verifyNoInteractions(details);
    }

    @Test
    public void terminalHistoryWithoutPendingCleanupIsAllowed() {
        assertFalse(blocked());
        when(snapshot.getState()).thenReturn(Snapshot.State.Error);
        assertFalse(blocked());
    }

    @Test
    public void newDestinationDoesNotInheritTheSourceVolumesCleanup() {
        when(volume.getId()).thenReturn(10L);
        when(snapshots.listByVolumeIdIncludingRemoved(10L)).thenReturn(List.of());
        assertFalse(blocked());
        verifyNoInteractions(details);
    }

    @Test
    public void unpersistedDestinationDoesNotQuerySourceHistory() {
        when(volume.getId()).thenReturn(0L);
        assertFalse(blocked());
        verifyNoInteractions(snapshots, details);
    }

    @Test
    public void unavailableHistoryOrAuditBlocksInsteadOfAssumingCleanupCompleted() {
        when(snapshots.listByVolumeIdIncludingRemoved(9L)).thenReturn(null);
        assertTrue(assertThrows(CloudRuntimeException.class, this::blocked).getMessage().startsWith("SNAPSHOT_CLEANUP_UNKNOWN:"));
        when(snapshots.listByVolumeIdIncludingRemoved(9L)).thenReturn(List.of(snapshot));
        when(details.listDetailsKeyPairs(7L)).thenReturn(null);
        assertTrue(assertThrows(CloudRuntimeException.class, this::blocked).getMessage().startsWith("SNAPSHOT_CLEANUP_UNKNOWN:"));
    }
}
