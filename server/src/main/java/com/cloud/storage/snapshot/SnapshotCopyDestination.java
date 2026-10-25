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
package com.cloud.storage.snapshot;

import java.util.LinkedHashMap;
import java.util.Map;
import com.cloud.dc.DataCenterVO;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.EndPoint;

/** One deterministic Secondary destination; internal endpoint IDs never leave the revision hash. */
final class SnapshotCopyDestination {
    final DataCenterVO zone;
    final DataStore store;
    final EndPoint endpoint;
    final String reason;

    SnapshotCopyDestination(DataCenterVO zone, DataStore store, EndPoint endpoint, String reason) {
        this.zone = zone; this.store = store; this.endpoint = endpoint; this.reason = reason;
    }
    boolean eligible() { return reason.isEmpty() || "RETRY".equals(reason); }
    boolean present() { return "ALREADY_PRESENT".equals(reason); }
    static String storeRevision(DataStore store) {
        if (store == null) { return ""; }
        String mount = store instanceof org.apache.cloudstack.storage.image.datastore.ImageStoreEntity
                ? ((org.apache.cloudstack.storage.image.datastore.ImageStoreEntity)store).getMountPoint() : "";
        String url = store.getTO() instanceof com.cloud.agent.api.to.NfsTO ? ((com.cloud.agent.api.to.NfsTO)store.getTO()).getUrl() : "unsupported";
        return store.getUuid() + ":" + mount + ":" + url;
    }
    String fingerprint() {
        return zone.getUuid() + ":" + zone.getAllocationState() + ":" + zone.getType() + ":" + zone.getDomainId()
                + ":" + storeRevision(store) + ":" + (endpoint != null) + ":" + reason;
    }
    Map<String, Object> describe(boolean details) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("zoneid", zone.getUuid()); row.put("name", zone.getName());
        row.put("eligible", eligible()); row.put("reason", reason);
        row.put("mode", present() ? "AlreadyPresent" : "RETRY".equals(reason) ? "Retry" : eligible() ? "Copy" : "Unavailable");
        row.put("method", "SecondaryStorageVM");
        if (store != null) {
            row.put("protocol", "nfs");
            if (details) { row.put("storeid", store.getUuid()); row.put("storename", store.getName()); }
        }
        return row;
    }
}
