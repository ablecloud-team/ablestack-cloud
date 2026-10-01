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
package com.cloud.upgrade.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Repeatable, non-destructive upgrade: legacy allocations remain reserved. */
public final class EuropaVbmcSchemaUpgrade {
    private EuropaVbmcSchemaUpgrade() { }

    public static void migrate(Connection conn) {
        try {
            migrateSchema(conn);
        } catch (SQLException e) {
            throw new com.cloud.utils.exception.CloudRuntimeException("Unable to upgrade Virtual BMC allocation schema", e);
        }
    }

    private static void migrateSchema(Connection conn) throws SQLException {
        column(conn, "status", "VARCHAR(32) NOT NULL DEFAULT 'Unallocated'");
        column(conn, "host_id", "BIGINT UNSIGNED NULL");
        column(conn, "instance_name", "VARCHAR(255) NULL");
        column(conn, "owner_token", "VARCHAR(64) NULL");
        column(conn, "address", "VARCHAR(64) NULL");
        column(conn, "allowed_cidr", "VARCHAR(64) NULL");
        column(conn, "last_error", "VARCHAR(255) NULL");
        column(conn, "last_checked", "DATETIME NULL");
        try (Statement s = conn.createStatement()) {
            // Do not guess the owning host: a legacy VM might already have migrated.
            s.executeUpdate("UPDATE cloud.vbmc_port SET status='CleanupRequired', " +
                    "last_error='Legacy endpoint: administrator must verify and clean the original host' " +
                    "WHERE vm_id <> 0 AND owner_token IS NULL");
        }
    }

    private static void column(Connection conn, String name, String definition) throws SQLException {
        try (PreparedStatement q = conn.prepareStatement("SELECT COUNT(*) FROM information_schema.columns " +
                "WHERE table_schema='cloud' AND table_name='vbmc_port' AND column_name=?")) {
            q.setString(1, name);
            try (ResultSet rs = q.executeQuery()) {
                rs.next();
                if (rs.getInt(1) == 0) {
                    try (Statement s = conn.createStatement()) {
                        s.execute("ALTER TABLE cloud.vbmc_port ADD COLUMN " + name + " " + definition);
                    }
                }
            }
        }
    }
}
