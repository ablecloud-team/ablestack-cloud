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



-- Same-version Europa S5B storage and backup migration. Safe to resume after DDL commits.

-- Add management_server_details table to allow ManagementServer scope configs
CREATE TABLE IF NOT EXISTS `cloud`.`management_server_details` (
                                                           `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'id',
                                                           `management_server_id` bigint unsigned NOT NULL COMMENT 'management server the detail is related to',
                                                           `name` varchar(255) NOT NULL COMMENT 'name of the detail',
    `value` varchar(255) NOT NULL,
    `display` tinyint(1) NOT NULL DEFAULT '1' COMMENT 'True if the detail can be displayed to the end user',
    PRIMARY KEY (`id`),
    CONSTRAINT `fk_management_server_details__management_server_id` FOREIGN KEY `fk_management_server_details__management_server_id`(`management_server_id`) REFERENCES `cloud`.`mshost`(`id`) ON DELETE CASCADE,
    KEY `i_management_server_details__name__value` (`name`(128),`value`(128))
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8;

-- Add checkpoint tracking fields to backups table for incremental backup support
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.backups', 'from_checkpoint_id', 'VARCHAR(255) DEFAULT NULL COMMENT "Previous active checkpoint id for incremental backups"');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.backups', 'to_checkpoint_id', 'VARCHAR(255) DEFAULT NULL COMMENT "New checkpoint id created for the next incremental backup"');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.backups', 'checkpoint_create_time', 'BIGINT DEFAULT NULL COMMENT "Checkpoint creation timestamp from libvirt"');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.backups', 'host_id', 'BIGINT UNSIGNED DEFAULT NULL COMMENT "Host where backup is running"');

-- Create image_transfer table for per-disk image transfers
CREATE TABLE IF NOT EXISTS `cloud`.`image_transfer`(
    `id` bigint unsigned NOT NULL auto_increment COMMENT 'id',
    `uuid` varchar(40) NOT NULL COMMENT 'uuid',
    `account_id` bigint unsigned NOT NULL COMMENT 'Account ID',
    `domain_id` bigint unsigned NOT NULL COMMENT 'Domain ID',
    `data_center_id` bigint unsigned NOT NULL COMMENT 'Data Center ID',
    `backup_id` bigint unsigned COMMENT 'Backup ID',
    `volume_id` bigint unsigned NOT NULL COMMENT 'Volume ID',
    `host_id` bigint unsigned NOT NULL COMMENT 'Host ID',
    `transfer_url` varchar(255) COMMENT 'ImageIO transfer URL',
    `file` varchar(255) COMMENT 'File for the file backend',
    `phase` varchar(20) NOT NULL COMMENT 'Transfer phase: initializing, transferring, finished, failed',
    `socket` varchar(255) COMMENT 'Unix socket for nbd backend',
    `direction` varchar(20) NOT NULL COMMENT 'Direction: upload, download',
    `backend` varchar(20) NOT NULL COMMENT 'Backend: nbd, file',
    `progress` int COMMENT 'Transfer progress percentage (0-100)',
    `signed_ticket_id` varchar(255) COMMENT 'Signed ticket ID from ImageIO',
    `created` datetime NOT NULL COMMENT 'date created',
    `updated` datetime COMMENT 'date updated if not null',
    `removed` datetime COMMENT 'date removed if not null',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uuid` (`uuid`),
    CONSTRAINT `fk_image_transfer__backup_id` FOREIGN KEY (`backup_id`) REFERENCES `backups`(`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_image_transfer__volume_id` FOREIGN KEY (`volume_id`) REFERENCES `volumes`(`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_image_transfer__host_id` FOREIGN KEY (`host_id`) REFERENCES `host`(`id`) ON DELETE CASCADE,
    INDEX `i_image_transfer__backup_id`(`backup_id`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8;

-- KBOSS

CREATE TABLE IF NOT EXISTS `cloud`.`internal_backup_pool_ref` (
    `id` bigint NOT NULL UNIQUE AUTO_INCREMENT,
    `backup_id` bigint unsigned NOT NULL COMMENT 'The backup ID. Foreign key that points to the backups table.',
    `storage_pool_id` bigint unsigned NOT NULL COMMENT 'The storage ID. Foreign key that points to the storage_pool table.',
    `volume_id` bigint unsigned NOT NULL COMMENT 'The volumes ID. Foreign key that points to the volumes table.',
    `backup_delta_path` varchar(255) COMMENT 'Path of the created delta.',
    `backup_parent_path` varchar(255) COMMENT 'Path of the created delta parent.',
    PRIMARY KEY (`id`),
    CONSTRAINT `fk_internal_backup_pool_ref__backup_id` FOREIGN KEY (`backup_id`) REFERENCES `backups`(`id`),
    CONSTRAINT `fk_internal_backup_pool_ref__storage_pool_id` FOREIGN KEY (`storage_pool_id`) REFERENCES `storage_pool`(`id`),
    CONSTRAINT `fk_internal_backup_pool_ref__volume_id` FOREIGN KEY (`volume_id`) REFERENCES `volumes`(`id`)
    );

CREATE TABLE IF NOT EXISTS `cloud`.`internal_backup_store_ref` (
     `id` bigint NOT NULL UNIQUE AUTO_INCREMENT,
     `backup_id` bigint unsigned NOT NULL COMMENT 'The backup ID. Foreign key that points to the backups table.',
     `volume_id` bigint unsigned NOT NULL COMMENT 'The volume ID. Foreign key that points to the volumes table.',
     `device_id` bigint unsigned COMMENT 'device ID of the volume',
     `path` varchar(255) COMMENT 'Path of the backup.',
    PRIMARY KEY (`id`),
    CONSTRAINT `fk_internal_backup_store_ref__backup_id` FOREIGN KEY (`backup_id`) REFERENCES `backups`(`id`),
    CONSTRAINT `fk_internal_backup_store_ref__volume_id` FOREIGN KEY (`volume_id`) REFERENCES `volumes`(`id`)
    );

CREATE TABLE IF NOT EXISTS `cloud`.`internal_backup_service_job` (
    `id` bigint NOT NULL UNIQUE AUTO_INCREMENT,
    `backup_id` bigint unsigned NOT NULL COMMENT 'The backup ID. Foreign key that points to the backups table.',
    `instance_id` bigint unsigned NOT NULL COMMENT 'The instance ID. Foreign key that points to the vm_instance table.',
    `account_id` bigint(20) unsigned COMMENT 'Account ID of the owner of the VM.',
    `host_id` bigint unsigned COMMENT 'The host ID that is executing the compression. Foreign key that points to the host table.',
    `zone_id` bigint unsigned NOT NULL COMMENT 'The zone ID of the where the VM is. Foreign key that points to the data_center table',
    `attempts` int(32) unsigned NOT NULL DEFAULT 0,
    `type` varchar(55) NOT NULL,
    `created` datetime NOT NULL,
    `scheduled_start_time` datetime NOT NULL,
    `start_time` datetime,
    `removed` datetime,
    PRIMARY KEY (`id`),
    CONSTRAINT `fk_internal_backup_service_job__backup_id` FOREIGN KEY (`backup_id`) REFERENCES `backups`(`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_internal_backup_service_job__instance_id` FOREIGN KEY (`instance_id`) REFERENCES `vm_instance`(`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_internal_backup_service_job__host_id` FOREIGN KEY (`host_id`) REFERENCES `host`(`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_internal_backup_service_job__zone_id` FOREIGN KEY (`zone_id`) REFERENCES `data_center`(`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_internal_backup_service_job__account_id` FOREIGN KEY (`account_id`) REFERENCES `account`(`id`) ON DELETE CASCADE
    );

CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.backups', 'uncompressed_size', 'bigint unsigned');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.backups', 'compression_status', 'varchar(55)');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.backups', 'validation_status', 'varchar(55)');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.backup_schedule', 'isolated', 'TINYINT(1) NOT NULL DEFAULT 0 COMMENT "Whether the scheduled backups will be isolated or not."');

UPDATE cloud.configuration
SET value = CONCAT(value, IF(value = '', '', ', '), 'backupValidationCommandTimeout')
WHERE name = 'user.vm.readonly.details' AND value IS NOT NULL
  AND FIND_IN_SET('backupValidationCommandTimeout', REPLACE(value, ' ', '')) = 0;
UPDATE cloud.configuration
SET value = CONCAT(value, IF(value = '', '', ', '), 'backupValidationScreenshotWait')
WHERE name = 'user.vm.readonly.details' AND value IS NOT NULL
  AND FIND_IN_SET('backupValidationScreenshotWait', REPLACE(value, ' ', '')) = 0;
UPDATE cloud.configuration
SET value = CONCAT(value, IF(value = '', '', ', '), 'backupValidationBootTimeout')
WHERE name = 'user.vm.readonly.details' AND value IS NOT NULL
  AND FIND_IN_SET('backupValidationBootTimeout', REPLACE(value, ' ', '')) = 0;

-- Create the KBOSS view only after the named phase creates its dependencies.
CREATE OR REPLACE VIEW `cloud`.`internal_backup_view` AS
SELECT  b.id,
        b.uuid,
        b.vm_id,
        b.backed_volumes,
        b.type,
        b.date,
        b.status,
        b.compression_status,
        b.backup_offering_id,
        b.size,
        b.protected_size,
        b.zone_id,
        MAX(CASE WHEN bd.name = 'image_store_id' THEN bd.value END) image_store_id,
        MAX(CASE WHEN bd.name = 'parent_id' THEN bd.value END) parent_id,
        MAX(CASE WHEN bd.name = 'end_of_chain' THEN bd.value END) end_of_chain,
        MAX(CASE WHEN bd.name = 'current' THEN bd.value END) current,
        COALESCE(MAX(CASE WHEN bd.name = 'isolated' THEN bd.value END), 'false') isolated,
        nbsr.volume_id,
        MAX(nbsr.path) image_store_path
FROM    backups b
LEFT    JOIN backup_details bd ON b.id = bd.backup_id
LEFT    JOIN backup_offering bo ON b.backup_offering_id = bo.id
LEFT    JOIN internal_backup_store_ref nbsr ON b.id = nbsr.backup_id
WHERE   bo.provider='kboss'
GROUP BY b.id, nbsr.volume_id;
