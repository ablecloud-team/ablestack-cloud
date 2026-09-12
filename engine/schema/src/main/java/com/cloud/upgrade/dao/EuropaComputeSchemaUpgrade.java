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

import com.cloud.utils.db.ScriptRunner;
import com.cloud.utils.exception.CloudRuntimeException;

/** Same-version compute migrations, resumable after each MySQL DDL commit. */
public final class EuropaComputeSchemaUpgrade {
    private EuropaComputeSchemaUpgrade() {
    }

    public static void migrate(Connection conn) {
        try {
            migrateSchedules(conn);
            String path = "META-INF/db/schema-europa-4.23-s5a.sql";
            try (InputStream input = EuropaComputeSchemaUpgrade.class.getClassLoader().getResourceAsStream(path)) {
                if (input == null) {
                    throw new CloudRuntimeException("Missing Europa migration: " + path);
                }
                new ScriptRunner(conn, false, true).runScript(new InputStreamReader(input, StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            throw new CloudRuntimeException("Unable to apply Europa S5A schema migration", e);
        }
    }

    static void migrateSchedules(Connection conn) throws SQLException {
        renameTable(conn, "vm_schedule", "resource_schedule");
        renameTable(conn, "vm_scheduled_job", "resource_scheduled_job");
        if (!tableExists(conn, "resource_schedule") || !tableExists(conn, "resource_scheduled_job")) {
            throw new SQLException("Europa VM schedule tables are missing; refusing to invent an empty schedule history");
        }
        dropForeignKey(conn, "resource_schedule", "fk_vm_schedule__vm_id");
        dropForeignKey(conn, "resource_scheduled_job", "fk_vm_scheduled_job__vm_id");
        dropForeignKey(conn, "resource_scheduled_job", "fk_vm_scheduled_job__vm_schedule_id");
        renameColumn(conn, "resource_schedule", "vm_id", "resource_id");
        renameColumn(conn, "resource_scheduled_job", "vm_id", "resource_id");
        renameColumn(conn, "resource_scheduled_job", "vm_schedule_id", "schedule_id");
        for (String table : new String[]{"resource_schedule", "resource_scheduled_job"}) {
            if (!columnExists(conn, table, "resource_type")) {
                execute(conn, "ALTER TABLE cloud." + table + " ADD COLUMN resource_type VARCHAR(64) NOT NULL DEFAULT 'VirtualMachine' AFTER uuid");
            }
        }
        addIndex(conn, "resource_schedule", "i_resource_schedule__resource", false, "resource_type, resource_id");
        addIndex(conn, "resource_schedule", "i_resource_schedule__enabled_end_date", false, "enabled, end_date");
        addIndex(conn, "resource_scheduled_job", "uc_resource_scheduled_job__schedule_timestamp", true, "schedule_id, scheduled_timestamp");
        addIndex(conn, "resource_scheduled_job", "i_resource_scheduled_job__resource", false, "resource_type, resource_id");
        addIndex(conn, "resource_scheduled_job", "i_resource_scheduled_job__scheduled_timestamp", false, "scheduled_timestamp");
        // Add replacements before removing the original unique key or lookup indexes.
        dropIndex(conn, "resource_schedule", "i_vm_schedule__vm_id");
        dropIndex(conn, "resource_schedule", "i_vm_schedule__enabled_end_date");
        dropIndex(conn, "resource_scheduled_job", "i_vm_scheduled_job__vm_id");
        dropIndex(conn, "resource_scheduled_job", "i_vm_scheduled_job__scheduled_timestamp");
        dropIndex(conn, "resource_scheduled_job", "vm_schedule_id");
        if (!foreignKeyExists(conn, "resource_scheduled_job", "fk_resource_scheduled_job__schedule_id")) {
            execute(conn, "ALTER TABLE cloud.resource_scheduled_job ADD CONSTRAINT fk_resource_scheduled_job__schedule_id " +
                    "FOREIGN KEY (schedule_id) REFERENCES cloud.resource_schedule(id) ON DELETE CASCADE");
        }
    }

    private static void renameTable(Connection conn, String oldName, String newName) throws SQLException {
        if (!tableExists(conn, oldName)) {
            return;
        }
        if (tableExists(conn, newName)) {
            throw new SQLException("Both " + oldName + " and " + newName + " exist; refusing to merge or discard schedule history");
        }
        execute(conn, "RENAME TABLE cloud." + oldName + " TO cloud." + newName);
    }

    private static void renameColumn(Connection conn, String table, String oldName, String newName) throws SQLException {
        if (!columnExists(conn, table, oldName)) {
            if (!columnExists(conn, table, newName)) {
                throw new SQLException("Missing schedule column " + table + "." + newName);
            }
            return;
        }
        if (columnExists(conn, table, newName)) {
            throw new SQLException("Both old and new schedule columns exist in " + table);
        }
        execute(conn, "ALTER TABLE cloud." + table + " CHANGE COLUMN " + oldName + " " + newName + " BIGINT UNSIGNED NOT NULL");
    }

    private static void addIndex(Connection conn, String table, String name, boolean unique, String columns) throws SQLException {
        if (!indexExists(conn, table, name)) {
            execute(conn, "ALTER TABLE cloud." + table + " ADD " + (unique ? "UNIQUE " : "") + "INDEX " + name + " (" + columns + ")");
        }
    }

    private static void dropIndex(Connection conn, String table, String name) throws SQLException {
        if (indexExists(conn, table, name)) {
            execute(conn, "ALTER TABLE cloud." + table + " DROP INDEX " + name);
        }
    }

    private static void dropForeignKey(Connection conn, String table, String name) throws SQLException {
        if (foreignKeyExists(conn, table, name)) {
            execute(conn, "ALTER TABLE cloud." + table + " DROP FOREIGN KEY " + name);
        }
    }

    private static boolean tableExists(Connection conn, String table) throws SQLException {
        return exists(conn, "SELECT 1 FROM information_schema.tables WHERE table_schema='cloud' AND table_name=?", table);
    }

    private static boolean columnExists(Connection conn, String table, String column) throws SQLException {
        return exists(conn, "SELECT 1 FROM information_schema.columns WHERE table_schema='cloud' AND table_name=? AND column_name=?", table, column);
    }

    private static boolean indexExists(Connection conn, String table, String name) throws SQLException {
        return exists(conn, "SELECT 1 FROM information_schema.statistics WHERE table_schema='cloud' AND table_name=? AND index_name=?", table, name);
    }

    private static boolean foreignKeyExists(Connection conn, String table, String name) throws SQLException {
        return exists(conn, "SELECT 1 FROM information_schema.referential_constraints WHERE constraint_schema='cloud' AND table_name=? AND constraint_name=?", table, name);
    }

    private static boolean exists(Connection conn, String sql, String... values) throws SQLException {
        try (PreparedStatement statement = conn.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                statement.setString(i + 1, values[i]);
            }
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static void execute(Connection conn, String sql) throws SQLException {
        try (Statement statement = conn.createStatement()) {
            statement.execute(sql);
        }
    }
}
