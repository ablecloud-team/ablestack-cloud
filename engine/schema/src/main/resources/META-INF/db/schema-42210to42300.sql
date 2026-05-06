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
-- Schema upgrade from 4.22.1.0 to 4.23.0.0
--;

CALL `cloud`.`ADD_GUEST_OS_AND_HYPERVISOR_MAPPING` (13, 'Rocky Linux 10', 'KVM', 'default', 'Rocky Linux 10');

CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.oauth_provider', 'domain_id', 'bigint unsigned DEFAULT NULL COMMENT "NULL for global provider, domain ID for domain-specific" AFTER `redirect_uri`');
CALL `cloud`.`IDEMPOTENT_ADD_FOREIGN_KEY`('cloud.oauth_provider', 'fk_oauth_provider__domain_id', '(`domain_id`)', '`domain`(`id`)');
CALL `cloud`.`IDEMPOTENT_ADD_KEY`('i_oauth_provider__domain_id', 'cloud.oauth_provider', '(`domain_id`)');

CALL `cloud`.`IDEMPOTENT_ADD_UNIQUE_KEY`('cloud.oauth_provider', 'uk_oauth_provider__provider_domain', '(`provider`, `domain_id`)');

CREATE TABLE IF NOT EXISTS `cloud`.`backup_offering_details` (
    `id` bigint unsigned NOT NULL auto_increment,
    `backup_offering_id` bigint unsigned NOT NULL COMMENT 'Backup offering id',
    `name` varchar(255) NOT NULL,
    `value` varchar(1024) NOT NULL,
    `display` tinyint(1) NOT NULL DEFAULT 1 COMMENT 'Should detail be displayed to the end user',
    PRIMARY KEY (`id`),
    CONSTRAINT `fk_offering_details__backup_offering_id` FOREIGN KEY `fk_offering_details__backup_offering_id`(`backup_offering_id`) REFERENCES `backup_offering`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

-- Update value to random for the config 'vm.allocation.algorithm' or 'volume.allocation.algorithm' if configured as userconcentratedpod_random
-- Update value to firstfit for the config 'vm.allocation.algorithm' or 'volume.allocation.algorithm' if configured as userconcentratedpod_firstfit
UPDATE `cloud`.`configuration` SET value='random' WHERE name IN ('vm.allocation.algorithm', 'volume.allocation.algorithm') AND value='userconcentratedpod_random' AND value <> 'random';
UPDATE `cloud`.`configuration` SET value='firstfit' WHERE name IN ('vm.allocation.algorithm', 'volume.allocation.algorithm') AND value='userconcentratedpod_firstfit' AND value <> 'firstfit';

-- Add Windows Server 2025 guest OS and mappings
CALL ADD_GUEST_OS_AND_HYPERVISOR_MAPPING (6, 'Windows Server 2025 (64-bit)', 'VMware', '7.0', 'windows2022srvNext_64Guest');
CALL ADD_GUEST_OS_AND_HYPERVISOR_MAPPING (6, 'Windows Server 2025 (64-bit)', 'VMware', '7.0.1.0', 'windows2022srvNext_64Guest');
CALL ADD_GUEST_OS_AND_HYPERVISOR_MAPPING (6, 'Windows Server 2025 (64-bit)', 'VMware', '7.0.2.0', 'windows2022srvNext_64Guest');
CALL ADD_GUEST_OS_AND_HYPERVISOR_MAPPING (6, 'Windows Server 2025 (64-bit)', 'VMware', '7.0.3.0', 'windows2022srvNext_64Guest');
CALL ADD_GUEST_OS_AND_HYPERVISOR_MAPPING (6, 'Windows Server 2025 (64-bit)', 'VMware', '8.0', 'windows2022srvNext_64Guest');
CALL ADD_GUEST_OS_AND_HYPERVISOR_MAPPING (6, 'Windows Server 2025 (64-bit)', 'VMware', '8.0.0.1', 'windows2022srvNext_64Guest');
CALL ADD_GUEST_OS_AND_HYPERVISOR_MAPPING (6, 'Windows Server 2025 (64-bit)', 'VMware', '8.0.0.2', 'windows2022srvNext_64Guest');
CALL ADD_GUEST_OS_AND_HYPERVISOR_MAPPING (6, 'Windows Server 2025 (64-bit)', 'VMware', '8.0.0.3', 'windows2022srvNext_64Guest');
CALL ADD_GUEST_OS_AND_HYPERVISOR_MAPPING (6, 'Windows Server 2025 (64-bit)', 'VMware', '8.0.1', 'windows2022srvNext_64Guest');
CALL ADD_GUEST_OS_AND_HYPERVISOR_MAPPING (6, 'Windows Server 2025 (64-bit)', 'VMware', '8.0.2', 'windows2022srvNext_64Guest');
CALL ADD_GUEST_OS_AND_HYPERVISOR_MAPPING (6, 'Windows Server 2025 (64-bit)', 'VMware', '8.0.3', 'windows2022srvNext_64Guest');

-- Create kubernetes_cluster_affinity_group_map table for CKS per-node-type affinity groups
CREATE TABLE IF NOT EXISTS `cloud`.`kubernetes_cluster_affinity_group_map` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `cluster_id` bigint unsigned NOT NULL COMMENT 'kubernetes cluster id',
    `node_type` varchar(32) NOT NULL COMMENT 'CONTROL, WORKER, or ETCD',
    `affinity_group_id` bigint unsigned NOT NULL COMMENT 'affinity group id',
    PRIMARY KEY (`id`),
    CONSTRAINT `fk_kubernetes_cluster_ag_map__cluster_id` FOREIGN KEY (`cluster_id`) REFERENCES `kubernetes_cluster`(`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_kubernetes_cluster_ag_map__ag_id` FOREIGN KEY (`affinity_group_id`) REFERENCES `affinity_group`(`id`) ON DELETE CASCADE,
    INDEX `i_kubernetes_cluster_ag_map__cluster_id`(`cluster_id`),
    INDEX `i_kubernetes_cluster_ag_map__ag_id`(`affinity_group_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

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

UPDATE `cloud`.`dr_restore_point` rp
JOIN (SELECT `plan_id`, MAX(`id`) AS `run_id` FROM `cloud`.`dr_run` WHERE `removed` IS NULL GROUP BY `plan_id`) latest_run
  ON latest_run.`plan_id` = rp.`plan_id`
SET rp.`run_id` = latest_run.`run_id`
WHERE rp.`removed` IS NULL AND rp.`run_id` IS NULL;

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
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'transfer_progress_schema_version', 'int unsigned NULL AFTER `transfer_payload_bytes`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'transfer_cycle_sequence', 'bigint unsigned NULL AFTER `transfer_progress_schema_version`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'transfer_sample_sequence', 'bigint unsigned NULL AFTER `transfer_cycle_sequence`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'transfer_phase', 'varchar(32) NULL AFTER `transfer_sample_sequence`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'transfer_mode', 'varchar(32) NULL AFTER `transfer_phase`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'transfer_bytes_total', 'bigint unsigned NULL AFTER `transfer_mode`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'transfer_bytes_processed', 'bigint unsigned NULL AFTER `transfer_bytes_total`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'transfer_source_read_bytes', 'bigint unsigned NULL AFTER `transfer_bytes_processed`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'transfer_target_written_bytes', 'bigint unsigned NULL AFTER `transfer_source_read_bytes`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'transfer_verified_bytes', 'bigint unsigned NULL AFTER `transfer_target_written_bytes`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'transfer_percent', 'decimal(5,2) NULL AFTER `transfer_verified_bytes`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'transfer_throughput_bps', 'bigint unsigned NULL AFTER `transfer_percent`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'transfer_eta_seconds', 'bigint unsigned NULL AFTER `transfer_throughput_bps`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'transfer_current_disk_index', 'int unsigned NULL AFTER `transfer_eta_seconds`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'transfer_disk_count', 'int unsigned NULL AFTER `transfer_current_disk_index`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'transfer_progress_estimated', 'tinyint(1) NULL AFTER `transfer_disk_count`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'transfer_progress_sampled_at', 'datetime NULL AFTER `transfer_progress_estimated`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan_runtime', 'transfer_progress_stale', 'tinyint(1) NULL AFTER `transfer_progress_sampled_at`');
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

-- Create webhook_filter table
CREATE TABLE IF NOT EXISTS `cloud`.`webhook_filter` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'id of the webhook filter',
    `uuid` varchar(255) COMMENT 'uuid of the webhook filter',
    `webhook_id` bigint unsigned  NOT NULL COMMENT 'id of the webhook',
    `type` varchar(20) COMMENT 'type of the filter',
    `mode` varchar(20) COMMENT 'mode of the filter',
    `match_type` varchar(20) COMMENT 'match type of the filter',
    `value` varchar(256) NOT NULL COMMENT 'value of the filter used for matching',
    `created` datetime NOT NULL COMMENT 'date created',
    PRIMARY KEY (`id`),
    INDEX `i_webhook_filter__webhook_id`(`webhook_id`),
    CONSTRAINT `fk_webhook_filter__webhook_id` FOREIGN KEY(`webhook_id`) REFERENCES `webhook`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- "api_keypair" table for API and secret keys
CREATE TABLE IF NOT EXISTS `cloud`.`api_keypair` (
    `id` bigint(20) unsigned NOT NULL auto_increment,
    `uuid` varchar(40) UNIQUE NOT NULL,
    `name` varchar(255) NOT NULL,
    `domain_id` bigint(20) unsigned NOT NULL,
    `account_id` bigint(20) unsigned NOT NULL,
    `user_id` bigint(20) unsigned NOT NULL,
    `start_date` datetime,
    `end_date` datetime,
    `description` varchar(1024),
    `api_key` varchar(255) NOT NULL,
    `secret_key` varchar(255) NOT NULL,
    `created` datetime NOT NULL,
    `removed` datetime,
    PRIMARY KEY (`id`),
    CONSTRAINT `fk_api_keypair__user_id` FOREIGN KEY(`user_id`) REFERENCES `cloud`.`user`(`id`),
    CONSTRAINT `fk_api_keypair__account_id` FOREIGN KEY(`account_id`) REFERENCES `cloud`.`account`(`id`),
    CONSTRAINT `fk_api_keypair__domain_id` FOREIGN KEY(`domain_id`) REFERENCES `cloud`.`domain`(`id`)
);

-- "api_keypair_permissions" table for API key pairs permissions
CREATE TABLE IF NOT EXISTS `cloud`.`api_keypair_permissions` (
    `id` bigint(20) unsigned NOT NULL auto_increment,
    `uuid` varchar(40) UNIQUE,
    `sort_order` bigint(20) unsigned NOT NULL DEFAULT 0,
    `rule` varchar(255) NOT NULL,
    `api_keypair_id` bigint(20) unsigned NOT NULL,
    `permission` varchar(255) NOT NULL,
    `description` varchar(255),
    PRIMARY KEY (`id`),
    CONSTRAINT `fk_keypair_permissions__api_keypair_id` FOREIGN KEY(`api_keypair_id`) REFERENCES `cloud`.`api_keypair`(`id`)
);

-- Existing user API keys are migrated by Upgrade42210to42300.performDataMigration().
-- The Java migration uses DBEncryptionUtil so api_keypair.secret_key is stored in
-- the format expected by the @Encrypt entity field. Legacy columns remain in place
-- until the cleanup phase, allowing a failed migration to be retried safely.

-- Grant access to the "deleteUserKeys" and "listUserKeyRules" APIs to the "User", "Domain Admin" and "Resource Admin" roles, similarly to the "registerUserKeys" API
CALL `cloud`.`IDEMPOTENT_UPDATE_API_PERMISSION`('User', 'deleteUserKeys', 'ALLOW');
CALL `cloud`.`IDEMPOTENT_UPDATE_API_PERMISSION`('Domain Admin', 'deleteUserKeys', 'ALLOW');
CALL `cloud`.`IDEMPOTENT_UPDATE_API_PERMISSION`('Resource Admin', 'deleteUserKeys', 'ALLOW');

CALL `cloud`.`IDEMPOTENT_UPDATE_API_PERMISSION`('User', 'listUserKeyRules', 'ALLOW');
CALL `cloud`.`IDEMPOTENT_UPDATE_API_PERMISSION`('Domain Admin', 'listUserKeyRules', 'ALLOW');
CALL `cloud`.`IDEMPOTENT_UPDATE_API_PERMISSION`('Resource Admin', 'listUserKeyRules', 'ALLOW');

-- Add conserve mode for VPC offerings
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.vpc_offerings','conserve_mode', 'tinyint(1) unsigned NULL DEFAULT 0 COMMENT ''True if the VPC offering is IP conserve mode enabled, allowing public IP services to be used across multiple VPC tiers'' ');

-- Disable/enable NICs
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.nics','enabled', 'TINYINT(1) NOT NULL DEFAULT 1 COMMENT ''Indicates whether the NIC is enabled or not'' ');
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
  `id` bigint unsigned NOT NULL AUTO_INCREMENT, `uuid` varchar(40) NOT NULL,
  `plan_id` bigint unsigned NOT NULL, `run_id` bigint unsigned NOT NULL, `cleanup_run_id` bigint unsigned DEFAULT NULL,
  `state` varchar(64) NOT NULL, `network_mode` varchar(32) DEFAULT NULL, `network_id` bigint unsigned DEFAULT NULL,
  `target_vm_id` bigint unsigned DEFAULT NULL, `target_vm_uuid` varchar(40) DEFAULT NULL, `target_vm_name` varchar(255) DEFAULT NULL,
  `checkpoint_sequence` bigint unsigned DEFAULT NULL, `restore_point_ref` varchar(1024) DEFAULT NULL,
  `validation_mode` varchar(32) DEFAULT NULL, `boot_timeout_seconds` int unsigned DEFAULT NULL,
  `artifact_contract_version` varchar(16) DEFAULT NULL, `artifact_manifest` mediumtext, `boot_validation_state` varchar(64) DEFAULT NULL,
  `cleanup_required` tinyint(1) NOT NULL DEFAULT 0, `error_code` varchar(128) DEFAULT NULL, `error_message` varchar(1024) DEFAULT NULL,
  `details_json` mediumtext, `created` datetime NOT NULL, `updated` datetime NOT NULL, `removed` datetime DEFAULT NULL,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_dr_test_session_uuid` (`uuid`), KEY `idx_dr_test_session_plan_active` (`plan_id`,`removed`),
  KEY `idx_dr_test_session_run` (`run_id`), KEY `idx_dr_test_session_vm` (`target_vm_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_test_session', 'restore_point_ref', 'varchar(1024) NULL AFTER `checkpoint_sequence`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_test_session', 'validation_mode', 'varchar(32) NULL AFTER `restore_point_ref`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_test_session', 'boot_timeout_seconds', 'int unsigned NULL AFTER `validation_mode`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_test_session', 'artifact_contract_version', 'varchar(16) NULL AFTER `boot_timeout_seconds`');

CREATE TABLE IF NOT EXISTS `cloud`.`dr_test_disk` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT, `session_id` bigint unsigned NOT NULL, `disk_index` int NOT NULL,
  `provider` varchar(32) DEFAULT NULL, `artifact_ref` varchar(1024) DEFAULT NULL, `target_volume_id` bigint unsigned DEFAULT NULL,
  `target_volume_uuid` varchar(40) DEFAULT NULL, `state` varchar(64) NOT NULL, `details_json` mediumtext,
  `created` datetime NOT NULL, `updated` datetime NOT NULL, `removed` datetime DEFAULT NULL,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_dr_test_disk_session_index` (`session_id`,`disk_index`), KEY `idx_dr_test_disk_volume` (`target_volume_id`),
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

-- Bounded DR fleet admission. Expired ACTIVE leases are ignored and can be
-- reclaimed after a management-server restart without changing a plan state.
CREATE TABLE IF NOT EXISTS `cloud`.`dr_resource_lease` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `uuid` varchar(40) NOT NULL,
  `resource_key` varchar(160) NOT NULL,
  `operation_class` varchar(32) NOT NULL,
  `plan_id` bigint unsigned NOT NULL,
  `run_id` bigint unsigned NOT NULL,
  `state` varchar(32) NOT NULL,
  `expires_at` datetime NOT NULL,
  `created` datetime NOT NULL,
  `updated` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dr_resource_lease_uuid` (`uuid`),
  KEY `idx_dr_resource_lease_capacity` (`resource_key`,`state`,`expires_at`),
  KEY `idx_dr_resource_lease_run` (`run_id`,`state`,`expires_at`),
  KEY `idx_dr_resource_lease_plan` (`plan_id`,`created`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Provider-neutral protection-group metadata. Null values keep legacy
-- single-plan execution behavior unchanged.
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan', 'protection_group_uuid', 'varchar(40) NULL AFTER `coordinator_worker_host_id`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan', 'protection_group_name', 'varchar(255) NULL AFTER `protection_group_uuid`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan', 'protection_group_order', 'int NULL AFTER `protection_group_name`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan', 'protection_group_max_parallel', 'int NULL AFTER `protection_group_order`');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_plan', 'protection_group_quiesce_required', 'tinyint(1) NULL AFTER `protection_group_max_parallel`');

CREATE TABLE IF NOT EXISTS `cloud`.`dr_group_run` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `uuid` varchar(40) NOT NULL,
  `group_uuid` varchar(40) NOT NULL,
  `group_name` varchar(255) NOT NULL,
  `action` varchar(32) NOT NULL,
  `state` varchar(32) NOT NULL,
  `plan_ids_json` text NOT NULL,
  `progress_json` mediumtext DEFAULT NULL,
  `max_parallel` int NOT NULL DEFAULT 1,
  `quiesce_required` tinyint(1) NOT NULL DEFAULT 0,
  `total_count` int NOT NULL DEFAULT 0,
  `succeeded_count` int NOT NULL DEFAULT 0,
  `failed_count` int NOT NULL DEFAULT 0,
  `created` datetime NOT NULL,
  `updated` datetime NOT NULL,
  `completed` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dr_group_run_uuid` (`uuid`),
  KEY `idx_dr_group_run_group` (`group_uuid`,`created`),
  KEY `idx_dr_group_run_state` (`state`,`updated`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- A durable FTCTL cycle is identified by plan and engine sequence. Scheduler
-- and operation Run UUIDs are producers/observers of that cycle, not identity.
DROP TEMPORARY TABLE IF EXISTS `cloud`.`dr_sync_cycle_canonical`;
CREATE TEMPORARY TABLE `cloud`.`dr_sync_cycle_canonical` (
  `plan_id` bigint unsigned NOT NULL,
  `sequence` bigint unsigned NOT NULL,
  `keep_id` bigint unsigned NOT NULL,
  PRIMARY KEY (`plan_id`, `sequence`)
) ENGINE=InnoDB;

INSERT INTO `cloud`.`dr_sync_cycle_canonical` (`plan_id`, `sequence`, `keep_id`)
SELECT `plan_id`, `sequence`,
       CAST(SUBSTRING_INDEX(GROUP_CONCAT(`id` ORDER BY
         (`removed` IS NULL) DESC,
         (`completed` IS NOT NULL) DESC,
         (`state` IN ('READY','COMPLETED','TARGET_READY')) DESC,
         `id` DESC), ',', 1) AS UNSIGNED)
FROM `cloud`.`dr_sync_cycle`
GROUP BY `plan_id`, `sequence`
HAVING COUNT(*) > 1;

DELETE alias
FROM `cloud`.`dr_sync_cycle` alias
JOIN `cloud`.`dr_sync_cycle_canonical` canonical
  ON canonical.plan_id = alias.plan_id AND canonical.sequence = alias.sequence
WHERE alias.id <> canonical.keep_id;

DROP TEMPORARY TABLE `cloud`.`dr_sync_cycle_canonical`;

SET @dr_cycle_drop_plan_sequence = IF(
  EXISTS (SELECT 1 FROM information_schema.STATISTICS
          WHERE TABLE_SCHEMA='cloud' AND TABLE_NAME='dr_sync_cycle'
            AND INDEX_NAME='i_dr_sync_cycle__plan_sequence'),
  'ALTER TABLE `cloud`.`dr_sync_cycle` DROP INDEX `i_dr_sync_cycle__plan_sequence`', 'SELECT 1');
PREPARE stmt FROM @dr_cycle_drop_plan_sequence; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @dr_cycle_add_plan_sequence = IF(
  EXISTS (SELECT 1 FROM information_schema.STATISTICS
          WHERE TABLE_SCHEMA='cloud' AND TABLE_NAME='dr_sync_cycle'
            AND INDEX_NAME='uk_dr_sync_cycle__plan_sequence'),
  'SELECT 1',
  'ALTER TABLE `cloud`.`dr_sync_cycle` ADD UNIQUE KEY `uk_dr_sync_cycle__plan_sequence` (`plan_id`,`sequence`)');
PREPARE stmt FROM @dr_cycle_add_plan_sequence; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @dr_cycle_drop_plan_run_sequence = IF(
  EXISTS (SELECT 1 FROM information_schema.STATISTICS
          WHERE TABLE_SCHEMA='cloud' AND TABLE_NAME='dr_sync_cycle'
            AND INDEX_NAME='uk_dr_sync_cycle__plan_run_sequence'),
  'ALTER TABLE `cloud`.`dr_sync_cycle` DROP INDEX `uk_dr_sync_cycle__plan_run_sequence`', 'SELECT 1');
PREPARE stmt FROM @dr_cycle_drop_plan_run_sequence; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @dr_cycle_add_plan_run_sequence = IF(
  EXISTS (SELECT 1 FROM information_schema.STATISTICS
          WHERE TABLE_SCHEMA='cloud' AND TABLE_NAME='dr_sync_cycle'
            AND INDEX_NAME='i_dr_sync_cycle__plan_run_sequence'),
  'SELECT 1',
  'ALTER TABLE `cloud`.`dr_sync_cycle` ADD KEY `i_dr_sync_cycle__plan_run_sequence` (`plan_id`,`engine_run_uuid`,`sequence`)');
PREPARE stmt FROM @dr_cycle_add_plan_run_sequence; EXECUTE stmt; DEALLOCATE PREPARE stmt;

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

-- Add URLs for OAuth provider
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.oauth_provider','authorize_url', 'VARCHAR(255) DEFAULT NULL COMMENT ''Authorize URL for OAuth initialization'' ');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.oauth_provider','token_url', 'VARCHAR(255) DEFAULT NULL COMMENT ''Token URL for OAuth finalization'' ');

--- Quota tariff/usage mapping
CREATE TABLE IF NOT EXISTS `cloud_usage`.`quota_tariff_usage` (
    `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
    `tariff_id` bigint(20) unsigned NOT NULL COMMENT 'ID of the tariff of the Quota usage detail calculated, foreign key to quota_tariff table',
    `quota_usage_id` bigint(20) unsigned NOT NULL COMMENT 'ID of the aggregation of Quota usage details, foreign key to quota_usage table',
    `quota_used` decimal(20,8) NOT NULL COMMENT 'Amount of quota used',
    PRIMARY KEY (`id`),
    CONSTRAINT `fk_quota_tariff_usage__tariff_id` FOREIGN KEY (`tariff_id`) REFERENCES `cloud_usage`.`quota_tariff` (`id`),
    CONSTRAINT `fk_quota_tariff_usage__quota_usage_id` FOREIGN KEY (`quota_usage_id`) REFERENCES `cloud_usage`.`quota_usage` (`id`));

--- Quota resource statement
INSERT INTO cloud.role_permissions (uuid, role_id, rule, permission, sort_order)
SELECT uuid(), role_id, 'quotaResourceStatement', permission, sort_order
FROM cloud.role_permissions rp
WHERE rule = 'quotaStatement' AND NOT EXISTS(SELECT 1 FROM cloud.role_permissions rp_ WHERE rp.role_id = rp_.role_id AND rp_.rule = 'quotaResourceStatement');

-- Widen the unique key on cloud_usage.usage_volume to include vm_id, so the two volume
-- usage records introduced in 4.22.1 (cumulative and per-VM) can coexist. See #13399.
CALL `cloud_usage`.`IDEMPOTENT_DROP_INDEX`('id', 'cloud_usage.usage_volume');
CALL `cloud_usage`.`IDEMPOTENT_ADD_UNIQUE_INDEX`('cloud_usage.usage_volume', 'id', '(volume_id ASC, created ASC, vm_id ASC)');

--- Per-VM ISO attachments. user_vm.iso_id remains as the primary/bootable ISO pointer.
CREATE TABLE IF NOT EXISTS `cloud`.`vm_iso_map` (
    `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
    `vm_id` bigint(20) unsigned NOT NULL COMMENT 'foreign key to user_vm',
    `iso_id` bigint(20) unsigned NOT NULL COMMENT 'foreign key to vm_template (ISOs are templates of format ISO)',
    `device_seq` int(10) unsigned NOT NULL COMMENT 'cdrom slot index used to derive the libvirt device label (3=hdc, 4=hdd)',
    `created` datetime NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uc_vm_iso_map__vm_iso` (`vm_id`, `iso_id`),
    UNIQUE KEY `uc_vm_iso_map__vm_seq` (`vm_id`, `device_seq`),
    CONSTRAINT `fk_vm_iso_map__vm_id` FOREIGN KEY (`vm_id`) REFERENCES `cloud`.`user_vm` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_vm_iso_map__iso_id` FOREIGN KEY (`iso_id`) REFERENCES `cloud`.`vm_template` (`id`)
);


-- Creates the 'kvm.memory.dynamic.scaling.capacity' and, for already active ACS environments,
-- initializes it with the value of the setting 'vm.serviceoffering.ram.size.max'
INSERT INTO `cloud`.`configuration` (`category`, `instance`, `component`, `name`, `value`, `default_value`, `updated`, `scope`, `is_dynamic`, `group_id`, `subgroup_id`, `display_text`, `description`)
SELECT 'Advanced', 'DEFAULT', 'CapacityManager', 'kvm.memory.dynamic.scaling.capacity', `cfg`.`value`, 0, NULL, 4, 1, 6, 27,
       'KVM memory dynamic scaling capacity', 'Defines the maximum memory capacity in MiB for which VMs can be dynamically scaled to with KVM. The ''kvm.memory.dynamic.scaling.capacity'' setting''s value will be used to define the value of the ''<maxMemory />'' element of domain XMLs. If it is set to a value less than or equal to ''0'', then the host''s memory capacity will be considered.'
FROM `cloud`.`configuration` `cfg`
WHERE NOT EXISTS (SELECT 1 FROM `cloud`.`configuration` WHERE `name` = 'kvm.memory.dynamic.scaling.capacity')
  AND `cfg`.`name` = 'vm.serviceoffering.ram.size.max';

-- Creates the 'kvm.cpu.dynamic.scaling.capacity' and, for already active ACS environments,
-- initializes it with the value of the setting 'vm.serviceoffering.cpu.cores.max'
INSERT INTO `cloud`.`configuration` (`category`, `instance`, `component`, `name`, `value`, `default_value`, `updated`, `scope`, `is_dynamic`, `group_id`, `subgroup_id`, `display_text`, `description`)
SELECT 'Advanced', 'DEFAULT', 'CapacityManager', 'kvm.cpu.dynamic.scaling.capacity', `cfg`.`value`, 0, NULL, 4, 1, 6, 27,
       'KVM CPU dynamic scaling capacity', 'Defines the maximum vCPU capacity for which VMs can be dynamically scaled to with KVM. The ''kvm.cpu.dynamic.scaling.capacity'' setting''s value will be used to define the value of the ''<vcpu />'' element of domain XMLs. If it is set to a value less than or equal to ''0'', then the host''s CPU cores capacity will be considered.'
FROM `cloud`.`configuration` `cfg`
WHERE NOT EXISTS (SELECT 1 FROM `cloud`.`configuration` WHERE `name` = 'kvm.cpu.dynamic.scaling.capacity')
  AND `cfg`.`name` = 'vm.serviceoffering.cpu.cores.max';

-- Schedule table changes are applied by the retry-safe Europa S5A named phase.
-- Storage/backup changes are applied by the retry-safe Europa S5B named phase.

-- Add the 'keep_mac_address_on_public_nic' column to the 'cloud.networks' and 'cloud.vpc' tables
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.networks', 'keep_mac_address_on_public_nic', 'TINYINT(1) NOT NULL DEFAULT 1');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.vpc', 'keep_mac_address_on_public_nic', 'TINYINT(1) NOT NULL DEFAULT 1');
