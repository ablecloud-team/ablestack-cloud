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
import com.cloud.utils.exception.CloudRuntimeException;
/** Additive configuration journal migration, independently resumable after each DDL. */
public final class EuropaStorageConfigurationUpgrade {
    public static final String KEY = "europa-storage-configuration-v1";
    private EuropaStorageConfigurationUpgrade() { }
    public static void migrate(final Connection conn) {
        try {
            column(conn, "configuration_revision", "BIGINT UNSIGNED NOT NULL DEFAULT 0");
            column(conn, "active_configuration_operation_id", "BIGINT UNSIGNED NULL");
            try (Statement s = conn.createStatement()) {
                s.execute("CREATE TABLE IF NOT EXISTS cloud.storage_service_configuration_operation (\n id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,\n uuid VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,\n instance_id BIGINT UNSIGNED NOT NULL, account_id BIGINT UNSIGNED NOT NULL, domain_id BIGINT UNSIGNED NOT NULL,\n job_id BIGINT UNSIGNED NULL, request_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin"
                        + " NOT NULL,\n request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,\n protocol VARCHAR(16), resource_type VARCHAR(32), resource_uuid VARCHAR(36), action VARCHAR(16),\n base_revision BIGINT UNSIGNED NOT NULL, target_revision BIGINT UNSIGNED NOT NULL,\n state VARCHAR(32) NOT NULL, phase VARCHAR(32) NOT NULL,\n before_snapshot MEDIUMTEXT, candidate_snapshot MEDIUMTEXT, original_failure_json"
                        + " TEXT,\n rollback_result_json TEXT, guest_observation_json TEXT,\n created DATETIME NOT NULL, updated DATETIME NOT NULL, completed DATETIME NULL,\n UNIQUE KEY uk_ss_cfg_op_uuid(uuid), UNIQUE KEY uk_ss_cfg_op_request(instance_id,request_id),\n KEY idx_ss_cfg_op_instance_created(instance_id,created)\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            }
        } catch (SQLException e) { throw new CloudRuntimeException("Unable to migrate storage configuration journal", e); }
    }
    private static void column(final Connection conn, final String name, final String definition) throws SQLException {
        try (PreparedStatement p = conn.prepareStatement("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='cloud' AND table_name='storage_service_instance' AND column_name=?")) {
            p.setString(1, name);
            try (ResultSet rs = p.executeQuery()) {
                if (rs.next() && rs.getInt(1) == 0) {
                    try (Statement s = conn.createStatement()) {
                        s.execute("ALTER TABLE cloud.storage_service_instance ADD COLUMN " + name + " " + definition);
                    }
                }
            }
        }
    }
}
