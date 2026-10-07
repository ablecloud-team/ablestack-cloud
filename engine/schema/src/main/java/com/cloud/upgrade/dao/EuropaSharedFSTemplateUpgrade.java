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
            statement.execute("CREATE TABLE IF NOT EXISTS cloud.storage_service_template_upgrade (\n  id bigint unsigned NOT NULL AUTO_INCREMENT PRIMARY KEY,\n  uuid varchar(40) NOT NULL,\n  instance_id bigint unsigned NOT NULL,\n  shared_filesystem_id bigint unsigned NOT NULL,\n  source_template_id bigint unsigned NOT NULL,\n  target_template_id bigint unsigned NOT NULL,\n  previous_root_volume_id bigint unsigned NOT NULL,\n  target_root_volume_id bigint unsigned DEFAULT NULL,\n  previous_guest_os_id bigint unsigned NOT NULL,\n  root_device_id bigint unsigned NOT NULL,\n  previous_vm_state varchar(32) NOT NULL,\n  state varchar(40) NOT NULL,\n  phase varchar(64) NOT NULL,\n  progress int NOT NULL DEFAULT 0,\n  revision bigint unsigned NOT NULL,\n  request_key varchar(191) NOT NULL,\n  operation_id bigint unsigned DEFAULT NULL,\n  snapshot_json LONGTEXT,\n  preflight_json MEDIUMTEXT,\n  verification_json MEDIUMTEXT,\n  rollback_result_json MEDIUMTEXT,\n  error_code varchar(128),\n  error_message TEXT,\n  created_by bigint unsigned NOT NULL,\n  started datetime DEFAULT NULL,\n  heartbeat datetime NOT NULL,\n  completed datetime DEFAULT NULL,\n  rollback_retain_until datetime DEFAULT NULL,\n  created datetime NOT NULL,\n  active_instance_id bigint unsigned GENERATED ALWAYS AS\n    (CASE WHEN state IN ('RUNNING','RECOVERY_REQUIRED') THEN instance_id ELSE NULL END) STORED,\n  UNIQUE KEY uk_storage_template_upgrade_uuid(uuid),\n  UNIQUE KEY uk_storage_template_upgrade_request(instance_id,request_key),\n  UNIQUE KEY uk_storage_template_upgrade_active(active_instance_id),\n  KEY idx_storage_template_upgrade_retention(rollback_retain_until),\n  KEY idx_storage_template_upgrade_scope(instance_id,created)\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        } catch (java.sql.SQLException failure) {
            throw new CloudRuntimeException("Unable to apply SharedFS retained ROOT upgrade migration",failure);
        }
    }
}
