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
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Validates the deployed view independently of completed migration markers. */
public final class EuropaVolumeViewReconciler {
    private EuropaVolumeViewReconciler() {
    }

    public static void reconcile(Connection connection) throws Exception {
        String resource = "META-INF/db/views/cloud.volume_view.sql";
        String sql;
        try (InputStream input = EuropaVolumeViewReconciler.class.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw new SQLException("Missing canonical volume view resource: " + resource);
            }
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        String marker = "CREATE VIEW `cloud`.`volume_view` AS";
        int start = sql.indexOf(marker);
        if (start < 0) {
            throw new SQLException("Unexpected canonical volume view format");
        }
        String select = sql.substring(start + marker.length()).trim().replaceFirst(";\\s*$", "");
        reconcile(connection, select);
    }

    static void reconcile(Connection connection, String select) throws SQLException {
        // Compile the complete canonical projection first: no DDL if a dependency is absent.
        Set<String> expected = new HashSet<>();
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(select + " LIMIT 0")) {
            ResultSetMetaData metadata = result.getMetaData();
            for (int i = 1; i <= metadata.getColumnCount(); i++) {
                expected.add(metadata.getColumnLabel(i).toLowerCase(Locale.ROOT));
            }
        }
        if (expected.isEmpty()) {
            throw new SQLException("Canonical volume view has no columns");
        }
        if (columns(connection).containsAll(expected)) {
            return;
        }
        // Avoid DROP + CREATE: a failed CREATE must not leave the existing view absent.
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE OR REPLACE VIEW `cloud`.`volume_view` AS " + select);
        }
        if (!columns(connection).containsAll(expected)) {
            throw new SQLException("volume_view remains incompatible after reconciliation");
        }
    }

    private static Set<String> columns(Connection connection) throws SQLException {
        Set<String> columns = new HashSet<>();
        try (ResultSet result = connection.getMetaData().getColumns("cloud", null, "volume_view", null)) {
            while (result.next()) {
                columns.add(result.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
            }
        }
        return columns;
    }
}
