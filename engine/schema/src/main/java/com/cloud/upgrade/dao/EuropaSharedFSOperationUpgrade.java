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

/** Durable operation/LKG metadata, with no user volume or VM table modification. */
public final class EuropaSharedFSOperationUpgrade {
    private EuropaSharedFSOperationUpgrade() { }
    public static void migrate(Connection connection) {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS cloud.storage_service_operation (" +
                    "id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY, uuid VARCHAR(40) NOT NULL, " +
                    "instance_id BIGINT UNSIGNED NOT NULL, request_key VARCHAR(191) NOT NULL, action VARCHAR(128) NOT NULL, " +
                    "state VARCHAR(40) NOT NULL, phase VARCHAR(64) NOT NULL, revision BIGINT UNSIGNED NOT NULL, progress INT NOT NULL DEFAULT 0, " +
                    "previous_snapshot_json LONGTEXT, snapshot_json LONGTEXT, result_json LONGTEXT, diagnostic TEXT, created_by BIGINT UNSIGNED NOT NULL, " +
                    "created DATETIME NOT NULL, heartbeat DATETIME NOT NULL, completed DATETIME DEFAULT NULL, " +
                    "UNIQUE KEY uk_storage_operation_uuid(uuid), UNIQUE KEY uk_storage_operation_request(instance_id,request_key), " +
                    "KEY idx_storage_operation_instance(instance_id,revision)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        } catch (Exception failure) {
            throw new CloudRuntimeException("Unable to apply SharedFS operation migration", failure);
        }
    }
}
