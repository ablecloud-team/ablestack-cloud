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

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import com.cloud.utils.db.ScriptRunner;
import com.cloud.utils.exception.CloudRuntimeException;

/** Explicit migrations for Europa installations already reporting the upstream version. */
public final class EuropaSchemaUpgrade {
    public static final String LEGACY_HOOKS = "europa-4.23-legacy-hooks";
    public static final String S4 = "europa-4.23-s4-v1";
    public static final String S5A = "europa-4.23-s5a-v1";
    public static final String S5B = "europa-4.23-s5b-v1";
    public static final String S5C = "europa-4.23-s5c-v1";
    public static final String S6 = "europa-4.23-s6-v1";
    public static final String S7 = "europa-4.23-s7-v1";
    public static final String S8 = "europa-4.23-s8-v1";

    private EuropaSchemaUpgrade() {
    }

    public static void initialize(Connection conn) throws SQLException {
        try (Statement statement = conn.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS cloud.ablestack_schema_migration (" +
                    "name VARCHAR(191) NOT NULL PRIMARY KEY, state VARCHAR(16) NOT NULL, " +
                    "updated DATETIME NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        }
    }

    public static boolean hasState(Connection conn, String name, String state) throws SQLException {
        try (PreparedStatement statement = conn.prepareStatement(
                "SELECT state FROM cloud.ablestack_schema_migration WHERE name = ?")) {
            statement.setString(1, name);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && state.equals(result.getString(1));
            }
        }
    }

    public static void begin(Connection conn, String name) throws SQLException {
        try (PreparedStatement statement = conn.prepareStatement(
                "INSERT INTO cloud.ablestack_schema_migration (name, state, updated) VALUES (?, 'Pending', NOW()) " +
                        "ON DUPLICATE KEY UPDATE name = VALUES(name)")) {
            statement.setString(1, name);
            statement.executeUpdate();
        }
        if (!conn.getAutoCommit()) {
            conn.commit();
        }
    }

    public static void complete(Connection conn, String name) throws SQLException {
        try (PreparedStatement statement = conn.prepareStatement(
                "UPDATE cloud.ablestack_schema_migration SET state = 'Complete', updated = NOW() WHERE name = ?")) {
            statement.setString(1, name);
            statement.executeUpdate();
        }
        if (!conn.getAutoCommit()) {
            conn.commit();
        }
    }

    public static void migrate(Connection conn) {
        final String path = "META-INF/db/schema-europa-4.23-s4.sql";
        try (InputStream input = EuropaSchemaUpgrade.class.getClassLoader().getResourceAsStream(path)) {
            if (input == null) {
                throw new CloudRuntimeException("Missing Europa migration: " + path);
            }
            new ScriptRunner(conn, false, true).runScript(new InputStreamReader(input, StandardCharsets.UTF_8));
            EuropaSecuritySchemaUpgrade.migrate(conn);
            new Upgrade42030to42040().performDataMigration(conn);
            ensureVolumeUsageIndex(conn);
        } catch (Exception e) {
            throw new CloudRuntimeException("Unable to apply Europa S4 schema migration", e);
        }
    }

    static void ensureVolumeUsageIndex(Connection conn) throws SQLException {
        List<String> columns = new ArrayList<>();
        boolean unique = true;
        try (PreparedStatement statement = conn.prepareStatement("SELECT COLUMN_NAME, NON_UNIQUE FROM information_schema.statistics " +
                "WHERE TABLE_SCHEMA = 'cloud_usage' AND TABLE_NAME = 'usage_volume' AND INDEX_NAME = 'id' ORDER BY SEQ_IN_INDEX");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                columns.add(result.getString(1));
                unique &= result.getInt(2) == 0;
            }
        }
        if (unique && columns.equals(List.of("volume_id", "created", "vm_id"))) {
            return;
        }
        try (Statement statement = conn.createStatement()) {
            // One ALTER keeps the old key when a conflicting row makes the new key invalid.
            statement.execute("ALTER TABLE cloud_usage.usage_volume " + (columns.isEmpty() ? "" : "DROP INDEX id, ") +
                    "ADD UNIQUE INDEX id (volume_id, created, vm_id)");
        }
    }
}
