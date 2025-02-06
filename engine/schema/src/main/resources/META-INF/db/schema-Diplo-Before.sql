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

-- Adding security check table
CREATE TABLE IF NOT EXISTS `security_check` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `mshost_id` bigint unsigned NOT NULL COMMENT 'the ID of the mshost',
  `check_name` varchar(255) NOT NULL COMMENT 'name of the security check',
  `last_update` datetime DEFAULT NULL COMMENT 'last check update time',
  `check_result` tinyint(1) NOT NULL COMMENT 'check executions success or failure',
  `check_details` blob COMMENT 'check result detailed message',
  PRIMARY KEY (`id`),
  UNIQUE KEY `i_security_checks__mshost_id__check_name` (`mshost_id`,`check_name`),
  KEY `i_security_checks__mshost_id` (`mshost_id`),
  CONSTRAINT `fk_security_checks__mshost_id` FOREIGN KEY (`mshost_id`) REFERENCES `mshost` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB CHARSET=utf8mb3;

-- Adding integrity_verify_initial_hash table
CREATE TABLE IF NOT EXISTS `integrity_verification_initial_hash` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `mshost_id` bigint unsigned NOT NULL COMMENT 'the ID of the mshost',
    `file_path` varchar(255) NOT NULL COMMENT 'the file path for integrity verification',
    `initial_hash_value` varchar(255) COMMENT 'the initial hash value of the file',
    `comparison_hash_value` varchar(255) COMMENT 'the hash value for file comparison',
    `verification_result` tinyint(1) DEFAULT 1 COMMENT 'check executions success or failure',
    `verification_date` datetime DEFAULT NULL COMMENT 'the last verification time',
    `verification_details` blob COMMENT 'verification result detailed message',
    PRIMARY KEY (`id`),
    UNIQUE KEY `i_integrity_verify__mshost_id__file_path` (`mshost_id`,`file_path`),
    KEY `i_integrity_verify__mshost_id` (`mshost_id`),
    CONSTRAINT `fk_integrity_verify__mshost_id` FOREIGN KEY (`mshost_id`) REFERENCES `mshost` (`id`) ON DELETE CASCADE
    ) ENGINE=InnoDB CHARSET=utf8mb3;

-- Adding integrity_verify_initial_hash_final_result table
CREATE TABLE IF NOT EXISTS `integrity_verification_initial_hash_final_result` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `uuid` varchar(40) NULL,
    `mshost_id` bigint unsigned NOT NULL COMMENT 'the ID of the mshost',
    `verification_final_result` tinyint(1) NOT NULL DEFAULT 1 COMMENT 'check executions success or failure',
    `verification_date` datetime DEFAULT NULL COMMENT 'the last verification time',
    `verification_failed_list` mediumtext NULL COMMENT 'the failed verification failed list',
    `type` varchar(30) NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `i_integrity_verify__mshost_id__final_result` (`uuid`,`mshost_id`),
    KEY `i_integrity_verify__mshost_id` (`mshost_id`),
    CONSTRAINT `i_integrity_verify__mshost_id__file_path_final_result` FOREIGN KEY (`mshost_id`) REFERENCES `mshost` (`id`) ON DELETE CASCADE
    ) ENGINE=InnoDB CHARSET=utf8mb3;

CALL `cloud`.`ADD_COL`('disk_offering', 'shareable', 'tinyint(1) unsigned NOT NULL DEFAULT 0');

CALL `cloud`.`ADD_COL`('disk_offering', 'kvdo_enable', 'tinyint(1) unsigned NOT NULL DEFAULT 0');

CALL `cloud`.`ADD_COL`('vm_template', 'kvdo_enable', 'tinyint(1) unsigned NOT NULL DEFAULT 0');

CALL `cloud`.`ADD_COL`('nics', 'link_state', 'tinyint(1) unsigned NOT NULL DEFAULT 1');

CALL `cloud`.`ADD_COL`('vm_instance', 'qemu_agent_version', 'varchar(16)');

CALL `cloud`.`ADD_COL`('snapshots', 'clone_type', 'varchar(32)');

CALL `cloud`.`ADD_COL`('volumes', 'compress', 'tinyint(1) unsigned NOT NULL DEFAULT 0');

CALL `cloud`.`ADD_COL`('volumes', 'dedup', 'tinyint(1) unsigned NOT NULL DEFAULT 0');

CALL `cloud`.`ADD_COL`('volumes', 'used_fs_bytes', 'bigint unsigned');

CALL `cloud`.`ADD_COL`('volumes', 'used_physical_size', 'bigint unsigned');

-- Adding disaster_recovery_cluster table
CREATE TABLE IF NOT EXISTS `disaster_recovery_cluster` (
    `id`                     bigint unsigned AUTO_INCREMENT,
    `uuid`                   varchar(40)     NULL,
    `mshost_id`              bigint unsigned NOT NULL,
    `name`                   varchar(255)    NOT NULL,
    `description`            varchar(255)    NULL,
    `dr_cluster_url`         varchar(255)    NOT NULL,
    `dr_cluster_type`        varchar(255)    NOT NULL,
    `dr_cluster_status`      varchar(255)    NOT NULL,
    `mirroring_agent_status` varchar(255)    NOT NULL,
    `glue_ip_address`        varchar(255)    NULL,
    `created`                datetime        NOT NULL COMMENT 'date created',
    `removed`                datetime        NULL COMMENT 'date removed',
    PRIMARY KEY (`id`),
    CONSTRAINT `i_disaster_recovery_cluster__mshost_id__file_path_final_result` FOREIGN KEY (`mshost_id`) REFERENCES mshost (`id`) ON DELETE CASCADE
    ) ENGINE=InnoDB CHARSET=utf8mb3;

-- Adding disaster_recovery_cluster_vm_map table
CREATE TABLE IF NOT EXISTS `disaster_recovery_cluster_vm_map` (
    `id`                           bigint unsigned auto_increment,
    `disaster_recovery_cluster_id` bigint unsigned NOT NULL COMMENT 'the ID of the Disaster Recovery Cluster',
    `vm_id`                        bigint unsigned NOT NULL COMMENT 'the ID of the VM',
    `mirrored_vm_id`               varchar(40)     NULL,
    `mirrored_vm_name`             varchar(255)    NULL,
    `mirrored_vm_status`           varchar(255)    NULL,
    `mirrored_vm_volume_type`      varchar(255)    NULL,
    `mirrored_vm_volume_path`        varchar(255)    NULL,
    `mirrored_vm_volume_status`    varchar(255)    NULL,
    PRIMARY KEY (`id`),
    CONSTRAINT `fk_disaster_recovery_cluster_vm_map_disaster_recovery_cluster_id` FOREIGN KEY (`disaster_recovery_cluster_id`) REFERENCES `disaster_recovery_cluster` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_disaster_recovery_cluster_vm_map_vm_instance_id`
    FOREIGN KEY (`vm_id`) REFERENCES vm_instance (`id`)
    ) ENGINE=InnoDB CHARSET=utf8mb3;

-- Adding disaster_recovery_cluster_details table
CREATE TABLE IF NOT EXISTS `disaster_recovery_cluster_details` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `dr_cluster_id` bigint unsigned NOT NULL COMMENT 'disaster_recovery_cluster id',
    `name` varchar(255) NOT NULL,
    `value` varchar(5120) NOT NULL,
    PRIMARY KEY (`id`),
    CONSTRAINT `fk_disaster_recovery_cluster_details__dr_cluster_id` FOREIGN KEY (`dr_cluster_id`) REFERENCES `disaster_recovery_cluster` (`id`) ON DELETE CASCADE
    ) ENGINE=InnoDB CHARSET=utf8mb3;
