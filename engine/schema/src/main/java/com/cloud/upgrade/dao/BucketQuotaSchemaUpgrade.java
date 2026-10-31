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
import java.sql.SQLException;
import java.sql.Statement;
import com.cloud.utils.exception.CloudRuntimeException;

public final class BucketQuotaSchemaUpgrade {
    private BucketQuotaSchemaUpgrade() { }
    public static void migrate(Connection connection) {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS `cloud`.`bucket_quota_operation` (\n"
                    + " `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,\n"
                    + " `uuid` VARCHAR(40) NOT NULL,\n"
                    + " `bucket_id` BIGINT UNSIGNED NOT NULL,\n"
                    + " `active_bucket_id` BIGINT UNSIGNED DEFAULT NULL,\n"
                    + " `account_id` BIGINT UNSIGNED NOT NULL,\n"
                    + " `domain_id` BIGINT UNSIGNED NOT NULL,\n"
                    + " `object_store_id` BIGINT UNSIGNED NOT NULL,\n"
                    + " `operation_type` VARCHAR(16) NOT NULL,\n"
                    + " `previous_quota` INT DEFAULT NULL,\n"
                    + " `requested_quota` INT DEFAULT NULL,\n"
                    + " `previous_snapshot` VARCHAR(1024) DEFAULT NULL,\n"
                    + " `store_url` VARCHAR(2048) NOT NULL,\n"
                    + " `external_id` VARCHAR(255) DEFAULT NULL,\n"
                    + " `external_created` TINYINT(1) NOT NULL DEFAULT 0,\n"
                    + " `state` VARCHAR(32) NOT NULL DEFAULT 'Prepared',\n"
                    + " `account_hold_bytes` BIGINT UNSIGNED NOT NULL DEFAULT 0,\n"
                    + " `reserved_bytes` BIGINT UNSIGNED NOT NULL DEFAULT 0,\n"
                    + " `attempts` INT NOT NULL DEFAULT 0,\n"
                    + " `error_code` VARCHAR(64) DEFAULT NULL,\n"
                    + " `created` DATETIME NOT NULL,\n"
                    + " `updated_at` DATETIME NOT NULL,\n"
                    + " PRIMARY KEY (`id`), UNIQUE KEY `uk_bucket_quota_operation_uuid` (`uuid`),\n"
                    + " UNIQUE KEY `uk_bucket_quota_operation_active_bucket` (`active_bucket_id`),\n"
                    + " KEY `idx_bucket_quota_operation_pending_store` (`object_store_id`,`state`),\n"
                    + " KEY `idx_bucket_quota_operation_pending_account` (`account_id`,`state`),\n"
                    + " CONSTRAINT `fk_bucket_quota_operation_account` FOREIGN KEY (`account_id`) REFERENCES `cloud`.`account` (`id`) ON DELETE CASCADE,\n"
                    + " CONSTRAINT `fk_bucket_quota_operation_store` FOREIGN KEY (`object_store_id`) REFERENCES `cloud`.`object_store` (`id`) ON DELETE CASCADE\n"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8;\n");
        } catch (SQLException e) { throw new CloudRuntimeException("Unable to prepare bucket quota operation schema", e); }
    }
}
