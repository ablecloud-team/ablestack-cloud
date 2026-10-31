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

/** Also runs for Europa installations already reporting schema 4.23. */
public final class ObjectStoreCredentialSchemaUpgrade {
    private ObjectStoreCredentialSchemaUpgrade() { }
    public static void migrate(Connection connection) {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS `cloud`.`object_store_account_credential` (\n"
                    + "  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,\n"
                    + "  `account_id` BIGINT UNSIGNED NOT NULL,\n"
                    + "  `object_store_id` BIGINT UNSIGNED NOT NULL,\n"
                    + "  `rgw_user_id` VARCHAR(255) NOT NULL,\n"
                    + "  `access_key` VARCHAR(1024) DEFAULT NULL,\n"
                    + "  `secret_key` VARCHAR(2048) DEFAULT NULL,\n"
                    + "  `state` VARCHAR(32) NOT NULL DEFAULT 'NeedsVerification',\n"
                    + "  `revision` BIGINT UNSIGNED NOT NULL DEFAULT 0,\n"
                    + "  `checked_at` DATETIME DEFAULT NULL,\n"
                    + "  `provisioning_source` VARCHAR(32) NOT NULL DEFAULT 'Existing',\n"
                    + "  PRIMARY KEY (`id`),\n"
                    + "  UNIQUE KEY `uk_object_store_account_credential_pair` (`account_id`,`object_store_id`),\n"
                    + "  CONSTRAINT `fk_object_store_account_credential_account` FOREIGN KEY (`account_id`) REFERENCES `cloud`.`account` (`id`) ON DELETE CASCADE,\n"
                    + "  CONSTRAINT `fk_object_store_account_credential_store` FOREIGN KEY (`object_store_id`) REFERENCES `cloud`.`object_store` (`id`) ON DELETE CASCADE\n"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8;\n");
        } catch (SQLException e) {
            throw new CloudRuntimeException("Unable to prepare scoped object storage credential schema", e);
        }
    }
}
