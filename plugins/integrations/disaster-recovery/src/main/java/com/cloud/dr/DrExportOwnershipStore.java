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
