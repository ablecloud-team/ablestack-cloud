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
package com.cloud.api.query;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.cloud.api.query.vo.SnapshotJoinVO;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.storage.Snapshot;
import com.cloud.storage.VMTemplateStorageResourceAssoc;
import org.apache.cloudstack.engine.subsystem.api.storage.ObjectInDataStoreStateMachine;

/** Stable snapshot/datastore reference identity and explicitly nullable read facts. */
public final class SnapshotCopyContract {
    private SnapshotCopyContract() { }

    public static Snapshot.LocationType location(Snapshot.LocationType requested, Long poolId, Long imageStoreId) {
        if (poolId != null && imageStoreId != null) {
            throw new InvalidParameterValueException("Specify only one of storageid and imagestoreid");
        }
        Snapshot.LocationType implied = poolId != null ? Snapshot.LocationType.PRIMARY : imageStoreId != null ? Snapshot.LocationType.SECONDARY : null;
        if (requested != null && implied != null && requested != implied) {
            throw new InvalidParameterValueException("locationtype conflicts with the requested datastore type");
        }
        return implied == null ? requested : implied;
    }

    public static Map<String, Object> describe(SnapshotJoinVO row, boolean diagnostics) {
        Map<String, Object> copy = new LinkedHashMap<>();
        String identity = row.getUuid() + ":" + row.getSnapshotStorePair() + ":" + row.getDataCenterUuid();
        copy.put("id", UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString());
        copy.put("role", row.getStoreRole());
        copy.put("state", row.getStoreState());
        boolean known = row.getStoreId() != null && row.getStoreUuid() != null;
        copy.put("datastoreknown", known);
        String availability = "Unknown";
        if (known && row.getStoreState() != null) {
            boolean downloaded = row.getDownloadState() == null || row.getDownloadState() == VMTemplateStorageResourceAssoc.Status.DOWNLOADED;
            boolean error = row.getErrorString() != null && !row.getErrorString().isBlank();
            availability = row.getStoreState() == ObjectInDataStoreStateMachine.State.Ready && downloaded && !error ? "Ready" : "NotReady";
        }
        copy.put("availability", availability);
        copy.put("virtualsize", row.getSize() > 0 ? row.getSize() : null);
        copy.put("downloadstate", row.getDownloadState());
        Integer progress = row.getDownloadState() == VMTemplateStorageResourceAssoc.Status.DOWNLOAD_IN_PROGRESS
                && row.getDownloadPercent() >= 0 && row.getDownloadPercent() <= 100 ? row.getDownloadPercent() : null;
        copy.put("downloadpercent", diagnostics ? progress : null);
        copy.put("error", diagnostics ? row.getErrorString() : null);
        return copy;
    }
}
