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
import java.util.Objects;

import com.cloud.storage.DataStoreRole;
import com.cloud.storage.Snapshot;
import com.cloud.storage.SnapshotVO;
import com.cloud.storage.Volume;
import com.cloud.storage.dao.SnapshotDao;
import com.cloud.storage.dao.SnapshotDetailsDao;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** ABLESTACK restore guard for native snapshots whose forced deletion has not settled. */
public final class AblestackBackupSnapshotGuard {
    private static final String PENDING = "snapshot.force-delete.pending.";
    private static final String UNMAPPED = "snapshot.force-delete.unmapped";

    private AblestackBackupSnapshotGuard() { }

    /** Called only for an RBD destination. New, unpersisted restore volumes have no native history. */
    public static boolean hasBlockingSnapshots(Volume volume, SnapshotDao snapshots, SnapshotDetailsDao details) {
        if (volume.getId() <= 0) {
            return false;
        }
        List<SnapshotVO> history = snapshots.listByVolumeIdIncludingRemoved(volume.getId());
        if (history == null) {
            throw new CloudRuntimeException("SNAPSHOT_CLEANUP_UNKNOWN: Cannot inspect native snapshots for volume " + volume.getUuid());
        }
        for (SnapshotVO snapshot : history) {
            if (snapshot.getRemoved() == null && snapshot.getState() != Snapshot.State.Destroyed && snapshot.getState() != Snapshot.State.Error) {
                return true;
            }
            Map<String, String> audit = details.listDetailsKeyPairs(snapshot.getId());
            if (audit == null) {
                throw new CloudRuntimeException("SNAPSHOT_CLEANUP_UNKNOWN: Cannot inspect snapshot cleanup for volume " + volume.getUuid());
            }
            if (audit.containsKey(UNMAPPED) || audit.entrySet().stream()
                    .anyMatch(entry -> entry.getKey().startsWith(PENDING) && primaryOrUnknown(snapshot, entry))) {
                throw new CloudRuntimeException("SNAPSHOT_CLEANUP_PENDING: Native snapshot cleanup is incomplete for volume "
                        + volume.getUuid() + ", snapshot " + snapshot.getUuid()
                        + ". Complete snapshot physical cleanup before restoring over this volume.");
            }
        }
        return false;
    }

    private static boolean primaryOrUnknown(SnapshotVO snapshot, Map.Entry<String, String> entry) {
        try {
            JsonObject target = JsonParser.parseString(entry.getValue()).getAsJsonObject();
            long reference = Long.parseLong(entry.getKey().substring(PENDING.length()));
            DataStoreRole role = DataStoreRole.valueOf(target.get("role").getAsString());
            // Only a complete, matching secondary-store receipt can prove that the source image is unaffected.
            return reference <= 0 || reference != number(target, "referenceid") || number(target, "storeid") <= 0
                    || !Objects.equals(snapshot.getUuid(), target.get("snapshotuuid").getAsString())
                    || snapshot.getVolumeId() != number(target, "volumeid") || !role.isImageStore();
        } catch (RuntimeException incomplete) {
            return true;
        }
    }

    private static long number(JsonObject target, String name) {
        return Long.parseLong(target.get(name).getAsString());
    }
}
