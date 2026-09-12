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
import java.util.List;

import com.cloud.utils.exception.CloudRuntimeException;

/** Additive authentication schema changes, including Europa databases already at 4.23. */
public final class EuropaSecuritySchemaUpgrade {
    private EuropaSecuritySchemaUpgrade() { }

    public static void migrate(Connection connection) {
        try {
            long width = scalar(connection, "SELECT character_maximum_length FROM information_schema.columns "
                    + "WHERE table_schema='cloud' AND table_name='api_keypair' AND column_name='description'");
            if (width < 0) {
                throw new SQLException("API key pair schema is missing");
            }
            if (width < 1024) {
                execute(connection, "ALTER TABLE cloud.api_keypair MODIFY COLUMN description VARCHAR(1024) DEFAULT NULL");
            }
            addColumn(connection, "authorize_url", "VARCHAR(255) DEFAULT NULL");
            addColumn(connection, "token_url", "VARCHAR(255) DEFAULT NULL");
            addColumn(connection, "domain_id", "BIGINT UNSIGNED DEFAULT NULL");
            if (!indexExists(connection, "i_oauth_provider__domain_id")) {
                execute(connection, "ALTER TABLE cloud.oauth_provider ADD INDEX i_oauth_provider__domain_id (domain_id)");
            }
            if (!indexExists(connection, "uk_oauth_provider__provider_domain")) {
                execute(connection, "ALTER TABLE cloud.oauth_provider ADD UNIQUE INDEX uk_oauth_provider__provider_domain (provider,domain_id)");
            }
            if (scalar(connection, "SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema='cloud' "
                    + "AND table_name='oauth_provider' AND constraint_name='fk_oauth_provider__domain_id' AND delete_rule='CASCADE'") == 0) {
                boolean existing = scalar(connection, "SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema='cloud' "
                        + "AND table_name='oauth_provider' AND constraint_name='fk_oauth_provider__domain_id' AND constraint_type='FOREIGN KEY'") > 0;
                // Keep the existing constraint if validation of the replacement fails.
                execute(connection, "ALTER TABLE cloud.oauth_provider " + (existing ? "DROP FOREIGN KEY fk_oauth_provider__domain_id, " : "")
                        + "ADD CONSTRAINT fk_oauth_provider__domain_id FOREIGN KEY (domain_id) REFERENCES cloud.domain(id) ON DELETE CASCADE");
            }
            for (String role : List.of("User", "Domain Admin", "Resource Admin")) {
                addPermission(connection, role);
            }
        } catch (SQLException e) {
            throw new CloudRuntimeException("Unable to apply Europa authentication schema upgrade; existing keys have been preserved", e);
        }
    }

    private static void addColumn(Connection connection, String column, String definition) throws SQLException {
        if (scalar(connection, "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='cloud' "
                + "AND table_name='oauth_provider' AND column_name='" + column + "'") == 0) {
            execute(connection, "ALTER TABLE cloud.oauth_provider ADD COLUMN " + column + " " + definition);
        }
    }

    private static boolean indexExists(Connection connection, String index) throws SQLException {
        return scalar(connection, "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='cloud' "
                + "AND table_name='oauth_provider' AND index_name='" + index + "'") > 0;
    }

    private static long scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            return result.next() ? result.getLong(1) : -1;
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static void addPermission(Connection connection, String roleName) throws SQLException {
        String query = "SELECT r.id FROM cloud.roles r WHERE r.name=? AND r.is_default=1 "
                + "AND NOT EXISTS (SELECT 1 FROM cloud.role_permissions p WHERE p.role_id=r.id AND p.rule='listUserKeyRules')";
        try (PreparedStatement roles = connection.prepareStatement(query)) {
            roles.setString(1, roleName);
            try (ResultSet result = roles.executeQuery()) {
                while (result.next()) {
                    long roleId = result.getLong(1);
                    long order = Math.max(0, scalar(connection, "SELECT COALESCE(MAX(sort_order),0) FROM cloud.role_permissions WHERE role_id=" + roleId));
                    try (PreparedStatement moveLast = connection.prepareStatement("UPDATE cloud.role_permissions SET sort_order=sort_order+1 WHERE role_id=? AND sort_order=?");
                         PreparedStatement insert = connection.prepareStatement("INSERT INTO cloud.role_permissions (uuid,role_id,rule,permission,sort_order) VALUES (UUID(),?,'listUserKeyRules','ALLOW',?)")) {
                        moveLast.setLong(1, roleId);
                        moveLast.setLong(2, order);
                        moveLast.executeUpdate();
                        insert.setLong(1, roleId);
                        insert.setLong(2, order);
                        insert.executeUpdate();
                    }
                }
            }
        }
    }
}
