// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.cloudstack.storage.sharedfs.query.dao;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import com.cloud.utils.db.TransactionLegacy;
import com.cloud.utils.exception.CloudRuntimeException;

/** Page-scoped DB projection: only attached, referenced, distinct data volumes. No guest reads. */
public final class SharedFSCapacityProjection {
    private SharedFSCapacityProjection() { }

    public static final class Capacity {
        private final Set<Long> volumes = new HashSet<>();
        private long total;
        private boolean unknown;
        private boolean transitioning;
        public void add(final long volumeId, final Long size, final String state) {
            if (!volumes.add(volumeId)) return;
            if (size == null || size < 0) unknown = true;
            else total = Math.addExact(total, size);
            if (!"Ready".equals(state)) transitioning = true;
        }
        public Long getTotal() { return unknown ? null : total; }
        public int getCount() { return volumes.size(); }
        public String getState() { return unknown ? "UNAVAILABLE" : transitioning ? "TRANSITIONING" : "PROVISIONED_USAGE_UNOBSERVED"; }
    }

    public static Map<Long, Capacity> load(final Long[] ids) {
        final Map<Long, Capacity> result = new HashMap<>();
        if (ids.length == 0) return result;
        for (Long id : ids) result.put(id, new Capacity());
        final String slots = String.join(",", Collections.nCopies(ids.length, "?"));
        final String query = "SELECT sf.id, v.id, v.size, v.state FROM shared_filesystem sf "
                + "JOIN (SELECT id AS sharedfs_id, volume_id FROM shared_filesystem WHERE id IN (" + slots + ") "
                + "UNION SELECT sf2.id, fs.volume_id FROM shared_filesystem sf2 "
                + "JOIN storage_service_instance si ON si.vm_id=sf2.vm_id JOIN storage_file_share fs ON fs.instance_id=si.id "
                + "WHERE fs.state<>'Destroyed' AND sf2.id IN (" + slots + ") "
                + "UNION SELECT sf3.id, bt.volume_id FROM shared_filesystem sf3 "
                + "JOIN storage_service_instance si ON si.vm_id=sf3.vm_id JOIN storage_block_target bt ON bt.instance_id=si.id "
                + "WHERE bt.state<>'Destroyed' AND sf3.id IN (" + slots + ")) refs ON refs.sharedfs_id=sf.id "
                + "JOIN volumes v ON v.id=refs.volume_id AND v.instance_id=sf.vm_id "
                + "WHERE v.removed IS NULL AND v.state NOT IN ('Destroy','Destroying','Expunging','Expunged')";
        try (PreparedStatement statement = TransactionLegacy.currentTxn().prepareAutoCloseStatement(query)) {
            for (int group=0; group<3; group++) {
                for (int i=0; i<ids.length; i++) statement.setLong(group*ids.length+i+1, ids[i]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    long size = rows.getLong(3);
                    Long bytes = rows.wasNull() ? null : size;
                    result.computeIfAbsent(rows.getLong(1), key -> new Capacity()).add(rows.getLong(2), bytes, rows.getString(4));
                }
            }
        } catch (SQLException e) {
            throw new CloudRuntimeException("Unable to load SharedFS backing volume capacity projection", e);
        }
        return result;
    }
}
