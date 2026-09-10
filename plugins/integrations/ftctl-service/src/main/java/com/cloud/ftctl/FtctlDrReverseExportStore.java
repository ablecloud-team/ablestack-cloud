// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information.
package com.cloud.ftctl;

import java.sql.PreparedStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import com.cloud.utils.db.TransactionLegacy;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.Gson;

/** Site-local revocation journal. Generations are issued only by the Plan owner Cloud. */
public class FtctlDrReverseExportStore {
    private static final Gson GSON = new Gson();
    public static class Journal {
        String sourceVmUuid;
        long zoneId;
        long generation;
        String operation;
        String runUuid;
        Long selectedHost;
        Map<Long, String> hosts = new LinkedHashMap<>();
    }
    public Journal load(String plan) {
        try (Connection connection = TransactionLegacy.getStandaloneConnectionWithException()) {
            connection.setAutoCommit(true);
            try (PreparedStatement ps = connection.prepareStatement(
                "SELECT journal_json FROM ftctl_dr_reverse_export WHERE plan_uuid=?")) {
            ps.setString(1, plan);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? GSON.fromJson(rs.getString(1), Journal.class) : null;
            }
            }
        } catch (SQLException e) { throw new CloudRuntimeException("DR reverse export journal read failed", e); }
    }
    public void save(String plan, Journal journal) {
        try (Connection connection = TransactionLegacy.getStandaloneConnectionWithException()) {
            connection.setAutoCommit(true);
            try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO ftctl_dr_reverse_export(plan_uuid,journal_json) VALUES (?,?) "
                + "ON DUPLICATE KEY UPDATE journal_json=VALUES(journal_json)")) {
            ps.setString(1, plan); ps.setString(2, GSON.toJson(journal)); ps.executeUpdate();
            }
        } catch (SQLException e) { throw new CloudRuntimeException("DR reverse export journal write failed", e); }
    }
}
