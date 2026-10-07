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
public final class EuropaSharedFSTemplateUpgrade {
    private EuropaSharedFSTemplateUpgrade() { }
    public static void migrate(Connection connection) {
        try (Statement statement=connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS cloud.storage_service_template_upgrade ( "
                    + "  id bigint unsigned NOT NULL AUTO_INCREMENT PRIMARY KEY, "
                    + "  uuid varchar(40) NOT NULL, "
                    + "  instance_id bigint unsigned NOT NULL, "
                    + "  shared_filesystem_id bigint unsigned NOT NULL, "
                    + "  source_template_id bigint unsigned NOT NULL, "
                    + "  target_template_id bigint unsigned NOT NULL, "
                    + "  previous_root_volume_id bigint unsigned NOT NULL, "
                    + "  target_root_volume_id bigint unsigned DEFAULT NULL, "
                    + "  previous_guest_os_id bigint unsigned NOT NULL, "
                    + "  root_device_id bigint unsigned NOT NULL, "
                    + "  previous_vm_state varchar(32) NOT NULL, "
                    + "  state varchar(40) NOT NULL, "
                    + "  phase varchar(64) NOT NULL, "
                    + "  progress int NOT NULL DEFAULT 0, "
                    + "  revision bigint unsigned NOT NULL, "
                    + "  request_key varchar(191) NOT NULL, "
                    + "  operation_id bigint unsigned DEFAULT NULL, "
                    + "  snapshot_json LONGTEXT, "
                    + "  preflight_json MEDIUMTEXT, "
                    + "  verification_json MEDIUMTEXT, "
                    + "  rollback_result_json MEDIUMTEXT, "
                    + "  error_code varchar(128), "
                    + "  error_message TEXT, "
                    + "  created_by bigint unsigned NOT NULL, "
                    + "  started datetime DEFAULT NULL, "
                    + "  heartbeat datetime NOT NULL, "
                    + "  completed datetime DEFAULT NULL, "
                    + "  rollback_retain_until datetime DEFAULT NULL, "
                    + "  created datetime NOT NULL, "
                    + "  active_instance_id bigint unsigned GENERATED ALWAYS AS "
                    + "    (CASE WHEN state IN ('RUNNING','RECOVERY_REQUIRED') THEN instance_id ELSE NULL END) STORED, "
                    + "  UNIQUE KEY uk_storage_template_upgrade_uuid(uuid), "
                    + "  UNIQUE KEY uk_storage_template_upgrade_request(instance_id,request_key), "
                    + "  UNIQUE KEY uk_storage_template_upgrade_active(active_instance_id), "
                    + "  KEY idx_storage_template_upgrade_retention(rollback_retain_until), "
                    + "  KEY idx_storage_template_upgrade_scope(instance_id,created) "
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 ");
        } catch (java.sql.SQLException failure) {
            throw new CloudRuntimeException("Unable to apply SharedFS retained ROOT upgrade migration",failure);
        }
    }
}
