-- Licensed to the Apache Software Foundation (ASF) under one
-- or more contributor license agreements.  See the NOTICE file
-- distributed with this work for additional information
-- regarding copyright ownership.  The ASF licenses this file
-- to you under the Apache License, Version 2.0 (the
-- "License"); you may not use this file except in compliance
-- with the License.  You may obtain a copy of the License at
--
--   http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing,
-- software distributed under the License is distributed on an
-- "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
-- KIND, either express or implied.  See the License for the
-- specific language governing permissions and limitations
-- under the License.

--;
-- Schema upgrade from ablestack-cerato to ablestack-diplo
--;

-- BEGIN TABLE vbmc_port
CREATE TABLE IF NOT EXISTS `vbmc_port` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `vm_id` bigint unsigned NOT NULL DEFAULT '0' COMMENT 'vbmc port assigned vm id',
  `port` int NOT NULL COMMENT 'vbmc port number',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb3;

INSERT IGNORE INTO `cloud`.`vbmc_port` (id, port) VALUES (1, 6230);
INSERT IGNORE INTO `cloud`.`vbmc_port` (id, port) VALUES (2, 6231);
INSERT IGNORE INTO `cloud`.`vbmc_port` (id, port) VALUES (3, 6232);
INSERT IGNORE INTO `cloud`.`vbmc_port` (id, port) VALUES (4, 6233);
INSERT IGNORE INTO `cloud`.`vbmc_port` (id, port) VALUES (5, 6234);
INSERT IGNORE INTO `cloud`.`vbmc_port` (id, port) VALUES (6, 6235);
INSERT IGNORE INTO `cloud`.`vbmc_port` (id, port) VALUES (7, 6236);
INSERT IGNORE INTO `cloud`.`vbmc_port` (id, port) VALUES (8, 6237);
INSERT IGNORE INTO `cloud`.`vbmc_port` (id, port) VALUES (9, 6238);
INSERT IGNORE INTO `cloud`.`vbmc_port` (id, port) VALUES (10, 6239);
INSERT IGNORE INTO `cloud`.`vbmc_port` (id, port) VALUES (11, 6240);
INSERT IGNORE INTO `cloud`.`vbmc_port` (id, port) VALUES (12, 6241);
INSERT IGNORE INTO `cloud`.`vbmc_port` (id, port) VALUES (13, 6242);
INSERT IGNORE INTO `cloud`.`vbmc_port` (id, port) VALUES (14, 6243);
INSERT IGNORE INTO `cloud`.`vbmc_port` (id, port) VALUES (15, 6244);
INSERT IGNORE INTO `cloud`.`vbmc_port` (id, port) VALUES (16, 6245);
INSERT IGNORE INTO `cloud`.`vbmc_port` (id, port) VALUES (17, 6246);
INSERT IGNORE INTO `cloud`.`vbmc_port` (id, port) VALUES (18, 6247);
INSERT IGNORE INTO `cloud`.`vbmc_port` (id, port) VALUES (19, 6248);
INSERT IGNORE INTO `cloud`.`vbmc_port` (id, port) VALUES (20, 6249);
INSERT IGNORE INTO `cloud`.`vbmc_port` (id, port) VALUES (21, 6250);


-- BEGIN TABLE rackml_config
CREATE TABLE IF NOT EXISTS `rackml_config` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'primary key',
    `zone_id` bigint unsigned NOT NULL COMMENT 'foreign key to data_center.id',
    `name` varchar(100) NOT NULL COMMENT 'config name (e.g. default)',
    `content` mediumtext NOT NULL COMMENT 'RackML content',
    `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'last update time',
PRIMARY KEY (`id`),
UNIQUE KEY `uc_rackml_zone_name` (`zone_id`, `name`),
CONSTRAINT `fk_rackml__zone` FOREIGN KEY (`zone_id`) REFERENCES `data_center` (`id`)
                                                          ON DELETE RESTRICT
                                                          ON UPDATE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb3;
CALL `cloud`.`IDEMPOTENT_CHANGE_COLUMN`(
    'cloud.rackml_config',
    'content',
    'content',
    'MEDIUMTEXT NOT NULL COMMENT ''RackML content'''
);
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.host', 'migration_ip', 'VARCHAR(45)');

-- backup offering table update
CALL `cloud`.`ADD_COL`('backup_offering', 'retention_period', 'VARCHAR(255) DEFAULT null');
CALL `cloud`.`ADD_COL`('backups', 'snapshot_id', 'VARCHAR(255) DEFAULT null');
ALTER TABLE `cloud`.`backups` MODIFY COLUMN `external_id` varchar(4096) DEFAULT NULL COMMENT 'external ID';

CREATE TABLE IF NOT EXISTS `cloud`.`storage_service_instance` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `uuid` varchar(40) NOT NULL,
  `name` varchar(255) NOT NULL,
  `description` varchar(4096) DEFAULT NULL,
  `domain_id` bigint unsigned NOT NULL,
  `account_id` bigint unsigned NOT NULL,
  `data_center_id` bigint unsigned NOT NULL,
  `vm_id` bigint unsigned DEFAULT NULL,
  `service_offering_id` bigint unsigned DEFAULT NULL,
  `provider` varchar(255) NOT NULL,
  `state` varchar(32) NOT NULL,
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated` datetime DEFAULT NULL,
  `removed` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_storage_service_instance__uuid` (`uuid`),
  KEY `idx_storage_service_instance__account_id` (`account_id`),
  KEY `idx_storage_service_instance__domain_id` (`domain_id`),
  KEY `idx_storage_service_instance__data_center_id` (`data_center_id`),
  KEY `idx_storage_service_instance__vm_id` (`vm_id`),
  KEY `idx_storage_service_instance__state` (`state`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;

CREATE TABLE IF NOT EXISTS `cloud`.`storage_service_protocol` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `uuid` varchar(40) NOT NULL,
  `instance_id` bigint unsigned NOT NULL,
  `protocol` varchar(32) NOT NULL,
  `enabled` tinyint(1) NOT NULL DEFAULT '0',
  `listen_ip` varchar(45) DEFAULT NULL,
  `port` int unsigned DEFAULT NULL,
  `state` varchar(32) NOT NULL,
  `config_json` mediumtext DEFAULT NULL,
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated` datetime DEFAULT NULL,
  `removed` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_storage_service_protocol__uuid` (`uuid`),
  KEY `idx_storage_service_protocol__instance_id` (`instance_id`),
  KEY `idx_storage_service_protocol__protocol` (`protocol`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;

CREATE TABLE IF NOT EXISTS `cloud`.`storage_file_share` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `uuid` varchar(40) NOT NULL,
  `instance_id` bigint unsigned NOT NULL,
  `protocol` varchar(32) NOT NULL,
  `name` varchar(255) NOT NULL,
  `path` varchar(4096) NOT NULL,
  `volume_id` bigint unsigned DEFAULT NULL,
  `filesystem` varchar(64) DEFAULT NULL,
  `quota_bytes` bigint unsigned DEFAULT NULL,
  `state` varchar(32) NOT NULL,
  `config_json` mediumtext DEFAULT NULL,
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated` datetime DEFAULT NULL,
  `removed` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_storage_file_share__uuid` (`uuid`),
  KEY `idx_storage_file_share__instance_id` (`instance_id`),
  KEY `idx_storage_file_share__protocol` (`protocol`),
  KEY `idx_storage_file_share__volume_id` (`volume_id`),
  KEY `idx_storage_file_share__state` (`state`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;

CREATE TABLE IF NOT EXISTS `cloud`.`storage_block_target` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `uuid` varchar(40) NOT NULL,
  `instance_id` bigint unsigned NOT NULL,
  `protocol` varchar(32) NOT NULL,
  `target_name` varchar(255) NOT NULL,
  `lun_or_namespace` varchar(255) DEFAULT NULL,
  `volume_id` bigint unsigned DEFAULT NULL,
  `state` varchar(32) NOT NULL,
  `config_json` mediumtext DEFAULT NULL,
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated` datetime DEFAULT NULL,
  `removed` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_storage_block_target__uuid` (`uuid`),
  KEY `idx_storage_block_target__instance_id` (`instance_id`),
  KEY `idx_storage_block_target__protocol` (`protocol`),
  KEY `idx_storage_block_target__volume_id` (`volume_id`),
  KEY `idx_storage_block_target__state` (`state`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;

CREATE TABLE IF NOT EXISTS `cloud`.`storage_access_rule` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `uuid` varchar(40) NOT NULL,
  `resource_type` varchar(32) NOT NULL,
  `resource_id` bigint unsigned NOT NULL,
  `principal_type` varchar(32) NOT NULL,
  `principal` varchar(1024) NOT NULL,
  `permission` varchar(32) NOT NULL,
  `secret_ref` varchar(1024) DEFAULT NULL,
  `state` varchar(32) NOT NULL,
  `config_json` mediumtext DEFAULT NULL,
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated` datetime DEFAULT NULL,
  `removed` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_storage_access_rule__uuid` (`uuid`),
  KEY `idx_storage_access_rule__resource` (`resource_type`, `resource_id`),
  KEY `idx_storage_access_rule__principal` (`principal_type`, `principal`(255))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;

CREATE TABLE IF NOT EXISTS `cloud`.`storage_identity_domain` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `uuid` varchar(40) NOT NULL,
  `instance_id` bigint unsigned NOT NULL,
  `domain_name` varchar(255) NOT NULL,
  `organizational_unit` varchar(1024) DEFAULT NULL,
  `dns_servers` varchar(1024) DEFAULT NULL,
  `join_state` varchar(32) NOT NULL,
  `health_state` varchar(32) DEFAULT NULL,
  `config_json` mediumtext DEFAULT NULL,
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated` datetime DEFAULT NULL,
  `removed` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_storage_identity_domain__uuid` (`uuid`),
  KEY `idx_storage_identity_domain__instance_id` (`instance_id`),
  KEY `idx_storage_identity_domain__domain_name` (`domain_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;

-- BEGIN Storage Service runtime in-place upgrade (#911)
CREATE TABLE IF NOT EXISTS `cloud`.`storage_service_runtime_bundle` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `uuid` varchar(40) NOT NULL,
  `version` varchar(128) NOT NULL,
  `runtime_abi_version` varchar(32) NOT NULL,
  `desired_state_schema_version` varchar(32) NOT NULL,
  `service_impact` varchar(32) NOT NULL,
  `artifact_url` varchar(2048) NOT NULL,
  `manifest_url` varchar(2048) NOT NULL,
  `signature_url` varchar(2048) NOT NULL,
  `artifact_size` bigint unsigned DEFAULT NULL,
  `sha256` char(64) NOT NULL,
  `manifest_sha256` char(64) NOT NULL,
  `signing_key_id` varchar(128) NOT NULL,
  `state` varchar(32) NOT NULL,
  `catalog_json` mediumtext DEFAULT NULL,
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `removed` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_storage_service_runtime_bundle__uuid` (`uuid`),
  UNIQUE KEY `uk_storage_service_runtime_bundle__version` (`version`),
  KEY `idx_storage_service_runtime_bundle__state` (`state`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `cloud`.`storage_service_runtime_upgrade` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `uuid` varchar(40) NOT NULL,
  `instance_id` bigint unsigned NOT NULL,
  `bundle_id` bigint unsigned NOT NULL,
  `previous_bundle_id` bigint unsigned DEFAULT NULL,
  `state` varchar(32) NOT NULL,
  `phase` varchar(64) NOT NULL,
  `progress` int unsigned NOT NULL DEFAULT 0,
  `transaction_id` varchar(128) NOT NULL,
  `started` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `heartbeat` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `completed` datetime DEFAULT NULL,
  `preflight_json` mediumtext DEFAULT NULL,
  `verification_json` mediumtext DEFAULT NULL,
  `rollback_result_json` mediumtext DEFAULT NULL,
  `error_code` varchar(128) DEFAULT NULL,
  `error_message` varchar(1024) DEFAULT NULL,
  `created_by` bigint unsigned DEFAULT NULL,
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_storage_service_runtime_upgrade__uuid` (`uuid`),
  UNIQUE KEY `uk_storage_service_runtime_upgrade__transaction_id` (`transaction_id`),
  KEY `idx_storage_service_runtime_upgrade__instance_state` (`instance_id`, `state`),
  KEY `idx_storage_service_runtime_upgrade__bundle_id` (`bundle_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.storage_service_instance', 'current_runtime_bundle_id', 'bigint unsigned DEFAULT NULL COMMENT "Current verified Storage Service runtime bundle"');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.storage_service_instance', 'previous_runtime_bundle_id', 'bigint unsigned DEFAULT NULL COMMENT "Previous verified Storage Service runtime bundle"');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.storage_service_instance', 'runtime_state', 'varchar(32) DEFAULT NULL COMMENT "Current Storage Service runtime state"');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.storage_service_instance', 'runtime_verified_at', 'datetime DEFAULT NULL COMMENT "Last verified Storage Service runtime time"');
-- END Storage Service runtime in-place upgrade (#911)

-- SharedFS Epic898 protocol-neutral directory ownership and ACL policy (#903/#916).
CREATE TABLE IF NOT EXISTS `cloud`.`storage_posix_directory_policy` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `uuid` varchar(40) NOT NULL,
  `instance_id` bigint unsigned NOT NULL,
  `volume_id` bigint unsigned NOT NULL,
  `relative_path` varchar(1024) NOT NULL,
  `path_key` char(64) CHARACTER SET ascii NOT NULL,
  `revision` bigint unsigned NOT NULL DEFAULT 1,
  `state` varchar(32) NOT NULL DEFAULT 'Allocated',
  `config_json` mediumtext NOT NULL,
  `effective_json` mediumtext DEFAULT NULL,
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `last_applied` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_storage_posix_directory_policy__uuid` (`uuid`),
  UNIQUE KEY `uk_storage_posix_directory_policy__path` (`instance_id`, `path_key`),
  KEY `idx_storage_posix_directory_policy__volume_id` (`volume_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;

SET @epic898_posix_reference_sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='cloud' AND TABLE_NAME='storage_file_share' AND COLUMN_NAME='posix_policy_id') = 0,
    'ALTER TABLE `cloud`.`storage_file_share` ADD COLUMN `posix_policy_id` bigint unsigned DEFAULT NULL, ADD KEY `idx_storage_file_share__posix_policy_id` (`posix_policy_id`)',
    'SELECT 1');
PREPARE epic898_posix_reference_stmt FROM @epic898_posix_reference_sql;
EXECUTE epic898_posix_reference_stmt;
DEALLOCATE PREPARE epic898_posix_reference_stmt;


-- Epic #898 / #909: immutable configuration artifacts and quarantined imports; no data volume bytes or secrets.
CREATE TABLE IF NOT EXISTS cloud.storage_service_config_artifact (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  uuid varchar(40) NOT NULL,
  instance_id bigint unsigned NOT NULL,
  kind varchar(32) NOT NULL,
  state varchar(32) NOT NULL,
  desired_revision bigint unsigned NOT NULL DEFAULT 0,
  source_operation_id bigint unsigned DEFAULT NULL,
  metadata_json mediumtext DEFAULT NULL,
  sha256 char(64) CHARACTER SET ascii DEFAULT NULL,
  size bigint unsigned NOT NULL DEFAULT 0,
  created_by bigint unsigned NOT NULL,
  created datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expires datetime DEFAULT NULL,
  removed datetime DEFAULT NULL,
  active_lkg_instance_id bigint unsigned GENERATED ALWAYS AS
    (CASE WHEN kind='RESTORE_POINT' AND state='ACTIVE_LKG' AND removed IS NULL THEN instance_id ELSE NULL END) STORED,
  PRIMARY KEY (id),
  UNIQUE KEY uk_storage_service_config_artifact__active_lkg (active_lkg_instance_id),
  UNIQUE KEY uk_storage_service_config_artifact__uuid (uuid),
  KEY idx_storage_service_config_artifact__scope (instance_id,kind,state),
  KEY idx_storage_service_config_artifact__expires (expires)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;


-- Existing Epic #909 artifact tables: fail rather than silently discard duplicate active points.
DROP PROCEDURE IF EXISTS cloud.ensure_storage_config_active_lkg;
DELIMITER //
CREATE PROCEDURE cloud.ensure_storage_config_active_lkg()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='cloud'
      AND TABLE_NAME='storage_service_config_artifact' AND COLUMN_NAME='active_lkg_instance_id') THEN
    ALTER TABLE cloud.storage_service_config_artifact
      ADD COLUMN active_lkg_instance_id bigint unsigned GENERATED ALWAYS AS
        (CASE WHEN kind='RESTORE_POINT' AND state='ACTIVE_LKG' AND removed IS NULL THEN instance_id ELSE NULL END) STORED,
      ADD UNIQUE KEY uk_storage_service_config_artifact__active_lkg (active_lkg_instance_id);
  END IF;
END //
DELIMITER ;
CALL cloud.ensure_storage_config_active_lkg();
DROP PROCEDURE cloud.ensure_storage_config_active_lkg;
