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
-- Schema upgrade from 4.21.0.0 to 4.22.0.0
--;

-- health check status as enum
CALL `cloud`.`IDEMPOTENT_CHANGE_COLUMN`('router_health_check', 'check_result', 'check_result', 'varchar(16) NOT NULL COMMENT "check executions result: SUCCESS, FAILURE, WARNING, UNKNOWN"');

-- Increase length of scripts_version column to 128 due to md5sum to sha512sum change
CALL `cloud`.`IDEMPOTENT_CHANGE_COLUMN`('cloud.domain_router', 'scripts_version', 'scripts_version', 'VARCHAR(128)');

CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.snapshot_policy','domain_id', 'BIGINT(20) DEFAULT NULL');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.snapshot_policy','account_id', 'BIGINT(20) DEFAULT NULL');

CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.backup_schedule','domain_id', 'BIGINT(20) DEFAULT NULL');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.backup_schedule','account_id', 'BIGINT(20) DEFAULT NULL');

-- Increase the cache_mode column size from cloud.disk_offering table
CALL `cloud`.`IDEMPOTENT_CHANGE_COLUMN`('cloud.disk_offering', 'cache_mode', 'cache_mode', 'varchar(18) DEFAULT "none" COMMENT "The disk cache mode to use for disks created with this offering"');

-- Add uuid column to ldap_configuration table
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.ldap_configuration', 'uuid', 'VARCHAR(40) NOT NULL');

-- Populate uuid for existing rows where uuid is NULL or empty
UPDATE `cloud`.`ldap_configuration` SET uuid = UUID() WHERE uuid IS NULL OR uuid = '';

-- Add the column cross_zone_instance_creation to cloud.backup_repository. if enabled it means that new Instance can be created on all Zones from Backups on this Repository.
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.backup_repository', 'cross_zone_instance_creation', 'TINYINT(1) DEFAULT NULL COMMENT ''Backup Repository can be used for disaster recovery on another zone''');

-- Updated display to false for password/token detail of the storage pool details
UPDATE `cloud`.`storage_pool_details` SET display = 0 WHERE name LIKE '%password%' AND display <> 0;
UPDATE `cloud`.`storage_pool_details` SET display = 0 WHERE name LIKE '%token%' AND display <> 0;

-- Add csi_enabled column to kubernetes_cluster table to indicate if the cluster is using csi or not
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.kubernetes_cluster', 'csi_enabled', 'TINYINT(1) unsigned NOT NULL DEFAULT 0 COMMENT "true if kubernetes cluster is using csi, false otherwise" ');

-- VMware to KVM migration improvements
CREATE TABLE IF NOT EXISTS `cloud`.`import_vm_task`(
    `id` bigint unsigned NOT NULL auto_increment COMMENT 'id',
    `uuid` varchar(40),
    `zone_id` bigint unsigned NOT NULL COMMENT 'Zone ID',
    `account_id` bigint unsigned NOT NULL COMMENT 'Account ID',
    `user_id` bigint unsigned NOT NULL COMMENT 'User ID',
    `vm_id` bigint unsigned COMMENT 'VM ID',
    `display_name` varchar(255) COMMENT 'Display VM Name',
    `vcenter` varchar(255) COMMENT 'VCenter',
    `datacenter` varchar(255) COMMENT 'VCenter Datacenter name',
    `source_vm_name` varchar(255) COMMENT 'Source VM name on vCenter',
    `convert_host_id` bigint unsigned COMMENT 'Convert Host ID',
    `import_host_id` bigint unsigned COMMENT 'Import Host ID',
    `step` varchar(20) COMMENT 'Importing VM Task Step',
    `v2k_step` varchar(32) DEFAULT 'None' COMMENT 'Ablestack-v2k importing VM task step',
    `cluster_id` bigint unsigned COMMENT 'Cluster ID used by the import task',
    `service_offering_id` bigint unsigned COMMENT 'Service offering ID used by the import task',
    `v2k_target_storage_pool_id` bigint unsigned COMMENT 'Primary storage pool ID used as ablestack-v2k target',
    `source_cluster_name` varchar(255) COMMENT 'Source VMware cluster name used by the import task',
    `source_host_name` varchar(255) COMMENT 'Source VMware host name used by the import task',
    `vcenter_id` bigint unsigned COMMENT 'Existing vCenter ID used by the import task',
    `vcenter_username` varchar(255) COMMENT 'vCenter username used by the import task',
    `vcenter_password` varchar(255) COMMENT 'vCenter password used by the import task',
    `service_offering_details` text COMMENT 'Serialized custom service offering details used by the import task',
    `nic_network_map` text COMMENT 'Serialized NIC selection map used by the import task, including network and optional IP address',
    `migration_tool` varchar(32) DEFAULT 'legacy' COMMENT 'Migration tool used by the import task',
    `source_provider` varchar(32) COMMENT 'Source provider used by the import task',
    `target_provider` varchar(32) COMMENT 'Target provider used by the import task',
    `target_profile` varchar(64) COMMENT 'Resolved target profile used by the import task',
    `target_storage_pool_id` bigint unsigned COMMENT 'Resolved target primary storage pool ID used by the import task',
    `target_format` varchar(16) COMMENT 'Resolved target disk format used by the import task',
    `target_storage_type` varchar(32) COMMENT 'Resolved target storage type used by the import task',
    `target_vm_name` varchar(255) COMMENT 'Target VM name used by the import task',
    `source_endpoint` varchar(255) COMMENT 'Source endpoint used by the import task without secrets',
    `source_ref` varchar(255) COMMENT 'Provider-specific source VM reference used by the import task',
    `source_inventory_json` mediumtext COMMENT 'Serialized source VM inventory snapshot',
    `source_context_json` mediumtext COMMENT 'Serialized non-secret source context',
    `source_credential_id` bigint unsigned COMMENT 'Encrypted credential row used by the import task',
    `target_context_json` mediumtext COMMENT 'Serialized target context and disk map',
    `workdir` varchar(1024) COMMENT 'Tool workdir used by the import task',
    `split_mode` varchar(16) COMMENT 'Requested split mode used by the import task',
    `current_phase` varchar(32) COMMENT 'Current normalized migration phase',
    `migration_state` varchar(32) COMMENT 'Current normalized migration state',
    `migration_step` varchar(255) COMMENT 'Current normalized migration step',
    `cutover_policy` varchar(32) COMMENT 'Cutover policy used by phase2 or full migration',
    `status_json` mediumtext COMMENT 'Latest normalized migration status payload',
    `error_code` varchar(64) COMMENT 'Normalized migration error code',
    `state` varchar(20) COMMENT 'Importing VM Task State',
    `description` varchar(255) COMMENT 'Importing VM Task Description',
    `duration` bigint unsigned COMMENT 'Duration in milliseconds for the completed tasks',
    `created` datetime NOT NULL COMMENT 'date created',
    `updated` datetime COMMENT 'date updated if not null',
    `removed` datetime COMMENT 'date removed if not null',
    PRIMARY KEY (`id`),
    CONSTRAINT `fk_import_vm_task__zone_id` FOREIGN KEY `fk_import_vm_task__zone_id` (`zone_id`) REFERENCES `data_center`(`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_import_vm_task__account_id` FOREIGN KEY `fk_import_vm_task__account_id` (`account_id`) REFERENCES `account`(`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_import_vm_task__user_id` FOREIGN KEY `fk_import_vm_task__user_id` (`user_id`) REFERENCES `user`(`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_import_vm_task__vm_id` FOREIGN KEY `fk_import_vm_task__vm_id` (`vm_id`) REFERENCES `vm_instance`(`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_import_vm_task__convert_host_id` FOREIGN KEY `fk_import_vm_task__convert_host_id` (`convert_host_id`) REFERENCES `host`(`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_import_vm_task__import_host_id` FOREIGN KEY `fk_import_vm_task__import_host_id` (`import_host_id`) REFERENCES `host`(`id`) ON DELETE CASCADE,
    INDEX `i_import_vm_task__zone_id`(`zone_id`),
    INDEX `i_import_vm_task__zone_tool_state_created`(`zone_id`, `migration_tool`, `state`, `created`),
    INDEX `i_import_vm_task__zone_source_state_created`(`zone_id`, `source_provider`, `state`, `created`),
    INDEX `i_import_vm_task__target_phase_state`(`target_provider`, `current_phase`, `migration_state`),
    INDEX `i_import_vm_task__source_credential_id`(`source_credential_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `cloud`.`import_vm_task_event`(
    `id` bigint unsigned NOT NULL auto_increment COMMENT 'id',
    `uuid` varchar(40) NOT NULL COMMENT 'UUID',
    `task_id` bigint unsigned NOT NULL COMMENT 'Import VM task ID',
    `event_type` varchar(64) NOT NULL COMMENT 'Import VM task event type',
    `phase` varchar(32) COMMENT 'Migration phase at event time',
    `state` varchar(32) COMMENT 'Migration state at event time',
    `step` varchar(255) COMMENT 'Migration step at event time',
    `message` text COMMENT 'Import VM task event message',
    `payload_json` mediumtext COMMENT 'Serialized event payload without secrets',
    `created` datetime NOT NULL COMMENT 'date created',
    PRIMARY KEY (`id`),
    CONSTRAINT `fk_import_vm_task_event__task_id` FOREIGN KEY `fk_import_vm_task_event__task_id` (`task_id`) REFERENCES `import_vm_task`(`id`) ON DELETE CASCADE,
    INDEX `i_import_vm_task_event__task_id_created`(`task_id`, `created`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `cloud`.`import_vm_task_credential`(
    `id` bigint unsigned NOT NULL auto_increment COMMENT 'id',
    `uuid` varchar(40) NOT NULL COMMENT 'UUID',
    `task_id` bigint unsigned NOT NULL COMMENT 'Import VM task ID',
    `provider` varchar(32) NOT NULL COMMENT 'Credential source provider',
    `credential_type` varchar(32) NOT NULL COMMENT 'Credential type',
    `username_hint` varchar(255) COMMENT 'Non-secret username hint',
    `encrypted_payload` mediumtext NOT NULL COMMENT 'Encrypted credential payload',
    `encryption_version` varchar(32) NOT NULL COMMENT 'Credential encryption version',
    `key_id` varchar(128) COMMENT 'Credential encryption key ID',
    `created` datetime NOT NULL COMMENT 'date created',
    `updated` datetime COMMENT 'date updated if not null',
    `removed` datetime COMMENT 'date removed if not null',
    PRIMARY KEY (`id`),
    CONSTRAINT `fk_import_vm_task_credential__task_id` FOREIGN KEY `fk_import_vm_task_credential__task_id` (`task_id`) REFERENCES `import_vm_task`(`id`) ON DELETE CASCADE,
    INDEX `i_import_vm_task_credential__task_id_created`(`task_id`, `created`),
    INDEX `i_import_vm_task_credential__task_id_removed`(`task_id`, `removed`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE `cloud`.`import_vm_task` CONVERT TO CHARACTER SET utf8mb4;
ALTER TABLE `cloud`.`import_vm_task_event` CONVERT TO CHARACTER SET utf8mb4;
ALTER TABLE `cloud`.`import_vm_task_credential` CONVERT TO CHARACTER SET utf8mb4;

CALL `cloud`.`INSERT_EXTENSION_IF_NOT_EXISTS`('MaaS', 'Baremetal Extension for Canonical MaaS written in Python', 'MaaS/maas.py');
CALL `cloud`.`INSERT_EXTENSION_DETAIL_IF_NOT_EXISTS`('MaaS', 'orchestratorrequirespreparevm', 'true', 0);

CALL `cloud`.`IDEMPOTENT_DROP_UNIQUE_KEY`('counter', 'uc_counter__provider__source__value');
CALL `cloud`.`IDEMPOTENT_ADD_UNIQUE_KEY`('cloud.counter', 'uc_counter__provider__source__value__removed', '(provider, source, value, removed)');

-- Change scope for configuration - 'use.https.to.upload from' from StoragePool to Zone
UPDATE `cloud`.`configuration` SET `scope` = 2 WHERE `name` = 'use.https.to.upload';
-- Delete the configuration for 'use.https.to.upload' from StoragePool
DELETE FROM `cloud`.`storage_pool_details` WHERE `name` = 'use.https.to.upload';
