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
-- Schema upgrade from 4.22.0.0 to 4.22.1.0
--;

-- Add vm_id column to usage_event table for volume usage events
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.usage_event','vm_id', 'bigint UNSIGNED NULL COMMENT "VM ID associated with volume usage events"');
CALL `cloud_usage`.`IDEMPOTENT_ADD_COLUMN`('cloud_usage.usage_event','vm_id', 'bigint UNSIGNED NULL COMMENT "VM ID associated with volume usage events"');

-- Add vm_id column to cloud_usage.usage_volume table
CALL `cloud_usage`.`IDEMPOTENT_ADD_COLUMN`('cloud_usage.usage_volume','vm_id', 'bigint UNSIGNED NULL COMMENT "VM ID associated with the volume usage"');

ALTER TABLE `cloud`.`template_store_ref` MODIFY COLUMN `download_url` varchar(2048);

UPDATE `cloud`.`alert` SET type = 33 WHERE name = 'ALERT.VR.PUBLIC.IFACE.MTU';
UPDATE `cloud`.`alert` SET type = 34 WHERE name = 'ALERT.VR.PRIVATE.IFACE.MTU';

-- Update configuration 'kvm.ssh.to.agent' description and is_dynamic fields
UPDATE `cloud`.`configuration` SET description = 'True if the management server will restart the agent service via SSH into the KVM hosts after or during maintenance operations', is_dynamic = 1 WHERE name = 'kvm.ssh.to.agent';
-- Drops the unused "backup_interval_type" column of the "cloud.backups" table
CALL `cloud`.`IDEMPOTENT_DROP_COLUMN`('cloud.backups', 'backup_interval_type');

-- Sanitize legacy network-level addressing fields for Public networks
UPDATE `cloud`.`networks`
SET `broadcast_uri` = NULL,
	`gateway` = NULL,
	`cidr` = NULL,
	`ip6_gateway` = NULL,
	`ip6_cidr` = NULL
WHERE `traffic_type` = 'Public';

-- Update existing vm_template records with NULL type to "USER"
UPDATE `cloud`.`vm_template` SET `type` = 'USER' WHERE `type` IS NULL;

-- remove unused config item
DELETE FROM `cloud`.`configuration` WHERE name = 'consoleproxy.cmd.port';

-- Update `user.password.reset.mail.template` configuration value to match new logic
UPDATE `cloud`.`configuration`
SET value = CONCAT_WS('\n', 'Hello {{username}}!', 'You have requested to reset your password. Please click the following link to reset your password:', '{{{resetLink}}}', 'If you did not request a password reset, please ignore this email.', '', 'Regards,', 'The CloudStack Team')
WHERE name = 'user.password.reset.mail.template'
  AND value IN (CONCAT_WS('\n', 'Hello {{username}}!', 'You have requested to reset your password. Please click the following link to reset your password:', 'http://{{{resetLink}}}', 'If you did not request a password reset, please ignore this email.', '', 'Regards,', 'The CloudStack Team'), CONCAT_WS('\n', 'Hello {{username}}!', 'You have requested to reset your password. Please click the following link to reset your password:', '{{{domainUrl}}}{{{resetLink}}}', 'If you did not request a password reset, please ignore this email.', '', 'Regards,', 'The CloudStack Team'));

UPDATE `cloud`.`vm_template` SET guest_os_id = 99 WHERE name = 'kvm-default-vm-import-dummy-template';

CREATE TABLE IF NOT EXISTS `cloud`.`ftctl_protection` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `uuid` varchar(40) NOT NULL,
    `primary_vm_id` bigint unsigned NOT NULL COMMENT 'Primary VM managed by FTCTL protection',
    `secondary_vm_id` bigint unsigned NULL COMMENT 'Cloud-provisioned standby VM, when available',
    `secondary_vm_name` varchar(255) NULL COMMENT 'FTCTL standby VM name',
    `peer_host_id` bigint unsigned NULL COMMENT 'Peer KVM host used by FTCTL',
    `target_storage_pool_id` bigint unsigned NULL COMMENT 'Target primary storage pool for standby volumes',
    `mode` varchar(32) NOT NULL COMMENT 'FTCTL protection mode',
    `backend_mode` varchar(64) NULL COMMENT 'FTCTL backend mode',
    `provisioning_backend` varchar(64) NOT NULL DEFAULT 'libvirt-managed' COMMENT 'Protection resource provisioning owner',
    `fencing_policy` varchar(64) NULL COMMENT 'FTCTL fencing policy',
    `xcolo_port_allocation_mode` varchar(16) NULL COMMENT 'FTCTL XCOLO port allocation mode',
    `xcolo_port_slot` int NULL COMMENT 'FTCTL XCOLO automatic port allocation slot',
    `admin_state` varchar(64) NULL,
    `provisioning_state` varchar(64) NULL,
    `protection_state` varchar(64) NULL,
    `transport_state` varchar(64) NULL,
    `active_side` varchar(64) NULL,
    `fencing_state` varchar(64) NULL,
    `last_error` varchar(1024) NULL,
    `created` datetime NOT NULL,
    `updated` datetime NULL,
    `removed` datetime NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ftctl_protection__uuid` (`uuid`),
    KEY `i_ftctl_protection__primary_vm_id` (`primary_vm_id`),
    KEY `i_ftctl_protection__secondary_vm_id` (`secondary_vm_id`),
    KEY `i_ftctl_protection__peer_host_id` (`peer_host_id`),
    KEY `i_ftctl_protection__target_storage_pool_id` (`target_storage_pool_id`),
    CONSTRAINT `fk_ftctl_protection__primary_vm_id` FOREIGN KEY (`primary_vm_id`) REFERENCES `vm_instance` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_ftctl_protection__secondary_vm_id` FOREIGN KEY (`secondary_vm_id`) REFERENCES `vm_instance` (`id`) ON DELETE SET NULL,
    CONSTRAINT `fk_ftctl_protection__peer_host_id` FOREIGN KEY (`peer_host_id`) REFERENCES `host` (`id`) ON DELETE SET NULL,
    CONSTRAINT `fk_ftctl_protection__target_storage_pool_id` FOREIGN KEY (`target_storage_pool_id`) REFERENCES `storage_pool` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.ftctl_protection', 'xcolo_port_allocation_mode', 'varchar(16) NULL COMMENT "FTCTL XCOLO port allocation mode" AFTER `fencing_policy`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.ftctl_protection', 'xcolo_port_slot', 'int NULL COMMENT "FTCTL XCOLO automatic port allocation slot" AFTER `xcolo_port_allocation_mode`');

CREATE TABLE IF NOT EXISTS `cloud`.`ftctl_protection_volume` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `protection_id` bigint unsigned NOT NULL COMMENT 'FTCTL protection relationship id',
    `primary_volume_id` bigint unsigned NOT NULL COMMENT 'Primary VM volume',
    `secondary_volume_id` bigint unsigned NULL COMMENT 'Cloud-provisioned standby volume, when available',
    `primary_disk_path` varchar(2048) NULL COMMENT 'Primary disk path used by FTCTL',
    `secondary_disk_path` varchar(2048) NULL COMMENT 'Secondary disk path used by FTCTL',
    `disk_label` varchar(255) NULL COMMENT 'Stable disk label for FTCTL disk map',
    `replication_state` varchar(64) NULL,
    `created` datetime NOT NULL,
    `updated` datetime NULL,
    `removed` datetime NULL,
    PRIMARY KEY (`id`),
    KEY `i_ftctl_protection_volume__protection_id` (`protection_id`),
    KEY `i_ftctl_protection_volume__primary_volume_id` (`primary_volume_id`),
    KEY `i_ftctl_protection_volume__secondary_volume_id` (`secondary_volume_id`),
    CONSTRAINT `fk_ftctl_protection_volume__protection_id` FOREIGN KEY (`protection_id`) REFERENCES `ftctl_protection` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_ftctl_protection_volume__primary_volume_id` FOREIGN KEY (`primary_volume_id`) REFERENCES `volumes` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_ftctl_protection_volume__secondary_volume_id` FOREIGN KEY (`secondary_volume_id`) REFERENCES `volumes` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS `cloud`.`dr_site` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `uuid` varchar(40) NOT NULL,
    `name` varchar(255) NOT NULL,
    `description` varchar(1024) NULL,
    `site_type` varchar(64) NOT NULL,
    `hypervisor_type` varchar(64) NOT NULL,
    `endpoint` varchar(2048) NULL,
    `credential_ref` varchar(255) NULL,
    `credential_id` bigint unsigned NULL,
    `zone_id` bigint unsigned NULL,
    `zone_external_id` varchar(255) NULL,
    `zone_name` varchar(255) NULL,
    `vmware_datacenter_id` bigint unsigned NULL,
    `vmware_datacenter_external_id` varchar(255) NULL,
    `vmware_datacenter_name` varchar(255) NULL,
    `state` varchar(64) NULL,
    `effective_mode` varchar(32) NULL,
    `incremental_verified` tinyint(1) NULL,
    `metrics_estimated` tinyint(1) NULL,
    `virtual_bytes` bigint unsigned NULL,
    `changed_bytes` bigint unsigned NULL,
    `source_read_bytes` bigint unsigned NULL,
    `target_written_bytes` bigint unsigned NULL,
    `transfer_payload_bytes` bigint unsigned NULL,
    `changed_extent_count` bigint unsigned NULL,
    `duration_ms` bigint unsigned NULL,
    `throughput_bps` bigint unsigned NULL,
    `baseline_generation` bigint unsigned NULL,
    `cycle_token` varchar(255) NULL,
    `health_state` varchar(64) NULL,
    `capabilities_json` text NULL,
    `last_checked` datetime NULL,
    `created` datetime NOT NULL,
    `updated` datetime NULL,
    `removed` datetime NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_dr_site__uuid` (`uuid`),
    KEY `i_dr_site__name` (`name`),
    KEY `i_dr_site__credential_id` (`credential_id`),
    KEY `i_dr_site__zone_id` (`zone_id`),
    CONSTRAINT `fk_dr_site__zone_id` FOREIGN KEY (`zone_id`) REFERENCES `data_center` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_site', 'credential_id', 'bigint unsigned NULL AFTER `credential_ref`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_site', 'zone_external_id', 'varchar(255) NULL AFTER `zone_id`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_site', 'zone_name', 'varchar(255) NULL AFTER `zone_external_id`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_site', 'vmware_datacenter_external_id', 'varchar(255) NULL AFTER `vmware_datacenter_id`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_site', 'vmware_datacenter_name', 'varchar(255) NULL AFTER `vmware_datacenter_external_id`');
CALL `cloud`.`IDEMPOTENT_ADD_KEY`('i_dr_site__credential_id', 'cloud.dr_site', '(`credential_id`)');

CREATE TABLE IF NOT EXISTS `cloud`.`dr_site_credential` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `uuid` varchar(40) NOT NULL,
    `site_id` bigint unsigned NOT NULL,
    `credential_type` varchar(64) NOT NULL,
    `endpoint` varchar(2048) NULL,
    `principal` varchar(255) NULL,
    `secret_payload` text NULL,
    `tls_verify` tinyint(1) NOT NULL DEFAULT 1,
    `state` varchar(64) NULL,
    `last_validated` datetime NULL,
    `created` datetime NOT NULL,
    `updated` datetime NULL,
    `removed` datetime NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_dr_site_credential__uuid` (`uuid`),
    KEY `i_dr_site_credential__site_id` (`site_id`),
    KEY `i_dr_site_credential__state` (`state`),
    CONSTRAINT `fk_dr_site_credential__site_id` FOREIGN KEY (`site_id`) REFERENCES `dr_site` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS `cloud`.`dr_site_health_check` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `uuid` varchar(40) NOT NULL,
    `site_id` bigint unsigned NOT NULL,
    `site_uuid` varchar(40) NOT NULL,
    `site_name` varchar(255) NOT NULL,
    `site_type` varchar(64) NOT NULL,
    `hypervisor_type` varchar(64) NOT NULL,
    `endpoint` varchar(2048) NULL,
    `credential_id` bigint unsigned NULL,
    `credential_state` varchar(64) NULL,
    `trigger_type` varchar(64) NOT NULL,
    `health_state` varchar(64) NOT NULL,
    `reason_code` varchar(128) NULL,
    `message` text NULL,
    `latency_ms` bigint NULL,
    `checked_at` datetime NOT NULL,
    `management_server_id` bigint unsigned NULL,
    `job_id` varchar(255) NULL,
    `details_json` text NULL,
    `created` datetime NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_dr_site_health_check__uuid` (`uuid`),
    KEY `i_dr_site_health_check__site_checked` (`site_id`, `checked_at`),
    KEY `i_dr_site_health_check__state_checked` (`health_state`, `checked_at`),
    KEY `i_dr_site_health_check__trigger_checked` (`trigger_type`, `checked_at`),
    CONSTRAINT `fk_dr_site_health_check__site_id` FOREIGN KEY (`site_id`) REFERENCES `dr_site` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS `cloud`.`dr_site_pair` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `uuid` varchar(40) NOT NULL,
    `name` varchar(255) NOT NULL,
    `source_site_id` bigint unsigned NOT NULL,
    `target_site_id` bigint unsigned NOT NULL,
    `direction` varchar(64) NOT NULL,
    `state` varchar(64) NULL,
    `legacy_dr_cluster_id` bigint unsigned NULL,
    `created` datetime NOT NULL,
    `updated` datetime NULL,
    `removed` datetime NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_dr_site_pair__uuid` (`uuid`),
    KEY `i_dr_site_pair__source_site_id` (`source_site_id`),
    KEY `i_dr_site_pair__target_site_id` (`target_site_id`),
    KEY `i_dr_site_pair__legacy_dr_cluster_id` (`legacy_dr_cluster_id`),
    CONSTRAINT `fk_dr_site_pair__source_site_id` FOREIGN KEY (`source_site_id`) REFERENCES `dr_site` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_dr_site_pair__target_site_id` FOREIGN KEY (`target_site_id`) REFERENCES `dr_site` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS `cloud`.`dr_plan` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `uuid` varchar(40) NOT NULL,
    `name` varchar(255) NOT NULL,
    `description` varchar(1024) NULL,
    `source_site_id` bigint unsigned NOT NULL,
    `target_site_id` bigint unsigned NOT NULL,
    `source_vm_id` bigint unsigned NULL,
    `source_external_ref` varchar(2048) NULL,
    `direction` varchar(64) NOT NULL,
    `engine_type` varchar(64) NULL,
    `engine_binding_type` varchar(64) NULL,
    `engine_binding_id` bigint unsigned NULL,
    `state` varchar(64) NULL,
    `admin_state` varchar(64) NULL,
    `active_side` varchar(16) NULL,
    `rpo_seconds` int NULL,
    `rto_seconds` int NULL,
    `schedule_json` text NULL,
    `policy_json` text NULL,
    `mapping_json` text NULL,
    `quiesce_policy_json` text NULL,
    `source_worker_host_id` bigint unsigned NULL,
    `target_worker_host_id` bigint unsigned NULL,
    `coordinator_worker_host_id` bigint unsigned NULL,
    `last_source_checkpoint_at` datetime NULL,
    `last_target_durable_at` datetime NULL,
    `target_ready_at` datetime NULL,
    `target_ready_rpo_seconds` int NULL,
    `last_run_id` bigint unsigned NULL,
    `last_error_code` varchar(128) NULL,
    `last_error_message` text NULL,
    `created` datetime NOT NULL,
    `updated` datetime NULL,
    `removed` datetime NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_dr_plan__uuid` (`uuid`),
    KEY `i_dr_plan__source_site_id` (`source_site_id`),
    KEY `i_dr_plan__target_site_id` (`target_site_id`),
    KEY `i_dr_plan__source_vm_id` (`source_vm_id`),
    KEY `i_dr_plan__source_site_external_ref_removed` (`source_site_id`, `source_external_ref`(255), `removed`),
    KEY `i_dr_plan__engine_binding` (`engine_binding_type`, `engine_binding_id`),
    KEY `i_dr_plan__state` (`state`),
    CONSTRAINT `fk_dr_plan__source_site_id` FOREIGN KEY (`source_site_id`) REFERENCES `dr_site` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_dr_plan__target_site_id` FOREIGN KEY (`target_site_id`) REFERENCES `dr_site` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_dr_plan__source_vm_id` FOREIGN KEY (`source_vm_id`) REFERENCES `vm_instance` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan', 'active_side', 'varchar(16) NULL AFTER `admin_state`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan', 'source_worker_host_id', 'bigint unsigned NULL AFTER `quiesce_policy_json`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan', 'target_worker_host_id', 'bigint unsigned NULL AFTER `source_worker_host_id`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan', 'coordinator_worker_host_id', 'bigint unsigned NULL AFTER `target_worker_host_id`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan', 'last_source_checkpoint_at', 'datetime NULL AFTER `coordinator_worker_host_id`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan', 'last_target_durable_at', 'datetime NULL AFTER `last_source_checkpoint_at`');
CALL `cloud`.`IDEMPOTENT_ADD_KEY`('i_dr_plan__source_site_external_ref_removed', 'cloud.dr_plan', '(`source_site_id`, `source_external_ref`(255), `removed`)');

CREATE TABLE IF NOT EXISTS `cloud`.`dr_restore_point` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `uuid` varchar(40) NOT NULL,
    `plan_id` bigint unsigned NOT NULL,
    `run_id` bigint unsigned NULL,
    `checkpoint_sequence` bigint unsigned NULL,
    `checkpoint_cycle_type` varchar(32) NULL,
    `checkpoint_ref_hash` binary(32) NULL,
    `source_snapshot_ref` varchar(2048) NULL,
    `source_created` datetime NULL,
    `target_ready_at` datetime NULL,
    `source_rpo_seconds` int NULL,
    `target_ready_rpo_seconds` int NULL,
    `consistency_level` varchar(64) NULL,
    `restore_point_type` varchar(64) NULL,
    `state` varchar(64) NULL,
    `created` datetime NOT NULL,
    `updated` datetime NULL,
    `removed` datetime NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_dr_restore_point__uuid` (`uuid`),
    UNIQUE KEY `uk_dr_restore_point__plan_checkpoint_hash` (`plan_id`, `checkpoint_ref_hash`),
    KEY `i_dr_restore_point__plan_id` (`plan_id`),
    KEY `i_dr_restore_point__target_ready_at` (`target_ready_at`),
    KEY `i_dr_restore_point__plan_ready_removed` (`plan_id`, `target_ready_at`, `removed`),
    CONSTRAINT `fk_dr_restore_point__plan_id` FOREIGN KEY (`plan_id`) REFERENCES `dr_plan` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_restore_point', 'run_id', 'bigint unsigned NULL AFTER `plan_id`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_restore_point', 'checkpoint_sequence', 'bigint unsigned NULL AFTER `run_id`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_restore_point', 'checkpoint_cycle_type', 'varchar(32) NULL AFTER `checkpoint_sequence`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_restore_point', 'checkpoint_ref_hash', 'binary(32) NULL AFTER `checkpoint_cycle_type`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_restore_point', 'effective_mode', 'varchar(32) NULL AFTER `state`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_restore_point', 'requested_mode', 'varchar(32) NULL AFTER `state`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_restore_point', 'automatic_reseed', 'tinyint(1) NULL AFTER `effective_mode`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_restore_point', 'mode_decision_code', 'varchar(128) NULL AFTER `automatic_reseed`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_restore_point', 'reseed_reason', 'varchar(128) NULL AFTER `mode_decision_code`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_restore_point', 'invalid_baseline_disk_count', 'int unsigned NULL AFTER `reseed_reason`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_restore_point', 'incremental_verified', 'tinyint(1) NULL AFTER `effective_mode`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_restore_point', 'metrics_estimated', 'tinyint(1) NULL AFTER `incremental_verified`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_restore_point', 'virtual_bytes', 'bigint unsigned NULL AFTER `metrics_estimated`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_restore_point', 'changed_bytes', 'bigint unsigned NULL AFTER `virtual_bytes`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_restore_point', 'source_read_bytes', 'bigint unsigned NULL AFTER `changed_bytes`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_restore_point', 'target_written_bytes', 'bigint unsigned NULL AFTER `source_read_bytes`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_restore_point', 'transfer_payload_bytes', 'bigint unsigned NULL AFTER `target_written_bytes`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_restore_point', 'changed_extent_count', 'bigint unsigned NULL AFTER `transfer_payload_bytes`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_restore_point', 'duration_ms', 'bigint unsigned NULL AFTER `changed_extent_count`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_restore_point', 'throughput_bps', 'bigint unsigned NULL AFTER `duration_ms`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_restore_point', 'baseline_generation', 'bigint unsigned NULL AFTER `throughput_bps`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_restore_point', 'cycle_token', 'varchar(255) NULL AFTER `baseline_generation`');
UPDATE `cloud`.`dr_restore_point` rp
JOIN (SELECT `plan_id`, MAX(`id`) AS `run_id` FROM `cloud`.`dr_run` WHERE `removed` IS NULL GROUP BY `plan_id`) latest_run
  ON latest_run.`plan_id` = rp.`plan_id`
SET rp.`run_id` = latest_run.`run_id`
WHERE rp.`removed` IS NULL AND rp.`run_id` IS NULL;
UPDATE `cloud`.`dr_restore_point`
SET `checkpoint_sequence` = CAST(SUBSTRING_INDEX(`source_snapshot_ref`, ':', -1) AS UNSIGNED),
    `checkpoint_cycle_type` = IF(CAST(SUBSTRING_INDEX(`source_snapshot_ref`, ':', -1) AS UNSIGNED) = 1, 'full-seed', 'incremental')
WHERE `removed` IS NULL AND `checkpoint_sequence` IS NULL
  AND `source_snapshot_ref` LIKE 'ftctl:%'
  AND SUBSTRING_INDEX(`source_snapshot_ref`, ':', -1) REGEXP '^[0-9]+$';
UPDATE `cloud`.`dr_restore_point`
SET `checkpoint_ref_hash` = UNHEX(SHA2(`source_snapshot_ref`, 256))
WHERE `removed` IS NULL AND `source_snapshot_ref` IS NOT NULL AND `checkpoint_ref_hash` IS NULL;
UPDATE `cloud`.`dr_restore_point` rp
JOIN (
    SELECT `plan_id`, `checkpoint_ref_hash`, MAX(`id`) AS `keep_id`
    FROM `cloud`.`dr_restore_point`
    WHERE `removed` IS NULL AND `checkpoint_ref_hash` IS NOT NULL
    GROUP BY `plan_id`, `checkpoint_ref_hash`
    HAVING COUNT(*) > 1
) duplicate_checkpoint
  ON duplicate_checkpoint.`plan_id` = rp.`plan_id`
 AND duplicate_checkpoint.`checkpoint_ref_hash` = rp.`checkpoint_ref_hash`
SET rp.`removed` = COALESCE(rp.`removed`, NOW()), rp.`checkpoint_ref_hash` = NULL, rp.`updated` = NOW()
WHERE rp.`id` <> duplicate_checkpoint.`keep_id`;
CALL `cloud`.`IDEMPOTENT_ADD_UNIQUE_KEY`('cloud.dr_restore_point', 'uk_dr_restore_point__plan_checkpoint_hash', '(`plan_id`, `checkpoint_ref_hash`)');
CALL `cloud`.`IDEMPOTENT_ADD_KEY`('i_dr_restore_point__plan_ready_removed', 'cloud.dr_restore_point', '(`plan_id`, `target_ready_at`, `removed`)');

CREATE TABLE IF NOT EXISTS `cloud`.`dr_restore_point_artifact` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `uuid` varchar(40) NOT NULL,
    `restore_point_id` bigint unsigned NOT NULL,
    `artifact_type` varchar(64) NOT NULL,
    `artifact_ref` varchar(2048) NOT NULL,
    `storage_pool_id` bigint unsigned NULL,
    `datastore_ref` varchar(2048) NULL,
    `format` varchar(64) NULL,
    `size_bytes` bigint NULL,
    `checksum` varchar(255) NULL,
    `parent_artifact_id` bigint unsigned NULL,
    `state` varchar(64) NULL,
    `details_json` text NULL,
    `created` datetime NOT NULL,
    `updated` datetime NULL,
    `removed` datetime NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_dr_restore_point_artifact__uuid` (`uuid`),
    KEY `i_dr_restore_point_artifact__restore_point_id` (`restore_point_id`),
    KEY `i_dr_restore_point_artifact__storage_pool_id` (`storage_pool_id`),
    KEY `i_dr_restore_point_artifact__parent_artifact_id` (`parent_artifact_id`),
    CONSTRAINT `fk_dr_rp_artifact__restore_point_id` FOREIGN KEY (`restore_point_id`) REFERENCES `dr_restore_point` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_dr_rp_artifact__storage_pool_id` FOREIGN KEY (`storage_pool_id`) REFERENCES `storage_pool` (`id`) ON DELETE SET NULL,
    CONSTRAINT `fk_dr_rp_artifact__parent_artifact_id` FOREIGN KEY (`parent_artifact_id`) REFERENCES `dr_restore_point_artifact` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS `cloud`.`dr_replica` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `uuid` varchar(40) NOT NULL,
    `plan_id` bigint unsigned NOT NULL,
    `target_site_id` bigint unsigned NOT NULL,
    `target_vm_id` bigint unsigned NULL,
    `target_external_ref` varchar(2048) NULL,
    `target_vm_name` varchar(255) NULL,
    `state` varchar(64) NULL,
    `power_state` varchar(64) NULL,
    `hypervisor_type` varchar(64) NULL,
    `active_side` varchar(64) NULL,
    `runtime_state_json` text NULL,
    `created` datetime NOT NULL,
    `updated` datetime NULL,
    `removed` datetime NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_dr_replica__uuid` (`uuid`),
    KEY `i_dr_replica__plan_id` (`plan_id`),
    KEY `i_dr_replica__target_site_id` (`target_site_id`),
    KEY `i_dr_replica__target_vm_id` (`target_vm_id`),
    CONSTRAINT `fk_dr_replica__plan_id` FOREIGN KEY (`plan_id`) REFERENCES `dr_plan` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_dr_replica__target_site_id` FOREIGN KEY (`target_site_id`) REFERENCES `dr_site` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_dr_replica__target_vm_id` FOREIGN KEY (`target_vm_id`) REFERENCES `vm_instance` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS `cloud`.`dr_replica_disk` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `uuid` varchar(40) NOT NULL,
    `replica_id` bigint unsigned NOT NULL,
    `source_volume_id` bigint unsigned NULL,
    `target_volume_id` bigint unsigned NULL,
    `source_disk_ref` varchar(2048) NULL,
    `target_disk_ref` varchar(2048) NULL,
    `disk_label` varchar(255) NULL,
    `format` varchar(64) NULL,
    `state` varchar(64) NULL,
    `size_bytes` bigint NULL,
    `details_json` text NULL,
    `created` datetime NOT NULL,
    `updated` datetime NULL,
    `removed` datetime NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_dr_replica_disk__uuid` (`uuid`),
    KEY `i_dr_replica_disk__replica_id` (`replica_id`),
    KEY `i_dr_replica_disk__source_volume_id` (`source_volume_id`),
    KEY `i_dr_replica_disk__target_volume_id` (`target_volume_id`),
    CONSTRAINT `fk_dr_replica_disk__replica_id` FOREIGN KEY (`replica_id`) REFERENCES `dr_replica` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_dr_replica_disk__source_volume_id` FOREIGN KEY (`source_volume_id`) REFERENCES `volumes` (`id`) ON DELETE SET NULL,
    CONSTRAINT `fk_dr_replica_disk__target_volume_id` FOREIGN KEY (`target_volume_id`) REFERENCES `volumes` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS `cloud`.`dr_run` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `uuid` varchar(40) NOT NULL,
    `plan_id` bigint unsigned NOT NULL,
    `run_type` varchar(64) NOT NULL,
    `state` varchar(64) NULL,
    `idempotency_key` varchar(255) NULL,
    `request_json` text NULL,
    `requested_by_user_id` bigint unsigned NULL,
    `async_job_id` bigint unsigned NULL,
    `external_job_ref` varchar(2048) NULL,
    `engine_accepted` tinyint(1) NOT NULL DEFAULT 0,
    `accepted_at` datetime NULL,
    `accepted_cycle_sequence` bigint unsigned NULL,
    `accepted_cycle_token` varchar(255) NULL,
    `dispatch_started` datetime NULL,
    `dispatch_completed` datetime NULL,
    `projection_state` varchar(64) NULL,
    `projection_checked` datetime NULL,
    `retryable` tinyint(1) NOT NULL DEFAULT 0,
    `retry_count` int NOT NULL DEFAULT 0,
    `retry_after_seconds` int NULL,
    `next_retry_at` datetime NULL,
    `last_status_json` mediumtext NULL,
    `current_step_name` varchar(255) NULL,
    `error_code` varchar(128) NULL,
    `error_message` text NULL,
    `started` datetime NULL,
    `completed` datetime NULL,
    `created` datetime NOT NULL,
    `updated` datetime NULL,
    `removed` datetime NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_dr_run__uuid` (`uuid`),
    KEY `i_dr_run__plan_id` (`plan_id`),
    KEY `i_dr_run__plan_created` (`plan_id`, `created`),
    KEY `i_dr_run__plan_state_completed` (`plan_id`, `state`, `completed`),
    KEY `i_dr_run__idempotency` (`plan_id`, `idempotency_key`),
    KEY `i_dr_run__async_job_id` (`async_job_id`),
    CONSTRAINT `fk_dr_run__plan_id` FOREIGN KEY (`plan_id`) REFERENCES `dr_plan` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_dr_run__requested_by_user_id` FOREIGN KEY (`requested_by_user_id`) REFERENCES `user` (`id`) ON DELETE SET NULL,
    CONSTRAINT `fk_dr_run__async_job_id` FOREIGN KEY (`async_job_id`) REFERENCES `async_job` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS `cloud`.`dr_run_step` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `uuid` varchar(40) NOT NULL,
    `run_id` bigint unsigned NOT NULL,
    `step_name` varchar(255) NOT NULL,
    `step_order` int NOT NULL DEFAULT 0,
    `state` varchar(64) NULL,
    `progress` int NULL,
    `started` datetime NULL,
    `completed` datetime NULL,
    `details_json` text NULL,
    `error_code` varchar(128) NULL,
    `error_message` text NULL,
    `created` datetime NOT NULL,
    `updated` datetime NULL,
    `removed` datetime NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_dr_run_step__uuid` (`uuid`),
    KEY `i_dr_run_step__run_id` (`run_id`),
    KEY `i_dr_run_step__run_order` (`run_id`, `step_order`),
    CONSTRAINT `fk_dr_run_step__run_id` FOREIGN KEY (`run_id`) REFERENCES `dr_run` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_run', 'engine_accepted', 'tinyint(1) NOT NULL DEFAULT 0 AFTER `external_job_ref`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_run', 'accepted_at', 'datetime NULL AFTER `engine_accepted`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_run', 'dispatch_started', 'datetime NULL AFTER `accepted_at`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_run', 'dispatch_completed', 'datetime NULL AFTER `dispatch_started`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_run', 'projection_state', 'varchar(64) NULL AFTER `dispatch_completed`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_run', 'projection_checked', 'datetime NULL AFTER `projection_state`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_run', 'retryable', 'tinyint(1) NOT NULL DEFAULT 0 AFTER `projection_checked`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_run', 'retry_count', 'int NOT NULL DEFAULT 0 AFTER `retryable`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_run', 'retry_after_seconds', 'int NULL AFTER `retry_count`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_run', 'next_retry_at', 'datetime NULL AFTER `retry_after_seconds`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_run', 'last_status_json', 'mediumtext NULL AFTER `next_retry_at`');
CALL `cloud`.`IDEMPOTENT_ADD_KEY`('i_dr_run__plan_created', 'cloud.dr_run', '(`plan_id`, `created`)');
CALL `cloud`.`IDEMPOTENT_ADD_KEY`('i_dr_run__plan_state_completed', 'cloud.dr_run', '(`plan_id`, `state`, `completed`)');

CALL `cloud`.`IDEMPOTENT_ADD_KEY`('i_dr_run_step__run_order', 'cloud.dr_run_step', '(`run_id`, `step_order`)');

CREATE TABLE IF NOT EXISTS `cloud`.`dr_event` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `uuid` varchar(40) NOT NULL,
    `plan_id` bigint unsigned NULL,
    `run_id` bigint unsigned NULL,
    `event_type` varchar(128) NOT NULL,
    `severity` varchar(32) NOT NULL,
    `source` varchar(64) NOT NULL,
    `message` text NULL,
    `details_json` text NULL,
    `created` datetime NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_dr_event__uuid` (`uuid`),
    KEY `i_dr_event__plan_id` (`plan_id`),
    KEY `i_dr_event__run_id` (`run_id`),
    KEY `i_dr_event__created` (`created`),
    CONSTRAINT `fk_dr_event__plan_id` FOREIGN KEY (`plan_id`) REFERENCES `dr_plan` (`id`) ON DELETE SET NULL,
    CONSTRAINT `fk_dr_event__run_id` FOREIGN KEY (`run_id`) REFERENCES `dr_run` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS `cloud`.`dr_plan_view_cache` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `plan_id` bigint unsigned NOT NULL,
    `snapshot_version` int unsigned NOT NULL DEFAULT 1,
    `snapshot_json` mediumtext NOT NULL,
    `projection_state` varchar(32) NOT NULL DEFAULT 'READY',
    `last_error` varchar(4096) DEFAULT NULL,
    `last_refresh_error_code` varchar(255) DEFAULT NULL,
    `last_refresh_error_message` varchar(4096) DEFAULT NULL,
    `last_refresh_failed_at` datetime DEFAULT NULL,
    `generated_at` datetime NOT NULL,
    `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated` datetime DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_dr_plan_view_cache__plan_id` (`plan_id`),
    KEY `i_dr_plan_view_cache__generated_at` (`generated_at`),
    CONSTRAINT `fk_dr_plan_view_cache__plan_id` FOREIGN KEY (`plan_id`) REFERENCES `dr_plan` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `cloud`.`dr_plan_runtime` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `plan_id` bigint unsigned NOT NULL,
    `engine_run_uuid` varchar(40) DEFAULT NULL,
    `runtime_generation` bigint unsigned NOT NULL DEFAULT 0,
    `scheduler_state` varchar(32) DEFAULT NULL,
    `scheduler_desired_state` varchar(32) NOT NULL DEFAULT 'STOPPED',
    `scheduler_service_unit` varchar(255) DEFAULT NULL,
    `scheduler_unit_active_state` varchar(32) DEFAULT NULL,
    `scheduler_unit_sub_state` varchar(32) DEFAULT NULL,
    `scheduler_unit_main_pid` bigint unsigned DEFAULT NULL,
    `scheduler_cgroup` varchar(512) DEFAULT NULL,
    `scheduler_recovery_state` varchar(32) NOT NULL DEFAULT 'NONE',
    `scheduler_recovery_trigger` varchar(64) DEFAULT NULL,
    `scheduler_recovery_attempts` int unsigned NOT NULL DEFAULT 0,
    `scheduler_recovery_error_code` varchar(128) DEFAULT NULL,
    `scheduler_recovery_error_message` varchar(4096) DEFAULT NULL,
    `scheduler_recovered_at` datetime DEFAULT NULL,
    `scheduler_pid_alive` tinyint(1) NOT NULL DEFAULT 0,
    `worker_state` varchar(32) DEFAULT NULL,
    `current_cycle_sequence` bigint unsigned DEFAULT NULL,
    `current_cycle_state` varchar(32) DEFAULT NULL,
    `current_cycle_mode` varchar(32) DEFAULT NULL,
    `baseline_state` varchar(32) DEFAULT NULL,
    `reseed_reason` varchar(128) DEFAULT NULL,
    `protection_state` varchar(32) NOT NULL DEFAULT 'UNKNOWN',
    `freshness_state` varchar(32) NOT NULL DEFAULT 'UNKNOWN',
    `scheduler_next_run_at` datetime DEFAULT NULL,
    `scheduler_execution_budget_seconds` int unsigned DEFAULT NULL,
    `scheduler_cycle_wall_duration_seconds` int unsigned DEFAULT NULL,
    `last_status_at` datetime DEFAULT NULL,
    `last_source_checkpoint_at` datetime DEFAULT NULL,
    `last_target_durable_at` datetime DEFAULT NULL,
    `rpo_age_seconds` bigint unsigned DEFAULT NULL,
    `rpo_overdue` tinyint(1) NOT NULL DEFAULT 0,
    `error_code` varchar(128) DEFAULT NULL,
    `error_message` varchar(4096) DEFAULT NULL,
    `status_json` mediumtext,
    `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated` datetime DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_dr_plan_runtime__plan_id` (`plan_id`),
    KEY `i_dr_plan_runtime__state_updated` (`protection_state`, `freshness_state`, `updated`),
    CONSTRAINT `fk_dr_plan_runtime__plan_id` FOREIGN KEY (`plan_id`) REFERENCES `dr_plan` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `cloud`.`dr_sync_cycle` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `uuid` varchar(40) NOT NULL,
    `plan_id` bigint unsigned NOT NULL,
    `run_id` bigint unsigned DEFAULT NULL,
    `engine_run_uuid` varchar(40) NOT NULL,
    `sequence` bigint unsigned NOT NULL,
    `requested_mode` varchar(32) DEFAULT NULL,
    `effective_mode` varchar(32) DEFAULT NULL,
    `state` varchar(32) NOT NULL,
    `commit_state` varchar(32) DEFAULT NULL,
    `baseline_generation` bigint unsigned DEFAULT NULL,
    `baseline_state` varchar(32) DEFAULT NULL,
    `reseed_reason` varchar(128) DEFAULT NULL,
    `incremental_verified` tinyint(1) DEFAULT NULL,
    `metrics_estimated` tinyint(1) DEFAULT NULL,
    `virtual_bytes` bigint unsigned DEFAULT NULL,
    `changed_bytes` bigint unsigned DEFAULT NULL,
    `source_read_bytes` bigint unsigned DEFAULT NULL,
    `target_written_bytes` bigint unsigned DEFAULT NULL,
    `transfer_payload_bytes` bigint unsigned DEFAULT NULL,
    `changed_extent_count` bigint unsigned DEFAULT NULL,
    `duration_ms` bigint unsigned DEFAULT NULL,
    `throughput_bps` bigint unsigned DEFAULT NULL,
    `source_checkpoint_at` datetime DEFAULT NULL,
    `target_durable_at` datetime DEFAULT NULL,
    `error_code` varchar(128) DEFAULT NULL,
    `error_message` varchar(4096) DEFAULT NULL,
    `started` datetime DEFAULT NULL,
    `completed` datetime DEFAULT NULL,
    `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated` datetime DEFAULT NULL,
    `removed` datetime DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_dr_sync_cycle__uuid` (`uuid`),
    UNIQUE KEY `uk_dr_sync_cycle__plan_sequence` (`plan_id`, `sequence`),
    KEY `i_dr_sync_cycle__plan_run_sequence` (`plan_id`, `engine_run_uuid`, `sequence`),
    KEY `i_dr_sync_cycle__plan_state_updated` (`plan_id`, `state`, `updated`),
    CONSTRAINT `fk_dr_sync_cycle__plan_id` FOREIGN KEY (`plan_id`) REFERENCES `dr_plan` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_dr_sync_cycle__run_id` FOREIGN KEY (`run_id`) REFERENCES `dr_run` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'last_mode_decision_code', 'varchar(128) NULL AFTER `reseed_reason`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'consecutive_automatic_reseed_count', 'int unsigned NOT NULL DEFAULT 0 AFTER `last_mode_decision_code`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'latest_completed_cycle_sequence', 'bigint unsigned NULL AFTER `consecutive_automatic_reseed_count`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'latest_completed_incremental_verified', 'tinyint(1) NULL AFTER `latest_completed_cycle_sequence`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'scheduler_next_run_at', 'datetime NULL AFTER `freshness_state`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'scheduler_execution_budget_seconds', 'int unsigned NULL AFTER `scheduler_next_run_at`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'scheduler_cycle_wall_duration_seconds', 'int unsigned NULL AFTER `scheduler_execution_budget_seconds`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_sync_cycle', 'automatic_reseed', 'tinyint(1) NULL AFTER `reseed_reason`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_sync_cycle', 'mode_decision_code', 'varchar(128) NULL AFTER `automatic_reseed`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_sync_cycle', 'invalid_baseline_disk_count', 'int unsigned NULL AFTER `mode_decision_code`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_sync_cycle', 'cycle_token', 'varchar(255) NULL AFTER `sequence`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'projection_integrity_state', 'varchar(32) NULL AFTER `latest_completed_incremental_verified`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'projection_integrity_code', 'varchar(128) NULL AFTER `projection_integrity_state`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'projection_integrity_sequence', 'bigint unsigned NULL AFTER `projection_integrity_code`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'scheduler_session_uuid', 'varchar(40) NULL AFTER `scheduler_pid_alive`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'scheduler_lease_epoch', 'bigint unsigned NOT NULL DEFAULT 0 AFTER `scheduler_session_uuid`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'authority_sequence', 'bigint unsigned NOT NULL DEFAULT 0 AFTER `scheduler_lease_epoch`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'plan_cycle_sequence', 'bigint unsigned NULL AFTER `authority_sequence`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'scheduler_health_state', 'varchar(32) NULL AFTER `plan_cycle_sequence`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'replication_activity_state', 'varchar(32) NULL AFTER `scheduler_health_state`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'active_worker_run_uuid', 'varchar(40) NULL AFTER `replication_activity_state`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'active_worker_pid', 'bigint unsigned NULL AFTER `active_worker_run_uuid`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'active_worker_start_ticks', 'bigint unsigned NULL AFTER `active_worker_pid`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'worker_heartbeat_at', 'datetime NULL AFTER `active_worker_start_ticks`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'control_request_run_uuid', 'varchar(40) NULL AFTER `worker_heartbeat_at`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'owner_matched', 'tinyint(1) NOT NULL DEFAULT 0 AFTER `control_request_run_uuid`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'worker_identity_state', 'varchar(32) NULL AFTER `worker_state`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'worker_liveness_state', 'varchar(32) NULL AFTER `worker_identity_state`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'worker_launch_nonce', 'varchar(64) NULL AFTER `worker_liveness_state`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'worker_generation', 'bigint unsigned NULL AFTER `worker_launch_nonce`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'transfer_activity_state', 'varchar(32) NULL AFTER `worker_generation`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'transfer_payload_bytes', 'bigint unsigned NULL AFTER `transfer_activity_state`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'owned_process_count', 'int unsigned NOT NULL DEFAULT 0 AFTER `transfer_payload_bytes`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'runtime_endpoints_drained', 'tinyint(1) NOT NULL DEFAULT 0 AFTER `owned_process_count`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'reconciliation_state', 'varchar(32) NULL AFTER `runtime_endpoints_drained`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'reconciliation_run_uuid', 'varchar(40) NULL AFTER `reconciliation_state`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'reconciliation_checks', 'int unsigned NOT NULL DEFAULT 0 AFTER `reconciliation_run_uuid`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'terminal_source', 'varchar(32) NULL AFTER `reconciliation_checks`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'terminal_version', 'int unsigned NULL AFTER `terminal_source`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'terminal_authoritative', 'tinyint(1) NOT NULL DEFAULT 0 AFTER `terminal_version`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_run', 'terminal_source', 'varchar(32) NULL AFTER `error_message`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_run', 'terminal_version', 'int unsigned NULL AFTER `terminal_source`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_run', 'terminal_authoritative', 'tinyint(1) NOT NULL DEFAULT 0 AFTER `terminal_version`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_run', 'accepted_cycle_sequence', 'bigint unsigned NULL AFTER `accepted_at`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_run', 'accepted_cycle_token', 'varchar(255) NULL AFTER `accepted_cycle_sequence`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'scheduler_desired_state', 'varchar(32) NOT NULL DEFAULT ''STOPPED'' AFTER `scheduler_state`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'scheduler_service_unit', 'varchar(255) NULL AFTER `scheduler_desired_state`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'scheduler_unit_active_state', 'varchar(32) NULL AFTER `scheduler_service_unit`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'scheduler_unit_sub_state', 'varchar(32) NULL AFTER `scheduler_unit_active_state`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'scheduler_unit_main_pid', 'bigint unsigned NULL AFTER `scheduler_unit_sub_state`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'scheduler_cgroup', 'varchar(512) NULL AFTER `scheduler_unit_main_pid`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'scheduler_recovery_state', 'varchar(32) NOT NULL DEFAULT ''NONE'' AFTER `scheduler_cgroup`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'scheduler_recovery_trigger', 'varchar(64) NULL AFTER `scheduler_recovery_state`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'scheduler_recovery_attempts', 'int unsigned NOT NULL DEFAULT 0 AFTER `scheduler_recovery_trigger`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'scheduler_recovery_error_code', 'varchar(128) NULL AFTER `scheduler_recovery_attempts`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'scheduler_recovery_error_message', 'varchar(4096) NULL AFTER `scheduler_recovery_error_code`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'scheduler_recovered_at', 'datetime NULL AFTER `scheduler_recovery_error_message`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'nbd_teardown_state', 'varchar(32) NULL AFTER `projection_integrity_sequence`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'nbd_quarantined_device_count', 'int unsigned NOT NULL DEFAULT 0 AFTER `nbd_teardown_state`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'nbd_teardown_error_code', 'varchar(128) NULL AFTER `nbd_quarantined_device_count`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'nbd_teardown_error_message', 'varchar(4096) NULL AFTER `nbd_teardown_error_code`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_sync_cycle', 'nbd_teardown_state', 'varchar(32) NULL AFTER `throughput_bps`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_sync_cycle', 'nbd_teardown_started_at', 'datetime NULL AFTER `nbd_teardown_state`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_sync_cycle', 'nbd_teardown_completed_at', 'datetime NULL AFTER `nbd_teardown_started_at`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_sync_cycle', 'nbd_teardown_duration_ms', 'bigint unsigned NULL AFTER `nbd_teardown_completed_at`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_sync_cycle', 'nbd_source_device_count', 'int unsigned NULL AFTER `nbd_teardown_duration_ms`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_sync_cycle', 'nbd_target_device_count', 'int unsigned NULL AFTER `nbd_source_device_count`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_sync_cycle', 'nbd_quarantined_device_count', 'int unsigned NOT NULL DEFAULT 0 AFTER `nbd_target_device_count`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_sync_cycle', 'nbd_teardown_error_code', 'varchar(128) NULL AFTER `nbd_quarantined_device_count`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_sync_cycle', 'nbd_teardown_error_message', 'varchar(4096) NULL AFTER `nbd_teardown_error_code`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_sync_cycle', 'scheduler_session_uuid', 'varchar(40) NULL AFTER `engine_run_uuid`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_sync_cycle', 'scheduler_lease_epoch', 'bigint unsigned NULL AFTER `scheduler_session_uuid`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_sync_cycle', 'authority_sequence', 'bigint unsigned NULL AFTER `scheduler_lease_epoch`');


CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_view_cache', 'last_refresh_error_code', 'varchar(255) DEFAULT NULL AFTER `last_error`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_view_cache', 'last_refresh_error_message', 'varchar(4096) DEFAULT NULL AFTER `last_refresh_error_code`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_view_cache', 'last_refresh_failed_at', 'datetime DEFAULT NULL AFTER `last_refresh_error_message`');

CALL `cloud`.`IDEMPOTENT_ADD_KEY`('i_dr_plan_runtime__nbd_teardown_state', 'cloud.dr_plan_runtime', '(`nbd_teardown_state`, `updated`)');

CALL `cloud`.`IDEMPOTENT_ADD_KEY`('i_dr_event__plan_created_id', 'cloud.dr_event', '(`plan_id`, `created`, `id`)');

UPDATE `cloud`.`volumes` v
JOIN `cloud`.`dr_replica_disk` d ON d.target_volume_id = v.id AND d.removed IS NULL
JOIN `cloud`.`storage_pool` p ON p.id = v.pool_id AND p.removed IS NULL
SET v.format = 'RAW'
WHERE v.removed IS NULL
  AND UPPER(p.pool_type) = 'RBD'
  AND UPPER(COALESCE(v.format, '')) <> 'RAW';

-- Allow the default Resource Admin role to use Cross Hypervisor DR APIs.
INSERT INTO `cloud`.`role_permissions` (`uuid`, `role_id`, `rule`, `permission`, `sort_order`)
SELECT UUID(), r.id, dr_api.rule, 'ALLOW',
       (SELECT COALESCE(MAX(rp.sort_order), 0) + 1
          FROM `cloud`.`role_permissions` rp
         WHERE rp.role_id = r.id)
FROM `cloud`.`roles` r
JOIN (
    SELECT 'adoptDrReplica' AS rule UNION ALL
    SELECT 'cancelDrRun' UNION ALL
    SELECT 'checkDrSite' UNION ALL
    SELECT 'clearDisasterRecoveryCluster' UNION ALL
    SELECT 'confirmDrFenceClear' UNION ALL
    SELECT 'connectivityTestsDisasterRecovery' UNION ALL
    SELECT 'createDisasterRecoveryCluster' UNION ALL
    SELECT 'createDisasterRecoveryClusterVm' UNION ALL
    SELECT 'createDrPlan' UNION ALL
    SELECT 'createDrSite' UNION ALL
    SELECT 'deleteDisasterRecoveryCluster' UNION ALL
    SELECT 'deleteDisasterRecoveryClusterVm' UNION ALL
    SELECT 'deleteDrPlan' UNION ALL
    SELECT 'deleteDrSite' UNION ALL
    SELECT 'demoteDisasterRecoveryCluster' UNION ALL
    SELECT 'demoteDisasterRecoveryClusterVm' UNION ALL
    SELECT 'discoverDrPlanInventory' UNION ALL
    SELECT 'discoverDrSiteInventory' UNION ALL
    SELECT 'disableDisasterRecoveryCluster' UNION ALL
    SELECT 'disableDrPlan' UNION ALL
    SELECT 'enableDisasterRecoveryCluster' UNION ALL
    SELECT 'enableDrPlan' UNION ALL
    SELECT 'getDisasterRecoveryClusterList' UNION ALL
    SELECT 'getDrPlan' UNION ALL
    SELECT 'getDrProtectionView' UNION ALL
    SELECT 'getDrRun' UNION ALL
    SELECT 'getDrSite' UNION ALL
    SELECT 'listDrEvents' UNION ALL
    SELECT 'listDrPlans' UNION ALL
    SELECT 'listDrReplicas' UNION ALL
    SELECT 'listDrRestorePoints' UNION ALL
    SELECT 'listDrRuns' UNION ALL
    SELECT 'listDrRunSteps' UNION ALL
    SELECT 'listDrSiteHealthChecks' UNION ALL
    SELECT 'listDrSites' UNION ALL
    SELECT 'pauseDrSync' UNION ALL
    SELECT 'previewDrPlanSpec' UNION ALL
    SELECT 'promoteDisasterRecoveryCluster' UNION ALL
    SELECT 'promoteDisasterRecoveryClusterVm' UNION ALL
    SELECT 'releaseDrProtection' UNION ALL
    SELECT 'refreshDrProtectionView' UNION ALL
    SELECT 'recoverDrSync' UNION ALL
    SELECT 'resumeDrSync' UNION ALL
    SELECT 'resyncDisasterRecoveryCluster' UNION ALL
    SELECT 'startDisasterRecoveryClusterVm' UNION ALL
    SELECT 'startDrFailback' UNION ALL
    SELECT 'startDrFailover' UNION ALL
    SELECT 'startDrReprotect' UNION ALL
    SELECT 'startDrSync' UNION ALL
    SELECT 'startDrTestFailover' UNION ALL
    SELECT 'stopDisasterRecoveryClusterVm' UNION ALL
    SELECT 'stopDrTestFailover' UNION ALL
    SELECT 'takeSnapshotDisasterRecoveryClusterVm' UNION ALL
    SELECT 'updateDisasterRecoveryCluster' UNION ALL
    SELECT 'updateDisasterRecoveryClusterVm' UNION ALL
    SELECT 'updateDrPlan' UNION ALL
    SELECT 'updateDrSite'
) dr_api
WHERE r.name = 'Resource Admin'
  AND NOT EXISTS (
        SELECT 1
          FROM `cloud`.`role_permissions` existing
         WHERE existing.role_id = r.id
           AND existing.rule = dr_api.rule
      );
CREATE TABLE IF NOT EXISTS `cloud`.`dr_cutover_session` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT, `uuid` varchar(40) NOT NULL, `plan_id` bigint unsigned NOT NULL,
  `run_id` bigint unsigned NOT NULL, `mode` varchar(16) NOT NULL, `checkpoint_sequence` bigint unsigned DEFAULT NULL,
  `state` varchar(64) NOT NULL, `guest_os_family` varchar(32), `guest_preparation_state` varchar(64),
  `virtio_state` varchar(32), `secure_boot_state` varchar(32), `domain_name` varchar(255),
  `boot_validation_state` varchar(64), `source_fence_state` varchar(32), `source_power_state` varchar(32),
  `manifest_schema_version` varchar(64), `manifest_sha256` varchar(64),
  `target_disk_count` int unsigned, `scheduler_recovery_state` varchar(32),
  `cloud_promotion_state` varchar(32), `target_power_state` varchar(32),
  `target_power_on_at` datetime, `boot_validated_at` datetime,
  `engine_ack_state` varchar(32), `engine_ack_at` datetime,
  `cloud_authority_generation` bigint unsigned, `commit_contract_version` varchar(64),
  `engine_session_id` varchar(255), `commit_attempt_id` varchar(64),
  `commit_envelope_sha256` varchar(64), `commit_state` varchar(32), `completed_at` datetime,
  `authority_ended_at` datetime, `authority_ended_by_run_id` bigint unsigned,
  `cleanup_required` tinyint(1) NOT NULL DEFAULT 0,
  `details_json` mediumtext, `error_code` varchar(128), `error_message` varchar(1024),
  `created` datetime NOT NULL, `updated` datetime NOT NULL, `removed` datetime,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_dr_cutover_session_uuid` (`uuid`),
  KEY `idx_dr_cutover_session_plan_active` (`plan_id`,`removed`), KEY `idx_dr_cutover_session_run` (`run_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_cutover_session', 'source_fence_state', 'varchar(32) DEFAULT NULL AFTER boot_validation_state');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_cutover_session', 'source_power_state', 'varchar(32) DEFAULT NULL AFTER source_fence_state');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_cutover_session', 'manifest_schema_version', 'varchar(64) DEFAULT NULL AFTER source_power_state');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_cutover_session', 'manifest_sha256', 'varchar(64) DEFAULT NULL AFTER manifest_schema_version');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_cutover_session', 'target_disk_count', 'int unsigned DEFAULT NULL AFTER manifest_sha256');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_cutover_session', 'scheduler_recovery_state', 'varchar(32) DEFAULT NULL AFTER target_disk_count');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_cutover_session', 'cloud_promotion_state', 'varchar(32) DEFAULT NULL AFTER scheduler_recovery_state');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_cutover_session', 'target_power_state', 'varchar(32) DEFAULT NULL AFTER cloud_promotion_state');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_cutover_session', 'target_power_on_at', 'datetime DEFAULT NULL AFTER target_power_state');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_cutover_session', 'boot_validated_at', 'datetime DEFAULT NULL AFTER target_power_on_at');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_cutover_session', 'engine_ack_state', 'varchar(32) DEFAULT NULL AFTER boot_validated_at');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_cutover_session', 'engine_ack_at', 'datetime DEFAULT NULL AFTER engine_ack_state');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_cutover_session', 'cloud_authority_generation', 'bigint unsigned DEFAULT NULL AFTER engine_ack_at');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_cutover_session', 'commit_contract_version', 'varchar(64) DEFAULT NULL AFTER cloud_authority_generation');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_cutover_session', 'engine_session_id', 'varchar(255) DEFAULT NULL AFTER commit_contract_version');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_cutover_session', 'commit_attempt_id', 'varchar(64) DEFAULT NULL AFTER engine_session_id');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_cutover_session', 'commit_envelope_sha256', 'varchar(64) DEFAULT NULL AFTER commit_attempt_id');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_cutover_session', 'commit_state', 'varchar(32) DEFAULT NULL AFTER commit_envelope_sha256');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_cutover_session', 'completed_at', 'datetime DEFAULT NULL AFTER cloud_authority_generation');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_cutover_session', 'authority_ended_at', 'datetime DEFAULT NULL AFTER completed_at');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_cutover_session', 'authority_ended_by_run_id', 'bigint unsigned DEFAULT NULL AFTER authority_ended_at');
CALL `cloud`.`IDEMPOTENT_ADD_KEY`('idx_dr_cutover_session_plan_state_active', 'cloud.dr_cutover_session', '(`plan_id`,`state`,`removed`,`id`)');
CALL `cloud`.`IDEMPOTENT_ADD_KEY`('idx_dr_cutover_session_authority_end_run', 'cloud.dr_cutover_session', '(`authority_ended_by_run_id`)');
CALL `cloud`.`IDEMPOTENT_ADD_KEY`('i_dr_run_step__run_name_removed', 'cloud.dr_run_step', '(`run_id`,`step_name`,`removed`)');
UPDATE `cloud`.`dr_cutover_session` cutover
JOIN `cloud`.`dr_plan` plan ON plan.id = cutover.plan_id AND plan.removed IS NULL
JOIN (
  SELECT plan_id, MAX(id) AS run_id
  FROM `cloud`.`dr_run`
  WHERE run_type = 'FAILBACK' AND state = 'SUCCEEDED' AND removed IS NULL
  GROUP BY plan_id
) latest_failback ON latest_failback.plan_id = cutover.plan_id
JOIN `cloud`.`dr_run` failback_run ON failback_run.id = latest_failback.run_id
SET cutover.state = 'FAILED_BACK',
    cutover.authority_ended_at = failback_run.completed,
    cutover.authority_ended_by_run_id = failback_run.id,
    cutover.updated = NOW()
WHERE cutover.removed IS NULL
  AND cutover.authority_ended_at IS NULL
  AND cutover.cloud_promotion_state = 'PROMOTED'
  AND cutover.engine_ack_state = 'ACKNOWLEDGED'
  AND plan.active_side = 'SOURCE'
  AND failback_run.completed > COALESCE(cutover.completed_at, cutover.created);
CREATE TABLE IF NOT EXISTS `cloud`.`dr_cutover_disk` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT, `session_id` bigint unsigned NOT NULL, `disk_index` int unsigned NOT NULL,
  `provider` varchar(32) NOT NULL, `checkpoint_ref` varchar(1024) NOT NULL, `writable_ref` varchar(1024),
  `rollback_ref` varchar(1024), `state` varchar(64) NOT NULL,
  `target_volume_id` bigint unsigned DEFAULT NULL, `target_volume_uuid` varchar(40) DEFAULT NULL,
  `checkpoint_sequence` bigint unsigned DEFAULT NULL, `manifest_sha256` varchar(64) DEFAULT NULL,
  `details_json` mediumtext,
  `created` datetime NOT NULL, `updated` datetime NOT NULL, `removed` datetime,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_dr_cutover_disk_session_index` (`session_id`,`disk_index`),
  KEY `idx_dr_cutover_disk_session_active` (`session_id`,`removed`),
  KEY `idx_dr_cutover_disk_target_volume` (`target_volume_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_cutover_disk', 'target_volume_id', 'bigint unsigned DEFAULT NULL AFTER `state`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_cutover_disk', 'target_volume_uuid', 'varchar(40) DEFAULT NULL AFTER `target_volume_id`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_cutover_disk', 'checkpoint_sequence', 'bigint unsigned DEFAULT NULL AFTER `target_volume_uuid`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_cutover_disk', 'manifest_sha256', 'varchar(64) DEFAULT NULL AFTER `checkpoint_sequence`');
CALL `cloud`.`IDEMPOTENT_ADD_KEY`('idx_dr_cutover_disk_target_volume', 'cloud.dr_cutover_disk', '(`target_volume_id`)');
CREATE TABLE IF NOT EXISTS `cloud`.`dr_test_session` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `uuid` varchar(40) NOT NULL,
  `plan_id` bigint unsigned NOT NULL,
  `run_id` bigint unsigned NOT NULL,
  `cleanup_run_id` bigint unsigned DEFAULT NULL,
  `state` varchar(64) NOT NULL,
  `network_mode` varchar(32) DEFAULT NULL,
  `network_id` bigint unsigned DEFAULT NULL,
  `target_vm_id` bigint unsigned DEFAULT NULL,
  `target_vm_uuid` varchar(40) DEFAULT NULL,
  `target_vm_name` varchar(255) DEFAULT NULL,
  `checkpoint_sequence` bigint unsigned DEFAULT NULL,
  `restore_point_ref` varchar(1024) DEFAULT NULL,
  `validation_mode` varchar(32) DEFAULT NULL,
  `boot_timeout_seconds` int unsigned DEFAULT NULL,
  `artifact_contract_version` varchar(16) DEFAULT NULL,
  `artifact_manifest` mediumtext,
  `boot_validation_state` varchar(64) DEFAULT NULL,
  `cleanup_required` tinyint(1) NOT NULL DEFAULT 0,
  `error_code` varchar(128) DEFAULT NULL,
  `error_message` varchar(1024) DEFAULT NULL,
  `details_json` mediumtext,
  `created` datetime NOT NULL,
  `updated` datetime NOT NULL,
  `removed` datetime DEFAULT NULL,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_dr_test_session_uuid` (`uuid`),
  KEY `idx_dr_test_session_plan_active` (`plan_id`,`removed`),
  KEY `idx_dr_test_session_run` (`run_id`),
  KEY `idx_dr_test_session_vm` (`target_vm_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_test_session', 'restore_point_ref', 'varchar(1024) NULL AFTER `checkpoint_sequence`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_test_session', 'validation_mode', 'varchar(32) NULL AFTER `restore_point_ref`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_test_session', 'boot_timeout_seconds', 'int unsigned NULL AFTER `validation_mode`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_test_session', 'artifact_contract_version', 'varchar(16) NULL AFTER `boot_timeout_seconds`');

CREATE TABLE IF NOT EXISTS `cloud`.`dr_test_disk` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `session_id` bigint unsigned NOT NULL,
  `disk_index` int NOT NULL,
  `provider` varchar(32) DEFAULT NULL,
  `artifact_ref` varchar(1024) DEFAULT NULL,
  `target_volume_id` bigint unsigned DEFAULT NULL,
  `target_volume_uuid` varchar(40) DEFAULT NULL,
  `state` varchar(64) NOT NULL,
  `details_json` mediumtext,
  `created` datetime NOT NULL,
  `updated` datetime NOT NULL,
  `removed` datetime DEFAULT NULL,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_dr_test_disk_session_index` (`session_id`,`disk_index`),
  KEY `idx_dr_test_disk_volume` (`target_volume_id`),
  CONSTRAINT `fk_dr_test_disk_session` FOREIGN KEY (`session_id`) REFERENCES `dr_test_session` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Failback routes and credentials are derived from registered DR sites.
UPDATE `cloud`.`dr_run`
SET `request_json` = JSON_REMOVE(`request_json`,
  '$.failbackTargetMoldType',
  '$.remoteMoldApiUrl', '$.remoteMoldApiKey', '$.remoteMoldSecretKey',
  '$.targetMoldApiUrl', '$.targetMoldApiKey', '$.targetMoldSecretKey')
WHERE `run_type` = 'FAILBACK'
  AND `request_json` IS NOT NULL
  AND JSON_VALID(`request_json`);

CREATE TABLE IF NOT EXISTS `cloud`.`dr_failback_session` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT, `uuid` varchar(40) NOT NULL,
  `plan_id` bigint unsigned NOT NULL, `run_id` bigint unsigned NOT NULL,
  `engine_session_id` varchar(255) NOT NULL, `checkpoint_sequence` bigint unsigned DEFAULT NULL,
  `authority_generation` bigint unsigned DEFAULT NULL, `state` varchar(64) NOT NULL,
  `acceptance_state` varchar(32), `failure_phase` varchar(64), `failed_component` varchar(128),
  `driver_exit_code` int, `baseline_file_state` varchar(32),
  `operation_intent` varchar(32), `requested_mode` varchar(32), `effective_mode` varchar(32),
  `mode_decision_code` varchar(64), `initial_seed_required` tinyint(1),
  `source_disk_probe_state` varchar(32), `source_disk_count` int,
  `target_writer_probe_state` varchar(32), `estimated_virtual_bytes` bigint unsigned,
  `worker_pid_alive` tinyint(1),
  `target_power_state` varchar(32), `source_power_state` varchar(32),
  `boot_validation_state` varchar(64), `engine_ack_state` varchar(32),
  `commit_attempt_id` varchar(64), `commit_outcome` varchar(32),
  `scheduler_generation` bigint unsigned, `scheduler_ack_generation` bigint unsigned,
  `scheduler_state` varchar(32), `rollback_state` varchar(32),
  `rollback_generation` bigint unsigned, `lifecycle_version` bigint unsigned NOT NULL DEFAULT 0,
  `resume_baseline_checkpoint_sequence` bigint unsigned DEFAULT NULL,
  `required_post_failback_checkpoint_sequence` bigint unsigned DEFAULT NULL,
  `post_failback_checkpoint_sequence` bigint unsigned DEFAULT NULL,
  `replication_direction` varchar(32) DEFAULT NULL,
  `provider_pair` varchar(64) DEFAULT NULL,
  `baseline_generation` bigint unsigned DEFAULT NULL,
  `baseline_state` varchar(32) DEFAULT NULL,
  `tracker_state` varchar(32) DEFAULT NULL,
  `writer_state` varchar(32) DEFAULT NULL,
  `target_written` tinyint(1) DEFAULT NULL,
  `write_verified` tinyint(1) DEFAULT NULL,
  `guest_compatibility_state` varchar(64) DEFAULT NULL,
  `data_ready_at` datetime, `target_stopped_at` datetime, `source_powered_on_at` datetime,
  `boot_validated_at` datetime, `engine_ack_at` datetime,
  `commit_requested_at` datetime, `commit_verified_at` datetime,
  `protection_resume_requested_at` datetime, `protection_resume_verified_at` datetime,
  `rollback_requested_at` datetime, `rollback_verified_at` datetime, `last_probe_at` datetime,
  `completed_at` datetime,
  `details_json` mediumtext, `error_code` varchar(128), `error_message` varchar(1024),
  `created` datetime NOT NULL, `updated` datetime NOT NULL, `removed` datetime DEFAULT NULL,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_dr_failback_session_uuid` (`uuid`),
  UNIQUE KEY `uk_dr_failback_session_run` (`run_id`),
  KEY `idx_dr_failback_session_plan_active` (`plan_id`,`removed`),
  KEY `idx_dr_failback_session_reconcile` (`state`,`last_probe_at`,`removed`,`plan_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_failback_session', 'commit_attempt_id', 'varchar(64) DEFAULT NULL AFTER `engine_ack_state`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_failback_session', 'commit_outcome', 'varchar(32) DEFAULT NULL AFTER `commit_attempt_id`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_failback_session', 'scheduler_generation', 'bigint unsigned DEFAULT NULL AFTER `commit_outcome`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_failback_session', 'scheduler_ack_generation', 'bigint unsigned DEFAULT NULL AFTER `scheduler_generation`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_failback_session', 'scheduler_state', 'varchar(32) DEFAULT NULL AFTER `scheduler_ack_generation`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_failback_session', 'rollback_state', 'varchar(32) DEFAULT NULL AFTER `scheduler_state`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_failback_session', 'rollback_generation', 'bigint unsigned DEFAULT NULL AFTER `rollback_state`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_failback_session', 'lifecycle_version', 'bigint unsigned NOT NULL DEFAULT 0 AFTER `rollback_generation`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_failback_session', 'resume_baseline_checkpoint_sequence', 'bigint unsigned DEFAULT NULL AFTER `lifecycle_version`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_failback_session', 'required_post_failback_checkpoint_sequence', 'bigint unsigned DEFAULT NULL AFTER `resume_baseline_checkpoint_sequence`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_failback_session', 'commit_requested_at', 'datetime DEFAULT NULL AFTER `engine_ack_at`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_failback_session', 'commit_verified_at', 'datetime DEFAULT NULL AFTER `commit_requested_at`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_failback_session', 'protection_resume_requested_at', 'datetime DEFAULT NULL AFTER `commit_verified_at`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_failback_session', 'protection_resume_verified_at', 'datetime DEFAULT NULL AFTER `protection_resume_requested_at`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_failback_session', 'rollback_requested_at', 'datetime DEFAULT NULL AFTER `commit_verified_at`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_failback_session', 'rollback_verified_at', 'datetime DEFAULT NULL AFTER `rollback_requested_at`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_failback_session', 'last_probe_at', 'datetime DEFAULT NULL AFTER `rollback_verified_at`');
CALL `cloud`.`IDEMPOTENT_ADD_KEY`('idx_dr_failback_session_reconcile', 'cloud.dr_failback_session', '(`state`,`last_probe_at`,`removed`,`plan_id`)');

CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_failback_session', 'replication_direction', 'varchar(32) NULL AFTER `post_failback_checkpoint_sequence`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_failback_session', 'provider_pair', 'varchar(64) NULL AFTER `replication_direction`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_failback_session', 'baseline_generation', 'bigint unsigned NULL AFTER `provider_pair`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_failback_session', 'baseline_state', 'varchar(32) NULL AFTER `baseline_generation`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_failback_session', 'tracker_state', 'varchar(32) NULL AFTER `baseline_state`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_failback_session', 'writer_state', 'varchar(32) NULL AFTER `tracker_state`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_failback_session', 'target_written', 'tinyint(1) NULL AFTER `writer_state`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_failback_session', 'write_verified', 'tinyint(1) NULL AFTER `target_written`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_failback_session', 'guest_compatibility_state', 'varchar(64) NULL AFTER `write_verified`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_failback_session', 'acceptance_state', 'varchar(32) NULL AFTER `state`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_failback_session', 'failure_phase', 'varchar(64) NULL AFTER `acceptance_state`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_failback_session', 'failed_component', 'varchar(128) NULL AFTER `failure_phase`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_failback_session', 'driver_exit_code', 'int NULL AFTER `failed_component`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_failback_session', 'baseline_file_state', 'varchar(32) NULL AFTER `driver_exit_code`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_failback_session', 'operation_intent', 'varchar(32) NULL AFTER `baseline_file_state`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_failback_session', 'requested_mode', 'varchar(32) NULL AFTER `operation_intent`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_failback_session', 'effective_mode', 'varchar(32) NULL AFTER `requested_mode`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_failback_session', 'mode_decision_code', 'varchar(64) NULL AFTER `effective_mode`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_failback_session', 'initial_seed_required', 'tinyint(1) NULL AFTER `mode_decision_code`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_failback_session', 'source_disk_probe_state', 'varchar(32) NULL AFTER `initial_seed_required`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_failback_session', 'source_disk_count', 'int NULL AFTER `source_disk_probe_state`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_failback_session', 'target_writer_probe_state', 'varchar(32) NULL AFTER `source_disk_count`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_failback_session', 'estimated_virtual_bytes', 'bigint unsigned NULL AFTER `target_writer_probe_state`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_failback_session', 'worker_pid_alive', 'tinyint(1) NULL AFTER `baseline_file_state`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_failback_session', 'commit_contract_version', 'varchar(32) NULL AFTER `commit_outcome`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_failback_session', 'commit_envelope_sha256', 'char(64) NULL AFTER `commit_contract_version`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_failback_session', 'commit_dispatch_state', 'varchar(32) NULL AFTER `commit_envelope_sha256`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_failback_session', 'commit_probe_count', 'int NULL AFTER `commit_dispatch_state`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_failback_session', 'commit_dispatched_at', 'datetime NULL AFTER `commit_verified_at`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_failback_session', 'commit_probe_deadline_at', 'datetime NULL AFTER `commit_dispatched_at`');
-- Cross-hypervisor DR target ownership and materialization contract v2.
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_replica', 'ownership_generation', 'bigint unsigned NOT NULL DEFAULT 1 AFTER `runtime_state_json`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_replica', 'ownership_state', 'varchar(32) NULL AFTER `ownership_generation`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_replica', 'materialization_digest', 'char(64) NULL AFTER `ownership_state`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_replica', 'power_state_observed_at', 'datetime NULL AFTER `materialization_digest`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_replica_disk', 'target_claim_id', 'bigint unsigned NULL AFTER `details_json`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_replica_disk', 'artifact_uuid', 'varchar(40) NULL AFTER `target_claim_id`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_replica_disk', 'locator_hash', 'char(64) NULL AFTER `artifact_uuid`');

CREATE TABLE IF NOT EXISTS `cloud`.`dr_target_resource_claim` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `uuid` varchar(40) NOT NULL,
  `plan_id` bigint unsigned NOT NULL,
  `replica_id` bigint unsigned NOT NULL,
  `replica_disk_id` bigint unsigned DEFAULT NULL,
  `claim_run_id` bigint unsigned DEFAULT NULL,
  `resource_type` varchar(32) NOT NULL,
  `resource_id` bigint unsigned NOT NULL,
  `resource_uuid` varchar(64) DEFAULT NULL,
  `resource_locator_hash` char(64) DEFAULT NULL,
  `ownership_generation` bigint unsigned NOT NULL DEFAULT 1,
  `claim_state` varchar(32) NOT NULL,
  `active_resource_key` varchar(160) DEFAULT NULL,
  `active_role_key` varchar(160) DEFAULT NULL,
  `manifest_sha256` char(64) DEFAULT NULL,
  `created` datetime NOT NULL,
  `updated` datetime NOT NULL,
  `released` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dr_target_claim_uuid` (`uuid`),
  UNIQUE KEY `uk_dr_target_claim_active_resource` (`active_resource_key`),
  UNIQUE KEY `uk_dr_target_claim_active_role` (`active_role_key`),
  KEY `idx_dr_target_claim_plan` (`plan_id`,`claim_state`),
  KEY `idx_dr_target_claim_replica` (`replica_id`,`claim_state`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- Backfill ownership from immutable VM details. Conflicting active replicas are
-- quarantined; the operating VM and attached volumes are never reassigned.
INSERT IGNORE INTO `cloud`.`dr_target_resource_claim`
  (`uuid`,`plan_id`,`replica_id`,`replica_disk_id`,`claim_run_id`,`resource_type`,`resource_id`,
   `resource_uuid`,`resource_locator_hash`,`ownership_generation`,`claim_state`,`active_resource_key`,
   `active_role_key`,`created`,`updated`)
SELECT UUID(), r.plan_id, r.id, NULL, NULL, 'VM', r.target_vm_id, v.uuid, NULL,
       COALESCE(r.ownership_generation, 1),
       IF(r.removed IS NULL, 'CLAIMED', 'DETACHED_OPERATIONAL'),
       CONCAT('VM:', r.target_vm_id), CONCAT('PLAN:', r.plan_id, ':REPLICA:', r.id, ':TARGET_VM'), NOW(), NOW()
FROM `cloud`.`dr_replica` r
JOIN `cloud`.`vm_instance` v ON v.id = r.target_vm_id AND v.removed IS NULL
JOIN `cloud`.`vm_instance_details` d ON d.vm_id = v.id AND d.name = 'dr.plan.id'
WHERE r.target_vm_id IS NOT NULL
  AND CAST(d.value AS UNSIGNED) = r.plan_id
  AND NOT EXISTS (
    SELECT 1 FROM `cloud`.`dr_replica` newer
    WHERE newer.target_vm_id = r.target_vm_id AND newer.plan_id = r.plan_id AND newer.id > r.id
  );

INSERT IGNORE INTO `cloud`.`dr_target_resource_claim`
  (`uuid`,`plan_id`,`replica_id`,`replica_disk_id`,`claim_run_id`,`resource_type`,`resource_id`,
   `resource_uuid`,`resource_locator_hash`,`ownership_generation`,`claim_state`,`active_resource_key`,
   `active_role_key`,`created`,`updated`)
SELECT UUID(), r.plan_id, r.id, rd.id, NULL, 'VOLUME', rd.target_volume_id, v.uuid,
       SHA2(COALESCE(v.path, v.uuid), 256), COALESCE(r.ownership_generation, 1),
       IF(r.removed IS NULL, 'CLAIMED', 'DETACHED_OPERATIONAL'),
       CONCAT('VOLUME:', rd.target_volume_id), CONCAT('PLAN:', r.plan_id, ':REPLICA:', r.id, ':TARGET_DISK:', rd.id),
       NOW(), NOW()
FROM `cloud`.`dr_replica_disk` rd
JOIN `cloud`.`dr_replica` r ON r.id = rd.replica_id
JOIN `cloud`.`volumes` v ON v.id = rd.target_volume_id AND v.removed IS NULL
LEFT JOIN `cloud`.`vm_instance_details` d ON d.vm_id = v.instance_id AND d.name = 'dr.plan.id'
WHERE rd.target_volume_id IS NOT NULL
  AND (v.instance_id IS NULL OR CAST(d.value AS UNSIGNED) = r.plan_id);

UPDATE `cloud`.`dr_replica_disk` rd
JOIN `cloud`.`dr_target_resource_claim` c
  ON c.replica_disk_id = rd.id AND c.resource_type = 'VOLUME' AND c.released IS NULL
SET rd.target_claim_id = c.id,
    rd.artifact_uuid = COALESCE(rd.artifact_uuid, c.resource_uuid),
    rd.locator_hash = COALESCE(rd.locator_hash, c.resource_locator_hash);

UPDATE `cloud`.`dr_plan` p
JOIN `cloud`.`dr_replica` r ON r.plan_id = p.id AND r.removed IS NULL
JOIN `cloud`.`vm_instance_details` d ON d.vm_id = r.target_vm_id AND d.name = 'dr.plan.id'
SET p.state = 'ERROR', p.admin_state = 'DISABLED',
    p.last_error_code = 'DR_TARGET_OWNERSHIP_CONFLICT',
    p.last_error_message = CONCAT('Target VM ', r.target_vm_id, ' is owned by DR plan ', d.value),
    p.updated = NOW()
WHERE r.target_vm_id IS NOT NULL AND CAST(d.value AS UNSIGNED) <> p.id;

UPDATE `cloud`.`dr_replica` r
JOIN `cloud`.`vm_instance_details` d ON d.vm_id = r.target_vm_id AND d.name = 'dr.plan.id'
SET r.state = 'ERROR', r.ownership_state = 'QUARANTINED', r.updated = NOW()
WHERE r.removed IS NULL AND r.target_vm_id IS NOT NULL AND CAST(d.value AS UNSIGNED) <> r.plan_id;

UPDATE `cloud`.`dr_replica` r
JOIN `cloud`.`vm_instance_details` d ON d.vm_id = r.target_vm_id AND d.name = 'dr.plan.id'
SET r.ownership_state = 'VALID', r.updated = NOW()
WHERE r.target_vm_id IS NOT NULL AND CAST(d.value AS UNSIGNED) = r.plan_id
  AND (r.ownership_state IS NULL OR r.ownership_state <> 'QUARANTINED');
-- Storage Service protocol/share/target/rule/domain JSON payloads are larger
-- than the default JPA string width. Keep the columns as MEDIUMTEXT on upgraded
-- Europa environments so endpoint, volume, authentication, and runtime metadata
-- are not truncated.
SET @storage_service_config_json_ddl = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = 'cloud' AND TABLE_NAME = 'storage_service_protocol' AND COLUMN_NAME = 'config_json') > 0,
    'ALTER TABLE `cloud`.`storage_service_protocol` MODIFY COLUMN `config_json` mediumtext DEFAULT NULL',
    'SELECT 1');
PREPARE stmt FROM @storage_service_config_json_ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @storage_service_config_json_ddl = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = 'cloud' AND TABLE_NAME = 'storage_file_share' AND COLUMN_NAME = 'config_json') > 0,
    'ALTER TABLE `cloud`.`storage_file_share` MODIFY COLUMN `config_json` mediumtext DEFAULT NULL',
    'SELECT 1');
PREPARE stmt FROM @storage_service_config_json_ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @storage_service_config_json_ddl = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = 'cloud' AND TABLE_NAME = 'storage_block_target' AND COLUMN_NAME = 'config_json') > 0,
    'ALTER TABLE `cloud`.`storage_block_target` MODIFY COLUMN `config_json` mediumtext DEFAULT NULL',
    'SELECT 1');
PREPARE stmt FROM @storage_service_config_json_ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @storage_service_config_json_ddl = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = 'cloud' AND TABLE_NAME = 'storage_access_rule' AND COLUMN_NAME = 'config_json') > 0,
    'ALTER TABLE `cloud`.`storage_access_rule` MODIFY COLUMN `config_json` mediumtext DEFAULT NULL',
    'SELECT 1');
PREPARE stmt FROM @storage_service_config_json_ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @storage_service_config_json_ddl = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = 'cloud' AND TABLE_NAME = 'storage_identity_domain' AND COLUMN_NAME = 'config_json') > 0,
    'ALTER TABLE `cloud`.`storage_identity_domain` MODIFY COLUMN `config_json` mediumtext DEFAULT NULL',
    'SELECT 1');
PREPARE stmt FROM @storage_service_config_json_ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
