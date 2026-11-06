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
package com.cloud.upgrade.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import com.cloud.utils.exception.CloudRuntimeException;

/** Retire settings removed since the Europa backup providers were unified. */
public final class EuropaBackupConfigUpgrade {
    public static final String PHASE = "europa-4.23-s14-backup-config-v1";
    public static final String FOLLOW_UP_PHASE = "europa-4.23-s15-netbackup-timeout-config-v1";
    public static final String VEEAM_VOLUME_PHASE = "europa-4.23-s16-veeam-volume-config-v1";
    private static final String DATA_TIMEOUT = "backup.data.operation.timeout";
    private static final int DEFAULT_DATA_TIMEOUT = 43200;
    private static final String[] LEGACY_TIMEOUTS = {"backup.command.timeout", "backup.restore.timeout", "nas.backup.restore.timeout", "netbackup.restore.timeout"};
    private static final String[] OBSOLETE = {LEGACY_TIMEOUTS[0], LEGACY_TIMEOUTS[1], LEGACY_TIMEOUTS[2], LEGACY_TIMEOUTS[3],
            "backup.plugin.ablestack-veeam.sync.delete.missing.catalog", "backup.plugin.ablestack-veeam.sync.delete.rbd.diff", "backup.plugin.ablestack-veeam.staging.path"};

    private EuropaBackupConfigUpgrade() { }

    public static void migrate(Connection conn) {
        try {
            migrateTimeout(conn);
            try (PreparedStatement statement = conn.prepareStatement(
                    "DELETE FROM cloud.configuration WHERE name IN (" + String.join(", ", Collections.nCopies(OBSOLETE.length, "?")) + ")")) {
                for (int i = 0; i < OBSOLETE.length; i++) {
                    statement.setString(i + 1, OBSOLETE[i]);
                }
                statement.executeUpdate();
            }
            try (PreparedStatement statement = conn.prepareStatement("DELETE FROM cloud.data_center_details WHERE name = ?")) {
                statement.setString(1, "backup.plugin.ablestack-veeam.staging.path");
                statement.executeUpdate();
            }
        } catch (SQLException e) {
            throw new CloudRuntimeException("Unable to migrate obsolete Europa backup settings", e);
        }
    }

    private static void migrateTimeout(Connection conn) throws SQLException {
        Map<String, String> values = new HashMap<>();
        Map<String, String> defaults = new HashMap<>();
        try (PreparedStatement statement = conn.prepareStatement(
                "SELECT name, value, default_value FROM cloud.configuration WHERE name IN (" +
                        String.join(", ", Collections.nCopies(LEGACY_TIMEOUTS.length + 1, "?")) + ")")) {
            statement.setString(1, DATA_TIMEOUT);
            for (int i = 0; i < LEGACY_TIMEOUTS.length; i++) {
                statement.setString(i + 2, LEGACY_TIMEOUTS[i]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    values.put(rows.getString(1), rows.getString(2));
                    defaults.put(rows.getString(1), rows.getString(3));
                }
            }
        }
        // An existing unified setting is authoritative, including on a retry after partial migration.
        if (values.containsKey(DATA_TIMEOUT)) {
            return;
        }
        Integer customTimeout = null;
        for (String name : LEGACY_TIMEOUTS) {
            Integer value = positiveInteger(values.get(name));
            if (value != null && !value.equals(positiveInteger(defaults.get(name)))) {
                customTimeout = customTimeout == null ? value : Math.max(customTimeout, value);
            }
        }
        try (PreparedStatement statement = conn.prepareStatement(
                "INSERT INTO cloud.configuration (category, instance, component, name, value, description, default_value, is_dynamic, scope, parent) " +
                        "VALUES ('Advanced', 'DEFAULT', 'BackupService', ?, ?, ?, '43200', 1, 1, 'backup.framework.enabled') " +
                        "ON DUPLICATE KEY UPDATE name = VALUES(name)")) {
            statement.setString(1, DATA_TIMEOUT);
            statement.setString(2, String.valueOf(customTimeout == null ? DEFAULT_DATA_TIMEOUT : customTimeout));
            statement.setString(3, "Maximum execution time in seconds for host-side KVM backup and restore data operations, external staging, and backup data cleanup.");
            statement.executeUpdate();
        }
    }

    private static Integer positiveInteger(String value) {
        if (value == null) { return null; }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
