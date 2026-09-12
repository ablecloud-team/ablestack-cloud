/*
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
*/

// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information.
package com.cloud.dr;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import com.cloud.utils.db.TransactionLegacy;
import com.cloud.utils.exception.CloudRuntimeException;

/** Monotonic fencing epochs and historical revocation obligations, not placement bindings. */
public class DrExportOwnershipStore {
    public long nextGeneration(long planId) {
        try (PreparedStatement ps = TransactionLegacy.currentTxn().getConnection().prepareStatement(
                "INSERT INTO dr_export_transition (plan_id) VALUES (?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, planId); ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (!rs.next()) { throw new SQLException("Missing export generation"); }
                return Math.multiplyExact(rs.getLong(1), 2L);
            }
        } catch (SQLException e) { throw new CloudRuntimeException("DR export generation persistence failed", e); }
    }
    public static class RecoveryExport {
        public long planId;
        public long generation;
        public String workerUuid;
        public String fingerprint;
        public boolean drained;
    }

    public RecoveryExport findRecoveryExport(long runId) {
        try (PreparedStatement ps = TransactionLegacy.currentTxn().prepareAutoCloseStatement(
                "SELECT plan_id,revoke_generation,observed_worker_uuid,disk_fingerprint,drained "
                + "FROM dr_cleanup_export_resume WHERE cleanup_run_id=?")) {
            ps.setLong(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) { return null; }
                RecoveryExport row = new RecoveryExport();
                row.planId=rs.getLong(1); row.generation=rs.getLong(2); row.workerUuid=rs.getString(3);
                row.fingerprint=rs.getString(4); row.drained=rs.getBoolean(5);
                return row;
            }
        } catch (SQLException e) { throw new CloudRuntimeException("DR cleanup export read failed", e); }
    }

    public RecoveryExport prepareRecoveryExport(long planId, long runId, String workerUuid, String fingerprint) {
        RecoveryExport current = findRecoveryExport(runId);
        if (current != null && current.planId == planId && workerUuid.equals(current.workerUuid)
                && fingerprint.equals(current.fingerprint)) { return current; }
        long generation = nextGeneration(planId);
        String same = "observed_worker_uuid=VALUES(observed_worker_uuid) AND disk_fingerprint=VALUES(disk_fingerprint)";
        try (PreparedStatement ps = TransactionLegacy.currentTxn().prepareAutoCloseStatement(
                "INSERT INTO dr_cleanup_export_resume "
                + "(cleanup_run_id,plan_id,revoke_generation,observed_worker_uuid,disk_fingerprint,drained) VALUES (?,?,?,?,?,0) "
                + "ON DUPLICATE KEY UPDATE drained=IF("+same+",drained,0), "
                + "revoke_generation=IF("+same+",revoke_generation,VALUES(revoke_generation)), "
                + "observed_worker_uuid=VALUES(observed_worker_uuid),disk_fingerprint=VALUES(disk_fingerprint)")) {
            ps.setLong(1,runId); ps.setLong(2,planId); ps.setLong(3,generation);
            ps.setString(4,workerUuid); ps.setString(5,fingerprint); ps.executeUpdate();
        } catch (SQLException e) { throw new CloudRuntimeException("DR cleanup export prepare failed", e); }
        RecoveryExport prepared = findRecoveryExport(runId);
        if (prepared == null || prepared.planId != planId) {
            throw new CloudRuntimeException("DR cleanup export Plan identity mismatch");
        }
        return prepared;
    }

    public void markRecoveryExportDrained(long runId, long generation) {
        try (PreparedStatement ps = TransactionLegacy.currentTxn().prepareAutoCloseStatement(
                "UPDATE dr_cleanup_export_resume SET drained=1 WHERE cleanup_run_id=? AND revoke_generation=?")) {
            ps.setLong(1,runId); ps.setLong(2,generation);
            if (ps.executeUpdate()!=1) { throw new SQLException("Export recovery generation changed"); }
        } catch (SQLException e) { throw new CloudRuntimeException("DR cleanup export drain persistence failed", e); }
    }

    public Set<Long> rememberHosts(long planId, Set<Long> hosts) {
        try {
            for (Long host : hosts) {
                try (PreparedStatement ps = TransactionLegacy.currentTxn().prepareAutoCloseStatement(
                        "INSERT IGNORE INTO dr_export_host_history (plan_id,host_id) VALUES (?,?)")) {
                    ps.setLong(1, planId); ps.setLong(2, host); ps.executeUpdate();
                }
            }
            Set<Long> result = new HashSet<>();
            try (PreparedStatement ps = TransactionLegacy.currentTxn().prepareAutoCloseStatement(
                    "SELECT host_id FROM dr_export_host_history WHERE plan_id=?")) {
                ps.setLong(1, planId);
                try (ResultSet rs = ps.executeQuery()) { while (rs.next()) { result.add(rs.getLong(1)); } }
            }
            return result;
        } catch (SQLException e) { throw new CloudRuntimeException("DR export host history persistence failed", e); }
    }
}
