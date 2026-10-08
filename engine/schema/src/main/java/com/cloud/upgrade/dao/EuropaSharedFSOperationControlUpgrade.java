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
import java.sql.Statement;
import com.cloud.utils.exception.CloudRuntimeException;

public final class EuropaSharedFSOperationControlUpgrade {
    private EuropaSharedFSOperationControlUpgrade() { }
    public static void migrate(Connection connection) {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS cloud.storage_service_operation_control ( " +
                    " id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY, " +
                    " operation_id BIGINT UNSIGNED NOT NULL, " +
                    " instance_id BIGINT UNSIGNED NOT NULL, " +
                    " control_revision BIGINT UNSIGNED NOT NULL DEFAULT 1, " +
                    " cancel_requested TINYINT(1) NOT NULL DEFAULT 0, " +
                    " cancel_requested_by BIGINT UNSIGNED DEFAULT NULL, " +
                    " drain_state VARCHAR(32) NOT NULL DEFAULT 'NOT_REQUESTED', " +
                    " policy_json LONGTEXT, " +
                    " lease_json LONGTEXT, " +
                    " created_by BIGINT UNSIGNED NOT NULL, " +
                    " created DATETIME NOT NULL, " +
                    " updated DATETIME NOT NULL, " +
                    " UNIQUE KEY uk_storage_operation_control_operation(operation_id), " +
                    " KEY idx_storage_operation_control_instance(instance_id) " +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 ");
        } catch (java.sql.SQLException failure) {
            throw new CloudRuntimeException("Unable to apply SharedFS operation control migration", failure);
        }
    }
    public static void migratePolicy(Connection connection) {
        try (Statement statement = connection.createStatement()) {
            try (java.sql.ResultSet table = connection.getMetaData().getTables("cloud", null, "storage_service_instance", new String[]{"TABLE"})) {
                if (!table.next()) return;
            }
            try (java.sql.ResultSet column = connection.getMetaData().getColumns("cloud", null, "storage_service_instance", "operation_control_policy_json")) {
                if (column.next()) return;
            }
            try {statement.execute("ALTER TABLE cloud.storage_service_instance ADD COLUMN operation_control_policy_json LONGTEXT DEFAULT NULL");}
            catch (java.sql.SQLException raced) {
                try (java.sql.ResultSet now = connection.getMetaData().getColumns("cloud", null, "storage_service_instance", "operation_control_policy_json")) {
                    if (!now.next()) throw raced;
                }
            }
        } catch (java.sql.SQLException failure) {
            throw new CloudRuntimeException("Unable to apply per-instance Storage Service operation control policy migration", failure);
        }
    }

}
